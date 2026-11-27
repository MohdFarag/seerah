"""Build the land and coast vector archive from mask blocks and Natural Earth.

The low zoom mask bands share a grid bounded by the data extent. At higher
zooms, polygons are cut on the block grid and contours get a pixel of context
before clipping. Natural Earth fills the area beyond the mask with a two-pixel
overlap. Geographic coordinates are degrees, projected coordinates metres.
"""

import fcntl
import json
import math
import os
import shutil
import subprocess
from collections.abc import Callable, Iterator
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import TextIO

import numpy as np
from osgeo import gdal, ogr, osr

from .config import (
    COPERNICUS_ATTRIBUTION,
    NATURAL_EARTH_ATTRIBUTION,
    PRODUCTION_MAP_NAME,
    TILE_SCHEMA,
    Bounds,
    BuildTarget,
    Coverage,
    Lock,
    Profile,
)
from .fetch import NE_FILES
from .grid import (
    BASE_ZOOM,
    TRIAL_MIN_MAPLIBRE_ZOOM,
    WIDEST_HALF_SCREEN_DP,
    blocks,
    check_base_coverage,
    check_data_extent_coverage,
    natural_earth_max_zoom,
    pixel_m,
    shrink_extent,
    view_bounds,
)
from .pack import Provenance
from .workarea import WorkArea, atomic_write

BASE_ARCHIVE_NAME = "base.pmtiles"
LAND_LAYER = "land"
COAST_LAYER = "coast"
MIN_WARP_PX = 64

_THRESHOLD = 128
_NODATA = -1
_COARSE_BANDS = ((0, 2), (3, 5))
_FINE_MIN_ZOOM = 6
_CELL_DEG = 10


class BaseError(Exception):
    """A base layer input, build tool or archive check failed."""


def _run(*args: str | Path) -> str:
    try:
        result = subprocess.run(list(args), capture_output=True, text=True, check=False)
    except OSError as exc:
        raise BaseError(f"Cannot run {args[0]}: {exc}") from exc
    if result.returncode:
        raise BaseError(f"{' '.join(map(str, args))} failed: {result.stderr.strip()}")
    return result.stdout


def _srs(epsg: int) -> osr.SpatialReference:
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(epsg)
    srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return srs


def _box(west: float, south: float, east: float, north: float) -> ogr.Geometry:
    ring = ogr.Geometry(ogr.wkbLinearRing)
    for x, y in ((west, south), (east, south), (east, north), (west, north), (west, south)):
        ring.AddPoint_2D(x, y)
    polygon = ogr.Geometry(ogr.wkbPolygon)
    polygon.AddGeometry(ring)
    return polygon


def _warp_mask(
    vrt: Path, bounds: tuple[float, float, float, float], cols: int, rows: int, resample: str
) -> gdal.Dataset:
    return gdal.Warp(
        "",
        str(vrt),
        format="MEM",
        dstSRS="EPSG:3857",
        outputBounds=bounds,
        width=cols,
        height=rows,
        outputType=gdal.GDT_Int16,
        dstNodata=_NODATA,
        resampleAlg=resample,
    )


def _coarse_mask(vrt: Path, extent: Bounds, top_zoom: int) -> gdal.Dataset:
    project = osr.CoordinateTransformation(_srs(4326), _srs(3857))
    west, south, _ = project.TransformPoint(extent.west, extent.south)
    east, north, _ = project.TransformPoint(extent.east, extent.north)
    band_pixel_m = pixel_m() * 2 ** (BASE_ZOOM - top_zoom)

    def dimension(span: float) -> int:
        return min(max(1, round(span / pixel_m())), max(MIN_WARP_PX, round(span / band_pixel_m)))

    return _warp_mask(
        vrt, (west, south, east, north), dimension(east - west), dimension(north - south), "average"
    )


def _multi_geometry(geom: ogr.Geometry, layer: str) -> ogr.Geometry | None:
    """Keep only nonempty parts of the layer's geometry type, even in mixed intersections."""
    want_polygon = layer == LAND_LAYER
    geom_type = ogr.GT_Flatten(geom.GetGeometryType())
    if geom.IsEmpty():
        return None
    if geom_type == ogr.wkbGeometryCollection:
        multi = ogr.Geometry(ogr.wkbMultiPolygon if want_polygon else ogr.wkbMultiLineString)
        for i in range(geom.GetGeometryCount()):
            part = _multi_geometry(geom.GetGeometryRef(i), layer)
            if part is not None:
                for j in range(part.GetGeometryCount()):
                    multi.AddGeometry(part.GetGeometryRef(j))
        return None if multi.IsEmpty() else multi
    if want_polygon and geom_type in (ogr.wkbPolygon, ogr.wkbMultiPolygon) and geom.Area() > 0:
        return ogr.ForceToMultiPolygon(geom.Clone())
    if (
        not want_polygon
        and geom_type in (ogr.wkbLineString, ogr.wkbMultiLineString)
        and geom.Length() > 0
    ):
        return ogr.ForceToMultiLineString(geom.Clone())
    return None


def _feature(stream: TextIO, geom: ogr.Geometry) -> None:
    stream.write('{"type":"Feature","properties":{},"geometry":' + geom.ExportToJson() + "}\n")


def _mask_vectors(
    ds: gdal.Dataset,
    values: np.ndarray,
    layer: str,
    stream: TextIO,
    scratch: Path,
    *,
    collar: bool = False,
    clip: ogr.Geometry | None = None,
) -> None:
    """Polygonise binary land, or contour valid Int16 mask pixels, into a geographic stream."""
    transform = ds.GetGeoTransform()
    if layer == LAND_LAYER:
        values = (values >= _THRESHOLD).astype(np.uint8)
        if not values.any():
            return
        datatype, nodata = gdal.GDT_Byte, 0
    else:
        if not np.any(values >= _THRESHOLD) or not np.any((values >= 0) & (values < _THRESHOLD)):
            return
        datatype, nodata = gdal.GDT_Int16, _NODATA
        if collar:
            values = np.pad(values, 1, constant_values=nodata)
            x, dx, _, y, _, dy = transform
            transform = (x - dx, dx, 0, y - dy, 0, dy)

    # Release per-block scratch files before moving on to the next block.
    with TemporaryDirectory(dir=scratch) as directory:
        raster, vector = Path(directory) / "mask.tif", Path(directory) / "features.gpkg"
        rows, cols = values.shape
        with gdal.GetDriverByName("GTiff").Create(str(raster), cols, rows, 1, datatype) as dst:
            dst.SetGeoTransform(transform)
            dst.SetProjection(ds.GetProjection())
            band = dst.GetRasterBand(1)
            band.SetNoDataValue(nodata)
            band.WriteArray(values)
        if layer == LAND_LAYER:
            _run("gdal", "raster", "polygonize", raster, vector)
        else:
            _run("gdal_contour", "-fl", str(_THRESHOLD), raster, vector)
        _append_mask_features(vector, layer, stream, clip)


def _append_mask_features(
    vector: Path, layer: str, stream: TextIO, clip: ogr.Geometry | None
) -> None:
    project = osr.CoordinateTransformation(_srs(3857), _srs(4326))
    with ogr.Open(str(vector)) as source:
        for feature in source.GetLayer():
            geom = feature.GetGeometryRef()
            if clip is not None:
                geom = geom.Intersection(clip)
            multi = _multi_geometry(geom, layer)
            if multi is not None:
                multi.Transform(project)
                _feature(stream, multi)


def _tile(features: Path, layer: str, first: int, last: int, archives: list[Path]) -> None:
    if not features.stat().st_size:
        return
    archive = features.with_suffix(".pmtiles")
    _run(
        "tippecanoe",
        "-q",
        "--no-progress-indicator",
        "--force",
        "-t",
        features.parent / "tmp",
        f"-Z{first}",
        f"-z{last}",
        "-l",
        layer,
        "--no-tile-size-limit",
        "--no-feature-limit",
        "--no-tiny-polygon-reduction",
        "--exclude-all",
        "-o",
        archive,
        features,
    )
    archives.append(archive)


def _wbm_slices(profile: Profile, work: WorkArea, archives: list[Path]) -> None:
    vrt, scratch = work.path / "mask.vrt", work.base_dir / "tmp"
    for first, last in _COARSE_BANDS:
        with _coarse_mask(vrt, profile.data_extent, last) as ds:
            values = ds.ReadAsArray()
            for layer in (LAND_LAYER, COAST_LAYER):
                features = work.base_dir / f"wbm_{layer}_{first}.geojsonl"
                with features.open("w") as stream:
                    _mask_vectors(ds, values, layer, stream, scratch, collar=True)
                _tile(features, layer, first, last, archives)
    land = work.base_dir / f"wbm_land_{_FINE_MIN_ZOOM}.geojsonl"
    coast = work.base_dir / f"wbm_coast_{_FINE_MIN_ZOOM}.geojsonl"
    with land.open("w") as polygons, coast.open("w") as lines:
        for block in blocks(profile.data_extent, profile.block_zoom):
            with gdal.Open(str(work.mask_dir / f"{block.id}.tif")) as ds:
                band = ds.GetRasterBand(1)
                values = band.ReadAsArray()
                values[band.GetMaskBand().ReadAsArray() == 0] = 0
                _mask_vectors(ds, values, LAND_LAYER, polygons, scratch)
            padded = block.window.pad(1)
            with _warp_mask(vrt, padded.bounds_3857(), padded.cols, padded.rows, "near") as ds:
                _mask_vectors(
                    ds,
                    ds.ReadAsArray(),
                    COAST_LAYER,
                    lines,
                    scratch,
                    clip=_box(*block.window.bounds_3857()),
                )
    _tile(land, LAND_LAYER, _FINE_MIN_ZOOM, profile.max_zoom, archives)
    _tile(coast, COAST_LAYER, _FINE_MIN_ZOOM, profile.max_zoom, archives)


def _split_land(geom: ogr.Geometry) -> Iterator[ogr.Geometry]:
    """Split geographic land on the global ten-degree grid, visiting its envelope's cells."""
    west, east, south, north = geom.GetEnvelope()
    for i in range(math.floor(max(-180, west) / _CELL_DEG), math.ceil(min(180, east) / _CELL_DEG)):
        for j in range(
            math.floor(max(-90, south) / _CELL_DEG), math.ceil(min(90, north) / _CELL_DEG)
        ):
            cell = _box(i * _CELL_DEG, j * _CELL_DEG, (i + 1) * _CELL_DEG, (j + 1) * _CELL_DEG)
            piece = _multi_geometry(geom.Intersection(cell).MakeValid(), LAND_LAYER)
            if piece is not None:
                yield piece


def _natural_earth_sources(lock: Lock, cache: Path) -> dict[str, Path]:
    root = cache.resolve()
    sources = {}
    for ref in lock.natural_earth:
        relative = Path(ref.path)
        name = relative.name
        if name not in NE_FILES:
            continue
        if name in sources:
            raise BaseError(f"Duplicate Natural Earth file in lock: {name}")
        if relative.is_absolute():
            raise BaseError(f"Natural Earth lock path must be relative to the cache: {relative}")
        resolved = (root / relative).resolve()
        if not resolved.is_relative_to(root):
            raise BaseError(f"Natural Earth lock path escapes the cache: {relative}")
        sources[name] = resolved
    for name in NE_FILES:
        if name not in sources:
            raise BaseError(f"Natural Earth file missing from lock: {name}")
        if not sources[name].is_file():
            raise BaseError(f"Natural Earth file missing from cache: {sources[name]}")
    return sources


def _natural_earth_slices(
    profile: Profile | BuildTarget,
    sources: dict[str, Path],
    ne_max_zoom: int,
    directory: Path,
    archives: list[Path],
) -> None:
    scratch = directory / "tmp"
    hole = scratch / "hole.geojson"
    inner = shrink_extent(profile.data_extent, pixels=2)
    hole.write_text(_box(inner.west, inner.south, inner.east, inner.north).ExportToJson())
    half_width_dp = getattr(profile, "widest_half_screen_dp", WIDEST_HALF_SCREEN_DP)
    bands = [
        ("110m", 0, 2, None),
        ("50m", 3, 5, None),
        ("10m", 6, min(9, ne_max_zoom), 5),
        ("10m", 10, ne_max_zoom, 9),
    ]
    for scale, first, last, view_zoom in bands:
        if first > last:
            continue
        for layer, source_layer in ((LAND_LAYER, "land"), (COAST_LAYER, "coastline")):
            source = sources[f"ne_{scale}_{source_layer}.geojson"]
            clip = (
                view_bounds(
                    profile.camera_bounds,
                    maplibre_zoom=view_zoom,
                    half_width_dp=half_width_dp,
                )
                if view_zoom is not None
                else None
            )
            erased = _erase_extent(source, hole, clip, scratch)
            features = directory / f"ne_{scale}_{layer}_{first}.geojsonl"
            _write_ne_features(
                erased, features, layer, split=(layer == LAND_LAYER and scale != "110m")
            )
            _tile(features, layer, first, last, archives)


def _make_valid_geojson(source: Path, target: Path) -> Path:
    """Repair clipped Natural Earth geometries with ``MakeValid()`` before layer-algebra erase."""
    with ogr.Open(str(source)) as src_ds:
        if src_ds is None:
            raise BaseError(f"Cannot open clipped Natural Earth vector {source}")
        src_layer = src_ds.GetLayer()
        driver = ogr.GetDriverByName("GeoJSON")
        if target.exists():
            target.unlink()
        with driver.CreateDataSource(str(target)) as dst_ds:
            dst_layer = dst_ds.CreateLayer(
                src_layer.GetName(), srs=src_layer.GetSpatialRef() or _srs(4326)
            )
            defn = dst_layer.GetLayerDefn()
            for feature in src_layer:
                geom = feature.GetGeometryRef()
                if geom is None:
                    continue
                valid_geom = geom.MakeValid()
                if valid_geom is None or valid_geom.IsEmpty():
                    continue
                out_feat = ogr.Feature(defn)
                out_feat.SetGeometry(valid_geom)
                dst_layer.CreateFeature(out_feat)
    return target


def _erase_extent(source: Path, hole: Path, clip: Bounds | None, scratch: Path) -> Path:
    if clip is not None:
        clipped = scratch / "clipped.geojson"
        _run(
            "gdal",
            "vector",
            "clip",
            "--overwrite",
            f"--bbox={clip.west},{clip.south},{clip.east},{clip.north}",
            source,
            clipped,
        )
        source = _make_valid_geojson(clipped, scratch / "clipped_valid.geojson")
    erased = scratch / "erased.geojson"
    _run(
        "gdal",
        "vector",
        "layer-algebra",
        "--operation=erase",
        "--no-input-field",
        "--overwrite",
        f"--input={source}",
        f"--method={hole}",
        f"--output={erased}",
    )
    return erased


def _write_ne_features(erased: Path, features: Path, layer: str, *, split: bool) -> None:
    with ogr.Open(str(erased)) as ds, features.open("w") as stream:
        for feature in ds.GetLayer():
            geom = feature.GetGeometryRef()
            if geom is None:
                continue
            multi = _multi_geometry(geom.MakeValid(), layer)
            if multi is None:
                continue
            if split:
                for part in _split_land(multi):
                    _feature(stream, part)
            else:
                _feature(stream, multi)


def _show(path: Path, flag: str) -> dict:
    try:
        obj = json.loads(_run("pmtiles", "show", path, flag))
    except ValueError as exc:
        raise BaseError(f"Invalid {flag} JSON in {path}") from exc
    if not isinstance(obj, dict):
        raise BaseError(f"Invalid {flag} object in {path}")
    return obj


def _vector_layers(meta: dict) -> list[dict]:
    layers = meta.get("vector_layers")
    if not isinstance(layers, list) or not all(isinstance(layer, dict) for layer in layers):
        raise BaseError("Missing or invalid vector_layers metadata")
    if not all(isinstance(layer.get("id"), str) for layer in layers):
        raise BaseError("Invalid vector layer identifier")
    return layers


def verify_base(
    pmtiles_path: Path,
    profile: Profile | Coverage | BuildTarget,
    *,
    trial: bool | None = None,
) -> None:
    """Verify the archive structure, build zoom range, trial/production metadata and layer IDs.

    The archive's geographic bounds include global Natural Earth coverage.
    Raises BaseError if the file cannot be read or any check fails.
    """
    target = profile.as_build_target() if isinstance(profile, Coverage) else profile
    is_trial = getattr(target, "trial", True) if trial is None else trial
    _run("pmtiles", "verify", pmtiles_path)
    header = _show(pmtiles_path, "--header-json")
    meta = _show(pmtiles_path, "--metadata")
    if (header.get("minzoom"), header.get("maxzoom")) != (0, target.max_zoom):
        raise BaseError(f"Expected base archive zooms 0 to {target.max_zoom}")
    if is_trial:
        if meta.get("trial") is not True:
            raise BaseError("Base archive metadata must say trial: true")
    else:
        if "trial" in meta:
            raise BaseError("Production base archive metadata must not contain 'trial'")
        if "variant" in meta or "mode" in meta:
            raise BaseError(
                "Production base archive metadata must not contain trial 'variant' or 'mode' keys"
            )
        if meta.get("tile_schema") != TILE_SCHEMA:
            raise BaseError(f"Production base archive metadata must say tile_schema: {TILE_SCHEMA}")
        if meta.get("name") != PRODUCTION_MAP_NAME:
            raise BaseError(
                f"Production base archive metadata must say name: {PRODUCTION_MAP_NAME}"
            )
    if {layer.get("id") for layer in _vector_layers(meta)} != {LAND_LAYER, COAST_LAYER}:
        raise BaseError("Expected land and coast vector layers")


def build_base_layer(
    profile: Profile | Coverage | BuildTarget,
    lock: Lock,
    cache: Path,
    work: WorkArea,
    out_dir: Path,
    provenance: Provenance,
    log: Callable[[str], None],
) -> Path:
    """Build and atomically publish base.pmtiles, returning the verified output path.

    Uses the profile's geographic extent in degrees, pinned Natural Earth files
    from the cache, and the work area's mask mosaic and blocks. The joined
    archive records build provenance. Reports stage start through log and
    raises BaseError if a source, tool or archive check fails.
    """
    target = profile.as_build_target() if isinstance(profile, Coverage) else profile
    log("base stage started")
    try:
        # The guard stays outside the scratch directory that each build recreates.
        with (work.path / "base.lock").open("a") as guard:
            fcntl.flock(guard, fcntl.LOCK_EX)
            return _build(target, lock, cache, work, out_dir, provenance)
    except (OSError, RuntimeError, ValueError) as exc:
        raise BaseError(f"Cannot build base layer for {target.name}: {exc}") from exc


def _build(
    profile: Profile | BuildTarget,
    lock: Lock,
    cache: Path,
    work: WorkArea,
    out_dir: Path,
    provenance: Provenance,
) -> Path:
    half_width_dp = getattr(profile, "widest_half_screen_dp", WIDEST_HALF_SCREEN_DP)
    min_maplibre_zoom = getattr(profile, "min_maplibre_zoom", TRIAL_MIN_MAPLIBRE_ZOOM)
    check_data_extent_coverage(profile.data_extent, profile.camera_bounds, profile.margin_deg)
    ne_max = natural_earth_max_zoom(
        profile.camera_bounds,
        profile.margin_deg,
        profile.max_zoom,
        half_width_dp=half_width_dp,
        data_extent=profile.data_extent,
    )
    check_base_coverage(
        profile.camera_bounds,
        profile.margin_deg,
        ne_max,
        profile.max_zoom,
        min_maplibre_zoom=min_maplibre_zoom,
        half_width_dp=half_width_dp,
        data_extent=profile.data_extent,
    )
    shutil.rmtree(work.base_dir, ignore_errors=True)
    (work.base_dir / "tmp").mkdir(parents=True)
    sources = _natural_earth_sources(lock, cache)
    archives: list[Path] = []
    _wbm_slices(profile, work, archives)
    _natural_earth_slices(profile, sources, ne_max, work.base_dir, archives)
    staged = _join_archives(archives, profile.name, work.base_dir)
    _write_metadata(staged, profile, provenance)
    verify_base(staged, profile)
    return _publish(staged, out_dir)


def _publish(staged: Path, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    output = out_dir / BASE_ARCHIVE_NAME
    # Separate work areas can publish here. Lock the directory so a killed
    # publisher releases the fixed temporary name for the next copy to reuse.
    fd = os.open(out_dir, os.O_RDONLY | os.O_DIRECTORY)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        with atomic_write(output) as tmp:
            shutil.copyfile(staged, tmp)
    finally:
        os.close(fd)
    return output


def _join_archives(archives: list[Path], name: str, directory: Path) -> Path:
    if not archives:
        raise BaseError("No base layer slices produced tiles")
    staged = directory / BASE_ARCHIVE_NAME
    _run(
        "tile-join",
        "--force",
        "--no-tile-size-limit",
        "--exclude-all",
        "-n",
        name,
        "-o",
        staged,
        *sorted(archives),
    )
    return staged


def _write_metadata(staged: Path, profile: Profile | BuildTarget, provenance: Provenance) -> None:
    layers = _vector_layers(_show(staged, "--metadata"))
    try:
        allowed = [
            {
                "id": layer["id"],
                "fields": {},
                "minzoom": layer["minzoom"],
                "maxzoom": layer["maxzoom"],
            }
            for layer in sorted(layers, key=lambda v: v["id"])
        ]
    except (KeyError, TypeError) as exc:
        raise BaseError("Incomplete vector layer metadata in joined archive") from exc
    meta = {
        "fingerprint": provenance.fingerprint,
        "tool_commit": provenance.tool_commit,
        "data_commit": provenance.data_commit,
        "image": provenance.image,
        "vector_layers": allowed,
    }
    if getattr(profile, "trial", True):
        meta |= {"name": profile.name, "trial": True}
    else:
        meta |= {
            "name": PRODUCTION_MAP_NAME,
            "tile_schema": TILE_SCHEMA,
            "attribution": f"{COPERNICUS_ATTRIBUTION} {NATURAL_EARTH_ATTRIBUTION}",
        }
    metadata = staged.parent / "metadata.json"
    metadata.write_text(json.dumps(meta, indent=2, sort_keys=True))
    _run("pmtiles", "edit", "--metadata", metadata, staged)
