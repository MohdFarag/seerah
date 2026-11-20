import math
from pathlib import Path

import morecantile
import numpy as np
import pytest
from seerah_map.config import Bounds, Edit, StyleSettings, load_profile
from seerah_map.grid import (
    Block,
    Window,
    blocks,
    border_px,
    check_base_coverage,
    check_data_extent_coverage,
    expected_tiles,
    natural_earth_max_zoom,
    pixel_m,
    row_latitudes,
    shrink_extent,
    view_bounds,
    view_reach_km,
)

BLOCK_ZOOM = 9
BLOCK_PX = 2048
CAMERA = Bounds(34, 12, 60, 32)
DATA_EXTENT = Bounds(33, 11, 61, 33)


def _merc_x(lon: float) -> float:
    return math.radians(lon) * 6378137.0


def _feather_edit(feather_m: float) -> Edit:
    return Edit("e", {}, "raise", 10.0, None, None, feather_m, "approved")


def test_blocks_on_whole_pixels():
    block_list = blocks(DATA_EXTENT, BLOCK_ZOOM)
    assert all(b.window.col0 % BLOCK_PX == 0 for b in block_list)
    assert all(b.window.row0 % BLOCK_PX == 0 for b in block_list)
    assert block_list == sorted(block_list, key=lambda b: (b.y, b.x))
    west = min(b.window.bounds_3857()[0] for b in block_list)
    east = max(b.window.bounds_3857()[2] for b in block_list)
    assert west <= _merc_x(33)
    assert east >= _merc_x(61)
    assert block_list[0].id == f"z9-x{block_list[0].x}-y{block_list[0].y}"


def test_block_count_data_extent():
    assert 1350 <= len(blocks(DATA_EXTENT, BLOCK_ZOOM)) <= 1450


def test_border_default():
    block = blocks(DATA_EXTENT, BLOCK_ZOOM)[0]
    assert border_px(StyleSettings(), [], block) == 21 + 4 + 12 + 1 == 38


def test_border_grows_with_feather():
    block = blocks(DATA_EXTENT, BLOCK_ZOOM)[0]
    tms = morecantile.tms.get("WebMercatorQuad")
    bbox = tms.bounds(morecantile.Tile(block.x, block.y, block.z))
    poleward = max(abs(bbox.bottom), abs(bbox.top))
    extra = border_px(StyleSettings(), [_feather_edit(500)], block) - 38
    assert extra == math.ceil(500 / (pixel_m() * math.cos(math.radians(poleward))))


def test_border_counts_half_carve_width():
    block = blocks(DATA_EXTENT, BLOCK_ZOOM)[0]
    carve = Edit("c", {}, "carve", None, None, 200.0, 100.0, "approved")
    assert border_px(StyleSettings(), [carve], block) == border_px(
        StyleSettings(), [_feather_edit(200)], block
    )


def test_row_latitudes_decrease_southward():
    block = blocks(DATA_EXTENT, BLOCK_ZOOM)[0]
    lats = row_latitudes(block.window)
    assert lats.shape == (block.window.rows,)
    assert np.all(np.diff(lats) < 0)


def test_expected_tiles_from_blocks():
    block = Block(9, 5, 7, Window(5 * BLOCK_PX, 7 * BLOCK_PX, BLOCK_PX, BLOCK_PX))
    tiles = expected_tiles([block], 12)
    by_zoom = {z: {t for t in tiles if t[0] == z} for z in range(13)}
    assert len(by_zoom[12]) == 64
    assert len(by_zoom[11]) == 16
    assert len(by_zoom[10]) == 4
    assert len(by_zoom[9]) == 1
    assert all(len(by_zoom[z]) == 1 for z in range(9))
    assert (9, 5, 7) in tiles
    assert (0, 0, 0) in tiles


def test_view_reach_table():
    assert abs(view_reach_km(6, 550, 22) / 620 - 1) < 0.05
    assert abs(view_reach_km(9, 550, 22) / 80 - 1) < 0.05


def test_natural_earth_max_zoom_full_extent():
    assert natural_earth_max_zoom(CAMERA, margin_deg=1.0, max_zoom=12) == 9


def test_natural_earth_max_zoom_zero_margin():
    assert natural_earth_max_zoom(CAMERA, margin_deg=0.0, max_zoom=12) == 12


def test_natural_earth_max_zoom_stays_at_zero_when_view_is_contained():
    assert natural_earth_max_zoom(CAMERA, margin_deg=1.0, max_zoom=12, half_width_dp=1) == 0


def test_view_bounds_expands_and_clamps():
    tms = morecantile.tms.get("WebMercatorQuad")
    view = view_bounds(CAMERA, maplibre_zoom=5)
    assert view.west < CAMERA.west < CAMERA.east < view.east
    assert view.south < CAMERA.south < CAMERA.north < view.north
    assert view_bounds(CAMERA, maplibre_zoom=0) == Bounds(*tms.bbox)


def test_shrink_extent_stays_inside_data_extent():
    tms = morecantile.tms.get("WebMercatorQuad")
    west, south = tms.xy(DATA_EXTENT.west, DATA_EXTENT.south)
    east, north = tms.xy(DATA_EXTENT.east, DATA_EXTENT.north)
    shrunk = shrink_extent(DATA_EXTENT)
    shrunk_west, shrunk_south = tms.xy(shrunk.west, shrunk.south)
    shrunk_east, shrunk_north = tms.xy(shrunk.east, shrunk.north)
    insets_m = (shrunk_west - west, shrunk_south - south, east - shrunk_east, north - shrunk_north)
    assert insets_m == pytest.approx((2 * pixel_m(),) * 4)

    tiny_east, tiny_north = tms.lnglat(west + 3 * pixel_m(), south + 3 * pixel_m())
    with pytest.raises(ValueError):
        shrink_extent(Bounds(DATA_EXTENT.west, DATA_EXTENT.south, tiny_east, tiny_north))


def test_coverage_checks_pass_for_trial_and_example():
    check_data_extent_coverage(DATA_EXTENT, CAMERA, margin_deg=1.0)
    check_base_coverage(CAMERA, margin_deg=1.0, ne_max_zoom=9, max_zoom=12)

    example_yaml = Path(__file__).resolve().parents[3] / "map-data/example/example.yaml"
    example = load_profile(example_yaml)
    check_data_extent_coverage(example.data_extent, example.camera_bounds, example.margin_deg)
    check_base_coverage(example.camera_bounds, example.margin_deg, 12, example.max_zoom)

    with pytest.raises(ValueError):
        check_data_extent_coverage(Bounds(34, 11, 61, 33), CAMERA, margin_deg=1.0)
    with pytest.raises(ValueError, match=r"build zoom 9\b"):
        check_base_coverage(CAMERA, margin_deg=1.0, ne_max_zoom=8, max_zoom=12)
