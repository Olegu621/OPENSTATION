#include "ota/ota_manager.hpp"
#include "net/http_client.hpp"
#include "utils/logger.hpp"

#include <mbedtls/sha256.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

namespace openstation {

namespace {

constexpr const char* kDaemonBinPath = "/data/local/openstation/openstationd";
constexpr const char* kLocalManifestPath = "/data/local/openstation/firmware_manifest.json";
constexpr const char* kOtaTmpDir = "/data/local/openstation/ota_tmp";
constexpr const char* kOtaApplyScriptPath = "/data/local/openstation/ota_apply.sh";
constexpr size_t kMaxArtifactBytes = 32ULL * 1024ULL * 1024ULL;
constexpr uint64_t kMinFreeDataBytes = 40ULL * 1024ULL * 1024ULL;

std::string to_lower_hex(const std::string& raw) {
    std::string out;
    out.reserve(raw.size());
    for (unsigned char c : raw) {
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
        out.push_back(static_cast<char>(std::tolower(c)));
    }
    return out;
}

bool is_valid_sha256_hex(const std::string& s) {
    if (s.size() != 64) return false;
    for (unsigned char c : s) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
}

bool is_safe_filename(const std::string& name) {
    if (name.empty() || name.size() > 96) return false;
    if (name == "." || name == ".." || name.find("..") != std::string::npos) return false;
    for (unsigned char c : name) {
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) {
            return false;
        }
    }
    return true;
}

bool is_trusted_github_url(const std::string& u) {
    return u.rfind("https://raw.githubusercontent.com/", 0) == 0 ||
           u.rfind("https://github.com/", 0) == 0;
}

std::string bytes_to_hex(const unsigned char* digest, size_t n) {
    static const char* kHex = "0123456789abcdef";
    std::string out;
    out.resize(n * 2);
    for (size_t i = 0; i < n; ++i) {
        out[i * 2] = kHex[(digest[i] >> 4) & 0xF];
        out[i * 2 + 1] = kHex[digest[i] & 0xF];
    }
    return out;
}

bool read_file_bytes(const std::string& path, std::string& out, size_t max_bytes = 256 * 1024) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    out.clear();
    char buf[4096];
    while (out.size() < max_bytes) {
        size_t want = std::min(sizeof(buf), max_bytes - out.size());
        size_t n = fread(buf, 1, want, f);
        if (n == 0) break;
        out.append(buf, n);
    }
    fclose(f);
    return !out.empty();
}

bool write_file_atomic(const std::string& path, const void* data, size_t len, mode_t mode) {
    std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return false;
    const uint8_t* ptr = static_cast<const uint8_t*>(data);
    size_t off = 0;
    while (off < len) {
        size_t n = fwrite(ptr + off, 1, len - off, f);
        if (n == 0) {
            fclose(f);
            unlink(tmp.c_str());
            return false;
        }
        off += n;
    }
    fflush(f);
    fsync(fileno(f));
    fclose(f);
    chmod(tmp.c_str(), mode);
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        unlink(tmp.c_str());
        return false;
    }
    chmod(path.c_str(), mode);
    return true;
}

bool run_shell_capture(const std::string& cmd, int timeout_sec, int& exit_code, std::string& output) {
    exit_code = -1;
    output.clear();
    int pfd[2];
    if (pipe2(pfd, O_CLOEXEC) != 0) return false;
    pid_t pid = fork();
    if (pid < 0) {
        close(pfd[0]);
        close(pfd[1]);
        return false;
    }
    if (pid == 0) {
        setpgid(0, 0);
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            if (devnull > 2) close(devnull);
        }
        dup2(pfd[1], STDOUT_FILENO);
        dup2(pfd[1], STDERR_FILENO);
        close(pfd[0]);
        close(pfd[1]);
        for (int fd = 3; fd < 1024; ++fd) close(fd);
        execl("/system/bin/sh", "sh", "-c", cmd.c_str(), static_cast<char*>(nullptr));
        _exit(127);
    }
    close(pfd[1]);
    int fl = fcntl(pfd[0], F_GETFL, 0);
    if (fl >= 0) fcntl(pfd[0], F_SETFL, fl | O_NONBLOCK);

    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(std::max(1, timeout_sec));
    bool timed_out = false;
    char buf[2048];
    while (true) {
        int rem = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                       deadline - std::chrono::steady_clock::now())
                                       .count());
        if (rem <= 0) {
            timed_out = true;
            break;
        }
        struct pollfd p {};
        p.fd = pfd[0];
        p.events = POLLIN;
        int pr = poll(&p, 1, std::min(rem, 500));
        if (pr > 0) {
            ssize_t n = read(pfd[0], buf, sizeof(buf));
            if (n > 0) {
                if (output.size() < 8192) {
                    output.append(buf, std::min<size_t>(static_cast<size_t>(n), 8192 - output.size()));
                }
            } else if (n == 0) {
                break;
            }
        } else if (pr < 0 && errno != EINTR) {
            break;
        } else {
            int st = 0;
            pid_t w = waitpid(pid, &st, WNOHANG);
            if (w == pid) {
                // Drain remaining bytes
                while (true) {
                    ssize_t n = read(pfd[0], buf, sizeof(buf));
                    if (n <= 0) break;
                    if (output.size() < 8192) {
                        output.append(buf, std::min<size_t>(static_cast<size_t>(n), 8192 - output.size()));
                    }
                }
                if (WIFEXITED(st)) exit_code = WEXITSTATUS(st);
                else if (WIFSIGNALED(st)) exit_code = 128 + WTERMSIG(st);
                close(pfd[0]);
                return true;
            }
        }
    }
    close(pfd[0]);
    if (timed_out) {
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
    }
    int st = 0;
    while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
    if (!timed_out) {
        if (WIFEXITED(st)) exit_code = WEXITSTATUS(st);
        else if (WIFSIGNALED(st)) exit_code = 128 + WTERMSIG(st);
    }
    return !timed_out;
}

bool spawn_detached_script(const std::string& script_path) {
    pid_t p1 = fork();
    if (p1 < 0) return false;
    if (p1 == 0) {
        pid_t p2 = fork();
        if (p2 == 0) {
            setsid();
            int nfd = open("/dev/null", O_RDWR);
            if (nfd >= 0) {
                dup2(nfd, STDIN_FILENO);
                dup2(nfd, STDOUT_FILENO);
                dup2(nfd, STDERR_FILENO);
                if (nfd > 2) close(nfd);
            }
            for (int fd = 3; fd < 1024; ++fd) close(fd);
            execl("/system/bin/sh", "sh", script_path.c_str(), static_cast<char*>(nullptr));
            _exit(127);
        }
        _exit(0);
    }
    int st = 0;
    while (waitpid(p1, &st, 0) < 0 && errno == EINTR) {}
    return true;
}

} // namespace

OtaManager::~OtaManager() {
    stop();
}

std::string OtaManager::compute_bytes_sha256(const void* data, size_t len) {
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    unsigned char digest[32] = {};
    if (mbedtls_sha256_starts(&ctx, 0) == 0 &&
        mbedtls_sha256_update(&ctx, static_cast<const unsigned char*>(data), len) == 0 &&
        mbedtls_sha256_finish(&ctx, digest) == 0) {
        mbedtls_sha256_free(&ctx);
        return bytes_to_hex(digest, 32);
    }
    mbedtls_sha256_free(&ctx);
    return "";
}

std::string OtaManager::compute_file_sha256(const std::string& path, int64_t* out_size) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return "";
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    if (mbedtls_sha256_starts(&ctx, 0) != 0) {
        mbedtls_sha256_free(&ctx);
        fclose(f);
        return "";
    }
    unsigned char buf[16384];
    int64_t total = 0;
    while (true) {
        size_t n = fread(buf, 1, sizeof(buf), f);
        if (n > 0) {
            total += static_cast<int64_t>(n);
            mbedtls_sha256_update(&ctx, buf, n);
        }
        if (n < sizeof(buf)) break;
    }
    fclose(f);
    unsigned char digest[32] = {};
    if (mbedtls_sha256_finish(&ctx, digest) != 0) {
        mbedtls_sha256_free(&ctx);
        return "";
    }
    mbedtls_sha256_free(&ctx);
    if (out_size) *out_size = total;
    return bytes_to_hex(digest, 32);
}

int OtaManager::compare_semver(const std::string& a, const std::string& b) {
    auto parse_ver = [](const std::string& s) {
        std::vector<int> parts;
        size_t i = 0;
        while (i < s.size() && (s[i] == 'v' || s[i] == 'V')) ++i;
        while (i < s.size()) {
            if (std::isdigit(static_cast<unsigned char>(s[i]))) {
                int v = 0;
                while (i < s.size() && std::isdigit(static_cast<unsigned char>(s[i]))) {
                    v = v * 10 + (s[i] - '0');
                    ++i;
                }
                parts.push_back(v);
            } else if (s[i] == '.') {
                ++i;
            } else {
                break;
            }
        }
        while (parts.size() < 3) parts.push_back(0);
        return parts;
    };
    auto pa = parse_ver(a);
    auto pb = parse_ver(b);
    size_t n = std::max(pa.size(), pb.size());
    for (size_t i = 0; i < n; ++i) {
        int va = i < pa.size() ? pa[i] : 0;
        int vb = i < pb.size() ? pb[i] : 0;
        if (va < vb) return -1;
        if (va > vb) return 1;
    }
    return 0;
}

void OtaManager::refresh_local_hashes_locked() {
    local_daemon_sha_ = compute_file_sha256(kDaemonBinPath);

    std::string raw;
    if (read_file_bytes(kLocalManifestPath, raw)) {
        json::Value mv;
        if (json::Parser::parse(raw, mv) && mv.is_obj()) {
            if (const auto* bt = mv.get("buildTimeUtc"); bt && bt->is_str()) {
                installed_build_time_ = bt->as_str();
            }
            if (const auto* files = mv.get("files"); files && files->is_obj()) {
                if (const auto* ln = files->get("launcher"); ln && ln->is_obj()) {
                    if (const auto* sh = ln->get("sha256"); sh && sh->is_str()) {
                        std::string h = to_lower_hex(sh->as_str());
                        if (is_valid_sha256_hex(h)) local_launcher_sha_ = h;
                    }
                }
                if (const auto* im = files->get("ime"); im && im->is_obj()) {
                    if (const auto* sh = im->get("sha256"); sh && sh->is_str()) {
                        std::string h = to_lower_hex(sh->as_str());
                        if (is_valid_sha256_hex(h)) local_ime_sha_ = h;
                    }
                }
            }
        }
    }
}

void OtaManager::start(const std::string& current_version,
                       const OtaConfig& cfg,
                       StatusBroadcastCb broadcast_cb) {
    stop();
    {
        std::lock_guard<std::mutex> lk(mu_);
        installed_version_ = current_version.empty() ? "1.2.0" : current_version;
        cfg_ = cfg;
        broadcast_cb_ = std::move(broadcast_cb);
        stop_req_.store(false);
        state_ = "idle";
        progress_pct_ = 0;
        progress_text_.clear();
        error_.clear();
        refresh_local_hashes_locked();
    }

    std::lock_guard<std::mutex> tlk(thread_mu_);
    auto_thread_ = std::thread([this]() {
        // Delay initial GitHub check by 25s so Wi-Fi & DNS stabilize cleanly after boot
        for (int i = 0; i < 125 && !stop_req_.load(); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
        }
        if (stop_req_.load()) return;

        bool do_auto = false;
        {
            std::lock_guard<std::mutex> lk(mu_);
            do_auto = cfg_.auto_check;
        }
        if (!do_auto) return;
        if (op_active_.exchange(true)) return;
        run_check_sync();
        op_active_.store(false);
    });
}

void OtaManager::stop() {
    stop_req_.store(true);
    std::thread a;
    std::thread o;
    {
        std::lock_guard<std::mutex> tlk(thread_mu_);
        if (auto_thread_.joinable()) a = std::move(auto_thread_);
        if (op_thread_.joinable()) o = std::move(op_thread_);
    }
    if (a.joinable()) a.join();
    if (o.joinable()) o.join();
}

void OtaManager::update_config(const OtaConfig& cfg) {
    std::lock_guard<std::mutex> lk(mu_);
    cfg_ = cfg;
}

bool OtaManager::is_busy() const {
    return op_active_.load();
}

std::vector<std::string> OtaManager::build_manifest_urls(const std::string& repo, const std::string& branch) const {
    int64_t ts = static_cast<int64_t>(std::time(nullptr));
    std::string q = "?t=" + std::to_string(ts);
    return {
        "https://raw.githubusercontent.com/" + repo + "/" + branch + "/firmware/manifest.json" + q,
        "https://github.com/" + repo + "/raw/" + branch + "/firmware/manifest.json" + q
    };
}

std::vector<std::string> OtaManager::build_artifact_urls(const OtaArtifactInfo& art,
                                                         const std::string& repo,
                                                         const std::string& branch,
                                                         const std::string& ver) const {
    std::vector<std::string> urls;
    std::string h8 = art.sha256.size() >= 8 ? art.sha256.substr(0, 8) : "0";
    std::string q = "?v=" + ver + "&h=" + h8;
    if (!art.url.empty() && is_trusted_github_url(art.url)) {
        urls.push_back(art.url);
    }
    urls.push_back("https://raw.githubusercontent.com/" + repo + "/" + branch + "/firmware/" + art.file_name + q);
    urls.push_back("https://github.com/" + repo + "/raw/" + branch + "/firmware/" + art.file_name + q);
    return urls;
}

void OtaManager::emit_status() {
    StatusBroadcastCb cb;
    {
        std::lock_guard<std::mutex> lk(mu_);
        cb = broadcast_cb_;
    }
    if (cb) {
        cb(get_status_json());
    }
}

json::Value OtaManager::get_status_json() const {
    using json::Value;
    std::lock_guard<std::mutex> lk(mu_);
    Value o = Value::make_obj();
    o["state"] = Value::make_str(state_);
    o["busy"] = Value::make_bool(op_active_.load());
    o["autoCheck"] = Value::make_bool(cfg_.auto_check);
    o["repo"] = Value::make_str(cfg_.repo);
    o["branch"] = Value::make_str(cfg_.branch);
    o["installedVersion"] = Value::make_str(installed_version_);
    o["installedBuildTime"] = Value::make_str(installed_build_time_);
    o["latestVersion"] = Value::make_str(latest_version_);
    o["latestBuildTime"] = Value::make_str(latest_build_time_);
    o["changelog"] = Value::make_str(changelog_);
    o["manifestUrl"] = Value::make_str(manifest_url_);
    o["updateAvailable"] = Value::make_bool(update_available_);
    o["progressPct"] = Value::make_int(progress_pct_);
    o["progressText"] = Value::make_str(progress_text_);
    o["error"] = Value::make_str(error_);
    o["lastCheckEpoch"] = Value::make_int(last_check_epoch_);
    o["localDaemonSha8"] = Value::make_str(local_daemon_sha_.size() >= 8 ? local_daemon_sha_.substr(0, 8) : "");

    Value arr = Value::make_arr();
    for (const auto& c : changed_components_) arr.push(Value::make_str(c));
    o["changedComponents"] = std::move(arr);

    Value comps = Value::make_obj();
    auto pack_art = [](const OtaArtifactInfo& a, const std::string& local_sha) {
        Value x = Value::make_obj();
        x["fileName"] = Value::make_str(a.file_name);
        x["size"] = Value::make_int(a.size);
        x["remoteSha8"] = Value::make_str(a.sha256.size() >= 8 ? a.sha256.substr(0, 8) : "");
        x["localSha8"] = Value::make_str(local_sha.size() >= 8 ? local_sha.substr(0, 8) : "");
        x["changed"] = Value::make_bool(!a.sha256.empty() && local_sha != a.sha256);
        return x;
    };
    comps["daemon"] = pack_art(remote_daemon_, local_daemon_sha_);
    comps["launcher"] = pack_art(remote_launcher_, local_launcher_sha_);
    comps["ime"] = pack_art(remote_ime_, local_ime_sha_);
    o["components"] = std::move(comps);
    return o;
}

bool OtaManager::fetch_and_parse_manifest(std::string& out_err) {
    out_err.clear();
    OtaConfig c_snap;
    std::string inst_ver;
    {
        std::lock_guard<std::mutex> lk(mu_);
        c_snap = cfg_;
        inst_ver = installed_version_;
        refresh_local_hashes_locked();
    }

    auto urls = build_manifest_urls(c_snap.repo, c_snap.branch);
    HttpClientResponse resp;
    std::string used_url;
    std::string last_err = "GitHub manifest request failed";

    for (const auto& u : urls) {
        if (stop_req_.load()) {
            out_err = "Операция остановлена";
            return false;
        }
        HttpClientRequest req;
        req.method = "GET";
        req.url = u;
        req.timeout_ms = 12000;
        req.max_redirects = 5;
        req.max_body_bytes = 256 * 1024;
        req.verify_tls = true;
        req.abort_req = &stop_req_;
        resp = http_perform(req);
        if (resp.ok && resp.status_code == 200 && !resp.body.empty()) {
            used_url = resp.final_url.empty() ? u : resp.final_url;
            break;
        }
        if (!resp.error.empty()) last_err = resp.error;
        else if (resp.status_code > 0) last_err = "HTTP " + std::to_string(resp.status_code);
    }

    if (used_url.empty()) {
        out_err = "Не удалось получить manifest.json с GitHub: " + last_err;
        return false;
    }

    json::Value mv;
    if (!json::Parser::parse(resp.body, mv) || !mv.is_obj()) {
        out_err = "Повреждённый JSON манифест на GitHub";
        return false;
    }

    std::string dev = mv.get("device") ? mv.get("device")->as_str("") : "";
    if (dev != "yandexstation_2") {
        out_err = "Манифест для другого устройства (" + dev + ")";
        return false;
    }

    std::string remote_ver = mv.get("version") ? mv.get("version")->as_str("") : "";
    if (remote_ver.empty()) {
        out_err = "В манифесте отсутствует версия прошивки";
        return false;
    }

    const json::Value* files = mv.get("files");
    if (!files || !files->is_obj()) {
        out_err = "В манифесте отсутствует раздел files";
        return false;
    }

    auto parse_art = [&](const char* key, const char* def_fn, OtaArtifactInfo& out_art) -> bool {
        const json::Value* obj = files->get(key);
        if (!obj || !obj->is_obj()) {
            out_err = std::string("В манифесте нет компонента ") + key;
            return false;
        }
        out_art.key = key;
        out_art.file_name = obj->get("fileName") ? obj->get("fileName")->as_str("") : def_fn;
        out_art.size = obj->get("size") ? obj->get("size")->as_i64(0) : 0;
        out_art.sha256 = obj->get("sha256") ? to_lower_hex(obj->get("sha256")->as_str("")) : "";
        out_art.url = obj->get("url") ? obj->get("url")->as_str("") : "";
        if (!is_safe_filename(out_art.file_name) ||
            out_art.size <= 0 ||
            out_art.size > static_cast<int64_t>(kMaxArtifactBytes) ||
            !is_valid_sha256_hex(out_art.sha256)) {
            out_err = std::string("Некорректные метаданные или SHA-256 для ") + key;
            return false;
        }
        return true;
    };

    OtaArtifactInfo rd, rl, ri;
    if (!parse_art("daemon", "openstationd", rd) ||
        !parse_art("launcher", "OpenStationLauncher.apk", rl) ||
        !parse_art("ime", "OpenStationIME.apk", ri)) {
        return false;
    }

    std::string remote_bt = mv.get("buildTimeUtc") ? mv.get("buildTimeUtc")->as_str("") : "";
    std::string remote_cl = mv.get("changelog") ? mv.get("changelog")->as_str("") : "";

    std::vector<std::string> changed;
    int cmp = compare_semver(remote_ver, inst_ver);

    {
        std::lock_guard<std::mutex> lk(mu_);
        if (local_daemon_sha_ != rd.sha256) changed.push_back("daemon");
        if (local_launcher_sha_.empty() || local_launcher_sha_ != rl.sha256) {
            if (!local_launcher_sha_.empty() || cmp > 0) changed.push_back("launcher");
        }
        if (local_ime_sha_.empty() || local_ime_sha_ != ri.sha256) {
            if (!local_ime_sha_.empty() || cmp > 0) changed.push_back("ime");
        }

        // Only signal updateAvailable if GitHub version >= installed version (no spurious downgrades)
        bool avail = false;
        if (cmp > 0) {
            avail = true;
            if (changed.empty()) {
                changed = {"daemon", "launcher", "ime"};
            }
        } else if (cmp == 0 && !changed.empty()) {
            avail = true;
        }

        latest_version_ = remote_ver;
        latest_build_time_ = remote_bt;
        changelog_ = remote_cl;
        manifest_url_ = used_url;
        remote_daemon_ = std::move(rd);
        remote_launcher_ = std::move(rl);
        remote_ime_ = std::move(ri);
        remote_manifest_raw_ = resp.body;
        changed_components_ = std::move(changed);
        update_available_ = avail;
        last_check_epoch_ = static_cast<int64_t>(std::time(nullptr));
    }

    return true;
}

void OtaManager::run_check_sync() {
    {
        std::lock_guard<std::mutex> lk(mu_);
        state_ = "checking";
        progress_pct_ = 10;
        progress_text_ = "Проверка обновлений на GitHub (" + cfg_.repo + "@" + cfg_.branch + ")...";
        error_.clear();
    }
    emit_status();

    std::string err;
    if (!fetch_and_parse_manifest(err)) {
        std::lock_guard<std::mutex> lk(mu_);
        state_ = "error";
        progress_pct_ = 0;
        progress_text_ = err;
        error_ = err;
        last_check_epoch_ = static_cast<int64_t>(std::time(nullptr));
        LOGW("OTA", "Manifest check failed: %s", err.c_str());
    } else {
        std::lock_guard<std::mutex> lk(mu_);
        progress_pct_ = 100;
        if (update_available_) {
            state_ = "available";
            progress_text_ = "Доступна версия v" + latest_version_ + " (" +
                             std::to_string(changed_components_.size()) + " комп.)";
            LOGI("OTA", "Update available: v%s (installed v%s, changed=%zu)",
                 latest_version_.c_str(), installed_version_.c_str(), changed_components_.size());
        } else {
            state_ = "up_to_date";
            progress_text_ = "Установлена актуальная версия v" + installed_version_;
            LOGI("OTA", "Firmware is up to date (v%s)", installed_version_.c_str());
        }
    }
    emit_status();
}

bool OtaManager::check_now() {
    if (op_active_.exchange(true)) return false;

    std::thread prev;
    {
        std::lock_guard<std::mutex> tlk(thread_mu_);
        if (op_thread_.joinable()) prev = std::move(op_thread_);
        op_thread_ = std::thread([this]() {
            run_check_sync();
            op_active_.store(false);
        });
    }
    if (prev.joinable()) prev.join();
    return true;
}

OtaInstallStartResult OtaManager::start_install(bool force) {
    if (op_active_.exchange(true)) {
        return {false, "Обновление или проверка уже выполняются"};
    }

    std::thread prev;
    {
        std::lock_guard<std::mutex> tlk(thread_mu_);
        if (op_thread_.joinable()) prev = std::move(op_thread_);
        op_thread_ = std::thread([this, force]() {
            run_install_sync(force);
            op_active_.store(false);
        });
    }
    if (prev.joinable()) prev.join();
    return {true, force ? "Запуск полной переустановки с GitHub..." : "Запуск OTA-обновления с GitHub..."};
}

void OtaManager::run_install_sync(bool force) {
    auto fail_ota = [&](const std::string& msg) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            state_ = "error";
            progress_pct_ = 0;
            progress_text_ = msg;
            error_ = msg;
        }
        LOGE("OTA", "OTA failed: %s", msg.c_str());
        emit_status();
    };

    {
        std::lock_guard<std::mutex> lk(mu_);
        state_ = "checking";
        progress_pct_ = 5;
        progress_text_ = "Получение manifest.json с GitHub...";
        error_.clear();
    }
    emit_status();

    std::string err;
    if (!fetch_and_parse_manifest(err)) {
        fail_ota(err);
        return;
    }

    OtaConfig c_snap;
    OtaArtifactInfo rd, rl, ri;
    std::string target_ver, target_bt, raw_manifest;
    std::vector<std::string> to_update;
    bool avail = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        c_snap = cfg_;
        rd = remote_daemon_;
        rl = remote_launcher_;
        ri = remote_ime_;
        target_ver = latest_version_;
        target_bt = latest_build_time_;
        raw_manifest = remote_manifest_raw_;
        avail = update_available_;
        if (force) {
            to_update = {"ime", "launcher", "daemon"};
        } else {
            to_update = changed_components_;
        }
    }

    if (!force && (!avail || to_update.empty())) {
        std::lock_guard<std::mutex> lk(mu_);
        state_ = "up_to_date";
        progress_pct_ = 100;
        progress_text_ = "Обновление не требуется — уже v" + installed_version_;
        emit_status();
        return;
    }

    // Check free space on /data before downloading binaries
    struct statvfs vfs {};
    if (statvfs("/data", &vfs) == 0) {
        uint64_t free_bytes = static_cast<uint64_t>(vfs.f_bavail) * static_cast<uint64_t>(vfs.f_frsize);
        if (free_bytes < kMinFreeDataBytes) {
            fail_ota("Недостаточно места на разделе /data (нужно 40 МБ)");
            return;
        }
    }

    auto has_comp = [&](const char* k) {
        return std::find(to_update.begin(), to_update.end(), k) != to_update.end();
    };
    bool need_ime = has_comp("ime");
    bool need_launcher = has_comp("launcher");
    bool need_daemon = has_comp("daemon");

    int dummy_rc = 0;
    std::string dummy_out;
    run_shell_capture("rm -rf /data/local/openstation/ota_tmp && mkdir -p /data/local/openstation/ota_tmp",
                      5, dummy_rc, dummy_out);

    std::vector<OtaArtifactInfo> downloads;
    if (need_ime) downloads.push_back(ri);
    if (need_launcher) downloads.push_back(rl);
    if (need_daemon) downloads.push_back(rd);

    for (size_t idx = 0; idx < downloads.size(); ++idx) {
        if (stop_req_.load()) {
            fail_ota("Прервано");
            return;
        }
        const auto& art = downloads[idx];
        int pct_start = 10 + static_cast<int>((idx * 65) / std::max<size_t>(1, downloads.size()));
        {
            std::lock_guard<std::mutex> lk(mu_);
            state_ = "downloading";
            progress_pct_ = pct_start;
            progress_text_ = "Скачивание " + art.file_name + " (" +
                             std::to_string((art.size + 512) / 1024) + " КБ)...";
        }
        emit_status();

        auto urls = build_artifact_urls(art, c_snap.repo, c_snap.branch, target_ver);
        std::string dl_err = "Нет ответа от GitHub";
        bool dl_ok = false;

        for (const auto& u : urls) {
            if (stop_req_.load()) break;
            HttpClientRequest req;
            req.method = "GET";
            req.url = u;
            req.timeout_ms = 120000;
            req.max_redirects = 5;
            req.max_body_bytes = kMaxArtifactBytes;
            req.verify_tls = true;
            req.abort_req = &stop_req_;
            HttpClientResponse r = http_perform(req);
            if (!r.ok || r.status_code != 200) {
                dl_err = !r.error.empty() ? r.error : ("HTTP " + std::to_string(r.status_code));
                continue;
            }
            if (r.truncated || static_cast<int64_t>(r.body.size()) != art.size) {
                dl_err = "Размер " + art.file_name + " не совпал (" +
                         std::to_string(r.body.size()) + " != " + std::to_string(art.size) + ")";
                continue;
            }
            std::string sha = compute_bytes_sha256(r.body.data(), r.body.size());
            if (sha != art.sha256) {
                dl_err = "SHA-256 не совпал для " + art.file_name;
                continue;
            }
            if (art.file_name.size() >= 4 &&
                art.file_name.substr(art.file_name.size() - 4) == ".apk") {
                if (r.body.size() < 4 || r.body[0] != 'P' || r.body[1] != 'K' ||
                    static_cast<unsigned char>(r.body[2]) != 0x03 ||
                    static_cast<unsigned char>(r.body[3]) != 0x04) {
                    dl_err = "Файл " + art.file_name + " не является корректным APK/ZIP";
                    continue;
                }
            }
            std::string dst_path = std::string(kOtaTmpDir) + "/" + art.file_name;
            mode_t mode = (art.key == "daemon") ? 0755 : 0644;
            if (!write_file_atomic(dst_path, r.body.data(), r.body.size(), mode)) {
                dl_err = "Ошибка записи на диск: " + dst_path;
                continue;
            }
            std::string disk_sha = compute_file_sha256(dst_path);
            if (disk_sha != art.sha256) {
                dl_err = "Повреждение записи на /data для " + art.file_name;
                continue;
            }
            dl_ok = true;
            break;
        }

        if (!dl_ok) {
            run_shell_capture("rm -rf /data/local/openstation/ota_tmp", 5, dummy_rc, dummy_out);
            fail_ota("Ошибка скачивания " + art.file_name + ": " + dl_err);
            return;
        }
    }

    {
        std::lock_guard<std::mutex> lk(mu_);
        state_ = "verifying";
        progress_pct_ = 78;
        progress_text_ = "Проверка целостности SHA-256 и самотест бинарников...";
    }
    emit_status();

    if (need_daemon) {
        int rc = -1;
        std::string out;
        std::string test_cmd =
            "LD_LIBRARY_PATH=/data/local/openstation/lib:/vendor/lib64:/system/lib64 "
            "/data/local/openstation/ota_tmp/openstationd --version";
        if (!run_shell_capture(test_cmd, 5, rc, out) || rc != 0 ||
            out.find("openstationd") == std::string::npos) {
            run_shell_capture("rm -rf /data/local/openstation/ota_tmp", 5, dummy_rc, dummy_out);
            fail_ota("Новый openstationd не прошёл самотест (--version): " + out);
            return;
        }
    }

    std::string tmp_manifest_path = std::string(kOtaTmpDir) + "/manifest.json";
    if (!write_file_atomic(tmp_manifest_path, raw_manifest.data(), raw_manifest.size(), 0644)) {
        run_shell_capture("rm -rf /data/local/openstation/ota_tmp", 5, dummy_rc, dummy_out);
        fail_ota("Не удалось сохранить временный manifest.json");
        return;
    }

    {
        std::lock_guard<std::mutex> lk(mu_);
        state_ = "installing";
        progress_pct_ = 84;
        progress_text_ = "Установка обновлённых компонентов...";
    }
    emit_status();

    // 1. Install IME synchronously if changed
    if (need_ime) {
        int rc = -1;
        std::string out;
        bool ok = run_shell_capture(
            "/system/bin/pm install -r -g -d /data/local/openstation/ota_tmp/OpenStationIME.apk",
            90, rc, out);
        if (!ok || rc != 0 || out.find("Failure") != std::string::npos) {
            run_shell_capture("rm -rf /data/local/openstation/ota_tmp", 5, dummy_rc, dummy_out);
            fail_ota("Ошибка установки OpenStationIME.apk: " + out);
            return;
        }
        run_shell_capture(
            "/system/bin/ime enable com.openstation.ime/.OpenStationImeService; "
            "/system/bin/ime set com.openstation.ime/.OpenStationImeService",
            10, dummy_rc, dummy_out);
    }

    // 2. If daemon did NOT change, install launcher & update manifest without restarting openstationd
    if (!need_daemon) {
        if (need_launcher) {
            {
                std::lock_guard<std::mutex> lk(mu_);
                progress_pct_ = 90;
                progress_text_ = "Установка OpenStationLauncher.apk...";
            }
            emit_status();

            int rc = -1;
            std::string out;
            bool ok = run_shell_capture(
                "/system/bin/pm install -r -g -d /data/local/openstation/ota_tmp/OpenStationLauncher.apk",
                120, rc, out);
            if (!ok || rc != 0 || out.find("Failure") != std::string::npos) {
                run_shell_capture("rm -rf /data/local/openstation/ota_tmp", 5, dummy_rc, dummy_out);
                fail_ota("Ошибка установки OpenStationLauncher.apk: " + out);
                return;
            }
            run_shell_capture(
                "pm grant com.openstation.launcher android.permission.RECORD_AUDIO 2>/dev/null || true; "
                "appops set com.openstation.launcher RECORD_AUDIO allow 2>/dev/null || true; "
                "appops set com.openstation.launcher SYSTEM_ALERT_WINDOW allow 2>/dev/null || true; "
                "cmd package set-home-activity com.openstation.launcher/.MainActivity 2>/dev/null || "
                "pm set-home-activity com.openstation.launcher/.MainActivity 2>/dev/null || true; "
                "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.openstation.launcher/.MainActivity",
                15, dummy_rc, dummy_out);
        }

        if (rename(tmp_manifest_path.c_str(), kLocalManifestPath) != 0) {
            write_file_atomic(kLocalManifestPath, raw_manifest.data(), raw_manifest.size(), 0644);
        }
        chmod(kLocalManifestPath, 0644);
        run_shell_capture("rm -rf /data/local/openstation/ota_tmp && sync", 5, dummy_rc, dummy_out);

        {
            std::lock_guard<std::mutex> lk(mu_);
            installed_version_ = target_ver;
            installed_build_time_ = target_bt;
            refresh_local_hashes_locked();
            changed_components_.clear();
            update_available_ = false;
            state_ = "up_to_date";
            progress_pct_ = 100;
            progress_text_ = "Обновлено до v" + installed_version_ + " (без перезапуска)";
            error_.clear();
        }
        LOGI("OTA", "In-place OTA completed to v%s", target_ver.c_str());
        emit_status();
        return;
    }

    // 3. Daemon changed: hand off atomic swap + restart + optional Launcher install to detached helper
    {
        std::lock_guard<std::mutex> lk(mu_);
        state_ = "restarting";
        progress_pct_ = 94;
        progress_text_ = need_launcher
                             ? "Перезапуск openstationd и обновление ТВ-лаунчера..."
                             : "Перезапуск openstationd с новой версией v" + target_ver + "...";
    }
    emit_status();
    std::this_thread::sleep_for(std::chrono::milliseconds(350));

    std::string script;
    script += "#!/system/bin/sh\n";
    script += "exec >/data/local/openstation/ota.log 2>&1\n";
    script += "echo \"=== OpenStation OTA Apply v" + target_ver + " ===\"\n";
    script += "sleep 1\n";
    script += "killall openstationd 2>/dev/null || true\n";
    script += "i=0\n";
    script += "while [ $i -lt 10 ]; do\n";
    script += "  pidof openstationd >/dev/null 2>&1 || break\n";
    script += "  i=$((i+1))\n";
    script += "  sleep 1\n";
    script += "done\n";
    script += "killall -9 openstationd 2>/dev/null || true\n";
    script += "chmod 755 /data/local/openstation/ota_tmp/openstationd\n";
    script += "mv -f /data/local/openstation/ota_tmp/openstationd /data/local/openstation/openstationd\n";
    script += "mv -f /data/local/openstation/ota_tmp/manifest.json /data/local/openstation/firmware_manifest.json\n";
    script += "chmod 644 /data/local/openstation/firmware_manifest.json\n";
    script += "sync\n";
    script += "export LD_LIBRARY_PATH=/data/local/openstation/lib:/vendor/lib64:/system/lib64\n";
    script += "/data/local/openstation/openstationd --daemon </dev/null >/data/local/openstation/boot.log 2>&1\n";
    if (need_launcher) {
        script += "pm install -r -g -d /data/local/openstation/ota_tmp/OpenStationLauncher.apk\n";
        script += "pm grant com.openstation.launcher android.permission.RECORD_AUDIO 2>/dev/null || true\n";
        script += "appops set com.openstation.launcher RECORD_AUDIO allow 2>/dev/null || true\n";
        script += "appops set com.openstation.launcher SYSTEM_ALERT_WINDOW allow 2>/dev/null || true\n";
        script += "cmd package set-home-activity com.openstation.launcher/.MainActivity 2>/dev/null || pm set-home-activity com.openstation.launcher/.MainActivity 2>/dev/null || true\n";
        script += "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.openstation.launcher/.MainActivity\n";
    }
    script += "rm -rf /data/local/openstation/ota_tmp\n";
    script += "rm -f /data/local/openstation/ota_apply.sh\n";
    script += "echo \"=== OpenStation OTA Completed ===\"\n";

    if (!write_file_atomic(kOtaApplyScriptPath, script.data(), script.size(), 0755)) {
        fail_ota("Не удалось создать ota_apply.sh");
        return;
    }

    if (!spawn_detached_script(kOtaApplyScriptPath)) {
        fail_ota("Не удалось запустить detached OTA скрипт");
        return;
    }
    LOGI("OTA", "Detached OTA script launched for v%s", target_ver.c_str());
}

} // namespace openstation
