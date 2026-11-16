"""Tests for the run report: cgroup readings and the written JSON."""

import json
from pathlib import Path

import pytest
from seerah_map import report

_NO_PEAK = "memory.peak not available (cgroup v1 or no cap)"


def test_report_reads_cgroup_v2(tmp_path):
    (tmp_path / "memory.peak").write_text("123456\n")
    (tmp_path / "memory.events").write_text("low 0\nhigh 0\nmax 4\noom 2\noom_kill 1\n")
    assert report.read_cgroup(tmp_path) == {
        "peak_bytes": 123456,
        "peak_reason": None,
        "oom_kills": 1,
    }


def test_report_without_cgroup_v2(tmp_path):
    assert report.read_cgroup(tmp_path) == {
        "peak_bytes": None,
        "peak_reason": _NO_PEAK,
        "oom_kills": None,
    }


def _report() -> dict:
    return {
        "provenance": {"tool_commit": "a", "data_commit": "b", "image": "c", "fingerprint": "d"},
        "stage_seconds": {"block": 1.5, "tile": 2.0, "pack": 0.5},
        "memory": {
            "peak_bytes": None,
            "peak_reason": _NO_PEAK,
            "oom_kills": None,
            "largest_child_rss_kib": 1024,
        },
        "disk": {"work_area_bytes_after_tiling": 10},
        "tiles": {"expected": 3, "written": 4, "stored": 3},
        "size_by_zoom": [{"zoom": 0, "inside_bytes": 1, "outside_bytes": 2}],
        "edits": {"applied": ["e1"], "skipped": []},
        "workers": {"count": 2, "reason": "memory", "gdal_cachemax_mib": 256},
    }


def test_report_keys(tmp_path: Path):
    path = report.write_report(_report(), tmp_path)
    assert path == tmp_path / "report.json"
    written = json.loads(path.read_text())
    assert set(written) == {
        "provenance",
        "stage_seconds",
        "memory",
        "disk",
        "tiles",
        "size_by_zoom",
        "edits",
        "workers",
    }
    assert set(written["memory"]) == {
        "peak_bytes",
        "peak_reason",
        "oom_kills",
        "largest_child_rss_kib",
    }
    assert set(written["tiles"]) == {"expected", "written", "stored"}
    assert set(written["edits"]) == {"applied", "skipped"}
    assert set(written["workers"]) == {"count", "reason", "gdal_cachemax_mib"}
    assert set(written["stage_seconds"]) == {"block", "tile", "pack"}
    assert set(written["provenance"]) == {"tool_commit", "data_commit", "image", "fingerprint"}
    assert set(written["disk"]) == {"work_area_bytes_after_tiling"}


def test_write_report_rejects_other_keys(tmp_path: Path):
    bad = _report()
    bad["tiles"]["extra"] = 1
    with pytest.raises(ValueError, match="tiles"):
        report.write_report(bad, tmp_path)
