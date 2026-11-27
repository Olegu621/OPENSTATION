#pragma once

#include "hw/voice_gain_policy.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace openstation {

// Computes RMS and peak magnitude (in int16 scale 0..32768) for a normalized [-1, 1] window.
inline void compute_window_rms_and_peak_i16(const float* samples, size_t count,
                                            float& out_rms_i16, float& out_peak_i16) {
    if (!samples || count == 0) {
        out_rms_i16 = 0.0f;
        out_peak_i16 = 0.0f;
        return;
    }
    double sum_sq = 0.0;
    float max_abs = 0.0f;
    for (size_t i = 0; i < count; ++i) {
        const float v = samples[i];
        sum_sq += static_cast<double>(v) * v;
        const float av = std::fabs(v);
        if (av > max_abs) max_abs = av;
    }
    out_rms_i16 = static_cast<float>(std::sqrt(sum_sq / static_cast<double>(count))) * 32768.0f;
    out_peak_i16 = max_abs * 32768.0f;
}

// Adaptive idle energy gate that skips continuous Silero VAD ONNX inference on flat room hum,
// while waking immediately on whispers or speech and holding VAD active across inter-syllable gaps.
struct WakeIdleGate {
    static constexpr int kDefaultWakeHoldWins = 22; // ~700 ms @ 32 ms/window

    float stationary_rms = 0.0f;
    bool initialized = false;
    int hold_wins = 0;

    void reset() {
        stationary_rms = 0.0f;
        initialized = false;
        hold_wins = 0;
    }

    float rms_wake_threshold(int mic_sensitivity, float audio_noise_rms) const {
        const float sens_norm = std::clamp(mic_sensitivity, 10, 100) / 100.0f;
        const float ref_rms = initialized
            ? std::clamp(stationary_rms, 25.0f, 800.0f)
            : std::clamp(audio_noise_rms, 25.0f, 800.0f);
        // High sensitivity wakes on +18%..+22% above stationary floor; lower sensitivity needs +30%.
        const float ratio = 1.32f - sens_norm * 0.14f;
        const float margin = 20.0f - sens_norm * 7.0f;
        return std::max(45.0f, ref_rms * ratio + margin);
    }

    float fallback_energy_rms(int mic_sensitivity, float audio_noise_rms) const {
        const float wake_thr = rms_wake_threshold(mic_sensitivity, audio_noise_rms);
        return std::max(450.0f, std::max(dialogue_activity_threshold(audio_noise_rms), wake_thr) * 1.35f);
    }

    bool should_bypass_vad(float rms_i16, float peak_i16,
                           float audio_noise_rms, int mic_sensitivity,
                           bool speaker_playing, bool kws_active,
                           int hangover_wins, bool command_open) {
        if (speaker_playing) {
            hold_wins = 0;
            return false;
        }

        if (!initialized) {
            stationary_rms = std::clamp(std::min(rms_i16, std::max(audio_noise_rms, rms_i16)), 25.0f, 800.0f);
            initialized = true;
        } else if (!kws_active && !command_open) {
            if (rms_i16 < stationary_rms) {
                stationary_rms = stationary_rms * 0.88f + rms_i16 * 0.12f;
            } else if (rms_i16 <= stationary_rms * 1.65f + 15.0f) {
                stationary_rms = stationary_rms * 0.992f + rms_i16 * 0.008f;
            }
            stationary_rms = std::clamp(stationary_rms, 20.0f, 900.0f);
        }

        if (kws_active || hangover_wins > 0 || command_open) {
            hold_wins = kDefaultWakeHoldWins;
            return false;
        }

        const float wake_thr = rms_wake_threshold(mic_sensitivity, audio_noise_rms);
        // 512-sample stationary room noise commonly hits ~3.7-4.0x RMS peaks (Gaussian tail);
        // use 4.6x wake_thr so stationary hiss stays bypassed while transients/speech wake VAD.
        const float peak_thr = std::max(260.0f, wake_thr * 4.6f);
        if (rms_i16 > wake_thr || peak_i16 > peak_thr) {
            hold_wins = kDefaultWakeHoldWins;
            return false;
        }

        if (hold_wins > 0) {
            --hold_wins;
            return false;
        }
        return true;
    }
};

} // namespace openstation
