#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include "utils/json_min.hpp"

namespace openstation {

struct MixerControlMeta {
    unsigned int numid = 0;
    std::string name;
    int type = 0; // SNDRV_CTL_ELEM_TYPE_*
    unsigned int count = 0;
    long min_val = 0;
    long max_val = 0;
    std::vector<std::string> enum_names;
};

class AlsaMixer {
public:
    AlsaMixer() = default;
    ~AlsaMixer();

    bool init(int card = 0);
    void close();

    bool set_int(const std::string& name, long value, int idx = 0);
    bool get_int(const std::string& name, long& out, int idx = 0);
    bool set_bool(const std::string& name, bool value);
    bool get_bool(const std::string& name, bool& out);
    bool set_enum(const std::string& name, const std::string& choice);
    bool set_enum_idx(const std::string& name, unsigned int idx);
    bool get_enum(const std::string& name, std::string& out);

    // High-level Yandex Station Max audio routing & hardware controls
    bool apply_output_route(const std::string& route, bool hp_plugged, bool muted = false);
    bool reload_amp_defaults();
    json::Value get_status_json();

private:
    bool refresh_controls();
    int fd_ = -1;
    std::mutex mu_;
    std::unordered_map<std::string, MixerControlMeta> controls_;
};

} // namespace openstation
