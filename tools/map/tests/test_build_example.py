"""End-to-end builds of the synthetic example in ``map-data/example``.

Each test runs the command line as a subprocess with its own data, work and
output folders, so no test touches a real run's ``/out``. Downloads go to
``/cache`` (the test cache volume) and are checked against the committed lock.
"""

import hashlib
import itertools
import json
import os
import shutil
import stat
from pathlib import Path

import numpy as np
import pytest
from conftest import StopAt
from osgeo import gdal, ogr, osr
from pmtiles.reader import MmapSource, Reader, all_tiles
from seerah_map import base, config, pack
from seerah_map.grid import blocks, expected_tiles, pixel_m, shrink_extent

pytestmark = pytest.mark.slow

EXAMPLE = Path(__file__).resolve().parents[3] / "map-data" / "example"
PROFILE = "example/example"
CACHE = Path("/cache")
BASE_ZOOM = 12
SINGLE_BLOCK_ZOOM = 7
ABSENT_CELL_WEST = 59.0
ALPHA_BAND = 4
FINISHING_BLOCKS = 2
BLOCK_DONE = StopAt(r"^block \S+ done \(", FINISHING_BLOCKS)
BLOCK_GATE = Path(__file__).resolve().parent / "block_gate"
TILE_STAGE = StopAt(r"^tile stage started$")
_FAILING_TILER = "#!/bin/sh\nexit 1\n"


@pytest.fixture(autouse=True)
def _needs_cache() -> None:
    """Skip outside the container, where the ``/cache`` volume is not writable."""
    if not os.access(CACHE, os.W_OK):
        pytest.skip(f"{CACHE} is not writable; run these tests in the image with the cache volume")


def _data(root: Path, block_zoom: int | None = None) -> Path:
    """Copy the example into ``root/example``, optionally with another block zoom."""
    shutil.copytree(EXAMPLE, root / "example")
    if block_zoom is not None:
        profile = root / "example" / "example.yaml"
        text = profile.read_text()
        profile.write_text(text.replace("block_zoom: 11", f"block_zoom: {block_zoom}"))
    return root


def _roots(run_dir: Path, data: Path) -> list[str]:
    return [
        "--data",
        str(data),
        "--cache",
        str(CACHE),
        "--work",
        str(run_dir / "work"),
        "--out",
        str(run_dir / "out"),
    ]


def _build(
    run_cli, run_dir: Path, data: Path, variant: str = "painted", mode: str | None = None, **kwargs
):
    cmd = ["build", *_roots(run_dir, data), "--profile", PROFILE, "--variant", variant]
    if mode is not None:
        cmd.extend(["--mode", mode])
    return run_cli(cmd, **kwargs)


def _archive(run_dir: Path) -> Path:
    (archive,) = (run_dir / "out").glob("*/relief.pmtiles")
    return archive


def _built(
    run_cli, run_dir: Path, data: Path, variant: str = "painted", mode: str | None = None
) -> Path:
    result = _build(run_cli, run_dir, data, variant, mode=mode)
    assert result.returncode == 0, result.output
    return _archive(run_dir)


def _tiles(archive: Path) -> dict[tuple[int, int, int], bytes]:
    with archive.open("rb") as f:
        return dict(all_tiles(MmapSource(f)))


def _decode(data: bytes) -> np.ndarray:
    name = f"/vsimem/tile-{hashlib.sha256(data).hexdigest()}"
    gdal.FileFromMemBuffer(name, data)
    try:
        ds = gdal.Open(name)
        assert ds is not None
        return ds.ReadAsArray()
    finally:
        gdal.Unlink(name)


def _decoded_at(archive: Path, zoom: int) -> dict[tuple[int, int, int], np.ndarray]:
    return {k: _decode(v) for k, v in _tiles(archive).items() if k[0] == zoom}


def _assert_same_tiles(a: Path, b: Path) -> None:
    """Assert the zoom-12 tiles of the example's block-zoom-11 grid decode equally.

    A coarser block grid also tiles the rest of its larger blocks, so only the
    tiles the example's own blocks cover are compared.
    """
    profile = config.load_profile(EXAMPLE / "example.yaml")
    keys = {
        t
        for t in expected_tiles(blocks(profile.data_extent, profile.block_zoom), BASE_ZOOM)
        if t[0] == BASE_ZOOM
    }
    first, second = _decoded_at(a, BASE_ZOOM), _decoded_at(b, BASE_ZOOM)
    assert keys <= first.keys()
    assert keys <= second.keys()
    for key in sorted(keys):
        np.testing.assert_array_equal(first[key], second[key], err_msg=str(key))


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _tile_west_lon(z: int, x: int) -> float:
    return x / 2**z * 360 - 180


@pytest.fixture(scope="session", autouse=True)
def _fetched(tmp_path_factory) -> None:
    """Fill the cache with the example's files, checked against the committed lock."""
    from conftest import _run_cli

    run_dir = tmp_path_factory.mktemp("fetch")
    data = _data(run_dir / "data")
    result = _run_cli(["fetch", *_roots(run_dir, data), "--profile", PROFILE])
    assert result.returncode == 0, result.output


@pytest.fixture(scope="session")
def reference(tmp_path_factory) -> Path:
    """A clean painted build of the example, shared by the comparison tests."""
    from conftest import _run_cli

    run_dir = tmp_path_factory.mktemp("reference")
    return _built(_run_cli, run_dir, _data(run_dir / "data"))


def test_example_builds_complete(reference):
    meta = json.loads((reference.parent / "metadata.json").read_text())
    assert meta["trial"] is True
    assert meta["encoding"] == "painted-rgba"
    profile = config.load_profile(EXAMPLE / "example.yaml")
    pack.verify(reference, profile)
    tiles = _tiles(reference)
    expected = expected_tiles(blocks(profile.data_extent, profile.block_zoom), profile.max_zoom)
    assert expected <= set(tiles)
    for key in expected:
        assert _decode(tiles[key]).shape[0] == ALPHA_BAND, key

    base_archive = reference.parent / base.BASE_ARCHIVE_NAME
    assert base_archive.is_file()
    base.verify_base(base_archive, profile)
    with base_archive.open("rb") as f:
        base_meta = Reader(MmapSource(f)).metadata()
    assert base_meta["name"] == profile.name
    assert base_meta["trial"] is True
    assert {layer["id"] for layer in base_meta["vector_layers"]} == {
        base.LAND_LAYER,
        base.COAST_LAYER,
    }


def test_example_value_variant(run_cli, tmp_path):
    archive = _built(run_cli, tmp_path, _data(tmp_path / "data"), "value")
    meta = json.loads((archive.parent / "metadata.json").read_text())
    assert meta["encoding"] == "terrarium-value"
    assert meta["trial"] is True


def test_example_per_zoom_builds_complete_and_matches_z12(run_cli, tmp_path, reference):
    archive = _built(run_cli, tmp_path, _data(tmp_path / "data"), "painted", mode="per-zoom")
    assert archive.parent.name.startswith("example-painted-per-zoom-")
    meta = json.loads((archive.parent / "metadata.json").read_text())
    assert meta["mode"] == "per-zoom"
    assert meta["variant"] == "painted"
    assert meta["trial"] is True
    assert meta["encoding"] == "painted-rgba"

    profile = config.load_profile(EXAMPLE / "example.yaml")
    pack.verify(archive, profile)
    base_archive = archive.parent / base.BASE_ARCHIVE_NAME
    assert base_archive.is_file()
    base.verify_base(base_archive, profile)

    tiles = _tiles(archive)
    expected = expected_tiles(blocks(profile.data_extent, profile.block_zoom), profile.max_zoom)
    assert expected <= set(tiles)
    _assert_same_tiles(archive, reference)

    coarse_zoom = 10
    coarse_keys = {t for t in expected if t[0] == coarse_zoom}
    pz_coarse = _decoded_at(archive, coarse_zoom)
    ref_coarse = _decoded_at(reference, coarse_zoom)
    assert coarse_keys <= pz_coarse.keys()
    assert coarse_keys <= ref_coarse.keys()
    assert any(not np.array_equal(pz_coarse[k], ref_coarse[k]) for k in sorted(coarse_keys))


def test_double_build_identical(run_cli, tmp_path):
    data = _data(tmp_path / "data")
    hashes = []
    for run in ("a", "b"):
        run_dir = tmp_path / run
        assert run_cli(["clean", *_roots(run_dir, data), "--all"]).returncode == 0
        relief = _built(run_cli, run_dir, data)
        base_archive = relief.parent / base.BASE_ARCHIVE_NAME
        hashes.append((_sha256(relief), _sha256(base_archive)))
    assert hashes[0] == hashes[1]


def _gated_env(finishing: list[str]) -> dict[str, str]:
    """The environment that lets only ``finishing`` blocks render (``block_gate/``)."""
    path = os.pathsep.join(filter(None, [str(BLOCK_GATE), os.environ.get("PYTHONPATH")]))
    return {"PYTHONPATH": path, "SEERAH_TEST_FINISHING_BLOCKS": ",".join(finishing)}


def test_resume_after_kill_in_block_stage(run_cli, tmp_path, reference):
    data = _data(tmp_path / "data")
    profile = config.load_profile(data / "example" / "example.yaml")
    all_blocks = sorted(b.id for b in blocks(profile.data_extent, profile.block_zoom))
    finishing = all_blocks[:FINISHING_BLOCKS]
    assert len(all_blocks) > FINISHING_BLOCKS
    killed = _build(run_cli, tmp_path, data, stop_at=BLOCK_DONE, env=_gated_env(finishing))
    assert killed.killed, killed.output
    assert "tile stage started" not in killed.output
    (area,) = (p for p in (tmp_path / "work").iterdir() if p.is_dir())
    assert sorted(p.stem for p in (area / "style").glob("*.tif")) == finishing
    _assert_same_tiles(_built(run_cli, tmp_path, data), reference)


def test_resume_after_kill_in_tile_stage(run_cli, tmp_path, reference):
    data = _data(tmp_path / "data")
    killed = _build(run_cli, tmp_path, data, stop_at=TILE_STAGE)
    assert killed.killed, killed.output
    _assert_same_tiles(_built(run_cli, tmp_path, data), reference)


def test_work_area_kept_on_failure(run_cli, tmp_path):
    data = _data(tmp_path / "data")
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    tiler = bin_dir / "gdal"
    tiler.write_text(_FAILING_TILER)
    tiler.chmod(tiler.stat().st_mode | stat.S_IXUSR)
    env = {"PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}"}
    failed = _build(run_cli, tmp_path, data, env=env)
    assert failed.returncode != 0
    assert "tile stage started" in failed.output
    areas = [p for p in (tmp_path / "work").iterdir() if p.is_dir()]
    assert len(areas) == 1
    listed = run_cli(["clean", *_roots(tmp_path, data)])
    assert listed.returncode == 0
    assert areas[0].name in listed.output


def test_block_split_matches_single_block(run_cli, tmp_path, reference):
    data = _data(tmp_path / "data", SINGLE_BLOCK_ZOOM)
    profile = config.load_profile(data / "example" / "example.yaml")
    assert len(blocks(profile.data_extent, profile.block_zoom)) == 1
    _assert_same_tiles(_built(run_cli, tmp_path, data), reference)


def _iter_lines(geom: ogr.Geometry):
    flat = ogr.GT_Flatten(geom.GetGeometryType())
    if flat == ogr.wkbLineString:
        yield geom
    elif flat in (ogr.wkbMultiLineString, ogr.wkbGeometryCollection):
        for i in range(geom.GetGeometryCount()):
            yield from _iter_lines(geom.GetGeometryRef(i))


def test_absent_neighbour_is_sea(reference):
    absent, land = [], []
    for (z, x, _y), pixels in _decoded_at(reference, BASE_ZOOM).items():
        alpha = pixels[ALPHA_BAND - 1]
        land.append(alpha.max())
        if _tile_west_lon(z, x) >= ABSENT_CELL_WEST:
            absent.append(alpha.max())
    assert absent, "no zoom-12 tile lies wholly in N20 E059"
    assert max(absent) == 0
    assert max(land) > 0


def test_example_base_has_no_inland_edge_coast(reference):
    profile = config.load_profile(EXAMPLE / "example.yaml")
    inner = shrink_extent(profile.data_extent, pixels=2)
    base_archive = reference.parent / base.BASE_ARCHIVE_NAME
    tol = 1.5 * pixel_m()
    inside_segments = 0
    with gdal.OpenEx(
        str(base_archive), gdal.OF_VECTOR, open_options=[f"ZOOM_LEVEL={BASE_ZOOM}"]
    ) as ds:
        coast = ds.GetLayerByName(base.COAST_LAYER)
        assert coast is not None
        geographic = osr.SpatialReference()
        geographic.ImportFromEPSG(4326)
        geographic.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
        project = osr.CoordinateTransformation(geographic, coast.GetSpatialRef())
        west, south, _ = project.TransformPoint(profile.data_extent.west, profile.data_extent.south)
        east, north, _ = project.TransformPoint(profile.data_extent.east, profile.data_extent.north)
        in_west, in_south, _ = project.TransformPoint(inner.west, inner.south)
        in_east, in_north, _ = project.TransformPoint(inner.east, inner.north)
        for feature in coast:
            geom = feature.GetGeometryRef()
            if geom is None:
                continue
            for line in _iter_lines(geom):
                points = [line.GetPoint_2D(i) for i in range(line.GetPointCount())]
                for (x0, y0), (x1, y1) in itertools.pairwise(points):
                    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
                    if in_west < mx < in_east and in_south < my < in_north:
                        inside_segments += 1
                    in_window = (
                        west - tol <= x0 <= east + tol
                        and west - tol <= x1 <= east + tol
                        and south - tol <= y0 <= north + tol
                        and south - tol <= y1 <= north + tol
                    )
                    if not in_window:
                        continue
                    dx, dy = abs(x1 - x0), abs(y1 - y0)
                    # Real pixel steps crossing an edge stay below tol; collinear false edge
                    # contours span more than tol along the boundary.
                    for name, c0, c1, edge, span in (
                        ("west", x0, x1, west, dy),
                        ("east", x0, x1, east, dy),
                        ("south", y0, y1, south, dx),
                        ("north", y0, y1, north, dx),
                    ):
                        assert not (
                            abs(c0 - edge) <= tol and abs(c1 - edge) <= tol and span > tol
                        ), (name, x0, y0, x1, y1)
    assert inside_segments > 0
