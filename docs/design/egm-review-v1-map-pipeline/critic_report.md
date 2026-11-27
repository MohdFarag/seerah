# Hostile Design & Security Audit: `docs/design/v1-map-pipeline-spec.md`

**Target:** `docs/design/v1-map-pipeline-spec.md`
**Reviewer:** `egm-design`

---

Audited against ADRs 0007/0008/0009, `docs/open-decisions.md`, `tools/map/src/seerah_map/*`, `android/core/map/*`, `THIRD_PARTY_NOTICES.md`, `.github/workflows/map.yml`, and `.pre-commit-config.yaml`.

## Summary Triage

| ID | Severity | Area | Title |
| --- | --- | --- | --- |
| **B1** | **Blocker** | Crypto / Tink | Multi-key Tink `NO_PREFIX` `KeysetHandle` makes `key_id` binding impossible without verify-after-parse |
| **B2** | **Blocker** | ZIP Security | Missing `ZIP_STORED` / encryption enforcement, LFH-vs-CD checks, `S_IFMT` / DOS dir / path-prefix checks, and bounded reads |
| **B3** | **Blocker** | G9 / Packaging | `pack.py` writes `metadata.json` into `out_dir` (self-refusal), `"trial": False` ambiguity, and missing cross-artifact / PMTiles header binding |
| **B4** | **Blocker** | Config / Zooms | `coverage.yaml` / `sources.lock` path resolution clash and `regional_max_zoom` vs camera `max_zoom` collision |
| **B5** | **Blocker** | Coastal Cleanup | `_render_coarse_block()` lacks `wbm_vrt` and applies edits post-`fill_invalid`, contradicting §1 helper reuse |
| **B6** | **Blocker** | Coverage / NE | `fade_deg` guard fails under single-side `0` margin; `base.py` hardcodes `550.0` instead of `widest_half_screen_dp` |
| **B7** | **Blocker** | Copernicus Legal | Phase 2 `INFO/` licence check cannot run: `fetch.py` never downloads `INFO/` into `seerah-map-cache` |
| **M1** | **Major** | Coastal Cleanup | `remove-land` + `fill-water` overlap ordering footgun, `wbm == 2/3` basin elevation spikes, slope-halo step, and no-op `"applied"` edits |
| **M2** | **Major** | Signing / ADR 0008 | Cross-`kind` replay in `verify`, key overwrite/passphrase gaps, mutable `dist/` overwrites, and leaked-key `version` floor lockout |
| **M3** | **Major** | 2 GiB / Evolution | Undefined `ZIP_BUDGET_OVERHEAD`, `Zip64` / Data Descriptor bounds, `manifest.json <= 1 MiB` content limit, and 3× device update storage |
| **M4** | **Major** | Schemas | `manifest.schema.json` `local:` contradiction, `FormatChecker` disabled in `config.py`, `map.schema.json` evolution traps, and OGR `3857` domain bugs |
| **M5** | **Major** | Build Integrity | `build` skips cache SHA-256 checks; `source_vrts()` lacks path confinement; `fingerprint()` omits `data_commit`; non-atomic staging dir |
| **M6** | **Major** | Legal / NE | Derived `base.pmtiles`/`relief.pmtiles` omit `"attribution"`; `THIRD_PARTY_NOTICES.md` stale; NE `erase` runs before `MakeValid()` |
| **N1** | **Nit** | PMTiles / Coast | Production PMTiles `"name"` defaults to `"coverage"`, `tippecanoe` block-cut coast simplification, and `block_zoom=6` RAM trap |
| **N2** | **Nit** | Doc / CI Accuracy | Minor spec inaccuracies (`base.py` coast source, ADR 0008 "decision 12", dual pubkey encodings, pre-commit `detect-private-key`) |

---

## Blockers

### [Blocker] B1 — Multi-key Tink `NO_PREFIX` `KeysetHandle` breaks `key_id` binding under verify-before-parse

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §Verified Facts #7 (L84–88), §3 `verify` (L330–332); ADR 0007 D9.
- **Failure Scenario:** Spec L87 & L331 claim Android / Python can ship `current` + `next` in a Tink keyset, verify `manifest.sig` before parsing JSON, and then assert `manifest["key_id"] == verifying_key_id`. In `tink-android`, `KeysetHandle.getPrimitive(PublicKeyVerify.class)` wraps all `NO_PREFIX` (`OutputPrefixType.RAW`) keys into `PublicKeyVerifyWrapper`, whose `verify(sig, data)` returns **`void`** (throwing only if *all* keys fail) without revealing **which** key verified the signature. If `current` and `next` sit in one Tink `KeysetHandle`, the app cannot check `manifest["key_id"] == verifying_key_id`, leaving key-confusion open (key A signs a manifest claiming `key_id: B`), or forcing developers to parse `manifest.json` before `verify()` (violating verify-before-parse).
- **Fix:** Mandate in both the Python verifier and `:core:content` / `:core:update` contract that each trusted `(key_id, raw_public_key)` entry MUST map to **its own single-key `KeysetHandle` / `PublicKeyVerify` instance** (never a multi-key `KeysetHandle`). Iterate single-key verifiers on raw `(manifest_sig, manifest_raw_bytes)` *before* `json.loads()`, capture `verified_key_id`, then parse JSON and assert `manifest["key_id"] == verified_key_id`. Reject `--public-keys` containing `> 2` keys or duplicate `key_id`s.

### [Blocker] B2 — ZIP verification gaps: compression bombs, parser differentials, mode bits, and unbounded writes

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §3 `pack-map` & `verify` (L319–334).
- **Failure Scenarios:**
  1. **ZIP bomb / compressed-entry confusion:** `verify` never checks `info.compress_type` or `info.flag_bits`. An attacker can set `compress_type = ZIP_DEFLATED` with `file_size = 100` in the Central Directory (CD) while hiding a 10 GiB deflate bomb in the payload; calling `zip.read("manifest.json")` or unbounded streaming exhausts RAM/CPU before size is checked. Also `flag_bits & 0x1` (encrypted entry) is unchecked.
  2. **LFH-vs-CD parser differential:** Python `zipfile.ZipFile` looks up entries from the CD (using the *last* duplicate in `NameToInfo`), while sequential Java `ZipInputStream` reads Local File Headers (LFH) and `ZipFile` reads CD. If LFH filename/size differs from CD, or trailing garbage / non-empty `zip.comment` exists, two parsers disagree.
  3. **Disguised special files & path-validation holes:** Checking only `S_ISLNK` in `external_attr >> 16` misses FIFOs (`S_ISFIFO`), devices, sockets, and `S_IFDIR` entries without trailing `/` (`ZipInfo.is_dir()` only checks `filename.endswith('/')`), plus DOS directory flags (`external_attr & 0x10`). Line 327 also omits NUL bytes (`\x00`), control chars (`< 0x20`, `0x7f`), `.` segments (`data/./map.json`), empty segments (`data//map.json`), trailing `/` on files, and path-prefix collisions (`data/a` + `data/a/b`, crashing extraction with `ENOTDIR`/`EISDIR`).
  4. **OOM on `pack-map`:** Lines 319–324 do not require streaming writes for `1.37–2.0 GiB` `data/` entries (`writestr` / `read_bytes` will blow memory on 4–8 GiB hosts).
- **Fix:**
  1. In `verify` (and Android extraction spec): reject `flag_bits & 0x1` (encryption), reject any `compress_type != ZIP_STORED` on `manifest.json`, `manifest.sig`, and all `map` entries (and for future `content` `data/` entries allow only `ZIP_STORED`/`ZIP_DEFLATED` bounded strictly by `manifest_entry["size"]` bytes read, aborting immediately at `expected_size + 1`).
  2. Perform bounded reads on metadata: `raw_manifest = f.read(MAX_MANIFEST_BYTES + 1)` (fail if `> 1 MiB`), `raw_sig = f.read(65)` (fail if `!= 64`), and verify `info.file_size == info.compress_size == len(raw)` when `ZIP_STORED`.
  3. Require `len(infolist) == len(unique_names)`, reject `zip.comment != b""`, validate every `ZipInfo` LFH header matches CD (`filename`, `compress_type`, sizes), require `stat.S_IFMT(info.external_attr >> 16) in (0, stat.S_IFREG)` and `(info.external_attr & 0x10) == 0` and `not info.is_dir()`, enforce ASCII/printable segment validation (no `.`, `..`, empty segments, `\x00`, control chars, backslashes), and assert all `manifest["files"]` paths are prefix-free.
  4. In `pack-map`: stream `data/` files in `1 MiB` chunks into `zf.open(zinfo, "w")` (pre-computing `file_size`/`CRC` or fixing deterministic `flag_bits`, `create_version`, `extract_version`, `internal_attr`).

### [Blocker] B3 — G9 staging self-refusal (`metadata.json` written by `pack.py`), `"trial": False` ambiguity, and missing cross-artifact binding

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §Verified Facts #4 (L69), §2 (L204–208, L269), §3 (L307–316); `tools/map/src/seerah_map/pack.py` (L127–128, L159–185); `tools/map/src/seerah_map/base.py` (L392–406).
- **Failure Scenarios:**
  1. **`pack.pack()` triggers G9 refusal:** `pack.py` L127–128 unconditionally copies `metadata.json` into `out_dir`, while spec L308 requires `pack-map` to refuse staging directories containing `<staging>/metadata.json`. Reusing `pack.pack()` in production causes `pack-map` to reject every production staging directory.
  2. **`"trial"` key contradiction:** Facts #4 (L69) suggests `"False / omitted"`, §2 L269 says *"must not contain `"trial": true`"*, and §3 L314 says *"Refuse if `"trial"` key exists and is truthy (or present at all)"*. If `tools/map` writes `"trial": False` and `pack-map` forbids `"trial"` presence, every production build fails G9.
  3. **Unchecked staging tampering / mixed-build PMTiles:** `pack-map` validates `build-provenance.json`, `map.json`, `base.pmtiles`, and `relief.pmtiles` individually, without asserting that `base.pmtiles` and `relief.pmtiles` metadata (`fingerprint`, `tool_commit`, `data_commit`, `image`) match `build-provenance.json`! Nor does L316 verify `base.pmtiles` `maxzoom == map.json.sources.base.max_zoom`, `relief.pmtiles` `minzoom == 0`, `relief.pmtiles` `bounds == map.json.data_extent`, or `tile_compression` headers.
  4. **PMTiles header parser trap:** `pmtiles_inspect.py` (L313) assumes gzip metadata without checking PMTiles v3 byte 97 (`internal_compression`) or bounding `json_metadata_bytes` (`<= 1 MiB`) and bounds checks against file length before decompressing (metadata gzip bomb / `zlib.error`).
- **Fix:**
  1. Parameterize `pack.pack(..., copy_metadata: bool = True)` (False in `--coverage` builds) and also forbid `<staging>/report.json` in `pack-map`.
  2. Standardize strictly: production PMTiles metadata and `build-provenance.json` **omit `"trial"` completely**; both `tools/map` production verification and `pack-map` reject `"trial" in meta`.
  3. In `pack-map`, assert exact equality of `(fingerprint, tool_commit, data_commit, image)` across `build-provenance.json`, `base.pmtiles`, and `relief.pmtiles`; verify `base.pmtiles` `maxzoom` & `tile_compression == gzip`, `relief.pmtiles` `minzoom == 0`, `bounds ~= data_extent` (`1e-6°`), `tile_type` (`PNG`/`WEBP`), `tile_compression == none`, and `addressed_tiles_count > 0`.
  4. In `pmtiles_inspect.py`, validate `magic == b"PMTiles"`, `version == 3`, `json_metadata_offset >= 127`, `json_metadata_bytes <= 1_048_576`, `offset + length <= file_size`, handle `internal_compression` (`1` raw, `2` bounded `zlib` decompression `<= 1 MiB`), and raise `PackError` on any violation.

### [Blocker] B4 — `coverage.yaml` / `sources.lock` path clash and `regional_max_zoom` vs `max_zoom` type/range collision

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §2 (L167, L175–177, L196–200), §Phased Roadmap (L365); `cli.py` (L123–160); `fetch.py` (L40, L295); `base.py` (L253–254, L401); `tilestage.py` (L151, L160); `pack.py` (L80, L176); `.github/workflows/map.yml` (L98).
- **Failure Scenarios:**
  1. **Path & filename clash:** Spec L197 & L200 hardcode `/data/coverage.yaml`, `/data/cleanup.yaml`, and `/data/sources.lock`, while `fetch` writes `/out/coverage.lock` (wrong filename!) for copying to `seerah-content/map/sources.lock`. Meanwhile CI mounts `map-data/` at `/data` and puts the production synthetic example under `map-data/example/` (`--coverage example/coverage` -> `/data/example/coverage.yaml`, whose sibling lock is `/data/example/sources.lock`, NOT `/data/sources.lock`, and whose `.github/workflows/map.yml` L98 cache key only hashes `map-data/example/example.lock`).
  2. **`max_zoom` semantic collision:** In `Profile`, `max_zoom` is the integer build zoom (`<= 12`). In `coverage.yaml`, `max_zoom` is the float MapLibre camera max zoom (`<= 22`, e.g., `14.0`), and `regional_max_zoom` is the build zoom (`6..12`). Passing a `Coverage` object into `tilestage.py` (`expected_tiles`), `base.py` (`_tile` & `verify_base`), `pack.py` (`metadata` & `verify`), or `grid.py` (`natural_earth_max_zoom` & `check_base_coverage`) without explicitly separating `build_max_zoom = regional_max_zoom` from `camera_max_zoom = max_zoom` either crashes `expected_tiles()` (`ValueError: max_zoom 14 > 12`) or tiles Natural Earth/relief up to zoom 14–22!
- **Fix:**
  1. Resolve `--coverage <rel>` identically to `--profile <rel>` (`<data>/<rel>.yaml`), and resolve `cleanup.yaml` and `sources.lock` in `coverage_path.parent` (writing `/out/sources.lock` with accurate copy instructions). Keep CI synthetic production example either sharing `example.lock` cells (and update `map.yml` cache key to hash `sources.lock` too).
  2. Introduce an explicit build adapter/parameter `build_max_zoom: int` (`coverage.regional_max_zoom`) across `tilestage`, `pack`, `base`, and `grid` (`natural_earth_max_zoom`, `check_base_coverage`), never aliasing `coverage.max_zoom` to `Profile.max_zoom`. Also validate in `blockstage.run_block_stage` that `build_max_zoom <= BASE_ZOOM` for both `pyramid` and `per-zoom`.

### [Blocker] B5 — `_render_coarse_block()` lacks `wbm_vrt` and applies edits post-`fill_invalid`, contradicting §1 helper reuse

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §1 (L127, L132, L138); `tools/map/src/seerah_map/blockstage.py` (L231, L281–285, L300–347, L439–479).
- **Failure Scenario:** Spec L138 mandates that `render_block()`, `_render_mask_block()`, and `_render_coarse_block()` *"share the same helper so production and any test modes stay consistent"*, applied *before* `mask.fill_invalid()` (L127). In `blockstage.py`:
  1. `_render_mask_block(block, profile, wbm_vrt, work)` passes `edits=[]` (L302) and never loads `dem_vrt`.
  2. `_render_coarse_block(block, profile, edits, variant, dem_vrt, mask_vrt, out_path)` does **not** receive `wbm_vrt` (`vrts[1]`), only `dem_vrt` + blurred zoom-12 `mask_vrt` (where `fill-water` pixels are already merged with `wbm == 0/2/3` land as `255`). Thus `_render_coarse_block` **cannot** evaluate `fill-water`'s `"where wbm == 1"` condition on `elevation`, and runs `edits.apply` inside `_shade_and_write_style` (L231) *after* `fill_invalid` (L326).
  3. At coarse zooms (`z6..z10`), rasterizing narrow piers/harbours (`allTouched=False`) drops sub-pixel polygons, diverging from `mask_vrt` averaging.
- **Fix:** Either (a) explicitly scope cleanup edits to `pyramid` + `_render_mask_block()` only (passing `cleanup_edits` to `_render_mask_block` for `wbm`, and removing the false claim that `_render_coarse_block()` uses the same helper, since production forbids `--mode per-zoom` anyway), OR (b) pass `wbm_vrt` to `_render_coarse_block()` and warp raw `wbm` (`near`) before `fill_invalid(elevation, valid)` (accepting coarse `allTouched=False` discretization limits). Option (a) is cleaner and aligns with `cli.py` forbidding `--mode` under `--coverage`.

### [Blocker] B6 — Unequal-margin `fade_deg` guard allows fading inside `camera_bounds`, and `base.py` ignores `widest_half_screen_dp`

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §2 (L179, L183, L190); `tools/map/src/seerah_map/grid.py` (L306–347); `tools/map/src/seerah_map/base.py` (L317, L439–440).
- **Failure Scenarios:**
  1. **Single-zero-margin `fade_deg` leak:** Spec L179 states: *"if every side margin `> 0`, `fade_deg <= min(side_margins)`"*. If one side has `0` margin (`data_extent.north == camera_bounds.north` with `min_data_margin_deg: 0`) while other sides have `1.0°`, `"every side margin > 0"` evaluates to **False**, permitting `fade_deg = 0.5°` — fading terrain to `0` **inside `camera_bounds`** along the north edge!
  2. **`widest_half_screen_dp` ignored in `base.py`:** `coverage.yaml` configures `widest_half_screen_dp`, yet `base.py` L317 calls `view_bounds(profile.camera_bounds, maplibre_zoom=view_zoom)` and L439–440 call `natural_earth_max_zoom` / `check_base_coverage` with the default `WIDEST_HALF_SCREEN_DP = 550.0`. If the MatePad 11.5 measurement exceeds `550.0` (e.g., `640.0 dp`), `coverage` check passes at `640.0` only if `base.py` uses `640.0`; clipping NE at `550.0` leaves blank sea outside `data_extent` at the tablet's screen edges!
  3. **Empty zoom check range:** If `min_zoom >= regional_max_zoom`, `range(math.floor(min_zoom), regional_max_zoom)` in spec L190 is empty and performs zero checks.
- **Fix:**
  1. Enforce `fade_deg <= min(side_margins)` **unconditionally** in `load_coverage()` (if any side margin is `0`, `fade_deg` MUST be `0`).
  2. Generalize `natural_earth_max_zoom` and `check_base_coverage` to accept an explicit `data_extent: Bounds` and `half_width_dp: float`, and pass `coverage.widest_half_screen_dp` into `base.py`'s `view_bounds()`, `natural_earth_max_zoom()`, and `check_base_coverage()`.
  3. Validate `math.floor(min_zoom) < regional_max_zoom` (or always check `max(0, math.floor(min_zoom))` against `ne_max` even when `min_zoom >= regional_max_zoom`).

### [Blocker] B7 — Phase 2 Copernicus `INFO/` pre-merge check cannot execute in CI or `seerah-map-cache`

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §Verified Facts #6 (L82), §Phased Roadmap (L365), §Known Risks (L379); `tools/map/src/seerah_map/fetch.py` (L260–267).
- **Failure Scenario:** Spec L365 lists *"verified Copernicus licence string matches cached `INFO/` file"* as a Phase 2 *"All Must Pass Before Merge"* check reading from `seerah-map-cache` (L82). However, `fetch.py` lines 260–267 ONLY download `..._DEM.tif` and `AUXFILES/..._WBM.tif` — **zero** `INFO/` files are ever downloaded into `seerah-map-cache` (nor pinned in `example.lock` / `sources.lock`). A Phase 2 automated test expecting `INFO/` inside `seerah-map-cache` will fail both locally and in CI (`--network none` build / `example.lock` cache).
- **Fix:** Separate the one-off manual/pre-release `INFO/` verification (or explicitly fetch and SHA-256-pin the `INFO/License-COPDEM-30.pdf` / `INFO/` notice file of one cell in `sources.lock` if automated verification in `seerah-map-cache` is desired) rather than gating Phase 2 CI/merge on an uncached, unfetched file.

---

## Majors

### [Major] M1 — Cleanup edge cases: overlap order footgun, `wbm == 2/3` port basins, relief step halos, and silent no-op edits

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §1 (L129–139, L153–159), §4 (L348–355); `cli.py` (L230).
- **Failure Scenarios:**
  1. **Order-dependent overlap trap:** Applying `edits` in file order means placing `remove-land` *before* `fill-water` on overlapping breakwater/basin geometries causes `fill-water` (seeing `wbm == 1`) to turn the removed breakwater back into land (`wbm = 0`).
  2. **Enclosed port basins tagged `wbm == 2` or `3`:** In KAEC's narrow channels or gated marinas, Copernicus WBM frequently classifies enclosed water as lake (`2`) or river (`3`) rather than ocean (`1`). While `mask.py` already renders `2`/`3` as land, `fill-water` (*"never alters non-ocean pixels"*, L132) skips zeroing `elevation` on `2`/`3`/`0` quay/crane pixels inside the basin, leaving artificial DEM bumps/pits next to `0.0 m` filled pixels.
  3. **Artificial hillshade halo:** Setting `elevation = 0.0` at a sharp unfeathered polygon against natural coastal elevation (`+8–15 m` bluff or corniche) injects a step discontinuity into `style.py`'s `blur_px = 7.0` (~800 m) Gaussian filter, drawing a dark relief band along the cut edge in `terrarium-value`.
  4. **Silent no-op `"applied"` edits:** A `[lat, lon]` coordinate swap (e.g., `[21.5, 39.1]` instead of `[39.1, 21.5]`) is a valid polygon in Somalia that passes `IsValid()` / `Area() > 0`, touches zero blocks, leaves Jeddah uncleaned, and is still recorded under `edits.applied` (per `cli.py` L230)!
- **Fix:**
  1. Reject overlapping approved `remove-land` and `fill-water` geometries in `load_cleanup()` (or document/enforce deterministic phase ordering, e.g., `fill-water` before `remove-land` if breakwaters cut across basin mouths).
  2. Check KAEC/Jebel Ali WBM classes during Phase 4 draft verification; consider whether `fill-water` should also flatten `elevation` on enclosed `wbm in (1, 2, 3)` (or document why only `1` is zeroed) and visually audit boundary halos at coastal bluffs in Phase 4.
  3. In `cli.py` / `build-provenance.json`, verify that every `approved` cleanup edit intersects `coverage.data_extent` (raise `ConfigError` if an approved cleanup edit lies completely outside `data_extent`) and track actual block-touch hits rather than blindly copying `[e.id for e in edits]`.

### [Major] M2 — Signing workflow & ADR 0008 traps: cross-`kind` replay, key overwrites, missing `--passphrase-fd`, and leaked-key `version` lockout

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §3 (L289–292, L302–306, L318, L325, L339); ADR 0007 D7–D9; ADR 0008 D5, D10–D11, Consequences.
- **Failure Scenarios:**
  1. **Cross-`kind` replay:** `verify --package <zip> --public-keys <json_or_dir>` has no `--kind` check, so a valid signed `content` package passes `verify` when a `map` package is expected (and vice versa).
  2. **Key destruction & mutable `dist/` overwrites:** `keygen` does not prohibit overwriting an existing private key (`O_EXCL`), `pack-map` omits `--passphrase-fd` (unlike `keygen`, blocking non-interactive signing tests), and `pack-map` uses `os.replace` onto `dist/seerah-map-<version>.zip` — silently overwriting a previously published artifact of the same `version` (violating ADR 0008 D11's *"Package files are never replaced or deleted"*).
  3. **Leaked-key `version` floor lockout:** Spec L339 mentions clearing the ADR 0008 `serial` floor when dropping a compromised key, but misses ADR 0007's **installed package `version` floor**: if a compromised key signed a poisoned `version = 999999999` package (`manifest.schema.json` has no `maximum` on `version`), dropping that key in an app update still leaves the poisoned package installed and blocks honest `version = 4` updates as "downgrades"!
- **Fix:**
  1. Add optional `--expected-kind {content,map}` to `verify` (enforced as `"map"` during `pack-map` self-verify).
  2. In `keygen`: create private key files strictly via `os.open(..., O_WRONLY | O_CREAT | O_EXCL, 0o600)` and refuse to overwrite existing keys. Add `--passphrase-fd` to `pack-map`. In `pack-map`, refuse to overwrite an existing `<out>/seerah-map-<version>.zip` (unless `--force` for dev/tests) and support optional `--previous-index` / `--previous-manifest` monotonicity guard (`version > prev.version`, warning/refusing accidental `min_app_version` decrease).
  3. Cap `manifest.schema.json` `version` (e.g., `maximum: 1000000`), document in `signing-runbook.md` that app builds dropping a retired key MUST also re-verify/discard any stored package signed by the dropped `key_id` (resetting its version floor), and fix L339's citation from `"ADR 0008 decision 12"` to `"ADR 0008 Consequences"`.

### [Major] M3 — `< 2 GiB` ZIP ceiling math, `Zip64` / Data Descriptor bounds, `manifest.json <= 1 MiB` evolution cap, and 3× storage

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §2 (L203), §3 (L295, L317, L320–328), §Known Risks (L378); ADR 0009 Consequences (L257).
- **Failure Scenarios:**
  1. **Unspecified `ZIP_BUDGET_OVERHEAD` & `ZIP64_LIMIT` off-by-one:** `tools/map` enforces `sum(data_files) + ZIP_BUDGET_OVERHEAD < 2,147,483,648`, while `ZIP_BUDGET_OVERHEAD` is undefined. Python's `zipfile.ZIP64_LIMIT` is `2,147,483,647` (`2 GiB - 1`), and streaming via `zf.open(zinfo, "w")` sets `flag_bits |= 0x08` adding Data Descriptors (16–24 B/entry) alongside LFH + CD + EOCD + `manifest.json` + `manifest.sig`. Underestimating `ZIP_BUDGET_OVERHEAD` lets a 40-minute `tools/map` build pass only to fail in `pack-map`. (Also note `ADR 0009` L257: a `1.37–2.0 GiB` package requires `~4.1–6.0 GiB` free space on device during update!).
  2. **`manifest.json <= 1 MiB` breaks large `content` packages:** Shared `tools/packages/` formats `manifest.json` with `indent=2` (~145 B per entry) and caps `manifest.json <= 1 MiB` (L328). Any future `seerah-content` package exceeding ~7,200 files exceeds `1 MiB` and fails `verify`.
  3. **Ambiguous canonical JSON rule:** L317 says *"`sort_keys=True` or fixed schema key order"* — leaving two valid implementations that emit different bytes and signatures.
- **Fix:**
  1. Define `ZIP_BUDGET_OVERHEAD = 65_536` (64 KiB for `map` builds, or `entry_count * 256 + MAX_MANIFEST_BYTES + 256`) and enforce `< 2_147_483_647` (`ZIP64_LIMIT`, avoiding Zip64 triggers on `< 2 GiB` single-file GitHub Release uploads).
  2. Pick one deterministic `manifest.json` serialization: `json.dumps(..., indent=2, sort_keys=True, ensure_ascii=False).encode("utf-8") + b"\n"` (or compact JSON without indentation to fit ~10,000+ entries under `1 MiB`, or raise `MAX_MANIFEST_BYTES` to `4 MiB` for `content` packages).
  3. Explicitly fix `ZipInfo` `flag_bits`, `create_version`, `extract_version`, and `internal_attr = 0` on every entry so Python minor-version updates never alter ZIP determinism.

### [Major] M4 — Schema validation & evolution traps (`manifest.schema.json`, `map.schema.json`, `cleanup.schema.json`, `config.py`)

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §1 (L153–159), §2 (L214–258), §3 (L287–298); `tools/map/src/seerah_map/config.py` (L137); `content/schema/content.schema.json` (L42–52).
- **Failure Scenarios:**
  1. **`manifest.schema.json` rejects `--allow-local-provenance`:** L298 sets `image` pattern `^sha256:[0-9a-f]{64}$`, which rejects `local:sha256:...` emitted by `tools/map/run.py` L100 when `--allow-local-provenance` is passed! Also L297 restricts `tool_commit` to 40-char SHA-1 while `source_commit` (L293) accepts 40 or 64 chars, and `tools/map` writes `data_commit` while `manifest.json` names it `source_commit`.
  2. **Silent schema validation bypasses in `config.py`:** `Draft202012Validator(schema)` in `config.py` L137 passes no `format_checker`, so `"format": "uri"` on `sources[].url` and `"format": "date-time"` on `created` are ignored! `source` in `content.schema.json` also lacks `additionalProperties: false` and `minLength: 1` on `work`/`locator`.
  3. **`map.schema.json` lacks invariants & evolution boundary:** Without `if/then` tying `("terrarium-value", "png")` and `("painted-rgba", "webp")`, invalid combos like lossy WebP Terrarium pass; without `const` / cross-checks on `sources.{base,relief}.file` and `credits` (`minItems: 1`, requiring `copernicus-worlddem-30` & `natural-earth`), empty credits pass; without `reconstructed_areas: {maxItems: 0}` (or `--allow-reconstructions` opt-in), reconstructions could ship before ADR 0009 D11's disclosure UI exists; and Android clients must ignore unknown fields on `tile_schema: 1` while refusing `tile_schema != 1`.
  4. **OGR `3857` pole crash in `load_cleanup()`:** Calling `TransformTo(_srs(3857))` (L159) before checking coordinate bounds `[-180, 180] x [-85.0511, 85.0511]` and ring closure (`ring[0] == ring[-1]`) allows `lat = +-90` to produce `inf` / GDAL errors instead of clean `ConfigError`.
- **Fix:**
  1. Allow `^(sha256:[0-9a-f]{64}|local:sha256:[0-9a-f]{64})$` in `manifest.schema.json` while having `pack-map`/`verify` reject `local:` unless `--allow-local-provenance` (and have production Android refuse `local:` if checked); accept 40- or 64-char hex for both `source_commit` and `tool_commit`; document `build-provenance["data_commit"] -> manifest["source_commit"]`; enforce `not: required: [tool_commit, image]` when `kind == "content"`.
  2. Pass `format_checker=Draft202012Validator.FORMAT_CHECKER` in schema validators; add strict `additionalProperties: false` and `minLength: 1` to `cleanup.schema.json` `sources`; validate `created` calendar timestamp with `datetime.strptime(..., "%Y-%m-%dT%H:%M:%SZ")`; enforce unique `id`s in `load_cleanup()` and sorted/unique/prefix-free `manifest["files"]` in Python.
  3. Lock `encoding <-> tile_format`, `sources.*.file`, non-empty `credits`, and v1 `reconstructed_areas` gate (`maxItems: 0` in v1) in `map.schema.json`, and specify producer-strict vs Android-lenient `tile_schema: 1` parsing rules.
  4. Validate finite numbers, WGS84/WebMercator domain bounds, and ring closure in `load_cleanup()` before `TransformTo(3857)`.

### [Major] M5 — Build integrity gaps: unverified cache in `build`, `source_vrts()` path traversal, `fingerprint()` collision, and non-atomic staging

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §2 (L185, L200–208); `blockstage.py` (L73); `cli.py` (L168–192, L268); `workarea.py` (L65–100); `pack.py` (L195–201).
- **Failure Scenarios:**
  1. **Unverified `/cache` in `build` & `source_vrts()` path traversal:** `cli._cmd_build()` calls `check_lock_matches()`, checking only `(lat, lon)` tuples — never verifying file SHA-256 against `sources.lock`! Corrupted or stale `/cache` files silently enter production builds. Worse, `blockstage.source_vrts()` (L73) passes `str(cache / ref.path)` straight to `gdal.BuildVRT()` without the confinement checks in `base._natural_earth_sources()`, allowing absolute/parent/symlink paths in a lock to read arbitrary files inside the container.
  2. **`fingerprint()` staging/work collisions:** `workarea.fingerprint()` omits `data_commit`, `peninsula_outline` file bytes, and a `"trial"` vs `"production"` domain tag. Committing unrelated changes in `seerah-content` (changing `data_commit`) or editing `peninsula_outline` produces the exact same `fp8`, colliding on `/out/map-staging-<fp8>/` and `/work/<fp>/`!
  3. **Non-atomic `/out/map-staging-<fp8>/` & late `peninsula_outline` crash:** `cli.py` writes directly into `out_dir`, so a failure in `base` leaves partial artifacts in `map-staging-<fp8>/` (violating L204). And `pack._outline_geometry` fails with `AttributeError` on invalid/missing `peninsula_outline` *after* a 40-minute build; `build-provenance.json`'s schema when `peninsula_outline` is omitted is also unspecified.
- **Fix:**
  1. Confine all `lock` paths (`dem`, `wbm`, `natural_earth`) inside `cache` in `load_lock()` / `source_vrts()`, and verify SHA-256 of present cache files during production `--coverage` `build` (before block stage).
  2. Add `"production"` domain tag, `data_commit`, and `peninsula_outline` bytes (when present) to production `fingerprint()`.
  3. Stage into `/out/.tmp-map-staging-<fp8>-<pid>/` and atomically rename into `/out/map-staging-<fp8>/` (failing if target already exists or replacing atomically) only after all 4 artifacts and `< 2 GiB` check pass. Validate `peninsula_outline` geometry upfront in `load_coverage()`, and specify `size_by_zoom` behavior when `peninsula_outline` is omitted.

### [Major] M6 — Copernicus attribution absent from derived PMTiles, `THIRD_PARTY_NOTICES.md` stale, and Natural Earth pre-`MakeValid()` erase

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §2 (L237–249, L269), §Phased Roadmap (L365–366); `base.py` (L329–370); `THIRD_PARTY_NOTICES.md` (L18).
- **Failure Scenarios:**
  1. **Attribution missing from public PMTiles archives:** Both `base.pmtiles` and `relief.pmtiles` are derived from Copernicus DEM/WBM and distributed publicly inside `seerah-map-<version>.zip` on GitHub Releases (ADR 0008), yet §2 L269 omits `"attribution"` from PMTiles metadata (putting credits only in `data/map.json`). Meanwhile `THIRD_PARTY_NOTICES.md` L18 still holds a truncated draft notice missing Airbus copyright, EU/ESA credit, `"all rights reserved"`, and the Article 6(c) liability clause, and Phase 3 omits adding `cryptography` / `tools/packages/` deps to `THIRD_PARTY_NOTICES.md`.
  2. **Natural Earth `erase` before `MakeValid()`:** `base.py` L363 calls `geom.MakeValid()` *after* `gdal vector layer-algebra --operation=erase` (`_erase_extent`), so any self-intersection introduced by `gdal vector clip` on `ne_10m_land` can fail GEOS `Difference` with `TopologyException`.
- **Fix:**
  1. Embed Copernicus + Natural Earth `"attribution"` directly in `base.pmtiles` and `relief.pmtiles` JSON metadata, update `THIRD_PARTY_NOTICES.md` with verbatim Copernicus + `tools/packages/` notices in Phases 2–3, and require non-empty `credits` (`copernicus-worlddem-30` + `natural-earth`) in `map.schema.json`.
  2. Validate/repair clipped Natural Earth geometries (`MakeValid`) *before* running `erase`, or test `erase` against any regional `data_extent` candidate in `coverage.yaml`.

---

## Nits

### [Nit] N1 — Production PMTiles `"name"`, `"mode"`/`"variant"` metadata keys, `tippecanoe` block-cut coast lines, and `block_zoom=6` RAM trap

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §2 (L178, L265, L269); `base.py` (L243–252, L477, L501); `pack.py` (L77–91).
- **Issues:**
  1. `Profile.name` comes from `path.stem`; for `coverage.yaml`, `"name"` in `base.pmtiles` / `relief.pmtiles` metadata would become `"coverage"`. Set an explicit constant `"seerah-map"` (or `name` field) and clarify whether `"variant"`/`"mode"` are omitted in production `relief.pmtiles` metadata.
  2. `wbm_coast_6.geojsonl` splits coastline `MultiLineString`s at every `z=9` block boundary before `tippecanoe` (without `--detect-shared-borders`), which can introduce slight endpoint drift/kinks on simplified `z6..z11` tiles (`test_build_example.py` only asserts `z12`). Verify `z6..z11` block-seam coast continuity visually or in tests once Phase 1 cleanup polygons cross block boundaries.
  3. `coverage.schema.json` allows `block_zoom: 6`, which produces `16,384 × 16,384` float64 arrays in `style.py` (>10 GiB per worker, guaranteed OOM on 8 GiB hosts). Document `block_zoom: 9` as the production norm (or enforce `block_zoom >= 8`).

### [Nit] N2 — Minor spec text inaccuracies & CI hook clash

- **Scope:** `docs/design/v1-map-pipeline-spec.md` §Verified Facts #3 (L58), §3 (L305, L339); `base.py` (L238–252); ADR 0008; `.pre-commit-config.yaml` (L14).
- **Issues:**
  1. Spec L58 states `base.py` extracts both `land` and `coast` (`z6..max_zoom`) from `work.mask_dir / f"{block.id}.tif"`; in `base.py` L244, `coast` actually reads `work.path / "mask.vrt"` for 1-pixel neighbor context.
  2. Spec L339 references *"ADR 0008 decision 12"*; ADR 0008 stops at decision 11 (high-version/serial risk is in ADR 0008 Consequences L118).
  3. Spec L305 writes both `public_key_hex` and `public_key_base64` in public key JSON; prefer one canonical field (`public_key_base64`) or assert decoded byte equality if both are kept.
  4. `.pre-commit-config.yaml` runs `detect-private-key` on all committed files; Phase 3 tests in `tools/packages/tests/` must generate keys in `tmp_path` or store raw 32-byte test seeds rather than committing literal PEM private-key headers.
