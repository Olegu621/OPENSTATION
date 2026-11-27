#include "hw/alsa_mixer_policy.hpp"

#include <cassert>
#include <cstring>

int main() {
    using openstation::loopback_source_for_revision;

    // Early and v3/v4 boards (rev 0..4) use TDMOUT_A for speaker/headphone AEC reference.
    assert(std::strcmp(loopback_source_for_revision(false, 0), "TDMOUT_A") == 0);
    assert(std::strcmp(loopback_source_for_revision(false, 2), "TDMOUT_A") == 0);
    assert(std::strcmp(loopback_source_for_revision(false, 4), "TDMOUT_A") == 0);

    // YNDX-00053 (rev 5+) uses TDMOUT_B in mixer_paths_tv_5.xml.
    assert(std::strcmp(loopback_source_for_revision(false, 5), "TDMOUT_B") == 0);
    assert(std::strcmp(loopback_source_for_revision(false, 6), "TDMOUT_B") == 0);

    // Active HDMI output overrides loopback source to TDMOUT_C across all revisions.
    assert(std::strcmp(loopback_source_for_revision(true, 2), "TDMOUT_C") == 0);
    assert(std::strcmp(loopback_source_for_revision(true, 4), "TDMOUT_C") == 0);
    assert(std::strcmp(loopback_source_for_revision(true, 5), "TDMOUT_C") == 0);
    return 0;
}
