"""Verify checked-in Android golden fixtures match Python generator and verifier."""

import importlib.util
import sys
import zipfile
from pathlib import Path

import pytest
from seerah_packages import archive, signing
from seerah_packages.archive import PackageError

_REPO_ROOT = Path(__file__).resolve().parents[3]
_GENERATOR_PATH = (
    _REPO_ROOT / "tools" / "packages" / "scripts" / "generate_android_test_fixtures.py"
)
_FIXTURES_DIR = (
    _REPO_ROOT / "android" / "core" / "content" / "src" / "test" / "resources" / "fixtures"
)


def _load_generator():
    spec = importlib.util.spec_from_file_location("generate_android_test_fixtures", _GENERATOR_PATH)
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


def _assert_zip_matches(committed: Path, regenerated: Path) -> None:
    """Compare ZIP structure, entry metadata, and uncompressed payloads.

    Comparing uncompressed payloads avoids false drift failures when ZIP_DEFLATED
    bitstreams vary between stock zlib and zlib-ng while still checking every
    manifest, Ed25519 signature, and payload byte identically.
    """
    with zipfile.ZipFile(committed) as cz, zipfile.ZipFile(regenerated) as rz:
        committed_meta = [
            (i.filename, i.compress_type, i.date_time, i.external_attr, i.file_size, i.CRC)
            for i in cz.infolist()
        ]
        regenerated_meta = [
            (i.filename, i.compress_type, i.date_time, i.external_attr, i.file_size, i.CRC)
            for i in rz.infolist()
        ]
        assert committed_meta == regenerated_meta, (
            f"Android fixture {committed.name} entry metadata drifted from "
            "generate_android_test_fixtures.py"
        )
        for info in cz.infolist():
            assert cz.read(info.filename) == rz.read(info.filename), (
                f"Android fixture {committed.name}:{info.filename} drifted from "
                "generate_android_test_fixtures.py"
            )


def test_checked_in_android_fixtures_verify_and_match_generator(tmp_path: Path):
    trusted = signing.load_trusted_keys(_FIXTURES_DIR / "trusted-keys.json")

    map_manifest = archive.verify_package(
        _FIXTURES_DIR / "valid-map-v1.zip",
        trusted_keys=trusted,
        expected_kind="map",
    )
    assert map_manifest["kind"] == "map"

    content_manifest = archive.verify_package(
        _FIXTURES_DIR / "valid-content-v1.zip",
        trusted_keys=trusted,
        expected_kind="content",
    )
    assert content_manifest["kind"] == "content"

    with pytest.raises(PackageError, match="trial archive refused"):
        archive.verify_package(
            _FIXTURES_DIR / "trial-rejected-map-v1.zip",
            trusted_keys=trusted,
            expected_kind="map",
        )

    generator = _load_generator()
    regenerated = tmp_path / "fixtures"
    generator.generate_fixtures(regenerated)

    for name in (
        "trusted-keys.json",
        "valid-map-v1.zip",
        "valid-content-v1.zip",
        "trial-rejected-map-v1.zip",
    ):
        committed_file = _FIXTURES_DIR / name
        assert committed_file.is_file(), f"Missing fixture {committed_file}"
        regenerated_file = regenerated / name
        if name.endswith(".zip"):
            _assert_zip_matches(committed_file, regenerated_file)
        else:
            assert committed_file.read_bytes() == regenerated_file.read_bytes(), (
                f"Android fixture {name} drifted from generate_android_test_fixtures.py"
            )
