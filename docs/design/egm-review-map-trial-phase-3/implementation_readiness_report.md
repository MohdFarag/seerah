# Stage 9 Implementation Readiness & Codebase Details Report: Map Trial Phase 3 Plan

**Target:** `docs/design/map-trial-phase-3-plan.md`
**Reviewers:** `egm-implementation-readiness` (R9), `egm-codebase-details` (CD9)

---

## Part 1: `egm-implementation-readiness` (Pragmatic Tech Lead)

### R9-B1. [Blocker] `base.pmtiles` has global header bounds, so reusing `pack.verify` will fail every build, and `fetch.check_lock_matches` must not check `lock.natural_earth`

- **Scope:** Task 3 (`docs/design/map-trial-phase-3-plan.md`, lines 238, 242–245) vs. `tools/map/src/seerah_map/pack.py` (lines 156–183) and `tools/map/src/seerah_map/fetch.py` (lines 92–95).
- **Finding:**
  1. Unlike `relief.pmtiles` (whose tiles cover only `profile.data_extent`), `base.pmtiles` joins global Natural Earth 1:110m (`0–2`) and 1:50m (`3–5`) slices with the WBM slices, so `tile-join` writes global header bounds (`[-180, -85.051129, 180, 85.051129]`), not `profile.data_extent`. Calling `pack.verify` or sharing its bounds check fails unconditionally at `pack.py:180`, and `pack._pmtiles` raises `PackError` instead of `BaseError`.
  2. Do not modify `fetch.check_lock_matches` to require `lock.natural_earth`, because existing unit tests (`tools/map/tests/test_cli.py:65`, `tools/map/tests/test_blockstage.py`) pass synthetic `Lock(..., natural_earth=())` objects.
- **Fix:**
  - Specify that `verify_base(pmtiles_path: Path, profile: Profile) -> None` runs `pmtiles verify`, reads `pmtiles show <path> --header-json` and `pmtiles show <path> --metadata`, and raises `BaseError` if `pmtiles` exits non-zero or if `header["minzoom"] != 0`, `header["maxzoom"] != profile.max_zoom`, `meta.get("trial") is not True`, or `{layer.get("id") for layer in meta.get("vector_layers", [])} != {"land", "coast"}` (without checking `header["bounds"] == profile.data_extent`).
  - Specify in Task 3 Step 4 that `base.py` indexes `{Path(ref.path).name: cache / ref.path for ref in lock.natural_earth}` and raises `BaseError` if any of `fetch.NE_FILES` is absent from `lock.natural_earth` or missing on disk under `cache`.

### R9-B2. [Blocker] Non-existent APK path `app-release-unsigned.apk` in Task 5 Step 4 will fail `check_no_trial_code.py`

- **Scope:** Task 5, Step 4 (line 321); `android/app/build.gradle.kts` (lines 27–31); `.github/workflows/android.yml` (lines 45–51).
- **Finding:** `android/app/build.gradle.kts:29` configures `release { signingConfig = signingConfigs.getByName("debug") }`, so Gradle outputs `app-release.apk` and `app-benchmark.apk`, not `app-release-unsigned.apk`.
- **Fix:** Use the exact paths from `.github/workflows/android.yml:45-51`:
  - `python3 tools/android/check_no_trial_code.py android/app/build/outputs/apk/release/app-release.apk`
  - `python3 tools/android/check_page_alignment.py android/app/build/outputs/apk/release/app-release.apk android/app/build/outputs/apk/benchmark/app-benchmark.apk`

### R9-M1. [Major] Valley-probe commands referenced in Task 6 (line 343) and Task 9 (line 383) are not literal commands in `map-trial-build.md` lines 351–356, and `BreachDepressionsLeastCost` requires `--dist`

- **Scope:** Task 6 Step 2 (line 343), Task 9 Step 1 (line 383); `docs/design/map-trial-build.md` (lines 351–356).
- **Finding:** `map-trial-build.md:351-356` has only a two-bullet prose summary, and in WhiteboxTools v2.4.0 `--run=BreachDepressionsLeastCost` requires `--dist=<cells>` (whereas `--run=BreachDepressions` takes only `--dem` and `--output`, and `--run=D8FlowAccumulation` takes `--input` and `--output`).
- **Fix:** Write out the exact `gdalwarp` (`-t_srs EPSG:3857 -tr 90 90 -r bilinear` and `-tr 180 180 -r bilinear`) and `whitebox_tools` (`--run=BreachDepressions` and `--run=D8FlowAccumulation`) container commands and `/sys/fs/cgroup/memory.peak` + `memory.events` checks in Task 6 and Task 9.

### R9-M2. [Major] Task 4 Step 1 misses the second pipeline-order assertion in `tools/map/tests/test_cli.py` (line 230)

- **Scope:** Task 4 Step 1 (line 276); `tools/map/tests/test_cli.py` (lines 112–141, 163, 230).
- **Finding:** `_stub_pipeline` (`test_cli.py:112`) is also called by `test_sibling_outline_inside_data_root_runs_fetch_and_build` (`test_cli.py:224`), which asserts `build_calls == ["check lock", "block", "tile", "pack", "verify", "report", "remove"]` at line 230.
- **Fix:** Update `_stub_pipeline` to stub `cli.build_base_layer` (raising `BaseError("stub failure")` when `fail_at == "base"`) and update both `test_build_pipeline_order` (line 163) and `test_sibling_outline_inside_data_root_runs_fetch_and_build` (line 230) to expect `["check lock", "block", "tile", "pack", "verify", "base", "report", "remove"]`.

### R9-M3. [Major] Single-file GeoJSONSeq concatenation for `tippecanoe`, lower-dimension geometry filtering after clip/erase, and exact `gdal vector layer-algebra` flags in Task 3

- **Scope:** Task 3 (lines 231, 234, 238–239).
- **Fix:**
  1. Append per-block EPSG:4326 `GeoJSONSeq` features in `(y, x)` order into a single `.geojsonl` file per band (`wbm_land_6.geojsonl` and `wbm_coast_6.geojsonl`) rather than passing 1,400 block file paths on the `tippecanoe` command line.
  2. Filter `land` geometries to polygonal parts (`ogr.ForceToMultiPolygon` after `MakeValid()`, keeping `Area() > 0`) and `coast` geometries to linear parts (`ogr.ForceToMultiLineString`, keeping `Length() > 0`).
  3. Specify exact `gdal vector layer-algebra` syntax: `gdal vector layer-algebra --operation=erase --no-input-field --overwrite --input=<src.geojson> --method=<hole.geojson> --output=<dst.geojson>`.

### R9-M4. [Major] Vacuous pass risk in `test_example_base_has_no_inland_edge_coast` and slow zoom range in `test_base.py`

- **Scope:** Task 3 Step 1 (line 254) and Task 4 Step 1 (line 281).
- **Fix:**
  1. Require `test_example_base_has_no_inland_edge_coast` to also assert that at least one decoded zoom-12 `coast` segment lies inside `profile.data_extent`.
  2. In `test_base.py`, use `max_zoom=6, block_zoom=6` for the synthetic `Profile` so the non-slow double build tiles only zooms `0..6` in under a second.

### R9-M5. [Major] Task 8 Step 4 execution order will trip `run.py`'s `check_clean` if lock files are copied into `seerah-content` between `fetch` runs

- **Scope:** Task 8 Step 4 (line 379); `tools/map/run.py` (lines 57–65, 229–230).
- **Fix:** Run all four `fetch` commands first (writing `map-data/dist/{window-spike,window-najd,window-yemen,full}.lock` on the host), then copy all four `.lock` files into `../seerah-content/map/trial/` and commit them together.

### R9-N1. [Nit] Exact tool licences for `THIRD_PARTY_NOTICES.md`, relative link path in `docs/spike/measurements.md`, and stale Phase 1 intro in `tools/map/README.md`

- **Scope:** Task 6 Step 2 (lines 343, 349, 350).
- **Fix:** List the exact licences for the eight map build tools (GDAL: MIT; `tippecanoe`: BSD-2-Clause; `go-pmtiles`: BSD-3-Clause; Protomaps `pmtiles`: BSD-3-Clause; `scipy`: BSD-3-Clause; `morecantile`: MIT; `ruamel.yaml`: MIT; `WhiteboxTools`: MIT), give the relative link `../../android/spike/spike/src/main/kotlin/app/seerah/spike/Stats.kt` for `docs/spike/measurements.md`, and update line 3 of `tools/map/README.md`.

### R9-N2. [Nit] Unspecified `log: Callable[[str], None]` behaviour in `build_base_layer`

- **Scope:** Task 3 (line 227) and Task 4 (line 270).
- **Fix:** Have `build_base_layer` call `log("base stage started")` on entry (matching `tilestage.py`), while `cli._cmd_build` logs `base done: <path>` on completion.

---

## Part 2: `egm-codebase-details` (Pedantic Technical Quality Auditor)

### CD9-B1. [Blocker] Non-existent release APK filename `app-release-unsigned.apk` in Task 5, Step 4

- Same as **R9-B2** (`android/app/build/outputs/apk/release/app-release.apk` and `android/app/build/outputs/apk/benchmark/app-benchmark.apk`).

### CD9-M1. [Major] `test_cli.py` second `build_calls` assertion (`test_sibling_outline_inside_data_root_runs_fetch_and_build`) and `cli.py` docstring/help text

- Same as **R9-M2**, plus updating `cli.py:7-8` module docstring (to include `BaseError`) and `cli.py:290` `build` subparser `help` text (`"build the relief and base archives"`).

### CD9-M2. [Major] `StyleJson.kt`, `MapFiles.kt`, `MapStyleSpec.kt`, and `android/core/map/README.md` KDoc / test-helper details in Task 5

- **Scope:** Task 5 (lines 317, 320) vs. `MapFiles.kt:8-11`, `MapStyleSpec.kt:39-40`, `StyleJson.kt:8, 31-32, 45`, `StyleJsonTest.kt:24-29, 41-42`, `android/core/map/README.md:15, 30`.
- **Fix:**
  - Update KDoc on `MapFiles.kt`, `MapStyleSpec.kt:39-40`, and `StyleJson.kt:31-32`.
  - In `StyleJsonTest.kt`, note that `spec()`'s test fixture uses `sea = 0xFF9FC3D8.toInt()` (`"rgba(159, 195, 216, 1)"`), `land = 0xFFF1E6C8.toInt()` (`"rgba(241, 230, 200, 1)"`), and `coast = 0xFF5B7F95.toInt()` (`"rgba(91, 127, 149, 1)"`), and that `JSONObject.paint(layer, key)` calls `.getJSONArray(key).toString()` (used for `"line-width"` on `"coast"`), while string and boolean paint properties use `layer(id).getJSONObject("paint").getString(...)` / `.getBoolean(...)`.
  - In `android/core/map/README.md`, update line 15 and remove line 30's "What is not here yet" bullet for `MapFiles.base`.

### CD9-N1. [Nit] `tools/map/README.md` line 3 and line 81 still describe a Phase-1-only single-archive tool

- Same as **R9-N1** (update `tools/map/README.md` line 3 and line 81).

### CD9-N2. [Nit] `THIRD_PARTY_NOTICES.md` line 20–24 section heading for MapLibre Native Android

- Same as **R9-N1** (update `THIRD_PARTY_NOTICES.md:20-24` so MapLibre Native Android is noted as used by the technical spike and `:core:map` / `benchmark`, and not shipped in `release` yet).
