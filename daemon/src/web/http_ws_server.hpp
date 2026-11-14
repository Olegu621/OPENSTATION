#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

struct HttpRequest {
    std::string method;
    std::string path;
    std::string query;
    std::string body;
    std::string host;
    std::string origin;
    std::string content_type;
};

struct HttpResponse {
    int status = 200;
    std::string content_type = "application/json; charset=utf-8";
    std::vector<uint8_t> body;

    static HttpResponse json(const std::string& s, int code = 200) {
        HttpResponse r;
        r.status = code;
        r.content_type = "application/json; charset=utf-8";
        r.body.assign(s.begin(), s.end());
        return r;
    }
    static HttpResponse bytes(std::vector<uint8_t> data, const std::string& mime, int code = 200) {
        HttpResponse r;
        r.status = code;
        r.content_type = mime;
        r.body = std::move(data);
        return r;
    }
};

class HttpWsServer {
public:
    using ApiHandler = std::function<HttpResponse(const HttpRequest&)>;
    using WsCmdHandler = std::function<void(const std::string& msg)>;

    HttpWsServer() = default;
    ~HttpWsServer();

    bool start(int port, ApiHandler api_cb, WsCmdHandler ws_cb);
    void stop();

    void broadcast_ws_text(const std::string& text);
    void broadcast_ws_audio(const int16_t* pcm, size_t samples);
    size_t ws_client_count() const;

private:
    struct WsClient {
        int fd = -1;
        bool want_audio = false;
        std::atomic<bool> dead{false};
        std::mutex send_mu;
    };

    struct ConnSlot {
        int fd = -1;
        std::atomic<bool> finished{false};
        std::thread thr;
    };

    void accept_loop();
    void handle_connection(std::shared_ptr<ConnSlot> slot);
    void remove_ws_client(int cfd);
    bool send_ws_frame(int fd, uint8_t opcode, const uint8_t* data, size_t len);
    void broadcast_ws_frame(uint8_t opcode, const uint8_t* data, size_t len, bool audio_only);

    int port_ = 8080;
    int listen_fd_ = -1;
    ApiHandler api_cb_;
    WsCmdHandler ws_cb_;

    std::atomic<bool> running_{false};
    std::thread accept_thread_;

    std::mutex conn_mu_;
    std::vector<std::shared_ptr<ConnSlot>> conns_;

    std::mutex ws_mu_;
    std::vector<std::shared_ptr<WsClient>> ws_clients_;
    std::atomic<size_t> ws_client_count_{0};
};

} // namespace openstation
