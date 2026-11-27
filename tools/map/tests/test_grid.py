import math
from pathlib import Path

import morecantile
import numpy as np
import pytest
from seerah_map.config import Bounds, Edit, StyleSettings, load_profile
from seerah_map.grid import (
    BAND_BLUR_RADIUS_PX,
    BASE_ZOOM,
    TILE_PX,
    Block,
    Window,
    band_blur_radius_px,
    blocks,
    border_px,
    check_base_coverage,
    check_data_extent_coverage,
    expected_tiles,
    natural_earth_max_zoom,
    pixel_m,
    row_latitudes,
    scale_style,
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

    coarse_block = blocks(DATA_EXTENT, 6, zoom=8)[0]
    coarse_tiles = expected_tiles([coarse_block], 8)
    assert len({t for t in coarse_tiles if t[0] == 8}) == 16
    assert len({t for t in coarse_tiles if t[0] == 7}) == 4
    assert len({t for t in coarse_tiles if t[0] == 6}) == 1
    assert (6, coarse_block.x, coarse_block.y) in coarse_tiles
    with pytest.raises(ValueError, match="zoom"):
        expected_tiles([coarse_block], 9)


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


def test_pixel_m_scales_with_zoom():
    assert pixel_m() == pixel_m(BASE_ZOOM)
    assert pixel_m(10) == pytest.approx(pixel_m() * 4)
    assert pixel_m(0) == pytest.approx(pixel_m() * 2**BASE_ZOOM)
    assert pixel_m(14) == pytest.approx(pixel_m() / 4)


def test_window_zoom_and_pad_preserves_zoom():
    default = Window(0, 0, TILE_PX, TILE_PX)
    assert default.zoom == BASE_ZOOM
    assert default.pixel_m() == pixel_m()

    world = Window(0, 0, TILE_PX, TILE_PX, zoom=0)
    assert world.pixel_m() == pixel_m(0)
    origin_m = 20037508.342789244
    assert world.bounds_3857() == pytest.approx((-origin_m, -origin_m, origin_m, origin_m))
    assert world.geotransform() == pytest.approx(
        (-origin_m, pixel_m(0), 0.0, origin_m, 0.0, -pixel_m(0))
    )

    padded = Window(10, 20, 30, 40, zoom=8).pad(5)
    assert padded == Window(5, 15, 40, 50, zoom=8)


def test_window_pad_clamped():
    origin_m = 20037508.342789244
    world = Window(0, 0, TILE_PX, TILE_PX, zoom=0)
    padded_world, pads_world = world.pad_clamped(10)
    assert padded_world == world
    assert pads_world == (0, 0, 0, 0)
    west, south, east, north = padded_world.bounds_3857()
    assert -origin_m <= west and east <= origin_m
    assert -origin_m <= south and north <= origin_m

    top_left = Window(0, 0, TILE_PX, TILE_PX, zoom=1)
    assert top_left.pad_clamped(10) == (Window(0, 0, TILE_PX + 10, TILE_PX + 10, 1), (0, 0, 10, 10))

    bottom_right = Window(TILE_PX, TILE_PX, TILE_PX, TILE_PX, zoom=1)
    assert bottom_right.pad_clamped(10) == (
        Window(TILE_PX - 10, TILE_PX - 10, TILE_PX + 10, TILE_PX + 10, 1),
        (10, 10, 0, 0),
    )

    interior = blocks(DATA_EXTENT, BLOCK_ZOOM)[0].window
    assert interior.pad_clamped(38) == (interior.pad(38), (38, 38, 38, 38))
    assert interior.pad_clamped(0) == (interior, (0, 0, 0, 0))
    with pytest.raises(ValueError):
        interior.pad_clamped(-1)


def test_row_latitudes_at_coarse_zoom():
    lats = row_latitudes(Window(0, 0, TILE_PX, TILE_PX, zoom=0))
    assert lats.shape == (TILE_PX,)
    assert np.all(np.diff(lats) < 0)
    assert lats[0] == pytest.approx(-lats[-1])


def test_blocks_at_coarse_zoom_and_validation():
    with pytest.raises(ValueError):
        blocks(DATA_EXTENT, 9, zoom=8)

    coarse = blocks(DATA_EXTENT, 6, zoom=8)
    side_px = TILE_PX * 2 ** (8 - 6)
    assert coarse
    assert all(b.window.zoom == 8 for b in coarse)
    assert all(b.window.cols == side_px and b.window.rows == side_px for b in coarse)
    assert all(b.window.col0 == b.x * side_px and b.window.row0 == b.y * side_px for b in coarse)

    assert blocks(DATA_EXTENT, 0, zoom=0) == [Block(0, 0, 0, Window(0, 0, TILE_PX, TILE_PX, 0))]


def test_scale_style_and_band_blur_radius():
    base = StyleSettings()
    assert scale_style(base, BASE_ZOOM) == base

    coarse = scale_style(base, 8)
    assert coarse.blur_px == pytest.approx(base.blur_px / 16)
    assert coarse.band_blur_px == pytest.approx(base.band_blur_px / 16)
    assert coarse.exaggeration == base.exaggeration
    assert coarse.azimuth_deg == base.azimuth_deg
    assert coarse.altitude_deg == base.altitude_deg
    assert coarse.contrast == base.contrast
    assert coarse.bands == base.bands
    assert coarse.ink_rgb == base.ink_rgb
    assert coarse.max_alpha == base.max_alpha

    assert band_blur_radius_px(base.band_blur_px) == BAND_BLUR_RADIUS_PX == 4
    assert band_blur_radius_px(coarse.band_blur_px) == 0
    assert band_blur_radius_px(0.0) == 0


def test_border_scales_down_at_coarse_zooms():
    z12_block = blocks(DATA_EXTENT, 6, zoom=12)[0]
    z8_block = blocks(DATA_EXTENT, 6, zoom=8)[0]
    z0_block = blocks(DATA_EXTENT, 0, zoom=0)[0]

    assert border_px(StyleSettings(), [], z8_block) == 2 + 0 + 1 + 1 == 4
    assert border_px(StyleSettings(), [], z0_block) == 1 + 0 + 1 + 1 == 3

    edit = _feather_edit(50000.0)
    tms = morecantile.tms.get("WebMercatorQuad")
    bbox = tms.bounds(morecantile.Tile(z8_block.x, z8_block.y, z8_block.z))
    poleward = max(abs(bbox.bottom), abs(bbox.top))
    expected_edit_px = math.ceil(50000.0 / (pixel_m(8) * math.cos(math.radians(poleward))))
    assert border_px(StyleSettings(), [edit], z8_block) == 4 + expected_edit_px
    assert border_px(StyleSettings(), [edit], z8_block) < border_px(
        StyleSettings(), [edit], z12_block
    )


def test_check_data_extent_coverage_float_tolerance():
    from seerah_map.grid import Bounds, check_data_extent_coverage

    cam = Bounds(west=58.85, south=20.55, east=59.00, north=20.70)
    data = Bounds(west=58.80, south=20.50, east=59.05, north=20.75)
    check_data_extent_coverage(data, cam, margin_deg=0.05)
    check_data_extent_coverage(data, cam, margin_deg=0.05 + 5e-10)
    with pytest.raises(ValueError, match="does not cover required extent"):
        check_data_extent_coverage(data, cam, margin_deg=0.05 + 1e-6)


def test_natural_earth_max_zoom_and_base_coverage_with_data_extent():
    from seerah_map.grid import Bounds, check_base_coverage, natural_earth_max_zoom

    cam = Bounds(west=58.85, south=20.55, east=59.00, north=20.70)
    tight_data = Bounds(west=58.80, south=20.50, east=59.05, north=20.75)
    wide_data = Bounds(west=30.0, south=10.0, east=65.0, north=35.0)
    ne_tight = natural_earth_max_zoom(cam, 0.05, 12, data_extent=tight_data)
    assert ne_tight >= 6
    check_base_coverage(cam, 0.05, ne_tight, 12, data_extent=tight_data)
    with pytest.raises(ValueError, match="Base coverage is incomplete"):
        check_base_coverage(cam, 0.05, ne_tight - 1, 12, data_extent=tight_data)
    ne_wide = natural_earth_max_zoom(cam, 0.05, 12, data_extent=wide_data)
    check_base_coverage(cam, 0.05, ne_wide, 12, data_extent=wide_data)
