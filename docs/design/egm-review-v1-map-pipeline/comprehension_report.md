# Comprehension Review Report: v1 Map Pipeline Spec

**Target:** `docs/design/v1-map-pipeline-spec.md`
**Reviewer:** `egm-comprehension`

---

## What this proposes

This specification defines a four-phase v1 production map pipeline extending `tools/map/` and adding a new host-side Python packaging tool (`tools/packages/`). It proposes: (1) adding discrete Copernicus Water Body Mask (`wbm`) coastal cleanup edits (`remove-land` and optionally `fill-water` under D1) via a dedicated `cleanup.schema.json` (D2), (2) introducing a production `coverage.yaml` / `coverage.schema.json` and `--coverage` CLI mode (D3) to stage `tile_schema: 1` archives (`base.pmtiles`, `relief.pmtiles`, `data/map.json`) plus `build-provenance.json` under the `2 GiB` single-file limit without `"trial": true` markers, (3) building deterministic, offline Ed25519-signed `seerah-map-<version>.zip` packages and strict verify-before-parse logic on the host (D4) compatible with Google Tink `NO_PREFIX` on Android, and (4) drafting private Hijaz and Gulf coastal cleanup polygons in `seerah-content/map/cleanup.yaml`.

## How the existing system works, per this document

Currently, `tools/map/` runs inside a pinned Docker container (`seerah-map`) using trial inputs (`profile.schema.json`, `edits.schema.json` `trial-0`) to warp Copernicus DEM elevation and WBM blocks, smooth the land mask (`MASK_SIGMA_PX = 4.0`), render Style C hillshade relief (`style.py` -> `pack.py` -> `relief.pmtiles`), extract vector `land`/`coast` layers (`base.py` -> `base.pmtiles`), and mark outputs with `"trial": true` in `metadata.json` and PMTiles metadata. On Android, `:core:map` consumes `"base"` and `"relief"` PMTiles sources (with MapLibre zooms offset by `-1` from 256-px relief build zooms) while `android/app/src/benchmark/` runs the trial viewer until deferred Oppo A55 and Huawei MatePad 11.5 tests close the map trial. Neither host packaging/signing (`tools/packages/`) nor Android's `:core:content` / `:core:update` download and verification layers exist yet, and historical reconstruction edits and valleys remain out of v1 scope.

---

## Findings

### Blockers

#### C-1

- **Severity:** Blocker
- **Affected Scope:** `build-provenance.json` — Overview D3 (line 31), Architecture Overview (line 107), Section 1 (line 137), Section 2 (lines 185, 202, 208), Section 3 `pack-map` (line 309)
- **Discrepancy Description & Rationale:** `build-provenance.json` is the handoff contract emitted inside Docker by `tools/map` (Phase 2) and validated on the host by `tools/packages/` `pack-map` (Phase 3), yet **no schema, field list, or example JSON is defined anywhere in the document**. The spec only scatters parenthetical hints (`commits, digest, edits, stats`, `edits.skipped`, `inside/outside byte split`) and refers to `missing fields` in line 309 without naming them. Worse, commit keys already drift across adjacent sections (`"data_commit"` in PMTiles metadata on line 269 vs `"source_commit"` in `manifest.json` on line 293), and line 313–316 never specifies whether `pack-map` must verify that PMTiles embedded provenance matches `build-provenance.json`. Phase 2 and Phase 3 cannot be implemented independently without guessing this interface.

#### C-2

- **Severity:** Blocker
- **Affected Scope:** Host vs. Container Boundary, `tools/map/run.py`, CLI `--coverage`, and `map-data/example/` — Architecture Overview (lines 100–103), Section 2 (lines 167, 192–208), Phase 2 (line 365)
- **Discrepancy Description & Rationale:** There are four intersecting ownership and path contradictions across the host/container boundary:
  - **Missing host launcher spec (`tools/map/run.py`):** The Architecture diagram shows `tools/map/run.py` as the entry point (line 100), but Section 2 only specifies container `cli.py` and Phase 2 (line 365) never mentions `run.py`. It is unstated how `run.py` adds `--coverage`, passes commits/digest env vars into `--network none` builds, enforces clean public/private checkouts (ADR 0009 decision 8), or maps host `map-data/dist/` (line 103) to container `/out/` (line 204).
  - **Impossible write to read-only `/data` mount:** Line 197 states `fetch --coverage coverage` `"verifies/writes /data/sources.lock (if missing, writes /out/coverage.lock and instructs owner to copy to seerah-content/map/sources.lock)"`. Container `/data` is a read-only mount (`docs/design/map-trial-build.md` line 186), so `fetch` cannot write `/data/sources.lock`.
  - **Opaque `--coverage coverage` naming mismatch:** Lines 196–198 pass `--coverage coverage` while hardcoding `/data/coverage.yaml`, `/data/cleanup.yaml`, and `/data/sources.lock`, yet `fetch` emits `/out/coverage.lock` and tells the owner to rename it to `seerah-content/map/sources.lock`. Unlike `--profile <P>`, it is unclear whether `--coverage` is a boolean flag, a file stem, or a relative path.
  - **CI example layout collision:** Line 167 mentions `map-data/example/coverage.yaml` and Phase 2 (line 365) places a `"synthetic production example under map-data/example/"` while keeping `"existing trial tests remain green"`. Currently `map-data/example/` holds trial files (`example.yaml`, `edits.yaml`, `example.lock`), and CI mounts `map-data/` at `/data` (placing the example at `/data/example/`, not `/data/coverage.yaml`). How trial and production examples coexist and are addressed by `--coverage` is unexplained.

#### C-3

- **Severity:** Blocker
- **Affected Scope:** Production PMTiles `"trial"` marker & `manifest.schema.json` `image` validation — Verified Fact 4 (line 69), Section 2 (lines 202, 269), Section 3 (lines 298, 309, 314, 317)
- **Discrepancy Description & Rationale:** Two security/validation rules contradict themselves between producer and verifier:
  - **PMTiles `"trial"` key (`false` vs. absent):** Line 69 allows `"False / omitted metadata marker"`, and lines 202 and 269 only forbid `"trial": true`. However, `pack-map` (line 314) says `Refuse if "trial" key exists and is truthy (or present at all in production metadata)`. Leaving `"or present at all"` undecided means Phase 2 could write `"trial": false` and Phase 3 could reject every staging build.
  - **`manifest.schema.json` `image` regex vs. `--allow-local-provenance`:** Line 298 specifies `image` pattern `^sha256:[0-9a-f]{64}$ in production; explicit test flag needed for local: in dev fixtures`, and line 309 lets `--allow-local-provenance` accept `"local:*"` images in `build-provenance.json`. Yet line 317 validates `manifest.json` against `content/schema/manifest.schema.json`. A static JSON Schema cannot switch regexes based on a CLI flag: either `manifest.schema.json` rejects `local:*` (breaking dev fixtures), or it permits `local:*` (contradicting line 298 unless the Python validator enforces `^sha256:` strictly when `--allow-local-provenance` is off).

---

### Majors

#### C-4

- **Severity:** Major
- **Affected Scope:** Section 3 `pack-map` & `verify` determinism and automation — Lines 287, 302–306, 316–318, 325–334, 366
- **Discrepancy Description & Rationale:** Several packaging and verification contracts have unresolved branches or missing mechanics:
  - **Undecided `manifest.json` canonical serialization (line 317):** `"sort_keys=True or fixed schema key order"` leaves two incompatible byte layouts for a file whose exact raw bytes are signed by `manifest.sig`.
  - **Undecided `manifest.schema.json` `additionalProperties` (line 287):** `"additionalProperties: false (or explicit forward-compatibility note ...)"` should state one concrete schema setting (`additionalProperties: false` for producers).
  - **Missing `--passphrase-fd` on `pack-map` (lines 306, 318 vs. 304, 366):** `keygen` documents `--passphrase-fd` for automation tests, whereas `pack-map` only reads passphrases via `getpass` and omits `--created` default semantics, leaving CI/Phase 3 automation of `pack-map` unspecified.
  - **Incomplete `keygen` & `verify` contracts:** `keygen` (lines 302–305) does not name its output filenames inside `--out-dir` or overwrite-refusal rules; `verify --public-keys <json_or_dir>` (lines 325–331) assumes at most two keys (`current` + `next`) without defining the single-JSON vs. directory file format or rejecting `> 2` keys; `pack-map` (line 316) checks `max_zoom` on `relief.pmtiles` but does not check `maxzoom` on `base.pmtiles` or `minzoom == 0` on `relief.pmtiles`; and `verify` (line 334) inspects PMTiles inside the `.zip` without requiring `compress_type == ZIP_STORED` when `kind == "map"`.

#### C-5

- **Severity:** Major
- **Affected Scope:** Ungrounded & Misattributed Shorthand (`G9`, `ADR 0008 decision 12`, `<fp8>`, `Step 1A`, `N/E` vs `NE`, `ZIP_BUDGET_OVERHEAD`, `Box`, `_render_coarse_block()`) — Throughout doc (lines 20, 31, 32, 38, 101, 103, 111, 138, 139, 173, 174, 190, 203, 204, 280, 307, 339, 366, 376)
- **Discrepancy Description & Rationale:** A fresh reader consulting this spec alongside ADR 0007, ADR 0009, `step-4.md`, and `open-decisions.md` hits multiple undefined or misattributed references:
  - **`ADR 0009 G9` / `G9` (7 occurrences):** `G9` does **not** exist in ADR 0009 (which only has Decisions 1–14). It comes from Goal 9 of `docs/design/map-trial-build.md` (not even linked on line 20 or in Section 3).
  - **`ADR 0008 decision 12` (line 339):** ADR 0008 only has Decisions 1–11. The high-version/serial leak risk sits in ADR 0008's Consequences (and Decision 5), not a "decision 12".
  - **`<fp8>` (lines 31, 103, 204):** Never defined as the 8-character hex prefix of the build fingerprint, and line 201 omits the hash algorithm (SHA-256) and repeats `"tool_commit, image_digest, and source tree"` without explaining how `"source tree"` differs from `tool_commit` (when ADR 0009 decision 8 forbids uncommitted changes) or whether `peninsula_outline` (line 185) is hashed.
  - **`Step 1A` (line 38):** Unlinked, undefined label (`"implemented in Step 1A"`) absent from `step-4.md` and the companion docs, leaving unclear what memory-trimming/prefetch changes already exist vs. remain untested on Oppo A55.
  - **`N/E` vs `NE` (lines 101, 173, 174):** Both undefined; `N/E` means Northern/Eastern hemisphere (`N/E WebMercatorQuad`), while `NE` two paragraphs earlier means `Natural Earth` (`pinned cells/NE`).
  - **Undefined code constants/symbols:** `ZIP_BUDGET_OVERHEAD` (line 203, no byte budget given), `Box` (line 190), `_render_coarse_block()` (line 138 — especially confusing since line 199 says `--mode` is refused and production always uses `pyramid`), and `edit_reach_m` (line 139).

#### C-6

- **Severity:** Major
- **Affected Scope:** Zoom Conventions, `coverage.yaml`, `check_base_coverage`, and `data/map.json` — Verified Fact 8 (lines 90–91), Section 2 (lines 175–190, 219–235, 262–264)
- **Discrepancy Description & Rationale:** Zoom semantics are incomplete and leave an open implementation fork:
  - **Unlabeled mixed zoom systems in `data/map.json` (lines 219–235):** Top-level `"min_zoom": 4.0, "max_zoom": 14.0` are MapLibre camera zooms, while `"sources.base"` and `"sources.relief"` (`"min_zoom": 0, "max_zoom": 12`) are 256-px build zooms (`0..regional_max_zoom`). Neither `map.json` nor its section prose explains this difference, and `sources.base` omits `"tile_size"`.
  - **`base.pmtiles` zoom mapping unexplained (lines 91, 190, 262):** Fact 8 only states `"where MapLibre zoom z displays build zoom z + 1 on the 256 px relief source"`. It never states how vector source `base.pmtiles` maps to MapLibre zooms, why `check_base_coverage` stops at `regional_max_zoom - 1` (line 190) instead of checking up to `max_zoom` (`ADR 0009` decision 7 says `"at every zoom"`), or why `min_zoom < regional_max_zoom` is not validated in `coverage.schema.json` (if `min_zoom >= regional_max_zoom`, the coverage loop is empty).
  - **Undecided `check_base_coverage` algorithm (line 190):** Leaves `"against the minimum uniform margin min(side_margins) (or generalize check_base_coverage to accept an explicit rectangular data_extent Box directly — recommended)"` as an unpicked option instead of specifying the rectangular check.
  - **`fade_deg` guard edge case (line 179):** `"if every side margin > 0, fade_deg <= min(side_margins)"` skips validation completely if one side has `0` margin and others have `> 0`, allowing a non-zero `fade_deg` to enter `camera_bounds` on the zero-margin edge.

#### C-7

- **Severity:** Major
- **Affected Scope:** D1 (`fill-water`) and D2 (`sources[].kind: "atlas"`) vs. ADR 0009 Decisions 2 & 3 — Owner Decisions (lines 29–30), Section 1 (lines 132, 156)
- **Discrepancy Description & Rationale:**
  - **D2 table vs. prose vs. ADR 0009:** D2 (line 30) describes `sources` `"with imagery/dated-data kinds"`, matching ADR 0009 Decision 2's cleanup evidence (`"Modern imagery or dated data"`). Line 156 instead defines `kind enum ["imagery", "dated-data", "atlas"]` while still claiming `(matching ADR 0009 decision 2 evidence column: "Modern imagery or dated data")` — even though ADR 0009 Decision 2 assigns `"Seerah atlases"` exclusively to **Reconstruction** edits, not Cleanup. (Line 156 also implies reusing `content/schema/content.schema.json`'s `source`, whose `kind` enum does not include `"imagery"` or `"dated-data"`.)
  - **D1 Option B (`fill-water`) vs. ADR 0009 Decisions 2 & 3:** ADR 0009 Decision 2 states Cleanup `"Never adds shape"`, and Decision 3's table defines Land/coastline as Copernicus WBM `"with 'remove land' edits"`. `fill-water` adds land pixels (`wbm = 0`, `0.0 m`) back into dredged ocean basins (`wbm == 1`) without noting whether this clarifies `"Never adds shape"` (flat `0 m` restoration vs. 3D relief) or amends ADR 0009 Decision 3.

#### C-8

- **Severity:** Major
- **Affected Scope:** Ambiguity on v1 Delivery Scope, Missing Phases/Transitions, and Trial Status — Lines 40, 44–48, 212, 257–270, 345, 360–368
- **Discrepancy Description & Rationale:** A reader cold-reading this spec can misjudge what is verified today and what still must happen before v1 ships:
  - **Overstated Step 4 validation of `option-c` (line 47):** Stating `"Step 2 and Step 4 validated option-c (terrarium-value) and painted fallback, so Option (b) is not built here"` blurs the fact from `step-4.md` line 11 and spec line 40 that **Oppo A55 (4 GB, deciding Trial Items 3 & 11) and Huawei MatePad 11.5 remain unmeasured**, and Galaxy A54 PSS sat at `208–226 MB` (`301 MB` on free pan). More accurately, Option (b) (`relief-detail.pmtiles`) only tiles approved reconstruction edits per ADR 0009 Decision 4, which wait for a later phase regardless.
  - **Phased Roadmap stops short of producing/shipping v1 map & missing `coverage.yaml` phase:** Phases 1–4 end with `seerah-content/map/cleanup.yaml` (line 367), omitting: (a) creating `seerah-content/map/coverage.yaml` and `sources.lock` (yet Phase 4 requires Style C visual verification renders that depend on them!), (b) closing the deferred Oppo A55 / MatePad 11.5 trial gate before locking `coverage.yaml` and shipping `seerah-map-1.zip`, and (c) Android `:core:map` reading `data/map.json` (line 212 says `:core:map` reads it and ADR 0009 line 242 lists `"the app's reading of map.json"` as new work, yet line 48's Non-Goals only exclude `:core:content`, `:core:update`, and CDN publishing — leaving `:core:map`'s `map.json` work unowned).
  - **Python-to-Kotlin Tink cross-check timing (line 48 vs. line 366):** Phase 3 requires `"Tink NO_PREFIX fixed test vector cross-check"` while line 48 excludes Android verifier changes, leaving unclear whether Phase 3 runs a real Kotlin/Tink verification test (required by ADR 0007 Consequences line 87) or only checks a static RFC 8032 / Tink vector in Python until `:core:content` / `:core:update` land.
  - **`tile_schema: 1` `relief-detail.pmtiles` inconsistency (line 257 vs. 260–270):** Line 257 says `relief-detail` is tolerant in future `tile_schema: 1` readers, whereas the `tile_schema 1 Specification` (lines 260–270) documents reserved `valley` but omits `relief-detail.pmtiles` (unlike ADR 0009 Decision 9).

#### C-9

- **Severity:** Major
- **Affected Scope:** Phase 2 Copernicus `INFO/` Licence Gate — Verified Fact 6 (line 82), Phase 2 (line 365), Known Risks (line 379)
- **Discrepancy Description & Rationale:** Lines 82, 365, and 379 mandate a Phase 2 check that reads and verifies the licence file inside a real cached Copernicus cell's `INFO/` folder in `seerah-map-cache`. However, per `docs/design/map-trial-build.md` (lines 117, 199), `fetch` only downloads `*_DEM.tif` and `AUXFILES/*_WBM.tif` (not `INFO/`), and `build` runs with `--network none`. Neither Section 2 (`fetch` / `sources.lock`) nor Phase 2 explains how `INFO/` gets into `seerah-map-cache` or whether this is a manual one-off check outside CI.

---

### Nits

#### C-10

- **Severity:** Nit
- **Affected Scope:** Verified Fact 4 (line 69)
- **Discrepancy Description & Rationale:** `"assert meta.get("trial") is not True failure as an error"` is grammatically garbled; reword to `"treat meta.get('trial') is not True as a verification failure"` (and align with the absent-key rule once resolved).

#### C-11

- **Severity:** Nit
- **Affected Scope:** ADR 0007 decision 3 attribution — Overview (line 20), Section 3 (line 280)
- **Discrepancy Description & Rationale:** Citing `"ADR 0007 decision 3"` for sharing `tools/packages/` between `tools/map/` and future content packaging is slightly stale: ADR 0007 decision 3 specified a single tool, and **ADR 0009** (Decision 8 and *Amends* ADR 0007, line 215) is what separated `tools/map/` while mandating a shared manifest module in `tools/`.

#### C-12

- **Severity:** Nit
- **Affected Scope:** Minor terminology, link, and phrasing rough edges — Lines 9, 66, 113, 264, 281, 354, 367
- **Discrepancy Description & Rationale:**
  - `EGM` (line 9) and `ASRY` (line 354) are unexpanded acronyms; `Style C` (lines 66, 72) is unlinked on first use.
  - Line 264 (`plus Natural Earth coastlines outside`) right after line 263 (`outside data_extent shrunk by 2 px at zoom 12`) should repeat `shrunk by 2 px at zoom 12` so coast doesn't read as clipped to unshrunk `data_extent`.
  - Line 281 leaves host venv installation ambiguous (`requirements-dev.txt or .venv`), and line 113 (`dist/seerah-map-<version>.zip`) vs. line 103 (`map-data/dist/map-staging-<fp8>/`) leaves unclear whether `dist/` is repo-root `./dist/` or `map-data/dist/`.
  - Line 367 (`validate / seerah_map config validation green on seerah-content`) blurs `tools/validate_content.py` (content YAML) with `seerah_map` (map YAML).
