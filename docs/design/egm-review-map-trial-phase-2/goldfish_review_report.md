# Goldfish Review Report: Map Trial Phase 2 Plan

**Target:** `docs/design/map-trial-phase-2-plan.md`, revised 2026-11-17
**Input:** [consolidated_review_report.md](consolidated_review_report.md)
**Owner decisions (2026-11-17):** apply every item with the report's recommended resolution. M3: the aapt2 guard plus a build-logic rule, no dex or `.so` scan. M8: drop D3. N1: `MapFiles(relief)` in phase 2. N2: keep the intent extra and the last-used view, validated.

Section names below are those of the revised plan.

## Unverified facts

All four were checked; results and sources are in the plan's "Facts checked (2026-11-17)".

1. **`testBenchmarkUnitTest`:** absent by default under AGP 9.5.1 (probe build type, `--dry-run`: "task not found"). Fixed with a `beforeVariants` switch for `benchmark` only (fact 1).
2. **`CommonExtension`:** not generic in AGP 9.5.1; every member `AndroidCommon.kt` uses is on it (`javap`, fact 2).
3. **MapLibre 13.7.1:** artifact on Maven Central; `color-relief` since 13.0.0; `["elevation"]` supported. New finding: `step` is not rejected but sampled every 37 m, so the band edges would be soft (fact 3, Deviations).
4. **`validatePlugins`:** `./gradlew :build-logic:convention:validatePlugins` from `android/` (fact 4).

## Items

| Item | Result | Where |
| --- | --- | --- |
| B1 | FIXED | Fact 1; Task 3 Step 4 (`beforeVariants` enables `testBenchmarkUnitTest`), Step 5 (`check --dry-run`); Deviations (spec line 978 assumption is wrong by default) |
| B2 | FIXED | Dependencies added in the task that first uses them: Task 4 none, Task 5 test deps, Task 6 MapLibre, coroutines, lifecycle-compose and the Compose plugin; Licensee check moved to Task 6 Step 3 |
| M1 | FIXED | Task 3 Step 4: `initWith(buildTypes.getByName("release"))` after the release block, `isDebuggable` dropped |
| M2 | FIXED | File structure and Task 2 Files list `android/build.gradle.kts`; no `gradle.properties` change needed (B1 fix lives in build logic) |
| M3 | FIXED | Architecture; Task 3 (`enforceMapOnlyInBenchmark` in `DependencyRules.kt`, a one-off local proof in Step 5); manual classpath comparisons and the scratchpad baseline removed; inline signatures cut to `main`'s contract |
| M4 | FIXED | Task 3 Step 2: `sys.path` import, exit 1 when aapt2 fails, full expected list (fact 5) |
| M5 | FIXED | Architecture, release guard sentence |
| M6 | FIXED | Fact 2; Task 1 Step 2 hedge removed |
| M7 | FIXED | Task 6 Step 1 (`androidx-lifecycle-runtime-compose`, `kotlinx-coroutines-android` 1.9.0 from fact 6, `LocalLifecycleOwner` import named); Task 7 Step 1 and Task 9 Step 1 (no test deps added; coroutines `benchmarkImplementation`); `libs.versions.toml` in Task 6 Files |
| M8 | FIXED | D3 dropped; Code rules (English literals, no resources); Task 9 (`TrialHeader` field types, line template, `asText` pure) |
| M9 | FIXED | Task 2 Interfaces: plugin IDs, `LibraryExtension` body, lint and test `targetSdk`, no module-graph plugin |
| M10 | FIXED | Global Constraints, Terms (spec links, zoom rule, stored value, "trial step 1, check N"); trial checks renamed throughout. The spec already has the zoom rule; "stored value" is defined in the plan from spec lines 268 and 375 |
| M11 | FIXED | Global Constraints keep only the differences from the spec; production constant names dropped; tests hold literals |
| M12 | FIXED | "Where this fits" points to the spec's Problem, Goals, A1–A19 and risks; rejected alternatives in D1 and D2 |
| M13 | FIXED | Review Focus, last paragraph (two device risks with their Task 12 steps); Task 12 Steps 1 and 3 |
| M14 | FIXED | Global Constraints (even-count median, "no reading"); Task 9 (nullable rates, two new tests) |
| M15 | FIXED | Task 5 Interfaces (alpha format, full c0–c3 strings, string comparison of `JSONArray`s, `color-relief-opacity`); Deviations (option (c) fade 13.5–15.5, a trial variable) |
| M16 | FIXED | Global Constraints (per-run readings); Task 10 Behaviour (picker, missing folder, open time once per process, kB to MB); `OverlayState` field types; Task 7 (maxzoom not 12 is skipped, with a test) |
| M17 | FIXED | Task 6 and Task 10 Behaviour as bullets; Task 12 opening sentence |
| N1 | FIXED | D1, Task 4 (`MapFiles(relief)`), Task 5 (land background), Deviations; sea hidden until phase 3 stated |
| N2 | FIXED | D5 removed; Task 10 Behaviour (stored key, validation against loaded variants, picker fallback) |
| N3 | FIXED | Approval sentence under the decisions table; header says D1–D3 |
| N4 | FIXED | Goal as bullets, "the trial views' relief, fades and camera limits" |
| N5 | FIXED | Self-review keeps coverage, gates and what an agent cannot verify |
| N6 | FIXED | Task 9: `animateThrough(camera, stops)` and pure `PathResults`; Task 10 loops `RUNS` times |
| N7 | FIXED | Task 6 Step 1 states the rule once with "stop and report"; release history moved to fact 3 |
| N8 | FIXED (no change) | Library Compose plugin kept (spec files 20–21) |
| N9 | FIXED | Task 12 Step 6 |
| N10 | FIXED (no change) | No bridge extraction; Task 12 covers `SeerahMap` |
| N11 | FIXED | Deviations: Robolectric for fidelity to Android's `org.json` |
| N12 | FIXED | Links to ADRs 0002, 0003, 0004, 0007, AGENTS.md and engineering.md; "build-20" dropped; spec numbering named; import fallback linked through the step 1 checks |
| N13 | FIXED, partly CONTESTED | Global Constraints sub-labels and Deviations are headings; one H2 case; `---` removed; tree task numbers; double colon gone. The front-matter labels (**Goal:**, **Architecture:**, **Tech stack:**, **Spec:**) stay bold, because they are the writing-plans header format the plan follows |
| N14 | FIXED | PSS unit, "a passing build and the existing tests are the check", "small" dropped, "rate" in prose and fps in code, "content validation" |
| N15 | FIXED | Deviations: `tools/README.md` row left to phase 3 |
| N16 | FIXED | Task 8 Interfaces |
| N17 | FIXED | Fact 4 and Task 2 Step 2; Task 12 Step 3 names `valueStyle_hasRampThresholds`; ruff in `pyproject.toml`; Task 3 Step 1 regeneration; `FrameStatsTest` is plain JUnit 4 |
| N18 | FIXED | "Facts checked (2026-11-17)" |
| N19 | FIXED (no change) | D2 (now D2), D4 (now D3), `enforceMapLibreBoundary`, skipped folders and the guard failing when it checked nothing are unchanged |

## Still open

- **Option (c)'s ramp form.** The spec says `step`, with `interpolate` only if `step` is rejected. MapLibre's source shows `step` is accepted but drawn imprecisely. The plan keeps `step` and checks the edges on the device; starting with `interpolate` would be a spec change for the owner.
- **Option (c)'s fade** (13.5–15.5, same as option (a)) fills a gap the spec left; it is a trial variable for the owner to change on the device.
- **Plan length:** 591 lines, up from 526. The facts section, the B1 code, the text template and the state rules the reviewers asked for outweigh what was cut.
