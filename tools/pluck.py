"""Writes plucked strings as a 48 kHz mono 16-bit WAV, the sound tools/store_screenshots.sh feeds
the emulator's microphone. Each NOTE is MIDI[+-CENTS][@SECONDS]: that note, detuned by CENTS,
plucked SECONDS into the file; without @, one pluck every --period seconds. Notes a few
milliseconds apart are a strum.

    python3 tools/pluck.py e.wav --repeat 4 40-35                          # low E 35 cents flat, 4 plucks
    python3 tools/pluck.py strum.wav 40@0 45-30@0.015 50@0.03 55@0.045 59+25@0.06 64@0.075
"""
import argparse
import math
import random
import struct
import wave

RATE = 48000
A4 = 440.0
# Harmonic amplitudes as a phone microphone hears a guitar: the fundamental weaker than the 2nd.
HARMONICS = [0.35, 1.0, 0.8, 0.55, 0.4, 0.28, 0.2, 0.14, 0.1, 0.07]


def parse(spec: str):
    at = None
    if "@" in spec:
        spec, t = spec.split("@")
        at = float(t)
    cents = 0.0
    for sign in "+-":
        i = spec.find(sign, 1)
        if i > 0:
            spec, cents = spec[:i], float(spec[i:])
            break
    return int(spec), cents, at


def pluck(midi: int, cents: float, n: int, rng: random.Random) -> list:
    """n samples of decaying harmonics (the upper ones faster) and a short noise burst for the pick."""
    f = A4 * 2 ** ((midi - 69) / 12 + cents / 1200)
    out = [0.0] * n
    for k, a in enumerate(HARMONICS, 1):
        if k * f > RATE * 0.45:
            break
        rot = complex(math.cos(2 * math.pi * k * f / RATE), math.sin(2 * math.pi * k * f / RATE))
        decay = math.exp(-(0.9 + 0.5 * k) / RATE)
        z = a * complex(math.cos(rng.random() * 2 * math.pi), math.sin(rng.random() * 2 * math.pi))
        for i in range(n):
            out[i] += z.imag
            z *= rot * decay
    for i in range(n):
        t = i / RATE
        out[i] = 0.12 * (min(1.0, t / 0.004) * out[i] + (rng.random() * 2 - 1) * 0.3 * math.exp(-t / 0.01))
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("notes", nargs="+", metavar="NOTE")
    ap.add_argument("--period", type=float, default=2.5, help="seconds between plucks without @ (default 2.5)")
    ap.add_argument("--repeat", type=int, default=1, help="play the whole sequence this many times")
    ap.add_argument("--ring", type=float, default=4.0, help="seconds each pluck rings (default 4)")
    args = ap.parse_args()

    events, t = [], 0.0
    for spec in args.notes:
        midi, cents, at = parse(spec)
        if at is None:
            at, t = t, t + args.period
        events.append((at, midi, cents))
    span = max(t, max(e[0] for e in events) + 0.5)
    starts = [(round((r * span + at) * RATE), midi, cents) for r in range(args.repeat) for at, midi, cents in events]
    ring = round(args.ring * RATE)
    buf = [0.0] * (max(s for s, _, _ in starts) + ring)
    rng = random.Random(1)
    cache = {}
    for start, midi, cents in starts:
        if (midi, cents) not in cache:
            cache[midi, cents] = pluck(midi, cents, ring, rng)
        for i, v in enumerate(cache[midi, cents]):
            buf[start + i] += v
    noise = random.Random(2)
    with wave.open(args.out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(
            struct.pack("<h", max(-32767, min(32767, round(s * 32767 + noise.random() - 0.5)))) for s in buf))


if __name__ == "__main__":
    main()
