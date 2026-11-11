#pragma once

#include "config.hpp"
#include "utils/json_min.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace openstation {

struct AdbLiveStatus {
    bool enabled = true;
    int port = 5555;
    bool listening = false;
    bool listening_v6 = false;
    bool listening_v4 = false;
    bool adbd_running = false;
    bool usb_gadget_ok = false;
    std::string service_port;
    std::string persist_port;
    std::string persist_usb_config;
    std::string sys_usb_config;
    std::string sys_usb_state;
    bool boot_completed = false;
    bool wifi_power_save_off = true;
    std::string wifi_power_save_state = "unknown"; // off, on, unknown
    std::string adb_setting = "unknown";           // 1, 0, unknown
    std::string wifi_sleep_policy = "unknown";     // 2, ...
    int64_t last_repair_epoch = 0;
    std::string last_repair_reason;
    bool last_repair_ok = true;
    int repair_count = 0;
    int consecutive_failures = 0;
    bool busy = false;
};

class AdbManager {
public:
    using ListenerChangedCb = std::function<void(int active_adb_port)>;

    AdbManager() = default;
    ~AdbManager();

    void start(const NetworkConfig& cfg,
               const std::string& hostname,
               ListenerChangedCb on_listener_changed = nullptr);
    void stop();

    void update_config(const NetworkConfig& cfg, const std::string& hostname);
    bool repair_now(const std::string& reason, bool force_restart);

    AdbLiveStatus snapshot() const;
    json::Value get_status_json(const std::string& local_ip,
                                const std::string& hostname) const;

private:
    void worker_loop();
    void poll_kernel_and_props(AdbLiveStatus& st, int listen_port) const;
    void check_and_fix_wifi_power_save(AdbLiveStatus& st, bool apply_fix);
    void sync_disabled_marker(bool enabled) const;
    bool pause_guard_active() const;
    void apply_enable_props_if_needed(const AdbLiveStatus& st, int target_port) const;
    void ensure_android_settings(AdbLiveStatus& st, bool wifi_adb_enabled);
    bool perform_adbd_transition(bool want_enable, int target_port, const std::string& reason);
    void notify_listener_if_changed(bool enabled, int port, bool listening);

    static bool is_tcp_port_listening_proc(const char* proc_path, int port);
    static std::string get_sys_prop(const char* name);
    static bool set_sys_prop(const char* name, const char* value);
    static bool check_usb_gadget_ok(std::string* out_id_product = nullptr,
                                    bool* out_f1_ffs = nullptr);
    static void escape_self_from_adbd_cgroup();
    static std::string find_adbd_pid_str();
    static double read_os_uptime_sec();

    mutable std::mutex mu_;
    mutable std::mutex op_mu_;
    std::mutex wake_mu_;
    std::condition_variable wake_cv_;

    NetworkConfig cfg_;
    std::string hostname_ = "station-max";
    ListenerChangedCb listener_cb_;

    AdbLiveStatus st_;
    std::atomic<bool> stop_req_{false};
    std::atomic<bool> wake_req_{false};
    std::thread worker_;

    int last_notified_adb_port_ = -1;
    std::chrono::steady_clock::time_point started_tp_{};
    std::chrono::steady_clock::time_point last_repair_tp_{};
    std::chrono::steady_clock::time_point last_pwrsave_check_tp_{};
    std::chrono::steady_clock::time_point last_settings_sync_tp_{};
    bool settings_synced_after_boot_ = false;
    bool mismatch_seen_ = false;
    std::chrono::steady_clock::time_point first_mismatch_tp_{};
    int usb_only_drift_attempts_ = 0;
};

} // namespace openstation
