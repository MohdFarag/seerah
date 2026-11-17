# Consolidated Review Report: Map Trial Phase 2 Plan

**Target:** `docs/design/map-trial-phase-2-plan.md` (lines 1-526)
**Spec checked against:** `docs/design/map-trial-build.md`
**Inputs:** stage 5 `zoom_out_report.md` (Z5), stage 6 `comprehension_report.md` (C6), stage 7 `critic_report.md` (K7), stage 8 `intermediate_zoom_out_report.md` (Z8), stage 9 `implementation_readiness_report.md` (R9). No new reviewers were run.

## Reviewer Status

| Stage | Reviewers | Status |
| --- | --- | --- |
| 5 Zoom out | `egm-zoom-out`, `egm-inventive-catalyst` | completed |
| 6 Comprehension | `egm-comprehension` | completed |
| 7 Critic | `egm-design`, `egm-structural`, `egm-style` | completed (structural and style did not check facts) |
| 8 Intermediate zoom out | `egm-zoom-out` | completed |
| 9 Readiness | `egm-implementation-readiness`, `egm-codebase-details`, `egm-style` | completed |

## Summary

The shape of the plan is sound and every stage said so. It is not ready to implement: two build-level problems (B1, B2) would stop Tasks 4 and 7-10. Most other findings fall into two groups. The first is gaps an implementer would have to decide alone (dependencies, snippets, formats, edge cases). The second is size: the plan repeats the spec and fixes details the implementer should choose. The resolutions below follow the spec and keep the plan small.

**Counts:** 2 Blocker, 17 Major, 19 Nit.

**Owner-decision items:** M3 (release guard scope), M8 (D3, trial text in English), N1 (`MapFiles.base` shape, D1), N2 (intent extra and last-used view, D5). Everything else is a technical fix the plan author can make.

## Stage conflicts and resolutions

| Topic | Positions | Resolution |
| --- | --- | --- |
| Release guard | Z5: replace the aapt2 manifest guard with a dex and `.so` scan. K7: keep it, or add a classpath or dex check. Z8: keep the spec's aapt2 guard, add a build-logic dependency rule. R9 nit: optionally check for `libmaplibre*.so`. | Keep the aapt2 guard as spec G7 (line 75) and file 31 define it. Close the leak with a build-logic rule in `DependencyRules.kt` (Z8). Drop the manual classpath comparisons. A dex scan is a spec change; raise it only if the owner wants it (M3). |
| Missing sections (Problem, Goals, Alternatives, Risks) | K7 asks to add them. Z8 says drop the request. | Do not add sections. Add one sentence under "Where this fits" linking the spec's problem, goals, A1-A10 and risks (M12). |
| Glossing terms | C6 and K7 want definitions in the plan. Z8 says link the spec. | Link the spec's "The app" and "Step 1 checks" sections in one Terms line, and state the zoom rule once (M10). |
| Nullable `MapFiles.base` | Z5: Major, two options. Z8: Nit; the "always land background" option is wrong because it hides the sea in phase 3. | Nit. Prefer `MapFiles(relief)` now and a non-null `base` in phase 3; D1 as written is acceptable (N1). |
| Intent extra and last-used view | Z5: drop them. Z8: spec line 387 and file 30 require both. | Keep both (spec). Demote D5 to a sentence in Task 10, and validate the value (N2). |
| `SeerahMap` callback tests, `org.json` | Z5: extract the bridge and test it; consider `org.json`. Z8: drop both. | Keep Robolectric and record why. Do not extract the bridge (N11). |
| `asText` formatter | Z5, Z8: drop the formatter and D3. R9: if kept, give the full signature. | Drop D3 and the formatter; use English literals (spec file 30). If the owner keeps D3, apply R9's signature fix (M8). |
| Median edge cases | K7 Nit, Z8 Nit, R9 Major. | Major, because a test asserts on it (M14). |
| "No base layer" marker | Z5: add a `TrialHeader` field. Z8: one phrase in Task 12. | One phrase in Task 12 (N9). |
| `tools/README.md` row | K7: missing. Z8: phase 3 (spec file 42). R9: no phase owns it. | List it as a phase 3 deviation in the plan so ownership is explicit (N15). |

## Facts still unverified

These need a check against the real tools before approval. No reviewer had network access.

1. **AGP 9.5.1 and `testBenchmark` unit tests.** Whether `android.onlyEnableUnitTestForTheTestedBuildType` defaults to true, which would mean `:app:testBenchmarkUnitTest` does not exist (B1). Spec line 978 assumes `./gradlew check` runs the `testBenchmark` tests, so the spec carries the same assumption.
2. **AGP 9 `CommonExtension`.** Whether the generic form was removed, and how `defaultConfig` and `testOptions.unitTests.all` look on the non-generic type (M6).
3. **MapLibre 13.7.1.** The spec (line 127) says `color-relief` exists since 13.0.0 and the spike used 13.7.1, so the layer itself has a source. Still unverified: `step` inside `color-relief-color` (spec check 3, with an `interpolate` fallback), `["elevation"]` support, and the existence of the `android-sdk-opengl` artifact at that version.
4. **`validatePlugins` task path** for the included build-logic build (N17).

## Prioritized Findings

### 🔴 Blockers

#### B1. `testBenchmark` unit tests may not exist under AGP 9 - Technical

* **Raw findings:** R9-B1
* **Scope:** lines 187, 363-381, 416, 455, 476, 492; Tasks 7-10
* **Problem:** If AGP 9 only creates host unit tests for the tested build type (debug), `src/testBenchmark/` never compiles. Every "N passed" step in Tasks 7-10 fails, and the Task 11 fallback does not help.
* **Fix:** Verify against AGP 9.5.1. Then state one fix in the plan: set the property to false in `android/gradle.properties` (and list that file), set `testBuildType`, or move the pure logic where `testDebugUnitTest` sees it. If the fix changes where tests live, update spec line 978.

#### B2. Task 4 Step 4 cannot pass `buildHealth` - Technical

* **Raw findings:** R9-B2
* **Scope:** lines 267-269
* **Problem:** Task 4 adds MapLibre, Compose foundation and test dependencies with no code using them until Tasks 5-6. `buildHealth` runs with `severity("fail")` and will flag them. Following its advice removes MapLibre, so the Licensee check recorded in Step 5 tests nothing.
* **Fix:** Add each dependency in the task that first uses it, and move the Licensee check (trial check 10) to Task 6.

### 🟡 Major

#### M1. `benchmark` build type snippet will not compile - Technical

* **Raw findings:** R9-M1
* **Scope:** lines 219-225
* **Fix:** Use `initWith(buildTypes.getByName("release"))`, place the block after the release configuration in `AndroidApplicationConventionPlugin.kt`, and drop the redundant `isDebuggable = false`.

#### M2. Missing files in the file tree and task lists - Technical

* **Raw findings:** K7-1, Z8-10 (second half), R9-M2
* **Scope:** File structure (lines 99-135), Task 2 (lines 162-170)
* **Fix:** Add `android/build.gradle.kts` to the tree and Task 2's Files. Add `android/gradle.properties` if B1 uses it. The `tools/README.md` row: see N15.

#### M3. Release guard: dependency leak not caught in CI - Owner decision (scope) + technical

* **Raw findings:** Z5-1, K7-2, Z8-1, Z8-19, R9-N5
* **Scope:** line 19, line 93, Task 3, Task 7 Step 5, Task 11 Step 1; spec G7 (line 75), file 31
* **Problem:** The manifest guard only proves `app.seerah.trial` is absent from the release manifest. If `implementation` replaces `benchmarkImplementation`, MapLibre reaches release and only manual `releaseRuntimeClasspath` comparisons would catch it.
* **Fix (recommended):** Keep the aapt2 guard as the spec defines it. Add a build-logic rule beside `enforceMapLibreBoundary` in `DependencyRules.kt` that fails when any non-`benchmark*` `:app` configuration declares `:core:map`. Drop the manual classpath comparisons and the scratchpad baseline. In Task 3, keep the four tests and `main`'s contract (one APK argument, exit 0 or 1); drop the other inline signatures.
* **Owner decision:** whether the build-logic rule is enough, or whether the guard should also scan dex and `.so` files. The second option changes spec G7.

#### M4. Python tests cannot import the guard script - Technical

* **Raw findings:** R9-M9
* **Scope:** lines 204-214, 491
* **Fix:** State the import mechanism (`sys.path` insert, or `-t` with `__init__.py`), state that `main` exits 1 when aapt2 fails, and write out the full expected manifest list with no ellipsis.

#### M5. Release guard sentence hides its failure cases - Technical

* **Raw findings:** K7-6
* **Scope:** line 19
* **Fix:** "... or if it cannot read the manifest (aapt2 missing, or an empty or unparsable dump)."

#### M6. Unverified AGP 9 `CommonExtension` claim - Technical

* **Raw findings:** K7-7, R9-M6
* **Scope:** lines 85, 154
* **Fix:** Verify against 9.5.1 before approval and remove the "if AGP 9.5.1 differs" hedge, or label it expected behaviour confirmed in Task 1 Step 2.

#### M7. Dependencies left open (lifecycle-compose, coroutines, test deps) - Technical

* **Raw findings:** R9-M3, R9-M4, R9-M5
* **Scope:** lines 328, 363, 446; Tasks 6, 9, 10
* **Fix:** Name `androidx-lifecycle-runtime-compose` (version ref `androidxLifecycle`) and import `androidx.lifecycle.compose.LocalLifecycleOwner`. Add a `kotlinx-coroutines-android` alias (`implementation` in `:core:map`, `benchmarkImplementation` in `:app`). For tests, say "none added" or name each one. Add `libs.versions.toml` to the right tasks' Files.

#### M8. D3 and the `asText` formatter - Owner decision

* **Raw findings:** Z5-4 (second half), Z8-6, R9-M8
* **Scope:** D3, Task 9 Step 3 (lines 441, 456), Task 10
* **Problem:** Spec file 30 says the trial text is English and never ships. D3 adds a benchmark `strings.xml` and a formatter parameter the signature does not show; `strings.xml` is created only in Task 10.
* **Fix (recommended):** Drop D3. Use English literals in the benchmark source set and keep `asText` pure. Either way, give `TrialHeader`'s field types and the line template, since a test asserts on the output.
* **Owner decision:** confirm dropping D3. If the owner keeps resources, use them for the activity UI only and keep `asText` in English.

#### M9. Library convention plugins underspecified - Technical

* **Raw findings:** R9-M7
* **Scope:** lines 168-169
* **Fix:** Write out plugin IDs, the `LibraryExtension` body, lint and test `targetSdk`, and whether the module-graph plugin applies, as `AndroidApplicationConventionPlugin.kt` does.

#### M10. Trial vocabulary used without definition or link - Technical

* **Raw findings:** C6-1, C6-2, C6-3, C6-4, C6-8, K7-8, K7-9, Z8-2, R9-M13
* **Scope:** lines 9, 43-45, 64, 71, 96, 269-270, 502-503, 523
* **Problem:** "Style C" vs "option (a)/(c)", "painted"/"value", "step 1 check N", "stored value", and the fade conversion are undefined in the plan. "Step" means both task steps and trial stages.
* **Fix:** Add one Terms line in Global Constraints linking the spec's "The app" and "Step 1 checks" sections (spec lines 387-392, 437-449). At line 43 add "spike zooms are build zooms; MapLibre zoom = build zoom − 1". Write trial checks as "trial step 1, check 10" so they cannot be confused with task steps. If the spec lacks the zoom rule or "stored value", add them to the spec.

#### M11. Global Constraints restate the spec, and plan fixes low-level details - Technical

* **Raw findings:** Z5-3, Z8-4, Z8-5
* **Scope:** lines 39-54; Task 5 Step 3; Task 7 (lines 374-382); Task 8
* **Fix:** Link the spec for style values and measurement rules; keep only the differences (D1 land background, option (a) fade as a trial variable, cold run reported separately). Drop production constant names; keep expected literals in the tests.

#### M12. Missing feature-spec sections and rejected alternatives - Technical

* **Raw findings:** K7-3, K7-4, Z8-3, Z8-8
* **Scope:** whole document; Decisions (lines 67-87)
* **Fix:** One sentence under "Where this fits" pointing to the spec for problem, goals, A1-A10 and risks. Add half a sentence of rejected alternative to D1 (stub `base.pmtiles` now) and D2 (name the per-zoom variant now).

#### M13. Risks and limits incomplete - Technical

* **Raw findings:** K7-5, Z8-9
* **Scope:** Review Focus (lines 89-97), line 524
* **Fix:** No new section. In Review Focus, name two risks with the device-gate step that reveals them: MapLibre native size and install on the Oppo A55, and frame listener rate vs display frames. Security points (exported launcher, external storage, debug key) come with the spec's `benchmark` build type; handle only the intent-extra validation (N2).

#### M14. Median for even counts and runs with no reading - Technical

* **Raw findings:** K7-11, Z8-11, R9-M12
* **Scope:** line 54, line 434, Task 9
* **Fix:** Define the even-count median, show "no reading" for a run without readings, and add or extend a test.

#### M15. Colour string format and option C fade undefined - Technical

* **Raw findings:** R9-M10, R9-M11, R9-N4
* **Scope:** lines 282, 288-295; Tasks 5 and 7 (line 377)
* **Fix:** Specify alpha formatting (Kotlin gives `1.0`, the test expects `1`) and the full strings for c0-c3. Specify the value layer's `ZoomRange`, whether `color-relief-opacity` is written, and an assertion for it. Say how tests compare `JSONArray` expressions.

#### M16. `TrialActivity` state handling undecided - Technical

* **Raw findings:** R9-M12 (TrialActivity), K7-12, R9-N1, R9-N2
* **Scope:** lines 51, 356-358, 404, 441, 470-475
* **Fix:** One sentence each: per-run reading collection and `FrameStats` reset (also stated in Global Constraints), type of `OverlayState.runs`, open time once or per switch, picker form, missing `trial/` directory, behaviour when a variant's maxzoom is not 12, and one memory unit (`Debug.getPss()` is kB; show MB).

#### M17. Run-on "Behaviour" paragraphs and wrong Task 12 opening - Technical

* **Raw findings:** R9-M14, R9-M15
* **Scope:** lines 324, 473, 499, 506
* **Fix:** Turn each Behaviour paragraph into one bullet per behaviour. Task 12: "An agent cannot do Steps 1-5; it prepares the checklist and does Step 6."

### 🟢 Nits

#### N1. Nullable `MapFiles.base` (D1) - Owner decision

* **Raw findings:** Z5-2, Z8-7
* Prefer `MapFiles(relief)` in phase 2 and a non-null `base` in phase 3. Do not use "always draw land", which hides the sea in phase 3. Keeping D1 as written is acceptable.

#### N2. Intent extra and last-used view (D5) - Owner decision (confirm demotion)

* **Raw findings:** Z5-8, K7-13, K7-18, Z8-12, Z8-16
* Keep both (spec line 387, file 30). Move D5 into Task 10 as a sentence. Match the `<folder>/<view>` value against loaded variants and fall back to the picker; this blocks `../x`.

#### N3. D6 is not a decision

* **Raw findings:** K7-19, Z8-8, Z8-16
* Move it under the table; change line 4 to "D1-D5" (or fewer after N2).

#### N4. Goal promises "the look" and is too long

* **Raw findings:** K7-10, K7-14, C6-1 (partly)
* Split into bullets; say "the trial views' relief, fades and camera limits". In D1, note the sea stays hidden until phase 3.

#### N5. Self-review repeats the task list

* **Raw findings:** Z5-10, Z8-18
* Keep the spec file mapping, the gates and what an agent cannot verify. Drop the type-consistency paragraph.

#### N6. Camera runner indirection

* **Raw findings:** Z5-4 (first half), Z8-17
* Replace `runCameraPath` callbacks with `animateThrough(camera, stops)` plus pure `PathResults`; the activity loops three times.

#### N7. MapLibre version rule hard to apply

* **Raw findings:** K7-14 (version), Z8-16
* State the rule once, add "stop and report if none qualifies", drop the release-list history.

#### N8. Library-compose plugin for one module

* **Raw findings:** Z5-9
* Keep; spec files 20-21 list it.

#### N9. Phase 2 numbers could pass as trial results

* **Raw findings:** Z5-7
* In Task 12 Step 6, record them as "mechanics check, no base layer". No `TrialHeader` field.

#### N10. `SeerahMap` callback logic untested

* **Raw findings:** Z5-5
* No extraction for trial code. Device gate in Task 12 covers it.

#### N11. Robolectric for `StyleJsonTest`

* **Raw findings:** Z5-6
* Keep Robolectric; record that it is for fidelity to Android's `org.json`.

#### N12. Unlinked references (ADRs, AGENTS.md, helpers, spike build 20, spec numbering, import fallback)

* **Raw findings:** C6-5, C6-6, C6-7, C6-9, C6-10
* Add links only. Drop "build-20". Say which numbering "spec item 11" uses.

#### N13. Formatting and headings

* **Raw findings:** K7-15, K7-16, K7-20, K7-21, K7-22, K7-23, K7-24, K7-25, K7-26, R9-N10
* "(kept after the trial and shipped in v1)"; plain-case banner with skill location; H2/H3 headings for bold labels; one H2 case style; Deviations under its own heading; task numbers on the `testBenchmark` tree entries; remove the lone `---`; fix the double colon at line 269.

#### N14. Wording

* **Raw findings:** K7-17, R9-N8, R9-N9, R9-N11, R9-N13
* State the PSS unit; "a passing build and the existing tests are the check"; drop "small"; use one of "rate"/`fps`; call `validate_content.py` content validation.

#### N15. `tools/README.md` row has no owner

* **Raw findings:** K7-1 (second half), Z8-10, R9-N6
* List it as a phase 3 deviation (spec file 42).

#### N16. Frame gap outside 1-250 ms

* **Raw findings:** R9-N3
* Say it still updates the last frame time.

#### N17. Small test and tooling details

* **Raw findings:** R9-N7, R9-N12, R9-N14, R9-N15, R9-N16
* Confirm the `validatePlugins` path; name the test that changes under the step 1 fallback; mention the ruff config in `pyproject.toml`; say how to regenerate `release_manifest.txt`; name `FrameStatsTest`'s runner.

#### N18. Unverified external facts

* **Raw findings:** R9-N17
* See "Facts still unverified".

#### N19. Things to keep as is

* **Raw findings:** Z5 "noted as good", Z8 "keep as is"
* D2, D4, reuse of `enforceMapLibreBoundary`, skipped-folder handling, guard failing when it checked nothing. No change.

## Traceability Matrix

| Raw finding | Item |
| --- | --- |
| Z5-1 Release guard heavy / dependency leak | M3 |
| Z5-2 Nullable `base` | N1 |
| Z5-3 Low-level details fixed | M11 |
| Z5-4 Camera runner / text formatter | N6, M8 |
| Z5-5 `SeerahMap` untested | N10 |
| Z5-6 Robolectric | N11 |
| Z5-7 "No base layer" numbers | N9 |
| Z5-8 Three ways to choose a view | N2 |
| Z5-9 Library-compose plugin | N8 |
| Z5-10 Self-review | N5 |
| Z5 noted as good | N19 |
| C6-1 Style C vs options | M10, N4 |
| C6-2 Painted / value | M10 |
| C6-3 Step checks | M10 |
| C6-4 Zoom conversion | M10 |
| C6-5 Build-20 | N12 |
| C6-6 ADR / AGENTS links | N12 |
| C6-7 Helpers undescribed | N12 |
| C6-8 Stored value / ink | M10 |
| C6-9 Import fallback | N12 |
| C6-10 Spec numbering | N12 |
| K7-1 Files missing from tree | M2, N15 |
| K7-2 Guard checks manifest only | M3 |
| K7-3 Missing sections | M12 |
| K7-4 Rejected alternatives | M12 |
| K7-5 Risks incomplete | M13 |
| K7-6 Guard sentence | M5 |
| K7-7 `CommonExtension` | M6 |
| K7-8 Fade conversion | M10 |
| K7-9 Step N undefined | M10 |
| K7-10 Goal vs D1 | N4 |
| K7-11 Median rule | M14 |
| K7-12 First reading timing | M16 |
| K7-13 Stored view not validated | N2 |
| K7-14 MapLibre version rule | N7 |
| K7-15 Goal too long | N4 |
| K7-16 "(stays in v1)" | N13 |
| K7-17 Banner | N13 |
| K7-18 PSS unit | N14 |
| K7-19 D5 label | N2 |
| K7-20 D6 not a decision | N3 |
| K7-21 Front-matter labels | N13 |
| K7-22 Bold sub-labels | N13 |
| K7-23 H2 case | N13 |
| K7-24 Deviations buried | N13 |
| K7-25 Tree task numbers | N13 |
| K7-26 Lone rule | N13 |
| Z8-1 Guard: keep spec, build-logic rule | M3 |
| Z8-2 Link spec for glosses | M10 |
| Z8-3 Drop section request | M12 |
| Z8-4 Constraints restate spec | M11 |
| Z8-5 Constant names | M11 |
| Z8-6 D3 / formatter | M8 |
| Z8-7 Nullable `base` | N1 |
| Z8-8 Rejected alternatives, D6 | M12, N3 |
| Z8-9 Security section | M13, N2 |
| Z8-10 `tools/README.md`, `build.gradle.kts` | N15, M2 |
| Z8-11 Median | M14 |
| Z8-12 Keep intent extra / last view | N2 |
| Z8-13 No bridge extraction, keep Robolectric | N10, N11 |
| Z8-14 "No base layer" phrase | N9 |
| Z8-15 ADR links | N12 |
| Z8-16 Trivia decisions, MapLibre rule | N2, N3, N7 |
| Z8-17 `runCameraPath` | N6 |
| Z8-18 Self-review | N5 |
| Z8-19 Guard test detail | M3 |
| Z8 keep as is | N19 |
| R9-B1 `testBenchmarkUnitTest` | B1 |
| R9-B2 `buildHealth` in Task 4 | B2 |
| R9-M1 Build type snippet | M1 |
| R9-M2 `build.gradle.kts` missing | M2 |
| R9-M3 Lifecycle-compose | M7 |
| R9-M4 Coroutines | M7 |
| R9-M5 Test dependencies | M7 |
| R9-M6 `CommonExtension` | M6 |
| R9-M7 Library plugins | M9 |
| R9-M8 `asText` signature | M8 |
| R9-M9 Python import | M4 |
| R9-M10 Colour format | M15 |
| R9-M11 Option C fade | M15 |
| R9-M12 Median / empty run; TrialActivity state | M14, M16 |
| R9-M13 "Step" two meanings | M10 |
| R9-M14 Task 12 claim | M17 |
| R9-M15 Run-on paragraphs | M17 |
| R9-N1 Memory units | M16 |
| R9-N2 `maxZoom` unused | M16 |
| R9-N3 Gap outside range | N16 |
| R9-N4 `JSONArray` equality | M15 |
| R9-N5 `.so` check | M3 |
| R9-N6 `tools/README.md` | N15 |
| R9-N7 `validatePlugins` path | N17 |
| R9-N8 "build is the test" | N14 |
| R9-N9 "small" | N14 |
| R9-N10 Double colon | N13 |
| R9-N11 rate / fps | N14 |
| R9-N12 Fallback vague | N17 |
| R9-N13 `validate_content.py` label | N14 |
| R9-N14 Ruff config | N17 |
| R9-N15 Fixture regeneration | N17 |
| R9-N16 `FrameStatsTest` runner | N17 |
| R9-N17 Unchecked facts | N18 |

Note: R9 lists "Median and empty-run" and "TrialActivity state" as separate Majors; the matrix shows both under R9-M12 for brevity, mapped to M14 and M16 respectively.
