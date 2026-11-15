#include "../src/hw/android_optimizer.hpp"

#include <atomic>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
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

void write_str(const std::string& p, const std::string& content) {
    const auto slash = p.find_last_of('/');
    if (slash != std::string::npos) mkdir_p(p.substr(0, slash));
    std::ofstream ofs(p, std::ios::binary | std::ios::trunc);
    assert(ofs.is_open());
    ofs.write(content.data(), static_cast<std::streamsize>(content.size()));
    assert(ofs.good());
}

std::string read_str(const std::string& p) {
    std::string out;
    assert(openstation::hw::opt_detail::read_small_file(p, out, 65536));
    return openstation::hw::opt_detail::trim_copy(out);
}

std::string join_args(const std::vector<std::string>& args) {
    std::string out;
    for (size_t i = 0; i < args.size(); ++i) {
        if (i) out += ' ';
        out += args[i];
    }
    return out;
}

void test_parsers_and_zram_guard() {
    using namespace openstation::hw;
    const std::string meminfo =
        "MemTotal:        2038280 kB\n"
        "MemFree:          592100 kB\n"
        "MemAvailable:    1104200 kB\n";
    assert(parse_proc_meminfo_kb(meminfo, "MemTotal") == 2038280);
    assert(parse_proc_meminfo_kb(meminfo, "MemAvailable") == 1104200);
    assert(parse_proc_meminfo_kb(meminfo, "Missing") == -1);

    const std::string swaps =
        "Filename\tType\tSize\tUsed\tPriority\n"
        "/dev/block/zram0 partition 262140 12345 -1\n";
    assert(parse_zram_swap_used_kb(swaps) == 12345);
    assert(parse_zram_swap_used_kb("Filename\n") == -1);

    // Already >= 640 MB
    assert(!should_expand_zram(671088640ULL, 671088640ULL, 1000000, 0));
    // Zero swap used -> always safe even when MemAvailable isn't huge
    assert(should_expand_zram(268435456ULL, 671088640ULL, 150000, 0));
    // Swap used doesn't have 128 MB margin -> unsafe
    assert(!should_expand_zram(268435456ULL, 671088640ULL, 200000, 150000));
    // Sufficient MemAvailable headroom -> safe
    assert(should_expand_zram(268435456ULL, 671088640ULL, 400000, 150000));

    const std::string interrupts =
        "           CPU0       CPU1       CPU2       CPU3\n"
        " 15:      74854          0          0          0     GIC-0 192 Level     ffe40000.bifrost\n"
        " 27:    7791699          0          0          0     GIC-0  53 Edge      ffd1f000.i2c\n"
        " 31:    2049777          0          0          0     GIC-0 122 Level     ffd15000.spi\n"
        " 47:     123639          0          0          0     GIC-0  35 Edge      osd-vsync, vsync\n"
        " 56:      85856          0          0          0     GIC-0 223 Edge      meson-aml-mmc\n"
        " 64:    1790428          0          0          0   PCI-MSI   0 Edge      iwlwifi\n";
    const auto gpu_irqs = find_gic_irqs_matching(interrupts, {"ffe40000.bifrost", "osd-vsync"});
    assert(gpu_irqs.size() == 2 && gpu_irqs[0] == 15 && gpu_irqs[1] == 47);
    const auto mmc_irqs = find_gic_irqs_matching(interrupts, {"meson-aml-mmc"});
    assert(mmc_irqs.size() == 1 && mmc_irqs[0] == 56);
    const auto msi_irqs = find_gic_irqs_matching(interrupts, {"iwlwifi"});
    assert(msi_irqs.empty());
}

void test_crashloop_and_zram_sandbox() {
    using namespace openstation::hw;
    const std::string root = mkdtemp_dir("opt_zram");
    OptimizerPaths paths;
    paths.zram_dir = root + "/zram0";
    paths.proc_meminfo = root + "/meminfo";
    paths.proc_swaps = root + "/swaps";
    paths.proc_interrupts = root + "/interrupts";
    paths.proc_irq_dir = root + "/irq";
    paths.proc_sys_dir = root + "/proc_sys";
    paths.lmk_minfree = root + "/lmk_minfree";
    paths.mmc_queue_dir = root + "/mmc_queue";
    paths.mmc_boot0_iostats = root + "/boot0/iostats";
    paths.mmc_boot1_iostats = root + "/boot1/iostats";
    paths.cpuset_dir = root + "/cpuset";
    paths.cpufreq_policy_dir = root + "/cpufreq";
    paths.cpufreq_interactive_dir = root + "/interactive";
    paths.gpu_bifrost_dir = root + "/gpu_bifrost";
    paths.stamp_path = root + "/state/stamp";

    write_str(paths.zram_dir + "/disksize", "268435456\n");
    write_str(paths.zram_dir + "/reset", "0\n");
    write_str(paths.zram_dir + "/comp_algorithm", "lzo\n");
    write_str(paths.zram_dir + "/max_comp_streams", "2\n");
    write_str(paths.proc_meminfo, "MemAvailable: 900000 kB\n");
    write_str(paths.proc_swaps, "Filename Type Size Used Priority\n/dev/block/zram0 partition 262140 1000 -1\n");

    std::vector<std::string> cmds;
    OptimizerHooks hooks;
    hooks.get_prop = [](const std::string& p) -> std::string {
        if (p == "init.svc.logd") return "restarting";
        if (p == "init.svc.direct_keymaster") return "stopped";
        return "";
    };
    hooks.run_cmd = [&](const std::vector<std::string>& args, std::string* out, int) -> int {
        cmds.push_back(join_args(args));
        if (out) out->clear();
        return 0;
    };

    OptimizerStats stats;
    stop_crashloop_services(hooks, stats);
    assert(stats.services_stopped == 1);
    assert(cmds.size() == 1 && cmds[0] == "/system/bin/stop logd");

    cmds.clear();
    expand_zram_if_needed(paths, hooks, stats);
    assert(stats.zram_resized);
    assert(stats.zram_bytes == 671088640ULL);
    assert(read_str(paths.zram_dir + "/reset") == "1");
    assert(read_str(paths.zram_dir + "/max_comp_streams") == "4");
    assert(cmds.size() == 3);
    assert(cmds[0] == "/system/bin/swapoff /dev/block/zram0");
    assert(cmds[1] == "/system/bin/mkswap /dev/block/zram0");
    assert(cmds[2] == "/system/bin/swapon /dev/block/zram0");

    // Second run is a clean no-op
    cmds.clear();
    stats.zram_resized = false;
    expand_zram_if_needed(paths, hooks, stats);
    assert(!stats.zram_resized);
    assert(cmds.empty());

    // Kernel + IRQ sandbox test
    write_str(paths.proc_sys_dir + "/vm/page-cluster", "3\n");
    write_str(paths.proc_sys_dir + "/vm/swappiness", "60\n");
    write_str(paths.proc_sys_dir + "/vm/vfs_cache_pressure", "100\n");
    write_str(paths.proc_sys_dir + "/vm/dirty_background_ratio", "5\n");
    write_str(paths.proc_sys_dir + "/vm/dirty_ratio", "20\n");
    write_str(paths.proc_sys_dir + "/kernel/sched_schedstats", "1\n");
    write_str(paths.lmk_minfree, "8192,12288,16384,24576,28672,32768\n");
    write_str(paths.mmc_queue_dir + "/iostats", "1\n");
    write_str(paths.mmc_queue_dir + "/rq_affinity", "1\n");
    write_str(paths.mmc_queue_dir + "/read_ahead_kb", "128\n");
    write_str(paths.mmc_boot0_iostats, "1\n");
    write_str(paths.mmc_boot1_iostats, "1\n");
    write_str(paths.cpuset_dir + "/system-background/cpus", "0-3\n");
    write_str(paths.cpuset_dir + "/background/cpus", "0-3\n");
    write_str(paths.cpuset_dir + "/restricted/cpus", "0-3\n");
    write_str(paths.cpufreq_policy_dir + "/scaling_governor", "interactive\n");
    write_str(paths.cpufreq_policy_dir + "/schedutil/up_rate_limit_us", "1000\n");
    write_str(paths.cpufreq_policy_dir + "/schedutil/down_rate_limit_us", "80000\n");
    write_str(paths.cpufreq_interactive_dir + "/go_hispeed_load", "95\n");
    write_str(paths.cpufreq_interactive_dir + "/hispeed_freq", "1200000\n");
    write_str(paths.cpufreq_interactive_dir + "/io_is_busy", "1\n");
    write_str(paths.cpufreq_interactive_dir + "/timer_rate", "20000\n");
    write_str(paths.cpufreq_interactive_dir + "/min_sample_time", "80000\n");
    write_str(paths.cpufreq_interactive_dir + "/above_hispeed_delay", "20000\n");
    write_str(paths.cpufreq_interactive_dir + "/target_loads", "50 900000:70\n");
    write_str(paths.gpu_bifrost_dir + "/dvfs_period", "100\n");
    write_str(paths.gpu_bifrost_dir + "/power/autosuspend_delay_ms", "100\n");
    write_str(paths.gpu_bifrost_dir + "/power/control", "on\n");
    write_str(paths.proc_interrupts,
              " 17: 100 0 0 0 GIC-0 194 Level ffe40000.bifrost\n"
              " 27: 100 0 0 0 GIC-0 53 Edge ffd1f000.i2c\n"
              " 56: 100 0 0 0 GIC-0 223 Edge meson-aml-mmc\n"
              " 64: 100 0 0 0 PCI-MSI 0 Edge iwlwifi\n");
    write_str(paths.proc_irq_dir + "/17/smp_affinity", "f\n");
    write_str(paths.proc_irq_dir + "/27/smp_affinity", "f\n");
    write_str(paths.proc_irq_dir + "/56/smp_affinity", "f\n");

    apply_kernel_and_irq_tuning(paths, hooks, stats);
    assert(stats.kernel_tunables_written == 25);  // Three values already match the target.
    assert(read_str(paths.cpufreq_policy_dir + "/scaling_governor") == "schedutil");
    assert(read_str(paths.cpufreq_policy_dir + "/schedutil/up_rate_limit_us") == "1000");
    assert(read_str(paths.cpufreq_policy_dir + "/schedutil/down_rate_limit_us") == "15000");
    assert(read_str(paths.cpufreq_interactive_dir + "/hispeed_freq") == "1000000");
    assert(read_str(paths.gpu_bifrost_dir + "/dvfs_period") == "20");
    assert(read_str(paths.gpu_bifrost_dir + "/power/autosuspend_delay_ms") == "50");
    assert(read_str(paths.gpu_bifrost_dir + "/power/control") == "auto");
    assert(stats.irq_affinities_set == 3);
    assert(read_str(paths.proc_sys_dir + "/vm/page-cluster") == "0");
    assert(read_str(paths.cpuset_dir + "/background/cpus") == "3");
    assert(read_str(paths.proc_irq_dir + "/17/smp_affinity") == "2");
    assert(read_str(paths.proc_irq_dir + "/27/smp_affinity") == "8");
    assert(read_str(paths.proc_irq_dir + "/56/smp_affinity") == "4");

    // Second pass is idempotent -> 0 writes
    stats.kernel_tunables_written = 0;
    stats.irq_affinities_set = 0;
    apply_kernel_and_irq_tuning(paths, hooks, stats);
    assert(stats.kernel_tunables_written == 0);
    assert(stats.irq_affinities_set == 0);

    std::string rm_cmd = "rm -rf " + root;
    (void)std::system(rm_cmd.c_str());
}

void test_android_settings_packages_and_compile_stamp() {
    using namespace openstation::hw;
    const std::string root = mkdtemp_dir("opt_pm");
    OptimizerPaths paths;
    paths.stamp_path = root + "/android_opt_stamp";

    std::map<std::string, std::string> settings = {
        {"secure/autofill_service", "com.google.android.gms/.autofill.service.AutofillService"},
        {"global/window_animation_scale", "1.0"},
        {"global/transition_animation_scale", "1.0"},
        {"global/animator_duration_scale", "1.0"},
        {"secure/screensaver_enabled", "1"},
    };
    bool has_calendar = true;
    bool has_efir = true;
    int bg_dexopt_calls = 0;
    int speed_compile_calls = 0;

    OptimizerHooks hooks;
    hooks.run_cmd = [&](const std::vector<std::string>& args, std::string* out, int) -> int {
        if (out) out->clear();
        if (args.size() >= 4 && args[0] == "/system/bin/settings") {
            const std::string key = args[2] + "/" + args[3];
            if (args[1] == "get") {
                auto it = settings.find(key);
                if (out) *out = (it != settings.end()) ? (it->second + "\n") : "null\n";
                return 0;
            }
            if (args[1] == "put" && args.size() >= 5) {
                settings[key] = args[4];
                return 0;
            }
            if (args[1] == "delete") {
                settings.erase(key);
                return 0;
            }
        }
        if (args.size() >= 4 && args[0] == "/system/bin/pm" && args[1] == "list") {
            std::string list = "package:com.openstation.launcher\n";
            if (has_calendar) list += "package:com.android.providers.calendar\n";
            if (has_efir) list += "package:com.yandex.tv.input.efir\n";
            if (out) *out = list;
            return 0;
        }
        if (args.size() == 5 && args[0] == "/system/bin/pm" && args[1] == "disable-user") {
            if (args[4] == "com.android.providers.calendar") has_calendar = false;
            if (args[4] == "com.yandex.tv.input.efir") has_efir = false;
            return 0;
        }
        if (args.size() >= 3 && args[0] == "/system/bin/cmd" && args[1] == "package") {
            if (args[2] == "compile") {
                ++speed_compile_calls;
                if (out) *out = "Success\n";
                return 0;
            }
            if (args[2] == "bg-dexopt-job") {
                ++bg_dexopt_calls;
                return 0;
            }
        }
        return 0;
    };

    std::atomic<bool> stop_flag{false};
    OptimizerStats st1;
    apply_android_package_and_settings_tuning(paths, hooks, "1.6.3:opt1", stop_flag, st1, 0);
    assert(settings.count("secure/autofill_service") == 0);
    assert(settings["global/animator_duration_scale"] == "0.5");
    assert(settings["secure/screensaver_enabled"] == "0");
    assert(!has_calendar && !has_efir);
    assert(st1.packages_disabled == 2);
    assert(st1.packages_compiled == 6);
    assert(speed_compile_calls == 6);
    assert(st1.bg_dexopt_triggered && bg_dexopt_calls == 1);
    assert(read_str(paths.stamp_path) == "1.6.3:opt1");

    // Re-run with matching stamp skips bg-dexopt-job and skips already-disabled packages
    OptimizerStats st2;
    apply_android_package_and_settings_tuning(paths, hooks, "1.6.3:opt1", stop_flag, st2, 0);
    assert(st2.packages_disabled == 0);
    assert(st2.settings_applied == 0);
    assert(!st2.bg_dexopt_triggered && bg_dexopt_calls == 1);

    std::string rm_cmd = "rm -rf " + root;
    (void)std::system(rm_cmd.c_str());
}

void test_ble_scan_location_ready_and_setting() {
    using namespace openstation::hw;
    std::string mode = "0";
    int put_calls = 0;
    std::vector<std::string> granted_perms;
    std::vector<std::string> allowed_ops;

    OptimizerHooks hooks;
    hooks.run_cmd = [&](const std::vector<std::string>& args, std::string* out, int) -> int {
        if (out) out->clear();
        if (args.size() >= 4 && args[0] == "/system/bin/settings" && args[2] == "secure" && args[3] == "location_mode") {
            if (args[1] == "get") {
                if (out) *out = mode + "\n";
                return 0;
            }
            if (args[1] == "put" && args.size() >= 5) {
                ++put_calls;
                mode = args[4];
                return 0;
            }
        }
        if (args.size() == 4 && args[0] == "/system/bin/pm" && args[1] == "grant" &&
            args[2] == "com.openstation.launcher") {
            granted_perms.push_back(args[3]);
            return 0;
        }
        if (args.size() == 6 && args[0] == "/system/bin/cmd" && args[1] == "appops" &&
            args[2] == "set" && args[3] == "com.openstation.launcher" && args[5] == "allow") {
            allowed_ops.push_back(args[4]);
            return 0;
        }
        return 0;
    };

    ensure_ble_scan_location_ready(hooks);
    assert(mode == "3");
    assert(put_calls == 1);
    assert(granted_perms.size() == 2);
    assert(granted_perms[0] == "android.permission.ACCESS_COARSE_LOCATION");
    assert(granted_perms[1] == "android.permission.ACCESS_FINE_LOCATION");
    assert(allowed_ops.size() == 2);
    assert(allowed_ops[0] == "COARSE_LOCATION");
    assert(allowed_ops[1] == "FINE_LOCATION");

    // Second call sees location_mode == "3" and avoids redundant settings put write.
    ensure_ble_scan_location_ready(hooks);
    assert(put_calls == 1);
}

}  // namespace

int main() {
    test_parsers_and_zram_guard();
    test_crashloop_and_zram_sandbox();
    test_android_settings_packages_and_compile_stamp();
    test_ble_scan_location_ready_and_setting();
    std::printf("android_optimizer_test: all checks passed\n");
    return 0;
}
