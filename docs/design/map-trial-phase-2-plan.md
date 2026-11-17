# Map trial, phase 2: the map on the device

- **Author:** MohdFaragDev
- **Status:** Draft, for the owner's review. Decisions D1–D3 below wait for the owner.
- **Date:** 2026-11-16, revised 2026-11-17 after the [review](egm-review-map-trial-phase-2/consolidated_review_report.md)

> For agentic workers: use the `superpowers:subagent-driven-development` skill (recommended) or `superpowers:executing-plans` (both from the Claude Code `superpowers` plugin) to carry out this plan task by task. Steps use checkboxes (`- [ ]`) for tracking.

**Goal:**

- The first piece of `:core:map`: MapLibre Native drawing a trial relief PMTiles file from device storage, with the trial views' relief, fades and camera limits.
- A `benchmark` build type of `:app` holding the temporary trial screen, the measurement overlay and the scripted camera path.
- Together, they let the owner open and measure phase 1's trial maps on the three test devices.

**Where this fits.** The approved [specification](map-trial-build.md) has three phases; its Problem, Goals, Alternatives (A1–A19) and Known risks apply here unchanged. [Phase 1](map-trial-phase-1-plan.md) built the map tool, whose output folder `<name>-<variant>-<fp8>/` holds `relief.pmtiles`, `metadata.json` and `report.json`; the owner copies `camera-path.json` into it ([map-data/README.md](../../map-data/README.md), [tools/map/README.md](../../tools/map/README.md)). This phase reads that folder on the device. Phase 3 adds the base layer (`base.pmtiles`: land, coast, Natural Earth) and the documents.

**Architecture:**

- **Build logic:** the shared Android settings move from the application extension to `CommonExtension`; two new convention plugins, `seerah.android.library` and `seerah.android.library.compose`, follow the application ones.
- **`:core:map`** (kept after the trial and shipped in v1) is the only module that touches MapLibre ([ADR 0003](../adr/0003-v1-architecture-and-modules.md)). Its public interface takes plain data (`MapFiles`, `MapStyleSpec`, `CameraLimits`) and gives back a `MapCamera` and `MapEvent`s. One internal, unit-tested function, `styleJson`, builds the style JSON.
- **The `benchmark` build type** of `:app`, as the spec's "The app" defines it. Only it depends on `:core:map`, and it has host unit tests (Task 3).
- **Trial code** lives only in `android/app/src/benchmark/kotlin/app/seerah/trial/` and is removed after the trial. Pure logic (`FrameStats`, `TrialFiles`, `Views`, `PathResults`) is unit tested in `src/testBenchmark/`; the activity and overlay are checked on devices.
- **Release guard:** two layers. A build-logic rule fails the build if any `:app` configuration outside `benchmark*` declares `:core:map`. `tools/android/check_no_trial_code.py` fails CI if the release APK's manifest declares any `app.seerah.trial` component, or if it cannot read the manifest (aapt2 missing, or an empty or unparsable dump).

**Tech stack:** Kotlin 2.4.21, AGP 9.5.1, Compose BOM 2026.09.00, MapLibre Native Android 13.7.1 (`org.maplibre.gl:android-sdk-opengl`), `org.json` from the Android framework, JUnit 4 and Robolectric 4.17 for unit tests, Python 3 (standard library only) for the release guard.

**Spec:** [docs/design/map-trial-build.md](map-trial-build.md) (Approved, 2026-11-14), phase 2, files 19–31 (the numbering of its "Detailed Implementation Plan"); "The app" and the Verification Plan.

## Facts checked (2026-11-17)

1. **Host unit tests for `benchmark`.** AGP 9.5.1 creates host unit tests only for the tested build type (debug). With a probe `benchmark` build type, `./gradlew :app:testBenchmarkUnitTest --dry-run` failed with "task not found", and `:app:tasks` reported "Unit tests are disabled for this variant (enableUnitTest = false in beforeVariants)". Two fixes worked in the probe: `android.onlyEnableUnitTestForTheTestedBuildType=false` in `gradle.properties` (enables `testReleaseUnitTest` too), and `beforeVariants(selector().withBuildType("benchmark")) { it.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true }` (only `testBenchmarkUnitTest`, which `check` then runs). The plan uses the second (Task 3). The probe edits were reverted.
2. **`CommonExtension` in AGP 9.5.1** has no type parameters (`javap` on `gradle-api-9.5.1.jar`): `interface CommonExtension extends ExtensionAware`, with `getDefaultConfig(): DefaultConfig` (`minSdk` on `BaseFlavor`), `getCompileOptions`, `getLint`, `getTestOptions`, `getBuildFeatures` and `compileSdk`. `UnitTestOptions` has `isIncludeAndroidResources` and `all(Function1<Test, Unit>)`. `Lint.targetSdk` and `TestOptions.targetSdk` exist (`gradle-common-api-9.5.1.jar`). So `AndroidCommon.kt`'s body compiles unchanged on `CommonExtension`.
3. **MapLibre 13.7.1:**
   - `org.maplibre.gl:android-sdk-opengl:13.7.1` is on Maven Central (`repo1.maven.org`, HTTP 200 for the POM).
   - `gh release list -R maplibre/maplibre-native`: `android-v13.7.1` released 2026-10-15; `android-v13.5.4` (2026-11-09) is the "Latest" tag but an older line and under 14 days old; the `-globe` builds are pre-releases.
   - The `color-relief` layer came in `android-v13.0.0` (release notes, PR #3965). `["elevation"]` is a known expression input in 13.7.1 (`src/mln/style/expression/parsing_context.cpp`, `compound_expression.cpp`; the Android `ColorReliefLayer` KDoc).
   - **`step` in `color-relief-color`:** `src/mln/renderer/layers/render_color_relief_layer.cpp` at `android-v13.7.1` reads stops exactly only from an `interpolate` expression. Any other expression, `step` included, is sampled at 256 points from −500 to 9,000, about 37 m apart. Option (c)'s values are 0–255 (Terrarium red 128), so a `step` ramp would come out with soft, shifted band edges, not a rejection. See Deviations.
4. **`validatePlugins`** for the included build: `cd android && ./gradlew :build-logic:convention:validatePlugins` runs and passes (also `-p build-logic :convention:validatePlugins`).
5. **The release manifest's components today** (merged manifest of `:app:processReleaseMainManifest`): `app.seerah.MainActivity`, `androidx.startup.InitializationProvider`, `androidx.profileinstaller.ProfileInstallReceiver`.
6. **Resolved versions in `:app` today:** `kotlinx-coroutines-android` 1.9.0 and `lifecycle-runtime-compose` 2.11.0 (`:app:dependencies --configuration releaseRuntimeClasspath`).

## Global Constraints

### Terms

- "The app", the style rules, the trial views and the overlay's measurement rules are those of the spec's [The app](map-trial-build.md#the-app) section; trial checks are its [Step 1 checks](map-trial-build.md#step-1-checks).
- **Zooms:** the spike's zooms and the build's are 256-pixel tile zooms; style, fade and camera zooms are MapLibre zooms; MapLibre zoom = build zoom − 1 ([Zoom conventions](map-trial-build.md#zoom-conventions)).
- **Stored value:** the value variant's Terrarium value, 0–255 (red 128, green = darkness × 255), which `["elevation"]` returns.
- "Trial step 1, check N" means the spec's step 1 checks table; "Task N, Step M" means this plan.

### Paths and modules

- **Paths:** relative to the repository root. "GRADLE" means `cd android && ./gradlew <tasks> --console=plain`.
- **Module:** `:core:map` at `android/core/map/`, namespace and package `app.seerah.map`.
- **Trial package:** `app.seerah.trial`, only under `android/app/src/benchmark/` and `android/app/src/testBenchmark/`.
- **MapLibre boundary:** only `:core:map` declares `org.maplibre.gl` (`enforceMapLibreBoundary`).

### Where this phase differs from the spec's style and measurement rules

- **No base layer yet (D1):** the style draws a land-coloured background under the relief; the sea is hidden until phase 3 brings `base.pmtiles`.
- **Option (a)'s fade (13.5–15.5)** is a trial variable, marked so in KDoc.
- **The cold run** (the first of the three) is reported separately as well as in the totals.
- **Median:** of an even count, the mean of the two middle values. A run without a published reading shows "no reading".
- **Readings per run:** before each run the activity resets `FrameStats` and starts a new reading list; readings outside a run are shown but not kept.

### Code rules

- Kotlin to [ADR 0004](../adr/0004-build-and-quality-tooling.md) (Spotless, ktlint, Compose Rules); warnings are errors. KDoc on every public type and non-obvious function. Numbers in production code are named constants ([docs/engineering.md](../engineering.md)); the tests hold the expected literals.
- **No spike code is copied.** The spike (`android/spike/`, its `Stats.kt` and `MapStyle.kt`) is read for values and lessons only.
- **Trial text** is English literals in the benchmark source set (spec file 30: a developer tool, never shipped). No resources.

### Process

- **Owner gates:** an agent stops at every step marked **Owner gate**. An agent cannot run anything on the three devices (Oppo A55, Galaxy A54, MatePad 11.5, [roadmap](../roadmap.md)); every device step is an owner gate, and the agent says plainly that it was not checked on a device.
- **Commits:** Conventional Commits, scope `android` for build and app changes and `map` for `:core:map`, one per task, on this branch, no attribution lines. Nothing is pushed without the owner's approval ([AGENTS.md](../../AGENTS.md)).

## Decisions for the owner

| # | Decision | Recommendation |
| --- | --- | --- |
| D1 | **No base layer yet.** The spec's `MapFiles(relief, base)` and its `land` fill and `coast` line need `base.pmtiles`, which phase 3 builds; its layer names are not decided yet. | `MapFiles(relief: File)` in phase 2; phase 3 adds a non-null `base` with its layers. Until then the style's background is land-coloured, so relief reads over land and the sea is hidden. Rejected: a stub `base.pmtiles` now (it would fix layer names phase 3 has not decided), and a land background kept in phase 3 (it would hide the sea). Phase 2's device gate checks the mechanics (trial step 1, checks 1–4 and 11, the overlay, the camera path), not the look. |
| D2 | **The per-zoom view** needs a per-zoom variant, which phase 1 deferred (its D5); its variant name is not defined. | Leave the per-zoom view out of phase 2 and add it with the per-zoom follow-up. Rejected: naming the per-zoom variant now, ahead of the tool that builds it. Phase 2 has four views. |
| D3 | **16 KB page alignment of the benchmark APK.** The release APK has no native code until it uses `:core:map`; the benchmark APK carries MapLibre's `.so` files. | Run `check_page_alignment.py` on the benchmark APK too, so a MapLibre alignment problem shows now and not with the first real screen. |

Approving the plan approves D1–D3 and the deviations below. The device steps (Task 12) and the push (Task 11, Step 5) keep their gates.

## Deviations from the specification

- **MapLibre version: 13.7.1** (fact 3). The implementer checks the rule again on the implementation date (Task 6, Step 1).
- **The artifact** is `org.maplibre.gl:android-sdk-opengl` (OpenGL, [ADR 0002](../adr/0002-proposed-stack.md)), the coordinate the spike used.
- **`MapFiles` has no `base` in phase 2** (D1), and the per-zoom view is left out (D2).
- **Option (c)'s ramp is checked for soft edges, not only for rejection.** Fact 3 shows `step` is not rejected but sampled every 37 m, so trial step 1, check 3 looks at the band edges on the device. Phase 2 writes `step`, as the spec says; if the edges are soft, the `interpolate linear` fallback (stops 0.5 apart, read exactly) replaces it and `valueStyle_hasRampThresholds` changes with it. The owner may prefer to start with `interpolate`; that is a spec change.
- **Option (c)'s fade:** the spec gives the value view no fade. It uses option (a)'s 13.5–15.5 through `color-relief-opacity`, so both close-up options are compared at the same zooms; a trial variable like (a)'s.
- **`check` and the benchmark unit tests:** spec line 978 assumes `./gradlew check` runs the `testBenchmark` tests. Under AGP 9.5.1 it does not by default (fact 1); Task 3 enables them for `benchmark` only.
- **Release guard, second layer:** a build-logic rule in `DependencyRules.kt` (Task 3) closes the dependency leak the manifest check cannot see. The aapt2 manifest check stays as spec G7 and file 31 define it.
- **Added tests beyond spec file 31:** host tests for the release guard, and Robolectric tests for `TrialFiles`, `Views` and `PathResults`. `StyleJsonTest` runs under Robolectric for fidelity to Android's `org.json`, which is a stub in plain JVM unit tests.
- **Trial step 1, check 10 (Licensee and MapLibre)** is settled by the first `:core:map` build that adds MapLibre (Task 6), not on a device.
- **Docs pulled into this phase:** `android/README.md` (the library plugins, `:core:map`, the `benchmark` build type) and the `docs/map-trial/step-1.md` sections for checks 1–4, 10 and 11. No `CHANGELOG.md` entry: nothing here is user-visible.
- **Left to phase 3:** the `tools/README.md` row for `tools/android/` (spec file 42).
- Spec file 31 says "Modify" for `check_no_trial_code.py`; the file does not exist yet, so it is created.

## Review Focus

Five failure modes the spec implies, each pinned by a test:

1. **The guard checks nothing.** `aapt2` is missing, or the dump is empty or unparsable, and the script passes. It must exit 1 (Task 3, `test_fails_when_no_manifest_found`).
2. **Idle pauses counted as slow frames**, a bug the spike once had: a 300 ms pause must not lower the lowest rate (Task 8, `pauseOver250ms_isIgnored`).
3. **A bad variant folder** (no `metadata.json`, `trial` not `true`, unknown `variant`, no `camera-path.json`) crashes the trial screen. It must be listed as skipped with its reason (Task 7, `folderWithoutTrialTrue_isSkippedWithReason` and siblings).
4. **The fade in the wrong zoom convention:** the zoom-12 view must fade at MapLibre 11.5–13.5, not the spike's build-zoom 12.5–14.5 (Task 7, `zoom12View_fadesAt11_5To13_5`).
5. **The cold run hidden in the totals:** the results must show the first run separately and take the lowest rate over all three runs (Task 9, `results_reportColdRunSeparately_andMinimumOverAllRuns`).

Two risks only the devices show (Task 12): MapLibre's native libraries' size and install on the Oppo A55 (Step 1), and whether `addOnDidFinishRenderingFrameListener` fires once per displayed frame (Step 3, check 4).

## File structure

```text
android/
  build.gradle.kts                                            android-library plugin alias (Task 2)
  build-logic/convention/build.gradle.kts                     register the library plugins (Task 2)
  build-logic/convention/src/main/kotlin/
    app/seerah/buildlogic/AndroidCommon.kt                    CommonExtension (Task 1)
    app/seerah/buildlogic/DependencyRules.kt                  :core:map only in benchmark (Task 3)
    AndroidLibraryConventionPlugin.kt                         (Task 2)
    AndroidLibraryComposeConventionPlugin.kt                  (Task 2)
    AndroidApplicationConventionPlugin.kt                     benchmark build type, its unit tests (Task 3)
  gradle/libs.versions.toml                                   (Tasks 2, 5, 6, 7)
  settings.gradle.kts                                         include(":core:map") (Task 4)
  core/map/
    build.gradle.kts                                          (Tasks 4, 5, 6)
    README.md                                                 (Task 6)
    src/main/kotlin/app/seerah/map/
      MapFiles.kt, MapStyleSpec.kt, CameraLimits.kt           (Task 4)
      MapCamera.kt, MapEvent.kt                               (Task 4)
      StyleJson.kt                                            internal (Task 5)
      SeerahMap.kt                                            (Task 6)
    src/test/kotlin/app/seerah/map/StyleJsonTest.kt           (Task 5)
  app/build.gradle.kts                                        benchmarkImplementation (Tasks 7, 9)
  app/src/benchmark/
    AndroidManifest.xml                                       (Task 10)
    kotlin/app/seerah/trial/
      TrialFiles.kt, Views.kt                                 (Task 7)
      FrameStats.kt                                           (Task 8)
      CameraPath.kt                                           (Task 9)
      Overlay.kt, TrialActivity.kt                            (Task 10)
  app/src/testBenchmark/kotlin/app/seerah/trial/
    TrialFilesTest.kt, ViewsTest.kt                           (Task 7)
    FrameStatsTest.kt                                         (Task 8)
    CameraPathTest.kt                                         (Task 9)
  README.md                                                   (Tasks 2, 3, 6, 11)
tools/android/
  check_no_trial_code.py                                      (Task 3)
  tests/test_check_no_trial_code.py, tests/fixtures/*.txt     (Task 3)
.github/workflows/android.yml                                 (Task 11)
docs/map-trial/step-1.md                                      (Tasks 6, 12)
```

## Tasks

### Task 1: Shared build logic on `CommonExtension` (spec file 19)

**Files:**

- Modify: `android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt`

**Interfaces:**

- Produces: `internal fun Project.configureAndroid(android: CommonExtension)` (was `ApplicationExtension`). `configureKotlin()` is unchanged.

A passing build and the existing tests are the check; build logic has no unit tests in this repository.

- [ ] **Step 1: Baseline.** GRADLE `:app:assembleDebug :app:testDebugUnitTest`. Expected: pass.
- [ ] **Step 2: Change the parameter type** to `com.android.build.api.dsl.CommonExtension` (no type parameters, fact 2). The body is unchanged. `AndroidApplicationConventionPlugin` keeps calling `configureAndroid(this)`.
- [ ] **Step 3: Run** GRADLE `spotlessCheck :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`. Expected: pass.
- [ ] **Step 4: Commit** `refactor(android): share the Android settings through CommonExtension`.

### Task 2: Library convention plugins (spec files 20, 21, 23 in part)

**Files:**

- Create: `android/build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt`, `AndroidLibraryComposeConventionPlugin.kt`
- Modify: `android/build-logic/convention/build.gradle.kts`, `android/gradle/libs.versions.toml`, `android/build.gradle.kts`, `android/README.md`

**Interfaces:**

- Produces:
  - **`seerah.android.library`** (`AndroidLibraryConventionPlugin`), written like `AndroidApplicationConventionPlugin`:

    ```kotlin
    pluginManager.apply("com.android.library")
    pluginManager.apply("app.cash.licensee")
    extensions.configure<LibraryExtension> {
        configureAndroid(this)
        lint.targetSdk = libs.intVersion("targetSdk")
        testOptions.targetSdk = libs.intVersion("targetSdk")
    }
    configureKotlin()
    configureLicences()
    enforceMapLibreBoundary()
    ```

    Not the module-graph plugin: `assertModuleGraph` runs from `:app`, which sees the whole graph.
  - **`seerah.android.library.compose`** (`AndroidLibraryComposeConventionPlugin`): applies `org.jetbrains.kotlin.plugin.compose`, sets `LibraryExtension.buildFeatures.compose = true`, and adds the Compose BOM to `implementation` and `testImplementation`. No tooling: the library has no previews yet.
  - **Catalog:** plugin aliases `seerah-android-library` and `seerah-android-library-compose` (no version), and `android-library = { id = "com.android.library", version.ref = "androidGradlePlugin" }`, declared `apply false` in `android/build.gradle.kts`.

- [ ] **Step 1: Write the plugins and register them** (`register("androidLibrary")`, `register("androidLibraryCompose")`), each class with a KDoc line as the application plugins have.
- [ ] **Step 2: Run** GRADLE `:build-logic:convention:validatePlugins help` (fact 4). Expected: pass. The plugins are first applied in Task 4.
- [ ] **Step 3: Docs.** In `android/README.md`, add both plugins to the convention table and change "Library, feature and JVM conventions are added with the first module that needs them" to name only feature and JVM.
- [ ] **Step 4: Commit** `build(android): add the library convention plugins`.

### Task 3: The `benchmark` build type and the release guard (spec files 22, 31 in part)

**Files:**

- Modify: `android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`, `android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt`, `android/README.md`
- Create: `tools/android/check_no_trial_code.py`, `tools/android/tests/test_check_no_trial_code.py`, `tools/android/tests/fixtures/release_manifest.txt`, `tools/android/tests/fixtures/trial_manifest.txt`

**Interfaces:**

- Produces:
  - build type `benchmark`, tasks `:app:assembleBenchmark` and `:app:testBenchmarkUnitTest`;
  - `internal fun Project.enforceMapOnlyInBenchmark()` in `DependencyRules.kt`, beside `enforceMapLibreBoundary`: in `:app`, it fails the build when any configuration whose name does not start with `benchmark` (case-insensitive, so `testBenchmark*` too) declares a project dependency on `:core:map`. Called from `AndroidApplicationConventionPlugin`.
  - `check_no_trial_code.py`, usage `check_no_trial_code.py APK`: exit 0 when the manifest was read and names no `app.seerah.trial` component; exit 1 otherwise, including when aapt2 is missing, exits non-zero, or prints an empty or unparsable dump. The module docstring cites [ADR 0007](../adr/0007-content-formats-compatibility-and-signing.md) decision 10. It finds aapt2 under `$ANDROID_HOME`, else `$ANDROID_SDK_ROOT`, in the newest `build-tools/<version>/` compared numerically. Other function names are the implementer's.

- [ ] **Step 1: Make the fixtures from real output.** Build `:app:assembleRelease`, run `aapt2 dump xmltree --file AndroidManifest.xml <apk>` and save it as `release_manifest.txt`. Make `trial_manifest.txt` by copying it and adding one `E: activity` element whose `android:name` is `app.seerah.trial.TrialActivity`, in the same format. To regenerate after a manifest change, repeat this step; the test's expected list changes with it.
- [ ] **Step 2: Write the failing tests** (`unittest`, standard library only). The test module imports the script with `sys.path.insert(0, str(Path(__file__).resolve().parents[1]))`.

  ```python
  def test_release_manifest_passes(self):
      # components of release_manifest.txt == ["app.seerah.MainActivity",
      #   "androidx.startup.InitializationProvider", "androidx.profileinstaller.ProfileInstallReceiver"] (fact 5); exit 0
  def test_trial_component_fails(self):            # fake aapt2 printing trial_manifest.txt -> 1, message names the component
  def test_fails_when_no_manifest_found(self):     # fake aapt2 printing "" -> 1; exiting 1 -> 1; missing build-tools dir -> 1
  def test_newest_build_tools_chosen_numerically(self):  # dirs "9.0.0", "37.0.0", "36.1.0" -> ".../37.0.0/aapt2"
  ```

  The fake `aapt2` is an executable script written into a temporary `build-tools/<version>/` folder, with `ANDROID_HOME` pointed at it.
- [ ] **Step 3: Run** `python3 -m unittest discover -s tools/android/tests`. Expected: FAIL (no module).
- [ ] **Step 4: Implement** `check_no_trial_code.py`. Then, in `AndroidApplicationConventionPlugin`, after the release block:

  ```kotlin
  /** Release-like build for measuring (ADR 0004 note, owner 2026-11-14); never published. */
  buildTypes.create("benchmark") {
      initWith(buildTypes.getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      applicationIdSuffix = ".benchmark"
      matchingFallbacks += "release"
  }
  ```

  and, so `check` runs its unit tests (fact 1):

  ```kotlin
  extensions.configure<ApplicationAndroidComponentsExtension> {
      beforeVariants(selector().withBuildType("benchmark")) { variant ->
          variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
      }
  }
  ```

  Add `enforceMapOnlyInBenchmark()`. The release signing line in `app/build.gradle.kts` stays; `benchmark` sets its own signing so it never depends on that block's order.
- [ ] **Step 5: Run** the unit tests (expected: 4 passed), `pre-commit run --all-files` (ruff, configured in `pyproject.toml`), and GRADLE `:app:assembleRelease :app:assembleBenchmark check`, then `python3 tools/android/check_no_trial_code.py android/app/build/outputs/apk/release/app-release.apk` (expected: exit 0). GRADLE `check --dry-run` lists `:app:testBenchmarkUnitTest`. To see the rule work, add `implementation(project(":core:map"))` to `:app` after Task 4 exists, once, locally: the build fails naming the configuration; revert.
- [ ] **Step 6: Docs.** `android/README.md`: the build types (`debug`, `release`, `benchmark`, what each is for, that `benchmark` is never published and only it may depend on `:core:map`).
- [ ] **Step 7: Commit** `build(android): add the benchmark build type and the release guard`.

### Task 4: `:core:map` module and public types (spec files 24, 25, 26 in part)

**Files:**

- Modify: `android/settings.gradle.kts`
- Create: `android/core/map/build.gradle.kts`, and in `android/core/map/src/main/kotlin/app/seerah/map/`: `MapFiles.kt`, `MapStyleSpec.kt`, `CameraLimits.kt`, `MapCamera.kt`, `MapEvent.kt`

**Interfaces:**

- Produces (each with KDoc):

  ```kotlin
  data class MapFiles(val relief: File)                                   // D1: base comes in phase 3
  data class MapStyleSpec(val relief: ReliefSource, val reliefFade: ZoomRange, val colours: MapColours)
  sealed interface ReliefSource {
      val maxZoom: Int
      data class Painted(override val maxZoom: Int) : ReliefSource
      data class Value(override val maxZoom: Int, val ramp: List<RampStep>) : ReliefSource
  }
  data class ZoomRange(val start: Double, val end: Double)                // MapLibre zooms
  data class MapColours(val sea: Int, val land: Int, val coast: Int, val ink: Int)  // ARGB; sea and coast drawn from phase 3
  data class RampStep(val value: Double, val colour: Int)                 // value: stored value, 0–255
  data class CameraLimits(val west: Double, val south: Double, val east: Double, val north: Double,
                          val minZoom: Double, val maxZoom: Double)
  interface MapCamera { suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long) }
  sealed interface MapEvent {
      data object FullyRendered : MapEvent
      data class FrameRendered(val atMillis: Long) : MapEvent             // SystemClock.elapsedRealtime()
  }
  ```

- [ ] **Step 1: Module build file.** Plugin `seerah.android.library` only; namespace `app.seerah.map`; no dependencies yet (each comes with the task that first uses it, so `buildHealth` never sees an unused one). `include(":core:map")` in `settings.gradle.kts`.
- [ ] **Step 2: Write the types** above, no logic.
- [ ] **Step 3: Run** GRADLE `spotlessCheck :core:map:assembleRelease :core:map:lintRelease buildHealth`. Expected: pass.
- [ ] **Step 4: Commit** `feat(map): add the core map module and its public types`.

### Task 5: The style JSON (spec files 26 `StyleJson.kt`, 27)

**Files:**

- Create: `android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt`, `android/core/map/src/test/kotlin/app/seerah/map/StyleJsonTest.kt`
- Modify: `android/core/map/build.gradle.kts`

**Interfaces:**

- Consumes: Task 4's types.
- Produces: `internal fun styleJson(files: MapFiles, spec: MapStyleSpec): String`, built with `org.json.JSONObject`/`JSONArray`:
  - `version` 8; one source `relief` (`raster`, or `raster-dem` with `encoding: terrarium`), `url` `pmtiles://file://<absolute path>`, `tileSize` 256, `maxzoom` from `ReliefSource.maxZoom`;
  - layers in order: `background` (type `background`, land colour, D1) and `relief` (`raster` with `raster-opacity`, or `color-relief` with `color-relief-color` and `color-relief-opacity`);
  - opacity: `["interpolate", ["linear"], ["zoom"], start, 1, end, 0]` from `reliefFade`;
  - ramp: `["step", ["elevation"], c0, v1, c1, v2, c2, v3, c3]` from the `RampStep`s (the first step's value is the base, not written);
  - colours: `rgba(r, g, b, a)` from ARGB, alpha = A ÷ 255 rounded to 3 decimals, trailing zeros removed (`1`, `0`, `0.2`).

- [ ] **Step 1: Dependencies.** In `android/core/map/build.gradle.kts`: `testImplementation` `junit4`, `kotlin-test`, `robolectric`, `androidx-test-ext-junit` (catalog entries exist).
- [ ] **Step 2: Write the failing tests** (`@RunWith(AndroidJUnit4::class)`), with `files = MapFiles(File("/data/t/relief.pmtiles"))`. The tests compare expressions as strings, `layer.getJSONObject("paint").getJSONArray(key).toString()`, against the expected JSON written in the test.

  ```kotlin
  @Test fun paintedStyle_parsesAndUsesARasterLayer()       // JSONObject(json); layers == ["background","relief"]; relief type "raster"
  @Test fun paintedStyle_fadesOverReliefFade()             // raster-opacity == ["interpolate",["linear"],["zoom"],11.5,1,13.5,0]
  @Test fun reliefSource_usesPmtilesFileUrl_tileSize256_andMaxZoom()  // url "pmtiles://file:///data/t/relief.pmtiles"; tileSize 256; maxzoom 12 (then 11)
  @Test fun valueStyle_usesTerrariumRasterDem_andColorRelief()        // source "raster-dem", encoding "terrarium"; layer "color-relief"; color-relief-opacity as the fade
  @Test fun valueStyle_hasRampThresholds()
      // color-relief-color == ["step",["elevation"],"rgba(150, 118, 72, 0)",42.5,"rgba(150, 118, 72, 0.2)",
      //                        127.5,"rgba(150, 118, 72, 0.4)",212.5,"rgba(150, 118, 72, 0.6)"]
  @Test fun background_isLandColour()                      // background-color "rgba(241, 230, 200, 1)"
  ```

  Each test builds its spec from literal values written in the test (land `#F1E6C8`, ink RGB 150, 118, 72, the spec's fades and thresholds), not from the trial code.
- [ ] **Step 3: Run** GRADLE `:core:map:testDebugUnitTest`. Expected: FAIL (unresolved `styleJson`).
- [ ] **Step 4: Implement** `styleJson`.
- [ ] **Step 5: Run** GRADLE `:core:map:testDebugUnitTest buildHealth`. Expected: 6 passed; pass.
- [ ] **Step 6: Commit** `feat(map): build the map style from plain data`.

### Task 6: `SeerahMap` (spec files 23 in part, 25, 26 `SeerahMap.kt`, 28)

**Files:**

- Create: `android/core/map/src/main/kotlin/app/seerah/map/SeerahMap.kt`, `android/core/map/README.md`
- Modify: `android/core/map/build.gradle.kts`, `android/gradle/libs.versions.toml`, `android/README.md`, `docs/map-trial/step-1.md`

**Interfaces:**

- Produces:

  ```kotlin
  @Composable
  fun SeerahMap(
      files: MapFiles,
      style: MapStyleSpec,
      limits: CameraLimits,
      modifier: Modifier = Modifier,
      onCamera: (MapCamera) -> Unit = {},
      onEvent: (MapEvent) -> Unit = {},
  )
  ```

  Behaviour (spec, "The app"):
  - `MapLibre.getInstance(context)` once.
  - A `MapView` in `AndroidView`, created once, its lifecycle forwarded from `androidx.lifecycle.compose.LocalLifecycleOwner`.
  - `setStyle(Style.Builder().fromJson(styleJson(files, style)))`; a change of `files` or `style` reloads the style.
  - `setLatLngBoundsForCameraTarget` with `limits`, and `setMinZoomPreference`/`setMaxZoomPreference`.
  - `onCamera` once the style has loaded, with a private `MapLibreCamera` whose `animateTo` suspends (`suspendCancellableCoroutine`) until MapLibre's `CancelableCallback` finishes, and throws `CancellationException` if it is cancelled.
  - `addOnDidFinishRenderingMapListener { fully -> if (fully) onEvent(FullyRendered) }`.
  - `addOnDidFinishRenderingFrameListener` → `onEvent(FrameRendered(SystemClock.elapsedRealtime()))`.

MapLibre's native view cannot run under Robolectric, so this task has no unit test. It is checked by compiling, `buildHealth`, and on devices in Task 12.

- [ ] **Step 1: Pin MapLibre and add dependencies.** Take the newest non-pre-release `android-v13.x` at least 14 days old (`gh release list -R maplibre/maplibre-native -L 40`); if none qualifies, stop and report. Catalog: `maplibre = "<version>"`, `maplibre-android-opengl = { group = "org.maplibre.gl", name = "android-sdk-opengl", version.ref = "maplibre" }`, `kotlinxCoroutines = "1.9.0"` (fact 6) with `kotlinx-coroutines-android`, and `androidx-lifecycle-runtime-compose` (version ref `androidxLifecycle`). Module: plugin `seerah.android.library.compose` added; `implementation` MapLibre, `kotlinx-coroutines-android`, `androidx-lifecycle-runtime-compose`; `api` Compose `runtime` and `ui` (for `@Composable` and `Modifier`).
- [ ] **Step 2: Implement** `SeerahMap` and `MapLibreCamera`.
- [ ] **Step 3: Run** GRADLE `spotlessCheck :core:map:check :core:map:licensee buildHealth`. Expected: pass; `buildHealth` decides the final `api`/`implementation` split. If Licensee rejects a MapLibre dependency (trial step 1, check 10), stop. **Owner gate:** a reviewed exception in `DependencyRules.kt` needs the owner's approval.
- [ ] **Step 4: Record check 10** in `docs/map-trial/step-1.md`: date, MapLibre version, Licensee's result.
- [ ] **Step 5: Docs.** `android/core/map/README.md`: the module's job (the only MapLibre user), its public types, how the style is built, that `base` arrives in phase 3 (D1), and that it holds no trial code. `android/README.md`: list `:core:map` under the modules.
- [ ] **Step 6: Commit** `feat(map): host MapLibre behind SeerahMap`.

### Task 7: Trial files and views (spec files 29, 30 `TrialFiles.kt`, `Views.kt`)

**Files:**

- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/benchmark/kotlin/app/seerah/trial/TrialFiles.kt`, `Views.kt`; `android/app/src/testBenchmark/kotlin/app/seerah/trial/TrialFilesTest.kt`, `ViewsTest.kt`

**Interfaces:**

- Consumes: `CameraLimits`, `MapStyleSpec`, `ReliefSource`, `RampStep`, `ZoomRange`, `MapColours`.
- Produces:

  ```kotlin
  enum class Variant { PAINTED, VALUE }                              // metadata.json "variant": "painted" | "value"
  data class CameraStop(val lat: Double, val lon: Double, val zoom: Double, val durationMs: Long)
  data class CameraPathSpec(val stops: List<CameraStop>, val limits: CameraLimits)
  data class TrialVariant(val folder: File, val variant: Variant, val fingerprint8: String,
                          val cameraPath: CameraPathSpec) { val relief: File }
  sealed interface VariantLoad {
      data class Loaded(val variant: TrialVariant) : VariantLoad
      data class Skipped(val folder: File, val reason: String) : VariantLoad
  }
  fun loadVariants(root: File): List<VariantLoad>                    // one entry per sub-folder, sorted by name
  enum class TrialView { ZOOM_12, ZOOM_11, OPTION_A, OPTION_C }      // D2: no per-zoom view
  fun viewsFor(variant: Variant): List<TrialView>                    // PAINTED -> 12, 11, A; VALUE -> C
  fun TrialView.styleSpec(): MapStyleSpec
  ```

  `camera-path.json` keys are those of `map-data/example/camera-path.json`: `stops[].lat`, `lon`, `zoom`, `duration_ms`; `limits.west`, `south`, `east`, `north`, `min_zoom`, `max_zoom`. A folder whose `metadata.json` `maxzoom` is not 12 is skipped: every view assumes a zoom-12 build. The colours are the spike's (land `#F1E6C8`, sea `#809B94`, coast `#6F8F88`, ink RGB 150, 118, 72) until `:core:designsystem` exists.

- [ ] **Step 1: Add** `"benchmarkImplementation"(project(":core:map"))`. No test dependency is added: `testImplementation` already covers `testBenchmark`.
- [ ] **Step 2: Write the failing tests** (Robolectric, temporary folders):

  ```kotlin
  // TrialFilesTest
  @Test fun validFolder_isLoaded()     // metadata {"trial":true,"variant":"painted","fingerprint":"0123456789abcdef","maxzoom":12} + the example camera-path -> Loaded, fingerprint8 "01234567", 1 stop, limits.minZoom 4.0
  @Test fun folderWithoutTrialTrue_isSkippedWithReason()   // "trial": false -> Skipped, reason mentions "trial"
  @Test fun folderWithoutCameraPath_isSkippedWithReason()
  @Test fun folderWithUnknownVariant_isSkippedWithReason() // "variant": "per-zoom" -> Skipped
  @Test fun folderWithoutRelief_isSkippedWithReason()
  @Test fun folderWithMaxZoomNot12_isSkippedWithReason()
  // ViewsTest
  @Test fun zoom12View_fadesAt11_5To13_5()        // ZOOM_12.styleSpec(): Painted(12), ZoomRange(11.5, 13.5)
  @Test fun zoom11View_isPainted11_sameFade()     // Painted(11), ZoomRange(11.5, 13.5)
  @Test fun optionA_fadesAt13_5To15_5()           // Painted(12), ZoomRange(13.5, 15.5)
  @Test fun optionC_isValue12_withFourRampSteps() // ZoomRange(13.5, 15.5); values 0.0, 42.5, 127.5, 212.5; alphas 0, 0.2, 0.4, 0.6 of ink 150,118,72
  @Test fun viewsFor_matchesVariant()
  ```

- [ ] **Step 3: Run** GRADLE `:app:testBenchmarkUnitTest`. Expected: FAIL (unresolved references).
- [ ] **Step 4: Implement.** `loadVariants` never throws for a bad folder; malformed JSON becomes `Skipped` with the parser's message. The option (a) and (c) fade carries a KDoc line saying it is a trial variable.
- [ ] **Step 5: Run** the same task. Expected: 11 passed. GRADLE `:app:assembleRelease` passes (the Task 3 rule guards the dependency).
- [ ] **Step 6: Commit** `feat(android): read trial variants and define the trial views`.

### Task 8: Frame statistics (spec files 30 `FrameStats.kt`, 31 `FrameStatsTest.kt`)

**Files:**

- Create: `android/app/src/benchmark/kotlin/app/seerah/trial/FrameStats.kt`, `android/app/src/testBenchmark/kotlin/app/seerah/trial/FrameStatsTest.kt`

**Interfaces:**

- Produces (pure Kotlin, no Android types):

  ```kotlin
  data class FrameReading(val fps: Double, val lowestFps: Double)
  class FrameStats {
      fun onFrame(atMillis: Long): FrameReading?   // non-null only when a reading is published
      fun reset()                                   // clears gaps, last frame and lowest
  }
  ```

  The rule is the spec's (gaps of 1–250 ms kept, 30 of them, published at most once a second; the first reading as soon as 30 gaps exist). A gap outside 1–250 ms is dropped but still updates the last frame time.

- [ ] **Step 1: Write the failing tests** (plain JUnit 4, no Robolectric runner):

  ```kotlin
  @Test fun steady60fps_reads60()            // gaps cycling 17, 17, 16 ms for 3 s -> every reading fps == 60.0 (±0.01)
  @Test fun pauseOver250ms_isIgnored()       // 20 ms gaps, one 300 ms gap, 20 ms gaps -> every reading 50.0, lowestFps 50.0
  @Test fun slowStretch_lowersLowest()       // 20 ms gaps for 2 s, then 50 ms gaps for 3 s -> last lowestFps == 20.0
  @Test fun readings_publishedAtMostOncePerSecond()  // 16 ms gaps for 5 s -> published times at least 1,000 ms apart
  @Test fun reset_clearsLowest()
  ```

- [ ] **Step 2: Run** GRADLE `:app:testBenchmarkUnitTest --tests 'app.seerah.trial.FrameStatsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: 5 passed.
- [ ] **Step 5: Commit** `feat(android): measure the trial frame rate`.

### Task 9: Camera path (spec file 30 `CameraPath.kt`)

**Files:**

- Create: `android/app/src/benchmark/kotlin/app/seerah/trial/CameraPath.kt`, `android/app/src/testBenchmark/kotlin/app/seerah/trial/CameraPathTest.kt`
- Modify: `android/app/build.gradle.kts`

**Interfaces:**

- Consumes: `MapCamera`, `CameraStop`.
- Produces:

  ```kotlin
  const val RUNS = 3
  suspend fun animateThrough(camera: MapCamera, stops: List<CameraStop>)   // every stop in order
  data class RunResult(val readings: List<Double>) {
      val typicalFps: Double?        // median; null without readings
      val lowestFps: Double?         // minimum; null without readings
  }
  data class PathResults(val runs: List<RunResult>) {
      val cold: RunResult            // runs.first()
      val typicalFps: Double?        // median of the runs' typical rates
      val lowestFps: Double?         // minimum over all runs
      fun asText(header: TrialHeader): String
  }
  data class TrialHeader(val device: String, val view: String, val fingerprint8: String,
                         val openTimeMs: Long?, val pssMb: Int?)
  ```

  `asText` is pure and English. Rates use one decimal (`Locale.ROOT`); a null shows "no reading":

  ```text
  Map trial | <device> | <view> | <fingerprint8>
  Open time: <openTimeMs> ms | Memory (PSS): <pssMb> MB
  Run 1 (cold): typical <x> fps, lowest <y> fps
  Run 2: typical <x> fps, lowest <y> fps
  Run 3: typical <x> fps, lowest <y> fps
  All runs: typical <x> fps, lowest <y> fps
  ```

- [ ] **Step 1: Dependency.** Catalog alias from Task 6; `"benchmarkImplementation"(libs.kotlinx.coroutines.android)` in `android/app/build.gradle.kts` (the tests use its `runBlocking`).
- [ ] **Step 2: Write the failing tests** (plain JUnit 4):

  ```kotlin
  @Test fun animateThrough_visitsEveryStopInOrder()   // fake MapCamera records calls -> stops in order, each with its durationMs
  @Test fun results_reportColdRunSeparately_andMinimumOverAllRuns()
      // runs [50,60,58], [59,60,60], [57,60,61] -> cold.typicalFps 58.0, typicalFps 60.0, lowestFps 50.0;
      // text contains "Run 1 (cold): typical 58.0 fps, lowest 50.0 fps"
  @Test fun median_ofEvenCount_isMeanOfMiddleTwo()   // [50, 60, 58, 62] -> 59.0
  @Test fun runWithoutReadings_showsNoReading()      // RunResult([]) -> nulls; text "Run 2: no reading"
  @Test fun text_includesDeviceViewFingerprintAndOpenTime()
  ```

- [ ] **Step 3: Run** GRADLE `:app:testBenchmarkUnitTest --tests 'app.seerah.trial.CameraPathTest'`. Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run.** Expected: 5 passed.
- [ ] **Step 6: Commit** `feat(android): run the trial camera path and report its results`.

### Task 10: Trial screen and overlay (spec file 30 `AndroidManifest.xml`, `TrialActivity.kt`, `Overlay.kt`)

**Files:**

- Create: `android/app/src/benchmark/AndroidManifest.xml`, `android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt`, `Overlay.kt`

**Interfaces:**

- Consumes: everything above.
- Produces:
  - `app.seerah.trial.TrialActivity` (a `ComponentActivity`), exported, with its own `MAIN`/`LAUNCHER` filter and the label "Map trial". Intent extra `EXTRA_VIEW = "app.seerah.trial.VIEW"`, value `<folder>/<view>`.
  - `@Composable fun Overlay(state: OverlayState, modifier: Modifier = Modifier)`, with `OverlayState(device: String, view: String, fingerprint8: String, fps: Double?, lowestFps: Double?, pssMb: Int?, openTimeMs: Long?, runs: List<RunResult>)`, changed only on a published reading or a finished run.

Behaviour:

- Files load off the main thread (`Dispatchers.IO`) from `getExternalFilesDir(null)/trial/` with `loadVariants`. If the folder is missing or empty, the screen says so and shows the `adb push` path.
- The view to open: the intent's `EXTRA_VIEW`, else the last used (`SharedPreferences` key `last_view`, same `<folder>/<view>` form), else the picker. Either value is used only if it equals a loaded folder's name and one of its views; anything else (`../x` included) goes to the picker.
- The picker: one button per loaded variant and view, and each skipped folder with its reason.
- `SeerahMap` gets `MapFiles(variant.relief)`, the view's `styleSpec()` and `cameraPath.limits`.
- `FrameRendered` feeds `FrameStats`; each published reading updates the overlay and reads `Debug.getPss()` (kB, shown in MB).
- Open time: recorded once per process, at the first `FullyRendered`, as the spec defines it; switching views does not measure it again.
- "Run path": `RUNS` times, reset `FrameStats`, start a new reading list, `animateThrough`; then `PathResults` goes to the overlay.
- "Copy results": `asText` to the clipboard.
- Device name: `Build.MANUFACTURER + " " + Build.MODEL`.

- [ ] **Step 1: Write** the manifest and the two files.
- [ ] **Step 2: Run** GRADLE `spotlessCheck :app:lintBenchmark :app:assembleBenchmark :app:testBenchmarkUnitTest`. Expected: pass. Then `aapt2 dump xmltree --file AndroidManifest.xml` on the benchmark APK lists `app.seerah.trial.TrialActivity`, and `check_no_trial_code.py` exits 1 on the **benchmark** APK (the guard sees it) and 0 on the release APK.
- [ ] **Step 3: Commit** `feat(android): add the map trial screen and overlay`.

### Task 11: CI and docs (spec file 31 workflow)

**Files:**

- Modify: `.github/workflows/android.yml`, `android/README.md`

- [ ] **Step 1: Workflow.**
  - "Check and build": `./gradlew spotlessCheck check buildHealth assembleRelease assembleBenchmark --console=plain` (`check` runs `testBenchmarkUnitTest`, Task 3).
  - A step "No trial code in release": `python3 ../tools/android/check_no_trial_code.py app/build/outputs/apk/release/app-release.apk`.
  - A step "Release guard tests": `python3 -m unittest discover -s ../tools/android/tests`.
  - "16 KB page alignment" also gets `app/build/outputs/apk/benchmark/app-benchmark.apk` (D3).
  - Upload `android/app/build/outputs/apk/benchmark/*.apk` as artifact `seerah-app-benchmark`, with the same pinned `upload-artifact` SHA and settings as `seerah-app`.
  - No path-filter change: `android/**` and `tools/android/**` are already covered.
- [ ] **Step 2: Run** the CI command locally, then `pre-commit run --all-files` (actionlint and zizmor cover the workflow) and the content validation, `python3 tools/validate_content.py`. Expected: pass.
- [ ] **Step 3: Docs.** `android/README.md`: how to get the benchmark APK (artifact `seerah-app-benchmark`), install it beside the normal app, `adb push <folder> /sdcard/Android/data/app.seerah.benchmark/files/trial/`, and open "Map trial". Check every touched doc's links.
- [ ] **Step 4: Commit** `ci(android): build the benchmark APK and guard release from trial code`.
- [ ] **Step 5: Owner gate:** the owner approves the push and runs the workflow or opens the pull request ([AGENTS.md](../../AGENTS.md)).

### Task 12: On the devices (spec Verification Plan, "On devices"; trial step 1, checks 1–4 and 11)

An agent cannot do Steps 1–5; it prepares the checklist in `docs/map-trial/step-1.md` and does Step 6.

- [ ] **Step 1: Owner gate: install.** The benchmark APK from `seerah-app-benchmark` installs beside the normal app on the Oppo A55, Galaxy A54 and MatePad 11.5, and shows the "Map trial" launcher entry. Note the APK size and any install problem on the Oppo A55.
- [ ] **Step 2: Owner gate: trial step 1, check 1.** `adb push` of the example output (or a trial window) into `/sdcard/Android/data/app.seerah.benchmark/files/trial/` works on all three. If it fails on any device, the in-app folder import fallback gets its own task before trial step 2.
- [ ] **Step 3: Owner gate: trial step 1, checks 2, 3 and 4.**
  - Check 2: the zoom 11 view stops drawing new relief detail past its `maxzoom` (fallback: `pmtiles extract --maxzoom=11`).
  - Check 3: option (c) draws three bands with sharp edges at the thresholds (fact 3; fallback: `interpolate linear`, and `valueStyle_hasRampThresholds` changes with it).
  - Check 4: the overlay's rate moves while panning and stays still at rest, and does not exceed the screen's refresh rate.
- [ ] **Step 4: Owner gate: trial step 1, check 11.** Each device's screen width in dp (`adb shell wm size` and `wm density`).
- [ ] **Step 5: Owner gate: the overlay and camera path.** The overlay shows device, view, fingerprint, rate, lowest, memory and open time; "Run path" runs three times; "Copy results" gives the text. Phone and tablet, in landscape on the tablet.
- [ ] **Step 6: Record** every result with its date in `docs/map-trial/step-1.md`, the numbers marked "mechanics check, no base layer" so they are not read as trial results, and commit `docs(map): record the phase 2 step 1 checks`.

## Self-review

- **Spec coverage, phase 2, files 19–31:** 19 Task 1; 20–21 Task 2; 22 Task 3; 23 Tasks 2 and 6; 24 Task 4; 25 Tasks 4–6; 26 Tasks 4–6; 27 Task 5; 28 Task 6; 29 Task 7; 30 Tasks 7–10 (per-zoom view deferred, D2); 31 Tasks 3, 8 and 11.
- **Gates:** D1–D3; Task 6, Step 3 (only if Licensee rejects MapLibre); Task 11, Step 5; Task 12, Steps 1–5.
- **Not verifiable by an agent:** anything on the three devices (Task 12), including whether `addOnDidFinishRenderingFrameListener` behaves as the spike measured and how option (c)'s band edges look. `SeerahMap` has no automated test because MapLibre's native view does not run under Robolectric.
