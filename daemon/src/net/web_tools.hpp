#pragma once

#include "utils/json_min.hpp"

#include <atomic>
#include <string>

namespace openstation {

struct GeoLocationResult {
    bool ok = false;
    std::string city;
    std::string country;
    std::string region;
    std::string timezone;
    double lat = 0.0;
    double lon = 0.0;
    std::string error;
};

GeoLocationResult detect_location_by_ip(const std::atomic<bool>* abort_req = nullptr);

json::Value tool_web_search(const std::string& query,
                            int count = 5,
                            const std::atomic<bool>* abort_req = nullptr);

json::Value tool_get_weather(const std::string& city,
                             int days = 3,
                             double fallback_lat = 0.0,
                             double fallback_lon = 0.0,
                             const std::string& fallback_city = "",
                             const std::atomic<bool>* abort_req = nullptr);

json::Value tool_fetch_url(const std::string& url,
                           bool raw = false,
                           int max_chars = 6000,
                           const std::atomic<bool>* abort_req = nullptr);

json::Value tool_http_request(const std::string& url,
                              const std::string& method = "GET",
                              const std::string& body = "",
                              const std::string& headers_json = "",
                              bool insecure = false,
                              const std::atomic<bool>* abort_req = nullptr);

} // namespace openstation
