#!/usr/bin/env python3
"""GW-44 - regenerate the legacy launcher rasters for API 23-25.

minSdkVersion is 23. API 26+ loads res/mipmap-anydpi-v26/ic_launcher.xml (the adaptive
icon); API 23-25 never looks in that folder and loads a PNG from the density buckets
instead. Both test devices are API 26+, so a missing or broken bucket is invisible on
hardware here and shows up only on someone's Android 6 or 7 phone.

What this does, and why each step:

  1. Rasterises ic_launcher_source.svg - the mirror of the two adaptive layers - at 8x
     (864px for a 108dp canvas), so the mask below is anti-aliased by downsampling rather
     than by drawing.

  2. Crops to the centre 72 of the 108 canvas. That is the adaptive-icon spec's visible
     area: the outer 18dp on each side exists for the launcher's masking and parallax and
     is never shown flat. Skipping this crop yields a legacy icon that looks zoomed out
     next to every other icon in the drawer.

  3. Masks that 72x72 to a shape, because a legacy icon does no masking of its own - what
     ships is what is drawn. ic_launcher gets a rounded square; ic_launcher_round a circle.

  4. Downsamples to 48/72/96/144/192 px (mdpi..xxxhdpi) with Lanczos.

Requires rsvg-convert (librsvg) and Pillow. Run from anywhere:

    python3 tools/icons/generate_launcher_png.py

It rewrites app/src/main/res/mipmap-*dpi/ic_launcher{,_round}.png in place and verifies
each result reopens as a PNG of the expected size before it returns.
"""

import pathlib
import subprocess
import sys

from PIL import Image, ImageDraw

# Density bucket -> edge length in px for a 48dp launcher icon.
BUCKETS = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

CANVAS_DP = 108           # adaptive-icon canvas
VISIBLE_DP = 72           # the part of it a launcher ever shows
SCALE = 8                 # supersampling factor for the render + mask
CORNER_FRACTION = 0.20    # rounded-square corner radius, as a fraction of the edge

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
SVG = HERE / "ic_launcher_source.svg"
RES = REPO / "app" / "src" / "main" / "res"


def render_visible_square() -> Image.Image:
    """Render the SVG at 8x and crop to the adaptive icon's visible centre square."""
    px = CANVAS_DP * SCALE
    raw = subprocess.run(
        ["rsvg-convert", "-w", str(px), "-h", str(px), "-f", "png", str(SVG)],
        check=True,
        capture_output=True,
    ).stdout

    tmp = HERE / ".render.png"
    tmp.write_bytes(raw)
    try:
        full = Image.open(tmp).convert("RGBA")
    finally:
        tmp.unlink(missing_ok=True)

    if full.size != (px, px):
        raise SystemExit(f"rsvg-convert produced {full.size}, expected {(px, px)}")

    inset = (CANVAS_DP - VISIBLE_DP) // 2 * SCALE
    return full.crop((inset, inset, px - inset, px - inset))


def mask_for(shape: str, edge: int) -> Image.Image:
    mask = Image.new("L", (edge, edge), 0)
    draw = ImageDraw.Draw(mask)
    if shape == "circle":
        draw.ellipse((0, 0, edge - 1, edge - 1), fill=255)
    else:
        draw.rounded_rectangle(
            (0, 0, edge - 1, edge - 1), radius=int(edge * CORNER_FRACTION), fill=255
        )
    return mask


def main() -> int:
    if not SVG.is_file():
        raise SystemExit(f"missing source: {SVG}")

    visible = render_visible_square()
    edge = visible.size[0]

    written = []
    for name, shape in (("ic_launcher", "square"), ("ic_launcher_round", "circle")):
        masked = Image.new("RGBA", (edge, edge), (0, 0, 0, 0))
        masked.paste(visible, (0, 0), mask_for(shape, edge))

        for bucket, size in BUCKETS.items():
            out = RES / f"mipmap-{bucket}" / f"{name}.png"
            out.parent.mkdir(parents=True, exist_ok=True)
            masked.resize((size, size), Image.LANCZOS).save(out, "PNG", optimize=True)
            written.append((out, size))

    # Verify rather than assume: reopen every file and check format and dimensions.
    for out, size in written:
        with Image.open(out) as check:
            check.load()
            if check.format != "PNG" or check.size != (size, size):
                raise SystemExit(f"{out}: got {check.format} {check.size}, want PNG {(size, size)}")
        print(f"ok  {out.relative_to(REPO)}  {size}x{size}  {out.stat().st_size} bytes")

    print(f"\n{len(written)} rasters verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
