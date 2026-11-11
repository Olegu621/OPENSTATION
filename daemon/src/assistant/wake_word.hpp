#pragma once

#include "utils/json_min.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

struct WakeWordSettings {
    bool enabled = true;
    std::string keywords = "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";
    float threshold = 0.20f;
    float score = 1.5f;
    int num_threads = 1;
    int mic_sensitivity = 85;
};

class WakeWordEngine {
public:
    using WakeCallback = std::function<void(const std::string& keyword)>;

    WakeWordEngine() = default;
    ~WakeWordEngine();

    bool start(const WakeWordSettings& settings, WakeCallback cb);
    void stop();

    void update_settings(const WakeWordSettings& settings);
    void set_paused(bool paused);
    bool is_paused() const { return paused_.load(); }
    bool is_ready() const { return ready_.load(); }

    void feed_audio_16k(const int16_t* samples, size_t count);

    json::Value get_status_json();

private:
    void worker_loop();

    WakeWordSettings settings_;
    std::mutex cfg_mu_;
    bool settings_dirty_ = false;

    WakeCallback cb_;

    std::atomic<bool> running_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> ready_{false};
    std::atomic<bool> vad_speech_active_{false};
    std::atomic<uint64_t> detections_{0};
    std::string status_str_ = "uninitialized";
    std::mutex stat_mu_;

    std::mutex q_mu_;
    std::condition_variable q_cv_;
    std::vector<float> pcm_q_;
    bool reset_req_ = false;

    std::thread worker_;
};

} // namespace openstation
