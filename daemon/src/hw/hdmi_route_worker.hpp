#pragma once

#include <chrono>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace openstation {

// Serialize AudioPolicy changes, retry failures and reapply HDMI if audioserver restarts.
class HdmiRouteWorker {
public:
    using Apply = std::function<bool(bool)>;
    using ServerId = std::function<std::string()>;
    using Applied = std::function<void(bool)>;

    HdmiRouteWorker(Apply apply, ServerId server_id, Applied applied,
                    std::chrono::milliseconds interval = std::chrono::seconds(5))
        : apply_(std::move(apply)), server_id_(std::move(server_id)),
          applied_(std::move(applied)), interval_(interval), thread_(&HdmiRouteWorker::run, this) {}

    ~HdmiRouteWorker() { stop(); }
    HdmiRouteWorker(const HdmiRouteWorker&) = delete;
    HdmiRouteWorker& operator=(const HdmiRouteWorker&) = delete;

    void request(bool hdmi) {
        std::lock_guard<std::mutex> lock(mu_);
        if (!requested_ || desired_ != hdmi) {
            requested_ = true;
            desired_ = hdmi;
            ++revision_;
            dirty_ = true;
            cv_.notify_one();
        }
    }

    void stop() {
        {
            std::lock_guard<std::mutex> lock(mu_);
            stopping_ = true;
            cv_.notify_one();
        }
        if (thread_.joinable()) thread_.join();
    }

private:
    void run() {
        int last_applied = -1;
        std::string last_server;
        for (;;) {
            bool hdmi;
            unsigned long revision;
            {
                std::unique_lock<std::mutex> lock(mu_);
                cv_.wait_for(lock, interval_, [&] { return stopping_ || dirty_; });
                if (stopping_) return;
                if (!requested_) continue;
                hdmi = desired_;
                revision = revision_;
                dirty_ = false;
            }
            const std::string server = hdmi ? server_id_() : std::string();
            if (hdmi && server.empty()) continue;
            if (last_applied == static_cast<int>(hdmi) && (!hdmi || server == last_server)) continue;
            {
                std::lock_guard<std::mutex> lock(mu_);
                if (stopping_) return;
                if (revision != revision_) continue;
            }
            if (!apply_(hdmi)) continue;
            last_applied = static_cast<int>(hdmi);
            last_server = server;
            bool still_current;
            {
                std::lock_guard<std::mutex> lock(mu_);
                still_current = !stopping_ && revision == revision_;
            }
            if (still_current && applied_) applied_(hdmi);
            if (!still_current) last_applied = -1;
        }
    }

    Apply apply_;
    ServerId server_id_;
    Applied applied_;
    std::chrono::milliseconds interval_;
    std::mutex mu_;
    std::condition_variable cv_;
    bool stopping_ = false;
    bool requested_ = false;
    bool desired_ = false;
    bool dirty_ = false;
    unsigned long revision_ = 0;
    std::thread thread_;
};

} // namespace openstation
