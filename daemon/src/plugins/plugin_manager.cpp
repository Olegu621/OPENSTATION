#include "plugins/plugin_manager.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <arpa/inet.h>
#include <array>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

namespace openstation {

namespace {

constexpr const char* kDefaultSubmitUrl =
    "https://github.com/Olegu621/OPENSTATION/blob/main/plugins/SCHEMA.md";

bool ensure_dir_p(const std::string& path) {
    if (path.empty()) return false;
    std::string cur;
    for (size_t i = 0; i < path.size(); ++i) {
        cur.push_back(path[i]);
        if (path[i] == '/' || i + 1 == path.size()) {
            if (cur == "/") continue;
            if (::mkdir(cur.c_str(), 0755) != 0 && errno != EEXIST) {
                return false;
            }
        }
    }
    return true;
}

bool read_text_file(const std::string& path, std::string& out, size_t max_bytes = 1024 * 1024) {
    out.clear();
    int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    char buf[4096];
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

bool write_text_atomic(const std::string& path, const std::string& content, mode_t mode = 0644) {
    const auto slash = path.find_last_of('/');
    if (slash != std::string::npos) {
        if (!ensure_dir_p(path.substr(0, slash))) return false;
    }
    const std::string tmp = path + ".tmp";
    int fd = ::open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC, mode);
    if (fd < 0) return false;
    size_t off = 0;
    while (off < content.size()) {
        const ssize_t n = ::write(fd, content.data() + off, content.size() - off);
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

void remove_tree(const std::string& path) {
    struct stat st {};
    if (::lstat(path.c_str(), &st) != 0) return;
    if (!S_ISDIR(st.st_mode)) {
        ::unlink(path.c_str());
        return;
    }
    if (DIR* d = ::opendir(path.c_str())) {
        struct dirent* ent = nullptr;
        while ((ent = ::readdir(d)) != nullptr) {
            if (std::strcmp(ent->d_name, ".") == 0 || std::strcmp(ent->d_name, "..") == 0) continue;
            remove_tree(path + "/" + ent->d_name);
        }
        ::closedir(d);
    }
    ::rmdir(path.c_str());
}

std::string trim_copy(std::string s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.pop_back();
    size_t start = 0;
    while (start < s.size() && std::isspace(static_cast<unsigned char>(s[start]))) ++start;
    if (start > 0) s.erase(0, start);
    return s;
}

std::string lower_copy(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string json_scalar_to_str(const json::Value& v) {
    if (v.is_str()) return v.str_val;
    if (v.is_bool()) return v.bool_val ? "true" : "false";
    if (v.is_num()) return v.dump(-1);
    return "";
}

std::string json_escape_raw(const std::string& s) {
    std::string esc = json::Value::escape(s);
    if (esc.size() >= 2 && esc.front() == '"' && esc.back() == '"') {
        return esc.substr(1, esc.size() - 2);
    }
    return esc;
}

std::string url_join(const std::string& base, const std::string& rel) {
    if (base.empty()) return rel;
    if (rel.rfind("http://", 0) == 0 || rel.rfind("https://", 0) == 0) return rel;
    if (base.back() == '/') return base + rel;
    return base + "/" + rel;
}

std::string parent_url(const std::string& u) {
    const auto q = u.find('?');
    const std::string clean = (q == std::string::npos) ? u : u.substr(0, q);
    const auto slash = clean.find_last_of('/');
    if (slash == std::string::npos) return "";
    return clean.substr(0, slash + 1);
}

// Standalone compact SHA-256 so tests work without linking external crypto
struct MiniSha256 {
    uint32_t h[8]{
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u,
    };
    uint8_t buf[64]{};
    size_t buf_len = 0;
    uint64_t total_bits = 0;

    static inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

    void compress(const uint8_t blk[64]) {
        static constexpr uint32_t k[64] = {
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
            w[i] = (uint32_t(blk[i * 4]) << 24) | (uint32_t(blk[i * 4 + 1]) << 16) |
                   (uint32_t(blk[i * 4 + 2]) << 8) | uint32_t(blk[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t t1 = hh + S1 + ch + k[i] + w[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + maj;
            hh = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }

    void update(const uint8_t* data, size_t len) {
        total_bits += uint64_t(len) * 8u;
        size_t pos = 0;
        while (pos < len) {
            size_t take = std::min(size_t(64) - buf_len, len - pos);
            std::memcpy(buf + buf_len, data + pos, take);
            buf_len += take;
            pos += take;
            if (buf_len == 64) {
                compress(buf);
                buf_len = 0;
            }
        }
    }

    std::string finish() {
        buf[buf_len++] = 0x80u;
        if (buf_len > 56) {
            while (buf_len < 64) buf[buf_len++] = 0;
            compress(buf);
            buf_len = 0;
        }
        while (buf_len < 56) buf[buf_len++] = 0;
        for (int i = 7; i >= 0; --i) {
            buf[buf_len++] = static_cast<uint8_t>((total_bits >> (i * 8)) & 0xFFu);
        }
        compress(buf);
        static constexpr char hex[] = "0123456789abcdef";
        std::string out;
        out.reserve(64);
        for (int i = 0; i < 8; ++i) {
            for (int j = 7; j >= 0; --j) {
                out.push_back(hex[(h[i] >> (j * 4)) & 0xFu]);
            }
        }
        return out;
    }
};

bool parse_mac_bytes(const std::string& raw, std::array<uint8_t, 6>& mac) {
    std::string hex;
    hex.reserve(12);
    for (unsigned char c : raw) {
        if (std::isxdigit(c)) hex.push_back(static_cast<char>(c));
        else if (c == ':' || c == '-' || c == ' ') continue;
        else return false;
    }
    if (hex.size() != 12) return false;
    for (int i = 0; i < 6; ++i) {
        unsigned int val = 0;
        if (std::sscanf(hex.substr(i * 2, 2).c_str(), "%x", &val) != 1) return false;
        mac[i] = static_cast<uint8_t>(val & 0xFFu);
    }
    return true;
}

bool send_wol_magic_packet(const std::string& mac_str, const std::string& bcast_ip, int port, std::string& out_err) {
    std::array<uint8_t, 6> mac{};
    if (!parse_mac_bytes(mac_str, mac)) {
        out_err = "Invalid MAC address (expected AA:BB:CC:DD:EE:FF)";
        return false;
    }
    uint8_t pkt[102];
    std::memset(pkt, 0xFF, 6);
    for (int i = 0; i < 16; ++i) {
        std::memcpy(pkt + 6 + i * 6, mac.data(), 6);
    }
    int fd = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        out_err = std::string("socket() failed: ") + std::strerror(errno);
        return false;
    }
    int yes = 1;
    ::setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(std::clamp(port, 1, 65535)));
    if (::inet_pton(AF_INET, bcast_ip.empty() ? "255.255.255.255" : bcast_ip.c_str(), &addr.sin_addr) != 1) {
        ::close(fd);
        out_err = "Invalid broadcast IP address";
        return false;
    }
    bool sent_ok = false;
    for (int attempt = 0; attempt < 3; ++attempt) {
        ssize_t n = ::sendto(fd, pkt, sizeof(pkt), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
        if (n == static_cast<ssize_t>(sizeof(pkt))) sent_ok = true;
    }
    if (!sent_ok) {
        out_err = std::string("sendto() failed: ") + std::strerror(errno);
    }
    ::close(fd);
    return sent_ok;
}

bool is_safe_station_tool(const std::string& tool_name) {
    return tool_name == "set_led_ring" ||
           tool_name == "set_led_screen" ||
           tool_name == "show_screen_banner" ||
           tool_name == "set_volume" ||
           tool_name == "set_eq_preset" ||
           tool_name == "set_hardware_eq" ||
           tool_name == "set_audio_route" ||
           tool_name == "set_mic_mute" ||
           tool_name == "play_sound" ||
           tool_name == "music_control" ||
           tool_name == "manage_apps" ||
           tool_name == "manage_timer" ||
           tool_name == "tv_hdmi_cec" ||
           tool_name == "remote_control" ||
           tool_name == "bluetooth_control";
}

bool is_secret_setting(const json::Value& manifest, const std::string& key) {
    const auto* ss = manifest.get("settingsSchema");
    if (!ss || !ss->is_arr()) return false;
    for (const auto& item : ss->arr_val) {
        if (!item.is_obj()) continue;
        const auto* k = item.get("key");
        const auto* t = item.get("type");
        if (k && k->as_str() == key && t && lower_copy(t->as_str()) == "secret") {
            return true;
        }
    }
    return false;
}

std::map<std::string, json::Value> default_settings_from_manifest(const json::Value& manifest) {
    std::map<std::string, json::Value> out;
    const auto* ss = manifest.get("settingsSchema");
    if (!ss || !ss->is_arr()) return out;
    for (const auto& item : ss->arr_val) {
        if (!item.is_obj()) continue;
        const auto* k = item.get("key");
        const auto* def = item.get("default");
        if (k && k->is_str() && !k->as_str().empty() && def && !def->is_null()) {
            out[k->as_str()] = *def;
        }
    }
    return out;
}

std::string shell_bin_path() {
    if (::access("/system/bin/sh", X_OK) == 0) return "/system/bin/sh";
    return "/bin/sh";
}

json::Value run_shell_capture(const std::string& cmd,
                              const std::string& plugin_id,
                              const std::string& plugin_dir,
                              const json::Value& args,
                              const std::map<std::string, json::Value>& settings,
                              int timeout_sec) {
    using json::Value;
    Value out = Value::make_obj();
    int pipefd[2]{-1, -1};
    if (::pipe(pipefd) != 0) {
        out["ok"] = Value::make_bool(false);
        out["error"] = Value::make_str("pipe() failed");
        return out;
    }

    Value settings_obj = Value::make_obj();
    for (const auto& kv : settings) settings_obj[kv.first] = kv.second;
    const std::string args_str = args.is_obj() ? args.dump(-1) : "{}";
    const std::string set_str = settings_obj.dump(-1);

    pid_t pid = ::fork();
    if (pid < 0) {
        ::close(pipefd[0]);
        ::close(pipefd[1]);
        out["ok"] = Value::make_bool(false);
        out["error"] = Value::make_str("fork() failed");
        return out;
    }
    if (pid == 0) {
        ::close(pipefd[0]);
        ::dup2(pipefd[1], STDOUT_FILENO);
        ::dup2(pipefd[1], STDERR_FILENO);
        if (pipefd[1] > STDERR_FILENO) ::close(pipefd[1]);
        ::setenv("OPENSTATION_PLUGIN_ID", plugin_id.c_str(), 1);
        ::setenv("OPENSTATION_PLUGIN_DIR", plugin_dir.c_str(), 1);
        ::setenv("OPENSTATION_ARGS_JSON", args_str.c_str(), 1);
        ::setenv("OPENSTATION_SETTINGS_JSON", set_str.c_str(), 1);
        if (!plugin_dir.empty()) {
            (void)::chdir(plugin_dir.c_str());
        }
        const std::string sh = shell_bin_path();
        ::execl(sh.c_str(), sh.c_str(), "-c", cmd.c_str(), nullptr);
        ::_exit(127);
    }

    ::close(pipefd[1]);
    std::string output;
    constexpr size_t kMaxOut = 16384;
    const auto deadline_ms = std::clamp(timeout_sec, 1, 25) * 1000;
    const auto t0 = std::chrono::steady_clock::now();
    bool timed_out = false;

    while (true) {
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                                 std::chrono::steady_clock::now() - t0).count();
        if (elapsed >= deadline_ms) {
            timed_out = true;
            ::kill(pid, SIGKILL);
            break;
        }
        struct pollfd pfd{pipefd[0], POLLIN, 0};
        const int rem = static_cast<int>(std::min<int64_t>(250, deadline_ms - elapsed));
        int pr = ::poll(&pfd, 1, rem);
        if (pr > 0) {
            char buf[1024];
            ssize_t n = ::read(pipefd[0], buf, sizeof(buf));
            if (n == 0) break;
            if (n > 0 && output.size() < kMaxOut) {
                output.append(buf, std::min<size_t>(static_cast<size_t>(n), kMaxOut - output.size()));
            }
        }
    }
    ::close(pipefd[0]);

    int status = 0;
    ::waitpid(pid, &status, 0);
    const int exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    out["ok"] = Value::make_bool(!timed_out && exit_code == 0);
    out["exitCode"] = Value::make_int(exit_code);
    out["timedOut"] = Value::make_bool(timed_out);
    std::string trimmed = trim_copy(output);
    out["output"] = Value::make_str(trimmed);
    Value parsed;
    if (!trimmed.empty() && json::Parser::parse(trimmed, parsed)) {
        out["json"] = std::move(parsed);
    }
    if (timed_out) {
        out["error"] = Value::make_str("Command timed out");
    } else if (exit_code != 0) {
        out["error"] = Value::make_str("Command failed with exit code " + std::to_string(exit_code));
    }
    return out;
}

} // namespace

bool PluginManager::is_valid_plugin_id(const std::string& id) {
    if (id.size() < 2 || id.size() > 48) return false;
    if (!std::isalnum(static_cast<unsigned char>(id[0])) || std::isupper(static_cast<unsigned char>(id[0]))) {
        return false;
    }
    for (unsigned char c : id) {
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') continue;
        return false;
    }
    return true;
}

bool PluginManager::is_valid_rel_path(const std::string& rel) {
    if (rel.empty() || rel.size() > 160) return false;
    if (rel[0] == '/' || rel.find('\\') != std::string::npos || rel.find("..") != std::string::npos) {
        return false;
    }
    for (unsigned char c : rel) {
        if (std::isalnum(c) || c == '/' || c == '.' || c == '-' || c == '_') continue;
        return false;
    }
    return true;
}

bool PluginManager::is_valid_tool_name(const std::string& name) {
    if (name.size() < 3 || name.size() > 48) return false;
    if (!(name[0] >= 'a' && name[0] <= 'z')) return false;
    for (unsigned char c : name) {
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') continue;
        return false;
    }
    return true;
}

std::string PluginManager::sha256_hex(const uint8_t* data, size_t len) {
    MiniSha256 ctx;
    if (data && len > 0) ctx.update(data, len);
    return ctx.finish();
}

std::string PluginManager::sha256_hex_str(const std::string& s) {
    return sha256_hex(reinterpret_cast<const uint8_t*>(s.data()), s.size());
}

std::string PluginManager::render_template(const std::string& tmpl,
                                           const json::Value& args,
                                           const std::map<std::string, json::Value>& settings,
                                           int mode) {
    std::string out;
    out.reserve(tmpl.size() + 32);
    size_t i = 0;
    while (i < tmpl.size()) {
        if (i + 1 < tmpl.size() && tmpl[i] == '{' && tmpl[i + 1] == '{') {
            size_t end = tmpl.find("}}", i + 2);
            if (end != std::string::npos) {
                std::string expr = trim_copy(tmpl.substr(i + 2, end - (i + 2)));
                std::string def_val;
                size_t pipe = expr.find('|');
                if (pipe != std::string::npos) {
                    def_val = expr.substr(pipe + 1);
                    expr = trim_copy(expr.substr(0, pipe));
                }
                std::string val = def_val;
                bool is_arg = false;
                if (expr.rfind("args.", 0) == 0) {
                    is_arg = true;
                    std::string key = expr.substr(5);
                    if (const auto* av = args.get(key)) {
                        std::string s = json_scalar_to_str(*av);
                        if (!s.empty()) val = s;
                    }
                } else if (expr.rfind("settings.", 0) == 0) {
                    std::string key = expr.substr(9);
                    auto it = settings.find(key);
                    if (it != settings.end()) {
                        std::string s = json_scalar_to_str(it->second);
                        if (!s.empty()) val = s;
                    }
                }
                if (mode == 1) {
                    if (is_arg) {
                        out += url_encode(val);
                    } else {
                        for (unsigned char ch : val) {
                            if (ch >= 0x20 && ch != 0x7f) out.push_back(static_cast<char>(ch));
                        }
                    }
                } else if (mode == 2) {
                    out += json_escape_raw(val);
                } else {
                    out += val;
                }
                i = end + 2;
                continue;
            }
        }
        out.push_back(tmpl[i++]);
    }
    return out;
}

bool PluginManager::init(const std::string& base_dir, PluginCallbacks cb) {
    std::lock_guard<std::mutex> lk(mu_);
    cb_ = std::move(cb);
    base_dir_ = base_dir;
    while (base_dir_.size() > 1 && base_dir_.back() == '/') base_dir_.pop_back();
    installed_dir_ = base_dir_ + "/installed";
    state_path_ = base_dir_ + "/state.json";
    catalog_cache_path_ = base_dir_ + "/catalog_cache.json";

    if (!ensure_dir_p(installed_dir_)) return false;
    load_state_locked();

    std::string cached;
    if (read_text_file(catalog_cache_path_, cached) && !cached.empty()) {
        json::Value j;
        if (json::Parser::parse(cached, j) && j.is_obj()) {
            if (const auto* arr = j.get("plugins"); arr && arr->is_arr()) {
                catalog_items_ = *arr;
                catalog_source_ = "cache";
                if (const auto* ft = j.get("fetchedAt")) catalog_fetched_at_ = ft->as_i64(0);
            }
        }
    }
    return true;
}

void PluginManager::set_callbacks(PluginCallbacks cb) {
    std::lock_guard<std::mutex> lk(mu_);
    cb_ = std::move(cb);
}

HttpClientResponse PluginManager::perform_http(const HttpClientRequest& req) const {
    std::function<HttpClientResponse(const HttpClientRequest&)> hook;
    {
        std::lock_guard<std::mutex> lk(mu_);
        hook = cb_.http_hook;
    }
    if (hook) return hook(req);
    return http_perform(req);
}

bool PluginManager::load_state_locked() {
    installed_.clear();
    std::string raw;
    if (!read_text_file(state_path_, raw) || raw.empty()) return true;

    json::Value st;
    if (!json::Parser::parse(raw, st) || !st.is_obj()) return false;
    const auto* arr = st.get("installed");
    if (!arr || !arr->is_arr()) return true;

    for (const auto& item : arr->arr_val) {
        if (!item.is_obj()) continue;
        const auto* idv = item.get("id");
        if (!idv || !idv->is_str() || !is_valid_plugin_id(idv->as_str())) continue;

        InstalledPluginRecord rec;
        rec.id = idv->as_str();
        if (const auto* v = item.get("sourceUrl")) rec.source_url = v->as_str();
        if (const auto* v = item.get("enabled")) rec.enabled = v->as_bool(false);
        if (const auto* v = item.get("official")) rec.official = v->as_bool(false);
        if (const auto* v = item.get("installedAt")) rec.installed_at = v->as_i64(0);
        if (const auto* v = item.get("updatedAt")) rec.updated_at = v->as_i64(0);
        if (const auto* v = item.get("lastRunAt")) rec.last_run_at = v->as_i64(0);
        if (const auto* v = item.get("lastStatus")) rec.last_status = v->as_str();
        if (const auto* v = item.get("lastError")) rec.last_error = v->as_str();

        std::string mf_raw;
        const std::string mf_path = installed_dir_ + "/" + rec.id + "/plugin.json";
        if (!read_text_file(mf_path, mf_raw) || !json::Parser::parse(mf_raw, rec.manifest) || !rec.manifest.is_obj()) {
            continue;
        }
        std::string err;
        if (!validate_manifest(rec.manifest, err)) {
            LOGW("Plugins", "Skipping invalid installed plugin %s: %s", rec.id.c_str(), err.c_str());
            continue;
        }
        rec.settings = default_settings_from_manifest(rec.manifest);
        if (const auto* sv = item.get("settings"); sv && sv->is_obj()) {
            for (const auto& kv : sv->obj_val) {
                rec.settings[kv.first] = kv.second;
            }
        }
        installed_.push_back(std::move(rec));
    }
    return true;
}

bool PluginManager::save_state_locked() const {
    using json::Value;
    Value arr = Value::make_arr();
    for (const auto& rec : installed_) {
        Value o = Value::make_obj();
        o["id"] = Value::make_str(rec.id);
        o["sourceUrl"] = Value::make_str(rec.source_url);
        o["enabled"] = Value::make_bool(rec.enabled);
        o["official"] = Value::make_bool(rec.official);
        o["installedAt"] = Value::make_int(rec.installed_at);
        o["updatedAt"] = Value::make_int(rec.updated_at);
        o["lastRunAt"] = Value::make_int(rec.last_run_at);
        o["lastStatus"] = Value::make_str(rec.last_status);
        o["lastError"] = Value::make_str(rec.last_error);
        Value s = Value::make_obj();
        for (const auto& kv : rec.settings) s[kv.first] = kv.second;
        o["settings"] = std::move(s);
        arr.push(std::move(o));
    }
    Value root = Value::make_obj();
    root["schemaVersion"] = Value::make_int(1);
    root["installed"] = std::move(arr);
    return write_text_atomic(state_path_, root.dump(2) + "\n", 0600);
}

bool PluginManager::validate_manifest(const json::Value& m, std::string& out_error) const {
    if (!m.is_obj()) {
        out_error = "Manifest must be a JSON object";
        return false;
    }
    const auto* idv = m.get("id");
    if (!idv || !idv->is_str() || !is_valid_plugin_id(idv->as_str())) {
        out_error = "Invalid plugin id (allowed: 2..48 lowercase a-z, 0-9, '-', '_')";
        return false;
    }
    const auto* namev = m.get("name");
    if (!namev || !namev->is_str() || trim_copy(namev->as_str()).empty()) {
        out_error = "Plugin name is required";
        return false;
    }
    const auto* verv = m.get("version");
    if (!verv || !verv->is_str() || trim_copy(verv->as_str()).empty()) {
        out_error = "Plugin version is required";
        return false;
    }
    if (const auto* tools = m.get("tools")) {
        if (!tools->is_arr()) {
            out_error = "tools must be an array";
            return false;
        }
        for (const auto& t : tools->arr_val) {
            if (!t.is_obj()) {
                out_error = "each tool must be an object";
                return false;
            }
            const auto* tn = t.get("name");
            if (!tn || !tn->is_str() || !is_valid_tool_name(tn->as_str())) {
                out_error = "Invalid tool name";
                return false;
            }
            if (!t.get("runner") || !t.get("runner")->is_obj()) {
                out_error = "Tool '" + tn->as_str() + "' is missing runner";
                return false;
            }
        }
    }
    if (const auto* files = m.get("files")) {
        if (!files->is_arr()) {
            out_error = "files must be an array";
            return false;
        }
        for (const auto& f : files->arr_val) {
            if (!f.is_obj() || !f.get("path") || !is_valid_rel_path(f.get("path")->as_str())) {
                out_error = "Invalid bundled file path";
                return false;
            }
        }
    }
    return true;
}

bool PluginManager::set_catalog_json(const json::Value& index_obj, std::string& out_error) {
    if (!index_obj.is_obj() || !index_obj.get("plugins") || !index_obj.get("plugins")->is_arr()) {
        out_error = "Invalid catalog format: expected {\"plugins\": [...]}";
        return false;
    }
    std::lock_guard<std::mutex> lk(mu_);
    catalog_items_ = *index_obj.get("plugins");
    catalog_fetched_at_ = static_cast<int64_t>(std::time(nullptr));
    catalog_source_ = "manual";
    catalog_error_.clear();

    json::Value cache = index_obj;
    cache["fetchedAt"] = json::Value::make_int(catalog_fetched_at_);
    write_text_atomic(catalog_cache_path_, cache.dump(2) + "\n", 0644);
    return true;
}

bool PluginManager::fetch_catalog(const std::string& repo,
                                  const std::string& branch,
                                  bool force_refresh,
                                  std::string& out_error,
                                  const std::atomic<bool>* abort_req) {
    const int64_t now_s = static_cast<int64_t>(std::time(nullptr));
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (!force_refresh && catalog_items_.is_arr() && !catalog_items_.arr_val.empty() &&
            catalog_fetched_at_ > 0 && (now_s - catalog_fetched_at_) < 300) {
            return true;
        }
    }

    const std::string r = repo.empty() ? "Olegu621/OPENSTATION" : repo;
    const std::string b = branch.empty() ? "main" : branch;
    HttpClientRequest req;
    req.url = "https://raw.githubusercontent.com/" + r + "/" + b + "/plugins/index.json";
    req.timeout_ms = 9000;
    req.max_body_bytes = 512 * 1024;
    req.abort_req = abort_req;
    req.headers["Accept"] = "application/json";

    HttpClientResponse resp = perform_http(req);
    if (!resp.ok || resp.status_code < 200 || resp.status_code >= 300) {
        out_error = !resp.error.empty() ? resp.error : ("HTTP " + std::to_string(resp.status_code));
        std::lock_guard<std::mutex> lk(mu_);
        catalog_error_ = out_error;
        return false;
    }

    json::Value parsed;
    if (!json::Parser::parse(resp.body, parsed) || !parsed.is_obj() ||
        !parsed.get("plugins") || !parsed.get("plugins")->is_arr()) {
        out_error = "Invalid plugins/index.json payload";
        std::lock_guard<std::mutex> lk(mu_);
        catalog_error_ = out_error;
        return false;
    }

    {
        std::lock_guard<std::mutex> lk(mu_);
        catalog_items_ = *parsed.get("plugins");
        catalog_fetched_at_ = static_cast<int64_t>(std::time(nullptr));
        catalog_source_ = "github";
        catalog_error_.clear();

        json::Value cache = parsed;
        cache["fetchedAt"] = json::Value::make_int(catalog_fetched_at_);
        write_text_atomic(catalog_cache_path_, cache.dump(2) + "\n", 0644);
    }
    return true;
}

bool PluginManager::install_from_catalog(const std::string& plugin_id,
                                         const std::string& repo,
                                         const std::string& branch,
                                         std::string& out_error,
                                         const std::atomic<bool>* abort_req) {
    if (!is_valid_plugin_id(plugin_id)) {
        out_error = "Invalid plugin id";
        return false;
    }
    std::string manifest_url;
    std::string expected_sha;
    bool found = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (catalog_items_.is_arr()) {
            for (const auto& item : catalog_items_.arr_val) {
                if (!item.is_obj() || !item.get("id") || item.get("id")->as_str() != plugin_id) continue;
                found = true;
                if (const auto* mu = item.get("manifestUrl")) manifest_url = mu->as_str();
                if (const auto* sh = item.get("manifestSha256")) expected_sha = lower_copy(trim_copy(sh->as_str()));
                break;
            }
        }
    }
    if (!found) {
        std::string ferr;
        if (fetch_catalog(repo, branch, true, ferr, abort_req)) {
            std::lock_guard<std::mutex> lk(mu_);
            for (const auto& item : catalog_items_.arr_val) {
                if (!item.is_obj() || !item.get("id") || item.get("id")->as_str() != plugin_id) continue;
                found = true;
                if (const auto* mu = item.get("manifestUrl")) manifest_url = mu->as_str();
                if (const auto* sh = item.get("manifestSha256")) expected_sha = lower_copy(trim_copy(sh->as_str()));
                break;
            }
        }
    }
    if (manifest_url.empty()) {
        const std::string r = repo.empty() ? "Olegu621/OPENSTATION" : repo;
        const std::string b = branch.empty() ? "main" : branch;
        manifest_url = "https://raw.githubusercontent.com/" + r + "/" + b + "/plugins/" + plugin_id + "/plugin.json";
    }

    HttpClientRequest req;
    req.url = manifest_url;
    req.timeout_ms = 10000;
    req.max_body_bytes = 256 * 1024;
    req.abort_req = abort_req;
    HttpClientResponse resp = perform_http(req);
    if (!resp.ok || resp.status_code < 200 || resp.status_code >= 300) {
        out_error = "Failed to download plugin manifest: " +
                    (!resp.error.empty() ? resp.error : ("HTTP " + std::to_string(resp.status_code)));
        return false;
    }
    if (!expected_sha.empty()) {
        const std::string got_sha = sha256_hex_str(resp.body);
        if (got_sha != expected_sha) {
            out_error = "Manifest SHA-256 mismatch for plugin " + plugin_id;
            return false;
        }
    }

    json::Value mf;
    if (!json::Parser::parse(resp.body, mf) || !mf.is_obj()) {
        out_error = "Invalid plugin.json response";
        return false;
    }
    if (!mf.get("id") || mf.get("id")->as_str() != plugin_id) {
        out_error = "Downloaded manifest id does not match requested plugin";
        return false;
    }

    std::map<std::string, std::string> downloaded_files;
    const std::string base_url = parent_url(manifest_url);
    if (const auto* files = mf.get("files"); files && files->is_arr()) {
        for (const auto& f : files->arr_val) {
            if (!f.is_obj() || !f.get("path")) continue;
            const std::string rel = f.get("path")->as_str();
            if (!is_valid_rel_path(rel)) {
                out_error = "Unsafe file path in manifest: " + rel;
                return false;
            }
            const std::string furl = f.get("url") && !f.get("url")->as_str().empty()
                                         ? f.get("url")->as_str()
                                         : url_join(base_url, rel);
            HttpClientRequest freq;
            freq.url = furl;
            freq.timeout_ms = 10000;
            freq.max_body_bytes = 512 * 1024;
            freq.abort_req = abort_req;
            HttpClientResponse fresp = perform_http(freq);
            if (!fresp.ok || fresp.status_code < 200 || fresp.status_code >= 300) {
                out_error = "Failed to download plugin file " + rel;
                return false;
            }
            if (const auto* fsha = f.get("sha256"); fsha && !fsha->as_str().empty()) {
                if (sha256_hex_str(fresp.body) != lower_copy(trim_copy(fsha->as_str()))) {
                    out_error = "SHA-256 mismatch on plugin file: " + rel;
                    return false;
                }
            }
            downloaded_files[rel] = std::move(fresp.body);
        }
    }

    return install_from_manifest(mf, manifest_url, downloaded_files, true, true, out_error);
}

bool PluginManager::install_from_url(const std::string& manifest_url,
                                     std::string& out_error,
                                     const std::atomic<bool>* abort_req) {
    if (manifest_url.rfind("http://", 0) != 0 && manifest_url.rfind("https://", 0) != 0) {
        out_error = "URL must start with http:// or https://";
        return false;
    }
    HttpClientRequest req;
    req.url = manifest_url;
    req.timeout_ms = 10000;
    req.max_body_bytes = 256 * 1024;
    req.abort_req = abort_req;
    HttpClientResponse resp = perform_http(req);
    if (!resp.ok || resp.status_code < 200 || resp.status_code >= 300) {
        out_error = "Failed to download manifest URL: " +
                    (!resp.error.empty() ? resp.error : ("HTTP " + std::to_string(resp.status_code)));
        return false;
    }
    json::Value mf;
    if (!json::Parser::parse(resp.body, mf) || !mf.is_obj()) {
        out_error = "Invalid plugin JSON at URL";
        return false;
    }
    std::map<std::string, std::string> downloaded_files;
    const std::string base_url = parent_url(manifest_url);
    if (const auto* files = mf.get("files"); files && files->is_arr()) {
        for (const auto& f : files->arr_val) {
            if (!f.is_obj() || !f.get("path")) continue;
            const std::string rel = f.get("path")->as_str();
            if (!is_valid_rel_path(rel)) {
                out_error = "Unsafe file path in manifest: " + rel;
                return false;
            }
            const std::string furl = f.get("url") && !f.get("url")->as_str().empty()
                                         ? f.get("url")->as_str()
                                         : url_join(base_url, rel);
            HttpClientRequest freq;
            freq.url = furl;
            freq.timeout_ms = 10000;
            freq.max_body_bytes = 512 * 1024;
            freq.abort_req = abort_req;
            HttpClientResponse fresp = perform_http(freq);
            if (!fresp.ok || fresp.status_code < 200 || fresp.status_code >= 300) {
                out_error = "Failed to download plugin file " + rel;
                return false;
            }
            if (const auto* fsha = f.get("sha256"); fsha && !fsha->as_str().empty()) {
                if (sha256_hex_str(fresp.body) != lower_copy(trim_copy(fsha->as_str()))) {
                    out_error = "SHA-256 mismatch on plugin file: " + rel;
                    return false;
                }
            }
            downloaded_files[rel] = std::move(fresp.body);
        }
    }
    return install_from_manifest(mf, manifest_url, downloaded_files, false, true, out_error);
}

bool PluginManager::install_from_manifest(const json::Value& manifest_obj,
                                          const std::string& source_url,
                                          const std::map<std::string, std::string>& preloaded_files,
                                          bool official,
                                          bool preserve_enabled,
                                          std::string& out_error) {
    if (!validate_manifest(manifest_obj, out_error)) return false;
    const std::string id = manifest_obj.get("id")->as_str();
    const std::string target_dir = installed_dir_ + "/" + id;
    if (!ensure_dir_p(target_dir)) {
        out_error = "Failed to create plugin directory";
        return false;
    }

    for (const auto& kv : preloaded_files) {
        if (!is_valid_rel_path(kv.first)) {
            out_error = "Invalid file path: " + kv.first;
            return false;
        }
        const std::string fpath = target_dir + "/" + kv.first;
        const bool is_sh = (kv.first.size() >= 3 && kv.first.substr(kv.first.size() - 3) == ".sh");
        if (!write_text_atomic(fpath, kv.second, is_sh ? 0755 : 0644)) {
            out_error = "Failed to write plugin file: " + kv.first;
            return false;
        }
    }
    if (!write_text_atomic(target_dir + "/plugin.json", manifest_obj.dump(2) + "\n", 0644)) {
        out_error = "Failed to save plugin.json";
        return false;
    }

    std::function<void()> on_changed;
    {
        std::lock_guard<std::mutex> lk(mu_);
        const int64_t now_s = static_cast<int64_t>(std::time(nullptr));
        auto defaults = default_settings_from_manifest(manifest_obj);

        auto it = std::find_if(installed_.begin(), installed_.end(),
                               [&](const InstalledPluginRecord& r) { return r.id == id; });
        if (it != installed_.end()) {
            for (const auto& kv : it->settings) defaults[kv.first] = kv.second;
            it->settings = std::move(defaults);
            it->manifest = manifest_obj;
            it->source_url = source_url.empty() ? it->source_url : source_url;
            it->official = official;
            it->updated_at = now_s;
            if (!preserve_enabled) it->enabled = true;
            it->last_error.clear();
        } else {
            InstalledPluginRecord rec;
            rec.id = id;
            rec.source_url = source_url;
            rec.enabled = true; // ready immediately after explicit install
            rec.official = official;
            rec.installed_at = now_s;
            rec.updated_at = now_s;
            rec.settings = std::move(defaults);
            rec.manifest = manifest_obj;
            installed_.push_back(std::move(rec));
        }
        save_state_locked();
        on_changed = cb_.on_plugins_changed;
    }
    if (on_changed) on_changed();
    return true;
}

bool PluginManager::uninstall(const std::string& plugin_id, std::string& out_error) {
    if (!is_valid_plugin_id(plugin_id)) {
        out_error = "Invalid plugin id";
        return false;
    }
    std::function<void()> on_changed;
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = std::find_if(installed_.begin(), installed_.end(),
                               [&](const InstalledPluginRecord& r) { return r.id == plugin_id; });
        if (it == installed_.end()) {
            out_error = "Plugin not installed";
            return false;
        }
        installed_.erase(it);
        remove_tree(installed_dir_ + "/" + plugin_id);
        save_state_locked();
        on_changed = cb_.on_plugins_changed;
    }
    if (on_changed) on_changed();
    return true;
}

bool PluginManager::set_enabled(const std::string& plugin_id, bool enabled, std::string& out_error) {
    json::Value hook_runner;
    bool has_hook = false;
    json::Value manifest;
    std::map<std::string, json::Value> settings;
    std::function<void()> on_changed;

    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = std::find_if(installed_.begin(), installed_.end(),
                               [&](const InstalledPluginRecord& r) { return r.id == plugin_id; });
        if (it == installed_.end()) {
            out_error = "Plugin not installed";
            return false;
        }
        it->enabled = enabled;
        it->updated_at = static_cast<int64_t>(std::time(nullptr));
        it->last_error.clear();
        save_state_locked();
        manifest = it->manifest;
        settings = it->settings;
        if (const auto* hooks = manifest.get("hooks"); hooks && hooks->is_obj()) {
            const char* hk = enabled ? "onEnable" : "onDisable";
            if (const auto* hr = hooks->get(hk); hr && hr->is_obj()) {
                hook_runner = *hr;
                has_hook = true;
            }
        }
        on_changed = cb_.on_plugins_changed;
    }

    if (has_hook) {
        std::thread([this, plugin_id, manifest, settings, hook_runner]() {
            json::Value r = execute_runner(plugin_id, manifest, settings, hook_runner, json::Value::make_obj(), nullptr);
            const bool ok = r.get("ok") ? r.get("ok")->as_bool(true) : true;
            const std::string err = (!ok && r.get("error")) ? r.get("error")->as_str() : "";
            record_run_status(plugin_id, ok, err);
        }).detach();
    }
    if (on_changed) on_changed();
    return true;
}

bool PluginManager::update_settings(const std::string& plugin_id,
                                    const json::Value& settings_obj,
                                    std::string& out_error) {
    if (!settings_obj.is_obj()) {
        out_error = "Settings must be a JSON object";
        return false;
    }
    std::function<void()> on_changed;
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = std::find_if(installed_.begin(), installed_.end(),
                               [&](const InstalledPluginRecord& r) { return r.id == plugin_id; });
        if (it == installed_.end()) {
            out_error = "Plugin not installed";
            return false;
        }
        for (const auto& kv : settings_obj.obj_val) {
            if (is_secret_setting(it->manifest, kv.first)) {
                const std::string sv = kv.second.as_str();
                if (sv.empty() || sv.rfind("••••", 0) == 0) {
                    continue; // preserve existing secret unless explicitly overwritten
                }
            }
            it->settings[kv.first] = kv.second;
        }
        it->updated_at = static_cast<int64_t>(std::time(nullptr));
        save_state_locked();
        on_changed = cb_.on_plugins_changed;
    }
    if (on_changed) on_changed();
    return true;
}

void PluginManager::record_run_status(const std::string& plugin_id, bool ok, const std::string& detail) {
    std::lock_guard<std::mutex> lk(mu_);
    for (auto& rec : installed_) {
        if (rec.id != plugin_id) continue;
        rec.last_run_at = static_cast<int64_t>(std::time(nullptr));
        rec.last_status = ok ? "ok" : "error";
        rec.last_error = ok ? "" : detail;
        save_state_locked();
        break;
    }
}

json::Value PluginManager::execute_runner(const std::string& plugin_id,
                                          const json::Value& manifest,
                                          const std::map<std::string, json::Value>& settings,
                                          const json::Value& runner,
                                          const json::Value& args,
                                          const std::atomic<bool>* abort_req) {
    using json::Value;
    Value res = Value::make_obj();
    const std::string rtype = runner.get("type") ? lower_copy(runner.get("type")->as_str()) : "";

    if (rtype == "http") {
        HttpClientRequest req;
        req.method = runner.get("method") ? runner.get("method")->as_str("GET") : "GET";
        req.url = render_template(runner.get("url") ? runner.get("url")->as_str() : "", args, settings, 1);
        req.timeout_ms = std::clamp(runner.get("timeoutMs") ? runner.get("timeoutMs")->as_int(7000) : 7000, 1000, 15000);
        req.verify_tls = !(runner.get("insecure") && runner.get("insecure")->as_bool(false));
        req.max_body_bytes = 256 * 1024;
        req.abort_req = abort_req;

        if (const auto* hdrs = runner.get("headers"); hdrs && hdrs->is_obj()) {
            for (const auto& kv : hdrs->obj_val) {
                req.headers[kv.first] = render_template(kv.second.as_str(), args, settings, 0);
            }
        }
        if (const auto* b = runner.get("body")) {
            if (b->is_obj() || b->is_arr()) {
                req.body = render_template(b->dump(-1), args, settings, 2);
                if (req.headers.find("Content-Type") == req.headers.end() &&
                    req.headers.find("content-type") == req.headers.end()) {
                    req.headers["Content-Type"] = "application/json";
                }
            } else if (b->is_str()) {
                req.body = render_template(b->as_str(), args, settings, 2);
            }
        }
        if (req.url.rfind("http://", 0) != 0 && req.url.rfind("https://", 0) != 0) {
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("Invalid HTTP URL in plugin runner");
            return res;
        }
        HttpClientResponse h = perform_http(req);
        res["ok"] = Value::make_bool(h.ok && h.status_code >= 200 && h.status_code < 400);
        res["statusCode"] = Value::make_int(h.status_code);
        std::string preview = h.body;
        if (preview.size() > 4096) preview.resize(4096);
        res["body"] = Value::make_str(preview);
        Value parsed;
        if (!preview.empty() && json::Parser::parse(preview, parsed)) {
            res["json"] = std::move(parsed);
        }
        if (!res["ok"].as_bool(false)) {
            res["error"] = Value::make_str(!h.error.empty() ? h.error : ("HTTP " + std::to_string(h.status_code)));
        }
        return res;
    }

    if (rtype == "wol") {
        std::string mac_tmpl = runner.get("mac") ? runner.get("mac")->as_str("{{args.mac|{{settings.mac}}}}")
                                                 : "{{args.mac|}}";
        std::string mac = render_template(mac_tmpl, args, settings, 0);
        if (mac.empty()) {
            auto it = settings.find("mac");
            if (it != settings.end()) mac = json_scalar_to_str(it->second);
        }
        std::string bcast = render_template(
            runner.get("broadcast") ? runner.get("broadcast")->as_str("{{settings.broadcast|255.255.255.255}}")
                                    : "255.255.255.255",
            args, settings, 0);
        int port = runner.get("port") ? runner.get("port")->as_int(9) : 9;
        std::string werr;
        bool ok = send_wol_magic_packet(mac, bcast, port, werr);
        res["ok"] = Value::make_bool(ok);
        res["mac"] = Value::make_str(mac);
        res["broadcast"] = Value::make_str(bcast.empty() ? "255.255.255.255" : bcast);
        if (!ok) res["error"] = Value::make_str(werr);
        return res;
    }

    if (rtype == "mqtt") {
        std::string topic = render_template(runner.get("topic") ? runner.get("topic")->as_str() : "", args, settings, 0);
        std::string payload = render_template(runner.get("payload") ? runner.get("payload")->as_str() : "", args, settings, 0);
        bool retain = runner.get("retain") ? runner.get("retain")->as_bool(false) : false;
        std::function<bool(const std::string&, const std::string&, bool)> pub;
        {
            std::lock_guard<std::mutex> lk(mu_);
            pub = cb_.mqtt_publish;
        }
        if (!pub || topic.empty()) {
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str(topic.empty() ? "Empty MQTT topic" : "MQTT unavailable");
            return res;
        }
        bool sent = pub(topic, payload, retain);
        res["ok"] = Value::make_bool(sent);
        res["topic"] = Value::make_str(topic);
        if (!sent) res["error"] = Value::make_str("MQTT broker is not connected");
        return res;
    }

    if (rtype == "station") {
        std::function<Value(const std::string&, const Value&)> exec_builtin;
        {
            std::lock_guard<std::mutex> lk(mu_);
            exec_builtin = cb_.execute_builtin_tool;
        }
        if (!exec_builtin) {
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("Station action dispatcher unavailable");
            return res;
        }
        const auto* steps = runner.get("steps");
        if (!steps || !steps->is_arr() || steps->arr_val.empty()) {
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("No steps configured in station runner");
            return res;
        }
        Value step_results = Value::make_arr();
        bool all_ok = true;
        for (const auto& step : steps->arr_val) {
            if (!step.is_obj()) continue;
            const std::string tname = step.get("tool") ? step.get("tool")->as_str() : "";
            if (!is_safe_station_tool(tname)) {
                all_ok = false;
                Value er = Value::make_obj();
                er["tool"] = Value::make_str(tname);
                er["ok"] = Value::make_bool(false);
                er["error"] = Value::make_str("Tool not permitted in station runner");
                step_results.push(std::move(er));
                continue;
            }
            Value sargs = Value::make_obj();
            if (const auto* raw_args = step.get("args"); raw_args && raw_args->is_obj()) {
                for (const auto& kv : raw_args->obj_val) {
                    if (kv.second.is_str()) {
                        sargs[kv.first] = Value::make_str(render_template(kv.second.as_str(), args, settings, 0));
                    } else {
                        sargs[kv.first] = kv.second;
                    }
                }
            }
            Value r = exec_builtin(tname, sargs);
            if (r.get("ok") && !r.get("ok")->as_bool(true)) all_ok = false;
            step_results.push(std::move(r));
        }
        res["ok"] = Value::make_bool(all_ok);
        res["steps"] = std::move(step_results);
        return res;
    }

    if (rtype == "shell") {
        std::string cmd = runner.get("command") ? runner.get("command")->as_str() : "";
        if (const auto* scr = runner.get("script"); scr && scr->is_str() && is_valid_rel_path(scr->as_str())) {
            cmd = "\"" + shell_bin_path() + "\" \"" + installed_dir_ + "/" + plugin_id + "/" + scr->as_str() + "\"";
        }
        if (cmd.empty()) {
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("Empty shell command");
            return res;
        }
        int timeout_sec = runner.get("timeoutSec") ? runner.get("timeoutSec")->as_int(10) : 10;
        return run_shell_capture(cmd, plugin_id, installed_dir_ + "/" + plugin_id, args, settings, timeout_sec);
    }

    (void)manifest;
    res["ok"] = Value::make_bool(false);
    res["error"] = Value::make_str("Unknown runner type: " + rtype);
    return res;
}

json::Value PluginManager::run_action(const std::string& plugin_id,
                                      const std::string& action_id,
                                      const json::Value& args_obj,
                                      const std::atomic<bool>* abort_req) {
    using json::Value;
    json::Value manifest;
    std::map<std::string, json::Value> settings;
    json::Value runner;
    bool found = false;
    bool is_enabled = false;

    {
        std::lock_guard<std::mutex> lk(mu_);
        for (const auto& rec : installed_) {
            if (rec.id != plugin_id) continue;
            found = true;
            is_enabled = rec.enabled;
            manifest = rec.manifest;
            settings = rec.settings;
            if (const auto* acts = rec.manifest.get("actions"); acts && acts->is_arr()) {
                for (const auto& a : acts->arr_val) {
                    if (a.is_obj() && a.get("id") && a.get("id")->as_str() == action_id && a.get("runner")) {
                        runner = *a.get("runner");
                        break;
                    }
                }
            }
            break;
        }
    }

    Value err = Value::make_obj();
    if (!found) {
        err["ok"] = Value::make_bool(false);
        err["error"] = Value::make_str("Plugin not installed");
        return err;
    }
    if (!is_enabled) {
        err["ok"] = Value::make_bool(false);
        err["error"] = Value::make_str("Plugin is disabled");
        return err;
    }
    if (!runner.is_obj()) {
        err["ok"] = Value::make_bool(false);
        err["error"] = Value::make_str("Action not found");
        return err;
    }
    Value res = execute_runner(plugin_id, manifest, settings, runner, args_obj, abort_req);
    const bool ok = res.get("ok") ? res.get("ok")->as_bool(true) : true;
    const std::string err_str = (!ok && res.get("error")) ? res.get("error")->as_str() : "";
    record_run_status(plugin_id, ok, err_str);
    return res;
}

bool PluginManager::has_tool(const std::string& tool_name) const {
    std::lock_guard<std::mutex> lk(mu_);
    for (const auto& rec : installed_) {
        if (!rec.enabled) continue;
        const auto* tools = rec.manifest.get("tools");
        if (!tools || !tools->is_arr()) continue;
        for (const auto& t : tools->arr_val) {
            if (t.is_obj() && t.get("name") && t.get("name")->as_str() == tool_name) return true;
        }
    }
    return false;
}

json::Value PluginManager::get_assistant_tool_decls() const {
    using json::Value;
    Value arr = Value::make_arr();
    std::lock_guard<std::mutex> lk(mu_);
    for (const auto& rec : installed_) {
        if (!rec.enabled) continue;
        const auto* tools = rec.manifest.get("tools");
        if (!tools || !tools->is_arr()) continue;
        for (const auto& t : tools->arr_val) {
            if (!t.is_obj() || !t.get("name")) continue;
            Value decl = Value::make_obj();
            decl["name"] = Value::make_str(t.get("name")->as_str());
            decl["description"] = Value::make_str(t.get("description") ? t.get("description")->as_str() : "");
            decl["behavior"] = Value::make_str("BLOCKING");
            if (const auto* params = t.get("parameters"); params && params->is_obj() && !params->obj_val.empty()) {
                Value schema = Value::make_obj();
                schema["type"] = Value::make_str("OBJECT");
                schema["properties"] = *params;
                if (const auto* req = t.get("required"); req && req->is_arr() && !req->arr_val.empty()) {
                    schema["required"] = *req;
                }
                decl["parameters"] = std::move(schema);
            }
            arr.push(std::move(decl));
        }
    }
    return arr;
}

std::string PluginManager::get_assistant_prompt_suffix() const {
    std::lock_guard<std::mutex> lk(mu_);
    std::string lines;
    for (const auto& rec : installed_) {
        if (!rec.enabled) continue;
        const auto* tools = rec.manifest.get("tools");
        if (!tools || !tools->is_arr() || tools->arr_val.empty()) continue;
        std::string pname = rec.manifest.get("name") ? rec.manifest.get("name")->as_str() : rec.id;
        lines += "- Плагин [" + pname + "]: ";
        for (size_t i = 0; i < tools->arr_val.size(); ++i) {
            const auto& t = tools->arr_val[i];
            if (!t.is_obj() || !t.get("name")) continue;
            if (i > 0) lines += ", ";
            lines += t.get("name")->as_str();
        }
        if (const auto* hint = rec.manifest.get("assistantHint"); hint && hint->is_str() && !hint->as_str().empty()) {
            lines += " (" + hint->as_str() + ")";
        }
        lines += "\n";
    }
    if (lines.empty()) return "";
    return "Активные плагины станции:\n" + lines;
}

json::Value PluginManager::execute_tool(const std::string& tool_name,
                                        const json::Value& args,
                                        const std::atomic<bool>* abort_req) {
    using json::Value;
    std::string plugin_id;
    json::Value manifest;
    std::map<std::string, json::Value> settings;
    json::Value runner;

    {
        std::lock_guard<std::mutex> lk(mu_);
        for (const auto& rec : installed_) {
            if (!rec.enabled) continue;
            const auto* tools = rec.manifest.get("tools");
            if (!tools || !tools->is_arr()) continue;
            for (const auto& t : tools->arr_val) {
                if (t.is_obj() && t.get("name") && t.get("name")->as_str() == tool_name && t.get("runner")) {
                    plugin_id = rec.id;
                    manifest = rec.manifest;
                    settings = rec.settings;
                    runner = *t.get("runner");
                    break;
                }
            }
            if (!plugin_id.empty()) break;
        }
    }

    if (plugin_id.empty()) {
        Value err = Value::make_obj();
        err["ok"] = Value::make_bool(false);
        err["error"] = Value::make_str("unknown plugin tool");
        return err;
    }

    Value res = execute_runner(plugin_id, manifest, settings, runner, args, abort_req);
    const bool ok = res.get("ok") ? res.get("ok")->as_bool(true) : true;
    const std::string err_str = (!ok && res.get("error")) ? res.get("error")->as_str() : "";
    record_run_status(plugin_id, ok, err_str);
    return res;
}

PluginManager::VoiceMatch PluginManager::match_voice_phrase(const std::string& normalized_text) const {
    VoiceMatch m;
    if (normalized_text.empty()) return m;
    std::lock_guard<std::mutex> lk(mu_);
    for (const auto& rec : installed_) {
        if (!rec.enabled) continue;
        const auto* vcs = rec.manifest.get("voiceCommands");
        if (!vcs || !vcs->is_arr()) continue;
        for (const auto& vc : vcs->arr_val) {
            if (!vc.is_obj() || !vc.get("actionId")) continue;
            const auto* phrases = vc.get("phrases");
            if (!phrases || !phrases->is_arr()) continue;
            for (const auto& p : phrases->arr_val) {
                if (p.is_str() && lower_copy(trim_copy(p.as_str())) == normalized_text) {
                    m.matched = true;
                    m.plugin_id = rec.id;
                    m.action_id = vc.get("actionId")->as_str();
                    if (const auto* r = vc.get("reply")) m.reply_hint = r->as_str();
                    return m;
                }
            }
        }
    }
    return m;
}

void PluginManager::tick_schedules(const std::atomic<bool>* abort_req) {
    struct DueJob {
        std::string id;
        json::Value manifest;
        std::map<std::string, json::Value> settings;
        json::Value runner;
    };
    std::vector<DueJob> jobs;
    const int64_t now_s = static_cast<int64_t>(std::time(nullptr));

    {
        std::lock_guard<std::mutex> lk(mu_);
        for (auto& rec : installed_) {
            if (!rec.enabled) continue;
            const auto* sch = rec.manifest.get("schedule");
            if (!sch || !sch->is_obj() || !sch->get("runner")) continue;
            const int interval = std::max(10, sch->get("intervalSec") ? sch->get("intervalSec")->as_int(0) : 0);
            if (interval < 10) continue;
            if (rec.last_schedule_at == 0 || (now_s - rec.last_schedule_at) >= interval) {
                rec.last_schedule_at = now_s;
                DueJob j;
                j.id = rec.id;
                j.manifest = rec.manifest;
                j.settings = rec.settings;
                j.runner = *sch->get("runner");
                jobs.push_back(std::move(j));
            }
        }
    }

    for (const auto& j : jobs) {
        if (abort_req && abort_req->load()) break;
        json::Value res = execute_runner(j.id, j.manifest, j.settings, j.runner, json::Value::make_obj(), abort_req);
        const bool ok = res.get("ok") ? res.get("ok")->as_bool(true) : true;
        const std::string err = (!ok && res.get("error")) ? res.get("error")->as_str() : "";
        record_run_status(j.id, ok, err);
    }
}

json::Value PluginManager::get_summary_json() const {
    using json::Value;
    std::lock_guard<std::mutex> lk(mu_);
    int enabled_cnt = 0;
    for (const auto& rec : installed_) {
        if (rec.enabled) ++enabled_cnt;
    }
    Value out = Value::make_obj();
    out["installedCount"] = Value::make_int(static_cast<int64_t>(installed_.size()));
    out["enabledCount"] = Value::make_int(enabled_cnt);
    out["catalogCount"] = Value::make_int(
        catalog_items_.is_arr() ? static_cast<int64_t>(catalog_items_.arr_val.size()) : 0);
    return out;
}

json::Value PluginManager::get_marketplace_json(const std::string& repo, const std::string& branch) const {
    using json::Value;
    std::lock_guard<std::mutex> lk(mu_);
    const std::string r = repo.empty() ? "Olegu621/OPENSTATION" : repo;
    const std::string b = branch.empty() ? "main" : branch;

    auto find_installed = [&](const std::string& id) -> const InstalledPluginRecord* {
        for (const auto& rec : installed_) {
            if (rec.id == id) return &rec;
        }
        return nullptr;
    };

    auto serialize_installed = [&](const InstalledPluginRecord& rec, const std::string& cat_ver) -> Value {
        Value o = Value::make_obj();
        o["id"] = Value::make_str(rec.id);
        o["name"] = Value::make_str(rec.manifest.get("name") ? rec.manifest.get("name")->as_str() : rec.id);
        o["version"] = Value::make_str(rec.manifest.get("version") ? rec.manifest.get("version")->as_str() : "1.0.0");
        o["author"] = Value::make_str(rec.manifest.get("author") ? rec.manifest.get("author")->as_str() : "Community");
        o["category"] = Value::make_str(rec.manifest.get("category") ? rec.manifest.get("category")->as_str() : "Утилиты");
        o["icon"] = Value::make_str(rec.manifest.get("icon") ? rec.manifest.get("icon")->as_str() : "🧩");
        o["description"] = Value::make_str(rec.manifest.get("description") ? rec.manifest.get("description")->as_str() : "");
        o["homepage"] = Value::make_str(rec.manifest.get("homepage") ? rec.manifest.get("homepage")->as_str() : "");
        o["installed"] = Value::make_bool(true);
        o["enabled"] = Value::make_bool(rec.enabled);
        o["official"] = Value::make_bool(rec.official);
        o["sourceUrl"] = Value::make_str(rec.source_url);
        o["installedAt"] = Value::make_int(rec.installed_at);
        o["updatedAt"] = Value::make_int(rec.updated_at);
        o["lastRunAt"] = Value::make_int(rec.last_run_at);
        o["lastStatus"] = Value::make_str(rec.last_status);
        o["lastError"] = Value::make_str(rec.last_error);
        const std::string cur_v = o["version"].as_str();
        o["updateAvailable"] = Value::make_bool(!cat_ver.empty() && cat_ver != cur_v);
        if (!cat_ver.empty()) o["latestVersion"] = Value::make_str(cat_ver);

        if (const auto* ss = rec.manifest.get("settingsSchema"); ss && ss->is_arr()) {
            o["settingsSchema"] = *ss;
        } else {
            o["settingsSchema"] = Value::make_arr();
        }

        Value pub_settings = Value::make_obj();
        for (const auto& kv : rec.settings) {
            if (is_secret_setting(rec.manifest, kv.first)) {
                const std::string sv = kv.second.as_str();
                pub_settings[kv.first] = Value::make_str(sv.empty() ? "" : "••••");
            } else {
                pub_settings[kv.first] = kv.second;
            }
        }
        o["settings"] = std::move(pub_settings);

        Value act_arr = Value::make_arr();
        if (const auto* acts = rec.manifest.get("actions"); acts && acts->is_arr()) {
            for (const auto& a : acts->arr_val) {
                if (!a.is_obj() || !a.get("id")) continue;
                Value ao = Value::make_obj();
                ao["id"] = Value::make_str(a.get("id")->as_str());
                ao["label"] = Value::make_str(a.get("label") ? a.get("label")->as_str() : ao["id"].as_str());
                if (const auto* inputs = a.get("inputs"); inputs && inputs->is_arr()) {
                    ao["inputs"] = *inputs;
                }
                act_arr.push(std::move(ao));
            }
        }
        o["actions"] = std::move(act_arr);

        Value tool_names = Value::make_arr();
        if (const auto* tools = rec.manifest.get("tools"); tools && tools->is_arr()) {
            for (const auto& t : tools->arr_val) {
                if (t.is_obj() && t.get("name")) tool_names.push(Value::make_str(t.get("name")->as_str()));
            }
        }
        o["tools"] = std::move(tool_names);
        return o;
    };

    Value installed_arr = Value::make_arr();
    std::map<std::string, std::string> cat_versions;
    if (catalog_items_.is_arr()) {
        for (const auto& ci : catalog_items_.arr_val) {
            if (ci.is_obj() && ci.get("id") && ci.get("version")) {
                cat_versions[ci.get("id")->as_str()] = ci.get("version")->as_str();
            }
        }
    }
    int enabled_cnt = 0;
    for (const auto& rec : installed_) {
        if (rec.enabled) ++enabled_cnt;
        installed_arr.push(serialize_installed(rec, cat_versions[rec.id]));
    }

    Value cat_arr = Value::make_arr();
    if (catalog_items_.is_arr()) {
        for (const auto& ci : catalog_items_.arr_val) {
            if (!ci.is_obj() || !ci.get("id")) continue;
            Value entry = ci;
            const std::string cid = ci.get("id")->as_str();
            const InstalledPluginRecord* rec = find_installed(cid);
            entry["installed"] = Value::make_bool(rec != nullptr);
            entry["enabled"] = Value::make_bool(rec != nullptr && rec->enabled);
            if (rec) {
                const std::string cur_v = rec->manifest.get("version") ? rec->manifest.get("version")->as_str() : "";
                const std::string cat_v = ci.get("version") ? ci.get("version")->as_str() : "";
                entry["installedVersion"] = Value::make_str(cur_v);
                entry["updateAvailable"] = Value::make_bool(!cat_v.empty() && cat_v != cur_v);
            }
            cat_arr.push(std::move(entry));
        }
    }

    Value root = Value::make_obj();
    root["ok"] = Value::make_bool(true);
    root["repo"] = Value::make_str(r);
    root["branch"] = Value::make_str(b);
    root["submitUrl"] = Value::make_str(kDefaultSubmitUrl);
    root["catalogFetchedAt"] = Value::make_int(catalog_fetched_at_);
    root["catalogSource"] = Value::make_str(catalog_source_);
    root["catalogError"] = Value::make_str(catalog_error_);
    root["installedCount"] = Value::make_int(static_cast<int64_t>(installed_.size()));
    root["enabledCount"] = Value::make_int(enabled_cnt);
    root["catalogCount"] = Value::make_int(static_cast<int64_t>(cat_arr.arr_val.size()));
    root["installed"] = std::move(installed_arr);
    root["catalog"] = std::move(cat_arr);
    return root;
}

} // namespace openstation
