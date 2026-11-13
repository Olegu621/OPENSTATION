#!/usr/bin/env python3
"""
Generates firmware/bootanimation.zip for OpenStation Max (16:9 960x540 @ 30fps).

Android libbootanimation.so requirements (verified on yandexstation_2):
  - All ZIP entries MUST use ZIP_STORED (uncompressed), otherwise BootAnimation rejects it.
  - Frames are 8-bit RGB non-interlaced PNGs without alpha channel complexity.
  - desc.txt uses Unix LF endings:
      960 540 30
      p 1 0 part0 #000000
      p 0 0 part1 #000000
"""

from __future__ import annotations

import argparse
import io
import math
import os
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont


WIDTH = 960
HEIGHT = 540
FPS = 30
PART0_FRAMES = 45  # 1.5s intro
PART1_FRAMES = 48  # 1.6s seamless loop

ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT_DIR / "firmware" / "bootanimation.zip"


def find_font(candidates: list[str], size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    win_fonts = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Fonts"
    search_dirs = [
        win_fonts,
        Path("/usr/share/fonts"),
        Path("/usr/share/fonts/truetype"),
    ]
    for name in candidates:
        for base in search_dirs:
            p = base / name
            if p.is_file():
                try:
                    return ImageFont.truetype(str(p), size=size)
                except OSError:
                    pass
    return ImageFont.load_default()


def make_variable_font(candidates: list[str], size: int, weight: int | None = None):
    font = find_font(candidates, size)
    if weight is not None and hasattr(font, "set_variation_by_axes"):
        try:
            axes = font.get_variation_axes()
            vals = [a["default"] for a in axes]
            for idx, ax in enumerate(axes):
                nm = str(ax.get("name", "")).lower()
                if "weight" in nm:
                    vals[idx] = max(ax["minimum"], min(ax["maximum"], weight))
            font.set_variation_by_axes(vals)
        except Exception:
            pass
    return font


def ease_out_cubic(t: float) -> float:
    t = max(0.0, min(1.0, t))
    return 1.0 - (1.0 - t) ** 3


def ease_in_out_sine(t: float) -> float:
    t = max(0.0, min(1.0, t))
    return 0.5 * (1.0 - math.cos(math.pi * t))


def draw_spaced_text_centered(
    draw: ImageDraw.ImageDraw,
    center_x: float,
    y: float,
    text: str,
    font,
    fill: tuple[int, int, int, int],
    tracking: float = 0.0,
) -> tuple[float, float]:
    widths = [draw.textlength(ch, font=font) for ch in text]
    total_w = sum(widths) + tracking * max(0, len(text) - 1)
    bbox = draw.textbbox((0, 0), text, font=font)
    text_h = bbox[3] - bbox[1]
    x = center_x - total_w * 0.5
    for idx, ch in enumerate(text):
        draw.text((x, y), ch, font=font, fill=fill)
        x += widths[idx] + tracking
    return total_w, float(text_h)


def build_coordinate_grids(w: int, h: int, cx: float, cy: float):
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    dx = xs - np.float32(cx)
    dy = ys - np.float32(cy)
    r = np.sqrt(dx * dx + dy * dy)
    theta = np.arctan2(dy, dx)
    return dx, dy, r, theta


def render_frame(
    *,
    dx: np.ndarray,
    dy: np.ndarray,
    r: np.ndarray,
    theta: np.ndarray,
    cx: float,
    cy: float,
    title_font,
    sub_font,
    intro_scale: float,
    ring_reveal: float,
    title_alpha: float,
    sub_alpha: float,
    sub_slide_px: float,
    phase: float,
    flash_strength: float,
    fade_all: float,
) -> Image.Image:
    # Pure #000000 edge-to-edge backdrop with localized nebula behind the ring (great OLED contrast + compact PNGs)
    r_soft = r / 138.0
    bg_halo = np.exp(-r_soft * r_soft * 1.15)
    bg_halo = np.where(bg_halo < 0.015, 0.0, bg_halo)

    ang_shift = np.sin(theta - phase)
    bg_r = (16.0 + 5.0 * (-ang_shift)) * bg_halo
    bg_g = (22.0 + 7.0 * ang_shift) * bg_halo
    bg_b = (44.0 + 10.0 * np.cos(theta - phase)) * bg_halo

    # Luminous sonic ring (station 24-LED halo proportions)
    ring_radius = 104.0 * max(0.05, intro_scale)
    ring_dist = np.abs(r - ring_radius)
    ring_core = np.exp(-(ring_dist / 2.3) ** 2)
    ring_glow = np.exp(-(ring_dist / 13.5) ** 2)

    wave_main = 0.5 + 0.5 * np.cos(theta - phase)
    wave_sec = 0.5 + 0.5 * np.cos(2.0 * theta + phase)

    # Smooth reveal arc starting cleanly from 12 o'clock (-pi/2) with no seam edge
    if ring_reveal < 0.999:
        ang_from_top = (theta + math.pi * 0.5) % (2.0 * math.pi)
        arc_lim = ring_reveal * 2.0 * math.pi
        tail = np.clip((arc_lim - ang_from_top) / 0.35, 0.0, 1.0)
        head_fade = np.clip(ang_from_top / 0.16, 0.0, 1.0)
        reveal_mask = np.maximum(tail * head_fade, 0.14 * (ring_reveal ** 1.5))
    else:
        reveal_mask = 1.0

    # Smooth 3D glassy AI sphere (no pinch artifacts at r=0)
    pulse = 1.0 + 0.06 * math.sin(phase) + 0.03 * math.cos(2.0 * phase)
    orb_r = 32.0 * max(0.05, intro_scale) * pulse
    orb_mask = np.clip((orb_r - r) / 2.2 + 0.5, 0.0, 1.0)
    orb_halo = np.exp(-(r / (52.0 * pulse * max(0.25, intro_scale))) ** 2)

    inv_orb_r = 1.0 / max(1.0, orb_r)
    nx = np.clip(dx * inv_orb_r, -1.0, 1.0)
    ny = np.clip(dy * inv_orb_r, -1.0, 1.0)
    nz = np.sqrt(np.maximum(0.0, 1.0 - nx * nx - ny * ny))

    # Moving caustic studio highlight orbiting gently around the sphere face
    lx = 0.42 * math.cos(phase) - 0.25
    ly = 0.32 * math.sin(phase) - 0.38
    lz = 0.80
    diff_light = np.maximum(0.0, nx * lx + ny * ly + nz * lz)
    rim_fresnel = (1.0 - nz) ** 2.2

    intensity_ring = (ring_core * (0.72 + 0.58 * wave_main) + ring_glow * (0.24 + 0.22 * wave_sec)) * reveal_mask
    intensity_ring *= np.clip(intro_scale * 1.1, 0.0, 1.0)

    gold_hit = (wave_main ** 4) * 0.42
    t_blend = wave_sec
    ring_col_r = (0.0 * (1.0 - t_blend) + 138.0 * t_blend) * (1.0 - gold_hit) + 232.0 * gold_hit
    ring_col_g = (224.0 * (1.0 - t_blend) + 92.0 * t_blend) * (1.0 - gold_hit) + 198.0 * gold_hit
    ring_col_b = (255.0 * (1.0 - t_blend) + 246.0 * t_blend) * (1.0 - gold_hit) + 134.0 * gold_hit

    # Inside orb: deep cerulean body + glossy specular core + bright cyan fresnel rim
    spec = (diff_light ** 4.5) * 215.0 + (diff_light ** 1.4) * 75.0
    orb_r_ch = 18.0 * orb_halo + orb_mask * (18.0 + 75.0 * rim_fresnel + spec * 0.85)
    orb_g_ch = 96.0 * orb_halo + orb_mask * (115.0 + 115.0 * rim_fresnel + spec * 0.96)
    orb_b_ch = 165.0 * orb_halo + orb_mask * (215.0 + 40.0 * rim_fresnel + spec * 1.00)

    out_r = bg_r + intensity_ring * ring_col_r + orb_r_ch
    out_g = bg_g + intensity_ring * ring_col_g + orb_g_ch
    out_b = bg_b + intensity_ring * ring_col_b + orb_b_ch

    # 24 subtle LED dot nodes orbiting the station ring
    for i in range(24):
        a = -math.pi * 0.5 + (2.0 * math.pi * i) / 24.0
        diff = ((a - phase + math.pi) % (2.0 * math.pi)) - math.pi
        active = math.exp(-(diff / 0.50) ** 2)
        dot_vis = 1.0
        if ring_reveal < 0.999:
            frac = i / 24.0
            dot_vis = max(0.0, min(1.0, (ring_reveal * 1.12 - frac) * 8.0))
        if dot_vis <= 0.01:
            continue
        px = math.cos(a) * ring_radius
        py = math.sin(a) * ring_radius
        cx_dot = int(round(cx + px))
        cy_dot = int(round(cy + py))
        x0 = max(0, cx_dot - 8)
        x1 = min(WIDTH, cx_dot + 9)
        y0 = max(0, cy_dot - 8)
        y1 = min(HEIGHT, cy_dot + 9)
        if x0 < x1 and y0 < y1:
            dd2 = (dx[y0:y1, x0:x1] - px) ** 2 + (dy[y0:y1, x0:x1] - py) ** 2
            g = np.exp(-dd2 / (3.8 if active > 0.35 else 2.0)) * (0.22 + 0.98 * active) * dot_vis * max(0.2, intro_scale)
            dot_col_r = 25.0 + 215.0 * active * active
            dot_col_g = 212.0 + 30.0 * active
            dot_col_b = 255.0 - 70.0 * (active ** 3)
            out_r[y0:y1, x0:x1] += g * dot_col_r * 0.72
            out_g[y0:y1, x0:x1] += g * dot_col_g * 0.72
            out_b[y0:y1, x0:x1] += g * dot_col_b * 0.72

    if flash_strength > 0.001:
        flash = np.exp(-(r / 88.0) ** 2) * flash_strength * 130.0
        out_r += flash * 0.7
        out_g += flash * 0.95
        out_b += flash * 1.0

    # Clamp < 1 to pure OLED #000000 for crisp PNG compression and zero grey borders
    out_r = np.where(out_r < 0.9, 0.0, out_r)
    out_g = np.where(out_g < 0.9, 0.0, out_g)
    out_b = np.where(out_b < 0.9, 0.0, out_b)

    out_r = np.clip(out_r * fade_all, 0.0, 255.0)
    out_g = np.clip(out_g * fade_all, 0.0, 255.0)
    out_b = np.clip(out_b * fade_all, 0.0, 255.0)

    rgb = np.stack([out_r, out_g, out_b], axis=-1).astype(np.uint8)
    base_img = Image.fromarray(rgb, mode="RGB").convert("RGBA")

    if title_alpha > 0.01 or sub_alpha > 0.01:
        overlay = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        glow_layer = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        gdraw = ImageDraw.Draw(glow_layer)

        ty = 348.0 + sub_slide_px * 0.45
        ta = int(max(0, min(255, round(255 * title_alpha * fade_all))))
        if ta > 2:
            draw_spaced_text_centered(
                gdraw, cx, ty, "OPENSTATION", title_font, (0, 212, 255, int(ta * 0.34)), tracking=7.0
            )
            glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(radius=6.0))
            base_img = Image.alpha_composite(base_img, glow_layer)
            odraw = ImageDraw.Draw(overlay)
            draw_spaced_text_centered(
                odraw, cx, ty, "OPENSTATION", title_font, (246, 250, 255, ta), tracking=7.0
            )
        else:
            odraw = ImageDraw.Draw(overlay)

        sa = int(max(0, min(255, round(255 * sub_alpha * fade_all))))
        if sa > 2:
            rule_half = 114.0 * ease_out_cubic(sub_alpha)
            if rule_half > 2.0:
                ry = 406.0 + sub_slide_px * 0.3
                odraw.line(
                    [(cx - rule_half, ry), (cx + rule_half, ry)],
                    fill=(232, 198, 134, int(sa * 0.48)),
                    width=2,
                )
            draw_spaced_text_centered(
                odraw,
                cx,
                418.0 + sub_slide_px,
                "MAX",
                sub_font,
                (232, 198, 134, sa),
                tracking=13.0,
            )

        base_img = Image.alpha_composite(base_img, overlay)

    return base_img.convert("RGB")


def image_to_png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, format="PNG", compress_level=9, optimize=True)
    return buf.getvalue()


def generate_bootanimation(out_path: Path, sample_dir: Path | None = None) -> tuple[int, int]:
    cx, cy = WIDTH * 0.5, 206.0
    dx, dy, r, theta = build_coordinate_grids(WIDTH, HEIGHT, cx, cy)

    title_font = make_variable_font(["bahnschrift.ttf", "segoeuib.ttf", "arialbd.ttf"], 42, weight=650)
    sub_font = make_variable_font(["bahnschrift.ttf", "segoeuisl.ttf", "segoeui.ttf"], 25, weight=500)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    if sample_dir is not None:
        sample_dir.mkdir(parents=True, exist_ok=True)

    desc_txt = f"{WIDTH} {HEIGHT} {FPS}\np 1 0 part0 #000000\np 0 0 part1 #000000\n"

    sample_indices_p0 = {0, 15, 32, PART0_FRAMES - 1}
    sample_indices_p1 = {0, PART1_FRAMES // 2, PART1_FRAMES - 1}

    frame_count = 0
    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr("desc.txt", desc_txt.encode("ascii"))

        for i in range(PART0_FRAMES):
            t = i / float(PART0_FRAMES - 1)
            # Frame 000 starts 100% seamless with U-Boot static 'bootup' logo (phase=0, full emblem),
            # then smoothly awakens an orbital surge + caustic halo into the continuous part1 loop.
            fade_all = 1.0
            intro_scale = 1.0 + 0.045 * math.sin(math.pi * t)
            ring_reveal = 1.0
            title_alpha = 1.0
            sub_alpha = 1.0
            sub_slide_px = 0.0
            # Ease phase smoothly from 0 (static U-Boot match) up to a full 2*pi rotation
            phase_ease = ease_in_out_sine(t)
            phase = 2.0 * math.pi * phase_ease
            flash_strength = math.exp(-((t - 0.34) / 0.14) ** 2) * 0.48 * (0.15 + 0.85 * min(1.0, i / 5.0))

            img = render_frame(
                dx=dx,
                dy=dy,
                r=r,
                theta=theta,
                cx=cx,
                cy=cy,
                title_font=title_font,
                sub_font=sub_font,
                intro_scale=intro_scale,
                ring_reveal=ring_reveal,
                title_alpha=title_alpha,
                sub_alpha=sub_alpha,
                sub_slide_px=sub_slide_px,
                phase=phase,
                flash_strength=flash_strength,
                fade_all=fade_all,
            )
            png_bytes = image_to_png_bytes(img)
            zf.writestr(f"part0/{i:03d}.png", png_bytes)
            if sample_dir is not None and i in sample_indices_p0:
                img.save(sample_dir / f"boot_p0_{i:03d}.png", format="PNG")
            frame_count += 1

        for i in range(PART1_FRAMES):
            phase = (2.0 * math.pi * i) / float(PART1_FRAMES)
            img = render_frame(
                dx=dx,
                dy=dy,
                r=r,
                theta=theta,
                cx=cx,
                cy=cy,
                title_font=title_font,
                sub_font=sub_font,
                intro_scale=1.0,
                ring_reveal=1.0,
                title_alpha=1.0,
                sub_alpha=1.0,
                sub_slide_px=0.0,
                phase=phase,
                flash_strength=0.0,
                fade_all=1.0,
            )
            png_bytes = image_to_png_bytes(img)
            zf.writestr(f"part1/{i:03d}.png", png_bytes)
            if sample_dir is not None and i in sample_indices_p1:
                img.save(sample_dir / f"boot_p1_{i:03d}.png", format="PNG")
            frame_count += 1

    return out_path.stat().st_size, frame_count


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate OpenStation Max bootanimation.zip")
    parser.add_argument("-o", "--output", type=Path, default=DEFAULT_OUT, help="Output zip path")
    parser.add_argument("--samples-dir", type=Path, default=None, help="Optional directory to dump preview PNGs")
    args = parser.parse_args()

    size_bytes, count = generate_bootanimation(args.output, args.samples_dir)
    print(f"Generated {args.output} ({size_bytes:,} bytes, {count} stored PNG frames @ {WIDTH}x{HEIGHT} {FPS}fps)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
