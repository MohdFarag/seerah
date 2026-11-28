# :core:content

Offline package verification and installation module ([ADR 0003](../../../docs/adr/0003-v1-architecture-and-modules.md), [ADR 0007](../../../docs/adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../../../docs/adr/0008-update-hosting-and-index.md), [v1 Android spec](../../../docs/design/v1-android-package-and-map-spec.md)).

## Public API (`app.seerah.content`)

- **Models:** `PackageKind`, `PackageFileEntry`, `PackageManifest`, `InstalledPackageInfo`.
- **Results and failures:** `PackageResult`, `PackageFailure` (`KeyRingNotConfigured`, `AppUpdateRequired`, `DowngradeOrSameVersion`, `UnsupportedReconstructedAreas`, `InsufficientStorage`, `InvalidPackage`, `IoFailure`), `InvalidPackageCause`.
- **Contracts:** `TrustedPublicKey`, `KeyRingState`, `VerifyOutcome`, `Ed25519Verifier`, `InstalledPackageState`, `PackageStorage`, `PackageStorageMaintenance`, `PackageInstaller`, `AvailableSpaceProvider`, `AppVersionCodeProvider`, `PackageStorageLogger`.

## Verification guarantees

- **Verify before parse:** Ed25519 `NO_PREFIX` signatures (`manifest.sig`, isolated per trusted key in Tink `KeysetHandle`) are verified against raw `manifest.json` bytes before any UTF-8 decoding or JSON parsing runs.
- **Hardened ZIP reading:** Inspects EOCD, Central Directory, Local File Headers, raw ASCII entry paths, file modes, contiguous offset-0 layout, CRC-32 checksums, SHA-256 digests, and bounded `DEFLATE` streams over a single pinned `FileChannel`.
- **PMTiles v3 & `tile_schema: 1` checks:** Validates 127-byte headers, binary LEB128 directory entries, bounded metadata gzip streams, Goal 9 trial markers, and non-empty `reconstructed_areas` refusal without coupling to host build-pipeline provenance.

## Test fixtures

`src/test/resources/fixtures/` contains small synthetic `Apache-2.0` test fixtures (no private keys; public key from RFC 8032 Section 7.1 Test 1 only) generated deterministically by [generate_android_test_fixtures.py](../../../tools/packages/scripts/generate_android_test_fixtures.py) and checked for byte-for-byte drift by `tools/packages/tests/test_android_fixtures.py`.

## Atomic storage, Hilt DI, and release key guard

- **Atomic `<filesDir>/content/` layout:** Active packages live under `content/<kind>/v<version>-g<generation>/` and are exposed via `active.json`. Cold starts read `active.json` offline without re-verifying Ed25519 signatures or hashing PMTiles.
- **Safety and concurrency:** Reads, cleanup sweeps, and active pointer swaps share one `Mutex` with `NonCancellable` atomic rollback on switch failures. Installations copy/pin packages early against TOCTOU, require 4 KiB block-aligned extracted size plus a `32 MiB` safety margin, and map `ENOSPC` errors to `InsufficientStorage`.
- **Key-compromise version-floor reset:** When the active key (`key_id`, `public_key_sha256`) is removed from the trusted keyring, the monotonic version floor resets for a clean replacement install without allowing arbitrary downgrades while an active key remains trusted ([signing runbook](../../../docs/signing-runbook.md)).
- **Hilt bindings:** `ContentModule.kt` (`SingletonComponent`) binds `PackageStorage`, `PackageStorageMaintenance`, and `PackageInstaller` with lazy raw-resource keyring loading (`src/main/res/raw/trusted_package_keys.json`, zero constructor disk/IPC I/O for `StrictMode`).
- **Pre-ceremony key guard:** `src/main/res/raw/trusted_package_keys.json` holds `{"keys": []}` (fail-closed) before the owner key ceremony; [check_trusted_keys.py](../../../tools/android/check_trusted_keys.py) verifies in CI that release APKs and Android source keyrings never embed test or RFC 8032 keys. Embed the production public keyring before shipping beta APKs.

## Test

```sh
cd android && ./gradlew :core:content:check
cd android && ./gradlew :core:content:connectedDebugAndroidTest -Pseerah.minifyContentAndroidTest=true
```
