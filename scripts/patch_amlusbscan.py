#!/usr/bin/env python3
"""Patch Amlogic AmlUsbScanX3.dll so one bad USB controller cannot abort the scan.

Background
----------
AmlUsbScanX3.dll (Amlogic, build 1.4.9.1218, 2012) enumerates USB host
controllers and, for each one, asks the driver for:

  * IOCTL_GET_HCD_DRIVERKEY_NAME (0x220424)  -> failure is only logged
  * IOCTL_USB_GET_ROOT_HUB_NAME  (0x220408)  -> failure ABORTS the whole scan

On modern systems (USB4/Thunderbolt host routers, some xHCI stacks) the second
IOCTL returns an error for at least one controller.  The DLL then prints
"根Hub名字失败" / "AmlScanUsbX3Devices:scan usb devices failed" and update.exe
reports "No [WorldCup Device] device after scan" even when the station is
plugged into a perfectly healthy controller.

The patch redirects the fatal failure branch to the existing "close handle and
continue with the next controller" path instead of returning 0.

  original:  68 AC 53 00 10   push 0x100053AC   ; "根Hub名字失败"
  patched:   EB 2D            jmp  +0x2D         ; -> 0x2C6B -> jmp 0x2CEB

Only two bytes are changed, right before a relocation entry, so ASLR does not
corrupt the patch.  On machines where the scan already works the patched
branch is never executed and behaviour is identical.

Usage:
    python scripts/patch_amlusbscan.py            # patch in place
    python scripts/patch_amlusbscan.py --check    # exit 1 if not patched
"""

import os
import sys

# File offset of the `push 0x100053AC` instruction.
# RVA 0x2C3C -> .text (VA 0x1000, raw offset 0x400).
PATCH_OFFSET = 0x203C
ORIGINAL = bytes.fromhex("68ac530010")  # push 0x100053AC
PATCHED = bytes.fromhex("eb2d")         # jmp +0x2D (skip the fatal return)


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dll = os.path.join(root, "service_flash", "AmlUsbScanX3.dll")
    check_only = "--check" in sys.argv

    if not os.path.isfile(dll):
        print(f"[!] Не найден {dll}")
        return 2

    with open(dll, "rb") as f:
        data = bytearray(f.read())

    current = bytes(data[PATCH_OFFSET:PATCH_OFFSET + 5])
    if current[:2] == PATCHED:
        print("[OK] AmlUsbScanX3.dll уже пропатчен (jmp +0x2D).")
        return 0
    if current != ORIGINAL:
        print(
            "[!] Неожидаемые байты в AmlUsbScanX3.dll по смещению "
            f"0x{PATCH_OFFSET:X}: {current.hex()}"
        )
        print("    Патч не применён (возможно, другая версия DLL).")
        return 3

    if check_only:
        print("[!] AmlUsbScanX3.dll не пропатчен (ожидается jmp +0x2D).")
        return 1

    data[PATCH_OFFSET:PATCH_OFFSET + 2] = PATCHED
    tmp = dll + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, dll)
    print("[OK] AmlUsbScanX3.dll пропатчен: битый USB-контроллер больше не прерывает поиск станции.")
    return 0


if __name__ == "__main__":
    sys.exit(main())