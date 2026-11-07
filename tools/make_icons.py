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

    r = vis / 2
    d.ellipse([S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r], fill=BG)
    d.ellipse([*P(30, 30), *P(78, 78)], fill=DISC)
    # dial broken at the top (gap +-28.7 deg), target ring, bead at +40 deg (mirrors res/drawable/ic_fg.xml)
    gap = math.degrees(math.asin(5.3 / 11.0))
    d.arc([*P(43, 43), *P(65, 65)], start=270 + gap, end=270 - gap + 360, fill=BG, width=max(1, round(1.8 * k)))
    d.ellipse([*P(50.2, 39.2), *P(57.8, 46.8)], outline=BG, width=max(1, round(1.5 * k)))
    d.ellipse([*P(61.07 - 2.8, 45.57 - 2.8), *P(61.07 + 2.8, 45.57 + 2.8)], fill=BG)
    return im.resize((size, size), Image.LANCZOS)


def main(res_dir: str) -> None:
    for name, px in SIZES.items():
        out = os.path.join(res_dir, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        render(px).save(os.path.join(out, "ic_launcher.png"), optimize=True)
    render(512).save(os.path.join(res_dir, "..", "icon-512.png"))


if __name__ == "__main__":
    main(sys.argv[1])
