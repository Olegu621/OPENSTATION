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


def is_uboot_alive(timeout: int = 4) -> bool:
    """Return True if station is already running U-Boot WorldCup burn mode and accepts bulkcmd."""
    try:
        update = get_update_exe()
        p = subprocess.run(
            [update, "bulkcmd", "mmc dev 1"],
            cwd=SCRIPT_DIR,
            capture_output=True,
            timeout=timeout,
            **hidden_subprocess_kwargs(),
        )
        return p.returncode == 0
    except Exception:
        return False


def wait_uboot_alive(wait_sec: float = 7.0) -> bool:
    deadline = time.monotonic() + wait_sec
    time.sleep(2.5)
    while time.monotonic() < deadline:
        if is_uboot_alive(timeout=4):
            return True
        time.sleep(0.8)
    return is_uboot_alive(timeout=4)


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
            if len(socid) >= 4:
                rom_maj, rom_min = ord(socid[0]), ord(socid[1])
                stage_maj, stage_min = ord(socid[2]), ord(socid[3])
                emit(f"  [{be_name}] BootROM ID: ROM {rom_maj}.{rom_min}, Stage {stage_maj}.{stage_min}")
                if stage_maj != 0:
                    emit(f"[OK] Станция уже в режиме U-Boot (Stage {stage_maj}.{stage_min}).")
                    return 0

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
            if p.returncode == 0:
                emit("Ожидание инициализации USB-стека U-Boot...")
                if wait_uboot_alive(7.5):
                    emit("[OK] Временный U-Boot готов к приёму команд.")
                    return True
                emit("  [!] Порт не ответил на bulkcmd 'mmc dev 1', пробуем следующий метод...")
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
            if p.returncode == 0:
                if wait_uboot_alive(7.5):
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
        if p.returncode == 0 and wait_uboot_alive(7.5):
            return True

    if os.name == "nt" and not is_admin():
        emit("[!] Подсказка: попробуйте запустить OpenStationFlasher.exe от имени Администратора и переподключить Type-C кабель.")
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
        cwd=SCRIPT_DIR,
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
