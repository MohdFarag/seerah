#!/usr/bin/env python3
"""Turn a grey hillshade into a transparent overlay: dark where shaded, light where lit.

    python3 tools/spike/shade_to_rgba.py HILLSHADE.envi LANDMASK.envi OUT.raw

Inputs are single-band byte rasters in ENVI (raw) format, as written by
`gdal_translate -of ENVI`. Output is raw RGBA bytes (pixel-interleaved) of the
same size; build_base.sh wraps it in a VRT. Works in strips, so the size of the
raster is limited by disk, not memory. Needs numpy.
"""

import pathlib
import sys

import numpy as np

FLAT = 181.0  # hillshade value of flat ground
SHADOW = (74, 56, 32)  # warm brown, close to the Penpot terrain
LIGHT = (255, 250, 235)
ALPHA_BITS = 8  # 4 cuts the size by about 40% but bands visibly at zoom 13 and above
STRIP = 1024  # rows per strip


def size(envi: str) -> tuple[int, int]:
    hdr = pathlib.Path(envi).with_suffix(".hdr").read_text()
    vals = {
        k.strip(): v.strip()
        for k, v in (line.split("=", 1) for line in hdr.splitlines() if "=" in line)
    }
    return int(vals["samples"]), int(vals["lines"])


def main() -> int:
    shade_path, mask_path, out_path = sys.argv[1:4]
    w, h = size(shade_path)
    shade_all = np.memmap(shade_path, dtype=np.uint8, mode="r", shape=(h, w))
    mask_all = np.memmap(mask_path, dtype=np.uint8, mode="r", shape=(h, w))
    out = np.memmap(out_path, dtype=np.uint8, mode="w+", shape=(h, w, 4))
    step = 256 >> ALPHA_BITS
    for y in range(0, h, STRIP):
        shade = shade_all[y : y + STRIP].astype(np.float32)
        land = mask_all[y : y + STRIP] > 0
        dark = np.clip((FLAT - shade) / FLAT, 0, 1)
        lit = np.clip((shade - FLAT) / (255 - FLAT), 0, 1)
        alpha = np.where(dark > 0, dark * 0.75, lit * 0.55)
        alpha[~land] = 0
        block = out[y : y + STRIP]
        for i in range(3):
            block[..., i] = np.where(dark > 0, SHADOW[i], LIGHT[i])
        # Transparency is stored losslessly and dominates the file size.
        level = (alpha * 255).astype(np.uint8) // step
        block[..., 3] = np.where(level == 0, 0, level * step + step // 2)
    out.flush()
    print(f"{w}x{h} relief")
    return 0


if __name__ == "__main__":
    sys.exit(main())
