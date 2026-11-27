#pragma once

#include <chrono>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace openstation {

// Serialize Android AudioPolicy HDMI wired-device calls, retry failures promptly,
// and reapply HDMI routing automatically if audioserver restarts.
class HdmiRouteWorker {
public:
    using Apply = std::function<bool(bool)>;
    using ServerId = std::function<std::string()>;
    using Applied = std::function<void(bool)>;

    HdmiRouteWorker(Apply apply, ServerId server_id, Applied applied,
                    std::chrono::milliseconds interval = std::chrono::seconds(5),
                    std::chrono::milliseconds retry_interval = std::chrono::milliseconds(1500))
        : apply_(std::move(apply)),
          server_id_(std::move(server_id)),
          applied_(std::move(applied)),
          interval_(interval),
          retry_interval_(retry_interval),
          thread_(&HdmiRouteWorker::run, this) {}

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
        bool last_attempt_failed = false;

        for (;;) {
            bool hdmi = false;
            unsigned long revision = 0;
            {
                std::unique_lock<std::mutex> lock(mu_);
                const auto wait_for = last_attempt_failed ? retry_interval_ : interval_;
                cv_.wait_for(lock, wait_for, [&] { return stopping_ || dirty_; });
                if (stopping_) return;
                if (!requested_) continue;
                hdmi = desired_;
                revision = revision_;
                dirty_ = false;
            }

            const std::string server = hdmi ? server_id_() : std::string();
            if (hdmi && server.empty()) {
                last_attempt_failed = true;
                continue;
            }
            if (last_applied == static_cast<int>(hdmi) && (!hdmi || server == last_server)) {
                last_attempt_failed = false;
                continue;
            }

            {
                std::lock_guard<std::mutex> lock(mu_);
                if (stopping_) return;
                if (revision != revision_) continue;
            }

            if (!apply_(hdmi)) {
                last_attempt_failed = true;
                continue;
            }

            last_applied = static_cast<int>(hdmi);
            last_server = server;
            last_attempt_failed = false;

            bool still_current = false;
            {
                std::lock_guard<std::mutex> lock(mu_);
                still_current = !stopping_ && revision == revision_;
            }
            if (still_current && applied_) {
                applied_(hdmi);
            }
        }
    }

    Apply apply_;
    ServerId server_id_;
    Applied applied_;
    std::chrono::milliseconds interval_;
    std::chrono::milliseconds retry_interval_;
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
