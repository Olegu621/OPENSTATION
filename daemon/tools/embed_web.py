#!/usr/bin/env python3
import os
import sys

MIME_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
}

def main():
    if len(sys.argv) != 3:
        print("Usage: embed_web.py <web_ui_dir> <out_header>")
        sys.exit(1)

    web_dir = sys.argv[1]
    out_hdr = sys.argv[2]
    os.makedirs(os.path.dirname(out_hdr) or ".", exist_ok=True)

    files = []
    for root, _, names in os.walk(web_dir):
        for n in sorted(names):
            full = os.path.join(root, n)
            rel = os.path.relpath(full, web_dir).replace("\\", "/")
            ext = os.path.splitext(n)[1].lower()
            mime = MIME_TYPES.get(ext, "application/octet-stream")
            with open(full, "rb") as f:
                data = f.read()
            files.append((rel, mime, data))

    with open(out_hdr, "w", encoding="utf-8") as out:
        out.write("#pragma once\n#include <cstddef>\n#include <cstdint>\n#include <string_view>\n\nnamespace openstation::web_assets {\n\n")
        out.write("struct Asset {\n    const char* path;\n    const char* mime;\n    const uint8_t* data;\n    size_t size;\n};\n\n")
        for idx, (rel, mime, data) in enumerate(files):
            out.write(f"static const uint8_t asset_data_{idx}[] = {{\n")
            for i in range(0, len(data), 16):
                chunk = ", ".join(f"0x{b:02x}" for b in data[i:i+16])
                out.write(f"    {chunk},\n")
            if not data:
                out.write("    0x00\n")
            out.write("};\n\n")

        out.write("static const Asset ALL_ASSETS[] = {\n")
        for idx, (rel, mime, data) in enumerate(files):
            out.write(f'    {{"/{rel}", "{mime}", asset_data_{idx}, {len(data)}}},\n')
        out.write("};\n\n")
        out.write("inline const Asset* find(std::string_view req_path) {\n")
        out.write('    if (req_path == "/" || req_path.empty()) req_path = "/index.html";\n')
        out.write("    for (const auto& a : ALL_ASSETS) {\n")
        out.write("        if (req_path == a.path) return &a;\n")
        out.write("    }\n")
        out.write("    return nullptr;\n")
        out.write("}\n\n} // namespace openstation::web_assets\n")

if __name__ == "__main__":
    main()
