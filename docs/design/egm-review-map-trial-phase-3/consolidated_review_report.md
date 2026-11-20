# Consolidated Review Report: Map Trial Phase 3 Plan

**Target:** `docs/design/map-trial-phase-3-plan.md`
**Spec checked against:** `docs/design/map-trial-build.md`, `docs/adr/0009-map-data-pipeline.md`
**Inputs:** stage 5 `zoom_out_report.md` (Z5, IC5), stage 6 `comprehension_report.md` (C6), stage 7 `critic_report.md` (D7, S7, St7), stage 8 `intermediate_zoom_out_report.md` (Z8), stage 9 `implementation_readiness_report.md` (R9, CD9).

## Reviewer Status

| Stage | Reviewers | Status |
| --- | --- | --- |
| 5 Zoom out | `egm-zoom-out`, `egm-inventive-catalyst` | completed |
| 6 Comprehension | `egm-comprehension` | completed |
| 7 Critic | `egm-design`, `egm-structural`, `egm-style` | completed |
| 8 Intermediate zoom out | `egm-zoom-out` | completed |
| 9 Readiness | `egm-implementation-readiness`, `egm-codebase-details`, `egm-style` | completed |

## Summary

All five EGM stages reviewed the Phase 3 draft and verified its technical claims against the pinned `seerah-map:dev` container and the repository on `main` (`7e1b406`). Every reviewer confirmed the overall architecture and phase boundaries are sound. The review uncovered **5 Blockers**, **12 Majors**, and **7 Nits**—all resolved directly in the revised plan while preserving Decisions D1–D5 for the owner.

## Stage conflicts and resolutions

| Topic | Positions | Resolution |
| --- | --- | --- |
| Coarse-band (`0–2`, `3–5`) WBM coast & land vs. 2-px Natural Earth hole & `example.yaml` collapse | IC5-B1: warp `mask.vrt` with `outputBounds = profile.data_extent` in `EPSG:3857` and `>= 64×64` dimensions; pad a 1-px `-1` collar around that warped array for `gdal_contour -fl 128`. D7-B1 option 1: contour coast only at zoom 12 and feed it into `tippecanoe` for `0–2` and `3–5`. Z8: adopt IC5-B1 (D7-B1 option 2); reject D7-B1 option 1. | Adopt IC5-B1 (B1). It obeys spec line 307 (`gdal_contour` at 128 on the same mask), keeps low-zoom `coast` aligned with low-zoom `land`, prevents `example.yaml` (0.25° × 0.25°) from collapsing to 1 × 1 px, and aligns the outer boundary with `profile.data_extent` so it overlaps the 2-zoom-12-pixel Natural Earth hole at every zoom. |
| Mid-zoom (`6..max_zoom`) block cuts on WBM land and coast | IC5-M1: merge per-block coastlines in memory with OGR `LineMerge`, pass `--detect-shared-borders` to `tippecanoe` (or split `6..8` vs `9..12`), and add `line-cap`/`line-join` in `StyleJson.kt`. Z8: reject IC5-M1. | Reject IC5-M1. Spec line 306 fixes the three zoom bands, block-grid cuts, and the spike's `tippecanoe` flags (`--no-tile-size-limit --no-feature-limit --no-tiny-polygon-reduction`). Spike build 11 proved cell-cut land polygons with `fill-antialias: false` show no seams, and custom `LineMerge` stitching violates G5 (Little custom code). |
| Per-block `6..max_zoom` subprocess execution | D7-M1: replace `gdal raster polygonize` with in-process `gdal.Polygonize` and synthesize `bounds_3857()` rectangles for all-land blocks. Z5-M1, Z8, R9-M3: keep `gdal raster polygonize` and `gdal_contour` subprocesses (spec file 32), pre-filter uniform/empty blocks in numpy, write one combined `.geojsonl` file per band, and run 10° cell splitting on Natural Earth `50m`/`10m` land in-process via OGR. | Adopt Z5-M1 + R9-M3 (M1). Pre-filtering in numpy skips ~85–90% of `full.yaml` blocks before spawning `gdal_contour`, honours spec file 32, avoids opening 1,400 files in `tippecanoe`, and avoids the spike's 648-process `ogr2ogr` loop. |
| Sharing `pack.py` verification with `verify_base` | Z5-M3: share `pack.verify` between `pack.py` and `base.py`. Z8, R9-B1: `base.pmtiles` includes global Natural Earth `110m`/`50m` at zooms `0–5` (global header bounds), whereas `pack.verify` asserts `header["bounds"] == profile.data_extent` and raises `PackError`. | Import `Provenance` from `pack.py`, and keep `verify_base` raising `BaseError` without checking `header["bounds"] == profile.data_extent` (B4). |

## Prioritized Findings

### 🔴 Blockers

#### B1. Coarse-band (`0–2`, `3–5`) resampling vs. 2-zoom-12-pixel Natural Earth hole collapses `example.yaml` and creates a low-zoom sea trench

- **Raw findings:** IC5-B1, D7-B1
- **Scope:** Task 3 (`base.py`, Steps 2–4)
- **Problem:** `mask.vrt` covers the outer `block_zoom` tile grid (with internal mask `0` outside `profile.data_extent`). Warping `mask.vrt` to zoom-2 (~39.1 km/px) or zoom-5 (~4.9 km/px) resolution without locking `outputBounds` to `profile.data_extent` causes coarse edge pixels straddling `data_extent` to be marked `dstNodata = -1` or quantized up to 2.45–19.5 km inward (which a 2-zoom-12-pixel ~76 m Natural Earth overlap cannot bridge), and collapses `map-data/example/example.yaml` (0.25° × 0.25°) to a 1 × 1 pixel raster at zoom 2 (0 land polygons, 0 contours).
- **Disposition:** Fixed in Task 3. For bands `0–2` and `3–5`, `gdal.Warp` pins `outputBounds` to `profile.data_extent` in `EPSG:3857` and clamps destination `(cols, rows)` to `min(full_z12_dim, max(64, round(span_m / band_pixel_m)))` (`GDT_Int16`, `dstNodata=-1`, `resampleAlg="average"`). Land thresholds `>= 128`, and coast pads a 1-pixel `-1` (`NoDataValue = -1`) border around that warped array (shifting the geotransform origin by `(-dx, +dy)`) before running `gdal_contour -fl 128`.

#### B2. Natural Earth 1:10m zoom-6 clip box tiled to zoom 12 explodes CI work on zero-margin profiles (`example.yaml`)

- **Raw findings:** Z5-B1, IC5-M2
- **Scope:** D3, Deviations, Task 3 Step 4
- **Problem:** For `example.yaml` (`margin_deg: 0`), `natural_earth_max_zoom` returns `12`. Clipping `ne_10m` to `view_bounds(camera_bounds, maplibre_zoom=5)` (~24.4° × 22.8°) and tiling `-Z6 -z12` tiles ~75,000–160,000 zoom-12 tile positions across 7 `build` runs in `tests/test_build_example.py`, even though at MapLibre zooms 9–11 (build zooms 10–12) the screen reaches at most ~0.75° outside `camera_bounds`.
- **Disposition:** Fixed in D3, Deviations, and Task 3 Step 4. When `ne_max_zoom > 9`, Natural Earth 1:10m is tiled in two sub-bands: `6..min(9, ne_max_zoom)` clipped to `view_bounds(camera_bounds, maplibre_zoom=5)`, and `10..ne_max_zoom` clipped to `view_bounds(camera_bounds, maplibre_zoom=9)` (cutting zoom 10–12 tile area by >99%).

#### B3. 100% inland profiles (`window-najd.yaml`, `window-yemen.yaml`) produce empty WBM coast slices

- **Raw findings:** D7-B2
- **Scope:** Task 3 Steps 2–5
- **Problem:** `window-najd` and `window-yemen` have `mask == 255` everywhere inside `data_extent`, so `gdal_contour -fl 128` yields 0 features across all WBM coast bands. Invoking `tippecanoe` unconditionally on empty GeoJSON/GeoJSONSeq slices fails or writes empty archives.
- **Disposition:** Fixed in Task 3. Every WBM and Natural Earth slice checks `feature_count > 0` before calling `tippecanoe`, skips empty slices, and passes only non-empty `.pmtiles` slice archives to `tile-join` (raising `BaseError` if no slice produced tiles). Added `test_all_land_mask_skips_empty_wbm_coast_slices` to `test_base.py`.

#### B4. `base.pmtiles` has global header bounds (unlike `relief.pmtiles`), and `fetch.check_lock_matches` must not check `lock.natural_earth`

- **Raw findings:** Z8-5, R9-B1
- **Scope:** Task 3 Steps 4–5
- **Problem:** `base.pmtiles` contains global Natural Earth `110m`/`50m` at zooms `0–5` (global header bounds), so reusing `pack.verify`'s `header["bounds"] == profile.data_extent` check fails every build. Changing `fetch.check_lock_matches` to require `lock.natural_earth` breaks existing unit tests that pass `Lock(..., natural_earth=())`.
- **Disposition:** Fixed in Task 3. `verify_base` checks `pmtiles verify`, `minzoom == 0`, `maxzoom == profile.max_zoom`, `meta.get("trial") is True`, and `{layer["id"] for layer in meta["vector_layers"]} == {"land", "coast"}` (without checking `header["bounds"] == profile.data_extent`), raising `BaseError`. `base.py` validates that all six `fetch.NE_FILES` are present in `lock.natural_earth` and exist under `cache / ref.path`.

#### B5. Non-existent APK path `app-release-unsigned.apk` in Task 5 Step 4 fails `check_no_trial_code.py`

- **Raw findings:** R9-B2, CD9-B1
- **Scope:** Task 5 Step 4
- **Problem:** `release` in `android/app/build.gradle.kts` uses debug signing, producing `app-release.apk` (not `app-release-unsigned.apk`).
- **Disposition:** Fixed in Task 5 Step 4 to use `android/app/build/outputs/apk/release/app-release.apk` and `android/app/build/outputs/apk/benchmark/app-benchmark.apk` (matching `.github/workflows/android.yml`).

### 🟡 Major

#### M1. Per-block CLI subprocess churn, single-file GeoJSONSeq concatenation, geometry dimension filtering, and in-process 10° cell splitting

- **Raw findings:** Z5-M1, C6-M3, D7-M1, Z8-3, R9-M3
- **Disposition:** Fixed in Task 3. Pre-filter each `6..max_zoom` block in numpy (skip land when no valid pixel is `>= 128`; skip coast on `block.window.pad(1)` unless valid pixels `>= 0` contain both `< 128` and `>= 128`), append reprojected EPSG:4326 features in `(y, x)` order into a single `.geojsonl` file per band, filter `land` to polygonal parts (`ogr.ForceToMultiPolygon`, `Area() > 0`) and `coast` to linear parts (`ogr.ForceToMultiLineString`, `Length() > 0`), and split `50m`/`10m` Natural Earth land in-process via OGR on the global 10° × 10° EPSG:4326 grid (`-180..180`, `-90..90`) for only cells overlapping each geometry's envelope.

#### M2. Web Mercator math for `view_bounds` and `shrink_extent`, magic-number removal, and explicit `check_data_extent_coverage` signature

- **Raw findings:** Z5-M2, IC5-M3, D7-M3, CD9-Part2
- **Disposition:** Fixed in Task 2. Removed magic number `111.32` and mixed-latitude reach wording. Defined `view_bounds` and `shrink_extent` in `EPSG:3857` metres using `_TMS`, `_EQUATOR_M`, `_MAPLIBRE_TILE_PX`, and `pixel_m()`, expressed `natural_earth_max_zoom` and `check_base_coverage` via `view_bounds` containment in `data_extent`, and changed `check_data_extent_coverage(data_extent: Bounds, camera: Bounds, margin_deg: float)` to take explicit bounds.

#### M3. Determinism, thread caps, `/work` temporary directory, strict metadata allowlist, and clean `work.base_dir` on resume

- **Raw findings:** Z5-M4, C6-M3, D7-M2, D7-M4, St7-M2, R9-M3
- **Disposition:** Fixed in Tasks 1 and 3. Added `TIPPECANOE_MAX_THREADS=1 RAYON_NUM_THREADS=1` to `Dockerfile` `ENV` (checked in `test_image.py`), wiped/recreated `work.base_dir` on entry to `build_base_layer`, passed `-t <work.base_dir / "tmp">` to `tippecanoe`, specified exact `gdal vector layer-algebra` flags, and replaced the metadata denylist with a strict allowlist (`"name"`, `"trial": True`, `"fingerprint"`, `"tool_commit"`, `"data_commit"`, `"image"`, and `"vector_layers"` sorted by `"id"` with `"fields": {}`).

#### M4. `Dockerfile` layer order and runtime `ldd` check

- **Raw findings:** Z5-M5, St7-N4
- **Disposition:** Fixed in Task 1. Placed `COPY --from=tippecanoe-build`, `COPY --from=whitebox-build`, and `go-pmtiles` installation before `COPY requirements.txt` / `pip install`, and made `ldd /usr/local/bin/whitebox_tools` a required check in Step 5.

#### M5. Coast-edge test decoding via GDAL 3.13 native PMTiles driver and non-vacuous assertion

- **Raw findings:** Z5-N2, IC5-N1, C6-M1, D7-N1, St7-M1, R9-M4
- **Disposition:** Fixed in Task 3 (using `max_zoom=6, block_zoom=6` in `test_base.py`) and Task 4 (`test_example_base_has_no_inland_edge_coast` opens `base.pmtiles` with `gdal.OpenEx(str(base_pmtiles), gdal.OF_VECTOR, open_options=["ZOOM_LEVEL=12"])`, asserts at least one `coast` segment lies inside `profile.data_extent`, and asserts no segment has both endpoints within `1.5 * pixel_m()` of the same `profile.data_extent` edge in `EPSG:3857`).

#### M6. Both `test_cli.py` pipeline-order assertions and `cli.py` docstring/help updates

- **Raw findings:** Z5-N2, R9-M2, CD9-M1
- **Disposition:** Fixed in Task 4. Updated `_stub_pipeline` and both `test_build_pipeline_order` and `test_sibling_outline_inside_data_root_runs_fetch_and_build` to expect `["check lock", "block", "tile", "pack", "verify", "base", "report", "remove"]`, plus `cli.py` docstring and `build` help string.

#### M7. Exact valley-probe container commands and `whitebox_tools` flags

- **Raw findings:** R9-M1
- **Disposition:** Fixed in Tasks 6 and 9 with exact `gdalwarp` (`-t_srs EPSG:3857 -tr 90 90 -r bilinear` and `-tr 180 180 -r bilinear`) and `whitebox_tools` (`--run=BreachDepressions` / `--run=BreachDepressionsLeastCost --dist=100` and `--run=D8FlowAccumulation`) commands and cgroup memory checks.

#### M8. Task 8 host lock path (`map-data/dist/<P>.lock`), batch `fetch` before copy (for `check_clean`), and link to synthetic edits spec

- **Raw findings:** C6-M4, R9-M5, CD9-Part2
- **Disposition:** Fixed in Task 8. All four `fetch` runs execute first while both checkouts are clean, then all four locks are copied from `map-data/dist/<P>.lock` into `../seerah-content/map/trial/` and committed together.

#### M9. Task 5 `StyleJson.kt`, `MapFiles.kt`, `MapStyleSpec.kt`, `StyleJsonTest.kt`, `TrialFiles.kt`, and `android/core/map/README.md` alignment

- **Raw findings:** Z5-N1, CD9-M2
- **Disposition:** Fixed in Task 5 (`loadVariants`, `"$BASE_FILE is missing"`, KDoc updates on all three Kotlin files, `StyleJsonTest` fixture colours and JSON getters, and `android/core/map/README.md` line 15 and line 30 updates).

#### M10. Explicit build-zoom vs. MapLibre-zoom labels

- **Raw findings:** C6-M2
- **Disposition:** Fixed across Architecture, D3, Deviations, Task 2 (`naming the uncovered build zoom m + 1`), Task 3, and Task 6 (`min MapLibre zoom 4`).

#### M11. File-structure tree completeness (`test_workarea.py`), `**Files:**` blocks in Tasks 8–9, and Task 9 commit step

- **Raw findings:** S7-M1, S7-M2
- **Disposition:** Fixed in File structure, Task 8, and Task 9.

#### M12. Architecture sentence structure and consistent `spec file N` / `trial item N` / `trial step 1, check N` terminology

- **Raw findings:** C6-N2, St7-M3, St7-M4
- **Disposition:** Fixed in Architecture, Global Constraints (`### Terms`), Facts checked, Tasks 1–9, and Self-review.

### 🟢 Nits

- **N1 (C6-N1, S7-N3):** Added relative links for `ADR 0009`, `ADR 0007`, `ADR 0003`, `docs/map-trial/step-1.md`, and `map-trial-build.md` section anchors.
- **N2 (C6-N3):** Expanded `Water Body Mask (WBM)`, `Masirah Island`, `schema: trial-0`, and `Copernicus GLO-90 (90 m elevation)` on first use.
- **N3 (S7-N1, St7-N6):** Aligned front-matter author to `MohdFaragDev` (keeping `MohdFarag` for git/GitHub auth) and used lowercase `phase 1`/`phase 2`/`phase 3` in running prose.
- **N4 (S7-N2):** Added `(spec file[s] ...)` to Task H3 headings, used `- Consumes:` / `- Produces:` in `**Interfaces:**`, added step titles to Tasks 7–9, and listed both `README.md` and `step-1.md` under spec file 41 in Self-review.
- **N5 (St7-N1..N5):** Applied British `-ise` spelling (`polygonises`), split long bullets in Tasks 2, 3, and 6 and Deviations, and stripped filler adverbs.
- **N6 (R9-N1, CD9-N1, CD9-N2):** Listed exact licences for all eight map build tools in Task 6, gave the exact relative path to `Stats.kt` for `docs/spike/measurements.md`, and noted updating `tools/map/README.md` lines 3 & 81 and `THIRD_PARTY_NOTICES.md` lines 20–24.
- **N7 (R9-N2):** Specified `log("base stage started")` on entry to `build_base_layer`.

## Traceability Matrix

| Raw finding | Consolidated item |
| --- | --- |
| Z5-B1 NE 1:10m zoom-12 explosion on zero-margin profiles | B2 |
| Z5-M1 Per-block subprocess churn & 10° split | M1 |
| Z5-M2 Reach math, magic number `111.32`, `check_data_extent_coverage` | M2 |
| Z5-M3 Share `pack.py` helpers | B4 |
| Z5-M4 Metadata allowlist & `0–2`/`3–5` warp resolution | B1, M3 |
| Z5-M5 `Dockerfile` layer ordering | M4 |
| Z5-N1 `TrialFiles.kt` `loadVariants` & skip reason | M9 |
| Z5-N2 `test_cli.py` stub list & native PMTiles vector driver | M5, M6 |
| IC5-B1 Coarse-band `0–2`/`3–5` resampling vs. 2-px NE hole & `example.yaml` collapse | B1 |
| IC5-M1 Mid-zoom block-cut `LineMerge` / `--detect-shared-borders` | Rejected (Z8) |
| IC5-M2 NE 1:10m sub-band split & in-process 10° split | B2, M1 |
| IC5-M3 Web Mercator `view_bounds` & `natural_earth_max_zoom` | M2 |
| IC5-N1 GDAL native PMTiles vector driver | M5 |
| C6-M1 Task 4 coast-edge test self-prompt & assertion | M5 |
| C6-M2 Build zoom vs. MapLibre zoom labels | M10 |
| C6-M3 10° grid origin, NE cache path, `tilestats` rule | M1, M3 |
| C6-M4 Task 8 host `/out` path, clean checkouts, synthetic edits link | M8 |
| C6-N1 Unlinked ADRs and `step-1.md` | N1 |
| C6-N2 Colliding numbering schemes | M12 |
| C6-N3 Undefined acronyms/terms on first use | N2 |
| D7-B1 Coarse-band resampling & small-window collapse | B1 (option 2 accepted; option 1 rejected by Z8) |
| D7-B2 100% inland profiles produce empty WBM coast slices | B3 |
| D7-M1 Per-block filtering & in-process OGR 10° split | M1 (CLI replacement rejected by Z8) |
| D7-M2 `TIPPECANOE_MAX_THREADS=1`, `-t`, metadata allowlist | M3 |
| D7-M3 `shrink_extent` Web Mercator math & `111.32` removal | M2 |
| D7-M4 Clean `work.base_dir` on entry to `build_base_layer` | M3 |
| D7-N1 Coast-edge MVT georeferencing & `1.5 * pixel_m()` tolerance | M5 |
| S7-M1 `test_workarea.py` missing from File structure | M11 |
| S7-M2 `**Files:**` blocks in Tasks 8–9 and Task 9 commit step | M11 |
| S7-N1 `MohdFaragDev` author handle | N3 |
| S7-N2 Task headings, `**Interfaces:**`, step titles, Self-review 41 | N4 |
| S7-N3 Unlinked ADRs and spec section anchors | N1 |
| St7-M1 Task 4 Step 1 self-prompt & run-on sentence | M5 |
| St7-M2 Task 3 deterministic metadata allowlist wording | M3 |
| St7-M3 Architecture `base.py` 96-word sentence | M12 |
| St7-M4 `spec file N` vs. `trial item N` terminology | M12 |
| St7-N1..N5 British `-ise`, split long bullets, remove hedges/adverbs | M4, N5 |
| St7-N6 Author handle & lowercase `phase N` in prose | N3 |
| R9-B1 `base.pmtiles` global bounds vs. `pack.verify`, `lock.natural_earth` check | B4 |
| R9-B2 `app-release.apk` vs. `app-release-unsigned.apk` | B5 |
| R9-M1 Exact valley-probe commands and `whitebox_tools` flags | M7 |
| R9-M2 Second `build_calls` assertion in `test_cli.py` | M6 |
| R9-M3 Single `.geojsonl` per band, geometry dimension filtering, `layer-algebra` flags | M1, M3 |
| R9-M4 Non-vacuous coast-edge assertion & `max_zoom=6` in `test_base.py` | M5 |
| R9-M5 Task 8 batch `fetch` before copying locks into `seerah-content` | M8 |
| R9-N1 Tool licences, `Stats.kt` relative path, `tools/map/README.md` intro | N6 |
| R9-N2 `log("base stage started")` in `build_base_layer` | N7 |
| CD9-B1 `app-release.apk` path in Task 5 Step 4 | B5 |
| CD9-M1 `test_cli.py:230` assertion and `cli.py` docstring/help | M6 |
| CD9-M2 `StyleJson.kt`, `MapFiles.kt`, `MapStyleSpec.kt`, `StyleJsonTest.kt`, `README.md` | M9 |
| CD9-N1 `tools/map/README.md` lines 3 & 81 | N6 |
| CD9-N2 `THIRD_PARTY_NOTICES.md` lines 20–24 MapLibre note | N6 |
