"""Tests for the relief style function and the edge fade."""

import numpy as np
import pytest
from seerah_map.config import StyleSettings
from seerah_map.grid import pixel_m
from seerah_map.style import darkness, edge_fade

_SIZE = 64
_SLOPE = 0.05  # metres of rise per metre of ground
_FADE_DEG = 0.5


def _lat(deg: float) -> np.ndarray:
    return np.full(_SIZE, deg)


def _land() -> np.ndarray:
    return np.ones((_SIZE, _SIZE), dtype=bool)


def _plane(rise_east: float, rise_south: float, lat_deg: float = 0.0) -> np.ndarray:
    """A tilted plane; at ``lat_deg`` a pixel covers pixel_m·cos(lat) metres of ground."""
    ground = pixel_m() * np.cos(np.radians(lat_deg))
    rows, cols = np.mgrid[0:_SIZE, 0:_SIZE].astype(np.float64)
    return (1000 + ground * (rise_east * cols + rise_south * rows)).astype(np.float32)


def _centre(a: np.ndarray) -> float:
    return float(a[_SIZE // 2, _SIZE // 2])


def test_flat_is_zero():
    z = np.full((_SIZE, _SIZE), 500, dtype=np.float32)
    d = darkness(z, _land(), _lat(0), pixel_m(), StyleSettings(), banded=False)
    assert d.dtype == np.float32
    assert d.shape == z.shape
    assert (d == 0).all()


def test_light_direction():
    s = StyleSettings()
    # Ground rising to the south-east faces north-west, towards the light.
    facing_nw = darkness(_plane(_SLOPE, _SLOPE), _land(), _lat(0), pixel_m(), s, banded=False)
    facing_se = darkness(_plane(-_SLOPE, -_SLOPE), _land(), _lat(0), pixel_m(), s, banded=False)
    assert _centre(facing_nw) < _centre(facing_se)


@pytest.mark.parametrize("banded", [False, True])
def test_sea_is_zero(banded):
    land = _land()
    land[:, : _SIZE // 2] = False
    d = darkness(_plane(-_SLOPE, -_SLOPE), land, _lat(0), pixel_m(), StyleSettings(), banded)
    assert (d[~land] == 0).all()
    assert (d[land] > 0).any()


def test_latitude_correction():
    s = StyleSettings()
    at_12 = darkness(_plane(-_SLOPE, 0, 12), _land(), _lat(12), pixel_m(), s, banded=False)
    at_32 = darkness(_plane(-_SLOPE, 0, 32), _land(), _lat(32), pixel_m(), s, banded=False)
    assert _centre(at_12) > 0
    assert abs(_centre(at_12) - _centre(at_32)) < 1e-3


def test_banded_has_four_levels():
    rng = np.random.default_rng(1)
    z = (rng.random((_SIZE, _SIZE)) * 2000).astype(np.float32)
    s = StyleSettings(bands=3, band_blur_px=0)
    d = darkness(z, _land(), _lat(0), pixel_m(), s, banded=True)
    levels = np.array([0, 1 / 3, 2 / 3, 1], dtype=np.float32)
    assert np.isin(d, levels).all()


def test_edge_fade():
    d = np.ones(5, dtype=np.float32)
    dist = np.array([0, _FADE_DEG / 4, _FADE_DEG / 2, _FADE_DEG, 2 * _FADE_DEG])
    np.testing.assert_allclose(edge_fade(d, dist, _FADE_DEG), [0, 0.25, 0.5, 1, 1])
