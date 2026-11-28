"""Command-line interface for ``python3 -m seerah_packages``."""

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from .archive import (
    PackageError,
    pack_content,
    pack_map_staging,
    validate_iso_timestamp,
    verify_package,
)
from .signing import (
    SigningError,
    generate_keypair,
    load_private_key,
    load_trusted_keys,
    read_passphrase,
    validate_key_id,
)

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2


class _UsageError(Exception):
    """Raised when command-line arguments are invalid."""


class _Parser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise _UsageError(message)


def _say(message: str) -> None:
    print(message, flush=True)


def _err(message: str) -> None:
    print(f"error: {message}", file=sys.stderr, flush=True)


def _build_parser() -> _Parser:
    parser = _Parser(
        prog="seerah_packages",
        description="Shared host-side packaging and offline Ed25519 manifest signing.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    keygen = sub.add_parser("keygen", help="Generate an encrypted Ed25519 keypair.")
    keygen.add_argument(
        "--key-id", required=True, help="Key identifier (^[a-z0-9][a-z0-9-]{0,63}$)."
    )
    keygen.add_argument(
        "--out-dir", type=Path, required=True, help="Output directory for key files."
    )
    keygen.add_argument(
        "--passphrase-fd",
        type=int,
        default=None,
        help="File descriptor to read passphrase from (one line).",
    )

    packing = argparse.ArgumentParser(add_help=False)
    packing.add_argument("--version", type=int, required=True, help="Package version (1..1000000).")
    packing.add_argument(
        "--min-app-version",
        type=int,
        required=True,
        help="Minimum Android app versionCode (>= 1).",
    )
    packing.add_argument(
        "--key", type=Path, required=True, help="Encrypted PKCS#8 private key PEM."
    )
    packing.add_argument("--key-id", required=True, help="Key identifier matching the public key.")
    packing.add_argument(
        "--out", type=Path, required=True, help="Output directory for .zip package."
    )
    packing.add_argument(
        "--created",
        default=None,
        help="UTC timestamp YYYY-MM-DDTHH:MM:SSZ (defaults to current UTC time).",
    )
    packing.add_argument(
        "--passphrase-fd",
        type=int,
        default=None,
        help="File descriptor to read passphrase from (one line).",
    )
    packing.add_argument(
        "--previous-manifest",
        type=Path,
        default=None,
        help="Optional previous manifest.json to enforce strictly increasing version.",
    )
    packing.add_argument(
        "--force",
        action="store_true",
        help="Allow overwriting an existing seerah-map-<version>.zip.",
    )

    pack_map = sub.add_parser(
        "pack-map", parents=[packing], help="Package a production map staging directory"
    )
    pack_map.add_argument("--staging", type=Path, required=True)
    pack_map.add_argument(
        "--allow-local-provenance",
        action="store_true",
        help="Allow local:* image provenance for local testing.",
    )
    content = sub.add_parser(
        "pack-content", parents=[packing], help="Compile and sign approved YAML content"
    )
    content.add_argument("--source", type=Path, required=True)
    content.add_argument("--source-commit")
    content.add_argument("--allow-dirty", action="store_true")
    content.add_argument("--strict-warnings", action="store_true")

    verify = sub.add_parser("verify", help="Verify a signed content or map package ZIP.")
    verify.add_argument("--package", type=Path, required=True, help="Path to package .zip file.")
    verify.add_argument(
        "--public-keys",
        type=Path,
        required=True,
        help="Trusted public key JSON file or directory of *.json files (1..2 keys).",
    )
    verify.add_argument(
        "--expected-kind",
        choices=("content", "map"),
        default=None,
        help="Require manifest.kind to match this package kind.",
    )
    verify.add_argument(
        "--allow-local-provenance",
        action="store_true",
        help="Allow local:* image provenance on map packages.",
    )
    return parser


def _cmd_keygen(args: argparse.Namespace) -> int:
    try:
        validate_key_id(args.key_id)
    except SigningError as exc:
        raise _UsageError(str(exc)) from exc
    passphrase = read_passphrase(args.passphrase_fd, confirm=(args.passphrase_fd is None))
    key_path, pub_path = generate_keypair(args.key_id, args.out_dir, passphrase)
    _say(f"keygen: wrote {key_path} and {pub_path}")
    return EXIT_OK


def _cmd_pack(args: argparse.Namespace) -> int:
    try:
        validate_key_id(args.key_id)
    except SigningError as exc:
        raise _UsageError(str(exc)) from exc
    if not (1 <= args.version <= 1_000_000):
        raise _UsageError(f"--version must be between 1 and 1000000, got {args.version}")
    if args.min_app_version < 1:
        raise _UsageError(f"--min-app-version must be >= 1, got {args.min_app_version}")
    if args.created is not None:
        try:
            validate_iso_timestamp(args.created)
        except PackageError as exc:
            raise _UsageError(str(exc)) from exc

    passphrase = read_passphrase(args.passphrase_fd, confirm=False)
    private_key = load_private_key(args.key, passphrase)
    if args.command == "pack-content":
        out_zip = pack_content(
            source=args.source,
            version=args.version,
            min_app_version=args.min_app_version,
            key_id=args.key_id,
            private_key=private_key,
            out_dir=args.out,
            created=args.created,
            previous_manifest_path=args.previous_manifest,
            strict_warnings=args.strict_warnings,
            allow_dirty=args.allow_dirty,
            source_commit=args.source_commit,
            force=args.force,
        )
        _say(f"pack-content: wrote and verified {out_zip}")
        return EXIT_OK
    out_zip = pack_map_staging(
        staging_dir=args.staging,
        version=args.version,
        min_app_version=args.min_app_version,
        key_id=args.key_id,
        private_key=private_key,
        out_dir=args.out,
        created=args.created,
        previous_manifest_path=args.previous_manifest,
        allow_local_provenance=args.allow_local_provenance,
        force=args.force,
    )
    _say(f"pack-map: wrote and verified {out_zip}")
    return EXIT_OK


def _cmd_verify(args: argparse.Namespace) -> int:
    trusted_keys = load_trusted_keys(args.public_keys)
    manifest = verify_package(
        args.package,
        trusted_keys=trusted_keys,
        expected_kind=args.expected_kind,
        allow_local_provenance=args.allow_local_provenance,
    )
    _say(
        f"verify: ok: {args.package} "
        f"(kind={manifest['kind']}, version={manifest['version']}, "
        f"key_id={manifest['key_id']}, files={len(manifest['files'])})"
    )
    return EXIT_OK


def main(argv: Sequence[str] | None = None) -> int:
    """Run the CLI and return an exit code (0 success, 1 check failed, 2 usage error)."""
    parser = _build_parser()
    try:
        args = parser.parse_args(argv)
        if args.command == "keygen":
            return _cmd_keygen(args)
        if args.command in ("pack-map", "pack-content"):
            return _cmd_pack(args)
        if args.command == "verify":
            return _cmd_verify(args)
        raise _UsageError(f"unknown command {args.command!r}")
    except _UsageError as exc:
        _err(str(exc))
        return EXIT_USAGE
    except (PackageError, SigningError) as exc:
        _err(str(exc))
        return EXIT_FAILED
    except OSError as exc:
        _err(f"I/O error: {exc}")
        return EXIT_FAILED
