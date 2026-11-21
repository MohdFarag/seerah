"""Tests for packing a tile folder into PMTiles and checking the archive."""

import hashlib
import json
from pathlib import Path

import pytest
from seerah_map import pack
from seerah_map.config import Bounds, Profile, StyleSettings
from seerah_map.workarea import open_area

_EXTENT = Bounds(58.8, 20.5, 59.05, 20.75)
_MAX_ZOOM = 1
_PROVENANCE = pack.Provenance("abc123", "def456", "ghcr.io/x/seerah-map@sha256:1", "f" * 64)
_TILE_BYTES = b"tile-bytes"
_OUTSIDE_ZOOM = 1
_OUTSIDE_BYTES = 7
_INSIDE_BYTES = 11
_TILES = [(0, 0, 0), (1, 0, 0), (1, 1, 0)]


def _profile(tmp_path: Path, extent: Bounds = _EXTENT, max_zoom: int = _MAX_ZOOM) -> Profile:
    return Profile(
        name="t",
        folder=tmp_path,
        purpose="trial",
        camera_bounds=extent,
        margin_deg=0,
        fade_deg=0.05,
        max_zoom=max_zoom,
        block_zoom=max_zoom,
        webp_quality=80,
        style=StyleSettings(),
        peninsula_outline=tmp_path / "o.geojson",
    )


def _tiles(tiles_dir: Path, tiles: list[tuple[int, int, int]]) -> None:
    for z, x, y in tiles:
        path = tiles_dir / str(z) / str(x) / f"{y}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(_TILE_BYTES + f"{z}/{x}/{y}".encode())


def _work(tmp_path: Path):
    work = open_area(tmp_path / "work", "fp")
    _tiles(work.tiles_dir, _TILES)
    return work


def test_metadata_marks_trial(tmp_path):
    meta = pack.metadata(_profile(tmp_path), "painted", _PROVENANCE)
    assert meta == {
        "format": "webp",
        "minzoom": 0,
        "maxzoom": _MAX_ZOOM,
        "bounds": "58.8,20.5,59.05,20.75",
        "name": "t",
        "trial": True,
        "variant": "painted",
        "mode": "pyramid",
        "encoding": "painted-rgba",
        "fingerprint": "f" * 64,
        "tool_commit": "abc123",
        "data_commit": "def456",
        "image": "ghcr.io/x/seerah-map@sha256:1",
    }
    value = pack.metadata(_profile(tmp_path), "value", _PROVENANCE)
    assert (value["format"], value["encoding"], value["mode"]) == (
        "png",
        "terrarium-value",
        "pyramid",
    )
    per_zoom = pack.metadata(_profile(tmp_path), "painted", _PROVENANCE, mode="per-zoom")
    assert per_zoom["mode"] == "per-zoom"


def _packed(tmp_path: Path, out_name: str, meta_extent: Bounds = _EXTENT) -> Path:
    work = open_area(tmp_path / "work", "fp")
    if not work.tiles_dir.exists() or not any(work.tiles_dir.iterdir()):
        _tiles(work.tiles_dir, _TILES)
    meta = pack.metadata(_profile(tmp_path, meta_extent), "value", _PROVENANCE)
    return pack.pack(work, tmp_path / out_name, meta)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.slow
def test_pack_is_deterministic(tmp_path):
    first = _packed(tmp_path, "a")
    second = _packed(tmp_path, "b")
    assert first.name == "relief.pmtiles"
    assert (first.parent / "metadata.json").is_file()
    assert _sha256(first) == _sha256(second)
    assert json.loads((first.parent / "metadata.json").read_text())["trial"] is True
    assert pack.stored_tiles(first) == 3


@pytest.mark.slow
def test_verify_checks_bounds_and_trial(tmp_path):
    archive = _packed(tmp_path, "ok")
    pack.verify(archive, _profile(tmp_path))
    with pytest.raises(pack.PackError, match="bounds"):
        pack.verify(archive, _profile(tmp_path, Bounds(58.8, 20.5, 59.05, 20.8)))
    with pytest.raises(pack.PackError, match="maxzoom"):
        pack.verify(archive, _profile(tmp_path, max_zoom=2))


@pytest.mark.slow
def test_verify_rejects_missing_trial_marker(tmp_path):
    work = _work(tmp_path)
    meta = pack.metadata(_profile(tmp_path), "value", _PROVENANCE) | {"trial": False}
    archive = pack.pack(work, tmp_path / "out", meta)
    with pytest.raises(pack.PackError, match="trial"):
        pack.verify(archive, _profile(tmp_path))


def test_size_by_zoom_split(tmp_path):
    outline = tmp_path / "o.geojson"
    # Zoom 1 tile (0,0) has its centre at lon -90, lat 66.5; tile (1,0) at lon 90.
    ring = [[-180, 0], [0, 0], [0, 89], [-180, 89], [-180, 0]]
    outline.write_text(
        json.dumps(
            {
                "type": "FeatureCollection",
                "features": [
                    {
                        "type": "Feature",
                        "properties": {},
                        "geometry": {"type": "Polygon", "coordinates": [ring]},
                    }
                ],
            }
        )
    )
    tiles_dir = tmp_path / "tiles"
    for z, x, y, size in [(0, 0, 0, 5), (1, 0, 0, _INSIDE_BYTES), (1, 1, 0, _OUTSIDE_BYTES)]:
        path = tiles_dir / str(z) / str(x) / f"{y}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"x" * size)
    (tiles_dir / "metadata.json").write_text("{}")
    rows = pack.size_by_zoom(tiles_dir, outline)
    # Zoom 0 centre is lon 0 / lat 0, on the polygon's edge: counted as outside.
    assert rows == [
        {"zoom": 0, "inside_bytes": 0, "outside_bytes": 5},
        {"zoom": _OUTSIDE_ZOOM, "inside_bytes": _INSIDE_BYTES, "outside_bytes": _OUTSIDE_BYTES},
    ]


def test_stored_tiles_rejects_a_file_that_is_not_an_archive(tmp_path):
    bogus = tmp_path / "bogus.pmtiles"
    bogus.write_bytes(b"not an archive")
    with pytest.raises(pack.PackError, match="tile count"):
        pack.stored_tiles(bogus)
