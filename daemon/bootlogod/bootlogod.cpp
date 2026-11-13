#include <algorithm>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/system_properties.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

constexpr const char* kSpiSetAnimMode =
    "/sys/devices/platform/soc/ffd00000.cbus/ffd15000.spi/spi_master/spi32766/spi32766.0/lp_common/set_animation_mode";
constexpr const char* kSpiPowerSaving =
    "/sys/devices/platform/soc/ffd00000.cbus/ffd15000.spi/spi_master/spi32766/spi32766.0/lp_common/power_saving_mode";
constexpr const char* kHandoffFlag = "/dev/.openstation_boot_handoff";
constexpr double kPi = 3.14159265358979323846;

volatile sig_atomic_t g_stop = 0;

void on_sig(int) {
    g_stop = 1;
}

struct FbDev {
    int fd = -1;
    std::string dev_path;
    std::string bl_path;
    uint32_t xres = 0;
    uint32_t yres = 0;
    uint32_t bpp = 24;
    uint32_t line_length = 0;
    uint32_t red_off = 16;
    uint32_t green_off = 8;
    uint32_t blue_off = 0;
    int max_bl = 255;
    std::vector<uint8_t> buf;
    std::vector<uint8_t> prev;
    bool has_prev = false;
};

struct Rgb {
    uint8_t r = 0;
    uint8_t g = 0;
    uint8_t b = 0;
};

bool read_trimmed(const std::string& p, std::string& out) {
    int fd = open(p.c_str(), O_RDONLY);
    if (fd < 0) return false;
    char tmp[128];
    ssize_t n = read(fd, tmp, sizeof(tmp) - 1);
    close(fd);
    if (n <= 0) return false;
    tmp[n] = '\0';
    out = tmp;
    while (!out.empty() && (out.back() == '\n' || out.back() == '\r' || out.back() == ' ')) {
        out.pop_back();
    }
    return true;
}

void write_sysfs(const char* path, const char* val) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return;
    size_t len = std::strlen(val);
    (void)write(fd, val, len);
    close(fd);
}

void write_bl(const std::string& bl_path, int value, int max_val) {
    if (bl_path.empty()) return;
    std::string p = bl_path + "/brightness";
    int fd = open(p.c_str(), O_WRONLY);
    if (fd < 0) return;
    int clamped = std::clamp(value, 0, std::max(1, max_val));
    char tmp[24];
    int len = snprintf(tmp, sizeof(tmp), "%d\n", clamped);
    (void)write(fd, tmp, static_cast<size_t>(len));
    close(fd);
}

bool open_fb_by_name(const char* preferred_name, const char* fallback_dev, FbDev& out) {
    std::string chosen;
    DIR* dir = opendir("/sys/class/graphics");
    if (dir) {
        while (struct dirent* ent = readdir(dir)) {
            if (std::strncmp(ent->d_name, "fb", 2) != 0) continue;
            std::string name;
            std::string np = std::string("/sys/class/graphics/") + ent->d_name + "/name";
            if (read_trimmed(np, name) && name.find(preferred_name) != std::string::npos) {
                chosen = std::string("/dev/graphics/") + ent->d_name;
                break;
            }
        }
        closedir(dir);
    }
    if (chosen.empty()) chosen = fallback_dev;

    int fd = open(chosen.c_str(), O_RDWR);
    if (fd < 0) return false;

    fb_var_screeninfo vi{};
    fb_fix_screeninfo fi{};
    if (ioctl(fd, FBIOGET_VSCREENINFO, &vi) != 0 || ioctl(fd, FBIOGET_FSCREENINFO, &fi) != 0) {
        close(fd);
        return false;
    }
    ioctl(fd, FBIOBLANK, 0);

    out.fd = fd;
    out.dev_path = chosen;
    auto slash = chosen.find_last_of('/');
    std::string base = (slash != std::string::npos) ? chosen.substr(slash + 1) : "fb4";
    out.bl_path = "/sys/class/backlight/" + base;
    out.xres = vi.xres > 0 ? vi.xres : 24;
    out.yres = vi.yres > 0 ? vi.yres : 1;
    out.bpp = vi.bits_per_pixel > 0 ? vi.bits_per_pixel : 24;
    out.line_length = fi.line_length > 0 ? fi.line_length : (out.xres * (out.bpp / 8));
    out.red_off = vi.red.offset;
    out.green_off = vi.green.offset;
    out.blue_off = vi.blue.offset;
    std::string max_s;
    if (read_trimmed(out.bl_path + "/max_brightness", max_s)) {
        out.max_bl = std::max(1, atoi(max_s.c_str()));
    }
    out.buf.assign(static_cast<size_t>(out.line_length) * out.yres, 0);
    out.prev.clear();
    out.has_prev = false;
    return true;
}

void set_px(FbDev& d, uint32_t x, uint32_t y, uint8_t r, uint8_t g, uint8_t b) {
    if (x >= d.xres || y >= d.yres || d.bpp < 24) return;
    size_t bytes_pp = d.bpp / 8;
    size_t off = static_cast<size_t>(y) * d.line_length + static_cast<size_t>(x) * bytes_pp;
    if (off + bytes_pp > d.buf.size()) return;
    if (d.red_off == 16 && d.green_off == 8 && d.blue_off == 0) {
        d.buf[off] = b;
        d.buf[off + 1] = g;
        d.buf[off + 2] = r;
    } else if (d.red_off == 0 && d.green_off == 8 && d.blue_off == 16) {
        d.buf[off] = r;
        d.buf[off + 1] = g;
        d.buf[off + 2] = b;
    } else {
        d.buf[off + std::min<size_t>(2, d.red_off / 8)] = r;
        d.buf[off + std::min<size_t>(2, d.green_off / 8)] = g;
        d.buf[off + std::min<size_t>(2, d.blue_off / 8)] = b;
    }
    if (bytes_pp == 4) d.buf[off + 3] = 255;
}

void flush_fb(FbDev& d) {
    if (d.fd < 0 || d.buf.empty()) return;
    if (d.has_prev && d.prev.size() == d.buf.size() &&
        std::memcmp(d.buf.data(), d.prev.data(), d.buf.size()) == 0) {
        return;
    }
    ssize_t w = pwrite(d.fd, d.buf.data(), d.buf.size(), 0);
    if (w == static_cast<ssize_t>(d.buf.size())) {
        d.prev = d.buf;
        d.has_prev = true;
    }
}

Rgb lerp_rgb(Rgb a, Rgb b, float t) {
    t = std::clamp(t, 0.0f, 1.0f);
    return Rgb{
        static_cast<uint8_t>(std::lround(a.r + (b.r - a.r) * t)),
        static_cast<uint8_t>(std::lround(a.g + (b.g - a.g) * t)),
        static_cast<uint8_t>(std::lround(a.b + (b.b - a.b) * t)),
    };
}

struct Glyph5x7 {
    char ch;
    uint8_t cols[5];
};

constexpr Glyph5x7 kBannerFont[] = {
    {' ', {0x00,0x00,0x00,0x00,0x00}},
    {'A', {0x7E,0x11,0x11,0x11,0x7E}},
    {'E', {0x7F,0x49,0x49,0x49,0x41}},
    {'I', {0x00,0x41,0x7F,0x41,0x00}},
    {'M', {0x7F,0x02,0x0C,0x02,0x7F}},
    {'N', {0x7F,0x04,0x08,0x10,0x7F}},
    {'O', {0x3E,0x41,0x41,0x41,0x3E}},
    {'P', {0x7F,0x09,0x09,0x09,0x06}},
    {'S', {0x46,0x49,0x49,0x49,0x31}},
    {'T', {0x01,0x01,0x7F,0x01,0x01}},
    {'X', {0x63,0x14,0x08,0x14,0x63}},
};

const uint8_t* glyph_for(char c) {
    for (const auto& g : kBannerFont) {
        if (g.ch == c) return g.cols;
    }
    return kBannerFont[0].cols;
}

bool handoff_requested() {
    if (access(kHandoffFlag, F_OK) == 0) return true;
    char prop[PROP_VALUE_MAX]{};
    if (__system_property_get("openstation.daemon.ready", prop) > 0 && prop[0] == '1') {
        return true;
    }
    return false;
}

bool android_boot_completed() {
    char prop[PROP_VALUE_MAX]{};
    return (__system_property_get("sys.boot_completed", prop) > 0 && prop[0] == '1');
}

} // namespace

int main() {
    std::signal(SIGTERM, on_sig);
    std::signal(SIGINT, on_sig);

    // Disable SPI MCU canned boot animation so we own fb4/fb5 cleanly
    write_sysfs(kSpiPowerSaving, "0\n");
    write_sysfs(kSpiSetAnimMode, "0\n");

    FbDev screen;
    FbDev ring;
    auto probe_start = std::chrono::steady_clock::now();
    while (!g_stop && !handoff_requested()) {
        if (screen.fd < 0) open_fb_by_name("YNDX LED screen", "/dev/graphics/fb4", screen);
        if (ring.fd < 0) open_fb_by_name("YNDX LED ring", "/dev/graphics/fb5", ring);
        if (screen.fd >= 0 || ring.fd >= 0) break;
        if (std::chrono::duration<double>(std::chrono::steady_clock::now() - probe_start).count() > 8.0) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(80));
    }

    if (screen.fd < 0 && ring.fd < 0) return 0;

    if (screen.fd >= 0) write_bl(screen.bl_path, std::min(screen.max_bl, 90), screen.max_bl);
    if (ring.fd >= 0) write_bl(ring.bl_path, std::min(ring.max_bl, 195), ring.max_bl);

    auto t0 = std::chrono::steady_clock::now();
    auto completed_at = std::chrono::steady_clock::time_point::min();
    bool exited_on_handoff = false;

    const Rgb c_cyan{0, 224, 255};
    const Rgb c_gold{232, 198, 134};
    const Rgb c_violet{132, 74, 245};

    while (!g_stop) {
        if (handoff_requested()) {
            exited_on_handoff = true;
            break;
        }
        auto now = std::chrono::steady_clock::now();
        double t = std::chrono::duration<double>(now - t0).count();

        if (android_boot_completed()) {
            if (completed_at == std::chrono::steady_clock::time_point::min()) {
                completed_at = now;
            } else if (std::chrono::duration<double>(now - completed_at).count() > 8.0) {
                break;
            }
        }
        if (t > 110.0) break;

        // 1. Animate 24-LED Top Ring (smooth Cyan/Gold Liquid Glass dual comet)
        if (ring.fd >= 0) {
            std::fill(ring.buf.begin(), ring.buf.end(), 0);
            uint32_t n = std::max<uint32_t>(1, ring.xres);
            float intro = static_cast<float>(std::min(1.0, t / 0.8));
            double head = t * 14.0;
            for (uint32_t i = 0; i < n; ++i) {
                double d1 = std::fmod(head - static_cast<double>(i) + 256.0, static_cast<double>(n));
                double d2 = std::fmod(head + n * 0.5 - static_cast<double>(i) + 256.0, static_cast<double>(n));
                float a1 = static_cast<float>(std::exp(-d1 * 0.42));
                float a2 = static_cast<float>(std::exp(-d2 * 0.48)) * 0.72f;
                float ambient = 0.08f + 0.05f * std::sin(static_cast<float>(t * 2.8 + i * 0.5));
                Rgb base = lerp_rgb(c_cyan, c_gold, a2 / std::max(0.01f, a1 + a2));
                float gain = std::clamp((a1 + a2 + ambient) * intro, 0.0f, 1.0f);
                set_px(ring, i, 0,
                       static_cast<uint8_t>(std::lround(base.r * gain)),
                       static_cast<uint8_t>(std::lround(base.g * gain)),
                       static_cast<uint8_t>(std::lround(base.b * gain)));
            }
            flush_fb(ring);
        }

        // 2. Animate 25x16 Front LED Screen (OpenStation Orb Reveal -> "OS" Emblem -> "OPENSTATION")
        if (screen.fd >= 0) {
            std::fill(screen.buf.begin(), screen.buf.end(), 0);
            int W = static_cast<int>(screen.xres);
            int H = static_cast<int>(screen.yres);
            float cx = (W - 1) * 0.5f;
            float cy = (H - 1) * 0.5f;

            // Subtle ambient liquid caustic glow background
            float global_in = static_cast<float>(std::min(1.0, t / 0.45));

            if (t < 1.35) {
                // Stage A: Expanding sonic orb wave from center
                float rad = static_cast<float>(t) * 14.5f;
                for (int y = 0; y < H; ++y) {
                    for (int x = 0; x < W; ++x) {
                        float dx = (x - cx) * 0.58f;
                        float dy = (y - cy);
                        float dist = std::sqrt(dx * dx + dy * dy);
                        float ring_w = std::exp(-std::pow(dist - rad, 2.0f) * 0.48f);
                        float core_w = std::exp(-dist * dist * 0.22f) * std::max(0.0f, 1.0f - static_cast<float>(t * 0.55));
                        Rgb col = lerp_rgb(c_cyan, c_gold, std::min(1.0f, core_w * 1.4f));
                        float k = std::clamp((ring_w * 0.95f + core_w * 1.1f) * global_in, 0.0f, 1.0f);
                        if (k > 0.02f) {
                            set_px(screen, static_cast<uint32_t>(x), static_cast<uint32_t>(y),
                                   static_cast<uint8_t>(std::lround(col.r * k)),
                                   static_cast<uint8_t>(std::lround(col.g * k)),
                                   static_cast<uint8_t>(std::lround(col.b * k)));
                        }
                    }
                }
            } else {
                double p = t - 1.35;
                if (p < 2.2) {
                    // Stage B: Crisp centered "OS" logo badge + glowing halo ring + bottom wave
                    float emblem_in = static_cast<float>(std::min(1.0, p / 0.45));
                    // Subtle circular ring corners behind OS
                    for (int y = 0; y < H; ++y) {
                        for (int x = 0; x < W; ++x) {
                            float dx = (x - cx) * 0.62f;
                            float dy = (y - 6.0f);
                            float dist = std::sqrt(dx * dx + dy * dy);
                            float halo = std::exp(-std::pow(dist - 5.9f, 2.0f) * 1.15f) * 0.34f * emblem_in;
                            if (halo > 0.02f) {
                                float sweep = 0.5f + 0.5f * std::sin(static_cast<float>(p * 3.8 - x * 0.25));
                                Rgb hc = lerp_rgb(c_violet, c_cyan, sweep);
                                set_px(screen, static_cast<uint32_t>(x), static_cast<uint32_t>(y),
                                       static_cast<uint8_t>(std::lround(hc.r * halo)),
                                       static_cast<uint8_t>(std::lround(hc.g * halo)),
                                       static_cast<uint8_t>(std::lround(hc.b * halo)));
                            }
                        }
                    }
                    // Centered "OS" (11px wide: 5 + 1 + 5)
                    const char* badge = "OS";
                    int bx = std::max(0, (W - 11) / 2);
                    int by = 3;
                    for (int ci = 0; ci < 2; ++ci) {
                        const uint8_t* cols = glyph_for(badge[ci]);
                        Rgb gc = (ci == 0) ? Rgb{242, 248, 255} : c_gold;
                        for (int cx_i = 0; cx_i < 5; ++cx_i) {
                            for (int cy_i = 0; cy_i < 7; ++cy_i) {
                                if (cols[cx_i] & (1 << cy_i)) {
                                    int px = bx + ci * 6 + cx_i;
                                    int py = by + cy_i;
                                    if (px >= 0 && px < W && py >= 0 && py < H) {
                                        set_px(screen, static_cast<uint32_t>(px), static_cast<uint32_t>(py),
                                               static_cast<uint8_t>(std::lround(gc.r * emblem_in)),
                                               static_cast<uint8_t>(std::lround(gc.g * emblem_in)),
                                               static_cast<uint8_t>(std::lround(gc.b * emblem_in)));
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Stage C: Smooth scrolling "OPENSTATION MAX"
                    const char* txt = "OPENSTATION MAX";
                    int len = static_cast<int>(std::strlen(txt));
                    int txt_w = len * 6 - 1;
                    int span = txt_w + W;
                    double scroll_t = p - 2.2;
                    int shift = static_cast<int>(scroll_t * 21.0) % std::max(1, span);
                    int start_x = W - shift;
                    int top_y = (H > 9) ? (H - 9) / 2 : 0;
                    for (int ci = 0; ci < len; ++ci) {
                        const uint8_t* cols = glyph_for(txt[ci]);
                        float mix = 0.5f + 0.5f * std::sin(static_cast<float>(scroll_t * 2.5 - ci * 0.45));
                        Rgb lc = lerp_rgb(c_cyan, c_gold, mix * 0.55f);
                        if (ci >= 12) lc = c_gold; // "MAX" in gold
                        for (int cx_i = 0; cx_i < 5; ++cx_i) {
                            int px = start_x + ci * 6 + cx_i;
                            if (px < 0 || px >= W) continue;
                            for (int cy_i = 0; cy_i < 7; ++cy_i) {
                                int py = top_y + cy_i;
                                if (py >= 0 && py < H && (cols[cx_i] & (1 << cy_i))) {
                                    set_px(screen, static_cast<uint32_t>(px), static_cast<uint32_t>(py), lc.r, lc.g, lc.b);
                                }
                            }
                        }
                    }
                }

                // Smooth progress/energy bar on bottom row
                if (H >= 14) {
                    int y_bar = H - 2;
                    double sweep_pos = std::fmod(p * 14.0, static_cast<double>(W + 8)) - 4.0;
                    for (int x = 0; x < W; ++x) {
                        double dist = std::fabs(x - sweep_pos);
                        float glow = static_cast<float>(std::exp(-dist * dist * 0.14));
                        float base_k = 0.14f + 0.86f * glow;
                        Rgb bc = lerp_rgb(c_cyan, c_gold, glow);
                        set_px(screen, static_cast<uint32_t>(x), static_cast<uint32_t>(y_bar),
                               static_cast<uint8_t>(std::lround(bc.r * base_k)),
                               static_cast<uint8_t>(std::lround(bc.g * base_k)),
                               static_cast<uint8_t>(std::lround(bc.b * base_k)));
                    }
                }
            }
            flush_fb(screen);
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(33));
    }

    if (!exited_on_handoff) {
        if (ring.fd >= 0) {
            std::fill(ring.buf.begin(), ring.buf.end(), 0);
            ring.has_prev = false;
            flush_fb(ring);
        }
        if (screen.fd >= 0) {
            std::fill(screen.buf.begin(), screen.buf.end(), 0);
            screen.has_prev = false;
            flush_fb(screen);
        }
    }

    if (ring.fd >= 0) close(ring.fd);
    if (screen.fd >= 0) close(screen.fd);
    return 0;
}
