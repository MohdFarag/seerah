# Stage 5 Zoom-Out & Inventive Catalyst Report: Map Trial Phase 3 Plan

**Target:** `docs/design/map-trial-phase-3-plan.md`
**Reviewers:** `egm-zoom-out` (Z5), `egm-inventive-catalyst` (IC5)

---

## Part 1: `egm-zoom-out` (Skeptical Architect)

### Z5-B1. [Blocker] Natural Earth 1:10m zoom-6 clip box tiled to zoom 12 explodes CI build work for zero-margin profiles (`example.yaml`)

- **Scope:** D3 (line 87), Deviations (line 98), Task 3 Step 4 (line 238).
- **Problem:** For `map-data/example/example.yaml` (`0.25° × 0.25°`, `margin_deg: 0`, `max_zoom: 12`), `natural_earth_max_zoom` returns `12`. Clipping `ne_10m` to a single `view_bounds(camera_bounds, maplibre_zoom=5)` box (~24.4° × 22.8°, ~557 sq. deg.) and tiling `-Z6 -z12` tiles ~75,000–160,000 zoom-12 tiles across 7 `build` invocations in `tests/test_build_example.py`. At zoom 10–12 (MapLibre zoom 9–11), the screen reaches at most ~0.75° outside `camera_bounds`, not 12°.
- **Recommendation:** Split the 1:10m band when `ne_max_zoom > 9`: tile `6..min(9, ne_max_zoom)` clipped to `view_bounds(camera_bounds, maplibre_zoom=5)`, and when `ne_max_zoom > 9`, tile `10..ne_max_zoom` clipped to `view_bounds(camera_bounds, maplibre_zoom=9)` (cutting the zoom 10–12 area by >99%).

### Z5-M1. [Major] GDAL CLI subprocess churn and redundant per-block work in `base.py`

- **Scope:** Architecture (line 22), Task 3 Steps 2–4 (lines 230–239, 257).
- **Problem:**
  1. Unfiltered per-block coast contouring (`6..max_zoom`, line 234) spawns `gdal_contour`, `clip`, and `reproject` subprocesses on all ~1,400 blocks of `full.yaml`, even though ~85–90% are all-land or all-sea.
  2. Per-block CLI reprojection spawns thousands of processes.
  3. 10° cell splitting ("the spike's fix") must not copy the spike's 648-iteration `ogr2ogr -clipsrc` bash loop.
- **Recommendation:** Pre-filter each block's numpy mask in memory (skip land polygonization if no valid pixel is `>= 128`; skip coast contouring unless valid pixels contain both `< 128` and `>= 128`), reproject once per band (or in-process via OGR), and run 10° cell splitting in-process with `osgeo.ogr` on only the 10° cells overlapping each geometry's envelope.

### Z5-M2. [Major] Mixed-latitude reach formula, magic number `111.32`, and tautological `check_data_extent_coverage` in `grid.py`

- **Scope:** Task 2 (lines 194–209).
- **Problem:**
  1. `check_data_extent_coverage(profile: Profile)` checks `profile.data_extent` against `camera_bounds` widened by `margin_deg`, which is tautological because `Profile.data_extent` is computed as `camera_bounds` widened by `margin_deg`.
  2. Line 196 uses magic number `111.32` (contradicting line 209) and compares `view_reach_km(..., south)` (which already includes `cos(south)`) against `margin_deg * 111.32 * cos(north)`.
- **Recommendation:** Pass explicit `data_extent: Bounds, camera: Bounds, margin_deg: float` to `check_data_extent_coverage`; define `view_bounds(camera, maplibre_zoom, half_width_dp)` via Web Mercator metres (`_EQUATOR_M`, `_MAPLIBRE_TILE_PX`, `_TMS.bbox`), and express both `natural_earth_max_zoom` and `check_base_coverage` as `Bounds` containment checks of `view_bounds(...)` inside `data_extent`.

### Z5-M3. [Major] Duplicated PMTiles verification and provenance metadata logic between `pack.py` and `base.py`

- **Scope:** Task 3 (lines 242–245) vs `tools/map/src/seerah_map/pack.py`.
- **Problem:** `pack.py` already has `_pmtiles`, `Provenance`, provenance dict assembly, and `verify` (`minzoom == 0`, `maxzoom == profile.max_zoom`, `trial is True`).
- **Recommendation:** Reuse/share `pack.py`'s `pmtiles` helper, provenance fields, and common header/metadata verification so `verify_base` only adds the `vector_layers` check (`{"land", "coast"}`).

### Z5-M4. [Major] Hedged `base.pmtiles` metadata stripping and unspecified `0–2` / `3–5` warp resolution in `base.py`

- **Scope:** Task 3 Steps 2, 3, and 5 (lines 230, 233, 242).
- **Problem:** Blacklisting metadata keys from `tile-join` ("and `tilestats` stripped or deterministic") risks leaving non-deterministic keys; lines 230 and 233 omit exact pixel resolution for `0–2` and `3–5`.
- **Recommendation:** Construct `base.pmtiles` metadata from an explicit allowlist (`"vector_layers"` keeping `"id"`, `"minzoom"`, `"maxzoom"`, `"fields": {}` sorted by `"id"`, plus `"name"`, `"trial": True`, `"fingerprint"`, `"tool_commit"`, `"data_commit"`, `"image"`). Specify the `0–2` and `3–5` warp resolution in `EPSG:3857` (see IC5-B1 below).

### Z5-M5. [Major] `Dockerfile` layer ordering causes C++ and Rust rebuilds on Python dependency changes

- **Scope:** Task 1 Step 4 (lines 177–180) and `tools/map/Dockerfile`.
- **Problem:** Placing `COPY --from=tippecanoe-build` and `COPY --from=whitebox-build` after `COPY requirements.txt` / `pip install` invalidates downstream layers on any Python package change.
- **Recommendation:** Place `COPY --from=tippecanoe-build`, `COPY --from=whitebox-build` and `go-pmtiles` before `COPY requirements.txt` in the runtime stage, and verify `ldd /usr/local/bin/whitebox_tools` inside the Ubuntu 26.04 runtime image.

### Z5-N1. [Nit] Function name and skip-reason wording in Task 5 do not match `TrialFiles.kt`

- **Scope:** Task 5 (line 313).
- **Recommendation:** Use `loadVariants` (not `loadTrialVariants`) and skip reason `"$BASE_FILE is missing"` (`"base.pmtiles is missing"`, matching `"$RELIEF_FILE is missing"` in `TrialFiles.kt`).

### Z5-N2. [Nit] `test_cli.py` stub names and hedged MVT decoding in Task 4

- **Scope:** Task 4 Step 1 (lines 276, 281).
- **Recommendation:** Match `test_cli.py`'s existing stub list `["check lock", "block", "tile", "pack", "verify", "base", "report", "remove"]` (space in `"check lock"`, `size_by_zoom` does not append to `calls`). In `test_example_base_has_no_inland_edge_coast`, open `base.pmtiles` directly via GDAL 3.13's native PMTiles vector driver (`gdal.OpenEx(..., open_options=["ZOOM_LEVEL=12"])`).

---

## Part 2: `egm-inventive-catalyst` (TRIZ Contradiction Audit)

### IC5-B1. [Blocker] Coarse-Band (`0–2`, `3–5`) Raster Resampling vs. 2-Zoom-12-Pixel Natural Earth Hole & Small-Window (`example.yaml`) Collapse

- **Scope:** Task 3 (`base.py`), lines 229–239; Review Focus 3 (line 109).
- **Problem:**
  1. `mask.vrt` covers the outer block grid (extending outside `profile.data_extent` with internal mask `0`). Warping `mask.vrt` to zoom-2 (~39.1 km/px) or zoom-5 (~4.9 km/px) resolution without pinning `outputBounds` to `profile.data_extent` causes coarse edge pixels straddling `data_extent` to be marked `dstNodata = -1` or quantized up to 2.45–19.5 km inward. A 2-zoom-12-pixel (~76 m) Natural Earth overlap ring cannot bridge a multi-km coarse-pixel pullback, leaving an inland sea trench along the data-extent border at zooms `0–5`.
  2. On `map-data/example/example.yaml` (0.25° × 0.25° ≈ 28 × 30 km), warping to zoom-2 pixel size collapses the raster to 1 × 1 pixel (averaging to ocean `< 128`, emitting **0 WBM land polygons** and **0 coastlines** at `0–2` while `hole` is erased from Natural Earth `110m`).
- **Recommendation:**
  - Set `gdal.Warp`'s `outputBounds` to `profile.data_extent` projected to `EPSG:3857`, and clamp destination `(cols, rows)` for `0–2` and `3–5` to `min(full_z12_dim, max(64, round(span_m / band_pixel_m)))`. Because `outputBounds` is strictly `profile.data_extent`, every pixel is inside the valid extent and outer land polygons reach `profile.data_extent` exactly, overlapping the 2-zoom-12-pixel Natural Earth hole at every zoom band while keeping `example.yaml` at least 64 × 64 pixels.
  - Before running `gdal_contour -fl 128` on those warped `0–2` and `3–5` arrays, pad a 1-pixel border of `-1` (`NoDataValue = -1`) around the array (shifting the geotransform origin by `(-dx, +dy)`) so `gdal_contour` sees nodata immediately outside `profile.data_extent` and draws zero false edge contours.

### IC5-M1. [Major] Block-Cut Fragmentation of Mid-Zoom (`6..8` / `6..10`) Coastlines and Land Polygons

- **Scope:** Task 3 (`base.py`), lines 231, 234; Task 5 (`StyleJson.kt`), line 311.
- **Problem:** At zooms `6..8` (and `6..10` on `example.yaml` with `block_zoom = 11`), block boundaries sit inside vector tiles. Independent per-block coastline `LineString` segments can be dropped or simplified asymmetrically by `tippecanoe` at zoom 6–8 (and render V-notches under default `line-cap: "butt"`), and independent per-block land polygons can develop 1-px simplification slivers along shared internal block cuts without `--detect-shared-borders`.
- **Recommendation:**
  1. After extracting and clipping per-block `6..max_zoom` coastlines (`block.window.pad(1)` clipped to `block.window.bounds_3857()`), merge the collected line geometries in memory with OGR/GEOS `LineMerge` (~4 MB RAM across all of `full.yaml`) before passing them to `tippecanoe`, and add `"layout": {"line-cap": "round", "line-join": "round"}` to the `"coast"` layer in `StyleJson.kt`.
  2. Pass `--detect-shared-borders` to `tippecanoe` for `land` (or warp `6..8` as a single zoom-8 raster and tile per-block only for `9..max_zoom`).

### IC5-M2. [Major] Natural Earth 1:10m Zoom-6 Reach Box Tiled to Zoom 12 on Zero-Margin Profiles + 10° Cell Split Subprocesses

- **Scope:** D3 (line 87), Deviation 4 (line 98), Task 3 Step 4 (line 238).
- **Recommendation:** Same as Z5-B1 and Z5-M1 (sub-band `10m` into `6..9` and `10..ne_max_zoom` when `ne_max_zoom > 9`, and run 10° cell splitting in-process via OGR/GEOS).

### IC5-M3. [Major] Mixed-Latitude Formula & Magic Number in `natural_earth_max_zoom` vs. `view_bounds` (`grid.py`)

- **Scope:** Task 2 (`grid.py`), lines 196–200, 209.
- **Recommendation:** Same as Z5-M2 (project `camera` to `EPSG:3857`, expand by `half_width_dp * (_EQUATOR_M / (_MAPLIBRE_TILE_PX * 2**maplibre_zoom))`, unproject to degrees clamped to `_TMS.bbox`, and define `natural_earth_max_zoom` and `check_base_coverage` via `view_bounds` containment in `data_extent`).

### IC5-N1. [Nit] Native GDAL PMTiles Vector Driver for `test_example_base_has_no_inland_edge_coast`

- **Scope:** Task 4 Step 1 (line 281).
- **Recommendation:** Same as Z5-N2 (`go-pmtiles` has no `pmtiles tile` command; open `base.pmtiles` directly with `gdal.OpenEx(str(base_pmtiles), gdal.OF_VECTOR, open_options=["ZOOM_LEVEL=12"])`).
