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
constexpr const char* kBootAnimSystemPath = "/system/media/bootanimation.zip";
constexpr const char* kBootAnimStockBackupPath = "/data/local/openstation/backup/bootanimation.zip.stock";
constexpr const char* kBootAnimRomStockPath = "/system/openstation/bootanimation.zip.stock";
constexpr const char* kBootAnimOpenStationShaPath = "/data/local/openstation/bootanim_openstation.sha256";
constexpr const char* kBootAnimCustomShaPath = "/data/local/openstation/bootanim_custom.sha256";
constexpr const char* kBootAnimUploadTmpPath = "/data/local/openstation/bootanim_upload.zip";
constexpr size_t kMaxArtifactBytes = 32ULL * 1024ULL * 1024ULL;
constexpr size_t kMaxBootAnimBytes = 24ULL * 1024ULL * 1024ULL;
constexpr uint64_t kMinFreeDataBytes = 40ULL * 1024ULL * 1024ULL;

uint16_t read_le16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t read_le32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

bool file_exists_nonempty(const char* path) {
    struct stat st {};
    return stat(path, &st) == 0 && st.st_size > 0;
}

bool validate_bootanimation_zip_bytes(const void* raw_data, size_t len, std::string& out_err) {
    out_err.clear();
    if (!raw_data || len < 128) {
        out_err = "Файл слишком мал и не является корректным bootanimation.zip";
        return false;
    }
    if (len > kMaxBootAnimBytes) {
        out_err = "Файл слишком большой (максимум 24 МБ)";
        return false;
    }
    const auto* buf = static_cast<const uint8_t*>(raw_data);
    if (buf[0] != 'P' || buf[1] != 'K' || buf[2] != 0x03 || buf[3] != 0x04) {
        out_err = "Файл не является корректным ZIP-архивом";
        return false;
    }

    // Find End-of-Central-Directory record
    size_t search_start = (len > 65558) ? (len - 65558) : 0;
    size_t eocd_off = std::string::npos;
    for (size_t i = len - 22; i >= search_start; --i) {
        if (buf[i] == 0x50 && buf[i + 1] == 0x4B && buf[i + 2] == 0x05 && buf[i + 3] == 0x06) {
            uint16_t cmt_len = read_le16(buf + i + 20);
            if (i + 22 + cmt_len == len) {
                eocd_off = i;
                break;
            }
        }
        if (i == 0) break;
    }
    if (eocd_off == std::string::npos) {
        out_err = "Повреждённый ZIP (не найден заголовок EOCD)";
        return false;
    }

    uint16_t total_entries = read_le16(buf + eocd_off + 10);
    uint32_t cd_size = read_le32(buf + eocd_off + 12);
    uint32_t cd_off = read_le32(buf + eocd_off + 16);
    if (total_entries == 0 || static_cast<uint64_t>(cd_off) + cd_size > eocd_off) {
        out_err = "Повреждённая таблица файлов в ZIP";
        return false;
    }

    bool found_desc = false;
    bool desc_valid = false;
    int frame_count = 0;
    size_t pos = cd_off;
    size_t cd_end = cd_off + cd_size;

    for (uint16_t idx = 0; idx < total_entries; ++idx) {
        if (pos + 46 > cd_end) {
            out_err = "Неожиданный конец заголовков ZIP";
            return false;
        }
        if (read_le32(buf + pos) != 0x02014b50U) {
            out_err = "Неверный формат заголовка файла в ZIP";
            return false;
        }
        uint16_t method = read_le16(buf + pos + 10);
        uint32_t uncomp_size = read_le32(buf + pos + 24);
        uint16_t name_len = read_le16(buf + pos + 28);
        uint16_t extra_len = read_le16(buf + pos + 30);
        uint16_t cmt_len = read_le16(buf + pos + 32);
        uint32_t local_off = read_le32(buf + pos + 42);
        if (pos + 46 + name_len + extra_len + cmt_len > cd_end) {
            out_err = "Выход имени файла за границы ZIP";
            return false;
        }
        std::string name(reinterpret_cast<const char*>(buf + pos + 46), name_len);
        pos += 46 + name_len + extra_len + cmt_len;

        if (name.find("..") != std::string::npos || (!name.empty() && name[0] == '/')) {
            out_err = "Небезопасный путь внутри ZIP: " + name;
            return false;
        }
        bool is_dir = !name.empty() && name.back() == '/';
        if (!is_dir && method != 0) {
            out_err = "ZIP-архив должен быть без сжатия (метод Store), а не Deflate — Android bootanimation требует ZIP без сжатия";
            return false;
        }
        if (is_dir) continue;

        if (name == "desc.txt") {
            found_desc = true;
            if (uncomp_size < 5 || uncomp_size > 16384) {
                out_err = "Некорректный размер desc.txt в архиве";
                return false;
            }
            if (static_cast<uint64_t>(local_off) + 30 > eocd_off ||
                read_le32(buf + local_off) != 0x04034b50U) {
                out_err = "Повреждённый локальный заголовок desc.txt";
                return false;
            }
            uint16_t lmethod = read_le16(buf + local_off + 8);
            uint16_t lfname = read_le16(buf + local_off + 26);
            uint16_t lextra = read_le16(buf + local_off + 28);
            size_t data_off = static_cast<size_t>(local_off) + 30 + lfname + lextra;
            if (lmethod != 0 || data_off + uncomp_size > eocd_off) {
                out_err = "Некорректный формат desc.txt в архиве";
                return false;
            }
            std::string desc(reinterpret_cast<const char*>(buf + data_off), uncomp_size);
            int w = 0, h = 0, fps = 0;
            if (sscanf(desc.c_str(), "%d %d %d", &w, &h, &fps) == 3 &&
                w >= 64 && w <= 3840 && h >= 64 && h <= 2160 && fps >= 1 && fps <= 120) {
                if (desc.find("\np ") != std::string::npos || desc.find("\nc ") != std::string::npos ||
                    desc.find("\np\t") != std::string::npos) {
                    desc_valid = true;
                }
            }
            if (!desc_valid) {
                out_err = "Некорректный формат desc.txt (ожидается ширина, высота, FPS и части part0/part1)";
                return false;
            }
        } else {
            std::string low = name;
            for (char& ch : low) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
            if ((low.size() > 4 && low.substr(low.size() - 4) == ".png") ||
                (low.size() > 4 && low.substr(low.size() - 4) == ".jpg") ||
                (low.size() > 5 && low.substr(low.size() - 5) == ".jpeg")) {
                frame_count++;
            }
        }
    }

    if (!found_desc) {
        out_err = "В архиве отсутствует файл desc.txt (должен лежать в корне ZIP)";
        return false;
    }
    if (frame_count <= 0) {
        out_err = "В архиве не найдены PNG/JPG кадры заставки";
        return false;
    }
    return true;
}

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

    std::string escape_sh_single(const std::string& s) {
    std::string out = "'";
    for (char c : s) {
        if (c == '\'') out += "'\\''";
        else out.push_back(c);
    }
    out.push_back('\'');
    return out;
}

bool ensure_stock_bootanim_backup(const std::string& known_os_sha, const std::string& known_custom_sha) {
    if (file_exists_nonempty(kBootAnimStockBackupPath)) return true;
    int rc = 0;
    std::string out;
    run_shell_capture("mkdir -p /data/local/openstation/backup", 5, rc, out);

    if (file_exists_nonempty(kBootAnimRomStockPath)) {
        std::string cmd = std::string("cp -f ") + kBootAnimRomStockPath + " " + kBootAnimStockBackupPath +
                          ".tmp && chmod 644 " + kBootAnimStockBackupPath + ".tmp && mv -f " +
                          kBootAnimStockBackupPath + ".tmp " + kBootAnimStockBackupPath + " && sync";
        return run_shell_capture(cmd, 15, rc, out) && rc == 0 && file_exists_nonempty(kBootAnimStockBackupPath);
    }

    if (!file_exists_nonempty(kBootAnimSystemPath)) return false;
    std::string cur_sha = OtaManager::compute_file_sha256(kBootAnimSystemPath);
    if (cur_sha.empty()) return false;
    if ((!known_os_sha.empty() && cur_sha == known_os_sha) ||
        (!known_custom_sha.empty() && cur_sha == known_custom_sha)) {
        return false;
    }
    std::string cmd = std::string("cp -f ") + kBootAnimSystemPath + " " + kBootAnimStockBackupPath +
                      ".tmp && chmod 644 " + kBootAnimStockBackupPath + ".tmp && mv -f " +
                      kBootAnimStockBackupPath + ".tmp " + kBootAnimStockBackupPath + " && sync";
    if (!run_shell_capture(cmd, 20, rc, out) || rc != 0) return false;
    return OtaManager::compute_file_sha256(kBootAnimStockBackupPath) == cur_sha;
}

bool install_bootanim_file_atomic(const std::string& src_path,
                                  const std::string& expected_sha,
                                  std::string& out_err) {
    out_err.clear();
    struct stat src_st {};
    if (stat(src_path.c_str(), &src_st) != 0 || src_st.st_size <= 0) {
        out_err = "Файл заставки не найден: " + src_path;
        return false;
    }

    struct stat cur_st {};
    uint64_t existing_sz = 0;
    if (stat(kBootAnimSystemPath, &cur_st) == 0 && cur_st.st_size > 0) {
        existing_sz = static_cast<uint64_t>(cur_st.st_size);
    }

    struct statvfs svfs {};
    if (statvfs("/system", &svfs) == 0) {
        uint64_t avail = static_cast<uint64_t>(svfs.f_bavail) * static_cast<uint64_t>(svfs.f_frsize);
        uint64_t need = static_cast<uint64_t>(src_st.st_size) + 2ULL * 1024ULL * 1024ULL;
        if (avail + existing_sz < need) {
            out_err = "Недостаточно места в системном разделе /system/media";
            return false;
        }
    }

    std::string sh_src = escape_sh_single(src_path);
    std::string cmd =
        "set -e\n"
        "mount -o rw,remount / 2>/dev/null || mount -o remount,rw /\n"
        "cleanup() {\n"
        "  rm -f /system/media/bootanimation.zip.tmp\n"
        "  sync\n"
        "  mount -o ro,remount / 2>/dev/null || mount -o remount,ro / || true\n"
        "}\n"
        "trap cleanup EXIT\n"
        "mkdir -p /system/media\n"
        "cp -f " + sh_src + " /system/media/bootanimation.zip.tmp\n"
        "chown 0:0 /system/media/bootanimation.zip.tmp 2>/dev/null || true\n"
        "chmod 644 /system/media/bootanimation.zip.tmp\n"
        "chcon u:object_r:system_file:s0 /system/media/bootanimation.zip.tmp 2>/dev/null || true\n"
        "mv -f /system/media/bootanimation.zip.tmp /system/media/bootanimation.zip\n"
        "chown 0:0 /system/media/bootanimation.zip 2>/dev/null || true\n"
        "chmod 644 /system/media/bootanimation.zip\n"
        "chcon u:object_r:system_file:s0 /system/media/bootanimation.zip 2>/dev/null || true\n"
        "restorecon /system/media/bootanimation.zip 2>/dev/null || true\n"
        "sync\n";

    int rc = -1;
    std::string out;
    if (!run_shell_capture(cmd, 30, rc, out) || rc != 0) {
        out_err = "Не удалось записать /system/media/bootanimation.zip: " + out;
        return false;
    }

    std::string actual_sha = OtaManager::compute_file_sha256(kBootAnimSystemPath);
    if (actual_sha != expected_sha) {
        out_err = "Контрольная сумма /system/media/bootanimation.zip после записи не совпала";
        return false;
    }
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
    local_bootanim_sha_ = compute_file_sha256(kBootAnimSystemPath, &local_bootanim_size_);
    if (local_bootanim_sha_.empty()) local_bootanim_size_ = 0;

    std::string local_manifest_bootanim_sha;
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
                if (const auto* ba = files->get("bootanimation"); ba && ba->is_obj()) {
                    if (const auto* sh = ba->get("sha256"); sh && sh->is_str()) {
                        std::string h = to_lower_hex(sh->as_str());
                        if (is_valid_sha256_hex(h)) local_manifest_bootanim_sha = h;
                    }
                }
            }
        }
    }

    std::string stock_sha;
    if (file_exists_nonempty(kBootAnimStockBackupPath)) {
        bootanim_has_stock_backup_ = true;
        stock_sha = compute_file_sha256(kBootAnimStockBackupPath);
    } else if (file_exists_nonempty(kBootAnimRomStockPath)) {
        bootanim_has_stock_backup_ = true;
        stock_sha = compute_file_sha256(kBootAnimRomStockPath);
    } else {
        bootanim_has_stock_backup_ = false;
    }

    std::string recorded_os_sha;
    if (read_file_bytes(kBootAnimOpenStationShaPath, recorded_os_sha, 128)) {
        recorded_os_sha = to_lower_hex(recorded_os_sha);
        if (!is_valid_sha256_hex(recorded_os_sha)) recorded_os_sha.clear();
    } else {
        recorded_os_sha.clear();
    }

    std::string recorded_custom_sha;
    if (read_file_bytes(kBootAnimCustomShaPath, recorded_custom_sha, 128)) {
        recorded_custom_sha = to_lower_hex(recorded_custom_sha);
        if (!is_valid_sha256_hex(recorded_custom_sha)) recorded_custom_sha.clear();
    } else {
        recorded_custom_sha.clear();
    }

    const std::string& os_ref = !remote_bootanim_.sha256.empty()
                                    ? remote_bootanim_.sha256
                                    : (!recorded_os_sha.empty() ? recorded_os_sha : local_manifest_bootanim_sha);

    if (local_bootanim_sha_.empty()) {
        bootanim_mode_ = "missing";
    } else if (!os_ref.empty() && local_bootanim_sha_ == os_ref) {
        bootanim_mode_ = "openstation";
    } else if (!recorded_os_sha.empty() && local_bootanim_sha_ == recorded_os_sha) {
        bootanim_mode_ = "openstation";
    } else if (!stock_sha.empty() && local_bootanim_sha_ == stock_sha) {
        bootanim_mode_ = "stock";
    } else if (!bootanim_has_stock_backup_ && recorded_custom_sha.empty()) {
        // Untouched station before first OpenStation bootanim flash has stock animation at /system/media/bootanimation.zip
        bootanim_mode_ = "stock";
    } else {
        bootanim_mode_ = "custom";
    }
}

void OtaManager::start(const std::string& current_version,
                       const OtaConfig& cfg,
                       StatusBroadcastCb broadcast_cb) {
    stop();
    {
        std::lock_guard<std::mutex> lk(mu_);
        installed_version_ = current_version.empty() ? "1.2.3" : current_version;
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
    Value ba = pack_art(remote_bootanim_, local_bootanim_sha_);
    ba["enabled"] = Value::make_bool(cfg_.sync_bootanimation);
    ba["changed"] = Value::make_bool(cfg_.sync_bootanimation && !remote_bootanim_.sha256.empty() &&
                                    local_bootanim_sha_ != remote_bootanim_.sha256);
    comps["bootanimation"] = std::move(ba);
    o["components"] = std::move(comps);
    return o;
}

json::Value OtaManager::get_bootanim_status_json() const {
    using json::Value;
    std::lock_guard<std::mutex> lk(mu_);
    Value b = Value::make_obj();
    b["mode"] = Value::make_str(bootanim_mode_);
    b["size"] = Value::make_int(local_bootanim_size_);
    b["sha8"] = Value::make_str(local_bootanim_sha_.size() >= 8 ? local_bootanim_sha_.substr(0, 8) : "");
    b["hasStockBackup"] = Value::make_bool(bootanim_has_stock_backup_);
    b["syncWithOta"] = Value::make_bool(cfg_.sync_bootanimation);
    b["remoteAvailable"] = Value::make_bool(!remote_bootanim_.sha256.empty());
    b["remoteSize"] = Value::make_int(remote_bootanim_.size);
    b["remoteSha8"] = Value::make_str(remote_bootanim_.sha256.size() >= 8 ? remote_bootanim_.sha256.substr(0, 8) : "");
    return b;
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

    OtaArtifactInfo rd, rl, ri, rb;
    if (!parse_art("daemon", "openstationd", rd) ||
        !parse_art("launcher", "OpenStationLauncher.apk", rl) ||
        !parse_art("ime", "OpenStationIME.apk", ri)) {
        return false;
    }

    bool has_remote_bootanim = false;
    if (const json::Value* ba_obj = files->get("bootanimation"); ba_obj && ba_obj->is_obj()) {
        OtaArtifactInfo cand;
        cand.key = "bootanimation";
        cand.file_name = ba_obj->get("fileName") ? ba_obj->get("fileName")->as_str("") : "bootanimation.zip";
        cand.size = ba_obj->get("size") ? ba_obj->get("size")->as_i64(0) : 0;
        cand.sha256 = ba_obj->get("sha256") ? to_lower_hex(ba_obj->get("sha256")->as_str("")) : "";
        cand.url = ba_obj->get("url") ? ba_obj->get("url")->as_str("") : "";
        if (is_safe_filename(cand.file_name) &&
            cand.size > 0 &&
            cand.size <= static_cast<int64_t>(kMaxBootAnimBytes) &&
            is_valid_sha256_hex(cand.sha256)) {
            rb = std::move(cand);
            has_remote_bootanim = true;
        }
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
        if (has_remote_bootanim && cfg_.sync_bootanimation && local_bootanim_sha_ != rb.sha256) {
            changed.push_back("bootanimation");
        }

        // Only signal updateAvailable if GitHub version >= installed version (no spurious downgrades)
        bool avail = false;
        if (cmp > 0) {
            avail = true;
            if (changed.empty()) {
                changed = {"daemon", "launcher", "ime"};
                if (has_remote_bootanim && cfg_.sync_bootanimation) {
                    changed.push_back("bootanimation");
                }
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
        remote_bootanim_ = has_remote_bootanim ? std::move(rb) : OtaArtifactInfo{};
        remote_manifest_raw_ = resp.body;
        changed_components_ = std::move(changed);
        update_available_ = avail;
        last_check_epoch_ = static_cast<int64_t>(std::time(nullptr));
        refresh_local_hashes_locked();
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
    OtaArtifactInfo rd, rl, ri, rb;
    std::string target_ver, target_bt, raw_manifest;
    std::vector<std::string> to_update;
    bool avail = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        c_snap = cfg_;
        rd = remote_daemon_;
        rl = remote_launcher_;
        ri = remote_ime_;
        rb = remote_bootanim_;
        target_ver = latest_version_;
        target_bt = latest_build_time_;
        raw_manifest = remote_manifest_raw_;
        avail = update_available_;
        if (force) {
            to_update = {"ime", "launcher", "daemon"};
            if (c_snap.sync_bootanimation && !rb.sha256.empty()) {
                to_update.push_back("bootanimation");
            }
        } else {
            to_update = changed_components_;
        }
    }

    if (!force && (!avail || to_update.empty())) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            state_ = "up_to_date";
            progress_pct_ = 100;
            progress_text_ = "Обновление не требуется — уже v" + installed_version_;
        }
        emit_status(); // must not hold mu_: emit_status() locks it
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
    bool need_bootanim = has_comp("bootanimation") && !rb.sha256.empty();

    int dummy_rc = 0;
    std::string dummy_out;
    run_shell_capture("rm -rf /data/local/openstation/ota_tmp && mkdir -p /data/local/openstation/ota_tmp",
                      5, dummy_rc, dummy_out);

    std::vector<OtaArtifactInfo> downloads;
    if (need_ime) downloads.push_back(ri);
    if (need_bootanim) downloads.push_back(rb);
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
            if (art.key == "bootanimation") {
                std::string zerr;
                if (!validate_bootanimation_zip_bytes(r.body.data(), r.body.size(), zerr)) {
                    dl_err = zerr;
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

    // 1b. Install OpenStation bootanimation.zip synchronously if changed
    if (need_bootanim) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            progress_pct_ = 88;
            progress_text_ = "Установка фирменной заставки загрузки...";
        }
        emit_status();

        std::lock_guard<std::mutex> blk(bootanim_mu_);
        ensure_stock_bootanim_backup(rb.sha256, "");
        std::string bsrc = std::string(kOtaTmpDir) + "/" + rb.file_name;
        std::string berr;
        if (!install_bootanim_file_atomic(bsrc, rb.sha256, berr)) {
            run_shell_capture("rm -rf /data/local/openstation/ota_tmp", 5, dummy_rc, dummy_out);
            fail_ota("Ошибка установки заставки загрузки: " + berr);
            return;
        }
        write_file_atomic(kBootAnimOpenStationShaPath, rb.sha256.data(), rb.sha256.size(), 0644);
        unlink(kBootAnimCustomShaPath);
        LOGI("OTA", "Installed bootanimation.zip (%s)", rb.sha256.substr(0, 8).c_str());
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
    // target_ver comes from the remote manifest; quote it so $(...) / backticks are not executed.
    script += "echo " + escape_sh_single("=== OpenStation OTA Apply v" + target_ver + " ===") + "\n";
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

BootAnimOpResult OtaManager::apply_uploaded_bootanimation(const void* data, size_t len) {
    std::string val_err;
    if (!validate_bootanimation_zip_bytes(data, len, val_err)) {
        return {false, val_err};
    }
    std::string sha = compute_bytes_sha256(data, len);
    if (sha.empty()) {
        return {false, "Не удалось вычислить SHA-256 архива"};
    }

    std::lock_guard<std::mutex> blk(bootanim_mu_);
    std::string os_sha;
    {
        std::lock_guard<std::mutex> lk(mu_);
        os_sha = remote_bootanim_.sha256;
    }
    if (os_sha.empty()) {
        std::string rec;
        if (read_file_bytes(kBootAnimOpenStationShaPath, rec, 128)) {
            rec = to_lower_hex(rec);
            if (is_valid_sha256_hex(rec)) os_sha = rec;
        }
    }
    ensure_stock_bootanim_backup(os_sha, "");

    if (!write_file_atomic(kBootAnimUploadTmpPath, data, len, 0644)) {
        return {false, "Не удалось сохранить загруженный архив во временный файл"};
    }

    std::string inst_err;
    bool ok = install_bootanim_file_atomic(kBootAnimUploadTmpPath, sha, inst_err);
    unlink(kBootAnimUploadTmpPath);
    if (!ok) {
        return {false, inst_err};
    }

    if (!os_sha.empty() && sha == os_sha) {
        unlink(kBootAnimCustomShaPath);
    } else {
        write_file_atomic(kBootAnimCustomShaPath, sha.data(), sha.size(), 0644);
    }

    {
        std::lock_guard<std::mutex> lk(mu_);
        cfg_.sync_bootanimation = false;
        refresh_local_hashes_locked();
    }
    emit_status();
    return {true, "Своя заставка установлена! Будет показана при следующей перезагрузке."};
}

BootAnimOpResult OtaManager::restore_stock_bootanimation() {
    std::lock_guard<std::mutex> blk(bootanim_mu_);
    const char* src = nullptr;
    if (file_exists_nonempty(kBootAnimStockBackupPath)) src = kBootAnimStockBackupPath;
    else if (file_exists_nonempty(kBootAnimRomStockPath)) src = kBootAnimRomStockPath;

    if (!src) {
        std::string mode_snap;
        {
            std::lock_guard<std::mutex> lk(mu_);
            refresh_local_hashes_locked();
            mode_snap = bootanim_mode_;
        }
        if (mode_snap == "stock") {
            return {true, "Сейчас уже установлена оригинальная заставка Яндекса"};
        }
        return {false, "Резервная копия стоковой заставки не найдена на устройстве"};
    }

    std::string stock_sha = compute_file_sha256(src);
    if (stock_sha.empty()) {
        return {false, "Не удалось прочитать резервную копию стоковой заставки"};
    }

    std::string inst_err;
    if (!install_bootanim_file_atomic(src, stock_sha, inst_err)) {
        return {false, inst_err};
    }
    unlink(kBootAnimCustomShaPath);

    {
        std::lock_guard<std::mutex> lk(mu_);
        cfg_.sync_bootanimation = false;
        refresh_local_hashes_locked();
    }
    emit_status();
    return {true, "Оригинальная заставка Яндекса восстановлена (автообновление заставки отключено)."};
}

BootAnimOpResult OtaManager::install_openstation_bootanimation_sync() {
    if (op_active_.exchange(true)) {
        return {false, "Подождите завершения текущей операции OTA"};
    }
    struct OpGuard {
        std::atomic<bool>& flag;
        bool armed = true;
        explicit OpGuard(std::atomic<bool>& f) : flag(f) {}
        void release() {
            if (armed) {
                flag.store(false);
                armed = false;
            }
        }
        ~OpGuard() { release(); }
    } op_guard(op_active_);

    std::string mf_err;
    if (!fetch_and_parse_manifest(mf_err)) {
        return {false, mf_err};
    }

    OtaConfig c_snap;
    OtaArtifactInfo rb;
    std::string ver;
    {
        std::lock_guard<std::mutex> lk(mu_);
        c_snap = cfg_;
        c_snap.sync_bootanimation = true;
        cfg_.sync_bootanimation = true;
        rb = remote_bootanim_;
        ver = latest_version_.empty() ? installed_version_ : latest_version_;
    }

    if (rb.sha256.empty()) {
        return {false, "В манифесте на GitHub ещё нет фирменной заставки OpenStation"};
    }

    bool already_installed = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        refresh_local_hashes_locked();
        already_installed = (local_bootanim_sha_ == rb.sha256);
    }
    if (already_installed) {
        write_file_atomic(kBootAnimOpenStationShaPath, rb.sha256.data(), rb.sha256.size(), 0644);
        unlink(kBootAnimCustomShaPath);
        {
            std::lock_guard<std::mutex> lk(mu_);
            refresh_local_hashes_locked();
            changed_components_.erase(
                std::remove(changed_components_.begin(), changed_components_.end(), "bootanimation"),
                changed_components_.end());
            if (compare_semver(latest_version_, installed_version_) == 0 && changed_components_.empty()) {
                update_available_ = false;
                state_ = "up_to_date";
                progress_pct_ = 100;
                progress_text_ = "Установлена актуальная версия v" + installed_version_;
            }
        }
        op_guard.release();
        emit_status();
        return {true, "Фирменная заставка OpenStation Max уже установлена"};
    }

    auto urls = build_artifact_urls(rb, c_snap.repo, c_snap.branch, ver);
    std::string dl_err = "Нет ответа от GitHub";
    std::string ok_body;
    for (const auto& u : urls) {
        if (stop_req_.load()) return {false, "Операция остановлена"};
        HttpClientRequest req;
        req.method = "GET";
        req.url = u;
        req.timeout_ms = 120000;
        req.max_redirects = 5;
        req.max_body_bytes = kMaxBootAnimBytes;
        req.verify_tls = true;
        req.abort_req = &stop_req_;
        HttpClientResponse r = http_perform(req);
        if (!r.ok || r.status_code != 200) {
            dl_err = !r.error.empty() ? r.error : ("HTTP " + std::to_string(r.status_code));
            continue;
        }
        if (r.truncated || static_cast<int64_t>(r.body.size()) != rb.size) {
            dl_err = "Несовпадение размера bootanimation.zip";
            continue;
        }
        if (compute_bytes_sha256(r.body.data(), r.body.size()) != rb.sha256) {
            dl_err = "Несовпадение SHA-256 bootanimation.zip";
            continue;
        }
        if (!validate_bootanimation_zip_bytes(r.body.data(), r.body.size(), dl_err)) {
            continue;
        }
        ok_body = std::move(r.body);
        break;
    }

    if (ok_body.empty()) {
        return {false, "Не удалось скачать заставку: " + dl_err};
    }

    std::lock_guard<std::mutex> blk(bootanim_mu_);
    ensure_stock_bootanim_backup(rb.sha256, "");
    if (!write_file_atomic(kBootAnimUploadTmpPath, ok_body.data(), ok_body.size(), 0644)) {
        return {false, "Ошибка записи временного файла заставки"};
    }
    std::string inst_err;
    bool inst_ok = install_bootanim_file_atomic(kBootAnimUploadTmpPath, rb.sha256, inst_err);
    unlink(kBootAnimUploadTmpPath);
    if (!inst_ok) {
        return {false, inst_err};
    }

    write_file_atomic(kBootAnimOpenStationShaPath, rb.sha256.data(), rb.sha256.size(), 0644);
    unlink(kBootAnimCustomShaPath);

    {
        std::lock_guard<std::mutex> lk(mu_);
        refresh_local_hashes_locked();
        changed_components_.erase(
            std::remove(changed_components_.begin(), changed_components_.end(), "bootanimation"),
            changed_components_.end());
        if (compare_semver(latest_version_, installed_version_) == 0 && changed_components_.empty()) {
            update_available_ = false;
            state_ = "up_to_date";
            progress_pct_ = 100;
            progress_text_ = "Установлена актуальная версия v" + installed_version_;
        }
    }
    op_guard.release();
    emit_status();
    return {true, "Фирменная заставка OpenStation Max установлена!"};
}

} // namespace openstation
