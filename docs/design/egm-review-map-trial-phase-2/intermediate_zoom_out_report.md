# Goldfish Review Report: Intermediate Zoom Out (Stage 8)

**Target:** `docs/design/map-trial-phase-2-plan.md` (full file)

**Inputs weighed:** `zoom_out_report.md`, `comprehension_report.md` and `critic_report.md` in this folder.

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |

## Verdict

There are no Blockers, and the plan's shape is sound. Most of the risk is added size, which comes from two places:

- The plan repeats the spec.
- Several fixes from earlier stages would either reopen what the approved spec already settled, or add sections that a phase plan inherits from the spec.

If the resolutions below are applied, the plan gets shorter. It gains about 8 short additions and loses several duplicated blocks. Only two items belong in the spec rather than the plan:

- the zoom convention and the meaning of "stored value", if the spec lacks them;
- any redesign of the release guard beyond checking the manifest.

**Counts:** 0 Blocker, 6 Major, 13 Nit.

## Prioritized Findings

### Release guard: keep the spec's design and close the dependency leak in build-logic - Severity: Major

- **Reviewer Lens:** `egm-zoom-out` (resolves earlier zoom-out and critic findings)
- **Affected Scope:** lines 19 and 93; Task 3; Task 7 Step 5; Task 1 Step 1. Spec G7 (line 75) and file 31 (lines 895-898).
- **Discrepancy Description**: The earlier zoom-out proposed replacing the aapt2 manifest guard with a dex/.so scan. That would change the spec, which already fixes how the guard works. The real gap is something else. If someone writes `implementation(project(":core:map"))` where `benchmarkImplementation` belongs, only the manual `releaseRuntimeClasspath` comparisons would catch it.
- **Rationale & Recommendation**:
  - Keep the aapt2 guard as the spec defines it.
  - Add a build-logic rule next to `enforceMapLibreBoundary` in `android/build-logic/convention/.../DependencyRules.kt`. It should fail when any `:app` configuration other than `benchmark*` declares `:core:map`.
  - Drop the three manual classpath comparisons and the scratchpad baseline.
  - A dex-scan redesign would be a spec change, not a plan change.

### Comprehension glosses: link to the spec instead of adding prose - Severity: Major

- **Reviewer Lens:** `egm-zoom-out` (resolves comprehension findings)
- **Affected Scope:** lines 9, 43-45, 71 and 96; Task 12.
- **Discrepancy Description**: The terms the comprehension report flagged are already defined in the spec, at lines 387-392 and 437-449: style C, options a and c, painted / value, the step 1 checks, and "stored value". Explaining them again in the plan would make a third copy.
- **Rationale & Recommendation**:
  - Add one "Terms" line in Global Constraints that links the spec's "The app" and "Step 1 checks" sections.
  - Add one clause at line 43: "spike zooms are build zooms; MapLibre zoom = build zoom − 1".
  - Reword the Goal on line 9 to "the trial views' relief, fades and camera limits". This also settles the critic's nit about the Goal and D1.
  - If the spec does not define the zoom conversion or "stored value", add them to the spec.

### Critic's request for Problem, Goals, Alternatives and Risks sections - Severity: Major (recommend dropping)

- **Reviewer Lens:** `egm-zoom-out` (resolves critic finding)
- **Affected Scope:** the whole document.
- **Discrepancy Description**: These sections would bloat an implementation plan whose approved spec already contains them.
- **Rationale & Recommendation**: Instead, add one sentence under "Where this fits" that points to the spec for the problem, goals, alternatives A1–A10 and risks.

### Global Constraints restate the spec - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** "Style values" and "Measurement rules" (lines 39-54).
- **Discrepancy Description**: Almost every line here repeats spec lines 369-401. The two copies have already started to drift: the median rule is worded differently in each.
- **Rationale & Recommendation**: Link to the spec and keep only what differs in the plan:
  - the D1 land background;
  - the option (a) fade as a trial variable;
  - the cold run reported separately.

### Constant names and literal encodings fixed in advance - Severity: Major

- **Reviewer Lens:** `egm-zoom-out` (agrees with earlier zoom-out)
- **Affected Scope:** Task 5 Step 3 (`TILE_SIZE`, `OPAQUE`, `TRANSPARENT`, `STYLE_VERSION`); the constant names in Task 8; the exact `rgba(...)` strings outside the tests.
- **Discrepancy Description**: The plan fixes production constant names that the implementer should choose.
- **Rationale & Recommendation**: Keep the test intents and the expected literals in the tests. Drop the production constant names; the "no magic values" rule already covers them.

### D3 and the formatter parameter in Task 9 - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** D3; Task 9 Step 3; Task 10.
- **Discrepancy Description**: Spec file 30 already decided that the trial's text is English and never shipped. Adding a benchmark-only `strings.xml` and a formatter parameter puts an extra layer into code that will be deleted.
- **Rationale & Recommendation**:
  - Drop D3. Use English literals in the benchmark source set and keep `asText` pure.
  - If the owner wants resources anyway, use `strings.xml` for the activity's UI only, and keep `asText` in English.

### Nullable `MapFiles.base` - Severity: Nit (downgraded from Major)

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** D1; Task 4.
- **Discrepancy Description**: The earlier option, "always draw a land background", would hide the sea background in phase 3, so it is wrong rather than simpler. Changing the type later costs little, because only trial code uses `:core:map` before phase 3.
- **Rationale & Recommendation**:
  - Simplest: use `MapFiles(relief)` only in phase 2, and add a non-null `base` in phase 3.
  - Keeping D1 as written is also acceptable.

### Rejected alternatives for each decision - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out` (resolves critic finding)
- **Affected Scope:** D1, D2 and D6.
- **Rationale & Recommendation**: Add half a sentence each to D1 and D2 only. Turn D6 into a sentence under the table.

### Security and limits section - Severity: Nit (move to spec / ADR)

- **Reviewer Lens:** `egm-zoom-out` (resolves critic finding)
- **Affected Scope:** Task 10.
- **Discrepancy Description**: The critic flagged the exported launcher activity, reads from external storage and debug-key signing. These come with the `benchmark` build type the spec chose, and spec file 35 already covers the ADR 0007 note.
- **Rationale & Recommendation**: Add no new section. In Task 10, add one clause: check the intent-extra path against the loaded variants, and show the picker if it does not match.

### `tools/README.md` row and the `android/build.gradle.kts` tree entry - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out` (resolves critic finding)
- **Rationale & Recommendation**:
  - The `tools/README.md` row belongs to phase 3 (spec file 42), so drop it from this plan.
  - The missing `android/build.gradle.kts` entry in the file tree is real: add that one line.

### Median for even counts and runs with no reading - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out` (resolves critic finding)
- **Affected Scope:** Task 9.
- **Rationale & Recommendation**: Add one line saying a run without readings shows "no reading", and fold it into an existing test.

### Dropping the intent extra and the last-used view - Severity: Nit (reject)

- **Reviewer Lens:** `egm-zoom-out` (resolves earlier zoom-out finding)
- **Discrepancy Description**: Spec line 387 and file 30 require both.
- **Rationale & Recommendation**: Keep both. Demote D5 to a sentence in Task 10, because a preference key is not an owner decision.

### Callback bridge extraction and `org.json` - Severity: Nit (drop)

- **Reviewer Lens:** `egm-zoom-out` (resolves earlier catalyst finding)
- **Rationale & Recommendation**:
  - Do not extract the `SeerahMap` callback bridge just to make it testable in trial code.
  - Keep Robolectric for `StyleJsonTest`. Adding `org.json` would need a catalog entry and a licence review.

### Marking phase 2 numbers as "no base layer" - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out` (resolves earlier catalyst finding)
- **Affected Scope:** Task 12 Step 6.
- **Rationale & Recommendation**: Add one phrase when recording the results: "mechanics check, no base layer". Do not add a `TrialHeader` field.

### Links to the ADRs and AGENTS.md - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out` (resolves comprehension finding)
- **Rationale & Recommendation**: Add the links only, with no descriptions.

### Decisions and gates for trivia - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** D5; D6; the MapLibre deviation (line 80).
- **Rationale & Recommendation**:
  - Move D5 and D6 into the tasks.
  - In the MapLibre deviation, keep the selection rule and add "stop and report if none qualifies". Drop the narrative about the release list's history.

### `runCameraPath` is over-specified - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out`
- **Rationale & Recommendation**: Replace the `onRunStart` and `readingsOfRun` callbacks with a suspend function, `animateThrough(camera, stops)`, plus the pure `PathResults`. The activity runs the loop three times itself.

### Self-review repeats the task list - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out`
- **Rationale & Recommendation**: Keep:
  - the spec file mapping;
  - the gates;
  - what an agent cannot verify.

  Drop the type-consistency paragraph.

### Release guard test detail - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** Task 3.
- **Rationale & Recommendation**: Keep the four tests. Drop the inline signatures, except for `main`'s contract: one argument (the APK), exit 0 or 1.

## Keep as is

- `seerah.android.library.compose`, which spec files 20 and 21 list.
- D4, page alignment on the benchmark APK, which reuses `check_page_alignment.py`.
- The reuse of `enforceMapLibreBoundary`.
- The handling of skipped folders, and the guard failing when it checked nothing.
- D2, deferring the per-zoom view.
