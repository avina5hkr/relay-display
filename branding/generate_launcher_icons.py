#!/usr/bin/env python3
"""Renders the Relay Display launcher mark to the legacy density PNGs.

A script rather than a design-tool export, because the mark is deliberately built from three
primitives -- two rounded rectangles and a triangle -- so the geometry below *is* the master
definition, shared with relay-display-logo.svg and the Android vector drawables. Regenerating
cannot drift from the source, and the repository needs no binary design files.

No third-party dependency: a PNG is a zlib stream plus a header, and anti-aliasing is 4x
supersampling. Adding Pillow or cairosvg for five small icons is not a trade worth making.

    python3 branding/generate_launcher_icons.py            # writes into app/src/main/res
    python3 branding/generate_launcher_icons.py --check    # verifies geometry, writes nothing

Legacy icons (API 23-25) are not masked by the launcher, so the rounded-square plate is drawn
here. API 26+ uses the adaptive icon and ignores these files entirely.
"""
from __future__ import annotations

import argparse
import struct
import sys
import zlib
from pathlib import Path

# ---------------------------------------------------------------------------------------------
# Geometry, in the 108x108 adaptive-icon viewport.
#
# The adaptive safe zone is a 66dp-diameter circle centred at (54, 54) -- radius 33. Every
# coordinate below sits inside it, so no launcher mask (circle, squircle, rounded square,
# teardrop) can clip anything meaningful. verify_safe_zone() asserts that rather than trusting
# this comment.
# ---------------------------------------------------------------------------------------------
VIEWPORT = 108.0
CENTRE = (54.0, 54.0)
SAFE_RADIUS = 33.0

# The controller: the phone you hold. Smaller, left.
CONTROLLER = dict(x0=29.0, y0=44.0, x1=42.0, y1=64.0, r=3.5)
# The companion display: the spare with the good screen. Taller, right.
DISPLAY = dict(x0=62.0, y0=36.0, x1=78.0, y1=72.0, r=5.0)
# The relay: content moving between them. A solid wedge, because at 48px a filled triangle
# survives where a stroked chevron or thin bars turn to mush.
RELAY = dict(bx=45.0, by0=45.0, by1=63.0, apex_x=59.5, apex_y=54.0)

# Flat colours only. An 8-bit gradient bands visibly at 48px, and the monochrome variant would
# flatten one to nothing useful.
NAVY = (0x0A, 0x2A, 0x4F)
WHITE = (0xFF, 0xFF, 0xFF)
TEAL = (0x6F, 0xF6, 0xFE)
PLATE_INSET = 8.0
PLATE_RADIUS = 22.0

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SUPERSAMPLE = 4


def rounded_rect_contains(px, py, x0, y0, x1, y1, r):
    """Point-in-rounded-rectangle. r is clamped so a fat radius cannot invert the shape."""
    r = min(r, (x1 - x0) / 2.0, (y1 - y0) / 2.0)
    if px < x0 or px > x1 or py < y0 or py > y1:
        return False
    if x0 + r <= px <= x1 - r or y0 + r <= py <= y1 - r:
        return True
    for cx, cy in ((x0 + r, y0 + r), (x1 - r, y0 + r), (x0 + r, y1 - r), (x1 - r, y1 - r)):
        if (px - cx) ** 2 + (py - cy) ** 2 <= r * r:
            return True
    return False


def triangle_contains(px, py):
    ax, ay = RELAY["bx"], RELAY["by0"]
    bx, by = RELAY["apex_x"], RELAY["apex_y"]
    cx, cy = RELAY["bx"], RELAY["by1"]

    def side(x1, y1, x2, y2):
        return (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)

    d1, d2, d3 = side(ax, ay, bx, by), side(bx, by, cx, cy), side(cx, cy, ax, ay)
    return not ((d1 < 0 or d2 < 0 or d3 < 0) and (d1 > 0 or d2 > 0 or d3 > 0))


def sample(px, py, with_plate):
    """Colour of one point in viewport space, painted front-to-back."""
    if triangle_contains(px, py):
        return (TEAL[0], TEAL[1], TEAL[2], 255)
    if rounded_rect_contains(px, py, **CONTROLLER) or rounded_rect_contains(px, py, **DISPLAY):
        return (WHITE[0], WHITE[1], WHITE[2], 255)
    if with_plate and rounded_rect_contains(
        px, py, PLATE_INSET, PLATE_INSET,
        VIEWPORT - PLATE_INSET, VIEWPORT - PLATE_INSET, PLATE_RADIUS,
    ):
        return (NAVY[0], NAVY[1], NAVY[2], 255)
    return (0, 0, 0, 0)


def render(size, with_plate):
    """RGBA rows with SUPERSAMPLE x SUPERSAMPLE box-filter anti-aliasing."""
    step = (VIEWPORT / size) / SUPERSAMPLE
    rows = bytearray()
    n = SUPERSAMPLE * SUPERSAMPLE
    for y in range(size):
        row = bytearray()
        for x in range(size):
            r = g = b = a = 0
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    px = (x * SUPERSAMPLE + sx) * step + step / 2.0
                    py = (y * SUPERSAMPLE + sy) * step + step / 2.0
                    cr, cg, cb, ca = sample(px, py, with_plate)
                    # Premultiply so partially covered edge pixels blend correctly.
                    r += cr * ca // 255
                    g += cg * ca // 255
                    b += cb * ca // 255
                    a += ca
            a_out = a // n
            if a_out == 0:
                row += bytes(4)
            else:
                row += bytes((
                    min(255, (r // n) * 255 // a_out),
                    min(255, (g // n) * 255 // a_out),
                    min(255, (b // n) * 255 // a_out),
                    a_out,
                ))
        rows += b"\x00" + bytes(row)
    return bytes(rows)


def write_png(path, size, rgba_rows):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rgba_rows, 9))
        + chunk(b"IEND", b"")
    )


def verify_safe_zone():
    points = []
    for name, shape in (("controller", CONTROLLER), ("display", DISPLAY)):
        for cx in (shape["x0"], shape["x1"]):
            for cy in (shape["y0"], shape["y1"]):
                points.append((name, cx, cy))
    points += [
        ("relay base top", RELAY["bx"], RELAY["by0"]),
        ("relay base bottom", RELAY["bx"], RELAY["by1"]),
        ("relay apex", RELAY["apex_x"], RELAY["apex_y"]),
    ]
    problems = []
    worst = 0.0
    for name, x, y in points:
        d = ((x - CENTRE[0]) ** 2 + (y - CENTRE[1]) ** 2) ** 0.5
        worst = max(worst, d)
        if d > SAFE_RADIUS:
            problems.append(f"{name} ({x}, {y}) is {d:.1f} from centre, outside radius {SAFE_RADIUS}")
    return problems, worst


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify geometry without writing")
    parser.add_argument("--res", default="app/src/main/res")
    args = parser.parse_args()

    problems, worst = verify_safe_zone()
    for p in problems:
        print(f"SAFE ZONE: {p}", file=sys.stderr)
    if problems:
        return 1
    print(f"Safe zone OK: furthest point is {worst:.1f} from centre, limit {SAFE_RADIUS}.")

    if args.check:
        print("Check only; nothing written.")
        return 0

    res = Path(args.res)
    for density, size in DENSITIES.items():
        target = res / f"mipmap-{density}"
        target.mkdir(parents=True, exist_ok=True)
        rows = render(size, with_plate=True)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            # The same square plate for both. A launcher on API 23-25 wanting a round icon applies
            # its own circular mask, and the plate's 22dp corners sit inside that circle, so
            # nothing of the mark is lost either way.
            write_png(target / name, size, rows)
        print(f"  mipmap-{density}: {size}x{size} ic_launcher.png + ic_launcher_round.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
