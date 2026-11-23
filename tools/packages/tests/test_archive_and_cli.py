"""Tests for deterministic ZIP packaging, Goal 9 guards, ZIP hardening, and CLI."""

import hashlib
import json
import os
import shutil
import stat
import struct
import zipfile
from pathlib import Path

import pytest
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import write as pmtiles_write
from seerah_packages import archive, cli, signing
from seerah_packages.archive import PackageError

SOURCE_COMMIT = "a" * 40
TOOL_COMMIT = "b" * 40
IMAGE_DIGEST = "sha256:" + "c" * 64
FINGERPRINT = "d" * 64

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


def _write_minimal_pmtiles(
    path: Path,
    *,
    tile_type: TileType,
    tile_compression: Compression,
    max_zoom: int = 12,
    bounds: tuple[float, float, float, float] = (33.0, 11.0, 61.0, 33.0),
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


def _make_staging(
    root: Path,
    *,
    encoding: str = "terrarium-value",
    image: str = IMAGE_DIGEST,
    purpose: str = "production",
    base_meta_overrides: dict | None = None,
    relief_meta_overrides: dict | None = None,
) -> Path:
    staging = root / "map-staging-dddddddd"
    data_dir = staging / "data"
    data_dir.mkdir(parents=True, exist_ok=True)

    base_meta = {
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
    if base_meta_overrides:
        base_meta.update(base_meta_overrides)

    relief_meta = {
        "name": "seerah-map",
        "tile_schema": 1,
        "attribution": COPERNICUS_ATTRIBUTION,
        "encoding": encoding,
        "fingerprint": FINGERPRINT,
        "tool_commit": TOOL_COMMIT,
        "data_commit": SOURCE_COMMIT,
        "image": image,
    }
    if relief_meta_overrides:
        relief_meta.update(relief_meta_overrides)

    _write_minimal_pmtiles(
        data_dir / "base.pmtiles",
        tile_type=TileType.MVT,
        tile_compression=Compression.GZIP,
        metadata=base_meta,
    )
    _write_minimal_pmtiles(
        data_dir / "relief.pmtiles",
        tile_type=TileType.PNG if encoding == "terrarium-value" else TileType.WEBP,
        tile_compression=Compression.NONE,
        metadata=relief_meta,
    )
    (data_dir / "map.json").write_text(
        json.dumps(_valid_map_json(encoding), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    report = {
        "purpose": purpose,
        "provenance": {
            "tool_commit": TOOL_COMMIT,
            "data_commit": SOURCE_COMMIT,
            "image": image,
            "fingerprint": FINGERPRINT,
        },
    }
    (staging / "report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return staging


def _run_with_passphrase(argv: list[str], passphrase: bytes = b"test-passphrase") -> int:
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


@pytest.mark.parametrize("encoding", ["terrarium-value", "painted-rgba"])
def test_cli_keygen_pack_map_verify_and_determinism(tmp_path: Path, encoding: str):
    keys_dir = tmp_path / "keys"
    assert (
        _run_with_passphrase(["keygen", "--key-id", "map-2026-01", "--out-dir", str(keys_dir)]) == 0
    )
    key_pem = keys_dir / "map-2026-01.key.pem"
    pub_json = keys_dir / "map-2026-01.pub.json"

    staging = _make_staging(tmp_path / "stage", encoding=encoding)
    out_a = tmp_path / "out_a"
    out_b = tmp_path / "out_b"
    created = "2026-11-23T12:00:00Z"

    for out_dir in (out_a, out_b):
        code = _run_with_passphrase(
            [
                "pack-map",
                "--staging",
                str(staging),
                "--version",
                "1",
                "--min-app-version",
                "100",
                "--key",
                str(key_pem),
                "--key-id",
                "map-2026-01",
                "--out",
                str(out_dir),
                "--created",
                created,
            ]
        )
        assert code == 0

    zip_a = out_a / "seerah-map-1.zip"
    zip_b = out_b / "seerah-map-1.zip"
    assert zip_a.read_bytes() == zip_b.read_bytes()

    with zipfile.ZipFile(zip_a, "r") as zf:
        assert zf.namelist() == [
            "manifest.json",
            "manifest.sig",
            "data/base.pmtiles",
            "data/map.json",
            "data/relief.pmtiles",
        ]
        for info in zf.infolist():
            assert info.compress_type == zipfile.ZIP_STORED
            assert info.date_time == archive.ZIP_DATE_TIME

    assert (
        cli.main(
            [
                "verify",
                "--package",
                str(zip_a),
                "--public-keys",
                str(pub_json),
                "--expected-kind",
                "map",
            ]
        )
        == 0
    )


def test_goal_9_refusals_and_provenance_guards(tmp_path: Path):
    key_pem, pub_json = signing.generate_keypair("k1", tmp_path / "keys", b"pass")
    priv = signing.load_private_key(key_pem, b"pass")

    # 1. Stray metadata.json in staging root or data/
    st_meta = _make_staging(tmp_path / "s_meta")
    (st_meta / "metadata.json").write_text('{"trial": true}', encoding="utf-8")
    with pytest.raises(PackageError, match="metadata.json refused"):
        archive.pack_map_staging(
            staging_dir=st_meta,
            version=1,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=tmp_path / "out",
        )

    # 2. report.json purpose != production
    st_trial_rep = _make_staging(tmp_path / "s_trep", purpose="trial")
    with pytest.raises(PackageError, match="purpose must be 'production'"):
        archive.pack_map_staging(
            staging_dir=st_trial_rep,
            version=1,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=tmp_path / "out",
        )

    # 3. "trial" present in base.pmtiles or relief.pmtiles
    st_trial_pm = _make_staging(tmp_path / "s_tpm", relief_meta_overrides={"trial": True})
    with pytest.raises(PackageError, match="trial archive refused"):
        archive.pack_map_staging(
            staging_dir=st_trial_pm,
            version=1,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=tmp_path / "out",
        )

    # 4. Missing tile_schema: 1
    st_no_schema = _make_staging(tmp_path / "s_noschema", base_meta_overrides={"tile_schema": 2})
    with pytest.raises(PackageError, match="tile_schema"):
        archive.pack_map_staging(
            staging_dir=st_no_schema,
            version=1,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=tmp_path / "out",
        )

    # 5. "unknown" provenance refused
    st_unknown = _make_staging(tmp_path / "s_unk", base_meta_overrides={"tool_commit": "unknown"})
    with pytest.raises(PackageError, match="known provenance"):
        archive.pack_map_staging(
            staging_dir=st_unknown,
            version=1,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=tmp_path / "out",
        )

    # 6. local:* image provenance refused unless allow_local_provenance=True
    st_local = _make_staging(tmp_path / "s_local", image="local:seerah-map:dev")
    with pytest.raises(PackageError, match="--allow-local-provenance"):
        archive.pack_map_staging(
            staging_dir=st_local,
            version=1,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=tmp_path / "out",
        )
    local_zip = archive.pack_map_staging(
        staging_dir=st_local,
        version=1,
        min_app_version=1,
        key_id="k1",
        private_key=priv,
        out_dir=tmp_path / "out_local",
        allow_local_provenance=True,
    )
    keys = signing.load_trusted_keys(pub_json)
    with pytest.raises(PackageError, match="--allow-local-provenance"):
        archive.verify_package(local_zip, trusted_keys=keys, expected_kind="map")
    archive.verify_package(
        local_zip, trusted_keys=keys, expected_kind="map", allow_local_provenance=True
    )


def test_verify_before_parse_and_key_id_binding(tmp_path: Path, monkeypatch):
    key_a_pem, pub_a_json = signing.generate_keypair("key-a", tmp_path / "ka", b"pass")
    key_b_pem, pub_b_json = signing.generate_keypair("key-b", tmp_path / "kb", b"pass")
    priv_a = signing.load_private_key(key_a_pem, b"pass")
    _ = signing.load_private_key(key_b_pem, b"pass")
    trusted = [
        *signing.load_trusted_keys(pub_a_json),
        *signing.load_trusted_keys(pub_b_json),
    ]

    # Build a ZIP with corrupt non-JSON manifest.json and invalid 64-byte signature
    bad_zip = tmp_path / "corrupt_unverified.zip"
    with zipfile.ZipFile(bad_zip, "w") as zf:
        zf.writestr(
            archive._make_zip_info("manifest.json", zipfile.ZIP_STORED), b"{not valid json!!!"
        )
        zf.writestr(archive._make_zip_info("manifest.sig", zipfile.ZIP_STORED), b"\x00" * 64)
        zf.writestr(archive._make_zip_info("data/item.json", zipfile.ZIP_STORED), b"{}")

    json_loads_calls = []
    real_loads = json.loads

    def spy_loads(*args, **kwargs):
        json_loads_calls.append(args[0])
        return real_loads(*args, **kwargs)

    monkeypatch.setattr(archive.json, "loads", spy_loads)
    with pytest.raises(PackageError, match="failed Ed25519 signature verification"):
        archive.verify_package(bad_zip, trusted_keys=trusted)
    # Critical invariant: json.loads was NEVER called before signature check failed!
    assert json_loads_calls == []
    monkeypatch.setattr(archive.json, "loads", real_loads)

    # Key confusion attack: manifest claims key_id="key-b", but signed by priv_a!
    # Both key-a and key-b are in the trusted keyring.
    confused_manifest = {
        "created": "2026-11-23T00:00:00Z",
        "files": [
            {
                "path": "data/item.json",
                "size": 2,
                "sha256": hashlib.sha256(b"{}").hexdigest(),
            }
        ],
        "key_id": "key-b",
        "kind": "content",
        "min_app_version": 1,
        "source_commit": SOURCE_COMMIT,
        "version": 1,
    }
    raw_m = archive.canonical_manifest_bytes(confused_manifest)
    sig_by_a = signing.sign_bytes(priv_a, raw_m)
    confused_zip = tmp_path / "confused.zip"
    with zipfile.ZipFile(confused_zip, "w") as zf:
        zf.writestr(archive._make_zip_info("manifest.json", zipfile.ZIP_STORED), raw_m)
        zf.writestr(archive._make_zip_info("manifest.sig", zipfile.ZIP_STORED), sig_by_a)
        zf.writestr(archive._make_zip_info("data/item.json", zipfile.ZIP_STORED), b"{}")

    with pytest.raises(PackageError, match="key_id binding mismatch"):
        archive.verify_package(confused_zip, trusted_keys=trusted)


def test_cross_kind_replay_and_monotonic_version_and_size_limit(tmp_path: Path, monkeypatch):
    key_pem, pub_json = signing.generate_keypair("k1", tmp_path / "keys", b"pass")
    priv = signing.load_private_key(key_pem, b"pass")
    trusted = signing.load_trusted_keys(pub_json)

    # Build a content package (ZIP_DEFLATED allowed for content)
    content_data = tmp_path / "content_data"
    content_data.mkdir()
    (content_data / "content.db").write_bytes(b"sqlite-content" * 100)
    content_zip = archive.build_package(
        kind="content",
        data_dir=content_data,
        version=1,
        min_app_version=1,
        key_id="k1",
        private_key=priv,
        source_commit=SOURCE_COMMIT,
        out_path=tmp_path / "seerah-content-1.zip",
        created="2026-11-23T00:00:00Z",
        compress_type=zipfile.ZIP_DEFLATED,
    )
    archive.verify_package(content_zip, trusted_keys=trusted, expected_kind="content")
    with pytest.raises(PackageError, match="ZIP_STORED"):
        archive.verify_package(content_zip, trusted_keys=trusted, expected_kind="map")

    # Build a stored content package and test kind mismatch against expected_kind="map"
    content_stored_zip = archive.build_package(
        kind="content",
        data_dir=content_data,
        version=1,
        min_app_version=1,
        key_id="k1",
        private_key=priv,
        source_commit=SOURCE_COMMIT,
        out_path=tmp_path / "seerah-content-stored-1.zip",
        created="2026-11-23T00:00:00Z",
        compress_type=zipfile.ZIP_STORED,
    )
    with pytest.raises(PackageError, match="expected 'map'"):
        archive.verify_package(content_stored_zip, trusted_keys=trusted, expected_kind="map")

    # Immutability (--force) and --previous-manifest monotonic check
    staging = _make_staging(tmp_path / "s1")
    out_dir = tmp_path / "out_map"
    map_zip = archive.pack_map_staging(
        staging_dir=staging,
        version=2,
        min_app_version=1,
        key_id="k1",
        private_key=priv,
        out_dir=out_dir,
    )
    with pytest.raises(PackageError, match="refusing to overwrite"):
        archive.pack_map_staging(
            staging_dir=staging,
            version=2,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=out_dir,
        )

    prev_manifest = tmp_path / "prev_manifest.json"
    with zipfile.ZipFile(map_zip, "r") as zf:
        prev_manifest.write_bytes(zf.read("manifest.json"))

    with pytest.raises(PackageError, match="strictly greater than previous version"):
        archive.pack_map_staging(
            staging_dir=staging,
            version=2,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=tmp_path / "out_map2",
            previous_manifest_path=prev_manifest,
        )

    # Size limit failure (> MAX_RELEASE_FILE_BYTES) cleans up temporary zip
    monkeypatch.setattr(archive, "MAX_RELEASE_FILE_BYTES", 50)
    out_small = tmp_path / "out_small"
    with pytest.raises(PackageError, match="MAX_RELEASE_FILE_BYTES"):
        archive.pack_map_staging(
            staging_dir=staging,
            version=3,
            min_app_version=1,
            key_id="k1",
            private_key=priv,
            out_dir=out_small,
        )
    assert list(out_small.glob("*")) == []


def test_zip_container_security_hardening(tmp_path: Path):
    key_pem, pub_json = signing.generate_keypair("k1", tmp_path / "keys", b"pass")
    priv = signing.load_private_key(key_pem, b"pass")
    trusted = signing.load_trusted_keys(pub_json)

    content_data = tmp_path / "cdata"
    content_data.mkdir()
    (content_data / "ok.json").write_bytes(b"{}")
    good_zip = archive.build_package(
        kind="content",
        data_dir=content_data,
        version=1,
        min_app_version=1,
        key_id="k1",
        private_key=priv,
        source_commit=SOURCE_COMMIT,
        out_path=tmp_path / "good.zip",
        created="2026-11-23T00:00:00Z",
    )
    with zipfile.ZipFile(good_zip, "r") as zf:
        raw_manifest = zf.read("manifest.json")
        raw_sig = zf.read("manifest.sig")

    # 1. Non-empty zip comment
    comment_zip = tmp_path / "comment.zip"
    shutil.copyfile(good_zip, comment_zip)
    with zipfile.ZipFile(comment_zip, "a") as zf:
        zf.comment = b"non-empty comment"
    with pytest.raises(PackageError, match="comment must be empty"):
        archive.verify_package(comment_zip, trusted_keys=trusted)

    # 2. Wrong entry order (data/ok.json before manifest.json)
    order_zip = tmp_path / "order.zip"
    with zipfile.ZipFile(order_zip, "w") as zf:
        zf.writestr(archive._make_zip_info("data/ok.json", zipfile.ZIP_STORED), b"{}")
        zf.writestr(archive._make_zip_info("manifest.json", zipfile.ZIP_STORED), raw_manifest)
        zf.writestr(archive._make_zip_info("manifest.sig", zipfile.ZIP_STORED), raw_sig)
    with pytest.raises(PackageError, match="must place 'manifest.json' first"):
        archive.verify_package(order_zip, trusted_keys=trusted)

    # 3. Symlink Unix mode in external_attr
    symlink_zip = tmp_path / "symlink.zip"
    with zipfile.ZipFile(symlink_zip, "w") as zf:
        zf.writestr(archive._make_zip_info("manifest.json", zipfile.ZIP_STORED), raw_manifest)
        zf.writestr(archive._make_zip_info("manifest.sig", zipfile.ZIP_STORED), raw_sig)
        zinfo = archive._make_zip_info("data/ok.json", zipfile.ZIP_STORED)
        zinfo.external_attr = (stat.S_IFLNK | 0o777) << 16
        zf.writestr(zinfo, b"{}")
    with pytest.raises(PackageError, match="non-regular Unix file mode"):
        archive.verify_package(symlink_zip, trusted_keys=trusted)

    # 4. DOS directory flag (0x10) in external_attr
    dosdir_zip = tmp_path / "dosdir.zip"
    with zipfile.ZipFile(dosdir_zip, "w") as zf:
        zf.writestr(archive._make_zip_info("manifest.json", zipfile.ZIP_STORED), raw_manifest)
        zf.writestr(archive._make_zip_info("manifest.sig", zipfile.ZIP_STORED), raw_sig)
        zinfo = archive._make_zip_info("data/ok.json", zipfile.ZIP_STORED)
        zinfo.external_attr |= 0x10
        zf.writestr(zinfo, b"{}")
    with pytest.raises(PackageError, match="directory entry refused"):
        archive.verify_package(dosdir_zip, trusted_keys=trusted)

    # 5. Encrypted flag bit (flag_bits & 0x1) in LFH
    enc_zip = tmp_path / "enc.zip"
    shutil.copyfile(good_zip, enc_zip)
    raw = bytearray(enc_zip.read_bytes())
    # Set bit 0 of LFH general purpose bit flag at offset 6 of first LFH
    raw[6] |= 0x1
    enc_zip.write_bytes(bytes(raw))
    with pytest.raises(PackageError, match="encryption flag"):
        archive.verify_package(enc_zip, trusted_keys=trusted)

    # 6. LFH vs CD filename mismatch
    lfh_mismatch = tmp_path / "lfh_mismatch.zip"
    shutil.copyfile(good_zip, lfh_mismatch)
    raw = bytearray(lfh_mismatch.read_bytes())
    # First LFH filename starts at offset 30 ("manifest.json") -> mutate first char to "X"
    assert raw[30:43] == b"manifest.json"
    raw[30] = ord("X")
    lfh_mismatch.write_bytes(bytes(raw))
    with pytest.raises(PackageError, match="LFH filename"):
        archive.verify_package(lfh_mismatch, trusted_keys=trusted)

    # 7. LFH vs CD compressed size mismatch
    lfh_size_mismatch = tmp_path / "lfh_size_mismatch.zip"
    shutil.copyfile(good_zip, lfh_size_mismatch)
    raw = bytearray(lfh_size_mismatch.read_bytes())
    # Compressed size in first LFH is uint32 at offset 18..22
    struct.pack_into("<I", raw, 18, 999999)
    lfh_size_mismatch.write_bytes(bytes(raw))
    with pytest.raises(PackageError, match="LFH compressed size"):
        archive.verify_package(lfh_size_mismatch, trusted_keys=trusted)

    # 8. Duplicate entry name in Central Directory
    dup_zip = tmp_path / "dup.zip"
    with pytest.warns(UserWarning), zipfile.ZipFile(dup_zip, "w") as zf:
        zf.writestr(archive._make_zip_info("manifest.json", zipfile.ZIP_STORED), raw_manifest)
        zf.writestr(archive._make_zip_info("manifest.sig", zipfile.ZIP_STORED), raw_sig)
        zf.writestr(archive._make_zip_info("data/ok.json", zipfile.ZIP_STORED), b"{}")
        zf.writestr(archive._make_zip_info("data/ok.json", zipfile.ZIP_STORED), b"{}")
    with pytest.raises(PackageError, match="duplicate entry names"):
        archive.verify_package(dup_zip, trusted_keys=trusted)

    # 9. Prefix collision in manifest files (data/a and data/a/b)
    with pytest.raises(PackageError, match="conflicting file/directory prefix"):
        archive.validate_data_paths(["data/a", "data/a/b"])

    # 10. Impossible calendar date in created timestamp
    with pytest.raises(PackageError, match="invalid calendar date"):
        archive.validate_iso_timestamp("2026-02-30T00:00:00Z")

    # 11. FIFO Unix mode in external_attr
    fifo_zip = tmp_path / "fifo.zip"
    with zipfile.ZipFile(fifo_zip, "w") as zf:
        zf.writestr(archive._make_zip_info("manifest.json", zipfile.ZIP_STORED), raw_manifest)
        zf.writestr(archive._make_zip_info("manifest.sig", zipfile.ZIP_STORED), raw_sig)
        zinfo = archive._make_zip_info("data/ok.json", zipfile.ZIP_STORED)
        zinfo.external_attr = (stat.S_IFIFO | 0o600) << 16
        zf.writestr(zinfo, b"{}")
    with pytest.raises(PackageError, match="non-regular Unix file mode"):
        archive.verify_package(fifo_zip, trusted_keys=trusted)

    # 12. Tampered payload bytes (SHA-256 mismatch)
    tampered_zip = tmp_path / "tampered.zip"
    with zipfile.ZipFile(tampered_zip, "w") as zf:
        zf.writestr(archive._make_zip_info("manifest.json", zipfile.ZIP_STORED), raw_manifest)
        zf.writestr(archive._make_zip_info("manifest.sig", zipfile.ZIP_STORED), raw_sig)
        zf.writestr(archive._make_zip_info("data/ok.json", zipfile.ZIP_STORED), b"[]")
    with pytest.raises(PackageError, match="SHA-256 mismatch"):
        archive.verify_package(tampered_zip, trusted_keys=trusted)

    # 13. Content manifest must not contain tool_commit or image
    bad_content_manifest = json.loads(raw_manifest.decode("utf-8"))
    bad_content_manifest["tool_commit"] = TOOL_COMMIT
    with pytest.raises(PackageError, match="manifest.json"):
        archive.validate_manifest_dict(bad_content_manifest)
