#!/usr/bin/env python3
"""Check that no test, debug, or RFC 8032 Ed25519 keys appear in main or release Android artifacts.

Scans every non-test `android/**/src/*/res/raw/trusted_package_keys.json` source file and,
when `--apk` is passed, inspects any packaged `trusted_package_keys` JSON resources (including
minified resource paths) inside the release APK.

Usage: check_trusted_keys.py --source-dir REPO_ROOT [--apk RELEASE_APK]
Exit code 0 when clean, 1 on test keys or invalid JSON, 2 on usage errors.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import re
import sys
import zipfile
from pathlib import Path

FORBIDDEN_RFC8032_PUBLIC_KEYS = frozenset(
    {
        bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
        bytes.fromhex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
    }
)
FORBIDDEN_KEY_ID_RE = re.compile(r"^(?:test|rfc8032|dev|debug)(?:$|[-_])", re.IGNORECASE)
VALID_KEY_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
MAX_TRUSTED_KEYS = 2
ED25519_PUBLIC_KEY_BYTES = 32
TEST_SOURCE_SEGMENTS = frozenset({"test", "androidTest"})


def validate_trusted_keys_json(raw_bytes: bytes, source_label: str) -> list[str]:
    """Validate raw JSON bytes of `trusted_package_keys.json`; return list of errors."""
    try:
        doc = json.loads(raw_bytes.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        return [f"{source_label}: invalid JSON ({exc})"]

    if not isinstance(doc, dict) or set(doc) != {"keys"}:
        return [f"{source_label}: root must be an object containing only 'keys'"]

    keys = doc["keys"]
    if not isinstance(keys, list):
        return [f"{source_label}: 'keys' must be a list"]
    if len(keys) > MAX_TRUSTED_KEYS:
        return [f"{source_label}: at most {MAX_TRUSTED_KEYS} keys allowed, got {len(keys)}"]

    errors: list[str] = []
    for idx, entry in enumerate(keys):
        if not isinstance(entry, dict):
            errors.append(f"{source_label}: keys[{idx}] must be an object")
            continue

        key_id = entry.get("key_id")
        if not isinstance(key_id, str) or not VALID_KEY_ID_RE.match(key_id):
            errors.append(f"{source_label}: keys[{idx}] has invalid key_id {key_id!r}")
        elif FORBIDDEN_KEY_ID_RE.match(key_id):
            errors.append(
                f"{source_label}: forbidden test/dev/debug key_id {key_id!r} in release resources"
            )

        raw_b64 = entry.get("public_key_base64")
        if not isinstance(raw_b64, str):
            errors.append(f"{source_label}: keys[{idx}] missing string public_key_base64")
            continue
        try:
            decoded = base64.b64decode(raw_b64, validate=True)
        except binascii.Error as exc:
            errors.append(f"{source_label}: keys[{idx}] invalid Base64 ({exc})")
            continue
        if len(decoded) != ED25519_PUBLIC_KEY_BYTES:
            errors.append(f"{source_label}: keys[{idx}] decoded key length {len(decoded)} != 32")
            continue
        if decoded in FORBIDDEN_RFC8032_PUBLIC_KEYS:
            errors.append(
                f"{source_label}: keys[{idx}] ({key_id!r}) uses forbidden RFC 8032 test key"
            )

        raw_hex = entry.get("public_key_hex")
        if raw_hex is not None:
            if not isinstance(raw_hex, str) or len(raw_hex) != 64:
                errors.append(f"{source_label}: keys[{idx}] public_key_hex must be 64 hex chars")
            else:
                try:
                    hex_bytes = bytes.fromhex(raw_hex)
                except ValueError as exc:
                    errors.append(f"{source_label}: keys[{idx}] invalid hex ({exc})")
                else:
                    if hex_bytes in FORBIDDEN_RFC8032_PUBLIC_KEYS:
                        errors.append(
                            f"{source_label}: keys[{idx}] hex matches forbidden RFC 8032 test key"
                        )
    return errors


def find_source_key_files(repo_root: Path) -> list[Path]:
    """Find non-test `trusted_package_keys.json` files under `android/**/src/`."""
    android_dir = repo_root / "android"
    results: list[Path] = []
    if not android_dir.is_dir():
        return results
    for path in sorted(android_dir.glob("**/src/*/res/raw/trusted_package_keys.json")):
        if "spike" in path.parts or "build" in path.parts:
            continue
        src_index = path.parts.index("src")
        variant = path.parts[src_index + 1]
        if variant in TEST_SOURCE_SEGMENTS or variant.startswith(("test", "androidTest")):
            continue
        results.append(path)
    return results


def check_apk_trusted_keys(apk_path: Path) -> list[str]:
    """Inspect any keyset JSON resource packaged inside an APK."""
    if not apk_path.is_file():
        return [f"{apk_path}: APK file not found"]
    errors: list[str] = []
    try:
        with zipfile.ZipFile(apk_path, "r") as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                is_named_keyring = info.filename.startswith("res/raw/trusted_package_keys")
                is_small_res_json = info.filename.startswith("res/") and info.filename.endswith(
                    ".json"
                )
                if not (is_named_keyring or is_small_res_json):
                    continue
                if info.file_size > 65_536:
                    continue
                raw = zf.read(info.filename)
                if (
                    not is_named_keyring
                    and b"public_key_base64" not in raw
                    and b'"keys"' not in raw
                ):
                    continue
                errors.extend(validate_trusted_keys_json(raw, f"{apk_path}!{info.filename}"))
    except (OSError, zipfile.BadZipFile) as exc:
        return [f"{apk_path}: cannot inspect APK ({exc})"]
    return errors


def run_check(source_dir: Path, apk_path: Path | None) -> tuple[bool, list[str]]:
    """Run source and optional APK trusted-key checks; return `(ok, messages)`."""
    source_files = find_source_key_files(source_dir)
    if not source_files:
        return False, [
            f"{source_dir}: found no non-test android/**/src/*/res/raw/trusted_package_keys.json"
        ]

    errors: list[str] = []
    for path in source_files:
        errors.extend(validate_trusted_keys_json(path.read_bytes(), str(path)))

    if apk_path is not None:
        errors.extend(check_apk_trusted_keys(apk_path))

    if errors:
        return False, errors

    summary = f"ok: checked {len(source_files)} trusted key file(s)"
    if apk_path is not None:
        summary += f" and {apk_path}"
    return True, [summary]


def main(args: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-dir",
        type=Path,
        required=True,
        help="Repository root directory containing android/",
    )
    parser.add_argument(
        "--apk",
        type=Path,
        default=None,
        help="Optional built release APK to inspect alongside source files",
    )
    parsed = parser.parse_args(args)
    ok, messages = run_check(parsed.source_dir, parsed.apk)
    for msg in messages:
        print(msg if ok else f"error: {msg}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
