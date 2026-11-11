#pragma once

#include "config.hpp"
#include "utils/json_min.hpp"

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

struct OtaArtifactInfo {
    std::string key;
    std::string file_name;
    std::string url;
    std::string sha256;
    int64_t size = 0;
};

struct OtaInstallStartResult {
    bool accepted = false;
    std::string message;
};

struct BootAnimOpResult {
    bool ok = false;
    std::string message;
};

class OtaManager {
public:
    using StatusBroadcastCb = std::function<void(const json::Value& status_json)>;

    OtaManager() = default;
    ~OtaManager();

    void start(const std::string& current_version,
               const OtaConfig& cfg,
               StatusBroadcastCb broadcast_cb);
    void stop();

    void update_config(const OtaConfig& cfg);
    bool check_now();
    OtaInstallStartResult start_install(bool force = false);

    json::Value get_status_json() const;
    json::Value get_bootanim_status_json() const;
    bool is_busy() const;

    BootAnimOpResult apply_uploaded_bootanimation(const void* data, size_t len);
    BootAnimOpResult restore_stock_bootanimation();
    BootAnimOpResult install_openstation_bootanimation_sync();

    static std::string compute_file_sha256(const std::string& path, int64_t* out_size = nullptr);
    static std::string compute_bytes_sha256(const void* data, size_t len);
    static int compare_semver(const std::string& a, const std::string& b);

private:
    void refresh_local_hashes_locked();
    bool fetch_and_parse_manifest(std::string& out_err);
    void run_check_sync();
    void run_install_sync(bool force);
    void emit_status();
    std::vector<std::string> build_manifest_urls(const std::string& repo, const std::string& branch) const;
    std::vector<std::string> build_artifact_urls(const OtaArtifactInfo& art,
                                                 const std::string& repo,
                                                 const std::string& branch,
                                                 const std::string& ver) const;

    mutable std::mutex mu_;
    mutable std::mutex bootanim_mu_;
    OtaConfig cfg_;
    StatusBroadcastCb broadcast_cb_;

    std::atomic<bool> stop_req_{false};
    std::atomic<bool> op_active_{false};

    std::mutex thread_mu_;
    std::thread auto_thread_;
    std::thread op_thread_;

    std::string state_ = "idle"; // idle, checking, up_to_date, available, downloading, verifying, installing, restarting, error
    std::string installed_version_ = "1.2.3";
    std::string installed_build_time_;
    std::string latest_version_;
    std::string latest_build_time_;
    std::string changelog_;
    std::string manifest_url_;
    bool update_available_ = false;
    int progress_pct_ = 0;
    std::string progress_text_;
    std::string error_;
    int64_t last_check_epoch_ = 0;
    std::vector<std::string> changed_components_;

    std::string local_daemon_sha_;
    std::string local_launcher_sha_;
    std::string local_ime_sha_;
    std::string local_bootanim_sha_;
    int64_t local_bootanim_size_ = 0;
    std::string bootanim_mode_ = "stock"; // openstation, stock, custom, missing
    bool bootanim_has_stock_backup_ = false;

    OtaArtifactInfo remote_daemon_;
    OtaArtifactInfo remote_launcher_;
    OtaArtifactInfo remote_ime_;
    OtaArtifactInfo remote_bootanim_;
    std::string remote_manifest_raw_;
};

} // namespace openstation
