#pragma once

#include "hw/voice_gain_policy.hpp"

#include <algorithm>
#include <cstdint>

namespace openstation {

// Adaptive voice-activity check for closing a Gemini Live audio stream.
// Uses the tracked noise floor so quiet speech / whispers still trigger
// audioStreamEnd while steady room noise does not hold turns open forever.
inline bool should_update_live_voice_ms(float rms, float noise_rms, bool playback_active) {
    if (playback_active) return false;
    return rms >= dialogue_activity_threshold(noise_rms);
}

inline bool should_send_audio_stream_end(bool audio_sent_since_end,
                                         bool listening_state,
                                         bool playback_active,
                                         int64_t activity_ms,
                                         int64_t last_audio_end_ms,
                                         int64_t now_ms,
                                         int vad_silence_ms) {
    if (!audio_sent_since_end || !listening_state || playback_active) return false;
    if (activity_ms <= last_audio_end_ms) return false;
    const int silence_ms = std::clamp(vad_silence_ms, 600, 2000);
    return (now_ms - activity_ms) >= silence_ms;
}

// Health & automatic Sherpa KWS fallback tracker for the chroot stock spotter.
struct StockSpotterHealth {
    static constexpr int64_t kFailoverDelayMs = 5000;

    bool healthy = false;
    bool fallback_to_kws = false;
    int64_t unhealthy_since_ms = 0;

    // Returns true if fallback_to_kws state changed and KWS settings must refresh.
    bool record_write_ok() {
        healthy = true;
        unhealthy_since_ms = 0;
        if (fallback_to_kws) {
            fallback_to_kws = false;
            return true;
        }
        return false;
    }

    // Returns true if fallback_to_kws state changed and KWS settings must refresh.
    bool record_unavailable(int64_t now_ms) {
        healthy = false;
        if (unhealthy_since_ms <= 0) {
            unhealthy_since_ms = now_ms;
            return false;
        }
        if (!fallback_to_kws && (now_ms - unhealthy_since_ms) >= kFailoverDelayMs) {
            fallback_to_kws = true;
            return true;
        }
        return false;
    }

    void reset() {
        healthy = false;
        fallback_to_kws = false;
        unhealthy_since_ms = 0;
    }
};

} // namespace openstation
