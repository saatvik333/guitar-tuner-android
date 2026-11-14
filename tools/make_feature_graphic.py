"""Renders the 1024x500 Play Store feature graphic in the app's Aurum theme (see hairline Palette.java):
the Advanced tuner's dial (Renderer.drawDial) reading an A string 12 cents sharp."""
import math
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "featureGraphic.png")
FONTS = os.path.join(ROOT, "hairline", "assets", "fonts")

BG = (0x2B, 0x2A, 0x27)
FG = (0xE9, 0xE5, 0xD9)
DIM = (0x9C, 0x97, 0x8A)
LINE = (0x4A, 0x47, 0x41)
ACCENT = (0xBC, 0xAD, 0x7E)

W, H, SS = 1024, 500, 4
SCALE_DEG, SCALE_CENTS = 75.0, 58.0       # Renderer: the bead's angle is 75 deg x tanh(cents / 58)


def theta(cents: float) -> float:
    return math.radians(SCALE_DEG * math.tanh(cents / SCALE_CENTS))


def main() -> None:
    im = Image.new("RGB", (W * SS, H * SS), BG)
    d = ImageDraw.Draw(im)

    def s(v):
        return round(v * SS)

    def circle(x, y, r, **kw):
        d.ellipse([s(x - r), s(y - r), s(x + r), s(y + r)], **kw)

    def line(x1, y1, x2, y2, fill, width):
        d.line([s(x1), s(y1), s(x2), s(y2)], fill=fill, width=s(width))
        for x, y in ((x1, y1), (x2, y2)):                  # round caps
            circle(x, y, width / 2, fill=fill)

    # Dial: proportions as Renderer.drawDial draws them for a radius R.
    cx, cy, R = 772, 288, 168
    b = 12.0                     # bead radius
    tr = b * 1.3                 # target ring radius
    r0 = R + b + 3               # scale
    for ct in range(-50, 51, 5):
        if ct == 0:
            continue
        end, major = abs(ct) == 50, ct % 10 == 0
        ln = 9 if end else 6 if major else 3.5
        th = theta(ct)
        line(cx + r0 * math.sin(th), cy - r0 * math.cos(th),
             cx + (r0 + ln) * math.sin(th), cy - (r0 + ln) * math.cos(th), DIM if end else LINE, 1.2)

    # flat and sharp marks beyond the ends, drawn as strokes as the app does
    gh, rr = 13.0, r0 + 9
    tl, ts = -theta(50) - math.radians(9), theta(50) + math.radians(9)
    fx, fy = cx + rr * math.sin(tl) - gh * 0.25, cy - rr * math.cos(tl) - gh * 0.5
    sx = fx + gh * 0.06
    line(sx, fy, sx, fy + gh, DIM, 1.4)
    bowl = []
    for p0, p1, p2, p3 in (((0, 0.58), (0.22, 0.40), (0.50, 0.48), (0.42, 0.68)),
                           ((0.42, 0.68), (0.36, 0.82), (0.16, 0.92), (0, 1.0))):
        for i in range(17):
            t = i / 16
            bx = (1 - t) ** 3 * p0[0] + 3 * (1 - t) ** 2 * t * p1[0] + 3 * (1 - t) * t ** 2 * p2[0] + t ** 3 * p3[0]
            by = (1 - t) ** 3 * p0[1] + 3 * (1 - t) ** 2 * t * p1[1] + 3 * (1 - t) * t ** 2 * p2[1] + t ** 3 * p3[1]
            bowl.append((s(sx + gh * bx), s(fy + gh * by)))
    d.line(bowl, fill=DIM, width=s(1.4), joint="curve")
    hx, hy = cx + rr * math.sin(ts) - gh * 0.3, cy - rr * math.cos(ts) - gh * 0.5
    gw = gh * 0.62
    line(hx + gw * 0.32, hy + gh * 0.06, hx + gw * 0.32, hy + gh, DIM, 1.4)
    line(hx + gw * 0.70, hy, hx + gw * 0.70, hy + gh * 0.94, DIM, 1.4)
    for yy in (0.38, 0.68):
        line(hx, hy + gh * (yy + 0.07), hx + gw, hy + gh * (yy - 0.07), DIM, 1.4)

    # the dial, broken around the target ring
    gap = math.degrees(math.asin((tr + 4) / R))
    d.arc([s(cx - R), s(cy - R), s(cx + R), s(cy + R)], start=-90 + gap, end=270 - gap, fill=LINE, width=s(1.5))

    # readout
    light = os.path.join(FONTS, "rubik_300.ttf")
    regular = os.path.join(FONTS, "rubik_400.ttf")
    note = ImageFont.truetype(light, s(R * 0.62))
    base = cy + R * 0.16
    d.text((s(cx), s(base)), "A", font=note, fill=FG, anchor="ms")
    lw = d.textlength("A", font=note) / SS
    d.text((s(cx + lw / 2 + R * 0.62 * 0.04), s(base)), "2", font=ImageFont.truetype(regular, s(R * 0.62 * 0.25)),
           fill=DIM, anchor="ls")
    d.text((s(cx), s(cy + R * 0.40 + 22 * 0.35)), "+12 ¢", font=ImageFont.truetype(light, s(22)), fill=FG, anchor="ms")

    # bead, live: accent fill with its stroke; then the target ring, a marking on the glass
    th = theta(12)
    bx, by = cx + R * math.sin(th), cy - R * math.cos(th)
    circle(bx, by, b, fill=ACCENT)
    q = b * 0.34
    line(bx - q * math.sin(th), by + q * math.cos(th), bx + q * math.sin(th), by - q * math.cos(th), BG, 1.2)
    circle(cx, cy - R, tr, outline=DIM, width=s(1.25))

    title = ImageFont.truetype(light, s(74))
    tagline = ImageFont.truetype(regular, s(26))
    d.text((s(80), s(178)), "Guitar Tuner", font=title, fill=FG)
    d.text((s(84), s(286)), "Guided tuning, string by string", font=tagline, fill=DIM)

    im.resize((W, H), Image.LANCZOS).save(OUT, optimize=True)


if __name__ == "__main__":
    main()
