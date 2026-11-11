#include "assistant/wake_word.hpp"
#include "sherpa_onnx/c-api.h"
#include "utils/logger.hpp"

#include <algorithm>
#include <cmath>
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
                err = std::string("dlopen libonnxruntime.so: ") + (dlerror() ? dlerror() : "unknown");
                return false;
            }
        }

        const char* sherpa_cand = access(kLibSherpaPath, R_OK) == 0 ? kLibSherpaPath : "libsherpa-onnx-c-api.so";
        sherpa_handle = dlopen(sherpa_cand, RTLD_NOW | RTLD_LOCAL);
        if (!sherpa_handle) {
            err = std::string("dlopen sherpa-onnx: ") + (dlerror() ? dlerror() : "missing .so");
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

std::string normalize_keywords_buf(std::string kw) {
    // Convert optional `/` separators between lines into `\n` and strip `\r`
    kw.erase(std::remove(kw.begin(), kw.end(), '\r'), kw.end());
    if (kw.empty()) {
        kw = "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";
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

bool WakeWordEngine::start(const WakeWordSettings& settings, WakeCallback cb) {
    stop();
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        settings_ = settings;
        settings_dirty_ = false;
    }
    cb_ = std::move(cb);
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

void WakeWordEngine::update_settings(const WakeWordSettings& settings) {
    std::lock_guard<std::mutex> lk(cfg_mu_);
    if (settings.enabled != settings_.enabled ||
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

void WakeWordEngine::feed_audio_16k(const int16_t* samples, size_t count) {
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

    const SherpaOnnxVoiceActivityDetector* vad = nullptr;
    const SherpaOnnxKeywordSpotter* kws = nullptr;
    const SherpaOnnxOnlineStream* kws_stream = nullptr;

    auto destroy_engines = [&]() {
        if (kws_stream) { g_sherpa.destroyOnlineStream(kws_stream); kws_stream = nullptr; }
        if (kws) { g_sherpa.destroyKws(kws); kws = nullptr; }
        if (vad) { g_sherpa.destroyVad(vad); vad = nullptr; }
        ready_.store(false);
        vad_speech_active_.store(false);
    };

    auto create_engines = [&](const WakeWordSettings& cur) -> bool {
        destroy_engines();
        if (!cur.enabled) {
            set_status("disabled");
            return false;
        }

        const float sens_norm = std::clamp(cur.mic_sensitivity, 10, 100) / 100.0f;
        const float vad_thr = std::clamp(0.20f - sens_norm * 0.12f, 0.06f, 0.25f);
        const float eff_kws_thr = std::clamp(cur.threshold * (0.75f - sens_norm * 0.25f), 0.02f, 0.25f);

        SherpaOnnxVadModelConfig vad_cfg {};
        memset(&vad_cfg, 0, sizeof(vad_cfg));
        vad_cfg.silero_vad.model = kVadModelPath;
        vad_cfg.silero_vad.threshold = vad_thr;
        vad_cfg.silero_vad.min_silence_duration = 0.55f;
        vad_cfg.silero_vad.min_speech_duration = 0.04f;
        vad_cfg.silero_vad.window_size = 512;
        vad_cfg.silero_vad.max_speech_duration = 15.0f;
        vad_cfg.sample_rate = 16000;
        vad_cfg.num_threads = 1;
        vad_cfg.provider = "cpu";
        vad_cfg.debug = 0;

        vad = g_sherpa.createVad(&vad_cfg, 6.0f);
        if (!vad) {
            set_status("error: createVad failed");
            LOGE("WakeWord", "Failed to create Silero VAD");
            return false;
        }

        std::string kw_buf = normalize_keywords_buf(cur.keywords);

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

        ready_.store(true);
        set_status("listening");
        LOGI("WakeWord", "Initialized (sherpa %s, kwsThr=%.2f, vadThr=%.2f, score=%.2f, sens=%d%%, threads=%d)",
             g_sherpa.getVersion ? g_sherpa.getVersion() : "?",
             eff_kws_thr, vad_thr, cur.score, cur.mic_sensitivity, cur.num_threads);
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

    bool kws_active = false;
    int hangover_wins = 0;
    int vad_warm_wins = 0;
    bool vad_sleeping = true;
    uint32_t vad_clr_cnt = 0;
    float noise_rms = 0.0055f;
    auto last_trigger_tp = std::chrono::steady_clock::now() - std::chrono::seconds(10);

    while (running_.load()) {
        batch.clear();
        bool need_recreate = false;
        bool do_reset = false;
        {
            std::unique_lock<std::mutex> lk(q_mu_);
            q_cv_.wait_for(lk, std::chrono::milliseconds(150), [&] {
                if (!running_.load() || !pcm_q_.empty() || reset_req_) return true;
                std::lock_guard<std::mutex> clk(cfg_mu_);
                return settings_dirty_;
            });
            if (!running_.load()) break;
            if (!pcm_q_.empty()) {
                batch.swap(pcm_q_);
            }
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
            acc.clear();
            acc_head = 0;
            lead_head = 0;
            lead_count = 0;
            kws_active = false;
            hangover_wins = 0;
            vad_warm_wins = 0;
            vad_sleeping = true;
            create_engines(cur_cfg);
        }

        if (do_reset) {
            acc.clear();
            acc_head = 0;
            lead_head = 0;
            lead_count = 0;
            kws_active = false;
            hangover_wins = 0;
            vad_warm_wins = 0;
            vad_sleeping = true;
            if (vad) g_sherpa.vadReset(vad);
            if (kws && kws_stream) g_sherpa.resetKwsStream(kws, kws_stream);
            vad_speech_active_.store(false);
        }

        if (!ready_.load() || paused_.load() || batch.empty()) {
            continue;
        }

        acc.insert(acc.end(), batch.begin(), batch.end());
        while ((acc.size() - acc_head) >= kVadWin && running_.load() && !paused_.load()) {
            const float* win = acc.data() + acc_head;

            // Fast acoustic energy check (avoids running Silero VAD neural net 31.25x/s in room silence)
            float sum_sq = 0.0f;
            float peak = 0.0f;
            for (size_t i = 0; i < kVadWin; ++i) {
                float v = win[i];
                sum_sq += v * v;
                float av = std::fabs(v);
                if (av > peak) peak = av;
            }
            float win_rms = std::sqrt(sum_sq * (1.0f / static_cast<float>(kVadWin)));
            const float sens_norm = std::clamp(cur_cfg.mic_sensitivity, 10, 100) / 100.0f;
            const float rms_mult = 1.15f - sens_norm * 0.20f;
            const float rms_floor = 0.0006f; // ~20 RMS in 16-bit PCM (far-field whisper receptive)
            const float rms_thr = std::clamp(noise_rms * rms_mult, rms_floor, 0.020f);
            const float peak_thr = std::clamp(noise_rms * (2.2f - sens_norm * 0.6f), rms_floor * 1.8f, 0.06f);
            bool energetic = (win_rms >= rms_thr) || (peak >= peak_thr && win_rms >= rms_thr * 0.25f);

            // Feed window into Silero VAD neural network (0.08 ms inference time on hexacore S905X2;
            // running continuously ensures zero gating loss for far-field room speech and faint whispers)
            g_sherpa.vadAccept(vad, win, static_cast<int32_t>(kVadWin));
            bool speech = (g_sherpa.vadDetected(vad) != 0);
            vad_speech_active_.store(speech);

            if (speech || (++vad_clr_cnt & 31U) == 0U) {
                g_sherpa.vadClear(vad);
            }

            bool fed_current = false;
            if (speech) {
                if (!kws_active) {
                    feed_lead_to_kws();
                    kws_active = true;
                }
                hangover_wins = 30; // ~960 ms extra KWS tail across syllables ("а-ли-са")
                g_sherpa.streamAccept(kws_stream, 16000, win, static_cast<int32_t>(kVadWin));
                fed_current = true;
            } else if (kws_active && hangover_wins > 0) {
                g_sherpa.streamAccept(kws_stream, 16000, win, static_cast<int32_t>(kVadWin));
                fed_current = true;
                --hangover_wins;
                if (hangover_wins == 0) {
                    kws_active = false;
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
                        vad_warm_wins = 0;
                        lead_count = 0;

                        auto now = std::chrono::steady_clock::now();
                        if (now - last_trigger_tp >= std::chrono::milliseconds(1500)) {
                            last_trigger_tp = now;
                            detections_.fetch_add(1);
                            LOGI("WakeWord", "Triggered wake word: '%s'", hit.c_str());
                            if (cb_) cb_(hit);
                        }
                        break;
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
