#include "hw/audio_engine.hpp"
#include "utils/logger.hpp"

#include <aaudio/AAudio.h>
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <sound/asound.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace openstation {

namespace {

constexpr double kPi = 3.14159265358979323846;

int64_t steady_now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// Dynamic libaaudio.so symbols (Android 8/9+ native API)
struct AAudioApi {
    void* handle = nullptr;
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder**) = nullptr;
    aaudio_result_t (*builder_delete)(AAudioStreamBuilder*) = nullptr;
    void (*builder_setDirection)(AAudioStreamBuilder*, aaudio_direction_t) = nullptr;
    void (*builder_setSampleRate)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*builder_setChannelCount)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*builder_setFormat)(AAudioStreamBuilder*, aaudio_format_t) = nullptr;
    void (*builder_setSharingMode)(AAudioStreamBuilder*, aaudio_sharing_mode_t) = nullptr;
    void (*builder_setPerformanceMode)(AAudioStreamBuilder*, aaudio_performance_mode_t) = nullptr;
    void (*builder_setInputPreset)(AAudioStreamBuilder*, aaudio_input_preset_t) = nullptr;
    aaudio_result_t (*builder_openStream)(AAudioStreamBuilder*, AAudioStream**) = nullptr;
    aaudio_result_t (*stream_close)(AAudioStream*) = nullptr;
    aaudio_result_t (*stream_requestStart)(AAudioStream*) = nullptr;
    aaudio_result_t (*stream_requestStop)(AAudioStream*) = nullptr;
    aaudio_result_t (*stream_read)(AAudioStream*, void*, int32_t, int64_t) = nullptr;
    aaudio_result_t (*stream_write)(AAudioStream*, const void*, int32_t, int64_t) = nullptr;
    int32_t (*stream_getSampleRate)(AAudioStream*) = nullptr;
    int32_t (*stream_getChannelCount)(AAudioStream*) = nullptr;
    aaudio_format_t (*stream_getFormat)(AAudioStream*) = nullptr;
    const char* (*convertResultToText)(aaudio_result_t) = nullptr;

    bool load() {
        if (handle) return true;
        handle = dlopen("libaaudio.so", RTLD_NOW);
        if (!handle) {
            LOGE("AudioEngine", "dlopen(libaaudio.so) failed: %s", dlerror());
            return false;
        }
        auto load_sym = [&](auto& fn, const char* name, bool required = true) -> bool {
            fn = reinterpret_cast<std::decay_t<decltype(fn)>>(dlsym(handle, name));
            if (required && !fn) {
                LOGE("AudioEngine", "Missing symbol %s in libaaudio.so", name);
                return false;
            }
            return true;
        };
        bool ok =
            load_sym(createStreamBuilder, "AAudio_createStreamBuilder") &&
            load_sym(builder_delete, "AAudioStreamBuilder_delete") &&
            load_sym(builder_setDirection, "AAudioStreamBuilder_setDirection") &&
            load_sym(builder_setSampleRate, "AAudioStreamBuilder_setSampleRate") &&
            load_sym(builder_setChannelCount, "AAudioStreamBuilder_setChannelCount") &&
            load_sym(builder_setFormat, "AAudioStreamBuilder_setFormat") &&
            load_sym(builder_setSharingMode, "AAudioStreamBuilder_setSharingMode") &&
            load_sym(builder_setPerformanceMode, "AAudioStreamBuilder_setPerformanceMode") &&
            load_sym(builder_openStream, "AAudioStreamBuilder_openStream") &&
            load_sym(stream_close, "AAudioStream_close") &&
            load_sym(stream_requestStart, "AAudioStream_requestStart") &&
            load_sym(stream_requestStop, "AAudioStream_requestStop") &&
            load_sym(stream_read, "AAudioStream_read") &&
            load_sym(stream_write, "AAudioStream_write") &&
            load_sym(stream_getSampleRate, "AAudioStream_getSampleRate") &&
            load_sym(stream_getChannelCount, "AAudioStream_getChannelCount") &&
            load_sym(stream_getFormat, "AAudioStream_getFormat");
        load_sym(builder_setInputPreset, "AAudioStreamBuilder_setInputPreset", false);
        load_sym(convertResultToText, "AAudio_convertResultToText", false);
        return ok;
    }

    const char* err_str(aaudio_result_t r) const {
        return convertResultToText ? convertResultToText(r) : "AAudioError";
    }
};

AAudioApi g_aa;

AAudioStream* open_aaudio_input(int req_ch, int req_rate, int32_t preset) {
    AAudioStreamBuilder* b = nullptr;
    if (g_aa.createStreamBuilder(&b) != AAUDIO_OK || !b) return nullptr;
    g_aa.builder_setDirection(b, AAUDIO_DIRECTION_INPUT);
    g_aa.builder_setSampleRate(b, req_rate);
    g_aa.builder_setChannelCount(b, req_ch);
    g_aa.builder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    g_aa.builder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    g_aa.builder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    if (g_aa.builder_setInputPreset && preset >= 0) {
        g_aa.builder_setInputPreset(b, preset);
    }
    AAudioStream* s = nullptr;
    aaudio_result_t r = g_aa.builder_openStream(b, &s);
    g_aa.builder_delete(b);
    if (r != AAUDIO_OK || !s) return nullptr;
    if (g_aa.stream_getFormat(s) != AAUDIO_FORMAT_PCM_I16) {
        g_aa.stream_close(s);
        return nullptr;
    }
    return s;
}

// Compute 24 log-spaced Goertzel magnitude bands (60 Hz .. 7500 Hz on 16k PCM)
std::array<float, 24> compute_spectrum_16k(const int16_t* samples, size_t count) {
    static const float coeffs[24] = {
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 65.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 85.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 110.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 140.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 180.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 230.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 290.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 370.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 470.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 600.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 760.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 960.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 1200.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 1500.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 1900.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 2400.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 3000.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 3600.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 4200.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 4900.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 5600.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 6300.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 7000.0 / 16000.0)),
        2.0f * std::cos(static_cast<float>(2.0 * kPi * 7600.0 / 16000.0)),
    };
    std::array<float, 24> bands{};
    if (!samples || count < 64) return bands;

    for (size_t b = 0; b < 24; ++b) {
        const float coeff = coeffs[b];
        float s0 = 0.0f, s1 = 0.0f, s2 = 0.0f;
        for (size_t i = 0; i < count; ++i) {
            float x = samples[i] * (1.0f / 32768.0f);
            s0 = x + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        float power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        float mag = std::sqrt(std::max(0.0f, power)) / static_cast<float>(count);
        // Log-compress for pleasing LED response
        float scaled = std::clamp(std::log1p(mag * 45.0f) / 2.8f, 0.0f, 1.0f);
        bands[b] = scaled;
    }
    return bands;
}

void write_le32(uint8_t* p, uint32_t v) {
    p[0] = v & 0xFF;
    p[1] = (v >> 8) & 0xFF;
    p[2] = (v >> 16) & 0xFF;
    p[3] = (v >> 24) & 0xFF;
}

void write_le16(uint8_t* p, uint16_t v) {
    p[0] = v & 0xFF;
    p[1] = (v >> 8) & 0xFF;
}

} // namespace

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start(const AudioConfig& cfg, Pcm16kCallback pcm_cb, SpectrumCallback spec_cb) {
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        cfg_ = cfg;
    }
    pcm_cb_ = std::move(pcm_cb);
    spec_cb_ = std::move(spec_cb);
    wav_ring_.assign(kWavRingMax, 0);
    aec_w_.assign(kAecTaps, 0.0f);
    aec_x_.assign(kAecTaps, 0.0f);
    aec_x_clean_ = true;
    aec_hangover_ = 0;
    hp1_x_ = 0.0f;
    hp1_y_ = 0.0f;
    hp2_x_ = 0.0f;
    hp2_y_ = 0.0f;
    agc_gain_ = 8.0f;
    agc_noise_floor_ = 55.0f;
    agc_hold_frames_ = 0;
    cap_warmup_frames_ = 24;
    clean_buf_.reserve(1024 * 16);
    f48_buf_.reserve(1024);
    f48_comb_buf_.reserve(1040);
    f16_batch_buf_.reserve(400);
    mono_buf_.reserve(1024);
    combined_buf_.reserve(1040);
    out_batch_buf_.reserve(400);
    out16k_acc_.reserve(1024);
    out16k_head_ = 0;

    bool ok = g_aa.load();
    aaudio_loaded_.store(ok);
    if (!ok) {
        {
            std::lock_guard<std::mutex> lk(stat_mu_);
            capture_backend_ = "unavailable";
        }
        LOGE("AudioEngine", "Failed to initialize AAudio library");
        return false;
    }

    running_.store(true);
    cap_thread_ = std::thread(&AudioEngine::capture_loop, this);
    play_thread_ = std::thread(&AudioEngine::playback_loop, this);
    return true;
}

void AudioEngine::stop() {
    running_.store(false);
    play_cv_.notify_all();
    if (cap_thread_.joinable()) cap_thread_.join();
    if (play_thread_.joinable()) play_thread_.join();
}

void AudioEngine::update_config(const AudioConfig& cfg) {
    bool reopen = false;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        reopen = (cfg_.capture_mode != cfg.capture_mode) ||
                 (cfg_.pdm_gain != cfg.pdm_gain) ||
                 (cfg_.pdm_filter != cfg.pdm_filter);
        cfg_ = cfg;
    }
    if (reopen) {
        reopen_capture_.store(true);
    }
}

void AudioEngine::set_mic_muted(bool muted) {
    std::lock_guard<std::mutex> lk(cfg_mu_);
    cfg_.mic_muted = muted;
}

void AudioEngine::set_capture_enabled(bool enabled) {
    bool prev = capture_enabled_.exchange(enabled);
    if (prev != enabled) reopen_capture_.store(true);
}

void AudioEngine::set_ws_audio_sink(WsAudioCallback cb) {
    std::lock_guard<std::mutex> lk(ws_cb_mu_);
    ws_audio_cb_ = std::move(cb);
}

void AudioEngine::set_spectrum_needed_cb(SpectrumNeededCallback cb) {
    std::lock_guard<std::mutex> lk(spec_needed_mu_);
    spec_needed_cb_ = std::move(cb);
}

void AudioEngine::push_external_spectrum(const std::array<float, 24>& bands) {
    {
        std::lock_guard<std::mutex> lk(stat_mu_);
        ext_spectrum_ = bands;
        ext_spec_tp_ = std::chrono::steady_clock::now();
    }
    bool need = static_cast<bool>(spec_cb_);
    if (need) {
        std::lock_guard<std::mutex> lk(spec_needed_mu_);
        if (spec_needed_cb_) need = spec_needed_cb_();
    }
    if (need) spec_cb_(bands);
}

void AudioEngine::process_frame(const int16_t* interleaved, size_t frames, int ch_count, int sample_rate) {
    AudioConfig cfg_copy;
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        cfg_copy = cfg_;
    }

    // 1. DC-block & per-channel RMS telemetry (reuse persistent clean_buf_ to prevent 64KB mmap churn)
    double ch_sq[16] = {0};
    int tracked_ch = std::min(ch_count, 16);
    clean_buf_.resize(frames * static_cast<size_t>(std::max(1, tracked_ch)));
    float* clean = clean_buf_.data();
    for (size_t f = 0; f < frames; ++f) {
        const int16_t* in_row = interleaved + f * static_cast<size_t>(ch_count);
        float* out_row = clean + f * static_cast<size_t>(tracked_ch);
        for (int c = 0; c < tracked_ch; ++c) {
            float x = static_cast<float>(in_row[c]);
            float y = x - dc_prev_x_[c] + 0.995f * dc_prev_y_[c];
            dc_prev_x_[c] = x;
            dc_prev_y_[c] = y;
            out_row[c] = y;
            ch_sq[c] += static_cast<double>(y) * y;
        }
    }
    {
        std::lock_guard<std::mutex> lk(stat_mu_);
        for (int c = 0; c < 16; ++c) {
            if (c < tracked_ch && frames > 0) {
                float r = static_cast<float>(std::sqrt(ch_sq[c] / frames));
                ch_rms_[c] = ch_rms_[c] * 0.55f + r * 0.45f;
            } else {
                ch_rms_[c] *= 0.8f;
            }
        }
    }

    // Check if speaker loopback channel has active signal before running 64-tap NLMS
    bool use_aec = cfg_copy.aec_enable && tracked_ch >= 10 &&
                   cfg_copy.loopback_channel >= 0 && cfg_copy.loopback_channel < tracked_ch;
    if (use_aec && frames > 0) {
        double lb_sq = ch_sq[cfg_copy.loopback_channel];
        if (lb_sq > static_cast<double>(frames) * 25.0) {
            aec_hangover_ = 25; // ~500 ms hangover after playback stops
            aec_x_clean_ = false;
        } else if (aec_hangover_ > 0) {
            --aec_hangover_;
        } else {
            if (!aec_x_clean_) {
                std::fill(aec_x_.begin(), aec_x_.end(), 0.0f);
                aec_pos_ = 0;
                aec_x_clean_ = true;
            }
            use_aec = false;
        }
    }

    // 2. Mix/select mono channel + optional NLMS AEC in float32 (preserve full headroom before AGC)
    f48_buf_.resize(frames);
    float* f48 = f48_buf_.data();
    if (cfg_copy.mic_muted) {
        std::fill(f48_buf_.begin(), f48_buf_.end(), 0.0f);
    } else {
        constexpr size_t kAecMask = kAecTaps - 1;
        // On Station Max Amlogic AXG PDM:
        // Even channels 0, 2, 4 are high-level.
        // Odd channels (1, 3, 5) and lane 3 (6, 7) have a ~12.8 dB lower hardware scale.
        // We apply kOddGain ~ 4.4f to equalize all 7 physical microphones to identical acoustic sensitivity.
        constexpr float kOddGain = 1.0f;
        int best_c = 0;
        if (tracked_ch >= 7) {
            float max_nrms = ch_rms_[0];
            for (int c = 1; c < 7; ++c) {
                if (ch_rms_[c] > max_nrms) { max_nrms = ch_rms_[c]; best_c = c; }
            }
        }

        for (size_t f = 0; f < frames; ++f) {
            const float* samp = clean + f * static_cast<size_t>(tracked_ch);
            float mic_f = 0.0f;
            if (tracked_ch == 1) {
                mic_f = samp[0];
            } else if (cfg_copy.primary_mic_channel >= 0 && cfg_copy.primary_mic_channel < tracked_ch) {
                mic_f = samp[cfg_copy.primary_mic_channel];
            } else {
                // Adaptive Coherent Beamformer:
                // 70% direct best-microphone (zero phase smear, preserving clear dental/fricative consonants)
                // + 30% 360-degree omni sum (room presence & seamless walk-around continuity)
                float best_s = samp[best_c];
                float omni_s = (samp[0] + samp[1] + samp[2] + samp[3] +
                                samp[4] + samp[5] + samp[6]) * (1.0f / 7.0f);
                mic_f = 0.70f * best_s + 0.30f * omni_s;
            }

            // NLMS Acoustic Echo Cancellation using hardware loopback reference
            if (use_aec) {
                float ref_f = samp[cfg_copy.loopback_channel];
                aec_x_[aec_pos_] = ref_f;
                float y_hat = 0.0f;
                float power = 1e-3f;
                for (size_t k = 0; k < kAecTaps; ++k) {
                    size_t idx = (aec_pos_ + kAecTaps - k) & kAecMask;
                    float xk = aec_x_[idx];
                    y_hat += aec_w_[k] * xk;
                    power += xk * xk;
                }
                float err = mic_f - y_hat;
                float step = (0.18f * err) / (power + 5000.0f);
                for (size_t k = 0; k < kAecTaps; ++k) {
                    size_t idx = (aec_pos_ + kAecTaps - k) & kAecMask;
                    aec_w_[k] = std::clamp(aec_w_[k] + step * aec_x_[idx], -3.0f, 3.0f);
                }
                aec_pos_ = (aec_pos_ + 1) & kAecMask;
                mic_f = err;
            }

            f48[f] = mic_f;
        }
    }

    // 3. Downsample in float32 (e.g. 48000 Hz -> 16000 Hz) reusing persistent scratch buffers
    f16_batch_buf_.clear();
    if (sample_rate == 16000) {
        f48_rem_.clear();
        f16_batch_buf_.assign(f48_buf_.begin(), f48_buf_.end());
    } else if (sample_rate == 48000) {
        // 3-sample FIR box decimation (anti-aliasing lowpass before /3), preserving trailing remainder across callbacks
        f48_comb_buf_.clear();
        f48_comb_buf_.insert(f48_comb_buf_.end(), f48_rem_.begin(), f48_rem_.end());
        f48_comb_buf_.insert(f48_comb_buf_.end(), f48_buf_.begin(), f48_buf_.end());
        f48_rem_.clear();

        size_t total = f48_comb_buf_.size();
        size_t usable = (total / 3) * 3;
        f16_batch_buf_.resize(usable / 3);
        const float* comb = f48_comb_buf_.data();
        for (size_t i = 0, o = 0; i < usable; i += 3, ++o) {
            f16_batch_buf_[o] = (comb[i] + comb[i + 1] + comb[i + 2]) * (1.0f / 3.0f);
        }
        if (usable < total) {
            f48_rem_.assign(comb + usable, comb + total);
        }
    } else if (sample_rate > 0) {
        f48_rem_.clear();
        double ratio = static_cast<double>(sample_rate) / 16000.0;
        while (resamp_phase_ < static_cast<double>(frames)) {
            size_t idx = static_cast<size_t>(resamp_phase_);
            f16_batch_buf_.push_back(f48[idx]);
            resamp_phase_ += ratio;
        }
        resamp_phase_ -= static_cast<double>(frames);
    }

    // 4. 2-stage 95 Hz High-Pass Filter + Smart Far-Field Speech AGC + Gentle Sub-Floor Gate + Soft Limiter
    out_batch_buf_.resize(f16_batch_buf_.size());
    if (cfg_copy.mic_muted || f16_batch_buf_.empty()) {
        std::fill(out_batch_buf_.begin(), out_batch_buf_.end(), 0);
    } else {
        const size_t n16 = f16_batch_buf_.size();
        double raw_sq = 0.0;
        for (size_t i = 0; i < n16; ++i) {
            float x = f16_batch_buf_[i];
            float y1 = 0.964f * (hp1_y_ + x - hp1_x_);
            hp1_x_ = x;
            hp1_y_ = y1;
            float y2 = 0.964f * (hp2_y_ + y1 - hp2_x_);
            hp2_x_ = y1;
            hp2_y_ = y2;
            f16_batch_buf_[i] = y2;
            raw_sq += static_cast<double>(y2) * y2;
        }
        float raw_rms = static_cast<float>(std::sqrt(raw_sq / static_cast<double>(n16)));

        // Suppress PDM hardware filter startup transient during first ~500 ms after opening capture
        if (cap_warmup_frames_ > 0) {
            --cap_warmup_frames_;
            std::fill(out_batch_buf_.begin(), out_batch_buf_.end(), 0);
            if (cap_warmup_frames_ == 1) {
                agc_noise_floor_ = std::clamp(raw_rms, 18.0f, 350.0f);
            }
        } else {
            const float sens_norm = std::clamp(cfg_copy.mic_sensitivity, 10, 100) / 100.0f;
            const bool is_raw_multi = (tracked_ch >= 8);
            const float min_gain = 0.50f;
            const float max_gain = is_raw_multi ? (8.0f + sens_norm * 24.0f) : (1.8f + sens_norm * 5.0f);
            const float target_rms = 1800.0f + sens_norm * 800.0f; // ~2480 @ 85% sensitivity - natural speech, zero clipping
            const float idle_noise_cap = 120.0f + sens_norm * 100.0f; // ~205 pre-armed ready ceiling

            // Track idle ambient room floor slowly (never chase active speech upward)
            if (raw_rms < agc_noise_floor_ * 2.2f + 15.0f) {
                float nf_rate = (raw_rms < agc_noise_floor_) ? 0.08f : 0.015f;
                agc_noise_floor_ = std::clamp(
                    agc_noise_floor_ * (1.0f - nf_rate) + raw_rms * nf_rate,
                    15.0f, 1200.0f);
            }

            float ready_gain = std::clamp(
                idle_noise_cap / std::max(agc_noise_floor_, 18.0f),
                is_raw_multi ? 2.5f : 1.2f,
                max_gain * 0.75f);

            float speech_trig = agc_noise_floor_ * (1.18f - sens_norm * 0.12f) + 5.0f;
            bool speech_present = (raw_rms > speech_trig);

            float prev_gain = agc_gain_;
            if (speech_present) {
                float desired = std::clamp(target_rms / std::max(raw_rms, 25.0f), min_gain, max_gain);
                float alpha = (desired < agc_gain_) ? 0.45f : 0.40f; // fast responsive attack
                agc_gain_ = agc_gain_ * (1.0f - alpha) + desired * alpha;
                agc_hold_frames_ = 25; // ~500 ms hold across syllable/word gaps
            } else if (agc_hold_frames_ > 0) {
                --agc_hold_frames_;
                agc_gain_ = agc_gain_ * 0.98f + ready_gain * 0.02f;
            } else {
                agc_gain_ = agc_gain_ * 0.91f + ready_gain * 0.09f;
            }
            agc_gain_ = std::clamp(agc_gain_, min_gain, max_gain);

            // Gentle sub-floor expander ONLY in true idle silence well below speech trig
            float ns_mul = 1.0f;
            if (cfg_copy.ns_enable && !speech_present && agc_hold_frames_ == 0) {
                float ratio = raw_rms / std::max(agc_noise_floor_ * 1.15f, 15.0f);
                ns_mul = std::clamp(0.80f + 0.20f * ratio, 0.80f, 1.0f);
            }

            const float start_g = prev_gain * ns_mul;
            const float end_g = agc_gain_ * ns_mul;
            const float dg = (n16 > 0) ? ((end_g - start_g) / static_cast<float>(n16)) : 0.0f;
            constexpr float kKnee = 24000.0f;
            constexpr float kHead = 8767.0f;
            for (size_t i = 0; i < n16; ++i) {
                float g = start_g + dg * static_cast<float>(i);
                float y = f16_batch_buf_[i] * g;
                if (y > kKnee) {
                    y = kKnee + kHead * std::tanh((y - kKnee) / kHead);
                } else if (y < -kKnee) {
                    y = -kKnee - kHead * std::tanh((-y - kKnee) / kHead);
                }
                out_batch_buf_[i] = static_cast<int16_t>(std::clamp(std::lround(y), -32768L, 32767L));
            }
            last_agc_gain_.store(end_g);
            last_noise_floor_.store(agc_noise_floor_);
        }
    }

    out16k_acc_.insert(out16k_acc_.end(), out_batch_buf_.begin(), out_batch_buf_.end());

    // Emit fixed 320-sample (20 ms @ 16 kHz) frames downstream
    bool need_spec = static_cast<bool>(spec_cb_);
    if (need_spec) {
        std::lock_guard<std::mutex> lk(spec_needed_mu_);
        if (spec_needed_cb_) need_spec = spec_needed_cb_();
    }

    while (out16k_acc_.size() - out16k_head_ >= 320) {
        const int16_t* frame16 = out16k_acc_.data() + out16k_head_;
        double sq = 0.0;
        for (size_t i = 0; i < 320; ++i) {
            double v = frame16[i];
            sq += v * v;
        }
        float rms = static_cast<float>(std::sqrt(sq / 320.0));
        {
            std::lock_guard<std::mutex> lk(stat_mu_);
            mono_rms_ = mono_rms_ * 0.5f + rms * 0.5f;
        }

        // Write into WAV circular buffer
        {
            std::lock_guard<std::mutex> lk(wav_mu_);
            for (size_t i = 0; i < 320; ++i) {
                wav_ring_[wav_head_] = frame16[i];
                wav_head_ = (wav_head_ + 1) % kWavRingMax;
                if (wav_filled_ < kWavRingMax) ++wav_filled_;
            }
        }

        if (pcm_cb_) {
            pcm_cb_(frame16, 320, rms);
        }
        {
            std::lock_guard<std::mutex> lk(ws_cb_mu_);
            if (ws_audio_cb_) ws_audio_cb_(frame16, 320);
        }

        // Fallback LED spectrum ONLY if LEDs actually use spectrum AND Visualizer didn't push recently
        if (need_spec && !playing_active_.load()) {
            bool use_mic_spec = false;
            {
                std::lock_guard<std::mutex> lk(stat_mu_);
                auto age = std::chrono::steady_clock::now() - ext_spec_tp_;
                use_mic_spec = age > std::chrono::milliseconds(350);
            }
            if (use_mic_spec) {
                if (cfg_copy.mic_muted || rms < 25.0f) {
                    if (!mic_spec_zero_sent_) {
                        std::array<float, 24> zero{};
                        spec_cb_(zero);
                        mic_spec_zero_sent_ = true;
                    }
                } else {
                    mic_spec_zero_sent_ = false;
                    spec_cb_(compute_spectrum_16k(frame16, 320));
                }
            }
        }

        out16k_head_ += 320;
    }

    size_t rem16 = out16k_acc_.size() - out16k_head_;
    if (rem16 == 0) {
        out16k_acc_.clear();
    } else if (out16k_head_ > 0) {
        std::memmove(out16k_acc_.data(), out16k_acc_.data() + out16k_head_, rem16 * sizeof(int16_t));
        out16k_acc_.resize(rem16);
    }
    out16k_head_ = 0;
}

static int open_alsa_pcm_capture(const char* dev, unsigned int rate, unsigned int channels,
                                 unsigned int period_frames, unsigned int periods,
                                 unsigned int* out_period_frames = nullptr) {
    // Must pass O_NONBLOCK so snd_pcm_open returns -EBUSY immediately if audioserver holds the substream.
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0 && errno != EBUSY && errno != EAGAIN) {
        fd = open(dev, O_RDONLY | O_NONBLOCK);
    }
    if (fd < 0) return -1;

    int fl = fcntl(fd, F_GETFL, 0);
    if (fl >= 0) {
        fcntl(fd, F_SETFL, fl & ~O_NONBLOCK);
    }

    struct snd_pcm_hw_params hw {};
    hw.rmask = ~0U;
    hw.cmask = 0;
    hw.info = ~0U;
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_MASK - SNDRV_PCM_HW_PARAM_FIRST_MASK; ++i) {
        for (unsigned int& b : hw.masks[i].bits) b = ~0U;
    }
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; ++i) {
        hw.intervals[i].min = 0;
        hw.intervals[i].max = ~0U;
    }

    auto set_mask_bit = [&](int param, int bit) {
        auto& m = hw.masks[param - SNDRV_PCM_HW_PARAM_FIRST_MASK];
        std::memset(m.bits, 0, sizeof(m.bits));
        m.bits[bit >> 5] |= (1U << (bit & 31));
    };
    auto set_interval = [&](int param, unsigned int min_v, unsigned int max_v) {
        auto& iv = hw.intervals[param - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
        iv.min = min_v;
        iv.max = max_v;
        iv.integer = (min_v == max_v) ? 1 : 0;
        iv.openmin = 0;
        iv.openmax = 0;
        iv.empty = 0;
    };
    auto get_interval = [&](int param) -> const struct snd_interval& {
        return hw.intervals[param - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
    };

    set_mask_bit(SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    set_mask_bit(SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
    set_mask_bit(SNDRV_PCM_HW_PARAM_SUBFORMAT, 0);
    set_interval(SNDRV_PCM_HW_PARAM_CHANNELS, channels, channels);
    set_interval(SNDRV_PCM_HW_PARAM_RATE, rate, rate);

    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_REFINE, &hw) == 0) {
        const auto& piv = get_interval(SNDRV_PCM_HW_PARAM_PERIOD_SIZE);
        const auto& niv = get_interval(SNDRV_PCM_HW_PARAM_PERIODS);
        unsigned int p_sz = std::max(piv.min, std::min(piv.max, period_frames));
        unsigned int p_cnt = std::max(niv.min, std::min(niv.max, periods));
        set_interval(SNDRV_PCM_HW_PARAM_PERIOD_SIZE, p_sz, p_sz);
        set_interval(SNDRV_PCM_HW_PARAM_PERIODS, p_cnt, p_cnt);
    } else {
        set_interval(SNDRV_PCM_HW_PARAM_PERIOD_SIZE, period_frames, period_frames);
        set_interval(SNDRV_PCM_HW_PARAM_PERIODS, periods, periods);
    }

    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &hw) < 0) {
        int e = errno;
        LOGW("AudioEngine", "ALSA HW_PARAMS failed on %s (rate=%u ch=%u per=%u..%u): %s",
             dev, rate, channels,
             get_interval(SNDRV_PCM_HW_PARAM_PERIOD_SIZE).min,
             get_interval(SNDRV_PCM_HW_PARAM_PERIOD_SIZE).max,
             strerror(e));
        ::close(fd);
        errno = e;
        return -1;
    }

    unsigned int actual_per = get_interval(SNDRV_PCM_HW_PARAM_PERIOD_SIZE).min;
    unsigned int actual_buf = get_interval(SNDRV_PCM_HW_PARAM_BUFFER_SIZE).min;
    if (actual_per == 0) actual_per = period_frames;
    if (actual_buf < actual_per) actual_buf = actual_per * periods;
    if (out_period_frames) *out_period_frames = actual_per;

    struct snd_pcm_sw_params sw {};
    sw.proto = SNDRV_PCM_VERSION;
    sw.period_step = 1;
    sw.avail_min = actual_per;
    sw.xfer_align = 1;
    sw.start_threshold = 1;
    sw.stop_threshold = actual_buf;
    if (ioctl(fd, SNDRV_PCM_IOCTL_SW_PARAMS, &sw) < 0) {
        int e = errno;
        LOGW("AudioEngine", "ALSA SW_PARAMS failed on %s: %s", dev, strerror(e));
        ::close(fd);
        errno = e;
        return -1;
    }

    if (ioctl(fd, SNDRV_PCM_IOCTL_PREPARE) < 0) {
        int e = errno;
        ::close(fd);
        errno = e;
        return -1;
    }
    if (ioctl(fd, SNDRV_PCM_IOCTL_START) < 0) {
        int e = errno;
        ::close(fd);
        errno = e;
        return -1;
    }
    return fd;
}

void AudioEngine::run_alsa_capture_session() {
    const unsigned int rate = 48000;
    const unsigned int ch = 16;
    unsigned int period = 1024; // ~21.3 ms, power-of-2 for Amlogic AXG PDM DMA
    int fd = open_alsa_pcm_capture("/dev/snd/pcmC0D1c", rate, ch, period, 4, &period);
    if (fd < 0) {
        {
            std::lock_guard<std::mutex> lk(stat_mu_);
            capture_backend_ = "alsa_busy_fallback_aaudio";
        }
        LOGW("AudioEngine", "Direct /dev/snd/pcmC0D1c busy/unavailable (%s), sleeping 1s", strerror(errno));
        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
        return;
    }

    f48_rem_.clear();
    mono48k_rem_.clear();
    out16k_acc_.clear();
    out16k_head_ = 0;
    resamp_phase_ = 0.0;
    dc_prev_x_.fill(0.0f);
    dc_prev_y_.fill(0.0f);
    hp1_x_ = 0.0f;
    hp1_y_ = 0.0f;
    hp2_x_ = 0.0f;
    hp2_y_ = 0.0f;
    agc_gain_ = 8.0f;
    agc_noise_floor_ = 55.0f;
    agc_hold_frames_ = 0;
    cap_warmup_frames_ = 24;
    std::fill(aec_x_.begin(), aec_x_.end(), 0.0f);
    aec_pos_ = 0;
    aec_hangover_ = 0;
    aec_x_clean_ = true;
    mic_spec_zero_sent_ = false;

    {
        std::lock_guard<std::mutex> lk(stat_mu_);
        capture_backend_ = "alsa_16ch_48k";
    }
    active_sample_rate_.store(rate);
    active_channels_.store(ch);
    LOGI("AudioEngine", "Raw ALSA 16-ch capture started (/dev/snd/pcmC0D1c, 48000 Hz, 16 ch, period=%u)", period);

    std::vector<int16_t> buf(static_cast<size_t>(period) * ch);
    while (running_.load() && capture_enabled_.load() && !reopen_capture_.load()) {
        struct snd_xferi xfer {};
        xfer.buf = buf.data();
        xfer.frames = period;
        if (ioctl(fd, SNDRV_PCM_IOCTL_READI_FRAMES, &xfer) < 0) {
            if (errno == EPIPE) {
                ioctl(fd, SNDRV_PCM_IOCTL_PREPARE);
                ioctl(fd, SNDRV_PCM_IOCTL_START);
                continue;
            }
            LOGW("AudioEngine", "ALSA read error: %s", strerror(errno));
            break;
        }
        if (xfer.result > 0) {
            process_frame(buf.data(), static_cast<size_t>(xfer.result), ch, rate);
        }
    }

    ioctl(fd, SNDRV_PCM_IOCTL_DROP);
    ::close(fd);
    active_channels_.store(0);
}

void AudioEngine::capture_loop() {
    while (running_.load()) {
        reopen_capture_.store(false);
        if (!capture_enabled_.load()) {
            {
                std::lock_guard<std::mutex> lk(stat_mu_);
                capture_backend_ = "paused_for_app";
            }
            active_channels_.store(0);
            std::this_thread::sleep_for(std::chrono::milliseconds(150));
            continue;
        }

        std::string mode;
        {
            std::lock_guard<std::mutex> lk(cfg_mu_);
            mode = cfg_.capture_mode;
        }
        if (mode == "alsa_16ch") {
            run_alsa_capture_session();
            continue;
        }

        AAudioStream* stream = nullptr;
        // Proven on Station Max: GENERIC 1ch @ 16k triggers AudioFlinger + libyandexvqe hardware DSP.
        struct TryCfg { int ch; int rate; int32_t preset; const char* tag; };
        const TryCfg attempts[] = {
            {1, 16000, AAUDIO_INPUT_PRESET_GENERIC, "aaudio_1ch_16k_vqe"},
            {1, 48000, AAUDIO_INPUT_PRESET_GENERIC, "aaudio_1ch_48k_vqe"},
            {1, 16000, -1, "aaudio_1ch_16k"},
        };

        const char* opened_tag = "unknown";
        for (const auto& a : attempts) {
            stream = open_aaudio_input(a.ch, a.rate, a.preset);
            if (stream) {
                opened_tag = a.tag;
                std::lock_guard<std::mutex> lk(stat_mu_);
                capture_backend_ = a.tag;
                break;
            }
        }

        if (!stream) {
            {
                std::lock_guard<std::mutex> lk(stat_mu_);
                capture_backend_ = "error_retrying";
            }
            LOGW("AudioEngine", "Cannot open AAudio capture stream, retrying in 1.5s");
            std::this_thread::sleep_for(std::chrono::milliseconds(1500));
            continue;
        }

        f48_rem_.clear();
        mono48k_rem_.clear();
        out16k_acc_.clear();
        out16k_head_ = 0;
        resamp_phase_ = 0.0;
        dc_prev_x_.fill(0.0f);
        dc_prev_y_.fill(0.0f);
        hp1_x_ = 0.0f;
        hp1_y_ = 0.0f;
        hp2_x_ = 0.0f;
        hp2_y_ = 0.0f;
        agc_gain_ = 1.8f;
        agc_noise_floor_ = 120.0f;
        agc_hold_frames_ = 0;
        cap_warmup_frames_ = 0;
        std::fill(aec_x_.begin(), aec_x_.end(), 0.0f);
        aec_pos_ = 0;
        aec_hangover_ = 0;
        aec_x_clean_ = true;
        mic_spec_zero_sent_ = false;

        int32_t rate = g_aa.stream_getSampleRate(stream);
        int32_t ch = g_aa.stream_getChannelCount(stream);
        active_sample_rate_.store(rate);
        active_channels_.store(ch);

        aaudio_result_t sr = g_aa.stream_requestStart(stream);
        if (sr != AAUDIO_OK) {
            LOGW("AudioEngine", "AAudio requestStart failed: %s", g_aa.err_str(sr));
            g_aa.stream_close(stream);
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        LOGI("AudioEngine", "Capture started (%s, %d Hz, %d ch)", opened_tag, rate, ch);
        int32_t chunk_frames = std::max<int32_t>(160, rate / 50); // 20 ms
        std::vector<int16_t> buf(static_cast<size_t>(chunk_frames) * ch);

        while (running_.load() && capture_enabled_.load() && !reopen_capture_.load()) {
            aaudio_result_t n = g_aa.stream_read(stream, buf.data(), chunk_frames, 200000000LL);
            if (n > 0) {
                process_frame(buf.data(), static_cast<size_t>(n), ch, rate);
            } else if (n < 0) {
                LOGW("AudioEngine", "AAudio read disconnected (%d: %s), reopening", n, g_aa.err_str(n));
                break;
            }
        }

        g_aa.stream_requestStop(stream);
        g_aa.stream_close(stream);
        active_channels_.store(0);
    }
}

void AudioEngine::play_pcm(const int16_t* samples, size_t frame_count, int sample_rate, int channels) {
    if (!samples || frame_count == 0 || sample_rate <= 0 || channels <= 0) return;

    // Linear resample to 48000 Hz stereo for stable AAudio output
    const double ratio = static_cast<double>(sample_rate) / 48000.0;
    const size_t out_frames = static_cast<size_t>(std::ceil(frame_count / ratio));
    PlayChunk chunk;
    chunk.samples_48k_stereo.resize(out_frames * 2);

    for (size_t i = 0; i < out_frames; ++i) {
        double src_f = i * ratio;
        size_t i0 = std::min<size_t>(frame_count - 1, static_cast<size_t>(src_f));
        size_t i1 = std::min<size_t>(frame_count - 1, i0 + 1);
        double frac = src_f - static_cast<double>(i0);

        auto get_s = [&](size_t f, int c) -> float {
            int cc = std::min(c, channels - 1);
            return static_cast<float>(samples[f * channels + cc]);
        };
        float l = static_cast<float>(get_s(i0, 0) * (1.0 - frac) + get_s(i1, 0) * frac);
        float r = channels > 1
                      ? static_cast<float>(get_s(i0, 1) * (1.0 - frac) + get_s(i1, 1) * frac)
                      : l;
        chunk.samples_48k_stereo[i * 2] = static_cast<int16_t>(std::clamp(std::lround(l), -32768L, 32767L));
        chunk.samples_48k_stereo[i * 2 + 1] = static_cast<int16_t>(std::clamp(std::lround(r), -32768L, 32767L));
    }

    {
        std::lock_guard<std::mutex> lk(play_mu_);
        chunk.epoch = playback_epoch_.load();
        while (play_q_.size() >= kMaxPlayQueueChunks) {
            play_q_.pop();
        }
        play_q_.push(std::move(chunk));
    }
    play_cv_.notify_one();
}

void AudioEngine::clear_playback_queue() {
    {
        std::lock_guard<std::mutex> lk(play_mu_);
        playback_epoch_.fetch_add(1);
        while (!play_q_.empty()) {
            play_q_.pop();
        }
        playback_rms_.store(0.0f);
    }
    play_cv_.notify_all();
}

bool AudioEngine::is_playback_or_tail_active(int tail_ms) const {
    if (playing_active_.load()) return true;
    int64_t end_ms = last_playback_end_ms_.load();
    if (end_ms <= 0) return false;
    return (steady_now_ms() - end_ms) < static_cast<int64_t>(tail_ms);
}

void AudioEngine::play_wake_chime() {
    // Crisp 2-note ascending tone (~180 ms @ 48 kHz)
    const int sr = 48000;
    const int n1 = sr * 75 / 1000;
    const int n2 = sr * 105 / 1000;
    const int total = n1 + n2;
    std::vector<int16_t> pcm(static_cast<size_t>(total) * 2);
    double phase = 0.0;
    for (int i = 0; i < total; ++i) {
        double f = (i < n1) ? 659.25 : 987.77; // E5 -> B5
        phase += 2.0 * kPi * f / sr;
        int local = (i < n1) ? i : (i - n1);
        int seg_len = (i < n1) ? n1 : n2;
        double env = 1.0;
        if (local < 240) env = local / 240.0;
        else if (local > seg_len - 480) env = std::max(0.0, (seg_len - local) / 480.0);
        int16_t v = static_cast<int16_t>(std::sin(phase) * env * 5000.0);
        pcm[i * 2] = v;
        pcm[i * 2 + 1] = v;
    }
    play_pcm(pcm.data(), static_cast<size_t>(total), sr, 2);
}

void AudioEngine::play_error_chime() {
    // Soft descending tone (~220 ms @ 48 kHz)
    const int sr = 48000;
    const int n1 = sr * 95 / 1000;
    const int n2 = sr * 125 / 1000;
    const int total = n1 + n2;
    std::vector<int16_t> pcm(static_cast<size_t>(total) * 2);
    double phase = 0.0;
    for (int i = 0; i < total; ++i) {
        double f = (i < n1) ? 440.0 : 311.13; // A4 -> D#4
        phase += 2.0 * kPi * f / sr;
        int local = (i < n1) ? i : (i - n1);
        int seg_len = (i < n1) ? n1 : n2;
        double env = 1.0;
        if (local < 240) env = local / 240.0;
        else if (local > seg_len - 560) env = std::max(0.0, (seg_len - local) / 560.0);
        int16_t v = static_cast<int16_t>(std::sin(phase) * env * 4500.0);
        pcm[i * 2] = v;
        pcm[i * 2 + 1] = v;
    }
    play_pcm(pcm.data(), static_cast<size_t>(total), sr, 2);
}

void AudioEngine::play_timer_alarm() {
    // Cheerful 3-cycle C5-E5-G5 alarm (~1.2s @ 48 kHz)
    const int sr = 48000;
    const double freqs[4] = {523.25, 659.25, 783.99, 1046.50};
    const int note_n = sr * 90 / 1000;
    const int gap_n = sr * 60 / 1000;
    const int cycle_n = note_n * 3 + gap_n;
    const int total = cycle_n * 3;
    std::vector<int16_t> pcm(static_cast<size_t>(total) * 2, 0);
    for (int rep = 0; rep < 3; ++rep) {
        int base = rep * cycle_n;
        double phase = 0.0;
        for (int note = 0; note < 3; ++note) {
            double f = freqs[note + (rep == 2 ? 1 : 0)];
            int nbase = base + note * note_n;
            for (int k = 0; k < note_n; ++k) {
                phase += 2.0 * kPi * f / sr;
                double env = 1.0;
                if (k < 240) env = k / 240.0;
                else if (k > note_n - 480) env = std::max(0.0, (note_n - k) / 480.0);
                int16_t v = static_cast<int16_t>(std::sin(phase) * env * 7800.0);
                int idx = (nbase + k) * 2;
                pcm[idx] = v;
                pcm[idx + 1] = v;
            }
        }
    }
    play_pcm(pcm.data(), static_cast<size_t>(total), sr, 2);
}

void AudioEngine::play_test_tone(int freq_hz, int duration_ms, bool sweep) {
    const int sr = 48000;
    int total = (sr * std::clamp(duration_ms, 100, 4000)) / 1000;
    std::vector<int16_t> pcm(static_cast<size_t>(total) * 2);
    double phase = 0.0;
    for (int i = 0; i < total; ++i) {
        double f = sweep ? (90.0 + (4500.0 * i) / total) : static_cast<double>(freq_hz);
        phase += 2.0 * kPi * f / sr;
        double env = 1.0;
        if (i < 1200) env = i / 1200.0;
        else if (i > total - 1500) env = (total - i) / 1500.0;
        int16_t v = static_cast<int16_t>(std::sin(phase) * env * 12000.0);
        pcm[i * 2] = v;
        pcm[i * 2 + 1] = v;
    }
    play_pcm(pcm.data(), total, sr, 2);
}

void AudioEngine::playback_loop() {
    while (running_.load()) {
        PlayChunk chunk;
        {
            std::unique_lock<std::mutex> lk(play_mu_);
            play_cv_.wait(lk, [&] { return !running_.load() || !play_q_.empty(); });
            if (!running_.load()) break;
            chunk = std::move(play_q_.front());
            play_q_.pop();
        }

        uint64_t active_epoch = chunk.epoch;
        if (active_epoch != playback_epoch_.load()) {
            continue;
        }

        AAudioStreamBuilder* b = nullptr;
        if (g_aa.createStreamBuilder(&b) != AAUDIO_OK || !b) continue;
        g_aa.builder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        g_aa.builder_setSampleRate(b, 48000);
        g_aa.builder_setChannelCount(b, 2);
        g_aa.builder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
        g_aa.builder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        g_aa.builder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);

        AAudioStream* out_s = nullptr;
        aaudio_result_t r = g_aa.builder_openStream(b, &out_s);
        g_aa.builder_delete(b);
        if (r != AAUDIO_OK || !out_s) {
            LOGW("AudioEngine", "Failed to open playback AAudio stream: %s", g_aa.err_str(r));
            continue;
        }
        if (g_aa.stream_requestStart(out_s) != AAUDIO_OK) {
            g_aa.stream_close(out_s);
            continue;
        }

        playing_active_.store(true);
        std::vector<int16_t> mono16k;
        mono16k.reserve(512);
        auto write_chunk = [&](const std::vector<int16_t>& buf, uint64_t ep) -> bool {
            size_t total_frames = buf.size() / 2;
            size_t off = 0;
            bool need_spec = static_cast<bool>(spec_cb_);
            if (need_spec) {
                std::lock_guard<std::mutex> lk(spec_needed_mu_);
                if (spec_needed_cb_) need_spec = spec_needed_cb_();
            }
            while (off < total_frames && running_.load() && playback_epoch_.load() == ep) {
                int32_t to_write = static_cast<int32_t>(std::min<size_t>(1024, total_frames - off));
                double sq = 0.0;
                mono16k.clear();
                for (int32_t k = 0; k < to_write; ++k) {
                    int32_t m = (static_cast<int32_t>(buf[(off + k) * 2]) + buf[(off + k) * 2 + 1]) / 2;
                    sq += static_cast<double>(m) * m;
                    if (need_spec && (k % 3) == 0) {
                        mono16k.push_back(static_cast<int16_t>(m));
                    }
                }
                float rms = static_cast<float>(std::sqrt(sq / std::max(1, to_write)));
                playback_rms_.store(std::clamp(rms / 9000.0f, 0.0f, 1.0f));
                if (need_spec && mono16k.size() >= 64) {
                    bool ext_fresh = false;
                    {
                        std::lock_guard<std::mutex> lk(stat_mu_);
                        ext_fresh = (std::chrono::steady_clock::now() - ext_spec_tp_) <= std::chrono::milliseconds(350);
                    }
                    if (!ext_fresh) {
                        spec_cb_(compute_spectrum_16k(mono16k.data(), mono16k.size()));
                    }
                }

                aaudio_result_t wr = g_aa.stream_write(out_s, buf.data() + off * 2, to_write, 1000000000LL);
                if (wr <= 0) break;
                off += static_cast<size_t>(wr);
                last_playback_end_ms_.store(steady_now_ms());
            }
            return running_.load() && playback_epoch_.load() == ep;
        };

        bool ok_stream = write_chunk(chunk.samples_48k_stereo, active_epoch);
        // Drain contiguous queued TTS chunks without reopening stream
        while (ok_stream && running_.load() && playback_epoch_.load() == active_epoch) {
            PlayChunk next_c;
            {
                std::unique_lock<std::mutex> lk(play_mu_);
                if (play_q_.empty()) {
                    play_cv_.wait_for(lk, std::chrono::milliseconds(80),
                                      [&] {
                                          return !running_.load() ||
                                                 !play_q_.empty() ||
                                                 playback_epoch_.load() != active_epoch;
                                      });
                }
                if (play_q_.empty() || !running_.load() || playback_epoch_.load() != active_epoch) break;
                next_c = std::move(play_q_.front());
                play_q_.pop();
            }
            if (next_c.epoch != active_epoch) break;
            ok_stream = write_chunk(next_c.samples_48k_stereo, active_epoch);
        }

        playback_rms_.store(0.0f);
        last_playback_end_ms_.store(steady_now_ms());
        playing_active_.store(false);
        g_aa.stream_requestStop(out_s);
        g_aa.stream_close(out_s);
    }
}

std::vector<uint8_t> AudioEngine::export_recent_wav(int seconds) {
    seconds = std::clamp(seconds, 1, 10);
    size_t want = static_cast<size_t>(seconds) * 16000;
    std::vector<int16_t> samples;
    {
        std::lock_guard<std::mutex> lk(wav_mu_);
        size_t take = std::min(want, wav_filled_);
        samples.resize(take);
        size_t start = (wav_head_ + kWavRingMax - take) % kWavRingMax;
        for (size_t i = 0; i < take; ++i) {
            samples[i] = wav_ring_[(start + i) % kWavRingMax];
        }
    }

    uint32_t data_bytes = static_cast<uint32_t>(samples.size() * sizeof(int16_t));
    std::vector<uint8_t> wav(44 + data_bytes);
    memcpy(wav.data() + 0, "RIFF", 4);
    write_le32(wav.data() + 4, 36 + data_bytes);
    memcpy(wav.data() + 8, "WAVE", 4);
    memcpy(wav.data() + 12, "fmt ", 4);
    write_le32(wav.data() + 16, 16);
    write_le16(wav.data() + 20, 1);     // PCM
    write_le16(wav.data() + 22, 1);     // Mono
    write_le32(wav.data() + 24, 16000); // Rate
    write_le32(wav.data() + 28, 32000); // ByteRate
    write_le16(wav.data() + 32, 2);     // BlockAlign
    write_le16(wav.data() + 34, 16);    // BitsPerSample
    memcpy(wav.data() + 36, "data", 4);
    write_le32(wav.data() + 40, data_bytes);
    if (data_bytes > 0) {
        memcpy(wav.data() + 44, samples.data(), data_bytes);
    }
    return wav;
}

json::Value AudioEngine::get_status_json() {
    using json::Value;
    Value obj = Value::make_obj();
    obj["captureEnabled"] = Value::make_bool(capture_enabled_.load());
    obj["sampleRate"] = Value::make_int(active_sample_rate_.load());
    obj["channels"] = Value::make_int(active_channels_.load());
    obj["playingTts"] = Value::make_bool(playing_active_.load());
    obj["agcGain"] = Value::make_num(std::round(last_agc_gain_.load() * 100.0) / 100.0);
    obj["noiseFloor"] = Value::make_num(std::round(last_noise_floor_.load()));

    Value meters = Value::make_arr();
    {
        std::lock_guard<std::mutex> lk(stat_mu_);
        obj["backend"] = Value::make_str(capture_backend_);
        obj["monoRms"] = Value::make_num(mono_rms_);
        for (float v : ch_rms_) {
            meters.push(Value::make_num(std::round(v)));
        }
    }
    obj["channelRms"] = std::move(meters);
    return obj;
}

void AudioEngine::run_cli_probe() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    printf("=== OpenStation Audio Probe ===\n");
    if (!g_aa.load()) {
        printf("ERROR: cannot load libaaudio.so\n");
        return;
    }

    const int cfgs[][3] = {
        {16, 48000, AAUDIO_INPUT_PRESET_UNPROCESSED},
        {16, 16000, AAUDIO_INPUT_PRESET_VOICE_RECOGNITION},
        {2, 48000, AAUDIO_INPUT_PRESET_VOICE_RECOGNITION},
        {1, 16000, AAUDIO_INPUT_PRESET_GENERIC},
    };
    for (const auto& c : cfgs) {
        AAudioStream* s = open_aaudio_input(c[0], c[1], c[2]);
        printf("Try AAudio ch=%d rate=%d preset=%d -> %s\n",
               c[0], c[1], c[2], s ? "OK" : "FAIL");
        if (s) {
            int32_t ch = g_aa.stream_getChannelCount(s);
            int32_t rate = g_aa.stream_getSampleRate(s);
            printf("  Actual: rate=%d ch=%d fmt=%d\n", rate, ch, g_aa.stream_getFormat(s));
            aaudio_result_t st = g_aa.stream_requestStart(s);
            if (st == AAUDIO_OK) {
                int32_t frames = rate / 10; // 100 ms * 5 = 500 ms
                std::vector<int16_t> buf(static_cast<size_t>(frames) * ch);
                double sq[16] = {0};
                int64_t tot_f = 0;
                for (int r = 0; r < 5; ++r) {
                    aaudio_result_t n = g_aa.stream_read(s, buf.data(), frames, 500000000LL);
                    if (n > 0) {
                        tot_f += n;
                        for (int f = 0; f < n; ++f) {
                            for (int k = 0; k < std::min(ch, 16); ++k) {
                                double v = buf[f * ch + k];
                                sq[k] += v * v;
                            }
                        }
                    }
                }
                printf("  Captured %lld frames. Per-ch RMS:\n", static_cast<long long>(tot_f));
                for (int k = 0; k < std::min(ch, 16); ++k) {
                    double rms = tot_f > 0 ? std::sqrt(sq[k] / tot_f) : 0.0;
                    printf("    ch[%2d] = %.1f\n", k, rms);
                }
                g_aa.stream_requestStop(s);
            } else {
                printf("  requestStart FAILED: %d (%s)\n", st, g_aa.err_str(st));
            }
            g_aa.stream_close(s);
        }
    }

    errno = 0;
    unsigned int probe_per = 1024;
    int pcm_fd = open_alsa_pcm_capture("/dev/snd/pcmC0D1c", 48000, 16, 1024, 4, &probe_per);
    int alsa_err = errno;
    printf("Raw ALSA 16ch /dev/snd/pcmC0D1c (period=%u): %s\n", probe_per, pcm_fd >= 0 ? "OPENED & CONFIGURED" : strerror(alsa_err));
    if (pcm_fd >= 0) {
        std::vector<int16_t> buf(probe_per * 16);
        double sq[16] = {0};
        long tot_f = 0;
        for (int r = 0; r < 10; ++r) {
            struct snd_xferi xfer {};
            xfer.buf = buf.data();
            xfer.frames = probe_per;
            if (ioctl(pcm_fd, SNDRV_PCM_IOCTL_READI_FRAMES, &xfer) == 0 && xfer.result > 0) {
                tot_f += static_cast<long>(xfer.result);
                for (long f = 0; f < static_cast<long>(xfer.result); ++f) {
                    for (int k = 0; k < 16; ++k) {
                        double v = buf[f * 16 + k];
                        sq[k] += v * v;
                    }
                }
            }
        }
        printf("  Raw ALSA captured %ld frames. Per-ch RMS:\n", tot_f);
        for (int k = 0; k < 16; ++k) {
            printf("    ch[%2d] = %.1f\n", k, tot_f > 0 ? std::sqrt(sq[k] / tot_f) : 0.0);
        }
        ioctl(pcm_fd, SNDRV_PCM_IOCTL_DROP);
        ::close(pcm_fd);
    }
    usleep(80000);
}

} // namespace openstation
