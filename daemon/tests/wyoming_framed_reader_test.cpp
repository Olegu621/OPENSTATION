#include "../src/wyoming/framed_reader.hpp"

#include <atomic>
#include <cassert>
#include <chrono>
#include <cstring>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

int main() {
    using namespace std::chrono_literals;
    std::atomic<bool> running{true};
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    openstation::wyoming::FramedReader reader(sockets[0], running, 160ms);

    // Idle between events is valid: even a pause longer than the frame budget
    // must not disconnect an otherwise healthy satellite.
    std::string line;
    bool line_ok = false;
    std::thread idle([&] { line_ok = reader.read_line(line); });
    std::this_thread::sleep_for(240ms);
    const char frame[] = "{\"type\":\"describe\"}\r\n";
    assert(send(sockets[1], frame, sizeof(frame) - 1, MSG_NOSIGNAL) == sizeof(frame) - 1);
    idle.join();
    assert(line_ok && line == "{\"type\":\"describe\"}");

    // A peer that starts a frame cannot keep it alive by trickling bytes.
    bool trickle_ok = true;
    std::thread trickle([&] { trickle_ok = reader.read_line(line); });
    for (int i = 0; i < 6; ++i) {
        assert(send(sockets[1], "a", 1, MSG_NOSIGNAL) == 1);
        std::this_thread::sleep_for(50ms);
    }
    trickle.join();
    assert(!trickle_ok);
    close(sockets[0]);
    close(sockets[1]);

    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    openstation::wyoming::FramedReader body_reader(sockets[0], running, 160ms);
    uint8_t body[4]{};
    assert(send(sockets[1], "abcd", 4, MSG_NOSIGNAL) == 4);
    assert(body_reader.read_exact(body, sizeof(body)));
    assert(std::memcmp(body, "abcd", sizeof(body)) == 0);
    assert(send(sockets[1], "a", 1, MSG_NOSIGNAL) == 1);
    assert(!body_reader.read_exact(body, sizeof(body)));
    close(sockets[0]);
    close(sockets[1]);
}
