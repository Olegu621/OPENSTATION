#include "assistant/tls_ws_client.hpp"
#include "assistant/ca_roots_fallback.hpp"
#include "utils/base64.hpp"
#include "utils/logger.hpp"

#include "mbedtls/error.h"
#include "mbedtls/net_sockets.h"
#include "mbedtls/sha1.h"
#include "psa/crypto.h"

#include <algorithm>
#include <arpa/inet.h>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

namespace openstation {

namespace {

constexpr int64_t kMinValidUnixSec = 1735689600LL; // 2025-01-01 UTC
constexpr size_t kMaxWsPayloadBytes = 16U * 1024U * 1024U;

std::once_flag g_psa_once;
bool g_psa_ok = false;

bool ensure_psa_initialized() {
    std::call_once(g_psa_once, []() {
        psa_status_t st = psa_crypto_init();
        g_psa_ok = (st == PSA_SUCCESS);
        if (!g_psa_ok) {
            LOGE("TlsWs", "psa_crypto_init failed: %d", static_cast<int>(st));
        }
    });
    return g_psa_ok;
}

std::string mbed_err_str(int code) {
    char buf[160];
    mbedtls_strerror(code, buf, sizeof(buf));
    return std::string(buf) + " (" + std::to_string(code) + ")";
}

int set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

int wait_fd_poll(int fd, short events, int timeout_ms, const std::atomic<bool>& abort_req) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max(0, timeout_ms));
    while (!abort_req.load()) {
        int rem = 50;
        if (timeout_ms >= 0) {
            auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                            deadline - std::chrono::steady_clock::now())
                            .count();
            if (left <= 0) return 0;
            rem = static_cast<int>(std::min<int64_t>(left, 100));
        }
        struct pollfd pfd {};
        pfd.fd = fd;
        pfd.events = events;
        int rc = poll(&pfd, 1, rem);
        if (rc < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (rc > 0) return 1;
        if (timeout_ms == 0) return 0;
    }
    return -2; // aborted
}

bool sntp_query_host(const char* host, int timeout_ms) {
    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    struct addrinfo* res = nullptr;
    if (getaddrinfo(host, "123", &hints, &res) != 0 || !res) {
        return false;
    }

    bool ok = false;
    for (struct addrinfo* p = res; p != nullptr && !ok; p = p->ai_next) {
        int fd = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (fd < 0) continue;

        uint8_t req[48] = {};
        req[0] = 0x23; // LI=0, VN=4, Mode=3 (client)
        if (sendto(fd, req, sizeof(req), 0, p->ai_addr, p->ai_addrlen) == sizeof(req)) {
            struct pollfd pfd {};
            pfd.fd = fd;
            pfd.events = POLLIN;
            if (poll(&pfd, 1, timeout_ms) > 0 && (pfd.revents & POLLIN)) {
                uint8_t resp[64];
                ssize_t n = recvfrom(fd, resp, sizeof(resp), 0, nullptr, nullptr);
                if (n >= 48) {
                    uint32_t ntp_sec = (static_cast<uint32_t>(resp[40]) << 24) |
                                       (static_cast<uint32_t>(resp[41]) << 16) |
                                       (static_cast<uint32_t>(resp[42]) << 8) |
                                       static_cast<uint32_t>(resp[43]);
                    uint32_t ntp_frac = (static_cast<uint32_t>(resp[44]) << 24) |
                                        (static_cast<uint32_t>(resp[45]) << 16) |
                                        (static_cast<uint32_t>(resp[46]) << 8) |
                                        static_cast<uint32_t>(resp[47]);
                    constexpr uint64_t kNtpToUnix = 2208988800ULL;
                    if (ntp_sec > kNtpToUnix) {
                        uint64_t unix_sec = static_cast<uint64_t>(ntp_sec) - kNtpToUnix;
                        if (unix_sec >= static_cast<uint64_t>(kMinValidUnixSec) &&
                            unix_sec < 2524608000ULL) {
                            struct timespec ts {};
                            ts.tv_sec = static_cast<time_t>(unix_sec);
                            ts.tv_nsec = static_cast<long>((static_cast<uint64_t>(ntp_frac) * 1000000000ULL) >> 32);
                            if (clock_settime(CLOCK_REALTIME, &ts) == 0) {
                                LOGI("TlsWs", "Synchronized system clock via SNTP (%s): unix=%llu",
                                     host, static_cast<unsigned long long>(unix_sec));
                                ok = true;
                            }
                        }
                    }
                }
            }
        }
        ::close(fd);
    }
    freeaddrinfo(res);
    return ok;
}

std::string sockaddr_to_str(const struct sockaddr* sa) {
    char buf[INET6_ADDRSTRLEN] = {};
    if (!sa) return "?";
    if (sa->sa_family == AF_INET) {
        const auto* sin = reinterpret_cast<const struct sockaddr_in*>(sa);
        inet_ntop(AF_INET, &sin->sin_addr, buf, sizeof(buf));
    } else if (sa->sa_family == AF_INET6) {
        const auto* sin6 = reinterpret_cast<const struct sockaddr_in6*>(sa);
        inet_ntop(AF_INET6, &sin6->sin6_addr, buf, sizeof(buf));
    }
    return buf[0] ? std::string(buf) : std::string("?");
}

int connect_tcp_nonblock(const std::string& host,
                         int port,
                         int timeout_ms,
                         const std::atomic<bool>& abort_req,
                         std::string& connected_ip_out,
                         std::string& err_out) {
    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    struct addrinfo* res = nullptr;
    int gai = getaddrinfo(host.c_str(), port_str, &hints, &res);
    if (gai != 0 || !res) {
        err_out = std::string("DNS failed: ") + gai_strerror(gai);
        return -1;
    }

    // Prefer IPv4 first if IPv6 route isn't active on the local Wi-Fi AP
    std::vector<struct addrinfo*> order;
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family == AF_INET) order.push_back(p);
    }
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family != AF_INET) order.push_back(p);
    }

    int last_err = ETIMEDOUT;
    for (struct addrinfo* p : order) {
        if (abort_req.load()) {
            freeaddrinfo(res);
            err_out = "aborted";
            return -1;
        }
        std::string ip_str = sockaddr_to_str(p->ai_addr);
        int fd = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (fd < 0) {
            last_err = errno;
            continue;
        }
        set_nonblock(fd);

        int rc = ::connect(fd, p->ai_addr, p->ai_addrlen);
        if (rc == 0) {
            connected_ip_out = ip_str;
            freeaddrinfo(res);
            return fd;
        }
        if (errno != EINPROGRESS) {
            last_err = errno;
            LOGW("TlsWs", "TCP connect %s (%s:%d) failed immediately: %s",
                 host.c_str(), ip_str.c_str(), port, strerror(last_err));
            ::close(fd);
            continue;
        }

        int per_addr_ms = std::min(timeout_ms, 4000);
        int pr = wait_fd_poll(fd, POLLOUT, per_addr_ms, abort_req);
        if (pr > 0) {
            int so_err = 0;
            socklen_t sl = sizeof(so_err);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &sl) == 0 && so_err == 0) {
                connected_ip_out = ip_str;
                freeaddrinfo(res);
                return fd;
            }
            last_err = (so_err != 0) ? so_err : ECONNREFUSED;
        } else {
            last_err = (pr == -2) ? ECANCELED : ETIMEDOUT;
        }
        LOGW("TlsWs", "TCP connect %s (%s:%d) failed: %s",
             host.c_str(), ip_str.c_str(), port, strerror(last_err));
        ::close(fd);
    }

    freeaddrinfo(res);
    err_out = std::string("TCP connect failed: ") + strerror(last_err);
    return -1;
}

std::string to_lower_ascii(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

} // namespace

bool ensure_sntp_time(int timeout_ms) {
    if (std::time(nullptr) >= kMinValidUnixSec) {
        return true;
    }
    LOGW("TlsWs", "System clock pre-2025 (%lld), syncing via SNTP...",
         static_cast<long long>(std::time(nullptr)));
    if (sntp_query_host("time.google.com", timeout_ms)) return true;
    if (sntp_query_host("pool.ntp.org", timeout_ms)) return true;
    if (sntp_query_host("time.cloudflare.com", timeout_ms)) return true;
    return std::time(nullptr) >= kMinValidUnixSec;
}

TlsWsClient::~TlsWsClient() {
    close();
}

int TlsWsClient::bio_send(void* ctx, const unsigned char* buf, size_t len) {
    auto* self = static_cast<TlsWsClient*>(ctx);
    int fd = self->sock_fd_.load();
    if (fd < 0 || self->abort_req_.load()) {
        return MBEDTLS_ERR_NET_SEND_FAILED;
    }
    ssize_t n = ::send(fd, buf, len, MSG_NOSIGNAL);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            return MBEDTLS_ERR_SSL_WANT_WRITE;
        }
        return MBEDTLS_ERR_NET_SEND_FAILED;
    }
    return static_cast<int>(n);
}

int TlsWsClient::bio_recv(void* ctx, unsigned char* buf, size_t len) {
    auto* self = static_cast<TlsWsClient*>(ctx);
    int fd = self->sock_fd_.load();
    if (fd < 0 || self->abort_req_.load()) {
        return MBEDTLS_ERR_NET_RECV_FAILED;
    }
    ssize_t n = ::recv(fd, buf, len, 0);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            return MBEDTLS_ERR_SSL_WANT_READ;
        }
        return MBEDTLS_ERR_NET_RECV_FAILED;
    }
    if (n == 0) {
        return 0; // EOF
    }
    return static_cast<int>(n);
}

void TlsWsClient::cleanup_tls() {
    if (tls_inited_) {
        mbedtls_ssl_free(&ssl_);
        mbedtls_ssl_config_free(&conf_);
        mbedtls_x509_crt_free(&cacert_);
        mbedtls_ctr_drbg_free(&ctr_drbg_);
        mbedtls_entropy_free(&entropy_);
        tls_inited_ = false;
    }
    int fd = sock_fd_.exchange(-1);
    if (fd >= 0) {
        ::close(fd);
    }
    rx_buf_.clear();
    frag_acc_.clear();
    frag_opcode_ = 0;
    in_fragment_ = false;
}

void TlsWsClient::request_abort() {
    abort_req_.store(true);
    int fd = sock_fd_.load();
    if (fd >= 0) {
        ::shutdown(fd, SHUT_RDWR);
    }
}

void TlsWsClient::close() {
    connected_.store(false);
    request_abort();
    std::lock_guard<std::mutex> lk(state_mu_);
    cleanup_tls();
}

bool TlsWsClient::connect(const std::string& host,
                          int port,
                          const std::string& path_with_query,
                          const std::string& ca_pem_path,
                          std::string& err_out,
                          int timeout_ms,
                          const std::string& connect_host_override) {
    close();
    std::lock_guard<std::mutex> lk(state_mu_);
    abort_req_.store(false);

    if (!ensure_psa_initialized()) {
        err_out = "PSA crypto init failed";
        return false;
    }

    // Ensure valid RTC time for X.509 certificate verification
    ensure_sntp_time(1800);

    const std::string& tcp_target = connect_host_override.empty() ? host : connect_host_override;
    std::string connected_ip;
    int fd = connect_tcp_nonblock(tcp_target, port, timeout_ms, abort_req_, connected_ip, err_out);
    if (fd < 0) {
        return false;
    }
    LOGI("TlsWs", "TCP connected %s:%d -> %s (target=%s)",
         host.c_str(), port, connected_ip.c_str(), tcp_target.c_str());
    sock_fd_.store(fd);

    mbedtls_ssl_init(&ssl_);
    mbedtls_ssl_config_init(&conf_);
    mbedtls_x509_crt_init(&cacert_);
    mbedtls_ctr_drbg_init(&ctr_drbg_);
    mbedtls_entropy_init(&entropy_);
    tls_inited_ = true;

    const char* pers = "openstation_gemini_live";
    int rc = mbedtls_ctr_drbg_seed(&ctr_drbg_, mbedtls_entropy_func, &entropy_,
                                   reinterpret_cast<const unsigned char*>(pers), strlen(pers));
    if (rc != 0) {
        err_out = "ctr_drbg_seed failed: " + mbed_err_str(rc);
        cleanup_tls();
        return false;
    }

    // Load CA bundle from disk if available, plus built-in fallback Google/DigiCert/ISRG roots
    int loaded_certs = 0;
    if (!ca_pem_path.empty() && access(ca_pem_path.c_str(), R_OK) == 0) {
        int r = mbedtls_x509_crt_parse_file(&cacert_, ca_pem_path.c_str());
        if (r >= 0) {
            loaded_certs++;
        } else {
            LOGW("TlsWs", "CA file parse partial/failed (%s): %d", ca_pem_path.c_str(), r);
        }
    }
    {
        int r = mbedtls_x509_crt_parse(
            &cacert_,
            reinterpret_cast<const unsigned char*>(kFallbackCaRootsPem),
            sizeof(kFallbackCaRootsPem));
        if (r >= 0) {
            loaded_certs++;
        }
    }
    if (loaded_certs == 0) {
        err_out = "No valid CA certificates loaded";
        cleanup_tls();
        return false;
    }

    rc = mbedtls_ssl_config_defaults(&conf_,
                                     MBEDTLS_SSL_IS_CLIENT,
                                     MBEDTLS_SSL_TRANSPORT_STREAM,
                                     MBEDTLS_SSL_PRESET_DEFAULT);
    if (rc != 0) {
        err_out = "ssl_config_defaults failed: " + mbed_err_str(rc);
        cleanup_tls();
        return false;
    }

    mbedtls_ssl_conf_authmode(&conf_, MBEDTLS_SSL_VERIFY_REQUIRED);
    mbedtls_ssl_conf_ca_chain(&conf_, &cacert_, nullptr);
    mbedtls_ssl_conf_rng(&conf_, mbedtls_ctr_drbg_random, &ctr_drbg_);

    rc = mbedtls_ssl_setup(&ssl_, &conf_);
    if (rc != 0) {
        err_out = "ssl_setup failed: " + mbed_err_str(rc);
        cleanup_tls();
        return false;
    }

    rc = mbedtls_ssl_set_hostname(&ssl_, host.c_str());
    if (rc != 0) {
        err_out = "ssl_set_hostname failed: " + mbed_err_str(rc);
        cleanup_tls();
        return false;
    }

    mbedtls_ssl_set_bio(&ssl_, this, &TlsWsClient::bio_send, &TlsWsClient::bio_recv, nullptr);

    // TLS handshake
    auto hs_deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    while (!abort_req_.load()) {
        rc = mbedtls_ssl_handshake(&ssl_);
        if (rc == 0) break;
        if (rc == MBEDTLS_ERR_SSL_WANT_READ || rc == MBEDTLS_ERR_SSL_WANT_WRITE) {
            int rem = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                           hs_deadline - std::chrono::steady_clock::now())
                                           .count());
            if (rem <= 0) {
                err_out = "TLS handshake timed out";
                cleanup_tls();
                return false;
            }
            short ev = (rc == MBEDTLS_ERR_SSL_WANT_WRITE) ? POLLOUT : POLLIN;
            int pr = wait_fd_poll(fd, ev, rem, abort_req_);
            if (pr <= 0) {
                err_out = (pr == -2) ? "aborted" : "TLS handshake poll timeout/error";
                cleanup_tls();
                return false;
            }
            continue;
        }
        uint32_t vflags = mbedtls_ssl_get_verify_result(&ssl_);
        char vbuf[256] = {};
        if (vflags != 0) {
            mbedtls_x509_crt_verify_info(vbuf, sizeof(vbuf), "", vflags);
        }
        err_out = "TLS handshake failed: " + mbed_err_str(rc) +
                  (vflags ? std::string(" verify: ") + vbuf : "");
        cleanup_tls();
        return false;
    }
    if (abort_req_.load()) {
        err_out = "aborted";
        cleanup_tls();
        return false;
    }

    uint32_t verify_res = mbedtls_ssl_get_verify_result(&ssl_);
    if (verify_res != 0) {
        char vbuf[256] = {};
        mbedtls_x509_crt_verify_info(vbuf, sizeof(vbuf), "", verify_res);
        err_out = std::string("CA verification failed: ") + vbuf;
        cleanup_tls();
        return false;
    }

    // Generate Sec-WebSocket-Key (16 random bytes base64-encoded)
    uint8_t key_raw[16];
    mbedtls_ctr_drbg_random(&ctr_drbg_, key_raw, sizeof(key_raw));
    std::string ws_key = b64::encode(key_raw, sizeof(key_raw));

    std::string req =
        "GET " + path_with_query + " HTTP/1.1\r\n"
        "Host: " + host + ":" + std::to_string(port) + "\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: " + ws_key + "\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "User-Agent: OpenStationMax/1.0\r\n\r\n";

    if (!tls_write_all(reinterpret_cast<const uint8_t*>(req.data()), req.size(), timeout_ms)) {
        err_out = "Failed to send WebSocket upgrade request";
        cleanup_tls();
        return false;
    }

    // Read HTTP 101 Switching Protocols response headers
    std::string hdrs;
    hdrs.reserve(2048);
    size_t hdr_end = std::string::npos;
    auto ws_deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    while (hdrs.size() < 16384 && !abort_req_.load()) {
        int rem = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                       ws_deadline - std::chrono::steady_clock::now())
                                       .count());
        if (rem <= 0) {
            err_out = "WebSocket upgrade response timeout";
            cleanup_tls();
            return false;
        }
        uint8_t tmp[1024];
        int rerr = 0;
        int n = tls_read_some(tmp, sizeof(tmp), rem, rerr);
        if (n <= 0) {
            err_out = "WebSocket upgrade connection closed (" + mbed_err_str(rerr) + ")";
            cleanup_tls();
            return false;
        }
        hdrs.append(reinterpret_cast<const char*>(tmp), static_cast<size_t>(n));
        hdr_end = hdrs.find("\r\n\r\n");
        if (hdr_end != std::string::npos) break;
    }

    if (hdr_end == std::string::npos) {
        err_out = "Malformed HTTP upgrade response";
        cleanup_tls();
        return false;
    }

    std::string header_block = hdrs.substr(0, hdr_end);
    if (hdr_end + 4 < hdrs.size()) {
        const uint8_t* rest = reinterpret_cast<const uint8_t*>(hdrs.data() + hdr_end + 4);
        rx_buf_.assign(rest, rest + (hdrs.size() - hdr_end - 4));
    }

    size_t first_nl = header_block.find("\r\n");
    std::string status_line = (first_nl == std::string::npos) ? header_block : header_block.substr(0, first_nl);
    if (status_line.find(" 101 ") == std::string::npos) {
        std::string extra = hdrs.substr(hdr_end + 4, std::min<size_t>(256, hdrs.size() - hdr_end - 4));
        err_out = "HTTP upgrade rejected: " + status_line + (extra.empty() ? "" : (" | " + extra));
        cleanup_tls();
        return false;
    }

    // Verify Sec-WebSocket-Accept
    static const char* kWsGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    std::string accept_src = ws_key + kWsGuid;
    uint8_t sha1_out[20];
    mbedtls_sha1(reinterpret_cast<const unsigned char*>(accept_src.data()), accept_src.size(), sha1_out);
    std::string expected_accept = b64::encode(sha1_out, sizeof(sha1_out));

    std::string actual_accept;
    std::string lower_blk = to_lower_ascii(header_block);
    size_t ak_pos = lower_blk.find("sec-websocket-accept:");
    if (ak_pos != std::string::npos) {
        size_t val_start = ak_pos + strlen("sec-websocket-accept:");
        size_t val_end = header_block.find("\r\n", val_start);
        if (val_end == std::string::npos) val_end = header_block.size();
        actual_accept = header_block.substr(val_start, val_end - val_start);
        while (!actual_accept.empty() && (actual_accept.front() == ' ' || actual_accept.front() == '\t')) {
            actual_accept.erase(actual_accept.begin());
        }
        while (!actual_accept.empty() && (actual_accept.back() == ' ' || actual_accept.back() == '\t')) {
            actual_accept.pop_back();
        }
    }
    if (actual_accept != expected_accept) {
        err_out = "Invalid Sec-WebSocket-Accept header";
        cleanup_tls();
        return false;
    }

    connected_.store(true);
    LOGI("TlsWs", "Connected wss://%s:%d (%s)", host.c_str(), port, mbedtls_ssl_get_version(&ssl_));
    return true;
}

bool TlsWsClient::tls_write_all(const uint8_t* data, size_t len, int timeout_ms) {
    size_t off = 0;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    while (off < len && !abort_req_.load()) {
        int rc = mbedtls_ssl_write(&ssl_, data + off, len - off);
        if (rc > 0) {
            off += static_cast<size_t>(rc);
            continue;
        }
        if (rc == MBEDTLS_ERR_SSL_WANT_WRITE || rc == MBEDTLS_ERR_SSL_WANT_READ) {
            int rem = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                           deadline - std::chrono::steady_clock::now())
                                           .count());
            if (rem <= 0) return false;
            int fd = sock_fd_.load();
            if (fd < 0) return false;
            short ev = (rc == MBEDTLS_ERR_SSL_WANT_WRITE) ? POLLOUT : POLLIN;
            if (wait_fd_poll(fd, ev, rem, abort_req_) <= 0) return false;
            continue;
        }
        return false;
    }
    return off == len;
}

int TlsWsClient::tls_read_some(uint8_t* dst, size_t cap, int timeout_ms, int& out_err) {
    out_err = 0;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max(0, timeout_ms));
    while (!abort_req_.load()) {
        // Check if MbedTLS already has decrypted application bytes ready without touching the socket
        if (mbedtls_ssl_get_bytes_avail(&ssl_) == 0) {
            int fd = sock_fd_.load();
            if (fd < 0) {
                out_err = MBEDTLS_ERR_NET_RECV_FAILED;
                return -1;
            }
            int rem = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                           deadline - std::chrono::steady_clock::now())
                                           .count());
            if (timeout_ms == 0) rem = 0;
            else if (rem <= 0) {
                return 0; // timeout
            }
            int pr = wait_fd_poll(fd, POLLIN, rem, abort_req_);
            if (pr == 0) return 0; // timeout
            if (pr < 0) {
                out_err = MBEDTLS_ERR_NET_RECV_FAILED;
                return -1;
            }
        }

        int rc = mbedtls_ssl_read(&ssl_, dst, cap);
        if (rc > 0) {
            return rc;
        }
        if (rc == MBEDTLS_ERR_SSL_WANT_READ || rc == MBEDTLS_ERR_SSL_WANT_WRITE) {
            if (timeout_ms == 0) return 0;
            continue;
        }
        if (rc == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY || rc == 0) {
            out_err = rc;
            return -2; // clean close
        }
        out_err = rc;
        return -1;
    }
    out_err = MBEDTLS_ERR_NET_RECV_FAILED;
    return -1;
}

bool TlsWsClient::send_ws_frame(uint8_t opcode, const uint8_t* data, size_t len) {
    if (!connected_.load()) return false;
    std::lock_guard<std::mutex> lk(send_mu_);
    if (!connected_.load() || abort_req_.load()) return false;

    uint8_t mask[4];
    mbedtls_ctr_drbg_random(&ctr_drbg_, mask, sizeof(mask));

    std::vector<uint8_t> pkt;
    pkt.reserve(14 + len);
    pkt.push_back(static_cast<uint8_t>(0x80 | (opcode & 0x0F)));
    if (len <= 125) {
        pkt.push_back(static_cast<uint8_t>(0x80 | len));
    } else if (len <= 65535) {
        pkt.push_back(static_cast<uint8_t>(0x80 | 126));
        pkt.push_back(static_cast<uint8_t>((len >> 8) & 0xFF));
        pkt.push_back(static_cast<uint8_t>(len & 0xFF));
    } else {
        pkt.push_back(static_cast<uint8_t>(0x80 | 127));
        for (int i = 0; i < 8; ++i) {
            pkt.push_back(static_cast<uint8_t>((static_cast<uint64_t>(len) >> ((7 - i) * 8)) & 0xFF));
        }
    }
    pkt.insert(pkt.end(), mask, mask + 4);
    for (size_t i = 0; i < len; ++i) {
        pkt.push_back(static_cast<uint8_t>(data[i] ^ mask[i & 3]));
    }
    bool ok = tls_write_all(pkt.data(), pkt.size(), 5000);
    if (!ok) {
        connected_.store(false);
    }
    return ok;
}

bool TlsWsClient::send_text(const std::string& text) {
    return send_ws_frame(0x01, reinterpret_cast<const uint8_t*>(text.data()), text.size());
}

bool TlsWsClient::send_pong(const std::string& payload) {
    return send_ws_frame(0x0A, reinterpret_cast<const uint8_t*>(payload.data()), payload.size());
}

void TlsWsClient::send_close(uint16_t code) {
    uint8_t buf[2] = {
        static_cast<uint8_t>((code >> 8) & 0xFF),
        static_cast<uint8_t>(code & 0xFF)
    };
    send_ws_frame(0x08, buf, 2);
}

bool TlsWsClient::try_pop_ws_frame(bool& complete_msg, WsFrame& out, bool& protocol_err) {
    complete_msg = false;
    protocol_err = false;
    if (rx_buf_.size() < 2) return false;

    uint8_t b0 = rx_buf_[0];
    uint8_t b1 = rx_buf_[1];
    bool fin = (b0 & 0x80) != 0;
    uint8_t opcode = b0 & 0x0F;
    bool masked = (b1 & 0x80) != 0;
    uint64_t plen = b1 & 0x7F;
    size_t hdr_len = 2;

    if (plen == 126) {
        if (rx_buf_.size() < 4) return false;
        plen = (static_cast<uint64_t>(rx_buf_[2]) << 8) | rx_buf_[3];
        hdr_len = 4;
    } else if (plen == 127) {
        if (rx_buf_.size() < 10) return false;
        plen = 0;
        for (int i = 0; i < 8; ++i) {
            plen = (plen << 8) | rx_buf_[2 + i];
        }
        hdr_len = 10;
    }

    if (plen > kMaxWsPayloadBytes) {
        protocol_err = true;
        return false;
    }

    size_t mask_off = hdr_len;
    if (masked) hdr_len += 4;
    if (rx_buf_.size() < hdr_len + static_cast<size_t>(plen)) return false;

    std::string body(static_cast<size_t>(plen), '\0');
    if (plen > 0) {
        memcpy(&body[0], rx_buf_.data() + hdr_len, static_cast<size_t>(plen));
        if (masked) {
            const uint8_t* m = rx_buf_.data() + mask_off;
            for (size_t i = 0; i < body.size(); ++i) body[i] ^= static_cast<char>(m[i & 3]);
        }
    }
    rx_buf_.erase(rx_buf_.begin(), rx_buf_.begin() + static_cast<ptrdiff_t>(hdr_len + plen));

    // Control frames (0x08..0x0F) may be interleaved inside fragmented messages
    if (opcode >= 0x08) {
        out.opcode = opcode;
        out.payload = std::move(body);
        if (opcode == 0x08 && out.payload.size() >= 2) {
            out.close_code = (static_cast<uint16_t>(static_cast<uint8_t>(out.payload[0])) << 8) |
                             static_cast<uint8_t>(out.payload[1]);
        } else {
            out.close_code = 0;
        }
        complete_msg = true;
        return true;
    }

    if (opcode == 0x00) {
        if (!in_fragment_) {
            protocol_err = true;
            return false;
        }
        frag_acc_.append(body);
        if (frag_acc_.size() > kMaxWsPayloadBytes) {
            protocol_err = true;
            return false;
        }
        if (fin) {
            out.opcode = frag_opcode_;
            out.payload = std::move(frag_acc_);
            out.close_code = 0;
            frag_acc_.clear();
            in_fragment_ = false;
            complete_msg = true;
        }
        return true;
    }

    if (!fin) {
        in_fragment_ = true;
        frag_opcode_ = opcode;
        frag_acc_ = std::move(body);
        return true;
    }

    out.opcode = opcode;
    out.payload = std::move(body);
    out.close_code = 0;
    complete_msg = true;
    return true;
}

WsRecvStatus TlsWsClient::recv_frame(WsFrame& out, int timeout_ms, std::string& err_out) {
    if (!connected_.load()) {
        err_out = "not connected";
        return WsRecvStatus::Closed;
    }

    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max(0, timeout_ms));

    while (!abort_req_.load()) {
        bool complete = false;
        bool proto_err = false;
        if (try_pop_ws_frame(complete, out, proto_err)) {
            if (complete) {
                if (out.opcode == 0x09) {
                    send_pong(out.payload);
                    continue;
                }
                if (out.opcode == 0x0A) {
                    continue;
                }
                if (out.opcode == 0x08) {
                    connected_.store(false);
                    return WsRecvStatus::Closed;
                }
                return WsRecvStatus::Frame;
            }
            continue;
        }
        if (proto_err) {
            err_out = "WebSocket frame protocol/size error";
            connected_.store(false);
            return WsRecvStatus::Error;
        }

        int rem = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                       deadline - std::chrono::steady_clock::now())
                                       .count());
        if (timeout_ms == 0) rem = 0;
        else if (rem <= 0) return WsRecvStatus::Timeout;

        uint8_t buf[16384];
        int rerr = 0;
        int n = tls_read_some(buf, sizeof(buf), rem, rerr);
        if (n > 0) {
            rx_buf_.insert(rx_buf_.end(), buf, buf + n);
            continue;
        }
        if (n == 0) {
            return WsRecvStatus::Timeout;
        }
        if (n == -2) {
            connected_.store(false);
            err_out = "TLS peer closed";
            return WsRecvStatus::Closed;
        }
        connected_.store(false);
        err_out = abort_req_.load() ? "aborted" : ("TLS read error: " + mbed_err_str(rerr));
        return abort_req_.load() ? WsRecvStatus::Closed : WsRecvStatus::Error;
    }

    connected_.store(false);
    err_out = "aborted";
    return WsRecvStatus::Closed;
}

} // namespace openstation
