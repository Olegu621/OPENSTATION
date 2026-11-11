#pragma once

#include <mutex>
#include <string>

namespace openstation {

class UinputKeys {
public:
    UinputKeys() = default;
    ~UinputKeys();

    bool init();
    void close();

    bool send_key(int linux_keycode);
    bool send_android_action(const std::string& action);

private:
    bool emit(unsigned short type, unsigned short code, int val);
    int fd_ = -1;
    std::mutex mu_;
};

} // namespace openstation
