#include "wyoming/wyoming_server.hpp"

#include <arpa/inet.h>
#include <cassert>
#include <chrono>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

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
    int peer = socket(AF_INET, SOCK_STREAM, 0);
    assert(peer >= 0);
    int receive_buffer = 1024;
    assert(setsockopt(peer, SOL_SOCKET, SO_RCVBUF, &receive_buffer, sizeof(receive_buffer)) == 0);
    addr.sin_port = htons(static_cast<uint16_t>(port));
    assert(connect(peer, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    for (int i = 0; i < 100 && !server.client_connected(); ++i) std::this_thread::sleep_for(10ms);
    assert(server.client_connected());

    // The peer deliberately never reads. Even with its receive window full,
    // submitting microphone samples must not wait for the network socket.
    int16_t samples[320]{};
    const auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < 20000; ++i) server.feed_audio_16k(samples, 320, 0);
    assert(std::chrono::steady_clock::now() - start < 1500ms);
    server.stop();
    close(peer);
}
