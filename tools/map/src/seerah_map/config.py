"""Profiles, terrain edits, coastal cleanup edits and the download lock: loaders and dataclasses.

A profile says what area to build and how to style it; an edits file lists
hand edits to the terrain; a cleanup file lists production coastal cleanup
edits to the Water Body Mask and elevation; a lock pins every downloaded file
by SHA-256. All angles are degrees, all lengths in edits are metres.
"""

import contextlib
import errno
import json
import math
import os
import stat
from collections import deque
from dataclasses import dataclass
from pathlib import Path

import morecantile
from jsonschema import Draft202012Validator

from . import strict_yaml
from .errors import ConfigError

_SCHEMA_DIR = Path(__file__).parent / "schema"
_MIN_BLOCK_ZOOM = 6
_MAX_SYMLINKS = 40  # Linux's MAXSYMLINKS limit for symlink expansion.
# The same tile grid as grid.py, which imports this module and so cannot be imported here.
_TILE_GRID = morecantile.tms.get("WebMercatorQuad")
# The part of the grid a profile may cover: the N/E quadrant, up to the grid's northern edge.
_LON_RANGE = (0.0, _TILE_GRID.bbox.right)
_LAT_RANGE = (0.0, _TILE_GRID.bbox.top)
_SIDE_RANGES = {
    "west": _LON_RANGE,
    "south": _LAT_RANGE,
    "east": _LON_RANGE,
    "north": _LAT_RANGE,
}


@dataclass(frozen=True)
class Bounds:
    """A lon/lat box in degrees (EPSG:4326)."""

    west: float
    south: float
    east: float
    north: float


@dataclass(frozen=True)
class StyleSettings:
    """Relief styling. Blurs are in pixels at zoom 12; angles in degrees."""

    blur_px: float = 7.0
    band_blur_px: float = 1.5
    exaggeration: float = 7.36
    azimuth_deg: float = 315
    altitude_deg: float = 45
    contrast: float = 1.6
    bands: int = 3
    ink_rgb: tuple[int, int, int] = (150, 118, 72)
    max_alpha: float = 0.6


@dataclass(frozen=True)
class Profile:
    """A trial build profile; ``peninsula_outline`` is canonical and confined to its data root."""

    name: str
    folder: Path
    purpose: str
    camera_bounds: Bounds
    margin_deg: float
    fade_deg: float
    max_zoom: int
    block_zoom: int
    webp_quality: int
    style: StyleSettings
    peninsula_outline: Path

    @property
    def data_extent(self) -> Bounds:
        """The camera bounds widened by ``margin_deg`` on every side (degrees)."""
        b, m = self.camera_bounds, self.margin_deg
        return Bounds(b.west - m, b.south - m, b.east + m, b.north + m)


@dataclass(frozen=True)
class Edit:
    """One terrain edit. Geometry is GeoJSON in longitude/latitude; sizes are metres."""

    id: str
    geometry: dict
    operation: str
    amount_m: float | None
    height_m: float | None
    width_m: float | None
    feather_m: float
    review: str


@dataclass(frozen=True)
class CleanupSource:
    """Citation supporting a production coastal cleanup edit (imagery or dated data)."""

    kind: str
    work: str
    locator: str
    edition: str | None = None
    url: str | None = None


@dataclass(frozen=True)
class CleanupEdit:
    """One production coastal cleanup edit applied to the WBM and coincident elevation."""

    id: str
    geometry: dict
    operation: str
    reason: str
    review: str
    sources: tuple[CleanupSource, ...] = ()


@dataclass(frozen=True)
class FileRef:
    """A downloaded file: its URL, size in bytes, SHA-256 and path relative to /cache."""

    url: str
    size: int
    sha256: str
    path: str


@dataclass(frozen=True)
class Cell:
    """A one-degree cell (south-west corner ``lat``, ``lon``); absent cells have no files."""

    lat: int
    lon: int
    absent: bool
    dem: FileRef | None
    wbm: FileRef | None


@dataclass(frozen=True)
class Lock:
    """The pinned download set: tile-list hash, cells and Natural Earth files."""

    created: str
    tile_list_sha256: str
    cells: tuple[Cell, ...]
    natural_earth: tuple[FileRef, ...]


def _validate(data: object, schema_name: str, path: Path) -> None:
    """Raise ConfigError for the first schema violation in ``data``."""
    schema = json.loads((_SCHEMA_DIR / schema_name).read_text(encoding="utf-8"))
    errors = sorted(
        Draft202012Validator(schema).iter_errors(data), key=lambda e: list(e.absolute_path)
    )
    if errors:
        error = errors[0]
        key = ".".join(str(p) for p in error.absolute_path) or "(top level)"
        raise ConfigError(f"{path}: key {key}: {error.message}")


def _fail(path: Path, key: str, expected: str, found: object) -> ConfigError:
    """Build a ConfigError naming the file, key, expectation and finding."""
    return ConfigError(f"{path}: key {key}: expected {expected}, found {found!r}")


def _check_finite(value: object, path: Path, prefix: str = "") -> None:
    """Reject non-finite floats anywhere in ``value`` (YAML allows ``.inf`` and ``.nan``)."""
    if isinstance(value, dict):
        for key, item in value.items():
            child = f"{prefix}.{key}" if prefix else str(key)
            _check_finite(item, path, child)
    elif isinstance(value, list):
        for i, item in enumerate(value):
            _check_finite(item, path, f"{prefix}[{i}]")
    elif isinstance(value, float) and not math.isfinite(value):
        raise _fail(path, prefix or "(top level)", "a finite number", value)


def _check_bounds(raw: dict, path: Path) -> Bounds:
    """Check the camera bounds are in the N/E quadrant, inside the tile grid and ordered."""
    bounds = Bounds(**raw)
    for key, (low, high) in _SIDE_RANGES.items():
        value = getattr(bounds, key)
        if value < low:
            raise _fail(
                path, f"camera_bounds.{key}", f"a value >= {low} (N/E quadrant only)", value
            )
        if value > high:
            raise _fail(
                path, f"camera_bounds.{key}", f"a value <= {high} (the tile grid's edge)", value
            )
    if not bounds.west < bounds.east:
        raise _fail(path, "camera_bounds", "west < east", f"{bounds.west} >= {bounds.east}")
    if not bounds.south < bounds.north:
        raise _fail(path, "camera_bounds", "south < north", f"{bounds.south} >= {bounds.north}")
    return bounds


def _check_data_extent(profile: Profile, path: Path) -> None:
    """Check the margin keeps the data extent in the N/E quadrant and inside the tile grid."""
    extent = profile.data_extent
    for key, (low, high) in _SIDE_RANGES.items():
        edge = getattr(extent, key)
        if not low <= edge <= high:
            raise _fail(
                path,
                "margin_deg",
                f"a margin keeping the data extent's {key} edge ({edge}) within {low}..{high}",
                profile.margin_deg,
            )


def _canonical_path(target: Path) -> Path:
    """Resolve symlinks component by component, allowing missing leaves while rejecting loops."""
    absolute = target if target.is_absolute() else Path.cwd() / target
    current = Path(absolute.anchor)
    pending = deque(absolute.parts[1:])
    links = 0
    while pending:
        part = pending.popleft()
        if part in ("", "."):
            continue
        if part == "..":
            with contextlib.suppress(FileNotFoundError):
                os.lstat(current / "..")
            current = current.parent
            continue
        nxt = current / part
        try:
            mode = os.lstat(nxt).st_mode
        except FileNotFoundError:
            current = nxt
            continue
        if stat.S_ISLNK(mode):
            links += 1
            if links > _MAX_SYMLINKS:
                raise OSError(errno.ELOOP, os.strerror(errno.ELOOP), str(target))
            dest = Path(os.readlink(nxt))
            if dest.is_absolute():
                current = Path(dest.anchor)
                pending.extendleft(reversed(dest.parts[1:]))
            else:
                pending.extendleft(reversed(dest.parts))
        else:
            current = nxt
    return current


def _resolve_outline(raw: str, path: Path, data_root: Path | None) -> Path:
    """Resolve ``peninsula_outline`` relative to ``path.parent`` inside ``data_root``."""
    outline = Path(raw)
    if outline.is_absolute():
        raise _fail(path, "peninsula_outline", "a path relative to the profile folder", raw)
    boundary = path.parent if data_root is None else Path(data_root)
    try:
        root = _canonical_path(boundary)
        candidate = _canonical_path(path.parent / outline)
    except (OSError, RuntimeError, ValueError) as error:
        raise _fail(
            path, "peninsula_outline", f"a resolvable path inside {boundary} ({error})", raw
        ) from error
    if candidate == root or not candidate.is_relative_to(root):
        raise _fail(path, "peninsula_outline", f"a path inside {root}", raw)
    return candidate


def load_profile(path: Path, *, data_root: Path | None = None) -> Profile:
    """Load and validate a profile YAML file.

    The camera bounds and the data extent (the camera bounds widened by
    ``margin_deg``) must both lie in the N/E quadrant of the WebMercatorQuad
    grid: longitude 0 to 180 and latitude 0 to the grid's northern edge, all
    edges included. The schema reports out-of-range values first; non-finite
    values it lets through are reported next.

    ``peninsula_outline`` must be a relative path resolved from the profile's
    folder; its canonical target must stay inside ``data_root`` (or the
    profile's folder when ``data_root`` is omitted), so sibling folders inside
    a shared data root are allowed while escapes and symlink loops are
    rejected. The file itself is not opened here; existence, file-type and
    geometry checks remain in ``pack``.

    Args:
        path: The profile file; its stem becomes ``Profile.name``.
        data_root: The directory that confines ``peninsula_outline``. Defaults
            to ``path.parent`` when omitted.

    Returns:
        The profile, with ``peninsula_outline`` canonicalized inside the root.

    Raises:
        ConfigError: The file breaks the schema, a cross-field rule, or the
            outline confinement rule.
    """
    path = Path(path)
    data = strict_yaml.load(path)
    _validate(data, "profile.schema.json", path)
    _check_finite(data, path)
    bounds = _check_bounds(data["camera_bounds"], path)
    margin, fade = data["margin_deg"], data["fade_deg"]
    if margin > 0 and fade > margin:
        raise _fail(path, "fade_deg", f"a value <= margin_deg ({margin})", fade)
    if not _MIN_BLOCK_ZOOM <= data["block_zoom"] <= data["max_zoom"]:
        raise _fail(
            path,
            "block_zoom",
            f"{_MIN_BLOCK_ZOOM} <= block_zoom <= max_zoom ({data['max_zoom']})",
            data["block_zoom"],
        )
    outline = _resolve_outline(data["peninsula_outline"], path, data_root)
    style = dict(data.get("style", {}))
    if "ink_rgb" in style:
        style["ink_rgb"] = tuple(style["ink_rgb"])
    profile = Profile(
        name=path.stem,
        folder=path.parent,
        purpose=data["purpose"],
        camera_bounds=bounds,
        margin_deg=margin,
        fade_deg=fade,
        max_zoom=data["max_zoom"],
        block_zoom=data["block_zoom"],
        webp_quality=data.get("webp_quality", 80),
        style=StyleSettings(**style),
        peninsula_outline=outline,
    )
    _check_data_extent(profile, path)
    return profile


def load_edits(path: Path) -> list[Edit]:
    """Load and validate an edits file; approved and other edits are all returned.

    Args:
        path: The edits YAML file.

    Raises:
        ConfigError: The file breaks the schema or repeats an edit id.
    """
    path = Path(path)
    data = strict_yaml.load(path)
    _validate(data, "edits.schema.json", path)
    seen: set[str] = set()
    edits = []
    for raw in data["edits"]:
        if raw["id"] in seen:
            raise _fail(path, "edits.id", "unique ids", raw["id"])
        seen.add(raw["id"])
        edits.append(
            Edit(
                id=raw["id"],
                geometry=raw["geometry"],
                operation=raw["operation"],
                amount_m=raw.get("amount_m"),
                height_m=raw.get("height_m"),
                width_m=raw.get("width_m"),
                feather_m=raw["feather_m"],
                review=raw["review"],
            )
        )
    return edits


def _check_cleanup_geometry(geometry: dict, path: Path, key: str) -> None:
    """Check WebMercatorQuad bounds and explicit ring closure for Polygon/MultiPolygon."""
    polygons = (
        [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
    )
    min_lon, max_lon = _TILE_GRID.bbox.left, _TILE_GRID.bbox.right
    min_lat, max_lat = _TILE_GRID.bbox.bottom, _TILE_GRID.bbox.top
    for poly_idx, polygon in enumerate(polygons):
        poly_key = key if geometry["type"] == "Polygon" else f"{key}[{poly_idx}]"
        for ring_idx, ring in enumerate(polygon):
            for pt_idx, (lon, lat) in enumerate(ring):
                if not min_lon <= lon <= max_lon:
                    raise _fail(
                        path,
                        f"{poly_key}[{ring_idx}][{pt_idx}][0]",
                        f"longitude in {min_lon}..{max_lon}",
                        lon,
                    )
                if not min_lat <= lat <= max_lat:
                    raise _fail(
                        path,
                        f"{poly_key}[{ring_idx}][{pt_idx}][1]",
                        f"latitude in {min_lat}..{max_lat}",
                        lat,
                    )
            if ring[0] != ring[-1]:
                raise _fail(
                    path,
                    f"{poly_key}[{ring_idx}]",
                    "a closed ring whose first and last points match",
                    (ring[0], ring[-1]),
                )


def load_cleanup(path: Path) -> list[CleanupEdit]:
    """Load and validate a production cleanup edits file; approved and others are all returned.

    Performs JSON Schema validation, finite-number checks, unique ID checks,
    WebMercatorQuad coordinate range checks, and explicit polygon ring closure
    without importing GDAL/OGR (geometric validity and overlap checks live in
    ``seerah_map.edits.validate_cleanups``).

    Args:
        path: The cleanup YAML file.

    Returns:
        Every cleanup edit declared in the file, in file order.

    Raises:
        ConfigError: The file breaks the schema, repeats an ID, contains
            non-finite numbers, leaves a ring unclosed, or exits WebMercatorQuad.
    """
    path = Path(path)
    data = strict_yaml.load(path)
    _validate(data, "cleanup.schema.json", path)
    _check_finite(data, path)
    seen: set[str] = set()
    cleanups = []
    for i, raw in enumerate(data["edits"]):
        if raw["id"] in seen:
            raise _fail(path, "edits.id", "unique ids", raw["id"])
        seen.add(raw["id"])
        _check_cleanup_geometry(raw["geometry"], path, f"edits[{i}].geometry")
        sources = tuple(
            CleanupSource(
                kind=src["kind"],
                work=src["work"],
                locator=src["locator"],
                edition=src.get("edition"),
                url=src.get("url"),
            )
            for src in raw.get("sources", [])
        )
        cleanups.append(
            CleanupEdit(
                id=raw["id"],
                geometry=raw["geometry"],
                operation=raw["operation"],
                reason=raw["reason"],
                review=raw["review"],
                sources=sources,
            )
        )
    return cleanups


def _file_ref(raw: object, path: Path, key: str) -> FileRef:
    """Build a FileRef from a lock entry, checking its fields."""
    if not isinstance(raw, dict) or set(raw) != {"url", "size", "sha256", "path"}:
        raise _fail(path, key, "a mapping with url, size, sha256 and path", raw)
    if not isinstance(raw["size"], int) or isinstance(raw["size"], bool) or raw["size"] < 0:
        raise _fail(path, f"{key}.size", "a non-negative integer", raw["size"])
    for field in ("url", "sha256", "path"):
        if not isinstance(raw[field], str) or not raw[field]:
            raise _fail(path, f"{key}.{field}", "a non-empty string", raw[field])
    return FileRef(**raw)


def _optional_ref(raw: object, path: Path, key: str) -> FileRef | None:
    """Build a FileRef, or None when the entry is null."""
    return None if raw is None else _file_ref(raw, path, key)


def load_lock(path: Path) -> Lock:
    """Load a download lock, checking its structure while building the dataclasses.

    Raises:
        ConfigError: A field is missing or has the wrong type.
    """
    path = Path(path)
    data = strict_yaml.load(path)
    if not isinstance(data, dict):
        raise _fail(path, "(top level)", "a mapping", data)
    for key in ("created", "tile_list_sha256", "cells", "natural_earth"):
        if key not in data:
            raise _fail(path, key, "the key to be present", "nothing")
    for key in ("cells", "natural_earth"):
        if type(data[key]) is not list:
            raise _fail(path, key, "a list", data[key])
    cells = []
    for i, raw in enumerate(data["cells"]):
        key = f"cells[{i}]"
        if not isinstance(raw, dict) or not {"lat", "lon", "absent"} <= set(raw):
            raise _fail(path, key, "a mapping with lat, lon and absent", raw)
        for field, expected in (("lat", int), ("lon", int), ("absent", bool)):
            if type(raw[field]) is not expected:
                raise _fail(path, f"{key}.{field}", f"a {expected.__name__}", raw[field])
        cells.append(
            Cell(
                lat=raw["lat"],
                lon=raw["lon"],
                absent=raw["absent"],
                dem=_optional_ref(raw.get("dem"), path, f"{key}.dem"),
                wbm=_optional_ref(raw.get("wbm"), path, f"{key}.wbm"),
            )
        )
    natural_earth = tuple(
        _file_ref(raw, path, f"natural_earth[{i}]") for i, raw in enumerate(data["natural_earth"])
    )
    return Lock(
        created=str(data["created"]),
        tile_list_sha256=str(data["tile_list_sha256"]),
        cells=tuple(cells),
        natural_earth=natural_earth,
    )


def _ref_dict(ref: FileRef | None) -> dict | None:
    """Return a FileRef as a plain dict, or None."""
    if ref is None:
        return None
    return {"url": ref.url, "size": ref.size, "sha256": ref.sha256, "path": ref.path}


def dump_lock(lock: Lock, path: Path) -> None:
    """Write a lock as YAML.

    Args:
        lock: The lock to write.
        path: The file to write.
    """
    strict_yaml.dump(
        {
            "created": lock.created,
            "tile_list_sha256": lock.tile_list_sha256,
            "cells": [
                {
                    "lat": c.lat,
                    "lon": c.lon,
                    "absent": c.absent,
                    "dem": _ref_dict(c.dem),
                    "wbm": _ref_dict(c.wbm),
                }
                for c in lock.cells
            ],
            "natural_earth": [_ref_dict(r) for r in lock.natural_earth],
        },
        Path(path),
    )
