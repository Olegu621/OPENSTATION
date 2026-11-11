#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

enum class HwEventType {
    RotaryUp,
    RotaryDown,
    MuteToggle,
    ActionShort,
    ActionLong,
    JackPlugged,
    JackUnplugged
};

class InputMonitor {
public:
    using Callback = std::function<void(HwEventType)>;

    InputMonitor() = default;
    ~InputMonitor();

    bool start(Callback cb);
    void stop();

    bool headphone_plugged() const { return hp_plugged_.load(); }

private:
    enum class DevRole { Keypad, Rotary, HeadphoneJack };
    struct WatchedFd {
        int fd = -1;
        DevRole role;
        std::string name;
        std::string path;
    };

    void scan_devices();
    void poll_loop();

    Callback cb_;
    std::vector<WatchedFd> fds_;
    std::atomic<bool> running_{false};
    std::atomic<bool> hp_plugged_{false};
    std::thread worker_;
};

} // namespace openstation
