"""End-to-end builds of the synthetic example in ``map-data/example``.

Each test runs the command line as a subprocess with its own data, work and
output folders, so no test touches a real run's ``/out``. Downloads go to
``/cache`` (the test cache volume) and are checked against the committed lock.
"""

import hashlib
import json
import os
import shutil
import stat
from pathlib import Path

import numpy as np
import pytest
from conftest import StopAt
from osgeo import gdal
from pmtiles.reader import MmapSource, all_tiles
from seerah_map import config, pack
from seerah_map.grid import blocks, expected_tiles

pytestmark = pytest.mark.slow

EXAMPLE = Path(__file__).resolve().parents[3] / "map-data" / "example"
PROFILE = "example/example"
CACHE = Path("/cache")
BASE_ZOOM = 12
SINGLE_BLOCK_ZOOM = 7
ABSENT_CELL_WEST = 59.0
ALPHA_BAND = 4
TWO_BLOCKS = StopAt(r"^block \S+ done \(", 2)
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


def _roots(base: Path, data: Path) -> list[str]:
    return [
        "--data",
        str(data),
        "--cache",
        str(CACHE),
        "--work",
        str(base / "work"),
        "--out",
        str(base / "out"),
    ]


def _build(run_cli, base: Path, data: Path, variant: str = "painted", **kwargs):
    return run_cli(
        ["build", *_roots(base, data), "--profile", PROFILE, "--variant", variant], **kwargs
    )


def _archive(base: Path) -> Path:
    (archive,) = (base / "out").glob("*/relief.pmtiles")
    return archive


def _built(run_cli, base: Path, data: Path, variant: str = "painted") -> Path:
    result = _build(run_cli, base, data, variant)
    assert result.returncode == 0, result.output
    return _archive(base)


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

    base = tmp_path_factory.mktemp("fetch")
    data = _data(base / "data")
    result = _run_cli(["fetch", *_roots(base, data), "--profile", PROFILE])
    assert result.returncode == 0, result.output


@pytest.fixture(scope="session")
def reference(tmp_path_factory) -> Path:
    """A clean painted build of the example, shared by the comparison tests."""
    from conftest import _run_cli

    base = tmp_path_factory.mktemp("reference")
    return _built(_run_cli, base, _data(base / "data"))


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


def test_example_value_variant(run_cli, tmp_path):
    archive = _built(run_cli, tmp_path, _data(tmp_path / "data"), "value")
    meta = json.loads((archive.parent / "metadata.json").read_text())
    assert meta["encoding"] == "terrarium-value"
    assert meta["trial"] is True


def test_double_build_identical(run_cli, tmp_path):
    data = _data(tmp_path / "data")
    hashes = []
    for run in ("a", "b"):
        base = tmp_path / run
        assert run_cli(["clean", *_roots(base, data), "--all"]).returncode == 0
        hashes.append(_sha256(_built(run_cli, base, data)))
    assert hashes[0] == hashes[1]


def test_resume_after_kill_in_block_stage(run_cli, tmp_path, reference):
    data = _data(tmp_path / "data")
    killed = _build(run_cli, tmp_path, data, stop_at=TWO_BLOCKS)
    assert killed.killed, killed.output
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
