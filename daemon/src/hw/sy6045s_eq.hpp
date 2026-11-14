#pragma once

#include <map>
#include <mutex>
#include <string>
#include <utility>
#include "utils/json_min.hpp"

namespace openstation {

// Coefficients for the built-in speakers on boards with two SY6045S amplifiers.
class Sy6045sEq {
public:
    bool supported() const;
    json::Value status();
    bool apply(const json::Value& request, std::string& error);

private:
    using Registers = std::map<std::pair<int, int>, std::string>;
    std::mutex mutex_;
    Registers original_;
    Registers expected_;
    json::Value active_ = json::Value::make_obj();
};

} // namespace openstation
