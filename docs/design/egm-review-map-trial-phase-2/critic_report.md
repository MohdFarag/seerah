# Goldfish Review Report

**Target:** `docs/design/map-trial-phase-2-plan.md` (lines 1-138)

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-design` | completed |
| `egm-structural` | completed |
| `egm-style` | completed |

Totals after deduplication: 0 Blocker, 9 Major, 17 Nit.

Note: the structural and style reviewers did not open the context files, so they did not check any facts. Only the design reviewer checked facts against the context.

## Prioritized Findings (Blockers -> Major -> Nits)

### Files missing from the file tree - Severity: Major

* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 99-135 (File structure)
* **Discrepancy Description**: Task 2 edits `android/build.gradle.kts` to add the `android-library` alias with `apply false`, but that file is not in the tree. The spec (map-trial-build.md, line 958) asks for a `tools/README.md` row for `android/check_no_trial_code.py`, but no tree entry or task covers it. The self-review still claims that files 19-31 are fully covered.
* **Rationale & Recommendation**: Add both files to the tree and to their tasks. If the `tools/README.md` row is meant for phase 3, list it as a deviation.

### The release guard only checks manifest components - Severity: Major

* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Line 19, with lines 93 and 131
* **Discrepancy Description**: The guard misses trial classes or `:core:map`/MapLibre that reach the release build without a manifest entry, for example through `implementation` used in place of `benchmarkImplementation`. The `releaseRuntimeClasspath` check in Tasks 3 and 7 is a manual step and never runs in CI. Review Focus does not list this classpath-leak risk.
* **Rationale & Recommendation**: Either state that the guard covers only what spec G7 asks for and accept the gap, or add a cheap CI check that fails if `releaseRuntimeClasspath` contains `:core:map` or `org.maplibre`, or if the release dex contains `app/seerah/trial` (for example, via `apkanalyzer dex packages`).

### The required feature-spec sections are missing - Severity: Major

* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 1-138
* **Discrepancy Description**: The plan has no Problem Statement, no Goals and Non-Goals, no Proposed Architecture heading, no Alternatives Considered and no Risks and Mitigations. The out-of-scope items are scattered across D1, D2 and the Deviations list.
* **Rationale & Recommendation**: Either say plainly that this phase plan inherits these sections from map-trial-build.md, with links to them, or add the sections.

### Decisions do not record the rejected alternatives - Severity: Major

* **Reviewer Lens:** `egm-structural`, `egm-design`
* **Affected Scope:** Lines 67-87
* **Discrepancy Description**: The Decisions table has only Decision and Recommendation columns. D1-D4 each imply an alternative that is never written down. Examples: building a stub `base.pmtiles` now (D1) and defining the per-zoom variant name now (D2). The design reviewer accepts that the spec already holds A1-A10.
* **Rationale & Recommendation**: For each decision, add one line naming the rejected option and the reason it was rejected.

### Risks and limits are incomplete - Severity: Major

* **Reviewer Lens:** `egm-structural`, `egm-design`
* **Affected Scope:** Lines 89-97 (Review Focus)
* **Discrepancy Description**: No security risks or limits are named: the exported LAUNCHER TrialActivity, reads from external storage, and a benchmark APK signed with the debug key. Two technical risks also lack a mitigation. The first is MapLibre's native-library size and install behaviour on the Oppo A55. The second is that `addOnDidFinishRenderingFrameListener` may fire at a different rate than real display frames, which is only admitted at line 524.
* **Rationale & Recommendation**: Rename the section "Risks and Mitigations", or add one. It should cover security and limits, and name the device-gate check that would reveal each risk (Task 12, Steps 1 and 3).

### The release guard sentence is unclear and hides its failure cases - Severity: Major

* **Reviewer Lens:** `egm-style`, `egm-structural`
* **Affected Scope:** Line 19
* **Discrepancy Description**: "if it found no manifest" mixes tenses. It also hides the real failure cases listed at line 93: aapt2 missing, an empty dump, or a dump that cannot be parsed.
* **Rationale & Recommendation**: Write "... or if it cannot read the manifest (aapt2 missing, empty or unparsable dump)."

### Unverified AGP 9 `CommonExtension` claim - Severity: Major

* **Reviewer Lens:** `egm-style`, `egm-design`
* **Affected Scope:** Line 85
* **Discrepancy Description**: The plan states as fact that AGP 9 removed the generic form, then adds "if AGP 9.5.1 differs". Nothing in the context confirms the claim.
* **Rationale & Recommendation**: Either cite the source and date of the check, or label it as expected behaviour that the implementer confirms in Task 1, Step 2.

### Fade conversion is not explained - Severity: Major

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 43 (and line 96)
* **Discrepancy Description**: "(the spike's 12.5–14.5 converted)" does not say what was converted into what. The rule (spike values are build zooms, and MapLibre zooms are one lower) only appears at line 96.
* **Rationale & Recommendation**: State the conversion rule once, at line 43.

### "Step 1 check N" is used without a definition - Severity: Major

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 44, 64, 71
* **Discrepancy Description**: The plan cites check numbers from `docs/map-trial/step-1.md` and the spec without naming the source. A reader can easily confuse them with the task steps.
* **Rationale & Recommendation**: Define "trial step 1" with a link the first time it is used, or in Global Constraints.

### The Goal promises "the look", which contradicts D1 - Severity: Nit

* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 9, 71
* **Discrepancy Description**: D1 says phase 2 checks the mechanics, not the look. With `base == null`, the `land` background covers `sea`, so the sea is never visible.
* **Rationale & Recommendation**: Reword the Goal to "relief layers, fades and camera limits". In D1, note that the sea stays hidden until phase 3.

### The camera-path median rule is incomplete - Severity: Nit

* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Line 54
* **Discrepancy Description**: The rule does not define the median for an even count, or for a run with no published reading (fewer than 30 gaps).
* **Rationale & Recommendation**: Define both cases and add a test in Task 9.

### The timing of the first reading is missing from Global Constraints - Severity: Nit

* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Line 51
* **Discrepancy Description**: The section says nothing about the `FrameStats` reset or when the first reading appears. Only Task 8 (line 404) states it.
* **Rationale & Recommendation**: State it at line 51 as well.

### The stored view value is not validated - Severity: Nit

* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Line 75 (D5)
* **Discrepancy Description**: The `<folder>/<view>` value also comes in through the exported activity's intent extra. A value such as `../x` could open files outside `trial/`. This affects the benchmark build only.
* **Rationale & Recommendation**: Match the value against the variants that were loaded. Fall back to the picker for anything unknown.

### The MapLibre version rule is hard to apply - Severity: Nit

* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Line 80
* **Discrepancy Description**: The rule mixes "newest by date" with "highest version". Pre-release lines such as `2026.11.x-globe` have no `android-v13.x` tag.
* **Rationale & Recommendation**: State the rule once, as Task 4, Step 1 does, and add "stop and report" for the case where no version passes the rule.

### The Goal sentence is too long - Severity: Nit

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 9
* **Discrepancy Description**: It is one 70-word sentence with nested parentheses.
* **Rationale & Recommendation**: Split it into bullets.

### "(stays in v1)" is unexplained - Severity: Nit

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 16
* **Discrepancy Description**: The shorthand is unclear.
* **Rationale & Recommendation**: Write "(kept after the trial and shipped in v1)".

### The agentic-workers banner - Severity: Nit

* **Reviewer Lens:** `egm-style`, `egm-structural`
* **Affected Scope:** Line 7
* **Discrepancy Description**: "REQUIRED" in capitals contradicts "(recommended)". The skill identifiers have no link or location.
* **Rationale & Recommendation**: Reword the sentence in plain case and say where the skills are found.

### The PSS unit is not stated - Severity: Nit

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 52
* **Discrepancy Description**: `Debug.getPss()` returns kB, but the plan elsewhere uses both `pssMb` and `pssKb`.
* **Rationale & Recommendation**: Add "(kB; shown in MB)".

### The D5 label "Technical;" is vague - Severity: Nit

* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 75
* **Discrepancy Description**: The label does not say what the owner is expected to do.
* **Rationale & Recommendation**: Write "No product impact; approved with the plan unless the owner objects."

### D6 is not a decision - Severity: Nit

* **Reviewer Lens:** `egm-style`, `egm-structural`
* **Affected Scope:** Lines 4, 76
* **Discrepancy Description**: D6 has an empty Recommendation cell and only describes how approval works.
* **Rationale & Recommendation**: Move D6 to a sentence under the table, and change line 4 to "D1–D5".

### The front-matter labels are bold text, not headings - Severity: Nit

* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 9-23
* **Discrepancy Description**: These labels do not appear in an outline. Their punctuation also differs: "Where this fits." ends with a full stop, the others with a colon.
* **Rationale & Recommendation**: Make them H2s, or at least use the same punctuation for all of them.

### Bold sub-labels in Global Constraints - Severity: Nit

* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 27, 34, 39, 48, 56, 62
* **Discrepancy Description**: These labels act as H3s but are bold text.
* **Rationale & Recommendation**: Turn them into H3s.

### H2 capitalisation is inconsistent - Severity: Nit

* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 25, 67, 89, 99
* **Discrepancy Description**: Some H2s use title case and others use sentence case.
* **Rationale & Recommendation**: Pick one style and use it for all H2s.

### Deviations is buried in a bold paragraph - Severity: Nit

* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 78-87
* **Discrepancy Description**: Deviations is a separate topic, but it sits inside the Decisions section.
* **Rationale & Recommendation**: Give it its own heading.

### Tree entries without task numbers - Severity: Nit

* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 127-128
* **Discrepancy Description**: The `testBenchmark` files name no task, unlike every other entry in the tree.
* **Rationale & Recommendation**: Add the task numbers (Tasks 7, 8 and 9).

### A lone horizontal rule - Severity: Nit

* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Line 137
* **Discrepancy Description**: This is the only `---` in the document.
* **Rationale & Recommendation**: Remove it, or put rules between all H2s.
