#pragma once

#include "net/http_client.hpp"
#include "utils/json_min.hpp"

#include <atomic>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace openstation {

struct PluginCallbacks {
    std::function<json::Value(const std::string& tool_name, const json::Value& args)> execute_builtin_tool;
    std::function<bool(const std::string& topic, const std::string& payload, bool retain)> mqtt_publish;
    std::function<HttpClientResponse(const HttpClientRequest& req)> http_hook;
    std::function<void()> on_plugins_changed;
};

struct InstalledPluginRecord {
    std::string id;
    std::string source_url;
    bool enabled = false;
    bool official = false;
    int64_t installed_at = 0;
    int64_t updated_at = 0;
    int64_t last_run_at = 0;
    int64_t last_schedule_at = 0;
    std::string last_status;
    std::string last_error;
    std::map<std::string, json::Value> settings;
    json::Value manifest; // parsed plugin.json
};

class PluginManager {
public:
    PluginManager() = default;

    bool init(const std::string& base_dir, PluginCallbacks cb = {});
    void set_callbacks(PluginCallbacks cb);

    static bool is_valid_plugin_id(const std::string& id);
    static bool is_valid_rel_path(const std::string& rel);
    static bool is_valid_tool_name(const std::string& name);
    static std::string sha256_hex(const uint8_t* data, size_t len);
    static std::string sha256_hex_str(const std::string& s);

    // Fetch remote marketplace catalog from GitHub raw (`<repo>/<branch>/plugins/index.json`).
    bool fetch_catalog(const std::string& repo,
                       const std::string& branch,
                       bool force_refresh,
                       std::string& out_error,
                       const std::atomic<bool>* abort_req = nullptr);

    // Seed catalog explicitly (useful for offline tests or embedded fallback).
    bool set_catalog_json(const json::Value& index_obj, std::string& out_error);

    bool install_from_catalog(const std::string& plugin_id,
                              const std::string& repo,
                              const std::string& branch,
                              std::string& out_error,
                              const std::atomic<bool>* abort_req = nullptr);

    bool install_from_url(const std::string& manifest_url,
                          std::string& out_error,
                          const std::atomic<bool>* abort_req = nullptr);

    bool install_from_manifest(const json::Value& manifest_obj,
                               const std::string& source_url,
                               const std::map<std::string, std::string>& preloaded_files,
                               bool official,
                               bool preserve_enabled,
                               std::string& out_error);

    bool uninstall(const std::string& plugin_id, std::string& out_error);
    bool set_enabled(const std::string& plugin_id, bool enabled, std::string& out_error);
    bool update_settings(const std::string& plugin_id,
                         const json::Value& settings_obj,
                         std::string& out_error);

    json::Value run_action(const std::string& plugin_id,
                           const std::string& action_id,
                           const json::Value& args_obj,
                           const std::atomic<bool>* abort_req = nullptr);

    // Assistant integration helpers (enabled plugins only)
    bool has_tool(const std::string& tool_name) const;
    json::Value get_assistant_tool_decls() const;
    std::string get_assistant_prompt_suffix() const;
    json::Value execute_tool(const std::string& tool_name,
                             const json::Value& args,
                             const std::atomic<bool>* abort_req = nullptr);

    // Quick offline voice phrase matcher (exact normalized match for enabled plugins)
    struct VoiceMatch {
        bool matched = false;
        std::string plugin_id;
        std::string action_id;
        std::string reply_hint;
    };
    VoiceMatch match_voice_phrase(const std::string& normalized_text) const;

    // Periodic background runner for enabled plugins declaring `schedule.intervalSec`.
    void tick_schedules(const std::atomic<bool>* abort_req = nullptr);

    json::Value get_marketplace_json(const std::string& repo, const std::string& branch) const;
    json::Value get_summary_json() const;

    static std::string render_template(const std::string& tmpl,
                                       const json::Value& args,
                                       const std::map<std::string, json::Value>& settings,
                                       int mode /* 0=Plain, 1=UrlEncoded, 2=JsonString */);

private:
    bool load_state_locked();
    bool save_state_locked() const;
    bool validate_manifest(const json::Value& manifest, std::string& out_error) const;
    json::Value execute_runner(const std::string& plugin_id,
                               const json::Value& manifest,
                               const std::map<std::string, json::Value>& settings,
                               const json::Value& runner,
                               const json::Value& args,
                               const std::atomic<bool>* abort_req);
    void record_run_status(const std::string& plugin_id, bool ok, const std::string& detail);
    HttpClientResponse perform_http(const HttpClientRequest& req) const;

    mutable std::mutex mu_;
    std::string base_dir_;
    std::string installed_dir_;
    std::string state_path_;
    std::string catalog_cache_path_;
    PluginCallbacks cb_;

    std::vector<InstalledPluginRecord> installed_;
    json::Value catalog_items_ = json::Value::make_arr();
    int64_t catalog_fetched_at_ = 0;
    std::string catalog_source_;
    std::string catalog_error_;
};

} // namespace openstation
