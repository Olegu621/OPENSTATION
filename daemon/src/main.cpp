#include "assistant/assistant_manager.hpp"
#include "assistant/timer_manager.hpp"
#include "assistant/tls_ws_client.hpp"
#include "config.hpp"
#include "hw/alsa_mixer.hpp"
#include "hw/audio_engine.hpp"
#include "hw/framebuffer_hw.hpp"
#include "hw/input_monitor.hpp"
#include "hw/uinput_keys.hpp"
#include "net/adb_manager.hpp"
#include "net/http_client.hpp"
#include "net/mdns_responder.hpp"
#include "net/mqtt_client.hpp"
#include "net/web_tools.hpp"
#include "ota/ota_manager.hpp"
#include "utils/json_min.hpp"
#include "utils/logger.hpp"
#include "web/http_ws_server.hpp"
#include "wyoming/wyoming_server.hpp"

#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <malloc.h>
#include <mutex>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/statvfs.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

constexpr const char* kFirmwareVersion = "1.3.2";

std::atomic<bool> g_stop{false};

void on_signal(int) {
    g_stop.store(true);
}

bool is_udp_port_bound(int port) {
    char needle[16];
    snprintf(needle, sizeof(needle), ":%04X ", static_cast<unsigned int>(port) & 0xFFFF);
    const char* files[] = {"/proc/net/udp", "/proc/net/udp6"};
    for (const char* path : files) {
        FILE* f = fopen(path, "r");
        if (!f) continue;
        char line[512];
        // Skip header line
        if (fgets(line, sizeof(line), f)) {
            while (fgets(line, sizeof(line), f)) {
                if (strstr(line, needle) != nullptr) {
                    fclose(f);
                    return true;
                }
            }
        }
        fclose(f);
    }
    return false;
}

bool send_udp_json(int port, const std::string& payload) {
    static std::mutex s_mu;
    static int s_fd = -1;
    std::lock_guard<std::mutex> lk(s_mu);
    if (s_fd < 0) {
        s_fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (s_fd < 0) return false;
    }
    struct sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    ssize_t n = sendto(s_fd, payload.data(), payload.size(), 0,
                       reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
    return n == static_cast<ssize_t>(payload.size());
}

bool spawn_detached(const std::vector<std::string>& args);

bool ensure_launcher_service_udp(int port = 8086) {
    if (is_udp_port_bound(port)) return true;
    LOGI("Main", "StationAudioService UDP %d not bound yet; starting foreground service...", port);
    spawn_detached({
        "/system/bin/am", "start-foreground-service",
        "-n", "com.openstation.launcher/.StationAudioService"
    });
    for (int i = 0; i < 30 && !g_stop.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(80));
        if (is_udp_port_bound(port)) {
            LOGI("Main", "StationAudioService UDP %d ready after %d ms", port, (i + 1) * 80);
            return true;
        }
    }
    LOGW("Main", "Timed out waiting for StationAudioService UDP %d", port);
    return is_udp_port_bound(port);
}

struct MusicPlaybackState {
    bool playing = false;
    bool paused = false;
    bool buffering = false;
    bool ducked = false;
    std::string source = "idle";
    std::string source_label = "Яндекс Музыка";
    std::string wave_mood = "any";
    std::string quality = "normal";
    std::string codec_label;
    std::string track_id;
    std::string title;
    std::string artist;
    std::string album;
    std::string cover_url;
    int64_t duration_ms = 0;
    int64_t position_ms = 0;
    bool liked = false;
    bool has_auth = false;
    std::string user_login;
    bool has_plus = false;
    std::string error;
    uint64_t seq = 0;
    int64_t updated_ms = 0;

    openstation::json::Value to_json() const {
        using openstation::json::Value;
        Value o = Value::make_obj();
        o["playing"] = Value::make_bool(playing);
        o["paused"] = Value::make_bool(paused);
        o["buffering"] = Value::make_bool(buffering);
        o["ducked"] = Value::make_bool(ducked);
        o["sourceType"] = Value::make_str(source);
        o["sourceLabel"] = Value::make_str(source_label);
        o["waveMood"] = Value::make_str(wave_mood);
        o["quality"] = Value::make_str(quality);
        o["codecLabel"] = Value::make_str(codec_label);
        o["trackId"] = Value::make_str(track_id);
        o["title"] = Value::make_str(title);
        o["artist"] = Value::make_str(artist);
        o["album"] = Value::make_str(album);
        o["coverUrl"] = Value::make_str(cover_url);
        o["durationMs"] = Value::make_int(duration_ms);
        o["positionMs"] = Value::make_int(position_ms);
        o["liked"] = Value::make_bool(liked);
        o["hasAuth"] = Value::make_bool(has_auth);
        o["userLogin"] = Value::make_str(user_login);
        o["hasPlus"] = Value::make_bool(has_plus);
        o["error"] = Value::make_str(error);
        o["seq"] = Value::make_int(static_cast<int64_t>(seq));
        o["updatedMs"] = Value::make_int(updated_ms);
        return o;
    }

    void update_from_json(const openstation::json::Value& v, int64_t now_ms) {
        if (const auto* p = v.get("playing")) playing = p->as_bool(playing);
        if (const auto* p = v.get("paused")) paused = p->as_bool(paused);
        if (const auto* p = v.get("buffering")) buffering = p->as_bool(buffering);
        if (const auto* p = v.get("ducked")) ducked = p->as_bool(ducked);
        if (const auto* p = v.get("sourceType"); p && p->is_str()) source = p->as_str();
        if (const auto* p = v.get("sourceLabel"); p && p->is_str()) source_label = p->as_str();
        if (const auto* p = v.get("waveMood"); p && p->is_str()) wave_mood = p->as_str();
        if (const auto* p = v.get("quality"); p && p->is_str()) quality = p->as_str();
        if (const auto* p = v.get("codecLabel"); p && p->is_str()) codec_label = p->as_str();
        if (const auto* p = v.get("trackId"); p && p->is_str()) track_id = p->as_str();
        if (const auto* p = v.get("title"); p && p->is_str()) title = p->as_str();
        if (const auto* p = v.get("artist"); p && p->is_str()) artist = p->as_str();
        if (const auto* p = v.get("album"); p && p->is_str()) album = p->as_str();
        if (const auto* p = v.get("coverUrl"); p && p->is_str()) cover_url = p->as_str();
        if (const auto* p = v.get("durationMs"); p && p->is_num()) duration_ms = p->as_i64(duration_ms);
        if (const auto* p = v.get("positionMs"); p && p->is_num()) position_ms = p->as_i64(position_ms);
        if (const auto* p = v.get("liked")) liked = p->as_bool(liked);
        if (const auto* p = v.get("hasAuth")) has_auth = p->as_bool(has_auth);
        if (const auto* p = v.get("userLogin"); p && p->is_str()) user_login = p->as_str();
        if (const auto* p = v.get("hasPlus")) has_plus = p->as_bool(has_plus);
        if (const auto* p = v.get("error"); p && p->is_str()) error = p->as_str();
        ++seq;
        updated_ms = now_ms;
    }
};

bool write_cec_hex(const std::string& bytes_hex) {
    FILE* f = fopen("/sys/class/cec/cmd", "w");
    if (!f) return false;
    bool ok = (fprintf(f, "%s", bytes_hex.c_str()) >= 0);
    if (fflush(f) != 0) ok = false;
    if (fclose(f) != 0) ok = false;
    return ok;
}

bool is_valid_output_route(const std::string& r) {
    return r == "auto" || r == "speakers" || r == "headphones" || r == "both" || r == "hdmi";
}

bool is_valid_ring_mode(const std::string& m) {
    return m == "off" || m == "solid" || m == "breathe" || m == "spinner" ||
           m == "rainbow" || m == "music" || m == "voice" || m == "spectrum";
}

bool is_valid_screen_mode(const std::string& m) {
    return m == "off" || m == "clock" || m == "text" || m == "spectrum" || m == "snake";
}

bool contains_case_insensitive(const std::string& hay, const std::string& needle) {
    if (needle.empty()) return false;
    auto it = std::search(hay.begin(), hay.end(), needle.begin(), needle.end(),
                          [](char a, char b) {
                              return std::tolower(static_cast<unsigned char>(a)) ==
                                     std::tolower(static_cast<unsigned char>(b));
                          });
    return it != hay.end();
}

bool is_valid_package_name(const std::string& pkg) {
    if (pkg.empty() || pkg.size() > 128) return false;
    if (pkg.front() == '.' || pkg.back() == '.' || pkg.find('.') == std::string::npos ||
        pkg.find("..") != std::string::npos) {
        return false;
    }
    for (unsigned char c : pkg) {
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '.' || c == '_')) {
            return false;
        }
    }
    return true;
}

bool spawn_detached(const std::vector<std::string>& args) {
    if (args.empty()) return false;
    pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) {
        pid_t pid2 = fork();
        if (pid2 == 0) {
            setsid();
            int nullfd = open("/dev/null", O_RDWR);
            if (nullfd >= 0) {
                dup2(nullfd, STDIN_FILENO);
                dup2(nullfd, STDOUT_FILENO);
                dup2(nullfd, STDERR_FILENO);
                if (nullfd > 2) ::close(nullfd);
            }
            for (int fd = 3; fd < 1024; ++fd) ::close(fd);
            std::vector<char*> c_args;
            c_args.reserve(args.size() + 1);
            for (const auto& s : args) c_args.push_back(const_cast<char*>(s.c_str()));
            c_args.push_back(nullptr);
            execvp(c_args[0], c_args.data());
            _exit(127);
        }
        _exit(0);
    }
    int st = 0;
    while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
    return true;
}

std::string normalize_cec_action(const std::string& raw) {
    if (raw == "wakeup") return "wake";
    if (raw == "volume_up") return "vol_up";
    if (raw == "volume_down") return "vol_down";
    return raw;
}

void exec_hdmi_cec_action(const std::string& raw_act) {
    std::string act = normalize_cec_action(raw_act);
    unsigned int la = 4;
    if (FILE* f = fopen("/sys/class/cec/log_addr", "r")) {
        unsigned int v = 4;
        if (fscanf(f, "%x", &v) == 1 && v < 15) la = v;
        fclose(f);
    }
    unsigned int p0 = 0x10, p1 = 0x00;
    if (FILE* f = fopen("/sys/class/cec/physical_addr", "r")) {
        unsigned int pa = 0x1000;
        if (fscanf(f, "%x", &pa) == 1) {
            p0 = (pa >> 8) & 0xFF;
            p1 = pa & 0xFF;
        }
        fclose(f);
    }
    char cmd[48];
    if (act == "wake") {
        snprintf(cmd, sizeof(cmd), "%X0 04", la);
        write_cec_hex(cmd);
        usleep(80000);
        snprintf(cmd, sizeof(cmd), "%XF 82 %02X %02X", la, p0, p1);
        write_cec_hex(cmd);
    } else if (act == "standby") {
        snprintf(cmd, sizeof(cmd), "%X0 36", la);
        write_cec_hex(cmd);
    } else if (act == "vol_up" || act == "vol_down" || act == "mute") {
        unsigned int key = (act == "vol_up") ? 0x41 : ((act == "vol_down") ? 0x42 : 0x43);
        snprintf(cmd, sizeof(cmd), "%X0 44 %02X", la, key);
        write_cec_hex(cmd);
        usleep(40000);
        snprintf(cmd, sizeof(cmd), "%X0 45", la);
        write_cec_hex(cmd);
    }
}

std::string run_cmd_capture(const char* cmd) {
    std::string out;
    FILE* fp = popen(cmd, "r");
    if (!fp) return out;
    char buf[512];
    while (fgets(buf, sizeof(buf), fp)) out += buf;
    pclose(fp);
    return out;
}

std::string get_query_param(const std::string& query, const std::string& key) {
    size_t pos = 0;
    while (pos < query.size()) {
        size_t amp = query.find('&', pos);
        if (amp == std::string::npos) amp = query.size();
        size_t eq = query.find('=', pos);
        if (eq != std::string::npos && eq < amp && query.substr(pos, eq - pos) == key) {
            return query.substr(eq + 1, amp - eq - 1);
        }
        pos = amp + 1;
    }
    return "";
}

std::string b64_encode_str(const std::string& s) {
    static const char* kTbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    for (size_t i = 0; i < s.size(); i += 3) {
        uint32_t v = static_cast<uint8_t>(s[i]) << 16;
        if (i + 1 < s.size()) v |= static_cast<uint8_t>(s[i + 1]) << 8;
        if (i + 2 < s.size()) v |= static_cast<uint8_t>(s[i + 2]);
        out.push_back(kTbl[(v >> 18) & 0x3F]);
        out.push_back(kTbl[(v >> 12) & 0x3F]);
        out.push_back(i + 1 < s.size() ? kTbl[(v >> 6) & 0x3F] : '=');
        out.push_back(i + 2 < s.size() ? kTbl[v & 0x3F] : '=');
    }
    return out;
}

bool is_valid_eq_preset(const std::string& p) {
    return p == "balanced" || p == "flat" || p == "bass_boost" || p == "vocal" ||
           p == "night" || p == "rock" || p == "electronic" || p == "custom";
}

bool is_valid_clock_style(const std::string& s) {
    return s == "classic" || s == "minimal" || s == "bold" || s == "seconds" ||
           s == "binary" || s == "neon" || s == "retro";
}

bool is_valid_visualizer_style(const std::string& s) {
    return s == "nexus" || s == "specterr" || s == "anomaly" ||
           s == "aurora" || s == "bars" || s == "wave" ||
           s == "fireflies" || s == "matrix" || s == "particles";
}

std::string url_decode_str(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    auto hex_val = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '+') {
            out.push_back(' ');
        } else if (s[i] == '%' && i + 2 < s.size()) {
            int hi = hex_val(s[i + 1]);
            int lo = hex_val(s[i + 2]);
            if (hi >= 0 && lo >= 0) {
                out.push_back(static_cast<char>((hi << 4) | lo));
                i += 2;
                continue;
            }
            out.push_back(s[i]);
        } else {
            out.push_back(s[i]);
        }
    }
    return out;
}

std::string normalize_voice_name(const std::string& raw, const std::string& current_voice) {
    static const char* kAllVoices[] = {"Aoede", "Kore", "Leda", "Zephyr", "Puck", "Charon", "Fenrir", "Orus"};
    static const char* kFemaleVoices[] = {"Aoede", "Kore", "Leda", "Zephyr"};
    static const char* kMaleVoices[] = {"Charon", "Fenrir", "Puck", "Orus"};

    std::string low;
    low.reserve(raw.size());
    for (unsigned char ch : raw) {
        if (ch < 128) low.push_back(static_cast<char>(std::tolower(ch)));
        else low.push_back(static_cast<char>(ch));
    }
    for (const char* v : kAllVoices) {
        std::string lv;
        for (const char* p = v; *p; ++p) lv.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(*p))));
        if (low.find(lv) != std::string::npos) return v;
    }
    if (low.find("нежный") != std::string::npos || low.find("мягк") != std::string::npos ||
        low.find("kore") != std::string::npos) return "Kore";
    if (low.find("тепл") != std::string::npos || low.find("тёпл") != std::string::npos) return "Aoede";
    if (low.find("выраз") != std::string::npos) return "Leda";
    if (low.find("ярк") != std::string::npos || low.find("бодр") != std::string::npos) return "Zephyr";
    if (low.find("глубок") != std::string::npos || low.find("спокой") != std::string::npos ||
        low.find("низк") != std::string::npos) return "Charon";
    if (low.find("энерг") != std::string::npos) return "Fenrir";
    if (low.find("дружел") != std::string::npos) return "Puck";
    if (low.find("уверен") != std::string::npos) return "Orus";

    if (low.find("муж") != std::string::npos || low.find("male") != std::string::npos ||
        low.find("парен") != std::string::npos || low.find("парн") != std::string::npos) {
        for (const char* v : kMaleVoices) {
            if (current_voice != v) return v;
        }
        return "Charon";
    }
    if (low.find("жен") != std::string::npos || low.find("female") != std::string::npos ||
        low.find("девуш") != std::string::npos) {
        for (const char* v : kFemaleVoices) {
            if (current_voice != v) return v;
        }
        return "Aoede";
    }
    for (size_t i = 0; i < sizeof(kAllVoices) / sizeof(kAllVoices[0]); ++i) {
        if (current_voice == kAllVoices[i]) {
            return kAllVoices[(i + 1) % (sizeof(kAllVoices) / sizeof(kAllVoices[0]))];
        }
    }
    return "Aoede";
}

size_t find_nearest_eq_band(int freq_hz) {
    static constexpr int kCenters[10] = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    size_t best_idx = 0;
    double best_dist = 1e9;
    double lf = std::log2(static_cast<double>(std::max(10, freq_hz)));
    for (size_t i = 0; i < 10; ++i) {
        double d = std::abs(lf - std::log2(static_cast<double>(kCenters[i])));
        if (d < best_dist) {
            best_dist = d;
            best_idx = i;
        }
    }
    return best_idx;
}

void parse_hex_rgb(const std::string& hex, uint8_t& r, uint8_t& g, uint8_t& b) {
    r = 0; g = 220; b = 255;
    std::string s = hex;
    if (!s.empty() && s[0] == '#') s.erase(s.begin());
    if (s.size() == 6) {
        char* end = nullptr;
        unsigned long v = std::strtoul(s.c_str(), &end, 16);
        if (end && *end == '\0') {
            r = static_cast<uint8_t>((v >> 16) & 0xFF);
            g = static_cast<uint8_t>((v >> 8) & 0xFF);
            b = static_cast<uint8_t>(v & 0xFF);
        }
    }
}

void dispatch_remote_text(const std::string& txt) {
    static std::atomic<uint64_t> text_seq{1};
    std::string seq = std::to_string(static_cast<long>(getpid())) + "_" + std::to_string(text_seq.fetch_add(1));
    openstation::json::Value payload = openstation::json::Value::make_obj();
    payload["cmd"] = openstation::json::Value::make_str("text");
    payload["seq"] = openstation::json::Value::make_str(seq);
    payload["text"] = openstation::json::Value::make_str(txt);
    send_udp_json(8085, payload.dump(-1));
    if (!is_udp_port_bound(8085)) {
        // Broadcast fallback only if IME service isn't bound to UDP 8085 yet
        std::string b64 = b64_encode_str(txt);
        spawn_detached({
            "/system/bin/am", "broadcast",
            "-a", "com.openstation.ime.COMMIT_TEXT",
            "--es", "seq", seq,
            "--es", "b64", b64
        });
    }
}

std::string read_first_line(const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) return "";
    char buf[256] = {};
    std::string out;
    if (fgets(buf, sizeof(buf), f)) {
        out = buf;
        while (!out.empty() && (out.back() == '\n' || out.back() == '\r' || out.back() == ' ')) {
            out.pop_back();
        }
    }
    fclose(f);
    return out;
}

struct ShellResult {
    int exit_code = -1;
    bool timed_out = false;
    std::string output;
};

ShellResult run_shell_with_timeout(const std::string& command, int timeout_sec, size_t max_bytes = 8192) {
    ShellResult sr;
    int pipefd[2] = {-1, -1};
    if (pipe(pipefd) != 0) {
        sr.output = std::string("pipe() failed: ") + strerror(errno);
        return sr;
    }

    pid_t pid = fork();
    if (pid < 0) {
        sr.output = std::string("fork() failed: ") + strerror(errno);
        ::close(pipefd[0]);
        ::close(pipefd[1]);
        return sr;
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
        execl("/system/bin/sh", "sh", "-c", command.c_str(), nullptr);
        _exit(127);
    }

    ::close(pipefd[1]);
    int fl = fcntl(pipefd[0], F_GETFL, 0);
    if (fl >= 0) fcntl(pipefd[0], F_SETFL, fl | O_NONBLOCK);

    int tsec = std::clamp(timeout_sec, 1, 30);
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(tsec);
    std::string raw;
    raw.reserve(4096);
    bool truncated = false;
    const size_t capture_cap = max_bytes * 2;
    char buf[2048];

    while (!g_stop.load()) {
        auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                        deadline - std::chrono::steady_clock::now())
                        .count();
        if (left <= 0) {
            sr.timed_out = true;
            break;
        }
        struct pollfd pfd {};
        pfd.fd = pipefd[0];
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, static_cast<int>(std::min<int64_t>(left, 150)));
        if (pr > 0) {
            ssize_t n = read(pipefd[0], buf, sizeof(buf));
            if (n > 0) {
                if (raw.size() < capture_cap) {
                    size_t take = std::min(static_cast<size_t>(n), capture_cap - raw.size());
                    raw.append(buf, take);
                    if (take < static_cast<size_t>(n)) truncated = true;
                } else {
                    truncated = true;
                }
                continue;
            }
            if (n == 0) break; // EOF
            if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) break;
        } else if (pr < 0 && errno != EINTR) {
            break;
        }
    }

    ::close(pipefd[0]);

    int st = 0;
    pid_t wp = waitpid(pid, &st, sr.timed_out || g_stop.load() ? WNOHANG : 0);
    if (wp == 0) {
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
        while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
        sr.timed_out = true;
    } else if (wp > 0) {
        if (WIFEXITED(st)) sr.exit_code = WEXITSTATUS(st);
        else if (WIFSIGNALED(st)) sr.exit_code = 128 + WTERMSIG(st);
    }

    sr.output = openstation::ensure_utf8_and_truncate(raw, max_bytes);
    if (truncated) sr.output += "\n...[truncated]";
    return sr;
}

bool ensure_hardvol_passthrough_active(openstation::AlsaMixer& mixer) {
    long woo_chk = 0;
    if (!mixer.get_int("Woofer Master Playback Volume", woo_chk)) {
        return false; // Not an SY6045S Station Max board
    }

    const char* kXmlPath = "/vendor/etc/hardvol_sy.xml";
    std::string cur;
    if (FILE* f = fopen(kXmlPath, "rb")) {
        char buf[1024];
        size_t n = 0;
        while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
            cur.append(buf, n);
            if (cur.size() >= 64 * 1024) break;
        }
        fclose(f);
    } else {
        return false;
    }

    if (cur.find("OPENSTATION_PASSTHROUGH") != std::string::npos) {
        return false;
    }

    LOGI("Audio", "Migrating %s to OPENSTATION_PASSTHROUGH so audio-hal never overwrites SY6045S curve...", kXmlPath);
    const std::string cmd = R"SH(
mkdir -p /data/local/openstation/backup
if [ -f /vendor/etc/hardvol_sy.xml ] && ! grep -q "OPENSTATION_" /vendor/etc/hardvol_sy.xml 2>/dev/null; then
    if [ ! -f /data/local/openstation/backup/hardvol_sy.xml ] || grep -q "OPENSTATION_" /data/local/openstation/backup/hardvol_sy.xml 2>/dev/null; then
        cp -f /vendor/etc/hardvol_sy.xml /data/local/openstation/backup/hardvol_sy.xml 2>/dev/null || true
        chmod 644 /data/local/openstation/backup/hardvol_sy.xml 2>/dev/null || true
    fi
fi
cat <<'EOF' > /data/local/openstation/hardvol_passthrough.xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- OPENSTATION_PASSTHROUGH: OpenStation Max daemon drives SY6045S/ES8156 curve + trims -->
<hardvol>
</hardvol>
EOF
chmod 644 /data/local/openstation/hardvol_passthrough.xml 2>/dev/null || true

applied=0
if mount -o rw,remount /vendor 2>/dev/null || mount -o remount,rw /vendor 2>/dev/null; then
    if cp -f /data/local/openstation/hardvol_passthrough.xml /vendor/etc/hardvol_sy.xml.tmp 2>/dev/null; then
        chown 0:0 /vendor/etc/hardvol_sy.xml.tmp 2>/dev/null || true
        chmod 644 /vendor/etc/hardvol_sy.xml.tmp 2>/dev/null || true
        mv -f /vendor/etc/hardvol_sy.xml.tmp /vendor/etc/hardvol_sy.xml 2>/dev/null || true
        chcon u:object_r:vendor_configs_file:s0 /vendor/etc/hardvol_sy.xml 2>/dev/null || true
        restorecon /vendor/etc/hardvol_sy.xml 2>/dev/null || true
        sync
    fi
    mount -o ro,remount /vendor 2>/dev/null || mount -o remount,ro /vendor 2>/dev/null || true
    grep -q "OPENSTATION_PASSTHROUGH" /vendor/etc/hardvol_sy.xml 2>/dev/null && applied=1
fi

if [ "$applied" -eq 0 ]; then
    mount -o bind /data/local/openstation/hardvol_passthrough.xml /vendor/etc/hardvol_sy.xml 2>/dev/null || true
    grep -q "OPENSTATION_PASSTHROUGH" /vendor/etc/hardvol_sy.xml 2>/dev/null && applied=2
fi

if [ "$applied" -ne 0 ]; then
    h_st=$(getprop init.svc.vendor.audio-hal-2-0 2>/dev/null | tr -d '\r\n')
    a_st=$(getprop init.svc.audioserver 2>/dev/null | tr -d '\r\n')
    if [ -n "$h_st" ] || [ -n "$a_st" ]; then
        stop vendor.audio-hal-2-0 2>/dev/null || true
        stop audioserver 2>/dev/null || true
        sleep 1
        start vendor.audio-hal-2-0 2>/dev/null || true
        start audioserver 2>/dev/null || true
        i=0
        while [ $i -lt 15 ]; do
            h=$(getprop init.svc.vendor.audio-hal-2-0 2>/dev/null | tr -d '\r\n')
            a=$(getprop init.svc.audioserver 2>/dev/null | tr -d '\r\n')
            [ "$h" = "running" ] && [ "$a" = "running" ] && break
            i=$((i+1))
            sleep 1
        done
    fi
    echo "PASSTHROUGH_OK:$applied"
else
    echo "PASSTHROUGH_FAIL"
fi
)SH";
    ShellResult sr = run_shell_with_timeout(cmd, 25, 2048);
    if (sr.output.find("PASSTHROUGH_OK:") != std::string::npos) {
        LOGI("Audio", "hardvol passthrough active (%s)", sr.output.c_str());
        return true;
    }
    LOGW("Audio", "hardvol passthrough migration failed (rc=%d, out=%s)", sr.exit_code, sr.output.c_str());
    return false;
}

std::string trim_ws_copy(const std::string& s) {
    size_t b = 0;
    while (b < s.size() && (s[b] == ' ' || s[b] == '\t' || s[b] == '\r' || s[b] == '\n')) ++b;
    size_t e = s.size();
    while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t' || s[e - 1] == '\r' || s[e - 1] == '\n')) --e;
    return s.substr(b, e - b);
}

bool is_valid_dns_host(const std::string& host) {
    if (host.empty() || host.size() > 128) return false;
    for (unsigned char c : host) {
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '.' || c == '-')) {
            return false;
        }
    }
    return true;
}

openstation::json::Value probe_gemini_live_json(const std::string& api_key_in,
                                                const std::string& model_in,
                                                const std::string& proxy_host_in) {
    using namespace openstation;
    using json::Value;

    std::string key = trim_ws_copy(api_key_in);
    std::string model = trim_ws_copy(model_in);
    if (model.empty()) model = "gemini-3.8-live";
    if (model.rfind("models/", 0) != 0) model = "models/" + model;
    std::string proxy_host = trim_ws_copy(proxy_host_in);

    Value res = Value::make_obj();
    if (key.empty()) {
        res["ok"] = Value::make_bool(false);
        res["via"] = Value::make_str("");
        res["latencyMs"] = Value::make_int(0);
        res["code"] = Value::make_int(0);
        res["error"] = Value::make_str("Укажите Gemini API Key");
        return res;
    }

    ensure_sntp_time(1500);

    std::string path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + key;
    Value gen_cfg = Value::make_obj();
    Value mods = Value::make_arr();
    mods.push(Value::make_str("AUDIO"));
    gen_cfg["responseModalities"] = std::move(mods);
    Value setup_inner = Value::make_obj();
    setup_inner["model"] = Value::make_str(model);
    setup_inner["generationConfig"] = std::move(gen_cfg);
    Value setup_msg = Value::make_obj();
    setup_msg["setup"] = std::move(setup_inner);
    std::string setup_payload = setup_msg.dump(-1);

    std::vector<std::string> targets;
    targets.push_back(""); // direct first
    if (!proxy_host.empty()) targets.push_back(proxy_host);

    std::string last_err;
    std::string last_via = "direct";
    int last_code = 0;
    int64_t last_ms = 0;

    for (size_t idx = 0; idx < targets.size(); ++idx) {
        const std::string& host_override = targets[idx];
        last_via = host_override.empty() ? "direct" : host_override;
        auto t0 = std::chrono::steady_clock::now();

        TlsWsClient ws;
        std::string err;
        if (!ws.connect("generativelanguage.googleapis.com", 443, path,
                        "/data/local/openstation/cacert.pem", err, 5500, host_override)) {
            last_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          std::chrono::steady_clock::now() - t0).count();
            last_err = err.empty() ? "TLS connect failed" : err;
            last_code = 0;
            continue;
        }
        if (!ws.send_text(setup_payload)) {
            ws.close();
            last_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          std::chrono::steady_clock::now() - t0).count();
            last_err = "Failed to send Gemini setup";
            last_code = 0;
            continue;
        }
        WsFrame fr;
        std::string rerr;
        WsRecvStatus st = ws.recv_frame(fr, 5000, rerr);
        ws.close();
        last_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::steady_clock::now() - t0).count();

        if (st == WsRecvStatus::Frame) {
            Value j;
            if (json::Parser::parse(fr.payload, j) && j.is_obj()) {
                if (const auto* eo = j.get("error"); eo && eo->is_obj()) {
                    const auto* msgp = eo->get("message");
                    last_err = (msgp && msgp->is_str()) ? msgp->as_str() : "Gemini API error";
                    last_code = 400;
                    std::string low = last_err;
                    for (char& c : low) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                    if (low.find("location") != std::string::npos ||
                        low.find("region") != std::string::npos ||
                        low.find("country") != std::string::npos) {
                        continue;
                    }
                    break;
                }
            }
            res["ok"] = Value::make_bool(true);
            res["via"] = Value::make_str(last_via);
            res["latencyMs"] = Value::make_int(last_ms);
            res["code"] = Value::make_int(0);
            res["error"] = Value::make_str("");
            return res;
        } else if (st == WsRecvStatus::Closed) {
            last_code = static_cast<int>(fr.close_code);
            std::string reason = fr.payload.size() > 2 ? fr.payload.substr(2) : rerr;
            last_err = reason.empty() ? ("WebSocket closed (" + std::to_string(last_code) + ")") : reason;
            std::string low = last_err;
            for (char& c : low) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            bool geo = (last_code == 1007) ||
                       (low.find("location") != std::string::npos) ||
                       (low.find("country") != std::string::npos) ||
                       (low.find("region") != std::string::npos);
            if (!geo) {
                break; // Clear auth / invalid key / model error: no need to mask with proxy failure
            }
        } else {
            last_code = 0;
            last_err = rerr.empty() ? "Timeout waiting for Gemini setupComplete" : rerr;
        }
    }

    res["ok"] = Value::make_bool(false);
    res["via"] = Value::make_str(last_via);
    res["latencyMs"] = Value::make_int(last_ms);
    res["code"] = Value::make_int(last_code);
    res["error"] = Value::make_str(last_err);
    return res;
}

int interpolate_piecewise(int pct, const int pts[][2], size_t n) {
    if (pct <= pts[0][0]) return pts[0][1];
    if (pct >= pts[n - 1][0]) return pts[n - 1][1];
    for (size_t i = 0; i + 1 < n; ++i) {
        int x0 = pts[i][0], y0 = pts[i][1];
        int x1 = pts[i + 1][0], y1 = pts[i + 1][1];
        if (pct >= x0 && pct <= x1) {
            int dx = x1 - x0;
            if (dx <= 0) return y1;
            double t = static_cast<double>(pct - x0) / static_cast<double>(dx);
            return static_cast<int>(std::lround(y0 + t * (y1 - y0)));
        }
    }
    return pts[n - 1][1];
}

int curve_base_speaker_reg(int pct) {
    static constexpr int kCurve[][2] = {
        {0, 3}, {1, 112}, {2, 118}, {5, 131}, {10, 146},
        {20, 166}, {35, 188}, {50, 208}, {70, 229}, {85, 243}, {100, 255}
    };
    return interpolate_piecewise(std::clamp(pct, 0, 100), kCurve, sizeof(kCurve) / sizeof(kCurve[0]));
}

int curve_base_headphone_reg(int pct) {
    static constexpr int kCurve[][2] = {
        {0, 0}, {1, 60}, {5, 78}, {10, 94}, {20, 112},
        {35, 132}, {50, 150}, {70, 170}, {85, 182}, {100, 193}
    };
    return interpolate_piecewise(std::clamp(pct, 0, 100), kCurve, sizeof(kCurve) / sizeof(kCurve[0]));
}

void compute_amp_curve(int pct, const openstation::AudioConfig& acfg,
                       int& out_woo, int& out_twe, int& out_hp) {
    int p = std::clamp(pct, 0, 100);
    if (p <= 0) {
        out_woo = 3;
        out_twe = 3;
        out_hp = 0;
        return;
    }
    int base_spk = curve_base_speaker_reg(p);
    int base_hp = curve_base_headphone_reg(p);
    out_woo = std::clamp(base_spk + std::clamp(acfg.woofer_trim, -40, 40), 3, 255);
    out_twe = std::clamp(base_spk + std::clamp(acfg.tweeters_trim, -40, 40), 3, 255);
    out_hp = std::clamp(base_hp + std::clamp(acfg.headphone_trim, -40, 40), 1, 255);
}

bool read_android_setting_from_xml(const std::string& xml, const std::string& key, int& out_val) {
    if (xml.empty()) return false;
    std::string pat1 = "name=\"" + key + "\"";
    std::string pat2 = "name='" + key + "'";
    size_t pos = xml.find(pat1);
    if (pos == std::string::npos) pos = xml.find(pat2);
    if (pos == std::string::npos) return false;
    size_t tag_start = xml.rfind('<', pos);
    size_t tag_end = xml.find('>', pos);
    if (tag_start == std::string::npos || tag_end == std::string::npos || tag_end <= tag_start) return false;
    std::string tag = xml.substr(tag_start, tag_end - tag_start + 1);
    size_t vp = tag.find("value=\"");
    char quote = '"';
    if (vp == std::string::npos) {
        vp = tag.find("value='");
        quote = '\'';
    }
    if (vp == std::string::npos) return false;
    vp += 7;
    size_t ve = tag.find(quote, vp);
    if (ve == std::string::npos || ve == vp) return false;
    std::string raw = tag.substr(vp, ve - vp);
    char* endp = nullptr;
    long v = std::strtol(raw.c_str(), &endp, 10);
    if (!endp || *endp != '\0') return false;
    out_val = static_cast<int>(v);
    return true;
}

void probe_android_stream_music(const std::string& route, bool hp_plugged, int& out_pct, int& out_max) {
    int max_steps = 15;
    char prop_steps[PROP_VALUE_MAX] = {};
    if (__system_property_get("ro.config.media_vol_steps", prop_steps) > 0) {
        int v = std::atoi(prop_steps);
        if (v >= 1 && v <= 200) max_steps = v;
    }
    out_max = max_steps;
    out_pct = 10;

    std::vector<const char*> keys;
    if (route == "headphones" || (route == "auto" && hp_plugged)) {
        keys = {"volume_music_headset", "volume_music_headphone", "volume_music_speaker", "volume_music"};
    } else {
        keys = {"volume_music_speaker", "volume_music", "volume_music_headset"};
    }

    char boot_prop[PROP_VALUE_MAX] = {};
    bool boot_done = (__system_property_get("sys.boot_completed", boot_prop) > 0 && strcmp(boot_prop, "1") == 0);
    if (boot_done) {
        for (const char* k : keys) {
            std::string cmd = std::string("/system/bin/settings get system ") + k + " 2>/dev/null";
            ShellResult sr = run_shell_with_timeout(cmd, 2, 128);
            std::string val = trim_ws_copy(sr.output);
            if (!sr.timed_out && sr.exit_code == 0 && !val.empty() && val != "null") {
                char* endp = nullptr;
                long cur = std::strtol(val.c_str(), &endp, 10);
                if (endp && *endp == '\0' && cur >= 0) {
                    out_pct = (cur <= 0)
                                  ? 0
                                  : std::clamp(static_cast<int>(std::lround(cur * 100.0 / out_max)), 1, 100);
                    return;
                }
            }
        }
    }

    std::string xml;
    if (FILE* f = fopen("/data/system/users/0/settings_system.xml", "rb")) {
        char buf[4096];
        size_t n = 0;
        while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
            xml.append(buf, n);
            if (xml.size() >= 256 * 1024) break;
        }
        fclose(f);
    }
    if (!xml.empty()) {
        for (const char* k : keys) {
            int cur = -1;
            if (read_android_setting_from_xml(xml, k, cur) && cur >= 0) {
                out_pct = (cur <= 0)
                              ? 0
                              : std::clamp(static_cast<int>(std::lround(cur * 100.0 / out_max)), 1, 100);
                return;
            }
        }
    }
}

} // namespace

int main(int argc, char** argv) {
    // Enable jemalloc 10s dirty-page decay on Android Bionic so periodic ONNX Runtime inference
    // reuses heap pages instead of churning madvise/minor page faults on every audio chunk.
    mallopt(M_DECAY_TIME, 1);

    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--version") == 0 || strcmp(argv[i], "-v") == 0) {
            printf("openstationd %s\n", kFirmwareVersion);
            return 0;
        }
        if (strcmp(argv[i], "--probe-audio") == 0) {
            openstation::AudioEngine::run_cli_probe();
            return 0;
        }
        if (strcmp(argv[i], "--probe-gemini") == 0) {
            using namespace openstation;
            std::string key = (i + 1 < argc) ? argv[i + 1] : "";
            std::string path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + key;
            std::vector<std::string> targets;
            for (int j = i + 2; j < argc; ++j) targets.emplace_back(argv[j]);
            if (targets.empty()) targets.push_back("");
            for (const auto& t : targets) {
                TlsWsClient ws;
                std::string err;
                const char* label = t.empty() ? "(system DNS)" : t.c_str();
                if (!ws.connect("generativelanguage.googleapis.com", 443, path, "/data/local/openstation/cacert.pem", err, 6000, t)) {
                    printf("PROBE [%s]: connect FAIL -> %s\n", label, err.c_str());
                    continue;
                }
                const char* setup_s = "{\"setup\":{\"model\":\"models/gemini-3.8-live\",\"generationConfig\":{\"responseModalities\":[\"AUDIO\"]}}}";
                if (!ws.send_text(setup_s)) {
                    printf("PROBE [%s]: send setup FAIL\n", label);
                    continue;
                }
                WsFrame fr;
                std::string rerr;
                WsRecvStatus st = ws.recv_frame(fr, 5000, rerr);
                if (st == WsRecvStatus::Frame) {
                    printf("PROBE [%s]: FRAME op=%u payload=%s\n", label, fr.opcode, fr.payload.c_str());
                } else if (st == WsRecvStatus::Closed) {
                    std::string reason = fr.payload.size() > 2 ? fr.payload.substr(2) : rerr;
                    printf("PROBE [%s]: CLOSED code=%u reason=%s\n", label, fr.close_code, reason.c_str());
                } else {
                    printf("PROBE [%s]: STATUS %d err=%s\n", label, static_cast<int>(st), rerr.c_str());
                }
                ws.close();
            }
            return 0;
        }
        if (strcmp(argv[i], "--probe-http") == 0) {
            using namespace openstation;
            for (int j = i + 1; j < argc; ++j) {
                HttpClientRequest req;
                req.url = argv[j];
                req.timeout_ms = 8000;
                HttpClientResponse r = http_perform(req);
                printf("HTTP [%s] -> ok=%d status=%d ct=%s final=%s err=%s len=%zu\n",
                       argv[j], r.ok ? 1 : 0, r.status_code, r.content_type.c_str(),
                       r.final_url.c_str(), r.error.c_str(), r.body.size());
                std::string preview = ensure_utf8_and_truncate(r.body, 400, r.content_type);
                printf("BODY: %s\n---\n", preview.c_str());
            }
            return 0;
        }
        if (strcmp(argv[i], "--probe-web") == 0) {
            using namespace openstation;
            std::string city = (i + 1 < argc) ? argv[i + 1] : "Москва";
            std::string query = (i + 2 < argc) ? argv[i + 2] : "курс доллара ЦБ";
            printf("=== WEATHER(%s) ===\n%s\n", city.c_str(), tool_get_weather(city, 2).dump(2).c_str());
            printf("=== SEARCH(%s) ===\n%s\n", query.c_str(), tool_web_search(query, 4).dump(2).c_str());
            return 0;
        }
        if (strcmp(argv[i], "--daemon") == 0) {
            pid_t p1 = fork();
            if (p1 < 0) return 1;
            if (p1 > 0) _exit(0);
            setsid();
            pid_t p2 = fork();
            if (p2 < 0) _exit(1);
            if (p2 > 0) _exit(0);
            int fd_in = open("/dev/null", O_RDONLY);
            if (fd_in >= 0) {
                dup2(fd_in, STDIN_FILENO);
                if (fd_in > 2) ::close(fd_in);
            }
            int fd_out = open("/data/local/openstation/boot.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
            if (fd_out >= 0) {
                dup2(fd_out, STDOUT_FILENO);
                dup2(fd_out, STDERR_FILENO);
                if (fd_out > 2) ::close(fd_out);
            }
            for (int fd = 3; fd < 1024; ++fd) ::close(fd);
            break;
        }
    }

    tzset();
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    using namespace openstation;
    Logger::instance().init("/data/local/openstation/openstationd.log");
    LOGI("Main", "=== OpenStation Max Daemon v%s starting ===", kFirmwareVersion);

    DaemonConfig cfg;
    cfg.load();
    std::mutex cfg_mu;
    std::mutex save_mu;
    auto save_cfg = [&](const DaemonConfig& c) {
        std::lock_guard<std::mutex> lk(save_mu);
        c.save();
    };

    // Cache of launchable apps pushed by OpenStationLauncher (or fallback pm list)
    std::mutex apps_mu;
    json::Value apps_cache = json::Value::make_arr();

    // Live native Yandex Music state pushed by StationAudioService (/api/music/state)
    std::mutex music_mu;
    MusicPlaybackState music_state;
    std::atomic<bool> music_duck_active{false};
    auto set_music_duck = [&](bool duck) {
        if (music_duck_active.exchange(duck) != duck) {
            if (is_udp_port_bound(8086)) {
                json::Value dcmd = json::Value::make_obj();
                dcmd["cmd"] = json::Value::make_str("music_duck");
                dcmd["duck"] = json::Value::make_bool(duck);
                send_udp_json(8086, dcmd.dump(-1));
            }
        }
    };
    auto get_music_json = [&]() -> json::Value {
        std::lock_guard<std::mutex> lk(music_mu);
        return music_state.to_json();
    };

    // System Android STREAM_MUSIC volume percentage (synced with Android settings/StationAudioService)
    std::atomic<int> volume_osd_pct{10};
    std::atomic<int> android_vol_max{100};
    std::atomic<int64_t> last_daemon_vol_cmd_ms{0};
    auto steady_ms_now = []() -> int64_t {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
    };
    auto start_tp = std::chrono::steady_clock::now();

    std::mutex dns_mu;
    std::string private_dns_mode_cache;
    std::string private_dns_host_cache;
    bool dns_late_probed = false;
    auto refresh_private_dns_cache = [&]() {
        std::string m = trim_ws_copy(run_cmd_capture("/system/bin/settings get global private_dns_mode 2>/dev/null"));
        std::string h = trim_ws_copy(run_cmd_capture("/system/bin/settings get global private_dns_specifier 2>/dev/null"));
        if (m == "null") m.clear();
        if (h == "null") h.clear();
        std::lock_guard<std::mutex> lk(dns_mu);
        private_dns_mode_cache = std::move(m);
        private_dns_host_cache = std::move(h);
    };
    refresh_private_dns_cache();

    AlsaMixer mixer;
    FramebufferHw leds;
    UinputKeys uinput;
    InputMonitor inputs;
    AudioEngine audio;
    AssistantManager assistant;
    TimerManager timers;
    WyomingServer wyoming;
    MdnsResponder mdns;
    AdbManager adb_mgr;
    MqttClient mqtt;
    OtaManager ota;
    HttpWsServer http;

    for (int attempt = 0; attempt < 5 && !g_stop.load(); ++attempt) {
        if (mixer.init(0)) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
    }
    ensure_hardvol_passthrough_active(mixer);
    bool hp_init = false;
    mixer.get_bool("Headphones Jack", hp_init);
    {
        int init_pct = 10;
        int init_max = 100;
        probe_android_stream_music(cfg.audio.output_route, hp_init, init_pct, init_max);
        volume_osd_pct.store(init_pct);
        android_vol_max.store(init_max);
        LOGI("Audio", "Initial Android volume probe: %d%% (maxSteps=%d, hp=%d)",
             init_pct, init_max, hp_init ? 1 : 0);
    }

    uinput.init();
    leds.start(cfg.led_ring, cfg.led_screen);

    std::mutex vol_apply_mu;
    int last_applied_woo = -1;
    int last_applied_twe = -1;
    int last_applied_hp = -1;
    bool has_last_mute_state = false;
    bool last_applied_muted = false;
    std::atomic<bool> first_vol_state_seen{false};

    // Must hold vol_apply_mu; preserves lock order vol_apply_mu -> cfg_mu -> mixer::mu_.
    auto apply_curve_unlocked = [&](int pct,
                                    bool force = false,
                                    bool manage_route_switches = true,
                                    bool force_route_apply = false,
                                    int* out_woo = nullptr,
                                    int* out_twe = nullptr,
                                    int* out_hp = nullptr) {
        int p = std::clamp(pct, 0, 100);
        int woo = 3, twe = 3, hp = 0;
        std::string route_snap;
        {
            std::lock_guard<std::mutex> clk(cfg_mu);
            compute_amp_curve(p, cfg.audio, woo, twe, hp);
            cfg.audio.woofer_volume = woo;
            cfg.audio.tweeters_volume = twe;
            cfg.audio.headphone_volume = hp;
            route_snap = cfg.audio.output_route;
        }
        bool hp_plugged = inputs.headphone_plugged();
        bool want_muted = (p <= 0);
        if (want_muted) {
            if (force_route_apply) {
                mixer.apply_output_route(route_snap, hp_plugged, true);
                last_applied_muted = true;
                has_last_mute_state = true;
            } else if (manage_route_switches && (force || !has_last_mute_state || !last_applied_muted)) {
                mixer.set_bool("Woofer Master Playback Switch", false);
                mixer.set_bool("Tweeters Master Playback Switch", false);
                mixer.set_bool("Headphone Headphone Switch", false);
                last_applied_muted = true;
                has_last_mute_state = true;
            } else if (!manage_route_switches) {
                last_applied_muted = true;
                has_last_mute_state = true;
            }
            if (force || woo != last_applied_woo) {
                if (mixer.set_int("Woofer Master Playback Volume", woo, -1)) {
                    last_applied_woo = woo;
                }
            }
            if (force || twe != last_applied_twe) {
                if (mixer.set_int("Tweeters Master Playback Volume", twe, -1)) {
                    last_applied_twe = twe;
                }
            }
            if (force || hp != last_applied_hp) {
                if (mixer.set_int("Headphone DAC Playback Volume", hp, -1)) {
                    last_applied_hp = hp;
                }
            }
        } else {
            if (force || woo != last_applied_woo) {
                if (mixer.set_int("Woofer Master Playback Volume", woo, -1)) {
                    last_applied_woo = woo;
                }
            }
            if (force || twe != last_applied_twe) {
                if (mixer.set_int("Tweeters Master Playback Volume", twe, -1)) {
                    last_applied_twe = twe;
                }
            }
            if (force || hp != last_applied_hp) {
                if (mixer.set_int("Headphone DAC Playback Volume", hp, -1)) {
                    last_applied_hp = hp;
                }
            }
            if (force_route_apply || (manage_route_switches && (!has_last_mute_state || last_applied_muted))) {
                mixer.apply_output_route(route_snap, hp_plugged, false);
                last_applied_muted = false;
                has_last_mute_state = true;
            } else if (!manage_route_switches) {
                last_applied_muted = false;
                has_last_mute_state = true;
            }
        }
        if (out_woo) *out_woo = woo;
        if (out_twe) *out_twe = twe;
        if (out_hp) *out_hp = hp;
    };

    auto reapply_current_volume_and_route = [&](bool force_vol = true, bool apply_route_now = true) {
        std::lock_guard<std::mutex> lk(vol_apply_mu);
        int cur_pct = volume_osd_pct.load();
        apply_curve_unlocked(cur_pct, force_vol, apply_route_now, apply_route_now);
    };

    auto reconcile_unlocked = [&]() {
        if (last_applied_woo < 0 || last_applied_twe < 0 || last_applied_hp < 0) {
            return;
        }
        int cur_pct = volume_osd_pct.load();
        int exp_woo = 3, exp_twe = 3, exp_hp = 0;
        std::string route_snap;
        {
            std::lock_guard<std::mutex> clk(cfg_mu);
            compute_amp_curve(cur_pct, cfg.audio, exp_woo, exp_twe, exp_hp);
            cfg.audio.woofer_volume = exp_woo;
            cfg.audio.tweeters_volume = exp_twe;
            cfg.audio.headphone_volume = exp_hp;
            route_snap = cfg.audio.output_route;
        }
        bool hp_plugged = inputs.headphone_plugged();
        bool want_muted = (cur_pct <= 0);
        bool want_spk = !want_muted &&
                        (route_snap == "speakers" || route_snap == "both" ||
                         (route_snap == "auto" && !hp_plugged));
        bool want_hp = !want_muted &&
                       (route_snap == "headphones" || route_snap == "both" ||
                        (route_snap == "auto" && hp_plugged));
        long hw_w = -1, hw_t = -1, hw_h = -1;
        if (!mixer.get_int("Woofer Master Playback Volume", hw_w) ||
            !mixer.get_int("Tweeters Master Playback Volume", hw_t) ||
            !mixer.get_int("Headphone DAC Playback Volume", hw_h)) {
            return;
        }
        bool sw_w = want_spk, sw_t = want_spk, sw_h = want_hp;
        mixer.get_bool("Woofer Master Playback Switch", sw_w);
        mixer.get_bool("Tweeters Master Playback Switch", sw_t);
        mixer.get_bool("Headphone Headphone Switch", sw_h);
        if (hw_w != exp_woo || hw_t != exp_twe || hw_h != exp_hp ||
            sw_w != want_spk || sw_t != want_spk || sw_h != want_hp) {
            LOGW("Audio", "Reconciling external ALSA drift (vol %ld/%ld/%ld -> %d/%d/%d, sw %d/%d/%d -> %d/%d/%d)",
                 hw_w, hw_t, hw_h, exp_woo, exp_twe, exp_hp,
                 sw_w ? 1 : 0, sw_t ? 1 : 0, sw_h ? 1 : 0,
                 want_spk ? 1 : 0, want_spk ? 1 : 0, want_hp ? 1 : 0);
            mixer.set_int("Woofer Master Playback Volume", exp_woo, -1);
            mixer.set_int("Tweeters Master Playback Volume", exp_twe, -1);
            mixer.set_int("Headphone DAC Playback Volume", exp_hp, -1);
            mixer.apply_output_route(route_snap, hp_plugged, want_muted);
        }
        last_applied_woo = exp_woo;
        last_applied_twe = exp_twe;
        last_applied_hp = exp_hp;
        last_applied_muted = want_muted;
        has_last_mute_state = true;
    };

    auto reconcile_volume_if_drifted = [&]() {
        std::lock_guard<std::mutex> lk(vol_apply_mu);
        reconcile_unlocked();
    };

    std::atomic<bool> ext_music_playing{false};
    auto update_led_music_playing = [&]() {
        bool ym_playing = false;
        {
            std::lock_guard<std::mutex> lk(music_mu);
            ym_playing = music_state.playing && !music_state.paused;
        }
        leds.set_music_playing(ym_playing || ext_music_playing.load());
    };

    auto push_eq_to_launcher = [&](const DaemonConfig& c) {
        if (!is_udp_port_bound(8086)) return;
        json::Value lcmd = json::Value::make_obj();
        lcmd["cmd"] = json::Value::make_str("eq");
        lcmd["preset"] = json::Value::make_str(c.audio.eq_preset);
        lcmd["bassBoost"] = json::Value::make_int(c.audio.bass_boost);
        json::Value bands = json::Value::make_arr();
        for (float g : c.audio.eq_bands) {
            bands.push(json::Value::make_num(g));
        }
        lcmd["bands"] = std::move(bands);
        send_udp_json(8086, lcmd.dump(-1));
    };

    auto push_style_sync_to_launcher = [&](const DaemonConfig& c) {
        if (!is_udp_port_bound(8086)) return;
        json::Value scmd = json::Value::make_obj();
        scmd["cmd"] = json::Value::make_str("style_sync");
        scmd["clockStyle"] = json::Value::make_str(c.led_screen.clock_style);
        scmd["visualizerStyle"] = json::Value::make_str(c.led_screen.visualizer_style);
        scmd["autoMusicVisualizer"] = json::Value::make_bool(c.led_screen.auto_music_visualizer);
        scmd["eqPreset"] = json::Value::make_str(c.audio.eq_preset);
        scmd["bassBoost"] = json::Value::make_int(c.audio.bass_boost);
        json::Value bands = json::Value::make_arr();
        for (float g : c.audio.eq_bands) {
            bands.push(json::Value::make_num(g));
        }
        scmd["eqBands"] = std::move(bands);
        std::string loc_city = !c.location.city.empty() ? c.location.city : c.location.resolved_city;
        scmd["locationCity"] = json::Value::make_str(loc_city);
        send_udp_json(8086, scmd.dump(-1));
    };

    std::mutex last_weather_mu;
    json::Value last_weather_json = json::Value::make_obj();
    bool has_last_weather = false;
    auto trigger_weather_visuals = [&](const json::Value& w, bool show_visuals = true) {
        if (!w.get("ok") || !w.get("ok")->as_bool(false)) return;
        const json::Value* cur = w.get("current");
        if (!cur || !cur->is_obj()) cur = &w;

        int temp_i = 0;
        if (const auto* tn = cur->get("tempNum"); tn && tn->is_num()) {
            temp_i = static_cast<int>(std::lround(tn->as_double(0.0)));
        } else if (const auto* tc = cur->get("tempC"); tc && tc->is_str()) {
            temp_i = static_cast<int>(std::lround(std::atof(tc->as_str().c_str())));
        }
        std::string cond_code = cur->get("conditionCode") ? cur->get("conditionCode")->as_str("clear") : "clear";
        std::string cond_str = cur->get("condition") ? cur->get("condition")->as_str() : "";
        std::string city_str = w.get("city") ? w.get("city")->as_str() : "";

        json::Value flat = json::Value::make_obj();
        flat["ok"] = json::Value::make_bool(true);
        flat["city"] = json::Value::make_str(city_str);
        if (const auto* sc = w.get("country")) flat["country"] = *sc;
        if (const auto* sl = w.get("stationLocation")) flat["stationLocation"] = *sl;
        if (const auto* pv = w.get("provider")) flat["provider"] = *pv;
        flat["tempNum"] = json::Value::make_int(temp_i);
        flat["tempC"] = cur->get("tempC") ? *cur->get("tempC") : json::Value::make_str(std::to_string(temp_i));
        if (const auto* fln = cur->get("feelsLikeNum")) flat["feelsLikeNum"] = *fln;
        if (const auto* flc = cur->get("feelsLikeC")) flat["feelsLikeC"] = *flc;
        flat["conditionCode"] = json::Value::make_str(cond_code);
        flat["condition"] = json::Value::make_str(cond_str);
        if (const auto* hm = cur->get("humidityPct")) flat["humidityPct"] = *hm;
        if (const auto* wm = cur->get("windMs")) flat["windMs"] = *wm;
        if (const auto* fc = w.get("forecast")) flat["forecast"] = *fc;
        flat["current"] = *cur;
        flat["updatedEpoch"] = json::Value::make_int(static_cast<int64_t>(std::time(nullptr)));

        {
            std::lock_guard<std::mutex> lk(last_weather_mu);
            last_weather_json = flat;
            has_last_weather = true;
        }
        if (show_visuals) {
            leds.trigger_weather(temp_i, cond_code, 12000);
            json::Value u = flat;
            u["cmd"] = json::Value::make_str("weather_event");
            u["durationMs"] = json::Value::make_int(15000);
            send_udp_json(8086, u.dump(-1));
            if (http.ws_client_count() > 0) {
                json::Value ws_evt = json::Value::make_obj();
                ws_evt["type"] = json::Value::make_str("weather_event");
                ws_evt["data"] = flat;
                http.broadcast_ws_text(ws_evt.dump(-1));
            }
        }
    };

    auto apply_hw_config = [&](const DaemonConfig& c, bool /*hp_plugged*/) {
        leds.update_config(c.led_ring, c.led_screen);
        leds.set_mic_muted(c.audio.mic_muted);

        mixer.set_bool("Woofer EQ Switch", c.audio.woofer_eq);
        mixer.set_bool("Woofer DRC Switch", c.audio.woofer_drc);
        mixer.set_bool("Tweeters EQ Switch", c.audio.tweeters_eq);
        mixer.set_bool("Tweeters DRC Switch", c.audio.tweeters_drc);
        mixer.set_int("PDM HCIC shift gain (~ 6dB * value)", c.audio.pdm_gain, 0);
        mixer.set_enum_idx("PDM Filter Mode", static_cast<unsigned int>(c.audio.pdm_filter));
        mixer.set_enum("Loopback Enable", c.audio.loopback_enable ? "Enable" : "Disable");
        reapply_current_volume_and_route(true, true);

        audio.update_config(c.audio);
        audio.set_mic_muted(c.audio.mic_muted);
        assistant.update_config(c.assistant);
        assistant.set_mic_muted(c.audio.mic_muted);
        assistant.set_mic_sensitivity(c.audio.mic_sensitivity);
        wyoming.update_config(c.wyoming);
        mdns.update_hostname(c.device_hostname);
        adb_mgr.update_config(c.network, c.device_hostname);
        mqtt.update_config(c.mqtt);
        ota.update_config(c.ota);

        push_eq_to_launcher(c);
        push_style_sync_to_launcher(c);
    };

    auto set_android_volume_pct = [&](int pct,
                                      bool show_osd,
                                      int* out_woo = nullptr,
                                      int* out_twe = nullptr,
                                      int* out_hp = nullptr) {
        int clamped = std::clamp(pct, 0, 100);
        int prev = clamped;
        bool sent_udp = false;
        {
            std::lock_guard<std::mutex> lk(vol_apply_mu);
            last_daemon_vol_cmd_ms.store(steady_ms_now());
            prev = volume_osd_pct.exchange(clamped);
            apply_curve_unlocked(clamped, true, true, false, out_woo, out_twe, out_hp);
            if (is_udp_port_bound(8086)) {
                json::Value vcmd = json::Value::make_obj();
                vcmd["cmd"] = json::Value::make_str("volume_set");
                vcmd["percent"] = json::Value::make_int(clamped);
                vcmd["showUi"] = json::Value::make_bool(false);
                sent_udp = send_udp_json(8086, vcmd.dump(-1));
                last_daemon_vol_cmd_ms.store(steady_ms_now());
            }
        }
        if (show_osd) {
            leds.trigger_volume_osd(clamped);
        }
        if (!sent_udp) {
            int max_s = std::max(1, android_vol_max.load());
            int cur_step = (prev * max_s + 50) / 100;
            int tgt_step = (clamped * max_s + 50) / 100;
            int diff = tgt_step - cur_step;
            for (int s = 0; s < std::abs(diff); ++s) {
                uinput.send_key(diff > 0 ? 115 : 114);
            }
            last_daemon_vol_cmd_ms.store(steady_ms_now());
        }
        return clamped;
    };

    auto step_android_volume_pct = [&](int delta_pct,
                                       bool show_osd,
                                       int* out_woo = nullptr,
                                       int* out_twe = nullptr,
                                       int* out_hp = nullptr) {
        int clamped = 0;
        int prev = 0;
        bool sent_udp = false;
        {
            std::lock_guard<std::mutex> lk(vol_apply_mu);
            last_daemon_vol_cmd_ms.store(steady_ms_now());
            prev = volume_osd_pct.load();
            clamped = std::clamp(prev + delta_pct, 0, 100);
            volume_osd_pct.store(clamped);
            apply_curve_unlocked(clamped, true, true, false, out_woo, out_twe, out_hp);
            if (is_udp_port_bound(8086)) {
                json::Value vcmd = json::Value::make_obj();
                vcmd["cmd"] = json::Value::make_str("volume_set");
                vcmd["percent"] = json::Value::make_int(clamped);
                vcmd["showUi"] = json::Value::make_bool(false);
                sent_udp = send_udp_json(8086, vcmd.dump(-1));
                last_daemon_vol_cmd_ms.store(steady_ms_now());
            }
        }
        if (show_osd) {
            leds.trigger_volume_osd(clamped);
        }
        if (!sent_udp) {
            int max_s = std::max(1, android_vol_max.load());
            int cur_step = (prev * max_s + 50) / 100;
            int tgt_step = (clamped * max_s + 50) / 100;
            int diff = tgt_step - cur_step;
            for (int s = 0; s < std::abs(diff); ++s) {
                uinput.send_key(diff > 0 ? 115 : 114);
            }
            last_daemon_vol_cmd_ms.store(steady_ms_now());
        }
        return clamped;
    };

    inputs.start([&](HwEventType ev) {
        DaemonConfig c;
        {
            std::lock_guard<std::mutex> lk(cfg_mu);
            c = cfg;
        }
        switch (ev) {
            case HwEventType::RotaryUp:
            case HwEventType::RotaryDown: {
                bool up = (ev == HwEventType::RotaryUp);
                if (c.input.rotary_invert) up = !up;
                int steps = std::max(1, std::min(5, c.input.rotary_step));
                int max_steps = std::max(1, android_vol_max.load());
                int delta_pct = std::max(1, (100 * steps + max_steps / 2) / max_steps);
                int v = 0;
                bool sent_udp = false;
                {
                    std::lock_guard<std::mutex> lk(vol_apply_mu);
                    last_daemon_vol_cmd_ms.store(steady_ms_now());
                    v = std::clamp(volume_osd_pct.load() + (up ? delta_pct : -delta_pct), 0, 100);
                    volume_osd_pct.store(v);
                    apply_curve_unlocked(v, false, true, false);
                    if (is_udp_port_bound(8086)) {
                        json::Value vcmd = json::Value::make_obj();
                        vcmd["cmd"] = json::Value::make_str("volume_step");
                        vcmd["dir"] = json::Value::make_int(up ? 1 : -1);
                        vcmd["steps"] = json::Value::make_int(steps);
                        vcmd["showUi"] = json::Value::make_bool(false);
                        sent_udp = send_udp_json(8086, vcmd.dump(-1));
                        last_daemon_vol_cmd_ms.store(steady_ms_now());
                    }
                }
                leds.trigger_volume_osd(v);
                if (!sent_udp) {
                    for (int s = 0; s < steps; ++s) {
                        uinput.send_key(up ? 115 : 114); // KEY_VOLUMEUP / KEY_VOLUMEDOWN
                    }
                    last_daemon_vol_cmd_ms.store(steady_ms_now());
                }
                break;
            }
            case HwEventType::MuteToggle: {
                DaemonConfig next_c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    cfg.audio.mic_muted = !cfg.audio.mic_muted;
                    next_c = cfg;
                }
                audio.set_mic_muted(next_c.audio.mic_muted);
                assistant.set_mic_muted(next_c.audio.mic_muted);
                leds.set_mic_muted(next_c.audio.mic_muted);
                save_cfg(next_c);
                LOGI("Main", "Mic mute toggled -> %s", next_c.audio.mic_muted ? "MUTED" : "LIVE");
                break;
            }
            case HwEventType::ActionShort:
            case HwEventType::ActionLong: {
                const std::string& act = (ev == HwEventType::ActionShort)
                                             ? c.input.action_short
                                             : c.input.action_long;
                if (act == "assistant") {
                    if (c.assistant.enabled && c.assistant.mode == "gemini") {
                        assistant.toggle_session();
                    } else if (c.wyoming.enabled) {
                        if (wyoming.is_streaming()) wyoming.stop_session();
                        else wyoming.trigger_button_session(6000);
                    }
                } else if (act == "wyoming") {
                    if (wyoming.is_streaming()) wyoming.stop_session();
                    else wyoming.trigger_button_session(6000);
                } else if (act == "play_pause") {
                    if (is_udp_port_bound(8086)) {
                        json::Value mcmd = json::Value::make_obj();
                        mcmd["cmd"] = json::Value::make_str("music_action");
                        mcmd["action"] = json::Value::make_str("PLAY_PAUSE");
                        send_udp_json(8086, mcmd.dump(-1));
                    } else {
                        uinput.send_android_action("play_pause");
                    }
                    leds.trigger_flash(0, 212, 255, 250);
                } else if (act == "led_cycle") {
                    static const char* kModes[] = {"rainbow", "solid", "breathe", "spinner", "music", "off"};
                    DaemonConfig next_c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        int next_i = 0;
                        for (int i = 0; i < 6; ++i) {
                            if (cfg.led_ring.mode == kModes[i]) { next_i = (i + 1) % 6; break; }
                        }
                        cfg.led_ring.mode = kModes[next_i];
                        next_c = cfg;
                    }
                    leds.update_config(next_c.led_ring, next_c.led_screen);
                    leds.trigger_flash(124, 58, 237, 250);
                    save_cfg(next_c);
                } else if (act == "launcher") {
                    uinput.send_android_action("home");
                }
                break;
            }
            case HwEventType::JackPlugged:
            case HwEventType::JackUnplugged: {
                reapply_current_volume_and_route(true, c.audio.output_route == "auto");
                break;
            }
        }
    });

    // Apply loaded hardware configuration right away
    apply_hw_config(cfg, inputs.headphone_plugged());

    std::thread geo_thread([&]() {
        bool need_geo = false;
        {
            std::lock_guard<std::mutex> lk(cfg_mu);
            int64_t age = static_cast<int64_t>(std::time(nullptr)) - cfg.location.resolved_epoch;
            need_geo = cfg.location.auto_detect &&
                       (cfg.location.resolved_city.empty() || age < 0 || age > 21600);
        }
        if (!need_geo) return;
        for (int i = 0; i < 10 && !g_stop.load(); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(150));
        }
        for (int attempt = 0; attempt < 3 && !g_stop.load(); ++attempt) {
            GeoLocationResult geo = detect_location_by_ip(&g_stop);
            if (geo.ok && !geo.city.empty()) {
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    cfg.location.resolved_city = geo.city;
                    cfg.location.resolved_country = geo.country;
                    cfg.location.resolved_epoch = static_cast<int64_t>(std::time(nullptr));
                    if (cfg.location.city.empty()) {
                        cfg.location.lat = geo.lat;
                        cfg.location.lon = geo.lon;
                    }
                    cfg.sanitize_and_validate();
                    c = cfg;
                }
                save_cfg(c);
                push_style_sync_to_launcher(c);
                LOGI("Location", "Auto-detected station city: %s (%s, %.3f, %.3f)",
                     geo.city.c_str(), geo.country.c_str(), geo.lat, geo.lon);
                return;
            }
            for (int w = 0; w < 25 && !g_stop.load(); ++w) {
                std::this_thread::sleep_for(std::chrono::milliseconds(160));
            }
        }
    });

    timers.start([&](const TimerEntry& t) {
        leds.trigger_flash(255, 140, 0, 2200);
        leds.trigger_screen_text("ТАЙМЕР: " + t.label, 255, 165, 0, 5000);
        audio.play_timer_alarm();
        if (http.ws_client_count() > 0) {
            json::Value evt = json::Value::make_obj();
            evt["type"] = json::Value::make_str("timer_fired");
            evt["id"] = json::Value::make_int(t.id);
            evt["label"] = json::Value::make_str(t.label);
            evt["totalSec"] = json::Value::make_int(t.total_sec);
            http.broadcast_ws_text(evt.dump(-1));
        }
    });

    auto list_apps_json_arr = [&]() -> json::Value {
        using json::Value;
        Value arr = Value::make_arr();
        {
            Value ym = Value::make_obj();
            ym["package"] = Value::make_str("com.openstation.launcher/.YandexMusicActivity");
            ym["label"] = Value::make_str("Яндекс Музыка · Моя волна");
            arr.push(std::move(ym));
        }
        {
            std::lock_guard<std::mutex> lk(apps_mu);
            for (const auto& item : apps_cache.arr_val) arr.push(item);
        }
        if (arr.arr_val.size() <= 1) {
            std::string raw = run_cmd_capture("pm list packages -3");
            size_t p = 0;
            while (p < raw.size()) {
                size_t nl = raw.find('\n', p);
                if (nl == std::string::npos) nl = raw.size();
                std::string line = raw.substr(p, nl - p);
                if (!line.empty() && line.back() == '\r') line.pop_back();
                if (line.rfind("package:", 0) == 0) {
                    std::string pkg = line.substr(8);
                    Value item = Value::make_obj();
                    item["package"] = Value::make_str(pkg);
                    item["label"] = Value::make_str(pkg);
                    arr.push(std::move(item));
                }
                p = nl + 1;
            }
        }
        return arr;
    };

    auto is_yandex_music_query = [&](const std::string& q) -> bool {
        return contains_case_insensitive(q, "yandex") ||
               contains_case_insensitive(q, "music") ||
               contains_case_insensitive(q, "яндекс") ||
               contains_case_insensitive(q, "музык") ||
               contains_case_insensitive(q, "xlam");
    };

    auto resolve_app_pkg = [&](const std::string& q, std::string& out_pkg, std::string& out_label) {
        out_pkg.clear();
        out_label.clear();
        {
            std::lock_guard<std::mutex> lk(apps_mu);
            for (const auto& a : apps_cache.arr_val) {
                std::string p = a.get("package") ? a.get("package")->as_str() : "";
                std::string l = a.get("label") ? a.get("label")->as_str() : "";
                if (p == q || contains_case_insensitive(l, q) || contains_case_insensitive(p, q)) {
                    out_pkg = p;
                    out_label = l.empty() ? p : l;
                    return;
                }
            }
        }
        std::string raw = run_cmd_capture("pm list packages -3");
        size_t pos = 0;
        while (pos < raw.size()) {
            size_t nl = raw.find('\n', pos);
            if (nl == std::string::npos) nl = raw.size();
            std::string line = raw.substr(pos, nl - pos);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.rfind("package:", 0) == 0) {
                std::string p = line.substr(8);
                if (p == q || contains_case_insensitive(p, q)) {
                    out_pkg = p;
                    out_label = p;
                    return;
                }
            }
            pos = nl + 1;
        }
        if (is_valid_package_name(q)) {
            out_pkg = q;
            out_label = q;
        }
    };

    AssistantCallbacks acb;
    acb.play_pcm = [&](const int16_t* samples, size_t frames, int rate, int ch) {
        audio.play_pcm(samples, frames, rate, ch);
    };
    acb.clear_playback = [&]() {
        audio.clear_playback_queue();
    };
    acb.is_playback_or_tail_active = [&]() -> bool {
        return audio.is_playback_or_tail_active();
    };
    acb.play_wake_chime = [&]() {
        audio.play_wake_chime();
    };
    acb.play_error_chime = [&]() {
        audio.play_error_chime();
    };
    acb.set_visual_state = [&](VoiceVisualState vst) {
        leds.set_voice_state(vst);
        bool should_duck = (vst == VoiceVisualState::Listening ||
                            vst == VoiceVisualState::Thinking ||
                            vst == VoiceVisualState::Speaking);
        set_music_duck(should_duck);
    };
    acb.show_screen_text = [&](const std::string& txt, uint8_t r, uint8_t g, uint8_t b, int dur) {
        leds.trigger_screen_text(txt, r, g, b, dur);
    };
    acb.broadcast_event = [&](const json::Value& evt) {
        if (http.ws_client_count() > 0) {
            http.broadcast_ws_text(evt.dump(-1));
        }
        json::Value u = evt;
        u["cmd"] = json::Value::make_str("assistant_event");
        send_udp_json(8086, u.dump(-1));
    };
    acb.get_runtime_context = [&]() -> std::string {
        DaemonConfig c;
        {
            std::lock_guard<std::mutex> lk(cfg_mu);
            c = cfg;
        }
        MusicPlaybackState ms;
        {
            std::lock_guard<std::mutex> lk(music_mu);
            ms = music_state;
        }
        std::string weather_summary;
        {
            std::lock_guard<std::mutex> lk(last_weather_mu);
            if (has_last_weather) {
                std::string wcity = last_weather_json.get("city") ? last_weather_json.get("city")->as_str() : "";
                std::string wt = last_weather_json.get("tempC") ? last_weather_json.get("tempC")->as_str() : "";
                std::string wc = last_weather_json.get("condition") ? last_weather_json.get("condition")->as_str() : "";
                if (!wcity.empty() && !wt.empty()) {
                    weather_summary = wcity + ": " + wt + "°C" + (wc.empty() ? "" : ", " + wc);
                }
            }
        }
        std::string eff_city = !c.location.city.empty() ? c.location.city : c.location.resolved_city;
        if (eff_city.empty()) eff_city = "автоопределение";
        std::string ctx = "Текущее состояние колонки:\n";
        ctx += "- Местоположение: " + eff_city +
               (c.location.city.empty() ? " (авто по IP)" : " (указано вручную)") + "\n";
        ctx += "- Голос: " + c.assistant.voice_name +
               "; стиль часов: " + c.led_screen.clock_style +
               "; стиль визуализатора: " + c.led_screen.visualizer_style +
               "; авто-визуализатор музыки: " + std::string(c.led_screen.auto_music_visualizer ? "вкл" : "выкл") + "\n";
        ctx += "- Громкость: " + std::to_string(volume_osd_pct.load()) + "%" +
               "; выход: " + c.audio.output_route +
               "; эквалайзер: " + c.audio.eq_preset +
               " (Bass Boost " + std::to_string(c.audio.bass_boost) + "%)\n";
        ctx += "- LED-кольцо: " + c.led_ring.mode + " (" + c.led_ring.color + ")" +
               "; экран: " + c.led_screen.mode +
               "; микрофон: " + std::string(c.audio.mic_muted ? "выкл" : "вкл") +
               " (чувствительность " + std::to_string(c.audio.mic_sensitivity) + "%)\n";
        if (ms.playing && !ms.title.empty()) {
            ctx += "- Сейчас играет: " + ms.artist + " — " + ms.title + "\n";
        }
        if (!weather_summary.empty()) {
            ctx += "- Последняя погода: " + weather_summary + "\n";
        }
        return ctx;
    };
    acb.execute_tool = [&](const std::string& name, const json::Value& args) -> json::Value {
        using json::Value;
        Value res = Value::make_obj();

        // --- Live Internet Tools ---
        if (name == "web_search") {
            std::string query = args.get("query") ? args.get("query")->as_str() : "";
            int count = args.get("count") ? args.get("count")->as_int(5) : 5;
            return tool_web_search(query, count, &g_stop);
        }

        if (name == "get_weather") {
            std::string city = args.get("city") ? args.get("city")->as_str() : "";
            int days = args.get("days") ? args.get("days")->as_int(3) : 3;
            std::string fb_city;
            double fb_lat = 0.0, fb_lon = 0.0;
            bool auto_det = true;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                auto_det = cfg.location.auto_detect;
                fb_city = !cfg.location.city.empty() ? cfg.location.city : cfg.location.resolved_city;
                if (cfg.location.city.empty()) {
                    fb_lat = cfg.location.lat;
                    fb_lon = cfg.location.lon;
                }
            }
            if (city.empty() && fb_city.empty() && fb_lat == 0.0 && fb_lon == 0.0 && auto_det) {
                GeoLocationResult geo = detect_location_by_ip(&g_stop);
                if (geo.ok) {
                    fb_city = geo.city;
                    fb_lat = geo.lat;
                    fb_lon = geo.lon;
                    DaemonConfig c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        cfg.location.resolved_city = geo.city;
                        cfg.location.resolved_country = geo.country;
                        cfg.location.resolved_epoch = static_cast<int64_t>(std::time(nullptr));
                        if (cfg.location.city.empty()) {
                            cfg.location.lat = geo.lat;
                            cfg.location.lon = geo.lon;
                        }
                        cfg.sanitize_and_validate();
                        c = cfg;
                    }
                    save_cfg(c);
                    push_style_sync_to_launcher(c);
                }
            }
            Value wres = tool_get_weather(city, days, fb_lat, fb_lon, fb_city, &g_stop);
            if (wres.get("ok") && wres.get("ok")->as_bool(false)) {
                trigger_weather_visuals(wres, true);
            }
            return wres;
        }

        if (name == "fetch_url") {
            std::string url = args.get("url") ? args.get("url")->as_str() : "";
            bool raw = args.get("raw") ? args.get("raw")->as_bool(false) : false;
            int max_chars = args.get("max_chars") ? args.get("max_chars")->as_int(6000) : 6000;
            return tool_fetch_url(url, raw, max_chars, &g_stop);
        }

        if (name == "http_request") {
            std::string url = args.get("url") ? args.get("url")->as_str() : "";
            std::string method = args.get("method") ? args.get("method")->as_str("GET") : "GET";
            std::string body = args.get("body") ? args.get("body")->as_str() : "";
            std::string headers_json = args.get("headers_json") ? args.get("headers_json")->as_str() : "";
            bool insecure = args.get("insecure") ? args.get("insecure")->as_bool(false) : false;
            return tool_http_request(url, method, body, headers_json, insecure, &g_stop);
        }

        // --- System & Root Speaker Control ---
        if (name == "get_device_status") {
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                c = cfg;
            }
            res["ok"] = Value::make_bool(true);
            res["volumePercent"] = Value::make_int(volume_osd_pct.load());
            res["wooferVolume"] = Value::make_int(c.audio.woofer_volume);
            res["tweetersVolume"] = Value::make_int(c.audio.tweeters_volume);
            res["headphoneVolume"] = Value::make_int(c.audio.headphone_volume);
            res["eqPreset"] = Value::make_str(c.audio.eq_preset);
            res["bassBoost"] = Value::make_int(c.audio.bass_boost);
            {
                Value eb = Value::make_arr();
                for (float g : c.audio.eq_bands) eb.push(Value::make_num(g));
                res["eqBands"] = std::move(eb);
            }
            res["outputRoute"] = Value::make_str(c.audio.output_route);
            res["headphonePlugged"] = Value::make_bool(inputs.headphone_plugged());
            res["micMuted"] = Value::make_bool(c.audio.mic_muted);
            res["micSensitivity"] = Value::make_int(c.audio.mic_sensitivity);
            res["voiceName"] = Value::make_str(c.assistant.voice_name);
            res["clockStyle"] = Value::make_str(c.led_screen.clock_style);
            res["visualizerStyle"] = Value::make_str(c.led_screen.visualizer_style);
            res["autoMusicVisualizer"] = Value::make_bool(c.led_screen.auto_music_visualizer);
            res["locationAuto"] = Value::make_bool(c.location.auto_detect);
            res["locationCity"] = Value::make_str(!c.location.city.empty() ? c.location.city : c.location.resolved_city);
            res["memoryCount"] = Value::make_int(static_cast<int64_t>(c.assistant.memory_notes.size()));
            res["ledRingMode"] = Value::make_str(c.led_ring.mode);
            res["ledRingColor"] = Value::make_str(c.led_ring.color);
            res["ledRingBrightness"] = Value::make_int(leds.effective_brightness());
            res["ledScreenConnected"] = Value::make_bool(leds.screen_present());
            res["ledScreenMode"] = Value::make_str(c.led_screen.mode);
            res["lux"] = Value::make_num(leds.current_lux());
            json::Value hw_st = leds.get_status_json();
            if (const auto* t = hw_st.get("socTempC")) res["socTempC"] = *t;
            res["ip"] = Value::make_str(MdnsResponder::detect_local_ipv4());
            res["activeTimers"] = timers.list_timers_json();
            res["music"] = get_music_json();
            return res;
        }

        if (name == "system_info") {
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                c = cfg;
            }
            res["ok"] = Value::make_bool(true);
            res["device"] = Value::make_str("Yandex Station Max (yandexstation_2 / S905X2)");
            res["hostname"] = Value::make_str(c.device_hostname);
            res["ip"] = Value::make_str(MdnsResponder::detect_local_ipv4());
            auto daemon_up = std::chrono::duration_cast<std::chrono::seconds>(
                                 std::chrono::steady_clock::now() - start_tp)
                                 .count();
            res["daemonUptimeSec"] = Value::make_int(daemon_up);
            std::string up_str = read_first_line("/proc/uptime");
            if (!up_str.empty()) {
                res["osUptimeSec"] = Value::make_int(static_cast<int64_t>(std::atof(up_str.c_str())));
            }
            std::string load_str = read_first_line("/proc/loadavg");
            if (!load_str.empty()) res["loadAvg"] = Value::make_str(load_str);

            if (FILE* f = fopen("/proc/meminfo", "r")) {
                char line[256];
                long mem_tot_kb = 0, mem_avail_kb = 0;
                while (fgets(line, sizeof(line), f)) {
                    sscanf(line, "MemTotal: %ld kB", &mem_tot_kb);
                    sscanf(line, "MemAvailable: %ld kB", &mem_avail_kb);
                }
                fclose(f);
                if (mem_tot_kb > 0) res["ramTotalMb"] = Value::make_int(mem_tot_kb / 1024);
                if (mem_avail_kb > 0) res["ramAvailableMb"] = Value::make_int(mem_avail_kb / 1024);
            }
            struct statvfs vfs {};
            if (statvfs("/data", &vfs) == 0 && vfs.f_frsize > 0) {
                uint64_t tot_mb = (static_cast<uint64_t>(vfs.f_blocks) * vfs.f_frsize) / (1024ULL * 1024ULL);
                uint64_t free_mb = (static_cast<uint64_t>(vfs.f_bavail) * vfs.f_frsize) / (1024ULL * 1024ULL);
                res["dataTotalMb"] = Value::make_int(static_cast<int64_t>(tot_mb));
                res["dataFreeMb"] = Value::make_int(static_cast<int64_t>(free_mb));
            }
            res["volumePercent"] = Value::make_int(volume_osd_pct.load());
            res["eqPreset"] = Value::make_str(c.audio.eq_preset);
            res["lux"] = Value::make_num(leds.current_lux());
            json::Value hw_st = leds.get_status_json();
            if (const auto* t = hw_st.get("socTempC")) res["socTempC"] = *t;
            res["activeTimers"] = timers.list_timers_json();

            ShellResult sr = run_shell_with_timeout(
                "printf 'rel=%s\\nsdk=%s\\ndns1=%s\\npdns=%s\\nbt=%s\\n' "
                "\"$(getprop ro.build.version.release)\" "
                "\"$(getprop ro.build.version.sdk)\" "
                "\"$(getprop net.dns1)\" "
                "\"$(settings get global private_dns_specifier 2>/dev/null)\" "
                "\"$(settings get global bluetooth_on 2>/dev/null)\"; "
                "dumpsys wifi 2>/dev/null | grep -m1 'mWifiInfo'; "
                "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n1",
                4, 2048);
            if (!sr.output.empty()) {
                res["androidTelemetry"] = Value::make_str(sr.output);
            }
            return res;
        }

        if (name == "shell_exec") {
            std::string cmd = args.get("command") ? args.get("command")->as_str() : "";
            if (cmd.empty()) {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("command is required");
                return res;
            }
            int tsec = args.get("timeout_sec") ? std::clamp(args.get("timeout_sec")->as_int(10), 1, 30) : 10;
            ShellResult sr = run_shell_with_timeout(cmd, tsec, 8192);
            res["ok"] = Value::make_bool(!sr.timed_out && sr.exit_code == 0);
            res["exitCode"] = Value::make_int(sr.exit_code);
            res["timedOut"] = Value::make_bool(sr.timed_out);
            res["output"] = Value::make_str(sr.output);
            return res;
        }

        if (name == "device_power") {
            std::string act = args.get("action") ? args.get("action")->as_str() : "";
            if (act == "screen_wake") {
                uinput.send_key(143); // KEY_WAKEUP
                exec_hdmi_cec_action("wake");
                res["ok"] = Value::make_bool(true);
                res["action"] = Value::make_str(act);
                return res;
            }
            if (act == "screen_sleep") {
                uinput.send_key(142); // KEY_SLEEP
                exec_hdmi_cec_action("standby");
                res["ok"] = Value::make_bool(true);
                res["action"] = Value::make_str(act);
                return res;
            }
            if (act == "restart_daemon") {
                long pid_l = static_cast<long>(getpid());
                char sh_cmd[512];
                snprintf(sh_cmd, sizeof(sh_cmd),
                         "sleep 4; kill -TERM %ld 2>/dev/null; "
                         "i=0; while kill -0 %ld 2>/dev/null && [ $i -lt 30 ]; do sleep 0.2; i=$((i+1)); done; "
                         "kill -KILL %ld 2>/dev/null; sleep 0.2; "
                         "LD_LIBRARY_PATH=/data/local/openstation/lib:/vendor/lib64:/system/lib64 "
                         "/data/local/openstation/openstationd --daemon </dev/null >/data/local/openstation/boot.log 2>&1",
                         pid_l, pid_l, pid_l);
                bool spawned = spawn_detached({"/system/bin/sh", "-c", sh_cmd});
                res["ok"] = Value::make_bool(spawned);
                res["action"] = Value::make_str(act);
                if (spawned) res["stopSession"] = Value::make_bool(true);
                return res;
            }
            if (act == "reboot") {
                bool spawned = spawn_detached({
                    "/system/bin/sh", "-c",
                    "sleep 4; /system/bin/svc power reboot || /system/bin/reboot"
                });
                res["ok"] = Value::make_bool(spawned);
                res["action"] = Value::make_str(act);
                if (spawned) res["stopSession"] = Value::make_bool(true);
                return res;
            }
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("unknown device_power action");
            return res;
        }

        // --- Audio, EQ & Sound Alerts ---
        if (name == "set_volume") {
            if (const auto* dir = args.get("direction"); dir && dir->is_str()) {
                std::string d = dir->as_str();
                int steps = args.get("steps") ? std::clamp(args.get("steps")->as_int(4), 1, 10) : 4;
                if (d == "mute") {
                    uinput.send_key(113); // KEY_MUTE
                    res["ok"] = Value::make_bool(true);
                    res["action"] = Value::make_str("mute");
                    return res;
                }
                if (d == "up" || d == "down") {
                    bool up = (d == "up");
                    int max_steps = std::max(1, android_vol_max.load());
                    int dpct = std::max(1, (100 * steps + max_steps / 2) / max_steps);
                    int next_p = step_android_volume_pct(up ? dpct : -dpct, true);
                    res["ok"] = Value::make_bool(true);
                    res["volumePercent"] = Value::make_int(next_p);
                    return res;
                }
            }
            if (const auto* pct_v = args.get("percent"); pct_v && pct_v->is_num()) {
                int pct = std::clamp(pct_v->as_int(50), 0, 100);
                std::string tgt = args.get("target") ? args.get("target")->as_str("all") : "all";
                if (tgt == "woofer" || tgt == "tweeters" || tgt == "headphones") {
                    int trim = std::clamp(static_cast<int>(std::lround((pct - 50) * 0.8)), -40, 40);
                    DaemonConfig c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        if (tgt == "woofer") cfg.audio.woofer_trim = trim;
                        if (tgt == "tweeters") cfg.audio.tweeters_trim = trim;
                        if (tgt == "headphones") cfg.audio.headphone_trim = trim;
                        cfg.sanitize_and_validate();
                    }
                    reapply_current_volume_and_route(true, true);
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        c = cfg;
                    }
                    int amp = (tgt == "woofer") ? c.audio.woofer_volume
                                                : ((tgt == "tweeters") ? c.audio.tweeters_volume
                                                                       : c.audio.headphone_volume);
                    leds.trigger_volume_osd(pct);
                    save_cfg(c);
                    res["ok"] = Value::make_bool(true);
                    res["target"] = Value::make_str(tgt);
                    res["trim"] = Value::make_int(trim);
                    res["trimDb"] = Value::make_num(trim * 0.5);
                    res["ampLevel"] = Value::make_int(amp);
                    res["percent"] = Value::make_int(pct);
                } else {
                    int applied = set_android_volume_pct(pct, true);
                    res["ok"] = Value::make_bool(true);
                    res["volumePercent"] = Value::make_int(applied);
                }
                return res;
            }
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("specify percent (0..100) or direction (up/down/mute)");
            return res;
        }

        if (name == "set_eq_preset") {
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                bool preset_given = false;
                bool bands_given = false;
                bool bass_given = false;
                std::string next_preset = cfg.audio.eq_preset;

                if (const auto* p = args.get("preset"); p && p->is_str()) {
                    if (!is_valid_eq_preset(p->as_str())) {
                        res["ok"] = Value::make_bool(false);
                        res["error"] = Value::make_str("invalid preset (balanced/flat/bass_boost/vocal/night/rock/electronic/custom)");
                        return res;
                    }
                    next_preset = p->as_str();
                    cfg.audio.eq_preset = next_preset;
                    preset_given = true;
                }
                if (const auto* bcsv = args.get("bands_csv"); bcsv && bcsv->is_str()) {
                    std::string s = bcsv->as_str();
                    std::array<float, 10> parsed = cfg.audio.eq_bands;
                    size_t idx = 0;
                    size_t pos = 0;
                    while (pos < s.size() && idx < 10) {
                        while (pos < s.size() && (s[pos] == ',' || s[pos] == ' ' || s[pos] == ';')) ++pos;
                        if (pos >= s.size()) break;
                        char* endp = nullptr;
                        double val = std::strtod(s.c_str() + pos, &endp);
                        if (endp == s.c_str() + pos) break;
                        parsed[idx++] = std::clamp(static_cast<float>(val), -12.0f, 12.0f);
                        pos = static_cast<size_t>(endp - s.c_str());
                    }
                    if (idx > 0) {
                        cfg.audio.eq_bands = parsed;
                        bands_given = true;
                    }
                }
                if (const auto* bhz = args.get("band_hz"); bhz && bhz->is_num()) {
                    double gdb = args.get("gain_db") ? args.get("gain_db")->as_double(0.0) : 0.0;
                    size_t idx = find_nearest_eq_band(bhz->as_int(1000));
                    cfg.audio.eq_bands[idx] = std::clamp(static_cast<float>(gdb), -12.0f, 12.0f);
                    bands_given = true;
                }
                if (const auto* bb = args.get("bass_boost"); bb && bb->is_num()) {
                    cfg.audio.bass_boost = std::clamp(bb->as_int(30), 0, 100);
                    bass_given = true;
                }
                if (preset_given && next_preset != "custom" && !bands_given) {
                    int def_bass = cfg.audio.bass_boost;
                    apply_eq_preset_defaults(next_preset, cfg.audio.eq_bands, def_bass);
                    if (!bass_given) cfg.audio.bass_boost = def_bass;
                } else if ((bands_given || bass_given) && !preset_given) {
                    cfg.audio.eq_preset = "custom";
                }
                if (const auto* x = args.get("woofer_eq"); x && x->is_bool()) cfg.audio.woofer_eq = x->as_bool(true);
                if (const auto* x = args.get("woofer_drc"); x && x->is_bool()) cfg.audio.woofer_drc = x->as_bool(true);
                if (const auto* x = args.get("tweeters_eq"); x && x->is_bool()) cfg.audio.tweeters_eq = x->as_bool(true);
                if (const auto* x = args.get("tweeters_drc"); x && x->is_bool()) cfg.audio.tweeters_drc = x->as_bool(true);
                if (const auto* x = args.get("loopback"); x && x->is_bool()) cfg.audio.loopback_enable = x->as_bool(true);
                cfg.sanitize_and_validate();
                c = cfg;
            }
            mixer.set_bool("Woofer EQ Switch", c.audio.woofer_eq);
            mixer.set_bool("Woofer DRC Switch", c.audio.woofer_drc);
            mixer.set_bool("Tweeters EQ Switch", c.audio.tweeters_eq);
            mixer.set_bool("Tweeters DRC Switch", c.audio.tweeters_drc);
            mixer.set_enum("Loopback Enable", c.audio.loopback_enable ? "Enable" : "Disable");
            audio.update_config(c.audio);
            push_eq_to_launcher(c);
            push_style_sync_to_launcher(c);
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["preset"] = Value::make_str(c.audio.eq_preset);
            res["bassBoost"] = Value::make_int(c.audio.bass_boost);
            {
                Value eb = Value::make_arr();
                for (float g : c.audio.eq_bands) eb.push(Value::make_num(g));
                res["eqBands"] = std::move(eb);
            }
            res["wooferEq"] = Value::make_bool(c.audio.woofer_eq);
            res["tweetersEq"] = Value::make_bool(c.audio.tweeters_eq);
            return res;
        }

        if (name == "set_assistant_voice") {
            std::string req_voice;
            if (const auto* vp = args.get("voice"); vp && vp->is_str()) req_voice = vp->as_str();
            else if (const auto* vp2 = args.get("voice_name"); vp2 && vp2->is_str()) req_voice = vp2->as_str();

            DaemonConfig c;
            std::string chosen;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                chosen = normalize_voice_name(req_voice, cfg.assistant.voice_name);
                cfg.assistant.voice_name = chosen;
                cfg.sanitize_and_validate();
                c = cfg;
            }
            assistant.update_config(c.assistant);
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["voice"] = Value::make_str(c.assistant.voice_name);
            res["reloadVoice"] = Value::make_bool(true);
            return res;
        }

        if (name == "set_styles") {
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                const Value* cs = args.get("clock_style") ? args.get("clock_style") : args.get("clockStyle");
                if (cs && cs->is_str()) {
                    if (!is_valid_clock_style(cs->as_str())) {
                        res["ok"] = Value::make_bool(false);
                        res["error"] = Value::make_str("invalid clock_style");
                        return res;
                    }
                    cfg.led_screen.clock_style = cs->as_str();
                }
                const Value* vs = args.get("visualizer_style") ? args.get("visualizer_style") : args.get("visualizerStyle");
                if (vs && vs->is_str()) {
                    if (!is_valid_visualizer_style(vs->as_str())) {
                        res["ok"] = Value::make_bool(false);
                        res["error"] = Value::make_str("invalid visualizer_style");
                        return res;
                    }
                    cfg.led_screen.visualizer_style = vs->as_str();
                }
                const Value* amv = args.get("auto_music_visualizer")
                                       ? args.get("auto_music_visualizer")
                                       : args.get("autoMusicVisualizer");
                if (amv && amv->is_bool()) {
                    cfg.led_screen.auto_music_visualizer = amv->as_bool(true);
                }
                const Value* sc = args.get("screen_color") ? args.get("screen_color") : args.get("color");
                if (sc && sc->is_str() && !sc->as_str().empty()) {
                    cfg.led_screen.color = sc->as_str();
                }
                const Value* sm = args.get("screen_mode");
                if (sm && sm->is_str() && is_valid_screen_mode(sm->as_str())) {
                    cfg.led_screen.mode = sm->as_str();
                }
                cfg.sanitize_and_validate();
                c = cfg;
            }
            leds.update_config(c.led_ring, c.led_screen);
            push_style_sync_to_launcher(c);
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["clockStyle"] = Value::make_str(c.led_screen.clock_style);
            res["visualizerStyle"] = Value::make_str(c.led_screen.visualizer_style);
            res["autoMusicVisualizer"] = Value::make_bool(c.led_screen.auto_music_visualizer);
            res["screenColor"] = Value::make_str(c.led_screen.color);
            res["screenMode"] = Value::make_str(c.led_screen.mode);
            return res;
        }

        if (name == "set_location") {
            bool has_city = (args.get("city") && args.get("city")->is_str());
            std::string city_val = has_city ? trim_ws_copy(args.get("city")->as_str()) : "";
            bool run_auto = false;
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                if (const auto* ad = args.get("auto_detect"); ad && ad->is_bool()) {
                    cfg.location.auto_detect = ad->as_bool(true);
                } else if (!has_city || city_val.empty()) {
                    cfg.location.auto_detect = true;
                }
                if (has_city) {
                    cfg.location.city = city_val;
                    if (!city_val.empty()) {
                        cfg.location.lat = 0.0;
                        cfg.location.lon = 0.0;
                    }
                }
                run_auto = cfg.location.auto_detect && cfg.location.city.empty();
                cfg.sanitize_and_validate();
                c = cfg;
            }
            if (run_auto) {
                GeoLocationResult geo = detect_location_by_ip(&g_stop);
                if (geo.ok) {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    cfg.location.resolved_city = geo.city;
                    cfg.location.resolved_country = geo.country;
                    cfg.location.resolved_epoch = static_cast<int64_t>(std::time(nullptr));
                    cfg.location.lat = geo.lat;
                    cfg.location.lon = geo.lon;
                    cfg.sanitize_and_validate();
                    c = cfg;
                }
            }
            save_cfg(c);
            push_style_sync_to_launcher(c);
            res["ok"] = Value::make_bool(true);
            res["autoDetect"] = Value::make_bool(c.location.auto_detect);
            res["city"] = Value::make_str(c.location.city);
            res["resolvedCity"] = Value::make_str(c.location.resolved_city);
            res["resolvedCountry"] = Value::make_str(c.location.resolved_country);
            return res;
        }

        if (name == "manage_memory") {
            std::string act = args.get("action") ? args.get("action")->as_str("list") : "list";
            std::string note = args.get("note") ? trim_ws_copy(args.get("note")->as_str()) : "";
            int idx_1b = args.get("index") ? args.get("index")->as_int(-1) : -1;
            bool dirty = false;
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                if (act == "add") {
                    if (note.empty()) {
                        res["ok"] = Value::make_bool(false);
                        res["error"] = Value::make_str("note is required for action='add'");
                        return res;
                    }
                    cfg.assistant.memory_notes.push_back(note);
                    dirty = true;
                } else if (act == "delete") {
                    if (idx_1b >= 1 && static_cast<size_t>(idx_1b) <= cfg.assistant.memory_notes.size()) {
                        cfg.assistant.memory_notes.erase(cfg.assistant.memory_notes.begin() + (idx_1b - 1));
                        dirty = true;
                    } else if (!note.empty()) {
                        auto& vec = cfg.assistant.memory_notes;
                        auto it = std::remove_if(vec.begin(), vec.end(), [&](const std::string& item) {
                            return contains_case_insensitive(item, note);
                        });
                        if (it != vec.end()) {
                            vec.erase(it, vec.end());
                            dirty = true;
                        }
                    }
                } else if (act == "clear") {
                    if (!cfg.assistant.memory_notes.empty()) {
                        cfg.assistant.memory_notes.clear();
                        dirty = true;
                    }
                }
                if (dirty) {
                    cfg.sanitize_and_validate();
                }
                c = cfg;
            }
            if (dirty) {
                assistant.update_config(c.assistant);
                save_cfg(c);
            }
            res["ok"] = Value::make_bool(true);
            res["action"] = Value::make_str(act);
            res["count"] = Value::make_int(static_cast<int64_t>(c.assistant.memory_notes.size()));
            Value notes = Value::make_arr();
            for (const auto& s : c.assistant.memory_notes) notes.push(Value::make_str(s));
            res["notes"] = std::move(notes);
            return res;
        }

        if (name == "set_audio_route") {
            std::string route = args.get("route") ? args.get("route")->as_str() : "";
            if (!is_valid_output_route(route)) {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("invalid route");
                return res;
            }
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                cfg.audio.output_route = route;
                cfg.sanitize_and_validate();
            }
            reapply_current_volume_and_route(true, true);
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                c = cfg;
            }
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["route"] = Value::make_str(route);
            return res;
        }

        if (name == "play_sound") {
            std::string kind = args.get("kind") ? args.get("kind")->as_str("wake") : "wake";
            if (kind == "alarm") {
                audio.play_timer_alarm();
            } else if (kind == "error") {
                audio.play_error_chime();
            } else if (kind == "tone") {
                int f = args.get("freq_hz") ? std::clamp(args.get("freq_hz")->as_int(440), 80, 8000) : 440;
                int dur = args.get("duration_ms") ? std::clamp(args.get("duration_ms")->as_int(700), 100, 4000) : 700;
                bool sweep = args.get("sweep") ? args.get("sweep")->as_bool(false) : false;
                audio.play_test_tone(f, dur, sweep);
            } else {
                audio.play_wake_chime();
            }
            res["ok"] = Value::make_bool(true);
            res["kind"] = Value::make_str(kind);
            return res;
        }

        if (name == "set_mic_mute") {
            bool muted = args.get("muted") ? args.get("muted")->as_bool(true) : true;
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                cfg.audio.mic_muted = muted;
                cfg.sanitize_and_validate();
                c = cfg;
            }
            audio.set_mic_muted(muted);
            leds.set_mic_muted(muted);
            if (!muted) {
                assistant.set_mic_muted(false);
            }
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["muted"] = Value::make_bool(muted);
            if (muted) res["stopSession"] = Value::make_bool(true);
            return res;
        }

        if (name == "set_mic_sensitivity") {
            int sens = args.get("sensitivity") ? std::clamp(args.get("sensitivity")->as_int(85), 10, 100) : 85;
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                cfg.audio.mic_sensitivity = sens;
                cfg.sanitize_and_validate();
                c = cfg;
            }
            audio.update_config(c.audio);
            assistant.set_mic_sensitivity(c.audio.mic_sensitivity);
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["micSensitivity"] = Value::make_int(c.audio.mic_sensitivity);
            return res;
        }

        // --- LED Ring, Front Matrix Screen & Timers ---
        if (name == "set_led_ring") {
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                if (const auto* m = args.get("mode"); m && m->is_str() && is_valid_ring_mode(m->as_str())) {
                    std::string mode = m->as_str();
                    cfg.led_ring.mode = (mode == "spectrum") ? "music" : mode;
                }
                if (const auto* col = args.get("color"); col && col->is_str()) {
                    cfg.led_ring.color = col->as_str();
                }
                if (const auto* scol = args.get("secondary_color"); scol && scol->is_str()) {
                    cfg.led_ring.secondary_color = scol->as_str();
                }
                if (const auto* br = args.get("brightness"); br && br->is_num()) {
                    cfg.led_ring.brightness = std::clamp(br->as_int(160), 0, 255);
                    cfg.led_ring.auto_brightness = false;
                }
                if (const auto* ab = args.get("auto_brightness"); ab && ab->is_bool()) {
                    cfg.led_ring.auto_brightness = ab->as_bool(true);
                }
                if (const auto* sp = args.get("speed"); sp && sp->is_num()) {
                    cfg.led_ring.speed = std::clamp(sp->as_int(55), 1, 100);
                }
                cfg.sanitize_and_validate();
                c = cfg;
            }
            leds.update_config(c.led_ring, c.led_screen);
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["mode"] = Value::make_str(c.led_ring.mode);
            res["color"] = Value::make_str(c.led_ring.color);
            res["secondaryColor"] = Value::make_str(c.led_ring.secondary_color);
            res["brightness"] = Value::make_int(c.led_ring.brightness);
            res["speed"] = Value::make_int(c.led_ring.speed);
            return res;
        }

        if (name == "set_led_screen") {
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                if (const auto* m = args.get("mode"); m && m->is_str() && is_valid_screen_mode(m->as_str())) {
                    cfg.led_screen.mode = m->as_str();
                }
                if (const auto* t = args.get("text"); t && t->is_str()) {
                    cfg.led_screen.text = t->as_str();
                    if (!args.get("mode")) cfg.led_screen.mode = "text";
                }
                if (const auto* col = args.get("color"); col && col->is_str()) {
                    cfg.led_screen.color = col->as_str();
                }
                if (const auto* br = args.get("brightness"); br && br->is_num()) {
                    cfg.led_screen.brightness = std::clamp(br->as_int(100), 0, 100);
                    cfg.led_screen.auto_brightness = false;
                }
                if (const auto* ab = args.get("auto_brightness"); ab && ab->is_bool()) {
                    cfg.led_screen.auto_brightness = ab->as_bool(true);
                }
                if (const auto* sp = args.get("scroll_speed"); sp && sp->is_num()) {
                    cfg.led_screen.scroll_speed = std::clamp(sp->as_int(45), 1, 100);
                }
                cfg.sanitize_and_validate();
                c = cfg;
            }
            leds.update_config(c.led_ring, c.led_screen);
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["screenConnected"] = Value::make_bool(leds.screen_present());
            res["mode"] = Value::make_str(c.led_screen.mode);
            return res;
        }

        if (name == "show_screen_banner") {
            std::string txt = args.get("text") ? args.get("text")->as_str() : "";
            if (txt.empty()) {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("text is required");
                return res;
            }
            std::string col = args.get("color") ? args.get("color")->as_str("#00dcff") : "#00dcff";
            int dur = args.get("duration_ms") ? std::clamp(args.get("duration_ms")->as_int(4500), 500, 20000) : 4500;
            uint8_t r = 0, g = 220, b = 255;
            parse_hex_rgb(col, r, g, b);
            leds.trigger_screen_text(txt, r, g, b, dur);
            res["ok"] = Value::make_bool(true);
            res["screenConnected"] = Value::make_bool(leds.screen_present());
            res["durationMs"] = Value::make_int(dur);
            return res;
        }

        if (name == "manage_timer") {
            std::string act = args.get("action") ? args.get("action")->as_str("list") : "list";
            if (act == "set") {
                int sec = args.get("seconds") ? args.get("seconds")->as_int(0) : 0;
                if (sec < 1 || sec > 86400) {
                    res["ok"] = Value::make_bool(false);
                    res["error"] = Value::make_str("seconds must be 1..86400");
                    return res;
                }
                std::string label = args.get("label") ? args.get("label")->as_str() : "";
                int id = timers.set_timer(sec, label);
                leds.trigger_flash(255, 165, 0, 500);
                res["ok"] = Value::make_bool(true);
                res["id"] = Value::make_int(id);
                res["seconds"] = Value::make_int(sec);
                if (!label.empty()) res["label"] = Value::make_str(label);
                res["activeTimers"] = timers.list_timers_json();
                return res;
            }
            if (act == "cancel") {
                std::string target = args.get("target") ? args.get("target")->as_str() : "";
                int removed = timers.cancel_timer(target);
                res["ok"] = Value::make_bool(removed > 0);
                res["removed"] = Value::make_int(removed);
                res["activeTimers"] = timers.list_timers_json();
                return res;
            }
            res["ok"] = Value::make_bool(true);
            res["timers"] = timers.list_timers_json();
            return res;
        }

        // --- TV, HDMI-CEC, Remote, Typing, Yandex Music & Apps ---
        if (name == "tv_hdmi_cec") {
            std::string act = args.get("action") ? normalize_cec_action(args.get("action")->as_str()) : "";
            if (act != "wake" && act != "standby" && act != "vol_up" && act != "vol_down" && act != "mute") {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("unknown CEC action");
                return res;
            }
            exec_hdmi_cec_action(act);
            if (act == "wake") uinput.send_key(143); // KEY_WAKEUP
            json::Value payload = json::Value::make_obj();
            payload["cmd"] = Value::make_str("cec");
            payload["action"] = Value::make_str(act);
            send_udp_json(8086, payload.dump(-1));
            res["ok"] = Value::make_bool(true);
            res["action"] = Value::make_str(act);
            return res;
        }

        if (name == "remote_control" || name == "media_control") {
            std::string act = args.get("action") ? args.get("action")->as_str() : "";
            int repeat = args.get("repeat") ? std::clamp(args.get("repeat")->as_int(1), 1, 10) : 1;
            bool ok = false;
            for (int r = 0; r < repeat; ++r) {
                if (r > 0) usleep(45000);
                ok = uinput.send_android_action(act);
                if (!ok) break;
            }
            res["ok"] = Value::make_bool(ok);
            res["action"] = Value::make_str(act);
            res["repeat"] = Value::make_int(repeat);
            return res;
        }

        if (name == "type_text") {
            std::string txt = args.get("text") ? args.get("text")->as_str() : "";
            if (txt.empty()) {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("text is required");
                return res;
            }
            bool press_enter = args.get("press_enter") ? args.get("press_enter")->as_bool(false) : false;
            dispatch_remote_text(txt);
            if (press_enter) {
                usleep(80000);
                uinput.send_android_action("ok");
            }
            res["ok"] = Value::make_bool(true);
            res["typed"] = Value::make_str(txt);
            res["pressEnter"] = Value::make_bool(press_enter);
            return res;
        }

        if (name == "music_control") {
            std::string act = args.get("action") ? args.get("action")->as_str() : "";
            std::string query = args.get("query") ? args.get("query")->as_str() : "";
            std::string mood = args.get("mood") ? args.get("mood")->as_str("any") : "any";
            bool open_ui = args.get("open_ui") ? args.get("open_ui")->as_bool(false) : false;

            if (act == "open") {
                bool udp_ok = ensure_launcher_service_udp(8086);
                Value payload = Value::make_obj();
                payload["cmd"] = Value::make_str("launch_music");
                if (udp_ok) {
                    send_udp_json(8086, payload.dump(-1));
                } else {
                    spawn_detached({"/system/bin/am", "start", "-n", "com.openstation.launcher/.YandexMusicActivity"});
                }
                res["ok"] = Value::make_bool(true);
                res["action"] = Value::make_str("open");
                res["music"] = get_music_json();
                return res;
            }

            if (act == "play_my_wave" || act == "play_query" || act == "play_liked") {
                if (!ensure_launcher_service_udp(8086)) {
                    res["ok"] = Value::make_bool(false);
                    res["error"] = Value::make_str("StationAudioService (UDP 8086) did not respond");
                    return res;
                }
                if (act == "play_query" && query.empty()) {
                    act = "play_my_wave";
                }
                uint64_t prev_seq = 0;
                std::string prev_track_id;
                {
                    std::lock_guard<std::mutex> lk(music_mu);
                    prev_seq = music_state.seq;
                    prev_track_id = music_state.track_id;
                }

                Value payload = Value::make_obj();
                if (act == "play_my_wave") {
                    payload["cmd"] = Value::make_str("play_my_wave");
                    payload["mood"] = Value::make_str(mood.empty() ? "any" : mood);
                    payload["openUi"] = Value::make_bool(open_ui);
                } else if (act == "play_query") {
                    payload["cmd"] = Value::make_str("play_query");
                    payload["query"] = Value::make_str(query);
                    payload["openUi"] = Value::make_bool(open_ui);
                } else {
                    payload["cmd"] = Value::make_str("music_action");
                    payload["action"] = Value::make_str("PLAY_LIKED");
                }
                send_udp_json(8086, payload.dump(-1));

                MusicPlaybackState cur;
                uint64_t seen_seq = prev_seq;
                bool saw_buffering = false;
                auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(3000);
                while (!g_stop.load() && std::chrono::steady_clock::now() < deadline) {
                    {
                        std::lock_guard<std::mutex> lk(music_mu);
                        cur = music_state;
                    }
                    if (cur.seq > seen_seq) {
                        seen_seq = cur.seq;
                        if (cur.buffering) {
                            saw_buffering = true;
                        } else if (!cur.error.empty()) {
                            break;
                        } else if (saw_buffering && cur.playing && !cur.title.empty()) {
                            break;
                        } else if (!cur.track_id.empty() && cur.track_id != prev_track_id && cur.playing) {
                            break;
                        }
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(60));
                }
                {
                    std::lock_guard<std::mutex> lk(music_mu);
                    cur = music_state;
                }

                bool auth_req = (cur.error.rfind("AUTH_REQUIRED:", 0) == 0);
                std::string err_clean = cur.error;
                if (auth_req) {
                    err_clean = err_clean.substr(strlen("AUTH_REQUIRED:"));
                    while (!err_clean.empty() && err_clean.front() == ' ') err_clean.erase(err_clean.begin());
                }
                bool has_err = !err_clean.empty();
                res["ok"] = Value::make_bool(!has_err);
                res["action"] = Value::make_str(act);
                res["playing"] = Value::make_bool(cur.playing);
                res["buffering"] = Value::make_bool(cur.buffering);
                res["source"] = Value::make_str(cur.source_label);
                res["hasAuth"] = Value::make_bool(cur.has_auth);
                res["authRequired"] = Value::make_bool(auth_req);
                if (!cur.track_id.empty()) {
                    res["trackId"] = Value::make_str(cur.track_id);
                    res["title"] = Value::make_str(cur.title);
                    res["artist"] = Value::make_str(cur.artist);
                    if (!cur.album.empty()) res["album"] = Value::make_str(cur.album);
                }
                if (!cur.codec_label.empty()) res["codec"] = Value::make_str(cur.codec_label);
                if (has_err) res["error"] = Value::make_str(err_clean);
                return res;
            }

            std::string player_act;
            std::string uinput_act;
            if (act == "play_pause") {
                player_act = "PLAY_PAUSE";
                uinput_act = "play_pause";
            } else if (act == "play") {
                player_act = "RESUME";
                uinput_act = "play_pause";
            } else if (act == "pause") {
                player_act = "PAUSE";
                uinput_act = "play_pause";
            } else if (act == "stop") {
                player_act = "STOP";
                uinput_act = "stop";
            } else if (act == "next") {
                player_act = "NEXT";
                uinput_act = "next";
            } else if (act == "prev" || act == "previous") {
                player_act = "PREV";
                uinput_act = "prev";
            } else if (act == "like") {
                player_act = "LIKE";
            } else if (act == "dislike") {
                player_act = "DISLIKE";
            } else {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("unknown music action");
                return res;
            }

            if (act == "play") {
                ensure_launcher_service_udp(8086);
            }
            if (is_udp_port_bound(8086)) {
                uint64_t prev_seq = 0;
                std::string prev_track_id;
                bool prev_liked = false;
                {
                    std::lock_guard<std::mutex> lk(music_mu);
                    prev_seq = music_state.seq;
                    prev_track_id = music_state.track_id;
                    prev_liked = music_state.liked;
                }
                Value payload = Value::make_obj();
                payload["cmd"] = Value::make_str("music_action");
                payload["action"] = Value::make_str(player_act);
                send_udp_json(8086, payload.dump(-1));

                if (act == "next" || act == "prev" || act == "dislike" || act == "like") {
                    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1600);
                    MusicPlaybackState cur;
                    while (!g_stop.load() && std::chrono::steady_clock::now() < deadline) {
                        {
                            std::lock_guard<std::mutex> lk(music_mu);
                            cur = music_state;
                        }
                        if (cur.seq > prev_seq) {
                            if (act == "like") {
                                if (cur.liked != prev_liked || !cur.error.empty()) break;
                            } else if ((!cur.buffering && cur.track_id != prev_track_id) || !cur.error.empty()) {
                                break;
                            }
                        }
                        std::this_thread::sleep_for(std::chrono::milliseconds(60));
                    }
                }
                MusicPlaybackState snap;
                {
                    std::lock_guard<std::mutex> lk(music_mu);
                    snap = music_state;
                }
                bool auth_req = (snap.error.rfind("AUTH_REQUIRED:", 0) == 0);
                res["ok"] = Value::make_bool(!auth_req);
                res["backend"] = Value::make_str("native_udp");
                res["action"] = Value::make_str(act);
                if (!snap.title.empty()) {
                    res["title"] = Value::make_str(snap.title);
                    res["artist"] = Value::make_str(snap.artist);
                }
                res["playing"] = Value::make_bool(snap.playing);
                res["liked"] = Value::make_bool(snap.liked);
                if (auth_req) {
                    res["authRequired"] = Value::make_bool(true);
                    res["error"] = Value::make_str(snap.error);
                }
                return res;
            } else if (!uinput_act.empty()) {
                bool ok = uinput.send_android_action(uinput_act);
                res["ok"] = Value::make_bool(ok);
                res["backend"] = Value::make_str("uinput");
                res["action"] = Value::make_str(act);
                return res;
            }
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("player service unavailable");
            return res;
        }

        if (name == "manage_apps") {
            std::string act = args.get("action") ? args.get("action")->as_str("list") : "list";
            if (act == "list") {
                res["ok"] = Value::make_bool(true);
                res["apps"] = list_apps_json_arr();
                return res;
            }
            if (act == "open_url") {
                std::string url = args.get("url") ? args.get("url")->as_str() : "";
                bool valid_url = (url.rfind("http://", 0) == 0 || url.rfind("https://", 0) == 0);
                for (unsigned char ch : url) {
                    if (ch <= 0x20) { valid_url = false; break; }
                }
                if (!valid_url) {
                    res["ok"] = Value::make_bool(false);
                    res["error"] = Value::make_str("valid http:// or https:// url is required");
                    return res;
                }
                bool spawned = spawn_detached({
                    "/system/bin/am", "start",
                    "-a", "android.intent.action.VIEW",
                    "-d", url
                });
                res["ok"] = Value::make_bool(spawned);
                res["url"] = Value::make_str(url);
                return res;
            }
            std::string q = args.get("app") ? args.get("app")->as_str() : "";
            if (q.empty()) {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("app name or package is required");
                return res;
            }
            if (act == "launch") {
                if (is_yandex_music_query(q)) {
                    Value payload = Value::make_obj();
                    payload["cmd"] = Value::make_str("launch_music");
                    send_udp_json(8086, payload.dump(-1));
                    if (!is_udp_port_bound(8086)) {
                        spawn_detached({"/system/bin/am", "start", "-n", "com.openstation.launcher/.YandexMusicActivity"});
                    }
                    res["ok"] = Value::make_bool(true);
                    res["label"] = Value::make_str("Яндекс Музыка · Моя волна");
                    return res;
                }
                std::string pkg, matched_label;
                resolve_app_pkg(q, pkg, matched_label);
                if (pkg.empty() || !is_valid_package_name(pkg)) {
                    res["ok"] = Value::make_bool(false);
                    res["error"] = Value::make_str("app not found");
                    return res;
                }
                Value payload = Value::make_obj();
                payload["cmd"] = Value::make_str("launch");
                payload["package"] = Value::make_str(pkg);
                send_udp_json(8086, payload.dump(-1));
                if (!is_udp_port_bound(8086)) {
                    spawn_detached({
                        "/system/bin/monkey", "-p", pkg,
                        "-c", "android.intent.category.LEANBACK_LAUNCHER",
                        "-c", "android.intent.category.LAUNCHER",
                        "1"
                    });
                }
                res["ok"] = Value::make_bool(true);
                res["package"] = Value::make_str(pkg);
                res["label"] = Value::make_str(matched_label);
                return res;
            }
            if (act == "close") {
                if (is_yandex_music_query(q)) {
                    Value payload = Value::make_obj();
                    payload["cmd"] = Value::make_str("music_action");
                    payload["action"] = Value::make_str("PAUSE");
                    send_udp_json(8086, payload.dump(-1));
                    uinput.send_android_action("home");
                    res["ok"] = Value::make_bool(true);
                    res["action"] = Value::make_str("minimized_home");
                    return res;
                }
                std::string pkg, matched_label;
                resolve_app_pkg(q, pkg, matched_label);
                if (pkg.empty() || !is_valid_package_name(pkg)) {
                    res["ok"] = Value::make_bool(false);
                    res["error"] = Value::make_str("app not found");
                    return res;
                }
                if (pkg == "com.openstation.launcher" || pkg == "com.openstation.ime") {
                    uinput.send_android_action("home");
                    res["ok"] = Value::make_bool(true);
                    res["package"] = Value::make_str(pkg);
                    res["note"] = Value::make_str("core OpenStation service kept alive; returned to Home");
                    return res;
                }
                ShellResult sr = run_shell_with_timeout("/system/bin/am force-stop " + pkg, 4, 512);
                res["ok"] = Value::make_bool(!sr.timed_out && sr.exit_code == 0);
                res["package"] = Value::make_str(pkg);
                if (!sr.output.empty()) res["output"] = Value::make_str(sr.output);
                return res;
            }
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("unknown manage_apps action");
            return res;
        }

        if (name == "list_apps") {
            res["ok"] = Value::make_bool(true);
            res["apps"] = list_apps_json_arr();
            return res;
        }

        if (name == "launch_app") {
            std::string q = args.get("app") ? args.get("app")->as_str() : "";
            if (is_yandex_music_query(q)) {
                Value payload = Value::make_obj();
                payload["cmd"] = Value::make_str("launch_music");
                send_udp_json(8086, payload.dump(-1));
                if (!is_udp_port_bound(8086)) {
                    spawn_detached({"/system/bin/am", "start", "-n", "com.openstation.launcher/.YandexMusicActivity"});
                }
                res["ok"] = Value::make_bool(true);
                res["label"] = Value::make_str("Яндекс Музыка · Моя волна");
                return res;
            }
            std::string pkg, matched_label;
            resolve_app_pkg(q, pkg, matched_label);
            if (pkg.empty() || !is_valid_package_name(pkg)) {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("app not found");
                return res;
            }
            Value payload = Value::make_obj();
            payload["cmd"] = Value::make_str("launch");
            payload["package"] = Value::make_str(pkg);
            send_udp_json(8086, payload.dump(-1));
            if (!is_udp_port_bound(8086)) {
                spawn_detached({
                    "/system/bin/monkey", "-p", pkg,
                    "-c", "android.intent.category.LEANBACK_LAUNCHER",
                    "-c", "android.intent.category.LAUNCHER",
                    "1"
                });
            }
            res["ok"] = Value::make_bool(true);
            res["package"] = Value::make_str(pkg);
            res["label"] = Value::make_str(matched_label);
            return res;
        }

        if (name == "mqtt_publish") {
            std::string topic = args.get("topic") ? args.get("topic")->as_str() : "";
            std::string payload = args.get("payload") ? args.get("payload")->as_str() : "";
            bool retain = args.get("retain") ? args.get("retain")->as_bool(false) : false;
            if (topic.empty()) {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("topic is required");
                return res;
            }
            if (!mqtt.is_connected()) {
                res["ok"] = Value::make_bool(false);
                res["error"] = Value::make_str("MQTT broker is not connected or disabled");
                return res;
            }
            bool sent = mqtt.publish(topic, payload, retain);
            res["ok"] = Value::make_bool(sent);
            res["topic"] = Value::make_str(topic);
            return res;
        }

        if (name == "stop_assistant") {
            res["ok"] = Value::make_bool(true);
            res["stopSession"] = Value::make_bool(true);
            return res;
        }

        res["ok"] = Value::make_bool(false);
        res["error"] = Value::make_str("unknown tool");
        return res;
    };
    assistant.start(cfg.assistant, std::move(acb));

    wyoming.start(
        cfg.wyoming,
        [&](const int16_t* samples, size_t frames, int rate, int ch) {
            audio.play_pcm(samples, frames, rate, ch);
        },
        [&](const std::string& st) {
            if (!assistant.is_active()) {
                if (st == "streaming") leds.set_voice_state(VoiceVisualState::Listening);
                else if (st == "thinking") leds.set_voice_state(VoiceVisualState::Thinking);
                else if (st == "tts") leds.set_voice_state(VoiceVisualState::Speaking);
                else leds.set_voice_state(VoiceVisualState::Idle);
                set_music_duck(st == "streaming" || st == "thinking" || st == "tts");
            }
        });

    audio.set_spectrum_needed_cb([&]() {
        return leds.needs_spectrum();
    });

    audio.start(
        cfg.audio,
        [&](const int16_t* pcm, size_t count, float rms) {
            DaemonConfig c_snap;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                c_snap = cfg;
            }
            if (c_snap.assistant.enabled && c_snap.assistant.mode == "gemini") {
                assistant.feed_audio_16k(pcm, count, rms);
            }
            if (c_snap.wyoming.enabled && (c_snap.assistant.mode == "wyoming" || !c_snap.assistant.enabled)) {
                wyoming.feed_audio_16k(pcm, count, rms);
            }
        },
        [&](const std::array<float, 24>& bands) {
            leds.set_spectrum(bands);
        });

    audio.set_ws_audio_sink([&](const int16_t* pcm, size_t samples) {
        http.broadcast_ws_audio(pcm, samples);
    });

    mdns.start(cfg.device_hostname, cfg.web_port, cfg.wyoming.port);
    adb_mgr.start(cfg.network, cfg.device_hostname, [&](int adb_port) {
        mdns.update_adb_port(adb_port);
    });

    mqtt.start(cfg.mqtt, [&](const std::string& topic_suffix, const std::string& payload) {
        DaemonConfig next_c;
        bool dirty = false;
        {
            std::lock_guard<std::mutex> lk(cfg_mu);
            if (topic_suffix == "ring/set") {
                json::Value v;
                if (json::Parser::parse(payload, v) && v.is_obj()) {
                    if (auto* s = v.get("state"); s && s->is_str()) {
                        if (s->as_str() == "OFF") cfg.led_ring.mode = "off";
                        else if (cfg.led_ring.mode == "off") cfg.led_ring.mode = "solid";
                    }
                    if (auto* b = v.get("brightness"); b && b->is_num()) {
                        cfg.led_ring.brightness = std::clamp(b->as_int(), 0, 255);
                        cfg.led_ring.auto_brightness = false;
                    }
                    if (auto* ef = v.get("effect"); ef && ef->is_str()) {
                        std::string m = ef->as_str();
                        if (is_valid_ring_mode(m)) {
                            cfg.led_ring.mode = (m == "spectrum") ? "music" : m;
                        }
                    }
                    if (auto* col = v.get("color"); col && col->is_obj()) {
                        int r = col->get("r") ? col->get("r")->as_int(0) : 0;
                        int g = col->get("g") ? col->get("g")->as_int(212) : 212;
                        int b = col->get("b") ? col->get("b")->as_int(255) : 255;
                        char hx[16];
                        snprintf(hx, sizeof(hx), "#%02x%02x%02x",
                                 std::clamp(r, 0, 255),
                                 std::clamp(g, 0, 255),
                                 std::clamp(b, 0, 255));
                        cfg.led_ring.color = hx;
                    }
                    dirty = true;
                }
            } else if (topic_suffix == "mute/set") {
                cfg.audio.mic_muted = (payload == "ON" || payload == "true" || payload == "1");
                dirty = true;
            } else if (topic_suffix == "woofer_vol/set") {
                int raw_v = std::clamp(atoi(payload.c_str()), 0, 255);
                int base_woo = curve_base_speaker_reg(std::max(1, volume_osd_pct.load()));
                cfg.audio.woofer_trim = std::clamp(raw_v - base_woo, -40, 40);
                dirty = true;
            } else if (topic_suffix == "route/set") {
                if (is_valid_output_route(payload)) {
                    cfg.audio.output_route = payload;
                    dirty = true;
                }
            }
            if (dirty) {
                cfg.sanitize_and_validate();
                next_c = cfg;
            }
        }
        if (dirty) {
            apply_hw_config(next_c, inputs.headphone_plugged());
            save_cfg(next_c);
        }
    });

    auto build_status_json = [&]() -> json::Value {
        using json::Value;
        DaemonConfig c;
        {
            std::lock_guard<std::mutex> lk(cfg_mu);
            c = cfg;
        }
        auto up = std::chrono::duration_cast<std::chrono::seconds>(
                      std::chrono::steady_clock::now() - start_tp)
                      .count();
        std::string local_ip = MdnsResponder::detect_local_ipv4();
        Value root = Value::make_obj();
        root["hostname"] = Value::make_str(c.device_hostname);
        root["ip"] = Value::make_str(local_ip);
        root["uptimeSec"] = Value::make_int(up);
        root["firmwareVersion"] = Value::make_str(kFirmwareVersion);
        root["autonomous"] = Value::make_bool(true);
        root["device"] = Value::make_str("Yandex Station Max (yandexstation_2 / S905X2)");
        {
            std::lock_guard<std::mutex> lk(dns_mu);
            root["privateDnsMode"] = Value::make_str(private_dns_mode_cache);
            root["privateDnsHost"] = Value::make_str(private_dns_host_cache);
        }
        root["hardware"] = leds.get_status_json();

        Value au = Value::make_obj();
        au["mixer"] = mixer.get_status_json();
        au["engine"] = audio.get_status_json();
        au["volumePercent"] = Value::make_int(volume_osd_pct.load());
        au["volumeMaxSteps"] = Value::make_int(android_vol_max.load());
        root["audio"] = std::move(au);

        root["assistant"] = assistant.get_status_json();
        root["music"] = get_music_json();
        root["timers"] = timers.list_timers_json();
        root["wyoming"] = wyoming.get_status_json();
        root["mqtt"] = mqtt.get_status_json();
        root["ota"] = ota.get_status_json();
        root["bootanim"] = ota.get_bootanim_status_json();
        root["wifiAdb"] = adb_mgr.get_status_json(local_ip, c.device_hostname);
        {
            std::lock_guard<std::mutex> lk(last_weather_mu);
            if (has_last_weather) {
                root["lastWeather"] = last_weather_json;
            }
        }
        root["config"] = c.to_json();
        return root;
    };

    Logger::instance().set_sink([&](const std::string& line) {
        if (http.ws_client_count() > 0) {
            json::Value msg = json::Value::make_obj();
            msg["type"] = json::Value::make_str("log");
            msg["line"] = json::Value::make_str(line);
            http.broadcast_ws_text(msg.dump(-1));
        }
    });

    http.start(
        cfg.web_port,
        [&](const HttpRequest& req) -> HttpResponse {
            if (req.method == "GET" && req.path == "/api/status") {
                return HttpResponse::json(build_status_json().dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/config") {
                json::Value v;
                if (!json::Parser::parse(req.body, v)) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    cfg.from_json(v);
                    c = cfg;
                }
                apply_hw_config(c, inputs.headphone_plugged());
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    c = cfg;
                }
                save_cfg(c);
                return HttpResponse::json(build_status_json().dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/led/calibrate") {
                json::Value v;
                int mode = -2;
                if (json::Parser::parse(req.body, v) && v.get("mode")) {
                    mode = v.get("mode")->as_int(-2);
                }
                leds.trigger_calibration(mode, 4500);
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "GET" && req.path == "/api/weather") {
                std::string city = url_decode_str(get_query_param(req.query, "city"));
                int days = 3;
                std::string ds = get_query_param(req.query, "days");
                if (!ds.empty()) days = std::clamp(atoi(ds.c_str()), 1, 3);
                bool silent = (get_query_param(req.query, "silent") == "1");

                std::string fb_city;
                double fb_lat = 0.0, fb_lon = 0.0;
                bool auto_det = true;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    auto_det = cfg.location.auto_detect;
                    fb_city = !cfg.location.city.empty() ? cfg.location.city : cfg.location.resolved_city;
                    if (cfg.location.city.empty()) {
                        fb_lat = cfg.location.lat;
                        fb_lon = cfg.location.lon;
                    }
                }
                if (city.empty() && fb_city.empty() && fb_lat == 0.0 && fb_lon == 0.0 && auto_det) {
                    GeoLocationResult geo = detect_location_by_ip(&g_stop);
                    if (geo.ok) {
                        fb_city = geo.city;
                        fb_lat = geo.lat;
                        fb_lon = geo.lon;
                        DaemonConfig c;
                        {
                            std::lock_guard<std::mutex> lk(cfg_mu);
                            cfg.location.resolved_city = geo.city;
                            cfg.location.resolved_country = geo.country;
                            cfg.location.resolved_epoch = static_cast<int64_t>(std::time(nullptr));
                            if (cfg.location.city.empty()) {
                                cfg.location.lat = geo.lat;
                                cfg.location.lon = geo.lon;
                            }
                            cfg.sanitize_and_validate();
                            c = cfg;
                        }
                        save_cfg(c);
                        push_style_sync_to_launcher(c);
                    }
                }
                json::Value wres = tool_get_weather(city, days, fb_lat, fb_lon, fb_city, &g_stop);
                if (wres.get("ok") && wres.get("ok")->as_bool(false)) {
                    trigger_weather_visuals(wres, !silent);
                }
                return HttpResponse::json(wres.dump(-1), (wres.get("ok") && wres.get("ok")->as_bool(false)) ? 200 : 502);
            }
            if (req.method == "POST" && req.path == "/api/location/detect") {
                GeoLocationResult geo = detect_location_by_ip(&g_stop);
                if (!geo.ok) {
                    json::Value err = json::Value::make_obj();
                    err["ok"] = json::Value::make_bool(false);
                    err["error"] = json::Value::make_str(geo.error.empty() ? "IP geolocation failed" : geo.error);
                    return HttpResponse::json(err.dump(-1), 502);
                }
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    cfg.location.auto_detect = true;
                    cfg.location.resolved_city = geo.city;
                    cfg.location.resolved_country = geo.country;
                    cfg.location.resolved_epoch = static_cast<int64_t>(std::time(nullptr));
                    if (cfg.location.city.empty()) {
                        cfg.location.lat = geo.lat;
                        cfg.location.lon = geo.lon;
                    }
                    cfg.sanitize_and_validate();
                    c = cfg;
                }
                save_cfg(c);
                push_style_sync_to_launcher(c);
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(true);
                out["city"] = json::Value::make_str(geo.city);
                out["country"] = json::Value::make_str(geo.country);
                out["lat"] = json::Value::make_num(geo.lat);
                out["lon"] = json::Value::make_num(geo.lon);
                out["location"] = c.to_json()["location"];
                return HttpResponse::json(out.dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/audio/eq") {
                json::Value v;
                if (!json::Parser::parse(req.body, v) || !v.is_obj()) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    bool preset_given = false;
                    bool bands_given = false;
                    bool bass_given = false;
                    std::string next_preset = cfg.audio.eq_preset;
                    if (const auto* p = v.get("preset"); p && p->is_str()) {
                        if (!is_valid_eq_preset(p->as_str())) {
                            return HttpResponse::json("{\"error\":\"invalid preset\"}", 400);
                        }
                        next_preset = p->as_str();
                        cfg.audio.eq_preset = next_preset;
                        preset_given = true;
                    }
                    if (const auto* eb = v.get("bands") ? v.get("bands") : v.get("eqBands");
                        eb && eb->is_arr() && !eb->arr_val.empty()) {
                        for (size_t i = 0; i < 10 && i < eb->arr_val.size(); ++i) {
                            cfg.audio.eq_bands[i] = std::clamp(static_cast<float>(eb->arr_val[i].as_double(0.0)), -12.0f, 12.0f);
                        }
                        bands_given = true;
                    }
                    if (const auto* bb = v.get("bassBoost"); bb && bb->is_num()) {
                        cfg.audio.bass_boost = std::clamp(bb->as_int(cfg.audio.bass_boost), 0, 100);
                        bass_given = true;
                    }
                    if (preset_given && next_preset != "custom" && !bands_given) {
                        int def_bass = cfg.audio.bass_boost;
                        apply_eq_preset_defaults(next_preset, cfg.audio.eq_bands, def_bass);
                        if (!bass_given) cfg.audio.bass_boost = def_bass;
                    } else if ((bands_given || bass_given) && !preset_given) {
                        cfg.audio.eq_preset = "custom";
                    }
                    cfg.sanitize_and_validate();
                    c = cfg;
                }
                audio.update_config(c.audio);
                push_eq_to_launcher(c);
                push_style_sync_to_launcher(c);
                save_cfg(c);
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(true);
                out["preset"] = json::Value::make_str(c.audio.eq_preset);
                out["bassBoost"] = json::Value::make_int(c.audio.bass_boost);
                json::Value eb = json::Value::make_arr();
                for (float g : c.audio.eq_bands) eb.push(json::Value::make_num(g));
                out["eqBands"] = std::move(eb);
                return HttpResponse::json(out.dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/audio/route") {
                json::Value v;
                if (json::Parser::parse(req.body, v) && v.get("route") && v.get("route")->is_str()) {
                    std::string route = v.get("route")->as_str();
                    if (!is_valid_output_route(route)) {
                        return HttpResponse::json("{\"error\":\"invalid route\"}", 400);
                    }
                    DaemonConfig c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        cfg.audio.output_route = route;
                    }
                    reapply_current_volume_and_route(true, true);
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        c = cfg;
                    }
                    save_cfg(c);
                    return HttpResponse::json("{\"ok\":true}");
                }
                return HttpResponse::json("{\"error\":\"missing route\"}", 400);
            }
            if (req.method == "POST" && req.path == "/api/audio/reload_eq") {
                bool ok = mixer.reload_amp_defaults();
                if (ok) {
                    DaemonConfig c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        c = cfg;
                    }
                    mixer.set_bool("Woofer EQ Switch", c.audio.woofer_eq);
                    mixer.set_bool("Woofer DRC Switch", c.audio.woofer_drc);
                    mixer.set_bool("Tweeters EQ Switch", c.audio.tweeters_eq);
                    mixer.set_bool("Tweeters DRC Switch", c.audio.tweeters_drc);
                    mixer.set_enum("Loopback Enable", c.audio.loopback_enable ? "Enable" : "Disable");
                    reapply_current_volume_and_route(true, true);
                }
                return HttpResponse::json(ok ? "{\"ok\":true}" : "{\"ok\":false}");
            }
            if (req.method == "POST" && req.path == "/api/audio/test_tone") {
                json::Value v;
                int freq = 440, dur = 700;
                bool sweep = false;
                if (json::Parser::parse(req.body, v)) {
                    if (v.get("freq")) freq = v.get("freq")->as_int(440);
                    if (v.get("duration")) dur = v.get("duration")->as_int(700);
                    if (v.get("sweep")) sweep = v.get("sweep")->as_bool(false);
                }
                audio.play_test_tone(freq, dur, sweep);
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/audio/capture_toggle") {
                json::Value v;
                bool en = true;
                if (json::Parser::parse(req.body, v) && v.get("enabled")) {
                    en = v.get("enabled")->as_bool(true);
                }
                audio.set_capture_enabled(en);
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/audio/spectrum") {
                json::Value v;
                if (json::Parser::parse(req.body, v)) {
                    const json::Value* arr = v.get("bands");
                    if (arr && arr->is_arr()) {
                        std::array<float, 24> bands{};
                        for (size_t i = 0; i < std::min<size_t>(24, arr->arr_val.size()); ++i) {
                            double dv = arr->arr_val[i].as_double(0.0);
                            float fv = std::isfinite(dv) ? static_cast<float>(dv) : 0.0f;
                            bands[i] = std::clamp(fv, 0.0f, 1.0f);
                        }
                        audio.push_external_spectrum(bands);
                    }
                }
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "GET" && req.path == "/api/audio/wav") {
                int sec = 5;
                std::string qs = get_query_param(req.query, "sec");
                if (!qs.empty()) sec = atoi(qs.c_str());
                auto wav = audio.export_recent_wav(sec);
                return HttpResponse::bytes(std::move(wav), "audio/wav", 200);
            }
            if (req.method == "POST" && req.path == "/api/wyoming/trigger") {
                int dur = 6000;
                json::Value v;
                if (json::Parser::parse(req.body, v) && v.get("duration")) {
                    dur = v.get("duration")->as_int(6000);
                }
                wyoming.trigger_button_session(dur);
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/assistant/trigger") {
                std::string reason = "webui";
                json::Value v;
                if (json::Parser::parse(req.body, v) && v.get("reason") && v.get("reason")->is_str()) {
                    reason = v.get("reason")->as_str();
                }
                bool ok = assistant.trigger_session(reason);
                return HttpResponse::json(ok ? "{\"ok\":true}" : "{\"ok\":false}", ok ? 200 : 400);
            }
            if (req.method == "POST" && req.path == "/api/assistant/stop") {
                assistant.stop_session("webui_stop");
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/assistant/text") {
                json::Value v;
                if (!json::Parser::parse(req.body, v) || !v.get("text") || !v.get("text")->is_str()) {
                    return HttpResponse::json("{\"error\":\"missing text\"}", 400);
                }
                bool ok = assistant.send_text_prompt(v.get("text")->as_str());
                return HttpResponse::json(ok ? "{\"ok\":true}" : "{\"ok\":false}", ok ? 200 : 400);
            }
            if (req.method == "POST" && req.path == "/api/assistant/test") {
                json::Value v;
                json::Parser::parse(req.body, v);
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    c = cfg;
                }
                std::string key = c.assistant.gemini_api_key;
                std::string model = c.assistant.gemini_model;
                std::string proxy = c.assistant.gemini_proxy_host;
                if (v.is_obj()) {
                    if (const auto* k = v.get("geminiApiKey"); k && k->is_str() && !trim_ws_copy(k->as_str()).empty()) {
                        key = k->as_str();
                    }
                    if (const auto* m = v.get("geminiModel"); m && m->is_str() && !trim_ws_copy(m->as_str()).empty()) {
                        model = m->as_str();
                    }
                    if (const auto* p = v.get("geminiProxyHost"); p && p->is_str()) {
                        proxy = p->as_str();
                    }
                }
                json::Value pr = probe_gemini_live_json(key, model, proxy);
                return HttpResponse::json(pr.dump(-1), 200);
            }
            if (req.method == "POST" && req.path == "/api/setup/complete") {
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    cfg.setup_completed = true;
                    c = cfg;
                }
                save_cfg(c);
                json::Value st = build_status_json();
                std::string out = st.dump(-1);
                if (http.ws_client_count() > 0) {
                    json::Value msg = json::Value::make_obj();
                    msg["type"] = json::Value::make_str("telemetry");
                    msg["data"] = std::move(st);
                    http.broadcast_ws_text(msg.dump(-1));
                }
                return HttpResponse::json(out);
            }
            if (req.method == "POST" && req.path == "/api/setup/reset") {
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    cfg.setup_completed = false;
                    c = cfg;
                }
                save_cfg(c);
                json::Value st = build_status_json();
                std::string out = st.dump(-1);
                if (http.ws_client_count() > 0) {
                    json::Value msg = json::Value::make_obj();
                    msg["type"] = json::Value::make_str("telemetry");
                    msg["data"] = std::move(st);
                    http.broadcast_ws_text(msg.dump(-1));
                }
                return HttpResponse::json(out);
            }
            if (req.method == "POST" && req.path == "/api/system/dns") {
                json::Value v;
                if (!json::Parser::parse(req.body, v) || !v.is_obj()) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                std::string mode = "hostname";
                if (const auto* mp = v.get("mode"); mp && mp->is_str()) mode = mp->as_str();
                if (mode != "hostname" && mode != "opportunistic" && mode != "off") {
                    return HttpResponse::json("{\"error\":\"invalid mode\"}", 400);
                }
                std::string host;
                if (const auto* hp = v.get("host"); hp && hp->is_str()) host = trim_ws_copy(hp->as_str());
                if (mode == "hostname" && !is_valid_dns_host(host)) {
                    return HttpResponse::json("{\"error\":\"invalid dns host\"}", 400);
                }
                std::string cmd = "/system/bin/settings put global private_dns_mode " + mode;
                if (mode == "hostname" && !host.empty()) {
                    cmd += " && /system/bin/settings put global private_dns_specifier " + host;
                }
                ShellResult sr = run_shell_with_timeout(cmd, 4, 256);
                refresh_private_dns_cache();
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(!sr.timed_out && sr.exit_code == 0);
                {
                    std::lock_guard<std::mutex> lk(dns_mu);
                    out["mode"] = json::Value::make_str(private_dns_mode_cache);
                    out["host"] = json::Value::make_str(private_dns_host_cache);
                }
                if (!sr.output.empty()) out["output"] = json::Value::make_str(sr.output);
                return HttpResponse::json(out.dump(-1));
            }
            if (req.method == "GET" && req.path == "/api/system/wifi_adb") {
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    c = cfg;
                }
                std::string local_ip = MdnsResponder::detect_local_ipv4();
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(true);
                out["wifiAdb"] = adb_mgr.get_status_json(local_ip, c.device_hostname);
                return HttpResponse::json(out.dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/system/wifi_adb") {
                std::string action = "repair";
                bool cfg_dirty = false;
                DaemonConfig c;
                if (!req.body.empty()) {
                    json::Value v;
                    if (!json::Parser::parse(req.body, v) || !v.is_obj()) {
                        return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                    }
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    if (const auto* ap = v.get("action"); ap && ap->is_str()) {
                        action = ap->as_str();
                        if (action != "repair" && action != "restart" &&
                            action != "enable" && action != "disable") {
                            return HttpResponse::json("{\"error\":\"invalid action\"}", 400);
                        }
                        if (action == "enable") {
                            cfg.network.wifi_adb_enabled = true;
                            cfg_dirty = true;
                        } else if (action == "disable") {
                            cfg.network.wifi_adb_enabled = false;
                            cfg_dirty = true;
                        }
                    }
                    if (const auto* ep = v.get("enabled"); ep && ep->is_bool()) {
                        cfg.network.wifi_adb_enabled = ep->as_bool(cfg.network.wifi_adb_enabled);
                        cfg_dirty = true;
                    }
                    if (const auto* pp = v.get("port"); pp && pp->is_num()) {
                        int port_val = pp->as_int(cfg.network.wifi_adb_port);
                        if (port_val < 1024 || port_val > 65535) {
                            return HttpResponse::json("{\"error\":\"invalid port (1024..65535)\"}", 400);
                        }
                        cfg.network.wifi_adb_port = port_val;
                        cfg_dirty = true;
                    }
                    if (const auto* pw = v.get("wifiPowerSaveOff"); pw && pw->is_bool()) {
                        cfg.network.wifi_power_save_off = pw->as_bool(cfg.network.wifi_power_save_off);
                        cfg_dirty = true;
                    }
                    if (cfg_dirty) {
                        cfg.sanitize_and_validate();
                    }
                    c = cfg;
                } else {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    c = cfg;
                }

                if (cfg_dirty) {
                    adb_mgr.update_config(c.network, c.device_hostname);
                    save_cfg(c);
                }

                bool force_restart = (action == "restart");
                bool ok = adb_mgr.repair_now("webui_" + action, force_restart);
                json::Value st = build_status_json();
                st["ok"] = json::Value::make_bool(ok);
                std::string out = st.dump(-1);
                if (http.ws_client_count() > 0) {
                    json::Value msg = json::Value::make_obj();
                    msg["type"] = json::Value::make_str("telemetry");
                    msg["data"] = std::move(st);
                    http.broadcast_ws_text(msg.dump(-1));
                }
                return HttpResponse::json(out);
            }
            if (req.method == "POST" && req.path == "/api/system/restart_daemon") {
                spawn_detached({
                    "/system/bin/sh", "-c",
                    "sleep 0.5; killall openstationd 2>/dev/null || true; sleep 1; "
                    "export LD_LIBRARY_PATH=/data/local/openstation/lib:/vendor/lib64:/system/lib64; "
                    "/data/local/openstation/openstationd --daemon </dev/null >/data/local/openstation/boot.log 2>&1"
                });
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/system/reboot") {
                spawn_detached({
                    "/system/bin/sh", "-c",
                    "sleep 0.6; /system/bin/svc power reboot || /system/bin/reboot"
                });
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "GET" && req.path == "/api/ota/status") {
                return HttpResponse::json(ota.get_status_json().dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/ota/check") {
                if (!req.body.empty()) {
                    json::Value v;
                    if (json::Parser::parse(req.body, v) && v.is_obj()) {
                        DaemonConfig c;
                        bool updated = false;
                        {
                            std::lock_guard<std::mutex> lk(cfg_mu);
                            if (const auto* rp = v.get("repo"); rp && rp->is_str()) {
                                cfg.ota.repo = rp->as_str();
                                updated = true;
                            }
                            if (const auto* br = v.get("branch"); br && br->is_str()) {
                                cfg.ota.branch = br->as_str();
                                updated = true;
                            }
                            if (const auto* ac = v.get("autoCheck"); ac && ac->is_bool()) {
                                cfg.ota.auto_check = ac->as_bool(cfg.ota.auto_check);
                                updated = true;
                            }
                            if (const auto* sb = v.get("syncBootanimation"); sb && sb->is_bool()) {
                                cfg.ota.sync_bootanimation = sb->as_bool(cfg.ota.sync_bootanimation);
                                updated = true;
                            }
                            if (updated) {
                                cfg.sanitize_and_validate();
                                c = cfg;
                            }
                        }
                        if (updated) {
                            ota.update_config(c.ota);
                            save_cfg(c);
                        }
                    }
                }
                bool started = ota.check_now();
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(started);
                out["started"] = json::Value::make_bool(started);
                out["ota"] = ota.get_status_json();
                return HttpResponse::json(out.dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/ota/install") {
                bool force = false;
                if (!req.body.empty()) {
                    json::Value v;
                    if (json::Parser::parse(req.body, v) && v.is_obj()) {
                        if (const auto* fp = v.get("force"); fp) force = fp->as_bool(false);
                    }
                }
                OtaInstallStartResult r = ota.start_install(force);
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(r.accepted);
                out["message"] = json::Value::make_str(r.message);
                out["ota"] = ota.get_status_json();
                return HttpResponse::json(out.dump(-1));
            }
            if (req.method == "GET" && req.path == "/api/bootanim/status") {
                return HttpResponse::json(ota.get_bootanim_status_json().dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/bootanim/upload") {
                BootAnimOpResult r = ota.apply_uploaded_bootanimation(req.body.data(), req.body.size());
                if (r.ok) {
                    DaemonConfig c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        cfg.ota.sync_bootanimation = false;
                        c = cfg;
                    }
                    ota.update_config(c.ota);
                    save_cfg(c);
                }
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(r.ok);
                out["message"] = json::Value::make_str(r.message);
                out["bootanim"] = ota.get_bootanim_status_json();
                return HttpResponse::json(out.dump(-1), r.ok ? 200 : 400);
            }
            if (req.method == "POST" && req.path == "/api/bootanim/restore") {
                BootAnimOpResult r = ota.restore_stock_bootanimation();
                if (r.ok) {
                    DaemonConfig c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        cfg.ota.sync_bootanimation = false;
                        c = cfg;
                    }
                    ota.update_config(c.ota);
                    save_cfg(c);
                }
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(r.ok);
                out["message"] = json::Value::make_str(r.message);
                out["bootanim"] = ota.get_bootanim_status_json();
                return HttpResponse::json(out.dump(-1), r.ok ? 200 : 400);
            }
            if (req.method == "POST" && req.path == "/api/bootanim/install_ota") {
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    cfg.ota.sync_bootanimation = true;
                    c = cfg;
                }
                ota.update_config(c.ota);
                save_cfg(c);
                BootAnimOpResult r = ota.install_openstation_bootanimation_sync();
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(r.ok);
                out["message"] = json::Value::make_str(r.message);
                out["bootanim"] = ota.get_bootanim_status_json();
                out["ota"] = ota.get_status_json();
                return HttpResponse::json(out.dump(-1), r.ok ? 200 : 400);
            }
            if (req.method == "POST" && req.path == "/api/bootanim/preview") {
                spawn_detached({
                    "/system/bin/sh", "-c",
                    "setprop service.bootanim.exit 0; setprop ctl.start bootanim; sleep 5; "
                    "setprop service.bootanim.exit 1; sleep 1; setprop ctl.stop bootanim"
                });
                return HttpResponse::json("{\"ok\":true,\"message\":\"Предпросмотр заставки запущен на экране ТВ (5 сек)\"}");
            }
            if (req.method == "POST" && req.path == "/api/audio/volume") {
                json::Value v;
                if (!json::Parser::parse(req.body, v)) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                int woo = 3, twe = 3, hp = 0;
                int applied = 0;
                if (const auto* p = v.get("percent"); p && p->is_num()) {
                    applied = set_android_volume_pct(p->as_int(volume_osd_pct.load()), true, &woo, &twe, &hp);
                } else if (const auto* d = v.get("delta"); d && d->is_num()) {
                    applied = step_android_volume_pct(d->as_int(0), true, &woo, &twe, &hp);
                } else {
                    applied = step_android_volume_pct(0, true, &woo, &twe, &hp);
                }
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(true);
                out["volumePercent"] = json::Value::make_int(applied);
                out["wooferVolume"] = json::Value::make_int(woo);
                out["tweetersVolume"] = json::Value::make_int(twe);
                out["headphoneVolume"] = json::Value::make_int(hp);
                return HttpResponse::json(out.dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/audio/volume_state") {
                json::Value v;
                if (!json::Parser::parse(req.body, v)) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                if (const auto* m = v.get("max"); m && m->is_num()) {
                    android_vol_max.store(std::clamp(m->as_int(15), 1, 200));
                }
                int applied_pct = 0;
                bool suppressed = false;
                {
                    std::lock_guard<std::mutex> lk(vol_apply_mu);
                    int64_t age_ms = steady_ms_now() - last_daemon_vol_cmd_ms.load();
                    if (age_ms >= 0 && age_ms < 900) {
                        suppressed = true;
                    } else {
                        int pct = volume_osd_pct.load();
                        if (const auto* p = v.get("percent"); p && p->is_num()) {
                            pct = std::clamp(p->as_int(pct), 0, 100);
                            volume_osd_pct.store(pct);
                        }
                        bool first_sync = !first_vol_state_seen.exchange(true);
                        apply_curve_unlocked(pct, first_sync, true, false);
                        reconcile_unlocked();
                        applied_pct = pct;
                    }
                }
                if (suppressed) {
                    return HttpResponse::json("{\"ok\":true,\"suppressed\":true}");
                }
                bool show_osd = v.get("showOsd") ? v.get("showOsd")->as_bool(true) : true;
                if (show_osd) {
                    leds.trigger_volume_osd(applied_pct);
                } else {
                    leds.update_volume_osd_if_active(applied_pct);
                }
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/screen/text") {
                json::Value v;
                if (!json::Parser::parse(req.body, v) || !v.get("text") || !v.get("text")->is_str()) {
                    return HttpResponse::json("{\"error\":\"missing text\"}", 400);
                }
                std::string txt = v.get("text")->as_str();
                int dur = v.get("durationMs") ? v.get("durationMs")->as_int(4000) : 4000;
                uint8_t r = static_cast<uint8_t>(std::clamp(v.get("r") ? v.get("r")->as_int(0) : 0, 0, 255));
                uint8_t g = static_cast<uint8_t>(std::clamp(v.get("g") ? v.get("g")->as_int(220) : 220, 0, 255));
                uint8_t b = static_cast<uint8_t>(std::clamp(v.get("b") ? v.get("b")->as_int(255) : 255, 0, 255));
                leds.trigger_screen_text(txt, r, g, b, dur);
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/music/state") {
                json::Value v;
                if (!json::Parser::parse(req.body, v)) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                int64_t now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                     std::chrono::steady_clock::now().time_since_epoch())
                                     .count();
                {
                    std::lock_guard<std::mutex> lk(music_mu);
                    music_state.update_from_json(v, now_ms);
                }
                update_led_music_playing();
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/music/command") {
                json::Value v;
                if (!json::Parser::parse(req.body, v)) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                bool udp_ok = ensure_launcher_service_udp(8086);
                std::string raw = v.dump(-1);
                bool sent = udp_ok && send_udp_json(8086, raw);
                if (const auto* cmd = v.get("cmd"); cmd && cmd->is_str() && cmd->as_str() == "launch_music") {
                    if (!sent) {
                        spawn_detached({"/system/bin/am", "start", "-n", "com.openstation.launcher/.YandexMusicActivity"});
                        sent = true;
                    }
                }
                return HttpResponse::json(sent ? "{\"ok\":true}" : "{\"ok\":false}");
            }
            if (req.method == "POST" && req.path == "/api/remote/key") {
                json::Value v;
                if (!json::Parser::parse(req.body, v)) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                bool ok = false;
                if (auto* act = v.get("action"); act && act->is_str()) {
                    ok = uinput.send_android_action(act->as_str());
                } else if (auto* kc = v.get("keycode"); kc && kc->is_num()) {
                    ok = uinput.send_key(kc->as_int());
                }
                return HttpResponse::json(ok ? "{\"ok\":true}" : "{\"ok\":false}");
            }
            if (req.method == "POST" && req.path == "/api/remote/text") {
                json::Value v;
                if (!json::Parser::parse(req.body, v) || !v.get("text")) {
                    return HttpResponse::json("{\"error\":\"missing text\"}", 400);
                }
                dispatch_remote_text(v.get("text")->as_str());
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/remote/cec") {
                json::Value v;
                if (!json::Parser::parse(req.body, v) || !v.get("cmd")) {
                    return HttpResponse::json("{\"error\":\"missing cmd\"}", 400);
                }
                std::string act = normalize_cec_action(v.get("cmd")->as_str());
                exec_hdmi_cec_action(act);
                if (act == "wake") {
                    uinput.send_key(143); // KEY_WAKEUP
                }
                json::Value payload = json::Value::make_obj();
                payload["cmd"] = json::Value::make_str("cec");
                payload["action"] = json::Value::make_str(act);
                send_udp_json(8086, payload.dump(-1));
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "POST" && req.path == "/api/apps/cache") {
                json::Value v;
                if (json::Parser::parse(req.body, v) && v.is_arr()) {
                    std::lock_guard<std::mutex> lk(apps_mu);
                    apps_cache = std::move(v);
                }
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "GET" && req.path == "/api/apps") {
                {
                    std::lock_guard<std::mutex> lk(apps_mu);
                    if (!apps_cache.arr_val.empty()) {
                        json::Value out = json::Value::make_obj();
                        out["apps"] = apps_cache;
                        return HttpResponse::json(out.dump(-1));
                    }
                }
                // Fallback query via pm list packages -3
                std::string raw = run_cmd_capture("pm list packages -3");
                json::Value arr = json::Value::make_arr();
                size_t p = 0;
                while (p < raw.size()) {
                    size_t nl = raw.find('\n', p);
                    if (nl == std::string::npos) nl = raw.size();
                    std::string line = raw.substr(p, nl - p);
                    if (!line.empty() && line.back() == '\r') line.pop_back();
                    if (line.rfind("package:", 0) == 0) {
                        std::string pkg = line.substr(8);
                        json::Value item = json::Value::make_obj();
                        item["package"] = json::Value::make_str(pkg);
                        item["label"] = json::Value::make_str(pkg);
                        arr.push(std::move(item));
                    }
                    p = nl + 1;
                }
                json::Value out = json::Value::make_obj();
                out["apps"] = std::move(arr);
                return HttpResponse::json(out.dump(-1));
            }
            if (req.method == "POST" && req.path == "/api/apps/launch") {
                json::Value v;
                if (!json::Parser::parse(req.body, v) || !v.get("package") || !v.get("package")->is_str()) {
                    return HttpResponse::json("{\"error\":\"missing package\"}", 400);
                }
                std::string pkg = v.get("package")->as_str();
                if (!is_valid_package_name(pkg)) {
                    return HttpResponse::json("{\"error\":\"invalid package\"}", 400);
                }
                json::Value payload = json::Value::make_obj();
                payload["cmd"] = json::Value::make_str("launch");
                payload["package"] = json::Value::make_str(pkg);
                send_udp_json(8086, payload.dump(-1));
                if (!is_udp_port_bound(8086)) {
                    spawn_detached({
                        "/system/bin/monkey", "-p", pkg,
                        "-c", "android.intent.category.LEANBACK_LAUNCHER",
                        "-c", "android.intent.category.LAUNCHER",
                        "1"
                    });
                }
                return HttpResponse::json("{\"ok\":true}");
            }
            if (req.method == "GET" && req.path == "/api/logs") {
                json::Value arr = json::Value::make_arr();
                for (const auto& l : Logger::instance().recent(150)) {
                    arr.push(json::Value::make_str(l));
                }
                json::Value out = json::Value::make_obj();
                out["logs"] = std::move(arr);
                return HttpResponse::json(out.dump(-1));
            }
            return HttpResponse::json("{\"error\":\"unknown endpoint\"}", 404);
        },
        [&](const std::string& ws_msg) {
            json::Value v;
            if (json::Parser::parse(ws_msg, v) && v.get("cmd")) {
                std::string cmd = v.get("cmd")->as_str();
                if (cmd == "key" && v.get("action")) {
                    uinput.send_android_action(v.get("action")->as_str());
                } else if (cmd == "mute_toggle") {
                    DaemonConfig next_c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        cfg.audio.mic_muted = !cfg.audio.mic_muted;
                        next_c = cfg;
                    }
                    audio.set_mic_muted(next_c.audio.mic_muted);
                    assistant.set_mic_muted(next_c.audio.mic_muted);
                    leds.set_mic_muted(next_c.audio.mic_muted);
                    save_cfg(next_c);
                }
            }
        });

    ota.start(kFirmwareVersion, cfg.ota, [&](const json::Value& st) {
        if (http.ws_client_count() > 0) {
            json::Value msg = json::Value::make_obj();
            msg["type"] = json::Value::make_str("ota");
            msg["data"] = st;
            http.broadcast_ws_text(msg.dump(-1));
        }
    });

    // Non-blocking loopback UDP 8087 receiver for low-overhead visualizer spectrum updates (96-byte float[24] or JSON)
    int spec_udp_fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (spec_udp_fd >= 0) {
        int opt = 1;
        setsockopt(spec_udp_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
        int fl = fcntl(spec_udp_fd, F_GETFL, 0);
        if (fl >= 0) fcntl(spec_udp_fd, F_SETFL, fl | O_NONBLOCK);
        struct sockaddr_in saddr {};
        saddr.sin_family = AF_INET;
        saddr.sin_port = htons(8087);
        saddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(spec_udp_fd, reinterpret_cast<struct sockaddr*>(&saddr), sizeof(saddr)) < 0) {
            LOGW("Main", "UDP 8087 spectrum bind failed: %s", strerror(errno));
            ::close(spec_udp_fd);
            spec_udp_fd = -1;
        }
    }

    // Main telemetry, spectrum & MQTT periodic sync loop (adaptive cadence: 50ms voice / 100ms WS / 250ms idle)
    bool prev_voice_active = false;
    bool initial_udp_synced = false;
    auto last_voice_udp_tp = std::chrono::steady_clock::now() - std::chrono::seconds(1);
    auto last_ws_tp = std::chrono::steady_clock::now();
    auto last_mqtt_tp = std::chrono::steady_clock::now();

    while (!g_stop.load()) {
        if (!initial_udp_synced && is_udp_port_bound(8086)) {
            DaemonConfig c_init;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                c_init = cfg;
            }
            push_eq_to_launcher(c_init);
            push_style_sync_to_launcher(c_init);
            initial_udp_synced = true;
        }

        // Drain any incoming UDP 8087 spectrum & music_active datagrams
        if (spec_udp_fd >= 0) {
            std::array<float, 24> latest_bands{};
            bool has_latest = false;
            char ubuf[512];
            for (int iter = 0; iter < 32; ++iter) {
                ssize_t n = recvfrom(spec_udp_fd, ubuf, sizeof(ubuf) - 1, 0, nullptr, nullptr);
                if (n <= 0) break;
                if (n == static_cast<ssize_t>(24 * sizeof(float))) {
                    for (size_t b = 0; b < 24; ++b) {
                        float fv = 0.0f;
                        std::memcpy(&fv, ubuf + b * sizeof(float), sizeof(float));
                        latest_bands[b] = std::isfinite(fv) ? std::clamp(fv, 0.0f, 1.0f) : 0.0f;
                    }
                    has_latest = true;
                } else if (ubuf[0] == '{') {
                    ubuf[n] = '\0';
                    json::Value v;
                    if (json::Parser::parse(std::string(ubuf, static_cast<size_t>(n)), v)) {
                        const json::Value* cmd_v = v.get("cmd");
                        if (cmd_v && cmd_v->is_str() && cmd_v->as_str() == "music_active") {
                            bool pl = v.get("playing") ? v.get("playing")->as_bool(false) : false;
                            ext_music_playing.store(pl);
                            update_led_music_playing();
                        }
                        const json::Value* arr = v.get("bands");
                        if (arr && arr->is_arr()) {
                            latest_bands.fill(0.0f);
                            for (size_t i = 0; i < std::min<size_t>(24, arr->arr_val.size()); ++i) {
                                double dv = arr->arr_val[i].as_double(0.0);
                                latest_bands[i] = std::isfinite(dv) ? std::clamp(static_cast<float>(dv), 0.0f, 1.0f) : 0.0f;
                            }
                            has_latest = true;
                        }
                    }
                }
            }
            if (has_latest) {
                audio.push_external_spectrum(latest_bands);
            }
        }

        auto now = std::chrono::steady_clock::now();
        bool v_act = assistant.is_active() || audio.is_playback_or_tail_active(250);
        if ((v_act && (now - last_voice_udp_tp) >= std::chrono::milliseconds(45)) ||
            (!v_act && prev_voice_active)) {
            float tts = audio.playback_level();
            float mic = std::clamp(assistant.last_mic_rms() / 4200.0f, 0.0f, 1.0f);
            json::Value vmsg = json::Value::make_obj();
            vmsg["cmd"] = json::Value::make_str("voice_level");
            vmsg["state"] = json::Value::make_str(AssistantManager::state_str(assistant.state()));
            vmsg["ttsRms"] = json::Value::make_num(v_act ? tts : 0.0f);
            vmsg["micRms"] = json::Value::make_num(v_act ? mic : 0.0f);
            send_udp_json(8086, vmsg.dump(-1));
            last_voice_udp_tp = now;
            prev_voice_active = v_act;
        }

        size_t ws_cnt = http.ws_client_count();
        if (ws_cnt > 0 && (now - last_ws_tp) >= std::chrono::milliseconds(200)) {
            last_ws_tp = now;
            json::Value msg = json::Value::make_obj();
            msg["type"] = json::Value::make_str("telemetry");
            msg["data"] = build_status_json();
            http.broadcast_ws_text(msg.dump(-1));
        }

        if ((now - last_mqtt_tp) >= std::chrono::seconds(5)) {
            last_mqtt_tp = now;
            reconcile_volume_if_drifted();
            if (!dns_late_probed &&
                std::chrono::duration_cast<std::chrono::seconds>(now - start_tp).count() >= 12) {
                bool empty_dns = false;
                {
                    std::lock_guard<std::mutex> lk(dns_mu);
                    empty_dns = private_dns_mode_cache.empty() || private_dns_host_cache.empty();
                }
                if (empty_dns) refresh_private_dns_cache();
                dns_late_probed = true;
            }
            if (mqtt.is_connected()) {
                DaemonConfig c;
                {
                    std::lock_guard<std::mutex> lk(cfg_mu);
                    c = cfg;
                }
                json::Value hw_st = leds.get_status_json();
                double mc_temp = 0.0;
                if (const auto* t = hw_st.get("socTempC")) mc_temp = t->as_double(0.0);
                mqtt.publish_state(
                    c.led_ring.mode,
                    c.led_ring.mode != "off",
                    leds.effective_brightness(),
                    c.led_ring.color,
                    c.audio.mic_muted,
                    c.audio.woofer_volume,
                    c.audio.output_route,
                    leds.current_lux(),
                    mc_temp,
                    inputs.headphone_plugged());
            }
        }

        int sleep_ms = v_act ? 50 : (ws_cnt > 0 ? 100 : 250);
        if (spec_udp_fd >= 0) {
            struct pollfd pfd {};
            pfd.fd = spec_udp_fd;
            pfd.events = POLLIN;
            poll(&pfd, 1, sleep_ms);
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(sleep_ms));
        }
    }

    LOGI("Main", "Shutting down...");
    if (spec_udp_fd >= 0) ::close(spec_udp_fd);
    Logger::instance().set_sink(nullptr);
    ota.stop();
    if (geo_thread.joinable()) geo_thread.join();
    timers.stop();
    http.stop();
    mqtt.stop();
    adb_mgr.stop();
    mdns.stop();
    assistant.stop();
    wyoming.stop();
    audio.stop();
    inputs.stop();
    leds.stop();
    uinput.close();
    mixer.close();
    return 0;
}
