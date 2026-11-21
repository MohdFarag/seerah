"""Tests for the tile stage: the tiler's arguments, retiling and the completeness check."""

import re
import subprocess
from pathlib import Path

import numpy as np
import pytest
from osgeo import gdal, osr
from seerah_map import tilestage
from seerah_map.config import Bounds, Profile, StyleSettings
from seerah_map.grid import blocks, expected_tiles
from seerah_map.workarea import open_area

_ZOOM = 12
_QUALITY = 77
_WORKERS = 2
_SIDE_PX = 512
_ORIGIN_M = 20037508.342789244
_TILE_PX = 256
_FIRST_X = 2048
_FIRST_Y = 1536
_TILE_PATH = re.compile(r"\d+/\d+/\d+\.png")
_EXTENT = Bounds(58.95, 20.45, 59.05, 20.55)


def _args(variant: str, resume: bool = False, min_zoom: int = 0) -> list[str]:
    return tilestage.tile_args(
        Path("s.vrt"), Path("t"), _ZOOM, variant, _QUALITY, _WORKERS, resume, min_zoom=min_zoom
    )


def _option(args: list[str], name: str) -> str:
    return args[args.index(name) + 1]


def _profile(tmp_path: Path) -> Profile:
    return Profile(
        name="t",
        folder=tmp_path,
        purpose="trial",
        camera_bounds=_EXTENT,
        margin_deg=0,
        fade_deg=0.05,
        max_zoom=_ZOOM,
        block_zoom=_ZOOM,
        webp_quality=80,
        style=StyleSettings(),
        peninsula_outline=tmp_path / "o.geojson",
    )


def _write_png(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mem = gdal.GetDriverByName("MEM").Create("", 4, 4, 4, gdal.GDT_Byte)
    gdal.GetDriverByName("PNG").CreateCopy(str(path), mem).Close()


def _fake_tiler(expected: set[tuple[int, int, int]], calls: list[list[str]]):
    def run(args: list[str], check: bool) -> None:
        calls.append(args)
        for z, x, y in expected:
            _write_png(Path(args[-1]) / str(z) / str(x) / f"{y}.png")

    return run


def test_tile_args_common() -> None:
    args = _args("painted")
    assert args[:3] == ["gdal", "raster", "tile"]
    assert _option(args, "--convention") == "xyz"
    assert _option(args, "--tiling-scheme") == "WebMercatorQuad"
    assert _option(args, "--min-zoom") == "0"
    assert _option(args, "--max-zoom") == str(_ZOOM)
    assert _option(args, "--resampling") == "average"
    assert _option(args, "--overview-resampling") == "average"
    assert _option(args, "-j") == str(_WORKERS)
    assert _option(args, "--webviewer") == "none"
    assert "--skip-blank" not in args
    assert "--resume" not in args
    assert args[-2:] == ["s.vrt", "t"]


def test_tile_args_painted_is_webp() -> None:
    args = _args("painted")
    assert _option(args, "-f") == "WEBP"
    assert _option(args, "--co") == f"QUALITY={_QUALITY}"


def test_tile_args_value_is_png() -> None:
    args = _args("value")
    assert _option(args, "-f") == "PNG"
    assert "--co" not in args


def test_tile_args_resume() -> None:
    assert "--resume" in _args("value", resume=True)


def test_tile_args_min_zoom() -> None:
    args = _args("painted", min_zoom=_ZOOM)
    assert _option(args, "--min-zoom") == str(_ZOOM)
    assert _option(args, "--max-zoom") == str(_ZOOM)


def test_per_zoom_tiles_each_zoom_from_its_mosaic(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    profile = Profile(
        name="t",
        folder=tmp_path,
        purpose="trial",
        camera_bounds=_EXTENT,
        margin_deg=0,
        fade_deg=0.05,
        max_zoom=2,
        block_zoom=2,
        webp_quality=80,
        style=StyleSettings(),
        peninsula_outline=tmp_path / "o.geojson",
    )
    work = open_area(tmp_path / "work", "fp")
    expected = expected_tiles(blocks(profile.data_extent, profile.block_zoom), profile.max_zoom)
    calls: list[list[str]] = []

    def fake_run(args: list[str], check: bool) -> None:
        assert check is True
        calls.append(args)
        z = int(_option(args, "--min-zoom"))
        for tz, x, y in expected:
            if tz == z:
                path = Path(args[-1]) / str(tz) / str(x) / f"{y}.webp"
                path.parent.mkdir(parents=True, exist_ok=True)
                mem = gdal.GetDriverByName("MEM").Create("", 4, 4, 4, gdal.GDT_Byte)
                gdal.GetDriverByName("WEBP").CreateCopy(str(path), mem).Close()

    monkeypatch.setattr(tilestage.subprocess, "run", fake_run)
    messages: list[str] = []
    count = tilestage.run_tile_stage(
        work, profile, "painted", _WORKERS, messages.append, mode="per-zoom"
    )
    assert count == len(expected)
    assert work.tiles_done.exists()
    assert [int(_option(c, "--min-zoom")) for c in calls] == [2, 1, 0]
    assert [int(_option(c, "--max-zoom")) for c in calls] == [2, 1, 0]
    assert [c[-2] for c in calls] == [str(work.path / f"style_z{z}.vrt") for z in (2, 1, 0)]
    assert messages == ["tile stage started", f"tile stage done ({len(expected)} tiles)"]


def test_run_tile_stage_rejects_unknown_mode(tmp_path: Path) -> None:
    profile = _profile(tmp_path)
    work = open_area(tmp_path / "work", "fp")
    with pytest.raises(ValueError, match="mode"):
        tilestage.run_tile_stage(work, profile, "painted", _WORKERS, lambda _m: None, mode="nope")


def test_folder_without_done_is_retiled(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    profile = _profile(tmp_path)
    work = open_area(tmp_path / "work", "fp")
    stale = work.tiles_dir / "stale.png"
    stale.write_bytes(b"half")
    expected = expected_tiles(blocks(profile.data_extent, profile.block_zoom), profile.max_zoom)
    calls: list[list[str]] = []
    monkeypatch.setattr(tilestage.subprocess, "run", _fake_tiler(expected, calls))
    messages: list[str] = []
    count = tilestage.run_tile_stage(work, profile, "value", _WORKERS, messages.append)
    assert count == len(expected)
    assert not stale.exists()
    assert work.tiles_done.exists()
    assert "--resume" not in calls[0]
    assert messages == ["tile stage started", f"tile stage done ({len(expected)} tiles)"]


def test_done_folder_is_not_retiled(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    profile = _profile(tmp_path)
    work = open_area(tmp_path / "work", "fp")
    work.tiles_done.write_text("")
    expected = expected_tiles(blocks(profile.data_extent, profile.block_zoom), profile.max_zoom)
    calls: list[list[str]] = []
    monkeypatch.setattr(tilestage.subprocess, "run", _fake_tiler(expected, calls))
    for z, x, y in expected:
        _write_png(work.tiles_dir / str(z) / str(x) / f"{y}.png")
    assert tilestage.run_tile_stage(work, profile, "value", _WORKERS, lambda _m: None) == len(
        expected
    )
    assert calls == []


def test_incomplete_tiling_raises(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    profile = _profile(tmp_path)
    work = open_area(tmp_path / "work", "fp")
    monkeypatch.setattr(tilestage.subprocess, "run", lambda args, check: None)
    with pytest.raises(tilestage.CompletenessError):
        tilestage.run_tile_stage(work, profile, "value", _WORKERS, lambda _m: None)
    assert not work.tiles_done.exists()


def test_completeness_reports_missing_and_broken(tmp_path: Path) -> None:
    _write_png(tmp_path / "1" / "0" / "0.png")
    (tmp_path / "1" / "0").mkdir(parents=True, exist_ok=True)
    (tmp_path / "1" / "0" / "1.png").write_bytes(b"not a png")
    expected = {(1, 0, 0), (1, 0, 1), (1, 1, 0)}
    assert sorted(tilestage.check_completeness(tmp_path, expected, "png")) == [(1, 0, 1), (1, 1, 0)]


def _write_vrt(tmp_path: Path, ink: tuple[int, int, int]) -> Path:
    """A 512x512 RGBA VRT: ink with a fading alpha, on zoom-12 tiles x 2048-2049, y 1536-1537."""
    pixel_m = 2 * _ORIGIN_M / (_TILE_PX * 2**_ZOOM)
    west = -_ORIGIN_M + _FIRST_X * _TILE_PX * pixel_m
    north = _ORIGIN_M - _FIRST_Y * _TILE_PX * pixel_m
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(3857)
    tif = tmp_path / "src.tif"
    ds = gdal.GetDriverByName("GTiff").Create(str(tif), _SIDE_PX, _SIDE_PX, 4, gdal.GDT_Byte)
    ds.SetGeoTransform((west, pixel_m, 0, north, 0, -pixel_m))
    ds.SetProjection(srs.ExportToWkt())
    alpha = np.tile(np.linspace(0, 153, _SIDE_PX).astype(np.uint8), (_SIDE_PX, 1))
    for band, value in enumerate(ink, start=1):
        ds.GetRasterBand(band).WriteArray(np.full((_SIDE_PX, _SIDE_PX), value, np.uint8))
    ds.GetRasterBand(4).WriteArray(alpha)
    ds.Close()
    vrt = tmp_path / "src.vrt"
    gdal.BuildVRT(str(vrt), [str(tif)]).Close()
    return vrt


@pytest.mark.slow
def test_tile_small_vrt(tmp_path: Path) -> None:
    vrt = _write_vrt(tmp_path, (150, 118, 72))
    out = tmp_path / "tiles"
    subprocess.run(
        tilestage.tile_args(vrt, out, _ZOOM, "value", _QUALITY, _WORKERS, False), check=True
    )
    expected = {(_ZOOM, _FIRST_X + dx, _FIRST_Y + dy) for dx in (0, 1) for dy in (0, 1)}
    base = set(expected)
    for z in range(_ZOOM):
        shift = _ZOOM - z
        expected |= {(z, x >> shift, y >> shift) for _, x, y in base}
    assert tilestage.check_completeness(out, expected, "png") == []
    files = [p.relative_to(out).as_posix() for p in out.rglob("*") if p.is_file()]
    assert all(_TILE_PATH.fullmatch(name) for name in files)
    assert {f"{z}/{x}/{y}.png" for z, x, y in expected} <= set(files)
