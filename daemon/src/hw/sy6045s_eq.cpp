#include "hw/sy6045s_eq.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/i2c-dev.h>
#include <linux/i2c.h>
#include <sys/ioctl.h>
#include <thread>
#include <tuple>
#include <unistd.h>

namespace openstation {
namespace {
using Registers = std::map<std::pair<int, int>, std::string>;
using Changes = std::vector<std::tuple<int, int, std::string>>;
constexpr std::array<int, 2> amps = {0x2a, 0x2b};
constexpr char device[] = "/dev/i2c-2";
constexpr char identity[] = "0000000000000000000000000000000004000000";

#include "hw/sy6045s_presets.inc"

std::string settings(int amp) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/bus/i2c/drivers/sy6045s/2-%04x/settings", amp);
    return path;
}

std::string read_register(int fd, int amp, int reg, size_t size) {
    uint8_t index = reg;
    uint8_t data[20] = {};
    i2c_msg messages[] = {
        {static_cast<uint16_t>(amp), 0, 1, &index},
        {static_cast<uint16_t>(amp), I2C_M_RD, static_cast<uint16_t>(size), data}
    };
    i2c_rdwr_ioctl_data operation{messages, 2};
    if (ioctl(fd, I2C_RDWR, &operation) != 2) return {};
    std::string hex;
    for (size_t i = 0; i < size; ++i) {
        char byte[3];
        std::snprintf(byte, sizeof(byte), "%02x", data[i]);
        hex += byte;
    }
    return hex;
}

bool write_register(int fd, int amp, int reg, const std::string& data) {
    FILE* out = std::fopen(settings(amp).c_str(), "w");
    if (!out) return false;
    std::fprintf(out, "w %02X %02X", amp * 2, reg);
    for (size_t i = 0; i < data.size(); i += 2) std::fprintf(out, " %.2s", data.c_str() + i);
    std::fputc('\n', out);
    return std::fclose(out) == 0 && read_register(fd, amp, reg, data.size() / 2) == data;
}

struct Bus {
    int fd = open(device, O_RDWR | O_CLOEXEC);
    ~Bus() { if (fd >= 0) close(fd); }
};

Registers snapshot(int fd) {
    Registers result;
    for (int amp : amps) {
        for (int reg : {3, 6, 0x22}) result[{amp, reg}] = read_register(fd, amp, reg, 1);
        for (int reg = 0x38; reg <= 0x40; ++reg) result[{amp, reg}] = read_register(fd, amp, reg, 20);
    }
    for (const auto& entry : result) if (entry.second.empty()) return {};
    return result;
}

bool write_coefficients(int fd, const Registers& registers, bool recovering) {
    bool ok = true;
    auto write = [&](int amp, int reg, const std::string& value) {
        const bool verified = write_register(fd, amp, reg, value);
        ok = verified && ok;
        return verified || recovering;
    };
    for (int amp : amps) {
        if (!write(amp, 6, "08") || !write(amp, 0x22, "30")) return false;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(15));
    for (int amp : amps) if (!write(amp, 3, "5f")) return false;
    for (const auto& [key, value] : registers) {
        if (key.second >= 0x38 && !write(key.first, key.second, value)) return false;
    }
    for (int amp : amps) {
        for (int reg : {3, 0x22, 6}) {
            if (!write(amp, reg, registers.at({amp, reg}))) return false;
        }
    }
    return ok && snapshot(fd) == registers;
}

std::string coefficients(double b0, double b1, double b2, double a0, double a1, double a2) {
    std::string out;
    for (double value : {-a2, -a1, b2, b1, b0}) {
        char word[9];
        auto fixed = static_cast<uint32_t>(std::llround(value / a0 * (1 << 26)));
        std::snprintf(word, sizeof(word), "%08x", fixed);
        out += word;
    }
    return out;
}

constexpr double pi = 3.14159265358979323846;
std::string high_pass(double frequency, double q) {
    const double w = 2 * pi * frequency / 48000;
    const double a = std::sin(w) / (2 * q), c = std::cos(w);
    return coefficients((1+c)/2, -(1+c), (1+c)/2, 1+a, -2*c, 1-a);
}

std::string shelf(double frequency, double q, double gain, bool high) {
    const double w = 2 * pi * frequency / 48000;
    const double a = std::pow(10., gain / 40), c = std::cos(w);
    const double b = std::sqrt(a) * std::sin(w) / q;
    const double p = a + 1, m = a - 1;
    if (high) return coefficients(a*(p+m*c+b), -2*a*(m+p*c), a*(p+m*c-b),
                                  p-m*c+b, 2*(m-p*c), p-m*c-b);
    return coefficients(a*(p-m*c+b), 2*a*(m-p*c), a*(p-m*c-b),
                        p+m*c+b, -2*(m+p*c), p+m*c-b);
}

bool plan(const json::Value& request, Changes& changes, json::Value& profile) {
    using json::Value;
    const auto* mode = request.get("profile");
    if (!mode || !mode->is_str()) return false;
    const auto name = mode->as_str();
    const auto* params = request.get("params");
    const auto* filters = request.get("filters");
    const bool custom = name == "custom";
    if (request.obj_val.size() != 1u + (params != nullptr) + (filters != nullptr) ||
        (!custom && (params || filters))) return false;
    profile["profile"] = *mode;
    if (name == "original") return true;
    if (name != "hifi" && name != "night" && name != "bass" && name != "club" && !custom) return false;

    double bass_db = 0, treble_db = 3, sub = 48;
    if (name == "night") { treble_db = -2.5; sub = 65; }
    if (name == "bass") { bass_db = 3.5; treble_db = 2; sub = 45; }
    if (name == "club") { bass_db = 6.5; treble_db = 2.5; sub = 40; }
    auto number = [&](const char* key, double min, double max, double& value) {
        const auto* field = params ? params->get(key) : nullptr;
        if (!field) return true;
        if (!field->is_num() || !std::isfinite(field->num_val) ||
            field->num_val < min || field->num_val > max) return false;
        value = field->num_val;
        return true;
    };
    if (params) {
        if (!params->is_obj()) return false;
        for (const auto& field : params->obj_val) {
            if (field.first != "bass_gain" && field.first != "treble_gain" &&
                field.first != "subsonic_freq") return false;
        }
    }
    if (!number("bass_gain", -6, 8, bass_db) || !number("treble_gain", -6, 6, treble_db) ||
        !number("subsonic_freq", 35, 80, sub)) return false;
    auto flags = Value::make_obj();
    for (const char* key : {"subsonic", "notch120", "phase", "treble"}) flags[key] = Value::make_bool(true);
    if (filters) {
        if (!filters->is_obj()) return false;
        for (const auto& [key, value] : filters->obj_val) {
            if (!value.is_bool() || !flags.get(key)) return false;
            flags[key] = value;
        }
    }
    auto controls = Value::make_obj();
    controls["bass_gain"] = Value::make_num(bass_db);
    controls["treble_gain"] = Value::make_num(treble_db);
    controls["subsonic_freq"] = Value::make_num(sub);
    profile["params"] = controls;
    profile["filters"] = flags;
    if (!custom) {
        changes = name == "hifi" ? hifi : name == "night" ? night : name == "bass" ? bass : club;
        return true;
    }
    auto on = [&](const char* key) { return flags.get(key)->bool_val; };
    // The first woofer and first tweeter entries in hifi are the calibrated
    // notch and phase filters, respectively. Use register keys to find them.
    for (const auto& [amp, reg, value] : hifi) {
        if ((amp == 0x2b && reg == 0x3a) || (amp == 0x2a && reg == 0x38) ||
            (amp == 0x2a && reg == 0x3c && std::abs(treble_db - 3) < 0.1)) {
            const char* key = amp == 0x2b ? "notch120" : reg == 0x38 ? "phase" : "treble";
            changes.emplace_back(amp, reg, on(key) ? value : identity);
        }
    }
    changes.emplace_back(0x2b, 0x3b, on("subsonic") ? high_pass(sub, .5412) : identity);
    changes.emplace_back(0x2b, 0x3c, on("subsonic") ? high_pass(sub, 1.3065) : identity);
    changes.emplace_back(0x2b, 0x3d, std::abs(bass_db) > .1 ? shelf(60, .85, bass_db, false) : identity);
    if (std::abs(treble_db - 3) >= .1) {
        changes.emplace_back(0x2a, 0x3c, on("treble") ? shelf(13500, .7, treble_db, true) : identity);
    }
    return true;
}

double gain_at(const std::string& data, double frequency) {
    std::array<double, 5> c{};
    for (size_t i = 0; i < c.size(); ++i) {
        const auto word = std::strtoul(data.substr(i * 8, 8).c_str(), nullptr, 16);
        c[i] = static_cast<int32_t>(word) / double(1 << 26);
    }
    const auto z = std::polar(1., -2 * pi * frequency / 48000);
    const auto magnitude = std::abs((c[4]+c[3]*z+c[2]*z*z) / (1.-c[1]*z-c[0]*z*z));
    return 20 * std::log10(std::max(magnitude, .0001));
}

json::Value curve(const Changes& changes) {
    using json::Value;
    Value woofer = Value::make_arr(), tweeters = Value::make_arr(), system = Value::make_arr();
    for (int i = 0; i < 60; ++i) {
        const double hz = 20 * std::pow(1000., i / 59.);
        double w = 0, t = 0;
        for (const auto& [amp, reg, coeff] : changes) {
            if (amp == 0x2b) w += gain_at(coeff, hz);
            else t += gain_at(coeff, hz);
        }
        woofer.push(Value::make_num(std::clamp(w, -40., 12.)));
        tweeters.push(Value::make_num(std::clamp(t, -40., 12.)));
        const double blend = std::clamp(std::log10(hz / 300.), 0., 1.);
        system.push(Value::make_num(std::clamp(w * (1 - blend) + t * blend, -40., 12.)));
    }
    auto result = Value::make_obj();
    result["woofer_db"] = std::move(woofer);
    result["tweeter_db"] = std::move(tweeters);
    result["system_db"] = std::move(system);
    return result;
}
} // namespace

bool Sy6045sEq::supported() const {
    return access(device, R_OK | W_OK) == 0 &&
           access(settings(amps[0]).c_str(), W_OK) == 0 &&
           access(settings(amps[1]).c_str(), W_OK) == 0;
}

json::Value Sy6045sEq::status() {
    std::lock_guard<std::mutex> lock(mutex_);
    auto out = active_;
    out["supported"] = json::Value::make_bool(supported());
    if (!out.get("profile")) out["profile"] = json::Value::make_str("original");
    out["canRestore"] = json::Value::make_bool(!original_.empty());
    Bus bus;
    out["verified"] = json::Value::make_bool(bus.fd >= 0 && !expected_.empty() &&
                                             snapshot(bus.fd) == expected_);
    return out;
}

bool Sy6045sEq::apply(const json::Value& request, std::string& error) {
    std::lock_guard<std::mutex> lock(mutex_);
    Changes changes;
    auto next = json::Value::make_obj();
    if (!plan(request, changes, next)) { error = "Invalid hardware EQ settings"; return false; }
    if (!supported()) { error = "SY6045S amplifier pair unavailable"; return false; }
    const bool restore = next.get("profile")->as_str() == "original";
    if (restore && original_.empty()) { error = "No original DSP snapshot"; return false; }
    Bus bus;
    const auto before = bus.fd >= 0 ? snapshot(bus.fd) : Registers{};
    if (before.empty()) { error = "Could not read DSP registers"; return false; }
    for (int amp : amps) {
        if (before.at({amp, 3}) != "5e" || before.at({amp, 6}) != "00" || before.at({amp, 0x22}) != "00") {
            error = "Amplifier is busy";
            return false;
        }
    }
    auto target = original_.empty() ? before : original_;
    for (const auto& [amp, reg, data] : changes) target[{amp, reg}] = data;
    if (!write_coefficients(bus.fd, target, false)) {
        error = write_coefficients(bus.fd, before, true)
              ? "DSP readback failed, previous registers restored"
              : "DSP readback and restoration failed";
        return false;
    }
    if (original_.empty()) original_ = before;
    if (!restore) next["curve"] = curve(changes);
    active_ = std::move(next);
    expected_ = std::move(target);
    return true;
}
} // namespace openstation
