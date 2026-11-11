#include "net/mqtt_client.hpp"
#include "utils/logger.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

namespace openstation {

namespace {

void encode_varint(std::vector<uint8_t>& out, size_t len) {
    do {
        uint8_t byte = static_cast<uint8_t>(len % 128);
        len /= 128;
        if (len > 0) byte |= 0x80;
        out.push_back(byte);
    } while (len > 0);
}

void append_mqtt_str(std::vector<uint8_t>& out, const std::string& s) {
    uint16_t l = static_cast<uint16_t>(std::min<size_t>(s.size(), 65535));
    out.push_back(static_cast<uint8_t>(l >> 8));
    out.push_back(static_cast<uint8_t>(l & 0xFF));
    out.insert(out.end(), s.begin(), s.begin() + l);
}

void parse_hex(const std::string& hex, int& r, int& g, int& b) {
    r = 0; g = 212; b = 255;
    if (hex.size() == 7 && hex[0] == '#') {
        unsigned rv = 0, gv = 0, bv = 0;
        if (sscanf(hex.c_str() + 1, "%02x%02x%02x", &rv, &gv, &bv) == 3) {
            r = static_cast<int>(rv);
            g = static_cast<int>(gv);
            b = static_cast<int>(bv);
        }
    }
}

} // namespace

MqttClient::~MqttClient() {
    stop();
}

void MqttClient::start(const MqttConfig& cfg, CommandCallback cmd_cb) {
    if (running_.load()) stop();
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        cfg_ = cfg;
    }
    cmd_cb_ = std::move(cmd_cb);
    running_.store(true);
    worker_ = std::thread(&MqttClient::worker_loop, this);
}

void MqttClient::stop() {
    running_.store(false);
    shutdown_socket();
    if (worker_.joinable()) worker_.join();
    disconnect_broker();
}

void MqttClient::update_config(const MqttConfig& cfg) {
    bool changed = false;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        changed = (cfg_.enabled != cfg.enabled ||
                   cfg_.host != cfg.host ||
                   cfg_.port != cfg.port ||
                   cfg_.username != cfg.username ||
                   cfg_.password != cfg.password ||
                   cfg_.base_topic != cfg.base_topic ||
                   cfg_.discovery != cfg.discovery);
        if (changed) {
            cfg_ = cfg;
        }
    }
    if (changed) {
        cfg_dirty_.store(true);
        shutdown_socket();
    }
}

void MqttClient::shutdown_socket() {
    std::lock_guard<std::mutex> lk(sock_mu_);
    if (sock_fd_ >= 0) {
        shutdown(sock_fd_, SHUT_RDWR);
    }
    connected_.store(false);
}

void MqttClient::disconnect_broker() {
    std::lock_guard<std::mutex> lk(sock_mu_);
    if (sock_fd_ >= 0) {
        shutdown(sock_fd_, SHUT_RDWR);
        ::close(sock_fd_);
        sock_fd_ = -1;
    }
    connected_.store(false);
}

bool MqttClient::send_packet(const uint8_t* data, size_t len) {
    std::lock_guard<std::mutex> lk(sock_mu_);
    if (sock_fd_ < 0) return false;
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(sock_fd_, data + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

bool MqttClient::connect_broker(const MqttConfig& c) {
    struct addrinfo hints {};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo* res = nullptr;
    char port_s[16];
    snprintf(port_s, sizeof(port_s), "%d", c.port);
    if (getaddrinfo(c.host.c_str(), port_s, &hints, &res) != 0 || !res) {
        return false;
    }

    int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd < 0) {
        freeaddrinfo(res);
        return false;
    }
    struct timeval tv {3, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    if (connect(fd, res->ai_addr, res->ai_addrlen) < 0) {
        ::close(fd);
        freeaddrinfo(res);
        return false;
    }
    freeaddrinfo(res);

    {
        std::lock_guard<std::mutex> lk(sock_mu_);
        if (sock_fd_ >= 0) ::close(sock_fd_);
        sock_fd_ = fd;
    }

    // Build MQTT CONNECT packet (v3.1.1)
    std::vector<uint8_t> vh;
    append_mqtt_str(vh, "MQTT");
    vh.push_back(4); // Level 4 = 3.1.1
    uint8_t flags = 0x02; // Clean session
    if (!c.username.empty()) flags |= 0x80;
    if (!c.password.empty()) flags |= 0x40;
    vh.push_back(flags);
    vh.push_back(0);
    vh.push_back(30); // KeepAlive 30s

    std::vector<uint8_t> pl;
    append_mqtt_str(pl, "openstation_max");
    if (!c.username.empty()) append_mqtt_str(pl, c.username);
    if (!c.password.empty()) append_mqtt_str(pl, c.password);

    std::vector<uint8_t> pkt;
    pkt.push_back(0x10);
    encode_varint(pkt, vh.size() + pl.size());
    pkt.insert(pkt.end(), vh.begin(), vh.end());
    pkt.insert(pkt.end(), pl.begin(), pl.end());
    if (!send_packet(pkt.data(), pkt.size())) {
        disconnect_broker();
        return false;
    }

    uint8_t ack[4] = {0};
    ssize_t ar = recv(fd, ack, 4, MSG_WAITALL);
    if (ar != 4 || ack[0] != 0x20 || ack[3] != 0x00) {
        int e = errno;
        LOGW("MQTT", "Broker rejected CONNECT (ar=%zd rc=%d err=%s)",
             ar, ar == 4 ? static_cast<int>(ack[3]) : -1, ar < 0 ? strerror(e) : "protocol");
        disconnect_broker();
        return false;
    }

    // Use short SO_RCVTIMEO after handshake so stop() / config reload stay responsive
    struct timeval rtv {0, 500000};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));

    connected_.store(true);
    LOGI("MQTT", "Connected to %s:%d", c.host.c_str(), c.port);
    subscribe_raw(c.base_topic + "/+/set");
    if (c.discovery) publish_discovery(c);
    publish_raw(c.base_topic + "/availability", "online", true);
    return true;
}

bool MqttClient::publish(const std::string& topic, const std::string& payload, bool retain) {
    return publish_raw(topic, payload, retain);
}

bool MqttClient::publish_raw(const std::string& topic, const std::string& payload, bool retain) {
    if (!connected_.load()) return false;
    std::vector<uint8_t> body;
    append_mqtt_str(body, topic);
    body.insert(body.end(), payload.begin(), payload.end());

    std::vector<uint8_t> pkt;
    pkt.push_back(static_cast<uint8_t>(0x30 | (retain ? 0x01 : 0x00)));
    encode_varint(pkt, body.size());
    pkt.insert(pkt.end(), body.begin(), body.end());
    return send_packet(pkt.data(), pkt.size());
}

bool MqttClient::subscribe_raw(const std::string& topic) {
    if (!connected_.load()) return false;
    std::vector<uint8_t> body;
    uint16_t pid = next_pid_++;
    if (next_pid_ == 0) next_pid_ = 1;
    body.push_back(static_cast<uint8_t>(pid >> 8));
    body.push_back(static_cast<uint8_t>(pid & 0xFF));
    append_mqtt_str(body, topic);
    body.push_back(0); // QoS 0

    std::vector<uint8_t> pkt;
    pkt.push_back(0x82);
    encode_varint(pkt, body.size());
    pkt.insert(pkt.end(), body.begin(), body.end());
    return send_packet(pkt.data(), pkt.size());
}

void MqttClient::publish_discovery(const MqttConfig& c) {
    using json::Value;
    Value dev = Value::make_obj();
    Value ids = Value::make_arr();
    ids.push(Value::make_str("openstation_max"));
    dev["identifiers"] = std::move(ids);
    dev["name"] = Value::make_str("OpenStation Max");
    dev["model"] = Value::make_str("Yandex Station Max (Open Firmware)");
    dev["manufacturer"] = Value::make_str("OpenStation");

    auto pub_cfg = [&](const std::string& comp, const std::string& obj_id, Value payload) {
        payload["device"] = dev;
        payload["availability_topic"] = Value::make_str(c.base_topic + "/availability");
        std::string topic = "homeassistant/" + comp + "/openstation_max/" + obj_id + "/config";
        publish_raw(topic, payload.dump(-1), true);
    };

    // 1. LED Ring Light (JSON schema)
    {
        Value l = Value::make_obj();
        l["name"] = Value::make_str("LED Ring");
        l["unique_id"] = Value::make_str("openstation_max_ring");
        l["schema"] = Value::make_str("json");
        l["state_topic"] = Value::make_str(c.base_topic + "/ring/state");
        l["command_topic"] = Value::make_str(c.base_topic + "/ring/set");
        l["brightness"] = Value::make_bool(true);
        l["brightness_scale"] = Value::make_int(255);
        Value modes = Value::make_arr();
        modes.push(Value::make_str("rgb"));
        l["supported_color_modes"] = std::move(modes);
        l["effect"] = Value::make_bool(true);
        Value eff = Value::make_arr();
        for (const char* m : {"rainbow", "solid", "breathe", "spinner", "music", "voice"}) {
            eff.push(Value::make_str(m));
        }
        l["effect_list"] = std::move(eff);
        pub_cfg("light", "ring", std::move(l));
    }

    // 2. Mic Mute Switch
    {
        Value s = Value::make_obj();
        s["name"] = Value::make_str("Microphone Mute");
        s["unique_id"] = Value::make_str("openstation_max_mute");
        s["state_topic"] = Value::make_str(c.base_topic + "/mute/state");
        s["command_topic"] = Value::make_str(c.base_topic + "/mute/set");
        s["payload_on"] = Value::make_str("ON");
        s["payload_off"] = Value::make_str("OFF");
        pub_cfg("switch", "mute", std::move(s));
    }

    // 3. Woofer Volume Number
    {
        Value n = Value::make_obj();
        n["name"] = Value::make_str("Woofer Gain");
        n["unique_id"] = Value::make_str("openstation_max_woofer_vol");
        n["state_topic"] = Value::make_str(c.base_topic + "/woofer_vol/state");
        n["command_topic"] = Value::make_str(c.base_topic + "/woofer_vol/set");
        n["min"] = Value::make_int(0);
        n["max"] = Value::make_int(255);
        pub_cfg("number", "woofer_vol", std::move(n));
    }

    // 4. Output Route Select
    {
        Value sel = Value::make_obj();
        sel["name"] = Value::make_str("Audio Output Route");
        sel["unique_id"] = Value::make_str("openstation_max_route");
        sel["state_topic"] = Value::make_str(c.base_topic + "/route/state");
        sel["command_topic"] = Value::make_str(c.base_topic + "/route/set");
        Value opts = Value::make_arr();
        for (const char* o : {"auto", "speakers", "headphones", "both", "hdmi"}) {
            opts.push(Value::make_str(o));
        }
        sel["options"] = std::move(opts);
        pub_cfg("select", "route", std::move(sel));
    }

    // 5. Sensors (Lux, Temp, Jack)
    {
        Value lx = Value::make_obj();
        lx["name"] = Value::make_str("Ambient Light");
        lx["unique_id"] = Value::make_str("openstation_max_lux");
        lx["state_topic"] = Value::make_str(c.base_topic + "/lux/state");
        lx["device_class"] = Value::make_str("illuminance");
        lx["unit_of_measurement"] = Value::make_str("lx");
        pub_cfg("sensor", "lux", std::move(lx));

        Value tp = Value::make_obj();
        tp["name"] = Value::make_str("SoC Temperature");
        tp["unique_id"] = Value::make_str("openstation_max_temp");
        tp["state_topic"] = Value::make_str(c.base_topic + "/temp/state");
        tp["device_class"] = Value::make_str("temperature");
        tp["unit_of_measurement"] = Value::make_str("°C");
        pub_cfg("sensor", "temp", std::move(tp));
    }
}

void MqttClient::publish_state(const std::string& ring_mode, bool ring_on, int brightness,
                               const std::string& hex_color, bool mic_muted,
                               int woofer_vol, const std::string& route,
                               double lux, double temp_c, bool jack_plugged) {
    if (!connected_.load()) return;
    MqttConfig c;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
    }

    int r = 0, g = 0, b = 0;
    parse_hex(hex_color, r, g, b);
    json::Value ring = json::Value::make_obj();
    ring["state"] = json::Value::make_str(ring_on ? "ON" : "OFF");
    ring["brightness"] = json::Value::make_int(brightness);
    ring["effect"] = json::Value::make_str(ring_mode);
    json::Value col = json::Value::make_obj();
    col["r"] = json::Value::make_int(r);
    col["g"] = json::Value::make_int(g);
    col["b"] = json::Value::make_int(b);
    ring["color"] = std::move(col);

    publish_raw(c.base_topic + "/ring/state", ring.dump(-1), true);
    publish_raw(c.base_topic + "/mute/state", mic_muted ? "ON" : "OFF", true);
    publish_raw(c.base_topic + "/woofer_vol/state", std::to_string(woofer_vol), true);
    publish_raw(c.base_topic + "/route/state", route, true);

    char lbuf[32], tbuf[32];
    snprintf(lbuf, sizeof(lbuf), "%.1f", lux);
    snprintf(tbuf, sizeof(tbuf), "%.1f", temp_c);
    publish_raw(c.base_topic + "/lux/state", lbuf);
    publish_raw(c.base_topic + "/temp/state", tbuf);
    publish_raw(c.base_topic + "/jack/state", jack_plugged ? "ON" : "OFF");
}

void MqttClient::worker_loop() {
    auto sleep_interruptible = [&](int total_ms) {
        for (int elapsed = 0; elapsed < total_ms && running_.load() && !cfg_dirty_.load(); elapsed += 100) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    };

    auto last_ping = std::chrono::steady_clock::now();
    while (running_.load()) {
        MqttConfig c;
        {
            std::lock_guard<std::mutex> lk(cfg_mu_);
            c = cfg_;
        }
        cfg_dirty_.store(false);

        if (!c.enabled || c.host.empty()) {
            disconnect_broker();
            sleep_interruptible(2000);
            continue;
        }

        if (!connected_.load()) {
            disconnect_broker();
            if (!connect_broker(c)) {
                sleep_interruptible(5000);
                continue;
            }
            last_ping = std::chrono::steady_clock::now();
        }

        int fd = -1;
        {
            std::lock_guard<std::mutex> lk(sock_mu_);
            fd = sock_fd_;
        }
        if (fd < 0) {
            connected_.store(false);
            continue;
        }

        // Read incoming MQTT packets (fd is only closed by this worker or after join())
        uint8_t hdr_b = 0;
        ssize_t n = recv(fd, &hdr_b, 1, 0);
        if (n == 0) {
            LOGW("MQTT", "Broker closed connection");
            disconnect_broker();
            continue;
        } else if (n < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                disconnect_broker();
                continue;
            }
        } else {
            // Decode varint remaining length
            size_t rem_len = 0, mult = 1;
            uint8_t d = 0;
            bool ok = true;
            do {
                if (recv(fd, &d, 1, MSG_WAITALL) != 1) { ok = false; break; }
                rem_len += (d & 0x7F) * mult;
                mult *= 128;
            } while ((d & 0x80) != 0 && mult <= 2097152);

            if (!ok || rem_len > 262144) {
                disconnect_broker();
                continue;
            }
            std::vector<uint8_t> body(rem_len);
            size_t got = 0;
            while (got < rem_len) {
                ssize_t r = recv(fd, body.data() + got, rem_len - got, 0);
                if (r <= 0) { ok = false; break; }
                got += static_cast<size_t>(r);
            }
            if (!ok) {
                disconnect_broker();
                continue;
            }

            uint8_t ptype = hdr_b & 0xF0;
            if (ptype == 0x30 && body.size() >= 2) { // PUBLISH
                uint16_t tlen = (static_cast<uint16_t>(body[0]) << 8) | body[1];
                if (2u + tlen <= body.size()) {
                    std::string topic(reinterpret_cast<const char*>(body.data() + 2), tlen);
                    size_t pl_off = 2u + tlen;
                    if ((hdr_b & 0x06) != 0 && pl_off + 2 <= body.size()) pl_off += 2; // QoS packet id
                    std::string payload(reinterpret_cast<const char*>(body.data() + pl_off),
                                        body.size() - pl_off);
                    std::string prefix = c.base_topic + "/";
                    if (topic.rfind(prefix, 0) == 0 && cmd_cb_) {
                        cmd_cb_(topic.substr(prefix.size()), payload);
                    }
                }
            }
        }

        auto now = std::chrono::steady_clock::now();
        if (now - last_ping >= std::chrono::seconds(20)) {
            uint8_t pingreq[2] = {0xC0, 0x00};
            if (!send_packet(pingreq, 2)) disconnect_broker();
            last_ping = now;
        }
    }
}

json::Value MqttClient::get_status_json() const {
    using json::Value;
    Value obj = Value::make_obj();
    MqttConfig c;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
    }
    obj["enabled"] = Value::make_bool(c.enabled);
    obj["connected"] = Value::make_bool(connected_.load());
    obj["host"] = Value::make_str(c.host);
    obj["port"] = Value::make_int(c.port);
    return obj;
}

} // namespace openstation
