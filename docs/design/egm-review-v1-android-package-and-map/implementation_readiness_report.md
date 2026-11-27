<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Implementation Readiness Audit: v1 Android Package & Map Spec

**Target:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md)
**Cross-checked against:** [v1-map-pipeline-spec.md](../v1-map-pipeline-spec.md), [engineering.md](../../engineering.md), [open-decisions.md](../../open-decisions.md), [ADR 0003](../../adr/0003-v1-architecture-and-modules.md), [ADR 0004](../../adr/0004-build-and-quality-tooling.md), [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../../adr/0008-update-hosting-and-index.md), [ADR 0009](../../adr/0009-map-data-pipeline.md), [libs.versions.toml](../../../android/gradle/libs.versions.toml), [android/build.gradle.kts](../../../android/build.gradle.kts), [build-logic](../../../android/build-logic/convention/build.gradle.kts), [app/build.gradle.kts](../../../android/app/build.gradle.kts), [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt), [core/map](../../../android/core/map/build.gradle.kts), [tools/packages](../../../tools/packages/README.md), [manifest.schema.json](../../../content/schema/manifest.schema.json), [map.schema.json](../../../map-data/schema/map.schema.json), [ci.yml](../../../.github/workflows/ci.yml), [android.yml](../../../.github/workflows/android.yml), [.pre-commit-config.yaml](../../../.pre-commit-config.yaml), and [REUSE.toml](../../../REUSE.toml).

**Verdict:** **Not ready for immediate Phases 1–3 coding without spec revisions.** The core architecture (option A module layout, isolated single-key Tink `NO_PREFIX` verification, `FileChannel` strict ZIP validation, atomic `v<version>-g<gen>/` + `active.json` activation, and `:core:map` `tile_schema: 1` bridge) is sound and aligns with [v1-map-pipeline-spec.md](../v1-map-pipeline-spec.md). However, the audit identified **4 Blockers**, **6 Majors**, and **2 Nits** that would otherwise force engineers to invent `:core:content` APIs, fail DAGP (`buildHealth`) and `StrictMode` checks, or introduce startup race conditions and R8 verification blind spots.

---

## Summary of Findings

| ID | Severity | Area | Short Summary |
| --- | --- | --- | --- |
| **R-B1** | Blocker | `:core:content` Kotlin APIs | Public/internal signatures, `KeyRingState` / `VerifyOutcome`, `PackageStorage` `StateFlow` shape, and `PackageInstaller` contracts are omitted or contradictory |
| **R-B2** | Blocker | Concurrency, `StateFlow`, `StrictMode` | Cold-start `StateFlow` initialization, startup `Mutex` sharing with `cleanupOrphanedFiles()`, `@ApplicationScope` lifecycle, and off-main-thread `raw` resource loading are unspecified |
| **R-B3** | Blocker | Gradle, DAGP `buildHealth`, AGP 9 | `:core:map` and `:core:content` need `api(project(":core:model"))`; `consumerProguardFiles` is not default; `JvmLibraryConventionPlugin` cannot reuse `AndroidCommon.configureKotlin()`; root plugins and `bundle("hilt")` need updates |
| **R-B4** | Blocker | R8 Full Mode & `consumer-rules.pro` | Concrete `tink-android:1.24.0` / `kotlinx.serialization` keep rules are missing, and `assembleRelease` alone never executes R8-shrunk verification |
| **R-M1** | Major | `active.json`, recovery, logging | Exact `active.json` schema, missing/corrupt active dir recovery, cold-start self-check vs key retirement, `v*-g*` scan regex, and JVM `android.util.Log` stub crash |
| **R-M2** | Major | Wire & parser edge cases | Contradictory ZIP entry cap, optional CRC-32 wording, `CONTENT` `tool_commit`/`image` draft question, `created` fractional-seconds leak, strict vs lenient `Json` split, `InflaterInputStream` / TOCTOU / gzip bomb off-by-one |
| **R-M3** | Major | `StatFs`, space estimator, `versionCode` | `StatFs` throws on non-existent `<filesDir>/content/`, `estimateDownloadAndInstallRequiredBytes` lacks signature/formula, `currentAppVersionCode` lacks provider/API-33 deprecation guard |
| **R-M4** | Major | Fixtures, CI drift, `TrustedKeysGuardTest` | Generator script path and exact fixture params unspecified, `pack_map_staging` rejects `"trial": true`, CI `packages` job checks `verify` but not generator drift, `TrustedKeysGuardTest` mechanics unspecified |
| **R-M5** | Major | Plain JVM vs Robolectric test split | `StyleJson.kt` (`org.json`) and `PackageStorage` (`Log` / `Context`) test boundaries unspecified; missing test deps; `:app` Robolectric tests trigger `SeerahApplication` startup coroutine |
| **R-M6** | Major | `:core:map` `MapPackageBinding` | `styleCValueRamp(inkArgb)` needs `0x00FFFFFF` alpha mask for `MapColours.ink`; `resolveMapFiles` needs canonical separator containment and `PackageKind.MAP` check; `ReliefSource` `init` validation |
| **R-N1** | Nit | Spec hygiene | Three unresolved conversational self-notes left in spec prose (L384, L432, L543) |
| **R-N2** | Nit | Docs & notices alignment | `docs/README.md`, `THIRD_PARTY_NOTICES.md` library policy vs Licensee, `map.sources.*.max_zoom` (`0..22` vs `6..12`), and `REUSE.toml` note |

---

## Blockers

### R-B1 — Missing Exact Kotlin Signatures and Contradictory Keyring Types in `:core:content`

- **Severity:** Blocker
- **Affected Scope:** Phase 2 & Phase 3; `android/core/content/`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L156–169, L346–363, L449, L485–556.
- **Finding & Guidance:**
  The spec gives full Kotlin signatures for `:core:model` (L206–293), `PackageResult` / `PackageFailure` (L305–326), and `:core:map` (`MapPackageBinding.kt`, L568–573), yet omits almost every public and internal Kotlin signature in `:core:content`:
  1. **`KeyRingState` & `IsolatedTinkEd25519Verifier` contradiction:** L355 specifies constructor `IsolatedTinkEd25519Verifier(ring: KeyRingState.Configured)`, whereas L357 requires `verify(...)` to *"Return `InvalidKeyRing` / `KeyRingNotConfigured` immediately if keyring is not `Configured`"*. Under D2+D4, `res/raw/trusted_package_keys.json` is `{"keys": []}` (`KeyRingState.Unconfigured`) on startup, and `SeerahApplication` triggers the Hilt graph. If any Hilt-injected object constructs `IsolatedTinkEd25519Verifier` with `KeyRingState.Configured`, Hilt graph creation crashes on startup. `KeyRingState` and `VerifyOutcome` are also never defined as Kotlin types.
  2. **`PackageStorage` `StateFlow` shape:** L544 refers to *"Update `StateFlow<InstalledPackageInfo?>` emitted by `PackageStorage`"*, but `:core:content` tracks **two** independent package kinds (`CONTENT` and `MAP`). The spec does not state whether `PackageStorage` exposes `fun installedFlow(kind: PackageKind): StateFlow<InstalledPackageInfo?>`, `val installedContent` / `val installedMap`, or `StateFlow<Map<PackageKind, InstalledPackageInfo>>`.
  3. **`PackageInstaller`, `PackageStorageMaintenance`, `AvailableSpaceProvider`, and active map loading:** None of these have declared method names, parameter nullability, return types, or `suspend` modifiers. L449 also mentions *"loading an already-installed active map folder with self-check"* without specifying any method on `PackageStorage` or `MapMetadataParser` (or what `readActiveMapMetadata(...)` verifies and returns).
- **Required Spec Fix:** Add an explicit **`:core:content` Public & Internal API Contract** subsection before implementation starts, locking down:

```kotlin
package app.seerah.content

sealed interface KeyRingState {
    data object Unconfigured : KeyRingState
    data class Configured(val keys: List<TrustedKeyEntry>) : KeyRingState
    data class Invalid(val detail: String) : KeyRingState
}

data class TrustedKeyEntry(val keyId: String, val publicKeyBytes: ByteArray) // custom equals/hashCode/toString hiding or comparing contentEquals

sealed interface VerifyOutcome {
    data class Verified(val verifiedKeyId: String) : VerifyOutcome
    data class Rejected(val failure: PackageFailure) : VerifyOutcome
}

interface Ed25519Verifier {
    fun verify(rawSignature: ByteArray, rawManifestBytes: ByteArray): VerifyOutcome
}

interface PackageStorageMaintenance {
    suspend fun cleanupOrphanedFiles()
}

fun interface AvailableSpaceProvider {
    fun availableBytes(directory: File): Long
}

interface PackageStorage {
    fun observeInstalled(kind: PackageKind): StateFlow<InstalledPackageInfo?>
    suspend fun currentInstalled(kind: PackageKind): InstalledPackageInfo?
    suspend fun readMapMetadata(packageInfo: InstalledPackageInfo): PackageResult<MapMetadata>
}

interface PackageInstaller {
    suspend fun install(archiveFile: File, expectedKind: PackageKind? = null): PackageResult<InstalledPackageInfo>
}
```

Ensure `Ed25519Verifier` accepts `KeyRingState` (or a `suspend () -> KeyRingState` / `TrustedKeyRingProvider` loaded lazily off the main thread) so `Unconfigured` / `Invalid` keyrings never throw during Hilt graph creation.

---

### R-B2 — Cold-Start `StateFlow` Race, Unsynchronized Startup Cleanup vs Install `Mutex`, & `StrictMode` Violations

- **Severity:** Blocker
- **Affected Scope:** Phase 3; `PackageStorage`, `PackageInstaller`, `ContentModule`, `SeerahApplication.kt`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L34, L169, L184, L527–556; [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt) L10–31.
- **Finding & Guidance:**
  1. **Main-thread I/O under `StrictMode`:** [SeerahApplication.kt](../../../android/app/src/main/kotlin/app/seerah/SeerahApplication.kt) L12–30 enables `StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog()` and `VmPolicy.Builder().detectAll().penaltyLog()` in debug builds (which includes Robolectric `testDebugUnitTest`). In Hilt, `@Inject lateinit var maintenance: PackageStorageMaintenance` on `SeerahApplication` constructs the singleton dependency graph **synchronously on the main thread inside `super.onCreate()`**. If `PackageStorage`'s constructor/`init` reads `active.json` synchronously to seed `MutableStateFlow`, or if `ContentModule` reads `context.resources.openRawResource(R.raw.trusted_package_keys)` inside a `@Provides @Singleton` factory, Hilt executes disk/resource I/O on the main thread at startup.
  2. **Cold-start `StateFlow` race:** Conversely, if `PackageStorage` initializes `MutableStateFlow(null)` and hydrates asynchronously in `cleanupOrphanedFiles()`, callers observing `StateFlow<InstalledPackageInfo?>` immediately on app start observe a false `null` ("not installed") until disk hydration finishes. Either expose a cold-start loading state (`StateFlow<InstalledPackageState>` with `Loading` / `Ready(info)`), or make `currentInstalled(kind)` a `suspend` function that awaits `ensureInitialized()` under the storage `Mutex` while `observeInstalled(kind)` updates once hydrated.
  3. **Startup cleanup vs `install()` `Mutex` race:** Spec L529 places the `Mutex` *"inside `PackageInstaller`"*, while D4 launches `cleanupOrphanedFiles()` asynchronously at startup on `Dispatchers.IO`. Because `cleanupOrphanedFiles()` deletes everything inside `<filesDir>/content/staging/` AND deletes any `v*` directory in `<kind>/` not referenced by `active.json` (L552–553), running `cleanupOrphanedFiles()` concurrently with `install()` without sharing the **exact same `Mutex`** can delete an in-flight `staging/` folder (steps 7–10) or delete `vNew` between step 11 (`ATOMIC_MOVE` to `finalVersionDir`) and step 13 (`active.json` swap)!
  4. **Application `CoroutineScope` & Robolectric teardown:** [engineering.md](../../engineering.md) L34 prohibits `GlobalScope`. The spec does not define how `SeerahApplication` obtains an `@ApplicationScope CoroutineScope` (`CoroutineScope(SupervisorJob() + ioDispatcher)`) or how uncaught exceptions/cancellation inside startup `cleanupOrphanedFiles()` are handled (important because [RightToLeftTest.kt](../../../android/app/src/test/kotlin/app/seerah/RightToLeftTest.kt) instantiates `@HiltAndroidApp SeerahApplication` in Robolectric).
- **Required Spec Fix:**
  - Move the single process-wide `Mutex` into `PackageStorage` (guarding `cleanupOrphanedFiles()`, `install()`, `currentInstalled()`, and initial disk hydration).
  - Require `TrustedKeyRingLoader` and `PackageStorage` to perform **zero disk/resource I/O during Hilt object construction**; load `R.raw.trusted_package_keys` lazily inside `withContext(ioDispatcher)` on first verification (cached atomically/under lock) and hydrate `PackageStorage` on `Dispatchers.IO`.
  - Specify Hilt qualifiers (`@IoDispatcher`, `@ApplicationScope`) in `ContentModule` (or `:core:content` DI), wrapping `SeerahApplication`'s startup `cleanupOrphanedFiles()` call in `try/catch` (logging `IoFailure` rather than crashing or leaking uncaught background errors in Robolectric).
  - Lock down L543's `"(Check withContext(NonCancellable)...)"`: steps 11–14 (`ATOMIC_MOVE` to `finalVersionDir`, `fsyncDir`, `active.json.tmp` write + `force(true)` + `ATOMIC_MOVE`, `fsyncDir`, `StateFlow` update) **must** execute inside `withContext(NonCancellable)`, with step 15 (`oldActiveDir.deleteRecursively()`) best-effort after step 14 (or inside `NonCancellable` after step 14).

---

### R-B3 — Gradle, AGP 9, `JvmLibraryConventionPlugin`, and DAGP (`buildHealth`) Failures

- **Severity:** Blocker
- **Affected Scope:** Phases 1–3; `android/build-logic/`, `android/build.gradle.kts`, `android/gradle/libs.versions.toml`, `android/core/map/build.gradle.kts`, `android/core/content/build.gradle.kts`, `android/app/build.gradle.kts`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L130–187.
- **Finding & Guidance:**
  Cross-checking the spec against live Gradle configuration in [android/build.gradle.kts](../../../android/build.gradle.kts) (L47–51: `onAny { severity("fail") }`), [AndroidCommon.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt), [AndroidLibraryConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt), and [libs.versions.toml](../../../android/gradle/libs.versions.toml) surfaces five concrete build breaks:
  1. **`implementation(project(":core:model"))` in `:core:map` (L174) and `:core:content` will fail `buildHealth`:** `MapPackageBinding.kt` (L568–571) exposes `MapMetadata` and `InstalledPackageInfo` in public function signatures; `:core:content` exposes `PackageKind`, `InstalledPackageInfo`, `PackageManifest`, and `MapMetadata` in `PackageFailure`, `PackageStorage`, and `PackageInstaller`. Under DAGP `3.20.4` with `onAny { severity("fail") }`, both `:core:map/build.gradle.kts` and `:core:content/build.gradle.kts` **must** declare `api(project(":core:model"))` (just as `:core:map` already uses `api(...)` for Compose types).
  2. **`consumerProguardFiles("consumer-rules.pro")` is NOT a convention default (L151):** Live [AndroidLibraryConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt) and [AndroidCommon.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt) do **not** configure `consumerProguardFiles`. `:core:content/build.gradle.kts` must explicitly configure `android { defaultConfig { consumerProguardFiles("consumer-rules.pro") } }`.
  3. **`JvmLibraryConventionPlugin` cannot reuse `AndroidCommon.configureKotlin()`:** Live [AndroidCommon.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt) L50–56 configures `KotlinAndroidProjectExtension`, which does not exist when applying `org.jetbrains.kotlin.jvm`. `JvmLibraryConventionPlugin` must configure `KotlinJvmProjectExtension` (`jvmTarget = JvmTarget.JVM_17`, `allWarningsAsErrors = true`) AND `JavaPluginExtension` (`sourceCompatibility = JavaVersion.VERSION_17`, `targetCompatibility = JavaVersion.VERSION_17`), plus `licensee`, `com.autonomousapps.dependency-analysis`, and `enforceMapLibreBoundary()`.
  4. **Root `android/build.gradle.kts` plugin classpath:** `build-logic` declares `kotlin-gradlePlugin` as `compileOnly` ([build-logic/convention/build.gradle.kts](../../../android/build-logic/convention/build.gradle.kts) L12), relying on root [android/build.gradle.kts](../../../android/build.gradle.kts) L2–12 to load plugins via `alias(...) apply false`. Root `android/build.gradle.kts` must add `alias(libs.plugins.kotlin.jvm) apply false` and `alias(libs.plugins.kotlin.serialization) apply false` (alongside `[plugins]` entries in `libs.versions.toml` and `seerah-jvm-library`), and `build-logic/convention/build.gradle.kts` must register `jvmLibrary` (`id = "seerah.jvm.library"`).
  5. **`hilt` bundle & `kotlinx-serialization-core` under DAGP:**
     - `:app/build.gradle.kts` currently declares `testImplementation(libs.kotlinx.coroutines.core)` (L61). Injecting coroutine launch logic in `SeerahApplication.kt` requires promoting it to `implementation(libs.kotlinx.coroutines.core)` (and keeping `PackageStorageMaintenance` free of `:core:model` types so `:app` only needs `implementation(project(":core:content"))`).
     - `:core:content` is the first module in the repository to use `@Inject`, `@Singleton`, `@Qualifier`, `@Module`, `@Provides`, `@Binds`, and `@InstallIn`. Live [android/build.gradle.kts](../../../android/build.gradle.kts) L37–45's `bundle("hilt")` includes `dagger` and `hilt-core` but **not** `javax.inject:javax.inject`. Also `@Serializable` uses `kotlinx-serialization-core` directly. Explicitly verify both with `./gradlew buildHealth` in Phase 2/3 (adding `javax.inject:javax.inject` to `bundle("hilt")` or declaring `kotlinx-serialization-core` in `libs.versions.toml` if DAGP flags direct use).

---

### R-B4 — Unverified R8 Full Mode Rules (`consumer-rules.pro`) & Zero Runtime Coverage of Shrunk Tink Code

- **Severity:** Blocker
- **Affected Scope:** Phase 3; `android/core/content/consumer-rules.pro`, `:app:assembleRelease`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L152, L613, L623, L631; [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt) L31–35.
- **Finding & Guidance:**
  - Spec L152 claims `consumer-rules.pro` contains *"ErrorProne compile-only `-dontwarn` + verified Tink R8 keep rules"*, without listing any concrete keep/`-dontwarn` rules or confirming what rules `tink-android:1.24.0` already ships inside its AAR (`proguard.txt`).
  - [AndroidApplicationConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt) L31–35 enables AGP 9 R8 Full Mode (`isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt`) for `release` (and `benchmark` via `initWith`).
  - Crucially, `./gradlew check` tests unshrunk debug/unit-test bytecode, and `./gradlew assembleRelease` only checks that R8 links without missing-class errors — **neither command executes `IsolatedTinkEd25519Verifier` (`SignatureConfig2026.get()`, `Ed25519PublicKey.create`, shaded `protobuf-lite`, `KeysetHandle`) or `kotlinx.serialization` DTOs under R8 Full Mode**. If R8 Full Mode strips reflection-accessed shaded protobuf classes or `SignatureConfig2026` registrations, signature verification will only fail at runtime in `app-release.apk`.
  - Also verify with `isShrinkResources = true` in `:app` that `res/raw/trusted_package_keys.json` in `:core:content` is retained in `app-release.apk` (since it is referenced via `app.seerah.content.R.raw.trusted_package_keys` inside `:core:content`, R8 resource shrinking keeps it only if the resource reference is reachable from `SeerahApplication` / `ContentModule`).
- **Required Spec Fix:**
  - Specify the concrete Phase 2/3 R8 verification steps: (1) inspect `tink-android:1.24.0`'s bundled `proguard.txt` and `kotlinx-serialization-core`'s bundled rules when resolving the AAR; (2) list exact verified `-dontwarn` / `-keep` rules in `android/core/content/consumer-rules.pro` (never blanket `-keep class com.google.crypto.tink.** { *; }` without justification); (3) add a mandatory Phase 3 exit check executing `Ed25519Verifier` + `PackageInstaller` + `TrustedKeyRingLoader` in an R8-minified APK (`androidTest` on `release` or a verified device/emulator smoke run per `AGENTS.md` DoD #3, plus inspecting `app-release.apk` `res/raw/trusted_package_keys.json`).

---

## Majors

### R-M1 — Unspecified `active.json` Schema, Recovery Edge Cases, & Plain-JVM `Log` Stub Crash

- **Severity:** Major
- **Affected Scope:** Phase 3; `PackageStorage`, `PackageStorageMaintenance`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L436–439, L449, L490–513, L532, L550–556.
- **Finding & Guidance:**
  1. **`active.json` schema & validation:** L490 only sketches `{ schema: 1, version, generation, key_id, active_dir }`. Lock down exact snake_case field names, types (`schema: Int = 1`, `version: Int`, `generation: Long`, `key_id: String`, `active_dir: String`), strict decoding (`schema != 1` rejected), strict validation of `active_dir` (`^v[0-9]+-g[0-9]+$`, canonical parent equals `kindDir`), and loading `<active_dir>/manifest.json` to build `InstalledPackageInfo` (cross-checking `manifest.kind == kind`, `manifest.version == active.version`, `manifest.keyId == active.keyId`, and whether `package.zip` is required or optional for `retainedArchivePath` on `CONTENT`).
  2. **Cold-start self-check vs key retirement (L449 vs L437):** L449 mentions *"loading an already-installed active map folder with self-check"*, without defining what is checked. Explicitly state that cold-start loading validates `active.json`, directory containment, `<active_dir>/manifest.json`, and (when requested for map opening via `readMapMetadata`) `data/map.json`, and **must not** re-verify `manifest.sig` against the current `TrustedKeyRing`, because Rule A (L437) guarantees offline continuity after a retired/rotated key is removed from `trusted_package_keys.json`.
  3. **Corrupt / dangling pointer semantics:** L555 writes `"quarantined/deleted"`. Pick one unambiguous action: **delete** corrupt or dangling `active.json` (e.g., if `active.json` is unparseable, `schema != 1`, `active_dir` is missing/invalid, or `<active_dir>/manifest.json` fails validation), and set `StateFlow` to `null`. Define the exact `v(\d+)-g(\d+)` regex (using `toLongOrNull()`) used to compute `maxExistingGen` when scanning `kindDir` if `active.json` is missing/corrupt.
  4. **Plain JVM `android.util.Log` stub hazard:** L555 says `PackageFailure.IoFailure` is *"logged"*. In Android unit tests (`testOptions.unitTests.isReturnDefaultValues` is `false` in `AndroidCommon.kt`), calling `android.util.Log.w(...)` directly in `PackageStorage` throws `RuntimeException("Method w in android.util.Log not mocked")` on plain JVM. Inject a plain-Kotlin `fun interface PackageStorageLogger { fun warn(message: String, cause: Throwable? = null) }` bound to `android.util.Log` in `ContentModule` and a fake/no-op in JVM tests.

---

### R-M2 — Wire Contract & Validation Ambiguities Across `StrictZipReader`, `PackageManifestParser`, `MapMetadataParser`, and `PmTilesInspector`

- **Severity:** Major
- **Affected Scope:** Phase 2; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L346–363, L379–480; [signing.py](../../../tools/packages/src/seerah_packages/signing.py); [archive.py](../../../tools/packages/src/seerah_packages/archive.py); [manifest.schema.json](../../../content/schema/manifest.schema.json); [map.schema.json](../../../map-data/schema/map.schema.json).
- **Finding & Guidance:**
  1. **Ambiguous ZIP entry cap (L384):** *"capped at `65_535` (actually `<= 16_384` entries practical DoS cap; if `totalEntries == 0xFFFF`, fail as ZIP64)"*. Lock down a single constant (`MAX_ZIP_ENTRIES = 16_384`), and explicitly check EOCD `cdSize != 0xFFFF_FFFFL` and `cdOffset != 0xFFFF_FFFFL` as ZIP64 sentinels.
  2. **Optional CRC-32 wording (L402):** Replace *"check CRC-32 if desired"* on `manifest.sig` with mandatory CRC-32 verification across `manifest.json`, `manifest.sig`, and every `data/*` entry using unsigned 32-bit `Long` masking (`and 0xFFFF_FFFFL`).
  3. **Exact `validateEntryPath` segments (L396):** Note explicitly that `.` and `..` match `[A-Za-z0-9._-]+`, so path validation must split on `/` and reject any segment equal to `"."`, `".."`, or `""`, matching [archive.py](../../../tools/packages/src/seerah_packages/archive.py) L95–97.
  4. **`CONTENT` `tool_commit` / `image` rule (L432):** Resolve the draft question (*"forbid `tool_commit` and `image` if present (`InvalidManifest`), or ignore? Wait..."*). Under `explicitNulls = false` (L422), DTO properties alone cannot tell `"tool_commit": null` apart from an omitted key. Inspect the parsed `JsonObject` (or check keys before DTO mapping) to reject `"tool_commit" in obj || "image" in obj` on `kind == CONTENT`, matching [manifest.schema.json](../../../content/schema/manifest.schema.json) L73.
  5. **`created` timestamp fractional seconds trap (L430):** `Instant.parse()` and `DateTimeFormatter.ISO_INSTANT` accept fractional seconds (`2026-01-01T00:00:00.123Z`) and 5-digit years (`+10000-...`). Require BOTH regex `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` AND `Instant.parse(created)` (or `ResolverStyle.STRICT` UTC formatter) so invalid calendar dates like `2026-02-30` and fractional seconds are both rejected, matching `validate_iso_timestamp` in [archive.py](../../../tools/packages/src/seerah_packages/archive.py) L74–84.
  6. **Separate strict `Json` config for `TrustedKeyRing` (L352 vs L422):** `manifest.json` and `map.json` use `ignoreUnknownKeys = true`, whereas `TrustedKeyRing` must use a dedicated `Json { ignoreUnknownKeys = false; ... }` (matching [signing.py](../../../tools/packages/src/seerah_packages/signing.py) L195–198), rejecting unknown fields inside key objects, rejecting sibling keys alongside `"keys"` in `{"keys": [...]}` (L285), and rejecting `"public_key_hex": null`.
  7. **`InflaterInputStream` failure modes, zero-byte files, cancellation, & TOCTOU (L408–413):**
     - Map `ZipException` / `EOFException` / `IOException` thrown by `InflaterInputStream` on corrupt DEFLATE payloads to `PackageFailure.PayloadIntegrityError` / `InvalidContainer` (never throw).
     - Verify `inflater.finished()` after reading `sizeBytes` + 1-byte overflow probe (and handle `sizeBytes == 0`, which is valid in [manifest.schema.json](../../../content/schema/manifest.schema.json) L94 `"minimum": 0`).
     - Ensure the bounded `FileChannel` slice `InputStream` does **not** close the underlying `FileChannel` when `InflaterInputStream.close()` runs, keep the single `FileChannel` open across steps 3, 4, and 8 (and copy `package.zip` for `CONTENT` from offset `0..totalSize` on that same `FileChannel` to prevent TOCTOU swaps), and call `coroutineContext.ensureActive()` inside the `256 KiB` streaming loop so cancelling a ~1.4 GiB map install aborts promptly.
  8. **PMTiles GZIP metadata cap & JSON primitive checks (L471–475):**
     - Clarify L471 (*"`<= 1_048_576 + 1` bytes"*): read up to `1_048_576 + 1` decompressed bytes and fail with `InvalidPmTiles` if `decompressedSize > 1_048_576` (max valid size is `1_048_576`). Also map `GZIPInputStream` `IOException` to `InvalidPmTiles`.
     - For `"tile_schema": 1`, verify `elem is JsonPrimitive && !elem.isString && elem.intOrNull == 1` (rejecting `"1"`, `1.0`, or `true`).
     - Clarify why `MapMetadataParser` checks `sources.*.max_zoom in 0..22` (L456–457) while [map.schema.json](../../../map-data/schema/map.schema.json) L87/L129 restricts producers to `6..12`, and why `MapMetadataParser` checks `tile_size == 256` while `:core:model` allows `tileSize > 0`.

---

### R-M3 — `StatFs` Non-Existent Directory Crash, Unspecified Space-Estimator Formula, & `currentAppVersionCode` Source

- **Severity:** Major
- **Affected Scope:** Phase 2 & Phase 3; `AvailableSpaceProvider`, `PackageStorage`, `PackageInstaller`, `ContentModule`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L427, L517–525.
- **Finding & Guidance:**
  1. **`StatFs` on non-existent `<filesDir>/content/`:** On Android, `StatFs(directory.path)` throws `IllegalArgumentException` (wrapping `ENOENT`) if `directory` does not exist yet (every fresh install before the first package is written). `StatFsAvailableSpaceProvider` must create the root directory (`mkdirs()`) or walk up to an existing ancestor (`context.filesDir`) inside `Dispatchers.IO`, and catch `IllegalArgumentException` -> `PackageFailure.IoFailure` in `PackageInstaller`.
  2. **Unspecified `estimateDownloadAndInstallRequiredBytes` (L525):** Give the exact Kotlin signature, receiver/package, and arithmetic formula so future `:core:update` does not miscalculate the `~3×` map budget:
     - For `PackageKind.MAP`: `archiveBytes + uncompressedPayloadBytes + SAFETY_MARGIN_BYTES` (downloaded archive in cache + extracted version directory + `32 MiB`, which together with any already-installed map approximates `3×` total disk usage).
     - For `PackageKind.CONTENT`: `archiveBytes + uncompressedPayloadBytes + archiveBytes + SAFETY_MARGIN_BYTES` (downloaded archive + extracted files + retained `package.zip` copy + `32 MiB`, or `archiveBytes + uncompressedPayloadBytes + SAFETY_MARGIN_BYTES` if moved — lock one formula down!).
  3. **`currentAppVersionCode` provisioning & API 33 deprecation:** Specify how `ContentModule` provides `currentAppVersionCode` (`@AppVersionCode Long` / `Int`). Note that if `:core:content` reads `context.packageManager.getPackageInfo(context.packageName, 0)`, `compileSdk = 37.2` with `allWarningsAsErrors = true` will **fail compilation** on the deprecated `getPackageInfo(String, Int)` overload unless gated with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` (`PackageManager.PackageInfoFlags.of(0)`), and it must not be called on the main thread during Hilt singleton creation. Binding `@AppVersionCode val versionCode: Int` from `:app`'s `BuildConfig.VERSION_CODE` avoids `PackageManager` IPC completely and simplifies tests.

---

### R-M4 — Golden Fixture Pipeline, CI Drift Guard, `REUSE.toml`, & `TrustedKeysGuardTest` Details

- **Severity:** Major
- **Affected Scope:** Phase 2 & Phase 3; `tools/packages/`, `android/core/content/src/test/resources/fixtures/`, `android/app/src/test/`, `.github/workflows/ci.yml`, `REUSE.toml`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L581–589, L612.
- **Finding & Guidance:**
  1. **Exact generator script & parameters:** L583 hedges on location (*"`e.g., tools/packages/scripts/generate_android_test_fixtures.py` or test helper"*). Lock down the exact script path (e.g., `tools/packages/scripts/generate_android_test_fixtures.py`) and every fixed parameter so regenerated fixtures stay deterministic:
     - `key_id` (e.g., `"rfc8032-test-1"`, RFC 8032 Test 1 seed kept transient in Python code — never written to PEM so `.pre-commit-config.yaml` `detect-private-key` never trips), `created = "2026-01-01T00:00:00Z"`, `version = 1`, `min_app_version = 1`, fixed 40-char `source_commit` / `tool_commit`, `image = "sha256:..."` (must be `sha256:` rather than `local:` so Kotlin's `allowLocalProvenance = false` default passes!), valid bounds/credits matching [map.schema.json](../../../map-data/schema/map.schema.json), and minimal `data/index.json` bytes for `valid-content-v1.zip`.
     - Note explicitly that `trial-rejected-map-v1.zip` **cannot** be built via `pack_map_staging` (because `pack_map_staging` rejects `"trial": true` at staging time); the generator must construct it via `archive.build_package(...)` directly.
  2. **CI drift detection in `.github/workflows/ci.yml`:** Running `verify` on checked-in fixtures (L588) only proves those files are valid; it does **not** fail if `tools/packages` changes manifest formatting or ZIP headers while checked-in fixtures go stale. Add a pytest test in `tools/packages/tests/` that runs the generator into `tmp_path` and asserts byte-for-byte equality against `android/core/content/src/test/resources/fixtures/*`, in addition to running `verify_package`. Note that [ci.yml](../../../.github/workflows/ci.yml) `packages` job installs only `tools/packages/requirements.txt` (`cryptography`, `jsonschema`, `pmtiles`, `pytest`), which suffices for this generator.
  3. **`REUSE.toml` & pre-commit:** Checked-in `.zip` fixtures (`< 64 KiB`) easily satisfy `.pre-commit-config.yaml`'s `check-added-large-files` (`--maxkb=1024`), and match `REUSE.toml`'s `path = "**"` `Apache-2.0` rule. Ensure `trusted-keys.json` and `trusted_package_keys.json` end with a trailing `\n` (`end-of-file-fixer` and `check-json`), and document in `REUSE.toml` / `android/core/content/README.md` that `src/test/resources/fixtures/` contains synthetic `Apache-2.0` test fixtures (not `LicenseRef-Seerah-Content`).
  4. **`TrustedKeysGuardTest` contract:** Specify exactly how `:app`'s `TrustedKeysGuardTest` accesses `:core:content`'s `res/raw/trusted_package_keys.json` (Robolectric reading `app.seerah.content.R.raw.trusted_package_keys`, validating via `TrustedKeyRingLoader` — NOT asserting `keys.isEmpty()`, which would break after the real key ceremony — and failing if any `TrustedKeyEntry` matches RFC 8032 Section 7.1 Test 1 or Test 2 raw 32-byte public keys or uses a `"test"`/`"rfc8032"` prefix).

---

### R-M5 — Plain JVM vs Robolectric Test Split & Dependency Declarations

- **Severity:** Major
- **Affected Scope:** Phases 1–3; `android/core/map/`, `android/core/content/`, `android/app/`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L172, L179–180, L590–614; [core/map/README.md](../../../android/core/map/README.md) L15.
- **Finding & Guidance:**
  1. **`:core:map` `org.json` stub split:** [StyleJson.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt) uses `org.json.JSONObject` / `JSONArray`, which are Android stubs on plain JVM (throwing `"Method ... not mocked"`). Pure `MapPackageBindingTest` checks (`toCameraLimits`, `resolveMapFiles`, `styleCValueRamp`, `toStyleSpec`) run on plain JVM, whereas any integration test passing `toStyleSpec()` into `styleJson()` (L600) **must** run under Robolectric (`@RunWith(AndroidJUnit4::class)`). Keep them cleanly split (e.g., `MapPackageBindingTest` on plain JVM and `MapPackageStyleJsonTest` with `@RunWith(AndroidJUnit4::class)`).
  2. **`:core:content` pure JVM vs Robolectric split:** Keep `Ed25519Verifier`, `TrustedKeyRing`, `StrictZipReader`, `PackageManifestParser`, `MapMetadataParser`, `PmTilesInspector`, `PackageStorage`, and `PackageInstaller` free of Android framework calls (`java.util.Base64` instead of `android.util.Base64`; injected `PackageStorageLogger` instead of `android.util.Log`; `AvailableSpaceProvider` interface instead of direct `StatFs`), so the entire security/ZIP/PMTiles/installer/compromise-reset suite runs fast on plain JVM using `tempDir` and `ClassLoader` resource streams. Reserve Robolectric (`@RunWith(AndroidJUnit4::class)`) for an explicit `ContentAndroidBindingsTest` verifying `R.raw.trusted_package_keys`, `StatFsAvailableSpaceProvider`, and `Context.filesDir` wiring.
  3. **Missing test dependencies in `:core:content` / `libs.versions.toml`:** Specify `:core:content`'s exact test dependencies (`junit4`, `kotlin-test`, `kotlinx-coroutines-test`, `robolectric`, `androidx-test-ext-junit`, and `androidx-test-core` if `ApplicationProvider` is called directly, to satisfy DAGP `buildHealth`).
  4. **`:app` Robolectric `SeerahApplication` side effect:** Every Robolectric test in `:app` ([RightToLeftTest.kt](../../../android/app/src/test/kotlin/app/seerah/RightToLeftTest.kt) and `TrustedKeysGuardTest`) instantiates `@HiltAndroidApp SeerahApplication`. Ensure `SeerahApplication`'s startup `cleanupOrphanedFiles()` coroutine handles Robolectric temp-directory teardown cleanly without throwing unhandled exceptions across tests.

---

### R-M6 — `styleCValueRamp(inkArgb)` Bitwise Alpha Mask Bug & `MapPackageBinding` Contracts

- **Severity:** Major
- **Affected Scope:** Phase 1; `android/core/map/src/main/kotlin/app/seerah/map/MapPackageBinding.kt`, `MapStyleSpec.kt`, `StyleJson.kt`; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L563–574; [Views.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/Views.kt) L36–47.
- **Finding & Guidance:**
  1. **Alpha-byte masking in `styleCValueRamp(inkArgb)`:** In [Views.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/Views.kt) L47, `MapColours.ink` is `0xFF967648.toInt()` (alpha byte is `0xFF`, opaque!). In L570–571, `toStyleSpec()` passes `colours.ink` as default `styleCValueRamp(colours.ink)`. If `styleCValueRamp` does `(alpha shl 24) or inkArgb` without first stripping alpha (`val rgb = inkArgb and 0x00FFFFFF`), all 6 ramp stops stay `0xFF...` opaque instead of `0x00`, `0x33`, `0x66`, `0x99`! Spec must explicitly require `inkArgb and 0x00FFFFFF`.
  2. **`resolveMapFiles()` path & kind hardening:** Require `require(packageInfo.kind == PackageKind.MAP)`, verify both `baseFile` and `reliefFile` pass canonical containment against `File(packageInfo.dataDirPath).canonicalFile` (using `toPath().startsWith(...)` or `canonicalPath + File.separator` so sibling directory prefixes cannot match), and verify `.isFile`.
  3. **`ReliefSource` `tileSize` invariant:** Put `init { require(tileSize > 0) }` inside both `ReliefSource.Painted` and `ReliefSource.Value` (plus `require(spec.tileSize > 0)` in `StyleJson.kt`), keeping `tileSize: Int = 256` as the **last** constructor parameter so all existing positional callers in [Views.kt](../../../android/app/src/benchmark/kotlin/app/seerah/trial/Views.kt) and [StyleJsonTest.kt](../../../android/core/map/src/test/kotlin/app/seerah/map/StyleJsonTest.kt) compile unchanged.

---

## Nits

### R-N1 — Conversational Draft Notes Left Inside Spec Prose

- **Severity:** Nit
- **Affected Scope:** [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L384, L432, L543.
- **Finding & Guidance:**
  Three lines contain self-questioning draft prose rather than final spec decisions:
  - L384: *"capped at `65_535` (actually `<= 16_384` entries practical DoS cap; if `totalEntries == 0xFFFF`, fail as ZIP64)"*
  - L432: *"forbid `tool_commit` and `image` if present (`InvalidManifest`), or ignore? Wait: check `manifest.schema.json` line 73..."*
  - L543: *"(Check `withContext(NonCancellable)` around steps 11–14 so coroutine cancellation cannot split pointer switch from cleanup!)"*
  Replace all three with crisp, single-choice rules before presenting to the owner for approval.

---

### R-N2 — Minor Documentation & Notices Alignment

- **Severity:** Nit
- **Affected Scope:** Phase 3 docs checklist; [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md) L456–457, L623; [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md); [docs/README.md](../../../docs/README.md).
- **Finding & Guidance:**
  1. **`THIRD_PARTY_NOTICES.md` scope (L623):** Live [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md) L3–4 states app runtime libraries are checked by Licensee and shown in the in-app notices screen (rather than duplicated in `THIRD_PARTY_NOTICES.md`, except MapLibre which is not in `release` yet). Clarify whether `tink-android` and `kotlinx-serialization-json` are documented in `THIRD_PARTY_NOTICES.md` or left to Licensee.
  2. **`docs/README.md`:** Add an entry for `v1-android-package-and-map-spec.md` in [docs/README.md](../../../docs/README.md) alongside `v1-map-pipeline-spec.md` (and link the EGM review folder once complete).

---

## Implicit Assumptions & Decisions to Lock Down or Surface

1. **No blocking conflict with [docs/open-decisions.md](../../open-decisions.md):** `D1–D5` stay cleanly inside accepted ADRs (`0003`, `0007`, `0008`, `0009`) and keep map bounds, zooms, encoding, first-launch bundling, and historical reconstruction parameters un-hardcoded.
2. **Verify whether `PackageStorage` should expose a 3-state cold-start model (`Loading` / `Uninstalled` / `Installed`) vs `StateFlow<InstalledPackageInfo?>` + `suspend currentInstalled()`:** Because `SeerahApplication` + `StrictMode` forbids synchronous disk I/O in `PackageStorage`'s Hilt constructor, starting `StateFlow<InstalledPackageInfo?>` at `null` introduces a transient `null` before IO hydration completes. Locking down whether `observeInstalled(kind)` uses a `Loading` wrapper or callers use `suspend currentInstalled(kind)` avoids UI flicker later in `:feature:explore` / `:feature:settings`.
3. **Confirm whether `THIRD_PARTY_NOTICES.md` should list `tink-android` / `kotlinx-serialization-json` or leave app dependencies strictly to Licensee** (currently [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md) omits Hilt, Compose, Coroutines, etc.).
