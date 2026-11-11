#!/usr/bin/env python3
"""Turn a grey hillshade into a transparent overlay: dark where shaded, light where lit.

    python3 tools/spike/shade_to_rgba.py HILLSHADE.png LANDMASK.png OUT.png

The map draws this over the land colour, so flat ground stays the land colour
and the sea (mask value 0) stays fully transparent. Needs Pillow and numpy.
"""

import sys

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
FLAT = 181.0  # hillshade value of flat ground
SHADOW = (74, 56, 32)  # warm brown, close to the Penpot terrain
LIGHT = (255, 250, 235)


def main() -> int:
    shade_path, mask_path, out_path = sys.argv[1:4]
    shade = np.asarray(Image.open(shade_path).convert("L"), dtype=np.float32)
    land = np.asarray(Image.open(mask_path).convert("L")) > 0
    dark = np.clip((FLAT - shade) / FLAT, 0, 1)
    lit = np.clip((shade - FLAT) / (255 - FLAT), 0, 1)
    alpha = np.where(dark > 0, dark * 0.75, lit * 0.55)
    alpha[~land] = 0
    rgba = np.empty((*shade.shape, 4), dtype=np.uint8)
    for i in range(3):
        rgba[..., i] = np.where(dark > 0, SHADOW[i], LIGHT[i])
    rgba[..., 3] = (alpha * 255).astype(np.uint8)
    Image.fromarray(rgba, "RGBA").save(out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
