"""Deterministically generate golden signed ZIP fixtures for Android unit tests."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
import tempfile
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from pmtiles.tile import Compression, TileType, deserialize_header, zxy_to_tileid
from pmtiles.writer import write as pmtiles_write

_REPO_ROOT = Path(__file__).resolve().parents[3]
_PACKAGES_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_PACKAGES_SRC) not in sys.path:
    sys.path.insert(0, str(_PACKAGES_SRC))

from seerah_packages import archive, signing  # noqa: E402

# RFC 8032 Section 7.1 TEST 1 test vector (public test vector; never for production use).
RFC8032_TEST1_SEED_HEX = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
FIXTURE_KEY_ID = "rfc8032-test-1"
FIXTURE_CREATED = "2026-01-01T00:00:00Z"
FIXTURE_SOURCE_COMMIT = "a" * 40
FIXTURE_TOOL_COMMIT = "b" * 40
FIXTURE_IMAGE_DIGEST = "sha256:" + "c" * 64
FIXTURE_FINGERPRINT = "d" * 64
FIXTURE_EXTENT = (33.0, 11.0, 61.0, 33.0)

DEFAULT_FIXTURES_DIR = (
    _REPO_ROOT / "android" / "core" / "content" / "src" / "test" / "resources" / "fixtures"
)


def _valid_map_json() -> dict:
    return {
        "tile_schema": 1,
        "camera_bounds": {"west": 34.0, "south": 12.0, "east": 60.0, "north": 32.0},
        "data_extent": {"west": 33.0, "south": 11.0, "east": 61.0, "north": 33.0},
        "min_zoom": 4.0,
        "max_zoom": 14.0,
        "sources": {
            "base": {
                "file": "base.pmtiles",
                "layers": ["land", "coast"],
                "min_zoom": 0,
                "max_zoom": 10,
            },
            "relief": {
                "file": "relief.pmtiles",
                "encoding": "terrarium-value",
                "tile_format": "png",
                "tile_size": 256,
                "min_zoom": 0,
                "max_zoom": 12,
            },
        },
        "credits": [
            {
                "id": "copernicus-worlddem-30",
                "source": "Copernicus DEM GLO-30 and Water Body Mask",
                "attribution": (
                    "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus "
                    "Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European "
                    "Union and ESA; all rights reserved."
                ),
                "liability": (
                    "The organisations in charge of the Copernicus programme by law or by "
                    "delegation do not incur any liability for any use of the Copernicus "
                    "WorldDEM-30."
                ),
            },
            {
                "id": "natural-earth",
                "source": "Natural Earth",
                "attribution": "Made with Natural Earth. Public domain.",
            },
        ],
        "reconstructed_areas": [],
    }


def _normalize_pmtiles_gzip_os(path: Path) -> None:
    """Pin RFC 1952 gzip OS header byte to 0xFF (unknown) for Python/zlib portability."""
    raw = bytearray(path.read_bytes())
    header = deserialize_header(bytes(raw[:127]))
    for offset_key, length_key in (
        ("root_offset", "root_length"),
        ("metadata_offset", "metadata_length"),
        ("leaf_directory_offset", "leaf_directory_length"),
    ):
        offset = header[offset_key]
        length = header[length_key]
        if length >= 10 and raw[offset : offset + 2] == b"\x1f\x8b":
            raw[offset + 9] = 0xFF
    path.write_bytes(bytes(raw))


def _write_minimal_pmtiles(
    path: Path,
    *,
    tile_type: TileType,
    tile_compression: Compression,
    max_zoom: int,
    metadata: dict,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with pmtiles_write(str(path)) as writer:
        writer.write_tile(zxy_to_tileid(0, 0, 0), b"fixture-tile-0")
        writer.write_tile(zxy_to_tileid(max_zoom, 0, 0), b"fixture-tile-max")
        writer.finalize(
            {
                "tile_type": tile_type,
                "tile_compression": tile_compression,
                "min_zoom": 0,
                "max_zoom": max_zoom,
                "min_lon_e7": int(FIXTURE_EXTENT[0] * 1e7),
                "min_lat_e7": int(FIXTURE_EXTENT[1] * 1e7),
                "max_lon_e7": int(FIXTURE_EXTENT[2] * 1e7),
                "max_lat_e7": int(FIXTURE_EXTENT[3] * 1e7),
                "center_zoom": 6,
                "center_lon_e7": int(((FIXTURE_EXTENT[0] + FIXTURE_EXTENT[2]) / 2) * 1e7),
                "center_lat_e7": int(((FIXTURE_EXTENT[1] + FIXTURE_EXTENT[3]) / 2) * 1e7),
            },
            metadata,
        )
    _normalize_pmtiles_gzip_os(path)


def _write_trial_rejected_map_zip(
    *,
    data_dir: Path,
    private_key: Ed25519PrivateKey,
    out_path: Path,
) -> None:
    """Build a validly signed ZIP whose base.pmtiles metadata has ``trial: true``.

    ``archive.build_package`` self-verifies via ``verify_package``, which intentionally rejects
    ``trial: true`` archives, so this negative fixture is assembled directly with ``_make_zip_info``
    and signed with ``sign_bytes``.
    """
    entries: list[tuple[str, Path]] = []
    manifest_files: list[dict] = []
    for path in sorted(data_dir.rglob("*")):
        if not path.is_file():
            continue
        arcname = f"data/{path.relative_to(data_dir).as_posix()}"
        raw = path.read_bytes()
        entries.append((arcname, path))
        manifest_files.append(
            {
                "path": arcname,
                "size": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
        )

    manifest = {
        "kind": "map",
        "version": 1,
        "min_app_version": 1,
        "key_id": FIXTURE_KEY_ID,
        "source_commit": FIXTURE_SOURCE_COMMIT,
        "tool_commit": FIXTURE_TOOL_COMMIT,
        "image": FIXTURE_IMAGE_DIGEST,
        "created": FIXTURE_CREATED,
        "files": manifest_files,
    }
    raw_manifest = archive.canonical_manifest_bytes(manifest)
    raw_sig = signing.sign_bytes(private_key, raw_manifest)

    make_zip_info = archive._make_zip_info  # noqa: SLF001
    with zipfile.ZipFile(out_path, mode="w") as zf:
        with zf.open(make_zip_info(archive.MANIFEST_NAME, zipfile.ZIP_STORED), "w") as dst:
            dst.write(raw_manifest)
        with zf.open(make_zip_info(archive.SIGNATURE_NAME, zipfile.ZIP_STORED), "w") as dst:
            dst.write(raw_sig)
        for arcname, fpath in entries:
            with zf.open(make_zip_info(arcname, zipfile.ZIP_STORED), "w") as dst:
                dst.write(fpath.read_bytes())


def generate_fixtures(out_dir: Path) -> None:
    """Write ``trusted-keys.json`` and deterministic signed golden test ZIPs into ``out_dir``."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    private_key = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(RFC8032_TEST1_SEED_HEX))
    pub_bytes = signing.public_key_bytes_from_private(private_key)
    keyset_doc = {
        "keys": [
            {
                "key_id": FIXTURE_KEY_ID,
                "algorithm": "Ed25519",
                "tink_variant": "NO_PREFIX",
                "public_key_base64": base64.b64encode(pub_bytes).decode("ascii"),
            }
        ]
    }
    (out_dir / "trusted-keys.json").write_text(
        json.dumps(keyset_doc, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)

        # 1. valid-map-v1.zip
        map_data = work / "valid-map" / "data"
        map_data.mkdir(parents=True)
        (map_data / "map.json").write_text(
            json.dumps(_valid_map_json(), indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        _write_minimal_pmtiles(
            map_data / "base.pmtiles",
            tile_type=TileType.MVT,
            tile_compression=Compression.GZIP,
            max_zoom=10,
            metadata={
                "name": "seerah-map",
                "tile_schema": 1,
                "fingerprint": FIXTURE_FINGERPRINT,
                "tool_commit": FIXTURE_TOOL_COMMIT,
                "data_commit": FIXTURE_SOURCE_COMMIT,
                "image": FIXTURE_IMAGE_DIGEST,
                "vector_layers": [{"id": "land"}, {"id": "coast"}],
            },
        )
        _write_minimal_pmtiles(
            map_data / "relief.pmtiles",
            tile_type=TileType.PNG,
            tile_compression=Compression.NONE,
            max_zoom=12,
            metadata={
                "name": "seerah-map",
                "tile_schema": 1,
                "encoding": "terrarium-value",
                "fingerprint": FIXTURE_FINGERPRINT,
                "tool_commit": FIXTURE_TOOL_COMMIT,
                "data_commit": FIXTURE_SOURCE_COMMIT,
                "image": FIXTURE_IMAGE_DIGEST,
            },
        )
        archive.build_package(
            kind="map",
            data_dir=map_data,
            version=1,
            min_app_version=1,
            key_id=FIXTURE_KEY_ID,
            private_key=private_key,
            source_commit=FIXTURE_SOURCE_COMMIT,
            tool_commit=FIXTURE_TOOL_COMMIT,
            image=FIXTURE_IMAGE_DIGEST,
            out_path=out_dir / "valid-map-v1.zip",
            created=FIXTURE_CREATED,
        )

        # 2. valid-content-v1.zip (ZIP_DEFLATED)
        content_data = work / "valid-content" / "data"
        content_data.mkdir(parents=True)
        (content_data / "index.json").write_text(
            json.dumps({"events": ["taif"], "schema": 1}, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        archive.build_package(
            kind="content",
            data_dir=content_data,
            version=1,
            min_app_version=1,
            key_id=FIXTURE_KEY_ID,
            private_key=private_key,
            source_commit=FIXTURE_SOURCE_COMMIT,
            out_path=out_dir / "valid-content-v1.zip",
            created=FIXTURE_CREATED,
        )

        # 3. trial-rejected-map-v1.zip
        trial_data = work / "trial-map" / "data"
        trial_data.mkdir(parents=True)
        (trial_data / "map.json").write_bytes((map_data / "map.json").read_bytes())
        (trial_data / "relief.pmtiles").write_bytes((map_data / "relief.pmtiles").read_bytes())
        _write_minimal_pmtiles(
            trial_data / "base.pmtiles",
            tile_type=TileType.MVT,
            tile_compression=Compression.GZIP,
            max_zoom=10,
            metadata={
                "name": "seerah-map",
                "tile_schema": 1,
                "trial": True,
                "fingerprint": FIXTURE_FINGERPRINT,
                "tool_commit": FIXTURE_TOOL_COMMIT,
                "data_commit": FIXTURE_SOURCE_COMMIT,
                "image": FIXTURE_IMAGE_DIGEST,
                "vector_layers": [{"id": "land"}, {"id": "coast"}],
            },
        )
        _write_trial_rejected_map_zip(
            data_dir=trial_data,
            private_key=private_key,
            out_path=out_dir / "trial-rejected-map-v1.zip",
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=DEFAULT_FIXTURES_DIR,
        help=f"Output fixtures directory (default: {DEFAULT_FIXTURES_DIR})",
    )
    args = parser.parse_args()
    generate_fixtures(args.out_dir)


if __name__ == "__main__":
    main()
