#include "assistant/assistant_manager.hpp"
#include "assistant/timer_manager.hpp"
#include "assistant/tls_ws_client.hpp"
#include "config.hpp"
#include "hw/alsa_mixer.hpp"
#include "hw/audio_engine.hpp"
#include "hw/framebuffer_hw.hpp"
#include "hw/input_monitor.hpp"
#include "hw/uinput_keys.hpp"
#include "net/http_client.hpp"
#include "net/mdns_responder.hpp"
#include "net/mqtt_client.hpp"
#include "net/web_tools.hpp"
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
#include <fcntl.h>
#include <mutex>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

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
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return false;
    struct sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    ssize_t n = sendto(fd, payload.data(), payload.size(), 0,
                       reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
    ::close(fd);
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
            int nullfd = open("/dev/null", O_RDWR);
            if (nullfd >= 0) {
                dup2(nullfd, STDIN_FILENO);
                dup2(nullfd, STDOUT_FILENO);
                dup2(nullfd, STDERR_FILENO);
                if (nullfd > 2) ::close(nullfd);
            }
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
    return p == "balanced" || p == "flat" || p == "bass_boost" || p == "vocal" || p == "night";
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

} // namespace

int main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
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

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    using namespace openstation;
    Logger::instance().init("/data/local/openstation/openstationd.log");
    LOGI("Main", "=== OpenStation Max Daemon starting ===");

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

    // System Android STREAM_MUSIC volume percentage (synced with StationAudioService; fallback estimate)
    std::atomic<int> volume_osd_pct{50};
    std::atomic<int> android_vol_max{15};
    auto start_tp = std::chrono::steady_clock::now();

    AlsaMixer mixer;
    FramebufferHw leds;
    UinputKeys uinput;
    InputMonitor inputs;
    AudioEngine audio;
    AssistantManager assistant;
    TimerManager timers;
    WyomingServer wyoming;
    MdnsResponder mdns;
    MqttClient mqtt;
    HttpWsServer http;

    for (int attempt = 0; attempt < 5 && !g_stop.load(); ++attempt) {
        if (mixer.init(0)) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
    }
    uinput.init();
    leds.start(cfg.led_ring, cfg.led_screen);

    auto apply_hw_config = [&](const DaemonConfig& c, bool hp_plugged) {
        leds.update_config(c.led_ring, c.led_screen);
        leds.set_mic_muted(c.audio.mic_muted);

        mixer.set_int("Woofer Master Playback Volume", c.audio.woofer_volume, -1);
        mixer.set_int("Tweeters Master Playback Volume", c.audio.tweeters_volume, -1);
        mixer.set_int("Headphone DAC Playback Volume", c.audio.headphone_volume, -1);
        mixer.set_bool("Woofer EQ Switch", c.audio.woofer_eq);
        mixer.set_bool("Woofer DRC Switch", c.audio.woofer_drc);
        mixer.set_bool("Tweeters EQ Switch", c.audio.tweeters_eq);
        mixer.set_bool("Tweeters DRC Switch", c.audio.tweeters_drc);
        mixer.set_int("PDM HCIC shift gain (~ 6dB * value)", c.audio.pdm_gain, 0);
        mixer.set_enum_idx("PDM Filter Mode", static_cast<unsigned int>(c.audio.pdm_filter));
        mixer.set_enum("Loopback Enable", c.audio.loopback_enable ? "Enable" : "Disable");
        mixer.apply_output_route(c.audio.output_route, hp_plugged);

        audio.update_config(c.audio);
        audio.set_mic_muted(c.audio.mic_muted);
        assistant.update_config(c.assistant);
        assistant.set_mic_muted(c.audio.mic_muted);
        wyoming.update_config(c.wyoming);
        mdns.update_hostname(c.device_hostname);
        mqtt.update_config(c.mqtt);

        // Notify Launcher Android AudioEffect EQ service
        json::Value lcmd = json::Value::make_obj();
        lcmd["cmd"] = json::Value::make_str("eq");
        lcmd["preset"] = json::Value::make_str(c.audio.eq_preset);
        send_udp_json(8086, lcmd.dump(-1));
    };

    auto set_android_volume_pct = [&](int pct, bool show_osd) {
        int clamped = std::clamp(pct, 0, 100);
        int prev = volume_osd_pct.exchange(clamped);
        if (show_osd) {
            leds.trigger_volume_osd(clamped);
        }
        if (is_udp_port_bound(8086)) {
            json::Value vcmd = json::Value::make_obj();
            vcmd["cmd"] = json::Value::make_str("volume_set");
            vcmd["percent"] = json::Value::make_int(clamped);
            vcmd["showUi"] = json::Value::make_bool(false);
            send_udp_json(8086, vcmd.dump(-1));
        } else {
            int max_s = std::max(1, android_vol_max.load());
            int cur_step = (prev * max_s + 50) / 100;
            int tgt_step = (clamped * max_s + 50) / 100;
            int diff = tgt_step - cur_step;
            for (int s = 0; s < std::abs(diff); ++s) {
                uinput.send_key(diff > 0 ? 115 : 114);
            }
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
                int v = volume_osd_pct.load() + (up ? delta_pct : -delta_pct);
                v = std::clamp(v, 0, 100);
                volume_osd_pct.store(v);
                leds.trigger_volume_osd(v);
                if (is_udp_port_bound(8086)) {
                    json::Value vcmd = json::Value::make_obj();
                    vcmd["cmd"] = json::Value::make_str("volume_step");
                    vcmd["dir"] = json::Value::make_int(up ? 1 : -1);
                    vcmd["steps"] = json::Value::make_int(steps);
                    vcmd["showUi"] = json::Value::make_bool(false);
                    send_udp_json(8086, vcmd.dump(-1));
                } else {
                    for (int s = 0; s < steps; ++s) {
                        uinput.send_key(up ? 115 : 114); // KEY_VOLUMEUP / KEY_VOLUMEDOWN
                    }
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
                bool plugged = (ev == HwEventType::JackPlugged);
                if (c.audio.output_route == "auto") {
                    mixer.apply_output_route("auto", plugged);
                }
                break;
            }
        }
    });

    // Apply loaded hardware configuration right away
    apply_hw_config(cfg, inputs.headphone_plugged());

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
            std::string city = args.get("city") ? args.get("city")->as_str("Москва") : "Москва";
            int days = args.get("days") ? args.get("days")->as_int(3) : 3;
            return tool_get_weather(city, days, &g_stop);
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
            res["outputRoute"] = Value::make_str(c.audio.output_route);
            res["headphonePlugged"] = Value::make_bool(inputs.headphone_plugged());
            res["micMuted"] = Value::make_bool(c.audio.mic_muted);
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
                    int next_p = std::clamp(volume_osd_pct.load() + (up ? dpct : -dpct), 0, 100);
                    set_android_volume_pct(next_p, true);
                    res["ok"] = Value::make_bool(true);
                    res["volumePercent"] = Value::make_int(next_p);
                    return res;
                }
            }
            if (const auto* pct_v = args.get("percent"); pct_v && pct_v->is_num()) {
                int pct = std::clamp(pct_v->as_int(50), 0, 100);
                std::string tgt = args.get("target") ? args.get("target")->as_str("all") : "all";
                if (tgt == "woofer" || tgt == "tweeters" || tgt == "headphones") {
                    int amp = std::clamp((pct * 255 + 50) / 100, 0, 255);
                    DaemonConfig c;
                    {
                        std::lock_guard<std::mutex> lk(cfg_mu);
                        if (tgt == "woofer") cfg.audio.woofer_volume = amp;
                        if (tgt == "tweeters") cfg.audio.tweeters_volume = amp;
                        if (tgt == "headphones") cfg.audio.headphone_volume = amp;
                        cfg.sanitize_and_validate();
                        c = cfg;
                    }
                    mixer.set_int("Woofer Master Playback Volume", c.audio.woofer_volume, -1);
                    mixer.set_int("Tweeters Master Playback Volume", c.audio.tweeters_volume, -1);
                    mixer.set_int("Headphone DAC Playback Volume", c.audio.headphone_volume, -1);
                    leds.trigger_volume_osd(pct);
                    save_cfg(c);
                    res["ok"] = Value::make_bool(true);
                    res["target"] = Value::make_str(tgt);
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
                if (const auto* p = args.get("preset"); p && p->is_str()) {
                    if (!is_valid_eq_preset(p->as_str())) {
                        res["ok"] = Value::make_bool(false);
                        res["error"] = Value::make_str("invalid preset (balanced/flat/bass_boost/vocal/night)");
                        return res;
                    }
                    cfg.audio.eq_preset = p->as_str();
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
            json::Value lcmd = json::Value::make_obj();
            lcmd["cmd"] = Value::make_str("eq");
            lcmd["preset"] = Value::make_str(c.audio.eq_preset);
            send_udp_json(8086, lcmd.dump(-1));
            save_cfg(c);
            res["ok"] = Value::make_bool(true);
            res["preset"] = Value::make_str(c.audio.eq_preset);
            res["wooferEq"] = Value::make_bool(c.audio.woofer_eq);
            res["tweetersEq"] = Value::make_bool(c.audio.tweeters_eq);
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
                c = cfg;
            }
            mixer.apply_output_route(c.audio.output_route, inputs.headphone_plugged());
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
                cfg.audio.woofer_volume = std::clamp(atoi(payload.c_str()), 0, 255);
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
        Value root = Value::make_obj();
        root["hostname"] = Value::make_str(c.device_hostname);
        root["ip"] = Value::make_str(MdnsResponder::detect_local_ipv4());
        root["uptimeSec"] = Value::make_int(up);
        root["device"] = Value::make_str("Yandex Station Max (yandexstation_2 / S905X2)");
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
                        c = cfg;
                    }
                    mixer.apply_output_route(c.audio.output_route, inputs.headphone_plugged());
                    save_cfg(c);
                    return HttpResponse::json("{\"ok\":true}");
                }
                return HttpResponse::json("{\"error\":\"missing route\"}", 400);
            }
            if (req.method == "POST" && req.path == "/api/audio/reload_eq") {
                bool ok = mixer.reload_amp_defaults();
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
            if (req.method == "POST" && req.path == "/api/audio/volume") {
                json::Value v;
                if (!json::Parser::parse(req.body, v)) {
                    return HttpResponse::json("{\"error\":\"invalid json\"}", 400);
                }
                int target = volume_osd_pct.load();
                if (const auto* p = v.get("percent"); p && p->is_num()) {
                    target = std::clamp(p->as_int(target), 0, 100);
                } else if (const auto* d = v.get("delta"); d && d->is_num()) {
                    target = std::clamp(target + d->as_int(0), 0, 100);
                }
                int applied = set_android_volume_pct(target, true);
                json::Value out = json::Value::make_obj();
                out["ok"] = json::Value::make_bool(true);
                out["volumePercent"] = json::Value::make_int(applied);
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
                int pct = volume_osd_pct.load();
                if (const auto* p = v.get("percent"); p && p->is_num()) {
                    pct = std::clamp(p->as_int(pct), 0, 100);
                    volume_osd_pct.store(pct);
                }
                bool show_osd = v.get("showOsd") ? v.get("showOsd")->as_bool(true) : true;
                if (show_osd) {
                    leds.trigger_volume_osd(pct);
                } else {
                    leds.update_volume_osd_if_active(pct);
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

    // Main telemetry & MQTT periodic sync loop (50 ms tick for live voice RMS + 100 ms WS telemetry)
    int tick = 0;
    int ws_div = 0;
    bool prev_voice_active = false;
    while (!g_stop.load()) {
        bool v_act = assistant.is_active() || audio.is_playback_or_tail_active(250);
        if (v_act || prev_voice_active) {
            float tts = audio.playback_level();
            float mic = std::clamp(assistant.last_mic_rms() / 4200.0f, 0.0f, 1.0f);
            json::Value vmsg = json::Value::make_obj();
            vmsg["cmd"] = json::Value::make_str("voice_level");
            vmsg["state"] = json::Value::make_str(AssistantManager::state_str(assistant.state()));
            vmsg["ttsRms"] = json::Value::make_num(v_act ? tts : 0.0f);
            vmsg["micRms"] = json::Value::make_num(v_act ? mic : 0.0f);
            send_udp_json(8086, vmsg.dump(-1));
        }
        prev_voice_active = v_act;

        if (++ws_div >= 2) {
            ws_div = 0;
            if (http.ws_client_count() > 0) {
                json::Value msg = json::Value::make_obj();
                msg["type"] = json::Value::make_str("telemetry");
                msg["data"] = build_status_json();
                http.broadcast_ws_text(msg.dump(-1));
            }
        }

        if (++tick >= 100) { // every ~5 seconds
            tick = 0;
            DaemonConfig c;
            {
                std::lock_guard<std::mutex> lk(cfg_mu);
                c = cfg;
            }
            if (mqtt.is_connected()) {
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
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    LOGI("Main", "Shutting down...");
    Logger::instance().set_sink(nullptr);
    timers.stop();
    http.stop();
    mqtt.stop();
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
