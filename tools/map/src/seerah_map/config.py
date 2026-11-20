"""Profiles, terrain edits and the download lock: loaders and dataclasses.

A profile says what area to build and how to style it; an edits file lists
hand edits to the terrain; a lock pins every downloaded file by SHA-256.
All angles are degrees, all lengths in edits are metres.
"""

import json
import math
from dataclasses import dataclass
from pathlib import Path

import morecantile
from jsonschema import Draft202012Validator

from . import strict_yaml
from .errors import ConfigError

_SCHEMA_DIR = Path(__file__).parent / "schema"
_MIN_BLOCK_ZOOM = 6
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
    """A trial build profile; ``peninsula_outline`` is already resolved against ``folder``."""

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


def load_profile(path: Path) -> Profile:
    """Load and validate a profile YAML file.

    The camera bounds and the data extent (the camera bounds widened by
    ``margin_deg``) must both lie in the N/E quadrant of the WebMercatorQuad
    grid: longitude 0 to 180 and latitude 0 to the grid's northern edge, all
    edges included. The schema reports out-of-range values first; non-finite
    values it lets through are reported next.

    Args:
        path: The profile file; its stem becomes ``Profile.name``.

    Returns:
        The profile, with the outline path resolved against the file's folder.

    Raises:
        ConfigError: The file breaks the schema or a cross-field rule.
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
        peninsula_outline=path.parent / data["peninsula_outline"],
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
