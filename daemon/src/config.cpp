#include "config.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <sys/stat.h>
#include <unistd.h>

namespace openstation {

void apply_eq_preset_defaults(const std::string& preset,
                              std::array<float, 10>& out_bands,
                              int& out_bass_boost) {
    if (preset == "flat") {
        out_bands = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
        out_bass_boost = 0;
    } else if (preset == "bass_boost") {
        out_bands = {7.5f, 6.0f, 4.0f, 1.5f, 0.0f, 0.0f, 0.5f, 1.5f, 2.0f, 1.5f};
        out_bass_boost = 80;
    } else if (preset == "vocal") {
        out_bands = {-3.0f, -2.0f, 0.0f, 1.5f, 3.0f, 4.5f, 4.0f, 2.0f, 0.5f, -1.0f};
        out_bass_boost = 10;
    } else if (preset == "night") {
        out_bands = {-6.0f, -4.5f, -1.5f, 0.5f, 1.5f, 2.0f, 1.5f, 0.5f, -1.0f, -2.0f};
        out_bass_boost = 5;
    } else if (preset == "rock") {
        out_bands = {4.5f, 3.5f, 1.5f, -1.0f, -1.5f, 0.5f, 2.5f, 3.5f, 4.0f, 3.5f};
        out_bass_boost = 55;
    } else if (preset == "electronic") {
        out_bands = {5.5f, 4.5f, 2.0f, 0.0f, -1.0f, 1.0f, 2.5f, 4.0f, 5.0f, 4.5f};
        out_bass_boost = 70;
    } else {
        // balanced (default)
        out_bands = {2.0f, 1.5f, 0.5f, 0.0f, 0.0f, 0.5f, 1.0f, 1.5f, 1.5f, 1.0f};
        out_bass_boost = 30;
    }
}

namespace {

bool is_one_of(const std::string& val, std::initializer_list<const char*> choices) {
    for (const char* c : choices) {
        if (val == c) return true;
    }
    return false;
}

std::string sanitize_hostname(const std::string& raw) {
    std::string out;
    out.reserve(64);
    for (unsigned char uc : raw) {
        char lc = static_cast<char>(std::tolower(uc));
        if ((lc >= 'a' && lc <= 'z') || (lc >= '0' && lc <= '9') || lc == '-') {
            out.push_back(lc);
        } else if (lc == ' ' || lc == '_') {
            out.push_back('-');
        }
        if (out.size() >= 63) break;
    }
    while (!out.empty() && out.front() == '-') out.erase(out.begin());
    while (!out.empty() && out.back() == '-') out.pop_back();
    return out.empty() ? std::string("station-max") : out;
}

std::string sanitize_topic(const std::string& raw) {
    std::string out;
    out.reserve(128);
    for (unsigned char uc : raw) {
        if (uc <= 0x20 || uc >= 0x7F) continue;
        char c = static_cast<char>(uc);
        if (c == '#' || c == '+') continue;
        out.push_back(c);
        if (out.size() >= 128) break;
    }
    while (!out.empty() && out.front() == '/') out.erase(out.begin());
    while (!out.empty() && out.back() == '/') out.pop_back();
    return out.empty() ? std::string("openstation/station_max") : out;
}

std::string sanitize_github_repo(const std::string& raw) {
    std::string s;
    s.reserve(raw.size());
    for (unsigned char uc : raw) {
        if (uc <= 0x20 || uc >= 0x7F) continue;
        s.push_back(static_cast<char>(uc));
    }
    const std::string p1 = "https://github.com/";
    const std::string p2 = "http://github.com/";
    if (s.rfind(p1, 0) == 0) s = s.substr(p1.size());
    else if (s.rfind(p2, 0) == 0) s = s.substr(p2.size());
    while (!s.empty() && s.front() == '/') s.erase(s.begin());
    while (!s.empty() && s.back() == '/') s.pop_back();
    if (s.size() > 4 && s.substr(s.size() - 4) == ".git") s.resize(s.size() - 4);
    size_t slash = s.find('/');
    if (slash == std::string::npos || slash == 0 || slash + 1 >= s.size() ||
        s.find('/', slash + 1) != std::string::npos || s.find("..") != std::string::npos ||
        s.size() > 128) {
        return "Olegu621/OPENSTATION";
    }
    std::string owner = s.substr(0, slash);
    std::string repo = s.substr(slash + 1);
    auto valid_part = [](const std::string& p) {
        if (p.empty() || p == "." || p == "..") return false;
        for (unsigned char c : p) {
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    };
    if (!valid_part(owner) || !valid_part(repo)) return "Olegu621/OPENSTATION";
    return s;
}

std::string sanitize_git_branch(const std::string& raw) {
    std::string s;
    s.reserve(raw.size());
    for (unsigned char uc : raw) {
        if (uc <= 0x20 || uc >= 0x7F) continue;
        char c = static_cast<char>(uc);
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == '/') {
            s.push_back(c);
        }
        if (s.size() >= 96) break;
    }
    while (!s.empty() && s.front() == '/') s.erase(s.begin());
    while (!s.empty() && s.back() == '/') s.pop_back();
    if (s.empty() || s == "." || s == ".." || s.find("..") != std::string::npos ||
        s.find("//") != std::string::npos) {
        return "main";
    }
    return s;
}

} // namespace

void DaemonConfig::sanitize_and_validate() {
    web_port = (web_port >= 1 && web_port <= 65535) ? web_port : 8080;
    device_hostname = sanitize_hostname(device_hostname);

    if (led_ring.mode == "spectrum") {
        led_ring.mode = "music";
    }
    if (!is_one_of(led_ring.mode, {"off", "solid", "breathe", "spinner", "rainbow", "music", "voice"})) {
        led_ring.mode = "rainbow";
    }
    if (led_ring.color.empty()) led_ring.color = "#00d4ff";
    if (led_ring.secondary_color.empty()) led_ring.secondary_color = "#7c3aed";
    led_ring.brightness = std::clamp(led_ring.brightness, 0, 255);
    led_ring.min_auto_brightness = std::clamp(led_ring.min_auto_brightness, 0, 255);
    led_ring.max_auto_brightness = std::clamp(led_ring.max_auto_brightness, 0, 255);
    if (led_ring.min_auto_brightness > led_ring.max_auto_brightness) {
        std::swap(led_ring.min_auto_brightness, led_ring.max_auto_brightness);
    }
    led_ring.speed = std::clamp(led_ring.speed, 1, 100);
    led_ring.ring_offset = std::clamp(led_ring.ring_offset, 0, 23);

    if (!is_one_of(led_screen.mode, {"off", "clock", "text", "spectrum", "snake"})) {
        led_screen.mode = "clock";
    }
    if (!is_one_of(led_screen.clock_style, {"classic", "minimal", "bold", "seconds", "binary", "neon", "retro"})) {
        led_screen.clock_style = "classic";
    }
    if (!is_one_of(led_screen.visualizer_style, {"dots", "nexus", "specterr", "particles", "bars", "wave", "fireflies", "aurora", "matrix", "anomaly"})) {
        led_screen.visualizer_style = "dots";
    }
    if (led_screen.text.size() > 256) led_screen.text.resize(256);
    if (led_screen.color.empty()) led_screen.color = "#ffffff";
    led_screen.brightness = std::clamp(led_screen.brightness, 0, 100);
    led_screen.scroll_speed = std::clamp(led_screen.scroll_speed, 1, 100);

    if (!is_one_of(audio.output_route, {"auto", "speakers", "headphones", "both", "hdmi"})) {
        audio.output_route = "auto";
    }
    audio.woofer_volume = std::clamp(audio.woofer_volume, 0, 255);
    audio.tweeters_volume = std::clamp(audio.tweeters_volume, 0, 255);
    audio.headphone_volume = std::clamp(audio.headphone_volume, 0, 255);
    audio.woofer_trim = std::clamp(audio.woofer_trim, -40, 40);
    audio.tweeters_trim = std::clamp(audio.tweeters_trim, -40, 40);
    audio.headphone_trim = std::clamp(audio.headphone_trim, -40, 40);
    if (!is_one_of(audio.eq_preset, {"flat", "balanced", "bass_boost", "vocal", "night", "rock", "electronic", "custom"})) {
        audio.eq_preset = "balanced";
    }
    for (float& g : audio.eq_bands) {
        if (!std::isfinite(g)) g = 0.0f;
        g = std::clamp(g, -12.0f, 12.0f);
    }
    audio.bass_boost = std::clamp(audio.bass_boost, 0, 100);

    if (location.city.size() > 128) location.city.resize(128);
    if (location.resolved_city.size() > 128) location.resolved_city.resize(128);
    if (location.resolved_country.size() > 128) location.resolved_country.resize(128);
    if (!std::isfinite(location.lat)) location.lat = 0.0;
    if (!std::isfinite(location.lon)) location.lon = 0.0;
    location.lat = std::clamp(location.lat, -90.0, 90.0);
    location.lon = std::clamp(location.lon, -180.0, 180.0);
    audio.pdm_gain = std::clamp(audio.pdm_gain, 0, 8);
    audio.pdm_filter = std::clamp(audio.pdm_filter, 0, 4);
    audio.mic_sensitivity = std::clamp(audio.mic_sensitivity, 10, 100);
    if (!is_one_of(audio.capture_mode, {"aaudio", "alsa_16ch"})) {
        audio.capture_mode = "aaudio";
    }
    audio.primary_mic_channel = std::clamp(audio.primary_mic_channel, -1, 15);
    audio.loopback_channel = std::clamp(audio.loopback_channel, 0, 15);

    input.rotary_step = std::clamp(input.rotary_step, 1, 5);
    if (!is_one_of(input.action_short, {"assistant", "wyoming", "play_pause", "led_cycle", "launcher", "none"})) {
        input.action_short = "assistant";
    }
    if (!is_one_of(input.action_long, {"assistant", "wyoming", "play_pause", "led_cycle", "launcher", "none"})) {
        input.action_long = "led_cycle";
    }

    if (!is_one_of(assistant.mode, {"gemini", "wyoming", "off"})) {
        assistant.mode = "gemini";
    }
    if (assistant.gemini_api_key.size() > 256) assistant.gemini_api_key.resize(256);
    while (!assistant.gemini_api_key.empty() &&
           (assistant.gemini_api_key.back() == '\n' || assistant.gemini_api_key.back() == '\r' || assistant.gemini_api_key.back() == ' ')) {
        assistant.gemini_api_key.pop_back();
    }
    if (assistant.gemini_model.empty() || assistant.gemini_model.size() > 96) {
        assistant.gemini_model = "gemini-3.8-live";
    }
    {
        std::string clean_proxy;
        clean_proxy.reserve(assistant.gemini_proxy_host.size());
        for (unsigned char uc : assistant.gemini_proxy_host) {
            if (uc <= 0x20 || uc >= 0x7F) continue;
            clean_proxy.push_back(static_cast<char>(uc));
            if (clean_proxy.size() >= 128) break;
        }
        assistant.gemini_proxy_host = std::move(clean_proxy);
    }
    if (assistant.voice_name.empty() || assistant.voice_name.size() > 32) {
        assistant.voice_name = "Aoede";
    }
    if (assistant.system_prompt.empty() ||
        assistant.system_prompt.rfind("Ты — голосовой ассистент", 0) == 0) {
        assistant.system_prompt = DaemonConfig().assistant.system_prompt;
    }
    if (assistant.system_prompt.size() > 4096) {
        assistant.system_prompt.resize(4096);
    }
    if (assistant.kws_keywords.empty() || assistant.kws_keywords.size() > 512) {
        assistant.kws_keywords = "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";
    }
    if (!std::isfinite(assistant.kws_threshold)) assistant.kws_threshold = 0.20f;
    assistant.kws_threshold = std::clamp(assistant.kws_threshold, 0.01f, 0.99f);
    if (!std::isfinite(assistant.kws_score)) assistant.kws_score = 1.5f;
    assistant.kws_score = std::clamp(assistant.kws_score, 0.1f, 10.0f);
    assistant.kws_threads = std::clamp(assistant.kws_threads, 1, 4);
    assistant.pre_roll_ms = std::clamp(assistant.pre_roll_ms, 0, 2000);
    assistant.follow_up_sec = std::clamp(assistant.follow_up_sec, 0, 30);
    assistant.max_session_sec = std::clamp(assistant.max_session_sec, 15, 600);
    assistant.vad_silence_ms = std::clamp(assistant.vad_silence_ms, 300, 3000);
    {
        std::vector<std::string> clean_notes;
        clean_notes.reserve(assistant.memory_notes.size());
        for (std::string n : assistant.memory_notes) {
            while (!n.empty() && (n.front() == ' ' || n.front() == '\t' || n.front() == '\r' || n.front() == '\n')) n.erase(n.begin());
            while (!n.empty() && (n.back() == ' ' || n.back() == '\t' || n.back() == '\r' || n.back() == '\n')) n.pop_back();
            if (n.empty()) continue;
            if (n.size() > 240) n.resize(240);
            if (std::find(clean_notes.begin(), clean_notes.end(), n) == clean_notes.end()) {
                clean_notes.push_back(std::move(n));
                if (clean_notes.size() >= 25) break;
            }
        }
        assistant.memory_notes = std::move(clean_notes);
    }

    wyoming.port = (wyoming.port >= 1 && wyoming.port <= 65535) ? wyoming.port : 10700;
    wyoming.vad_threshold = std::clamp(wyoming.vad_threshold, 0, 32767);
    wyoming.mic_sample_rate = std::clamp(wyoming.mic_sample_rate, 8000, 48000);

    mqtt.port = (mqtt.port >= 1 && mqtt.port <= 65535) ? mqtt.port : 1883;
    mqtt.base_topic = sanitize_topic(mqtt.base_topic);

    ota.repo = sanitize_github_repo(ota.repo);
    ota.branch = sanitize_git_branch(ota.branch);

    if (network.wifi_adb_port < 1024 || network.wifi_adb_port > 65535) {
        network.wifi_adb_port = 5555;
    }
}

json::Value DaemonConfig::to_json() const {
    using json::Value;
    Value root = Value::make_obj();
    root["webPort"] = Value::make_int(web_port);
    root["deviceHostname"] = Value::make_str(device_hostname);
    root["setupCompleted"] = Value::make_bool(setup_completed);

    Value lr = Value::make_obj();
    lr["mode"] = Value::make_str(led_ring.mode);
    lr["color"] = Value::make_str(led_ring.color);
    lr["secondaryColor"] = Value::make_str(led_ring.secondary_color);
    lr["brightness"] = Value::make_int(led_ring.brightness);
    lr["autoBrightness"] = Value::make_bool(led_ring.auto_brightness);
    lr["minAutoBrightness"] = Value::make_int(led_ring.min_auto_brightness);
    lr["maxAutoBrightness"] = Value::make_int(led_ring.max_auto_brightness);
    lr["speed"] = Value::make_int(led_ring.speed);
    lr["ringOffset"] = Value::make_int(led_ring.ring_offset);
    lr["clockwise"] = Value::make_bool(led_ring.clockwise);
    lr["bgrOrderOverride"] = Value::make_bool(led_ring.bgr_order_override);
    root["ledRing"] = std::move(lr);

    Value ls = Value::make_obj();
    ls["mode"] = Value::make_str(led_screen.mode);
    ls["clockStyle"] = Value::make_str(led_screen.clock_style);
    ls["visualizerStyle"] = Value::make_str(led_screen.visualizer_style);
    ls["autoMusicVisualizer"] = Value::make_bool(led_screen.auto_music_visualizer);
    ls["text"] = Value::make_str(led_screen.text);
    ls["color"] = Value::make_str(led_screen.color);
    ls["brightness"] = Value::make_int(led_screen.brightness);
    ls["autoBrightness"] = Value::make_bool(led_screen.auto_brightness);
    ls["scrollSpeed"] = Value::make_int(led_screen.scroll_speed);
    root["ledScreen"] = std::move(ls);

    Value au = Value::make_obj();
    au["outputRoute"] = Value::make_str(audio.output_route);
    au["wooferVolume"] = Value::make_int(audio.woofer_volume);
    au["tweetersVolume"] = Value::make_int(audio.tweeters_volume);
    au["headphoneVolume"] = Value::make_int(audio.headphone_volume);
    au["wooferTrim"] = Value::make_int(audio.woofer_trim);
    au["tweetersTrim"] = Value::make_int(audio.tweeters_trim);
    au["headphoneTrim"] = Value::make_int(audio.headphone_trim);
    au["wooferEq"] = Value::make_bool(audio.woofer_eq);
    au["wooferDrc"] = Value::make_bool(audio.woofer_drc);
    au["tweetersEq"] = Value::make_bool(audio.tweeters_eq);
    au["tweetersDrc"] = Value::make_bool(audio.tweeters_drc);
    au["eqPreset"] = Value::make_str(audio.eq_preset);
    {
        Value eb = Value::make_arr();
        for (float g : audio.eq_bands) eb.push(Value::make_num(g));
        au["eqBands"] = std::move(eb);
    }
    au["bassBoost"] = Value::make_int(audio.bass_boost);
    au["pdmGain"] = Value::make_int(audio.pdm_gain);
    au["pdmFilter"] = Value::make_int(audio.pdm_filter);
    au["loopbackEnable"] = Value::make_bool(audio.loopback_enable);
    au["aecEnable"] = Value::make_bool(audio.aec_enable);
    au["nsEnable"] = Value::make_bool(audio.ns_enable);
    au["micSensitivity"] = Value::make_int(audio.mic_sensitivity);
    au["micMuted"] = Value::make_bool(audio.mic_muted);
    au["captureMode"] = Value::make_str(audio.capture_mode);
    au["primaryMicChannel"] = Value::make_int(audio.primary_mic_channel);
    au["loopbackChannel"] = Value::make_int(audio.loopback_channel);
    root["audio"] = std::move(au);

    Value loc = Value::make_obj();
    loc["autoDetect"] = Value::make_bool(location.auto_detect);
    loc["city"] = Value::make_str(location.city);
    loc["lat"] = Value::make_num(location.lat);
    loc["lon"] = Value::make_num(location.lon);
    loc["resolvedCity"] = Value::make_str(location.resolved_city);
    loc["resolvedCountry"] = Value::make_str(location.resolved_country);
    loc["resolvedEpoch"] = Value::make_int(location.resolved_epoch);
    root["location"] = std::move(loc);

    Value in = Value::make_obj();
    in["rotaryInvert"] = Value::make_bool(input.rotary_invert);
    in["rotaryStep"] = Value::make_int(input.rotary_step);
    in["actionShort"] = Value::make_str(input.action_short);
    in["actionLong"] = Value::make_str(input.action_long);
    root["input"] = std::move(in);

    Value ast = Value::make_obj();
    ast["enabled"] = Value::make_bool(assistant.enabled);
    ast["mode"] = Value::make_str(assistant.mode);
    ast["geminiApiKey"] = Value::make_str(assistant.gemini_api_key);
    ast["geminiModel"] = Value::make_str(assistant.gemini_model);
    ast["geminiProxyHost"] = Value::make_str(assistant.gemini_proxy_host);
    ast["voiceName"] = Value::make_str(assistant.voice_name);
    ast["systemPrompt"] = Value::make_str(assistant.system_prompt);
    ast["wakeWordEnabled"] = Value::make_bool(assistant.wake_word_enabled);
    ast["kwsKeywords"] = Value::make_str(assistant.kws_keywords);
    ast["kwsThreshold"] = Value::make_num(assistant.kws_threshold);
    ast["kwsScore"] = Value::make_num(assistant.kws_score);
    ast["kwsThreads"] = Value::make_int(assistant.kws_threads);
    ast["chimeEnabled"] = Value::make_bool(assistant.chime_enabled);
    ast["googleSearch"] = Value::make_bool(assistant.google_search);
    ast["agentMode"] = Value::make_bool(assistant.agent_mode);
    ast["echoGuard"] = Value::make_bool(assistant.echo_guard);
    ast["preRollMs"] = Value::make_int(assistant.pre_roll_ms);
    ast["followUpSec"] = Value::make_int(assistant.follow_up_sec);
    ast["maxSessionSec"] = Value::make_int(assistant.max_session_sec);
    ast["vadSilenceMs"] = Value::make_int(assistant.vad_silence_ms);
    {
        Value mn = Value::make_arr();
        for (const auto& s : assistant.memory_notes) mn.push(Value::make_str(s));
        ast["memoryNotes"] = std::move(mn);
    }
    root["assistant"] = std::move(ast);

    Value wy = Value::make_obj();
    wy["enabled"] = Value::make_bool(wyoming.enabled);
    wy["port"] = Value::make_int(wyoming.port);
    wy["deviceName"] = Value::make_str(wyoming.device_name);
    wy["alwaysStream"] = Value::make_bool(wyoming.always_stream);
    wy["vadThreshold"] = Value::make_int(wyoming.vad_threshold);
    wy["micSampleRate"] = Value::make_int(wyoming.mic_sample_rate);
    root["wyoming"] = std::move(wy);

    Value mq = Value::make_obj();
    mq["enabled"] = Value::make_bool(mqtt.enabled);
    mq["host"] = Value::make_str(mqtt.host);
    mq["port"] = Value::make_int(mqtt.port);
    mq["username"] = Value::make_str(mqtt.username);
    mq["password"] = Value::make_str(mqtt.password);
    mq["baseTopic"] = Value::make_str(mqtt.base_topic);
    mq["discovery"] = Value::make_bool(mqtt.discovery);
    root["mqtt"] = std::move(mq);

    Value ot = Value::make_obj();
    ot["autoCheck"] = Value::make_bool(ota.auto_check);
    ot["syncBootanimation"] = Value::make_bool(ota.sync_bootanimation);
    ot["repo"] = Value::make_str(ota.repo);
    ot["branch"] = Value::make_str(ota.branch);
    root["ota"] = std::move(ot);

    Value nw = Value::make_obj();
    nw["wifiAdbEnabled"] = Value::make_bool(network.wifi_adb_enabled);
    nw["wifiAdbPort"] = Value::make_int(network.wifi_adb_port);
    nw["wifiPowerSaveOff"] = Value::make_bool(network.wifi_power_save_off);
    root["network"] = std::move(nw);

    return root;
}

void DaemonConfig::from_json(const json::Value& v) {
    if (!v.is_obj()) return;
    if (auto* x = v.get("webPort"); x && x->is_num()) web_port = x->as_int(web_port);
    if (auto* x = v.get("deviceHostname"); x && x->is_str()) device_hostname = x->as_str(device_hostname);
    if (auto* x = v.get("setupCompleted"); x && x->is_bool()) setup_completed = x->as_bool(setup_completed);

    if (auto* lr = v.get("ledRing"); lr && lr->is_obj()) {
        if (auto* x = lr->get("mode"); x && x->is_str()) led_ring.mode = x->as_str();
        if (auto* x = lr->get("color"); x && x->is_str()) led_ring.color = x->as_str();
        if (auto* x = lr->get("secondaryColor"); x && x->is_str()) led_ring.secondary_color = x->as_str();
        if (auto* x = lr->get("brightness"); x && x->is_num()) led_ring.brightness = x->as_int();
        if (auto* x = lr->get("autoBrightness"); x && x->is_bool()) led_ring.auto_brightness = x->as_bool();
        if (auto* x = lr->get("minAutoBrightness"); x && x->is_num()) led_ring.min_auto_brightness = x->as_int();
        if (auto* x = lr->get("maxAutoBrightness"); x && x->is_num()) led_ring.max_auto_brightness = x->as_int();
        if (auto* x = lr->get("speed"); x && x->is_num()) led_ring.speed = x->as_int();
        if (auto* x = lr->get("ringOffset"); x && x->is_num()) led_ring.ring_offset = x->as_int();
        if (auto* x = lr->get("clockwise"); x && x->is_bool()) led_ring.clockwise = x->as_bool();
        if (auto* x = lr->get("bgrOrderOverride"); x && x->is_bool()) led_ring.bgr_order_override = x->as_bool();
    }

    if (auto* ls = v.get("ledScreen"); ls && ls->is_obj()) {
        if (auto* x = ls->get("mode"); x && x->is_str()) led_screen.mode = x->as_str();
        if (auto* x = ls->get("clockStyle"); x && x->is_str()) led_screen.clock_style = x->as_str();
        if (auto* x = ls->get("visualizerStyle"); x && x->is_str()) led_screen.visualizer_style = x->as_str();
        if (auto* x = ls->get("autoMusicVisualizer"); x && x->is_bool()) led_screen.auto_music_visualizer = x->as_bool();
        if (auto* x = ls->get("text"); x && x->is_str()) led_screen.text = x->as_str();
        if (auto* x = ls->get("color"); x && x->is_str()) led_screen.color = x->as_str();
        if (auto* x = ls->get("brightness"); x && x->is_num()) led_screen.brightness = x->as_int();
        if (auto* x = ls->get("autoBrightness"); x && x->is_bool()) led_screen.auto_brightness = x->as_bool();
        if (auto* x = ls->get("scrollSpeed"); x && x->is_num()) led_screen.scroll_speed = x->as_int();
    }

    if (auto* au = v.get("audio"); au && au->is_obj()) {
        if (auto* x = au->get("outputRoute"); x && x->is_str()) audio.output_route = x->as_str();
        if (auto* x = au->get("wooferVolume"); x && x->is_num()) audio.woofer_volume = x->as_int();
        if (auto* x = au->get("tweetersVolume"); x && x->is_num()) audio.tweeters_volume = x->as_int();
        if (auto* x = au->get("headphoneVolume"); x && x->is_num()) audio.headphone_volume = x->as_int();
        if (auto* x = au->get("wooferTrim"); x && x->is_num()) audio.woofer_trim = x->as_int();
        if (auto* x = au->get("tweetersTrim"); x && x->is_num()) audio.tweeters_trim = x->as_int();
        if (auto* x = au->get("headphoneTrim"); x && x->is_num()) audio.headphone_trim = x->as_int();
        if (auto* x = au->get("wooferEq"); x && x->is_bool()) audio.woofer_eq = x->as_bool();
        if (auto* x = au->get("wooferDrc"); x && x->is_bool()) audio.woofer_drc = x->as_bool();
        if (auto* x = au->get("tweetersEq"); x && x->is_bool()) audio.tweeters_eq = x->as_bool();
        if (auto* x = au->get("tweetersDrc"); x && x->is_bool()) audio.tweeters_drc = x->as_bool();
        bool preset_sent = false;
        std::string prev_preset = audio.eq_preset;
        if (auto* x = au->get("eqPreset"); x && x->is_str()) {
            audio.eq_preset = x->as_str();
            preset_sent = true;
        }
        bool bands_sent = false;
        if (auto* eb = au->get("eqBands"); eb && eb->is_arr() && !eb->arr_val.empty()) {
            for (size_t i = 0; i < 10 && i < eb->arr_val.size(); ++i) {
                audio.eq_bands[i] = static_cast<float>(eb->arr_val[i].as_double(0.0));
            }
            bands_sent = true;
        }
        bool bass_sent = false;
        if (auto* x = au->get("bassBoost"); x && x->is_num()) {
            audio.bass_boost = x->as_int();
            bass_sent = true;
        }
        if (preset_sent && audio.eq_preset != "custom" && !bands_sent &&
            (audio.eq_preset != prev_preset || !bass_sent)) {
            int p_bass = audio.bass_boost;
            apply_eq_preset_defaults(audio.eq_preset, audio.eq_bands, p_bass);
            if (!bass_sent) audio.bass_boost = p_bass;
        }
        if (auto* x = au->get("pdmGain"); x && x->is_num()) audio.pdm_gain = x->as_int();
        if (auto* x = au->get("pdmFilter"); x && x->is_num()) audio.pdm_filter = x->as_int();
        if (auto* x = au->get("loopbackEnable"); x && x->is_bool()) audio.loopback_enable = x->as_bool();
        if (auto* x = au->get("aecEnable"); x && x->is_bool()) audio.aec_enable = x->as_bool();
        if (auto* x = au->get("nsEnable"); x && x->is_bool()) audio.ns_enable = x->as_bool();
        bool has_mic_sens = false;
        if (auto* x = au->get("micSensitivity"); x && x->is_num()) {
            audio.mic_sensitivity = x->as_int();
            has_mic_sens = true;
        }
        if (auto* x = au->get("micMuted"); x && x->is_bool()) audio.mic_muted = x->as_bool();
        if (auto* x = au->get("captureMode"); x && x->is_str()) audio.capture_mode = x->as_str();
        if (auto* x = au->get("primaryMicChannel"); x && x->is_num()) {
            audio.primary_mic_channel = x->as_int();
            // One-time upgrade for pre-v1.3 configs stuck on single perimeter ch 1 -> full 7-mic array
            if (!has_mic_sens && !au->get("eqBands") && audio.primary_mic_channel == 1) {
                audio.primary_mic_channel = -1;
            }
        }
        if (auto* x = au->get("loopbackChannel"); x && x->is_num()) audio.loopback_channel = x->as_int();
    }

    if (auto* loc = v.get("location"); loc && loc->is_obj()) {
        if (auto* x = loc->get("autoDetect"); x && x->is_bool()) location.auto_detect = x->as_bool();
        if (auto* x = loc->get("city"); x && x->is_str()) location.city = x->as_str();
        if (auto* x = loc->get("lat"); x && x->is_num()) location.lat = x->as_double();
        if (auto* x = loc->get("lon"); x && x->is_num()) location.lon = x->as_double();
        if (auto* x = loc->get("resolvedCity"); x && x->is_str()) location.resolved_city = x->as_str();
        if (auto* x = loc->get("resolvedCountry"); x && x->is_str()) location.resolved_country = x->as_str();
        if (auto* x = loc->get("resolvedEpoch"); x && x->is_num()) location.resolved_epoch = x->as_i64();
    }

    if (auto* in = v.get("input"); in && in->is_obj()) {
        if (auto* x = in->get("rotaryInvert"); x && x->is_bool()) input.rotary_invert = x->as_bool();
        if (auto* x = in->get("rotaryStep"); x && x->is_num()) input.rotary_step = x->as_int();
        if (auto* x = in->get("actionShort"); x && x->is_str()) input.action_short = x->as_str();
        if (auto* x = in->get("actionLong"); x && x->is_str()) input.action_long = x->as_str();
    }

    if (auto* ast = v.get("assistant"); ast && ast->is_obj()) {
        if (auto* x = ast->get("enabled"); x && x->is_bool()) assistant.enabled = x->as_bool();
        if (auto* x = ast->get("mode"); x && x->is_str()) assistant.mode = x->as_str();
        if (auto* x = ast->get("geminiApiKey"); x && x->is_str()) assistant.gemini_api_key = x->as_str();
        if (auto* x = ast->get("geminiModel"); x && x->is_str()) assistant.gemini_model = x->as_str();
        if (auto* x = ast->get("geminiProxyHost"); x && x->is_str()) assistant.gemini_proxy_host = x->as_str();
        if (auto* x = ast->get("voiceName"); x && x->is_str()) assistant.voice_name = x->as_str();
        if (auto* x = ast->get("systemPrompt"); x && x->is_str()) assistant.system_prompt = x->as_str();
        if (auto* x = ast->get("wakeWordEnabled"); x && x->is_bool()) assistant.wake_word_enabled = x->as_bool();
        if (auto* x = ast->get("kwsKeywords"); x && x->is_str()) assistant.kws_keywords = x->as_str();
        if (auto* x = ast->get("kwsThreshold"); x && x->is_num()) assistant.kws_threshold = static_cast<float>(x->as_double());
        if (auto* x = ast->get("kwsScore"); x && x->is_num()) assistant.kws_score = static_cast<float>(x->as_double());
        if (auto* x = ast->get("kwsThreads"); x && x->is_num()) assistant.kws_threads = x->as_int();
        if (auto* x = ast->get("chimeEnabled"); x && x->is_bool()) assistant.chime_enabled = x->as_bool();
        if (auto* x = ast->get("googleSearch"); x && x->is_bool()) assistant.google_search = x->as_bool();
        if (auto* x = ast->get("agentMode"); x && x->is_bool()) assistant.agent_mode = x->as_bool();
        if (auto* x = ast->get("echoGuard"); x && x->is_bool()) assistant.echo_guard = x->as_bool();
        if (auto* x = ast->get("preRollMs"); x && x->is_num()) assistant.pre_roll_ms = x->as_int();
        if (auto* x = ast->get("followUpSec"); x && x->is_num()) assistant.follow_up_sec = x->as_int();
        if (auto* x = ast->get("maxSessionSec"); x && x->is_num()) assistant.max_session_sec = x->as_int();
        if (auto* x = ast->get("vadSilenceMs"); x && x->is_num()) assistant.vad_silence_ms = x->as_int();
        if (auto* mn = ast->get("memoryNotes"); mn && mn->is_arr()) {
            assistant.memory_notes.clear();
            for (const auto& item : mn->arr_val) {
                if (item.is_str()) assistant.memory_notes.push_back(item.as_str());
            }
        }
    }

    if (auto* wy = v.get("wyoming"); wy && wy->is_obj()) {
        if (auto* x = wy->get("enabled"); x && x->is_bool()) wyoming.enabled = x->as_bool();
        if (auto* x = wy->get("port"); x && x->is_num()) wyoming.port = x->as_int();
        if (auto* x = wy->get("deviceName"); x && x->is_str()) wyoming.device_name = x->as_str();
        if (auto* x = wy->get("alwaysStream"); x && x->is_bool()) wyoming.always_stream = x->as_bool();
        if (auto* x = wy->get("vadThreshold"); x && x->is_num()) wyoming.vad_threshold = x->as_int();
        if (auto* x = wy->get("micSampleRate"); x && x->is_num()) wyoming.mic_sample_rate = x->as_int();
    }

    if (auto* mq = v.get("mqtt"); mq && mq->is_obj()) {
        if (auto* x = mq->get("enabled"); x && x->is_bool()) mqtt.enabled = x->as_bool();
        if (auto* x = mq->get("host"); x && x->is_str()) mqtt.host = x->as_str();
        if (auto* x = mq->get("port"); x && x->is_num()) mqtt.port = x->as_int();
        if (auto* x = mq->get("username"); x && x->is_str()) mqtt.username = x->as_str();
        if (auto* x = mq->get("password"); x && x->is_str()) mqtt.password = x->as_str();
        if (auto* x = mq->get("baseTopic"); x && x->is_str()) mqtt.base_topic = x->as_str();
        if (auto* x = mq->get("discovery"); x && x->is_bool()) mqtt.discovery = x->as_bool();
    }

    if (auto* ot = v.get("ota"); ot && ot->is_obj()) {
        if (auto* x = ot->get("autoCheck"); x && x->is_bool()) ota.auto_check = x->as_bool();
        if (auto* x = ot->get("syncBootanimation"); x && x->is_bool()) ota.sync_bootanimation = x->as_bool();
        if (auto* x = ot->get("repo"); x && x->is_str()) ota.repo = x->as_str();
        if (auto* x = ot->get("branch"); x && x->is_str()) ota.branch = x->as_str();
    }

    if (auto* x = v.get("wifiAdbEnabled"); x && x->is_bool()) network.wifi_adb_enabled = x->as_bool();
    if (auto* x = v.get("wifiAdbPort"); x && x->is_num()) network.wifi_adb_port = x->as_int();
    if (auto* x = v.get("wifiPowerSaveOff"); x && x->is_bool()) network.wifi_power_save_off = x->as_bool();
    if (auto* nw = v.get("network"); nw && nw->is_obj()) {
        if (auto* x = nw->get("wifiAdbEnabled"); x && x->is_bool()) network.wifi_adb_enabled = x->as_bool();
        if (auto* x = nw->get("wifiAdbPort"); x && x->is_num()) network.wifi_adb_port = x->as_int();
        if (auto* x = nw->get("wifiPowerSaveOff"); x && x->is_bool()) network.wifi_power_save_off = x->as_bool();
    }

    sanitize_and_validate();
}

bool DaemonConfig::load() {
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) {
        LOGI("Config", "No existing config at %s, using defaults", path.c_str());
        return false;
    }
    std::string data;
    char buf[4096];
    while (size_t n = fread(buf, 1, sizeof(buf), fp)) {
        data.append(buf, n);
    }
    fclose(fp);
    json::Value v;
    if (!json::Parser::parse(data, v)) {
        LOGW("Config", "Failed to parse %s, using defaults", path.c_str());
        return false;
    }
    from_json(v);
    LOGI("Config", "Loaded config from %s", path.c_str());
    return true;
}

bool DaemonConfig::save() const {
    auto slash = path.find_last_of('/');
    if (slash != std::string::npos) {
        std::string dir = path.substr(0, slash);
        mkdir(dir.c_str(), 0755);
    }
    std::string tmp = path + ".tmp";
    FILE* fp = fopen(tmp.c_str(), "wb");
    if (!fp) {
        LOGE("Config", "Failed to open %s for writing: %s", tmp.c_str(), strerror(errno));
        return false;
    }
    std::string body = to_json().dump(2) + "\n";
    size_t written = fwrite(body.data(), 1, body.size(), fp);
    fflush(fp);
    fsync(fileno(fp));
    fclose(fp);
    if (written != body.size()) return false;
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        LOGE("Config", "Failed to rename %s -> %s: %s", tmp.c_str(), path.c_str(), strerror(errno));
        return false;
    }
    return true;
}

} // namespace openstation
