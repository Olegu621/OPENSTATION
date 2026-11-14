#pragma once

#include <string>

namespace openstation {

// Input is already lower-cased by the transcript handler. Match only short,
// unambiguous requests; named devices must continue through the normal path.
inline bool is_assistant_stop_request(const std::string& text) {
    std::string normalized;
    for (unsigned char ch : text) {
        if (ch == ',' || ch == '.' || ch == '!' || ch == '?' || ch == '\n' || ch == '\t' || ch == ' ') {
            if (!normalized.empty() && normalized.back() != ' ') normalized += ' ';
        } else {
            normalized += static_cast<char>(ch);
        }
    }
    if (!normalized.empty() && normalized.back() == ' ') normalized.pop_back();
    return normalized == "выключись" || normalized == "алиса выключись" ||
           normalized == "выключись пожалуйста" || normalized == "алиса выключись пожалуйста" ||
           normalized == "пожалуйста выключись" || normalized == "алиса пожалуйста выключись";
}

} // namespace openstation
