# Map trial, phase 2: the map on the device

- **Author:** MohdFaragDev
- **Status:** Draft, for the owner's review. Decisions D1–D6 below wait for the owner.
- **Date:** 2026-11-16

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkboxes (`- [ ]`) for tracking.

**Goal:** the first piece of `:core:map` (MapLibre Native drawing a trial relief PMTiles file from device storage, with style C's look, fades and camera limits) and a `benchmark` build type of `:app` that holds the temporary trial screen, the measurement overlay and the scripted camera path, so the trial maps from phase 1 can be opened and measured on the three test devices.

**Where this fits.** The approved [specification](map-trial-build.md) has three phases. [Phase 1](map-trial-phase-1-plan.md) built the map tool, whose output folder `<name>-<variant>-<fp8>/` holds `relief.pmtiles`, `metadata.json` and `report.json`; the owner copies `camera-path.json` into it ([map-data/README.md](../../map-data/README.md), [tools/map/README.md](../../tools/map/README.md)). This phase reads that folder on the device. Phase 3 adds the base layer (`base.pmtiles`: land, coast, Natural Earth) and the documents.

**Architecture:**

- **Build logic:** the shared Android settings move from the application extension to `CommonExtension`; two new convention plugins, `seerah.android.library` and `seerah.android.library.compose`, follow the application ones.
- **`:core:map`** (stays in v1) is the only module that touches MapLibre (ADR 0003). Its public interface takes plain data (`MapFiles`, `MapStyleSpec`, `CameraLimits`) and gives back a `MapCamera` and `MapEvent`s. The style JSON is built by one internal function, `styleJson`, which is unit tested.
- **The `benchmark` build type** of `:app`: `initWith(release)`, R8, not debuggable, debug-signed, ID suffix `.benchmark`. Only it depends on `:core:map`.
- **Trial code** lives only in `android/app/src/benchmark/kotlin/app/seerah/trial/` and is removed after the trial. Pure logic (`FrameStats`, `TrialFiles`, `Views`, the camera-path results) is unit tested in `src/testBenchmark/`; the activity and overlay are checked on devices.
- **Release guard:** `tools/android/check_no_trial_code.py` fails CI if the release APK's manifest declares any `app.seerah.trial` component, or if it found no manifest.

**Tech stack:** Kotlin 2.4.21, AGP 9.5.1, Compose BOM 2026.09.00, MapLibre Native Android 13.7.1 (`org.maplibre.gl:android-sdk-opengl`), `org.json` from the Android framework, JUnit 4 and Robolectric 4.17 for unit tests, Python 3 (standard library only) for the release guard.

**Spec:** [docs/design/map-trial-build.md](map-trial-build.md) (Approved, 2026-11-14), phase 2, files 19–31; "The app" and the Verification Plan.

## Global Constraints

**Paths and modules:**

- **Paths:** relative to the repository root. Gradle commands run in `android/` ("GRADLE" below means `cd android && ./gradlew <tasks> --console=plain`).
- **Module:** `:core:map` at `android/core/map/`, namespace `app.seerah.map`, package `app.seerah.map`.
- **Trial package:** `app.seerah.trial`, only under `android/app/src/benchmark/` and `android/app/src/testBenchmark/`.
- **MapLibre boundary:** only `:core:map` declares `org.maplibre.gl` (already enforced by `enforceMapLibreBoundary`).

**Benchmark build type (spec, "The app"; ADR 0004 note):**

- `initWith(release)`, R8 on, not debuggable, signed with the debug key, `applicationIdSuffix = ".benchmark"` (ID `app.seerah.benchmark`), `matchingFallbacks += "release"`.
- Only `benchmarkImplementation(project(":core:map"))`; `release` gains no new dependency.

**Style values:**

- **Relief source:** `pmtiles://file://<absolute path>`, `tileSize` 256, `maxzoom` from `ReliefSource.maxZoom`.
- **Value source:** `raster-dem`, `encoding: terrarium`, drawn by a `color-relief` layer.
- **Fade** (MapLibre zooms, opacity 1 at `start`, 0 at `end`): zoom 12 and zoom 11 views 11.5–13.5 (the spike's 12.5–14.5 converted); option (a) 13.5–15.5 (a trial variable).
- **Option (c) ramp:** thresholds at stored values 42.5, 127.5 and 212.5, ink alpha 0, 0.2, 0.4 and 0.6 between them, written with `step` (fallback `interpolate linear`, stops 0.5 apart, only if step 1 check 3 fails).
- **Colours** (the spike's values, until `:core:designsystem` exists): sea `#809B94`, land `#F1E6C8`, coast `#6F8F88`, ink RGB 150, 118, 72.
- Every number in the style is a named constant or comes from the spec (docs/engineering.md: no magic values).

**Measurement rules (spec, "Measurement overlay", following the spike's `Stats.kt`):**

- A frame's gap is kept if it is 1–250 ms, inclusive.
- Rate = 1000 × 30 ÷ (sum of the last 30 kept gaps), published at most once a second. Lowest = the minimum published rate.
- Memory: `Debug.getPss()`. Open time: `SystemClock.elapsedRealtime() − Process.getStartElapsedRealtime()` at the first `FullyRendered`.
- The overlay updates only when a value is published, never per frame.
- Camera path: three runs. Per run: typical rate = median of the published readings, lowest = minimum. Over the runs: the median of the typical rates and the minimum of the lowest rates, with the first (cold) run also reported separately (spec item 11).

**Code rules:**

- Kotlin to ADR 0004 (Spotless, ktlint, Compose Rules); warnings are errors. KDoc on every public type and non-obvious function.
- **No spike code is copied.** The spike (`android/spike/`, `Stats.kt`, `MapStyle.kt`) is read for values and lessons only.
- Trial text is English (spec file 30: a developer tool, never shipped); see D3 for where it lives.

**Process:**

- **Owner gates:** an agent stops at every step marked **Owner gate**. An agent cannot run anything on the three devices (Oppo A55, Galaxy A54, MatePad 11.5, [roadmap](../roadmap.md)); every device step is an owner gate, and the agent says plainly that it was not checked on a device.
- **Commits:** Conventional Commits, scope `android` for build and app changes and `map` for `:core:map`, one per task, on this branch, no attribution lines. Nothing is pushed without the owner's approval.

## Decisions for the owner

| # | Decision | Recommendation |
| --- | --- | --- |
| D1 | **No base layer yet.** The spec's `MapFiles(relief, base)` and its `land` fill and `coast` line need `base.pmtiles`, which phase 3 builds; its layer names are not decided yet. | `MapFiles(relief: File, base: File?)`. With `base == null`, `styleJson` draws a `land`-coloured background and no land or coast layers, so relief reads over land colour. Phase 3 adds the base layers and makes `base` required. Phase 2's device gate checks the mechanics (step 1 checks 1–4 and 11, the overlay, the camera path), not the look. |
| D2 | **The per-zoom view** needs a per-zoom variant, which phase 1 deferred (its D5); its variant name is not defined. | Leave the per-zoom view out of phase 2, and add it with the per-zoom follow-up, when the variant name exists. Phase 2 has four views. |
| D3 | **Where the trial's English text lives.** docs/engineering.md says all user text lives in resources; the spec says the trial is English and never shipped. | `android/app/src/benchmark/res/values/strings.xml`, English, with a comment that the file is trial-only. No hard-coded strings in code. |
| D4 | **16 KB page alignment of the benchmark APK.** The release APK has no native code until it uses `:core:map`; the benchmark APK carries MapLibre's `.so` files. | Run `check_page_alignment.py` on the benchmark APK too, so a MapLibre alignment problem shows now and not with the first real screen. |
| D5 | **The view remembered as "last used"** is stored in the benchmark app's `SharedPreferences` (key `last_view`, value `<folder>/<view>`). | Technical; approved with the plan unless the owner objects. |
| D6 | **Approving the plan approves D1–D5 and the deviations below.** The device steps (Task 12) and the push (Task 11, Step 5) keep their gates. | |

**Deviations from the specification:**

- **MapLibre version: 13.7.1.** Checked on 2026-11-16 with `gh release list -R maplibre/maplibre-native`: 13.7.1 was released 2026-10-15 (32 days old). The newest by date, 13.5.4 (2026-11-09), is a patch to an older line and less than 14 days old; the `2026.11.x-globe` builds are pre-releases. The implementer checks again on the implementation date (Task 4, Step 1).
- **The artifact** is `org.maplibre.gl:android-sdk-opengl` (OpenGL, ADR 0002), the coordinate the spike used.
- **`MapFiles.base` is nullable** in phase 2 (D1), and the per-zoom view is left out (D2).
- **Added tests beyond spec file 31:** host tests for the release guard, and Robolectric tests for `TrialFiles`, `Views` and the camera-path results. `StyleJsonTest` runs under Robolectric, because the framework's `org.json` is a stub in plain JVM unit tests.
- **Step 1 check 10 (Licensee and MapLibre)** is settled by the first `:core:map` build in CI, not on a device (Task 4).
- **AGP 9's `CommonExtension` has no type parameters** (the generic form was removed in AGP 9). File 19 uses the plain type; if AGP 9.5.1 differs, the implementer follows its API and notes it in the commit.
- **Docs pulled into this phase:** `android/README.md` (the library plugins, `:core:map`, the `benchmark` build type) and the `docs/map-trial/step-1.md` sections for checks 1–4, 10 and 11. No `CHANGELOG.md` entry: nothing here is user-visible.
- Spec file 31 says "Modify" for `check_no_trial_code.py`; the file does not exist yet, so it is created.

## Review Focus

Five failure modes the spec implies, each pinned by a test:

1. **The guard checks nothing.** `aapt2` is missing, or the dump is empty or unparsable, and the script passes. It must exit 1 (Task 3, `test_fails_when_no_manifest_found`).
2. **Idle pauses counted as slow frames**, the spike's build-20 bug: a 300 ms pause must not lower the lowest rate (Task 8, `pauseOver250ms_isIgnored`).
3. **A bad variant folder** (no `metadata.json`, `trial` not `true`, unknown `variant`, no `camera-path.json`) crashes the trial screen. It must be listed as skipped with its reason (Task 7, `folderWithoutTrialTrue_isSkippedWithReason` and siblings).
4. **The fade in the wrong zoom convention:** the zoom-12 view must fade at MapLibre 11.5–13.5, not the spike's build-zoom 12.5–14.5 (Task 7, `zoom12View_fadesAt11_5To13_5`).
5. **The cold run hidden in the totals:** the results must show the first run separately and take the lowest rate over all three runs (Task 9, `results_reportColdRunSeparately_andMinimumOverAllRuns`).

## File structure

```text
android/
  build-logic/convention/build.gradle.kts                     register the library plugins (Task 2)
  build-logic/convention/src/main/kotlin/
    app/seerah/buildlogic/AndroidCommon.kt                    CommonExtension (Task 1)
    AndroidLibraryConventionPlugin.kt                         (Task 2)
    AndroidLibraryComposeConventionPlugin.kt                  (Task 2)
    AndroidApplicationConventionPlugin.kt                     benchmark build type (Task 3)
  gradle/libs.versions.toml                                   plugin aliases (Task 2), MapLibre (Task 4)
  settings.gradle.kts                                         include(":core:map") (Task 4)
  core/map/
    build.gradle.kts, README.md                               (Tasks 4, 6)
    src/main/kotlin/app/seerah/map/
      MapFiles.kt, MapStyleSpec.kt, CameraLimits.kt           (Task 4)
      MapCamera.kt, MapEvent.kt                               (Task 4)
      StyleJson.kt                                            internal (Task 5)
      SeerahMap.kt                                            (Task 6)
    src/test/kotlin/app/seerah/map/StyleJsonTest.kt           (Task 5)
  app/build.gradle.kts                                        benchmarkImplementation (Task 7)
  app/src/benchmark/
    AndroidManifest.xml, res/values/strings.xml               (Task 10)
    kotlin/app/seerah/trial/
      TrialFiles.kt, Views.kt                                 (Task 7)
      FrameStats.kt                                           (Task 8)
      CameraPath.kt                                           (Task 9)
      Overlay.kt, TrialActivity.kt                            (Task 10)
  app/src/testBenchmark/kotlin/app/seerah/trial/
    TrialFilesTest.kt, ViewsTest.kt, FrameStatsTest.kt, CameraPathTest.kt
  README.md                                                   (Tasks 2, 3, 6, 11)
tools/android/
  check_no_trial_code.py                                      (Task 3)
  tests/test_check_no_trial_code.py, tests/fixtures/*.txt     (Task 3)
.github/workflows/android.yml                                 (Task 11)
docs/map-trial/step-1.md                                      (Tasks 4, 12)
```

---

## Tasks

### Task 1: Shared build logic on `CommonExtension` (spec file 19)

**Files:**

- Modify: `android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt`

**Interfaces:**

- Produces: `internal fun Project.configureAndroid(android: CommonExtension)` (was `ApplicationExtension`). `configureKotlin()` is unchanged.

Build logic has no unit tests in this repository; the build itself is the test.

- [ ] **Step 1: Baseline.** GRADLE `:app:assembleDebug :app:testDebugUnitTest`. Expected: pass. Save `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` output to the scratchpad for Task 3's comparison.
- [ ] **Step 2: Change the parameter type** to `com.android.build.api.dsl.CommonExtension`. `defaultConfig.minSdk`, `compileOptions`, `lint` and `testOptions` are all on `CommonExtension`; no body change is expected. `AndroidApplicationConventionPlugin` keeps calling `configureAndroid(this)` unchanged.
- [ ] **Step 3: Run** GRADLE `spotlessCheck :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`. Expected: pass, with no change in behaviour.
- [ ] **Step 4: Commit** `refactor(android): share the Android settings through CommonExtension`.

### Task 2: Library convention plugins (spec files 20, 21, 23 in part)

**Files:**

- Create: `android/build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt`, `AndroidLibraryComposeConventionPlugin.kt`
- Modify: `android/build-logic/convention/build.gradle.kts`, `android/gradle/libs.versions.toml`, `android/README.md`

**Interfaces:**

- Produces:
  - plugin `seerah.android.library` (`AndroidLibraryConventionPlugin`): applies `com.android.library` and `app.cash.licensee`; calls `configureAndroid`, `configureKotlin`, `configureLicences` and `enforceMapLibreBoundary`. Not `configureModuleGraphRules`: `assertModuleGraph` runs from `:app`, which sees the whole graph.
  - plugin `seerah.android.library.compose` (`AndroidLibraryComposeConventionPlugin`): applies `org.jetbrains.kotlin.plugin.compose`, sets `LibraryExtension.buildFeatures.compose = true`, adds the Compose BOM to `implementation` and `testImplementation`. It adds no tooling: the library has no previews yet.
  - catalog aliases `seerah-android-library` and `seerah-android-library-compose`; plugin alias `android-library = { id = "com.android.library", version.ref = "androidGradlePlugin" }`, declared `apply false` in `android/build.gradle.kts`.

- [ ] **Step 1: Write the plugins and register them** (`register("androidLibrary")`, `register("androidLibraryCompose")`), each class with a KDoc line as the application plugins have.
- [ ] **Step 2: Run** `cd android && ./gradlew -p build-logic :convention:validatePlugins --console=plain` and GRADLE `help`. Expected: pass. The plugins are first applied in Task 4.
- [ ] **Step 3: Docs.** In `android/README.md`, add both plugins to the convention table and remove "Library … conventions are added with the first module that needs them" for library (keep feature and JVM).
- [ ] **Step 4: Commit** `build(android): add the library convention plugins`.

### Task 3: The `benchmark` build type and the release guard (spec files 22, 31 in part)

**Files:**

- Modify: `android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`, `android/README.md`
- Create: `tools/android/check_no_trial_code.py`, `tools/android/tests/test_check_no_trial_code.py`, `tools/android/tests/fixtures/release_manifest.txt`, `tools/android/tests/fixtures/trial_manifest.txt`

**Interfaces:**

- Produces:
  - build type `benchmark`, tasks `:app:assembleBenchmark` and `:app:testBenchmarkUnitTest`;
  - `check_no_trial_code.py`:

    ```python
    TRIAL_PREFIX = "app.seerah.trial"


    def find_aapt2(sdk_root: Path) -> Path: ...  # newest build-tools version, compared numerically
    def component_names(
        manifest_dump: str,
    ) -> list[str]: ...  # android:name of activity, activity-alias, service, receiver, provider
    def main(argv: list[str]) -> int: ...  # usage: check_no_trial_code.py APK; 0 pass, 1 fail
    ```

    The SDK root is `$ANDROID_HOME`, else `$ANDROID_SDK_ROOT`. The module docstring cites ADR 0007 decision 10.

- [ ] **Step 1: Make the fixtures from real output.** Build `:app:assembleRelease`, run `aapt2 dump xmltree --file AndroidManifest.xml <apk>` and save it as `release_manifest.txt`. Make `trial_manifest.txt` by copying it and adding one `E: activity` element whose `android:name` is `app.seerah.trial.TrialActivity`, in the same format.
- [ ] **Step 2: Write the failing tests** (`unittest`, standard library only):

  ```python
  def test_release_manifest_passes(self):          # component_names(release) == ["app.seerah.MainActivity", ...]; no TRIAL_PREFIX
  def test_trial_component_fails(self):            # main() on a fake aapt2 printing trial_manifest.txt -> 1, message names the component
  def test_fails_when_no_manifest_found(self):     # fake aapt2 printing "" -> 1; missing build-tools dir -> 1
  def test_newest_build_tools_chosen_numerically(self):  # dirs "9.0.0", "37.0.0", "36.1.0" -> ".../37.0.0/aapt2"
  ```

  The fake `aapt2` is a small executable script written into a temporary `build-tools/<version>/` folder.
- [ ] **Step 3: Run** `python3 -m unittest discover -s tools/android/tests`. Expected: FAIL (no module).
- [ ] **Step 4: Implement** `check_no_trial_code.py`, then add the build type in `AndroidApplicationConventionPlugin`:

  ```kotlin
  /** Release-like build for measuring (ADR 0004 note, owner 2026-11-14); never published. */
  buildTypes.create("benchmark") {
      initWith(getByName("release"))
      isDebuggable = false
      signingConfig = signingConfigs.getByName("debug")
      applicationIdSuffix = ".benchmark"
      matchingFallbacks += "release"
  }
  ```

  The release signing line in `app/build.gradle.kts` stays where it is; `benchmark` sets its own signing in the plugin so it never depends on the release block's order.
- [ ] **Step 5: Run** the unit tests (expected: 4 passed), `ruff check tools/android` through `pre-commit run --all-files`, and GRADLE `:app:assembleRelease :app:assembleBenchmark`, then `python3 tools/android/check_no_trial_code.py android/app/build/outputs/apk/release/app-release.apk` (expected: exit 0). Compare `:app:dependencies --configuration releaseRuntimeClasspath` with Task 1's: identical.
- [ ] **Step 6: Docs.** `android/README.md`: the build types (`debug`, `release`, `benchmark`, what each is for, and that `benchmark` is never published).
- [ ] **Step 7: Commit** `build(android): add the benchmark build type and the release guard`.

### Task 4: `:core:map` module and public types (spec files 23, 24, 25, 26 in part)

**Files:**

- Modify: `android/gradle/libs.versions.toml`, `android/settings.gradle.kts`, `docs/map-trial/step-1.md`
- Create: `android/core/map/build.gradle.kts`, and in `android/core/map/src/main/kotlin/app/seerah/map/`: `MapFiles.kt`, `MapStyleSpec.kt`, `CameraLimits.kt`, `MapCamera.kt`, `MapEvent.kt`

**Interfaces:**

- Produces (each with KDoc):

  ```kotlin
  data class MapFiles(val relief: File, val base: File?)                 // D1
  data class MapStyleSpec(val relief: ReliefSource, val reliefFade: ZoomRange, val colours: MapColours)
  sealed interface ReliefSource {
      val maxZoom: Int
      data class Painted(override val maxZoom: Int) : ReliefSource
      data class Value(override val maxZoom: Int, val ramp: List<RampStep>) : ReliefSource
  }
  data class ZoomRange(val start: Double, val end: Double)                // MapLibre zooms
  data class MapColours(val sea: Int, val land: Int, val coast: Int, val ink: Int) {  // ARGB
      companion object { val Spike: MapColours }                          // the Global Constraints colours, ink opaque
  }
  data class RampStep(val value: Double, val colour: Int)
  data class CameraLimits(val west: Double, val south: Double, val east: Double, val north: Double,
                          val minZoom: Double, val maxZoom: Double)
  interface MapCamera { suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long) }
  sealed interface MapEvent {
      data object FullyRendered : MapEvent
      data class FrameRendered(val atMillis: Long) : MapEvent             // SystemClock.elapsedRealtime()
  }
  ```

- [ ] **Step 1: Pin MapLibre.** Run `gh release list -R maplibre/maplibre-native -L 40` and take the newest non-pre-release `android-v13.x` at least 14 days old, highest version number among them (13.7.1 on 2026-11-16). Add `maplibre = "<version>"` and `maplibre-android-opengl = { group = "org.maplibre.gl", name = "android-sdk-opengl", version.ref = "maplibre" }`.
- [ ] **Step 2: Module build file.** Plugins `seerah.android.library` and `seerah.android.library.compose`; namespace `app.seerah.map`. Dependencies: `implementation` MapLibre and Compose `foundation`; `api` Compose `runtime` and `ui` (for `@Composable` and `Modifier`); `testImplementation` `junit4`, `kotlin-test`, `robolectric`, `androidx-test-ext-junit`, as in `:app`. `include(":core:map")` in `settings.gradle.kts`.
- [ ] **Step 3: Write the types** above, no logic.
- [ ] **Step 4: Run** GRADLE `:core:map:assembleRelease :core:map:licensee :core:map:lintRelease buildHealth`. Expected: pass. `buildHealth` decides the final `api`/`implementation` split; follow its advice. If Licensee rejects a MapLibre dependency (step 1 check 10), stop: **Owner gate**: a reviewed exception in `DependencyRules.kt` needs the owner's approval.
- [ ] **Step 5: Record check 10** in `docs/map-trial/step-1.md`: date, MapLibre version, Licensee's result.
- [ ] **Step 6: Commit** `feat(map): add the core map module and its public types`.

### Task 5: The style JSON (spec files 26 `StyleJson.kt`, 27)

**Files:**

- Create: `android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt`, `android/core/map/src/test/kotlin/app/seerah/map/StyleJsonTest.kt`

**Interfaces:**

- Consumes: Task 4's types.
- Produces: `internal fun styleJson(files: MapFiles, spec: MapStyleSpec): String`, built with `org.json.JSONObject`/`JSONArray`. Layer ids: `sea` (background), `land` (background, only when `base == null`, D1), `relief`. Colours are written as `rgba(r, g, b, a)` from ARGB.

- [ ] **Step 1: Write the failing tests** (`@RunWith(AndroidJUnit4::class)`), with `files = MapFiles(File("/data/t/relief.pmtiles"), base = null)`:

  ```kotlin
  @Test fun paintedStyle_parsesAndUsesARasterLayer()       // JSONObject(json); layer "relief" type == "raster"
  @Test fun paintedStyle_fadesOverReliefFade()             // raster-opacity == ["interpolate",["linear"],["zoom"],11.5,1,13.5,0]
  @Test fun reliefSource_usesPmtilesFileUrl_tileSize256_andMaxZoom()  // url == "pmtiles://file:///data/t/relief.pmtiles"; tileSize == 256; maxzoom == 12 (then 11)
  @Test fun valueStyle_usesTerrariumRasterDem_andColorRelief()        // source type "raster-dem", encoding "terrarium"; layer type "color-relief"
  @Test fun valueStyle_hasRampThresholds()                 // color-relief-color == ["step",["elevation"],c0,42.5,c1,127.5,c2,212.5,c3]
  @Test fun noBase_drawsLandBackground_andNoBaseLayers()   // layers == ["sea","land","relief"]; land colour rgba(241, 230, 200, 1)
  ```

  `c0`–`c3` are the ink at alpha 0, 0.2, 0.4, 0.6. Each test builds its spec from Global Constraints values written in the test, not from the trial code.
- [ ] **Step 2: Run** GRADLE `:core:map:testDebugUnitTest`. Expected: FAIL (unresolved `styleJson`).
- [ ] **Step 3: Implement** `styleJson` with `version: 8`, the sources, and the layers in the order above. Named constants: `TILE_SIZE = 256`, `OPAQUE = 1.0`, `TRANSPARENT = 0.0`, `STYLE_VERSION = 8`.
- [ ] **Step 4: Run** the same tests. Expected: 6 passed.
- [ ] **Step 5: Commit** `feat(map): build the map style from plain data`.

### Task 6: `SeerahMap` (spec files 26 `SeerahMap.kt`, 28)

**Files:**

- Create: `android/core/map/src/main/kotlin/app/seerah/map/SeerahMap.kt`, `android/core/map/README.md`
- Modify: `android/README.md`

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

  Behaviour (spec, "The app"): `MapLibre.getInstance(context)` once; a `MapView` in `AndroidView`, its lifecycle forwarded from `LocalLifecycleOwner`; `setStyle(Style.Builder().fromJson(styleJson(files, style)))`; `setLatLngBoundsForCameraTarget` with `limits` and `setMinZoomPreference`/`setMaxZoomPreference`; `onCamera` called once the style has loaded, with a `MapCamera` whose `animateTo` suspends until MapLibre's `CancelableCallback` finishes or is cancelled (then it throws `CancellationException`); `addOnDidFinishRenderingMapListener { fully -> if (fully) FullyRendered }` and `addOnDidFinishRenderingFrameListener` → `FrameRendered(SystemClock.elapsedRealtime())`. A change of `files` or `style` reloads the style; the `MapView` itself is created once.

MapLibre's native view cannot run under Robolectric, so this task has no unit test. It is checked by compiling, `buildHealth`, and on devices in Task 12.

- [ ] **Step 1: Implement** `SeerahMap` and a private `MapLibreCamera : MapCamera`. Add `androidx-lifecycle-runtime-compose` to the catalog only if `LocalLifecycleOwner` needs it at this Compose BOM (check first; `buildHealth` confirms).
- [ ] **Step 2: Run** GRADLE `spotlessCheck :core:map:check buildHealth`. Expected: pass.
- [ ] **Step 3: Docs.** `android/core/map/README.md`: the module's job (the only MapLibre user), its public types, how the style is built, that `base` is optional until phase 3 (D1), and that it holds no trial code. `android/README.md`: list `:core:map` under the modules.
- [ ] **Step 4: Commit** `feat(map): host MapLibre behind SeerahMap`.

### Task 7: Trial files and views (spec files 29, 30 `TrialFiles.kt`, `Views.kt`)

**Files:**

- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/benchmark/kotlin/app/seerah/trial/TrialFiles.kt`, `Views.kt`; `android/app/src/testBenchmark/kotlin/app/seerah/trial/TrialFilesTest.kt`, `ViewsTest.kt`

**Interfaces:**

- Consumes: `CameraLimits`, `MapStyleSpec`, `ReliefSource`, `RampStep`, `ZoomRange`, `MapColours.Spike`.
- Produces:

  ```kotlin
  enum class Variant { PAINTED, VALUE }                              // metadata.json "variant": "painted" | "value"
  data class CameraStop(val lat: Double, val lon: Double, val zoom: Double, val durationMs: Long)
  data class CameraPathSpec(val stops: List<CameraStop>, val limits: CameraLimits)
  data class TrialVariant(val folder: File, val variant: Variant, val fingerprint8: String,
                          val maxZoom: Int, val cameraPath: CameraPathSpec) { val relief: File }
  sealed interface VariantLoad {
      data class Loaded(val variant: TrialVariant) : VariantLoad
      data class Skipped(val folder: File, val reason: String) : VariantLoad
  }
  fun loadVariants(root: File): List<VariantLoad>                    // one entry per sub-folder, sorted by name
  enum class TrialView { ZOOM_12, ZOOM_11, OPTION_A, OPTION_C }      // D2: no per-zoom view
  fun viewsFor(variant: Variant): List<TrialView>                    // PAINTED -> 12, 11, A; VALUE -> C
  fun TrialView.styleSpec(): MapStyleSpec
  ```

  `camera-path.json` keys are those of `map-data/example/camera-path.json`: `stops[].lat`, `lon`, `zoom`, `duration_ms`; `limits.west`, `south`, `east`, `north`, `min_zoom`, `max_zoom`.

- [ ] **Step 1: Add** `"benchmarkImplementation"(project(":core:map"))` and the `testBenchmarkImplementation` entries needed (`junit4`, `kotlin-test`, `robolectric`, `androidx-test-ext-junit`, the ones `testImplementation` already gives may suffice: check with `buildHealth`).
- [ ] **Step 2: Write the failing tests** (Robolectric, temporary folders):

  ```kotlin
  // TrialFilesTest
  @Test fun validFolder_isLoaded()     // metadata {"trial":true,"variant":"painted","fingerprint":"0123456789abcdef","maxzoom":12} + the example camera-path -> Loaded, fingerprint8 "01234567", maxZoom 12, 1 stop, limits.minZoom 4.0
  @Test fun folderWithoutTrialTrue_isSkippedWithReason()   // "trial": false -> Skipped, reason mentions "trial"
  @Test fun folderWithoutCameraPath_isSkippedWithReason()
  @Test fun folderWithUnknownVariant_isSkippedWithReason() // "variant": "per-zoom" -> Skipped
  @Test fun folderWithoutRelief_isSkippedWithReason()
  // ViewsTest
  @Test fun zoom12View_fadesAt11_5To13_5()        // ZOOM_12.styleSpec(): Painted(12), ZoomRange(11.5, 13.5)
  @Test fun zoom11View_isPainted11_sameFade()     // Painted(11), ZoomRange(11.5, 13.5)
  @Test fun optionA_fadesAt13_5To15_5()           // Painted(12), ZoomRange(13.5, 15.5)
  @Test fun optionC_isValue12_withFourRampSteps() // values 0.0, 42.5, 127.5, 212.5; alphas 0, 0.2, 0.4, 0.6 of ink 150,118,72
  @Test fun viewsFor_matchesVariant()
  ```

- [ ] **Step 3: Run** GRADLE `:app:testBenchmarkUnitTest`. Expected: FAIL (unresolved references).
- [ ] **Step 4: Implement.** `loadVariants` never throws for a bad folder; a malformed JSON becomes `Skipped` with the parser's message. The fade and ramp values are named constants in `Views.kt` (the option (a) fade carries a KDoc line saying it is a trial variable).
- [ ] **Step 5: Run** the same task. Expected: 10 passed. Also GRADLE `:app:assembleRelease` and `:app:dependencies --configuration releaseRuntimeClasspath`: no `:core:map`, no `org.maplibre`.
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

  Constants: `MIN_GAP_MS = 1L`, `MAX_GAP_MS = 250L`, `WINDOW = 30`, `PUBLISH_INTERVAL_MS = 1_000L`. A reading is published when 30 kept gaps exist and at least 1,000 ms passed since the last one (the first one as soon as 30 gaps exist).

- [ ] **Step 1: Write the failing tests:**

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

**Interfaces:**

- Consumes: `MapCamera`, `CameraPathSpec`, `FrameReading`.
- Produces:

  ```kotlin
  const val RUNS = 3
  data class RunResult(val readings: List<Double>) { val typicalFps: Double; val lowestFps: Double }  // median, minimum
  data class PathResults(val runs: List<RunResult>) {
      val cold: RunResult            // runs.first()
      val typicalFps: Double         // median of the runs' typical rates
      val lowestFps: Double          // minimum over all runs
  }
  suspend fun runCameraPath(camera: MapCamera, path: CameraPathSpec, onRunStart: (Int) -> Unit, readingsOfRun: () -> List<Double>): PathResults
  fun PathResults.asText(header: TrialHeader): String   // TrialHeader(device, view, fingerprint8, openTimeMs, pssKb)
  ```

  `runCameraPath` animates through every stop in order, `RUNS` times, calling `onRunStart(n)` before each run so the caller resets `FrameStats`.

- [ ] **Step 1: Write the failing tests** (`kotlinx-coroutines-test` only if it is already on the test classpath through Compose; otherwise `runBlocking`, no new dependency):

  ```kotlin
  @Test fun runs_visitEveryStopThreeTimes()     // fake MapCamera records calls -> 3 × stops, in order, with each stop's durationMs
  @Test fun results_reportColdRunSeparately_andMinimumOverAllRuns()
      // runs [50,60,58], [59,60,60], [57,60,61] -> cold.typicalFps 58.0, typicalFps 60.0, lowestFps 50.0; text has a "cold run" line
  @Test fun text_includesDeviceViewFingerprintAndOpenTime()
  ```

- [ ] **Step 2: Run** GRADLE `:app:testBenchmarkUnitTest --tests 'app.seerah.trial.CameraPathTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Labels in the text come from `strings.xml` (D3) through a small formatter parameter, so the pure function stays testable without resources.
- [ ] **Step 4: Run.** Expected: 3 passed.
- [ ] **Step 5: Commit** `feat(android): run the trial camera path and report its results`.

### Task 10: Trial screen and overlay (spec file 30 `AndroidManifest.xml`, `TrialActivity.kt`, `Overlay.kt`)

**Files:**

- Create: `android/app/src/benchmark/AndroidManifest.xml`, `android/app/src/benchmark/res/values/strings.xml`, `android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt`, `Overlay.kt`

**Interfaces:**

- Consumes: everything above.
- Produces:
  - `app.seerah.trial.TrialActivity` (a `ComponentActivity`), exported, with its own `MAIN`/`LAUNCHER` filter and label `@string/trial_launcher` = "Map trial". Intent extra `EXTRA_VIEW = "app.seerah.trial.VIEW"` with value `<folder>/<view>`.
  - `@Composable fun Overlay(state: OverlayState, modifier: Modifier = Modifier)`, where `OverlayState(device, view, fingerprint8, fps, lowestFps, pssMb, openTimeMs, runs)` changes only on a published reading.

  Behaviour: lists `getExternalFilesDir(null)/trial/` with `loadVariants` (skipped folders shown with their reason); opens the intent's view, else the last used (D5), else a picker; hosts `SeerahMap` with the variant's `MapFiles(relief, null)`, the view's `styleSpec()` and `cameraPath.limits`; feeds `FrameRendered` to `FrameStats`; on the first `FullyRendered` records the open time; reads `Debug.getPss()` on each published reading; a "Run path" button runs `runCameraPath`; a "Copy results" button copies `asText` to the clipboard. Device name is `Build.MANUFACTURER + " " + Build.MODEL`.

- [ ] **Step 1: Write** the manifest, strings and the two files. Files load off the main thread (`Dispatchers.IO`).
- [ ] **Step 2: Run** GRADLE `spotlessCheck :app:lintBenchmark :app:assembleBenchmark :app:testBenchmarkUnitTest`. Expected: pass. Then `aapt2 dump xmltree --file AndroidManifest.xml` on the benchmark APK lists `app.seerah.trial.TrialActivity`, and `python3 tools/android/check_no_trial_code.py` on the **benchmark** APK exits 1 (the guard sees it) while on the release APK it exits 0.
- [ ] **Step 3: Commit** `feat(android): add the map trial screen and overlay`.

### Task 11: CI and docs (spec file 31 workflow)

**Files:**

- Modify: `.github/workflows/android.yml`, `android/README.md`

- [ ] **Step 1: Workflow.**
  - "Check and build": `./gradlew spotlessCheck check buildHealth assembleRelease assembleBenchmark --console=plain`.
  - A step "No trial code in release": `python3 ../tools/android/check_no_trial_code.py app/build/outputs/apk/release/app-release.apk`.
  - A step "Release guard tests": `python3 -m unittest discover -s ../tools/android/tests`.
  - "16 KB page alignment" also gets `app/build/outputs/apk/benchmark/app-benchmark.apk` (D4).
  - Upload `android/app/build/outputs/apk/benchmark/*.apk` as artifact `seerah-app-benchmark`, with the same pinned `upload-artifact` SHA and settings as `seerah-app`.
  - No path-filter change: `android/**` and `tools/android/**` are already covered.
- [ ] **Step 2: Confirm `check` runs the benchmark unit tests:** GRADLE `check --dry-run | grep testBenchmarkUnitTest`. If it is absent, add `:app:testBenchmarkUnitTest` to the workflow command explicitly and note it in `android/README.md`.
- [ ] **Step 3: Run** the full CI command locally, then `pre-commit run --all-files` (actionlint and zizmor cover the workflow) and `python3 tools/validate_content.py`. Expected: pass.
- [ ] **Step 4: Docs.** `android/README.md`: how to get the benchmark APK (artifact `seerah-app-benchmark`), install it beside the normal app, `adb push <folder> /sdcard/Android/data/app.seerah.benchmark/files/trial/`, and open "Map trial". Check every touched doc's links.
- [ ] **Step 5: Commit** `ci(android): build the benchmark APK and guard release from trial code`. **Owner gate:** the owner approves the push and runs the workflow or opens the pull request (AGENTS.md).

### Task 12: On the devices (spec Verification Plan, "On devices"; step 1 checks 1–4 and 11)

An agent cannot do any step of this task. It prepares the checklist in `docs/map-trial/step-1.md` and records the owner's results.

- [ ] **Step 1: Owner gate: install.** The benchmark APK from `seerah-app-benchmark` installs beside the normal app on the Oppo A55, Galaxy A54 and MatePad 11.5, and shows the "Map trial" launcher entry.
- [ ] **Step 2: Owner gate: check 1.** `adb push` of the example output (or a trial window) into `/sdcard/Android/data/app.seerah.benchmark/files/trial/` works on all three. If it fails on any device, the in-app folder import fallback gets its own task before step 2 of the trial.
- [ ] **Step 3: Owner gate: checks 2, 3 and 4.** Zoom 11 view stops drawing new relief detail past its `maxzoom` (fallback: `pmtiles extract --maxzoom=11`); option (c) draws three bands with `step` (fallback: `interpolate linear`, then Task 5's test changes with it); the overlay's rate moves while panning and stays still at rest.
- [ ] **Step 4: Owner gate: check 11.** Each device's screen width in dp (from the overlay or `adb shell wm size` and `wm density`).
- [ ] **Step 5: Owner gate: the overlay and camera path.** The overlay shows device, view, fingerprint, rate, lowest, memory and open time; "Run path" runs three times; "Copy results" gives the text. Phone and tablet, in landscape on the tablet.
- [ ] **Step 6: Record** every result with its date in `docs/map-trial/step-1.md`, and commit `docs(map): record the phase 2 step 1 checks`.

## Self-review

- **Spec coverage, phase 2, files 19–31:**
  - 19 (`AndroidCommon.kt`): Task 1.
  - 20 and 21 (library plugins, registration): Task 2.
  - 22 (`benchmark` build type): Task 3.
  - 23 (catalog): Tasks 2 (plugin aliases) and 4 (MapLibre).
  - 24 (`settings.gradle.kts`) and 25 (module build file): Task 4.
  - 26 (public types, `StyleJson.kt`, `SeerahMap.kt`): Tasks 4, 5 and 6.
  - 27 (`StyleJsonTest`): Task 5.
  - 28 (`:core:map` README): Task 6.
  - 29 (`benchmarkImplementation`): Task 7.
  - 30 (trial code): Tasks 7–10; the per-zoom view is deferred (D2).
  - 31 (`FrameStatsTest`, workflow, release guard): Tasks 8, 11 and 3.
- **Verification Plan:** build and tests (Task 11, Step 3), lint (`pre-commit`, `validate_content.py`), the release guard failing when it checks nothing (Task 3), on devices (Task 12).
- **Owner gates:** D1–D6; Task 4, Step 4 (only if Licensee rejects MapLibre); Task 11, Step 5; Task 12, Steps 1–5.
- **Not verifiable by an agent:** anything on the three devices (Task 12), and whether the real `addOnDidFinishRenderingFrameListener` behaves as the spike measured. `SeerahMap` has no automated test because MapLibre's native view does not run under Robolectric.
- **Type consistency:** `MapFiles`, `MapStyleSpec`, `ReliefSource`, `ZoomRange`, `MapColours`, `RampStep`, `CameraLimits`, `MapCamera` and `MapEvent` are defined once in Task 4; `Variant`, `CameraStop`, `CameraPathSpec`, `TrialVariant`, `VariantLoad` and `TrialView` in Task 7; `FrameReading` and `FrameStats` in Task 8; `RunResult`, `PathResults` and `TrialHeader` in Task 9. Later tasks use the same names.
- **Review Focus:** each of the five has a named test in its owning task (Tasks 3, 8, 7, 7, 9).
