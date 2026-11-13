#include "hw/uinput_keys.hpp"
#include "utils/logger.hpp"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace openstation {

namespace {
constexpr int SUPPORTED_KEYS[] = {
    KEY_BACK, KEY_HOMEPAGE, KEY_MENU, KEY_ENTER, KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,
    KEY_VOLUMEUP, KEY_VOLUMEDOWN, KEY_MUTE, KEY_POWER, KEY_WAKEUP, KEY_SLEEP, KEY_PLAYPAUSE,
    KEY_STOPCD, KEY_NEXTSONG, KEY_PREVIOUSSONG, KEY_SEARCH, KEY_ESC, KEY_OK, KEY_SELECT,
    KEY_INFO, KEY_CHANNELUP, KEY_CHANNELDOWN, KEY_REWIND, KEY_FASTFORWARD,
    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE
};
} // namespace

UinputKeys::~UinputKeys() {
    close();
}

void UinputKeys::close() {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ >= 0) {
        ioctl(fd_, UI_DEV_DESTROY);
        ::close(fd_);
        fd_ = -1;
    }
}

bool UinputKeys::init() {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ >= 0) return true;
    fd_ = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd_ < 0) {
        LOGE("Uinput", "Failed to open /dev/uinput: %s", strerror(errno));
        return false;
    }

    ioctl(fd_, UI_SET_EVBIT, EV_KEY);
    ioctl(fd_, UI_SET_EVBIT, EV_REL);
    ioctl(fd_, UI_SET_EVBIT, EV_SYN);
    for (int k : SUPPORTED_KEYS) {
        ioctl(fd_, UI_SET_KEYBIT, k);
    }
    ioctl(fd_, UI_SET_RELBIT, REL_X);
    ioctl(fd_, UI_SET_RELBIT, REL_Y);
    ioctl(fd_, UI_SET_RELBIT, REL_WHEEL);
    ioctl(fd_, UI_SET_RELBIT, REL_HWHEEL);

    struct uinput_user_dev udev {};
    snprintf(udev.name, UINPUT_MAX_NAME_SIZE, "OpenStation Virtual Remote");
    udev.id.bustype = BUS_VIRTUAL;
    udev.id.vendor = 0x18d1;
    udev.id.product = 0x5050;
    udev.id.version = 1;

    if (write(fd_, &udev, sizeof(udev)) != sizeof(udev)) {
        LOGE("Uinput", "Failed to write uinput_user_dev: %s", strerror(errno));
        ::close(fd_);
        fd_ = -1;
        return false;
    }
    if (ioctl(fd_, UI_DEV_CREATE) < 0) {
        LOGE("Uinput", "UI_DEV_CREATE failed: %s", strerror(errno));
        ::close(fd_);
        fd_ = -1;
        return false;
    }
    LOGI("Uinput", "Created virtual remote device on /dev/uinput");
    return true;
}

bool UinputKeys::emit(unsigned short type, unsigned short code, int val) {
    if (fd_ < 0) return false;
    struct input_event ev {};
    ev.type = type;
    ev.code = code;
    ev.value = val;
    return write(fd_, &ev, sizeof(ev)) == sizeof(ev);
}

bool UinputKeys::send_key(int linux_keycode) {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ < 0) return false;
    bool ok = emit(EV_KEY, static_cast<unsigned short>(linux_keycode), 1);
    emit(EV_SYN, SYN_REPORT, 0);
    usleep(12000);
    ok &= emit(EV_KEY, static_cast<unsigned short>(linux_keycode), 0);
    emit(EV_SYN, SYN_REPORT, 0);
    return ok;
}

bool UinputKeys::send_android_action(const std::string& action) {
    if (action == "up") return send_key(KEY_UP);
    if (action == "down") return send_key(KEY_DOWN);
    if (action == "left") return send_key(KEY_LEFT);
    if (action == "right") return send_key(KEY_RIGHT);
    if (action == "center" || action == "ok" || action == "enter") return send_key(KEY_ENTER);
    if (action == "back") return send_key(KEY_BACK);
    if (action == "home") return send_key(KEY_HOMEPAGE);
    if (action == "menu") return send_key(KEY_MENU);
    if (action == "vol_up") return send_key(KEY_VOLUMEUP);
    if (action == "vol_down") return send_key(KEY_VOLUMEDOWN);
    if (action == "mute") return send_key(KEY_MUTE);
    if (action == "play_pause") return send_key(KEY_PLAYPAUSE);
    if (action == "next") return send_key(KEY_NEXTSONG);
    if (action == "prev") return send_key(KEY_PREVIOUSSONG);
    if (action == "power") return send_key(KEY_POWER);
    if (action == "wakeup" || action == "wake") return send_key(KEY_WAKEUP);
    if (action == "sleep") return send_key(KEY_SLEEP);
    if (action == "search") return send_key(KEY_SEARCH);
    if (action == "stop") return send_key(KEY_STOPCD);
    if (action == "rewind") return send_key(KEY_REWIND);
    if (action == "fastforward" || action == "ff") return send_key(KEY_FASTFORWARD);
    if (action == "ch_up" || action == "page_up") return send_key(KEY_CHANNELUP);
    if (action == "ch_down" || action == "page_down") return send_key(KEY_CHANNELDOWN);
    if (action == "info") return send_key(KEY_INFO);
    if (action == "esc") return send_key(KEY_ESC);
    return false;
}

bool UinputKeys::send_mouse_move(int dx, int dy) {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ < 0 || (dx == 0 && dy == 0)) return fd_ >= 0;
    bool ok = true;
    if (dx != 0) ok &= emit(EV_REL, REL_X, dx);
    if (dy != 0) ok &= emit(EV_REL, REL_Y, dy);
    ok &= emit(EV_SYN, SYN_REPORT, 0);
    return ok;
}

bool UinputKeys::send_mouse_click(int button, int count) {
    int code = (button == 1) ? BTN_RIGHT : ((button == 2) ? BTN_MIDDLE : BTN_LEFT);
    int reps = (count >= 2) ? 2 : 1;
    bool ok = true;
    for (int i = 0; i < reps; ++i) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (fd_ < 0) return false;
            ok &= emit(EV_KEY, static_cast<unsigned short>(code), 1);
            emit(EV_SYN, SYN_REPORT, 0);
            usleep(14000);
            ok &= emit(EV_KEY, static_cast<unsigned short>(code), 0);
            emit(EV_SYN, SYN_REPORT, 0);
        }
        if (i + 1 < reps) usleep(45000);
    }
    return ok;
}

bool UinputKeys::send_mouse_scroll(int dy) {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ < 0 || dy == 0) return fd_ >= 0;
    bool ok = emit(EV_REL, REL_WHEEL, dy);
    ok &= emit(EV_SYN, SYN_REPORT, 0);
    return ok;
}

} // namespace openstation
