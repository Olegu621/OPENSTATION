#!/usr/bin/env python3
"""Validates every plugin under plugins/<id>/plugin.json and builds plugins/index.json."""

import hashlib
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLUGINS_DIR = os.path.join(ROOT, "plugins")
INDEX_PATH = os.path.join(PLUGINS_DIR, "index.json")
ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{1,47}$")
TOOL_RE = re.compile(r"^[a-z][a-z0-9_]{2,47}$")
ALLOWED_RUNNERS = {"http", "wol", "mqtt", "station", "shell"}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_safe_rel_path(rel: str) -> bool:
    if not rel or len(rel) > 160 or rel.startswith("/") or "\\" in rel or ".." in rel:
        return False
    return bool(re.match(r"^[A-Za-z0-9._/-]+$", rel))


def validate_plugin(folder_name: str, mf_path: str) -> dict:
    with open(mf_path, "rb") as f:
        raw = f.read()
    mf = json.loads(raw.decode("utf-8"))
    if not isinstance(mf, dict):
        raise ValueError(f"{mf_path}: manifest must be JSON object")
    pid = mf.get("id", "")
    if pid != folder_name:
        raise ValueError(f"{mf_path}: id '{pid}' must match folder name '{folder_name}'")
    if not ID_RE.match(pid):
        raise ValueError(f"{mf_path}: invalid id '{pid}'")
    for req in ("name", "version", "description"):
        if not isinstance(mf.get(req), str) or not mf[req].strip():
            raise ValueError(f"{mf_path}: missing non-empty '{req}'")

    tools = mf.get("tools", [])
    if not isinstance(tools, list):
        raise ValueError(f"{mf_path}: tools must be a list")
    tool_names = []
    for t in tools:
        tn = t.get("name", "")
        if not TOOL_RE.match(tn):
            raise ValueError(f"{mf_path}: invalid tool name '{tn}'")
        runner = t.get("runner")
        if not isinstance(runner, dict) or runner.get("type") not in ALLOWED_RUNNERS:
            raise ValueError(f"{mf_path}: tool '{tn}' has invalid runner")
        tool_names.append(tn)

    actions = mf.get("actions", [])
    if not isinstance(actions, list):
        raise ValueError(f"{mf_path}: actions must be a list")
    for a in actions:
        runner = a.get("runner")
        if not isinstance(runner, dict) or runner.get("type") not in ALLOWED_RUNNERS:
            raise ValueError(f"{mf_path}: action '{a.get('id')}' has invalid runner")

    files_meta = mf.get("files", [])
    if files_meta:
        plugin_dir = os.path.dirname(mf_path)
        for entry in files_meta:
            rel = entry.get("path", "")
            if not is_safe_rel_path(rel):
                raise ValueError(f"{mf_path}: unsafe file path '{rel}'")
            fpath = os.path.join(plugin_dir, rel)
            if not os.path.isfile(fpath):
                raise ValueError(f"{mf_path}: declared file missing: {rel}")
            with open(fpath, "rb") as bf:
                actual_sha = sha256_bytes(bf.read())
            if entry.get("sha256") and entry["sha256"].lower() != actual_sha:
                raise ValueError(f"{mf_path}: sha256 mismatch for {rel}")

    return {
        "id": pid,
        "name": mf["name"],
        "version": mf["version"],
        "author": mf.get("author", "Community"),
        "category": mf.get("category", "Утилиты"),
        "icon": mf.get("icon", "🧩"),
        "description": mf["description"],
        "homepage": mf.get("homepage", f"https://github.com/Olegu621/OPENSTATION/tree/main/plugins/{pid}"),
        "manifestUrl": f"https://raw.githubusercontent.com/Olegu621/OPENSTATION/main/plugins/{pid}/plugin.json",
        "manifestSha256": sha256_bytes(raw),
        "tools": tool_names,
        "hasSettings": bool(mf.get("settingsSchema")),
        "actionsCount": len(actions),
        "hasVoiceCommands": bool(mf.get("voiceCommands")),
        "hasSchedule": bool(mf.get("schedule")),
    }


def build_index() -> dict:
    entries = []
    if os.path.isdir(PLUGINS_DIR):
        for name in sorted(os.listdir(PLUGINS_DIR)):
            pdir = os.path.join(PLUGINS_DIR, name)
            mf_path = os.path.join(pdir, "plugin.json")
            if os.path.isdir(pdir) and os.path.isfile(mf_path):
                entries.append(validate_plugin(name, mf_path))
    return {
        "schemaVersion": 1,
        "plugins": entries,
    }


def main() -> int:
    index = build_index()
    with open(INDEX_PATH, "w", encoding="utf-8", newline="\n") as out:
        json.dump(index, out, ensure_ascii=False, indent=2)
        out.write("\n")
    print(f"Generated plugins/index.json with {len(index['plugins'])} plugins.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
