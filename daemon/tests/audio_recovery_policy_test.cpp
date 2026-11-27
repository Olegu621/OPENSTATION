#include "hw/audio_recovery_policy.hpp"

#include <cassert>
#include <cerrno>
#include <cstring>

int main() {
    using openstation::AudioRecoveryTracker;
    using openstation::CaptureFailureKind;
    using openstation::capture_error_code;
    using openstation::classify_capture_errno;

    assert(std::strcmp(capture_error_code(0), "") == 0);
    assert(std::strcmp(capture_error_code(ENOENT), "no_pcm_device") == 0);
    assert(std::strcmp(capture_error_code(ENODEV), "no_pcm_device") == 0);
    assert(std::strcmp(capture_error_code(EBUSY), "device_busy") == 0);
    assert(std::strcmp(capture_error_code(EACCES), "permission_denied") == 0);

    assert(classify_capture_errno(ENOENT) == CaptureFailureKind::NoDevice);
    assert(classify_capture_errno(EBUSY) == CaptureFailureKind::Busy);

    AudioRecoveryTracker tracker;

    // Missing sound card node must never trigger HAL restarts.
    for (int i = 0; i < 12; ++i) {
        assert(!tracker.record_failure(CaptureFailureKind::NoDevice, false, true, false, true, 10000 + i * 1000));
        assert(!tracker.record_failure(CaptureFailureKind::Busy, false, true, false, true, 25000 + i * 1000));
    }

    // Boot incomplete / mic muted / paused capture must never trigger HAL restarts.
    for (int i = 0; i < 10; ++i) {
        assert(!tracker.record_failure(CaptureFailureKind::Busy, true, true, false, false, 40000 + i * 1000));
        assert(!tracker.record_failure(CaptureFailureKind::Busy, true, true, true, true, 55000 + i * 1000));
        assert(!tracker.record_failure(CaptureFailureKind::Busy, true, false, false, true, 70000 + i * 1000));
    }

    // Consecutive EBUSY while card node exists triggers once threshold is reached.
    for (int i = 0; i < AudioRecoveryTracker::kFailureThreshold - 1; ++i) {
        assert(!tracker.record_failure(CaptureFailureKind::Busy, true, true, false, true, 100000 + i * 1000));
    }
    assert(tracker.record_failure(CaptureFailureKind::Busy, true, true, false, true, 106000));
    assert(tracker.recovery_attempts == 1);

    // Cooldown prevents restarting again too soon even after enough failures.
    for (int i = 0; i < AudioRecoveryTracker::kFailureThreshold; ++i) {
        assert(!tracker.record_failure(CaptureFailureKind::DigitalSilenceBusy, true, true, false, true, 120000 + i * 1000));
    }

    // After cooldown expires, second recovery fires immediately since failures accumulated during cooldown.
    int64_t t = 200000;
    assert(tracker.record_failure(CaptureFailureKind::DigitalSilenceBusy, true, true, false, true, t));
    assert(tracker.recovery_attempts == 2);

    // Subsequent attempts after a reset require kFailureThreshold failures once cooldown expires.
    t += AudioRecoveryTracker::kCooldownMs + 5000;
    for (int attempt = 3; attempt <= AudioRecoveryTracker::kMaxConsecutiveRestarts; ++attempt) {
        for (int i = 0; i < AudioRecoveryTracker::kFailureThreshold - 1; ++i) {
            assert(!tracker.record_failure(CaptureFailureKind::DigitalSilenceBusy, true, true, false, true, t + i * 1000));
        }
        assert(tracker.record_failure(CaptureFailureKind::DigitalSilenceBusy, true, true, false, true, t + 10000));
        assert(tracker.recovery_attempts == attempt);
        t += AudioRecoveryTracker::kCooldownMs + 5000;
    }

    // Fourth attempt without healthy reset stays capped.
    for (int i = 0; i < AudioRecoveryTracker::kFailureThreshold + 2; ++i) {
        assert(!tracker.record_failure(CaptureFailureKind::Busy, true, true, false, true, t + i * 1000));
    }

    // Healthy audio resets both consecutive failures and attempt budget.
    tracker.on_healthy_audio();
    assert(tracker.consecutive_busy_failures == 0);
    assert(tracker.recovery_attempts == 0);
    return 0;
}
