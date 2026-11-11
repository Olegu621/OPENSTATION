#pragma once

#include "assistant/wake_word.hpp"
#include "config.hpp"
#include "hw/framebuffer_hw.hpp"
#include "utils/json_min.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

enum class AssistantState {
    Idle,
    Connecting,
    Listening,
    Thinking,
    Speaking,
    Error
};

struct AssistantCallbacks {
    std::function<void(const int16_t* samples, size_t frames, int rate, int channels)> play_pcm;
    std::function<void()> clear_playback;
    std::function<bool()> is_playback_or_tail_active;
    std::function<void()> play_wake_chime;
    std::function<void()> play_error_chime;
    std::function<void(VoiceVisualState)> set_visual_state;
    std::function<void(const std::string& text, uint8_t r, uint8_t g, uint8_t b, int duration_ms)> show_screen_text;
    std::function<void(const json::Value& evt)> broadcast_event;
    std::function<json::Value(const std::string& name, const json::Value& args)> execute_tool;
    std::function<std::string()> get_runtime_context;
};

struct DialogEntry {
    std::string role; // "user", "model", "tool"
    std::string text;
    std::string ts;
};

class AssistantManager {
public:
    AssistantManager() = default;
    ~AssistantManager();

    AssistantManager(const AssistantManager&) = delete;
    AssistantManager& operator=(const AssistantManager&) = delete;

    bool start(const AssistantConfig& cfg, AssistantCallbacks cb);
    void stop();
    void update_config(const AssistantConfig& cfg);
    void set_mic_muted(bool muted);
    void set_mic_sensitivity(int sensitivity);

    void feed_audio_16k(const int16_t* samples, size_t count, float rms);

    bool trigger_session(const std::string& reason = "manual");
    bool toggle_session();
    void stop_session(const std::string& reason = "stop");
    bool send_text_prompt(const std::string& text);

    bool is_active() const;
    AssistantState state() const { return state_.load(); }
    float last_mic_rms() const { return last_mic_rms_.load(); }
    static const char* state_str(AssistantState st);

    json::Value get_status_json();

private:
    enum class TriggerSource { WakeWord, Manual, Text };

    bool start_session_internal(TriggerSource src,
                                const std::string& initial_text,
                                const std::string& wake_kw);
    void session_worker(uint64_t sid,
                        TriggerSource src,
                        std::string initial_text,
                        AssistantConfig cfg_snap);
    void abort_active_session_locked();

    void set_state(AssistantState st);
    void append_history(const std::string& role, const std::string& text);
    void emit_event(const std::string& kind, const std::string& role = "", const std::string& text = "");
    void set_error(const std::string& msg, bool play_chime = true);

    json::Value build_setup_json(const AssistantConfig& c) const;
    static WakeWordSettings to_ww_settings(const AssistantConfig& c, int mic_sensitivity);

    AssistantConfig cfg_;
    int mic_sensitivity_ = 85;
    mutable std::mutex cfg_mu_;
    AssistantCallbacks cb_;
    WakeWordEngine wake_word_;

    std::atomic<bool> running_{false};
    std::atomic<bool> mic_muted_{false};
    std::atomic<AssistantState> state_{AssistantState::Idle};

    // Circular pre-roll PCM buffer (16 kHz mono) maintained while Idle
    std::mutex pre_mu_;
    std::vector<int16_t> pre_ring_;
    size_t pre_head_ = 0;
    size_t pre_count_ = 0;

    // Outgoing audio & prompt queue for active session
    std::mutex tx_mu_;
    std::condition_variable tx_cv_;
    std::vector<int16_t> tx_pcm_;
    std::deque<std::string> tx_texts_;
    std::atomic<float> last_mic_rms_{0.0f};
    std::atomic<int64_t> last_speech_ms_{0};

    // Session thread management
    std::mutex session_ctrl_mu_;
    std::thread session_thread_;
    std::atomic<uint64_t> session_seq_{0};
    std::atomic<bool> session_abort_{false};

    // Transcript & telemetry state
    std::mutex hist_mu_;
    std::deque<DialogEntry> history_;
    std::string live_user_text_;
    std::string live_model_text_;
    std::string last_user_text_;
    std::string last_model_text_;
    std::string last_error_;
    std::string last_trigger_reason_;
    std::atomic<uint64_t> sessions_count_{0};
    std::atomic<bool> use_proxy_host_{false};
    std::atomic<bool> search_quota_unsupported_{false};

    // Guard to avoid self-retriggering right after a session ends
    std::atomic<int64_t> wake_suppress_until_ms_{0};
};

} // namespace openstation
