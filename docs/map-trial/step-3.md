<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Map Trial Step 3: 10% Contiguous Hijaz–Najd Band Extrapolation

Step 3 builds a contiguous ~10% band across the Hijaz escarpment and central Najd (`trial/band-10pct`) from the private `seerah-content` repository for both the primary **`value`** encoding (option (c)) and the **`painted`** (`--mode pyramid`) fallback, then extrapolates build wall time, temporary disk usage, and `relief.pmtiles` archive sizes to the full Arabian Peninsula extent (`trial/full`) against the limits in [docs/design/map-trial-build.md](../design/map-trial-build.md) (12 hours, 120 GB temporary disk, and 2 GiB per archive).

Both variants pass every Step 3 resource gate with large margins and zero OOM kills, clearing the path to Step 4 (full-extent build).

## Scope, Provenance and Geometry

- **Band profile (`trial/band-10pct`):** `21–25° N, 38–49° E` camera bounds with `0.25°` margin (`20.75–25.25° N, 37.75–49.25° E` data extent), `0.25°` fade, `block_zoom: 9`, `max_zoom: 12`, `webp_quality: 80`.
- **Full profile (`trial/full`):** `12–32° N, 34–60° E` camera bounds with `1.0°` margin (`11.0–33.0° N, 33.0–61.0° E` data extent), `0.25°` fade, `block_zoom: 9`, `max_zoom: 12`, `webp_quality: 80`.
- **Tool commit (`seerah`):** `cb5803689d595f031e7700278ddb25c097c5eaea` (built from a clean detached worktree matching Step 2's toolchain commit)
- **Data commit (`seerah-content`):** `45dc2f8ad2476cae93165523911ee59478fe273c` (`band-10pct.lock` verified 100% matching `full.lock` hashes across all 78 intersecting `1°×1°` cells: `0` downloaded, `158` cached files verified)
- **Container image:** `ghcr.io/mohdfarag/seerah-map:5498b0a66ee99ccb` (`sha256:91de3a5133dfce734608223a425cbef9ec9389cbbf8e819dcfe64a2237a69230`)
- **Container limits:** `8,192 MiB` memory cap, `4` workers (`2,048 MiB` per worker budget, `gdal_cachemax_mib: 256`), `--network none` during `build`, runs executed sequentially (`value` first, then `painted`).

| Metric | `trial/band-10pct` | `trial/full` | Full / band ratio |
| --- | --- | --- | --- |
| `block_zoom: 9` blocks | `144` | `1,435` | `9.965×` (`10.03%` sample) |
| Expected relief tiles (`z0–z12`) | `12,314` (`9,216` at `z12`) | `122,524` (`91,840` at `z12`) | `9.950×` |
| Expected relief tiles (`z0–z11`) | `3,098` (`2,304` at `z11`) | `30,684` (`22,960` at `z11`) | `9.904×` |
| `1°×1°` DEM/WBM cells (`present` / `total`) | `76 / 78` (`2.6%` absent sea) | `529 / 616` (`14.1%` absent sea) | `6.961×` present cells |

## Measured `band-10pct` Builds

All `5` synthetic test edits applied (`skipped: []`), `12,718` tiles were addressed in `relief.pmtiles` in both builds, and `oom_kills` stayed `0`:

| Metric | `band-10pct-value-23bb9080` (Primary `option-c`) | `band-10pct-painted-2ce5a687` (Fallback `pyramid`) |
| --- | --- | --- |
| `relief.pmtiles` (`z0–z12`) | `137,877,568 B` (`131.49 MiB`) | `43,290,003 B` (`41.28 MiB`) |
| `base.pmtiles` | `1,274,247 B` (`1.22 MiB`) | `1,274,244 B` (`1.22 MiB`) |
| Temp disk after tiling (`work_area_bytes_after_tiling`) | `258,034,926 B` (`246.08 MiB`, `0.26 GB`) | `94,037,857 B` (`89.68 MiB`, `0.09 GB`) |
| Stage times (`base` / `block` / `tile` / `pack`) | `73.00 / 78.44 / 77.22 / 0.65 s` | `72.76 / 80.18 / 173.56 / 0.45 s` |
| Total wall time (all stages) | `229.32 s` (`3.82 min`) | `326.95 s` (`5.45 min`) |
| `block + tile + pack` time | `156.31 s` (`2.61 min`) | `254.19 s` (`4.24 min`) |
| Peak cgroup memory (`oom_kills`) | `3,217,969,152 B` (`3,068.9 MiB`, `0` kills) | `2,812,428,288 B` (`2,682.1 MiB`, `0` kills) |
| Unique PMTiles contents (`pmtiles show`) | `9,387` / `12,718` addressed (`9,803` entries) | `7,157` / `12,718` addressed (`8,178` entries) |
| Host kernel OOM log (`journalctl -k --since "1 hour ago"`) | `0` OOM or killed-process entries | `0` OOM or killed-process entries |

### Zoom Breakdown (`size_by_zoom`) and Zoom-11 Baseline

Both archives compress identically sized uniform/empty tiles inside PMTiles v3 (`137.88 MB` on disk vs `140.13 MB` summed uncompressed-directory tile bytes for `value`, and `43.29 MB` vs `44.50 MB` for `painted`). Dropping zoom 12 reduces summed tile payload bytes by `61.4%` for `value` and `58.7%` for `painted`:

| Zoom slice | `value` (`inside` / `outside` B) | `painted` (`inside` / `outside` B) |
| --- | --- | --- |
| `z0–z10` summed tile bytes | `19,290,731 B` + `210,808 B` = `19,501,539 B` | `6,665,604 B` + `78,156 B` = `6,743,760 B` |
| `z11` tile bytes | `34,415,339 B` + `188,822 B` = `34,604,161 B` | `11,574,300 B` + `48,772 B` = `11,623,072 B` |
| `z12` tile bytes | `85,339,972 B` + `680,514 B` = `86,020,486 B` | `25,944,688 B` + `191,584 B` = `26,136,272 B` |
| **`z0–z11` share of `z0–z12` payload** | **`54,105,700 / 140,126,186` (`38.61%`)** | **`18,366,832 / 44,503,104` (`41.27%`)** |

## Full-Extent Extrapolations vs Step 3 Gates

Extrapolations below show both geometric block scaling (`1,435 / 144 = 9.965×`) and expected-tile scaling (`122,524 / 12,314 = 9.950×` for `z0–z12`; `30,684 / 3,098 = 9.904×` for `z0–z11`). For wall time, both linear all-stage scaling (`total × ratio`) and `base` + linear `block+tile+pack` scaling are reported because `base.pmtiles` builds vector coastlines once rather than per block:

| Metric & Gate | Measured `value` | Projected Full `value` (block / tile scaling) | Measured `painted` | Projected Full `painted` (block / tile scaling) | Gate status |
| --- | --- | --- | --- | --- | --- |
| Wall time (`<= 12 h` / `43,200 s`) | `229.32 s` (`3.82 min`) | `27.1–27.2 min` split; `38.0–38.1 min` all-linear (`3.8%–5.3%` of limit) | `326.95 s` (`5.45 min`) | `43.4 min` split; `54.2–54.3 min` all-linear (`6.0%–7.5%` of limit) | **PASS** |
| Temp disk after tiling (`<= 120 GB`) | `258.0 MB` (`0.26 GB`) | `2.57 GB` (`2.39 GiB`, `2.14%` of limit) | `94.0 MB` (`0.09 GB`) | `0.94 GB` (`0.87 GiB`, `0.78%` of limit) | **PASS** |
| `relief.pmtiles` `z0–z12` (`<= 2 GiB` / `2,147,483,648 B`) | `137,877,568 B` (`131.49 MiB`) | `1.37 GB` (`1.28 GiB`, `63.9%–64.0%` of limit; `~738 MiB` headroom) | `43,290,003 B` (`41.28 MiB`) | `430.7–431.4 MB` (`0.40 GiB`, `20.1%` of limit; `~1,637 MiB` headroom) | **PASS** |
| `relief.pmtiles` `z0–z11` (`<= 2 GiB` / `2,147,483,648 B`) | `38.61%` payload share (`~50.8 MiB`) | `527.3–530.5 MB` (`0.49 GiB`, `24.6%–24.7%` of limit) | `41.27%` payload share (`~17.0 MiB`) | `177.0–178.0 MB` (`0.16–0.17 GiB`, `8.2%–8.3%` of limit) | **PASS** |
| Peak memory (`8,192 MiB` cap, `0` OOM) | `3,068.9 MiB` (`0` OOMs) | Same 4-worker concurrency (`2,048 MiB/worker` budget) | `2,682.1 MiB` (`0` OOMs) | Same 4-worker concurrency (`2,048 MiB/worker` budget) | **PASS** |

## Interpretation and Caveats Before Step 4

- **Margin under `2 GiB` for primary `option-c` (`value` at zoom 12):** Scaling puts `value` at `~1.28 GiB` (`~738 MiB` below `2 GiB`), while zoom 11 stays under `0.50 GiB` and the `painted` fallback stays around `0.40 GiB`.
- **Sea vs land balance (conservative bias):** Only `2.6%` (`2 / 78`) of `band-10pct`'s `1°×1°` cells are absent ocean cells, compared with `14.1%` (`87 / 616`) across `full`. Pure block/tile scaling (`9.97×`) therefore scales denser land blocks across open sea borders, while `base.pmtiles` covers vector coasts once (`1.22 MiB` on `band-10pct`) rather than scaling `10×`.
- **Terrain roughness across the peninsula:** `band-10pct` samples Red Sea coast, rugged Hijaz mountains/lava fields, and the central Najd plateau (`99.2%` of zoom-12 bytes are inside the peninsula outline). In the full extent, large sand basins (an-Nafud and ar-Rub' al-Khali) compress smaller than Najd/Hijaz, whereas rugged mountain arcs (Asir, Yemen, Oman) are denser than the band average (`window-yemen` `value` was `1.83 MiB` for a 1-block rugged sample). Step 4 measures the exact full-extent file directly.
- **Container peak memory:** Peak cgroup RSS reached `3,068.9 MiB` (`37.5%` of `8,192 MiB`) during `value` and `2,682.1 MiB` during `painted`, leaving more than `5 GiB` of headroom under the cap with `0` OOM kills.
