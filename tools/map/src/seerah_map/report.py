"""The run report: what a build did, how long it took and what it cost.

``report.json`` records provenance, stage times, memory, disk, tile counts,
size by zoom, applied and skipped edits, and the worker settings. Times are in
seconds, memory in bytes (except ``largest_child_rss_kib``, in KiB).
"""

import json
from pathlib import Path

from .workarea import atomic_write

REPORT_NAME = "report.json"
NO_PEAK_REASON = "memory.peak not available (cgroup v1 or no cap)"

_CGROUP_V2_ROOT = Path("/sys/fs/cgroup")
_OOM_KILL_KEY = "oom_kill"
_SHAPE: dict[str, set[str] | None] = {
    "provenance": {"tool_commit", "data_commit", "image", "fingerprint"},
    "stage_seconds": {"block", "tile", "pack", "base"},
    "memory": {"peak_bytes", "peak_reason", "oom_kills", "largest_child_rss_kib"},
    "disk": {"work_area_bytes_after_tiling"},
    "tiles": {"expected", "written", "stored"},
    "size_by_zoom": None,
    "edits": {"applied", "skipped"},
    "workers": {"count", "reason", "gdal_cachemax_mib"},
}


def read_cgroup(root: Path = _CGROUP_V2_ROOT) -> dict:
    """Read the container's peak memory and OOM kills from cgroup v2.

    Args:
        root: the cgroup directory to read.

    Returns:
        ``{peak_bytes, peak_reason, oom_kills}``. ``peak_bytes`` comes from
        ``memory.peak`` and includes page cache; ``oom_kills`` is the
        ``oom_kill`` count in ``memory.events``. When ``memory.peak`` is
        missing (cgroup v1 or no cap) ``peak_bytes`` is ``None`` and
        ``peak_reason`` says why. ``oom_kills`` is ``None`` when
        ``memory.events`` is missing.
    """
    peak_file = root / "memory.peak"
    events_file = root / "memory.events"
    peak = int(peak_file.read_text()) if peak_file.is_file() else None
    oom_kills = None
    if events_file.is_file():
        events = dict(line.split() for line in events_file.read_text().splitlines() if line.strip())
        oom_kills = int(events.get(_OOM_KILL_KEY, 0))
    return {
        "peak_bytes": peak,
        "peak_reason": None if peak is not None else NO_PEAK_REASON,
        "oom_kills": oom_kills,
    }


def write_report(report: dict, out_dir: Path) -> Path:
    """Write ``report.json``.

    Args:
        report: the report; its keys must be exactly the documented ones.
        out_dir: the output directory.

    Returns:
        The path of ``report.json``.

    Raises:
        ValueError: if a section is missing or has other keys than documented.
    """
    _check_shape(report)
    path = out_dir / REPORT_NAME
    with atomic_write(path) as tmp:
        tmp.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return path


def _check_shape(report: dict) -> None:
    if set(report) != set(_SHAPE):
        raise ValueError(f"report keys {sorted(report)} differ from {sorted(_SHAPE)}")
    for section, keys in _SHAPE.items():
        if keys is not None and set(report[section]) != keys:
            found = sorted(report[section])
            raise ValueError(f"report section {section!r} keys {found} differ from {sorted(keys)}")
