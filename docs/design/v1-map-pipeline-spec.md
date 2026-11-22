<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# v1 map pipeline: coastal cleanup, map.json and signed packaging

- **Author:** MohdFaragDev
- **Status:** Revised after [Elephant-Goldfish Model (EGM) review](egm-review-v1-map-pipeline/consolidated_review_report.md); pending owner approval of decisions D1–D7
- **Date:** 2026-11-23

## Overview

This specification defines the production v1 map pipeline that turns verified Map Trial findings ([Step 4 report](../map-trial/step-4.md)) into a signed, installable v1 map package matching [ADR 0007](../adr/0007-content-formats-compatibility-and-signing.md) and [ADR 0009](../adr/0009-map-data-pipeline.md).

It specifies four connected deliverables across the existing map build tool (`tools/map/`) and a new shared host packaging tool (`tools/packages/`, fulfilling ADR 0009 decision 8's amendment to ADR 0007):

1. **Coastal cleanup edits** on the Copernicus Water Body Mask (WBM) and coincident elevation pixels so modern reclaimed land, artificial islands, breakwaters, and excavated industrial port basins are removed before generating both `base.pmtiles` and `relief.pmtiles`.
2. **Production map configuration (`coverage.yaml`) and `tile_schema: 1` staging output** (`data/base.pmtiles`, `data/relief.pmtiles`, `data/map.json`, plus `report.json`), built inside the pinned `seerah-map` container without `trial` markers and enforced within the `< 2 GiB` (`ZIP64_LIMIT`) single-file GitHub Release ceiling.
3. **Shared host-side manifest, deterministic packaging, and offline Ed25519 signing** (`tools/packages/`), shared with future content packaging (ADR 0009 decision 8), enforcing [map-trial-build.md Goal 9](map-trial-build.md#goals) (refusing trial outputs) and isolated single-key verify-before-parse signature checks compatible with Google Tink `NO_PREFIX` on Android.
4. **Private coastal cleanup drafts (`seerah-content/map/cleanup.yaml`)** for the Priority 1 Hijaz and Priority 2 Gulf modern coastal artifacts identified during the Step 4 visual audit.

## Owner Decisions Needed Before Coding

These seven design choices (`D1–D7`) sit within [ADR 0007](../adr/0007-content-formats-compatibility-and-signing.md) and [ADR 0009](../adr/0009-map-data-pipeline.md) and require owner confirmation before implementation starts.

| ID | Topic | Recommended option | Alternative |
| --- | --- | --- | --- |
| **D1** | Coastal cleanup operations | **Option B (`remove-land` + `fill-water`):** removes reclaimed islands/jetties AND infills modern excavated port basins (`wbm == 1`, checked on KAEC/Jebel Ali in Phase 4 for `wbm == 2/3` channels) to flat `0.0 m` sea-level plain with zero added relief shape (amends ADR 0009 decision 3's table narrowly) | **Option A (`remove-land` only):** removes islands/jetties but leaves modern geometric dredged basins etched into the coastline |
| **D2** | Cleanup edit format | **Separate `cleanup.schema.json` (`schema: 1`, `purpose: production`):** no `feather_m`, supports `Polygon`/`MultiPolygon`, requires `reason` and strict `sources` when `approved` | Reuse provisional `edits.schema.json` (`trial-0`), mixing elevation-feather trial fields with discrete WBM operations |
| **D3** | Production build config, staging & `report.json` | **Dedicated `coverage.schema.json` + CLI `--coverage` flag:** clean break from `purpose: trial` profiles; stages `/out/map-staging-<fp8>/data/{base.pmtiles,relief.pmtiles,map.json}` + extended `report.json` (`purpose: production`); omits `metadata.json` and `trial` keys completely | Reuse `profile.schema.json`, risking trial/production ambiguity and colliding with [Goal 9](map-trial-build.md#goals) separation |
| **D4** | Package building & Ed25519 signing | **Shared host Python package `tools/packages/` (3 modules: `signing.py`, `archive.py`, `cli.py`):** keeps private Ed25519 keys outside Docker, shares manifest/signing/zipping with content packages, reuses `pmtiles==3.8.1`, puts `manifest.json` + `manifest.sig` first in ZIP | Put signing inside `tools/map` Docker image (forces key mounts into containers and ties content signing to a GIS image) |
| **D5** | Cleanup evidence `sources[].kind` enum | **Cleanup-specific `["imagery", "dated-data"]`:** matches ADR 0009 decision 2's Cleanup evidence rule (*"Modern imagery or dated data"*), keeping `"atlas"` reserved for future historical reconstruction | Allow `"atlas"` alongside `["imagery", "dated-data"]` if a historical atlas is cited as negative evidence for removing a modern coastal change |
| **D6** | Data margin & screen-width defaults in `coverage.yaml` | **No schema defaults (`1.0°` / `550.0 dp`):** keep `widest_half_screen_dp > 0` required (no default until MatePad 11.5 is measured); enforce a positive production `min_data_margin_deg` (either fixed constant in `tools/map` or required positive config field) and allow `0` margin *only* via CLI `--allow-zero-margin` for CI examples | Keep `1.0` and `550.0` as schema defaults (conflicts with [open-decisions.md](../open-decisions.md) and lets a config file weaken ADR 0009 decision 7) |
| **D7** | `coverage.schema.json` `schema: 1` & `map.schema.json` bounds scope | **Defer Option (b) / `valley` from `coverage.schema.json` `schema: 1` and remove Northern/Eastern quadrant lock from `map.schema.json`:** scope `schema: 1` to single-archive `relief.pmtiles` (relying on ADR 0009 decision 10 additive evolution if revisited); document optional `valley` & `relief-detail.pmtiles` in `tile_schema: 1`; keep `0..180`/`0..~85.0511` check in `tools/map` only | Wait to finalize `coverage.schema.json` until Oppo A55 & MatePad 11.5 close the trial, or include optional Option (b) / `valley` fields in `schema: 1` now |

---

## Where This Fits and Explicit Non-Goals

[ADR 0009](../adr/0009-map-data-pipeline.md) established a two-stage sequence: run the map trial (decision 13), then build and package the v1 map. Steps 1–4 of the trial are complete on the workstation and Galaxy A54 ([step-4.md](../map-trial/step-4.md)). Both `option-c` (`terrarium-value`) and `painted` fallback passed Galaxy A54 checks, while measurements on the **Oppo A55 (4 GB)** and **Huawei MatePad 11.5** remain deferred ([step-4.md](../map-trial/step-4.md)), and `:core:map` `onTrimMemory -> reduceMemoryUse()` wiring and tile-cache/prefetch tuning remain pending merge and Oppo A55 verification (`main`'s `SeerahMap.kt` still carries the trial placeholder `onTrimMemory` no-op). Option (b) (`relief-detail.pmtiles`) is omitted here because ADR 0009 decision 4 ties it strictly to approved historical reconstruction edits.

The trial path (`--profile`, `purpose: trial`, `"trial": true`, and `android/app/src/benchmark/`) stays intact alongside this production path until those deferred device checks close the map trial.

**Explicit Non-Goals (respecting [open-decisions.md](../open-decisions.md)):**

- **Never hard-code open map configuration values:** final `coverage.yaml` values in `seerah-content/map/` (exact Arabian Peninsula bounds, camera `min_zoom`/`max_zoom`, regional zoom `11` vs `12`, primary encoding `terrarium-value` vs `painted-rgba`, first-launch bundling vs download) stay configurable inputs and are not decided in code or this spec.
- **Historical reconstruction edits stay separate and undecided:** the `reconstruction` edit format (Badr terrain, sources, atlases, smoothing/feathering method) remains open until historical reconstruction work starts. This spec covers `cleanup` edits only and constrains `map.json`'s `reconstructed_areas` to `[]` until approved reconstructions exist.
- **No `valley` layer:** the Step 1 Copernicus GLO-90 probe OOM-killed at `8,192 MiB` ([step-1.md](../map-trial/step-1.md#valley-probe-trial-item-7)); v1 ships without valleys, while `tile_schema: 1` reserves optional `valley` per ADR 0009 decision 10.
- **No Option (b) detail archive (`relief-detail.pmtiles`):** excluded from `coverage.schema.json` `schema: 1` per D7 (still reserved as optional in `tile_schema: 1` per ADR 0009 decision 9).
- **No Android app code or CDN publishing changes:** reading `data/map.json` in `:core:map`, verification/extraction in `:core:content` / `:core:update`, and Cloudflare Pages `index.json` publishing (ADR 0008) belong to companion Android and publishing work. This spec locks the exact file layouts, JSON schemas, ZIP rules, and single-key Ed25519 signature verification contract so Python and Kotlin align on the exact bytes.
- **No private content in public git and no agent approvals:** private `seerah-content/map/cleanup.yaml` polygons are drafted privately as `review: draft`; only the owner sets `review: approved`.

---

## Verified Codebase & External Facts (Checked 2026-11-23)

1. **Single land mask feeds both `relief.pmtiles` and `base.pmtiles` (`tools/map/`):**
   - `MASK_SIGMA_PX = 4.0` is defined in `tools/map/src/seerah_map/grid.py` (`12 px` truncate-3 radius at zoom 12) and imported by `mask.py`, which turns Copernicus WBM (`0` no water, `1` ocean, `2` lake, `3` river) into binary land (`wbm != 1`), blurs with `ndimage.gaussian_filter(..., sigma=MASK_SIGMA_PX, truncate=3.0, mode="nearest")`, scales to uint8 `0..255`, and zeroes invalid outside-extent pixels.
   - `blockstage.py` (`render_block()`) computes `land_values = _land_mask_from_wbm(wbm, valid)`, writes `work.mask_dir / f"{block.id}.tif"`, and feeds `land_values >= 128` into `style.darkness()`. Afterwards `write_mosaics()` (`blockstage.py`, called by `run_block_stage()`) builds `work.path / "mask.vrt"`.
   - `base.py` extracts `land` polygons (`>= 128`) and `coast` contours (`gdal_contour -fl 128`) from `work.path / "mask.vrt"` (`z0–5`), and at `z6..max_zoom` extracts `land` from each `work.mask_dir / f"{block.id}.tif"` and `coast` from `work.path / "mask.vrt"` (clipped to `block.window` for 1-pixel neighbor context).
   - Editing `wbm` **before** `mask.fill_invalid()` and `_land_mask_from_wbm()` therefore updates both `base.pmtiles` and `relief.pmtiles` identically from one source of truth (ADR 0009 decision 6).

2. **Elevation blur precedes land masking in `style.py` (critical `remove-land` side effect):**
   - `style.py` (`darkness()`) computes [Style C](map-trial-build.md#the-block-stage) darkness by applying `ndimage.gaussian_filter(elevation, sigma=s.blur_px, truncate=3.0, ...)` (radius `21 px` ≈ `800 m` at zoom 12 when `blur_px = 7.0`) **before** masking out sea with `d[~land] = 0`.
   - Zeroing `elevation` inside `remove-land` footprints prevents artificial piers, dikes, or elevated reclaimed terminals from bleeding phantom hillshade darkness onto adjacent natural coastline.

3. **Block border padding already covers cleanup smoothing (`grid.py`):**
   - `border_px(style, edits, block)` already adds `ceil(3 * blur_px) + band_blur_radius_px(band_blur_px) + ceil(3 * MASK_SIGMA_PX) + 1` (`38 px` at zoom 12 for default Style C) around each block. Rasterizing cleanup geometries onto each bordered block window requires `0.0 m` extra edit reach.

4. **PMTiles trial guard targets (`pack.py`, `base.py`):**
   - Both `pack.metadata()` and `base._write_metadata()` currently write `"trial": True`, `pack.pack()` copies `metadata.json` beside `relief.pmtiles` in `out_dir`, and `pack.verify()` + `base.verify_base()` reject `meta.get("trial") is not True` as an error. Production mode must omit `"trial"` completely and skip writing sidecar `metadata.json` in the staging directory.

5. **Step 4 cleanup audit findings ([step-4.md](../map-trial/step-4.md#decision-3--modern-features-cleanup-audit-item-9)):**
   - **Zero DEM cleanup needed:** Abraj Al Bait Clock Tower (Makkah), central Madinah, 1256 CE Harrat Rahat lava flow, Badr, and Taif are visually clean at Style C zoom-12 smoothing.
   - **Two distinct modern coastal artifact classes in `base.pmtiles`:**
     - *Protruding reclaimed land / breakwaters / artificial islands:* Jeddah Islamic Port & North Jeddah/Obhur piers, Yanbu industrial jetties, Dubai Palm Jumeirah / Palm Jebel Ali / The World / Dubai Maritime City, Bahrain Durrat Al Bahrain & northern reclaimed islands/jetties, Doha The Pearl-Qatar / Lusail islands / Hamad Airport peninsula.
     - *Dredged modern inland port basins cut into coastal plain:* King Abdullah Economic City (KAEC) dredged lagoon/channels and Dubai Jebel Ali Port's excavated rectangular harbour comb.

6. **Copernicus WorldDEM-30 attribution and liability terms ([Copernicus WorldDEM-30 licence](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf)):**
   - **Modified-data notice:** `"produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved."`
   - **Article 6(c) liability sentence:** `"The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30."`
   - **Important `fetch.py` constraint:** `fetch.py` only downloads `*_DEM.tif` and `AUXFILES/*_WBM.tif` into `seerah-map-cache` (never `INFO/` folders). Therefore, checking the upstream Copernicus `INFO/` licence file per ADR 0009 decision 11 is an explicit one-off Phase 2 upstream verification step recorded in `THIRD_PARTY_NOTICES.md`, not an offline CI check against `seerah-map-cache`.

7. **Google Tink Android + Python `cryptography` Ed25519 wire format (`tink-android` key-binding trap):**
   - `tink-android` `Ed25519Parameters.Variant.NO_PREFIX` (`OutputPrefixType.RAW`) verifies a raw **64-byte** RFC 8032 signature against raw bytes using a raw **32-byte** Ed25519 public key (matching Python `cryptography`'s `Ed25519PrivateKey.sign()`).
   - **Critical wrapper constraint:** In `tink-android`, calling `keysetHandle.getPrimitive(PublicKeyVerify.class)` on a *multi-key* `KeysetHandle` with `NO_PREFIX` wraps keys in `PublicKeyVerifyWrapper`, whose `verify(sig, data)` returns **`void`** without identifying *which* key verified the signature. To enforce both verify-before-parse and strict `key_id` binding during key rotation (`current` + `next` per ADR 0007 decision 9), each trusted `(key_id, raw_public_key_32B)` entry **must** sit in its own **isolated single-key `KeysetHandle` / `PublicKeyVerify` instance**.

8. **`:core:map` contract alignment (`StyleJson.kt`, `CameraLimits.kt`, `MapStyleSpec.kt`):**
   - `:core:map` reads two PMTiles sources: `"base"` (`vector` source with layers `"land"` and `"coast"`) and `"relief"` (`raster` for `Painted` or `raster-dem` `encoding: "terrarium"` for `Value`, both with `"tileSize": 256` and `"maxzoom": spec.maxZoom` in `StyleJson.kt`).
   - Zoom convention: PMTiles headers store 256-px **build zooms** (`0..regional_max_zoom`, e.g., `11` or `12`). Camera bounds and fade schedules use **MapLibre zooms**, where MapLibre zoom `z` displays build zoom `z + 1` on `relief`.

---

## Architecture Overview

```text
Private checkout (seerah-content/map/)        Public repository (seerah/)
  coverage.yaml     (schema: 1, prod)  ───┐
  cleanup.yaml      (schema: 1, prod)     ├──▶  tools/map/run.py (host git checks, mounts :ro)
  sources.lock      (pinned cells/NE)  ───┘       │  docker run --network none -> python3 -m seerah_map
                                                  ▼ builds + checks < 2 GiB + no trial keys
                                              map-data/dist/map-staging-<fp8>/
                                                data/base.pmtiles      (tile_schema: 1, no trial)
                                                data/relief.pmtiles    (tile_schema: 1, no trial)
                                                data/map.json          (schema-validated credits/bounds)
                                                report.json            (purpose: production, provenance)
                                                  │
                                                  ▼ host Python (offline Ed25519 private key)
Offline owner key (~/.config/seerah/keys/) ───▶ tools/packages/ pack-map --version N --min-app-version M
                                                  │ checks Goal 9 + tile_schema: 1 + checksums + <= ZIP64_LIMIT
                                                  ▼ deterministic ZIP_STORED (manifest & sig FIRST)
                                              dist/seerah-map-<version>.zip  (host ./dist/ or --out DIR)
                                                manifest.json          (ADR 0007 + 0009, exact file hashes)
                                                manifest.sig           (64-byte raw Ed25519 signature)
                                                data/base.pmtiles
                                                data/relief.pmtiles
                                                data/map.json
```

*(Note: staging output lands in repo-relative `map-data/dist/map-staging-<fp8>/` via `tools/map/run.py`, while host `tools/packages/ pack-map` writes final distributable `.zip` archives into a host release directory passed via `--out`, e.g. `./dist/`.)*

---

## Section 1 — Coastal Cleanup Edits (D1, D2, D5)

### Operations, Scope, and Pixel Semantics

Cleanup operations apply inside `render_block()` (`blockstage.py`, `pyramid` zoom-12 blocks) **after** `read_block()` warps `elevation` and `wbm`, **before** `mask.fill_invalid()` and `_land_mask_from_wbm()`. (`--coverage` builds forbid `--mode per-zoom`, so coarse per-zoom rendering `_render_coarse_block()` never runs; `_render_mask_block()` may optionally call `apply_wbm_cleanup` for mask-only unit tests.)

| Operation | Target condition (`valid & footprint`) | Effect on `wbm` | Effect on `elevation` |
| --- | --- | --- | --- |
| `remove-land` | All `valid` pixels inside edit polygon | Set to `1` (ocean) | Set to `0.0` m (prevents `blur_px` elevation bleed onto adjacent coast) |
| `fill-water` *(D1 Option B)* | `valid` pixels inside edit polygon where `wbm == 1` (ocean) | Set to `0` (no water / land) | Set to `0.0` m (flat sea-level coastal plain; never alters non-ocean pixels) |

**Dataclass, reuse, and safety rules (`edits.py` + `config.py` + `blockstage.py`):**

- **Separate `CleanupEdit` dataclass:** holds `id`, `geometry`, `operation`, `reason`, `review`, `sources` (no `feather_m`, `amount_m`, `height_m`, or `width_m`).
- **Shared primitives in `edits.py`:** parameterize `touches(edit, window, reach_m=...)` (`reach_m=0.0` for `CleanupEdit`), reuse `applicable()`, `_srs()`, `_to_mercator()`, and `_footprint()`, and provide `apply_cleanup(elevation, wbm, valid, cleanups, window)` (plus `apply_wbm_cleanup(wbm, valid, cleanups, window)` if needed by `_render_mask_block()`). `grid.border_px()` passes `edits=[]` for cleanup edits since `0.0 m` reach adds zero extra padding beyond `MASK_SIGMA_PX` and `blur_px`.
- **Approval and provenance rules:** only `review == "approved"` cleanup edits apply; every unapproved edit (`draft`, `in-review`, `title-approved`) is skipped and listed in `report.json` (`edits.skipped`) and stdout.
- **No overlapping approved `remove-land` / `fill-water` polygons:** `edits.py` rejects any pair of approved cleanup edits with different operations whose Web Mercator (`EPSG:3857`) geometries intersect (`ConfigError` or build validation failure), eliminating order-dependent revert bugs.
- **No silent no-op edits:** `cli.py` validates before block rendering that every `approved` cleanup edit's bounding box intersects `coverage.data_extent` (failing with `ConfigError` on swapped `[lat, lon]` coordinates), and `report.json` records `edits.applied` for edits intersecting the built area.
- **Keep `config.py` GDAL-free:** `load_cleanup()` validates against `cleanup.schema.json`, runs `_check_finite()`, checks unique `id`s, validates explicit polygon ring closure (`ring[0] == ring[-1]`), and checks WGS84 WebMercator bounds (`lon in [-180.0, 180.0]`, `lat in [-85.0511287798066, 85.0511287798066]`) before any GDAL transform. `edits.validate_cleanups()` checks `ogr.Geometry.IsValid()` and `Area() > 0` inside the container (used by `build` and `validate`).

### Schema: `tools/map/src/seerah_map/schema/cleanup.schema.json`

- **Top-level keys (`additionalProperties: false`, all required):**
  - `schema`: `const: 1` (integer)
  - `purpose`: `const: "production"`
  - `edits`: array of cleanup edit objects (may be `[]`)
- **Each cleanup edit (`additionalProperties: false`):**
  - `id` *(required)*: slug `^[a-z0-9]+(-[a-z0-9]+)*$` (`minLength: 1`), unique across the file
  - `class` *(required)*: `const: "cleanup"`
  - `operation` *(required)*: `"remove-land"` or (under D1 Option B) `"fill-water"`
  - `geometry` *(required)*: GeoJSON `Polygon` or `MultiPolygon` in EPSG:4326 `[lon, lat]` coordinates (`lon` `-180..180`, `lat` `-85.0511287798066..85.0511287798066`, `minItems: 4` per ring)
  - `reason` *(required)*: string (`minLength: 1`, pattern `\S` non-whitespace) describing the modern artifact removed
  - `review` *(required)*: enum `["title-approved", "draft", "in-review", "approved"]`
  - `sources` *(optional or `[]` when unapproved; **required `minItems: 1` when `review == "approved"`**)*: array of strict objects (`additionalProperties: false`, required `["kind", "work", "locator"]`, optional `"edition"` (`minLength: 1`) and `"url"` (`minLength: 1`, `pattern: "^https?://\\S+$"`)):
    - `kind`: per **D5**, recommended `["imagery", "dated-data"]`
    - `work`, `locator`: `type: "string", minLength: 1, pattern: "\\S"`

---

## Section 2 — Production Config (`coverage.yaml`), Staging, `map.json`, and `tile_schema: 1` (D3, D6, D7)

### Production Configuration Schema: `tools/map/src/seerah_map/schema/coverage.schema.json`

`<rel>.yaml` is addressed relative to `--data` (e.g. `seerah-content/map/coverage.yaml` via `--coverage coverage`, or `map-data/example-production/coverage.yaml` via `--coverage example-production/coverage`), with `cleanup.yaml` and `sources.lock` resolved in `coverage_path.parent`.

| Key | Type | Rule / Constraints | Notes |
| --- | --- | --- | --- |
| `schema` | int | `const: 1` | Production config schema version |
| `purpose` | string | `const: "production"` | Separates production configs from `purpose: trial` |
| `camera_bounds` | object | `{west, south, east, north}` inside Northern/Eastern (N/E) hemisphere `WebMercatorQuad` (`0 <= west < east <= 180`, `0 <= south < north <= 85.0511287798066`) | Map camera pan limits in degrees |
| `data_extent` | object | `{west, south, east, north}` inside N/E `WebMercatorQuad`; encloses `camera_bounds` widened by `min_data_margin_deg` (within `1e-9` float tolerance) | Extent of Copernicus cells & tiles |
| `camera_min_zoom` | number | `>= 0`, `math.floor(camera_min_zoom) < regional_max_zoom`, `< camera_max_zoom` | Minimum MapLibre camera zoom |
| `camera_max_zoom` | number | `<= 22` and `> camera_min_zoom` | Maximum MapLibre camera zoom |
| `regional_max_zoom` | int | `8 <= block_zoom <= regional_max_zoom <= 12` | Build zoom (256-px grid) of `relief.pmtiles` and fine WBM tiles |
| `block_zoom` | int | `8 <= block_zoom <= regional_max_zoom` (standard production value: `9`) | Worker tiling grid zoom (`>= 8` prevents `>10 GiB` float64 z6 OOM) |
| `fade_deg` | number | `>= 0` and unconditionally `<= min(side_margins)` (`1e-9` float tolerance) | Forces `0` whenever any side margin is `0` |
| `relief_encoding` | string | `"terrarium-value"` \| `"painted-rgba"` | Selects `value` (`png`) or `painted` (`webp`) |
| `webp_quality` | int *(cond.)* | `1..100`; required when `relief_encoding == "painted-rgba"`, forbidden when `"terrarium-value"` | Avoids meaningless quality config on PNG builds |
| `widest_half_screen_dp` | number | `> 0`, **required (no default per D6)** | Half width in dp for `check_base_coverage` (awaits MatePad 11.5 measurement) |
| `min_data_margin_deg` | number | Per **D6**: `> 0` in production configs (`0` permitted only with CLI `--allow-zero-margin` for CI fixtures) | Prevents accidental zero-margin production builds |
| `style` | object *(opt.)* | Same schema as `profile.schema.json` `style` | Defaults match calibrated Style C |
| `peninsula_outline` | string *(opt.)* | Relative path confined to `data_root` via `_resolve_outline`; validated upfront in `load_coverage()` | Enables inside/outside byte split in `report.json` when present |

**Coverage & geometry checks (`grid.py`, `base.py`, `config.py`):**

- Generalize `grid.check_data_extent_coverage`, `grid.natural_earth_max_zoom`, and `grid.check_base_coverage` to accept an explicit rectangular `data_extent: Bounds`, `min_maplibre_zoom: int`, and `half_width_dp: float` (keeping existing `Profile` callers working cleanly).
- In `base.py`, pass `coverage.widest_half_screen_dp` and `math.floor(coverage.camera_min_zoom)` into `view_bounds()`, `natural_earth_max_zoom()`, and `check_base_coverage()`. Apply `geom.MakeValid()` on clipped Natural Earth geometries *before* `gdal vector layer-algebra --operation=erase` in `_erase_extent()`.
- Explain cleanly: `base.pmtiles` spans build zooms `0..regional_max_zoom`, and `check_base_coverage` checks MapLibre zooms `math.floor(camera_min_zoom)..regional_max_zoom - 1` because MapLibre zoom `regional_max_zoom - 1` displays build zoom `regional_max_zoom` (above which `data_extent` already exceeds the zoomed-in screen or `base` overzooms smoothly).
- In `load_coverage()`, catch `ValueError` from `grid.*` checks and raise `ConfigError`.
- Provide an explicit `BuildTarget` adapter dataclass inside `tools/map/` carrying `build_max_zoom = coverage.regional_max_zoom` into `blockstage`, `tilestage`, `pack`, and `base` (`run_block_stage` checks `build_max_zoom <= BASE_ZOOM` for all modes), never conflating `camera_max_zoom` with build zoom.

### Host Launcher (`tools/map/run.py`) & Container CLI (`cli.py`)

**Host launcher (`tools/map/run.py`):** continues enforcing clean git checkouts on both `seerah` and `seerah-content`, computing/resolving the pinned image (`ghcr.io/al-seerah/seerah-map`), mounting `/src:ro`, `/data:ro` (`<private>/map` or `--data`), `seerah-map-cache:/cache`, `seerah-map-work:/work`, and `map-data/dist` (or `--out`) at `/out`, injecting `SEERAH_TOOL_COMMIT`, `SEERAH_DATA_COMMIT`, and `SEERAH_IMAGE_DIGEST`, and passing `--network none` for every command except `fetch`. Forward `--coverage` cleanly just like `--profile`.

**Container CLI (`python3 -m seerah_map ...`):** make `--profile` and `--coverage` a `mutually_exclusive_group(required=True)` on `fetch` and `build`, set `--variant` (`required=False, default=None`) and `--mode` (`default=None`), and add `validate`:

1. **`fetch --coverage <rel>`**:
   - Reads read-only `/data/<rel>.yaml` (`coverage_path`). Resolves `/data/.../sources.lock` in `coverage_path.parent`.
   - Container `/data` is read-only: when `sources.lock` already exists, verifies cached DEM, WBM, and Natural Earth files match it; when missing, downloads and hashes them into `/cache`, writes **`/out/sources.lock`**, and instructs: `cp /out/sources.lock seerah-content/map/sources.lock` (`fetch()` parameterized with `lock_name` and `lock_destination`).
2. **`validate --coverage <rel>`**:
   - Runs full Python schema checks (`load_coverage`, `load_cleanup`, `load_lock`) plus GDAL geometry validation (`edits.validate_cleanups`, `peninsula_outline`, non-overlapping approved ops, `data_extent` intersection) inside the container (`exit 0` valid, `exit 2` `ConfigError`).
3. **`build --coverage <rel> [--allow-zero-margin]`**:
   - Rejects `--variant` or `--mode` (`exit 2`), always building `mode = "pyramid"` using `coverage.relief_encoding`.
   - Loads `coverage_path`, `coverage_path.parent / "cleanup.yaml"`, and `coverage_path.parent / "sources.lock"`.
   - **Pre-build integrity checks:** confirms all lock paths (`dem`, `wbm`, `natural_earth`) are relative paths resolving inside `cache`; verifies SHA-256 of present cache files before rendering; validates cleanups (`edits.validate_cleanups`) and `peninsula_outline`.
   - Computes build fingerprint (`workarea.fingerprint()` + `"production"` domain tag + `data_commit` + `peninsula_outline` bytes if configured; `<fp8>` = first 8 hex chars). Note `workarea.fingerprint()` hashes `tool_commit` when set else `code_hash(src_root)`.
   - Renders blocks (`render_block` with `apply_cleanup`), tiles `relief.pmtiles`, packs with `pack.pack(..., write_metadata_file=False)` and production metadata (**no `"trial"`, `"variant"`, or `"mode"` keys**; `"name": "seerah-map"`, `"tile_schema": 1`, `"attribution"`, `"encoding"`, provenance), verifies `pack.verify(..., trial=False)` (`"trial" not in meta`), builds `base.pmtiles` (**no `"trial"` key**; `"name": "seerah-map"`, `"tile_schema": 1`, `"attribution"`, `vector_layers`, provenance), verifies `base.verify_base(..., trial=False)`.
   - Generates `data/map.json` (`json.dumps(..., indent=2, sort_keys=True, ensure_ascii=False) + "\n"`) validated against `map-data/schema/map.schema.json`, plus `<staging>/report.json` (`purpose: "production"`).
   - **Hard release-size check:** `MAX_RELEASE_FILE_BYTES = 2_147_483_647` (`ZIP64_LIMIT`, strictly `< 2 GiB`) and `ZIP_BUDGET_OVERHEAD = 1_048_576` (`1 MiB`, covering ZIP headers + Data Descriptors + `manifest.json` + `manifest.sig`). Fails with `PackError` before finalizing if any `data/` file or `sum(data_files) + ZIP_BUDGET_OVERHEAD > MAX_RELEASE_FILE_BYTES`.
   - **Atomic staging:** writes all outputs into `/out/.tmp-map-staging-<fp8>-<os.getpid()>/` and swaps cleanly into `/out/map-staging-<fp8>/` on the same filesystem (`/out`), removing `.tmp-*` on failure:
     - `data/base.pmtiles`
     - `data/relief.pmtiles`
     - `data/map.json`
     - `report.json` *(outside `data/`, never packaged)*

### Staging `report.json` Contract (`report.py`)

Reuses `tools/map/src/seerah_map/report.py` (`_SHAPE`) rather than inventing a second provenance format:

- Adds `"purpose": "production"` on `--coverage` builds (while `--profile` keeps existing trial reporting or `"purpose": "trial"`).
- Allows `"size_by_zoom"` to be `None` when `coverage.peninsula_outline` is omitted (`cli.py` skips `pack.size_by_zoom`).
- Includes `provenance`: `{"tool_commit", "data_commit", "image", "fingerprint"}`, where `report["provenance"]["data_commit"]` maps to `manifest["source_commit"]`.
- Includes `edits`: `{"applied": [...], "skipped": [...]}` plus `stage_seconds`, `memory`, `disk`, `tiles`, `workers`.

### `data/map.json` Mapping & Schema (`map-data/schema/map.schema.json`)

**Field mapping from `coverage.yaml`:**

- `coverage.camera_bounds` -> `camera_bounds`
- `coverage.data_extent` -> `data_extent`
- `coverage.camera_min_zoom` -> `min_zoom` *(MapLibre camera zoom)*
- `coverage.camera_max_zoom` -> `max_zoom` *(MapLibre camera zoom)*
- `coverage.regional_max_zoom` -> `sources.base.max_zoom` and `sources.relief.max_zoom` *(256-px build zoom)*
- `coverage.relief_encoding` -> `sources.relief.encoding` (`"terrarium-value"` -> `tile_format: "png"`, `"painted-rgba"` -> `tile_format: "webp"`)

**Schema constraints (`map-data/schema/map.schema.json`):**

| Field | Schema Rule | Notes |
| --- | --- | --- |
| `(top-level)` | `additionalProperties: false` *(producer)*; required: `tile_schema`, `camera_bounds`, `data_extent`, `min_zoom`, `max_zoom`, `sources`, `credits`, `reconstructed_areas` | `$comment` notes Android `tile_schema: 1` readers ignore unknown optional fields per ADR 0009 decision 10 |
| `tile_schema` | `type: integer`, `const: 1` | Tile contract version |
| `camera_bounds`, `data_extent` | WGS84 WebMercator bounds (`-180..180`, `-85.0511287798066..85.0511287798066`, per **D7**), `west < east`, `south < north` checked cleanly | Does not hard-code N/E quadrant in `map.schema.json` |
| `min_zoom`, `max_zoom` | Numbers (`0..22`, `min_zoom < max_zoom`) | MapLibre camera zoom bounds |
| `sources.base` | `{file: const "base.pmtiles", layers: array (minItems 2 containing "land" & "coast"), min_zoom: const 0, max_zoom: int 6..12}`, `additionalProperties: false` | Tolerates optional `"valley"` layer if ever added |
| `sources.relief` | `{file: const "relief.pmtiles", encoding: enum ["terrarium-value", "painted-rgba"], tile_format: enum ["png", "webp"], tile_size: const 256, min_zoom: const 0, max_zoom: int 6..12}` + `if/then` locking `("terrarium-value", "png")` and `("painted-rgba", "webp")` | `sources` may tolerate optional `"relief-detail"` in future `tile_schema: 1` readers; absent in v1 producer |
| `credits` | Array `minItems: 2` of `{id, source, attribution, liability?}` requiring `copernicus-worlddem-30` (with non-empty `liability`) and `natural-earth` | Exact verified Copernicus & Natural Earth notices |
| `reconstructed_areas` | `type: "array", maxItems: 0` *(v1 producer)* | Leaves reconstruction item schema completely open until historical reconstruction starts (`open-decisions.md`) |

**Example generated `data/map.json` (illustrative bounds; actual values always come from `coverage.yaml`):**

```json
{
  "tile_schema": 1,
  "camera_bounds": { "west": 34.0, "south": 12.0, "east": 60.0, "north": 32.0 },
  "data_extent": { "west": 33.0, "south": 11.0, "east": 61.0, "north": 33.0 },
  "min_zoom": 4.0,
  "max_zoom": 14.0,
  "sources": {
    "base": {
      "file": "base.pmtiles",
      "layers": ["land", "coast"],
      "min_zoom": 0,
      "max_zoom": 12
    },
    "relief": {
      "file": "relief.pmtiles",
      "encoding": "terrarium-value",
      "tile_format": "png",
      "tile_size": 256,
      "min_zoom": 0,
      "max_zoom": 12
    }
  },
  "credits": [
    {
      "id": "copernicus-worlddem-30",
      "source": "Copernicus DEM GLO-30 and Water Body Mask",
      "attribution": "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved.",
      "liability": "The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30."
    },
    {
      "id": "natural-earth",
      "source": "Natural Earth (1:110m, 1:50m, 1:10m land and coastline)",
      "attribution": "Made with Natural Earth. Public domain."
    }
  ],
  "reconstructed_areas": []
}
```

### `tile_schema` 1 Specification (for `map-data/README.md`)

1. **`data/base.pmtiles`** (PMTiles v3, gzip-compressed MVT vector tiles, build zooms `0..regional_max_zoom`):
   - Layer `land`: `MultiPolygon` geometry covering land (`>= 128` threshold of the `MASK_SIGMA_PX = 4.0` smoothed mask after approved cleanup inside `data_extent`, Natural Earth `110m`/`50m`/`10m` outside `data_extent` shrunk by `2 px` at zoom 12). Empty feature properties (`fields: {}`).
   - Layer `coast`: `MultiLineString` geometry at the `128` threshold contour of the smoothed land mask inside `data_extent`, plus Natural Earth coastlines outside `data_extent` shrunk by `2 px` at zoom 12. Empty feature properties (`fields: {}`).
   - Reserved optional layer `valley`: absent in v1 (`tile_schema: 1` readers tolerate `{"land", "coast"} <= layer_ids`).
2. **`data/relief.pmtiles`** (PMTiles v3, uncompressed `256×256` raster tiles, build zooms `0..regional_max_zoom`, **every tile covering `data_extent` present with zero skipped blank tiles** per ADR 0009 decision 5):
   - `encoding: "terrarium-value"` (`tile_format: "png"`, RGB where `R=128`, `G=trunc(d*255)`, `B=0`), or
   - `encoding: "painted-rgba"` (`tile_format: "webp"`, RGBA where RGB = configured ink and alpha = `trunc(d * max_alpha * 255)`).
   - Reserved optional archive `data/relief-detail.pmtiles`: absent in v1 (ADR 0009 decision 9), tolerated if ever introduced cleanly under `tile_schema: 1`.
3. **PMTiles v3 binary header vs JSON metadata:**
   - Binary 127-byte header stores `min_zoom = 0`, `max_zoom = regional_max_zoom`, `tile_type`, `tile_compression`, `internal_compression`, and `bounds` (`relief.pmtiles` `bounds ~= data_extent`).
   - PMTiles JSON metadata allowlist (`production`, no `"trial"`, `"variant"`, or `"mode"` keys): `"name": "seerah-map"`, `"tile_schema": 1`, `"attribution"`, `"fingerprint"`, `"tool_commit"`, `"data_commit"`, `"image"`, plus `"encoding"` on `relief.pmtiles` and `"vector_layers"` (sorted by `"id"`, `"fields": {}`) on `base.pmtiles`. (Transient `"format"`, `"minzoom"`, `"maxzoom"`, `"bounds"` written for `disk_to_pmtiles` are consumed into the PMTiles header.)

---

## Section 3 — Shared Host Packaging & Offline Ed25519 Signing (`tools/packages/`, D4)

### Package Structure & Layout

`tools/packages/` runs on the host (private keys never enter containers) and owns the shared manifest/signing/ZIP machinery required for both future `content` packages and `map` packages:

```text
tools/packages/
  README.md
  pyproject.toml            # pytest pythonpath = ["src"], testpaths = ["tests"]
  requirements.in           # cryptography, jsonschema==4.26.0, pmtiles==3.8.1, pytest
  requirements.txt          # uv pip compile --generate-hashes (releases >= 14 days old)
  src/seerah_packages/
    __init__.py
    __main__.py
    signing.py              # keygen (O_EXCL), Ed25519 sign, isolated single-key verify-before-parse
    archive.py              # shared build_package / verify_package, streaming ZIP, pmtiles.reader checks, Goal 9 guards
    cli.py                  # keygen, pack-map, verify subcommands (exit codes 0, 1, 2)
  tests/
```

*(Note: `tools/packages/requirements.txt` stays separate from unhashed `requirements-dev.txt` and runs in its own test job in `.github/workflows/ci.yml`; `.github/dependabot.yml` tracks `/tools/packages` via `pip`.)*

### Shared Manifest Schema: `content/schema/manifest.schema.json`

- `"additionalProperties": false` for producers (with `$comment` noting Android readers ignore unknown fields per ADR 0008 decision 4).
- Required fields:
  - `kind`: `"content"` \| `"map"`
  - `version`: integer `1..1_000_000` *(bounds monotonic floor against key-leak DoS)*
  - `min_app_version`: integer `>= 1` (`versionCode`)
  - `key_id`: string `^[a-z0-9][a-z0-9-]{0,63}$`
  - `source_commit`: string `^(?:[0-9a-f]{40}|[0-9a-f]{64})$` (`seerah-content` commit; maps from `report["provenance"]["data_commit"]`)
  - `created`: string `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` (also validated via `datetime.strptime` in Python)
  - `files`: non-empty array of `{path, size, sha256}`, where Python enforces lexicographically sorted unique prefix-free paths (`^data/...`, printable ASCII, no `.`/`..`/`//`/backslashes/trailing `/`), `size >= 0`, `sha256` matches `^[0-9a-f]{64}$`.
- Conditional properties by `kind`:
  - `if kind == "map" -> required: ["tool_commit", "image"]`, where `tool_commit` matches `^(?:[0-9a-f]{40}|[0-9a-f]{64})$` and `image` matches `^(?:sha256:[0-9a-f]{64}|local:[A-Za-z0-9:._-]+)$` (`pack-map` and `verify` enforce `image.startswith("sha256:")` strictly unless `--allow-local-provenance` is set).
  - `if kind == "content" -> not: anyOf: [required: ["tool_commit"], required: ["image"]]`.
- **Canonical manifest bytes:** `json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=False).encode("utf-8") + b"\n"`.

### CLI, Streaming ZIP, & Verification Contract (`python3 -m seerah_packages ...`)

Exit codes: `0` success, `1` packaging/verification/I/O check failed, `2` CLI usage/argument error.

1. **`keygen --key-id <id> --out-dir <dir> [--passphrase-fd <fd>]`**:
   - Validates `<id>` against `^[a-z0-9][a-z0-9-]{0,63}$`. Refuses to overwrite existing `<out-dir>/<id>.key.pem` or `<out-dir>/<id>.pub.json` (`os.open(..., O_WRONLY | O_CREAT | O_EXCL, 0o600)` for private key).
   - Prompts twice via `getpass` (or reads passphrase from `--passphrase-fd` for tests), writes PKCS#8 encrypted PEM (`BestAvailableEncryption`), and writes `<out-dir>/<id>.pub.json`:
     `{"key_id": ..., "algorithm": "Ed25519", "tink_variant": "NO_PREFIX", "public_key_base64": ...}` (standard padded Base64 of raw 32-byte public key; if optional `public_key_hex` is included, `verify` asserts exact byte equality on load).
2. **`pack-map --staging <dir> --version <int> --min-app-version <int> --key <pem> --key-id <id> --out <dir> [--created <iso>] [--passphrase-fd <fd>] [--previous-manifest <json>] [--allow-local-provenance] [--force]`**:
   - Refuses overwriting existing `<out>/seerah-map-<version>.zip` unless `--force` is passed (ADR 0008 decision 11 immutability).
   - If `--previous-manifest` is given, enforces `version > prev.version` and `prev.kind == "map"`.
   - Default `--created`: `datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")`; pinning `--created` yields byte-identical reproducible `.zip` outputs.
   - **Staging & Goal 9 checks:**
     - Refuses if `metadata.json` exists anywhere inside `<staging>`.
     - Reads `<staging>/report.json`; requires `"purpose": "production"` and non-`unknown` provenance (`data_commit`, `tool_commit`, `image`; refuses `local:*` unless `--allow-local-provenance`).
     - Requires `<staging>/data/` to contain only regular files `{base.pmtiles, relief.pmtiles, map.json}` (no symlinks or extra files).
     - Validates `data/map.json` (`<= 1 MiB`) against `map-data/schema/map.schema.json`.
     - Inspects `data/base.pmtiles` and `data/relief.pmtiles` via `pmtiles.reader.Reader` with pre-read header bounds (`magic == b"PMTiles"`, `version == 3`, `json_metadata_offset >= 127`, `json_metadata_bytes <= 1_048_576`, `offset + length <= size`): refuses `"trial" in meta`, requires `meta["tile_schema"] == 1`, `{"land", "coast"} <= layer_ids`, matching `maxzoom`/`minzoom == 0`/`bounds ~= data_extent`/`tile_type`/`tile_compression`/`addressed_tiles_count > 0`, and exact equality of `(fingerprint, tool_commit, data_commit, image)` across `report.json`, `base.pmtiles`, and `relief.pmtiles`.
   - **Shared `archive.build_package(...)` contract:** exposed cleanly so future `content` builders pass `kind="content"`, `compress_type` (`ZIP_DEFLATED` allowed for `content`, `ZIP_STORED` mandatory for `map`), and `max_manifest_bytes` (`1_048_576` for `map`, `4_194_304` for `content`).
   - **Deterministic streaming ZIP (`manifest.json` FIRST):**
     - Writes entries strictly in order: **`manifest.json`**, **`manifest.sig`**, then lexicographically sorted **`data/*`**. (Putting manifest + sig first allows streaming sequential verification/extraction on Android per ADR 0009 L253 while keeping O(1) central-directory lookup!)
     - Streams `data/` files in `1 MiB` chunks via `zf.open(zinfo, "w")`, forcing constant `ZipInfo`: `date_time=(2026, 1, 1, 0, 0, 0)`, `create_system=3`, `external_attr=0o100644 << 16`, fixed `compress_type`, `internal_attr=0`, empty `extra` & `comment`, `allowZip64=True`.
     - Writes atomically via `<zip>.tmp`, fails if final zip `> MAX_RELEASE_FILE_BYTES` (`2_147_483_647` bytes = `ZIP64_LIMIT`), swaps into final path, and runs **self-verification** (`verify_package`) using `private_key.public_key()` + `key_id` + `expected_kind="map"`.
3. **`verify --package <zip> --public-keys <json_or_dir> [--expected-kind {content,map}] [--allow-local-provenance]`**:
   - **Loads trusted keys (`1 <= len(keys) <= 2`):** accepts either a `.json` file (key object or list of 1–2 objects) or a directory of `*.json` files. Rejects `0` or `> 2` keys, duplicate `key_id`s, duplicate raw 32-byte public keys, or invalid algorithm/variant.
   - **ZIP container hardening (before reading any entry):**
     - Rejects non-empty `zf.comment`, duplicate entry names (`len(infolist) == len(names)`), encrypted entries (`flag_bits & 0x1`), Local File Header (LFH) vs Central Directory (CD) mismatches (`filename`, `compress_type`, sizes), non-`S_IFREG` Unix mode (`S_IFMT(external_attr >> 16) not in (0, S_IFREG)`), DOS dir flag (`external_attr & 0x10`), `info.is_dir()`, and invalid paths (`.`/`..`/empty segments/leading `/`/trailing `/`/backslashes/`\x00`/control chars).
     - Enforces `compress_type == ZIP_STORED` and `file_size == compress_size` on `manifest.json`, `manifest.sig`, and (when `kind == "map"` / `expected_kind == "map"`) all entries.
   - **Isolated single-key verify-before-parse + `key_id` binding:**
     - Bounded reads: `manifest.sig` via `.read(65)` (must be `64` bytes), `manifest.json` via `.read(max_manifest_bytes + 1)` (must be `<= max_manifest_bytes`, `4 MiB` cap before `kind` is known or `1 MiB` when `expected_kind == "map"`).
     - Tries each isolated single-key Ed25519 verifier in turn on `(raw_sig, raw_manifest_bytes)` **before calling `json.loads()`**.
     - Only after a trusted key verifies does it call `json.loads(raw_manifest_bytes)`, validate against `manifest.schema.json` (`+ image` `sha256:` enforcement unless `--allow-local-provenance`), verify `manifest["key_id"] == verifying_key_id`, verify `--expected-kind` (when set), verify exact calendar `created`, and verify `1 MiB` limit on `kind == "map"`.
   - **Manifest completeness & streamed payload verification:**
     - Requires `set(names) == {"manifest.json", "manifest.sig"} | {f["path"] for f in manifest["files"]}`, sorted/unique/prefix-free `files[].path`, and exact order (`manifest.json`, `manifest.sig`, sorted `data/*`) optional warning or check.
     - Streams each `data/...` entry in `1 MiB` chunks up to `entry["size"] + 1` bytes, verifying exact byte length and SHA-256.
     - When `kind == "map"`: reads `data/map.json` bounded (`<= 1 MiB`), validates `map.schema.json`, inspects `data/base.pmtiles` and `data/relief.pmtiles` via `pmtiles.reader.Reader` over a seekable `ZIP_STORED` byte-slice reader (`pmtiles_reader` over raw archive offsets), asserting `"trial" not in meta`, `tile_schema == 1`, `{"land", "coast"} <= layer_ids`, header zooms/bounds/types match `map.json`, and provenance fields across both PMTiles match `manifest.json`.

### Key Rotation & Signing Runbook (`docs/signing-runbook.md` in Phase 3)

Covers: key generation (`keygen`), encrypted private key storage & offline backup, embedding public keys in Android resources, dual-key rotation (`current` + `next` in app release before signing with `next`), and key-compromise response per [ADR 0007](../adr/0007-content-formats-compatibility-and-signing.md#decision) and [ADR 0008](../adr/0008-update-hosting-and-index.md#consequences): an app update dropping a compromised `key_id` **must** both clear the ADR 0008 index `serial` floor **and** discard/reset the local installed package `version` floor for any package signed by the dropped `key_id` so a leaked-key high `version` cannot permanently block honest updates.

---

## Section 4 — Private Cleanup Edits Plan (`seerah-content/map/cleanup.yaml`)

Executed only after owner approves `D1–D7` and Phases 1–2 merge cleanly:

- **Precondition:** author `seerah-content/map/coverage.yaml` and `seerah-content/map/sources.lock` (using working trial box or owner-finalized bounds) so Style C verification renders can execute over real cells.
- **Priority 1 (Hijaz Red Sea corridor near v1 events):**
  1. Jeddah Islamic Port breakwaters & container piers (`remove-land`)
  2. North Jeddah / Obhur Corniche artificial piers & marina spits (`remove-land`)
  3. King Abdullah Economic City (KAEC) dredged port channels (`fill-water` under D1 Option B; verify empirically in Phase 4 whether channels carry `wbm == 1` vs `wbm == 2/3`) and protruding jetty (`remove-land`)
  4. Yanbu industrial port reclaimed piers & breakwaters (`remove-land`)
- **Priority 2 (Unmistakable Gulf mega-reclamation visible peninsula-wide):**
  1. Dubai: Palm Jumeirah, Palm Jebel Ali, The World, Dubai Maritime City, Deira Islands (`remove-land`); Jebel Ali Port excavated harbour basin (`fill-water` under D1 Option B, verify WBM class + boundary halos empirically)
  2. Bahrain: Durrat Al Bahrain crescent islands, Amwaj / Diyar Al Muharraq / Northern City reclaimed islands, Khalifa Bin Salman Port & Arab Shipbuilding and Repair Yard (ASRY) jetties (`remove-land`)
  3. Doha/Qatar: The Pearl-Qatar, Lusail artificial islands, Hamad International Airport reclaimed peninsula (`remove-land`)
- **Rule compliance:** every polygon is committed first with `review: draft`, verified visually via side-by-side before/after PNG crops rendered over Style C + WBM coast in scratch, and promoted to `review: approved` **only by the owner**.

---

## Phased Implementation Roadmap & Verification Plan

| Phase | Deliverables | Key Checks (All Must Pass Before Merge) |
| --- | --- | --- |
| **Phase 1: Cleanup Edits (`tools/map/`)** | `cleanup.schema.json`, `load_cleanup()` (`config.py`), `CleanupEdit` + `apply_cleanup()` + `validate_cleanups()` (`edits.py`), `blockstage.py` (`render_block`) integration, `tools/map/tests/fixtures/` fixture | Unit tests (`remove-land` zeroing both WBM & elevation -> zero coastal blur bleed; `fill-water` flattening ocean pixels only); unapproved skip log; overlap & outside-`data_extent` rejection; GDS/ring-closure/bounds errors; **seam test** (single-block vs multi-block with cleanup polygon crossing block edge + `z6..z11` coast seam check); `map` CI passes |
| **Phase 2: Production Build & `map.json` Staging** | `coverage.schema.json`, `map-data/schema/map.schema.json`, `BuildTarget` adapter, `grid.py` & `base.py` rectangular `Bounds` / `widest_half_screen_dp` / `MakeValid` updates, `cli.py` `--coverage` + `validate` + `fetch.py` `sources.lock` params, `pack.py` (`write_metadata_file=False`), `report.py` (`purpose: "production"` + nullable `size_by_zoom`), `ZIP64_LIMIT` check, atomic `/out` staging, `map-data/example-production/` (reusing `example.lock` cells), `.github/workflows/map.yml` (`paths:` + cache key), `THIRD_PARTY_NOTICES.md` (Copernicus wording), `map-data/README.md` (`tile_schema: 1`), `tools/map/README.md`, `docs/README.md`, `CHANGELOG.md`, **plus one-off upstream `INFO/` licence check** | Determinism test (two runs produce byte-identical `data/{base.pmtiles,relief.pmtiles,map.json}`, excluding timing fields in `report.json`); both `terrarium-value` and `painted-rgba` production builds; `"trial" not in meta` & no `metadata.json`; cache SHA-256 failure test on build; rectangular `Bounds` + asymmetric-margin `fade_deg` failure tests; `< 2 GiB` (`MAX_RELEASE_FILE_BYTES`) failure test; `map.schema.json` + `reconstructed_areas: maxItems 0` checks; existing trial tests remain green |
| **Phase 3: Host Packaging & Offline Ed25519 Signing (`tools/packages/`)** | `content/schema/manifest.schema.json`, `tools/packages/` (`signing.py`, `archive.py`, `cli.py`, `__init__.py`, `__main__.py`, `pyproject.toml`, `requirements.in`, `requirements.txt`, `README.md`), `docs/signing-runbook.md`, `.github/workflows/ci.yml` (`tools/packages` job), `.github/dependabot.yml`, `THIRD_PARTY_NOTICES.md`, `tools/README.md`, `content/README.md`, `docs/README.md`, `docs/roadmap.md`, `CHANGELOG.md` | **Goal 9 refusal tests** (staging `metadata.json`, `"trial"` in PMTiles, missing `tile_schema: 1`, `local:` / `unknown` provenance); ZIP determinism (`--created`) & `> 2_147_483_647` failure tests (monkeypatched limit); **stream order check (`manifest.json`, `manifest.sig` first)**; ZIP security tests (`zip.comment`, encryption flag, LFH!=CD, duplicate, symlink/FIFO/DOS dir, `data/a`+`data/a/b`, non-`ZIP_STORED`, bounded reads); **isolated single-key verify-before-parse test** (invalid sig on corrupt JSON fails before `json.loads` spy; key A signing `key_id: B` rejected; `> 2` keys rejected); cross-`kind` replay test; RFC 8032 / Tink `NO_PREFIX` fixed test vector fixture checked in Python (`tmp_path` ephemeral test keys so `detect-private-key` passes); pure-Python minimal PMTiles v3 fixture tests in `ci.yml` + `pre-commit` pass |
| **Phase 4: Private Coastal Cleanup (`seerah-content`)** | `seerah-content/map/coverage.yaml` + `sources.lock` + `cleanup.yaml` Priority 1 & Priority 2 (`review: draft` -> side-by-side scratch renders -> owner `approved`) | `seerah_map validate --coverage ...` green on `seerah-content`; empirical `wbm` class check at KAEC/Jebel Ali & bluff halo check; side-by-side before/after PNG inspection crops of Jeddah, KAEC, Yanbu, Dubai, Bahrain, Doha before owner marks `approved` |

---

## Known Risks and Mitigations

| Risk | Consequence | Mitigation |
| --- | --- | --- |
| **Reclaimed-land elevation bleed** | Un-zeroed `elevation` on removed islands creates dark slopes along adjacent coast via `blur_px = 7.0` | `remove-land` explicitly zeroes `elevation` (`0.0 m`) alongside setting `wbm = 1` before `fill_invalid` and `land_mask` |
| **On-device update storage (~3× package size)** | Per [ADR 0009](../adr/0009-map-data-pipeline.md#consequences), installing an update alongside old map + `.zip` temporarily uses ~3× size (~`4.1 GiB` for `1.37 GiB` package) | Keep regional size disciplined via step-4 zoom/margin choices (`open-decisions.md`), keep `ZIP_STORED`, and put `manifest.json`/`manifest.sig` first to enable streaming extraction on Android |
| **Multi-key Tink verify / key confusion** | Multi-key `NO_PREFIX` `KeysetHandle` hides signing key or parses JSON before crypto check | Enforce isolated single-key verifiers on raw bytes before `json.loads()`, then assert `manifest["key_id"] == verifying_key_id` |
| **Accidental publishing of trial data** | Unsigned/unapproved or `purpose: trial` tiles reach users ([Goal 9](map-trial-build.md#goals)) | Separate schemas (`coverage.schema.json` vs `profile.schema.json`), `"trial"` absent in production PMTiles, `report.json` `"purpose": "production"`, `metadata.json` refusal, `tile_schema == 1` check |
| **`2 GiB` ZIP release limit** | `full-value` (`~1.365 GiB`) + `base.pmtiles` (`4.4 MiB`) + overhead sits around `1.37 GiB` (`~646 MiB` headroom today) | Enforce `sum(data_files) + ZIP_BUDGET_OVERHEAD` (`1 MiB`) and final `.zip` size `<= 2_147_483_647` bytes (`ZIP64_LIMIT`) |
| **Copernicus `INFO/` notice mismatch** | Attribution / Article 6(c) compliance gap before first release | Pre-release Phase 2 upstream check against a real `INFO/` licence file from `copernicus-dem-30m` S3 before finalizing credits |
| **Key compromise / `version` lockout** | Leaked key signs high `version` locking out honest updates ([ADR 0008](../adr/0008-update-hosting-and-index.md#consequences)) | Cap `manifest.version <= 1_000_000`; `signing-runbook.md` mandates dropping compromised key packages + resetting installed version floor alongside index `serial` floor |

---

## Additional Resources

- **Accepted ADRs:** [ADR 0006](../adr/0006-open-source-code-and-closed-content.md), [ADR 0007](../adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../adr/0008-update-hosting-and-index.md), [ADR 0009](../adr/0009-map-data-pipeline.md)
- **Open Decisions:** [docs/open-decisions.md](../open-decisions.md)
- **Map Trial & Consolidated Review:** [Map Trial Build Spec](map-trial-build.md), [Phase 3 Plan](map-trial-phase-3-plan.md), [Step 4 Report](../map-trial/step-4.md), [Consolidated EGM Review](egm-review-v1-map-pipeline/consolidated_review_report.md)
- **PMTiles v3 Specification:** [Protomaps PMTiles v3](https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md)
- **Copernicus WorldDEM-30 Licence:** [Copernicus WorldDEM-30 Terms](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf)
- **Google Tink Ed25519:** [Tink Java/Android Setup](https://developers.google.com/tink/setup/java)
