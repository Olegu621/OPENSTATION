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
import threading
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
    info = {"usb_connected": False, "usb_stage": "none", "usb_desc": "", "usb_service": ""}
    if os.name == "nt":
        try:
            ps = (
                "Get-CimInstance Win32_PnPEntity -ErrorAction SilentlyContinue | "
                "Where-Object { $_.DeviceID -like '*VID_1B8E&PID_C003*' } | "
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
        cwd=SCRIPT_DIR,
        capture_output=True,
        timeout=timeout,
        **hidden_subprocess_kwargs(),
    )
    out = safe_decode((p.stdout or b"") + (p.stderr or b"")).strip()
    for ln in out.splitlines():
        s = ln.strip()
        if s:
            emit(f"  [USB] {s}")
    if _update_output_failed(out, p.returncode) and not ignore_error:
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


def _byte_at(buf, idx: int) -> int:
    try:
        v = buf[idx]
    except Exception:
        return 0
    try:
        return int(v)
    except (TypeError, ValueError):
        return ord(v)


def classify_usb_id(socid) -> dict:
    """Classify an Amlogic identify reply.

    A genuine G12 BootROM answers ``ROM 3.2 / Stage 0.0``. A station that is
    already running the temporary U-Boot (WorldCup burn mode) answers with
    different bytes (seen in the wild: ``ROM 0.7 / Stage 0.16``). Sending
    BootROM-only requests (WR_LARGE_MEM) to a live U-Boot kills the session
    with ``libusb0 ... Устройство не опознано``, so this distinction is exact.
    """
    info = {"rom": (0, 0), "stage": (0, 0), "mode": "unknown"}
    if socid is None or len(socid) < 4:
        return info
    rom = (_byte_at(socid, 0), _byte_at(socid, 1))
    stage = (_byte_at(socid, 2), _byte_at(socid, 3))
    info["rom"] = rom
    info["stage"] = stage
    if rom[0] == 3 and stage[1] == 0:
        info["mode"] = "bootrom"
    else:
        info["mode"] = "uboot"
    return info


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


def is_uboot_alive(timeout: int = 8) -> bool:
    """Return True if U-Boot WorldCup burn mode accepts a bulkcmd."""
    try:
        update = get_update_exe()
        p = subprocess.run(
            [update, "bulkcmd", "mmc dev 1"],
            cwd=SCRIPT_DIR,
            capture_output=True,
            timeout=timeout,
            **hidden_subprocess_kwargs(),
        )
        out = safe_decode((p.stdout or b"") + (p.stderr or b""))
        return not _update_output_failed(out, p.returncode)
    except Exception:
        return False


def probe_usb_identify(preferred_service: str = "") -> dict:
    """Read-only pyusb identify (never writes anything to the station)."""
    res = {"present": False, "mode": "none", "rom": (0, 0), "stage": (0, 0), "backend": ""}
    try:
        from pyamlboot_local import proto
    except Exception:
        return res
    for be_name, backend in get_pyusb_backends(preferred_service):
        soc = None
        try:
            soc = proto.AmlogicSoC(backend=backend)
            try:
                soc.dev.set_configuration()
            except Exception:
                pass
            info = classify_usb_id(soc.identify())
            res.update(
                present=True,
                mode=info["mode"],
                rom=info["rom"],
                stage=info["stage"],
                backend=be_name,
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


def run_usb_boot_step_inproc(img_path: str, preferred_service: str = "") -> int:
    """Single isolated BootROM -> U-Boot transfer attempt (always releases handle on exit)."""
    from pyamlboot_local import proto

    with open(img_path, "rb") as f:
        data = f.read()

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
            info = classify_usb_id(socid)
            rom_maj, rom_min = info["rom"]
            stage_maj, stage_min = info["stage"]
            emit(f"  [{be_name}] USB ID: ROM {rom_maj}.{rom_min}, Stage {stage_maj}.{stage_min}")
            if info["mode"] == "uboot":
                # BootROM commands (WR_LARGE_MEM) hit a live U-Boot and drop the
                # USB link with "Устройство не опознано" - never do that.
                emit("[OK] Станция уже в режиме U-Boot — повторная загрузка BL2 не нужна.")
                return 2
            if info["mode"] != "bootrom":
                raise RuntimeError("устройство не ответило на identify (USB ещё инициализируется)")

            soc.writeLargeMemory(0xFFFA0000, data[0:0x10000], 4096)
            soc.run(0xFFFA0000)
            time.sleep(2.0)

            prev_len = -1
            prev_off = -1
            seq = 0
            while True:
                length, offset = soc.getBootAMLC()
                if length == prev_len and offset == prev_off:
                    emit(f"[OK] Загружен {os.path.basename(img_path)} через {be_name} ({seq} блоков AMLC).")
                    return 0
                prev_len = length
                prev_off = offset
                soc.writeAMLCData(seq, offset, data[offset : offset + length])
                seq += 1
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
        if p.returncode == 0 and wait_uboot_alive():
            return True

    if os.name == "nt" and not is_admin():
        emit("[!] Подсказка: попробуйте запустить OpenStationFlasher.exe от имени Администратора и переподключить Type-C кабель.")
    emit("[!] U-Boot не запустился: отключите Type-C и питание 19В, замкните 6-й контакт на GND и подключите кабель снова.")
    return False


def download_latest_rom(dest_cache: str, base_pct: int = 8, span_pct: int = 20) -> str:
    """Download the pinned v1.5.1 release and verify it before extraction."""
    name = "OpenStationMax-ROM-v1.5.1.zip"
    url = "https://github.com/Olegu621/OPENSTATION/releases/download/v1.5.1/" + name
    expected = "57e8273af5d28e96ab79ca3a4d82eec0a730174ece23a2959427f5675d1a20b5"
    expected_size = 760870540
    os.makedirs(dest_cache, exist_ok=True)
    extract_dir = os.path.join(dest_cache, "rom_v1.5.1_verified")
    try:
        verify_rom(extract_dir)
        return extract_dir
    except (OSError, RuntimeError):
        pass
    part = os.path.join(dest_cache, name + ".part")
    digest = hashlib.sha256()
    request = urllib.request.Request(url, headers={"User-Agent": "OpenStationFlasher/1.5.4"})
    downloaded = 0
    with urllib.request.urlopen(request, timeout=60) as response, open(part, "wb") as output:
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
        for filename in ROM_FILES:
            entry = archive.getinfo(filename)
            if entry.file_size != ROM_FILES[filename][0]:
                raise RuntimeError(f"Неверный размер {filename} в архиве")
            with archive.open(entry) as source, open(os.path.join(extract_dir, filename), "wb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
    verify_rom(extract_dir)
    os.remove(part)
    return extract_dir


def flash_partition(name: str, img_path: str, pct_start: int, pct_end: int) -> None:
    update = get_update_exe()
    emit(f"Запись раздела {name} ({os.path.getsize(img_path) / (1024 * 1024):.1f} МБ)...")
    process = subprocess.Popen([update, "partition", name, img_path], cwd=SCRIPT_DIR,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               bufsize=0, **hidden_subprocess_kwargs())
    errors = []

    def read_output():
        pending = b""
        last = -1
        try:
            while True:
                chunk = process.stdout.read(256)
                if not chunk:
                    break
                pending += chunk
                while b"\n" in pending or b"\r" in pending:
                    match = re.search(b"[\r\n]", pending)
                    raw, pending = pending[:match.start()], pending[match.end():]
                    line = safe_decode(raw).strip()
                    if not line:
                        continue
                    if _update_output_failed(line, 0):
                        errors.append(line)
                    progress = re.findall(r"(\d{1,3})%", line)
                    if progress and min(100, int(progress[-1])) != last:
                        last = min(100, int(progress[-1]))
                        emit_progress(pct_start + int((pct_end - pct_start) * last / 100),
                                      f"Запись {name}: {last}%")
                    else:
                        emit("  [USB] " + line)
            if pending and _update_output_failed(safe_decode(pending), 0):
                errors.append(safe_decode(pending))
        except Exception as exc:
            errors.append(str(exc))

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    try:
        code = process.wait(timeout=2700)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
        reader.join(timeout=5)
        process.stdout.close()
        raise RuntimeError(f"Время записи {name} истекло; состояние станции неизвестно")
    reader.join(timeout=10)
    if not reader.is_alive():
        process.stdout.close()
    if reader.is_alive() or code != 0 or errors:
        raise RuntimeError(f"Ошибка записи {name} (код {code}): {'; '.join(errors)[:300]}")
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
    "vendor.img": (536870912, "9ee367d61541e7dd237474d5a3d217496192014605e6f34ad08dd8592ac5d420"),
    "system.img": (1551892480, "25e861a7eb86cfc269f9334b92f1373a030e9c08bd39e4ea1fe203a8cb0f33f9"),
}


def verify_rom(rom_dir):
    for name, (size, expected) in ROM_FILES.items():
        path = os.path.join(rom_dir, name)
        if not os.path.isfile(path) or os.path.getsize(path) != size:
            raise RuntimeError(f"Неверный размер или отсутствует {name}; запись не начата")
        digest = hashlib.sha256()
        with open(path, "rb") as f:
            for block in iter(lambda: f.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != expected:
            raise RuntimeError(f"Не совпадает SHA-256 {name}; запись не начата")


def run_after_ubt():
    batch = os.path.join(SCRIPT_DIR, "after_ubt.bat")
    if not os.path.isfile(batch):
        raise RuntimeError("Не найден обязательный after_ubt.bat")
    process = subprocess.Popen(["cmd.exe", "/d", "/c", "after_ubt.bat"],
                               cwd=SCRIPT_DIR, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, **hidden_subprocess_kwargs())
    try:
        output, _ = process.communicate(timeout=180)
    except subprocess.TimeoutExpired:
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       **hidden_subprocess_kwargs())
        process.communicate()
        raise RuntimeError("after_ubt.bat не завершился вовремя; состояние станции неизвестно")
    if output:
        emit(safe_decode(output))
    if process.returncode != 0:
        raise RuntimeError("Ошибка after_ubt.bat: код %s" % process.returncode)


def run_flash(args):
    emit_progress(2, "Проверка подключения и драйвера...")
    if not check_worldcup_driver():
        if not is_admin():
            raise RuntimeError("Требуются права администратора для установки драйвера WorldCup.")
        if not install_driver():
            raise RuntimeError("Не удалось установить драйвер WorldCup.")

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

    emit_progress(25, "Поиск станции...")

    emit_progress(28, "Проверка ответа станции...")
    if not is_uboot_alive(timeout=8):
        raise RuntimeError("Устройство не отвечает на команды update.exe. Запись не начата.")

    emit_progress(35, "Инициализация eMMC...")
    aml_bulkcmd("mmc dev 1")

    flash_partition("vbmeta", os.path.join(rom_dir, "vbmeta.img"), 38, 41)
    flash_partition("vendor", os.path.join(rom_dir, "vendor.img"), 41, 52)
    flash_partition("system", os.path.join(rom_dir, "system.img"), 52, 92)

    emit_progress(95, "Обязательный завершающий этап after_ubt...")
    run_after_ubt()

    emit_progress(100, "Запись и завершающие команды выполнены")
    emit("[DONE] Команды выполнены. Загрузка станции ещё не проверена.")


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
    parser.add_argument("--usb-boot-step", default="", help=argparse.SUPPRESS)
    parser.add_argument("--usb-service", default="", help=argparse.SUPPRESS)
    args = parser.parse_args()

    try:
        if args.usb_boot_step:
            return run_usb_boot_step_inproc(args.usb_boot_step, args.usb_service)
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
