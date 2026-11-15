#pragma once

#include "utils/json_min.hpp"
#include "utils/logger.hpp"
#include "assistant/ca_roots_fallback.hpp"

#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/error.h"
#include "mbedtls/net_sockets.h"
#include "mbedtls/ssl.h"
#include "mbedtls/x509_crt.h"

#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstring>
#include <ctime>
#include <errno.h>
#include <fcntl.h>
#include <functional>
#include <mutex>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <sys/types.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>
#include <utility>
#include <vector>

namespace openstation {
namespace net {

inline bool is_valid_dns_host(const std::string& host) {
    if (host.empty() || host.size() > 128) return false;
    size_t label_len = 0;
    bool previous_hyphen = false;
    for (unsigned char c : host) {
        if (c == '.') {
            if (label_len == 0 || previous_hyphen) return false;
            label_len = 0;
            previous_hyphen = false;
            continue;
        }
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '-')) {
            return false;
        }
        if ((c == '-' && label_len == 0) || ++label_len > 63) return false;
        previous_hyphen = (c == '-');
    }
    return label_len > 0 && !previous_hyphen;
}

inline bool is_ipv4_literal(const std::string& host) {
    struct in_addr a {};
    return !host.empty() && inet_pton(AF_INET, host.c_str(), &a) == 1;
}

inline std::vector<std::string> normalize_dns_host_list(const std::vector<std::string>& raw, size_t max_items = 16) {
    std::vector<std::string> out;
    out.reserve(std::min(raw.size(), max_items));
    for (const auto& item : raw) {
        std::string h;
        size_t begin = item.find_first_not_of(" \t\r\n");
        if (begin == std::string::npos) continue;
        size_t end = item.find_last_not_of(" \t\r\n") + 1;
        h.reserve(end - begin);
        for (size_t i = begin; i < end; ++i) {
            unsigned char uc = static_cast<unsigned char>(item[i]);
            h.push_back(static_cast<char>(std::tolower(uc)));
        }
        if (!is_valid_dns_host(h)) continue;
        if (std::find(out.begin(), out.end(), h) != out.end()) continue;
        out.push_back(std::move(h));
        if (out.size() >= max_items) break;
    }
    return out;
}

inline std::vector<std::string> normalize_proxy_list(const std::vector<std::string>& raw, size_t max_items = 10) {
    std::vector<std::string> out;
    out.reserve(std::min(raw.size(), max_items));
    for (const auto& item : raw) {
        std::string h;
        h.reserve(item.size());
        for (unsigned char uc : item) {
            if (uc <= 0x20 || uc >= 0x7F) continue;
            h.push_back(static_cast<char>(uc));
            if (h.size() >= 128) break;
        }
        if (h.empty()) continue;
        if (std::find(out.begin(), out.end(), h) != out.end()) continue;
        out.push_back(std::move(h));
        if (out.size() >= max_items) break;
    }
    return out;
}

struct DnsPreset {
    std::string id;
    std::string host;
    std::string name;
    std::string category; // "smart" or "public"
    std::string description;
    std::vector<std::string> bootstrap_ips;
};

inline const std::vector<DnsPreset>& builtin_dns_presets() {
    static const std::vector<DnsPreset> kPresets = {
        {
            "comss", "dns.comss.one", "Comss.one DNS", "smart",
            "Умный DNS (обход блокировок Gemini / OpenAI / YouTube)",
            {}
        },
        {
            "comss_router", "router.comss.one", "Comss Router DNS", "smart",
            "Резервный сервер Comss.one (DoT :853)",
            {}
        },
        {
            "malw", "dns.malw.link", "MALW.link DNS", "smart",
            "Альтернативный Smart DNS (DoT :853)",
            {}
        },
        {
            "xbox", "xbox-dns.ru", "Xbox-DNS.ru", "smart",
            "Быстрый российский Smart DNS",
            {}
        },
        {
            "xbox_reserve", "dns.xbox-dns.ru", "Xbox-DNS Reserve", "smart",
            "Резервный узел Xbox-DNS (DoT :853)",
            {}
        },
        {
            "controld", "freedns.controld.com", "ControlD Free", "smart",
            "ControlD Anycast DNS без фильтрации",
            {"76.76.2.11"}
        },
        {
            "adguard", "dns.adguard-dns.com", "AdGuard DNS", "smart",
            "AdGuard DoT + блокировка рекламы (94.140.14.14)",
            {"94.140.14.14", "94.140.15.15"}
        },
        {
            "cloudflare", "one.one.one.one", "Cloudflare 1.1.1.1", "public",
            "Самый быстрый глобальный Anycast DoT/DoH",
            {"1.1.1.1", "1.0.0.1"}
        },
        {
            "google", "dns.google", "Google DNS", "public",
            "Надёжный публичный DNS Google (8.8.8.8)",
            {"8.8.8.8", "8.8.4.4"}
        },
        {
            "quad9", "dns.quad9.net", "Quad9 DNS", "public",
            "Безопасный Anycast DoT/DoH (9.9.9.9)",
            {"9.9.9.9", "149.112.112.112"}
        },
        {
            "yandex", "common.dot.dns.yandex.net", "Яндекс DNS", "public",
            "Локальный DoT по РФ (77.88.8.8)",
            {"77.88.8.8", "77.88.8.1"}
        },
        {
            "mullvad", "dns.mullvad.net", "Mullvad DNS", "public",
            "Приватный DoT без логов",
            {}
        },
        {
            "nextdns", "dns.nextdns.io", "NextDNS", "public",
            "Публичный Anycast DoT",
            {}
        }
    };
    return kPresets;
}

inline const DnsPreset* find_builtin_preset_by_host(const std::string& host) {
    std::string low;
    low.reserve(host.size());
    for (unsigned char c : host) low.push_back(static_cast<char>(std::tolower(c)));
    for (const auto& p : builtin_dns_presets()) {
        if (p.host == low || p.id == low) return &p;
    }
    return nullptr;
}

// RFC 1035 wire query builder for TYPE A (1) IN (1)
inline std::vector<uint8_t> build_dns_a_query(const std::string& qname, uint16_t txid) {
    if (!is_valid_dns_host(qname)) return {};
    std::vector<uint8_t> pkt;
    pkt.reserve(24 + qname.size());
    // 12-byte header
    pkt.push_back(static_cast<uint8_t>((txid >> 8) & 0xFF));
    pkt.push_back(static_cast<uint8_t>(txid & 0xFF));
    pkt.push_back(0x01); // QR=0, Opcode=0, RD=1
    pkt.push_back(0x00);
    pkt.push_back(0x00);
    pkt.push_back(0x01); // QDCOUNT = 1
    for (int i = 0; i < 6; ++i) pkt.push_back(0x00); // ANCOUNT, NSCOUNT, ARCOUNT

    size_t pos = 0;
    while (pos < qname.size()) {
        size_t dot = qname.find('.', pos);
        if (dot == std::string::npos) dot = qname.size();
        size_t lbl_len = dot - pos;
        if (lbl_len > 0 && lbl_len <= 63) {
            pkt.push_back(static_cast<uint8_t>(lbl_len));
            for (size_t i = pos; i < dot; ++i) {
                pkt.push_back(static_cast<uint8_t>(qname[i]));
            }
        }
        pos = dot + 1;
    }
    pkt.push_back(0x00); // root label
    pkt.push_back(0x00);
    pkt.push_back(0x01); // QTYPE = A (1)
    pkt.push_back(0x00);
    pkt.push_back(0x01); // QCLASS = IN (1)
    return pkt;
}

namespace dns_detail {

inline bool skip_dns_name(const uint8_t* data, size_t len, size_t& off) {
    size_t hops = 0;
    while (off < len) {
        if (++hops > 128) return false;
        uint8_t b = data[off];
        if (b == 0) {
            off += 1;
            return true;
        }
        if ((b & 0xC0u) == 0xC0u) {
            if (off + 2 > len) return false;
            off += 2;
            return true;
        }
        if ((b & 0xC0u) != 0) return false;
        off += 1 + static_cast<size_t>(b);
    }
    return false;
}

inline int connect_nonblock_ip_or_host(const std::string& host,
                                       int port,
                                       const std::vector<std::string>& fallback_ips,
                                       int timeout_ms,
                                       const std::atomic<bool>* abort_req,
                                       std::string& out_ip,
                                       std::string& err_out) {
    struct TargetAddr {
        struct sockaddr_storage ss {};
        socklen_t slen = 0;
        std::string ip_str;
    };
    std::vector<TargetAddr> targets;

    auto push_v4 = [&](const std::string& ip) {
        struct sockaddr_in sin {};
        sin.sin_family = AF_INET;
        sin.sin_port = htons(static_cast<uint16_t>(port));
        if (inet_pton(AF_INET, ip.c_str(), &sin.sin_addr) == 1) {
            TargetAddr ta;
            std::memcpy(&ta.ss, &sin, sizeof(sin));
            ta.slen = sizeof(sin);
            ta.ip_str = ip;
            targets.push_back(std::move(ta));
        }
    };

    if (is_ipv4_literal(host)) {
        push_v4(host);
    } else {
        if (!host.empty()) {
            struct addrinfo hints {};
            hints.ai_family = AF_UNSPEC;
            hints.ai_socktype = SOCK_STREAM;
            char pstr[16];
            snprintf(pstr, sizeof(pstr), "%d", port);
            struct addrinfo* res = nullptr;
            if (getaddrinfo(host.c_str(), pstr, &hints, &res) == 0 && res) {
                for (int pass = 0; pass < 2; ++pass) {
                    for (struct addrinfo* p = res; p; p = p->ai_next) {
                        bool is_v4 = (p->ai_family == AF_INET);
                        if ((pass == 0 && !is_v4) || (pass == 1 && is_v4)) continue;
                        if (p->ai_addrlen > sizeof(sockaddr_storage)) continue;
                        TargetAddr ta;
                        std::memcpy(&ta.ss, p->ai_addr, p->ai_addrlen);
                        ta.slen = static_cast<socklen_t>(p->ai_addrlen);
                        char buf[INET6_ADDRSTRLEN] = {};
                        if (p->ai_family == AF_INET) {
                            inet_ntop(AF_INET, &reinterpret_cast<const sockaddr_in*>(p->ai_addr)->sin_addr, buf, sizeof(buf));
                        } else if (p->ai_family == AF_INET6) {
                            inet_ntop(AF_INET6, &reinterpret_cast<const sockaddr_in6*>(p->ai_addr)->sin6_addr, buf, sizeof(buf));
                        }
                        ta.ip_str = buf;
                        targets.push_back(std::move(ta));
                    }
                }
                freeaddrinfo(res);
            }
        }
        for (const auto& fb : fallback_ips) {
            bool dup = false;
            for (const auto& t : targets) {
                if (t.ip_str == fb) { dup = true; break; }
            }
            if (!dup) push_v4(fb);
        }
    }

    if (targets.empty()) {
        err_out = "No IP or DNS resolution for " + host;
        return -1;
    }

    int last_errno = ETIMEDOUT;
    for (const auto& t : targets) {
        if (abort_req && abort_req->load()) {
            err_out = "aborted";
            return -1;
        }
        int fd = ::socket(t.ss.ss_family, SOCK_STREAM, 0);
        if (fd < 0) {
            last_errno = errno;
            continue;
        }
        int flags = fcntl(fd, F_GETFL, 0);
        if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);

        int rc = ::connect(fd, reinterpret_cast<const struct sockaddr*>(&t.ss), t.slen);
        if (rc == 0) {
            out_ip = t.ip_str;
            return fd;
        }
        if (errno != EINPROGRESS) {
            last_errno = errno;
            ::close(fd);
            continue;
        }

        auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::clamp(timeout_ms, 250, 5000));
        while (true) {
            if (abort_req && abort_req->load()) {
                ::close(fd);
                err_out = "aborted";
                return -1;
            }
            auto rem = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
            if (rem <= 0) {
                last_errno = ETIMEDOUT;
                ::close(fd);
                fd = -1;
                break;
            }
            struct pollfd pfd { fd, POLLOUT, 0 };
            int pr = ::poll(&pfd, 1, static_cast<int>(std::min<int64_t>(rem, 120)));
            if (pr > 0) {
                int so_err = 0;
                socklen_t sl = sizeof(so_err);
                if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &sl) == 0 && so_err == 0) {
                    out_ip = t.ip_str;
                    return fd;
                }
                last_errno = (so_err != 0) ? so_err : ECONNREFUSED;
                ::close(fd);
                fd = -1;
                break;
            }
            if (pr < 0 && errno != EINTR) {
                last_errno = errno;
                ::close(fd);
                fd = -1;
                break;
            }
        }
    }

    err_out = std::string("TCP connect failed: ") + strerror(last_errno);
    return -1;
}

struct TlsBio {
    int fd = -1;
    const std::atomic<bool>* abort_req = nullptr;
};

inline int bio_send(void* ctx, const unsigned char* buf, size_t len) {
    auto* b = static_cast<TlsBio*>(ctx);
    if (b->fd < 0 || (b->abort_req && b->abort_req->load())) return MBEDTLS_ERR_NET_SEND_FAILED;
    ssize_t n = ::send(b->fd, buf, len, MSG_NOSIGNAL);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return MBEDTLS_ERR_SSL_WANT_WRITE;
        return MBEDTLS_ERR_NET_SEND_FAILED;
    }
    return static_cast<int>(n);
}

inline int bio_recv(void* ctx, unsigned char* buf, size_t len) {
    auto* b = static_cast<TlsBio*>(ctx);
    if (b->fd < 0 || (b->abort_req && b->abort_req->load())) return MBEDTLS_ERR_NET_RECV_FAILED;
    ssize_t n = ::recv(b->fd, buf, len, 0);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return MBEDTLS_ERR_SSL_WANT_READ;
        return MBEDTLS_ERR_NET_RECV_FAILED;
    }
    return static_cast<int>(n);
}

inline int run_tls_io(int fd,
                      const std::atomic<bool>* abort_req,
                      std::chrono::steady_clock::time_point deadline,
                      const std::function<int()>& step) {
    while (true) {
        if (abort_req && abort_req->load()) return MBEDTLS_ERR_NET_RECV_FAILED;
        int rc = step();
        if (rc != MBEDTLS_ERR_SSL_WANT_READ && rc != MBEDTLS_ERR_SSL_WANT_WRITE) return rc;
        auto rem = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
        if (rem <= 0) return MBEDTLS_ERR_NET_RECV_FAILED;
        struct pollfd pfd { fd, static_cast<short>(rc == MBEDTLS_ERR_SSL_WANT_WRITE ? POLLOUT : POLLIN), 0 };
        int pr = ::poll(&pfd, 1, static_cast<int>(std::min<int64_t>(rem, 120)));
        if (pr < 0 && errno != EINTR) return MBEDTLS_ERR_NET_RECV_FAILED;
    }
}

inline std::mutex& dns_cache_mu() {
    static std::mutex m;
    return m;
}

struct CachedHostIps {
    std::vector<std::string> ips;
    std::chrono::steady_clock::time_point expires;
};

inline std::unordered_map<std::string, CachedHostIps>& dns_cache_map() {
    static std::unordered_map<std::string, CachedHostIps> map;
    return map;
}

} // namespace dns_detail

inline bool parse_dns_a_response(const uint8_t* data,
                                 size_t len,
                                 uint16_t expected_txid,
                                 std::vector<std::string>& out_ips,
                                 int* out_rcode = nullptr) {
    out_ips.clear();
    if (!data || len < 12) return false;
    uint16_t txid = static_cast<uint16_t>((static_cast<uint16_t>(data[0]) << 8) | data[1]);
    if (txid != expected_txid) return false;
    uint16_t flags = static_cast<uint16_t>((static_cast<uint16_t>(data[2]) << 8) | data[3]);
    int rcode = flags & 0x0F;
    if (out_rcode) *out_rcode = rcode;
    if ((flags & 0x8000u) == 0 || rcode != 0) return false;

    uint16_t qdcount = static_cast<uint16_t>((static_cast<uint16_t>(data[4]) << 8) | data[5]);
    uint16_t ancount = static_cast<uint16_t>((static_cast<uint16_t>(data[6]) << 8) | data[7]);
    size_t off = 12;

    for (uint16_t q = 0; q < qdcount; ++q) {
        if (!dns_detail::skip_dns_name(data, len, off) || off + 4 > len) return false;
        off += 4;
    }

    for (uint16_t a = 0; a < ancount; ++a) {
        if (!dns_detail::skip_dns_name(data, len, off) || off + 10 > len) break;
        uint16_t rtype = static_cast<uint16_t>((static_cast<uint16_t>(data[off]) << 8) | data[off + 1]);
        uint16_t rclass = static_cast<uint16_t>((static_cast<uint16_t>(data[off + 2]) << 8) | data[off + 3]);
        uint16_t rdlen = static_cast<uint16_t>((static_cast<uint16_t>(data[off + 8]) << 8) | data[off + 9]);
        off += 10;
        if (off + rdlen > len) break;
        if (rtype == 1 && rclass == 1 && rdlen == 4) {
            char buf[INET_ADDRSTRLEN] = {};
            struct in_addr addr {};
            std::memcpy(&addr, data + off, 4);
            if (inet_ntop(AF_INET, &addr, buf, sizeof(buf))) {
                out_ips.emplace_back(buf);
            }
        }
        off += rdlen;
    }
    return true;
}

struct DnsProbeResult {
    std::string id;
    std::string host;
    std::string name;
    std::string category = "custom";
    std::string description;
    bool ok = false;
    bool tcp_ok = false;
    bool tls_ok = false;
    bool resolve_ok = false;
    int latency_ms = -1;
    std::string resolved_ip;
    std::string error;
};

inline bool query_doh_json_ipv4(const std::string& bootstrap_ip,
                                const std::string& tls_sni,
                                const std::string& request_host,
                                const std::string& request_path,
                                std::vector<std::string>& out_ips,
                                int timeout_ms = 1600,
                                const std::atomic<bool>* abort_req = nullptr,
                                const std::string& ca_pem_path = "/data/local/openstation/cacert.pem");

inline DnsProbeResult query_dot_a_records(const std::string& server_host,
                                          const std::string& query_domain,
                                          const std::vector<std::string>& bootstrap_ips,
                                          std::vector<std::string>& out_ips,
                                          int timeout_ms = 1400,
                                          const std::string& ca_pem_path = "/data/local/openstation/cacert.pem",
                                          const std::atomic<bool>* abort_req = nullptr) {
    out_ips.clear();
    DnsProbeResult res;
    res.host = server_host;
    if (!is_valid_dns_host(server_host) || !is_valid_dns_host(query_domain)) {
        res.error = "Invalid DNS name";
        return res;
    }
    if (const auto* preset = find_builtin_preset_by_host(server_host)) {
        res.id = preset->id;
        res.name = preset->name;
        res.category = preset->category;
        res.description = preset->description;
    } else {
        res.id = server_host;
        res.name = server_host;
    }

    auto t0 = std::chrono::steady_clock::now();
    std::vector<std::string> ips = bootstrap_ips;
    if (ips.empty()) {
        if (const auto* p = find_builtin_preset_by_host(server_host)) {
            ips = p->bootstrap_ips;
        }
    }
    if (ips.empty() && !is_ipv4_literal(server_host) &&
        server_host != "one.one.one.one" && server_host != "dns.google" && server_host != "dns.quad9.net") {
        // Resolve the DoT hostname via Anycast 1.1.1.1 DoT / 8.8.8.8 DoH if system DNS is broken
        struct addrinfo hints {};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        struct addrinfo* probe_res = nullptr;
        bool sys_ok = (getaddrinfo(server_host.c_str(), "853", &hints, &probe_res) == 0 && probe_res != nullptr);
        if (probe_res) freeaddrinfo(probe_res);
        if (!sys_ok) {
            std::vector<std::string> resolved_dot;
            auto b_res = query_dot_a_records("one.one.one.one", server_host, {"1.1.1.1", "1.0.0.1"},
                                             resolved_dot, std::min(timeout_ms, 900), ca_pem_path, abort_req);
            if (b_res.resolve_ok && !resolved_dot.empty()) {
                ips = std::move(resolved_dot);
            } else {
                query_doh_json_ipv4("1.1.1.1", "one.one.one.one", "cloudflare-dns.com",
                                    "/dns-query?name=" + server_host + "&type=A", ips,
                                    std::min(timeout_ms, 900), abort_req, ca_pem_path);
            }
        }
    }

    std::string conn_ip, conn_err;
    int fd = dns_detail::connect_nonblock_ip_or_host(server_host, 853, ips, timeout_ms, abort_req, conn_ip, conn_err);
    if (fd < 0) {
        res.error = conn_err;
        return res;
    }
    res.tcp_ok = true;

    auto deadline = t0 + std::chrono::milliseconds(std::clamp(timeout_ms, 500, 5000));
    dns_detail::TlsBio bio{fd, abort_req};
    mbedtls_ssl_context ssl {};
    mbedtls_ssl_config conf {};
    mbedtls_x509_crt cacert {};
    mbedtls_ctr_drbg_context drbg {};
    mbedtls_entropy_context entropy {};

    mbedtls_ssl_init(&ssl);
    mbedtls_ssl_config_init(&conf);
    mbedtls_x509_crt_init(&cacert);
    mbedtls_ctr_drbg_init(&drbg);
    mbedtls_entropy_init(&entropy);

    auto cleanup = [&]() {
        mbedtls_ssl_free(&ssl);
        mbedtls_ssl_config_free(&conf);
        mbedtls_x509_crt_free(&cacert);
        mbedtls_ctr_drbg_free(&drbg);
        mbedtls_entropy_free(&entropy);
        if (fd >= 0) ::close(fd);
    };

    const char* pers = "openstation_dot";
    int rc = mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &entropy,
                                   reinterpret_cast<const unsigned char*>(pers), strlen(pers));
    if (rc != 0 ||
        (rc = mbedtls_ssl_config_defaults(&conf, MBEDTLS_SSL_IS_CLIENT,
                                          MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT)) != 0) {
        res.error = "TLS config error";
        cleanup();
        return res;
    }

    bool have_ca = !ca_pem_path.empty() && ::access(ca_pem_path.c_str(), R_OK) == 0 &&
                   mbedtls_x509_crt_parse_file(&cacert, ca_pem_path.c_str()) >= 0;
    have_ca = (mbedtls_x509_crt_parse(&cacert,
        reinterpret_cast<const unsigned char*>(kFallbackCaRootsPem), sizeof(kFallbackCaRootsPem)) >= 0) || have_ca;
    if (!have_ca) {
        res.error = "No trusted DNS certificates";
        cleanup();
        return res;
    }
    mbedtls_ssl_conf_authmode(&conf, MBEDTLS_SSL_VERIFY_REQUIRED);
    mbedtls_ssl_conf_ca_chain(&conf, &cacert, nullptr);
    mbedtls_ssl_conf_rng(&conf, mbedtls_ctr_drbg_random, &drbg);
    static const char* kDotAlpn[] = {"dot", nullptr};
    mbedtls_ssl_conf_alpn_protocols(&conf, kDotAlpn);

    if ((rc = mbedtls_ssl_setup(&ssl, &conf)) != 0 ||
        (rc = mbedtls_ssl_set_hostname(&ssl, server_host.c_str())) != 0) {
        res.error = "TLS setup error";
        cleanup();
        return res;
    }
    mbedtls_ssl_set_bio(&ssl, &bio, dns_detail::bio_send, dns_detail::bio_recv, nullptr);

    rc = dns_detail::run_tls_io(fd, abort_req, deadline, [&]() { return mbedtls_ssl_handshake(&ssl); });
    if (rc != 0) {
        char eb[96] = {};
        mbedtls_strerror(rc, eb, sizeof(eb));
        res.error = std::string("DoT TLS handshake: ") + eb;
        cleanup();
        return res;
    }
    res.tls_ok = true;

    const uint16_t txid = static_cast<uint16_t>((std::chrono::steady_clock::now().time_since_epoch().count() & 0xFFFF) ^ 0xA531);
    std::vector<uint8_t> pkt = build_dns_a_query(query_domain, txid);
    std::vector<uint8_t> frame;
    frame.reserve(2 + pkt.size());
    frame.push_back(static_cast<uint8_t>((pkt.size() >> 8) & 0xFF));
    frame.push_back(static_cast<uint8_t>(pkt.size() & 0xFF));
    frame.insert(frame.end(), pkt.begin(), pkt.end());

    size_t sent = 0;
    while (sent < frame.size()) {
        int wr = dns_detail::run_tls_io(fd, abort_req, deadline, [&]() {
            return mbedtls_ssl_write(&ssl, frame.data() + sent, frame.size() - sent);
        });
        if (wr <= 0) {
            res.error = "DoT write failed";
            cleanup();
            return res;
        }
        sent += static_cast<size_t>(wr);
    }

    std::vector<uint8_t> rx;
    rx.reserve(512);
    size_t expected_len = 0;
    uint8_t buf[1024];
    while (true) {
        int rd = dns_detail::run_tls_io(fd, abort_req, deadline, [&]() {
            return mbedtls_ssl_read(&ssl, buf, sizeof(buf));
        });
        if (rd <= 0) break;
        rx.insert(rx.end(), buf, buf + rd);
        if (expected_len == 0 && rx.size() >= 2) {
            expected_len = (static_cast<size_t>(rx[0]) << 8) | rx[1];
            if (expected_len < 12 || expected_len > 4096) break;
        }
        if (expected_len > 0 && rx.size() >= 2 + expected_len) break;
    }

    mbedtls_ssl_close_notify(&ssl);
    cleanup();

    if (expected_len < 12 || rx.size() < 2 + expected_len) {
        res.error = "DoT DNS timeout / short reply";
        return res;
    }

    int rcode = 0;
    if (!parse_dns_a_response(rx.data() + 2, expected_len, txid, out_ips, &rcode)) {
        res.error = "DoT DNS RCODE=" + std::to_string(rcode);
        return res;
    }

    res.resolve_ok = !out_ips.empty();
    res.ok = res.resolve_ok;
    if (!out_ips.empty()) res.resolved_ip = out_ips.front();
    else res.error = "Empty A answer";
    res.latency_ms = static_cast<int>(
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count());
    return res;
}

inline bool query_doh_json_ipv4(const std::string& bootstrap_ip,
                                const std::string& tls_sni,
                                const std::string& request_host,
                                const std::string& request_path,
                                std::vector<std::string>& out_ips,
                                int timeout_ms,
                                const std::atomic<bool>* abort_req,
                                const std::string& ca_pem_path) {
    out_ips.clear();
    std::string dummy_ip, conn_err;
    int fd = dns_detail::connect_nonblock_ip_or_host(bootstrap_ip, 443, {}, timeout_ms, abort_req, dummy_ip, conn_err);
    if (fd < 0) return false;

    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::clamp(timeout_ms, 500, 5000));
    dns_detail::TlsBio bio{fd, abort_req};
    mbedtls_ssl_context ssl {};
    mbedtls_ssl_config conf {};
    mbedtls_x509_crt cacert {};
    mbedtls_ctr_drbg_context drbg {};
    mbedtls_entropy_context entropy {};
    mbedtls_ssl_init(&ssl);
    mbedtls_ssl_config_init(&conf);
    mbedtls_x509_crt_init(&cacert);
    mbedtls_ctr_drbg_init(&drbg);
    mbedtls_entropy_init(&entropy);

    auto cleanup = [&]() {
        mbedtls_ssl_free(&ssl);
        mbedtls_ssl_config_free(&conf);
        mbedtls_x509_crt_free(&cacert);
        mbedtls_ctr_drbg_free(&drbg);
        mbedtls_entropy_free(&entropy);
        if (fd >= 0) ::close(fd);
    };

    const char* pers = "openstation_doh";
    if (mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &entropy,
                              reinterpret_cast<const unsigned char*>(pers), strlen(pers)) != 0 ||
        mbedtls_ssl_config_defaults(&conf, MBEDTLS_SSL_IS_CLIENT,
                                    MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT) != 0) {
        cleanup();
        return false;
    }
    bool have_ca = !ca_pem_path.empty() && ::access(ca_pem_path.c_str(), R_OK) == 0 &&
                   mbedtls_x509_crt_parse_file(&cacert, ca_pem_path.c_str()) >= 0;
    have_ca = (mbedtls_x509_crt_parse(&cacert,
        reinterpret_cast<const unsigned char*>(kFallbackCaRootsPem), sizeof(kFallbackCaRootsPem)) >= 0) || have_ca;
    if (!have_ca) { cleanup(); return false; }
    mbedtls_ssl_conf_authmode(&conf, MBEDTLS_SSL_VERIFY_REQUIRED);
    mbedtls_ssl_conf_ca_chain(&conf, &cacert, nullptr);
    mbedtls_ssl_conf_rng(&conf, mbedtls_ctr_drbg_random, &drbg);
    if (mbedtls_ssl_setup(&ssl, &conf) != 0 ||
        mbedtls_ssl_set_hostname(&ssl, tls_sni.c_str()) != 0) {
        cleanup();
        return false;
    }
    mbedtls_ssl_set_bio(&ssl, &bio, dns_detail::bio_send, dns_detail::bio_recv, nullptr);
    if (dns_detail::run_tls_io(fd, abort_req, deadline, [&]() { return mbedtls_ssl_handshake(&ssl); }) != 0) {
        cleanup();
        return false;
    }

    std::string req = "GET " + request_path + " HTTP/1.1\r\n"
                      "Host: " + request_host + "\r\n"
                      "Accept: application/dns-json\r\n"
                      "Connection: close\r\n\r\n";
    size_t sent = 0;
    while (sent < req.size()) {
        int wr = dns_detail::run_tls_io(fd, abort_req, deadline, [&]() {
            return mbedtls_ssl_write(&ssl, reinterpret_cast<const uint8_t*>(req.data() + sent), req.size() - sent);
        });
        if (wr <= 0) { cleanup(); return false; }
        sent += static_cast<size_t>(wr);
    }

    std::string raw;
    uint8_t buf[2048];
    while (raw.size() < 32768) {
        int rd = dns_detail::run_tls_io(fd, abort_req, deadline, [&]() {
            return mbedtls_ssl_read(&ssl, buf, sizeof(buf));
        });
        if (rd <= 0) break;
        raw.append(reinterpret_cast<const char*>(buf), static_cast<size_t>(rd));
    }
    cleanup();

    size_t hdr_end = raw.find("\r\n\r\n");
    if (hdr_end == std::string::npos) return false;
    std::string body = raw.substr(hdr_end + 4);
    size_t jstart = body.find('{');
    if (jstart == std::string::npos) return false;
    if (jstart > 0) body = body.substr(jstart);

    json::Value j;
    if (!json::Parser::parse(body, j) || !j.is_obj()) return false;
    const auto* ans = j.get("Answer");
    if (!ans || !ans->is_arr()) return false;
    for (const auto& rr : ans->arr_val) {
        if (!rr.is_obj()) continue;
        const auto* t = rr.get("type");
        const auto* d = rr.get("data");
        if (t && t->as_int(0) == 1 && d && d->is_str() && is_ipv4_literal(d->as_str())) {
            out_ips.push_back(d->as_str());
        }
    }
    return !out_ips.empty();
}

inline bool resolve_ipv4_with_fallback(const std::string& host,
                                       std::vector<std::string>& out_ips,
                                       const std::string& preferred_dot_host = "",
                                       int timeout_ms = 1400,
                                       const std::string& ca_pem_path = "/data/local/openstation/cacert.pem",
                                       const std::atomic<bool>* abort_req = nullptr) {
    out_ips.clear();
    if (host.empty()) return false;
    if (is_ipv4_literal(host)) {
        out_ips.push_back(host);
        return true;
    }
    if (!is_valid_dns_host(host)) return false;

    // 1. Check short TTL cache
    auto now = std::chrono::steady_clock::now();
    {
        std::lock_guard<std::mutex> lk(dns_detail::dns_cache_mu());
        auto& mp = dns_detail::dns_cache_map();
        auto it = mp.find(host);
        if (it != mp.end() && it->second.expires > now && !it->second.ips.empty()) {
            out_ips = it->second.ips;
            return true;
        }
    }

    // 2. Try DoT resolvers (preferred active host first if known, then reliable anycast IPs)
    std::vector<std::pair<std::string, std::vector<std::string>>> dot_targets;
    if (!preferred_dot_host.empty() && is_valid_dns_host(preferred_dot_host)) {
        std::vector<std::string> b;
        if (const auto* p = find_builtin_preset_by_host(preferred_dot_host)) b = p->bootstrap_ips;
        dot_targets.emplace_back(preferred_dot_host, std::move(b));
    }
    dot_targets.emplace_back("one.one.one.one", std::vector<std::string>{"1.1.1.1", "1.0.0.1"});
    dot_targets.emplace_back("dns.google", std::vector<std::string>{"8.8.8.8", "8.8.4.4"});
    dot_targets.emplace_back("dns.quad9.net", std::vector<std::string>{"9.9.9.9", "149.112.112.112"});

    for (const auto& t : dot_targets) {
        if (abort_req && abort_req->load()) return false;
        DnsProbeResult r = query_dot_a_records(t.first, host, t.second, out_ips, timeout_ms, ca_pem_path, abort_req);
        if (r.resolve_ok && !out_ips.empty()) {
            std::lock_guard<std::mutex> lk(dns_detail::dns_cache_mu());
            dns_detail::dns_cache_map()[host] = {out_ips, std::chrono::steady_clock::now() + std::chrono::seconds(45)};
            return true;
        }
    }

    // 3. DoH HTTPS fallback directly by Anycast IP (works even if port 853 is firewalled)
    if (query_doh_json_ipv4("1.1.1.1", "one.one.one.one", "cloudflare-dns.com",
                            "/dns-query?name=" + host + "&type=A", out_ips, timeout_ms, abort_req, ca_pem_path) ||
        query_doh_json_ipv4("8.8.8.8", "dns.google", "dns.google",
                            "/resolve?name=" + host + "&type=A", out_ips, timeout_ms, abort_req, ca_pem_path)) {
        std::lock_guard<std::mutex> lk(dns_detail::dns_cache_mu());
        dns_detail::dns_cache_map()[host] = {out_ips, std::chrono::steady_clock::now() + std::chrono::seconds(45)};
        return true;
    }

    return false;
}

class DnsFailoverManager {
public:
    using ProbeFn = std::function<DnsProbeResult(const std::string& host, int timeout_ms)>;
    using ApplyDnsFn = std::function<bool(const std::string& mode, const std::string& host,
                                          const std::string& expected_mode, const std::string& expected_host)>;

    struct FailoverOutcome {
        bool ok = false;
        bool healthy = false;
        bool switched = false;
        std::string active_mode;
        std::string active_host;
        std::string reason;
        int latency_ms = -1;
        std::vector<DnsProbeResult> tried;
    };

    DnsFailoverManager() {
        probe_fn_ = [](const std::string& host, int timeout_ms) {
            std::vector<std::string> ips;
            return query_dot_a_records(host, "generativelanguage.googleapis.com", {}, ips, timeout_ms);
        };
    }

    void set_hooks(ProbeFn probe_fn, ApplyDnsFn apply_fn) {
        std::lock_guard<std::mutex> lk(mu_);
        if (probe_fn) probe_fn_ = std::move(probe_fn);
        if (apply_fn) apply_fn_ = std::move(apply_fn);
    }

    static std::vector<std::string> build_candidate_hosts(const std::string& current_host,
                                                          const std::vector<std::string>& custom_fallbacks) {
        std::vector<std::string> out;
        auto push_unique = [&](const std::string& raw) {
            std::string h;
            h.reserve(raw.size());
            for (unsigned char c : raw) {
                if (c <= 0x20 || c >= 0x7F) continue;
                h.push_back(static_cast<char>(std::tolower(c)));
            }
            if (!is_valid_dns_host(h)) return;
            if (std::find(out.begin(), out.end(), h) == out.end()) out.push_back(std::move(h));
        };
        push_unique(current_host);
        for (const auto& h : custom_fallbacks) push_unique(h);
        for (const auto& p : builtin_dns_presets()) {
            if (p.category == "smart") push_unique(p.host);
        }
        for (const auto& p : builtin_dns_presets()) {
            if (p.category != "smart") push_unique(p.host);
        }
        return out;
    }

    std::vector<DnsProbeResult> probe_all(const std::string& active_host,
                                          const std::vector<std::string>& custom_fallbacks,
                                          int timeout_ms = 1300) {
        std::vector<std::string> hosts;
        for (const auto& p : builtin_dns_presets()) hosts.push_back(p.host);
        for (const auto& h : custom_fallbacks) {
            std::string low;
            for (unsigned char c : h) if (c > 0x20 && c < 0x7F) low.push_back(static_cast<char>(std::tolower(c)));
            if (is_valid_dns_host(low) && std::find(hosts.begin(), hosts.end(), low) == hosts.end()) {
                hosts.push_back(std::move(low));
            }
        }
        if (!active_host.empty() && is_valid_dns_host(active_host)) {
            std::string low;
            for (unsigned char c : active_host) low.push_back(static_cast<char>(std::tolower(c)));
            if (std::find(hosts.begin(), hosts.end(), low) == hosts.end()) {
                hosts.insert(hosts.begin(), std::move(low));
            }
        }

        ProbeFn pfn;
        {
            std::lock_guard<std::mutex> lk(mu_);
            pfn = probe_fn_;
        }

        std::vector<DnsProbeResult> results(hosts.size());
        const size_t max_workers = std::min<size_t>(6, hosts.size());
        std::atomic<size_t> next_idx{0};
        std::vector<std::thread> workers;
        workers.reserve(max_workers);
        for (size_t w = 0; w < max_workers; ++w) {
            workers.emplace_back([&]() {
                while (true) {
                    size_t idx = next_idx.fetch_add(1);
                    if (idx >= hosts.size()) break;
                    results[idx] = pfn(hosts[idx], timeout_ms);
                }
            });
        }
        for (auto& th : workers) th.join();

        std::lock_guard<std::mutex> lk(mu_);
        last_probe_epoch_ = static_cast<int64_t>(std::time(nullptr));
        for (const auto& r : results) {
            if (!active_host.empty() && r.host == active_host) {
                active_healthy_ = r.ok;
                last_active_latency_ms_ = r.latency_ms;
                last_error_ = r.ok ? "" : r.error;
            }
        }
        return results;
    }

    FailoverOutcome evaluate_and_switch(const std::string& cur_mode,
                                        const std::string& cur_host,
                                        const std::vector<std::string>& custom_fallbacks,
                                        bool auto_failover_enabled,
                                        bool force = false,
                                        int min_cooldown_sec = 25,
                                        int probe_timeout_ms = 1300) {
        FailoverOutcome out;
        out.active_mode = cur_mode;
        out.active_host = cur_host;

        if (!force && !auto_failover_enabled) {
            std::lock_guard<std::mutex> lk(mu_);
            out.ok = true;
            out.healthy = active_healthy_;
            out.reason = "disabled";
            return out;
        }

        // An automatic check must not turn on Private DNS when the user chose
        // the Android automatic or off mode. Manual selection remains available.
        if (!force && cur_mode != "hostname") {
            std::lock_guard<std::mutex> lk(mu_);
            out.ok = true;
            out.healthy = active_healthy_;
            out.reason = "private_dns_not_strict";
            return out;
        }

        auto now = std::chrono::steady_clock::now();
        ProbeFn pfn;
        ApplyDnsFn afn;
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (!force && has_checked_before_ &&
                std::chrono::duration_cast<std::chrono::seconds>(now - last_failover_tp_).count() < min_cooldown_sec) {
                out.ok = true;
                out.healthy = active_healthy_;
                out.reason = "cooldown";
                return out;
            }
            pfn = probe_fn_;
            afn = apply_fn_;
        }

        // First verify current hostname if mode == "hostname"
        if (!force && cur_mode == "hostname" && !cur_host.empty()) {
            DnsProbeResult cur_res = pfn(cur_host, probe_timeout_ms);
            out.tried.push_back(cur_res);
            if (cur_res.ok) {
                std::lock_guard<std::mutex> lk(mu_);
                has_checked_before_ = true;
                last_failover_tp_ = now;
                active_healthy_ = true;
                last_active_latency_ms_ = cur_res.latency_ms;
                last_error_.clear();
                last_probe_epoch_ = static_cast<int64_t>(std::time(nullptr));
                out.ok = true;
                out.healthy = true;
                out.latency_ms = cur_res.latency_ms;
                out.reason = "active_healthy";
                return out;
            }
        }

        const auto candidates = build_candidate_hosts(force ? "" : cur_host, custom_fallbacks);
        DnsProbeResult chosen;
        bool found = false;
        for (int pass = 0; pass < (force ? 2 : 1) && !found; ++pass) {
            for (const auto& h : candidates) {
                if (!force && cur_mode == "hostname" && h == cur_host) continue;
                if (force && pass == 0 && h == cur_host) continue;
                if (force && pass == 1 && h != cur_host) continue;
                DnsProbeResult r = pfn(h, probe_timeout_ms);
                out.tried.push_back(r);
                if (r.ok) {
                    chosen = r;
                    found = true;
                    break;
                }
            }
        }

        const int64_t now_epoch = static_cast<int64_t>(std::time(nullptr));
        if (found) {
            bool need_apply = (cur_mode != "hostname") || (chosen.host != cur_host);
            bool applied = !need_apply || (afn ? afn("hostname", chosen.host, cur_mode, cur_host) : false);
            if (applied) {
                std::lock_guard<std::mutex> lk(mu_);
                has_checked_before_ = true;
                last_failover_tp_ = now;
                active_healthy_ = true;
                last_active_latency_ms_ = chosen.latency_ms;
                last_error_.clear();
                last_probe_epoch_ = now_epoch;
                if (need_apply) {
                    last_switch_from_ = cur_host.empty() ? cur_mode : cur_host;
                    last_switch_to_ = chosen.host;
                    last_switch_reason_ = force ? "manual_failover" : "active_dns_failed";
                    last_switch_epoch_ = now_epoch;
                }
            }
            out.ok = applied;
            out.healthy = applied;
            out.switched = need_apply && applied;
            out.active_mode = applied ? "hostname" : cur_mode;
            out.active_host = applied ? chosen.host : cur_host;
            out.latency_ms = chosen.latency_ms;
            out.reason = need_apply ? (applied ? "switched" : "apply_failed") : "active_healthy";
            return out;
        }

        // All DoT presets failed (e.g., port 853 blocked on network): fall back cleanly to opportunistic
        bool fallback_opp = (cur_mode != "opportunistic") && afn &&
                            afn("opportunistic", "", cur_mode, cur_host);
        {
            std::lock_guard<std::mutex> lk(mu_);
            has_checked_before_ = true;
            last_failover_tp_ = now;
            active_healthy_ = false;
            last_probe_epoch_ = now_epoch;
            last_error_ = "All DoT servers unreachable";
            if (fallback_opp) {
                last_switch_from_ = cur_host.empty() ? cur_mode : cur_host;
                last_switch_to_ = "opportunistic";
                last_switch_reason_ = "dot_blocked_fallback_auto";
                last_switch_epoch_ = now_epoch;
            }
        }
        out.ok = fallback_opp;
        out.healthy = false;
        out.switched = fallback_opp;
        out.active_mode = fallback_opp ? "opportunistic" : cur_mode;
        out.active_host = fallback_opp ? "" : cur_host;
        out.reason = fallback_opp ? "fallback_opportunistic" : "all_dot_failed";
        return out;
    }

    static json::Value presets_to_json(const std::vector<DnsProbeResult>* probes = nullptr,
                                       const std::string& active_mode = "",
                                       const std::string& active_host = "") {
        using json::Value;
        Value arr = Value::make_arr();
        auto probe_for = [&](const std::string& h) -> const DnsProbeResult* {
            if (!probes) return nullptr;
            for (const auto& p : *probes) {
                if (p.host == h) return &p;
            }
            return nullptr;
        };
        for (const auto& p : builtin_dns_presets()) {
            Value item = Value::make_obj();
            item["id"] = Value::make_str(p.id);
            item["host"] = Value::make_str(p.host);
            item["name"] = Value::make_str(p.name);
            item["category"] = Value::make_str(p.category);
            item["description"] = Value::make_str(p.description);
            item["active"] = Value::make_bool(active_mode == "hostname" && active_host == p.host);
            if (const auto* pr = probe_for(p.host)) {
                item["ok"] = Value::make_bool(pr->ok);
                item["latencyMs"] = Value::make_int(pr->latency_ms);
                item["resolvedIp"] = Value::make_str(pr->resolved_ip);
                item["error"] = Value::make_str(pr->error);
            }
            arr.push(std::move(item));
        }
        if (probes) {
            for (const auto& pr : *probes) {
                if (find_builtin_preset_by_host(pr.host)) continue;
                Value item = Value::make_obj();
                item["id"] = Value::make_str(pr.id);
                item["host"] = Value::make_str(pr.host);
                item["name"] = Value::make_str(pr.name);
                item["category"] = Value::make_str("custom");
                item["description"] = Value::make_str("Пользовательский Private DNS");
                item["active"] = Value::make_bool(active_mode == "hostname" && active_host == pr.host);
                item["ok"] = Value::make_bool(pr.ok);
                item["latencyMs"] = Value::make_int(pr.latency_ms);
                item["resolvedIp"] = Value::make_str(pr.resolved_ip);
                item["error"] = Value::make_str(pr.error);
                arr.push(std::move(item));
            }
        }
        return arr;
    }

    json::Value get_status_json() {
        using json::Value;
        std::lock_guard<std::mutex> lk(mu_);
        Value obj = Value::make_obj();
        obj["healthy"] = Value::make_bool(active_healthy_);
        obj["latencyMs"] = Value::make_int(last_active_latency_ms_);
        obj["lastProbeEpoch"] = Value::make_int(last_probe_epoch_);
        obj["lastSwitchFrom"] = Value::make_str(last_switch_from_);
        obj["lastSwitchTo"] = Value::make_str(last_switch_to_);
        obj["lastSwitchReason"] = Value::make_str(last_switch_reason_);
        obj["lastSwitchEpoch"] = Value::make_int(last_switch_epoch_);
        obj["lastError"] = Value::make_str(last_error_);
        return obj;
    }

private:
    std::mutex mu_;
    ProbeFn probe_fn_;
    ApplyDnsFn apply_fn_;
    bool has_checked_before_ = false;
    bool active_healthy_ = true;
    int last_active_latency_ms_ = -1;
    int64_t last_probe_epoch_ = 0;
    std::chrono::steady_clock::time_point last_failover_tp_{};
    std::string last_switch_from_;
    std::string last_switch_to_;
    std::string last_switch_reason_;
    int64_t last_switch_epoch_ = 0;
    std::string last_error_;
};

} // namespace net
} // namespace openstation
