#!/usr/bin/env bash
# Regenerates the "Liquid Glass" theme (the upstream author's UI) from an upstream tag.
# Usage: launcher/tools/sync_glass_theme.sh [tag]   (default v1.5.0)
# Upstream files are copied verbatim into package com.openstation.launcher.glass; only the
# package line, two imports and the names of drawables the Ambient theme restyled are changed.
set -euo pipefail
TAG="${1:-v1.5.0}"
cd "$(git rev-parse --show-toplevel)/launcher/app/src/main"
SRC=java/com/openstation/launcher
DST=$SRC/glass
mkdir -p "$DST"
FILES="MainActivity HomeEqWidgetController AliceOverlayManager VolumeOverlayManager MarketActivity
SetupWizardActivity YandexMusicActivity LiquidGlassKit market/MarketUiKit"
DRAWABLES="bg_card_bork_hero bg_card_duo_widget bg_card_hero bg_pill bg_pill_gold bg_progress_gold bg_tile"
dsed=""
for d in $DRAWABLES; do
  git show "$TAG:launcher/app/src/main/res/drawable/$d.xml" > "res/drawable/glass_$d.xml"
  dsed="$dsed -e s/R\.drawable\.$d\b/R.drawable.glass_$d/g -e s#@drawable/$d\b#@drawable/glass_$d#g"
done
for d in $DRAWABLES; do sed -i $dsed "res/drawable/glass_$d.xml"; done
for f in $FILES; do
  out="$DST/$(basename "$f").kt"
  git show "$TAG:launcher/app/src/main/$SRC/$f.kt" \
    | sed -e '/^import com.openstation.launcher.R$/d' -e '1s/^package .*/package com.openstation.launcher.glass\n\nimport com.openstation.launcher.*\nimport com.openstation.launcher.R/' \
          -e 's/com\.openstation\.launcher\.market\.MarketUiKit/com.openstation.launcher.glass.MarketUiKit/g' \
          $dsed > "$out"
done
# The one non-verbatim change: a "back to Ambient" tile in the station settings dock.
sed -i '/SysItem(R.drawable.ic_settings, false, "Настройки TV")/i\            SysItem(R.drawable.ic_hw_display, false, "Тема: Ambient") { Theme.switchTo(this, glass = false) },' "$DST/MainActivity.kt"
# Station fixes: com.android.tv.settings has no launcher entry; APPLICATION_SETTINGS opens a chooser.
sed -i -e 's/?: packageManager\.getLaunchIntentForPackage(pkg)/?: packageManager.appLaunchIntent(pkg)/'        -e 's/Settings\.ACTION_APPLICATION_SETTINGS/Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS/g' "$DST/MainActivity.kt"
# Sprites the Ambient theme no longer ships (glass-only, same names).
for s in bg_tv_stage.webp sprite_bork_dial_ring.png sprite_station_hero.png sprite_sys_bt.png \
         sprite_sys_display.png sprite_sys_eq.png sprite_sys_wifi.png; do
  git show "$TAG:launcher/app/src/main/res/drawable-nodpi/$s" > "res/drawable-nodpi/$s"
done
echo "glass theme synced from $TAG"
