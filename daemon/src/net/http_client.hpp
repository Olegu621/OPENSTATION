#pragma once

#include <atomic>
#include <cstdint>
#include <map>
#include <string>

namespace openstation {

struct HttpClientRequest {
    std::string method = "GET";
    std::string url;
    std::map<std::string, std::string> headers;
    std::string body;
    int timeout_ms = 8000;
    int max_redirects = 5;
    size_t max_body_bytes = 512 * 1024;
    bool verify_tls = true;
    const std::atomic<bool>* abort_req = nullptr;
};

struct HttpClientResponse {
    bool ok = false;
    int status_code = 0;
    std::string status_text;
    std::string final_url;
    std::string content_type;
    std::map<std::string, std::string> headers; // lower-cased keys
    std::string body;
    bool truncated = false;
    std::string error;
};

HttpClientResponse http_perform(const HttpClientRequest& req);

std::string url_encode(const std::string& s);
std::string html_decode_entities(const std::string& in);
std::string html_to_text(const std::string& html, size_t max_chars = 6000);
std::string cp1251_to_utf8(const std::string& in);
std::string ensure_utf8_and_truncate(const std::string& raw, size_t max_bytes,
                                     const std::string& content_type_hint = "");

} // namespace openstation
