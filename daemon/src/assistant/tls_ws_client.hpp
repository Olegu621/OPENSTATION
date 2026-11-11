#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/ssl.h"
#include "mbedtls/x509_crt.h"

namespace openstation {

struct WsFrame {
    uint8_t opcode = 0; // 0x01 text, 0x02 binary, 0x08 close, 0x09 ping, 0x0A pong
    std::string payload;
    uint16_t close_code = 0;
};

enum class WsRecvStatus {
    Frame,
    Timeout,
    Closed,
    Error
};

// Synchronizes CLOCK_REALTIME via SNTP if current clock is pre-2025.
bool ensure_sntp_time(int timeout_ms = 2000);

class TlsWsClient {
public:
    TlsWsClient() = default;
    ~TlsWsClient();

    TlsWsClient(const TlsWsClient&) = delete;
    TlsWsClient& operator=(const TlsWsClient&) = delete;

    bool connect(const std::string& host,
                 int port,
                 const std::string& path_with_query,
                 const std::string& ca_pem_path,
                 std::string& err_out,
                 int timeout_ms = 8000,
                 const std::string& connect_host_override = "");

    bool send_text(const std::string& text);
    bool send_pong(const std::string& payload);
    void send_close(uint16_t code = 1000);

    WsRecvStatus recv_frame(WsFrame& out, int timeout_ms, std::string& err_out);

    void request_abort();
    void close();
    bool is_connected() const { return connected_.load(); }

private:
    bool tls_write_all(const uint8_t* data, size_t len, int timeout_ms = 5000);
    int tls_read_some(uint8_t* dst, size_t cap, int timeout_ms, int& out_err);
    bool send_ws_frame(uint8_t opcode, const uint8_t* data, size_t len);
    bool try_pop_ws_frame(bool& complete_msg, WsFrame& out, bool& protocol_err);
    void cleanup_tls();

    static int bio_send(void* ctx, const unsigned char* buf, size_t len);
    static int bio_recv(void* ctx, unsigned char* buf, size_t len);

    std::atomic<bool> connected_{false};
    std::atomic<bool> abort_req_{false};
    std::atomic<int> sock_fd_{-1};

    std::mutex send_mu_;
    std::mutex state_mu_;

    mbedtls_ssl_context ssl_{};
    mbedtls_ssl_config conf_{};
    mbedtls_x509_crt cacert_{};
    mbedtls_ctr_drbg_context ctr_drbg_{};
    mbedtls_entropy_context entropy_{};
    bool tls_inited_ = false;

    std::vector<uint8_t> rx_buf_;
    uint8_t frag_opcode_ = 0;
    bool in_fragment_ = false;
    std::string frag_acc_;
};

} // namespace openstation
