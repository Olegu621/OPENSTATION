#pragma once

#include <algorithm>
#include <cmath>
#include <string>

namespace openstation {

// Computes the effective backlight brightness for /sys/class/backlight/fb4 so that
// "off" mode shuts the ring hardware off immediately regardless of user brightness, and
// "music"/"voice" modes go completely dark during internal silence like stock Station Max.
inline int effective_ring_backlight(const std::string& mode,
                                    bool override_visual,
                                    bool muted,
                                    float peak_spectrum,
                                    int target_bl) {
    if (override_visual) return std::clamp(target_bl, 0, 255);
    if (mode == "off") return 0;
    if ((mode == "music" || mode == "voice") && !muted && peak_spectrum < 0.015f) {
        return 0;
    }
    return std::clamp(target_bl, 0, 255);
}

inline bool should_write_ring_backlight(int last_bl, int next_bl) {
    if (last_bl < 0) return true;
    if ((next_bl == 0) != (last_bl == 0)) return true;
    return std::abs(next_bl - last_bl) >= 2;
}

inline float ring_music_band_level(float band) {
    float clamped = std::clamp(band, 0.0f, 1.0f);
    return (clamped < 0.015f) ? 0.0f : clamped;
}

} // namespace openstation
