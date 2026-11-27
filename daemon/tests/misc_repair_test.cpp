#include "../src/hw/misc_repair.hpp"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
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

void mkdir_p(const std::string& p) {
    size_t pos = 1;
    while (pos <= p.size()) {
        const auto next = p.find('/', pos);
        const std::string sub = (next == std::string::npos) ? p : p.substr(0, next);
        ::mkdir(sub.c_str(), 0755);
        if (next == std::string::npos) break;
        pos = next + 1;
    }
}

void write_file(const std::string& p, const std::string& content) {
    const auto slash = p.find_last_of('/');
    if (slash != std::string::npos) mkdir_p(p.substr(0, slash));
    std::ofstream ofs(p, std::ios::binary | std::ios::trunc);
    assert(ofs.is_open());
    ofs.write(content.data(), static_cast<std::streamsize>(content.size()));
    assert(ofs.good());
}

void write_bytes(const std::string& p, const std::vector<uint8_t>& bytes) {
    const auto slash = p.find_last_of('/');
    if (slash != std::string::npos) mkdir_p(p.substr(0, slash));
    std::ofstream ofs(p, std::ios::binary | std::ios::trunc);
    assert(ofs.is_open());
    ofs.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    assert(ofs.good());
}

std::vector<uint8_t> read_bytes(const std::string& p) {
    std::ifstream ifs(p, std::ios::binary);
    assert(ifs.is_open());
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(ifs)), std::istreambuf_iterator<char>());
}

openstation::hw::MiscPaths make_base_paths(const std::string& root) {
    openstation::hw::MiscPaths p;
    p.sys_pci = root + "/sys/bus/pci/devices";
    p.sys_sdio = root + "/sys/bus/sdio/devices";
    p.sys_usb = root + "/sys/bus/usb/devices";
    p.sys_net = root + "/sys/class/net";
    p.sys_bluetooth = root + "/sys/class/bluetooth";
    p.dev_tty_bt0 = root + "/dev/ttyBT0";
    p.dev_tty_s1 = root + "/dev/ttyS1";
    p.misc_block_by_name = root + "/dev/block/misc";
    p.misc_block_fallback = root + "/dev/block/misc_fb";
    p.backup_path = root + "/data/misc_backup.img";
    p.guard_state_path = root + "/data/.misc_autofix_state";
    p.lock_dir_path = root + "/data/.misc_autofix.lock";
    p.wifi_power_bin = "";
    p.proc_cmdline_path = root + "/proc/cmdline";
    p.check_hal_services = false;
    mkdir_p(p.sys_pci);
    mkdir_p(p.sys_sdio);
    mkdir_p(p.sys_usb);
    mkdir_p(p.sys_net);
    mkdir_p(p.sys_bluetooth);
    return p;
}

}  // namespace

int main() {
    using namespace openstation::hw;

    // 1. Embedded 1 MiB image templates match exact verified SHA-256 checksums
    std::vector<uint8_t> intel_img, mod53_img;
    assert(build_verified_misc_image(MiscTarget::Intel_Rev35f, intel_img));
    assert(build_verified_misc_image(MiscTarget::Mod00053_Rev375f, mod53_img));
    assert(intel_img.size() == kMiscImageSize);
    assert(mod53_img.size() == kMiscImageSize);
    assert(intel_img != mod53_img);
    assert(std::string(intel_img.begin() + 720, intel_img.begin() + 740).find("rev35f") != std::string::npos);
    assert(std::string(mod53_img.begin() + 720, mod53_img.begin() + 740).find("rev375f") != std::string::npos);

    const std::string root = mkdtemp_dir("misc_repair_test");

    // 2. Unknown hardware never touches flash or reboots
    {
        auto p = make_base_paths(root + "/t_unknown");
        write_bytes(p.misc_block_by_name, std::vector<uint8_t>(kMiscImageSize, 0x00u));
        bool rebooted = false;
        auto rep = auto_repair_misc_if_needed(p, true, false, [&]() { rebooted = true; });
        assert(rep.chip == WirelessChip::Unknown);
        assert(rep.action == MiscRepairAction::NoOp);
        assert(!rep.wrote_flash);
        assert(!rebooted);
    }

    // 3. Intel 0008/00052 booted with misc_00053 (rev375f) + broken Wi-Fi/BT -> repairs to misc.img and reboots once
    {
        auto p = make_base_paths(root + "/t_intel_broken");
        write_file(p.sys_pci + "/0000:01:00.0/vendor", "0x8086\n");
        write_file(p.sys_pci + "/0000:01:00.0/device", "0x095a\n");
        write_file(p.proc_cmdline_path, "console=ttyS0 androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev375f quiet\n");
        write_bytes(p.misc_block_by_name, mod53_img);

        int reboots = 0;
        auto rep1 = auto_repair_misc_if_needed(p, true, false, [&]() { ++reboots; });
        assert(rep1.chip == WirelessChip::Intel);
        assert(rep1.target == MiscTarget::Intel_Rev35f);
        assert(!rep1.dt_matches);
        assert(rep1.wrote_flash);
        assert(rep1.backup_created);
        assert(rep1.action == MiscRepairAction::UpdatedAndReboot);
        assert(reboots == 1);
        assert(read_bytes(p.misc_block_by_name) == intel_img);
        assert(read_bytes(p.backup_path) == mod53_img);

        // Next run before reboot sees flash already equals intel_img -> NoOp, zero extra reboot or write
        auto rep2 = auto_repair_misc_if_needed(p, true, false, [&]() { ++reboots; });
        assert(rep2.flash_matched_target);
        assert(rep2.action == MiscRepairAction::NoOp);
        assert(!rep2.wrote_flash);
        assert(reboots == 1);
    }

    // 4. Reboot loop guard prevents repeat reboot for same hardware/dtb transition even if block regressed
    {
        auto p = make_base_paths(root + "/t_guard");
        write_file(p.sys_pci + "/0000:01:00.0/vendor", "0x8086\n");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev375f\n");
        write_bytes(p.misc_block_by_name, mod53_img);
        int reboots = 0;
        auto r1 = auto_repair_misc_if_needed(p, true, false, [&]() { ++reboots; });
        assert(r1.action == MiscRepairAction::UpdatedAndReboot);
        assert(reboots == 1);

        // Simulate block reverted while guard remains -> flash repaired but reboot suppressed
        write_bytes(p.misc_block_by_name, mod53_img);
        auto r2 = auto_repair_misc_if_needed(p, true, false, [&]() { ++reboots; });
        assert(r2.wrote_flash);
        assert(r2.reboot_suppressed_by_guard);
        assert(r2.action == MiscRepairAction::UpdatedNoReboot);
        assert(reboots == 1);
    }

    // 5. 00053 Realtek board booted with misc.img (rev35f) needs wifi_power hook to appear on SDIO -> repairs to misc_00053
    {
        auto p = make_base_paths(root + "/t_realtek");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev35f\n");
        write_bytes(p.misc_block_by_name, intel_img);
        p.wifi_power_hook = [&]() {
            write_file(p.sys_sdio + "/mmc2:0001:1/vendor", "0x024c\n");
            write_file(p.sys_sdio + "/mmc2:0001:1/device", "0xa822\n");
            return true;
        };

        int reboots = 0;
        auto rep = auto_repair_misc_if_needed(p, true, false, [&]() { ++reboots; });
        assert(rep.chip == WirelessChip::Realtek);
        assert(rep.target == MiscTarget::Mod00053_Rev375f);
        assert(!rep.dt_matches);
        assert(rep.wrote_flash);
        assert(rep.action == MiscRepairAction::UpdatedAndReboot);
        assert(reboots == 1);
        assert(read_bytes(p.misc_block_by_name) == mod53_img);
    }

    // 6. 00053 Unisoc SDIO (0000:0000) with broken BT under rev35f -> repairs to misc_00053
    {
        auto p = make_base_paths(root + "/t_unisoc");
        write_file(p.sys_sdio + "/mmc2:0001:1/vendor", "0x0000\n");
        write_file(p.sys_sdio + "/mmc2:0001:1/device", "0x0000\n");
        write_file(p.sys_net + "/wlan0/operstate", "up\n");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev35f\n");
        write_bytes(p.misc_block_by_name, intel_img);

        auto rep = auto_repair_misc_if_needed(p, false, false);
        assert(rep.chip == WirelessChip::Unisoc);
        assert(rep.target == MiscTarget::Mod00053_Rev375f);
        assert(rep.wifi_ok);
        assert(!rep.bt_ok);
        assert(rep.wrote_flash);
        assert(rep.action == MiscRepairAction::UpdatedNoReboot);
        assert(read_bytes(p.misc_block_by_name) == mod53_img);
    }

    // 7. Healthy Intel station on rev35f with blank stock misc -> NoOp (do not overwrite working box)
    {
        auto p = make_base_paths(root + "/t_healthy");
        write_file(p.sys_pci + "/0000:01:00.0/vendor", "0x8086\n");
        write_file(p.sys_net + "/wlan0/operstate", "up\n");
        write_file(p.sys_bluetooth + "/hci0/type", "1\n");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev35f\n");
        std::vector<uint8_t> zeros(kMiscImageSize, 0x00u);
        write_bytes(p.misc_block_by_name, zeros);
        write_file(p.guard_state_path, "intel:stale\n");

        bool rebooted = false;
        auto rep = auto_repair_misc_if_needed(p, true, false, [&]() { rebooted = true; });
        assert(rep.chip == WirelessChip::Intel);
        assert(rep.wifi_ok && rep.bt_ok && rep.dt_matches);
        assert(rep.action == MiscRepairAction::NoOp);
        assert(!rep.wrote_flash);
        assert(!rebooted);
        assert(read_bytes(p.misc_block_by_name) == zeros);
        struct stat st {};
        assert(::stat(p.guard_state_path.c_str(), &st) != 0);
    }

    // 8. Revision parser + read-only diagnosis + interactive explicit flash target
    {
        assert(parse_misc_revision("0008") == MiscTarget::Intel_Rev35f);
        assert(parse_misc_revision("00052") == MiscTarget::Intel_Rev35f);
        assert(parse_misc_revision("0008_00052") == MiscTarget::Intel_Rev35f);
        assert(parse_misc_revision("00053") == MiscTarget::Mod00053_Rev375f);
        assert(parse_misc_revision("bogus") == MiscTarget::None);

        auto p = make_base_paths(root + "/t_interactive");
        // Simulate broken Wi-Fi + BT even when chip is unknown (must flag needs_repair without touching flash)
        write_bytes(p.misc_block_by_name, intel_img);
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev35f\n");
        auto diag = diagnose_misc_status(p, false);
        assert(!diag.wifi_ok && !diag.bt_ok);
        assert(diag.needs_repair);
        assert(diag.current_revision == "0008_00052");
        assert(diag.current_misc_revision == "0008_00052");
        assert(diag.recommended_revision.empty());
        assert(read_bytes(p.misc_block_by_name) == intel_img);

        // User chooses 00053 interactively -> flashes misc_00053 even when chip wasn't enumerated yet
        int reboots = 0;
        auto rep = flash_verified_misc_target(MiscTarget::Mod00053_Rev375f, p, true, [&]() { ++reboots; });
        assert(rep.wrote_flash);
        assert(rep.action == MiscRepairAction::UpdatedAndReboot);
        assert(rep.current_misc_revision == "00053");
        assert(reboots == 1);
        assert(read_bytes(p.misc_block_by_name) == mod53_img);
    }

    // 9. Healthy 00053 Realtek (wlan0 + /dev/ttyS1 + rev375f, NO /sys/class/bluetooth/hci*) -> healthy NoOp
    {
        auto p = make_base_paths(root + "/t_healthy_rtl");
        write_file(p.sys_sdio + "/mmc2:0001:1/vendor", "0x024c\n");
        write_file(p.sys_sdio + "/mmc2:0001:1/device", "0xa822\n");
        write_file(p.sys_net + "/wlan0/operstate", "up\n");
        write_file(p.dev_tty_s1, "");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev375f\n");
        write_bytes(p.misc_block_by_name, mod53_img);

        auto diag = diagnose_misc_status(p, false);
        assert(diag.chip == WirelessChip::Realtek);
        assert(diag.wifi_ok && diag.bt_ok && diag.dt_matches);
        assert(!diag.needs_repair);

        auto rep = auto_repair_misc_if_needed(p, true, false);
        assert(rep.action == MiscRepairAction::NoOp);
        assert(!rep.wrote_flash);
    }

    // 10. Healthy 00053 Unisoc (wlan0 + /dev/ttyBT0 + rev375f, NO /sys/class/bluetooth/hci*) -> healthy NoOp
    {
        auto p = make_base_paths(root + "/t_healthy_uwe");
        write_file(p.sys_sdio + "/mmc2:0001:1/vendor", "0x0000\n");
        write_file(p.sys_sdio + "/mmc2:0001:1/device", "0x0000\n");
        write_file(p.sys_net + "/wlan0/operstate", "up\n");
        write_file(p.dev_tty_bt0, "");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev375f\n");
        write_bytes(p.misc_block_by_name, mod53_img);

        auto diag = diagnose_misc_status(p, false);
        assert(diag.chip == WirelessChip::Unisoc);
        assert(diag.wifi_ok && diag.bt_ok && diag.dt_matches);
        assert(!diag.needs_repair);
    }

    // 11. Realtek 00053 with conflicting btlinux-1.0 HAL -> flagged as broken BT needing repair
    {
        auto p = make_base_paths(root + "/t_rtl_hal_conflict");
        write_file(p.sys_sdio + "/mmc2:0001:1/vendor", "0x024c\n");
        write_file(p.sys_sdio + "/mmc2:0001:1/device", "0xa822\n");
        write_file(p.sys_net + "/wlan0/operstate", "up\n");
        write_file(p.dev_tty_s1, "");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev375f\n");
        p.service_state_hook = [](const std::string& svc) -> std::string {
            return (svc == "btlinux-1.0") ? "running" : "stopped";
        };
        auto diag = diagnose_misc_status(p, false);
        assert(!diag.bt_ok);
        assert(diag.needs_repair);
    }

    // 12. Intel with hci0 present but conflicting vendor.bluetooth-1-0 running -> flagged as broken BT
    {
        auto p = make_base_paths(root + "/t_intel_hal_conflict");
        write_file(p.sys_pci + "/0000:01:00.0/vendor", "0x8086\n");
        write_file(p.sys_net + "/wlan0/operstate", "up\n");
        write_file(p.sys_bluetooth + "/hci0/type", "1\n");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev35f\n");
        p.service_state_hook = [](const std::string& svc) -> std::string {
            return (svc == "vendor.bluetooth-1-0") ? "running" : "stopped";
        };
        auto diag = diagnose_misc_status(p, false);
        assert(!diag.bt_ok);
        assert(diag.needs_repair);
    }

    // 13. Matched DTB with early-boot missing wlan0/BT and blank stock misc -> MUST NEVER overwrite misc or reboot
    {
        auto p = make_base_paths(root + "/t_early_boot_safe");
        write_file(p.sys_pci + "/0000:01:00.0/vendor", "0x8086\n");
        write_file(p.proc_cmdline_path, "androidboot.hardware.amlogic_dt_id=g12a_s905x2_rev35f\n");
        std::vector<uint8_t> zeros(kMiscImageSize, 0x00u);
        write_bytes(p.misc_block_by_name, zeros);

        int reboots = 0;
        auto rep = auto_repair_misc_if_needed(p, true, false, [&]() { ++reboots; });
        assert(rep.chip == WirelessChip::Intel);
        assert(rep.dt_matches);
        assert(!rep.wifi_ok && !rep.bt_ok);
        assert(rep.action == MiscRepairAction::NoOp);
        assert(!rep.wrote_flash);
        assert(reboots == 0);
        assert(read_bytes(p.misc_block_by_name) == zeros);
    }

    std::printf("ALL_MISC_REPAIR_TESTS_PASSED\n");
    return 0;
}
