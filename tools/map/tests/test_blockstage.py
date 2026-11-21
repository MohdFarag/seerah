"""Tests for the block stage: reading the sources, rendering blocks and the mosaics."""

import math
from pathlib import Path

import numpy as np
import pytest
from osgeo import gdal, osr
from seerah_map import blockstage
from seerah_map.config import Bounds, Cell, Edit, FileRef, Lock, Profile, StyleSettings
from seerah_map.grid import BASE_ZOOM, Window, blocks, border_px
from seerah_map.workarea import open_area

_CELL_PX = 500
_PRESENT = (20, 58)
_ABSENT = (20, 59)
_FLAT_M = 500.0
_HILL_M = 20.0
_HILL_SIGMA_DEG = 0.02
_EXTENT = Bounds(58.95, 20.45, 59.05, 20.55)
_BLOCK_ZOOM = 12
_FADE_DEG = 0.05
_LAND_CLASS = 0
_OCEAN_CLASS = 1
_SEAM_DEG = 0.01
_RIM_PX = 8
_INNER_PX = 40
_ROWS_PX = 60
_EARTH_RADIUS_M = 6378137.0


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


def _profile(
    folder: Path,
    fade_deg: float = _FADE_DEG,
    max_zoom: int = _BLOCK_ZOOM,
    block_zoom: int = _BLOCK_ZOOM,
) -> Profile:
    return Profile(
        name="t",
        folder=folder,
        purpose="trial",
        camera_bounds=_EXTENT,
        margin_deg=0,
        fade_deg=fade_deg,
        max_zoom=max_zoom,
        block_zoom=block_zoom,
        webp_quality=80,
        style=StyleSettings(),
        peninsula_outline=folder / "o.geojson",
    )


def _pixel_lonlat(window) -> tuple[np.ndarray, np.ndarray]:
    """Pixel-centre longitudes and latitudes of ``window`` (degrees)."""
    west, south, east, north = window.bounds_3857()
    xs = west + (np.arange(window.cols) + 0.5) * (east - west) / window.cols
    ys = north - (np.arange(window.rows) + 0.5) * (north - south) / window.rows
    radius = _EARTH_RADIUS_M
    lons = np.degrees(xs / radius)
    lats = np.degrees(2 * np.arctan(np.exp(ys / radius)) - math.pi / 2)
    return np.meshgrid(lons, lats)


def _read(path: Path) -> np.ndarray:
    ds = gdal.Open(str(path))
    try:
        return ds.ReadAsArray()
    finally:
        ds.Close()


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
    """A hill cut by the extent's west edge shows no rim darker than the hill's own shading.

    The fade is off, so only the fill of invalid pixels keeps the cut from reading as a cliff.
    Blocks render in this process so a test can swap out the fill.
    """
    profile, lock, cache, work = _setup(tmp_path, elevation=_hill_at_west_edge(), fade_deg=0)
    vrts = blockstage.source_vrts(lock, cache, profile.data_extent, work)
    block_list = blocks(profile.data_extent, profile.block_zoom)
    for block in block_list:
        blockstage.render_block(block, profile, [], "painted", vrts, work)
    blockstage.write_mosaics(block_list, work)

    alpha_band = 4
    mosaic = gdal.Open(str(work.path / "style.vrt"))
    alpha = mosaic.GetRasterBand(alpha_band).ReadAsArray()
    west, pixel_width = mosaic.GetGeoTransform()[:2]
    lons = np.degrees(
        (west + (np.arange(mosaic.RasterXSize) + 0.5) * pixel_width) / _EARTH_RADIUS_M
    )
    first_inside = int(np.argmax(lons >= _EXTENT.west))
    assert first_inside > 0
    north = mosaic.GetGeoTransform()[3]
    ys = north - (np.arange(mosaic.RasterYSize) + 0.5) * pixel_width
    lats = np.degrees(2 * np.arctan(np.exp(ys / _EARTH_RADIUS_M)) - math.pi / 2)
    centre_row = int(np.argmin(np.abs(lats - (_EXTENT.south + _EXTENT.north) / 2)))
    rows = slice(centre_row - _ROWS_PX, centre_row + _ROWS_PX)
    rim = alpha[rows, first_inside : first_inside + _RIM_PX]
    just_inside = alpha[rows, first_inside + _RIM_PX : first_inside + _INNER_PX]
    assert alpha[rows, first_inside:].max() > 0
    assert rim.max() <= just_inside.max()


def test_column_longitudes_uses_window_zoom():
    lons = blockstage._column_longitudes(Window(0, 0, 256, 256, zoom=0))
    assert lons.shape == (256,)
    assert lons[0] == pytest.approx(-180 + 360 / 512)
    assert lons[-1] == pytest.approx(180 - 360 / 512)


def test_crop_pads_handles_zero_sides():
    arr = np.arange(2 * 6 * 8).reshape(2, 6, 8)
    np.testing.assert_array_equal(blockstage._crop_pads(arr, (0, 0, 0, 0)), arr)
    np.testing.assert_array_equal(blockstage._crop_pads(arr, (0, 0, 2, 1)), arr[:, :5, :6])
    np.testing.assert_array_equal(blockstage._crop_pads(arr, (2, 1, 0, 0)), arr[:, 1:, 2:])
    np.testing.assert_array_equal(blockstage._crop_pads(arr, (1, 2, 2, 1)), arr[:, 2:5, 1:6])


def test_render_block_rejects_non_base_zoom(tmp_path):
    profile, lock, cache, work = _setup(tmp_path)
    vrts = blockstage.source_vrts(lock, cache, _EXTENT, work)
    coarse_block = blocks(_EXTENT, 8, zoom=8)[0]
    with pytest.raises(ValueError, match="zoom"):
        blockstage.render_block(coarse_block, profile, [], "painted", vrts, work)


def test_run_block_stage_validates_variant_mode_and_zoom(tmp_path):
    profile, lock, cache, work = _setup(tmp_path)
    with pytest.raises(ValueError, match="variant"):
        blockstage.run_block_stage(profile, [], "bad", lock, cache, work, 1, lambda _m: None)
    with pytest.raises(ValueError, match="mode"):
        blockstage.run_block_stage(
            profile, [], "painted", lock, cache, work, 1, lambda _m: None, mode="bad"
        )
    over_profile = _profile(tmp_path, max_zoom=BASE_ZOOM + 1, block_zoom=BASE_ZOOM)
    with pytest.raises(ValueError, match="max_zoom"):
        blockstage.run_block_stage(
            over_profile,
            [],
            "painted",
            lock,
            cache,
            work,
            1,
            lambda _m: None,
            mode=blockstage.PER_ZOOM,
        )
    assert not (work.path / "dem.vrt").exists()


def test_per_zoom_sub12_renders_mask_and_coarse_zooms_and_resumes(tmp_path):
    profile = _profile(tmp_path, max_zoom=10, block_zoom=9)
    cache = tmp_path / "cache"
    lock = _make_cache(cache, _hill_at_west_edge())
    work = open_area(tmp_path / "work", "fp")
    logged: list[str] = []

    first = blockstage.run_block_stage(
        profile, [], "painted", lock, cache, work, 2, logged.append, mode=blockstage.PER_ZOOM
    )
    assert first
    assert len(logged) == len(first)
    assert any(line.startswith("block mask/") for line in logged)
    assert any(line.startswith("block z10/") for line in logged)
    assert (work.path / "mask.vrt").is_file()
    assert not (work.path / "style.vrt").exists()
    assert not (work.path / f"style_z{BASE_ZOOM}.vrt").exists()
    for block in blocks(profile.data_extent, profile.block_zoom):
        assert (work.mask_dir / f"{block.id}.tif").is_file()
    for z in range(profile.max_zoom + 1):
        assert (work.path / f"style_z{z}.vrt").is_file()
        z_blocks = blocks(profile.data_extent, min(profile.block_zoom, z), zoom=z)
        for block in z_blocks:
            tif = work.style_dir / f"z{z}" / f"{block.id}.tif"
            assert tif.is_file()
            arr = _read(tif)
            assert arr.shape == (4, block.window.rows, block.window.cols)
    assert _read(work.style_dir / "z0" / "z0-x0-y0.tif").shape == (4, 256, 256)

    z10_blocks = blocks(profile.data_extent, min(profile.block_zoom, 10), zoom=10)
    z10_block = z10_blocks[0]
    z10_arr = _read(work.style_dir / "z10" / f"{z10_block.id}.tif")
    lons, lats = _pixel_lonlat(z10_block.window)
    outside = (
        (lons < _EXTENT.west)
        | (lons > _EXTENT.east)
        | (lats < _EXTENT.south)
        | (lats > _EXTENT.north)
    )
    assert z10_arr[3][outside].max() == 0
    assert z10_arr[3][~outside].max() > 0

    mtimes = {p: p.stat().st_mtime_ns for p in work.path.rglob("*.tif")}
    logged_before = len(logged)
    second = blockstage.run_block_stage(
        profile, [], "painted", lock, cache, work, 2, logged.append, mode=blockstage.PER_ZOOM
    )
    assert second == []
    assert len(logged) == logged_before
    assert {p: p.stat().st_mtime_ns for p in work.path.rglob("*.tif")} == mtimes

    target = next((work.style_dir / "z1").glob("*.tif"))
    target.unlink()
    third = blockstage.run_block_stage(
        profile, [], "painted", lock, cache, work, 2, logged.append, mode=blockstage.PER_ZOOM
    )
    assert len(third) == 1
    assert target.is_file()

    edit = Edit(
        id="e1",
        geometry={
            "type": "Polygon",
            "coordinates": [
                [[58.96, 20.46], [59.02, 20.46], [59.02, 20.52], [58.96, 20.52], [58.96, 20.46]]
            ],
        },
        operation="raise",
        amount_m=120.0,
        height_m=None,
        width_m=None,
        feather_m=500.0,
        review="approved",
    )
    work_edited = open_area(tmp_path / "work", "edited")
    blockstage.run_block_stage(
        profile,
        [edit],
        "painted",
        lock,
        cache,
        work_edited,
        2,
        lambda _m: None,
        mode=blockstage.PER_ZOOM,
    )
    z10_edited = _read(work_edited.style_dir / "z10" / f"{z10_block.id}.tif")
    assert not np.array_equal(z10_edited, z10_arr)


def test_per_zoom_z12_matches_pyramid_pixels_and_builds_all_zooms(tmp_path):
    profile = _profile(tmp_path)
    cache = tmp_path / "cache"
    lock = _make_cache(cache, _hill_at_west_edge())
    edit = Edit(
        id="e1",
        geometry={
            "type": "Polygon",
            "coordinates": [
                [[58.97, 20.47], [59.01, 20.47], [59.01, 20.51], [58.97, 20.51], [58.97, 20.47]]
            ],
        },
        operation="raise",
        amount_m=50.0,
        height_m=None,
        width_m=None,
        feather_m=200.0,
        review="approved",
    )

    work_pyr = open_area(tmp_path / "work", "pyr")
    work_pz = open_area(tmp_path / "work", "pz")
    blockstage.run_block_stage(
        profile, [edit], "painted", lock, cache, work_pyr, 2, lambda _m: None
    )
    rendered = blockstage.run_block_stage(
        profile,
        [edit],
        "painted",
        lock,
        cache,
        work_pz,
        2,
        lambda _m: None,
        mode=blockstage.PER_ZOOM,
    )
    assert rendered
    assert (work_pz.path / "mask.vrt").is_file()
    for z in range(BASE_ZOOM + 1):
        assert (work_pz.path / f"style_z{z}.vrt").is_file()

    z12_blocks = blocks(profile.data_extent, profile.block_zoom)
    for block in z12_blocks:
        np.testing.assert_array_equal(
            _read(work_pz.style_dir / f"z{BASE_ZOOM}" / f"{block.id}.tif"),
            _read(work_pyr.style_dir / f"{block.id}.tif"),
        )
        np.testing.assert_array_equal(
            _read(work_pz.mask_dir / f"{block.id}.tif"),
            _read(work_pyr.mask_dir / f"{block.id}.tif"),
        )

    assert (
        blockstage.run_block_stage(
            profile,
            [edit],
            "painted",
            lock,
            cache,
            work_pz,
            2,
            lambda _m: None,
            mode=blockstage.PER_ZOOM,
        )
        == []
    )

    z12_style = work_pz.style_dir / f"z{BASE_ZOOM}" / f"{z12_blocks[0].id}.tif"
    z12_style.unlink()
    resumed = blockstage.run_block_stage(
        profile,
        [edit],
        "painted",
        lock,
        cache,
        work_pz,
        2,
        lambda _m: None,
        mode=blockstage.PER_ZOOM,
    )
    assert resumed == [f"z{BASE_ZOOM}/{z12_blocks[0].id}"]
    assert z12_style.is_file()
