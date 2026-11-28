<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Hostile Design, Security, Cryptographic, and Resilience Audit: `v1-android-package-and-map-spec.md`

**Target:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md)
**Verified Against:** [signing.py](../../../tools/packages/src/seerah_packages/signing.py), [archive.py](../../../tools/packages/src/seerah_packages/archive.py), [manifest.schema.json](../../../content/schema/manifest.schema.json), [map.schema.json](../../../map-data/schema/map.schema.json), [ADR 0003](../../adr/0003-v1-architecture-and-modules.md), [ADR 0004](../../adr/0004-build-and-quality-tooling.md), [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../../adr/0008-update-hosting-and-index.md), [ADR 0009](../../adr/0009-map-data-pipeline.md), [signing-runbook.md](../../signing-runbook.md), [check_no_trial_code.py](../../../tools/android/check_no_trial_code.py), [backup_rules.xml](../../../android/app/src/main/res/xml/backup_rules.xml), [data_extraction_rules.xml](../../../android/app/src/main/res/xml/data_extraction_rules.xml), [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt), [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt), [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt), [StyleJson.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt), and [MapStyleSpec.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/MapStyleSpec.kt).

---

## Executive Triage

The overall architecture (isolated single-key Tink `NO_PREFIX` verify-before-parse, strict `FileChannel` ZIP container auditing, pure-Kotlin PMTiles inspection, atomic `v<version>-g<gen>/` + `active.json` pointer switching, and keeping `:core:map` benchmark-only until trial close) is sound. However, the audit uncovered **6 Blockers (`B1–B6`)**, **10 Majors (`M1–M10`)**, and **5 Nits (`N1–N5`)** across concurrency/crash-recovery races, `StatFs` / block-slack storage exhaustion, `kotlinx.serialization` type/null/schema traps, ZIP/DEFLATE/PMTiles validation holes, key-binding & release-variant guard gaps, and Python-to-Kotlin wire divergences.

| ID | Severity | Area | Title |
| --- | --- | --- | --- |
| **B1** | Blocker | Concurrency / Crash | Unsynchronized startup `cleanupOrphanedFiles()` races `PackageInstaller` and can delete active/staged versions |
| **B2** | Blocker | Storage / Crash | Post-Step-11 failure leaves `finalVersionDir` orphaned on disk; `StatFs(staging)` crashes before dir creation & ignores 4 KiB block slack |
| **B3** | Blocker | Release Guard | `TrustedKeysGuardTest` only checks the `debug` variant by default and misses `src/release/` resource leaks |
| **B4** | Blocker | JSON / D5 Gate | `ReconstructedArea(id)` DTO breaks D5 (`UnsupportedReconstructedAreas`) on unknown future schemas; `explicitNulls = false` hides forbidden `null` fields |
| **B5** | Blocker | PMTiles / Native | `PmTilesInspector` leaves `root_directory` / `leaf_directory` / varint / header-overlap attacks unvalidated before native MapLibre C++ mmap |
| **B6** | Blocker | ZIP / Cross-Tool | `StrictZipReader` (`<= 16_384` entries) disagrees with `archive.py` (`4 MiB` manifest); `InflaterInputStream` ignores trailing compressed junk and throws uncaught exceptions |
| **M1** | Major | Crypto / Keyring | `TrustedKeyRing` `ignoreUnknownKeys` / `{"keys": []}` discrepancy with `signing.py`; missing exception guard on invalid 32-byte Ed25519 keys |
| **M2** | Major | Crypto / Reset | Rule A compares `key_id` string only without key-material binding; `allowLocalProvenance` parameter creates release policy-bypass surface |
| **M3** | Major | JSON / `map.json` | `Set<String>` silently swallows duplicate `sources.base.layers`; `JsonElement` `.intOrNull`/`.content` ignores `isString`; `tile_size == 256` contradicts `:core:map` wiring |
| **M4** | Major | ZIP Security | Missing `flagBits` allowlist (bits `0x40`/`0x2000`), CD `diskNumberStart != 0` check, and mandatory `manifest.json`/`manifest.sig` CRC-32 validation |
| **M5** | Major | Startup / Performance | Synchronous Hilt injection in `SeerahApplication.onCreate()` risks `StrictMode` disk-read violations and pollutes `benchmark` cold-start open-time |
| **M6** | Major | TOCTOU / Storage | Retaining `CONTENT` `package.zip` via second read creates TOCTOU window; `MAP` input `File` should use a single pinned `FileChannel` |
| **M7** | Major | Crash / Recovery | `nextGen` computation ignores higher orphan `v*-g*` generation on disk; active cold-start verification of `active.json` vs `manifest.json` under-specified |
| **M8** | Major | R8 / Verification | Phase 3 exit gate never executes Tink `SignatureConfig2026` or `kotlinx.serialization` inside an R8-shrunk APK |
| **M9** | Major | Map Binding | `resolveMapFiles` throws `IllegalArgumentException` on missing files on disk (violating ADR 0003) and lacks explicit `PackageKind.MAP` failure path |
| **M10** | Major | Update Budget | `estimateDownloadAndInstallRequiredBytes` lacks an exact formula aligning with ADR 0009 `~3×` free-space budget and `CONTENT` retention |
| **N1** | Nit | Draft Hygiene | Unresolved drafting question (`"or ignore? Wait:..."`) left in spec line 432 |
| **N2** | Nit | Manifest Dates | `Instant.parse()` accepts fractional seconds (`.123Z`) forbidden by `manifest.schema.json` and `archive.py` |
| **N3** | Nit | PMTiles Parsing | Signed Kotlin `Byte` (`-128..127`) trap on `uint8` PMTiles header fields (`min_zoom`/`max_zoom`) and unchecked `addressed_tiles_count` / `bounds` ordering |
| **N4** | Nit | Gradle / Build | Missing `androidx.annotation` / `javax.inject` DAGP `bundle("hilt")` considerations and `:core:model` `init` vs parser validation boundary |
| **N5** | Nit | Concurrency / UI | Immediate Step 15 `oldActiveDir` deletion can cause transient `ENOENT` on MapLibre tile threads before Compose `setStyle` reloads `vNew` |

---

## Blockers

### B1 — Unsynchronized startup `cleanupOrphanedFiles()` races `PackageInstaller` and can destroy active or staged packages

- **Severity:** Blocker
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L34) (`D4`, L184, L529, L540–554); [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt#L10-L13)
- **Finding & Recommendation:**
  - `D4` launches `PackageStorageMaintenance.cleanupOrphanedFiles()` asynchronously on `Dispatchers.IO` inside `SeerahApplication.onCreate()`. Meanwhile, Section 7 (L529) places the `Mutex` inside `PackageInstaller` rather than around all storage mutations in `PackageStorage`.
  - If an installation (in tests, future `:core:update`, or an immediate user action after launch) runs concurrently with startup cleanup:
    1. Between Step 7 and Step 11, `cleanupOrphanedFiles()` deletes everything inside `staging/` (L552), destroying the in-progress staging folder mid-extraction.
    2. Between Step 11 (`Files.move(stagingDir, finalVersionDir)`) and Step 13 (`active.json` atomic swap), `cleanupOrphanedFiles()` reads the old `active.json`, sees `finalVersionDir` (`vNew`) is not `active.json.active_dir` (L553), and **deletes `vNew`**. Step 13 then atomically points `active.json` to the deleted directory, bricking offline access to both `vOld` and `vNew`.
  - **Recommendation:** Move the single shared `Mutex` (injected or owned by a `@Singleton` `PackageStorage` / storage lock) so **every** read-modify-write and cleanup operation (`PackageInstaller.install`, `PackageStorageMaintenance.cleanupOrphanedFiles`, and initial `active.json` quarantine on corruption) acquires the exact same `Mutex`.

### B2 — Disk-full & post-Step-11 crash/recovery gaps (`StatFs(nonExistent)`, missing 4 KiB block slack, and leaked `finalVersionDir`)

- **Severity:** Blocker
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L517-L546)
- **Finding & Recommendation:**
  1. **First-launch `StatFs` crash:** Step 6 (L536) checks `AvailableSpaceProvider.availableBytes(dir)` before Step 7 creates `stagingDir` (L537). Calling Android `android.os.StatFs(path)` on a directory that does not yet exist throws `IllegalArgumentException` (`ENOENT`).
  2. **Block-slack underestimation on `CONTENT` packages:** Line 519 sums raw `entry.sizeBytes` plus a flat `32 MiB` margin. On Android EXT4/F2FS (`4 KiB` block size), a `content` package with `10,000` 500-byte JSON files consumes `~40.96 MiB` on disk versus `5 MiB` raw `payloadBytes` — exhausting space mid-extraction despite passing `StatFs`.
  3. **Leaked `finalVersionDir` on post-Step-11 failure / `ENOSPC`:** Step 16 (`finally` L546) only deletes `stagingDir`. If Step 12 (`fsyncDir`) or Step 13 (`active.json.tmp` write/`force`/`move`) fails (for example due to `ENOSPC` or cancellation if `NonCancellable` wasn't entered before Step 11), `stagingDir` has already been renamed to `finalVersionDir` (`1.37 GiB`), leaving `1.37 GiB` orphaned on a full disk until the next app cold restart.
  4. **Uncaught `ENOSPC`:** An `IOException` caused by `ENOSPC` during Step 8 or Step 13 must map to `PackageFailure.InsufficientStorage` (when `ErrnoException.errno == OsConstants.ENOSPC` in the cause chain) or `PackageFailure.IoFailure`, never escaping as an unhandled exception.
  - **Recommendation:**
    - Call `contentRootDir.mkdirs()` (or pass `context.filesDir`) before constructing `StatFs`, and wrap `StatFs` `IllegalArgumentException` into `IoFailure`.
    - Compute disk footprint per file rounded up to `4_096` bytes (`((sizeBytes + 4095L) ushr 12) shl 12`, minimum `4_096L` or `0L` for empty files as applicable) plus `active.json` and directory overhead.
    - Wrap Steps 11–15 inside `withContext(NonCancellable)` and track `var movedFinalDir: File? = null`; if an exception occurs after Step 11 and before Step 13 completes, delete `movedFinalDir` and `activeTmp` immediately in `catch`/`finally`.
    - Recursively `fsync` every created parent directory inside `stagingDir/data/...` (not only `stagingDir` and `stagingDir.parent`) before Step 11.

### B3 — `TrustedKeysGuardTest` checks only the `debug` build by default and misses `src/release/` key leaks

- **Severity:** Blocker
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L32); [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt#L44-L48); [check_no_trial_code.py](../../../tools/android/check_no_trial_code.py)
- **Finding & Recommendation:**
  - Spec L186 & L612 rely on `:app` unit test `TrustedKeysGuardTest` to guarantee no RFC 8032 or test key ships in `release`.
  - In AGP 9 (`AndroidApplicationConventionPlugin.kt` L44–48), host unit tests are enabled for `debug` and explicitly enabled for `benchmark` — **not `release`**. Running `./gradlew check` only runs `:app:testDebugUnitTest` (which merges `src/debug/` + `src/main/`), never `release` merged resources (`src/release/res/raw/trusted_package_keys.json`).
  - Furthermore, checking only for the two RFC 8032 test keys misses ad-hoc test keys generated with `seerah_packages keygen` during development.
  - **Recommendation:** Add a release-APK verification step in CI (extending [check_no_trial_code.py](../../../tools/android/check_no_trial_code.py) or adding a sibling `check_release_keys.py` alongside `check_no_trial_code.py` in `.github/workflows/android.yml`) that extracts `res/raw/trusted_package_keys.json` (or scans raw resources) directly from `app-release.apk`, verifies valid `TrustedKeyRing` JSON (`{"keys": []}` or `1..2` valid keys), and rejects RFC 8032 public keys and test `key_id` prefixes (`test*`, `rfc8032*`, `dev*`, `debug*`). Keep `TrustedKeysGuardTest` in `:core:content` / `:app` as a secondary check on `src/main`.

### B4 — `ReconstructedArea(val id: String)` and `explicitNulls = false` violate D5 and `manifest.schema.json` contracts

- **Severity:** Blocker
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L282); [manifest.schema.json](../../../content/schema/manifest.schema.json#L68-L80); [map.schema.json](../../../map-data/schema/map.schema.json#L217-L220)
- **Finding & Recommendation:**
  1. **D5 broken by premature DTO schema:** `map.schema.json` L217–220 deliberately constrains `reconstructed_areas` to `maxItems: 0` because the reconstruction item schema is an undecided open decision ([open-decisions.md](../../open-decisions.md#L10)). Spec L282 invents `data class ReconstructedArea(val id: String)`. If a future map package ships `reconstructed_areas: [{"title_ar": "...", "footprint": ...}]` without an `"id"` field (or strings), `MapMetadataParser` deserializing `List<ReconstructedAreaDto>` fails with `MissingFieldException` -> `PackageFailure.InvalidMapMetadata` **before** reaching D5's check -> **failing to return `PackageFailure.UnsupportedReconstructedAreas(count)`**.
  2. **`explicitNulls = false` hides forbidden nulls:** Spec L422 sets `explicitNulls = false` on the shared `Json` instance. In `kotlinx.serialization-json`, `explicitNulls = false` silently turns missing keys into `null` AND accepts explicit `"tool_commit": null` / `"image": null` on a nullable DTO property. On `kind == "content"`, [manifest.schema.json](../../../content/schema/manifest.schema.json#L73-L78) forbids `tool_commit` and `image` keys from existing at all (`not: anyOf: [required: [tool_commit], required: [image]]`). A DTO checking `toolCommit != null || image != null` will wrongly pass `"tool_commit": null`. Also, if any non-null default value is ever added to a nullable DTO field, `explicitNulls = false` makes missing vs `null` asymmetric.
  - **Recommendation:**
    - Parse `reconstructed_areas` in the wire DTO as `List<JsonElement>` (or `JsonArray`), enforce `if (reconstructedAreas.isNotEmpty()) return Failure(UnsupportedReconstructedAreas(reconstructedAreas.size))` first, and remove the speculative `ReconstructedArea(val id: String)` model until the reconstruction schema is decided.
    - Check presence of `tool_commit` / `image` keys in `content` manifests against the parsed `JsonObject` (or use `explicitNulls = true` with explicit `= null` defaults only where optional, rejecting `"tool_commit": null` on `content` and `"liability": null` on `map.json`).

### B5 — `PmTilesInspector` leaves `root_directory`, `leaf_directory`, varints, and header overlaps unvalidated before native MapLibre C++ reads the file

- **Severity:** Blocker
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L461-L480); [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L211-L277)
- **Finding & Recommendation:**
  - `PmTilesInspector` is the final security boundary before MapLibre Native (`libmaplibre.so` C++) memory-maps and parses `base.pmtiles` and `relief.pmtiles`. Currently, Spec L461–479 only validates the 127-byte header bounds and decompresses/parses the `metadata` JSON slice. It leaves critical PMTiles v3 attack vectors unchecked:
    1. **Header overlap & zero-length root:** Only `metadata_offset >= 127` is checked (L466). A crafted archive (via a compromised key or disk corruption) can set `root_offset = 0`, `root_length = 0`, `leaf_directory_offset = 0` (when `leaf_directory_length > 0`), or `tile_data_offset < 127`.
    2. **Unbounded directory gzip bombs & varint bombs:** `root_directory` (and `leaf_directory`) share `internal_compression` (`2 = GZIP`). `PmTilesInspector` never decompresses or inspects `root_directory`, allowing a gzip bomb or malformed unsigned LEB128 varint (overlong `> 10` bytes, truncated varint, or a `num_entries` varint claiming `2^62` entries to trigger native `std::vector::reserve` OOM/abort inside MapLibre C++).
    3. **Uncaught `GZIPInputStream` exceptions & off-by-one cap:** Line 471 specifies *"hard decompressed cap `<= 1_048_576 + 1` bytes"* — reading `1_048_577` bytes must **reject** (`> 1_048_576`), not accept `1_048_577` bytes. `GZIPInputStream` also throws `ZipException` / `EOFException` on truncated/corrupt streams; if unmapped, these crash instead of returning `PackageFailure.InvalidPmTiles`.
  - **Recommendation:**
    - Require `root_offset >= 127`, `1 <= root_length <= 16_384` (per PMTiles v3 spec, header + root dir sit within the first 16 KiB, or cap `<= 1_048_576`), and whenever `leaf_directory_length > 0` or `tile_data_length > 0`, require their offsets `>= 127`. Also verify `tile_entries_count > 0` and `tile_contents_count > 0` (non-negative `Long`s).
    - Decompress `root_directory` (if `internal_compression == 2`, capped strictly at `<= 1_048_576` bytes with `> 1_048_576` rejected) and validate the PMTiles v3 directory binary structure: decode `num_entries` unsigned LEB128 (`<= 10` bytes, `1 <= num_entries <= 65_535`, and `num_entries <= remainingBytes`), then walk all `num_entries` (`tile_id` deltas, `run_length`, `length`, `offset`) verifying strictly canonical `<= 10`-byte LEB128 varints, monotonic `tile_id`s, exact byte consumption (`cursor == decompressedRoot.size`), and bounded entry offsets/lengths against `leaf_directory_length` (`run_length == 0`) or `tile_data_length` (`run_length > 0`).
    - Catch all `IOException` / `ZipException` / `EOFException` from `GZIPInputStream` cleanly into `PackageFailure.InvalidPmTiles`.

### B6 — `StrictZipReader` diverges from `archive.py` on entry caps (`16_384` vs `4 MiB` manifest) and leaves `InflaterInputStream` trailing-byte and exception edge cases open

- **Severity:** Blocker
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L384-L411); [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L36-L40)
- **Finding & Recommendation:**
  1. **Cross-tool cap mismatch:** Spec L384 caps `totalEntries <= 16_384` (and `cdSize <= 8_388_608`), whereas `archive.py` allows up to `65_534` entries (`allowZip64=True`, non-ZIP64 limit `0xFFFE`) and sets `MAX_CONTENT_MANIFEST_BYTES = 4_194_304` (which holds `~28,900` entries formatted at `indent=2`). A valid signed `content` package with `18,000` files passes `archive.py verify` yet fails on Android with `InvalidContainer`.
  2. **DEFLATE trailing-data smuggling & uncaught exceptions in `InflaterInputStream`:** Spec L411 wraps `InflaterInputStream(sliceStream, Inflater(true), 65_536)` and checks 1 extra uncompressed byte after `manifestEntry.sizeBytes`. However:
     - `Inflater` stops decoding at the DEFLATE `BFINAL=1` end-of-block symbol without requiring `sliceStream` to reach EOF (`compressedSize`), allowing arbitrary unauthenticated smuggled bytes inside `[dataOffset, dataOffset + compressedSize)` after the DEFLATE block.
     - Truncated or malformed DEFLATE streams cause `InflaterInputStream.read()` to throw `EOFException` or `ZipException` (wrapping `DataFormatException`), which must be explicitly mapped to `PackageFailure.PayloadIntegrityError` / `InvalidContainer`.
     - For `0`-byte `DEFLATED` entries (`manifest.schema.json` allows `size: minimum 0`), the read loop must execute at least one `read(..., 1)` so a 0-byte manifest header cannot mask a non-empty DEFLATE zip bomb.
  - **Recommendation:**
    - Align both [archive.py](../../../tools/packages/src/seerah_packages/archive.py) and `StrictZipReader` on one explicit `MAX_ZIP_ENTRIES` (`65_534` with `MAX_CENTRAL_DIRECTORY_BYTES = 8_388_608`, or enforce `16_384` in `archive.py` as well).
    - After reading `manifestEntry.sizeBytes` from `InflaterInputStream` (including attempting a 1-byte read even when `sizeBytes == 0`), assert `inflater.finished() && inflater.bytesRead == compressedSize` (no truncated DEFLATE and no trailing smuggled bytes inside the compressed slice), and catch `ZipException`/`EOFException`/`IOException` into `PackageFailure`.

---

## Majors

### M1 — `TrustedKeyRing` schema discrepancy (`{"keys": []}` & strict fields) and unhandled Tink key-construction exceptions

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L32); [signing.py](../../../tools/packages/src/seerah_packages/signing.py#L192-L289)
- **Finding & Recommendation:**
  - Spec D2 (L32) states `{"keys": []}` uses *"the exact `tools/packages/` `signing.py` schema"*, but [signing.py](../../../tools/packages/src/seerah_packages/signing.py#L280-L286) explicitly rejects empty lists `[]` and `{"keys": []}` (`"keyset object must contain only non-empty 'keys'"`).
  - Spec L352 requires rejecting unknown keys inside key objects, and `signing.py` L285 rejects extra top-level keys alongside `"keys"` (`set(raw) != {"keys"}`). If Android reuses the `Json { ignoreUnknownKeys = true }` instance from Section 5 to parse `trusted_package_keys.json`, unknown fields in `trusted_package_keys.json` will be silently ignored instead of failing with `InvalidKeyRing`. Also clarify that top-level `[]` is rejected (`InvalidKeyRing`), while only `{"keys": []}` maps to `KeyRingState.Unconfigured`.
  - During `IsolatedTinkEd25519Verifier` construction (L355), `Ed25519PublicKey.create(...)` or `getPrimitive(...)` can throw `GeneralSecurityException` or `IllegalArgumentException` on invalid 32-byte material; uncaught, this violates L296 (*"exceptions are bugs"*).
  - **Recommendation:** Use a dedicated strict `Json { ignoreUnknownKeys = false; isLenient = false }` (or strict `JsonObject` key-set validation) for `TrustedKeyRingLoader`, verify `TrustedKeyRing` + `IsolatedTinkEd25519Verifier` key construction catches `GeneralSecurityException`/`IllegalArgumentException` into `PackageFailure.InvalidKeyRing`, and document the intentional `{"keys": []}` pre-ceremony exception vs `signing.py`.

### M2 — Rule A lacks public-key fingerprint binding, and `allowLocalProvenance` creates an unnecessary release policy-bypass surface

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L431-L443); [signing-runbook.md](../../signing-runbook.md#L82-L90); [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md#L78-L80)
- **Finding & Recommendation:**
  1. **Rule A key-ID collision trap:** Spec L436–441 checks only `installed.keyId` against `trustedKeyRing.keys.map { it.keyId }`. If an operator ever rotated a compromised key while accidentally reusing the `key_id` string with different 32-byte key material, Rule B would activate and preserve the compromised package's high `version` floor.
  2. **`allowLocalProvenance` in production code:** Spec L431 retains an `allowLocalProvenance: Boolean` parameter inside `:core:content` (*"only if explicitly passed by internal tests/debug hooks — production Dagger binding hard-codes `allowLocalProvenance = false`"*). [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md#L78-L80) requires release builds to carry no development-only bypass path, and [manifest.schema.json](../../../content/schema/manifest.schema.json#L45) already permits `sha256:<64hex>` in synthetic test fixtures (`tools/packages/` golden fixtures can pass `--image sha256:000...` or a real digest!).
  - **Recommendation:**
    - Store `public_key_sha256` (or canonical `public_key_base64`) in `active.json` (and verify on cold start / Rule A check that **both** `key_id` and raw public key bytes match an entry in the current `TrustedKeyRing`; if either differs, treat as retired/revoked under Rule A).
    - Remove `allowLocalProvenance` from `:core:content` production code entirely and require `^sha256:[0-9a-f]{64}$` unconditionally on Android `MAP` manifests; use a synthetic `sha256:<64hex>` digest in Android golden fixtures.

### M3 — `Set<String>` swallows duplicate layers, `JsonPrimitive` lacks `.isString` checks, and `tile_size == 256` contradicts `:core:map` wiring

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L78); [map.schema.json](../../../map-data/schema/map.schema.json#L59-L122); [StyleJson.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt#L82-L88)
- **Finding & Recommendation:**
  1. **Silent layer deduplication:** [map.schema.json](../../../map-data/schema/map.schema.json#L62) sets `"uniqueItems": true` on `sources.base.layers`. In Spec L256, `BaseSourceMetadata.layers` is `Set<String>`. If the `kotlinx.serialization` DTO also declares `Set<String>`, `["land", "land", "coast"]` silently deduplicates into `setOf("land", "coast")` instead of failing the *"distinct non-blank strings"* check (L456).
  2. **`kotlinx.serialization` `JsonPrimitive` coercion trap:** In `PmTilesInspector` (L473–478), calling `.jsonPrimitive.intOrNull` or `.jsonPrimitive.content` accepts `"tile_schema": "1"` (a JSON string!) or `"fingerprint": 12345` / `"fingerprint": true` (a JSON number/boolean!) unless `isString` is explicitly checked (`!primitive.isString` for integers, `primitive.isString` for strings).
  3. **`tile_size` contradiction:** Spec L20, L78, L177 refactor `:core:map` (`ReliefSource.tileSize`, `StyleJson.kt`) *"so `data/map.json` `sources.relief.tile_size` flows all the way to MapLibre"*, yet Spec L457 hard-codes `tile_size == 256` in `MapMetadataParser`. Also `map.json` `credits` uniqueness on `credit.id` is unchecked in both `map.schema.json` and Spec L459.
  - **Recommendation:**
    - Use `List<String>` in the `BaseSourceMetadataDto`, verify `layers.size == layers.toSet().size` before converting to `Set<String>`, and enforce unique `credit.id`s.
    - Helper-wrap `JsonObject` inspection in `PmTilesInspector` so `requireInt` checks `primitive is JsonPrimitive && !primitive.isString` and `requireString` checks `primitive is JsonPrimitive && primitive.isString`.
    - Resolve the `tile_size` contract explicitly: either accept positive power-of-two tile sizes (`tileSize > 0` / `{256, 512}`) in `MapMetadataParser` to justify `:core:map`'s refactor, or state explicitly that `tile_schema: 1` restricts `tile_size == 256` (keeping `:core:map`'s `tileSize` property solely for `tile_schema: 2+` readiness).

### M4 — `StrictZipReader` omits `flagBits` allowlist, CD `diskNumberStart == 0`, and mandatory `manifest.json`/`manifest.sig` CRC-32 checks

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L388-L404); [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L819-L932)
- **Finding & Recommendation:**
  - Spec L389–399 checks only `flagBits & 0x01 == 0` and `flagBits & 0x08 == 0`, leaving unsupported PKWARE ZIP flags unchecked (`0x0020` patched data, `0x0040` strong encryption, `0x2000` masked/encrypted central directory), as well as Central Directory `diskNumberStart` (which must be `0` in a single-disk archive).
  - Spec L396 + L399 describe checking UTF-8 `fileNameBytes` + `validateEntryPath()` (`32..126` ASCII). Decoding raw ZIP name bytes via `String(bytes, UTF_8)` converts invalid byte sequences into `U+FFFD` rather than preserving raw byte identity; checking raw bytes directly (`b in 0x20..0x7E` and `cdNameBytes.contentEquals(lfhNameBytes)`) prevents any UTF-8 vs CP437 differential.
  - Spec L402 makes CRC-32 verification optional on `manifest.sig` (*"check CRC-32 if desired"*) and omits it on `manifest.json`, whereas Python `archive.py` (`zf.open()`) enforces CRC-32 on both. Note also that [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L819) unpacks `_crc` from LFH without comparing it to `info.CRC`; Kotlin `StrictZipReader` should enforce CRC-32 on **all** entries (`manifest.json`, `manifest.sig`, and `data/*`) and ensure unsigned `uint32` comparison (`Long` masked with `0xFFFF_FFFFL`).
  - **Recommendation:** Enforce `(flagBits and 0xF7FF) == 0` (or `flagBits == 0` since `archive.py` L424 writes `0` for ASCII paths, optionally allowing `0x0006` deflate tuning / `0x0800` UTF-8 flag), enforce CD `diskNumberStart == 0`, compare raw filename bytes `0x20..0x7E` between CD and LFH, and require CRC-32 match on `manifest.json` and `manifest.sig`.

### M5 — Startup injection in `SeerahApplication.onCreate()` risks `StrictMode` violations and pollutes `benchmark` cold-start measurements

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L34); [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt#L10-L31); [TrialActivity.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt#L98-L100)
- **Finding & Recommendation:**
  - [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt#L16-L31) enables `StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog()` in `debug` builds, and `benchmark` (`TrialActivity.kt` L99) measures cold-start open time from `Process.getStartElapsedRealtime()`.
  - Because `benchmark` inherits `:app`'s `main` source set (including `SeerahApplication` and its Hilt graph), injecting `PackageStorageMaintenance` into `SeerahApplication` causes Hilt to eagerly instantiate `PackageStorageMaintenance`'s transitive dependency graph on the **main thread** during `Application.onCreate()`. If `PackageStorage`, `TrustedKeyRingLoader`, or `IsolatedTinkEd25519Verifier` reads `res/raw/trusted_package_keys.json`, reads `active.json`, or initializes Tink inside a constructor/`init`/`@Provides` method, every launch triggers a `StrictMode` main-thread disk-read violation and adds I/O + class-loading overhead to `benchmark` cold-start measurements.
  - **Recommendation:** Ensure `PackageStorageMaintenance` (and `@Provides` bindings in `ContentModule`) perform **zero** file I/O, JSON parsing, or Tink keyset instantiation during constructor/DI injection — deferring all resource reads (`trusted_package_keys.json`), disk scans, and Tink primitive construction lazily onto `Dispatchers.IO` inside `suspend` functions using an application-scoped `CoroutineScope(SupervisorJob() + ioDispatcher)`. (Note also: if `PackageStorage` exposes `StateFlow<InstalledPackageInfo?>` synchronously, start with `null` or load asynchronously on `Dispatchers.IO` so `Application.onCreate()` stays I/O-free.)

### M6 — TOCTOU risk when retaining `CONTENT` `package.zip` and reading `MAP` input files

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L44)
- **Finding & Recommendation:**
  - Spec L44 states `PackageInstaller` accepts *"any local readable `.zip` file (`File`)"*, and Step 8 (L538) extracts `data/*` and copies `archiveFile` to `stagingDir/package.zip` when `kind == CONTENT`.
  - If `archiveFile` lives in a cache or shared directory and is read twice (once by `StrictZipReader`, then again by `archiveFile.copyTo(...)`), a concurrent modification between verification and copying lets `stagingDir/package.zip` diverge from the verified bytes — breaking ADR 0007 Decision 7 (future Room DB rebuilds from `package.zip` assume `package.zip` was verified!). Similarly, reopening `File` by path across Steps 3 and 8 on a `MAP` archive creates a TOCTOU window.
  - **Recommendation:** For `CONTENT`, copy `archiveFile` into `stagingDir/package.zip` **first** (with bounded copy `<= MAX_RELEASE_FILE_BYTES`) and run `StrictZipReader` + Ed25519 verification + extraction directly over `stagingDir/package.zip`. For `MAP`, open a single read-only `FileChannel` once at Step 3 and pass that open channel through Step 8 so path re-resolution cannot swap the file mid-install.

### M7 — Generation counter (`nextGen`) and `active.json` cold-start integrity edge cases

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L490-L555)
- **Finding & Recommendation:**
  1. **`nextGen` collision with orphaned `v*-g*` dirs:** Spec L532 sets `nextGen = (active?.generation ?: highestExistingGen) + 1`. If a prior install crashed after Step 11 (`v6-g2` exists alongside valid `active.json` at `generation = 1`) and `install()` runs before startup cleanup finishes (or in tests calling `install()` directly), `active?.generation` is `1`, so `nextGen = 2` collides with the leftover `v6-g2` directory. Computing `nextGen = maxOf(active?.generation ?: 0, highestExistingGen) + 1` avoids all collisions even before orphan cleanup runs.
  2. **Cold-start validation of `active.json` & `<active_dir>`:** Spec L555 addresses corrupt `active.json`, but leaves unaddressed what happens if `active.json` parses cleanly while `<active_dir>` is missing, contains traversal (`active_dir = "../other"`), or `<active_dir>/manifest.json` is missing/corrupt/disagrees with `active.json.version` or `active.json.key_id`. Meanwhile, because of Rule A (offline continuity when a key is retired), cold-start loading **must not** reject an active package solely because its `key_id` is absent from the current `TrustedKeyRing`.
  - **Recommendation:** Enforce `active_dir` matches `^v[1-9][0-9]*-g[1-9][0-9]*$`, verify canonical containment inside `kindDir`, verify `<active_dir>/manifest.json` + `<active_dir>/manifest.sig` exist and `manifest.json` (`<= 4 MiB` / `1 MiB`) parses with matching `kind`, `version`, and `key_id` (and for `MAP`, required `data/{base.pmtiles,map.json,relief.pmtiles}` exist), falling back to `null` + quarantine (`IoFailure`) without crashing if any check fails.

### M8 — Phase 3 exit gate omits runtime verification of Tink `SignatureConfig2026` and `kotlinx.serialization` under R8 Full Mode

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L152); [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt#L31-L35)
- **Finding & Recommendation:**
  - Known Risks (L631) flags that AGP 9 R8 Full Mode may strip or warn on `tink-android:1.24.0` (which shades protobuf-lite and transitively pulls `gson`, `jsr305`, and `error_prone_annotations`), and recommends *"a release/shrunk instrumented or verification smoke check"*. Yet the Phase 3 Exit Gate (L623) only runs `./gradlew check` (unshrunk JVM tests) and `./gradlew assembleRelease` (compile/link only) — nothing actually **runs** `IsolatedTinkEd25519Verifier` or `kotlinx.serialization` inside the R8-minified bytecode!
  - **Recommendation:** Add an explicit Phase 3 exit check running at least one instrumented smoke test against the minified `release`/`benchmark` APK (or an R8-shrunk androidTest variant) executing `TrustedKeyRingLoader` + `IsolatedTinkEd25519Verifier` + `PackageManifestParser` on a golden signed package so R8 rule omissions fail immediately in Phase 3 rather than when `:core:update` ships later.

### M9 — `MapPackageBinding.resolveMapFiles` mixes programming assertions with runtime disk failures

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L569); [ADR 0003](../../adr/0003-v1-architecture-and-modules.md#L113)
- **Finding & Recommendation:**
  - Spec L569 has `resolveMapFiles(packageInfo: InstalledPackageInfo, metadata: MapMetadata): MapFiles` throw `IllegalArgumentException` both when passed a `CONTENT` package (a caller bug) **and** when `base.pmtiles` / `relief.pmtiles` fails `.isFile` or canonical containment on disk (an I/O/storage corruption condition). Throwing exceptions on runtime storage loss conflicts with [ADR 0003](../../adr/0003-v1-architecture-and-modules.md#L113) (*"Expected failures are `sealed` results; exceptions are bugs"*).
  - **Recommendation:** Keep `require(packageInfo.kind == PackageKind.MAP)` as a programming precondition, and either return a sealed result from `resolveMapFiles` for missing/non-regular files on disk or keep file-presence/containment verification inside `:core:content`'s typed `PackageResult` API when loading the active map package.

### M10 — `estimateDownloadAndInstallRequiredBytes` formula is unspecified

- **Severity:** Major
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L525); [ADR 0009](../../adr/0009-map-data-pipeline.md#L257)
- **Finding & Recommendation:**
  - Spec L525 introduces `estimateDownloadAndInstallRequiredBytes(kind, archiveBytes, uncompressedPayloadBytes)` for future `:core:update` pre-download gating against ADR 0009's `~3×` free-space budget, without specifying the formula or edge behavior (`archiveBytes` for cache download + `uncompressedPayloadBytes` for extraction + optional retained `archiveBytes` for `CONTENT` + block-slack + `SAFETY_MARGIN_BYTES` + overflow safety).
  - **Recommendation:** Define the exact overflow-safe `Long` formula in the spec (distinguishing `MAP`: `archiveBytes + uncompressedPayloadBytes + overhead` on top of existing `vOld`, vs `CONTENT`: `archiveBytes /* cache */ + archiveBytes /* retained package.zip */ + uncompressedPayloadBytes + overhead`), and verify negative/overflow inputs fail closed.

---

## Nits

### N1 — Unresolved drafting question in Spec line 432

- **Severity:** Nit
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L432)
- **Finding & Recommendation:** Line 432 still contains raw drafting text: *"forbid `tool_commit` and `image` if present (`InvalidManifest`), or ignore? Wait: check `manifest.schema.json` line 73..."*. Remove the unresolved question and state the final rule directly (aligned with B4: reject presence of `tool_commit` or `image` on `kind == "content"`).

### N2 — `Instant.parse()` accepts fractional seconds and out-of-range years disallowed by `manifest.schema.json`

- **Severity:** Nit
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L430); [manifest.schema.json](../../../content/schema/manifest.schema.json#L47-L50); [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L74-L84)
- **Finding & Recommendation:** `java.time.Instant.parse` / `DateTimeFormatter.ISO_INSTANT` accepts fractional seconds (`2026-01-01T00:00:00.123Z`) and `+10000-...` years, whereas `manifest.schema.json` and `archive.py` enforce `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` plus valid calendar date. Enforce both the exact regex and strict calendar parsing (`DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withResolverStyle(ResolverStyle.STRICT)`).

### N3 — Kotlin signed `Byte` trap on PMTiles `uint8` header fields and degenerate header bounds

- **Severity:** Nit
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L88-L89)
- **Finding & Recommendation:** Bytes `7`, `96..101`, and `118` in the PMTiles v3 header are unsigned `uint8`. Reading `bytes[101].toInt()` without `and 0xFF` treats `128..255` as negative integers (`-128..-1`). Also validate `min_lon_e7 < max_lon_e7` and `min_lat_e7 < max_lat_e7` on `relief.pmtiles` header bounds, and check `addressed_tiles_count > 0L` (signed `> 0L` rather than `!= 0L`, rejecting `>= 2^63`).

### N4 — DAGP `buildHealth` `javax.inject` bundle coverage and `:core:model` `init` invariant alignment

- **Severity:** Nit
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L136); [android/build.gradle.kts](../../../android/build.gradle.kts#L37-L50)
- **Finding & Recommendation:**
  - `bundle("hilt")` in [android/build.gradle.kts](../../../android/build.gradle.kts#L37-L45) does not include `javax.inject:javax.inject` / `jakarta.inject:jakarta.inject-api` or `dagger.hilt.components.SingletonComponent` (which `:core:content` will import directly in `ContentModule` and `@Inject` constructors, unlike `:app` today). Expect to verify `buildHealth` (`severity("fail")`) when `:core:content` lands.
  - Specify the exact `init { require(...) }` invariants on `:core:model` types (`GeoBounds`, `MapMetadata`, `PackageManifest`, `PackageFileEntry`) and state explicitly that `:core:content` parsers validate all constraints **before** constructing `:core:model` objects so invalid packages always return `PackageResult.Failure`.
  - Also note in Spec L407 to drop the contradictory parenthetical *"exact allowed set check on `map.json` / `pmtiles` below"* since L407 & L458 allow additive files under ADR 0009 Decision 10 (and optionally run `PmTilesInspector` basic header + no-`trial` check on any additive `data/*.pmtiles` if present).

### N5 — Step 15 eager deletion of `oldActiveDir` during live `StateFlow` map reload

- **Severity:** Nit
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L544-L545)
- **Finding & Recommendation:** Deleting `oldActiveDir` immediately at Step 15 right after emitting `vNew` to `StateFlow` creates a brief window where MapLibre's background tile worker threads may try to `open()` `vOld/data/*.pmtiles` before the UI thread processes the `StateFlow` emission and calls `map.setStyle(...)`. Document this handoff clearly so future UI/`:feature:explore` wiring either pauses map tile access / detaches old style before install or treats transient old-dir delete failure gracefully until next startup cleanup.

---

## Repository Artifact Verification Matrix

| Artifact | Verified Invariants | Discrepancies / Gaps Found |
| --- | --- | --- |
| [signing.py](../../../tools/packages/src/seerah_packages/signing.py) | `MAX_TRUSTED_KEYS = 2`, `key_id` regex, canonical Base64 + optional hex, `Ed25519` + `NO_PREFIX`, isolated single-key verification | `signing.py` rejects `{"keys": []}` whereas Android D2 uses `{"keys": []}` as `Unconfigured` (**M1**) |
| [archive.py](../../../tools/packages/src/seerah_packages/archive.py) | Single-disk non-ZIP64, contiguous byte-0 layout, LFH=CD check, `S_IFMT` + `0x10` dir check, `manifest.json`/`sig` first, `ZIP_STORED` for `map` | No `16_384` entry cap (**B6**); `archive.py` ignores `_crc` in LFH (**M4**); `archive.py` checks exact `MAP_REQUIRED_DATA_FILES` while Android allows additive files (**N4**) |
| [manifest.schema.json](../../../content/schema/manifest.schema.json) | `version` `1..1_000_000`, `min_app_version >= 1`, `files` `size <= 2_147_483_647`, `content` forbids `tool_commit`/`image` | `explicitNulls = false` leaks `"tool_commit": null` on `content` (**B4**); `Instant.parse` looser than `created` regex (**N2**) |
| [map.schema.json](../../../map-data/schema/map.schema.json) | `tile_schema: 1`, `encoding <-> tile_format`, `credits` requirements, `reconstructed_areas` `maxItems: 0`, `uniqueItems` on `layers` | `Set<String>` hides duplicate `layers` (**M3**); `tile_size: 256` vs `:core:map` refactor (**M3**); `ReconstructedArea(id)` breaks D5 (**B4**) |
| [ADR 0003](../../adr/0003-v1-architecture-and-modules.md), [0007](../../adr/0007-content-formats-compatibility-and-signing.md), [0008](../../adr/0008-update-hosting-and-index.md), [0009](../../adr/0009-map-data-pipeline.md) | Sealed failure model, verify-before-parse, `CONTENT` `.zip` retention vs `MAP` no retention, additive `tile_schema: 1` evolution, D5 disclosure gate | `resolveMapFiles` throws on disk corruption (**M9**); `allowLocalProvenance` in prod code (**M2**); `~3×` `estimateDownloadAndInstallRequiredBytes` unspecified (**M10**) |
| [signing-runbook.md](../../signing-runbook.md) | Dual-key rotation (`current` + `next`), isolated single-key rule, version-floor reset on key removal | Rule A checks `key_id` string only without public-key material binding (**M2**) |
| [check_no_trial_code.py](../../../tools/android/check_no_trial_code.py) | Manifest check for `app.seerah.trial` on `app-release.apk` | Does not check `res/raw/trusted_package_keys.json` in `release` APK (**B3**) |
| [backup_rules.xml](../../../android/app/src/main/res/xml/backup_rules.xml) & [data_extraction_rules.xml](../../../android/app/src/main/res/xml/data_extraction_rules.xml) | Both exclude `<exclude domain="file" path="content/" />` matching `<filesDir>/content/` | Verified consistent across API <= 11 and API 12+ |
| [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt) & [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt) | `:app -> :core:content -> :core:model` and `:core:map -> :core:model` allowed; `:core:map` remains `benchmark`-only; Licensee allows `Apache-2.0` | `release` unit tests disabled by default (**B3**); R8 Full Mode Tink/serialization execution untested in Phase 3 (**M8**); `hilt` bundle check (**N4**) |
