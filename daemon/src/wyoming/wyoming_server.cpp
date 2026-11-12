#include "wyoming/wyoming_server.hpp"
#include "utils/logger.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

namespace openstation {

WyomingServer::~WyomingServer() {
    stop();
}

bool WyomingServer::start(const WyomingConfig& cfg, TtsAudioCallback tts_cb, StateCallback state_cb) {
    if (running_.load()) stop();
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        cfg_ = cfg;
    }
    tts_cb_ = std::move(tts_cb);
    state_cb_ = std::move(state_cb);

    if (!cfg.enabled) {
        LOGI("Wyoming", "Wyoming server disabled in config");
        return true;
    }

    listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd_ < 0) {
        LOGE("Wyoming", "socket() failed: %s", strerror(errno));
        return false;
    }
    int opt = 1;
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(static_cast<uint16_t>(cfg.port));

    if (bind(listen_fd_, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("Wyoming", "bind(:%d) failed: %s", cfg.port, strerror(errno));
        ::close(listen_fd_);
        listen_fd_ = -1;
        return false;
    }
    if (listen(listen_fd_, 4) < 0) {
        LOGE("Wyoming", "listen() failed: %s", strerror(errno));
        ::close(listen_fd_);
        listen_fd_ = -1;
        return false;
    }

    running_.store(true);
    accept_thread_ = std::thread(&WyomingServer::accept_loop, this);
    LOGI("Wyoming", "Listening for Home Assistant on port %d", cfg.port);
    return true;
}

void WyomingServer::stop() {
    running_.store(false);
    if (listen_fd_ >= 0) {
        shutdown(listen_fd_, SHUT_RDWR);
        ::close(listen_fd_);
        listen_fd_ = -1;
    }
    {
        std::lock_guard<std::mutex> lk(client_mu_);
        // Only shut down: the accept thread still reads this fd and closes it on exit.
        if (client_fd_ >= 0) shutdown(client_fd_, SHUT_RDWR);
    }
    if (accept_thread_.joinable()) accept_thread_.join();
}

void WyomingServer::update_config(const WyomingConfig& cfg) {
    std::lock_guard<std::mutex> lk(cfg_mu_);
    cfg_ = cfg;
}

void WyomingServer::trigger_button_session(int duration_ms) {
    auto now = std::chrono::steady_clock::now();
    {
        std::lock_guard<std::mutex> lk(client_mu_);
        stream_until_ = now + std::chrono::milliseconds(duration_ms);
        last_voice_tp_ = now;
        if (client_fd_ >= 0 && !streaming_active_.load()) {
            json::Value pipe = json::Value::make_obj();
            pipe["start_stage"] = json::Value::make_str("asr");
            pipe["end_stage"] = json::Value::make_str("tts");
            send_event(client_fd_, "run-pipeline", pipe);
            json::Value astart = json::Value::make_obj();
            astart["rate"] = json::Value::make_int(16000);
            astart["width"] = json::Value::make_int(2);
            astart["channels"] = json::Value::make_int(1);
            send_event(client_fd_, "audio-start", astart);
            audio_start_sent_ = true;
            streaming_active_.store(true);
        }
    }
    if (state_cb_) state_cb_("streaming");
}

void WyomingServer::stop_session() {
    {
        std::lock_guard<std::mutex> lk(client_mu_);
        if (client_fd_ >= 0 && audio_start_sent_) {
            send_event(client_fd_, "audio-stop", json::Value::make_obj());
        }
        audio_start_sent_ = false;
        streaming_active_.store(false);
        remote_run_pipeline_.store(false);
    }
    if (state_cb_) state_cb_("idle");
}

void WyomingServer::feed_audio_16k(const int16_t* samples, size_t count, float rms) {
    if (!client_connected_.load() || !samples || count == 0) return;

    WyomingConfig c;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
    }
    if (!c.enabled) return;

    std::string notify_state;
    {
        std::lock_guard<std::mutex> lk(client_mu_);
        if (client_fd_ < 0) return;

        auto now = std::chrono::steady_clock::now();
        bool want_stream = false;

        if (c.always_stream || remote_run_pipeline_.load()) {
            want_stream = true;
        } else if (now < stream_until_) {
            want_stream = true;
            if (rms >= static_cast<float>(c.vad_threshold)) {
                last_voice_tp_ = now;
            }
        } else if (c.vad_threshold > 0 && rms >= static_cast<float>(c.vad_threshold)) {
            want_stream = true;
            last_voice_tp_ = now;
            stream_until_ = now + std::chrono::milliseconds(2200);
        }

        if (want_stream) {
            if (!audio_start_sent_) {
                json::Value pipe = json::Value::make_obj();
                if (c.always_stream || remote_run_pipeline_.load()) {
                    pipe["start_stage"] = json::Value::make_str("wake");
                } else {
                    pipe["start_stage"] = json::Value::make_str("asr");
                }
                pipe["end_stage"] = json::Value::make_str("tts");
                send_event(client_fd_, "run-pipeline", pipe);
                json::Value astart = json::Value::make_obj();
                astart["rate"] = json::Value::make_int(16000);
                astart["width"] = json::Value::make_int(2);
                astart["channels"] = json::Value::make_int(1);
                send_event(client_fd_, "audio-start", astart);
                audio_start_sent_ = true;
                streaming_active_.store(true);
                notify_state = "streaming";
            }
            json::Value chunk = json::Value::make_obj();
            chunk["rate"] = json::Value::make_int(16000);
            chunk["width"] = json::Value::make_int(2);
            chunk["channels"] = json::Value::make_int(1);
            send_event(client_fd_, "audio-chunk", chunk,
                       reinterpret_cast<const uint8_t*>(samples), count * sizeof(int16_t));
        } else if (audio_start_sent_) {
            send_event(client_fd_, "audio-stop", json::Value::make_obj());
            audio_start_sent_ = false;
            streaming_active_.store(false);
            notify_state = "thinking";
        }
    }
    if (!notify_state.empty() && state_cb_) state_cb_(notify_state);
}

bool WyomingServer::send_raw(int fd, const void* buf, size_t len) {
    const uint8_t* p = static_cast<const uint8_t*>(buf);
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(fd, p + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) {
            // A partial event leaves the stream unframed; drop the connection so the reader exits.
            shutdown(fd, SHUT_RDWR);
            return false;
        }
        sent += static_cast<size_t>(n);
    }
    return true;
}

bool WyomingServer::send_event(int fd, const std::string& type, const json::Value& data,
                               const uint8_t* payload, size_t payload_len) {
    std::string data_str = data.is_obj() ? data.dump(-1) : "{}";
    json::Value hdr = json::Value::make_obj();
    hdr["type"] = json::Value::make_str(type);
    if (data_str != "{}") {
        hdr["data_length"] = json::Value::make_int(static_cast<int64_t>(data_str.size()));
    }
    if (payload && payload_len > 0) {
        hdr["payload_length"] = json::Value::make_int(static_cast<int64_t>(payload_len));
    }
    std::string line = hdr.dump(-1) + "\n";
    if (!send_raw(fd, line.data(), line.size())) return false;
    if (data_str != "{}" && !send_raw(fd, data_str.data(), data_str.size())) return false;
    if (payload && payload_len > 0 && !send_raw(fd, payload, payload_len)) return false;
    return true;
}

void WyomingServer::accept_loop() {
    while (running_.load()) {
        struct sockaddr_in caddr {};
        socklen_t clen = sizeof(caddr);
        int cfd = accept(listen_fd_, reinterpret_cast<struct sockaddr*>(&caddr), &clen);
        if (cfd < 0) {
            if (!running_.load()) break;
            usleep(100000);
            continue;
        }
        int nodelay = 1;
        setsockopt(cfd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));
        // Sends happen on the mic capture thread; a stalled peer must not block it forever.
        struct timeval stv {2, 0};
        setsockopt(cfd, SOL_SOCKET, SO_SNDTIMEO, &stv, sizeof(stv));

        char ip[INET_ADDRSTRLEN] = {0};
        inet_ntop(AF_INET, &caddr.sin_addr, ip, sizeof(ip));
        LOGI("Wyoming", "Home Assistant connected from %s", ip);

        {
            std::lock_guard<std::mutex> lk(client_mu_);
            if (client_fd_ >= 0) ::close(client_fd_);
            client_fd_ = cfd;
            peer_addr_ = ip;
            audio_start_sent_ = false;
        }
        client_connected_.store(true);
        handle_client(cfd);
        client_connected_.store(false);
        streaming_active_.store(false);
        remote_run_pipeline_.store(false);
        {
            std::lock_guard<std::mutex> lk(client_mu_);
            if (client_fd_ == cfd) {
                ::close(cfd);
                client_fd_ = -1;
            }
            peer_addr_.clear();
        }
        if (state_cb_) state_cb_("idle");
        LOGI("Wyoming", "Client disconnected (%s)", ip);
    }
}

void WyomingServer::handle_client(int cfd) {
    auto read_exact = [&](uint8_t* dst, size_t n) -> bool {
        size_t got = 0;
        while (got < n && running_.load()) {
            ssize_t r = recv(cfd, dst + got, n - got, 0);
            if (r <= 0) return false;
            got += static_cast<size_t>(r);
        }
        return got == n;
    };

    auto read_line = [&](std::string& out) -> bool {
        out.clear();
        char c = 0;
        while (running_.load() && out.size() < 16384) {
            ssize_t r = recv(cfd, &c, 1, 0);
            if (r <= 0) return false;
            if (c == '\n') return true;
            if (c != '\r') out.push_back(c);
        }
        return false;
    };

    std::string line;
    while (running_.load() && read_line(line)) {
        if (line.empty()) continue;
        json::Value hdr;
        if (!json::Parser::parse(line, hdr) || !hdr.is_obj()) continue;

        std::string type;
        if (auto* t = hdr.get("type")) type = t->as_str();
        size_t data_len = hdr.get("data_length") ? static_cast<size_t>(std::max<int64_t>(0, hdr.get("data_length")->as_i64())) : 0;
        size_t payload_len = hdr.get("payload_length") ? static_cast<size_t>(std::max<int64_t>(0, hdr.get("payload_length")->as_i64())) : 0;

        json::Value data_obj = json::Value::make_obj();
        if (hdr.get("data") && hdr.get("data")->is_obj()) {
            data_obj = *hdr.get("data");
        }
        // Oversized sections can't be skipped safely; drop the client rather than desync the stream.
        if (data_len >= 1048576 || payload_len >= 4194304) break;
        if (data_len > 0) {
            std::string dstr(data_len, '\0');
            if (!read_exact(reinterpret_cast<uint8_t*>(&dstr[0]), data_len)) break;
            json::Value parsed;
            if (json::Parser::parse(dstr, parsed) && parsed.is_obj()) {
                data_obj = std::move(parsed);
            }
        }
        std::vector<uint8_t> payload;
        if (payload_len > 0) {
            payload.resize(payload_len);
            if (!read_exact(payload.data(), payload_len)) break;
        }

        if (type == "describe") {
            WyomingConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu_);
                c = cfg_;
            }
            json::Value info = json::Value::make_obj();
            json::Value sat = json::Value::make_obj();
            sat["name"] = json::Value::make_str(c.device_name);
            sat["description"] = json::Value::make_str("OpenStation Max Voice Satellite");
            json::Value attr = json::Value::make_obj();
            attr["name"] = json::Value::make_str("OpenStation");
            attr["url"] = json::Value::make_str("https://github.com/openstation");
            sat["attribution"] = std::move(attr);
            sat["installed"] = json::Value::make_bool(true);
            sat["version"] = json::Value::make_str("1.0.0");
            sat["area"] = json::Value::make_str("Living Room");
            sat["has_vad"] = json::Value::make_bool(!c.always_stream);
            sat["supports_trigger"] = json::Value::make_bool(true);
            info["satellite"] = std::move(sat);
            std::lock_guard<std::mutex> lk(client_mu_);
            send_event(cfd, "info", info);
        } else if (type == "run-satellite") {
            LOGI("Wyoming", "HA requested run-satellite");
            remote_run_pipeline_.store(true);
            if (state_cb_) state_cb_("streaming");
        } else if (type == "pause-satellite") {
            LOGI("Wyoming", "HA requested pause-satellite");
            remote_run_pipeline_.store(false);
            stop_session();
        } else if (type == "detection") {
            LOGI("Wyoming", "Wake word detected by Home Assistant!");
            remote_run_pipeline_.store(false);
            {
                std::lock_guard<std::mutex> lk(client_mu_);
                stream_until_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(5500);
            }
            if (state_cb_) state_cb_("streaming");
        } else if (type == "transcript") {
            if (auto* t = data_obj.get("text")) {
                std::string txt = t->as_str();
                {
                    std::lock_guard<std::mutex> lk(client_mu_);
                    last_stt_text_ = txt;
                }
                LOGI("Wyoming", "Transcript: %s", txt.c_str());
            }
        } else if (type == "audio-start") {
            tts_rate_ = data_obj.get("rate") ? data_obj.get("rate")->as_int(22050) : 22050;
            tts_channels_ = data_obj.get("channels") ? data_obj.get("channels")->as_int(1) : 1;
            LOGI("Wyoming", "TTS audio-start: %d Hz, %d ch", tts_rate_, tts_channels_);
            if (state_cb_) state_cb_("tts");
        } else if (type == "audio-chunk") {
            int rate = data_obj.get("rate") ? data_obj.get("rate")->as_int(tts_rate_) : tts_rate_;
            int ch = data_obj.get("channels") ? data_obj.get("channels")->as_int(tts_channels_) : tts_channels_;
            // Reject absurd network-supplied formats: tiny rates blow up the 48 kHz resample buffer,
            // huge channel counts overflow the frame-size math.
            if (rate < 8000 || rate > 96000 || ch < 1 || ch > 8) continue;
            if (!payload.empty() && tts_cb_) {
                size_t frames = payload.size() / (2 * std::max(1, ch));
                tts_cb_(reinterpret_cast<const int16_t*>(payload.data()), frames, rate, std::max(1, ch));
            }
        } else if (type == "audio-stop") {
            LOGI("Wyoming", "TTS audio-stop");
            if (state_cb_) state_cb_("idle");
        } else if (type == "ping") {
            std::lock_guard<std::mutex> lk(client_mu_);
            send_event(cfd, "pong", json::Value::make_obj());
        }
    }
}

json::Value WyomingServer::get_status_json() const {
    using json::Value;
    Value obj = Value::make_obj();
    WyomingConfig c;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
    }
    std::string peer;
    std::string stt;
    {
        std::lock_guard<std::mutex> lk(client_mu_);
        peer = peer_addr_;
        stt = last_stt_text_;
    }
    obj["enabled"] = Value::make_bool(c.enabled);
    obj["port"] = Value::make_int(c.port);
    obj["clientConnected"] = Value::make_bool(client_connected_.load());
    obj["streaming"] = Value::make_bool(streaming_active_.load());
    obj["peerIp"] = Value::make_str(peer);
    obj["lastTranscript"] = Value::make_str(stt);
    return obj;
}

} // namespace openstation
