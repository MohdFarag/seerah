"""Command-line interface for ``python3 -m seerah_packages``."""

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from .archive import (
    PackageError,
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

    pack_map = sub.add_parser(
        "pack-map",
        help="Package and sign a production map staging directory into seerah-map-<version>.zip.",
    )
    pack_map.add_argument("--staging", type=Path, required=True, help="Path to map-staging-<fp8>/.")
    pack_map.add_argument(
        "--version", type=int, required=True, help="Package version (1..1000000)."
    )
    pack_map.add_argument(
        "--min-app-version",
        type=int,
        required=True,
        help="Minimum Android app versionCode (>= 1).",
    )
    pack_map.add_argument(
        "--key", type=Path, required=True, help="Encrypted PKCS#8 private key PEM."
    )
    pack_map.add_argument("--key-id", required=True, help="Key identifier matching the public key.")
    pack_map.add_argument(
        "--out", type=Path, required=True, help="Output directory for .zip package."
    )
    pack_map.add_argument(
        "--created",
        default=None,
        help="UTC timestamp YYYY-MM-DDTHH:MM:SSZ (defaults to current UTC time).",
    )
    pack_map.add_argument(
        "--passphrase-fd",
        type=int,
        default=None,
        help="File descriptor to read passphrase from (one line).",
    )
    pack_map.add_argument(
        "--previous-manifest",
        type=Path,
        default=None,
        help="Optional previous manifest.json to enforce strictly increasing version.",
    )
    pack_map.add_argument(
        "--allow-local-provenance",
        action="store_true",
        help="Allow local:* image provenance for local testing.",
    )
    pack_map.add_argument(
        "--force",
        action="store_true",
        help="Allow overwriting an existing seerah-map-<version>.zip.",
    )

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


def _cmd_pack_map(args: argparse.Namespace) -> int:
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
        if args.command == "pack-map":
            return _cmd_pack_map(args)
        if args.command == "verify":
            return _cmd_verify(args)
        raise _UsageError(f"unknown command {args.command!r}")
    except _UsageError as exc:
        _err(str(exc))
        return EXIT_USAGE
    except (PackageError, SigningError) as exc:
        _err(str(exc))
        return EXIT_FAILED
