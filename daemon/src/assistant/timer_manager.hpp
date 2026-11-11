#pragma once

#include "utils/json_min.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

struct TimerEntry {
    int id = 0;
    std::string label;
    int total_sec = 0;
    std::chrono::steady_clock::time_point fire_tp{};
};

class TimerManager {
public:
    using FireCallback = std::function<void(const TimerEntry& t)>;

    TimerManager() = default;
    ~TimerManager();

    void start(FireCallback on_fire);
    void stop();

    int set_timer(int seconds, const std::string& label);
    int cancel_timer(const std::string& id_or_label);
    json::Value list_timers_json();
    size_t active_count();

private:
    void worker_loop();

    FireCallback on_fire_;
    std::atomic<bool> running_{false};
    std::thread worker_;
    std::mutex mu_;
    std::condition_variable cv_;
    std::vector<TimerEntry> timers_;
    int next_id_ = 1;
};

} // namespace openstation
