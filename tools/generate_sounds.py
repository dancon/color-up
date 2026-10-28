#!/usr/bin/env python3
"""Generate original short game effects with Python's standard library."""
import math
import random
import pathlib
import struct
import wave

RATE = 22050
DEST = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/audio"


def tone(start, end, duration):
    count = round(RATE * duration)
    phase = 0.0
    result = []
    for i in range(count):
        t = i / count
        phase += 2 * math.pi * (start + (end - start) * t) / RATE
        envelope = math.sin(math.pi * t) ** 2
        result.append(round(16000 * envelope * math.sin(phase)))
    return result


DEST.mkdir(parents=True, exist_ok=True)
random_source = random.Random(20261027)
dry_crayon = []
low_fast = 0.0
low_slow = 0.0
for index in range(round(RATE * .08)):
    noise = random_source.uniform(-1, 1)
    low_fast += .65 * (noise - low_fast)
    low_slow += .12 * (noise - low_slow)
    envelope = math.sin(math.pi * index / (RATE * .08)) ** 2
    dry_crayon.append(round(12000 * envelope * (low_fast - low_slow)))
effects = {
    "crayon": dry_crayon,
    "pop": tone(620, 190, .13),
    "select": tone(760, 880, .06),
    "erase": tone(300, 170, .1),
    "complete": sum((tone(hz, hz, .13) for hz in (523.25, 659.25, 783.99, 1046.5)), []),
}
for name, samples in effects.items():
    with wave.open(str(DEST / f"{name}.wav"), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(struct.pack(f"<{len(samples)}h", *samples))
