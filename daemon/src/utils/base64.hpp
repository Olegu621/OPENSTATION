#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace openstation::b64 {

inline std::string encode(const uint8_t* data, size_t len) {
    static const char kTbl[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    for (size_t i = 0; i < len; i += 3) {
        uint32_t v = static_cast<uint32_t>(data[i]) << 16;
        if (i + 1 < len) v |= static_cast<uint32_t>(data[i + 1]) << 8;
        if (i + 2 < len) v |= static_cast<uint32_t>(data[i + 2]);
        out.push_back(kTbl[(v >> 18) & 0x3F]);
        out.push_back(kTbl[(v >> 12) & 0x3F]);
        out.push_back(i + 1 < len ? kTbl[(v >> 6) & 0x3F] : '=');
        out.push_back(i + 2 < len ? kTbl[v & 0x3F] : '=');
    }
    return out;
}

inline std::string encode_str(const std::string& s) {
    return encode(reinterpret_cast<const uint8_t*>(s.data()), s.size());
}

inline std::vector<uint8_t> decode(const std::string& in) {
    std::vector<uint8_t> out;
    out.reserve((in.size() * 3) / 4);
    uint32_t val = 0;
    int bits = -8;
    for (unsigned char c : in) {
        int d = -1;
        if (c >= 'A' && c <= 'Z') d = c - 'A';
        else if (c >= 'a' && c <= 'z') d = c - 'a' + 26;
        else if (c >= '0' && c <= '9') d = c - '0' + 52;
        else if (c == '+' || c == '-') d = 62;
        else if (c == '/' || c == '_') d = 63;
        else if (c == '=') break;
        else continue; // skip whitespace/newlines

        val = (val << 6) | static_cast<uint32_t>(d);
        bits += 6;
        if (bits >= 0) {
            out.push_back(static_cast<uint8_t>((val >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

} // namespace openstation::b64
