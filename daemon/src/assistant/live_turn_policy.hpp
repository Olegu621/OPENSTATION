#pragma once

#include "hw/voice_gain_policy.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <string>

namespace openstation {

// Adaptive voice-activity check for closing a Gemini Live audio stream.
// Uses the tracked noise floor so quiet speech / whispers still trigger
// audioStreamEnd while steady room noise does not hold turns open forever.
inline bool should_update_live_voice_ms(float rms, float noise_rms, bool playback_active) {
    if (playback_active) return false;
    return rms >= dialogue_activity_threshold(noise_rms);
}

// Client-side end of speech. The server runs its own VAD with
// silenceDurationMs == vad_silence_ms, so this end must lead it by a margin:
// an audioStreamEnd arriving after the server already started the reply is
// taken as a new (empty) user turn and cuts the generation short.
// model_audio_ms: when the model last produced audio; no end is sent unless the
// user spoke after that, so room noise after a reply never opens a turn.
inline bool should_send_audio_stream_end(bool audio_sent_since_end,
                                         bool listening_state,
                                         bool playback_active,
                                         int64_t activity_ms,
                                         int64_t last_audio_end_ms,
                                         int64_t model_audio_ms,
                                         int64_t now_ms,
                                         int vad_silence_ms) {
    if (!audio_sent_since_end || !listening_state || playback_active) return false;
    if (activity_ms <= last_audio_end_ms) return false;
    if (activity_ms <= model_audio_ms) return false;
    const int silence_ms = std::clamp(vad_silence_ms, 600, 2000);
    return (now_ms - activity_ms) >= std::max(600, silence_ms - 500);
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

// Hardware-AEC barge-in gate:
// - If echo_guard is disabled: always passes mic audio.
// - During playback without hardware VQE AEC: blocks mic to prevent feedback.
// - During playback with VQE AEC and voice_barge_in off: blocks mic, never triggers
//   (for rooms where TV or voice chat would otherwise cut every reply).
// - During playback with VQE AEC: holds low residual echo back, then triggers an immediate
//   barge-in edge + unlocks streaming once sustained user speech exceeds the AEC floor.
struct BargeInGate {
    static constexpr int kTriggerFrames = 5; // ~100 ms of sustained speech at 20 ms chunks
    enum class Result { Drop, Pass, TriggerBargeIn };

    int speech_frames = 0;
    bool latched = false;

    void reset() {
        speech_frames = 0;
        latched = false;
    }

    Result feed(float rms,
                float noise_rms,
                bool playback_active,
                bool vqe_input,
                bool echo_guard,
                bool voice_barge_in) {
        if (!echo_guard) {
            reset();
            return Result::Pass;
        }
        if (!playback_active) {
            reset();
            return Result::Pass;
        }
        if (!vqe_input || !voice_barge_in) {
            speech_frames = 0;
            return Result::Drop;
        }
        if (latched) {
            return Result::Pass;
        }
        const float thresh = std::max(dialogue_activity_threshold(noise_rms) * 1.45f,
                                      noise_rms + 260.0f);
        if (rms >= thresh) {
            if (++speech_frames >= kTriggerFrames) {
                latched = true;
                return Result::TriggerBargeIn;
            }
        } else if (speech_frames > 0) {
            --speech_frames;
        }
        return Result::Drop;
    }
};

inline std::string ascii_and_ru_lower(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(in[i]);
        if (c < 0x80) {
            out.push_back(static_cast<char>(std::tolower(c)));
        } else if (c == 0xD0 && i + 1 < in.size()) {
            unsigned char n = static_cast<unsigned char>(in[i + 1]);
            if (n == 0x81) {
                out.push_back(static_cast<char>(0xD0));
                out.push_back(static_cast<char>(0xB5)); // Ё -> е
            } else if (n >= 0x90 && n <= 0x9F) {
                out.push_back(static_cast<char>(0xD0));
                out.push_back(static_cast<char>(n + 0x20)); // А..П -> а..п
            } else if (n >= 0xA0 && n <= 0xAF) {
                out.push_back(static_cast<char>(0xD1));
                out.push_back(static_cast<char>(n - 0x20)); // Р..Я -> р..я
            } else {
                out.push_back(static_cast<char>(c));
                out.push_back(static_cast<char>(n));
            }
            ++i;
        } else if (c == 0xD1 && i + 1 < in.size()) {
            unsigned char n = static_cast<unsigned char>(in[i + 1]);
            if (n == 0x91) {
                out.push_back(static_cast<char>(0xD0));
                out.push_back(static_cast<char>(0xB5)); // ё -> е
            } else {
                out.push_back(static_cast<char>(c));
                out.push_back(static_cast<char>(n));
            }
            ++i;
        } else {
            out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

inline bool contains_word_ascii(const std::string& hay, const std::string& word) {
    size_t pos = 0;
    while ((pos = hay.find(word, pos)) != std::string::npos) {
        const bool left_ok = (pos == 0) || !std::isalnum(static_cast<unsigned char>(hay[pos - 1]));
        const size_t end = pos + word.size();
        const bool right_ok = (end >= hay.size()) || !std::isalnum(static_cast<unsigned char>(hay[end]));
        if (left_ok && right_ok) return true;
        pos += 1;
    }
    return false;
}

// Guard against the model spontaneously calling set_mic_mute(true) on background noise
// or "хватит/потише"; unmuting is always allowed, muting requires explicit mic-off phrasing.
inline bool should_allow_ai_mic_mute(bool requested_mute,
                                     const std::string& turn_user_text,
                                     const std::string& last_user_text) {
    if (!requested_mute) return true;
    auto has_explicit_mic = [](const std::string& raw) -> bool {
        if (raw.empty()) return false;
        const std::string lo = ascii_and_ru_lower(raw);
        return lo.find("микрофон") != std::string::npos ||
               lo.find("мьют") != std::string::npos ||
               lo.find("замут") != std::string::npos ||
               contains_word_ascii(lo, "mic") ||
               contains_word_ascii(lo, "mute");
    };
    return has_explicit_mic(turn_user_text) || has_explicit_mic(last_user_text);
}

} // namespace openstation
