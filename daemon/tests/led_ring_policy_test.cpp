#include "hw/led_ring_policy.hpp"

#include <cassert>

int main() {
    using namespace openstation;

    // Mode "off" must drop hardware backlight to 0 even when brightness slider is high,
    // unless a transient visual override (volume OSD, BT, startup, assistant) is active.
    assert(effective_ring_backlight("off", false, false, 0.9f, 220) == 0);
    assert(effective_ring_backlight("off", false, true, 0.9f, 180) == 0);
    assert(effective_ring_backlight("off", true, false, 0.0f, 160) == 160);

    // Music/voice mode: completely off during silence, lit as soon as internal audio plays.
    assert(effective_ring_backlight("music", false, false, 0.0f, 160) == 0);
    assert(effective_ring_backlight("music", false, false, 0.01f, 160) == 0);
    assert(effective_ring_backlight("music", false, false, 0.12f, 160) == 160);
    assert(effective_ring_backlight("music", false, true, 0.0f, 160) == 160);
    assert(effective_ring_backlight("voice", false, false, 0.0f, 140) == 0);

    // Steady lighting modes preserve target brightness.
    assert(effective_ring_backlight("rainbow", false, false, 0.0f, 180) == 180);
    assert(effective_ring_backlight("solid", false, false, 0.0f, 90) == 90);

    // Zero transitions must never be skipped by delta >= 2 hysteresis.
    assert(should_write_ring_backlight(-1, 0));
    assert(should_write_ring_backlight(1, 0));
    assert(should_write_ring_backlight(0, 1));
    assert(!should_write_ring_backlight(0, 0));
    assert(!should_write_ring_backlight(100, 101));
    assert(should_write_ring_backlight(100, 102));

    // Zero idle glow floor for silent music bands.
    assert(ring_music_band_level(0.0f) == 0.0f);
    assert(ring_music_band_level(0.01f) == 0.0f);
    assert(ring_music_band_level(0.5f) == 0.5f);
    assert(ring_music_band_level(1.5f) == 1.0f);
    return 0;
}
