#include "config.hpp"
#include "assistant/assistant_personalization.hpp"
#include <cassert>
#include <string>
#include <vector>

namespace {

void assert_contains(const std::string& hay, const std::string& needle) {
    assert(hay.find(needle) != std::string::npos);
}

void assert_not_contains(const std::string& hay, const std::string& needle) {
    assert(hay.find(needle) == std::string::npos);
}

} // namespace

int main() {
    using namespace openstation;

    DaemonConfig defaults;
    assert(defaults.assistant.agent_mode == true);
    assert(defaults.network.wifi_adb_enabled == true);
    assert(defaults.assistant.gemini_model == "gemini-3.8-live");
    assert(defaults.assistant.wake_phrase == "alisa");
    assert(defaults.led_screen.visualizer_style == "pulse_bars");

    {
        for (const char* style : {"matrix", "bold", "yandex1", "yandex2", "yandex3"}) {
            DaemonConfig cc;
            json::Value patch = json::Value::make_obj();
            patch["ledScreen"]["clockStyle"] = json::Value::make_str(style);
            cc.from_json(patch);
            cc.sanitize_and_validate();
            assert(cc.led_screen.clock_style == style);
        }
    }
    // Supported visualizer styles survive validation; unknown styles fall back.
    const std::vector<std::string> valid_vis = {
        "pulse_bars", "beat_wave", "bass_nova", "mirror_meter", "spark_burst", "vu_plasma", "track_title",
        "stock_wave", "stock_pads", "stock_bricks", "stock_fluid"
    };
    for (const auto& style : valid_vis) {
        DaemonConfig vc;
        json::Value patch = json::Value::make_obj();
        patch["ledScreen"]["visualizerStyle"] = json::Value::make_str(style);
        vc.from_json(patch);
        vc.sanitize_and_validate();
        assert(vc.led_screen.visualizer_style == style);
    }
    for (const char* legacy : {"dots", "nexus", "specterr", "particles", "bars", "wave", "fireflies", "aurora", "matrix", "anomaly", "bad"}) {
        DaemonConfig vc;
        json::Value patch = json::Value::make_obj();
        patch["ledScreen"]["visualizerStyle"] = json::Value::make_str(legacy);
        vc.from_json(patch);
        vc.sanitize_and_validate();
        assert(vc.led_screen.visualizer_style == "pulse_bars");
    }

    // gemini-3.8-live cannot be overwritten via config mutations or JSON.
    {
        DaemonConfig mc;
        mc.assistant.gemini_model = "gemini-2.5-flash";
        mc.sanitize_and_validate();
        assert(mc.assistant.gemini_model == "gemini-3.8-live");

        json::Value patch = json::Value::make_obj();
        patch["assistant"]["geminiModel"] = json::Value::make_str("gemini-2.0-flash-exp");
        mc.from_json(patch);
        mc.sanitize_and_validate();
        assert(mc.assistant.gemini_model == "gemini-3.8-live");
    }

    // All voices map deterministically to female vs male grammatical gender.
    for (const char* mv : {"Puck", "Charon", "Fenrir", "Orus"}) assert(is_male_voice_name(mv));
    for (const char* fv : {"Aoede", "Kore", "Leda", "Zephyr"}) assert(!is_male_voice_name(fv));

    // All 3 personality presets x preset names x custom names x male/female voices.
    const std::string prompt_universal = defaults.assistant.system_prompt;
    const std::string prompt_concise =
        "Ты — голосовой ассистент Алиса на Станции Макс. Отвечай максимально кратко и по делу на русском языке (1-2 предложения), сразу выполняй команды управления музыкой, громкостью, ТВ, подсветкой и поиском через доступные инструменты.";
    const std::string prompt_witty =
        "Ты — весёлая и остроумная Алиса на Станции Макс. Общайся по-русски тепло, живо и иногда с лёгкой шуткой, оставаясь полезной и используя все встроенные инструменты колонки для музыки, погоды, ТВ и умного дома.";

    const std::vector<std::string> names = {"Алиса", "Джарвис", "Пятница", "Макс", "Вега", "Алёна", "Артём", "Orion"};
    for (const std::string& name : names) {
        // Female voice
        {
            const std::string u = personalize_assistant_system_prompt(prompt_universal, name, false);
            assert_contains(u, "Ты — девушка, голосовая помощница " + name);
            assert_contains(u, "в женском роде («я готова»");
            assert_not_contains(u, "в мужском роде");

            const std::string c = personalize_assistant_system_prompt(prompt_concise, name, false);
            assert_contains(c, "Ты — девушка, голосовая помощница " + name);

            const std::string w = personalize_assistant_system_prompt(prompt_witty, name, false);
            assert_contains(w, "Ты — весёлая и остроумная " + name);
            assert_contains(w, "оставаясь полезной");
            assert_not_contains(w, "оставаясь полезным");
        }
        // Male voice
        {
            const std::string u = personalize_assistant_system_prompt(prompt_universal, name, true);
            assert_contains(u, "Ты — голосовой ассистент " + name);
            assert_contains(u, "в мужском роде («я готов»");
            assert_not_contains(u, "девушка, голосовая помощница");
            assert_not_contains(u, "в женском роде");

            const std::string c = personalize_assistant_system_prompt(prompt_concise, name, true);
            assert_contains(c, "Ты — голосовой ассистент " + name);

            const std::string w = personalize_assistant_system_prompt(prompt_witty, name, true);
            assert_contains(w, "Ты — весёлый и остроумный " + name);
            assert_contains(w, "оставаясь полезным");
            assert_not_contains(w, "весёлая и остроумная");
            assert_not_contains(w, "оставаясь полезной");
        }
    }

    // Blank raw name falls back cleanly to Алиса.
    assert_contains(personalize_assistant_system_prompt(prompt_witty, "   ", true), "Ты — весёлый и остроумный Алиса");

    DaemonConfig config;
    const std::string selected = "Ты — голосовой ассистент Алиса. Отвечай коротко.";
    config.assistant.system_prompt = selected;
    config.assistant.name = "Джарвис";
    config.assistant.gemini_api_key = "secret-gemini-key";
    config.home_assistant.url = "http://192.168.0.5:8123/";
    config.home_assistant.token = "ha-secret-token";
    AlarmEntry al;
    al.id = 1;
    al.time = "07:30";
    al.label = "Подъём";
    al.days = {true, true, true, true, true, false, false};
    al.sound = "chime";
    config.alarms.push_back(al);
    config.sanitize_and_validate();
    assert(config.assistant.system_prompt == selected);
    assert(config.assistant.name == "Джарвис");
    assert(config.home_assistant.url == "http://192.168.0.5:8123");

    DaemonConfig restored;
    restored.from_json(config.to_json());
    restored.sanitize_and_validate();
    assert(restored.assistant.system_prompt == selected);
    assert(restored.assistant.name == "Джарвис");
    {
        DaemonConfig long_config;
        std::string prompt(40957, 'x');
        prompt += "я";
        prompt += "z";
        assert(prompt.size() == 40960);
        long_config.assistant.system_prompt = prompt;
        long_config.sanitize_and_validate();
        assert(long_config.assistant.system_prompt == prompt);
        DaemonConfig roundtrip;
        json::Value parsed;
        assert(json::Parser::parse(long_config.to_json().dump(-1), parsed));
        roundtrip.from_json(parsed);
        assert(roundtrip.assistant.system_prompt == prompt);
        long_config.assistant.system_prompt = std::string(40959, 'x') + "я";
        long_config.sanitize_and_validate();
        assert(long_config.assistant.system_prompt == std::string(40959, 'x'));
        long_config.assistant.system_prompt = std::string(40961, 'x');
        long_config.sanitize_and_validate();
        assert(long_config.assistant.system_prompt == std::string(40960, 'x'));
    }
    assert(restored.alarms.size() == 1 && restored.alarms[0].time == "07:30");

    // Simulate WebUI saving empty secrets (must preserve existing tokens)
    json::Value empty_secrets = json::Value::make_obj();
    empty_secrets["assistant"]["geminiApiKey"] = json::Value::make_str("");
    empty_secrets["homeAssistant"]["token"] = json::Value::make_str("");
    restored.from_json(empty_secrets);
    assert(restored.assistant.gemini_api_key == "secret-gemini-key");
    assert(restored.home_assistant.token == "ha-secret-token");

    restored.assistant.system_prompt.clear();
    restored.assistant.name.clear();
    restored.sanitize_and_validate();
    assert(!restored.assistant.system_prompt.empty());
    assert(!restored.assistant.name.empty());
    assert(restored.assistant.gemini_model == "gemini-3.8-live");

    // wakePhrase validation & JSON roundtrip
    {
        DaemonConfig wp;
        json::Value patch = json::Value::make_obj();
        patch["assistant"]["wakePhrase"] = json::Value::make_str("yandex");
        wp.from_json(patch);
        wp.sanitize_and_validate();
        assert(wp.assistant.wake_phrase == "yandex");
        json::Value dumped = wp.to_json();
        assert(dumped.get("assistant") && dumped.get("assistant")->get("wakePhrase") &&
               dumped.get("assistant")->get("wakePhrase")->as_str() == "yandex");

        DaemonConfig wp_rt;
        wp_rt.from_json(dumped);
        wp_rt.sanitize_and_validate();
        assert(wp_rt.assistant.wake_phrase == "yandex");

        patch["assistant"]["wakePhrase"] = json::Value::make_str("unknown_phrase");
        wp_rt.from_json(patch);
        wp_rt.sanitize_and_validate();
        assert(wp_rt.assistant.wake_phrase == "alisa");
    }
}
