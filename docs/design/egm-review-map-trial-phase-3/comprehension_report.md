# Stage 6 Comprehension Report: Map Trial Phase 3 Plan

**Target:** `docs/design/map-trial-phase-3-plan.md`
**Reviewer:** `egm-comprehension` (C6)

---

## Paraphrase

### What this proposes

Phase 3 of the map trial build finishes the foundations required to evaluate trial Step 1 by adding `tippecanoe` (`tile-join`) and `WhiteboxTools` (`whitebox_tools`, compiled from pinned Rust source) to the `tools/map` Docker image and building the vector base layer archive (`base.pmtiles`). Inside the profile's data extent, a new `base.py` stage generates `land` polygons and `coast` contour lines from the smoothed Water Body Mask rasters across three zoom bands (`0–2`, `3–5`, `6..max_zoom`); outside the data extent (shrunk inward by 2 zoom-12 pixels to leave an overlap seam), it erases that box from cached Natural Earth 1:110m, 1:50m, and regionally clipped 1:10m datasets, splits large polygons on a 10° grid, tiles and joins all slices into a deterministic `"trial": true` PMTiles archive, and records `"base"` timing in `report.json`. On Android, `:core:map` and the `benchmark` build type are updated so `MapFiles` requires `base.pmtiles` and `StyleJson` renders `land` fill and `coast` lines over a `sea`-coloured background beneath the `relief` layer. Finally, the plan completes the remaining Phase 3 repository documentation and third-party notices, and—behind owner gates after the public PR merges—prepares the private `seerah-content/map/trial/` profiles/locks and runs the WhiteboxTools valley-extraction memory probe.

### How the existing system works, per this document

Phase 1 implemented the Python/GDAL relief pipeline in `tools/map/` and a zero-margin synthetic example in `map-data/example/`, producing `relief.pmtiles`, `metadata.json`, and `report.json` while saving smoothed per-block land masks (`mask/<id>.tif` and `mask.vrt` in the work area) and caching six Natural Earth GeoJSON files during `fetch`. Phase 2 added the Android `:core:map` module and `benchmark` app build type, which currently take only `relief: File` in `MapFiles` and draw `"relief"` over a temporary land-coloured `"background"` layer (though `TRIAL_COLOURS` already defines `SEA`, `LAND`, and `COAST`). The `tools/map/run.py` launcher runs the pinned `seerah-map` container image with `/src`, `/data`, `/cache`, `/work`, and `/out` mounts. Several Phase 3 documentation deliverables from the spec (ADRs 0004, 0007, 0009, and `.github/dependabot.yml`) are already merged on `main`, while two benchmark devices (Oppo A55 and Huawei MatePad 11.5) are temporarily unavailable, leaving the MatePad's ~550 dp half-screen width as a working constant.

---

## Prioritized Findings

### C6-M1. [Major] Task 4 coast-edge test contains an unsettled drafting note, references `tippecanoe-decode` (not installed in the image), and leaves "inland stretches" undefined

- **Scope:** Task 4, Step 1 (`docs/design/map-trial-phase-3-plan.md`, line 281).
- **Problem:**
  1. Line 281 contains an unresolved parenthetical (`via ogr.Open on MVT / pmtiles tile extraction or tippecanoe-decode — specify pmtiles tile + GDAL MVT driver or gdal vector on decoded features`) and suggests `tippecanoe-decode`, which is not copied into the runtime image in Task 1.
  2. Line 281 asserts `no coast vertex lies on the inland stretches of the window edge` without defining what coordinate ranges constitute the inland stretches or defining the geometric assertion purely in terms of `profile.data_extent`.
- **Concrete Fix:** Replace the parenthetical with one concrete decoding method supported by the runtime image (`gdal.OpenEx(str(base_pmtiles), gdal.OF_VECTOR, open_options=["ZOOM_LEVEL=12"])`), and define the geometric assertion purely in terms of `profile.data_extent` (no `coast` segment has both endpoints within tolerance of the same edge of `profile.data_extent`).

### C6-M2. [Major] Ambiguous mixing of build zooms and MapLibre zooms across Architecture, D3, Task 2, and Task 3

- **Scope:** Architecture (line 21), Global Constraints (line 48), D3 (line 87), Deviations (line 98), Task 2 (lines 200, 205, 207), Task 3 (line 238), Task 6 (line 348).
- **Problem:**
  1. Lines 21, 87, and 98 refer to "the zoom-6 view-reach bounding box", whereas Task 3 line 238 calls `view_bounds(profile.camera_bounds, maplibre_zoom=5)` (because build zoom 6 is MapLibre zoom 5).
  2. Task 2 line 200 states `check_base_coverage` iterates MapLibre zoom `m` and raises `ValueError` naming "the uncovered zoom", while line 207 expects it to name build zoom `9` (`m + 1`).
  3. Line 348 writes `min zoom 4` without stating `min MapLibre zoom 4`.
- **Concrete Fix:** Write `at build zoom 6 (MapLibre zoom 5)` on lines 21, 87, and 98; specify `naming the uncovered build zoom m + 1` on line 200; and write `min MapLibre zoom 4` on line 348.

### C6-M3. [Major] Underspecified 10° polygon-splitting grid alignment, Natural Earth cache lookup, and `tilestats` metadata rule in Task 3

- **Scope:** Task 3 (lines 238–255).
- **Problem:**
  1. Neither line 238 nor line 253 states the grid origin for 10° cell splitting or how splitting is executed.
  2. Line 238 says `from lock.natural_earth under cache` without noting that each `FileRef` in `lock.natural_earth` has a `.path` relative to `/cache` (`cache / ref.path`).
  3. Line 242 leaves `and tilestats stripped or deterministic` undecided.
- **Concrete Fix:** Specify a global 10° × 10° EPSG:4326 grid (`-180..180` lon, `-90..90` lat) intersected in-process via OGR/GEOS; resolve Natural Earth paths as `cache / ref.path` for each `ref` in `lock.natural_earth`; and specify an explicit metadata allowlist that omits `tilestats`.

### C6-M4. [Major] Task 8 confuses container `/out` with host output directory, omits `run.py` clean-checkout prerequisite, and leaves "the five synthetic edits" unlinked

- **Scope:** Task 8 (lines 371–379).
- **Problem:**
  1. Line 379 tells the operator to run `python3 tools/map/run.py -- fetch --profile trial/<P>` on the host and `copy /out/<P>.lock`, whereas `/out` is the container path mounted to `map-data/dist/` on the host by default (and `run.py` requires both checkouts to be git-clean).
  2. Lines 371 and 374 mention `the five synthetic edits` without linking the spec section (`docs/design/map-trial-build.md` item 34, lines 923–924) that defines them.
- **Concrete Fix:** Change `/out/<P>.lock` on line 379 to `map-data/dist/<P>.lock` (noting `--private` and clean-checkout requirements), and link `the five synthetic edits` to `map-trial-build.md` item 34.

### C6-N1. [Nit] Unlinked cross-references to `ADR 0009`, `ADR 0007`, `ADR 0003`, and `docs/map-trial/step-1.md`

- **Scope:** Lines 12, 16, 21, 40–41, 145, 199–200, 340, 347, 392.
- **Concrete Fix:** Add relative links to `../adr/0009-map-data-pipeline.md`, `../adr/0007-content-formats-compatibility-and-signing.md`, `../adr/0003-v1-architecture-and-modules.md`, and `../map-trial/step-1.md`.

### C6-N2. [Nit] Colliding numbering schemes ("Step 1", "Check 11", "eleven items", "Step 1, item 7", "spec items 32–44", and "build 11")

- **Scope:** Lines 16, 28, 40–41, 86, 101, 194, 340, 381.
- **Concrete Fix:** Distinguish `trial step 1, check N` (from `step-1.md`), `trial item N` (from ADR 0009 decision 13), `spec file N` (from `map-trial-build.md` files 32–44), and `spike build 11` in `### Terms` and throughout the text.

### C6-N3. [Nit] Undefined acronyms and project-specific terms on first use (`WBM`, `GLO-90`, `Masirah`, `trial-0`)

- **Scope:** Lines 12, 107, 109, 146, 229, 232, 348, 383.
- **Concrete Fix:** Introduce `Water Body Mask (WBM)` on line 12, `the map-data/example/ Masirah Island window` on line 107, `the trial's provisional schema: trial-0 edit format` on line 348, and `Copernicus GLO-90 (90 m elevation)` on line 383.
