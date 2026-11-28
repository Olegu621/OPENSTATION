#!/usr/bin/env python3
"""
Renders daemon/web_ui/favicon.svg into the PNG favicon fallbacks with a
headless Chrome or Edge, so the PNGs are exactly what the browser draws for
the SVG. Set CHROME=<path to chrome.exe> if the browser lives elsewhere.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
SVG_PATH = ROOT_DIR / "daemon" / "web_ui" / "favicon.svg"

# (output name, size px, keep the rounded tile corners)
TARGETS = [
    ("favicon-32.png", 32, True),
    ("apple-touch-icon.png", 180, False),  # iOS masks the icon itself, so corners stay square
]

BROWSER_CANDIDATES = [
    Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"),
    Path(os.environ.get("LOCALAPPDATA", "")) / "Google" / "Chrome" / "Application" / "chrome.exe",
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
]
BROWSER_NAMES = ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "msedge"]


def find_browser() -> str:
    env = os.environ.get("CHROME")
    if env and Path(env).is_file():
        return env
    for path in BROWSER_CANDIDATES:
        if path.is_file():
            return str(path)
    for name in BROWSER_NAMES:
        found = shutil.which(name)
        if found:
            return found
    sys.exit("Chrome or Edge not found; set CHROME=<path to chrome.exe>")


def _sub(text: str, old: str, new: str) -> str:
    if old not in text:
        sys.exit(f"favicon.svg: '{old}' not found, update square_corners()")
    return text.replace(old, new)


def square_corners(svg_text: str) -> str:
    """Drop the rounded tile corners and its border stroke: iOS applies its own mask."""
    text = _sub(svg_text, 'rx="14"', 'rx="0"')
    text = _sub(text, 'x="0.5" y="0.5" width="63" height="63"', 'x="0" y="0" width="64" height="64"')
    return _sub(text, ' stroke="#ffffff" stroke-opacity="0.12"', "")


def rmtree_retry(path: Path, attempts: int = 10) -> None:
    """Chrome's child processes outlive the parent for a moment and keep the profile locked."""
    for _ in range(attempts):
        shutil.rmtree(path, ignore_errors=True)
        if not path.exists():
            return
        time.sleep(0.3)


def render(browser: str, svg_text: str, size: int, out_png: Path) -> None:
    # A private profile keeps a running Chrome from hijacking the launch
    profile = Path(tempfile.mkdtemp(prefix="favicon-profile-"))
    with tempfile.TemporaryDirectory() as tmp:
        svg = Path(tmp) / "favicon.svg"
        html = Path(tmp) / "icon.html"
        svg.write_text(svg_text, encoding="utf-8")
        html.write_text(
            "<!doctype html><html><head><style>"
            "html,body{margin:0;background:transparent}"
            f"img{{display:block;width:{size}px;height:{size}px}}"
            '</style></head><body><img src="favicon.svg"></body></html>',
            encoding="utf-8",
        )
        cmd = [
            browser,
            "--headless=new",
            "--disable-gpu",
            "--hide-scrollbars",
            "--no-first-run",
            "--force-device-scale-factor=1",
            "--default-background-color=00000000",
            f"--window-size={size},{size}",
            f"--user-data-dir={profile}",
            f"--screenshot={out_png}",
            html.as_uri(),
        ]
        try:
            subprocess.run(cmd, check=True, capture_output=True, timeout=120)
        finally:
            rmtree_retry(profile)
    if not out_png.is_file() or out_png.stat().st_size == 0:
        sys.exit(f"browser produced no screenshot for {out_png}")


def main() -> None:
    svg_text = SVG_PATH.read_text(encoding="utf-8")
    browser = find_browser()
    for name, size, rounded in TARGETS:
        out_png = SVG_PATH.parent / name
        render(browser, svg_text if rounded else square_corners(svg_text), size, out_png)
        print(f"{name}: {out_png.stat().st_size} bytes")


if __name__ == "__main__":
    main()
