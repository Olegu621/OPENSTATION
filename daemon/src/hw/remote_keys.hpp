#pragma once
#include <linux/input.h>
#include <chrono>
#include <string>

namespace openstation {
inline bool is_remote_assistant_key(const std::string& name, int code) {
    const bool yandex_remote = name.find("Yandex Remote") != std::string::npos ||
                               name.find("YNDX-0006") != std::string::npos;
    return yandex_remote && (code == KEY_SEARCH || code == KEY_VOICECOMMAND || code == KEY_ASSISTANT);
}

enum class RemoteAssistantGesture { None, Short, Long };

// Each input device owns its own state; repeats cannot trigger extra actions.
class RemoteAssistantPress {
public:
    using Clock = std::chrono::steady_clock;
    RemoteAssistantGesture event(int value, Clock::time_point now) {
        if (value == 1 && !down_) { down_ = true; long_fired_ = false; start_ = now; }
        if (value != 0 || !down_) return RemoteAssistantGesture::None;
        auto result = long_fired_ ? RemoteAssistantGesture::None :
            (now - start_ >= std::chrono::milliseconds(800) ? RemoteAssistantGesture::Long : RemoteAssistantGesture::Short);
        down_ = false;
        return result;
    }
    RemoteAssistantGesture tick(Clock::time_point now) {
        if (down_ && !long_fired_ && now - start_ >= std::chrono::milliseconds(800)) {
            long_fired_ = true;
            return RemoteAssistantGesture::Long;
        }
        return RemoteAssistantGesture::None;
    }
    bool long_active() const { return down_ && long_fired_; }
    bool pressed() const { return down_; }
private:
    bool down_ = false;
    bool long_fired_ = false;
    Clock::time_point start_{};
};

enum class RemoteGesture { None, Short, Double, Long };

// Reusable press/double-tap/long-hold detector for remote buttons (ignores EV_KEY repeats == 2).
class RemoteKeyGestureTracker {
public:
    using Clock = std::chrono::steady_clock;

    RemoteGesture event(int value,
                        Clock::time_point now,
                        bool detect_double,
                        int hold_ms = 650,
                        int double_ms = 340) {
        if (value == 1 && !down_) {
            down_ = true;
            long_fired_ = false;
            start_ = now;
            return RemoteGesture::None;
        }
        if (value != 0 || !down_) {
            return RemoteGesture::None;
        }
        down_ = false;
        if (long_fired_) {
            long_fired_ = false;
            pending_short_ = false;
            return RemoteGesture::None;
        }
        if (now - start_ >= std::chrono::milliseconds(hold_ms)) {
            pending_short_ = false;
            return RemoteGesture::Long;
        }
        if (!detect_double) {
            pending_short_ = false;
            return RemoteGesture::Short;
        }
        if (pending_short_ && (now - pending_short_tp_ <= std::chrono::milliseconds(double_ms))) {
            pending_short_ = false;
            return RemoteGesture::Double;
        }
        pending_short_ = true;
        pending_short_tp_ = now;
        return RemoteGesture::None;
    }

    RemoteGesture tick(Clock::time_point now, int hold_ms = 650, int double_ms = 340) {
        if (down_ && !long_fired_ && (now - start_ >= std::chrono::milliseconds(hold_ms))) {
            long_fired_ = true;
            pending_short_ = false;
            return RemoteGesture::Long;
        }
        if (!down_ && pending_short_ && (now - pending_short_tp_ >= std::chrono::milliseconds(double_ms))) {
            pending_short_ = false;
            return RemoteGesture::Short;
        }
        return RemoteGesture::None;
    }

    void reset() {
        down_ = false;
        long_fired_ = false;
        pending_short_ = false;
    }

    bool pressed() const { return down_; }
    bool long_active() const { return down_ && long_fired_; }

private:
    bool down_ = false;
    bool long_fired_ = false;
    Clock::time_point start_{};
    bool pending_short_ = false;
    Clock::time_point pending_short_tp_{};
};

// Hardware Mute button: 1..4 taps -> single MuteToggle (never swallowed by even count);
// 5 rapid taps -> BluetoothToggle.
class MuteButtonTracker {
public:
    using Clock = std::chrono::steady_clock;
    enum class Action { None, MuteToggle, BluetoothToggle };

    Action on_press(Clock::time_point now, int multi_window_ms = 480) {
        if (pending_ && (now - last_tap_ <= std::chrono::milliseconds(multi_window_ms))) {
            ++tap_count_;
        } else {
            tap_count_ = 1;
        }
        last_tap_ = now;
        pending_ = true;
        if (tap_count_ >= 5) {
            pending_ = false;
            tap_count_ = 0;
            return Action::BluetoothToggle;
        }
        return Action::None;
    }

    Action tick(Clock::time_point now, int expire_ms = 350) {
        if (pending_ && (now - last_tap_ >= std::chrono::milliseconds(expire_ms))) {
            pending_ = false;
            int cnt = tap_count_;
            tap_count_ = 0;
            if (cnt > 0 && cnt < 5) {
                return Action::MuteToggle;
            }
        }
        return Action::None;
    }

private:
    int tap_count_ = 0;
    bool pending_ = false;
    Clock::time_point last_tap_{};
};

}
