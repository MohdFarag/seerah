#!/usr/bin/env python3
"""Paint a generalized relief overlay from a DEM: soft shading in a few bands.

    python3 tools/spike/relief_style.py DEM.envi OUT.raw

DEM.envi is a Float32 ENVI raster in EPSG:3857 (from `gdalwarp -of ENVI`).
OUT.raw is pixel-interleaved RGBA of the same size; build_base.sh wraps it in
a VRT. The terrain is smoothed first, so the style shows the shape of the land
without claiming more detail than the source has. Needs numpy.
"""

import pathlib
import re
import sys

import numpy as np

SMOOTH_PX = 7.0  # Gaussian sigma, in pixels of the DEM
EXAGGERATION = 8.0
AZIMUTH, ALTITUDE = 315.0, 45.0
BANDS = 3
INK = (150, 118, 72)  # warm brown drawn over the land colour
MAX_ALPHA = 0.6


def read_envi(path: str) -> tuple[np.ndarray, float]:
    hdr = pathlib.Path(path).with_suffix(".hdr").read_text()
    w = int(re.search(r"samples\s*=\s*(\d+)", hdr).group(1))
    h = int(re.search(r"lines\s*=\s*(\d+)", hdr).group(1))
    px = float(
        re.search(r"map info\s*=\s*\{[^,]*,[^,]*,[^,]*,[^,]*,[^,]*,\s*([-\d.e]+)", hdr).group(1)
    )
    return np.fromfile(path, dtype=np.float32).reshape(h, w), px


def blur(a: np.ndarray, sigma: float) -> np.ndarray:
    r = int(sigma * 3)
    x = np.arange(-r, r + 1)
    k = np.exp(-x * x / (2 * sigma * sigma))
    k /= k.sum()
    p = np.pad(a, r, mode="edge")
    p = np.apply_along_axis(lambda v: np.convolve(v, k, "valid"), 1, p)
    return np.apply_along_axis(lambda v: np.convolve(v, k, "valid"), 0, p)


def main() -> int:
    dem_path, out_path = sys.argv[1:3]
    dem, px = read_envi(dem_path)
    land = dem > 0.5
    z = blur(np.nan_to_num(dem).astype(np.float32), SMOOTH_PX) * EXAGGERATION
    gy, gx = np.gradient(z, px)
    slope = np.arctan(np.hypot(gx, gy))
    aspect = np.arctan2(-gx, gy)
    az, alt = np.radians(AZIMUTH), np.radians(ALTITUDE)
    shade = np.clip(
        np.sin(alt) * np.cos(slope) + np.cos(alt) * np.sin(slope) * np.cos(az - aspect), 0, 1
    )
    dark = np.clip((np.sin(alt) - shade) / np.sin(alt) * 1.6, 0, 1)
    bands = blur(np.round(dark * BANDS) / BANDS, 1.5)
    alpha = np.where(land, bands * MAX_ALPHA, 0)
    out = np.empty((*dem.shape, 4), dtype=np.uint8)
    out[..., :3] = INK
    out[..., 3] = (alpha * 255).astype(np.uint8)
    out.tofile(out_path)
    print(f"{dem.shape[1]}x{dem.shape[0]} relief")
    return 0


if __name__ == "__main__":
    sys.exit(main())
