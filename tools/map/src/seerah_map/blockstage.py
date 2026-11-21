"""The block stage: render the styled relief and the land mask, one block at a time.

Each block (an XYZ tile at the profile's block zoom) is read from the cached
Copernicus cells with a border of context, filled, edited, styled, faded at
the data extent's edge, cropped and written as GeoTIFFs in EPSG:3857. In
``pyramid`` mode, zoom-12 blocks are written to ``style/<id>.tif`` and
``mask/<id>.tif``. In ``per-zoom`` mode, zoom 12 (when ``max_zoom == 12``)
matches ``pyramid`` into ``style/z12/<id>.tif`` and ``mask/<id>.tif``, while
zooms below 12 render into ``style/z<z>/<id>.tif`` using averaged elevation and
land mask at that zoom's pixel scale (building zoom-12 ``mask/<id>.tif`` and
``mask.vrt`` first if ``max_zoom < 12``). Finished blocks are kept, so an
interrupted run resumes. Elevations are metres, extents and distances degrees.
"""

import multiprocessing
from collections.abc import Callable
from concurrent.futures import Future, ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np
from osgeo import gdal, osr

from . import encode, mask, style
from .config import Bounds, Edit, Lock, Profile
from .edits import apply, touches
from .fetch import check_lock_matches
from .grid import BASE_ZOOM, Block, Window, blocks, border_px, row_latitudes, scale_style
from .workarea import WorkArea, atomic_write

PAINTED = "painted"
VALUE = "value"
VARIANTS = (PAINTED, VALUE)
PYRAMID = "pyramid"
PER_ZOOM = "per-zoom"
MODES = (PYRAMID, PER_ZOOM)

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


def _warp(
    vrt: Path,
    window: Window,
    resample: str,
    *,
    output_type: int | None = None,
    dst_nodata: float | None = None,
    error_threshold: float | None = 0,
) -> np.ndarray:
    """Read ``vrt`` onto ``window``'s Web Mercator grid."""
    options: dict[str, object] = {
        "format": "MEM",
        "dstSRS": f"EPSG:{_EPSG_WEB_MERCATOR}",
        "outputBounds": window.bounds_3857(),
        "width": window.cols,
        "height": window.rows,
        "resampleAlg": resample,
    }
    if error_threshold is not None:
        options["errorThreshold"] = error_threshold
    if output_type is not None:
        options["outputType"] = output_type
    if dst_nodata is not None:
        options["dstNodata"] = dst_nodata
    ds = gdal.Warp("", str(vrt), **options)
    try:
        return ds.GetRasterBand(1).ReadAsArray()
    finally:
        ds.Close()


def _column_longitudes(window: Window) -> np.ndarray:
    """Longitude of each pixel-column centre of ``window`` (degrees), west to east."""
    west = window.bounds_3857()[0]
    easting_m = west + (np.arange(window.cols) + 0.5) * window.pixel_m()
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


def _crop_pads(a: np.ndarray, pads: tuple[int, int, int, int]) -> np.ndarray:
    left, top, right, bottom = pads
    return a[..., top : a.shape[-2] - bottom, left : a.shape[-1] - right]


def _crop(a: np.ndarray, border: int) -> np.ndarray:
    return _crop_pads(a, (border, border, border, border))


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


def _land_mask_from_wbm(wbm: np.ndarray, valid: np.ndarray) -> np.ndarray:
    if valid.any():
        wbm = mask.fill_invalid(wbm, valid)
    return mask.land_mask(wbm, valid)


def _write_mask_block(
    path: Path, land_values: np.ndarray, valid: np.ndarray, out_window: Window, border: int
) -> None:
    _write_geotiff(
        path,
        _crop(land_values, border)[np.newaxis],
        out_window,
        _crop(valid, border),
    )


def _shade_and_write_style(
    elevation: np.ndarray,
    land_values: np.ndarray,
    window: Window,
    pads: tuple[int, int, int, int],
    out_window: Window,
    out_path: Path,
    profile: Profile,
    edits: list[Edit],
    variant: str,
) -> None:
    extent = profile.data_extent
    elevation = apply(elevation, [e for e in edits if touches(e, window)], window)
    d = style.darkness(
        elevation,
        land_values >= _LAND_THRESHOLD,
        row_latitudes(window),
        window.pixel_m(),
        scale_style(profile.style, window.zoom),
        banded=variant == PAINTED,
    )
    if profile.fade_deg > 0:
        d = style.edge_fade(
            d, np.maximum(_distance_to_edge_deg(window, extent), 0), profile.fade_deg
        )
    _write_geotiff(out_path, _encode(_crop_pads(d, pads), profile, variant), out_window)


def render_block(
    block: Block,
    profile: Profile,
    edits: list[Edit],
    variant: str,
    vrts: tuple[Path, Path],
    work: WorkArea,
    style_dir: Path | None = None,
) -> None:
    """Render one zoom-12 block's styled relief and land mask.

    Args:
        block: the zoom-12 block to render.
        profile: the build profile (style, data extent, fade width).
        edits: the approved edits; those that cannot touch the block are skipped.
        variant: ``painted`` (banded, RGBA ink) or ``value`` (RGB Terrarium).
        vrts: the elevation and Water Body Mask VRTs from ``source_vrts``.
        work: the work area.
        style_dir: directory for the styled GeoTIFF; defaults to ``work.style_dir``.

    Raises:
        ValueError: if ``variant`` is unknown or ``block.window.zoom != BASE_ZOOM``.

    Writes ``<style_dir>/<id>.tif`` (the encoded darkness) and ``mask/<id>.tif``
    (the land mask, 0 to 255, with an internal mask that is 0 outside the data
    extent), both on the block's window. Each file appears complete or not at all.
    """
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant {variant!r}; expected one of {VARIANTS}")
    if block.window.zoom != BASE_ZOOM:
        raise ValueError(f"render_block expects zoom {BASE_ZOOM}, got {block.window.zoom}")
    extent = profile.data_extent
    border = border_px(profile.style, edits, block)
    window = block.window.pad(border)
    elevation, wbm, valid = read_block(block, border, *vrts, extent)
    if valid.any():
        elevation = mask.fill_invalid(elevation, valid)
    land_values = _land_mask_from_wbm(wbm, valid)
    _write_mask_block(work.mask_dir / f"{block.id}.tif", land_values, valid, block.window, border)
    target_dir = work.style_dir if style_dir is None else style_dir
    _shade_and_write_style(
        elevation,
        land_values,
        window,
        (border, border, border, border),
        block.window,
        target_dir / f"{block.id}.tif",
        profile,
        edits,
        variant,
    )


def _render_mask_block(block: Block, profile: Profile, wbm_vrt: Path, work: WorkArea) -> None:
    extent = profile.data_extent
    border = border_px(profile.style, [], block)
    window = block.window.pad(border)
    wbm = _warp(wbm_vrt, window, "near")
    valid = _distance_to_edge_deg(window, extent) >= 0
    land_values = _land_mask_from_wbm(wbm, valid)
    _write_mask_block(work.mask_dir / f"{block.id}.tif", land_values, valid, block.window, border)


def _render_coarse_block(
    block: Block,
    profile: Profile,
    edits: list[Edit],
    variant: str,
    dem_vrt: Path,
    mask_vrt: Path,
    out_path: Path,
) -> None:
    extent = profile.data_extent
    border = border_px(profile.style, edits, block)
    # Clamp padding to the zoom's grid edges so z=0/1 blocks stay inside Web Mercator bounds.
    window, pads = block.window.pad_clamped(border)
    elevation = _warp(dem_vrt, window, "average")
    valid = _distance_to_edge_deg(window, extent) >= 0
    if valid.any():
        elevation = mask.fill_invalid(elevation, valid)
    # Warp the blurred zoom-12 mask (not raw WBM codes), filling outside mask.vrt with sea (0).
    land_values = _warp(
        mask_vrt,
        window,
        "average",
        output_type=gdal.GDT_Byte,
        dst_nodata=0,
        error_threshold=None,
    )
    land_values[~valid] = 0
    _shade_and_write_style(
        elevation,
        land_values,
        window,
        pads,
        block.window,
        out_path,
        profile,
        edits,
        variant,
    )


def _finished(block: Block, work: WorkArea) -> bool:
    return all((d / f"{block.id}.tif").is_file() for d in (work.style_dir, work.mask_dir))


def _write_mosaic(vrt_path: Path, block_list: list[Block], directory: Path) -> None:
    sources = []
    for block in block_list:
        path = directory / f"{block.id}.tif"
        if not path.is_file():
            raise FileNotFoundError(f"block {block.id} has no file in {directory}")
        sources.append(str(path))
    gdal.BuildVRT(str(vrt_path), sources).Close()


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
        _write_mosaic(work.path / f"{name}.vrt", block_list, directory)


def _run_pool(
    pending: list[Block],
    submit: Callable[[ProcessPoolExecutor, Block], Future[None]],
    label: Callable[[Block], str],
    workers: int,
    log: Callable[[str], None],
) -> list[str]:
    if not pending:
        return []
    rendered: list[str] = []
    context = multiprocessing.get_context(_START_METHOD)
    with ProcessPoolExecutor(max_workers=workers, mp_context=context) as pool:
        futures = {submit(pool, b): b for b in pending}
        for future in as_completed(futures):
            future.result()
            name = label(futures[future])
            rendered.append(name)
            log(f"block {name} done ({len(rendered)}/{len(pending)})")
    return rendered


def _prepare_per_zoom_z12(
    profile: Profile,
    edits: list[Edit],
    variant: str,
    vrts: tuple[Path, Path],
    work: WorkArea,
    workers: int,
    log: Callable[[str], None],
) -> tuple[list[str], Path]:
    """Prepare zoom-12 land masks (and zoom-12 style when built), returning ``mask.vrt``."""
    # Zoom-12 mask/<id>.tif + mask.vrt are always required: coarse zooms average
    # from mask.vrt, and base.py reads the zoom-12 land masks and mosaic.
    mask_blocks = blocks(profile.data_extent, profile.block_zoom)
    mask_vrt = work.path / "mask.vrt"
    if profile.max_zoom == BASE_ZOOM:
        z12_dir = work.style_dir / f"z{BASE_ZOOM}"
        z12_dir.mkdir(parents=True, exist_ok=True)
        pending = [
            b
            for b in mask_blocks
            if not (
                (work.mask_dir / f"{b.id}.tif").is_file() and (z12_dir / f"{b.id}.tif").is_file()
            )
        ]
        rendered = _run_pool(
            pending,
            lambda pool, b: pool.submit(
                render_block, b, profile, edits, variant, vrts, work, z12_dir
            ),
            lambda b: f"z{BASE_ZOOM}/{b.id}",
            workers,
            log,
        )
        _write_mosaic(mask_vrt, mask_blocks, work.mask_dir)
        _write_mosaic(work.path / f"style_z{BASE_ZOOM}.vrt", mask_blocks, z12_dir)
        return rendered, mask_vrt

    pending_mask = [b for b in mask_blocks if not (work.mask_dir / f"{b.id}.tif").is_file()]
    rendered = _run_pool(
        pending_mask,
        lambda pool, b: pool.submit(_render_mask_block, b, profile, vrts[1], work),
        lambda b: f"mask/{b.id}",
        workers,
        log,
    )
    _write_mosaic(mask_vrt, mask_blocks, work.mask_dir)
    return rendered, mask_vrt


def _run_per_zoom(
    profile: Profile,
    edits: list[Edit],
    variant: str,
    vrts: tuple[Path, Path],
    work: WorkArea,
    workers: int,
    log: Callable[[str], None],
) -> list[str]:
    rendered, mask_vrt = _prepare_per_zoom_z12(profile, edits, variant, vrts, work, workers, log)
    for z in range(min(profile.max_zoom, BASE_ZOOM - 1), -1, -1):
        z_dir = work.style_dir / f"z{z}"
        z_dir.mkdir(parents=True, exist_ok=True)
        # Clamp block zoom to z because a block cannot be smaller than one pixel-grid tile at z.
        z_blocks = blocks(profile.data_extent, min(profile.block_zoom, z), zoom=z)
        pending = [b for b in z_blocks if not (z_dir / f"{b.id}.tif").is_file()]
        rendered.extend(
            _run_pool(
                pending,
                lambda pool, b, d=z_dir: pool.submit(
                    _render_coarse_block,
                    b,
                    profile,
                    edits,
                    variant,
                    vrts[0],
                    mask_vrt,
                    d / f"{b.id}.tif",
                ),
                lambda b, zoom=z: f"z{zoom}/{b.id}",
                workers,
                log,
            )
        )
        _write_mosaic(work.path / f"style_z{z}.vrt", z_blocks, z_dir)
    return rendered


def run_block_stage(
    profile: Profile,
    edits: list[Edit],
    variant: str,
    lock: Lock,
    cache: Path,
    work: WorkArea,
    workers: int,
    log: Callable[[str], None],
    mode: str = PYRAMID,
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
        log: receives progress lines as each block finishes.
        mode: ``pyramid`` (default) or ``per-zoom``.

    Returns:
        The identifiers of the blocks rendered in this run, in completion order.

    Raises:
        ValueError: if ``variant`` or ``mode`` is unknown, or if ``max_zoom > BASE_ZOOM``
            in ``per-zoom`` mode.
    """
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant {variant!r}; expected one of {VARIANTS}")
    if mode not in MODES:
        raise ValueError(f"unknown mode {mode!r}; expected one of {MODES}")
    if mode == PER_ZOOM and profile.max_zoom > BASE_ZOOM:
        raise ValueError(f"max_zoom {profile.max_zoom} is above the base zoom {BASE_ZOOM}")
    check_lock_matches(lock, profile)
    vrts = source_vrts(lock, cache, profile.data_extent, work)
    if mode == PER_ZOOM:
        return _run_per_zoom(profile, edits, variant, vrts, work, workers, log)
    block_list = blocks(profile.data_extent, profile.block_zoom)
    pending = [b for b in block_list if not _finished(b, work)]
    rendered = _run_pool(
        pending,
        lambda pool, b: pool.submit(render_block, b, profile, edits, variant, vrts, work),
        lambda b: b.id,
        workers,
        log,
    )
    write_mosaics(block_list, work)
    return rendered
