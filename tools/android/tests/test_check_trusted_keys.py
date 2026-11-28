"""Tests for tools/android/check_trusted_keys.py."""

import base64
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_trusted_keys  # noqa: E402

RFC_TEST1_PUB = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
CLEAN_PROD_PUB = bytes(range(1, 33))


def _keyring_bytes(entries: list[dict]) -> bytes:
    return (json.dumps({"keys": entries}) + "\n").encode("utf-8")


def _entry(key_id: str, pub_bytes: bytes) -> dict:
    return {
        "key_id": key_id,
        "algorithm": "Ed25519",
        "tink_variant": "NO_PREFIX",
        "public_key_base64": base64.b64encode(pub_bytes).decode("ascii"),
    }


class TrustedKeysGuardUnitTest(unittest.TestCase):
    def _seed_source_tree(self, root: Path, payload: bytes) -> Path:
        raw_dir = root / "android" / "core" / "content" / "src" / "main" / "res" / "raw"
        raw_dir.mkdir(parents=True, exist_ok=True)
        key_file = raw_dir / "trusted_package_keys.json"
        key_file.write_bytes(payload)
        return key_file

    def test_accepts_pre_ceremony_empty_and_clean_production_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_source_tree(root, _keyring_bytes([]))
            ok, _ = check_trusted_keys.run_check(root, None)
            self.assertTrue(ok)

            self._seed_source_tree(root, _keyring_bytes([_entry("seerah-2026-01", CLEAN_PROD_PUB)]))
            ok_prod, _ = check_trusted_keys.run_check(root, None)
            self.assertTrue(ok_prod)

    def test_rejects_rfc8032_and_test_key_ids_and_malformed_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for payload in (
                _keyring_bytes([_entry("seerah-2026-01", RFC_TEST1_PUB)]),
                _keyring_bytes([_entry("test-signing-1", CLEAN_PROD_PUB)]),
                _keyring_bytes([_entry("dev_local", CLEAN_PROD_PUB)]),
                b"[]",
                b"{not json",
            ):
                self._seed_source_tree(root, payload)
                ok, errors = check_trusted_keys.run_check(root, None)
                self.assertFalse(ok)
                self.assertTrue(errors)

    def test_inspects_apk_resources_and_catches_leaked_test_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_source_tree(root, _keyring_bytes([]))

            clean_apk = root / "clean.apk"
            with zipfile.ZipFile(clean_apk, "w") as zf:
                zf.writestr("res/raw/trusted_package_keys.json", _keyring_bytes([]))
            ok_clean, _ = check_trusted_keys.run_check(root, clean_apk)
            self.assertTrue(ok_clean)

            dirty_apk = root / "dirty.apk"
            with zipfile.ZipFile(dirty_apk, "w") as zf:
                zf.writestr(
                    "res/raw/trusted_package_keys.json",
                    _keyring_bytes([_entry("rfc8032-1", CLEAN_PROD_PUB)]),
                )
            ok_dirty, _ = check_trusted_keys.run_check(root, dirty_apk)
            self.assertFalse(ok_dirty)

    def test_fails_when_no_source_key_files_exist(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok, errors = check_trusted_keys.run_check(Path(tmp), None)
            self.assertFalse(ok)
            self.assertIn("found no", errors[0])


if __name__ == "__main__":
    unittest.main()
