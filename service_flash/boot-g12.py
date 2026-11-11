#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import os
import platform
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import usb.backend.libusb0
import usb.backend.libusb1
import usb.core
import usb.util
from pyamlboot import pyamlboot


def resolve_usb_backend():
    if os.name == "nt":
        is_64 = platform.machine().endswith("64") or sys.maxsize > 2**32
        candidates = []
        if is_64:
            candidates.append(os.path.join(SCRIPT_DIR, "Drivers", "amd64", "libusb0.dll"))
            candidates.append(os.path.join(SCRIPT_DIR, "Drivers", "amd64", "libusbK.dll"))
        else:
            candidates.append(os.path.join(SCRIPT_DIR, "Drivers", "x86", "libusb0_x86.dll"))
            candidates.append(os.path.join(SCRIPT_DIR, "Drivers", "x86", "libusbK_x86.dll"))
        for dll in candidates:
            if os.path.isfile(dll):
                dll_dir = os.path.dirname(dll)
                if hasattr(os, "add_dll_directory"):
                    try:
                        os.add_dll_directory(dll_dir)
                    except OSError:
                        pass
                os.environ["PATH"] = dll_dir + os.pathsep + os.environ.get("PATH", "")
                be = usb.backend.libusb0.get_backend(find_library=lambda _name, p=dll: p)
                if be is not None:
                    return be
    be1 = usb.backend.libusb1.get_backend()
    if be1 is not None:
        return be1
    be0 = usb.backend.libusb0.get_backend()
    if be0 is not None:
        return be0
    raise RuntimeError(
        "USB backend (libusb0/libusb1) не найден. Установите драйвер WorldCup из service_flash\\Drivers."
    )


def parse_cmdline():
    parser = argparse.ArgumentParser(
        description="USB boot tool for Amlogic G12 SoCs (Yandex Station Max)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--version", "-v", action="version", version="%(prog)s 1.1")
    parser.add_argument("--retries", type=int, default=5, help="Number of retries")
    parser.add_argument("binary", action="store", help="aml.img / FIP binary to load")
    return parser.parse_args()


def reset_usb_connection(backend):
    print("Переподключение к USB WorldCup (1B8E:C003)...")
    time.sleep(3)
    dev = usb.core.find(idVendor=0x1B8E, idProduct=0xC003, backend=backend)
    if dev is None:
        raise ValueError("Устройство Amlogic (1B8E:C003) не найдено после сброса USB")
    try:
        dev.set_configuration()
    except usb.core.USBError:
        pass
    return pyamlboot.AmlogicSoC(backend=backend)


def main():
    args = parse_cmdline()
    backend = resolve_usb_backend()

    with open(args.binary, "rb") as f:
        data = f.read()

    retries = max(1, args.retries)
    while retries > 0:
        try:
            dev = pyamlboot.AmlogicSoC(backend=backend)
            try:
                dev.dev.set_configuration()
            except usb.core.USBError:
                pass
            socid = dev.identify()

            print("Firmware Version :")
            print("ROM: %d.%d Stage: %d.%d" % (ord(socid[0]), ord(socid[1]), ord(socid[2]), ord(socid[3])))
            print("Need Password: %d Password OK: %d" % (ord(socid[4]), ord(socid[5])))

            loadAddr = 0xFFFA0000
            seq = 0

            print(f"Writing {args.binary} at 0x{loadAddr:x}...")
            dev.writeLargeMemory(loadAddr, data[0:0x10000], 4096)
            print("[DONE]")

            print(f"Running at 0x{loadAddr:x}...")
            dev.run(loadAddr)
            print("[DONE]")

            time.sleep(2)

            prevLength = -1
            prevOffset = -1
            amlc_err = 0
            while True:
                try:
                    (length, offset) = dev.getBootAMLC()
                    if length == prevLength and offset == prevOffset:
                        print("[BL2 END]")
                        break

                    prevLength = length
                    prevOffset = offset

                    print(f"AMLC dataSize={length}, offset={offset}, seq={seq}...")
                    dev.writeAMLCData(seq, offset, data[offset : offset + length])
                    print("[DONE]")

                    seq += 1
                    amlc_err = 0
                except (usb.core.USBError, ValueError) as e:
                    amlc_err += 1
                    print(f"USB Error during AMLC transfer ({amlc_err}/5): {e}")
                    if amlc_err >= 5:
                        raise
                    time.sleep(3)
                    dev = reset_usb_connection(backend)
                    continue

            return 0

        except (usb.core.USBError, ValueError) as e:
            print(f"Ошибка подключения: {e}. Осталось попыток: {retries - 1}")
            retries -= 1
            if retries <= 0:
                break
            time.sleep(4)
            continue

    print("Не удалось инициализировать U-Boot через сервисный порт (1B8E:C003).")
    return 1


if __name__ == "__main__":
    sys.exit(main())
