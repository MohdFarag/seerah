"""Tests for the command line: worker count, exit codes, build order and clean."""

from pathlib import Path
from types import SimpleNamespace

import pytest
from seerah_map import cli, report
from seerah_map.base import BaseError
from seerah_map.config import Cell, Lock, dump_lock, load_profile
from seerah_map.fetch import FetchError, cells_for
from seerah_map.pack import PackError
from seerah_map.tilestage import CompletenessError
from seerah_map.workarea import list_areas, open_area

_FIXTURES = Path(__file__).parent / "fixtures"
_EIGHT_GIB = "8589934592"
_PER_WORKER_MIB = 2048
_PROFILE = """\
purpose: trial
camera_bounds:
  west: 58.8
  south: 20.5
  east: 59.05
  north: 20.75
margin_deg: 0
fade_deg: 0.05
max_zoom: 8
block_zoom: 8
peninsula_outline: outline.geojson
"""


def test_worker_count_with_cap():
    count, _ = cli.worker_count(_EIGHT_GIB, _PER_WORKER_MIB, None)
    assert count == 4


def test_worker_count_without_cap():
    expected = (1, "no memory cap; using 1 worker")
    assert cli.worker_count("max", _PER_WORKER_MIB, None) == expected
    assert cli.worker_count(None, _PER_WORKER_MIB, None) == expected
    assert cli.worker_count("max", _PER_WORKER_MIB, 3)[0] == 3


def test_worker_count_rejects_a_malformed_cap():
    with pytest.raises(cli._UsageError, match="memory cap"):
        cli.worker_count("lots", _PER_WORKER_MIB, None)


def _roots(tmp_path: Path) -> list[str]:
    return [f"--{name}={tmp_path / name}" for name in ("data", "cache", "work", "out")]


def _data(tmp_path: Path, lock: bool = True, matching: bool = True) -> None:
    folder = tmp_path / "data" / "trial"
    folder.mkdir(parents=True)
    (folder / "t.yaml").write_text(_PROFILE)
    (folder / "edits.yaml").write_text((_FIXTURES / "edits_ok.yaml").read_text())
    if lock:
        profile = load_profile(folder / "t.yaml")
        cells = (
            tuple(Cell(lat, lon, True, None, None) for lat, lon in cells_for(profile.data_extent))
            if matching
            else ()
        )
        dump_lock(Lock("2026-11-16", "0" * 64, cells, ()), folder / "t.lock")


@pytest.fixture(autouse=True)
def _env(monkeypatch):
    for name in ("SEERAH_TOOL_COMMIT", "SEERAH_DATA_COMMIT", "SEERAH_IMAGE_DIGEST"):
        monkeypatch.setenv(name, "test")


def _build(tmp_path: Path) -> list[str]:
    return ["build", "--profile", "trial/t", "--variant", "painted", *_roots(tmp_path)]


def _fetch(tmp_path: Path) -> list[str]:
    return ["fetch", "--profile", "trial/t", *_roots(tmp_path)]


def _set_outline(tmp_path: Path, outline: str) -> Path:
    profile = tmp_path / "data" / "trial" / "t.yaml"
    text = profile.read_text(encoding="utf-8")
    updated = text.replace("peninsula_outline: outline.geojson", f"peninsula_outline: {outline}")
    assert updated != text
    profile.write_text(updated, encoding="utf-8")
    return profile


def _stub_fetch(monkeypatch, calls: list[Path]) -> None:
    def run(profile, lock_path, cache, out):
        calls.append(profile.peninsula_outline)
        return SimpleNamespace(downloaded=0, verified=0, message="stub fetch")

    monkeypatch.setattr(cli, "fetch", run)


def test_build_missing_lock_message(tmp_path, capsys):
    _data(tmp_path, lock=False)
    assert cli.main(_build(tmp_path)) == 2
    assert "run fetch first, then copy the lock beside the profile and commit it" in (
        capsys.readouterr().err
    )


def test_build_lock_mismatch(tmp_path):
    _data(tmp_path, matching=False)
    assert cli.main(_build(tmp_path)) == 1


def _stub_pipeline(
    monkeypatch,
    calls: list[str],
    fail_at: str | None = None,
    outlines: list[Path] | None = None,
    captured: dict[str, tuple] | None = None,
) -> None:
    def stage(name, result=None, error: type[Exception] = PackError):
        def run(*args, **kwargs):
            calls.append(name)
            if captured is not None:
                captured[name] = args
            if name == fail_at:
                raise error("stub failure")
            return result

        return run

    def record_size_by_zoom(tiles, outline):
        if outlines is not None:
            outlines.append(outline)
        return []

    monkeypatch.setattr(cli, "check_lock_matches", stage("check lock"))
    monkeypatch.setattr(cli, "run_block_stage", stage("block", []))
    monkeypatch.setattr(cli, "run_tile_stage", stage("tile", 0))
    monkeypatch.setattr(cli, "pack", stage("pack", Path("relief.pmtiles")))
    monkeypatch.setattr(cli, "verify", stage("verify"))
    monkeypatch.setattr(cli, "build_base_layer", stage("base", Path("base.pmtiles"), BaseError))
    monkeypatch.setattr(cli, "stored_tiles", lambda path: 0)
    monkeypatch.setattr(cli, "size_by_zoom", record_size_by_zoom)
    monkeypatch.setattr(cli, "write_report", stage("report", Path("report.json")))
    monkeypatch.setattr(cli, "remove", stage("remove"))


def test_exit_codes(tmp_path, monkeypatch):
    _data(tmp_path)
    for error in (FetchError, CompletenessError, PackError, BaseError):

        def fail(*args, error=error, **kwargs):
            raise error("stub")

        monkeypatch.setattr(cli, "run_block_stage", fail)
        assert cli.main(_build(tmp_path)) == 1
    (tmp_path / "data" / "trial" / "t.yaml").write_text("purpose: other\n")
    assert cli.main(_build(tmp_path)) == 2
    assert cli.main(["build", "--variant", "nope", *_roots(tmp_path)]) == 2
    assert cli.main(["clean", *_roots(tmp_path)]) == 0


def test_build_pipeline_order(tmp_path, monkeypatch, capsys):
    _data(tmp_path)
    calls: list[str] = []
    captured: dict[str, tuple] = {}
    _stub_pipeline(monkeypatch, calls, captured=captured)
    assert cli.main(_build(tmp_path)) == 0
    assert calls == ["check lock", "block", "tile", "pack", "verify", "base", "report", "remove"]
    out = capsys.readouterr().out
    assert "build: t painted fingerprint " in out
    assert "base done: " in out
    assert "work area removed" in out

    _, _, cache, area, out_dir, provenance, _log = captured["base"]
    assert cache == tmp_path / "cache"
    assert area.path.parent == tmp_path / "work"
    assert out_dir.parent == tmp_path / "out"
    assert provenance.fingerprint == area.fp

    report_data, report_out_dir = captured["report"]
    assert report_out_dir == out_dir
    assert "base" in report_data["stage_seconds"]
    report._check_shape(report_data)


@pytest.mark.parametrize("fail_at", ["verify", "base"])
def test_failed_stage_keeps_work_area(tmp_path, monkeypatch, fail_at):
    _data(tmp_path)
    calls: list[str] = []
    _stub_pipeline(monkeypatch, calls, fail_at=fail_at)
    assert cli.main(_build(tmp_path)) == 1
    assert "remove" not in calls
    assert calls[-1] == fail_at
    assert len(list_areas(tmp_path / "work")) == 1


def test_clean_semantics(tmp_path, capsys):
    work = tmp_path / "work"
    for fp in ("a" * 64, "b" * 64, "c" * 64):
        open_area(work, fp)
    assert cli.main(["clean", *_roots(tmp_path)]) == 0
    assert len(list_areas(work)) == 3
    assert "a" * 64 in capsys.readouterr().out
    assert cli.main(["clean", "--fingerprint", "a" * 64, *_roots(tmp_path)]) == 0
    assert [fp for fp, _ in list_areas(work)] == ["b" * 64, "c" * 64]
    assert cli.main(["clean", "--all", *_roots(tmp_path)]) == 0
    assert list_areas(work) == []


@pytest.mark.parametrize("outline", ["../../outside.geojson", "/outside.geojson"])
def test_escaping_outline_fails_before_fetch_or_build(tmp_path, monkeypatch, capsys, outline):
    _data(tmp_path)
    profile_path = _set_outline(tmp_path, outline)
    fetch_calls: list[Path] = []
    build_calls: list[str] = []
    _stub_fetch(monkeypatch, fetch_calls)
    _stub_pipeline(monkeypatch, build_calls)

    assert cli.main(_fetch(tmp_path)) == 2
    fetch_err = capsys.readouterr().err
    assert str(profile_path) in fetch_err
    assert "peninsula_outline" in fetch_err

    assert cli.main(_build(tmp_path)) == 2
    build_err = capsys.readouterr().err
    assert str(profile_path) in build_err
    assert "peninsula_outline" in build_err

    assert fetch_calls == []
    assert build_calls == []
    assert list_areas(tmp_path / "work") == []


def test_sibling_outline_inside_data_root_runs_fetch_and_build(tmp_path, monkeypatch):
    _data(tmp_path)
    shared = tmp_path / "data" / "shared"
    shared.mkdir()
    outline = shared / "outline.geojson"
    outline.write_text("{}", encoding="utf-8")
    _set_outline(tmp_path, "../shared/outline.geojson")

    fetch_calls: list[Path] = []
    build_calls: list[str] = []
    build_outlines: list[Path] = []
    _stub_fetch(monkeypatch, fetch_calls)
    _stub_pipeline(monkeypatch, build_calls, outlines=build_outlines)

    assert cli.main(_fetch(tmp_path)) == 0
    assert fetch_calls == [outline.resolve()]

    assert cli.main(_build(tmp_path)) == 0
    assert build_calls == [
        "check lock",
        "block",
        "tile",
        "pack",
        "verify",
        "base",
        "report",
        "remove",
    ]
    assert build_outlines == [outline.resolve()]
