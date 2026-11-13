#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OpenStation Max — Unified Amlogic USB Service Port Engine (powered by yasta_flasher).

Supports:
  - Hardware & driver diagnostics (--check)
  - Silent WorldCup driver install (--install-driver)
  - Auto-download latest OpenStationMax-ROM-*.zip from GitHub Releases
  - BootROM -> BL2 -> U-Boot handoff with USB re-enumeration and mmc dev 1
  - Live U-Boot env unlock (silent=0, lock=10000000, avb2=0, saveenv)
  - Service partition flashing through WorldCup (vbmeta, vendor, system, misc) + data/cache erase
"""

import argparse
from contextlib import contextmanager
import ctypes
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import struct
import sys
import tempfile
import time
import urllib.request
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

GITHUB_REPO = "Olegu621/OPENSTATION"
GITHUB_LATEST_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
REQUIRED_ROM_IMAGES = ("vbmeta.img", "vendor.img", "system.img")

# Ensure bundled libusb0 DLL matches Python pointer width (never mix 32-bit SCRIPT_DIR ahead of 64-bit Python)
_IS_PY_64 = sys.maxsize > 2**32
_DLL_DIRS = (
    (os.path.join(SCRIPT_DIR, "Drivers", "amd64"),)
    if _IS_PY_64
    else (os.path.join(SCRIPT_DIR, "Drivers", "x86"), SCRIPT_DIR)
)
for _dll_dir in _DLL_DIRS:
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
    """Detect Amlogic 1B8E:C003 purely via Windows PnP (never open libusb handles during polling)."""
    info = {"usb_connected": False, "usb_stage": "none", "usb_desc": "", "usb_service": ""}
    if os.name == "nt":
        try:
            ps = (
                "Get-CimInstance Win32_PnPEntity -Filter \"Present = TRUE AND DeviceID LIKE '%VID_1B8E&PID_C003%'\" | "
                "Select-Object -First 1 -Property Name,Status,Service | ConvertTo-Json -Compress"
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
                status = str(data.get("Status") or "")
                name = str(data.get("Name") or "WorldCup Device")
                svc = str(data.get("Service") or "")
                info["usb_connected"] = True
                info["usb_service"] = svc
                svc_tag = f", {svc}" if svc else ""
                info["usb_desc"] = f"{name} ({status}{svc_tag})".strip()
                info["usb_stage"] = "rom_or_uboot"
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


def local_rom_dirs(rom_root: str = REPO_ROOT) -> list:
    return [
        os.path.join(rom_root, "rom"),
        os.path.join(REPO_ROOT, "rom"),
        os.path.join(SCRIPT_DIR, "rom"),
        os.path.join(rom_root, "boot_out"),
        os.path.join(REPO_ROOT, "boot_out"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "OpenStation", "rom_cache"),
    ]


def read_rom_manifest(directory):
    try:
        with open(os.path.join(directory, "rom_manifest.json"), encoding="utf-8-sig") as stream:
            return json.load(stream)
    except FileNotFoundError:
        return {}


def find_local_rom(rom_root: str = REPO_ROOT) -> tuple:
    for directory in dict.fromkeys(local_rom_dirs(rom_root)):
        if all(os.path.isfile(os.path.join(directory, name)) for name in REQUIRED_ROM_IMAGES):
            try:
                version = str(read_rom_manifest(directory).get("version", ""))
            except (OSError, ValueError):
                version = ""
            return directory, version
        archives = glob.glob(os.path.join(directory, "OpenStationMax-ROM-*.zip"))
        if archives:
            manifest = read_rom_manifest(directory)
            expected = os.path.join(directory, manifest.get("archive", {}).get("fileName", ""))
            if expected in archives:
                return expected, str(manifest.get("version", ""))
            archive = max(archives, key=lambda path: (os.path.getmtime(path), path))
            version = os.path.basename(archive)[len("OpenStationMax-ROM-"):-4]
            return archive, version
    return "", ""


def run_check(rom_root: str = REPO_ROOT) -> dict:
    usb_info = check_usb_device()
    adb_info = check_adb_device()
    rom_path, rom_ver = find_local_rom(rom_root)
    tools_ok = os.path.isfile(os.path.join(SCRIPT_DIR, "update.exe")) and any(
        os.path.isfile(os.path.join(SCRIPT_DIR, image))
        for image in ("aml_bundle.img", "aml.img", "aml_00053.img", "bootloader.img")
    )
    res = {
        "admin": is_admin(),
        "driver_installed": check_worldcup_driver(),
        "usb_connected": usb_info["usb_connected"],
        "usb_stage": usb_info["usb_stage"],
        "usb_desc": usb_info["usb_desc"],
        "adb_connected": adb_info["adb_connected"],
        "adb_serial": adb_info["adb_serial"],
        "local_rom_dir": rom_path if os.path.isdir(rom_path) else "",
        "local_rom_archive": rom_path if os.path.isfile(rom_path) else "",
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
    try:
        with open_worldcup() as soc:
            reply = soc.bulkCmd(cmd, timeout * 1000)
        if not reply.startswith(b"success"):
            raise RuntimeError(safe_decode(reply) or "пустой ответ")
        return safe_decode(reply)
    except Exception as ex:
        if not ignore_error:
            raise RuntimeError(f"Ошибка U-Boot '{cmd}': {ex}") from ex
        return ""


def apply_unlock_minimum():
    emit("Применение разблокировки загрузчика (silent=0, lock=10000000, avb2=0)...")
    for key, val in (("silent", "0"), ("lock", "10000000"), ("avb2", "0")):
        try:
            aml_bulkcmd(f"setenv {key} {val}")
        except Exception as ex:
            emit(f"  [!] Предупреждение setenv {key}: {ex}")
    aml_bulkcmd("saveenv", ignore_error=True)


def _update_output_failed(out: str, returncode: int) -> bool:
    """update.exe exits with 0 even when it never found the device."""
    if returncode != 0:
        return True
    txt = (out or "").lower()
    return any(
        marker in txt
        for marker in (
            "err(l",
            "can not find device",
            "no [worldcup device]",
            "scan usb devices failed",
            "fail to open file",
        )
    )


def is_uboot_alive(timeout: int = 4) -> bool:
    try:
        with open_worldcup() as soc:
            return usb_boot_stage(soc.identify()) == "uboot" and soc.bulkCmd(
                "mmc dev 1", timeout * 1000
            ).startswith(b"success")
    except Exception:
        return False


def wait_uboot_alive(wait_sec: float = 15.0) -> bool:
    deadline = time.monotonic() + wait_sec
    while time.monotonic() < deadline:
        if is_uboot_alive(timeout=2):
            return True
        time.sleep(0.5)
    return False


def warn_conflicting_usb_tools() -> None:
    if os.name != "nt":
        return
    try:
        p = subprocess.run(
            ["tasklist", "/FO", "CSV", "/NH"],
            capture_output=True,
            timeout=4,
            **hidden_subprocess_kwargs(),
        )
        txt = safe_decode(p.stdout).lower()
        for proc in ("usb_burning_tool.exe", "worldcup", "aml_upgrade"):
            if proc in txt:
                emit(f"[!] ВНИМАНИЕ: Обнаружен конфликтующий процесс ({proc}). Закройте Amlogic USB Burning Tool и при повторе переподключите станцию.")
                break
    except Exception:
        pass


def get_pyusb_backends(preferred_service: str = "") -> list:
    import usb.backend.libusb0 as libusb0_be
    import usb.backend.libusb1 as libusb1_be

    backends = []

    def try_libusb0():
        if os.name == "nt":
            dlls = (
                [os.path.join(SCRIPT_DIR, "Drivers", "amd64", "libusb0.dll")]
                if _IS_PY_64
                else [
                    os.path.join(SCRIPT_DIR, "Drivers", "x86", "libusb0_x86.dll"),
                    os.path.join(SCRIPT_DIR, "libusb0.dll"),
                ]
            )
            for dll in dlls:
                if os.path.isfile(dll):
                    be = libusb0_be.get_backend(find_library=lambda _n, p=dll: p)
                    if be is not None:
                        backends.append(("libusb0", be))
                        return
        be = libusb0_be.get_backend()
        if be is not None:
            backends.append(("libusb0", be))

    def try_libusb1():
        be = libusb1_be.get_backend()
        if be is not None:
            backends.append(("libusb1", be))

    if preferred_service.lower() in ("winusb", "libusbk"):
        try_libusb1()
        try_libusb0()
    else:
        try_libusb0()
        try_libusb1()
    return backends


def usb_boot_stage(socid: str) -> str:
    if len(socid) < 4:
        raise ValueError("Неполный ответ WorldCup identify")
    version = tuple(map(ord, socid[:4]))
    if version == (0, 7, 0, 16):
        # TPL on Station Max reports stage 0.16 despite sharing the BootROM VID/PID.
        return "uboot"
    if version[2:] == (1, 8):
        return "bl2"
    if version[2:] == (0, 0):
        return "rom"
    return "unknown"


@contextmanager
def open_worldcup():
    from pyamlboot_local import proto
    from usb.core import USBError

    last_error = None
    for _, backend in get_pyusb_backends():
        soc = None
        try:
            soc = proto.AmlogicSoC(backend=backend)
            soc.dev.set_configuration()
        except (USBError, ValueError) as ex:
            last_error = ex
            if soc is not None:
                soc.close()
            continue
        try:
            yield soc
        finally:
            soc.close()
        return
    raise RuntimeError("Не удалось открыть устройство WorldCup через libusb") from last_error


def run_usb_boot_step_inproc(img_path: str, preferred_service: str = "") -> int:
    """Single isolated BootROM -> U-Boot transfer attempt (always releases handle on exit)."""
    from pyamlboot_local import proto

    with open(img_path, "rb") as f:
        data = f.read()
    if len(data) < 0x10000:
        raise ValueError(f"Слишком короткий образ U-Boot: {img_path}")

    backends = get_pyusb_backends(preferred_service)
    if not backends:
        raise RuntimeError("Не найден USB backend libusb0/libusb1.")

    last_ex = None
    for be_name, backend in backends:
        soc = None
        try:
            soc = proto.AmlogicSoC(backend=backend)
            try:
                soc.dev.set_configuration()
            except Exception:
                pass

            socid = soc.identify()
            stage = usb_boot_stage(socid)
            emit(f"  [{be_name}] WorldCup ID: {'-'.join(str(ord(c)) for c in socid[:4])}")
            if stage == "uboot":
                return 0
            if stage != "rom":
                raise RuntimeError("Для запуска BL2 переподключите питание станции в режиме BootROM.")

            load_addr = 0xFFFA0000
            if socid[:2] == "\x03\x02":
                # G12 DDR initialization sequence used by USB Burning Tool.
                for value in (0xB1, 0x5183, 0xB1, 0x5183):
                    soc.writeSimpleMemory(load_addr, struct.pack("<I", value))
                    time.sleep(0.5)

            soc.writeLargeMemory(load_addr, data[:0x10000], 4096)
            soc.run(load_addr)
            time.sleep(0.2)

            previous_request = None
            for seq in range(256):
                length, offset = soc.getBootAMLC()
                if previous_request is not None and ((length, offset) == previous_request or length == 0):
                    emit(f"[OK] Загружен {os.path.basename(img_path)} через {be_name} ({seq} блоков AMLC).")
                    return 0
                if length == 0 or offset + length > len(data):
                    raise ValueError(f"Запрос AMLC вне образа: смещение {offset}, размер {length}")
                soc.writeAMLCData(seq, offset, data[offset : offset + length])
                previous_request = (length, offset)
            raise RuntimeError("Превышено число блоков AMLC.")
        except Exception as ex:
            last_ex = ex
            emit(f"  [!] [{be_name}] {str(ex).strip()[:160]}")
        finally:
            if soc is not None:
                try:
                    soc.close()
                except Exception:
                    pass
                soc = None

    if last_ex is not None:
        raise last_ex
    return 1


def boot_uboot_via_yasta(revision: str = "auto") -> bool:
    """Load the temporary U-Boot and verify it after USB re-enumeration."""
    if is_uboot_alive(timeout=4):
        emit("[OK] U-Boot уже активен на сервисном порту.")
        return True

    warn_conflicting_usb_tools()
    usb_info = check_usb_device()
    pref_svc = usb_info.get("usb_service", "")

    if revision == "00053":
        cand_names = ("aml_00053.img", "bootloader.img", "aml_bundle.img")
    elif revision in ("0008", "00052"):
        cand_names = ("aml_bundle.img", "aml.img", "aml_00053.img")
    else:
        cand_names = ("aml_00053.img", "aml_bundle.img", "aml.img", "bootloader.img")

    candidates = []
    seen = set()
    for nm in cand_names:
        p = os.path.join(SCRIPT_DIR, nm)
        if os.path.isfile(p):
            rp = os.path.realpath(p)
            if rp not in seen:
                seen.add(rp)
                candidates.append(p)
    if not candidates:
        raise FileNotFoundError(f"Не найден образ U-Boot (aml_bundle.img / aml_00053.img) в {SCRIPT_DIR}")

    # An isolated process releases the USB handle before the next stage enumerates.
    for idx, img_path in enumerate(candidates):
        max_tries = 2 if idx == 0 else 1
        for attempt in range(1, max_tries + 1):
            emit(f"Загрузка временного U-Boot ({os.path.basename(img_path)}, попытка {attempt}/{max_tries})...")
            cmd = [sys.executable, "-u", __file__, "--usb-boot-step", img_path]
            if pref_svc:
                cmd.extend(["--usb-service", pref_svc])
            env = os.environ.copy()
            env["PYTHONUNBUFFERED"] = "1"
            try:
                rc = subprocess.run(
                    cmd, cwd=SCRIPT_DIR, stdin=subprocess.DEVNULL, env=env, timeout=45,
                    **hidden_subprocess_kwargs(),
                ).returncode
            except subprocess.TimeoutExpired:
                rc = 1
                emit("  [!] Истекло время загрузки временного U-Boot.")
            emit("Ожидание USB-интерфейса U-Boot после передачи загрузчика...")
            # Windows may report a disconnect as the final AMLC block is acknowledged.
            if wait_uboot_alive(15 if rc == 0 else 8):
                emit("[OK] Временный U-Boot готов к приёму команд.")
                return True

    # Strategy 2: Native Amlogic C bootloader (update.exe bl2_boot) using bundled x86 AmlLibusb.dll + libusb0.dll
    update = get_update_exe()
    for img_path in candidates:
        emit(f"Запуск нативного загрузчика update.exe bl2_boot ({os.path.basename(img_path)})...")
        try:
            p = subprocess.run(
                [update, "bl2_boot", img_path],
                cwd=SCRIPT_DIR,
                stdin=subprocess.DEVNULL,
                capture_output=True,
                timeout=30,
                **hidden_subprocess_kwargs(),
            )
            out = safe_decode((p.stdout or b"") + (p.stderr or b"")).strip()
            for ln in out.splitlines():
                if ln.strip():
                    emit(f"  [BL2] {ln.strip()}")
            if wait_uboot_alive(8 if _update_output_failed(out, p.returncode) else 15):
                emit("[OK] Нативный U-Boot запущен и готов к прошивке.")
                return True
        except Exception as ex:
            emit(f"  [!] update.exe bl2_boot: {str(ex)[:140]}")
        time.sleep(2.0)

    if os.name == "nt" and not is_admin():
        emit("[!] Подсказка: попробуйте запустить OpenStationFlasher.exe от имени Администратора и переподключить Type-C кабель.")
    return False


def download_latest_rom(dest_cache: str, base_pct: int = 8, span_pct: int = 20) -> str:
    """Fetch latest OpenStationMax-ROM-*.zip from GitHub Releases and verify SHA-256."""
    emit("Поиск актуальной прошивки на GitHub (Olegu621/OPENSTATION)...")

    def _get_json(url: str):
        req = urllib.request.Request(url, headers={"User-Agent": "OpenStationFlasher/1.5"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))

    try:
        rel = _get_json(GITHUB_LATEST_API)
        releases = [rel]
    except Exception:
        releases = []

    def _find_rom(assets):
        for a in assets or []:
            nm = str(a.get("name", ""))
            if nm.startswith("OpenStationMax-ROM-") and nm.endswith(".zip"):
                return a
        return None

    tag = releases[0].get("tag_name", "latest") if releases else "latest"
    rom_asset = _find_rom(releases[0].get("assets", []) if releases else [])
    if not rom_asset:
        # A release may ship only the fixed flasher tools (e.g. v1.5.2 while
        # the ROM itself is still v1.5.1). Look through older releases too.
        try:
            for rel_item in _get_json(f"https://api.github.com/repos/{GITHUB_REPO}/releases"):
                found = _find_rom(rel_item.get("assets", []))
                if found:
                    tag = rel_item.get("tag_name", tag)
                    rom_asset = found
                    break
        except Exception:
            pass
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


def extract_local_rom(archive, destination):
    manifest = read_rom_manifest(os.path.dirname(archive))
    archive_info = manifest.get("archive", {})
    if archive_info.get("fileName") == os.path.basename(archive):
        expected_hash = archive_info.get("sha256")
        if expected_hash:
            with open(archive, "rb") as stream:
                digest = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            if digest.hexdigest() != expected_hash.lower():
                raise RuntimeError(f"Повреждён архив {archive}: несовпадение SHA-256")

    with zipfile.ZipFile(archive) as rom:
        members = set(rom.namelist())
        for name in REQUIRED_ROM_IMAGES:
            if name not in members or rom.getinfo(name).file_size == 0:
                raise RuntimeError(f"В архиве {archive} отсутствует образ {name}")
        for name in (*REQUIRED_ROM_IMAGES, "logo.img", "misc.img"):
            if name in members:
                rom.extract(name, destination)


def download_image(target, name, img_path, image_format="normal", progress=None):
    size = os.path.getsize(img_path)
    if size == 0:
        raise ValueError(f"Пустой образ: {img_path}")

    with open(img_path, "rb") as image, open_worldcup() as soc:
        reply = soc.start_download(target, name, image_format, size, timeout=30000)
        if not reply.startswith(b"success"):
            raise RuntimeError(f"WorldCup отклонил загрузку {name}: {reply!r}")

        written = 0
        for seq, data in enumerate(iter(lambda: image.read(65536), b"")):
            soc.write_download_block(seq, data, timeout=50000)
            written += len(data)
            if progress:
                progress(written * 100 // size)

        if written != size:
            raise RuntimeError(f"Размер образа изменился во время записи: {img_path}")
        reply = soc.bulkCmd("download get_status", timeout=120000)
        if not reply.startswith(b"success"):
            raise RuntimeError(f"WorldCup не подтвердил запись {name}: {reply!r}")


def flash_partition(name: str, img_path: str, pct_start: int, pct_end: int) -> None:
    size_mb = os.path.getsize(img_path) / (1024 * 1024)
    emit(f"Запись раздела {name} ({size_mb:.1f} МБ)...")
    last_sub_pct = -1

    def report_progress(sub):
        nonlocal last_sub_pct
        if sub != last_sub_pct and sub < 100:
            last_sub_pct = sub
            overall = pct_start + (pct_end - pct_start) * sub // 100
            emit_progress(overall, f"Прошивка {name}.img — {sub}%")

    download_image("store", name, img_path, progress=report_progress)
    emit_progress(pct_end, f"Раздел {name} записан")


def wait_for_usb_or_adb_reboot() -> None:
    if is_uboot_alive(timeout=4):
        emit("[OK] Станция уже в режиме U-Boot (WorldCup ready).")
        return

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
        for _ in range(28):
            time.sleep(1.0)
            if is_uboot_alive(timeout=3):
                emit("[OK] Станция перешла в U-Boot Burn Mode.")
                return
            usb_info = check_usb_device()
            if usb_info["usb_connected"]:
                emit(f"[OK] Станция перешла в USB Boot: {usb_info['usb_desc']}")
                return

    raise RuntimeError(
        "Станция не найдена в режиме USB Boot (1B8E:C003).\n"
        "Замкните 6-й контакт сервисного USB-порта на GND (корпус) и подключите Type-C кабель."
    )


def ensure_worldcup_driver():
    if not check_worldcup_driver():
        if not is_admin():
            raise RuntimeError("Требуются права администратора для установки драйвера WorldCup.")
        if not install_driver():
            raise RuntimeError("Не удалось установить драйвер WorldCup.")


def enter_update_mode(revision):
    wait_for_usb_or_adb_reboot()
    if boot_uboot_via_yasta(revision):
        return
    raise RuntimeError(
        "Не удалось запустить U-Boot Update. Закройте USB Burning Tool, "
        "переподключите питание станции в сервисном режиме и повторите."
    )


def run_flash(args):
    emit_progress(2, "Проверка подключения и драйвера...")
    ensure_worldcup_driver()

    emit_progress(6, "Подготовка актуальной прошивки...")
    rom_path = args.rom_dir
    if not rom_path and not args.download_latest:
        rom_path, _ = find_local_rom(args.rom_root)
    if not rom_path:
        cache_dir = os.path.join(
            os.environ.get("LOCALAPPDATA", SCRIPT_DIR),
            "OpenStation",
            "rom_cache",
        )
        rom_path = download_latest_rom(cache_dir, base_pct=6, span_pct=18)

    if os.path.isfile(rom_path):
        emit_progress(8, f"Проверка и распаковка {os.path.basename(rom_path)}...")
        with tempfile.TemporaryDirectory(prefix="openstation-rom-") as rom_dir:
            extract_local_rom(rom_path, rom_dir)
            flash_prepared_rom(rom_dir, args)
    else:
        flash_prepared_rom(rom_path, args)


def flash_prepared_rom(rom_dir, args):
    for req in REQUIRED_ROM_IMAGES:
        fp = os.path.join(rom_dir, req)
        if not os.path.isfile(fp):
            raise FileNotFoundError(f"В папке ROM отсутствует обязательный образ: {req}")

    emit_progress(25, "Переход в режим U-Boot Update...")
    enter_update_mode(args.revision)

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
    parser.add_argument("--enter-update", action="store_true", help="Перевести станцию в U-Boot Update без прошивки")
    parser.add_argument("--flash", action="store_true", help="Прошить станцию полностью автоматически")
    parser.add_argument("--rom-dir", default="", help="Путь к локальной папке с образами или ZIP-архиву ROM")
    parser.add_argument("--rom-root", default=REPO_ROOT, help=argparse.SUPPRESS)
    parser.add_argument("--download-latest", action="store_true", help="Принудительно скачать свежий ROM с GitHub")
    parser.add_argument("--keep-data", action="store_true", help="Не стирать пользовательские данные (data/cache)")
    parser.add_argument("--revision", default="auto", choices=["auto", "0008", "00052", "00053"])
    parser.add_argument("--skip-driver-check", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--usb-boot-step", default="", help=argparse.SUPPRESS)
    parser.add_argument("--usb-service", default="", help=argparse.SUPPRESS)
    parser.add_argument("--bulkcmd", help=argparse.SUPPRESS)
    parser.add_argument("--mwrite", nargs=4, metavar=("IMAGE", "TARGET", "NAME", "FORMAT"), help=argparse.SUPPRESS)
    args = parser.parse_args()

    try:
        if args.usb_boot_step:
            return run_usb_boot_step_inproc(args.usb_boot_step, args.usb_service)
        if args.bulkcmd:
            emit(aml_bulkcmd(args.bulkcmd))
            return 0
        if args.mwrite:
            image, target, name, image_format = args.mwrite
            download_image(target, name, image, image_format)
            return 0
        if args.check:
            run_check(args.rom_root)
            return 0
        if args.install_driver:
            ok = install_driver()
            return 0 if ok else 1
        if args.enter_update:
            if not args.skip_driver_check:
                ensure_worldcup_driver()
            enter_update_mode(args.revision)
            return 0
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
