#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

namespace openstation {

class MdnsResponder {
public:
    MdnsResponder() = default;
    ~MdnsResponder();

    bool start(const std::string& hostname, int web_port, int wyoming_port);
    void stop();
    void update_hostname(const std::string& hostname);
    static std::string detect_local_ipv4();

private:
    void worker_loop();
    void ensure_multicast_membership(const std::string& ip_str);
    void send_announcement(uint32_t ttl_sec);

    std::string hostname_ = "station-max";
    std::string joined_ip_;
    int web_port_ = 8080;
    int wyoming_port_ = 10700;
    int sock_fd_ = -1;
    std::mutex mu_;
    std::atomic<bool> running_{false};
    std::thread worker_;
};

} // namespace openstation
