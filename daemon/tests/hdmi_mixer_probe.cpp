// Manual device probe: briefly changes mixer switches, then restores them.
// Run only while playback is stopped; this is not a host/unit test.
#include "../src/hw/alsa_mixer.hpp"
#include <cstdio>
#include <string>

int main() {
    openstation::AlsaMixer mixer;
    if (!mixer.init(0)) return 2;
    bool woo_stby, twe_stby, woo_on, twe_on, hp_on, hdmi_mute = false;
    std::string loopback;
    if (!mixer.get_bool("Woofer STBY Switch", woo_stby) ||
        !mixer.get_bool("Tweeters STBY Switch", twe_stby) ||
        !mixer.get_bool("Woofer Master Playback Switch", woo_on) ||
        !mixer.get_bool("Tweeters Master Playback Switch", twe_on) ||
        !mixer.get_bool("Headphone Headphone Switch", hp_on) ||
        !mixer.get_enum("Loopback tmdin lb source", loopback)) return 3;
    const bool has_hdmi_mute = mixer.get_bool("Audio hdmi-out mute", hdmi_mute);

    mixer.apply_output_route("hdmi", false, false);
    bool wst = false, tst = false, ws = true, ts = true, hs = true;
    std::string reference;
    const bool verified = mixer.get_bool("Woofer STBY Switch", wst) && wst &&
        mixer.get_bool("Tweeters STBY Switch", tst) && tst &&
        mixer.get_bool("Woofer Master Playback Switch", ws) && !ws &&
        mixer.get_bool("Tweeters Master Playback Switch", ts) && !ts &&
        mixer.get_bool("Headphone Headphone Switch", hs) && !hs &&
        mixer.get_enum("Loopback tmdin lb source", reference) && reference == "TDMOUT_C";

    bool restored = true;
    restored = mixer.set_enum("Loopback tmdin lb source", loopback) && restored;
    if (has_hdmi_mute) restored = mixer.set_bool("Audio hdmi-out mute", hdmi_mute) && restored;
    restored = mixer.set_bool("Woofer STBY Switch", woo_stby) && restored;
    restored = mixer.set_bool("Tweeters STBY Switch", twe_stby) && restored;
    restored = mixer.set_bool("Woofer Master Playback Switch", woo_on) && restored;
    restored = mixer.set_bool("Tweeters Master Playback Switch", twe_on) && restored;
    restored = mixer.set_bool("Headphone Headphone Switch", hp_on) && restored;
    std::printf("HDMI mixer: %s; restore: %s\n", verified ? "OK" : "FAIL", restored ? "OK" : "FAIL");
    return verified && restored ? 0 : 1;
}
