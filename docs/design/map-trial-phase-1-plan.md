# Map trial, phase 1: tool core and example

- **Author:** MohdFaragDev
- **Status:** Revised after an [EGM review](egm-review-map-trial-phase-1/consolidated_review_report.md); the owner accepted its recommendations D1–D7 on 2026-11-15.
- **Date:** 2026-11-15

> **For agentic workers:** use superpowers:subagent-driven-development or superpowers:executing-plans to carry out this plan task by task. Steps use checkboxes (`- [ ]`) for tracking.

**Goal:** a containerised map build tool that fetches pinned Copernicus data, renders style C relief in blocks, tiles it with GDAL, packs it into PMTiles with a run report, and is proven end to end on a synthetic example in CI.

**Where this fits.** The approved [specification](map-trial-build.md) plans a trial that answers [ADR 0009](../adr/0009-map-data-pipeline.md) decision 13. It has three implementation phases:

- **Phase 1** (this plan): the build tool and its CI.
- **Phase 2:** the first piece of `:core:map` and the `benchmark` app build that shows the trial maps on the three test phones and tablet.
- **Phase 3:** the base layer (land, coast, Natural Earth) and the documents.

The trial's own steps, starting with step 1's on-device checks, need all three phases. From phase 1, phase 2 takes the output folder layout (`relief.pmtiles`, `metadata.json`, `camera-path.json`) and the example build as a test fixture.

**Terms used here:**

- **Spike:** the finished technical prototype (reference only, AGENTS.md).
- **Build 11:** the spike build whose look the owner approved.
- **Style C:** that look. Relief smoothed and shaded by an oblique light, quantised into three bands, and painted as semi-transparent brown ink.
- **Goals (spec):**
  - G3: no seams or missing relief tiles;
  - G4: resumable and repeatable;
  - G5: little custom code;
  - G8: keep build 11's look;
  - G9: trial output marked as trial.

**Architecture:**

- **Pure array functions:** `grid` (tile maths with morecantile), `mask`, `style` and `encode`; `edits` also uses GDAL to rasterise shapes.
- **Stage modules** call GDAL and Protomaps' tools: the block stage, the tile stage, and pack.
- **A CLI** wires the stages together.
- **The image** holds only the toolchain, and the code is mounted read-only from the checkout.
- **A host launcher,** `run.py`, starts it with the right mounts and limits.
- **Portability:** CI and any other Docker host on linux/amd64 call the same `python3 -m seerah_map` entry point. The owner asked on 2026-11-14 that the pipeline stay portable between cloud and local environments.

**Two variants:**

- **painted:** banded RGBA tiles in WebP, drawn by MapLibre as a raster layer. This is options (a) and (b) in the specification.
- **value:** continuous darkness in a fake-elevation (Terrarium) PNG, for MapLibre's `color-relief` layer. This is option (c), which is unbanded because the app draws the bands.

Each build makes one variant.

**Tech stack:**

- **Image:** GDAL 3.13.3 (`ghcr.io/osgeo/gdal:ubuntu-small-3.13.3`) and go-pmtiles v1.31.2, with Python 3 packages: numpy (the base image's version), scipy, Protomaps' `pmtiles`, morecantile, ruamel.yaml, jsonschema 4.26.0 and pytest.
- **CI:** GitHub Actions, and the image registry GHCR.

**Spec:** [docs/design/map-trial-build.md](map-trial-build.md) (Approved, 2026-11-14), phase 1, files 1–18.

## Global Constraints

**Paths, image and pinning:**

- **Paths:** every path in this plan is relative to the repository root, unless it starts with `/` (a container path) or `seerah-content/` (the private repository).
- **Container paths:** `/src` and `/data` are read-only; `/cache`, `/work` and `/out` are writable.
- **The image** holds only the toolchain. The tool's code runs from `/src` with `PYTHONPATH=/src/tools/map/src`, and there is no `ENTRYPOINT`.
- **Pinning:**
  - Every tool and wheel is pinned by digest, commit, SHA-256 or a hash-locked `requirements.txt`.
  - A **direct** dependency is pinned with `==` to its newest release that is at least 14 days old on the implementation date.
  - **Transitive** pins are whatever `pip-compile --generate-hashes` resolves; this is the one recorded exception to the 14-day rule.
  - `pip-tools` is used only in a throwaway container, never in the image.
- **pip itself** (D3): installed from a pip wheel downloaded by URL and checked against a pinned SHA-256, never from Ubuntu's archive.
- **numpy** is pinned in `tools/map/requirements.in` to the exact version the base image ships, so GDAL's numpy bindings keep working.

**Behaviour and output:**

- **Profiles:** `purpose` is the constant `trial`, and no other value is accepted. Every output's `metadata.json` and PMTiles metadata carry `"trial": true`.
- **Edits:** only edits with `review: approved` are applied; the rest are listed in the build log. Only the owner sets `approved` (AGENTS.md).
- **Determinism:** the same inputs on the same machine and image give identical PMTiles bytes; `disk_to_pmtiles` runs with `PYTHONHASHSEED=0`.
- **No missing relief tiles:** every relief tile exists at every zoom, transparent ones included, so `gdal raster tile` never gets `--skip-blank`.

**Code rules:**

- **Style values** (spec lines 241 and 259; pixel values are at zoom 12):
  - blur σ 7 px, band blur σ 1.5 px with a cutoff radius of 4 px;
  - exaggeration 7.36, which is 8 × cos 23°, so slopes at 23° N match build 11;
  - light from 315° at 45°, contrast 1.6, 3 bands;
  - ink RGB 150, 118, 72, maximum alpha 0.6;
  - mask σ 4 px, land at mask ≥ 128.

  All blurs use `mode="nearest"`, as the spike pads with edge values.
- **No spike code is copied.** `tools/spike/relief_style.py` is only run once, to make a regression fixture.
- **Python:** 3.11 syntax, to the repository's ruff settings in `pyproject.toml`. Every module and every public function has a docstring stating its inputs, outputs and units.

**Running the tests:**

- **In the image** ("MAPTEST" below), from the repository root:

  ```sh
  docker build -t seerah-map:dev tools/map
  docker run --rm --security-opt label=disable \
    -v "$PWD:/src:ro" -v "$PWD/map-data:/data:ro" -v seerah-map-test-cache:/cache \
    -w /src/tools/map -e PYTHONPATH=/src/tools/map/src \
    seerah-map:dev python3 -m pytest <args>
  ```

  `<args>` are paths relative to `tools/map` (for example `tests/test_grid.py`); with no `<args>`, pytest runs `tests/`.
- **On the host** ("HOSTTEST"), for the launcher: `python3 -m unittest discover -s tools/map/host_tests`.

**Process:**

- **Owner gates:** an agent stops at every step marked **Owner gate** and does not continue until the owner confirms. Every other decision below was approved with the plan.
- **Commits:** Conventional Commits with scope `map` (for example `feat(map): add the grid module`), one per task, on this branch. Nothing is pushed or merged without the owner's approval.

## Decisions (owner, 2026-11-15)

| # | Decision | Status |
| --- | --- | --- |
| D1 | **The public CI example window.** It must be away from any Seerah event or named historical place, span one present cell and its absent all-sea neighbour, and hold land inside its edge. The first proposal (Dhofar, 17.50–17.75° N, 56.80–57.05° E) was measured on 2026-11-15 at 0.1% land and rejected. **Proposal:** 20.50–20.75° N, 58.80–59.05° E, off Masirah Island, Oman. That is cell N20 E058, whose strip at 58.75–59.00° E is 27% land (measured from the Water Body Mask on 2026-11-15), plus its neighbour N20 E059, which is absent from `tileList.txt`. | **Owner gate** at Task 14, Step 1 |
| D2 | **The image** is `ghcr.io/mohdfarag/seerah-map`. The name lives in one constant in `tools/map/run.py`, and CI checks that it equals the lowercased `github.repository_owner`. The owner makes the package public after its first push; if it cannot be public, pulls use a personal access token (specification step-1 check 9). | Approved with the plan |
| D3 | **pip** comes from a hash-pinned wheel, not from Ubuntu's archive. | Approved with the plan |
| D4 | **`--per-worker-mib`** defaults to 2048 until specification step-1 check 12 measures it, and `GDAL_CACHEMAX=256` counts inside that allowance. A missing `memory.max` (cgroup v1) is treated as no cap. | Approved with the plan |
| D5 | **Per-zoom mode** (trial item 5) is deferred to a short follow-up before trial step 2. Phase 1 has no `--mode` option. | Approved with the plan |
| D6 | **The deviations list below.** | Approved with the plan |
| D7 | **Approving the plan approves D2–D6.** D1 keeps its gate, and Task 16 ends at an owner gate: the owner approves the push and opens the pull request, or runs the workflow (AGENTS.md: no pull request unless the owner asks). | Approved with the plan |

**Deviations from the specification** (D6):

- tippecanoe and WhiteboxTools join the image in phase 3, because phase 1 uses neither.
- The thread and cache variables are set as `ENV` in the Dockerfile rather than by `run.py`, so every runner gets them; this follows from the portability requirement.
- Profiles are named by their path relative to `/data`, without `.yaml` (for example `trial/full`, or `example/example` with `map-data` mounted).
- `run.py` gains `--data`, `--image` and `--print-tag`.
- Parts of spec files 41 (`docs/map-trial/step-1.md`), 42 (the `tools/README.md` and `map-data/README.md` rows) and 44 (Dependabot) are pulled into phase 1.
- `natural_earth_max_zoom` and the coverage-check tests move to phase 3, with the base layer.
- Per-zoom mode is deferred (D5).

The specification's step-1 checks 5, 6 and 7 are settled in this phase, in Tasks 11 and 5.

## Review Focus

Five inputs the spec implies, each pinned by a test:

1. **An interrupted download** leaves a `.part` file. The next `fetch` replaces it, never trusts it, and leaves the lock unchanged (Task 9, `test_fetch_discards_partial`).
2. **A cached file no longer matches the lock:** `fetch` exits 1, naming the file and both hashes (Task 9, `test_fetch_rejects_changed_file`).
3. **No memory cap** (`memory.max` reads `max` or is missing): the build uses `--workers`, or 1 worker, and says so (Task 13, `test_worker_count_without_cap`).
4. **A profile outside the N/E quadrant:** rejected with a clear message (Task 2, `test_profile_rejects_southern_or_western_extent`).
5. **cgroup v1, or no `memory.peak`:** the report records `null` with a reason (Task 12, `test_report_without_cgroup_v2`).

## File structure

```text
tools/map/
  Dockerfile                      toolchain image (Task 1)
  requirements.in, requirements.txt
  pyproject.toml                  pytest settings (Task 1)
  run.py                          host launcher (Task 15)
  README.md                       how to run, the docker run contract (Task 16)
  src/seerah_map/
    __init__.py                   (Task 1)
    __main__.py, cli.py           commands (Task 13)
    strict_yaml.py, config.py     loading and validation (Task 2)
    schema/profile.schema.json, schema/edits.schema.json   (Task 2)
    grid.py                       global grid, blocks, borders, tiles (Task 3)
    workarea.py                   fingerprint, work areas, atomic writes (Task 4)
    mask.py                       land mask, invalid-pixel fill (Task 5)
    edits.py                      edit rasterising and weights (Task 6)
    style.py                      darkness, edge fade (Task 7)
    encode.py                     painted and value encoders (Task 8)
    fetch.py                      downloads and lock (Task 9)
    blockstage.py                 block stage (Task 10)
    tilestage.py                  tile stage (Task 11)
    pack.py, report.py            PMTiles, metadata, run report (Task 12)
  tests/
    conftest.py                   shared fixtures: local HTTP server (Task 9), CLI runner (Task 14)
    fixtures/                     YAML samples (Task 2), spike_relief.npz + make_spike_relief.py (Task 7), fetch/ (Task 9)
    test_*.py                     one per module (Tasks 1–14)
  host_tests/test_run.py          launcher tests, run on the host (Task 15)
map-data/example/                 example.yaml, edits.yaml, example.lock, peninsula.geojson, camera-path.json (Task 14)
docs/map-trial/step-1.md          step-1 check results (Tasks 5, 11)
.github/workflows/map.yml         (Task 16)
.github/dependabot.yml, tools/README.md, map-data/README.md   (Task 16)
```

**Not used by this tool, but written now for later phases:**

- `peninsula.geojson`: the outline for the report's inside/outside size split (used here);
- `camera-path.json`: read by the app in phase 2;
- the Natural Earth files: read by the base layer in phase 3;
- the mask blocks: read by the base layer in phase 3.

---

## Tasks

### Task 1: Toolchain image and test harness

**Files:**

- Create:
  - `tools/map/Dockerfile`
  - `tools/map/requirements.in` and `tools/map/requirements.txt`
  - `tools/map/pyproject.toml`
  - `tools/map/src/seerah_map/__init__.py`
  - `tools/map/tests/test_image.py`

**Interfaces:**

- Produces:
  - the image `seerah-map:dev`;
  - `seerah_map.__version__ = "0.1.0"`;
  - the pytest marker `slow`.

- [ ] **Step 1: Find the base image's numpy version.** Run `docker run --rm ghcr.io/osgeo/gdal:ubuntu-small-3.13.3 python3 -c "import numpy; print(numpy.__version__)"`, and pin that version in `requirements.in`.

- [ ] **Step 2: Write the failing tests** in `tools/map/tests/test_image.py`:

```python
import os, shutil, pathlib


def test_toolchain_present():
    from osgeo import gdal, gdal_array, osr  # noqa: F401
    import numpy, scipy, pmtiles, morecantile, ruamel.yaml, jsonschema  # noqa: F401

    assert gdal.__version__.startswith("3.13")
    assert numpy.__file__.startswith("/usr/lib/python3/dist-packages")
    assert shutil.which("pmtiles") is not None


def test_morecantile_and_osr_in_one_process():
    import morecantile
    from osgeo import osr

    tile = morecantile.tms.get("WebMercatorQuad").tile(39.6, 24.5, 12)
    t = osr.CoordinateTransformation(
        osr.SpatialReference(wkt=osr.SRS_WKT_WGS84_LAT_LONG), _srs3857()
    )
    assert tile.z == 12 and t is not None


def test_single_thread_environment():
    for name in ("GDAL_NUM_THREADS", "OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS"):
        assert os.environ[name] == "1"
    assert os.environ["GDAL_CACHEMAX"] == "256"


def test_writable_mount_points():
    for p in ("/cache", "/work", "/out"):
        assert oct(pathlib.Path(p).stat().st_mode)[-4:] == "1777"


def test_in_memory_vector_driver():
    from osgeo import ogr

    assert ogr.GetDriverByName("MEM") or ogr.GetDriverByName("Memory")
```

  `_srs3857()` is a small helper in the same file that returns an EPSG:3857 `SpatialReference`.

- [ ] **Step 3: Run** MAPTEST `tests/test_image.py`. Expected: FAIL (the image does not build yet).

- [ ] **Step 4: Write the Dockerfile and its dependency files.**
  - **Base:** `FROM ghcr.io/osgeo/gdal:ubuntu-small-3.13.3@sha256:<digest>`, with the digest from `docker buildx imagetools inspect`.
  - **pip** (D3): `ADD` the pip wheel by URL with `--checksum=sha256:<pinned>`, then `python3 /tmp/pip.whl/pip install --break-system-packages --require-hashes -r requirements.txt`.
  - **go-pmtiles:** v1.31.2 `go-pmtiles_1.31.2_Linux_x86_64.tar.gz`, checked by SHA-256. The binary goes to `/usr/local/bin/pmtiles`, and its `LICENSE` to `/usr/share/licenses/go-pmtiles/`.
  - **Mount points:** `RUN mkdir -m 1777 /cache /work /out`.
  - **Environment:** `ENV GDAL_NUM_THREADS=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 GDAL_CACHEMAX=256 PYTHONDONTWRITEBYTECODE=1`.
  - **Comments:** one per pin, giving its source.
  - **`requirements.in`:**

    ```text
    numpy==<base version>
    scipy==<pin>
    pmtiles==<pin>
    morecantile==<pin>
    ruamel.yaml==<pin>
    jsonschema==4.26.0
    pytest==<pin>
    ```

    `requirements.txt` comes from `pip-compile --generate-hashes`, run in a throwaway container made from the base image.
  - **`pyproject.toml`:** `[tool.pytest.ini_options]` with `testpaths = ["tests"]`, `pythonpath = ["src"]`, `addopts = "-p no:cacheprovider"` and `markers = ["slow: needs GDAL and the example's downloaded cells"]`.

- [ ] **Step 5: Run** MAPTEST `tests/test_image.py`. Expected: 5 passed. If `test_toolchain_present` shows a pip-installed numpy, fix the numpy pin; do not loosen the test.

- [ ] **Step 6: Commit** `build(map): add the toolchain image and test harness`.

### Task 2: Strict YAML and configuration

**Files:**

- Create:
  - `tools/map/src/seerah_map/strict_yaml.py` and `tools/map/src/seerah_map/config.py`
  - `tools/map/src/seerah_map/schema/profile.schema.json` and `tools/map/src/seerah_map/schema/edits.schema.json`
  - `tools/map/tests/test_config.py`
  - `tools/map/tests/fixtures/profile_ok.yaml` and `tools/map/tests/fixtures/edits_ok.yaml`

**Interfaces:**

- Produces:
  - `strict_yaml.load(path: Path) -> object` and `strict_yaml.dump(obj, path: Path) -> None`;
  - `config.Bounds(west, south, east, north: float)`;
  - `config.StyleSettings` (frozen; field names and defaults as in the table below);
  - `config.Profile(name: str, folder: Path, purpose: str, camera_bounds: Bounds, margin_deg: float, fade_deg: float, max_zoom: int, block_zoom: int, webp_quality: int, style: StyleSettings, peninsula_outline: Path)`:
    - `name` is the profile file's stem;
    - `peninsula_outline` is resolved against `folder`;
    - the property `data_extent -> Bounds` is the camera bounds widened by `margin_deg` on every side;
  - `config.Edit(id: str, geometry: dict, operation: str, amount_m: float | None, height_m: float | None, width_m: float | None, feather_m: float, review: str)`;
  - `config.FileRef(url: str, size: int, sha256: str, path: str)`, where `path` is relative to `/cache`;
  - `config.Cell(lat: int, lon: int, absent: bool, dem: FileRef | None, wbm: FileRef | None)`;
  - `config.Lock(created: str, tile_list_sha256: str, cells: tuple[Cell, ...], natural_earth: tuple[FileRef, ...])`;
  - the loaders:
    - `load_profile(path) -> Profile`;
    - `load_edits(path) -> list[Edit]`, which returns approved and other edits alike;
    - `load_lock(path) -> Lock` and `dump_lock(lock, path)`;
  - `ConfigError(Exception)`, whose message names the file, the key, what was expected and what was found.

**Profile keys** (YAML top level; `additionalProperties: false`):

| Key | Type | Required | Range / default |
| --- | --- | --- | --- |
| `purpose` | string | yes | const `trial` |
| `camera_bounds` | object `{west, south, east, north}` | yes | all ≥ 0 (N/E quadrant); `west < east`, `south < north` |
| `margin_deg` | number | yes | ≥ 0 |
| `fade_deg` | number | yes | ≥ 0, ≤ `margin_deg` when `margin_deg` > 0 |
| `max_zoom` | integer | yes | 0–14 |
| `block_zoom` | integer | yes | 6 ≤ `block_zoom` ≤ `max_zoom` |
| `webp_quality` | integer | no | 1–100, default 80 |
| `style` | object | no | each key optional, defaults as below |
| `peninsula_outline` | string | yes | path to a GeoJSON file, relative to the profile's folder |

**Style keys and defaults:**

| Key | Default |
| --- | --- |
| `blur_px` | 7.0 |
| `band_blur_px` | 1.5 (0 allowed) |
| `exaggeration` | 7.36 |
| `azimuth_deg` | 315 |
| `altitude_deg` | 45 |
| `contrast` | 1.6 |
| `bands` | 3 |
| `ink_rgb` | [150, 118, 72] |
| `max_alpha` | 0.6 |

**Edits file** (top level; `additionalProperties: false`):

| Key | Type | Required | Notes |
| --- | --- | --- | --- |
| `schema` | string | yes | const `trial-0` |
| `purpose` | string | yes | const `trial` |
| `edits` | array | yes | the edits, possibly empty |

**Each edit:**

| Key | Type | Required | Notes |
| --- | --- | --- | --- |
| `id` | string | yes | unique in the file |
| `geometry` | GeoJSON Polygon or LineString | yes | longitude and latitude |
| `operation` | `raise`, `flatten-to` or `carve` | yes | |
| `amount_m` | number | for `raise` and `carve` | `raise`: signed; `carve`: positive depth |
| `height_m` | number | for `flatten-to` | absolute height |
| `width_m` | number > 0 | for `carve` | |
| `feather_m` | number > 0 | yes | |
| `review` | the content schema's `review` | yes | |
| `sources` | array of the content schema's `source` | no | |

`review` and `source` are copied from `content/schema/content.schema.json` `$defs`, with a `$comment` naming that file and the commit they were copied from.

- [ ] **Step 1: Write the failing tests** in `tools/map/tests/test_config.py`:
  - `test_duplicate_key_rejected`: the YAML `a: 1\na: 2` raises `ConfigError` naming `a`.
  - `test_yes_no_and_dates_stay_strings`: `x: yes`, `y: no` and `d: 2024-01-01` load as `"yes"`, `"no"` and `"2024-01-01"`.
  - `test_profile_loads`: `profile_ok.yaml` (camera bounds 34, 12, 60, 32 and a 1° margin) gives `style.exaggeration == 7.36`, `data_extent == Bounds(33, 11, 61, 33)` and `name == "profile_ok"`.
  - `test_profile_rejects_release_purpose`: `purpose: release` raises `ConfigError` naming `purpose`.
  - `test_profile_rejects_southern_or_western_extent`: `south: -1` and `west: -1` each raise `ConfigError` mentioning "N/E".
  - `test_edits_rules`:
    - `review: draft` loads;
    - `operation: lower` is rejected;
    - `schema: trial-1` is rejected;
    - `feather_m: 0` is rejected;
    - `carve` without `width_m` is rejected.
  - `test_lock_round_trip`: `dump_lock` followed by `load_lock` gives an equal `Lock`.

- [ ] **Step 2: Run** MAPTEST `tests/test_config.py`. Expected: FAIL (`ModuleNotFoundError`).

- [ ] **Step 3: Implement the loader, the schemas and the dataclasses.**
  - **`strict_yaml`:** `ruamel.yaml.YAML(typ="safe", pure=True)` with `version=(1, 2)` and `allow_duplicate_keys=False`. Remove the timestamp resolver, so dates stay strings.
  - **Validation:** `jsonschema.Draft202012Validator`, with the first error turned into `ConfigError`.
  - **Lock:** no schema; `load_lock` checks it while building the dataclasses.

- [ ] **Step 4: Run.** Expected: 7 passed.

- [ ] **Step 5: Commit** `feat(map): load and validate profiles, edits and locks`.

### Task 3: Grid

**Files:**

- Create: `tools/map/src/seerah_map/grid.py` and `tools/map/tests/test_grid.py`

**Interfaces:**

- Consumes: `Bounds`, `StyleSettings` and `Edit`.
- Produces:
  - **Constants:** `BASE_ZOOM = 12`, `TILE_PX = 256`, `MASK_SIGMA_PX = 4.0` and `BAND_BLUR_RADIUS_PX = 4`.
  - **`Window(col0: int, row0: int, cols: int, rows: int)`:** a window of zoom-12 global pixels, with `.bounds_3857()`, `.geotransform()` and `.pad(n) -> Window`.
  - **`Block(z: int, x: int, y: int, window: Window)`:** its `.id` is `"z9-x123-y45"`.
  - **`blocks(extent: Bounds, block_zoom: int) -> list[Block]`:** every tile at `block_zoom` that intersects the extent, sorted by `(y, x)`.
  - **`pixel_m() -> float`:** 40075016.68557849 / 256 / 2**12.
  - **`border_px(style: StyleSettings, edits: list[Edit], block: Block) -> int`:** `ceil(3 * blur_px) + BAND_BLUR_RADIUS_PX + ceil(3 * MASK_SIGMA_PX) + 1`, plus the largest `feather_m` (and half of `width_m` for carves), converted to pixels at the block's poleward edge latitude.
  - **`row_latitudes(window: Window) -> np.ndarray`:** latitudes at pixel centres.
  - **`expected_tiles(block_list: list[Block], max_zoom: int) -> set[tuple[int, int, int]]`:** every tile at zooms 0 to `max_zoom` that the blocks' windows cover.
  - **`view_reach_km(maplibre_zoom: float, half_width_dp: float, lat_deg: float) -> float`:** how far half a screen of `half_width_dp` Android density-independent pixels reaches. MapLibre zooms use 512-pixel tiles, hence the 512. It exists to check the spec's view-reach table.

- [ ] **Step 1: Write the failing tests:**
  - `test_blocks_on_whole_pixels`: with `block_zoom=9`, every `col0` and `row0` is a multiple of 2048, and the blocks cover `Bounds(33, 11, 61, 33)`.
  - `test_block_count_data_extent`: between 1,350 and 1,450 blocks.
  - `test_border_default`: with the default style and no edits, `border_px == 21 + 4 + 12 + 1 == 38`.
  - `test_border_grows_with_feather`: a 500 m feather adds `ceil(500 / (pixel_m() * cos(poleward_lat)))`.
  - `test_row_latitudes_decrease_southward`.
  - `test_expected_tiles_from_blocks`: one zoom-9 block with `max_zoom=12` gives 64 tiles at zoom 12, 16 at zoom 11, 4 at zoom 10, 1 at zoom 9, and 1 at each lower zoom.
  - `test_view_reach_table`: `view_reach_km(6, 550, 22)` is within 5% of 620, and `view_reach_km(9, 550, 22)` within 5% of 80.

- [ ] **Step 2: Run** MAPTEST `tests/test_grid.py`. Expected: FAIL.

- [ ] **Step 3: Implement** with `morecantile.tms.get("WebMercatorQuad")` for the tile ranges and bounds; the pixel windows are integer arithmetic.

- [ ] **Step 4: Run.** Expected: 7 passed.

- [ ] **Step 5: Commit** `feat(map): add the global grid and block layout`.

### Task 4: Fingerprint and work areas

**Files:**

- Create: `tools/map/src/seerah_map/workarea.py` and `tools/map/tests/test_workarea.py`

**Interfaces:**

- Produces:
  - **`code_hash(src_root: Path) -> str`:** the SHA-256 over the relative path and bytes of every file under `src_root`, in sorted path order.
  - **`toolchain_id() -> str`:** the GDAL version plus the sorted `name==version` list from `importlib.metadata.distributions()`.
  - **`fingerprint(profile: bytes, edits: bytes, lock: bytes, tool_commit: str | None, image_digest: str | None, variant: str, src_root: Path) -> str`:** the SHA-256 of the parts joined with `b"\0"`. A missing `tool_commit` is replaced by `code_hash(src_root)`, and a missing `image_digest` by `toolchain_id()`.
  - **`WorkArea(root: Path, fp: str)`:** with `.path`, `.style_dir`, `.mask_dir`, `.tiles_dir` (`<area>/tiles/`) and `.tiles_done` (`<area>/tiles.done`).
  - **`open_area(work_root: Path, fp: str) -> WorkArea`:** idempotent. Areas with other fingerprints are left alone, so several areas coexist.
  - **`atomic_write(path: Path) -> ContextManager[Path]`:** yields `<path>.tmp`, renames it on a clean exit, and deletes it on an exception.
  - **`list_areas(work_root) -> list[tuple[str, int]]`:** each area's fingerprint and size in bytes.
  - **`remove(area: WorkArea) -> None`**.

- [ ] **Step 1: Write the failing tests:**
  - `test_fingerprint_changes_with_each_input`.
  - `test_fingerprint_without_provenance_tracks_code`: with `tool_commit=None`, changing one file under a temporary `src_root` changes the fingerprint.
  - `test_areas_coexist`: two fingerprints give two directories, and `list_areas` lists both.
  - `test_atomic_write_leaves_nothing_on_error`.
  - `test_atomic_write_replaces`.

- [ ] **Step 2: Run** MAPTEST `tests/test_workarea.py`. Expected: FAIL.

- [ ] **Step 3: Implement** with `hashlib`, `importlib.metadata`, `pathlib` and `os.replace`.

- [ ] **Step 4: Run.** Expected: 5 passed.

- [ ] **Step 5: Commit** `feat(map): add fingerprints and work areas`.

### Task 5: Land mask and the Water Body Mask check

**Files:**

- Create:
  - `tools/map/src/seerah_map/mask.py`
  - `tools/map/tests/test_mask.py`
  - `docs/map-trial/step-1.md` (a heading per specification step-1 check, 1–12)

**Interfaces:**

- Consumes: `grid.MASK_SIGMA_PX`.
- Produces:
  - **`land_mask(wbm: np.ndarray, valid: np.ndarray) -> np.ndarray`:** uint8 0–255, where 255 is certain land, and 0 where `valid` is false.
  - **`fill_invalid(a: np.ndarray, valid: np.ndarray) -> np.ndarray`:** copies each invalid pixel from its nearest valid pixel, using `scipy.ndimage.distance_transform_edt(~valid, return_indices=True)`.

- [ ] **Step 1: Specification step-1 check 7.** Read one Water Body Mask in the image:

  ```sh
  docker run --rm seerah-map:dev gdalinfo -hist \
    /vsicurl/https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N20_00_E058_00_DEM/AUXFILES/Copernicus_DSM_COG_10_N20_00_E058_00_WBM.tif
  ```

  Record the classes found in `docs/map-trial/step-1.md` under check 7. The expected legend is 0 no water, 1 ocean, 2 lake and 3 river (spec line 447). If other values appear, stop and tell the owner.

- [ ] **Step 2: Write the failing tests:**
  - `test_ocean_is_sea`: an all-1 array gives all 0.
  - `test_lakes_and_rivers_are_land`: classes 2 and 3 give 255 away from the ocean.
  - `test_smoothing_softens_steps`: a straight coast gives values between 1 and 254 within 3σ of it.
  - `test_invalid_pixels_zero`.
  - `test_fill_invalid_takes_nearest`: an invalid pixel next to a valid 5 becomes 5.

- [ ] **Step 3: Run** MAPTEST `tests/test_mask.py`. Expected: FAIL.

- [ ] **Step 4: Implement.** Land is `(wbm != 1)` as a float. Blur it with `gaussian_filter(sigma=MASK_SIGMA_PX, truncate=3.0, mode="nearest")`, scale to 0–255 by truncation, and set 0 where the pixel is not valid.

- [ ] **Step 5: Run.** Expected: 5 passed.

- [ ] **Step 6: Commit** `feat(map): add the land mask and record the mask classes`.

### Task 6: Edits

**Files:**

- Create: `tools/map/src/seerah_map/edits.py` and `tools/map/tests/test_edits.py`

**Interfaces:**

- Consumes: `Edit`, `Window`, `grid.row_latitudes` and `grid.pixel_m`.
- Produces:
  - **`applicable(edits: list[Edit]) -> tuple[list[Edit], list[str]]`:** the approved edits, and the IDs of the ones skipped.
  - **`touches(edit: Edit, window: Window) -> bool`**.
  - **`apply(elevation: np.ndarray, edits: list[Edit], window: Window) -> np.ndarray`:** returns a new array. An edit whose rasterised footprint is empty in the window is skipped.

- [ ] **Step 1: Write the failing tests:**
  - `test_raise_inside_outline`: a 10 m `raise` adds exactly 10 inside the outline.
  - `test_feather_falls_linearly`: halfway through `feather_m`, the change is 5 ± 0.5; beyond it, 0.
  - `test_flatten_to_height`: inside the outline the elevation becomes 600, over sloping ground.
  - `test_carve_width_at_latitude`: a `carve` with `width_m=600` and `amount_m=8` at 30° N lowers a corridor 600 ± 31 m wide, which is ±1 pixel.
  - `test_empty_footprint_skipped`.
  - `test_only_approved_applied`.
  - `test_edit_across_window_edge`: two adjacent windows, each padded by the border, give cropped cores equal to one window covering both.

- [ ] **Step 2: Run** MAPTEST `tests/test_edits.py`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Footprint:** the edit's geometry goes into an in-memory OGR layer (the driver name from Task 1's test), is reprojected to EPSG:3857, and is drawn onto the window's grid with `gdal.Rasterize` (`ALL_TOUCHED=FALSE`). A carve is first buffered by `width_m / 2 / cos(lat)` in Mercator metres.
  - **Distance:** `distance_transform_edt(~inside)`, multiplied by `pixel_m() * cos(row_lat)` per row.
  - **Weight:** `clip(1 - d / feather_m, 0, 1)`.

- [ ] **Step 4: Run.** Expected: 7 passed.

- [ ] **Step 5: Commit** `feat(map): apply trial edits with distance feathering`.

### Task 7: Style function and spike regression

**Files:**

- Create:
  - `tools/map/src/seerah_map/style.py`
  - `tools/map/tests/test_style.py` and `tools/map/tests/test_style_regression.py`
  - `tools/map/tests/fixtures/make_spike_relief.py` and `tools/map/tests/fixtures/spike_relief.npz`

**Interfaces:**

- Consumes: `StyleSettings` and `grid.BAND_BLUR_RADIUS_PX`.
- Produces:
  - **`darkness(elevation: np.ndarray, land: np.ndarray, row_lat_deg: np.ndarray, pixel_m: float, s: StyleSettings, banded: bool) -> np.ndarray`:** float32 in [0, 1], the same shape as the input. `land` is boolean.
  - **`edge_fade(d: np.ndarray, distance_to_data_edge_deg: np.ndarray, fade_deg: float) -> np.ndarray`**.

- [ ] **Step 1: Write `make_spike_relief.py`.** It runs once and is committed, and it takes its output folder as an argument.
  - **The elevation:** a 256×256 float32 grid, made with numpy seed 1, from three parts:
    - a Gaussian hill 600 m high with σ 30 px at the centre;
    - a ridge 200 m high along the diagonal;
    - a sea corner below 0.5 m in the bottom-right 40×40.
  - **The ENVI header** has `map info = {Pseudo Mercator, 1, 1, 0, 0, <pixel_m>, <pixel_m>}`, with `pixel_m = grid.pixel_m()`.
  - **Then** it runs `tools/spike/relief_style.py` on that file and saves `elevation`, `alpha` (band 4) and `pixel_m` to `spike_relief.npz` (under 1 MB).

  Run it with:

  ```sh
  docker run --rm --security-opt label=disable -v "$PWD:/src:ro" -v "$PWD/tools/map/tests/fixtures:/out" \
    -e PYTHONPATH=/src/tools/map/src seerah-map:dev python3 /src/tools/map/tests/fixtures/make_spike_relief.py /out
  ```

- [ ] **Step 2: Write the failing tests:**
  - `test_flat_is_zero`.
  - `test_light_direction`: a north-west-facing slope gives lower darkness than the same slope facing south-east.
  - `test_sea_is_zero`, also when `banded=True`.
  - `test_latitude_correction`: the same ground slope at 12° N and 32° N gives equal darkness within 1e-3.
  - `test_banded_has_four_levels`: with `bands=3` and `band_blur_px=0`, the values are only {0, 1/3, 2/3, 1}.
  - `test_matches_spike` (`test_style_regression.py`):
    - **Run:** `darkness(elevation, elevation > 0.5, zeros, pixel_m, StyleSettings(exaggeration=8), banded=True)`, converted to alpha as `trunc(d * 0.6 * 255)`.
    - **Pass:** it matches the fixture's alpha within 1 for at least 99.9% of pixels, and within 2 for every pixel.
  - `test_edge_fade`: 0 at the data extent's edge; unchanged at `fade_deg` and beyond; linear between.

- [ ] **Step 3: Run** MAPTEST `tests/test_style.py tests/test_style_regression.py`. Expected: FAIL.

- [ ] **Step 4: Implement the steps from the specification's "The style function", in order:**
  1. Blur: `gaussian_filter(sigma=blur_px, truncate=3.0, mode="nearest")`.
  2. Gradients: `np.gradient(z, pixel_m)`, with rows running north to south, both divided by `cos(row_lat)[:, None]`.
  3. Shade and darkness, as in the specification.
  4. Bands, when `banded`: `round(d * bands) / bands`, then `gaussian_filter(sigma=band_blur_px, radius=4, mode="nearest")`, skipped when `band_blur_px == 0`.
  5. Sea set to 0, last.

- [ ] **Step 5: Run.** Expected: 7 passed. If `test_matches_spike` fails, stop and report: the look differs from build 11 (G8). Do not adjust the tolerance.

- [ ] **Step 6: Commit** `feat(map): add the style C function with a spike regression test`.

### Task 8: Encoders

**Files:**

- Create: `tools/map/src/seerah_map/encode.py` and `tools/map/tests/test_encode.py`

**Interfaces:**

- Produces:
  - **`painted(d: np.ndarray, ink_rgb: tuple[int, int, int], max_alpha: float) -> np.ndarray`:** uint8 of shape (4, h, w).
  - **`value(d: np.ndarray) -> np.ndarray`:** uint8 of shape (3, h, w).

- [ ] **Step 1: Write the failing tests:**
  - `test_painted_ink_everywhere`.
  - `test_painted_alpha_truncates`: `d = 1` gives 153.
  - `test_average_keeps_ink`: alphas (153, 0, 0, 0) averaged keep the ink RGB and give 38.
  - `test_value_terrarium_round_trip`: `(R*256 + G + B/256) - 32768` gives `trunc(d * 255)` within 1.
  - `test_value_only_green_varies`: red is 128 and blue is 0.

- [ ] **Step 2: Run** MAPTEST `tests/test_encode.py`. Expected: FAIL.

- [ ] **Step 3: Implement** with numpy.

- [ ] **Step 4: Run.** Expected: 5 passed.

- [ ] **Step 5: Commit** `feat(map): add painted and value encoders`.

### Task 9: Fetch and the lock

**Files:**

- Create:
  - `tools/map/src/seerah_map/fetch.py`
  - `tools/map/tests/test_fetch.py`
  - `tools/map/tests/conftest.py` (the `http_server` fixture)
- Create, under `tools/map/tests/fixtures/fetch/`:
  - `tileList.txt` (three cell names);
  - for two of those cells, `Copernicus_DSM_COG_10_N20_00_E058_00_DEM/Copernicus_DSM_COG_10_N20_00_E058_00_DEM.tif` and `…/AUXFILES/Copernicus_DSM_COG_10_N20_00_E058_00_WBM.tif`, each a few bytes;
  - `natural-earth/geojson/ne_110m_land.geojson` and the other five Natural Earth files, each a few bytes.

**Interfaces:**

- Consumes:
  - `Profile.data_extent`, `Lock`, `Cell` and `FileRef`;
  - `strict_yaml`;
  - `workarea.atomic_write`.
- Produces:
  - **The address templates:**
    - `DEM_BASE = "https://copernicus-dem-30m.s3.amazonaws.com"`;
    - `TILE_LIST = f"{DEM_BASE}/tileList.txt"`, one cell name per line;
    - DEM file: `f"{DEM_BASE}/{cell}/{cell}.tif"`;
    - Water Body Mask file: `f"{DEM_BASE}/{cell}/AUXFILES/{cell.removesuffix('_DEM')}_WBM.tif"`;
    - `NATURAL_EARTH_COMMIT`: the commit of tag v5.1.2 in `nvkelso/natural-earth-vector`;
    - `NE_BASE = f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{NATURAL_EARTH_COMMIT}/geojson"`;
    - `NE_FILES`: `ne_{110m,50m,10m}_{land,coastline}.geojson`.
  - **`cell_name(lat: int, lon: int) -> str`:** for example `Copernicus_DSM_COG_10_N20_00_E058_00_DEM`.
  - **`cells_for(extent: Bounds) -> list[tuple[int, int]]`:** every 1° cell that intersects the extent.
  - **`fetch(profile: Profile, lock_path: Path | None, cache: Path, out: Path, dem_base: str = DEM_BASE, ne_base: str = NE_BASE) -> FetchResult`**.
  - **`FetchResult(lock: Lock, wrote_new_lock: bool, downloaded: int, verified: int, message: str)`**.
  - **`FetchError(Exception)`**.
  - **`check_lock_matches(lock: Lock, profile: Profile) -> None`:** raises `FetchError` when `{(c.lat, c.lon) for c in lock.cells} != set(cells_for(profile.data_extent))`. Its message is "the lock does not match the profile; move the lock aside and run fetch".

- [ ] **Step 1: Write the failing tests.** They use the `http_server` fixture, which serves `tests/fixtures/fetch/` on a free local port.
  - `test_cells_for_data_extent`: `Bounds(33, 11, 61, 33)` gives 616 cells.
  - `test_first_run_writes_lock_to_out`:
    - the lock is written to `out/<profile name>.lock`;
    - a cell missing from `tileList.txt` is marked `absent: true`;
    - present cells and the Natural Earth files have their size and SHA-256 recorded;
    - `wrote_new_lock` is set;
    - `message` contains `copy`, `seerah-content/map/trial/` and `commit`.
  - `test_second_run_verifies`: no downloads, `tileList.txt` is not read again, and `verified` equals the file count.
  - `test_lock_profile_mismatch` (with `check_lock_matches`).
  - `test_fetch_rejects_changed_file` (Review Focus 2): the message contains the file, both hashes, and "delete the cached file and run fetch again".
  - `test_fetch_discards_partial` (Review Focus 1): a leftover `.part` file is replaced, and the lock is unchanged.
  - `test_no_lock_written_into_data`.

- [ ] **Step 2: Run** MAPTEST `tests/test_fetch.py`. Expected: FAIL.

- [ ] **Step 3: Implement with standard-library `urllib.request.urlopen(..., timeout=60)`,** one file at a time.
  - **Download:** to `<cache>/<path>.part`, hashing while it writes, then `os.replace`. A failure deletes the `.part` file and raises `FetchError`.
  - **Cache layout:** `copernicus/<cell>/<file>` and `natural-earth/<commit>/<file>`.
  - **Lock order:** cells sorted by latitude, then longitude.

- [ ] **Step 4: Run.** Expected: 7 passed.

- [ ] **Step 5: Commit** `feat(map): fetch pinned sources and write the lock`.

### Task 10: Block stage

**Files:**

- Create: `tools/map/src/seerah_map/blockstage.py` and `tools/map/tests/test_blockstage.py`

**Interfaces:**

- Consumes:
  - Tasks 3–8 (`grid`, `workarea`, `mask`, `edits`, `style`, `encode`);
  - `Lock` and `fetch.check_lock_matches`.
- Produces:
  - **`source_vrts(lock: Lock, cache: Path, extent: Bounds, work: WorkArea) -> tuple[Path, Path]`:** made with `gdal.BuildVRT` over the present cells, with `outputBounds` set to the data extent. Areas with no source read as 0 m for elevation and 1 for the Water Body Mask (`VRTNodata`, `hideNodata=True`).
  - **`read_block(block: Block, border: int, dem_vrt: Path, wbm_vrt: Path, extent: Bounds) -> tuple[np.ndarray, np.ndarray, np.ndarray]`:** the elevation, the Water Body Mask classes, and `valid`, which is false outside the data extent.
  - **`render_block(block: Block, profile: Profile, edits: list[Edit], variant: str, vrts: tuple[Path, Path], work: WorkArea) -> None`:** writes `style/<id>.tif` and `mask/<id>.tif`.
  - **`run_block_stage(profile, edits, variant, lock, cache, work, workers: int, log: Callable[[str], None]) -> list[str]`:** returns the IDs of the blocks rendered in this run. It asserts that a style file exists for every block in `grid.blocks(data_extent, block_zoom)`, then writes `style.vrt` and `mask.vrt`.

- [ ] **Step 1: Write the failing tests:**
  - `test_absent_area_reads_as_sea`: with a tiny synthetic GeoTIFF as the one present cell and one absent cell, the absent cell gives Water Body Mask 1 and elevation 0, and pixels outside the extent are `valid == False`.
  - `test_render_block_writes_atomically`.
  - `test_finished_blocks_skipped`.
  - `test_missing_block_file_fails`: deleting one style file before the VRT step raises an error naming the block.
  - `test_no_dark_rim_at_extent_edge`: a hill cut by the data extent's edge has darkness 0 at the cut.

- [ ] **Step 2: Run** MAPTEST `tests/test_blockstage.py`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Read:** `gdal.Warp` to an in-memory dataset on the block's padded window (EPSG:3857, zoom-12 resolution, `errorThreshold=0`). Elevation is resampled bilinearly, the Water Body Mask by nearest neighbour.
  - **Fill:** `fill_invalid` on both arrays.
  - **Land:** `land = land_mask(wbm, valid) >= 128`.
  - **Style:** edits, then style with `banded=(variant == "painted")`, then `edge_fade` measured to the data extent's edge, then crop, then encode.
  - **Write:** a GeoTIFF, tiled and DEFLATE-compressed. The mask's invalid pixels get an internal mask band.
  - **Workers:** `ProcessPoolExecutor(max_workers=workers, mp_context=multiprocessing.get_context("forkserver"))`. Each finished block logs `block <id> done (<n>/<total>)`.

- [ ] **Step 4: Run.** Expected: 5 passed.

- [ ] **Step 5: Commit** `feat(map): add the block stage`.

### Task 11: Tile stage and specification checks 5 and 6

**Files:**

- Create: `tools/map/src/seerah_map/tilestage.py` and `tools/map/tests/test_tilestage.py`
- Modify: `docs/map-trial/step-1.md`

**Interfaces:**

- Consumes: `WorkArea` and `grid.expected_tiles`.
- Produces:
  - **`tile_args(style_vrt: Path, tiles_dir: Path, max_zoom: int, variant: str, webp_quality: int, workers: int, resume: bool) -> list[str]`**.
  - **`run_tile_stage(work: WorkArea, profile: Profile, variant: str, workers: int, log) -> int`:** returns the number of tiles. It logs `tile stage started` and `tile stage done (<n> tiles)`, and writes `<area>/tiles.done`.
  - **`check_completeness(tiles_dir: Path, expected: set[tuple[int, int, int]], ext: str) -> list[tuple[int, int, int]]`:** the missing or broken tiles.
  - **`CompletenessError(Exception)`**.

- [ ] **Step 1: Write the failing tests:**
  - `test_tile_args`:
    - **always present:** `gdal raster tile`, `--convention xyz`, `--tiling-scheme WebMercatorQuad`, `--min-zoom 0`, `--max-zoom 12`, `--resampling average`, `--overview-resampling average` and `-j`;
    - **always absent:** `--skip-blank`;
    - **painted:** `-f WEBP --co QUALITY=<q>`; **value:** `-f PNG`;
    - **viewers:** the option that turns off web-viewer files, as `gdal raster tile --help` lists it in GDAL 3.13.
  - `test_folder_without_done_is_retiled`.
  - `test_completeness_reports_missing_and_broken`.
  - `test_tile_small_vrt` (`slow`): a 512×512 RGBA VRT at zoom 12 tiles to `expected_tiles`, every file decodes, and the folder holds only `z/x/y.<ext>` files.

- [ ] **Step 2: Run** MAPTEST `tests/test_tilestage.py`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Tiler:** `subprocess.run(tile_args(...), check=True)`.
  - **Expected tiles:** from `grid.expected_tiles(grid.blocks(data_extent, block_zoom), max_zoom)`.
  - **Decode check:** `gdal.Open` each tile and read one band.

- [ ] **Step 4: Run.** Expected: 4 passed.

- [ ] **Step 5: Check 5, whether GDAL writes tiles atomically.**
  - **Run:** in a scratch container (not the image), with `apt-get install strace` there:

    ```sh
    strace -f -e trace=rename,renameat,renameat2 gdal raster tile <small vrt> /tmp/t
    ```

  - **If each tile is written to a temporary name and renamed:** change `run_tile_stage` to resume a folder without `tiles.done`, replace `test_folder_without_done_is_retiled` with `test_folder_without_done_is_resumed`, and run the tests again.
  - **Otherwise:** change nothing.
  - **Either way:** record the result under check 5 in `docs/map-trial/step-1.md`.

- [ ] **Step 6: Check 6, whether averaging keeps the ink.** Tile the small VRT once more with `-f PNG`, decode one zoom-11 edge tile, and confirm that its RGB equals the ink exactly wherever alpha is above 0. Record the largest RGB difference in the WebP tiles for information. Write both results under check 6.

- [ ] **Step 7: Commit** `feat(map): add the tile stage and record checks 5 and 6`.

### Task 12: Pack and run report

**Files:**

- Create:
  - `tools/map/src/seerah_map/pack.py` and `tools/map/src/seerah_map/report.py`
  - `tools/map/tests/test_pack.py` and `tools/map/tests/test_report.py`

**Interfaces:**

- Produces:
  - **`Provenance(tool_commit: str, data_commit: str, image: str, fingerprint: str)`:** a missing value is written as `"unknown"`.
  - **`metadata(profile, variant, provenance) -> dict`:** the keys are:
    - `format` (`webp` or `png`), `minzoom` 0 and `maxzoom`;
    - `bounds`, the string `"w,s,e,n"` of the data extent;
    - `name`, `trial` (true), `variant`;
    - `encoding` (`painted-rgba` or `terrarium-value`);
    - `fingerprint`, `tool_commit`, `data_commit` and `image`.
  - **`pack(work: WorkArea, out_dir: Path, meta: dict) -> Path`:**
    1. writes `metadata.json` into `work.tiles_dir`;
    2. runs `disk_to_pmtiles(tiles_dir, tmp, "auto")` in a subprocess with `PYTHONHASHSEED=0`;
    3. writes `out_dir/relief.pmtiles` through `atomic_write`;
    4. copies `metadata.json` to `out_dir`.
  - **`verify(pmtiles_path: Path, profile: Profile) -> None`:** runs `pmtiles verify` and `pmtiles show --header-json`, and compares the zooms and bounds (within 1e-6°) and `trial: true`. Raises `PackError`.
  - **`size_by_zoom(tiles_dir: Path, outline: Path) -> list[dict]`:** one row per zoom, `{zoom, inside_bytes, outside_bytes}`, with each tile assigned by its centre point.
  - **`read_cgroup(root: Path = Path("/sys/fs/cgroup")) -> dict`**.
  - **`write_report(report: dict, out_dir: Path) -> Path`:** writes `report.json` with exactly these keys:

| Key | Type | Notes |
| --- | --- | --- |
| `provenance` | object | `tool_commit`, `data_commit`, `image`, `fingerprint`: strings |
| `stage_seconds` | object | `block`, `tile`, `pack`: numbers |
| `memory.peak_bytes` | integer or null | from `memory.peak`, which includes page cache |
| `memory.peak_reason` | string or null | the reason it is null |
| `memory.oom_kills` | integer or null | from `memory.events` |
| `memory.largest_child_rss_kib` | integer | `getrusage(RUSAGE_CHILDREN).ru_maxrss` (KiB on Linux), read right after the block pool closes |
| `disk.work_area_bytes_after_tiling` | integer | |
| `tiles.expected` | integer | |
| `tiles.written` | integer | |
| `tiles.stored` | integer | the addressed-content count from `pmtiles show --header-json` |
| `size_by_zoom` | array | the rows from `size_by_zoom` |
| `edits.applied`, `edits.skipped` | arrays of IDs | |
| `workers.count`, `workers.reason`, `workers.gdal_cachemax_mib` | integer, string, integer | |

- [ ] **Step 1: Write the failing tests:**
  - `test_metadata_marks_trial`.
  - `test_pack_is_deterministic` (`slow`): two packs give the same SHA-256.
  - `test_verify_checks_bounds_and_trial` (`slow`): wrong bounds raise `PackError`. If the header's bounds come from somewhere other than `metadata.json`, record that, and set them with `pmtiles edit --header-json` after packing.
  - `test_size_by_zoom_split`.
  - `test_report_reads_cgroup_v2`.
  - `test_report_without_cgroup_v2` (Review Focus 5): the reason is `"memory.peak not available (cgroup v1 or no cap)"`.
  - `test_report_keys`: the written JSON has exactly the keys in the table.

- [ ] **Step 2: Run** MAPTEST `tests/test_pack.py tests/test_report.py`. Expected: FAIL.

- [ ] **Step 3: Implement** as in Interfaces.

- [ ] **Step 4: Run.** Expected: 7 passed.

- [ ] **Step 5: Commit** `feat(map): pack PMTiles and write the run report`.

### Task 13: CLI

**Files:**

- Create:
  - `tools/map/src/seerah_map/cli.py` and `tools/map/src/seerah_map/__main__.py`
  - `tools/map/tests/test_cli.py`

**Interfaces:**

- Consumes: Tasks 2–12.
- Produces:
  - **`main(argv: list[str] | None = None) -> int`**.
  - **The commands:**
    - `fetch --profile REL`;
    - `build --profile REL --variant {painted,value} [--workers N] [--per-worker-mib N]` (default 2048, D4);
    - `clean [--fingerprint F | --all]`. A bare `clean` lists the work areas with their sizes and removes nothing.
  - **Profile paths:** `REL` is relative to `/data` without `.yaml`. The profile's `edits.yaml` and `<stem>.lock` are read from the same folder.
  - **`worker_count(memory_max: str | None, per_worker_mib: int, workers: int | None) -> tuple[int, str]`**.
  - **Environment:** `SEERAH_TOOL_COMMIT`, `SEERAH_DATA_COMMIT` and `SEERAH_IMAGE_DIGEST`. A missing one is logged as a warning, and the fingerprint fills it in (Task 4).
  - **Output folder:** `/out/<stem>-<variant>-<fp8>/`, where `fp8` is the fingerprint's first 8 hexadecimal characters.
  - **Progress lines, exactly:**
    - `fetch: <n> downloaded, <m> verified`;
    - `build: <stem> <variant> fingerprint <fp8>`;
    - `workers: <n> (<reason>)`;
    - `block <id> done (<n>/<total>)`;
    - `tile stage started`;
    - `tile stage done (<n> tiles)`;
    - `pack done: <path>`;
    - `work area removed`.
  - **Exit codes:** 0 for success; 1 when a check fails (`FetchError`, `CompletenessError`, `PackError`); 2 for a usage or configuration error (`ConfigError`, a missing lock).

- [ ] **Step 1: Write the failing tests:**
  - `test_worker_count_with_cap`: `("8589934592", 2048, None)` gives 4.
  - `test_worker_count_without_cap` (Review Focus 3):
    - `("max", 2048, None)` and `(None, 2048, None)` both give `(1, "no memory cap; using 1 worker")`;
    - `("max", 2048, 3)` gives 3.
  - `test_build_missing_lock_message`: exits 2 with "run fetch first, then copy the lock beside the profile and commit it".
  - `test_build_lock_mismatch`: exits 1.
  - `test_exit_codes`.
  - `test_build_pipeline_order` (every stage replaced with a stub):
    - the order is check lock, block stage, tile stage, pack, verify, report, remove the work area;
    - after an exception, the work area is kept.
  - `test_clean_semantics`: a bare `clean` removes nothing; `--fingerprint` removes one area; `--all` removes every area.

- [ ] **Step 2: Run** MAPTEST `tests/test_cli.py`. Expected: FAIL.

- [ ] **Step 3: Implement** with `argparse`. `__main__.py` calls `sys.exit(main())`. Read `memory.max` from `/sys/fs/cgroup/memory.max`; treat a missing file as `None`.

- [ ] **Step 4: Run.** Expected: 7 passed.

- [ ] **Step 5: Commit** `feat(map): add the command line`.

### Task 14: Synthetic example and end-to-end tests

**Files:**

- Create:
  - `map-data/example/example.yaml`, `edits.yaml`, `example.lock`, `peninsula.geojson` and `camera-path.json`
  - `tools/map/tests/test_build_example.py`
- Modify:
  - `tools/map/tests/conftest.py`: add the `run_cli` fixture (it starts with `start_new_session=True`, kills with `os.killpg`, and waits for a given progress line);
  - any module from Tasks 2–13, only to fix a failure these tests find. Each such fix goes in its own `fix(map): …` commit, with a regression test in that module's test file.

**Interfaces:**

- Consumes: `python3 -m seerah_map`, run as a subprocess.
- Produces: the example's output, which phase 2 uses as a fixture, and the contract for `camera-path.json`:

```json
{
  "stops": [{"lat": 20.62, "lon": 58.9, "zoom": 10, "duration_ms": 4000}],
  "limits": {"west": 58.8, "south": 20.5, "east": 59.05, "north": 20.75, "min_zoom": 4, "max_zoom": 14}
}
```

Zooms are MapLibre zooms.

- [ ] **Step 1: Owner gate (D1).** Show the owner the Masirah window and its measured land fraction, and wait for confirmation or a replacement.

- [ ] **Step 2: Write the example files.**
  - **`example.yaml`:**
    - `purpose: trial`, with the camera bounds equal to the confirmed window;
    - `margin_deg: 0` and `fade_deg: 0` (the window is its own data extent);
    - `max_zoom: 12`, `block_zoom: 11` and `webp_quality: 80`;
    - `peninsula_outline: peninsula.geojson`.
  - **`edits.yaml`:** one `raise` of 20 m, 600 m wide, with `feather_m: 300`. It crosses a block edge and has `review: draft`; the owner sets `approved` at this gate. A comment says it is synthetic.
  - **`peninsula.geojson`:** a polygon covering the window's western half.
  - **`camera-path.json`:** as in Interfaces.

- [ ] **Step 3: Make the lock.**

  ```sh
  docker run --rm --security-opt label=disable -v "$PWD:/src:ro" -v "$PWD/map-data:/data:ro" \
    -v seerah-map-cache:/cache -v "$PWD/map-data/dist:/out" -e PYTHONPATH=/src/tools/map/src \
    seerah-map:dev python3 -m seerah_map fetch --profile example/example
  cp map-data/dist/example.lock map-data/example/example.lock
  ```

  Then commit `example.lock`.

- [ ] **Step 4: Write the failing tests** (all `slow`):
  - `test_example_builds_complete`: `build --variant painted` gives `relief.pmtiles` and `metadata.json` with `"trial": true`; `pmtiles verify` passes; every expected tile decodes.
  - `test_example_value_variant`: `"encoding": "terrarium-value"`.
  - `test_double_build_identical`: two builds, each after `clean --all`, give equal SHA-256 hashes.
  - `test_resume_after_kill_in_block_stage`: killed after two `block … done` lines, then rerun; the decoded zoom-12 tiles equal a clean build's.
  - `test_resume_after_kill_in_tile_stage`: killed after `tile stage started`; same check.
  - `test_work_area_kept_on_failure`: a failure forced after the block stage (a monkeypatched tiler that exits 1) keeps the area, and `clean` lists it.
  - `test_block_split_matches_single_block`:
    - `block_zoom: 8` gives exactly one block;
    - its decoded zoom-12 tiles equal those from `block_zoom: 11`.
  - `test_absent_neighbour_is_sea`: pixels in N20 E059 have alpha 0, and some land pixels have alpha above 0.

- [ ] **Step 5: Run** MAPTEST `-m slow tests/test_build_example.py`. Expected: 8 passed. A failure is fixed in its owning module, as described under Files.

- [ ] **Step 6: Commit** `test(map): build the synthetic example end to end`.

### Task 15: Host launcher

**Files:**

- Create: `tools/map/run.py` and `tools/map/host_tests/test_run.py`

**Interfaces:**

- Produces:
  - **`IMAGE_REPO = "ghcr.io/mohdfarag/seerah-map"`** (D2).
  - **`image_tag(dockerfile: Path, requirements: Path) -> str`:** the first 16 hexadecimal characters of the SHA-256 of the Dockerfile's bytes followed by `requirements.txt`'s bytes.
  - **`check_clean(repo: Path) -> str`:** returns the commit, or raises `SystemExit` naming the repository and saying "commit or stash".
  - **`resolve_image(ref: str | None) -> tuple[str, str]`:** gives the image to run and its digest. If `ref` is given, it does not pull, and an image with no repository digest records `local:<Id>`. Otherwise it pulls `IMAGE_REPO:<tag>`.
  - **`docker_args(cmd: list[str], public: Path, data: Path, out: Path, image: str, memory_mib: int, network: bool, user: tuple[int, int], env: dict[str, str]) -> list[str]`**.
  - **`main(argv) -> int`.** The usage is `run.py [--private PATH] [--data PATH] [--out PATH] [--memory-mib 8192] [--image REF] [--print-tag] -- <command> [args]`.
    - **Defaults:** `--private` is `../seerah-content`, and `/data` is `<private>/map` unless `--data` is given. `--out` is `map-data/dist`. Relative paths resolve against the public repository root.
    - **Clean checks:** `check_clean` runs on the public root and on `git -C <data> rev-parse --show-toplevel`.
    - **`--print-tag`** prints `IMAGE_REPO:<tag>` and exits.

- [ ] **Step 1: Write the failing tests** (HOSTTEST, standard library only):
  - `test_args_are_a_list_and_paths_with_spaces_survive`.
  - `test_mounts_and_security`: the arguments contain `/src:ro`, `/data:ro`, `seerah-map-cache:/cache`, `seerah-map-work:/work`, `--security-opt` and `label=disable`.
  - `test_network_none_except_fetch`.
  - `test_memory_flags`: `--memory 8192m` and `--memory-swap 8192m`.
  - `test_image_tag_known_bytes`.
  - `test_print_tag`.
  - `test_local_image_digest`: an `--image` with no repository digest gives `local:sha256:…`, faked with a stubbed `docker inspect`.
  - `test_dirty_public_checkout_refused` and `test_dirty_data_checkout_refused`: temporary git repositories, one dirty each.

- [ ] **Step 2: Run** HOSTTEST. Expected: FAIL.

- [ ] **Step 3: Implement** with `subprocess.run(list)` only, never a shell string. The command run is `python3 -m seerah_map <cmd>`, with `SEERAH_TOOL_COMMIT`, `SEERAH_DATA_COMMIT` and `SEERAH_IMAGE_DIGEST` set.

- [ ] **Step 4: Run.** Expected: 9 passed.

- [ ] **Step 5: Smoke test:**

  ```sh
  python3 tools/map/run.py --data map-data --image seerah-map:dev -- fetch --profile example/example
  ```

  Expected: exit 0 and `fetch: 0 downloaded, <m> verified`.

- [ ] **Step 6: Commit** `feat(map): add the host launcher`.

### Task 16: CI workflow, Dependabot and docs

**Files:**

- Create: `.github/workflows/map.yml` and `tools/map/README.md`
- Modify: `.github/dependabot.yml`, `tools/README.md` and `map-data/README.md`

- [ ] **Step 1: Write `map.yml`.**
  - **Triggers:** `push` to `main` and `pull_request` (paths `tools/map/**`, `map-data/example/**` and the workflow itself), plus `workflow_dispatch`.
  - **Top level:** `permissions: {}`, and `concurrency` and `timeout-minutes` as in `android.yml`.
  - **Job `image`** (`packages: write`):
    1. assert that `run.py`'s `IMAGE_REPO` namespace equals the lowercased `github.repository_owner`;
    2. take `TAG=$(python3 tools/map/run.py --print-tag)`;
    3. run `docker manifest inspect "$TAG"`; if it is missing, build it, and push only on `main`.
  - **Job `test`** (`contents: read`, needs `image`):
    - get the image with `docker pull "$TAG" || docker build -t "$TAG" tools/map`;
    - restore `actions/cache`, keyed on `hashFiles('map-data/example/example.lock')`, into `$RUNNER_TEMP/cache`;
    - run MAPTEST's flags with `--memory 6g`, the cache at `/cache`, and `SEERAH_TOOL_COMMIT=${{ github.sha }}`: `python3 -m pytest` (everything, including `slow`);
    - run HOSTTEST.
  - **Conventions:** actions pinned by commit, and `persist-credentials: false`.

- [ ] **Step 2: Dependabot.** Add `docker` and `pip` entries for `/tools/map`, with the existing schedule and grouping style.

- [ ] **Step 3: Docs.**
  - **`tools/map/README.md`:**
    - what the tool does, and its three commands;
    - the profile, edits and lock layout, with the copy step for a new lock;
    - `run.py` usage and its memory flags;
    - the plain `docker run` contract: the mounts, the three environment variables, `--memory`, and `--network none` for `build`;
    - the output folder;
    - that trial output is never packaged;
    - how CI runs it.
  - **`tools/README.md`:** a row for `map/`.
  - **`map-data/README.md`:** describe `example/` and the trial output folder layout, stating that it is not `tile_schema` 1.

- [ ] **Step 4: Local gate (the agent's).** MAPTEST (all tests), HOSTTEST and `pre-commit run --all-files` pass (ruff, markdownlint, zizmor on `map.yml`, REUSE).

- [ ] **Step 5: Commit** `ci(map): build the image and run the map tool's tests`.

- [ ] **Step 6: Owner gate (D7).** Stop and report. The owner approves the push, then opens the pull request or runs the workflow by hand. The `map` workflow must come back green; if it does not, fix the cause on this branch and report again.

---

## Self-review

- **Spec coverage, phase 1, files 1–18:**
  - 1 (`Dockerfile`), 2 (`requirements`) and 3 (`pyproject.toml`): Task 1.
  - 4 (`run.py`): Task 15.
  - 5 (`cli.py`): Task 13.
  - 6 (`config.py`, `strict_yaml.py`) and 7 (the schemas): Task 2.
  - 8 (`grid.py`): Task 3.
  - 9 (`fetch.py`): Task 9.
  - 10 (`workarea.py`): Task 4.
  - 11 (`style`, `encode`, `mask`, `edits`): Tasks 5–8.
  - 12 (`blockstage.py`): Task 10.
  - 13 (`tilestage.py`): Task 11.
  - 14 (`pack.py`) and 15 (`report.py`): Task 12.
  - 16 (the tests): Tasks 1–14.
  - 17 (the example): Task 14.
  - 18 (`map.yml`): Task 16.
- **Deviations:** listed under Decisions (D6).
- **Owner gates:** D1 (Task 14, Step 1) and D7 (Task 16, Step 6).
- **Type consistency:**
  - `Profile`, `Lock`, `Cell`, `FileRef`, `Edit` and `StyleSettings` are defined once, in Task 2;
  - `Block` and `Window` in Task 3;
  - `WorkArea` in Task 4;
  - `Provenance` in Task 12.

  The same names are used in every later task.
