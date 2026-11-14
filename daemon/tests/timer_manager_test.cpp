#include "assistant/timer_manager.hpp"
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>

int main() {
    using namespace std::chrono_literals;
    openstation::TimerManager timers;
    std::mutex mu;
    std::condition_variable cv;
    std::string fired;
    timers.start([&](const openstation::TimerEntry& timer) {
        std::lock_guard<std::mutex> lock(mu);
        fired = timer.label;
        cv.notify_one();
    });
    timers.set_timer(8, "long");
    std::this_thread::sleep_for(200ms);
    timers.set_timer(1, "short");
    {
        std::unique_lock<std::mutex> lock(mu);
        assert(cv.wait_for(lock, 3s, [&] { return !fired.empty(); }));
        assert(fired == "short");
    }
    assert(timers.cancel_timer("long") == 1);
    timers.stop();
    assert(timers.active_count() == 0);
}
