# Stage 7 Critic Report: Map Trial Phase 3 Plan

**Target:** `docs/design/map-trial-phase-3-plan.md`
**Reviewers:** `egm-design` (D7), `egm-structural` (S7), `egm-style` (St7)

---

## Part 1: `egm-design` (Hostile Design Critic)

### D7-B1. [Blocker] Coarse-band (`0–2`, `3–5`) resampling vs. 2-zoom-12-pixel Natural Earth erase hole causes multi-kilometre seam gaps and collapses small windows (`map-data/example/`)

- **Scope:** Lines 22, 230, 233, 238; `map-data/example/example.yaml` lines 6–11.
- **Finding:**
  1. **Small-window collapse in CI (`map-data/example/`):** `example.yaml` has a 0.25° × 0.25° (~30 km × 30 km) data extent. At build zoom 2 (top zoom of `0–2`), one Web Mercator pixel is 39,135.8 m (~0.352°) wide—larger than the entire example window (~0.76 × 0.76 pixels). Warping `mask.vrt` to zoom-2 pixel resolution collapses the window to a 1 × 1 pixel whose average across Masirah Island and surrounding sea is `< 128` (0 land polygons) and on which `gdal_contour` cannot trace any contour (requires at least a 2 × 2 grid).
  2. **Multi-kilometre WBM/Natural Earth gap at zooms `0–5`:** Task 3 line 238 erases Natural Earth `110m` (`0–2`) and `50m` (`3–5`) using `hole = shrink_extent(profile.data_extent, pixels=2)`, which shrinks `data_extent` by only 2 zoom-12 pixels (~19.6–76 m). However, lines 230 and 233 resample `mask.vrt` to zoom-2 (39.1 km/px) and zoom-5 (4.89 km/px) grids without locking `outputBounds` to `profile.data_extent`, pulling the contour and polygonized land back by 2.45–19.6 km inside `data_extent`.
- **Recommendation:**
  - For **WBM coast (`0..max_zoom`):** either extract the contoured coastline GeoJSONSeq once at zoom-12 resolution (from the padded block loop in step 3, where pixel centres are within 19 m of the edge and strictly overlap the 2-pixel `shrink_extent` ring) and pass it to `tippecanoe` across bands, or warp `0–2` and `3–5` with `outputBounds` pinned to `profile.data_extent`, a minimum 64 × 64 grid, and a 1-pixel `-1` nodata collar.
  - For **WBM land (`0–2`, `3–5`):** lock `gdal.Warp` `outputBounds` to `profile.data_extent` in EPSG:3857 and clamp target `(cols, rows)` to at least 64 × 64 pixels so small windows never collapse and outer land pixels reach `profile.data_extent`.

### D7-B2. [Blocker] 100% inland profiles (`window-najd.yaml`, `window-yemen.yaml`) produce empty WBM coast slices that Task 3 does not guard against

- **Scope:** Lines 230–234, 239, 241, 243, 373; `docs/design/map-trial-build.md` lines 207–208.
- **Finding:**
  - `window-najd.yaml` and `window-yemen.yaml` are 100% inland (`mask == 255` everywhere inside `data_extent`), so `gdal_contour -fl 128` produces **zero features** across all WBM coast bands (`0–2`, `3–5`, `6..max_zoom`).
  - Task 3 line 239 specifies "tiles each non-empty slice with `tippecanoe`" only under step 4 (Natural Earth), while steps 2 and 3 (lines 230–234) unconditionally invoke `tippecanoe` on WBM land and WBM coast outputs. Passing an empty GeoJSON/GeoJSONSeq to `tippecanoe` or `tile-join` can fail or emit empty archives.
  - Similarly, in `test_base.py` (lines 249–254), if a synthetic fixture has no coast features, `tile-join` omits `"coast"` from `vector_layers` and `verify_base` raises `BaseError`.
- **Recommendation:**
  - Require in Task 3 steps 2–4 that **every** WBM and Natural Earth slice checks `feature_count > 0` before invoking `tippecanoe`, skipping empty slices and passing only non-empty `.pmtiles` slice archives to `tile-join` (raising `BaseError` if no slice across the build produced any tiles).
  - Ensure global `ne_110m_coastline` (`0–2`) and `ne_50m_coastline` (`3–5`) (and synthetic equivalents in `test_base.py`) always supply at least one `"coast"` feature, and add a unit test in `test_base.py` for an all-land WBM mask with zero WBM coastline.

### D7-M1. [Major] Serial subprocess storm and repeated 1,400-source VRT opens across 1,400 blocks on the owner's 13 GB machine

- **Scope:** Lines 231, 234, 238, 253, 257.
- **Finding:** In `full.yaml` (`block_zoom: 9`, ~1,400 blocks), ~85–90% of blocks are uniformly all-land or all-sea. Unconditionally warping `mask.vrt` and spawning `gdal_contour`, `clip`, `polygonize`, and `reproject` subprocesses per block, plus a 648-iteration `ogr2ogr` loop for 10° cell splitting, spawns 5,000+ sequential CLI processes.
- **Recommendation:**
  - In `6..max_zoom` land: read the block's mask array in Python; if `not (valid & (mask >= 128)).any()`, skip immediately; if `(valid & (mask >= 128)).all()`, emit the block's `bounds_3857()` rectangle directly (reprojected to EPSG:4326 via `osr.CoordinateTransformation`); otherwise polygonise in-process via `gdal.Polygonize`.
  - In `6..max_zoom` coast: after reading `block.window.pad(1)` from `mask.vrt` in-process (`gdal.Warp(..., format="MEM")`), skip the block unless valid pixels contain both `< 128` and `>= 128`.
  - In 10° grid splitting on 50m/10m land: intersect in-process with OGR/GEOS (`geom.Intersection(cell).MakeValid()`, extracting polygonal parts via `ogr.ForceToMultiPolygon` to discard shared-edge `LineString`/`Point` artifacts).

### D7-M2. [Major] Determinism and `/tmp` disk-escape risks in `tippecanoe`, `tile-join`, and `pmtiles edit`

- **Scope:** Lines 110, 180, 230–243; `tools/map/Dockerfile` line 48.
- **Finding:**
  1. `tippecanoe` ignores `GDAL_NUM_THREADS`/`OMP_NUM_THREADS` and spawns `nproc` pthreads unless `TIPPECANOE_MAX_THREADS=1` is set (and `whitebox_tools` uses `RAYON_NUM_THREADS=1`).
  2. By default `tippecanoe` writes temporary files to `/tmp` (container overlayfs on host root disk) instead of the `/work` volume (`seerah-map-work`), bypassing Step 1 Check 8 and `report.json` disk accounting.
  3. Line 242 uses a metadata denylist instead of a strict allowlist.
- **Recommendation:**
  - Add `TIPPECANOE_MAX_THREADS=1 RAYON_NUM_THREADS=1` to `ENV` in `tools/map/Dockerfile` (and assert both in `test_image.py::test_single_thread_environment`).
  - Pass `-t <work.base_dir / "tmp">` (`--temporary-directory`) to every `tippecanoe` invocation so scratch I/O stays inside `/work/<fingerprint>/base/`.
  - Use a strict allowlist when rewriting `base.pmtiles` metadata via `pmtiles edit --metadata`.

### D7-M3. [Major] Ambiguous `shrink_extent` longitude math and magic number `111.32` in Task 2 (`grid.py`)

- **Scope:** Lines 196–200, 209; `tools/map/src/seerah_map/grid.py`.
- **Finding:** Line 198 says "converted to degrees at the relevant edge latitude", which could mislead an implementer into scaling `west`/`east` longitude shrink by `cos(lat)` (in Web Mercator, zoom-12 pixel longitude width is latitude-independent). Line 196 also embeds the magic number `111.32`.
- **Recommendation:** Specify that `shrink_extent` converts `pixels * pixel_m()` in `EPSG:3857` metres directly to degrees (shrinking all four sides in `EPSG:3857` metres by `pixels * pixel_m()` and unprojecting to degrees), and define `view_bounds` in `EPSG:3857` metres without `111.32`.

### D7-M4. [Major] Resume / interruption in `base` stage leaves dirty `work.base_dir`

- **Scope:** Lines 224, 227–245, 270.
- **Finding:** If a build fails or is killed mid-way through `build_base_layer` and is later resumed under the same fingerprint, `open_area` keeps `work.base_dir` as-is, leaving partial `.geojson`, `.tif`, or `.pmtiles` slice files behind.
- **Recommendation:** Have `build_base_layer` wipe and recreate `work.base_dir` (`shutil.rmtree(work.base_dir, ignore_errors=True); work.base_dir.mkdir(parents=True, exist_ok=True)`) on entry.

### D7-N1. [Nit] `test_example_base_has_no_inland_edge_coast` MVT georeferencing and edge-tolerance specification

- **Scope:** Lines 178, 281.
- **Recommendation:** Use GDAL 3.13's native PMTiles vector driver (`gdal.OpenEx(str(base_pmtiles), gdal.OF_VECTOR, open_options=["ZOOM_LEVEL=12"])`) and assert that no `coast` segment has both endpoints within `1.5 * pixel_m()` of the same edge of `profile.data_extent` in `EPSG:3857`.

---

## Part 2: `egm-structural` (Structural Layout Validator)

### S7-M1. [Major] File-structure tree omits `tools/map/tests/test_workarea.py`

- **Scope:** `## File structure` (lines 125–130) vs. `### Task 3` (lines 217, 248, 256, 258).
- **Problem:** Task 3 modifies `tools/map/tests/test_workarea.py`, but `test_workarea.py` is omitted from `## File structure`.

### S7-M2. [Major] Missing `**Files:**` blocks in Tasks 8 and 9, and missing commit step in Task 9

- **Scope:** `## File structure` (lines 149, 153–155) vs. `### Task 8` (lines 367–380) and `### Task 9` (lines 381–385).
- **Problem:**
  1. Tasks 8 and 9 omit the `**Files:**` section that Tasks 1–6 (and Phases 1–2) have.
  2. Task 9 Step 1 modifies `docs/map-trial/step-1.md` in the public repository after Task 7 merges the main PR, but omits a commit step (`docs(map): record the valley probe in step-1.md`).

### S7-N1. [Nit] Front-matter author handle mismatch (`MohdFarag` vs. `MohdFaragDev`)

- **Scope:** Line 3.
- **Problem:** Phases 1 and 2, `map-trial-build.md`, and `concepts_brief.md` all use `- **Author:** MohdFaragDev`.

### S7-N2. [Nit] Task template & self-review asymmetries across Tasks 1–9

- **Scope:** `### Task 1`–`### Task 9` (lines 160–385) and `## Self-review` (lines 393, 395).
- **Problem:**
  - Add `(spec file[s] ...)` to Task 1–6 H3 headings to match Phase 2 and Tasks 8–9.
  - Use `- Consumes:` / `- Produces:` consistently in `**Interfaces:**` for Tasks 1–5.
  - Give Tasks 7–9 step titles (`- [ ] **Step N: <Title> (Owner gate).**`) and mark Task 9 Step 2 as `(Owner gate, optional on available Galaxy A54)`.
  - In `## Self-review`, list both `docs/map-trial/README.md` (Task 6) and `docs/map-trial/step-1.md` (Task 9) under spec file 41.

### S7-N3. [Nit] Unlinked ADR and spec-section anchor references

- **Scope:** Lines 12, 22, 40, 47, 199, 200, 340, 392.
- **Problem:** Link `ADR 0009` and `ADR 0007`, and add section anchors (`#the-base-layer`, `#the-app`, `#valleys`, `#step-1-checks`) on line 47.

---

## Part 3: `egm-style` (Technical Copy Editor)

### St7-M1. [Major] Unresolved editorial self-prompt and 83-word run-on sentence in Task 4, Step 1

- **Scope:** Line 281.
- **Fix:** Replace the self-prompt parenthetical with one concrete decoding method and split into two crisp sentences.

### St7-M2. [Major] Hedged specification of deterministic PMTiles metadata in Task 3 (`base.py`)

- **Scope:** Line 242.
- **Fix:** Specify the exact allowlist of metadata keys written via `pmtiles edit --metadata` (`"name"`, `"trial": True`, `"fingerprint"`, `"tool_commit"`, `"data_commit"`, `"image"`, and `"vector_layers"`).

### St7-M3. [Major] 96-word run-on sentence with misattached scope modifier in Architecture (`base.py`)

- **Scope:** Line 22.
- **Fix:** Split the `base.py` Architecture bullet into four clear sentences covering inside-extent WBM, outside-extent Natural Earth, and the shared tiling/joining/metadata/verification steps.

### St7-M4. [Major] Ambiguous terminology: `"spec items 32–44"` vs `"spec files 32–44"` and ADR 0009's `"eleven items"`

- **Scope:** Lines 40, 89, 100, 145, 281, 339–342, 367, 381, 388.
- **Fix:** Use `spec file N` consistently for the numbered entries in `map-trial-build.md` (files 32–44), reserving `item N` for ADR 0009 decision 13's eleven trial items and `check N` for the Step 1 checks table.

### St7-N1. [Nit] British / repository English consistency (`polygonizes` -> `polygonises`) and slash-hedge removal

- **Scope:** Line 231.
- **Fix:** Use `polygonises` in prose (keeping `gdal raster polygonize` and test identifiers literal) and replace `streams/passes` with `writes an EPSG:4326 GeoJSONSeq file and passes it`.

### St7-N2. [Nit] Run-on sentences in Task 2 (`grid.py` interfaces) and Task 6 (`THIRD_PARTY_NOTICES.md`)

- **Scope:** Lines 196, 200, 350.
- **Fix:** Split each 70–80 word bullet into two direct sentences.

### St7-N3. [Nit] Spliced two-topic bullet in Deviations (Line 100)

- **Scope:** Line 100.
- **Fix:** Split already-complete spec files (`35, 36, 37, 44`) and Phase 2 carryover (`tools/README.md`) into two separate bullets.

### St7-N4. [Nit] Hedged verification step in Task 1, Step 1 (`if preferred, verify...`)

- **Scope:** Line 173.
- **Fix:** Drop `if preferred` from Step 1 and make `ldd /usr/local/bin/whitebox_tools` a required verification check in Step 5.

### St7-N5. [Nit] Filler adverbs and colloquial shorthand (`strictly`, `cleanly`, `clearly`, `(tiny)`, `(slow/huge)`)

- **Scope:** Lines 24, 37, 87, 98, 253, 350, 358.
- **Fix:** Strip conversational parentheticals and filler adverbs.

### St7-N6. [Nit] Metadata and casing consistency (`MohdFaragDev` and lowercase `phase 1`/`phase 2`/`phase 3`)

- **Scope:** Line 3 and running prose.
- **Fix:** Use `MohdFaragDev` on line 3 (keeping `MohdFarag` for git/GitHub auth on line 79) and lowercase `phase 1`/`phase 2`/`phase 3` in running prose to match `map-trial-phase-2-plan.md`.
