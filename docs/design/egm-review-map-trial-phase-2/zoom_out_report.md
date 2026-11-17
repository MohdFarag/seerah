# Goldfish Review Report: Initial Zoom Out

**Target:** `docs/design/map-trial-phase-2-plan.md` (lines 1-526)
**Standard:** `zoom-out-standard.md` (EGM 0.1.1)

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed (read the plan, the standard, and the spec sections on base, benchmark, per-zoom and the release guard; did not read the other context files) |
| `egm-inventive-catalyst` | completed (read the plan, the standard, and the spec sections on build type, release guard, adb push, import fallback and camera-path.json; did not read the other context files) |

Neither reviewer found a Blocker. Both said the overall shape is sound. Trial code lives in a removable `benchmark` source set, so the build itself keeps it out of release. The public interface of `:core:map` is plain data. The plan reuses the existing conventions and defers D1 and D2 to later phases.

## Prioritized Findings (Blockers -> Major -> Nits)

### Release guard is heavy for what it checks, and misses the dependency leak - Severity: Major

* **Reviewer Lens:** `egm-zoom-out`, `egm-inventive-catalyst`
* **Affected Scope:** Global Constraints "Release guard" (line 19), line 37, line 93, Task 3 (lines 177-231), Task 7 Step 5 (line 383), Task 11 Step 1
* **Discrepancy Description:** `check_no_trial_code.py` finds the newest `aapt2` in the SDK by numeric version sorting. Its tests use fake `aapt2` scripts and its fixtures come from real `aapt2` output. All of that proves only that `app.seerah.trial` is missing from the release manifest. A separate gap: "release gains no new dependency" is checked once, by hand, in Tasks 3 and 7. If `benchmarkImplementation` is later changed to `implementation`, MapLibre and its native libraries reach release, and nothing in CI catches it.
* **Rationale & Recommendation:** Base the guard on `tools/android/check_page_alignment.py`, which opens the APK as a zip using only the standard library. Make the guard scan `classes*.dex` for `Lapp/seerah/trial/` and fail if `lib/*/libmaplibre*.so` is present. It should still fail when it finds no dex file, so the "checked nothing" protection stays. This removes the aapt2 discovery and the fake-executable tests, and it puts the dependency check in CI. If the spec's wording about "manifest components" must hold, use AGP's build-tools path for aapt2 instead of writing new version-discovery code.

### Nullable `base` in a long-lived public type - Severity: Major

* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** D1, Task 4 (`MapFiles(relief, base: File?)`, lines 71, 245, 282)
* **Discrepancy Description:** `:core:map` stays in v1, but its public type gets a nullable field and a `base == null` land-background branch that exist only for phase 2. Phase 3 then has to change the field to non-null and delete the branch.
* **Rationale & Recommendation:** Pick one:
  * Keep `MapFiles` as `relief` only in phase 2 and add `base` in phase 3.
  * Make the land-colour background the bottom layer every time, so a base map can sit on it later and no null branch is needed.

### Plan fixes low-level details in advance - Severity: Major

* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Global Constraints (lines 41-46), Task 5 (lines 284-297), Task 7 (lines 374-382)
* **Discrepancy Description:** The plan names constants (`TILE_SIZE`, `OPAQUE`, `TRANSPARENT`, `STYLE_VERSION`, `MIN_GAP_MS`), gives exact `rgba(...)` strings and JSON literals, and spells out the `step`/`interpolate` fallback down to "stops 0.5 apart". It also repeats the spec, so any API difference forces an edit to the plan.
* **Rationale & Recommendation:** State the behaviours and test names: the fade range per view, the ramp thresholds, the gap bounds and the publish rate. Leave constant names and literal encodings to the implementer, who is already bound by the "no magic values" rule.

### Extra indirection in the camera runner and text output - Severity: Major

* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Task 9 (lines 440-441, 456), D3
* **Discrepancy Description:** `runCameraPath(camera, path, onRunStart, readingsOfRun)` calls back into the caller's `FrameStats`. `asText(TrialHeader)` takes a formatter parameter so its labels can come from resources. That is two extra layers in a developer tool that will be deleted.
* **Rationale & Recommendation:**
  * Have the activity loop over the runs and call a plain `animateThrough(camera, stops)`. Keep `PathResults` as the pure, tested part.
  * Use English literals in a formatter that exists only in the trial (the spec says the trial is in English and never ships), or pass the resolved labels in once. Revisit D3 to match.

### `SeerahMap` callback logic left without tests - Severity: Nit

* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Task 6 (line 326), Self-review (line 524)
* **Discrepancy Description:** The plan says "no unit test because the native view can't run under Robolectric". That skips the riskiest logic: bridging `CancelableCallback` to a suspend call (resume when it finishes, throw `CancellationException` when it is cancelled) and mapping render listeners to `FullyRendered`/`FrameRendered`. Bugs there would first appear on the owner's devices in Task 12.
* **Rationale & Recommendation:** Move the bridge and the event mapping into small internal functions that take a callback interface. Test them on the JVM with fakes.

### Robolectric for `StyleJsonTest` is a choice, not a requirement - Severity: Nit

* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Deviations "Added tests" (line 83), Task 5 Step 1
* **Discrepancy Description:** The plan presents Robolectric as forced because Android's `org.json` is a stub on the JVM.
* **Rationale & Recommendation:** One option is to add `org.json:json` as `testImplementation` only, so these run as plain JVM tests. It needs a catalog entry and a Licensee check. The other is to keep Robolectric and record that it was chosen for fidelity to Android's JSON number formatting.

### Phase 2 performance numbers could be mistaken for trial results - Severity: Nit

* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** D1 (line 71), Task 12 Step 5
* **Discrepancy Description:** Phase 2 runs without the land and coast layers. Its frame rate, memory and open-time figures understate the real load, yet they could sit in `step-1.md` next to the phase 3 figures.
* **Rationale & Recommendation:** Mark these runs "no base layer" in `TrialHeader` and in the copied text. In Task 12, record the numbers as checks that the mechanics work, not as measurements.

### Three ways to choose a view - Severity: Nit

* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Task 10, D5 (lines 75, 470, 473)
* **Discrepancy Description:** There is a picker, an exported intent extra, and a last-used view stored in `SharedPreferences`.
* **Rationale & Recommendation:** Keep the picker. Add the intent extra only if the owner will script runs over adb. Drop D5 unless re-measuring the same view is a stated need.

### Separate library-compose convention plugin for one module - Severity: Nit

* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Task 2 (line 169)
* **Discrepancy Description:** `seerah.android.library.compose` has a single user, `:core:map`.
* **Rationale & Recommendation:** This is acceptable because it mirrors the existing application/application.compose split and the spec lists it. Confirm that it is needed and not speculative.

### Self-review mostly repeats the task list - Severity: Nit

* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Self-review (lines 508-526)
* **Discrepancy Description:** The spec mapping and the type-consistency notes repeat the tasks.
* **Rationale & Recommendation:** Cut it down to the gates and the items an agent cannot verify.

## Noted as good (no change)

* D4 / Task 11: running `check_page_alignment.py` on the benchmark APK reuses the existing script, which already accepts several APKs.
* The fallbacks are planned up front (`interpolate`, `pmtiles extract`, import inside the app). The guard fails when it has checked nothing, and bad variant folders are listed as skipped rather than crashing the app.
