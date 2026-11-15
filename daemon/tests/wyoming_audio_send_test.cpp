#include "wyoming/wyoming_server.hpp"

#include <arpa/inet.h>
#include <cassert>
#include <chrono>
#include <cstring>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>
#include <vector>

static std::string read_exact(int fd, size_t count) {
    std::string out(count, '\0');
    size_t offset = 0;
    while (offset < count) {
        ssize_t n = recv(fd, &out[offset], count - offset, 0);
        assert(n > 0);
        offset += static_cast<size_t>(n);
    }
    return out;
}

int main() {
    using namespace std::chrono_literals;
    int probe = socket(AF_INET, SOCK_STREAM, 0);
    assert(probe >= 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    assert(bind(probe, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    socklen_t len = sizeof(addr);
    assert(getsockname(probe, reinterpret_cast<sockaddr*>(&addr), &len) == 0);
    const int port = ntohs(addr.sin_port);
    close(probe);

    openstation::WyomingConfig config;
    config.enabled = true;
    config.always_stream = true;
    config.port = port;
    openstation::WyomingServer server;
    assert(server.start(config, {}, {}));
    const int peer = socket(AF_INET, SOCK_STREAM, 0);
    assert(peer >= 0);
    timeval timeout{3, 0};
    assert(setsockopt(peer, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
    addr.sin_port = htons(static_cast<uint16_t>(port));
    assert(connect(peer, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    for (int i = 0; i < 100 && !server.client_connected(); ++i) std::this_thread::sleep_for(10ms);
    assert(server.client_connected());
    int16_t samples[320];
    for (int i = 0; i < 320; ++i) samples[i] = static_cast<int16_t>(i - 160);
    server.feed_audio_16k(samples, 320, 0);

    for (const char* expected : {"run-pipeline", "audio-start", "audio-chunk"}) {
        std::string line;
        while (true) {
            std::string c = read_exact(peer, 1);
            if (c[0] == '\n') break;
            line += c;
        }
        openstation::json::Value header;
        assert(openstation::json::Parser::parse(line, header));
        assert(header.get("type") && header.get("type")->as_str() == expected);
        const auto* data = header.get("data_length");
        const auto* payload = header.get("payload_length");
        if (data) (void)read_exact(peer, static_cast<size_t>(data->as_i64()));
        if (std::strcmp(expected, "audio-chunk") == 0) {
            assert(payload && payload->as_i64() == sizeof(samples));
            const std::string audio = read_exact(peer, sizeof(samples));
            assert(std::memcmp(audio.data(), samples, sizeof(samples)) == 0);
        } else {
            assert(!payload);
        }
    }
    close(peer);
    server.stop();

    // Repeated immediate shutdown exercises the queue worker's wake-up path.
    for (int i = 0; i < 20; ++i) {
        assert(server.start(config, {}, {}));
        server.stop();
    }
}
