# Goldfish Review Report: Implementation Readiness (Stage 9)

**Target:** `docs/design/map-trial-phase-2-plan.md` (lines 139-526; lines 1-138 used as context)

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-implementation-readiness` | completed |
| `egm-codebase-details` | completed |
| `egm-style` | completed |

**Verdict:** not ready to implement. 2 Blockers, 16 Majors, 17 Nits. Most paths, plugin IDs, catalog aliases, classes and spike values match the repo (see "Verified" at the end).

## Blockers

### `testBenchmarkUnitTest` may not exist - Blocker

* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** lines 187, 363-381, 416, 455, 476, 492
* **Discrepancy Description:** In AGP 9, `android.onlyEnableUnitTestForTheTestedBuildType` appears to default to true, so host unit tests exist only for debug. `android/gradle.properties` does not override this. If so, `src/testBenchmark/` never compiles and `:app:testBenchmarkUnitTest` does not exist. Tasks 7-10 and their "N passed" checks would break, and the Task 11 Step 2 fallback would not help.
* **Rationale & Recommendation:** Check this against AGP 9.5.1, then choose a fix and state it in the plan: set the property to false in `android/gradle.properties` and add that file to the file list, set `testBuildType`, or move the pure logic to where `testDebugUnitTest` can see it.

### Task 4 Step 4 cannot pass `buildHealth` - Blocker

* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** lines 267-269
* **Discrepancy Description:** Task 4 adds MapLibre, Compose foundation and four test dependencies, but there is no code using them until Tasks 5 and 6. `buildHealth` uses `severity("fail")` (android/build.gradle.kts lines 29-35), so it will flag them as unused and fail. "Follow its advice" would remove MapLibre, and then the Licensee check 10 recorded in Step 5 would test nothing.
* **Rationale & Recommendation:** Add each dependency in the task that first uses it, and move check 10 to Task 6. Or run `buildHealth` only from Task 6 onward.

## Majors

### The `benchmark` build type snippet will not compile - Major

* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`
* **Affected Scope:** lines 219-225
* **Discrepancy Description:** Inside `buildTypes.create("benchmark") { }`, `getByName("release")` does not resolve. Also, `initWith` copies values when it is called, so the block has to come after the release configuration in `AndroidApplicationConventionPlugin.kt`.
* **Rationale & Recommendation:** Use `initWith(buildTypes.getByName("release"))` and state where the block goes. `isDebuggable = false` repeats the default and can be dropped.

### `android/build.gradle.kts` is missing from the file lists - Major

* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`
* **Affected Scope:** lines 162-163, 170; File structure lines 101-135
* **Discrepancy Description:** Line 170 requires `android-library ... apply false` in that file, but neither Task 2's Files nor the File structure lists it.
* **Rationale & Recommendation:** Add it to both lists.

### Lifecycle-compose dependency is left open - Major

* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`, `egm-style`
* **Affected Scope:** line 328
* **Discrepancy Description:** The plan says to add the dependency "only if `LocalLifecycleOwner` needs it (check first)". The `ui` copy of `LocalLifecycleOwner` is deprecated, and warnings are errors, so the dependency is needed.
* **Rationale & Recommendation:** Name the alias `androidx-lifecycle-runtime-compose` (version ref `androidxLifecycle`), import `androidx.lifecycle.compose.LocalLifecycleOwner`, and add `libs.versions.toml` to Task 6's Files.

### Coroutines dependency is never declared - Major

* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Tasks 6, 9 and 10
* **Discrepancy Description:** `suspendCancellableCoroutine`, the suspend `runCameraPath` and `Dispatchers.IO` all need coroutines. The dependency analysis plugin reports a used transitive dependency as an issue, and no task adds a catalog entry or dependency for coroutines.
* **Rationale & Recommendation:** Add a `kotlinx-coroutines-android` (or core) alias, with `implementation` in `:core:map` and `benchmarkImplementation` in `:app`, and add it to the right task's Files.

### Test dependencies are left to `buildHealth` - Major

* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`, `egm-style`
* **Affected Scope:** lines 363, 446
* **Discrepancy Description:** "May suffice: check with `buildHealth`" and "`kotlinx-coroutines-test` only if already on the classpath" leave the dependency choice to the implementer.
* **Rationale & Recommendation:** `testImplementation` already applies to `testBenchmark`, so state "none added" or name each dependency.

### The `CommonExtension` claim has not been checked - Major

* **Reviewer Lens:** `egm-codebase-details` (the `egm-implementation-readiness` reviewer judged it sound)
* **Affected Scope:** lines 85, 154
* **Discrepancy Description:** The plan says "if AGP 9.5.1 differs, the implementer follows its API", which hands the decision to the implementer. In AGP 9, `defaultConfig` and `testOptions.unitTests.all` on the non-generic `CommonExtension` may differ from what the plan assumes.
* **Rationale & Recommendation:** Check against the 9.5.1 API before approval and remove the hedge.

### The library convention plugins are underspecified - Major

* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** lines 168-169
* **Discrepancy Description:** The plan does not give the exact `pluginManager.apply` IDs or the `LibraryExtension` import and body. It does not say whether lint or test `targetSdk` is set, or whether the module-graph plugin is left out.
* **Rationale & Recommendation:** Write them out the way `AndroidApplicationConventionPlugin.kt` does.

### `asText` signature conflicts with its step - Major

* **Reviewer Lens:** all three
* **Affected Scope:** lines 441, 456
* **Discrepancy Description:** Step 3 adds "a small formatter parameter" for the strings.xml labels, but the signature has none. The field types of `TrialHeader` and the output layout are not given, yet a test asserts on that output. Also, strings.xml is only created later, in Task 10.
* **Rationale & Recommendation:** Give the full signature (for example `labels: ResultLabels`), the line template and the string resource names.

### Python tests cannot import the script - Major

* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`
* **Affected Scope:** lines 204-214, 491
* **Discrepancy Description:** `unittest discover -s tools/android/tests` does not put `tools/android` on `sys.path`, and there is no `__init__.py`. The `main` exit code when aapt2 fails is not stated. The expected manifest list contains an ellipsis.
* **Rationale & Recommendation:** State how the import works (a `sys.path` insert, or `-t` with an `__init__.py`). Say that `main` exits 1 when aapt2 fails, and write out the full expected list.

### The colour string format is undefined - Major

* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** lines 282, 291-295
* **Discrepancy Description:** The test expects `rgba(241, 230, 200, 1)`, but formatting a Kotlin Double gives `1.0`. The alpha formatting for c0-c3 (0, 0.2, 0.4, 0.6) is not specified.
* **Rationale & Recommendation:** Specify the rounding and decimal places, and give the full expected strings for c0-c3.

### Option C's fade is undefined - Major

* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Tasks 5 and 7 (line 377)
* **Discrepancy Description:** `reliefFade` is required, but there is no `ZoomRange` for the value layer. It is not said whether `color-relief-opacity` is written, and no test covers it.
* **Rationale & Recommendation:** Specify the range and the property, and add a matching assertion.

### Median and empty-run edge cases are not decided - Major

* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** line 434
* **Discrepancy Description:** The plan does not say how the median of an even number of readings is computed. It also does not say what happens when a run publishes no readings.
* **Rationale & Recommendation:** Decide both and add tests for them.

### `TrialActivity` state handling is left to the implementer - Major

* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** lines 470-475
* **Discrepancy Description:** Several points are not decided:
  * how readings are collected per run, and when `FrameStats` is reset;
  * the type of `OverlayState.runs`;
  * whether open time is recorded once or on every view switch;
  * the form of the picker;
  * what happens when the `trial/` directory is missing.
* **Rationale & Recommendation:** Add one sentence of decision for each.

### Task 12's opening claim is wrong - Major

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** lines 499, 506
* **Discrepancy Description:** "An agent cannot do any step" is contradicted by the next sentence, and by Step 6, which an agent can do.
* **Rationale & Recommendation:** Say that an agent cannot do Steps 1-5, prepares the checklist, and does Step 6.

### "Step" means two different things - Major

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** lines 269, 270, 502, 503, 523
* **Discrepancy Description:** The plan uses "Step" for its own task steps and also for the stages of the trial.
* **Rationale & Recommendation:** Use a separate term for the trial's stages, such as "trial stage 1, check 10".

### The "Behaviour" paragraphs are run-on sentences - Major

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** lines 324, 473
* **Discrepancy Description:** Each paragraph chains eight or nine clauses with semicolons, so requirements a reviewer has to check get lost.
* **Rationale & Recommendation:** Turn each into a bulleted list with one behaviour per bullet.

## Nits

1. **Lines 441, 471:** memory units disagree (`pssMb` and `pssKb`). `Debug.getPss()` returns KB. Pick one unit and state the conversion.
2. **Lines 356-358:** `TrialVariant.maxZoom` is loaded but never used. Say what happens when a variant's maxzoom is not 12, and give the path of `relief`.
3. **Line 404:** say that a frame gap outside 1-250 ms still updates the last frame time.
4. **Lines 288, 291:** `JSONArray` has no structural equals. Say how the tests compare expressions.
5. **Line 383:** optionally, also check that the release APK contains no `libmaplibre*.so`.
6. **Spec line 958:** no phase owns the `tools/README.md` row for `check_no_trial_code.py`.
7. **Line 173:** confirm the `validatePlugins` task path for the included build-logic build.
8. **Line 151:** "the build itself is the test" overstates what a build shows. Say "a passing build and the existing tests are the check".
9. **Lines 213, 456:** "small" adds nothing. Drop it.
10. **Line 269:** two colons in a row ("stop: **Owner gate**:"). Rephrase.
11. **Lines 434-438, 505:** "rate" in prose and `fps` in code. Use one term.
12. **Line 503:** the fallback is vague. Name the test that changes and what it should then expect.
13. **Line 522:** `validate_content.py` is labelled as lint. Call it content validation.
14. **Line 229:** the ruff step relies on the ruff configuration in `pyproject.toml`, which the plan does not mention.
15. **Line 203:** say how to regenerate the `release_manifest.txt` fixture when dependencies change.
16. **Line 383:** say which runner `FrameStatsTest` uses.
17. **Not checked (no network access):** whether MapLibre 13.7.1 and the `android-sdk-opengl` artifact exist; whether `color-relief` and `["elevation"]` are supported in that release; the AGP 9.5.1 defaults behind the first Blocker and the `CommonExtension` Major. Confirm these before approval.

## Verified against the repo

These match the repo:

* `AndroidCommon.kt` and the functions in `DependencyRules.kt`.
* `MAP_MODULE`, and the module-graph rule that allows `:app -> :core:map`.
* The catalog aliases and versions the plan names.
* `kotlin-compose` plugin ID.
* The release `signingConfig` line.
* The workflow step names, SHA and path filters.
* The keys of `camera-path.json` and `metadata.json` (`pack.py:75-88`).
* `docs/map-trial/step-1.md`.
* `android/README.md:45`.
* `app.seerah.MainActivity`.
* The spike's `MapStyle.kt:85-86` values.
* The test arithmetic in `FrameStats` and `CameraPath`.
