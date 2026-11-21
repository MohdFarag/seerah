"""Terrain edits: raise, flatten or carve the elevation inside a drawn outline.

An edit's geometry is GeoJSON in longitude/latitude. It is reprojected to Web
Mercator, drawn onto a window's pixel grid, and blended into the elevation with
a weight that falls linearly from 1 at the outline to 0 at ``feather_m`` away.
Lengths are metres on the ground; elevations are metres.
"""

import json
import math

import numpy as np
from osgeo import gdal, ogr, osr
from scipy import ndimage

from .config import Edit
from .grid import Window, edit_reach_m, row_latitudes

_APPROVED = "approved"
_MEMORY_DRIVER_NAMES = ("MEM", "Memory")
_EPSG_WGS84 = 4326
_EPSG_WEB_MERCATOR = 3857


def applicable(edits: list[Edit]) -> tuple[list[Edit], list[str]]:
    """Split edits by review state.

    Args:
        edits: all edits of a profile.

    Returns:
        The edits whose review is ``approved`` (order kept), and the IDs of the
        others, which must not be applied.
    """
    kept = [e for e in edits if e.review == _APPROVED]
    skipped = [e.id for e in edits if e.review != _APPROVED]
    return kept, skipped


def touches(edit: Edit, window: Window) -> bool:
    """Whether an edit can change any pixel of ``window``.

    Tests the bounding box of the reprojected geometry, grown by the edit's
    reach, against the window bounds. Cheap and conservative: it may say yes for
    an edit that changes nothing, never no for one that does.

    Args:
        edit: the edit.
        window: the pixel window.

    Returns:
        True if the grown bounding box intersects the window.
    """
    geometry = _to_mercator(edit)
    min_x, max_x, min_y, max_y = geometry.GetEnvelope()
    reach = edit_reach_m(edit) / _min_cos_latitude(edit)
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


def _to_mercator(edit: Edit) -> ogr.Geometry:
    """The edit's geometry in EPSG:3857, with a carve buffered by half its width."""
    geometry = ogr.CreateGeometryFromJson(json.dumps(edit.geometry))
    geometry.AssignSpatialReference(_srs(_EPSG_WGS84))
    geometry.TransformTo(_srs(_EPSG_WEB_MERCATOR))
    if edit.operation == "carve" and edit.width_m:
        half_width_mercator = edit.width_m / 2 / _mean_cos_latitude(edit)
        geometry = geometry.Buffer(half_width_mercator)
    return geometry


def _latitudes(edit: Edit) -> list[float]:
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


def _mean_cos_latitude(edit: Edit) -> float:
    lats = _latitudes(edit)
    return math.cos(math.radians(sum(lats) / len(lats)))


def _min_cos_latitude(edit: Edit) -> float:
    return min(math.cos(math.radians(lat)) for lat in _latitudes(edit))


def _footprint(edit: Edit, window: Window) -> np.ndarray:
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
