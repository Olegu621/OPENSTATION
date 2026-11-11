#pragma once

#include "config.hpp"
#include "utils/json_min.hpp"

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace openstation {

class MqttClient {
public:
    using CommandCallback = std::function<void(const std::string& topic_suffix, const std::string& payload)>;

    MqttClient() = default;
    ~MqttClient();

    void start(const MqttConfig& cfg, CommandCallback cmd_cb);
    void stop();
    void update_config(const MqttConfig& cfg);

    void publish_state(const std::string& ring_mode, bool ring_on, int brightness,
                       const std::string& hex_color, bool mic_muted,
                       int woofer_vol, const std::string& route,
                       double lux, double temp_c, bool jack_plugged);

    bool is_connected() const { return connected_.load(); }
    bool publish(const std::string& topic, const std::string& payload, bool retain = false);
    json::Value get_status_json() const;

private:
    void worker_loop();
    bool connect_broker(const MqttConfig& c);
    void disconnect_broker();
    void shutdown_socket();
    bool send_packet(const uint8_t* data, size_t len);
    bool publish_raw(const std::string& topic, const std::string& payload, bool retain = false);
    bool subscribe_raw(const std::string& topic);
    void publish_discovery(const MqttConfig& c);

    MqttConfig cfg_;
    mutable std::mutex cfg_mu_;
    std::atomic<bool> cfg_dirty_{false};

    CommandCallback cmd_cb_;
    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    int sock_fd_ = -1;
    std::mutex sock_mu_;
    std::thread worker_;
    uint16_t next_pid_ = 1;
};

} // namespace openstation
