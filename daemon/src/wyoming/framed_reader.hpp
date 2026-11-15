#pragma once

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <poll.h>
#include <string>
#include <sys/socket.h>

namespace openstation::wyoming {

// A quiet, established satellite may stay connected indefinitely. Once a
// frame starts, its header and body must each arrive within a fixed deadline.
class FramedReader {
public:
    FramedReader(int fd, const std::atomic<bool>& running,
                 std::chrono::milliseconds deadline = std::chrono::seconds(10))
        : fd_(fd), running_(running), budget_(deadline) {}

    bool read_line(std::string& out) const {
        out.clear();
        auto deadline = Clock::time_point::max();
        bool started = false;
        size_t received = 0;
        while (running_.load() && received < 16384) {
            char c = 0;
            if (read_some(&c, 1, deadline) <= 0) return false;
            if (!started) {
                started = true;
                deadline = Clock::now() + budget_;
            }
            ++received;
            if (c == '\n') return true;
            if (c != '\r') out.push_back(c);
        }
        return false;
    }

    bool read_exact(uint8_t* out, size_t count) const {
        const auto deadline = Clock::now() + budget_;
        size_t offset = 0;
        while (running_.load() && offset < count) {
            const auto n = read_some(out + offset, count - offset, deadline);
            if (n <= 0) return false;
            offset += static_cast<size_t>(n);
        }
        return offset == count;
    }

private:
    using Clock = std::chrono::steady_clock;

    ssize_t read_some(void* out, size_t count, Clock::time_point deadline) const {
        while (running_.load()) {
            auto now = Clock::now();
            if (deadline != Clock::time_point::max() && now >= deadline) return -1;
            int wait_ms = 200;
            if (deadline != Clock::time_point::max()) {
                const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
                wait_ms = static_cast<int>(std::max<int64_t>(1, std::min<int64_t>(remaining, wait_ms)));
            }
            pollfd pfd{fd_, POLLIN, 0};
            const int ready = ::poll(&pfd, 1, wait_ms);
            if (ready < 0 && errno != EINTR) return -1;
            if (ready <= 0) continue;
            const ssize_t n = ::recv(fd_, out, count, MSG_DONTWAIT);
            if (n > 0) return n;
            if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
            return -1;
        }
        return -1;
    }

    int fd_;
    const std::atomic<bool>& running_;
    std::chrono::milliseconds budget_;
};

} // namespace openstation::wyoming
