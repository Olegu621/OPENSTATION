#include "net/adb_manager.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace openstation {

namespace {

constexpr const char* kPauseGuardPath = "/data/local/openstation/pause_usb_guard";
constexpr const char* kDisabledMarkerPath = "/data/local/openstation/wifi_adb_disabled";
constexpr time_t kStaleGuardAgeSec = 180;

struct CmdResult {
    int exit_code = -1;
    bool timed_out = false;
    std::string output;
};

std::string trim_copy(const std::string& s) {
    size_t b = 0;
    while (b < s.size() && (s[b] == ' ' || s[b] == '\t' || s[b] == '\r' || s[b] == '\n')) ++b;
    size_t e = s.size();
    while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t' || s[e - 1] == '\r' || s[e - 1] == '\n')) --e;
    return s.substr(b, e - b);
}

std::string read_small_file(const char* path, size_t max_len = 256) {
    FILE* f = fopen(path, "r");
    if (!f) return "";
    std::string out(max_len, '\0');
    size_t n = fread(&out[0], 1, max_len, f);
    fclose(f);
    out.resize(n);
    return trim_copy(out);
}

bool write_small_file(const char* path, const char* data) {
    int fd = open(path, O_WRONLY | O_NONBLOCK);
    if (fd < 0) return false;
    size_t len = strlen(data);
    ssize_t w = write(fd, data, len);
    ::close(fd);
    return w == static_cast<ssize_t>(len);
}

CmdResult run_sh_timeout(const std::string& script, int timeout_sec, size_t max_bytes = 4096) {
    CmdResult res;
    int pipefd[2] = {-1, -1};
    if (pipe(pipefd) != 0) {
        res.output = strerror(errno);
        return res;
    }

    pid_t pid = fork();
    if (pid < 0) {
        res.output = strerror(errno);
        ::close(pipefd[0]);
        ::close(pipefd[1]);
        return res;
    }

    if (pid == 0) {
        setpgid(0, 0);
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            if (devnull > 2) ::close(devnull);
        }
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        ::close(pipefd[0]);
        ::close(pipefd[1]);
        for (int fd = 3; fd < 256; ++fd) ::close(fd);
        execl("/system/bin/sh", "sh", "-c", script.c_str(), nullptr);
        _exit(127);
    }

    ::close(pipefd[1]);
    int fl = fcntl(pipefd[0], F_GETFL, 0);
    if (fl >= 0) fcntl(pipefd[0], F_SETFL, fl | O_NONBLOCK);

    int tsec = std::clamp(timeout_sec, 1, 30);
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(tsec);
    char buf[1024];
    std::string raw;
    raw.reserve(1024);

    while (true) {
        auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                        deadline - std::chrono::steady_clock::now())
                        .count();
        if (left <= 0) {
            res.timed_out = true;
            break;
        }
        struct pollfd pfd {};
        pfd.fd = pipefd[0];
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, static_cast<int>(std::min<int64_t>(left, 150)));
        if (pr > 0) {
            ssize_t n = read(pipefd[0], buf, sizeof(buf));
            if (n > 0) {
                if (raw.size() < max_bytes) {
                    size_t take = std::min(static_cast<size_t>(n), max_bytes - raw.size());
                    raw.append(buf, take);
                }
                continue;
            }
            if (n == 0) break;
            if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) break;
        } else if (pr < 0 && errno != EINTR) {
            break;
        }
    }

    ::close(pipefd[0]);

    int st = 0;
    pid_t wp = waitpid(pid, &st, res.timed_out ? WNOHANG : 0);
    if (wp == 0) {
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
        while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
        res.timed_out = true;
    } else if (wp > 0) {
        if (WIFEXITED(st)) res.exit_code = WEXITSTATUS(st);
        else if (WIFSIGNALED(st)) res.exit_code = 128 + WTERMSIG(st);
    }

    res.output = trim_copy(raw);
    return res;
}

std::string sanitize_mdns_host(const std::string& raw) {
    std::string out;
    for (unsigned char c : raw) {
        if (std::isalnum(c) || c == '-') {
            out.push_back(static_cast<char>(std::tolower(c)));
        } else if (c == ' ' || c == '_') {
            out.push_back('-');
        }
    }
    return out.empty() ? std::string("station-max") : out;
}

struct PauseGuardScope {
    bool owns_guard = false;
    PauseGuardScope() {
        struct stat sb {};
        if (stat(kPauseGuardPath, &sb) != 0) {
            int fd = open(kPauseGuardPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (fd >= 0) {
                ::close(fd);
                owns_guard = true;
            }
        }
    }
    ~PauseGuardScope() {
        if (owns_guard) {
            unlink(kPauseGuardPath);
        }
    }
};

} // namespace

AdbManager::~AdbManager() {
    stop();
}

std::string AdbManager::get_sys_prop(const char* name) {
    char val[PROP_VALUE_MAX + 1] = {0};
    int len = __system_property_get(name, val);
    if (len <= 0) return "";
    val[std::min(len, PROP_VALUE_MAX)] = '\0';
    return trim_copy(std::string(val));
}

bool AdbManager::set_sys_prop(const char* name, const char* value) {
    std::string cur = get_sys_prop(name);
    std::string want = value ? std::string(value) : "";
    if (cur == want) return true;

    pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) {
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            if (devnull > 2) ::close(devnull);
        }
        for (int fd = 3; fd < 256; ++fd) ::close(fd);
        const char* argv[] = {"/system/bin/setprop", name, want.c_str(), nullptr};
        execv("/system/bin/setprop", const_cast<char* const*>(argv));
        _exit(127);
    }

    int st = 0;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (true) {
        pid_t wp = waitpid(pid, &st, WNOHANG);
        if (wp == pid) {
            return WIFEXITED(st) && WEXITSTATUS(st) == 0;
        }
        if (wp < 0 && errno != EINTR) return false;
        if (std::chrono::steady_clock::now() >= deadline) {
            kill(pid, SIGKILL);
            while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
            return false;
        }
        usleep(15000);
    }
}

bool AdbManager::is_tcp_port_listening_proc(const char* proc_path, int port) {
    if (port <= 0 || port > 65535) return false;
    FILE* f = fopen(proc_path, "r");
    if (!f) return false;

    char line[512];
    bool first = true;
    while (fgets(line, sizeof(line), f)) {
        if (first) {
            first = false;
            continue;
        }
        const char* p = line;
        // Skip leading whitespace -> col 0 (sl)
        while (*p && std::isspace(static_cast<unsigned char>(*p))) ++p;
        while (*p && !std::isspace(static_cast<unsigned char>(*p))) ++p;
        // col 1 (local_address = HEX_IP:HEX_PORT)
        while (*p && std::isspace(static_cast<unsigned char>(*p))) ++p;
        const char* local_start = p;
        while (*p && !std::isspace(static_cast<unsigned char>(*p))) ++p;
        const char* local_end = p;
        const char* colon = nullptr;
        for (const char* c = local_start; c < local_end; ++c) {
            if (*c == ':') colon = c;
        }
        if (!colon || colon + 1 >= local_end) continue;
        char* hex_end = nullptr;
        unsigned long parsed_port = std::strtoul(colon + 1, &hex_end, 16);
        if (hex_end != local_end || parsed_port != static_cast<unsigned long>(port)) {
            continue;
        }
        // col 2 (rem_address)
        while (*p && std::isspace(static_cast<unsigned char>(*p))) ++p;
        while (*p && !std::isspace(static_cast<unsigned char>(*p))) ++p;
        // col 3 (st) -> "0A" == TCP_LISTEN
        while (*p && std::isspace(static_cast<unsigned char>(*p))) ++p;
        const char* st_start = p;
        while (*p && !std::isspace(static_cast<unsigned char>(*p))) ++p;
        if (p - st_start == 2 && st_start[0] == '0' &&
            (st_start[1] == 'A' || st_start[1] == 'a')) {
            fclose(f);
            return true;
        }
    }
    fclose(f);
    return false;
}

bool AdbManager::check_usb_gadget_ok(std::string* out_id_product, bool* out_f1_ffs) {
    std::string id_prod = read_small_file("/config/usb_gadget/g1/idProduct");
    if (out_id_product) *out_id_product = id_prod;

    char f1_buf[256] = {0};
    ssize_t rlen = readlink("/config/usb_gadget/g1/configs/b.1/f1", f1_buf, sizeof(f1_buf) - 1);
    std::string f1 = (rlen > 0) ? trim_copy(std::string(f1_buf, static_cast<size_t>(rlen))) : "";
    bool f1_ok = (f1.size() >= 7 && f1.compare(f1.size() - 7, 7, "ffs.adb") == 0);
    if (out_f1_ffs) *out_f1_ffs = f1_ok;

    struct stat sb {};
    bool f2_absent = (lstat("/config/usb_gadget/g1/configs/b.1/f2", &sb) != 0);
    std::string udc = read_small_file("/config/usb_gadget/g1/UDC");
    bool udc_ok = (!udc.empty() && udc != "none");

    std::string p_usb = get_sys_prop("persist.sys.usb.config");
    std::string s_usb = get_sys_prop("sys.usb.config");
    std::string st_usb = get_sys_prop("sys.usb.state");

    return (p_usb == "adb" && s_usb == "adb" && st_usb == "adb" &&
            id_prod == "0x4e40" && f1_ok && f2_absent && udc_ok);
}

std::string AdbManager::find_adbd_pid_str() {
    DIR* dir = opendir("/proc");
    if (!dir) return "";
    struct dirent* ent = nullptr;
    std::string found;
    while ((ent = readdir(dir)) != nullptr) {
        if (!std::isdigit(static_cast<unsigned char>(ent->d_name[0]))) continue;
        bool all_digits = true;
        for (const char* c = ent->d_name; *c; ++c) {
            if (!std::isdigit(static_cast<unsigned char>(*c))) {
                all_digits = false;
                break;
            }
        }
        if (!all_digits) continue;
        char path[96];
        snprintf(path, sizeof(path), "/proc/%s/comm", ent->d_name);
        if (read_small_file(path, 64) == "adbd") {
            found = ent->d_name;
            break;
        }
    }
    closedir(dir);
    return found;
}

void AdbManager::escape_self_from_adbd_cgroup() {
    std::string adbd_pid = find_adbd_pid_str();
    if (adbd_pid.empty()) return;

    std::string self_cg = read_small_file("/proc/self/cgroup", 1024);
    std::string needle = "/uid_0/pid_" + adbd_pid;
    if (self_cg.find(needle) == std::string::npos) {
        return; // Already outside adbd's cgroup
    }

    std::string pid_line = std::to_string(static_cast<long>(getpid())) + "\n";
    if (!write_small_file("/acct/uid_0/cgroup.procs", pid_line.c_str())) {
        write_small_file("/acct/cgroup.procs", pid_line.c_str());
    }
    LOGI("WiFiADB", "Moved openstationd (pid=%ld) out of adbd cgroup (%s) before restarting adbd",
         static_cast<long>(getpid()), needle.c_str());
}

double AdbManager::read_os_uptime_sec() {
    std::string s = read_small_file("/proc/uptime", 64);
    if (s.empty()) return 0.0;
    return std::atof(s.c_str());
}

void AdbManager::sync_disabled_marker(bool enabled) const {
    if (enabled) {
        unlink(kDisabledMarkerPath);
    } else {
        int fd = open(kDisabledMarkerPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) ::close(fd);
    }
}

bool AdbManager::pause_guard_active() const {
    struct stat sb {};
    if (stat(kPauseGuardPath, &sb) != 0) return false;
    time_t now_t = time(nullptr);
    if (now_t > sb.st_mtime && (now_t - sb.st_mtime) > kStaleGuardAgeSec) {
        LOGW("WiFiADB", "Removing stale pause_usb_guard (age=%lds)",
             static_cast<long>(now_t - sb.st_mtime));
        unlink(kPauseGuardPath);
        return false;
    }
    return true;
}

void AdbManager::poll_kernel_and_props(AdbLiveStatus& st, int listen_port) const {
    st.port = listen_port;
    st.listening_v6 = is_tcp_port_listening_proc("/proc/net/tcp6", listen_port);
    st.listening_v4 = is_tcp_port_listening_proc("/proc/net/tcp", listen_port);
    st.listening = st.listening_v6 || st.listening_v4;

    st.adbd_running = (get_sys_prop("init.svc.adbd") == "running");
    st.service_port = get_sys_prop("service.adb.tcp.port");
    st.persist_port = get_sys_prop("persist.adb.tcp.port");
    st.persist_usb_config = get_sys_prop("persist.sys.usb.config");
    st.sys_usb_config = get_sys_prop("sys.usb.config");
    st.sys_usb_state = get_sys_prop("sys.usb.state");
    st.boot_completed = (get_sys_prop("sys.boot_completed") == "1");
    st.usb_gadget_ok = check_usb_gadget_ok();
}

void AdbManager::check_and_fix_wifi_power_save(AdbLiveStatus& st, bool apply_fix) {
    if (access("/system/bin/iw", X_OK) != 0) {
        st.wifi_power_save_state = "unsupported";
        return;
    }
    CmdResult r = run_sh_timeout("/system/bin/iw dev wlan0 get power_save 2>&1", 2, 256);
    if (r.exit_code != 0 || r.output.empty()) {
        // wlan0 might be initializing at early boot
        return;
    }
    std::string low = r.output;
    for (char& c : low) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));

    if (low.find(": on") != std::string::npos) {
        st.wifi_power_save_state = "on";
        if (apply_fix) {
            CmdResult fx = run_sh_timeout("/system/bin/iw dev wlan0 set power_save off 2>&1", 2, 256);
            CmdResult r2 = run_sh_timeout("/system/bin/iw dev wlan0 get power_save 2>&1", 2, 256);
            std::string low2 = r2.output;
            for (char& c : low2) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            if (low2.find(": off") != std::string::npos) {
                st.wifi_power_save_state = "off";
                LOGI("WiFiADB", "Disabled wlan0 power_save (was on -> off)");
            } else {
                LOGW("WiFiADB", "Failed to turn off wlan0 power_save (rc=%d, out=%s)",
                     fx.exit_code, fx.output.c_str());
            }
        }
    } else if (low.find(": off") != std::string::npos) {
        st.wifi_power_save_state = "off";
    }
}

void AdbManager::apply_enable_props_if_needed(const AdbLiveStatus& st, int target_port) const {
    std::string pstr = std::to_string(target_port);
    if (st.persist_port != pstr) set_sys_prop("persist.adb.tcp.port", pstr.c_str());
    if (st.service_port != pstr) set_sys_prop("service.adb.tcp.port", pstr.c_str());
    if (st.persist_usb_config != "adb") set_sys_prop("persist.sys.usb.config", "adb");
}

void AdbManager::ensure_android_settings(AdbLiveStatus& st, bool wifi_adb_enabled) {
    if (!st.boot_completed) return;

    // Ensure persist props are already set before Android's UsbDeviceManager potentially observes adb_enabled=1
    if (wifi_adb_enabled) {
        std::string pstr = std::to_string(st.port);
        set_sys_prop("persist.adb.tcp.port", pstr.c_str());
        set_sys_prop("service.adb.tcp.port", pstr.c_str());
        set_sys_prop("persist.sys.usb.config", "adb");
    }

    const std::string script =
        "put_diff() { cur=$(settings get \"$1\" \"$2\" 2>/dev/null | tr -d '\\r\\n'); "
        "[ \"$cur\" = \"$3\" ] || settings put \"$1\" \"$2\" \"$3\" 2>/dev/null || true; }\n"
        "put_diff global development_settings_enabled 1\n"
        "put_diff global device_provisioned 1\n"
        "put_diff secure user_setup_complete 1\n"
        "put_diff secure install_non_market_apps 1\n"
        "put_diff global install_non_market_apps 1\n"
        "put_diff global stay_on_while_plugged_in 7\n"
        "put_diff global wifi_sleep_policy 2\n" +
        std::string(wifi_adb_enabled ? "put_diff global adb_enabled 1\n" : "") +
        "echo adb=$(settings get global adb_enabled 2>/dev/null | tr -d '\\r\\n')\n"
        "echo wsleep=$(settings get global wifi_sleep_policy 2>/dev/null | tr -d '\\r\\n')\n";

    CmdResult r = run_sh_timeout(script, 6, 512);
    if (!r.output.empty()) {
        auto pos_a = r.output.find("adb=");
        if (pos_a != std::string::npos) {
            auto end_a = r.output.find('\n', pos_a);
            std::string val = trim_copy(r.output.substr(pos_a + 4, end_a == std::string::npos ? std::string::npos : end_a - (pos_a + 4)));
            if (!val.empty() && val != "null") st.adb_setting = val;
        }
        auto pos_w = r.output.find("wsleep=");
        if (pos_w != std::string::npos) {
            auto end_w = r.output.find('\n', pos_w);
            std::string val = trim_copy(r.output.substr(pos_w + 7, end_w == std::string::npos ? std::string::npos : end_w - (pos_w + 7)));
            if (!val.empty() && val != "null") st.wifi_sleep_policy = val;
        }
    }
}

bool AdbManager::perform_adbd_transition(bool want_enable, int target_port, const std::string& reason) {
    std::lock_guard<std::mutex> op_lk(op_mu_);
    {
        std::lock_guard<std::mutex> lk(mu_);
        st_.busy = true;
    }

    PauseGuardScope pause_scope;
    escape_self_from_adbd_cgroup();

    bool ok = false;
    std::string port_str = std::to_string(target_port);

    if (want_enable) {
        std::string id_prod;
        bool f1_ffs = false;
        check_usb_gadget_ok(&id_prod, &f1_ffs);
        std::string sys_cfg = get_sys_prop("sys.usb.config");

        bool try_opt_a = (sys_cfg == "adb" && id_prod == "0x4e40" && f1_ffs);
        if (try_opt_a) {
            LOGI("WiFiADB", "Restarting adbd for TCP :%d (reason=%s, fast USB-preserving path)",
                 target_port, reason.c_str());
            std::string cmd =
                "echo $$ > /acct/uid_0/cgroup.procs 2>/dev/null || echo $$ > /acct/cgroup.procs 2>/dev/null || true\n"
                "stop adbd 2>/dev/null || true\n"
                "w=0\n"
                "while [ $w -lt 20 ]; do\n"
                "  [ \"$(getprop init.svc.adbd 2>/dev/null | tr -d '\\r\\n')\" = \"stopped\" ] && break\n"
                "  w=$((w+1))\n"
                "  sleep 0.1\n"
                "done\n"
                "sleep 0.15\n"
                "setprop persist.adb.tcp.port " + port_str + " 2>/dev/null || true\n"
                "setprop service.adb.tcp.port " + port_str + " 2>/dev/null || true\n"
                "setprop persist.sys.usb.config adb 2>/dev/null || true\n"
                "start adbd 2>/dev/null || true\n";
            run_sh_timeout(cmd, 8, 512);

            for (int i = 0; i < 24; ++i) {
                usleep(150000);
                bool l6 = is_tcp_port_listening_proc("/proc/net/tcp6", target_port);
                bool l4 = is_tcp_port_listening_proc("/proc/net/tcp", target_port);
                bool svc_ok = (get_sys_prop("init.svc.adbd") == "running");
                if ((l6 || l4) && svc_ok && get_sys_prop("sys.usb.config") == "adb") {
                    ok = true;
                    break;
                }
            }
        }

        if (!ok) {
            LOGW("WiFiADB", "Running full ConfigFS + adbd recovery for TCP :%d (reason=%s, opt_a_tried=%d)",
                 target_port, reason.c_str(), try_opt_a ? 1 : 0);
            std::string cmd =
                "echo $$ > /acct/uid_0/cgroup.procs 2>/dev/null || echo $$ > /acct/cgroup.procs 2>/dev/null || true\n"
                "setprop sys.usb.config none 2>/dev/null || true\n"
                "w=0\n"
                "while [ $w -lt 20 ]; do\n"
                "  adbd_st=$(getprop init.svc.adbd 2>/dev/null | tr -d '\\r\\n')\n"
                "  usb_st=$(getprop sys.usb.state 2>/dev/null | tr -d '\\r\\n')\n"
                "  [ \"$adbd_st\" = \"stopped\" ] && [ \"$usb_st\" = \"none\" ] && break\n"
                "  w=$((w+1))\n"
                "  sleep 0.2\n"
                "done\n"
                "setprop persist.adb.tcp.port " + port_str + " 2>/dev/null || true\n"
                "setprop service.adb.tcp.port " + port_str + " 2>/dev/null || true\n"
                "setprop persist.sys.usb.config adb 2>/dev/null || true\n"
                "setprop sys.usb.config adb 2>/dev/null || true\n"
                "start adbd 2>/dev/null || true\n";
            run_sh_timeout(cmd, 12, 512);

            for (int i = 0; i < 30; ++i) {
                usleep(150000);
                bool l6 = is_tcp_port_listening_proc("/proc/net/tcp6", target_port);
                bool l4 = is_tcp_port_listening_proc("/proc/net/tcp", target_port);
                bool svc_ok = (get_sys_prop("init.svc.adbd") == "running");
                if ((l6 || l4) && svc_ok) {
                    ok = true;
                    break;
                }
            }
        }
    } else {
        LOGI("WiFiADB", "Disabling Wi-Fi ADB TCP listener (port=%d, reason=%s, switching adbd to USB-only)",
             target_port, reason.c_str());
        std::string cmd =
            "echo $$ > /acct/uid_0/cgroup.procs 2>/dev/null || echo $$ > /acct/cgroup.procs 2>/dev/null || true\n"
            "setprop persist.adb.tcp.port \"\" 2>/dev/null || true\n"
            "setprop service.adb.tcp.port -1 2>/dev/null || true\n"
            "stop adbd 2>/dev/null || true\n"
            "w=0\n"
            "while [ $w -lt 20 ]; do\n"
            "  [ \"$(getprop init.svc.adbd 2>/dev/null | tr -d '\\r\\n')\" = \"stopped\" ] && break\n"
            "  w=$((w+1))\n"
            "  sleep 0.1\n"
            "done\n"
            "sleep 0.15\n"
            "start adbd 2>/dev/null || true\n";
        run_sh_timeout(cmd, 8, 512);

        for (int i = 0; i < 20; ++i) {
            usleep(150000);
            bool l6 = is_tcp_port_listening_proc("/proc/net/tcp6", target_port);
            bool l4 = is_tcp_port_listening_proc("/proc/net/tcp", target_port);
            if (!l6 && !l4) {
                ok = true;
                break;
            }
        }
    }

    AdbLiveStatus fresh;
    {
        std::lock_guard<std::mutex> lk(mu_);
        fresh = st_;
    }
    fresh.enabled = want_enable;
    poll_kernel_and_props(fresh, target_port);
    if (want_enable && fresh.boot_completed) {
        ensure_android_settings(fresh, true);
    }

    bool pw_off = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        pw_off = cfg_.wifi_power_save_off;
    }
    check_and_fix_wifi_power_save(fresh, pw_off);

    fresh.busy = false;
    fresh.last_repair_epoch = static_cast<int64_t>(time(nullptr));
    fresh.last_repair_reason = reason;
    fresh.last_repair_ok = ok;
    if (ok) {
        fresh.repair_count += 1;
        fresh.consecutive_failures = 0;
        LOGI("WiFiADB", "Transition OK (%s): enabled=%d port=%d listening=%d usb_ok=%d pw_save=%s",
             reason.c_str(), want_enable ? 1 : 0, target_port,
             fresh.listening ? 1 : 0, fresh.usb_gadget_ok ? 1 : 0,
             fresh.wifi_power_save_state.c_str());
    } else {
        fresh.consecutive_failures += 1;
        LOGW("WiFiADB", "Transition FAILED (%s): enabled=%d port=%d listening=%d adbd=%d svc_port=%s",
             reason.c_str(), want_enable ? 1 : 0, target_port,
             fresh.listening ? 1 : 0, fresh.adbd_running ? 1 : 0,
             fresh.service_port.c_str());
    }

    {
        std::lock_guard<std::mutex> lk(mu_);
        st_ = fresh;
    }
    last_repair_tp_ = std::chrono::steady_clock::now();
    notify_listener_if_changed(want_enable, target_port, fresh.listening);
    return ok;
}

void AdbManager::notify_listener_if_changed(bool enabled, int port, bool listening) {
    int adv_port = (enabled && listening && port > 0) ? port : 0;
    ListenerChangedCb cb;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (last_notified_adb_port_ == adv_port) return;
        last_notified_adb_port_ = adv_port;
        cb = listener_cb_;
    }
    if (cb) cb(adv_port);
}

void AdbManager::start(const NetworkConfig& cfg,
                       const std::string& hostname,
                       ListenerChangedCb on_listener_changed) {
    stop();
    {
        std::lock_guard<std::mutex> lk(mu_);
        cfg_ = cfg;
        hostname_ = sanitize_mdns_host(hostname);
        listener_cb_ = std::move(on_listener_changed);
        st_ = AdbLiveStatus{};
        st_.enabled = cfg_.wifi_adb_enabled;
        st_.port = cfg_.wifi_adb_port;
        st_.wifi_power_save_off = cfg_.wifi_power_save_off;
        poll_kernel_and_props(st_, cfg_.wifi_adb_port);
        last_notified_adb_port_ = -1;
        settings_synced_after_boot_ = false;
        mismatch_seen_ = false;
        usb_only_drift_attempts_ = 0;
        started_tp_ = std::chrono::steady_clock::now();
        last_repair_tp_ = started_tp_ - std::chrono::seconds(60);
        last_pwrsave_check_tp_ = started_tp_ - std::chrono::seconds(60);
        last_settings_sync_tp_ = started_tp_ - std::chrono::minutes(10);
    }
    sync_disabled_marker(cfg.wifi_adb_enabled);
    stop_req_.store(false);
    wake_req_.store(false);
    worker_ = std::thread(&AdbManager::worker_loop, this);
    LOGI("WiFiADB", "Watchdog started (enabled=%d, port=%d, pw_save_off=%d, listening=%d)",
         cfg.wifi_adb_enabled ? 1 : 0, cfg.wifi_adb_port,
         cfg.wifi_power_save_off ? 1 : 0, snapshot().listening ? 1 : 0);
}

void AdbManager::stop() {
    stop_req_.store(true);
    {
        std::lock_guard<std::mutex> lk(wake_mu_);
        wake_req_.store(true);
    }
    wake_cv_.notify_all();
    if (worker_.joinable()) {
        worker_.join();
    }
}

void AdbManager::update_config(const NetworkConfig& cfg, const std::string& hostname) {
    bool wake = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (cfg_.wifi_adb_enabled != cfg.wifi_adb_enabled ||
            cfg_.wifi_adb_port != cfg.wifi_adb_port ||
            cfg_.wifi_power_save_off != cfg.wifi_power_save_off) {
            wake = true;
        }
        cfg_ = cfg;
        hostname_ = sanitize_mdns_host(hostname);
        st_.enabled = cfg_.wifi_adb_enabled;
        st_.wifi_power_save_off = cfg_.wifi_power_save_off;
    }
    sync_disabled_marker(cfg.wifi_adb_enabled);
    if (wake) {
        mismatch_seen_ = false;
        {
            std::lock_guard<std::mutex> wlk(wake_mu_);
            wake_req_.store(true);
        }
        wake_cv_.notify_all();
    }
}

bool AdbManager::repair_now(const std::string& reason, bool force_restart) {
    NetworkConfig c;
    {
        std::lock_guard<std::mutex> lk(mu_);
        c = cfg_;
    }
    sync_disabled_marker(c.wifi_adb_enabled);

    AdbLiveStatus cur;
    cur.enabled = c.wifi_adb_enabled;
    cur.port = c.wifi_adb_port;
    cur.wifi_power_save_off = c.wifi_power_save_off;
    poll_kernel_and_props(cur, c.wifi_adb_port);

    if (!force_restart) {
        if (c.wifi_adb_enabled && cur.listening && cur.adbd_running && cur.usb_gadget_ok) {
            apply_enable_props_if_needed(cur, c.wifi_adb_port);
            check_and_fix_wifi_power_save(cur, c.wifi_power_save_off);
            if (cur.boot_completed) ensure_android_settings(cur, true);
            {
                std::lock_guard<std::mutex> lk(mu_);
                st_ = cur;
            }
            notify_listener_if_changed(c.wifi_adb_enabled, c.wifi_adb_port, cur.listening);
            return true;
        }
        if (!c.wifi_adb_enabled && !cur.listening) {
            check_and_fix_wifi_power_save(cur, c.wifi_power_save_off);
            {
                std::lock_guard<std::mutex> lk(mu_);
                st_ = cur;
            }
            notify_listener_if_changed(false, c.wifi_adb_port, false);
            return true;
        }
    }

    return perform_adbd_transition(c.wifi_adb_enabled, c.wifi_adb_port, reason);
}

void AdbManager::worker_loop() {
    // Allow early boot init/rc_local scripts 3s on fresh boot, or 1s on warm daemon restart
    double up = read_os_uptime_sec();
    int grace_ms = (up > 0.0 && up < 45.0) ? 3000 : 1000;
    {
        std::unique_lock<std::mutex> lk(wake_mu_);
        wake_cv_.wait_for(lk, std::chrono::milliseconds(grace_ms), [&]() {
            return stop_req_.load() || wake_req_.exchange(false);
        });
    }

    while (!stop_req_.load()) {
        NetworkConfig c;
        AdbLiveStatus prev_st;
        {
            std::lock_guard<std::mutex> lk(mu_);
            c = cfg_;
            prev_st = st_;
        }

        AdbLiveStatus cur = prev_st;
        cur.enabled = c.wifi_adb_enabled;
        cur.port = c.wifi_adb_port;
        cur.wifi_power_save_off = c.wifi_power_save_off;
        poll_kernel_and_props(cur, c.wifi_adb_port);

        auto now = std::chrono::steady_clock::now();

        // Throttled Wi-Fi 802.11 power save check (every 30s, fast native iw binary)
        if ((now - last_pwrsave_check_tp_) >= std::chrono::seconds(30)) {
            last_pwrsave_check_tp_ = now;
            check_and_fix_wifi_power_save(cur, c.wifi_power_save_off);
        }

        // Android framework settings sync: once after boot_completed, then every 5 min
        if (cur.boot_completed &&
            (!settings_synced_after_boot_ ||
             (now - last_settings_sync_tp_) >= std::chrono::seconds(300))) {
            if (!settings_synced_after_boot_ && (now - started_tp_) < std::chrono::seconds(5)) {
                // Give ActivityManager/SettingsProvider a moment right after boot_completed
            } else {
                last_settings_sync_tp_ = now;
                settings_synced_after_boot_ = true;
                ensure_android_settings(cur, c.wifi_adb_enabled);
                // Re-poll in case adb_enabled=1 triggered UsbDeviceManager change
                poll_kernel_and_props(cur, c.wifi_adb_port);
            }
        }

        {
            std::lock_guard<std::mutex> lk(mu_);
            st_ = cur;
        }
        notify_listener_if_changed(c.wifi_adb_enabled, c.wifi_adb_port, cur.listening);

        bool external_pause = pause_guard_active();
        if (!external_pause) {
            std::string svc_state = get_sys_prop("init.svc.adbd");
            bool adbd_restarting = (svc_state == "restarting" || svc_state == "stopping");

            if (c.wifi_adb_enabled) {
                // Keep persistent props aligned even while active connection stays up
                if (cur.listening && cur.adbd_running) {
                    apply_enable_props_if_needed(cur, c.wifi_adb_port);
                }
                if (cur.usb_gadget_ok) {
                    usb_only_drift_attempts_ = 0;
                }

                bool tcp_or_proc_down = (!cur.listening || !cur.adbd_running);
                bool usb_only_drift = (!tcp_or_proc_down && !cur.usb_gadget_ok);
                bool need_restart = tcp_or_proc_down || usb_only_drift;

                if (need_restart && !adbd_restarting) {
                    if (!mismatch_seen_) {
                        mismatch_seen_ = true;
                        first_mismatch_tp_ = now;
                    } else if ((now - first_mismatch_tp_) >= std::chrono::seconds(2)) {
                        int cooldown_sec = usb_only_drift
                                               ? ((usb_only_drift_attempts_ >= 2) ? 600 : 15)
                                               : ((cur.consecutive_failures >= 3) ? 60 : 15);
                        if ((now - last_repair_tp_) >= std::chrono::seconds(cooldown_sec)) {
                            mismatch_seen_ = false;
                            std::string reason = !cur.adbd_running
                                                     ? "adbd_not_running"
                                                     : (!cur.listening ? "tcp_port_not_listening" : "usb_gadget_drift");
                            if (usb_only_drift) {
                                usb_only_drift_attempts_ += 1;
                            }
                            perform_adbd_transition(true, c.wifi_adb_port, reason);
                        }
                    }
                } else {
                    mismatch_seen_ = false;
                }
            } else {
                // Wi-Fi ADB explicitly disabled: close TCP port if listening (check target port & default 5555)
                int svc_p = atoi(cur.service_port.c_str());
                bool tcp_active = cur.listening ||
                                  is_tcp_port_listening_proc("/proc/net/tcp6", 5555) ||
                                  is_tcp_port_listening_proc("/proc/net/tcp", 5555) ||
                                  (svc_p > 0 && (is_tcp_port_listening_proc("/proc/net/tcp6", svc_p) ||
                                                 is_tcp_port_listening_proc("/proc/net/tcp", svc_p)));
                if (tcp_active && !adbd_restarting &&
                    (now - last_repair_tp_) >= std::chrono::seconds(15)) {
                    int close_p = cur.listening ? c.wifi_adb_port : (svc_p > 0 ? svc_p : 5555);
                    perform_adbd_transition(false, close_p, "disabled_by_config");
                }
                mismatch_seen_ = false;
            }
        } else {
            mismatch_seen_ = false;
        }

        int wait_ms = mismatch_seen_ ? 2000 : 5000;
        std::unique_lock<std::mutex> lk(wake_mu_);
        wake_cv_.wait_for(lk, std::chrono::milliseconds(wait_ms), [&]() {
            return stop_req_.load() || wake_req_.exchange(false);
        });
    }
}

AdbLiveStatus AdbManager::snapshot() const {
    std::lock_guard<std::mutex> lk(mu_);
    return st_;
}

json::Value AdbManager::get_status_json(const std::string& local_ip,
                                        const std::string& hostname) const {
    using json::Value;
    AdbLiveStatus s;
    std::string h_snap;
    {
        std::lock_guard<std::mutex> lk(mu_);
        s = st_;
        h_snap = hostname.empty() ? hostname_ : sanitize_mdns_host(hostname);
    }

    Value o = Value::make_obj();
    o["enabled"] = Value::make_bool(s.enabled);
    o["port"] = Value::make_int(s.port);
    o["listening"] = Value::make_bool(s.listening);
    o["listeningV6"] = Value::make_bool(s.listening_v6);
    o["listeningV4"] = Value::make_bool(s.listening_v4);
    o["adbdRunning"] = Value::make_bool(s.adbd_running);
    o["usbGadgetOk"] = Value::make_bool(s.usb_gadget_ok);
    o["servicePort"] = Value::make_str(s.service_port);
    o["persistPort"] = Value::make_str(s.persist_port);
    o["persistUsbConfig"] = Value::make_str(s.persist_usb_config);
    o["sysUsbConfig"] = Value::make_str(s.sys_usb_config);
    o["sysUsbState"] = Value::make_str(s.sys_usb_state);
    o["bootCompleted"] = Value::make_bool(s.boot_completed);
    o["wifiPowerSaveOff"] = Value::make_bool(s.wifi_power_save_off);
    o["wifiPowerSaveState"] = Value::make_str(s.wifi_power_save_state);
    o["adbSetting"] = Value::make_str(s.adb_setting);
    o["wifiSleepPolicy"] = Value::make_str(s.wifi_sleep_policy);
    o["lastRepairEpoch"] = Value::make_int(s.last_repair_epoch);
    o["lastRepairReason"] = Value::make_str(s.last_repair_reason);
    o["lastRepairOk"] = Value::make_bool(s.last_repair_ok);
    o["repairCount"] = Value::make_int(s.repair_count);
    o["busy"] = Value::make_bool(s.busy);
    o["mdnsService"] = Value::make_str((s.enabled && s.listening) ? "_adb._tcp.local" : "");

    std::string port_sfx = ":" + std::to_string(s.port);
    o["connectCommand"] = Value::make_str(!local_ip.empty() ? ("adb connect " + local_ip + port_sfx) : "");
    o["mdnsCommand"] = Value::make_str("adb connect " + h_snap + ".local" + port_sfx);
    return o;
}

} // namespace openstation
