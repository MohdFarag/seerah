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

*(Atomic `<filesDir>/content/` storage, Hilt wiring, and release key guards land in Phase 3.)*

## Test

```sh
cd android && ./gradlew :core:content:check
```
