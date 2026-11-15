"""Encoders that turn darkness into the two tile layouts.

``painted`` makes the RGBA tile for the map: the ink colour everywhere, with
the darkness in the alpha channel. ``value`` makes the RGB tile that carries
darkness as a Terrarium-style height, so the client can decode it. Both take
darkness in [0, 1] (float32, dimensionless) and return uint8 arrays. Bytes are
truncated, never rounded, so the encoders match what GDAL's byte maths does.
"""

import numpy as np

_BYTE_MAX = 255
TERRARIUM_OFFSET = 32768
TERRARIUM_RED_BASE = TERRARIUM_OFFSET // 256


def painted(d: np.ndarray, ink_rgb: tuple[int, int, int], max_alpha: float) -> np.ndarray:
    """Encode darkness as an ink-coloured RGBA tile.

    Args:
        d: darkness, 2-D float32 in [0, 1] (0 none, 1 full ink).
        ink_rgb: the ink colour, three 8-bit channel values (0 to 255).
        max_alpha: the opacity at full darkness, in [0, 1] (dimensionless).

    Returns:
        uint8 array of shape (4, h, w): R, G, B hold ``ink_rgb`` everywhere;
        A holds the truncated ``d * max_alpha * 255``.
    """
    h, w = d.shape
    out = np.empty((4, h, w), dtype=np.uint8)
    out[:3] = np.asarray(ink_rgb, dtype=np.uint8)[:, None, None]
    out[3] = (d.astype(np.float64) * max_alpha * _BYTE_MAX).astype(np.uint8)
    return out


def value(d: np.ndarray) -> np.ndarray:
    """Encode darkness as a Terrarium-style RGB tile.

    Decoding is ``R * 256 + G + B / 256 - TERRARIUM_OFFSET``, which gives the
    truncated ``d * 255`` (a unitless value from 0 to 255). Red is the fixed base
    ``TERRARIUM_RED_BASE``, blue is 0, and only green varies.

    Args:
        d: darkness, 2-D float32 in [0, 1].

    Returns:
        uint8 array of shape (3, h, w): R = ``TERRARIUM_RED_BASE``,
        G = the truncated ``d * 255``, B = 0.
    """
    h, w = d.shape
    out = np.zeros((3, h, w), dtype=np.uint8)
    out[0] = TERRARIUM_RED_BASE
    out[1] = (d.astype(np.float64) * _BYTE_MAX).astype(np.uint8)
    return out
