#include "hw/remote_keys.hpp"
#include <cassert>

int main() {
    assert(openstation::is_remote_assistant_key("Yandex Remote", KEY_SEARCH));
    assert(openstation::is_remote_assistant_key("YNDX-0006", KEY_VOICECOMMAND));
    assert(!openstation::is_remote_assistant_key("Generic Remote", KEY_SEARCH));
    assert(!openstation::is_remote_assistant_key("Yandex Remote", KEY_RIGHT));
}
