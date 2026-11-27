#include "../src/hw/hdmi_route_worker.hpp"

#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

namespace {

bool wait_for_cond(std::mutex& mu, std::condition_variable& cv,
                   const std::function<bool()>& pred,
                   std::chrono::milliseconds timeout = std::chrono::milliseconds(1500)) {
    std::unique_lock<std::mutex> lk(mu);
    return cv.wait_for(lk, timeout, pred);
}

void test_retry_and_audioserver_restart() {
    std::mutex mu;
    std::condition_variable cv;
    std::vector<bool> applies;
    std::vector<bool> applied_cbs;
    std::atomic<int> fail_first{2};
    std::string pid = "1001";
    std::atomic<int> server_id_calls{0};

    openstation::HdmiRouteWorker worker(
        [&](bool en) {
            {
                std::lock_guard<std::mutex> lk(mu);
                applies.push_back(en);
            }
            if (en && fail_first.fetch_sub(1) > 0) return false;
            cv.notify_all();
            return true;
        },
        [&]() {
            server_id_calls.fetch_add(1);
            std::lock_guard<std::mutex> lk(mu);
            return pid;
        },
        [&](bool en) {
            {
                std::lock_guard<std::mutex> lk(mu);
                applied_cbs.push_back(en);
            }
            cv.notify_all();
        },
        std::chrono::milliseconds(40),
        std::chrono::milliseconds(15));

    worker.request(true);
    assert(wait_for_cond(mu, cv, [&] { return applied_cbs.size() == 1; }));
    {
        std::lock_guard<std::mutex> lk(mu);
        assert(applies.size() == 3);
        assert(applies[0] && applies[1] && applies[2]);
        assert(applied_cbs[0] == true);
    }

    // Simulate audioserver restart -> worker must reapply HDMI automatically
    {
        std::lock_guard<std::mutex> lk(mu);
        pid = "2002";
    }
    assert(wait_for_cond(mu, cv, [&] { return applied_cbs.size() == 2; }));
    {
        std::lock_guard<std::mutex> lk(mu);
        assert(applies.size() == 4);
        assert(applies[3] == true);
        assert(applied_cbs[1] == true);
    }

    // Switch back to non-HDMI -> applies once and stops polling server_id
    worker.request(false);
    assert(wait_for_cond(mu, cv, [&] { return applied_cbs.size() == 3; }));
    int calls_after_disable = server_id_calls.load();
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    assert(server_id_calls.load() == calls_after_disable);
    {
        std::lock_guard<std::mutex> lk(mu);
        assert(applies.back() == false);
        assert(applied_cbs.back() == false);
    }

    worker.stop();
}

void test_superseded_revision_skips_stale_applied_callback() {
    std::mutex mu;
    std::condition_variable cv;
    bool in_first_apply = false;
    bool release_first_apply = false;
    std::vector<bool> applied_cbs;

    openstation::HdmiRouteWorker worker(
        [&](bool en) {
            if (en) {
                std::unique_lock<std::mutex> lk(mu);
                in_first_apply = true;
                cv.notify_all();
                cv.wait(lk, [&] { return release_first_apply; });
            }
            return true;
        },
        []() { return std::string("3003"); },
        [&](bool en) {
            {
                std::lock_guard<std::mutex> lk(mu);
                applied_cbs.push_back(en);
            }
            cv.notify_all();
        },
        std::chrono::milliseconds(50),
        std::chrono::milliseconds(15));

    worker.request(true);
    assert(wait_for_cond(mu, cv, [&] { return in_first_apply; }));

    // Supersede with false while apply(true) is still in flight
    worker.request(false);
    {
        std::lock_guard<std::mutex> lk(mu);
        release_first_apply = true;
    }
    cv.notify_all();

    assert(wait_for_cond(mu, cv, [&] { return !applied_cbs.empty(); }));
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    {
        std::lock_guard<std::mutex> lk(mu);
        assert(applied_cbs.size() == 1);
        assert(applied_cbs[0] == false);
    }

    worker.stop();
}

} // namespace

int main() {
    test_retry_and_audioserver_restart();
    test_superseded_revision_skips_stale_applied_callback();
    std::puts("hdmi_route_worker_test: ALL PASSED");
    return 0;
}
