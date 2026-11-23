"""Offline Ed25519 key generation, signing, and isolated single-key verification."""

import base64
import binascii
import getpass
import json
import os
import re
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

KEY_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
HEX_PUB_RE = re.compile(r"^[0-9a-f]{64}$")
RAW_PUBLIC_KEY_BYTES = 32
RAW_SIGNATURE_BYTES = 64
KEY_DIR_MODE = 0o700
PRIVATE_KEY_MODE = 0o600
PUBLIC_KEY_MODE = 0o644
ALGORITHM = "Ed25519"
TINK_VARIANT = "NO_PREFIX"
MAX_TRUSTED_KEYS = 2


class SigningError(Exception):
    """Key generation, key loading, signing, or signature verification failed."""


def validate_key_id(key_id: object) -> str:
    """Validate and return a key identifier matching ``^[a-z0-9][a-z0-9-]{0,63}$``."""
    if not isinstance(key_id, str) or not KEY_ID_RE.match(key_id):
        raise SigningError(
            f"invalid key_id {key_id!r}: expected lowercase alphanumeric/hyphen (1..64 chars)"
        )
    return key_id


@dataclass(frozen=True)
class TrustedKey:
    """One trusted Ed25519 public key (Tink ``NO_PREFIX`` / raw 32-byte key)."""

    key_id: str
    public_key_bytes: bytes

    def __post_init__(self) -> None:
        validate_key_id(self.key_id)
        if (
            not isinstance(self.public_key_bytes, bytes)
            or len(self.public_key_bytes) != RAW_PUBLIC_KEY_BYTES
        ):
            raise SigningError(
                f"trusted key {self.key_id!r} must have exactly {RAW_PUBLIC_KEY_BYTES} raw bytes"
            )

    def verify(self, signature: bytes, data: bytes) -> bool:
        """Verify raw 64-byte Ed25519 ``signature`` over ``data`` with this single key."""
        if len(signature) != RAW_SIGNATURE_BYTES:
            return False
        pub = Ed25519PublicKey.from_public_bytes(self.public_key_bytes)
        try:
            pub.verify(signature, data)
        except InvalidSignature:
            return False
        return True


def read_passphrase(passphrase_fd: int | None = None, *, confirm: bool = False) -> bytes:
    """Read a non-empty UTF-8 passphrase from ``passphrase_fd`` or interactive prompt."""
    if passphrase_fd is not None:
        try:
            dup_fd = os.dup(passphrase_fd)
            with os.fdopen(dup_fd, "r", encoding="utf-8") as stream:
                line = stream.readline().rstrip("\r\n")
        except OSError as exc:
            raise SigningError(f"cannot read passphrase from fd {passphrase_fd}: {exc}") from exc
        if not line:
            raise SigningError("passphrase must not be empty")
        return line.encode("utf-8")

    first = getpass.getpass("Enter key passphrase: ")
    if not first:
        raise SigningError("passphrase must not be empty")
    if confirm:
        second = getpass.getpass("Confirm key passphrase: ")
        if first != second:
            raise SigningError("passphrases do not match")
    return first.encode("utf-8")


def generate_keypair(key_id: str, out_dir: Path, passphrase: bytes) -> tuple[Path, Path]:
    """Generate an encrypted PKCS#8 Ed25519 private key and a Tink NO_PREFIX public key JSON.

    A missing ``out_dir`` is created with mode ``0700``; an existing one keeps its mode.
    """
    validate_key_id(key_id)
    if not passphrase:
        raise SigningError("passphrase must not be empty")
    out_dir = Path(out_dir)
    out_dir.mkdir(mode=KEY_DIR_MODE, parents=True, exist_ok=True)
    key_path = out_dir / f"{key_id}.key.pem"
    pub_path = out_dir / f"{key_id}.pub.json"
    if key_path.exists():
        raise SigningError(f"refusing to overwrite existing private key {key_path}")
    if pub_path.exists():
        raise SigningError(f"refusing to overwrite existing public key {pub_path}")

    private_key = Ed25519PrivateKey.generate()
    pem_bytes = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.BestAvailableEncryption(passphrase),
    )
    raw_pub = public_key_bytes_from_private(private_key)
    pub_doc = {
        "algorithm": ALGORITHM,
        "key_id": key_id,
        "public_key_base64": base64.b64encode(raw_pub).decode("ascii"),
        "tink_variant": TINK_VARIANT,
    }
    pub_bytes = (json.dumps(pub_doc, indent=2, sort_keys=True) + "\n").encode("utf-8")

    try:
        key_fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, PRIVATE_KEY_MODE)
    except FileExistsError as exc:
        raise SigningError(f"refusing to overwrite existing private key {key_path}") from exc
    try:
        with os.fdopen(key_fd, "wb") as handle:
            handle.write(pem_bytes)
    except Exception:
        key_path.unlink(missing_ok=True)
        raise

    try:
        pub_fd = os.open(pub_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, PUBLIC_KEY_MODE)
    except FileExistsError as exc:
        key_path.unlink(missing_ok=True)
        raise SigningError(f"refusing to overwrite existing public key {pub_path}") from exc
    try:
        with os.fdopen(pub_fd, "wb") as handle:
            handle.write(pub_bytes)
    except Exception:
        pub_path.unlink(missing_ok=True)
        key_path.unlink(missing_ok=True)
        raise

    return key_path, pub_path


def load_private_key(key_path: Path, passphrase: bytes) -> Ed25519PrivateKey:
    """Load an encrypted PKCS#8 Ed25519 private key from ``key_path``."""
    if not passphrase:
        raise SigningError("passphrase must not be empty")
    try:
        pem_bytes = Path(key_path).read_bytes()
    except OSError as exc:
        raise SigningError(f"cannot read private key {key_path}: {exc}") from exc
    encrypted_header = b"-----BEGIN ENCRYPTED " + b"PRIVATE KEY-----"
    if encrypted_header not in pem_bytes:
        raise SigningError(f"{key_path}: private key must be an encrypted PKCS#8 PEM file")
    try:
        key = serialization.load_pem_private_key(pem_bytes, password=passphrase)
    except (ValueError, TypeError) as exc:
        raise SigningError(f"cannot decrypt or parse private key {key_path}: {exc}") from exc
    if not isinstance(key, Ed25519PrivateKey):
        raise SigningError(f"{key_path}: expected an Ed25519 private key")
    return key


def public_key_bytes_from_private(private_key: Ed25519PrivateKey) -> bytes:
    """Extract the raw 32-byte Ed25519 public key from ``private_key``."""
    return private_key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )


def sign_bytes(private_key: Ed25519PrivateKey, data: bytes) -> bytes:
    """Sign ``data`` with ``private_key`` and return the 64-byte raw Ed25519 signature."""
    sig = private_key.sign(data)
    if len(sig) != RAW_SIGNATURE_BYTES:
        raise SigningError(f"unexpected signature length {len(sig)}")
    return sig


def _parse_key_object(raw: object, source: Path) -> TrustedKey:
    if not isinstance(raw, dict):
        raise SigningError(f"{source}: public key entry must be a JSON object")
    allowed = {"key_id", "algorithm", "tink_variant", "public_key_base64", "public_key_hex"}
    extra = set(raw) - allowed
    if extra:
        raise SigningError(f"{source}: unexpected public key fields {sorted(extra)}")
    required = {"key_id", "algorithm", "tink_variant", "public_key_base64"}
    missing = required - set(raw)
    if missing:
        raise SigningError(f"{source}: missing required public key fields {sorted(missing)}")

    key_id = validate_key_id(raw["key_id"])
    if raw["algorithm"] != ALGORITHM:
        raise SigningError(f"{source}: algorithm must be {ALGORITHM!r}, got {raw['algorithm']!r}")
    if raw["tink_variant"] != TINK_VARIANT:
        raise SigningError(
            f"{source}: tink_variant must be {TINK_VARIANT!r}, got {raw['tink_variant']!r}"
        )
    b64_text = raw["public_key_base64"]
    if not isinstance(b64_text, str) or not b64_text:
        raise SigningError(f"{source}: public_key_base64 must be a non-empty string")
    try:
        pub_bytes = base64.b64decode(b64_text, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise SigningError(f"{source}: invalid public_key_base64: {exc}") from exc
    if (
        len(pub_bytes) != RAW_PUBLIC_KEY_BYTES
        or base64.b64encode(pub_bytes).decode("ascii") != b64_text
    ):
        raise SigningError(
            f"{source}: public_key_base64 must encode {RAW_PUBLIC_KEY_BYTES} "
            f"raw bytes in canonical Base64"
        )
    if "public_key_hex" in raw:
        hex_text = raw["public_key_hex"]
        if not isinstance(hex_text, str) or not HEX_PUB_RE.match(hex_text):
            raise SigningError(f"{source}: public_key_hex must be 64 lowercase hex characters")
        if bytes.fromhex(hex_text) != pub_bytes:
            raise SigningError(
                f"{source}: public_key_hex disagrees with public_key_base64 for key {key_id!r}"
            )
    return TrustedKey(key_id=key_id, public_key_bytes=pub_bytes)


def validate_trusted_keyset(
    keys: Sequence[TrustedKey], source: Path = Path("<keys>")
) -> tuple[TrustedKey, ...]:
    """Validate a 1..2 trusted key ring (unique key_ids and unique raw public keys)."""
    if not (1 <= len(keys) <= MAX_TRUSTED_KEYS):
        raise SigningError(
            f"{source}: trusted key ring must contain 1 or {MAX_TRUSTED_KEYS} keys, got {len(keys)}"
        )
    seen_ids: set[str] = set()
    seen_pubs: set[bytes] = set()
    for key in keys:
        if key.key_id in seen_ids:
            raise SigningError(f"{source}: duplicate key_id {key.key_id!r} in trusted key ring")
        if key.public_key_bytes in seen_pubs:
            raise SigningError(
                f"{source}: duplicate public key bytes in trusted key ring (key_id {key.key_id!r})"
            )
        seen_ids.add(key.key_id)
        seen_pubs.add(key.public_key_bytes)
    return tuple(keys)


def load_trusted_keys(path: Path) -> tuple[TrustedKey, ...]:
    """Load 1..2 trusted Ed25519 public keys from a JSON file or directory of ``*.json`` files."""
    path = Path(path)
    if path.is_dir():
        files = sorted(p for p in path.iterdir() if p.is_file() and p.suffix == ".json")
        if not files:
            raise SigningError(f"{path}: no .json public key files found")
        keys: list[TrustedKey] = []
        for file_path in files:
            keys.extend(_load_keys_from_file(file_path))
        return validate_trusted_keyset(keys, path)
    if path.is_file():
        return validate_trusted_keyset(_load_keys_from_file(path), path)
    raise SigningError(f"{path}: public keys path does not exist")


def _load_keys_from_file(file_path: Path) -> list[TrustedKey]:
    try:
        raw = json.loads(file_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise SigningError(f"{file_path}: cannot read public key JSON: {exc}") from exc
    if isinstance(raw, list):
        if not raw:
            raise SigningError(f"{file_path}: empty public key list")
        return [_parse_key_object(item, file_path) for item in raw]
    if isinstance(raw, dict) and "keys" in raw and isinstance(raw["keys"], list):
        if set(raw) != {"keys"} or not raw["keys"]:
            raise SigningError(f"{file_path}: keyset object must contain only non-empty 'keys'")
        return [_parse_key_object(item, file_path) for item in raw["keys"]]
    return [_parse_key_object(raw, file_path)]


def verify_signature_isolated(
    raw_sig: bytes,
    raw_manifest_bytes: bytes,
    trusted_keys: Sequence[TrustedKey],
) -> str:
    """Verify ``raw_sig`` over ``raw_manifest_bytes`` using isolated single-key verifiers.

    Returns the ``key_id`` of the single trusted key that verified the signature,
    BEFORE any JSON parsing of ``raw_manifest_bytes`` takes place.
    """
    validated_keys = validate_trusted_keyset(trusted_keys)
    if len(raw_sig) != RAW_SIGNATURE_BYTES:
        raise SigningError(
            f"manifest.sig must be exactly {RAW_SIGNATURE_BYTES} bytes, got {len(raw_sig)}"
        )
    for key in validated_keys:
        if key.verify(raw_sig, raw_manifest_bytes):
            return key.key_id
    raise SigningError(
        "manifest.sig failed Ed25519 signature verification against all trusted keys"
    )
