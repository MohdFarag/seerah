"""Tests for applying terrain edits."""

import math

import numpy as np
import pytest
from seerah_map.config import Bounds, CleanupEdit, CleanupSource, Edit, StyleSettings
from seerah_map.edits import (
    applicable,
    apply,
    apply_cleanup,
    apply_wbm_cleanup,
    touches,
    validate_cleanups,
)
from seerah_map.errors import ConfigError
from seerah_map.grid import BASE_ZOOM, TILE_PX, Block, Window, border_px, pixel_m, row_latitudes
from seerah_map.mask import land_mask
from seerah_map.style import darkness

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


def _cleanup(
    geometry: dict,
    operation: str = "remove-land",
    *,
    edit_id: str = "c1",
    review: str = "approved",
) -> CleanupEdit:
    return CleanupEdit(
        id=edit_id,
        geometry=geometry,
        operation=operation,
        reason="Remove or infill synthetic modern coastal feature",
        review=review,
        sources=(CleanupSource(kind="imagery", work="Synthetic Sat", locator="scene-1"),),
    )


def _valid_mask(window: Window) -> np.ndarray:
    return np.ones((window.rows, window.cols), dtype=bool)


def test_cleanup_applicable_and_touches():
    window = _window_at(_LON, _LAT)
    far = _window_at(_LON + 2, _LAT)
    near_box = _lonlat_box(window, 20, 30)
    far_box = _lonlat_box(far, 20, 30)
    a1 = _cleanup(near_box, edit_id="a1", review="approved")
    d1 = _cleanup(near_box, edit_id="d1", review="draft")
    r1 = _cleanup(near_box, edit_id="r1", review="in-review")
    t1 = _cleanup(near_box, edit_id="t1", review="title-approved")
    a2 = _cleanup(far_box, edit_id="a2", review="approved")

    kept, skipped = applicable([a1, d1, r1, t1, a2])
    assert kept == [a1, a2]
    assert skipped == ["d1", "r1", "t1"]

    assert touches(a1, window)
    assert not touches(a2, window)

    # Just outside `window` west edge: `reach_m=0.0` skips it, large `reach_m` hits it.
    just_left = _lonlat_box(Window(window.col0 - 20, window.row0, window.cols, window.rows), 5, 10)
    adjacent = _cleanup(just_left, edit_id="adj")
    assert not touches(adjacent, window)
    assert touches(adjacent, window, reach_m=2000.0)
    with pytest.raises(ValueError, match="non-negative"):
        touches(adjacent, window, reach_m=-1.0)


def test_apply_cleanup_remove_land_and_immutability():
    window = _window_at(_LON, _LAT)
    elev = np.full((window.rows, window.cols), 45.0, dtype=np.float32)
    wbm = np.zeros((window.rows, window.cols), dtype=np.uint8)
    valid = _valid_mask(window)
    valid[:10, :] = False

    clean = _cleanup(_lonlat_box(window, 40, 40), "remove-land")
    out_elev, out_wbm = apply_cleanup(elev, wbm, valid, [clean], window)

    # Inputs stay unchanged.
    assert (elev == 45.0).all()
    assert (wbm == 0).all()

    # Inside & valid turned into sea (wbm=1) and 0.0 m elevation.
    assert (out_wbm[45:75, 45:75] == 1).all()
    assert (out_elev[45:75, 45:75] == 0.0).all()
    # Outside & invalid stayed untouched.
    assert out_wbm[5, 5] == 0
    assert out_elev[5, 5] == 45.0
    assert out_wbm[100, 100] == 0
    assert out_elev[100, 100] == 45.0

    # Overlapping invalid band: remove-land touching row 0..80 leaves ~valid rows untouched.
    spanning = _cleanup(_lonlat_box(window, 0, 50), "remove-land")
    _, wbm_span = apply_cleanup(elev, wbm, valid, [spanning], window)
    assert (wbm_span[:10, :50] == 0).all()
    assert (wbm_span[10:45, 5:45] == 1).all()

    np.testing.assert_array_equal(apply_wbm_cleanup(wbm, valid, [clean], window), out_wbm)


def test_remove_land_zeroes_elevation_to_prevent_coastal_blur_bleed():
    window = _window_at(_LON, _LAT)
    valid = _valid_mask(window)
    # Natural flat land on cols 0..41, tall artificial pier inside cols 42..78 / rows 42..78.
    elev = np.zeros((window.rows, window.cols), dtype=np.float32)
    elev[42:78, 42:78] = 200.0
    wbm = np.ones((window.rows, window.cols), dtype=np.uint8)
    wbm[:, :42] = 0
    wbm[42:78, 42:78] = 0

    clean = _cleanup(_lonlat_box(window, 40, 40), "remove-land")
    cleaned_elev, cleaned_wbm = apply_cleanup(elev, wbm, valid, [clean], window)
    cleaned_land = land_mask(cleaned_wbm, valid) >= 128

    lats = row_latitudes(window)
    px_m = window.pixel_m()
    s = StyleSettings()
    shade_clean = darkness(cleaned_elev, cleaned_land, lats, px_m, s, banded=False)
    shade_wbm_only = darkness(elev, cleaned_land, lats, px_m, s, banded=False)

    # Zeroing elevation keeps natural coast flat (0); un-zeroed bleeds hillshade onto coast.
    assert (shade_clean[45:75, 32:41] == 0.0).all()
    assert shade_wbm_only[45:75, 32:41].max() > 0.05


def test_apply_cleanup_fill_water_only_infills_ocean_pixels():
    window = _window_at(_LON, _LAT)
    valid = _valid_mask(window)
    valid[55:60, 55:60] = False

    elev = np.full((window.rows, window.cols), 120.0, dtype=np.float32)
    wbm = np.ones((window.rows, window.cols), dtype=np.uint8)
    # Place existing land (0), lake (2), and river (3) inside the fill-water polygon.
    wbm[45:50, 45:50] = 0
    wbm[50:55, 50:55] = 2
    wbm[60:65, 60:65] = 3
    elev[wbm == 1] = -3.0  # synthetic negative DEM noise in basin; valid ocean pixels -> 0.0

    fill = _cleanup(_lonlat_box(window, 40, 40), "fill-water")
    out_elev, out_wbm = apply_cleanup(elev, wbm, valid, [fill], window)

    # Pure ocean pixel inside polygon became wbm=0, elevation=0.0.
    assert out_wbm[70, 70] == 0
    assert out_elev[70, 70] == 0.0
    # Existing land (0), lake (2), river (3) and their elevations stayed untouched.
    assert (out_wbm[46:49, 46:49] == 0).all() and (out_elev[46:49, 46:49] == 120.0).all()
    assert (out_wbm[51:54, 51:54] == 2).all() and (out_elev[51:54, 51:54] == 120.0).all()
    assert (out_wbm[61:64, 61:64] == 3).all() and (out_elev[61:64, 61:64] == 120.0).all()
    # Invalid pixel stayed untouched.
    assert out_wbm[57, 57] == 1 and out_elev[57, 57] == -3.0
    # Outside polygon ocean stayed untouched.
    assert out_wbm[5, 5] == 1 and out_elev[5, 5] == -3.0

    np.testing.assert_array_equal(apply_wbm_cleanup(wbm, valid, [fill], window), out_wbm)


def test_apply_cleanup_supports_multipolygon_and_polygon_with_hole():
    window = _window_at(_LON, _LAT)
    valid = _valid_mask(window)
    outer = _lonlat_box(window, 20, 80)["coordinates"][0]
    hole = list(reversed(_lonlat_box(window, 45, 30)["coordinates"][0]))
    poly_with_hole = {"type": "Polygon", "coordinates": [outer, hole]}

    wbm = np.zeros((window.rows, window.cols), dtype=np.uint8)
    elev = np.full((window.rows, window.cols), 30.0, dtype=np.float32)
    out_elev, out_wbm = apply_cleanup(
        elev, wbm, valid, [_cleanup(poly_with_hole, "remove-land")], window
    )
    assert out_wbm[25, 25] == 1 and out_elev[25, 25] == 0.0
    assert out_wbm[60, 60] == 0 and out_elev[60, 60] == 30.0

    p1 = _lonlat_box(window, 15, 25)["coordinates"]
    p2 = _lonlat_box(window, 80, 25)["coordinates"]
    multi = {"type": "MultiPolygon", "coordinates": [p1, p2]}
    wbm_ocean = np.ones((window.rows, window.cols), dtype=np.uint8)
    out_wbm_multi = apply_wbm_cleanup(wbm_ocean, valid, [_cleanup(multi, "fill-water")], window)
    assert out_wbm_multi[25, 25] == 0
    assert out_wbm_multi[90, 90] == 0
    assert out_wbm_multi[60, 60] == 1


def test_apply_cleanup_rejects_bad_operation_and_shape_mismatch():
    window = _window_at(_LON, _LAT)
    far = _window_at(_LON + 5, _LAT)
    valid = _valid_mask(window)
    wbm = np.zeros((window.rows, window.cols), dtype=np.uint8)
    elev = np.zeros((window.rows, window.cols), dtype=np.float32)

    bad_op = _cleanup(_lonlat_box(far, 10, 10), operation="raise")
    with pytest.raises(ValueError, match="unknown operation"):
        apply_cleanup(elev, wbm, valid, [bad_op], window)
    with pytest.raises(ValueError, match="unknown operation"):
        apply_wbm_cleanup(wbm, valid, [bad_op], window)

    with pytest.raises(ValueError, match="shape"):
        apply_cleanup(elev[:-1, :], wbm, valid, [], window)
    with pytest.raises(ValueError, match="shape"):
        apply_wbm_cleanup(wbm[:-1, :], valid, [], window)


def test_validate_cleanups_rules():
    window = _window_at(_LON, _LAT)
    box_a = _lonlat_box(window, 10, 30)
    box_b = _lonlat_box(window, 70, 30)
    box_overlap = _lonlat_box(window, 20, 30)
    extent = Bounds(39.0, 29.0, 40.5, 31.0)

    # Disjoint approved edits and same-op overlapping approved edits pass.
    validate_cleanups(
        [
            _cleanup(box_a, "remove-land", edit_id="r1"),
            _cleanup(box_overlap, "remove-land", edit_id="r2"),
            _cleanup(box_b, "fill-water", edit_id="f1"),
        ],
        data_extent=extent,
    )

    # Unapproved opposite overlapping edits or unapproved edits outside extent pass.
    far_box = _lonlat_box(_window_at(10.0, 10.0), 10, 30)
    validate_cleanups(
        [
            _cleanup(box_a, "remove-land", edit_id="r1"),
            _cleanup(box_overlap, "fill-water", edit_id="f-draft", review="draft"),
            _cleanup(far_box, "fill-water", edit_id="f-far", review="in-review"),
        ],
        data_extent=extent,
    )

    # Conflicting approved opposite operations fail.
    with pytest.raises(ConfigError, match="overlap"):
        validate_cleanups(
            [
                _cleanup(box_a, "remove-land", edit_id="r1"),
                _cleanup(box_overlap, "fill-water", edit_id="f1"),
            ],
            data_extent=extent,
        )

    # Approved edit outside data_extent (e.g. swapped lat/lon) fails.
    with pytest.raises(ConfigError, match="data_extent"):
        validate_cleanups([_cleanup(far_box, "remove-land", edit_id="far")], data_extent=extent)

    # Self-intersecting bow-tie geometry fails IsValid() even when draft.
    bowtie = {
        "type": "Polygon",
        "coordinates": [[[39.5, 30.0], [39.6, 30.1], [39.6, 30.0], [39.5, 30.1], [39.5, 30.0]]],
    }
    with pytest.raises(ConfigError, match="invalid or zero-area"):
        validate_cleanups([_cleanup(bowtie, "remove-land", edit_id="bow", review="draft")])

    # Collinear zero-area polygon fails Area() > 0.
    collinear = {
        "type": "Polygon",
        "coordinates": [[[39.5, 30.0], [39.6, 30.0], [39.7, 30.0], [39.5, 30.0]]],
    }
    with pytest.raises(ConfigError, match="invalid or zero-area"):
        validate_cleanups([_cleanup(collinear, "remove-land", edit_id="flat", review="draft")])

    # Duplicate ID and unknown operation fail.
    with pytest.raises(ConfigError, match="duplicate"):
        validate_cleanups([_cleanup(box_a, edit_id="dup"), _cleanup(box_b, edit_id="dup")])
    with pytest.raises(ConfigError, match="unknown operation"):
        validate_cleanups([_cleanup(box_a, operation="carve", edit_id="badop")])
