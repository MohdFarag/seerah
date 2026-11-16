"""Tests for the land mask and the invalid-pixel fill."""

import numpy as np
from seerah_map.grid import MASK_SIGMA_PX
from seerah_map.mask import fill_invalid, land_mask

_SIZE = 64
_SMOOTHING_REACH_PX = int(2 * MASK_SIGMA_PX)


def _all_valid() -> np.ndarray:
    return np.ones((_SIZE, _SIZE), dtype=bool)


def test_ocean_is_sea():
    wbm = np.ones((_SIZE, _SIZE), dtype=np.uint8)
    assert (land_mask(wbm, _all_valid()) == 0).all()


def test_lakes_and_rivers_are_land():
    wbm = np.zeros((_SIZE, _SIZE), dtype=np.uint8)
    wbm[:, :8] = 1
    wbm[20:30, 30:40] = 2
    wbm[40:50, 30:40] = 3
    mask = land_mask(wbm, _all_valid())
    assert mask.dtype == np.uint8
    assert (mask[20:30, 30:40] == 255).all()
    assert (mask[40:50, 30:40] == 255).all()


def test_smoothing_softens_steps():
    wbm = np.zeros((_SIZE, _SIZE), dtype=np.uint8)
    wbm[:, : _SIZE // 2] = 1
    mask = land_mask(wbm, _all_valid())
    coast = _SIZE // 2
    band = mask[_SIZE // 2, coast - _SMOOTHING_REACH_PX : coast + _SMOOTHING_REACH_PX]
    assert ((band >= 1) & (band <= 254)).all()


def test_invalid_pixels_zero():
    wbm = np.zeros((_SIZE, _SIZE), dtype=np.uint8)
    valid = _all_valid()
    valid[:10, :] = False
    mask = land_mask(wbm, valid)
    assert (mask[:10, :] == 0).all()
    assert (mask[10:, :] > 0).all()


def test_fill_invalid_takes_nearest():
    a = np.zeros((3, 3), dtype=np.float32)
    a[1, 0] = 5
    valid = np.zeros((3, 3), dtype=bool)
    valid[1, 0] = True
    assert fill_invalid(a, valid)[1, 1] == 5
