"""Tests for deterministic ZIP packaging, Goal 9 guards, ZIP hardening, and CLI."""

import hashlib
import json
import os
import shutil
import stat
import struct
import zipfile
from collections.abc import Callable
from pathlib import Path
from typing import NamedTuple

import pytest
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import write as pmtiles_write
from seerah_packages import archive, cli, signing
from seerah_packages.archive import PackageError

SOURCE_COMMIT = "a" * 40
TOOL_COMMIT = "b" * 40
IMAGE_DIGEST = "sha256:" + "c" * 64
FINGERPRINT = "d" * 64
CREATED = "2026-11-23T12:00:00Z"
PASSPHRASE = b"test-passphrase"
DATA_EXTENT = (33.0, 11.0, 61.0, 33.0)

COPERNICUS_ATTRIBUTION = (
    "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence "
    "and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; "
    "all rights reserved."
)
COPERNICUS_LIABILITY = (
    "The organisations in charge of the Copernicus programme by law or by delegation do "
    "not incur any liability for any use of the Copernicus WorldDEM-30."
)
NATURAL_EARTH_ATTRIBUTION = "Made with Natural Earth. Public domain."


class Keypair(NamedTuple):
    key_id: str
    private_key: object
    trusted: tuple[signing.TrustedKey, ...]
    pem: Path
    pub: Path


class ContentPackage(NamedTuple):
    path: Path
    raw_manifest: bytes
    raw_sig: bytes


@pytest.fixture
def keypair(tmp_path: Path) -> Keypair:
    pem, pub = signing.generate_keypair("k1", tmp_path / "keys", PASSPHRASE)
    return Keypair(
        "k1", signing.load_private_key(pem, PASSPHRASE), signing.load_trusted_keys(pub), pem, pub
    )


@pytest.fixture
def content_package(tmp_path: Path, keypair: Keypair) -> ContentPackage:
    data = tmp_path / "content-data"
    data.mkdir()
    (data / "ok.json").write_bytes(b"{}")
    path = archive.build_package(
        kind="content",
        data_dir=data,
        version=1,
        min_app_version=1,
        key_id=keypair.key_id,
        private_key=keypair.private_key,
        source_commit=SOURCE_COMMIT,
        out_path=tmp_path / "good.zip",
        created=CREATED,
    )
    with zipfile.ZipFile(path) as zf:
        return ContentPackage(path, zf.read("manifest.json"), zf.read("manifest.sig"))


def _write_minimal_pmtiles(
    path: Path,
    *,
    tile_type: TileType,
    tile_compression: Compression,
    max_zoom: int = 12,
    bounds: tuple[float, float, float, float] = DATA_EXTENT,
    metadata: dict,
) -> None:
    """Create a valid PMTiles v3 archive in pure Python using ``pmtiles.writer``."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with pmtiles_write(str(path)) as writer:
        writer.write_tile(zxy_to_tileid(0, 0, 0), b"synthetic-tile-bytes")
        writer.write_tile(zxy_to_tileid(max_zoom, 0, 0), b"synthetic-tile-bytes")
        header = {
            "tile_type": tile_type,
            "tile_compression": tile_compression,
            "min_zoom": 0,
            "max_zoom": max_zoom,
            "min_lon_e7": int(bounds[0] * 1e7),
            "min_lat_e7": int(bounds[1] * 1e7),
            "max_lon_e7": int(bounds[2] * 1e7),
            "max_lat_e7": int(bounds[3] * 1e7),
            "center_zoom": 6,
            "center_lon_e7": int(((bounds[0] + bounds[2]) / 2) * 1e7),
            "center_lat_e7": int(((bounds[1] + bounds[3]) / 2) * 1e7),
        }
        writer.finalize(header, metadata)


def _valid_map_json(encoding: str = "terrarium-value") -> dict:
    tile_format = "png" if encoding == "terrarium-value" else "webp"
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
                "max_zoom": 12,
            },
            "relief": {
                "file": "relief.pmtiles",
                "encoding": encoding,
                "tile_format": tile_format,
                "tile_size": 256,
                "min_zoom": 0,
                "max_zoom": 12,
            },
        },
        "credits": [
            {
                "id": "copernicus-worlddem-30",
                "source": "Copernicus DEM GLO-30 and Water Body Mask",
                "attribution": COPERNICUS_ATTRIBUTION,
                "liability": COPERNICUS_LIABILITY,
            },
            {
                "id": "natural-earth",
                "source": "Natural Earth (1:110m, 1:50m, 1:10m land and coastline)",
                "attribution": NATURAL_EARTH_ATTRIBUTION,
            },
        ],
        "reconstructed_areas": [],
    }


def _base_meta(image: str = IMAGE_DIGEST) -> dict:
    return {
        "name": "seerah-map",
        "tile_schema": 1,
        "attribution": f"{COPERNICUS_ATTRIBUTION} {NATURAL_EARTH_ATTRIBUTION}",
        "fingerprint": FINGERPRINT,
        "tool_commit": TOOL_COMMIT,
        "data_commit": SOURCE_COMMIT,
        "image": image,
        "vector_layers": [
            {"id": "coast", "fields": {}, "minzoom": 0, "maxzoom": 12},
            {"id": "land", "fields": {}, "minzoom": 0, "maxzoom": 12},
        ],
    }


def _relief_meta(encoding: str = "terrarium-value", image: str = IMAGE_DIGEST) -> dict:
    return {
        "name": "seerah-map",
        "tile_schema": 1,
        "attribution": COPERNICUS_ATTRIBUTION,
        "encoding": encoding,
        "fingerprint": FINGERPRINT,
        "tool_commit": TOOL_COMMIT,
        "data_commit": SOURCE_COMMIT,
        "image": image,
    }


def _write_base(staging: Path, meta: dict) -> None:
    _write_minimal_pmtiles(
        staging / "data" / "base.pmtiles",
        tile_type=TileType.MVT,
        tile_compression=Compression.GZIP,
        metadata=meta,
    )


def _write_relief(
    staging: Path,
    meta: dict,
    *,
    tile_type: TileType = TileType.PNG,
    bounds: tuple[float, float, float, float] = DATA_EXTENT,
) -> None:
    _write_minimal_pmtiles(
        staging / "data" / "relief.pmtiles",
        tile_type=tile_type,
        tile_compression=Compression.NONE,
        bounds=bounds,
        metadata=meta,
    )


def _write_map_json(staging: Path, doc: dict) -> None:
    (staging / "data" / "map.json").write_text(
        json.dumps(doc, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def _write_report(staging: Path, *, purpose: str = "production", **provenance: str) -> None:
    prov = {
        "tool_commit": TOOL_COMMIT,
        "data_commit": SOURCE_COMMIT,
        "image": IMAGE_DIGEST,
        "fingerprint": FINGERPRINT,
    } | provenance
    (staging / "report.json").write_text(
        json.dumps({"purpose": purpose, "provenance": prov}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _make_staging(
    root: Path, *, encoding: str = "terrarium-value", image: str = IMAGE_DIGEST
) -> Path:
    staging = root / "map-staging-dddddddd"
    (staging / "data").mkdir(parents=True)
    _write_base(staging, _base_meta(image))
    _write_relief(
        staging,
        _relief_meta(encoding, image),
        tile_type=TileType.PNG if encoding == "terrarium-value" else TileType.WEBP,
    )
    _write_map_json(staging, _valid_map_json(encoding))
    _write_report(staging, image=image)
    return staging


def _pack(staging: Path, keypair: Keypair, out_dir: Path, **kwargs) -> Path:
    return archive.pack_map_staging(
        staging_dir=staging,
        version=kwargs.pop("version", 1),
        min_app_version=1,
        key_id=keypair.key_id,
        private_key=keypair.private_key,
        out_dir=out_dir,
        created=CREATED,
        **kwargs,
    )


def _write_zip(path: Path, entries: list[tuple[str, bytes]], **zinfo_overrides) -> Path:
    """Write ``entries`` in order; ``zinfo_overrides`` maps an entry name to ZipInfo changes."""
    with zipfile.ZipFile(path, "w") as zf:
        for name, payload in entries:
            zinfo = archive._make_zip_info(name, zipfile.ZIP_STORED)
            for attr, value in zinfo_overrides.get(name, {}).items():
                setattr(zinfo, attr, value)
            zf.writestr(zinfo, payload)
    return path


def _run_with_passphrase(argv: list[str], passphrase: bytes = PASSPHRASE) -> int:
    r_fd, w_fd = os.pipe()
    try:
        os.write(w_fd, passphrase + b"\n")
        os.close(w_fd)
        w_fd = -1
        return cli.main([*argv, "--passphrase-fd", str(r_fd)])
    finally:
        if w_fd >= 0:
            os.close(w_fd)
        os.close(r_fd)


def _pack_map_argv(staging: Path, keypair: Keypair, out_dir: Path) -> list[str]:
    return [
        "pack-map",
        "--staging",
        str(staging),
        "--version",
        "1",
        "--min-app-version",
        "100",
        "--key",
        str(keypair.pem),
        "--key-id",
        keypair.key_id,
        "--out",
        str(out_dir),
        "--created",
        CREATED,
    ]


# --- CLI round trip and determinism -------------------------------------------------------


@pytest.mark.parametrize("encoding", ["terrarium-value", "painted-rgba"])
def test_cli_pack_map_then_verify_succeeds(tmp_path: Path, keypair: Keypair, encoding: str):
    staging = _make_staging(tmp_path / "stage", encoding=encoding)

    assert _run_with_passphrase(_pack_map_argv(staging, keypair, tmp_path / "out")) == 0
    zip_path = tmp_path / "out" / "seerah-map-1.zip"
    verify_argv = ["verify", "--package", str(zip_path), "--public-keys", str(keypair.pub)]
    assert cli.main([*verify_argv, "--expected-kind", "map"]) == 0


def test_cli_keygen_writes_loadable_keys(tmp_path: Path):
    keys_dir = tmp_path / "keys"

    assert (
        _run_with_passphrase(["keygen", "--key-id", "map-2026-01", "--out-dir", str(keys_dir)]) == 0
    )
    signing.load_private_key(keys_dir / "map-2026-01.key.pem", PASSPHRASE)
    assert [k.key_id for k in signing.load_trusted_keys(keys_dir / "map-2026-01.pub.json")] == [
        "map-2026-01"
    ]


def test_pack_map_with_pinned_created_is_byte_identical(tmp_path: Path, keypair: Keypair):
    staging = _make_staging(tmp_path / "stage")

    zip_a = _pack(staging, keypair, tmp_path / "out_a")
    zip_b = _pack(staging, keypair, tmp_path / "out_b")

    assert zip_a.read_bytes() == zip_b.read_bytes()


def test_map_package_entries_are_stored_in_streaming_order(tmp_path: Path, keypair: Keypair):
    zip_path = _pack(_make_staging(tmp_path / "stage"), keypair, tmp_path / "out")

    with zipfile.ZipFile(zip_path) as zf:
        assert zf.namelist() == [
            "manifest.json",
            "manifest.sig",
            "data/base.pmtiles",
            "data/map.json",
            "data/relief.pmtiles",
        ]
        assert zf.infolist()[0].header_offset == 0
        for info in zf.infolist():
            assert info.compress_type == zipfile.ZIP_STORED
            assert info.date_time == archive.ZIP_DATE_TIME


def test_cli_rejects_out_of_range_version_as_usage_error(tmp_path: Path, keypair: Keypair):
    argv = _pack_map_argv(_make_staging(tmp_path / "stage"), keypair, tmp_path / "out")
    argv[argv.index("--version") + 1] = "0"

    assert _run_with_passphrase(argv) == cli.EXIT_USAGE


def test_cli_reports_io_error_as_failed_check(
    tmp_path: Path, keypair: Keypair, capsys: pytest.CaptureFixture[str]
):
    staging = _make_staging(tmp_path / "stage")
    map_json = staging / "data" / "map.json"
    map_json.chmod(0)
    try:
        code = _run_with_passphrase(_pack_map_argv(staging, keypair, tmp_path / "out"))
    finally:
        map_json.chmod(0o644)

    assert code == cli.EXIT_FAILED
    assert "I/O error" in capsys.readouterr().err


# --- Goal 9 trial isolation and provenance ------------------------------------------------


def _add_root_metadata(staging: Path) -> None:
    (staging / "metadata.json").write_text('{"trial": true}', encoding="utf-8")


def _add_data_metadata(staging: Path) -> None:
    (staging / "data" / "metadata.json").write_text('{"trial": true}', encoding="utf-8")


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (_add_root_metadata, "metadata.json refused"),
        (_add_data_metadata, "metadata.json refused"),
        (lambda s: _write_report(s, purpose="trial"), "purpose must be 'production'"),
        (lambda s: _write_relief(s, _relief_meta() | {"trial": True}), "trial archive refused"),
        (lambda s: _write_relief(s, _relief_meta() | {"variant": "value"}), "'variant'/'mode'"),
        (lambda s: _write_base(s, _base_meta() | {"tile_schema": 2}), "tile_schema"),
        (lambda s: _write_base(s, _base_meta() | {"tool_commit": "unknown"}), "known provenance"),
        (lambda s: _write_report(s, data_commit="unknown"), "provenance.data_commit"),
    ],
    ids=[
        "metadata-json-in-staging",
        "metadata-json-in-data",
        "trial-report",
        "trial-key-in-pmtiles",
        "variant-key-in-pmtiles",
        "wrong-tile-schema",
        "unknown-pmtiles-provenance",
        "unknown-report-provenance",
    ],
)
def test_pack_map_refuses_trial_or_unknown_staging(
    tmp_path: Path, keypair: Keypair, mutate: Callable[[Path], None], message: str
):
    staging = _make_staging(tmp_path / "stage")
    mutate(staging)

    with pytest.raises(PackageError, match=message):
        _pack(staging, keypair, tmp_path / "out")


def test_local_image_provenance_needs_explicit_flag(tmp_path: Path, keypair: Keypair):
    staging = _make_staging(tmp_path / "stage", image="local:seerah-map:dev")

    with pytest.raises(PackageError, match="--allow-local-provenance"):
        _pack(staging, keypair, tmp_path / "out")
    local_zip = _pack(staging, keypair, tmp_path / "out", allow_local_provenance=True)

    with pytest.raises(PackageError, match="--allow-local-provenance"):
        archive.verify_package(local_zip, trusted_keys=keypair.trusted, expected_kind="map")
    archive.verify_package(
        local_zip, trusted_keys=keypair.trusted, expected_kind="map", allow_local_provenance=True
    )


# --- Staging layout and map payload consistency -------------------------------------------


def _add_extra_data_file(staging: Path) -> None:
    (staging / "data" / "notes.txt").write_text("extra", encoding="utf-8")


def _replace_map_json_with_symlink(staging: Path) -> None:
    real = staging / "map.json.real"
    (staging / "data" / "map.json").rename(real)
    (staging / "data" / "map.json").symlink_to(real)


def _with_map_json(change: Callable[[dict], None]) -> Callable[[Path], None]:
    def mutate(staging: Path) -> None:
        doc = _valid_map_json()
        change(doc)
        _write_map_json(staging, doc)

    return mutate


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (_add_extra_data_file, "expected exact files"),
        (_replace_map_json_with_symlink, "non-regular file refused"),
        (
            _with_map_json(lambda d: d["reconstructed_areas"].append({})),
            "data/map.json: key reconstructed_areas",
        ),
        (
            _with_map_json(lambda d: d["sources"]["base"].update(max_zoom=11)),
            "base.pmtiles: header max_zoom",
        ),
        (
            lambda s: _write_base(s, _base_meta() | {"vector_layers": [{"id": "land"}]}),
            "must include 'land' and 'coast'",
        ),
        (
            lambda s: _write_relief(s, _relief_meta(), tile_type=TileType.WEBP),
            "header tile_type",
        ),
        (
            lambda s: _write_relief(s, _relief_meta("painted-rgba")),
            "metadata encoding",
        ),
        (
            lambda s: _write_relief(s, _relief_meta(), bounds=(34.0, 11.0, 61.0, 33.0)),
            "header bounds",
        ),
        (
            lambda s: _write_relief(s, _relief_meta() | {"fingerprint": "e" * 64}),
            "fingerprint mismatch",
        ),
        (lambda s: _write_report(s, fingerprint="e" * 64), "report.json fingerprint"),
        (
            lambda s: _write_report(s, data_commit="f" * 40),
            "data_commit .* disagrees with source_commit",
        ),
    ],
    ids=[
        "extra-data-file",
        "symlinked-map-json",
        "map-json-schema",
        "base-max-zoom",
        "missing-coast-layer",
        "relief-tile-type",
        "relief-encoding",
        "relief-bounds",
        "pmtiles-fingerprints-differ",
        "report-fingerprint",
        "report-data-commit",
    ],
)
def test_pack_map_refuses_inconsistent_staging(
    tmp_path: Path, keypair: Keypair, mutate: Callable[[Path], None], message: str
):
    staging = _make_staging(tmp_path / "stage")
    mutate(staging)

    with pytest.raises(PackageError, match=message):
        _pack(staging, keypair, tmp_path / "out")


# --- Verify-before-parse and key binding ----------------------------------------------------


def test_bad_signature_fails_before_manifest_is_parsed(
    tmp_path: Path, keypair: Keypair, monkeypatch: pytest.MonkeyPatch
):
    bad_zip = _write_zip(
        tmp_path / "corrupt.zip",
        [
            ("manifest.json", b"{not valid json!!!"),
            ("manifest.sig", b"\x00" * 64),
            ("data/item.json", b"{}"),
        ],
    )
    parsed: list[object] = []
    real_loads = json.loads
    monkeypatch.setattr(
        archive.json, "loads", lambda *a, **kw: (parsed.append(a[0]), real_loads(*a, **kw))[1]
    )

    with pytest.raises(PackageError, match="failed Ed25519 signature verification"):
        archive.verify_package(bad_zip, trusted_keys=keypair.trusted)
    assert parsed == []


def _content_manifest(key_id: str) -> dict:
    return {
        "created": CREATED,
        "files": [
            {"path": "data/item.json", "size": 2, "sha256": hashlib.sha256(b"{}").hexdigest()}
        ],
        "key_id": key_id,
        "kind": "content",
        "min_app_version": 1,
        "source_commit": SOURCE_COMMIT,
        "version": 1,
    }


def _two_key_ring(tmp_path: Path) -> tuple[object, object, list[signing.TrustedKey]]:
    pem_a, pub_a = signing.generate_keypair("key-a", tmp_path / "ka", PASSPHRASE)
    pem_b, pub_b = signing.generate_keypair("key-b", tmp_path / "kb", PASSPHRASE)
    ring = [*signing.load_trusted_keys(pub_a), *signing.load_trusted_keys(pub_b)]
    return (
        signing.load_private_key(pem_a, PASSPHRASE),
        signing.load_private_key(pem_b, PASSPHRASE),
        ring,
    )


def _signed_content_zip(path: Path, manifest: dict, private_key) -> Path:
    raw = archive.canonical_manifest_bytes(manifest)
    return _write_zip(
        path,
        [
            ("manifest.json", raw),
            ("manifest.sig", signing.sign_bytes(private_key, raw)),
            ("data/item.json", b"{}"),
        ],
    )


def test_manifest_naming_another_trusted_key_is_rejected(tmp_path: Path):
    priv_a, _, ring = _two_key_ring(tmp_path)
    confused = _signed_content_zip(tmp_path / "confused.zip", _content_manifest("key-b"), priv_a)

    with pytest.raises(PackageError, match="key_id binding mismatch"):
        archive.verify_package(confused, trusted_keys=ring)


def test_package_signed_by_second_trusted_key_verifies(tmp_path: Path):
    _, priv_b, ring = _two_key_ring(tmp_path)
    rotated = _signed_content_zip(tmp_path / "rotated.zip", _content_manifest("key-b"), priv_b)

    assert archive.verify_package(rotated, trusted_keys=ring)["key_id"] == "key-b"


def test_oversized_signature_is_rejected(tmp_path: Path, keypair: Keypair, content_package):
    zip_path = _write_zip(
        tmp_path / "long-sig.zip",
        [
            ("manifest.json", content_package.raw_manifest),
            ("manifest.sig", content_package.raw_sig + b"\x00"),
            ("data/ok.json", b"{}"),
        ],
    )

    with pytest.raises(PackageError, match="manifest.sig must be 64 bytes"):
        archive.verify_package(zip_path, trusted_keys=keypair.trusted)


def test_oversized_map_manifest_is_rejected_before_signature_check(
    tmp_path: Path, keypair: Keypair
):
    zip_path = _write_zip(
        tmp_path / "huge-manifest.zip",
        [
            ("manifest.json", b" " * (archive.MAX_MAP_MANIFEST_BYTES + 1)),
            ("manifest.sig", b"\x00" * 64),
            ("data/ok.json", b"{}"),
        ],
    )

    with pytest.raises(PackageError, match="manifest.json size"):
        archive.verify_package(zip_path, trusted_keys=keypair.trusted, expected_kind="map")


# --- Kinds, versions and size limits ------------------------------------------------------


def _content_zip(tmp_path: Path, keypair: Keypair, compress_type: int) -> Path:
    data = tmp_path / f"content-{compress_type}"
    data.mkdir()
    (data / "content.db").write_bytes(b"sqlite-content" * 100)
    return archive.build_package(
        kind="content",
        data_dir=data,
        version=1,
        min_app_version=1,
        key_id=keypair.key_id,
        private_key=keypair.private_key,
        source_commit=SOURCE_COMMIT,
        out_path=tmp_path / f"seerah-content-{compress_type}.zip",
        created=CREATED,
        compress_type=compress_type,
    )


def test_deflated_content_package_verifies_as_content(tmp_path: Path, keypair: Keypair):
    zip_path = _content_zip(tmp_path, keypair, zipfile.ZIP_DEFLATED)

    assert archive.verify_package(zip_path, trusted_keys=keypair.trusted, expected_kind="content")


def test_deflated_package_is_refused_as_map(tmp_path: Path, keypair: Keypair):
    zip_path = _content_zip(tmp_path, keypair, zipfile.ZIP_DEFLATED)

    with pytest.raises(PackageError, match="ZIP_STORED"):
        archive.verify_package(zip_path, trusted_keys=keypair.trusted, expected_kind="map")


def test_content_package_cannot_be_replayed_as_map(tmp_path: Path, keypair: Keypair):
    zip_path = _content_zip(tmp_path, keypair, zipfile.ZIP_STORED)

    with pytest.raises(PackageError, match="expected 'map'"):
        archive.verify_package(zip_path, trusted_keys=keypair.trusted, expected_kind="map")


def test_pack_map_refuses_to_overwrite_without_force(tmp_path: Path, keypair: Keypair):
    staging = _make_staging(tmp_path / "stage")
    _pack(staging, keypair, tmp_path / "out")

    with pytest.raises(PackageError, match="refusing to overwrite"):
        _pack(staging, keypair, tmp_path / "out")
    assert _pack(staging, keypair, tmp_path / "out", force=True).is_file()


def test_pack_map_requires_version_above_previous(tmp_path: Path, keypair: Keypair):
    staging = _make_staging(tmp_path / "stage")
    first = _pack(staging, keypair, tmp_path / "out", version=2)
    previous = tmp_path / "previous-manifest.json"
    with zipfile.ZipFile(first) as zf:
        previous.write_bytes(zf.read("manifest.json"))

    with pytest.raises(PackageError, match="strictly greater than previous version"):
        _pack(staging, keypair, tmp_path / "out2", version=2, previous_manifest_path=previous)
    assert _pack(staging, keypair, tmp_path / "out3", version=3, previous_manifest_path=previous)


def test_pack_map_refuses_previous_manifest_of_other_kind(
    tmp_path: Path, keypair: Keypair, content_package: ContentPackage
):
    previous = tmp_path / "previous-manifest.json"
    previous.write_bytes(content_package.raw_manifest)

    with pytest.raises(PackageError, match="previous manifest kind"):
        _pack(_make_staging(tmp_path / "stage"), keypair, tmp_path / "out", version=2,
              previous_manifest_path=previous)  # fmt: skip


def test_package_over_release_limit_fails_and_leaves_nothing(
    tmp_path: Path, keypair: Keypair, monkeypatch: pytest.MonkeyPatch
):
    staging = _make_staging(tmp_path / "stage")
    monkeypatch.setattr(archive, "MAX_RELEASE_FILE_BYTES", 50)

    with pytest.raises(PackageError, match="MAX_RELEASE_FILE_BYTES"):
        _pack(staging, keypair, tmp_path / "out")
    assert list((tmp_path / "out").glob("*")) == []


# --- ZIP container hardening ----------------------------------------------------------------


def _append_comment(src: Path, dst: Path) -> None:
    shutil.copyfile(src, dst)
    with zipfile.ZipFile(dst, "a") as zf:
        zf.comment = b"non-empty comment"


def _patch_bytes(src: Path, dst: Path, patch: Callable[[bytearray], None]) -> None:
    raw = bytearray(src.read_bytes())
    patch(raw)
    dst.write_bytes(bytes(raw))


def _set_lfh_encryption_flag(raw: bytearray) -> None:
    raw[6] |= 0x1


def _rename_first_lfh(raw: bytearray) -> None:
    assert raw[30:43] == b"manifest.json"
    raw[30] = ord("X")


def _resize_first_lfh(raw: bytearray) -> None:
    struct.pack_into("<I", raw, 18, 999999)


class _Unseekable:
    """A write-only stream, so ``zipfile`` writes data descriptors after each entry."""

    def __init__(self, handle):
        self._handle = handle

    def write(self, data: bytes) -> int:
        return self._handle.write(data)

    def flush(self) -> None:
        self._handle.flush()

    def tell(self) -> int:
        return self._handle.tell()


def _write_with_data_descriptors(dst: Path, entries: list[tuple[str, bytes]]) -> None:
    with dst.open("wb") as handle, zipfile.ZipFile(_Unseekable(handle), "w") as zf:
        for name, payload in entries:
            with zf.open(archive._make_zip_info(name, zipfile.ZIP_STORED), "w") as dest:
                dest.write(payload)


def _write_with_gap(dst: Path, entries: list[tuple[str, bytes]]) -> None:
    with zipfile.ZipFile(dst, "w") as zf:
        for index, (name, payload) in enumerate(entries):
            if index == len(entries) - 1:
                zf.fp.write(b"\x00" * 16)
                zf.start_dir = zf.fp.tell()
            zf.writestr(archive._make_zip_info(name, zipfile.ZIP_STORED), payload)


def _case(name: str, build: Callable[[Path, ContentPackage], None], message: str):
    return pytest.param(build, message, id=name)


def _entries(pkg: ContentPackage, data: bytes = b"{}") -> list[tuple[str, bytes]]:
    return [
        ("manifest.json", pkg.raw_manifest),
        ("manifest.sig", pkg.raw_sig),
        ("data/ok.json", data),
    ]


@pytest.mark.parametrize(
    ("build", "message"),
    [
        _case("comment", lambda d, p: _append_comment(p.path, d), "comment must be empty"),
        _case(
            "data-first",
            lambda d, p: _write_zip(d, [_entries(p)[2], *_entries(p)[:2]]),
            "must place 'manifest.json' first",
        ),
        _case(
            "symlink-mode",
            lambda d, p: _write_zip(
                d, _entries(p), **{"data/ok.json": {"external_attr": (stat.S_IFLNK | 0o777) << 16}}
            ),
            "non-regular Unix file mode",
        ),
        _case(
            "fifo-mode",
            lambda d, p: _write_zip(
                d, _entries(p), **{"data/ok.json": {"external_attr": (stat.S_IFIFO | 0o600) << 16}}
            ),
            "non-regular Unix file mode",
        ),
        _case(
            "dos-directory",
            lambda d, p: _write_zip(
                d,
                _entries(p),
                **{"data/ok.json": {"external_attr": archive.ZIP_REGULAR_FILE_ATTR | 0x10}},
            ),
            "directory entry refused",
        ),
        _case(
            "extra-field",
            lambda d, p: _write_zip(
                d, _entries(p), **{"data/ok.json": {"extra": b"\x99\x99\x02\x00ab"}}
            ),
            "extra fields",
        ),
        _case(
            "lfh-encrypted",
            lambda d, p: _patch_bytes(p.path, d, _set_lfh_encryption_flag),
            "encryption flag",
        ),
        _case("lfh-name", lambda d, p: _patch_bytes(p.path, d, _rename_first_lfh), "LFH filename"),
        _case(
            "lfh-size",
            lambda d, p: _patch_bytes(p.path, d, _resize_first_lfh),
            "LFH compressed size",
        ),
        _case(
            "data-descriptor",
            lambda d, p: _write_with_data_descriptors(d, _entries(p)),
            "data descriptor",
        ),
        _case(
            "prepended-bytes",
            lambda d, p: d.write_bytes(b"\x00" * 4096 + p.path.read_bytes()),
            "contiguous from the start",
        ),
        _case(
            "trailing-bytes",
            lambda d, p: d.write_bytes(p.path.read_bytes() + b"junk"),
            "no trailing bytes",
        ),
        _case("gap-between-entries", lambda d, p: _write_with_gap(d, _entries(p)), "contiguous"),
        _case(
            "tampered-payload",
            lambda d, p: _write_zip(d, _entries(p, data=b"[]")),
            "SHA-256 mismatch",
        ),
    ],
)
def test_verify_rejects_hostile_container(
    tmp_path: Path,
    keypair: Keypair,
    content_package: ContentPackage,
    build: Callable[[Path, ContentPackage], None],
    message: str,
):
    hostile = tmp_path / "hostile.zip"
    build(hostile, content_package)

    with pytest.raises(PackageError, match=message):
        archive.verify_package(hostile, trusted_keys=keypair.trusted)


def test_verify_rejects_duplicate_entry_names(
    tmp_path: Path, keypair: Keypair, content_package: ContentPackage
):
    with pytest.warns(UserWarning, match="Duplicate name"):
        dup = _write_zip(
            tmp_path / "dup.zip", [*_entries(content_package), ("data/ok.json", b"{}")]
        )

    with pytest.raises(PackageError, match="duplicate entry names"):
        archive.verify_package(dup, trusted_keys=keypair.trusted)


# --- Manifest field rules -------------------------------------------------------------------


def test_manifest_paths_must_be_prefix_free():
    with pytest.raises(PackageError, match="conflicting file/directory prefix"):
        archive.validate_data_paths(["data/a", "data/a/b"])


def test_created_timestamp_must_be_a_real_date():
    with pytest.raises(PackageError, match="invalid calendar date"):
        archive.validate_iso_timestamp("2026-02-30T00:00:00Z")


def test_content_manifest_must_not_carry_map_provenance(content_package: ContentPackage):
    manifest = json.loads(content_package.raw_manifest) | {"tool_commit": TOOL_COMMIT}

    with pytest.raises(PackageError, match="manifest.json"):
        archive.validate_manifest_dict(manifest)
