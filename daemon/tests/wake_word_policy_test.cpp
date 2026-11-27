#include "assistant/wake_word_policy.hpp"

#include <cassert>
#include <cmath>
#include <vector>

int main() {
    using namespace openstation;

    // 1. RMS/peak helper scales [-1, 1] floats to int16 magnitude.
    std::vector<float> win(512, 0.01f); // ~327.68 RMS
    float rms = 0.0f;
    float peak = 0.0f;
    compute_window_rms_and_peak_i16(win.data(), win.size(), rms, peak);
    assert(std::fabs(rms - 327.68f) < 0.5f);
    assert(std::fabs(peak - 327.68f) < 0.5f);

    // 2. Stationary room hum with normal ~3.8x crest factor peaks must still bypass Silero VAD.
    WakeIdleGate gate;
    for (int i = 0; i < 40; ++i) {
        bool bypass = gate.should_bypass_vad(160.0f, 610.0f, 160.0f, 80, false, false, 0, false);
        assert(bypass);
    }

    // 3. Soft whisper / speech onset wakes VAD immediately and keeps hold_wins active.
    bool woke = !gate.should_bypass_vad(235.0f, 520.0f, 160.0f, 80, false, false, 0, false);
    assert(woke);
    assert(gate.hold_wins == WakeIdleGate::kDefaultWakeHoldWins);

    // Brief inter-syllable dip must NOT immediately bypass VAD while hold_wins > 0.
    assert(!gate.should_bypass_vad(155.0f, 380.0f, 160.0f, 80, false, false, 0, false));

    // After hold expires back in silence, bypass resumes cleanly.
    for (int i = 0; i < WakeIdleGate::kDefaultWakeHoldWins; ++i) {
        (void)gate.should_bypass_vad(155.0f, 380.0f, 160.0f, 80, false, false, 0, false);
    }
    assert(gate.should_bypass_vad(155.0f, 380.0f, 160.0f, 80, false, false, 0, false));

    // 4. Higher steady noise floor (like 265..780 after AGC) must NOT trigger fallback KWS forever.
    WakeIdleGate noisy;
    for (int i = 0; i < 120; ++i) {
        (void)noisy.should_bypass_vad(700.0f, 1500.0f, 700.0f, 80, false, false, 0, false);
    }
    assert(noisy.fallback_energy_rms(80, 700.0f) > 780.0f);

    return 0;
}
