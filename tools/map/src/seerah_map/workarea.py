"""Build fingerprints and the work areas they name.

A fingerprint is a SHA-256 hex digest of everything that decides a build's
output. Each fingerprint owns one directory under the work root, so builds
with different inputs coexist and a finished stage is reused only when its
inputs are unchanged. Sizes are in bytes.
"""

import hashlib
import os
import shutil
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from importlib import metadata
from pathlib import Path

from osgeo import gdal

_PART_SEPARATOR = b"\0"
_IGNORED_DIR = "__pycache__"
_IGNORED_SUFFIX = ".pyc"


def code_hash(src_root: Path) -> str:
    """Hash the tool's own code.

    Args:
        src_root: directory whose files are hashed.

    Returns:
        SHA-256 hex digest over each file's relative path and bytes, in sorted
        path order. ``__pycache__`` directories and ``*.pyc`` files are
        skipped, so bytecode caches never change the result.
    """
    digest = hashlib.sha256()
    files = sorted(
        path
        for path in src_root.rglob("*")
        if path.is_file()
        and _IGNORED_DIR not in path.relative_to(src_root).parts
        and path.suffix != _IGNORED_SUFFIX
    )
    for path in files:
        digest.update(path.relative_to(src_root).as_posix().encode())
        digest.update(_PART_SEPARATOR)
        digest.update(path.read_bytes())
        digest.update(_PART_SEPARATOR)
    return digest.hexdigest()


def toolchain_id() -> str:
    """Describe the installed toolchain.

    Returns:
        The GDAL version, then the sorted ``name==version`` list of every
        installed Python distribution (names lowercased), one per line.
    """
    packages = sorted(
        f"{dist.metadata['Name'].lower()}=={dist.version}" for dist in metadata.distributions()
    )
    return "\n".join([f"gdal {gdal.__version__}", *packages])


def fingerprint(
    profile: bytes,
    edits: bytes,
    lock: bytes,
    tool_commit: str | None,
    image_digest: str | None,
    variant: str,
    src_root: Path,
) -> str:
    """Name a build by everything that decides its output.

    Args:
        profile: bytes of the profile file.
        edits: bytes of the edits file.
        lock: bytes of the lock file.
        tool_commit: commit of the tool; ``None`` uses ``code_hash(src_root)``.
        image_digest: digest of the image; ``None`` uses ``toolchain_id()``.
        variant: the build variant's name.
        src_root: the tool's source directory, hashed when ``tool_commit`` is
            ``None``.

    Returns:
        SHA-256 hex digest of the parts joined with ``b"\\0"``.
    """
    parts = [
        profile,
        edits,
        lock,
        (tool_commit if tool_commit is not None else code_hash(src_root)).encode(),
        (image_digest if image_digest is not None else toolchain_id()).encode(),
        variant.encode(),
    ]
    return hashlib.sha256(_PART_SEPARATOR.join(parts)).hexdigest()


@dataclass(frozen=True)
class WorkArea:
    """One fingerprint's directory under the work root.

    Attributes:
        root: the work root that holds every area.
        fp: the fingerprint that names this area.
    """

    root: Path
    fp: str

    @property
    def path(self) -> Path:
        """The area's directory."""
        return self.root / self.fp

    @property
    def style_dir(self) -> Path:
        """Directory for styled-relief blocks."""
        return self.path / "style"

    @property
    def mask_dir(self) -> Path:
        """Directory for land-mask blocks."""
        return self.path / "mask"

    @property
    def base_dir(self) -> Path:
        """Directory for the vector base layer and its intermediate slices."""
        return self.path / "base"

    @property
    def tiles_dir(self) -> Path:
        """Directory for tiles."""
        return self.path / "tiles"

    @property
    def tiles_done(self) -> Path:
        """Marker file written once every tile is complete."""
        return self.path / "tiles.done"


def open_area(work_root: Path, fp: str) -> WorkArea:
    """Create the area for a fingerprint if it is missing.

    Idempotent: an existing area is kept as it is, and areas with other
    fingerprints are never touched.

    Args:
        work_root: the work root.
        fp: the fingerprint.

    Returns:
        The area, with its directories present.
    """
    area = WorkArea(work_root, fp)
    for directory in (area.style_dir, area.mask_dir, area.tiles_dir, area.base_dir):
        directory.mkdir(parents=True, exist_ok=True)
    return area


@contextmanager
def atomic_write(path: Path) -> Iterator[Path]:
    """Write a file so readers never see it half-written.

    Args:
        path: the final file.

    Yields:
        ``<path>.tmp``, for the caller to write. On a clean exit it is
        renamed over ``path``; on an exception it is deleted.
    """
    tmp = path.with_name(path.name + ".tmp")
    try:
        yield tmp
        os.replace(tmp, path)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


def _size_bytes(directory: Path) -> int:
    return sum(p.stat().st_size for p in directory.rglob("*") if p.is_file())


def list_areas(work_root: Path) -> list[tuple[str, int]]:
    """List the work areas.

    Args:
        work_root: the work root.

    Returns:
        ``(fingerprint, size in bytes)`` for each area, sorted by fingerprint.
        A missing work root has no areas.
    """
    if not work_root.is_dir():
        return []
    return [(path.name, _size_bytes(path)) for path in sorted(work_root.iterdir()) if path.is_dir()]


def remove(area: WorkArea) -> None:
    """Delete an area and everything in it.

    Args:
        area: the area to delete.
    """
    shutil.rmtree(area.path)
