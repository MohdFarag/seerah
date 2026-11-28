<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Codebase Details Audit Report: `v1-android-package-and-map-spec.md`

**Target:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md)
**Audit Mode:** Strictly read-only codebase verification (file not written to disk; returned verbatim).

## Summary

Most codebase citations in [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) match the live repository accurately—including AGP `9.5.3`, Kotlin `2.4.21`, KSP `2.3.14`, SDK `37.2`/`37`/`26`, `JAVA_VERSION_17`, Spotless/ktlint/Licensee/DAGP/module-graph settings, `SeerahMap.kt` (`setPrefetchZoomDelta(0)` and `onTrimMemory -> MapView.onLowMemory()`), `Views.kt` Style C ramp constants, `backup_rules.xml`/`data_extraction_rules.xml`, and the `tools/packages/` ZIP/PMTiles/Ed25519 wire layouts.

However, direct cross-checking against the Gradle build configuration, schemas, and Python verifier surfaced **2 Blockers**, **8 Majors**, and **5 Nits** that will break compilation/`buildHealth`, allow schema drift, or misclassify failure modes if implemented as written.

---

## Findings

### Blockers

#### CD-B1. `:core:map` (`implementation(project(":core:model"))`) and `:core:content` will fail DAGP `buildHealth` on public ABI exposure

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L150-L175), [L209-L326](../v1-android-package-and-map-spec.md#L209-L326), [L544](../v1-android-package-and-map-spec.md#L544), [L568-L573](../v1-android-package-and-map-spec.md#L568-L573)
  - [android/build.gradle.kts](../../../android/build.gradle.kts#L47-L51)
  - [android/core/map/build.gradle.kts](../../../android/core/map/build.gradle.kts#L10-L18)
- **Discrepancy Description & Rationale:**
  - [android/build.gradle.kts](../../../android/build.gradle.kts#L47-L51) configures Dependency Analysis Gradle Plugin (`3.20.4`) with `onAny { severity("fail") }`, and `AndroidLibraryConventionPlugin` applies it to every library module.
  - Spec [L174](../v1-android-package-and-map-spec.md#L174) specifies adding `implementation(project(":core:model"))` to `:core:map`, yet `MapPackageBinding.kt` ([L568-L573](../v1-android-package-and-map-spec.md#L568-L573)) exposes `:core:model` types (`MapMetadata`, `InstalledPackageInfo`) in public function signatures (`public fun MapMetadata.toCameraLimits()`, `public fun resolveMapFiles(...)`, `public fun MapMetadata.toStyleSpec(...)`). Just as `:core:map` already declares `api(libs.androidx.compose.runtime)` and `api(libs.androidx.compose.ui)` for `SeerahMap`, `:core:map` **must** declare `api(project(":core:model"))` or `./gradlew buildHealth` will fail.
  - Likewise, `:core:content` exposes `:core:model` types (`PackageKind`, `PackageManifest`, `InstalledPackageInfo`, `MapMetadata`) in `PackageFailure.UnexpectedKind`, `PackageStorage`, and `PackageInstaller`, and exposes `StateFlow<InstalledPackageInfo?>` ([L544](../v1-android-package-and-map-spec.md#L544)). Therefore `:core:content/build.gradle.kts` must declare `api(project(":core:model"))` and `api(libs.kotlinx.coroutines.core)` (while keeping Tink/serialization types internal so `tink-android` and `kotlinx-serialization-json` remain `implementation`).

#### CD-B2. Contradicts the Code: `:app` does not have coroutines in `main` (`SeerahApplication.kt` fails to compile as specified)

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L34), [L182-L184](../v1-android-package-and-map-spec.md#L182-L184)
  - [android/app/build.gradle.kts](../../../android/app/build.gradle.kts#L44-L72)
  - [HiltConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/HiltConventionPlugin.kt#L13-L16)
- **Discrepancy Description & Rationale:**
  - Spec [L182](../v1-android-package-and-map-spec.md#L182) states: `app/build.gradle.kts: + implementation(project(":core:content")) (already has hilt + coroutines)`.
  - In [android/app/build.gradle.kts](../../../android/app/build.gradle.kts#L44-L72), `kotlinx-coroutines-core` is **only** declared in `testImplementation` (`L61`) and `benchmarkImplementation` (`L71`), and `kotlinx-coroutines-android` is not declared in `main` at all. `HiltConventionPlugin` only adds `hilt-android` and `hilt-compiler`.
  - Launching `cleanupOrphanedFiles()` on `Dispatchers.IO` from `SeerahApplication` ([L34](../v1-android-package-and-map-spec.md#L34), [L184](../v1-android-package-and-map-spec.md#L184)) requires adding `implementation(libs.kotlinx.coroutines.core)` to `android/app/build.gradle.kts` (and ensuring `PackageStorage` / `TrustedKeyRingLoader` do zero synchronous disk or resource reads inside constructors/`@Provides` so `SeerahApplication.enableStrictMode()`'s `detectAll()` does not flag main-thread I/O during `HiltAndroidApp` injection in `super.onCreate()`).

---

### Majors

#### CD-M1. `data/map.json` `sources.{base,relief}.max_zoom` range (`0..22`) diverges from `map.schema.json` (`6..12`)

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L451-L457), [L584](../v1-android-package-and-map-spec.md#L584)
  - [map.schema.json](../../../map-data/schema/map.schema.json#L85-L89), [L127-L131](../../../map-data/schema/map.schema.json#L127-L131)
- **Discrepancy Description & Rationale:**
  - Spec [L451](../v1-android-package-and-map-spec.md#L451) states the checks match `map.schema.json`, but [L456-L457](../v1-android-package-and-map-spec.md#L456-L457) check `max_zoom in 0..22` on both `sources.base` and `sources.relief`.
  - In [map.schema.json](../../../map-data/schema/map.schema.json#L85-L131), `sources.base.max_zoom` and `sources.relief.max_zoom` are 256-px **build zooms** constrained strictly to `6..12` (`minimum: 6, maximum: 12`), not MapLibre camera zooms (`0..22`).
  - Furthermore, Python `archive.verify_package` validates `data/map.json` against `map.schema.json`, so `valid-map-v1.zip` ([L584](../v1-android-package-and-map-spec.md#L584)) will fail Python `verify` if `sources.*.max_zoom` is outside `6..12`. Either enforce `6..12` in `MapMetadataParser` to match `map.schema.json`, or explicitly document why Android widens `max_zoom` (and still build `valid-map-v1.zip` with `6..12`).

#### CD-M2. `kotlinx.serialization` DTO traps break `manifest.schema.json` and `map.schema.json` contract parity

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L256), [L282](../v1-android-package-and-map-spec.md#L282), [L422](../v1-android-package-and-map-spec.md#L422), [L430-L432](../v1-android-package-and-map-spec.md#L430-L432), [L456-L460](../v1-android-package-and-map-spec.md#L456-L460)
  - [manifest.schema.json](../../../content/schema/manifest.schema.json#L47-L50), [L68-L80](../../../content/schema/manifest.schema.json#L68-L80)
  - [map.schema.json](../../../map-data/schema/map.schema.json#L59-L63), [L217-L220](../../../map-data/schema/map.schema.json#L217-L220), [L279-L283](../../../map-data/schema/map.schema.json#L279-L283)
  - [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L74-L84)
  - [test_archive_and_cli.py](../../../tools/packages/tests/test_archive_and_cli.py#L463)
- **Discrepancy Description & Rationale:**
  1. **`reconstructed_areas` DTO shape:** Typing `reconstructed_areas` in the DTO as `List<ReconstructedAreaDto>` (`id: String`) instead of `JsonArray` causes non-empty arrays with empty or future-schema objects (such as `[{}]` in [test_archive_and_cli.py](../../../tools/packages/tests/test_archive_and_cli.py#L463)) to fail deserialization as `InvalidMapMetadata` instead of returning `PackageFailure.UnsupportedReconstructedAreas(count)` per D5.
  2. **`sources.base.layers` uniqueness:** Decoding directly into `Set<String>` in the DTO silently deduplicates `["land", "land", "coast"]`, bypassing `map.schema.json`'s `"uniqueItems": true`. The DTO must decode to `List<String>` and verify `distinct().size == size`.
  3. **Explicit `null` on optional nullable properties (`explicitNulls = false` + `String? = null`):** `"tool_commit": null` / `"image": null` on `kind == CONTENT` and `"liability": null` on `MapCredit` deserialize as Kotlin `null`, whereas `manifest.schema.json` (`not: anyOf: [required: [tool_commit], required: [image]]`) and `map.schema.json` (`"type": "string"`) reject explicit `null` values. Similarly in `TrustedKeyRing` (`signing.py:226`), `"public_key_hex": null` is rejected in Python and must not decode as absent `null`.
  4. **`created` timestamp parsing:** Spec [L430](../v1-android-package-and-map-spec.md#L430) must require **both** the exact regex `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` and `Instant.parse(...)` (or `DateTimeFormatter.ISO_INSTANT` strict calendar check), because `Instant.parse` alone accepts fractional seconds (`.000Z`) and 5-digit years (`+10000-...`) that `manifest.schema.json` and `archive.py` reject.
  5. **`PmTilesInspector` `tile_schema == 1` (`JsonPrimitive`):** In `kotlinx.serialization.json`, `JsonPrimitive("1").intOrNull` returns `1` for JSON string `"1"`, so `PmTilesInspector` must verify `!primitive.isString` alongside `.intOrNull == 1` to match Python `meta.get("tile_schema") != 1`.

#### CD-M3. Missing `currentAppVersionCode` Hilt binding in `ContentModule.kt` and `minSdk = 26` API/Lint/warning trap

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L169), [L427](../v1-android-package-and-map-spec.md#L427), [L535](../v1-android-package-and-map-spec.md#L535)
  - [libs.versions.toml](../../../android/gradle/libs.versions.toml#L13)
  - [AndroidCommon.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt#L38), [L54](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt#L54)
- **Discrepancy Description & Rationale:**
  - Spec [L427](../v1-android-package-and-map-spec.md#L427) and [L535](../v1-android-package-and-map-spec.md#L535) require `PackageInstaller` / `PackageManifestParser` to check `minAppVersion <= currentAppVersionCode`, yet `ContentModule.kt` ([L169](../v1-android-package-and-map-spec.md#L169)) only lists `IODispatcher, TrustedKeyRing loader, Time/Space`—leaving `currentAppVersionCode` unbound (a Hilt KSP `MissingBinding` compile error unless provided).
  - If `ContentModule` resolves it via Android `PackageManager.getPackageInfo(...)`, `PackageInfo.longVersionCode` requires API 28 while `minSdk` is `26` (failing Android Lint `NewApi` under `warningsAsErrors = true`), and deprecated `versionCode` fails Kotlin `allWarningsAsErrors = true` unless wrapped with an `SDK_INT >= P` branch (`PackageInfoCompat` is not in `:core:content` unless `androidx-core-ktx` is declared) or injected from `:app`'s `BuildConfig.VERSION_CODE`.
  - Conversely, `Time` listed in `ContentModule.kt` ([L169](../v1-android-package-and-map-spec.md#L169)) has no caller anywhere in `v1-android-package-and-map-spec.md` (and [ADR 0008](../../adr/0008-update-hosting-and-index.md) explicitly notes device clocks cannot be trusted for package validity).

#### CD-M4. Missing Gradle root/catalog/convention changes (`android/build.gradle.kts`, `libs.versions.toml`, `AndroidCommon.kt`)

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L128-L140), [L150](../v1-android-package-and-map-spec.md#L150)
  - [android/build.gradle.kts](../../../android/build.gradle.kts#L2-L12)
  - [libs.versions.toml](../../../android/gradle/libs.versions.toml#L78-L94)
  - [AndroidCommon.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt#L50-L56)
- **Discrepancy Description & Rationale:**
  - `android/build.gradle.kts` declares `apply false` for every subproject plugin (`hilt`, `kotlin.compose`, `ksp`, etc.) so Gradle shares one parent plugin classloader with `build-logic`. The spec file list omits adding `alias(libs.plugins.kotlin.serialization) apply false` (and `kotlin.jvm`) to `android/build.gradle.kts`, which risks Gradle plugin classloader conflicts when `:core:content` applies `kotlin-serialization` alongside `build-logic`'s `kotlin-gradle-plugin`.
  - Spec [L130](../v1-android-package-and-map-spec.md#L130) omits adding `seerah-jvm-library = { id = "seerah.jvm.library" }` to `[plugins]` in `libs.versions.toml` (matching lines 90–94 of `libs.versions.toml`).
  - `AndroidCommon.kt`'s `configureKotlin()` hard-codes `KotlinAndroidProjectExtension`; `JvmLibraryConventionPlugin` cannot reuse it directly and must configure `KotlinJvmProjectExtension` (`jvmTarget = 17`, `allWarningsAsErrors = true`) and `JavaPluginExtension` (`sourceCompatibility`/`targetCompatibility = JAVA_VERSION_17`).

#### CD-M5. `TrustedKeysGuardTest` and `:core:content` Robolectric tests need explicit `androidx-test-core` or `testImplementation(libs.robolectric)` for DAGP

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L63), [L172](../v1-android-package-and-map-spec.md#L172), [L185-L186](../v1-android-package-and-map-spec.md#L185-L186), [L612](../v1-android-package-and-map-spec.md#L612)
  - [libs.versions.toml](../../../android/gradle/libs.versions.toml#L55-L66)
  - [android/app/build.gradle.kts](../../../android/app/build.gradle.kts#L58-L64)
  - [android/core/map/build.gradle.kts](../../../android/core/map/build.gradle.kts#L20-L23)
- **Discrepancy Description & Rationale:**
  - Both `:app` and `:core:map` declare `robolectric` as `testRuntimeOnly` (not `testImplementation`), and `androidx.test:core` (`ApplicationProvider`) is absent from `libs.versions.toml`.
  - Because DAGP `buildHealth` fails on undeclared transitive dependencies, `TrustedKeysGuardTest` (reading `:core:content`'s `res/raw/trusted_package_keys.json` via Android `Resources` in `:app`) and `:core:content` Robolectric tests (`StatFs`, `res/raw`) cannot import `ApplicationProvider` or `RuntimeEnvironment` without adding `androidx-test-core` to `libs.versions.toml` + `testImplementation` (or promoting `robolectric` to `testImplementation`). Note also that `:app`'s `SeerahApplication` is `@HiltAndroidApp`, so plain Robolectric tests in `:app` should specify `@Config(application = Application::class)` (`robolectric-annotations` is already in `:app`).

#### CD-M6. `StrictZipReader` `InflaterInputStream` error mapping and trailing-slice gap

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L411)
- **Discrepancy Description & Rationale:**
  - `InflaterInputStream` stops as soon as the deflate stream emits its end-of-block marker (`inflater.finished()`). If a hostile `ZIP_DEFLATED` entry pads unconsumed trailing bytes at the end of `[dataOffset, dataOffset + compressedSize)`, `InflaterInputStream` ignores them unless `StrictZipReader` checks `inflater.finished() && inflater.bytesRead == compressedSize`.
  - Additionally, when a truncated or corrupt deflate stream hits EOF on the extra `+1` byte read or fails decompression, `InflaterInputStream` throws `EOFException` or `ZipException` (both subclasses of `IOException`). Catching general `IOException` as `PackageFailure.IoFailure` would misclassify a hostile/corrupt `DEFLATED` entry instead of returning `PackageFailure.PayloadIntegrityError` (breaking [L329](../v1-android-package-and-map-spec.md#L329)'s failure separation).

#### CD-M7. `StyleJson.kt` unused `RELIEF_TILE_SIZE` under `allWarningsAsErrors`, KDoc update, and `MapFiles` parameter order

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L78), [L177](../v1-android-package-and-map-spec.md#L177), [L564-L569](../v1-android-package-and-map-spec.md#L564-L569)
  - [StyleJson.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt#L39), [L57-L87](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt#L57-L87)
  - [MapFiles.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/MapFiles.kt#L11)
- **Discrepancy Description & Rationale:**
  - [StyleJson.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt#L39) defines `private const val RELIEF_TILE_SIZE = 256`; replacing it in `reliefSource()` (`L85`) without removing the constant triggers an unused-constant compiler warning and fails under `allWarningsAsErrors = true`. Also note `reliefSource(relief: File, spec: ReliefSource)` takes `ReliefSource`, whereas `styleJson()` takes `MapStyleSpec` ([L177](../v1-android-package-and-map-spec.md#L177) vs [L566](../v1-android-package-and-map-spec.md#L566)), and `styleJson`'s KDoc `@throws IllegalArgumentException` (`L57-L58`) should document `tileSize <= 0`.
  - In [MapFiles.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/MapFiles.kt#L11), the constructor order is `MapFiles(val relief: File, val base: File)` (`relief` first, `base` second). `resolveMapFiles()` ([L569](../v1-android-package-and-map-spec.md#L569)) must pass named arguments (`MapFiles(relief = ..., base = ...)`) to prevent swapping files.

#### CD-M8. `trial-rejected-map-v1.zip` cannot be generated via `archive.build_package()`

- **Affected Scope:**
  - [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L583-L586)
  - [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L548-L561)
- **Discrepancy Description & Rationale:**
  - `archive.build_package()` ([archive.py](../../../tools/packages/src/seerah_packages/archive.py#L548-L561)) unconditionally self-verifies the output with `verify_package(..., expected_kind=kind)`, which refuses `"trial": true` on `kind == "map"`. The fixture script must build `trial-rejected-map-v1.zip` directly via `_make_zip_info` / `zipfile.ZipFile` + `signing.sign_bytes` (like `test_archive_and_cli.py`), not `build_package()`.

---

### Nits

#### CD-N1. Off-by-one phrasing on PMTiles gzip metadata cap (`<= 1_048_576 + 1`)

- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L471), [L633](../v1-android-package-and-map-spec.md#L633)
- **Discrepancy Description & Rationale:** Both lines write `<= 1_048_576 + 1` / `> 1_048_576 + 1` uncompressed bytes, which literally accepts `1_048_577` bytes instead of reading up to `1_048_576 + 1` bytes and rejecting `> 1_048_576` (`MAX_PMTILES_METADATA_BYTES`).

#### CD-N2. D2 claim that `{"keys": []}` is readable by `seerah_packages verify` & top-level keyring strictness

- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L32), [L347-L353](../v1-android-package-and-map-spec.md#L347-L353); [signing.py](../../../tools/packages/src/seerah_packages/signing.py#L280-L288)
- **Discrepancy Description & Rationale:**
  - `signing.py:285` explicitly rejects `{"keys": []}` (`keyset object must contain only non-empty 'keys'`); the shared file becomes readable by `seerah_packages verify --public-keys` only once populated with 1–2 keys after the key ceremony.
  - Spec §3.1 should also match `signing.py`'s top-level rules explicitly: reject empty array `[]` and reject extra fields alongside `"keys"` (`set(obj) != {"keys"}`) as `InvalidKeyRing`.

#### CD-N3. Unedited draft-thinking residue on `kind == CONTENT` provenance check

- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L432)
- **Discrepancy Description & Rationale:** Line 432 contains internal drafting dialogue (`"forbid tool_commit and image if present (InvalidManifest), or ignore? Wait: check manifest.schema.json line 73..."`) that should be cleaned into normative prose.

#### CD-N4. Startup recovery gap when `<active_dir>` or `<active_dir>/manifest.json` is missing/corrupt

- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L490), [L550-L555](../v1-android-package-and-map-spec.md#L550-L555)
- **Discrepancy Description & Rationale:** Because `active.json` (`{ schema: 1, version, generation, key_id, active_dir }`) does not store `PackageManifest`, `PackageStorage` must read `<active_dir>/manifest.json` to build `InstalledPackageInfo`. The Crash Recovery Matrix covers corrupt `active.json`, but should also specify validating `active.json` `schema == 1` + `active_dir` regex `^v\d+-g\d+$` and degrading safely to `null` (not throwing) if `<active_dir>` or its `manifest.json` is missing/unreadable.

#### CD-N5. Android-only strictness deltas vs `archive.py` and Phase 3 docs list

- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md#L384-L399), [L407](../v1-android-package-and-map-spec.md#L407), [L623](../v1-android-package-and-map-spec.md#L623); [docs/README.md](../../README.md#L3); [android/README.md](../../../android/README.md#L79)
- **Discrepancy Description & Rationale:**
  - `StrictZipReader` adds `totalEntries <= 16_384`, `cdSize <= 8_388_608`, CD `commentLen == 0`, and full LFH-vs-CD `flags`/`crc32` equality, all compatible with `archive.py` outputs (`build_package` writes `flag_bits = 0`, `comment = b""`, and seekable `ZipFile` matches LFH/CD CRC32), though stricter than `archive.py`'s `verify_package`. (Also clarify [L407](../v1-android-package-and-map-spec.md#L407) phrasing `"exact allowed set check on map.json / pmtiles below"` since additive archives like `relief-detail.pmtiles` are allowed per ADR 0009 decision 10.)
  - Phase 3's documentation list ([L623](../v1-android-package-and-map-spec.md#L623)) should also include [docs/README.md](../../README.md#L3) (to list this new spec) and update [android/README.md](../../../android/README.md#L79) ("Feature and JVM conventions are added...").
