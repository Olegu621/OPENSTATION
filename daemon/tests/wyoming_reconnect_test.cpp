#include "wyoming/wyoming_server.hpp"

#include <arpa/inet.h>
#include <cassert>
#include <chrono>
#include <cstring>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

static int connect_local(int port) {
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    assert(fd >= 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(static_cast<uint16_t>(port));
    assert(connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    return fd;
}

int main() {
    using namespace std::chrono_literals;
    const int temporary = socket(AF_INET, SOCK_STREAM, 0);
    assert(temporary >= 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    assert(bind(temporary, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    socklen_t len = sizeof(addr);
    assert(getsockname(temporary, reinterpret_cast<sockaddr*>(&addr), &len) == 0);
    const int port = ntohs(addr.sin_port);
    close(temporary);

    openstation::WyomingConfig config;
    config.enabled = true;
    config.port = port;
    openstation::WyomingServer server;
    assert(server.start(config, {}, {}));
    const int stalled = connect_local(port);
    const char incomplete[] = "{\"type\":";
    assert(send(stalled, incomplete, sizeof(incomplete) - 1, MSG_NOSIGNAL) == sizeof(incomplete) - 1);
    std::this_thread::sleep_for(11s);

    const int healthy = connect_local(port);
    timeval timeout{4, 0};
    assert(setsockopt(healthy, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
    const char request[] = "{\"type\":\"describe\"}\n";
    assert(send(healthy, request, sizeof(request) - 1, MSG_NOSIGNAL) == sizeof(request) - 1);
    char response[2048]{};
    const ssize_t n = recv(healthy, response, sizeof(response) - 1, 0);
    assert(n > 0);
    assert(std::string(response, static_cast<size_t>(n)).find("\"type\":\"info\"") != std::string::npos);
    close(healthy);
    close(stalled);
    server.stop();
}
