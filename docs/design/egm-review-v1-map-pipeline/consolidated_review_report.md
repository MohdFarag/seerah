# Consolidated Review Report: v1 Map Pipeline Spec

**Target:** [docs/design/v1-map-pipeline-spec.md](../v1-map-pipeline-spec.md)
**Spec checked against:** [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../../adr/0008-update-hosting-and-index.md), [ADR 0009](../../adr/0009-map-data-pipeline.md), [docs/open-decisions.md](../../open-decisions.md), [docs/design/map-trial-build.md](../map-trial-build.md), [docs/map-trial/step-4.md](../../map-trial/step-4.md), `tools/map/`, `android/core/map/`, `content/schema/`, `.github/workflows/`, `.pre-commit-config.yaml`, and `THIRD_PARTY_NOTICES.md`
**Inputs:**

- [zoom_out_report.md](zoom_out_report.md) (`Z-B1..Z-B3`, `Z-M1..Z-M6`, `Z-N1..Z-N3`)
- [comprehension_report.md](comprehension_report.md) (`C-1..C-12`)
- [critic_report.md](critic_report.md) (`D-B1..D-B7`, `D-M1..D-M6`, `D-N1..D-N2`)
- [codebase_details_report.md](codebase_details_report.md) (`CD-B1..CD-B6`, `CD-M1..CD-M9`, `CD-N1..CD-N11`)
- [implementation_readiness_report.md](implementation_readiness_report.md) (`R-B1..R-B7`, `R-M1..R-M5`, `R-N1..R-N5`)

## Reviewer Status

| Lens | Reviewer | Status | Raw Findings |
| --- | --- | --- | --- |
| Zoom-out architecture | `egm-zoom-out` | completed | `Z-B1..B3`, `Z-M1..M6`, `Z-N1..N3` |
| Cold-read comprehension | `egm-comprehension` | completed | `C-1..C-12` |
| Hostile design & security | `egm-design` | completed | `D-B1..B7`, `D-M1..M6`, `D-N1..N2` |
| Codebase details | `egm-codebase-details` | completed | `CD-B1..B6`, `CD-M1..M9`, `CD-N1..N11` |
| Implementation readiness | `egm-implementation-readiness` | completed | `R-B1..R-B7`, `R-M1..R-M5`, `R-N1..R-N5` |

## Summary

**Verdict: Needs revision before owner sign-off and Phase 1–4 implementation.** All five fresh-context reviewers affirmed the core architecture: applying discrete WBM + coincident elevation cleanup before `_land_mask_from_wbm()` in `tools/map/`, separating production `--coverage` builds from the ongoing trial `--profile` path, and keeping offline Ed25519 signing on the host in `tools/packages/`. However, deduplication across the 82 raw findings surfaced **10 Blockers (`B1–B10`)**, **8 Majors (`M1–M8`)**, and **2 Nits (`N1–N2`)** spanning self-defeating staging contracts (`pack.py` writing `metadata.json` and `"trial": False` vs. absent), a Tink multi-key `KeysetHandle` cryptographic binding flaw (`D-B1`), ZIP verification gaps, unspecified `report.json` / `map.schema.json` contracts, `--coverage` / `sources.lock` / zoom-naming clashes with `tools/map/`, an unexecutable `INFO/` cache check, and prose choices that fill open decisions (`open-decisions.md`).

**Branch-state note (`:core:map` `onTrimMemory` / prefetch):** Spec line 38 currently claims *"with `:core:map` memory trimming and prefetch tuning implemented in Step 1A."* On `main` (and on `docs/v1-map-pipeline-spec`, which branches off `main`), [SeerahMap.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/SeerahMap.kt#L142) still has `override fun onTrimMemory(level: Int) = Unit`, and [step-4.md](../../map-trial/step-4.md#L51) and [docs/map-trial/README.md](../../map-trial/README.md#L20) record `reduceMemoryUse()` wiring and tile-cache tuning as open follow-ups. Those `:core:map` `onTrimMemory` and prefetch changes currently live on unmerged local branch `feat/map-memory-trim-and-prefetch`, not on `main`. The spec must reflect `main`'s exact code state accurately.

## Reviewer Conflicts and Resolutions

| Topic | Reviewer Positions | Resolution |
| --- | --- | --- |
| **PMTiles inspection: `pmtiles==3.8.1` vs. custom `pmtiles_inspect.py`** | `D-B3`, `R-B5`, and `CD-M8` patch a custom `pmtiles_inspect.py` to hand-parse the 127-byte PMTiles v3 header, `internal_compression`, and `<= 1 MiB` metadata bounds (with `CD-M8` noting `pmtiles==3.8.1` is already pinned and suggesting a cross-check). `Z-M1` rejects hand-rolling a binary parser when `pmtiles==3.8.1` is already pinned in `tools/map/requirements.in` (BSD-3-Clause, zero transitive deps). | **Adopt `Z-M1` with `D-B3`/`R-B5` bounds hardening (`B4`).** Drop `pmtiles_inspect.py`; pin `pmtiles==3.8.1` in `tools/packages/requirements.in` / `requirements.txt` and wrap `pmtiles.reader.Reader` with a ~10-line seekable slice reader over `ZIP_STORED` zip offsets plus pre-read bounds checks (`json_metadata_bytes <= 1_048_576`, `offset + length <= member_size`) before calling `metadata()`. |
| **Provenance handoff: reusing `report.json` vs. unspecified `build-provenance.json`** | `C-1`, `CD-M2`, and `R-B1` note `build-provenance.json` has no schema and clashes with `report.py` (`_SHAPE`, non-null `peninsula_outline` in `size_by_zoom`). `D-B3` forbids `<staging>/report.json` if `build-provenance.json` exists. `Z-B1` points out `report.py` already writes and validates `report.json` (with `provenance`, `edits`, `tiles`, `size_by_zoom`), so `build-provenance.json` duplicates existing code. | **Adopt `Z-B1` + `R-B1` schema structure (`B3`).** Eliminate `build-provenance.json` and reuse `report.py` -> `<staging>/report.json` (outside `data/`), extended with `"purpose": "production"` (when built via `--coverage`) and nullable `size_by_zoom: list \| null` (set to `None` when `peninsula_outline` is omitted). `pack-map` reads `<staging>/report.json`, asserts `"purpose": "production"` and cross-file provenance equality, and still rejects `<staging>/metadata.json`. |
| **`min_data_margin_deg` and `widest_half_screen_dp` defaults in `coverage.yaml`** | `Z-B3` asks to remove both fields from `coverage.schema.json`, hardcoding `1.0°` in `tools/map` (bypassable via `--allow-zero-margin` for CI) and keeping `WIDEST_HALF_SCREEN_DP` in `grid.py`. `R-M5` warns that `1.0°` and `550.0 dp` are trial working placeholders (`open-decisions.md:9`, `grid.py:20`), so defaulting or hardcoding them fills open decisions. `D-B6` catches `base.py` ignoring `widest_half_screen_dp`. | **Adopt `R-M5` + `Z-B3` guard separation + `D-B6` wiring (`B6`, `D6`).** Remove the `1.0` and `550.0` schema defaults from `coverage.schema.json`. Keep `widest_half_screen_dp` as a **required, no-default** field in `coverage.yaml` and wire it through `base.py` (awaiting MatePad 11.5 measurement). Surface **D6** to the owner: choose between a spec-fixed production `min_data_margin_deg` constant in `tools/map` (`Z-B3` / ADR 0009 Decision 7) vs. a required positive field in `coverage.yaml` until finalized, while forbidding `0` inside production configs (`0` allowed only via explicit CLI `--allow-zero-margin` for synthetic CI). |
| **`tools/packages/` decomposition: 6 modules vs. 2–3 cohesive modules** | Spec plans 6 modules (`builder.py`, `signing.py`, `zipper.py`, `verify.py`, `pmtiles_inspect.py`, `cli.py`). `Z-M2` flags premature micro-modularization before the second caller (`content`) exists and notes duplicated `tools/map` PMTiles assertions. | **Adopt `Z-M2` (`M4`, `B4`).** Structure `tools/packages/src/seerah_packages/` as 3 cohesive modules plus `__init__.py` / `__main__.py`: `signing.py` (Ed25519 keygen, signing, strict single-key verify-before-parse), `archive.py` (shared `build_package` / `verify_package`, schema + G9 + `pmtiles.reader.Reader` checks, streaming ZIP writer/verifier), and `cli.py` (`keygen`, `pack-map`, `verify`). Limit map checks in `tools/packages/` to G9, `tile_schema: 1`, `map.schema.json`, and subset layer presence `{"land", "coast"} <= layer_ids`. |
| **Tink multi-key `KeysetHandle` vs. isolated single-key verifiers** | Spec L87 & L331 imply a multi-key Tink keyset (`current` + `next`) can verify raw bytes before parsing `manifest.json` and then assert `manifest["key_id"] == verifying_key_id`. `D-B1` proves `tink-android`'s multi-key `PublicKeyVerify` wrapper returns `void` and hides which key verified. | **Adopt `D-B1` (`B1`).** Mandate in both Python `tools/packages/` and the `:core:content` / `:core:update` verification contract that each trusted `(key_id, raw_public_key)` entry maps to its **own single-key `KeysetHandle` / `PublicKeyVerify`**. Verify `(manifest.sig, manifest_raw_bytes)` against each single-key verifier (`1 <= len(keys) <= 2`) before `json.loads()`, record `verified_key_id`, then parse JSON and assert `manifest["key_id"] == verified_key_id`. |
| **Per-zoom cleanup helper scope (`_render_coarse_block` & `_render_mask_block`)** | `CD-B2` proposes splitting `apply_cleanup` into WBM and elevation primitives so `_render_mask_block` and `_render_coarse_block` both run cleanup. `Z-M4`, `D-B5` (Option a), and `R-M3` observe `_render_coarse_block` has no `wbm_vrt`, production (`--coverage`) forbids `--mode per-zoom`, and shared helpers belong in `edits.py`. | **Adopt `Z-M4` / `D-B5` Option (a) / `R-M3` (`B8`).** Drop `_render_coarse_block()` from cleanup scope entirely (`--coverage` enforces `mode == "pyramid"`). Run cleanup solely in `render_block()` (plus optional `wbm`-only cleanup in `_render_mask_block()` if needed for tests), reusing `edits.py` geometry/rasterization primitives with a dedicated `CleanupEdit` dataclass (`reach_m = 0.0`). |
| **CI fixture folder (`map-data/example/` vs. dedicated `map-data/example-production/`)** | `D-B4` proposes placing `sources.lock` / `coverage.yaml` inside `map-data/example/` sharing `example.lock` cells. `Z-M3` and `C-2` warn `map-data/example/` already contains trial `example.yaml`, `edits.yaml`, and `example.lock` (`test_build_example.py` invariants). | **Adopt `Z-M3` + `D-B4` cell reuse (`B5`).** Put Phase 1 unit cleanup fixtures in `tools/map/tests/fixtures/`. Put Phase 2's synthetic production fixture in a dedicated `map-data/example-production/` directory (`coverage.yaml`, `cleanup.yaml`, `sources.lock` referencing the exact same cached Copernicus cell + Natural Earth files as `example.lock` so zero extra downloads occur), and update `.github/workflows/map.yml` `paths:` and cache key. |

## Owner Decisions vs. Uncontroversial Spec Fixes

### Existing Owner Decisions (`D1–D4`) — Required Clarifications Before Sign-Off

- **D1 (`remove-land` + `fill-water`):** Reword prose from *"restores flat 0 m land"* to *"infills modern excavated port basins (`wbm == 1`) to flat `0.0 m` sea-level plain with zero added relief shape"*, clarifying ADR 0009 Decision 2 (*"Never adds shape"*) applies to 3D relief/reconstructions and recording the narrow ADR 0009 Decision 3 table amendment (`Z-N2`, `C-7`). Also record a Phase 4 empirical check on KAEC / Jebel Ali WBM pixels: verify whether enclosed channels are tagged `wbm == 2` (lake) or `3` (river), and confirm whether `fill-water` zeros `elevation` across `wbm in (1, 2, 3)` or strictly `wbm == 1` (`D-M1`).
- **D2 (`cleanup.schema.json` separate from `trial-0`):** Align `D2` with **D5** below on `sources[].kind` and enforce strict `additionalProperties: false` + `minLength: 1` on `sources[]` (`CD-M9`, `R-M3`).
- **D3 (`coverage.yaml` + `--coverage` staging):** Replace `build-provenance.json` with extended `report.json` (`"purpose": "production"`), fix `--coverage` path resolution + `/out/sources.lock`, and separate `camera_min_zoom`/`camera_max_zoom` from `regional_max_zoom` (`Z-B1`, `Z-M3`, `B3`, `B5`, `B6`).
- **D4 (`tools/packages/` host packaging & Ed25519 signing):** Consolidate from 6 modules to 3 (`signing.py`, `archive.py`, `cli.py`), reuse `pmtiles==3.8.1`, enforce single-key Tink verifiers, and put `manifest.json` + `manifest.sig` first in the ZIP (`Z-B2`, `Z-M1`, `Z-M2`, `D-B1`).

### New Owner Decisions Surfaced by Review (`D5–D7`)

| ID | Topic | Recommended option | Alternative | Raw Source |
| --- | --- | --- | --- | --- |
| **D5** | Cleanup evidence `sources[].kind` enum | **Cleanup-specific `["imagery", "dated-data"]`:** matches `D2` summary table and ADR 0009 Decision 2 (*"Modern imagery or dated data"*), dropping `"atlas"` (reserved for Reconstruction) instead of matching `content.schema.json`'s text-source enum (`quran`, `hadith`, `seerah-book`, `atlas`, `shamail`, `commentary`). | Allow `"atlas"` alongside `["imagery", "dated-data"]` if a historical atlas is needed as negative evidence for removing a modern coastal change. | `Z-N2`, `C-7`, `D-M4` |
| **D6** | `min_data_margin_deg` enforcement & open `widest_half_screen_dp` default | **Remove schema defaults (`1.0`, `550.0`); forbid `0` margin in production `coverage.yaml`:** keep `widest_half_screen_dp > 0` required (no default) until MatePad 11.5 is measured; either fix the production `min_data_margin_deg` constant in `tools/map` (e.g., `1.0°` or owner-revised value per `step-4.md` size note) or require `min_data_margin_deg > 0` in `coverage.yaml`, allowing `0` *only* via CLI `--allow-zero-margin` for CI examples. | Keep `min_data_margin_deg: 1.0` and `widest_half_screen_dp: 550.0` as defaults in `coverage.schema.json` (conflicts with `open-decisions.md:9` and `grid.py:20` and lets config bypass ADR 0009 D7). | `Z-B3`, `R-M5`, `D-B6` |
| **D7** | `coverage.schema.json` `schema: 1` & `map.schema.json` `tile_schema: 1` scope | **Defer Option (b) / `valley` from `coverage.schema.json` `schema: 1` and remove N/E lock from `map.schema.json`:** keep `schema: 1` scoped to single-archive `relief.pmtiles` (relying on ADR 0009 D10 additive evolution if Option (b) or valleys return after Oppo A55/MatePad close); document optional `valley` and `relief-detail.pmtiles` in `tile_schema: 1`; keep N/E `0..180`/`0..90` limits in `coverage.schema.json` only while allowing general WebMercator WGS84 bounds in `map.schema.json`. | Wait to finalize `coverage.schema.json` until Oppo A55 & MatePad 11.5 close the trial, or include optional `detail_max_zoom` / `valley` fields in `schema: 1` now. | `R-M5`, `Z-B3`, `C-8` |

*(Note: `R-M5` also questioned `Uniform ZIP_STORED` inside shared `builder.py`; ADR 0007 Decision 5 already specifies `ZIP_STORED` for `map` while leaving `content` compressible, so parameterizing `compress_type` per `kind` in `archive.build_package` is an uncontroversial fix rather than a new owner decision.)*

### Uncontroversial Technical Fixes to Apply Directly to `docs/design/v1-map-pipeline-spec.md`

All remaining findings — `B1–B10`, `M1–M7`, `N1–N2`, plus the non-decision portions of `M8` (such as removing the speculative `{id, title_ar, footprint, sources}` object from `map.json` `reconstructed_areas` in favor of `"maxItems": 0` for v1) — are concrete bug fixes, missing interface specs, or factual corrections against the codebase and accepted ADRs. They should be written straight into `docs/design/v1-map-pipeline-spec.md` before presenting `D1–D7` for owner approval.

## Prioritized Findings

### 🔴 Blockers

#### B1. Multi-key Tink `NO_PREFIX` `KeysetHandle` breaks `key_id` binding under verify-before-parse

- **Raw findings:** `D-B1`, `Z-M6` (part 2), `C-4` (part 4), `R-M1` (part 3), `CD-N10`, `D-N2` (part 3)
- **Scope:** Spec Verified Fact #7 (L84–88), Section 3 `keygen` & `verify` (L305, L325–332)
- **Problem:** Spec L87 & L331 assume a multi-key Tink keyset (`current` + `next`) can verify `manifest.sig` before parsing JSON and then check `manifest["key_id"] == verifying_key_id`. In `tink-android`, `KeysetHandle.getPrimitive(PublicKeyVerify.class)` wraps all `NO_PREFIX` (`OutputPrefixType.RAW`) keys into `PublicKeyVerifyWrapper`, whose `verify(sig, data)` returns `void` without revealing *which* key verified. A multi-key `KeysetHandle` therefore prevents checking `manifest["key_id"]` without either allowing key confusion (key A signs a manifest claiming `key_id: B`) or parsing untrusted JSON before verification. Additionally, `--public-keys <json_or_dir>` has no defined file schema, key-count limit, or canonical key field (`public_key_hex` vs. `public_key_base64`).
- **Resolution / Required Spec Change (Technical fix):**
  1. Mandate in both `tools/packages/` and the `:core:content` / `:core:update` verification contract that each trusted `(key_id, raw_public_key_32B)` maps to **its own isolated single-key `KeysetHandle` / `PublicKeyVerify`** (never a multi-key `KeysetHandle`).
  2. Verify `(manifest_sig, manifest_raw_bytes)` sequentially across the single-key verifiers *before* `json.loads()`, capture `verified_key_id`, then parse JSON and assert `manifest["key_id"] == verified_key_id`.
  3. Define the `--public-keys` schema (single JSON key object or JSON array / directory of `*.json` files), enforce `1 <= len(keys) <= 2`, reject duplicate `key_id`s or duplicate public key bytes, reject `algorithm != "Ed25519"` or `tink_variant != "NO_PREFIX"`, and pick one canonical key field (`public_key_base64`, standard padded Base64 of raw 32 bytes; if `public_key_hex` is retained for human inspection, verify exact decoded equality on load).

#### B2. ZIP member ordering (`data/*` first), verification security gaps, and streaming OOM risk

- **Raw findings:** `Z-B2`, `D-B2`, `Z-M6` (part 2), `C-4` (part 4), `R-M1` (part 3)
- **Scope:** Spec Section 3 `pack-map` & `verify` (L319–334); [ADR 0009](../../adr/0009-map-data-pipeline.md#L253)
- **Problem:**
  1. Putting `sorted data/*` (~1.37 GiB) before `manifest.json` and `manifest.sig` (L322) defeats streaming verification/extraction (`ZipInputStream`, ADR 0009 L253) where a client must verify the signature, `version`, and `min_app_version` before touching the gigabyte payload.
  2. `verify` never checks `info.compress_type`, `info.flag_bits & 0x1` (encrypted entry), `zip.comment != b""`, Local File Header (LFH) vs. Central Directory (CD) consistency, `stat.S_IFMT(info.external_attr >> 16)` (non-regular files like FIFOs/devices/sockets/`S_IFDIR` without trailing `/`), DOS directory attribute (`external_attr & 0x10`), `.` / empty path segments (`data/./a`, `data//a`), NUL/control characters (`< 0x20`, `0x7f`), trailing `/`, or path-prefix collisions (`data/a` + `data/a/b`), leaving compression bombs and parser differentials open.
  3. `pack-map` (L319–324) does not mandate streaming 1 MiB chunked writes into the ZIP archive (`writestr` buffers multi-GiB entries in RAM).
- **Resolution / Required Spec Change (Technical fix):**
  1. Write ZIP members in order: `manifest.json`, `manifest.sig`, then lexicographically sorted `data/*`.
  2. In `verify` (and the Android extractor contract): reject `zip.comment != b""`, reject `flag_bits & 0x1` (encryption), reject any `compress_type != ZIP_STORED` on `manifest.json`, `manifest.sig`, and all `kind == "map"` members (allowing `ZIP_STORED`/`ZIP_DEFLATED` on future `content` `data/` entries), require `len(zf.infolist()) == len(unique_names)`, cross-check LFH vs. CD (`filename`, `compress_type`, sizes), enforce `S_IFMT(external_attr >> 16) in (0, S_IFREG)` and `(external_attr & 0x10) == 0` and `not info.is_dir()`, enforce printable ASCII prefix-free `data/` path segments without `.`/`..`/empty/`\x00`/backslashes/trailing `/`, and perform bounded reads (`f.read(MAX_MANIFEST_BYTES + 1)`, `f.read(65)`, and `entry["size"] + 1` cap on streamed payload checks).
  3. In `pack-map`, stream `data/` files in `1 MiB` chunks via `zf.open(zinfo, "w")`, locking deterministic `ZipInfo` attributes (`flag_bits`, `create_version`, `extract_version`, `internal_attr = 0`).

#### B3. G9 staging self-refusal (`pack.py` `metadata.json`), `"trial"` key policy contradiction, and `report.json` reuse vs. `build-provenance.json`

- **Raw findings:** `Z-B1`, `C-1`, `C-3` (part 1), `D-B3` (items 1–2), `CD-B3`, `CD-M2`, `CD-M4`, `R-B1`, `R-B5` (item 1)
- **Scope:** Verified Fact #4 (L69), D3 (L31), Architecture (L103–107), Section 1 (L137), Section 2 (L185, L202–208, L269), Section 3 (L308–314); [pack.py](../../../tools/map/src/seerah_map/pack.py#L83-L129), [report.py](../../../tools/map/src/seerah_map/report.py#L18-L84), [cli.py](../../../tools/map/src/seerah_map/cli.py#L229-L269)
- **Problem:**
  1. `pack.pack()` (`pack.py:127-128`) unconditionally writes `out_dir / "metadata.json"` beside `relief.pmtiles`, guaranteeing that `pack-map` (L308 forbidding `<staging>/metadata.json` and L311 allowing only `{base.pmtiles, relief.pmtiles, map.json}` in `data/`) rejects every production staging directory.
  2. L69 allows `"trial": False` or omitted, L269 forbids `"trial": true`, and L314 refuses `"trial"` if *"present at all"* — causing a Phase 2 `"trial": false` build to fail Phase 3 `pack-map`.
  3. `build-provenance.json` is introduced with no schema or example while duplicating [report.py](../../../tools/map/src/seerah_map/report.py#L18-L27)'s shape-checked `report.json`, and `cli.py:268` calls `pack.size_by_zoom(area.tiles_dir, profile.peninsula_outline)` unconditionally even though `coverage.yaml` makes `peninsula_outline` optional.
- **Resolution / Required Spec Change (Technical fix):**
  1. Parameterize `pack.pack(..., write_metadata_file: bool = True)` (`False` in `--coverage` production mode) so `metadata.json` is written only inside temporary `work.tiles_dir` for `disk_to_pmtiles` and never copied into `<staging>/` or `<staging>/data/`. Reject `metadata.json` anywhere under `<staging>/` in `pack-map`.
  2. Standardize strictly across `tools/map/` and `tools/packages/`: **omit `"trial"` completely** from production PMTiles metadata (`pack.py`, `base.py`), and reject `"trial" in meta` regardless of value in production verification (`pack.verify(..., trial=False)`, `base.verify_base(..., trial=False)`, `pack-map`, `verify`).
  3. Delete `build-provenance.json` from the spec and reuse [report.py](../../../tools/map/src/seerah_map/report.py) -> `<staging>/report.json` (staging root, outside `data/`). Extend `report._SHAPE` to support `"purpose": "production"` (or `"purpose"` optional/trial vs. required `"production"` under `--coverage`), allow `size_by_zoom` to be `None` when `peninsula_outline` is omitted (skipping `pack.size_by_zoom` in `cli.py`), record `edits: {"applied": ..., "skipped": ...}`, and document `report["provenance"]["data_commit"] -> manifest["source_commit"]`.

#### B4. Missing cross-artifact provenance binding and incomplete/unbounded PMTiles verification (resolved via `pmtiles==3.8.1`)

- **Raw findings:** `Z-M1`, `Z-M2` (part 2), `D-B3` (items 3–4), `C-1` (end), `C-4` (part 4), `CD-M8`, `R-B5` (items 2–3), `D-N1` (item 1), `Z-N3` (part 4)
- **Scope:** Section 2 (L269), Section 3 (L281, L313–316, L334, L366); [tools/map/requirements.in](../../../tools/map/requirements.in#L6), [pack.py](../../../tools/map/src/seerah_map/pack.py#L159-L186), [base.py](../../../tools/map/src/seerah_map/base.py#L392-L406)
- **Problem:**
  1. Hand-rolling `pmtiles_inspect.py` duplicates `pmtiles==3.8.1` (already pinned in `tools/map/requirements.in`, BSD-3-Clause, zero deps) and assumed gzip metadata without handling PMTiles v3 `internal_compression` (`NONE` vs `GZIP`), `1 MiB` decompression caps, or direct slice reading over `ZIP_STORED` entries (avoiding copying 1.4 GiB to temp disk).
  2. `pack-map` and `verify` never verify provenance equality (`fingerprint`, `tool_commit`, `data_commit`, `image`) across `report.json`, `base.pmtiles`, and `relief.pmtiles`; omit checking `base.pmtiles` `maxzoom`, `relief.pmtiles` `minzoom == 0`, `relief.pmtiles` `bounds ~= data_extent`, `tile_compression`, and `addressed_tiles_count > 0`; and assert **exact** layer equality `{"land", "coast"}` (L316), violating ADR 0009 Decision 10 (additive optional `"valley"` tolerance).
  3. Production PMTiles metadata keys `"name"` (would default to `"coverage"` via `path.stem`), `"variant"`, and `"mode"` are unspecified.
- **Resolution / Required Spec Change (Technical fix):**
  1. Drop `pmtiles_inspect.py`; pin `pmtiles==3.8.1` in `tools/packages/requirements.txt` and inspect PMTiles via `pmtiles.reader.Reader` over a seekable file / bounded `ZIP_STORED` offset slice reader, pre-checking `magic == b"PMTiles"`, `version == 3`, `json_metadata_offset >= 127`, `json_metadata_bytes <= 1_048_576`, and `offset + length <= member_size` before reading metadata.
  2. Set production PMTiles metadata `"name"` to constant `"seerah-map"`, omit `"variant"` and `"mode"`, embed `"attribution"` (`M6`), and state the exact required/allowed key set (`tile_schema: 1`, `name`, `attribution`, `fingerprint`, `tool_commit`, `data_commit`, `image`, plus `encoding` on relief and `vector_layers` on base).
  3. Let `tools/map/` retain domain build checks (`base.verify_base`, `pack.verify`), while `tools/packages/` checks G9 (`"trial" not in meta`), `"tile_schema" == 1`, `{"land", "coast"} <= layer_ids` (subset, tolerating `"valley"`), `base` `minzoom == 0`/`maxzoom == map.json.sources.base.max_zoom`/`tile_type == MVT`/`tile_compression == GZIP`, `relief` `minzoom == 0`/`maxzoom == map.json.sources.relief.max_zoom`/`bounds ~= data_extent` (`1e-6°`)/matching `tile_type`/`tile_compression == NONE`/`addressed_tiles_count > 0`, and cross-file provenance equality (`report.json` == `base.pmtiles` == `relief.pmtiles` == `manifest.json` in `pack-map` and `base.pmtiles` == `relief.pmtiles` in `verify`).

#### B5. `--coverage` / `sources.lock` path & filename clashes, read-only `/data`, `cli.py` argparse failure, missing `run.py` spec, and CI fixture collision

- **Raw findings:** `Z-M3` (part 3), `C-2`, `D-B4` (part 1), `CD-B6`, `CD-M3`, `R-B2`
- **Scope:** Architecture (L100–103), Section 2 (L167, L192–200), Phase 2 (L365); [cli.py](../../../tools/map/src/seerah_map/cli.py#L123-L163,L307-L321), [fetch.py](../../../tools/map/src/seerah_map/fetch.py#L40,L202-L208,L295), [tools/map/run.py](../../../tools/map/run.py#L142), [.github/workflows/map.yml](../../../.github/workflows/map.yml#L98)
- **Problem:**
  1. Spec L197 says `fetch --coverage coverage` *"verifies/writes `/data/sources.lock` (if missing, writes `/out/coverage.lock` and instructs owner to copy to `seerah-content/map/sources.lock`)"*. Container `/data` is mounted `:ro` (`run.py:142`), `cli.py` resolves `.with_suffix(".lock")` (`coverage.lock` instead of `sources.lock`), and `fetch.py:40` hardcodes `_LOCK_DESTINATION = "seerah-content/map/trial/"`.
  2. Lines 197–200 hardcode `/data/coverage.yaml`, `/data/cleanup.yaml`, and `/data/sources.lock`, while L167 and Phase 2 place a synthetic production example in `map-data/example/` (already holding trial `example.yaml`, `edits.yaml`, `example.lock`, and mounted under `/data/` in CI).
  3. `cli.py:315,319` marks `--variant` `required=True` and `--mode` `default=PYRAMID`, causing `argparse` to fail or misread `build --coverage ...` when spec L199 requires refusing `--variant` and `--mode`.
  4. `tools/map/run.py` is depicted as the launcher (L100) without specifying its updates (`--coverage` arg forwarding, `sources.lock` handling, clean checkout checks, `map-data/dist/` mount mapping).
- **Resolution / Required Spec Change (Technical fix):**
  1. Resolve `--coverage <rel>` identically to `--profile <rel>` (`<data>/<rel>.yaml`, rejecting `.yaml` suffix, leading `/`, and `..` segments), and resolve `cleanup.yaml` and `sources.lock` in `coverage_path.parent`.
  2. Parameterize `fetch()` (`lock_name`, `lock_destination`) so missing production locks write `/out/sources.lock` (never `/data/...` or `coverage.lock`) and print `cp /out/sources.lock seerah-content/map/sources.lock`.
  3. In `cli.py`, put `--profile` and `--coverage` in a `mutually_exclusive_group(required=True)` on `fetch` and `build`, change `--variant` to `required=False, default=None` and `--mode` to `default=None` (requiring `--variant` and defaulting `mode=PYRAMID` only for `--profile`; rejecting non-`None` `--variant`/`--mode` with exit code `2` under `--coverage`).
  4. Document `tools/map/run.py` behavior explicitly (mounts repo at `/src:ro`, data root at `/data:ro`, `map-data/dist` at `/out`, passes `SEERAH_TOOL_COMMIT` / `SEERAH_DATA_COMMIT` / `SEERAH_IMAGE_DIGEST` from clean checkouts into `--network none` builds).
  5. Place Phase 2's synthetic production example in `map-data/example-production/` (`coverage.yaml`, `cleanup.yaml`, `sources.lock` reusing `example.lock`'s exact cached cell + Natural Earth files so CI performs zero extra downloads), and add `map-data/schema/**` + `map-data/example-production/**` + `sources.lock` cache hashing to `.github/workflows/map.yml`.

#### B6. Zoom semantics collision (`max_zoom` vs `regional_max_zoom`), unadapted `grid.py`/`base.py` rectangular `Bounds` & `widest_half_screen_dp`, and single-zero-margin `fade_deg` leak

- **Raw findings:** `Z-M3` (parts 1–2), `C-5` (part 6 `Box`), `C-6` (parts 2–4), `D-B4` (part 2), `D-B6`, `CD-M1`, `R-B3` (stage adapter part), `R-B6`, `Z-N3` (part 2), `R-N2`
- **Scope:** Section 2 (L175–190); [grid.py](../../../tools/map/src/seerah_map/grid.py#L21,L306-L347), [base.py](../../../tools/map/src/seerah_map/base.py#L317,L401,L438-L440), [config.py](../../../tools/map/src/seerah_map/config.py#L66-L85), `blockstage.py`, `tilestage.py`, `pack.py`
- **Problem:**
  1. In `tools/map/`, `profile.max_zoom` is the integer **build zoom** (`<= 12`), whereas in `coverage.yaml` `max_zoom` is the float **MapLibre camera zoom** (`<= 22`, e.g., `14.0`) and `regional_max_zoom` is the build zoom. Passing `Coverage` into stage functions risks `expected_tiles()` crashes or tiling up to zoom 14–22.
  2. Spec L190 keeps a hedged `or` between `min(side_margins)` and a rectangular `Box` (class is actually `Bounds` in `config.py`/`grid.py`), ignores `grid.natural_earth_max_zoom` and `grid.check_data_extent_coverage` (which also use `_widen_camera(camera, margin_deg)`), and `base.py:317,439-440` hardcodes `WIDEST_HALF_SCREEN_DP` (`550.0`) and `TRIAL_MIN_MAPLIBRE_ZOOM` (`4`), ignoring `coverage.widest_half_screen_dp` and `math.floor(min_zoom)`.
  3. Spec L179 (`"if every side margin > 0, fade_deg <= min(side_margins)"`) evaluates to `False` when *one* side has `0` margin and others have `> 0`, permitting `fade_deg > 0` to fade terrain inside `camera_bounds` on the zero-margin edge. Also, if `min_zoom >= regional_max_zoom`, `range(floor(min_zoom), regional_max_zoom)` runs zero checks.
- **Resolution / Required Spec Change (Technical fix + `D6`):**
  1. Rename `min_zoom`/`max_zoom` in `coverage.yaml` to `camera_min_zoom` / `camera_max_zoom` (mapping to `min_zoom`/`max_zoom` only at the `map.json` output boundary), and define a stage `BuildTarget` / adapter dataclass passing `build_max_zoom = coverage.regional_max_zoom` to `fetch`, `blockstage`, `tilestage`, `pack`, and `base` (asserting `build_max_zoom <= BASE_ZOOM` in `run_block_stage`).
  2. Drop the `min(side_margins)` hedge and replace `Box` with `Bounds`. Generalize `grid.check_data_extent_coverage`, `grid.natural_earth_max_zoom`, and `grid.check_base_coverage` to accept `data_extent: Bounds`, `min_maplibre_zoom: int`, and `half_width_dp: float` (keeping `Profile` compatibility), and wire `coverage.widest_half_screen_dp` and `math.floor(camera_min_zoom)` into `base.py` (`_natural_earth_slices`, `natural_earth_max_zoom`, `check_base_coverage`). Validate `math.floor(camera_min_zoom) < regional_max_zoom` (or check `max(0, math.floor(camera_min_zoom))` against `ne_max`) and convert `ValueError` to `ConfigError` in `load_coverage()`.
  3. Enforce `fade_deg <= min(side_margins)` **unconditionally** (with `1e-9` float tolerance on IEEE-754 subtraction for `data_extent` vs `camera_bounds ± margin`), forcing `fade_deg == 0` whenever any side margin is `0`. Explain clearly that `base.pmtiles` spans `0..regional_max_zoom` (build zooms) and `check_base_coverage` checks MapLibre zooms `floor(camera_min_zoom)..regional_max_zoom - 1` because MapLibre zoom `regional_max_zoom - 1` displays build zoom `regional_max_zoom` (beyond which `base.pmtiles` tiles overzoom `regional_max_zoom` or `data_extent` already covers the zoomed-in viewport).

#### B7. Unspecified `map-data/schema/map.schema.json`, `coverage -> map.json` mapping, `tile_schema: 1` optional files, and Unicode escaping

- **Raw findings:** `R-B3` (schema part), `D-M4` (part 3), `C-6` (part 1), `C-8` (part 4), `CD-N4`, `R-N3`
- **Scope:** Verified Fact #8 (L90), Section 2 (L210–270)
- **Problem:** Only a single JSON example (`terrarium-value`) is given for `data/map.json`, leaving `map-data/schema/map.schema.json` unimplementable: it omits `painted-rgba` (`"tile_format": "webp"`), omits explicit `coverage.yaml -> map.json` mapping (`regional_max_zoom -> sources.{base,relief}.max_zoom`), leaves `additionalProperties` / `credits[]` requirements / `reconstructed_areas` schema constraints undefined, mixes MapLibre camera zooms and 256-px build zooms without clear labeling (`sources.base` also omits `tile_size`), omits `relief-detail.pmtiles` from the `tile_schema: 1` specification (unlike L257 and ADR 0009 Decision 9), and risks escaping `©` as `\u00a9` if serialized with `ensure_ascii=True`.
- **Resolution / Required Spec Change (Technical fix + `D7`):**
  1. Add a complete `map-data/schema/map.schema.json` specification table: `tile_schema: {const: 1}`, WGS84/WebMercator bounds for `camera_bounds` & `data_extent` (`D7`), `min_zoom`/`max_zoom` (explicitly documented as MapLibre camera zooms), `sources.base` (`file: const "base.pmtiles"`, `layers` including `"land"` and `"coast"`, `min_zoom: const 0`, `max_zoom: int 6..12`), `sources.relief` (`file: const "relief.pmtiles"`, `tile_size: const 256`, `min_zoom: const 0`, `max_zoom: int 6..12`, `if/then` locking `terrarium-value <-> png` and `painted-rgba <-> webp`), `credits` (`minItems: 2` requiring `copernicus-worlddem-30` with `liability` and `natural-earth`), `reconstructed_areas: {type: "array", maxItems: 0}` for v1, and `"additionalProperties": false` for the producer schema (with a `$comment` stating `tile_schema: 1` Android readers ignore unknown optional fields per ADR 0009 Decision 10).
  2. Explicitly document the `coverage.yaml -> map.json` field mapping (`camera_min_zoom`/`camera_max_zoom` -> `min_zoom`/`max_zoom`; `regional_max_zoom` -> `sources.{base,relief}.max_zoom`) and serialize `map.json` with `json.dumps(..., indent=2, sort_keys=True, ensure_ascii=False) + "\n"` so `©` is literal UTF-8.
  3. Document optional `valley` (`base.pmtiles` layer) and optional `relief-detail.pmtiles` (`sources["relief-detail"]`) together in the `tile_schema: 1` specification as absent in v1 and tolerated by `tile_schema: 1` readers; update Verified Fact #8 to reflect `StyleJson.kt:85-86` (`tileSize: 256` and `maxzoom` on both `Painted` and `Value`).

#### B8. `blockstage.py` per-zoom cleanup contradiction (`_render_coarse_block()` lacks `wbm_vrt`) and `CleanupEdit` / `osgeo` boundaries

- **Raw findings:** `D-B5`, `CD-B2`, `Z-M4` (parts 1–2), `R-M3` (part 2), `D-M4` (part 4), `CD-N5`
- **Scope:** Section 1 (L127, L138, L159); [blockstage.py](../../../tools/map/src/seerah_map/blockstage.py#L247-L348), [config.py](../../../tools/map/src/seerah_map/config.py#L1-L23,L133-L161,L233-L248), [edits.py](../../../tools/map/src/seerah_map/edits.py#L25-L160), [grid.py](../../../tools/map/src/seerah_map/grid.py#L180-L210)
- **Problem:**
  1. Spec L138 requires `render_block()`, `_render_mask_block()`, and `_render_coarse_block()` to share the same `(elevation, wbm)` cleanup helper before `fill_invalid()`. In `blockstage.py`, `_render_mask_block()` has no `dem_vrt`, and `_render_coarse_block()` warps blurred `mask_vrt` with **no `wbm_vrt`** — making `fill-water`'s `"where wbm == 1"` condition impossible there.
  2. `grid.border_px()` and `edits.touches()` read `Edit.feather_m` and `Edit.width_m`, which a `CleanupEdit` dataclass lacks.
  3. Spec L159 calls `ogr.Geometry.IsValid()` / `Area() > 0` inside Python config loading (`load_cleanup()`), pulling `osgeo`/GDAL bindings into `config.py` (which intentionally imports no `osgeo`), and calls `TransformTo(3857)` without checking WGS84/WebMercator bounds (`[-180, 180] × [-85.0511, 85.0511]`) or ring closure first.
- **Resolution / Required Spec Change (Technical fix):**
  1. Drop `_render_coarse_block()` from cleanup scope (`--coverage` enforces `mode == "pyramid"`, so coarse per-zoom rendering never runs in production). Apply `(elevation, wbm)` cleanup in `render_block()` (and optionally `wbm`-only cleanup in `_render_mask_block()` for mask tests).
  2. Define a separate `CleanupEdit` dataclass and `apply_cleanup(elevation, wbm, valid, cleanups, window)` in `edits.py`, sharing `applicable()`, `touches()` (parameterized with `reach_m=0.0` so `grid.border_px()` passes `edits=[]` or treats `CleanupEdit` reach as `0.0`), `_srs()`, `_to_mercator()`, and `_footprint()`.
  3. Keep `config.py` GDAL-free: `load_cleanup()` reuses `config._validate` + `config._check_finite` and validates finite numbers, ring closure (`ring[0] == ring[-1]`), and WebMercator WGS84 coordinate bounds (`[-180, 180]`, `[-85.0511287798066, 85.0511287798066]`) before GDAL runs; perform `ogr.Geometry.IsValid()` / `Area() > 0` validation in `edits.py` (during `build` / `seerah_map validate` inside the container). Note `load_coverage()` reuses `config._resolve_outline` for `peninsula_outline`.

#### B9. Phase 2 Copernicus `INFO/` licence gate assumes files `fetch.py` never downloads into `seerah-map-cache`

- **Raw findings:** `Z-M5`, `C-9`, `D-B7`, `CD-B5`, `R-B4`
- **Scope:** Verified Fact #6 (L82), Phase 2 Roadmap (L365), Known Risks (L379); [fetch.py](../../../tools/map/src/seerah_map/fetch.py#L261-L271)
- **Problem:** Phase 2 lists *"verified Copernicus licence string matches cached `INFO/` file"* inside `seerah-map-cache` as a mandatory pre-merge check (`L82, L365, L379`). However, `fetch.py:261-267` only downloads `{name}.tif` and `AUXFILES/{wbm_name}`, `example.lock` pins no `INFO/` files, and `build` runs with `--network none`. Any Phase 2 CI/merge test expecting `INFO/` inside `seerah-map-cache` will fail.
- **Resolution / Required Spec Change (Technical fix):** Remove the `INFO/` check from automated Phase 2 CI/merge checks. Replace it with a documented one-off Phase 2 pre-release verification gate fetched directly from upstream `copernicus-dem-30m` S3 / Copernicus portal (recording the verified `INFO/` file path, SHA-256, copyright years, and Article 6(c) wording in `THIRD_PARTY_NOTICES.md` and the `map.json` credits template before shipping `seerah-map-1.zip`).

#### B10. False statement on `:core:map` `onTrimMemory`/prefetch ("Step 1A"), overstated Step 4 status, and unowned `:core:map` `map.json` integration

- **Raw findings:** `CD-B1`, `C-5` (part 4 `"Step 1A"`), `C-8` (parts 1 & 2)
- **Scope:** Where This Fits & Explicit Non-Goals (L38–48), Section 2 (L212), Phased Roadmap (L367); [SeerahMap.kt](../../../android/core/map/src/main/kotlin/app/seerah/map/SeerahMap.kt#L136-L145), [step-4.md](../../map-trial/step-4.md#L11,L51), [docs/map-trial/README.md](../../map-trial/README.md#L12,L20)
- **Problem:**
  1. Spec L38 claims *"with `:core:map` memory trimming and prefetch tuning implemented in Step 1A."* In the codebase on `main` (from which `docs/v1-map-pipeline-spec` is branched), `SeerahMap.kt:142` is still `override fun onTrimMemory(level: Int) = Unit`, `step-4.md:51` and `docs/map-trial/README.md:20` state `reduceMemoryUse()` wiring and tile-cache tuning remain open, and no `"Step 1A"` exists in `docs/map-trial/`. (Those `:core:map` `onTrimMemory` and prefetch changes currently reside on unmerged local branch `feat/map-memory-trim-and-prefetch`, not on `main`.)
  2. Spec L47 (*"Step 2 and Step 4 validated `option-c` (`terrarium-value`) and `painted` fallback"*) obscures that **Oppo A55 (4 GB)** and **Huawei MatePad 11.5** remain unmeasured (`step-4.md:11`).
  3. Spec L212 says `data/map.json` is *"read by `:core:map`"*, while L48 Non-Goals only exclude `:core:content`, `:core:update`, and CDN publishing — leaving `:core:map`'s reading of `map.json` unowned in both Non-Goals and the Roadmap.
- **Resolution / Required Spec Change (Technical fix):**
  1. Rewrite L38 to accurately match `main`'s code state: state that Steps 1–4 are complete on the workstation and Galaxy A54 (`step-4.md`), while `:core:map` `onTrimMemory -> reduceMemoryUse()` wiring and tile-cache/prefetch tuning remain unmerged on `main` (pending merge/verification on the Oppo A55) and drop `"Step 1A"`.
  2. Clarify L47 to state that `option-c` and `painted` passed Galaxy A54 checks, Oppo A55 and MatePad 11.5 trial checks remain deferred before locking production `coverage.yaml`, and Option (b) is excluded here because ADR 0009 Decision 4 ties `relief-detail.pmtiles` exclusively to reconstruction edits.
  3. Explicitly place Android `:core:map` reading `data/map.json` in Non-Goals (as companion Android v1 work alongside `:core:content` / `:core:update`) and note in Phase 4 / post-Phase-4 handoff that creating `seerah-content/map/coverage.yaml` and `sources.lock` precedes rendering Style C cleanup verification images and building `seerah-map-1.zip`.

---

### 🟡 Major

#### M1. Build integrity & directory staging atomicity (`fingerprint()` omissions, unverified `/cache` in `build`, `source_vrts()` traversal, staging atomicity)

- **Raw findings:** `D-M5`, `CD-M5`, `R-B7` (part 2), `R-N1`, `C-5` (part 3)
- **Scope:** Section 2 (L185, L200–208); [cli.py](../../../tools/map/src/seerah_map/cli.py#L168-L192), [workarea.py](../../../tools/map/src/seerah_map/workarea.py#L65-L100,L166-L182), `blockstage.py:73`, `pack.py:195-201`
- **Problem:**
  1. `cli._cmd_build()` calls `check_lock_matches()`, checking only `(lat, lon)` tuples without verifying SHA-256 of cached DEM/WBM/NE files during `build`, and `blockstage.source_vrts()` (`blockstage.py:73`) builds paths under `cache` without `base._natural_earth_sources()`'s confinement check.
  2. `workarea.fingerprint()` omits `"production"` domain tag, `data_commit`, and `peninsula_outline` bytes (when present), allowing `/out/map-staging-<fp8>/` and `/work/<fp>/` collisions. Spec L201 also misstates that `fingerprint()` hashes `tool_commit` *and* the source tree simultaneously (`workarea.py:95` hashes `code_hash(src_root)` *only* when `tool_commit is None`).
  3. `cli.py` writes directly into `out_dir`, `workarea.atomic_write` works only on single files (not directories or across `/work` and `/out` mounts), and `peninsula_outline` is validated after the 40-minute block/tile build.
- **Resolution / Required Spec Change (Technical fix):**
  1. Confine all `sources.lock` relative paths (`dem`, `wbm`, `natural_earth`) inside `cache` in `load_lock()` / `source_vrts()`, and verify SHA-256 of cached source files on production `--coverage` builds before the block stage.
  2. Define `<fp8>` as the first 8 hex chars of the SHA-256 `workarea.fingerprint()`, matching `workarea.py` (`tool_commit` if set else `code_hash(src_root)`), and extend production fingerprints with a `"production"` domain tag, `data_commit`, and `peninsula_outline` file bytes when configured.
  3. Validate `peninsula_outline` GeoJSON geometry upfront in `load_coverage()`. Stage outputs inside `args.out / f".tmp-map-staging-{fp8}-{os.getpid()}"`, verify `< 2 GiB`, and replace into `args.out / f"map-staging-{fp8}"` on the same filesystem (`/out`) only on full success (cleaning `.tmp-*` on failure).

#### M2. Coastal cleanup & Natural Earth geometry hazards (overlap ordering, `wbm == 2/3` basins, halos, silent no-ops, `MakeValid()` order, coast cuts, `block_zoom=6`)

- **Raw findings:** `D-M1`, `D-M6` (part 2), `D-N1` (parts 2–3)
- **Scope:** Section 1 (L129–139, L153–159), Section 2 (L178), Section 4 (L348–355); [cli.py](../../../tools/map/src/seerah_map/cli.py#L230), [base.py](../../../tools/map/src/seerah_map/base.py#L343-L364)
- **Problem:**
  1. Applying `edits` strictly in file order means placing `remove-land` before `fill-water` on overlapping polygons causes `fill-water` (seeing `wbm == 1`) to revert removed breakwaters back to land.
  2. Enclosed dredged basins (KAEC, Jebel Ali) can be tagged `wbm == 2` (lake) or `3` (river) or hold non-ocean quay pixels; unfeathered `0.0 m` cuts beside `8–15 m` coastal bluffs can also produce dark `blur_px = 7.0` hillshade step halos.
  3. A `[lat, lon]` coordinate swap passes `IsValid()` / `Area() > 0`, hits zero blocks, leaves the artifact uncleaned, and is still logged under `edits.applied` (`cli.py:230`).
  4. `base.py:363` calls `MakeValid()` *after* `gdal vector layer-algebra --operation=erase`; `wbm_coast_6.geojsonl` splits coast lines at `z9` block edges without `--detect-shared-borders` on `z6..z11`; and `coverage.schema.json` allows `block_zoom: 6` (>10 GiB float64 arrays per worker, guaranteed OOM on 8 GiB hosts).
- **Resolution / Required Spec Change (Technical fix):**
  1. Reject overlapping `remove-land` and `fill-water` approved geometries in `edits.py` (or enforce `fill-water` before `remove-land`) and reject any `approved` cleanup edit whose envelope does not intersect `coverage.data_extent` (`ConfigError`), tracking actual block hits for `edits.applied`.
  2. Audit KAEC/Jebel Ali `wbm` classes and coastal-bluff halos during Phase 4 visual renders before owner approval (`D1`).
  3. Repair clipped Natural Earth geometries (`MakeValid`) before `erase` (or verify against `data_extent` during Phase 2/4), visually verify `z6..z11` block-seam coast lines with cross-block cleanup polygons, and constrain production `block_zoom` to `>= 8` (documenting `9` as standard).

#### M3. JSON Schema & loader validation traps (`manifest.schema.json`, `cleanup.schema.json`, `FormatChecker`, `--created` default)

- **Raw findings:** `D-M4` (parts 1, 2, 4), `C-3` (part 2), `C-4` (part 2), `CD-B4`, `CD-M9` (parts 1–4), `CD-N8`, `R-M2`, `R-M3` (part 1), `Z-N1`
- **Scope:** Section 1 (L145–160), Section 3 (L287–298, L306, L317); [config.py](../../../tools/map/src/seerah_map/config.py#L136-L138,L315-L345), [content.schema.json](../../../content/schema/content.schema.json#L42-L52)
- **Problem:**
  1. `manifest.schema.json` L298 sets `image` to `^sha256:[0-9a-f]{64}$`, which rejects `local:sha256:...` (`tools/map/run.py:100`) during `pack-map --allow-local-provenance` schema validation (L317); `tool_commit` accepts 40-char hex only while `source_commit` accepts 40 or 64; L287 leaves `additionalProperties` unresolved; `"kind": "content"` should explicitly forbid `tool_commit`/`image`; and `--created` lacks a UTC default / reproducibility note.
  2. `jsonschema==4.26.0` ignores `"format": "date-time"` and `"format": "uri"` unless `format_checker` (`rfc3339-validator` / `rfc3987`, absent from `requirements.txt`) or explicit patterns/Python checks are used. JSON Schema `uniqueItems` also cannot enforce uniqueness of `files[].path` or sorted order.
  3. `cleanup.schema.json` omits coordinate bounds, ring-closure checks, non-whitespace `reason`, strict `additionalProperties: false` + `minLength: 1` on `sources[]`, and whether `draft` permits `sources: []` (plus `config.load_edits` omits `_check_finite`).
- **Resolution / Required Spec Change (Technical fix):**
  1. In `manifest.schema.json`: set `"additionalProperties": false` (with `$comment` documenting Android client forward compatibility per ADR 0008), match `^(?:[0-9a-f]{40}|[0-9a-f]{64})$` for both `source_commit` and `tool_commit`, match `^(?:sha256:[0-9a-f]{64}|local:[A-Za-z0-9:._-]+)$` for `image` (while `pack-map` and `verify` enforce `image.startswith("sha256:")` strictly unless `--allow-local-provenance` is passed), add `not: required: [tool_commit, image]` when `kind == "content"`, regex-check `created` (`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$`) + `datetime.strptime` calendar validation in Python, enforce `files` sorted/unique/prefix-free in Python, and default `--created` to `datetime.now(UTC)` (noting deterministic builds pin `--created`).
  2. In `cleanup.schema.json`: enforce WGS84/WebMercator bounds, `minLength: 1` + non-whitespace `pattern` on `reason`, `additionalProperties: false` and `minLength: 1` on `sources[]` (`work`, `locator`, optional `http(s)` URI pattern on `url`), clarify `sources` on unapproved items may be omitted or `[]` (required `minItems: 1` on `approved`), and check `_check_finite()`, unique `id`s, and `ring[0] == ring[-1]` in `load_cleanup()`.

#### M4. Over-decomposed `tools/packages/` layout (6 modules -> 3) and incomplete CLI contracts

- **Raw findings:** `Z-M2` (part 1), `C-4` (parts 3–4), `D-M2` (parts 1–2), `CD-M6` (part 2), `CD-N9`, `R-M1` (parts 1, 2, 4)
- **Scope:** Section 3 (L275–335), Phase 3 (L366)
- **Problem:** Splitting `tools/packages/src/seerah_packages/` into 6 modules (`builder.py`, `signing.py`, `zipper.py`, `verify.py`, `pmtiles_inspect.py`, `cli.py`) before the second caller exists is premature micro-modularization (`Z-M2`). Meanwhile, the CLI contracts lack: `keygen` output filenames and `O_EXCL` overwrite refusal; `--key-id` regex validation; `--passphrase-fd` on `pack-map`; `--allow-local-provenance` and `--expected-kind {content,map}` on `verify` (cross-`kind` replay protection); refusing to overwrite existing `dist/seerah-map-<version>.zip` (ADR 0008 Decision 11 immutability) + optional `--previous-manifest` monotonicity check; deriving the public key from `private_key.public_key()` for `pack-map` self-verify; exit codes (`0`/`1`/`2`); and `build_package(...)` signature.
- **Resolution / Required Spec Change (Technical fix):**
  1. Collapse `tools/packages/src/seerah_packages/` into 3 cohesive modules: `signing.py`, `archive.py` (exposing `build_package(...)` and `verify_package(...)`), and `cli.py` (plus `__init__.py` and `__main__.py`).
  2. Specify `keygen` writes `<out-dir>/<key_id>.key.pem` via `os.open(..., O_WRONLY | O_CREAT | O_EXCL, 0o600)` and `<out-dir>/<key_id>.pub.json` (refusing overwrites and validating `--key-id`), add `--passphrase-fd` to `pack-map`, add `--expected-kind` and `--allow-local-provenance` to `verify`, refuse overwriting an existing `<out>/seerah-map-<version>.zip` (unless `--force` for dev/tests), support `--previous-manifest` monotonicity guard, self-verify with `private_key.public_key()`, and document exit codes (`0` success, `1` verification/I/O/packaging error, `2` CLI usage error).

#### M5. `< 2 GiB` ceiling math (`ZIP_BUDGET_OVERHEAD`, `ZIP64_LIMIT`), `manifest.json <= 1 MiB` content limit, `compress_type` per `kind`, and canonical JSON

- **Raw findings:** `D-M3`, `Z-N3` (part 3), `C-4` (part 1), `C-5` (part 6), `CD-M6` (part 1), `R-B7` (part 1), `R-M5` (part 3), `R-N5`
- **Scope:** Section 2 (L203), Section 3 (L295, L317, L320–328, L335), Known Risks (L378)
- **Problem:** `ZIP_BUDGET_OVERHEAD` is undefined, Python's `zipfile.ZIP64_LIMIT` (`2_147_483_647` = `2 GiB - 1`) and streaming Data Descriptors (16–24 B/entry) are unaddressed (plus ADR 0009's `~3×` ~4.1–6.0 GiB device update space during install), `manifest.json <= 1 MiB` caps future `content` packages at ~7,200 files, L317 hedges `"sort_keys=True or fixed schema key order"`, and L320 forces `ZIP_STORED` even on shared `builder.py` (`content` JSONs).
- **Resolution / Required Spec Change (Technical fix):**
  1. Define `MAX_RELEASE_FILE_BYTES = 2_147_483_647` (`ZIP64_LIMIT`, strictly `< 2 GiB`) and `ZIP_BUDGET_OVERHEAD = 1_048_576` (`1 MiB`) in `tools/map/`, and enforce `final_zip_bytes <= MAX_RELEASE_FILE_BYTES` in `tools/packages/`. Note the ADR 0009 `~3×` device free-space impact under Known Risks.
  2. Lock canonical manifest JSON to `json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=False).encode("utf-8") + b"\n"`.
  3. Set `MAX_MANIFEST_BYTES` to `1_048_576` (`1 MiB`) for `kind == "map"` and `4_194_304` (`4 MiB`) for `kind == "content"` (or per-kind limit), and parameterize `compress_type` per `kind` in `archive.build_package(...)` (`ZIP_STORED` mandatory for `map`, configurable `ZIP_DEFLATED`/`ZIP_STORED` for `content` per ADR 0007 Decision 5).

#### M6. Missing PMTiles `"attribution"`, stale `THIRD_PARTY_NOTICES.md`, leaked-key `version` floor runbook gaps, and `detect-private-key` hook

- **Raw findings:** `D-M6` (part 1), `D-M2` (part 3), `CD-N11`, `Z-M6` (part 3), `D-N2` (part 4)
- **Scope:** Section 2 (L237–249, L269), Section 3 (L339), Phase 2–3 (L365–366); [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md#L18), [.pre-commit-config.yaml](../../../.pre-commit-config.yaml#L14)
- **Problem:**
  1. Derived `base.pmtiles` and `relief.pmtiles` distributed publicly inside `seerah-map-<version>.zip` omit `"attribution"` metadata, and [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md#L18) still carries a truncated Copernicus notice missing Airbus copyright, EU/ESA attribution, `"all rights reserved"`, and the Article 6(c) liability sentence (and Phase 3 omits adding `cryptography` / `tools/packages/` notices).
  2. `docs/signing-runbook.md` (L339) covers clearing the ADR 0008 `serial` floor after a key leak, but misses ADR 0007's installed package **`version` floor** (`manifest.schema.json` also has no `maximum` on `version`, allowing a leaked key to sign `version = 999999999` and permanently lock out honest `version = 4` updates after key rotation unless the dropped key's installed package is also invalidated/reset).
  3. `.pre-commit-config.yaml:14` runs `detect-private-key`, which rejects literal PEM private-key headers in committed test fixtures.
- **Resolution / Required Spec Change (Technical fix):**
  1. Embed Copernicus + Natural Earth `"attribution"` directly in `base.pmtiles` and `relief.pmtiles` JSON metadata, and update `THIRD_PARTY_NOTICES.md` with verbatim Copernicus notices (Phase 2) and `tools/packages/` dependencies (Phase 3).
  2. Cap `manifest.schema.json` `version` (e.g., `maximum: 1_000_000`), and document in `docs/signing-runbook.md` that app builds dropping a compromised `key_id` must discard/re-verify any installed package signed by that `key_id` and reset its version floor alongside the ADR 0008 serial floor.
  3. Specify in Phase 3 that unit tests generate ephemeral Ed25519 keys in `tmp_path` (`Ed25519PrivateKey.generate()` / raw 32-byte seeds) rather than committing PEM files.

#### M7. Missing CI wiring, `tools/packages/` repo scaffolding, DoD doc updates, and non-concrete Phase 1–4 verification specs

- **Raw findings:** `CD-M7`, `R-M4`, `Z-M4` (part 2 end), `Z-M6` (parts 1 & 3), `C-8` (parts 2–3), `C-12` (parts 3–4), `Z-N3` (part 1), `R-N4`
- **Scope:** Section 3 (L281), Section 4 (L356), Phased Roadmap (L362–368); [.github/workflows/ci.yml](../../../.github/workflows/ci.yml#L20-L33), [requirements-dev.txt](../../../requirements-dev.txt#L1-L4), [.github/dependabot.yml](../../../.github/dependabot.yml#L17-L34)
- **Problem:**
  1. Spec mixes unhashed `requirements-dev.txt` with hash-locked `tools/packages/requirements.txt` (`pip install --require-hashes` rejects unhashed pins), omits `tools/packages/{requirements.in,pyproject.toml,README.md,__main__.py}`, omits `.github/dependabot.yml` (`/tools/packages`), and omits Phase 2/3 DoD updates to `docs/README.md`, `content/README.md`, `docs/roadmap.md`, `CHANGELOG.md`, and `REUSE.toml`. L113 (`dist/`) vs. L103 (`map-data/dist/`) also leaves the output directory path ambiguous.
  2. Verification checks in Phases 1–4 lack concrete mechanics: Phase 1 fixture path (`tools/map/tests/fixtures/`), Phase 2 `< 2 GiB` failure & `painted-rgba` tests + excluding `report.json` (which contains wall-clock timings/peak memory) from byte-identity checks, Phase 3 `MAX_PACKAGE_BYTES` monkeypatch test + `verify-before-parse` mock assertion + pure-Python minimal PMTiles v3 fixture in `ci.yml` + clarifying Python-only RFC 8032 / Tink `NO_PREFIX` static vector test (since `ci.yml` has no Android/Tink JDK runtime and live Kotlin verification belongs to `:core:content`/`:core:update` per ADR 0007 L87), and Phase 4 referencing a non-existent `validate` command (blurring `tools/validate_content.py` with `seerah_map`) and unspecified PNG crop render workflow.
- **Resolution / Required Spec Change (Technical fix):**
  1. Add `tools/packages/{requirements.in,pyproject.toml,README.md,src/seerah_packages/__main__.py}`, keep `tools/packages/requirements.txt` separate from `requirements-dev.txt` with its own CI test job in `.github/workflows/ci.yml`, add `/tools/packages` (`pip`) to `.github/dependabot.yml`, clarify output paths (`map-data/dist/` vs `./dist/`), and list all `AGENTS.md` DoD documentation updates across Phases 2–3.
  2. Make every Phase 1–4 check concrete (including adding `seerah_map validate --coverage <rel>` inside the container + documenting the Phase 4 scratch render check, excluding `report.json` from the Phase 2 byte-identity diff, testing `< 2 GiB` via monkeypatched limit, asserting `json.loads` is not called on bad signatures, and framing Phase 3's Tink test accurately as an RFC 8032 / `NO_PREFIX` byte-vector test committed for later Kotlin `:core:content` cross-testing).

#### M8. Hidden decisions in prose outside `D1–D4` and ADR 0009 / `open-decisions.md` alignment

- **Raw findings:** `Z-B3`, `Z-N2`, `C-7`, `CD-M9` (part 5), `R-M5` (parts 1, 2, 4)
- **Scope:** Owner Decisions (L23–33), Section 1 (L132, L156), Section 2 (L173–183, L257–258), Section 3 (L320, L335)
- **Problem:**
  1. Line 258 prescribes `{id, title_ar, footprint, sources: [...]}` for future `map.json` `reconstructed_areas` (even using `title_ar` vs. `content.schema.json:71` `title`), foreclosing an explicit open decision ([open-decisions.md:10–11](../../open-decisions.md#L10-L11) and ADR 0009 Not in scope L225–226).
  2. `D1`, `D2`, `coverage.schema.json` defaults (`1.0°`, `550.0 dp`), `coverage.schema.json` exclusion of Option (b)/`valley`, and `map.schema.json` N/E quadrant restriction either diverge from ADR 0009 or embed unapproved choices in prose.
- **Resolution / Required Spec Change:**
  1. **Technical fix:** Remove the speculative `{id, title_ar, footprint, sources}` schema from L258; constrain v1 `map.schema.json` `reconstructed_areas` strictly to `{"type": "array", "maxItems": 0}` and leave future item fields open until reconstruction design begins. Also update `D1` wording (`Z-N2`, `C-7`).
  2. **Owner decisions:** Surface `sources[].kind` (`D5`), `min_data_margin_deg` / `widest_half_screen_dp` (`D6`), and `coverage.schema.json` `schema: 1` Option (b)/valley deferral & `map.schema.json` WGS84 bounds (`D7`) in the Owner Decisions table (`D1–D7`).

---

### 🟢 Nits

#### N1. Ungrounded / misattributed references, colliding acronyms, and rough prose

- **Raw findings:** `C-5` (parts 1, 2, 3 `<fp8>`, 5 `N/E` vs `NE`, 6 `_render_coarse_block`/`edit_reach_m`), `C-11`, `C-12` (parts 1–2), `D-N2` (part 2)
- **Scope:** Throughout doc (L9, L20, L66, L72, L101, L111, L173–174, L264, L280, L339, L354)
- **Problem & Fix:**
  1. Replace `"ADR 0009 G9"` / unlinked `"G9"` (7 occurrences) with `[map-trial-build.md Goal 9](../map-trial-build.md#L77)`.
  2. Replace `"ADR 0008 decision 12"` (L339) with `[ADR 0008](../../adr/0008-update-hosting-and-index.md#L118)` (Consequences / Decision 5 — ADR 0008 has decisions 1–11 only).
  3. Update `"ADR 0007 decision 3"` (L20, L280) to cite `ADR 0009 Decision 8` (which amended `ADR 0007 Decision 3` to split `tools/map/` while keeping shared manifest code in `tools/`).
  4. Expand `EGM` (L9), `ASRY` (L354), and `N/E` (Northern/Eastern hemisphere) vs. `NE` (Natural Earth) on first use; link `Style C` (L66, L72); repeat `shrunk by 2 px at zoom 12` for Natural Earth `coast` on L264 so it doesn't read as clipped differently from `land` (L263); and remove stray references to `_render_coarse_block()` and `edit_reach_m`.

#### N2. `Verified Codebase` and `tile_schema: 1` factual wording corrections

- **Raw findings:** `C-10`, `CD-N1`, `CD-N2`, `CD-N3`, `CD-N4`, `CD-N6`, `CD-N7`, `D-N2` (part 1)
- **Scope:** Verified Facts #1, #4, #8 (L56–58, L69, L90), Section 2 (L263, L269)
- **Problem & Fix:**
  1. L56: note `MASK_SIGMA_PX = 4.0` is defined in `grid.py:18` and imported by `mask.py:10`.
  2. L57: clarify `render_block()` writes `work.mask_dir / f"{block.id}.tif"`, and `write_mosaics()` (`blockstage.py:364-379`, called at L539) builds `mask.vrt`.
  3. L58: clarify `base.py:244` extracts `coast` at `z6..max_zoom` from `work.path / "mask.vrt"` clipped to `block.window` (for 1-px neighbor context), whereas `land` reads `work.mask_dir / f"{block.id}.tif"`.
  4. L69: fix garbled `"assert meta.get('trial') is not True failure as an error"` -> `"reject meta.get('trial') is not True as an error"` (matching `pack.verify()` and `base.verify_base()`).
  5. L90: note `StyleJson.kt:85-86` sets `"tileSize": 256` and `"maxzoom": spec.maxZoom` on both `Painted` (`raster`) and `Value` (`raster-dem`).
  6. L263: clarify `land` inside `data_extent` comes from the `>= 128` threshold of the `MASK_SIGMA_PX = 4.0` smoothed land mask (not raw `WBM != 1`), matching `coast` on L264.
  7. L269: distinguish PMTiles v3 127-byte binary header fields (`min_zoom`, `max_zoom`, `tile_type`, `tile_compression`, `internal_compression`, `bounds`) from JSON metadata fields (`tile_schema`, `encoding`, `vector_layers`, `fingerprint`, `tool_commit`, `data_commit`, `image`, `attribution`, `name`, plus transient `format`/`minzoom`/`maxzoom`/`bounds` for `disk_to_pmtiles`).

## Traceability Matrix

Every finding across all five review reports is accounted for below (`82/82` raw entries).

| Raw ID | Summary | Consolidated Target |
| --- | --- | --- |
| `Z-B1` | Staging `metadata.json` collision, `"trial"` key, `report.json` vs `build-provenance.json` | `B3` |
| `Z-B2` | ZIP order puts payload ahead of `manifest.json` / `manifest.sig` | `B2` |
| `Z-B3` | Speculative `reconstructed_areas` item shape; `min_data_margin_deg` / `widest_half_screen_dp` in `coverage.yaml` | `M8`, `D6`, `D7` |
| `Z-M1` | Reuse `pmtiles==3.8.1` instead of custom `pmtiles_inspect.py` | `B4` |
| `Z-M2` | Over-decomposed 6-file `tools/packages/`; duplicated assertions & exact `{"land", "coast"}` equality | `M4`, `B4` |
| `Z-M3` | `max_zoom` collision, missed `natural_earth_max_zoom`, `/out/coverage.lock`, `map-data/example/` collision | `B5`, `B6` |
| `Z-M4` | Dead `per-zoom` cleanup scope, `osgeo` in `config.py`, reuse `edits.py`, non-existent `validate` command | `B8`, `M7` |
| `Z-M5` | Phase 2 Copernicus `INFO/` check assumes unfetched files | `B9` |
| `Z-M6` | Python-only Tink test scope; `ZIP_STORED` & bounded read; `1..2` pubkeys; `requirements.txt` vs `requirements-dev.txt`; `detect-private-key` | `B1`, `B2`, `M6`, `M7` |
| `Z-N1` | `manifest.schema.json` `$comment`, `tool_commit` 40/64 hex, `--created` UTC default | `M3` |
| `Z-N2` | `D1` `"restores flat 0 m land"` wording & `D2` `sources[].kind` `"atlas"` divergence | `M8`, `D1`, `D5` |
| `Z-N3` | Phase 3 DoD docs; drop `min(side_margins)` option; `ZIP_BUDGET_OVERHEAD`; PMTiles metadata allowlist | `B4`, `B6`, `M5`, `M7` |
| `C-1` | `build-provenance.json` unspecified, `data_commit` vs `source_commit`, PMTiles cross-check | `B3`, `B4` |
| `C-2` | Missing `run.py` spec, read-only `/data`, `--coverage` path/lock mismatch, `map-data/example/` collision | `B5` |
| `C-3` | PMTiles `"trial"` `false` vs absent; `manifest.schema.json` `image` regex vs `--allow-local-provenance` | `B3`, `M3` |
| `C-4` | Canonical JSON rule; `additionalProperties`; `--passphrase-fd` & `keygen`; `--public-keys`; `base`/`relief` zooms; `ZIP_STORED` | `B1`, `B2`, `B4`, `M3`, `M4`, `M5` |
| `C-5` | Undefined/misattributed `G9`, `ADR 0008 decision 12`, `<fp8>`, `Step 1A`, `N/E` vs `NE`, `ZIP_BUDGET_OVERHEAD`, `Box`, `_render_coarse_block`, `edit_reach_m` | `B6`, `B10`, `M1`, `M5`, `N1` |
| `C-6` | Unlabeled zoom systems in `map.json`, `base.pmtiles` zoom mapping, `min_zoom < regional_max_zoom`, `check_base_coverage` hedge, `fade_deg` guard | `B6`, `B7` |
| `C-7` | `D2` `sources[].kind` `"atlas"` & `D1` `fill-water` vs ADR 0009 Decisions 2–3 | `M8`, `D1`, `D5` |
| `C-8` | Overstated Step 4 status (Oppo A55/MatePad open); missing `coverage.yaml` phase & `:core:map` `map.json` ownership; Tink test timing; `relief-detail` omission | `B7`, `B10`, `M7` |
| `C-9` | Phase 2 Copernicus `INFO/` gate unfetchable | `B9` |
| `C-10` | Garbled L69 `"assert meta.get('trial') is not True failure as an error"` | `N2` |
| `C-11` | `ADR 0007 decision 3` vs `ADR 0009 decision 8` attribution for `tools/packages/` | `N1` |
| `C-12` | Unexpanded `EGM`/`ASRY`, unlinked `Style C`, `coast` 2-px wording, venv & `dist/` ambiguity, `validate` blurring | `M7`, `N1` |
| `D-B1` | Multi-key Tink `NO_PREFIX` `KeysetHandle` breaks `key_id` binding under verify-before-parse | `B1` |
| `D-B2` | ZIP verification security gaps (compression bombs, LFH vs CD, `S_IFMT`/DOS dir/path prefix checks, streaming OOM) | `B2` |
| `D-B3` | `pack.py` `metadata.json` self-refusal, `"trial": False` ambiguity, missing cross-artifact binding & PMTiles bounds/`internal_compression` checks | `B3`, `B4` |
| `D-B4` | `coverage.yaml` / `sources.lock` path clash & `map.yml` cache key; `regional_max_zoom` vs `max_zoom` collision | `B5`, `B6` |
| `D-B5` | `_render_coarse_block()` lacks `wbm_vrt` & runs edits post-`fill_invalid` | `B8` |
| `D-B6` | Single-zero-margin `fade_deg` leak; `base.py` ignores `widest_half_screen_dp`; empty zoom check range | `B6` |
| `D-B7` | Phase 2 `INFO/` licence check cannot run in CI or `seerah-map-cache` | `B9` |
| `D-M1` | Cleanup overlap order footgun, `wbm == 2/3` port basins, relief step halos, silent no-op `"applied"` edits | `M2`, `D1` |
| `D-M2` | Cross-`kind` replay, `O_EXCL` key guard, `--passphrase-fd`, mutable `dist/` overwrites, leaked-key `version` lockout | `M4`, `M6` |
| `D-M3` | `ZIP_BUDGET_OVERHEAD`, `ZIP64_LIMIT`, Data Descriptors, 3× storage, `manifest.json <= 1 MiB` content limit, canonical JSON, deterministic `ZipInfo` | `M5` |
| `D-M4` | `manifest.schema.json` `local:`/`FormatChecker`/`not: required`; `map.schema.json` `encoding`/`credits`; OGR `3857` pole crash | `B7`, `B8`, `M3`, `D5` |
| `D-M5` | `build` skips cache SHA-256 check, `source_vrts()` traversal, `fingerprint()` omissions, non-atomic staging dir, late `peninsula_outline` | `M1` |
| `D-M6` | PMTiles missing `"attribution"`, stale `THIRD_PARTY_NOTICES.md`, Phase 3 notices; NE `erase` before `MakeValid()` | `M2`, `M6` |
| `D-N1` | PMTiles `"name"`/`"variant"`/`"mode"`, `tippecanoe` block-cut coast on `z6..z11`, `block_zoom=6` RAM trap | `B4`, `M2` |
| `D-N2` | `base.py:244` `coast` from `mask.vrt`; `ADR 0008 decision 12`; canonical pubkey encoding; `detect-private-key` | `B1`, `M6`, `N1`, `N2` |
| `CD-B1` | False statement: `:core:map` `onTrimMemory` / prefetch tuning ("Step 1A") not on `main` | `B10` |
| `CD-B2` | `blockstage.py` per-zoom helpers cannot share single `(elevation, wbm)` cleanup helper | `B8` |
| `CD-B3` | `pack.pack()` writes `metadata.json` triggering G9 self-refusal | `B3` |
| `CD-B4` | `manifest.schema.json` `image` regex contradicts `pack-map --allow-local-provenance` | `M3` |
| `CD-B5` | Non-existent Copernicus `INFO/` files in `seerah-map-cache` | `B9` |
| `CD-B6` | `cli.py` `--variant` (`required=True`) and `--mode` (`default=PYRAMID`) break `--coverage` | `B5` |
| `CD-M1` | `Box` vs `Bounds`, `natural_earth_max_zoom` & `check_data_extent_coverage`, `base.py` constants, `max_zoom` collision & stage adapter | `B6` |
| `CD-M2` | `build-provenance.json` unspecified vs `report.py` & `size_by_zoom` crash when `peninsula_outline` is `None` | `B3` |
| `CD-M3` | `sources.lock` vs `coverage.lock`, read-only `/data`, `fetch.py` `_LOCK_DESTINATION` mismatch | `B5` |
| `CD-M4` | Inconsistent `"trial"` key policy in production PMTiles metadata | `B3` |
| `CD-M5` | Inaccurate `workarea.fingerprint()` description and unimplemented directory atomic staging | `M1` |
| `CD-M6` | Undefined `ZIP_BUDGET_OVERHEAD` and missing `--passphrase-fd` on `pack-map` | `M4`, `M5` |
| `CD-M7` | Missing `map.yml` paths/cache key, `ci.yml` vs `requirements-dev.txt`, `tools/packages/` scaffolding, `dependabot.yml`, `docs/README.md` | `M7` |
| `CD-M8` | `verify` / PMTiles checks: `base` `maxzoom`, `relief` `minzoom`/`bounds`, `map.json <= 1 MiB`, `internal_compression`, streaming reads, `pmtiles==3.8.1` | `B4` |
| `CD-M9` | `FormatChecker` disabled, `cleanup` `$defs.source` strictness & `_check_finite`, `files` sort/uniqueness, `additionalProperties: false`, open `reconstructed_areas` fields | `M3`, `M8`, `D7` |
| `CD-N1` | `MASK_SIGMA_PX = 4.0` defined in `grid.py:18` (not `mask.py`) | `N2` |
| `CD-N2` | `write_mosaics()` builds `mask.vrt` (not `render_block()`) | `N2` |
| `CD-N3` | Garbled L69 `"assert meta.get('trial') is not True failure as an error"` | `N2` |
| `CD-N4` | `StyleJson.kt:85-86` `tileSize: 256` applies to both `Painted` and `Value` | `N2` |
| `CD-N5` | Reusable helpers in `config.py` and `edits.py` not explicitly cited | `B8` |
| `CD-N6` | `tile_schema: 1` `land` layer omits smoothed mask `>= 128` threshold | `N2` |
| `CD-N7` | PMTiles v3 binary header vs JSON metadata terminology | `N2` |
| `CD-N8` | `tool_commit` vs `source_commit` hash length inconsistency | `M3` |
| `CD-N9` | `keygen` output filenames, overwrite guard, `--key-id` regex validation | `M4` |
| `CD-N10` | `--public-keys <json_or_dir>` format, `1..2` limit, duplicate check | `B1` |
| `CD-N11` | Pre-commit `detect-private-key` constraint on Phase 3 test fixtures | `M6` |
| `R-B1` | `build-provenance.json` unspecified, `pack.py` `metadata.json` self-refusal, `size_by_zoom` crash | `B3` |
| `R-B2` | `sources.lock` vs `/out/coverage.lock`, `--coverage` nesting, `cli.py`/`fetch.py`/`run.py` mismatch | `B5` |
| `R-B3` | `map-data/schema/map.schema.json` unspecified, `coverage -> map.json` mapping, stage `build_max_zoom` adapter | `B6`, `B7` |
| `R-B4` | Phase 2 `INFO/` Copernicus licence check cannot run as specified | `B9` |
| `R-B5` | Ambiguous `"trial"` metadata policy, missing PMTiles keys/headers/`internal_compression`/seekable `BinaryIO`/`1 MiB` cap | `B3`, `B4` |
| `R-B6` | Hedged rectangular coverage check, missing `widest_half_screen_dp` / `floor(min_zoom)` wiring in `base.py`, `ConfigError` | `B6` |
| `R-B7` | Undefined `ZIP_BUDGET_OVERHEAD` and cross-mount directory staging atomicity | `M1`, `M5` |
| `R-M1` | `tools/packages/` CLI contracts (`keygen` files, `--passphrase-fd`, `--public-keys`, `--allow-local-provenance`, streaming reads/writes, exit codes, `build_package`, `pyproject.toml`) | `B1`, `B2`, `M4` |
| `R-M2` | `manifest.schema.json` regex/format traps (`created`, `url`, `image` vs `--allow-local-provenance`, `additionalProperties`, `files` uniqueness) | `M3` |
| `R-M3` | `cleanup.schema.json` coordinate bounds/ring closure/strictness; `_render_coarse_block` & `CleanupEdit` vs `Edit` attributes | `B8`, `M3` |
| `R-M4` | Non-concrete Phase 1–4 test specs (fixtures, `< 2 GiB` failure, excluding `report.json` from determinism, `verify-before-parse`, CI PMTiles fixture, `validate`, PNG crops) | `M7` |
| `R-M5` | Hidden decisions in prose (`1.0`/`550.0` defaults, Option (b)/valleys, `ZIP_STORED` in `builder.py`, N/E in `map.schema.json`) | `M5`, `M8`, `D6`, `D7` |
| `R-N1` | `fingerprint()` `SRC_ROOT` condition and `peninsula_outline` bytes | `M1` |
| `R-N2` | `fade_deg` inequality phrasing & `1e-9` float tolerance on `data_extent` margins | `B6` |
| `R-N3` | `data/map.json` `ensure_ascii=False` for literal `©` | `B7` |
| `R-N4` | Phase 2/3 DoD docs (`content/README.md`, `docs/roadmap.md`, `CHANGELOG.md`, `REUSE.toml`) | `M7` |
| `R-N5` | Canonical `manifest.json` serialization (`indent=2, sort_keys=True, ensure_ascii=False`) | `M5` |
