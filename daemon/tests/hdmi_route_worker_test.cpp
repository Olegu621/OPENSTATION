#include "../src/hw/hdmi_route_worker.hpp"
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <vector>

int main() {
    using namespace std::chrono_literals;
    std::mutex mu;
    std::condition_variable cv;
    std::string pid = "one";
    std::vector<bool> completed;
    int enable_attempts = 0;
    openstation::HdmiRouteWorker worker(
        [&](bool on) {
            std::lock_guard<std::mutex> lock(mu);
            if (on && ++enable_attempts == 1) return false;
            return true;
        },
        [&] { std::lock_guard<std::mutex> lock(mu); return pid; },
        [&](bool on) {
            std::lock_guard<std::mutex> lock(mu);
            completed.push_back(on);
            cv.notify_all();
        }, 20ms);
    auto wait_count = [&](size_t count) {
        std::unique_lock<std::mutex> lock(mu);
        return cv.wait_for(lock, 2s, [&] { return completed.size() >= count; });
    };
    worker.request(false);
    assert(wait_count(1));
    worker.request(true);
    assert(wait_count(2)); // failed first attempt is retried
    {
        std::lock_guard<std::mutex> lock(mu);
        assert(!completed[0] && completed[1] && enable_attempts >= 2);
        pid = "two"; // audioserver restarted
    }
    assert(wait_count(3));
    worker.request(false);
    assert(wait_count(4));
    {
        std::lock_guard<std::mutex> lock(mu);
        assert(completed[2] && !completed[3]);
    }
    worker.stop();

    bool entered = false, release = false;
    completed.clear();
    openstation::HdmiRouteWorker rapid(
        [&](bool on) {
            std::unique_lock<std::mutex> lock(mu);
            if (on) {
                entered = true;
                cv.notify_all();
                cv.wait(lock, [&] { return release; });
            }
            return true;
        },
        [] { return std::string("server"); },
        [&](bool on) {
            std::lock_guard<std::mutex> lock(mu);
            completed.push_back(on);
            cv.notify_all();
        }, 20ms);
    rapid.request(true);
    {
        std::unique_lock<std::mutex> lock(mu);
        assert(cv.wait_for(lock, 2s, [&] { return entered; }));
    }
    rapid.request(false);
    {
        std::lock_guard<std::mutex> lock(mu);
        release = true;
        cv.notify_all();
    }
    assert(wait_count(1));
    {
        std::lock_guard<std::mutex> lock(mu);
        assert(completed.size() == 1 && !completed[0]);
    }
    rapid.stop();
}
