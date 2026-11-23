"""Terrain and coastal cleanup edits on elevation and the Water Body Mask.

Trial terrain edits raise, flatten or carve elevation with linear distance
feathering around their outline. Production coastal cleanup edits apply
discrete masks to Copernicus Water Body Mask (WBM) classes and coincident
elevation pixels without feathering. Lengths are metres on the ground;
elevations are metres.
"""

import json
import math
from collections.abc import Sequence
from typing import NamedTuple, TypeVar

import numpy as np
from osgeo import gdal, ogr, osr
from scipy import ndimage

from .config import Bounds, CleanupEdit, Edit
from .errors import ConfigError
from .grid import Window, edit_reach_m, row_latitudes

CLEANUP_REMOVE_LAND = "remove-land"
CLEANUP_FILL_WATER = "fill-water"
CLEANUP_OPERATIONS = (CLEANUP_REMOVE_LAND, CLEANUP_FILL_WATER)

_APPROVED = "approved"
_MEMORY_DRIVER_NAMES = ("MEM", "Memory")
_EPSG_WGS84 = 4326
_EPSG_WEB_MERCATOR = 3857
_LAND_CLASS = 0
_OCEAN_CLASS = 1

_EditT = TypeVar("_EditT", Edit, CleanupEdit)


class CleanupResult(NamedTuple):
    """The arrays after coastal cleanup, and which edits changed at least one pixel."""

    elevation: np.ndarray
    wbm: np.ndarray
    changed_ids: tuple[str, ...]


def applicable(edits: Sequence[_EditT]) -> tuple[list[_EditT], list[str]]:
    """Split edits by review state.

    Args:
        edits: all terrain or cleanup edits of a build.

    Returns:
        The edits whose review is ``approved`` (order kept), and the IDs of the
        others, which must not be applied.
    """
    kept = [e for e in edits if e.review == _APPROVED]
    skipped = [e.id for e in edits if e.review != _APPROVED]
    return kept, skipped


def touches(edit: Edit | CleanupEdit, window: Window, *, reach_m: float | None = None) -> bool:
    """Whether an edit can change any pixel of ``window``.

    Tests the bounding box of the reprojected geometry, grown by the edit's
    reach, against the window bounds. Cheap and conservative: it may say yes for
    an edit that changes nothing, never no for one that does.

    Args:
        edit: the terrain or cleanup edit.
        window: the pixel window.
        reach_m: ground reach in metres. Defaults to ``0.0`` for
            ``CleanupEdit`` and ``edit_reach_m(edit)`` for ``Edit``.

    Returns:
        True if the grown bounding box intersects the window.

    Raises:
        ValueError: if ``reach_m`` is negative.
    """
    if reach_m is None:
        effective_reach = 0.0 if isinstance(edit, CleanupEdit) else edit_reach_m(edit)
    else:
        if reach_m < 0:
            raise ValueError(f"reach_m {reach_m} must be non-negative")
        effective_reach = reach_m
    geometry = _to_mercator(edit)
    min_x, max_x, min_y, max_y = geometry.GetEnvelope()
    reach = effective_reach / _min_cos_latitude(edit) if effective_reach > 0 else 0.0
    west, south, east, north = window.bounds_3857()
    return (
        min_x - reach <= east
        and max_x + reach >= west
        and min_y - reach <= north
        and max_y + reach >= south
    )


def apply(elevation: np.ndarray, edits: list[Edit], window: Window) -> np.ndarray:
    """Apply edits, in order, to an elevation array.

    Args:
        elevation: 2-D elevation in metres covering ``window``. Not modified.
        edits: the edits to apply (the caller filters by review state).
        window: the pixel window ``elevation`` covers.

    Returns:
        A new float32 array. An edit whose outline covers no pixel of the
        window is skipped.
    """
    result = elevation.astype(np.float32, copy=True)
    ground_per_px = window.pixel_m() * np.cos(np.radians(row_latitudes(window)))
    for edit in edits:
        inside = _footprint(edit, window)
        if not inside.any():
            continue
        weight = _weight(inside, edit.feather_m, ground_per_px)
        if edit.operation == "raise":
            result += (edit.amount_m * weight).astype(np.float32)
        elif edit.operation == "carve":
            result -= (edit.amount_m * weight).astype(np.float32)
        elif edit.operation == "flatten-to":
            result += ((edit.height_m - result) * weight).astype(np.float32)
        else:
            raise ValueError(f"edit {edit.id}: unknown operation {edit.operation!r}")
    return result


def validate_cleanups(cleanups: Sequence[CleanupEdit], data_extent: Bounds | None = None) -> None:
    """Validate OGR geometry, area, non-overlap, and extent intersection for cleanup edits.

    Every edit (approved or unapproved) must parse as valid, positive-area
    geometry in both EPSG:4326 and EPSG:3857. Approved edits must additionally
    intersect ``data_extent`` (when provided) and must not intersect any other
    approved edit of the opposite operation in EPSG:3857.

    Args:
        cleanups: cleanup edits to check.
        data_extent: optional build extent in degrees; checked against every
            approved edit's WGS 84 bounding box.

    Raises:
        ConfigError: if any ID duplicates, operation is unknown, geometry is
            invalid/degenerate, opposite approved edits overlap, or an approved
            edit misses ``data_extent``.
    """
    seen: set[str] = set()
    approved_geoms: list[tuple[CleanupEdit, ogr.Geometry]] = []
    for edit in cleanups:
        if edit.id in seen:
            raise ConfigError(f"cleanup edit {edit.id}: duplicate edit id")
        seen.add(edit.id)
        if edit.operation not in CLEANUP_OPERATIONS:
            raise ConfigError(f"cleanup edit {edit.id}: unknown operation {edit.operation!r}")
        geom_wgs84 = ogr.CreateGeometryFromJson(json.dumps(edit.geometry))
        if geom_wgs84 is None or not geom_wgs84.IsValid() or geom_wgs84.Area() <= 0:
            raise ConfigError(f"cleanup edit {edit.id}: invalid or zero-area EPSG:4326 geometry")
        geom_wgs84.AssignSpatialReference(_srs(_EPSG_WGS84))
        geom_3857 = _to_mercator(edit)
        if not geom_3857.IsValid() or geom_3857.Area() <= 0:
            raise ConfigError(f"cleanup edit {edit.id}: invalid or zero-area EPSG:3857 geometry")
        if edit.review != _APPROVED:
            continue
        if data_extent is not None:
            min_lon, max_lon, min_lat, max_lat = geom_wgs84.GetEnvelope()
            intersects_extent = (
                max_lon >= data_extent.west
                and min_lon <= data_extent.east
                and max_lat >= data_extent.south
                and min_lat <= data_extent.north
            )
            if not intersects_extent:
                raise ConfigError(
                    f"cleanup edit {edit.id}: approved edit does not intersect data_extent"
                )
        for other_edit, other_geom in approved_geoms:
            if other_edit.operation != edit.operation and geom_3857.Intersects(other_geom):
                raise ConfigError(
                    f"cleanup edits {other_edit.id} ({other_edit.operation}) and "
                    f"{edit.id} ({edit.operation}) overlap in EPSG:3857"
                )
        approved_geoms.append((edit, geom_3857))


def _apply_cleanup_edits(
    wbm: np.ndarray,
    valid: np.ndarray,
    cleanups: Sequence[CleanupEdit],
    window: Window,
    *,
    elevation: np.ndarray | None = None,
) -> tuple[np.ndarray | None, np.ndarray, tuple[str, ...]]:
    """Shared coastal cleanup rasterizer for ``apply_cleanup`` and ``apply_wbm_cleanup``.

    Returns the new elevation (or None), the new WBM, and the IDs of the edits
    that changed at least one WBM class or elevation value, in edit order.
    """
    expected = (window.rows, window.cols)
    if wbm.shape != expected or valid.shape != expected:
        raise ValueError(
            f"wbm shape {wbm.shape} and valid shape {valid.shape} must match {expected}"
        )
    if elevation is not None and elevation.shape != expected:
        raise ValueError(f"elevation shape {elevation.shape} must match {expected}")

    wbm_out = wbm.astype(np.uint8, copy=True)
    elev_out = None if elevation is None else elevation.astype(np.float32, copy=True)
    changed: list[str] = []
    for edit in cleanups:
        if edit.operation not in CLEANUP_OPERATIONS:
            raise ValueError(f"cleanup edit {edit.id}: unknown operation {edit.operation!r}")
        if not touches(edit, window, reach_m=0.0):
            continue
        target = valid & _footprint(edit, window)
        if not target.any():
            continue
        if edit.operation == CLEANUP_REMOVE_LAND:
            changes = bool((wbm_out[target] != _OCEAN_CLASS).any())
            wbm_out[target] = _OCEAN_CLASS
            if elev_out is not None:
                changes = changes or bool((elev_out[target] != 0.0).any())
                elev_out[target] = 0.0
        else:
            target &= wbm_out == _OCEAN_CLASS
            changes = bool(target.any())
            wbm_out[target] = _LAND_CLASS
            if elev_out is not None:
                elev_out[target] = 0.0
        if changes:
            changed.append(edit.id)
    return elev_out, wbm_out, tuple(changed)


def apply_cleanup(
    elevation: np.ndarray,
    wbm: np.ndarray,
    valid: np.ndarray,
    cleanups: Sequence[CleanupEdit],
    window: Window,
) -> CleanupResult:
    """Apply coastal cleanup edits to WBM and coincident elevation arrays.

    Args:
        elevation: 2-D elevation in metres covering ``window``. Not modified.
        wbm: 2-D Water Body Mask classes covering ``window``. Not modified.
        valid: boolean mask; False pixels outside the data extent stay untouched.
        cleanups: approved cleanup edits to apply in order.
        window: the pixel window covered by the arrays.

    Returns:
        The new ``float32`` elevation and ``uint8`` WBM copies, and the IDs of
        the edits that changed at least one pixel of either.

    Raises:
        ValueError: on array shape mismatch or unknown cleanup operation.
    """
    elev_out, wbm_out, changed = _apply_cleanup_edits(
        wbm, valid, cleanups, window, elevation=elevation
    )
    assert elev_out is not None
    return CleanupResult(elev_out, wbm_out, changed)


def apply_wbm_cleanup(
    wbm: np.ndarray,
    valid: np.ndarray,
    cleanups: Sequence[CleanupEdit],
    window: Window,
) -> np.ndarray:
    """Apply coastal cleanup edits to a Water Body Mask array without elevation.

    Args:
        wbm: 2-D Water Body Mask classes covering ``window``. Not modified.
        valid: boolean mask; False pixels outside the data extent stay untouched.
        cleanups: approved cleanup edits to apply in order.
        window: the pixel window covered by ``wbm``.

    Returns:
        A new ``uint8`` WBM array.

    Raises:
        ValueError: on array shape mismatch or unknown cleanup operation.
    """
    _, wbm_out, _ = _apply_cleanup_edits(wbm, valid, cleanups, window)
    return wbm_out


def _weight(inside: np.ndarray, feather_m: float, ground_per_px: np.ndarray) -> np.ndarray:
    """Edit weight per pixel: 1 inside, falling linearly to 0 at ``feather_m``."""
    if feather_m <= 0:
        return inside.astype(np.float64)
    distance_px = ndimage.distance_transform_edt(~inside)
    distance_m = distance_px * ground_per_px[:, np.newaxis]
    return np.clip(1.0 - distance_m / feather_m, 0.0, 1.0)


def _srs(epsg: int) -> osr.SpatialReference:
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(epsg)
    srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return srs


def _to_mercator(edit: Edit | CleanupEdit) -> ogr.Geometry:
    """The edit's geometry in EPSG:3857, with a carve buffered by half its width."""
    geometry = ogr.CreateGeometryFromJson(json.dumps(edit.geometry))
    if geometry is None:
        raise ConfigError(f"edit {edit.id}: invalid GeoJSON geometry")
    geometry.AssignSpatialReference(_srs(_EPSG_WGS84))
    if geometry.TransformTo(_srs(_EPSG_WEB_MERCATOR)) != 0:
        raise ConfigError(f"edit {edit.id}: cannot transform geometry to EPSG:3857")
    if isinstance(edit, Edit) and edit.operation == "carve" and edit.width_m:
        half_width_mercator = edit.width_m / 2 / _mean_cos_latitude(edit)
        geometry = geometry.Buffer(half_width_mercator)
    return geometry


def _latitudes(edit: Edit | CleanupEdit) -> list[float]:
    """Latitudes (degrees) of every vertex of the edit's GeoJSON geometry."""
    lats: list[float] = []

    def walk(node) -> None:
        if node and isinstance(node[0], (int, float)):
            lats.append(node[1])
        else:
            for child in node:
                walk(child)

    walk(edit.geometry["coordinates"])
    return lats


def _mean_cos_latitude(edit: Edit | CleanupEdit) -> float:
    lats = _latitudes(edit)
    return math.cos(math.radians(sum(lats) / len(lats)))


def _min_cos_latitude(edit: Edit | CleanupEdit) -> float:
    return min(math.cos(math.radians(lat)) for lat in _latitudes(edit))


def _footprint(edit: Edit | CleanupEdit, window: Window) -> np.ndarray:
    """Boolean raster of the pixels whose centres lie inside the edit's outline."""
    driver = next(d for d in map(ogr.GetDriverByName, _MEMORY_DRIVER_NAMES) if d)
    source = driver.CreateDataSource("footprint")
    layer = source.CreateLayer("edit", _srs(_EPSG_WEB_MERCATOR))
    feature = ogr.Feature(layer.GetLayerDefn())
    feature.SetGeometry(_to_mercator(edit))
    layer.CreateFeature(feature)

    target = gdal.GetDriverByName("MEM").Create("", window.cols, window.rows, 1, gdal.GDT_Byte)
    target.SetGeoTransform(window.geotransform())
    target.SetProjection(_srs(_EPSG_WEB_MERCATOR).ExportToWkt())
    gdal.Rasterize(target, source, burnValues=[1], allTouched=False)
    return target.GetRasterBand(1).ReadAsArray().astype(bool)
