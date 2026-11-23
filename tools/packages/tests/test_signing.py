"""Unit tests for offline Ed25519 key generation, signing, and RFC 8032 / Tink vectors."""

import base64
import json
import os
import stat
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from seerah_packages import signing
from seerah_packages.signing import SigningError, TrustedKey


def test_rfc8032_and_tink_no_prefix_test_vectors():
    """Verify wire compatibility with RFC 8032 §7.1 Test 1 & Test 2 (Tink NO_PREFIX / RAW)."""
    # RFC 8032 Section 7.1 TEST 1 (empty message)
    seed_1 = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    pub_1 = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    msg_1 = b""
    sig_1 = bytes.fromhex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
        "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    )
    priv_1 = Ed25519PrivateKey.from_private_bytes(seed_1)
    assert signing.public_key_bytes_from_private(priv_1) == pub_1
    assert signing.sign_bytes(priv_1, msg_1) == sig_1

    # RFC 8032 Section 7.1 TEST 2 (1-byte message 0x72)
    seed_2 = bytes.fromhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
    pub_2 = bytes.fromhex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    msg_2 = bytes.fromhex("72")
    sig_2 = bytes.fromhex(
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
        "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
    )
    priv_2 = Ed25519PrivateKey.from_private_bytes(seed_2)
    assert signing.public_key_bytes_from_private(priv_2) == pub_2
    assert signing.sign_bytes(priv_2, msg_2) == sig_2

    key_1 = TrustedKey(key_id="rfc8032-1", public_key_bytes=pub_1)
    key_2 = TrustedKey(key_id="rfc8032-2", public_key_bytes=pub_2)
    assert signing.verify_signature_isolated(sig_1, msg_1, [key_2, key_1]) == "rfc8032-1"
    assert signing.verify_signature_isolated(sig_2, msg_2, [key_1, key_2]) == "rfc8032-2"
    with pytest.raises(SigningError, match="failed Ed25519 signature verification"):
        signing.verify_signature_isolated(sig_1, b"wrong", [key_1, key_2])


def test_generate_keypair_permissions_o_excl_and_loading(tmp_path: Path):
    passphrase = b"correct-horse-battery-staple"
    key_path, pub_path = signing.generate_keypair("seerah-2026-01", tmp_path, passphrase)
    assert key_path.is_file()
    assert pub_path.is_file()
    assert stat.S_IMODE(key_path.stat().st_mode) == 0o600
    assert stat.S_IMODE(pub_path.stat().st_mode) == 0o644

    # Refuses to overwrite existing key or pub file
    with pytest.raises(SigningError, match="refusing to overwrite"):
        signing.generate_keypair("seerah-2026-01", tmp_path, passphrase)

    priv = signing.load_private_key(key_path, passphrase)
    with pytest.raises(SigningError, match="cannot decrypt"):
        signing.load_private_key(key_path, b"wrong-passphrase")

    # Unencrypted private key in tmp_path is refused
    unenc_path = tmp_path / "unencrypted.pem"
    unenc_bytes = priv.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    unenc_path.write_bytes(unenc_bytes)
    with pytest.raises(SigningError, match="encrypted PKCS#8 PEM"):
        signing.load_private_key(unenc_path, passphrase)


def test_load_trusted_keys_formats_hex_check_and_limits(tmp_path: Path):
    _, pub1 = signing.generate_keypair("key-2026-a", tmp_path / "k1", b"pass-a")
    _, pub2 = signing.generate_keypair("key-2026-b", tmp_path / "k2", b"pass-b")
    _, pub3 = signing.generate_keypair("key-2026-c", tmp_path / "k3", b"pass-c")

    # Single file
    keys_single = signing.load_trusted_keys(pub1)
    assert [k.key_id for k in keys_single] == ["key-2026-a"]

    # Directory with 2 keys (rotation window: current + next)
    rot_dir = tmp_path / "rotation"
    rot_dir.mkdir()
    (rot_dir / "a.json").write_bytes(pub1.read_bytes())
    (rot_dir / "b.json").write_bytes(pub2.read_bytes())
    keys_rot = signing.load_trusted_keys(rot_dir)
    assert [k.key_id for k in keys_rot] == ["key-2026-a", "key-2026-b"]

    # Optional public_key_hex matching and mismatching
    doc_a = json.loads(pub1.read_text(encoding="utf-8"))
    raw_a = base64.b64decode(doc_a["public_key_base64"])
    doc_a["public_key_hex"] = raw_a.hex()
    hex_ok = tmp_path / "hex_ok.json"
    hex_ok.write_text(json.dumps(doc_a), encoding="utf-8")
    assert signing.load_trusted_keys(hex_ok)[0].public_key_bytes == raw_a

    doc_a["public_key_hex"] = "00" * 32
    hex_bad = tmp_path / "hex_bad.json"
    hex_bad.write_text(json.dumps(doc_a), encoding="utf-8")
    with pytest.raises(SigningError, match="public_key_hex disagrees"):
        signing.load_trusted_keys(hex_bad)

    # > 2 keys rejected
    (rot_dir / "c.json").write_bytes(pub3.read_bytes())
    with pytest.raises(SigningError, match="1 or 2 keys"):
        signing.load_trusted_keys(rot_dir)

    # Duplicate key_id rejected
    dup_id = tmp_path / "dup_id.json"
    d1 = json.loads(pub1.read_text(encoding="utf-8"))
    d2 = json.loads(pub2.read_text(encoding="utf-8"))
    d2["key_id"] = d1["key_id"]
    dup_id.write_text(json.dumps([d1, d2]), encoding="utf-8")
    with pytest.raises(SigningError, match="duplicate key_id"):
        signing.load_trusted_keys(dup_id)

    # Duplicate public key bytes with different key_id rejected
    dup_pub = tmp_path / "dup_pub.json"
    d2_same_pub = dict(d1, key_id="key-2026-alias")
    dup_pub.write_text(json.dumps({"keys": [d1, d2_same_pub]}), encoding="utf-8")
    with pytest.raises(SigningError, match="duplicate public key bytes"):
        signing.load_trusted_keys(dup_pub)


@pytest.mark.parametrize("bad_id", ["", "UPPER", "-leading", "a" * 65, "bad_underscore", "a/b"])
def test_validate_key_id_rejects_invalid_ids(bad_id: str):
    with pytest.raises(SigningError, match="invalid key_id"):
        signing.validate_key_id(bad_id)


def test_read_passphrase_from_fd():
    r_fd, w_fd = os.pipe()
    try:
        os.write(w_fd, b"secret-passphrase\n")
        os.close(w_fd)
        w_fd = -1
        assert signing.read_passphrase(r_fd) == b"secret-passphrase"
    finally:
        if w_fd >= 0:
            os.close(w_fd)
        os.close(r_fd)
