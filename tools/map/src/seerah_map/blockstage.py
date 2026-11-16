"""The block stage: render the styled relief and the land mask, one block at a time.

Each block (an XYZ tile at the profile's block zoom) is read from the cached
Copernicus cells with a border of context, filled, edited, styled, faded at
the data extent's edge, cropped and written as two GeoTIFFs in EPSG:3857 at
zoom-12 resolution: ``style/<id>.tif`` and ``mask/<id>.tif``. Finished blocks
are kept, so an interrupted run resumes. Elevations are metres, extents and
distances degrees, pixel windows zoom-12 pixels.
"""

import multiprocessing
from collections.abc import Callable
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np
from osgeo import gdal, osr

from . import encode, mask, style
from .config import Bounds, Edit, Lock, Profile
from .edits import apply, touches
from .fetch import check_lock_matches
from .grid import Block, Window, blocks, border_px, pixel_m, row_latitudes
from .workarea import WorkArea, atomic_write

PAINTED = "painted"
VALUE = "value"
VARIANTS = (PAINTED, VALUE)

_EPSG_WEB_MERCATOR = 3857
_EARTH_RADIUS_M = 6378137.0
_LAND_THRESHOLD = 128
_DEM_ABSENT_M = 0
_WBM_ABSENT_CLASS = 1
_MASK_VALID = 255
_GTIFF_OPTIONS = ["TILED=YES", "COMPRESS=DEFLATE"]
_START_METHOD = "forkserver"


def source_vrts(lock: Lock, cache: Path, extent: Bounds, work: WorkArea) -> tuple[Path, Path]:
    """Mosaic the cached cells into one elevation VRT and one Water Body Mask VRT.

    Args:
        lock: the pinned download set; absent cells have no files.
        cache: the cache directory the lock's paths are relative to.
        extent: the data extent (degrees); both VRTs cover exactly this box.
        work: the work area the VRTs are written into.

    Returns:
        Paths of ``dem.vrt`` and ``wbm.vrt``. Where no cell is present, the
        elevation reads 0 m and the Water Body Mask reads 1 (ocean).

    Raises:
        ValueError: if the lock has no present cell.
    """
    present = [c for c in lock.cells if not c.absent]
    if not present:
        raise ValueError("the lock has no present cell, so there is nothing to render")
    bounds = (extent.west, extent.south, extent.east, extent.north)
    paths = []
    for name, refs, absent_value in (
        ("dem", [c.dem for c in present], _DEM_ABSENT_M),
        ("wbm", [c.wbm for c in present], _WBM_ABSENT_CLASS),
    ):
        path = work.path / f"{name}.vrt"
        sources = [str(cache / ref.path) for ref in refs]
        gdal.BuildVRT(
            str(path), sources, outputBounds=bounds, VRTNodata=absent_value, hideNodata=True
        ).Close()
        paths.append(path)
    return paths[0], paths[1]


def _web_mercator_wkt() -> str:
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(_EPSG_WEB_MERCATOR)
    return srs.ExportToWkt()


def _warp(vrt: Path, window: Window, resample: str) -> np.ndarray:
    """Read ``vrt`` onto ``window``'s zoom-12 Web Mercator grid."""
    ds = gdal.Warp(
        "",
        str(vrt),
        format="MEM",
        dstSRS=f"EPSG:{_EPSG_WEB_MERCATOR}",
        outputBounds=window.bounds_3857(),
        width=window.cols,
        height=window.rows,
        resampleAlg=resample,
        errorThreshold=0,
    )
    return ds.GetRasterBand(1).ReadAsArray()


def _column_longitudes(window: Window) -> np.ndarray:
    """Longitude of each pixel-column centre of ``window`` (degrees), west to east."""
    west = window.bounds_3857()[0]
    easting_m = west + (np.arange(window.cols) + 0.5) * pixel_m()
    return np.degrees(easting_m / _EARTH_RADIUS_M)


def _distance_to_edge_deg(window: Window, extent: Bounds) -> np.ndarray:
    """Each pixel's distance to the nearest edge of ``extent`` (degrees); negative outside."""
    lons = _column_longitudes(window)[np.newaxis, :]
    lats = row_latitudes(window)[:, np.newaxis]
    return np.minimum(
        np.minimum(lons - extent.west, extent.east - lons),
        np.minimum(lats - extent.south, extent.north - lats),
    )


def read_block(
    block: Block, border: int, dem_vrt: Path, wbm_vrt: Path, extent: Bounds
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Read a block and its border from the source VRTs.

    Args:
        block: the block to read.
        border: pixels of context to read on every side.
        dem_vrt: the elevation VRT from ``source_vrts``.
        wbm_vrt: the Water Body Mask VRT from ``source_vrts``.
        extent: the data extent (degrees).

    Returns:
        Elevation (metres, bilinear), Water Body Mask classes (nearest
        neighbour) and ``valid`` (False where the pixel centre lies outside
        ``extent``), each covering ``block.window.pad(border)``.
    """
    window = block.window.pad(border)
    elevation = _warp(dem_vrt, window, "bilinear")
    wbm = _warp(wbm_vrt, window, "near")
    valid = _distance_to_edge_deg(window, extent) >= 0
    return elevation, wbm, valid


def _crop(a: np.ndarray, border: int) -> np.ndarray:
    return a[..., border : a.shape[-2] - border, border : a.shape[-1] - border]


def _fill_dataset(ds: gdal.Dataset, bands: np.ndarray, valid: np.ndarray | None) -> None:
    """Write ``bands`` into ``ds``; with ``valid``, add a per-dataset mask that is 0 where False."""
    for i, band in enumerate(bands, start=1):
        ds.GetRasterBand(i).WriteArray(band)
    if valid is not None:
        ds.CreateMaskBand(gdal.GMF_PER_DATASET)
        ds.GetRasterBand(1).GetMaskBand().WriteArray(valid.astype(np.uint8) * _MASK_VALID)


def _write_geotiff(
    path: Path, bands: np.ndarray, window: Window, valid: np.ndarray | None = None
) -> None:
    """Write a uint8 (bands, h, w) array atomically as a tiled, DEFLATE GeoTIFF on ``window``."""
    count, rows, cols = bands.shape
    with atomic_write(path) as tmp, gdal.config_option("GDAL_TIFF_INTERNAL_MASK", "YES"):
        ds = gdal.GetDriverByName("GTiff").Create(
            str(tmp), cols, rows, count, gdal.GDT_Byte, options=_GTIFF_OPTIONS
        )
        try:
            ds.SetGeoTransform(window.geotransform())
            ds.SetProjection(_web_mercator_wkt())
            _fill_dataset(ds, bands, valid)
        finally:
            ds.Close()


def _encode(d: np.ndarray, profile: Profile, variant: str) -> np.ndarray:
    if variant == PAINTED:
        return encode.painted(d, profile.style.ink_rgb, profile.style.max_alpha)
    if variant == VALUE:
        return encode.value(d)
    raise ValueError(f"unknown variant {variant!r}; expected one of {VARIANTS}")


def render_block(
    block: Block,
    profile: Profile,
    edits: list[Edit],
    variant: str,
    vrts: tuple[Path, Path],
    work: WorkArea,
) -> None:
    """Render one block's styled relief and land mask.

    Args:
        block: the block to render.
        profile: the build profile (style, data extent, fade width).
        edits: the approved edits; those that cannot touch the block are skipped.
        variant: ``painted`` (banded, RGBA ink) or ``value`` (RGB Terrarium).
        vrts: the elevation and Water Body Mask VRTs from ``source_vrts``.
        work: the work area.

    Writes ``style/<id>.tif`` (the encoded darkness) and ``mask/<id>.tif`` (the
    land mask, 0 to 255, with an internal mask that is 0 outside the data
    extent), both on the block's window. Each file appears complete or not at all.
    """
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant {variant!r}; expected one of {VARIANTS}")
    extent = profile.data_extent
    border = border_px(profile.style, edits, block)
    window = block.window.pad(border)
    elevation, wbm, valid = read_block(block, border, *vrts, extent)
    if valid.any():
        elevation = mask.fill_invalid(elevation, valid)
        wbm = mask.fill_invalid(wbm, valid)
    land_values = mask.land_mask(wbm, valid)
    elevation = apply(elevation, [e for e in edits if touches(e, window)], window)
    d = style.darkness(
        elevation,
        land_values >= _LAND_THRESHOLD,
        row_latitudes(window),
        pixel_m(),
        profile.style,
        banded=variant == PAINTED,
    )
    if profile.fade_deg > 0:
        d = style.edge_fade(
            d, np.maximum(_distance_to_edge_deg(window, extent), 0), profile.fade_deg
        )
    _write_geotiff(
        work.mask_dir / f"{block.id}.tif",
        _crop(land_values, border)[np.newaxis],
        block.window,
        _crop(valid, border),
    )
    _write_geotiff(
        work.style_dir / f"{block.id}.tif",
        _encode(_crop(d, border), profile, variant),
        block.window,
    )


def _finished(block: Block, work: WorkArea) -> bool:
    return all((d / f"{block.id}.tif").is_file() for d in (work.style_dir, work.mask_dir))


def write_mosaics(block_list: list[Block], work: WorkArea) -> None:
    """Write ``style.vrt`` and ``mask.vrt`` over every block's files.

    Args:
        block_list: every block of the build.
        work: the work area.

    Raises:
        FileNotFoundError: naming the first block whose style or mask file is missing.
    """
    for block in block_list:
        if not _finished(block, work):
            raise FileNotFoundError(f"block {block.id} has no style or mask file")
    for name, directory in (("style", work.style_dir), ("mask", work.mask_dir)):
        sources = [str(directory / f"{b.id}.tif") for b in block_list]
        gdal.BuildVRT(str(work.path / f"{name}.vrt"), sources).Close()


def run_block_stage(
    profile: Profile,
    edits: list[Edit],
    variant: str,
    lock: Lock,
    cache: Path,
    work: WorkArea,
    workers: int,
    log: Callable[[str], None],
) -> list[str]:
    """Render every unfinished block in parallel, then write the mosaics.

    Args:
        profile: the build profile.
        edits: the approved edits.
        variant: ``painted`` or ``value``.
        lock: the pinned download set; it must match the profile.
        cache: the cache directory the lock's paths are relative to.
        work: the work area.
        workers: the number of worker processes.
        log: receives ``block <id> done (<n>/<total>)`` as each block finishes.

    Returns:
        The IDs of the blocks rendered in this run, in completion order.
    """
    check_lock_matches(lock, profile)
    block_list = blocks(profile.data_extent, profile.block_zoom)
    vrts = source_vrts(lock, cache, profile.data_extent, work)
    pending = [b for b in block_list if not _finished(b, work)]
    rendered: list[str] = []
    if pending:
        context = multiprocessing.get_context(_START_METHOD)
        with ProcessPoolExecutor(max_workers=workers, mp_context=context) as pool:
            futures = {
                pool.submit(render_block, b, profile, edits, variant, vrts, work): b
                for b in pending
            }
            for future in as_completed(futures):
                future.result()
                block = futures[future]
                rendered.append(block.id)
                log(f"block {block.id} done ({len(rendered)}/{len(pending)})")
    write_mosaics(block_list, work)
    return rendered
