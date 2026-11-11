#include "net/http_client.hpp"
#include "assistant/ca_roots_fallback.hpp"
#include "assistant/tls_ws_client.hpp"
#include "utils/logger.hpp"

#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/error.h"
#include "mbedtls/net_sockets.h"
#include "mbedtls/ssl.h"
#include "mbedtls/x509_crt.h"
#include "psa/crypto.h"

#include <algorithm>
#include <arpa/inet.h>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <vector>

namespace openstation {

namespace {

constexpr const char* kCaBundlePath = "/data/local/openstation/cacert.pem";
constexpr const char* kDefaultUserAgent =
    "Mozilla/5.0 (Linux; Android 9; OpenStationMax) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

std::once_flag g_http_tls_once;
bool g_http_ca_ok = false;
mbedtls_x509_crt g_http_cacert;

mbedtls_x509_crt* get_shared_ca_chain() {
    std::call_once(g_http_tls_once, []() {
        psa_crypto_init();
        mbedtls_x509_crt_init(&g_http_cacert);
        int loaded = 0;
        if (access(kCaBundlePath, R_OK) == 0) {
            int r = mbedtls_x509_crt_parse_file(&g_http_cacert, kCaBundlePath);
            if (r >= 0) loaded++;
            else LOGW("HttpClient", "CA file parse partial/failed (%s): %d", kCaBundlePath, r);
        }
        {
            int r = mbedtls_x509_crt_parse(
                &g_http_cacert,
                reinterpret_cast<const unsigned char*>(kFallbackCaRootsPem),
                sizeof(kFallbackCaRootsPem));
            if (r >= 0) loaded++;
        }
        g_http_ca_ok = (loaded > 0);
        LOGI("HttpClient", "Initialized shared CA chain (%s)", g_http_ca_ok ? "ok" : "empty");
    });
    return g_http_ca_ok ? &g_http_cacert : nullptr;
}

std::string to_lower_ascii(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string trim_ws(std::string s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t' || s.front() == '\r' || s.front() == '\n')) {
        s.erase(s.begin());
    }
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t' || s.back() == '\r' || s.back() == '\n')) {
        s.pop_back();
    }
    return s;
}

std::string mbed_err(int code) {
    char buf[160];
    mbedtls_strerror(code, buf, sizeof(buf));
    return std::string(buf) + " (" + std::to_string(code) + ")";
}

bool aborted(const std::atomic<bool>* flag) {
    return flag && flag->load();
}

int wait_fd(int fd, short events, int timeout_ms, const std::atomic<bool>* abort_req) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max(0, timeout_ms));
    while (!aborted(abort_req)) {
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
    return -2;
}

struct ParsedUrl {
    bool valid = false;
    std::string scheme; // "http" or "https"
    std::string host;
    int port = 0;
    bool explicit_port = false;
    std::string target; // path + ?query starting with /
};

ParsedUrl parse_url(const std::string& raw) {
    ParsedUrl u;
    std::string s = trim_ws(raw);
    size_t sc_end = s.find("://");
    if (sc_end == std::string::npos) return u;
    u.scheme = to_lower_ascii(s.substr(0, sc_end));
    if (u.scheme != "http" && u.scheme != "https") return u;
    u.port = (u.scheme == "https") ? 443 : 80;

    size_t auth_start = sc_end + 3;
    size_t path_start = s.find_first_of("/?#", auth_start);
    std::string authority;
    if (path_start == std::string::npos) {
        authority = s.substr(auth_start);
        u.target = "/";
    } else {
        authority = s.substr(auth_start, path_start - auth_start);
        if (s[path_start] == '?') {
            u.target = "/" + s.substr(path_start);
        } else if (s[path_start] == '#') {
            u.target = "/";
        } else {
            u.target = s.substr(path_start);
            size_t frag = u.target.find('#');
            if (frag != std::string::npos) u.target.resize(frag);
            if (u.target.empty()) u.target = "/";
        }
    }

    // Strip userinfo@ if present
    size_t at_pos = authority.rfind('@');
    if (at_pos != std::string::npos) {
        authority = authority.substr(at_pos + 1);
    }

    if (authority.empty()) return u;
    if (authority.front() == '[') {
        size_t rb = authority.find(']');
        if (rb == std::string::npos) return u;
        u.host = authority.substr(1, rb - 1);
        if (rb + 1 < authority.size() && authority[rb + 1] == ':') {
            int p = std::atoi(authority.c_str() + rb + 2);
            if (p <= 0 || p > 65535) return u;
            u.port = p;
            u.explicit_port = true;
        }
    } else {
        size_t colon = authority.rfind(':');
        if (colon != std::string::npos) {
            u.host = authority.substr(0, colon);
            int p = std::atoi(authority.c_str() + colon + 1);
            if (p <= 0 || p > 65535) return u;
            u.port = p;
            u.explicit_port = true;
        } else {
            u.host = authority;
        }
    }
    if (u.host.empty()) return u;
    u.valid = true;
    return u;
}

std::string resolve_redirect_url(const ParsedUrl& base, const std::string& location) {
    std::string loc = trim_ws(location);
    if (loc.empty()) return "";
    if (loc.rfind("http://", 0) == 0 || loc.rfind("https://", 0) == 0) {
        return loc;
    }
    std::string origin = base.scheme + "://" + base.host;
    if (base.explicit_port) origin += ":" + std::to_string(base.port);
    if (loc.rfind("//", 0) == 0) {
        return base.scheme + ":" + loc;
    }
    if (loc.front() == '/') {
        return origin + loc;
    }
    std::string path = base.target;
    size_t q = path.find('?');
    if (q != std::string::npos) path.resize(q);
    size_t slash = path.rfind('/');
    std::string dir = (slash == std::string::npos) ? "/" : path.substr(0, slash + 1);
    return origin + dir + loc;
}

int connect_tcp(const std::string& host,
                int port,
                int timeout_ms,
                const std::atomic<bool>* abort_req,
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

    std::vector<struct addrinfo*> order;
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family == AF_INET) order.push_back(p);
    }
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family != AF_INET) order.push_back(p);
    }

    int last_err = ETIMEDOUT;
    for (struct addrinfo* p : order) {
        if (aborted(abort_req)) {
            freeaddrinfo(res);
            err_out = "aborted";
            return -1;
        }
        int fd = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (fd < 0) {
            last_err = errno;
            continue;
        }
        int fl = fcntl(fd, F_GETFL, 0);
        if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);

        int rc = ::connect(fd, p->ai_addr, p->ai_addrlen);
        if (rc == 0) {
            freeaddrinfo(res);
            return fd;
        }
        if (errno != EINPROGRESS) {
            last_err = errno;
            ::close(fd);
            continue;
        }

        int per_addr_ms = std::min(timeout_ms, 4000);
        int pr = wait_fd(fd, POLLOUT, per_addr_ms, abort_req);
        if (pr > 0) {
            int so_err = 0;
            socklen_t sl = sizeof(so_err);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &sl) == 0 && so_err == 0) {
                freeaddrinfo(res);
                return fd;
            }
            last_err = (so_err != 0) ? so_err : ECONNREFUSED;
        } else {
            last_err = (pr == -2) ? ECANCELED : ETIMEDOUT;
        }
        ::close(fd);
    }
    freeaddrinfo(res);
    err_out = std::string("TCP connect failed: ") + strerror(last_err);
    return -1;
}

struct BioCtx {
    int fd = -1;
    const std::atomic<bool>* abort_req = nullptr;
};

int tls_bio_send(void* ctx, const unsigned char* buf, size_t len) {
    auto* b = static_cast<BioCtx*>(ctx);
    if (b->fd < 0 || aborted(b->abort_req)) return MBEDTLS_ERR_NET_SEND_FAILED;
    ssize_t n = ::send(b->fd, buf, len, MSG_NOSIGNAL);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return MBEDTLS_ERR_SSL_WANT_WRITE;
        return MBEDTLS_ERR_NET_SEND_FAILED;
    }
    return static_cast<int>(n);
}

int tls_bio_recv(void* ctx, unsigned char* buf, size_t len) {
    auto* b = static_cast<BioCtx*>(ctx);
    if (b->fd < 0 || aborted(b->abort_req)) return MBEDTLS_ERR_NET_RECV_FAILED;
    ssize_t n = ::recv(b->fd, buf, len, 0);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return MBEDTLS_ERR_SSL_WANT_READ;
        return MBEDTLS_ERR_NET_RECV_FAILED;
    }
    return static_cast<int>(n);
}

bool decode_chunked(const std::string& raw, std::string& out, size_t max_bytes, bool& truncated) {
    out.clear();
    size_t pos = 0;
    while (pos < raw.size()) {
        size_t line_end = raw.find("\r\n", pos);
        if (line_end == std::string::npos) return false;
        std::string sz_line = raw.substr(pos, line_end - pos);
        size_t semi = sz_line.find(';');
        if (semi != std::string::npos) sz_line.resize(semi);
        sz_line = trim_ws(sz_line);
        if (sz_line.empty()) return false;
        char* endp = nullptr;
        unsigned long long chunk_len = std::strtoull(sz_line.c_str(), &endp, 16);
        if (endp == sz_line.c_str()) return false;
        pos = line_end + 2;
        if (chunk_len == 0) {
            return true;
        }
        size_t avail = raw.size() - pos;
        size_t take = static_cast<size_t>(std::min<unsigned long long>(chunk_len, avail));
        if (out.size() < max_bytes) {
            size_t room = max_bytes - out.size();
            size_t copy_n = std::min(take, room);
            out.append(raw, pos, copy_n);
            if (copy_n < take) truncated = true;
        } else {
            truncated = true;
        }
        pos += take;
        if (take < chunk_len) {
            return false; // partial chunk due to max_bytes cap or abrupt EOF
        }
        if (pos + 2 <= raw.size() && raw[pos] == '\r' && raw[pos + 1] == '\n') {
            pos += 2;
        } else if (pos + 1 <= raw.size() && raw[pos] == '\n') {
            pos += 1;
        }
    }
    return false;
}

HttpClientResponse perform_single(const HttpClientRequest& req) {
    HttpClientResponse resp;
    resp.final_url = req.url;

    ParsedUrl u = parse_url(req.url);
    if (!u.valid) {
        resp.error = "Invalid URL: " + req.url;
        return resp;
    }

    const bool use_tls = (u.scheme == "https");
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::clamp(req.timeout_ms, 500, 60000));
    auto rem_ms = [&]() -> int {
        auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                        deadline - std::chrono::steady_clock::now())
                        .count();
        return static_cast<int>(std::max<int64_t>(0, left));
    };

    mbedtls_x509_crt* ca_chain = nullptr;
    if (use_tls) {
        ensure_sntp_time(1500);
        ca_chain = get_shared_ca_chain();
        if (req.verify_tls && !ca_chain) {
            resp.error = "No CA roots available";
            return resp;
        }
    }

    std::string conn_err;
    int fd = connect_tcp(u.host, u.port, std::max(1000, rem_ms()), req.abort_req, conn_err);
    if (fd < 0) {
        resp.error = conn_err;
        return resp;
    }

    BioCtx bio{fd, req.abort_req};
    mbedtls_ssl_context ssl {};
    mbedtls_ssl_config conf {};
    mbedtls_ctr_drbg_context drbg {};
    mbedtls_entropy_context entropy {};
    bool tls_init = false;

    auto cleanup = [&]() {
        if (tls_init) {
            mbedtls_ssl_free(&ssl);
            mbedtls_ssl_config_free(&conf);
            mbedtls_ctr_drbg_free(&drbg);
            mbedtls_entropy_free(&entropy);
            tls_init = false;
        }
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
    };

    if (use_tls) {
        mbedtls_ssl_init(&ssl);
        mbedtls_ssl_config_init(&conf);
        mbedtls_ctr_drbg_init(&drbg);
        mbedtls_entropy_init(&entropy);
        tls_init = true;

        const char* pers = "openstation_httpclient";
        int rc = mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &entropy,
                                       reinterpret_cast<const unsigned char*>(pers), strlen(pers));
        if (rc != 0 ||
            (rc = mbedtls_ssl_config_defaults(&conf, MBEDTLS_SSL_IS_CLIENT,
                                              MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT)) != 0) {
            resp.error = "TLS config failed: " + mbed_err(rc);
            cleanup();
            return resp;
        }

        if (req.verify_tls) {
            mbedtls_ssl_conf_authmode(&conf, MBEDTLS_SSL_VERIFY_REQUIRED);
            mbedtls_ssl_conf_ca_chain(&conf, ca_chain, nullptr);
        } else {
            mbedtls_ssl_conf_authmode(&conf, MBEDTLS_SSL_VERIFY_NONE);
        }
        mbedtls_ssl_conf_rng(&conf, mbedtls_ctr_drbg_random, &drbg);

        if ((rc = mbedtls_ssl_setup(&ssl, &conf)) != 0 ||
            (rc = mbedtls_ssl_set_hostname(&ssl, u.host.c_str())) != 0) {
            resp.error = "TLS setup failed: " + mbed_err(rc);
            cleanup();
            return resp;
        }
        mbedtls_ssl_set_bio(&ssl, &bio, &tls_bio_send, &tls_bio_recv, nullptr);

        while (!aborted(req.abort_req)) {
            rc = mbedtls_ssl_handshake(&ssl);
            if (rc == 0) break;
            if (rc == MBEDTLS_ERR_SSL_WANT_READ || rc == MBEDTLS_ERR_SSL_WANT_WRITE) {
                int r = rem_ms();
                if (r <= 0) {
                    resp.error = "TLS handshake timeout";
                    cleanup();
                    return resp;
                }
                short ev = (rc == MBEDTLS_ERR_SSL_WANT_WRITE) ? POLLOUT : POLLIN;
                int pr = wait_fd(fd, ev, r, req.abort_req);
                if (pr <= 0) {
                    resp.error = (pr == -2) ? "aborted" : "TLS handshake timeout";
                    cleanup();
                    return resp;
                }
                continue;
            }
            uint32_t vf = mbedtls_ssl_get_verify_result(&ssl);
            char vb[192] = {};
            if (vf != 0) mbedtls_x509_crt_verify_info(vb, sizeof(vb), "", vf);
            resp.error = "TLS handshake failed: " + mbed_err(rc) + (vf ? std::string(" ") + vb : "");
            cleanup();
            return resp;
        }
        if (aborted(req.abort_req)) {
            resp.error = "aborted";
            cleanup();
            return resp;
        }
    }

    auto write_all = [&](const uint8_t* data, size_t len) -> bool {
        size_t off = 0;
        while (off < len && !aborted(req.abort_req)) {
            int rc = 0;
            if (use_tls) {
                rc = mbedtls_ssl_write(&ssl, data + off, len - off);
                if (rc > 0) { off += static_cast<size_t>(rc); continue; }
                if (rc != MBEDTLS_ERR_SSL_WANT_WRITE && rc != MBEDTLS_ERR_SSL_WANT_READ) return false;
            } else {
                ssize_t n = ::send(fd, data + off, len - off, MSG_NOSIGNAL);
                if (n > 0) { off += static_cast<size_t>(n); continue; }
                if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) return false;
                rc = MBEDTLS_ERR_SSL_WANT_WRITE;
            }
            int r = rem_ms();
            if (r <= 0) return false;
            short ev = (rc == MBEDTLS_ERR_SSL_WANT_READ) ? POLLIN : POLLOUT;
            if (wait_fd(fd, ev, r, req.abort_req) <= 0) return false;
        }
        return off == len;
    };

    auto read_some = [&](uint8_t* dst, size_t cap, int& err_code) -> int {
        err_code = 0;
        while (!aborted(req.abort_req)) {
            if (use_tls && mbedtls_ssl_get_bytes_avail(&ssl) == 0) {
                int r = rem_ms();
                if (r <= 0) return 0; // timeout
                int pr = wait_fd(fd, POLLIN, r, req.abort_req);
                if (pr == 0) return 0;
                if (pr < 0) { err_code = -1; return -1; }
            } else if (!use_tls) {
                int r = rem_ms();
                if (r <= 0) return 0;
                int pr = wait_fd(fd, POLLIN, r, req.abort_req);
                if (pr == 0) return 0;
                if (pr < 0) { err_code = -1; return -1; }
            }

            if (use_tls) {
                int rc = mbedtls_ssl_read(&ssl, dst, cap);
                if (rc > 0) return rc;
                if (rc == MBEDTLS_ERR_SSL_WANT_READ || rc == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
                if (rc == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY || rc == 0) return -2;
                err_code = rc;
                return -1;
            } else {
                ssize_t n = ::recv(fd, dst, cap, 0);
                if (n > 0) return static_cast<int>(n);
                if (n == 0) return -2;
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue;
                err_code = errno;
                return -1;
            }
        }
        return -1;
    };

    // Build HTTP request
    std::string method = req.method.empty() ? "GET" : req.method;
    for (char& c : method) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));

    std::string host_hdr = u.host;
    if (u.explicit_port) host_hdr += ":" + std::to_string(u.port);

    bool has_ua = false;
    bool has_accept = false;
    bool has_ct = false;
    std::string extra_hdrs;
    for (const auto& [k, v] : req.headers) {
        std::string lk = to_lower_ascii(k);
        if (lk == "host" || lk == "connection" || lk == "content-length" || lk == "accept-encoding") continue;
        if (lk == "user-agent") has_ua = true;
        if (lk == "accept") has_accept = true;
        if (lk == "content-type") has_ct = true;
        extra_hdrs += k + ": " + v + "\r\n";
    }

    std::string raw_req;
    raw_req.reserve(512 + req.body.size());
    raw_req += method + " " + u.target + " HTTP/1.1\r\n";
    raw_req += "Host: " + host_hdr + "\r\n";
    if (!has_ua) raw_req += std::string("User-Agent: ") + kDefaultUserAgent + "\r\n";
    if (!has_accept) raw_req += "Accept: */*\r\n";
    raw_req += "Accept-Encoding: identity\r\n";
    raw_req += "Connection: close\r\n";
    raw_req += extra_hdrs;
    if (!req.body.empty()) {
        if (!has_ct) raw_req += "Content-Type: application/json\r\n";
        raw_req += "Content-Length: " + std::to_string(req.body.size()) + "\r\n";
    }
    raw_req += "\r\n";
    if (!req.body.empty()) raw_req += req.body;

    if (!write_all(reinterpret_cast<const uint8_t*>(raw_req.data()), raw_req.size())) {
        resp.error = "Failed to send HTTP request";
        cleanup();
        return resp;
    }

    // Read response headers + initial body bytes
    std::string raw_rx;
    raw_rx.reserve(8192);
    size_t hdr_end = std::string::npos;
    uint8_t buf[8192];
    while (raw_rx.size() < 65536) {
        int rerr = 0;
        int n = read_some(buf, sizeof(buf), rerr);
        if (n > 0) {
            raw_rx.append(reinterpret_cast<const char*>(buf), static_cast<size_t>(n));
            hdr_end = raw_rx.find("\r\n\r\n");
            if (hdr_end != std::string::npos) break;
            continue;
        }
        if (n == -2) break;
        resp.error = (n == 0) ? "HTTP response header timeout" : "HTTP response read error";
        cleanup();
        return resp;
    }

    if (hdr_end == std::string::npos) {
        resp.error = "Malformed HTTP response headers";
        cleanup();
        return resp;
    }

    std::string header_block = raw_rx.substr(0, hdr_end);
    std::string body_raw = raw_rx.substr(hdr_end + 4);

    // Parse status line
    size_t first_nl = header_block.find("\r\n");
    std::string status_line = (first_nl == std::string::npos) ? header_block : header_block.substr(0, first_nl);
    size_t sp1 = status_line.find(' ');
    if (sp1 != std::string::npos) {
        size_t sp2 = status_line.find(' ', sp1 + 1);
        resp.status_code = std::atoi(status_line.substr(sp1 + 1).c_str());
        if (sp2 != std::string::npos) resp.status_text = trim_ws(status_line.substr(sp2 + 1));
    }

    // Parse headers
    size_t pos = (first_nl == std::string::npos) ? header_block.size() : (first_nl + 2);
    while (pos < header_block.size()) {
        size_t nl = header_block.find("\r\n", pos);
        if (nl == std::string::npos) nl = header_block.size();
        std::string line = header_block.substr(pos, nl - pos);
        size_t colon = line.find(':');
        if (colon != std::string::npos) {
            std::string k = to_lower_ascii(trim_ws(line.substr(0, colon)));
            std::string v = trim_ws(line.substr(colon + 1));
            resp.headers[k] = v;
        }
        pos = nl + 2;
    }

    if (auto it = resp.headers.find("content-type"); it != resp.headers.end()) {
        resp.content_type = it->second;
    }

    bool no_body = (method == "HEAD" || resp.status_code == 204 || resp.status_code == 304);
    bool is_chunked = false;
    if (auto it = resp.headers.find("transfer-encoding"); it != resp.headers.end()) {
        if (to_lower_ascii(it->second).find("chunked") != std::string::npos) is_chunked = true;
    }
    int64_t content_len = -1;
    if (auto it = resp.headers.find("content-length"); it != resp.headers.end()) {
        content_len = std::atoll(it->second.c_str());
    }

    const size_t cap = std::max<size_t>(4096, req.max_body_bytes);
    const size_t raw_cap = cap + 65536; // room for chunk headers

    if (!no_body) {
        while (true) {
            if (is_chunked) {
                if (body_raw.find("\r\n0\r\n") != std::string::npos ||
                    body_raw.rfind("0\r\n\r\n", 0) == 0 ||
                    body_raw.size() >= raw_cap) {
                    break;
                }
            } else if (content_len >= 0) {
                if (static_cast<int64_t>(body_raw.size()) >= content_len || body_raw.size() >= cap) {
                    break;
                }
            } else if (body_raw.size() >= cap) {
                break;
            }

            if (rem_ms() <= 0) break;
            int rerr = 0;
            int n = read_some(buf, sizeof(buf), rerr);
            if (n > 0) {
                body_raw.append(reinterpret_cast<const char*>(buf), static_cast<size_t>(n));
                continue;
            }
            if (n == -2 || n == 0) break;
            break;
        }
    }

    cleanup();

    if (no_body) {
        resp.body.clear();
    } else if (is_chunked) {
        std::string decoded;
        if (decode_chunked(body_raw, decoded, cap, resp.truncated)) {
            resp.body = std::move(decoded);
        } else if (!decoded.empty()) {
            resp.body = std::move(decoded);
            resp.truncated = true;
        } else {
            if (body_raw.size() > cap) {
                body_raw.resize(cap);
                resp.truncated = true;
            }
            resp.body = std::move(body_raw);
        }
    } else {
        if (content_len >= 0 && static_cast<size_t>(content_len) < body_raw.size()) {
            body_raw.resize(static_cast<size_t>(content_len));
        }
        if (body_raw.size() > cap) {
            body_raw.resize(cap);
            resp.truncated = true;
        }
        resp.body = std::move(body_raw);
    }

    resp.ok = (resp.status_code > 0);
    return resp;
}

std::string cp_to_utf8(uint32_t cp) {
    std::string out;
    if (cp <= 0x7F) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0x10FFFF) {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
    return out;
}

bool is_valid_utf8(const std::string& s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        if (c < 0x80) { ++i; continue; }
        size_t need = 0;
        if ((c & 0xE0) == 0xC0 && c >= 0xC2) need = 1;
        else if ((c & 0xF0) == 0xE0) need = 2;
        else if ((c & 0xF8) == 0xF0 && c <= 0xF4) need = 3;
        else return false;
        if (i + need >= n) return false;
        for (size_t k = 1; k <= need; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) return false;
        }
        i += need + 1;
    }
    return true;
}

} // namespace

HttpClientResponse http_perform(const HttpClientRequest& req) {
    HttpClientRequest cur = req;
    int max_redirs = std::clamp(req.max_redirects, 0, 10);
    for (int hop = 0; hop <= max_redirs; ++hop) {
        HttpClientResponse r = perform_single(cur);
        if (!r.ok) return r;
        if ((r.status_code == 301 || r.status_code == 302 || r.status_code == 303 ||
             r.status_code == 307 || r.status_code == 308) &&
            hop < max_redirs) {
            auto loc_it = r.headers.find("location");
            if (loc_it != r.headers.end() && !loc_it->second.empty()) {
                ParsedUrl base = parse_url(cur.url);
                std::string next_url = resolve_redirect_url(base, loc_it->second);
                if (!next_url.empty()) {
                    cur.url = next_url;
                    if (r.status_code == 301 || r.status_code == 302 || r.status_code == 303) {
                        std::string lm = to_lower_ascii(cur.method);
                        if (lm != "get" && lm != "head") {
                            cur.method = "GET";
                            cur.body.clear();
                        }
                    }
                    continue;
                }
            }
        }
        return r;
    }
    HttpClientResponse err;
    err.error = "Too many redirects";
    return err;
}

std::string url_encode(const std::string& s) {
    static const char* kHex = "0123456789ABCDEF";
    std::string out;
    out.reserve(s.size() + 12);
    for (unsigned char c : s) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
            out.push_back(static_cast<char>(c));
        } else if (c == ' ') {
            out.push_back('+');
        } else {
            out.push_back('%');
            out.push_back(kHex[(c >> 4) & 0x0F]);
            out.push_back(kHex[c & 0x0F]);
        }
    }
    return out;
}

std::string cp1251_to_utf8(const std::string& in) {
    static const uint16_t kUpper[128] = {
        0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021,
        0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F,
        0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x0020, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F,
        0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7,
        0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407,
        0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7,
        0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457,
        0x0410, 0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417,
        0x0418, 0x0419, 0x041A, 0x041B, 0x041C, 0x041D, 0x041E, 0x041F,
        0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0425, 0x0426, 0x0427,
        0x0428, 0x0429, 0x042A, 0x042B, 0x042C, 0x042D, 0x042E, 0x042F,
        0x0430, 0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0437,
        0x0438, 0x0439, 0x043A, 0x043B, 0x043C, 0x043D, 0x043E, 0x043F,
        0x0440, 0x0441, 0x0442, 0x0443, 0x0444, 0x0445, 0x0446, 0x0447,
        0x0448, 0x0449, 0x044A, 0x044B, 0x044C, 0x044D, 0x044E, 0x044F
    };
    std::string out;
    out.reserve(in.size() * 2);
    for (unsigned char c : in) {
        if (c < 0x80) out.push_back(static_cast<char>(c));
        else out += cp_to_utf8(kUpper[c - 128]);
    }
    return out;
}

std::string ensure_utf8_and_truncate(const std::string& raw, size_t max_bytes,
                                     const std::string& content_type_hint) {
    std::string s;
    std::string hint = to_lower_ascii(content_type_hint);
    bool maybe_1251 = (hint.find("1251") != std::string::npos);
    if (!maybe_1251 && raw.size() > 12) {
        std::string head = to_lower_ascii(raw.substr(0, std::min<size_t>(512, raw.size())));
        if (head.find("windows-1251") != std::string::npos || head.find("cp1251") != std::string::npos) {
            maybe_1251 = true;
        }
    }
    if (maybe_1251 && !is_valid_utf8(raw)) {
        s = cp1251_to_utf8(raw);
    } else if (is_valid_utf8(raw)) {
        s = raw;
    } else {
        // Scrub invalid sequences cleanly so JSON never emits broken UTF-8
        s.reserve(raw.size());
        size_t i = 0;
        const size_t n = raw.size();
        while (i < n) {
            unsigned char c = static_cast<unsigned char>(raw[i]);
            if (c < 0x80) {
                s.push_back(static_cast<char>(c));
                ++i;
            } else {
                size_t need = 0;
                if ((c & 0xE0) == 0xC0 && c >= 0xC2) need = 1;
                else if ((c & 0xF0) == 0xE0) need = 2;
                else if ((c & 0xF8) == 0xF0 && c <= 0xF4) need = 3;
                bool ok = (need > 0 && i + need < n);
                for (size_t k = 1; ok && k <= need; ++k) {
                    if ((static_cast<unsigned char>(raw[i + k]) & 0xC0) != 0x80) ok = false;
                }
                if (ok) {
                    s.append(raw, i, need + 1);
                    i += need + 1;
                } else {
                    s.push_back('?');
                    ++i;
                }
            }
        }
    }

    if (max_bytes == 0 || s.size() <= max_bytes) return s;
    size_t cut = max_bytes;
    // Do not split a multi-byte UTF-8 rune
    while (cut > 0 && (static_cast<unsigned char>(s[cut]) & 0xC0) == 0x80) {
        --cut;
    }
    s.resize(cut);
    return s;
}

std::string html_decode_entities(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        if (in[i] == '&') {
            size_t semi = in.find(';', i + 1);
            if (semi != std::string::npos && semi - i <= 12) {
                std::string ent = in.substr(i + 1, semi - i - 1);
                if (ent == "amp") { out += '&'; i = semi + 1; continue; }
                if (ent == "lt") { out += '<'; i = semi + 1; continue; }
                if (ent == "gt") { out += '>'; i = semi + 1; continue; }
                if (ent == "quot") { out += '"'; i = semi + 1; continue; }
                if (ent == "apos" || ent == "#39") { out += '\''; i = semi + 1; continue; }
                if (ent == "nbsp") { out += ' '; i = semi + 1; continue; }
                if (ent == "mdash") { out += "\xE2\x80\x94"; i = semi + 1; continue; }
                if (ent == "ndash") { out += "\xE2\x80\x93"; i = semi + 1; continue; }
                if (ent == "laquo") { out += "\xC2\xAB"; i = semi + 1; continue; }
                if (ent == "raquo") { out += "\xC2\xBB"; i = semi + 1; continue; }
                if (!ent.empty() && ent[0] == '#') {
                    uint32_t cp = 0;
                    if (ent.size() > 1 && (ent[1] == 'x' || ent[1] == 'X')) {
                        cp = static_cast<uint32_t>(std::strtoul(ent.c_str() + 2, nullptr, 16));
                    } else {
                        cp = static_cast<uint32_t>(std::strtoul(ent.c_str() + 1, nullptr, 10));
                    }
                    if (cp > 0 && cp <= 0x10FFFF) {
                        out += cp_to_utf8(cp);
                        i = semi + 1;
                        continue;
                    }
                }
            }
        }
        out.push_back(in[i++]);
    }
    return out;
}

std::string html_to_text(const std::string& html, size_t max_chars) {
    std::string out;
    out.reserve(std::min<size_t>(html.size(), max_chars + 256));
    std::string low = to_lower_ascii(html);

    auto starts_tag = [&](size_t pos, const char* tag) -> bool {
        size_t tl = strlen(tag);
        if (pos + tl >= low.size()) return false;
        if (low.compare(pos, tl, tag) != 0) return false;
        char after = low[pos + tl];
        return after == '>' || after == ' ' || after == '\t' || after == '\n' || after == '/';
    };

    size_t i = 0;
    bool prev_space = true;
    while (i < html.size() && out.size() < max_chars + 512) {
        if (html.compare(i, 4, "<!--") == 0) {
            size_t end = html.find("-->", i + 4);
            i = (end == std::string::npos) ? html.size() : (end + 3);
            continue;
        }
        if (html[i] == '<') {
            if (starts_tag(i + 1, "script") || starts_tag(i + 1, "style") ||
                starts_tag(i + 1, "noscript") || starts_tag(i + 1, "svg")) {
                const char* end_tag = starts_tag(i + 1, "script") ? "</script>" :
                                      starts_tag(i + 1, "style") ? "</style>" :
                                      starts_tag(i + 1, "noscript") ? "</noscript>" : "</svg>";
                size_t end = low.find(end_tag, i + 1);
                i = (end == std::string::npos) ? html.size() : (end + strlen(end_tag));
                continue;
            }
            bool newline_tag =
                starts_tag(i + 1, "br") || starts_tag(i + 1, "p") || starts_tag(i + 1, "/p") ||
                starts_tag(i + 1, "div") || starts_tag(i + 1, "/div") || starts_tag(i + 1, "li") ||
                starts_tag(i + 1, "tr") || starts_tag(i + 1, "h1") || starts_tag(i + 1, "h2") ||
                starts_tag(i + 1, "h3") || starts_tag(i + 1, "h4");
            size_t close_gt = html.find('>', i + 1);
            i = (close_gt == std::string::npos) ? html.size() : (close_gt + 1);
            if (newline_tag && !out.empty() && out.back() != '\n') {
                out.push_back('\n');
                prev_space = true;
            } else if (!prev_space) {
                out.push_back(' ');
                prev_space = true;
            }
            continue;
        }
        char c = html[i++];
        if (c == '\r' || c == '\n' || c == '\t' || c == ' ') {
            if (!prev_space) {
                out.push_back(' ');
                prev_space = true;
            }
        } else {
            out.push_back(c);
            prev_space = false;
        }
    }

    std::string decoded = html_decode_entities(out);
    // Clean excess blank lines
    std::string clean;
    clean.reserve(decoded.size());
    int nl_run = 0;
    for (char c : decoded) {
        if (c == '\n') {
            if (++nl_run <= 2) clean.push_back(c);
        } else {
            if (c != ' ') nl_run = 0;
            clean.push_back(c);
        }
    }
    return ensure_utf8_and_truncate(trim_ws(clean), max_chars);
}

} // namespace openstation
