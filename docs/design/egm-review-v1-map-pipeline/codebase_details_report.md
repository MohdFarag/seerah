# Codebase Details Audit: `docs/design/v1-map-pipeline-spec.md`

**Target:** `docs/design/v1-map-pipeline-spec.md`
**Reviewer:** `egm-codebase-details`

---

Audited `docs/design/v1-map-pipeline-spec.md` against `tools/map/`, `android/core/map/`, `content/schema/`, `.github/workflows/`, pre-commit/tooling configuration, and accepted ADRs/trial reports.

Summary: **6 Blockers**, **9 Majors**, **11 Nits**.

---

## Blockers

### B1. False statement: `:core:map` memory trimming and prefetch tuning are NOT implemented

- **Severity:** Blocker
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:38` vs `android/core/map/src/main/kotlin/app/seerah/map/SeerahMap.kt:136-145`, `docs/map-trial/step-4.md:51`, `docs/map-trial/README.md:20`
- **Discrepancy & Rationale:** Spec line 38 states `"Steps 1–4 of the trial are complete on the workstation and Galaxy A54 (step-4.md), with :core:map memory trimming and prefetch tuning implemented in Step 1A."` In the actual codebase:
  - `SeerahMap.kt:142` has `override fun onTrimMemory(level: Int) = Unit` (an empty no-op), and there is no `mapView.reduceMemoryUse()` call or tile-cache/prefetch tuning anywhere in `android/core/map/`.
  - `docs/map-trial/step-4.md:51` lists forwarding `onTrimMemory` to `mapView.reduceMemoryUse()` and tuning tile-cache bounds as an unimplemented recommendation, and `docs/map-trial/README.md:20` explicitly states `"reduceMemoryUse() wiring remain open"`.
  - No `"Step 1A"` exists in `docs/map-trial/` or `docs/roadmap.md`.
- **Exact Correction:** Replace the clause on `docs/design/v1-map-pipeline-spec.md:38` with: `"Steps 1–4 of the trial are complete on the workstation and Galaxy A54 (step-4.md); :core:map onTrimMemory -> reduceMemoryUse() wiring and tile-cache tuning remain open follow-ups pending Oppo A55 verification."`

### B2. `blockstage.py` per-zoom helpers cannot share a single WBM+elevation cleanup helper as described

- **Severity:** Blocker
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:127, 138` vs `tools/map/src/seerah_map/blockstage.py:247-348`
- **Discrepancy & Rationale:** Line 127 specifies cleanup running on `(elevation, wbm)` after `read_block()`, and line 138 asserts: `"Both render_block() (pyramid z12) and per-zoom helpers (_render_mask_block(), _render_coarse_block()) share the same helper so production and any test modes stay consistent."` In `blockstage.py`:
  - `_render_mask_block(block, profile, wbm_vrt, work)` (`blockstage.py:300-307`) warps ONLY `wbm_vrt`; it has no `elevation` array, no `dem_vrt`, and no `edits` parameter.
  - `_render_coarse_block(block, profile, edits, variant, dem_vrt, mask_vrt, out_path)` (`blockstage.py:310-347`) warps `dem_vrt` and `mask_vrt` (the already-rendered z12 land mask); it has no `wbm` array and no `wbm_vrt`.
  - Consequently, `_render_mask_block` and `_render_coarse_block` cannot call one `(elevation, wbm)` helper without refactoring or splitting operations.
- **Exact Correction:** Update line 138 (and `edits.py` API design) to define separate WBM and elevation cleanup primitives (`apply_cleanup_wbm` / `apply_cleanup_elevation` or `apply_cleanup(..., elevation=..., wbm=...)` with optional arrays): `render_block()` applies both before `fill_invalid()`, `_render_mask_block()` applies WBM cleanup before `_land_mask_from_wbm()`, and `_render_coarse_block()` applies elevation cleanup before `fill_invalid()` (while inheriting WBM cleanup via `mask_vrt`).

### B3. `pack.pack()` unconditionally writes `metadata.json`, triggering `pack-map`'s own staging rejection

- **Severity:** Blocker
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:202, 204-208, 308, 311` vs `tools/map/src/seerah_map/pack.py:98-129`
- **Discrepancy & Rationale:**
  - `pack.pack(work, out_dir, meta)` (`pack.py:127-128`) unconditionally writes `out_dir / "metadata.json"` (`METADATA_NAME`) beside `relief.pmtiles`.
  - Spec line 308 requires `pack-map` to refuse if `<staging>/metadata.json` exists, and line 311 requires `<staging>/data/` to contain only `{base.pmtiles, relief.pmtiles, map.json}` with no extra unmanifested files.
  - Whether `cli.py` calls `pack(area, staging_dir / "data", ...)` or `pack(area, staging_dir, ...)`, `pack.pack()` will write a `metadata.json` that causes `pack-map` to reject `tools/map`'s own production staging directory.
- **Exact Correction:** Specify updating `pack.pack()` (e.g., adding `write_metadata_file: bool = True`, passed as `False` in `--coverage` production mode) so `metadata.json` is still written inside `work.tiles_dir` for `disk_to_pmtiles` (`pack.py:115`) without writing a sidecar `metadata.json` into `<staging>/` or `<staging>/data/`.

### B4. Internal contradiction between `manifest.schema.json` `image` regex and `pack-map --allow-local-provenance`

- **Severity:** Blocker
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:298, 306, 309, 317` vs `tools/map/run.py:97-100`
- **Discrepancy & Rationale:**
  - Line 298 states `manifest.schema.json` requires `image` matching `^sha256:[0-9a-f]{64}$` in production (`"(explicit test flag needed for local: in dev fixtures)"`).
  - Lines 306 & 309 add `--allow-local-provenance` to `pack-map` specifically so `local:*` `image` digests (emitted by `tools/map/run.py:100` as `f"local:{info['Id']}"` for local `--image` builds) are accepted in local tests.
  - Line 317 validates the generated `manifest.json` against `content/schema/manifest.schema.json`.
  - If `manifest.schema.json` forbids `local:...`, `pack-map --allow-local-provenance` will fail JSON Schema validation at line 317 whenever `build-provenance.json` carries a `local:` digest.
- **Exact Correction:** Pick one consistent contract: either allow `^(?:sha256:[0-9a-f]{64}|local:sha256:[0-9a-f]{64})$` in `manifest.schema.json` and enforce `sha256:[0-9a-f]{64}` in `pack-map` (when `--allow-local-provenance` is off) and `verify` (unless `--allow-local-provenance` is passed), or explicitly document that `manifest.schema.json` accepts a conditional/relaxed pattern only if test fixtures never use `run.py --image` directly.

### B5. Non-existent Copernicus `INFO/` files in `seerah-map-cache` for Phase 2 licence check

- **Severity:** Blocker
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:82, 365, 379` vs `tools/map/src/seerah_map/fetch.py:261-267`, `map-data/example/example.lock:5-20`
- **Discrepancy & Rationale:** Lines 82, 365, and 379 specify a Phase 2 check `"reading the licence file inside a real cached Copernicus cell's INFO/ folder in seerah-map-cache"`. However, `fetch.py:261-267` downloads ONLY `<cell>_DEM.tif` and `AUXFILES/<cell>_WBM.tif` into `copernicus/<cell>/`, and `example.lock:5-20` pins no `INFO/` files. Neither CI (`--network none`) nor local `seerah-map-cache` contains any `INFO/` directory or licence PDF/text.
- **Exact Correction:** Specify how the `INFO/` licence file is actually retrieved and verified (e.g., an explicit Phase 2 verification step/script fetching the `INFO/` licence file from `copernicus-dem-30m` S3 or adding it to `fetch.py` / `sources.lock` if it must be verified offline in cache).

### B6. `cli.py` `--variant` (`required=True`) and `--mode` (`default=PYRAMID`) break `--coverage` CLI contract

- **Severity:** Blocker
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:194, 198-199` vs `tools/map/src/seerah_map/cli.py:315-321`
- **Discrepancy & Rationale:** Line 199 requires `build --coverage coverage` to refuse `--variant` and `--mode` flags. In `cli.py:315`, `--variant` has `required=True` (causing `argparse` to abort `build --coverage ...` before `_cmd_build` runs), and in `cli.py:319`, `--mode` has `default=PYRAMID` (preventing `_cmd_build` from distinguishing omitted `--mode` from an explicit `--mode pyramid`).
- **Exact Correction:** Specify changing `--variant` to `required=False` (enforced in `_cmd_build` for `--profile`, rejected for `--coverage`) and `--mode` to `default=None` (defaulting to `PYRAMID` only for `--profile`, rejected if non-`None` with `--coverage`).

---

## Majors

### M1. Incomplete `grid.py` / `base.py` / `Profile` adaptation (`Box` vs `Bounds`, `natural_earth_max_zoom`, and `max_zoom` name collision)

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:176-190` vs `tools/map/src/seerah_map/config.py:41, 66-85`, `tools/map/src/seerah_map/grid.py:306-347`, `tools/map/src/seerah_map/base.py:438-440`, `tools/map/src/seerah_map/blockstage.py:489-540`, `tools/map/src/seerah_map/tilestage.py:121-184`, `tools/map/src/seerah_map/pack.py:61-91, 159-186`
- **Discrepancy & Rationale:**
  1. Line 190 refers to `"an explicit rectangular data_extent Box"`, but `config.py:41` and `grid.py:14` define **`Bounds`**, not `Box`.
  2. Line 190 only asks to generalize `check_base_coverage`, ignoring that `natural_earth_max_zoom` (`grid.py:306-320`) and `check_data_extent_coverage` (`grid.py:323-329`) ALSO take `margin_deg` and call `_widen_camera(camera, margin_deg)`. Leaving `natural_earth_max_zoom` on uniform `margin_deg` produces the wrong `ne_max` for rectangular `data_extent`, and `base.py:438-440` hardcodes `WIDEST_HALF_SCREEN_DP` and `TRIAL_MIN_MAPLIBRE_ZOOM` instead of `coverage.widest_half_screen_dp` and `math.floor(coverage.min_zoom)`.
  3. Across `config.py:75`, `blockstage.py:524`, `tilestage.py:151`, `pack.py:80, 176`, and `base.py:401, 439-440`, `profile.max_zoom` means the **build zoom** (`<= 12`), whereas in `coverage.yaml` (lines 176–177), `max_zoom` is the **MapLibre camera max zoom** (`<= 22`) and `regional_max_zoom` is the **build zoom**. The spec omits how `Coverage` adapts cleanly to all `Profile`-typed stage functions without passing camera `max_zoom` as build zoom or breaking `profile.margin_deg` assumptions.
- **Exact Correction:** Replace `Box` with `Bounds`; specify generalizing all three coverage functions in `grid.py` (`check_data_extent_coverage`, `natural_earth_max_zoom`, `check_base_coverage`) to accept `data_extent: Bounds`, `min_maplibre_zoom`, and `half_width_dp` (and wiring them in `base.py:438-440`); and explicitly specify a `BuildSpec`/shared target dataclass (or `Coverage` adapter) with `build_max_zoom = coverage.regional_max_zoom` for `fetch`, `blockstage`, `tilestage`, `pack`, and `base`.

### M2. `build-provenance.json` schema is unspecified and conflicts with `report.py` / `size_by_zoom`

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:107, 185, 202, 208, 309` vs `tools/map/src/seerah_map/cli.py:229-236, 268`, `tools/map/src/seerah_map/report.py:18-27, 58-84`, `tools/map/src/seerah_map/pack.py:204-230`
- **Discrepancy & Rationale:**
  - `cli.py:229-236` writes `report.json`, whose keys are strictly enforced by `report._SHAPE` (`report.py:18-27`) and whose `size_by_zoom()` (`pack.py:204`) requires a non-null `outline: Path` (`ogr.Open(str(outline))`).
  - Line 185 makes `peninsula_outline` optional in `coverage.yaml` (`"Enables inside/outside byte split in build-provenance.json when present"`).
  - Line 309 requires `pack-map` to read `<staging>/build-provenance.json` and refuse missing fields, `"trial": true`, `"unknown"`, or `"local:*"` `image`, yet the spec never defines the JSON keys/schema of `build-provenance.json` (e.g., whether the content commit key is `data_commit` as in `report.py:19` / `pack.py:89` or `source_commit` as in `manifest.json` line 293, whether provenance fields are nested under `"provenance"` as in `report.py` or top-level, whether `report.json` is still written alongside `build-provenance.json`, and what happens to `size_by_zoom` when `peninsula_outline` is omitted).
- **Exact Correction:** Specify the exact JSON schema/keys of `build-provenance.json` (including `"trial": false`, commit key mapping to `manifest["source_commit"]`, `edits`, and optional `size_by_zoom`), state whether `report.json` is omitted or permitted in `<staging>/`, and guard `size_by_zoom` when `peninsula_outline is None`.

### M3. Lock filename (`sources.lock` vs `coverage.lock`), read-only `/data` wording, and `fetch.py` message mismatch

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:167, 197, 200` vs `tools/map/run.py:142`, `tools/map/src/seerah_map/cli.py:133, 156`, `tools/map/src/seerah_map/fetch.py:40, 202-208, 295`, `tools/map/tests/test_fetch.py:163`
- **Discrepancy & Rationale:**
  - Line 197 states `fetch --coverage coverage` `"verifies/writes /data/sources.lock (if missing, writes /out/coverage.lock and instructs owner to copy to seerah-content/map/sources.lock)"`. `/data` is mounted **read-only** (`run.py:142`, `.github/workflows/map.yml:107`, `test_fetch.py:163`), so `fetch` never writes `/data/sources.lock`.
  - `cli.py:133, 156` resolves the lock via `profile_path.with_suffix(".lock")` (which gives `coverage.lock`, NOT `sources.lock`), and `fetch.py:40, 202-208, 295` hardcodes `_LOCK_DESTINATION = "seerah-content/map/trial/"` and writes `out / f"{profile.name}.lock"` (`coverage.lock`), printing `cp /out/coverage.lock seerah-content/map/trial/coverage.lock`.
- **Exact Correction:** State explicitly that `/data` is read-only; for `--coverage`, resolve the lock at `coverage_path.parent / "sources.lock"`, and parameterize `fetch()` (`lock_filename` / `lock_destination`) so missing locks write `/out/sources.lock` and print `seerah-content/map/sources.lock` instead of `seerah-content/map/trial/`.

### M4. Inconsistent `"trial"` key policy in production PMTiles metadata

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:69, 269, 314` vs `tools/map/src/seerah_map/pack.py:83, 184`, `tools/map/src/seerah_map/base.py:403, 502`
- **Discrepancy & Rationale:** Line 69 allows `"False / omitted metadata marker"`, line 269 says `"must not contain 'trial': true"`, and line 314 says `pack-map` refuses `"if 'trial' key exists and is truthy (or present at all in production metadata)"`. If `pack.metadata()` or `base._write_metadata()` emits `"trial": False`, the strict branch on line 314 rejects it.
- **Exact Correction:** Standardize across Sections 2 and 3: omit the `"trial"` key completely from production PMTiles metadata, and have both `tools/map` verification (`trial=False`) and `tools/packages` (`pmtiles_inspect.py`) fail whenever `"trial"` is present in PMTiles metadata.

### M5. Inaccurate `workarea.fingerprint()` description and unimplemented directory atomic staging

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:201, 204` vs `tools/map/src/seerah_map/workarea.py:65-100, 166-182`
- **Discrepancy & Rationale:**
  - Line 201 lists `"tool_commit, image_digest, and source tree"` as simultaneous fingerprint inputs. In `workarea.py:91-100`, `fingerprint()` hashes `code_hash(src_root)` **only when `tool_commit is None`**, never alongside `tool_commit`.
  - Line 204 specifies `"Stages output atomically into /out/map-staging-<fp8>/"`, whereas `workarea.atomic_write` (`workarea.py:166-182`) handles single files only (`os.replace(tmp, path)`), not directories (and fails with `OSError` if a non-empty target directory already exists).
- **Exact Correction:** Describe `fingerprint()` accurately as matching `workarea.fingerprint()` (`tool_commit` when set, else `code_hash(src_root)`), and specify how directory staging achieves atomicity (e.g., staging in `/out/.map-staging-<fp8>.tmp`, removing/rotating any stale target if needed, or writing a `.staging.done` marker).

### M6. Undefined `ZIP_BUDGET_OVERHEAD` constant and missing `--passphrase-fd` on `pack-map`

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:203, 306, 318` vs `docs/engineering.md:30`
- **Discrepancy & Rationale:**
  - Line 203 checks `sum(data_files) + ZIP_BUDGET_OVERHEAD < 2,147,483,648` without defining `ZIP_BUDGET_OVERHEAD`'s value or formula (violating `docs/engineering.md:30` "No magic values").
  - Line 304 adds `--passphrase-fd` to `keygen` for automated tests, while `pack-map` (lines 306, 318) only uses interactive `getpass`, making automated Phase 3 CLI testing of `pack-map` fragile or blocking.
- **Exact Correction:** Define the exact value/calculation for `ZIP_BUDGET_OVERHEAD` (e.g., `1_048_576` bytes / `1 MiB` conservative ceiling for ZIP headers + `manifest.json` + `manifest.sig`), and add `[--passphrase-fd <int>]` to `pack-map`.

### M7. Missing CI triggers, Dependabot, `pyproject.toml`, `requirements-dev.txt` separation, and `docs/README.md` integration

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:210, 281, 365-366` vs `.github/workflows/map.yml:6, 8, 98`, `.github/workflows/ci.yml:20-33`, `requirements-dev.txt:1-4`, `.github/dependabot.yml:17-34`, `docs/README.md:3`, `docs/engineering.md:75`
- **Discrepancy & Rationale:**
  1. `.github/workflows/map.yml:6, 8` triggers only on `tools/map/**`, `map-data/example/**`, `.github/workflows/map.yml`. Adding `map-data/schema/map.schema.json` (line 210) without adding `"map-data/schema/**"` to `map.yml` leaves schema changes untested by the `map` workflow.
  2. `.github/workflows/map.yml:98` caches `/cache` on `hashFiles('map-data/example/example.lock')`; Phase 2's synthetic production example in `map-data/example/` needs `sources.lock` included in that cache key (and pinning the same cached files to avoid extra downloads).
  3. Spec line 281 states `tools/packages/requirements.txt` is `"hash-locked ... Installed in a tiny host tooling venv (requirements-dev.txt or .venv), tested in .github/workflows/ci.yml."` `requirements-dev.txt` uses unhashed pins (`jsonschema==4.26.0`, `pre-commit==4.7.0`, `ruff==0.17.5`) and `ci.yml:25` runs `pip install -r requirements-dev.txt`; combining unhashed `requirements-dev.txt` with hash-locked `tools/packages/requirements.txt` under `--require-hashes` fails.
  4. Phase 3 omits `tools/packages/requirements.in`, `tools/packages/pyproject.toml` (setting `pythonpath = ["src"]`, `testpaths = ["tests"]` as in `tools/map/pyproject.toml`), `tools/packages/__init__.py` / `__main__.py`, `tools/packages/README.md` (required per `docs/engineering.md:75`), updating `.github/dependabot.yml` for `/tools/packages` (`pip`), and listing `docs/signing-runbook.md` in `docs/README.md` (Phase 2 lists `docs/README.md`, Phase 3 does not).
- **Exact Correction:** Add those exact repository updates (`map.yml` `paths:` + cache key, separate `ci.yml` install/test job using `tools/packages/requirements.txt`, `tools/packages/{requirements.in,pyproject.toml,README.md,src/seerah_packages/__main__.py}`, `.github/dependabot.yml`, and Phase 3 `docs/README.md` update).

### M8. `verify` / `pmtiles_inspect.py` misses archive checks, `map.json` size cap, compression handling, and streaming details

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:313-316, 328, 334` vs `tools/map/src/seerah_map/pack.py:159-186`, `tools/map/src/seerah_map/base.py:392-406`, `tools/map/requirements.in:6`
- **Discrepancy & Rationale:**
  - Line 316 checks `base.pmtiles` `minzoom == 0`, `tile_type == MVT`, layers `{"land", "coast"}`, omitting `base.pmtiles` `max_zoom == map.json["sources"]["base"]["max_zoom"]`, and omits checking `relief.pmtiles` `min_zoom == 0` and header `bounds` against `map.json["data_extent"]` (unlike `pack.py:174-183` and `base.py:401`).
  - Line 328 enforces `manifest.json <= 1 MiB` and `manifest.sig == 64` bytes before `json.loads()`, whereas line 334 parses `data/map.json` with no pre-read byte limit.
  - Line 313 assumes `"gzip JSON metadata"` instead of dispatching on the PMTiles v3 127-byte header's `internal_compression` byte (`NONE` vs `GZIP`), and line 334 does not specify reading PMTiles headers/metadata from the `1.4 GiB` `ZIP_STORED` member via bounded offset reads / `ZipExtFile` without buffering the archive entry in memory.
  - Note also that `tools/map` already pins `pmtiles==3.8.1` (`tools/map/requirements.in:6`, BSD-3-Clause); Phase 3 should cross-check `pmtiles_inspect.py` against `pmtiles.reader.Reader` in tests.
- **Exact Correction:** Add the missing `base.pmtiles` `max_zoom`, `relief.pmtiles` `min_zoom`/`bounds`, and `data/map.json <= 1 MiB` checks; handle PMTiles v3 `internal_compression` (`NONE`/`GZIP`) in `pmtiles_inspect.py`; and specify streaming/offset-based PMTiles inspection inside `verify()`.

### M9. JSON Schema validation gaps (`Draft202012Validator` `format`, sorting, `source` strictness, open `reconstructed_areas` fields)

- **Severity:** Major
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:156, 258, 287, 294-295` vs `tools/map/src/seerah_map/config.py:136-138, 315-345`, `content/schema/content.schema.json:42-52, 71`, `docs/open-decisions.md:11`
- **Discrepancy & Rationale:**
  - `config.py:137` instantiates `Draft202012Validator(schema)` with no `FormatChecker`. In `jsonschema==4.26.0`, `"format": "date-time"` (`manifest.schema.json` `created`) and `"format": "uri"` (`cleanup.schema.json` `source.url`) are ignored unless an explicit regex `pattern` is included or a `FormatChecker` is passed.
  - Line 156 reuses `content.schema.json` `$defs.source`, which lacks `additionalProperties: false` and `minLength: 1` on `work`/`locator`; additionally `config.load_edits()` (`config.py:315-345`) omits `_check_finite`, so `load_cleanup()` must explicitly call `_check_finite()`.
  - Line 295 states `files` are sorted lexicographically by `path` and have no duplicate paths, which JSON Schema cannot enforce (`uniqueItems` checks whole objects, not `.path` keys).
  - Line 287 leaves `"additionalProperties: false (or explicit forward-compatibility note ...)"` undecided in the schema itself.
  - Line 258 specifies `{id, title_ar, footprint, sources: [...]}` for future `reconstructed_areas`, filling an open decision (`docs/open-decisions.md:11`: disclosure design is undecided; also `content.schema.json:71` uses `title`, not `title_ar`).
- **Exact Correction:** Give `created` an explicit regex (`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$`), tighten `cleanup.schema.json` `$defs.source` (`additionalProperties: false`, `minLength: 1`) and enforce `_check_finite()` + ring closure + WGS84/EPSG:3857 bounds in `load_cleanup()`, state that Python code enforces `files` sort order and unique `path`s, lock `additionalProperties: false` in producer `manifest.schema.json` (with forward-compat note for Android readers), and mark the future `reconstructed_areas` item shape on line 258 as illustrative/tentative only.

---

## Nits

### N1. `MASK_SIGMA_PX` home attributed to `mask.py` instead of `grid.py`

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:56` vs `tools/map/src/seerah_map/grid.py:18`, `tools/map/src/seerah_map/mask.py:10`
- **Discrepancy & Correction:** `MASK_SIGMA_PX = 4.0` is defined in `grid.py:18` and imported by `mask.py:10`. Clarify `"MASK_SIGMA_PX = 4.0 (defined in grid.py)"`.

### N2. `render_block()` does not build `mask.vrt`

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:57` vs `tools/map/src/seerah_map/blockstage.py:247-297, 364-379, 539`
- **Discrepancy & Correction:** `render_block()` only writes `work.mask_dir / f"{block.id}.tif"`; `write_mosaics()` (`blockstage.py:364-379`, called by `run_block_stage` at line 539) builds `mask.vrt`. Update line 57 to cite `write_mosaics()`.

### N3. Garbled `trial` assertion description in `Verified Codebase` item 4

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:69` vs `tools/map/src/seerah_map/pack.py:184-185`, `tools/map/src/seerah_map/base.py:403-404`
- **Discrepancy & Correction:** `"assert meta.get('trial') is not True failure as an error"` is grammatically inverted; `pack.verify()` and `base.verify_base()` raise when `meta.get("trial") is not True`. Rephrase to `"reject meta.get('trial') is not True as an error"`.

### N4. `StyleJson.kt` `tileSize: 256` applies to both `Painted` and `Value`

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:90` vs `android/core/map/src/main/kotlin/app/seerah/map/StyleJson.kt:82-87`
- **Discrepancy & Correction:** Line 90's wording makes `tileSize: 256` sound specific to `Value`; `StyleJson.kt:85-86` sets `"tileSize": 256` and `"maxzoom": spec.maxZoom` on both `Painted` (`raster`) and `Value` (`raster-dem`) sources.

### N5. Reusable helpers in `config.py` and `edits.py` not explicitly cited

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:159, 185, 189` vs `tools/map/src/seerah_map/config.py:133-161, 233-248`, `tools/map/src/seerah_map/edits.py:25-37, 105-160`
- **Discrepancy & Correction:** Note explicitly that `load_coverage()` and `load_cleanup()` should reuse `config._validate`, `config._check_finite`, `config._resolve_outline` (for optional `peninsula_outline`), `edits.applicable`, `edits._srs`, and shared OGR/GDAL rasterization helpers in `edits.py` (which already handle `MultiPolygon` in `_latitudes` and `gdal.Rasterize`).

### N6. `tile_schema: 1` `land` layer description omits Gaussian smoothing & `>= 128` threshold

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:263` vs `tools/map/src/seerah_map/mask.py:30-35`, `tools/map/src/seerah_map/base.py:153`
- **Discrepancy & Correction:** Line 263 describes `land` inside `data_extent` as `"WBM != 1 after approved cleanup"`, omitting that it comes from the `>= 128` threshold of the `MASK_SIGMA_PX = 4.0` smoothed land mask (matching line 264's `coast` description).

### N7. PMTiles v3 binary header vs JSON metadata terminology

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:269` vs `tools/map/src/seerah_map/pack.py:77-91`, `tools/map/src/seerah_map/base.py:500-508`
- **Discrepancy & Correction:** Line 269 says `"production PMTiles headers & JSON metadata include 'tile_schema': 1, ..."`, whereas `"tile_schema"`, `"encoding"`, `"vector_layers"`, `"fingerprint"`, `"tool_commit"`, `"data_commit"`, and `"image"` reside in PMTiles JSON metadata only (while the 127-byte header stores `min_zoom`, `max_zoom`, `tile_type`, `bounds`; and `disk_to_pmtiles` also requires `"format"`, `"minzoom"`, `"maxzoom"`, `"bounds"` in `metadata.json` when packing `relief.pmtiles`).

### N8. `tool_commit` vs `source_commit` hash length inconsistency in `manifest.schema.json`

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:293, 297`
- **Discrepancy & Correction:** `source_commit` allows 40-char SHA-1 or 64-char SHA-256 hex, while `tool_commit` allows only 40-char SHA-1 hex; accept `^(?:[0-9a-f]{40}|[0-9a-f]{64})$` for both.

### N9. `keygen` output filenames, overwrite guard, and `key_id` validation unspecified

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:302-305`
- **Discrepancy & Correction:** Specify exact output filenames under `--out-dir` (e.g., `<key_id>.key.pem` and `<key_id>.pub.json`), refuse overwriting existing key files, and validate `--key-id` against `^[a-z0-9][a-z0-9-]{0,63}$`.

### N10. `--public-keys <json_or_dir>` input contract unspecified

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:325, 331`
- **Discrepancy & Correction:** Specify the expected structure of a single JSON file (single key object vs array of 1–2 key objects), enforce `1 <= len(keys) <= 2` when reading a directory of `.json` files, and reject duplicate `key_id` or duplicate raw public key bytes.

### N11. Pre-commit `detect-private-key` constraint on Phase 3 test fixtures

- **Severity:** Nit
- **Affected Scope:** `docs/design/v1-map-pipeline-spec.md:366` vs `.pre-commit-config.yaml:14`
- **Discrepancy & Correction:** `.pre-commit-config.yaml:14` runs `detect-private-key` on every commit, which flags literal `-----BEGIN ... PRIVATE KEY-----` PEM headers. Note in Phase 3 that unit tests must generate test Ed25519 keys dynamically via `Ed25519PrivateKey.generate()` or `from_private_bytes()` rather than committing PEM private-key fixtures.
