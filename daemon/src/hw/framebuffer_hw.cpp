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
    const bool ns = (ring_cfg_.mode == "music" || ring_cfg_.mode == "voice" || v_speak ||
                     (fb5_present_.load() && (screen_cfg_.mode == "spectrum" || v_speak)));
    needs_spectrum_.store(ns, std::memory_order_relaxed);
}

void FramebufferHw::start(const LedRingConfig& ring_cfg, const LedScreenConfig& screen_cfg) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        ring_cfg_ = ring_cfg;
        screen_cfg_ = screen_cfg;
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
        fb5_present_.store(true);
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
    if (wr < 0) {
        LOGW("Framebuffer", "Write error on %s: %s", dev.dev_path.c_str(), strerror(errno));
        if (&dev == &fb5_) {
            std::lock_guard<std::mutex> lk(mu_);
            close_fb(fb5_);
            fb5_present_.store(false);
            last_fb5_bl_ = -1;
            update_needs_spectrum_locked();
        }
    } else {
        dev.prev_buf = dev.buf;
        dev.has_prev = true;
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

    if (cal_active) {
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

} // namespace

void FramebufferHw::render_screen(double t_sec, const LedScreenConfig& cfg) {
    if (fb5_.fd < 0) return;

    auto now = std::chrono::steady_clock::now();
    VoiceVisualState vstate = VoiceVisualState::Idle;
    std::array<float, 24> spec{};
    bool osd_active = false;
    int osd_pct = 0;
    bool scr_override_active = false;
    std::string scr_override_txt;
    Rgb scr_override_col{};
    double scr_override_elapsed = 0.0;
    {
        std::lock_guard<std::mutex> lk(mu_);
        vstate = voice_state_;
        spec = spectrum_;
        osd_active = now < osd_until_;
        osd_pct = osd_volume_pct_;
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
    if ((vstate != VoiceVisualState::Idle || scr_override_active || osd_active) && bl < std::max(1, max_bl / 3)) {
        bl = std::max(1, max_bl / 3);
    }
    if (std::abs(bl - last_fb5_bl_) >= 2) {
        write_backlight(fb5_.bl_path, bl, max_bl);
        last_fb5_bl_ = bl;
    }

    std::fill(fb5_.buf.begin(), fb5_.buf.end(), 0);
    if (cfg.mode == "off" && vstate == VoiceVisualState::Idle && !scr_override_active && !osd_active) {
        flush_fb(fb5_);
        return;
    }

    Rgb col = parse_hex_color(cfg.color, Rgb{255, 255, 255});
    uint32_t W = fb5_.xres;
    uint32_t H = fb5_.yres;

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
                            set_pixel(fb5_, static_cast<uint32_t>(px), static_cast<uint32_t>(py), c.r, c.g, c.b);
                        }
                    }
                }
            }
            cursor += 6;
        }
        return cursor - start_x;
    };

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
                            set_pixel(fb5_, static_cast<uint32_t>(px), static_cast<uint32_t>(py), vcol.r, vcol.g, vcol.b);
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
                for (uint32_t y = 0; y < H; ++y) {
                    set_pixel(fb5_, x, y, bc.r, bc.g, bc.b);
                }
            }
        }

        if (has_bar) {
            uint32_t bar_y = H - 1;
            uint32_t fill_w = static_cast<uint32_t>((osd_pct * static_cast<int>(W) + 50) / 100);
            for (uint32_t x = 0; x < W; ++x) {
                Rgb bc = (x < fill_w) ? vcol : Rgb{24, 28, 38};
                set_pixel(fb5_, x, bar_y, bc.r, bc.g, bc.b);
            }
        }
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
        if (vstate == VoiceVisualState::Listening) {
            // Alternating cyan wave & crisp label if panel >= 29 px wide
            float pulse = 0.65f + 0.35f * std::sin(static_cast<float>(t_sec * 5.0));
            Rgb cyan = scale_rgb(Rgb{0, 235, 255}, pulse);
            if (W >= 29 && H >= 7) {
                draw_text_col("АЛИСА", static_cast<int>((W - 29) / 2), top_y, cyan);
            } else {
                for (uint32_t x = 0; x < W; ++x) {
                    float s = 0.5f + 0.5f * std::sin(static_cast<float>(t_sec * 6.5 + x * 0.45));
                    uint32_t bh = std::max<uint32_t>(1, static_cast<uint32_t>(std::lround(s * H)));
                    for (uint32_t y = 0; y < bh; ++y) {
                        set_pixel(fb5_, x, H - 1 - y, cyan.r, cyan.g, cyan.b);
                    }
                }
            }
        } else if (vstate == VoiceVisualState::Thinking) {
            // Flowing 3-dot comet across width
            int mid_y = static_cast<int>(H / 2);
            double head = std::fmod(t_sec * 24.0, static_cast<double>(std::max<uint32_t>(1, W)));
            for (int k = 0; k < 4; ++k) {
                int px = (static_cast<int>(head) - k + static_cast<int>(W)) % static_cast<int>(W);
                float fade = 1.0f - k * 0.22f;
                Rgb c = scale_rgb(lerp_rgb(Rgb{124, 58, 237}, Rgb{0, 212, 255}, 1.0f - k * 0.25f), fade);
                for (int dy = -1; dy <= 1; ++dy) {
                    int py = mid_y + dy;
                    if (py >= 0 && py < static_cast<int>(H)) {
                        set_pixel(fb5_, static_cast<uint32_t>(px), static_cast<uint32_t>(py), c.r, c.g, c.b);
                    }
                }
            }
        } else { // Speaking: emerald-cyan spectrum bars
            for (uint32_t x = 0; x < W; ++x) {
                size_t bi = std::min<size_t>(23, (x * 24) / std::max<uint32_t>(1, W));
                float lvl = std::clamp(spec[bi], 0.08f, 1.0f);
                uint32_t bar_h = std::max<uint32_t>(1, static_cast<uint32_t>(std::lround(lvl * H)));
                Rgb c = lerp_rgb(Rgb{0, 210, 170}, Rgb{150, 255, 240}, lvl);
                for (uint32_t y = 0; y < bar_h; ++y) {
                    set_pixel(fb5_, x, H - 1 - y, c.r, c.g, c.b);
                }
            }
        }
    } else if (cfg.mode == "clock") {
        tzset();
        std::time_t tt = std::time(nullptr);
        std::tm tm_now{};
        localtime_r(&tt, &tm_now);
        bool colon = (static_cast<int>(t_sec * 2.0) % 2) == 0;
        if (W >= 23 && H >= 12) {
            int sx = static_cast<int>((W - 23) / 2);
            int top_y_big = static_cast<int>((H - 10) / 2);
            auto draw_digit_4x10 = [&](int digit, int dx) {
                if (digit < 0 || digit > 9) return;
                for (int cx = 0; cx < 4; ++cx) {
                    uint16_t bits = kFontDigits4x10[digit][cx];
                    for (int cy = 0; cy < 10; ++cy) {
                        if (bits & (1u << cy)) {
                            set_pixel(fb5_,
                                      static_cast<uint32_t>(dx + cx),
                                      static_cast<uint32_t>(top_y_big + cy),
                                      col.r, col.g, col.b);
                        }
                    }
                }
            };
            int h = std::clamp(tm_now.tm_hour, 0, 23);
            int m = std::clamp(tm_now.tm_min, 0, 59);
            draw_digit_4x10(h / 10, sx);
            draw_digit_4x10(h % 10, sx + 5);
            if (colon) {
                uint32_t cx = static_cast<uint32_t>(sx + 11);
                set_pixel(fb5_, cx, static_cast<uint32_t>(top_y_big + 2), col.r, col.g, col.b);
                set_pixel(fb5_, cx, static_cast<uint32_t>(top_y_big + 3), col.r, col.g, col.b);
                set_pixel(fb5_, cx, static_cast<uint32_t>(top_y_big + 6), col.r, col.g, col.b);
                set_pixel(fb5_, cx, static_cast<uint32_t>(top_y_big + 7), col.r, col.g, col.b);
            }
            draw_digit_4x10(m / 10, sx + 14);
            draw_digit_4x10(m % 10, sx + 19);
        } else {
            char tbuf[16];
            snprintf(tbuf, sizeof(tbuf), colon ? "%02d:%02d" : "%02d %02d", tm_now.tm_hour, tm_now.tm_min);
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
        for (uint32_t x = 0; x < W; ++x) {
            size_t bi = std::min<size_t>(23, (x * 24) / std::max<uint32_t>(1, W));
            uint32_t bar_h = static_cast<uint32_t>(std::lround(std::clamp(spec[bi], 0.0f, 1.0f) * H));
            for (uint32_t y = 0; y < bar_h; ++y) {
                set_pixel(fb5_, x, H - 1 - y, col.r, col.g, col.b);
            }
        }
    } else { // snake
        uint32_t total = std::max<uint32_t>(1, W * H);
        uint32_t head = static_cast<uint32_t>(t_sec * 18.0) % total;
        for (int k = 0; k < 6; ++k) {
            uint32_t p = (head + total - k) % total;
            float fade = 1.0f - k * 0.16f;
            Rgb c = scale_rgb(col, fade);
            set_pixel(fb5_, p % W, p / W, c.r, c.g, c.b);
        }
    }

    flush_fb(fb5_);
}

void FramebufferHw::worker_loop() {
    using clock = std::chrono::steady_clock;
    auto start_tp = clock::now();
    auto last_sensor_tp = clock::now() - std::chrono::seconds(5);
    auto last_fb5_probe_tp = clock::now() - std::chrono::seconds(5);

    while (running_.load()) {
        auto now = clock::now();
        double t_sec = std::chrono::duration<double>(now - start_tp).count();

        if (now - last_sensor_tp >= std::chrono::milliseconds(2000)) {
            poll_sensors();
            last_sensor_tp = now;
        }
        if (fb5_.fd < 0 && now - last_fb5_probe_tp >= std::chrono::seconds(5)) {
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
                {
                    std::lock_guard<std::mutex> lk(mu_);
                    fb5_ = std::move(cand);
                    fb5_max_bl_ = max_bl;
                    last_fb5_bl_ = -1;
                    fb5_present_.store(true);
                    update_needs_spectrum_locked();
                }
                LOGI("Framebuffer", "Hotplug detected LED screen on %s (%ux%u %ubpp, max_bl=%d)",
                     cand_dev.c_str(), cand_w, cand_h, cand_bpp, max_bl);
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
            bool transient_active = (voice_state_ != VoiceVisualState::Idle) || mic_muted_ ||
                                    (now < osd_until_) || (now < flash_until_) ||
                                    (now < cal_until_) || (now < scr_text_until_);
            bool ring_static = (rcfg.mode == "off" || rcfg.mode == "solid");
            bool scr_has = fb5_present_.load();
            bool scr_static = (!scr_has || scfg.mode == "off" || scfg.mode == "clock");
            int sleep_ms = 33; // ~30 FPS for animated modes / transient flashes
            if (!transient_active && ring_static && scr_static) {
                sleep_ms = (scr_has && scfg.mode == "clock") ? 200 : 500;
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
