"""Verify checked-in Android golden fixtures match Python generator and verifier."""

import importlib.util
import sys
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
        assert committed_file.read_bytes() == (regenerated / name).read_bytes(), (
            f"Android fixture {name} drifted from generate_android_test_fixtures.py"
        )
