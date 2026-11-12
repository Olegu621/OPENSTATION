#include "assistant/timer_manager.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <cctype>
#include <cstdlib>

namespace openstation {

namespace {

bool contains_ci(const std::string& hay, const std::string& needle) {
    if (needle.empty()) return false;
    auto it = std::search(hay.begin(), hay.end(), needle.begin(), needle.end(),
                          [](char a, char b) {
                              return std::tolower(static_cast<unsigned char>(a)) ==
                                     std::tolower(static_cast<unsigned char>(b));
                          });
    return it != hay.end();
}

} // namespace

TimerManager::~TimerManager() {
    stop();
}

void TimerManager::start(FireCallback on_fire) {
    stop();
    on_fire_ = std::move(on_fire);
    running_.store(true);
    worker_ = std::thread(&TimerManager::worker_loop, this);
}

void TimerManager::stop() {
    {
        // Store under the mutex so the worker can't miss the wakeup between predicate check and wait.
        std::lock_guard<std::mutex> lk(mu_);
        running_.store(false);
    }
    cv_.notify_all();
    if (worker_.joinable()) {
        worker_.join();
    }
}

int TimerManager::set_timer(int seconds, const std::string& label) {
    int clamped = std::clamp(seconds, 1, 24 * 3600);
    TimerEntry e;
    {
        std::lock_guard<std::mutex> lk(mu_);
        e.id = next_id_++;
        e.label = label.empty() ? ("Таймер #" + std::to_string(e.id)) : label;
        e.total_sec = clamped;
        e.fire_tp = std::chrono::steady_clock::now() + std::chrono::seconds(clamped);
        timers_.push_back(e);
    }
    cv_.notify_all();
    LOGI("Timer", "Scheduled timer #%d (%s) for %ds", e.id, e.label.c_str(), e.total_sec);
    return e.id;
}

int TimerManager::cancel_timer(const std::string& id_or_label) {
    std::lock_guard<std::mutex> lk(mu_);
    if (timers_.empty()) return 0;

    std::string q = id_or_label;
    while (!q.empty() && std::isspace(static_cast<unsigned char>(q.front()))) q.erase(q.begin());
    while (!q.empty() && std::isspace(static_cast<unsigned char>(q.back()))) q.pop_back();

    if (q == "all" || q == "все" || q == "*") {
        int n = static_cast<int>(timers_.size());
        timers_.clear();
        cv_.notify_all();
        return n;
    }

    if (q.empty()) {
        timers_.pop_back(); // cancel most recent timer
        cv_.notify_all();
        return 1;
    }

    bool all_digits = true;
    for (unsigned char c : q) {
        if (!std::isdigit(c)) { all_digits = false; break; }
    }
    if (all_digits) {
        int id = std::atoi(q.c_str());
        auto it = std::remove_if(timers_.begin(), timers_.end(),
                                 [id](const TimerEntry& t) { return t.id == id; });
        int removed = static_cast<int>(std::distance(it, timers_.end()));
        timers_.erase(it, timers_.end());
        if (removed > 0) {
            cv_.notify_all();
            return removed;
        }
    }

    auto it = std::remove_if(timers_.begin(), timers_.end(),
                             [&](const TimerEntry& t) { return contains_ci(t.label, q); });
    int removed = static_cast<int>(std::distance(it, timers_.end()));
    timers_.erase(it, timers_.end());
    if (removed > 0) cv_.notify_all();
    return removed;
}

size_t TimerManager::active_count() {
    std::lock_guard<std::mutex> lk(mu_);
    return timers_.size();
}

json::Value TimerManager::list_timers_json() {
    using json::Value;
    Value arr = Value::make_arr();
    auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lk(mu_);
    for (const auto& t : timers_) {
        auto rem = std::chrono::duration_cast<std::chrono::seconds>(t.fire_tp - now).count();
        Value o = Value::make_obj();
        o["id"] = Value::make_int(t.id);
        o["label"] = Value::make_str(t.label);
        o["totalSec"] = Value::make_int(t.total_sec);
        o["remainingSec"] = Value::make_int(std::max<int64_t>(0, rem));
        arr.push(std::move(o));
    }
    return arr;
}

void TimerManager::worker_loop() {
    while (running_.load()) {
        std::vector<TimerEntry> expired;
        {
            std::unique_lock<std::mutex> lk(mu_);
            if (timers_.empty()) {
                cv_.wait(lk, [&] { return !running_.load() || !timers_.empty(); });
            } else {
                auto soonest = timers_.front().fire_tp;
                for (const auto& t : timers_) {
                    if (t.fire_tp < soonest) soonest = t.fire_tp;
                }
                // No predicate: a newly added/cancelled timer must wake us to recompute the deadline.
                cv_.wait_until(lk, soonest);
            }
            if (!running_.load()) break;

            auto now = std::chrono::steady_clock::now();
            auto it = timers_.begin();
            while (it != timers_.end()) {
                if (it->fire_tp <= now) {
                    expired.push_back(*it);
                    it = timers_.erase(it);
                } else {
                    ++it;
                }
            }
        }
        for (const auto& t : expired) {
            LOGI("Timer", "Fired timer #%d: %s", t.id, t.label.c_str());
            if (on_fire_) on_fire_(t);
        }
    }
}

} // namespace openstation
