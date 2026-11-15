"""Pack a tile folder into one PMTiles archive and check the archive.

``pack`` writes ``metadata.json`` next to the tiles and hands the folder to
Protomaps' ``disk_to_pmtiles``, which takes the archive's bounds, zooms and
format from that file. The packer runs in its own process with
``PYTHONHASHSEED=0`` so the same tiles always give the same bytes. ``verify``
reads the finished archive back with go-pmtiles. Angles are degrees, sizes
bytes.
"""

import json
import math
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from osgeo import ogr

from .config import Bounds, Profile
from .workarea import WorkArea, atomic_write

ARCHIVE_NAME = "relief.pmtiles"
METADATA_NAME = "metadata.json"

_UNKNOWN = "unknown"
_BOUNDS_TOLERANCE_DEG = 1e-6
_FORMATS = {"painted": ("webp", "painted-rgba"), "value": ("png", "terrarium-value")}
_PACK_CODE = (
    "import sys; from pmtiles.convert import disk_to_pmtiles;"
    " disk_to_pmtiles(sys.argv[1], sys.argv[2], 'auto')"
)
_STORED_LINE = re.compile(r"^addressed tiles count:\s*(\d+)\s*$", re.MULTILINE)


class PackError(Exception):
    """Packing failed, or the archive does not match the profile."""


@dataclass(frozen=True)
class Provenance:
    """Where a build came from; a value that is not known is ``"unknown"``.

    Attributes:
        tool_commit: commit of the tool's code.
        data_commit: commit of the data checkout.
        image: the container image reference or digest.
        fingerprint: the build fingerprint (SHA-256 hex).
    """

    tool_commit: str = _UNKNOWN
    data_commit: str = _UNKNOWN
    image: str = _UNKNOWN
    fingerprint: str = _UNKNOWN


def metadata(profile: Profile, variant: str, provenance: Provenance) -> dict:
    """Build the archive's metadata.

    Args:
        profile: the build profile.
        variant: ``painted`` or ``value``.
        provenance: where the build came from.

    Returns:
        A dict with ``format`` (``webp`` or ``png``), ``minzoom`` 0, ``maxzoom``,
        ``bounds`` (``"west,south,east,north"`` of the data extent, degrees),
        ``name``, ``trial`` (always true), ``variant``, ``encoding`` and the
        provenance fields.
    """
    tile_format, encoding = _FORMATS[variant]
    return {
        "format": tile_format,
        "minzoom": 0,
        "maxzoom": profile.max_zoom,
        "bounds": _bounds_text(profile.data_extent),
        "name": profile.name,
        "trial": True,
        "variant": variant,
        "encoding": encoding,
        "fingerprint": provenance.fingerprint,
        "tool_commit": provenance.tool_commit,
        "data_commit": provenance.data_commit,
        "image": provenance.image,
    }


def _bounds_text(bounds: Bounds) -> str:
    return f"{bounds.west},{bounds.south},{bounds.east},{bounds.north}"


def pack(work: WorkArea, out_dir: Path, meta: dict) -> Path:
    """Pack the work area's tiles into ``out_dir/relief.pmtiles``.

    Args:
        work: the work area with a finished tile folder.
        out_dir: the output directory; created if missing.
        meta: the metadata from ``metadata``. It is written to
            ``<tiles>/metadata.json``, where ``disk_to_pmtiles`` reads the
            format, zooms and bounds for the archive's header.

    Returns:
        The archive's path. ``metadata.json`` is copied beside it.

    Raises:
        PackError: if the packer fails.
    """
    text = json.dumps(meta, indent=2, sort_keys=True) + "\n"
    (work.tiles_dir / METADATA_NAME).write_text(text)
    out_dir.mkdir(parents=True, exist_ok=True)
    archive = out_dir / ARCHIVE_NAME
    with atomic_write(archive) as tmp:
        result = subprocess.run(
            [sys.executable, "-c", _PACK_CODE, str(work.tiles_dir), str(tmp)],
            env={**os.environ, "PYTHONHASHSEED": "0"},
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise PackError(f"disk_to_pmtiles failed: {result.stderr.strip()}")
    (out_dir / METADATA_NAME).write_text(text)
    return archive


def _pmtiles(*args: Path | str) -> str:
    result = subprocess.run(["pmtiles", *map(str, args)], capture_output=True, text=True)
    if result.returncode != 0:
        raise PackError(f"pmtiles {args[0]} failed: {result.stderr.strip()}")
    return result.stdout


def stored_tiles(pmtiles_path: Path) -> int:
    """Count the tiles an archive addresses.

    Args:
        pmtiles_path: the archive.

    Returns:
        The ``addressed tiles count`` of ``pmtiles show``. The header JSON
        (``--header-json``) does not carry it.

    Raises:
        PackError: if the count cannot be read.
    """
    match = _STORED_LINE.search(_pmtiles("show", pmtiles_path))
    if match is None:
        raise PackError(f"no addressed tiles count in {pmtiles_path}")
    return int(match.group(1))


def verify(pmtiles_path: Path, profile: Profile) -> None:
    """Check an archive's structure, zooms, bounds and trial marker.

    Args:
        pmtiles_path: the archive.
        profile: the profile it was built from.

    Raises:
        PackError: if ``pmtiles verify`` fails, the zooms are not 0 to the
            profile's ``max_zoom``, a bound differs from the data extent by
            more than 1e-6 degrees, or the metadata is not ``trial: true``.
    """
    _pmtiles("verify", pmtiles_path)
    header = json.loads(_pmtiles("show", pmtiles_path, "--header-json"))
    meta = json.loads(_pmtiles("show", pmtiles_path, "--metadata"))
    if header["minzoom"] != 0:
        raise PackError(f"minzoom is {header['minzoom']}, expected 0")
    if header["maxzoom"] != profile.max_zoom:
        raise PackError(f"maxzoom is {header['maxzoom']}, expected {profile.max_zoom}")
    extent = profile.data_extent
    expected = [extent.west, extent.south, extent.east, extent.north]
    if any(
        abs(a - b) > _BOUNDS_TOLERANCE_DEG for a, b in zip(header["bounds"], expected, strict=True)
    ):
        raise PackError(f"bounds are {header['bounds']}, expected {expected}")
    if meta.get("trial") is not True:
        raise PackError("metadata does not say trial: true")


def _centre_lon_lat(z: int, x: int, y: int) -> tuple[float, float]:
    n = 2**z
    lon = (x + 0.5) / n * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 0.5) / n))))
    return lon, lat


def _outline_geometry(outline: Path) -> ogr.Geometry:
    land = None
    source = ogr.Open(str(outline))
    for feature in source.GetLayer():
        geometry = feature.GetGeometryRef().Clone()
        land = geometry if land is None else land.Union(geometry)
    return land


def size_by_zoom(tiles_dir: Path, outline: Path) -> list[dict]:
    """Split each zoom's tile bytes into inside and outside the peninsula.

    Args:
        tiles_dir: a ``<z>/<x>/<y>.<ext>`` tile folder; other files are ignored.
        outline: a GeoJSON polygon file in longitude/latitude.

    Returns:
        One row per zoom, ascending: ``{zoom, inside_bytes, outside_bytes}``.
        A tile is inside when its centre point is strictly inside the outline.
    """
    land = _outline_geometry(outline)
    totals: dict[int, list[int]] = {}
    for zoom_dir in sorted(tiles_dir.iterdir(), key=lambda p: p.name):
        if not (zoom_dir.is_dir() and zoom_dir.name.isdigit()):
            continue
        z = int(zoom_dir.name)
        row = totals.setdefault(z, [0, 0])
        for tile in zoom_dir.glob("*/*"):
            lon, lat = _centre_lon_lat(z, int(tile.parent.name), int(tile.stem))
            point = ogr.Geometry(ogr.wkbPoint)
            point.AddPoint_2D(lon, lat)
            row[0 if land.Contains(point) else 1] += tile.stat().st_size
    return [
        {"zoom": z, "inside_bytes": inside, "outside_bytes": outside}
        for z, (inside, outside) in sorted(totals.items())
    ]
