#pragma once

namespace openstation {

/**
 * Selects the Amlogic TDMIN loopback source matching the active output bus and
 * board hardware revision:
 *   - HDMI out always routes AEC loopback from TDMOUT_C
 *   - Speaker/headphone on revision >= 5 (YNDX-00053, mixer_paths_tv_5.xml) uses TDMOUT_B
 *   - Speaker/headphone on revisions <= 4 uses TDMOUT_A
 */
inline const char* loopback_source_for_revision(bool hdmi_on, int hw_revision) {
    if (hdmi_on) return "TDMOUT_C";
    if (hw_revision >= 5) return "TDMOUT_B";
    return "TDMOUT_A";
}

} // namespace openstation
