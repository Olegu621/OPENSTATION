#include "../src/plugins/plugin_manager.hpp"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

std::string mkdtemp_dir(const char* prefix) {
    std::string tmpl = std::string("/data/local/tmp/") + prefix + "_XXXXXX";
    std::vector<char> buf(tmpl.begin(), tmpl.end());
    buf.push_back('\0');
    char* res = ::mkdtemp(buf.data());
    if (!res) {
        tmpl = std::string("/tmp/") + prefix + "_XXXXXX";
        buf.assign(tmpl.begin(), tmpl.end());
        buf.push_back('\0');
        res = ::mkdtemp(buf.data());
    }
    assert(res != nullptr);
    return std::string(res);
}

} // namespace

namespace openstation {
bool ensure_sntp_time(int) { return false; }
} // namespace openstation

int main() {
    using namespace openstation;
    const std::string root = mkdtemp_dir("plugin_mgr_test");

    // 1. Validation & template substitution safety
    assert(PluginManager::is_valid_plugin_id("crypto-rates"));
    assert(PluginManager::is_valid_plugin_id("p1_test"));
    assert(!PluginManager::is_valid_plugin_id("../evil"));
    assert(!PluginManager::is_valid_plugin_id("Upper"));
    assert(PluginManager::is_valid_rel_path("run.sh"));
    assert(PluginManager::is_valid_rel_path("sub/data.json"));
    assert(!PluginManager::is_valid_rel_path("../secret"));
    assert(!PluginManager::is_valid_rel_path("/etc/passwd"));

    {
        json::Value args = json::Value::make_obj();
        args["text"] = json::Value::make_str("Привет \"мир\"\nстрока2");
        args["q"] = json::Value::make_str("a b&c");
        std::map<std::string, json::Value> settings;
        settings["tok"] = json::Value::make_str("abc");

        const std::string js = PluginManager::render_template(
            "{\"t\":\"{{args.text}}\",\"k\":\"{{settings.tok|none}}\"}", args, settings, 2);
        json::Value parsed;
        assert(json::Parser::parse(js, parsed));
        assert(parsed["t"].as_str() == "Привет \"мир\"\nстрока2");
        assert(parsed["k"].as_str() == "abc");

        const std::string u = PluginManager::render_template(
            "{{settings.base}}bot{{settings.bot_token}}/send?q={{args.q}}&m={{args.miss|def}}",
            args,
            {{"base", json::Value::make_str("https://api.telegram.org/")},
             {"bot_token", json::Value::make_str("12345:ABC-xyz")}},
            1);
        assert(u == "https://api.telegram.org/bot12345:ABC-xyz/send?q=a+b%26c&m=def");
    }

    // 2. Full catalog install (SHA-256 verified), toggle enable/disable, secrets protection, actions, tools
    {
        const std::string manifest_raw = R"JSON({
  "schemaVersion": 1,
  "id": "demo-echo",
  "name": "Demo Echo",
  "version": "1.2.0",
  "author": "Tester",
  "category": "Утилиты",
  "description": "Echo tool and shell test",
  "settingsSchema": [
    {"key": "api_secret", "label": "Secret", "type": "secret", "default": "init-secret"},
    {"key": "prefix", "label": "Prefix", "type": "string", "default": "hi"}
  ],
  "tools": [
    {
      "name": "plugin_demo_http",
      "description": "Calls HTTP endpoint",
      "parameters": {"q": {"type": "STRING", "description": "Query"}},
      "required": ["q"],
      "runner": {"type": "http", "method": "GET", "url": "https://example.test/api?q={{args.q}}&p={{settings.prefix}}"}
    }
  ],
  "actions": [
    {
      "id": "run_shell",
      "label": "Run shell",
      "runner": {"type": "shell", "command": "printf '{\"seen\":\"%s\"}' \"$OPENSTATION_PLUGIN_ID\""}
    }
  ],
  "hooks": {
    "onEnable": {"type": "station", "steps": [{"tool": "set_led_ring", "args": {"mode": "solid"}}]},
    "onDisable": {"type": "station", "steps": [{"tool": "set_led_ring", "args": {"mode": "off"}}]}
  }
})JSON";

        const std::string mf_sha = PluginManager::sha256_hex_str(manifest_raw);
        const std::string index_raw = std::string(R"({"schemaVersion":1,"plugins":[{"id":"demo-echo","name":"Demo Echo","version":"1.2.0","manifestUrl":"https://example.test/plugins/demo-echo/plugin.json","manifestSha256":")") + mf_sha + R"("}]})";

        int changed_notifications = 0;
        std::vector<std::string> builtin_calls;
        std::string last_http_url;

        PluginCallbacks cb;
        cb.http_hook = [&](const HttpClientRequest& req) -> HttpClientResponse {
            HttpClientResponse r;
            r.ok = true;
            r.status_code = 200;
            if (req.url.find("plugins/index.json") != std::string::npos) {
                r.body = index_raw;
            } else if (req.url.find("plugins/demo-echo/plugin.json") != std::string::npos) {
                r.body = manifest_raw;
            } else {
                last_http_url = req.url;
                r.body = "{\"echo\":true}";
            }
            return r;
        };
        cb.execute_builtin_tool = [&](const std::string& tname, const json::Value&) -> json::Value {
            builtin_calls.push_back(tname);
            json::Value ok = json::Value::make_obj();
            ok["ok"] = json::Value::make_bool(true);
            return ok;
        };
        cb.on_plugins_changed = [&]() { ++changed_notifications; };

        PluginManager pm;
        assert(pm.init(root + "/pm1", cb));

        std::string err;
        assert(pm.fetch_catalog("Olegu621/OPENSTATION", "main", true, err));
        assert(pm.install_from_catalog("demo-echo", "Olegu621/OPENSTATION", "main", err));
        assert(pm.has_tool("plugin_demo_http"));
        assert(pm.get_assistant_tool_decls().arr_val.size() == 1);
        assert(!pm.get_assistant_prompt_suffix().empty());
        assert(changed_notifications == 1);

        // Secret masking in marketplace JSON & prompt safety
        auto mk = pm.get_marketplace_json("Olegu621/OPENSTATION", "main");
        assert(mk["installedCount"].as_int() == 1);
        assert(mk["enabledCount"].as_int() == 1);
        assert(mk["installed"].arr_val[0]["settings"]["api_secret"].as_str() == "••••");
        assert(pm.get_assistant_prompt_suffix().find("init-secret") == std::string::npos);

        // Execute tool while enabled
        json::Value targs = json::Value::make_obj();
        targs["q"] = json::Value::make_str("test 1");
        auto tres = pm.execute_tool("plugin_demo_http", targs);
        assert(tres["ok"].as_bool(false));
        assert(last_http_url == "https://example.test/api?q=test+1&p=hi");

        // Shell action
        auto sres = pm.run_action("demo-echo", "run_shell", json::Value::make_obj());
        assert(sres["ok"].as_bool(false));
        assert(sres["json"]["seen"].as_str() == "demo-echo");

        // Disable plugin -> removes tool from Assistant and blocks execution
        assert(pm.set_enabled("demo-echo", false, err));
        assert(!pm.has_tool("plugin_demo_http"));
        assert(pm.get_assistant_tool_decls().arr_val.empty());
        assert(pm.get_assistant_prompt_suffix().empty());
        auto blocked_action = pm.run_action("demo-echo", "run_shell", json::Value::make_obj());
        assert(!blocked_action["ok"].as_bool(true));
        auto blocked_tool = pm.execute_tool("plugin_demo_http", targs);
        assert(!blocked_tool["ok"].as_bool(true));

        // Re-enable -> tools restored cleanly
        assert(pm.set_enabled("demo-echo", true, err));
        assert(pm.has_tool("plugin_demo_http"));

        // Persistence across daemon restart
        PluginManager pm2;
        assert(pm2.init(root + "/pm1", cb));
        assert(pm2.has_tool("plugin_demo_http"));

        // Uninstall
        assert(pm2.uninstall("demo-echo", err));
        assert(!pm2.has_tool("plugin_demo_http"));
        assert(pm2.get_summary_json()["installedCount"].as_int() == 0);
    }

    // 3. Reject tampered manifest SHA-256 on catalog install
    {
        PluginCallbacks cb;
        cb.http_hook = [](const HttpClientRequest& req) -> HttpClientResponse {
            HttpClientResponse r;
            r.ok = true;
            r.status_code = 200;
            if (req.url.find("plugins/index.json") != std::string::npos) {
                r.body = R"({"schemaVersion":1,"plugins":[{"id":"bad-sha","name":"Bad","version":"1.0.0","manifestUrl":"https://example.test/bad.json","manifestSha256":"0000000000000000000000000000000000000000000000000000000000000000"}]})";
            } else {
                r.body = R"({"schemaVersion":1,"id":"bad-sha","name":"Bad","version":"1.0.0"})";
            }
            return r;
        };
        PluginManager pm;
        assert(pm.init(root + "/pm_bad", cb));
        std::string err;
        assert(pm.fetch_catalog("Olegu621/OPENSTATION", "main", true, err));
        assert(!pm.install_from_catalog("bad-sha", "Olegu621/OPENSTATION", "main", err));
        assert(err.find("SHA-256") != std::string::npos);
    }

    // 4. Reject disallowed tools inside station runner (e.g. shell_exec blocked)
    {
        PluginCallbacks cb;
        bool dangerous_called = false;
        cb.execute_builtin_tool = [&](const std::string&, const json::Value&) -> json::Value {
            dangerous_called = true;
            return json::Value::make_obj();
        };
        PluginManager pm;
        assert(pm.init(root + "/pm_sec", cb));
        json::Value mf;
        assert(json::Parser::parse(R"JSON({
          "schemaVersion": 1,
          "id": "sec-test",
          "name": "Sec",
          "version": "1.0.0",
          "tools": [{
            "name": "plugin_sec_test",
            "description": "bad",
            "runner": {"type": "station", "steps": [{"tool": "shell_exec", "args": {"command": "id"}}]}
          }]
        })JSON", mf));
        std::string err;
        assert(pm.install_from_manifest(mf, "local://test", {}, false, false, err));
        auto r = pm.execute_tool("plugin_sec_test", json::Value::make_obj());
        assert(!r["ok"].as_bool(true));
        assert(!dangerous_called);
    }

    ::usleep(50000); // let detached hook finish cleanly before test exits
    std::printf("ALL_PLUGIN_MANAGER_TESTS_PASSED\n");
    return 0;
}
