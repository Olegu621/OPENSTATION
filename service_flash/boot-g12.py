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
        is_64 = sys.maxsize > 2**32
        candidates = []
        if is_64:
            candidates.append(os.path.join(SCRIPT_DIR, "Drivers", "amd64", "libusb0.dll"))
        else:
            candidates.append(os.path.join(SCRIPT_DIR, "Drivers", "x86", "libusb0_x86.dll"))
            candidates.append(os.path.join(SCRIPT_DIR, "libusb0.dll"))
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
        description="USB boot tool for Amlogic G12 SoCs (Yandex Station Max: 0008, 00052, 00053)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--version", "-v", action="version", version="%(prog)s 1.3")
    parser.add_argument("--retries", type=int, default=3, help="Количество повторных попыток")
    parser.add_argument(
        "--revision",
        choices=["auto", "0008", "00052", "00053"],
        default="auto",
        help="Ревизия Станции Макс (0008, 00052, 00053 или auto)",
    )
    parser.add_argument("binary", nargs="?", default=None, help="Путь к aml.img / bootloader.img (если не указан, выбирается по ревизии)")
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


def get_image_candidates(args):
    if args.binary:
        return [args.binary]

    rev = args.revision
    aml_std = os.path.join(SCRIPT_DIR, "aml.img")
    aml_53 = os.path.join(SCRIPT_DIR, "aml_00053.img")
    bootloader_53 = os.path.join(SCRIPT_DIR, "bootloader.img")

    if rev == "00053":
        cands = [aml_53, bootloader_53, aml_std]
    elif rev in ("0008", "00052"):
        cands = [aml_std, aml_53, bootloader_53]
    else:  # auto
        # Проверяем оба варианта
        cands = [aml_std, aml_53, bootloader_53]

    valid = [p for p in cands if os.path.isfile(p)]
    if not valid:
        raise FileNotFoundError(f"Не найден образ загрузчика aml.img или aml_00053.img в {SCRIPT_DIR}")
    return valid


def boot_single_image(backend, img_path, retries=2):
    print(f"\n[*] Попытка загрузки образа: {os.path.basename(img_path)} ({os.path.getsize(img_path)} байт)...")
    with open(img_path, "rb") as f:
        data = f.read()

    attempt = 0
    while attempt < retries:
        attempt += 1
        dev = None
        try:
            dev = pyamlboot.AmlogicSoC(backend=backend)
            try:
                dev.dev.set_configuration()
            except usb.core.USBError:
                pass

            socid = dev.identify()
            rom_maj, rom_min = ord(socid[0]), ord(socid[1])
            stage_maj, stage_min = ord(socid[2]), ord(socid[3])
            print("  Firmware Version :")
            print(f"  ROM: {rom_maj}.{rom_min} Stage: {stage_maj}.{stage_min}")
            print(f"  Need Password: {ord(socid[4])} Password OK: {ord(socid[5])}")

            if stage_maj != 0:
                print(f"  [✓] Устройство уже находится в режиме U-Boot/Update (Stage {stage_maj}.{stage_min})!")
                return True

            loadAddr = 0xFFFA0000
            print(f"  Writing {os.path.basename(img_path)} at 0x{loadAddr:x} (BL2)...")
            dev.writeLargeMemory(loadAddr, data[0:0x10000], 4096)
            print("  [DONE]")

            print(f"  Running at 0x{loadAddr:x}...")
            dev.run(loadAddr)
            print("  [DONE]")

            time.sleep(2)

            prevLength = -1
            prevOffset = -1
            seq = 0
            amlc_err = 0
            while True:
                try:
                    (length, offset) = dev.getBootAMLC()
                    if length == prevLength and offset == prevOffset:
                        print("  [BL2 END — Загрузка завершена успешно!]")
                        return True

                    prevLength = length
                    prevOffset = offset

                    print(f"  AMLC dataSize={length}, offset={offset}, seq={seq}...")
                    dev.writeAMLCData(seq, offset, data[offset : offset + length])
                    print("  [DONE]")

                    seq += 1
                    amlc_err = 0
                except (usb.core.USBError, ValueError) as e:
                    amlc_err += 1
                    print(f"  USB Error during AMLC transfer ({amlc_err}/4): {e}")
                    if amlc_err >= 4:
                        raise
                    if dev is not None:
                        dev.close()
                        dev = None
                    time.sleep(2)
                    dev = reset_usb_connection(backend)
                    continue

        except Exception as e:
            print(f"  [!] Ошибка при загрузке {os.path.basename(img_path)} (попытка {attempt}/{retries}): {e}")
        finally:
            if dev is not None:
                try:
                    dev.close()
                except Exception:
                    pass
                dev = None
        if attempt < retries:
            time.sleep(3)

    return False


def main():
    args = parse_cmdline()
    backend = resolve_usb_backend()
    candidates = get_image_candidates(args)

    print("====================================================================")
    print("  Amlogic G12 BootROM -> U-Boot (Yandex Station Max 0008/00052/00053)")
    print(f"  Режим: ревизия={args.revision}, доступных образов: {len(candidates)}")
    print("====================================================================")

    for cand in candidates:
        if boot_single_image(backend, cand, retries=args.retries):
            print(f"\n[+] УСПЕХ: U-Boot успешно запущен с помощью {os.path.basename(cand)}!")
            return 0
        else:
            print(f"[-] Не удалось запустить через {os.path.basename(cand)}, пробуем следующий вариант...")

    print("\n[ОШИБКА] Ни один из образов (0008/00052 и 00053) не смог инициализировать U-Boot.")
    print("Проверьте: контакт сервисного Type-C, перемычку 6 пина на GND и подачу 19V питания.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
