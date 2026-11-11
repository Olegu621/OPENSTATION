#include "net/mdns_responder.hpp"
#include "utils/logger.hpp"

#include <arpa/inet.h>
#include <cctype>
#include <cstring>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

namespace openstation {

namespace {

constexpr char kMdnsAddr[] = "224.0.0.251";
constexpr int kMdnsPort = 5353;

void append_u16(std::vector<uint8_t>& buf, uint16_t v) {
    buf.push_back(static_cast<uint8_t>(v >> 8));
    buf.push_back(static_cast<uint8_t>(v & 0xFF));
}

void append_u32(std::vector<uint8_t>& buf, uint32_t v) {
    buf.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(v & 0xFF));
}

void append_dns_name(std::vector<uint8_t>& buf, const std::string& name) {
    size_t start = 0;
    while (start < name.size()) {
        size_t dot = name.find('.', start);
        if (dot == std::string::npos) dot = name.size();
        size_t len = dot - start;
        if (len > 0 && len < 64) {
            buf.push_back(static_cast<uint8_t>(len));
            for (size_t i = start; i < dot; ++i) {
                buf.push_back(static_cast<uint8_t>(name[i]));
            }
        }
        start = dot + 1;
    }
    buf.push_back(0);
}

void append_rr_header(std::vector<uint8_t>& buf, const std::string& name,
                      uint16_t rtype, uint16_t rclass, uint32_t ttl, uint16_t rdlen) {
    append_dns_name(buf, name);
    append_u16(buf, rtype);
    append_u16(buf, rclass);
    append_u32(buf, ttl);
    append_u16(buf, rdlen);
}

void append_txt_str(std::vector<uint8_t>& rdata, const std::string& kv) {
    size_t l = std::min<size_t>(kv.size(), 255);
    rdata.push_back(static_cast<uint8_t>(l));
    rdata.insert(rdata.end(), kv.begin(), kv.begin() + l);
}

std::string sanitize_label(const std::string& s) {
    std::string out;
    for (char c : s) {
        if (std::isalnum(static_cast<unsigned char>(c)) || c == '-') {
            out.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
        } else if (c == ' ' || c == '_') {
            out.push_back('-');
        }
    }
    return out.empty() ? "station-max" : out;
}

} // namespace

MdnsResponder::~MdnsResponder() {
    stop();
}

std::string MdnsResponder::detect_local_ipv4() {
    struct ifaddrs* ifa = nullptr;
    if (getifaddrs(&ifa) != 0) return "";
    std::string chosen;
    for (struct ifaddrs* p = ifa; p != nullptr; p = p->ifa_next) {
        if (!p->ifa_addr || p->ifa_addr->sa_family != AF_INET) continue;
        if (p->ifa_flags & IFF_LOOPBACK) continue;
        if (!(p->ifa_flags & IFF_UP)) continue;
        char buf[INET_ADDRSTRLEN] = {0};
        auto* sin = reinterpret_cast<struct sockaddr_in*>(p->ifa_addr);
        if (inet_ntop(AF_INET, &sin->sin_addr, buf, sizeof(buf))) {
            std::string ip(buf);
            if (ip.rfind("169.254.", 0) == 0) continue;
            chosen = ip;
            if (p->ifa_name && strncmp(p->ifa_name, "wlan", 4) == 0) break;
            if (p->ifa_name && strncmp(p->ifa_name, "eth", 3) == 0) break;
        }
    }
    freeifaddrs(ifa);
    return chosen;
}

bool MdnsResponder::start(const std::string& hostname, int web_port, int wyoming_port) {
    if (running_.load()) stop();
    {
        std::lock_guard<std::mutex> lk(mu_);
        hostname_ = sanitize_label(hostname);
        web_port_ = web_port;
        wyoming_port_ = wyoming_port;
        sock_fd_ = socket(AF_INET, SOCK_DGRAM, 0);
    }
    if (sock_fd_ < 0) {
        LOGW("mDNS", "socket() failed");
        return false;
    }

    int reuse = 1;
    setsockopt(sock_fd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
#ifdef SO_REUSEPORT
    setsockopt(sock_fd_, SOL_SOCKET, SO_REUSEPORT, &reuse, sizeof(reuse));
#endif
    int ttl = 255;
    setsockopt(sock_fd_, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, sizeof(ttl));
    int loop = 1;
    setsockopt(sock_fd_, IPPROTO_IP, IP_MULTICAST_LOOP, &loop, sizeof(loop));

    struct sockaddr_in bind_addr {};
    bind_addr.sin_family = AF_INET;
    bind_addr.sin_addr.s_addr = INADDR_ANY;
    bind_addr.sin_port = htons(kMdnsPort);
    if (bind(sock_fd_, reinterpret_cast<struct sockaddr*>(&bind_addr), sizeof(bind_addr)) < 0) {
        LOGW("mDNS", "Cannot bind UDP :5353 (will announce only from ephemeral port)");
    }

    struct ip_mreq mreq {};
    mreq.imr_multiaddr.s_addr = inet_addr(kMdnsAddr);
    mreq.imr_interface.s_addr = INADDR_ANY;
    setsockopt(sock_fd_, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq));

    struct timeval tv {1, 0};
    setsockopt(sock_fd_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    running_.store(true);
    worker_ = std::thread(&MdnsResponder::worker_loop, this);
    LOGI("mDNS", "Responder active (%s.local, _http._tcp:%d, _wyoming._tcp:%d)",
         hostname_.c_str(), web_port_, wyoming_port_);
    return true;
}

void MdnsResponder::stop() {
    running_.store(false);
    int fd = -1;
    {
        std::lock_guard<std::mutex> lk(mu_);
        fd = sock_fd_;
    }
    if (fd >= 0) {
        send_announcement(0); // goodbye packet before closing
        shutdown(fd, SHUT_RDWR);
    }
    if (worker_.joinable()) worker_.join();
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (sock_fd_ >= 0) {
            ::close(sock_fd_);
            sock_fd_ = -1;
        }
    }
}

void MdnsResponder::update_hostname(const std::string& hostname) {
    std::lock_guard<std::mutex> lk(mu_);
    hostname_ = sanitize_label(hostname);
}

void MdnsResponder::send_announcement(uint32_t ttl_sec) {
    std::string host;
    int fd = -1;
    {
        std::lock_guard<std::mutex> lk(mu_);
        fd = sock_fd_;
        host = hostname_;
    }
    if (fd < 0) return;
    std::string ip_str = detect_local_ipv4();
    if (ip_str.empty()) return;

    struct in_addr ina {};
    if (inet_pton(AF_INET, ip_str.c_str(), &ina) != 1) return;

    std::string fqdn = host + ".local";
    std::string http_svc = "_http._tcp.local";
    std::string http_inst = host + "._http._tcp.local";
    std::string wyo_svc = "_wyoming._tcp.local";
    std::string wyo_inst = host + "._wyoming._tcp.local";

    // Build unsolicited DNS mDNS response (QR=1, AA=1)
    std::vector<uint8_t> pkt;
    pkt.reserve(512);
    append_u16(pkt, 0);      // ID = 0
    append_u16(pkt, 0x8400); // Flags: Response, Authoritative
    append_u16(pkt, 0);      // QDCOUNT
    append_u16(pkt, 7);      // ANCOUNT: A + 2*PTR + 2*SRV + 2*TXT
    append_u16(pkt, 0);      // NSCOUNT
    append_u16(pkt, 0);      // ARCOUNT

    // 1. A record (<host>.local -> IPv4)
    append_rr_header(pkt, fqdn, 1, 0x8001, ttl_sec, 4);
    const uint8_t* ipb = reinterpret_cast<const uint8_t*>(&ina.s_addr);
    pkt.insert(pkt.end(), ipb, ipb + 4);

    // 2. PTR _http._tcp.local -> <host>._http._tcp.local
    std::vector<uint8_t> ptr_http;
    append_dns_name(ptr_http, http_inst);
    append_rr_header(pkt, http_svc, 12, 0x0001, ttl_sec, static_cast<uint16_t>(ptr_http.size()));
    pkt.insert(pkt.end(), ptr_http.begin(), ptr_http.end());

    // 3. SRV <host>._http._tcp.local
    std::vector<uint8_t> srv_http;
    append_u16(srv_http, 0);
    append_u16(srv_http, 0);
    append_u16(srv_http, static_cast<uint16_t>(web_port_));
    append_dns_name(srv_http, fqdn);
    append_rr_header(pkt, http_inst, 33, 0x8001, ttl_sec, static_cast<uint16_t>(srv_http.size()));
    pkt.insert(pkt.end(), srv_http.begin(), srv_http.end());

    // 4. TXT _http
    std::vector<uint8_t> txt_http;
    append_txt_str(txt_http, "path=/");
    append_txt_str(txt_http, "model=Yandex Station Max");
    append_rr_header(pkt, http_inst, 16, 0x8001, ttl_sec, static_cast<uint16_t>(txt_http.size()));
    pkt.insert(pkt.end(), txt_http.begin(), txt_http.end());

    // 5. PTR _wyoming._tcp.local -> <host>._wyoming._tcp.local
    std::vector<uint8_t> ptr_wyo;
    append_dns_name(ptr_wyo, wyo_inst);
    append_rr_header(pkt, wyo_svc, 12, 0x0001, ttl_sec, static_cast<uint16_t>(ptr_wyo.size()));
    pkt.insert(pkt.end(), ptr_wyo.begin(), ptr_wyo.end());

    // 6. SRV _wyoming
    std::vector<uint8_t> srv_wyo;
    append_u16(srv_wyo, 0);
    append_u16(srv_wyo, 0);
    append_u16(srv_wyo, static_cast<uint16_t>(wyoming_port_));
    append_dns_name(srv_wyo, fqdn);
    append_rr_header(pkt, wyo_inst, 33, 0x8001, ttl_sec, static_cast<uint16_t>(srv_wyo.size()));
    pkt.insert(pkt.end(), srv_wyo.begin(), srv_wyo.end());

    // 7. TXT _wyoming
    std::vector<uint8_t> txt_wyo;
    append_txt_str(txt_wyo, "type=satellite");
    append_rr_header(pkt, wyo_inst, 16, 0x8001, ttl_sec, static_cast<uint16_t>(txt_wyo.size()));
    pkt.insert(pkt.end(), txt_wyo.begin(), txt_wyo.end());

    struct sockaddr_in dst {};
    dst.sin_family = AF_INET;
    dst.sin_port = htons(kMdnsPort);
    dst.sin_addr.s_addr = inet_addr(kMdnsAddr);
    sendto(fd, pkt.data(), pkt.size(), 0,
           reinterpret_cast<struct sockaddr*>(&dst), sizeof(dst));
}

void MdnsResponder::worker_loop() {
    send_announcement(120);
    int ticks = 0;
    uint8_t rx[1500];
    int fd = -1;
    {
        std::lock_guard<std::mutex> lk(mu_);
        fd = sock_fd_;
    }

    while (running_.load() && fd >= 0) {
        struct sockaddr_in src {};
        socklen_t slen = sizeof(src);
        ssize_t n = recvfrom(fd, rx, sizeof(rx), 0,
                             reinterpret_cast<struct sockaddr*>(&src), &slen);
        if (n >= 12) {
            uint16_t flags = (static_cast<uint16_t>(rx[2]) << 8) | rx[3];
            uint16_t qd = (static_cast<uint16_t>(rx[4]) << 8) | rx[5];
            // Answer any mDNS query if our name or service string appears in packet
            if ((flags & 0x8000) == 0 && qd > 0) {
                std::string raw(reinterpret_cast<const char*>(rx + 12), static_cast<size_t>(n - 12));
                for (char& c : raw) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                std::string host;
                {
                    std::lock_guard<std::mutex> lk(mu_);
                    host = hostname_;
                }
                if (raw.find(host) != std::string::npos ||
                    raw.find("_wyoming") != std::string::npos ||
                    raw.find("_http") != std::string::npos) {
                    send_announcement(120);
                }
            }
        } else {
            if (++ticks >= 25) {
                ticks = 0;
                send_announcement(120);
            }
        }
    }
}

} // namespace openstation
