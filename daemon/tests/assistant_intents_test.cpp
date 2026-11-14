#include "assistant/assistant_intents.hpp"
#include <cassert>

int main() {
    using openstation::is_assistant_stop_request;
    assert(is_assistant_stop_request("выключись"));
    assert(is_assistant_stop_request("алиса, выключись!"));
    assert(is_assistant_stop_request("алиса, пожалуйста выключись."));
    assert(!is_assistant_stop_request("выключи телевизор"));
    assert(!is_assistant_stop_request("телевизор выключись"));
    assert(!is_assistant_stop_request("не выключись"));
    assert(!is_assistant_stop_request("выключи музыку"));
    assert(openstation::block_power_tool_for_stop(true, "tv_hdmi_cec"));
    assert(openstation::block_power_tool_for_stop(true, "device_power"));
    assert(openstation::block_power_tool_for_stop(true, "remote_control"));
    assert(!openstation::block_power_tool_for_stop(false, "tv_hdmi_cec"));
    assert(!openstation::block_power_tool_for_stop(true, "stop_assistant"));
}
