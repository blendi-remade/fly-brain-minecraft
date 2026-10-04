#!/usr/bin/env python3
"""Generate the fruit fly spawn egg item texture.

Minecraft 26.x dropped the tinted spawn-egg template, so every spawn egg ships its own texture. This draws a 16x16
egg in the colours the egg used to be tinted with (fruit-fly tan 0xC8A165 with eye-red 0xB22222 spots): shaded body,
dark outline, a few red spots.

Writes src/main/resources/assets/fruitfly/textures/item/fruit_fly_spawn_egg.png.
Usage:  python tools/gen_spawn_egg.py
"""
import os
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: pip install pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "fruitfly", "textures", "item", "fruit_fly_spawn_egg.png")
N = 16
BASE = (0xC8, 0xA1, 0x65)
SPOT = (0xB2, 0x22, 0x22)
# spots as (x, y) pixels: two larger blotches and a few freckles, kept off the outline
SPOTS = [(6, 4), (7, 4), (6, 5),
         (9, 7), (10, 7), (9, 8), (10, 8),
         (5, 9), (6, 10),
         (8, 11), (9, 12), (10, 11)]


def inside(x, y):
    """Egg silhouette: an ellipse whose half-width shrinks toward the top."""
    cx, cy, rx, ry = 8.0, 8.5, 5.4, 7.0
    ty = (y + 0.5 - cy) / ry
    w = rx * (1.0 + 0.16 * ty)
    tx = (x + 0.5 - cx) / w
    return tx * tx + ty * ty <= 1.0


def shade(rgb, f):
    return tuple(max(0, min(255, int(round(c * f)))) for c in rgb)


def main():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    mask = [[inside(x, y) for x in range(N)] for y in range(N)]
    for row in mask:  # no one- or two-pixel nubs at the tips
        if sum(row) < 3:
            row[:] = [False] * N
    spots = set(SPOTS)
    for y in range(N):
        for x in range(N):
            if not mask[y][x]:
                continue
            edge = any(not (0 <= x + dx < N and 0 <= y + dy < N and mask[y + dy][x + dx])
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                img.putpixel((x, y), shade(BASE, 0.38) + (255,))
                continue
            # light from the top left: brighter there, darker toward the bottom right
            light = 1.12 - 0.045 * (x - 4) - 0.035 * (y - 3)
            colour = SPOT if (x, y) in spots else BASE
            img.putpixel((x, y), shade(colour, max(0.7, min(1.2, light))) + (255,))
    # specular highlight
    for (x, y) in ((5, 3), (4, 4), (4, 5)):
        if mask[y][x]:
            img.putpixel((x, y), shade(BASE, 1.35) + (255,))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
