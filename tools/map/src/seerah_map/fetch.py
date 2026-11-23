"""Download the pinned source files and write or check the download lock.

The first run downloads every Copernicus DSM cell the profile needs, plus six
Natural Earth files, hashes them and writes a lock. Later runs check the cache
against the lock and download only what is missing. Sizes are in bytes;
cell corners are whole degrees (EPSG:4326).
"""

import hashlib
import math
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import UTC, datetime
from http.client import HTTPException
from pathlib import Path

from . import config
from .config import Bounds, BuildTarget, Cell, Coverage, FileRef, Lock, Profile
from .workarea import atomic_write

DEM_BASE = "https://copernicus-dem-30m.s3.amazonaws.com"
TILE_LIST = f"{DEM_BASE}/tileList.txt"

# Commit of tag v5.1.2 in nvkelso/natural-earth-vector, resolved with
# `gh api repos/nvkelso/natural-earth-vector/git/refs/tags/v5.1.2` on 2026-11-16.
NATURAL_EARTH_COMMIT = "f1890d9f152c896d250a77557a5751a93d494776"
NE_BASE = (
    f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{NATURAL_EARTH_COMMIT}/geojson"
)
NE_FILES = tuple(
    f"ne_{scale}_{layer}.geojson"
    for scale in ("110m", "50m", "10m")
    for layer in ("land", "coastline")
)

_TIMEOUT_S = 60
_CHUNK_BYTES = 1 << 20
_LOCK_DESTINATION = "seerah-content/map/trial/"


class FetchError(Exception):
    """A download failed, or a cached file or the lock disagrees with the pins."""


@dataclass(frozen=True)
class FetchResult:
    """What a fetch run did.

    ``downloaded`` and ``verified`` count files (not the tile list): files
    downloaded this run, and files found in the cache and hashed.
    """

    lock: Lock
    wrote_new_lock: bool
    downloaded: int
    verified: int
    message: str


def cell_name(lat: int, lon: int) -> str:
    """Return the Copernicus DEM cell name for the cell with south-west corner (lat, lon).

    Args:
        lat: whole degrees north, 0 or more.
        lon: whole degrees east, 0 or more.

    Returns:
        For example ``Copernicus_DSM_COG_10_N20_00_E058_00_DEM``.
    """
    return f"Copernicus_DSM_COG_10_N{lat:02d}_00_E{lon:03d}_00_DEM"


def cells_for(extent: Bounds) -> list[tuple[int, int]]:
    """List every 1 degree cell that intersects the extent.

    Args:
        extent: a lon/lat box in degrees; an edge on a whole degree does not
            reach into the next cell.

    Returns:
        ``(lat, lon)`` south-west corners, sorted by latitude then longitude.
    """
    return [
        (lat, lon)
        for lat in range(math.floor(extent.south), math.ceil(extent.north))
        for lon in range(math.floor(extent.west), math.ceil(extent.east))
    ]


def check_lock_matches(lock: Lock, profile: Profile | Coverage | BuildTarget) -> None:
    """Raise FetchError unless the lock's cells are exactly the profile's cells."""
    if {(c.lat, c.lon) for c in lock.cells} != set(cells_for(profile.data_extent)):
        raise FetchError("the lock does not match the profile; move the lock aside and run fetch")


def verify_cached_sources(
    lock: Lock, profile: Profile | Coverage | BuildTarget, cache: Path
) -> None:
    """Verify every locked DEM, WBM and Natural Earth file is inside ``cache`` and matches SHA-256.

    Args:
        lock: the committed download lock.
        profile: the profile or coverage configuration.
        cache: the cache root directory.

    Raises:
        FetchError: if the lock cells disagree with ``profile.data_extent``, a
            required Natural Earth or non-absent cell ref is missing, a lock path
            escapes ``cache``, a file is missing from ``cache``, or a file's size
            or SHA-256 differs from ``lock``.
    """
    check_lock_matches(lock, profile)
    root = cache.resolve()
    refs: list[FileRef] = []
    for cell in lock.cells:
        if cell.absent:
            continue
        if cell.dem is None or cell.wbm is None:
            raise FetchError(f"cell ({cell.lat}, {cell.lon}) is present but missing dem/wbm ref")
        refs.extend((cell.dem, cell.wbm))

    ne_names = {Path(ref.path).name for ref in lock.natural_earth}
    missing_ne = [name for name in NE_FILES if name not in ne_names]
    if missing_ne:
        raise FetchError(f"Natural Earth file missing from lock: {missing_ne[0]}")
    if len(ne_names) != len(lock.natural_earth):
        raise FetchError("Duplicate Natural Earth file in lock")
    refs.extend(lock.natural_earth)

    for ref in refs:
        rel = Path(ref.path)
        if rel.is_absolute():
            raise FetchError(f"lock path must be relative to cache: {ref.path}")
        try:
            resolved = (root / rel).resolve()
        except (OSError, RuntimeError) as error:
            raise FetchError(f"cannot resolve cached path {ref.path}: {error}") from error
        if resolved == root or not resolved.is_relative_to(root):
            raise FetchError(f"lock path escapes cache: {ref.path}")
        if not resolved.is_file():
            raise FetchError(f"cached file missing: {ref.path}; run fetch first")
        size, sha = _sha256_file(resolved)
        if (size, sha) != (ref.size, ref.sha256):
            raise _mismatch(ref.path, ref, size, sha)


def _sha256_file(path: Path) -> tuple[int, str]:
    """Return the size in bytes and SHA-256 hex digest of a file."""
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        while chunk := handle.read(_CHUNK_BYTES):
            digest.update(chunk)
            size += len(chunk)
    return size, digest.hexdigest()


def _mismatch(rel_path: str, pinned: FileRef, size: int, sha: str) -> FetchError:
    """Build the error for a file whose size or SHA-256 differs from the lock."""
    return FetchError(
        f"{rel_path} does not match the lock: expected {pinned.size} bytes and sha256 "
        f"{pinned.sha256}, found {size} bytes and sha256 {sha}; "
        "delete the cached file and run fetch again"
    )


def _download(url: str, target: Path, rel_path: str, pinned: FileRef | None) -> None:
    """Download ``url`` to ``target``, checking it before it enters the cache.

    The body is written to a ``.part`` file and hashed as it arrives. Only a
    body whose length equals ``Content-Length`` (when sent) and whose size and
    SHA-256 equal ``pinned`` (when given) is renamed to ``target``; otherwise
    the ``.part`` file is deleted and FetchError is raised.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    digest = hashlib.sha256()
    size = 0
    try:
        with urllib.request.urlopen(url, timeout=_TIMEOUT_S) as response, part.open("wb") as out:
            expected = response.headers.get("Content-Length")
            while chunk := response.read(_CHUNK_BYTES):
                out.write(chunk)
                digest.update(chunk)
                size += len(chunk)
        if expected is not None and int(expected) != size:
            raise FetchError(f"{url} is truncated: expected {expected} bytes, got {size}")
        if pinned is not None and (size, digest.hexdigest()) != (pinned.size, pinned.sha256):
            raise _mismatch(rel_path, pinned, size, digest.hexdigest())
        os.replace(part, target)
    except (OSError, HTTPException, ValueError) as error:
        part.unlink(missing_ok=True)
        raise FetchError(f"could not download {url}: {error}") from error
    except FetchError:
        part.unlink(missing_ok=True)
        raise


def _read_url(url: str) -> bytes:
    """Return the body of ``url``, raising FetchError on failure."""
    try:
        with urllib.request.urlopen(url, timeout=_TIMEOUT_S) as response:
            return response.read()
    except (OSError, HTTPException) as error:
        raise FetchError(f"could not download {url}: {error}") from error


class _Counts:
    """Running totals of files downloaded and files hashed from the cache."""

    def __init__(self) -> None:
        self.downloaded = 0
        self.verified = 0


def _obtain(
    url: str, rel_path: str, cache: Path, pinned: FileRef | None, counts: _Counts
) -> FileRef:
    """Make sure one file is in the cache and return its reference.

    Args:
        url: where to download the file if it is not cached.
        rel_path: the file's path relative to the cache root.
        cache: the cache root.
        pinned: the lock's entry for this file, or None on a first run.
        counts: updated with what happened.

    Raises:
        FetchError: the download failed, or the file's hash differs from ``pinned``.
    """
    target = cache / rel_path
    if target.is_file():
        counts.verified += 1
    else:
        _download(url, target, rel_path, pinned)
        counts.downloaded += 1
    size, sha = _sha256_file(target)
    if pinned is not None and (size, sha) != (pinned.size, pinned.sha256):
        raise _mismatch(rel_path, pinned, size, sha)
    return FileRef(url=url, size=size, sha256=sha, path=rel_path)


def _pinned_refs(lock: Lock | None) -> dict[str, FileRef]:
    """Index the lock's file entries by cache path."""
    if lock is None:
        return {}
    refs = [r for c in lock.cells for r in (c.dem, c.wbm) if r is not None]
    return {r.path: r for r in (*refs, *lock.natural_earth)}


def _new_message(lock_file: Path, lock_destination: str = _LOCK_DESTINATION) -> str:
    """Tell the owner how to keep a new lock."""
    return (
        f"Wrote a new lock to {lock_file}. Review it, then copy it into the private "
        f"content repository and commit it there: "
        f"cp {lock_file} {lock_destination}{lock_file.name}"
    )


def fetch(
    profile: Profile | Coverage | BuildTarget,
    lock_path: Path | None,
    cache: Path,
    out: Path,
    dem_base: str = DEM_BASE,
    ne_base: str = NE_BASE,
    *,
    lock_name: str | None = None,
    lock_destination: str = _LOCK_DESTINATION,
) -> FetchResult:
    """Download and pin the profile's source files.

    Args:
        profile: decides the cells to fetch (``data_extent``) and default lock name.
        lock_path: an existing lock to verify against; None or a missing file
            means a first run.
        cache: the cache root; files go under ``copernicus/<cell>/`` and
            ``natural-earth/<commit>/``.
        out: where a new lock is written, as ``<profile name>.lock`` (or
            ``lock_name`` when specified). Nothing is ever written under the
            profile's own folder.
        dem_base: base URL of the Copernicus bucket.
        ne_base: base URL of the Natural Earth GeoJSON folder.
        lock_name: optional explicit output filename under ``out`` (e.g.
            ``"sources.lock"`` for production ``--coverage``).
        lock_destination: destination directory prefix printed in the ``cp``
            hint when a new lock is written.

    Returns:
        The lock in force and what the run did.

    Raises:
        FetchError: a download failed, a file differs from the lock, or the lock
            does not match the profile.
    """
    existing = config.load_lock(lock_path) if lock_path is not None and lock_path.exists() else None
    if existing is not None:
        check_lock_matches(existing, profile)
    pinned = _pinned_refs(existing)
    counts = _Counts()

    if existing is None:
        tile_list = _read_url(f"{dem_base}/tileList.txt")
        listed = set(tile_list.decode("utf-8").split())
        tile_list_sha = hashlib.sha256(tile_list).hexdigest()
    else:
        listed, tile_list_sha = None, existing.tile_list_sha256

    locked_absent = {(c.lat, c.lon): c.absent for c in existing.cells} if existing else {}
    cells = []
    for lat, lon in cells_for(profile.data_extent):
        name = cell_name(lat, lon)
        absent = name not in listed if listed is not None else locked_absent[lat, lon]
        if absent:
            cells.append(Cell(lat, lon, True, None, None))
            continue
        wbm_name = f"{name.removesuffix('_DEM')}_WBM.tif"
        files = {
            "dem": (f"{dem_base}/{name}/{name}.tif", f"copernicus/{name}/{name}.tif"),
            "wbm": (
                f"{dem_base}/{name}/AUXFILES/{wbm_name}",
                f"copernicus/{name}/{wbm_name}",
            ),
        }
        refs = {
            key: _obtain(url, rel, cache, pinned.get(rel), counts)
            for key, (url, rel) in files.items()
        }
        cells.append(Cell(lat, lon, False, refs["dem"], refs["wbm"]))

    natural_earth = tuple(
        _obtain(
            f"{ne_base}/{name}",
            f"natural-earth/{NATURAL_EARTH_COMMIT}/{name}",
            cache,
            pinned.get(f"natural-earth/{NATURAL_EARTH_COMMIT}/{name}"),
            counts,
        )
        for name in NE_FILES
    )

    if existing is not None:
        return FetchResult(
            existing, False, counts.downloaded, counts.verified, "The cache matches the lock."
        )
    lock = Lock(
        created=datetime.now(UTC).date().isoformat(),
        tile_list_sha256=tile_list_sha,
        cells=tuple(cells),
        natural_earth=natural_earth,
    )
    out_name = f"{profile.name}.lock" if lock_name is None else lock_name
    lock_file = out / out_name
    out.mkdir(parents=True, exist_ok=True)
    with atomic_write(lock_file) as tmp:
        config.dump_lock(lock, tmp)
    return FetchResult(
        lock,
        True,
        counts.downloaded,
        counts.verified,
        _new_message(lock_file, lock_destination=lock_destination),
    )
