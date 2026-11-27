#pragma once

#include <cerrno>
#include <cstdint>

namespace openstation {

enum class CaptureFailureKind {
    Healthy,
    NoDevice,
    Busy,
    IoError,
    DigitalSilenceBusy,
};

inline const char* capture_error_code(int err) {
    if (err == 0) return "";
    if (err == ENOENT || err == ENODEV || err == ENXIO) return "no_pcm_device";
    if (err == EBUSY || err == EAGAIN) return "device_busy";
    if (err == EACCES || err == EPERM) return "permission_denied";
    return "io_error";
}

inline CaptureFailureKind classify_capture_errno(int err) {
    if (err == 0) return CaptureFailureKind::Healthy;
    if (err == ENOENT || err == ENODEV || err == ENXIO) return CaptureFailureKind::NoDevice;
    if (err == EBUSY || err == EAGAIN) return CaptureFailureKind::Busy;
    return CaptureFailureKind::IoError;
}

/**
 * Rate-limited decision tracker for recovering a wedged Android audio HAL + audioserver pair.
 * Never fires when sound card device nodes are missing (NoDevice / !card_node_present),
 * during early boot, while capture is paused, or when mic is muted.
 */
struct AudioRecoveryTracker {
    static constexpr int kFailureThreshold = 6;
    static constexpr int kMaxConsecutiveRestarts = 3;
    static constexpr int64_t kCooldownMs = 90000;

    int consecutive_busy_failures = 0;
    int recovery_attempts = 0;
    int64_t last_recovery_ms = 0;

    void on_healthy_audio() {
        consecutive_busy_failures = 0;
        recovery_attempts = 0;
    }

    bool record_failure(CaptureFailureKind kind,
                        bool card_node_present,
                        bool capture_enabled,
                        bool mic_muted,
                        bool boot_completed,
                        int64_t now_ms) {
        if (!card_node_present || kind == CaptureFailureKind::NoDevice) {
            consecutive_busy_failures = 0;
            return false;
        }
        if (!capture_enabled || mic_muted || !boot_completed) {
            return false;
        }
        if (kind != CaptureFailureKind::Busy && kind != CaptureFailureKind::DigitalSilenceBusy) {
            consecutive_busy_failures = 0;
            return false;
        }

        ++consecutive_busy_failures;
        if (consecutive_busy_failures < kFailureThreshold) {
            return false;
        }
        if (recovery_attempts >= kMaxConsecutiveRestarts) {
            return false;
        }
        if (last_recovery_ms > 0 && (now_ms - last_recovery_ms) < kCooldownMs) {
            return false;
        }

        last_recovery_ms = now_ms;
        ++recovery_attempts;
        consecutive_busy_failures = 0;
        return true;
    }
};

} // namespace openstation
