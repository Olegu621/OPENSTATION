#include "hw/alsa_mixer.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sound/asound.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace openstation {

namespace {
bool write_sysfs(const char* path, const char* val) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return false;
    ssize_t n = write(fd, val, strlen(val));
    close(fd);
    return n > 0;
}
} // namespace

AlsaMixer::~AlsaMixer() {
    close();
}

void AlsaMixer::close() {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
}

bool AlsaMixer::init(int card) {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
    char dev[64];
    snprintf(dev, sizeof(dev), "/dev/snd/controlC%d", card);
    fd_ = open(dev, O_RDWR);
    if (fd_ < 0) {
        LOGE("AlsaMixer", "Failed to open %s: %s", dev, strerror(errno));
        return false;
    }
    if (!refresh_controls()) {
        LOGE("AlsaMixer", "Failed to enumerate mixer controls on %s", dev);
        ::close(fd_);
        fd_ = -1;
        return false;
    }
    LOGI("AlsaMixer", "Opened %s with %zu controls", dev, controls_.size());
    return true;
}

bool AlsaMixer::refresh_controls() {
    controls_.clear();
    struct snd_ctl_elem_list elist {};
    if (ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_LIST, &elist) < 0) {
        return false;
    }
    if (elist.count == 0) return true;
    std::vector<struct snd_ctl_elem_id> ids(elist.count);
    elist.offset = 0;
    elist.space = elist.count;
    elist.pids = ids.data();
    if (ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_LIST, &elist) < 0) {
        return false;
    }

    for (unsigned int i = 0; i < elist.used; ++i) {
        struct snd_ctl_elem_info info {};
        info.id = ids[i];
        if (ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) continue;

        MixerControlMeta meta;
        meta.numid = info.id.numid;
        meta.name = reinterpret_cast<const char*>(info.id.name);
        meta.type = info.type;
        meta.count = info.count;
        if (info.type == SNDRV_CTL_ELEM_TYPE_INTEGER) {
            meta.min_val = info.value.integer.min;
            meta.max_val = info.value.integer.max;
        } else if (info.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN) {
            meta.min_val = 0;
            meta.max_val = 1;
        } else if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
            unsigned int items = info.value.enumerated.items;
            meta.min_val = 0;
            meta.max_val = items > 0 ? static_cast<long>(items - 1) : 0;
            for (unsigned int e = 0; e < items; ++e) {
                info.value.enumerated.item = e;
                if (ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_INFO, &info) == 0) {
                    meta.enum_names.emplace_back(info.value.enumerated.name);
                } else {
                    meta.enum_names.emplace_back("");
                }
            }
        }
        controls_[meta.name] = std::move(meta);
    }
    return true;
}

bool AlsaMixer::get_int(const std::string& name, long& out, int idx) {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ < 0) return false;
    auto it = controls_.find(name);
    if (it == controls_.end()) return false;
    struct snd_ctl_elem_value val {};
    val.id.numid = it->second.numid;
    if (ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_READ, &val) < 0) return false;
    if (idx < 0 || static_cast<unsigned int>(idx) >= it->second.count) return false;
    out = val.value.integer.value[idx];
    return true;
}

bool AlsaMixer::set_int(const std::string& name, long value, int idx) {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ < 0) return false;
    auto it = controls_.find(name);
    if (it == controls_.end()) return false;
    long lo = std::min(it->second.min_val, it->second.max_val);
    long hi = std::max(it->second.min_val, it->second.max_val);
    if (value < lo) value = lo;
    if (value > hi) value = hi;

    struct snd_ctl_elem_value val {};
    val.id.numid = it->second.numid;
    if (ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_READ, &val) < 0) return false;
    if (idx < 0) {
        for (unsigned int i = 0; i < it->second.count && i < 128; ++i) {
            val.value.integer.value[i] = value;
        }
    } else {
        if (static_cast<unsigned int>(idx) >= it->second.count || idx >= 128) return false;
        val.value.integer.value[idx] = value;
    }
    if (ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) < 0) {
        LOGW("AlsaMixer", "Failed to set %s=%ld: %s", name.c_str(), value, strerror(errno));
        return false;
    }
    return true;
}

bool AlsaMixer::get_bool(const std::string& name, bool& out) {
    long v = 0;
    if (!get_int(name, v, 0)) return false;
    out = (v != 0);
    return true;
}

bool AlsaMixer::set_bool(const std::string& name, bool value) {
    return set_int(name, value ? 1 : 0, -1);
}

bool AlsaMixer::get_enum(const std::string& name, std::string& out) {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ < 0) return false;
    auto it = controls_.find(name);
    if (it == controls_.end() || it->second.type != SNDRV_CTL_ELEM_TYPE_ENUMERATED) return false;
    struct snd_ctl_elem_value val {};
    val.id.numid = it->second.numid;
    if (ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_READ, &val) < 0) return false;
    unsigned int idx = val.value.enumerated.item[0];
    if (idx < it->second.enum_names.size()) {
        out = it->second.enum_names[idx];
        return true;
    }
    return false;
}

bool AlsaMixer::set_enum_idx(const std::string& name, unsigned int idx) {
    std::lock_guard<std::mutex> lk(mu_);
    if (fd_ < 0) return false;
    auto it = controls_.find(name);
    if (it == controls_.end() || it->second.type != SNDRV_CTL_ELEM_TYPE_ENUMERATED) return false;
    if (idx >= it->second.enum_names.size()) return false;
    struct snd_ctl_elem_value val {};
    val.id.numid = it->second.numid;
    val.value.enumerated.item[0] = idx;
    return ioctl(fd_, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) == 0;
}

bool AlsaMixer::set_enum(const std::string& name, const std::string& choice) {
    unsigned int target = 0;
    bool found = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = controls_.find(name);
        if (it == controls_.end()) return false;
        for (unsigned int i = 0; i < it->second.enum_names.size(); ++i) {
            if (it->second.enum_names[i] == choice) {
                target = i;
                found = true;
                break;
            }
        }
    }
    if (!found) return false;
    return set_enum_idx(name, target);
}

bool AlsaMixer::apply_output_route(const std::string& route, bool hp_plugged, bool muted) {
    bool spk_on = true;
    bool hp_on = false;
    if (route == "speakers") {
        spk_on = true;
        hp_on = false;
    } else if (route == "headphones") {
        spk_on = false;
        hp_on = true;
    } else if (route == "both") {
        spk_on = true;
        hp_on = true;
    } else if (route == "hdmi") {
        spk_on = false;
        hp_on = false;
    } else {
        // "auto": follow physical 3.5mm jack switch
        spk_on = !hp_plugged;
        hp_on = hp_plugged;
    }

    // STBY Switch On = standby/muted, Off = active
    set_bool("Woofer STBY Switch", !spk_on);
    set_bool("Tweeters STBY Switch", !spk_on);
    set_bool("Woofer Master Playback Switch", spk_on && !muted);
    set_bool("Tweeters Master Playback Switch", spk_on && !muted);
    set_bool("Headphone Headphone Switch", hp_on && !muted);
    LOGI("AlsaMixer", "Applied route '%s' (hp_plugged=%d, muted=%d -> spk=%d hp=%d)",
         route.c_str(), hp_plugged ? 1 : 0, muted ? 1 : 0, spk_on ? 1 : 0, hp_on ? 1 : 0);
    return true;
}

bool AlsaMixer::reload_amp_defaults() {
    bool a = write_sysfs("/tmp/amp_woofer_default_settings", "1\n");
    bool b = write_sysfs("/tmp/amp_mihi_default_settings", "1\n");
    LOGI("AlsaMixer", "Reloaded SY6045S factory defaults: woofer=%d tweeters=%d", a ? 1 : 0, b ? 1 : 0);
    return a || b;
}

json::Value AlsaMixer::get_status_json() {
    using json::Value;
    Value obj = Value::make_obj();
    long v = 0;
    bool b = false;
    std::string e;

    if (get_int("Woofer Master Playback Volume", v)) obj["wooferVolume"] = Value::make_int(v);
    if (get_int("Tweeters Master Playback Volume", v)) obj["tweetersVolume"] = Value::make_int(v);
    if (get_int("Headphone DAC Playback Volume", v)) obj["headphoneVolume"] = Value::make_int(v);
    if (get_bool("Woofer Master Playback Switch", b)) obj["wooferSwitch"] = Value::make_bool(b);
    if (get_bool("Tweeters Master Playback Switch", b)) obj["tweetersSwitch"] = Value::make_bool(b);
    if (get_bool("Woofer STBY Switch", b)) obj["wooferStandby"] = Value::make_bool(b);
    if (get_bool("Tweeters STBY Switch", b)) obj["tweetersStandby"] = Value::make_bool(b);
    if (get_bool("Woofer EQ Switch", b)) obj["wooferEq"] = Value::make_bool(b);
    if (get_bool("Woofer DRC Switch", b)) obj["wooferDrc"] = Value::make_bool(b);
    if (get_bool("Tweeters EQ Switch", b)) obj["tweetersEq"] = Value::make_bool(b);
    if (get_bool("Tweeters DRC Switch", b)) obj["tweetersDrc"] = Value::make_bool(b);
    if (get_bool("Headphone Headphone Switch", b)) obj["headphoneSwitch"] = Value::make_bool(b);
    if (get_bool("Headphones Jack", b)) obj["headphonesJack"] = Value::make_bool(b);
    if (get_int("PDM HCIC shift gain (~ 6dB * value)", v)) obj["pdmGain"] = Value::make_int(v);
    if (get_enum("PDM Filter Mode", e)) obj["pdmFilter"] = Value::make_str(e);
    if (get_enum("Loopback Enable", e)) obj["loopbackEnable"] = Value::make_str(e);
    return obj;
}

} // namespace openstation
