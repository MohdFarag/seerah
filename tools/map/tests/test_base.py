"""Build and inspect vector base layers from synthetic, internally masked GeoTIFFs."""

import fcntl
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from threading import Barrier

import numpy as np
import pytest
from osgeo import gdal, ogr, osr
from seerah_map import base
from seerah_map.blockstage import _distance_to_edge_deg, _write_geotiff
from seerah_map.config import Bounds, FileRef, Lock, load_profile
from seerah_map.fetch import NE_FILES
from seerah_map.grid import Window, blocks, row_latitudes, shrink_extent
from seerah_map.pack import Provenance
from seerah_map.workarea import open_area

_SEAM_LON = 45
_EXTENT = Bounds(_SEAM_LON - 0.125, 0.125, _SEAM_LON + 0.125, 0.375)
_PROVENANCE = Provenance("tool", "data", "image", "test-fingerprint")
_ROOT = Path(__file__).resolve().parents[3]


def _mask_inputs(root, inland):
    profile = replace(
        load_profile(_ROOT / "map-data/example/example.yaml"),
        camera_bounds=_EXTENT,
        margin_deg=0,
        max_zoom=6,
        block_zoom=6,
    )
    work = open_area(root / "work", "fp")
    # Keep the real zoom-6 seam at 45 degrees east and the actual zoom-12 pixels.
    # Trim distant invalid rows and columns from the two 16384 × 16384 windows.
    pieces = [
        replace(b, window=Window(655360 + (i - 1) * 400, 523150, 400, 820))
        for i, b in enumerate(blocks(_EXTENT, 6))
    ]
    assert len(pieces) == 2
    paths = []
    for block in pieces:
        window = block.window
        valid = _distance_to_edge_deg(window, _EXTENT) >= 0
        land = np.broadcast_to((row_latitudes(window)[:, None] >= 0.25) | inland, valid.shape)
        path = work.mask_dir / f"{block.id}.tif"
        _write_geotiff(path, (land.astype(np.uint8) * 255)[None, ...], window, valid)
        paths.append(str(path))
    gdal.BuildVRT(str(work.path / "mask.vrt"), paths).Close()
    return profile, work, pieces


def _natural_earth(cache):
    directory = cache / "ne"
    directory.mkdir(parents=True)
    polygon = {"type": "Polygon", "coordinates": [[[40, -5], [50, -5], [50, 5], [40, 5], [40, -5]]]}
    coast = {"type": "LineString", "coordinates": [[40, 1], [50, 1]]}
    refs = []
    for name in NE_FILES:
        path = directory / name
        path.write_text(
            json.dumps(
                {
                    "type": "Feature",
                    "properties": {"synthetic": True},
                    "geometry": polygon if "_land." in name else coast,
                }
            )
        )
        refs.append(FileRef("", path.stat().st_size, "", str(path.relative_to(cache))))
    return Lock("", "", (), tuple(refs))


def _build(root, inland=False):
    profile, work, pieces = _mask_inputs(root, inland)
    cache = root / "cache"
    lock = _natural_earth(cache)
    messages = []
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(base, "blocks", lambda extent, zoom: pieces)
        archive = base.build_base_layer(
            profile, lock, cache, work, root / "out", _PROVENANCE, messages.append
        )
    assert messages[0] == "base stage started"
    return profile, work, archive


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    return _build(tmp_path_factory.mktemp("base"))


def _geometries(path):
    with ogr.Open(str(path)) as source:
        return [feature.GetGeometryRef().Clone() for feature in source.GetLayer()]


def _envelope(geometries):
    envelopes = np.array([geom.GetEnvelope() for geom in geometries])
    return (min(envelopes[:, 0]), max(envelopes[:, 1]), min(envelopes[:, 2]), max(envelopes[:, 3]))


def _assert_internal_coast(path):
    geometries = _geometries(path)
    west, east, south, north = _envelope(geometries)
    # Both halves of the true horizontal coast survive, with no line down a cut edge.
    assert (west, east, south, north) == pytest.approx(
        (_EXTENT.west, _EXTENT.east, 0.25, 0.25), abs=0.001
    )
    assert sum(g.Length() for g in geometries) == pytest.approx(0.25, abs=0.001)


def test_contour_skips_data_edge(built):
    _, work, _ = built
    _assert_internal_coast(work.base_dir / "wbm_coast_6.geojsonl")


def test_low_zoom_warp_preserves_small_window_and_data_extent(built):
    profile, work, _ = built
    with base._coarse_mask(work.path / "mask.vrt", profile.data_extent, 2) as ds:
        assert (ds.RasterXSize, ds.RasterYSize) == (64, 64)
    land = _geometries(work.base_dir / "wbm_land_0.geojsonl")
    assert _envelope(land) == pytest.approx((_EXTENT.west, _EXTENT.east, 0.25, 0.375), abs=0.0001)
    _assert_internal_coast(work.base_dir / "wbm_coast_0.geojsonl")


def test_polygonize_emits_only_land(built):
    _, work, _ = built
    land = _geometries(work.base_dir / "wbm_land_6.geojsonl")
    assert _envelope(land) == pytest.approx((_EXTENT.west, _EXTENT.east, 0.25, 0.375), abs=0.0003)
    assert sum(g.Area() for g in land) == pytest.approx(0.25 * 0.125, abs=0.0001)


def test_erase_leaves_overlap_ring(built):
    profile, work, _ = built
    (geom,) = _geometries(work.base_dir / "ne_110m_land_0.geojsonl")
    inner = shrink_extent(profile.data_extent, 2)
    for lon, expected in ((44, True), ((_EXTENT.west + inner.west) / 2, True), (_SEAM_LON, False)):
        point = ogr.CreateGeometryFromWkt(f"POINT ({lon} 0.25)")
        assert bool(geom.Contains(point)) == expected


def test_split_land_preserves_area_and_clips_to_cells():
    poly = ogr.CreateGeometryFromWkt("POLYGON ((-1 -2, 3 -2, 3 4, -1 4, -1 -2))")
    parts = list(base._split_land(poly))
    assert sorted(p.GetEnvelope() for p in parts) == [
        (-1, 0, -2, 0),
        (-1, 0, 0, 4),
        (0, 3, -2, 0),
        (0, 3, 0, 4),
    ]
    assert sum(p.Area() for p in parts) == pytest.approx(poly.Area())


def _show(archive):
    return json.loads(subprocess.check_output(["pmtiles", "show", archive, "--metadata"]))


def test_all_land_mask_skips_empty_wbm_coast_slices(tmp_path):
    profile, work, archive = _build(tmp_path, inland=True)
    base.verify_base(archive, profile)
    assert not list(work.base_dir.glob("wbm_coast_*.pmtiles"))
    assert {layer["id"] for layer in _show(archive)["vector_layers"]} == {"land", "coast"}


def test_build_base_layer_produces_verified_deterministic_archive(built, tmp_path):
    profile, _, archive = built
    base.verify_base(archive, profile)
    meta = _show(archive)
    assert meta == {
        "name": profile.name,
        "trial": True,
        "fingerprint": _PROVENANCE.fingerprint,
        "image": _PROVENANCE.image,
        "tool_commit": _PROVENANCE.tool_commit,
        "data_commit": _PROVENANCE.data_commit,
        "vector_layers": [
            {"id": layer, "fields": {}, "minzoom": 0, "maxzoom": 6} for layer in ("coast", "land")
        ],
    }
    # The synthetic Natural Earth coast lies at 1° N, outside the test window.
    # Decode its interior tile to confirm the archived WBM line survives quantisation.
    with gdal.OpenEx(str(archive), gdal.OF_VECTOR, open_options=["ZOOM_LEVEL=6"]) as ds:
        coast = ds.GetLayerByName("coast")
        geographic = osr.SpatialReference()
        geographic.ImportFromEPSG(4326)
        geographic.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
        project = osr.CoordinateTransformation(geographic, coast.GetSpatialRef())
        west, south, _ = project.TransformPoint(_SEAM_LON - 0.06, 0.23)
        east, north, _ = project.TransformPoint(_SEAM_LON + 0.06, 0.27)
        coast.SetSpatialFilterRect(west, south, east, north)
        assert sum(f.GetGeometryRef().Length() for f in coast) > 0
    _, _, other = _build(tmp_path)
    assert (
        hashlib.sha256(archive.read_bytes()).digest() == hashlib.sha256(other.read_bytes()).digest()
    )


@pytest.mark.parametrize("change", [{"vector_layers": [{"id": "land"}]}, {"trial": False}])
def test_verify_base_rejects_missing_layer_or_trial_flag(built, tmp_path, change):
    profile, _, archive = built
    altered = tmp_path / "altered.pmtiles"
    shutil.copyfile(archive, altered)
    metadata = tmp_path / "meta.json"
    metadata.write_text(json.dumps({**_show(archive), **change}))
    subprocess.run(["pmtiles", "edit", "--metadata", metadata, altered], check=True)
    with pytest.raises(base.BaseError):
        base.verify_base(altered, profile)


@pytest.mark.parametrize("remove_from_lock", [True, False], ids=["lock", "cache"])
def test_missing_natural_earth_names_source_and_preserves_previous_output(
    tmp_path, remove_from_lock
):
    profile, work, _ = _mask_inputs(tmp_path, False)
    cache = tmp_path / "cache"
    lock = _natural_earth(cache)
    missing = lock.natural_earth[0]
    if remove_from_lock:
        lock = replace(lock, natural_earth=lock.natural_earth[1:])
    else:
        (cache / missing.path).unlink()
    out = tmp_path / "out"
    out.mkdir()
    archive = out / base.BASE_ARCHIVE_NAME
    archive.write_bytes(b"previous archive")
    with pytest.raises(base.BaseError, match=Path(missing.path).name):
        base.build_base_layer(profile, lock, cache, work, out, _PROVENANCE, print)
    assert archive.read_bytes() == b"previous archive"


@pytest.mark.parametrize("location", ["absolute", "parent", "symlink"])
def test_natural_earth_lock_requires_cache_relative_files(tmp_path, location):
    cache = tmp_path / "cache"
    lock = _natural_earth(cache)
    ref = lock.natural_earth[0]
    name = Path(ref.path).name
    outside = tmp_path / "outside" / name
    outside.parent.mkdir()
    outside.write_bytes((cache / ref.path).read_bytes())
    if location == "absolute":
        entry = str(cache / ref.path)
    elif location == "parent":
        entry = str(Path("..") / "outside" / name)
    else:
        (cache / "alias").symlink_to(outside.parent, target_is_directory=True)
        entry = str(Path("alias") / name)
    lock = replace(lock, natural_earth=(replace(ref, path=entry), *lock.natural_earth[1:]))
    with pytest.raises(base.BaseError, match=name):
        base._natural_earth_sources(lock, cache)


def test_natural_earth_lock_rejects_duplicate_expected_filename(tmp_path):
    cache = tmp_path / "cache"
    lock = _natural_earth(cache)
    ref = lock.natural_earth[0]
    duplicate = cache / "copy" / Path(ref.path).name
    duplicate.parent.mkdir()
    shutil.copyfile(cache / ref.path, duplicate)
    lock = replace(
        lock,
        natural_earth=(*lock.natural_earth, replace(ref, path=str(duplicate.relative_to(cache)))),
    )
    with pytest.raises(base.BaseError, match="Duplicate"):
        base._natural_earth_sources(lock, cache)


def test_two_builders_sharing_work_area_publish_verified_archives(tmp_path, monkeypatch):
    profile, work, pieces = _mask_inputs(tmp_path, False)
    cache = tmp_path / "cache"
    lock = _natural_earth(cache)
    monkeypatch.setattr(base, "blocks", lambda extent, zoom: pieces)
    start = Barrier(2, timeout=20)

    def build(out):
        return base.build_base_layer(
            profile, lock, cache, work, tmp_path / out, _PROVENANCE, lambda message: start.wait()
        )

    with ThreadPoolExecutor(max_workers=2) as pool:
        first, second = pool.map(build, ("a", "b"))
    base.verify_base(first, profile)
    base.verify_base(second, profile)
    assert first.read_bytes() == second.read_bytes()


def test_retry_replaces_partial_archive_left_by_killed_publisher(built, tmp_path):
    profile, _, staged = built
    out = tmp_path / "out"
    out.mkdir()
    previous = out / base.BASE_ARCHIVE_NAME
    previous.write_bytes(b"previous archive")
    interrupt = """
import os, signal, sys
from pathlib import Path
from seerah_map import base

def stop_during_copy(source, target):
    target.write_bytes(b"incomplete archive")
    os.kill(os.getpid(), signal.SIGKILL)

base.shutil.copyfile = stop_during_copy
base._publish(Path(sys.argv[1]), Path(sys.argv[2]))
"""
    stopped = subprocess.run(
        [sys.executable, "-c", interrupt, staged, out], check=False, timeout=20
    )
    assert stopped.returncode == -signal.SIGKILL
    assert previous.read_bytes() == b"previous archive"
    (partial,) = out.glob("*.tmp")
    assert partial.read_bytes() == b"incomplete archive"
    archive = base._publish(staged, out)
    base.verify_base(archive, profile)
    assert archive.read_bytes() == staged.read_bytes()
    assert list(out.iterdir()) == [archive]


def test_separate_work_areas_publish_under_the_same_output_directory_lock(
    built, tmp_path, monkeypatch
):
    profile, _, archive = built
    out = tmp_path / "out"
    staged = []
    for fp in ("first", "second"):
        path = open_area(tmp_path / "work", fp).base_dir / base.BASE_ARCHIVE_NAME
        shutil.copyfile(archive, path)
        meta = path.parent / "metadata.json"
        meta.write_text(json.dumps({**_show(path), "fingerprint": fp}))
        subprocess.run(["pmtiles", "edit", "--metadata", meta, path], check=True)
        staged.append(path)

    copyfile = shutil.copyfile

    def check_locked_then_copy(source, target):
        # A separate descriptor cannot acquire the directory's lock during the copy.
        fd = os.open(out, os.O_RDONLY | os.O_DIRECTORY)
        try:
            with pytest.raises(BlockingIOError):
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        finally:
            os.close(fd)
        return copyfile(source, target)

    monkeypatch.setattr(base.shutil, "copyfile", check_locked_then_copy)
    start = Barrier(2, timeout=20)

    def publish(path):
        start.wait()
        return base._publish(path, out)

    with ThreadPoolExecutor(max_workers=2) as pool:
        first, second = pool.map(publish, staged)
    assert first == second == out / base.BASE_ARCHIVE_NAME
    base.verify_base(first, profile)
    assert first.read_bytes() in [path.read_bytes() for path in staged]
    assert list(out.iterdir()) == [first]


def test_base_production_metadata_and_verify_base(built, tmp_path):
    from seerah_map.config import BuildTarget
    from seerah_map.pack import Provenance

    profile, _, archive = built
    prod_archive = tmp_path / base.BASE_ARCHIVE_NAME
    shutil.copyfile(archive, prod_archive)
    with pytest.raises(base.BaseError, match="trial"):
        base.verify_base(prod_archive, profile, trial=False)

    target = BuildTarget(
        name="seerah-map",
        folder=profile.folder,
        purpose="production",
        camera_bounds=profile.camera_bounds,
        data_extent=profile.data_extent,
        margin_deg=profile.margin_deg,
        fade_deg=profile.fade_deg,
        max_zoom=profile.max_zoom,
        block_zoom=profile.block_zoom,
        webp_quality=profile.webp_quality,
        style=profile.style,
        peninsula_outline=profile.peninsula_outline,
        trial=False,
    )
    base._write_metadata(prod_archive, target, Provenance(fingerprint="prod-fp"))
    meta = _show(prod_archive)
    assert "trial" not in meta
    assert meta["tile_schema"] == 1
    assert meta["name"] == "seerah-map"
    base.verify_base(prod_archive, target, trial=False)
    with pytest.raises(base.BaseError, match="trial"):
        base.verify_base(prod_archive, target, trial=True)
