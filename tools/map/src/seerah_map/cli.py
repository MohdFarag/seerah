"""The map tool's command line: ``fetch``, ``validate``, ``build`` and ``clean``.

Profiles (``--profile``) and production coverage configs (``--coverage``) are
named by their path relative to the data root, without ``.yaml`` (for example
``trial/full`` or ``coverage``).

Exit codes: 0 for success, 1 when a check fails (``FetchError``,
``CompletenessError``, ``PackError``, ``BaseError``), 2 for a usage or
configuration error.
"""

import argparse
import json
import os
import resource
import shutil
import sys
import time
from collections.abc import Callable
from pathlib import Path

from . import config
from .base import BaseError, build_base_layer
from .blockstage import (
    MODES,
    PAINTED,
    PER_ZOOM,
    PYRAMID,
    VARIANTS,
    changed_cleanup_ids,
    run_block_stage,
)
from .edits import applicable, validate_cleanups
from .errors import ConfigError
from .fetch import FetchError, check_lock_matches, fetch, verify_cached_sources
from .grid import blocks, expected_tiles
from .pack import (
    METADATA_NAME,
    PackError,
    Provenance,
    metadata,
    pack,
    size_by_zoom,
    stored_tiles,
    validate_outline,
    verify,
)
from .report import read_cgroup, write_report
from .tilestage import CompletenessError, run_tile_stage
from .workarea import WorkArea, fingerprint, list_areas, open_area, remove

DATA_ROOT = Path("/data")
CACHE_ROOT = Path("/cache")
WORK_ROOT = Path("/work")
OUT_ROOT = Path("/out")
SRC_ROOT = Path(__file__).resolve().parent.parent
MEMORY_MAX = Path("/sys/fs/cgroup/memory.max")
DEFAULT_PER_WORKER_MIB = 2048
EDITS_NAME = "edits.yaml"
CLEANUP_NAME = "cleanup.yaml"
SOURCES_LOCK_NAME = "sources.lock"
MAP_JSON_NAME = "map.json"
PRODUCTION_LOCK_DEST = "seerah-content/map/"
MAX_RELEASE_FILE_BYTES = 2_147_483_647
ZIP_BUDGET_OVERHEAD = 1_048_576
FP_SHORT = 8

TOOL_COMMIT_ENV = "SEERAH_TOOL_COMMIT"
DATA_COMMIT_ENV = "SEERAH_DATA_COMMIT"
IMAGE_DIGEST_ENV = "SEERAH_IMAGE_DIGEST"
GDAL_CACHEMAX_ENV = "GDAL_CACHEMAX"

EXIT_OK = 0
EXIT_CHECK_FAILED = 1
EXIT_USAGE = 2

_MIB = 1 << 20
_NO_CAP = "max"
_UNKNOWN = "unknown"
_MISSING_LOCK = (
    "no lock at {path}: run fetch first, then copy the lock beside the profile and commit it"
)


class _UsageError(Exception):
    """The command line or the data folder cannot be used as given."""


def worker_count(
    memory_max: str | None, per_worker_mib: int, workers: int | None
) -> tuple[int, str]:
    """Choose the number of worker processes.

    Args:
        memory_max: the text of cgroup v2 ``memory.max`` (bytes or ``max``);
            ``None`` when the file is missing (cgroup v1), treated as no cap.
        per_worker_mib: the memory one worker may use, in MiB; it includes
            ``GDAL_CACHEMAX``.
        workers: an explicit count from ``--workers``, which always wins.

    Returns:
        The count (at least 1) and the reason, for the ``workers:`` line.

    Raises:
        _UsageError: if ``memory_max`` is neither a byte count nor ``max``.
    """
    if workers is not None:
        return workers, "set by --workers"
    if memory_max is None or memory_max.strip() == _NO_CAP:
        return 1, "no memory cap; using 1 worker"
    try:
        cap_bytes = int(memory_max)
    except ValueError:
        raise _UsageError(f"cannot read the memory cap {memory_max!r}; pass --workers") from None
    cap_mib = cap_bytes // _MIB
    count = max(1, cap_mib // per_worker_mib)
    return count, f"memory cap {cap_mib} MiB / {per_worker_mib} MiB per worker"


def _read_memory_max() -> str | None:
    return MEMORY_MAX.read_text().strip() if MEMORY_MAX.is_file() else None


def _say(line: str) -> None:
    print(line, flush=True)


def _warn(line: str) -> None:
    print(f"warning: {line}", file=sys.stderr, flush=True)


def _env(name: str) -> str | None:
    value = os.environ.get(name)
    if not value:
        _warn(f"{name} is not set; it is filled in from the running tool")
        return None
    return value


def _profile_path(data: Path, rel: str) -> Path:
    path = data / f"{rel}.yaml"
    if not path.is_file():
        raise _UsageError(f"no profile at {path}")
    return path


def _coverage_path(data: Path, rel: str) -> Path:
    for candidate in (data / f"{rel}.yaml", data / rel, data / rel / "coverage.yaml"):
        if candidate.is_file():
            return candidate
    raise _UsageError(f"no coverage file at {data / f'{rel}.yaml'}")


def _cmd_fetch(args: argparse.Namespace) -> int:
    if args.coverage is not None:
        coverage_path = _coverage_path(args.data, args.coverage)
        coverage = config.load_coverage(
            coverage_path,
            data_root=args.data,
            allow_zero_margin=args.allow_zero_margin,
        )
        lock_path = coverage_path.parent / SOURCES_LOCK_NAME
        result = fetch(
            coverage,
            lock_path,
            args.cache,
            args.out,
            lock_name=SOURCES_LOCK_NAME,
            lock_destination=PRODUCTION_LOCK_DEST,
        )
        _say(f"fetch: {result.downloaded} downloaded, {result.verified} verified")
        _say(result.message)
        return EXIT_OK
    if args.allow_zero_margin:
        raise _UsageError("--allow-zero-margin is only supported with --coverage")
    profile_path = _profile_path(args.data, args.profile)
    profile = config.load_profile(profile_path, data_root=args.data)
    lock_path = profile_path.with_suffix(".lock")
    result = fetch(profile, lock_path, args.cache, args.out)
    _say(f"fetch: {result.downloaded} downloaded, {result.verified} verified")
    _say(result.message)
    return EXIT_OK


def _cmd_validate(args: argparse.Namespace) -> int:
    coverage_path = _coverage_path(args.data, args.coverage)
    cleanup_path = coverage_path.parent / CLEANUP_NAME
    lock_path = coverage_path.parent / SOURCES_LOCK_NAME
    if not cleanup_path.is_file():
        raise _UsageError(f"no cleanup file at {cleanup_path}")
    if not lock_path.is_file():
        raise _UsageError(_MISSING_LOCK.format(path=lock_path))
    coverage = config.load_coverage(
        coverage_path,
        data_root=args.data,
        allow_zero_margin=args.allow_zero_margin,
    )
    cleanups = config.load_cleanup(cleanup_path)
    lock = config.load_lock(lock_path)
    try:
        check_lock_matches(lock, coverage)
    except FetchError as error:
        raise ConfigError(f"{lock_path}: {error}") from error
    validate_cleanups(cleanups, data_extent=coverage.data_extent)
    if coverage.peninsula_outline is not None:
        validate_outline(coverage.peninsula_outline)
    config.build_map_json(coverage)
    _say(f"validate: {coverage_path.name} ok ({len(cleanups)} cleanup edits)")
    return EXIT_OK


def _timed(seconds: dict[str, float], stage: str, run: Callable[[], object]) -> object:
    start = time.monotonic()
    result = run()
    seconds[stage] = time.monotonic() - start
    return result


def _tiles_written(tiles_dir: Path) -> int:
    return sum(1 for p in tiles_dir.rglob("*") if p.is_file() and p.name != METADATA_NAME)


def _check_release_size(data_dir: Path) -> None:
    """Enforce the < 2 GiB (ZIP64_LIMIT) single-file release ceiling on staged data files."""
    files = [p for p in sorted(data_dir.iterdir()) if p.is_file()]
    total = 0
    for path in files:
        size = path.stat().st_size
        if size > MAX_RELEASE_FILE_BYTES:
            raise PackError(
                f"{path.name} is {size} bytes, exceeding MAX_RELEASE_FILE_BYTES "
                f"({MAX_RELEASE_FILE_BYTES})"
            )
        total += size
    if total + ZIP_BUDGET_OVERHEAD > MAX_RELEASE_FILE_BYTES:
        raise PackError(
            f"staged data total ({total} bytes) + ZIP_BUDGET_OVERHEAD ({ZIP_BUDGET_OVERHEAD} bytes)"
            f" exceeds MAX_RELEASE_FILE_BYTES ({MAX_RELEASE_FILE_BYTES})"
        )


def _check_cleanups_changed_pixels(
    target: config.BuildTarget, area: WorkArea, cleanups: list[config.CleanupEdit]
) -> list[str]:
    """Return the approved cleanup IDs in file order; refuse any that changed no pixel."""
    if not cleanups:
        return []
    changed = changed_cleanup_ids(blocks(target.data_extent, target.block_zoom), area)
    unchanged = [c.id for c in cleanups if c.id not in changed]
    if unchanged:
        raise ConfigError(
            f"approved cleanup edits changed no pixels (check coordinates and operation): "
            f"{', '.join(unchanged)}"
        )
    return [c.id for c in cleanups]


def _cmd_build_coverage(args: argparse.Namespace) -> int:
    if args.variant is not None:
        raise _UsageError(
            "--variant cannot be used with --coverage (relief_encoding is set in coverage.yaml)"
        )
    if args.mode is not None:
        raise _UsageError(
            "--mode cannot be used with --coverage (production builds always use pyramid)"
        )
    coverage_path = _coverage_path(args.data, args.coverage)
    cleanup_path = coverage_path.parent / CLEANUP_NAME
    lock_path = coverage_path.parent / SOURCES_LOCK_NAME
    if not cleanup_path.is_file():
        raise _UsageError(f"no cleanup file at {cleanup_path}")
    if not lock_path.is_file():
        raise _UsageError(_MISSING_LOCK.format(path=lock_path))

    coverage = config.load_coverage(
        coverage_path,
        data_root=args.data,
        allow_zero_margin=args.allow_zero_margin,
    )
    all_cleanups = config.load_cleanup(cleanup_path)
    lock = config.load_lock(lock_path)
    validate_cleanups(all_cleanups, data_extent=coverage.data_extent)
    if coverage.peninsula_outline is not None:
        validate_outline(coverage.peninsula_outline)
    verify_cached_sources(lock, coverage, args.cache)
    map_doc = config.build_map_json(coverage)

    tool_commit = _env(TOOL_COMMIT_ENV)
    data_commit = _env(DATA_COMMIT_ENV)
    image_digest = _env(IMAGE_DIGEST_ENV)
    outline_bytes = (
        coverage.peninsula_outline.read_bytes() if coverage.peninsula_outline is not None else b""
    )
    variant = coverage.variant
    fp = fingerprint(
        coverage_path.read_bytes(),
        cleanup_path.read_bytes(),
        lock_path.read_bytes(),
        tool_commit,
        image_digest,
        variant,
        SRC_ROOT,
        mode=PYRAMID,
        extra_parts=(
            b"production",
            (data_commit or _UNKNOWN).encode(),
            outline_bytes,
        ),
    )
    fp8 = fp[:FP_SHORT]
    _say(f"build: production {coverage.relief_encoding} fingerprint {fp8}")
    count, reason = worker_count(_read_memory_max(), args.per_worker_mib, args.workers)
    _say(f"workers: {count} ({reason})")
    cleanups, skipped = applicable(all_cleanups)
    for edit_id in skipped:
        _say(f"edit {edit_id} skipped: not approved")

    target = coverage.as_build_target()
    area = open_area(args.work, fp)
    args.out.mkdir(parents=True, exist_ok=True)
    final_staging = args.out / f"map-staging-{fp8}"
    tmp_staging = args.out / f".tmp-map-staging-{fp8}-{os.getpid()}"
    shutil.rmtree(tmp_staging, ignore_errors=True)
    tmp_data = tmp_staging / "data"
    tmp_data.mkdir(parents=True, exist_ok=True)

    seconds: dict[str, float] = {}
    try:
        _timed(
            seconds,
            "block",
            lambda: run_block_stage(
                target,
                [],
                variant,
                lock,
                args.cache,
                area,
                count,
                _say,
                mode=PYRAMID,
                cleanups=cleanups,
            ),
        )
        applied = _check_cleanups_changed_pixels(target, area, cleanups)
        child_rss_kib = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
        _timed(
            seconds,
            "tile",
            lambda: run_tile_stage(area, target, variant, count, _say, mode=PYRAMID),
        )
        area_bytes = dict(list_areas(args.work)).get(fp)

        provenance = Provenance(
            tool_commit=tool_commit or _UNKNOWN,
            data_commit=data_commit or _UNKNOWN,
            image=image_digest or _UNKNOWN,
            fingerprint=fp,
        )

        def pack_and_verify() -> Path:
            meta = metadata(target, variant, provenance, mode=PYRAMID, trial=False)
            archive = pack(area, tmp_data, meta, write_metadata_file=False)
            verify(archive, target, trial=False)
            return archive

        archive = _timed(seconds, "pack", pack_and_verify)
        _say(f"pack done: {archive}")
        base_archive = _timed(
            seconds,
            "base",
            lambda: build_base_layer(target, lock, args.cache, area, tmp_data, provenance, _say),
        )
        _say(f"base done: {base_archive}")

        map_text = json.dumps(map_doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
        (tmp_data / MAP_JSON_NAME).write_text(map_text, encoding="utf-8")
        _check_release_size(tmp_data)

        report = _report(area, target, provenance, seconds, child_rss_kib, area_bytes, archive)
        report["purpose"] = "production"
        report["edits"] = {"applied": applied, "skipped": skipped}
        report["workers"] = {
            "count": count,
            "reason": reason,
            "gdal_cachemax_mib": _gdal_cachemax_mib(),
        }
        write_report(report, tmp_staging)
        shutil.rmtree(final_staging, ignore_errors=True)
        os.replace(tmp_staging, final_staging)
    except BaseException:
        shutil.rmtree(tmp_staging, ignore_errors=True)
        raise

    remove(area)
    _say("work area removed")
    return EXIT_OK


def _cmd_build(args: argparse.Namespace) -> int:
    if args.coverage is not None:
        return _cmd_build_coverage(args)
    if args.allow_zero_margin:
        raise _UsageError("--allow-zero-margin is only supported with --coverage")
    if args.variant is None:
        raise _UsageError("--variant is required with --profile")
    mode = PYRAMID if args.mode is None else args.mode
    if mode == PER_ZOOM and args.variant != PAINTED:
        raise _UsageError("--mode per-zoom is only supported with --variant painted")
    profile_path = _profile_path(args.data, args.profile)
    edits_path = profile_path.parent / EDITS_NAME
    lock_path = profile_path.with_suffix(".lock")
    if not edits_path.is_file():
        raise _UsageError(f"no edits file at {edits_path}")
    if not lock_path.is_file():
        raise _UsageError(_MISSING_LOCK.format(path=lock_path))
    profile = config.load_profile(profile_path, data_root=args.data)
    all_edits = config.load_edits(edits_path)
    lock = config.load_lock(lock_path)

    tool_commit = _env(TOOL_COMMIT_ENV)
    data_commit = _env(DATA_COMMIT_ENV)
    image_digest = _env(IMAGE_DIGEST_ENV)
    fp = fingerprint(
        profile_path.read_bytes(),
        edits_path.read_bytes(),
        lock_path.read_bytes(),
        tool_commit,
        image_digest,
        args.variant,
        SRC_ROOT,
        mode=mode,
    )
    fp8 = fp[:FP_SHORT]
    _say(f"build: {profile.name} {args.variant} fingerprint {fp8} ({mode})")
    count, reason = worker_count(_read_memory_max(), args.per_worker_mib, args.workers)
    _say(f"workers: {count} ({reason})")
    edits, skipped = applicable(all_edits)
    for edit_id in skipped:
        _say(f"edit {edit_id} skipped: not approved")

    check_lock_matches(lock, profile)
    area = open_area(args.work, fp)
    out_dir = args.out / (
        f"{profile.name}-{args.variant}-{fp8}"
        if mode == PYRAMID
        else f"{profile.name}-{args.variant}-{mode}-{fp8}"
    )
    seconds: dict[str, float] = {}
    _timed(
        seconds,
        "block",
        lambda: run_block_stage(
            profile, edits, args.variant, lock, args.cache, area, count, _say, mode=mode
        ),
    )
    child_rss_kib = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
    _timed(
        seconds,
        "tile",
        lambda: run_tile_stage(area, profile, args.variant, count, _say, mode=mode),
    )
    area_bytes = dict(list_areas(args.work)).get(fp)

    provenance = Provenance(
        tool_commit=tool_commit or _UNKNOWN,
        data_commit=data_commit or _UNKNOWN,
        image=image_digest or _UNKNOWN,
        fingerprint=fp,
    )

    def pack_and_verify() -> Path:
        archive = pack(area, out_dir, metadata(profile, args.variant, provenance, mode=mode))
        verify(archive, profile)
        return archive

    archive = _timed(seconds, "pack", pack_and_verify)
    _say(f"pack done: {archive}")
    base_archive = _timed(
        seconds,
        "base",
        lambda: build_base_layer(profile, lock, args.cache, area, out_dir, provenance, _say),
    )
    _say(f"base done: {base_archive}")
    report = _report(area, profile, provenance, seconds, child_rss_kib, area_bytes, archive)
    report["edits"] = {"applied": [e.id for e in edits], "skipped": skipped}
    report["workers"] = {
        "count": count,
        "reason": reason,
        "gdal_cachemax_mib": _gdal_cachemax_mib(),
    }
    write_report(report, out_dir)
    remove(area)
    _say("work area removed")
    return EXIT_OK


def _report(
    area: WorkArea,
    profile: config.Profile | config.BuildTarget,
    provenance: Provenance,
    seconds: dict[str, float],
    child_rss_kib: int,
    area_bytes: int | None,
    archive: Path,
) -> dict:
    """Assemble the report sections that come from the build's measurements."""
    expected = expected_tiles(blocks(profile.data_extent, profile.block_zoom), profile.max_zoom)
    outline = profile.peninsula_outline
    return {
        "provenance": {
            "tool_commit": provenance.tool_commit,
            "data_commit": provenance.data_commit,
            "image": provenance.image,
            "fingerprint": provenance.fingerprint,
        },
        "stage_seconds": seconds,
        "memory": {**read_cgroup(), "largest_child_rss_kib": child_rss_kib},
        "disk": {"work_area_bytes_after_tiling": area_bytes},
        "tiles": {
            "expected": len(expected),
            "written": _tiles_written(area.tiles_dir),
            "stored": stored_tiles(archive),
        },
        "size_by_zoom": size_by_zoom(area.tiles_dir, outline) if outline is not None else None,
    }


def _gdal_cachemax_mib() -> int | None:
    """GDAL's block cache in MiB, as the image sets it; None when it is not set."""
    value = os.environ.get(GDAL_CACHEMAX_ENV)
    return int(value) if value else None


def _cmd_clean(args: argparse.Namespace) -> int:
    areas = list_areas(args.work)
    if args.all:
        for fp, _ in areas:
            remove(WorkArea(args.work, fp))
            _say(f"removed {fp}")
        return EXIT_OK
    if args.fingerprint is not None:
        if args.fingerprint not in dict(areas):
            raise _UsageError(f"no work area {args.fingerprint} under {args.work}")
        remove(WorkArea(args.work, args.fingerprint))
        _say(f"removed {args.fingerprint}")
        return EXIT_OK
    for fp, size in areas:
        _say(f"{fp} {size} bytes")
    return EXIT_OK


def _parser() -> argparse.ArgumentParser:
    roots = argparse.ArgumentParser(add_help=False)
    roots.add_argument("--data", type=Path, default=DATA_ROOT, help="read-only data root")
    roots.add_argument("--cache", type=Path, default=CACHE_ROOT, help="download cache root")
    roots.add_argument("--work", type=Path, default=WORK_ROOT, help="work-area root")
    roots.add_argument("--out", type=Path, default=OUT_ROOT, help="output root")

    parser = argparse.ArgumentParser(prog="seerah_map", description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    profile_help = "profile path relative to --data, without .yaml (e.g. trial/full)"
    coverage_help = "production coverage path relative to --data, without .yaml (e.g. coverage)"

    fetch_cmd = commands.add_parser("fetch", parents=[roots], help="download and pin sources")
    fetch_target = fetch_cmd.add_mutually_exclusive_group(required=True)
    fetch_target.add_argument("--profile", help=profile_help)
    fetch_target.add_argument("--coverage", help=coverage_help)
    fetch_cmd.add_argument(
        "--allow-zero-margin",
        action="store_true",
        help="allow min_data_margin_deg: 0 on --coverage fetches (for synthetic CI fixtures only)",
    )
    fetch_cmd.set_defaults(run=_cmd_fetch)

    validate_cmd = commands.add_parser(
        "validate",
        parents=[roots],
        help="validate production coverage, cleanup and lock files",
    )
    validate_cmd.add_argument("--coverage", required=True, help=coverage_help)
    validate_cmd.add_argument(
        "--allow-zero-margin",
        action="store_true",
        help="allow min_data_margin_deg: 0 (for synthetic CI fixtures only)",
    )
    validate_cmd.set_defaults(run=_cmd_validate)

    build_cmd = commands.add_parser(
        "build", parents=[roots], help="build the relief and base archives"
    )
    build_target = build_cmd.add_mutually_exclusive_group(required=True)
    build_target.add_argument("--profile", help=profile_help)
    build_target.add_argument("--coverage", help=coverage_help)
    build_cmd.add_argument("--variant", choices=VARIANTS, default=None)
    build_cmd.add_argument(
        "--mode",
        choices=MODES,
        default=None,
        help="how zooms below max_zoom are built (default pyramid; --profile only)",
    )
    build_cmd.add_argument(
        "--allow-zero-margin",
        action="store_true",
        help="allow min_data_margin_deg: 0 on --coverage builds (for synthetic CI fixtures only)",
    )
    build_cmd.add_argument(
        "--workers", type=_positive, help="worker processes (default: by memory)"
    )
    build_cmd.add_argument(
        "--per-worker-mib",
        type=_positive,
        default=DEFAULT_PER_WORKER_MIB,
        help=f"memory per worker in MiB, GDAL_CACHEMAX included (default {DEFAULT_PER_WORKER_MIB})",
    )
    build_cmd.set_defaults(run=_cmd_build)

    clean_cmd = commands.add_parser(
        "clean", parents=[roots], help="list work areas; remove one or all"
    )
    which = clean_cmd.add_mutually_exclusive_group()
    which.add_argument("--fingerprint", help="remove this work area")
    which.add_argument("--all", action="store_true", help="remove every work area")
    clean_cmd.set_defaults(run=_cmd_clean)
    return parser


def _positive(text: str) -> int:
    value = int(text)
    if value < 1:
        raise argparse.ArgumentTypeError(f"{text} is not a positive integer")
    return value


def main(argv: list[str] | None = None) -> int:
    """Run the command line.

    Args:
        argv: the arguments without the program name; ``None`` reads ``sys.argv``.

    Returns:
        The exit code: 0 success, 1 a failed check, 2 a usage or configuration error.
    """
    try:
        args = _parser().parse_args(argv)
    except SystemExit as exit_:
        return int(exit_.code or EXIT_OK)
    try:
        return args.run(args)
    except (FetchError, CompletenessError, PackError, BaseError) as error:
        print(f"error: {error}", file=sys.stderr, flush=True)
        return EXIT_CHECK_FAILED
    except (ConfigError, _UsageError) as error:
        print(f"error: {error}", file=sys.stderr, flush=True)
        return EXIT_USAGE
