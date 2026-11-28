<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Consolidated Review Report: v1 Android Package Verification, Storage, and `tile_schema: 1` Map Spec

**Target:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md)
**Spec checked against:** [ADR 0002](../../adr/0002-proposed-stack.md), [ADR 0003](../../adr/0003-v1-architecture-and-modules.md), [ADR 0004](../../adr/0004-build-and-quality-tooling.md), [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../../adr/0008-update-hosting-and-index.md), [ADR 0009](../../adr/0009-map-data-pipeline.md), [open-decisions.md](../../open-decisions.md), [engineering.md](../../engineering.md), [signing-runbook.md](../../signing-runbook.md), [v1-map-pipeline-spec.md](../v1-map-pipeline-spec.md), [map-trial-build.md](../map-trial-build.md), [signing.py](../../../tools/packages/src/seerah_packages/signing.py), [archive.py](../../../tools/packages/src/seerah_packages/archive.py), [cli.py](../../../tools/packages/src/seerah_packages/cli.py), [test_archive_and_cli.py](../../../tools/packages/tests/test_archive_and_cli.py), [manifest.schema.json](../../../content/schema/manifest.schema.json), [map.schema.json](../../../map-data/schema/map.schema.json), [map-data/README.md](../../../map-data/README.md), [check_no_trial_code.py](../../../tools/android/check_no_trial_code.py), [android/README.md](../../../android/README.md), [android/build.gradle.kts](../../../android/build.gradle.kts), [android/settings.gradle.kts](../../../android/settings.gradle.kts), [libs.versions.toml](../../../android/gradle/libs.versions.toml), [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt), [AndroidLibraryConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt), [HiltConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/HiltConventionPlugin.kt), [AndroidCommon.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt), [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt), [android/app/build.gradle.kts](../../../android/app/build.gradle.kts), [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt), [TrialActivity.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt), [Views.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/Views.kt), [android/core/map/build.gradle.kts](../../../android/core/map/build.gradle.kts), [StyleJson.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt), [MapStyleSpec.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/MapStyleSpec.kt), [MapFiles.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/MapFiles.kt), [SeerahMap.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/SeerahMap.kt), [StyleJsonTest.kt](../../../android/core/map/src/test/kotlin/app/seerah/map/StyleJsonTest.kt), [backup_rules.xml](../../../android/app/src/main/res/xml/backup_rules.xml), [data_extraction_rules.xml](../../../android/app/src/main/res/xml/data_extraction_rules.xml), [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md), [REUSE.toml](../../../REUSE.toml), and [.github/workflows/ci.yml](../../../.github/workflows/ci.yml)
**Inputs:**

- [zoom_out_report.md](zoom_out_report.md) (`Z-B1..Z-B2`, `Z-M1..Z-M6`, `Z-N1..Z-N3`)
- [comprehension_report.md](comprehension_report.md) (`C-1..C-12`)
- [critic_report.md](critic_report.md) (`D-B1..D-B6`, `D-M1..D-M10`, `D-N1..D-N5`)
- [codebase_details_report.md](codebase_details_report.md) (`CD-B1..CD-B2`, `CD-M1..CD-M8`, `CD-N1..CD-N5`)
- [implementation_readiness_report.md](implementation_readiness_report.md) (`R-B1..R-B4`, `R-M1..R-M6`, `R-N1..R-N2`)

## Reviewer Status

| Lens | Reviewer | Status | Raw Findings |
| --- | --- | --- | --- |
| Zoom-out architecture | `egm-zoom-out` | completed | `Z-B1..Z-B2`, `Z-M1..Z-M6`, `Z-N1..Z-N3` (11) |
| Cold-read comprehension | `egm-comprehension` | completed | `C-1..C-12` (12) |
| Hostile design and security | `egm-design` | completed | `D-B1..D-B6`, `D-M1..D-M10`, `D-N1..D-N5` (21) |
| Codebase details | `egm-codebase-details` | completed | `CD-B1..CD-B2`, `CD-M1..CD-M8`, `CD-N1..CD-N5` (15) |
| Implementation readiness | `egm-implementation-readiness` | completed | `R-B1..R-B4`, `R-M1..R-M6`, `R-N1..R-N2` (12) |

## Summary and Overall Verdict

**Verdict: Needs revision before owner sign-off (`D1–D5`) and Phases 1–3 implementation.** All five reviewers confirmed the core engineering foundations: deferring `:core:update` networking until ADR 0008 download work starts (`D1`), enforcing isolated single-key Ed25519 `NO_PREFIX` verify-before-parse with `tink-android:1.24.0` matching `tools/packages/`, building a custom `FileChannel` ZIP container validator, isolating `:core:map` inside `benchmark` until the Oppo A55 and Huawei MatePad 11.5 trial runs finish, and generating deterministic Python-to-Kotlin test fixtures.

However, deduplicating the **71 raw findings** surfaced **7 Blockers (`B1–B7`)**, **10 Majors (`M1–M10`)**, and **4 Nits (`N1–N4`)** that must be resolved in the spec before owner approval or coding:

1. **Open-decisions & ADR violations:** A speculative `ReconstructedArea(val id: String)` model violates [open-decisions.md](../../open-decisions.md) and breaks `D5`'s fail-closed check on unknown array schemas (`B1`). Meanwhile `:core:model` leaks `:core:content` manifest and filesystem internals against [ADR 0003](../../adr/0003-v1-architecture-and-modules.md), coupling `:core:map` (`M1`).
2. **Broken `D4` `:app` wiring:** Injecting `PackageStorageMaintenance.cleanupOrphanedFiles()` into `SeerahApplication` fails to exercise R8 on Tink (zero call-graph reachability from cleanup to Tink), lacks `kotlinx-coroutines-core` in `:app`'s `main` configuration, and runs startup work inside the `benchmark` process during `TrialActivity`'s cold open-time window (`B2`).
3. **Undefined `:core:content` API & crash/race bugs:** Missing public signatures, a `KeyRingState.Configured` constructor clash on pre-ceremony startup, a cold-start `StateFlow` / startup cleanup `Mutex` race, `StatFs` crashes on non-existent `<filesDir>/content/`, missing 4 KiB block-slack accounting, and post-`ATOMIC_MOVE` orphan leaks (`B3`).
4. **Build, DAGP, and release-guard breaks:** `implementation(project(":core:model"))` fails `buildHealth` (`severity("fail")`), `AndroidLibraryConventionPlugin` has no default `consumerProguardFiles`, `JvmLibraryConventionPlugin` cannot call `AndroidCommon.configureKotlin()`, root plugins are missing (`B4`), `TrustedKeysGuardTest` never runs on `release` variants, and `assembleRelease` never executes shrunk Tink code (`B5`).
5. **Binary parser & schema parity gaps:** `PmTilesInspector` leaves native MapLibre C++ directory/varint/header-overlap vectors unvalidated while over-coupling host build provenance (`B6`); `StrictZipReader` diverges from `archive.py` entry caps and leaves `InflaterInputStream` trailing-slice and error-mapping gaps (`B7`); and `kotlinx.serialization` DTO traps (`explicitNulls = false`, `Set<String>` layers, `created` fractional seconds, `max_zoom` `0..22` vs `6..12`) diverge from the schemas (`M3`).

## Reviewer Conflict Resolutions

Every tension across `egm-zoom-out` and the four detail/security/readiness reviewers was cross-checked directly against the repository files and resolved as follows:

| Topic | Conflicting Reviewer Positions | Cross-Check & Resolution |
| --- | --- | --- |
| **`D4` (`SeerahApplication` wiring vs. `benchmark` trial contamination & R8 Tink reachability)** | `egm-design` (`D-M5`, `D-M8`), `egm-codebase-details` (`CD-B2`, `CD-M5`), and `egm-implementation-readiness` (`R-B2`, `R-B3`, `R-B4`) patch `D4` in place: promote `kotlinx-coroutines-core` in `:app`, inject `@ApplicationScope`, make Hilt constructors zero-I/O for `StrictMode`, share `PackageStorage`'s `Mutex`, and add an R8-minified smoke test. `egm-zoom-out` (`Z-B2`) rejects `D4` outright because `cleanupOrphanedFiles()` has zero reachability to Tink under R8 and `SeerahApplication` runs inside `benchmark`. | **Adopt `Z-B2` (`B2`), retaining `:core:content` safety hardening (`B3`, `B5`).** Cross-checking [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt) (`isMinifyEnabled = true`, `benchmark` `initWith(release)`), [TrialActivity.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt) (open time measured from `Process.getStartElapsedRealtime()`), [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt) (`StrictMode` `detectAll()`), and [android/app/build.gradle.kts](../../../android/app/build.gradle.kts) (`kotlinx-coroutines-core` absent from `main`) confirms `Z-B2`: R8 Full Mode strips unreachable `PackageInstaller`/`Ed25519Verifier`/Tink classes anyway when only `cleanupOrphanedFiles()` is called, while wiring `:app -> :core:content` alters the `benchmark` process before Oppo A55 and MatePad 11.5 finish the trial. Drop `D4` from this spec until `:core:update` or onboarding lands, keep `:core:content` DI/constructors I/O-free and `Mutex`-guarded for future wiring, and move R8 verification of `consumer-rules.pro` to an isolated shrunk `androidTest` / library verification gate (`B5`). |
| **`:core:model` scope, `resolveMapFiles` decoupling, and DAGP `api(project(":core:model"))`** | `egm-comprehension` (`C-2`), `egm-codebase-details` (`CD-B1`), and `egm-implementation-readiness` (`R-B3`) keep `PackageManifest`/`InstalledPackageInfo` in `:core:model` and upgrade `:core:map` and `:core:content` to `api(project(":core:model"))`. `egm-design` (`D-M9`) fixes `resolveMapFiles` throwing `IllegalArgumentException` on missing disk files. `egm-zoom-out` (`Z-M1`) argues `PackageKind`/`PackageFileEntry`/`PackageManifest`/`InstalledPackageInfo` leak `:core:content` storage/packaging internals into `:core:model` against ADR 0003 and needlessly couple `:core:map`. | **Adopt `Z-M1` + `D-M9` + `CD-B1`/`R-B3` (`B4`, `M1`).** [ADR 0003](../../adr/0003-v1-architecture-and-modules.md) reserves `:core:model` for domain models (`Phase`, `Event`, `Place`, dates), while [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt) already allows `:core:.* -> :core:.*` and `:feature:.* -> :core:.*`. Move `PackageKind`, `PackageFileEntry`, `PackageManifest`, and `InstalledPackageInfo` into `:core:content`, drop the eager `manifest: PackageManifest` field from `InstalledPackageInfo`, keep shared map models (`GeoBounds`, `ReliefEncoding`, `MapMetadata`, etc.) in `:core:model`, and change `:core:map`'s `resolveMapFiles` to `resolveMapFiles(dataDir: File, metadata: MapMetadata): MapFiles` (pure path-containment mapping while `:core:content` checks missing/corrupt disk files via sealed `PackageResult` per ADR 0003). Both `:core:map` and `:core:content` still expose `MapMetadata` in public signatures, so both must declare `api(project(":core:model"))` for DAGP `buildHealth` ([android/build.gradle.kts](../../../android/build.gradle.kts) `onAny { severity("fail") }`). |
| **`PmTilesInspector` scope (native C++ mmap security vs. brittle provenance coupling)** | `egm-design` (`D-B5`, `D-N3`) expands `PmTilesInspector` to validate `root_directory`/`leaf_directory` LEB128 varints, header section non-overlap (`>= 127`), unsigned `uint8` reads, and gzip caps before MapLibre C++ mmaps the file. `egm-zoom-out` (`Z-M2`) warns that `PmTilesInspector`'s JSON metadata cross-checks (`name == "seerah-map"`, `fingerprint`, `tool_commit`, `data_commit`, `image`) duplicate `archive.py`'s signing-time validator and risk brittle breakage under ADR 0009 Decision 10 (`tile_schema` permissive compatibility). | **Adopt both: harden binary/native safety (`D-B5`, `D-N3`) and drop host build-provenance coupling (`Z-M2`) (`B6`).** Every signed archive already passed `pack-map` provenance checks in [archive.py](../../../tools/packages/src/seerah_packages/archive.py) before Ed25519 signing, so requiring `name == "seerah-map"`, `fingerprint` equality, `tool_commit`, `data_commit`, and `image` inside the Android client adds brittle coupling against [ADR 0009](../../adr/0009-map-data-pipeline.md) Decision 10. Conversely, validating 127-byte header bounds, non-overlapping sections (`>= 127`), `root_directory`/referenced `leaf_directory` LEB128 varints, bounded GZIP decompression (`<= 1_048_576` with `GZIPInputStream` exceptions mapped), `"trial"`/`"variant"`/`"mode"` refusal (Goal 9), `tile_schema == 1` (`!isString`), `vector_layers` (`land`, `coast`), and `encoding` parity directly prevents native MapLibre C++ crashes and accepts `trial-rejected-map-v1.zip`. |
| **`ReliefSource.tileSize`, `allowLocalProvenance`, and `estimateDownloadAndInstallRequiredBytes`** | `egm-comprehension` (`C-7`, `C-9`, `C-10`), `egm-design` (`D-M3`, `D-M10`), `egm-codebase-details` (`CD-M7`), and `egm-implementation-readiness` (`R-M2`, `R-M3`, `R-M6`) tighten these APIs: reconcile `tile_size == 256` with `:core:map`, clarify `allowLocalProvenance`, and specify `estimateDownloadAndInstallRequiredBytes`'s `Long` formula. `egm-zoom-out` (`Z-M4`) and `egm-design` (`D-M2`) recommend removing `allowLocalProvenance` and `Z-M4` recommends dropping `tileSize` and deferring `estimateDownloadAndInstallRequiredBytes`. | **Adopt `Z-M4` + `D-M2` (`M4`).** Cross-checking [map.schema.json](../../../map-data/schema/map.schema.json) (`"tile_size": { "const": 256 }`), [StyleJson.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt) (`RELIEF_TILE_SIZE = 256`), and [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md) Decision 10: (1) keep `RELIEF_TILE_SIZE = 256` internal in `StyleJson.kt`, omit `tileSize` from `ReliefSource` and `ReliefSourceMetadata`, and validate `tile_size == 256` in `MapMetadataParser`; (2) remove `allowLocalProvenance` from `:core:content` completely, enforce `^sha256:[0-9a-f]{64}$` unconditionally on Android `MAP` manifests, and use a synthetic `sha256:<64hex>` digest in Python test fixtures; (3) defer `estimateDownloadAndInstallRequiredBytes` to the future `:core:update` spec where download cache partitions and move-vs-copy semantics are designed. |
| **`D3` (`active.json` vs. DataStore) & `:core:content` API / `PackageFailure` granularity** | `egm-implementation-readiness` (`R-B1`) demands exact Kotlin signatures for `:core:content` (`PackageInstaller`, `PackageStorage`, `Ed25519Verifier`, `KeyRingState`, `VerifyOutcome`). `egm-zoom-out` (`Z-N1`, `Z-M6`) recommends downgrading `D3` to an internal technical choice inside ADR 0007, avoiding over-prescription of 12 internal `.kt` files/buffer constants/offset tables, and collapsing 15 `PackageFailure` subtypes so downstream UI doesn't branch exhaustively over 10 internal corrupt-package subtypes. | **Adopt `Z-N1` (`N1`) and reconcile `R-B1` with `Z-M6` (`B3`, `M7`).** Keep `active.json` under backup-excluded `<filesDir>/content/<kind>/` (per [backup_rules.xml](../../../android/app/src/main/res/xml/backup_rules.xml) and [data_extraction_rules.xml](../../../android/app/src/main/res/xml/data_extraction_rules.xml)) as a technical decision inside ADR 0007 rather than an owner gate (`D3`). Lock down `:core:content`'s **public** entry points and invariants (`R-B1`) while relaxing internal file splits and byte-offset tables (`Z-M6`), and collapse `PackageFailure` to UI-actionable sealed cases (`KeyRingNotConfigured`, `AppUpdateRequired`, `DowngradeOrSameVersion`, `UnsupportedReconstructedAreas`, `InsufficientStorage`, `InvalidPackage(cause: InvalidPackageCause, detail: String)`, `IoFailure`) so callers need only 7 exhaustive branches while tests and logs still distinguish exact causes. |
| **`TrustedKeyRingLoader` JSON shapes (`{"keys": [...]}` vs. 3 polymorphic shapes)** | `egm-design` (`D-M1`) and `egm-implementation-readiness` (`R-M2`) harden the spec's 3-shape parser (`{"keys": [...]}`, `[...]`, `{...}`) using strict `ignoreUnknownKeys = false` and note `signing.py` rejects `{"keys": []}` and sibling keys. `egm-zoom-out` (`Z-M3`) recommends simplifying Android to accept only `{"keys": [...]}`. | **Adopt `Z-M3` + `D-M1`/`R-M2` (`M2`).** Android only reads one embedded file (`res/raw/trusted_package_keys.json`) whose canonical schema is `{"keys": [...]}`; parsing only `{"keys": [...]}` with strict `Json { ignoreUnknownKeys = false; isLenient = false }` (rejecting sibling keys, `[...]`, `{...}`, unknown entry keys, and `"public_key_hex": null`) matches [signing.py](../../../tools/packages/src/seerah_packages/signing.py) for `1..2` keys while documenting `{"keys": []}` as an Android-only pre-ceremony placeholder. |

## Owner Decisions vs. Uncontroversial Spec Fixes

### Recommended Updates to Owner Decisions (`D1–D5`)

| ID | Topic | Recommended Disposition | Required Spec Change Before Owner Sign-Off |
| --- | --- | --- | --- |
| **D1** | Module placement of Tink verification & installer in `:core:content`; defer `:core:update` | **Keep (with ADR clarification)** | State explicitly that placing signature/ZIP/install verification in `:core:content` and having `:core:update` depend on `:core:content` amends [ADR 0003](../../adr/0003-v1-architecture-and-modules.md) (`L143–144`), and explain clearly why [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt) isolates `:core:domain -> :core:content` from `:core:update` networking (`C-5`). |
| **D2** | Trusted public keys pre-ceremony state `{"keys": []}` in `res/raw/trusted_package_keys.json` | **Keep (with schema & release-guard fix)** | Remove the inaccurate claim that `tools/packages` `verify` accepts `{"keys": []}` (`signing.py` requires `1..2` keys), lock Android parsing strictly to `{"keys": [...]}` (`Z-M3`, `M2`), note [signing-runbook.md](../../signing-runbook.md) must wrap `.pub.json` inside `{"keys": [...]}` (`C-6`), and pair unit tests with a release-APK key-guard script in `tools/android/` (`B5`). |
| **D3** | Active package pointer persistence (`active.json` vs. DataStore) | **Downgrade to technical spec decision** | Move `active.json` out of the Owner Decisions gate into a design-options table inside the spec body (`Z-N1`, `N1`), since [ADR 0003](../../adr/0003-v1-architecture-and-modules.md) limits DataStore to `:core:userdata` and [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md) (`L89`) delegates the atomic pointer to this spec (`AGENTS.md` L104). |
| **D4** | Wiring `:core:content` startup cleanup into `SeerahApplication` now | **Drop / Defer (`B2`)** | Remove `:app -> :core:content` and `SeerahApplication` startup cleanup from this spec (`Z-B2`, `B2`): it fails its R8 Tink reachability goal and runs inside the `benchmark` process during pending Oppo A55 and MatePad 11.5 trial measurements. Re-introduce `:app` startup cleanup alongside `:core:update` or onboarding, and test R8 consumer rules via an isolated shrunk verification check (`B5`). |
| **D5** | Fail closed on non-empty `reconstructed_areas` before disclosure UI exists | **Keep (with `ReconstructedArea` removed)** | Delete `ReconstructedArea` and `MapMetadata.reconstructedAreas` (`B1`) and validate `reconstructed_areas` as an untyped `JsonArray` (`Z-B1`, `C-3`, `D-B4`). Note explicitly in `D5` that failing closed across all build types also rejects reconstructed maps in `test` builds until disclosure UI lands (stricter than [ADR 0009](../../adr/0009-map-data-pipeline.md) Decision 11's `stable`-only gate; `C-12`). |

**New owner decisions needed:** None. Every remaining adjustment (`:core:model` boundary cleanup, `PmTilesInspector` provenance vs. native hardening, removing `ReliefSource.tileSize` / `allowLocalProvenance` / `estimateDownloadAndInstallRequiredBytes`, `PackageFailure` consolidation, Gradle/DAGP fixes, and schema alignment) sits cleanly inside accepted ADRs (`0003`, `0004`, `0007`, `0008`, `0009`) and [open-decisions.md](../../open-decisions.md).

### Uncontroversial Spec Fixes

All `7` Blockers (`B1–B7`), `10` Majors (`M1–M10`), and `4` Nits (`N1–N4`) should be applied directly to [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) before asking the owner to sign off on the updated `D1`, `D2`, and `D5`.

## Prioritized Findings

### Blockers

#### B1. Speculative `ReconstructedArea` model violates `open-decisions.md` and breaks `D5`

- **Raw findings:** `Z-B1`, `C-3`, `D-B4` (part 1), `CD-M2` (part 1)
- **Scope:** Overview (L18), Module layout (L146), Detailed Design §1 (L282, L292), §6.1 (L460)
- **Problem:** [open-decisions.md](../../open-decisions.md) (`L3, L10–11`) forbids hard-coding undecided formats, and [map.schema.json](../../../map-data/schema/map.schema.json) (`L217–220`) enforces `"maxItems": 0` on `reconstructed_areas` specifically to keep item fields open ([ADR 0009](../../adr/0009-map-data-pipeline.md) mentions footprints and citation text, never an `id`). Defining `data class ReconstructedArea(val id: String)` guesses an unapproved schema. Worse, decoding `reconstructed_areas` into `List<ReconstructedAreaDto(val id: String)>` causes non-empty future schemas (or `[{}]` in [test_archive_and_cli.py](../../../tools/packages/tests/test_archive_and_cli.py#L463)) to fail deserialization with `InvalidMapMetadata` instead of failing closed with `UnsupportedReconstructedAreas(count)` under `D5`.
- **Required fix:** Delete `ReconstructedArea` and `MapMetadata.reconstructedAreas` from `:core:model`. In `MapMetadataParser`, decode `reconstructed_areas` as a raw `JsonArray` (failing with invalid map metadata if missing or not a JSON array) and return `UnsupportedReconstructedAreas(array.size)` whenever `array.isNotEmpty()`.

#### B2. `D4` (`SeerahApplication` wiring) fails R8 Tink verification, fails compilation without `:app` coroutines, and contaminates `benchmark`

- **Raw findings:** `Z-B2`, `CD-B2`, `D-M5`, `R-B2` (startup/`StrictMode`/Robolectric parts), `C-9` (part 1)
- **Scope:** Owner Decision `D4` (L34), Overview (L21), Architecture (L98, L181–185), Phase 3 (L623), Known Risks (L631)
- **Problem:**
  1. *Broken R8 rationale:* `cleanupOrphanedFiles()` only walks `<filesDir>/content/` and has zero call-graph reachability to `PackageInstaller`, `Ed25519Verifier`, or Tink. Under R8 Full Mode (`isMinifyEnabled = true`), Tink is stripped unless forced via artificial `-keep` rules.
  2. *`benchmark` open-time interference:* [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt) (`L36–42`) builds `benchmark` with `initWith(release)`, inheriting `main`'s `SeerahApplication`. Injecting Hilt `:core:content` bindings and launching `Dispatchers.IO` work in `SeerahApplication.onCreate()` executes during `TrialActivity`'s cold open measurement ([TrialActivity.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt#L98-L100)) before the deferred Oppo A55 and Huawei MatePad 11.5 trial runs close.
  3. *Missing `:app` coroutines & `StrictMode` risk:* [android/app/build.gradle.kts](../../../android/app/build.gradle.kts) declares `kotlinx-coroutines-core` only in `testImplementation` and `benchmarkImplementation`, not `main`, so `SeerahApplication` fails to compile as written; additionally, [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt) enables `StrictMode.detectAll()` in debug and [engineering.md](../../engineering.md) forbids `GlobalScope`.
- **Required fix:** Drop `D4` (`:app -> :core:content` and `SeerahApplication` wiring) from this spec until `:core:update` or onboarding introduces a real package caller. Keep all `:core:content` `@Singleton` constructors and Hilt `@Provides` factories completely free of synchronous disk/resource I/O so future `@HiltAndroidApp` integration satisfies `StrictMode`.

#### B3. Missing `:core:content` public API contract, startup `Mutex` / `StateFlow` race, and `StatFs` / post-`ATOMIC_MOVE` crash gaps

- **Raw findings:** `R-B1`, `C-1`, `D-B1`, `D-B2`, `D-M5` (DI lazy-I/O part), `D-M7`, `R-B2` (`Mutex` / `StateFlow` / `NonCancellable` parts), `R-M1` (parts 1–3), `R-M3` (part 1), `CD-N4`, `C-12` (part 9)
- **Scope:** Architecture (L156–169), §3 (L355–357), §5.3 (L435–439), §6 (L449), §7 (L485–556)
- **Problem:**
  1. *Missing public API & keyring constructor crash:* Unlike §8 (`:core:map`), `:core:content` omits exact public method signatures for `PackageInstaller`, `PackageStorage`, `PackageStorageMaintenance`, `Ed25519Verifier`, and active-map reading; `IsolatedTinkEd25519Verifier(ring: KeyRingState.Configured)` (`L355`) conflicts with `L357` (`Unconfigured`/`Invalid` checks) and crashes Hilt graph creation pre-ceremony (`D2`); and a singular `StateFlow<InstalledPackageInfo?>` (`L544`) cannot track independent `CONTENT` and `MAP` packages or avoid emitting a misleading `null` before cold-start disk hydration finishes.
  2. *Unsynchronized `cleanupOrphanedFiles()` race:* Placing the `Mutex` inside `PackageInstaller` (`L529`) while `cleanupOrphanedFiles()` runs concurrently lets cleanup delete `staging/` mid-extraction (Steps 7–10) or delete `finalVersionDir` (`vNew`) between Step 11 (`ATOMIC_MOVE`) and Step 13 (`active.json` swap).
  3. *Disk-full, `StatFs`, and post-Step-11 leaks:* Calling `StatFs` before `<filesDir>/content/` exists throws `IllegalArgumentException` (`ENOENT`); flat `32 MiB` margin ignores 4 KiB EXT4/F2FS block slack on `CONTENT` packages with thousands of small JSON files; `ENOSPC` during extraction or pointer writes is unmapped (`InsufficientStorage` vs uncaught `IOException`); and failure/cancellation after Step 11 leaves `finalVersionDir` (`1+ GiB`) orphaned on disk because Step 16 only deletes `stagingDir`.
  4. *`nextGen` collision & cold-start self-check vs. Rule A:* `(active?.generation ?: highestExistingGen) + 1` collides with orphaned higher `v*-g*` dirs when `active.json` is valid; `active.json` lacks explicit `schema == 1`, `^v[1-9][0-9]*-g[1-9][0-9]*$` containment, and missing/corrupt `<active_dir>/manifest.json` recovery; and L449's undefined `"self-check"` risks re-verifying `manifest.sig` against `TrustedKeyRing` or re-hashing PMTiles at launch, breaking Rule A offline continuity (`L437`) and cold-start open times.
- **Required fix:**
  1. Add a **`:core:content` Public API Contract** subsection specifying `KeyRingState` (`Unconfigured`, `Configured`, `Invalid`), `Ed25519Verifier(keyRingProvider)` accepting `KeyRingState`, `PackageStorage` (`observeInstalled(kind)` with explicit `Loading`/`Ready(info)` state or `suspend currentInstalled(kind)`, plus `readActiveMapMetadata(...)`), `PackageInstaller.install(archiveFile, expectedKind?)`, `PackageStorageMaintenance`, and `AvailableSpaceProvider`.
  2. Move a single shared `Mutex` into `@Singleton PackageStorage` covering `install()`, `cleanupOrphanedFiles()`, `currentInstalled()`, and hydration; wrap Steps 11–14 in `withContext(NonCancellable)` and track `movedFinalDir` so any failure before Step 13 completes deletes `movedFinalDir` and `active.json.tmp` immediately; `fsync` created staging subdirectories before Step 11.
  3. Call `contentRootDir.mkdirs()` (or fall back to `filesDir`) off the main thread before `StatFs` (catching `IllegalArgumentException` into `IoFailure`), round each extracted file up to `4_096`-byte block slack (`((sizeBytes + 4095L) ushr 12) shl 12`), and map `ErrnoException(ENOSPC)` to `PackageFailure.InsufficientStorage`.
  4. Compute `nextGen = maxOf(active?.generation ?: 0L, highestExistingGen) + 1L`; define exact `active.json` schema (`schema: 1`, `version`, `generation`, `key_id`, `public_key_sha256`, `active_dir`), validate `active_dir` regex + canonical containment + `<active_dir>/manifest.json` + `manifest.sig` existence (and `data/map.json` + required files for `MAP`, degrading corrupt/dangling state to `null` via `active.json` deletion), and mandate that cold-start loading **never** re-verifies `manifest.sig` against `TrustedKeyRing` or re-hashes gigabyte PMTiles.

#### B4. Gradle, AGP 9 `JvmLibraryConventionPlugin`, and DAGP `buildHealth` failures

- **Raw findings:** `C-2`, `CD-B1`, `CD-M4`, `R-B3`, `Z-M1` (DAGP part), `D-N4` (DAGP `hilt` bundle part), `C-12` (part 6 catalog)
- **Scope:** Build & Module Files (L128–186), §8 (L568–573)
- **Problem:** Cross-checking [android/build.gradle.kts](../../../android/build.gradle.kts), [AndroidLibraryConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt), [AndroidCommon.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt), and [libs.versions.toml](../../../android/gradle/libs.versions.toml):
  1. `:core:map` and `:core:content` expose `:core:model` types in public signatures (and `:core:content` exposes `StateFlow`), yet L174 uses `implementation(project(":core:model"))` and L150 omits `api(...)`, failing DAGP `buildHealth` (`onAny { severity("fail") }`).
  2. `AndroidLibraryConventionPlugin` does not configure `consumerProguardFiles`; `:core:content/build.gradle.kts` must declare `defaultConfig { consumerProguardFiles("consumer-rules.pro") }` explicitly.
  3. `AndroidCommon.configureKotlin()` configures `KotlinAndroidProjectExtension`, which fails when applied to a pure-JVM module (`org.jetbrains.kotlin.jvm`).
  4. Root `android/build.gradle.kts` omits `alias(libs.plugins.kotlin.jvm) apply false` and `alias(libs.plugins.kotlin.serialization) apply false`; `libs.versions.toml` omits `seerah-jvm-library` and `kotlinx-serialization-core`; and `bundle("hilt")` omits `javax.inject:javax.inject`.
- **Required fix:**
  - Declare `api(project(":core:model"))` in both `:core:map` and `:core:content` (and `api(libs.kotlinx.coroutines.core)` in `:core:content`).
  - Explicitly add `consumerProguardFiles("consumer-rules.pro")` inside `:core:content/build.gradle.kts`.
  - Implement `JvmLibraryConventionPlugin` configuring `KotlinJvmProjectExtension` (`jvmTarget = JvmTarget.JVM_17`, `allWarningsAsErrors = true`), `JavaPluginExtension` (`VERSION_17`), `licensee`, `com.autonomousapps.dependency-analysis`, and `enforceMapLibreBoundary()`, and register `jvmLibrary` (`seerah.jvm.library`) in `build-logic/convention/build.gradle.kts` and `libs.versions.toml`.
  - Add `kotlin-jvm` and `kotlin-serialization` `apply false` declarations in `android/build.gradle.kts`, and verify `bundle("hilt")` (`javax.inject:javax.inject`) and `kotlinx-serialization-core` against `./gradlew buildHealth`.

#### B5. Release-variant blind spot in `TrustedKeysGuardTest` and unverified R8 Full Mode Tink rules

- **Raw findings:** `D-B3`, `D-M8`, `R-B4`, `C-10` (part 6), `CD-M5` (release/Robolectric guard part), `R-M4` (part 4)
- **Scope:** Owner Decision `D2` (L32), Layout (L152, L186), §3 (L339, L342), Test Matrix (L612–613), Phase 3 (L623), Known Risks (L631)
- **Problem:**
  1. [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt) (`L44–48`) enables host unit tests for `debug` and `benchmark` only — **not `release`**. Running `./gradlew check` only runs `debug` unit tests, completely missing any test keys placed in `src/release/res/raw/trusted_package_keys.json`. Checking only RFC 8032 vectors also misses ad-hoc dev keys generated with `seerah_packages keygen`, and asserting `keys.isEmpty()` would fail after the key ceremony.
  2. Spec L152 cites unverified Tink R8 rules while `./gradlew assembleRelease` only checks link-time class references and never executes `SignatureConfig2026.get()`, `Ed25519PublicKey.create`, shaded `protobuf-lite`, or `kotlinx.serialization` DTOs under R8 Full Mode (nor verifies `isShrinkResources = true` retention of `trusted_package_keys.json`).
- **Required fix:**
  - Move `TrustedKeysGuardTest` into `:core:content/src/test/` (decoupled from `:app`, loading `R.raw.trusted_package_keys` under Robolectric and rejecting RFC 8032 keys and `test*`/`rfc8032*`/`dev*`/`debug*` `key_id`s without asserting `keys.isEmpty()`), AND add a Python release-APK/source guard in `tools/android/` (invoked in `.github/workflows/android.yml` alongside [check_no_trial_code.py](../../../tools/android/check_no_trial_code.py)) validating `res/raw/trusted_package_keys.json` in `src/*/res/raw/` and `app-release.apk` (once `:app` links `:core:content`).
  - Inspect `tink-android:1.24.0`'s bundled `proguard.txt` and `kotlinx-serialization-core` rules in Phase 2, document the exact minimal `consumer-rules.pro` directives, and require an R8-shrunk runtime smoke test (`androidTest` with R8 enabled or verified release/device smoke run per [AGENTS.md](../../../AGENTS.md) DoD #3) executing `TrustedKeyRingLoader` + `IsolatedTinkEd25519Verifier` + `PackageManifestParser` before declaring R8 compatibility verified.

#### B6. `PmTilesInspector` leaves native MapLibre C++ directory/varint attacks unvalidated while over-coupling build provenance

- **Raw findings:** `D-B5`, `Z-M2`, `D-N3`, `CD-N1`, `C-10` (part 5), `CD-M2` (part 5), `R-M2` (part 8), `D-N4` (part 3)
- **Scope:** Overview (L19), Verified Facts (L88–89), §6.2 (L461–480), Known Risks (L633)
- **Problem:**
  1. *Native MapLibre C++ attack vectors (`D-B5`, `D-N3`):* Before `libmaplibre.so` mmaps `base.pmtiles` and `relief.pmtiles`, Spec §6.2 checks only metadata JSON bounds and leaves critical binary hazards unchecked: overlapping section offsets (`root_offset = 0`, `root_length = 0`, `leaf_directory_offset`/`tile_data_offset < 127` when non-empty), unbounded `root_directory` / `leaf_directory` gzip bombs, malformed LEB128 varints (`> 10` bytes or huge `num_entries` triggering native `std::vector::reserve` OOM/abort), signed Kotlin `Byte` (`-128..127`) bugs on `uint8` header fields (`min_zoom`/`max_zoom`), off-by-one `<= 1_048_576 + 1` cap phrasing (literally accepting `1_048_577` bytes), uncaught `GZIPInputStream` `ZipException`/`EOFException`, degenerate `relief` bounds (`min_lon_e7 >= max_lon_e7`), and `JsonPrimitive("1").intOrNull` string coercion (`!primitive.isString` omitted).
  2. *Brittle build-provenance coupling (`Z-M2`):* Requiring internal build-pipeline keys (`name == "seerah-map"`, `fingerprint` presence/equality, `tool_commit`, `data_commit`, `image`) inside an Android `tile_schema: 1` client duplicates `archive.py`'s signing-time checks and breaks [ADR 0009](../../adr/0009-map-data-pipeline.md) Decision 10's permissive forward-compatibility contract.
- **Required fix:**
  - Harden `PmTilesInspector` on binary & native-safety checks: enforce unsigned `Byte` masking (`and 0xFF`), `root_offset >= 127`, `1 <= root_length <= 1_048_576`, `leaf_directory_offset >= 127` / `tile_data_offset >= 127` when non-empty, `addressed_tiles_count > 0L`, `tile_entries_count > 0L`, `tile_contents_count > 0L`, `min_lon_e7 < max_lon_e7` / `min_lat_e7 < max_lat_e7` on `relief`, strict `> 1_048_576` metadata/directory decompression rejection (`MAX_PMTILES_METADATA_BYTES = 1_048_576`), `GZIPInputStream` exception mapping into `InvalidPackage`, and strict binary parsing of `root_directory` (and referenced `leaf_directory` slices) checking canonical `<= 10`-byte LEB128 varints, bounded `num_entries`, monotonic `tile_id`s, exact byte consumption, and in-bounds offsets/lengths.
  - Enforce `"trial"` / `"variant"` / `"mode"` key refusal (Goal 9), `tile_schema == 1` (`elem is JsonPrimitive && !elem.isString && elem.intOrNull == 1`), `vector_layers` (`land`, `coast`), and `encoding` parity (plus basic header + no-`trial` check on any additive `data/*.pmtiles`), while **dropping** `name == "seerah-map"`, `fingerprint`, `tool_commit`, `data_commit`, and `image` checks from Android's `PmTilesInspector`.

#### B7. `StrictZipReader` diverges from `archive.py` and leaves `InflaterInputStream` trailing-byte, flag, and error-mapping gaps

- **Raw findings:** `D-B6`, `D-M4`, `CD-M6`, `R-M2` (parts 1–3, 7), `C-10` (parts 1–2), `CD-N5` (part 1)
- **Scope:** §4 (L384–413)
- **Problem:**
  1. Spec L384 hedges *"capped at `65_535` (actually `<= 16_384` entries practical DoS cap; if `totalEntries == 0xFFFF`, fail as ZIP64)"*, contradicting [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L36) which permits up to `65_534` entries (`0xFFFE`) and a `4 MiB` `content` manifest (`~28,900` entries), and omits explicit `0xFFFF_FFFFL` ZIP64 sentinel checks on EOCD `cdSize`/`cdOffset`.
  2. `InflaterInputStream` stops at the DEFLATE `BFINAL=1` marker without checking `inflater.finished() && inflater.bytesRead == compressedSize`, allowing trailing unauthenticated bytes inside `[dataOffset, dataOffset + compressedSize)`; throws `ZipException`/`EOFException` (`IOException`) that could escape or map wrongly to `IoFailure`; fails to probe 0-byte `DEFLATED` entries with a 1-byte read (`manifest.schema.json` allows `size >= 0`); risks closing the shared `FileChannel` on stream `.close()`; and omits `coroutineContext.ensureActive()` inside the `256 KiB` copy loop.
  3. Omitted ZIP hardenings: `flagBits` allowlist (unsupported `0x20`, `0x40`, `0x2000` unchecked), CD `diskNumberStart == 0`, raw ASCII byte (`0x20..0x7E`) equality between CD and LFH (preventing `String(..., UTF_8)` `U+FFFD` replacement masking), path-segment check rejecting `"."`/`".."`/`""` explicitly, and mandatory unsigned `0xFFFF_FFFFL` CRC-32 validation on `manifest.json` and `manifest.sig` (currently `"if desired"`).
- **Required fix:** Align `MAX_ZIP_ENTRIES` between `archive.py` and `StrictZipReader` (e.g., `3..65_534` with `cdSize <= 8_388_608` and `0xFFFF`/`0xFFFF_FFFFL` ZIP64 sentinel rejection, or cap `archive.py` at `16_384` too), enforce `flagBits == 0` (or explicit safe allowlist), CD `diskNumberStart == 0`, raw ASCII CD==LFH name byte comparison, path-segment rejection, mandatory unsigned CRC-32 checks on all entries, `inflater.finished() && inflater.bytesRead == compressedSize` (including a 1-byte probe on `sizeBytes == 0`), non-closing slice streams, `ensureActive()`, and clean `ZipException`/`EOFException` mapping into `InvalidPackage`.

---

### Majors

#### M1. `:core:model` leaks `:core:content` packaging internals and couples `:core:map`

- **Raw findings:** `Z-M1` (module scope part), `D-M9`
- **Scope:** Overview (L18), Layout (L142–146), §1 (L209–240), §8 (`resolveMapFiles` L569)
- **Problem:** Placing `PackageKind`, `PackageFileEntry`, `PackageManifest`, and `InstalledPackageInfo` (with `sha256Hex`, `sourceCommit`, `toolCommit`, `image`, `rootDirPath`, `dataDirPath`, `retainedArchivePath`) in `:core:model` violates [ADR 0003](../../adr/0003-v1-architecture-and-modules.md) (`L141–144`), forces eager parsing of up to `4 MiB` `manifest.json` files to construct `InstalledPackageInfo`, and forces `:core:map`'s `resolveMapFiles(packageInfo: InstalledPackageInfo, metadata: MapMetadata)` to take `:core:content`'s storage record and throw `IllegalArgumentException` on runtime missing-file corruption (`D-M9`).
- **Required fix:** Move `PackageKind`, `PackageFileEntry`, `PackageManifest`, and `InstalledPackageInfo` into `:core:content` (`app.seerah.content`), dropping the required `manifest: PackageManifest` field from `InstalledPackageInfo`. Keep only shared map metadata (`GeoBounds`, `ReliefEncoding`, `BaseSourceMetadata`, `ReliefSourceMetadata`, `MapSourcesMetadata`, `MapCredit`, `MapMetadata`) in `:core:model`. Change `:core:map`'s signature to `resolveMapFiles(dataDir: File, metadata: MapMetadata): MapFiles` (pure path resolution + structural containment check), and return missing/corrupt file failures via `:core:content`'s `PackageResult` when loading the active map package.

#### M2. `D2` / `TrustedKeyRing` misstates `signing.py` compatibility and mishandles Tink key exceptions

- **Raw findings:** `Z-M3`, `C-6` (parts 1–2, 4), `D-M1`, `CD-N2`, `R-M2` (part 6)
- **Scope:** `D2` (L32), Layout (L158), §2 (L312), §3.1 (L346–357), Test Matrix (L596), Phase 3 (L623), Known Risks (L635)
- **Problem:** `D2` claims `{"keys": []}` matches `tools/packages` `signing.py`, yet [signing.py](../../../tools/packages/src/seerah_packages/signing.py#L284-L286) rejects `{"keys": []}` with `SigningError`. Implementing 3 polymorphic root shapes on Android is unnecessary when `trusted_package_keys.json` is fixed as `{"keys": [...]}`; reusing lenient `Json { ignoreUnknownKeys = true }` silently ignores unknown fields or `"public_key_hex": null` (violating `signing.py` `L196–198, L226, L285`); and Tink `Ed25519PublicKey.create` / `getPrimitive` throws uncaught `GeneralSecurityException`/`IllegalArgumentException` on malformed 32-byte key inputs.
- **Required fix:** Clarify in `D2` that `{"keys": []}` is an Android-only pre-ceremony placeholder (`Unconfigured`); parse ONLY `{"keys": [...]}` using a dedicated strict `Json { ignoreUnknownKeys = false; isLenient = false }` (rejecting sibling keys, `[...]`, `{...}`, unknown entry keys, and `"public_key_hex": null`); map Tink key-construction exceptions to `KeyRingState.Invalid` / `InvalidKeyRing`; and add [signing-runbook.md](../../signing-runbook.md) to Phase 3 docs to document wrapping `<key_id>.pub.json` inside `{"keys": [...]}`.

#### M3. `kotlinx.serialization` DTO and `map.json` schema parity traps

- **Raw findings:** `CD-M1`, `CD-M2` (parts 2–4), `D-B4` (part 2), `D-M3` (parts 1–2), `D-N2`, `C-4` (part 2 `explicitNulls`), `C-7` (parts 2–4), `C-10` (part 3), `Z-N2` (parts 2–3), `CD-N5` (part 2), `R-M2` (parts 4–5, 8 end), `R-N2` (part 1 `max_zoom`)
- **Scope:** §4 (L407), §5 (L422, L430, L432), §6.1 (L451–459)
- **Problem:**
  1. With `explicitNulls = false` (`L422`), explicit `"tool_commit": null` / `"image": null` on `CONTENT` and `"liability": null` on `MapCredit` silently decode to Kotlin `null`, bypassing [manifest.schema.json](../../../content/schema/manifest.schema.json#L73-L78) and [map.schema.json](../../../map-data/schema/map.schema.json#L279-L283).
  2. Modeling `BaseSourceMetadata` DTO `layers` as `Set<String>` silently deduplicates duplicate strings, bypassing `map.schema.json`'s `"uniqueItems": true`; `credits` also omits duplicate `credit.id` checks.
  3. `Instant.parse` / `DateTimeFormatter.ISO_INSTANT` accepts fractional seconds (`.123Z`) and 5-digit years disallowed by `manifest.schema.json` and `archive.py`.
  4. `MapMetadataParser` accepts `sources.{base,relief}.max_zoom in 0..22` while `map.schema.json` enforces `6..12`; `L458` writes `sources.relief_detail` (underscore) instead of `"relief-detail"` (hyphen, [v1-map-pipeline-spec.md](../v1-map-pipeline-spec.md#L254)); and `L407` ends with a contradictory `"(exact allowed set check on map.json / pmtiles below)"`.
- **Required fix:** Inspect raw `JsonObject` keys on `CONTENT` manifests (`"tool_commit" in obj || "image" in obj` -> `InvalidPackage`) and reject explicit `null` on `liability`; decode `layers` as `List<String>` checking `distinct().size == size` and check unique `credit.id`s; enforce both regex `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` and strict calendar parsing (`ResolverStyle.STRICT`) on `created`; align `sources.{base,relief}.max_zoom` to `6..12`; fix `sources.relief-detail` spelling; and remove L407's contradictory `"exact allowed set"` parenthetical while documenting Android's additive `data/*` tolerance vs. `archive.py`.

#### M4. Premature public APIs and security-bypass toggle (`ReliefSource.tileSize`, `allowLocalProvenance`, `estimateDownloadAndInstallRequiredBytes`)

- **Raw findings:** `Z-M4`, `D-M2` (part 2), `D-M3` (part 3), `D-M10`, `C-7` (parts 1, 5), `C-9` (part 3), `C-10` (part 4), `CD-M7` (parts 1–2), `R-M3` (part 2), `R-M6` (part 3)
- **Scope:** Overview (L20), Verified Facts (L78), Layout (L176–177), §1 (L265), §5.2 (L431), §7 (L513, L525), §8 (L563–566)
- **Problem:**
  1. [map.schema.json](../../../map-data/schema/map.schema.json#L119-L122) hard-codes `"tile_size": { "const": 256 }` and `MapMetadataParser` (`L457`) rejects `tile_size != 256`, yet the spec plumbs `tileSize` through `ReliefSourceMetadata`, `ReliefSource`, and `StyleJson.kt` (also causing an unused-constant warning on `RELIEF_TILE_SIZE` under `allWarningsAsErrors = true` and a `spec.tileSize` vs. `spec.relief.tileSize` typo).
  2. `allowLocalProvenance` adds an unnecessary security-bypass knob inside production `:core:content` when Android never installs `local:*` ZIPs (`ADR 0007` Decision 10) and test fixtures can pass synthetic `sha256:<64hex>` strings.
  3. `estimateDownloadAndInstallRequiredBytes(...)` belongs to `:core:update` (Non-Goal #1) and lacks download-cache partition context (`cacheDir` vs. `filesDir`).
- **Required fix:** Validate `tile_size == 256` in `MapMetadataParser` and leave `ReliefSource`, `ReliefSourceMetadata`, and `StyleJson.kt`'s `RELIEF_TILE_SIZE = 256` unchanged; remove `allowLocalProvenance` from `:core:content` and enforce `^sha256:[0-9a-f]{64}$` unconditionally; and defer `estimateDownloadAndInstallRequiredBytes` to the `:core:update` spec.

#### M5. Security and concurrency gaps in `PackageStorage` / `PackageInstaller`: Rule A key binding, TOCTOU `File` reads, and eager `oldActiveDir` deletion

- **Raw findings:** `D-M2` (part 1), `D-M6`, `D-N5`, `R-M2` (part 7 TOCTOU part)
- **Scope:** §5.3 (L436–441), §7 (L513, L533–545)
- **Problem:**
  1. Rule A (`L436–441`) compares only `installed.keyId` against `trustedKeyRing`, so accidental `key_id` reuse during rotation preserves a compromised package's high `version` floor.
  2. Reading `archiveFile` by path across Steps 3 and 8 (`MAP`) or copying `archiveFile` to `stagingDir/package.zip` after verification (`CONTENT`) opens a TOCTOU window where an external writer can mutate `archiveFile` mid-install.
  3. Deleting `oldActiveDir` eagerly at Step 15 immediately after updating `StateFlow` can cause transient `ENOENT` in MapLibre background tile threads before UI `setStyle` switches to `vNew`.
- **Required fix:** Persist `public_key_sha256` in `active.json` and require both `key_id` and key digest to match in Rule A; copy `CONTENT` `archiveFile` (bounded `<= MAX_RELEASE_FILE_BYTES`) into `stagingDir/package.zip` before verification and hold a single pinned `FileChannel` across Steps 3–8 for `MAP`; and document the Step 15 UI style-switch handoff (treating old-dir delete failure gracefully until next cleanup).

#### M6. Missing `currentAppVersionCode` binding (`minSdk = 26` / API 33 lint-deprecation trap), phantom `Time` DI binding, and `Log` stub crash

- **Raw findings:** `CD-M3`, `C-9` (part 2), `Z-M5` (part 1), `R-M1` (part 4), `R-M3` (part 3)
- **Scope:** Layout (L169), §5.2 (L427), §7 (L535, L555)
- **Problem:** `ContentModule.kt` omits a Hilt binding for `currentAppVersionCode` (causing `MissingBinding`), while calling `PackageManager.getPackageInfo(String, Int)` fails `allWarningsAsErrors = true` at `compileSdk = 37.2` and `longVersionCode` fails Android Lint `NewApi` at `minSdk = 26`; `ContentModule.kt` lists a phantom `Time` binding unused anywhere (and device clocks are untrusted per [ADR 0008](../../adr/0008-update-hosting-and-index.md)); and calling `android.util.Log` directly in `PackageStorage` crashes plain-JVM unit tests (`isReturnDefaultValues = false`).
- **Required fix:** Provide `currentAppVersionCode` via an `AppVersionCodeProvider` (using `SDK_INT` guards / `PackageInfoCompat` off the main thread, or `:app` `BuildConfig.VERSION_CODE` once wired), delete `Time` from `ContentModule.kt`, and inject a plain Kotlin `PackageStorageLogger` interface bound to `android.util.Log` in production and a no-op/recording fake in JVM tests.

#### M7. Altitude drops and over-granular `PackageFailure` hierarchy

- **Raw findings:** `Z-M6`, `C-12` (part 5 ASCII diagram)
- **Scope:** Verified Facts (L84–89), Layout (L156–169), §1–2 (L206–326), §4 (L377–415), §7 (L531–546)
- **Problem:** Prescribing 12 exact internal `.kt` filenames, buffer constants, and byte-offset tables already defined in [archive.py](../../../tools/packages/src/seerah_packages/archive.py) and the PMTiles v3 spec locks internal code structure instead of architecture; using ASCII diagrams conflicts with [engineering.md](../../engineering.md#L78); and defining 15 top-level `PackageFailure` subtypes forces every UI/`update` `when` expression to branch exhaustively over ~10 internal parsing/crypto errors that present identically to users.
- **Required fix:** Keep the spec at architectural altitude (public contracts, invariants, and wire references, with Mermaid diagrams), treat internal filenames as illustrative, and collapse `PackageFailure` to UI-actionable sealed cases (`KeyRingNotConfigured`, `AppUpdateRequired`, `DowngradeOrSameVersion`, `UnsupportedReconstructedAreas`, `InsufficientStorage`, `InvalidPackage(cause: InvalidPackageCause, detail: String)`, `IoFailure`).

#### M8. Unresolved draft prose, undefined `"Option A"` label, `D1` rationale, and misleading past-tense `"verified live"` claims

- **Raw findings:** `C-4`, `C-5`, `C-8`, `Z-M5` (parts 2–5), `D-N1`, `CD-N3`, `R-N1`
- **Scope:** Overview (L14, L20), `D1` (L27, L31), Non-Goals (L45), Verified Facts (L79), Layout (L140), §3 (L339, L342), §5.2 (L432), §7 (L513, L543–544), Known Risks (L631–632)
- **Problem:** The spec calls its design `"Option A"` six times without defining it (colliding with ADR 0009 `"option (a)"`); leaves conversational self-prompts in normative sections (`L384`, `L432` `"or ignore? Wait..."`, `L543` `"(Check withContext(NonCancellable)...)"`); contradicts its own `:core:model` decision at `L140` (`(+ kotlin-serialization plugin if serialized...)`); frames `D1` inconsistently against ADR 0003; and claims Phase 2/D4 checks are `"verified live"` in a draft spec whose modules do not exist yet.
- **Required fix:** Remove `"Option A"` throughout; resolve all draft self-prompts and `L140` contradiction into normative statements; explain `D1`'s narrow amendment to ADR 0003 and `:core:domain` isolation clearly; and rewrite `"verified live"` / `"Already checked"` phrases as future Phase 2/3 verification exit gates.

#### M9. Golden fixture generator (`trial-rejected-map-v1.zip`, CI drift check) and JVM vs. Robolectric test dependency split

- **Raw findings:** `CD-M5`, `CD-M8`, `R-M4`, `R-M5`
- **Scope:** Verified Facts (L63), Layout (L171–172, L179–180, L185–186), Test Matrix (L581–614)
- **Problem:**
  1. Spec L583 hedges the fixture generator path; neither `pack_map_staging` nor `archive.build_package` can build `trial-rejected-map-v1.zip` because both invoke `verify_package`, which rejects `"trial": true`; and running `verify` alone in CI does not detect drift between `tools/packages/` and checked-in `.zip` fixtures.
  2. [StyleJson.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt) uses `org.json` (Android stub on plain JVM, requiring Robolectric); both `:app` and `:core:map` declare `robolectric` as `testRuntimeOnly` and `libs.versions.toml` lacks `androidx-test-core`, which fails DAGP `buildHealth` if test code imports Robolectric or `ApplicationProvider` directly.
- **Required fix:** Pin the generator path (`tools/packages/scripts/generate_android_test_fixtures.py`) and deterministic inputs, build `trial-rejected-map-v1.zip` via `_make_zip_info` / `ZipFile` + `sign_bytes`, and add a pytest byte-equality drift test in `tools/packages/tests/`; split `:core:map` JVM tests (`MapPackageBindingTest`) from Robolectric tests (`MapPackageStyleJsonTest` with `@RunWith(AndroidJUnit4::class)`), keep `:core:content` core/installer tests pure-JVM (`java.util.Base64`, `PackageStorageLogger`, `AvailableSpaceProvider`) with one Robolectric `ContentAndroidBindingsTest`, and declare `testImplementation(libs.robolectric)` / `androidx-test-core` explicitly.

#### M10. `styleCValueRamp(inkArgb)` bitwise alpha mask bug and `MapFiles` / `MapPackageBinding` contract fixes

- **Raw findings:** `R-M6` (parts 1–2), `CD-M7` (part 3), `C-12` (part 10)
- **Scope:** §8 (`resolveMapFiles`, `styleCValueRamp`, L569–573)
- **Problem:** In [Views.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/Views.kt#L47), `MapColours.ink` is opaque (`0xFF967648`); combining `(alpha shl 24) or inkArgb` without masking `inkArgb and 0x00FFFFFF` pins every ramp stop's alpha to `0xFF`. In [MapFiles.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/MapFiles.kt#L11), `relief` comes before `base`, risking argument swapping in positional calls. Also canonical containment must use `toPath().startsWith(...)` (or `File.separator`) to prevent sibling-prefix bypasses.
- **Required fix:** Require `val rgb = inkArgb and 0x00FFFFFF` in `styleCValueRamp`, construct `MapFiles(relief = ..., base = ...)` with named arguments, and enforce `toPath().startsWith(...)` canonical containment in `resolveMapFiles`.

---

### Nits

#### N1. `D3` (`active.json`) is an internal implementation choice, not an owner gate

- **Raw findings:** `Z-N1`
- **Scope:** Owner Decisions `D3` (L33)
- **Summary:** Downgrade `D3` from an Owner Decision to a technical decision inside ADR 0007's accepted boundary ([AGENTS.md](../../../AGENTS.md) L104).

#### N2. Documentation deliverables, `THIRD_PARTY_NOTICES.md` scope, and Phase 1/2 `settings.gradle.kts` clarity

- **Raw findings:** `Z-N2` (part 1), `C-6` (part 3 docs), `CD-N5` (part 2), `R-N2`, `C-12` (part 6 phase alignment)
- **Scope:** Architecture (L129–130), Phase 3 docs list (L623)
- **Summary:** Clarify that app libraries (`tink-android`, `kotlinx-serialization-json`) are checked by Licensee and rendered in the in-app notices screen rather than duplicated in [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md#L3-L4); add [docs/signing-runbook.md](../../signing-runbook.md), [docs/README.md](../../README.md), and [docs/open-decisions.md](../../open-decisions.md) to Phase 3 docs; clarify `:core:content` enters [android/settings.gradle.kts](../../../android/settings.gradle.kts) in Phase 2 (not Phase 1) per ADR 0003's no-empty-modules rule; and document synthetic `Apache-2.0` test fixtures in `android/core/content/README.md` / [REUSE.toml](../../../REUSE.toml).

#### N3. Unintroduced acronyms, unlinked references, typos, and subset clarifications

- **Raw findings:** `C-11`, `C-12` (parts 1–4, 8)
- **Scope:** Throughout spec
- **Summary:** Expand `AGP`, `KSP`, `DAGP`, `EOCD`, `LFH`, `CD`, `MVT`, `e7`, `RFC 8032`, `NO_PREFIX` on first use; link [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt), `content.schema.json`, [map-trial-build.md](../map-trial-build.md), [signing-runbook.md](../../signing-runbook.md), [map-data/README.md](../../../map-data/README.md), [Views.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/Views.kt), and [StyleJsonTest.kt](../../../android/core/map/src/test/kotlin/app/seerah/map/StyleJsonTest.kt); fix `"Goal 9"` anchor (`#goals`); clarify `L18` vs `L144–146` class count (`6` vs `8`); clarify `L88` PMTiles enum lists are Seerah's accepted subset; fix `L439` typo (`v1-g1` vs `v5-g1`); and note [check_no_trial_code.py](../../../tools/android/check_no_trial_code.py) already runs independently in CI.

#### N4. `fsyncDir` Android runtime verification and `:core:model` `init` invariant alignment

- **Raw findings:** `Z-N3`, `D-N4` (part 2)
- **Scope:** §1 (L296), §7 (L542–543)
- **Summary:** Note an explicit device/emulator smoke check for `FileChannel.open(dirPath, READ).force(true)` on real Android ART/FUSE/f2fs filesystems ([AGENTS.md](../../../AGENTS.md) DoD #3), and enumerate the exact `:core:model` `init { require(...) }` invariants vs `:core:content` pre-validation so invalid input never throws unhandled exceptions.

---

## Traceability Matrix

Every finding across all five Goldfish review reports is accounted for below (`71/71` raw findings):

| Raw ID | Summary | Consolidated Target |
| --- | --- | --- |
| `Z-B1` | Speculative `ReconstructedArea` violates `open-decisions.md` and breaks `D5` | `B1` |
| `Z-B2` | `D4` fails R8 Tink reachability and contaminates `benchmark` open-time runs | `B2` |
| `Z-M1` | `:core:model` leaks `:core:content` internals, couples `:core:map`, fails DAGP `api` check | `B4`, `M1` |
| `Z-M2` | `PmTilesInspector` duplicates host provenance validation against ADR 0009 D10 | `B6` |
| `Z-M3` | `D2` misstates `signing.py` compatibility (`{"keys": []}`) and over-builds keyring shapes | `M2` |
| `Z-M4` | Premature `ReliefSource.tileSize`, `allowLocalProvenance`, `estimateDownloadAndInstallRequiredBytes` | `M4` |
| `Z-M5` | Unresolved draft hedges, phantom `Time` DI binding, `:core:model` serialization hedge, `StateFlow` | `M6`, `M8` |
| `Z-M6` | Spec altitude drops (internal files/tables) and 15 over-granular `PackageFailure` subtypes | `M7` |
| `Z-N1` | `D3` (`active.json`) is an internal implementation choice, not an owner gate | `N1` |
| `Z-N2` | Phase 3 doc list gaps (`THIRD_PARTY_NOTICES`, `android/README`, `open-decisions`), `relief_detail`, `explicitNulls` | `M3`, `N2` |
| `Z-N3` | `fsyncDir` assumption needs device/emulator verification across Android filesystems | `N4` |
| `C-1` | Missing `:core:content` public API, self-check vs Rule A contradiction, cleanup `Mutex` race | `B3` |
| `C-2` | `:core:map` `implementation(project(":core:model"))` fails DAGP `buildHealth` | `B4` |
| `C-3` | `ReconstructedArea(val id: String)` assumes open schema and breaks `D5` | `B1` |
| `C-4` | Undefined `"Option A"` label and raw draft self-prompts (`L432`, `L543`, `explicitNulls`) | `M3`, `M8` |
| `C-5` | Unclear `D1` framing against ADR 0003 and opaque `:core:domain` isolation rationale | `M8` |
| `C-6` | `D2` misstates `signing.py` `{"keys": []}`, clashes with `signing-runbook.md`, `Configured` clash | `M2`, `N2` |
| `C-7` | `map.json` `max_zoom` (`0..22` vs `6..12`), `tileSize`, `relief_detail`, dangling allowed-set note | `M3`, `M4` |
| `C-8` | Misleading past-tense `"verified live"` claims in an unapproved draft spec | `M8` |
| `C-9` | Unexplained startup `StrictMode`/coroutine rules, unused `Time` binding, missing space formula | `B2`, `M4`, `M6` |
| `C-10` | `StrictZipReader` cap/CRC-32, `created`, `allowLocalProvenance`, PMTiles gzip cap, `TrustedKeysGuardTest` | `B5`, `B6`, `B7`, `M3`, `M4` |
| `C-11` | Unintroduced acronyms, project jargon, and unlinked repository references | `N3` |
| `C-12` | Class-count mismatch, `check_no_trial_code`, `D5` test channel, ASCII diagrams, `v1-g1` typo, `styleCValueRamp` | `M7`, `M10`, `N2`, `N3` |
| `D-B1` | Unsynchronized `cleanupOrphanedFiles()` races `PackageInstaller` and can delete active/staged versions | `B3` |
| `D-B2` | `StatFs(nonExistent)` crash, missing 4 KiB block slack, leaked `finalVersionDir` post-Step 11, `ENOSPC` | `B3` |
| `D-B3` | `TrustedKeysGuardTest` checks only `debug` by default and misses `src/release/` key leaks | `B5` |
| `D-B4` | `ReconstructedArea(id)` breaks `D5`; `explicitNulls = false` hides forbidden `null` fields | `B1`, `M3` |
| `D-B5` | `PmTilesInspector` leaves `root_directory`, `leaf_directory`, varints, and overlaps unvalidated before C++ mmap | `B6` |
| `D-B6` | `StrictZipReader` (`16_384` vs `archive.py` `4 MiB`), `InflaterInputStream` trailing junk & uncaught errors | `B7` |
| `D-M1` | `TrustedKeyRing` `{"keys": []}`/strict-field mismatch with `signing.py`; uncaught Tink key errors | `M2` |
| `D-M2` | Rule A lacks public-key material binding; `allowLocalProvenance` adds release policy-bypass surface | `M4`, `M5` |
| `D-M3` | `Set<String>` swallows duplicate `layers`; `JsonPrimitive` lacks `.isString`; `tile_size` contradiction | `M3`, `M4` |
| `D-M4` | `StrictZipReader` omits `flagBits` allowlist, CD `diskNumberStart == 0`, raw byte compare, mandatory CRC-32 | `B7` |
| `D-M5` | `SeerahApplication` Hilt injection risks `StrictMode` disk-read violations and pollutes `benchmark` | `B2`, `B3` |
| `D-M6` | TOCTOU risk retaining `CONTENT` `package.zip` and reading `MAP` input files twice by path | `M5` |
| `D-M7` | `nextGen` collides with orphan `v*-g*` dirs; `active.json` cold-start validation gaps | `B3` |
| `D-M8` | Phase 3 exit gate omits runtime verification of Tink and `kotlinx.serialization` in R8-shrunk APK | `B5` |
| `D-M9` | `resolveMapFiles` mixes programming assertions with runtime disk failures (`IllegalArgumentException`) | `M1` |
| `D-M10` | `estimateDownloadAndInstallRequiredBytes` formula unspecified | `M4` |
| `D-N1` | Unresolved drafting question in Spec line 432 | `M8` |
| `D-N2` | `Instant.parse()` accepts fractional seconds and 5-digit years disallowed by `manifest.schema.json` | `M3` |
| `D-N3` | Signed `Byte` trap on PMTiles `uint8` header fields and degenerate bounds/`addressed_tiles_count` | `B6` |
| `D-N4` | DAGP `hilt` bundle `javax.inject`, `:core:model` `init` alignment, additive `data/*.pmtiles` note | `B4`, `B6`, `N4` |
| `D-N5` | Step 15 eager deletion of `oldActiveDir` during live `StateFlow` map reload | `M5` |
| `CD-B1` | `:core:map` (`implementation`) and `:core:content` fail DAGP `buildHealth` on public ABI exposure | `B4`, `M1` |
| `CD-B2` | `:app` has no `kotlinx-coroutines-core` in `main` (`SeerahApplication.kt` fails to compile) | `B2` |
| `CD-M1` | `data/map.json` `sources.{base,relief}.max_zoom` (`0..22`) diverges from `map.schema.json` (`6..12`) | `M3` |
| `CD-M2` | `kotlinx.serialization` DTO traps (`reconstructed_areas`, `Set`, `explicitNulls`, `created`, `isString`) | `B1`, `B6`, `M3` |
| `CD-M3` | Missing `currentAppVersionCode` Hilt binding, `minSdk = 26` API/lint/deprecation trap, unused `Time` | `M6` |
| `CD-M4` | Missing root `build.gradle.kts`, `libs.versions.toml`, and `JvmLibraryConventionPlugin` Kotlin setup | `B4` |
| `CD-M5` | `TrustedKeysGuardTest` and `:core:content` Robolectric tests need explicit deps for DAGP | `B5`, `M9` |
| `CD-M6` | `StrictZipReader` `InflaterInputStream` error mapping and trailing-slice gap | `B7` |
| `CD-M7` | `StyleJson.kt` unused `RELIEF_TILE_SIZE` compiler error, parameter typo, and `MapFiles` arg order | `M4`, `M10` |
| `CD-M8` | `trial-rejected-map-v1.zip` cannot be generated via `archive.build_package()` | `M9` |
| `CD-N1` | Off-by-one phrasing on PMTiles gzip metadata cap (`<= 1_048_576 + 1`) | `B6` |
| `CD-N2` | `D2` claim that `{"keys": []}` is readable by `seerah_packages verify` & strict keyring JSON | `M2` |
| `CD-N3` | Unedited draft-thinking residue on `kind == CONTENT` provenance check | `M8` |
| `CD-N4` | Startup recovery gap when `<active_dir>` or `<active_dir>/manifest.json` is missing/corrupt | `B3` |
| `CD-N5` | Android-only strictness deltas vs `archive.py` and Phase 3 docs (`docs/README`, `android/README`) | `B7`, `M3`, `N2` |
| `R-B1` | Missing exact `:core:content` Kotlin signatures and contradictory `KeyRingState` / `StateFlow` shapes | `B3` |
| `R-B2` | Cold-start `StateFlow` race, startup `Mutex` vs `cleanupOrphanedFiles()`, `@ApplicationScope`, `StrictMode` | `B2`, `B3` |
| `R-B3` | Gradle, AGP 9 `JvmLibraryConventionPlugin`, `consumerProguardFiles`, root plugins, and DAGP breaks | `B4` |
| `R-B4` | Missing concrete `consumer-rules.pro` Tink rules and zero runtime coverage of shrunk Tink code | `B5` |
| `R-M1` | Unspecified `active.json` schema, recovery, cold-start self-check vs Rule A, plain-JVM `Log` crash | `B3`, `M6` |
| `R-M2` | Wire/parser edge cases (`StrictZipReader`, `CONTENT` `tool_commit`, `created`, strict `Json`, gzip cap, TOCTOU) | `B6`, `B7`, `M2`, `M3`, `M5` |
| `R-M3` | `StatFs` non-existent dir crash, `estimateDownloadAndInstallRequiredBytes`, `currentAppVersionCode` | `B3`, `M4`, `M6` |
| `R-M4` | Golden fixture script/params, `pack_map_staging` vs `trial`, CI drift check, `TrustedKeysGuardTest` | `B5`, `M9` |
| `R-M5` | Plain-JVM vs Robolectric test split, missing test deps, `SeerahApplication` Robolectric teardown | `M9` |
| `R-M6` | `styleCValueRamp(inkArgb)` alpha mask bug, `resolveMapFiles` containment, `ReliefSource` `init` | `M4`, `M10` |
| `R-N1` | Conversational draft notes left in spec prose (`L384`, `L432`, `L543`) | `M8` |
| `R-N2` | `THIRD_PARTY_NOTICES.md` library policy vs Licensee and `docs/README.md` update | `M3`, `N2` |
