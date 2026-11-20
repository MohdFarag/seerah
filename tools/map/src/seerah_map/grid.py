"""The global pixel grid and the block layout every build stage shares.

All pixel windows are in zoom-12 Web Mercator pixels (``BASE_ZOOM``, 256-pixel
tiles) and use integer arithmetic, so blocks and tiles line up exactly.
Lengths are metres unless a name says otherwise; angles are degrees.
"""

import math
from dataclasses import dataclass

import morecantile
import numpy as np

from .config import Bounds, Edit, StyleSettings

BASE_ZOOM = 12
TILE_PX = 256
MASK_SIGMA_PX = 4.0
BAND_BLUR_RADIUS_PX = 4
# MatePad half-width working value, pending measurement in trial step 1, check 11.
WIDEST_HALF_SCREEN_DP = 550.0
# Working minimum for the trial (map trial specification Q1).
TRIAL_MIN_MAPLIBRE_ZOOM = 4

_EQUATOR_M = 40075016.68557849
_EARTH_RADIUS_M = 6378137.0
_ORIGIN_M = _EQUATOR_M / 2
_BLUR_SIGMAS = 3
_MAPLIBRE_TILE_PX = 512
_M_PER_KM = 1000.0
_TMS = morecantile.tms.get("WebMercatorQuad")


def pixel_m() -> float:
    """Ground size of one zoom-12 pixel at the equator (metres)."""
    return _EQUATOR_M / TILE_PX / 2**BASE_ZOOM


@dataclass(frozen=True)
class Window:
    """A rectangle of zoom-12 global pixels: top-left ``col0``/``row0``, size ``cols``/``rows``."""

    col0: int
    row0: int
    cols: int
    rows: int

    def bounds_3857(self) -> tuple[float, float, float, float]:
        """The window as (west, south, east, north) in EPSG:3857 metres."""
        west, north = self._origin_3857()
        return (
            west,
            north - self.rows * pixel_m(),
            west + self.cols * pixel_m(),
            north,
        )

    def geotransform(self) -> tuple[float, float, float, float, float, float]:
        """The GDAL geotransform: north-up, square pixels of ``pixel_m()`` metres."""
        west, north = self._origin_3857()
        return (west, pixel_m(), 0.0, north, 0.0, -pixel_m())

    def pad(self, n: int) -> "Window":
        """The window grown by ``n`` pixels on every side."""
        return Window(self.col0 - n, self.row0 - n, self.cols + 2 * n, self.rows + 2 * n)

    def _origin_3857(self) -> tuple[float, float]:
        return (
            -_ORIGIN_M + self.col0 * pixel_m(),
            _ORIGIN_M - self.row0 * pixel_m(),
        )


@dataclass(frozen=True)
class Block:
    """One XYZ tile at the block zoom, with its zoom-12 pixel window."""

    z: int
    x: int
    y: int
    window: Window

    @property
    def id(self) -> str:
        """A name safe for file names, for example ``z9-x123-y45``."""
        return f"z{self.z}-x{self.x}-y{self.y}"


def blocks(extent: Bounds, block_zoom: int) -> list[Block]:
    """Every tile at ``block_zoom`` that intersects ``extent`` (degrees), sorted by (y, x)."""
    side_px = TILE_PX * 2 ** (BASE_ZOOM - block_zoom)
    tiles = _TMS.tiles(extent.west, extent.south, extent.east, extent.north, [block_zoom])
    return [
        Block(t.z, t.x, t.y, Window(t.x * side_px, t.y * side_px, side_px, side_px))
        for t in sorted(tiles, key=lambda t: (t.y, t.x))
    ]


def _poleward_latitude(block: Block) -> float:
    """The block edge latitude farthest from the equator (degrees)."""
    bbox = _TMS.bounds(morecantile.Tile(block.x, block.y, block.z))
    return max(abs(bbox.bottom), abs(bbox.top))


def border_px(style: StyleSettings, edits: list[Edit], block: Block) -> int:
    """Pixels of context to read around ``block`` so no filter or edit is cut at its edge.

    Covers the relief blur, the band blur, the land-mask blur and one spare pixel,
    plus the widest edit reach (``feather_m`` plus half of a carve's ``width_m``),
    converted to pixels at the block's poleward edge, where pixels are shortest.
    """
    filters_px = (
        math.ceil(_BLUR_SIGMAS * style.blur_px)
        + BAND_BLUR_RADIUS_PX
        + math.ceil(_BLUR_SIGMAS * MASK_SIGMA_PX)
        + 1
    )
    reach_m = max((edit_reach_m(e) for e in edits), default=0.0)
    ground_per_px = pixel_m() * math.cos(math.radians(_poleward_latitude(block)))
    return filters_px + math.ceil(reach_m / ground_per_px)


def edit_reach_m(edit: Edit) -> float:
    """How far an edit changes the terrain beyond its geometry (metres on the ground).

    The feather width, plus half of a carve's width.
    """
    half_width = edit.width_m / 2 if edit.operation == "carve" and edit.width_m else 0.0
    return edit.feather_m + half_width


def row_latitudes(window: Window) -> np.ndarray:
    """Latitude of each pixel-row centre of ``window`` (degrees), north to south."""
    centres = window.row0 + 0.5 + np.arange(window.rows)
    northing_m = _ORIGIN_M - centres * pixel_m()
    return np.degrees(2 * np.arctan(np.exp(northing_m / _EARTH_RADIUS_M)) - math.pi / 2)


def expected_tiles(block_list: list[Block], max_zoom: int) -> set[tuple[int, int, int]]:
    """Every XYZ tile (z, x, y) at zooms 0 to ``max_zoom`` that the blocks' windows cover."""
    if max_zoom > BASE_ZOOM:
        raise ValueError(f"max_zoom {max_zoom} is above the base zoom {BASE_ZOOM}")
    tiles: set[tuple[int, int, int]] = set()
    for block in block_list:
        w = block.window
        for z in range(max_zoom + 1):
            side_px = TILE_PX * 2 ** (BASE_ZOOM - z)
            for x in range(w.col0 // side_px, (w.col0 + w.cols - 1) // side_px + 1):
                for y in range(w.row0 // side_px, (w.row0 + w.rows - 1) // side_px + 1):
                    tiles.add((z, x, y))
    return tiles


def view_reach_km(maplibre_zoom: float, half_width_dp: float, lat_deg: float) -> float:
    """Ground distance (km) from the screen centre to its edge.

    ``half_width_dp`` is half the screen width in Android density-independent pixels.
    MapLibre zooms use 512-pixel tiles.
    """
    return _view_reach_m(maplibre_zoom, half_width_dp) * math.cos(math.radians(lat_deg)) / _M_PER_KM


def _view_reach_m(maplibre_zoom: float, half_width_dp: float) -> float:
    """Half the view width in projected Web Mercator metres."""
    metres_per_dp = _EQUATOR_M / (_MAPLIBRE_TILE_PX * 2**maplibre_zoom)
    return half_width_dp * metres_per_dp


def view_bounds(
    camera: Bounds, maplibre_zoom: float, half_width_dp: float = WIDEST_HALF_SCREEN_DP
) -> Bounds:
    """Expand the camera box by half the view width and return degrees within the tile grid."""
    west, south = _TMS.xy(camera.west, camera.south)
    east, north = _TMS.xy(camera.east, camera.north)
    reach_m = _view_reach_m(maplibre_zoom, half_width_dp)
    # Clamp before inverse projection so longitude cannot wrap at the antimeridian.
    grid = _TMS.xy_bbox
    return Bounds(
        *_TMS.lnglat(max(west - reach_m, grid.left), max(south - reach_m, grid.bottom)),
        *_TMS.lnglat(min(east + reach_m, grid.right), min(north + reach_m, grid.top)),
    )


def shrink_extent(extent: Bounds, pixels: int = 2) -> Bounds:
    """Inset the lon/lat extent on each side by zoom-12 pixels, or raise if it collapses."""
    west, south = _TMS.xy(extent.west, extent.south)
    east, north = _TMS.xy(extent.east, extent.north)
    inset_m = pixels * pixel_m()
    west, south, east, north = west + inset_m, south + inset_m, east - inset_m, north - inset_m
    if west >= east or south >= north:
        raise ValueError(f"Extent {extent} collapses when inset by {pixels} pixels")
    return Bounds(*_TMS.lnglat(west, south), *_TMS.lnglat(east, north))


def _widen_camera(camera: Bounds, margin_deg: float) -> Bounds:
    return Bounds(
        camera.west - margin_deg,
        camera.south - margin_deg,
        camera.east + margin_deg,
        camera.north + margin_deg,
    )


def _contains(outer: Bounds, inner: Bounds) -> bool:
    return (
        outer.west <= inner.west
        and outer.south <= inner.south
        and outer.east >= inner.east
        and outer.north >= inner.north
    )


def natural_earth_max_zoom(
    camera: Bounds, margin_deg: float, max_zoom: int, half_width_dp: float = WIDEST_HALF_SCREEN_DP
) -> int:
    """Return the highest build zoom whose view exceeds the data margin in degrees.

    Use build zoom 0 if the view always fits, or max_zoom if the margin is zero.
    Build tiles use 256-pixel tiles and display one zoom lower in MapLibre.
    """
    if margin_deg == 0:
        return max_zoom
    extent = _widen_camera(camera, margin_deg)
    for build_zoom in range(max_zoom, 0, -1):
        if not _contains(extent, view_bounds(camera, build_zoom - 1, half_width_dp)):
            return build_zoom
    return 0


def check_data_extent_coverage(data_extent: Bounds, camera: Bounds, margin_deg: float) -> None:
    """Raise if the data extent fails to enclose the camera plus the margin in degrees."""
    required_extent = _widen_camera(camera, margin_deg)
    if not _contains(data_extent, required_extent):
        raise ValueError(
            f"Data extent {data_extent} does not cover required extent {required_extent}"
        )


def check_base_coverage(
    camera: Bounds,
    margin_deg: float,
    ne_max_zoom: int,
    max_zoom: int,
    min_maplibre_zoom: int = TRIAL_MIN_MAPLIBRE_ZOOM,
    half_width_dp: float = WIDEST_HALF_SCREEN_DP,
) -> None:
    """Raise if a trial view needs Natural Earth above its maximum build zoom."""
    extent = _widen_camera(camera, margin_deg)
    for maplibre_zoom in range(min_maplibre_zoom, max_zoom):
        build_zoom = maplibre_zoom + 1
        if build_zoom > ne_max_zoom and not _contains(
            extent, view_bounds(camera, maplibre_zoom, half_width_dp)
        ):
            raise ValueError(f"Base coverage is incomplete at build zoom {build_zoom}")
