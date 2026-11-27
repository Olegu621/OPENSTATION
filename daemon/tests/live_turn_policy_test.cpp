#include "assistant/live_turn_policy.hpp"

#include <cassert>

int main() {
    using namespace openstation;

    // Quiet speech / whisper must still advance last voice activity in a quiet room
    // (fixed from the overly high 650 RMS threshold).
    assert(should_update_live_voice_ms(120.0f, 45.0f, false) == true);
    assert(should_update_live_voice_ms(80.0f, 45.0f, false) == false);

    // Higher ambient room noise raises the adaptive threshold above 550.
    assert(should_update_live_voice_ms(400.0f, 300.0f, false) == false);
    assert(should_update_live_voice_ms(560.0f, 300.0f, false) == true);

    // Assistant playback never refreshes user speech activity.
    assert(should_update_live_voice_ms(5000.0f, 45.0f, true) == false);

    // audioStreamEnd gates: require active listening, unsent audio, no playback,
    // fresh speech activity after last audioStreamEnd, and clamped silence timeout [600, 2000] ms.
    assert(should_send_audio_stream_end(false, true, false, 2000, 1000, 3000, 900) == false);
    assert(should_send_audio_stream_end(true, false, false, 2000, 1000, 3000, 900) == false);
    assert(should_send_audio_stream_end(true, true, true, 2000, 1000, 3000, 900) == false);
    assert(should_send_audio_stream_end(true, true, false, 1000, 1000, 3000, 900) == false);
    assert(should_send_audio_stream_end(true, true, false, 2000, 1000, 2899, 900) == false);
    assert(should_send_audio_stream_end(true, true, false, 2000, 1000, 2900, 900) == true);
    assert(should_send_audio_stream_end(true, true, false, 2000, 1000, 2599, 200) == false);
    assert(should_send_audio_stream_end(true, true, false, 2000, 1000, 2600, 200) == true);
    assert(should_send_audio_stream_end(true, true, false, 2000, 1000, 3999, 5000) == false);
    assert(should_send_audio_stream_end(true, true, false, 2000, 1000, 4000, 5000) == true);

    // Stock spotter health: 5s failover to Sherpa KWS + automatic recovery when FIFO writes resume.
    StockSpotterHealth health;
    assert(!health.healthy && !health.fallback_to_kws);
    assert(health.record_unavailable(1000) == false);
    assert(!health.fallback_to_kws);
    assert(health.record_unavailable(5999) == false);
    assert(!health.fallback_to_kws);
    assert(health.record_unavailable(6000) == true);
    assert(health.fallback_to_kws);
    assert(health.record_unavailable(7000) == false);
    assert(health.record_write_ok() == true);
    assert(health.healthy && !health.fallback_to_kws);
    assert(health.record_write_ok() == false);

    // Recovery resets unhealthy timer cleanly for any later outage.
    assert(health.record_unavailable(10000) == false);
    assert(health.record_write_ok() == false);
    assert(health.record_unavailable(10500) == false);
    assert(health.record_unavailable(15499) == false);
    assert(health.record_unavailable(15500) == true);
    assert(health.fallback_to_kws);
    health.reset();
    assert(!health.healthy && !health.fallback_to_kws && health.unhealthy_since_ms == 0);

    // Hardware-AEC barge-in gate checks.
    BargeInGate barge;
    assert(barge.feed(50.0f, 40.0f, false, true, true) == BargeInGate::Result::Pass);
    // Without VQE input, echo guard drops audio during playback even for loud spikes.
    for (int i = 0; i < 8; ++i) {
        assert(barge.feed(3000.0f, 50.0f, true, false, true) == BargeInGate::Result::Drop);
    }
    // With VQE input, low residual echo is dropped; 5 sustained speech frames trigger barge-in.
    assert(barge.feed(120.0f, 80.0f, true, true, true) == BargeInGate::Result::Drop);
    for (int i = 0; i < BargeInGate::kTriggerFrames - 1; ++i) {
        assert(barge.feed(900.0f, 80.0f, true, true, true) == BargeInGate::Result::Drop);
    }
    assert(barge.feed(900.0f, 80.0f, true, true, true) == BargeInGate::Result::TriggerBargeIn);
    // Subsequent playback frames stay unlocked until playback ends.
    assert(barge.feed(100.0f, 80.0f, true, true, true) == BargeInGate::Result::Pass);
    assert(barge.feed(100.0f, 80.0f, false, true, true) == BargeInGate::Result::Pass);
    assert(!barge.latched);

    // AI mic mute guard: block spontaneous mute on unrelated chatter, allow explicit request or unmute.
    assert(should_allow_ai_mic_mute(false, "", ""));
    assert(!should_allow_ai_mic_mute(true, "", ""));
    assert(!should_allow_ai_mic_mute(true, "хватит говорить", "включи микроволновку"));
    assert(should_allow_ai_mic_mute(true, "Выключи МИКРОФОН пожалуйста", ""));
    assert(should_allow_ai_mic_mute(true, "", "mute the mic"));

    return 0;
}
