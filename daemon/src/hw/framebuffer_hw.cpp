#include "hw/framebuffer_hw.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace openstation {

namespace {

constexpr double kPi = 3.14159265358979323846;

Rgb parse_hex_color(const std::string& hex, Rgb fallback) {
    if (hex.size() == 7 && hex[0] == '#') {
        char* end = nullptr;
        unsigned long val = strtoul(hex.c_str() + 1, &end, 16);
        if (end && *end == '\0') {
            return Rgb{
                static_cast<uint8_t>((val >> 16) & 0xFF),
                static_cast<uint8_t>((val >> 8) & 0xFF),
                static_cast<uint8_t>(val & 0xFF)
            };
        }
    }
    return fallback;
}

Rgb hsv_to_rgb(float h_deg, float s, float v) {
    float h = std::fmod(h_deg, 360.0f);
    if (h < 0.0f) h += 360.0f;
    float c = v * s;
    float x = c * (1.0f - std::fabs(std::fmod(h / 60.0f, 2.0f) - 1.0f));
    float m = v - c;
    float r = 0, g = 0, b = 0;
    if (h < 60) { r = c; g = x; }
    else if (h < 120) { r = x; g = c; }
    else if (h < 180) { g = c; b = x; }
    else if (h < 240) { g = x; b = c; }
    else if (h < 300) { r = x; b = c; }
    else { r = c; b = x; }
    auto cl = [](float val) -> uint8_t {
        int iv = static_cast<int>(std::lround(val * 255.0f));
        return static_cast<uint8_t>(std::clamp(iv, 0, 255));
    };
    return Rgb{cl(r + m), cl(g + m), cl(b + m)};
}

Rgb lerp_rgb(Rgb a, Rgb b, float t) {
    t = std::clamp(t, 0.0f, 1.0f);
    return Rgb{
        static_cast<uint8_t>(a.r + (b.r - a.r) * t),
        static_cast<uint8_t>(a.g + (b.g - a.g) * t),
        static_cast<uint8_t>(a.b + (b.b - a.b) * t)
    };
}

Rgb scale_rgb(Rgb c, float k) {
    k = std::clamp(k, 0.0f, 1.0f);
    return Rgb{
        static_cast<uint8_t>(c.r * k),
        static_cast<uint8_t>(c.g * k),
        static_cast<uint8_t>(c.b * k)
    };
}

bool read_trimmed_file(const std::string& path, std::string& out) {
    FILE* fp = fopen(path.c_str(), "r");
    if (!fp) return false;
    char buf[128];
    out.clear();
    if (fgets(buf, sizeof(buf), fp)) {
        out = buf;
        while (!out.empty() && (out.back() == '\n' || out.back() == '\r' || out.back() == ' ')) {
            out.pop_back();
        }
    }
    fclose(fp);
    return !out.empty();
}

// Minimal 5x7 font (ASCII + uppercase Russian Cyrillic UTF-8 codepoints)
struct Glyph5x7 {
    uint32_t cp;
    uint8_t cols[5]; // bit 0 = top row, bit 6 = bottom row
};

constexpr Glyph5x7 kFont5x7[] = {
    {' ', {0x00,0x00,0x00,0x00,0x00}},
    {'.', {0x00,0x60,0x60,0x00,0x00}},
    {',', {0x00,0x50,0x30,0x00,0x00}},
    {':', {0x00,0x36,0x36,0x00,0x00}},
    {'-', {0x08,0x08,0x08,0x08,0x08}},
    {'+', {0x08,0x08,0x3E,0x08,0x08}},
    {'!', {0x00,0x00,0x5F,0x00,0x00}},
    {'?', {0x02,0x01,0x51,0x09,0x06}},
    {'/', {0x20,0x10,0x08,0x04,0x02}},
    {'%', {0x23,0x13,0x08,0x64,0x62}},
    {'0', {0x3E,0x51,0x49,0x45,0x3E}},
    {'1', {0x00,0x42,0x7F,0x40,0x00}},
    {'2', {0x42,0x61,0x51,0x49,0x46}},
    {'3', {0x21,0x41,0x45,0x4B,0x31}},
    {'4', {0x18,0x14,0x12,0x7F,0x10}},
    {'5', {0x27,0x45,0x45,0x45,0x39}},
    {'6', {0x3C,0x4A,0x49,0x49,0x30}},
    {'7', {0x01,0x71,0x09,0x05,0x03}},
    {'8', {0x36,0x49,0x49,0x49,0x36}},
    {'9', {0x06,0x49,0x49,0x29,0x1E}},
    {'A', {0x7E,0x11,0x11,0x11,0x7E}},
    {'B', {0x7F,0x49,0x49,0x49,0x36}},
    {'C', {0x3E,0x41,0x41,0x41,0x22}},
    {'D', {0x7F,0x41,0x41,0x22,0x1C}},
    {'E', {0x7F,0x49,0x49,0x49,0x41}},
    {'F', {0x7F,0x09,0x09,0x09,0x01}},
    {'G', {0x3E,0x41,0x49,0x49,0x7A}},
    {'H', {0x7F,0x08,0x08,0x08,0x7F}},
    {'I', {0x00,0x41,0x7F,0x41,0x00}},
    {'J', {0x20,0x40,0x41,0x3F,0x01}},
    {'K', {0x7F,0x08,0x14,0x22,0x41}},
    {'L', {0x7F,0x40,0x40,0x40,0x40}},
    {'M', {0x7F,0x02,0x0C,0x02,0x7F}},
    {'N', {0x7F,0x04,0x08,0x10,0x7F}},
    {'O', {0x3E,0x41,0x41,0x41,0x3E}},
    {'P', {0x7F,0x09,0x09,0x09,0x06}},
    {'Q', {0x3E,0x41,0x51,0x21,0x5E}},
    {'R', {0x7F,0x09,0x19,0x29,0x46}},
    {'S', {0x46,0x49,0x49,0x49,0x31}},
    {'T', {0x01,0x01,0x7F,0x01,0x01}},
    {'U', {0x3F,0x40,0x40,0x40,0x3F}},
    {'V', {0x1F,0x20,0x40,0x20,0x1F}},
    {'W', {0x3F,0x40,0x38,0x40,0x3F}},
    {'X', {0x63,0x14,0x08,0x14,0x63}},
    {'Y', {0x07,0x08,0x70,0x08,0x07}},
    {'Z', {0x61,0x51,0x49,0x45,0x43}},
    // Russian Cyrillic uppercase (and lowercase mapped to uppercase)
    {0x0410, {0x7E,0x11,0x11,0x11,0x7E}}, // А
    {0x0411, {0x7F,0x49,0x49,0x49,0x31}}, // Б
    {0x0412, {0x7F,0x49,0x49,0x49,0x36}}, // В
    {0x0413, {0x7F,0x01,0x01,0x01,0x03}}, // Г
    {0x0414, {0x60,0x3E,0x21,0x3F,0x60}}, // Д
    {0x0415, {0x7F,0x49,0x49,0x49,0x41}}, // Е
    {0x0401, {0x7C,0x55,0x54,0x55,0x44}}, // Ё
    {0x0416, {0x63,0x1C,0x7F,0x1C,0x63}}, // Ж
    {0x0417, {0x22,0x41,0x49,0x49,0x36}}, // З
    {0x0418, {0x7F,0x20,0x10,0x08,0x7F}}, // И
    {0x0419, {0x7C,0x21,0x12,0x09,0x7C}}, // Й
    {0x041A, {0x7F,0x08,0x14,0x22,0x41}}, // К
    {0x041B, {0x40,0x3E,0x01,0x01,0x7F}}, // Л
    {0x041C, {0x7F,0x02,0x0C,0x02,0x7F}}, // М
    {0x041D, {0x7F,0x08,0x08,0x08,0x7F}}, // Н
    {0x041E, {0x3E,0x41,0x41,0x41,0x3E}}, // О
    {0x041F, {0x7F,0x01,0x01,0x01,0x7F}}, // П
    {0x0420, {0x7F,0x09,0x09,0x09,0x06}}, // Р
    {0x0421, {0x3E,0x41,0x41,0x41,0x22}}, // С
    {0x0422, {0x01,0x01,0x7F,0x01,0x01}}, // Т
    {0x0423, {0x47,0x48,0x48,0x48,0x3F}}, // У
    {0x0424, {0x0E,0x11,0x7F,0x11,0x0E}}, // Ф
    {0x0425, {0x63,0x14,0x08,0x14,0x63}}, // Х
    {0x0426, {0x3F,0x20,0x20,0x3F,0x60}}, // Ц
    {0x0427, {0x07,0x08,0x08,0x08,0x7F}}, // Ч
    {0x0428, {0x7F,0x40,0x7F,0x40,0x7F}}, // Ш
    {0x0429, {0x3F,0x20,0x3F,0x20,0x7F}}, // Щ
    {0x042A, {0x01,0x7F,0x48,0x48,0x30}}, // Ъ
    {0x042B, {0x7F,0x48,0x30,0x00,0x7F}}, // Ы
    {0x042C, {0x7F,0x48,0x48,0x48,0x30}}, // Ь
    {0x042D, {0x22,0x41,0x49,0x49,0x3E}}, // Э
    {0x042E, {0x7F,0x08,0x3E,0x41,0x3E}}, // Ю
    {0x042F, {0x66,0x19,0x09,0x09,0x7F}}, // Я
};

const uint8_t* lookup_glyph(uint32_t cp) {
    if (cp >= 'a' && cp <= 'z') cp -= 32;
    else if (cp >= 0x0430 && cp <= 0x044F) cp -= 0x20; // Cyrillic а-я -> А-Я
    else if (cp == 0x0451) cp = 0x0401;                // ё -> Ё
    for (const auto& g : kFont5x7) {
        if (g.cp == cp) return g.cols;
    }
    return kFont5x7[0].cols;
}

std::vector<uint32_t> utf8_to_codepoints(const std::string& s) {
    std::vector<uint32_t> out;
    size_t i = 0;
    while (i < s.size()) {
        uint8_t c = static_cast<uint8_t>(s[i]);
        if (c < 0x80) {
            out.push_back(c);
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < s.size()) {
            out.push_back(((c & 0x1F) << 6) | (static_cast<uint8_t>(s[i + 1]) & 0x3F));
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < s.size()) {
            out.push_back(((c & 0x0F) << 12) |
                          ((static_cast<uint8_t>(s[i + 1]) & 0x3F) << 6) |
                          (static_cast<uint8_t>(s[i + 2]) & 0x3F));
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < s.size()) {
            out.push_back(((c & 0x07) << 18) |
                          ((static_cast<uint8_t>(s[i + 1]) & 0x3F) << 12) |
                          ((static_cast<uint8_t>(s[i + 2]) & 0x3F) << 6) |
                          (static_cast<uint8_t>(s[i + 3]) & 0x3F));
            i += 4;
        } else {
            ++i;
        }
    }
    return out;
}

} // namespace

FramebufferHw::~FramebufferHw() {
    stop();
}

void FramebufferHw::update_needs_spectrum_locked() {
    const bool v_speak = (voice_state_ == VoiceVisualState::Speaking);
    const bool m_vis = music_playing_ && screen_cfg_.auto_music_visualizer && (screen_cfg_.mode != "off");
    const bool ns = (ring_cfg_.mode == "music" || ring_cfg_.mode == "voice" || v_speak ||
                     (fb5_present_.load() && (screen_cfg_.mode == "spectrum" || v_speak || m_vis)));
    needs_spectrum_.store(ns, std::memory_order_relaxed);
}

void FramebufferHw::start(const LedRingConfig& ring_cfg, const LedScreenConfig& screen_cfg) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        ring_cfg_ = ring_cfg;
        screen_cfg_ = screen_cfg;
    }
    // Signal /sbin/bootlogod for a seamless zero-flicker framebuffer handoff before opening fb4/fb5
    {
        int hfd = open("/dev/.openstation_boot_handoff", O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (hfd >= 0) ::close(hfd);
        std::string open_cnt;
        if (read_trimmed_file("/sys/devices/platform/soc/ffd00000.cbus/ffd15000.spi/spi_master/spi32766/spi32766.0/lp_common/open_fb_count", open_cnt) &&
            atoi(open_cnt.c_str()) > 0) {
            usleep(130000);
        }
    }
    open_fb_by_name("YNDX LED ring", "/dev/graphics/fb4", fb4_);
    fb4_present_.store(fb4_.fd >= 0);
    if (fb4_.fd >= 0) {
        std::string max_s;
        if (read_trimmed_file(fb4_.bl_path + "/max_brightness", max_s)) {
            fb4_max_bl_ = std::max(1, atoi(max_s.c_str()));
        }
        LOGI("Framebuffer", "Ring ready: %s (%ux%u, %ubpp, stride=%u, R@%u G@%u B@%u)",
             fb4_.dev_path.c_str(), fb4_.xres, fb4_.yres, fb4_.bpp, fb4_.line_length,
             fb4_.red_off, fb4_.green_off, fb4_.blue_off);
    }

    if (open_fb_by_name("YNDX LED screen", "/dev/graphics/fb5", fb5_)) {
        fb5_expected_ = true;
        fb5_present_.store(true);
        last_fb5_ok_tp_ = std::chrono::steady_clock::now();
        std::string max_s;
        if (read_trimmed_file(fb5_.bl_path + "/max_brightness", max_s)) {
            fb5_max_bl_ = std::max(1, atoi(max_s.c_str()));
        }
        LOGI("Framebuffer", "Screen ready: %s (%ux%u, %ubpp, stride=%u, R@%u G@%u B@%u)",
             fb5_.dev_path.c_str(), fb5_.xres, fb5_.yres, fb5_.bpp, fb5_.line_length,
             fb5_.red_off, fb5_.green_off, fb5_.blue_off);
    } else {
        fb5_present_.store(false);
        LOGI("Framebuffer", "Front LED screen not connected (dynamic hotplug scan enabled)");
    }

    {
        std::lock_guard<std::mutex> lk(mu_);
        update_needs_spectrum_locked();
    }
    running_.store(true);
    worker_ = std::thread(&FramebufferHw::worker_loop, this);
}

void FramebufferHw::stop() {
    running_.store(false);
    {
        std::lock_guard<std::mutex> lk(mu_);
        ++wake_seq_;
    }
    cv_.notify_all();
    if (worker_.joinable()) {
        worker_.join();
    }
    if (fb4_.fd >= 0) {
        std::fill(fb4_.buf.begin(), fb4_.buf.end(), 0);
        fb4_.has_prev = false;
        flush_fb(fb4_);
        close_fb(fb4_);
    }
    if (fb5_.fd >= 0) {
        std::fill(fb5_.buf.begin(), fb5_.buf.end(), 0);
        fb5_.has_prev = false;
        flush_fb(fb5_);
        close_fb(fb5_);
    }
}

void FramebufferHw::update_config(const LedRingConfig& ring_cfg, const LedScreenConfig& screen_cfg) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        ring_cfg_ = ring_cfg;
        screen_cfg_ = screen_cfg;
        update_needs_spectrum_locked();
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::set_spectrum(const std::array<float, 24>& bands) {
    std::lock_guard<std::mutex> lk(mu_);
    for (size_t i = 0; i < 24; ++i) {
        spectrum_[i] = spectrum_[i] * 0.45f + bands[i] * 0.55f;
    }
}

void FramebufferHw::set_voice_state(VoiceVisualState st) {
    bool notify = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (voice_state_ != st) {
            voice_state_ = st;
            voice_state_start_ = std::chrono::steady_clock::now();
            update_needs_spectrum_locked();
            ++wake_seq_;
            notify = true;
        }
    }
    if (notify) cv_.notify_all();
}

void FramebufferHw::set_mic_muted(bool muted) {
    bool notify = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (mic_muted_ != muted) {
            mic_muted_ = muted;
            flash_color_ = muted ? Rgb{255, 24, 24} : Rgb{0, 220, 255};
            flash_until_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(650);
            ++wake_seq_;
            notify = true;
        }
    }
    if (notify) cv_.notify_all();
}

void FramebufferHw::set_music_playing(bool playing) {
    bool notify = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (playing) {
            music_stop_grace_until_ = {};
            if (!music_playing_) {
                music_playing_ = true;
                update_needs_spectrum_locked();
                ++wake_seq_;
                notify = true;
            }
        } else {
            if (music_playing_) {
                music_playing_ = false;
                music_stop_grace_until_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(300);
                update_needs_spectrum_locked();
                ++wake_seq_;
                notify = true;
            }
        }
    }
    if (notify) cv_.notify_all();
}

void FramebufferHw::trigger_weather(int temp_c, const std::string& cond_code, int duration_ms) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        weather_temp_c_ = std::clamp(temp_c, -99, 99);
        weather_cond_ = cond_code.empty() ? std::string("clear") : cond_code;
        weather_start_ = std::chrono::steady_clock::now();
        weather_until_ = weather_start_ + std::chrono::milliseconds(std::clamp(duration_ms, 2000, 30000));
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::trigger_volume_osd(int percent) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        osd_volume_pct_ = std::clamp(percent, 0, 100);
        osd_until_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(1900);
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::update_volume_osd_if_active(int percent) {
    bool notify = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (std::chrono::steady_clock::now() < osd_until_) {
            osd_volume_pct_ = std::clamp(percent, 0, 100);
            ++wake_seq_;
            notify = true;
        }
    }
    if (notify) cv_.notify_all();
}

void FramebufferHw::trigger_flash(uint8_t r, uint8_t g, uint8_t b, int duration_ms) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        flash_color_ = Rgb{r, g, b};
        flash_until_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(duration_ms);
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::trigger_screen_text(const std::string& text, uint8_t r, uint8_t g, uint8_t b, int duration_ms) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        scr_override_text_ = text;
        scr_override_color_ = Rgb{r, g, b};
        scr_override_start_ = std::chrono::steady_clock::now();
        scr_text_until_ = scr_override_start_ + std::chrono::milliseconds(std::clamp(duration_ms, 300, 30000));
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::trigger_bluetooth_visual(const std::string& bt_state, int duration_ms) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        bt_visual_state_ = bt_state;
        bt_start_ = std::chrono::steady_clock::now();
        bt_until_ = bt_start_ + std::chrono::milliseconds(std::clamp(duration_ms, 800, 30000));
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::trigger_startup_show(int duration_ms) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        startup_start_ = std::chrono::steady_clock::now();
        startup_until_ = startup_start_ + std::chrono::milliseconds(std::clamp(duration_ms, 1500, 15000));
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::trigger_task_icon(const std::string& icon_id,
                                      uint8_t r,
                                      uint8_t g,
                                      uint8_t b,
                                      int duration_ms,
                                      const std::string& custom_pixels) {
    if (icon_id.empty() && custom_pixels.empty()) return;
    {
        std::lock_guard<std::mutex> lk(mu_);
        task_icon_id_ = icon_id.empty() ? std::string("custom") : icon_id;
        task_icon_col_ = Rgb{r, g, b};
        task_icon_custom_ = custom_pixels;
        task_icon_start_ = std::chrono::steady_clock::now();
        task_icon_until_ = task_icon_start_ + std::chrono::milliseconds(std::clamp(duration_ms, 1200, 20000));
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::force_recover_screen() {
    force_recover_requested_.store(true);
    {
        std::lock_guard<std::mutex> lk(mu_);
        ++wake_seq_;
    }
    cv_.notify_all();
}

void FramebufferHw::trigger_calibration(int cal_mode, int duration_ms) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        cal_mode_ = cal_mode;
        cal_until_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(duration_ms);
        ++wake_seq_;
    }
    cv_.notify_all();
}

bool FramebufferHw::open_fb_by_name(const char* preferred_name, const char* fallback_dev, FbDevice& out) {
    std::string fb_idx_name;
    DIR* dir = opendir("/sys/class/graphics");
    if (dir) {
        struct dirent* ent;
        while ((ent = readdir(dir)) != nullptr) {
            if (strncmp(ent->d_name, "fb", 2) != 0) continue;
            std::string name_path = std::string("/sys/class/graphics/") + ent->d_name + "/name";
            std::string name_val;
            if (read_trimmed_file(name_path, name_val)) {
                if (name_val.find(preferred_name) != std::string::npos) {
                    fb_idx_name = ent->d_name;
                    break;
                }
            }
        }
        closedir(dir);
    }

    std::string dev_path = fb_idx_name.empty() ? std::string(fallback_dev)
                                               : std::string("/dev/graphics/") + fb_idx_name;
    struct stat st {};
    if (stat(dev_path.c_str(), &st) != 0) {
        return false;
    }

    int fd = open(dev_path.c_str(), O_RDWR);
    if (fd < 0) return false;

    struct fb_var_screeninfo vinfo {};
    struct fb_fix_screeninfo finfo {};
    if (ioctl(fd, FBIOGET_VSCREENINFO, &vinfo) < 0 ||
        ioctl(fd, FBIOGET_FSCREENINFO, &finfo) < 0) {
        ::close(fd);
        return false;
    }

    // Wake / unblank framebuffer controller if blanked
    ioctl(fd, FBIOBLANK, 0);

    out.fd = fd;
    out.dev_path = dev_path;
    auto slash = dev_path.find_last_of('/');
    std::string base = slash != std::string::npos ? dev_path.substr(slash + 1) : "fb4";
    out.bl_path = "/sys/class/backlight/" + base;
    out.xres = vinfo.xres > 0 ? vinfo.xres : 24;
    out.yres = vinfo.yres > 0 ? vinfo.yres : 1;
    out.bpp = vinfo.bits_per_pixel > 0 ? vinfo.bits_per_pixel : 24;
    out.line_length = finfo.line_length > 0 ? finfo.line_length : (out.xres * (out.bpp / 8));
    out.red_off = vinfo.red.offset;
    out.green_off = vinfo.green.offset;
    out.blue_off = vinfo.blue.offset;
    out.grayscale = vinfo.grayscale;
    out.has_prev = false;
    out.buf.assign(static_cast<size_t>(out.line_length) * out.yres, 0);
    return true;
}

void FramebufferHw::close_fb(FbDevice& dev) {
    if (dev.fd >= 0) {
        ::close(dev.fd);
        dev.fd = -1;
    }
    dev.dev_path.clear();
    dev.bl_path.clear();
    dev.xres = 0;
    dev.yres = 0;
    dev.bpp = 0;
    dev.line_length = 0;
    dev.red_off = 0;
    dev.green_off = 8;
    dev.blue_off = 16;
    dev.grayscale = 0;
    dev.pan_supported = false;
    dev.has_prev = false;
    dev.buf.clear();
    dev.prev_buf.clear();
}

void FramebufferHw::flush_fb(FbDevice& dev) {
    if (dev.fd < 0 || dev.buf.empty()) return;
    if (dev.has_prev && dev.prev_buf.size() == dev.buf.size() &&
        std::memcmp(dev.buf.data(), dev.prev_buf.data(), dev.buf.size()) == 0) {
        return;
    }
    ssize_t wr = pwrite(dev.fd, dev.buf.data(), dev.buf.size(), 0);
    if (wr < 0 || wr != static_cast<ssize_t>(dev.buf.size())) {
        LOGW("Framebuffer", "Write error on %s (wr=%zd, exp=%zu): %s -> auto-recovering",
             dev.dev_path.c_str(), wr, dev.buf.size(), strerror(errno));
        if (&dev == &fb5_) {
            std::lock_guard<std::mutex> lk(mu_);
            close_fb(fb5_);
            fb5_present_.store(false);
            last_fb5_bl_ = -1;
            update_needs_spectrum_locked();
        } else if (&dev == &fb4_) {
            close_fb(fb4_);
            fb4_present_.store(false);
            last_fb4_bl_ = -1;
        }
    } else {
        dev.prev_buf = dev.buf;
        dev.has_prev = true;
        if (&dev == &fb5_) {
            last_fb5_ok_tp_ = std::chrono::steady_clock::now();
        }
    }
}

void FramebufferHw::write_backlight(const std::string& bl_path, int value, int max_val) {
    std::string p = bl_path + "/brightness";
    int fd = open(p.c_str(), O_WRONLY);
    if (fd < 0) return;
    max_val = std::max(1, max_val);
    int clamped = std::clamp(value, 0, max_val);
    char tmp[16];
    int len = snprintf(tmp, sizeof(tmp), "%d\n", clamped);
    write(fd, tmp, len);
    ::close(fd);
}

void FramebufferHw::poll_sensors() {
    std::string lux_s;
    if (read_trimmed_file("/sys/bus/iio/devices/iio:device0/in_intensity_input", lux_s)) {
        double lux = strtod(lux_s.c_str(), nullptr);
        last_lux_.store(lux);
    }
    std::string temp_s;
    if (read_trimmed_file("/sys/class/thermal/thermal_zone0/temp", temp_s)) {
        double mc = strtod(temp_s.c_str(), nullptr);
        soc_temp_c_.store(mc / 1000.0);
    }
}

void FramebufferHw::set_pixel(FbDevice& dev, uint32_t x, uint32_t y, uint8_t r, uint8_t g, uint8_t b) {
    if (x >= dev.xres || y >= dev.yres) return;
    size_t row_off = static_cast<size_t>(y) * dev.line_length;
    if (dev.bpp == 8 || dev.grayscale) {
        size_t off = row_off + x;
        if (off < dev.buf.size()) {
            uint8_t lum = static_cast<uint8_t>((77 * r + 150 * g + 29 * b) >> 8);
            dev.buf[off] = lum;
        }
        return;
    }
    size_t bytes_pp = dev.bpp / 8;
    if (bytes_pp < 3) bytes_pp = 3;
    size_t off = row_off + static_cast<size_t>(x) * bytes_pp;
    if (off + bytes_pp > dev.buf.size()) return;

    uint32_t r_idx = dev.red_off / 8;
    uint32_t g_idx = dev.green_off / 8;
    uint32_t b_idx = dev.blue_off / 8;
    if (r_idx >= bytes_pp || g_idx >= bytes_pp || b_idx >= bytes_pp ||
        (r_idx == g_idx && g_idx == b_idx)) {
        r_idx = 0; g_idx = 1; b_idx = 2;
    }
    dev.buf[off + r_idx] = r;
    dev.buf[off + g_idx] = g;
    dev.buf[off + b_idx] = b;
    if (bytes_pp == 4) dev.buf[off + 3] = 0xFF;
}

void FramebufferHw::render_ring(double t_sec, const LedRingConfig& cfg) {
    std::array<Rgb, 24> logical{};
    Rgb primary = parse_hex_color(cfg.color, Rgb{0, 212, 255});
    Rgb secondary = parse_hex_color(cfg.secondary_color, Rgb{124, 58, 237});
    auto now = std::chrono::steady_clock::now();

    VoiceVisualState vstate;
    bool muted;
    std::array<float, 24> spec;
    bool osd_active = false;
    int osd_pct = 0;
    bool flash_active = false;
    Rgb flash_col{};
    bool cal_active = false;
    int cal_mode = -1;
    bool bt_active = false;
    std::string bt_state;
    double bt_elapsed = 0.0;
    bool startup_active = false;
    double startup_elapsed = 0.0;
    {
        std::lock_guard<std::mutex> lk(mu_);
        vstate = voice_state_;
        muted = mic_muted_;
        spec = spectrum_;
        osd_active = now < osd_until_;
        osd_pct = osd_volume_pct_;
        flash_active = now < flash_until_;
        flash_col = flash_color_;
        cal_active = now < cal_until_;
        cal_mode = cal_mode_;
        if (now < bt_until_) {
            bt_active = true;
            bt_state = bt_visual_state_;
            bt_elapsed = std::chrono::duration<double>(now - bt_start_).count();
        }
        if (now < startup_until_) {
            startup_active = true;
            startup_elapsed = std::chrono::duration<double>(now - startup_start_).count();
        }
    }

    // Backlight calculation (lux-adaptive or manual)
    int bl = std::clamp(cfg.brightness, 0, 255);
    if (cfg.auto_brightness) {
        int min_bl = std::clamp(cfg.min_auto_brightness, 0, 255);
        int max_bl = std::clamp(cfg.max_auto_brightness, 0, 255);
        if (min_bl > max_bl) std::swap(min_bl, max_bl);
        double lux = std::max(0.0, last_lux_.load());
        // Smooth log curve from 0.1 lux -> min_auto_brightness, 250+ lux -> max_auto_brightness
        double norm = std::clamp(std::log10(lux + 1.0) / 2.4, 0.0, 1.0);
        int target_bl = static_cast<int>(min_bl + norm * (max_bl - min_bl));
        bl = std::clamp(target_bl, min_bl, max_bl);
    }
    eff_brightness_.store(bl);
    if (std::abs(bl - last_fb4_bl_) >= 2 && fb4_.fd >= 0) {
        write_backlight(fb4_.bl_path, bl, fb4_max_bl_);
        last_fb4_bl_ = bl;
    }

    float spd = 0.25f + (std::clamp(cfg.speed, 1, 100) / 100.0f) * 2.5f;

    if (startup_active) {
        double p = startup_elapsed;
        if (p < 1.6) {
            // Dual-head symmetry sweep converging in violet -> electric cyan
            float sweep = static_cast<float>(std::min(1.0, p / 1.4));
            double head1 = std::fmod(p * 26.0, 24.0);
            double head2 = std::fmod(head1 + 12.0, 24.0);
            for (int i = 0; i < 24; ++i) {
                double d1 = std::min(std::fabs(i - head1), 24.0 - std::fabs(i - head1));
                double d2 = std::min(std::fabs(i - head2), 24.0 - std::fabs(i - head2));
                float k1 = static_cast<float>(std::max(0.0, 1.0 - d1 / 4.5));
                float k2 = static_cast<float>(std::max(0.0, 1.0 - d2 / 4.5));
                Rgb c1 = scale_rgb(Rgb{0, 212, 255}, k1);
                Rgb c2 = scale_rgb(Rgb{168, 85, 247}, k2);
                Rgb bg = scale_rgb(Rgb{24, 16, 68}, sweep * 0.45f);
                logical[i] = Rgb{
                    static_cast<uint8_t>(std::min(255, bg.r + c1.r + c2.r)),
                    static_cast<uint8_t>(std::min(255, bg.g + c1.g + c2.g)),
                    static_cast<uint8_t>(std::min(255, bg.b + c1.b + c2.b))
                };
            }
        } else {
            // Blooming aurora harmony with champagne gold sparkle
            float breath = 0.55f + 0.45f * std::sin(static_cast<float>((p - 1.6) * 3.8));
            float phase = static_cast<float>((p - 1.6) * 55.0);
            for (int i = 0; i < 24; ++i) {
                float w = 0.5f + 0.5f * std::sin(static_cast<float>(i * (2.0 * kPi / 8.0) - p * 3.2));
                Rgb c = lerp_rgb(Rgb{0, 212, 255}, Rgb{168, 85, 247}, w);
                if (i % 6 == 0) c = lerp_rgb(c, Rgb{232, 198, 134}, 0.55f * breath);
                logical[i] = scale_rgb(c, breath);
            }
            (void)phase;
        }
    } else if (cal_active) {
        if (cal_mode == -2) {
            for (int i = 0; i < 24; ++i) {
                if (i < 8) logical[i] = Rgb{255, 0, 0};
                else if (i < 16) logical[i] = Rgb{0, 255, 0};
                else logical[i] = Rgb{0, 0, 255};
            }
        } else if (cal_mode >= 0 && cal_mode < 24) {
            for (int i = 0; i < 24; ++i) {
                logical[i] = (i == cal_mode) ? Rgb{255, 255, 255} : Rgb{4, 4, 12};
            }
        }
    } else if (osd_active) {
        int lit = std::clamp((osd_pct * 24 + 50) / 100, 0, 24);
        for (int i = 0; i < 24; ++i) {
            if (i < lit) {
                float frac = static_cast<float>(i) / 23.0f;
                logical[i] = lerp_rgb(Rgb{0, 220, 255}, Rgb{124, 58, 237}, frac);
            } else {
                logical[i] = Rgb{8, 8, 12};
            }
        }
    } else if (bt_active) {
        if (bt_state == "connected") {
            float flash_k = (bt_elapsed < 1.0) ? (0.4f + 0.6f * std::sin(static_cast<float>(bt_elapsed * 9.0))) : 0.78f;
            for (int i = 0; i < 24; ++i) {
                float w = 0.5f + 0.5f * std::sin(static_cast<float>(t_sec * 3.5 + i * 0.28));
                logical[i] = scale_rgb(lerp_rgb(Rgb{0, 120, 255}, Rgb{0, 235, 255}, w), std::clamp(flash_k, 0.2f, 1.0f));
            }
        } else if (bt_state == "disconnected") {
            float pulse = 0.3f + 0.7f * std::max(0.0f, std::sin(static_cast<float>(bt_elapsed * 6.0)));
            logical.fill(scale_rgb(Rgb{255, 95, 70}, pulse));
        } else { // "discoverable" sonar orbital
            double head = std::fmod(bt_elapsed * 20.0, 24.0);
            for (int i = 0; i < 24; ++i) {
                double d = std::min(std::fabs(i - head), 24.0 - std::fabs(i - head));
                float k = static_cast<float>(std::max(0.12, 1.0 - d / 5.5));
                logical[i] = lerp_rgb(Rgb{10, 35, 160}, Rgb{0, 212, 255}, k);
            }
        }
    } else if (flash_active) {
        for (int i = 0; i < 24; ++i) logical[i] = flash_col;
    } else if (vstate != VoiceVisualState::Idle) {
        if (vstate == VoiceVisualState::Listening) {
            double head = std::fmod(t_sec * 18.0, 24.0);
            for (int i = 0; i < 24; ++i) {
                double d = std::fabs(i - head);
                d = std::min(d, 24.0 - d);
                float k = static_cast<float>(std::max(0.15, 1.0 - d / 5.0));
                logical[i] = lerp_rgb(Rgb{0, 90, 255}, Rgb{0, 255, 220}, k);
            }
        } else if (vstate == VoiceVisualState::Thinking) {
            for (int i = 0; i < 24; ++i) {
                float w = 0.5f + 0.5f * std::sin(static_cast<float>(t_sec * 7.0 + i * (2.0 * kPi / 12.0)));
                logical[i] = lerp_rgb(Rgb{32, 16, 128}, Rgb{0, 212, 255}, w);
            }
        } else { // Speaking
            float energy = 0.0f;
            for (float b : spec) energy += b;
            energy = std::clamp(energy / 10.0f, 0.2f, 1.0f);
            for (int i = 0; i < 24; ++i) {
                float w = 0.5f + 0.5f * std::sin(static_cast<float>(t_sec * 5.0 + i * 0.4));
                logical[i] = scale_rgb(lerp_rgb(Rgb{0, 200, 160}, Rgb{160, 255, 240}, w), energy);
            }
        }
    } else if (muted && cfg.mode != "off") {
        // Dedicated muted ring indication matching physical Station Max cue
        float pulse = 0.35f + 0.65f * (0.5f + 0.5f * std::sin(static_cast<float>(t_sec * 2.2)));
        Rgb rcol = scale_rgb(Rgb{255, 22, 32}, pulse);
        for (int i = 0; i < 24; ++i) logical[i] = rcol;
    } else if (cfg.mode == "off") {
        logical.fill(Rgb{0, 0, 0});
    } else if (cfg.mode == "solid") {
        logical.fill(primary);
    } else if (cfg.mode == "breathe") {
        float w = 0.20f + 0.80f * (0.5f + 0.5f * std::sin(static_cast<float>(t_sec * spd * 1.8)));
        logical.fill(scale_rgb(primary, w));
    } else if (cfg.mode == "spinner") {
        double head = std::fmod(t_sec * spd * 10.0, 24.0);
        for (int i = 0; i < 24; ++i) {
            double d = std::fmod(head - i + 24.0, 24.0);
            float k = (d < 8.0) ? static_cast<float>(1.0 - d / 8.0) : 0.0f;
            logical[i] = lerp_rgb(scale_rgb(secondary, 0.12f), primary, k);
        }
    } else if (cfg.mode == "music" || cfg.mode == "voice") {
        for (int i = 0; i < 24; ++i) {
            float b = std::clamp(spec[i], 0.0f, 1.0f);
            float base_lvl = 0.08f + b * 0.92f;
            Rgb col = lerp_rgb(primary, secondary, b);
            logical[i] = scale_rgb(col, base_lvl);
        }
    } else { // "rainbow"
        float phase = static_cast<float>(t_sec * spd * 45.0);
        for (int i = 0; i < 24; ++i) {
            float hue = phase + (360.0f / 24.0f) * i;
            logical[i] = hsv_to_rgb(hue, 0.95f, 1.0f);
        }
    }

    // Apply brightness scale in pixel domain too so direct RGB ring shows exact intensity
    float br_k = static_cast<float>(bl) / 255.0f;
    // Note: backlight sysfs scales overall current, keep RGB vivid above low thresholds
    float pixel_scale = cfg.mode == "off" ? 0.0f : (0.25f + 0.75f * br_k);

    if (fb4_.fd >= 0) {
        std::fill(fb4_.buf.begin(), fb4_.buf.end(), 0);
        for (int phys = 0; phys < 24; ++phys) {
            int log_idx = (cal_active && cal_mode >= 0)
                              ? phys
                              : ((cfg.clockwise ? (phys - cfg.ring_offset)
                                                : (23 - phys - cfg.ring_offset)) % 24 + 24) % 24;
            Rgb c = cal_active ? logical[phys] : scale_rgb(logical[log_idx], pixel_scale);
            if (cfg.bgr_order_override) {
                set_pixel(fb4_, static_cast<uint32_t>(phys), 0, c.b, c.g, c.r);
            } else {
                set_pixel(fb4_, static_cast<uint32_t>(phys), 0, c.r, c.g, c.b);
            }
        }
        flush_fb(fb4_);
    }

    std::lock_guard<std::mutex> lk(mu_);
    for (int i = 0; i < 24; ++i) {
        ring_pixels_[i] = scale_rgb(logical[i], cfg.mode == "off" ? 0.0f : std::max(0.15f, br_k));
    }
}

namespace {

// Crisp 4x10 pixel numerals for the 25x16 front RGB matrix ("HH:MM" -> 23x10 px centered).
// Each digit stores 4 columns; bit y (0..9, LSB = top row) lights pixel (x + col, y_top + y).
constexpr uint16_t kFontDigits4x10[10][4] = {
    {0x1FE, 0x201, 0x201, 0x1FE}, // 0
    {0x204, 0x202, 0x3FF, 0x200}, // 1
    {0x3C2, 0x221, 0x211, 0x20E}, // 2
    {0x102, 0x211, 0x211, 0x1EE}, // 3
    {0x03F, 0x020, 0x020, 0x3FF}, // 4
    {0x11F, 0x211, 0x211, 0x1E1}, // 5
    {0x1FE, 0x211, 0x211, 0x1E2}, // 6
    {0x001, 0x3C1, 0x031, 0x00F}, // 7
    {0x1EE, 0x211, 0x211, 0x1EE}, // 8
    {0x11E, 0x221, 0x221, 0x1FE}, // 9
};

// Compact 3x7 numerals + sign for seconds clock & animated weather temperature readout
constexpr uint8_t kFontDigits3x7[12][3] = {
    {0x7F, 0x41, 0x7F}, // 0
    {0x42, 0x7F, 0x40}, // 1
    {0x79, 0x49, 0x4F}, // 2
    {0x49, 0x49, 0x7F}, // 3
    {0x0F, 0x08, 0x7F}, // 4
    {0x4F, 0x49, 0x79}, // 5
    {0x7F, 0x49, 0x79}, // 6
    {0x01, 0x01, 0x7F}, // 7
    {0x7F, 0x49, 0x7F}, // 8
    {0x4F, 0x49, 0x7F}, // 9
    {0x08, 0x1C, 0x08}, // + (idx 10)
    {0x08, 0x08, 0x08}, // - (idx 11)
};

// Micro 3x5 digits for ":SS" seconds line
constexpr uint8_t kFontDigits3x5[10][3] = {
    {0x1F, 0x11, 0x1F}, // 0
    {0x12, 0x1F, 0x10}, // 1
    {0x1D, 0x15, 0x17}, // 2
    {0x15, 0x15, 0x1F}, // 3
    {0x07, 0x04, 0x1F}, // 4
    {0x17, 0x15, 0x1D}, // 5
    {0x1F, 0x15, 0x1D}, // 6
    {0x01, 0x01, 0x1F}, // 7
    {0x1F, 0x15, 0x1F}, // 8
    {0x17, 0x15, 0x1F}, // 9
};

uint32_t next_rng(uint32_t& s) {
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    return s;
}

float rng_unit(uint32_t& s) {
    return static_cast<float>(next_rng(s) & 0xFFFFu) / 65535.0f;
}

} // namespace

void FramebufferHw::render_screen(double t_sec, const LedScreenConfig& cfg) {
    if (fb5_.fd < 0) return;

    auto now = std::chrono::steady_clock::now();
    double dt_sec = (last_screen_render_t_ > 0.0) ? std::clamp(t_sec - last_screen_render_t_, 0.005, 0.12) : 0.033;
    last_screen_render_t_ = t_sec;

    VoiceVisualState vstate = VoiceVisualState::Idle;
    double voice_elapsed = 0.0;
    std::array<float, 24> spec{};
    bool osd_active = false;
    int osd_pct = 0;
    bool startup_active = false;
    double startup_elapsed = 0.0;
    bool bt_active = false;
    std::string bt_state;
    double bt_elapsed = 0.0;
    bool task_active = false;
    std::string task_id;
    Rgb task_col{};
    std::string task_custom;
    double task_elapsed = 0.0;
    bool scr_override_active = false;
    std::string scr_override_txt;
    Rgb scr_override_col{};
    double scr_override_elapsed = 0.0;
    bool music_vis_active = false;
    bool weather_active = false;
    int weather_temp = 0;
    std::string weather_cond = "clear";
    double weather_elapsed = 0.0;
    {
        std::lock_guard<std::mutex> lk(mu_);
        vstate = voice_state_;
        if (voice_state_start_.time_since_epoch().count() > 0) {
            voice_elapsed = std::max(0.0, std::chrono::duration<double>(now - voice_state_start_).count());
        }
        spec = spectrum_;
        osd_active = now < osd_until_;
        osd_pct = osd_volume_pct_;
        if (now < startup_until_) {
            startup_active = true;
            startup_elapsed = std::chrono::duration<double>(now - startup_start_).count();
        }
        if (now < bt_until_) {
            bt_active = true;
            bt_state = bt_visual_state_;
            bt_elapsed = std::chrono::duration<double>(now - bt_start_).count();
        }
        if (now < task_icon_until_ && (!task_icon_id_.empty() || !task_icon_custom_.empty())) {
            task_active = true;
            task_id = task_icon_id_;
            task_col = task_icon_col_;
            task_custom = task_icon_custom_;
            task_elapsed = std::max(0.0, std::chrono::duration<double>(now - task_icon_start_).count());
        }
        music_vis_active = (music_playing_ || now < music_stop_grace_until_) &&
                           cfg.auto_music_visualizer && (cfg.mode != "off");
        if (now < weather_until_) {
            weather_active = true;
            weather_temp = weather_temp_c_;
            weather_cond = weather_cond_;
            weather_elapsed = std::chrono::duration<double>(now - weather_start_).count();
        }
        if (now < scr_text_until_ && !scr_override_text_.empty()) {
            scr_override_active = true;
            scr_override_txt = scr_override_text_;
            scr_override_col = scr_override_color_;
            scr_override_elapsed = std::chrono::duration<double>(now - scr_override_start_).count();
        }
    }

    int bri_pct = std::clamp(cfg.brightness, 0, 100);
    int max_bl = std::max(1, fb5_max_bl_);
    int bl = std::clamp((bri_pct * max_bl) / 100, 0, max_bl);
    if (cfg.auto_brightness) {
        double lux = std::max(0.0, last_lux_.load());
        double norm = std::clamp(std::log10(lux + 1.0) / 2.4, 0.05, 1.0);
        bl = std::clamp(static_cast<int>(norm * max_bl), 1, max_bl);
    }
    if ((vstate != VoiceVisualState::Idle || scr_override_active || osd_active || startup_active || bt_active || task_active || weather_active || music_vis_active) &&
        bl < std::max(1, max_bl / 3)) {
        bl = std::max(1, max_bl / 3);
    }
    if (std::abs(bl - last_fb5_bl_) >= 2) {
        write_backlight(fb5_.bl_path, bl, max_bl);
        last_fb5_bl_ = bl;
    }

    uint32_t W = fb5_.xres;
    uint32_t H = fb5_.yres;
    size_t npix = static_cast<size_t>(W) * static_cast<size_t>(H);
    if (npix == 0) return;

    if (scr_curr_frame_.size() != npix) {
        scr_curr_frame_.assign(npix, FloatRgb{});
        scr_prev_frame_.assign(npix, FloatRgb{});
        scr_trail_buf_.assign(npix, FloatRgb{});
    }

    std::string scene_key;
    if (osd_active) {
        scene_key = "osd";
    } else if (startup_active) {
        scene_key = "startup";
    } else if (bt_active) {
        scene_key = "bt:" + bt_state;
    } else if (task_active) {
        scene_key = "task:" + task_id;
    } else if (weather_active) {
        scene_key = "weather:" + weather_cond + ":" + std::to_string(weather_temp);
    } else if (scr_override_active) {
        scene_key = "banner";
    } else if (vstate != VoiceVisualState::Idle) {
        scene_key = "voice:" + std::to_string(static_cast<int>(vstate));
    } else if (cfg.mode == "off") {
        scene_key = "off";
    } else if (music_vis_active) {
        scene_key = "music:" + cfg.visualizer_style;
    } else if (cfg.mode == "clock") {
        scene_key = "clock:" + cfg.clock_style;
    } else if (cfg.mode == "spectrum") {
        scene_key = "vis:" + cfg.visualizer_style;
    } else {
        scene_key = "mode:" + cfg.mode;
    }

    if (scene_key != last_scene_key_) {
        if (!last_scene_key_.empty()) {
            scr_prev_frame_ = scr_curr_frame_;
            scene_trans_start_ = now;
            if (scene_key == "osd" || last_scene_key_ == "osd") {
                scene_trans_dur_sec_ = 0.16;
            } else if (scene_key.rfind("voice:", 0) == 0 || last_scene_key_.rfind("voice:", 0) == 0) {
                scene_trans_dur_sec_ = 0.36;
            } else if (scene_key.rfind("task:", 0) == 0 || last_scene_key_.rfind("task:", 0) == 0) {
                scene_trans_dur_sec_ = 0.42;
            } else {
                scene_trans_dur_sec_ = 0.48;
            }
        }
        last_scene_key_ = scene_key;
    }

    std::vector<FloatRgb> target(npix, FloatRgb{});

    auto pset_rgb = [&](int x, int y, float r, float g, float b) {
        if (x < 0 || y < 0 || x >= static_cast<int>(W) || y >= static_cast<int>(H)) return;
        size_t idx = static_cast<size_t>(y) * W + static_cast<size_t>(x);
        target[idx].r = std::clamp(r, 0.0f, 255.0f);
        target[idx].g = std::clamp(g, 0.0f, 255.0f);
        target[idx].b = std::clamp(b, 0.0f, 255.0f);
    };
    auto pset = [&](int x, int y, Rgb c) {
        pset_rgb(x, y, static_cast<float>(c.r), static_cast<float>(c.g), static_cast<float>(c.b));
    };
    auto padd = [&](int x, int y, float r, float g, float b) {
        if (x < 0 || y < 0 || x >= static_cast<int>(W) || y >= static_cast<int>(H)) return;
        size_t idx = static_cast<size_t>(y) * W + static_cast<size_t>(x);
        target[idx].r = std::clamp(target[idx].r + r, 0.0f, 255.0f);
        target[idx].g = std::clamp(target[idx].g + g, 0.0f, 255.0f);
        target[idx].b = std::clamp(target[idx].b + b, 0.0f, 255.0f);
    };
    auto splat_soft = [&](std::vector<FloatRgb>& buf, float fx, float fy, Rgb c, float intensity) {
        int x0 = static_cast<int>(std::floor(fx));
        int y0 = static_cast<int>(std::floor(fy));
        float tx = fx - x0;
        float ty = fy - y0;
        const float w[4] = {(1.0f - tx) * (1.0f - ty), tx * (1.0f - ty), (1.0f - tx) * ty, tx * ty};
        const int ox[4] = {0, 1, 0, 1};
        const int oy[4] = {0, 0, 1, 1};
        for (int k = 0; k < 4; ++k) {
            int px = x0 + ox[k];
            int py = y0 + oy[k];
            if (px >= 0 && px < static_cast<int>(W) && py >= 0 && py < static_cast<int>(H)) {
                size_t idx = static_cast<size_t>(py) * W + static_cast<size_t>(px);
                float a = w[k] * intensity;
                buf[idx].r = std::min(255.0f, buf[idx].r + c.r * a);
                buf[idx].g = std::min(255.0f, buf[idx].g + c.g * a);
                buf[idx].b = std::min(255.0f, buf[idx].b + c.b * a);
            }
        }
    };

    auto draw_text_col = [&](const std::string& txt, int start_x, int start_y, Rgb c) {
        auto cps = utf8_to_codepoints(txt);
        int cursor = start_x;
        for (uint32_t cp : cps) {
            const uint8_t* cols = lookup_glyph(cp);
            for (int cx = 0; cx < 5; ++cx) {
                int px = cursor + cx;
                if (px >= 0 && px < static_cast<int>(W)) {
                    uint8_t col_bits = cols[cx];
                    for (int cy = 0; cy < 7; ++cy) {
                        int py = start_y + cy;
                        if (py >= 0 && py < static_cast<int>(H) && (col_bits & (1 << cy))) {
                            pset(px, py, c);
                        }
                    }
                }
            }
            cursor += 6;
        }
        return cursor - start_x;
    };

    auto draw_digit_4x10 = [&](int digit, int dx, int dy, Rgb c) {
        if (digit < 0 || digit > 9) return;
        for (int cx = 0; cx < 4; ++cx) {
            uint16_t bits = kFontDigits4x10[digit][cx];
            for (int cy = 0; cy < 10; ++cy) {
                if (bits & (1u << cy)) pset(dx + cx, dy + cy, c);
            }
        }
    };

    auto draw_glyph_3x7 = [&](int idx, int dx, int dy, Rgb c) {
        if (idx < 0 || idx >= 12) return;
        for (int cx = 0; cx < 3; ++cx) {
            uint8_t bits = kFontDigits3x7[idx][cx];
            for (int cy = 0; cy < 7; ++cy) {
                if (bits & (1u << cy)) pset(dx + cx, dy + cy, c);
            }
        }
    };

    auto draw_digit_3x5 = [&](int digit, int dx, int dy, Rgb c) {
        if (digit < 0 || digit > 9) return;
        for (int cx = 0; cx < 3; ++cx) {
            uint8_t bits = kFontDigits3x5[digit][cx];
            for (int cy = 0; cy < 5; ++cy) {
                if (bits & (1u << cy)) pset(dx + cx, dy + cy, c);
            }
        }
    };

    auto render_visualizer = [&](const std::string& vis_style, Rgb base_col) {
        float bass = 0.0f, mid = 0.0f, high = 0.0f, overall = 0.0f;
        for (int i = 0; i < 5; ++i) bass += spec[i];
        bass /= 5.0f;
        for (int i = 5; i < 14; ++i) mid += spec[i];
        mid /= 9.0f;
        for (int i = 14; i < 24; ++i) high += spec[i];
        high /= 10.0f;
        overall = std::clamp(bass * 0.45f + mid * 0.35f + high * 0.20f, 0.0f, 1.0f);
        float bass_flux = std::max(0.0f, bass - prev_bass_energy_);
        prev_bass_energy_ = prev_bass_energy_ * 0.7f + bass * 0.3f;

        static uint32_t s_rng = 0x5A17C0DEu;

        if (vis_style == "bars") {
            float decay = std::exp(-static_cast<float>(dt_sec) * 6.5f);
            for (size_t i = 0; i < npix; ++i) {
                scr_trail_buf_[i].r *= decay;
                scr_trail_buf_[i].g *= decay;
                scr_trail_buf_[i].b *= decay;
            }
            for (uint32_t x = 0; x < W; ++x) {
                size_t bi = std::min<size_t>(23, (x * 24) / std::max<uint32_t>(1, W));
                float lvl = std::clamp(spec[bi], 0.0f, 1.0f);
                float bar_hf = lvl * static_cast<float>(H);
                uint32_t bar_h = static_cast<uint32_t>(std::lround(bar_hf));
                float hue = std::fmod(190.0f + (static_cast<float>(x) / std::max<uint32_t>(1, W)) * 135.0f + static_cast<float>(t_sec * 18.0), 360.0f);
                for (uint32_t y = 0; y < bar_h; ++y) {
                    float yk = static_cast<float>(y) / std::max<uint32_t>(1, H);
                    Rgb c = hsv_to_rgb(hue - yk * 35.0f, 0.90f, std::clamp(0.45f + lvl * 0.55f, 0.2f, 1.0f));
                    size_t idx = static_cast<size_t>(H - 1 - y) * W + x;
                    scr_trail_buf_[idx] = {static_cast<float>(c.r), static_cast<float>(c.g), static_cast<float>(c.b)};
                }
                if (bar_h > 0 && bar_h < H) {
                    size_t tip = static_cast<size_t>(H - 1 - bar_h) * W + x;
                    scr_trail_buf_[tip].r = std::max(scr_trail_buf_[tip].r, 255.0f);
                    scr_trail_buf_[tip].g = std::max(scr_trail_buf_[tip].g, 240.0f);
                    scr_trail_buf_[tip].b = std::max(scr_trail_buf_[tip].b, 255.0f);
                }
            }
            target = scr_trail_buf_;
        } else if (vis_style == "wave") {
            float decay = std::exp(-static_cast<float>(dt_sec) * 5.2f);
            for (size_t i = 0; i < npix; ++i) {
                scr_trail_buf_[i].r *= decay;
                scr_trail_buf_[i].g *= decay;
                scr_trail_buf_[i].b *= decay;
            }
            float cy = (static_cast<float>(H) - 1.0f) * 0.5f;
            float amp = (static_cast<float>(H) * 0.44f) * (0.20f + overall * 0.85f);
            for (int x = 0; x < static_cast<int>(W); ++x) {
                float u = static_cast<float>(x) / std::max(1.0f, static_cast<float>(W - 1));
                size_t bi = std::min<size_t>(23, (x * 24) / std::max<uint32_t>(1, W));
                float b_amp = 0.35f + spec[bi] * 0.95f;
                float y1 = cy + std::sin(u * 6.283f * 1.5f + static_cast<float>(t_sec * 4.2)) * amp * b_amp;
                float y2 = cy + std::cos(u * 6.283f * 2.2f - static_cast<float>(t_sec * 3.1)) * amp * (0.4f + high * 0.8f);
                Rgb c1 = hsv_to_rgb(185.0f + u * 80.0f + static_cast<float>(t_sec * 20.0), 0.9f, 1.0f);
                Rgb c2 = hsv_to_rgb(285.0f - u * 70.0f + static_cast<float>(t_sec * 25.0), 0.85f, 0.92f);
                splat_soft(scr_trail_buf_, static_cast<float>(x), y1, c1, 0.95f);
                splat_soft(scr_trail_buf_, static_cast<float>(x), y2, c2, 0.72f);
            }
            target = scr_trail_buf_;
        } else if (vis_style == "fireflies") {
            float decay = std::exp(-static_cast<float>(dt_sec) * 3.2f);
            for (size_t i = 0; i < npix; ++i) {
                scr_trail_buf_[i].r *= decay;
                scr_trail_buf_[i].g *= decay;
                scr_trail_buf_[i].b *= decay;
            }
            if (scr_particles_.size() != 16) {
                scr_particles_.resize(16);
                for (size_t i = 0; i < scr_particles_.size(); ++i) {
                    auto& p = scr_particles_[i];
                    p.x = rng_unit(s_rng) * std::max(1.0f, static_cast<float>(W - 1));
                    p.y = rng_unit(s_rng) * std::max(1.0f, static_cast<float>(H - 1));
                    p.vx = (rng_unit(s_rng) - 0.5f) * 8.0f;
                    p.vy = (rng_unit(s_rng) - 0.5f) * 6.0f;
                    p.hue = 45.0f + rng_unit(s_rng) * 165.0f;
                    p.life = rng_unit(s_rng) * 3.0f;
                }
            }
            for (size_t i = 0; i < scr_particles_.size(); ++i) {
                auto& p = scr_particles_[i];
                size_t bi = (i * 24) / scr_particles_.size();
                float e = std::clamp(spec[bi], 0.05f, 1.0f);
                p.vx += std::sin(static_cast<float>(t_sec * 1.8 + i * 1.3)) * 12.0f * static_cast<float>(dt_sec);
                p.vy += std::cos(static_cast<float>(t_sec * 1.4 + i * 2.1)) * 9.0f * static_cast<float>(dt_sec);
                float sp = 0.65f + e * 2.0f;
                p.x += p.vx * sp * static_cast<float>(dt_sec);
                p.y += p.vy * sp * static_cast<float>(dt_sec);
                p.vx *= 0.96f;
                p.vy *= 0.96f;
                if (p.x < 0.0f) { p.x = 0.0f; p.vx = std::fabs(p.vx); }
                if (p.x > W - 1.0f) { p.x = W - 1.0f; p.vx = -std::fabs(p.vx); }
                if (p.y < 0.0f) { p.y = 0.0f; p.vy = std::fabs(p.vy); }
                if (p.y > H - 1.0f) { p.y = H - 1.0f; p.vy = -std::fabs(p.vy); }
                float glow = std::clamp(0.22f + e * 1.15f + bass_flux * 1.2f, 0.15f, 1.0f);
                Rgb c = hsv_to_rgb(p.hue + static_cast<float>(t_sec * 12.0), 0.85f, 1.0f);
                splat_soft(scr_trail_buf_, p.x, p.y, c, glow);
            }
            target = scr_trail_buf_;
        } else if (vis_style == "aurora") {
            float decay = std::exp(-static_cast<float>(dt_sec) * 4.5f);
            for (size_t i = 0; i < npix; ++i) {
                scr_trail_buf_[i].r *= decay;
                scr_trail_buf_[i].g *= decay;
                scr_trail_buf_[i].b *= decay;
            }
            for (int x = 0; x < static_cast<int>(W); ++x) {
                float u = static_cast<float>(x) / std::max(1.0f, static_cast<float>(W - 1));
                size_t bi = std::min<size_t>(23, (x * 24) / std::max<uint32_t>(1, W));
                float e = std::clamp(spec[bi], 0.0f, 1.0f);
                float curtain_y = (static_cast<float>(H) * 0.52f) +
                                  std::sin(u * 4.2f + static_cast<float>(t_sec * 1.6)) * (static_cast<float>(H) * 0.25f) -
                                  e * (static_cast<float>(H) * 0.35f);
                for (int y = 0; y < static_cast<int>(H); ++y) {
                    float dy = std::fabs(static_cast<float>(y) - curtain_y);
                    float inten = std::exp(-(dy * dy) / (4.5f + bass * 8.0f)) * (0.28f + e * 0.85f);
                    float hue = 140.0f + u * 110.0f + std::sin(static_cast<float>(t_sec) + u * 3.0f) * 30.0f;
                    Rgb c = hsv_to_rgb(hue, 0.90f, inten);
                    size_t idx = static_cast<size_t>(y) * W + static_cast<size_t>(x);
                    scr_trail_buf_[idx].r = std::max(scr_trail_buf_[idx].r, static_cast<float>(c.r));
                    scr_trail_buf_[idx].g = std::max(scr_trail_buf_[idx].g, static_cast<float>(c.g));
                    scr_trail_buf_[idx].b = std::max(scr_trail_buf_[idx].b, static_cast<float>(c.b));
                }
            }
            target = scr_trail_buf_;
        } else if (vis_style == "matrix") {
            float decay = std::exp(-static_cast<float>(dt_sec) * 6.0f);
            for (size_t i = 0; i < npix; ++i) {
                scr_trail_buf_[i].r *= decay;
                scr_trail_buf_[i].g *= decay;
                scr_trail_buf_[i].b *= decay;
            }
            uint32_t cols = std::min<uint32_t>(32, W);
            for (uint32_t x = 0; x < cols; ++x) {
                size_t bi = std::min<size_t>(23, (x * 24) / std::max<uint32_t>(1, cols));
                float e = std::clamp(spec[bi], 0.0f, 1.0f);
                float spd = 5.0f + e * 22.0f + bass * 10.0f;
                matrix_drop_y_[x] += spd * static_cast<float>(dt_sec);
                if (matrix_drop_y_[x] >= static_cast<float>(H + 2)) {
                    matrix_drop_y_[x] = -rng_unit(s_rng) * 4.0f;
                }
                int hy = static_cast<int>(std::floor(matrix_drop_y_[x]));
                if (hy >= 0 && hy < static_cast<int>(H)) {
                    size_t idx = static_cast<size_t>(hy) * W + x;
                    Rgb head_c = lerp_rgb(Rgb{0, 235, 110}, Rgb{185, 255, 220}, std::clamp(e * 1.4f, 0.2f, 1.0f));
                    scr_trail_buf_[idx] = {static_cast<float>(head_c.r), static_cast<float>(head_c.g), static_cast<float>(head_c.b)};
                    if (hy - 1 >= 0) {
                        size_t idx2 = static_cast<size_t>(hy - 1) * W + x;
                        scr_trail_buf_[idx2].g = std::max(scr_trail_buf_[idx2].g, 190.0f);
                        scr_trail_buf_[idx2].b = std::max(scr_trail_buf_[idx2].b, 70.0f);
                    }
                }
            }
            target = scr_trail_buf_;
        } else {
            // Default: "particles" — luminescent audio-reactive particle cloud bursting on beats
            float decay = std::exp(-static_cast<float>(dt_sec) * 4.4f);
            for (size_t i = 0; i < npix; ++i) {
                scr_trail_buf_[i].r *= decay;
                scr_trail_buf_[i].g *= decay;
                scr_trail_buf_[i].b *= decay;
            }
            float cx = (static_cast<float>(W) - 1.0f) * 0.5f;
            float cy = (static_cast<float>(H) - 1.0f) * 0.5f;
            if (scr_particles_.size() != 26) {
                scr_particles_.resize(26);
                for (size_t i = 0; i < scr_particles_.size(); ++i) {
                    auto& p = scr_particles_[i];
                    float ang = (static_cast<float>(i) / 26.0f) * 6.28318f;
                    p.x = cx + std::cos(ang) * 2.0f;
                    p.y = cy + std::sin(ang) * 2.0f;
                    p.vx = std::cos(ang) * (4.0f + rng_unit(s_rng) * 8.0f);
                    p.vy = std::sin(ang) * (3.0f + rng_unit(s_rng) * 6.0f);
                    p.hue = 185.0f + static_cast<float>(i) * 6.5f;
                    p.max_life = 0.65f + rng_unit(s_rng) * 0.9f;
                    p.life = rng_unit(s_rng) * p.max_life;
                }
            }
            // Subtle breathing glowing nucleus in the center
            float core_r = 1.4f + bass * 2.4f;
            Rgb core_c = lerp_rgb(Rgb{0, 212, 255}, Rgb{170, 90, 255}, mid);
            for (int y = static_cast<int>(cy) - 3; y <= static_cast<int>(cy) + 3; ++y) {
                for (int x = static_cast<int>(cx) - 3; x <= static_cast<int>(cx) + 3; ++x) {
                    if (x < 0 || y < 0 || x >= static_cast<int>(W) || y >= static_cast<int>(H)) continue;
                    float dx = x - cx, dy = y - cy;
                    float d2 = dx * dx + dy * dy;
                    float g = std::exp(-d2 / (core_r * core_r)) * (0.28f + overall * 0.72f);
                    size_t idx = static_cast<size_t>(y) * W + static_cast<size_t>(x);
                    scr_trail_buf_[idx].r = std::max(scr_trail_buf_[idx].r, core_c.r * g);
                    scr_trail_buf_[idx].g = std::max(scr_trail_buf_[idx].g, core_c.g * g);
                    scr_trail_buf_[idx].b = std::max(scr_trail_buf_[idx].b, core_c.b * g);
                }
            }
            for (size_t i = 0; i < scr_particles_.size(); ++i) {
                auto& p = scr_particles_[i];
                size_t bi = (i * 24) / scr_particles_.size();
                float e = std::clamp(spec[bi], 0.04f, 1.0f);
                p.life -= static_cast<float>(dt_sec) * (0.65f + overall * 0.9f);
                if (p.life <= 0.0f) {
                    float ang = rng_unit(s_rng) * 6.28318f;
                    float spd = 5.5f + bass * 16.0f + e * 10.0f + bass_flux * 20.0f;
                    p.x = cx + std::cos(ang) * (0.5f + rng_unit(s_rng) * 1.8f);
                    p.y = cy + std::sin(ang) * (0.4f + rng_unit(s_rng) * 1.4f);
                    p.vx = std::cos(ang) * spd;
                    p.vy = std::sin(ang) * (spd * 0.75f);
                    p.hue = 180.0f + rng_unit(s_rng) * 130.0f + static_cast<float>(t_sec * 16.0);
                    p.max_life = 0.55f + rng_unit(s_rng) * 0.85f;
                    p.life = p.max_life;
                } else {
                    float swirl = 1.6f + high * 3.0f;
                    float dx = p.x - cx, dy = p.y - cy;
                    p.vx += (-dy * swirl * 0.5f) * static_cast<float>(dt_sec);
                    p.vy += (dx * swirl * 0.5f) * static_cast<float>(dt_sec);
                    float boost = 1.0f + bass * 1.15f + bass_flux * 2.2f;
                    p.x += p.vx * boost * static_cast<float>(dt_sec);
                    p.y += p.vy * boost * static_cast<float>(dt_sec);
                    p.vx *= 0.97f;
                    p.vy *= 0.97f;
                    if (p.x < 0.0f) { p.x = 0.0f; p.vx = std::fabs(p.vx) * 0.7f; }
                    if (p.x > W - 1.0f) { p.x = W - 1.0f; p.vx = -std::fabs(p.vx) * 0.7f; }
                    if (p.y < 0.0f) { p.y = 0.0f; p.vy = std::fabs(p.vy) * 0.7f; }
                    if (p.y > H - 1.0f) { p.y = H - 1.0f; p.vy = -std::fabs(p.vy) * 0.7f; }
                }
                float fade = std::clamp(p.life / std::max(0.1f, p.max_life), 0.0f, 1.0f);
                float inten = fade * (0.42f + e * 0.75f + overall * 0.35f);
                Rgb c = hsv_to_rgb(p.hue, 0.92f - e * 0.2f, 1.0f);
                splat_soft(scr_trail_buf_, p.x, p.y, c, inten);
            }
            target = scr_trail_buf_;
        }
        (void)base_col;
    };

    auto render_weather_scene = [&]() {
        // Left animated weather icon (0..8 x 0..15) + Right crisp numeric temperature (10..24)
        Rgb temp_col = (weather_temp <= 0) ? Rgb{100, 215, 255}
                     : (weather_temp <= 14 ? Rgb{85, 240, 225}
                     : (weather_temp <= 24 ? Rgb{245, 250, 255}
                     : (weather_temp <= 30 ? Rgb{255, 200, 75} : Rgb{255, 95, 70})));

        auto draw_cloud = [&](int ox, int oy, Rgb cc) {
            const uint8_t rows[4][7] = {
                {0, 0, 1, 1, 0, 0, 0},
                {0, 1, 1, 1, 1, 1, 0},
                {1, 1, 1, 1, 1, 1, 1},
                {0, 1, 1, 1, 1, 1, 0},
            };
            for (int r = 0; r < 4; ++r) {
                for (int c = 0; c < 7; ++c) {
                    if (rows[r][c]) pset(ox + c, oy + r, cc);
                }
            }
        };
        auto draw_sun = [&](int cx, int cy, bool small_sun) {
            float pulse = 0.85f + 0.15f * std::sin(static_cast<float>(weather_elapsed * 3.0));
            Rgb core = scale_rgb(Rgb{255, 210, 45}, pulse);
            Rgb ray = scale_rgb(Rgb{255, 145, 15}, 0.55f + 0.35f * std::sin(static_cast<float>(weather_elapsed * 4.2)));
            int rad = small_sun ? 1 : 2;
            for (int dy = -rad; dy <= rad; ++dy) {
                for (int dx = -rad; dx <= rad; ++dx) {
                    if (dx * dx + dy * dy <= rad * rad + 1) pset(cx + dx, cy + dy, core);
                }
            }
            if (!small_sun) {
                int rot = static_cast<int>(weather_elapsed * 4.0) % 2;
                if (rot == 0) {
                    pset(cx, cy - 4, ray); pset(cx, cy + 4, ray);
                    pset(cx - 4, cy, ray); pset(cx + 4, cy, ray);
                } else {
                    pset(cx - 3, cy - 3, ray); pset(cx + 3, cy - 3, ray);
                    pset(cx - 3, cy + 3, ray); pset(cx + 3, cy + 3, ray);
                }
            }
        };

        if (weather_cond == "clear") {
            draw_sun(4, 7, false);
        } else if (weather_cond == "partly_cloudy") {
            draw_sun(3, 5, true);
            int shift = static_cast<int>(std::lround(std::sin(weather_elapsed * 1.4)));
            draw_cloud(1 + std::max(0, shift), 7, Rgb{205, 225, 255});
        } else if (weather_cond == "cloudy") {
            int shift = static_cast<int>(std::lround(std::sin(weather_elapsed * 1.2)));
            draw_cloud(1 + shift, 4, Rgb{120, 155, 200});
            draw_cloud(1 - shift, 7, Rgb{185, 210, 240});
        } else if (weather_cond == "rain") {
            draw_cloud(1, 2, Rgb{100, 145, 215});
            for (int d = 0; d < 4; ++d) {
                float fy = std::fmod(static_cast<float>(weather_elapsed * 8.5 + d * 2.7), 9.0f);
                int rx = 1 + ((d * 2) % 7);
                int ry = 7 + static_cast<int>(fy);
                if (ry < static_cast<int>(H)) pset(rx, ry, Rgb{0, 215, 255});
                if (ry + 1 < static_cast<int>(H)) pset(rx, ry + 1, Rgb{40, 130, 230});
            }
        } else if (weather_cond == "snow") {
            draw_cloud(1, 2, Rgb{155, 195, 240});
            for (int d = 0; d < 5; ++d) {
                float fy = std::fmod(static_cast<float>(weather_elapsed * 3.8 + d * 1.9), 9.0f);
                int rx = 1 + ((d * 2 + static_cast<int>(std::sin(weather_elapsed * 2.0 + d) + 1.0)) % 7);
                int ry = 7 + static_cast<int>(fy);
                if (ry < static_cast<int>(H)) pset(rx, ry, Rgb{225, 248, 255});
            }
        } else if (weather_cond == "storm") {
            draw_cloud(1, 2, Rgb{85, 105, 165});
            bool flash = std::fmod(weather_elapsed, 1.9) < 0.22 ||
                         (std::fmod(weather_elapsed, 1.9) > 0.34 && std::fmod(weather_elapsed, 1.9) < 0.48);
            if (flash) {
                pset(4, 6, Rgb{255, 245, 120}); pset(3, 7, Rgb{255, 245, 120});
                pset(4, 8, Rgb{255, 235, 90}); pset(5, 8, Rgb{255, 235, 90});
                pset(4, 9, Rgb{255, 220, 60}); pset(3, 10, Rgb{255, 210, 40});
            } else {
                for (int d = 0; d < 3; ++d) {
                    int ry = 7 + static_cast<int>(std::fmod(weather_elapsed * 9.0 + d * 3.0, 8.0));
                    pset(2 + d * 2, ry, Rgb{70, 175, 255});
                }
            }
        } else { // fog
            for (int r = 0; r < 4; ++r) {
                int ry = 3 + r * 3;
                float a = 0.55f + 0.45f * std::sin(static_cast<float>(weather_elapsed * 1.6 + r * 1.1));
                Rgb fc = scale_rgb(Rgb{165, 200, 235}, a);
                int sx = (r % 2 == 0) ? 0 : 1;
                for (int x = sx; x < 8; ++x) pset(x, ry, fc);
            }
        }

        // Draw crisp signed temperature + ° on right side (x = 9..24)
        int abs_t = std::min(99, std::abs(weather_temp));
        bool has_sign = (weather_temp != 0);
        bool two_dig = (abs_t >= 10);
        int glyphs_w = (has_sign ? 4 : 0) + (two_dig ? 7 : 3) + 3; // +2px deg +1px gap
        int tx = (static_cast<int>(W) >= 24) ? (9 + std::max(0, (16 - glyphs_w) / 2))
                                             : std::max(0, static_cast<int>(W - glyphs_w) / 2);
        int ty = H > 9 ? static_cast<int>((H - 7) / 2) : 0;
        if (has_sign) {
            draw_glyph_3x7(weather_temp > 0 ? 10 : 11, tx, ty, temp_col);
            tx += 4;
        }
        if (two_dig) {
            draw_glyph_3x7(abs_t / 10, tx, ty, temp_col);
            tx += 4;
        }
        draw_glyph_3x7(abs_t % 10, tx, ty, temp_col);
        tx += 4;
        // 2x2 degree circle
        if (tx + 1 < static_cast<int>(W)) {
            pset(tx, ty, temp_col); pset(tx + 1, ty, temp_col);
            pset(tx, ty + 1, temp_col); pset(tx + 1, ty + 1, temp_col);
        }
    };

    Rgb col = parse_hex_color(cfg.color, Rgb{255, 255, 255});
    int top_y = H > 7 ? static_cast<int>((H - 7) / 2) : 0;

    if (osd_active) {
        Rgb vcol = (osd_pct == 0)
                       ? Rgb{255, 48, 64}
                       : (osd_pct <= 65 ? Rgb{0, 232, 255}
                                        : (osd_pct <= 85 ? Rgb{255, 195, 25} : Rgb{255, 65, 130}));
        char vbuf[16];
        snprintf(vbuf, sizeof(vbuf), "%d%%", osd_pct);
        int txt_w = static_cast<int>(strlen(vbuf)) * 6 - 1;
        bool has_bar = (H >= 9);
        int osd_top_y = has_bar ? (static_cast<int>(H) - 1 > 7 ? (static_cast<int>(H) - 1 - 7) / 2 : 0) : top_y;

        if (txt_w + 7 <= static_cast<int>(W)) {
            int total_w = 5 + 2 + txt_w;
            int sx = std::max(0, (static_cast<int>(W) - total_w) / 2);
            const uint8_t spk[5] = {
                0x1C, 0x3E, 0x7F, 0x00,
                static_cast<uint8_t>(osd_pct > 60 ? 0x5D : (osd_pct > 0 ? 0x1C : 0x00))
            };
            for (int cx = 0; cx < 5; ++cx) {
                int px = sx + cx;
                if (px >= 0 && px < static_cast<int>(W)) {
                    for (int cy = 0; cy < 7; ++cy) {
                        int py = osd_top_y + cy;
                        if (py >= 0 && py < static_cast<int>(H) && (spk[cx] & (1 << cy))) {
                            pset(px, py, vcol);
                        }
                    }
                }
            }
            draw_text_col(vbuf, sx + 7, osd_top_y, vcol);
        } else if (txt_w <= static_cast<int>(W)) {
            draw_text_col(vbuf, std::max(0, (static_cast<int>(W) - txt_w) / 2), osd_top_y, vcol);
        } else {
            uint32_t fill_w = static_cast<uint32_t>((osd_pct * static_cast<int>(W) + 50) / 100);
            for (uint32_t x = 0; x < W; ++x) {
                Rgb bc = (x < fill_w) ? vcol : Rgb{20, 24, 34};
                for (uint32_t y = 0; y < H; ++y) pset(static_cast<int>(x), static_cast<int>(y), bc);
            }
        }
        if (has_bar) {
            uint32_t bar_y = H - 1;
            uint32_t fill_w = static_cast<uint32_t>((osd_pct * static_cast<int>(W) + 50) / 100);
            for (uint32_t x = 0; x < W; ++x) {
                Rgb bc = (x < fill_w) ? vcol : Rgb{24, 28, 38};
                pset(static_cast<int>(x), static_cast<int>(bar_y), bc);
            }
        }
    } else if (startup_active) {
        double cx = static_cast<double>(W - 1) * 0.5;
        double cy = static_cast<double>(H - 1) * 0.5;
        if (startup_elapsed < 1.7) {
            double rad = startup_elapsed * (std::max(12.0, static_cast<double>(W) * 0.85));
            for (uint32_t y = 0; y < H; ++y) {
                for (uint32_t x = 0; x < W; ++x) {
                    double dx = (static_cast<double>(x) - cx) * 0.55;
                    double dy = (static_cast<double>(y) - cy);
                    double dist = std::sqrt(dx * dx + dy * dy);
                    double ring = std::exp(-std::pow(dist - rad, 2.0) * 0.45);
                    double core = std::exp(-dist * dist * 0.35) * std::max(0.0, 1.0 - startup_elapsed * 0.55);
                    float r = static_cast<float>(ring * 120.0 + core * 230.0);
                    float g = static_cast<float>(ring * 220.0 + core * 245.0);
                    float b = static_cast<float>(ring * 255.0 + core * 255.0);
                    pset_rgb(static_cast<int>(x), static_cast<int>(y), r, g, b);
                }
            }
        } else {
            double p = startup_elapsed - 1.7;
            std::string banner = "OPENSTATION MAX";
            auto cps = utf8_to_codepoints(banner);
            int txt_w = static_cast<int>(cps.size()) * 6 - 1;
            int span = txt_w + static_cast<int>(W);
            int px = static_cast<int>(p * 26.0) % std::max(1, span);
            Rgb col{
                static_cast<uint8_t>(90 + 130 * (0.5 + 0.5 * std::sin(p * 3.1))),
                static_cast<uint8_t>(215 + 35 * (0.5 + 0.5 * std::cos(p * 2.4))),
                255
            };
            draw_text_col(banner, static_cast<int>(W) - px, top_y, col);
            if (H >= 8) {
                float fill = std::clamp(static_cast<float>(startup_elapsed / 5.2), 0.0f, 1.0f);
                int fw = static_cast<int>(std::lround(fill * static_cast<float>(W)));
                for (int x = 0; x < static_cast<int>(W); ++x) {
                    if (x < fw) {
                        float k = static_cast<float>(x) / std::max(1, static_cast<int>(W - 1));
                        pset_rgb(x, static_cast<int>(H - 1), k * 168.0f, 212.0f - k * 120.0f, 255.0f);
                    }
                }
            }
        }
    } else if (bt_active) {
        static const uint8_t bt_glyph[7] = {
            0b00100,
            0b00110,
            0b10101,
            0b01110,
            0b10101,
            0b00110,
            0b00100
        };
        bool is_off = (bt_state == "off" || bt_state == "disconnected");
        bool is_disc = (bt_state == "discoverable");
        Rgb icon_col = is_off ? Rgb{255, 85, 85}
                              : (is_disc ? Rgb{0, 225, 255} : Rgb{40, 255, 195});
        int iy0 = std::max(0, (static_cast<int>(H) - 7) / 2);
        int ix0 = (W >= 22) ? 1 : std::max(0, (static_cast<int>(W) - 5) / 2);
        float icon_pulse = is_disc ? (0.55f + 0.45f * std::sin(static_cast<float>(bt_elapsed * 6.5))) : 1.0f;
        for (int row = 0; row < 7 && (iy0 + row) < static_cast<int>(H); ++row) {
            for (int col = 0; col < 5; ++col) {
                if ((bt_glyph[row] >> (4 - col)) & 1) {
                    pset(ix0 + col, iy0 + row, scale_rgb(icon_col, icon_pulse));
                }
            }
        }
        if (W >= 22) {
            std::string label = is_off ? "OFF" : (is_disc ? "PAIR" : "BT ON");
            if (is_disc) {
                for (int wave = 0; wave < 3; ++wave) {
                    double phase = std::fmod(bt_elapsed * 1.8 - wave * 0.28, 1.0);
                    if (phase < 0.0) phase += 1.0;
                    int wx = 7 + static_cast<int>(phase * 4.5);
                    if (wx < 12) {
                        float inten = static_cast<float>(1.0 - phase) * 0.9f;
                        for (int dy = -2; dy <= 2; ++dy) {
                            int py = iy0 + 3 + dy;
                            int shift = (std::abs(dy) >= 2) ? -1 : 0;
                            if (py >= 0 && py < static_cast<int>(H) && (wx + shift) >= 7) {
                                pset_rgb(wx + shift, py, 0.0f, 220.0f * inten, 255.0f * inten);
                            }
                        }
                    }
                }
                draw_text_col("BT", std::max(12, static_cast<int>(W) - 12), top_y, Rgb{185, 240, 255});
            } else {
                int lx = 8;
                draw_text_col(label, lx, top_y, is_off ? Rgb{255, 130, 130} : Rgb{160, 255, 220});
            }
        }
    } else if (task_active) {
        double te = task_elapsed;
        float pop = std::clamp(static_cast<float>(te / 0.28), 0.0f, 1.0f);
        float breathe = 0.88f + 0.12f * std::sin(static_cast<float>(te * 4.2));
        auto glow_px = [&](int x, int y, Rgb c, float k) {
            float gain = pop * k;
            padd(x, y, c.r * gain, c.g * gain, c.b * gain);
        };
        if (task_id == "custom" && !task_custom.empty()) {
            std::vector<std::string> rows;
            std::string cur;
            for (char ch : task_custom) {
                if (ch == '\n' || ch == '|' || ch == ';') {
                    rows.push_back(cur);
                    cur.clear();
                } else if (ch != '\r') {
                    cur.push_back(ch);
                }
            }
            if (!cur.empty()) rows.push_back(cur);
            if (rows.size() == 1 && rows[0].size() >= W * 2) {
                std::string flat = rows[0];
                rows.clear();
                for (uint32_t r = 0; r < H && r * W < flat.size(); ++r) {
                    rows.push_back(flat.substr(r * W, W));
                }
            }
            for (uint32_t y = 0; y < H && y < rows.size(); ++y) {
                for (uint32_t x = 0; x < W && x < rows[y].size(); ++x) {
                    char c = rows[y][x];
                    if (c == '.' || c == '0' || c == ' ') continue;
                    Rgb px = task_col;
                    if (c == 'r' || c == 'R') px = Rgb{255, 38, 56};
                    else if (c == 'g' || c == 'G') px = Rgb{30, 235, 130};
                    else if (c == 'b' || c == 'B') px = Rgb{40, 145, 255};
                    else if (c == 'c' || c == 'C') px = Rgb{0, 228, 255};
                    else if (c == 'y' || c == 'Y') px = Rgb{255, 200, 60};
                    else if (c == 'm' || c == 'M') px = Rgb{210, 75, 255};
                    else if (c == 'w' || c == 'W') px = Rgb{255, 255, 255};
                    pset(static_cast<int>(x), static_cast<int>(y), scale_rgb(px, pop));
                }
            }
        } else if (task_id == "youtube") {
            // Rounded YouTube red plate (x=3..21, y=2..13) + razor-sharp white Play triangle
            Rgb yt_red{255, 18, 30};
            Rgb yt_rim{255, 70, 82};
            Rgb yt_white{255, 255, 255};
            for (int y = 2; y <= 13; ++y) {
                for (int x = 3; x <= 21; ++x) {
                    bool corner = ((x <= 4 || x >= 20) && (y == 2 || y == 13)) ||
                                  ((x == 3 || x == 21) && (y == 3 || y == 12));
                    if (corner) continue;
                    bool edge = (y == 2 || y == 13 || x == 3 || x == 21);
                    float sweep = 0.85f + 0.15f * std::sin(static_cast<float>(te * 5.0 - x * 0.25));
                    pset(x, y, scale_rgb(edge ? yt_rim : yt_red, pop * sweep));
                }
            }
            // Soft outer glow around play plate
            for (int x = 5; x <= 19; ++x) {
                glow_px(x, 1, yt_red, 0.22f);
                glow_px(x, 14, yt_red, 0.22f);
            }
            // Crisp Play triangle centered in (x=10..15, y=4..11)
            for (int row = 0; row < 7; ++row) {
                int y = 4 + row;
                int dist_c = std::abs(row - 3);
                int max_dx = (3 - dist_c) * 2;
                for (int dx = 0; dx <= max_dx && (10 + dx) <= 15; ++dx) {
                    pset(10 + dx, y, scale_rgb(yt_white, std::min(1.0f, pop * 1.1f)));
                }
            }
        } else if (task_id == "music" || task_id == "yandex_music") {
            // Musical eighth-note + live miniature spectrum bars
            Rgb nc = lerp_rgb(Rgb{0, 224, 255}, Rgb{255, 200, 68}, 0.5f + 0.5f * std::sin(static_cast<float>(te * 3.5)));
            int bob = static_cast<int>(std::lround(std::sin(te * 4.5) * 0.8));
            const uint8_t note_mask[10][8] = {
                {0,0,0,1,1,1,1,1},
                {0,0,0,1,1,1,1,1},
                {0,0,0,1,0,0,0,1},
                {0,0,0,1,0,0,0,1},
                {0,0,0,1,0,0,0,1},
                {0,1,1,1,0,0,0,1},
                {1,1,1,1,0,1,1,1},
                {1,1,1,1,1,1,1,1},
                {0,1,1,0,1,1,1,0},
                {0,0,0,0,0,1,0,0},
            };
            for (int r = 0; r < 10; ++r) {
                for (int c = 0; c < 8; ++c) {
                    if (note_mask[r][c]) pset(3 + c, 3 + r + bob, scale_rgb(nc, pop));
                }
            }
            for (int b = 0; b < 5; ++b) {
                int bx = 14 + b * 2;
                float amp = 0.35f + 0.65f * (0.5f + 0.5f * std::sin(static_cast<float>(te * 8.5 + b * 1.3)));
                int bh = std::max(2, static_cast<int>(std::lround(amp * 11.0f)));
                Rgb bc = lerp_rgb(Rgb{0, 230, 210}, Rgb{232, 198, 134}, b / 4.0f);
                for (int y = 0; y < bh; ++y) {
                    pset(bx, static_cast<int>(H) - 2 - y, scale_rgb(bc, pop));
                }
            }
        } else if (task_id == "telegram") {
            Rgb sky{38, 175, 255};
            for (int y = 1; y < 15; ++y) {
                for (int x = 5; x < 20; ++x) {
                    float dx = (x - 12.0f);
                    float dy = (y - 7.5f);
                    if (dx * dx + dy * dy <= 49.0f) {
                        pset(x, y, scale_rgb(sky, 0.32f * pop));
                    }
                }
            }
            // Crisp tilted paper airplane silhouette
            const char* plane[9] = {
                "         ...",
                "       ..XX.",
                "     ..XXXX.",
                "   ..XX.XXX.",
                " ..XX..XXXX.",
                "   ...XXXXX.",
                "     ..XXXX.",
                "     .X.XXX.",
                "     .   XX."
            };
            for (int r = 0; r < 9; ++r) {
                for (int c = 0; plane[r][c] != '\0'; ++c) {
                    if (plane[r][c] == 'X') pset(6 + c, 4 + r, Rgb{255, 255, 255});
                    else if (plane[r][c] == '.') pset(6 + c, 4 + r, scale_rgb(sky, pop * 0.9f));
                }
            }
        } else if (task_id == "browser" || task_id == "search") {
            float cx = 12.0f, cy = 7.5f;
            float r_outer = 6.1f;
            for (int y = 1; y < 15; ++y) {
                for (int x = 5; x < 20; ++x) {
                    float dx = x - cx, dy = y - cy;
                    float dist = std::sqrt(dx * dx + dy * dy);
                    float rim = std::exp(-std::pow(dist - r_outer, 2.0f) * 1.4f);
                    float mer = std::exp(-std::pow(dx - 2.6f * std::sin(static_cast<float>(te * 2.2)), 2.0f) * 1.2f) * (dist <= r_outer ? 0.85f : 0.0f);
                    float eq = std::exp(-dy * dy * 1.1f) * (dist <= r_outer ? 0.7f : 0.0f);
                    float k = std::clamp((rim + mer * 0.7f + eq * 0.65f) * pop, 0.0f, 1.0f);
                    if (k > 0.08f) {
                        Rgb c = lerp_rgb(Rgb{0, 185, 255}, Rgb{200, 248, 255}, rim);
                        pset(x, y, scale_rgb(c, k));
                    }
                }
            }
        } else if (task_id == "timer") {
            Rgb gold{255, 196, 56};
            // Hourglass outline x=8..16, y=2..13
            for (int x = 8; x <= 16; ++x) {
                pset(x, 2, scale_rgb(gold, pop));
                pset(x, 13, scale_rgb(gold, pop));
            }
            for (int r = 1; r <= 5; ++r) {
                int lx = 8 + r - 1;
                int rx = 16 - (r - 1);
                if (lx > rx) std::swap(lx, rx);
                pset(lx, 2 + r, gold); pset(rx, 2 + r, gold);
                pset(lx, 13 - r, gold); pset(rx, 13 - r, gold);
            }
            int drop_y = 6 + (static_cast<int>(te * 7.0) % 6);
            pset(12, drop_y, Rgb{255, 245, 190});
            pset(11, 12, Rgb{255, 215, 95}); pset(12, 12, Rgb{255, 215, 95}); pset(13, 12, Rgb{255, 215, 95});
        } else if (task_id == "eq") {
            for (int b = 0; b < 8; ++b) {
                int bx = 2 + b * 3;
                float phase = static_cast<float>(te * 6.5 + b * 0.85);
                int bh = 3 + static_cast<int>(std::lround((0.5f + 0.5f * std::sin(phase)) * 10.0f));
                Rgb bc = hsv_to_rgb(180.0f + b * 18.0f, 0.9f, 1.0f);
                for (int y = 0; y < bh; ++y) {
                    pset(bx, 13 - y, scale_rgb(bc, pop));
                    pset(bx + 1, 13 - y, scale_rgb(bc, pop * 0.8f));
                }
            }
        } else if (task_id == "tv") {
            Rgb rim{90, 210, 255};
            for (int x = 3; x <= 21; ++x) {
                pset(x, 2, scale_rgb(rim, pop));
                pset(x, 11, scale_rgb(rim, pop));
            }
            for (int y = 2; y <= 11; ++y) {
                pset(3, y, scale_rgb(rim, pop));
                pset(21, y, scale_rgb(rim, pop));
            }
            for (int y = 3; y <= 10; ++y) {
                for (int x = 4; x <= 20; ++x) {
                    float wave = 0.5f + 0.5f * std::sin(static_cast<float>(te * 4.0 + x * 0.35 + y * 0.4));
                    Rgb sc = lerp_rgb(Rgb{18, 45, 95}, Rgb{120, 65, 220}, wave);
                    pset(x, y, scale_rgb(sc, pop));
                }
            }
            pset(12, 12, Rgb{200, 220, 255});
            for (int x = 8; x <= 16; ++x) pset(x, 13, Rgb{200, 220, 255});
        } else if (task_id == "terminal" || task_id == "agent" || task_id == "adb") {
            // Glowing Agent Console ">_"
            Rgb frame{0, 175, 130};
            Rgb prompt{70, 255, 195};
            for (int x = 2; x <= 22; ++x) {
                pset(x, 1, scale_rgb(frame, 0.45f * pop));
                pset(x, 14, scale_rgb(frame, 0.45f * pop));
            }
            for (int y = 2; y <= 13; ++y) {
                pset(2, y, scale_rgb(frame, 0.45f * pop));
                pset(22, y, scale_rgb(frame, 0.45f * pop));
            }
            // '>' symbol
            for (int i = 0; i < 4; ++i) {
                pset(5 + i, 4 + i, prompt);
                pset(5 + i, 11 - i, prompt);
            }
            // Blinking '_' cursor
            if (static_cast<int>(te * 3.0) % 2 == 0) {
                for (int x = 12; x <= 18; ++x) {
                    pset(x, 10, Rgb{220, 255, 240});
                    pset(x, 11, Rgb{0, 230, 255});
                }
            }
        } else if (task_id == "apps") {
            const Rgb cols[4] = {{0, 224, 255}, {232, 198, 134}, {138, 92, 246}, {16, 224, 155}};
            const int ox[4] = {6, 14, 6, 14};
            const int oy[4] = {2, 2, 9, 9};
            for (int t = 0; t < 4; ++t) {
                float reveal = std::clamp(static_cast<float>(te * 4.5 - t * 0.25), 0.0f, 1.0f);
                Rgb tc = scale_rgb(cols[t], reveal * breathe);
                for (int dy = 0; dy < 5; ++dy) {
                    for (int dx = 0; dx < 5; ++dx) {
                        if ((dx == 0 || dx == 4) && (dy == 0 || dy == 4)) continue;
                        pset(ox[t] + dx, oy[t] + dy, tc);
                    }
                }
            }
        } else if (task_id == "heart") {
            const char* hmap[10] = {
                " .XX...XX. ",
                ".XXX..XXX. ",
                "XXXXXXXXXX ",
                "XXXXXXXXXX ",
                "XXXXXXXXXX ",
                ".XXXXXXXX. ",
                "..XXXXXX.. ",
                "...XXXX... ",
                "....XX.... ",
                ".......... "
            };
            float hb = 0.72f + 0.28f * std::pow(0.5f + 0.5f * std::sin(static_cast<float>(te * 5.8)), 3.0f);
            for (int r = 0; r < 10; ++r) {
                for (int c = 0; c < 10; ++c) {
                    if (hmap[r][c] == 'X') pset(7 + c, 3 + r, scale_rgb(Rgb{255, 45, 95}, pop * hb));
                }
            }
        } else { // settings / check / star / default
            float cx = 12.0f, cy = 7.5f;
            float ring_r = 4.6f + 0.35f * std::sin(static_cast<float>(te * 4.5));
            for (int y = 1; y < 15; ++y) {
                for (int x = 4; x < 21; ++x) {
                    float dx = (x - cx) * 0.85f, dy = y - cy;
                    float dist = std::sqrt(dx * dx + dy * dy);
                    float g = std::exp(-std::pow(dist - ring_r, 2.0f) * 1.15f);
                    if (g > 0.06f) {
                        pset(x, y, scale_rgb(task_col, pop * g));
                    }
                }
            }
            if (task_id == "check") {
                Rgb okc{45, 255, 160};
                pset(9, 8, okc); pset(10, 9, okc); pset(11, 10, okc);
                pset(12, 9, okc); pset(13, 8, okc); pset(14, 7, okc); pset(15, 6, okc);
            } else {
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx)
                        pset(12 + dx, 7 + dy, Rgb{255, 240, 190});
            }
        }
    } else if (weather_active) {
        render_weather_scene();
    } else if (scr_override_active) {
        auto cps = utf8_to_codepoints(scr_override_txt);
        int txt_w = cps.empty() ? 0 : static_cast<int>(cps.size()) * 6 - 1;
        if (txt_w <= static_cast<int>(W)) {
            draw_text_col(scr_override_txt, static_cast<int>((W - txt_w) / 2), top_y, scr_override_col);
        } else {
            int span = txt_w + static_cast<int>(W);
            int px = static_cast<int>(scr_override_elapsed * 22.0) % std::max(1, span);
            draw_text_col(scr_override_txt, static_cast<int>(W) - px, top_y, scr_override_col);
        }
    } else if (vstate != VoiceVisualState::Idle) {
        // Expressive animated Alice Eyes (25x16 matrix) with natural blinks, gaze & speaking lip wave
        float wake_t = std::clamp(static_cast<float>(voice_elapsed / 0.36), 0.0f, 1.0f);
        float wake_ease = wake_t * wake_t * (3.0f - 2.0f * wake_t);

        // Periodic organic eyelid blink every 3.8s
        double blink_cycle = std::fmod(voice_elapsed + 1.1, 3.8);
        float blink_k = 1.0f;
        if (blink_cycle > 3.60) {
            float bp = static_cast<float>((blink_cycle - 3.60) / 0.20);
            blink_k = 1.0f - 0.86f * std::sin(bp * static_cast<float>(kPi));
        }

        float spk_energy = 0.0f;
        for (size_t i = 0; i < 16; ++i) spk_energy += spec[i];
        spk_energy = std::clamp(spk_energy / 6.5f, 0.0f, 1.0f);

        float gaze_x = 0.0f, gaze_y = 0.0f;
        float base_ry = 4.1f;
        Rgb rim_col{0, 212, 255};
        Rgb core_col{218, 252, 255};
        Rgb pupil_col{4, 24, 52};

        if (vstate == VoiceVisualState::Listening) {
            gaze_x = 0.65f * std::sin(static_cast<float>(t_sec * 1.35));
            gaze_y = 0.35f * std::cos(static_cast<float>(t_sec * 0.95));
            base_ry = 4.2f;
            rim_col = Rgb{0, 224, 255};
            core_col = Rgb{225, 252, 255};
        } else if (vstate == VoiceVisualState::Thinking) {
            gaze_x = 1.45f * std::sin(static_cast<float>(t_sec * 2.8));
            gaze_y = -0.85f + 0.35f * std::cos(static_cast<float>(t_sec * 2.1));
            base_ry = 3.2f;
            rim_col = Rgb{150, 95, 255};
            core_col = Rgb{195, 232, 255};
        } else { // Speaking
            gaze_x = 0.35f * std::sin(static_cast<float>(t_sec * 2.1));
            gaze_y = -0.25f;
            base_ry = 3.7f - spk_energy * 0.45f; // gentle warm happy squint when speaking loud
            rim_col = lerp_rgb(Rgb{0, 224, 195}, Rgb{232, 198, 134}, 0.25f + spk_energy * 0.45f);
            core_col = Rgb{240, 255, 248};
        }

        float eye_ry = std::max(0.55f, base_ry * wake_ease * blink_k);
        float eye_rx = 3.15f;
        float eye_cy = 5.8f;
        const float eye_cxs[2] = {6.3f, 17.7f};

        for (int e = 0; e < 2; ++e) {
            float ecx = eye_cxs[e];
            for (int y = 0; y < static_cast<int>(H) - 3; ++y) {
                for (int x = 0; x < static_cast<int>(W); ++x) {
                    float dx = (static_cast<float>(x) - ecx) / eye_rx;
                    float dy = (static_cast<float>(y) - eye_cy) / eye_ry;
                    // Cheek / smile arch slightly clips lower outer edge when Speaking
                    if (vstate == VoiceVisualState::Speaking && dy > 0.42f && std::fabs(dx) < 0.72f) {
                        dy += 0.25f * (1.0f - std::fabs(dx));
                    }
                    float d2 = dx * dx + dy * dy;
                    if (d2 <= 1.75f) {
                        float glow = std::exp(-std::max(0.0f, d2 - 0.85f) * 2.6f);
                        Rgb px_c = lerp_rgb(rim_col, core_col, std::clamp(1.1f - d2 * 0.72f, 0.0f, 1.0f));
                        // Pupil & specular highlight inside open eye
                        if (wake_ease * blink_k > 0.52f && d2 < 0.92f) {
                            float px = static_cast<float>(x) - (ecx + gaze_x);
                            float py = static_cast<float>(y) - (eye_cy + gaze_y);
                            float pd2 = px * px + py * py;
                            if (pd2 < 1.35f) {
                                // Luminous sapphire-obsidian iris + crisp white catchlight at top-left
                                px_c = lerp_rgb(pupil_col, px_c, std::clamp(pd2 / 1.35f, 0.22f, 1.0f));
                            }
                            float hx = static_cast<float>(x) - (ecx + gaze_x - 0.85f);
                            float hy = static_cast<float>(y) - (eye_cy + gaze_y - 0.9f);
                            if (hx * hx + hy * hy < 0.72f) {
                                px_c = Rgb{255, 255, 255};
                            }
                        }
                        padd(x, y, px_c.r * glow, px_c.g * glow, px_c.b * glow);
                    }
                }
            }
        }

        if (vstate == VoiceVisualState::Listening) {
            // Soft listening acoustic shimmer at outer cheeks
            float mic_pulse = 0.28f + 0.42f * (0.5f + 0.5f * std::sin(static_cast<float>(t_sec * 5.2)));
            padd(1, 6, 0.0f, 210.0f * mic_pulse, 255.0f * mic_pulse);
            padd(23, 6, 0.0f, 210.0f * mic_pulse, 255.0f * mic_pulse);
        } else if (vstate == VoiceVisualState::Thinking) {
            // Orbiting stardust sparkles across brow line
            for (int k = 0; k < 4; ++k) {
                float sx = std::fmod(static_cast<float>(t_sec * 16.0) + k * 2.0f, static_cast<float>(W));
                int px = static_cast<int>(sx) % static_cast<int>(W);
                float fade = 1.0f - k * 0.24f;
                padd(px, 1, 120.0f * fade, 195.0f * fade, 255.0f * fade);
            }
        } else { // Speaking -> animated centered vocal wave bar along y = 12..15
            int bar_y_base = static_cast<int>(H) - 1;
            for (int x = 5; x <= 19; ++x) {
                float dist_mid = std::fabs(x - 12.0f) / 7.0f;
                float falloff = 1.0f - dist_mid * dist_mid;
                size_t bi = static_cast<size_t>((x - 5) * 24 / 15) % 24;
                float v = std::clamp(spec[bi] * 1.15f + 0.16f + 0.14f * std::sin(static_cast<float>(t_sec * 14.0 + x * 0.65)), 0.08f, 1.0f);
                float amp = v * falloff * 3.1f;
                int rows = std::clamp(static_cast<int>(std::lround(amp)), 1, 3);
                Rgb wc = lerp_rgb(Rgb{0, 224, 215}, Rgb{240, 210, 130}, amp / 3.1f);
                for (int r = 0; r < rows; ++r) {
                    padd(x, bar_y_base - r, wc.r * 0.9f, wc.g * 0.9f, wc.b * 0.9f);
                }
            }
        }
    } else if (cfg.mode == "off") {
        // target remains black, crossfades smoothly off
    } else if (music_vis_active) {
        render_visualizer(cfg.visualizer_style, col);
    } else if (cfg.mode == "clock") {
        tzset();
        std::time_t tt = std::time(nullptr);
        std::tm tm_now{};
        localtime_r(&tt, &tm_now);
        int h = std::clamp(tm_now.tm_hour, 0, 23);
        int m = std::clamp(tm_now.tm_min, 0, 59);
        int s = std::clamp(tm_now.tm_sec, 0, 59);
        float colon_k = 0.25f + 0.75f * (0.5f + 0.5f * std::cos(static_cast<float>(t_sec * kPi * 2.0)));
        const std::string& cs = cfg.clock_style;

        if (cs == "binary" && W >= 22 && H >= 12) {
            const int digits[6] = {h / 10, h % 10, m / 10, m % 10, s / 10, s % 10};
            const int xs[6] = {2, 5, 10, 13, 18, 21};
            const Rgb cols[6] = {
                Rgb{0, 220, 255}, Rgb{0, 220, 255},
                col, col,
                Rgb{140, 110, 255}, Rgb{140, 110, 255}
            };
            for (int c = 0; c < 6; ++c) {
                for (int bit = 0; bit < 4; ++bit) {
                    int row_y = 2 + (3 - bit) * 3;
                    bool on = (digits[c] & (1 << bit)) != 0;
                    Rgb pc = on ? cols[c] : Rgb{16, 22, 34};
                    pset(xs[c], row_y, pc);
                    pset(xs[c] + 1, row_y, pc);
                    pset(xs[c], row_y + 1, pc);
                    pset(xs[c] + 1, row_y + 1, pc);
                }
            }
            // subtle separator dots
            pset(8, 14, scale_rgb(col, colon_k));
            pset(16, 14, scale_rgb(col, colon_k));
        } else if (cs == "seconds" && W >= 21 && H >= 15) {
            // Top HH:MM in 3x7 + bottom :SS in 3x5 + progress line at H-1
            int sx = static_cast<int>((W - 19) / 2);
            draw_glyph_3x7(h / 10, sx, 1, col);
            draw_glyph_3x7(h % 10, sx + 4, 1, col);
            Rgb col_dot = scale_rgb(col, colon_k);
            pset(sx + 9, 2, col_dot);
            pset(sx + 9, 6, col_dot);
            draw_glyph_3x7(m / 10, sx + 12, 1, col);
            draw_glyph_3x7(m % 10, sx + 16, 1, col);

            Rgb sec_c = lerp_rgb(col, Rgb{0, 225, 255}, 0.55f);
            int ss_x = static_cast<int>((W - 9) / 2);
            pset(ss_x, 10, col_dot);
            pset(ss_x, 12, col_dot);
            draw_digit_3x5(s / 10, ss_x + 2, 9, sec_c);
            draw_digit_3x5(s % 10, ss_x + 6, 9, sec_c);

            int bar_w = ((s + 1) * static_cast<int>(W)) / 60;
            for (int x = 0; x < static_cast<int>(W); ++x) {
                pset(x, static_cast<int>(H - 1), x < bar_w ? Rgb{0, 215, 255} : Rgb{18, 24, 38});
            }
        } else if (cs == "minimal" && W >= 21 && H >= 12) {
            Rgb soft = scale_rgb(col, 0.85f);
            int sx = static_cast<int>((W - 19) / 2);
            int ty = static_cast<int>((H - 9) / 2);
            draw_glyph_3x7(h / 10, sx, ty, soft);
            draw_glyph_3x7(h % 10, sx + 4, ty, soft);
            Rgb dot_c = scale_rgb(soft, colon_k);
            pset(sx + 9, ty + 1, dot_c);
            pset(sx + 9, ty + 5, dot_c);
            draw_glyph_3x7(m / 10, sx + 12, ty, soft);
            draw_glyph_3x7(m % 10, sx + 16, ty, soft);
            int dot_x = (s * static_cast<int>(W - 1)) / 59;
            pset(dot_x, static_cast<int>(H - 2), Rgb{0, 210, 255});
        } else if (W >= 23 && H >= 12) {
            int sx = static_cast<int>((W - 23) / 2);
            int top_y_big = static_cast<int>((H - 10) / 2);
            bool is_neon = (cs == "neon");
            bool is_bold = (cs == "bold");
            bool is_retro = (cs == "retro");
            auto draw_styled_4x10 = [&](int digit, int dx) {
                if (digit < 0 || digit > 9) return;
                for (int cx = 0; cx < 4; ++cx) {
                    uint16_t bits = kFontDigits4x10[digit][cx];
                    for (int cy = 0; cy < 10; ++cy) {
                        if (bits & (1u << cy)) {
                            Rgb px_c = col;
                            if (is_neon) {
                                float hue = std::fmod(static_cast<float>(t_sec * 42.0) + (dx + cx) * 11.0f + cy * 4.0f, 360.0f);
                                px_c = hsv_to_rgb(hue, 0.92f, 1.0f);
                                // Subtle 1px halo around active neon pixels
                                padd(dx + cx - 1, top_y_big + cy, px_c.r * 0.16f, px_c.g * 0.16f, px_c.b * 0.16f);
                                padd(dx + cx + 1, top_y_big + cy, px_c.r * 0.16f, px_c.g * 0.16f, px_c.b * 0.16f);
                            } else if (is_bold) {
                                px_c = lerp_rgb(col, Rgb{0, 215, 255}, static_cast<float>(cy) / 9.0f);
                            } else if (is_retro) {
                                Rgb amber = Rgb{255, 168, 32};
                                px_c = (cy % 2 == 0) ? amber : scale_rgb(amber, 0.78f);
                            }
                            pset(dx + cx, top_y_big + cy, px_c);
                        }
                    }
                }
            };
            draw_styled_4x10(h / 10, sx);
            draw_styled_4x10(h % 10, sx + 5);
            Rgb col_c = is_neon
                            ? hsv_to_rgb(std::fmod(static_cast<float>(t_sec * 42.0) + 120.0f, 360.0f), 0.9f, colon_k)
                            : (is_retro ? scale_rgb(Rgb{255, 168, 32}, colon_k) : scale_rgb(col, colon_k));
            int ccol_x = sx + 11;
            pset(ccol_x, top_y_big + 2, col_c);
            pset(ccol_x, top_y_big + 3, col_c);
            pset(ccol_x, top_y_big + 6, col_c);
            pset(ccol_x, top_y_big + 7, col_c);
            draw_styled_4x10(m / 10, sx + 14);
            draw_styled_4x10(m % 10, sx + 19);

            if (is_retro && H >= 15) {
                for (int x = 1; x < static_cast<int>(W) - 1; x += 2) {
                    pset(x, 0, Rgb{95, 55, 8});
                    pset(x, static_cast<int>(H - 1), Rgb{95, 55, 8});
                }
            }
        } else {
            bool colon = (static_cast<int>(t_sec * 2.0) % 2) == 0;
            char tbuf[16];
            snprintf(tbuf, sizeof(tbuf), colon ? "%02d:%02d" : "%02d %02d", h, m);
            int txt_w = 5 * 6 - 1;
            int sx = static_cast<int>(W) > txt_w ? static_cast<int>((W - txt_w) / 2) : 0;
            draw_text_col(tbuf, sx, top_y, col);
        }
    } else if (cfg.mode == "text") {
        auto cps = utf8_to_codepoints(cfg.text);
        int txt_w = static_cast<int>(cps.size()) * 6;
        if (txt_w <= static_cast<int>(W)) {
            draw_text_col(cfg.text, static_cast<int>((W - txt_w) / 2), top_y, col);
        } else {
            double dt = t_sec - last_scroll_t_;
            double step_period = std::max(0.03, 0.20 - (cfg.scroll_speed / 100.0) * 0.16);
            if (dt >= step_period) {
                last_scroll_t_ = t_sec;
                scroll_px_ = (scroll_px_ + 1) % (txt_w + static_cast<int>(W));
            }
            draw_text_col(cfg.text, static_cast<int>(W) - scroll_px_, top_y, col);
        }
    } else if (cfg.mode == "spectrum") {
        render_visualizer(cfg.visualizer_style, col);
    } else { // snake
        uint32_t total = std::max<uint32_t>(1, W * H);
        uint32_t head = static_cast<uint32_t>(t_sec * 18.0) % total;
        for (int k = 0; k < 6; ++k) {
            uint32_t p = (head + total - k) % total;
            float fade = 1.0f - k * 0.16f;
            Rgb c = scale_rgb(col, fade);
            pset(static_cast<int>(p % W), static_cast<int>(p / W), c);
        }
    }

    // Smoothstep crossfade between previous snapshot and active target frame
    double trans_elapsed = std::chrono::duration<double>(now - scene_trans_start_).count();
    float alpha = 1.0f;
    if (scene_trans_dur_sec_ > 0.001 && trans_elapsed >= 0.0 && trans_elapsed < scene_trans_dur_sec_) {
        float u = static_cast<float>(trans_elapsed / scene_trans_dur_sec_);
        alpha = u * u * (3.0f - 2.0f * u);
    }
    float inv_alpha = 1.0f - alpha;
    std::fill(fb5_.buf.begin(), fb5_.buf.end(), 0);
    for (uint32_t y = 0; y < H; ++y) {
        for (uint32_t x = 0; x < W; ++x) {
            size_t idx = static_cast<size_t>(y) * W + x;
            FloatRgb blended{
                scr_prev_frame_[idx].r * inv_alpha + target[idx].r * alpha,
                scr_prev_frame_[idx].g * inv_alpha + target[idx].g * alpha,
                scr_prev_frame_[idx].b * inv_alpha + target[idx].b * alpha
            };
            scr_curr_frame_[idx] = blended;
            uint8_t r = static_cast<uint8_t>(std::clamp(static_cast<int>(std::lround(blended.r)), 0, 255));
            uint8_t g = static_cast<uint8_t>(std::clamp(static_cast<int>(std::lround(blended.g)), 0, 255));
            uint8_t b = static_cast<uint8_t>(std::clamp(static_cast<int>(std::lround(blended.b)), 0, 255));
            set_pixel(fb5_, x, y, r, g, b);
        }
    }

    flush_fb(fb5_);
}

void FramebufferHw::worker_loop() {
    using clock = std::chrono::steady_clock;
    auto start_tp = clock::now();
    auto last_sensor_tp = clock::now() - std::chrono::seconds(5);
    auto last_fb5_probe_tp = clock::now() - std::chrono::seconds(5);
    auto last_fb5_health_tp = clock::now();
    auto last_fb5_refresh_tp = clock::now();

    while (running_.load()) {
        auto now = clock::now();
        double t_sec = std::chrono::duration<double>(now - start_tp).count();

        if (now - last_sensor_tp >= std::chrono::milliseconds(2000)) {
            poll_sensors();
            last_sensor_tp = now;
        }

        if (force_recover_requested_.exchange(false)) {
            LOGI("Framebuffer", "Manual/watchdog screen recovery triggered");
            {
                std::lock_guard<std::mutex> lk(mu_);
                if (fb5_.fd >= 0) {
                    close_fb(fb5_);
                }
                fb5_present_.store(false);
                scr_prev_frame_.clear();
                last_scene_key_.clear();
            }
            last_fb5_probe_tp = now - std::chrono::seconds(5);
        }

        if (fb5_.fd >= 0 && now - last_fb5_health_tp >= std::chrono::milliseconds(3500)) {
            struct fb_var_screeninfo vi{};
            if (ioctl(fb5_.fd, FBIOGET_VSCREENINFO, &vi) != 0) {
                LOGW("Framebuffer", "fb5 ioctl health check failed; resetting LED screen");
                std::lock_guard<std::mutex> lk(mu_);
                close_fb(fb5_);
                fb5_present_.store(false);
                fb5_recoveries_.fetch_add(1);
                scr_prev_frame_.clear();
                last_scene_key_.clear();
            } else {
                ioctl(fb5_.fd, FBIOBLANK, 0);
                last_fb5_ok_tp_ = now;
            }
            last_fb5_health_tp = now;
        }

        if (fb5_.fd >= 0 && now - last_fb5_refresh_tp >= std::chrono::milliseconds(2000)) {
            fb5_.has_prev = false;
            last_fb5_refresh_tp = now;
        }

        if (fb5_.fd < 0 && now - last_fb5_probe_tp >= std::chrono::milliseconds(1200)) {
            FbDevice cand;
            if (open_fb_by_name("YNDX LED screen", "/dev/graphics/fb5", cand)) {
                int max_bl = 255;
                std::string max_s;
                if (read_trimmed_file(cand.bl_path + "/max_brightness", max_s)) {
                    max_bl = std::max(1, atoi(max_s.c_str()));
                }
                std::string cand_dev = cand.dev_path;
                uint32_t cand_w = cand.xres;
                uint32_t cand_h = cand.yres;
                uint32_t cand_bpp = cand.bpp;
                bool recovered = false;
                {
                    std::lock_guard<std::mutex> lk(mu_);
                    recovered = fb5_expected_;
                    fb5_ = std::move(cand);
                    fb5_expected_ = true;
                    fb5_max_bl_ = max_bl;
                    last_fb5_bl_ = -1;
                    last_fb5_ok_tp_ = now;
                    scr_prev_frame_.clear();
                    last_scene_key_.clear();
                    fb5_present_.store(true);
                    update_needs_spectrum_locked();
                }
                if (recovered) {
                    fb5_recoveries_.fetch_add(1);
                }
                LOGI("Framebuffer", "LED screen ready on %s (%ux%u %ubpp, max_bl=%d, recoveries=%d)",
                     cand_dev.c_str(), cand_w, cand_h, cand_bpp, max_bl, fb5_recoveries_.load());
            }
            last_fb5_probe_tp = now;
        }

        LedRingConfig rcfg;
        LedScreenConfig scfg;
        {
            std::lock_guard<std::mutex> lk(mu_);
            rcfg = ring_cfg_;
            scfg = screen_cfg_;
        }

        render_ring(t_sec, rcfg);
        render_screen(t_sec, scfg);

        {
            std::unique_lock<std::mutex> lk(mu_);
            bool trans_active = (std::chrono::duration<double>(now - scene_trans_start_).count() < scene_trans_dur_sec_ + 0.08);
            bool weather_active = (now < weather_until_);
            bool startup_active_lk = (now < startup_until_);
            bool bt_active_lk = (now < bt_until_);
            bool task_active_lk = (now < task_icon_until_);
            bool music_active = (music_playing_ || now < music_stop_grace_until_) && scfg.auto_music_visualizer && scfg.mode != "off";
            bool transient_active = (voice_state_ != VoiceVisualState::Idle) || mic_muted_ ||
                                    (now < osd_until_) || (now < flash_until_) ||
                                    (now < cal_until_) || (now < scr_text_until_) ||
                                    startup_active_lk || bt_active_lk || task_active_lk ||
                                    trans_active || weather_active || music_active;
            bool ring_static = (rcfg.mode == "off" || rcfg.mode == "solid");
            bool scr_has = fb5_present_.load();
            bool clock_smooth = (scfg.mode == "clock" &&
                                 (scfg.clock_style == "neon" || scfg.clock_style == "minimal" || scfg.clock_style == "seconds" || scfg.clock_style == "binary"));
            bool scr_static = (!scr_has || scfg.mode == "off" || (scfg.mode == "clock" && !clock_smooth));
            int sleep_ms = (music_active || trans_active || weather_active || startup_active_lk || bt_active_lk || task_active_lk) ? 25 : 33;
            if (!transient_active && ring_static && scr_static) {
                sleep_ms = (scr_has && scfg.mode == "clock") ? 180 : 500;
            }
            uint64_t seen_seq = wake_seq_;
            cv_.wait_for(lk, std::chrono::milliseconds(sleep_ms), [&] {
                return !running_.load() || wake_seq_ != seen_seq;
            });
        }
    }
}

json::Value FramebufferHw::get_status_json() {
    using json::Value;
    Value obj = Value::make_obj();
    obj["fb4Present"] = Value::make_bool(fb4_present_.load());
    obj["fb5Present"] = Value::make_bool(fb5_present_.load());
    obj["fb5Recoveries"] = Value::make_int(fb5_recoveries_.load());
    obj["lux"] = Value::make_num(last_lux_.load());
    obj["socTempC"] = Value::make_num(soc_temp_c_.load());
    obj["effectiveBrightness"] = Value::make_int(eff_brightness_.load());

    Value leds = Value::make_arr();
    {
        std::lock_guard<std::mutex> lk(mu_);
        obj["fb4Device"] = Value::make_str(fb4_.dev_path);
        obj["fb4Width"] = Value::make_int(fb4_.xres);
        obj["fb4Bpp"] = Value::make_int(fb4_.bpp);
        obj["fb4RgbOffsets"] = Value::make_str(
            std::to_string(fb4_.red_off) + "/" + std::to_string(fb4_.green_off) + "/" + std::to_string(fb4_.blue_off));
        obj["fb5Width"] = Value::make_int(fb5_.xres);
        obj["fb5Height"] = Value::make_int(fb5_.yres);
        obj["fb5Bpp"] = Value::make_int(fb5_.bpp);
        obj["fb5RgbOffsets"] = Value::make_str(
            std::to_string(fb5_.red_off) + "/" + std::to_string(fb5_.green_off) + "/" + std::to_string(fb5_.blue_off));
        auto now_st = std::chrono::steady_clock::now();
        obj["activeTaskIcon"] = Value::make_str(now_st < task_icon_until_ ? task_icon_id_ : "");
        const char* vs_str = "idle";
        if (voice_state_ == VoiceVisualState::Listening) vs_str = "listening";
        else if (voice_state_ == VoiceVisualState::Thinking) vs_str = "thinking";
        else if (voice_state_ == VoiceVisualState::Speaking) vs_str = "speaking";
        obj["voiceVisualState"] = Value::make_str(vs_str);
        for (const auto& p : ring_pixels_) {
            Value item = Value::make_arr();
            item.push(Value::make_int(p.r));
            item.push(Value::make_int(p.g));
            item.push(Value::make_int(p.b));
            leds.push(std::move(item));
        }
    }
    obj["ringPixels"] = std::move(leds);
    return obj;
}

} // namespace openstation
