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
    captured_kwargs: dict[str, dict] | None = None,
) -> None:
    def stage(name, result=None, error: type[Exception] = PackError):
        def run(*args, **kwargs):
            calls.append(name)
            if captured is not None:
                captured[name] = args
            if captured_kwargs is not None:
                captured_kwargs[name] = kwargs
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
    assert cli.main(["build", "--variant", "painted", "--mode", "nope", *_roots(tmp_path)]) == 2
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


def _wrap_mode_call(monkeypatch, attr: str, seen: dict[str, str | None]) -> None:
    original = getattr(cli, attr)

    def wrapped(*args, **kwargs):
        seen[attr] = kwargs.get("mode")
        return original(*args, **kwargs)

    monkeypatch.setattr(cli, attr, wrapped)


@pytest.mark.parametrize(
    ("extra_args", "expected_mode", "expected_prefix"),
    [
        ([], "pyramid", "t-painted-"),
        (["--mode", "per-zoom"], "per-zoom", "t-painted-per-zoom-"),
    ],
)
def test_build_mode_wiring_and_output_folder(
    tmp_path, monkeypatch, capsys, extra_args, expected_mode, expected_prefix
):
    _data(tmp_path)
    calls: list[str] = []
    captured: dict[str, tuple] = {}
    captured_kwargs: dict[str, dict] = {}
    seen_modes: dict[str, str | None] = {}
    _stub_pipeline(monkeypatch, calls, captured=captured, captured_kwargs=captured_kwargs)
    _wrap_mode_call(monkeypatch, "fingerprint", seen_modes)
    _wrap_mode_call(monkeypatch, "metadata", seen_modes)

    assert cli.main([*_build(tmp_path), *extra_args]) == 0
    out = capsys.readouterr().out
    assert f"({expected_mode})" in out

    _, _, _, area, out_dir, _, _ = captured["base"]
    assert out_dir.name == f"{expected_prefix}{area.fp[:8]}"
    assert seen_modes["fingerprint"] == expected_mode
    assert seen_modes["metadata"] == expected_mode
    assert captured_kwargs["block"].get("mode") == expected_mode
    assert captured_kwargs["tile"].get("mode") == expected_mode


def test_build_rejects_value_with_per_zoom(tmp_path, monkeypatch, capsys):
    _data(tmp_path)
    calls: list[str] = []
    _stub_pipeline(monkeypatch, calls)

    code = cli.main(
        [
            "build",
            "--profile",
            "trial/t",
            "--variant",
            "value",
            "--mode",
            "per-zoom",
            *_roots(tmp_path),
        ]
    )
    assert code == cli.EXIT_USAGE
    err = capsys.readouterr().err
    assert "--mode per-zoom" in err
    assert "painted" in err
    assert calls == []
    assert list_areas(tmp_path / "work") == []


def _coverage_data(tmp_path: Path, *, margin: float = 0.05) -> Path:
    import json
    import shutil

    repo_root = Path(__file__).resolve().parents[3]
    cov_dir = tmp_path / "data" / "prod"
    cov_dir.mkdir(parents=True, exist_ok=True)
    outline = cov_dir / "peninsula.geojson"
    shutil.copyfile(repo_root / "map-data" / "example" / "peninsula.geojson", outline)
    shutil.copyfile(
        repo_root / "map-data" / "example-production" / "cleanup.yaml",
        cov_dir / "cleanup.yaml",
    )
    shutil.copyfile(
        repo_root / "map-data" / "example-production" / "sources.lock",
        cov_dir / "sources.lock",
    )
    cam = (
        {"west": 58.85, "south": 20.55, "east": 59.00, "north": 20.70}
        if margin > 0
        else {"west": 58.80, "south": 20.50, "east": 59.05, "north": 20.75}
    )
    cov_dict = {
        "schema": 1,
        "purpose": "production",
        "camera_bounds": cam,
        "data_extent": {"west": 58.80, "south": 20.50, "east": 59.05, "north": 20.75},
        "camera_min_zoom": 4.0,
        "camera_max_zoom": 14.0,
        "regional_max_zoom": 12,
        "block_zoom": 11,
        "fade_deg": 0.0,
        "relief_encoding": "painted-rgba",
        "webp_quality": 80,
        "widest_half_screen_dp": 550.0,
        "min_data_margin_deg": margin,
        "peninsula_outline": "peninsula.geojson",
    }
    (cov_dir / "coverage.yaml").write_text(json.dumps(cov_dict, indent=2), encoding="utf-8")
    return cov_dir


def test_validate_coverage_command(tmp_path, capsys):
    _coverage_data(tmp_path, margin=0.0)
    code = cli.main(["validate", "--coverage", "prod", *_roots(tmp_path)])
    assert code == cli.EXIT_USAGE
    assert "--allow-zero-margin" in capsys.readouterr().err

    code = cli.main(["validate", "--coverage", "prod", "--allow-zero-margin", *_roots(tmp_path)])
    assert code == 0
    assert "validate: coverage.yaml ok" in capsys.readouterr().out

    (tmp_path / "data" / "prod" / "sources.lock").unlink()
    code = cli.main(["validate", "--coverage", "prod", "--allow-zero-margin", *_roots(tmp_path)])
    assert code == cli.EXIT_USAGE
    assert "sources.lock" in capsys.readouterr().err


def test_fetch_coverage_command(tmp_path, monkeypatch):
    _coverage_data(tmp_path, margin=0.05)
    seen: list[tuple] = []

    def fake_fetch(target, lock_path, cache, out, **kwargs):
        seen.append((lock_path.name, kwargs.get("lock_name"), kwargs.get("lock_destination")))
        return SimpleNamespace(downloaded=0, verified=1, message="stub fetch")

    monkeypatch.setattr(cli, "fetch", fake_fetch)
    assert cli.main(["fetch", "--coverage", "prod", *_roots(tmp_path)]) == 0
    assert seen == [("sources.lock", "sources.lock", "seerah-content/map/")]


def test_build_coverage_rejects_incompatible_flags(tmp_path, capsys):
    _coverage_data(tmp_path, margin=0.05)
    _data(tmp_path)

    for bad_args in [
        ["build", "--coverage", "prod", "--variant", "painted", *_roots(tmp_path)],
        ["build", "--coverage", "prod", "--mode", "per-zoom", *_roots(tmp_path)],
        [
            "build",
            "--profile",
            "trial/t",
            "--variant",
            "painted",
            "--allow-zero-margin",
            *_roots(tmp_path),
        ],
    ]:
        assert cli.main(bad_args) == cli.EXIT_USAGE
        assert capsys.readouterr().err != ""


def test_build_coverage_stages_atomic_bundle_and_checks_cache_and_size(
    tmp_path, monkeypatch, capsys
):
    import json
    import shutil

    from seerah_map import fetch

    _coverage_data(tmp_path, margin=0.05)
    calls: list[str] = []

    monkeypatch.setattr(
        cli,
        "verify_cached_sources",
        lambda lock, target, cache: calls.append("verify_cache"),
    )
    monkeypatch.setattr(
        cli,
        "run_block_stage",
        lambda *a, **kw: (calls.append("block"), [])[1],
    )
    monkeypatch.setattr(
        cli,
        "run_tile_stage",
        lambda *a, **kw: (calls.append("tile"), 1)[1],
    )

    def fake_pack(area, out_dir, meta, *, write_metadata_file=True):
        calls.append("pack")
        assert "trial" not in meta
        assert meta["tile_schema"] == 1
        assert write_metadata_file is False
        out_dir.mkdir(parents=True, exist_ok=True)
        pmtiles = out_dir / "relief.pmtiles"
        pmtiles.write_bytes(b"relief-bytes")
        return pmtiles

    monkeypatch.setattr(cli, "pack", fake_pack)
    monkeypatch.setattr(cli, "verify", lambda *a, **kw: calls.append("verify"))
    monkeypatch.setattr(cli, "stored_tiles", lambda path: 1)
    monkeypatch.setattr(cli, "size_by_zoom", lambda tiles, outline: [])

    def fake_base(target, lock, cache, area, out_dir, provenance, log):
        calls.append("base")
        assert target.trial is False
        out_dir.mkdir(parents=True, exist_ok=True)
        base_pm = out_dir / "base.pmtiles"
        base_pm.write_bytes(b"base-bytes")
        return base_pm

    monkeypatch.setattr(cli, "build_base_layer", fake_base)

    assert cli.main(["build", "--coverage", "prod", *_roots(tmp_path)]) == 0
    assert calls == ["verify_cache", "block", "tile", "pack", "verify", "base"]

    staged_dirs = list((tmp_path / "out").iterdir())
    assert len(staged_dirs) == 1
    staged = staged_dirs[0]
    assert staged.name.startswith("map-staging-")
    assert (staged / "data" / "base.pmtiles").exists()
    assert (staged / "data" / "relief.pmtiles").exists()
    assert (staged / "data" / "map.json").exists()
    assert not (staged / "metadata.json").exists()
    assert not (staged / "data" / "metadata.json").exists()
    rep = json.loads((staged / "report.json").read_text(encoding="utf-8"))
    assert rep["purpose"] == "production"
    assert rep["edits"] == {
        "applied": ["remove-synthetic-pier"],
        "skipped": ["draft-synthetic-basin"],
    }

    # Cache verification failure aborts before block stage.
    calls.clear()
    monkeypatch.setattr(
        cli,
        "verify_cached_sources",
        lambda *a, **kw: (_ for _ in ()).throw(fetch.FetchError("bad sha256")),
    )
    assert cli.main(["build", "--coverage", "prod", *_roots(tmp_path)]) == 1
    assert calls == []

    # Release size failure cleans up .tmp-map-staging-* and exits with 1.
    monkeypatch.setattr(cli, "verify_cached_sources", lambda *a, **kw: None)
    monkeypatch.setattr(cli, "MAX_RELEASE_FILE_BYTES", 10)
    monkeypatch.setattr(cli, "ZIP_BUDGET_OVERHEAD", 2)
    shutil.rmtree(staged)
    assert cli.main(["build", "--coverage", "prod", *_roots(tmp_path)]) == 1
    assert "MAX_RELEASE_FILE_BYTES" in capsys.readouterr().err
    assert list((tmp_path / "out").iterdir()) == []
