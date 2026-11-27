#include "assistant/wake_word.hpp"
#include "assistant/wake_word_policy.hpp"
#include "assistant/wake_word_tokens.hpp"
#include "hw/voice_gain_policy.hpp"
#include "sherpa_onnx/c-api.h"
#include "utils/logger.hpp"

#include <algorithm>
#include <array>
#include <utility>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>

namespace openstation {

namespace {

constexpr const char* kLibOrTPath = "/data/local/openstation/lib/libonnxruntime.so";
constexpr const char* kLibSherpaPath = "/data/local/openstation/lib/libsherpa-onnx-c-api.so";
constexpr const char* kVadModelPath = "/data/local/openstation/models/silero_vad.int8.onnx";
constexpr const char* kKwsDir = "/data/local/openstation/models/ru-vosk-small";

struct SherpaApi {
    void* ort_handle = nullptr;
    void* sherpa_handle = nullptr;

    const SherpaOnnxVoiceActivityDetector* (*createVad)(const SherpaOnnxVadModelConfig*, float) = nullptr;
    void (*destroyVad)(const SherpaOnnxVoiceActivityDetector*) = nullptr;
    void (*vadAccept)(const SherpaOnnxVoiceActivityDetector*, const float*, int32_t) = nullptr;
    int32_t (*vadDetected)(const SherpaOnnxVoiceActivityDetector*) = nullptr;
    void (*vadClear)(const SherpaOnnxVoiceActivityDetector*) = nullptr;
    void (*vadReset)(const SherpaOnnxVoiceActivityDetector*) = nullptr;

    const SherpaOnnxKeywordSpotter* (*createKws)(const SherpaOnnxKeywordSpotterConfig*) = nullptr;
    void (*destroyKws)(const SherpaOnnxKeywordSpotter*) = nullptr;
    const SherpaOnnxOnlineStream* (*createKwsStream)(const SherpaOnnxKeywordSpotter*) = nullptr;
    void (*destroyOnlineStream)(const SherpaOnnxOnlineStream*) = nullptr;
    void (*streamAccept)(const SherpaOnnxOnlineStream*, int32_t, const float*, int32_t) = nullptr;
    int32_t (*isKwsReady)(const SherpaOnnxKeywordSpotter*, const SherpaOnnxOnlineStream*) = nullptr;
    void (*decodeKws)(const SherpaOnnxKeywordSpotter*, const SherpaOnnxOnlineStream*) = nullptr;
    void (*resetKwsStream)(const SherpaOnnxKeywordSpotter*, const SherpaOnnxOnlineStream*) = nullptr;
    const SherpaOnnxKeywordResult* (*getKwsResult)(const SherpaOnnxKeywordSpotter*, const SherpaOnnxOnlineStream*) = nullptr;
    void (*destroyKwsResult)(const SherpaOnnxKeywordResult*) = nullptr;
    const char* (*getVersion)() = nullptr;

    bool load(std::string& err) {
        if (sherpa_handle) return true;

        if (!ort_handle && access(kLibOrTPath, R_OK) == 0) {
            ort_handle = dlopen(kLibOrTPath, RTLD_NOW | RTLD_GLOBAL);
            if (!ort_handle) {
                const char* de = dlerror(); // dlerror() clears itself; a second call returns NULL
                err = std::string("dlopen libonnxruntime.so: ") + (de ? de : "unknown");
                return false;
            }
        }

        const char* sherpa_cand = access(kLibSherpaPath, R_OK) == 0 ? kLibSherpaPath : "libsherpa-onnx-c-api.so";
        sherpa_handle = dlopen(sherpa_cand, RTLD_NOW | RTLD_LOCAL);
        if (!sherpa_handle) {
            const char* de = dlerror();
            err = std::string("dlopen sherpa-onnx: ") + (de ? de : "missing .so");
            return false;
        }

        auto sym = [&](auto& fn, const char* name, bool req = true) -> bool {
            fn = reinterpret_cast<std::decay_t<decltype(fn)>>(dlsym(sherpa_handle, name));
            if (req && !fn) {
                err = std::string("Missing symbol in sherpa-onnx: ") + name;
                return false;
            }
            return true;
        };

        bool ok =
            sym(createVad, "SherpaOnnxCreateVoiceActivityDetector") &&
            sym(destroyVad, "SherpaOnnxDestroyVoiceActivityDetector") &&
            sym(vadAccept, "SherpaOnnxVoiceActivityDetectorAcceptWaveform") &&
            sym(vadDetected, "SherpaOnnxVoiceActivityDetectorDetected") &&
            sym(vadClear, "SherpaOnnxVoiceActivityDetectorClear") &&
            sym(vadReset, "SherpaOnnxVoiceActivityDetectorReset") &&
            sym(createKws, "SherpaOnnxCreateKeywordSpotter") &&
            sym(destroyKws, "SherpaOnnxDestroyKeywordSpotter") &&
            sym(createKwsStream, "SherpaOnnxCreateKeywordStream") &&
            sym(destroyOnlineStream, "SherpaOnnxDestroyOnlineStream") &&
            sym(streamAccept, "SherpaOnnxOnlineStreamAcceptWaveform") &&
            sym(isKwsReady, "SherpaOnnxIsKeywordStreamReady") &&
            sym(decodeKws, "SherpaOnnxDecodeKeywordStream") &&
            sym(resetKwsStream, "SherpaOnnxResetKeywordStream") &&
            sym(getKwsResult, "SherpaOnnxGetKeywordResult") &&
            sym(destroyKwsResult, "SherpaOnnxDestroyKeywordResult");
        sym(getVersion, "SherpaOnnxGetVersionStr", false);

        if (!ok) {
            dlclose(sherpa_handle);
            sherpa_handle = nullptr;
            return false;
        }
        return true;
    }
};

SherpaApi g_sherpa;
std::mutex g_sherpa_load_mu;

std::string normalize_keywords_buf(std::string kw, const SentencePieceTokenizer* tokenizer) {
    kw.erase(std::remove(kw.begin(), kw.end(), '\r'), kw.end());
    if (kw.empty()) {
        kw = "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";
    }
    if (tokenizer && !tokenizer->empty()) {
        return tokenizer->format_keywords_buf(kw);
    }
    if (kw.back() != '\n') kw.push_back('\n');
    return kw;
}

bool file_readable(const std::string& p) {
    return access(p.c_str(), R_OK) == 0;
}

} // namespace

WakeWordEngine::~WakeWordEngine() {
    stop();
}

bool WakeWordEngine::start(const WakeWordSettings& settings, WakeCallback cb, QuickCallback quick_cb) {
    stop();
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        settings_ = settings;
        settings_dirty_ = false;
    }
    cb_ = std::move(cb);
    quick_cb_ = std::move(quick_cb);
    {
        std::lock_guard<std::mutex> lk(q_mu_);
        pcm_q_.reserve(4096);
    }
    running_.store(true);
    worker_ = std::thread(&WakeWordEngine::worker_loop, this);
    return true;
}

void WakeWordEngine::stop() {
    running_.store(false);
    q_cv_.notify_all();
    if (worker_.joinable()) {
        worker_.join();
    }
    ready_.store(false);
    vad_speech_active_.store(false);
}

void WakeWordEngine::restart(const WakeWordSettings& settings) {
    WakeCallback cb = cb_;
    QuickCallback quick_cb = quick_cb_;
    stop();
    start(settings, std::move(cb), std::move(quick_cb));
}

void WakeWordEngine::update_settings(const WakeWordSettings& settings) {
    std::lock_guard<std::mutex> lk(cfg_mu_);
    if (settings.enabled != settings_.enabled ||
        settings.kws_enabled != settings_.kws_enabled ||
        settings.quick_commands != settings_.quick_commands ||
        settings.keywords != settings_.keywords ||
        std::abs(settings.threshold - settings_.threshold) > 1e-4f ||
        std::abs(settings.score - settings_.score) > 1e-4f ||
        settings.num_threads != settings_.num_threads ||
        settings.mic_sensitivity != settings_.mic_sensitivity) {
        settings_ = settings;
        settings_dirty_ = true;
    }
    q_cv_.notify_one();
}

void WakeWordEngine::set_paused(bool paused) {
    bool prev = paused_.exchange(paused);
    if (prev != paused) {
        std::lock_guard<std::mutex> lk(q_mu_);
        pcm_q_.clear();
        reset_req_ = true;
        q_cv_.notify_one();
    }
}

void WakeWordEngine::feed_audio_16k(const int16_t* samples, size_t count, float noise_rms, bool during_reply) {
    noise_rms_.store(noise_rms, std::memory_order_relaxed);
    during_reply_.store(during_reply, std::memory_order_relaxed);
    if (!samples || count == 0 || !running_.load() || paused_.load() || !ready_.load()) {
        return;
    }
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        if (!settings_.enabled) return;
    }
    std::lock_guard<std::mutex> lk(q_mu_);
    // Cap backlog to 1.5s to prevent latency buildup
    constexpr size_t kMaxBacklog = 24000;
    if (pcm_q_.size() + count > kMaxBacklog) {
        pcm_q_.clear();
    }
    size_t old_sz = pcm_q_.size();
    pcm_q_.resize(old_sz + count);
    constexpr float kScale = 1.0f / 32768.0f;
    for (size_t i = 0; i < count; ++i) {
        pcm_q_[old_sz + i] = static_cast<float>(samples[i]) * kScale;
    }
    q_cv_.notify_one();
}

void WakeWordEngine::feed_playback_16k(const int16_t* samples, size_t count) {
    if (!samples || count == 0 || !running_ || !ready_) return;
    std::lock_guard<std::mutex> lock(q_mu_);
    constexpr size_t kMaxPlaybackBacklog = 8000;
    if (playback_q_.size() + count > kMaxPlaybackBacklog) playback_q_.clear();
    for (size_t i = 0; i < count; ++i) playback_q_.push_back(samples[i] / 32768.0f);
    q_cv_.notify_one();
}

json::Value WakeWordEngine::get_status_json() {
    using json::Value;
    Value obj = Value::make_obj();
    obj["ready"] = Value::make_bool(ready_.load());
    obj["paused"] = Value::make_bool(paused_.load());
    obj["vadSpeech"] = Value::make_bool(vad_speech_active_.load());
    obj["detections"] = Value::make_int(static_cast<int64_t>(detections_.load()));
    {
        std::lock_guard<std::mutex> lk(stat_mu_);
        obj["status"] = Value::make_str(status_str_);
    }
    return obj;
}

void WakeWordEngine::worker_loop() {
    auto set_status = [&](const std::string& st) {
        std::lock_guard<std::mutex> lk(stat_mu_);
        status_str_ = st;
    };

    {
        std::lock_guard<std::mutex> lk(g_sherpa_load_mu);
        std::string err;
        if (!g_sherpa.load(err)) {
            LOGW("WakeWord", "WakeWord disabled (%s)", err.c_str());
            set_status("lib_missing: " + err);
            return;
        }
    }

    const std::string enc_path = std::string(kKwsDir) + "/encoder.int8.onnx";
    const std::string dec_path = std::string(kKwsDir) + "/decoder.onnx";
    const std::string join_path = std::string(kKwsDir) + "/joiner.int8.onnx";
    const std::string tok_path = std::string(kKwsDir) + "/tokens.txt";

    for (const auto& req_file : {std::string(kVadModelPath), enc_path, dec_path, join_path, tok_path}) {
        if (!file_readable(req_file)) {
            LOGW("WakeWord", "Missing model file: %s (offline wake word disabled)", req_file.c_str());
            set_status("model_missing: " + req_file);
            return;
        }
    }

    SentencePieceTokenizer tokenizer;
    if (!tokenizer.load_tokens_file(tok_path)) {
        LOGW("WakeWord", "Failed to parse SentencePiece tokens from %s", tok_path.c_str());
    }

    const SherpaOnnxVoiceActivityDetector* vad = nullptr;
    const SherpaOnnxKeywordSpotter* kws = nullptr;
    const SherpaOnnxOnlineStream* kws_stream = nullptr;
    const SherpaOnnxOnlineStream* playback_stream = nullptr;

    auto destroy_engines = [&]() {
        if (kws_stream) { g_sherpa.destroyOnlineStream(kws_stream); kws_stream = nullptr; }
        if (playback_stream) {
            g_sherpa.destroyOnlineStream(playback_stream);
            playback_stream = nullptr;
        }
        if (kws) { g_sherpa.destroyKws(kws); kws = nullptr; }
        if (vad) { g_sherpa.destroyVad(vad); vad = nullptr; }
        ready_.store(false);
        vad_speech_active_.store(false);
    };

    auto create_engines = [&](const WakeWordSettings& cur) -> bool {
        destroy_engines();
        const bool want_vad = cur.enabled && (cur.kws_enabled || (cur.quick_commands && quick_cb_));
        if (!want_vad) {
            set_status("disabled");
            return false;
        }

        const float sens_norm = std::clamp(cur.mic_sensitivity, 10, 100) / 100.0f;
        const float vad_thr = std::clamp(0.32f - sens_norm * 0.20f, 0.10f, 0.35f);
        const float eff_kws_thr = std::clamp(cur.threshold * (0.95f - sens_norm * 0.35f), 0.09f, 0.35f);

        SherpaOnnxVadModelConfig vad_cfg {};
        memset(&vad_cfg, 0, sizeof(vad_cfg));
        vad_cfg.silero_vad.model = kVadModelPath;
        vad_cfg.silero_vad.threshold = vad_thr;
        vad_cfg.silero_vad.min_silence_duration = 0.45f;
        vad_cfg.silero_vad.min_speech_duration = 0.04f;
        vad_cfg.silero_vad.window_size = 512;
        vad_cfg.silero_vad.max_speech_duration = 15.0f;
        vad_cfg.sample_rate = 16000;
        vad_cfg.num_threads = 1;
        vad_cfg.provider = "cpu";
        vad_cfg.debug = 0;

        vad = g_sherpa.createVad(&vad_cfg, cur.quick_commands ? 20.0f : 6.0f);
        if (!vad) {
            set_status("error: createVad failed");
            LOGE("WakeWord", "Failed to create Silero VAD");
            return false;
        }

        if (cur.kws_enabled) {
            std::string kw_buf = normalize_keywords_buf(cur.keywords, &tokenizer);

            SherpaOnnxKeywordSpotterConfig kws_cfg {};
            memset(&kws_cfg, 0, sizeof(kws_cfg));
            kws_cfg.feat_config.sample_rate = 16000;
            kws_cfg.feat_config.feature_dim = 80;
            kws_cfg.model_config.transducer.encoder = enc_path.c_str();
            kws_cfg.model_config.transducer.decoder = dec_path.c_str();
            kws_cfg.model_config.transducer.joiner = join_path.c_str();
            kws_cfg.model_config.tokens = tok_path.c_str();
            kws_cfg.model_config.num_threads = std::clamp(cur.num_threads, 1, 4);
            kws_cfg.model_config.provider = "cpu";
            kws_cfg.model_config.debug = 0;
            kws_cfg.max_active_paths = 4;
            kws_cfg.num_trailing_blanks = 1;
            kws_cfg.keywords_score = cur.score;
            kws_cfg.keywords_threshold = eff_kws_thr;
            kws_cfg.keywords_file = nullptr;
            kws_cfg.keywords_buf = kw_buf.c_str();
            kws_cfg.keywords_buf_size = static_cast<int32_t>(kw_buf.size());

            kws = g_sherpa.createKws(&kws_cfg);
            if (!kws) {
                destroy_engines();
                set_status("error: createKws failed");
                LOGE("WakeWord", "Failed to create KeywordSpotter (check tokens/keywords)");
                return false;
            }
            kws_stream = g_sherpa.createKwsStream(kws);
            if (!kws_stream) {
                destroy_engines();
                set_status("error: createKwsStream failed");
                return false;
            }
        }

        ready_.store(true);
        set_status(cur.kws_enabled ? "listening" : "vad_only");
        LOGI("WakeWord", "Initialized (sherpa %s, kws=%s, quick=%s, kwsThr=%.2f, vadThr=%.2f, sens=%d%%)",
             g_sherpa.getVersion ? g_sherpa.getVersion() : "?",
             cur.kws_enabled ? "on" : "off",
             cur.quick_commands ? "on" : "off",
             eff_kws_thr, vad_thr, cur.mic_sensitivity);
        return true;
    };

    WakeWordSettings cur_cfg;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        cur_cfg = settings_;
        settings_dirty_ = false;
    }
    create_engines(cur_cfg);

    constexpr size_t kVadWin = 512;      // 32 ms @ 16 kHz
    constexpr size_t kLeadInWins = 16; // 512 ms pre-speech context
    constexpr size_t kLeadInMax = kLeadInWins * kVadWin;

    std::vector<float> batch;
    batch.reserve(4096);
    std::vector<float> playback_batch;
    std::vector<float> acc;
    acc.reserve(8192);
    size_t acc_head = 0;

    std::vector<float> lead_ring(kLeadInMax, 0.0f);
    size_t lead_head = 0;
    size_t lead_count = 0;

    auto push_lead_win = [&](const float* w) {
        std::memcpy(lead_ring.data() + lead_head, w, kVadWin * sizeof(float));
        lead_head = (lead_head + kVadWin) % kLeadInMax;
        if (lead_count < kLeadInMax) {
            lead_count += kVadWin;
        }
    };
    auto feed_lead_to_kws = [&]() {
        if (lead_count == 0) return;
        size_t start = (lead_head + kLeadInMax - lead_count) % kLeadInMax;
        size_t first = std::min(lead_count, kLeadInMax - start);
        if (first > 0) {
            g_sherpa.streamAccept(kws_stream, 16000, lead_ring.data() + start, static_cast<int32_t>(first));
        }
        size_t second = lead_count - first;
        if (second > 0) {
            g_sherpa.streamAccept(kws_stream, 16000, lead_ring.data(), static_cast<int32_t>(second));
        }
        lead_count = 0;
    };

    auto is_speaker_playback_running = []() -> bool {
        static constexpr const char* kPaths[] = {
            "/proc/asound/card0/pcm0p/sub0/status",
            "/proc/asound/card0/pcm2p/sub0/status",
            "/proc/asound/card0/pcm3p/sub0/status",
        };
        for (const char* p : kPaths) {
            FILE* f = std::fopen(p, "r");
            if (!f) continue;
            char line[64] = {};
            const bool ok = (std::fgets(line, sizeof(line), f) != nullptr);
            std::fclose(f);
            if (ok && std::strstr(line, "RUNNING") != nullptr) {
                return true;
            }
        }
        return false;
    };

    bool kws_active = false;
    bool vad_had_speech = false;
    bool speaker_playing = false;
    bool prev_speaker_playing = false;
    int hangover_wins = 0;
    int active_energy_wins = 0;
    int music_cont_wins = 0;
    float avg_music_sq = 1.0e-4f;
    uint32_t vad_clr_cnt = 0;
    uint32_t pb_poll_cnt = 0;
    WakeIdleGate idle_gate;
    auto last_trigger_tp = std::chrono::steady_clock::now() - std::chrono::seconds(10);
    // Preserve the start of quiet words, then finish once the capture has a
    // continuous pause. Silero confirms speech without controlling this timer.
    constexpr size_t kCommandLead = kVadWin * 8;
    constexpr size_t kCommandMax = 64000;
    constexpr int kCommandPause = 14;
    std::vector<int16_t> command_lead(kCommandLead);
    std::vector<int16_t> command_pcm;
    command_pcm.reserve(kCommandMax);
    size_t lead_write = 0, lead_size = 0;
    int command_quiet = 0;
    int command_idle = 0;
    bool command_open = false, command_voiced = false, command_too_long = false;
    auto reset_command = [&] {
        lead_write = lead_size = 0;
        command_quiet = 0;
        command_idle = 0;
        command_open = command_voiced = command_too_long = false;
        command_pcm.clear();
    };
    auto to_pcm = [](float sample) {
        return static_cast<int16_t>(std::clamp(sample * 32768.0f, -32768.0f, 32767.0f));
    };

    using Clock = std::chrono::steady_clock;
    Clock::time_point last_playback_audio{}, pending_deadline{};
    std::vector<std::string> playback_wakes;
    std::string pending_wake;
    const std::array<float, 5120> playback_lead{};
    auto reset_playback_state = [&] {
        if (kws && playback_stream) g_sherpa.resetKwsStream(kws, playback_stream);
        last_playback_audio = {};
        playback_wakes.clear();
        pending_wake.clear();
    };
    auto emit_wake = [&](const std::string& hit, Clock::time_point now) {
        if (during_reply_ && std::find(playback_wakes.begin(), playback_wakes.end(), hit) != playback_wakes.end()) {
            LOGI("WakeWord", "Ignored wake word from speaker playback");
            return;
        }
        if (now - last_trigger_tp < std::chrono::milliseconds(1500)) return;
        last_trigger_tp = now;
        detections_.fetch_add(1);
        LOGI("WakeWord", "Triggered wake word: '%s'", hit.c_str());
        if (cb_) cb_(hit);
    };

    while (running_.load()) {
        batch.clear();
        playback_batch.clear();
        bool need_recreate = false;
        bool do_reset = false;
        {
            std::unique_lock<std::mutex> lk(q_mu_);
            q_cv_.wait_for(lk, std::chrono::milliseconds(pending_wake.empty() ? 150 : 40), [&] {
                if (!running_.load() || !pcm_q_.empty() || !playback_q_.empty() || reset_req_) return true;
                std::lock_guard<std::mutex> clk(cfg_mu_);
                return settings_dirty_;
            });
            if (!running_.load()) break;
            if (!pcm_q_.empty()) {
                batch.swap(pcm_q_);
            }
            playback_batch.swap(playback_q_);
            if (reset_req_) {
                do_reset = true;
                reset_req_ = false;
            }
        }

        {
            std::lock_guard<std::mutex> clk(cfg_mu_);
            if (settings_dirty_) {
                cur_cfg = settings_;
                settings_dirty_ = false;
                need_recreate = true;
            }
        }

        if (need_recreate) {
            reset_command();
            acc.clear();
            acc_head = 0;
            lead_head = 0;
            lead_count = 0;
            kws_active = false;
            vad_had_speech = false;
            prev_speaker_playing = false;
            hangover_wins = 0;
            active_energy_wins = 0;
            music_cont_wins = 0;
            idle_gate.reset();
            create_engines(cur_cfg);
            reset_playback_state();
        }

        if (do_reset) {
            reset_command();
            acc.clear();
            acc_head = 0;
            lead_head = 0;
            lead_count = 0;
            kws_active = false;
            vad_had_speech = false;
            prev_speaker_playing = false;
            hangover_wins = 0;
            active_energy_wins = 0;
            music_cont_wins = 0;
            idle_gate.reset();
            if (vad) g_sherpa.vadReset(vad);
            if (kws && kws_stream) g_sherpa.resetKwsStream(kws, kws_stream);
            reset_playback_state();
            vad_speech_active_.store(false);
        }

        if (!ready_ || paused_) continue;

        if (!playback_batch.empty() && kws) {
            const auto now = Clock::now();
            if (!playback_stream) playback_stream = g_sherpa.createKwsStream(kws);
            if (playback_stream) {
                if (now - last_playback_audio > std::chrono::milliseconds(350)) {
                    g_sherpa.resetKwsStream(kws, playback_stream);
                    g_sherpa.streamAccept(playback_stream, 16000, playback_lead.data(),
                                          static_cast<int32_t>(playback_lead.size()));
                }
                g_sherpa.streamAccept(playback_stream, 16000, playback_batch.data(),
                                      static_cast<int32_t>(playback_batch.size()));
                while (g_sherpa.isKwsReady(kws, playback_stream)) {
                    g_sherpa.decodeKws(kws, playback_stream);
                    const auto* result = g_sherpa.getKwsResult(kws, playback_stream);
                    if (result && result->keyword && result->keyword[0] != '\0') {
                        std::string keyword = result->keyword;
                        g_sherpa.destroyKwsResult(result);
                        g_sherpa.resetKwsStream(kws, playback_stream);
                        if (std::find(playback_wakes.begin(), playback_wakes.end(), keyword) == playback_wakes.end()) {
                            playback_wakes.push_back(std::move(keyword));
                            LOGI("WakeWord", "Detected wake word in speaker reference");
                        }
                        break;
                    }
                    if (result) g_sherpa.destroyKwsResult(result);
                }
            }
            last_playback_audio = now;
        }

        if (!pending_wake.empty() && Clock::now() >= pending_deadline) {
            emit_wake(std::exchange(pending_wake, {}), Clock::now());
            if (paused_) continue;
        }
        if (batch.empty()) continue;
        acc.insert(acc.end(), batch.begin(), batch.end());
        while ((acc.size() - acc_head) >= kVadWin && running_.load() && !paused_.load()) {
            const float* win = acc.data() + acc_head;
            if ((pb_poll_cnt++ & 15U) == 0U) {
                speaker_playing = is_speaker_playback_running();
            }

            if (prev_speaker_playing && !speaker_playing) {
                idle_gate.reset();
                if (vad_had_speech) {
                    g_sherpa.vadReset(vad);
                    vad_had_speech = false;
                }
            } else if (!prev_speaker_playing && speaker_playing && command_open) {
                reset_command();
            }
            prev_speaker_playing = speaker_playing;

            float rms_i16 = 0.0f;
            float peak_i16 = 0.0f;
            compute_window_rms_and_peak_i16(win, kVadWin, rms_i16, peak_i16);
            constexpr float kInvI16 = 1.0f / 32768.0f;
            const float mean_sq = (rms_i16 * kInvI16) * (rms_i16 * kInvI16);
            const float cur_noise_rms = noise_rms_.load(std::memory_order_relaxed);
            const bool collect_commands = cur_cfg.quick_commands && quick_cb_;

            // Adaptive idle energy gate: bypass Silero VAD ONNX on stationary room noise
            // while keeping 512ms wake + quick-command pre-roll rings continuously filled.
            if (idle_gate.should_bypass_vad(rms_i16, peak_i16, cur_noise_rms,
                                            cur_cfg.mic_sensitivity,
                                            speaker_playing, kws_active,
                                            hangover_wins, command_open)) {
                if (vad_had_speech) {
                    g_sherpa.vadReset(vad);
                    vad_had_speech = false;
                }
                active_energy_wins = 0;
                music_cont_wins = 0;
                vad_speech_active_.store(false);
                push_lead_win(win);
                if (collect_commands && !paused_ && !speaker_playing) {
                    for (size_t i = 0; i < kVadWin; ++i) {
                        command_lead[lead_write] = to_pcm(win[i]);
                        lead_write = (lead_write + 1) % kCommandLead;
                    }
                    lead_size = std::min(kCommandLead, lead_size + kVadWin);
                }
                acc_head += kVadWin;
                continue;
            }

            bool speech = false;
            bool continuous_audio = false;
            bool voiced = false;
            if (!speaker_playing) {
                g_sherpa.vadAccept(vad, win, static_cast<int32_t>(kVadWin));
                voiced = g_sherpa.vadDetected(vad) != 0;
            }
            if (speaker_playing) {
                // During active speaker playback, never let Silero VAD suppress wake words
                // (music residuals/harmonics pull Silero below threshold even at loud SNRs).
                if (vad_had_speech) {
                    g_sherpa.vadReset(vad);
                    vad_had_speech = false;
                }
                vad_speech_active_.store(false);
                speech = true;
                continuous_audio = true;
            } else {
                speech = voiced;
                vad_speech_active_.store(speech);
                if (speech) vad_had_speech = true;
                if (speech || (++vad_clr_cnt & 31U) == 0U) {
                    g_sherpa.vadClear(vad);
                }

                // Fallback acoustic energy gate in case external TV/BT room sound confuses Silero.
                const float fallback_rms = idle_gate.fallback_energy_rms(cur_cfg.mic_sensitivity, cur_noise_rms);
                if (rms_i16 > fallback_rms) {
                    if (++active_energy_wins >= 3) {
                        speech = true;
                        continuous_audio = true;
                    }
                } else {
                    active_energy_wins = 0;
                }
            }

            if (continuous_audio) {
                avg_music_sq = avg_music_sq * 0.96f + mean_sq * 0.04f;
            } else {
                music_cont_wins = 0;
            }

            bool fed_current = false;
            if (kws && kws_stream) {
                if (speech) {
                    if (!kws_active) {
                        feed_lead_to_kws();
                        kws_active = true;
                        music_cont_wins = 0;
                    }
                    hangover_wins = 30; // ~960 ms KWS tail across syllables ("а-ли-са") and playback stop
                    g_sherpa.streamAccept(kws_stream, 16000, win, static_cast<int32_t>(kVadWin));
                    fed_current = true;
                    if (continuous_audio) {
                        // Maintain rolling 512 ms context and refresh long continuous music streams ~every 30s
                        // at an energy dip so transducer decoder state stays bounded without blind spots.
                        push_lead_win(win);
                        if (++music_cont_wins >= 937 &&
                            (mean_sq <= avg_music_sq * 0.85f || music_cont_wins >= 1250)) {
                            g_sherpa.resetKwsStream(kws, kws_stream);
                            feed_lead_to_kws();
                            music_cont_wins = 0;
                        }
                    }
                } else if (kws_active && hangover_wins > 0) {
                    g_sherpa.streamAccept(kws_stream, 16000, win, static_cast<int32_t>(kVadWin));
                    fed_current = true;
                    --hangover_wins;
                    if (hangover_wins == 0) {
                        kws_active = false;
                        music_cont_wins = 0;
                        g_sherpa.resetKwsStream(kws, kws_stream);
                    }
                }

                if (!fed_current) {
                    push_lead_win(win);
                }

                if (kws_active || hangover_wins > 0) {
                    while (g_sherpa.isKwsReady(kws, kws_stream)) {
                        g_sherpa.decodeKws(kws, kws_stream);
                        const SherpaOnnxKeywordResult* res = g_sherpa.getKwsResult(kws, kws_stream);
                        std::string hit;
                        if (res && res->keyword && res->keyword[0] != '\0') {
                            hit = res->keyword;
                        }
                        if (res) {
                            g_sherpa.destroyKwsResult(res);
                        }
                        if (!hit.empty()) {
                            g_sherpa.resetKwsStream(kws, kws_stream);
                            kws_active = false;
                            hangover_wins = 0;
                            active_energy_wins = 0;
                            music_cont_wins = 0;
                            lead_count = 0;

                            const auto now = Clock::now();
                            if (during_reply_ && now - last_playback_audio <= std::chrono::milliseconds(300)) {
                                // Let the digital reference catch up before accepting the mic result.
                                if (pending_wake.empty()) {
                                    pending_wake = std::move(hit);
                                    pending_deadline = now + std::chrono::milliseconds(110);
                                }
                            } else {
                                emit_wake(hit, now);
                            }
                            break;
                        }
                    }
                }
            }

            if (collect_commands && !paused_ && !speaker_playing) {
                const float floor = dialogue_activity_threshold(noise_rms_.load(std::memory_order_relaxed)) / 32768.0f;
                const bool audible = mean_sq >= floor * floor;
                if (!command_open && audible) {
                    command_open = true;
                    size_t start = (lead_write + kCommandLead - lead_size) % kCommandLead;
                    for (size_t i = 0; i < lead_size; ++i) {
                        command_pcm.push_back(command_lead[(start + i) % kCommandLead]);
                    }
                }
                if (command_open) {
                    command_voiced |= voiced;
                    command_quiet = audible ? 0 : command_quiet + 1;
                    if (command_pcm.size() + kVadWin <= kCommandMax) {
                        for (size_t i = 0; i < kVadWin; ++i) command_pcm.push_back(to_pcm(win[i]));
                    } else {
                        command_too_long = true;
                    }
                    if (command_quiet >= kCommandPause) {
                        if (command_voiced && !command_too_long) {
                            quick_cb_(command_pcm);
                        }
                        reset_command();
                        g_sherpa.vadReset(vad);
                    }
                } else {
                    for (size_t i = 0; i < kVadWin; ++i) {
                        command_lead[lead_write] = to_pcm(win[i]);
                        lead_write = (lead_write + 1) % kCommandLead;
                    }
                    lead_size = std::min(kCommandLead, lead_size + kVadWin);
                    command_idle = voiced ? 0 : command_idle + 1;
                    if (command_idle == 100) {
                        g_sherpa.vadReset(vad);
                        command_idle = 0;
                    }
                }
            }

            acc_head += kVadWin;
        }

        size_t rem = acc.size() - acc_head;
        if (rem == 0) {
            acc.clear();
        } else if (acc_head > 0) {
            std::memmove(acc.data(), acc.data() + acc_head, rem * sizeof(float));
            acc.resize(rem);
        }
        acc_head = 0;
    }

    destroy_engines();
    set_status("stopped");
}

} // namespace openstation
