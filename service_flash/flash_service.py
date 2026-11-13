#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OpenStation Max — Unified Amlogic USB Service Port Engine (powered by yasta_flasher).

Supports:
  - Hardware & driver diagnostics (--check)
  - Silent WorldCup driver install (--install-driver)
  - Auto-download latest OpenStationMax-ROM-*.zip from GitHub Releases
  - Reliable BootROM -> U-Boot handoff (pyamlboot_local + 3x retry + 4s settle + mmc dev 1)
  - Live U-Boot env unlock (silent=0, lock=10000000, avb2=0, saveenv)
  - Full service partition flashing (vbmeta, vendor, system, misc) + data/cache erase
"""

import argparse
import ctypes
import glob
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

GITHUB_REPO = "Olegu621/OPENSTATION"
GITHUB_LATEST_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"

# Ensure bundled DLLs (libusb0, AmlLibusb, UsbRomDrv) resolve seamlessly on Windows
for _dll_dir in (
    SCRIPT_DIR,
    os.path.join(SCRIPT_DIR, "Drivers", "amd64"),
    os.path.join(SCRIPT_DIR, "Drivers", "x86"),
):
    if os.path.isdir(_dll_dir):
        if hasattr(os, "add_dll_directory"):
            try:
                os.add_dll_directory(_dll_dir)
            except OSError:
                pass
        os.environ["PATH"] = _dll_dir + os.pathsep + os.environ.get("PATH", "")


def configure_stdio():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
        except Exception:
            pass


def emit(msg: str):
    print(msg, flush=True)


def emit_progress(pct: int, stage: str = ""):
    pct_clamped = max(0, min(100, int(pct)))
    if stage:
        emit(f"[STAGE] {stage}")
    emit(f"[PCT] {pct_clamped}")


def safe_decode(raw) -> str:
    if raw is None:
        return ""
    if isinstance(raw, str):
        return raw
    for enc in ("utf-8", "cp1251", "cp866", "latin-1"):
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", errors="replace")


def is_admin() -> bool:
    if os.name != "nt":
        return os.geteuid() == 0
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def hidden_subprocess_kwargs() -> dict:
    if sys.platform == "win32":
        return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)}
    return {}


def get_update_exe() -> str:
    exe = os.path.join(SCRIPT_DIR, "update.exe")
    if os.path.isfile(exe):
        return exe
    alt = os.path.join(SCRIPT_DIR, "update")
    if os.path.isfile(alt):
        return alt
    raise FileNotFoundError(f"update.exe не найден в {SCRIPT_DIR}")


def get_adb_exe() -> str:
    adb = os.path.join(SCRIPT_DIR, "adb", "adb.exe")
    if os.path.isfile(adb):
        return adb
    return shutil.which("adb") or ""


def check_usb_device() -> dict:
    info = {"usb_connected": False, "usb_stage": "none", "usb_desc": ""}
    # 1. PnP check (works even if libusb filter not attached yet)
    if os.name == "nt":
        try:
            ps = (
                "Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | "
                "Where-Object { $_.InstanceId -match 'VID_1B8E&PID_C003' } | "
                "Select-Object -First 1 -Property FriendlyName,Status | ConvertTo-Json -Compress"
            )
            p = subprocess.run(
                ["powershell", "-NoProfile", "-Command", ps],
                capture_output=True,
                timeout=6,
                **hidden_subprocess_kwargs(),
            )
            out = safe_decode(p.stdout).strip()
            if out and out.startswith("{"):
                data = json.loads(out)
                status = str(data.get("Status", ""))
                name = str(data.get("FriendlyName", "WorldCup Device"))
                info["usb_connected"] = True
                info["usb_desc"] = f"{name} ({status})".strip()
                info["usb_stage"] = "rom_or_uboot"
        except Exception:
            pass

    # 2. Direct pyusb identify check (distinguishes BootROM vs U-Boot stage cleanly)
    try:
        import usb.core

        dev = usb.core.find(idVendor=0x1B8E, idProduct=0xC003)
        if dev is not None:
            info["usb_connected"] = True
            if not info["usb_desc"]:
                info["usb_desc"] = "Amlogic USB (1B8E:C003)"
            info["usb_stage"] = "ready"
    except Exception:
        pass
    return info


def check_worldcup_driver() -> bool:
    if os.name != "nt":
        return True
    try:
        sysroot = os.environ.get("SystemRoot", r"C:\Windows")
        sys_libusb = os.path.join(sysroot, "System32", "drivers", "libusb0.sys")
        if os.path.isfile(sys_libusb):
            return True
        p = subprocess.run(
            ["pnputil", "/enum-drivers"],
            capture_output=True,
            timeout=6,
            **hidden_subprocess_kwargs(),
        )
        out = safe_decode(p.stdout).lower()
        if "worldcup" in out or "libusb0" in out or "amlogic" in out:
            return True
    except Exception:
        pass
    return False


def check_adb_device() -> dict:
    adb = get_adb_exe()
    if not adb:
        return {"adb_connected": False, "adb_serial": ""}
    try:
        p = subprocess.run(
            [adb, "devices"],
            capture_output=True,
            timeout=5,
            **hidden_subprocess_kwargs(),
        )
        out = safe_decode(p.stdout)
        for line in out.splitlines()[1:]:
            parts = line.strip().split()
            if len(parts) >= 2 and parts[1] == "device":
                return {"adb_connected": True, "adb_serial": parts[0]}
    except Exception:
        pass
    return {"adb_connected": False, "adb_serial": ""}


def find_local_rom_dir() -> tuple:
    candidates = [
        os.path.join(REPO_ROOT, "rom"),
        os.path.join(SCRIPT_DIR, "rom"),
        os.path.join(REPO_ROOT, "boot_out"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "OpenStation", "rom_cache"),
    ]
    required = ("vbmeta.img", "vendor.img", "system.img")
    for d in candidates:
        if d and os.path.isdir(d) and all(os.path.isfile(os.path.join(d, f)) for f in required):
            ver = ""
            mpath = os.path.join(d, "rom_manifest.json")
            if os.path.isfile(mpath):
                try:
                    with open(mpath, "r", encoding="utf-8") as mf:
                        ver = str(json.load(mf).get("version", ""))
                except Exception:
                    pass
            return d, ver
    return "", ""


def run_check() -> dict:
    usb_info = check_usb_device()
    adb_info = check_adb_device()
    rom_dir, rom_ver = find_local_rom_dir()
    tools_ok = os.path.isfile(os.path.join(SCRIPT_DIR, "update.exe")) and os.path.isfile(
        os.path.join(SCRIPT_DIR, "aml_bundle.img")
    )
    res = {
        "admin": is_admin(),
        "driver_installed": check_worldcup_driver(),
        "usb_connected": usb_info["usb_connected"],
        "usb_stage": usb_info["usb_stage"],
        "usb_desc": usb_info["usb_desc"],
        "adb_connected": adb_info["adb_connected"],
        "adb_serial": adb_info["adb_serial"],
        "local_rom_dir": rom_dir,
        "local_rom_version": rom_ver,
        "tools_ready": tools_ok,
    }
    emit("[CHECK_JSON] " + json.dumps(res, ensure_ascii=False))
    return res


def install_driver() -> bool:
    ps1 = os.path.join(REPO_ROOT, "scripts", "install_driver.ps1")
    if os.path.isfile(ps1):
        emit("Установка драйвера WorldCup...")
        p = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1, "-NoPause"],
            capture_output=True,
            timeout=120,
            **hidden_subprocess_kwargs(),
        )
        out = safe_decode((p.stdout or b"") + (p.stderr or b""))
        for ln in out.splitlines():
            if ln.strip():
                emit(ln.strip())
        if p.returncode == 0:
            emit("[OK] Драйвер WorldCup установлен.")
            return True
    # Direct fallback via pnputil on bundled Drivers/inf
    infs = glob.glob(os.path.join(SCRIPT_DIR, "Drivers", "**", "*.inf"), recursive=True)
    for inf in infs:
        p = subprocess.run(
            ["pnputil", "/add-driver", inf, "/install"],
            capture_output=True,
            timeout=60,
            **hidden_subprocess_kwargs(),
        )
        if p.returncode in (0, 259, 3010):
            emit(f"[OK] Установлен драйвер: {os.path.basename(inf)}")
            return True
    return False


def aml_bulkcmd(cmd: str, timeout: int = 30, ignore_error: bool = False) -> str:
    update = get_update_exe()
    p = subprocess.run(
        [update, "bulkcmd", cmd],
        capture_output=True,
        timeout=timeout,
        **hidden_subprocess_kwargs(),
    )
    out = safe_decode((p.stdout or b"") + (p.stderr or b"")).strip()
    for ln in out.splitlines():
        s = ln.strip()
        if s:
            emit(f"  [USB] {s}")
    if p.returncode != 0 and not ignore_error:
        raise RuntimeError(f"Ошибка U-Boot '{cmd}': {out[:240]}")
    return out


def apply_unlock_minimum():
    emit("Применение разблокировки загрузчика (silent=0, lock=10000000, avb2=0)...")
    for key, val in (("silent", "0"), ("lock", "10000000"), ("avb2", "0")):
        try:
            aml_bulkcmd(f"setenv {key} {val}")
        except Exception as ex:
            emit(f"  [!] Предупреждение setenv {key}: {ex}")
    aml_bulkcmd("saveenv", ignore_error=True)


def boot_uboot_via_yasta(revision: str = "auto") -> bool:
    """Load temporary U-Boot using yasta_flasher's pyamlboot_local with 3x retry + 4s wait."""
    bundle_path = os.path.join(SCRIPT_DIR, "aml_bundle.img")
    if not os.path.isfile(bundle_path):
        bundle_path = os.path.join(SCRIPT_DIR, "aml.img")

    from pyamlboot_local.boot import main as send_aml_bundle

    max_tries = 3
    for attempt in range(1, max_tries + 1):
        try:
            if attempt > 1:
                emit(f"Повтор загрузки U-Boot ({attempt}/{max_tries})...")
                time.sleep(2.5)
            emit(f"Загрузка временного U-Boot ({os.path.basename(bundle_path)})...")
            send_aml_bundle(bundle_path)
            emit("[OK] Временный U-Boot запущен, ожидание USB-стека (4 сек)...")
            time.sleep(4.0)
            return True
        except Exception as ex:
            emsg = str(ex)
            transient = any(
                s in emsg.lower()
                for s in (
                    "timeout",
                    "reap_async",
                    "aborted",
                    "i/o",
                    "control_msg",
                    "no such device",
                    "not found",
                    "no backend",
                    "already",
                )
            )
            emit(f"  [!] Попытка {attempt}: {emsg[:140]}")
            if attempt >= max_tries or not transient:
                break

    # Fallback to revision-aware boot-g12.py (covers 00053 hardware if aml_bundle failed)
    boot_g12 = os.path.join(SCRIPT_DIR, "boot-g12.py")
    if os.path.isfile(boot_g12):
        emit("Запуск резервного загрузчика boot-g12.py...")
        p = subprocess.run(
            [sys.executable, boot_g12, "--revision", revision, "--retries", "3"],
            capture_output=False,
            timeout=120,
            **hidden_subprocess_kwargs(),
        )
        if p.returncode == 0:
            time.sleep(4.0)
            return True
    return False


def download_latest_rom(dest_cache: str, base_pct: int = 8, span_pct: int = 20) -> str:
    """Fetch latest OpenStationMax-ROM-*.zip from GitHub Releases and verify SHA-256."""
    emit("Поиск актуальной прошивки на GitHub (Olegu621/OPENSTATION)...")
    req = urllib.request.Request(
        GITHUB_LATEST_API,
        headers={"User-Agent": "OpenStationFlasher/1.5"},
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        rel = json.loads(resp.read().decode("utf-8"))

    tag = rel.get("tag_name", "latest")
    assets = rel.get("assets", [])
    rom_asset = None
    for a in assets:
        nm = str(a.get("name", ""))
        if nm.startswith("OpenStationMax-ROM-") and nm.endswith(".zip"):
            rom_asset = a
            break
    if not rom_asset:
        raise RuntimeError(f"В релизе {tag} не найден архив OpenStationMax-ROM-*.zip")

    asset_name = rom_asset["name"]
    asset_url = rom_asset["browser_download_url"]
    asset_size = int(rom_asset.get("size", 0))

    os.makedirs(dest_cache, exist_ok=True)
    extract_dir = os.path.join(dest_cache, f"rom_{tag}")
    manifest_path = os.path.join(extract_dir, "rom_manifest.json")
    if (
        os.path.isfile(manifest_path)
        and all(os.path.isfile(os.path.join(extract_dir, x)) for x in ("vbmeta.img", "vendor.img", "system.img"))
    ):
        emit(f"[OK] Актуальная прошивка {tag} уже загружена локально.")
        return extract_dir

    zip_path = os.path.join(dest_cache, asset_name)
    part_path = zip_path + ".part"
    emit(f"Загрузка {asset_name} ({asset_size / (1024*1024):.1f} МБ)...")

    dl_req = urllib.request.Request(asset_url, headers={"User-Agent": "OpenStationFlasher/1.5"})
    with urllib.request.urlopen(dl_req, timeout=60) as r, open(part_path, "wb") as f:
        downloaded = 0
        last_pct = -1
        while True:
            chunk = r.read(262144)
            if not chunk:
                break
            f.write(chunk)
            downloaded += len(chunk)
            if asset_size > 0:
                p = int((downloaded * span_pct) / asset_size)
                if p != last_pct:
                    last_pct = p
                    emit_progress(base_pct + p, f"Загрузка {asset_name} ({downloaded // (1024*1024)}/{asset_size // (1024*1024)} МБ)")
    os.replace(part_path, zip_path)

    emit("Распаковка и проверка контрольных сумм ROM...")
    os.makedirs(extract_dir, exist_ok=True)
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(extract_dir)

    if os.path.isfile(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as mf:
            man = json.load(mf)
        files_map = man.get("files") if isinstance(man.get("files"), dict) else (
            man.get("partitions") if isinstance(man.get("partitions"), dict) else {}
        )
        for part_key, meta in files_map.items():
            if not isinstance(meta, dict):
                continue
            img_name = str(meta.get("fileName") or part_key)
            fp = os.path.join(extract_dir, img_name)
            expected_sha = str(meta.get("sha256", "")).lower()
            if os.path.isfile(fp) and expected_sha:
                h = hashlib.sha256()
                with open(fp, "rb") as hf:
                    for b in iter(lambda: hf.read(1048576), b""):
                        h.update(b)
                if h.hexdigest().lower() != expected_sha:
                    raise RuntimeError(f"Повреждён файл {img_name}: несовпадение SHA-256")
    emit(f"[OK] Прошивка {tag} проверена и готова к установке.")
    return extract_dir


def flash_partition(name: str, img_path: str, pct_start: int, pct_end: int) -> None:
    update = get_update_exe()
    size_mb = os.path.getsize(img_path) / (1024 * 1024)
    emit(f"Запись раздела {name} ({size_mb:.1f} МБ)...")
    p = subprocess.Popen(
        [update, "partition", name, img_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=False,
        bufsize=0,
        **hidden_subprocess_kwargs(),
    )
    buf = b""
    last_sub_pct = -1
    while True:
        chunk = p.stdout.read(256)
        if chunk:
            buf += chunk
            while True:
                idx_n = buf.find(b"\n")
                idx_r = buf.find(b"\r")
                if idx_n < 0 and idx_r < 0:
                    break
                if idx_n < 0 or (0 <= idx_r < idx_n):
                    sep_idx, is_cr = idx_r, True
                else:
                    sep_idx, is_cr = idx_n, False
                seg = buf[:sep_idx]
                buf = buf[sep_idx + 1 :]
                line = safe_decode(seg).strip()
                if not line:
                    continue
                m = re.findall(r"(\d{1,3})%", line)
                if m:
                    sub = min(100, int(m[-1]))
                    if sub != last_sub_pct:
                        last_sub_pct = sub
                        overall = pct_start + int((pct_end - pct_start) * (sub / 100.0))
                        emit_progress(overall, f"Прошивка {name}.img — {sub}%")
                if not is_cr:
                    emit(f"  [USB] {line}")
        if p.poll() is not None:
            break
    if p.returncode != 0:
        raise RuntimeError(f"Ошибка записи раздела {name} (код {p.returncode})")
    emit_progress(pct_end, f"Раздел {name} записан")


def wait_for_usb_or_adb_reboot() -> None:
    usb_info = check_usb_device()
    if usb_info["usb_connected"]:
        emit(f"[OK] Найдена станция: {usb_info['usb_desc']}")
        return

    adb_info = check_adb_device()
    if adb_info["adb_connected"]:
        adb = get_adb_exe()
        emit(f"Станция подключена по ADB ({adb_info['adb_serial']}). Перезагрузка в USB Burn Mode...")
        subprocess.run(
            [adb, "-s", adb_info["adb_serial"], "shell", "su -c 'reboot update' || reboot update"],
            capture_output=True,
            timeout=10,
            **hidden_subprocess_kwargs(),
        )
        for _ in range(25):
            time.sleep(1.0)
            usb_info = check_usb_device()
            if usb_info["usb_connected"]:
                emit(f"[OK] Станция перешла в USB Boot: {usb_info['usb_desc']}")
                return

    raise RuntimeError(
        "Станция не найдена в режиме USB Boot (1B8E:C003).\n"
        "Замкните 6-й контакт сервисного USB-порта на GND (корпус) и подключите Type-C кабель."
    )


def run_flash(args):
    emit_progress(2, "Проверка подключения и драйвера...")
    if not check_worldcup_driver():
        if not is_admin():
            raise RuntimeError("Требуются права администратора для установки драйвера WorldCup.")
        if not install_driver():
            raise RuntimeError("Не удалось установить драйвер WorldCup.")

    emit_progress(6, "Подготовка актуальной прошивки...")
    rom_dir = args.rom_dir or ""
    if not rom_dir or not os.path.isdir(rom_dir):
        local_dir, _ = find_local_rom_dir()
        if local_dir and not args.download_latest:
            rom_dir = local_dir
        else:
            cache_dir = os.path.join(
                os.environ.get("LOCALAPPDATA", SCRIPT_DIR),
                "OpenStation",
                "rom_cache",
            )
            rom_dir = download_latest_rom(cache_dir, base_pct=6, span_pct=18)

    for req in ("vbmeta.img", "vendor.img", "system.img"):
        fp = os.path.join(rom_dir, req)
        if not os.path.isfile(fp):
            raise FileNotFoundError(f"В папке ROM отсутствует обязательный образ: {req}")

    emit_progress(25, "Поиск станции...")
    wait_for_usb_or_adb_reboot()

    emit_progress(28, "Загрузка временного U-Boot...")
    if not boot_uboot_via_yasta(revision=args.revision):
        raise RuntimeError(
            "Не удалось инициализировать U-Boot через USB Boot. "
            "Отключите кабель, снимите питание 19В на 5 сек и повторите."
        )

    emit_progress(35, "Инициализация eMMC...")
    aml_bulkcmd("mmc dev 1")
    apply_unlock_minimum()

    logo_img = os.path.join(rom_dir, "logo.img")
    if os.path.isfile(logo_img):
        flash_partition("logo", logo_img, 36, 38)

    flash_partition("vbmeta", os.path.join(rom_dir, "vbmeta.img"), 38, 41)
    flash_partition("vendor", os.path.join(rom_dir, "vendor.img"), 41, 52)
    flash_partition("system", os.path.join(rom_dir, "system.img"), 52, 92)

    misc_img = os.path.join(rom_dir, "misc.img")
    if os.path.isfile(misc_img):
        flash_partition("misc", misc_img, 92, 94)

    if not args.keep_data:
        emit_progress(95, "Очистка пользовательских разделов data/cache/misc...")
        for part in ("data", "cache", "misc"):
            aml_bulkcmd(f"amlmmc erase {part}", ignore_error=True)

    emit_progress(98, "Перезагрузка станции...")
    try:
        aml_bulkcmd("reset", timeout=4, ignore_error=True)
    except Exception:
        pass

    emit_progress(100, "Готово! Отключите перемычку и кабель.")
    emit("[DONE] Прошивка OpenStation Max успешно установлена!")


def main():
    configure_stdio()
    parser = argparse.ArgumentParser(description="OpenStation Max USB Service Port Flasher")
    parser.add_argument("--check", action="store_true", help="Проверить драйвер, USB и локальный ROM (JSON)")
    parser.add_argument("--install-driver", action="store_true", help="Установить драйвер WorldCup")
    parser.add_argument("--flash", action="store_true", help="Прошить станцию полностью автоматически")
    parser.add_argument("--rom-dir", default="", help="Путь к локальной папке с vbmeta.img/vendor.img/system.img")
    parser.add_argument("--download-latest", action="store_true", help="Принудительно скачать свежий ROM с GitHub")
    parser.add_argument("--keep-data", action="store_true", help="Не стирать пользовательские данные (data/cache)")
    parser.add_argument("--revision", default="auto", choices=["auto", "0008", "00052", "00053"])
    args = parser.parse_args()

    try:
        if args.check:
            run_check()
            return 0
        if args.install_driver:
            ok = install_driver()
            return 0 if ok else 1
        if args.flash:
            run_flash(args)
            return 0
        parser.print_help()
        return 0
    except Exception as ex:
        emit(f"[ERROR] {ex}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
