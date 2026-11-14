#pragma once
#include <linux/input.h>
#include <string>

namespace openstation {
inline bool is_remote_assistant_key(const std::string& name, int code) {
    const bool yandex_remote = name.find("Yandex Remote") != std::string::npos ||
                               name.find("YNDX-0006") != std::string::npos;
    return yandex_remote && (code == KEY_SEARCH || code == KEY_VOICECOMMAND || code == KEY_ASSISTANT);
}
}
