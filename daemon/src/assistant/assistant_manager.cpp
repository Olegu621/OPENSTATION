#include "assistant/listening_deadline.hpp"
#include "assistant/assistant_manager.hpp"
#include "assistant/live_turn_policy.hpp"
#include "hw/voice_gain_policy.hpp"
#include "assistant/assistant_personalization.hpp"
#include "assistant/assistant_intents.hpp"
#include "assistant/quiet_speech.hpp"
#include "assistant/tls_ws_client.hpp"
#include "assistant/proxy_selector.hpp"
#include "utils/base64.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <memory>
#include <unistd.h>

namespace openstation {

namespace {

constexpr const char* kGeminiHost = "generativelanguage.googleapis.com";
constexpr int kGeminiPort = 443;
constexpr const char* kGeminiWsPathPrefix =
    "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";
constexpr const char* kCaBundlePath = "/data/local/openstation/cacert.pem";

// Send ~100 ms frames upstream (1600 samples = 3200 PCM bytes)
constexpr size_t kTxChunkSamples = 1600;
// A connection can take several seconds when the first route fails. Preserve
// the beginning of a spoken request while the verified alternate is selected.
constexpr size_t kMaxConnectingPcmSamples = 16000 * 20;
constexpr size_t kMaxLivePcmSamples = 16000 * 5;

// Optional taps for the stock Yandex spotter helpers (glibc chroot).
// Each engine reads 16 kHz mono PCM from its own FIFO. When a helper is not
// running, open/write fail harmlessly and AssistantManager falls back to Sherpa KWS.
struct StockSpotterTap {
    const char* fifo;
    int fd = -2;
    int64_t next_try_ms = 0;
};
StockSpotterTap g_stock_spotter_taps[] = {
    {"/data/local/spotter-root/run/spotter.fifo"},        // «Алиса»
    {"/data/local/spotter-root/run/spotter-yandex.fifo"}, // «Яндекс»
};
constexpr size_t kStockSpotterTapCount =
    sizeof(g_stock_spotter_taps) / sizeof(g_stock_spotter_taps[0]);

bool stock_spotter_model_available(const char* dir) {
    char path[256];
    snprintf(path, sizeof(path), "%s/acoustic_model.nnet", dir);
    return ::access(path, R_OK) == 0;
}

bool is_alisa_name(const std::string& name) {
    return name == "\xD0\x90\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0" || // Алиса
           name == "\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0" || // алиса
           name == "Alisa" || name == "ALISA" || name == "alisa";
}

bool stock_alisa_available() {
    return ::access("/data/local/spotter-root/probe", X_OK) == 0 &&
           ::access("/data/local/spotter-root/run_spotter.sh", X_OK) == 0 &&
           stock_spotter_model_available("/data/local/spotter-root/models/alisa-2023");
}

bool stock_yandex_available() {
    return ::access("/data/local/spotter-root/probe", X_OK) == 0 &&
           ::access("/data/local/spotter-root/run_spotter_yandex.sh", X_OK) == 0 &&
           stock_spotter_model_available("/data/local/spotter-root/models/yandex-2022");
}

size_t stock_phrase_index(const AssistantConfig& c) {
    if (c.wake_phrase == "yandex" && stock_yandex_available()) return 1;
    return 0;
}

bool stock_phrase_available(const AssistantConfig& c) {
    return stock_phrase_index(c) == 1 ? stock_yandex_available()
                                      : stock_alisa_available();
}

bool use_stock_wake_config(const AssistantConfig& c) {
    return c.enabled && c.mode == "gemini" && c.wake_word_enabled &&
           is_alisa_name(c.name) && stock_phrase_available(c);
}

void close_all_stock_taps_locked() {
    for (auto& tap : g_stock_spotter_taps) {
        if (tap.fd >= 0) {
            ::close(tap.fd);
            tap.fd = -2;
        }
        tap.next_try_ms = 0;
    }
}

int64_t steady_now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

std::string now_hhmmss() {
    std::time_t t = std::time(nullptr);
    struct tm tm_now {};
    localtime_r(&t, &tm_now);
    char buf[16];
    snprintf(buf, sizeof(buf), "%02d:%02d:%02d", tm_now.tm_hour, tm_now.tm_min, tm_now.tm_sec);
    return buf;
}

std::string url_encode_query(const std::string& s) {
    static const char* kHex = "0123456789ABCDEF";
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
            out.push_back(static_cast<char>(c));
        } else {
            out.push_back('%');
            out.push_back(kHex[(c >> 4) & 0x0F]);
            out.push_back(kHex[c & 0x0F]);
        }
    }
    return out;
}

int parse_pcm_sample_rate(const std::string& mime) {
    std::string lower;
    lower.reserve(mime.size());
    for (char c : mime) lower.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
    if (lower.rfind("audio/pcm", 0) != 0 && lower.rfind("audio/l16", 0) != 0) {
        return 0;
    }
    size_t pos = lower.find("rate=");
    if (pos != std::string::npos) {
        int r = std::atoi(lower.c_str() + pos + 5);
        if (r >= 8000 && r <= 48000) return r;
    }
    return 24000; // Gemini Live native PCM output rate
}

bool contains_ci(const std::string& hay, const std::string& needle) {
    if (needle.empty()) return true;
    auto it = std::search(hay.begin(), hay.end(), needle.begin(), needle.end(),
                          [](char a, char b) {
                              return std::tolower(static_cast<unsigned char>(a)) ==
                                     std::tolower(static_cast<unsigned char>(b));
                          });
    return it != hay.end();
}

static std::string utf8_to_lower_ru(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        if (c >= 'A' && c <= 'Z') {
            out.push_back(static_cast<char>(c + 32));
        } else if (c == 0xD0 && i + 1 < s.size()) {
            unsigned char c2 = static_cast<unsigned char>(s[i + 1]);
            if (c2 >= 0x90 && c2 <= 0xAF) {
                if (c2 <= 0x9F) {
                    out.push_back(static_cast<char>(0xD0));
                    out.push_back(static_cast<char>(c2 + 0x20));
                } else {
                    out.push_back(static_cast<char>(0xD1));
                    out.push_back(static_cast<char>(c2 - 0x20));
                }
                i++;
                continue;
            } else if (c2 == 0x81) {
                out.push_back(static_cast<char>(0xD1));
                out.push_back(static_cast<char>(0x91));
                i++;
                continue;
            }
            out.push_back(static_cast<char>(c));
        } else {
            out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

std::string trim_str(std::string s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) s.erase(s.begin());
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.pop_back();
    return s;
}

json::Value make_prop(const char* type, const char* desc) {
    using json::Value;
    Value p = Value::make_obj();
    p["type"] = Value::make_str(type);
    p["description"] = Value::make_str(desc);
    return p;
}

json::Value make_enum_prop(const char* desc, std::initializer_list<const char*> vals) {
    using json::Value;
    Value p = Value::make_obj();
    p["type"] = Value::make_str("STRING");
    p["description"] = Value::make_str(desc);
    Value e = Value::make_arr();
    for (const char* v : vals) e.push(Value::make_str(v));
    p["enum"] = std::move(e);
    return p;
}

json::Value make_func_decl(const char* name,
                           const char* desc,
                           json::Value props,
                           std::initializer_list<const char*> required,
                           bool non_blocking) {
    using json::Value;
    Value fn = Value::make_obj();
    fn["name"] = Value::make_str(name);
    fn["description"] = Value::make_str(desc);
    if (non_blocking) {
        fn["behavior"] = Value::make_str("NON_BLOCKING");
    } else {
        fn["behavior"] = Value::make_str("BLOCKING");
    }
    if (!props.obj_val.empty()) {
        Value schema = Value::make_obj();
        schema["type"] = Value::make_str("OBJECT");
        schema["properties"] = std::move(props);
        if (required.size() > 0) {
            Value req = Value::make_arr();
            for (const char* r : required) req.push(Value::make_str(r));
            schema["required"] = std::move(req);
        }
        fn["parameters"] = std::move(schema);
    }
    return fn;
}

} // namespace

AssistantManager::~AssistantManager() {
    stop();
}

const char* AssistantManager::state_str(AssistantState st) {
    switch (st) {
        case AssistantState::Idle: return "idle";
        case AssistantState::Connecting: return "connecting";
        case AssistantState::Listening: return "listening";
        case AssistantState::Thinking: return "thinking";
        case AssistantState::Speaking: return "speaking";
        case AssistantState::Error: return "error";
    }
    return "idle";
}

WakeWordSettings AssistantManager::to_ww_settings(const AssistantConfig& c,
                                                  int mic_sensitivity,
                                                  bool stock_active) {
    WakeWordSettings ww;
    const bool voice_enabled = c.enabled && c.mode == "gemini";
    ww.kws_enabled = voice_enabled && c.wake_word_enabled && !stock_active;
    ww.quick_commands = voice_enabled && c.quick_commands;
    ww.enabled = ww.kws_enabled || ww.quick_commands;
    ww.keywords = c.wake_word_enabled ? c.kws_keywords : "";
    ww.threshold = c.kws_threshold;
    ww.score = c.kws_score;
    ww.num_threads = c.kws_threads;
    ww.mic_sensitivity = std::clamp(mic_sensitivity, 10, 100);
    return ww;
}

bool AssistantManager::is_stock_wake_active(const AssistantConfig& c) const {
    return use_stock_wake_config(c) && !stock_fallback_to_kws_.load(std::memory_order_relaxed);
}

bool AssistantManager::start(const AssistantConfig& cfg, AssistantCallbacks cb) {
    stop();
    int sens = 85;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        cfg_ = cfg;
        sens = mic_sensitivity_;
    }
    {
        std::lock_guard<std::mutex> slk(stock_mu_);
        close_all_stock_taps_locked();
        stock_health_.reset();
        stock_ready_.store(false);
        stock_fallback_to_kws_.store(false);
    }
    cb_ = std::move(cb);
    running_.store(true);
    set_state(AssistantState::Idle);

    wake_word_.start(to_ww_settings(cfg, sens, is_stock_wake_active(cfg)), [this](const std::string& kw) {
        if (!running_.load() || mic_muted_.load()) return;
        if (steady_now_ms() < wake_suppress_until_ms_.load()) return;

        AssistantConfig c;
        {
            std::lock_guard<std::mutex> lk(cfg_mu_);
            c = cfg_;
        }
        if (!c.enabled || c.mode != "gemini" || !c.wake_word_enabled) return;

        const auto st = state_.load();
        if (st == AssistantState::Speaking) {
            if (steady_now_ms() - last_speech_ms_.load() <= 800) {
                start_session_internal(TriggerSource::WakeWord, "", kw, session_seq_.load());
            }
        } else if (st == AssistantState::Idle || st == AssistantState::Error) {
            start_session_internal(TriggerSource::WakeWord, "", kw);
        }
    }, [this](const std::vector<int16_t>& pcm) {
        if (!cb_.quick_command || !local_asr_ready_ || !running_ || mic_muted_ || is_active() ||
            steady_now_ms() < wake_suppress_until_ms_ ||
            (cb_.is_playback_or_tail_active && cb_.is_playback_or_tail_active())) return;
        {
            std::lock_guard<std::mutex> lock(quick_pcm_mu_);
            quick_pending_pcm_ = pcm;
        }
        quick_pcm_cv_.notify_one();
    });

    if (cfg.enabled && cfg.mode == "gemini" && cfg.quick_commands) start_quick_command_worker();
    LOGI("Assistant", "Manager started (mode=%s, model=%s, wake=%s, hasKey=%s)",
         cfg.mode.c_str(),
         cfg.gemini_model.c_str(),
         cfg.wake_word_enabled ? "on" : "off",
         cfg.gemini_api_key.empty() ? "no" : "yes");
    return true;
}

void AssistantManager::stop() {
    {
        std::lock_guard<std::mutex> lock(quick_pcm_mu_);
        running_.store(false);
    }
    quick_pcm_cv_.notify_one();
    stop_session("daemon_stop");
    {
        std::lock_guard<std::mutex> lk(session_ctrl_mu_);
        if (session_thread_.joinable()) {
            session_thread_.join();
        }
    }
    wake_word_.stop();
    {
        std::lock_guard<std::mutex> slk(stock_mu_);
        close_all_stock_taps_locked();
        stock_health_.reset();
        stock_ready_.store(false);
        stock_fallback_to_kws_.store(false);
    }
    std::lock_guard<std::mutex> lock(quick_worker_mu_);
    if (quick_worker_.joinable()) quick_worker_.join();
    quick_pending_pcm_.clear();
}

void AssistantManager::start_quick_command_worker() {
    std::lock_guard<std::mutex> lock(quick_worker_mu_);
    if (!running_ || quick_worker_.joinable()) return;
    quick_worker_ = std::thread([this] {
        // Avoid loading the recognition and wake word models at the same time.
        for (int i = 0; running_ && !wake_word_.is_ready() && i < 300; ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        if (!running_) return;
        std::string error;
        local_asr_ready_ = local_asr_.initialize(error);
        if (!local_asr_ready_) {
            LOGW("Assistant", "Quick command recognizer unavailable: %s", error.c_str());
            return;
        }

        std::vector<int16_t> pcm;
        while (running_) {
            {
                std::unique_lock<std::mutex> lock(quick_pcm_mu_);
                quick_pcm_cv_.wait(lock, [this] { return !running_ || !quick_pending_pcm_.empty(); });
                if (!running_) break;
                quick_pending_pcm_.swap(pcm);
                quick_pending_pcm_.clear();
            }
            if (mic_muted_ || is_active() || steady_now_ms() < wake_suppress_until_ms_ ||
                (cb_.is_playback_or_tail_active && cb_.is_playback_or_tail_active())) continue;
            std::string text, error;
            if (!local_asr_.recognize(pcm, text, error)) continue;
            const auto command = quick_command_from_transcript(utf8_to_lower_ru(text));
            if (command.command == QuickCommand::None) continue;
            {
                std::lock_guard<std::mutex> lock(cfg_mu_);
                if (!cfg_.enabled || !cfg_.quick_commands || cfg_.mode != "gemini") continue;
            }
            if (!running_ || mic_muted_ || is_active()) continue;
            cb_.quick_command(command);
        }
    });
}

void AssistantManager::update_config(const AssistantConfig& cfg) {
    bool stop_cur = false;
    bool reload_wake = false;
    int sens = 85;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        if (cfg.gemini_proxy_host != cfg_.gemini_proxy_host) {
            use_proxy_host_.store(false);
        }
        if (cfg.google_search != cfg_.google_search || cfg.gemini_api_key != cfg_.gemini_api_key || !cfg.google_search) {
            search_quota_unsupported_.store(false);
        }
        if (!cfg.enabled || cfg.mode != "gemini") {
            stop_cur = true;
        }
        const bool prev_stock = use_stock_wake_config(cfg_);
        const bool next_stock = use_stock_wake_config(cfg);
        reload_wake = (cfg.name != cfg_.name) || (cfg.wake_phrase != cfg_.wake_phrase) || (prev_stock != next_stock);
        cfg_ = cfg;
        sens = mic_sensitivity_;
    }
    if (reload_wake) {
        std::lock_guard<std::mutex> slk(stock_mu_);
        close_all_stock_taps_locked();
        stock_health_.reset();
        stock_ready_.store(false);
        stock_fallback_to_kws_.store(false);
    }
    const WakeWordSettings ww = to_ww_settings(cfg, sens, is_stock_wake_active(cfg));
    if (reload_wake) {
        wake_word_.restart(ww);
    } else {
        wake_word_.update_settings(ww);
    }
    if (cfg.enabled && cfg.mode == "gemini" && cfg.quick_commands) start_quick_command_worker();
    if (stop_cur) {
        stop_session("mode_changed");
    }
}

void AssistantManager::set_mic_sensitivity(int sensitivity) {
    int clamped = std::clamp(sensitivity, 10, 100);
    AssistantConfig cfg_copy;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        mic_sensitivity_ = clamped;
        cfg_copy = cfg_;
    }
    wake_word_.update_settings(to_ww_settings(cfg_copy, clamped, is_stock_wake_active(cfg_copy)));
}

void AssistantManager::set_mic_muted(bool muted) {
    mic_muted_.store(muted);
    if (muted) {
        if (remote_turn_.load() && cb_.stop_remote_capture) cb_.stop_remote_capture();
        {
            std::lock_guard<std::mutex> blk(barge_mu_);
            barge_in_.reset();
        }
        {
            std::lock_guard<std::mutex> lk(pre_mu_);
            pre_head_ = 0;
            pre_count_ = 0;
        }
        stop_session("mic_muted");
        wake_word_.set_paused(true);
    } else {
        wake_word_.set_paused(is_active());
    }
}

void AssistantManager::feed_audio_16k(const int16_t* samples, size_t count, float rms, float noise_rms,
                                      bool vqe_input) {
    if (remote_turn_.load()) return;
    if (!samples || count == 0 || !running_.load() || mic_muted_.load()) {
        return;
    }

    AssistantConfig c;
    int sens = 85;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
        sens = mic_sensitivity_;
    }
    if (!c.enabled || c.mode != "gemini") {
        return;
    }

    const bool playback_active = cb_.is_playback_or_tail_active && cb_.is_playback_or_tail_active();
    const int64_t now_ms = steady_now_ms();
    last_mic_rms_.store(rms);
    last_noise_rms_.store(noise_rms, std::memory_order_relaxed);
    if (rms >= dialogue_activity_threshold(noise_rms)) {
        last_speech_ms_.store(now_ms);
    }
    if (should_update_live_voice_ms(rms, noise_rms, playback_active)) {
        last_voice_ms_.store(now_ms);
    }

    const bool stock_cfg = use_stock_wake_config(c);
    if (!stock_cfg) {
        std::lock_guard<std::mutex> slk(stock_mu_);
        if (stock_ready_.load() || stock_fallback_to_kws_.load()) {
            close_all_stock_taps_locked();
            stock_health_.reset();
            stock_ready_.store(false);
            stock_fallback_to_kws_.store(false);
        }
    }

    const AssistantState st = state_.load();
    const bool idle = st == AssistantState::Idle || st == AssistantState::Error;
    const bool vqe_reply = (st == AssistantState::Speaking && vqe_input);
    const bool wake_during_reply = vqe_reply && c.wake_word_enabled;
    if (idle || vqe_reply) {
        if (idle && (now_ms < wake_suppress_until_ms_.load() || playback_active)) return;

        // Keep the words that immediately follow the wake phrase or voice barge-in.
        size_t max_pre = static_cast<size_t>(std::max(0, c.pre_roll_ms)) * 16U;
        if (max_pre > 0) {
            std::lock_guard<std::mutex> lk(pre_mu_);
            if (pre_ring_.size() != max_pre) {
                pre_ring_.assign(max_pre, 0);
                pre_head_ = 0;
                pre_count_ = 0;
            }
            for (size_t i = 0; i < count; ++i) {
                pre_ring_[pre_head_] = samples[i];
                pre_head_ = (pre_head_ + 1) % max_pre;
                if (pre_count_ < max_pre) ++pre_count_;
            }
        }

        if ((idle || wake_during_reply) && stock_cfg && now_ms >= wake_suppress_until_ms_.load()) {
            const size_t active = stock_phrase_index(c);
            bool health_changed = false;
            bool fallback_now = false;
            {
                std::lock_guard<std::mutex> slk(stock_mu_);
                for (size_t i = 0; i < kStockSpotterTapCount; ++i) {
                    auto& tap = g_stock_spotter_taps[i];
                    if (i != active) {
                        if (tap.fd >= 0) {
                            ::close(tap.fd);
                            tap.fd = -2;
                        }
                        continue;
                    }
                    if (tap.fd < 0 && now_ms >= tap.next_try_ms) {
                        tap.next_try_ms = now_ms + 1000;
                        tap.fd = ::open(tap.fifo, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
                    }
                    bool write_ok = false;
                    if (tap.fd >= 0) {
                        const ssize_t n = ::write(tap.fd, samples, count * sizeof(int16_t));
                        if (n >= 0 || errno == EAGAIN || errno == EWOULDBLOCK) {
                            write_ok = true;
                        } else if (errno == EPIPE || errno == ENXIO || errno == EBADF) {
                            ::close(tap.fd);
                            tap.fd = -2;
                            tap.next_try_ms = now_ms + 1000;
                        }
                    }
                    health_changed = write_ok ? stock_health_.record_write_ok()
                                              : stock_health_.record_unavailable(now_ms);
                }
                stock_ready_.store(stock_health_.healthy, std::memory_order_relaxed);
                fallback_now = stock_health_.fallback_to_kws;
                stock_fallback_to_kws_.store(fallback_now, std::memory_order_relaxed);
            }
            if (health_changed) {
                if (fallback_now) {
                    LOGW("Assistant", "Stock Yandex spotter FIFO unavailable; switching to OpenStation Sherpa KWS fallback");
                } else {
                    LOGI("Assistant", "Stock Yandex spotter FIFO recovered; resuming stock spotter");
                }
                wake_word_.update_settings(to_ww_settings(c, sens, !fallback_now));
            }
        }

        const bool stock_active = is_stock_wake_active(c);
        if ((idle || wake_during_reply) &&
            (!stock_active ? (c.wake_word_enabled || (idle && c.quick_commands)) : (idle && c.quick_commands))) {
            wake_word_.set_paused(false);
            wake_word_.feed_audio_16k(samples, count, noise_rms, wake_during_reply);
        } else if (!idle && !wake_during_reply) {
            wake_word_.set_paused(true);
        }
        if (idle) return;
    } else {
        wake_word_.set_paused(true);
    }

    // Echo Guard / hardware-AEC barge-in gate during TTS playback.
    bool trigger_barge_now = false;
    {
        std::lock_guard<std::mutex> blk(barge_mu_);
        const auto res = barge_in_.feed(rms, noise_rms, playback_active, vqe_input, c.echo_guard);
        if (res == BargeInGate::Result::Drop) {
            return;
        }
        trigger_barge_now = (res == BargeInGate::Result::TriggerBargeIn);
    }

    std::vector<int16_t> barge_pre;
    if (trigger_barge_now) {
        LOGI("Assistant", "Voice barge-in detected (rms=%.0f, noise=%.0f) -> interrupting reply", rms, noise_rms);
        if (cb_.clear_playback) cb_.clear_playback();
        set_state(AssistantState::Listening);
        {
            std::lock_guard<std::mutex> plk(pre_mu_);
            if (pre_count_ > 0 && !pre_ring_.empty()) {
                const size_t take = std::min<size_t>(pre_count_, 3520U);
                barge_pre.reserve(take);
                size_t cap = pre_ring_.size();
                size_t start_idx = (pre_head_ + cap - take) % cap;
                for (size_t i = 0; i < take; ++i) {
                    barge_pre.push_back(pre_ring_[(start_idx + i) % cap]);
                }
            }
        }
    }

    std::lock_guard<std::mutex> lk(tx_mu_);
    // Keep the connection backlog intact until it has drained; otherwise the
    // first live microphone callback discards the beginning of the request.
    size_t cap = (st == AssistantState::Connecting || tx_pcm_.size() > kMaxLivePcmSamples)
                     ? kMaxConnectingPcmSamples : kMaxLivePcmSamples;
    const size_t add_len = barge_pre.size() + count;
    if (tx_pcm_.size() + add_len > cap) {
        size_t overflow = tx_pcm_.size() + add_len - cap;
        if (overflow >= tx_pcm_.size()) tx_pcm_.clear();
        else tx_pcm_.erase(tx_pcm_.begin(), tx_pcm_.begin() + static_cast<ptrdiff_t>(overflow));
    }
    if (!barge_pre.empty()) {
        tx_pcm_.insert(tx_pcm_.end(), barge_pre.begin(), barge_pre.end());
    }
    tx_pcm_.insert(tx_pcm_.end(), samples, samples + count);
    if (tx_pcm_.size() >= kTxChunkSamples) {
        tx_cv_.notify_one();
    }
}

void AssistantManager::feed_playback_16k(const int16_t* samples, size_t count) {
    if (state_ == AssistantState::Speaking && !mic_muted_ && !remote_turn_ && !session_abort_) {
        wake_word_.feed_playback_16k(samples, count);
    }
}

bool AssistantManager::is_active() const {
    AssistantState st = state_.load();
    return st != AssistantState::Idle && st != AssistantState::Error;
}

bool AssistantManager::trigger_session(const std::string& reason) {
    if (is_active()) return true;
    return start_session_internal(TriggerSource::Manual, "", reason);
}

bool AssistantManager::trigger_wake_session(const std::string& keyword) {
    if (!running_.load()) return false;
    AssistantConfig c;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
    }
    if (!is_stock_wake_active(c)) return false;
    const bool yandex_active = stock_phrase_index(c) == 1;
    const bool yandex_hit = (keyword == "yandex");
    const bool alisa_hit = (keyword == "stock" || keyword == "\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0" || keyword.empty());
    if ((yandex_hit && !yandex_active) || (alisa_hit && yandex_active)) return false;
    stock_wake_detections_.fetch_add(1, std::memory_order_relaxed);
    std::string phrase = keyword.empty() ? "stock" : keyword;
    if (phrase == "stock") phrase = "\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";
    else if (phrase == "yandex") phrase = "\xD1\x8F\xD0\xBD\xD0\xB4\xD0\xB5\xD0\xBA\xD1\x81";
    return start_session_internal(TriggerSource::WakeWord, "", phrase);
}

bool AssistantManager::begin_remote_turn() {
    return start_session_internal(TriggerSource::Remote, "", "remote_push_to_talk");
}

void AssistantManager::feed_remote_audio_16k(const int16_t* samples, size_t count) {
    if (!samples || !count || !remote_recording_.load() || !running_.load() || mic_muted_.load()) return;
    std::lock_guard<std::mutex> lk(tx_mu_);
    if (!remote_recording_.load()) return;
    const size_t cap = kMaxConnectingPcmSamples;
    if (count > cap) { samples += count - cap; count = cap; }
    if (tx_pcm_.size() + count > cap) {
        const size_t overflow = tx_pcm_.size() + count - cap;
        tx_pcm_.erase(tx_pcm_.begin(), tx_pcm_.begin() + static_cast<ptrdiff_t>(overflow));
    }
    tx_pcm_.insert(tx_pcm_.end(), samples, samples + count);
    remote_samples_.fetch_add(count, std::memory_order_relaxed);
    tx_cv_.notify_one();
}

void AssistantManager::end_remote_turn(bool failed) {
    if (!remote_turn_.load()) return;
    failed = failed || remote_samples_.load(std::memory_order_relaxed) == 0;
    {
        std::lock_guard<std::mutex> lk(tx_mu_);
        remote_recording_.store(false);
        remote_end_pending_.store(!failed);
    }
    if (failed) {
        LOGW("Assistant", "Remote microphone unavailable");
        set_error("Микрофон пульта недоступен или не передал звук", false);
        if (cb_.show_screen_text) cb_.show_screen_text("НЕТ МИКРОФОНА ПУЛЬТА", 255, 80, 80, 3000);
        stop_session("remote_microphone_failed");
    }
    tx_cv_.notify_all();
}

bool AssistantManager::toggle_session() {
    if (is_active()) {
        stop_session("toggled_off");
        return false;
    }
    return trigger_session("button");
}

void AssistantManager::stop_session(const std::string& reason) {
    session_abort_.store(true);
    tx_cv_.notify_all();
    if (cb_.clear_playback) {
        cb_.clear_playback();
    }
    if (is_active()) {
        LOGI("Assistant", "Stop session requested (%s)", reason.c_str());
    }
}

bool AssistantManager::send_text_prompt(const std::string& text) {
    std::string clean = trim_str(text);
    if (clean.empty()) return false;

    AssistantConfig c;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
    }
    if (!c.enabled || c.mode != "gemini") {
        set_error("Ассистент отключён в настройках", false);
        return false;
    }
    if (c.gemini_api_key.empty()) {
        set_error("Gemini API ключ не задан", true);
        return false;
    }

    if (is_active()) {
        if (cb_.clear_playback) cb_.clear_playback();
        {
            std::lock_guard<std::mutex> lk(tx_mu_);
            tx_texts_.push_back(clean);
            tx_pcm_.clear();
        }
        tx_cv_.notify_one();
        append_history("user", clean);
        emit_event("message", "user", clean);
        return true;
    }

    return start_session_internal(TriggerSource::Text, clean, "text");
}

void AssistantManager::abort_active_session_locked() {
    session_abort_.store(true);
    tx_cv_.notify_all();
    if (session_thread_.joinable()) {
        if (session_thread_.get_id() == std::this_thread::get_id()) {
            session_thread_.detach();
        } else {
            session_thread_.join();
        }
    }
}

bool AssistantManager::announce_update(const std::string& text) {
    if (trim_str(text).empty()) return false;
    return start_session_internal(TriggerSource::Text, text, "update_announcement");
}

bool AssistantManager::start_session_internal(TriggerSource src,
                                              const std::string& initial_text,
                                              const std::string& wake_kw,
                                              uint64_t interrupted_session) {
    if (!running_.load()) return false;
    const bool announcement = src == TriggerSource::Text && wake_kw == "update_announcement";

    AssistantConfig c;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
    }
    if (!c.enabled || c.mode != "gemini") {
        set_error("Ассистент отключён или выбран режим Wyoming", false);
        return false;
    }
    if (src != TriggerSource::Text && mic_muted_.load()) {
        set_error("Микрофон выключен (MUTE)", true);
        return false;
    }
    if (c.gemini_api_key.empty()) {
        set_error("Не задан Gemini API ключ (настройте в Web UI -> Алиса)", !announcement);
        if (!announcement && cb_.show_screen_text) cb_.show_screen_text("НЕТ КЛЮЧА", 255, 80, 80, 2800);
        return false;
    }

    std::lock_guard<std::mutex> ctrl_lk(session_ctrl_mu_);
    // stop() may have joined the session thread while we waited for the lock; don't spawn an unjoined one.
    if (!running_.load()) return false;
    if (announcement && is_active()) return false;
    if (interrupted_session != 0) {
        if (session_seq_.load() != interrupted_session || !is_active() ||
            mic_muted_.load() || remote_turn_.load()) return false;
        stop_session("wake_word_interrupt");
    } else if (src == TriggerSource::WakeWord && is_active()) {
        return false;
    }
    abort_active_session_locked();
    {
        std::lock_guard<std::mutex> blk(barge_mu_);
        barge_in_.reset();
    }
    if (interrupted_session != 0 && cb_.clear_playback) cb_.clear_playback();

    remote_end_pending_.store(false);
    remote_samples_.store(0);
    remote_turn_.store(src == TriggerSource::Remote);
    remote_recording_.store(src == TriggerSource::Remote);
    if (announcement) c.follow_up_sec = 0;

    wake_word_.set_paused(true);
    session_abort_.store(false);
    uint64_t sid = session_seq_.fetch_add(1) + 1;
    sessions_count_.fetch_add(1);

    {
        std::lock_guard<std::mutex> lk(hist_mu_);
        last_error_.clear();
        live_user_text_.clear();
        live_model_text_.clear();
        last_user_text_.clear();
        last_model_text_.clear();
        last_trigger_reason_ =
            interrupted_session != 0       ? ("wake_interrupt:" + wake_kw)
            : (src == TriggerSource::WakeWord) ? ("wake:" + wake_kw)
            : (src == TriggerSource::Text)     ? "text"
                                               : (wake_kw.empty() ? "manual" : wake_kw);
    }

    // Prepare initial audio queue
    {
        std::vector<int16_t> snap_pre;
        if (src == TriggerSource::WakeWord) {
            std::lock_guard<std::mutex> plk(pre_mu_);
            if (pre_count_ > 0 && !pre_ring_.empty()) {
                snap_pre.reserve(pre_count_);
                size_t cap = pre_ring_.size();
                size_t start_idx = (pre_head_ + cap - pre_count_) % cap;
                for (size_t i = 0; i < pre_count_; ++i) {
                    snap_pre.push_back(pre_ring_[(start_idx + i) % cap]);
                }
            }
            pre_head_ = 0;
            pre_count_ = 0;
        } else {
            std::lock_guard<std::mutex> plk(pre_mu_);
            pre_head_ = 0;
            pre_count_ = 0;
        }

        std::lock_guard<std::mutex> tlk(tx_mu_);
        tx_pcm_ = std::move(snap_pre);
        tx_texts_.clear();
        if (src == TriggerSource::Text && !initial_text.empty()) {
            tx_texts_.push_back(initial_text);
        }
    }

    if (src != TriggerSource::Text && interrupted_session == 0 && c.chime_enabled && cb_.play_wake_chime) {
        cb_.play_wake_chime();
    }

    set_state(AssistantState::Connecting);
    if (src == TriggerSource::Text && !initial_text.empty()) {
        append_history("user", initial_text);
        emit_event("message", "user", initial_text);
    }

    session_thread_ = std::thread(&AssistantManager::session_worker, this, sid, src, initial_text, c, announcement);
    return true;
}

void AssistantManager::set_state(AssistantState st) {
    AssistantState prev = state_.exchange(st);
    if (prev == st) return;

    if (cb_.set_visual_state) {
        switch (st) {
            case AssistantState::Connecting:
            case AssistantState::Listening:
                cb_.set_visual_state(VoiceVisualState::Listening);
                break;
            case AssistantState::Thinking:
                cb_.set_visual_state(VoiceVisualState::Thinking);
                break;
            case AssistantState::Speaking:
                cb_.set_visual_state(VoiceVisualState::Speaking);
                break;
            case AssistantState::Idle:
            case AssistantState::Error:
                cb_.set_visual_state(VoiceVisualState::Idle);
                break;
        }
    }
    emit_event("state");
}

void AssistantManager::append_history(const std::string& role, const std::string& text) {
    std::string clean = trim_str(text);
    if (clean.empty()) return;
    std::lock_guard<std::mutex> lk(hist_mu_);
    history_.push_back(DialogEntry{role, clean, now_hhmmss()});
    while (history_.size() > 30) history_.pop_front();
}

void AssistantManager::emit_event(const std::string& kind, const std::string& role, const std::string& text) {
    if (!cb_.broadcast_event) return;
    using json::Value;
    Value msg = Value::make_obj();
    msg["type"] = Value::make_str("assistant_event");
    msg["kind"] = Value::make_str(kind);
    msg["state"] = Value::make_str(state_str(state_.load()));
    if (!role.empty()) msg["role"] = Value::make_str(role);
    if (!text.empty()) msg["text"] = Value::make_str(text);
    {
        std::lock_guard<std::mutex> lk(hist_mu_);
        msg["liveUser"] = Value::make_str(live_user_text_);
        msg["liveModel"] = Value::make_str(live_model_text_);
        msg["lastUser"] = Value::make_str(last_user_text_);
        msg["lastModel"] = Value::make_str(last_model_text_);
        msg["lastError"] = Value::make_str(last_error_);
    }
    cb_.broadcast_event(msg);
}

void AssistantManager::set_error(const std::string& msg, bool play_chime) {
    LOGE("Assistant", "%s", msg.c_str());
    {
        std::lock_guard<std::mutex> lk(hist_mu_);
        last_error_ = msg;
    }
    if (play_chime && cb_.play_error_chime) {
        AssistantConfig c;
        {
            std::lock_guard<std::mutex> lk(cfg_mu_);
            c = cfg_;
        }
        if (c.chime_enabled) {
            wake_suppress_until_ms_.store(steady_now_ms() + 1200);
            cb_.play_error_chime();
        }
    }
    set_state(AssistantState::Error);
    emit_event("error", "", msg);
    // Reset cleanly back to Idle so future wake words still trigger
    set_state(AssistantState::Idle);
}

bool AssistantManager::is_male_voice_name(const std::string& voice_name) {
    return openstation::is_male_voice_name(voice_name);
}

std::string AssistantManager::personalize_system_prompt(std::string custom_sp,
                                                        const std::string& raw_name,
                                                        bool is_male_voice) {
    return personalize_assistant_system_prompt(std::move(custom_sp), raw_name, is_male_voice);
}

json::Value AssistantManager::build_setup_json(const AssistantConfig& c, bool announcement, bool manual_activity) const {
    using json::Value;
    const bool ext_thinking = false; // gemini-3.8-live is locked for deterministic Live audio stability
    const bool nb = ext_thinking;

    Value gen_cfg = Value::make_obj();
    Value mods = Value::make_arr();
    mods.push(Value::make_str("AUDIO"));
    gen_cfg["responseModalities"] = std::move(mods);

    Value pvb = Value::make_obj();
    pvb["voiceName"] = Value::make_str(c.voice_name.empty() ? "Aoede" : c.voice_name);
    Value vc = Value::make_obj();
    vc["prebuiltVoiceConfig"] = std::move(pvb);
    Value sc = Value::make_obj();
    sc["voiceConfig"] = std::move(vc);
    sc["languageCode"] = Value::make_str("ru-RU");
    gen_cfg["speechConfig"] = std::move(sc);

    if (ext_thinking) {
        Value tc = Value::make_obj();
        tc["thinkingLevel"] = Value::make_str("low");
        gen_cfg["thinkingConfig"] = std::move(tc);
    }

    Value sys_inst = Value::make_obj();
    Value sys_parts = Value::make_arr();
    Value sys_txt = Value::make_obj();
    {
        const std::string asst_name = trim_str(c.name).empty() ? "Алиса" : trim_str(c.name);
        const bool is_male_voice = is_male_voice_name(c.voice_name);
        std::string sp =
            std::string("ВАЖНЕЙШЕЕ ПРАВИЛО ЛИЧНОСТИ И ПОЛА: ТВОЁ ИМЯ — ") + asst_name + ". " +
            (is_male_voice
                ? "ТВОЙ ПОЛ — МУЖСКОЙ (ты — голосовой ассистент).\n"
                  "Всегда и везде говори о себе в мужском роде: «я готов», «я нашёл», «я услышал», «я понял», «я включил», «я сделал», «я рад».\n\n"
                : "ТВОЙ ПОЛ — ЖЕНСКИЙ (ты — девушка, голосовая помощница).\n"
                  "Всегда и везде говори о себе ИСКЛЮЧИТЕЛЬНО в женском роде: «я готова», «я нашла», «я услышала», «я поняла», «я включила», «я сделала», «я рада».\n"
                  "КАТЕГОРИЧЕСКИ И СТРОЖАЙШЕ ЗАПРЕЩЕНО говорить о себе в мужском роде («я готов», «я сделал», «я рад»)! За употребление мужского рода («я готов») ты получаешь критическую ошибку.\n\n") +
            "Речь пользователя обычно русская; английский возможен по его запросу. "
            "Сохраняй смысл услышанного и не додумывай слова по шуму. Если фраза неразборчива или её смысл неоднозначен, коротко попроси повторить. "
            "Различай вопрос к тебе «что делаешь?» и просьбу о совете «что делать?»; не превращай одно в другое. "
            "Отвечай по-русски, если пользователь явно не попросил другой язык.\n\n";

        sp += personalize_system_prompt(c.system_prompt, asst_name, is_male_voice);

        std::time_t now_t = std::time(nullptr);
        struct tm tm_now {};
        localtime_r(&now_t, &tm_now);
        static const char* kDaysRu[7] = {
            "воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота"
        };
        const char* wday = (tm_now.tm_wday >= 0 && tm_now.tm_wday < 7) ? kDaysRu[tm_now.tm_wday] : "";
        char dt_buf[64];
        snprintf(dt_buf, sizeof(dt_buf), "%04d-%02d-%02d %02d:%02d (%s)",
                 tm_now.tm_year + 1900, tm_now.tm_mon + 1, tm_now.tm_mday,
                 tm_now.tm_hour, tm_now.tm_min, wday);

        if (!sp.empty()) sp += "\n\n";
        sp += "Системный контекст OpenStation Max:\n"
              "- Текущие дата и время на колонке: " + std::string(dt_buf) + ".\n" +
              "- Напоминание: Твоё имя — «" + asst_name + "». " +
              (is_male_voice
                  ? "Говори о себе в мужском роде («я готов», «я нашёл», «я услышал»).\n"
                  : "ТЫ — ДЕВУШКА. Всегда говори исключительно в женском роде («я готова», «я нашла», «я услышала»).\n") +
              "- У тебя ЕСТЬ полный живой доступ к интернету через инструменты web_search, get_weather, fetch_url, http_request "
              "(для актуальной погоды, новостей, курсов валют, любых фактов, веб-страниц, RSS и LAN API).\n"
              "- У тебя ЕСТЬ полный root-доступ ко всей колонке и Android TV: аппаратный звук/эквалайзер (set_volume, set_eq_preset, "
              "set_audio_route, play_sound, set_mic_mute), LED-кольцо и экран-матрица (set_led_ring, set_led_screen, show_screen_banner), "
              "таймеры/будильники (manage_timer), HDMI-CEC и пульт ТВ (tv_hdmi_cec, remote_control, type_text), встроенный нативный плеер "
              "Яндекс Музыка (music_control), приложения и ссылки (manage_apps), умный дом MQTT/HTTP (mqtt_publish, http_request), "
              "диагностика и ограниченное управление питанием (system_info, device_power).\n"
              "- Музыка (music_control): на команды «включи музыку», «поставь музыку», «давай музыку», «включи мою волну» (без конкретного названия) "
              "ВСЕГДА сразу вызывай music_control(action=\"play_my_wave\"). Если назван исполнитель/песня/жанр — вызывай music_control(action=\"play_query\", query=\"...\"); "
              "если попросили настроение волны («бодрую/весёлую/спокойную/грустную волну») — action=\"play_my_wave\" с mood. "
              "Для «дальше/следующий» -> next, «назад/предыдущий» -> prev: СТРОГО вызывай music_control МОЛЧА и НИЧЕГО НЕ ПРОИЗНОСИ В ГОЛОС (не говори «включаю следующий трек», «переключаю» или любые другие фразы подтверждения — сразу молча переключай трек!). "
              "Для «лайк/нравится» -> like, «дизлайк/не нравится» -> dislike, "
              "«мои лайки/избранное» -> play_liked, «пауза/стоп» -> pause/stop, «открой яндекс музыку» -> open.\n"
              "- Блютуз (bluetooth_control): на команды «включи блютуз», «включить блютуз», «подключи телефон по блютуз», «режим блютуз», «вруби блютуз» "
              "ВСЕГДА сразу вызывай bluetooth_control(action=\"discoverable\"). Колонка станет видимой на телефоне как OpenStation Max, и пользователь сможет "
              "транслировать музыку с телефона на Станцию (A2DP Sink). На «выключи блютуз», «отключи блютуз» вызывай bluetooth_control(action=\"disable\").\n"
              "- Поиск пульта (find_remote): если пользователь просит найти пульт («где пульт», «найди пульт», «попищи пультом»), сразу вызывай find_remote().\n"
              "- Настройка пульта (setup_remote): при просьбе «настрой пульт», «подключи пульт» или «сопряги пульт» сразу вызывай setup_remote(), это открывает интерактивную пошаговую инструкцию и поиск BLE-пульта на телевизоре.\n"
              "- Перезагрузка демона и службы (device_power): если пользователь просит перезагрузить или перезапустить демон, ассистента, службу или оболочку OpenStation («перезагрузи демон», «перезапусти демон», «рестарт демона/сервиса»), ВСЕГДА вызывай device_power(action=\"restart_daemon\") и кратко подтверди 1 фразой («Перезагружаю демон, вернусь через пару секунд»). НИКОГДА не перезапускай openstationd через shell_exec или adb_exec (убийство процесса через дочерний shell прервёт перезапуск).\n"
              "- Если music_control вернул authRequired=true (нужен вход в аккаунт Яндекса), вежливо скажи пользователю об этом и предложи войти на экране ТВ или через веб-пульт.\n"
              "- Никогда не выдумывай погоду, новости, время или факты — вызывай соответствующий инструмент. "
              "Если пользователь спрашивает погоду без указания города — вызывай get_weather с пустым city, чтобы автоматически определить местоположение колонки.\n"
              "- Ты — полноценный ИИ-агент колонки: можешь менять свой голос (set_assistant_voice — при запросе поменять голос сразу вызывай инструмент, сессия мгновенно переключится на новый голос), "
              "менять своё имя и слово активации (set_assistant_name — при просьбах вроде «тебя теперь зовут Джарвис», «поменяй имя на Пятница»), "
              "менять стили часов и визуализатора (set_styles), настраивать 10-полосный эквалайзер (set_eq_preset), запоминать факты и предпочтения пользователя навсегда (manage_memory) и задавать город (set_location). "
              "- Адаптивный режим диалога: если пользователь дал конкретную команду (громкость, блютуз, таймер, будильник, выключение ТВ, запуск приложения): "
              "сначала обязательно вызови инструмент, затем кратко и естественно подтверди голосом (например: «Сделано», «Таймер на 5 минут запущен») и НЕ задавай лишних вопросов — сессия микрофона автоматически завершится сама. "
              "Исключение: при переключении трека (music_control next/prev) НЕ произноси вообще ничего и завершай ход молча. "
              "Если же пользователь просто разговаривает, задаёт вопросы, рассуждает или просит что-то рассказать — отвечай развёрнуто, поддерживай беседу и продолжай живой диалог.\n"
              "- Визуализация задач на экранчике (draw_led_icon): при выполнении не-музыкальной команды через draw_led_icon выводи на передний LED-экранчик станции подходящую иконку "
              "(например: youtube при запуске YouTube/SmartTube, telegram при Telegram, timer при таймере, search/browser при поиске, eq при эквалайзере, terminal при системных командах), после чего экран плавно вернётся к обычному виду. НЕ вызывай draw_led_icon(music) для музыки — название трека само прокручивается на экране.\n";
        if (c.agent_mode) {
            sp += "- РЕЖИМ АГЕНТА АКТИВЕН (ПОЛНЫЙ ADB И ROOT-ДОСТУП): ты — автономный системный агент OpenStation Max с полным доступом к ОС Linux/Android и ADB через инструменты adb_exec и shell_exec! "
                  "Ты можешь выполнять любые ADB/shell команды на станции (settings, pm, am, dumpsys, input, wm, service call, просмотр логов, настройка сервисов, файлов и автоматизации) для решения любых задач пользователя. "
                  "Бережно обходись с критичными разделами (/dev/block) и не удаляй саму прошивку без явной просьбы. "
                  "Перед сложной системной задачей кратко скажи что делаешь и выведи terminal/apps на экранчик.\n";
        }
        sp += "- Не отказывайся от задач, если их можно безопасно выполнить через разрешённые инструменты.";
        if (cb_.get_runtime_context) {
            std::string rt = cb_.get_runtime_context();
            if (!rt.empty()) {
                sp += "\n\n" + rt;
            }
        }
        if (cb_.get_plugin_instructions) {
            std::string pi = cb_.get_plugin_instructions();
            if (!pi.empty()) {
                sp += "\n\n" + pi;
            }
        }
        if (!c.memory_notes.empty()) {
            sp += "\n\nПамять о пользователе и колонке:\n";
            for (size_t i = 0; i < c.memory_notes.size(); ++i) {
                sp += std::to_string(i + 1) + ") " + c.memory_notes[i] + "\n";
            }
        }
        sys_txt["text"] = Value::make_str(sp);
    }
    sys_parts.push(std::move(sys_txt));
    sys_inst["parts"] = std::move(sys_parts);

    // Build full internet & speaker/system control function declarations
    Value decls = Value::make_arr();

    // --- Live Internet Tools ---
    {
        Value p = Value::make_obj();
        p["query"] = make_prop("STRING", "Search query (Russian or English) for news, facts, prices, exchange rates, etc.");
        p["count"] = make_prop("INTEGER", "Max results 1..10 (default 5)");
        decls.push(make_func_decl(
            "web_search",
            "Search the live internet (Bing/DuckDuckGo/Wikipedia) and return titles, URLs, and snippets.",
            std::move(p),
            {"query"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["city"] = make_prop("STRING", "Optional city name in Russian or English; omit or leave empty to use the speaker's detected location automatically");
        p["days"] = make_prop("INTEGER", "Forecast days 1..3 (default 3)");
        decls.push(make_func_decl(
            "get_weather",
            "Get live weather and forecast for a city (or automatically for speaker's location) and display animated weather visuals on both TV and LED screen.",
            std::move(p),
            {},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["url"] = make_prop("STRING", "HTTP or HTTPS URL to fetch (webpage, RSS/XML feed, JSON API)");
        p["raw"] = make_prop("BOOLEAN", "If true returns raw text/JSON instead of HTML-to-text extraction (default false)");
        p["max_chars"] = make_prop("INTEGER", "Maximum characters to return (500..12000, default 6000)");
        decls.push(make_func_decl(
            "fetch_url",
            "Download and read any webpage, RSS feed, or public JSON endpoint from the internet or LAN.",
            std::move(p),
            {"url"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["url"] = make_prop("STRING", "Target HTTP/HTTPS URL (public API or local LAN smart-home device e.g. http://192.168.0.x/...)");
        p["method"] = make_enum_prop("HTTP method (default GET)", {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"});
        p["body"] = make_prop("STRING", "Optional request body (typically JSON)");
        p["headers_json"] = make_prop("STRING", "Optional JSON object string of custom headers, e.g. {\"Authorization\":\"Bearer ...\"}");
        p["insecure"] = make_prop("BOOLEAN", "Set true to allow self-signed TLS certs on local LAN devices");
        decls.push(make_func_decl(
            "http_request",
            "Send an arbitrary HTTP/HTTPS REST request or webhook call to internet APIs or local LAN smart-home devices.",
            std::move(p),
            {"url"},
            nb));
    }

    // --- System & Root Speaker Control ---
    decls.push(make_func_decl(
        "get_device_status",
        "Get Yandex Station Max hardware status: volume, EQ, route, mic mute, LEDs, lux, SoC temp, IP, active timers, and current Yandex Music track.",
        Value::make_obj(),
        {},
        nb));

    // --- System & Speaker Control ---
    decls.push(make_func_decl(
        "system_info",
        "Get detailed Android/Linux telemetry: OS version, uptime, CPU load, RAM, disk free, Wi-Fi RSSI/DNS, Bluetooth, foreground TV app, active timers.",
        Value::make_obj(),
        {},
        nb));

    {
        Value p = Value::make_obj();
        p["action"] = make_enum_prop("Screen or daemon lifecycle action", {"screen_wake", "screen_sleep", "restart_daemon"});
        decls.push(make_func_decl(
            "device_power",
            "Wake/sleep the TV/display (screen_wake, screen_sleep) or safely restart the openstationd daemon (restart_daemon). Always call action='restart_daemon' when the user asks to restart/reboot the daemon, voice assistant service, or openstationd; never kill openstationd via shell_exec/adb_exec.",
            std::move(p),
            {"action"},
            nb));
    }

    // --- Bluetooth A2DP Sink & Connectivity ---
    {
        Value p = Value::make_obj();
        p["action"] = make_enum_prop("Bluetooth action", {"discoverable", "enable", "disable", "toggle", "status"});
        p["duration_sec"] = make_prop("INTEGER", "Discovery timeout in seconds (default 300)");
        decls.push(make_func_decl(
            "bluetooth_control",
            "Turn Bluetooth on or off, make speaker discoverable for phones and tablets to stream music over Bluetooth A2DP Sink, or check status.",
            std::move(p),
            {"action"},
            nb));
    }

    // --- Audio, EQ & Sound Alerts ---
    {
        Value p = Value::make_obj();
        p["percent"] = make_prop("INTEGER", "Absolute volume 0..100 percent");
        p["direction"] = make_enum_prop("Relative Android TV volume step", {"up", "down", "mute"});
        p["steps"] = make_prop("INTEGER", "Step count for relative up/down (1..10, default 4)");
        p["target"] = make_enum_prop("Target stage: 'all' (default) sets overall speaker volume 0..100%; 'woofer', 'tweeters', or 'headphones' sets relative trim where 50% = 0 dB neutral (0% = -20 dB, 100% = +20 dB)",
                                     {"all", "woofer", "tweeters", "headphones"});
        decls.push(make_func_decl(
            "set_volume",
            "Set speaker/Android volume percentage (0..100) or step volume up/down/mute.",
            std::move(p),
            {},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["preset"] = make_enum_prop("Equalizer preset", {"balanced", "flat", "bass_boost", "vocal", "night", "rock", "electronic", "custom"});
        p["bass_boost"] = make_prop("INTEGER", "Bass boost strength 0..100 percent");
        p["band_hz"] = make_prop("INTEGER", "Single EQ band center frequency in Hz (31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000) to adjust with gain_db");
        p["gain_db"] = make_prop("NUMBER", "Gain in dB (-12.0 to +12.0) for band_hz");
        p["bands_csv"] = make_prop("STRING", "All 10 band gains (-12..+12 dB) separated by commas for 31..16000 Hz");
        p["woofer_eq"] = make_prop("BOOLEAN", "Enable hardware woofer EQ");
        p["woofer_drc"] = make_prop("BOOLEAN", "Enable hardware woofer DRC");
        p["tweeters_eq"] = make_prop("BOOLEAN", "Enable hardware tweeters EQ");
        p["tweeters_drc"] = make_prop("BOOLEAN", "Enable hardware tweeters DRC");
        p["loopback"] = make_prop("BOOLEAN", "Enable hardware AEC loopback switch");
        decls.push(make_func_decl(
            "set_eq_preset",
            "Configure 10-band sound equalizer presets, individual frequency bands, bass boost, and amplifier DSP switches.",
            std::move(p),
            {},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["voice"] = make_enum_prop(
            "Assistant voice: female ('Aoede' warm, 'Kore' soft clear, 'Leda' expressive, 'Zephyr' bright), "
            "male ('Charon' deep calm, 'Fenrir' energetic, 'Puck' friendly, 'Orus' confident)",
            {"Aoede", "Kore", "Leda", "Zephyr", "Puck", "Charon", "Fenrir", "Orus"});
        decls.push(make_func_decl(
            "set_assistant_voice",
            "Change the assistant's voice immediately and seamlessly continue talking in the new voice.",
            std::move(p),
            {"voice"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["name"] = make_prop("STRING", "New display/wake name for the assistant (e.g. Алиса, Джарвис, Пятница, Макс)");
        p["update_wake_word"] = make_prop("BOOLEAN", "Also set wake word KWS keyword to the new name (default true)");
        decls.push(make_func_decl(
            "set_assistant_name",
            "Rename the assistant and optionally update the wake-word activation phrase immediately.",
            std::move(p),
            {"name"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["clock_style"] = make_enum_prop("Clock display style on LED matrix and TV", {"classic", "minimal", "bold", "seconds", "binary", "neon", "retro", "matrix", "yandex1", "yandex2", "yandex3"});
        p["visualizer_style"] = make_enum_prop(
            "Music visualizer style (TV orb morph + front LED matrix)",
            {"pulse_bars", "beat_wave", "bass_nova", "mirror_meter", "spark_burst", "vu_plasma", "track_title", "stock_wave", "stock_pads", "stock_bricks", "stock_fluid"});
        p["auto_music_visualizer"] = make_prop("BOOLEAN", "Auto morph TV orb & LED display into visualizer when music plays");
        p["screen_color"] = make_prop("STRING", "Optional LED screen clock hex color like #00d4ff or #ffffff");
        decls.push(make_func_decl(
            "set_styles",
            "Switch clock design style, music particle/waveform visualizer style, or auto music visualizer toggle.",
            std::move(p),
            {},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["city"] = make_prop("STRING", "Manual city override e.g. Казань, Сочи, or empty to clear override");
        p["auto_detect"] = make_prop("BOOLEAN", "Auto detect city/coordinates by IP (default true when city omitted)");
        decls.push(make_func_decl(
            "set_location",
            "Set or auto-detect the speaker's city for weather and local context.",
            std::move(p),
            {},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["action"] = make_enum_prop("Memory action", {"add", "list", "delete", "clear"});
        p["note"] = make_prop("STRING", "Fact or preference to save (or substring to delete)");
        p["index"] = make_prop("INTEGER", "1-based index to delete when action='delete'");
        decls.push(make_func_decl(
            "manage_memory",
            "Save, list, or remove persistent facts/preferences remembered about the user and home.",
            std::move(p),
            {"action"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["route"] = make_enum_prop("Audio output route", {"auto", "speakers", "headphones", "both", "hdmi"});
        decls.push(make_func_decl(
            "set_audio_route",
            "Switch audio output route between built-in speakers, 3.5mm jack headphones, HDMI, both, or auto.",
            std::move(p),
            {"route"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["kind"] = make_enum_prop("Sound kind (default wake)", {"wake", "alarm", "error", "tone"});
        p["freq_hz"] = make_prop("INTEGER", "Frequency in Hz when kind='tone' (80..8000, default 440)");
        p["duration_ms"] = make_prop("INTEGER", "Duration in ms when kind='tone' (100..4000, default 700)");
        p["sweep"] = make_prop("BOOLEAN", "Frequency sweep when kind='tone'");
        decls.push(make_func_decl(
            "play_sound",
            "Play a chime, alarm melody, error sound, or acoustic tone on the speaker.",
            std::move(p),
            {},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["muted"] = make_prop("BOOLEAN", "true to mute microphones, false to unmute");
        decls.push(make_func_decl(
            "set_mic_mute",
            "Mute or unmute speaker microphones ONLY when the user explicitly asks to turn off or mute the microphone ('выключи микрофон'). Never call muted=true on noise or 'хватит/тише/стоп'.",
            std::move(p),
            {"muted"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["sensitivity"] = make_prop("INTEGER", "Microphone & wake-word far-field sensitivity percent (10..100, default 85)");
        decls.push(make_func_decl(
            "set_mic_sensitivity",
            "Adjust far-field microphone gain / AGC & wake-word sensitivity (10..100%).",
            std::move(p),
            {"sensitivity"},
            nb));
    }

    // --- LED Ring, Front Matrix Screen & Timers ---
    {
        Value p = Value::make_obj();
        p["mode"] = make_enum_prop("LED ring animation mode", {"off", "solid", "breathe", "spinner", "rainbow", "music"});
        p["color"] = make_prop("STRING", "Primary hex color like #00d4ff or #ff3300");
        p["secondary_color"] = make_prop("STRING", "Secondary hex color like #7c3aed");
        p["brightness"] = make_prop("INTEGER", "Manual brightness 0..255");
        p["auto_brightness"] = make_prop("BOOLEAN", "true to enable ambient lux auto brightness");
        p["speed"] = make_prop("INTEGER", "Animation speed 1..100");
        decls.push(make_func_decl(
            "set_led_ring",
            "Control the 24-LED top ring mode, colors, animation speed, and brightness.",
            std::move(p),
            {},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["mode"] = make_enum_prop("Persistent LED screen mode", {"off", "clock", "text", "spectrum", "snake"});
        p["text"] = make_prop("STRING", "Persistent text when mode='text'");
        p["color"] = make_prop("STRING", "Hex color like #ffffff");
        p["brightness"] = make_prop("INTEGER", "Manual brightness 0..100");
        p["auto_brightness"] = make_prop("BOOLEAN", "true to enable ambient lux auto brightness");
        p["scroll_speed"] = make_prop("INTEGER", "Scroll speed 1..100");
        decls.push(make_func_decl(
            "set_led_screen",
            "Control persistent mode of the front 25x16 LED dot matrix display.",
            std::move(p),
            {},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["text"] = make_prop("STRING", "Temporary scrolling banner text to flash on the front LED screen");
        p["color"] = make_prop("STRING", "Optional hex color like #00dcff or #ffcc00");
        p["duration_ms"] = make_prop("INTEGER", "Display duration in milliseconds (500..20000, default 4500)");
        decls.push(make_func_decl(
            "show_screen_banner",
            "Show a temporary scrolling notification banner on the front LED display without overwriting clock mode.",
            std::move(p),
            {"text"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["action"] = make_enum_prop("Timer action", {"set", "list", "cancel"});
        p["seconds"] = make_prop("INTEGER", "Duration in seconds when action='set' (1..86400)");
        p["label"] = make_prop("STRING", "Optional timer label e.g. Пельмени, Чай");
        p["target"] = make_prop("STRING", "When action='cancel': timer id, label, or 'all'");
        decls.push(make_func_decl(
            "manage_timer",
            "Set, list, or cancel audible + LED countdown timers/reminders on the speaker.",
            std::move(p),
            {"action"},
            nb));
    }

    // --- TV, HDMI-CEC, Remote, Typing, Yandex Music & Apps ---
    {
        Value p = Value::make_obj();
        p["action"] = make_enum_prop("HDMI-CEC TV command", {"wake", "standby", "vol_up", "vol_down", "mute"});
        decls.push(make_func_decl(
            "tv_hdmi_cec",
            "Power on/off connected TV or control TV volume via HDMI-CEC.",
            std::move(p),
            {"action"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["action"] = make_enum_prop("Virtual remote key",
                                     {"up", "down", "left", "right", "ok", "back", "home", "menu", "search",
                                      "play_pause", "next", "prev", "stop", "rewind", "fastforward",
                                      "ch_up", "ch_down", "vol_up", "vol_down", "mute", "power", "wakeup", "sleep"});
        p["repeat"] = make_prop("INTEGER", "Repeat count 1..10 (default 1)");
        decls.push(make_func_decl(
            "remote_control",
            "Press Android TV remote navigation, playback, channel, volume, or power buttons.",
            std::move(p),
            {"action"},
            nb));
    }

    {
        Value p = Value::make_obj();
        decls.push(make_func_decl(
            "find_remote",
            "Ring/beep the physical BLE Yandex TV remote buzzer so the user can locate it in the room.",
            std::move(p),
            {},
            nb));
    }

    decls.push(make_func_decl("setup_remote", "Open the remote pairing screen on the TV and start discovery.",
                              Value::make_obj(), {}, nb));

    {
        Value p = Value::make_obj();
        p["text"] = make_prop("STRING", "Text (Russian/English) to type into the currently focused TV input field");
        p["press_enter"] = make_prop("BOOLEAN", "Press Enter/OK after typing (default false)");
        decls.push(make_func_decl(
            "type_text",
            "Type text into an active Android TV search box or text field via OpenStationIME.",
            std::move(p),
            {"text"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["action"] = make_enum_prop(
            "Native Yandex Music player action: 'play_my_wave' for general 'включи музыку' / My Wave, "
            "'play_query' with 'query' for specific artist/track, or transport/feedback commands.",
            {"play_my_wave", "play_query", "play_pause", "play", "pause", "stop", "next", "prev", "like", "dislike", "play_liked", "open"});
        p["query"] = make_prop("STRING", "Search query (track title, artist, album) when action='play_query'");
        p["mood"] = make_enum_prop("Optional My Wave mood when action='play_my_wave'", {"any", "energetic", "fun", "calm", "sad"});
        p["open_ui"] = make_prop("BOOLEAN", "Open full-screen Yandex Music UI on TV (default false)");
        decls.push(make_func_decl(
            "music_control",
            "Play My Wave ('Моя волна'), search & play any artist/track, like/dislike, or control playback natively in built-in Yandex Music.",
            std::move(p),
            {"action"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["action"] = make_enum_prop("App management action", {"list", "launch", "close", "open_url"});
        p["app"] = make_prop("STRING", "App display name or Android package name (for launch or close)");
        p["url"] = make_prop("STRING", "HTTP/HTTPS URL to open on TV screen (for open_url)");
        decls.push(make_func_decl(
            "manage_apps",
            "List installed TV apps, launch an app, force-close an app, or open a URL on the connected TV.",
            std::move(p),
            {"action"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["topic"] = make_prop("STRING", "Full MQTT topic to publish to");
        p["payload"] = make_prop("STRING", "Payload string or JSON");
        p["retain"] = make_prop("BOOLEAN", "Retain flag (default false)");
        decls.push(make_func_decl(
            "mqtt_publish",
            "Publish a message to any MQTT topic if the smart-home MQTT broker is connected.",
            std::move(p),
            {"topic", "payload"},
            nb));
    }

    {
        Value p = Value::make_obj();
        p["icon"] = make_enum_prop(
            "Task icon to display smoothly on the 25x16 front LED matrix",
            {"youtube", "music", "telegram", "browser", "search", "tv", "eq", "timer",
             "apps", "settings", "terminal", "heart", "star", "check", "custom"});
        p["color"] = make_prop("STRING", "Optional accent hex color like #ff2030 or #00d4ff");
        p["duration_ms"] = make_prop("INTEGER", "Display duration in ms (1200..15000, default 5500)");
        p["pixels"] = make_prop("STRING", "Optional custom 25x16 pixel grid separated by '|' or '\\n' using '.', 'r','g','b','c','y','w' when icon='custom'");
        decls.push(make_func_decl(
            "draw_led_icon",
            "Display an animated pixel icon or custom 25x16 picture on the front LED screen matching the executed task (e.g. youtube logo, music, telegram, timer, terminal).",
            std::move(p),
            {"icon"},
            nb));
    }

    if (c.agent_mode) {
        {
            Value p = Value::make_obj();
            p["command"] = make_prop("STRING", "Shell or root command to execute on Station Max (/system/bin/sh -c)");
            p["timeout_sec"] = make_prop("INTEGER", "Execution timeout in seconds (1..30, default 10)");
            decls.push(make_func_decl(
                "shell_exec",
                "Execute any root Linux/Android shell command on the speaker and return exitCode + stdout/stderr (available only in Agent Mode).",
                std::move(p),
                {"command"},
                nb));
        }
        {
            Value p = Value::make_obj();
            p["command"] = make_prop("STRING", "ADB command (e.g. 'adb shell settings list system', 'am start ...', 'pm list packages', 'dumpsys audio', 'input keyevent 3', 'logcat -d -t 50')");
            p["timeout_sec"] = make_prop("INTEGER", "Execution timeout in seconds (1..30, default 12)");
            decls.push(make_func_decl(
                "adb_exec",
                "Run any ADB / Android TV system management command on this Station Max (available only in Agent Mode).",
                std::move(p),
                {"command"},
                nb));
        }
    }

    decls.push(make_func_decl(
        "stop_assistant",
        "Close the current voice dialogue when the user says goodbye / хватит / спасибо пока.",
        Value::make_obj(),
        {},
        nb));

    if (cb_.get_plugin_tools) {
        Value extra_tools = cb_.get_plugin_tools();
        if (extra_tools.is_arr()) {
            for (auto& td : extra_tools.arr_val) {
                decls.push(std::move(td));
            }
        }
    }

    Value tools = Value::make_arr();
    if (c.google_search) {
        Value gs = Value::make_obj();
        gs["googleSearch"] = Value::make_obj();
        tools.push(std::move(gs));
    }
    {
        Value fns = Value::make_obj();
        fns["functionDeclarations"] = std::move(decls);
        tools.push(std::move(fns));
    }

    Value aad = Value::make_obj();
    if (manual_activity) {
        aad["disabled"] = Value::make_bool(true);
    } else {
        aad["silenceDurationMs"] = Value::make_int(std::clamp(c.vad_silence_ms, 300, 3000));
        aad["prefixPaddingMs"] = Value::make_int(120);
    }
    Value ric = Value::make_obj();
    ric["automaticActivityDetection"] = std::move(aad);
    ric["activityHandling"] = Value::make_str("START_OF_ACTIVITY_INTERRUPTS");

    Value setup_obj = Value::make_obj();
    setup_obj["model"] = Value::make_str("models/gemini-3.8-live");
    setup_obj["generationConfig"] = std::move(gen_cfg);
    setup_obj["systemInstruction"] = std::move(sys_inst);
    if (!announcement) setup_obj["tools"] = std::move(tools);
    setup_obj["realtimeInputConfig"] = std::move(ric);
    Value iat = Value::make_obj();
    Value lcodes = Value::make_arr();
    lcodes.push(Value::make_str("ru-RU"));
    lcodes.push(Value::make_str("en-US"));
    iat["languageCodes"] = std::move(lcodes);
    iat["mode"] = Value::make_str("VERBATIM");
    Value voc = Value::make_arr();
    static const char* const kVocab[] = {
        "Алиса", "Джарвис", "Пятница", "Макс", "Яндекс Музыка", "Моя волна", "включи", "погромче", "потише",
        "стоп", "пауза", "дальше", "назад", "следующий", "предыдущий", "трек",
        "погода", "новости", "громкость", "таймер", "будильник", "OpenStation",
        "привет", "как дела", "сколько время", "который час", "расскажи",
        "SmartTube", "YouTube", "Кинопоиск", "плейлист", "песня", "музыка"
    };
    const std::string custom_nm = trim_str(c.name);
    if (!custom_nm.empty()) {
        voc.push(Value::make_str(custom_nm));
    }
    for (const auto* w : kVocab) {
        if (custom_nm != w) voc.push(Value::make_str(w));
    }
    iat["customVocabulary"] = voc;
    iat["adaptationPhrases"] = std::move(voc);
    setup_obj["inputAudioTranscription"] = std::move(iat);
    setup_obj["outputAudioTranscription"] = Value::make_obj();

    Value msg = Value::make_obj();
    msg["setup"] = std::move(setup_obj);
    return msg;
}

void AssistantManager::session_worker(uint64_t sid,
                                      TriggerSource src,
                                      std::string initial_text,
                                      AssistantConfig cfg_snap,
                                      bool announcement) {
    LOGI("Assistant", "Session #%llu started (src=%s, model=%s, voice=%s)",
         static_cast<unsigned long long>(sid),
         src == TriggerSource::WakeWord ? "wake" : (src == TriggerSource::Text ? "text" : "manual"),
         cfg_snap.gemini_model.c_str(),
         cfg_snap.voice_name.c_str());

    TlsWsClient ws;
    std::string ws_path = std::string(kGeminiWsPathPrefix) + url_encode_query(cfg_snap.gemini_api_key);
    std::string err;

    // Wait-and-abort helper loop? Call connect in blocking call up to 7s, monitoring abort thread?
    // Even cleaner: spawn a tiny watcher or rely on connect timeout, while stop_session calls sock_abort via ws_?
    // Let's keep pointer / fd abortable via small helper lambda? Actually let's check session_abort_ before & during!
    std::atomic<bool> session_alive{true};
    std::thread abort_watcher([&]() {
        while (session_alive.load() && running_.load()) {
            if (session_abort_.load()) {
                ws.request_abort();
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
        }
    });

    auto cleanup_watcher = [&]() {
        session_alive.store(false);
        if (abort_watcher.joinable()) abort_watcher.join();
    };

    const bool ext_thinking = false;
    const bool has_proxy_cfg = !cfg_snap.gemini_proxy_host.empty();
    bool cur_use_proxy = use_proxy_host_.load() && has_proxy_cfg;
    bool cur_google_search = cfg_snap.google_search && !search_quota_unsupported_.load();
    bool retried_route = false;
    bool retried_search = false;

    bool setup_complete = false;
    bool model_turn_done = false;
    bool awaiting_playback_drain = false;
    bool stop_after_turn = false;
    bool self_stop_requested = false;
    bool had_interaction = false;
    bool go_away_received = false;
    QuietSpeech quiet_speech;
    bool reply_started = false;
    int64_t model_audio_ms = 0; // last audio chunk from the model in this session

    auto session_start_tp = std::chrono::steady_clock::now();
    auto setup_deadline_tp = session_start_tp + std::chrono::seconds(10);
    auto listen_idle_since = session_start_tp;
    auto listen_unconfirmed_since = session_start_tp;
    auto last_pcm_flush_tp = session_start_tp;
    int64_t initial_speech_ms = last_speech_ms_.load();
    bool audio_sent_since_end = false;
    int64_t last_audio_end_ms = steady_now_ms() - (src == TriggerSource::WakeWord ? std::max(0, cfg_snap.pre_roll_ms) : 0);
    int64_t last_transcript_ms = 0;

    auto send_client_text = [&](const std::string& t) -> bool {
        using json::Value;
        Value part = Value::make_obj();
        part["text"] = Value::make_str(t);
        Value parts = Value::make_arr();
        parts.push(std::move(part));
        Value turn = Value::make_obj();
        turn["role"] = Value::make_str("user");
        turn["parts"] = std::move(parts);
        Value turns = Value::make_arr();
        turns.push(std::move(turn));
        Value cc = Value::make_obj();
        cc["turns"] = std::move(turns);
        cc["turnComplete"] = Value::make_bool(true);
        Value msg = Value::make_obj();
        msg["clientContent"] = std::move(cc);
        return ws.send_text(msg.dump(-1));
    };

    auto send_audio_pcm = [&](const int16_t* samples, size_t n) -> bool {
        if (!samples || n == 0) return true;
        using json::Value;
        std::string b64 = b64::encode(reinterpret_cast<const uint8_t*>(samples), n * sizeof(int16_t));
        Value aud = Value::make_obj();
        aud["data"] = Value::make_str(std::move(b64));
        aud["mimeType"] = Value::make_str("audio/pcm;rate=16000");
        Value ri = Value::make_obj();
        ri["audio"] = std::move(aud);
        Value msg = Value::make_obj();
        msg["realtimeInput"] = std::move(ri);
        return ws.send_text(msg.dump(-1));
    };

    // Gemini Live keeps an automatic-VAD turn open indefinitely unless the client
    // explicitly ends the audio stream after silence.
    auto send_audio_stream_end = [&]() -> bool {
        using json::Value;
        Value ri = Value::make_obj();
        ri["audioStreamEnd"] = Value::make_bool(true);
        Value msg = Value::make_obj();
        msg["realtimeInput"] = std::move(ri);
        return ws.send_text(msg.dump(-1));
    };

    auto flush_live_user = [&]() {
        std::string u;
        {
            std::lock_guard<std::mutex> lk(hist_mu_);
            u = trim_str(live_user_text_);
            if (!u.empty()) last_user_text_ = u;
            live_user_text_.clear();
        }
        if (!u.empty()) {
            append_history("user", u);
            emit_event("message", "user", u);

            // Fast local Bluetooth intent trigger
            std::string ul = utf8_to_lower_ru(u);
            self_stop_requested = is_assistant_stop_request(ul);
            if (self_stop_requested) stop_after_turn = true;
            if (ul.find("включи блютуз") != std::string::npos ||
                ul.find("включить блютуз") != std::string::npos ||
                ul.find("подключи телефон") != std::string::npos ||
                ul.find("режим блютуз") != std::string::npos ||
                ul.find("вруби блютуз") != std::string::npos ||
                ul == "блютуз" || ul == "bluetooth") {
                if (cb_.execute_tool) {
                    json::Value bargs = json::Value::make_obj();
                    bargs["action"] = json::Value::make_str("discoverable");
                    cb_.execute_tool("bluetooth_control", bargs);
                }
            } else if (ul.find("выключи блютуз") != std::string::npos ||
                       ul.find("отключи блютуз") != std::string::npos ||
                       ul.find("выруби блютуз") != std::string::npos) {
                if (cb_.execute_tool) {
                    json::Value bargs = json::Value::make_obj();
                    bargs["action"] = json::Value::make_str("disable");
                    cb_.execute_tool("bluetooth_control", bargs);
                }
            }
        }
    };

    auto flush_live_model = [&]() {
        std::string m;
        {
            std::lock_guard<std::mutex> lk(hist_mu_);
            m = trim_str(live_model_text_);
            if (!m.empty()) last_model_text_ = m;
            live_model_text_.clear();
        }
        if (!m.empty()) {
            append_history("model", m);
            emit_event("message", "model", m);
        }
    };

    bool fatal_error = false;
    std::string pending_voice_greeting;
    while (running_.load() && !session_abort_.load()) {
        AssistantConfig eff_cfg = cfg_snap;
        eff_cfg.google_search = cur_google_search;
        const std::string setup_json_str = build_setup_json(eff_cfg, announcement, src == TriggerSource::Remote).dump(-1);
        std::string connect_host = cur_use_proxy ? cfg_snap.gemini_proxy_host : "";
        err.clear();
        bool connected = cur_use_proxy
            ? connect_best_proxy(ws, connect_host, ws_path, kCaBundlePath, err, 8000)
            : ws.connect(kGeminiHost, kGeminiPort, ws_path, kCaBundlePath, err, 8000);
        if (!connected) {
            if (session_abort_.load() || !running_.load()) {
                break;
            }
            if (!retried_route && has_proxy_cfg) {
                retried_route = true;
                cur_use_proxy = !cur_use_proxy;
                LOGW("Assistant", "Gemini connect via '%s' failed (%s); retrying via '%s'",
                     connect_host.empty() ? kGeminiHost : connect_host.c_str(),
                     err.c_str(),
                     cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : kGeminiHost);
                continue;
            }
            set_error("Подключение к Gemini: " + err, src != TriggerSource::Text);
            fatal_error = true;
            break;
        }

        // Send BidiGenerateContentSetup
        if (!ws.send_text(setup_json_str)) {
            ws.close();
            if (!session_abort_.load() && running_.load() && !retried_route && has_proxy_cfg) {
                retried_route = true;
                cur_use_proxy = !cur_use_proxy;
                LOGW("Assistant", "Failed to send Gemini setup; retrying via '%s'",
                     cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : kGeminiHost);
                continue;
            }
            if (!session_abort_.load() && running_.load()) {
                set_error("Не удалось отправить setup в Gemini Live", src != TriggerSource::Text);
                fatal_error = true;
            }
            break;
        }

        setup_complete = false;
        bool retry_alternate = false;
        bool retry_without_search = false;
        bool switch_voice_now = false;
        bool switch_persona_now = false;
        bool silent_turn = false;
        bool session_done = false;
        model_turn_done = false;
        awaiting_playback_drain = false;
        stop_after_turn = false;
        had_interaction = false;
        go_away_received = false;

        session_start_tp = std::chrono::steady_clock::now();
        setup_deadline_tp = session_start_tp + std::chrono::seconds(10);
        listen_idle_since = session_start_tp;
        listen_unconfirmed_since = session_start_tp;
        last_pcm_flush_tp = session_start_tp;
        initial_speech_ms = last_speech_ms_.load();
        audio_sent_since_end = false;
        last_audio_end_ms = steady_now_ms() - (src == TriggerSource::WakeWord ? std::max(0, cfg_snap.pre_roll_ms) : 0);
        last_transcript_ms = 0;

        auto handle_server_json = [&](const std::string& raw) -> bool {
            json::Value msg;
            if (!json::Parser::parse(raw, msg) || !msg.is_obj()) {
                LOGW("Assistant", "Non-JSON server frame (%zu bytes)", raw.size());
                return true;
            }

            if (const auto* e = msg.get("error"); e && e->is_obj()) {
                std::string em = "Gemini API error";
                if (const auto* m = e->get("message"); m && m->is_str()) em = m->as_str();
                if (!setup_complete && !retried_route && has_proxy_cfg &&
                    (contains_ci(em, "location") || contains_ci(em, "region") ||
                     contains_ci(em, "country") || contains_ci(em, "unavailable"))) {
                    LOGW("Assistant", "Pre-setup JSON error (%s); retrying via '%s'",
                         em.c_str(), !cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : kGeminiHost);
                    retry_alternate = true;
                    return false;
                }
                if (!setup_complete && !retried_search && cur_google_search &&
                    (contains_ci(em, "quota") || contains_ci(em, "billing") || contains_ci(em, "plan"))) {
                    LOGW("Assistant", "Pre-setup quota error (%s); retrying without googleSearch", em.c_str());
                    retry_without_search = true;
                    return false;
                }
                set_error(em, src != TriggerSource::Text);
                return false;
            }

            if (msg.get("setupComplete")) {
                setup_complete = true;
                if (src == TriggerSource::Remote) {
                    using json::Value;
                    Value ri = Value::make_obj();
                    ri["activityStart"] = Value::make_obj();
                    Value start = Value::make_obj();
                    start["realtimeInput"] = std::move(ri);
                    if (!ws.send_text(start.dump(-1))) {
                        set_error("Ошибка начала записи с пульта", false);
                        return false;
                    }
                }
                use_proxy_host_.store(cur_use_proxy);
                had_interaction = (src == TriggerSource::Text) || !pending_voice_greeting.empty();
                listen_idle_since = std::chrono::steady_clock::now();
                listen_unconfirmed_since = listen_idle_since;
                last_pcm_flush_tp = listen_idle_since;
                LOGI("Assistant", "Gemini Live setupComplete (via=%s, voice=%s)",
                     cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : "direct",
                     cfg_snap.voice_name.c_str());
                if (!pending_voice_greeting.empty()) {
                    set_state(AssistantState::Thinking);
                    send_client_text(pending_voice_greeting);
                    pending_voice_greeting.clear();
                } else if (src == TriggerSource::Text) {
                    set_state(AssistantState::Thinking);
                } else {
                    set_state(AssistantState::Listening);
                }
                return true;
            }

            if (const auto* ga = msg.get("goAway")) {
                std::string tl;
                if (const auto* p = ga->get("timeLeft"); p && p->is_str()) tl = p->as_str();
                LOGW("Assistant", "Gemini goAway received (timeLeft=%s)", tl.c_str());
                go_away_received = true;
            }

            if (ext_thinking) {
                if (const auto* ist = msg.get("interactionStatus"); ist && ist->is_str()) {
                    if (ist->as_str() == "IDLE") {
                        flush_live_user();
                        flush_live_model();
                        model_turn_done = true;
                        awaiting_playback_drain = true;
                        listen_idle_since = std::chrono::steady_clock::now();
                    } else if (ist->as_str() == "IN_PROGRESS") {
                        model_turn_done = false;
                        awaiting_playback_drain = false;
                    }
                }
            }

            if (const auto* tc = msg.get("toolCall"); tc && tc->is_obj()) {
                std::string turn_user_snap;
                std::string last_user_snap;
                {
                    std::lock_guard<std::mutex> hlk(hist_mu_);
                    turn_user_snap = live_user_text_;
                    last_user_snap = last_user_text_;
                }
                flush_live_user();
                had_interaction = true;
                model_turn_done = false;
                set_state(AssistantState::Thinking);

                const auto* fcalls = tc->get("functionCalls");
                if (fcalls && fcalls->is_arr()) {
                    json::Value fresps = json::Value::make_arr();
                    for (const auto& fc : fcalls->arr_val) {
                        std::string id = fc.get("id") ? fc.get("id")->as_str() : "";
                        std::string name = fc.get("name") ? fc.get("name")->as_str() : "";
                        json::Value args = fc.get("args") ? *fc.get("args") : json::Value::make_obj();
                        LOGI("Assistant", "ToolCall: %s(%s)", name.c_str(), args.dump(-1).c_str());
                        emit_event("tool_call", "tool", name);

                        json::Value res;
                        const bool req_mic_mute = (name == "set_mic_mute") &&
                            (!args.get("muted") || args.get("muted")->as_bool(true));
                        if (block_power_tool_for_stop(self_stop_requested, name)) {
                            res = json::Value::make_obj();
                            res["ok"] = json::Value::make_bool(false);
                            res["error"] = json::Value::make_str("Пользователь просит завершить диалог, а не выключать устройство.");
                            res["stopSession"] = json::Value::make_bool(true);
                        } else if (req_mic_mute && !should_allow_ai_mic_mute(true, turn_user_snap, last_user_snap)) {
                            LOGW("Assistant", "Blocked spontaneous set_mic_mute(true) without explicit microphone command");
                            res = json::Value::make_obj();
                            res["ok"] = json::Value::make_bool(false);
                            res["error"] = json::Value::make_str("Отключать микрофон можно только по прямой просьбе выключить микрофон.");
                        } else {
                            res = cb_.execute_tool ? cb_.execute_tool(name, args) : json::Value::make_obj();
                        }
                        if (const auto* ss = res.get("stopSession"); ss && ss->as_bool(false)) {
                            stop_after_turn = true;
                            if (name == "set_mic_mute") {
                                mic_muted_.store(true);
                            }
                        }
                        if (const auto* sl = res.get("silent"); sl && sl->as_bool(false)) {
                            silent_turn = true;
                            stop_after_turn = true;
                            if (cb_.clear_playback) cb_.clear_playback();
                        }
                        if (const auto* rv = res.get("reloadVoice"); rv && rv->as_bool(false)) {
                            switch_voice_now = true;
                        }
                        if (const auto* rp = res.get("reloadPersona"); rp && rp->as_bool(false)) {
                            switch_persona_now = true;
                        }

                        json::Value fr = json::Value::make_obj();
                        if (!id.empty()) fr["id"] = json::Value::make_str(id);
                        fr["name"] = json::Value::make_str(name);
                        json::Value resp_wrap = json::Value::make_obj();
                        resp_wrap["output"] = res;
                        fr["response"] = std::move(resp_wrap);
                        fresps.push(std::move(fr));
                    }

                    json::Value tr_body = json::Value::make_obj();
                    tr_body["functionResponses"] = std::move(fresps);
                    json::Value tr_msg = json::Value::make_obj();
                    tr_msg["toolResponse"] = std::move(tr_body);
                    if (!ws.send_text(tr_msg.dump(-1))) {
                        return false;
                    }
                    if (switch_voice_now || switch_persona_now) {
                        if (cb_.clear_playback) cb_.clear_playback();
                        return false;
                    }
                    if (silent_turn && stop_after_turn) {
                        if (cb_.clear_playback) cb_.clear_playback();
                        session_done = true;
                        return false;
                    }
                }
            }

            if (const auto* sc = msg.get("serverContent"); sc && sc->is_obj()) {
                if (const auto* intr = sc->get("interrupted"); intr && intr->as_bool(false)) {
                    LOGI("Assistant", "Server signaled interruption -> clearing playback");
                    if (cb_.clear_playback) cb_.clear_playback();
                    flush_live_model();
                    model_turn_done = false;
                    awaiting_playback_drain = false;
                    quiet_reply_active_.store(false);
                    quiet_speech = {};
                    reply_started = false;
                    audio_sent_since_end = false;
                    last_audio_end_ms = steady_now_ms();
                    listen_idle_since = std::chrono::steady_clock::now();
                    listen_unconfirmed_since = listen_idle_since;
                    set_state(AssistantState::Listening);
                }

                if (const auto* it = sc->get("inputTranscription"); it && it->is_obj()) {
                    if (const auto* txt = it->get("text"); txt && txt->is_str()) {
                        const std::string& delta = txt->as_str();
                        if (!delta.empty()) {
                            silent_turn = false;
                            had_interaction = true;
                            last_transcript_ms = steady_now_ms();
                            listen_idle_since = std::chrono::steady_clock::now();
                            listen_unconfirmed_since = listen_idle_since;
                            {
                                std::lock_guard<std::mutex> lk(hist_mu_);
                                live_user_text_ += delta;
                                last_user_text_ = trim_str(live_user_text_);
                            }
                            emit_event("transcript_delta", "user", delta);
                        }
                    }
                }

                if (const auto* mt = sc->get("modelTurn"); mt && mt->is_obj()) {
                    flush_live_user();
                    had_interaction = true;
                    model_turn_done = false;
                    if (const auto* parts = mt->get("parts"); parts && parts->is_arr()) {
                        for (const auto& pt : parts->arr_val) {
                            if (const auto* idata = pt.get("inlineData"); idata && idata->is_obj()) {
                                std::string mime = idata->get("mimeType") ? idata->get("mimeType")->as_str() : "audio/pcm";
                                std::string b64d = idata->get("data") ? idata->get("data")->as_str() : "";
                                int rate = parse_pcm_sample_rate(mime);
                                if (!silent_turn && rate > 0 && !b64d.empty() && cb_.play_pcm) {
                                    std::vector<uint8_t> pcm_bytes = b64::decode(b64d);
                                    size_t frames = pcm_bytes.size() / 2;
                                    if (frames > 0 && !session_abort_.load()) {
                                        if (!reply_started) {
                                            quiet_reply_active_.store(cfg_snap.quiet_whisper_reply && quiet_speech.detected());
                                            reply_started = true;
                                        }
                                        model_audio_ms = steady_now_ms();
                                        set_state(AssistantState::Speaking);
                                        if (quiet_reply_active_) {
                                            // Change only this response, before it reaches the speaker and echo reference.
                                            for (size_t i = 0; i < frames; ++i) {
                                                int16_t sample = 0;
                                                std::memcpy(&sample, pcm_bytes.data() + i * 2, 2);
                                                sample = static_cast<int16_t>(sample * 0.35f);
                                                std::memcpy(pcm_bytes.data() + i * 2, &sample, 2);
                                            }
                                        }
                                        cb_.play_pcm(reinterpret_cast<const int16_t*>(pcm_bytes.data()), frames, rate, 1);
                                    }
                                }
                            } else if (const auto* txt = pt.get("text"); txt && txt->is_str()) {
                                // Some fallback text parts may arrive alongside audio/tools
                                if (!pt.get("thought") || !pt.get("thought")->as_bool(false)) {
                                    const std::string& delta = txt->as_str();
                                    if (!delta.empty()) {
                                        {
                                            std::lock_guard<std::mutex> lk(hist_mu_);
                                            live_model_text_ += delta;
                                            last_model_text_ = trim_str(live_model_text_);
                                        }
                                        emit_event("transcript_delta", "model", delta);
                                    }
                                }
                            }
                        }
                    }
                }

                if (const auto* ot = sc->get("outputTranscription"); ot && ot->is_obj()) {
                    if (const auto* txt = ot->get("text"); txt && txt->is_str()) {
                        const std::string& delta = txt->as_str();
                        if (!delta.empty()) {
                            flush_live_user();
                            had_interaction = true;
                            {
                                std::lock_guard<std::mutex> lk(hist_mu_);
                                live_model_text_ += delta;
                                last_model_text_ = trim_str(live_model_text_);
                            }
                            emit_event("transcript_delta", "model", delta);
                        }
                    }
                }

                bool turn_done_now = false;
                if (!ext_thinking) {
                    if (const auto* tc_flag = sc->get("turnComplete"); tc_flag && tc_flag->as_bool(false)) {
                        turn_done_now = true;
                    }
                    if (const auto* gc = sc->get("generationComplete"); gc && gc->as_bool(false)) {
                        turn_done_now = true;
                    }
                }

                if (turn_done_now) {
                    flush_live_user();
                    flush_live_model();
                    model_turn_done = true;
                    awaiting_playback_drain = !silent_turn;
                    silent_turn = false;
                    audio_sent_since_end = false;
                    last_audio_end_ms = steady_now_ms();
                    listen_idle_since = std::chrono::steady_clock::now();
                }
            }
            return true;
        };

        while (running_.load() && !session_abort_.load() && ws.is_connected()) {
            // 1. Poll / read incoming frames cleanly without stalling outgoing TX
            WsFrame frame;
            std::string rerr;
            WsRecvStatus rst = ws.recv_frame(frame, 20, rerr);
            while (true) {
                if (rst == WsRecvStatus::Frame) {
                    if (frame.opcode == 0x01 || frame.opcode == 0x02) {
                        if (!handle_server_json(frame.payload)) {
                            if (!retry_alternate && !retry_without_search &&
                                !switch_voice_now && !switch_persona_now && !session_done && !silent_turn) {
                                fatal_error = true;
                            }
                            break;
                        }
                    }
                    // Drain any buffered decrypted frames immediately with 0ms timeout
                    rst = ws.recv_frame(frame, 0, rerr);
                    continue;
                }
                if (rst == WsRecvStatus::Closed) {
                    std::string close_reason = (frame.payload.size() > 2) ? frame.payload.substr(2) : "";
                    LOGI("Assistant", "Gemini WebSocket closed cleanly (code=%u, reason=%s)",
                         frame.close_code, close_reason.c_str());
                    bool can_fallback =
                        !setup_complete && !retried_route && has_proxy_cfg &&
                        !session_abort_.load() && running_.load() &&
                        (frame.close_code == 1007 || frame.close_code == 1006 || frame.close_code == 0 ||
                         contains_ci(close_reason, "location") || contains_ci(close_reason, "region") ||
                         contains_ci(close_reason, "country"));
                    if (can_fallback) {
                        LOGW("Assistant", "Pre-setup close (code=%u) via '%s'; retrying via '%s'",
                             frame.close_code,
                             cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : kGeminiHost,
                             !cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : kGeminiHost);
                        retry_alternate = true;
                        break;
                    }
                    bool can_disable_search =
                        !setup_complete && !retried_search && cur_google_search &&
                        !session_abort_.load() && running_.load() &&
                        (frame.close_code == 1011 ||
                         contains_ci(close_reason, "quota") || contains_ci(close_reason, "billing") ||
                         contains_ci(close_reason, "plan"));
                    if (can_disable_search) {
                        LOGW("Assistant", "Pre-setup close (code=%u, reason=%s); retrying without googleSearch",
                             frame.close_code, close_reason.c_str());
                        retry_without_search = true;
                        break;
                    }
                    if (!setup_complete || (frame.close_code != 1000 && frame.close_code != 0)) {
                        set_error("Gemini close " + std::to_string(frame.close_code) +
                                      (close_reason.empty() ? "" : (": " + close_reason)),
                                  src != TriggerSource::Text);
                        fatal_error = true;
                    }
                    session_done = true;
                    break;
                }
                if (rst == WsRecvStatus::Error) {
                    if (!setup_complete && !retried_route && has_proxy_cfg && !session_abort_.load() && running_.load()) {
                        LOGW("Assistant", "Pre-setup WebSocket read error (%s); retrying via '%s'",
                             rerr.c_str(), !cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : kGeminiHost);
                        retry_alternate = true;
                        break;
                    }
                    if (!session_abort_.load() && running_.load()) {
                        set_error("WebSocket чтение: " + rerr, src != TriggerSource::Text);
                        fatal_error = true;
                    }
                    break;
                }
                break; // Timeout
            }
            if (fatal_error || retry_alternate || retry_without_search || switch_voice_now || session_done || session_abort_.load() || !ws.is_connected()) break;

            auto now = std::chrono::steady_clock::now();

            if (!setup_complete) {
                if (now >= setup_deadline_tp) {
                    if (!retried_route && has_proxy_cfg && running_.load() && !session_abort_.load()) {
                        LOGW("Assistant", "setupComplete timeout via '%s'; retrying via '%s'",
                             cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : kGeminiHost,
                             !cur_use_proxy ? cfg_snap.gemini_proxy_host.c_str() : kGeminiHost);
                        retry_alternate = true;
                    } else {
                        set_error("Таймаут setupComplete от Gemini Live", src != TriggerSource::Text);
                        fatal_error = true;
                    }
                    break;
                }
                continue;
            }

            // 2. Send pending text turns (initial UI text prompt or mid-session text turn)
            while (true) {
                std::string t;
                {
                    std::lock_guard<std::mutex> lk(tx_mu_);
                    if (tx_texts_.empty()) break;
                    t = std::move(tx_texts_.front());
                    tx_texts_.pop_front();
                }
                model_turn_done = false;
                awaiting_playback_drain = false;
                self_stop_requested = is_assistant_stop_request(utf8_to_lower_ru(trim_str(t)));
                if (self_stop_requested) stop_after_turn = true;
                set_state(AssistantState::Thinking);
                if (!send_client_text(t)) {
                    set_error("Ошибка отправки текста в Gemini Live", src != TriggerSource::Text);
                    fatal_error = true;
                    break;
                }
            }
            if (fatal_error) break;

            // 3. Send buffered 16 kHz PCM audio (chunked in ~100 ms frames)
            bool speaking_now = (cb_.is_playback_or_tail_active && cb_.is_playback_or_tail_active());
            if (!announcement && !(src == TriggerSource::Text && !had_interaction)) {
                std::vector<int16_t> pcm_batch;
                {
                    std::lock_guard<std::mutex> lk(tx_mu_);
                    bool time_due = !tx_pcm_.empty() &&
                                    (now - last_pcm_flush_tp >= std::chrono::milliseconds(110));
                    if (tx_pcm_.size() >= kTxChunkSamples || time_due) {
                        pcm_batch.swap(tx_pcm_);
                        last_pcm_flush_tp = now;
                    }
                }
                if (!pcm_batch.empty()) {
                    if (cfg_snap.quiet_whisper_reply && !reply_started && !speaking_now &&
                        !remote_turn_ && src != TriggerSource::Text) {
                        quiet_speech.observe(pcm_batch.data(), pcm_batch.size(),
                                             last_noise_rms_.load(std::memory_order_relaxed));
                    }
                    size_t off = 0;
                    while (off < pcm_batch.size()) {
                        size_t n = std::min(kTxChunkSamples, pcm_batch.size() - off);
                        if (!send_audio_pcm(pcm_batch.data() + off, n)) {
                            if (cur_use_proxy && !session_abort_.load()) invalidate_proxy_selection(cfg_snap.gemini_proxy_host);
                            set_error("Ошибка отправки аудио в Gemini Live", src != TriggerSource::Text);
                            fatal_error = true;
                            break;
                        }
                        off += n;
                    }
                    if (fatal_error) break;
                    audio_sent_since_end = true;
                }
                if (src != TriggerSource::Remote && !remote_turn_.load()) {
                    const int64_t now_ms = steady_now_ms();
                    const int64_t activity_ms = std::max(last_voice_ms_.load(), last_transcript_ms);
                    if (should_send_audio_stream_end(audio_sent_since_end,
                                                     state_.load() == AssistantState::Listening,
                                                     speaking_now,
                                                     activity_ms,
                                                     last_audio_end_ms,
                                                     model_audio_ms,
                                                     now_ms,
                                                     cfg_snap.vad_silence_ms)) {
                        if (send_audio_stream_end()) {
                            last_audio_end_ms = now_ms;
                            audio_sent_since_end = false;
                        } else {
                            set_error("Ошибка закрытия аудиопотока Gemini Live", src != TriggerSource::Text);
                            fatal_error = true;
                            break;
                        }
                    }
                }
                bool send_remote_end = false;
                {
                    std::lock_guard<std::mutex> lk(tx_mu_);
                    if (tx_pcm_.empty() && remote_end_pending_.exchange(false)) send_remote_end = true;
                }
                if (send_remote_end) {
                    using json::Value;
                    Value ri = Value::make_obj();
                    ri["activityEnd"] = Value::make_obj();
                    Value msg = Value::make_obj();
                    msg["realtimeInput"] = std::move(ri);
                    if (!ws.send_text(msg.dump(-1))) {
                        set_error("Ошибка завершения записи с пульта", false);
                        fatal_error = true;
                        break;
                    }
                    stop_after_turn = true;
                    set_state(AssistantState::Thinking);
                }
            }

            // 4. Transition Speaking -> Listening / End once speaker finishes playing
            if (awaiting_playback_drain) {
                if (!speaking_now) {
                    awaiting_playback_drain = false;
                    quiet_reply_active_.store(false);
                    quiet_speech = {};
                    reply_started = false;
                    if (stop_after_turn || go_away_received || cfg_snap.follow_up_sec <= 0) {
                        LOGI("Assistant", "Session complete after turn (stop=%d, goAway=%d)",
                             stop_after_turn ? 1 : 0, go_away_received ? 1 : 0);
                        session_done = true;
                        break;
                    }
                    listen_idle_since = now;
                    listen_unconfirmed_since = now;
                    initial_speech_ms = last_speech_ms_.load();
                    set_state(AssistantState::Listening);
                } else {
                    listen_idle_since = now;
                }
            } else if (state_.load() == AssistantState::Listening) {
                if (!speaking_now && last_speech_ms_.load() > initial_speech_ms) {
                    initial_speech_ms = last_speech_ms_.load();
                    listen_idle_since = now;
                }
                if (!remote_recording_.load() && listening_deadline_expired(now - listen_idle_since, now - listen_unconfirmed_since,
                                               cfg_snap.follow_up_sec, had_interaction)) {
                    LOGI("Assistant", "Listening timeout (idle=%llds, unconfirmed=%llds), closing session",
                         static_cast<long long>(std::chrono::duration_cast<std::chrono::seconds>(now - listen_idle_since).count()),
                         static_cast<long long>(std::chrono::duration_cast<std::chrono::seconds>(now - listen_unconfirmed_since).count()));
                    session_done = true;
                    break;
                }
            }

            if (now - session_start_tp >= std::chrono::seconds(std::max(15, cfg_snap.max_session_sec))) {
                LOGI("Assistant", "Reached maxSessionSec (%ds), closing session", cfg_snap.max_session_sec);
                session_done = true;
                break;
            }
        }

        if ((switch_voice_now || switch_persona_now) && running_.load() && !session_abort_.load()) {
            LOGI("Assistant", "Reconnecting WebSocket cleanly for live voice/persona change...");
            if (ws.is_connected()) ws.send_close(1000);
            ws.close();
            {
                std::lock_guard<std::mutex> lk(cfg_mu_);
                cfg_snap = cfg_;
            }
            if (switch_voice_now && switch_persona_now) {
                pending_voice_greeting =
                    "[Системное событие] Имя ассистента изменено на «" + cfg_snap.name +
                    "», а голос переключён на " + cfg_snap.voice_name +
                    ". Подтверди это пользователю 1–2 короткими фразами.";
            } else if (switch_persona_now) {
                pending_voice_greeting =
                    "[Системное событие] Имя ассистента изменено на «" + cfg_snap.name +
                    "» (ключевое слово отклика: «" + cfg_snap.kws_keywords +
                    "»). Коротко подтверди смену имени пользователю 1 фразой.";
            } else {
                pending_voice_greeting =
                    "[Системное событие] Голос ассистента только что переключён на " + cfg_snap.voice_name +
                    ". Подтверди смену голоса пользователю 1–2 короткими фразами этим новым голосом.";
            }
            fatal_error = false;
            retried_route = false;
            continue;
        }
        if (retry_alternate && !retried_route && has_proxy_cfg && running_.load() && !session_abort_.load()) {
            ws.close();
            retried_route = true;
            cur_use_proxy = !cur_use_proxy;
            continue;
        }
        if (retry_without_search && !retried_search && cur_google_search && running_.load() && !session_abort_.load()) {
            ws.close();
            if (cur_use_proxy) use_proxy_host_.store(true);
            search_quota_unsupported_.store(true);
            retried_search = true;
            cur_google_search = false;
            continue;
        }
        break;
    }

    flush_live_user();
    flush_live_model();

    if (ws.is_connected()) {
        ws.send_close(1000);
    }
    ws.close();
    cleanup_watcher();

    remote_recording_.store(false);
    remote_end_pending_.store(false);
    remote_turn_.store(false);
    quiet_reply_active_.store(false);

    if (!fatal_error) {
        set_state(AssistantState::Idle);
    }

    // Small suppression guard so trailing acoustic room reverb cannot re-trigger "алиса"
    wake_suppress_until_ms_.store(steady_now_ms() + 800);
    wake_word_.set_paused(mic_muted_.load());
    LOGI("Assistant", "Session #%llu ended cleanly", static_cast<unsigned long long>(sid));
}

json::Value AssistantManager::get_status_json() {
    using json::Value;
    Value obj = Value::make_obj();
    obj["state"] = Value::make_str(state_str(state_.load()));
    obj["active"] = Value::make_bool(is_active());
    obj["micMuted"] = Value::make_bool(mic_muted_.load());
    obj["quietReplyActive"] = Value::make_bool(quiet_reply_active_.load());
    Value remote_mic = Value::make_obj();
    remote_mic["recording"] = Value::make_bool(remote_recording_.load());
    remote_mic["samples"] = Value::make_int(static_cast<int64_t>(remote_samples_.load()));
    obj["remoteMic"] = std::move(remote_mic);
    obj["sessionsCount"] = Value::make_int(static_cast<int64_t>(sessions_count_.load()));

    AssistantConfig c;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        c = cfg_;
    }

    Value ww = wake_word_.get_status_json();
    const bool alisa_cfg = is_alisa_name(c.name);
    const bool stock_active = is_stock_wake_active(c);
    ww["engine"] = Value::make_str(stock_active ? "stock" : "kws");
    if (alisa_cfg) {
        const size_t active = stock_phrase_index(c);
        Value available = Value::make_arr();
        if (stock_alisa_available()) available.push(Value::make_str("\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0"));
        if (stock_yandex_available()) available.push(Value::make_str("\xD1\x8F\xD0\xBD\xD0\xB4\xD0\xB5\xD0\xBA\xD1\x81"));
        ww["availablePhrases"] = std::move(available);
        ww["stockFallback"] = Value::make_bool(stock_fallback_to_kws_.load(std::memory_order_relaxed));
        if (stock_active) {
            const bool stock_ready = stock_ready_.load(std::memory_order_relaxed);
            ww["ready"] = Value::make_bool(stock_ready);
            ww["paused"] = Value::make_bool(mic_muted_.load());
            ww["status"] = Value::make_str(stock_ready ? "stock_listening" : "stock_waiting");
            ww["detections"] = Value::make_int(
                static_cast<int64_t>(stock_wake_detections_.load(std::memory_order_relaxed)));
            const char* active_phrase = active == 1 ? "\xD1\x8F\xD0\xBD\xD0\xB4\xD0\xB5\xD0\xBA\xD1\x81"
                                                    : "\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";
            ww["phrase"] = Value::make_str(active_phrase);
            Value phrases = Value::make_arr();
            phrases.push(Value::make_str(active_phrase));
            ww["phrases"] = std::move(phrases);
        }
    }
    obj["wakeWord"] = std::move(ww);

    obj["enabled"] = Value::make_bool(c.enabled);
    obj["mode"] = Value::make_str(c.mode);
    obj["quickCommandsReady"] = Value::make_bool(c.enabled && c.quick_commands && c.mode == "gemini" &&
        wake_word_.is_ready() && local_asr_ready_);
    obj["model"] = Value::make_str("gemini-3.8-live");
    obj["voiceName"] = Value::make_str(c.voice_name);
    obj["proxyHost"] = Value::make_str(c.gemini_proxy_host);
    obj["proxyActive"] = Value::make_bool(use_proxy_host_.load() && !c.gemini_proxy_host.empty());
    obj["hasApiKey"] = Value::make_bool(!c.gemini_api_key.empty());
    obj["agentMode"] = Value::make_bool(c.agent_mode);

    Value hist = Value::make_arr();
    {
        std::lock_guard<std::mutex> lk(hist_mu_);
        obj["lastError"] = Value::make_str(last_error_);
        obj["lastTrigger"] = Value::make_str(last_trigger_reason_);
        obj["liveUserText"] = Value::make_str(live_user_text_);
        obj["liveModelText"] = Value::make_str(live_model_text_);
        obj["lastUserText"] = Value::make_str(last_user_text_);
        obj["lastModelText"] = Value::make_str(last_model_text_);
        for (const auto& h : history_) {
            Value item = Value::make_obj();
            item["role"] = Value::make_str(h.role);
            item["text"] = Value::make_str(h.text);
            item["ts"] = Value::make_str(h.ts);
            hist.push(std::move(item));
        }
    }
    obj["history"] = std::move(hist);
    return obj;
}

} // namespace openstation
