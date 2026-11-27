#include "hw/remote_keys.hpp"
#include <cassert>

int main() {
    assert(openstation::is_remote_assistant_key("Yandex Remote", KEY_SEARCH));
    assert(openstation::is_remote_assistant_key("YNDX-0006", KEY_VOICECOMMAND));
    assert(!openstation::is_remote_assistant_key("Generic Remote", KEY_SEARCH));
    assert(!openstation::is_remote_assistant_key("Yandex Remote", KEY_RIGHT));
    using namespace std::chrono;
    using openstation::RemoteAssistantGesture;
    openstation::RemoteAssistantPress press;
    const auto t = openstation::RemoteAssistantPress::Clock::now();
    assert(press.event(1, t) == RemoteAssistantGesture::None);
    assert(press.pressed());
    assert(press.event(1, t + milliseconds(100)) == RemoteAssistantGesture::None);
    assert(press.event(2, t + milliseconds(200)) == RemoteAssistantGesture::None);
    assert(press.event(0, t + milliseconds(300)) == RemoteAssistantGesture::Short);
    assert(!press.pressed());
    assert(press.event(1, t) == RemoteAssistantGesture::None);
    assert(press.tick(t + milliseconds(800)) == RemoteAssistantGesture::Long);
    assert(press.long_active());
    assert(press.tick(t + milliseconds(900)) == RemoteAssistantGesture::None);
    assert(press.event(0, t + milliseconds(1000)) == RemoteAssistantGesture::None);
    assert(!press.long_active());
    assert(press.event(1, t) == RemoteAssistantGesture::None);
    assert(press.event(0, t + milliseconds(900)) == RemoteAssistantGesture::Long);
    assert(press.event(0, t + milliseconds(1000)) == RemoteAssistantGesture::None);

    // Multi-tap & long press tracker for blind remote bindings
    using openstation::RemoteGesture;
    openstation::RemoteKeyGestureTracker gk;
    // Single tap with double-click detection waits 340ms before emitting Short
    assert(gk.event(1, t, true) == RemoteGesture::None);
    assert(gk.event(0, t + milliseconds(90), true) == RemoteGesture::None);
    assert(gk.tick(t + milliseconds(250)) == RemoteGesture::None);
    assert(gk.tick(t + milliseconds(440)) == RemoteGesture::Short);

    // Double tap emits Double right on 2nd release
    assert(gk.event(1, t + seconds(1), true) == RemoteGesture::None);
    assert(gk.event(0, t + seconds(1) + milliseconds(80), true) == RemoteGesture::None);
    assert(gk.event(1, t + seconds(1) + milliseconds(200), true) == RemoteGesture::None);
    assert(gk.event(0, t + seconds(1) + milliseconds(280), true) == RemoteGesture::Double);
    assert(gk.tick(t + seconds(2)) == RemoteGesture::None);

    // Long press fires via tick at 650ms and suppresses release
    assert(gk.event(1, t + seconds(3), true) == RemoteGesture::None);
    assert(gk.event(2, t + seconds(3) + milliseconds(400), true) == RemoteGesture::None);
    assert(gk.tick(t + seconds(3) + milliseconds(650)) == RemoteGesture::Long);
    assert(gk.event(0, t + seconds(3) + milliseconds(900), true) == RemoteGesture::None);

    // Mute button: 1 or 2 taps still toggle MuteToggle once; 5 taps trigger BluetoothToggle
    using openstation::MuteButtonTracker;
    MuteButtonTracker mute;
    assert(mute.on_press(t) == MuteButtonTracker::Action::None);
    assert(mute.on_press(t + milliseconds(180)) == MuteButtonTracker::Action::None);
    assert(mute.tick(t + milliseconds(550)) == MuteButtonTracker::Action::MuteToggle);
    for (int i = 0; i < 4; ++i) {
        assert(mute.on_press(t + seconds(2) + milliseconds(i * 100)) == MuteButtonTracker::Action::None);
    }
    assert(mute.on_press(t + seconds(2) + milliseconds(400)) == MuteButtonTracker::Action::BluetoothToggle);
    assert(mute.tick(t + seconds(3)) == MuteButtonTracker::Action::None);
    return 0;
}
