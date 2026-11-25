#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OpenStation Max — Unified Amlogic USB Service Port Engine (powered by yasta_flasher).

Supports:
  - Hardware & driver diagnostics (--check)
  - Silent WorldCup driver install (--install-driver)
  - Auto-download latest OpenStationMax-ROM-*.zip from GitHub Releases
  - Reliable BootROM -> U-Boot handoff (pyamlboot_local + U-Boot stage detection + 40s re-enumeration wait)
  - Live U-Boot env unlock (silent=0, lock=10000000, avb2=0, saveenv)
  - Service partition flashing with the matched 1.99 base and after_ubt
"""

import argparse
import ctypes
from contextlib import contextmanager
from functools import lru_cache
import glob
import hashlib
import json
import os
import platform
import re
import shutil
import ssl
import subprocess
import struct
import sys
import time
import threading
import urllib.error
import urllib.request
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

GITHUB_REPO = "Olegu621/OPENSTATION"
GITHUB_LATEST_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"

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
    info = {"usb_connected": False, "usb_stage": "none", "usb_desc": "", "usb_service": "", "usb_status": ""}
    if os.name == "nt":
        try:
            ps = (
                "Get-CimInstance Win32_PnPEntity -ErrorAction SilentlyContinue | "
                "Where-Object { $_.Present -eq $true -and $_.DeviceID -like '*VID_1B8E&PID_C003*' } | "
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
                info["usb_status"] = status
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
        device = check_usb_device()
        if device.get("usb_connected") and device.get("usb_status", "").lower() == "ok" and device.get("usb_service", "").lower() in ("libusbk", "winusb", "libusb0"):
            return True
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


def _byte_at(buf, idx: int) -> int:
    try:
        v = buf[idx]
    except Exception:
        return 0
    try:
        return int(v)
    except (TypeError, ValueError):
        return ord(v)


EXIT_BOOTROM_PASSWORD = 4


def classify_usb_id(socid) -> dict:
    """Classify an Amlogic identify reply.

    A genuine G12 BootROM answers ``ROM 3.2 / Stage 0.0``. A station that is
    already running the temporary U-Boot (WorldCup burn mode) answers with
    different bytes (seen in the wild: ``ROM 0.7 / Stage 0.16``). Sending
    BootROM-only requests (WR_LARGE_MEM) to a live U-Boot kills the session
    with ``libusb0 ... Устройство не опознано``, so this distinction is exact.
    """
    info = {
        "rom": (0, 0),
        "stage": (0, 0),
        "mode": "unknown",
        "need_password": None,
        "password_ok": None,
        "password_blocked": False,
        "password_invalid": False,
    }
    if socid is None or len(socid) < 4:
        return info
    rom = (_byte_at(socid, 0), _byte_at(socid, 1))
    stage = (_byte_at(socid, 2), _byte_at(socid, 3))
    info["rom"] = rom
    info["stage"] = stage
    if rom[0] == 3 and stage[1] == 0:
        info["mode"] = "bootrom"
        if len(socid) >= 6:
            need_pw = _byte_at(socid, 4)
            pw_ok = _byte_at(socid, 5)
            info["need_password"] = need_pw
            info["password_ok"] = pw_ok
            if need_pw not in (0, 1) or pw_ok not in (0, 1):
                info["password_invalid"] = True
            elif need_pw == 1 and pw_ok != 1:
                info["password_blocked"] = True
    else:
        info["mode"] = "uboot"
    return info


def _bootrom_password_error(info: dict) -> str:
    """Return a diagnostic message when BootROM password flags block BL2 upload."""
    if not isinstance(info, dict) or info.get("mode") != "bootrom":
        return ""
    need_pw = info.get("need_password")
    pw_ok = info.get("password_ok")
    if info.get("password_invalid"):
        return (
            f"Неизвестные флаги защиты BootROM: Need Password={need_pw}, "
            f"Password OK={pw_ok}. Передача BL2 отменена."
        )
    if info.get("password_blocked"):
        return (
            "BootROM требует пароль (Need Password=1), но пароль не принят "
            "(Password OK=0). Передача BL2 отменена. Используйте только "
            "официальный/предусмотренный проектом способ авторизации; "
            "USB-таймауты после этого статуса не являются доказательством "
            "неисправности кабеля или образа."
        )
    return ""


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
            "err:",
            "failed",
        )
    )


def _configure_worldcup(soc):
    # Windows drivers can reject SET_CONFIGURATION while the device is already configured.
    try:
        soc.dev.get_active_configuration()
    except Exception:
        soc.dev.set_configuration()


def probe_burn_mode(timeout: int = 8) -> bool:
    from pyamlboot_local import proto

    info = check_usb_device()
    emit("[USB] Устройство: %s; драйвер: %s; состояние: %s" % (
        info.get("usb_desc") or "не найдено", info.get("usb_service") or "неизвестен",
        info.get("usb_status") or "неизвестно"))
    backends = get_pyusb_backends(info.get("usb_service", ""))
    if not backends:
        emit("[USB] Не найдена подходящая USB-библиотека.")
        return False
    for name, backend in backends:
        soc = None
        try:
            soc = proto.AmlogicSoC(backend=backend)
            _configure_worldcup(soc)
            ident = soc.identify()
            stage = usb_boot_stage(ident)
            ident_hex = " ".join("%02x" % _byte_at(ident, i) for i in range(min(4, len(ident))))
            emit("[USB] %s: код=%s, режим=%s" % (name, ident_hex, stage))
            if stage != "uboot":
                continue
            reply = soc.bulkCmd("mmc dev 1", timeout * 1000)
            if reply.startswith(b"success"):
                os.environ["OPENSTATION_USB_BACKEND"] = name
                emit("[USB] %s: устройство подтвердило команду." % name)
                return True
            emit("[USB] %s: команда отклонена: %s" % (name, safe_decode(reply)[:160]))
        except Exception as ex:
            emit("[USB] %s: %s: %s" % (name, type(ex).__name__, str(ex)[:180]))
        finally:
            if soc is not None:
                try:
                    soc.close()
                except Exception:
                    pass
    return False


def is_uboot_alive(timeout: int = 4) -> bool:
    return probe_burn_mode(timeout)


def probe_usb_identify(preferred_service: str = "") -> dict:
    """Read-only pyusb identify (never writes anything to the station)."""
    res = {
        "present": False,
        "mode": "none",
        "rom": (0, 0),
        "stage": (0, 0),
        "backend": "",
        "need_password": None,
        "password_ok": None,
        "password_blocked": False,
        "password_invalid": False,
    }
    try:
        from pyamlboot_local import proto
    except Exception:
        return res
    for be_name, backend in get_pyusb_backends(preferred_service):
        soc = None
        try:
            soc = proto.AmlogicSoC(backend=backend)
            try:
                _configure_worldcup(soc)
            except Exception:
                pass
            info = classify_usb_id(soc.identify())
            res.update(
                present=True,
                mode=info["mode"],
                rom=info["rom"],
                stage=info["stage"],
                backend=be_name,
                need_password=info.get("need_password"),
                password_ok=info.get("password_ok"),
                password_blocked=info.get("password_blocked", False),
                password_invalid=info.get("password_invalid", False),
            )
            return res
        except Exception:
            continue
        finally:
            if soc is not None:
                try:
                    soc.close()
                except Exception:
                    pass
    return res


def reset_usb_device(preferred_service: str = "") -> bool:
    """Force a USB port reset so Windows re-creates a clean device node."""
    try:
        from pyamlboot_local import proto
    except Exception:
        return False
    for _be_name, backend in get_pyusb_backends(preferred_service):
        soc = None
        try:
            soc = proto.AmlogicSoC(backend=backend)
            soc.dev.reset()
            return True
        except Exception:
            continue
        finally:
            if soc is not None:
                try:
                    soc.close()
                except Exception:
                    pass
    return False


def reenumerate_worldcup_device() -> bool:
    """Soft USB replug: disable/enable the WorldCup PnP node to clear a stuck state."""
    if os.name != "nt":
        return False
    ps = (
        "$d = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | "
        "Where-Object { $_.InstanceId -like '*VID_1B8E&PID_C003*' } | Select-Object -First 1; "
        "if ($d) { "
        "Disable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction SilentlyContinue; "
        "Start-Sleep -Milliseconds 700; "
        "Enable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction SilentlyContinue; "
        "Write-Output 'OK' }"
    )
    try:
        p = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps],
            capture_output=True,
            timeout=30,
            **hidden_subprocess_kwargs(),
        )
        return b"OK" in (p.stdout or b"")
    except Exception:
        return False


# After BL2 hands over to U-Boot the USB device re-enumerates and Windows
# re-binds the libusb0 driver. On real machines this regularly takes 10-25 s;
# the previous 7.5 s window is exactly why every user got "Порт не ответил"
# at 28%. Keep a generous margin.
UBOOT_HANDOFF_TIMEOUT = 40.0


def wait_uboot_alive(wait_sec: float = UBOOT_HANDOFF_TIMEOUT) -> bool:
    """Wait for the U-Boot WorldCup gadget to become usable."""
    start = time.monotonic()
    deadline = start + wait_sec
    # Do not poke update.exe during the first seconds of USB re-enumeration:
    # scanning a half-created device node leaves Windows in "device not
    # recognized" state.
    time.sleep(3.0)
    next_report = start + 5.0
    recovery = 0
    while time.monotonic() < deadline:
        if is_uboot_alive(timeout=8):
            return True
        now = time.monotonic()
        if now >= next_report:
            state = probe_usb_identify()
            elapsed = int(now - start)
            if state.get("mode") == "uboot":
                if recovery == 0 and elapsed >= 16:
                    # U-Boot is alive but update.exe still cannot scan it:
                    # usually a stale "device not recognized" node left by a
                    # previous failed attempt. A port reset re-creates it.
                    emit(f"  ... U-Boot уже на USB, но update.exe его не видит — переинициализация USB ({elapsed} с)")
                    reset_usb_device()
                    recovery = 1
                    time.sleep(2.0)
                elif recovery == 1 and elapsed >= 28:
                    emit(f"  ... повторное подключение устройства ({elapsed} с)")
                    reenumerate_worldcup_device()
                    recovery = 2
                    time.sleep(3.0)
                else:
                    emit(f"  ... U-Boot уже на USB ({state['backend']}), ждём готовности update.exe ({elapsed} с)")
            elif state.get("mode") == "bootrom":
                emit(f"  ... станция всё ещё в BootROM, ждём U-Boot ({elapsed} с)")
            else:
                emit(f"  ... ожидание USB-стека U-Boot ({elapsed} с)")
            next_report = now + 5.0
        time.sleep(1.0)
    if is_uboot_alive(timeout=8):
        return True
    state = probe_usb_identify()
    if state.get("mode") == "uboot":
        emit("  [!] U-Boot уже поднялся на USB, но update.exe его не видит.")
        emit("  [!] Отключите Type-C и питание 19В на 5 секунд, затем подключите снова.")
        emit("  [!] Если не помогает — переустановите драйвер WorldCup (libusb0).")
        emit("  [!] Также попробуйте другой USB-порт (желательно USB 2.0 на задней панели ПК).")
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
        dll = os.path.join(SCRIPT_DIR, "Drivers", "amd64" if _IS_PY_64 else "x86", "libusb-1.0.dll")
        be = libusb1_be.get_backend(find_library=lambda _n: dll) if os.path.isfile(dll) else libusb1_be.get_backend()
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
    preferred = check_usb_device().get("usb_service", "")
    backends = get_pyusb_backends(preferred)
    selected = os.environ.get("OPENSTATION_USB_BACKEND", "")
    backends.sort(key=lambda item: item[0] != selected)
    for _, backend in backends:
        soc = None
        try:
            soc = proto.AmlogicSoC(backend=backend)
            _configure_worldcup(soc)
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
        transfer_started = False
        try:
            soc = proto.AmlogicSoC(backend=backend)
            try:
                _configure_worldcup(soc)
            except Exception:
                pass

            socid = soc.identify()
            ident_info = classify_usb_id(socid)
            stage = usb_boot_stage(socid)
            emit(f"  [{be_name}] WorldCup ID: {'-'.join(str(_byte_at(socid, i)) for i in range(min(4, len(socid))))}")
            if len(socid) >= 6:
                emit(f"  [{be_name}] Need Password: {_byte_at(socid, 4)} Password OK: {_byte_at(socid, 5)}")
            if stage == "uboot":
                return 0
            if stage != "rom":
                raise RuntimeError("Для запуска BL2 переподключите питание станции в режиме BootROM.")
            pw_err = _bootrom_password_error(ident_info)
            if pw_err:
                emit(f"  [AUTH REQUIRED] {pw_err}")
                return EXIT_BOOTROM_PASSWORD

            load_addr = 0xFFFA0000
            transfer_started = True
            if socid[:2] == "\x03\x02":
                emit(f"  [{be_name}] Инициализация памяти...")
                # G12 DDR initialization sequence used by USB Burning Tool.
                for value in (0xB1, 0x5183, 0xB1, 0x5183):
                    soc.writeSimpleMemory(load_addr, struct.pack("<I", value))
                    time.sleep(0.5)

            emit(f"  [{be_name}] Передача начального загрузчика...")
            soc.writeLargeMemory(load_addr, data[:0x10000], 4096)
            emit(f"  [{be_name}] Запуск начального загрузчика...")
            soc.run(load_addr)
            time.sleep(0.2)
            emit(f"  [{be_name}] Ожидание запроса остальных данных...")

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
            msg = str(ex).strip()
            emit(f"  [!] [{be_name}] {type(ex).__name__}: {msg[:160]}")
            low = msg.lower()
            if "claim_interface" in low or "timeout" in low or "control_msg" in low:
                emit("  [!] Подсказка USB: закройте Amlogic USB Burning Tool и запустите от Администратора (попробуйте USB 2.0).")
            if transfer_started:
                # A partial transfer can leave the device in transition. Do not send
                # another loader using a different USB backend until it is identified again.
                raise
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
    """Initialize U-Boot via isolated pyamlboot_local worker, native update.exe bl2_boot, and boot-g12.py."""
    if is_uboot_alive(timeout=8):
        emit("[OK] U-Boot уже активен на сервисном порту.")
        return True

    warn_conflicting_usb_tools()
    usb_info = check_usb_device()
    pref_svc = usb_info.get("usb_service", "")

    # If the station is already running U-Boot there is nothing to upload:
    # BootROM commands sent to a live U-Boot drop the USB link
    # ("...Устройство не опознано") and leave a broken device node behind.
    pre = probe_usb_identify(pref_svc)
    if pre.get("mode") == "uboot":
        emit("[*] Станция уже в U-Boot — ожидаем готовности update.exe (загрузка BL2 не нужна)...")
        if wait_uboot_alive():
            emit("[OK] Временный U-Boot готов к приёму команд.")
            return True
        emit("  [!] U-Boot на USB, но update.exe его не видит — повторная загрузка BL2 отменена.")
        return False
    pw_err = _bootrom_password_error(pre)
    if pw_err:
        emit(f"[AUTH REQUIRED] {pw_err}")
        return False

    if revision == "00053":
        cand_names = ("aml_00053.img", "bootloader.img", "aml_bundle.img")
    elif revision in ("0008", "00052"):
        cand_names = ("aml_bundle.img", "aml.img", "aml_00053.img")
    else:
        cand_names = ("aml_bundle.img", "aml_00053.img", "aml.img")

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

    # Strategy 1: Isolated child subprocesses running pyamlboot_local (kernel reclaims USB interface on exit)
    for idx, img_path in enumerate(candidates):
        max_tries = 2 if idx == 0 else 1
        for attempt in range(1, max_tries + 1):
            emit(f"Загрузка временного U-Boot ({os.path.basename(img_path)}, попытка {attempt}/{max_tries})...")
            cmd = [sys.executable, "-u", __file__, "--usb-boot-step", img_path]
            if pref_svc:
                cmd.extend(["--usb-service", pref_svc])
            env = os.environ.copy()
            env["PYTHONUNBUFFERED"] = "1"
            p = subprocess.Popen(
                cmd,
                cwd=SCRIPT_DIR,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=False,
                env=env,
                **hidden_subprocess_kwargs(),
            )
            for raw_ln in p.stdout:
                txt = safe_decode(raw_ln).strip()
                if txt:
                    emit(f"  {txt}")
            p.wait(timeout=45)
            if p.returncode == EXIT_BOOTROM_PASSWORD:
                emit("[ОСТАНОВКА] BootROM ожидает успешную парольную авторизацию. Другие образы пробовать бессмысленно.")
                return False
            if p.returncode in (0, 2):
                if p.returncode == 2:
                    emit("  [OK] U-Boot уже запущен — пропускаем загрузку BL2.")
                emit(f"Ожидание инициализации USB-стека U-Boot (до {int(UBOOT_HANDOFF_TIMEOUT)} с)...")
                if wait_uboot_alive():
                    emit("[OK] Временный U-Boot готов к приёму команд.")
                    return True
                emit(f"  [!] bulkcmd 'mmc dev 1' не ответил за {int(UBOOT_HANDOFF_TIMEOUT)} с.")
                state = probe_usb_identify(pref_svc)
                if state.get("mode") == "uboot":
                    # U-Boot is alive - uploading another BL2 would kill it.
                    emit("  [!] U-Boot уже на USB, но update.exe его не видит. Повторная загрузка BL2 отменена.")
                    emit("  [!] Отключите Type-C и питание 19В на 5 секунд, затем подключите снова.")
                    emit("  [!] Если не помогает — переустановите драйвер WorldCup (libusb0) и попробуйте другой USB-порт (USB 2.0).")
                    return False
                emit("  [!] Станция вернулась в BootROM — пробуем следующий образ...")
            time.sleep(2.5)

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
            if p.returncode == 0 and not _update_output_failed(out, p.returncode):
                if wait_uboot_alive():
                    emit("[OK] Нативный U-Boot запущен и готов к прошивке.")
                    return True
        except Exception as ex:
            emit(f"  [!] update.exe bl2_boot: {str(ex)[:140]}")
        time.sleep(2.0)

    # Strategy 3: Legacy revision-aware boot-g12.py fallback with live unbuffered logs
    boot_g12 = os.path.join(SCRIPT_DIR, "boot-g12.py")
    if os.path.isfile(boot_g12):
        emit("Запуск резервного загрузчика boot-g12.py...")
        env = os.environ.copy()
        env["PYTHONUNBUFFERED"] = "1"
        p = subprocess.Popen(
            [sys.executable, "-u", boot_g12, "--revision", revision, "--retries", "2"],
            cwd=SCRIPT_DIR,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=False,
            env=env,
            **hidden_subprocess_kwargs(),
        )
        for raw_ln in p.stdout:
            txt = safe_decode(raw_ln).strip()
            if txt:
                emit(f"  [g12] {txt}")
        p.wait(timeout=90)
        if p.returncode == 2:
            emit("[ОСТАНОВКА] BootROM ожидает успешную парольную авторизацию. Другие образы пробовать бессмысленно.")
            return False
        if p.returncode == 0 and wait_uboot_alive():
            return True

    if os.name == "nt" and not is_admin():
        emit("[!] Подсказка: попробуйте запустить OpenStationFlasher.exe от имени Администратора и переподключить Type-C кабель.")
    emit("[!] U-Boot не запустился: отключите Type-C и питание 19В, замкните 6-й контакт на GND и подключите кабель снова.")
    return False


PINNED_ROM_VERSION = "1.6.3"
PINNED_ROM_TAG = "v1.6.3"
PINNED_ROM_NAME = "OpenStationMax-ROM-v1.6.3.zip"
PINNED_ROM_SIZE = 724888285
PINNED_ROM_SHA256 = "d1eac06a052d7aeed9a5a174ad29a8e951217c02816a55afd1a52888d390e9d5"


def _parse_rom_manifest_files(manifest: dict) -> dict:
    if not isinstance(manifest, dict):
        raise RuntimeError("Некорректный формат rom_manifest.json")
    files = manifest.get("files")
    if not isinstance(files, dict):
        raise RuntimeError("В rom_manifest.json отсутствует секция files")
    resolved = {}
    for key in ("vbmeta", "vendor", "system"):
        entry = files.get(key)
        if not isinstance(entry, dict):
            raise RuntimeError(f"В rom_manifest.json отсутствует описание раздела {key}")
        filename = str(entry.get("fileName") or f"{key}.img")
        size = int(entry.get("size") or 0)
        sha256 = str(entry.get("sha256") or "").strip().lower()
        if size <= 0 or len(sha256) != 64:
            raise RuntimeError(f"Некорректная контрольная сумма {filename} в rom_manifest.json")
        resolved[filename] = (size, sha256)
    return resolved


@lru_cache(maxsize=1)
def _rom_tls_context() -> ssl.SSLContext:
    # Keep the Windows trust store (including managed roots) and supply public
    # roots with the installer for Windows installations with an outdated store.
    if getattr(sys, "frozen", False):
        ca_path = os.path.join(SCRIPT_DIR, "tls", "cacert.pem")
    else:
        ca_path = os.path.join(REPO_ROOT, "assets", "alice", "cacert.pem")
    context = ssl.create_default_context()
    context.load_verify_locations(cafile=ca_path)
    return context


def _open_rom_url(request: urllib.request.Request, timeout: int):
    try:
        return urllib.request.urlopen(request, timeout=timeout, context=_rom_tls_context())
    except urllib.error.URLError as exc:
        if isinstance(exc.reason, ssl.SSLCertVerificationError):
            raise RuntimeError(
                "Не удалось проверить сертификат сервера GitHub. Проверьте дату и время Windows "
                "и настройки HTTPS-прокси. Загрузка прошивки остановлена."
            ) from exc
        raise


def _resolve_latest_rom_release() -> dict:
    """Resolve the newest ROM release and manifest from GitHub Releases, or fall back cleanly."""
    headers = {"User-Agent": "OpenStationFlasher/1.6", "Accept": "application/vnd.github+json"}
    api_url = f"https://api.github.com/repos/{GITHUB_REPO}/releases?per_page=100"
    req = urllib.request.Request(api_url, headers=headers)
    with _open_rom_url(req, timeout=20) as resp:
        releases = json.loads(resp.read().decode("utf-8"))

    if isinstance(releases, list):
        for rel in releases:
            if not isinstance(rel, dict) or rel.get("draft"):
                continue
            assets = rel.get("assets") or []
            manifest_asset = None
            zip_assets = []
            for a in assets:
                if not isinstance(a, dict):
                    continue
                aname = str(a.get("name") or "")
                if aname == "rom_manifest.json":
                    manifest_asset = a
                elif aname.startswith("OpenStationMax-ROM-") and aname.endswith(".zip"):
                    zip_assets.append(a)
            if not zip_assets:
                continue

            if manifest_asset and manifest_asset.get("browser_download_url"):
                mreq = urllib.request.Request(manifest_asset["browser_download_url"], headers=headers)
                with _open_rom_url(mreq, timeout=20) as mresp:
                    raw_manifest = mresp.read()
                manifest = json.loads(raw_manifest.decode("utf-8"))
                files_map = _parse_rom_manifest_files(manifest)
                arch = manifest.get("archive") or {}
                arch_name = str(arch.get("fileName") or "")
                arch_size = int(arch.get("size") or 0)
                arch_sha = str(arch.get("sha256") or "").strip().lower()
                matched_zip = next((z for z in zip_assets if z.get("name") == arch_name), None)
                if matched_zip and arch_size > 0 and len(arch_sha) == 64:
                    return {
                        "version": str(manifest.get("version") or rel.get("tag_name") or "").lstrip("vV"),
                        "name": arch_name,
                        "url": matched_zip["browser_download_url"],
                        "size": arch_size,
                        "sha256": arch_sha,
                        "files": files_map,
                        "manifest_bytes": raw_manifest,
                    }

            # Fallback for releases without attached rom_manifest.json asset (e.g. pinned v1.5.1-rom)
            z = zip_assets[0]
            if z.get("name") == PINNED_ROM_NAME:
                return {
                    "version": PINNED_ROM_VERSION,
                    "name": PINNED_ROM_NAME,
                    "url": z.get("browser_download_url") or (
                        f"https://github.com/Olegu621/OPENSTATION/releases/download/{PINNED_ROM_TAG}/{PINNED_ROM_NAME}"
                    ),
                    "size": PINNED_ROM_SIZE,
                    "sha256": PINNED_ROM_SHA256,
                    "files": dict(ROM_FILES),
                    "manifest_bytes": None,
                }

    raise RuntimeError("Не найден совместимый релиз ROM на GitHub")


def download_latest_rom(dest_cache: str, base_pct: int = 8, span_pct: int = 20) -> str:
    """Download the latest verified ROM release (or pinned fallback) before extraction."""
    try:
        meta = _resolve_latest_rom_release()
        emit(f"[ROM] Выбран релиз GitHub v{meta['version']} ({meta['name']}).")
    except Exception as ex:
        emit(f"[ROM] GitHub Releases недоступен ({str(ex)[:100]}), используем резервную сборку v{PINNED_ROM_VERSION}.")
        meta = {
            "version": PINNED_ROM_VERSION,
            "name": PINNED_ROM_NAME,
            "url": f"https://github.com/Olegu621/OPENSTATION/releases/download/{PINNED_ROM_TAG}/{PINNED_ROM_NAME}",
            "size": PINNED_ROM_SIZE,
            "sha256": PINNED_ROM_SHA256,
            "files": dict(ROM_FILES),
            "manifest_bytes": None,
        }

    name = meta["name"]
    url = meta["url"]
    expected = meta["sha256"]
    expected_size = meta["size"]
    expected_files = meta["files"]
    manifest_bytes = meta.get("manifest_bytes")

    os.makedirs(dest_cache, exist_ok=True)
    extract_dir = os.path.join(dest_cache, f"rom_v{meta['version']}_verified")
    cached_manifest_path = os.path.join(extract_dir, "rom_manifest.json")
    try:
        if manifest_bytes is not None:
            if not os.path.isfile(cached_manifest_path):
                raise RuntimeError("cache manifest missing")
            with open(cached_manifest_path, "rb") as mf:
                if mf.read() != manifest_bytes:
                    raise RuntimeError("cache manifest changed")
        verify_rom(extract_dir)
        return extract_dir
    except (OSError, RuntimeError):
        pass

    part = os.path.join(dest_cache, name + ".part")
    digest = hashlib.sha256()
    request = urllib.request.Request(url, headers={"User-Agent": "OpenStationFlasher/1.6"})
    downloaded = 0
    with _open_rom_url(request, timeout=60) as response, open(part, "wb") as output:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            downloaded += len(block)
            if downloaded > expected_size:
                raise RuntimeError("Архив больше ожидаемого размера")
            digest.update(block)
            output.write(block)
            emit_progress(base_pct + int(downloaded * span_pct / expected_size),
                          f"Загрузка прошивки: {downloaded // (1024*1024)} из {expected_size // (1024*1024)} МБ")
    if downloaded != expected_size or digest.hexdigest() != expected:
        raise RuntimeError("Контрольная сумма архива прошивки не совпала")
    os.makedirs(extract_dir, exist_ok=True)
    with zipfile.ZipFile(part) as archive:
        for filename, (exp_sz, _) in expected_files.items():
            entry = archive.getinfo(filename)
            if entry.file_size != exp_sz:
                raise RuntimeError(f"Неверный размер {filename} в архиве")
            with archive.open(entry) as source, open(os.path.join(extract_dir, filename), "wb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
    if manifest_bytes is not None:
        with open(cached_manifest_path, "wb") as mf:
            mf.write(manifest_bytes)
    elif os.path.isfile(cached_manifest_path):
        os.remove(cached_manifest_path)
    verify_rom(extract_dir)
    os.remove(part)
    return extract_dir


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
            try:
                soc.write_download_block(seq, data, timeout=50000)
            except Exception as ex:
                raise RuntimeError(
                    f"Запись {name} прервана на блоке {seq}, смещение {written}: {ex}. "
                    "Состояние раздела неизвестно."
                ) from ex
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
    if is_uboot_alive(timeout=8):
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
            if is_uboot_alive(timeout=6):
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


ROM_FILES = {
    "vbmeta.img": (2097152, "9b5a0a3e9854d7ab67cf838e03a713063cbcb0727c253f6cb4bceaab3045efaa"),
    "vendor.img": (536870912, "8a7d8f46446d90b7060afc1d634308541e84332e233d09df49517d2d57b2c406"),
    "system.img": (1551892480, "f0806e1396b24993b9436509a6643b8b292f225e3685f4a784d32c40d1183519"),
}

BASE_PARTITION_FILES = {
    "boot.img": (16777216, "d4d761b2dc1437a069363c5c8fa1415c4d286ead67bcbbc0ec7ab52a543c2261"),
    "dtbo.img": (8388608, "29a07bd6132186d223dfae0f7be4a66aa22f05a00b5e3f4df40c63c4c939f4dd"),
    "odm.img": (134217728, "5fe65b2dd1cadd01fc9220db62d95c587b1333c843dba2a32d39afa7e90c3cc4"),
    "product.img": (134217728, "809ff747c9926910dc800741b6222d7465bac7e8742d8bd62860ad50ba0dbb03"),
}
BASE_PARTITION_ARCHIVE = "OpenStationMax-base-1.99-partitions.zip"
BASE_PARTITION_ARCHIVE_SHA256 = "345cfcb654324fbaea5951ed8984e5dafd21e84acce4f63acc906cb0c5f81f19"


def prepare_base_partitions(cache_dir):
    """Extract the bundled 1.99 images and verify every image before any flash write."""
    archive_path = os.path.join(SCRIPT_DIR, BASE_PARTITION_ARCHIVE)
    digest = hashlib.sha256()
    with open(archive_path, "rb") as src:
        for block in iter(lambda: src.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != BASE_PARTITION_ARCHIVE_SHA256:
        raise RuntimeError("Неверная контрольная сумма архива базовых разделов; запись не начата")
    os.makedirs(cache_dir, exist_ok=True)
    paths = {}
    with zipfile.ZipFile(archive_path) as archive:
        if sorted(archive.namelist()) != sorted(BASE_PARTITION_FILES):
            raise RuntimeError("Неверный состав базовых разделов; запись не начата")
        for name, (size, expected) in BASE_PARTITION_FILES.items():
            if archive.getinfo(name).file_size != size:
                raise RuntimeError(f"Неверный размер {name}; запись не начата")
            path = os.path.join(cache_dir, name)
            tmp = path + ".part"
            digest = hashlib.sha256()
            try:
                with archive.open(name) as src, open(tmp, "wb") as dst:
                    for block in iter(lambda: src.read(1024 * 1024), b""):
                        digest.update(block)
                        dst.write(block)
                if os.path.getsize(tmp) != size or digest.hexdigest() != expected:
                    raise RuntimeError(f"Контрольная сумма {name} не совпала; запись не начата")
                os.replace(tmp, path)
            finally:
                if os.path.exists(tmp):
                    os.remove(tmp)
            paths[name] = path
    return paths


def verify_rom(rom_dir):
    manifest_path = os.path.join(rom_dir, "rom_manifest.json")
    if os.path.isfile(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as mf:
            manifest = json.load(mf)
        expected_map = _parse_rom_manifest_files(manifest)
    else:
        expected_map = ROM_FILES

    for name, (size, expected) in expected_map.items():
        path = os.path.join(rom_dir, name)
        if not os.path.isfile(path) or os.path.getsize(path) != size:
            raise RuntimeError(f"Неверный размер или отсутствует {name}; запись не начата")
        digest = hashlib.sha256()
        with open(path, "rb") as f:
            for block in iter(lambda: f.read(1024 * 1024), b""):
                digest.update(block)
        actual = digest.hexdigest()
        if actual != expected:
            raise RuntimeError(
                f"Не совпадает SHA-256 {name} (ожидалось {expected[:12]}, получено {actual[:12]}); "
                "запись не начата"
            )


def verify_base_compatibility(rom_dir):
    manifest_path = os.path.join(rom_dir, "rom_manifest.json")
    if not os.path.isfile(manifest_path):
        # verify_rom has already required exact hashes of the pinned 1.6.3 ROM.
        return
    with open(manifest_path, "r", encoding="utf-8-sig") as mf:
        manifest = json.load(mf)
    if manifest.get("device") != "yandexstation_2" or str(manifest.get("baseSource", "")).strip().lower() != "yaosx 1.99":
        raise RuntimeError("База ROM не подтверждена как YAOSX 1.99 для Станции Макс; запись не начата")


def run_after_ubt():
    batch = os.path.join(SCRIPT_DIR, "after_ubt.bat")
    if not os.path.isfile(batch):
        raise RuntimeError("Не найден обязательный after_ubt.bat")
    env = os.environ.copy()
    env["OPENSTATION_ENGINE"] = sys.executable
    if not getattr(sys, "frozen", False):
        env["OPENSTATION_ENGINE_SCRIPT"] = os.path.abspath(__file__)
    else:
        env.pop("OPENSTATION_ENGINE_SCRIPT", None)
    process = subprocess.Popen(["cmd.exe", "/d", "/c", "after_ubt.bat"],
                               cwd=SCRIPT_DIR, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, **hidden_subprocess_kwargs())
    try:
        output, _ = process.communicate(timeout=240)
    except subprocess.TimeoutExpired:
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       **hidden_subprocess_kwargs())
        process.communicate()
        raise RuntimeError("after_ubt.bat не завершился вовремя; состояние станции неизвестно")
    if output:
        emit(safe_decode(output))
    if process.returncode == 1:
        return True
    if process.returncode != 0:
        raise RuntimeError("Не удалось выполнить after_ubt.bat: код %s" % process.returncode)
    return False


def ensure_burn_mode() -> bool:
    if probe_burn_mode(timeout=8):
        return True
    info = check_usb_device()
    pref_svc = info.get("usb_service", "")
    observed = probe_usb_identify(pref_svc)
    pw_err = _bootrom_password_error(observed)
    if pw_err:
        emit(f"[AUTH REQUIRED] {pw_err}")
        return False
    if not (observed.get("rom") == (3, 2) and observed.get("stage") == (0, 0)):
        emit("[USB] Не удалось подтвердить начальный режим станции; загрузка не начата.")
        return False

    warn_conflicting_usb_tools()

    def _wait_for_burn_after_loader() -> bool:
        for attempt in range(15):
            time.sleep(1)
            if probe_burn_mode(timeout=3):
                return True
            # Give Windows time to re-enumerate before treating BootROM as a failed loader.
            if attempt >= 4:
                state = probe_usb_identify(pref_svc)
                if state.get("mode") == "bootrom":
                    break
        return False

    for image in ("aml_00053.img", "aml_bundle.img", "aml.img"):
        image_path = os.path.join(SCRIPT_DIR, image)
        if not os.path.isfile(image_path):
            continue
        # Only try a loader while identify confirms BootROM, never on a live U-Boot.
        observed = probe_usb_identify(pref_svc)
        if not (observed.get("rom") == (3, 2) and observed.get("stage") == (0, 0)):
            return probe_burn_mode(timeout=8)
        emit("[USB] Подготовка станции к записи (%s)..." % image)
        cmd = [sys.executable]
        if not getattr(sys, "frozen", False):
            cmd.extend(["-u", os.path.abspath(__file__)])
        cmd.extend(["--usb-boot-step", image_path, "--usb-service", pref_svc])
        failed_rc = 0
        try:
            result = subprocess.run(cmd, cwd=SCRIPT_DIR, stdin=subprocess.DEVNULL,
                                    timeout=60, **hidden_subprocess_kwargs())
            failed_rc = result.returncode
            emit("[USB] Подготовка завершилась с кодом %s; ожидаем устройство." % result.returncode)
        except subprocess.TimeoutExpired:
            failed_rc = 1
            emit("[USB] Подготовка не завершилась вовремя; проверяем состояние устройства.")
        if failed_rc == EXIT_BOOTROM_PASSWORD:
            emit("[ОСТАНОВКА] BootROM ожидает успешную парольную авторизацию. Передача BL2 остановлена.")
            return False
        if _wait_for_burn_after_loader():
            return True
        if failed_rc != 0:
            state = probe_usb_identify(pref_svc)
            if state.get("rom") == (3, 2) and state.get("stage") == (0, 0):
                emit("[USB] Сброс USB-порта после сбоя libusb перед следующей попыткой...")
                reset_usb_device(pref_svc)
                if os.name == "nt" and is_admin():
                    reenumerate_worldcup_device()
                time.sleep(2.0)

    # Strategy 2 fallback: native update.exe bl2_boot (only if BootROM is still confirmed)
    try:
        update_exe = get_update_exe()
    except FileNotFoundError:
        update_exe = ""
    if update_exe:
        for image in ("aml_bundle.img", "aml_00053.img", "aml.img"):
            image_path = os.path.join(SCRIPT_DIR, image)
            if not os.path.isfile(image_path):
                continue
            observed = probe_usb_identify(pref_svc)
            if not (observed.get("rom") == (3, 2) and observed.get("stage") == (0, 0)):
                return probe_burn_mode(timeout=8)
            emit("[USB] Резервный загрузчик update.exe bl2_boot (%s)..." % image)
            try:
                p = subprocess.run(
                    [update_exe, "bl2_boot", image_path],
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
                if p.returncode == 0 and not _update_output_failed(out, p.returncode):
                    if _wait_for_burn_after_loader():
                        return True
            except Exception as ex:
                emit(f"  [!] update.exe bl2_boot: {str(ex)[:140]}")
            time.sleep(1.5)

    if os.name == "nt" and not is_admin():
        emit("[USB] Подсказка: запустите OpenStationFlasher.exe от имени Администратора и закройте USB Burning Tool.")
    emit("[USB] Переподключите питание 19В и сервисный Type-C (замкните 6-й контакт на GND), желательно в порт USB 2.0.")
    return False


MISC_SHA256 = {
    "misc.img": "9e52b0fc6c40a4db81193f4066c09b2c2d41fe6aa1f0b9a4c521d7422b80b6c8",
    "misc_00053.img": "4d85187efb08dcdc49202a73e8e37e5e297326a89f44ce11c9173bc906bbcae9",
}


def resolve_misc_image(revision="auto"):
    """Выбирает и проверяет образ misc в зависимости от ревизии платы."""
    rev = (revision or "auto").strip()
    candidates = ("misc.img", "misc_00053.img") if rev in ("0008", "00052") else ("misc_00053.img", "misc.img")
    for name in candidates:
        path = os.path.join(SCRIPT_DIR, name)
        if os.path.isfile(path):
            expected = MISC_SHA256[name]
            with open(path, "rb") as misc_file:
                if hashlib.sha256(misc_file.read()).hexdigest() != expected:
                    raise RuntimeError(f"Контрольная сумма {name} не совпала; запись не начата")
            return path
    raise RuntimeError("Отсутствует завершающий образ misc; запись не начата")


def run_flash(args):
    emit_progress(2, "Проверка подключения и драйвера...")
    if not check_worldcup_driver():
        if not is_admin():
            raise RuntimeError("Требуются права администратора для установки драйвера WorldCup.")
        if not install_driver():
            raise RuntimeError("Не удалось установить драйвер WorldCup.")

    emit_progress(4, "Проверка USB-соединения...")
    if not ensure_burn_mode():
        raise RuntimeError("Не удалось подготовить станцию к записи. Данные выше помогут определить причину; запись не начата.")

    emit_progress(6, "Подготовка актуальной прошивки...")
    rom_dir = args.rom_dir or ""
    if rom_dir and not os.path.isdir(rom_dir):
        raise FileNotFoundError("Указанная папка прошивки не существует")
    if not rom_dir:
        cache_dir = os.path.join(os.environ.get("LOCALAPPDATA", SCRIPT_DIR),
                                 "OpenStation", "rom_cache")
        rom_dir = download_latest_rom(cache_dir, base_pct=6, span_pct=18)

    for req in ("vbmeta.img", "vendor.img", "system.img"):
        fp = os.path.join(rom_dir, req)
        if not os.path.isfile(fp):
            raise FileNotFoundError(f"В папке ROM отсутствует обязательный образ: {req}")

    emit_progress(24, "Проверка контрольных сумм прошивки...")
    verify_rom(rom_dir)
    verify_base_compatibility(rom_dir)
    misc_path = resolve_misc_image(getattr(args, "revision", "auto"))
    base_dir = os.path.join(os.environ.get("LOCALAPPDATA", SCRIPT_DIR), "OpenStation", "base_199")
    base = prepare_base_partitions(base_dir)

    emit_progress(35, "Инициализация eMMC...")
    aml_bulkcmd("mmc dev 1")

    flash_partition("boot", base["boot.img"], 35, 37)
    flash_partition("dtbo", base["dtbo.img"], 37, 38)
    flash_partition("odm", base["odm.img"], 38, 40)
    flash_partition("product", base["product.img"], 40, 42)
    flash_partition("vbmeta", os.path.join(rom_dir, "vbmeta.img"), 42, 43)
    flash_partition("vendor", os.path.join(rom_dir, "vendor.img"), 43, 55)
    flash_partition("system", os.path.join(rom_dir, "system.img"), 55, 92)

    misc_error = None
    try:
        flash_partition("misc", misc_path, 92, 95)
    except Exception as ex:
        misc_error = ex
        emit("[ERROR] Не удалось записать misc: %s; выполняю оставшиеся команды after_ubt." % ex)

    emit_progress(95, "Обязательный завершающий этап after_ubt...")
    after_error = None
    after_warning = False
    try:
        after_warning = run_after_ubt()
    except Exception as ex:
        after_error = ex
    if misc_error or after_error:
        failures = []
        if misc_error: failures.append("misc: " + str(misc_error))
        if after_error: failures.append("after_ubt: " + str(after_error))
        raise RuntimeError("; ".join(failures))

    if after_warning:
        emit_progress(100, "Запись разделов завершена")
        emit("[DONE] Запись разделов завершена. Загрузка станции не проверена.")
    else:
        emit_progress(100, "Запись и завершающие команды выполнены")
        emit("[DONE] Команды выполнены. Загрузка станции ещё не проверена.")
    return bool(after_warning)


def main():
    configure_stdio()
    parser = argparse.ArgumentParser(description="OpenStation Max USB Service Port Flasher")
    parser.add_argument("--bulkcmd", default="", help=argparse.SUPPRESS)
    parser.add_argument("--check", action="store_true", help="Проверить драйвер, USB и локальный ROM (JSON)")
    parser.add_argument("--install-driver", action="store_true", help="Установить драйвер WorldCup")
    parser.add_argument("--flash", action="store_true", help="Прошить станцию полностью автоматически")
    parser.add_argument("--rom-dir", default="", help="Путь к локальной папке с vbmeta.img/vendor.img/system.img")
    parser.add_argument("--download-latest", action="store_true", help="Принудительно скачать свежий ROM с GitHub")
    parser.add_argument("--keep-data", action="store_true", help="Не стирать пользовательские данные (data/cache)")
    parser.add_argument("--revision", default="auto", choices=["auto", "0008", "00052", "00053"])
    parser.add_argument("--usb-boot-step", default="", help=argparse.SUPPRESS)
    parser.add_argument("--usb-service", default="", help=argparse.SUPPRESS)
    args = parser.parse_args()

    try:
        if args.bulkcmd:
            emit(aml_bulkcmd(args.bulkcmd))
            return 0
        if args.usb_boot_step:
            return run_usb_boot_step_inproc(args.usb_boot_step, args.usb_service)
        if args.check:
            run_check()
            return 0
        if args.install_driver:
            ok = install_driver()
            return 0 if ok else 1
        if args.flash:
            return 3 if run_flash(args) else 0
        parser.print_help()
        return 0
    except Exception as ex:
        emit(f"[ERROR] {ex}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
