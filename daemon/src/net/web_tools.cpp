#include "net/web_tools.hpp"
#include "net/http_client.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace openstation {

namespace {

std::string to_lower_ascii(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string trim_ws(std::string s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) s.erase(s.begin());
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.pop_back();
    return s;
}

int hex_val(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::string url_decode(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        if (in[i] == '%' && i + 2 < in.size()) {
            int hi = hex_val(in[i + 1]);
            int lo = hex_val(in[i + 2]);
            if (hi >= 0 && lo >= 0) {
                out.push_back(static_cast<char>((hi << 4) | lo));
                i += 2;
                continue;
            }
        } else if (in[i] == '+') {
            out.push_back(' ');
            continue;
        }
        out.push_back(in[i]);
    }
    return out;
}

std::string strip_tags_and_decode(const std::string& raw) {
    std::string s = raw;
    // Strip CDATA wrapper if present
    size_t cd = s.find("<![CDATA[");
    if (cd != std::string::npos) {
        size_t cd_end = s.find("]]>", cd + 9);
        if (cd_end != std::string::npos) {
            s = s.substr(cd + 9, cd_end - (cd + 9));
        }
    }
    std::string no_tags;
    no_tags.reserve(s.size());
    bool in_tag = false;
    bool prev_space = true;
    for (char c : s) {
        if (c == '<') {
            in_tag = true;
            if (!prev_space) {
                no_tags.push_back(' ');
                prev_space = true;
            }
            continue;
        }
        if (c == '>') {
            in_tag = false;
            continue;
        }
        if (in_tag) continue;
        if (c == '\r' || c == '\n' || c == '\t' || c == ' ') {
            if (!prev_space) {
                no_tags.push_back(' ');
                prev_space = true;
            }
        } else {
            no_tags.push_back(c);
            prev_space = false;
        }
    }
    return ensure_utf8_and_truncate(trim_ws(html_decode_entities(no_tags)), 1200);
}

std::string extract_xml_tag(const std::string& block, const char* tag) {
    std::string open_t = std::string("<") + tag;
    std::string close_t = std::string("</") + tag + ">";
    size_t p = block.find(open_t);
    if (p == std::string::npos) return "";
    size_t gt = block.find('>', p + open_t.size());
    if (gt == std::string::npos) return "";
    size_t end = block.find(close_t, gt + 1);
    if (end == std::string::npos) return "";
    return block.substr(gt + 1, end - (gt + 1));
}

struct SearchItem {
    std::string title;
    std::string url;
    std::string snippet;
};

std::vector<SearchItem> search_bing_rss(const std::string& query, int max_n, const std::atomic<bool>* abort_req) {
    std::vector<SearchItem> items;
    HttpClientRequest req;
    req.url = "https://www.bing.com/search?q=" + url_encode(query) + "&format=rss&cc=ru&setlang=ru";
    req.timeout_ms = 6000;
    req.abort_req = abort_req;
    req.headers["Accept"] = "application/rss+xml, application/xml, text/xml";
    HttpClientResponse r = http_perform(req);
    if (!r.ok || r.status_code != 200 || r.body.empty()) return items;

    size_t pos = 0;
    while (items.size() < static_cast<size_t>(max_n)) {
        size_t it_s = r.body.find("<item>", pos);
        if (it_s == std::string::npos) break;
        size_t it_e = r.body.find("</item>", it_s);
        if (it_e == std::string::npos) break;
        std::string blk = r.body.substr(it_s, it_e - it_s + 7);
        pos = it_e + 7;

        SearchItem s;
        s.title = strip_tags_and_decode(extract_xml_tag(blk, "title"));
        s.url = strip_tags_and_decode(extract_xml_tag(blk, "link"));
        s.snippet = strip_tags_and_decode(extract_xml_tag(blk, "description"));
        if (!s.title.empty() && !s.url.empty()) {
            items.push_back(std::move(s));
        }
    }
    return items;
}

std::string normalize_ddg_url(std::string href) {
    href = html_decode_entities(trim_ws(href));
    if (href.rfind("//", 0) == 0) href = "https:" + href;
    size_t uddg = href.find("uddg=");
    if (uddg != std::string::npos) {
        size_t amp = href.find('&', uddg + 5);
        std::string enc = (amp == std::string::npos) ? href.substr(uddg + 5) : href.substr(uddg + 5, amp - (uddg + 5));
        std::string dec = url_decode(enc);
        if (dec.rfind("http://", 0) == 0 || dec.rfind("https://", 0) == 0) return dec;
    }
    return href;
}

std::vector<SearchItem> search_ddg_html(const std::string& query, int max_n, const std::atomic<bool>* abort_req) {
    std::vector<SearchItem> items;
    HttpClientRequest req;
    req.url = "https://html.duckduckgo.com/html/?q=" + url_encode(query) + "&kl=ru-ru";
    req.timeout_ms = 7000;
    req.abort_req = abort_req;
    req.headers["Accept"] = "text/html";
    HttpClientResponse r = http_perform(req);
    if (!r.ok || r.status_code != 200 || r.body.empty()) return items;

    // Bail cleanly if DDG presents an anti-bot challenge page
    if (r.body.find("anomaly-modal") != std::string::npos || r.body.find("challenge-form") != std::string::npos) {
        return items;
    }

    size_t pos = 0;
    while (items.size() < static_cast<size_t>(max_n)) {
        size_t a_cls = r.body.find("class=\"result__a\"", pos);
        if (a_cls == std::string::npos) break;
        size_t tag_start = r.body.rfind('<', a_cls);
        size_t tag_gt = r.body.find('>', a_cls);
        size_t a_end = r.body.find("</a>", a_cls);
        if (tag_start == std::string::npos || tag_gt == std::string::npos || a_end == std::string::npos) break;

        std::string a_open = r.body.substr(tag_start, tag_gt - tag_start + 1);
        std::string href;
        size_t hp = a_open.find("href=\"");
        if (hp != std::string::npos) {
            size_t hq = a_open.find('"', hp + 6);
            if (hq != std::string::npos) href = a_open.substr(hp + 6, hq - (hp + 6));
        }

        SearchItem item;
        item.title = strip_tags_and_decode(r.body.substr(tag_gt + 1, a_end - (tag_gt + 1)));
        item.url = normalize_ddg_url(href);

        size_t next_a = r.body.find("class=\"result__a\"", a_end);
        size_t sn_pos = r.body.find("class=\"result__snippet\"", a_end);
        if (sn_pos != std::string::npos && (next_a == std::string::npos || sn_pos < next_a)) {
            size_t s_gt = r.body.find('>', sn_pos);
            size_t s_end = (s_gt == std::string::npos) ? std::string::npos : r.body.find("</", s_gt + 1);
            if (s_gt != std::string::npos && s_end != std::string::npos) {
                item.snippet = strip_tags_and_decode(r.body.substr(s_gt + 1, s_end - (s_gt + 1)));
            }
        }
        pos = a_end + 4;
        if (!item.title.empty() && item.url.rfind("http", 0) == 0) {
            items.push_back(std::move(item));
        }
    }
    return items;
}

std::vector<SearchItem> search_wikipedia(const std::string& query, const char* lang, int max_n, const std::atomic<bool>* abort_req) {
    std::vector<SearchItem> items;
    HttpClientRequest req;
    req.url = std::string("https://") + lang +
              ".wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=" +
              std::to_string(max_n) + "&srsearch=" + url_encode(query);
    req.timeout_ms = 6000;
    req.abort_req = abort_req;
    req.headers["User-Agent"] = "OpenStationMax/1.0";
    HttpClientResponse r = http_perform(req);
    if (!r.ok || r.status_code != 200) return items;

    json::Value doc;
    if (!json::Parser::parse(r.body, doc)) return items;
    const auto* qobj = doc.get("query");
    const auto* arr = qobj ? qobj->get("search") : nullptr;
    if (!arr || !arr->is_arr()) return items;

    for (const auto& entry : arr->arr_val) {
        std::string title = entry.get("title") ? entry.get("title")->as_str() : "";
        std::string snip = entry.get("snippet") ? strip_tags_and_decode(entry.get("snippet")->as_str()) : "";
        if (!title.empty()) {
            SearchItem s;
            s.title = title;
            s.url = std::string("https://") + lang + ".wikipedia.org/wiki/" + url_encode(title);
            s.snippet = snip;
            items.push_back(std::move(s));
        }
    }
    return items;
}

const char* translate_metno_symbol(const std::string& sym) {
    std::string s = to_lower_ascii(sym);
    if (s.find("thunder") != std::string::npos) return "гроза";
    if (s.find("heavysnow") != std::string::npos) return "сильный снег";
    if (s.find("snow") != std::string::npos) return "снег";
    if (s.find("sleet") != std::string::npos) return "дождь со снегом";
    if (s.find("heavyrain") != std::string::npos) return "сильный дождь";
    if (s.find("rain") != std::string::npos) return "дождь";
    if (s.find("fog") != std::string::npos) return "туман";
    if (s.find("cloudy") != std::string::npos) return "облачно";
    if (s.find("partlycloudy") != std::string::npos) return "переменная облачность";
    if (s.find("fair") != std::string::npos) return "малооблачно";
    if (s.find("clearsky") != std::string::npos) return "ясно";
    return sym.c_str();
}

std::string classify_wwo_code(int code) {
    switch (code) {
        case 113: return "clear";
        case 116: return "partly_cloudy";
        case 119:
        case 122: return "cloudy";
        case 143:
        case 248:
        case 260: return "fog";
        case 200:
        case 386:
        case 389:
        case 392:
        case 395: return "storm";
        case 179:
        case 182:
        case 185:
        case 227:
        case 230:
        case 323:
        case 326:
        case 329:
        case 332:
        case 335:
        case 338:
        case 350:
        case 368:
        case 371:
        case 374:
        case 377: return "snow";
        case 176:
        case 263:
        case 266:
        case 281:
        case 284:
        case 293:
        case 296:
        case 299:
        case 302:
        case 305:
        case 308:
        case 311:
        case 314:
        case 317:
        case 320:
        case 353:
        case 356:
        case 359:
        case 362:
        case 365: return "rain";
        default: return "";
    }
}

std::string classify_weather_condition(const std::string& sym_or_code, const std::string& desc) {
    if (!sym_or_code.empty()) {
        int code = std::atoi(sym_or_code.c_str());
        if (code > 0) {
            std::string wc = classify_wwo_code(code);
            if (!wc.empty()) return wc;
        }
        std::string s = to_lower_ascii(sym_or_code);
        if (s.find("thunder") != std::string::npos) return "storm";
        if (s.find("snow") != std::string::npos || s.find("sleet") != std::string::npos) return "snow";
        if (s.find("rain") != std::string::npos) return "rain";
        if (s.find("fog") != std::string::npos) return "fog";
        if (s.find("partly") != std::string::npos || s.find("fair") != std::string::npos) return "partly_cloudy";
        if (s.find("cloud") != std::string::npos) return "cloudy";
        if (s.find("clear") != std::string::npos) return "clear";
    }
    std::string d = to_lower_ascii(desc);
    auto has = [&](const char* a, const char* b = nullptr) {
        return d.find(a) != std::string::npos || (b && d.find(b) != std::string::npos);
    };
    if (has("гроз", "Гроз") || has("thunder")) return "storm";
    if (has("снег", "Снег") || has("снеж", "Снеж") || has("метел", "Метел") ||
        has("snow", "blizzard") || has("sleet")) return "snow";
    if (has("дожд", "Дожд") || has("ливн", "Ливн") || has("ливен", "Ливен") ||
        has("морос", "Морос") || has("осадк", "Осадк") || has("rain", "shower") || has("drizzle")) return "rain";
    if (has("туман", "Туман") || has("дымк", "Дымк") || has("fog", "mist")) return "fog";
    if (has("перемен", "Перемен") || has("малооблач", "Малооблач") ||
        has("небольшая облач", "partly")) return "partly_cloudy";
    if (has("облач", "Облач") || has("пасмур", "Пасмур") ||
        has("cloud", "overcast")) return "cloudy";
    return "clear";
}

} // namespace

GeoLocationResult detect_location_by_ip(const std::atomic<bool>* abort_req) {
    GeoLocationResult out;
    // Provider 1: ip-api.com (fast JSON with Russian city names)
    {
        HttpClientRequest req;
        req.url = "http://ip-api.com/json/?lang=ru&fields=status,message,country,regionName,city,lat,lon,timezone";
        req.timeout_ms = 4500;
        req.abort_req = abort_req;
        HttpClientResponse r = http_perform(req);
        json::Value doc;
        if (r.ok && r.status_code == 200 && json::Parser::parse(r.body, doc)) {
            std::string st = doc.get("status") ? doc.get("status")->as_str() : "";
            if (st == "success") {
                out.ok = true;
                if (const auto* x = doc.get("city")) out.city = trim_ws(x->as_str());
                if (const auto* x = doc.get("country")) out.country = trim_ws(x->as_str());
                if (const auto* x = doc.get("regionName")) out.region = trim_ws(x->as_str());
                if (const auto* x = doc.get("timezone")) out.timezone = trim_ws(x->as_str());
                if (const auto* x = doc.get("lat")) out.lat = x->as_double();
                if (const auto* x = doc.get("lon")) out.lon = x->as_double();
                if (!out.city.empty() || (out.lat != 0.0 && out.lon != 0.0)) return out;
            }
        }
    }
    // Provider 2 fallback: ipwho.is
    {
        HttpClientRequest req;
        req.url = "https://ipwho.is/?lang=ru";
        req.timeout_ms = 5500;
        req.abort_req = abort_req;
        HttpClientResponse r = http_perform(req);
        json::Value doc;
        if (r.ok && r.status_code == 200 && json::Parser::parse(r.body, doc)) {
            if (doc.get("success") && doc.get("success")->as_bool(false)) {
                out.ok = true;
                if (const auto* x = doc.get("city")) out.city = trim_ws(x->as_str());
                if (const auto* x = doc.get("country")) out.country = trim_ws(x->as_str());
                if (const auto* x = doc.get("region")) out.region = trim_ws(x->as_str());
                if (const auto* tz = doc.get("timezone"); tz && tz->is_obj()) {
                    if (const auto* id = tz->get("id")) out.timezone = trim_ws(id->as_str());
                }
                if (const auto* x = doc.get("latitude")) out.lat = x->as_double();
                if (const auto* x = doc.get("longitude")) out.lon = x->as_double();
                if (!out.city.empty() || (out.lat != 0.0 && out.lon != 0.0)) return out;
            }
        }
        out.error = r.error.empty() ? "IP geolocation unreachable" : r.error;
    }
    return out;
}

json::Value tool_web_search(const std::string& query, int count, const std::atomic<bool>* abort_req) {
    using json::Value;
    Value res = Value::make_obj();
    std::string q = trim_ws(query);
    if (q.empty()) {
        res["ok"] = Value::make_bool(false);
        res["error"] = Value::make_str("query is required");
        return res;
    }
    int want = std::clamp(count, 1, 10);

    std::vector<SearchItem> combined;
    std::string engines_used;
    auto push_unique = [&](const std::vector<SearchItem>& src, const char* eng_name) {
        if (src.empty()) return;
        if (!engines_used.empty()) engines_used += "+";
        engines_used += eng_name;
        for (const auto& it : src) {
            if (combined.size() >= static_cast<size_t>(want)) break;
            bool dup = false;
            for (const auto& ex : combined) {
                if (ex.url == it.url || ex.title == it.title) { dup = true; break; }
            }
            if (!dup) combined.push_back(it);
        }
    };

    push_unique(search_bing_rss(q, want, abort_req), "bing");
    if (combined.size() < static_cast<size_t>(std::min(want, 3))) {
        push_unique(search_ddg_html(q, want, abort_req), "duckduckgo");
    }
    if (combined.size() < static_cast<size_t>(std::min(want, 2))) {
        push_unique(search_wikipedia(q, "ru", 2, abort_req), "wikipedia_ru");
    }

    Value arr = Value::make_arr();
    for (const auto& it : combined) {
        Value o = Value::make_obj();
        o["title"] = Value::make_str(it.title);
        o["url"] = Value::make_str(it.url);
        if (!it.snippet.empty()) o["snippet"] = Value::make_str(it.snippet);
        arr.push(std::move(o));
    }

    res["ok"] = Value::make_bool(!combined.empty());
    res["query"] = Value::make_str(q);
    res["engine"] = Value::make_str(engines_used.empty() ? "none" : engines_used);
    res["count"] = Value::make_int(static_cast<int64_t>(combined.size()));
    res["results"] = std::move(arr);
    if (combined.empty()) {
        res["error"] = Value::make_str("No search results retrieved");
    }
    return res;
}

json::Value tool_get_weather(const std::string& city,
                             int days,
                             double fallback_lat,
                             double fallback_lon,
                             const std::string& fallback_city,
                             const std::atomic<bool>* abort_req) {
    using json::Value;
    Value res = Value::make_obj();
    std::string c = trim_ws(city);
    double use_lat = 0.0;
    double use_lon = 0.0;
    std::string hint_city;
    bool using_station_location = false;
    if (c.empty()) {
        using_station_location = true;
        std::string fc = trim_ws(fallback_city);
        if (!fc.empty() || (fallback_lat != 0.0 || fallback_lon != 0.0)) {
            c = fc;
            hint_city = fc;
            use_lat = fallback_lat;
            use_lon = fallback_lon;
        } else {
            GeoLocationResult geo = detect_location_by_ip(abort_req);
            if (geo.ok) {
                c = geo.city;
                hint_city = geo.city;
                use_lat = geo.lat;
                use_lon = geo.lon;
            }
        }
        if (c.empty()) {
            if (use_lat != 0.0 || use_lon != 0.0) {
                char llbuf[48];
                snprintf(llbuf, sizeof(llbuf), "%.3f,%.3f", use_lat, use_lon);
                c = llbuf;
            } else {
                c = "Moscow";
            }
        }
    }
    int n_days = std::clamp(days, 1, 3);

    // Provider 1: wttr.in JSON (localized in Russian, works for Cyrillic & Latin cities)
    {
        HttpClientRequest req;
        req.url = "https://wttr.in/" + url_encode(c) + "?format=j1&lang=ru";
        req.timeout_ms = 6500;
        req.abort_req = abort_req;
        req.headers["Accept"] = "application/json";
        HttpClientResponse r = http_perform(req);
        json::Value doc;
        if (r.ok && r.status_code == 200 && json::Parser::parse(r.body, doc)) {
            const auto* cc_arr = doc.get("current_condition");
            if (cc_arr && cc_arr->is_arr() && !cc_arr->arr_val.empty()) {
                const auto& cur = cc_arr->arr_val[0];
                std::string resolved_city = !hint_city.empty() ? hint_city : c;
                std::string country;
                if (const auto* na = doc.get("nearest_area"); na && na->is_arr() && !na->arr_val.empty()) {
                    if (const auto* an = na->arr_val[0].get("areaName"); an && an->is_arr() && !an->arr_val.empty()) {
                        if (const auto* v = an->arr_val[0].get("value"); v && v->is_str()) {
                            if (hint_city.empty()) resolved_city = v->as_str();
                        }
                    }
                    if (const auto* cn = na->arr_val[0].get("country"); cn && cn->is_arr() && !cn->arr_val.empty()) {
                        if (const auto* v = cn->arr_val[0].get("value"); v && v->is_str()) country = v->as_str();
                    }
                }
                std::string desc;
                if (const auto* lr = cur.get("lang_ru"); lr && lr->is_arr() && !lr->arr_val.empty()) {
                    if (const auto* v = lr->arr_val[0].get("value"); v && v->is_str()) desc = v->as_str();
                } else if (const auto* wd = cur.get("weatherDesc"); wd && wd->is_arr() && !wd->arr_val.empty()) {
                    if (const auto* v = wd->arr_val[0].get("value"); v && v->is_str()) desc = v->as_str();
                }
                std::string wwo = cur.get("weatherCode") ? cur.get("weatherCode")->as_str() : "";
                std::string cond_code = classify_weather_condition(wwo, desc);

                res["ok"] = Value::make_bool(true);
                res["provider"] = Value::make_str("wttr.in");
                res["city"] = Value::make_str(resolved_city);
                res["stationLocation"] = Value::make_bool(using_station_location);
                if (!country.empty()) res["country"] = Value::make_str(country);

                Value cur_out = Value::make_obj();
                int temp_num = 0;
                if (const auto* x = cur.get("temp_C")) {
                    cur_out["tempC"] = Value::make_str(x->as_str());
                    temp_num = static_cast<int>(std::lround(std::atof(x->as_str().c_str())));
                    cur_out["tempNum"] = Value::make_int(temp_num);
                }
                if (const auto* x = cur.get("FeelsLikeC")) {
                    cur_out["feelsLikeC"] = Value::make_str(x->as_str());
                    cur_out["feelsLikeNum"] = Value::make_int(static_cast<int>(std::lround(std::atof(x->as_str().c_str()))));
                }
                if (const auto* x = cur.get("humidity")) cur_out["humidityPct"] = Value::make_str(x->as_str());
                if (const auto* x = cur.get("pressure")) cur_out["pressureHpa"] = Value::make_str(x->as_str());
                if (const auto* x = cur.get("windspeedKmph")) {
                    double kmh = std::atof(x->as_str().c_str());
                    double ms = std::round((kmh / 3.6) * 10.0) / 10.0;
                    cur_out["windMs"] = Value::make_num(ms);
                }
                if (const auto* x = cur.get("winddir16Point")) cur_out["windDir"] = Value::make_str(x->as_str());
                if (!desc.empty()) cur_out["condition"] = Value::make_str(desc);
                cur_out["conditionCode"] = Value::make_str(cond_code);
                res["current"] = std::move(cur_out);

                Value fc = Value::make_arr();
                if (const auto* w_arr = doc.get("weather"); w_arr && w_arr->is_arr()) {
                    for (size_t i = 0; i < std::min<size_t>(w_arr->arr_val.size(), static_cast<size_t>(n_days)); ++i) {
                        const auto& d = w_arr->arr_val[i];
                        Value item = Value::make_obj();
                        if (const auto* dt = d.get("date")) item["date"] = Value::make_str(dt->as_str());
                        if (const auto* mn = d.get("mintempC")) item["minC"] = Value::make_str(mn->as_str());
                        if (const auto* mx = d.get("maxtempC")) item["maxC"] = Value::make_str(mx->as_str());
                        std::string day_desc;
                        std::string day_wwo;
                        if (const auto* h_arr = d.get("hourly"); h_arr && h_arr->is_arr() && h_arr->arr_val.size() > 4) {
                            const auto& noon = h_arr->arr_val[4];
                            if (const auto* lr = noon.get("lang_ru"); lr && lr->is_arr() && !lr->arr_val.empty()) {
                                if (const auto* v = lr->arr_val[0].get("value"); v && v->is_str()) {
                                    day_desc = v->as_str();
                                    item["dayCondition"] = Value::make_str(day_desc);
                                }
                            }
                            if (const auto* wc = noon.get("weatherCode"); wc && wc->is_str()) {
                                day_wwo = wc->as_str();
                            }
                        }
                        item["conditionCode"] = Value::make_str(classify_weather_condition(day_wwo, day_desc));
                        fc.push(std::move(item));
                    }
                }
                res["forecast"] = std::move(fc);
                return res;
            }
        }
    }

    // Provider 2 Fallback: direct coordinates (if known) or Open-Meteo Geocoding + Met.no Locationforecast 2.0
    double lat = use_lat;
    double lon = use_lon;
    std::string rname = !hint_city.empty() ? hint_city : c;
    std::string rcountry;

    if (lat == 0.0 && lon == 0.0) {
        HttpClientRequest geo_req;
        geo_req.url = "https://geocoding-api.open-meteo.com/v1/search?name=" + url_encode(c) +
                      "&count=1&language=ru&format=json";
        geo_req.timeout_ms = 5500;
        geo_req.abort_req = abort_req;
        HttpClientResponse geo_r = http_perform(geo_req);
        json::Value geo_doc;
        if (!geo_r.ok || geo_r.status_code != 200 || !json::Parser::parse(geo_r.body, geo_doc)) {
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("Weather providers unreachable: " + geo_r.error);
            return res;
        }
        const auto* g_arr = geo_doc.get("results");
        if (!g_arr || !g_arr->is_arr() || g_arr->arr_val.empty()) {
            res["ok"] = Value::make_bool(false);
            res["error"] = Value::make_str("City not found: " + c);
            return res;
        }
        const auto& g0 = g_arr->arr_val[0];
        lat = g0.get("latitude") ? g0.get("latitude")->as_double(55.7558) : 55.7558;
        lon = g0.get("longitude") ? g0.get("longitude")->as_double(37.6176) : 37.6176;
        rname = g0.get("name") ? g0.get("name")->as_str(c) : c;
        rcountry = g0.get("country") ? g0.get("country")->as_str() : "";
    }

    char met_url[192];
    snprintf(met_url, sizeof(met_url),
             "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon);
    HttpClientRequest met_req;
    met_req.url = met_url;
    met_req.timeout_ms = 6500;
    met_req.abort_req = abort_req;
    met_req.headers["User-Agent"] = "OpenStationMax/1.3 (https://openstation.local)";
    HttpClientResponse met_r = http_perform(met_req);
    json::Value met_doc;
    if (!met_r.ok || met_r.status_code != 200 || !json::Parser::parse(met_r.body, met_doc)) {
        res["ok"] = Value::make_bool(false);
        res["error"] = Value::make_str("Met.no request failed: " + met_r.error);
        return res;
    }

    const auto* props = met_doc.get("properties");
    const auto* ts = props ? props->get("timeseries") : nullptr;
    if (!ts || !ts->is_arr() || ts->arr_val.empty()) {
        res["ok"] = Value::make_bool(false);
        res["error"] = Value::make_str("Empty forecast timeseries");
        return res;
    }

    const auto& t0 = ts->arr_val[0];
    const auto* d0 = t0.get("data");
    const auto* inst = d0 ? d0->get("instant") : nullptr;
    const auto* det = inst ? inst->get("details") : nullptr;

    res["ok"] = Value::make_bool(true);
    res["provider"] = Value::make_str("met.no");
    res["city"] = Value::make_str(rname);
    res["stationLocation"] = Value::make_bool(using_station_location);
    if (!rcountry.empty()) res["country"] = Value::make_str(rcountry);

    Value cur_out = Value::make_obj();
    if (det) {
        if (const auto* x = det->get("air_temperature")) {
            double td = x->as_double(0.0);
            int ti = static_cast<int>(std::lround(td));
            cur_out["tempC"] = Value::make_str(std::to_string(ti));
            cur_out["tempNum"] = Value::make_int(ti);
            cur_out["feelsLikeC"] = Value::make_str(std::to_string(ti));
            cur_out["feelsLikeNum"] = Value::make_int(ti);
        }
        if (const auto* x = det->get("relative_humidity")) cur_out["humidityPct"] = Value::make_str(std::to_string(static_cast<int>(std::lround(x->as_double(0.0)))));
        if (const auto* x = det->get("wind_speed")) cur_out["windMs"] = *x;
        if (const auto* x = det->get("air_pressure_at_sea_level")) cur_out["pressureHpa"] = *x;
    }
    std::string sym0;
    if (d0) {
        const auto* n1 = d0->get("next_1_hours") ? d0->get("next_1_hours") : d0->get("next_6_hours");
        const auto* sum = n1 ? n1->get("summary") : nullptr;
        const auto* sc = sum ? sum->get("symbol_code") : nullptr;
        if (sc && sc->is_str()) {
            sym0 = sc->as_str();
            cur_out["condition"] = Value::make_str(translate_metno_symbol(sym0));
        }
    }
    cur_out["conditionCode"] = Value::make_str(classify_weather_condition(sym0, ""));
    res["current"] = std::move(cur_out);

    // Build daily summary forecast from Met.no timeseries (up to n_days)
    Value fc = Value::make_arr();
    std::string cur_date;
    double d_min = 999.0, d_max = -999.0;
    std::string d_sym;
    auto flush_day = [&]() {
        if (cur_date.empty() || d_min > 500.0) return;
        Value item = Value::make_obj();
        item["date"] = Value::make_str(cur_date);
        item["minC"] = Value::make_str(std::to_string(static_cast<int>(std::lround(d_min))));
        item["maxC"] = Value::make_str(std::to_string(static_cast<int>(std::lround(d_max))));
        if (!d_sym.empty()) {
            item["dayCondition"] = Value::make_str(translate_metno_symbol(d_sym));
        }
        item["conditionCode"] = Value::make_str(classify_weather_condition(d_sym, ""));
        fc.push(std::move(item));
    };
    for (const auto& step : ts->arr_val) {
        const auto* tm = step.get("time");
        if (!tm || !tm->is_str() || tm->as_str().size() < 10) continue;
        std::string dt = tm->as_str().substr(0, 10);
        if (dt != cur_date) {
            flush_day();
            if (fc.arr_val.size() >= static_cast<size_t>(n_days)) break;
            cur_date = dt;
            d_min = 999.0;
            d_max = -999.0;
            d_sym.clear();
        }
        const auto* d_obj = step.get("data");
        const auto* i_obj = d_obj ? d_obj->get("instant") : nullptr;
        const auto* s_det = i_obj ? i_obj->get("details") : nullptr;
        if (s_det && s_det->get("air_temperature")) {
            double t = s_det->get("air_temperature")->as_double(0.0);
            if (t < d_min) d_min = t;
            if (t > d_max) d_max = t;
        }
        if (d_obj && (d_sym.empty() || tm->as_str().find("T12:") != std::string::npos)) {
            const auto* nx = d_obj->get("next_6_hours") ? d_obj->get("next_6_hours") : d_obj->get("next_1_hours");
            const auto* sum = nx ? nx->get("summary") : nullptr;
            const auto* sc = sum ? sum->get("symbol_code") : nullptr;
            if (sc && sc->is_str()) d_sym = sc->as_str();
        }
    }
    if (fc.arr_val.size() < static_cast<size_t>(n_days)) flush_day();
    res["forecast"] = std::move(fc);
    return res;
}

json::Value tool_fetch_url(const std::string& url,
                           bool raw,
                           int max_chars,
                           const std::atomic<bool>* abort_req) {
    using json::Value;
    Value res = Value::make_obj();
    std::string u = trim_ws(url);
    if (u.empty()) {
        res["ok"] = Value::make_bool(false);
        res["error"] = Value::make_str("url is required");
        return res;
    }

    HttpClientRequest req;
    req.url = u;
    req.timeout_ms = 8000;
    req.abort_req = abort_req;
    HttpClientResponse r = http_perform(req);

    res["ok"] = Value::make_bool(r.ok && r.status_code >= 200 && r.status_code < 400);
    res["status"] = Value::make_int(r.status_code);
    res["finalUrl"] = Value::make_str(r.final_url);
    if (!r.content_type.empty()) res["contentType"] = Value::make_str(r.content_type);
    if (!r.ok) {
        res["error"] = Value::make_str(r.error);
        return res;
    }

    size_t cap = static_cast<size_t>(std::clamp(max_chars, 500, 12000));
    std::string utf8_body = ensure_utf8_and_truncate(r.body, 128 * 1024, r.content_type);
    std::string ct_low = to_lower_ascii(r.content_type);
    bool is_markup = (ct_low.find("html") != std::string::npos ||
                      ct_low.find("xml") != std::string::npos ||
                      utf8_body.rfind("<html", 0) == 0 ||
                      utf8_body.rfind("<!DOCTYPE", 0) == 0 ||
                      utf8_body.rfind("<?xml", 0) == 0);

    std::string extracted = (!raw && is_markup)
                                ? html_to_text(utf8_body, cap)
                                : ensure_utf8_and_truncate(utf8_body, cap, r.content_type);
    res["text"] = Value::make_str(extracted);
    res["truncated"] = Value::make_bool(r.truncated || utf8_body.size() > extracted.size());
    return res;
}

json::Value tool_http_request(const std::string& url,
                              const std::string& method,
                              const std::string& body,
                              const std::string& headers_json,
                              bool insecure,
                              const std::atomic<bool>* abort_req) {
    using json::Value;
    Value res = Value::make_obj();
    std::string u = trim_ws(url);
    if (u.empty()) {
        res["ok"] = Value::make_bool(false);
        res["error"] = Value::make_str("url is required");
        return res;
    }

    HttpClientRequest req;
    req.url = u;
    req.method = method.empty() ? "GET" : method;
    req.body = body;
    req.verify_tls = !insecure;
    req.timeout_ms = 8000;
    req.abort_req = abort_req;

    if (!trim_ws(headers_json).empty()) {
        Value hj;
        if (json::Parser::parse(headers_json, hj) && hj.is_obj()) {
            for (const auto& [k, v] : hj.obj_val) {
                if (v.is_str()) req.headers[k] = v.as_str();
                else if (v.is_num() || v.is_bool()) req.headers[k] = v.dump(-1);
            }
        }
    }

    HttpClientResponse r = http_perform(req);
    res["ok"] = Value::make_bool(r.ok && r.status_code >= 200 && r.status_code < 400);
    res["status"] = Value::make_int(r.status_code);
    if (!r.status_text.empty()) res["statusText"] = Value::make_str(r.status_text);
    res["finalUrl"] = Value::make_str(r.final_url);
    if (!r.content_type.empty()) res["contentType"] = Value::make_str(r.content_type);
    if (!r.ok) {
        res["error"] = Value::make_str(r.error);
        return res;
    }
    res["body"] = Value::make_str(ensure_utf8_and_truncate(r.body, 8000, r.content_type));
    res["truncated"] = Value::make_bool(r.truncated || r.body.size() > 8000);
    return res;
}

} // namespace openstation
