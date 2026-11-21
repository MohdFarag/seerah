"""Tests for applying terrain edits."""

import math

import numpy as np
import pytest
from seerah_map.config import Edit, StyleSettings
from seerah_map.edits import applicable, apply, touches
from seerah_map.grid import BASE_ZOOM, TILE_PX, Block, Window, border_px, pixel_m

_FLAT_M = 100.0
_RAISE_M = 10.0
_FEATHER_M = 400.0
_SIZE_PX = 128
_LAT = 30.0
_LON = 39.6
_PX_TOLERANCE = 1


def _window_at(lon: float, lat: float, size: int = _SIZE_PX, zoom: int = BASE_ZOOM) -> Window:
    """A window of ``size`` pixels at ``zoom`` whose centre is at ``lon``, ``lat``."""
    world_px = TILE_PX * 2**zoom
    col = (lon + 180) / 360 * world_px
    y = math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))
    row = (1 - y / math.pi) / 2 * world_px
    return Window(int(col) - size // 2, int(row) - size // 2, size, size, zoom)


def _lonlat_box(window: Window, inset_px: int, span_px: int) -> dict:
    """A GeoJSON polygon covering ``span_px`` pixels, ``inset_px`` from the window's top-left."""
    from osgeo import osr

    west, _, _, north = window.bounds_3857()
    px_m = window.pixel_m()
    xs = (west + inset_px * px_m, west + (inset_px + span_px) * px_m)
    ys = (north - inset_px * px_m, north - (inset_px + span_px) * px_m)
    src = osr.SpatialReference()
    src.ImportFromEPSG(3857)
    dst = osr.SpatialReference()
    dst.ImportFromEPSG(4326)
    dst.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    src.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    t = osr.CoordinateTransformation(src, dst)
    corners = [(xs[0], ys[0]), (xs[1], ys[0]), (xs[1], ys[1]), (xs[0], ys[1]), (xs[0], ys[0])]
    ring = [list(t.TransformPoint(x, y)[:2]) for x, y in corners]
    return {"type": "Polygon", "coordinates": [ring]}


def _edit(geometry: dict, operation: str = "raise", **overrides) -> Edit:
    fields = {
        "id": "e1",
        "geometry": geometry,
        "operation": operation,
        "amount_m": _RAISE_M,
        "height_m": None,
        "width_m": None,
        "feather_m": 0.0,
        "review": "approved",
    }
    fields.update(overrides)
    return Edit(**fields)


def _flat(window: Window) -> np.ndarray:
    return np.full((window.rows, window.cols), _FLAT_M, dtype=np.float32)


def test_raise_inside_outline():
    window = _window_at(_LON, _LAT)
    edit = _edit(_lonlat_box(window, 40, 40))
    out = apply(_flat(window), [edit], window)
    assert np.allclose(out[50:70, 50:70], _FLAT_M + _RAISE_M)
    assert out[0, 0] == _FLAT_M


def test_apply_does_not_modify_input():
    window = _window_at(_LON, _LAT)
    elevation = _flat(window)
    apply(elevation, [_edit(_lonlat_box(window, 40, 40))], window)
    assert (elevation == _FLAT_M).all()


def test_feather_falls_linearly():
    window = _window_at(_LON, _LAT)
    edit = _edit(_lonlat_box(window, 40, 40), feather_m=_FEATHER_M)
    out = apply(_flat(window), [edit], window)
    change = out - _FLAT_M
    row = 60
    left_edge_col = 40
    ground_per_px = pixel_m() * math.cos(math.radians(_LAT))
    half_cols = round(_FEATHER_M / 2 / ground_per_px)
    assert change[row, left_edge_col - half_cols] == pytest.approx(_RAISE_M / 2, abs=0.5)
    beyond = math.ceil(_FEATHER_M / ground_per_px) + 2
    assert change[row, left_edge_col - beyond] == 0


def test_flatten_to_height():
    window = _window_at(_LON, _LAT)
    slope = np.tile(np.linspace(0, 200, window.cols, dtype=np.float32), (window.rows, 1))
    edit = _edit(_lonlat_box(window, 40, 40), "flatten-to", amount_m=None, height_m=600.0)
    out = apply(slope, [edit], window)
    assert np.allclose(out[50:70, 50:70], 600.0)
    assert out[0, 0] == slope[0, 0]


def test_carve_width_at_latitude():
    window = _window_at(_LON, _LAT)
    line = _lonlat_line(window)
    edit = _edit(line, "carve", amount_m=8.0, width_m=600.0)
    out = apply(_flat(window), [edit], window)
    column = out[:, window.cols // 2]
    lowered = int((column < _FLAT_M).sum())
    ground_per_px = pixel_m() * math.cos(math.radians(_LAT))
    assert abs(lowered * ground_per_px - 600.0) <= ground_per_px * _PX_TOLERANCE
    assert (column[column < _FLAT_M] == _FLAT_M - 8.0).all()


def _lonlat_line(window: Window) -> dict:
    """A horizontal GeoJSON line through the middle of the window's rows."""
    box = _lonlat_box(window, 0, window.cols)
    ring = box["coordinates"][0]
    lat = (ring[0][1] + ring[2][1]) / 2
    return {"type": "LineString", "coordinates": [[ring[0][0], lat], [ring[1][0], lat]]}


def test_empty_footprint_skipped():
    window = _window_at(_LON, _LAT)
    far = _window_at(_LON + 2, _LAT)
    edit = _edit(_lonlat_box(far, 40, 40))
    elevation = _flat(window)
    assert (apply(elevation, [edit], window) == elevation).all()
    assert not touches(edit, window)
    assert touches(_edit(_lonlat_box(window, 40, 40)), window)


def test_only_approved_applied():
    box = _lonlat_box(_window_at(_LON, _LAT), 40, 40)
    approved = _edit(box, id="a")
    draft = _edit(box, id="b", review="draft")
    kept, skipped = applicable([approved, draft])
    assert kept == [approved]
    assert skipped == ["b"]


def test_edit_across_window_edge():
    wide = _window_at(_LON, _LAT, size=2 * _SIZE_PX)
    edit = _edit(_lonlat_box(wide, _SIZE_PX - 20, 40), feather_m=_FEATHER_M)
    whole = apply(_flat(wide), [edit], wide)
    block = Block(BASE_ZOOM, 0, 0, wide)
    pad = border_px(StyleSettings(), [edit], block)
    for col0 in (wide.col0, wide.col0 + _SIZE_PX):
        core = Window(col0, wide.row0, _SIZE_PX, wide.rows)
        padded = core.pad(pad)
        out = apply(_flat(padded), [edit], padded)
        cropped = out[pad:-pad, pad:-pad]
        expected = whole[:, col0 - wide.col0 : col0 - wide.col0 + _SIZE_PX]
        assert np.allclose(cropped, expected, atol=1e-4)


def test_feather_uses_window_zoom():
    zoom = 10
    feather_m = 1600.0
    window = _window_at(_LON, _LAT, zoom=zoom)
    edit = _edit(_lonlat_box(window, 40, 40), feather_m=feather_m)
    out = apply(_flat(window), [edit], window)
    change = out - _FLAT_M
    ground_per_px_10 = pixel_m(zoom) * math.cos(math.radians(_LAT))
    ground_per_px_12 = pixel_m() * math.cos(math.radians(_LAT))
    half_cols = round(feather_m / 2 / ground_per_px_10)
    half_cols_z12 = round(feather_m / 2 / ground_per_px_12)
    assert half_cols == pytest.approx(half_cols_z12 / 4, abs=1)
    assert change[60, 40 - half_cols] == pytest.approx(_RAISE_M / 2, abs=0.5)
    beyond = math.ceil(feather_m / ground_per_px_10) + 2
    assert change[60, 40 - beyond] == 0
