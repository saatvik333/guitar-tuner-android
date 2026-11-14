"""Renders legacy (pre-API 26) launcher PNGs matching res/drawable/ic_fg.xml."""
import math
import os
import sys
from PIL import Image, ImageDraw

BG = (0x2B, 0x2A, 0x27, 255)
DISC = (0xBC, 0xAD, 0x7E, 255)
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render(size: int) -> Image.Image:
    ss = 8
    S = size * ss
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # 108-unit adaptive canvas mapped so the 72-unit visible circle fills 92% of the icon
    vis = 0.92 * S
    k = vis / 72.0
    ox = S / 2 - 54 * k

    def P(x, y):
        return (ox + x * k, ox + y * k)

    def disc(cx, cy, r):
        x, y = P(cx, cy)
        return [x - r * k, y - r * k, x + r * k, y + r * k]

    def stroke_px(width):
        return max(1, round(width * k))

    def ring_box(cx, cy, r, w):
        # PIL draws an outline inside its box, a VectorDrawable stroke straddles the path:
        # grow the box by half the stroke so both have the same centreline
        x, y = P(cx, cy)
        o = r * k + w / 2
        return [x - o, y - o, x + o, y + o]

    r = vis / 2
    d.ellipse([S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r], fill=BG)
    d.ellipse(disc(54, 54, 24), fill=DISC)

    # dial broken at the top (arc ends at 5.28 units either side of the axis, as in ic_fg.xml),
    # target ring, bead at +40 deg
    dial_r = 11.0
    gap = math.degrees(math.asin(5.28 / dial_r))
    w = stroke_px(1.8)
    d.arc(ring_box(54, 54, dial_r, w), start=270 + gap, end=270 - gap + 360, fill=BG, width=w)
    for a in (270 + gap, 270 - gap):        # round caps: a disc of the stroke's width at each arc end
        ex, ey = P(54 + dial_r * math.cos(math.radians(a)), 54 + dial_r * math.sin(math.radians(a)))
        d.ellipse([ex - w / 2, ey - w / 2, ex + w / 2, ey + w / 2], fill=BG)
    w = stroke_px(1.5)
    d.ellipse(ring_box(54, 43, 3.8, w), outline=BG, width=w)
    d.ellipse(disc(61.07, 45.57, 2.8), fill=BG)
    return im.resize((size, size), Image.LANCZOS)


def main(res_dir: str) -> None:
    for name, px in SIZES.items():
        out = os.path.join(res_dir, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        render(px).save(os.path.join(out, "ic_launcher.png"), optimize=True)
    render(512).save(os.path.join(res_dir, "..", "icon-512.png"))


if __name__ == "__main__":
    main(sys.argv[1])
