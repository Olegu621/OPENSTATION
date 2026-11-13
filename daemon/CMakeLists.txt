cmake_minimum_required(VERSION 3.18)
project(openstationd LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Static MbedTLS 3.6.7 for TLS/WSS Gemini Live client
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/third_party/mbedtls" "${CMAKE_CURRENT_BINARY_DIR}/mbedtls" EXCLUDE_FROM_ALL)

# Generate embedded Web UI C++ header from daemon/web_ui/
set(WEB_UI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/web_ui")
set(WEB_ASSETS_HDR "${CMAKE_CURRENT_BINARY_DIR}/generated/web_assets.hpp")

find_package(Python3 COMPONENTS Interpreter REQUIRED)
add_custom_command(
    OUTPUT "${WEB_ASSETS_HDR}"
    COMMAND ${Python3_EXECUTABLE} "${CMAKE_CURRENT_SOURCE_DIR}/tools/embed_web.py"
            "${WEB_UI_DIR}" "${WEB_ASSETS_HDR}"
    DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/tools/embed_web.py"
        "${WEB_UI_DIR}/index.html"
        "${WEB_UI_DIR}/style.css"
        "${WEB_UI_DIR}/app.js"
    COMMENT "Embedding Web UI assets into web_assets.hpp"
    VERBATIM
)

add_executable(openstationd
    src/main.cpp
    src/config.cpp
    src/hw/alsa_mixer.cpp
    src/hw/framebuffer_hw.cpp
    src/hw/uinput_keys.cpp
    src/hw/input_monitor.cpp
    src/hw/audio_engine.cpp
    src/assistant/tls_ws_client.cpp
    src/assistant/wake_word.cpp
    src/assistant/timer_manager.cpp
    src/assistant/assistant_manager.cpp
    src/wyoming/wyoming_server.cpp
    src/net/adb_manager.cpp
    src/net/mdns_responder.cpp
    src/net/mqtt_client.cpp
    src/net/http_client.cpp
    src/net/web_tools.cpp
    src/ota/ota_manager.cpp
    src/web/http_ws_server.cpp
    "${WEB_ASSETS_HDR}"
)

target_include_directories(openstationd PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/mbedtls/include"
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
)

target_compile_options(openstationd PRIVATE
    -O2
    -Wall
    -Wextra
    -Wno-unused-parameter
    -ffunction-sections
    -fdata-sections
)

target_link_options(openstationd PRIVATE
    -Wl,--gc-sections
)

target_link_libraries(openstationd PRIVATE
    mbedtls
    mbedx509
    mbedcrypto
    dl
    m
)

add_executable(openstation_bootlogod
    bootlogod/bootlogod.cpp
)

set_target_properties(openstation_bootlogod PROPERTIES
    OUTPUT_NAME "bootlogod"
)

target_compile_options(openstation_bootlogod PRIVATE
    -O2
    -Wall
    -Wextra
    -ffunction-sections
    -fdata-sections
)

target_link_options(openstation_bootlogod PRIVATE
    -static
    -Wl,--gc-sections
)

target_link_libraries(openstation_bootlogod PRIVATE
    m
)
