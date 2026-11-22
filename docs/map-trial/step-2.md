<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Map Trial Step 2: Small Windows (`window-najd`, `window-spike`, `window-yemen`)

Step 2 builds all three approved trial windows from the private `seerah-content` repository (`data_commit` `15a4d7c46d5f797de65a606f99af4188d134d6ac`) using `tools/map/` at `tool_commit` `cb5803689d595f031e7700278ddb25c097c5eaea`, measures them on the Galaxy A54 (`SM-A546E`) with `benchmark` `0.1.0` (built from `cb5803689d595f031e7700278ddb25c097c5eaea`), and records the owner's Step 2 decisions (taken on **2026-11-22**) on Decision 13 items 2, 3, 4, 5, 8 and 11 of [docs/design/map-trial-build.md](../design/map-trial-build.md).

> [!NOTE]
> As recorded in [step-1.md](step-1.md), the Oppo A55 and Huawei MatePad 11.5 were temporarily unavailable during Step 2. Galaxy A54 results and visual judgements are complete; Oppo A55 and MatePad 11.5 runs remain deferred and keep items 3 and 11 open until tested on those devices.

## Build Provenance and Parameters

- **Tool commit (`seerah`):** `cb5803689d595f031e7700278ddb25c097c5eaea`
- **Data commit (`seerah-content`):** `15a4d7c46d5f797de65a606f99af4188d134d6ac`
- **Container image:** `ghcr.io/mohdfarag/seerah-map:5498b0a66ee99ccb` (`sha256:91de3a5133dfce734608223a425cbef9ec9389cbbf8e819dcfe64a2237a69230`)
- **Container limits:** `8,192 MiB` memory cap, `4` workers (`2,048 MiB` per worker budget, `gdal_cachemax_mib: 256`), `--network none` during `build`
- **Test edits applied:** All `5` approved synthetic test edits in `seerah-content` applied (`skipped: []`) in every build; coordinates and geometry stay in the private repository.
- **Process isolation:** `0` OOM kills (`oom_kills: 0`) across all 8 builds.

## Step 2 Window Builds

All 8 archives built cleanly into `map-data/dist/step2/` with `block_zoom: 9`, `maxzoom: 12`, and matching `camera-path.json`:

| Build | Mode / Variant | `relief.pmtiles` | `base.pmtiles` | Temp disk after tiling | Stage times (`base` / `block` / `tile` / `pack`, total) | Peak cgroup memory | Tiles (`expected` / `stored`) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `window-najd` `painted` | `pyramid` / `painted-rgba` WebP | `50,272 B` (`0.05 MiB`) | `1,064,694 B` (`1.02 MiB`) | `142,336 B` (`0.14 MiB`) | `5.54 / 2.21 / 1.17 / 0.09 s` (`9.02 s`) | `614.1 MiB` | `94 / 134` |
| `window-najd` `per-zoom` | `per-zoom` / `painted-rgba` WebP | `44,455 B` (`0.04 MiB`, `-11.6%`) | `1,064,693 B` (`1.02 MiB`) | `205,903 B` (`0.20 MiB`) | `5.44 / 8.79 / 1.66 / 0.09 s` (`15.98 s`) | `559.0 MiB` | `94 / 150` |
| `window-najd` `value` | `pyramid` / `terrarium-value` PNG | `254,164 B` (`0.24 MiB`, `5.06×`) | `1,064,693 B` (`1.02 MiB`) | `500,601 B` (`0.48 MiB`) | `5.43 / 2.23 / 0.61 / 0.09 s` (`8.37 s`) | `558.8 MiB` | `94 / 134` |
| `window-spike` `painted` (`21–25° N, 38–40° E`) | `pyramid` / `painted-rgba` WebP | `13,208,131 B` (`12.60 MiB`) | `1,199,165 B` (`1.14 MiB`) | `31,003,620 B` (`29.57 MiB`) | `19.95 / 20.01 / 42.79 / 0.17 s` (`82.91 s`) | `2,293.0 MiB` | `3,005 / 3,197` |
| `window-spike` `per-zoom` (`21–25° N, 38–40° E`) | `per-zoom` / `painted-rgba` WebP | `12,331,224 B` (`11.76 MiB`, `-6.6%`) | `1,199,164 B` (`1.14 MiB`) | `35,760,980 B` (`34.10 MiB`) | `19.96 / 48.21 / 39.27 / 0.17 s` (`107.61 s`) | `2,153.4 MiB` | `3,005 / 3,223` |
| `window-yemen` `painted` | `pyramid` / `painted-rgba` WebP | `1,177,187 B` (`1.12 MiB`) | `1,027,959 B` (`0.98 MiB`) | `2,751,297 B` (`2.62 MiB`) | `5.67 / 2.12 / 2.45 / 0.09 s` (`10.34 s`) | `588.3 MiB` | `94 / 130` |
| `window-yemen` `per-zoom` | `per-zoom` / `painted-rgba` WebP | `1,121,726 B` (`1.07 MiB`, `-4.7%`) | `1,027,956 B` (`0.98 MiB`) | `3,280,610 B` (`3.13 MiB`) | `5.64 / 9.34 / 2.93 / 0.09 s` (`17.99 s`) | `564.5 MiB` | `94 / 150` |
| `window-yemen` `value` | `pyramid` / `terrarium-value` PNG | `1,923,576 B` (`1.83 MiB`, `1.63×`) | `1,027,957 B` (`0.98 MiB`) | `3,690,040 B` (`3.52 MiB`) | `5.66 / 1.99 / 0.84 / 0.09 s` (`8.58 s`) | `564.7 MiB` | `94 / 130` |

Key build trade-offs observed:

- **`value` vs `painted` archive size:** On flat/sparsely edited Najd (`window-najd`), lossless `terrarium-value` PNG is `5.06×` larger than `painted` WebP (`254,164 B` vs `50,272 B`). On rugged highlands (`window-yemen`), the ratio narrows to `1.63×` (`1,923,576 B` vs `1,177,187 B`). Across zoom 12 tiles alone (`size_by_zoom` at zoom 12), `window-yemen` `value` takes `1,190,852 B` vs `683,790 B` for `painted` (`1.74×`).
- **`per-zoom` vs `pyramid` build cost and size:** `per-zoom` shrinks `relief.pmtiles` by `4.7%` to `11.6%` (`-6.6%` on `window-spike`, `11.76 MiB` vs `12.60 MiB`), because intermediate zoom tiles carry cleaner, flat-banded regions that compress better in WebP. The trade-off is `block`-stage time: `2.41×` slower on `window-spike` (`48.21 s` vs `20.01 s`, increasing total build wall time by `+29.8%`), plus slightly higher temp disk before packing (`34.10 MiB` vs `29.57 MiB`).

## Galaxy A54 Measurements (`SM-A546E`)

- **Device conditions:** Samsung Galaxy A54 (`SM-A546E`, Android 16), Adaptive refresh (`120 Hz`), Power saving off (`low_power=0`), cold-started per tested view over `adb` (`am start -S`), portrait orientation, 3 automated `camera-path.json` runs per view.
- **ADR 0002 / Item 11 targets for A54:** open time `<= 1.5 s` (`1,500 ms`), typical frame rate `120 fps`, lowest frame rate `>= 45 fps`, memory (PSS) `<= 200 MB`.
- **Android 16 `adb push` operational note (for future device setup):** Pushing variant folders to `/sdcard/Android/data/app.seerah.benchmark/files/trial/` *before* launching the freshly installed `benchmark` app once left FUSE external storage hidden from the app (`"No trial files"`). Launching **Map trial** once so Android initializes `getExternalFilesDir(null)` resolves this cleanly.

| Window & View | Cold open | End PSS (observed peak) | Run 1 (cold) fps | Run 2 fps | Run 3 fps | All runs typical / lowest | Visual & mechanical notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `window-najd` `option-a` (`painted`, fade `13.5–15.5`) | `538 ms` *(2nd zoomed launch `410 ms`)* | `128 MB` (`140–143 MB` zoomed) | `120.0 / 118.6` | `120.0 / 118.1` | `120.0 / 118.6` | `120.0 / 118.1 fps` | Soft wash/blur at zooms 13–14 over the test edits; passes all targets cleanly |
| `window-najd` `option-c` (`value`, fade `13.5–15.5`) | `428 ms` *(prior warm picker run `24,689 ms`)* | `185 MB` (brief `205 MB` pre-GC in Run 2; `208 MB` in prior picker run) | `119.0 / 94.0` | `119.5 / 114.1` | `119.8 / 114.9` | `119.5 / 94.0 fps` | Crisp, smooth stepped contour bands at close zoom with zero pixelation or blur |
| `window-spike` `zoom12` (`painted`, `pyramid`, fade `11.5–13.5`) | `387 ms` | `169 MB` (brief `206 MB` pre-GC, dropped to `127 MB`) | `119.5 / 107.5` | `120.0 / 115.8` | `120.0 / 118.6` | `120.0 / 107.5 fps` | Finer, lower-contrast medium-zoom Hijaz ridges; early fade hides close-up blur |
| `window-spike` `per-zoom` (`painted`, `per-zoom`, fade `11.5–13.5`) | `470 ms` | `161 MB` (brief `204 MB` while zooming) | `119.8 / 114.1` | `119.5 / 118.1` | `119.5 / 111.5` | `119.5 / 111.5 fps` | Bolder, higher-contrast medium zooms; identical to `pyramid` at zoom 12 |
| `window-yemen` `option-c` (`value`, fade `13.5–15.5`) | `486 ms` | `197 MB` | `118.8 / 104.5` | `119.3 / 107.9` | `119.5 / 109.9` | `119.3 / 104.5 fps` | Sculpted ridges and steep valleys with zero blur; stays under `200 MB` end PSS |
| `window-yemen` `zoom12` (`painted`, `pyramid`, fade `11.5–13.5`) | `435 ms` | `187 MB` (`193 MB` while zooming) | `119.3 / 89.0` | `120.0 / 114.1` | `119.5 / 112.8` | `119.5 / 89.0 fps` | Softens up close; only `10 MB` lighter end PSS than `option-c`, lower cold minimum |

*(Coverage note: `window-najd` `per-zoom` and `window-yemen` `per-zoom` were built and measured in the archive table above, while live device `per-zoom` vs `pyramid` visual/FPS runs were taken on `window-spike`.)*

## Phase 3 Base-Layer Smoke Check and Visual Observations

- **Vector base layer (`base.pmtiles`):** Across the Najd and Hijaz runs, the sea polygon, Arabian Peninsula land polygon, and Red Sea coastline rendered cleanly and smoothly at `120 fps` with no spurious coast segments inside window interiors. Panning deliberately far outside a cropped window's bounds (for example, panning from `window-spike` across to Najd at high zoom) shows the sea fallback color where the cropped `base.pmtiles` land polygon stops; this does not occur inside a build's camera bounds.
- **Close-up test edits (items 2 and 3):** Enlarging `painted` tiles past zoom 12 (`option-a`, fade `13.5–15.5`) softens the synthetic edit shapes into a blurry wash at zooms 13–14. Under **`option-c`** (`value` PNG + MapLibre GPU `color-relief`), the stepped elevation contours remain sharp and smooth at close zoom on both flat terrain (`window-najd`) and steep mountain slopes (`window-yemen`). The trade-off is higher memory usage on Galaxy A54 (`185–197 MB` end PSS vs `128–187 MB` for `painted`, with brief pre-GC excursions up to `205 MB` similar to transient zoom spikes of `204–206 MB` on `window-spike`).
- **Medium zooms (`pyramid` vs `per-zoom`, item 5):** On `window-spike`, `per-zoom` preserves stronger medium-zoom contrast across the Hijaz escarpment and lava fields, whereas `pyramid` produces lighter, softer medium-zoom shading. Note that `option-c` already computes sharp stepped contour bands per pixel on the GPU at every zoom level, so `--mode per-zoom` applies only if falling back to `--variant painted`.
- **Style C terrain readability (item 8):** The Hijaz escarpment, drainage valleys, lava fields, Red Sea coast (`window-spike`), and Yemen highlands (`window-yemen`) read clearly in style C on Galaxy A54.

## Owner Decisions (2026-11-22)

The owner reviewed the Galaxy A54 screenshots, screen recordings, and measurements and approved the following Step 2 direction:

1. **Primary close-up path (items 2, 3, 4):** Choose **option (c)** (`value` PNG + GPU `color-relief`, fade `13.5–15.5`) as the primary v1 path, **contingent on**:
   - Step 3 projected full-extent `relief.pmtiles` staying under the `2 GiB` limit (`2,147,483,648 B`); and
   - Deferred memory verification on the Oppo A55 (`<= 200 MB` PSS) once that device is available.
2. **Fallback path:** Keep **`painted` with early fade (`11.5–13.5`)** as the fallback if `option-c` exceeds `2 GiB` in Step 3/4 or fails Oppo A55 memory; do not use `option-a` (late fade over zoomed painted WebP) because of zoom 13–14 blur.
3. **Item 4 (option (b)):** **Not triggered** (remains conditional; skipped unless both primary and fallback fail).
4. **Item 5 (`pyramid` vs `per-zoom`):** Not needed for the `option-c` primary path (`per-zoom` supports `--variant painted` only). If the painted fallback is needed later, both options' trade-offs are recorded (`per-zoom` gives bolder medium zooms and `6.6%` smaller Hijaz archives at `+30%` build time; `pyramid` gives quieter medium zooms and faster builds).
5. **Item 8 (Style C):** Style C contrast, latitude-corrected hillshade, and terrain readability are approved on Galaxy A54; full-extent lava fields and Oppo A55 / MatePad 11.5 screens remain to be verified in Step 4 and when devices return.
