#!/usr/bin/env python3
"""
Builds unified seamless OpenStation Max HDMI boot assets:
  1. firmware/bootanimation.zip (960x540 @ 30fps, ZIP_STORED) whose very first
     frame part0/000.png matches U-Boot bootup visually pixel-for-pixel.
  2. firmware/logo.img (AML_RES! v2 container with 16-bit RGB565 bootup BMP)
     built by cloning stock upgrade/sysrecovery resources from stock logo image
     and swapping entry[0] "bootup" with the matching OpenStation Max frame.
"""

from __future__ import annotations

import argparse
import shutil
import struct
import zlib
from pathlib import Path
from PIL import Image

import generate_bootanimation as gba

ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_STOCK_LOGO = Path(
    r"C:\Users\admin\.gemini\antigravity\brain\4ba77531-21c9-4c2e-940f-7ec44630e95a\scratch\logo_dump.img"
)
PART_TOTAL_BYTES = 4 * 1024 * 1024


def rgb888_to_bmp_rgb565(img: Image.Image) -> bytes:
    """
    Encodes a PIL RGB image into a 16-bit BI_BITFIELDS RGB565 BMP (138-byte
    BITMAPV5HEADER matching stock U-Boot logo BMP format, bottom-up scanlines).
    """
    import numpy as np

    arr = np.asarray(img.convert("RGB"), dtype=np.uint16)
    h, w, _ = arr.shape
    r5 = (arr[:, :, 0] >> 3) & 0x1F
    g6 = (arr[:, :, 1] >> 2) & 0x3F
    b5 = (arr[:, :, 2] >> 3) & 0x1F
    pix16 = (r5 << 11) | (g6 << 5) | b5

    # BMP scanlines are stored bottom-up, row stride aligned to 4 bytes
    row_bytes = w * 2
    pad_bytes = (4 - (row_bytes % 4)) % 4
    flipped = pix16[::-1, :].astype("<u2")
    if pad_bytes == 0:
        pixel_data = flipped.tobytes()
    else:
        raw_rows = flipped.tobytes()
        pad = b"\x00" * pad_bytes
        pixel_data = b"".join(
            raw_rows[y * row_bytes : (y + 1) * row_bytes] + pad for y in range(h)
        )

    bf_off_bits = 138
    bi_size_image = len(pixel_data)
    bf_size = bf_off_bits + bi_size_image

    # Exact 138-byte header matching stock Amlogic U-Boot bootup BMP:
    # BITMAPFILEHEADER (14B) + BITMAPV5HEADER (124B) with RGB565 bitfields
    hdr = bytearray(138)
    struct.pack_into("<2sIHHI", hdr, 0, b"BM", bf_size, 0, 0, bf_off_bits)
    struct.pack_into(
        "<IiiHHIIIIIIIII",
        hdr,
        14,
        124,           # biSize (BITMAPV5HEADER)
        w,             # biWidth
        h,             # biHeight (>0 bottom-up)
        1,             # biPlanes
        16,            # biBitCount
        3,             # biCompression = BI_BITFIELDS
        bi_size_image, # biSizeImage
        2834,          # biXPelsPerMeter
        2834,          # biYPelsPerMeter
        0,             # biClrUsed
        0,             # biClrImportant
        0x0000F800,    # bV5RedMask
        0x000007E0,    # bV5GreenMask
        0x0000001F,    # bV5BlueMask
    )
    return bytes(hdr) + pixel_data


def unpack_aml_res(blob: bytes) -> list[tuple[str, bytes, int]]:
    crc, ver, magic, tot_sz, cnt, align = struct.unpack_from("<II8sIII", blob, 0)
    if magic != b"AML_RES!":
        raise ValueError(f"Unexpected magic: {magic!r}")
    expect_crc = (~zlib.crc32(blob[4:tot_sz])) & 0xFFFFFFFF
    if expect_crc != crc:
        raise ValueError(f"Stock CRC mismatch: got 0x{crc:08x}, expect 0x{expect_crc:08x}")
    items: list[tuple[str, bytes, int]] = []
    for i in range(cnt):
        magic_i, comp, data_sz, data_off, _r1, _next_off, _r2, id_val, raw_name = (
            struct.unpack_from("<8I32s", blob, 64 + i * 64)
        )
        if magic_i != 0x27051956:
            raise ValueError(f"Bad item[{i}] magic: 0x{magic_i:08x}")
        name = raw_name.split(b"\x00", 1)[0].decode("ascii")
        items.append((name, blob[data_off : data_off + data_sz], id_val))
    return items


def pack_aml_res(items: list[tuple[str, bytes, int]], align: int = 16) -> bytes:
    cnt = len(items)
    header_bytes = 64 + cnt * 64
    cur_off = header_bytes

    payloads = bytearray()
    item_hdrs = bytearray()
    for idx, (name, data, id_val) in enumerate(items):
        # Record offset before appending payload
        data_off = cur_off
        padded_len = (len(data) + (align - 1)) & ~(align - 1)
        payloads.extend(data)
        payloads.extend(b"\x00" * (padded_len - len(data)))
        cur_off += padded_len

        next_hdr_off = (64 + (idx + 1) * 64) if (idx + 1 < cnt) else 0
        raw_name = name.encode("ascii")[:31].ljust(32, b"\x00")
        hdr = struct.pack(
            "<8I32s",
            0x27051956,
            0,
            len(data),
            data_off,
            0,
            next_hdr_off,
            0,
            id_val,
            raw_name,
        )
        item_hdrs.extend(hdr)

    tot_sz = 64 + len(item_hdrs) + len(payloads)
    if tot_sz > PART_TOTAL_BYTES:
        raise ValueError(f"logo.img overflow: {tot_sz} > {PART_TOTAL_BYTES}")

    main_hdr = struct.pack("<II8sIII", 0, 2, b"AML_RES!", tot_sz, cnt, align).ljust(64, b"\x00")
    out = bytearray(main_hdr + item_hdrs + payloads)
    crc = (~zlib.crc32(out[4:tot_sz])) & 0xFFFFFFFF
    struct.pack_into("<I", out, 0, crc)
    return bytes(out)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build OpenStation Max bootanimation.zip + logo.img")
    parser.add_argument("--stock-logo", type=Path, default=DEFAULT_STOCK_LOGO)
    parser.add_argument("--out-logo", type=Path, default=ROOT_DIR / "firmware" / "logo.img")
    parser.add_argument("--out-bootanim", type=Path, default=ROOT_DIR / "firmware" / "bootanimation.zip")
    parser.add_argument("--samples-dir", type=Path, default=None)
    parser.add_argument("--bootup-width", type=int, default=960)
    parser.add_argument("--bootup-height", type=int, default=540)
    args = parser.parse_args()

    # 1. Build bootanimation.zip and capture exact canonical Bridge Frame (part0 frame 0)
    bridge_dir = ROOT_DIR / "firmware" / "_boot_preview"
    size_bytes, count = gba.generate_bootanimation(args.out_bootanim, sample_dir=bridge_dir)
    print(f"Bootanimation: {args.out_bootanim} ({size_bytes:,} bytes, {count} frames)")

    # Extract exact first frame of bootanimation.zip so U-Boot bootup matches seamlessly
    import zipfile

    with zipfile.ZipFile(args.out_bootanim, "r") as zf:
        import io

        first_png = zf.read("part0/000.png")
    bridge_img = Image.open(io.BytesIO(first_png)).convert("RGB")
    if (args.bootup_width, args.bootup_height) != bridge_img.size:
        bootup_img = bridge_img.resize(
            (args.bootup_width, args.bootup_height), Image.Resampling.LANCZOS
        )
    else:
        bootup_img = bridge_img

    if args.samples_dir is not None:
        args.samples_dir.mkdir(parents=True, exist_ok=True)
        bootup_img.save(args.samples_dir / "bootup_uboot.png")
        shutil.copy2(bridge_dir / "boot_p0_000.png", args.samples_dir / "boot_p0_000.png")
        shutil.copy2(bridge_dir / "boot_p0_015.png", args.samples_dir / "boot_p0_015.png")
        shutil.copy2(bridge_dir / "boot_p0_044.png", args.samples_dir / "boot_p0_044.png")
        shutil.copy2(bridge_dir / "boot_p1_000.png", args.samples_dir / "boot_p1_000.png")
    shutil.rmtree(bridge_dir, ignore_errors=True)

    # 2. Pack logo.img if stock dump is provided
    if args.stock_logo.is_file():
        stock_blob = args.stock_logo.read_bytes()
        items = unpack_aml_res(stock_blob)
        # Verify lossless roundtrip before replacing bootup
        repacked_stock = pack_aml_res(items)
        if repacked_stock != stock_blob[: len(repacked_stock)]:
            raise AssertionError("AML_RES! roundtrip check failed against stock dump!")

        bootup_bmp = rgb888_to_bmp_rgb565(bootup_img)
        new_items = []
        replaced = False
        for name, data, id_val in items:
            if name == "bootup":
                new_items.append((name, bootup_bmp, id_val))
                replaced = True
            else:
                new_items.append((name, data, id_val))
        if not replaced:
            raise RuntimeError("No 'bootup' entry found in stock logo.img")

        new_logo_blob = pack_aml_res(new_items)
        args.out_logo.parent.mkdir(parents=True, exist_ok=True)
        args.out_logo.write_bytes(new_logo_blob)
        print(
            f"Boot logo: {args.out_logo} ({len(new_logo_blob):,} bytes / {PART_TOTAL_BYTES:,}, "
            f"bootup={len(bootup_bmp):,} bytes @ {args.bootup_width}x{args.bootup_height})"
        )
    else:
        print(f"WARN: stock logo not found at {args.stock_logo}, skipping logo.img build")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
