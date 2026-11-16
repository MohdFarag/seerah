import math

import morecantile
import numpy as np
from seerah_map.config import Bounds, Edit, StyleSettings
from seerah_map.grid import (
    Block,
    Window,
    blocks,
    border_px,
    expected_tiles,
    pixel_m,
    row_latitudes,
    view_reach_km,
)

BLOCK_ZOOM = 9
BLOCK_PX = 2048
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
