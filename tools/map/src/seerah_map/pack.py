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
import struct
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from osgeo import ogr
from pmtiles.reader import MmapSource, Reader
from pmtiles.tile import MagicNumberNotFound

from .blockstage import PYRAMID
from .config import COPERNICUS_ATTRIBUTION, Bounds, BuildTarget, Coverage, Profile
from .errors import ConfigError
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


def metadata(
    profile: Profile | BuildTarget,
    variant: str,
    provenance: Provenance,
    mode: str = PYRAMID,
    *,
    trial: bool | None = None,
) -> dict:
    """Build the archive's metadata.

    Args:
        profile: the build profile or target.
        variant: ``painted`` or ``value``.
        provenance: where the build came from.
        mode: how zooms below ``max_zoom`` were built (``pyramid`` or ``per-zoom``).
        trial: whether to emit trial metadata (``trial: true``, ``variant``,
            ``mode``) or production ``tile_schema: 1`` metadata (``name: seerah-map``,
            no ``trial``/``variant``/``mode`` keys). Defaults to ``profile.trial``
            when present, else ``True``.

    Returns:
        A metadata dict for ``disk_to_pmtiles``.
    """
    is_trial = getattr(profile, "trial", True) if trial is None else trial
    tile_format, encoding = _FORMATS[variant]
    if not is_trial:
        return {
            "format": tile_format,
            "minzoom": 0,
            "maxzoom": profile.max_zoom,
            "bounds": _bounds_text(profile.data_extent),
            "name": "seerah-map",
            "tile_schema": 1,
            "attribution": COPERNICUS_ATTRIBUTION,
            "encoding": encoding,
            "fingerprint": provenance.fingerprint,
            "tool_commit": provenance.tool_commit,
            "data_commit": provenance.data_commit,
            "image": provenance.image,
        }
    return {
        "format": tile_format,
        "minzoom": 0,
        "maxzoom": profile.max_zoom,
        "bounds": _bounds_text(profile.data_extent),
        "name": profile.name,
        "trial": True,
        "variant": variant,
        "mode": mode,
        "encoding": encoding,
        "fingerprint": provenance.fingerprint,
        "tool_commit": provenance.tool_commit,
        "data_commit": provenance.data_commit,
        "image": provenance.image,
    }


def _bounds_text(bounds: Bounds) -> str:
    return f"{bounds.west},{bounds.south},{bounds.east},{bounds.north}"


def pack(work: WorkArea, out_dir: Path, meta: dict, *, write_metadata_file: bool = True) -> Path:
    """Pack the work area's tiles into ``out_dir/relief.pmtiles``.

    Args:
        work: the work area with a finished tile folder.
        out_dir: the output directory; created if missing.
        meta: the metadata from ``metadata``. It is written to
            ``<tiles>/metadata.json``, where ``disk_to_pmtiles`` reads the
            format, zooms and bounds for the archive's header.
        write_metadata_file: whether to copy ``metadata.json`` into ``out_dir``
            (``True`` for trial builds, ``False`` for production staging).

    Returns:
        The archive's path.

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
    if write_metadata_file:
        with atomic_write(out_dir / METADATA_NAME) as tmp:
            tmp.write_text(text)
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
        The header's ``addressed_tiles_count``, read with the ``pmtiles``
        package.

    Raises:
        PackError: if the header cannot be read.
    """
    try:
        with pmtiles_path.open("rb") as archive:
            return int(Reader(MmapSource(archive)).header()["addressed_tiles_count"])
    except (OSError, KeyError, ValueError, struct.error, MagicNumberNotFound) as error:
        raise PackError(f"cannot read the tile count of {pmtiles_path}: {error}") from error


def verify(
    pmtiles_path: Path,
    profile: Profile | Coverage | BuildTarget,
    *,
    trial: bool | None = None,
) -> None:
    """Check an archive's structure, zooms, bounds and trial/production metadata contract.

    Args:
        pmtiles_path: the archive.
        profile: the profile, coverage or build target it was built from.
        trial: whether to verify trial metadata (``trial: true``) or production
            ``tile_schema: 1`` metadata (``trial`` absent, ``tile_schema == 1``,
            ``name == "seerah-map"``). Defaults to ``profile.trial`` when present,
            else ``True`` for ``Profile`` and ``False`` for ``Coverage``.

    Raises:
        PackError: if ``pmtiles verify`` fails, the zooms or bounds disagree,
            or the trial/production metadata contract is broken.
    """
    target = profile.as_build_target() if isinstance(profile, Coverage) else profile
    is_trial = getattr(target, "trial", True) if trial is None else trial
    _pmtiles("verify", pmtiles_path)
    header = json.loads(_pmtiles("show", pmtiles_path, "--header-json"))
    meta = json.loads(_pmtiles("show", pmtiles_path, "--metadata"))
    if header["minzoom"] != 0:
        raise PackError(f"minzoom is {header['minzoom']}, expected 0")
    if header["maxzoom"] != target.max_zoom:
        raise PackError(f"maxzoom is {header['maxzoom']}, expected {target.max_zoom}")
    extent = target.data_extent
    expected = [extent.west, extent.south, extent.east, extent.north]
    if any(
        abs(a - b) > _BOUNDS_TOLERANCE_DEG for a, b in zip(header["bounds"], expected, strict=True)
    ):
        raise PackError(f"bounds are {header['bounds']}, expected {expected}")
    if is_trial:
        if meta.get("trial") is not True:
            raise PackError("metadata does not say trial: true")
    else:
        if "trial" in meta:
            raise PackError("production metadata must not contain 'trial'")
        if "variant" in meta or "mode" in meta:
            raise PackError("production metadata must not contain trial 'variant' or 'mode' keys")
        if meta.get("tile_schema") != 1:
            raise PackError(f"metadata tile_schema is {meta.get('tile_schema')!r}, expected 1")
        if meta.get("name") != "seerah-map":
            raise PackError(f"metadata name is {meta.get('name')!r}, expected 'seerah-map'")


def _centre_lon_lat(z: int, x: int, y: int) -> tuple[float, float]:
    n = 2**z
    lon = (x + 0.5) / n * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 0.5) / n))))
    return lon, lat


def _outline_geometry(outline: Path) -> ogr.Geometry:
    if not outline.is_file():
        raise ConfigError(f"peninsula_outline {outline} does not exist")
    try:
        source = ogr.Open(str(outline))
    except (RuntimeError, OSError) as exc:
        raise ConfigError(f"cannot open peninsula_outline {outline}: {exc}") from exc
    if source is None:
        raise ConfigError(f"cannot open peninsula_outline {outline}")
    layer = source.GetLayer()
    if layer is None:
        raise ConfigError(f"peninsula_outline {outline} has no vector layer")
    land = None
    for feature in layer:
        geom = feature.GetGeometryRef()
        if geom is None:
            continue
        geometry = geom.Clone()
        land = geometry if land is None else land.Union(geometry)
    if land is None or land.IsEmpty() or not land.IsValid() or land.Area() <= 0:
        raise ConfigError(f"peninsula_outline {outline} has no valid polygon geometry")
    return land


def validate_outline(outline: Path) -> None:
    """Validate that ``outline`` exists and holds a valid, positive-area polygon geometry."""
    _outline_geometry(outline)


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
