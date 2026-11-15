#include "../src/net/dns_failover.hpp"

#include <cassert>
#include <cstring>
#include <string>
#include <vector>

namespace {

using namespace openstation::net;

void test_host_validation_and_normalization() {
    assert(is_valid_dns_host("dns.comss.one"));
    assert(is_valid_dns_host("one.one.one.one"));
    assert(is_valid_dns_host("xbox-dns.ru"));
    assert(!is_valid_dns_host(""));
    assert(!is_valid_dns_host(".dns.comss.one"));
    assert(!is_valid_dns_host("dns.comss.one."));
    assert(!is_valid_dns_host("bad host!"));
    assert(!is_valid_dns_host("-bad.host"));
    assert(!is_valid_dns_host("bad..host"));
    assert(!is_valid_dns_host("bad.-host"));
    assert(!is_valid_dns_host("bad-.host"));
    assert(!is_valid_dns_host(std::string(64, 'a') + ".host"));
    assert(build_dns_a_query("bad..host", 1).empty());

    assert(is_ipv4_literal("1.1.1.1"));
    assert(!is_ipv4_literal("dns.google"));

    const auto norm = normalize_dns_host_list({
        " DNS.Comss.One ",
        "dns.comss.one",
        "bad/host",
        "bad host",
        "router.comss.one",
        "xbox-dns.ru"
    });
    assert(norm.size() == 3);
    assert(norm[0] == "dns.comss.one");
    assert(norm[1] == "router.comss.one");
    assert(norm[2] == "xbox-dns.ru");

    const auto proxies = normalize_proxy_list({" ai-pool.comss.one ", "ai-pool.comss.one", "gemini-pool.comss.one"});
    assert(proxies.size() == 2);
    assert(proxies[0] == "ai-pool.comss.one");
    assert(proxies[1] == "gemini-pool.comss.one");
}

void test_dns_wire_packet_build_and_parse() {
    const uint16_t txid = 0x4242;
    const auto q = build_dns_a_query("generativelanguage.googleapis.com", txid);
    assert(q.size() > 16);
    assert(q[0] == 0x42 && q[1] == 0x42);
    assert(q[5] == 0x01); // QDCOUNT == 1

    // Build a minimal valid compressed DNS response referencing offset 12 (0xC00C)
    std::vector<uint8_t> resp = q;
    resp[2] = 0x81; // QR=1, RD=1
    resp[3] = 0x80; // RA=1, RCODE=0
    resp[7] = 0x02; // ANCOUNT = 2
    // Answer 1: pointer 0xC00C, TYPE=A(1), CLASS=IN(1), TTL=60, RDLEN=4, IP=206.223.242.221
    const uint8_t rr1[] = {0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04, 206, 223, 242, 221};
    // Answer 2: pointer 0xC00C, TYPE=A(1), CLASS=IN(1), TTL=60, RDLEN=4, IP=142.250.74.10
    const uint8_t rr2[] = {0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04, 142, 250, 74, 10};
    resp.insert(resp.end(), rr1, rr1 + sizeof(rr1));
    resp.insert(resp.end(), rr2, rr2 + sizeof(rr2));

    std::vector<std::string> ips;
    int rcode = -1;
    assert(parse_dns_a_response(resp.data(), resp.size(), txid, ips, &rcode));
    assert(rcode == 0);
    assert(ips.size() == 2);
    assert(ips[0] == "206.223.242.221");
    assert(ips[1] == "142.250.74.10");

    // Wrong TXID must be rejected
    std::vector<std::string> bad_ips;
    assert(!parse_dns_a_response(resp.data(), resp.size(), 0x1111, bad_ips, &rcode));

    // SERVFAIL (RCODE=2) must be rejected
    resp[3] = 0x82;
    assert(!parse_dns_a_response(resp.data(), resp.size(), txid, bad_ips, &rcode));
    assert(rcode == 2);
}

void test_candidate_ordering_and_failover() {
    const auto cands = DnsFailoverManager::build_candidate_hosts(
        "router.comss.one",
        {"dns.malw.link", "dns.comss.one", "invalid host"}
    );
    assert(!cands.empty());
    assert(cands[0] == "router.comss.one");
    assert(cands[1] == "dns.malw.link");
    assert(cands[2] == "dns.comss.one");

    DnsFailoverManager mgr;
    std::string applied_mode = "hostname";
    std::string applied_host = "dns.comss.one";
    int apply_count = 0;
    bool comss_ok = true;
    bool router_ok = true;
    bool malw_ok = true;

    mgr.set_hooks(
        [&](const std::string& host, int) {
            DnsProbeResult r;
            r.host = host;
            if (host == "dns.comss.one") r.ok = comss_ok;
            else if (host == "router.comss.one") r.ok = router_ok;
            else if (host == "dns.malw.link") r.ok = malw_ok;
            else r.ok = false;
            r.latency_ms = r.ok ? 24 : -1;
            return r;
        },
        [&](const std::string& mode, const std::string& host) {
            applied_mode = mode;
            applied_host = host;
            ++apply_count;
            return true;
        }
    );

    // Background checks must preserve an explicit Android DNS mode selection.
    for (const auto& mode : {"off", "opportunistic", ""}) {
        auto untouched = mgr.evaluate_and_switch(mode, "", {}, true, false, 0);
        assert(untouched.ok && !untouched.switched);
        assert(untouched.reason == "private_dns_not_strict");
        assert(apply_count == 0);
    }

    // 1. Healthy active DNS stays active with zero switches
    auto out1 = mgr.evaluate_and_switch(applied_mode, applied_host, {"router.comss.one", "dns.malw.link"}, true, false, 60);
    assert(out1.ok && out1.healthy && !out1.switched);
    assert(applied_host == "dns.comss.one");
    assert(apply_count == 0);

    // 2. Cooldown blocks immediate second non-forced check; forced switch jumps to router.comss.one when comss is down
    comss_ok = false;
    auto out_cd = mgr.evaluate_and_switch(applied_mode, applied_host, {"router.comss.one", "dns.malw.link"}, true, false, 60);
    assert(out_cd.reason == "cooldown");

    auto out2 = mgr.evaluate_and_switch(applied_mode, applied_host, {"router.comss.one", "dns.malw.link"}, true, false, 0);
    assert(out2.ok && out2.healthy && out2.switched);
    assert(out2.active_host == "router.comss.one");
    assert(applied_host == "router.comss.one");
    assert(apply_count == 1);

    // 3. When all DoT hosts fail, falls back cleanly to opportunistic mode
    comss_ok = false;
    router_ok = false;
    malw_ok = false;
    auto out3 = mgr.evaluate_and_switch(applied_mode, applied_host, {"router.comss.one", "dns.malw.link"}, true, true, 0);
    assert(out3.ok && !out3.healthy && out3.switched);
    assert(applied_mode == "opportunistic");
    assert(applied_host.empty());
}

} // namespace

int main() {
    test_host_validation_and_normalization();
    test_dns_wire_packet_build_and_parse();
    test_candidate_ordering_and_failover();
    return 0;
}
