"""Tests for the block stage: reading the sources, rendering blocks and the mosaics."""

import math
from pathlib import Path

import numpy as np
import pytest
from osgeo import gdal, osr
from seerah_map import blockstage
from seerah_map.config import Bounds, Cell, FileRef, Lock, Profile, StyleSettings
from seerah_map.grid import blocks, border_px
from seerah_map.workarea import open_area

_CELL_PX = 500
_PRESENT = (20, 58)
_ABSENT = (20, 59)
_FLAT_M = 500.0
_HILL_M = 800.0
_HILL_SIGMA_DEG = 0.02
_EXTENT = Bounds(58.95, 20.45, 59.05, 20.55)
_BLOCK_ZOOM = 12
_FADE_DEG = 0.05
_LAND_CLASS = 0
_OCEAN_CLASS = 1
_SEAM_DEG = 0.01


def _write_cell(path: Path, lat: int, lon: int, data: np.ndarray, dtype: int) -> None:
    """Write ``data`` as a one-degree EPSG:4326 GeoTIFF with south-west corner ``lat``, ``lon``."""
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(4326)
    ds = gdal.GetDriverByName("GTiff").Create(str(path), _CELL_PX, _CELL_PX, 1, dtype)
    ds.SetGeoTransform((lon, 1 / _CELL_PX, 0, lat + 1, 0, -1 / _CELL_PX))
    ds.SetProjection(srs.ExportToWkt())
    ds.GetRasterBand(1).WriteArray(data)
    ds.Close()


def _cell_lonlat(lat: int, lon: int) -> tuple[np.ndarray, np.ndarray]:
    """Pixel-centre longitudes and latitudes of a synthetic cell (degrees)."""
    centres = (np.arange(_CELL_PX) + 0.5) / _CELL_PX
    return np.meshgrid(lon + centres, lat + 1 - centres)


def _make_cache(cache: Path, elevation: np.ndarray) -> Lock:
    """Write the present cell's DEM and WBM (all land) and a lock naming them."""
    cache.mkdir(parents=True, exist_ok=True)
    lat, lon = _PRESENT
    _write_cell(cache / "dem.tif", lat, lon, elevation.astype(np.float32), gdal.GDT_Float32)
    wbm = np.full((_CELL_PX, _CELL_PX), _LAND_CLASS, dtype=np.uint8)
    _write_cell(cache / "wbm.tif", lat, lon, wbm, gdal.GDT_Byte)

    def ref(name: str) -> FileRef:
        return FileRef(url=f"https://example.invalid/{name}", size=0, sha256="0", path=name)

    return Lock(
        created="2026-11-16",
        tile_list_sha256="0",
        cells=(
            Cell(lat, lon, False, ref("dem.tif"), ref("wbm.tif")),
            Cell(*_ABSENT, True, None, None),
        ),
        natural_earth=(),
    )


def _flat() -> np.ndarray:
    return np.full((_CELL_PX, _CELL_PX), _FLAT_M)


def _hill_at_west_edge() -> np.ndarray:
    """A hill centred on the extent's west edge, so the edge cuts it in half."""
    lons, lats = _cell_lonlat(*_PRESENT)
    centre_lat = (_EXTENT.south + _EXTENT.north) / 2
    r2 = (lons - _EXTENT.west) ** 2 + (lats - centre_lat) ** 2
    return _FLAT_M + _HILL_M * np.exp(-r2 / (2 * _HILL_SIGMA_DEG**2))


def _profile(folder: Path, fade_deg: float = _FADE_DEG) -> Profile:
    return Profile(
        name="t",
        folder=folder,
        purpose="trial",
        camera_bounds=_EXTENT,
        margin_deg=0,
        fade_deg=fade_deg,
        max_zoom=_BLOCK_ZOOM,
        block_zoom=_BLOCK_ZOOM,
        webp_quality=80,
        style=StyleSettings(),
        peninsula_outline=folder / "o.geojson",
    )


def _pixel_lonlat(window) -> tuple[np.ndarray, np.ndarray]:
    """Pixel-centre longitudes and latitudes of a zoom-12 window (degrees)."""
    west, south, east, north = window.bounds_3857()
    xs = west + (np.arange(window.cols) + 0.5) * (east - west) / window.cols
    ys = north - (np.arange(window.rows) + 0.5) * (north - south) / window.rows
    radius = 6378137.0
    lons = np.degrees(xs / radius)
    lats = np.degrees(2 * np.arctan(np.exp(ys / radius)) - math.pi / 2)
    return np.meshgrid(lons, lats)


def _read(path: Path) -> np.ndarray:
    ds = gdal.Open(str(path))
    return ds.ReadAsArray()


def test_absent_area_reads_as_sea(tmp_path):
    cache = tmp_path / "cache"
    lock = _make_cache(cache, _flat())
    work = open_area(tmp_path / "work", "fp")
    dem_vrt, wbm_vrt = blockstage.source_vrts(lock, cache, _EXTENT, work)
    profile = _profile(tmp_path)

    seen_absent = seen_outside = False
    for block in blocks(_EXTENT, _BLOCK_ZOOM):
        border = border_px(profile.style, [], block)
        elevation, wbm, valid = blockstage.read_block(block, border, dem_vrt, wbm_vrt, _EXTENT)
        lons, lats = _pixel_lonlat(block.window.pad(border))
        inside = (
            (lons >= _EXTENT.west)
            & (lons <= _EXTENT.east)
            & (lats >= _EXTENT.south)
            & (lats <= _EXTENT.north)
        )
        np.testing.assert_array_equal(valid, inside)
        absent = valid & (lons > _ABSENT[1] + _SEAM_DEG)
        present = valid & (lons < _ABSENT[1] - _SEAM_DEG)
        assert (wbm[absent] == _OCEAN_CLASS).all()
        assert (elevation[absent] == 0).all()
        assert (wbm[present] == _LAND_CLASS).all()
        np.testing.assert_allclose(elevation[present], _FLAT_M)
        seen_absent |= bool(absent.any())
        seen_outside |= bool((~valid).any())
    assert seen_absent and seen_outside


def _setup(tmp_path, elevation=None, fade_deg=_FADE_DEG):
    cache = tmp_path / "cache"
    lock = _make_cache(cache, _flat() if elevation is None else elevation)
    work = open_area(tmp_path / "work", "fp")
    return _profile(tmp_path, fade_deg), lock, cache, work


def test_render_block_writes_atomically(tmp_path, monkeypatch):
    profile, lock, cache, work = _setup(tmp_path)
    vrts = blockstage.source_vrts(lock, cache, profile.data_extent, work)
    first, second = blocks(profile.data_extent, profile.block_zoom)[:2]

    blockstage.render_block(first, profile, [], "painted", vrts, work)
    assert (work.style_dir / f"{first.id}.tif").is_file()
    assert (work.mask_dir / f"{first.id}.tif").is_file()

    def fail(*_args, **_kwargs):
        raise RuntimeError("disk full")

    monkeypatch.setattr(blockstage, "_fill_dataset", fail)
    with pytest.raises(RuntimeError, match="disk full"):
        blockstage.render_block(second, profile, [], "painted", vrts, work)
    assert not (work.style_dir / f"{second.id}.tif").exists()
    assert not (work.mask_dir / f"{second.id}.tif").exists()
    assert not list(work.path.rglob("*.tmp"))


def test_finished_blocks_skipped(tmp_path):
    profile, lock, cache, work = _setup(tmp_path)
    logged: list[str] = []
    total = len(blocks(profile.data_extent, profile.block_zoom))

    first = blockstage.run_block_stage(profile, [], "value", lock, cache, work, 2, logged.append)
    assert len(first) == total
    assert any(line.endswith(f"({total}/{total})") for line in logged)
    mtimes = {p: p.stat().st_mtime_ns for p in work.style_dir.iterdir()}

    second = blockstage.run_block_stage(profile, [], "value", lock, cache, work, 2, logged.append)
    assert second == []
    assert {p: p.stat().st_mtime_ns for p in work.style_dir.iterdir()} == mtimes
    assert (work.path / "style.vrt").is_file()
    assert (work.path / "mask.vrt").is_file()


def test_missing_block_file_fails(tmp_path):
    profile, lock, cache, work = _setup(tmp_path)
    blockstage.run_block_stage(profile, [], "value", lock, cache, work, 1, lambda _line: None)
    block_list = blocks(profile.data_extent, profile.block_zoom)
    missing = block_list[-1]
    (work.style_dir / f"{missing.id}.tif").unlink()

    with pytest.raises(FileNotFoundError, match=missing.id):
        blockstage.write_mosaics(block_list, work)


def test_no_dark_rim_at_extent_edge(tmp_path):
    profile, lock, cache, work = _setup(tmp_path, elevation=_hill_at_west_edge())
    blockstage.run_block_stage(profile, [], "painted", lock, cache, work, 1, lambda _line: None)

    alpha_band = 4
    mosaic = gdal.Open(str(work.path / "style.vrt"))
    alpha = mosaic.GetRasterBand(alpha_band).ReadAsArray()
    window_west = mosaic.GetGeoTransform()[0]
    lons = np.degrees(
        (window_west + (np.arange(mosaic.RasterXSize) + 0.5) * mosaic.GetGeoTransform()[1])
        / 6378137.0
    )
    first_inside = int(np.argmax(lons >= _EXTENT.west))
    assert first_inside > 0
    assert alpha.max() > 0
    assert (alpha[:, : first_inside + 1] == 0).all()
