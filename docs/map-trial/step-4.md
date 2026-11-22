<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Map Trial Step 4: Full Arabian Peninsula (`trial/full`)

Step 4 builds the full Arabian Peninsula extent (`trial/full`, `12–32° N, 34–60° E` camera bounds with `1.0°` margin -> `11.0–33.0° N, 33.0–61.0° E` data extent) for both **`value`** (`option-c` primary) and **`painted`** (`--mode pyramid`, fallback) variants, measures workstation resource consumption, tests full-extent rendering and performance on the Galaxy A54 (`SM-A546E`), audits modern coastal/urban/volcanic features for Decision 13 item 9, and records best-practice recommendations across items 1, 6, 8, 9, 10 and 11 of [docs/design/map-trial-build.md](../design/map-trial-build.md).

> [!NOTE]
> As recorded in [step-1.md](step-1.md) and [step-2.md](step-2.md), the Oppo A55 (4 GB RAM, key memory verification for items 3 and 11) and Huawei MatePad 11.5 remain temporarily unavailable. Formal `map/coverage.yaml` zoom/encoding locks are deferred until those devices are tested and map preparation begins.

## Build Provenance and Parameters

- **Tool commit (`seerah`):** `cb5803689d595f031e7700278ddb25c097c5eaea` (built from a clean detached worktree matching Steps 2–3)
- **Data commit (`seerah-content`):** `15a4d7c46d5f797de65a606f99af4188d134d6ac` (`full-value-9c482d9e`) and `a7a6e62a4a458101f861d152a7d671f076f3a41d` (`full-painted-5b3d064d`); `git diff` confirmed `full.yaml`, `full.lock`, `edits.yaml`, `peninsula.geojson`, and `camera-path.json` were identical across both commits.
- **Container image:** `ghcr.io/mohdfarag/seerah-map:5498b0a66ee99ccb` (`sha256:91de3a5133dfce734608223a425cbef9ec9389cbbf8e819dcfe64a2237a69230`)
- **Container limits:** `8,192 MiB` memory cap, `4` workers (`2,048 MiB`/worker budget, `gdal_cachemax_mib: 256`), `--network none` during `build`, runs executed sequentially (`value` then `painted`).
- **Grid & edits:** `1,435` blocks (`block_zoom: 9`), `122,524` expected / `123,668` addressed tiles (`z0–z12`), `0.25°` fade, `webp_quality: 80`, all `5` synthetic test edits applied (`skipped: []`).

## Full-Extent Workstation Measurements (Items 1 and 6)

| Metric & Gate | `full-value-9c482d9e` (Primary `option-c`) | `full-painted-5b3d064d` (Fallback `pyramid`) | Gate / Limit | Status |
| --- | --- | --- | --- | --- |
| **`relief.pmtiles` (`z0–z12`)** | **`1,465,644,155 B`** (`1,397.75 MiB`, **`1.365 GiB`**, `68.25%` of `2 GiB`, `650.25 MiB` headroom) | **`605,108,616 B`** (`577.08 MiB`, **`0.564 GiB`**, `28.18%` of `2 GiB`, `1,470.92 MiB` headroom) | `<= 2 GiB` (`2,147,483,648 B`) | **PASS** |
| **Estimated `relief.pmtiles` (`z0–z11`)** | `~565,582,453 B` (`~539.38 MiB`, `38.59%` of `z0–z12` payload) | `~250,447,501 B` (`~238.85 MiB`, `41.39%` of `z0–z12` payload) | `<= 2 GiB` | **PASS** |
| **`base.pmtiles`** | `4,624,743 B` (`4.41 MiB`) | `4,624,743 B` (`4.41 MiB`) | — | — |
| **Inside vs outside outline (`z0–z12` payload)** | `845,813,578 B` inside (`56.72%`) / `645,265,191 B` outside (`43.28%`, sum `1,491,078,769 B`) | `323,053,904 B` inside (`52.38%`) / `293,672,538 B` outside (`47.62%`, sum `616,726,442 B`) | Item 1 (`1.0°` margin) | Recorded |
| **Summed tiles (`z0–z10` / `z11` / `z12`)** | `204,559,408` / `370,838,095` / `915,681,266 B` | `92,518,558` / `162,737,428` / `361,470,456 B` | — | — |
| **Unique PMTiles contents (`pmtiles show`)** | `87,942` / `123,668` addressed (`90,362` entries) | `70,497` / `123,668` addressed (`76,447` entries) | — | — |
| **Wall time (`base` / `block` / `tile` / `pack`)** | `792.69 / 725.74 / 715.90 / 7.73 s` (**`2,242.05 s` = `37.37 min`**, `5.19%`) | `785.80 / 757.92 / 1,857.52 / 3.72 s` (**`3,404.95 s` = `56.75 min`**, `7.88%`) | `<= 12 h` (`43,200 s`) | **PASS** |
| **Temp disk (`work_area_bytes_after_tiling`)** | **`2,853,373,244 B`** (`2.853 GB`, `2.657 GiB`, `2.38%`) | **`1,324,431,114 B`** (`1.324 GB`, `1.233 GiB`, `1.10%`) | `<= 120 GB` | **PASS** |
| **Peak cgroup memory (`oom_kills`)** | `4,936,359,936 B` (`4,707.7 MiB`, `57.47%` of cap, `0` kills) | `6,049,153,024 B` (`5,768.9 MiB`, `70.42%` of cap, `0` kills) | `8,192 MiB` cap, `0` OOM | **PASS** |
| **Host `journalctl -k --since "3 hours ago"`** | Exited `0` (`1,443` lines), `0` OOM / killed-process hits | Exited `0` (`1,443` lines), `0` OOM / killed-process hits | `0` host OOMs | **PASS** |

- **Step 3 extrapolation accuracy:** Step 3 projected `full-value` at `38.0–38.1 min` (`2,282–2,285 s`), `2.57 GB` temp disk, and `1,372–1,374 MB` (`1.28 GiB`) archive size; actual was `37.37 min`, `2.85 GB`, and `1,465.6 MB` (`1.365 GiB`, `+6.7%` due to rugged southern highlands). Step 3 projected `full-painted` at `54.2–54.3 min`, `0.94 GB` temp disk, and `431 MB`; actual was `56.75 min`, `1.32 GB`, and `605.1 MB` (`0.564 GiB`).

## Galaxy A54 Measurements (`SM-A546E`, Item 11)

Tested with `benchmark` `0.1.0` (`Android 16`, `120 Hz` Adaptive, `low_power=0`, cold launch `am start -S` per view, portrait, `3` automated `camera-path.json` runs). Both **clean (no screen recording)** and **earlier screen-recorded** runs are shown:

| View | Cold open | Clean PSS *(recorded PSS notes)* | Run 1 (cold) | Run 2 | Run 3 | Clean typical / lowest *(recorded)* |
| --- | --- | --- | --- | --- | --- | --- |
| **`full-value` `option-c`** (`1.365 GiB`) | **`480 ms`** *(rec. `641 ms`)* | **`208 MB`** *(rec.: `274 MB` copied; `167 → 194 → 254 MB` floor, `285 MB` R3 peak, `301 MB` free pan)* | `120.7 / 117.2` | `120.0 / 113.2` | `120.0 / 118.1` | **`120.0 / 113.2 fps`** *(rec. `105.6 / 80.6`, `52.6` pan)* |
| **`full-painted` `zoom12`** (`0.564 GiB`) | **`413 ms`** *(rec. `519 ms`)* | **`226 MB`** *(rec.: `171 MB` copied; `149–171 MB` GC floor, `249–254 MB` pre-GC spike)* | `120.0 / 118.6` | `119.8 / 114.1` | `120.0 / 115.4` | **`120.0 / 114.1 fps`** *(rec. `112.8 / 90.1`, `65.6` pan)* |
| **`full-painted` `zoom11`** (`~0.23 GiB` est.) | **`368 ms`** | **`213 MB`** *(rec.: `235 MB`; `131–183 MB` GC floor, `230–257 MB` pre-GC spikes)* | `120.0 / 109.9` | `119.5 / 107.9` | `120.0 / 115.8` | **`120.0 / 107.9 fps`** *(rec. `116.7 / 86.0`)* |

Key mechanical findings:

- **Frame rate & open time pass cleanly on clean runs:** Samsung Screen Recorder added `~3–14 fps` load (`105.6–116.7 fps` recorded vs locked **`120.0 fps`** typical and `107.9–114.1 fps` lowest without recording, passing `<= 1.5 s` open, `120 fps` typical, and `>= 45 fps` lowest on A54).
- **PSS around `208–226 MB` right after `Run path`:** On this 8 GB RAM device, clean post-run PSS sits just above `200 MB` (`+8 to +26 MB`) across all three full-extent views because ART allows `~70–90 MB` pre-GC allocation swings before reclaiming (`131–183 MB` post-GC floor on `painted`; recorded extended free pan climbed to `301 MB` on `value`). In the v1 app (`:core:map`), forwarding `ComponentCallbacks2.onTrimMemory` to `mapView.reduceMemoryUse()` and tuning tile-cache bounds should be applied and checked directly on the 4 GB Oppo A55.

## Best-Practice Option Comparisons and Step 4 Decisions

### Decision 1 — Regional Zoom (11 vs 12) and Primary Encoding (Item 1 & Item 3)

| Option | Full archive | Build time | Clean A54 (`open / PSS / fps`) | Visual quality | Trade-off |
| --- | --- | --- | --- | --- | --- |
| **A. `value` (`option-c`) z12 (Recommended primary)** | `1,397.8 MiB` (`1.365 GiB`) | `37.4 min` | `480 ms / 208 MB / 120.0 (113.2) fps` | Sharp ridges & crisp GPU contour bands at close zoom (`z 13–14`) without dual-file option (b) | Largest download (`~1.40 GiB`), well inside GitHub Releases `2 GiB` cap (`650 MiB` headroom); needs 4 GB Oppo A55 memory check & `reduceMemoryUse()` |
| **B. `painted` z12 (`zoom12`, fallback)** | `577.1 MiB` (`0.564 GiB`) | `56.8 min` | `413 ms / 226 MB (149–171 post-GC) / 120.0 (114.1) fps` | Clean Hijaz/Yemen shading to z12 with early fade (`11.5–13.5`) | ~2.4× smaller download (`~577 MiB`, aligns well with Android offline UX guidelines around `~500 MiB`); fades to paper past z12–13.5 |
| **C. `value` (`option-c`) z11 (Future compression lever)** | `~539.4 MiB` est. | Faster (`z12` skipped) | Not yet built/tested in `benchmark` | Retains crisp GPU stepped contour edges, though contours come from `z11` (61 m) grid | Halves archive size (`~539 MiB`) while keeping `option-c` close-up sharpness if `~1.4 GiB` download proves too large |
| **D. `painted` z11 (`zoom11`, not chosen)** | `~238.9 MiB` est. | ~same build + skip `z12` | `368 ms / 213 MB / 120.0 (107.9) fps` | Visibly softer ridges on Yemen/Hijaz than `zoom12` | Sacrifices regional terrain crispness despite `zoom12` already sitting at `28%` of `2 GiB` |

- **Decision (Item 1):** Prefer **zoom 12** over softer zoom 11. Keep **`value` (`option-c`) at zoom 12 (`1.365 GiB`)** as the primary v1 path and **`painted` at zoom 12 (`577 MiB`, early fade `11.5–13.5`)** as the fallback. Before locking `map/coverage.yaml` during map preparation: (1) verify both on the 4 GB Oppo A55 with `onTrimMemory -> reduceMemoryUse()`, and (2) note that `43.28%` (`645.3 MB`) of `full-value`'s summed tile bytes currently sit *outside* the peninsula outline in the `1.0°` margin + borders (`47.62%` / `293.7 MB` in `full-painted`), offering large future size reduction if tightening margins or evaluating `value` z11 (`~539 MiB`) is ever desired for mobile-data UX (`<= 500–600 MiB` initial download).

### Decision 2 — Style C Terrain Readability (Item 8)

- **Options compared:** Keep current Style C settings (`blur_px: 7.0`, `band_blur_px: 1.5`, `exaggeration: 7.36`, `contrast: 1.6`, `bands: 3`, latitude-calibrated to ~23° N) vs re-tuning blur/contrast or adding separate lava/valley overlays.
- **Decision (Item 8):** **Approve current Style C settings** across the full Arabian Peninsula (`Red Sea`, `Hijaz/Asir`, `Yemen`, `Oman/Hajar`, `Sinai/Jordan Rift`). Re-confirm visual rendering on the Oppo A55 and Huawei MatePad 11.5 once available.

### Decision 3 — Modern Features Cleanup Audit (Item 9)

Audited directly against rendered `z12` tiles of `full-painted-5b3d064d/relief.pmtiles` and `z11` vector land tiles of `base.pmtiles` using [ADR 0009](../adr/0009-map-data-pipeline.md) Decision 2 ("Cleanup" smooths/removes unmistakable modern artifacts; the 1256 CE lava flow is left unedited unless clearly distracting in Style C):

- **Options compared:** (A) Zero cleanup edits (modern Jeddah ports and Gulf artificial palm islands remain visible); (B) blanket urban smoothing + broad coastline simplification; (C) **targeted coastline-only cleanup (`remove land` / harbor infilling)** driven by verified visibility.
- **Empirical inspection results (zoom-12 relief + zoom-11 base tiles):**
  - **Clean in Style C relief — zero DEM cleanup needed:**
    - *Makkah (Haram / Abraj Al Bait Clock Tower):* Absorbed by Style C `7 px` (~`267 m`) Gaussian smoothing + `3`-band quantization; natural Makkah hills dominate cleanly.
    - *Central Madinah (Al-Masjid an-Nabawi):* Flat urban plain with no tower artifacts; Jabal Uhud reads naturally to the north.
    - *1256 CE / 654 AH Harrat Rahat lava flow (SE of Madinah):* Indistinguishable inside the wider lava-field relief at Style C resolution — left unedited per ADR 0009 Decision 2.
    - *Badr and Taif:* Clean natural dune/escarpment terrain.
  - **Visible in `base.pmtiles` WBM coastline — targeted cleanup list (`remove land` / coastline smoothing edits):**
    - *Priority 1 (Hijaz Red Sea corridor near v1 events):* Jeddah Islamic Port breakwaters and North Jeddah / Obhur marinas & piers; King Abdullah Economic City dredged port lagoon/channels; Yanbu industrial port jetties/piers.
    - *Priority 2 (Unmistakable modern Gulf mega-reclamation visible across the peninsula):* Dubai/UAE (Palm Jumeirah, Palm Jebel Ali, The World, Jebel Ali port basin); Bahrain (Durrat Al Bahrain crescent islands, northern reclaimed islands, east-coast jetties); Doha/Qatar (The Pearl-Qatar, Lusail artificial islands, Hamad International Airport reclaimed peninsula).
    - *(Note: the Suez Canal lies west of the `33.0° E` trial data extent.)*
- **Decision (Item 9):** Adopt **Option C — targeted WBM coastline cleanup only** (Priority 1 Hijaz ports first, Priority 2 Gulf artificial islands second, zero DEM building/lava cleanup needed), plus a quick spot-check on any additional named coastal/event sites finalized during v1 content preparation.

### Decision 4 — Water Body Mask Coast vs Natural Earth & Extent Joins (Item 10)

- **Options compared:** Keep current WBM-inside / Natural-Earth-outside hybrid vs substituting Natural Earth inside the peninsula at wide zooms (`z 4–6`) vs replacing GLO-30 WBM with an older global shoreline dataset.
- **Decision (Item 10):** **Approve the current hybrid as-is.** Recorded Galaxy A54 runs confirm crisp coastlines across Red Sea, Bab el-Mandeb, Farasan Islands, Sinai/Aqaba, Arabian Sea, and Persian Gulf, with seamless `0.25°` feathered extent borders (no rectangular seam). Swapping Natural Earth inside at `z 4–6` would introduce zoom-level coastline snapping and decouple land boundaries from DEM hillshade, while GLO-30 WBM keeps relief and coastline locked to one dataset per ADR 0009 Decision 6. The Dead Sea remains beige basin terrain (no ocean tint), matching ADR 0009's `WBM class == 1` (ocean-only) rule.
