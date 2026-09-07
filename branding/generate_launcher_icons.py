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

import sys

# Set before anything else can be imported: --check must never leave untracked output behind.
sys.dont_write_bytecode = True

import argparse
import struct
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


# Radius of the circular plate used for the round launcher icon. Nearly the full canvas: a round
# icon is displayed as supplied, so anything less reads as a small circle floating in a gap.
ROUND_PLATE_RADIUS = 53.0


def circle_contains(px, py, radius):
    dx = px - CENTRE[0]
    dy = py - CENTRE[1]
    return dx * dx + dy * dy <= radius * radius


def sample(px, py, plate):
    """
    Colour of one point in viewport space, painted front-to-back.

    `plate` is None for the adaptive foreground (transparent behind the mark, because the launcher
    supplies its own background layer), "rect" for the legacy square icon, or "circle" for the
    round one.
    """
    if triangle_contains(px, py):
        return (TEAL[0], TEAL[1], TEAL[2], 255)
    if rounded_rect_contains(px, py, **CONTROLLER) or rounded_rect_contains(px, py, **DISPLAY):
        return (WHITE[0], WHITE[1], WHITE[2], 255)
    if plate == "rect" and rounded_rect_contains(
        px, py, PLATE_INSET, PLATE_INSET,
        VIEWPORT - PLATE_INSET, VIEWPORT - PLATE_INSET, PLATE_RADIUS,
    ):
        return (NAVY[0], NAVY[1], NAVY[2], 255)
    if plate == "circle" and circle_contains(px, py, ROUND_PLATE_RADIUS):
        return (NAVY[0], NAVY[1], NAVY[2], 255)
    return (0, 0, 0, 0)


def render(size, plate):
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
                    cr, cg, cb, ca = sample(px, py, plate)
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


def rounded_rect_path(x0, y0, x1, y1, r):
    """SVG/Android path data for a rounded rectangle. One implementation, used by every writer."""
    return (f"M{x0 + r},{y0} L{x1 - r},{y0} A{r},{r} 0 0 1 {x1},{y0 + r} "
            f"L{x1},{y1 - r} A{r},{r} 0 0 1 {x1 - r},{y1} "
            f"L{x0 + r},{y1} A{r},{r} 0 0 1 {x0},{y1 - r} "
            f"L{x0},{y0 + r} A{r},{r} 0 0 1 {x0 + r},{y0} Z")


def paths():
    """The three shapes as path data, plus the legacy plate."""
    return {
        "controller": rounded_rect_path(**CONTROLLER),
        "display": rounded_rect_path(**DISPLAY),
        "relay": (f"M{RELAY['bx']},{RELAY['by0']} "
                  f"L{RELAY['apex_x']},{RELAY['apex_y']} "
                  f"L{RELAY['bx']},{RELAY['by1']} Z"),
        "plate": rounded_rect_path(
            PLATE_INSET, PLATE_INSET,
            VIEWPORT - PLATE_INSET, VIEWPORT - PLATE_INSET, PLATE_RADIUS,
        ),
    }


def hexof(rgb):
    return "#%02X%02X%02X" % rgb


def vector_xml(body, comment, tint=""):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n<!--\n' + comment + '-->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108"' + tint + '>\n'
        + body + '</vector>\n'
    )


def path_element(fill, data):
    return f'    <path\n        android:fillColor="{fill}"\n        android:pathData="{data}" />\n'


GENERATED_NOTE = (
    "  Generated by branding/generate_launcher_icons.py, which holds the geometry. Do not hand\n"
    "  edit: change the script and re-run it, or this file and the others will disagree.\n"
)


def write_vectors(res, branding_dir):
    """Writes the SVG master, the vector drawables and the adaptive descriptors."""
    p = paths()
    navy, teal = hexof(NAVY), hexof(TEAL)
    written = []

    def put(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        written.append(str(path))

    mark_body = path_element("#FFFFFF", p["controller"]) + path_element("#FFFFFF", p["display"])

    put(res / "drawable/ic_launcher_background.xml", vector_xml(
        path_element(navy, "M0,0h108v108h-108z"),
        "  Adaptive launcher background. A single flat colour on purpose: an 8 bit gradient bands\n"
        "  visibly at 48px, and the launcher may scale and parallax this layer.\n" + GENERATED_NOTE))

    put(res / "drawable/ic_launcher_foreground.xml", vector_xml(
        mark_body + path_element(teal, p["relay"]),
        "  Adaptive launcher foreground: the Relay Display mark. Two rounded screens, the\n"
        "  controller you hold and the taller companion display, with a relay wedge between them.\n"
        "\n"
        "  Every coordinate is inside the 66dp safe circle centred on (54, 54), so no launcher\n"
        "  mask clips anything. The generator asserts that; it is not a claim in a comment.\n"
        + GENERATED_NOTE))

    put(res / "drawable/ic_launcher_monochrome.xml", vector_xml(
        mark_body + path_element("#FFFFFF", p["relay"]),
        "  Android 13+ themed icon. The system replaces the fill with its own colour, so this is a\n"
        "  single colour silhouette. The foreground cannot be reused: its teal wedge would flatten\n"
        "  into the white screens and the mark would become one blob.\n" + GENERATED_NOTE,
        tint='\n    android:tint="#FFFFFF"'))

    # The About screen and any other in-app use. NOT the mipmap: on API 26+ mipmap/ic_launcher
    # resolves to the adaptive-icon XML, which Compose's painterResource cannot load at all.
    put(res / "drawable/relay_display_mark.xml", vector_xml(
        path_element(navy, p["plate"]) + mark_body + path_element(teal, p["relay"]),
        "  The mark for in-app use, including the About screen.\n"
        "\n"
        "  Separate from the launcher resources on purpose. On API 26+ mipmap/ic_launcher resolves\n"
        "  to mipmap-anydpi-v26/ic_launcher.xml, an <adaptive-icon>, which is not a drawable\n"
        "  Compose can inflate: painterResource on it throws. This is a plain vector with the\n"
        "  plate baked in, so it renders anywhere and needs no launcher masking.\n" + GENERATED_NOTE))

    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!--\n' + GENERATED_NOTE + '-->\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
    put(res / "mipmap-anydpi-v26/ic_launcher.xml", adaptive)
    put(res / "mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)

    put(branding_dir / "relay-display-logo.svg",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<!--\n'
        '  Relay Display logo, editable master.\n'
        '\n'
        '  Original artwork for this project, built entirely from three geometric primitives. No\n'
        '  downloaded, purchased, traced or third party asset is involved.\n'
        '\n'
        '  Concept: the phone you hold (left, smaller) relays content to the companion display\n'
        '  (right, taller). The wedge is the content in flight.\n'
        '\n' + GENERATED_NOTE +
        '\n'
        '  108x108 viewport matches Android\'s adaptive-icon canvas so coordinates transfer\n'
        '  unchanged.\n'
        '-->\n'
        '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
        '  <title>Relay Display</title>\n'
        f'  <path d="{p["plate"]}" fill="{navy}"/>\n'
        f'  <path d="{p["controller"]}" fill="#FFFFFF"/>\n'
        f'  <path d="{p["display"]}" fill="#FFFFFF"/>\n'
        f'  <path d="{p["relay"]}" fill="{teal}"/>\n'
        '</svg>\n')
    return written


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
    for written in write_vectors(res, Path(__file__).resolve().parent):
        print(f"  {written}")
    for density, size in DENSITIES.items():
        target = res / f"mipmap-{density}"
        target.mkdir(parents=True, exist_ok=True)
        # Two genuinely different assets. android:roundIcon is displayed *as supplied* by the
        # launchers that ask for it -- nothing masks it into a circle on the app's behalf -- so
        # handing over a copy of the square icon produces a square icon in a round slot. That is
        # what lint's IconLauncherShape and IconDuplicates were both reporting, and it is visible
        # on the API 24 phone, where these PNGs are what actually renders: mipmap-anydpi-v26 only
        # takes over from API 26.
        write_png(target / "ic_launcher.png", size, render(size, plate="rect"))
        write_png(target / "ic_launcher_round.png", size, render(size, plate="circle"))
        print(f"  mipmap-{density}: {size}x{size} ic_launcher.png + ic_launcher_round.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
