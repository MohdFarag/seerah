"""Land mask built from the Copernicus Water Body Mask (WBM).

WBM classes: 0 no water, 1 ocean, 2 lake, 3 river. Only the ocean is sea;
lakes and rivers stay land so inland relief is kept.
"""

import numpy as np
from scipy import ndimage

from seerah_map.grid import MASK_SIGMA_PX

_OCEAN_CLASS = 1
_MASK_MAX = 255.0
_BLUR_TRUNCATE = 3.0


def land_mask(wbm: np.ndarray, valid: np.ndarray) -> np.ndarray:
    """Turn a Water Body Mask into a smoothed land mask.

    Args:
        wbm: class values, 2-D (0 no water, 1 ocean, 2 lake, 3 river).
        valid: boolean array of the same shape; False where the source has
            no data.

    Returns:
        uint8 array of the same shape, 0 (sea) to 255 (certain land), in
        pixels blurred with sigma ``MASK_SIGMA_PX``. 0 wherever ``valid`` is
        False.
    """
    land = (wbm != _OCEAN_CLASS).astype(np.float64)
    blurred = ndimage.gaussian_filter(
        land, sigma=MASK_SIGMA_PX, truncate=_BLUR_TRUNCATE, mode="nearest"
    )
    mask = (blurred * _MASK_MAX).astype(np.uint8)
    mask[~valid] = 0
    return mask


def fill_invalid(a: np.ndarray, valid: np.ndarray) -> np.ndarray:
    """Fill invalid pixels from their nearest valid pixel.

    Args:
        a: 2-D array of any dtype.
        valid: boolean array of the same shape; True where ``a`` is usable.

    Returns:
        A copy of ``a`` in which every invalid pixel holds the value of the
        nearest valid pixel (Euclidean distance, in pixels).
    """
    nearest = ndimage.distance_transform_edt(~valid, return_indices=True)[1]
    return a[tuple(nearest)]
