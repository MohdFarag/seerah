# Map trial, phase 3: base layer, trial inputs and documents

- **Author:** MohdFaragDev
- **Status:** Revised after an [EGM review](egm-review-map-trial-phase-3/consolidated_review_report.md); the owner approved the plan and decisions D1–D5 on 2026-11-20.
- **Date:** 2026-11-20

> For agentic workers: use the `superpowers:subagent-driven-development` skill (recommended) or `superpowers:executing-plans` to carry out this plan task by task. Steps use checkboxes (`- [ ]`) for tracking.

**Goal:**

- Add `tippecanoe` (`tile-join`) and `WhiteboxTools` (`whitebox_tools`) to the pinned map toolchain image.
- Build the vector base layer (`base.pmtiles`: `land` polygons and `coast` lines from the Copernicus Water Body Mask (WBM) inside the data extent, joined with Natural Earth beyond it) and enforce [ADR 0009](../adr/0009-map-data-pipeline.md) decision 7's coverage checks.
- Wire `base.pmtiles` into `:core:map` and the `benchmark` trial app, closing phase 2 D1 so the map draws sea, land, coast and relief together.
- Complete the remaining phase 3 repository documentation and prepare the private `seerah-content/map/trial/` inputs behind owner gates.

**Where this fits.** The approved [specification](map-trial-build.md) has three phases; its Problem, Goals, Alternatives (A1–A19) and Known risks apply here unchanged. [Phase 1](map-trial-phase-1-plan.md) built the map tool's relief pipeline and synthetic example (`tools/map/`, `map-data/example/`), writing `relief.pmtiles`, `metadata.json` and `report.json`, and saving smoothed land-mask blocks in `mask/<id>.tif` plus `mask.vrt` for this phase. [Phase 2](map-trial-phase-2-plan.md) added `:core:map` and the `benchmark` build type with a temporary land-coloured background (phase 2 D1). Phase 3 finishes [trial step 1's](../map-trial/step-1.md) foundations: `base.pmtiles`, the base-layer Android wiring, the private trial profiles and edits, and the remaining documentation.

**Architecture:**

- **Toolchain image (`tools/map/Dockerfile`):** adds two multi-stage build stages on top of the phase 1 image: `tippecanoe` (plus `tile-join`) built from a pinned GitHub release commit, and `whitebox_tools` built from the pinned `v2.4.0` GitHub commit (D1). Only the compiled binaries and their licence files are copied into the runtime stage, placed before `COPY requirements.txt` so Python updates do not rebuild C++ or Rust.
- **Geometry and coverage (`grid.py`):** pure Web Mercator (`EPSG:3857`) functions compute `view_bounds`, `shrink_extent`, `natural_earth_max_zoom`, and both [ADR 0009](../adr/0009-map-data-pipeline.md) decision 7 coverage checks.
- **Base layer (`base.py`):** runs after `pack` and before `write_report` and work-area removal. Inside the data extent, it builds land polygons (`gdal raster polygonize`) for three zoom bands (`0–2`, `3–5`, `6..max_zoom`, cut on the block grid) and coastlines (`gdal_contour -fl 128` on `Int16` nodata-masked rasters, avoiding false edge lines). Outside the data extent (shrunk by two zoom-12 pixels), it erases that box from cached Natural Earth 1:110m (`0–2`), 1:50m (`3–5`) and regionally clipped 1:10m (`6..natural_earth_max_zoom`), and splits 50m/10m land polygons on a global 10° grid in-process with OGR. It tiles each non-empty slice with `tippecanoe`, joins all slices with `tile-join`, rewrites deterministic `"trial": true` metadata from an allowlist with `pmtiles edit`, verifies the archive with `pmtiles verify` and `show`, and writes `base.pmtiles` atomically to the output folder.
- **Android `:core:map` (`[ADR 0003](../adr/0003-v1-architecture-and-modules.md)`) and `benchmark`:** `MapFiles` gains a required `base: File`; `StyleJson` switches the background layer from `colours.land` to `colours.sea` and inserts vector source `"base"` with `"land"` fill (`fill-antialias: false`) and `"coast"` line layers below `"relief"`; `TrialFiles` requires `base.pmtiles` in each variant folder and `TrialActivity` passes both files.
- **Private trial data (`seerah-content/map/trial/`):** kept outside the public repository ([ADR 0006](../adr/0006-open-source-code-and-closed-content.md)), drafted behind owner gates after the public PR merges.

**Tech stack:** GDAL 3.13.3 (`gdal raster polygonize`, `gdal_contour`, `gdal vector layer-algebra`, `gdal vector reproject`, `ogr2ogr`), `felt/tippecanoe` 2.79.0 (`tippecanoe`, `tile-join`), `go-pmtiles` v1.31.2 (`pmtiles edit`, `verify`, `show`), `jblindsay/whitebox-tools` v2.4.0 (`whitebox_tools`), Python 3.11+ (`seerah-map:dev` ships 3.14.4), Kotlin / Compose / MapLibre Native Android 13.7.1, JUnit 4 + Robolectric 4.17.

**Spec:** [docs/design/map-trial-build.md](map-trial-build.md) (Approved, 2026-11-14), phase 3, spec files 32–44; [The base layer](map-trial-build.md#the-base-layer), [The app](map-trial-build.md#the-app), [Valleys](map-trial-build.md#valleys), and the [Verification Plan](map-trial-build.md#verification-plan).

## Facts checked (2026-11-20)

1. **`felt/tippecanoe` 2.79.0:** BSD-2-Clause, published 2025-07-24 (> 14 days old), commit `68ab8dcc229f95b8b25877697d5e8d66783af503`. The base image `ghcr.io/osgeo/gdal:ubuntu-small-3.13.3` runs Ubuntu 26.04 LTS (`gcc-16-base`) and already ships runtime shared libraries `libsqlite3-0`, `zlib1g` and `libstdc++6`, so binaries compiled against the same base need no extra runtime apt packages. Fallback pinned commit if GCC 16 rejects 2.79.0: `4f2621186acfec33b63ddf636f665623c0fef2dd` (2026-08-14, > 14 days old, includes GCC warning/defect fixes from PR #406).
2. **`jblindsay/whitebox-tools` v2.4.0:** MIT, published 2024-05-22, commit `28ed01f506f2024cfb52db85348b887f2ded4e5d`. Its GitHub release has **no binary assets** (`assets: []`), and the repository was marked legacy on 2026-05-26 (development moved to `whitebox_next_gen`). The WhiteboxGeo Linux archive URL (`https://www.whiteboxgeo.com/WBT_Linux/WhiteboxTools_linux_amd64.zip`) is unversioned and returned HTTP 403 to `curl`. Package `whitebox_tools` in `whitebox-tools-app/Cargo.toml` is pure Rust (zero C/OpenSSL deps when built without `whitebox-runner`) and contains `breach_depressions.rs`, `breach_depressions_least_cost.rs` and `d8_flow_accum.rs`.
3. **GDAL 3.13.3 base-layer tools in `seerah-map:dev`:** `gdal raster polygonize`, `gdal_contour`, `gdal vector layer-algebra --operation=erase`, `gdal vector clip`, `gdal vector reproject`, `ogr2ogr`, GEOS geometry operations, and the native PMTiles vector driver (`gdal.OpenEx(..., gdal.OF_VECTOR, open_options=["ZOOM_LEVEL=12"])`) are all present and verified.
4. **`gdal_contour` and internal mask bands (probed in `seerah-map:dev`):** `gdal_contour -fl 128` ignores GDAL internal per-dataset mask bands (`GMF_PER_DATASET`) and draws a false contour line along the data-extent edge where land (`255`) meets outside (`0`). Passing `-inodata` makes it ignore `NoDataValue` as well. Warping `mask.vrt` with `gdal.Warp(..., outputType=gdal.GDT_Int16, dstNodata=-1, resampleAlg="average")` turns masked outside pixels into `-1` while keeping valid `0..255` values; running `gdal_contour -fl 128` **without** `-inodata` on that `Int16` raster skips `-1` and draws zero false edge contours.
5. **`gdal raster polygonize` with `NoDataValue=0` (probed in `seerah-map:dev`):** thresholding a valid mask to `GDT_Byte` (`1` where `valid` and `mask >= 128`, `0` with `NoDataValue=0` elsewhere) emits only land polygons (`DN = 1`).
6. **Natural Earth erase (probed in `seerah-map:dev`):** all six Natural Earth GeoJSON files are already cached by phase 1's `fetch` at `/cache/natural-earth/f1890d9f152c896d250a77557a5751a93d494776/ne_{110m,50m,10m}_{land,coastline}.geojson`. `gdal vector layer-algebra --operation=erase --no-input-field` erased a box from `ne_110m_land` (0.04 s, 127 features), `ne_10m_land` (1.10 s, 11 multipolygon features) and `ne_10m_coastline` (0.83 s, 4,133 features) with zero errors.
7. **`pmtiles edit --metadata` and `atomic_write` suffix:** `go-pmtiles` v1.31.2 supports `pmtiles edit --metadata <file> <archive>`. `workarea.atomic_write` appends `.tmp`, whereas `tippecanoe` and `tile-join` detect PMTiles output from the `.pmtiles` filename extension (otherwise writing MBTiles) and `/work` and `/out` are distinct mounts. Staging in `work.base_dir / "base.pmtiles"`, rewriting metadata in place, and copying via `atomic_write(out_dir / "base.pmtiles")` avoids both hazards.
8. **Phase 2 code state (`main` at `7e1b406`):** `MapFiles` takes only `relief: File`; `StyleJson` draws `"background"` with `colours.land` and `"relief"` over it; `Views.kt` already sets `TRIAL_COLOURS` (`SEA = 0xFF809B94`, `LAND = 0xFFF1E6C8`, `COAST = 0xFF6F8F88`); `TrialFiles.loadVariants` checks only `relief.pmtiles`; `TrialActivity` has one `MapFiles(...)` call; `:app:assembleRelease` and `:app:assembleBenchmark` output `app-release.apk` and `app-benchmark.apk`.
9. **Spec phase 3 files already done:** spec files 35 ([ADR 0007](../adr/0007-content-formats-compatibility-and-signing.md)), 36 ([ADR 0004](../adr/0004-build-and-quality-tooling.md)), 37 ([ADR 0009](../adr/0009-map-data-pipeline.md)) and 44 (`.github/dependabot.yml` + design docs) are already merged on `main`. Spec files 39 (`docs/roadmap.md`) and 42 (`tools/README.md`, `map-data/README.md`) are partial; spec files 38 (`docs/open-decisions.md`), 40 (`docs/spike/measurements.md`), 41 (`docs/map-trial/README.md` + `docs/README.md` index) and 43 (`THIRD_PARTY_NOTICES.md`) remain.
10. **Device availability ([docs/map-trial/step-1.md](../map-trial/step-1.md)):** as of 2026-11-20, the Oppo A55 and Huawei MatePad 11.5 are temporarily unavailable; trial step 1, check 11 has measured only the Galaxy A54 (384 dp portrait, 832 dp landscape). The MatePad's ~550 dp half-width from the spec remains the working constant until check 11 completes.

## Global Constraints

### Terms

- [The base layer](map-trial-build.md#the-base-layer), [The app](map-trial-build.md#the-app), [Valleys](map-trial-build.md#valleys) and [Step 1 checks](map-trial-build.md#step-1-checks) refer to the sections of [map-trial-build.md](map-trial-build.md).
- **Zooms:** build zooms are 256-pixel tile zooms; MapLibre zooms equal build zoom − 1 ([Zoom conventions](map-trial-build.md#zoom-conventions)).
- **Data extent:** `profile.data_extent` (`camera_bounds` widened by `margin_deg` on each side).
- **Numbering:** "trial item N" means [ADR 0009](../adr/0009-map-data-pipeline.md) decision 13's eleven items; "trial step 1, check N" means the checks table in [docs/map-trial/step-1.md](../map-trial/step-1.md); "spec file N" means files 32–44 of [map-trial-build.md](map-trial-build.md); "spike build 11" means the approved prototype look ([findings](../spike/findings.md)).

### Paths, commands and pinning

- **Paths:** relative to the public repository root, except `/` (container paths) and `seerah-content/` (the private repository).
- **MAPTEST** (from repository root, `<args>` relative to `tools/map`):

  ```sh
  docker run --rm --security-opt label=disable --memory 6g \
    -v "$PWD:/src:ro" -v "$PWD/map-data:/data:ro" -v seerah-map-test-cache:/cache \
    -w /src/tools/map -e PYTHONPATH=/src/tools/map/src \
    seerah-map:dev python3 -m pytest <args>
  ```

- **HOSTTEST:** `python3 -m unittest discover -s tools/map/host_tests`.
- **GRADLE:** `cd android && ./gradlew <tasks> --console=plain`.
- **Pinning:** every base image, source archive and Rust builder image is pinned by digest or SHA-256 and uses a release at least 14 days old (phase 1 pinning rule).
- **Determinism (G4):** the same inputs on the same machine and image give identical bytes for **both** `relief.pmtiles` and `base.pmtiles`.
- **Trial marking (G9):** both `relief.pmtiles` and `base.pmtiles` carry `"trial": true` in PMTiles metadata, and `metadata.json` carries `"trial": true`.

### Code rules

- Python 3.11+ syntax, repo ruff config (`pyproject.toml`), docstrings stating inputs, outputs and units on every module and public function.
- Kotlin to [ADR 0004](../adr/0004-build-and-quality-tooling.md) (Spotless, ktlint, Compose Rules, warnings are errors), KDoc on public types, named constants in production code.
- **No spike code is copied.** `tools/spike/build_base.sh` and `android/spike/.../MapStyle.kt` are read only for lessons and visual values.
- **No private content in the public repository** ([ADR 0006](../adr/0006-open-source-code-and-closed-content.md), [AGENTS.md](../../AGENTS.md)). Only the owner marks edits `approved`.

### Process

- **Owner gates:** an agent stops at every step marked **Owner gate**.
- **Commits:** Conventional Commits authored as `MohdFarag`, no attribution lines, scope `map` (or `docs`), one commit per task on a working branch. Never commit on `main`; push, PR creation and merge require explicit owner approval (confirm `gh auth status` shows `MohdFarag` active before pushing or opening a PR).

## Decisions (owner, 2026-11-20)

| # | Decision | Recommendation (approved 2026-11-20) |
| --- | --- | --- |
| D1 | **How `WhiteboxTools` enters the image.** Spec line 175 says "release binaries, checked by SHA-256", but GitHub `v2.4.0` has no binary assets and `whiteboxgeo.com`'s zip URL is unversioned and returned HTTP 403 (fact 2). | Build `whitebox_tools` (`-p whitebox_tools`) from the pinned `v2.4.0` GitHub source archive (`28ed01f506f2024cfb52db85348b887f2ded4e5d`) with `--locked` in a pinned multi-stage Rust builder, copying only `/usr/local/bin/whitebox_tools` and `LICENSE.txt`. Rejected: unversioned `whiteboxgeo.com` zip (breaks reproducibility/CI), and moving to `whitebox_next_gen` mid-trial. |
| D2 | **Base layer vector schema and Android wiring.** Phase 2 D1 deferred `MapFiles.base` and the `sea`/`land`/`coast` layers until phase 3 fixed the vector schema. | Source `"base"` (`base.pmtiles`), layers `"land"` (fill, `colours.land`, `fill-antialias: false`) and `"coast"` (line, `colours.coast`, width interpolated `3 -> 0.4, 10 -> 1.2` as in spike build 11, [findings](../spike/findings.md)) over a `"background"` layer in `colours.sea`, all beneath `"relief"`. Make `MapFiles(relief: File, base: File)` non-null and require `base.pmtiles` in `TrialFiles`. Rejected: nullable `base` (keeps dead fallback code) or a separate phase 4 for Android. |
| D3 | **Natural Earth upper zoom and regional clipping.** Spec line 324 sets build zoom 9 for a 1° margin (~550 dp half-screen), while `map-data/example/` has `margin_deg: 0`, where the view passes the data extent at every zoom. | Compute `natural_earth_max_zoom(camera_bounds, margin_deg, max_zoom, half_width_dp=550.0)` in `grid.py` (no new profile YAML key): returns 9 for `full.yaml` and `max_zoom` (12) when `margin_deg == 0`. Tile 1:110m (`0–2`) and 1:50m (`3–5`) globally; for 1:10m, tile `6..min(9, ne_max_zoom)` clipped to the build-zoom-6 (MapLibre zoom 5) view-reach box around `camera_bounds`, and when `ne_max_zoom > 9`, tile `10..ne_max_zoom` clipped to the build-zoom-10 (MapLibre zoom 9) view-reach box, erasing the shrunk data extent from every scale. Rejected: tiling the whole world or the zoom-6 reach box at zoom 12 in CI, or adding a manual `natural_earth_max_zoom` field in profiles. |
| D4 | **Stage order and `report.json` shape.** Spec line 304 builds the base layer from the mask blocks before removing the work area, and line 338 records wall time of each stage. | Run `base` immediately after `pack` and `verify`, before `write_report` and `remove(area)`, adding `"base"` to `report.json`'s `stage_seconds` (`{"block", "tile", "pack", "base"}`). Rejected: omitting base timing or running base after `write_report`. |
| D5 | **Private repo work, per-zoom mode, and valley probe.** Spec file 34 lives in `seerah-content`, per-zoom mode was deferred in phase 1 D5, and Oppo A55 / MatePad 11.5 are unavailable. | Keep the public phase 3 PR self-contained and CI-green on `map-data/example/` (Tasks 1–7), then draft `seerah-content/map/trial/` behind owner gates (Task 8) and run the documented valley probe once the new image is published (Task 9). Keep `--mode per-zoom` as its own follow-up before trial step 2. |

Approving this plan approves D1–D5 and the deviations below. Tasks 7, 8 and 9 keep their owner gates.

## Deviations from the specification

- **`WhiteboxTools` built from source (D1)** instead of downloading a prebuilt binary, because no pinned GitHub release binary exists.
- **`tippecanoe` and `WhiteboxTools` added in phase 3** (deferred from phase 1 per phase 1 D6).
- **Android `:core:map` and `benchmark` updates moved into phase 3 (D2)** (deferred from phase 2 per phase 2 D1).
- **Natural Earth 1:10m clipped by sub-band (D3):** `6..min(9, ne_max_zoom)` clipped to the build-zoom-6 (MapLibre zoom 5) view-reach box, and `10..ne_max_zoom` clipped to the build-zoom-10 (MapLibre zoom 9) view-reach box when `ne_max_zoom > 9`, so zero-margin windows like `map-data/example/` tile up to `max_zoom` without tiling a 550 sq. deg. region to zoom 12 in CI.
- **Per-zoom mode remains deferred (D5)** until its follow-up before trial step 2.
- **Spec files 35, 36, 37 and 44 are already complete** on `main` (fact 9) and need no edits.
- **Carried over from phase 2:** Task 6 adds the `tools/README.md` row for `tools/android/check_no_trial_code.py` (spec file 42) and updates `tools/android/check_page_alignment.py`'s row to include `benchmark`.
- **Trial step 1, check 11 (MatePad width) remains deferred** until the device is available (fact 10); `WIDEST_HALF_SCREEN_DP = 550.0` is kept as one named constant in `grid.py` so updating it later is a one-line change.

## Review Focus

Five failure modes this phase must prevent, each pinned by a test:

1. **False coastline along the data-extent edge:** `gdal_contour` ignoring the internal mask band draws a line where land meets the data boundary (fact 4). Caught by `test_contour_skips_data_edge` (unit) and `test_example_base_has_no_inland_edge_coast` (end-to-end on the `map-data/example/` Masirah Island window).
2. **Dropped land polygons in tippecanoe or empty WBM coast on inland windows:** tiling unsplit Eurasia+Africa polygons caused rectangular sea holes in spike build 4 ([findings](../spike/findings.md)), and 100% inland profiles (`window-najd`, `window-yemen`) produce zero WBM coastline features. Caught by 10° cell splitting on 50m/10m land (`test_split_land_preserves_area_and_clips_to_cells`) and skipping empty slices (`test_all_land_mask_skips_empty_wbm_coast_slices`).
3. **Seam, coarse-pixel trench, or 1×1 collapse between WBM and Natural Earth:** Natural Earth is erased by `profile.data_extent` **shrunk** by 2 zoom-12 pixels, while low-zoom (`0–2`, `3–5`) WBM warps lock `outputBounds` to `profile.data_extent` in `EPSG:3857` with a minimum 64 × 64 grid so edge land reaches `profile.data_extent` and small windows never collapse. Caught by `test_shrink_extent_stays_inside_data_extent`, `test_low_zoom_warp_preserves_small_window_and_data_extent`, and `test_erase_leaves_overlap_ring`.
4. **Non-deterministic `base.pmtiles` or missing `"trial": true`:** `tippecanoe` embeds command lines/temp paths in metadata and spawns multiple threads unless pinned. Setting `TIPPECANOE_MAX_THREADS=1`, rewriting metadata from a strict allowlist via `pmtiles edit`, and checking SHA-256 equality across two scratch builds in `test_double_build_identical` pins determinism and `"trial": true`.
5. **Missing `base.pmtiles` crashing the benchmark app or leaking `:core:map` into `release`:** `TrialFilesTest` pins skipping folders without `base.pmtiles`, `StyleJsonTest` pins sea/land/coast/relief layer order and `fill-antialias: false`, and `check_no_trial_code.py` + `DependencyRules.kt` keep `release` clean.

## File structure

```text
tools/map/
  Dockerfile                                  tippecanoe + WhiteboxTools stages, thread env vars (Task 1)
  README.md                                   base.pmtiles, image tools, valley probe commands (Task 6)
  src/seerah_map/
    grid.py                                   natural_earth_max_zoom, view_bounds, shrink_extent, coverage checks (Task 2)
    workarea.py                               WorkArea.base_dir (Task 3)
    base.py                                   base layer pipeline & verify_base (Task 3)
    report.py                                 stage_seconds.base (Task 4)
    cli.py                                    run base stage after pack, handle BaseError (Task 4)
  tests/
    test_image.py                             tippecanoe, tile-join, gdal_contour, whitebox_tools, thread env (Task 1)
    test_grid.py                              natural_earth_max_zoom, view_bounds, shrink_extent, coverage checks (Task 2)
    test_workarea.py                          WorkArea.base_dir creation (Task 3)
    test_base.py                              unit + synthetic integration tests for base.py (Task 3)
    test_cli.py, test_report.py               base stage order, error exit 1, stage_seconds.base (Task 4)
    test_build_example.py                     base.pmtiles checks, coast edge, determinism (Task 4)
android/
  core/map/
    README.md                                 update for MapFiles.base and base layers (Task 5)
    src/main/kotlin/app/seerah/map/
      MapFiles.kt, MapStyleSpec.kt            non-null base: File, updated KDoc (Task 5)
      StyleJson.kt                            base vector source, sea/land/coast/relief layers (Task 5)
    src/test/kotlin/app/seerah/map/
      StyleJsonTest.kt                        4-layer order, sea background, land/coast paint (Task 5)
  app/src/benchmark/kotlin/app/seerah/trial/
    TrialFiles.kt, TrialActivity.kt           require base.pmtiles, pass MapFiles(relief, base) (Task 5)
  app/src/testBenchmark/kotlin/app/seerah/trial/
    TrialFilesTest.kt                         base.pmtiles present/missing cases (Task 5)
docs/
  README.md                                   index docs/map-trial/ and design plans (Task 6)
  roadmap.md                                  fix ADRs 0003/0004 status, update map trial phases (Task 6)
  open-decisions.md                           trial-0 note and trial working values note (Task 6)
  spike/measurements.md                       mark line 7 out of date, point to Stats.kt (Task 6)
  map-trial/README.md                         eleven items table and step report index (Task 6)
  map-trial/step-1.md                         valley probe result (Task 9)
tools/README.md                               check_no_trial_code.py row, update map/ and alignment rows (Task 6)
map-data/README.md                            add base.pmtiles to output folder layout (Task 6)
THIRD_PARTY_NOTICES.md                        map build tools and data table (Task 6)
seerah-content/map/trial/                     (private repository, Task 8 — owner gate)
  full.yaml, window-spike.yaml, window-najd.yaml, window-yemen.yaml
  edits.yaml, camera-path.json, peninsula.geojson, README.md, <profile>.lock
```

## Tasks

### Task 1: Add `tippecanoe` and `WhiteboxTools` to the toolchain image (spec file 1 carryover)

**Files:**

- Modify: `tools/map/Dockerfile`, `tools/map/tests/test_image.py`

**Interfaces:**

- Consumes: pinned source archives for `felt/tippecanoe` (`2.79.0`) and `jblindsay/whitebox-tools` (`v2.4.0`).
- Produces:
  - `/usr/local/bin/tippecanoe`, `/usr/local/bin/tile-join`, `/usr/local/bin/whitebox_tools` and their licences under `/usr/share/licenses/{tippecanoe,whitebox-tools}/`;
  - `ENV` additions `TIPPECANOE_MAX_THREADS=1` and `RAYON_NUM_THREADS=1`;
  - an updated image tag from `python3 tools/map/run.py --print-tag`.

- [ ] **Step 1: Resolve pins and checksums.**
  - Download the GitHub source archives for `felt/tippecanoe` commit `68ab8dcc229f95b8b25877697d5e8d66783af503` (tag `2.79.0`) and `jblindsay/whitebox-tools` commit `28ed01f506f2024cfb52db85348b887f2ded4e5d` (tag `v2.4.0`) to a temporary directory and compute their SHA-256 digests.
  - Pick the newest stable `rust:<version>-bookworm` image at least 14 days old and resolve its multi-arch digest via `docker buildx imagetools inspect` (Bookworm's glibc 2.36 is forward-compatible with Ubuntu 26.04's glibc 2.43).
- [ ] **Step 2: Write the failing tests** in `tools/map/tests/test_image.py`:
  - Extend `test_single_thread_environment` to assert `os.environ["TIPPECANOE_MAX_THREADS"] == "1"` and `os.environ["RAYON_NUM_THREADS"] == "1"`.
  - Add `test_base_and_valley_tools_present`: assert `shutil.which` finds `tippecanoe`, `tile-join`, `gdal_contour` and `whitebox_tools`, each runs `--version` with exit code 0, and `/usr/share/licenses/tippecanoe/LICENSE.md` and `/usr/share/licenses/whitebox-tools/LICENSE.txt` exist.
- [ ] **Step 3: Run** MAPTEST `tests/test_image.py` against the existing image. Expected: FAIL (`tippecanoe` and `whitebox_tools` absent).
- [ ] **Step 4: Update `tools/map/Dockerfile`** with two build stages before the final runtime stage:
  - Stage `tippecanoe-build`: `FROM ghcr.io/osgeo/gdal:ubuntu-small-3.13.3@sha256:64250faf...`, install `g++ make libsqlite3-dev zlib1g-dev`, `ADD --checksum=sha256:...` the pinned `tippecanoe` tarball, build `make -j"$(nproc)" tippecanoe tile-join`. If GCC 16 fails to compile `2.79.0`, use fallback commit `4f2621186acfec33b63ddf636f665623c0fef2dd` (fact 1) and record why in a Dockerfile comment.
  - Stage `whitebox-build`: `FROM rust:<pinned>@sha256:...`, `ADD --checksum=sha256:...` the pinned `whitebox-tools` tarball, run `cargo build --release --locked -p whitebox_tools`.
  - In the final runtime stage, place `COPY --from=tippecanoe-build` (binaries + `/usr/share/licenses/tippecanoe/LICENSE.md`), `COPY --from=whitebox-build` (`/usr/local/bin/whitebox_tools` + `/usr/share/licenses/whitebox-tools/LICENSE.txt`), and `go-pmtiles` **before** `COPY requirements.txt` and `pip install`, so Python package updates do not invalidate the C++/Rust layers.
  - Add `TIPPECANOE_MAX_THREADS=1 RAYON_NUM_THREADS=1` to the `ENV` line, and keep a comment per pin giving source, version/commit and date.
- [ ] **Step 5: Build and verify:** `docker build -t seerah-map:dev tools/map`, then run MAPTEST `tests/test_image.py` and `HOSTTEST`. Also run `docker run --rm seerah-map:dev bash -c "ldd /usr/local/bin/whitebox_tools && whitebox_tools --toolparameters=BreachDepressions && whitebox_tools --toolparameters=D8FlowAccumulation"`. Expected: all pass.
- [ ] **Step 6: Commit** `build(map): add tippecanoe and WhiteboxTools to the toolchain image`.

### Task 2: Coverage checks and Natural Earth zoom limit (spec file 8 carryover)

**Files:**

- Modify: `tools/map/src/seerah_map/grid.py`, `tools/map/tests/test_grid.py`

**Interfaces:**

- Consumes: `Bounds`, `pixel_m`, `_TMS`, `_EQUATOR_M`, `_MAPLIBRE_TILE_PX`.
- Produces:
  - Constant `WIDEST_HALF_SCREEN_DP = 550.0` (comment citing MatePad working value and trial step 1, check 11).
  - Constant `TRIAL_MIN_MAPLIBRE_ZOOM = 4` (spec Q1 working value).
  - `view_bounds(camera: Bounds, maplibre_zoom: float, half_width_dp: float = WIDEST_HALF_SCREEN_DP) -> Bounds`: projects `camera` to Web Mercator metres (`_TMS.xy`), expands all four sides by `half_width_dp * (_EQUATOR_M / (_MAPLIBRE_TILE_PX * 2**maplibre_zoom))` metres, and unprojects back to degrees (`_TMS.lnglat`) clamped to `_TMS.bbox` (`[-180, -85.0511287798066, 180, 85.0511287798066]`).
  - `shrink_extent(extent: Bounds, pixels: int = 2) -> Bounds`: projects `extent` to Web Mercator metres (`_TMS.xy`), shrinks all four sides inward by `pixels * pixel_m()` metres, and unprojects back to degrees (`_TMS.lnglat`). Raises `ValueError` if `west >= east` or `south >= north`.
  - `natural_earth_max_zoom(camera: Bounds, margin_deg: float, max_zoom: int, half_width_dp: float = WIDEST_HALF_SCREEN_DP) -> int`: returns the highest build zoom `z` in `0..max_zoom` where `view_bounds(camera, max(0, z - 1), half_width_dp)` extends outside `camera` widened by `margin_deg`. Returns `max_zoom` when `margin_deg == 0`.
  - `check_data_extent_coverage(data_extent: Bounds, camera: Bounds, margin_deg: float) -> None`: verifies `data_extent` contains `camera` widened by `margin_deg` on all four sides ([ADR 0009](../adr/0009-map-data-pipeline.md) decision 7, part 1), raising `ValueError` if violated.
  - `check_base_coverage(camera: Bounds, margin_deg: float, ne_max_zoom: int, max_zoom: int, min_maplibre_zoom: int = TRIAL_MIN_MAPLIBRE_ZOOM, half_width_dp: float = WIDEST_HALF_SCREEN_DP) -> None`: verifies [ADR 0009](../adr/0009-map-data-pipeline.md) decision 7 (part 2) across MapLibre zooms `m` from `min_maplibre_zoom` to `max_zoom - 1`. At each zoom, either `view_bounds(camera, m, half_width_dp)` must stay inside `camera` widened by `margin_deg`, or build zoom `m + 1` must be `<= ne_max_zoom` so Natural Earth covers the outside; otherwise raises `ValueError` naming the uncovered build zoom `m + 1`.

- [ ] **Step 1: Write the failing tests** in `tools/map/tests/test_grid.py`:
  - `test_natural_earth_max_zoom_full_extent`: `Bounds(34, 12, 60, 32)`, `margin_deg=1.0`, `max_zoom=12` -> `9`.
  - `test_natural_earth_max_zoom_zero_margin`: `margin_deg=0.0`, `max_zoom=12` -> `12`.
  - `test_view_bounds_expands_and_clamps`: MapLibre zoom 5 expands beyond camera bounds; MapLibre zoom 0 clamps to `_TMS.bbox`.
  - `test_shrink_extent_stays_inside_data_extent`: shrunk box is strictly inside `extent` by 2 zoom-12 pixels in Web Mercator metres on all four sides; a 3-pixel box shrunk by 2 pixels raises `ValueError`.
  - `test_coverage_checks_pass_for_trial_and_example`: both `full` (1° margin, `ne_max_zoom=9`) and `example` (0° margin, `ne_max_zoom=12`) pass both checks; an undersized `data_extent` fails `check_data_extent_coverage`; passing `ne_max_zoom=8` for `full` raises `ValueError` naming build zoom `9`.
- [ ] **Step 2: Run** MAPTEST `tests/test_grid.py`. Expected: FAIL (`ImportError`).
- [ ] **Step 3: Implement** in `tools/map/src/seerah_map/grid.py` using `_TMS`, `_EQUATOR_M`, `_MAPLIBRE_TILE_PX` and `pixel_m()` (no magic numbers).
- [ ] **Step 4: Run** MAPTEST `tests/test_grid.py`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(map): add coverage checks and Natural Earth zoom limit`.

### Task 3: Base layer module (`base.py`) and `WorkArea.base_dir` (spec file 32)

**Files:**

- Modify: `tools/map/src/seerah_map/workarea.py`, `tools/map/tests/test_workarea.py`
- Create: `tools/map/src/seerah_map/base.py`, `tools/map/tests/test_base.py`

**Interfaces:**

- Consumes: `Profile`, `Lock`, `WorkArea`, `atomic_write`, `Provenance` (from `pack.py`), `NE_FILES` (from `fetch.py`), `grid` (`BASE_ZOOM`, `blocks`, `pixel_m`, `natural_earth_max_zoom`, `view_bounds`, `shrink_extent`, `check_data_extent_coverage`, `check_base_coverage`).
- Produces:
  - `WorkArea.base_dir` (`<area>/base/`), created by `open_area`.
  - `BASE_ARCHIVE_NAME = "base.pmtiles"`, `LAND_LAYER = "land"`, `COAST_LAYER = "coast"`, `MIN_WARP_PX = 64`.
  - `BaseError(Exception)`: raised when GDAL, `tippecanoe`, `tile-join` or `pmtiles` fails, when a Natural Earth file is missing from the lock or cache, or when `base.pmtiles` fails verification.
  - `build_base_layer(profile: Profile, lock: Lock, cache: Path, work: WorkArea, out_dir: Path, provenance: Provenance, log: Callable[[str], None]) -> Path`:
    1. Calls `log("base stage started")`, runs `check_data_extent_coverage(profile.data_extent, profile.camera_bounds, profile.margin_deg)` and `check_base_coverage(...)`, and wipes/recreates `work.base_dir` (`shutil.rmtree(work.base_dir, ignore_errors=True); work.base_dir.mkdir(parents=True, exist_ok=True)`) with `tmp_dir = work.base_dir / "tmp"`.
    2. **Low-zoom WBM land and coast (`0–2` at top zoom 2, `3–5` at top zoom 5):**
       - Projects `profile.data_extent` to `EPSG:3857` bounds `(west_m, south_m, east_m, north_m)` and computes `band_pixel_m = pixel_m() * 2**(BASE_ZOOM - band_max_zoom)`. Clamps target `(cols, rows)` to `min(full_z12_dim, max(MIN_WARP_PX, round(span_m / band_pixel_m)))` so small windows like `example.yaml` never collapse below 64 × 64 pixels and outer pixels align with `profile.data_extent`.
       - Warps `work.path / "mask.vrt"` to that grid (`outputBounds=(west_m, south_m, east_m, north_m)`, `outputType=GDT_Int16`, `dstNodata=-1`, `resampleAlg="average"`).
       - **Land:** if any valid pixel is `>= 128`, writes a binary `GDT_Byte` GeoTIFF (`1` where `>= 128`, `0` with `NoDataValue=0` elsewhere), runs `gdal raster polygonize`, reprojects to EPSG:4326 GeoJSON filtering polygonal parts (`ogr.ForceToMultiPolygon`, `Area() > 0`), and when `feature_count > 0` tiles with `tippecanoe -q --no-progress-indicator --force -t <tmp_dir> -Z<min> -z<max> -l land --no-tile-size-limit --no-feature-limit --no-tiny-polygon-reduction --exclude-all`.
       - **Coast:** if valid pixels contain both `< 128` and `>= 128`, pads a 1-pixel border of `-1` around the warped `Int16` array (shifting the geotransform origin by `(-dx, +dy)` with `NoDataValue=-1` so `gdal_contour` sees nodata immediately outside `profile.data_extent`), runs `gdal_contour -fl 128`, reprojects to EPSG:4326 filtering linear parts (`ogr.ForceToMultiLineString`, `Length() > 0`), and when `feature_count > 0` tiles with `tippecanoe` (`-l coast`, same flags).
    3. **High-zoom WBM land and coast (`6..max_zoom`, cut on the block grid):**
       - Iterates `blocks(profile.data_extent, profile.block_zoom)` in deterministic `(y, x)` order.
       - **Land:** reads each `work.mask_dir / f"{b.id}.tif"` and its internal mask band in numpy; skips the block if no valid pixel is `>= 128`. Otherwise writes a binary `GDT_Byte` (`NoDataValue=0`, `1` for valid land), runs `gdal raster polygonize`, reprojects to EPSG:4326 (`ogr.ForceToMultiPolygon`, `Area() > 0`), and appends features to a single combined `work.base_dir / "wbm_land_6.geojsonl"` file. When non-empty, tiles that single file with `tippecanoe` (`-Z6 -z<max_zoom> -l land`, same flags).
       - **Coast:** warps `work.path / "mask.vrt"` onto `b.window.pad(1)` in memory (`GDT_Int16`, `dstNodata=-1`, `resampleAlg="near"`); skips the block unless valid pixels (`>= 0`) contain both `< 128` and `>= 128` (skipping ~85–90% of `full.yaml` blocks). Otherwise writes the padded `Int16` GeoTIFF, runs `gdal_contour -fl 128`, clips to `b.window.bounds_3857()`, reprojects to EPSG:4326 (`ogr.ForceToMultiLineString`, `Length() > 0`), and appends features to a single combined `work.base_dir / "wbm_coast_6.geojsonl"` file. When non-empty, tiles that file with `tippecanoe` (`-Z6 -z<max_zoom> -l coast`, same flags).
    4. **Natural Earth beyond the data extent:**
       - Indexes `{Path(ref.path).name: cache / ref.path for ref in lock.natural_earth}` and raises `BaseError` if any of `fetch.NE_FILES` is absent from `lock.natural_earth` or missing on disk.
       - Writes `hole = shrink_extent(profile.data_extent, pixels=2)` as a temporary EPSG:4326 GeoJSON polygon.
       - Scale bands: `110m` (`0–2`, global), `50m` (`3–5`, global), and `10m` split into `6..min(9, ne_max_zoom)` clipped to `view_bounds(profile.camera_bounds, maplibre_zoom=5)` and, when `ne_max_zoom > 9`, `10..ne_max_zoom` clipped to `view_bounds(profile.camera_bounds, maplibre_zoom=9)`.
       - Erases `hole` with `gdal vector layer-algebra --operation=erase --no-input-field --overwrite --input=<src.geojson> --method=<hole.geojson> --output=<erased.geojson>`.
       - For `50m` and `10m` land, splits erased polygons in-process with `osgeo.ogr` on a global 10° × 10° EPSG:4326 grid (`-180..180` lon, `-90..90` lat), intersecting only the 10° cells overlapping each geometry's envelope (`geom.Intersection(cell).MakeValid()`, `ogr.ForceToMultiPolygon`, `Area() > 0`). For `110m` land and all coastline slices, filters valid polygonal or linear geometries (`Area() > 0` or `Length() > 0`).
       - Tiles each slice with `feature_count > 0` using `tippecanoe` (`-l land` or `-l coast`, same flags).
    5. **Join, allowlist metadata, verify and atomic copy:**
       - Raises `BaseError` if no slice produced tiles. Runs `tile-join --force --no-tile-size-limit --exclude-all -n <profile.name> -o <work.base_dir>/base.pmtiles <non-empty slice archives in fixed sorted order>`.
       - Reads `pmtiles show <staged> --metadata`, extracts `"vector_layers"` (keeping only `{"id", "fields": {}, "minzoom", "maxzoom"}` per layer, sorted by `"id"`), and writes back an allowlisted JSON object via `pmtiles edit --metadata` containing only `"name": profile.name`, `"trial": True`, `"fingerprint": provenance.fingerprint`, `"tool_commit": provenance.tool_commit`, `"data_commit": provenance.data_commit`, `"image": provenance.image`, and `"vector_layers"` (`json.dumps(..., indent=2, sort_keys=True)`).
       - Runs `verify_base(staged, profile)` and copies `staged` into `out_dir / "base.pmtiles"` via `atomic_write`.
  - `verify_base(pmtiles_path: Path, profile: Profile) -> None`: runs `pmtiles verify`, reads `pmtiles show <path> --header-json` and `pmtiles show <path> --metadata`, and raises `BaseError` if `pmtiles` fails or if `header["minzoom"] != 0`, `header["maxzoom"] != profile.max_zoom`, `meta.get("trial") is not True`, or `{layer.get("id") for layer in meta.get("vector_layers", [])} != {"land", "coast"}` (does not check `header["bounds"] == profile.data_extent` because `base.pmtiles` includes global Natural Earth at zooms `0–5`).

- [ ] **Step 1: Write the failing tests:**
  - In `tests/test_workarea.py`: assert `open_area` creates `area.base_dir`.
  - In `tests/test_base.py` (synthetic rasters with `max_zoom=6, block_zoom=6` + synthetic Natural Earth GeoJSON fixtures — fast, no network):
    - `test_contour_skips_data_edge`: synthetic `mask.tif` with land (`255`) reaching the data-extent cut (`valid == False` outside); assert no contour segment runs along the cut edge and the true internal coast line is present.
    - `test_low_zoom_warp_preserves_small_window_and_data_extent`: a 0.25° × 0.25° mask warped for band `0–2` clamps to 64 × 64 pixels, emits non-empty land and coast inside `data_extent`, and draws no false contour along the data-extent boundary.
    - `test_polygonize_emits_only_land`: binary land inside `data_extent`, sea and out-of-extent excluded.
    - `test_erase_leaves_overlap_ring`: Natural Earth polygon covering the extent erased by `shrink_extent(extent, 2)` leaves land outside `shrunk` and overlaps the outer 2 pixels of `extent`.
    - `test_split_land_preserves_area_and_clips_to_cells`: a polygon spanning four 10° cells splits into four polygonal parts with matching total area.
    - `test_all_land_mask_skips_empty_wbm_coast_slices`: a 100% inland (`255`) WBM mask skips all WBM coast slices without error and still produces a verified `base.pmtiles` with `"coast"` from Natural Earth.
    - `test_build_base_layer_produces_verified_deterministic_archive`: synthetic 2-block mask + synthetic NE files build `base.pmtiles` twice; assert `verify_base` passes, both layers (`land`, `coast`) exist, `"trial": True`, metadata keys match the allowlist, and both runs produce identical SHA-256 bytes.
    - `test_verify_base_rejects_missing_layer_or_trial_flag`: raises `BaseError`.
- [ ] **Step 2: Run** MAPTEST `tests/test_workarea.py tests/test_base.py`. Expected: FAIL.
- [ ] **Step 3: Implement** `WorkArea.base_dir` in `workarea.py` and `base.py` in small, single-purpose helpers. Keep every subprocess invoked as an argument list (`check=False`, raising `BaseError` with stderr on non-zero return).
- [ ] **Step 4: Run** MAPTEST `tests/test_workarea.py tests/test_base.py`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(map): build the vector base layer from mask blocks and Natural Earth`.

### Task 4: Wire base layer into CLI, report and end-to-end example tests (spec files 5, 15, 32, 33)

**Files:**

- Modify: `tools/map/src/seerah_map/report.py`, `tools/map/src/seerah_map/cli.py`, `tools/map/tests/test_report.py`, `tools/map/tests/test_cli.py`, `tools/map/tests/test_build_example.py`

**Interfaces:**

- Consumes: `build_base_layer`, `BaseError`, `verify_base`.
- Produces:
  - `report._SHAPE["stage_seconds"]` set to `{"block", "tile", "pack", "base"}`;
  - `cli._cmd_build` calling `build_base_layer(...)` timed under `"base"` after `pack_and_verify`, logging `base done: <path>`, writing `report.json`, then removing the work area;
  - `cli.main` catching `BaseError` alongside `(FetchError, CompletenessError, PackError)` and exiting with code `1` (`EXIT_CHECK_FAILED`), with updated module docstring and `build` help text (`"build the relief and base archives"`).

- [ ] **Step 1: Write the failing tests:**
  - `tests/test_report.py`: update expected `stage_seconds` keys to `{"block", "tile", "pack", "base"}` and verify missing `"base"` is rejected.
  - `tests/test_cli.py`:
    - update `_stub_pipeline` to stub `cli.build_base_layer` (recording `"base"` and raising `BaseError("stub failure")` when `fail_at == "base"`), and update **both** `test_build_pipeline_order` (line 163) and `test_sibling_outline_inside_data_root_runs_fetch_and_build` (line 230) to expect `["check lock", "block", "tile", "pack", "verify", "base", "report", "remove"]` (and verify the work area is kept when `fail_at="base"`);
    - add `BaseError` to `test_exit_codes` (exits `1`).
  - `tests/test_build_example.py` (`slow`):
    - extend `test_example_builds_complete` to verify `base.pmtiles` exists alongside `relief.pmtiles`, passes `verify_base`, has `"trial": true`, and contains both `land` and `coast` vector layers;
    - extend `test_double_build_identical` to compare SHA-256 hashes of **both** `relief.pmtiles` and `base.pmtiles`;
    - add `test_example_base_has_no_inland_edge_coast` (spec line 428, spec file 33): open `base.pmtiles` at zoom 12 with GDAL's native PMTiles vector driver (`gdal.OpenEx(str(base_pmtiles), gdal.OF_VECTOR, open_options=["ZOOM_LEVEL=12"])`) and read the `"coast"` layer in `EPSG:3857`. Assert that at least one `coast` segment lies inside `profile.data_extent`, and that no `coast` segment has both endpoints within `1.5 * pixel_m()` of the same edge of `profile.data_extent` in `EPSG:3857`.
- [ ] **Step 2: Run** MAPTEST `tests/test_report.py tests/test_cli.py`. Expected: FAIL.
- [ ] **Step 3: Implement** changes in `report.py` and `cli.py`.
- [ ] **Step 4: Run** MAPTEST `tests/test_report.py tests/test_cli.py` and then MAPTEST `tests/test_build_example.py`. Expected: all pass, including determinism of both archives and the coast-edge check.
- [ ] **Step 5: Commit** `feat(map): integrate the base layer into the build pipeline`.

### Task 5: Wire `base.pmtiles` into Android `:core:map` and `benchmark` (spec files 26–28, 30; closes phase 2 D1)

**Files:**

- Modify:
  - `android/core/map/src/main/kotlin/app/seerah/map/MapFiles.kt`
  - `android/core/map/src/main/kotlin/app/seerah/map/MapStyleSpec.kt`
  - `android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt`
  - `android/core/map/src/test/kotlin/app/seerah/map/StyleJsonTest.kt`
  - `android/core/map/README.md`
  - `android/app/src/benchmark/kotlin/app/seerah/trial/TrialFiles.kt`
  - `android/app/src/benchmark/kotlin/app/seerah/trial/TrialActivity.kt`
  - `android/app/src/testBenchmark/kotlin/app/seerah/trial/TrialFilesTest.kt`

**Interfaces:**

- Consumes: `relief.pmtiles` and `base.pmtiles` on device storage.
- Produces:
  - `MapFiles(val relief: File, val base: File)`.
  - `StyleJson.styleJson(files, spec)`:
    - Sources:
      - `"base"`: `{"type": "vector", "url": "pmtiles://file://${files.base.absolutePath}"}`
      - `"relief"`: unchanged
    - Layers (in draw order):
      1. `"background"` (`type: "background"`, `paint: {"background-color": rgba(colours.sea)}`)
      2. `"land"` (`type: "fill"`, `source: "base"`, `source-layer: "land"`, `paint: {"fill-color": rgba(colours.land), "fill-antialias": false}`)
      3. `"coast"` (`type: "line"`, `source: "base"`, `source-layer: "coast"`, `paint: {"line-color": rgba(colours.coast), "line-width": ["interpolate", ["linear"], ["zoom"], 3, 0.4, 10, 1.2]}`)
      4. `"relief"` (unchanged)
  - `TrialVariant` exposes `val base: File get() = File(folder, BASE_FILE)` (`"base.pmtiles"`); `loadVariants` skips folders missing `base.pmtiles` with reason `"$BASE_FILE is missing"` (`"base.pmtiles is missing"`).
  - `TrialActivity` passes `MapFiles(relief = selection.variant.relief, base = selection.variant.base)`.

- [ ] **Step 1: Update unit tests first:**
  - `StyleJsonTest.kt`: update `MapFiles(File("/data/t/relief.pmtiles"), File("/data/t/base.pmtiles"))`; assert layer IDs `listOf("background", "land", "coast", "relief")`; assert `"base"` vector source URL `"pmtiles://file:///data/t/base.pmtiles"`; replace `background_isLandColour` with `background_isSeaColour` (expecting `"rgba(159, 195, 216, 1)"` for the test fixture's `sea = 0xFF9FC3D8.toInt()`); add tests asserting `"land"` fill colour (`"rgba(241, 230, 200, 1)"`) and `getBoolean("fill-antialias") == false`, and `"coast"` line colour (`"rgba(91, 127, 149, 1)"`) and `paint("coast", "line-width") == """["interpolate",["linear"],["zoom"],3,0.4,10,1.2]"""`.
  - `TrialFilesTest.kt`: create `base.pmtiles` in `variantFolder(...)`, assert `variant.base` resolves to it, and add `folderWithoutBaseArchive_isSkippedWithReason` checking reason `"base.pmtiles is missing"`.
- [ ] **Step 2: Run** GRADLE `:core:map:testDebugUnitTest :app:testBenchmarkUnitTest`. Expected: FAIL (compile error on `MapFiles` / missing `base`).
- [ ] **Step 3: Implement** changes in `MapFiles.kt`, `MapStyleSpec.kt`, `StyleJson.kt` (named constants for source/layer IDs and coast line-width stops, plus updated KDoc on all three files), `TrialFiles.kt`, `TrialActivity.kt`, and `android/core/map/README.md` (update line 15's layer description and remove line 30's "What is not here yet" bullet for `MapFiles.base`).
- [ ] **Step 4: Run** GRADLE `spotlessCheck check buildHealth assembleRelease assembleBenchmark`, then run:
  - `python3 tools/android/check_no_trial_code.py android/app/build/outputs/apk/release/app-release.apk`
  - `python3 tools/android/check_page_alignment.py android/app/build/outputs/apk/release/app-release.apk android/app/build/outputs/apk/benchmark/app-benchmark.apk`
  Expected: all pass.
- [ ] **Step 5: Commit** `feat(map): draw base.pmtiles land and coast layers in :core:map and benchmark`.

### Task 6: Complete phase 3 documentation, notices and full public verification (spec files 38–43)

**Files:**

- Create: `docs/map-trial/README.md`
- Modify:
  - `tools/map/README.md`
  - `tools/README.md`
  - `map-data/README.md`
  - `docs/README.md`
  - `docs/roadmap.md`
  - `docs/open-decisions.md`
  - `docs/spike/measurements.md`
  - `THIRD_PARTY_NOTICES.md`

- [ ] **Step 1: Create `docs/map-trial/README.md`** (spec file 41):
  - List all eleven trial items from [ADR 0009](../adr/0009-map-data-pipeline.md) decision 13 with their current status and links to [step-1.md](../map-trial/step-1.md) (and placeholders for later step reports).
  - Note the current temporary deferral of Oppo A55 and Huawei MatePad 11.5 measurements.
- [ ] **Step 2: Update the remaining phase 3 docs** (spec files 38–43 + phase 2 carryover):
  - `tools/map/README.md`: update line 3 to mention both `relief.pmtiles` and `base.pmtiles` and link `map-trial-phase-2-plan.md` and `map-trial-phase-3-plan.md`; update image tools (`tippecanoe`, `tile-join`, `WhiteboxTools`), `build` stages (including base layer), output folder (`base.pmtiles`), `report.json` `stage_seconds.base`, and line 81 (`relief.pmtiles`, `base.pmtiles` and `metadata.json` all carry `trial: true`). Document the exact valley-probe container commands:
    1. Warp Copernicus GLO-90 (`copernicus-dem-90m`) over the full data extent (`33..61° E, 11..33° N`) in `seerah-map` under `--memory 8192m --memory-swap 8192m` with `gdalwarp -t_srs EPSG:3857 -tr 90 90 -r bilinear` and `-tr 180 180 -r bilinear` to `/work/valley-probe/dem_90m.tif` and `/work/valley-probe/dem_180m.tif`.
    2. Run `whitebox_tools -v=false --wd=/work/valley-probe --run=BreachDepressionsLeastCost --dem=dem_<res>.tif --output=breached_<res>.tif --dist=100` (and `--run=BreachDepressions --dem=dem_<res>.tif --output=breached_<res>.tif` if comparing standard breaching) followed by `whitebox_tools -v=false --wd=/work/valley-probe --run=D8FlowAccumulation --input=breached_<res>.tif --output=accum_<res>.tif`, reading `/sys/fs/cgroup/memory.peak` and `/sys/fs/cgroup/memory.events` (`oom_kill`).
  - `tools/README.md`: add a row for `android/check_no_trial_code.py` (spec file 42 / phase 2 carryover), note `check_page_alignment.py` checks both release and benchmark APKs, and update `map/` to mention both relief and base PMTiles archives.
  - `map-data/README.md`: add `base.pmtiles` to the trial output folder layout and remove the stale "(phase 2)" note on `camera-path.json`.
  - `docs/README.md`: index `docs/map-trial/README.md`, `docs/design/map-trial-build.md`, and the phase 1–3 plans + consolidated EGM reviews.
  - `docs/roadmap.md`: correct line 43 so ADRs 0003 and 0004 are no longer called "Proposed", update step 6 with phases 1–3 status and link `docs/map-trial/README.md`.
  - `docs/open-decisions.md`: under the bounds/zoom row note the trial's working values (12–32° N, 34–60° E, 1° margin, min MapLibre zoom 4; not decisions); under the reconstruction row note that the trial's provisional `schema: trial-0` edit format is not the reconstruction edit format.
  - `docs/spike/measurements.md`: mark line 7's frame-rate description out of date and point to `[Stats.kt](../../android/spike/spike/src/main/kotlin/app/seerah/spike/Stats.kt)`.
  - `THIRD_PARTY_NOTICES.md`: add a table of the map build tools — GDAL (MIT), `tippecanoe` (BSD-2-Clause), `go-pmtiles` (BSD-3-Clause), Protomaps `pmtiles` (BSD-3-Clause), `scipy` (BSD-3-Clause), `morecantile` (MIT), `ruamel.yaml` (MIT) and `WhiteboxTools` (MIT) — and update the downloaded-data table to include `tools/map/` and the Copernicus Water Body Mask. Update lines 20–24 so MapLibre Native Android (BSD-2-Clause) is noted as used by the technical spike and `:core:map` / `benchmark`, and state that none of these ships in the `release` app build yet (MapLibre Native moves to shipped notices when `release` depends on `:core:map`).
- [ ] **Step 3: Full public verification gates:**
  - MAPTEST (full suite, including all `slow` tests in `tests/test_build_example.py`)
  - HOSTTEST (`python3 -m unittest discover -s tools/map/host_tests`)
  - `python3 -m unittest discover -s tools/android/tests`
  - GRADLE `spotlessCheck check buildHealth assembleRelease assembleBenchmark`
  - `pre-commit run --all-files` (all hooks including `ruff`, `markdownlint-cli2`, `reuse lint`, `zizmor`, `validate content`)
  - `python3 tools/validate_content.py`
  - Verify that every relative Markdown link in created or modified docs resolves.
- [ ] **Step 4: Commit** `docs(map): complete phase 3 map trial documentation and notices`.

### Task 7: Owner gate — push public branch, open PR, publish updated image

- [ ] **Step 1: Request approval to publish (Owner gate).** Present the full branch summary and test results to the owner and stop for explicit approval to push and open the PR.
- [ ] **Step 2: Push and open PR.** After approval, run `gh auth status` (confirm `MohdFarag` active), push the working branch, open a PR against `main`, and wait for the `map` and `Android` workflows to pass.
- [ ] **Step 3: Merge and publish toolchain image (Owner gate).** After the owner merges the PR, wait for the `map` workflow on `main` to build and push the new `ghcr.io/mohdfarag/seerah-map:<tag>` image (with `tippecanoe` and `WhiteboxTools`).

### Task 8: Owner gate — private trial inputs in `seerah-content/map/trial/` (spec file 34)

Runs only in the private `seerah-content` repository (never committed to public `seerah`).

**Files:**

- Create (in private `seerah-content/map/trial/`): `full.yaml`, `window-spike.yaml`, `window-najd.yaml`, `window-yemen.yaml`, `edits.yaml`, `camera-path.json`, `peninsula.geojson`, `README.md`, and `<profile>.lock`

- [ ] **Step 1: Confirm trial window coordinates and outline (Owner gate).** Confirm the neutral Najd coordinates for `window-najd.yaml` and the five synthetic edits ([map-trial-build.md](map-trial-build.md) spec file 34), the Yemen mountain block for `window-yemen.yaml`, the `fade_deg` value for `full.yaml` and the windows, and the `peninsula.geojson` boundary polygon.
- [ ] **Step 2: Write draft trial profiles and edits in `seerah-content/map/trial/`.** Create:
  - `full.yaml` (12–32° N, 34–60° E, `margin_deg: 1`, `max_zoom: 12`, `block_zoom: 9`), `window-spike.yaml` (21–25° N, 38–40° E), `window-najd.yaml`, `window-yemen.yaml`;
  - `edits.yaml` (`schema: trial-0`, `purpose: trial`, all five synthetic edits from spec file 34 marked `review: draft`, with comments noting they are synthetic);
  - `camera-path.json` (stops covering the full extent / test windows, MapLibre zooms 4–14);
  - `peninsula.geojson`;
  - `README.md` stating these are trial values, refused by packaging and deleted after the trial.
- [ ] **Step 3: Review, approve edits and commit profiles (Owner gate).** Owner reviews the draft files in `seerah-content/map/trial/`, marks the synthetic edits `review: approved` when ready ([AGENTS.md](../../AGENTS.md): only the owner marks content `approved`), and commits the files on a working branch in `seerah-content` so both `seerah` and `seerah-content` git checkouts are clean.
- [ ] **Step 4: Fetch and commit locks (Owner gate).** With both checkouts clean (pass `--private <path>` if `seerah-content` is not at `../seerah-content`), run `python3 tools/map/run.py -- fetch --profile trial/<P>` for all four profiles (`window-spike`, `window-najd`, `window-yemen`, `full`) so `map-data/dist/<P>.lock` (mounted at `/out` in the container) is written for each. Then copy all four `map-data/dist/<P>.lock` files into `seerah-content/map/trial/` and commit them together in `seerah-content`.

### Task 9: Owner gate — valley probe (trial item 7, spec file 41) and A54 smoke check

**Files:**

- Modify: `docs/map-trial/step-1.md`

- [ ] **Step 1: Run the valley probe (Owner gate).** Run the two valley-probe container commands documented in Task 6 Step 2 (warp Copernicus GLO-90 over `33..61° E, 11..33° N` to 90 m and 180 m, then run `whitebox_tools` depression breaching and D8 flow accumulation on each under the 8,192 MiB container memory cap), record whether each fits (peak memory, wall time, OOM kills) in [docs/map-trial/step-1.md](../map-trial/step-1.md), and commit as `docs(map): record the valley probe in step-1.md`.
- [ ] **Step 2: Smoke-check base layer on Galaxy A54 (Owner gate, optional on available Galaxy A54).** Push a phase 3 example output folder (`relief.pmtiles`, `base.pmtiles`, `metadata.json`, `camera-path.json`) to the Galaxy A54 benchmark build to confirm sea, land, coast and relief render together. Oppo A55 and Huawei MatePad 11.5 checks remain deferred until those devices are available.

## Self-review

- **Spec coverage (phase 3, spec files 32–44):**
  - 32 (`base.py`): Tasks 3–4 (plus `Dockerfile`/`test_image.py` in Task 1 and `grid.py` coverage checks in Task 2).
  - 33 (`test_build_example.py` coast check): Task 4.
  - 34 (`seerah-content/map/trial/`): Task 8 (private repo, owner gate).
  - 35 ([ADR 0007](../adr/0007-content-formats-compatibility-and-signing.md)), 36 ([ADR 0004](../adr/0004-build-and-quality-tooling.md)), 37 ([ADR 0009](../adr/0009-map-data-pipeline.md)), 44 (`.github/dependabot.yml` + design docs): already done on `main`.
  - 38 (`docs/open-decisions.md`), 39 (`docs/roadmap.md`), 40 (`docs/spike/measurements.md`), 42 (`tools/README.md`, `map-data/README.md`), 43 (`THIRD_PARTY_NOTICES.md`): Task 6.
  - 41 (`docs/map-trial/README.md` and `docs/map-trial/step-1.md`): Task 6 (`README.md`) and Task 9 (`step-1.md` valley probe).
  - Phase 2 D1 carryover (`MapFiles.base`, `StyleJson`, `TrialFiles`, `TrialActivity`): Task 5.
- **Owner gates:** D1–D5 approval before starting; Task 7 (push/PR/merge + image publish); Task 8 (private trial coordinates, edit approval, lock commits); Task 9 (valley probe run and optional A54 smoke check).
- **What an agent cannot verify:** on-device rendering and measurements on Oppo A55 and Huawei MatePad 11.5 (currently unavailable) or Galaxy A54 without the owner's device attached; private `seerah-content` edits and locks without owner approval.
