#!/vendor/bin/sh
# OpenStation Max: Auto-detect physical Wi-Fi/BT chip and repair mismatched misc partition.
# Verified targets:
#   - Boards 0008/00052 (Intel 7265): misc.img (revision=4, g12a_s905x2_rev35f)
#   - Boards 00053 (Realtek 8822 / Unisoc): misc_00053.img (revision=5, g12a_s905x2_rev375f)

set -u

MODE="apply"
AUTO_REBOOT=0
FORCE=0
EXPLICIT_TARGET=""

for arg in "$@"; do
  case "$arg" in
    --check) MODE="check" ;;
    --apply) MODE="apply" ;;
    --auto-reboot) MODE="apply"; AUTO_REBOOT=1 ;;
    --force) FORCE=1 ;;
    --target=*) EXPLICIT_TARGET="${arg#--target=}" ;;
    --rev=*)
      r="${arg#--rev=}"
      case "$r" in
        0008|00052|0008_00052|intel|rev35f) EXPLICIT_TARGET="intel_rev35f" ;;
        00053|realtek|unisoc|rev375f) EXPLICIT_TARGET="00053_rev375f" ;;
        *)
          echo "ERROR: unknown revision: $r" >&2
          exit 2
          ;;
      esac
      ;;
    -h|--help)
      echo "Usage: $0 [--check|--apply|--auto-reboot] [--force] [--rev=0008|00052|00053] [--target=intel_rev35f|00053_rev375f]"
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

B64_INTEL_HDR='dWJvb3QtY29tbWFuZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHNldGVudiBzaWxlbnQgMDtzZXRlbnYgbG9jayAxMDAwMDAwMDtzZXRlbnYgYXZiMiAwO3NhdmVlbnY7c2V0ZW52IGJvb3RhcmdzICJpbml0PS9pbml0IHJvb3Q9L2Rldi9tbWNibGswcDE4IHJvb3Rmc3R5cGU9ZXh0NCBybyByb290d2FpdCBjb25zb2xlPXR0eVMwLDExNTIwMCBlYXJseXByaW50az1hbWwtdWFydCwweGZmODAzMDAwIGxvZ289b3NkMCxsb2FkZWQsMHgzZDgwMDAwMCB2b3V0PTEwODBwNjBoeixlbmFibGUgcGFuZWxfdHlwZT1sY2RfMSBoZG1pdHg9LDQ0NCw4Yml0IGhkbWltb2RlPTEwODBwNjBoeiBmcmFjX3JhdGVfcG9saWN5PTEgY3Zic21vZGU9NTc2Y3ZicyBvc2RfcmV2ZXJzZT0wIHZpZGVvX3JldmVyc2U9MCBhbmRyb2lkYm9vdC5zZWxpbnV4PXBlcm1pc3NpdmUgYW5kcm9pZGJvb3QudmVyaWZpZWRib290c3RhdGU9b3JhbmdlIGFuZHJvaWRib290LnZibWV0YS5za2lwPTEgYW5kcm9pZGJvb3Quc2VyaWFsbm89WDEwUEJCMDAwNVJNMUsgYW5kcm9pZGJvb3QuZGV2aWNlaWQ9WDEwUEJCMDAwNVJNMUsgYW5kcm9pZGJvb3QubG9jYWxpemF0aW9uPVJVLlJVIGFuZHJvaWRib290LmhhcmR3YXJlPWFtbG9naWMgYW5kcm9pZGJvb3QuaGFyZHdhcmUucmV2aXNpb249NCBhbmRyb2lkYm9vdC5oYXJkd2FyZS5hbWxvZ2ljX2R0X2lkPWcxMmFfczkwNXgyX3JldjM1ZiI7aW1ncmVhZCBrZXJuZWwgJHtib290X3BhcnR9ICR7bG9hZGFkZHJ9O2Jvb3RtICR7bG9hZGFkZHJ9OwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEFCMAEAAAAPBwAADgcAAAAAAAAAAAAAAAAAAHnx5b8='
SHA_INTEL_1M='e40d02c23d4ad023028f2bfe1ee6049165905708a5ef6ee8810d68e66f169b10'

B64_00053_HDR='dWJvb3QtY29tbWFuZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHNldGVudiBzaWxlbnQgMDtzZXRlbnYgbG9jayAxMDAwMDAwMDtzZXRlbnYgYXZiMiAwO3NhdmVlbnY7c2V0ZW52IGJvb3RhcmdzICJpbml0PS9pbml0IHJvb3Q9L2Rldi9tbWNibGswcDE4IHJvb3Rmc3R5cGU9ZXh0NCBybyByb290d2FpdCBjb25zb2xlPXR0eVMwLDExNTIwMCBlYXJseXByaW50az1hbWwtdWFydCwweGZmODAzMDAwIGxvZ289b3NkMCxsb2FkZWQsMHgzZDgwMDAwMCB2b3V0PTEwODBwNjBoeixlbmFibGUgcGFuZWxfdHlwZT1sY2RfMSBoZG1pdHg9LDQ0NCw4Yml0IGhkbWltb2RlPTEwODBwNjBoeiBmcmFjX3JhdGVfcG9saWN5PTEgY3Zic21vZGU9NTc2Y3ZicyBvc2RfcmV2ZXJzZT0wIHZpZGVvX3JldmVyc2U9MCBhbmRyb2lkYm9vdC5zZWxpbnV4PXBlcm1pc3NpdmUgYW5kcm9pZGJvb3QudmVyaWZpZWRib290c3RhdGU9b3JhbmdlIGFuZHJvaWRib290LnZibWV0YS5za2lwPTEgYW5kcm9pZGJvb3Quc2VyaWFsbm89WDEwUEJCMDAwNVJNMUsgYW5kcm9pZGJvb3QuZGV2aWNlaWQ9WDEwUEJCMDAwNVJNMUsgYW5kcm9pZGJvb3QubG9jYWxpemF0aW9uPVJVLlJVIGFuZHJvaWRib290LmhhcmR3YXJlPWFtbG9naWMgYW5kcm9pZGJvb3QuaGFyZHdhcmUucmV2aXNpb249NSBhbmRyb2lkYm9vdC5oYXJkd2FyZS5hbWxvZ2ljX2R0X2lkPWcxMmFfczkwNXgyX3JldjM3NWYiO2ltZ3JlYWQga2VybmVsICR7Ym9vdF9wYXJ0fSAke2xvYWRhZGRyfTtib290bSAke2xvYWRhZGRyfTsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEFCMAEAAAAPBwAADgcAAAAAAAAAAAAAAAAAAHnx5b8='
SHA_00053_1M='800cfdf8f9da8800d200a9a783951175564d43b0a98c1680ec2856219f27d1db'

OS_DATA="/data/local/openstation"
BACKUP_PATH="$OS_DATA/misc_backup.img"
GUARD_PATH="$OS_DATA/.misc_autofix_state"
LOCK_DIR="$OS_DATA/.misc_autofix.lock"

w_data=0
while [ $w_data -lt 20 ] && [ ! -d /data/local ]; do
  w_data=$((w_data + 1))
  sleep 1
done

mkdir -p "$OS_DATA" 2>/dev/null || true

TMP_DIR="/data/local/tmp/.misc_fix_$$"
if [ -d "$OS_DATA" ]; then
  TMP_DIR="$OS_DATA/.misc_fix_$$"
fi
LOCKED=0

cleanup() {
  rm -rf "$TMP_DIR" 2>/dev/null || true
  if [ "$LOCKED" -eq 1 ]; then
    rmdir "$LOCK_DIR" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

acquire_lock() {
  i=0
  while [ $i -lt 15 ]; do
    if mkdir "$LOCK_DIR" 2>/dev/null; then
      LOCKED=1
      return 0
    fi
    i=$((i + 1))
    sleep 0.1
  done
  return 0
}

lower_hex() {
  printf '%s' "$1" | tr -d '\r\n ' | tr 'A-F' 'a-f' | sed 's/^0x//'
}

scan_buses_once() {
  CHIP="unknown"

  for sdev in /sys/bus/sdio/devices/*; do
    [ -r "$sdev/vendor" ] && [ -r "$sdev/device" ] || continue
    sv=$(lower_hex "$(cat "$sdev/vendor" 2>/dev/null)")
    sd=$(lower_hex "$(cat "$sdev/device" 2>/dev/null)")
    if [ "$sv" = "024c" ] && [ "$sd" = "a822" ]; then
      CHIP="realtek"
      return 0
    fi
    if [ "$sv" = "0000" ] && [ "$sd" = "0000" ]; then
      CHIP="unisoc"
      return 0
    fi
  done

  for pdev in /sys/bus/pci/devices/*; do
    [ -r "$pdev/vendor" ] || continue
    pv=$(lower_hex "$(cat "$pdev/vendor" 2>/dev/null)")
    if [ "$pv" = "8086" ]; then
      CHIP="intel"
      return 0
    fi
  done

  for udev in /sys/bus/usb/devices/*; do
    [ -r "$udev/idVendor" ] && [ -r "$udev/idProduct" ] || continue
    uv=$(lower_hex "$(cat "$udev/idVendor" 2>/dev/null)")
    up=$(lower_hex "$(cat "$udev/idProduct" 2>/dev/null)")
    if [ "$uv" = "8087" ] && [ "$up" = "0a2a" ]; then
      CHIP="intel"
      return 0
    fi
  done

  return 1
}

detect_chip() {
  scan_buses_once && return 0
  if [ -x /vendor/bin/wifi_power ]; then
    /vendor/bin/wifi_power 1 >/dev/null 2>&1 || true
    k=0
    while [ $k -lt 6 ]; do
      sleep 0.2
      scan_buses_once && return 0
      k=$((k + 1))
    done
  fi
  return 1
}

read_dt_id() {
  dt=""
  if [ -r /proc/cmdline ]; then
    for tok in $(cat /proc/cmdline 2>/dev/null); do
      case "$tok" in
        androidboot.hardware.amlogic_dt_id=*)
          dt="${tok#androidboot.hardware.amlogic_dt_id=}"
          break
          ;;
      esac
    done
  fi
  if [ -z "$dt" ]; then
    dt=$(getprop ro.boot.hardware.amlogic_dt_id 2>/dev/null | tr -d '\r\n ')
  fi
  printf '%s' "$dt"
}

dt_matches_target() {
  tgt="$1"
  dt="$2"
  [ -n "$dt" ] || return 0
  case "$dt" in
    *rev375f*|*rev376f*)
      [ "$tgt" = "00053_rev375f" ] && return 0 || return 1
      ;;
    *rev35f*|*rev3f*|*rev3*|*dvt*|*pvt*)
      [ "$tgt" = "intel_rev35f" ] && return 0 || return 1
      ;;
  esac
  return 0
}

resolve_misc_dev() {
  if [ -e /dev/block/platform/ffe07000.emmc/by-name/misc ]; then
    printf '%s' "/dev/block/platform/ffe07000.emmc/by-name/misc"
    return 0
  fi
  if [ -e /dev/block/misc ]; then
    printf '%s' "/dev/block/misc"
    return 0
  fi
  return 1
}

acquire_lock

CHIP="unknown"
detect_chip || true

TARGET="none"
TARGET_DT=""
TARGET_B64=""
TARGET_SHA=""
if [ -n "$EXPLICIT_TARGET" ]; then
  case "$EXPLICIT_TARGET" in
    intel_rev35f|intel|0008|00052|0008_00052)
      TARGET="intel_rev35f"
      TARGET_DT="g12a_s905x2_rev35f"
      TARGET_B64="$B64_INTEL_HDR"
      TARGET_SHA="$SHA_INTEL_1M"
      ;;
    00053_rev375f|00053|realtek|unisoc)
      TARGET="00053_rev375f"
      TARGET_DT="g12a_s905x2_rev375f"
      TARGET_B64="$B64_00053_HDR"
      TARGET_SHA="$SHA_00053_1M"
      ;;
    *)
      echo "ERROR: unsupported target: $EXPLICIT_TARGET" >&2
      exit 2
      ;;
  esac
else
  case "$CHIP" in
    intel)
      TARGET="intel_rev35f"
      TARGET_DT="g12a_s905x2_rev35f"
      TARGET_B64="$B64_INTEL_HDR"
      TARGET_SHA="$SHA_INTEL_1M"
      ;;
    realtek|unisoc)
      TARGET="00053_rev375f"
      TARGET_DT="g12a_s905x2_rev375f"
      TARGET_B64="$B64_00053_HDR"
      TARGET_SHA="$SHA_00053_1M"
      ;;
  esac
fi

CUR_DT=$(read_dt_id)
WIFI_OK=0
[ -d /sys/class/net/wlan0 ] && WIFI_OK=1

DT_MATCH=1
if [ "$TARGET" != "none" ] && ! dt_matches_target "$TARGET" "$CUR_DT"; then
  DT_MATCH=0
fi

BT_HAL_RTL=$(getprop init.svc.vendor.bluetooth-1-0 2>/dev/null | tr -d '\r\n')
BT_HAL_LIN=$(getprop init.svc.btlinux-1.0 2>/dev/null | tr -d '\r\n')

BT_OK=0
for h in /sys/class/bluetooth/hci*; do
  if [ -e "$h" ]; then
    BT_OK=1
    break
  fi
done
case "$CHIP" in
  intel)
    [ "$BT_HAL_RTL" = "running" ] && BT_OK=0
    ;;
  unisoc)
    [ -e /dev/ttyBT0 ] && BT_OK=1
    [ "$BT_HAL_LIN" = "running" ] && BT_OK=0
    ;;
  realtek)
    if [ "$DT_MATCH" -eq 1 ] && [ -e /dev/ttyS1 ]; then
      BT_OK=1
    fi
    [ "$BT_HAL_LIN" = "running" ] && BT_OK=0
    ;;
esac

MISC_DEV=$(resolve_misc_dev || true)

if [ "$TARGET" = "none" ]; then
  if [ "$MODE" = "check" ] && { [ "$WIFI_OK" -eq 0 ] || [ "$BT_OK" -eq 0 ]; }; then
    echo "RESULT=NEEDS_REPAIR ACTION=SELECT_REVISION REASON=WIRELESS_DOWN_UNKNOWN_CHIP CHIP=$CHIP DT_ID=$CUR_DT WIFI_OK=$WIFI_OK BT_OK=$BT_OK"
    exit 0
  fi
  echo "RESULT=NO_OP ACTION=NO_OP REASON=UNKNOWN_CHIP CHIP=$CHIP DT_ID=$CUR_DT WIFI_OK=$WIFI_OK BT_OK=$BT_OK"
  exit 0
fi

if [ "$FORCE" -eq 0 ] && [ "$WIFI_OK" -eq 1 ] && [ "$BT_OK" -eq 1 ] && [ "$DT_MATCH" -eq 1 ]; then
  [ "$MODE" = "check" ] || rm -f "$GUARD_PATH" 2>/dev/null || true
  echo "RESULT=NO_OP ACTION=NO_OP REASON=HEALTHY CHIP=$CHIP TARGET=$TARGET DT_ID=$CUR_DT WIFI_OK=1 BT_OK=1"
  exit 0
fi

[ -n "$MISC_DEV" ] || {
  echo "RESULT=FAILED REASON=NO_MISC_BLOCK CHIP=$CHIP TARGET=$TARGET DT_ID=$CUR_DT" >&2
  exit 1
}

mkdir -p "$TMP_DIR" || exit 1
HDR_BIN="$TMP_DIR/hdr.bin"
TARGET_IMG="$TMP_DIR/target.img"
CUR_IMG="$TMP_DIR/current.img"
VERIFY_IMG="$TMP_DIR/verify.img"

printf '%s' "$TARGET_B64" | base64 -d > "$HDR_BIN" || exit 1
(cat "$HDR_BIN"; dd if=/dev/zero bs=4096 count=256 2>/dev/null) | head -c 1048576 > "$TARGET_IMG" || exit 1
GOT_SHA=$(sha256sum "$TARGET_IMG" | cut -d' ' -f1)
if [ "$GOT_SHA" != "$TARGET_SHA" ]; then
  echo "RESULT=FAILED REASON=SHA_MISMATCH GOT=$GOT_SHA EXPECTED=$TARGET_SHA" >&2
  exit 1
fi

dd if="$MISC_DEV" of="$CUR_IMG" bs=4096 count=256 >/dev/null 2>&1 || {
  echo "RESULT=FAILED REASON=READ_MISC_FAILED DEV=$MISC_DEV" >&2
  exit 1
}

if cmp -s "$CUR_IMG" "$TARGET_IMG"; then
  echo "RESULT=NO_OP ACTION=ALREADY_MATCHES CHIP=$CHIP TARGET=$TARGET DT_ID=$CUR_DT WIFI_OK=$WIFI_OK BT_OK=$BT_OK DEV=$MISC_DEV"
  exit 0
fi

if [ "$MODE" = "check" ]; then
  echo "RESULT=NEEDS_REPAIR ACTION=WOULD_WRITE CHIP=$CHIP TARGET=$TARGET DT_ID=$CUR_DT TARGET_DT=$TARGET_DT DT_MATCH=$DT_MATCH WIFI_OK=$WIFI_OK BT_OK=$BT_OK DEV=$MISC_DEV"
  exit 0
fi

if [ ! -s "$BACKUP_PATH" ]; then
  cp -f "$CUR_IMG" "$BACKUP_PATH.tmp" && chmod 600 "$BACKUP_PATH.tmp" && mv -f "$BACKUP_PATH.tmp" "$BACKUP_PATH"
fi

dd if="$TARGET_IMG" of="$MISC_DEV" bs=4096 count=256 >/dev/null 2>&1 || {
  echo "RESULT=FAILED REASON=WRITE_MISC_FAILED DEV=$MISC_DEV" >&2
  exit 1
}
sync

dd if="$MISC_DEV" of="$VERIFY_IMG" bs=4096 count=256 >/dev/null 2>&1 || {
  echo "RESULT=FAILED REASON=READBACK_FAILED DEV=$MISC_DEV" >&2
  exit 1
}
if ! cmp -s "$VERIFY_IMG" "$TARGET_IMG"; then
  dd if="$CUR_IMG" of="$MISC_DEV" bs=4096 count=256 >/dev/null 2>&1 || true
  sync
  echo "RESULT=FAILED REASON=VERIFY_MISMATCH_ROLLED_BACK DEV=$MISC_DEV" >&2
  exit 1
fi

GUARD_KEY="$CHIP:${CUR_DT:-unknown}->$TARGET_DT"
PREV_GUARD=""
[ -r "$GUARD_PATH" ] && PREV_GUARD=$(cat "$GUARD_PATH" 2>/dev/null | tr -d '\r\n ')

if [ "$AUTO_REBOOT" -eq 1 ]; then
  if [ -n "$PREV_GUARD" ] && [ "$PREV_GUARD" = "$GUARD_KEY" ]; then
    echo "RESULT=UPDATED ACTION=UPDATED_NO_REBOOT REASON=REBOOT_GUARD CHIP=$CHIP TARGET=$TARGET DT_ID=$CUR_DT TARGET_DT=$TARGET_DT"
    exit 0
  fi
  printf '%s\n' "$GUARD_KEY" > "$GUARD_PATH.tmp" && mv -f "$GUARD_PATH.tmp" "$GUARD_PATH"
  sync
  echo "RESULT=UPDATED ACTION=REBOOTING CHIP=$CHIP TARGET=$TARGET DT_ID=$CUR_DT TARGET_DT=$TARGET_DT"
  sleep 1
  /system/bin/reboot >/dev/null 2>&1 || reboot
  exit 0
fi

echo "RESULT=UPDATED ACTION=UPDATED_NO_REBOOT CHIP=$CHIP TARGET=$TARGET DT_ID=$CUR_DT TARGET_DT=$TARGET_DT"
exit 0
