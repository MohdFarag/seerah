# Comprehension report: map trial phase 2 plan

Reviewed: `docs/design/map-trial-phase-2-plan.md` (lines 1–526). The reviewer read only the plan.

## What this proposes

Phase 2 of a three-phase map trial gets the trial maps that the phase 1 tool produces displayed and measured on three test Android devices. It adds two library convention plugins and a new `:core:map` module. This module is the only place MapLibre is used. It takes plain data (map files, a style spec, camera limits), builds a MapLibre style JSON internally, and reports a camera handle and render events. It also adds a `benchmark` build type of `:app`. That build is release-like, debug-signed and never published. Only it depends on `:core:map`, and it holds temporary trial code: a screen that loads the variant folders, a frame-rate and memory overlay, and a scripted camera path that runs three times. A Python CI guard fails if trial components ever appear in the release APK's manifest. Six owner decisions (D1–D6) and several deviations from the spec are listed. The main ones are: no base layer yet (`base` is nullable), no per-zoom view, and trial strings in a benchmark-only `strings.xml`. Device checks are owner gates.

## How the existing system works, per this document

An approved spec (`map-trial-build.md`) defines three phases. Phase 1 is already built. Its map tool writes one folder per variant, `<name>-<variant>-<fp8>/`, containing `relief.pmtiles`, `metadata.json` and `report.json`. The owner adds `camera-path.json` to each folder by hand. The Android app already has:

- application convention plugins;
- shared settings in `AndroidCommon.kt`, typed to `ApplicationExtension`;
- a MapLibre-boundary check (`enforceMapLibreBoundary`);
- Licensee licence checks and `buildHealth`;
- a page-alignment script;
- a CI workflow that uploads a `seerah-app` artifact.

An earlier throwaway "spike" (`android/spike/`) supplied the colours, fade ranges and frame-statistics lessons. Phase 3 will add `base.pmtiles` (land, coast, Natural Earth).

## Findings

1. **Severity: Major.** **Scope:** line 9 "style C's look" vs lines 43–44 and 356 "option (c)", "option (a)", "zoom 12/zoom 11 views". The Goal says the map is drawn with "style C's look". Elsewhere the plan has four views, and only one is "option (c)", a value/color-relief view. A new reader can't tell whether "style C" is the same thing as "option (c)". If it is, the Goal contradicts the three painted views. If it isn't, it is never defined. The plan also never says what options (a) and (c) are options *of*, or why there is no option (b).
2. **Severity: Major.** **Scope:** the "painted" and "value" variants (lines 346, 357; also `ReliefSource.Painted`/`Value`). The plan never says what the two phase 1 variant kinds are. From the style values you can infer that "painted" is a pre-coloured raster and "value" is a terrarium-encoded elevation DEM, but the plan doesn't say so. You need that to understand why `viewsFor` maps PAINTED to three views and VALUE to one.
3. **Severity: Major.** **Scope:** "step 1 check 1–4, 10, 11", "step 2 of the trial", "trial window" (lines 71, 84, 86, 269, 497–504). These checks are numbered references to a checklist in `docs/map-trial/step-1.md` or the spec, and that file is never linked. Line 503 gives brief content for checks 2–4, and line 266 says check 10 is about Licensee and MapLibre. Check 1 (adb push) and check 11 (screen width) are only explained at Task 12. "Step 2 of the trial" and "a trial window" are never defined.
4. **Severity: Major.** **Scope:** the fade and zoom convention (lines 43, 96). "The spike's 12.5–14.5 converted" and "build-zoom" assume the reader knows that the phase 1 tool's zoom numbering is one higher than MapLibre's (presumably 512 vs 256 px tiles). The conversion rule is never stated. Review Focus item 4 depends on it.
5. **Severity: Nit.** **Scope:** "the spike's build-20 bug" (line 94). This refers to a build number of the spike that isn't explained. The sentence still makes sense without it.
6. **Severity: Nit.** **Scope:** ADR 0002, 0003, 0004, 0007, the roadmap and AGENTS.md (lines 16, 34, 58, 81, 201, 495). The roadmap is linked, but none of the ADRs or AGENTS.md are. "ADR 0004 note, owner 2026-11-14" and "ADR 0007 decision 10" can't be checked from this document.
7. **Severity: Nit.** **Scope:** existing build-logic helpers (`configureLicences`, `enforceMapLibreBoundary`, `configureModuleGraphRules`/`assertModuleGraph`, `DependencyRules.kt`, `check_page_alignment.py`, `tools/validate_content.py`; lines 32, 74, 168, 269, 493). These are used as known existing pieces with no one-line description or link. You can mostly tell what each does from its name, but `DependencyRules.kt` (where a Licensee exception goes) and `validate_content.py` are opaque.
8. **Severity: Nit.** **Scope:** "stored values 42.5, 127.5, 212.5" (line 44) and "ink" (lines 45, 253). The plan doesn't say what scale the "stored value" is on (an 8-bit 0–255 code? elevation?). It also doesn't say what "ink" is as a map element, beyond its being the relief tint colour.
9. **Severity: Nit.** **Scope:** "the in-app folder import fallback" (line 502). This is cited as an existing contingency but never described or linked.
10. **Severity: Nit.** **Scope:** "spec files 19–31" and "spec item 11" (lines 23, 54, 510–521). The numbering is the spec's own. The self-review maps the numbers to tasks, which helps, but "spec item 11" on line 54 is a different numbering and isn't explained.

Overall, the plan's architecture, tasks, interfaces and test intents fit together, and the reviewer could paraphrase every section. The gaps are trial-domain vocabulary carried over from the spec and the spike without even a one-line gloss: style C/options, painted/value, the numbered checks, and the zoom conversion.
