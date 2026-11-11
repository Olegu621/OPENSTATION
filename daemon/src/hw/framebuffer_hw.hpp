#pragma once

#include "config.hpp"
#include "utils/json_min.hpp"

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

enum class VoiceVisualState {
    Idle,
    Listening,
    Thinking,
    Speaking
};

struct Rgb {
    uint8_t r = 0;
    uint8_t g = 0;
    uint8_t b = 0;
};

class FramebufferHw {
public:
    FramebufferHw() = default;
    ~FramebufferHw();

    void start(const LedRingConfig& ring_cfg, const LedScreenConfig& screen_cfg);
    void stop();

    void update_config(const LedRingConfig& ring_cfg, const LedScreenConfig& screen_cfg);
    void set_spectrum(const std::array<float, 24>& bands);
    void set_voice_state(VoiceVisualState st);
    void set_mic_muted(bool muted);
    void trigger_volume_osd(int percent);
    void update_volume_osd_if_active(int percent);
    void trigger_flash(uint8_t r, uint8_t g, uint8_t b, int duration_ms = 600);
    void trigger_screen_text(const std::string& text, uint8_t r = 0, uint8_t g = 212, uint8_t b = 255, int duration_ms = 3500);
    // Calibration helper: idx >= 0 highlights a single physical LED (before ring_offset)
    // and idx == -2 runs RGB byte-order test ([0..7]=R, [8..15]=G, [16..23]=B).
    void trigger_calibration(int cal_mode, int duration_ms = 4000);

    double current_lux() const { return last_lux_.load(); }
    int effective_brightness() const { return eff_brightness_.load(); }
    bool screen_present() const { return fb5_present_.load(); }
    bool needs_spectrum() const { return needs_spectrum_.load(std::memory_order_relaxed); }

    json::Value get_status_json();

private:
    struct FbDevice {
        int fd = -1;
        std::string dev_path;
        std::string bl_path;
        uint32_t xres = 0;
        uint32_t yres = 0;
        uint32_t bpp = 0;
        uint32_t line_length = 0;
        uint32_t red_off = 0;
        uint32_t green_off = 8;
        uint32_t blue_off = 16;
        uint32_t grayscale = 0;
        bool pan_supported = false;
        bool has_prev = false;
        std::vector<uint8_t> buf;
        std::vector<uint8_t> prev_buf;
    };

    void worker_loop();
    bool open_fb_by_name(const char* preferred_name, const char* fallback_dev, FbDevice& out);
    void close_fb(FbDevice& dev);
    void flush_fb(FbDevice& dev);
    void write_backlight(const std::string& bl_path, int value, int max_val);
    void poll_sensors();
    void update_needs_spectrum_locked();

    void render_ring(double t_sec, const LedRingConfig& cfg);
    void render_screen(double t_sec, const LedScreenConfig& cfg);
    void set_pixel(FbDevice& dev, uint32_t x, uint32_t y, uint8_t r, uint8_t g, uint8_t b);

    std::atomic<bool> running_{false};
    std::thread worker_;

    mutable std::mutex mu_;
    std::condition_variable cv_;
    uint64_t wake_seq_ = 0;
    std::atomic<bool> needs_spectrum_{false};
    LedRingConfig ring_cfg_;
    LedScreenConfig screen_cfg_;
    std::array<float, 24> spectrum_{};
    std::array<Rgb, 24> ring_pixels_{};
    VoiceVisualState voice_state_ = VoiceVisualState::Idle;
    bool mic_muted_ = false;

    std::chrono::steady_clock::time_point osd_until_{};
    int osd_volume_pct_ = 0;
    std::chrono::steady_clock::time_point flash_until_{};
    Rgb flash_color_{};
    std::chrono::steady_clock::time_point cal_until_{};
    int cal_mode_ = -1;
    std::chrono::steady_clock::time_point scr_text_until_{};
    std::string scr_override_text_;
    Rgb scr_override_color_{0, 212, 255};
    std::chrono::steady_clock::time_point scr_override_start_{};

    FbDevice fb4_;
    FbDevice fb5_;
    int fb4_max_bl_ = 255;
    int fb5_max_bl_ = 255;
    int last_fb4_bl_ = -1;
    int last_fb5_bl_ = -1;

    std::atomic<double> last_lux_{10.0};
    std::atomic<double> soc_temp_c_{0.0};
    std::atomic<int> eff_brightness_{160};
    std::atomic<bool> fb4_present_{false};
    std::atomic<bool> fb5_present_{false};
    int scroll_px_ = 0;
    double last_scroll_t_ = 0.0;
};

} // namespace openstation
