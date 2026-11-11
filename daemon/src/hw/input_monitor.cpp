#include "hw/input_monitor.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace openstation {

namespace {
constexpr int kKeyMute = 248;       // KEY_MICMUTE on gpio_keypad
constexpr int kKeyAction = 582;     // Custom Yandex Action button
constexpr int kRotaryUp = 104;      // KEY_PAGEUP on rotary-volume
constexpr int kRotaryDown = 109;    // KEY_PAGEDOWN on rotary-volume
constexpr int kSwHeadphone = 2;     // SW_HEADPHONE_INSERT
} // namespace

InputMonitor::~InputMonitor() {
    stop();
}

void InputMonitor::scan_devices() {
    DIR* dir = opendir("/dev/input");
    if (!dir) {
        LOGE("InputMonitor", "Cannot open /dev/input: %s", strerror(errno));
        return;
    }

    struct dirent* ent;
    while ((ent = readdir(dir)) != nullptr) {
        if (strncmp(ent->d_name, "event", 5) != 0) continue;
        std::string path = std::string("/dev/input/") + ent->d_name;
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        char name[128] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0) {
            ::close(fd);
            continue;
        }
        std::string sname(name);
        DevRole role;
        bool match = false;
        if (sname.find("gpio_keypad") != std::string::npos) {
            role = DevRole::Keypad;
            match = true;
        } else if (sname.find("rotary-volume") != std::string::npos) {
            role = DevRole::Rotary;
            match = true;
            // Exclusively grab rotary encoder so Android InputReader does not see phantom PAGE_UP/PAGE_DOWN
            if (ioctl(fd, EVIOCGRAB, 1) < 0) {
                LOGW("InputMonitor", "EVIOCGRAB failed on %s: %s", path.c_str(), strerror(errno));
            }
        } else if (sname.find("Headphones") != std::string::npos) {
            role = DevRole::HeadphoneJack;
            match = true;
            // Read initial SW state
            uint8_t sw_bits[16] = {0};
            if (ioctl(fd, EVIOCGSW(sizeof(sw_bits)), sw_bits) >= 0) {
                bool inserted = (sw_bits[kSwHeadphone / 8] & (1 << (kSwHeadphone % 8))) != 0;
                hp_plugged_.store(inserted);
                LOGI("InputMonitor", "Initial 3.5mm jack state: %s", inserted ? "plugged" : "unplugged");
            }
        }

        if (match) {
            LOGI("InputMonitor", "Watching %s (%s)", path.c_str(), sname.c_str());
            fds_.push_back(WatchedFd{fd, role, sname, path});
        } else {
            ::close(fd);
        }
    }
    closedir(dir);
}

bool InputMonitor::start(Callback cb) {
    cb_ = std::move(cb);
    scan_devices();
    running_.store(true);
    worker_ = std::thread(&InputMonitor::poll_loop, this);
    return true;
}

void InputMonitor::stop() {
    running_.store(false);
    if (worker_.joinable()) {
        worker_.join();
    }
    for (auto& w : fds_) {
        if (w.fd >= 0) {
            if (w.role == DevRole::Rotary) {
                ioctl(w.fd, EVIOCGRAB, 0);
            }
            ::close(w.fd);
        }
    }
    fds_.clear();
}

void InputMonitor::poll_loop() {
    using clock = std::chrono::steady_clock;
    bool action_down = false;
    bool action_long_fired = false;
    clock::time_point action_down_tp;

    while (running_.load()) {
        std::vector<struct pollfd> pfds;
        pfds.reserve(fds_.size());
        for (const auto& w : fds_) {
            struct pollfd pfd {};
            pfd.fd = w.fd;
            pfd.events = POLLIN;
            pfds.push_back(pfd);
        }
        int ret = pfds.empty() ? (usleep(100000), 0) : poll(pfds.data(), pfds.size(), 100);
        auto now = clock::now();

        if (action_down && !action_long_fired &&
            now - action_down_tp >= std::chrono::milliseconds(800)) {
            action_long_fired = true;
            LOGI("InputMonitor", "Action button long press");
            if (cb_) cb_(HwEventType::ActionLong);
        }

        if (ret < 0) {
            if (errno == EINTR) continue;
            usleep(50000);
            continue;
        }
        if (ret == 0) continue;

        bool removed_any = false;
        for (size_t i = 0; i < pfds.size(); ++i) {
            short evm = pfds[i].revents;
            if (evm & (POLLERR | POLLHUP | POLLNVAL)) {
                LOGW("InputMonitor", "Closing dead input fd %s (%s)",
                     fds_[i].path.c_str(), fds_[i].name.c_str());
                ::close(fds_[i].fd);
                fds_[i].fd = -1;
                removed_any = true;
                continue;
            }
            if (!(evm & POLLIN)) continue;
            struct input_event ev {};
            while (true) {
                ssize_t nr = read(fds_[i].fd, &ev, sizeof(ev));
                if (nr == 0) {
                    LOGW("InputMonitor", "EOF on input %s", fds_[i].path.c_str());
                    ::close(fds_[i].fd);
                    fds_[i].fd = -1;
                    removed_any = true;
                    break;
                }
                if (nr < 0) {
                    if (errno == EINTR) continue;
                    if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                    LOGW("InputMonitor", "Read error on %s: %s", fds_[i].path.c_str(), strerror(errno));
                    ::close(fds_[i].fd);
                    fds_[i].fd = -1;
                    removed_any = true;
                    break;
                }
                if (nr != sizeof(ev)) break;
                if (fds_[i].role == DevRole::Rotary && ev.type == EV_KEY && ev.value == 1) {
                    if (ev.code == kRotaryUp) {
                        if (cb_) cb_(HwEventType::RotaryUp);
                    } else if (ev.code == kRotaryDown) {
                        if (cb_) cb_(HwEventType::RotaryDown);
                    }
                } else if (fds_[i].role == DevRole::Keypad && ev.type == EV_KEY) {
                    if (ev.code == kKeyMute && ev.value == 1) {
                        LOGI("InputMonitor", "Hardware Mute button pressed");
                        if (cb_) cb_(HwEventType::MuteToggle);
                    } else if (ev.code == kKeyAction) {
                        if (ev.value == 1) {
                            action_down = true;
                            action_long_fired = false;
                            action_down_tp = clock::now();
                        } else if (ev.value == 0 && action_down) {
                            action_down = false;
                            if (!action_long_fired) {
                                LOGI("InputMonitor", "Action button short press");
                                if (cb_) cb_(HwEventType::ActionShort);
                            }
                        }
                    }
                } else if (fds_[i].role == DevRole::HeadphoneJack && ev.type == EV_SW &&
                           ev.code == kSwHeadphone) {
                    bool plugged = (ev.value != 0);
                    hp_plugged_.store(plugged);
                    LOGI("InputMonitor", "3.5mm jack state -> %s", plugged ? "plugged" : "unplugged");
                    if (cb_) cb_(plugged ? HwEventType::JackPlugged : HwEventType::JackUnplugged);
                }
            }
        }
        if (removed_any) {
            fds_.erase(
                std::remove_if(fds_.begin(), fds_.end(),
                               [](const WatchedFd& w) { return w.fd < 0; }),
                fds_.end());
        }
    }
}

} // namespace openstation
