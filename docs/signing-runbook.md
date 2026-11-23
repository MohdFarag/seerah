# Package Signing, Key Rotation, and Compromise Runbook

Operational runbook for generating, storing, rotating, and revoking the offline Ed25519 keys that sign Seerah `content` and `map` packages ([ADR 0007](adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](adr/0008-update-hosting-and-index.md), [ADR 0009](adr/0009-map-data-pipeline.md), [v1 map pipeline specification](design/v1-map-pipeline-spec.md)).

---

## 1. Key Format and Wire Contract

- **Algorithm:** Ed25519 (RFC 8032 pure EdDSA).
- **Private key file (`<key_id>.key.pem`):** PKCS#8 encrypted PEM, file mode `0600`, encrypted with a strong owner passphrase (`BestAvailableEncryption`). Unencrypted private keys are refused by [tools/packages](../tools/packages/README.md) and blocked from git by `detect-private-key`.
- **Public key file (`<key_id>.pub.json`):**

  ```json
  {
    "algorithm": "Ed25519",
    "key_id": "seerah-2026-01",
    "public_key_base64": "<standard padded Base64 of raw 32-byte public key>",
    "tink_variant": "NO_PREFIX"
  }
  ```

- **Google Tink Android compatibility (`tink-android`):**
  - `tink_variant: "NO_PREFIX"` corresponds to `Ed25519Parameters.Variant.NO_PREFIX` (`OutputPrefixType.RAW`): raw 32-byte public key and raw 64-byte signature (`manifest.sig`) over the exact bytes of `manifest.json`.
  - **Isolated single-key verification rule:** On Android (and in `seerah_packages.signing.verify_signature_isolated`), each trusted `(key_id, public_key_bytes)` entry in the app's trusted keyring (`1..2` keys) **must** be wrapped in its own **isolated single-key `KeysetHandle` / `PublicKeyVerify` instance**. Verify `manifest.sig` against raw `manifest.json` bytes **before** parsing `manifest.json`, record which single `verifying_key_id` succeeded, parse `manifest.json`, and assert `manifest.key_id == verifying_key_id`.

---

## 2. Generating a New Keypair (`keygen`)

Run on the owner's workstation outside any container, targeting an offline directory outside all git checkouts (for example `~/.config/seerah-keys/` on an encrypted volume, backed up to an offline encrypted USB drive):

```sh
PYTHONPATH=tools/packages/src python3 -m seerah_packages keygen \
  --key-id seerah-2026-01 \
  --out-dir ~/.config/seerah-keys
```

1. Enter and confirm a high-entropy passphrase when prompted.
2. Verify that `~/.config/seerah-keys/seerah-2026-01.key.pem` has mode `0600` and never leaves the owner's encrypted storage.
3. Copy only the public key JSON (`seerah-2026-01.pub.json`) into the app's trusted public keyring when embedding it in the Android client.

---

## 3. Signing and Verifying a Production Map Package (`pack-map` & `verify`)

After `python3 tools/map/run.py -- build --coverage coverage` stages `map-data/dist/map-staging-<fp8>/`:

```sh
PYTHONPATH=tools/packages/src python3 -m seerah_packages pack-map \
  --staging map-data/dist/map-staging-<fp8> \
  --version 1 \
  --min-app-version 1 \
  --key ~/.config/seerah-keys/seerah-2026-01.key.pem \
  --key-id seerah-2026-01 \
  --out map-data/dist/packages
```

When publishing a new map version (`N > 1`), pass `--previous-manifest <path-to-previous-manifest.json>` so `pack-map` enforces `version > previous.version` before signing.

Verify any package against the trusted public key(s):

```sh
PYTHONPATH=tools/packages/src python3 -m seerah_packages verify \
  --package map-data/dist/packages/seerah-map-1.zip \
  --public-keys ~/.config/seerah-keys/seerah-2026-01.pub.json \
  --expected-kind map
```

---

## 4. Planned Key Rotation (`current` + `next`)

The trusted keyring holds at most **2 keys** (`current` and `next`, per [ADR 0007](adr/0007-content-formats-compatibility-and-signing.md) decision 9):

1. **Generate `next`:** Run `keygen --key-id seerah-<year>-<seq>` to create the new keypair.
2. **Ship dual-key app release:** Add `next`'s `.pub.json` alongside `current`'s `.pub.json` in the app's trusted keyring (`2` keys total) and ship an app update. Continue signing packages with `current` while users update.
3. **Switch signing to `next`:** Once the minimum supported `min_app_version` includes `next`, sign new packages with `--key-id <next>`.
4. **Retire old key:** In a subsequent app release, remove the old `current` public key so `next` becomes the sole trusted key.

---

## 5. Key Compromise Recovery (Emergency Revocation & Floor Reset)

If a private key (`<compromised_key_id>`) is ever leaked or suspected of compromise ([ADR 0007](adr/0007-content-formats-compatibility-and-signing.md) decision 9, [ADR 0008](adr/0008-update-hosting-and-index.md#consequences)):

1. **Generate a replacement key (`<new_key_id>`)** immediately on a clean workstation if a pre-staged `next` key is not already isolated from the compromise.
2. **Re-sign the latest verified clean `content` and `map` packages** with `<new_key_id>`, and re-sign `index.json` with `<new_key_id>`.
3. **Ship an emergency app update** that removes `<compromised_key_id>` from the embedded trusted keyring and enforces both anti-lockout resets on first launch:
   - **Reset the index `serial` floor:** Clear the stored `index.json` `serial` high-water mark whenever the previously accepted index was signed by a `key_id` no longer in the trusted keyring ([ADR 0008](adr/0008-update-hosting-and-index.md#consequences)).
   - **Reset the installed package `version` floor:** If the locally installed `content` or `map` package on disk was signed by a removed/revoked `key_id` (not present in the app's current trusted keyring), **discard that package's `version` from the monotonic `version` floor comparison** and replace the package with the latest valid package signed by a currently trusted key. This prevents an attacker who leaked a key from signing a high `version` (up to `1,000,000`) to permanently block legitimate updates after the compromised key is removed.
