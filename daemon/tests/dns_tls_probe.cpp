// Manual network probe: verifies the real DoH certificate and rejects a wrong name.
#include "../src/net/dns_failover.hpp"
#include <cstdio>
#include <vector>

int main() {
    std::vector<std::string> ips;
    const bool valid = openstation::net::query_doh_json_ipv4(
        "1.1.1.1", "one.one.one.one", "cloudflare-dns.com",
        "/dns-query?name=example.com&type=A", ips, 5000);
    if (!valid) {
        std::fprintf(stderr, "DoH unavailable; certificate rejection not tested\n");
        return 2;
    }
    const bool wrong_name = openstation::net::query_doh_json_ipv4(
        "1.1.1.1", "invalid.openstation.example", "cloudflare-dns.com",
        "/dns-query?name=example.com&type=A", ips, 5000);
    if (wrong_name) {
        std::fprintf(stderr, "Unexpectedly accepted wrong certificate name\n");
        return 1;
    }
    std::puts("DoH certificate verification passed");
    return 0;
}
