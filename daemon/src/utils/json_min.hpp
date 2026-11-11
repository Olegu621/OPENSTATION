#pragma once

#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace openstation::json {

enum class Type { Null, Bool, Number, String, Array, Object };

struct Value {
    Type type = Type::Null;
    bool bool_val = false;
    double num_val = 0.0;
    std::string str_val;
    std::vector<Value> arr_val;
    std::map<std::string, Value> obj_val;

    static Value make_null() { return Value{}; }
    static Value make_bool(bool b) { Value v; v.type = Type::Bool; v.bool_val = b; return v; }
    static Value make_num(double n) { Value v; v.type = Type::Number; v.num_val = n; return v; }
    static Value make_int(int64_t n) { Value v; v.type = Type::Number; v.num_val = static_cast<double>(n); return v; }
    static Value make_str(std::string s) { Value v; v.type = Type::String; v.str_val = std::move(s); return v; }
    static Value make_arr() { Value v; v.type = Type::Array; return v; }
    static Value make_obj() { Value v; v.type = Type::Object; return v; }

    bool is_null() const { return type == Type::Null; }
    bool is_bool() const { return type == Type::Bool; }
    bool is_num() const { return type == Type::Number; }
    bool is_str() const { return type == Type::String; }
    bool is_arr() const { return type == Type::Array; }
    bool is_obj() const { return type == Type::Object; }

    bool as_bool(bool def = false) const {
        if (type == Type::Bool) return bool_val;
        if (type == Type::Number) return num_val != 0.0;
        return def;
    }
    double as_double(double def = 0.0) const {
        return type == Type::Number ? num_val : def;
    }
    int as_int(int def = 0) const {
        return type == Type::Number ? static_cast<int>(std::llround(num_val)) : def;
    }
    int64_t as_i64(int64_t def = 0) const {
        return type == Type::Number ? static_cast<int64_t>(std::llround(num_val)) : def;
    }
    std::string as_str(const std::string& def = "") const {
        return type == Type::String ? str_val : def;
    }

    const Value* get(const std::string& key) const {
        if (type != Type::Object) return nullptr;
        auto it = obj_val.find(key);
        return it == obj_val.end() ? nullptr : &it->second;
    }
    Value& operator[](const std::string& key) {
        if (type != Type::Object) {
            type = Type::Object;
            obj_val.clear();
        }
        return obj_val[key];
    }
    void push(Value v) {
        if (type != Type::Array) {
            type = Type::Array;
            arr_val.clear();
        }
        arr_val.push_back(std::move(v));
    }

    static std::string escape(const std::string& s) {
        std::string out;
        out.reserve(s.size() + 8);
        out.push_back('"');
        for (unsigned char c : s) {
            switch (c) {
                case '"': out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\b': out += "\\b"; break;
                case '\f': out += "\\f"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                default:
                    if (c < 0x20) {
                        char buf[8];
                        snprintf(buf, sizeof(buf), "\\u%04x", c);
                        out += buf;
                    } else {
                        out.push_back(static_cast<char>(c));
                    }
            }
        }
        out.push_back('"');
        return out;
    }

    std::string dump(int indent = -1, int depth = 0) const {
        std::string pad = indent >= 0 ? std::string((depth + 1) * indent, ' ') : "";
        std::string end_pad = indent >= 0 ? std::string(depth * indent, ' ') : "";
        std::string nl = indent >= 0 ? "\n" : "";
        std::string sep = indent >= 0 ? ": " : ":";

        switch (type) {
            case Type::Null: return "null";
            case Type::Bool: return bool_val ? "true" : "false";
            case Type::Number: {
                if (!std::isfinite(num_val)) return "0";
                double intpart = 0.0;
                if (std::modf(num_val, &intpart) == 0.0 &&
                    std::fabs(num_val) < 9e15) {
                    return std::to_string(static_cast<int64_t>(intpart));
                }
                char buf[64];
                snprintf(buf, sizeof(buf), "%.6g", num_val);
                return buf;
            }
            case Type::String: return escape(str_val);
            case Type::Array: {
                if (arr_val.empty()) return "[]";
                std::string s = "[" + nl;
                for (size_t i = 0; i < arr_val.size(); ++i) {
                    s += pad + arr_val[i].dump(indent, depth + 1);
                    if (i + 1 < arr_val.size()) s += ",";
                    s += nl;
                }
                s += end_pad + "]";
                return s;
            }
            case Type::Object: {
                if (obj_val.empty()) return "{}";
                std::string s = "{" + nl;
                size_t i = 0;
                for (const auto& [k, v] : obj_val) {
                    s += pad + escape(k) + sep + v.dump(indent, depth + 1);
                    if (++i < obj_val.size()) s += ",";
                    s += nl;
                }
                s += end_pad + "}";
                return s;
            }
        }
        return "null";
    }
};

class Parser {
public:
    static bool parse(const std::string& input, Value& out) {
        Parser p(input);
        p.skip_ws();
        if (!p.parse_value(out)) return false;
        p.skip_ws();
        return p.pos_ == p.s_.size();
    }

private:
    explicit Parser(const std::string& s) : s_(s) {}
    const std::string& s_;
    size_t pos_ = 0;

    void skip_ws() {
        while (pos_ < s_.size() && (s_[pos_] == ' ' || s_[pos_] == '\t' || s_[pos_] == '\r' || s_[pos_] == '\n')) {
            ++pos_;
        }
    }

    static void append_utf8(std::string& out, uint32_t cp) {
        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    bool parse_hex4(uint32_t& val) {
        if (pos_ + 4 > s_.size()) return false;
        val = 0;
        for (int i = 0; i < 4; ++i) {
            char c = s_[pos_++];
            val <<= 4;
            if (c >= '0' && c <= '9') val |= (c - '0');
            else if (c >= 'a' && c <= 'f') val |= (c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') val |= (c - 'A' + 10);
            else return false;
        }
        return true;
    }

    bool parse_string(std::string& out) {
        if (pos_ >= s_.size() || s_[pos_] != '"') return false;
        ++pos_;
        out.clear();
        while (pos_ < s_.size()) {
            char c = s_[pos_++];
            if (c == '"') return true;
            if (c == '\\') {
                if (pos_ >= s_.size()) return false;
                char esc = s_[pos_++];
                switch (esc) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        uint32_t cp = 0;
                        if (!parse_hex4(cp)) return false;
                        if (cp >= 0xD800 && cp <= 0xDBFF && pos_ + 2 <= s_.size() &&
                            s_[pos_] == '\\' && s_[pos_ + 1] == 'u') {
                            pos_ += 2;
                            uint32_t low = 0;
                            if (!parse_hex4(low)) return false;
                            cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                        }
                        append_utf8(out, cp);
                        break;
                    }
                    default: return false;
                }
            } else {
                out.push_back(c);
            }
        }
        return false;
    }

    bool parse_number(Value& out) {
        size_t start = pos_;
        if (s_[pos_] == '-') ++pos_;
        if (pos_ >= s_.size() || !std::isdigit(static_cast<unsigned char>(s_[pos_]))) return false;
        while (pos_ < s_.size() && std::isdigit(static_cast<unsigned char>(s_[pos_]))) ++pos_;
        if (pos_ < s_.size() && s_[pos_] == '.') {
            ++pos_;
            if (pos_ >= s_.size() || !std::isdigit(static_cast<unsigned char>(s_[pos_]))) return false;
            while (pos_ < s_.size() && std::isdigit(static_cast<unsigned char>(s_[pos_]))) ++pos_;
        }
        if (pos_ < s_.size() && (s_[pos_] == 'e' || s_[pos_] == 'E')) {
            ++pos_;
            if (pos_ < s_.size() && (s_[pos_] == '+' || s_[pos_] == '-')) ++pos_;
            if (pos_ >= s_.size() || !std::isdigit(static_cast<unsigned char>(s_[pos_]))) return false;
            while (pos_ < s_.size() && std::isdigit(static_cast<unsigned char>(s_[pos_]))) ++pos_;
        }
        std::string sub = s_.substr(start, pos_ - start);
        char* endp = nullptr;
        double d = std::strtod(sub.c_str(), &endp);
        if (endp == sub.c_str()) return false;
        out = Value::make_num(d);
        return true;
    }

    bool parse_value(Value& out) {
        skip_ws();
        if (pos_ >= s_.size()) return false;
        char c = s_[pos_];
        if (c == '"') {
            std::string str;
            if (!parse_string(str)) return false;
            out = Value::make_str(std::move(str));
            return true;
        }
        if (c == '{') {
            ++pos_;
            out = Value::make_obj();
            skip_ws();
            if (pos_ < s_.size() && s_[pos_] == '}') { ++pos_; return true; }
            while (true) {
                skip_ws();
                std::string k;
                if (!parse_string(k)) return false;
                skip_ws();
                if (pos_ >= s_.size() || s_[pos_] != ':') return false;
                ++pos_;
                Value v;
                if (!parse_value(v)) return false;
                out.obj_val[k] = std::move(v);
                skip_ws();
                if (pos_ >= s_.size()) return false;
                if (s_[pos_] == '}') { ++pos_; return true; }
                if (s_[pos_] != ',') return false;
                ++pos_;
            }
        }
        if (c == '[') {
            ++pos_;
            out = Value::make_arr();
            skip_ws();
            if (pos_ < s_.size() && s_[pos_] == ']') { ++pos_; return true; }
            while (true) {
                Value v;
                if (!parse_value(v)) return false;
                out.arr_val.push_back(std::move(v));
                skip_ws();
                if (pos_ >= s_.size()) return false;
                if (s_[pos_] == ']') { ++pos_; return true; }
                if (s_[pos_] != ',') return false;
                ++pos_;
            }
        }
        if (s_.compare(pos_, 4, "true") == 0) { pos_ += 4; out = Value::make_bool(true); return true; }
        if (s_.compare(pos_, 5, "false") == 0) { pos_ += 5; out = Value::make_bool(false); return true; }
        if (s_.compare(pos_, 4, "null") == 0) { pos_ += 4; out = Value::make_null(); return true; }
        return parse_number(out);
    }
};

} // namespace openstation::json
