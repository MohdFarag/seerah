# Implementation Readiness Audit: `docs/design/v1-map-pipeline-spec.md`

**Target:** `docs/design/v1-map-pipeline-spec.md`
**Reviewer:** `egm-implementation-readiness`

---

**Verdict: Not implementation-ready (needs revision before Phases 1–3 coding).**  
While the overall architecture fits ADR 0007 and ADR 0009, an implementer cannot write Phases 1–3 without inventing multiple unstated contracts, schemas, CLI behaviors, and test rules—and would hit direct contradictions with `tools/map/src/seerah_map/` (`cli.py`, `fetch.py`, `pack.py`, `base.py`, `blockstage.py`, `grid.py`, `report.py`) and `tools/map/run.py`.

---

## Summary by Audit Question

1. **Lock filenames, nesting & `run.py`:** **Broken/Ambiguous (Blocker).** The spec mixes `/data/sources.lock`, `/out/coverage.lock`, and nested `map-data/example/coverage.yaml` (under `--data map-data`), while `run.py` mounts `/data:ro` and `fetch.py` hardcodes `seerah-content/map/trial/` and `<stem>.lock`.
2. **Schemas, `data/map.json`, `build-provenance.json`, PMTiles metadata:** **Incomplete (Blocker).** `build-provenance.json` has no schema at all; `map.schema.json` lacks formal property/conditional definitions (`painted-rgba` vs `terrarium-value`, `regional_max_zoom` mapping); PMTiles metadata hedges on omitting `"trial"` vs `"trial": false`.
3. **Test cases & acceptance checks (Phases 1–4):** **Gaps & Unactionable Checks (Blocker/Major).** Phase 2 mandates matching a cached `INFO/` Copernicus licence file that `fetch.py` never downloads; several tests lack concrete pass/fail mechanics (`< 2 GiB` in CI, verify-before-parse, Tink vector cross-check, Phase 4 `validate` command).
4. **D1–D4 completeness:** **Incomplete (Major).** Trial placeholders (`min_data_margin_deg: 1.0`, `widest_half_screen_dp: 550.0`), Option (b)/valley omission from `coverage.schema.json`, `ZIP_STORED` lock-in for `content` via `builder.py`, and N/E quadrant lock-in in `map.schema.json` are decided in prose without owner sign-off.

---

## Blockers

### B1. `build-provenance.json` has no schema, and `pack.py` sidecar `metadata.json` triggers G9 self-refusal

- **Affected Scope:** Overview (line 107), Section 1 (line 137), Section 2 (lines 185, 202, 208), Section 3 (line 309); `tools/map/src/seerah_map/pack.py` (lines 109, 127–128), `report.py` (lines 18–27, 78–84), `cli.py` (lines 229–236).
- **Finding:**
  - `tools/map` writes `<staging>/build-provenance.json` in Phase 2, and `tools/packages/ pack-map` validates it in Phase 3 (refusing `"missing fields"`, `"trial": true`, `"unknown"`, or `"local:*"` `image`). Yet the spec never defines the JSON schema or keys of `build-provenance.json`:
    - `report.py` uses `"provenance": {"tool_commit", "data_commit", "image", "fingerprint"}`, `"stage_seconds"`, `"memory"`, `"disk"`, `"tiles"`, `"size_by_zoom"`, `"edits"`, `"workers"`. Does `build-provenance.json` keep that `_SHAPE`, add `"purpose": "production"` / `"schema": 1`, rename `"data_commit"` to `"source_commit"`, and include `coverage` metadata?
    - `pack-map` (line 306) has no `--source-commit`, `--tool-commit`, or `--image` flags, so it must extract `source_commit`, `tool_commit`, and `image` from `build-provenance.json` (or PMTiles metadata)—the exact source keys are unstated.
    - When optional `peninsula_outline` is omitted from `coverage.yaml` (line 185), `size_by_zoom` in `cli.py` line 268 and `pack.size_by_zoom` (which requires `outline: Path`) would crash or fail `report._check_shape()`. What `build-provenance.json` emits without `peninsula_outline` is unstated.
  - Meanwhile, `pack.pack()` (line 127) currently writes `metadata.json` into `out_dir`. In production mode, writing `metadata.json` to `<staging>/metadata.json` causes `pack-map` line 308 to refuse it; writing it to `<staging>/data/metadata.json` causes `pack-map` line 311 ("exact expected files `base.pmtiles`, `relief.pmtiles`, `map.json`") to refuse it.
- **Concrete Resolution:**
  1. Define the exact JSON structure (or schema) of `build-provenance.json` in Section 2 (e.g., existing `report.json` shape plus `"purpose": "production"` and optional `"size_by_zoom"`—either omitted/empty/total-only when `peninsula_outline` is `None`), explicitly stating whether `provenance.data_commit` supplies `manifest.source_commit`.
  2. Specify that in production mode (`--coverage`), `pack.py` writes `metadata.json` only inside the temporary `work.tiles_dir` for `disk_to_pmtiles` and **must not** copy `metadata.json` into `<staging>/` or `<staging>/data/` (and writes `build-provenance.json` instead of `report.json`).

### B2. Lock resolution (`sources.lock` vs `/out/coverage.lock`), `--coverage` nesting, and `cli.py`/`fetch.py`/`run.py` mismatch

- **Affected Scope:** Architecture Overview (lines 99–101), Section 2 (lines 167, 194–200), Phase 2 (line 365); `tools/map/src/seerah_map/cli.py` (lines 123–163, 307–322), `fetch.py` (lines 40, 202–208, 295), `tools/map/run.py` (lines 142, 200).
- **Finding:**
  - Line 197 says `fetch --coverage coverage` *"verifies/writes `/data/sources.lock` (if missing, writes `/out/coverage.lock` and instructs owner to copy to `seerah-content/map/sources.lock`)"*. First, `/data` is mounted `:ro` by `run.py` line 142, so `fetch` can never write `/data/sources.lock`. Second, writing `/out/coverage.lock` while expecting `/data/sources.lock` forces manual renaming (due to `fetch.py` line 295 hardcoding `out / f"{profile.name}.lock"`).
  - Line 167 places the synthetic production example at `map-data/example/coverage.yaml`, and `.github/workflows/map.yml` mounts `$PWD/map-data:/data:ro` (as does `tools/map/README.md` line 53 with `--profile example/example`). Passing `--coverage example/coverage` makes `coverage_path = /data/example/coverage.yaml`, yet lines 197–200 hardcode `/data/coverage.yaml`, `/data/cleanup.yaml`, and `/data/sources.lock` instead of resolving `cleanup.yaml` and `sources.lock` in `coverage_path.parent`!
  - `fetch.py` line 40 hardcodes `_LOCK_DESTINATION = "seerah-content/map/trial/"` in the printed `cp` command.
  - `cli.py` line 315 defines `--variant` with `required=True` and `--mode` with `default=PYRAMID`. Because `--coverage` forbids `--variant` and `--mode` (line 199), an implementer cannot even tell whether `--mode pyramid` was explicitly passed on the CLI unless `default=None` is used.
- **Concrete Resolution:**
  1. Specify `--coverage <rel>` works identically to `--profile <rel>`: relative path stem under `--data` without `.yaml` (rejecting `.yaml` suffix, leading `/`, and `..` segments), so `--coverage coverage` reads `<data>/coverage.yaml` and `--coverage example/coverage` reads `<data>/example/coverage.yaml`.
  2. Resolve `cleanup.yaml` and `sources.lock` unconditionally in `coverage_path.parent` (`coverage_path.parent / "cleanup.yaml"`, `coverage_path.parent / "sources.lock"`).
  3. On missing lock in `fetch --coverage`, verify cache and write `/out/sources.lock` (matching the target filename) and print `cp /out/sources.lock seerah-content/map/sources.lock`. Add `lock_name` / `lock_destination` parameters to `fetch()`.
  4. In `cli.py`, make `--profile` and `--coverage` a `mutually_exclusive_group(required=True)` on `fetch` and `build`, set `--variant` `required=False, default=None` and `--mode` `default=None`, requiring `--variant` (error exit `2`) under `--profile` and rejecting `--variant`/`--mode` (exit `2`) under `--coverage`.

### B3. `map-data/schema/map.schema.json` is unspecified (`painted-rgba`, `regional_max_zoom`, `credits`, `reconstructed_areas`)

- **Affected Scope:** Section 2 (lines 210–259, 175–178) and existing `blockstage.py`/`tilestage.py`/`base.py`/`pack.py`/`report.py`.
- **Finding:**
  - The spec gives only a single JSON example (`terrarium-value`), leaving `map.schema.json` unimplementable without guessing:
    - How `coverage.yaml` fields map to `map.json` is never stated explicitly: `coverage.min_zoom` / `coverage.max_zoom` (camera zooms) map to top-level `min_zoom` / `max_zoom`, whereas `coverage.regional_max_zoom` maps to `sources.base.max_zoom` and `sources.relief.max_zoom` (and does **not** appear as a top-level `regional_max_zoom` key).
    - Meanwhile, existing `tools/map` modules (`blockstage.py`, `tilestage.py`, `base.py`, `pack.py`, `report.py`) all treat `profile.max_zoom` as the **build zoom**. Without specifying a build-config adapter or protocol (`build_max_zoom` vs `camera_max_zoom`), implementers risk passing camera `max_zoom` (e.g. `14`) into `expected_tiles()`/`pack.metadata()`, which immediately fails `max_zoom <= BASE_ZOOM (12)` checks.
    - No rules are given for `sources.relief` when `encoding == "painted-rgba"` (must `tile_format` be `"webp"` via `if/then`? Is `tile_size` `const: 256`?), `sources.base` (`file: const "base.pmtiles"`, `layers: const ["land", "coast"]` or `items` enum, `min_zoom: const 0`, `max_zoom: integer 6..12`), `additionalProperties` (strict `false` vs open for future `tile_schema: 1` optional keys like `valley`/`relief-detail`), `credits[]` schema (`id`, `source`, `attribution`, optional `liability`, min lengths/items), `reconstructed_areas` (`maxItems: 0` in v1 or full item schema?), or deterministic JSON serialization (`indent=2, sort_keys=True, ensure_ascii=False` for `©`).
- **Concrete Resolution:**
  1. Add a complete field/constraint table for `map-data/schema/map.schema.json` covering every property, type, `const`, `if/then` (`terrarium-value` <-> `png`, `painted-rgba` <-> `webp`), `additionalProperties` policy, `credits[]` schema, `reconstructed_areas` schema, and `camera_bounds`/`data_extent` bounds.
  2. Explicitly document the `coverage.yaml` -> `data/map.json` field mapping (including `regional_max_zoom` -> `sources.{base,relief}.max_zoom`) and define how `tools/map` adapts `Coverage` to `blockstage`/`tilestage`/`base`/`pack` (`build_max_zoom` = `regional_max_zoom`).

### B4. Phase 2 "`INFO/` Copernicus licence check" cannot run as specified

- **Affected Scope:** Verified Fact 6 (line 82), Phase 2 Roadmap (line 365), Known Risks (line 379); `tools/map/src/seerah_map/fetch.py` (lines 260–267).
- **Finding:** Phase 2 lists "`INFO/` Copernicus licence check" as a deliverable and *"verified Copernicus licence string matches cached `INFO/` file"* as a Key Check that **"All Must Pass Before Merge"**. However, `fetch.py` only downloads `{name}.tif` and `AUXFILES/{wbm_name}`. Nothing in `seerah-map-cache` or `map-data/example/` contains an `INFO/` licence file, `build` runs with `--network none`, and adding `INFO/` to `fetch.py` would break the existing `Lock` schema and `example.lock`.
- **Concrete Resolution:** Either (a) reclassify this out of Phase 2 automated/CI merge checks into a one-time documented manual verification gate (recording the fetched S3 `INFO/` path, SHA-256, and verified exact wording in `docs/` or `THIRD_PARTY_NOTICES.md` before first release), or (b) explicitly specify fetching/caching one `INFO/` licence file (or adding a dedicated script) without altering existing cell lock entries.

### B5. Ambiguous `"trial"` metadata policy and `pmtiles_inspect.py` checks

- **Affected Scope:** Verified Fact 4 (line 69), Section 2 (lines 202, 269), Section 3 (lines 313–316, 334), Known Risks (line 376).
- **Finding:**
  - Line 69 writes `(False / omitted metadata marker)` and line 314 writes `Refuse if "trial" key exists and is truthy (or present at all in production metadata)`. An implementer writing `"trial": False` in Phase 2 would fail a Phase 3 `pmtiles_inspect.py` that forbids any `"trial"` key.
  - Line 269 includes `"data_commit"`, `"tool_commit"`, `"image"`, `"fingerprint"` in PMTiles metadata, while `pack.py` also emits `"variant"` and `"mode"`; it is unstated whether `"variant"` and `"mode"` stay or are omitted in production `relief.pmtiles`, and whether `pack-map` cross-checks provenance fields across `build-provenance.json`, `base.pmtiles`, and `relief.pmtiles`.
  - Line 316 omits checking `base.pmtiles` `maxzoom == map.json["sources"]["base"]["max_zoom"]` and `relief.pmtiles` `bounds` vs `map.json["data_extent"]`, and doesn't specify how `pmtiles_inspect.py` handles PMTiles v3 header `internal_compression` (gzip vs uncompressed) or bounded metadata reads (`<= 1 MiB`), or whether `verify` reads directly via seekable `ZipExtFile` (avoiding copying `1.365 GiB` to temp disk).
- **Concrete Resolution:**
  1. Mandate **omitting `"trial"` completely** in production `base.pmtiles` and `relief.pmtiles` metadata, and in `pmtiles_inspect.py`, `pack.verify(trial=False)`, and `base.verify_base(trial=False)` **reject if `"trial" in meta`** regardless of value.
  2. Specify exact required/allowed PMTiles JSON metadata keys (`tile_schema: 1`, `encoding` on relief, `vector_layers` on base, `fingerprint`, `tool_commit`, `data_commit`, `image`, plus `name`, and explicitly whether `variant`/`mode` are kept or omitted), exact binary header assertions for both archives (including `tile_compression`/`internal_compression`, `bounds` tolerance `1e-6` deg, and `base.pmtiles` `maxzoom`), and specify `pmtiles_inspect.py` accepting a seekable `BinaryIO` with a `1 MiB` metadata decompression cap.

### B6. Hedged rectangular coverage check and missing `widest_half_screen_dp` wiring in `base.py`

- **Affected Scope:** Section 2 (lines 183, 190); `tools/map/src/seerah_map/grid.py` (lines 306–347), `base.py` (lines 316–320, 438–440).
- **Finding:** Line 190 specifies `min(side_margins)` *"or generalize `check_base_coverage` to accept an explicit rectangular `data_extent` Box directly — recommended"*. Leaving both options open yields different `ne_max` zoom limits on asymmetric margins. Worse, `base.py` lines 317 and 439–440 currently hardcode `WIDEST_HALF_SCREEN_DP` and `TRIAL_MIN_MAPLIBRE_ZOOM` instead of `coverage.widest_half_screen_dp` and `floor(coverage.min_zoom)`, and `grid.check_base_coverage` raises `ValueError` while `load_coverage()` must raise `ConfigError`.
- **Concrete Resolution:** Remove the `or` hedge: mandate generalizing `grid.natural_earth_max_zoom` and `grid.check_base_coverage` to accept `data_extent: Bounds` (keeping a backwards-compatible wrapper or default behavior for `Profile`), pass `coverage.widest_half_screen_dp` and `math.floor(coverage.min_zoom)` into `base.py` (`_natural_earth_slices`, `natural_earth_max_zoom`, `check_base_coverage`), and catch `ValueError` in `load_coverage()` to raise `ConfigError`.

### B7. Undefined `ZIP_BUDGET_OVERHEAD` and ambiguous staging atomicity

- **Affected Scope:** Section 2 (lines 203–208).
- **Finding:** Line 203 enforces `sum(data_files) + ZIP_BUDGET_OVERHEAD < 2,147,483,648` bytes without defining `ZIP_BUDGET_OVERHEAD` (e.g., `65_536` vs `1_048_576` bytes). Line 204 says *"Stages output atomically into `/out/map-staging-<fp8>/`"* without specifying how a directory is staged atomically (remember `/work` and `/out` are separate mounts so `os.replace` across them fails with `EXDEV`, and `os.replace` cannot replace an existing non-empty directory).
- **Concrete Resolution:** Define an explicit constant (e.g., `ZIP_BUDGET_OVERHEAD = 1_048_576` — 1 MiB generous upper bound for `manifest.json` + `manifest.sig` + ZIP headers) and specify staging inside `args.out / f".map-staging-{fp8}.tmp"` followed by atomic swap/replacement into `args.out / f"map-staging-{fp8}"` (cleaning `.tmp` on failure).

---

## Majors

### M1. `tools/packages/` CLI details: `keygen` filenames, missing `--passphrase-fd` on `pack-map`, `--public-keys` format, and exit codes

- **Affected Scope:** Section 3 (lines 280–281, 300–335).
- **Finding:**
  - `keygen` (lines 302–305) never names the output files written into `--out-dir` (e.g., `<key_id>.key.pem` and `<key_id>.pub.json`?), overwrite behavior if they already exist, or whether `public_key_base64` uses standard padded Base64.
  - `pack-map` (lines 306, 318) omits `--passphrase-fd` (present on `keygen`), making non-interactive CLI testing in `pytest` impossible without mocking `getpass`. It also doesn't state how `self-verify` obtains the public key (must derive `private_key.public_key()` paired with `--key-id`), overwrite behavior if `<out>/seerah-map-<version>.zip` exists, or whether `verify` also needs `--allow-local-provenance`.
  - `verify` (lines 325–334) leaves `--public-keys <json_or_dir>` format unspecified (single key object vs array, directory `*.json` globbing, rejecting `0` or `> 2` keys or duplicate `key_id`s, rejecting `algorithm != "Ed25519"` / `tink_variant != "NO_PREFIX"`). Also must mandate bounded streaming reads (`.read(1_048_576 + 1)` for `manifest.json`, `.read(65)` for `manifest.sig`, chunked size verification stopping if stream exceeds `entry["size"]`) to stop spoofed-header ZIP bombs, plus requiring `ZIP_STORED` on `kind == "map"` members and using `zf.open(zi, "w")` instead of `zf.writestr()` in `zipper.py` (so `1.4 GiB` archives don't consume ~2.8 GiB RAM).
  - Exit codes (`0` / `1` / `2`), `builder.py`'s `build_package(...)` signature, and Python invocation setup (`pyproject.toml` vs `PYTHONPATH=tools/packages/src`, `requirements.in`) are unstated.
- **Concrete Resolution:** Specify exact `keygen` filenames and no-overwrite guard, add `--passphrase-fd` to `pack-map` and `--allow-local-provenance` to `verify`, define the `--public-keys` JSON/directory schema and `0 < keys <= 2` check, specify exit codes (`0`/`1`/`2`), `build_package(...)` parameters, streaming `ZipFile.open` writes, and `PYTHONPATH`/`pyproject.toml` + `ci.yml` invocation.

### M2. `manifest.schema.json` regex/format traps (`created`, `url`, `image` vs `--allow-local-provenance`, hedging)

- **Affected Scope:** Section 1 (line 156), Section 3 (lines 287–298); `tools/map/requirements.txt`.
- **Finding:**
  - `jsonschema==4.26.0` without `rfc3339-validator` / `rfc3987` (neither is in `requirements.txt`) ignores JSON Schema `"format": "date-time"` and `"format": "uri"`! Relying on `"format"` alone leaves invalid timestamps/URIs unchecked.
  - Putting `^sha256:[0-9a-f]{64}$` in `manifest.schema.json` (line 298) breaks `pack-map --allow-local-provenance` when it validates its own generated `manifest.json` against `manifest.schema.json`.
  - Line 287 hedges on `additionalProperties: false` `(or explicit forward-compatibility note ...)`.
- **Concrete Resolution:**
  1. Set `"additionalProperties": false` in `manifest.schema.json` (documenting ADR 0007/0008 reader forward-compatibility in `$comment`), add explicit regex patterns for `created` (`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` + `datetime.strptime` calendar validation), `source_commit`/`tool_commit` (`^(?:[0-9a-f]{40}|[0-9a-f]{64})$`), and `files[].path` rules (`^data/...`, no `//`, no trailing `/`, no `..` segment, sorted & unique checked in validator).
  2. Allow `^(?:sha256:[0-9a-f]{64}|local:[A-Za-z0-9:._-]+)$` in `manifest.schema.json`, and enforce `image.startswith("sha256:")` in `pack-map` and `verify` unless `--allow-local-provenance` is passed.

### M3. `cleanup.schema.json` edge cases and `blockstage.py` / `edits.py` / `grid.py` integration

- **Affected Scope:** Section 1 (lines 138, 145–160); `tools/map/src/seerah_map/blockstage.py`, `edits.py`, `grid.py`.
- **Finding:**
  - `cleanup.schema.json` doesn't state coordinate bounds (`[-180, 180]`/`[-85.0511287798066, 85.0511287798066]` vs N/E only, and checking `ring[0] == ring[-1]` explicitly in `load_cleanup()`), `minLength: 1` / non-whitespace `reason`, `additionalProperties: false` + `minLength: 1` on `sources[]`, or whether `draft` allows `sources: []`.
  - Line 138 requires `_render_coarse_block()` to share `apply_cleanup`, yet `_render_coarse_block()` (lines 310–347) does **not** warp `wbm_vrt`—it warps blurred `mask_vrt` (`land_values`), so `fill-water` (`wbm == 1`) cannot evaluate there! Moreover, `grid.border_px()` and `edits.touches()` access `Edit.feather_m` and `Edit.width_m`, which a `CleanupEdit` dataclass doesn't have.
- **Concrete Resolution:** Define exact `cleanup.schema.json` coordinate bounds, explicit ring-closure check in `load_cleanup()`, `sources` sub-schema strictness, a `CleanupEdit` dataclass + `run_block_stage(..., cleanups=())` / `render_block(..., cleanups=())` signatures, and drop `_render_coarse_block()` from `apply_cleanup` (since `--coverage` enforces `mode == "pyramid"`, and `_render_mask_block()` can optionally apply `wbm`-only cleanup if ever needed).

### M4. Incomplete/non-concrete test specifications across Phases 1–4

- **Affected Scope:** Section 4 (line 356), Phased Roadmap Table (lines 362–368).
- **Finding:**
  - **Phase 1:** Doesn't specify that the synthetic `cleanup` fixture goes in `tools/map/tests/fixtures/` (not `map-data/example/`, which is `purpose: trial` in Phase 1), nor exact `remove-land` + `fill-water` + `style.py` zero-bleed test assertions.
  - **Phase 2:** Doesn't include a `< 2 GiB` failure unit test in `tools/map` or a `painted-rgba` production build test; doesn't clarify that the determinism test compares `data/{base.pmtiles,relief.pmtiles,map.json}` and **excludes** `build-provenance.json` (which contains wall-clock `stage_seconds` and `memory.peak_bytes`).
  - **Phase 3:** `"Tink NO_PREFIX fixed test vector cross-check"` is ambiguous given line 48 excludes Android changes (clarify checking RFC 8032 / Tink raw test vectors in Python and committing a test vector fixture for future `:core:content`); doesn't specify testing `< 2 GiB` failure without writing a 2 GiB file in CI (monkeypatched limit) or how `verify-before-parse` asserts `json.loads` is not called; doesn't explain how `ci.yml` (which lacks GDAL/`tools/map`) obtains valid PMTiles v3 archives for `tools/packages` tests.
  - **Phase 4:** References "`validate` / `seerah_map` config validation", but neither `tools/validate_content.py` nor `tools/map` has a `validate` command for `cleanup.yaml`/`coverage.yaml`; before/after crop PNG generation method (lines 356, 367) is unspecified.
- **Concrete Resolution:** Update the Phases 1–4 Key Checks with exact fixture paths, assertion targets (excluding `build-provenance.json` from byte equality), `MAX_PACKAGE_BYTES` monkeypatch testing, minimal pure-Python PMTiles v3 fixture generation for `ci.yml`, and either add a `seerah_map validate --coverage <rel>` subcommand (exit `0`/`2`) or specify the exact validation + crop-rendering script for Phase 4.

### M5. Hidden decisions in prose outside D1–D4

- **Affected Scope:** Owner Decisions (lines 23–33) vs Section 2 (lines 173–183, 217–218, 257), Section 3 (lines 320, 335).
- **Finding:** Four real decisions are embedded in prose without appearing in D1–D4:
  1. **Default values in `coverage.schema.json` (`min_data_margin_deg: 1.0`, `widest_half_screen_dp: 550.0`):** `open-decisions.md` line 9 and `grid.py` line 20 mark `1°` margin and `550.0 dp` as trial placeholders only. Making them schema defaults violates `AGENTS.md` (*"never fill an open decision with an assumption in code or docs"*). Better: make both **required** in `coverage.yaml` (no defaults) or obtain explicit owner sign-off.
  2. **Deferring/excluding `detail_max_zoom` (Option b) and `valley` from `coverage.schema.json`:** `step-4.md` line 11 notes the map trial isn't formally closed pending Oppo A55 and MatePad 11.5. Confirm with owner that `coverage.schema.json` `schema: 1` targets single-archive `relief.pmtiles` without Option (b)/valleys (relying on ADR 0009 decision 10 additive schema evolution if ever revisited).
  3. **`Uniform ZIP_STORED` in shared `builder.py`:** `tools/packages/` is shared with future `kind: "content"` packages (line 335), yet line 320 specifies `Uniform ZIP_STORED` (ADR 0007 explicitly noted uncompressed storage for `map`, leaving `content` JSON compression open). Clarify `builder.py` supports `compress_type` per `kind` (`ZIP_STORED` mandatory for `map`).
  4. **N/E quadrant restriction in `map.schema.json`:** Restricting `coverage.schema.json` to N/E works for `tools/map` today, whereas baking `0..180` / `0..90` into `tile_schema: 1` `map.schema.json` needlessly blocks any future westward expansion without bumping `tile_schema`.

---

## Nits

1. **Fingerprint wording (line 201):** Line 201 says *"over `coverage.yaml`, `cleanup.yaml`, `sources.lock`, `tool_commit`, `image_digest`, and source tree"*, whereas `workarea.fingerprint()` hashes `SRC_ROOT` only when `tool_commit is None` (and ignores `peninsula_outline`). Clarify reusing `workarea.fingerprint()` (or adding `peninsula_outline` bytes if present).
2. **`fade_deg` inequality & float tolerance (lines 174, 179):** Clarify `"if every side margin > 0"` as `"if min(side_margins) > 0"`, and guard `data_extent` vs `camera_bounds ± min_data_margin_deg` against IEEE-754 subtraction noise (e.g., `61.3 - 60.3 = 0.9999999999999929 < 1.0`, use a small tolerance like `1e-9` or `Decimal`).
3. **`data/map.json` Unicode escaping (lines 241–242):** Specify `ensure_ascii=False` so `©` is written literally in UTF-8 rather than `\u00a9`.
4. **Phase 2/3 DoD docs list (lines 365–366):** Add `content/README.md` (for `manifest.schema.json`), `docs/roadmap.md`, `CHANGELOG.md`, and `REUSE.toml` check alongside `map-data/README.md`, `tools/map/README.md`, `tools/README.md`, and `THIRD_PARTY_NOTICES.md`.
5. **Markdown/JSON key alignment (`min_app_version` / `canonical JSON`):** Line 317 hedges `"sort_keys=True or fixed schema key order"` — fix to `json.dumps(..., indent=2, sort_keys=True, ensure_ascii=False) + "\n"`, matching `pack.py` and `report.py`.
