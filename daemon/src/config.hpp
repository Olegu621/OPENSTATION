#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include "utils/json_min.hpp"

namespace openstation {

struct LedRingConfig {
    std::string mode = "rainbow";  // off, solid, breathe, spinner, rainbow, music, voice
    std::string color = "#00d4ff";
    std::string secondary_color = "#7c3aed";
    int brightness = 160;          // 0..255 manual floor/level
    bool auto_brightness = true;   // ltr308als01 adaptive
    int min_auto_brightness = 18;
    int max_auto_brightness = 255;
    int speed = 55;                // 1..100
    int ring_offset = 0;           // 0..23 LED index calibration
    bool clockwise = true;
    bool bgr_order_override = false; // auto-detected via FBIOGET_VSCREENINFO by default
};

struct LedScreenConfig {
    std::string mode = "clock";    // off, clock, text, spectrum, snake
    std::string text = "OPEN STATION MAX";
    std::string color = "#ffffff";
    int brightness = 100;          // 0..100 or max_brightness
    bool auto_brightness = true;
    int scroll_speed = 45;
};

struct AudioConfig {
    std::string output_route = "auto"; // auto, speakers, headphones, both, hdmi
    int woofer_volume = 169;           // live mirror of current SY6045S 2-002b register (0..255)
    int tweeters_volume = 169;         // live mirror of current SY6045S 2-002a register (0..255)
    int headphone_volume = 180;        // live mirror of current ES8156 DAC register (0..255)
    int woofer_trim = 0;               // -40..+40 half-dB relative balance offset (-20..+20 dB)
    int tweeters_trim = 0;             // -40..+40 half-dB relative balance offset (-20..+20 dB)
    int headphone_trim = 0;            // -40..+40 half-dB relative balance offset (-20..+20 dB)
    bool woofer_eq = true;
    bool woofer_drc = true;
    bool tweeters_eq = true;
    bool tweeters_drc = true;
    std::string eq_preset = "balanced"; // flat, balanced, bass_boost, vocal, night, custom
    int pdm_gain = 3;                   // 0..8 (~6dB * val)
    int pdm_filter = 1;                 // 0..4
    bool loopback_enable = true;
    bool aec_enable = true;
    bool ns_enable = true;
    bool mic_muted = false;
    std::string capture_mode = "alsa_16ch"; // alsa_16ch (raw 16ch /dev/snd/pcmC0D1c, low CPU) or aaudio (1ch 16k VQE/shared)
    int primary_mic_channel = 1;       // 0..15 or -1 = auto beamform average
    int loopback_channel = 8;          // reference channel in 16ch frame (calibrated empirically)
};

struct InputConfig {
    bool rotary_invert = false;
    int rotary_step = 1;                  // volume steps per detent
    std::string action_short = "assistant"; // assistant, wyoming, play_pause, led_cycle, launcher, none
    std::string action_long = "led_cycle";
};

struct AssistantConfig {
    bool enabled = true;
    std::string mode = "gemini"; // gemini, wyoming, off
    std::string gemini_api_key;
    std::string gemini_model = "gemini-3.8-live";
    std::string gemini_proxy_host = "ai-pool.comss.one"; // SNI proxy fallback on geo-block (1007)
    std::string voice_name = "Aoede";
    std::string system_prompt =
        "Ты — голосовой ассистент Алиса на умной колонке Яндекс Станция Макс с открытой прошивкой OpenStation Max (полный root-доступ и живой доступ в интернет). "
        "Отвечай по-русски живо, дружелюбно, естественно и кратко (1-3 предложения, если не просят подробнее). "
        "Обязательно используй локальные инструменты: web_search, get_weather, fetch_url и http_request для погоды, новостей, курсов валют, любых фактов и умного дома; "
        "shell_exec, system_info, device_power, set_volume, set_eq_preset, set_audio_route, play_sound, set_mic_mute, set_led_ring, set_led_screen, "
        "show_screen_banner, manage_timer, tv_hdmi_cec, remote_control, type_text, music_control, manage_apps и mqtt_publish для полного управления колонкой и Android TV.";
    bool wake_word_enabled = true;
    std::string kws_keywords = "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";
    float kws_threshold = 0.20f;
    float kws_score = 1.5f;
    int kws_threads = 1;
    bool chime_enabled = true;
    bool google_search = false;
    bool echo_guard = true;
    int pre_roll_ms = 450;
    int follow_up_sec = 8;
    int max_session_sec = 120;
    int vad_silence_ms = 900;
};

struct WyomingConfig {
    bool enabled = true;
    int port = 10700;
    std::string device_name = "Station Max";
    bool always_stream = false;       // true for HA openWakeWord continuous stream, false for VAD/Button
    int vad_threshold = 450;          // RMS threshold for VAD
    int mic_sample_rate = 16000;
};

struct MqttConfig {
    bool enabled = false;
    std::string host = "192.168.1.10";
    int port = 1883;
    std::string username;
    std::string password;
    std::string base_topic = "openstation/station_max";
    bool discovery = true;
};

struct OtaConfig {
    bool auto_check = true;
    bool sync_bootanimation = true;
    std::string repo = "Olegu621/OPENSTATION";
    std::string branch = "main";
};

struct DaemonConfig {
    std::string path = "/data/local/openstation/config.json";
    int web_port = 8080;
    std::string device_hostname = "station-max";
    bool setup_completed = false;
    LedRingConfig led_ring;
    LedScreenConfig led_screen;
    AudioConfig audio;
    InputConfig input;
    AssistantConfig assistant;
    WyomingConfig wyoming;
    MqttConfig mqtt;
    OtaConfig ota;

    json::Value to_json() const;
    void from_json(const json::Value& v);
    void sanitize_and_validate();
    bool load();
    bool save() const;
};

} // namespace openstation
