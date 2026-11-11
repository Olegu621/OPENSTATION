#pragma once

#include "config.hpp"
#include "utils/json_min.hpp"

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

class AudioEngine {
public:
    using Pcm16kCallback = std::function<void(const int16_t* samples, size_t count, float rms)>;
    using WsAudioCallback = std::function<void(const int16_t* samples, size_t count)>;
    using SpectrumCallback = std::function<void(const std::array<float, 24>& bands)>;

    AudioEngine() = default;
    ~AudioEngine();

    bool start(const AudioConfig& cfg, Pcm16kCallback pcm_cb, SpectrumCallback spec_cb);
    void stop();

    void update_config(const AudioConfig& cfg);
    void set_mic_muted(bool muted);
    void set_capture_enabled(bool enabled);
    bool capture_enabled() const { return capture_enabled_.load(); }

    void set_ws_audio_sink(WsAudioCallback cb);
    void push_external_spectrum(const std::array<float, 24>& bands);

    void play_pcm(const int16_t* samples, size_t frame_count, int sample_rate, int channels);
    void clear_playback_queue();
    void play_test_tone(int freq_hz = 440, int duration_ms = 700, bool sweep = false);
    void play_wake_chime();
    void play_error_chime();
    void play_timer_alarm();
    bool is_playing() const { return playing_active_.load(); }
    bool is_playback_or_tail_active(int tail_ms = 350) const;
    float playback_level() const { return playback_rms_.load(); }

    std::vector<uint8_t> export_recent_wav(int seconds = 5);

    static void run_cli_probe();

    json::Value get_status_json();

private:
    struct PlayChunk {
        uint64_t epoch = 0;
        std::vector<int16_t> samples_48k_stereo;
    };

    void capture_loop();
    void run_alsa_capture_session();
    void playback_loop();
    void process_frame(const int16_t* interleaved, size_t frames, int ch_count, int sample_rate);

    AudioConfig cfg_;
    mutable std::mutex cfg_mu_;

    Pcm16kCallback pcm_cb_;
    SpectrumCallback spec_cb_;
    WsAudioCallback ws_audio_cb_;
    std::mutex ws_cb_mu_;

    std::atomic<bool> running_{false};
    std::atomic<bool> capture_enabled_{true};
    std::atomic<bool> reopen_capture_{false};
    std::atomic<bool> playing_active_{false};
    std::atomic<uint64_t> playback_epoch_{0};
    std::atomic<int64_t> last_playback_end_ms_{0};
    std::atomic<float> playback_rms_{0.0f};
    std::atomic<bool> aaudio_loaded_{false};
    std::atomic<int> active_channels_{0};
    std::atomic<int> active_sample_rate_{0};
    std::string capture_backend_ = "initializing";

    std::thread cap_thread_;
    std::thread play_thread_;

    mutable std::mutex stat_mu_;
    std::array<float, 16> ch_rms_{};
    std::array<float, 16> dc_prev_x_{};
    std::array<float, 16> dc_prev_y_{};
    float mono_rms_ = 0.0f;
    std::array<float, 24> ext_spectrum_{};
    std::chrono::steady_clock::time_point ext_spec_tp_{};

    static constexpr size_t kWavRingMax = 16000 * 10;
    std::vector<int16_t> wav_ring_;
    size_t wav_head_ = 0;
    size_t wav_filled_ = 0;
    std::mutex wav_mu_;

    static constexpr size_t kAecTaps = 64;
    std::vector<float> aec_w_;
    std::vector<float> aec_x_;
    size_t aec_pos_ = 0;
    float ns_noise_floor_ = 120.0f;

    double resamp_phase_ = 0.0;
    std::vector<int16_t> mono48k_rem_;
    std::vector<int16_t> out16k_acc_;

    static constexpr size_t kMaxPlayQueueChunks = 1024;
    std::mutex play_mu_;
    std::condition_variable play_cv_;
    std::queue<PlayChunk> play_q_;
};

} // namespace openstation
