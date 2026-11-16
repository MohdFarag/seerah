"""The approved look (G8): the style function must reproduce the spike's build-11 relief.

The fixture was made once by ``fixtures/make_spike_relief.py`` running
``tools/spike/relief_style.py``. Do not loosen the tolerance to make this pass.
"""

from pathlib import Path

import numpy as np
from seerah_map.config import StyleSettings
from seerah_map.style import darkness

_FIXTURE = Path(__file__).parent / "fixtures" / "spike_relief.npz"
_SPIKE_EXAGGERATION = 8.0
_SPIKE_MAX_ALPHA = 0.6
_SEA_BELOW_M = 0.5
_ALPHA_MAX = 255
_MIN_WITHIN_ONE = 0.999
_MAX_DIFF = 2


def test_matches_spike():
    f = np.load(_FIXTURE)
    elevation, expected = f["elevation"], f["alpha"].astype(np.int32)
    rows = elevation.shape[0]
    d = darkness(
        elevation,
        elevation > _SEA_BELOW_M,
        np.zeros(rows),
        float(f["pixel_m"]),
        StyleSettings(exaggeration=_SPIKE_EXAGGERATION),
        banded=True,
    )
    alpha = np.trunc(d * _SPIKE_MAX_ALPHA * _ALPHA_MAX).astype(np.int32)
    diff = np.abs(alpha - expected)
    assert expected.max() > 0
    assert (diff <= 1).mean() >= _MIN_WITHIN_ONE
    assert diff.max() <= _MAX_DIFF
