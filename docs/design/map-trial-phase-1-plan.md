# Map trial, phase 1: tool core and example. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a containerised map build tool that fetches pinned Copernicus data, renders style C relief in blocks, tiles it with GDAL, packs it into PMTiles with a run report, and is proven end to end on a synthetic example in CI.

**Architecture:** the work is split into units, each with one job:

- **Pure numpy functions:** grid, mask, edits, style and encode.
- **Stage modules:** the block stage, tile stage and pack, each of which calls GDAL or Protomaps.
- **A CLI** that wires the stages together.

The tool runs in a toolchain-only image, with its code mounted read-only from the checkout. A host launcher, `run.py`, starts it. CI runs the same image and the same `python -m seerah_map` entry point, so the pipeline runs unchanged on any Docker host, local or cloud.

**Tech Stack:**

- the image: GDAL 3.13.3 (`ghcr.io/osgeo/gdal:ubuntu-small-3.13.3`), go-pmtiles v1.31.2, and Python 3 with numpy, scipy, Protomaps' `pmtiles`, morecantile, ruamel.yaml, jsonschema and pytest;
- CI: GitHub Actions and GHCR.

**Spec:** [docs/design/map-trial-build.md](map-trial-build.md) (Approved, 2026-11-14): Phase 1, files 1–18, plus the parts of "Technical Plan" they implement.

## Global Constraints

**Runtime and installation:**

- **The image holds only the toolchain.** The tool's code runs from `/src` with `PYTHONPATH=/src/tools/map/src`, and there is no `ENTRYPOINT`.
- **Pinning:** every dependency is pinned by digest, commit, SHA-256 or hash-locked `requirements.txt`; there are no `latest` tags. A new pin is the newest release at least two weeks old.
- **Python:** packages are installed with `pip install --require-hashes --break-system-packages` into the image's system Python. Code is Python 3.11 syntax, to the repository's ruff settings in `pyproject.toml`.
- **Container paths:** `/src` and `/data` are read-only; `/cache`, `/work` and `/out` are writable. Everything else is a host path relative to the repository root.

**Behaviour and output:**

- **Profiles:** `purpose` is the constant `trial`, and no other value is accepted. Every output's `metadata.json` carries `"trial": true`.
- **Edits:** only edits with `review: approved` are applied; the rest are listed in the build log.
- **Determinism:** the same inputs on the same machine and image give identical PMTiles bytes; `disk_to_pmtiles` runs with `PYTHONHASHSEED=0`.
- **No missing relief tiles:** every relief tile exists at every zoom, transparent ones included, so `gdal raster tile` never gets `--skip-blank`.

**Code rules:**

- **Style constants:** the spike's values, verbatim:
  - blur σ 7 px, band blur σ 1.5 px with radius 4, exaggeration 7.36;
  - light from 315° at 45°, contrast 1.6, bands 3;
  - ink RGB 150, 118, 72, maximum alpha 0.6;
  - mask σ 4 px, land at 128 and above.

  All pixel values are at zoom 12.
- **No spike code is copied** (AGENTS.md). `tools/spike/relief_style.py` is used only to generate the regression fixture.
- **Docstrings:** every module, and every public function, stating its inputs, outputs and units.

**Running the tests** ("`MAPTEST`" below):

```sh
docker build -t seerah-map:dev tools/map
docker run --rm -v "$PWD:/src:ro" -w /src -e PYTHONPATH=/src/tools/map/src \
  -v seerah-map-test-cache:/cache seerah-map:dev python3 -m pytest tools/map <args>
```

**Commits:** Conventional Commits, scope `map` (for example `feat(map): add the grid module`), one per task, on this branch. Nothing is pushed or merged without the owner's approval.

## Unresolved decisions (owner's, before or during the named task)

| # | Decision | Needed by | Proposal |
| --- | --- | --- | --- |
| U1 | **Where the public CI example sits.** It must be neutral (far from any event or named place), inside one present cell, with an absent all-sea neighbour, and its window edge must cross land. | Task 14 | Cell N17 E056 on the Dhofar coast of Oman, with E057 absent. The window spans latitude 17.50–17.75° N and longitude 56.80–57.05° E. The owner confirms that the place is neutral and that the window holds land. |
| U2 | **GHCR package name and visibility.** CI pushes `ghcr.io/mohdfarag/seerah-map`. The package must be public so `run.py` and CI pull it without a token; making it public is a one-time owner action in GitHub's package settings. | Task 16 | Push it as `ghcr.io/mohdfarag/seerah-map`, and the owner sets it public after the first push. |
| U3 | **When tippecanoe and WhiteboxTools join the image.** The spec's Dockerfile (file 1) lists them, but phase 1 uses neither. | Task 1 | Add them in phase 3, which uses them. This keeps phase 1's image smaller and avoids two unpinned builds. It is the one deviation from file 1. |
| U4 | **pip in the base image.** If `ubuntu-small` lacks `pip`, it must come from Ubuntu's archive, which is not pinnable. The image's bytes are then fixed only by its digest, not by the Dockerfile. | Task 1 | Accept it. Runs use the image by digest, so they stay reproducible. Record the outcome in the Dockerfile comment. |
| U5 | **Where the thread and cache variables are set.** The spec sets `GDAL_NUM_THREADS`, `OMP_NUM_THREADS` and `OPENBLAS_NUM_THREADS` in `run.py`. | Task 1 | Set them as `ENV` in the Dockerfile instead, with `GDAL_CACHEMAX=256`. That way every runner (run.py, CI, a cloud host) gets them without extra steps. This is a small deviation from the spec, made for portability. |

The specification's "Step 1 checks" are facts for the trial's step 1 on devices; phase 1 settles only three of them:

- **check 5** (does GDAL write tiles atomically?) is recorded by Task 11;
- **check 6** (does average resampling keep the ink?) by Task 11;
- **check 7** (the Water Body Mask's class values) by Task 2.

## Review Focus

Five inputs the spec implies, each pinned by a test in the task named:

1. **An interrupted download** leaves a `.part` file: the next `fetch` replaces it, never trusts it, and never writes it to the lock (Task 9, `test_fetch_discards_partial`).
2. **The lock and the cache disagree,** because a cached file was edited or truncated: `fetch` exits 1, naming the file and both hashes (Task 9, `test_fetch_rejects_changed_file`).
3. **No memory cap in the container,** so `memory.max` reads `max`: the build uses `--workers`, or 1 if that is absent, and says so. It never divides by an unbounded number (Task 13, `test_worker_count_without_cap`).
4. **A profile outside the N/E quadrant** (latitude < 0 or longitude < 0), where Copernicus names cells `S`/`W`: config rejects it with a clear message instead of building wrong cell names (Task 2, `test_profile_rejects_southern_or_western_extent`).
5. **cgroup v1, or no `memory.peak` file:** the report records `null` with a reason instead of crashing the build (Task 12, `test_report_without_cgroup_v2`).

## File structure

```text
tools/map/
  Dockerfile                    toolchain image (Task 1)
  requirements.in, requirements.txt
  pyproject.toml                pytest config, pythonpath, markers
  run.py                        host launcher (Task 15)
  README.md                     how to run (Task 16)
  src/seerah_map/
    __init__.py, __main__.py    entry point → cli.main (Task 13)
    strict_yaml.py              YAML 1.2 loader (Task 2)
    config.py                   Profile, Edit, Lock dataclasses + loaders (Task 2)
    schema/profile.schema.json, edits.schema.json
    grid.py                     global grid, blocks, borders, tiles (Task 3)
    workarea.py                 fingerprint, work areas, atomic writes (Task 4)
    mask.py                     land mask (Task 5)
    edits.py                    edit rasterising and weights (Task 6)
    style.py                    darkness, edge fade (Task 7)
    encode.py                   painted and value encoders (Task 8)
    fetch.py                    downloads and lock (Task 9)
    blockstage.py               block stage (Task 10)
    tilestage.py                tile stage (Task 11)
    pack.py                     metadata + PMTiles (Task 12)
    report.py                   run report (Task 12)
    cli.py                      commands (Task 13)
  tests/
    conftest.py, fixtures/, test_*.py
map-data/example/               example.yaml, edits.yaml, example.lock, peninsula.geojson, camera-path.json (Task 14)
.github/workflows/map.yml       image and test jobs (Task 16)
.github/dependabot.yml          docker and pip for /tools/map (Task 16)
tools/README.md                 one row for map/ (Task 16)
```

---

### Task 1: Toolchain image and test harness

**Files:**

- Create: `tools/map/Dockerfile`, `tools/map/requirements.in`, `tools/map/requirements.txt`, `tools/map/pyproject.toml`, `tools/map/src/seerah_map/__init__.py`, `tools/map/tests/test_image.py`

**Interfaces:**

- Produces:
  - the image `seerah-map:dev`;
  - `seerah_map.__version__ = "0.1.0"`;
  - the pytest marker `slow`.

- [ ] **Step 1: Write the failing test** `tests/test_image.py`

```python
def test_toolchain_present():
    from osgeo import gdal
    import numpy, scipy, pmtiles, morecantile, ruamel.yaml, jsonschema  # noqa: F401

    assert gdal.__version__.startswith("3.13")
    assert shutil.which("pmtiles") is not None


def test_single_thread_environment():
    for name in ("GDAL_NUM_THREADS", "OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS"):
        assert os.environ[name] == "1"
    assert os.environ["GDAL_CACHEMAX"] == "256"
```

- [ ] **Step 2: Run** `MAPTEST tests/test_image.py`. It is expected to fail: the image build fails, or the imports are missing.

- [ ] **Step 3: Write the Dockerfile and its dependency files.**
  - **Base:** `FROM ghcr.io/osgeo/gdal:ubuntu-small-3.13.3@sha256:<digest>`. Resolve the digest with `docker buildx imagetools inspect`.
  - **pip** (U4): if `python3 -m pip --version` fails in the base image, `apt-get install --no-install-recommends python3-pip`, with a comment.
  - **go-pmtiles:** download v1.31.2 `go-pmtiles_1.31.2_Linux_x86_64.tar.gz`, check its SHA-256 against a pinned `ARG`, and install it to `/usr/local/bin/pmtiles`.
  - **Python packages:** `COPY requirements.txt` and `pip install --require-hashes --break-system-packages -r requirements.txt`.
  - **Environment** (U5): `ENV GDAL_NUM_THREADS=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 GDAL_CACHEMAX=256 PYTHONDONTWRITEBYTECODE=1`.
  - **Dependency files:**
    - `requirements.in` lists `pmtiles`, `scipy`, `morecantile`, `ruamel.yaml`, `jsonschema` and `pytest`, plus `numpy` only if the base image lacks it;
    - `requirements.txt` comes from `pip-compile --generate-hashes`, run in the base image so the wheels match.
  - **`pyproject.toml`:** `[tool.pytest.ini_options]` with `testpaths = ["tests"]`, `pythonpath = ["src"]` and `markers = ["slow: needs GDAL and the example's downloaded cell"]`.

- [ ] **Step 4: Run** `MAPTEST tests/test_image.py`. Expected: 2 passed.

- [ ] **Step 5: Commit** `build(map): add the toolchain image and test harness`.

### Task 2: Strict YAML and configuration

**Files:**

- Create: `src/seerah_map/strict_yaml.py`, `src/seerah_map/config.py`, `src/seerah_map/schema/profile.schema.json`, `src/seerah_map/schema/edits.schema.json`, `tests/test_config.py`, `tests/fixtures/profile_ok.yaml`, `tests/fixtures/edits_ok.yaml`

**Interfaces:**

- Produces:
  - `strict_yaml.load(path: Path) -> object` and `strict_yaml.dump(obj, path: Path) -> None`;
  - `config.Bounds(west, south, east, north: float)`;
  - `config.StyleSettings`: the fields `blur_px`, `band_blur_px`, `exaggeration`, `azimuth_deg`, `altitude_deg`, `contrast`, `bands`, `ink_rgb` and `max_alpha`, with the spike's defaults from Global Constraints;
  - `config.Profile(name: str, purpose: str, camera_bounds: Bounds, margin_deg: float, fade_deg: float, max_zoom: int, block_zoom: int, webp_quality: int, style: StyleSettings, peninsula_outline: Path)`, with a `data_extent -> Bounds` property (the camera bounds widened by `margin_deg`);
  - `config.Edit(id, geometry: dict, operation: str, amount_m: float | None, height_m: float | None, width_m: float | None, feather_m: float, review: str)`;
  - `config.FileRef(url: str, size: int, sha256: str)`;
  - `config.Cell(lat: int, lon: int, absent: bool, dem: FileRef | None, wbm: FileRef | None)`;
  - `config.Lock(created: str, tile_list_sha256: str, cells: tuple[Cell, ...], natural_earth: tuple[FileRef, ...])`;
  - `load_profile(path) -> Profile`, `load_edits(path) -> list[Edit]` (approved and other edits both returned), `load_lock(path) -> Lock`, `dump_lock(lock, path)`;
  - `ConfigError(Exception)`, whose message names the file, the field, what was expected and what was found.

- [ ] **Step 1: Write the failing tests** in `test_config.py`:
  - `test_duplicate_key_rejected`: YAML `a: 1\na: 2` raises `ConfigError` naming `a`.
  - `test_yes_no_and_dates_stay_strings`: `x: yes`, `y: no`, `d: 2024-01-01` load as `"yes"`, `"no"` and `"2024-01-01"`.
  - `test_profile_loads`: `profile_ok.yaml` gives `Profile(...)` with `style.exaggeration == 7.36`, and `data_extent == Bounds(33, 11, 61, 33)` for camera bounds (34, 12, 60, 32) and a 1° margin.
  - `test_profile_rejects_release_purpose`: `purpose: release` raises `ConfigError` mentioning `purpose`.
  - `test_profile_rejects_southern_or_western_extent`: `camera_bounds.south: -1` raises `ConfigError` mentioning "N/E" (Review Focus 4).
  - `test_edits_schema_and_review`: an edit with `review: draft` loads with `review == "draft"`; `operation: lower` is rejected; `schema: trial-1` is rejected.
  - `test_lock_round_trip`: `dump_lock` then `load_lock` returns an equal `Lock`.

- [ ] **Step 2: Run** `MAPTEST tests/test_config.py`. Expected: FAIL (`ModuleNotFoundError: seerah_map.config`).

- [ ] **Step 3: Implement the loader, the schemas and the dataclasses.**
  - **`strict_yaml`:** `ruamel.yaml.YAML(typ="safe", pure=True)` with `version=(1, 2)` and `allow_duplicate_keys=False`. Remove the timestamp resolver, so dates stay strings.
  - **The two schemas:** draft 2020-12, fields as in the spec's Inputs. `review` and `sources` are copied from `content/schema/content.schema.json`'s `$defs`, with a `$comment` naming the source file.
  - **Validation:** `jsonschema.Draft202012Validator`, with the first error turned into `ConfigError`.
  - **The lock** has no schema: `load_lock` checks it while building the dataclasses.

- [ ] **Step 4: Run** `MAPTEST tests/test_config.py`. Expected: 7 passed.

- [ ] **Step 5: Record step-1 check 7.** Run `gdalinfo -hist` on one cached Water Body Mask (any present coastal cell, fetched by hand inside the image). Write the class values found into `docs/map-trial/step-1.md`; the file is created in this task with a heading per check. If they differ from 0, 1, 2 and 3, stop and tell the owner before Task 5.

- [ ] **Step 6: Commit** `feat(map): load and validate profiles, edits and locks`.

### Task 3: Grid

**Files:**

- Create: `src/seerah_map/grid.py`, `tests/test_grid.py`

**Interfaces:**

- Consumes: `Bounds`, `StyleSettings`, `Edit`.
- Produces:
  - constants `BASE_ZOOM = 12` and `TILE_PX = 256`;
  - `Window(col0: int, row0: int, cols: int, rows: int)`, in zoom-12 global pixels, with `.bounds_3857()` and `.geotransform()`;
  - `Block(z: int, x: int, y: int, window: Window)`, with `.id` returning `"z9-x123-y45"`;
  - `blocks(extent: Bounds, block_zoom: int) -> list[Block]`, sorted by `(y, x)`;
  - `MASK_SIGMA_PX = 4.0`;
  - `border_px(style: StyleSettings, edits: list[Edit], lat_deg: float) -> int`;
  - `row_latitudes(window: Window) -> np.ndarray`, one value per row, at pixel centres;
  - `pixel_m() -> float`, the zoom-12 Mercator pixel size, 40075016.68557849 / 256 / 2**12;
  - `expected_tiles(extent_3857: tuple[float, float, float, float], max_zoom: int) -> set[tuple[int, int, int]]`;
  - `view_reach_km(maplibre_zoom: float, half_width_dp: float, lat_deg: float) -> float`.

- [ ] **Step 1: Write the failing tests:**
  - `test_blocks_on_whole_pixels`: each block's `col0` and `row0` are multiples of 2048 for `block_zoom=9`, and the blocks cover `Bounds(33, 11, 61, 33)`.
  - `test_block_count_data_extent`: between 1,350 and 1,450 blocks (the spec says about 1,400).
  - `test_border_grows_with_blur_and_feather`: `border_px` with the defaults and no edits is `21 + 4 + 12 + 1 = 38` (the blur's 3σ radius, the band blur's radius 4, the mask's 3σ, and one pixel for the gradient); adding a 500 m feather at latitude 23° adds `ceil(500 / (pixel_m() * cos(23°)))`.
  - `test_row_latitudes_decrease_southward`: values decrease down the rows.
  - `test_expected_tiles_counts`: for a one-zoom-12-tile extent, `max_zoom=12` gives 13 tiles, one per zoom.
  - `test_view_reach_table`: `view_reach_km(6, 550, 22)` is within 5% of 620, and `view_reach_km(9, 550, 22)` within 5% of 80, matching the spec's table.

- [ ] **Step 2: Run** `MAPTEST tests/test_grid.py`. Expected: FAIL.

- [ ] **Step 3: Implement with `morecantile.tms.get("WebMercatorQuad")`** for the tile ranges and their bounds. Pixel windows are integer arithmetic on the global grid. `view_reach_km` = half_width_dp × 40075016.69 × cos(lat) / (512 × 2^zoom) / 1000.

- [ ] **Step 4: Run** `MAPTEST tests/test_grid.py`. Expected: 6 passed.

- [ ] **Step 5: Commit** `feat(map): add the global grid and block layout`.

### Task 4: Fingerprint and work areas

**Files:**

- Create: `src/seerah_map/workarea.py`, `tests/test_workarea.py`

**Interfaces:**

- Produces:
  - `fingerprint(profile: bytes, edits: bytes, lock: bytes, tool_commit: str, image_digest: str, variant: str, mode: str) -> str`: the SHA-256 hexadecimal of the parts joined with `b"\0"`;
  - `WorkArea(root: Path, fingerprint: str)`, with `.path`, `.style_dir`, `.mask_dir`, `.tiles_dir` and `.tiles_done`;
  - `open_area(work_root: Path, fp: str) -> WorkArea`, which creates `<root>/<fp>/` and is idempotent;
  - `atomic_write(path: Path) -> ContextManager[Path]`, which yields `<path>.tmp` and renames it on clean exit, or deletes it on an exception;
  - `list_areas(work_root) -> list[tuple[str, int]]`, giving the fingerprint and size in bytes;
  - `remove(area: WorkArea) -> None`.

- [ ] **Step 1: Write the failing tests:**
  - `test_fingerprint_changes_with_each_input`: changing any one argument changes the result, and equal arguments give equal results.
  - `test_areas_coexist`: two fingerprints give two directories, and `list_areas` lists both.
  - `test_atomic_write_leaves_nothing_on_error`: an exception inside the block leaves neither `path` nor `path.tmp`.
  - `test_atomic_write_replaces`: a second clean write replaces the content.

- [ ] **Step 2: Run** `MAPTEST tests/test_workarea.py`. Expected: FAIL.

- [ ] **Step 3: Implement** with `hashlib`, `pathlib` and `os.replace`; the sizes come from `os.scandir`, walking the tree.

- [ ] **Step 4: Run** `MAPTEST tests/test_workarea.py`. Expected: 4 passed.

- [ ] **Step 5: Commit** `feat(map): add fingerprints and work areas`.

### Task 5: Land mask

**Files:**

- Create: `src/seerah_map/mask.py`, `tests/test_mask.py`

**Interfaces:**

- Consumes: `grid.MASK_SIGMA_PX`.
- Produces: `land_mask(wbm: np.ndarray, valid: np.ndarray) -> np.ndarray`, returning uint8 0–255 (255 for certain land). `valid` is a boolean array, false outside the data extent. The output is 0 where `valid` is false, and callers use `valid` again to write nodata.

- [ ] **Step 1: Write the failing tests:**
  - `test_ocean_is_sea`: an all-1 array gives all 0.
  - `test_lakes_and_rivers_are_land`: classes 2 and 3 give 255 away from any ocean.
  - `test_smoothing_softens_steps`: a straight coast step produces values between 1 and 254 within 3σ of the step.
  - `test_invalid_pixels_zero`: pixels where `valid` is false are 0.

- [ ] **Step 2: Run** `MAPTEST tests/test_mask.py`. Expected: FAIL.

- [ ] **Step 3: Implement.** Land = `(wbm != 1)` as float. Blur it with `scipy.ndimage.gaussian_filter(sigma=MASK_SIGMA_PX, truncate=3.0, mode="nearest")`, then scale to 0–255 by truncation, and set 0 where not `valid`.

- [ ] **Step 4: Run.** Expected: 4 passed.

- [ ] **Step 5: Commit** `feat(map): add the land mask`.

### Task 6: Edits

**Files:**

- Create: `src/seerah_map/edits.py`, `tests/test_edits.py`

**Interfaces:**

- Consumes: `Edit`, `Window`, `grid.row_latitudes`, `grid.pixel_m`.
- Produces:
  - `applicable(edits: list[Edit]) -> tuple[list[Edit], list[str]]`, giving the approved edits and the IDs of the skipped ones;
  - `touches(edit: Edit, window: Window, border_px: int) -> bool`;
  - `apply(elevation: np.ndarray, edits: list[Edit], window: Window) -> np.ndarray`, which returns a new array.

- [ ] **Step 1: Write the failing tests:**
  - `test_raise_inside_outline`: a 10 m raise on a square adds exactly 10 inside it.
  - `test_feather_falls_linearly`: halfway through `feather_m` outside the outline the change is 5 ± 0.5, and beyond `feather_m` it is 0.
  - `test_flatten_to_height`: `flatten-to` with `height_m=600` gives 600 inside, over sloping ground.
  - `test_carve_line`: `carve` along a line with `width_m=600` and `amount_m=8` lowers the line's pixels by 8.
  - `test_only_approved_applied`: `applicable` returns the draft edit's ID in the skipped list.
  - `test_edit_across_window_edge`: the same edit applied to two adjacent windows matches the result on one window covering both (the seam rule).

- [ ] **Step 2: Run** `MAPTEST tests/test_edits.py`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Rasterising:** the edit's geometry goes into an in-memory OGR layer (`ogr.GetDriverByName("Memory")`), reprojected to EPSG:3857, then onto the window's grid with `gdal.Rasterize` (`ALL_TOUCHED=FALSE`).
  - **Distance:** `scipy.ndimage.distance_transform_edt(~inside)`, in pixels, times `pixel_m() * cos(row_lat)` per row, gives metres.
  - **Weight:** `clip(1 - d / feather_m, 0, 1)`.
  - **Operations:** `raise` adds amount × weight; `flatten-to` blends towards the height by the weight; `carve` buffers the line by `width_m / 2` in metres before rasterising.

- [ ] **Step 4: Run.** Expected: 6 passed.

- [ ] **Step 5: Commit** `feat(map): apply trial edits with distance feathering`.

### Task 7: Style function and regression fixture

**Files:**

- Create: `src/seerah_map/style.py`, `tests/test_style.py`, `tests/test_style_regression.py`, `tests/fixtures/spike_relief.npz`, `tests/fixtures/README.md`

**Interfaces:**

- Consumes: `StyleSettings`.
- Produces:
  - `darkness(elevation: np.ndarray, land: np.ndarray, row_lat_deg: np.ndarray, pixel_m: float, s: StyleSettings, banded: bool) -> np.ndarray`, returning float32 in [0, 1] with the same shape as the input;
  - `edge_fade(d: np.ndarray, distance_to_edge_deg: np.ndarray, fade_deg: float) -> np.ndarray`.

- [ ] **Step 1: Make the fixture.**
  - Generate a 200×200 synthetic float32 elevation (a Gaussian hill plus a ridge) with a fixed numpy seed, written as ENVI.
  - Run `tools/spike/relief_style.py` on it in the image.
  - Save the input elevation and the output alpha (band 4, as uint8) to `spike_relief.npz`, under 1 MB.
  - `fixtures/README.md` records the command, the spike commit and the seed.

- [ ] **Step 2: Write the failing tests:**
  - `test_flat_is_zero`: constant elevation gives all 0.
  - `test_light_direction`: a slope facing north-west is lighter (lower darkness) than the same slope facing south-east.
  - `test_sea_is_zero`: where `land` is false the result is 0, also when `banded=True` and the band blur spreads near it.
  - `test_latitude_correction`: the same ground slope at 12° N and at 32° N gives equal darkness within 1e-3 (the input slope is built per latitude in metres).
  - `test_banded_has_four_levels`: before the band blur, `banded=True` with `bands=3` gives only {0, 1/3, 2/3, 1}.
  - `test_matches_spike` (`test_style_regression.py`): `darkness(..., banded=True)` with `exaggeration=8`, latitude 0, `land` set by `elevation > 0.5`, and `pixel_m` from the fixture's grid, converted to alpha as `trunc(d * 0.6 * 255)`, matches the fixture within 1/255 (one level) for 99.9% of pixels and within 2 for all of them.
  - `test_edge_fade`: darkness is 0 at the edge, unchanged at `fade_deg` and beyond, and linear between.

- [ ] **Step 3: Run** `MAPTEST tests/test_style.py tests/test_style_regression.py`. Expected: FAIL.

- [ ] **Step 4: Implement the seven steps** listed in the spec's "The style function", in order. `np.gradient(z, pixel_m)` gives gradients with rows running north to south; divide both by `cos(row_lat)[:, None]`. The band blur is `gaussian_filter(sigma=1.5, radius=4)`; scipy's `radius` argument fixes the cutoff at the spike's.

- [ ] **Step 5: Run.** Expected: 7 passed. If `test_matches_spike` fails, stop: the look differs from build 11 (G8).

- [ ] **Step 6: Commit** `feat(map): add the style C function with a spike regression test`.

### Task 8: Encoders

**Files:**

- Create: `src/seerah_map/encode.py`, `tests/test_encode.py`

**Interfaces:**

- Produces:
  - `painted(d: np.ndarray, ink_rgb: tuple[int, int, int], max_alpha: float) -> np.ndarray`, uint8 of shape (4, h, w);
  - `value(d: np.ndarray) -> np.ndarray`, uint8 of shape (3, h, w);
  - `TERRARIUM_OFFSET = 32768`.

- [ ] **Step 1: Write the failing tests:**
  - `test_painted_ink_everywhere`: the RGB bands equal the ink in every pixel, including where `d == 0`.
  - `test_painted_alpha_truncates`: `d = 1` gives alpha `trunc(0.6 * 255) = 153`.
  - `test_average_keeps_ink`: a 2×2 block with alpha (153, 0, 0, 0), averaged as GDAL does, keeps the ink RGB and gives alpha 38.
  - `test_value_terrarium_round_trip`: decoding `(R*256 + G + B/256) - 32768` gives `trunc(d * 255)` within 1.
  - `test_value_only_green_varies`: red is always 128 and blue always 0.

- [ ] **Step 2: Run** `MAPTEST tests/test_encode.py`. Expected: FAIL.

- [ ] **Step 3: Implement** with numpy only.

- [ ] **Step 4: Run.** Expected: 5 passed.

- [ ] **Step 5: Commit** `feat(map): add painted and value encoders`.

### Task 9: Fetch and the lock

**Files:**

- Create: `src/seerah_map/fetch.py`, `tests/test_fetch.py`, `tests/fixtures/fetch/` (tiny fake files and a `tileList.txt`)

**Interfaces:**

- Consumes:
  - `Profile.data_extent`;
  - `Lock`, `Cell` and `FileRef`;
  - `strict_yaml`;
  - `workarea.atomic_write`.
- Produces:
  - `DEM_BASE = "https://copernicus-dem-30m.s3.amazonaws.com"`;
  - `NATURAL_EARTH_COMMIT` (the commit of tag v5.1.2 in `nvkelso/natural-earth-vector`, resolved at implementation) and `NE_FILES`, the six `geojson/ne_{110m,50m,10m}_{land,coastline}.geojson` files;
  - `cell_name(lat: int, lon: int) -> str`, for example `Copernicus_DSM_COG_10_N17_00_E056_00_DEM`;
  - `cells_for(extent: Bounds) -> list[tuple[int, int]]`, covering every 1° cell that intersects the extent;
  - `fetch(profile: Profile, lock_path: Path | None, cache: Path, out: Path, dem_base: str = DEM_BASE, ne_base: str = ...) -> FetchResult`;
  - `FetchResult(lock: Lock, wrote_new_lock: bool, downloaded: int, verified: int)`;
  - `FetchError(Exception)`.

- [ ] **Step 1: Write the failing tests.** They use a local `http.server` fixture on a free port, serving `tests/fixtures/fetch/`.
  - `test_cells_for_data_extent`: `Bounds(33, 11, 61, 33)` gives 616 cells.
  - `test_first_run_writes_lock_to_out`: with no committed lock, `fetch` writes `out/<name>.lock`, marks cells missing from `tileList.txt` as `absent: true`, records size and SHA-256 for both files of each present cell plus the Natural Earth files, and sets `wrote_new_lock`.
  - `test_second_run_verifies`: with the lock passed in, no file is downloaded again and `verified` equals the file count.
  - `test_fetch_rejects_changed_file` (Review Focus 2): one byte of a cached file is changed, and `fetch` raises `FetchError` whose message contains the file name, both hashes and "delete the cached file and run fetch again".
  - `test_fetch_discards_partial` (Review Focus 1): a leftover `.part` file is replaced; the final file's hash matches the source.
  - `test_no_lock_written_into_data`: the new lock is written only under `out`.

- [ ] **Step 2: Run** `MAPTEST tests/test_fetch.py`. Expected: FAIL.

- [ ] **Step 3: Implement with standard-library `urllib.request` only,** one file at a time.
  - **Download:** to `<cache>/<relative path>.part`, hashing while writing, then `os.replace`. A failure deletes the `.part` file and raises `FetchError`.
  - **Cell list:** `tileList.txt` is read once, and its SHA-256 recorded in the lock.
  - **Cache layout:** `<cache>/copernicus/<cell>/<file>` and `<cache>/natural-earth/<commit>/<file>`.
  - **Lock order:** cells sorted by latitude, then longitude.

- [ ] **Step 4: Run.** Expected: 6 passed.

- [ ] **Step 5: Commit** `feat(map): fetch pinned sources and write the lock`.

### Task 10: Block stage

**Files:**

- Create: `src/seerah_map/blockstage.py`, `tests/test_blockstage.py`

**Interfaces:**

- Consumes:
  - `grid.blocks`, `border_px`, `row_latitudes`, `pixel_m`;
  - `mask.land_mask`;
  - `edits.applicable`, `edits.touches`, `edits.apply`;
  - `style.darkness`, `style.edge_fade`;
  - `encode.painted`, `encode.value`;
  - `workarea.WorkArea`, `workarea.atomic_write`.
- Produces:
  - `source_vrts(lock: Lock, cache: Path, work: WorkArea) -> tuple[Path, Path]`, giving the DEM VRT and the WBM VRT, made with `gdal.BuildVRT` over the present cells;
  - `read_block(block, border, dem_vrt, wbm_vrt, lock, extent) -> tuple[np.ndarray, np.ndarray, np.ndarray]`, giving the elevation, the WBM classes and `valid`;
  - `render_block(block: Block, profile: Profile, edits: list[Edit], variant: str, lock: Lock, cache: Path, work: WorkArea) -> None`, which writes `style/<id>.tif` and `mask/<id>.tif`;
  - `run_block_stage(profile, edits, variant, lock, cache, work, workers: int, on_block: Callable[[str], None]) -> list[str]`, which returns the IDs of the blocks rendered in this run and writes `style.vrt` and `mask.vrt`.

- [ ] **Step 1: Write the failing tests:**
  - `test_absent_cells_read_as_sea`: a lock with one present cell, whose DEM is a tiny synthetic GeoTIFF from a fixture, and one absent cell. Pixels in the absent cell get WBM 1 and elevation 0; pixels outside the extent get `valid == False`.
  - `test_render_block_writes_atomically`: after `render_block`, both files exist and no `.tmp` file is left.
  - `test_finished_blocks_skipped`: running `run_block_stage` twice renders nothing the second time.
  - `test_block_split_matches_single_block` (`slow`): on the example's real cell, `block_zoom=11` and a single block over the same window give equal style arrays (decoded from the TIFFs) on their overlap.

- [ ] **Step 2: Run** `MAPTEST tests/test_blockstage.py`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Reading:** `gdal.Warp` to an in-memory dataset on the block's bordered window (EPSG:3857, the zoom-12 resolution, `errorThreshold=0`). Elevation is resampled bilinear, the WBM nearest.
  - **Absent and outside pixels:** each pixel's cell comes from its latitude and longitude. Pixels in an absent cell get WBM 1 and 0 m; pixels outside the data extent are invalid.
  - **Steps:** mask, edits, style with `banded=(variant == "painted")`, edge fade, crop, then encode.
  - **Writing:** a GeoTIFF that is tiled, DEFLATE-compressed, and has the block's geotransform. The mask's invalid pixels get an internal mask band (`GMF_PER_DATASET`).
  - **Parallel work:** a `ProcessPoolExecutor(max_workers=workers)`, with results collected in block order.

- [ ] **Step 4: Run.** Expected: 4 passed (the `slow` one in CI, or locally once the example cell is cached).

- [ ] **Step 5: Commit** `feat(map): add the block stage`.

### Task 11: Tile stage

**Files:**

- Create: `src/seerah_map/tilestage.py`, `tests/test_tilestage.py`

**Interfaces:**

- Consumes: `WorkArea`, `grid.expected_tiles`.
- Produces:
  - `tile_args(style_vrt: Path, out_dir: Path, max_zoom: int, variant: str, webp_quality: int, workers: int, resume: bool) -> list[str]`;
  - `run_tile_stage(work: WorkArea, profile: Profile, variant: str, workers: int) -> int`, returning the number of tiles; it writes `tiles.done`;
  - `check_completeness(tiles_dir: Path, expected: set[tuple[int, int, int]], ext: str) -> list[tuple[int, int, int]]`, returning the missing or broken tiles;
  - `CompletenessError(Exception)`.

- [ ] **Step 1: Write the failing tests:**
  - `test_tile_args`: the list contains `gdal`, `raster`, `tile`, `--convention`, `xyz`, `--tiling-scheme`, `WebMercatorQuad`, `--min-zoom`, `0`, `--max-zoom`, `12`, `--resampling`, `average`, `--overview-resampling`, `average` and `-j`, and it does **not** contain `--skip-blank`. For `painted` it has `-f WEBP` and `--co QUALITY=<q>`; for `value`, `-f PNG`.
  - `test_folder_without_done_is_retiled`: a stray tile folder without `tiles.done` is deleted before tiling.
  - `test_completeness_reports_missing_and_broken`: a missing file and a truncated file are both reported.
  - `test_tile_small_vrt` (`slow`): a 512×512 RGBA VRT at zoom 12 tiles to `expected_tiles`; every file decodes; there are as many transparent tiles as expected.

- [ ] **Step 2: Run** `MAPTEST tests/test_tilestage.py`. Expected: FAIL.

- [ ] **Step 3: Implement.** Run the tiler with `subprocess.run(check=True)` on the argument list; `expected_tiles` comes from `style.vrt`'s extent; tiles are decoded by opening each with `gdal.Open` and reading one band.

- [ ] **Step 4: Run.** Expected: 4 passed.

- [ ] **Step 5: Record step-1 checks 5 and 6** in `docs/map-trial/step-1.md`.
  - **Check 5:** run the tiler on the small VRT with `strace -f -e trace=rename,renameat2` (installed temporarily in a scratch container, not in the image), and record whether each tile is written to a temporary name and renamed. If it is, `run_tile_stage` may pass `resume=True` on a folder without `tiles.done`; otherwise the default (delete and retile) stays.
  - **Check 6:** decode one edge tile at zoom 11 and confirm that its RGB equals the ink wherever alpha is above 0.

- [ ] **Step 6: Commit** `feat(map): add the tile stage with a completeness check`.

### Task 12: Pack and run report

**Files:**

- Create: `src/seerah_map/pack.py`, `src/seerah_map/report.py`, `tests/test_pack.py`, `tests/test_report.py`

**Interfaces:**

- Consumes: `WorkArea`, `Profile` and the provenance values.
- Produces:
  - `Provenance(tool_commit: str, data_commit: str, image_digest: str, fingerprint: str)`;
  - `metadata(profile, variant, mode, provenance) -> dict`, with the keys `format`, `minzoom`, `maxzoom`, `bounds` (`"w,s,e,n"` of the data extent), `name`, `trial`, `variant`, `mode`, `encoding`, `fingerprint`, `tool_commit`, `data_commit` and `image`;
  - `pack(work, out_dir, meta) -> Path`, which writes `relief.pmtiles` and `metadata.json` into `out_dir`;
  - `verify(pmtiles_path, profile) -> None`, raising `PackError`;
  - `Report`, a dataclass with the fields in the spec's "The run report";
  - `read_cgroup(root: Path = Path("/sys/fs/cgroup")) -> dict`;
  - `write_report(report, out_dir) -> Path`.

- [ ] **Step 1: Write the failing tests:**
  - `test_metadata_marks_trial`: `metadata(...)["trial"] is True`, and `encoding` is `painted-rgba` or `terrarium-value` (G9).
  - `test_pack_is_deterministic` (`slow`): packing the same tile folder twice gives equal SHA-256 hashes.
  - `test_verify_checks_bounds` (`slow`): a profile whose extent differs from the header raises `PackError`.
  - `test_report_reads_cgroup_v2`: a fake cgroup directory with `memory.peak=123` and `memory.events` containing `oom_kill 0` gives `peak_bytes=123` and `oom_kills=0`.
  - `test_report_without_cgroup_v2` (Review Focus 5): an empty directory gives `peak_bytes=None` and the reason `"memory.peak not available (cgroup v1 or no cap)"`.

- [ ] **Step 2: Run** `MAPTEST tests/test_pack.py tests/test_report.py`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Packing:** `pack` runs `python3 -m pmtiles.convert`, or a small inline call of `disk_to_pmtiles(tiles_dir, tmp, "auto")` in a subprocess with `env={..., "PYTHONHASHSEED": "0"}`, writing through `atomic_write`.
  - **Verifying:** `verify` runs `pmtiles verify` and `pmtiles show --header-json`, then compares `min_zoom`, `max_zoom` and the bounds to within 1e-6°.
  - **Worker memory:** the largest worker's memory comes from `resource.getrusage(RUSAGE_CHILDREN).ru_maxrss`.

- [ ] **Step 4: Run.** Expected: 5 passed.

- [ ] **Step 5: Commit** `feat(map): pack PMTiles and write the run report`.

### Task 13: CLI

**Files:**

- Create: `src/seerah_map/cli.py`, `src/seerah_map/__main__.py`, `tests/test_cli.py`

**Interfaces:**

- Consumes: every earlier task.
- Produces:
  - `main(argv: list[str] | None = None) -> int`;
  - the subcommands:
    - `fetch --profile REL`;
    - `build --profile REL --variant {painted,value} [--mode {average-down,per-zoom}] [--workers N] [--per-worker-mib N]`;
    - `clean [--fingerprint F]`.

    `REL` is a profile path relative to `/data` without `.yaml` (for example `trial/full`, or `example/example` when `map-data/` is mounted as `/data`). Its `edits.yaml` and `<name>.lock` are read from the same folder.
  - `worker_count(memory_max: str, per_worker_mib: int, workers: int | None) -> tuple[int, str]`, returning the count and the reason;
  - the environment inputs `SEERAH_TOOL_COMMIT`, `SEERAH_DATA_COMMIT` and `SEERAH_IMAGE_DIGEST`. A missing one is recorded as `"unknown"` with a warning, so plain `docker run` in a cloud runner still works.
  - the output folder `/out/<name>-<variant>-<fp8>/`.

- [ ] **Step 1: Write the failing tests:**
  - `test_worker_count_with_cap`: `worker_count("8589934592", 2048, None)` gives 4.
  - `test_worker_count_without_cap` (Review Focus 3): `worker_count("max", 2048, None)` gives `(1, "no memory cap; using 1 worker")`, and `worker_count("max", 2048, 3)` gives 3.
  - `test_build_missing_lock_message`: `build` with no lock beside the profile exits 2, and its message says "run fetch first, then commit the lock".
  - `test_exit_codes`: a `ConfigError` exits 2, and a `CompletenessError` or `FetchError` exits 1.
  - `test_build_pipeline_order` (with every stage monkeypatched): the stages run in the order block, tile, pack, report, remove the work area. On an exception the work area is kept.
  - `test_per_zoom_mode_scales_blur`: in `per-zoom` mode at zoom 10, the style settings passed on have `blur_px == 7 * 2**(10-12)`.

- [ ] **Step 2: Run** `MAPTEST tests/test_cli.py`. Expected: FAIL.

- [ ] **Step 3: Implement** with `argparse`; `__main__.py` calls `sys.exit(main())`. The block stage's `on_block` writes one log line per block, `block <id> done (<n>/<total>)`.

- [ ] **Step 4: Run.** Expected: 6 passed.

- [ ] **Step 5: Commit** `feat(map): add the command line`.

### Task 14: Synthetic example and end-to-end tests

**Files:**

- Create: `map-data/example/example.yaml`, `edits.yaml`, `example.lock`, `peninsula.geojson`, `camera-path.json`, `tests/test_build_example.py`
- Modify: `tests/conftest.py` (fixtures to run the CLI and kill it after N progress lines)

**Interfaces:**

- Consumes: `cli.main` as a subprocess (`python3 -m seerah_map`).
- Produces: the example output used by phase 2's fixtures.

- [ ] **Step 1: Owner decision U1.** Confirm the window, or replace it. Do not continue until it is confirmed.

- [ ] **Step 2: Write the example's files.**
  - **`example.yaml`:** `purpose: trial`; camera bounds equal to the window; `margin_deg: 0`; `fade_deg: 0.02`; `max_zoom: 12`; `block_zoom: 11`; `webp_quality: 80`; the default style.
  - **`edits.yaml`:** one `raise` of 20 m and 600 m wide, with `feather_m: 300`, crossing a block edge, `review: approved`, and a comment saying it is synthetic.
  - **The lock:** generate `example.lock` with `fetch` and commit it.
  - **`camera-path.json`:** two stops over the window; the camera limits equal to the window.

- [ ] **Step 3: Write the failing tests** (all `slow`):
  - `test_example_builds_complete`: `fetch`, then `build --variant painted`, gives a folder with `relief.pmtiles` and `metadata.json` with `"trial": true`. `pmtiles verify` passes, and every expected tile decodes.
  - `test_example_value_variant`: `--variant value` builds, and `metadata.json` has `"encoding": "terrarium-value"`.
  - `test_double_build_identical`: two builds from scratch (after `clean`) give equal SHA-256 hashes of `relief.pmtiles`.
  - `test_resume_after_kill_in_block_stage`: the build is killed after 2 `block ... done` lines, then rerun; the decoded zoom-12 tiles equal a clean build's.
  - `test_resume_after_kill_in_tile_stage`: the build is killed after the line `tile stage started`, then rerun; same check.
  - `test_work_area_removed_on_success_kept_on_failure`: after success `clean` lists nothing for this fingerprint; after a forced failure (a corrupted lock entry) the area remains.
  - `test_absent_neighbour_is_sea`: pixels in the absent cell decode with alpha 0, and the window's land pixels have alpha above 0 somewhere.

- [ ] **Step 4: Run** `MAPTEST -m slow tests/test_build_example.py`. Expected: FAIL until all the earlier tasks are wired, then 7 passed.

- [ ] **Step 5: Commit** `test(map): build the synthetic example end to end`.

### Task 15: Host launcher

**Files:**

- Create: `tools/map/run.py`, `tools/map/tests/test_run.py` (host-side, standard library only)

**Interfaces:**

- Produces:
  - `docker_args(cmd: list[str], public: Path, private: Path, out: Path, image: str, memory_mib: int, network: bool, user: tuple[int, int], provenance: dict[str, str]) -> list[str]`;
  - `image_tag(dockerfile: Path, requirements: Path) -> str`, the first 16 hexadecimal characters of the SHA-256 of the two files' bytes concatenated;
  - `check_clean(repo: Path) -> str`, returning the commit, or raising `SystemExit` with the command to commit or stash;
  - `main(argv) -> int`.

  The usage is `run.py [--private PATH] [--data PATH] [--out PATH] [--memory-mib 8192] [--per-worker-mib N] [--workers N] [--image REF] -- <command> [args]`.

- [ ] **Step 1: Write the failing tests.** They run on the host with `python3 -m unittest tools/map/tests/test_run.py`, because the launcher must work without the image.
  - `test_args_are_a_list_and_paths_with_spaces_survive`: a public path containing `My Projects` appears as one list element.
  - `test_mounts_read_only_and_volumes`: the arguments contain `/src:ro`, `/data:ro`, `seerah-map-cache:/cache`, `seerah-map-work:/work` and `--security-opt`, `label=disable`.
  - `test_network_none_except_fetch`: `build` gets `--network none`; `fetch` does not.
  - `test_memory_flags`: `--memory 8192m --memory-swap 8192m`.
  - `test_image_tag_matches_ci_rule`: known bytes give a known tag.
  - `test_dirty_checkout_refused`: a temporary git repository with an untracked file makes `check_clean` raise `SystemExit` with a message naming the repository.

- [ ] **Step 2: Run** `python3 -m unittest tools/map/tests/test_run.py`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Defaults:** `--private` is `../seerah-content`, and `/data` is mounted from `<private>/map` unless `--data` is given (for the public example, `--data map-data`); `--out` is `map-data/dist`. The clean-checkout check covers the repository that holds `--data`.
  - **Image:** `docker pull ghcr.io/mohdfarag/seerah-map:<tag>`, then `docker image inspect --format '{{index .RepoDigests 0}}'` for the digest.
  - **Command:** `python3 -m seerah_map <cmd>`, run with `subprocess.run(args)`.
  - **Errors:** a missing Docker, an image that cannot be pulled, or a missing output folder each print the next step.

- [ ] **Step 4: Run.** Expected: 6 passed.

- [ ] **Step 5: Smoke test** on the owner's machine: `python3 tools/map/run.py --data map-data -- fetch --profile example/example`. Use the locally built image with `--image seerah-map:dev` until GHCR has one. Expected: it exits 0 and reports the lock as verified.

- [ ] **Step 6: Commit** `feat(map): add the host launcher`.

### Task 16: CI workflow, Dependabot and docs

**Files:**

- Create: `.github/workflows/map.yml`, `tools/map/README.md`
- Modify: `.github/dependabot.yml`, `tools/README.md`

**Interfaces:**

- Consumes: `run.py`'s `image_tag` rule, reimplemented as one `sha256sum` line in the workflow and checked by a step that compares it with `python3 tools/map/run.py --print-tag`.

- [ ] **Step 1: Write `map.yml`.**
  - **Trigger:** `push` to `main` and `pull_request`, with paths `tools/map/**`, `map-data/example/**` and `.github/workflows/map.yml`.
  - **Top level:** `permissions: {}`.
  - **Job `image`** (`packages: write`):
    1. compute the tag, and check that it equals `run.py --print-tag`;
    2. `docker manifest inspect ghcr.io/mohdfarag/seerah-map:<tag>`;
    3. if the tag is missing, build it, and on `main` push it with `GITHUB_TOKEN`.
  - **Job `test`** (`contents: read`, needs `image`):
    - restore `actions/cache`, keyed on `hashFiles('map-data/example/example.lock')`, into `$RUNNER_TEMP/cache`;
    - run the image (on pull requests, the freshly built local image), with the checkout mounted read-only and the cache mounted at `/cache`, under `--memory 6g`: `python3 -m pytest tools/map` (all, including `slow`);
    - run `python3 -m unittest tools/map/tests/test_run.py` on the runner;
    - set `SEERAH_TOOL_COMMIT` to `${{ github.sha }}`.
  - **Conventions:** actions pinned by commit and `persist-credentials: false`, as in `android.yml`.

- [ ] **Step 2: Dependabot.** Add `docker` and `pip` entries for `/tools/map`, using the existing schedule and grouping style.

- [ ] **Step 3: Docs.**
  - **`tools/map/README.md`:** what the tool does; the three commands; the profile, edits and lock layout; `run.py` usage; memory flags; the output folder; the rule that trial output is never packaged; how CI runs it.
  - **`tools/README.md`:** add a row for `map/`. This pulls part of the spec's file 42 into phase 1, so the tool is documented when it lands.

- [ ] **Step 4: Verify.** `pre-commit run --all-files` passes (ruff, markdownlint, zizmor on `map.yml`, and REUSE). Push to a branch only with the owner's approval; the `map` workflow is green on that pull request.

- [ ] **Step 5: Commit** `ci(map): build the image and run the map tool's tests`.

---

## Self-review

- **Spec coverage, phase 1, files 1–18:**

  | Spec file | Task |
  | --- | --- |
  | 1, 2, 3 | 1 |
  | 4 | 15 |
  | 5 | 13 |
  | 6, 7 | 2 |
  | 8 | 3 |
  | 9 | 9 |
  | 10 | 4 |
  | 11 | 5–8 |
  | 12 | 10 |
  | 13 | 11 |
  | 14 | 12 (pack) |
  | 15 | 12 (report) |
  | 16 | 2–14 |
  | 17 | 14 |
  | 18 | 16 |

- **Deviations, each flagged:**
  - U3: tippecanoe and WhiteboxTools move to phase 3;
  - U5: the thread variables are set in the Dockerfile;
  - the `tools/README.md` row and `tools/map/README.md` are pulled forward from file 42.
- **Out of phase 1:** the base layer (phase 3) and the valley probe (trial step 1, which needs phase 3's image).
- **Type consistency:** `Profile`, `Lock`, `Cell`, `FileRef`, `Edit`, `StyleSettings`, `Block`, `Window`, `WorkArea` and `Provenance` are defined once (Tasks 2–4 and 12) and used with the same names afterwards.
