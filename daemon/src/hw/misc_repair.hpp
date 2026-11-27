#pragma once

#include <algorithm>
#include <array>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <functional>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

namespace openstation::hw {

#include "misc_repair_data.inc"

enum class WirelessChip {
    Unknown,
    Intel,
    Realtek,
    Unisoc,
};

enum class MiscTarget {
    None,
    Intel_Rev35f,      // Boards 0008 / 00052 (Intel 7265) -> misc.img
    Mod00053_Rev375f,  // Boards 00053 (Realtek / Unisoc) -> misc_00053.img
};

enum class MiscRepairAction {
    NoOp,
    UpdatedNoReboot,
    UpdatedAndReboot,
    Failed,
};

struct MiscPaths {
    std::string sys_pci = "/sys/bus/pci/devices";
    std::string sys_sdio = "/sys/bus/sdio/devices";
    std::string sys_usb = "/sys/bus/usb/devices";
    std::string sys_net = "/sys/class/net";
    std::string sys_bluetooth = "/sys/class/bluetooth";
    std::string dev_tty_bt0 = "/dev/ttyBT0";
    std::string dev_tty_s1 = "/dev/ttyS1";
    std::string misc_block_by_name = "/dev/block/platform/ffe07000.emmc/by-name/misc";
    std::string misc_block_fallback = "/dev/block/misc";
    std::string backup_path = "/data/local/openstation/misc_backup.img";
    std::string guard_state_path = "/data/local/openstation/.misc_autofix_state";
    std::string lock_dir_path = "/data/local/openstation/.misc_autofix.lock";
    std::string wifi_power_bin = "/vendor/bin/wifi_power";
    std::string proc_cmdline_path = "/proc/cmdline";
    std::string override_dt_id;
    std::function<bool()> wifi_power_hook;
    std::function<std::string(const std::string&)> service_state_hook;
    bool check_hal_services = true;
};

struct MiscRepairReport {
    WirelessChip chip = WirelessChip::Unknown;
    MiscTarget target = MiscTarget::None;
    MiscRepairAction action = MiscRepairAction::NoOp;
    std::string current_dt_id;
    std::string target_dt_id;
    std::string current_revision;      // "0008_00052" | "00053" | "unknown"
    std::string recommended_revision;  // "0008_00052" | "00053" | ""
    std::string current_misc_revision; // "0008_00052" | "00053" | "unknown"
    bool wifi_ok = false;
    bool bt_ok = false;
    bool dt_matches = true;
    bool needs_repair = false;
    bool flash_matched_target = false;
    bool wrote_flash = false;
    bool backup_created = false;
    bool reboot_suppressed_by_guard = false;
    std::string block_device;
    std::string detail;
};

namespace detail {

inline std::string trim_ascii(std::string s) {
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ' || s.back() == '\t')) {
        s.pop_back();
    }
    size_t start = 0;
    while (start < s.size() && (s[start] == ' ' || s[start] == '\t' || s[start] == '\r' || s[start] == '\n')) {
        ++start;
    }
    if (start > 0) s.erase(0, start);
    return s;
}

inline std::string lower_hex_token(const std::string& raw) {
    std::string t = trim_ascii(raw);
    for (char& c : t) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    if (t.rfind("0x", 0) == 0) {
        t.erase(0, 2);
    }
    return t;
}

inline bool read_small_text(const std::string& path, std::string& out, size_t max_bytes = 4096) {
    out.clear();
    int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    char buf[512];
    while (out.size() < max_bytes) {
        const size_t want = std::min(sizeof(buf), max_bytes - out.size());
        const ssize_t n = ::read(fd, buf, want);
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            return false;
        }
        if (n == 0) break;
        out.append(buf, static_cast<size_t>(n));
    }
    ::close(fd);
    return true;
}

inline bool path_exists_plain(const std::string& p) {
    struct stat st {};
    return !p.empty() && ::stat(p.c_str(), &st) == 0;
}

inline bool write_text_file(const std::string& path, const std::string& text) {
    const std::string tmp = path + ".tmp";
    int fd = ::open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return false;
    size_t off = 0;
    while (off < text.size()) {
        const ssize_t n = ::write(fd, text.data() + off, text.size() - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            ::unlink(tmp.c_str());
            return false;
        }
        off += static_cast<size_t>(n);
    }
    ::fsync(fd);
    ::close(fd);
    if (::rename(tmp.c_str(), path.c_str()) != 0) {
        ::unlink(tmp.c_str());
        return false;
    }
    return true;
}

inline void ensure_parent_dir(const std::string& file_path) {
    const auto slash = file_path.find_last_of('/');
    if (slash == std::string::npos || slash == 0) return;
    const std::string dir = file_path.substr(0, slash);
    size_t pos = 1;
    while (pos <= dir.size()) {
        const auto next = dir.find('/', pos);
        const std::string cur = (next == std::string::npos) ? dir : dir.substr(0, next);
        ::mkdir(cur.c_str(), 0755);
        if (next == std::string::npos) break;
        pos = next + 1;
    }
}

class Sha256 {
public:
    static std::string digest_hex(const uint8_t* data, size_t len) {
        Sha256 ctx;
        ctx.update(data, len);
        return ctx.final_hex();
    }

private:
    uint32_t state_[8] = {
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u,
    };
    uint64_t bitlen_ = 0;
    uint8_t buf_[64] = {};
    size_t buflen_ = 0;

    static inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

    void transform(const uint8_t block[64]) {
        static constexpr uint32_t K[64] = {
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
        };
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (uint32_t(block[i * 4]) << 24) | (uint32_t(block[i * 4 + 1]) << 16) |
                   (uint32_t(block[i * 4 + 2]) << 8) | uint32_t(block[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            const uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
        uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];
        for (int i = 0; i < 64; ++i) {
            const uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            const uint32_t ch = (e & f) ^ ((~e) & g);
            const uint32_t t1 = h + S1 + ch + K[i] + w[i];
            const uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t t2 = S0 + maj;
            h = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        state_[0] += a; state_[1] += b; state_[2] += c; state_[3] += d;
        state_[4] += e; state_[5] += f; state_[6] += g; state_[7] += h;
    }

    void update(const uint8_t* data, size_t len) {
        for (size_t i = 0; i < len; ++i) {
            buf_[buflen_++] = data[i];
            if (buflen_ == 64) {
                transform(buf_);
                bitlen_ += 512;
                buflen_ = 0;
            }
        }
    }

    std::string final_hex() {
        uint64_t total_bits = bitlen_ + buflen_ * 8u;
        buf_[buflen_++] = 0x80;
        if (buflen_ > 56) {
            while (buflen_ < 64) buf_[buflen_++] = 0x00;
            transform(buf_);
            buflen_ = 0;
        }
        while (buflen_ < 56) buf_[buflen_++] = 0x00;
        for (int i = 7; i >= 0; --i) {
            buf_[buflen_++] = static_cast<uint8_t>((total_bits >> (i * 8)) & 0xffu);
        }
        transform(buf_);

        static constexpr char hex[] = "0123456789abcdef";
        std::string out(64, '0');
        for (int i = 0; i < 8; ++i) {
            for (int j = 0; j < 4; ++j) {
                const uint8_t byte = static_cast<uint8_t>((state_[i] >> ((3 - j) * 8)) & 0xffu);
                out[(i * 4 + j) * 2] = hex[(byte >> 4) & 0x0f];
                out[(i * 4 + j) * 2 + 1] = hex[byte & 0x0f];
            }
        }
        return out;
    }
};

struct DirLockGuard {
    std::string dir;
    bool locked = false;

    explicit DirLockGuard(std::string d) : dir(std::move(d)) {
        if (dir.empty()) return;
        ensure_parent_dir(dir);
        for (int i = 0; i < 15; ++i) {
            if (::mkdir(dir.c_str(), 0700) == 0) {
                locked = true;
                return;
            }
            if (errno != EEXIST) return;
            ::usleep(100000);
        }
        // Clear stale lock directory left behind by an unclean power loss/reboot.
        struct stat st {};
        if (::stat(dir.c_str(), &st) == 0 && ::time(nullptr) - st.st_mtime > 30) {
            ::rmdir(dir.c_str());
            if (::mkdir(dir.c_str(), 0700) == 0) locked = true;
        }
    }

    ~DirLockGuard() {
        if (locked) ::rmdir(dir.c_str());
    }
};

inline bool read_exact_at(int fd, off_t offset, uint8_t* dst, size_t len) {
    if (::lseek(fd, offset, SEEK_SET) < 0) return false;
    size_t got = 0;
    while (got < len) {
        const ssize_t n = ::read(fd, dst + got, len - got);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) return false;
        got += static_cast<size_t>(n);
    }
    return true;
}

inline bool write_exact_at(int fd, off_t offset, const uint8_t* src, size_t len) {
    if (::lseek(fd, offset, SEEK_SET) < 0) return false;
    size_t put = 0;
    while (put < len) {
        const ssize_t n = ::write(fd, src + put, len - put);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        put += static_cast<size_t>(n);
    }
    ::fsync(fd);
    return true;
}

inline WirelessChip scan_buses_once(const MiscPaths& paths) {
    // 1. Check SDIO first (Realtek 024c:a822 or Unisoc 0000:0000 on 00053 boards)
    if (DIR* d = ::opendir(paths.sys_sdio.c_str())) {
        struct dirent* ent = nullptr;
        while ((ent = ::readdir(d)) != nullptr) {
            if (ent->d_name[0] == '.') continue;
            std::string vendor, device;
            const std::string base = paths.sys_sdio + "/" + ent->d_name;
            if (!read_small_text(base + "/vendor", vendor, 64)) continue;
            if (!read_small_text(base + "/device", device, 64)) continue;
            const std::string v = lower_hex_token(vendor);
            const std::string dev = lower_hex_token(device);
            if (v == "024c" && dev == "a822") {
                ::closedir(d);
                return WirelessChip::Realtek;
            }
            if (v == "0000" && dev == "0000") {
                ::closedir(d);
                return WirelessChip::Unisoc;
            }
        }
        ::closedir(d);
    }

    // 2. Check PCIe (Intel 7265 0x8086:0x095a on 0008 / 00052 boards)
    if (DIR* d = ::opendir(paths.sys_pci.c_str())) {
        struct dirent* ent = nullptr;
        while ((ent = ::readdir(d)) != nullptr) {
            if (ent->d_name[0] == '.') continue;
            std::string vendor;
            const std::string base = paths.sys_pci + "/" + ent->d_name;
            if (!read_small_text(base + "/vendor", vendor, 64)) continue;
            if (lower_hex_token(vendor) == "8086") {
                ::closedir(d);
                return WirelessChip::Intel;
            }
        }
        ::closedir(d);
    }

    // 3. Fallback: USB Bluetooth radio (Intel 7265 BT 8087:0a2a)
    if (DIR* d = ::opendir(paths.sys_usb.c_str())) {
        struct dirent* ent = nullptr;
        while ((ent = ::readdir(d)) != nullptr) {
            if (ent->d_name[0] == '.') continue;
            std::string idv, idp;
            const std::string base = paths.sys_usb + "/" + ent->d_name;
            if (!read_small_text(base + "/idVendor", idv, 64)) continue;
            if (!read_small_text(base + "/idProduct", idp, 64)) continue;
            if (lower_hex_token(idv) == "8087" && lower_hex_token(idp) == "0a2a") {
                ::closedir(d);
                return WirelessChip::Intel;
            }
        }
        ::closedir(d);
    }

    return WirelessChip::Unknown;
}

}  // namespace detail

inline const char* chip_name(WirelessChip chip) {
    switch (chip) {
        case WirelessChip::Intel: return "intel";
        case WirelessChip::Realtek: return "realtek";
        case WirelessChip::Unisoc: return "unisoc";
        case WirelessChip::Unknown: return "unknown";
    }
    return "unknown";
}

inline MiscTarget target_for_chip(WirelessChip chip) {
    switch (chip) {
        case WirelessChip::Intel: return MiscTarget::Intel_Rev35f;
        case WirelessChip::Realtek:
        case WirelessChip::Unisoc: return MiscTarget::Mod00053_Rev375f;
        case WirelessChip::Unknown: return MiscTarget::None;
    }
    return MiscTarget::None;
}

inline const char* target_dt_id(MiscTarget target) {
    switch (target) {
        case MiscTarget::Intel_Rev35f: return "g12a_s905x2_rev35f";
        case MiscTarget::Mod00053_Rev375f: return "g12a_s905x2_rev375f";
        case MiscTarget::None: return "";
    }
    return "";
}

inline const char* expected_sha256_for_target(MiscTarget target) {
    switch (target) {
        case MiscTarget::Intel_Rev35f: return kMiscIntel1MSha256;
        case MiscTarget::Mod00053_Rev375f: return kMisc000531MSha256;
        case MiscTarget::None: return "";
    }
    return "";
}

inline std::string revision_id_for_target(MiscTarget target) {
    switch (target) {
        case MiscTarget::Intel_Rev35f: return "0008_00052";
        case MiscTarget::Mod00053_Rev375f: return "00053";
        case MiscTarget::None: return "";
    }
    return "";
}

inline std::string revision_for_dt_id(const std::string& dt_id) {
    if (dt_id.empty()) return "unknown";
    if (dt_id.find("rev375f") != std::string::npos || dt_id.find("rev376f") != std::string::npos) {
        return "00053";
    }
    if (dt_id.find("rev35f") != std::string::npos || dt_id.find("rev3f") != std::string::npos ||
        dt_id.find("rev3") != std::string::npos || dt_id.find("dvt") != std::string::npos ||
        dt_id.find("pvt") != std::string::npos) {
        return "0008_00052";
    }
    return "unknown";
}

inline MiscTarget parse_misc_revision(const std::string& raw) {
    const std::string t = detail::lower_hex_token(raw);
    if (t == "0008" || t == "00052" || t == "0008_00052" || t == "0008/00052" ||
        t == "intel" || t == "intel_rev35f" || t == "rev35f" || t == "g12a_s905x2_rev35f") {
        return MiscTarget::Intel_Rev35f;
    }
    if (t == "00053" || t == "realtek" || t == "unisoc" || t == "00053_rev375f" ||
        t == "rev375f" || t == "g12a_s905x2_rev375f") {
        return MiscTarget::Mod00053_Rev375f;
    }
    return MiscTarget::None;
}

/**
 * Builds the exact verified 1 MiB (1,048,576 byte) misc partition image for `target`
 * and validates its SHA-256 checksum against the known-good digest before returning.
 */
inline bool build_verified_misc_image(MiscTarget target, std::vector<uint8_t>& out_img) {
    if (target == MiscTarget::None) return false;
    out_img.assign(kMiscImageSize, 0x00u);
    std::memcpy(out_img.data(), kMiscIntelHeader, kMiscHeaderSize);
    if (target == MiscTarget::Mod00053_Rev375f) {
        for (size_t i = 0; i < kMisc00053PatchCount; ++i) {
            const auto& p = kMisc00053Patch[i];
            if (p.offset < kMiscHeaderSize) {
                out_img[p.offset] = p.value;
            }
        }
    }
    const std::string got = detail::Sha256::digest_hex(out_img.data(), out_img.size());
    return got == expected_sha256_for_target(target);
}

/**
 * Detects physical Wi-Fi/BT hardware without trusting U-Boot/DTB board revision properties
 * (which are falsified if a mismatched misc image was flashed).
 */
inline WirelessChip detect_wireless_chip(const MiscPaths& paths, bool try_wifi_power = true) {
    WirelessChip chip = detail::scan_buses_once(paths);
    if (chip != WirelessChip::Unknown || !try_wifi_power) return chip;

    // A 00053 board booted under rev35f DTB never executes `/vendor/bin/wifi_power 1`
    // in init.amlogic.board.rc, leaving SDIO unpowered until toggled here.
    bool powered = false;
    if (paths.wifi_power_hook) {
        powered = paths.wifi_power_hook();
    } else if (!paths.wifi_power_bin.empty() && ::access(paths.wifi_power_bin.c_str(), X_OK) == 0) {
        const std::string cmd = paths.wifi_power_bin + " 1 >/dev/null 2>&1";
        powered = (std::system(cmd.c_str()) == 0);
    }
    if (!powered) return WirelessChip::Unknown;

    for (int i = 0; i < 6; ++i) {
        ::usleep(paths.wifi_power_hook ? 1000 : 180000);
        chip = detail::scan_buses_once(paths);
        if (chip != WirelessChip::Unknown) return chip;
    }
    return WirelessChip::Unknown;
}

inline std::string read_current_dt_id(const MiscPaths& paths) {
    if (!paths.override_dt_id.empty()) return detail::trim_ascii(paths.override_dt_id);

    std::string cmdline;
    if (detail::read_small_text(paths.proc_cmdline_path, cmdline, 8192)) {
        static constexpr const char* kKey = "androidboot.hardware.amlogic_dt_id=";
        const auto pos = cmdline.find(kKey);
        if (pos != std::string::npos) {
            const size_t val_start = pos + std::strlen(kKey);
            size_t val_end = cmdline.find_first_of(" \t\r\n", val_start);
            if (val_end == std::string::npos) val_end = cmdline.size();
            const std::string v = detail::trim_ascii(cmdline.substr(val_start, val_end - val_start));
            if (!v.empty()) return v;
        }
    }

#if defined(__ANDROID__)
    char prop_buf[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.boot.hardware.amlogic_dt_id", prop_buf) > 0) {
        return detail::trim_ascii(prop_buf);
    }
#endif
    return "";
}

inline bool is_bt_hci_present(const MiscPaths& paths) {
    DIR* d = ::opendir(paths.sys_bluetooth.c_str());
    if (!d) return false;
    struct dirent* ent = nullptr;
    bool found = false;
    while ((ent = ::readdir(d)) != nullptr) {
        if (std::strncmp(ent->d_name, "hci", 3) == 0) {
            found = true;
            break;
        }
    }
    ::closedir(d);
    return found;
}

inline std::string query_bt_service_state(const MiscPaths& paths, const char* service_name) {
    if (!service_name) return "";
    if (paths.service_state_hook) {
        return detail::trim_ascii(paths.service_state_hook(service_name));
    }
    if (!paths.check_hal_services) return "";
#if defined(__ANDROID__)
    char buf[PROP_VALUE_MAX] = {0};
    const std::string prop = std::string("init.svc.") + service_name;
    if (__system_property_get(prop.c_str(), buf) > 0) {
        return detail::trim_ascii(buf);
    }
#else
    (void)paths;
#endif
    return "";
}

/**
 * Checks whether Bluetooth hardware is present for the detected wireless chip.
 * Realtek (UART H5 on /dev/ttyS1) and Unisoc (char device /dev/ttyBT0 via sprdbt_tty)
 * use Android vendor BT HALs in userspace and never create /sys/class/bluetooth/hci*.
 */
inline bool is_bt_hardware_present(const MiscPaths& paths, WirelessChip chip, bool dt_matches) {
    const bool has_hci = is_bt_hci_present(paths);
    const std::string btlinux_state = query_bt_service_state(paths, "btlinux-1.0");
    const std::string vendor_bt_state = query_bt_service_state(paths, "vendor.bluetooth-1-0");

    switch (chip) {
        case WirelessChip::Intel:
            if (!has_hci) return false;
            if (vendor_bt_state == "running") return false;
            return true;
        case WirelessChip::Unisoc: {
            const bool has_tty_bt0 = detail::path_exists_plain(paths.dev_tty_bt0);
            if (!has_hci && !has_tty_bt0) return false;
            if (btlinux_state == "running") return false;
            return true;
        }
        case WirelessChip::Realtek: {
            const bool has_uart_h5 = dt_matches && detail::path_exists_plain(paths.dev_tty_s1);
            if (!has_hci && !has_uart_h5) return false;
            if (btlinux_state == "running") return false;
            return true;
        }
        case WirelessChip::Unknown:
        default:
            return has_hci;
    }
}

inline bool dt_id_matches_target(MiscTarget target, const std::string& dt_id) {
    if (dt_id.empty() || target == MiscTarget::None) return true;
    const bool is_00053_dt =
        (dt_id.find("rev375f") != std::string::npos) || (dt_id.find("rev376f") != std::string::npos);
    const bool is_intel_dt =
        !is_00053_dt &&
        ((dt_id.find("rev35f") != std::string::npos) || (dt_id.find("rev3f") != std::string::npos) ||
         (dt_id.find("rev3") != std::string::npos) || (dt_id.find("dvt") != std::string::npos) ||
         (dt_id.find("pvt") != std::string::npos));

    if (target == MiscTarget::Intel_Rev35f) {
        return !is_00053_dt;
    }
    if (target == MiscTarget::Mod00053_Rev375f) {
        return !is_intel_dt;
    }
    return true;
}

inline std::string resolve_misc_block_device(const MiscPaths& paths) {
    if (detail::path_exists_plain(paths.misc_block_by_name)) return paths.misc_block_by_name;
    if (detail::path_exists_plain(paths.misc_block_fallback)) return paths.misc_block_fallback;
    return "";
}

inline std::string identify_misc_revision_from_bytes(const uint8_t* data, size_t len) {
    if (!data || len < kMiscImageSize) return "unknown";
    const std::string sha = detail::Sha256::digest_hex(data, kMiscImageSize);
    if (sha == kMiscIntel1MSha256) return "0008_00052";
    if (sha == kMisc000531MSha256) return "00053";
    return "unknown";
}

/**
 * Strictly read-only diagnostic check for Wi-Fi, Bluetooth, DTB revision, and existing misc bytes.
 * Never opens /dev/block/.../misc for writing or triggers reboots.
 */
inline MiscRepairReport diagnose_misc_status(const MiscPaths& paths = MiscPaths{}, bool allow_power_up = false) {
    MiscRepairReport report;
    report.wifi_ok = detail::path_exists_plain(paths.sys_net + "/wlan0");
    report.current_dt_id = read_current_dt_id(paths);
    report.current_revision = revision_for_dt_id(report.current_dt_id);

    const WirelessChip initial_chip = detail::scan_buses_once(paths);
    const bool initial_dt_matches = dt_id_matches_target(target_for_chip(initial_chip), report.current_dt_id);
    const bool initial_bt_ok = is_bt_hardware_present(paths, initial_chip, initial_dt_matches);
    const bool probe_bus = allow_power_up && (initial_chip == WirelessChip::Unknown) &&
                           (!report.wifi_ok || !initial_bt_ok);
    report.chip = probe_bus ? detect_wireless_chip(paths, true) : initial_chip;
    report.target = target_for_chip(report.chip);
    report.target_dt_id = target_dt_id(report.target);
    report.recommended_revision = revision_id_for_target(report.target);
    report.dt_matches = dt_id_matches_target(report.target, report.current_dt_id);
    report.bt_ok = is_bt_hardware_present(paths, report.chip, report.dt_matches);
    report.block_device = resolve_misc_block_device(paths);

    report.current_misc_revision = "unknown";
    if (!report.block_device.empty()) {
        int fd = ::open(report.block_device.c_str(), O_RDONLY);
        if (fd >= 0) {
            std::vector<uint8_t> cur(kMiscImageSize, 0x00u);
            if (detail::read_exact_at(fd, 0, cur.data(), cur.size())) {
                report.current_misc_revision = identify_misc_revision_from_bytes(cur.data(), cur.size());
                if (report.target != MiscTarget::None) {
                    report.flash_matched_target = (report.current_misc_revision == report.recommended_revision);
                }
            }
            ::close(fd);
        }
    }

    report.needs_repair = (!report.wifi_ok || !report.bt_ok || !report.dt_matches);
    report.action = MiscRepairAction::NoOp;
    if (!report.wifi_ok && !report.bt_ok) {
        report.detail = "Wi-Fi и Bluetooth не работают";
    } else if (!report.wifi_ok) {
        report.detail = "Wi-Fi адаптер (wlan0) не найден";
    } else if (!report.bt_ok) {
        report.detail = "Bluetooth адаптер не обнаружен или конфликт BT HAL";
    } else if (!report.dt_matches) {
        report.detail = "Текущий DTB не совпадает с обнаруженным беспроводным чипом";
    } else {
        report.detail = "Wi-Fi, Bluetooth и DTB ревизии работают штатно";
    }
    return report;
}

namespace detail {

inline void write_verified_misc_image_internal(
    MiscTarget target,
    MiscRepairReport& report,
    const MiscPaths& paths,
    bool allow_reboot,
    bool use_reboot_guard,
    bool reboot_if_already_matching,
    const std::function<void()>& reboot_hook) {
    if (target == MiscTarget::None) {
        report.action = MiscRepairAction::Failed;
        report.detail = "Unsupported misc target";
        return;
    }

    std::vector<uint8_t> target_img;
    if (!build_verified_misc_image(target, target_img)) {
        report.action = MiscRepairAction::Failed;
        report.detail = "Embedded misc image SHA-256 verification failed";
        return;
    }

    report.block_device = resolve_misc_block_device(paths);
    if (report.block_device.empty()) {
        report.action = MiscRepairAction::Failed;
        report.detail = "misc block device not found";
        return;
    }

    int fd = ::open(report.block_device.c_str(), O_RDWR);
    if (fd < 0) {
        report.action = MiscRepairAction::Failed;
        report.detail = "Failed to open misc block device for read/write";
        return;
    }

    std::vector<uint8_t> current_buf(kMiscImageSize, 0x00u);
    if (!read_exact_at(fd, 0, current_buf.data(), current_buf.size())) {
        ::close(fd);
        report.action = MiscRepairAction::Failed;
        report.detail = "Failed to read existing misc block";
        return;
    }

    if (std::memcmp(current_buf.data(), target_img.data(), kMiscImageSize) == 0) {
        report.flash_matched_target = true;
        report.current_misc_revision = revision_id_for_target(target);
        ::close(fd);
        if (allow_reboot && reboot_if_already_matching) {
            report.action = MiscRepairAction::UpdatedAndReboot;
            report.detail = "misc already matches selected revision; scheduling reboot";
            if (reboot_hook) {
                reboot_hook();
            }
            return;
        }
        report.action = MiscRepairAction::NoOp;
        report.detail = "Existing misc already matches verified hardware target";
        return;
    }

    // Preserve first-time backup of original misc before overwriting.
    if (!paths.backup_path.empty() && !path_exists_plain(paths.backup_path)) {
        ensure_parent_dir(paths.backup_path);
        const std::string btmp = paths.backup_path + ".tmp";
        int bfd = ::open(btmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (bfd >= 0) {
            if (write_exact_at(bfd, 0, current_buf.data(), current_buf.size())) {
                ::close(bfd);
                if (::rename(btmp.c_str(), paths.backup_path.c_str()) == 0) {
                    report.backup_created = true;
                } else {
                    ::unlink(btmp.c_str());
                }
            } else {
                ::close(bfd);
                ::unlink(btmp.c_str());
            }
        }
    }

    if (!write_exact_at(fd, 0, target_img.data(), target_img.size())) {
        ::close(fd);
        report.action = MiscRepairAction::Failed;
        report.detail = "Failed writing target misc image";
        return;
    }

    std::vector<uint8_t> verify_buf(kMiscImageSize, 0x00u);
    if (!read_exact_at(fd, 0, verify_buf.data(), verify_buf.size()) ||
        std::memcmp(verify_buf.data(), target_img.data(), kMiscImageSize) != 0) {
        write_exact_at(fd, 0, current_buf.data(), current_buf.size());
        ::close(fd);
        report.action = MiscRepairAction::Failed;
        report.detail = "misc write verification failed; rolled back";
        return;
    }
    ::close(fd);
    ::sync();
    report.wrote_flash = true;
    report.current_misc_revision = revision_id_for_target(target);

    if (!allow_reboot) {
        report.action = MiscRepairAction::UpdatedNoReboot;
        report.detail = "misc updated for " + revision_id_for_target(target) + " (reboot required)";
        return;
    }

    if (use_reboot_guard) {
        const std::string guard_key = std::string(chip_name(report.chip)) + ":" +
                                      (report.current_dt_id.empty() ? "unknown" : report.current_dt_id) +
                                      "->" + report.target_dt_id;
        std::string prev_guard;
        if (!paths.guard_state_path.empty()) {
            read_small_text(paths.guard_state_path, prev_guard, 512);
            prev_guard = trim_ascii(prev_guard);
        }
        if (!prev_guard.empty() && prev_guard == guard_key) {
            report.reboot_suppressed_by_guard = true;
            report.action = MiscRepairAction::UpdatedNoReboot;
            report.detail = "misc updated, but auto-reboot suppressed (already attempted for " + guard_key + ")";
            return;
        }
        if (!paths.guard_state_path.empty()) {
            ensure_parent_dir(paths.guard_state_path);
            write_text_file(paths.guard_state_path, guard_key + "\n");
        }
    } else if (!paths.guard_state_path.empty()) {
        ::unlink(paths.guard_state_path.c_str());
    }

    report.action = MiscRepairAction::UpdatedAndReboot;
    report.detail = "misc updated for " + revision_id_for_target(target) + "; scheduling reboot";
    if (reboot_hook) {
        reboot_hook();
    }
}

}  // namespace detail

/**
 * Flashes an explicitly user-selected misc revision (0008/00052 or 00053) with SHA-256
 * verification, backup, and byte-exact readback validation.
 */
inline MiscRepairReport flash_verified_misc_target(
    MiscTarget explicit_target,
    const MiscPaths& paths = MiscPaths{},
    bool allow_reboot = false,
    const std::function<void()>& reboot_hook = nullptr) {
    MiscRepairReport report;
    detail::DirLockGuard lock(paths.lock_dir_path);

    report.chip = detect_wireless_chip(paths, false);
    report.target = explicit_target;
    report.target_dt_id = target_dt_id(explicit_target);
    report.recommended_revision = revision_id_for_target(target_for_chip(report.chip));
    report.current_dt_id = read_current_dt_id(paths);
    report.current_revision = revision_for_dt_id(report.current_dt_id);
    report.wifi_ok = detail::path_exists_plain(paths.sys_net + "/wlan0");
    report.dt_matches = dt_id_matches_target(explicit_target, report.current_dt_id);
    const bool chip_dt_matches = dt_id_matches_target(target_for_chip(report.chip), report.current_dt_id);
    report.bt_ok = is_bt_hardware_present(paths, report.chip, chip_dt_matches);
    report.needs_repair = (!report.wifi_ok || !report.bt_ok || !report.dt_matches);

    detail::write_verified_misc_image_internal(
        explicit_target, report, paths, allow_reboot,
        /*use_reboot_guard=*/false,
        /*reboot_if_already_matching=*/allow_reboot,
        reboot_hook);
    return report;
}

/**
 * Inspects physical Wi-Fi/BT buses, runtime wlan0/hci state, and kernel DT ID; if a wrong
 * misc partition is preventing wireless hardware from initializing cleanly, backs up existing
 * misc (first 1 MiB) and writes + readback-verifies the exact hardware-matched 1 MiB image.
 */
inline MiscRepairReport auto_repair_misc_if_needed(
    const MiscPaths& paths = MiscPaths{},
    bool allow_reboot = false,
    bool force = false,
    const std::function<void()>& reboot_hook = nullptr) {
    MiscRepairReport report;
    detail::DirLockGuard lock(paths.lock_dir_path);

    report.chip = detect_wireless_chip(paths, true);
    report.target = target_for_chip(report.chip);
    report.target_dt_id = target_dt_id(report.target);
    report.recommended_revision = revision_id_for_target(report.target);
    report.current_dt_id = read_current_dt_id(paths);
    report.current_revision = revision_for_dt_id(report.current_dt_id);
    report.wifi_ok = detail::path_exists_plain(paths.sys_net + "/wlan0");
    report.dt_matches = dt_id_matches_target(report.target, report.current_dt_id);
    report.bt_ok = is_bt_hardware_present(paths, report.chip, report.dt_matches);
    report.needs_repair = (!report.wifi_ok || !report.bt_ok || !report.dt_matches);

    if (report.chip == WirelessChip::Unknown || report.target == MiscTarget::None) {
        report.action = MiscRepairAction::NoOp;
        report.detail = "No supported Wi-Fi/BT hardware detected; misc untouched";
        return report;
    }

    if (!force && report.wifi_ok && report.bt_ok && report.dt_matches) {
        if (!paths.guard_state_path.empty()) {
            ::unlink(paths.guard_state_path.c_str());
        }
        report.action = MiscRepairAction::NoOp;
        report.detail = "Wi-Fi, Bluetooth, and kernel DTB match detected hardware";
        return report;
    }

    detail::write_verified_misc_image_internal(
        report.target, report, paths, allow_reboot,
        /*use_reboot_guard=*/true,
        /*reboot_if_already_matching=*/false,
        reboot_hook);
    return report;
}

}  // namespace openstation::hw
