#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <thread>
#include <vector>
#include "hw/remote_keys.hpp"

namespace openstation {

enum class HwEventType {
    RotaryUp,
    RotaryDown,
    MuteToggle,
    ActionShort,
    ActionLong,
    JackPlugged,
    JackUnplugged,
    BluetoothToggle,
    HomePress,
    RemoteAssistantDown,
    RemoteAssistant,
    RemoteAssistantDouble,
    RemoteAssistantLong,
    RemoteAssistantRelease,
    RemoteLeftLong,
    RemoteRightLong,
    RemoteBackLong,
    RemotePlayDouble
};

class InputMonitor {
public:
    using Callback = std::function<void(HwEventType)>;

    InputMonitor() = default;
    ~InputMonitor();

    bool start(Callback cb);
    void stop();

    void set_remote_assistant_double_enabled(bool en) { remote_assistant_double_.store(en); }
    bool headphone_plugged() const { return hp_plugged_.load(); }

private:
    enum class DevRole { Keypad, Rotary, HeadphoneJack, RemoteControl };
    struct WatchedFd {
        int fd = -1;
        DevRole role;
        std::string name;
        std::string path;
        RemoteAssistantPress assistant_press;
        RemoteKeyGestureTracker assistant_gesture;
        RemoteKeyGestureTracker left_gesture;
        RemoteKeyGestureTracker right_gesture;
        RemoteKeyGestureTracker back_gesture;
        RemoteKeyGestureTracker play_gesture;
    };

    void scan_devices();
    void poll_loop();

    Callback cb_;
    std::vector<WatchedFd> fds_;
    std::atomic<bool> running_{false};
    std::atomic<bool> hp_plugged_{false};
    std::atomic<bool> remote_assistant_double_{true};
    std::thread worker_;
};

} // namespace openstation
