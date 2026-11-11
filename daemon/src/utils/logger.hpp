#pragma once

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <ctime>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

namespace openstation {

class Logger {
public:
    static Logger& instance() {
        static Logger inst;
        return inst;
    }

    void init(const std::string& log_path) {
        std::lock_guard<std::mutex> lk(mu_);
        if (fp_) {
            fclose(fp_);
            fp_ = nullptr;
        }
        log_path_ = log_path;
        // Cap / rotate log file > 1.5 MB so /data never fills up
        long existing_sz = 0;
        FILE* chk = fopen(log_path_.c_str(), "rb");
        if (chk) {
            fseek(chk, 0, SEEK_END);
            long sz = ftell(chk);
            fclose(chk);
            if (sz > 1572864L) {
                std::string old_path = log_path_ + ".old";
                rename(log_path_.c_str(), old_path.c_str());
            } else if (sz > 0) {
                existing_sz = sz;
            }
        }
        fp_ = fopen(log_path_.c_str(), "a");
        if (fp_) setvbuf(fp_, nullptr, _IOLBF, 0);
        bytes_written_ = existing_sz;
    }

    void set_sink(std::function<void(const std::string&)> cb) {
        std::lock_guard<std::mutex> lk(mu_);
        sink_ = std::move(cb);
    }

    void log(const char* level, const char* tag, const char* fmt, ...) {
        char msg[1024];
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(msg, sizeof(msg), fmt, ap);
        va_end(ap);

        auto now = std::chrono::system_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                      now.time_since_epoch()) % 1000;
        std::time_t tt = std::chrono::system_clock::to_time_t(now);
        std::tm tm_now{};
        localtime_r(&tt, &tm_now);

        char line[1200];
        snprintf(line, sizeof(line),
                 "%02d:%02d:%02d.%03d [%s] %s: %s",
                 tm_now.tm_hour, tm_now.tm_min, tm_now.tm_sec,
                 static_cast<int>(ms.count()), level, tag, msg);

        std::function<void(const std::string&)> cb_copy;
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (!fp_ || isatty(STDERR_FILENO)) {
                fprintf(stderr, "%s\n", line);
            }
            if (fp_) {
                if (bytes_written_ >= 1572864L && !log_path_.empty()) {
                    fclose(fp_);
                    fp_ = nullptr;
                    std::string old_path = log_path_ + ".old";
                    rename(log_path_.c_str(), old_path.c_str());
                    fp_ = fopen(log_path_.c_str(), "a");
                    if (fp_) setvbuf(fp_, nullptr, _IOLBF, 0);
                    bytes_written_ = 0;
                }
                if (fp_) {
                    int nw = fprintf(fp_, "%s\n", line);
                    if (nw > 0) bytes_written_ += nw;
                }
            }
            ring_.emplace_back(line);
            while (ring_.size() > 250) ring_.pop_front();
            cb_copy = sink_;
        }
        if (cb_copy) {
            cb_copy(line);
        }
    }

    std::vector<std::string> recent(size_t max_lines = 100) {
        std::lock_guard<std::mutex> lk(mu_);
        std::vector<std::string> out;
        size_t start = ring_.size() > max_lines ? ring_.size() - max_lines : 0;
        for (size_t i = start; i < ring_.size(); ++i) {
            out.push_back(ring_[i]);
        }
        return out;
    }

private:
    Logger() = default;
    ~Logger() {
        if (fp_) fclose(fp_);
    }
    std::mutex mu_;
    std::string log_path_;
    FILE* fp_ = nullptr;
    long bytes_written_ = 0;
    std::deque<std::string> ring_;
    std::function<void(const std::string&)> sink_;
};

#define LOGI(tag, ...) ::openstation::Logger::instance().log("I", tag, __VA_ARGS__)
#define LOGW(tag, ...) ::openstation::Logger::instance().log("W", tag, __VA_ARGS__)
#define LOGE(tag, ...) ::openstation::Logger::instance().log("E", tag, __VA_ARGS__)

} // namespace openstation
