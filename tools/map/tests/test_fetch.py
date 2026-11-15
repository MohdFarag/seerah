import hashlib
from pathlib import Path

import pytest
from seerah_map import config, fetch
from seerah_map.config import Bounds, Lock, Profile, StyleSettings

NE_FILE_COUNT = 6
PRESENT_CELLS = 2


def make_profile(folder: Path) -> Profile:
    """A profile whose extent covers cells N20 E058, E059 (listed) and E060 (not listed)."""
    return Profile(
        name="t",
        folder=folder,
        purpose="trial",
        camera_bounds=Bounds(58.2, 20.2, 60.5, 20.8),
        margin_deg=0,
        fade_deg=0,
        max_zoom=8,
        block_zoom=6,
        webp_quality=80,
        style=StyleSettings(),
        peninsula_outline=folder / "o.geojson",
    )


def run(tmp_path, server, lock_path=None):
    return fetch.fetch(
        make_profile(tmp_path / "data"),
        lock_path,
        tmp_path / "cache",
        tmp_path / "out",
        dem_base=str(server),
        ne_base=f"{server}/natural-earth/geojson",
    )


@pytest.fixture
def out(tmp_path):
    (tmp_path / "out").mkdir()
    return tmp_path / "out"


def test_cells_for_data_extent():
    assert len(fetch.cells_for(Bounds(33, 11, 61, 33))) == 616


def test_cell_name():
    assert fetch.cell_name(20, 58) == "Copernicus_DSM_COG_10_N20_00_E058_00_DEM"


def test_first_run_writes_lock_to_out(tmp_path, out, http_server):
    result = run(tmp_path, http_server)

    lock_file = out / "t.lock"
    assert result.wrote_new_lock
    assert config.load_lock(lock_file) == result.lock
    cells = {(c.lat, c.lon): c for c in result.lock.cells}
    assert [(c.lat, c.lon) for c in result.lock.cells] == [(20, 58), (20, 59), (20, 60)]
    assert cells[(20, 60)].absent
    assert cells[(20, 60)].dem is None
    dem = cells[(20, 58)].dem
    assert dem.size == len(b"dem N20_00_E058_00")
    assert dem.sha256 == hashlib.sha256(b"dem N20_00_E058_00").hexdigest()
    assert dem.path == (
        "copernicus/Copernicus_DSM_COG_10_N20_00_E058_00_DEM/"
        "Copernicus_DSM_COG_10_N20_00_E058_00_DEM.tif"
    )
    assert cells[(20, 58)].wbm.path == (
        "copernicus/Copernicus_DSM_COG_10_N20_00_E058_00_DEM/Copernicus_DSM_COG_10_N20_00_E058_00_WBM.tif"
    )
    assert "/AUXFILES/" in cells[(20, 58)].wbm.url
    assert len(result.lock.natural_earth) == NE_FILE_COUNT
    assert all(r.size > 0 and len(r.sha256) == 64 for r in result.lock.natural_earth)
    assert result.downloaded == PRESENT_CELLS * 2 + NE_FILE_COUNT
    for word in ("copy", "seerah-content/map/trial/", "commit"):
        assert word in result.message


def test_second_run_verifies(tmp_path, out, http_server):
    first = run(tmp_path, http_server)
    http_server.requests.clear()

    second = run(tmp_path, http_server, out / "t.lock")

    assert not second.wrote_new_lock
    assert second.downloaded == 0
    assert second.verified == PRESENT_CELLS * 2 + NE_FILE_COUNT
    assert second.lock == first.lock
    assert http_server.requests == []


def test_lock_profile_mismatch(tmp_path, out, http_server):
    lock = run(tmp_path, http_server).lock
    other = make_profile(tmp_path)
    other = Profile(**{**other.__dict__, "camera_bounds": Bounds(58.2, 20.2, 59.5, 20.8)})
    with pytest.raises(fetch.FetchError, match="the lock does not match the profile"):
        fetch.check_lock_matches(lock, other)
    fetch.check_lock_matches(lock, make_profile(tmp_path))


def test_lock_mismatch_stops_fetch(tmp_path, out, http_server):
    lock = run(tmp_path, http_server).lock
    config.dump_lock(Lock(lock.created, lock.tile_list_sha256, lock.cells[:2], ()), out / "x.lock")
    with pytest.raises(fetch.FetchError, match="does not match"):
        run(tmp_path, http_server, out / "x.lock")


def test_fetch_rejects_changed_file(tmp_path, out, http_server):
    first = run(tmp_path, http_server)
    ref = first.lock.cells[0].dem
    cached = tmp_path / "cache" / ref.path
    cached.write_bytes(b"tampered")
    new_hash = hashlib.sha256(b"tampered").hexdigest()

    with pytest.raises(fetch.FetchError) as info:
        run(tmp_path, http_server, out / "t.lock")

    message = str(info.value)
    assert ref.path in message
    assert ref.sha256 in message
    assert new_hash in message
    assert "delete the cached file and run fetch again" in message


def test_fetch_discards_partial(tmp_path, out, http_server):
    first = run(tmp_path, http_server)
    lock_bytes = (out / "t.lock").read_bytes()
    ref = first.lock.cells[0].wbm
    cached = tmp_path / "cache" / ref.path
    cached.unlink()
    part = cached.with_name(cached.name + ".part")
    part.write_bytes(b"half a fi")

    second = run(tmp_path, http_server, out / "t.lock")

    assert second.downloaded == 1
    assert not part.exists()
    assert hashlib.sha256(cached.read_bytes()).hexdigest() == ref.sha256
    assert (out / "t.lock").read_bytes() == lock_bytes
    assert second.lock == first.lock


def test_failed_download_raises_and_leaves_no_part(tmp_path, out, http_server):
    profile = make_profile(tmp_path / "data")
    with pytest.raises(fetch.FetchError, match="ne_110m_land"):
        fetch.fetch(
            profile,
            None,
            tmp_path / "cache",
            out,
            dem_base=str(http_server),
            ne_base=f"{http_server}/missing",
        )
    assert not list((tmp_path / "cache").rglob("*.part"))


def test_no_lock_written_into_data(tmp_path, out, http_server):
    (tmp_path / "data").mkdir()
    run(tmp_path, http_server)
    assert list((tmp_path / "data").iterdir()) == []
