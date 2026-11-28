# tools/packages

Shared host-side packaging and offline Ed25519 manifest signing for Seerah `content` and `map` packages ([ADR 0007](../../docs/adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../../docs/adr/0008-update-hosting-and-index.md), [ADR 0009](../../docs/adr/0009-map-data-pipeline.md), [v1 pipeline specification](../../docs/design/v1-map-pipeline-spec.md)).

It runs directly on the host — private keys never enter containers — and enforces:

- **Offline encrypted Ed25519 keys:** PKCS#8 encrypted PEM private keys (`0600`, created with `O_EXCL`) and Tink `Ed25519Parameters.Variant.NO_PREFIX` (`OutputPrefixType.RAW`) 32-byte raw public keys.
- **Isolated single-key verify-before-parse:** verifies raw 64-byte `manifest.sig` against raw `manifest.json` bytes using one isolated single-key verifier per trusted key (`1..2` keys during key rotation) **before** calling `json.loads()`, then checks `manifest["key_id"] == verifying_key_id`.
- **Goal 9 trial-isolation guards (`pack-map` and `verify`):** refuses any staging directory containing `metadata.json`, any `report.json` whose `"purpose"` is not `"production"`, any `"unknown"` provenance (`data_commit`, `tool_commit`, `image`, `fingerprint`), any `local:*` image reference (unless `--allow-local-provenance` is explicitly set for local testing), or any PMTiles v3 archive carrying `"trial"`, `"variant"`, or `"mode"` or missing `"tile_schema": 1`.
- **Deterministic streaming ZIP archives:** places `manifest.json` first and `manifest.sig` second (both `ZIP_STORED`), followed by lexicographically sorted `data/*` streamed in `1 MiB` chunks (`ZIP_STORED` mandatory for `map` packages so PMTiles range reads work without recompression; `ZIP_DEFLATED` allowed for `content` packages), with fixed timestamps (`2026-01-01T00:00:00`) and Unix permissions (`0100644`), capped at `< 2 GiB` (`2,147,483,647` bytes).

## Commands

Run from the repository root with `PYTHONPATH=tools/packages/src:tools/content/src`:

```sh
PYTHONPATH=tools/packages/src:tools/content/src python3 -m seerah_packages <command> [args]
```

| Command | What it does |
| --- | --- |
| `keygen --key-id <id> --out-dir <dir> [--passphrase-fd <fd>]` | Generates (creating a missing `<out-dir>` with mode `0700`) `<out-dir>/<id>.key.pem` (PKCS#8 encrypted Ed25519 private key, mode `0600`) and `<out-dir>/<id>.pub.json` (Tink `NO_PREFIX` raw 32-byte Base64 public key, mode `0644`). Refuses to overwrite existing files. |
| `pack-content --source <dir> --version <int> --min-app-version <int> --key <pem> --key-id <id> --out <dir>` | Compiles owner-approved YAML, signs the eight compressed JSON entries and verifies their schemas before publishing. Use `--strict-warnings` for editorial warnings. Local non-Git fixtures require `--allow-dirty --source-commit <sha>`. |
| `pack-map --staging <dir> --version <int> --min-app-version <int> --key <pem> --key-id <id> --out <dir> [--created <iso>] [--passphrase-fd <fd>] [--previous-manifest <json>] [--allow-local-provenance] [--force]` | Validates a `dist/map-staging-<fp8>/` staging directory, builds `manifest.json` ([content/schema/manifest.schema.json](../../content/schema/manifest.schema.json)), signs `manifest.sig`, streams `<out>/seerah-map-<version>.zip`, and self-verifies the archive before publishing. |
| `verify --package <zip> --public-keys <json_or_dir> [--expected-kind {content,map}] [--allow-local-provenance]` | Hardens the ZIP container (LFH vs Central Directory, no encryption/symlinks/traversal, and a layout a streaming reader can follow: entries contiguous from byte 0 with no data descriptors, extra fields, gaps or trailing bytes), verifies `manifest.sig` before parsing `manifest.json`, checks `key_id` binding, verifies every `data/*` entry's size and SHA-256, and (for `map` packages) validates `data/map.json` and both PMTiles v3 headers and metadata in-place. |

Exit codes: `0` for success, `1` when a packaging or verification check fails, `2` for a usage or argument error.

See [docs/signing-runbook.md](../../docs/signing-runbook.md) for key generation, offline storage, rotation, and compromise recovery.

## Android test fixtures

- [scripts/generate_android_test_fixtures.py](scripts/generate_android_test_fixtures.py) deterministically builds `trusted-keys.json`, `valid-map-v1.zip`, `valid-content-v1.zip`, and `trial-rejected-map-v1.zip` in `android/core/content/src/test/resources/fixtures/` using the RFC 8032 Test 1 vector (private seed kept transient in Python).
- [tests/test_android_fixtures.py](tests/test_android_fixtures.py) runs `verify` against those fixtures and fails CI if checked-in ZIPs drift from the generator.

## Dependencies and Tests

Dependencies are hash-locked in [requirements.txt](requirements.txt) (compiled from [requirements.in](requirements.in) with `uv pip compile --generate-hashes` and tracked by Dependabot):

```sh
pip install --require-hashes -r tools/packages/requirements.txt
PYTHONPATH=tools/packages/src:tools/content/src python3 -m pytest -c tools/packages/pyproject.toml tools/packages/tests
```
