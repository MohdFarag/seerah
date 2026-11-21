"""The relief style (style C): elevation in, darkness out.

Pure array code with no GDAL or file access. It computes what
``tools/spike/relief_style.py`` computed for build 11, written anew, plus a
per-row latitude correction so slopes use true ground distance. Elevations are
metres, pixel sizes metres, latitudes and angles degrees; darkness is 0 (none)
to 1 (full ink).
"""

import numpy as np
from scipy import ndimage

from seerah_map.config import StyleSettings
from seerah_map.grid import band_blur_radius_px

_BLUR_TRUNCATE = 3.0


def darkness(
    elevation: np.ndarray,
    land: np.ndarray,
    row_lat_deg: np.ndarray,
    pixel_m: float,
    s: StyleSettings,
    banded: bool,
) -> np.ndarray:
    """Shade an elevation grid into relief darkness.

    Args:
        elevation: 2-D elevation in metres, rows running north to south.
        land: boolean array of the same shape; False is sea.
        row_lat_deg: 1-D latitude of each row on the global grid (degrees).
        pixel_m: the grid's Web Mercator pixel size (metres, at the equator).
        s: the style settings (blurs in pixels).
        banded: True for the painted variant, stepped into ``s.bands`` levels.

    Returns:
        float32 darkness in [0, 1], the shape of ``elevation``; 0 on the sea.
    """
    z = ndimage.gaussian_filter(
        elevation.astype(np.float64), sigma=s.blur_px, truncate=_BLUR_TRUNCATE, mode="nearest"
    )
    z *= s.exaggeration
    gy, gx = np.gradient(z, pixel_m)
    cos_lat = np.cos(np.radians(row_lat_deg))[:, None]
    gy, gx = gy / cos_lat, gx / cos_lat
    slope = np.arctan(np.hypot(gx, gy))
    aspect = np.arctan2(-gx, gy)
    az, alt = np.radians(s.azimuth_deg), np.radians(s.altitude_deg)
    shade = np.clip(
        np.sin(alt) * np.cos(slope) + np.cos(alt) * np.sin(slope) * np.cos(az - aspect), 0, 1
    )
    d = np.clip((np.sin(alt) - shade) / np.sin(alt) * s.contrast, 0, 1)
    if banded:
        d = np.round(d * s.bands) / s.bands
        radius = band_blur_radius_px(s.band_blur_px)
        if s.band_blur_px > 0 and radius > 0:
            d = ndimage.gaussian_filter(d, sigma=s.band_blur_px, radius=radius, mode="nearest")
    d[~land] = 0
    return d.astype(np.float32)


def edge_fade(d: np.ndarray, distance_to_data_edge_deg: np.ndarray, fade_deg: float) -> np.ndarray:
    """Fade darkness linearly to 0 at the data extent's edge.

    Args:
        d: darkness in [0, 1].
        distance_to_data_edge_deg: each pixel's distance to the nearest edge of
            the data extent (degrees), broadcastable to ``d``.
        fade_deg: the width of the fade (degrees); beyond it ``d`` is unchanged.

    Returns:
        ``d`` scaled by min(distance / fade_deg, 1), in ``d``'s dtype.
    """
    weight = np.clip(distance_to_data_edge_deg / fade_deg, 0, 1)
    return (d * weight).astype(d.dtype)
