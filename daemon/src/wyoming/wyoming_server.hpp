#pragma once

#include "config.hpp"
#include "utils/json_min.hpp"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

// Implements Home Assistant Wyoming Satellite protocol over TCP (default port 10700).
// Framing: newline-terminated JSON header with optional data_length & payload_length,
// followed directly by raw UTF-8 data + binary payload (PCM audio chunks).
class WyomingServer {
public:
    using TtsAudioCallback = std::function<void(const int16_t* samples, size_t frames, int rate, int channels)>;
    using StateCallback = std::function<void(const std::string& state)>; // idle, streaming, tts

    WyomingServer() = default;
    ~WyomingServer();

    bool start(const WyomingConfig& cfg, TtsAudioCallback tts_cb, StateCallback state_cb);
    void stop();
    void update_config(const WyomingConfig& cfg);

    // Feed 16 kHz mono 16-bit PCM from AudioEngine (320 samples / 20 ms chunks)
    void feed_audio_16k(const int16_t* samples, size_t count, float rms);

    // Trigger manual voice session (e.g., Action button press or Web UI "Push to Talk")
    void trigger_button_session(int duration_ms = 5500);
    void stop_session();

    bool client_connected() const { return client_connected_.load(); }
    bool is_streaming() const { return streaming_active_.load(); }
    json::Value get_status_json() const;

private:
    void accept_loop();
    void handle_client(int cfd);
    bool send_event(int fd, const std::string& type, const json::Value& data,
                    const uint8_t* payload = nullptr, size_t payload_len = 0);
    bool send_raw(int fd, const void* buf, size_t len);

    WyomingConfig cfg_;
    mutable std::mutex cfg_mu_;

    TtsAudioCallback tts_cb_;
    StateCallback state_cb_;

    int listen_fd_ = -1;
    int client_fd_ = -1;
    mutable std::mutex client_mu_;

    std::atomic<bool> running_{false};
    std::atomic<bool> client_connected_{false};
    std::atomic<bool> streaming_active_{false};
    std::atomic<bool> remote_run_pipeline_{false};
    std::string peer_addr_;
    std::string last_stt_text_;

    std::chrono::steady_clock::time_point stream_until_{};
    std::chrono::steady_clock::time_point last_voice_tp_{};
    bool audio_start_sent_ = false;
    int tts_rate_ = 22050;
    int tts_channels_ = 1;

    std::thread accept_thread_;
};

} // namespace openstation
