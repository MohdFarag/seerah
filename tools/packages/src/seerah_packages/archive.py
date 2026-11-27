"""Shared deterministic ZIP packaging and verify-before-parse package verification."""

import hashlib
import json
import math
import os
import re
import stat
import struct
import zipfile
from collections.abc import Callable, Sequence
from datetime import UTC, datetime
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from jsonschema import Draft202012Validator
from pmtiles.reader import MmapSource, Reader, deserialize_header
from pmtiles.tile import Compression, TileType

from .signing import (
    RAW_SIGNATURE_BYTES,
    SigningError,
    TrustedKey,
    public_key_bytes_from_private,
    sign_bytes,
    validate_key_id,
    verify_signature_isolated,
)

_REPO_ROOT = Path(__file__).resolve().parents[4]
MANIFEST_SCHEMA_PATH = _REPO_ROOT / "content" / "schema" / "manifest.schema.json"
MAP_SCHEMA_PATH = _REPO_ROOT / "map-data" / "schema" / "map.schema.json"

MAX_RELEASE_FILE_BYTES = 2_147_483_647  # zipfile.ZIP64_LIMIT (2 GiB - 1 B)
MAX_MAP_MANIFEST_BYTES = 1_048_576  # 1 MiB
MAX_CONTENT_MANIFEST_BYTES = 4_194_304  # 4 MiB
MAX_MAP_JSON_BYTES = 1_048_576  # 1 MiB
MAX_PMTILES_METADATA_BYTES = 1_048_576  # 1 MiB
STREAM_CHUNK_BYTES = 1_048_576  # 1 MiB

MANIFEST_NAME = "manifest.json"
SIGNATURE_NAME = "manifest.sig"
REPORT_NAME = "report.json"
METADATA_NAME = "metadata.json"
MAP_REQUIRED_DATA_FILES = ("data/base.pmtiles", "data/map.json", "data/relief.pmtiles")

ZIP_DATE_TIME = (2026, 1, 1, 0, 0, 0)
ZIP_CREATE_SYSTEM_UNIX = 3
ZIP_REGULAR_FILE_ATTR = 0o100644 << 16
_LFH_STRUCT = struct.Struct("<4sHHHHHIIIHH")
_LFH_MAGIC = b"PK\x03\x04"
_EOCD_STRUCT = struct.Struct("<4sHHHHIIH")
_EOCD_MAGIC = b"PK\x05\x06"
_FLAG_ENCRYPTED = 0x01
_FLAG_DATA_DESCRIPTOR = 0x08

_DATA_PATH_RE = re.compile(r"^data/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")
_COMMIT_RE = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
_WEBMERCATOR_MAX_LAT = 85.0511287798066
_FLOAT_TOLERANCE_DEG = 1e-9
_PMTILES_BOUNDS_TOLERANCE_DEG = 1e-4


class PackageError(Exception):
    """Staging check, manifest validation, ZIP packaging, or verification failed."""


def format_utc_timestamp(dt: datetime | None = None) -> str:
    """Format a UTC datetime as ``YYYY-MM-DDTHH:MM:SSZ``."""
    target = datetime.now(UTC) if dt is None else dt.astimezone(UTC)
    return target.strftime("%Y-%m-%dT%H:%M:%SZ")


def validate_iso_timestamp(value: object) -> str:
    """Validate a strict UTC ISO-8601 timestamp ``YYYY-MM-DDTHH:MM:SSZ``."""
    if not isinstance(value, str):
        raise PackageError(f"created timestamp must be a string, got {value!r}")
    if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$", value):
        raise PackageError(f"invalid created timestamp {value!r}: expected YYYY-MM-DDTHH:MM:SSZ")
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise PackageError(f"invalid calendar date in created timestamp {value!r}: {exc}") from exc
    return value


def validate_entry_path(path: object, *, allow_root_manifest: bool = False) -> str:
    """Validate a ZIP entry path for safety and canonical structure."""
    if not isinstance(path, str) or not path:
        raise PackageError(f"invalid entry path {path!r}")
    if any(ord(ch) < 32 or ord(ch) > 126 for ch in path):
        raise PackageError(f"entry path {path!r} contains non-printable-ASCII characters")
    if "\\" in path or path.startswith("/") or path.endswith("/") or "//" in path:
        raise PackageError(f"entry path {path!r} contains forbidden slash or backslash syntax")
    segments = path.split("/")
    if any(seg in ("", ".", "..") for seg in segments):
        raise PackageError(f"entry path {path!r} contains relative or empty path segments")
    if allow_root_manifest and path in (MANIFEST_NAME, SIGNATURE_NAME):
        return path
    if not _DATA_PATH_RE.match(path):
        raise PackageError(f"entry path {path!r} must lie under data/ with safe ASCII characters")
    return path


def validate_data_paths(paths: Sequence[str]) -> list[str]:
    """Ensure ``data/...`` paths are valid, non-empty, sorted, unique, and prefix-free."""
    if not paths:
        raise PackageError("manifest files list must not be empty")
    validated = [validate_entry_path(p, allow_root_manifest=False) for p in paths]
    if validated != sorted(validated):
        raise PackageError("manifest files list must be sorted lexicographically by path")
    if len(set(validated)) != len(validated):
        raise PackageError("manifest files list contains duplicate paths")
    # Prefix-free check: since validated is sorted, any directory prefix immediately
    # precedes its descendant (e.g. "data/a" right before "data/a/b").
    for prev_path, next_path in zip(validated, validated[1:], strict=False):
        if next_path.startswith(prev_path + "/"):
            raise PackageError(
                f"manifest files list contains conflicting file/directory prefix: "
                f"{prev_path!r} and {next_path!r}"
            )
    return validated


def canonical_manifest_bytes(manifest: dict) -> bytes:
    """Serialize ``manifest`` to canonical deterministic UTF-8 JSON bytes."""
    return (json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode(
        "utf-8"
    )


def validate_manifest_dict(
    manifest: object,
    *,
    allow_local_provenance: bool = False,
) -> dict:
    """Validate a manifest dictionary against ``content/schema/manifest.schema.json``."""
    schema = json.loads(MANIFEST_SCHEMA_PATH.read_text(encoding="utf-8"))
    errors = sorted(
        Draft202012Validator(schema).iter_errors(manifest),
        key=lambda e: list(e.absolute_path),
    )
    if errors:
        err = errors[0]
        key = ".".join(str(p) for p in err.absolute_path) or "(top level)"
        raise PackageError(f"manifest.json: key {key}: {err.message}")
    assert isinstance(manifest, dict)
    validate_iso_timestamp(manifest["created"])
    validate_data_paths([entry["path"] for entry in manifest["files"]])
    if manifest["kind"] == "map" and not allow_local_provenance:
        image = manifest["image"]
        if not image.startswith("sha256:"):
            raise PackageError(
                f"manifest.json: map image provenance {image!r} must be a registry digest "
                f"(sha256:...) unless --allow-local-provenance is set"
            )
    return manifest


def validate_map_json_bytes(raw_bytes: bytes, source: str = "data/map.json") -> dict:
    """Validate ``data/map.json`` raw bytes against ``map-data/schema/map.schema.json``."""
    if len(raw_bytes) > MAX_MAP_JSON_BYTES:
        raise PackageError(
            f"{source} is {len(raw_bytes)} bytes, exceeding {MAX_MAP_JSON_BYTES} bytes"
        )
    try:
        data = json.loads(
            raw_bytes.decode("utf-8"),
            parse_constant=lambda c: (_ for _ in ()).throw(ValueError(f"non-finite {c}")),
        )
    except (UnicodeDecodeError, ValueError) as exc:
        raise PackageError(f"{source}: invalid JSON: {exc}") from exc

    schema = json.loads(MAP_SCHEMA_PATH.read_text(encoding="utf-8"))
    errors = sorted(
        Draft202012Validator(schema).iter_errors(data),
        key=lambda e: list(e.absolute_path),
    )
    if errors:
        err = errors[0]
        key = ".".join(str(p) for p in err.absolute_path) or "(top level)"
        raise PackageError(f"{source}: key {key}: {err.message}")
    assert isinstance(data, dict)

    for field in ("camera_bounds", "data_extent"):
        box = data[field]
        for k in ("west", "south", "east", "north"):
            val = float(box[k])
            if not math.isfinite(val):
                raise PackageError(f"{source}: {field}.{k} must be finite")
        if not (-180.0 <= box["west"] < box["east"] <= 180.0):
            raise PackageError(f"{source}: {field} must satisfy -180 <= west < east <= 180")
        if not (-_WEBMERCATOR_MAX_LAT <= box["south"] < box["north"] <= _WEBMERCATOR_MAX_LAT):
            raise PackageError(f"{source}: {field} must satisfy south < north within WebMercator")

    cam = data["camera_bounds"]
    ext = data["data_extent"]
    if not (
        ext["west"] <= cam["west"] + _FLOAT_TOLERANCE_DEG
        and ext["south"] <= cam["south"] + _FLOAT_TOLERANCE_DEG
        and ext["east"] >= cam["east"] - _FLOAT_TOLERANCE_DEG
        and ext["north"] >= cam["north"] - _FLOAT_TOLERANCE_DEG
    ):
        raise PackageError(f"{source}: data_extent must cover camera_bounds")

    if not (float(data["min_zoom"]) < float(data["max_zoom"])):
        raise PackageError(f"{source}: min_zoom must be strictly less than max_zoom")
    return data


def inspect_pmtiles_bytes(
    get_bytes: Callable[[int, int], bytes],
    total_size: int,
    label: str,
) -> tuple[dict, dict]:
    """Validate a PMTiles v3 archive via bounded ``get_bytes`` and return ``(header, metadata)``."""
    if total_size < 127:
        raise PackageError(
            f"{label}: file size {total_size} is smaller than 127-byte PMTiles v3 header"
        )
    raw_header = get_bytes(0, 127)
    if len(raw_header) != 127 or raw_header[:7] != b"PMTiles":
        raise PackageError(f"{label}: invalid PMTiles magic header")
    if raw_header[7] != 3:
        raise PackageError(f"{label}: unsupported PMTiles version {raw_header[7]}, expected 3")

    header = deserialize_header(raw_header)
    meta_offset = int(header["metadata_offset"])
    meta_bytes = int(header["metadata_length"])
    if meta_offset < 127:
        raise PackageError(f"{label}: metadata_offset {meta_offset} overlaps 127-byte header")
    if meta_bytes <= 0 or meta_bytes > MAX_PMTILES_METADATA_BYTES:
        raise PackageError(
            f"{label}: metadata_length {meta_bytes} outside 1..{MAX_PMTILES_METADATA_BYTES}"
        )
    for section, off_key, len_key in (
        ("root directory", "root_offset", "root_length"),
        ("json metadata", "metadata_offset", "metadata_length"),
        ("leaf directory", "leaf_directory_offset", "leaf_directory_length"),
        ("tile data", "tile_data_offset", "tile_data_length"),
    ):
        off = int(header[off_key])
        length = int(header[len_key])
        if off < 0 or length < 0 or off + length > total_size:
            raise PackageError(
                f"{label}: {section} slice ({off} + {length}) exceeds archive size {total_size}"
            )

    if int(header["addressed_tiles_count"]) <= 0:
        raise PackageError(f"{label}: archive contains 0 addressed tiles")
    if int(header["min_zoom"]) != 0:
        raise PackageError(f"{label}: header min_zoom is {header['min_zoom']}, expected 0")

    try:
        reader = Reader(get_bytes)
        meta = reader.metadata()
    except Exception as exc:
        raise PackageError(f"{label}: cannot parse PMTiles metadata: {exc}") from exc
    if not isinstance(meta, dict):
        raise PackageError(f"{label}: PMTiles metadata must be a JSON object")
    if "trial" in meta:
        raise PackageError(f"{label}: trial archive refused ('trial' key present in metadata)")
    if "variant" in meta or "mode" in meta:
        raise PackageError(f"{label}: trial 'variant'/'mode' keys present in metadata")
    if meta.get("tile_schema") != 1:
        raise PackageError(
            f"{label}: metadata tile_schema is {meta.get('tile_schema')!r}, expected 1"
        )
    if meta.get("name") != "seerah-map":
        raise PackageError(f"{label}: metadata name is {meta.get('name')!r}, expected 'seerah-map'")
    for prov_key in ("fingerprint", "tool_commit", "data_commit", "image"):
        val = meta.get(prov_key)
        if not isinstance(val, str) or not val or val == "unknown":
            raise PackageError(
                f"{label}: metadata {prov_key!r} must be a known provenance value, got {val!r}"
            )
    return header, meta


def inspect_pmtiles_file(path: Path, label: str | None = None) -> tuple[dict, dict]:
    """Inspect a PMTiles v3 file on disk."""
    name = label or str(path)
    try:
        size = path.stat().st_size
        with path.open("rb") as handle:
            source = MmapSource(handle)
            return inspect_pmtiles_bytes(source, size, name)
    except OSError as exc:
        raise PackageError(f"{name}: cannot read PMTiles file: {exc}") from exc


def verify_map_payload_consistency(
    *,
    map_doc: dict,
    base_header: dict,
    base_meta: dict,
    relief_header: dict,
    relief_meta: dict,
    expected_source_commit: str,
    expected_tool_commit: str,
    expected_image: str,
    expected_fingerprint: str | None = None,
) -> None:
    """Cross-check ``map.json``, ``base.pmtiles``, ``relief.pmtiles``, and package provenance."""
    # Base layer checks
    base_src = map_doc["sources"]["base"]
    if int(base_header["max_zoom"]) != int(base_src["max_zoom"]):
        raise PackageError(
            f"data/base.pmtiles: header max_zoom {base_header['max_zoom']} disagrees with "
            f"map.json sources.base.max_zoom {base_src['max_zoom']}"
        )
    if base_header["tile_type"] != TileType.MVT:
        raise PackageError(
            f"data/base.pmtiles: tile_type is {base_header['tile_type']}, expected MVT"
        )
    if base_header["tile_compression"] != Compression.GZIP:
        raise PackageError(
            f"data/base.pmtiles: tile_compression is {base_header['tile_compression']}, "
            f"expected GZIP"
        )
    vector_layers = base_meta.get("vector_layers")
    if not isinstance(vector_layers, list):
        raise PackageError("data/base.pmtiles: missing vector_layers list in metadata")
    layer_ids = {layer.get("id") for layer in vector_layers if isinstance(layer, dict)}
    if not ({"land", "coast"} <= layer_ids):
        raise PackageError(
            f"data/base.pmtiles: vector_layers must include 'land' and 'coast', "
            f"got {sorted(layer_ids)}"
        )
    declared_layers = set(base_src["layers"])
    if not (declared_layers <= layer_ids):
        raise PackageError(
            f"data/base.pmtiles: vector_layers {sorted(layer_ids)} missing layers "
            f"declared in map.json ({sorted(declared_layers - layer_ids)})"
        )

    # Relief layer checks
    relief_src = map_doc["sources"]["relief"]
    if int(relief_header["max_zoom"]) != int(relief_src["max_zoom"]):
        raise PackageError(
            f"data/relief.pmtiles: header max_zoom {relief_header['max_zoom']} disagrees with "
            f"map.json sources.relief.max_zoom {relief_src['max_zoom']}"
        )
    if relief_header["tile_compression"] != Compression.NONE:
        raise PackageError(
            f"data/relief.pmtiles: tile_compression is {relief_header['tile_compression']}, "
            f"expected NONE"
        )
    encoding = relief_src["encoding"]
    expected_tile_type = TileType.PNG if encoding == "terrarium-value" else TileType.WEBP
    if relief_header["tile_type"] != expected_tile_type:
        raise PackageError(
            f"data/relief.pmtiles: header tile_type {relief_header['tile_type']} disagrees "
            f"with encoding {encoding!r}"
        )
    if relief_meta.get("encoding") != encoding:
        raise PackageError(
            f"data/relief.pmtiles: metadata encoding {relief_meta.get('encoding')!r} disagrees "
            f"with map.json encoding {encoding!r}"
        )
    ext = map_doc["data_extent"]
    header_bounds = (
        relief_header["min_lon_e7"] / 1e7,
        relief_header["min_lat_e7"] / 1e7,
        relief_header["max_lon_e7"] / 1e7,
        relief_header["max_lat_e7"] / 1e7,
    )
    expected_bounds = (ext["west"], ext["south"], ext["east"], ext["north"])
    if any(
        abs(a - b) > _PMTILES_BOUNDS_TOLERANCE_DEG
        for a, b in zip(header_bounds, expected_bounds, strict=True)
    ):
        raise PackageError(
            f"data/relief.pmtiles: header bounds {header_bounds} disagree with "
            f"map.json data_extent {expected_bounds}"
        )

    # Provenance consistency across base.pmtiles, relief.pmtiles, and manifest/report
    if base_meta["fingerprint"] != relief_meta["fingerprint"]:
        raise PackageError(
            f"fingerprint mismatch between base.pmtiles ({base_meta['fingerprint']}) "
            f"and relief.pmtiles ({relief_meta['fingerprint']})"
        )
    if expected_fingerprint is not None and base_meta["fingerprint"] != expected_fingerprint:
        raise PackageError(
            f"PMTiles fingerprint {base_meta['fingerprint']} disagrees with "
            f"report.json fingerprint {expected_fingerprint}"
        )
    for label, meta in (("data/base.pmtiles", base_meta), ("data/relief.pmtiles", relief_meta)):
        if meta["data_commit"] != expected_source_commit:
            raise PackageError(
                f"{label}: data_commit {meta['data_commit']!r} disagrees with "
                f"source_commit {expected_source_commit!r}"
            )
        if meta["tool_commit"] != expected_tool_commit:
            raise PackageError(
                f"{label}: tool_commit {meta['tool_commit']!r} disagrees with "
                f"tool_commit {expected_tool_commit!r}"
            )
        if meta["image"] != expected_image:
            raise PackageError(
                f"{label}: image {meta['image']!r} disagrees with image {expected_image!r}"
            )


def _sha256_and_size(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        while chunk := handle.read(STREAM_CHUNK_BYTES):
            total += len(chunk)
            digest.update(chunk)
    return total, digest.hexdigest()


def _make_zip_info(arcname: str, compress_type: int) -> zipfile.ZipInfo:
    zinfo = zipfile.ZipInfo(filename=arcname, date_time=ZIP_DATE_TIME)
    zinfo.compress_type = compress_type
    zinfo.create_system = ZIP_CREATE_SYSTEM_UNIX
    zinfo.external_attr = ZIP_REGULAR_FILE_ATTR
    zinfo.internal_attr = 0
    zinfo.extra = b""
    zinfo.comment = b""
    zinfo.flag_bits = 0
    return zinfo


def _collect_data_files(data_dir: Path) -> list[tuple[str, Path]]:
    if not data_dir.is_dir() or data_dir.is_symlink():
        raise PackageError(f"data directory {data_dir} must be a real directory")
    collected: list[tuple[str, Path]] = []
    for root, dirs, files in os.walk(data_dir, followlinks=False):
        root_path = Path(root)
        for dname in dirs:
            dpath = root_path / dname
            if dpath.is_symlink():
                raise PackageError(f"symlink directory refused in data tree: {dpath}")
        for fname in files:
            fpath = root_path / fname
            try:
                st = fpath.lstat()
            except OSError as exc:
                raise PackageError(f"cannot stat {fpath}: {exc}") from exc
            if not stat.S_ISREG(st.st_mode):
                raise PackageError(f"non-regular file refused in data tree: {fpath}")
            rel = fpath.relative_to(data_dir).as_posix()
            arcname = f"data/{rel}"
            validate_entry_path(arcname, allow_root_manifest=False)
            collected.append((arcname, fpath))
    collected.sort(key=lambda item: item[0])
    validate_data_paths([arcname for arcname, _ in collected])
    return collected


def build_package(
    *,
    kind: str,
    data_dir: Path,
    version: int,
    min_app_version: int,
    key_id: str,
    private_key: Ed25519PrivateKey,
    source_commit: str,
    out_path: Path,
    created: str | None = None,
    tool_commit: str | None = None,
    image: str | None = None,
    compress_type: int = zipfile.ZIP_STORED,
    allow_local_provenance: bool = False,
) -> Path:
    """Build, sign, stream-zip, and self-verify a ``content`` or ``map`` package."""
    if kind not in ("content", "map"):
        raise PackageError(f"unsupported package kind {kind!r}")
    if kind == "map" and compress_type != zipfile.ZIP_STORED:
        raise PackageError("map packages must use ZIP_STORED for all entries")
    if compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
        raise PackageError(f"unsupported ZIP compression type {compress_type}")

    validate_key_id(key_id)
    created_ts = format_utc_timestamp() if created is None else validate_iso_timestamp(created)
    files_to_pack = _collect_data_files(Path(data_dir))

    manifest_files: list[dict] = []
    for arcname, fpath in files_to_pack:
        size, sha256 = _sha256_and_size(fpath)
        if size > MAX_RELEASE_FILE_BYTES:
            raise PackageError(
                f"{arcname} is {size} bytes, exceeding MAX_RELEASE_FILE_BYTES "
                f"({MAX_RELEASE_FILE_BYTES})"
            )
        manifest_files.append({"path": arcname, "size": size, "sha256": sha256})

    manifest: dict = {
        "created": created_ts,
        "files": manifest_files,
        "key_id": key_id,
        "kind": kind,
        "min_app_version": min_app_version,
        "source_commit": source_commit,
        "version": version,
    }
    if kind == "map":
        manifest["tool_commit"] = tool_commit
        manifest["image"] = image
    elif tool_commit is not None or image is not None:
        raise PackageError("content packages must not include tool_commit or image")

    validate_manifest_dict(manifest, allow_local_provenance=allow_local_provenance)
    raw_manifest = canonical_manifest_bytes(manifest)
    max_manifest = MAX_MAP_MANIFEST_BYTES if kind == "map" else MAX_CONTENT_MANIFEST_BYTES
    if len(raw_manifest) > max_manifest:
        raise PackageError(
            f"manifest.json is {len(raw_manifest)} bytes, exceeding {max_manifest} bytes"
        )
    raw_sig = sign_bytes(private_key, raw_manifest)

    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_zip = out_path.parent / f".tmp-{out_path.name}-{os.getpid()}"
    tmp_zip.unlink(missing_ok=True)

    try:
        with zipfile.ZipFile(tmp_zip, mode="w", allowZip64=True) as zf:
            # 1. manifest.json FIRST (always ZIP_STORED)
            zinfo_manifest = _make_zip_info(MANIFEST_NAME, zipfile.ZIP_STORED)
            with zf.open(zinfo_manifest, "w") as dest:
                dest.write(raw_manifest)

            # 2. manifest.sig SECOND (always ZIP_STORED)
            zinfo_sig = _make_zip_info(SIGNATURE_NAME, zipfile.ZIP_STORED)
            with zf.open(zinfo_sig, "w") as dest:
                dest.write(raw_sig)

            # 3. Lexicographically sorted data/* streamed in 1 MiB chunks
            for arcname, fpath in files_to_pack:
                zinfo_data = _make_zip_info(arcname, compress_type)
                with fpath.open("rb") as src, zf.open(zinfo_data, "w") as dest:
                    while chunk := src.read(STREAM_CHUNK_BYTES):
                        dest.write(chunk)

        final_size = tmp_zip.stat().st_size
        if final_size > MAX_RELEASE_FILE_BYTES:
            raise PackageError(
                f"package {out_path.name} is {final_size} bytes, exceeding the 2 GiB GitHub "
                f"Release limit ({MAX_RELEASE_FILE_BYTES} bytes)"
            )

        # Self-verification with isolated single-key verifier before atomic publish
        self_trusted = (
            TrustedKey(
                key_id=key_id,
                public_key_bytes=public_key_bytes_from_private(private_key),
            ),
        )
        verify_package(
            tmp_zip,
            trusted_keys=self_trusted,
            expected_kind=kind,
            allow_local_provenance=allow_local_provenance,
        )
        tmp_zip.replace(out_path)
    except Exception:
        tmp_zip.unlink(missing_ok=True)
        raise

    return out_path


def _check_previous_manifest(
    previous_manifest_path: Path,
    *,
    expected_kind: str,
    new_version: int,
) -> None:
    try:
        prev = json.loads(Path(previous_manifest_path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PackageError(
            f"cannot read previous manifest {previous_manifest_path}: {exc}"
        ) from exc
    validate_manifest_dict(prev, allow_local_provenance=True)
    if prev["kind"] != expected_kind:
        raise PackageError(
            f"previous manifest kind is {prev['kind']!r}, expected {expected_kind!r}"
        )
    if new_version <= int(prev["version"]):
        raise PackageError(
            f"new version {new_version} must be strictly greater than previous version "
            f"{prev['version']}"
        )


def pack_map_staging(
    *,
    staging_dir: Path,
    version: int,
    min_app_version: int,
    key_id: str,
    private_key: Ed25519PrivateKey,
    out_dir: Path,
    created: str | None = None,
    previous_manifest_path: Path | None = None,
    allow_local_provenance: bool = False,
    force: bool = False,
) -> Path:
    """Validate a production map staging folder and build ``seerah-map-<version>.zip``."""
    staging_dir = Path(staging_dir)
    if not staging_dir.is_dir():
        raise PackageError(f"staging directory {staging_dir} does not exist")

    out_dir = Path(out_dir)
    out_path = out_dir / f"seerah-map-{version}.zip"
    if out_path.exists() and not force:
        raise PackageError(
            f"refusing to overwrite existing package {out_path} (pass --force to overwrite)"
        )

    if previous_manifest_path is not None:
        _check_previous_manifest(previous_manifest_path, expected_kind="map", new_version=version)

    # Goal 9 guard: refuse if metadata.json exists anywhere under staging_dir
    stray_meta = list(staging_dir.rglob(METADATA_NAME))
    if stray_meta:
        raise PackageError(
            f"trial or sidecar metadata.json refused in production staging: {stray_meta[0]}"
        )

    report_path = staging_dir / REPORT_NAME
    if not report_path.is_file():
        raise PackageError(f"missing {REPORT_NAME} in {staging_dir}")
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PackageError(f"cannot read {report_path}: {exc}") from exc
    if not isinstance(report, dict):
        raise PackageError(f"{report_path}: report must be a JSON object")
    if report.get("purpose") != "production":
        raise PackageError(
            f"{report_path}: report purpose must be 'production', got {report.get('purpose')!r}"
        )
    prov = report.get("provenance")
    if not isinstance(prov, dict):
        raise PackageError(f"{report_path}: missing provenance object")

    source_commit = prov.get("data_commit")
    tool_commit = prov.get("tool_commit")
    image = prov.get("image")
    fingerprint = prov.get("fingerprint")
    for field_name, val in (
        ("data_commit", source_commit),
        ("tool_commit", tool_commit),
        ("image", image),
        ("fingerprint", fingerprint),
    ):
        if not isinstance(val, str) or not val or val == "unknown":
            raise PackageError(
                f"{report_path}: provenance.{field_name} must be known for production packaging, "
                f"got {val!r}"
            )
    if not _COMMIT_RE.match(source_commit):
        raise PackageError(
            f"{report_path}: provenance.data_commit {source_commit!r} must be a 40/64-char hex SHA"
        )
    if not _COMMIT_RE.match(tool_commit):
        raise PackageError(
            f"{report_path}: provenance.tool_commit {tool_commit!r} must be a 40/64-char hex SHA"
        )
    if not allow_local_provenance and not image.startswith("sha256:"):
        raise PackageError(
            f"{report_path}: provenance.image {image!r} must be a registry digest (sha256:...) "
            f"unless --allow-local-provenance is set"
        )

    data_dir = staging_dir / "data"
    collected = _collect_data_files(data_dir)
    actual_paths = tuple(arcname for arcname, _ in collected)
    if actual_paths != MAP_REQUIRED_DATA_FILES:
        raise PackageError(
            f"{data_dir}: expected exact files {list(MAP_REQUIRED_DATA_FILES)}, "
            f"found {list(actual_paths)}"
        )

    with (data_dir / "map.json").open("rb") as map_stream:
        raw_map_json = map_stream.read(MAX_MAP_JSON_BYTES + 1)
    map_doc = validate_map_json_bytes(raw_map_json)
    base_header, base_meta = inspect_pmtiles_file(data_dir / "base.pmtiles", "data/base.pmtiles")
    relief_header, relief_meta = inspect_pmtiles_file(
        data_dir / "relief.pmtiles", "data/relief.pmtiles"
    )
    verify_map_payload_consistency(
        map_doc=map_doc,
        base_header=base_header,
        base_meta=base_meta,
        relief_header=relief_header,
        relief_meta=relief_meta,
        expected_source_commit=source_commit,
        expected_tool_commit=tool_commit,
        expected_image=image,
        expected_fingerprint=fingerprint,
    )

    return build_package(
        kind="map",
        data_dir=data_dir,
        version=version,
        min_app_version=min_app_version,
        key_id=key_id,
        private_key=private_key,
        source_commit=source_commit,
        tool_commit=tool_commit,
        image=image,
        out_path=out_path,
        created=created,
        compress_type=zipfile.ZIP_STORED,
        allow_local_provenance=allow_local_provenance,
    )


def _check_end_record(raw_handle, total_zip_size: int, entry_count: int) -> int:
    """Check the archive ends with a plain end-of-central-directory record; return the CD offset.

    The record must be the last 22 bytes (no comment, no trailing bytes, one disk, no ZIP64),
    and the central directory must end exactly where the record starts.
    """
    if total_zip_size < _EOCD_STRUCT.size:
        raise PackageError("ZIP archive is too small to hold an end-of-central-directory record")
    raw_handle.seek(total_zip_size - _EOCD_STRUCT.size)
    (
        magic,
        disk,
        cd_disk,
        disk_entries,
        total_entries,
        cd_size,
        cd_offset,
        comment_len,
    ) = _EOCD_STRUCT.unpack(raw_handle.read(_EOCD_STRUCT.size))
    if magic != _EOCD_MAGIC or comment_len != 0:
        raise PackageError(
            "ZIP archive must end with an end-of-central-directory record and no trailing bytes"
        )
    if (disk, cd_disk) != (0, 0) or disk_entries != total_entries or total_entries != entry_count:
        raise PackageError("ZIP archive must be a single-disk archive without ZIP64 records")
    if cd_offset + cd_size != total_zip_size - _EOCD_STRUCT.size:
        raise PackageError("ZIP central directory must end where the end record starts")
    return cd_offset


def _check_zip_container_and_lfh(
    zf: zipfile.ZipFile,
    raw_handle,
    total_zip_size: int,
    *,
    require_all_stored: bool,
) -> dict[str, int]:
    """Harden the ZIP container and return ``{filename: data_offset}`` for every entry.

    Besides each entry's own checks, the layout must be readable front to back by a
    streaming reader: the first entry starts at byte 0, every entry starts where the
    previous one's data ends (no data descriptors, no extra fields, no gaps), and the
    central directory starts right after the last entry.
    """
    if zf.comment:
        raise PackageError("ZIP archive comment must be empty")
    infolist = zf.infolist()
    if len(infolist) < 3:
        raise PackageError("ZIP archive must contain manifest.json, manifest.sig, and data/*")
    names = [info.filename for info in infolist]
    if len(names) != len(set(names)):
        raise PackageError("ZIP archive contains duplicate entry names in central directory")

    data_offsets: dict[str, int] = {}
    for info in infolist:
        validate_entry_path(info.filename, allow_root_manifest=True)
        if info.flag_bits & _FLAG_ENCRYPTED:
            raise PackageError(f"ZIP entry {info.filename!r} is encrypted")
        if info.flag_bits & _FLAG_DATA_DESCRIPTOR:
            raise PackageError(f"ZIP entry {info.filename!r} uses a data descriptor")
        if info.extra:
            raise PackageError(f"ZIP entry {info.filename!r} has Central Directory extra fields")
        if info.is_dir() or (info.external_attr & 0x10):
            raise PackageError(f"ZIP directory entry refused: {info.filename!r}")
        unix_mode = info.external_attr >> 16
        file_type = stat.S_IFMT(unix_mode)
        if file_type not in (0, stat.S_IFREG):
            raise PackageError(
                f"ZIP entry {info.filename!r} has non-regular Unix file mode {oct(unix_mode)}"
            )
        if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise PackageError(
                f"ZIP entry {info.filename!r} uses unsupported compression {info.compress_type}"
            )
        must_be_stored = require_all_stored or info.filename in (MANIFEST_NAME, SIGNATURE_NAME)
        if must_be_stored:
            if info.compress_type != zipfile.ZIP_STORED:
                raise PackageError(f"ZIP entry {info.filename!r} must use ZIP_STORED")
            if info.file_size != info.compress_size:
                raise PackageError(
                    f"ZIP_STORED entry {info.filename!r} has mismatched file_size "
                    f"({info.file_size}) and compress_size ({info.compress_size})"
                )

        # Verify Local File Header (LFH) matches Central Directory (CD)
        if info.header_offset < 0 or info.header_offset + _LFH_STRUCT.size > total_zip_size:
            raise PackageError(
                f"ZIP entry {info.filename!r} has out-of-range header_offset {info.header_offset}"
            )
        raw_handle.seek(info.header_offset)
        lfh_raw = raw_handle.read(_LFH_STRUCT.size)
        if len(lfh_raw) != _LFH_STRUCT.size:
            raise PackageError(f"ZIP entry {info.filename!r}: truncated Local File Header")
        (
            lfh_magic,
            _ver,
            lfh_flags,
            lfh_compress,
            _time,
            _date,
            _crc,
            lfh_comp_size,
            lfh_uncomp_size,
            lfh_name_len,
            lfh_extra_len,
        ) = _LFH_STRUCT.unpack(lfh_raw)
        if lfh_magic != _LFH_MAGIC:
            raise PackageError(f"ZIP entry {info.filename!r}: invalid Local File Header magic")
        if lfh_flags & _FLAG_ENCRYPTED:
            raise PackageError(f"ZIP entry {info.filename!r}: LFH encryption flag set")
        if lfh_flags & _FLAG_DATA_DESCRIPTOR:
            raise PackageError(f"ZIP entry {info.filename!r}: LFH data descriptor flag set")
        if lfh_extra_len != 0:
            raise PackageError(f"ZIP entry {info.filename!r}: LFH has extra fields")
        if lfh_compress != info.compress_type:
            raise PackageError(
                f"ZIP entry {info.filename!r}: LFH compression ({lfh_compress}) disagrees "
                f"with Central Directory ({info.compress_type})"
            )
        lfh_name = raw_handle.read(lfh_name_len)
        if lfh_name != info.filename.encode("utf-8"):
            raise PackageError(
                f"ZIP entry {info.filename!r}: LFH filename {lfh_name!r} disagrees with "
                f"Central Directory"
            )
        if lfh_comp_size != info.compress_size:
            raise PackageError(
                f"ZIP entry {info.filename!r}: LFH compressed size ({lfh_comp_size}) "
                f"disagrees with Central Directory ({info.compress_size})"
            )
        if lfh_uncomp_size != info.file_size:
            raise PackageError(
                f"ZIP entry {info.filename!r}: LFH uncompressed size ({lfh_uncomp_size}) "
                f"disagrees with Central Directory ({info.file_size})"
            )
        data_offset = info.header_offset + _LFH_STRUCT.size + lfh_name_len
        if data_offset + info.compress_size > total_zip_size:
            raise PackageError(f"ZIP entry {info.filename!r}: data slice exceeds archive bounds")
        data_offsets[info.filename] = data_offset

    if infolist[0].filename != MANIFEST_NAME or infolist[1].filename != SIGNATURE_NAME:
        raise PackageError(
            f"ZIP archive must place {MANIFEST_NAME!r} first and {SIGNATURE_NAME!r} second "
            f"for streaming verification, found {infolist[0].filename!r} "
            f"and {infolist[1].filename!r}"
        )
    next_offset = 0
    for info in infolist:
        if info.header_offset != next_offset:
            raise PackageError(
                f"ZIP entry {info.filename!r} starts at byte {info.header_offset}, expected "
                f"{next_offset}: entries must be contiguous from the start of the file"
            )
        next_offset = data_offsets[info.filename] + info.compress_size
    if next_offset != _check_end_record(raw_handle, total_zip_size, len(infolist)):
        raise PackageError("ZIP central directory must start right after the last entry")
    return data_offsets


def verify_package(
    package_path: Path,
    *,
    trusted_keys: Sequence[TrustedKey],
    expected_kind: str | None = None,
    allow_local_provenance: bool = False,
) -> dict:
    """Verify a signed package ZIP (verify-before-parse) and return its manifest."""
    package_path = Path(package_path)
    if not package_path.is_file():
        raise PackageError(f"package {package_path} does not exist")
    total_zip_size = package_path.stat().st_size
    if total_zip_size > MAX_RELEASE_FILE_BYTES:
        raise PackageError(
            f"package {package_path} is {total_zip_size} bytes, exceeding "
            f"MAX_RELEASE_FILE_BYTES ({MAX_RELEASE_FILE_BYTES})"
        )
    if expected_kind is not None and expected_kind not in ("content", "map"):
        raise PackageError(f"invalid expected_kind {expected_kind!r}")

    try:
        with package_path.open("rb") as raw_handle, zipfile.ZipFile(raw_handle, "r") as zf:
            data_offsets = _check_zip_container_and_lfh(
                zf,
                raw_handle,
                total_zip_size,
                require_all_stored=(expected_kind == "map"),
            )

            # Bounded read of manifest.sig (must be 64 bytes)
            sig_info = zf.getinfo(SIGNATURE_NAME)
            if sig_info.file_size != RAW_SIGNATURE_BYTES:
                raise PackageError(
                    f"manifest.sig must be {RAW_SIGNATURE_BYTES} bytes, got {sig_info.file_size}"
                )
            with zf.open(sig_info, "r") as sig_stream:
                raw_sig = sig_stream.read(RAW_SIGNATURE_BYTES + 1)
            if len(raw_sig) != RAW_SIGNATURE_BYTES:
                raise PackageError(
                    f"manifest.sig read {len(raw_sig)} bytes, expected {RAW_SIGNATURE_BYTES}"
                )

            # Bounded read of manifest.json BEFORE any JSON parse
            max_pre_parse = (
                MAX_MAP_MANIFEST_BYTES if expected_kind == "map" else MAX_CONTENT_MANIFEST_BYTES
            )
            manifest_info = zf.getinfo(MANIFEST_NAME)
            if manifest_info.file_size <= 0 or manifest_info.file_size > max_pre_parse:
                raise PackageError(
                    f"manifest.json size {manifest_info.file_size} outside 1..{max_pre_parse}"
                )
            with zf.open(manifest_info, "r") as manifest_stream:
                raw_manifest = manifest_stream.read(max_pre_parse + 1)
            if len(raw_manifest) > max_pre_parse or len(raw_manifest) != manifest_info.file_size:
                raise PackageError("manifest.json byte length disagrees with central directory")

            # Verify Ed25519 signature via isolated single-key verifiers BEFORE json.loads()
            try:
                verifying_key_id = verify_signature_isolated(raw_sig, raw_manifest, trusted_keys)
            except SigningError as exc:
                raise PackageError(str(exc)) from exc

            # Only after cryptographic verification succeeds do we parse manifest.json
            try:
                manifest_obj = json.loads(raw_manifest.decode("utf-8"))
            except (UnicodeDecodeError, ValueError) as exc:
                raise PackageError(f"manifest.json is not valid UTF-8 JSON: {exc}") from exc

            manifest = validate_manifest_dict(
                manifest_obj, allow_local_provenance=allow_local_provenance
            )
            if manifest["key_id"] != verifying_key_id:
                raise PackageError(
                    f"key_id binding mismatch: signature verified with key {verifying_key_id!r}, "
                    f"but manifest declares key_id {manifest['key_id']!r}"
                )
            if expected_kind is not None and manifest["kind"] != expected_kind:
                raise PackageError(
                    f"package kind is {manifest['kind']!r}, expected {expected_kind!r}"
                )
            if manifest["kind"] == "map":
                if len(raw_manifest) > MAX_MAP_MANIFEST_BYTES:
                    raise PackageError(
                        f"map manifest.json is {len(raw_manifest)} bytes, exceeding "
                        f"{MAX_MAP_MANIFEST_BYTES} bytes"
                    )
                for info in zf.infolist():
                    if (
                        info.compress_type != zipfile.ZIP_STORED
                        or info.file_size != info.compress_size
                    ):
                        raise PackageError(
                            f"map package entry {info.filename!r} must use ZIP_STORED"
                        )

            expected_order = [
                MANIFEST_NAME,
                SIGNATURE_NAME,
                *[entry["path"] for entry in manifest["files"]],
            ]
            actual_order = [info.filename for info in zf.infolist()]
            if set(actual_order) != set(expected_order):
                raise PackageError(
                    f"ZIP entries {sorted(actual_order)} disagree with manifest files "
                    f"{sorted(expected_order)}"
                )
            if actual_order != expected_order:
                raise PackageError(
                    f"ZIP entry order {actual_order} must be [manifest.json, manifest.sig, "
                    f"sorted data/*]"
                )

            # Stream and verify every payload entry in 1 MiB chunks
            for entry in manifest["files"]:
                path = entry["path"]
                expected_size = int(entry["size"])
                expected_sha = entry["sha256"]
                info = zf.getinfo(path)
                if info.file_size != expected_size:
                    raise PackageError(
                        f"{path}: ZIP header size {info.file_size} disagrees with "
                        f"manifest size {expected_size}"
                    )
                digest = hashlib.sha256()
                read_total = 0
                with zf.open(info, "r") as stream:
                    while chunk := stream.read(
                        min(STREAM_CHUNK_BYTES, expected_size - read_total + 1)
                    ):
                        read_total += len(chunk)
                        if read_total > expected_size:
                            raise PackageError(
                                f"{path}: entry stream exceeded declared size {expected_size}"
                            )
                        digest.update(chunk)
                if read_total != expected_size:
                    raise PackageError(f"{path}: read {read_total} bytes, expected {expected_size}")
                if digest.hexdigest() != expected_sha:
                    raise PackageError(
                        f"{path}: SHA-256 mismatch (expected {expected_sha}, "
                        f"got {digest.hexdigest()})"
                    )

            # Deep map payload verification (map.json + seekable ZIP_STORED PMTiles v3 slices)
            if manifest["kind"] == "map":
                file_paths = tuple(entry["path"] for entry in manifest["files"])
                if file_paths != MAP_REQUIRED_DATA_FILES:
                    raise PackageError(
                        f"map package must contain exact files {list(MAP_REQUIRED_DATA_FILES)}, "
                        f"found {list(file_paths)}"
                    )
                with zf.open("data/map.json", "r") as map_stream:
                    raw_map_json = map_stream.read(MAX_MAP_JSON_BYTES + 1)
                map_doc = validate_map_json_bytes(raw_map_json)

                def make_slice_reader(arcname: str) -> tuple[Callable[[int, int], bytes], int]:
                    info = zf.getinfo(arcname)
                    base_offset = data_offsets[arcname]
                    limit = info.file_size

                    def get_bytes(offset: int, length: int) -> bytes:
                        if offset < 0 or length < 0 or offset + length > limit:
                            raise PackageError(
                                f"{arcname}: read ({offset} + {length}) out of bounds ({limit})"
                            )
                        raw_handle.seek(base_offset + offset)
                        return raw_handle.read(length)

                    return get_bytes, limit

                base_reader, base_size = make_slice_reader("data/base.pmtiles")
                base_header, base_meta = inspect_pmtiles_bytes(
                    base_reader, base_size, "data/base.pmtiles"
                )
                relief_reader, relief_size = make_slice_reader("data/relief.pmtiles")
                relief_header, relief_meta = inspect_pmtiles_bytes(
                    relief_reader, relief_size, "data/relief.pmtiles"
                )
                verify_map_payload_consistency(
                    map_doc=map_doc,
                    base_header=base_header,
                    base_meta=base_meta,
                    relief_header=relief_header,
                    relief_meta=relief_meta,
                    expected_source_commit=manifest["source_commit"],
                    expected_tool_commit=manifest["tool_commit"],
                    expected_image=manifest["image"],
                )
            return manifest
    except zipfile.BadZipFile as exc:
        raise PackageError(f"{package_path}: corrupt ZIP archive: {exc}") from exc
    except OSError as exc:
        raise PackageError(f"{package_path}: I/O error: {exc}") from exc
