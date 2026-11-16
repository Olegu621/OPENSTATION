# FocusHelper

A tiny `app_process` helper that holds Android audio focus with
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` while the assistant is active.

Ducking the amplifier is not an option on the Station Max: music, Bluetooth and
the assistant TTS all go through the same SY6045S output, so lowering it also
lowers the voice (and compensating the TTS digitally causes clipping). Instead
this helper makes other players duck **their own** output:

- the Bluetooth A2DP sink lowers its playback track by about 12 dB;
- Yandex Music lowers its player volume through its focus listener;
- the amplifier and the assistant TTS stay at full volume.

The daemon writes `/data/local/openstation/assistant_duck` (`1`/`0`) for the
listening/thinking/speaking states and starts this helper automatically.

## Rebuilding the embedded dex

`daemon/src/assistant/focus_helper_dex.hpp` contains the dex generated from this
source. To regenerate it you need JDK 17, Android SDK platform 28 (`android.jar`)
and `d8` (Android build-tools or the R8 jar):

```bash
javac -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_HOME/platforms/android-28/android.jar" \
    -d classes FocusHelper.java

java -cp r8.jar com.android.tools.r8.D8 \
    --lib "$ANDROID_HOME/platforms/android-28/android.jar" \
    --min-api 28 --output dex classes/*.class

python3 - <<'PY'
from pathlib import Path
data = Path('dex/classes.dex').read_bytes()
lines = []
for i in range(0, len(data), 16):
    lines.append('    ' + ', '.join(f'0x{b:02x}' for b in data[i:i+16]) + ',')
Path('focus_helper_dex.hpp').write_text(
    '#pragma once\n'
    '// Generated from daemon/tools/focus_helper/FocusHelper.java.\n'
    '// Rebuild instructions are in daemon/tools/focus_helper/README.md.\n'
    '#include <cstddef>\n\n'
    'inline constexpr unsigned char kFocusHelperDex[] = {\n'
    + '\n'.join(lines) + '\n'
    '};\n'
    'inline constexpr size_t kFocusHelperDexSize = sizeof(kFocusHelperDex);\n')
PY
```

Copy the generated header to `daemon/src/assistant/focus_helper_dex.hpp`.
