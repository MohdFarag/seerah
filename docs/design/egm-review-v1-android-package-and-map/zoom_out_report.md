<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Zoom-Out Architectural Review: `docs/design/v1-android-package-and-map-spec.md`

**Target:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) (lines 1–635)
**Reviewer:** `egm-zoom-out` (read-only)
**Date:** 2026-11-27

---

## Overall Verdict

**Needs revision before owner approval (`D1–D5`) and implementation.** The core architectural direction is solid: deferring `:core:update` until ADR 0008 networking work starts (`D1`), validating the exact deterministic ZIP + isolated single-key Ed25519 Tink `NO_PREFIX` contract from `tools/packages/`, keeping `app.seerah.trial` untouched until Oppo A55 and Huawei MatePad 11.5 finish the map trial, and isolating `:core:map` behind plain models.

However, the current draft introduces **two Blockers**, **six Majors**, and **three Nits**:

1. It re-introduces a speculative `ReconstructedArea(val id: String)` model that violates `docs/open-decisions.md` and misroutes invalid non-empty arrays under `D5`.
2. `D4` wires `:core:content` startup cleanup into `SeerahApplication.onCreate()` on a false R8 rationale (Tink is unreachable from `cleanupOrphanedFiles()`, so R8 strips it anyway) while executing inside the `benchmark` trial process during open-time measurements.
3. It leaks `:core:content` packaging/storage internals (`PackageManifest`, `InstalledPackageInfo` with filesystem paths) into `:core:model` against ADR 0003, couples `:core:map`'s public ABI to `InstalledPackageInfo`, and declares `implementation(project(":core:model"))` in `:core:map` where DAGP `buildHealth` will require `api`.
4. It duplicates `tools/packages`'s host-side build-provenance validator inside Android (`PmTilesInspector`), states a false compatibility claim for `{"keys": []}` in `D2`, leaves speculative parameters (`ReliefSource.tileSize`, `allowLocalProvenance`, `estimateDownloadAndInstallRequiredBytes`) and unresolved draft hedges (`"forbid ... or ignore? Wait..."`, phantom `Time` DI binding), and over-specifies internal `.kt` file decomposition and wire-offset tables.

---

## Prioritized Findings

### Blockers

#### B1. Speculative `ReconstructedArea` model violates `open-decisions.md` and breaks D5 error classification

- **Severity:** Blocker
- **Affected Scope:** Overview (L18), Module layout (L146), Detailed Design §1 (L282, L292), §6.1 (L460)
- **Cross-check:** [`docs/open-decisions.md`](../../open-decisions.md) (L3, L10–11), [`docs/adr/0009-map-data-pipeline.md`](../../adr/0009-map-data-pipeline.md) (L225–226), [`map-data/schema/map.schema.json`](../../../map-data/schema/map.schema.json) (L217–220), [`docs/design/v1-map-pipeline-spec.md`](../v1-map-pipeline-spec.md) (L52, L256)
- **Simplicity & Boundary Rationale:**
  1. `open-decisions.md` states: *"Don't hard-code an answer to any of these. Keep each one configurable or leave it out until it is decided."* Both the historical reconstruction format and the disclosure UI remain undecided (`open-decisions.md` L10–11), and `map.schema.json` enforces `"maxItems": 0` on `reconstructed_areas` specifically to keep item structure open. Defining `data class ReconstructedArea(val id: String)` and `val reconstructedAreas: List<ReconstructedArea>` in `:core:model` guesses an item schema (`{id: String}`) that has no owner decision and can never be populated in a D5 client.
  2. Decoding `reconstructed_areas` into `List<ReconstructedAreaDto(val id: String)>` creates a runtime bug under D5: if a future map package populates `reconstructed_areas` with items that do not have an `"id"` field (or uses plain strings), JSON deserialization throws and returns `InvalidMapMetadata` instead of `PackageFailure.UnsupportedReconstructedAreas(count)`.
- **Recommendation:** Delete `ReconstructedArea` and `MapMetadata.reconstructedAreas` from `:core:model`. In `MapMetadataParser`, parse `reconstructed_areas` as a raw `JsonArray` (failing with `InvalidMapMetadata` if missing or not an array) and return `PackageFailure.UnsupportedReconstructedAreas(array.size)` whenever `array.isNotEmpty()`.

#### B2. `D4` (`SeerahApplication` wiring) fails its stated Tink R8 rationale and contaminates `benchmark` open-time runs

- **Severity:** Blocker
- **Affected Scope:** Owner Decisions `D4` (L34), Overview (L21), Architecture (L98, L181–185), Phased Roadmap Phase 3 (L623), Known Risks (L631)
- **Cross-check:** [`android/app/src/main/kotlin/app/seerah/SeerahApplication.kt`](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt) (L9–32), [`android/app/src/benchmark/AndroidManifest.xml`](../../../android/app/src/benchmark/AndroidManifest.xml), [`android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt`](../../../android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt) (L89–101), [`android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt) (L36–42), [`docs/engineering.md`](../../engineering.md) (L34), [`docs/adr/0003-v1-architecture-and-modules.md`](../../adr/0003-v1-architecture-and-modules.md) (L83), [`android/README.md`](../../../android/README.md) (L16)
- **Simplicity & Boundary Rationale:**
  1. *Broken R8 rationale:* `D4` claims injecting `PackageStorageMaintenance` into `SeerahApplication` to call `cleanupOrphanedFiles()` *"immediately exercises R8 full mode on `tink-android`"*. However, `cleanupOrphanedFiles()` only walks `<filesDir>/content/` and has zero call-graph reachability to `PackageInstaller`, `Ed25519Verifier`, or `com.google.crypto.tink`. Under R8 full mode (`isMinifyEnabled = true`), Tink and the verification pipeline are tree-shaken away as dead code unless forced with unnatural `-keep` rules in `consumer-rules.pro`.
  2. *Map trial `benchmark` interference:* The `benchmark` build type (`initWith(release)`) merges onto `main`'s `.SeerahApplication`. Injecting Hilt `:core:content` bindings and launching `Dispatchers.IO` cleanup inside `SeerahApplication.onCreate()` executes during `TrialActivity`'s cold open measurement (`Process.getStartElapsedRealtime()` to `FullyRendered`), altering the environment before the deferred Oppo A55 and Huawei MatePad 11.5 trial measurements finish.
  3. *Undefined coroutine scope & StrictMode hazard:* `SeerahApplication` runs `StrictMode.detectAll()` in debug, and `engineering.md` forbids `GlobalScope`. The spec neither defines an `@ApplicationScope` nor guards against Hilt initializing `TrustedKeyRing` or `active.json` synchronously on the main thread during `Application.onCreate()`.
- **Recommendation:** Drop `D4` (`PackageStorageMaintenance` in `SeerahApplication` and `:app -> :core:content`) from this spec until `:core:update` or first-launch onboarding actually introduces a caller that writes packages. Move `TrustedKeysGuardTest` to `:core:content/src/test/` (checking `res/raw/trusted_package_keys.json` directly), and verify Tink under R8 once `:app` has a real installation entrypoint (or via an isolated release smoke test without modifying `SeerahApplication`).

---

### Majors

#### M1. `:core:model` leaks `:core:content` packaging/storage internals, couples `:core:map`, and breaks `buildHealth` (`api` vs `implementation`)

- **Severity:** Major
- **Affected Scope:** Overview (L18), Architecture & Module Layout (L139–148, L174), §1 Public Models (L209–240), §7 (L544), §8 `resolveMapFiles` (L569)
- **Cross-check:** [`docs/adr/0003-v1-architecture-and-modules.md`](../../adr/0003-v1-architecture-and-modules.md) (L141–144, L171–176), [`android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt`](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt) (L27–37), [`android/core/map/build.gradle.kts`](../../../android/core/map/build.gradle.kts) (L10–18), [`android/build.gradle.kts`](../../../android/build.gradle.kts) (L47–51)
- **Simplicity & Boundary Rationale:**
  1. *ADR 0003 violation:* ADR 0003 reserves `:core:model` for shared Seerah domain/content models (`Phase`, `Event`, `Situation`, `Text`, `Source`, `Place`, dates), while `:core:content` owns package installation and storage. Putting `PackageKind`, `PackageFileEntry`, `PackageManifest`, and `InstalledPackageInfo` (including `sha256Hex`, `sourceCommit`, `toolCommit`, `image`, `rootDirPath`, `dataDirPath`, `retainedArchivePath`) into `:core:model` leaks `:core:content`'s internal storage layout and ZIP manifest format into the shared model module. Every future consumer of packaging status (`:core:update`, `:feature:settings`) already depends on `:core:content` (`:core:.* -> :core:.*` and `:feature:.* -> :core:.*` are allowed in `DependencyRules.kt`, and `D1` explicitly relies on `:core:update -> :core:content`).
  2. *Forced `:core:map` coupling:* `:core:map` only touches `InstalledPackageInfo` because `resolveMapFiles(packageInfo: InstalledPackageInfo, metadata: MapMetadata)` needlessly takes the entire package struct instead of `dataDir: File` (throwing `IllegalArgumentException` at runtime if passed a `CONTENT` package).
  3. *Redundant eager `manifest.json` reads:* `InstalledPackageInfo` duplicates `kind`, `version`, and `keyId` from `manifest: PackageManifest` while requiring `manifest` to be non-null. Because `active.json` stores only `{schema, version, generation, key_id, active_dir}`, constructing `InstalledPackageInfo` forces `PackageStorage` to read and parse `manifest.json` (up to 4 MiB for `content`) on disk even when callers only want `(version, keyId, dataDir)`.
  4. *DAGP `buildHealth` failure:* `MapPackageBinding.kt` exposes `:core:model` types (`MapMetadata`) in public functions (`MapMetadata.toCameraLimits()`, `MapMetadata.toStyleSpec()`), yet L174 declares `implementation(project(":core:model"))` in `:core:map`. Under `build.gradle.kts`'s `onAny { severity("fail") }`, `buildHealth` requires `api(project(":core:model"))` whenever `:core:model` types appear in public signatures.
- **Recommendation:**
  - Move `PackageKind`, `PackageFileEntry`, `PackageManifest`, and `InstalledPackageInfo` out of `:core:model` into `:core:content`.
  - Remove `manifest: PackageManifest` from `InstalledPackageInfo` (keep manifest inspection as a separate read if ever needed for self-audit).
  - Keep only shared map metadata (`GeoBounds`, `ReliefEncoding`, `MapSourcesMetadata`, `BaseSourceMetadata`, `ReliefSourceMetadata`, `MapCredit`, `MapMetadata`) in `:core:model`.
  - Change `resolveMapFiles(dataDir: File, metadata: MapMetadata): MapFiles` so `:core:map` has zero dependency on packaging/install types, and declare `api(project(":core:model"))` in `android/core/map/build.gradle.kts` (and `:core:content` where `:core:model` is in its public ABI).

#### M2. `PmTilesInspector` duplicates `tools/packages`'s host-build validator on Android and risks ADR 0009 Decision 10 compatibility

- **Severity:** Major
- **Affected Scope:** Overview (L19), §6.2 `PmTilesInspector` (L461–480)
- **Cross-check:** [`docs/adr/0009-map-data-pipeline.md`](../../adr/0009-map-data-pipeline.md) Decision 10 (L183), [`docs/design/map-trial-build.md`](../map-trial-build.md) G9 (L77), [`tools/packages/src/seerah_packages/archive.py`](../../../tools/packages/src/seerah_packages/archive.py) (`inspect_pmtiles_bytes`, `verify_map_payload_consistency`, L211–404)
- **Simplicity & Boundary Rationale:** `PmTilesInspector` ports all of `archive.py`'s host-side `inspect_pmtiles_bytes()` and `verify_map_payload_consistency()` to Kotlin: 127-byte little-endian section-bounds arithmetic, up-to-1 MiB gzip decompression with bomb guards, and strict JSON metadata cross-checks (`name == "seerah-map"`, non-empty `fingerprint`/`tool_commit`/`data_commit`/`image`, `baseMeta.fingerprint == reliefMeta.fingerprint`, `data_commit == manifest.sourceCommit`, `bounds ~= data_extent`). On Android, every archive byte has already passed Ed25519 verification against the owner's offline key and SHA-256 verification against `manifest.json`, and `pack-map` already enforces all cross-archive provenance consistency before signing. Requiring internal build-pipeline metadata keys (`fingerprint`, `name == "seerah-map"`, exact `fingerprint` match between both PMTiles) inside an Android `tile_schema: 1` client creates brittle coupling that goes against ADR 0009 Decision 10's permissive compatibility model.
- **Recommendation:** Leave build-provenance cross-checking (`fingerprint`, `tool_commit`, `data_commit`, `image`, `addressed_tiles_count`, bounds tolerance) to `tools/packages/` at signing time. On Android, keep `data/map.json` validation (the metadata the app actually uses) and limit PMTiles inspection at most to a lightweight header magic/version check (`"PMTiles\x03"`)—or drop PMTiles JSON metadata parsing on Android altogether.

#### M3. `D2` misstates `tools/packages/signing.py` schema compatibility and over-builds Android keyring parsing

- **Severity:** Major
- **Affected Scope:** Owner Decisions `D2` (L32), §3.1 (L346–353), Tests (L596)
- **Cross-check:** [`tools/packages/src/seerah_packages/signing.py`](../../../tools/packages/src/seerah_packages/signing.py) (`_load_keys_from_file`, `_parse_key_object`, L192–289), [`docs/signing-runbook.md`](../../signing-runbook.md) §1 (L11–25)
- **Simplicity & Boundary Rationale:**
  1. `D2` states that `{"keys": []}` uses *"the exact `tools/packages/` `signing.py` schema"* and is *"readable by both Android and `python3 -m seerah_packages verify --public-keys ...`"*. In reality, `signing.py` L285–286 explicitly fails on `{"keys": []}` with `SigningError("keyset object must contain only non-empty 'keys'")`.
  2. §3.1 implements three polymorphic JSON root shapes (`{"keys": [...]}`, `[...]`, single object `{...}`) plus optional `public_key_hex` cross-checking in Android's `TrustedKeyRingLoader`. The host CLI supports those formats because users pass standalone `.pub.json` files from `keygen` as well as keyset files; Android only ever reads one embedded resource (`res/raw/trusted_package_keys.json`) whose canonical format `D2` already fixes as `{"keys": [...]}`.
- **Recommendation:** Correct `D2` to note that `{"keys": []}` is an Android-only pre-ceremony placeholder (`signing.py verify` requires 1–2 keys), and simplify Android's `TrustedKeyRingLoader` to accept only `{"keys": [...]}` with `public_key_base64`.

#### M4. Premature public APIs and security-bypass toggle (`ReliefSource.tileSize`, `estimateDownloadAndInstallRequiredBytes`, `allowLocalProvenance`)

- **Severity:** Major
- **Affected Scope:** §1 `ReliefSourceMetadata.tileSize` (L265), §5.2 `allowLocalProvenance` (L431), §7 `estimateDownloadAndInstallRequiredBytes` (L525), §8 `MapStyleSpec.tileSize` / `StyleJson` (L563–566)
- **Cross-check:** [`map-data/schema/map.schema.json`](../../../map-data/schema/map.schema.json) (L119–122), [`android/core/map/src/main/kotlin/app/seerah/map/MapStyleSpec.kt`](../../../android/core/map/src/main/kotlin/app/seerah/map/MapStyleSpec.kt), [`android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt`](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt) (L39, L85), [`docs/adr/0007-content-formats-compatibility-and-signing.md`](../../adr/0007-content-formats-compatibility-and-signing.md) Decision 10 (L78–80)
- **Simplicity & Boundary Rationale:**
  1. *Speculative `tileSize` plumbing:* `map.schema.json` sets `"tile_size": { "const": 256 }` and `MapMetadataParser` (L457) rejects any `tile_size != 256`. Adding `tileSize: Int` to `ReliefSourceMetadata`, `ReliefSource`, `ReliefSource.Painted`, `ReliefSource.Value`, and `StyleJson` expands `:core:map`'s public API for a constant that is rejected if it is ever not `256`.
  2. *Premature `:core:update` helper:* `estimateDownloadAndInstallRequiredBytes(...)` is proposed solely for future `:core:update` (Explicit Non-Goal #1). Download and install paths may not even share the same filesystem (`cacheDir` vs `filesDir`), so specifying an uncalled helper ahead of the `:core:update` design is YAGNI.
  3. *`allowLocalProvenance` bypass flag in Android:* `tools/packages` needs `--allow-local-provenance` for local container tests on the host. Android never installs `local:*` map packages (`ADR 0007` Decision 10 uses unpacked folders in `debug` and `benchmark` source sets for unsigned local testing). Carrying `allowLocalProvenance: Boolean` inside production `:core:content` classes adds an unnecessary security-bypass knob; Android tests can use valid `sha256:<64-hex>` fixture strings.
- **Recommendation:** Keep `RELIEF_TILE_SIZE = 256` internal in `StyleJson.kt` (while `MapMetadataParser` validates `tile_size == 256`), defer `estimateDownloadAndInstallRequiredBytes` to the `:core:update` spec, and unconditionally enforce `^sha256:[0-9a-f]{64}$` on Android map manifests without an `allowLocalProvenance` parameter.

#### M5. Unresolved draft contradictions and phantom components (`Time` DI binding, `CONTENT` provenance hedge, `:core:model` serialization hedge, startup self-check, unused `StateFlow`)

- **Severity:** Major
- **Affected Scope:** Layout (L140, L169), §5.2 (L432), §6 (L449), §7 (L544)
- **Cross-check:** [`content/schema/manifest.schema.json`](../../../content/schema/manifest.schema.json) (L5, L73–78), [`docs/adr/0002-proposed-stack.md`](../../adr/0002-proposed-stack.md) (L25), [`docs/adr/0008-update-hosting-and-index.md`](../../adr/0008-update-hosting-and-index.md) (L84)
- **Simplicity & Boundary Rationale:**
  1. `ContentModule.kt` (L169) lists `Time/Space` bindings, yet nothing in `:core:content` reads current wall-clock time (and ADR 0008 explicitly forbids relying on device clocks).
  2. L432 leaves an unresolved author question inside the spec: *"forbid `tool_commit` and `image` if present (`InvalidManifest`), or ignore? Wait: check `manifest.schema.json` line 73..."*.
  3. L140 says `:core:model` uses `(+ kotlin-serialization plugin if serialized, or plain data classes)`, contradicting the explicit architectural decision in L189–198 (`Pure-JVM Serialization Boundary`) to keep `:core:model` free of `kotlin-serialization`.
  4. L449 adds *"(or when loading an already-installed active map folder with self-check)"* without specifying whether cold launch re-runs `data/map.json` / PMTiles checks (which would add cold-start I/O risk against ADR 0002's 1.5 s / 4 s open target).
  5. L544 mentions updating a singular `StateFlow<InstalledPackageInfo?>` on `PackageStorage`, even though `PackageStorage` manages two independent kinds (`CONTENT` and `MAP`) and Option A has no UI or update observer yet.
- **Recommendation:** Remove the phantom `Time` binding and the `:core:model` serialization parenthetical; resolve L432 cleanly (either reject `tool_commit`/`image` on `CONTENT` to match `manifest.schema.json` or ignore consistently via `ignoreUnknownKeys = true`); clarify L449 so heavy validation happens at install time only; and either specify per-kind active observation or defer `StateFlow` until `:feature:explore` / `:core:update` lands.

#### M6. Altitude drops ("weeds") and premature internal file/error decomposition in `:core:content`

- **Severity:** Major
- **Affected Scope:** Module layout (L156–169), §1–2 verbatim code blocks (L206–326), §4 (L377–415), §6.2 (L461–480), §7 (L531–546)
- **Cross-check:** [`docs/design/egm-review-map-trial-phase-2/zoom_out_report.md`](../egm-review-map-trial-phase-2/zoom_out_report.md) (L33–38), [`docs/design/egm-review-v1-map-pipeline/zoom_out_report.md`](../egm-review-v1-map-pipeline/zoom_out_report.md) (`M2`)
- **Simplicity & Boundary Rationale:**
  1. Prescribing 12 exact internal `.kt` file names inside `:core:content` (L156–169), 120 lines of verbatim Kotlin model/error definitions (L206–326), exact coroutine primitives (`NonCancellable`, `Mutex`, `65_536` / `256 KiB` buffer sizes), and byte-offset tables for ZIP and PMTiles v3 (L84–89, L381–400) already documented in `tools/packages/src/seerah_packages/archive.py` and the PMTiles v3 spec locks implementation structure rather than architecture and interfaces.
  2. Defining 15 distinct public `PackageFailure` subtypes (L310–326) forces every downstream caller (`:core:update`, `:feature:settings`) to handle exhaustive `when` branches for ~10 internal parsing/crypto errors (`InvalidKeyRing`, `InvalidContainer`, `InvalidSignature`, `UntrustedKeyId`, `InvalidManifest`, `UnexpectedKind`, `PayloadIntegrityError`, `InvalidMapMetadata`, `InvalidPmTiles`) that map to the same user-facing state ("corrupt or untrusted package").
- **Recommendation:** Keep the spec at architectural altitude: define `:core:content`'s public entry points, invariants, and wire contract references (`archive.py`, `manifest.schema.json`, `map.schema.json`), let implementation choose internal file splits, and collapse `PackageFailure` to UI-actionable cases (e.g., `KeyRingNotConfigured`, `AppUpdateRequired`, `InsufficientStorage`, `DowngradeOrSameVersion`, `UnsupportedReconstructedAreas`, `InvalidPackage(detail)`, `IoFailure(detail)`).

---

### Nits

#### N1. `D3` (`active.json` location/format) is an internal implementation choice, not an owner gate

- **Severity:** Nit
- **Affected Scope:** Owner Decisions `D3` (L33)
- **Cross-check:** [`docs/adr/0003-v1-architecture-and-modules.md`](../../adr/0003-v1-architecture-and-modules.md) (L91), [`docs/adr/0007-content-formats-compatibility-and-signing.md`](../../adr/0007-content-formats-compatibility-and-signing.md) (L89), [`AGENTS.md`](../../../AGENTS.md) (L104)
- **Simplicity & Boundary Rationale:** ADR 0003 selected DataStore for *user data* (`:core:userdata`), and ADR 0007 explicitly delegated *"the atomic pointer"* to the implementation spec. Using an atomic JSON pointer under `<filesDir>/content/<kind>/` (already excluded by `backup_rules.xml` and `data_extraction_rules.xml`) is a clean internal choice inside accepted ADRs and does not require an owner decision (`AGENTS.md` L104).

#### N2. Documentation deliverables and minor schema/JSON config mismatches

- **Severity:** Nit
- **Affected Scope:** §5.1 `Json` config (L422), §6.1 `relief_detail` (L458), Phased Roadmap Phase 3 (L623)
- **Cross-check:** [`android/README.md`](../../../android/README.md) (L16, L79), [`THIRD_PARTY_NOTICES.md`](../../../THIRD_PARTY_NOTICES.md) (L3, L57), [`docs/design/v1-map-pipeline-spec.md`](../v1-map-pipeline-spec.md) (L254), [`AGENTS.md`](../../../AGENTS.md) (L38–47)
- **Simplicity & Boundary Rationale:**
  - Phase 3 doc updates list `THIRD_PARTY_NOTICES.md`, which explicitly excludes Gradle/Licensee dependencies (and MapLibre stays benchmark-only in Option A), while omitting `android/README.md` (which states `seerah.jvm.library` and new `core/*` modules are added there when real code lands) and `docs/open-decisions.md` (`AGENTS.md` DoD #4).
  - L458 writes `sources.relief_detail`, whereas `v1-map-pipeline-spec.md` L254 uses `"relief-detail"` (hyphen).
  - L422 specifies `explicitNulls = false` in `Json { ... }`: verify whether explicit `"field": null` on defaulted nullable fields should be rejected (default `explicitNulls = true`) or tolerated.

#### N3. `fsyncDir` assumption across Android filesystems

- **Severity:** Nit
- **Affected Scope:** §7 Atomic Installation Protocol (L542–543)
- **Cross-check:** [`AGENTS.md`](../../../AGENTS.md) (L37)
- **Simplicity & Boundary Rationale:** `FileChannel.open(dirPath, READ).force(true)` works on typical Linux filesystems for directory `fsync`, but JVM unit tests and Robolectric do not exercise real Android ART/FUSE/f2fs directory descriptor semantics. Note an explicit device/emulator check on the real Android runtime per `AGENTS.md` DoD #3 (`androidTest` or device smoke check) so `fsyncDir` does not throw `IOException` on target devices.

---

## Owner Decision Check (`D1–D5`)

| Decision | Assessment |
| --- | --- |
| **D1** (Module placement in `:core:content`, defer `:core:update`) | **Keep** — aligns with ADR 0003 (`no empty modules`) and notes the `:core:update -> :core:content` reuse edge cleanly. |
| **D2** (`{"keys": []}` in `res/raw/trusted_package_keys.json` pre-ceremony) | **Keep with fix** — valid owner/security gate for pre-ceremony release builds, but remove the inaccurate claim that `{"keys": []}` is accepted by `tools/packages` `verify`. |
| **D3** (`active.json` atomic pointer vs DataStore) | **Downgrade to spec decision** — purely internal to `:core:content` inside ADR 0007's accepted boundary. |
| **D4** (Wire `:core:content` startup cleanup into `SeerahApplication` now) | **Drop / Revise (Blocker `B2`)** — fails its stated Tink R8 verification claim while introducing startup work inside `benchmark`. |
| **D5** (Fail closed on non-empty `reconstructed_areas` pre-disclosure) | **Keep with fix (Blocker `B1`)** — valid on-device enforcement of ADR 0009 Decision 11, implemented without adding `ReconstructedArea` to `:core:model`. |
| **New gaps?** | **None required** once `ReconstructedArea`, `allowLocalProvenance`, and `D4` are removed; `buildHealth` `api` vs `implementation`, `MapMetadata` placement, and `PmTilesInspector` simplification are uncontroversial technical fixes inside existing ADRs. |
