#pragma once

#include <atomic>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <set>
#include <sstream>
#include <string>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

namespace openstation::hw {

struct OptimizerPaths {
    std::string zram_dir = "/sys/block/zram0";
    std::string zram_dev = "/dev/block/zram0";
    std::string proc_meminfo = "/proc/meminfo";
    std::string proc_swaps = "/proc/swaps";
    std::string proc_interrupts = "/proc/interrupts";
    std::string proc_irq_dir = "/proc/irq";
    std::string proc_sys_dir = "/proc/sys";
    std::string lmk_minfree = "/sys/module/lowmemorykiller/parameters/minfree";
    std::string mmc_queue_dir = "/sys/block/mmcblk0/queue";
    std::string mmc_boot0_iostats = "/sys/block/mmcblk0boot0/queue/iostats";
    std::string mmc_boot1_iostats = "/sys/block/mmcblk0boot1/queue/iostats";
    std::string cpuset_dir = "/dev/cpuset";
    std::string cpufreq_policy_dir = "/sys/devices/system/cpu/cpu0/cpufreq";
    std::string cpufreq_interactive_dir = "/sys/devices/system/cpu/cpu0/cpufreq/interactive";
    std::string gpu_bifrost_dir = "/sys/devices/platform/ffe40000.bifrost";
    std::string stamp_path = "/data/local/openstation/android_opt_stamp";
};

struct OptimizerHooks {
    std::function<std::string(const std::string& prop)> get_prop;
    std::function<int(const std::vector<std::string>& args, std::string* out, int timeout_ms)> run_cmd;
    std::function<void(const std::string& msg)> log;
    const std::atomic<bool>* abort_flag = nullptr;
};

struct OptimizerStats {
    int services_stopped = 0;
    bool zram_resized = false;
    uint64_t zram_bytes = 0;
    int kernel_tunables_written = 0;
    int irq_affinities_set = 0;
    int packages_disabled = 0;
    int settings_applied = 0;
    int packages_compiled = 0;
    bool bg_dexopt_triggered = false;
};

namespace opt_detail {

inline std::string trim_copy(const std::string& s) {
    size_t start = 0;
    while (start < s.size() && std::isspace(static_cast<unsigned char>(s[start]))) ++start;
    size_t end = s.size();
    while (end > start && std::isspace(static_cast<unsigned char>(s[end - 1]))) --end;
    return s.substr(start, end - start);
}

inline bool read_small_file(const std::string& path, std::string& out, size_t max_bytes = 65536) {
    out.clear();
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[4096];
    while (out.size() < max_bytes) {
        const ssize_t n = ::read(fd, buf, sizeof(buf));
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

inline bool write_text_file(const std::string& path, const std::string& value) {
    const int fd = ::open(path.c_str(), O_WRONLY | O_TRUNC | O_CLOEXEC);
    if (fd < 0) return false;
    const size_t total = value.size();
    size_t written = 0;
    while (written < total) {
        const ssize_t n = ::write(fd, value.data() + written, total - written);
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            return false;
        }
        written += static_cast<size_t>(n);
    }
    ::close(fd);
    return true;
}

inline bool write_text_create(const std::string& path, const std::string& value) {
    const auto slash = path.find_last_of('/');
    if (slash != std::string::npos && slash > 0) {
        const std::string dir = path.substr(0, slash);
        size_t pos = 1;
        while (pos <= dir.size()) {
            const auto next = dir.find('/', pos);
            const std::string sub = (next == std::string::npos) ? dir : dir.substr(0, next);
            ::mkdir(sub.c_str(), 0755);
            if (next == std::string::npos) break;
            pos = next + 1;
        }
    }
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return false;
    const ssize_t n = ::write(fd, value.data(), value.size());
    ::close(fd);
    return n == static_cast<ssize_t>(value.size());
}

inline bool write_if_changed(const std::string& path, const std::string& desired) {
    std::string cur;
    if (read_small_file(path, cur, 4096) && trim_copy(cur) == trim_copy(desired)) {
        return false;
    }
    return write_text_file(path, desired);
}

inline std::string default_get_prop(const std::string& prop) {
#if defined(__ANDROID__)
    char buf[PROP_VALUE_MAX] = {0};
    if (__system_property_get(prop.c_str(), buf) > 0) {
        return trim_copy(buf);
    }
#else
    (void)prop;
#endif
    return "";
}

inline int default_run_cmd_cancellable(const std::vector<std::string>& args,
                                       std::string* out,
                                       int timeout_ms,
                                       const std::atomic<bool>* abort_flag) {
    if (out) out->clear();
    if (args.empty()) return -1;
    if (abort_flag && abort_flag->load()) return -1;

    int pipefd[2] = {-1, -1};
    if (out && ::pipe(pipefd) != 0) {
        pipefd[0] = pipefd[1] = -1;
    }

    const pid_t pid = ::fork();
    if (pid < 0) {
        if (pipefd[0] >= 0) ::close(pipefd[0]);
        if (pipefd[1] >= 0) ::close(pipefd[1]);
        return -1;
    }
    if (pid == 0) {
        const int devnull = ::open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            ::dup2(devnull, STDIN_FILENO);
            if (pipefd[1] < 0) ::dup2(devnull, STDOUT_FILENO);
            ::dup2(devnull, STDERR_FILENO);
            if (devnull > STDERR_FILENO) ::close(devnull);
        }
        if (pipefd[1] >= 0) {
            ::dup2(pipefd[1], STDOUT_FILENO);
            ::close(pipefd[0]);
            ::close(pipefd[1]);
        }
        std::vector<char*> argv;
        argv.reserve(args.size() + 1);
        for (const auto& s : args) argv.push_back(const_cast<char*>(s.c_str()));
        argv.push_back(nullptr);
        ::execv(args[0].c_str(), argv.data());
        ::_exit(127);
    }

    if (pipefd[1] >= 0) ::close(pipefd[1]);
    if (pipefd[0] >= 0) {
        const int fl = ::fcntl(pipefd[0], F_GETFL, 0);
        if (fl >= 0) ::fcntl(pipefd[0], F_SETFL, fl | O_NONBLOCK);
    }

    const auto start = std::chrono::steady_clock::now();
    int status = 0;
    bool exited = false;
    while (true) {
        if (pipefd[0] >= 0) {
            char buf[1024];
            while (true) {
                const ssize_t n = ::read(pipefd[0], buf, sizeof(buf));
                if (n > 0) {
                    if (out && out->size() < 262144) out->append(buf, static_cast<size_t>(n));
                    continue;
                }
                break;
            }
        }
        const pid_t w = ::waitpid(pid, &status, WNOHANG);
        if (w == pid) {
            exited = true;
            break;
        }
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
        if ((abort_flag && abort_flag->load()) || (timeout_ms > 0 && elapsed >= timeout_ms)) {
            ::kill(pid, SIGKILL);
            ::waitpid(pid, &status, 0);
            break;
        }
        ::usleep(20000);
    }

    if (pipefd[0] >= 0) {
        char buf[1024];
        while (true) {
            const ssize_t n = ::read(pipefd[0], buf, sizeof(buf));
            if (n > 0) {
                if (out && out->size() < 262144) out->append(buf, static_cast<size_t>(n));
                continue;
            }
            break;
        }
        ::close(pipefd[0]);
    }
    if (!exited) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -1;
}

inline OptimizerHooks resolve_hooks(const OptimizerHooks& in) {
    OptimizerHooks h = in;
    if (!h.get_prop) h.get_prop = default_get_prop;
    if (!h.run_cmd) {
        const std::atomic<bool>* af = h.abort_flag;
        h.run_cmd = [af](const std::vector<std::string>& args, std::string* out, int timeout_ms) {
            return default_run_cmd_cancellable(args, out, timeout_ms, af);
        };
    }
    if (!h.log) {
        h.log = [](const std::string&) {};
    }
    return h;
}

inline bool interruptible_sleep_ms(int ms, const std::atomic<bool>& stop_flag) {
    int left = ms;
    while (left > 0) {
        if (stop_flag.load()) return false;
        const int slice = left > 150 ? 150 : left;
        std::this_thread::sleep_for(std::chrono::milliseconds(slice));
        left -= slice;
    }
    return !stop_flag.load();
}

}  // namespace opt_detail

inline int64_t parse_proc_meminfo_kb(const std::string& content, const std::string& key) {
    std::istringstream iss(content);
    std::string line;
    const std::string prefix = key + ":";
    while (std::getline(iss, line)) {
        if (line.compare(0, prefix.size(), prefix) != 0) continue;
        size_t pos = prefix.size();
        while (pos < line.size() && !std::isdigit(static_cast<unsigned char>(line[pos]))) ++pos;
        if (pos >= line.size()) return -1;
        return std::strtoll(line.c_str() + pos, nullptr, 10);
    }
    return -1;
}

inline int64_t parse_zram_swap_used_kb(const std::string& swaps_content,
                                       const std::string& dev_prefix = "/dev/block/zram0") {
    std::istringstream iss(swaps_content);
    std::string line;
    while (std::getline(iss, line)) {
        const auto trimmed = opt_detail::trim_copy(line);
        if (trimmed.compare(0, dev_prefix.size(), dev_prefix) != 0) continue;
        std::istringstream row(trimmed);
        std::string fname, type;
        int64_t size_kb = 0, used_kb = 0;
        if (row >> fname >> type >> size_kb >> used_kb) {
            return used_kb < 0 ? 0 : used_kb;
        }
    }
    return -1;
}

inline bool should_expand_zram(uint64_t current_disksize,
                               uint64_t target_disksize,
                               int64_t mem_available_kb,
                               int64_t swap_used_kb,
                               uint64_t min_free_margin_kb = 131072ULL) {
    if (current_disksize >= target_disksize) return false;
    if (swap_used_kb <= 0) return true;
    if (mem_available_kb < 0) return false;
    return mem_available_kb >= swap_used_kb + static_cast<int64_t>(min_free_margin_kb);
}

inline std::vector<int> find_gic_irqs_matching(const std::string& interrupts_content,
                                               const std::vector<std::string>& name_patterns) {
    std::vector<int> result;
    std::istringstream iss(interrupts_content);
    std::string line;
    while (std::getline(iss, line)) {
        if (line.find("PCI-MSI") != std::string::npos) continue;
        size_t pos = 0;
        while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos]))) ++pos;
        if (pos >= line.size() || !std::isdigit(static_cast<unsigned char>(line[pos]))) continue;
        size_t end_num = pos;
        while (end_num < line.size() && std::isdigit(static_cast<unsigned char>(line[end_num]))) ++end_num;
        if (end_num >= line.size() || line[end_num] != ':') continue;
        const int irq = std::atoi(line.substr(pos, end_num - pos).c_str());
        if (irq <= 0) continue;
        for (const auto& pat : name_patterns) {
            if (!pat.empty() && line.find(pat) != std::string::npos) {
                result.push_back(irq);
                break;
            }
        }
    }
    return result;
}

inline void stop_crashloop_services(const OptimizerHooks& raw_hooks, OptimizerStats& stats) {
    const auto hooks = opt_detail::resolve_hooks(raw_hooks);
    static constexpr const char* kCrashServices[] = {"logd", "direct_keymaster"};
    for (const char* svc : kCrashServices) {
        const std::string st = hooks.get_prop(std::string("init.svc.") + svc);
        if (st == "restarting") {
            if (hooks.run_cmd({"/system/bin/stop", svc}, nullptr, 3000) == 0) {
                ++stats.services_stopped;
                hooks.log(std::string("Stopped crash-looping service: ") + svc);
            }
        }
    }
}

inline void expand_zram_if_needed(const OptimizerPaths& paths,
                                  const OptimizerHooks& raw_hooks,
                                  OptimizerStats& stats,
                                  uint64_t target_bytes = 671088640ULL) {
    const auto hooks = opt_detail::resolve_hooks(raw_hooks);
    std::string disksize_str;
    if (!opt_detail::read_small_file(paths.zram_dir + "/disksize", disksize_str, 128)) {
        return;
    }
    const uint64_t cur_bytes = std::strtoull(opt_detail::trim_copy(disksize_str).c_str(), nullptr, 10);
    stats.zram_bytes = cur_bytes;
    if (cur_bytes >= target_bytes) return;

    std::string swaps_str;
    opt_detail::read_small_file(paths.proc_swaps, swaps_str, 8192);
    const int64_t swap_used_kb = parse_zram_swap_used_kb(swaps_str, paths.zram_dev);

    std::string meminfo_str;
    opt_detail::read_small_file(paths.proc_meminfo, meminfo_str, 16384);
    const int64_t mem_avail_kb = parse_proc_meminfo_kb(meminfo_str, "MemAvailable");

    if (!should_expand_zram(cur_bytes, target_bytes, mem_avail_kb, swap_used_kb)) {
        hooks.log("Skipped ZRAM resize due to insufficient MemAvailable headroom");
        return;
    }

    if (swap_used_kb >= 0) {
        if (hooks.run_cmd({"/system/bin/swapoff", paths.zram_dev}, nullptr, 10000) != 0) {
            hooks.log("ZRAM swapoff failed; keeping current ZRAM size");
            return;
        }
    }

    if (!opt_detail::write_text_file(paths.zram_dir + "/reset", "1")) {
        hooks.log("Failed to reset zram0; re-enabling swap");
        hooks.run_cmd({"/system/bin/swapon", paths.zram_dev}, nullptr, 5000);
        return;
    }

    opt_detail::write_text_file(paths.zram_dir + "/comp_algorithm", "lzo");
    opt_detail::write_text_file(paths.zram_dir + "/max_comp_streams", "4");

    if (!opt_detail::write_text_file(paths.zram_dir + "/disksize", std::to_string(target_bytes))) {
        opt_detail::write_text_file(paths.zram_dir + "/disksize", std::to_string(cur_bytes));
    }

    if (hooks.run_cmd({"/system/bin/mkswap", paths.zram_dev}, nullptr, 5000) == 0 &&
        hooks.run_cmd({"/system/bin/swapon", paths.zram_dev}, nullptr, 5000) == 0) {
        std::string new_sz;
        if (opt_detail::read_small_file(paths.zram_dir + "/disksize", new_sz, 128)) {
            stats.zram_bytes = std::strtoull(opt_detail::trim_copy(new_sz).c_str(), nullptr, 10);
        }
        stats.zram_resized = (stats.zram_bytes >= target_bytes);
        hooks.log("Expanded zram0 disksize to " + std::to_string(stats.zram_bytes / (1024ULL * 1024ULL)) + " MB");
    }
}

inline void apply_kernel_and_irq_tuning(const OptimizerPaths& paths,
                                        const OptimizerHooks& raw_hooks,
                                        OptimizerStats& stats) {
    const auto hooks = opt_detail::resolve_hooks(raw_hooks);

    struct Tunable {
        std::string path;
        std::string value;
    };

    const std::vector<Tunable> tunables = {
        {paths.proc_sys_dir + "/vm/page-cluster", "0"},
        {paths.proc_sys_dir + "/vm/swappiness", "85"},
        {paths.proc_sys_dir + "/vm/vfs_cache_pressure", "75"},
        {paths.proc_sys_dir + "/vm/dirty_background_ratio", "5"},
        {paths.proc_sys_dir + "/vm/dirty_ratio", "15"},
        {paths.proc_sys_dir + "/kernel/sched_schedstats", "0"},
        {paths.lmk_minfree, "6144,8192,12288,16384,20480,24576"},
        {paths.mmc_queue_dir + "/iostats", "0"},
        {paths.mmc_queue_dir + "/rq_affinity", "2"},
        {paths.mmc_queue_dir + "/read_ahead_kb", "128"},
        {paths.mmc_boot0_iostats, "0"},
        {paths.mmc_boot1_iostats, "0"},
        {paths.cpuset_dir + "/system-background/cpus", "2-3"},
        {paths.cpuset_dir + "/background/cpus", "3"},
        {paths.cpuset_dir + "/restricted/cpus", "3"},
        // Stock S905X2 kernel 4.9.113 locks static OPP voltage-table UV (# CONFIG_DEVMEM is not set,
        // read-only regulator.4/microvolts, pwm-regulator exclusively owns pwm1). Switching to schedutil
        // (left intact by vendor PowerHAL unlike interactive) with fast down-scaling keeps idle residency
        // at 100-1000 MHz (731 mV) instead of pinning 1800 MHz (981 mV), while cooler interactive values
        // remain as fallback if schedutil sysfs is absent.
        {paths.cpufreq_policy_dir + "/scaling_governor", "schedutil"},
        {paths.cpufreq_policy_dir + "/schedutil/up_rate_limit_us", "1000"},
        {paths.cpufreq_policy_dir + "/schedutil/down_rate_limit_us", "15000"},
        {paths.cpufreq_interactive_dir + "/go_hispeed_load", "88"},
        {paths.cpufreq_interactive_dir + "/hispeed_freq", "1000000"},
        {paths.cpufreq_interactive_dir + "/io_is_busy", "0"},
        {paths.cpufreq_interactive_dir + "/timer_rate", "30000"},
        {paths.cpufreq_interactive_dir + "/min_sample_time", "30000"},
        {paths.cpufreq_interactive_dir + "/above_hispeed_delay", "25000 1398000:50000"},
        {paths.cpufreq_interactive_dir + "/target_loads", "75 1000000:85 1398000:92"},
        // Mali-G31 Bifrost: halve DVFS window & autosuspend delay so GPU drops clocks rapidly at rest
        {paths.gpu_bifrost_dir + "/dvfs_period", "20"},
        {paths.gpu_bifrost_dir + "/power/autosuspend_delay_ms", "50"},
        {paths.gpu_bifrost_dir + "/power/control", "auto"},
    };

    for (const auto& t : tunables) {
        if (opt_detail::write_if_changed(t.path, t.value)) {
            ++stats.kernel_tunables_written;
        }
    }

    std::string interrupts_str;
    if (!opt_detail::read_small_file(paths.proc_interrupts, interrupts_str, 65536)) {
        return;
    }

    struct IrqRule {
        std::vector<std::string> patterns;
        const char* cpu_mask;
    };
    const std::vector<IrqRule> rules = {
        {{"ffe40000.bifrost", "osd-vsync", "rdma"}, "2"},
        {{"meson-aml-mmc"}, "4"},
        {{"ffd1f000.i2c", "ffd15000.spi"}, "8"},
    };

    for (const auto& rule : rules) {
        const auto irqs = find_gic_irqs_matching(interrupts_str, rule.patterns);
        for (int irq : irqs) {
            const std::string aff_path = paths.proc_irq_dir + "/" + std::to_string(irq) + "/smp_affinity";
            if (opt_detail::write_if_changed(aff_path, rule.cpu_mask)) {
                ++stats.irq_affinities_set;
            }
        }
    }

    if (stats.kernel_tunables_written > 0 || stats.irq_affinities_set > 0) {
        hooks.log("Kernel tuning applied: tunables=" + std::to_string(stats.kernel_tunables_written) +
                  " irqs=" + std::to_string(stats.irq_affinities_set));
    }
}

inline void ensure_ble_scan_location_ready(const OptimizerHooks& raw_hooks = OptimizerHooks{},
                                           const std::atomic<bool>* stop_flag = nullptr) {
    const auto hooks = opt_detail::resolve_hooks(raw_hooks);
    auto stopped = [&]() { return stop_flag && stop_flag->load(); };
    if (stopped()) return;

    std::string cur_mode;
    if (hooks.run_cmd({"/system/bin/settings", "get", "secure", "location_mode"}, &cur_mode, 2500) != 0 ||
        opt_detail::trim_copy(cur_mode) != "3") {
        hooks.run_cmd({"/system/bin/settings", "put", "secure", "location_mode", "3"}, nullptr, 2500);
    }
    if (stopped()) return;

    static constexpr const char* kLocationPerms[] = {
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
    };
    for (const char* perm : kLocationPerms) {
        if (stopped()) return;
        hooks.run_cmd({"/system/bin/pm", "grant", "com.openstation.launcher", perm}, nullptr, 3000);
    }

    static constexpr const char* kLocationOps[] = {
        "COARSE_LOCATION",
        "FINE_LOCATION",
    };
    for (const char* op : kLocationOps) {
        if (stopped()) return;
        hooks.run_cmd({"/system/bin/cmd", "appops", "set", "com.openstation.launcher", op, "allow"}, nullptr, 2500);
    }
}

inline void apply_android_package_and_settings_tuning(const OptimizerPaths& paths,
                                                      const OptimizerHooks& raw_hooks,
                                                      const std::string& firmware_version,
                                                      const std::atomic<bool>& stop_flag,
                                                      OptimizerStats& stats,
                                                      int compile_delay_ms = 6000) {
    const auto hooks = opt_detail::resolve_hooks(raw_hooks);
    if (stop_flag.load()) return;

    // 1. Safe system settings (skip redundant writes where quick checks show desired state)
    std::string cur_autofill;
    if (hooks.run_cmd({"/system/bin/settings", "get", "secure", "autofill_service"}, &cur_autofill, 3000) == 0) {
        if (cur_autofill.find("com.google.android.gms") != std::string::npos) {
            if (hooks.run_cmd({"/system/bin/settings", "delete", "secure", "autofill_service"}, nullptr, 3000) == 0) {
                ++stats.settings_applied;
            }
        }
    }
    if (stop_flag.load()) return;

    struct SettingKv {
        const char* ns;
        const char* key;
        const char* val;
    };
    static constexpr SettingKv kSettings[] = {
        {"global", "window_animation_scale", "0.5"},
        {"global", "transition_animation_scale", "0.5"},
        {"global", "animator_duration_scale", "0.5"},
        {"global", "package_verifier_enable", "0"},
        {"global", "verifier_verify_adb_installs", "0"},
        {"global", "wifi_scan_always_enabled", "0"},
        {"global", "wifi_networks_available_notification_on", "0"},
        {"secure", "location_mode", "3"},
        {"secure", "screensaver_enabled", "0"},
        {"secure", "screensaver_activate_on_dock", "0"},
        {"secure", "screensaver_activate_on_sleep", "0"},
    };
    for (const auto& s : kSettings) {
        if (stop_flag.load()) return;
        std::string cur_val;
        if (hooks.run_cmd({"/system/bin/settings", "get", s.ns, s.key}, &cur_val, 2500) == 0 &&
            opt_detail::trim_copy(cur_val) == s.val) {
            continue;
        }
        if (hooks.run_cmd({"/system/bin/settings", "put", s.ns, s.key, s.val}, nullptr, 3000) == 0) {
            ++stats.settings_applied;
        }
    }

    // 2. Disable safe unused bloat packages only if currently enabled
    std::string enabled_out;
    if (hooks.run_cmd({"/system/bin/pm", "list", "packages", "-e"}, &enabled_out, 5000) == 0) {
        std::set<std::string> enabled_pkgs;
        std::istringstream iss(enabled_out);
        std::string line;
        while (std::getline(iss, line)) {
            const auto t = opt_detail::trim_copy(line);
            static constexpr const char* kPrefix = "package:";
            if (t.compare(0, 8, kPrefix) == 0) {
                enabled_pkgs.insert(t.substr(8));
            }
        }

        static constexpr const char* kSafeDisablePkgs[] = {
            "com.android.se",
            "com.android.providers.calendar",
            "com.android.onetimeinitializer",
            "com.android.smspush",
            "com.android.statementservice",
            "com.android.wallpaperbackup",
            "com.android.backupconfirm",
            "com.android.sharedstoragebackup",
            "com.droidlogic.mediacenter",
            "com.droidlogic.droidlivetv",
            "com.droidlogic.SubTitleService",
            "com.yandex.tv.input.efir",
        };
        for (const char* pkg : kSafeDisablePkgs) {
            if (stop_flag.load()) return;
            if (enabled_pkgs.count(pkg) == 0) continue;
            if (hooks.run_cmd({"/system/bin/pm", "disable-user", "--user", "0", pkg}, nullptr, 4000) == 0) {
                ++stats.packages_disabled;
                hooks.run_cmd({"/system/bin/am", "force-stop", pkg}, nullptr, 3000);
            }
        }
    }

    ensure_ble_scan_location_ready(raw_hooks, &stop_flag);

    if (stats.settings_applied > 0 || stats.packages_disabled > 0) {
        hooks.log("Android PM/Settings tuning: settings=" + std::to_string(stats.settings_applied) +
                  " disabled=" + std::to_string(stats.packages_disabled));
    }

    // 3. Give Home/UI breathing room before background AOT dexopt
    if (compile_delay_ms > 0 && !opt_detail::interruptible_sleep_ms(compile_delay_ms, stop_flag)) return;

    static constexpr const char* kCompilePkgs[] = {
        "com.openstation.launcher",
        "com.openstation.ime",
        "com.android.systemui",
        "com.android.tv.settings",
        "com.yandex.tv.settings",
        "com.android.webview",
    };
    for (const char* pkg : kCompilePkgs) {
        if (stop_flag.load()) return;
        std::string comp_out;
        if (hooks.run_cmd({"/system/bin/cmd", "package", "compile", "-m", "speed", pkg}, &comp_out, 45000) == 0 &&
            comp_out.find("Success") != std::string::npos) {
            ++stats.packages_compiled;
        }
    }

    std::string stamp;
    const bool stamp_matches =
        opt_detail::read_small_file(paths.stamp_path, stamp, 256) &&
        opt_detail::trim_copy(stamp) == opt_detail::trim_copy(firmware_version);
    if (!stamp_matches && !stop_flag.load()) {
        hooks.run_cmd({"/system/bin/cmd", "package", "bg-dexopt-job"}, nullptr, 120000);
        opt_detail::write_text_create(paths.stamp_path, firmware_version + "\n");
        stats.bg_dexopt_triggered = true;
    }

    if (stats.packages_compiled > 0 || stats.bg_dexopt_triggered) {
        hooks.log("ART dexopt complete: speed_pkgs=" + std::to_string(stats.packages_compiled) +
                  " bg_dexopt=" + (stats.bg_dexopt_triggered ? "1" : "0"));
    }
}

class AndroidOptimizer {
public:
    AndroidOptimizer() = default;
    ~AndroidOptimizer() { stop(); }

    AndroidOptimizer(const AndroidOptimizer&) = delete;
    AndroidOptimizer& operator=(const AndroidOptimizer&) = delete;

    void start(std::string firmware_version,
               OptimizerPaths paths = OptimizerPaths{},
               OptimizerHooks hooks = OptimizerHooks{}) {
        stop();
        stop_.store(false);
        if (!hooks.abort_flag) hooks.abort_flag = &stop_;
        worker_ = std::thread([this, fw = std::move(firmware_version), paths, raw_hooks = hooks]() {
            ::setpriority(PRIO_PROCESS, 0, 10);
            const auto resolved = opt_detail::resolve_hooks(raw_hooks);
            OptimizerStats stats;

            stop_crashloop_services(resolved, stats);
            if (stop_.load()) return;

            expand_zram_if_needed(paths, resolved, stats);
            if (stop_.load()) return;

            apply_kernel_and_irq_tuning(paths, resolved, stats);
            if (stop_.load()) return;

            // Wait up to 45s for Android sys.boot_completed and PackageManager
            for (int i = 0; i < 45 && !stop_.load(); ++i) {
                if (resolved.get_prop("sys.boot_completed") == "1") {
                    std::string chk;
                    if (resolved.run_cmd({"/system/bin/cmd", "package", "list", "packages", "android"}, &chk, 2500) == 0 &&
                        chk.find("package:android") != std::string::npos) {
                        break;
                    }
                }
                if (!opt_detail::interruptible_sleep_ms(1000, stop_)) return;
            }
            if (stop_.load()) return;

            // Catch services init may have re-triggered during late boot
            stop_crashloop_services(resolved, stats);
            apply_android_package_and_settings_tuning(paths, resolved, fw, stop_, stats);
        });
    }

    void stop() {
        stop_.store(true);
        if (worker_.joinable()) {
            worker_.join();
        }
    }

private:
    std::atomic<bool> stop_{false};
    std::thread worker_;
};

}  // namespace openstation::hw
