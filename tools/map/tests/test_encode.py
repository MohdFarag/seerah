"""Tests for the painted (RGBA) and value (Terrarium RGB) encoders."""

import numpy as np
from seerah_map.encode import TERRARIUM_OFFSET, TERRARIUM_RED_BASE, painted, value

_INK = (150, 118, 72)
_MAX_ALPHA = 0.6
_SIZE = 4


def _ramp() -> np.ndarray:
    """Darkness rising from 0 to 1 across a 4 x 4 grid, as float32."""
    return np.linspace(0, 1, _SIZE * _SIZE, dtype=np.float32).reshape(_SIZE, _SIZE)


def test_painted_ink_everywhere():
    out = painted(_ramp(), _INK, _MAX_ALPHA)
    assert out.dtype == np.uint8
    assert out.shape == (4, _SIZE, _SIZE)
    for channel, ink in enumerate(_INK):
        assert (out[channel] == ink).all()


def test_painted_alpha_truncates():
    out = painted(np.ones((1, 1), dtype=np.float32), _INK, _MAX_ALPHA)
    assert out[3, 0, 0] == 153


def test_average_keeps_ink():
    d = np.zeros((2, 2), dtype=np.float32)
    d[0, 0] = 1
    out = painted(d, _INK, _MAX_ALPHA)
    alphas = out[3].ravel().astype(np.int64)
    assert int(alphas.sum() // alphas.size) == 38
    for channel, ink in enumerate(_INK):
        assert (out[channel] == ink).all()


def test_value_terrarium_round_trip():
    d = _ramp()
    out = value(d).astype(np.float64)
    height = out[0] * 256 + out[1] + out[2] / 256 - TERRARIUM_OFFSET
    expected = np.floor(d.astype(np.float64) * 255)
    assert (np.abs(height - expected) <= 1).all()


def test_value_only_green_varies():
    out = value(_ramp())
    assert out.dtype == np.uint8
    assert out.shape == (3, _SIZE, _SIZE)
    assert (out[0] == TERRARIUM_RED_BASE).all()
    assert (out[2] == 0).all()
    assert len(np.unique(out[1])) > 1
