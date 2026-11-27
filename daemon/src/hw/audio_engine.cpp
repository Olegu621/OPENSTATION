#include "hw/audio_engine.hpp"
#include "hw/loopback_channels.hpp"
#include "hw/voice_gain_policy.hpp"
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
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif
#include <unistd.h>

namespace openstation {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kVqeRetrySeconds = 30;

bool is_android_boot_completed() {
#if defined(__ANDROID__)
    char buf[PROP_VALUE_MAX] {};
    return __system_property_get("sys.boot_completed", buf) > 0 && std::strcmp(buf, "1") == 0;
#else
    return true;
#endif
}

bool check_pcm_card_present() {
    return (access("/dev/snd/pcmC0D1c", F_OK) == 0) || (access("/dev/snd/pcmC0D0p", F_OK) == 0);
}

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
    if (preset >= 0 && !g_aa.builder_setInputPreset) return nullptr;
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

void decimate_to_16k(const float* in, size_t frames, int sample_rate,
                     double& resamp_phase,
                     std::vector<float>& rem48, std::vector<float>& comb_scratch,
                     std::vector<float>& out16) {
    out16.clear();
    if (!in || frames == 0 || sample_rate <= 0) return;

    if (sample_rate == 16000) {
        rem48.clear();
        out16.assign(in, in + frames);
    } else if (sample_rate == 48000) {
        // 3-sample FIR box decimation (anti-aliasing lowpass before /3), preserving trailing remainder
        comb_scratch.clear();
        comb_scratch.insert(comb_scratch.end(), rem48.begin(), rem48.end());
        comb_scratch.insert(comb_scratch.end(), in, in + frames);
        rem48.clear();

        const size_t total = comb_scratch.size();
        const size_t usable = (total / 3) * 3;
        out16.resize(usable / 3);
        const float* comb = comb_scratch.data();
        for (size_t i = 0, o = 0; i < usable; i += 3, ++o) {
            out16[o] = (comb[i] + comb[i + 1] + comb[i + 2]) * (1.0f / 3.0f);
        }
        if (usable < total) {
            rem48.assign(comb + usable, comb + total);
        }
    } else {
        rem48.clear();
        const double ratio = static_cast<double>(sample_rate) / 16000.0;
        while (resamp_phase < static_cast<double>(frames)) {
            const size_t idx = static_cast<size_t>(resamp_phase);
            out16.push_back(in[idx]);
            resamp_phase += ratio;
        }
        resamp_phase -= static_cast<double>(frames);
    }
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

bool AudioEngine::start(const AudioConfig& cfg, Pcm16kCallback pcm_cb, SpectrumCallback spec_cb,
                        InputRetryAllowedCallback input_retry_allowed,
                        HalRecoverCallback hal_recover_cb) {
    {
        std::lock_guard<std::mutex> lk(cfg_mu_);
        cfg_ = cfg;
    }
    mic_muted_.store(cfg.mic_muted);
    pcm_cb_ = std::move(pcm_cb);
    spec_cb_ = std::move(spec_cb);
    input_retry_allowed_ = std::move(input_retry_allowed);
    hal_recover_cb_ = std::move(hal_recover_cb);
    hal_recoveries_.store(0);
    {
        std::lock_guard<std::mutex> lk(stat_mu_);
        capture_backend_ = "initializing";
        capture_error_.clear();
        playback_error_.clear();
        pcm_card_present_ = true;
    }
    wav_ring_.assign(kWavRingMax, 0);
    aec_.reset(true);
    aec_x_clean_ = true;
    aec_hangover_ = 0;
    hp_mic_.reset();
    hp_ref_.reset();
    agc_gain_ = 1.0f;
    agc_noise_floor_ = 55.0f;
    agc_hold_frames_ = 0;
    cap_warmup_frames_ = 24;
    clean_buf_.reserve(1024 * 16);
    f48_buf_.reserve(1024);
    f48_comb_buf_.reserve(1040);
    f16_batch_buf_.reserve(400);
    ref48_buf_.reserve(1024);
    ref48_comb_buf_.reserve(1040);
    ref16_batch_buf_.reserve(400);
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
    {
        // Under play_mu_ so playback_loop cannot miss the wakeup between its predicate check and wait.
        std::lock_guard<std::mutex> lk(play_mu_);
        running_.store(false);
    }
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
    mic_muted_.store(cfg.mic_muted);
    if (reopen) {
        reopen_capture_.store(true);
    }
}

void AudioEngine::set_mic_muted(bool muted) {
    mic_muted_.store(muted);
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

void AudioEngine::process_frame(const int16_t* interleaved, size_t frames, int ch_count, int sample_rate,
                                bool vqe_input) {
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

    // Check if speaker loopback channels have active signal before running 16 kHz NLMS AEC
    bool use_aec = cfg_copy.aec_enable && tracked_ch >= 10 &&
                   cfg_copy.loopback_channel >= 0 && cfg_copy.loopback_channel < tracked_ch;
    const int lb_ch = cfg_copy.loopback_channel;
    const bool has_stereo_lb = is_stereo_loopback(lb_ch, tracked_ch);
    if (use_aec && frames > 0) {
        const double lb_sq = has_stereo_lb
                                 ? 0.5 * (ch_sq[lb_ch] + ch_sq[lb_ch + 1])
                                 : ch_sq[lb_ch];
        if (lb_sq > static_cast<double>(frames) * 25.0) {
            aec_hangover_ = 30; // ~600 ms hangover after playback stops
            aec_x_clean_ = false;
        } else if (aec_hangover_ > 0) {
            --aec_hangover_;
        } else {
            if (!aec_x_clean_) {
                aec_.reset_history();
                hp_ref_.reset();
                aec_x_clean_ = true;
            }
            use_aec = false;
        }
    }

    // 2. Mix/select mono mic channel and aligned loopback reference in float32
    f48_buf_.resize(frames);
    float* f48 = f48_buf_.data();
    float* ref48 = nullptr;
    if (use_aec) {
        ref48_buf_.resize(frames);
        ref48 = ref48_buf_.data();
    }

    if (cfg_copy.mic_muted) {
        std::fill(f48_buf_.begin(), f48_buf_.end(), 0.0f);
        if (use_aec) std::fill(ref48_buf_.begin(), ref48_buf_.end(), 0.0f);
    } else {
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
            } else if (tracked_ch < 7) {
                mic_f = samp[0]; // beamformer below reads channels 0..6
            } else {
                // During active speaker playback with NLMS AEC, use stationary omni sum across
                // the 7 symmetric mics: 20 ms best_c hopping shifts phase/delay every frame and
                // prevents the adaptive FIR from converging. When quiet, favor nearest mic + omni.
                float omni_s = (samp[0] + samp[1] + samp[2] + samp[3] +
                                samp[4] + samp[5] + samp[6]) * (1.0f / 7.0f);
                mic_f = use_aec ? omni_s : (0.70f * samp[best_c] + 0.30f * omni_s);
            }
            f48[f] = mic_f;

            if (use_aec) {
                ref48[f] = has_stereo_lb
                               ? 0.5f * (samp[lb_ch] + samp[lb_ch + 1])
                               : samp[lb_ch];
            }
        }
    }

    // 3. Downsample mic (and synchronized loopback ref when active) to 16 kHz
    decimate_to_16k(f48_buf_.data(), frames, sample_rate,
                    resamp_phase_, f48_rem_, f48_comb_buf_, f16_batch_buf_);
    if (use_aec && !cfg_copy.mic_muted) {
        decimate_to_16k(ref48_buf_.data(), frames, sample_rate,
                        ref_resamp_phase_, ref48_rem_, ref48_comb_buf_, ref16_batch_buf_);
    } else {
        ref48_rem_.assign(f48_rem_.size(), 0.0f);
        ref_resamp_phase_ = resamp_phase_;
    }

    // 4. 2-stage 95 Hz High-Pass Filter on mic & ref -> 16 kHz Delay-Aligned NLMS AEC -> Far-Field AGC
    out_batch_buf_.resize(f16_batch_buf_.size());
    if (cfg_copy.mic_muted || f16_batch_buf_.empty()) {
        std::fill(out_batch_buf_.begin(), out_batch_buf_.end(), 0);
    } else {
        const size_t n16 = f16_batch_buf_.size();
        for (size_t i = 0; i < n16; ++i) {
            f16_batch_buf_[i] = hp_mic_.process(f16_batch_buf_[i]);
        }
        if (use_aec) {
            const size_t nref = std::min(n16, ref16_batch_buf_.size());
            for (size_t i = 0; i < nref; ++i) {
                ref16_batch_buf_[i] = hp_ref_.process(ref16_batch_buf_[i]);
            }
            if (nref > 0) {
                aec_.process_block(f16_batch_buf_.data(), ref16_batch_buf_.data(), nref);
            }
        }

        double raw_sq = 0.0;
        for (size_t i = 0; i < n16; ++i) {
            const float y2 = f16_batch_buf_[i];
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
            const bool is_vqe = (tracked_ch <= 2);
            const float min_gain = 0.50f;
            const float max_gain = is_raw_multi ? (8.0f + sens_norm * 24.0f) :
                                   is_vqe       ? vqe_max_speech_gain(sens_norm) :
                                                  (1.8f + sens_norm * 5.0f);
            const float target_rms = is_vqe ? (850.0f + sens_norm * 350.0f) :
                                              (1800.0f + sens_norm * 800.0f);
            const float idle_noise_cap = is_vqe ? (60.0f + sens_norm * 40.0f) :
                                                  (120.0f + sens_norm * 100.0f);

            // Track idle ambient room floor slowly (never chase active speech or playback residual upward)
            if (raw_rms < agc_noise_floor_ * 2.2f + 15.0f &&
                (!use_aec || raw_rms < agc_noise_floor_)) {
                float nf_rate = (raw_rms < agc_noise_floor_) ? 0.08f : 0.015f;
                agc_noise_floor_ = std::clamp(
                    agc_noise_floor_ * (1.0f - nf_rate) + raw_rms * nf_rate,
                    15.0f, 1200.0f);
            }

            float ready_gain = std::clamp(
                idle_noise_cap / std::max(agc_noise_floor_, 18.0f),
                is_raw_multi ? 2.5f : (is_vqe ? 0.85f : 1.2f),
                is_vqe ? vqe_idle_gain_cap(sens_norm) : max_gain * 0.75f);

            float speech_trig = is_vqe ? vqe_speech_trigger(agc_noise_floor_, sens_norm)
                                       : agc_noise_floor_ * (1.18f - sens_norm * 0.12f) + 5.0f;
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
            constexpr float kKnee = 28000.0f;
            constexpr float kHead = 4767.0f;
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
            pcm_cb_(frame16, 320, rms, vqe_input);
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

static int open_alsa_pcm(const char* dev, bool is_capture, unsigned int rate, unsigned int channels,
                         unsigned int period_frames, unsigned int periods,
                         unsigned int* out_period_frames = nullptr) {
    // Must pass O_NONBLOCK so snd_pcm_open returns -EBUSY immediately if audioserver holds the substream.
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0 && is_capture && errno != EBUSY && errno != EAGAIN) {
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
    if (is_capture && ioctl(fd, SNDRV_PCM_IOCTL_START) < 0) {
        int e = errno;
        ::close(fd);
        errno = e;
        return -1;
    }
    return fd;
}

bool AudioEngine::input_retry_allowed() const {
    return !input_retry_allowed_ || input_retry_allowed_();
}

bool AudioEngine::run_alsa_capture_session(int retry_after_seconds, int* out_errno) {
    const unsigned int rate = 48000;
    const unsigned int ch = 16;
    unsigned int period = 1024; // ~21.3 ms, power-of-2 for Amlogic AXG PDM DMA
    int fd = open_alsa_pcm("/dev/snd/pcmC0D1c", true, rate, ch, period, 4, &period);
    if (fd < 0) {
        const int err = errno;
        if (out_errno) *out_errno = err;
        const bool card_present = check_pcm_card_present();
        {
            std::lock_guard<std::mutex> lk(stat_mu_);
            capture_backend_ = "alsa_unavailable";
            capture_error_ = capture_error_code(err);
            pcm_card_present_ = card_present;
        }
        LOGW("AudioEngine", "Direct /dev/snd/pcmC0D1c busy/unavailable (%s), retrying", strerror(err));
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        return false;
    }
    if (out_errno) *out_errno = 0;

    f48_rem_.clear();
    ref48_rem_.clear();
    mono48k_rem_.clear();
    out16k_acc_.clear();
    out16k_head_ = 0;
    resamp_phase_ = 0.0;
    ref_resamp_phase_ = 0.0;
    dc_prev_x_.fill(0.0f);
    dc_prev_y_.fill(0.0f);
    hp_mic_.reset();
    hp_ref_.reset();
    agc_gain_ = 1.0f;
    agc_noise_floor_ = 55.0f;
    agc_hold_frames_ = 0;
    cap_warmup_frames_ = 48; // ~1.02 s for ch[0,2,4] PDM DC offset transient to settle
    aec_.reset(true);
    aec_hangover_ = 0;
    aec_x_clean_ = true;
    mic_spec_zero_sent_ = false;

    {
        std::lock_guard<std::mutex> lk(stat_mu_);
        capture_backend_ = "alsa_16ch_48k";
        capture_error_.clear();
        pcm_card_present_ = true;
    }
    active_sample_rate_.store(rate);
    active_channels_.store(ch);
    LOGI("AudioEngine", "Raw ALSA 16-ch capture started (/dev/snd/pcmC0D1c, 48000 Hz, 16 ch, period=%u)", period);

    const auto retry_at = (retry_after_seconds > 0)
        ? (std::chrono::steady_clock::now() + std::chrono::seconds(retry_after_seconds))
        : std::chrono::steady_clock::time_point::max();

    std::vector<int16_t> buf(static_cast<size_t>(period) * ch);
    while (running_.load() && capture_enabled_.load() && !reopen_capture_.load()) {
        if (std::chrono::steady_clock::now() >= retry_at && input_retry_allowed()) {
            LOGI("AudioEngine", "Retrying Yandex VQE after using the ALSA input");
            break;
        }
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
            process_frame(buf.data(), static_cast<size_t>(xfer.result), ch, rate, false);
        }
    }

    ioctl(fd, SNDRV_PCM_IOCTL_DROP);
    ::close(fd);
    active_channels_.store(0);
    return true;
}

void AudioEngine::capture_loop() {
    bool prefer_alsa_after_silence = false;
    int alsa_short_retries = 0;
    AudioRecoveryTracker recovery_tracker;

    auto trigger_hal_recovery_if_needed = [&](CaptureFailureKind kind) {
        if (recovery_tracker.record_failure(
                kind, check_pcm_card_present(), capture_enabled_.load(),
                mic_muted_.load(), is_android_boot_completed(), steady_now_ms())) {
            hal_recoveries_.fetch_add(1);
            LOGW("AudioEngine", "Android audio HAL/audioserver wedged on pcmC0D1c; triggering clean recovery (#%d)",
                 hal_recoveries_.load());
            if (hal_recover_cb_) {
                hal_recover_cb_();
            }
        }
    };

    while (running_.load()) {
        if (reopen_capture_.exchange(false)) {
            prefer_alsa_after_silence = false;
            alsa_short_retries = 0;
        }
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
            run_alsa_capture_session(0);
            continue;
        }

        if (prefer_alsa_after_silence) {
            const auto started = std::chrono::steady_clock::now();
            int alsa_err = 0;
            const bool alsa_ok = run_alsa_capture_session(kVqeRetrySeconds, &alsa_err);
            if (alsa_ok) {
                recovery_tracker.on_healthy_audio();
            }
            if (!running_.load() || !capture_enabled_.load() || reopen_capture_.load()) continue;
            if (std::chrono::steady_clock::now() - started >= std::chrono::seconds(kVqeRetrySeconds - 2)) {
                // Recovery window finished; return to AAudio GENERIC (YandexVQE).
                prefer_alsa_after_silence = false;
                alsa_short_retries = 0;
                continue;
            }
            if (++alsa_short_retries < 3) {
                std::this_thread::sleep_for(std::chrono::milliseconds(250));
                continue;
            }
            prefer_alsa_after_silence = false;
            alsa_short_retries = 0;
            if (!alsa_ok && alsa_err != 0) {
                const CaptureFailureKind fk = (classify_capture_errno(alsa_err) == CaptureFailureKind::Busy)
                                                  ? CaptureFailureKind::DigitalSilenceBusy
                                                  : classify_capture_errno(alsa_err);
                trigger_hal_recovery_if_needed(fk);
            }
        }

        AAudioStream* stream = nullptr;
        // Prioritize GENERIC preset (stream type="mic" in /vendor/etc/audio_effects.xml) FIRST:
        // it attaches hardware-calibrated 7-mic YandexVQE (libyandexvqe.so) in audioserver and stays
        // active continuously, whereas VOICE_RECOGNITION skips YandexVQE and is silenced at ~33s.
        struct TryCfg { int ch; int rate; int32_t preset; const char* tag; };
        const TryCfg attempts[] = {
            {1, 16000, AAUDIO_INPUT_PRESET_GENERIC, "aaudio_1ch_16k_vqe"},
            {1, 48000, AAUDIO_INPUT_PRESET_GENERIC, "aaudio_1ch_48k_vqe"},
            {1, 16000, AAUDIO_INPUT_PRESET_VOICE_RECOGNITION, "aaudio_1ch_16k_voicerec"},
            {1, 48000, AAUDIO_INPUT_PRESET_VOICE_RECOGNITION, "aaudio_1ch_48k_voicerec"},
            {1, 16000, -1, "aaudio_1ch_16k"},
        };

        const char* opened_tag = "unknown";
        bool preferred_input = false;
        for (const auto& a : attempts) {
            stream = open_aaudio_input(a.ch, a.rate, a.preset);
            if (!stream) continue;
            const aaudio_result_t result = g_aa.stream_requestStart(stream);
            if (result != AAUDIO_OK) {
                LOGW("AudioEngine", "Cannot start %s (%s), trying the next input", a.tag, g_aa.err_str(result));
                g_aa.stream_close(stream);
                stream = nullptr;
                continue;
            }
            opened_tag = a.tag;
            preferred_input = a.preset == AAUDIO_INPUT_PRESET_GENERIC;
            std::lock_guard<std::mutex> lk(stat_mu_);
            capture_backend_ = a.tag;
            break;
        }

        if (!stream) {
            {
                std::lock_guard<std::mutex> lk(stat_mu_);
                capture_backend_ = "alsa_fallback";
            }
            LOGW("AudioEngine", "Cannot open AAudio capture stream, trying direct ALSA fallback...");
            int alsa_err = 0;
            const bool alsa_ok = run_alsa_capture_session(kVqeRetrySeconds, &alsa_err);
            if (alsa_ok) {
                recovery_tracker.on_healthy_audio();
            } else {
                trigger_hal_recovery_if_needed(classify_capture_errno(alsa_err));
            }
            if (!capture_enabled_.load() || reopen_capture_.load() || !running_.load()) continue;
            std::this_thread::sleep_for(std::chrono::milliseconds(1000));
            continue;
        }

        f48_rem_.clear();
        ref48_rem_.clear();
        mono48k_rem_.clear();
        out16k_acc_.clear();
        out16k_head_ = 0;
        resamp_phase_ = 0.0;
        ref_resamp_phase_ = 0.0;
        dc_prev_x_.fill(0.0f);
        dc_prev_y_.fill(0.0f);
        hp_mic_.reset();
        hp_ref_.reset();
        agc_gain_ = 1.0f;
        agc_noise_floor_ = 120.0f;
        agc_hold_frames_ = 0;
        cap_warmup_frames_ = 25; // ~0.50 s skip HAL initial transient spike
        aec_.reset(true);
        aec_hangover_ = 0;
        aec_x_clean_ = true;
        mic_spec_zero_sent_ = false;

        int32_t rate = g_aa.stream_getSampleRate(stream);
        int32_t ch = g_aa.stream_getChannelCount(stream);
        active_sample_rate_.store(rate);
        active_channels_.store(ch);

        LOGI("AudioEngine", "Capture started (%s, %d Hz, %d ch)", opened_tag, rate, ch);
        int32_t chunk_frames = std::max<int32_t>(160, rate / 50); // 20 ms
        std::vector<int16_t> buf(static_cast<size_t>(chunk_frames) * ch);
        int consecutive_zero_chunks = 0;
        int consecutive_nonzero_chunks = 0;
        bool try_alsa_on_silence = false;
        const auto retry_at = std::chrono::steady_clock::now() + std::chrono::seconds(kVqeRetrySeconds);

        while (running_.load() && capture_enabled_.load() && !reopen_capture_.load()) {
            if (!preferred_input && std::chrono::steady_clock::now() >= retry_at && input_retry_allowed()) {
                LOGI("AudioEngine", "Retrying Yandex VQE after using %s", opened_tag);
                break;
            }
            aaudio_result_t n = g_aa.stream_read(stream, buf.data(), chunk_frames, 200000000LL);
            if (n > 0) {
                int16_t max_abs = 0;
                const size_t nsamples = static_cast<size_t>(n) * static_cast<size_t>(ch);
                for (size_t i = 0; i < nsamples; i += std::max(1, ch)) {
                    int16_t v = buf[i];
                    int16_t a = (v == INT16_MIN) ? INT16_MAX : static_cast<int16_t>(std::abs(v));
                    if (a > max_abs) {
                        max_abs = a;
                        break;
                    }
                }
                if (max_abs == 0 && !mic_muted_.load()) {
                    consecutive_nonzero_chunks = 0;
                    ++consecutive_zero_chunks;
                    if (consecutive_zero_chunks >= 75) {
                        LOGW("AudioEngine", "Capture stream %s digitally silenced by Android AudioFlinger (75 zero chunks); recovering via ALSA/reopen", opened_tag);
                        try_alsa_on_silence = true;
                        break;
                    }
                } else {
                    consecutive_zero_chunks = 0;
                    if (max_abs > 0 && ++consecutive_nonzero_chunks == 5) {
                        recovery_tracker.on_healthy_audio();
                        std::lock_guard<std::mutex> lk(stat_mu_);
                        capture_error_.clear();
                        pcm_card_present_ = true;
                    }
                }
                process_frame(buf.data(), static_cast<size_t>(n), ch, rate, preferred_input);
            } else if (n < 0) {
                LOGW("AudioEngine", "AAudio read disconnected (%d: %s), reopening", n, g_aa.err_str(n));
                break;
            }
        }

        g_aa.stream_requestStop(stream);
        g_aa.stream_close(stream);
        active_channels_.store(0);
        if (try_alsa_on_silence && running_.load() && capture_enabled_.load() && !reopen_capture_.load()) {
            prefer_alsa_after_silence = true;
        }
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

void AudioEngine::play_startup_chime() {
    // Rich Station Max boot signature: 52->65 Hz woofer bloom + Liquid Glass major-9 arpeggio (~2.4s @ 48 kHz)
    const int sr = 48000;
    const int total = sr * 24 / 10; // 2.4s
    std::vector<int16_t> pcm(static_cast<size_t>(total) * 2, 0);

    struct Note { double freq; double start_s; double dur_s; double amp; double pan; };
    const Note notes[] = {
        {130.81, 0.04, 2.20, 4200.0,  0.00}, // C3 foundation
        {261.63, 0.14, 1.90, 3200.0, -0.25}, // C4
        {392.00, 0.28, 1.80, 3000.0,  0.25}, // G4
        {493.88, 0.44, 1.75, 2900.0, -0.35}, // B4
        {587.33, 0.60, 1.65, 2800.0,  0.35}, // D5
        {659.25, 0.78, 1.55, 2600.0, -0.15}, // E5
        {783.99, 0.96, 1.38, 2500.0,  0.15}, // G5
        {1046.50,1.16, 1.18, 2100.0,  0.00}, // C6 shimmer
    };

    double sub_phase = 0.0;
    for (int i = 0; i < total; ++i) {
        double t = static_cast<double>(i) / sr;
        // Sub-bass warm sweep 48 -> 65.4 Hz
        double sub_f = 48.0 + 17.41 * std::min(1.0, t / 0.85);
        sub_phase += 2.0 * kPi * sub_f / sr;
        double sub_env = (t < 0.06) ? (t / 0.06) : std::exp(-(t - 0.06) * 1.45);
        double sub_s = std::sin(sub_phase) * sub_env * 5200.0;

        double l_sum = sub_s;
        double r_sum = sub_s;
        for (const auto& n : notes) {
            if (t >= n.start_s && t < n.start_s + n.dur_s) {
                double dt = t - n.start_s;
                double atk = std::min(1.0, dt / 0.018);
                double rel = std::min(1.0, (n.start_s + n.dur_s - t) / 0.28);
                double dec = std::exp(-dt * 2.1);
                double env = atk * rel * dec;
                double s = (std::sin(2.0 * kPi * n.freq * dt) + 0.18 * std::sin(4.0 * kPi * n.freq * dt)) * env * n.amp;
                double g_l = 0.5 * (1.0 - n.pan * 0.6);
                double g_r = 0.5 * (1.0 + n.pan * 0.6);
                l_sum += s * g_l * 1.6;
                r_sum += s * g_r * 1.6;
            }
        }
        pcm[i * 2] = static_cast<int16_t>(std::clamp(static_cast<int>(std::lround(l_sum)), -31000, 31000));
        pcm[i * 2 + 1] = static_cast<int16_t>(std::clamp(static_cast<int>(std::lround(r_sum)), -31000, 31000));
    }
    play_pcm(pcm.data(), static_cast<size_t>(total), sr, 2);
}

void AudioEngine::play_bt_chime(const std::string& kind) {
    const int sr = 48000;
    struct BtTone { double freq; double start_s; double dur_s; double amp; };
    std::vector<BtTone> tones;
    double dur_s = 0.55;
    if (kind == "discoverable" || kind == "pairing") {
        tones = {
            {587.33, 0.00, 0.14, 4800.0},
            {880.00, 0.11, 0.16, 4800.0},
            {1174.66,0.23, 0.30, 4200.0},
        };
        dur_s = 0.56;
    } else if (kind == "off" || kind == "disconnected") {
        tones = {
            {783.99, 0.00, 0.13, 4500.0},
            {523.25, 0.11, 0.16, 4500.0},
            {392.00, 0.24, 0.28, 4200.0},
        };
        dur_s = 0.55;
    } else { // connected / enable
        tones = {
            {261.63, 0.00, 0.48, 3000.0},
            {523.25, 0.00, 0.18, 4600.0},
            {659.25, 0.12, 0.20, 4600.0},
            {1046.50,0.24, 0.34, 4200.0},
        };
        dur_s = 0.62;
    }

    const int total = static_cast<int>(sr * dur_s);
    std::vector<int16_t> pcm(static_cast<size_t>(total) * 2, 0);
    for (int i = 0; i < total; ++i) {
        double t = static_cast<double>(i) / sr;
        double sum = 0.0;
        for (const auto& tn : tones) {
            if (t >= tn.start_s && t < tn.start_s + tn.dur_s) {
                double dt = t - tn.start_s;
                double atk = std::min(1.0, dt / 0.008);
                double rel = std::min(1.0, (tn.start_s + tn.dur_s - t) / 0.08);
                double dec = std::exp(-dt * 4.5);
                sum += std::sin(2.0 * kPi * tn.freq * dt) * atk * rel * dec * tn.amp;
            }
        }
        int16_t v = static_cast<int16_t>(std::clamp(static_cast<int>(std::lround(sum)), -30000, 30000));
        pcm[i * 2] = v;
        pcm[i * 2 + 1] = v;
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

void AudioEngine::emit_playback_metrics_and_ref(const int16_t* stereo_48k, size_t frames, bool need_spec,
                                              int& reference_sum, int& reference_samples,
                                              std::vector<int16_t>& mono16k,
                                              std::vector<int16_t>& playback_reference) {
    if (!stereo_48k || frames == 0) return;
    double sq = 0.0;
    mono16k.clear();
    for (size_t k = 0; k < frames; ++k) {
        int32_t m = (static_cast<int32_t>(stereo_48k[k * 2]) + stereo_48k[k * 2 + 1]) / 2;
        sq += static_cast<double>(m) * m;
        if (need_spec && (k % 3) == 0) {
            mono16k.push_back(static_cast<int16_t>(m));
        }
    }
    float rms = static_cast<float>(std::sqrt(sq / static_cast<double>(std::max<size_t>(1, frames))));
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
    if (playback_reference_cb_) {
        playback_reference.clear();
        for (size_t i = 0; i < frames; ++i) {
            reference_sum += stereo_48k[i * 2] + stereo_48k[i * 2 + 1];
            if (++reference_samples == 3) {
                playback_reference.push_back(static_cast<int16_t>(reference_sum / 6));
                reference_sum = reference_samples = 0;
            }
        }
        if (!playback_reference.empty()) {
            playback_reference_cb_(playback_reference.data(), playback_reference.size());
        }
    }
}

bool AudioEngine::play_alsa_fallback(PlayChunk& chunk, bool& has_chunk, size_t& chunk_off, uint64_t active_epoch) {
    unsigned int period = 512;
    int fd = open_alsa_pcm("/dev/snd/pcmC0D0p", false, 48000, 2, period, 4, &period);
    if (fd < 0) {
        const int err = errno;
        const bool card_present = check_pcm_card_present();
        {
            std::lock_guard<std::mutex> lk(stat_mu_);
            playback_error_ = capture_error_code(err);
            pcm_card_present_ = card_present;
        }
        LOGW("AudioEngine", "Fallback ALSA /dev/snd/pcmC0D0p unavailable (%s)", strerror(err));
        has_chunk = false;
        return false;
    }

    {
        std::lock_guard<std::mutex> lk(stat_mu_);
        playback_error_.clear();
        pcm_card_present_ = true;
    }
    LOGI("AudioEngine", "Playback switched to direct ALSA /dev/snd/pcmC0D0p (48000 Hz, 2 ch, period=%u)", period);
    playing_active_.store(true);

    std::vector<int16_t> mono16k;
    mono16k.reserve(512);
    std::vector<int16_t> playback_reference;
    playback_reference.reserve(342);
    std::vector<int16_t> pad_buf(static_cast<size_t>(period) * 2, 0);
    int reference_sum = 0;
    int reference_samples = 0;

    auto write_chunk_alsa = [&](const std::vector<int16_t>& buf, size_t& off, uint64_t ep) -> bool {
        size_t total_frames = buf.size() / 2;
        bool need_spec = static_cast<bool>(spec_cb_);
        if (need_spec) {
            std::lock_guard<std::mutex> lk(spec_needed_mu_);
            if (spec_needed_cb_) need_spec = spec_needed_cb_();
        }
        while (off < total_frames && running_.load() && playback_epoch_.load() == ep) {
            size_t avail = total_frames - off;
            size_t to_send = std::min<size_t>(period, avail);
            const int16_t* ptr = buf.data() + off * 2;
            if (to_send < period) {
                std::fill(pad_buf.begin(), pad_buf.end(), 0);
                std::memcpy(pad_buf.data(), ptr, to_send * 2 * sizeof(int16_t));
                ptr = pad_buf.data();
            }
            struct snd_xferi xfer {};
            xfer.buf = const_cast<int16_t*>(ptr);
            xfer.frames = period;
            if (ioctl(fd, SNDRV_PCM_IOCTL_WRITEI_FRAMES, &xfer) < 0) {
                if (errno == EPIPE) {
                    ioctl(fd, SNDRV_PCM_IOCTL_PREPARE);
                    if (ioctl(fd, SNDRV_PCM_IOCTL_WRITEI_FRAMES, &xfer) < 0) {
                        LOGW("AudioEngine", "ALSA playback write error: %s", strerror(errno));
                        return false;
                    }
                } else {
                    LOGW("AudioEngine", "ALSA playback write error: %s", strerror(errno));
                    return false;
                }
            }
            emit_playback_metrics_and_ref(buf.data() + off * 2, to_send, need_spec,
                                          reference_sum, reference_samples, mono16k, playback_reference);
            off += to_send;
            last_playback_end_ms_.store(steady_now_ms());
        }
        return running_.load() && playback_epoch_.load() == ep;
    };

    bool ok_stream = write_chunk_alsa(chunk.samples_48k_stereo, chunk_off, active_epoch);
    if (ok_stream) {
        has_chunk = false;
        chunk_off = 0;
    }
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
        chunk = std::move(next_c);
        chunk_off = 0;
        has_chunk = !chunk.samples_48k_stereo.empty();
        if (!has_chunk) continue;
        ok_stream = write_chunk_alsa(chunk.samples_48k_stereo, chunk_off, active_epoch);
        if (ok_stream) {
            has_chunk = false;
            chunk_off = 0;
        }
    }

    if (running_.load() && playback_epoch_.load() == active_epoch) {
        ioctl(fd, SNDRV_PCM_IOCTL_DRAIN);
    } else {
        ioctl(fd, SNDRV_PCM_IOCTL_DROP);
    }
    ::close(fd);
    playback_rms_.store(0.0f);
    last_playback_end_ms_.store(steady_now_ms());
    playing_active_.store(false);
    has_chunk = false;
    return true;
}

void AudioEngine::playback_loop() {
    // Keep the current chunk across AAudio reopen attempts so a route switch
    // (e.g. Speaker <-> HDMI Out) resumes seamlessly instead of truncating TTS.
    PlayChunk chunk;
    bool has_chunk = false;
    size_t chunk_off = 0;
    int chunk_reopens = 0;

    while (running_.load()) {
        if (!has_chunk) {
            std::unique_lock<std::mutex> lk(play_mu_);
            play_cv_.wait(lk, [&] { return !running_.load() || !play_q_.empty(); });
            if (!running_.load()) break;
            chunk = std::move(play_q_.front());
            play_q_.pop();
            chunk_off = 0;
            chunk_reopens = 0;
            has_chunk = !chunk.samples_48k_stereo.empty();
        }
        if (!has_chunk) continue;

        uint64_t active_epoch = chunk.epoch;
        if (active_epoch != playback_epoch_.load()) {
            has_chunk = false;
            continue;
        }

        AAudioStreamBuilder* b = nullptr;
        if (g_aa.createStreamBuilder(&b) != AAUDIO_OK || !b) {
            has_chunk = false;
            continue;
        }
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
            if (++chunk_reopens >= 3) {
                play_alsa_fallback(chunk, has_chunk, chunk_off, active_epoch);
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(40));
            }
            continue;
        }
        aaudio_result_t start_r = g_aa.stream_requestStart(out_s);
        if (start_r != AAUDIO_OK) {
            LOGW("AudioEngine", "Failed to start playback AAudio stream: %s", g_aa.err_str(start_r));
            g_aa.stream_close(out_s);
            if (++chunk_reopens >= 3) {
                play_alsa_fallback(chunk, has_chunk, chunk_off, active_epoch);
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(40));
            }
            continue;
        }

        {
            std::lock_guard<std::mutex> lk(stat_mu_);
            playback_error_.clear();
        }
        playing_active_.store(true);
        std::vector<int16_t> mono16k;
        mono16k.reserve(512);
        std::vector<int16_t> playback_reference;
        playback_reference.reserve(342);
        int reference_sum = 0;
        int reference_samples = 0;
        auto write_chunk = [&](const std::vector<int16_t>& buf, size_t& off, uint64_t ep, bool& disconnected) -> bool {
            disconnected = false;
            size_t total_frames = buf.size() / 2;
            bool need_spec = static_cast<bool>(spec_cb_);
            if (need_spec) {
                std::lock_guard<std::mutex> lk(spec_needed_mu_);
                if (spec_needed_cb_) need_spec = spec_needed_cb_();
            }
            while (off < total_frames && running_.load() && playback_epoch_.load() == ep) {
                int32_t to_write = static_cast<int32_t>(std::min<size_t>(1024, total_frames - off));
                aaudio_result_t wr = g_aa.stream_write(out_s, buf.data() + off * 2, to_write, 1000000000LL);
                if (wr <= 0) {
                    disconnected = true;
                    return false;
                }
                emit_playback_metrics_and_ref(buf.data() + off * 2, static_cast<size_t>(wr), need_spec,
                                              reference_sum, reference_samples, mono16k, playback_reference);
                off += static_cast<size_t>(wr);
                last_playback_end_ms_.store(steady_now_ms());
            }
            return running_.load() && playback_epoch_.load() == ep;
        };

        bool disconnected = false;
        bool ok_stream = write_chunk(chunk.samples_48k_stereo, chunk_off, active_epoch, disconnected);
        if (ok_stream) {
            has_chunk = false;
            chunk_off = 0;
            chunk_reopens = 0;
        }
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
            chunk = std::move(next_c);
            chunk_off = 0;
            chunk_reopens = 0;
            has_chunk = !chunk.samples_48k_stereo.empty();
            if (!has_chunk) continue;
            ok_stream = write_chunk(chunk.samples_48k_stereo, chunk_off, active_epoch, disconnected);
            if (ok_stream) {
                has_chunk = false;
                chunk_off = 0;
            }
        }

        const bool should_retry_aaudio =
            disconnected && has_chunk && chunk_off < (chunk.samples_48k_stereo.size() / 2) &&
            running_.load() && playback_epoch_.load() == active_epoch;

        playback_rms_.store(0.0f);
        last_playback_end_ms_.store(steady_now_ms());
        playing_active_.store(false);
        g_aa.stream_requestStop(out_s);
        g_aa.stream_close(out_s);

        if (should_retry_aaudio) {
            if (++chunk_reopens < 3) {
                LOGI("AudioEngine", "Playback AAudio stream disconnected on route change; reopening (%d/3)", chunk_reopens);
                std::this_thread::sleep_for(std::chrono::milliseconds(25));
            } else {
                play_alsa_fallback(chunk, has_chunk, chunk_off, active_epoch);
            }
        } else {
            has_chunk = false;
        }
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
        obj["captureError"] = Value::make_str(capture_error_);
        obj["playbackError"] = Value::make_str(playback_error_);
        obj["pcmCardPresent"] = Value::make_bool(pcm_card_present_);
        obj["monoRms"] = Value::make_num(mono_rms_);
        for (float v : ch_rms_) {
            meters.push(Value::make_num(std::round(v)));
        }
    }
    obj["halRecoveries"] = Value::make_int(hal_recoveries_.load());
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
    int pcm_fd = open_alsa_pcm("/dev/snd/pcmC0D1c", true, 48000, 16, 1024, 4, &probe_per);
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
