# Zoom-Out Architectural Review: `docs/design/v1-map-pipeline-spec.md`

**Target:** `docs/design/v1-map-pipeline-spec.md`
**Reviewer:** `egm-zoom-out`

---

**Overall Verdict:** **Needs revision before owner approval and Phase 1–4 implementation.** The high-level split (WBM+DEM cleanup in `tools/map/` before `_land_mask_from_wbm()`, standalone host Ed25519 signing in `tools/packages/`, and keeping `trial` intact until Oppo A55/MatePad close) is sound. However, the spec currently introduces staging-path and metadata contradictions that break Phase 2 -> Phase 3 integration, writes custom tooling where repo dependencies already exist, locks undecided reconstruction/margin details prematurely, and drops into premature micro-modularization in `tools/packages/`.

---

## Findings

### Blockers

#### B1. Staging `metadata.json` collision and undefined `build-provenance.json` duplicating `report.json`

- **Where:** [v1-map-pipeline-spec.md](../v1-map-pipeline-spec.md#L103-L107) (Architecture Overview), [D3](../v1-map-pipeline-spec.md#L31), [lines 68-69](../v1-map-pipeline-spec.md#L68-L69), [lines 185, 202-208](../v1-map-pipeline-spec.md#L185-L208), [line 269](../v1-map-pipeline-spec.md#L269), [lines 308-314](../v1-map-pipeline-spec.md#L308-L314); vs [pack.py](../../../tools/map/src/seerah_map/pack.py#L114-L129), [cli.py](../../../tools/map/src/seerah_map/cli.py#L229-L269), [report.py](../../../tools/map/src/seerah_map/report.py#L18-L84).
- **Problem:**
  1. **Self-defeating staging layout:** `pack.pack()` (`pack.py` lines 127–128) unconditionally writes `out_dir / "metadata.json"` beside `relief.pmtiles`. In the spec's staging layout, `data/` holds `relief.pmtiles`, `base.pmtiles`, `map.json`, while `pack-map` refuses `<staging>/metadata.json` (line 308) *and* requires `data/` to hold only those exact three files (line 311). The spec never directs `pack.py` to skip writing `out_dir / "metadata.json"` in production mode. Additionally, line 69 leaves PMTiles `"trial"` as *"False / omitted"*, line 269 forbids `"trial": true`, and line 314 rejects `"trial"` if *"present at all"* — writing `"trial": False` breaks `pack-map`.
  2. **Redundant `build-provenance.json`:** `tools/map` already produces and shape-checks `report.json` (`report.py` `_SHAPE`, including `provenance`, `edits.applied/skipped`, `tiles`, `size_by_zoom`, `memory`, `disk`, `stage_seconds`). The spec introduces a new `build-provenance.json` with **no schema or field specification**, while ignoring `report.json` and ignoring that `cli.py` line 268 unconditionally requires `profile.peninsula_outline` (which `coverage.yaml` line 185 makes optional).
- **Fix:**
  1. Explicitly state that production mode (`trial=False`) **omits** `"trial"` (and trial-only `variant`/`mode`) from both PMTiles metadata (`pack.py`, `base.py`) and skips writing `out_dir / "metadata.json"` in `pack.py`.
  2. Delete `build-provenance.json` and reuse `report.json` (making `size_by_zoom` optional/nullable in `report.py` when `peninsula_outline` is absent, and including `"trial": false` or `"purpose": "production"` so `pack-map` checks it directly) — or explicitly specify `build-provenance.json`'s exact schema and state it replaces `report.json`.

#### B2. ZIP member ordering puts `1.37 GiB` payload before `manifest.json` and `manifest.sig`

- **Where:** [v1-map-pipeline-spec.md Section 3](../v1-map-pipeline-spec.md#L322); vs [ADR 0009 Consequences](../../adr/0009-map-data-pipeline.md#L253) and [ADR 0007 Decision 6](../../adr/0007-content-formats-compatibility-and-signing.md#L74).
- **Problem:** Line 322 orders ZIP members as `sorted data/*` first, then `manifest.json`, then `manifest.sig`, arguing `ZipFile.open()` uses the central directory anyway. However, `ADR 0009` line 253 explicitly anticipates **streaming extraction** of the map package on-device to cut `3×` free-space overhead (`~4.1 GiB` peak). Putting `1.365 GiB` of `data/relief.pmtiles` ahead of `manifest.json` and `manifest.sig` prevents any sequential stream reader (`ZipInputStream`) from verifying the signature, `version`, or `min_app_version` before reading the entire gigabyte payload.
- **Fix:** Put `manifest.json` and `manifest.sig` **first** in the ZIP member order, followed by lexicographically sorted `data/*` entries. That preserves O(1) central-directory access on `ZipFile` while enabling streaming verification/extraction.

#### B3. Foreclosing open decisions (`reconstructed_areas` item shape) and weakening ADR 0009's fixed margin guard via `coverage.yaml`

- **Where:** [v1-map-pipeline-spec.md Section 2](../v1-map-pipeline-spec.md#L182-L190) and [line 258](../v1-map-pipeline-spec.md#L258); vs [open-decisions.md](../../open-decisions.md#L3-L11), [ADR 0009](../../adr/0009-map-data-pipeline.md#L167-L186), and [grid.py](../../../tools/map/src/seerah_map/grid.py#L21).
- **Problem:**
  1. Line 258 prescribes the exact future JSON object `{id, title_ar, footprint, sources: [...]}` for `map.json`'s `reconstructed_areas`. Per `open-decisions.md` lines 10–11 and `ADR 0009` Not in scope (lines 225–226), both the reconstruction edit format and the disclosure UI design remain undecided. Locking concrete fields now violates `open-decisions.md` line 3 (*"leave it out until it is decided"*).
  2. Lines 182 and 189 place `min_data_margin_deg` (allowing `0`) inside `coverage.yaml`. `ADR 0009` Decision 7 requires *"the fixed data margin the specification defines"*; placing the threshold inside the validated file lets a misconfigured production `coverage.yaml` set `0` and silently bypass the margin check. Similarly, `widest_half_screen_dp` (line 183) is a target-device hardware property already defined as `WIDEST_HALF_SCREEN_DP = 550.0` in `grid.py`, not a map-coverage config field.
- **Fix:**
  1. In `map.schema.json` for v1, constrain `reconstructed_areas` only to an empty array (`"type": "array", "maxItems": 0`) and remove the speculative `{id, title_ar, footprint, sources}` shape from line 258.
  2. Remove `min_data_margin_deg` and `widest_half_screen_dp` from `coverage.schema.json`. Enforce the fixed `1.0°` minimum margin in `tools/map` (bypassable for synthetic zero-margin CI examples only via an explicit CLI/test flag like `--allow-zero-margin`, analogous to `--allow-local-provenance`), and keep `WIDEST_HALF_SCREEN_DP` in `grid.py`.

---

### Majors

#### M1. Reinventing a pure-Python PMTiles v3 binary parser (`pmtiles_inspect.py`) instead of reusing `pmtiles`

- **Where:** [v1-map-pipeline-spec.md](../v1-map-pipeline-spec.md#L281), [lines 313-316](../v1-map-pipeline-spec.md#L313-L316), [line 366](../v1-map-pipeline-spec.md#L366); vs [tools/map/requirements.in](../../../tools/map/requirements.in#L6), [tools/map/requirements.txt](../../../tools/map/requirements.txt#L124-L127), [pack.py](../../../tools/map/src/seerah_map/pack.py#L21-L22), and [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md#L29).
- **Problem:** The spec proposes writing `pmtiles_inspect.py` to hand-parse the 127-byte PMTiles v3 header and compressed metadata section in pure Python. Meanwhile, Protomaps' `pmtiles==3.8.1` is **already** pinned in `tools/map/`, pure Python, BSD-3-Clause approved, and has **zero transitive dependencies**. Hand-rolling a binary header/compression parser duplicates `pmtiles.reader.Reader` and creates unnecessary maintenance and spec-compliance risk.
- **Fix:** Drop `pmtiles_inspect.py`, add `pmtiles==3.8.1` to `tools/packages/requirements.txt`, and use `pmtiles.reader.Reader` (with `MmapSource` on disk or a ~10-line byte-slice reader over `ZIP_STORED` zip offsets during `verify`).

#### M2. Over-decomposed `tools/packages/` layout and duplicated map-domain verification

- **Where:** [v1-map-pipeline-spec.md Section 3](../v1-map-pipeline-spec.md#L275-L335), [Phase 3](../v1-map-pipeline-spec.md#L366); vs [ADR 0007 Decision 3](../../adr/0007-content-formats-compatibility-and-signing.md#L62), [ADR 0009 Decision 8 & 10](../../adr/0009-map-data-pipeline.md#L171-L183), [base.py](../../../tools/map/src/seerah_map/base.py#L392-L406), and [pack.py](../../../tools/map/src/seerah_map/pack.py#L159-L186).
- **Problem:**
  1. Breaking a small host packaging/signing tool into 6 separate modules (`builder.py`, `signing.py`, `zipper.py`, `verify.py`, `pmtiles_inspect.py`, `cli.py`) before the second caller (`content`) even exists is premature micro-modularization.
  2. `tools/packages/` (`pack-map` and `verify`) duplicates map-specific assertions (`minzoom == 0`, `tile_type == MVT`, `encoding`, `tile_format`, `max_zoom`) already verified inside `tools/map` (`pack.verify` and `base.verify_base`). Worse, line 316 asserts exact layer equality `{"land", "coast"}`, which directly violates `ADR 0009` Decision 10 (`tile_schema: 1` apps/validators must tolerate additive optional layers like `"valley"`!).
- **Fix:** Simplify `tools/packages/` to 2–3 cohesive modules (e.g. `signing.py`, `archive.py`, `cli.py` + `README.md`). Limit `tools/packages/`'s map-specific responsibility to G9 (`metadata.json` absent, `"trial"` absent from PMTiles metadata, `"tile_schema": 1` present) and `map.schema.json` validation for `kind == "map"`. If vector layers are checked there, assert `{"land", "coast"} <= layer_ids` (subset, not exact equality).

#### M3. `max_zoom` naming collision, missed `natural_earth_max_zoom`, and inconsistent `--coverage` / `sources.lock` paths

- **Where:** [v1-map-pipeline-spec.md Section 2](../v1-map-pipeline-spec.md#L167-L200), [Phase 2](../v1-map-pipeline-spec.md#L365); vs [grid.py](../../../tools/map/src/seerah_map/grid.py#L306-L347), [base.py](../../../tools/map/src/seerah_map/base.py#L401-L440), [config.py](../../../tools/map/src/seerah_map/config.py#L66-L85), and [map-data/example/](../../../map-data/example/example.yaml).
- **Problem:**
  1. Throughout `tools/map/` (`profile.schema.json`, `config.py`, `grid.py`, `pack.py`, `base.py`, `tilestage.py`), `max_zoom` means **build zoom** (`12`). In `coverage.schema.json`, `max_zoom` means **MapLibre camera max zoom** (`14.0`) while `regional_max_zoom` means **build zoom** (`12`). Sharing `tools/map` stages across both config types with inverted field meanings invites zoom bugs.
  2. Line 190 updates `grid.check_base_coverage` to accept an explicit rectangular `data_extent`, overlooking `grid.natural_earth_max_zoom` (called on the immediately preceding line in `base.py` line 439), which also computes `_widen_camera(camera, margin_deg)`.
  3. Line 196 runs `fetch --coverage coverage` reading `/data/coverage.yaml` and `/data/sources.lock`, yet line 197 writes a missing lock to `/out/coverage.lock` (mismatching `sources.lock`!), and Phase 2 puts a synthetic production example in `map-data/example/` (where `example.yaml`, `edits.yaml`, and `example.lock` already live and `--data map-data` resolves paths relative to `map-data/`).
- **Fix:**
  1. Name camera zoom limits `camera_min_zoom` / `camera_max_zoom` in `coverage.schema.json` (only emitting `min_zoom` / `max_zoom` at the `map.json` boundary) or keep `build_max_zoom` unmistakable inside `tools/map/`.
  2. Update `check_data_extent_coverage`, `natural_earth_max_zoom`, and `check_base_coverage` together in `grid.py` to accept `data_extent: Bounds` directly.
  3. Resolve `--coverage <rel>` relative to `--data` identically to `--profile <rel>`, make `fetch` write `/out/sources.lock` (matching `/data/sources.lock`), and place the Phase 2 synthetic production fixture in a dedicated folder (e.g. `map-data/example-production/` or `tests/fixtures/`) rather than colliding with `map-data/example/`'s `example.lock` / `edits.yaml`.

#### M4. Dead `per-zoom` cleanup scope, `osgeo` coupling in `config.py`, and non-existent `validate` command in Phase 4

- **Where:** [v1-map-pipeline-spec.md Section 1](../v1-map-pipeline-spec.md#L138-L159), [Phase 1 & 4](../v1-map-pipeline-spec.md#L364-L367); vs [blockstage.py](../../../tools/map/src/seerah_map/blockstage.py#L279-L347), [config.py](../../../tools/map/src/seerah_map/config.py#L1-L23), [edits.py](../../../tools/map/src/seerah_map/edits.py#L25-L160), and [validate_content.py](../../../tools/validate_content.py#L1-L109).
- **Problem:**
  1. Line 138 wires cleanup into `_render_mask_block()` and `_render_coarse_block()` (`per-zoom` helpers), even though line 199 explicitly rejects `--mode per-zoom` with `--coverage`! Moreover, `_render_coarse_block()` warps `mask.vrt` rather than raw WBM, so it could not run `fill-water` (`wbm == 1`) without pointless rework.
  2. Line 159 puts `ogr.Geometry.IsValid()` / `Area() > 0` inside Python config loading (`load_cleanup()`), whereas `config.py` intentionally imports **no** `osgeo` / GDAL bindings. And Phase 4 (line 367) expects `validate / seerah_map config validation green on seerah-content`, overlooking that `tools/validate_content.py` checks only `content/` (not `map/cleanup.yaml`) and `tools/map` has no `validate` subcommand.
- **Fix:**
  1. Apply cleanup solely in `render_block()` via one helper shared with tests, and explicitly reuse `edits.py`'s existing `applicable()`, `touches()`, `_to_mercator()`, and `_footprint()` (avoiding duplicated OGR/GDAL logic).
  2. Keep `config.py` GDAL-free (schema + ring closure + finite numbers) and run `ogr.Geometry.IsValid()` / `Area() > 0` in `edits.py` (at build time or via a new explicit `seerah_map validate --coverage ...` command inside the container). Clarify how `seerah-content` CI validates `map/` files.

#### M5. Phase 2 Copernicus `INFO/` check assumes files `fetch.py` never downloads

- **Where:** [v1-map-pipeline-spec.md lines 82, 365, 379](../v1-map-pipeline-spec.md#L82-L379); vs [fetch.py](../../../tools/map/src/seerah_map/fetch.py#L261-L271) and [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md#L18).
- **Problem:** Phase 2 plans a check *"reading the licence file inside a real cached Copernicus cell's `INFO/` folder in `seerah-map-cache`"*. However, `fetch.py` lines 261–271 only download `{name}.tif` (`DEM`) and `AUXFILES/{wbm_name}` (`WBM`); `INFO/` is never fetched or cached.
- **Fix:** Either perform this as a documented one-off Phase 2 verification directly against the upstream Copernicus licence PDF / AWS S3 object (recording the result in docs), or explicitly add `INFO/` licence fetching if an automated check is truly desired.

#### M6. Phase 3 verifier hardening gaps (`zip` bomb / key count) and overstated Tink CI cross-check

- **Where:** [v1-map-pipeline-spec.md Section 3](../v1-map-pipeline-spec.md#L281-L331) and [Phase 3](../v1-map-pipeline-spec.md#L366); vs [.github/workflows/ci.yml](../../../.github/workflows/ci.yml#L11-L33), [requirements-dev.txt](../../../requirements-dev.txt#L1-L4), and [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md#L87).
- **Problem:**
  1. Phase 3 claims a *"Tink `NO_PREFIX` fixed test vector cross-check"* in `.github/workflows/ci.yml`, but `ci.yml` runs Python 3.12 only (no JDK/Android/Tink), and `:core:content`/`:core:update` are explicitly out of scope (line 48). Python tests alone can only check an RFC 8032 byte fixture, not real `tink-android` Kotlin verification.
  2. `verify` (lines 328–331) enforces `manifest.json <= 1 MiB` and `manifest.sig == 64` bytes, but does not state checking `info.compress_type == ZIP_STORED` and reading `zf.open(info).read(limit + 1)` (to guard against deflated zip bombs with spoofed header `file_size`), nor does it enforce `1 <= len(keys) <= 2` on `--public-keys <json_or_dir>` or settle whether `public_key_hex` vs `public_key_base64` (line 305) is canonical.
  3. Line 281 mixes unhashed `requirements-dev.txt` with hash-locked `tools/packages/requirements.txt` without specifying how `ci.yml` installs hash-locked `cryptography` wheels cleanly.
- **Fix:**
  1. Re-scope the Phase 3 test accurately as an RFC 8032 / Tink `NO_PREFIX` static test vector in Python, noting that live Python-to-Kotlin Tink verification ships with `:core:content`/`:core:update` (ADR 0007 line 87).
  2. Mandate `compress_type == ZIP_STORED` for map packages, bounded `read(limit + 1)` before `json.loads`, `1 <= len(keys) <= 2` in `verify`, and one canonical key encoding in public key JSON.
  3. Keep `tools/packages/requirements.txt` separate from `requirements-dev.txt` with its own CI install step, and note runtime-only test keypair generation in `tmp_path` so `.pre-commit-config.yaml`'s `detect-private-key` hook stays green.

---

### Nits

#### N1. Unresolved `manifest.schema.json` notes, inconsistent SHA regex, and unspecified `--created` default

- **Where:** [v1-map-pipeline-spec.md lines 287, 293, 297, 306](../v1-map-pipeline-spec.md#L287-L306).
- **Problem:** Line 287 contains an unresolved `"(or explicit forward-compatibility note ...)"` parenthetical; `source_commit` (line 293) accepts 40-char SHA-1 or 64-char SHA-256 while `tool_commit` (line 297) accepts only 40-char SHA-1; and `--created` (line 306) does not state its default (`datetime.now(UTC)`) or clarify that reproducibility requires pinning `--created`.
- **Fix:** Keep `"additionalProperties": false` in `manifest.schema.json` with a `$comment` noting Android clients ignore unknown fields per ADR 0008; use the same 40/64-hex git SHA regex for `source_commit` and `tool_commit`; document `--created`'s UTC default and determinism rule.

#### N2. `D1` phrasing ("restores flat 0 m land") and `D2` `source.kind` divergence from ADR 0009 Decision 2

- **Where:** [v1-map-pipeline-spec.md lines 29-30, 132, 156](../v1-map-pipeline-spec.md#L29-L156); vs [ADR 0009 Decision 2](../../adr/0009-map-data-pipeline.md#L124-L127).
- **Problem:** Describing `fill-water` as *"restores flat 0 m land"* blurs ADR 0009 Decision 2's strict line between **Cleanup** (*"Never adds shape"*) and **Reconstruction** (*"Adds or restores historical shape"*). Also, line 156 restricts `source.kind` to `["imagery", "dated-data", "atlas"]`, silently changing `content.schema.json`'s enum (`quran`, `hadith`, `seerah-book`, `atlas`, `shamail`, `commentary`) despite ADR 0009 saying edits reuse the content schema's source type.
- **Fix:** Phrase `fill-water` strictly as infilling modern excavated basins to flat `0.0 m` sea-level plain (zero added relief shape), and explicitly record the `source.kind` enum adjustment in D2 (referencing shared `$defs` or `$comment` provenance).

#### N3. Missing `AGENTS.md` DoD doc updates, leftover `min(side_margins)` option, and vague `ZIP_BUDGET_OVERHEAD` / `relief.pmtiles` allowlist

- **Where:** [v1-map-pipeline-spec.md lines 190, 203, 269, 365-366](../v1-map-pipeline-spec.md#L190-L366); vs [docs/README.md](../../README.md#L3) and [docs/engineering.md](../../engineering.md#L75).
- **Problem:** Phase 3 omits required documentation updates to `docs/README.md` (listing `docs/signing-runbook.md`), `content/README.md` (documenting `manifest.schema.json`), and `tools/packages/README.md`. Line 190 still lists `min(side_margins)` as an alternative even though rectangular `data_extent` checking is strictly better and 1 line simpler. Line 203 leaves `ZIP_BUDGET_OVERHEAD` unquantified, and line 269 leaves trial-only `variant`/`mode` keys ambiguous in production `relief.pmtiles` metadata.
- **Fix:** Add the missing doc deliverables to Phase 3; drop the `min(side_margins)` alternative; give `ZIP_BUDGET_OVERHEAD` a concrete constant (e.g. `1 MiB`); and state the exact production metadata key allowlist for `base.pmtiles` and `relief.pmtiles`.
