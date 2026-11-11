#include "web/http_ws_server.hpp"
#include "utils/logger.hpp"
#include "web_assets.hpp"

#include <arpa/inet.h>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

namespace openstation {

namespace {

// Compact RFC 3174 SHA-1 for WebSocket Sec-WebSocket-Accept handshake
void sha1_digest(const std::string& input, uint8_t out[20]) {
    uint32_t h0 = 0x67452301;
    uint32_t h1 = 0xEFCDAB89;
    uint32_t h2 = 0x98BADCFE;
    uint32_t h3 = 0x10325476;
    uint32_t h4 = 0xC3D2E1F0;

    std::vector<uint8_t> msg(input.begin(), input.end());
    uint64_t bit_len = static_cast<uint64_t>(msg.size()) * 8ULL;
    msg.push_back(0x80);
    while ((msg.size() % 64) != 56) msg.push_back(0x00);
    for (int i = 7; i >= 0; --i) {
        msg.push_back(static_cast<uint8_t>((bit_len >> (i * 8)) & 0xFF));
    }

    auto rotl = [](uint32_t x, int n) -> uint32_t {
        return (x << n) | (x >> (32 - n));
    };

    for (size_t off = 0; off < msg.size(); off += 64) {
        uint32_t w[80];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(msg[off + i * 4]) << 24) |
                   (static_cast<uint32_t>(msg[off + i * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(msg[off + i * 4 + 2]) << 8) |
                   static_cast<uint32_t>(msg[off + i * 4 + 3]);
        }
        for (int i = 16; i < 80; ++i) {
            w[i] = rotl(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        }
        uint32_t a = h0, b = h1, c = h2, d = h3, e = h4;
        for (int i = 0; i < 80; ++i) {
            uint32_t f = 0, k = 0;
            if (i < 20) { f = (b & c) | ((~b) & d); k = 0x5A827999; }
            else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
            else { f = b ^ c ^ d; k = 0xCA62C1D6; }
            uint32_t temp = rotl(a, 5) + f + e + k + w[i];
            e = d;
            d = c;
            c = rotl(b, 30);
            b = a;
            a = temp;
        }
        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e;
    }

    uint32_t hs[5] = {h0, h1, h2, h3, h4};
    for (int i = 0; i < 5; ++i) {
        out[i * 4 + 0] = static_cast<uint8_t>((hs[i] >> 24) & 0xFF);
        out[i * 4 + 1] = static_cast<uint8_t>((hs[i] >> 16) & 0xFF);
        out[i * 4 + 2] = static_cast<uint8_t>((hs[i] >> 8) & 0xFF);
        out[i * 4 + 3] = static_cast<uint8_t>(hs[i] & 0xFF);
    }
}

std::string base64_encode(const uint8_t* data, size_t len) {
    static const char* kTbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    for (size_t i = 0; i < len; i += 3) {
        uint32_t v = static_cast<uint32_t>(data[i]) << 16;
        if (i + 1 < len) v |= static_cast<uint32_t>(data[i + 1]) << 8;
        if (i + 2 < len) v |= static_cast<uint32_t>(data[i + 2]);
        out.push_back(kTbl[(v >> 18) & 0x3F]);
        out.push_back(kTbl[(v >> 12) & 0x3F]);
        out.push_back(i + 1 < len ? kTbl[(v >> 6) & 0x3F] : '=');
        out.push_back(i + 2 < len ? kTbl[v & 0x3F] : '=');
    }
    return out;
}

bool send_all(int fd, const void* buf, size_t len) {
    const uint8_t* p = static_cast<const uint8_t*>(buf);
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(fd, p + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

const char* status_text(int code) {
    switch (code) {
        case 200: return "OK";
        case 204: return "No Content";
        case 400: return "Bad Request";
        case 404: return "Not Found";
        case 500: return "Internal Server Error";
        default: return "OK";
    }
}

} // namespace

HttpWsServer::~HttpWsServer() {
    stop();
}

bool HttpWsServer::start(int port, ApiHandler api_cb, WsCmdHandler ws_cb) {
    if (running_.load()) stop();
    port_ = port;
    api_cb_ = std::move(api_cb);
    ws_cb_ = std::move(ws_cb);

    listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd_ < 0) {
        LOGE("HttpServer", "socket() failed: %s", strerror(errno));
        return false;
    }
    int opt = 1;
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(static_cast<uint16_t>(port_));
    if (bind(listen_fd_, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("HttpServer", "bind(:%d) failed: %s", port_, strerror(errno));
        ::close(listen_fd_);
        listen_fd_ = -1;
        return false;
    }
    if (listen(listen_fd_, 16) < 0) {
        LOGE("HttpServer", "listen() failed: %s", strerror(errno));
        ::close(listen_fd_);
        listen_fd_ = -1;
        return false;
    }

    running_.store(true);
    accept_thread_ = std::thread(&HttpWsServer::accept_loop, this);
    LOGI("HttpServer", "Listening on http://0.0.0.0:%d", port_);
    return true;
}

void HttpWsServer::stop() {
    running_.store(false);
    if (listen_fd_ >= 0) {
        shutdown(listen_fd_, SHUT_RDWR);
        ::close(listen_fd_);
        listen_fd_ = -1;
    }
    if (accept_thread_.joinable()) accept_thread_.join();
    {
        std::lock_guard<std::mutex> lk(ws_mu_);
        for (const auto& c : ws_clients_) {
            c->dead = true;
            if (c->fd >= 0) shutdown(c->fd, SHUT_RDWR);
        }
        ws_clients_.clear();
    }
    std::vector<std::shared_ptr<ConnSlot>> all_conns;
    {
        std::lock_guard<std::mutex> lk(conn_mu_);
        for (const auto& s : conns_) {
            if (s->fd >= 0) shutdown(s->fd, SHUT_RDWR);
        }
        all_conns.swap(conns_);
    }
    for (auto& s : all_conns) {
        if (s->thr.joinable()) s->thr.join();
    }
}

void HttpWsServer::accept_loop() {
    while (running_.load()) {
        struct sockaddr_in ca {};
        socklen_t cl = sizeof(ca);
        int cfd = accept(listen_fd_, reinterpret_cast<struct sockaddr*>(&ca), &cl);
        if (cfd < 0) {
            if (!running_.load()) break;
            usleep(50000);
            continue;
        }
        int nodelay = 1;
        setsockopt(cfd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

        std::vector<std::shared_ptr<ConnSlot>> reaped;
        {
            std::lock_guard<std::mutex> lk(conn_mu_);
            for (auto it = conns_.begin(); it != conns_.end();) {
                if ((*it)->finished.load()) {
                    reaped.push_back(std::move(*it));
                    it = conns_.erase(it);
                } else {
                    ++it;
                }
            }
            if (!running_.load()) {
                ::close(cfd);
            } else {
                auto slot = std::make_shared<ConnSlot>();
                slot->fd = cfd;
                slot->thr = std::thread(&HttpWsServer::handle_connection, this, slot);
                conns_.push_back(std::move(slot));
            }
        }
        for (auto& r : reaped) {
            if (r->thr.joinable()) r->thr.join();
        }
    }
}

void HttpWsServer::remove_ws_client(int cfd) {
    std::lock_guard<std::mutex> lk(ws_mu_);
    for (auto it = ws_clients_.begin(); it != ws_clients_.end(); ++it) {
        if ((*it)->fd == cfd) {
            (*it)->dead = true;
            ws_clients_.erase(it);
            break;
        }
    }
}

bool HttpWsServer::send_ws_frame(int fd, uint8_t opcode, const uint8_t* data, size_t len) {
    uint8_t hdr[10];
    size_t hlen = 0;
    hdr[0] = static_cast<uint8_t>(0x80 | (opcode & 0x0F));
    if (len <= 125) {
        hdr[1] = static_cast<uint8_t>(len);
        hlen = 2;
    } else if (len <= 65535) {
        hdr[1] = 126;
        hdr[2] = static_cast<uint8_t>((len >> 8) & 0xFF);
        hdr[3] = static_cast<uint8_t>(len & 0xFF);
        hlen = 4;
    } else {
        hdr[1] = 127;
        for (int i = 0; i < 8; ++i) {
            hdr[2 + i] = static_cast<uint8_t>((static_cast<uint64_t>(len) >> ((7 - i) * 8)) & 0xFF);
        }
        hlen = 10;
    }
    if (!send_all(fd, hdr, hlen)) return false;
    if (len > 0 && !send_all(fd, data, len)) return false;
    return true;
}

void HttpWsServer::broadcast_ws_frame(uint8_t opcode, const uint8_t* data, size_t len, bool audio_only) {
    std::vector<std::shared_ptr<WsClient>> snap;
    {
        std::lock_guard<std::mutex> lk(ws_mu_);
        snap.reserve(ws_clients_.size());
        for (const auto& c : ws_clients_) {
            if (c->dead) continue;
            if (audio_only && !c->want_audio) continue;
            snap.push_back(c);
        }
    }
    if (snap.empty()) return;

    std::vector<int> failed_fds;
    for (const auto& c : snap) {
        std::lock_guard<std::mutex> slk(c->send_mu);
        if (c->dead) continue;
        if (!send_ws_frame(c->fd, opcode, data, len)) {
            c->dead = true;
            shutdown(c->fd, SHUT_RDWR);
            failed_fds.push_back(c->fd);
        }
    }
    if (!failed_fds.empty()) {
        std::lock_guard<std::mutex> lk(ws_mu_);
        for (auto it = ws_clients_.begin(); it != ws_clients_.end();) {
            bool rem = false;
            for (int ffd : failed_fds) {
                if ((*it)->fd == ffd) {
                    rem = true;
                    break;
                }
            }
            if (rem) it = ws_clients_.erase(it);
            else ++it;
        }
    }
}

void HttpWsServer::broadcast_ws_text(const std::string& text) {
    broadcast_ws_frame(0x01, reinterpret_cast<const uint8_t*>(text.data()), text.size(), false);
}

void HttpWsServer::broadcast_ws_audio(const int16_t* pcm, size_t samples) {
    if (!pcm || samples == 0) return;
    broadcast_ws_frame(0x02, reinterpret_cast<const uint8_t*>(pcm), samples * sizeof(int16_t), true);
}

size_t HttpWsServer::ws_client_count() {
    std::lock_guard<std::mutex> lk(ws_mu_);
    return ws_clients_.size();
}

void HttpWsServer::handle_connection(std::shared_ptr<ConnSlot> slot) {
    int cfd = slot->fd;
    struct ConnGuard {
        HttpWsServer* srv;
        std::shared_ptr<ConnSlot> slot;
        int cfd;
        ~ConnGuard() {
            {
                std::lock_guard<std::mutex> lk(srv->conn_mu_);
                slot->fd = -1;
            }
            ::close(cfd);
            slot->finished.store(true);
        }
    } guard{this, slot, cfd};

    struct timeval tv {10, 0};
    setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    struct timeval stv {2, 0};
    setsockopt(cfd, SOL_SOCKET, SO_SNDTIMEO, &stv, sizeof(stv));

    std::string raw;
    raw.reserve(2048);
    char tmp[2048];
    size_t hdr_end = std::string::npos;
    while (running_.load() && raw.size() < 32768) {
        ssize_t n = recv(cfd, tmp, sizeof(tmp), 0);
        if (n <= 0) return;
        raw.append(tmp, static_cast<size_t>(n));
        hdr_end = raw.find("\r\n\r\n");
        if (hdr_end != std::string::npos) break;
    }
    if (hdr_end == std::string::npos) return;

    std::string hdr_block = raw.substr(0, hdr_end);
    std::string leftover = raw.substr(hdr_end + 4);

    // Parse request line
    size_t line_end = hdr_block.find("\r\n");
    std::string req_line = line_end == std::string::npos ? hdr_block : hdr_block.substr(0, line_end);
    size_t sp1 = req_line.find(' ');
    size_t sp2 = sp1 != std::string::npos ? req_line.find(' ', sp1 + 1) : std::string::npos;
    if (sp1 == std::string::npos || sp2 == std::string::npos) return;

    HttpRequest req;
    req.method = req_line.substr(0, sp1);
    std::string full_uri = req_line.substr(sp1 + 1, sp2 - sp1 - 1);
    size_t qm = full_uri.find('?');
    if (qm == std::string::npos) {
        req.path = full_uri;
    } else {
        req.path = full_uri.substr(0, qm);
        req.query = full_uri.substr(qm + 1);
    }

    // Parse headers
    size_t content_length = 0;
    bool is_ws = false;
    std::string ws_key;
    size_t pos = line_end == std::string::npos ? hdr_block.size() : line_end + 2;
    while (pos < hdr_block.size()) {
        size_t next = hdr_block.find("\r\n", pos);
        if (next == std::string::npos) next = hdr_block.size();
        std::string hline = hdr_block.substr(pos, next - pos);
        size_t colon = hline.find(':');
        if (colon != std::string::npos) {
            std::string k = hline.substr(0, colon);
            std::string v = hline.substr(colon + 1);
            for (char& c : k) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            while (!v.empty() && (v.front() == ' ' || v.front() == '\t')) v.erase(v.begin());
            if (k == "content-length") {
                content_length = static_cast<size_t>(std::strtoull(v.c_str(), nullptr, 10));
            } else if (k == "upgrade") {
                for (char& c : v) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                if (v.find("websocket") != std::string::npos) is_ws = true;
            } else if (k == "sec-websocket-key") {
                ws_key = v;
            }
        }
        pos = next + 2;
    }

    // WebSocket upgrade path
    if (is_ws && req.path == "/ws" && !ws_key.empty()) {
        static const char* kGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        uint8_t digest[20];
        sha1_digest(ws_key + kGuid, digest);
        std::string accept_key = base64_encode(digest, 20);
        std::string resp =
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Accept: " + accept_key + "\r\n\r\n";
        if (!send_all(cfd, resp.data(), resp.size())) return;
        // Clear strict timeout for long-lived WebSocket
        struct timeval notv {0, 0};
        setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &notv, sizeof(notv));

        auto ws_client = std::make_shared<WsClient>();
        ws_client->fd = cfd;
        {
            std::lock_guard<std::mutex> lk(ws_mu_);
            ws_clients_.push_back(ws_client);
        }

        // Read client WS frames
        auto recv_exact = [&](uint8_t* dst, size_t n) -> bool {
            size_t got = 0;
            while (got < n && running_.load()) {
                ssize_t r = recv(cfd, dst + got, n - got, 0);
                if (r <= 0) return false;
                got += static_cast<size_t>(r);
            }
            return got == n;
        };

        while (running_.load()) {
            uint8_t b[2];
            if (!recv_exact(b, 2)) break;
            uint8_t opcode = b[0] & 0x0F;
            bool masked = (b[1] & 0x80) != 0;
            uint64_t plen = b[1] & 0x7F;
            if (plen == 126) {
                uint8_t ext[2];
                if (!recv_exact(ext, 2)) break;
                plen = (static_cast<uint64_t>(ext[0]) << 8) | ext[1];
            } else if (plen == 127) {
                uint8_t ext[8];
                if (!recv_exact(ext, 8)) break;
                plen = 0;
                for (int i = 0; i < 8; ++i) plen = (plen << 8) | ext[i];
            }
            if (plen > 262144) break;

            uint8_t mask[4] = {0};
            if (masked && !recv_exact(mask, 4)) break;
            std::string pl(static_cast<size_t>(plen), '\0');
            if (plen > 0 && !recv_exact(reinterpret_cast<uint8_t*>(&pl[0]), static_cast<size_t>(plen))) break;
            if (masked) {
                for (size_t i = 0; i < pl.size(); ++i) pl[i] ^= static_cast<char>(mask[i % 4]);
            }

            if (opcode == 0x08) { // close
                break;
            } else if (opcode == 0x09) { // ping -> pong
                std::lock_guard<std::mutex> slk(ws_client->send_mu);
                if (!ws_client->dead) {
                    if (!send_ws_frame(cfd, 0x0A, reinterpret_cast<const uint8_t*>(pl.data()), pl.size())) {
                        ws_client->dead = true;
                        break;
                    }
                }
            } else if (opcode == 0x01) {
                if (pl.find("\"mic_audio\"") != std::string::npos) {
                    bool en = pl.find("true") != std::string::npos;
                    std::lock_guard<std::mutex> lk(ws_mu_);
                    ws_client->want_audio = en;
                } else if (ws_cb_) {
                    ws_cb_(pl);
                }
            }
        }
        remove_ws_client(cfd);
        return;
    }

    // Read remaining HTTP POST body
    if (content_length > 2097152) return;
    req.body = std::move(leftover);
    while (req.body.size() < content_length && running_.load()) {
        ssize_t n = recv(cfd, tmp, std::min(sizeof(tmp), content_length - req.body.size()), 0);
        if (n <= 0) break;
        req.body.append(tmp, static_cast<size_t>(n));
    }
    if (req.body.size() > content_length) req.body.resize(content_length);

    HttpResponse resp;
    if (req.method == "OPTIONS") {
        resp.status = 204;
    } else if (req.path.rfind("/api/", 0) == 0 && api_cb_) {
        resp = api_cb_(req);
    } else {
        const auto* asset = web_assets::find(req.path);
        if (asset) {
            resp.status = 200;
            resp.content_type = asset->mime;
            resp.body.assign(asset->data, asset->data + asset->size);
        } else {
            resp = HttpResponse::json("{\"error\":\"not found\"}", 404);
        }
    }

    char hbuf[512];
    int hlen = snprintf(hbuf, sizeof(hbuf),
                        "HTTP/1.1 %d %s\r\n"
                        "Content-Type: %s\r\n"
                        "Content-Length: %zu\r\n"
                        "Access-Control-Allow-Origin: *\r\n"
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                        "Access-Control-Allow-Headers: Content-Type\r\n"
                        "Cache-Control: no-store\r\n"
                        "Connection: close\r\n\r\n",
                        resp.status, status_text(resp.status),
                        resp.content_type.c_str(), resp.body.size());
    send_all(cfd, hbuf, static_cast<size_t>(hlen));
    if (!resp.body.empty()) {
        send_all(cfd, resp.body.data(), resp.body.size());
    }
}

} // namespace openstation
