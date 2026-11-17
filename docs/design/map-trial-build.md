# Map trial build

- **Author:** MohdFaragDev
- **Status:** Approved by the owner on 2026-11-14; option (c)'s ramp and fade amended on 2026-11-17 (owner). Revised after an [EGM review](egm-review-map-trial/consolidated_review_report.md) and the owner's answers of 2026-11-14 ([resolution](egm-review-map-trial/goldfish_review_report.md)).
- **Date:** 2026-11-14

---

## Overview

This specification describes how to build and run the trial that [ADR 0009](../adr/0009-map-data-pipeline.md) decision 13 requires before the first map package. It covers three pieces of new work and how they meet:

1. **The map build tool** in `tools/map/`: Python, public, Apache-2.0, run in a pinned container image. It downloads Copernicus GLO-30 elevation and its Water Body Mask, applies terrain edits and renders style C relief in blocks, cuts tiles with GDAL's tiler, and writes PMTiles files with Protomaps' converter. This is **method M2**: our code styles blocks, GDAL tiles them, and Protomaps packs them (owner, 2026-11-14; alternatives in A5–A8).
2. **The first piece of the v1 map** in `:core:map`: MapLibre Native drawing PMTiles files from the device, with style C's look, fades and camera limits. This code stays in v1.
3. **A `benchmark` build type** of the app, optimised like `release` but separate from it. It holds the temporary trial screen, the measurement overlay and a scripted camera path. The trial code is removed after the trial; the build type stays.

The trial's settings and synthetic test edits live in the private repository, in `seerah-content/map/trial/`. The public repository keeps a small synthetic example in `map-data/example/` for CI. The decisions behind this specification were taken by the owner on 2026-11-14 after a seven-lens review (seven fresh-context reviewers, each with a different focus), recorded in the [concepts brief](concepts_brief.md).

**Changes to accepted ADRs made with this specification:**

- **ADR 0009, decisions 3, 6 and 7:** Natural Earth fills the view beyond the data extent, and the coverage check is split between the data extent and the base layer.
- **ADR 0004:** the `benchmark` build type is brought forward.
- **ADR 0007, decision 10:** the `benchmark` build type may load unsigned trial map files.

## The Problem

ADR 0009 fixed how the base map is built but left questions to measurement, and decision 13 makes their answers the gate before any real map package:

- **The regional maximum zoom**, 11 or 12. The relief's size roughly quadruples from one to the next.
- **How close-up terrain is shown:**
  - option (a), the regional relief enlarged with a later fade;
  - option (c), colour bands drawn by the app from a value file;
  - option (b), a second detail file, only if (a) and (c) both fail.
- **The real map size.** Today it is only an extrapolation from the spike's 8-square-degree sample to an area about 65 times larger.
- **Whether valleys ship.** That depends on whether flow routing fits on the owner's machine and gives clean lines.
- **Which modern features need cleanup edits:** city towers, the 1256 lava flow, artificial islands.

These need real builds of the real area, viewed in the real app on the three test devices, measured against ADR 0002's targets, and compared by eye with spike build 11, the look the owner approved.

Three things stand in the way today:

- **No tool can build the map at this size on the owner's machine.** The spike's method loads the whole area into memory. For the data extent at zoom 12 that is about 5.7 billion pixels, about 23 GB for elevation alone, on a machine with 13 GB of memory and about 2 GB free while the desktop runs.
- **No v1 app can show it.** The v1 app is an empty scaffold. The spike app is reference only (AGENTS.md), and its speed numbers would not be v1's.
- **No safe way exists to load trial files into an optimised build.** ADR 0002 measures speed in release builds only, and ADR 0007 decision 10 forbids release builds from loading unsigned files. Signing does not exist yet.

**The owner's failure criteria** (owner, 2026-11-14), copied from the brief:

- **Visual quality:** visible seams, missing tiles, rendering artefacts, or noticeably bad terrain.
- **Performance:** ADR 0002's targets missed on any of the three devices, especially the Oppo A55.
- **Build practicality:** too much memory or disk, unreasonable build time, or a process that cannot be repeated reliably on the owner's machine. Build cost is measured, never assumed.
- **Code quality (major priority):** unnecessary custom code, duplicating what mature tools do, poor separation of concerns, fragile architecture, or heavy maintenance.
- **Reliability:** a build that cannot recover from an interruption, gives different output for the same input, or leaves temporary files behind.

## Goals

- **G1. Answer decision 13's eleven items** (table below) with evidence the owner can judge: on-device screenshots with the overlay, run reports, and a report per step in `docs/map-trial/`. *Check:* the owner signs off each item, or records why it is deferred.
- **G2. Build the full data extent on the owner's machine within the limits in item 6:** 12 hours and 120 GB of temporary disk (owner, 2026-11-14). The build runs under a container memory cap, and its time, peak memory and disk are measured. *Check:* the full build's run report; the owner reads `journalctl -k` afterwards to confirm the host's out-of-memory killer did not act outside the container.
- **G3. No seams and no missing relief tiles.** Every relief tile exists at every zoom of the data extent, transparent ones included (decision 5). A build split into many blocks gives the same decoded tiles as one block. *Check:* automated tests on the example, and a completeness check after every build.
- **G4. Resumable and repeatable.**
  - An interrupted build resumes without redoing finished blocks or keeping a broken tile.
  - A build under changed inputs gets its own work area.
  - The same inputs on the same machine and image give identical bytes.
  - A work area is removed on success and kept on failure.

  *Check:* automated tests that kill a build in the block stage and in the tile stage and then resume it, and that build the example twice and compare hashes.
- **G5. Little custom code, each part with one job.** Our code is limited to:
  - the block stage (style, encoders, edits, mask);
  - the completeness check;
  - the base-layer glue;
  - configuration and lock validation;
  - the launcher.

  Reading, warping, tiling, resampling and PMTiles writing are left to GDAL, tippecanoe and Protomaps' tools. *Check:* the file list below, and code review of each phase.
- **G6. Measure ADR 0002's measures on the three devices** in an optimised build, with the spike's definitions (`Stats.kt`), over a scripted camera path run at least three times per device and view. *Check:* the overlay's readings, against item 11's pass rule. The trial map has no labels yet, so a pass is a lower bound for v1.
- **G7. Keep `release` free of any way to load unsigned files** (ADR 0007 decision 10), and keep trial code out of `:core:map`'s public interface. *Check:* a CI check that the release APK's manifest declares no trial component, and a review of `:core:map`'s public types.
- **G8. Keep the approved look:** style C as in build 11, with the latitude correction calibrated so the Hijaz matches build 11 at about 23° N. *Check:* a regression test against the spike's formula, and the owner's side-by-side judgement on the three devices.
- **G9. Mark trial output so later tools refuse it.** Trial profiles and edits say `purpose: trial`, and every output folder's `metadata.json` says `trial: true`. *Check:* a test that a trial profile produces `trial: true`. The future packaging step must refuse it, with its own test.

### Decision 13's items

| # | Item (ADR 0009 decision 13) | Step | Build or view | Pass rule |
| --- | --- | --- | --- | --- |
| 1 | Relief at zoom 11 and 12, with the size of the full data extent, margin included | 4 | painted build to zoom 12; zoom 11 is the same file capped at 11 | Size per zoom reported; every file under 2 GiB; the owner chooses the zoom |
| 2 | Option (a): regional relief enlarged to zooms 13–15 at the test edits, fade moved later | 2 | painted build; view "option a" | Owner judges on the three devices against build 11 |
| 3 | Option (c): colour-relief bands at the test edits, their size and their cost on the Oppo A55 | 2 | value build; view "option c" | Owner judges the look; size and Oppo A55 frame rate reported |
| 4 | Option (b), if needed: the seam between two files, the detail zoom and its size | none | none | If (a) and (c) both fail, the trial stops here and option (b) gets its own specification |
| 5 | Rendering each zoom separately against rendering at the top zoom and averaging down | 2 | per-zoom build of the small windows | Owner judges |
| 6 | A full build on the owner's machine, with its time and peak memory | 4 | full painted build | Within 12 hours and 120 GB of temporary disk; peak memory reported |
| 7 | Clean valley lines | 5 | valley build, only if the probe fits | Owner judges; deferred if the probe does not fit |
| 8 | Lava fields and main valleys reading in style C | 2 and 4 | painted build | Owner judges |
| 9 | Which modern features show near events (towers, the 1256 flow, islands) | 4 | painted build | Owner lists the features that need cleanup edits |
| 10 | The Water Body Mask coast against Natural Earth at the widest zoom, and an earlier water mask if available | 4 | base layer | Owner judges, including the joins where the data's edge crosses a coast |
| 11 | Performance on the three devices against ADR 0002 | 2 and 4 | every view | Open time ≤ 4 s on the Oppo A55 and ≤ 1.5 s on the others; typical frame rate ≥ 55 fps on the Oppo A55 and the screen's rate on the others; lowest ≥ 20 fps on the Oppo A55 and ≥ 45 fps on the others; memory (PSS) ≤ 200 MB on all three |

For item 11: the typical rate is the median of the overlay's published readings over a run. The lowest rate is the minimum over all runs, with the first, cold run reported separately.

## Non-Goals

- **The real map package:** the manifest, signing, `tile_schema` 1, `map.json`, and publishing to a channel. Trial output is never published.
- **Real terrain edits:** no cleanup or reconstruction edits for real places, and no decision on the reconstruction edit format. The trial's edit format (`trial-0`) is provisional.
- **Final decisions the trial informs.** The camera bounds, the zoom values, the close-up option and the valley decision stay the owner's, taken after the trial.
- **Labels, markers and the explore screen** (`:feature:explore`). The trial map shows terrain only.
- **Option (b)'s detail file**, unless items 2 and 3 both fail, in which case it gets its own specification.
- **Streaming tiles**, or any online map.
- **Reducing the map's size.** Size is measured, not optimised.

## Open Questions

- **Q1. Trial camera minimum zoom.** The map's zoom limits are an open decision (owner's call during implementation). The trial uses MapLibre zoom 4 as a working value, recorded in `camera-path.json`, so the whole peninsula fits on a phone screen.

The rest are facts to check in step 1, listed with their fallbacks under "Step 1 checks".

## Technical Plan (Architecture)

### Facts this plan rests on (checked 2026-11-14)

- **Copernicus GLO-30 on AWS** (bucket `copernicus-dem-30m`, read anonymously): every 1°×1° cell folder holds the elevation (`…_DEM.tif`) and the Water Body Mask (`AUXFILES/…_WBM.tif`).
  - **The cell list:** the bucket's `tileList.txt` names the 26,450 cells that exist.
  - **The data extent's cells:** 616, of which 529 exist and 87 are absent from `tileList.txt` (expected to be all sea).
- **GDAL 3.13.3** is published as `ghcr.io/osgeo/gdal:ubuntu-small-3.13.3`.
  - **`gdal raster tile` (GDAL 3.11+):** writes a folder of XYZ tiles in WebP or PNG, with `-j`, `--resume`, `--skip-blank`, `--resampling` and `--overview-resampling`.
  - **PMTiles:** GDAL writes PMTiles for vector tiles only.
- **Protomaps' Python `pmtiles` package:**
  - `disk_to_pmtiles` reads `metadata.json`, sorts every tile by tile ID, and writes them in that order.
  - Its writer stores identical tiles once, matching them by Python's `hash()`.
  - **go-pmtiles** offers `verify`, `show` and `extract`.
- **MapLibre Native Android** has had the `color-relief` layer since 13.0.0. The spike used 13.7.1 with `pmtiles://file://` sources and measured frames with `addOnDidFinishRenderingFrameListener` (`android/spike/spike/src/main/kotlin/app/seerah/spike/Stats.kt`).
- **How `color-relief` reads its ramp** (MapLibre Native tag `android-v13.7.1`, checked 2026-11-17), in `src/mln/renderer/layers/render_color_relief_layer.cpp`, `updateColorRamp()`:
  - if `color-relief-color` is an `interpolate` expression, its stop elevations are used exactly (lines 129–157), stored in a 32-bit float texture read with nearest filtering (lines 266–269);
  - any other expression, `step` included, is sampled at 256 evenly spaced elevations from −500 to 9000 m, about 37 m apart (lines 159–176), so stored values 0–255 fall between only seven samples and bands at 42.5, 127.5 and 212.5 cannot be drawn;
  - the fragment shader (`shaders/color_relief.fragment.glsl`) finds the two stops around each pixel's elevation by binary search and mixes their colours linearly.

Sources are listed under Additional Resources.

### Terms

- **Camera bounds:** the trial box, latitude 12–32° N, longitude 34–60° E (owner's working value). MapLibre limits the centre of the view to it.
- **Data extent:** the camera bounds plus a 1° margin, latitude 11–33° N, longitude 33–61° E: the 616 cells in the lock. Relief and Water Body Mask land exist only inside it.
- **Block:** one zoom-9 tile on the global Web Mercator grid, 2,048 × 2,048 pixels at zoom 12 (`block_zoom: 9`). About 1,400 blocks cover the data extent.
- **Variant:** one build output (painted, value, or per-zoom).
- **View:** one style over a variant on the device (zoom 12, zoom 11, option a, option c, per-zoom).

### Zoom conventions

- **Build zooms** are 256-pixel tile zooms: the relief is tiled with 256-pixel tiles, and "zoom 12" is the zoom-12 tile grid.
- **Style, fade, camera and camera-path zooms** are MapLibre zooms. The relief source is declared with `tileSize: 256`, as in the spike, so build zoom z is shown at MapLibre zoom z − 1 and above.
- **Screens:** the three test devices are the Oppo A55 (about 360 dp wide), the Galaxy A54 (about 385 dp) and the MatePad 11.5 (about 1,100 dp wide in landscape). Exact widths are recorded in step 1.

### Components

```text
 seerah-content/map/trial/ (private, read-only)    seerah (public)
   window-*.yaml, full.yaml   (profiles)            tools/map/run.py      host launcher: git checks, image, mounts, limits
   edits.yaml                 (draft → approved)    tools/map/Dockerfile  toolchain image (GDAL, tippecanoe, go-pmtiles,
   <profile>.lock             (committed by owner)                        WhiteboxTools, Python packages), no tool code
   camera-path.json                                 tools/map/src/seerah_map/   the tool, run from the /src mount
                                                      fetch  → download cache (volume) + /out/<profile>.lock (first run)
 map-data/example/ (public, CI) ─────────────────▶    build  → block stage → tile stage → pack → base layer
                                                             → /out/<profile>-<variant>-<fp8>/
                                                    │
                                                    ▼  adb push (one folder per variant)
 android/app/src/benchmark/ (trial, removed after)        android/core/map/ (stays in v1)
   TrialActivity: views, overlay, camera path  ──uses──▶  SeerahMap(files, style, limits, onCamera, onEvent)
```

The tool and the app meet only through files. The app depends on the PMTiles format and the fields of `metadata.json` and `camera-path.json`; the build depends only on the tile encodings MapLibre reads.

### The image and the launcher

The image holds **only the toolchain**:

- **Base:** `ghcr.io/osgeo/gdal:ubuntu-small-3.13.3`, pinned by digest.
- **Map tools:**
  - tippecanoe, built from a pinned commit in its own build stage;
  - the go-pmtiles and WhiteboxTools release binaries, each checked by SHA-256.
- **Python packages:** from `tools/map/requirements.txt`, installed with `pip install --require-hashes --break-system-packages` into the image's system Python, so they sit beside GDAL's own Python bindings.
- **Tag:** the first 16 hexadecimal characters of the SHA-256 of the Dockerfile followed by `requirements.txt`. The `map` workflow builds and pushes `ghcr.io/<owner>/seerah-map:<tag>` when that tag is missing. Nothing is committed back.

The tool's code is **not** in the image. It runs from the public checkout, mounted read-only at `/src`, with `PYTHONPATH=/src/tools/map/src`. So the commit recorded is the code that ran.

`tools/map/run.py` (host Python 3.11, standard library only) is the only way the owner runs the tool. It:

1. checks that both checkouts are clean and reads their commits with `git` on the host (decision 8 refuses uncommitted changes);
2. computes the image tag the same way CI does, pulls the image and resolves its digest;
3. starts the container:
   - **mounts:** `/src` (public checkout) and `/data` (`seerah-content/map/` only), both read-only; the named volumes `seerah-map-cache` at `/cache` and `seerah-map-work` at `/work`; and the output folder at `/out`;
   - **user and labels:** `--user` set to the host user, and `--security-opt label=disable`, so SELinux never relabels the owner's checkouts;
   - **network:** `--network none` for every command except `fetch`;
   - **memory:** `--memory` and `--memory-swap` from `--memory-mib`;
   - **threads:** `GDAL_NUM_THREADS=1`, `OMP_NUM_THREADS=1` and `OPENBLAS_NUM_THREADS=1` in the container's environment, so every process starts with them;
   - **provenance:** both commits and the image digest passed as environment variables.

It builds the `docker run` arguments as a list, never a shell string, so paths with spaces are safe. Container paths are written `/src`, `/data`, `/cache`, `/work` and `/out` throughout this document; everything else is a host path relative to the repository root.

### Commands

| Command | What it does |
| --- | --- |
| `fetch --profile P` | Downloads P's cells (elevation and Water Body Mask) and the Natural Earth files into `/cache`, one file at a time, through a temporary name. A failed download restarts that file. On the first run for P, it writes `/out/<P>.lock`, with every cell either listed with its addresses, sizes and SHA-256 or marked `absent: true` (from `tileList.txt`), plus the Natural Earth files pinned to a commit of `natural-earth-vector`. It then prints "copy `/out/<P>.lock` to `seerah-content/map/trial/` and commit". On later runs it verifies every cached file against the committed lock and refuses any mismatch, naming the file, both hashes and the next step. The first hashes are trust on first use, since Copernicus publishes none. |
| `build --profile P --variant {painted,value} [--mode per-zoom]` | Runs the block stage, tile stage, pack and base layer for P, writing `/out/<P>-<variant>-<fp8>/`. |
| `clean [--fingerprint F]` | Lists work areas with their sizes, and removes one or all. |

A profile `P` lives at `/data/trial/<P>.yaml`. Its edits file is `/data/trial/edits.yaml`, and its lock is `/data/trial/<P>.lock`. The trial has four profiles:

- `full` (the data extent);
- `window-spike` (latitude 21–25° N, longitude 38–40° E, for the side-by-side with build 11);
- `window-najd` (around the test edits);
- `window-yemen` (one mountain block, for the worst-case tile size).

All four share the download cache.

### Inputs

- **Profile** (`<P>.yaml`, checked against `profile.schema.json`):
  - `purpose: trial` (the only value accepted);
  - the camera bounds, `margin_deg` and `fade_deg`;
  - `max_zoom` (the highest build zoom) and `block_zoom`;
  - `webp_quality`;
  - the style settings below;
  - `peninsula_outline`, a GeoJSON file for the size split.
- **Edits** (`edits.yaml`, checked against `edits.schema.json`):
  - **Format:** `schema: trial-0`, `purpose: trial`. It is not the reconstruction edit format, which stays an open decision.
  - **Fields per edit:**
    - an ID;
    - a GeoJSON Polygon or LineString in longitude and latitude;
    - an operation: `raise`, with a positive or negative `amount_m`; `flatten-to`, with an absolute `height_m`; or `carve`, for lines, with `amount_m` and `width_m`;
    - `feather_m`;
    - `review`, using the content schema's review values;
    - optional `sources`.
  - **Approval:** only `review: approved` edits are applied, and the rest are listed in the build log. The test edits are committed as `draft`; the owner sets `approved` at the step 1 checkpoint (AGENTS.md: only the owner marks content `approved`).
- **Lock** (`<P>.lock`), written by `fetch`.

**The fingerprint** is the SHA-256 of the profile, the edits, the lock, the public commit, the image digest, the variant and the mode. Machine settings (`--memory-mib`, `--per-worker-mib`) are not part of it, so lowering them after an out-of-memory kill keeps finished work. The work area is `/work/<fingerprint>/`; several can coexist.

### The block stage

Each block is processed by one worker. The number of workers is the container's `memory.max` divided by `--per-worker-mib`, capped by `--workers`. The per-worker allowance is measured in step 1.

For each block:

1. **Read** the elevation (bilinear) and the Water Body Mask (nearest) through a VRT of the locked cells, warped to the zoom-12 grid with an error threshold of zero, over the block plus its border.
   - **Absent cells:** cells marked `absent: true` read as ocean in the mask and 0 m in the elevation.
   - **Outside the data extent:** pixels are nodata in both.
2. **The border** is computed from the settings, never hard-coded: three times each blur's σ, plus one pixel for the gradient, plus the mask smoothing radius, plus the largest `feather_m` (and half the largest `width_m`) converted to pixels at the block's latitude, all rounded up.
3. **Land mask:** ocean (class 1) is sea and every other class is land (decision 1 draws no inland water). The mask is blurred with σ = 4 pixels at zoom 12 and kept as a byte from 0 to 255; land is at 128 and above.
4. **Edits:** each approved edit whose footprint plus feather touches the bordered block is rasterised with `gdal.Rasterize`. Its weight falls linearly from 1 at its outline to 0 at `feather_m`. The distance comes from `scipy.ndimage.distance_transform_edt` in pixels, times each row's ground pixel size. Edits come **before** the smoothing (owner, 2026-11-14), so shapes narrower than about 500 m mostly disappear (see Known risks).
5. **Style:** the style function returns darkness in 0–1 for every pixel.
6. **Crop** to the block, then **encode** pixel by pixel, and write the styled block and the mask block to temporary names, renamed when complete. Finished blocks are skipped on resume.

**The style function** (`style.py`) is pure: arrays and settings in, an array out, with no GDAL or file code. It follows `tools/spike/relief_style.py` line by line in what it computes; the code is written anew.

1. Blur the elevation: `gaussian_filter(sigma=blur_px, truncate=3.0, mode="nearest")`.
2. Multiply by `exaggeration`.
3. Gradients `gy, gx = np.gradient(z, pixel_m)`, with rows running north to south. Then divide each row's gradients by `cos(latitude)`, using the latitude of that row's absolute position on the global grid, so that slopes use true ground distance. The latitude comes from the global grid, never from the block, so block edges cannot show steps.
4. Slope = atan(hypot(gx, gy)); aspect = atan2(−gx, gy). Shade = clip(sin(alt)·cos(slope) + cos(alt)·sin(slope)·cos(az − aspect), 0, 1), with the light from `azimuth_deg` at `altitude_deg`.
5. Darkness = clip((sin(alt) − shade) / sin(alt) × `contrast`, 0, 1).
6. When `banded` is true (the painted variant): darkness = round(darkness × `bands`) / `bands`, which gives `bands` + 1 levels, then blur with `band_blur_px`, cut off at radius 4 as the spike's `int(1.5 × 3)`.
7. Set sea to 0, last.

**Settings** (spike values, so the look matches build 11):

- `blur_px` 7 and `band_blur_px` 1.5, in pixels at zoom 12;
- `exaggeration` 7.36 (8 × cos 23°, so slopes at 23° N match build 11, which used the uncorrected Mercator pixel size);
- `azimuth_deg` 315, `altitude_deg` 45, `contrast` 1.6, `bands` 3;
- `ink_rgb` 150, 118, 72, and `max_alpha` 0.6.

In per-zoom mode, blurs are scaled by 2^(zoom − 12), so they cover the same ground distance.

**Two encoders:**

- **Painted** (WebP): RGBA with the ink colour in every pixel and alpha = trunc(darkness × `max_alpha` × 255), as the spike did.
- **Value** (option c, lossless PNG): continuous darkness, unbanded, as ADR 0009 decision 4 describes, in Terrarium form: red 128, green trunc(darkness × 255), blue 0. Averaging changes only the green channel, so values stay true.

**Edge fade:** within `fade_deg` of the data extent's edge, darkness falls linearly to 0, so relief never ends in a straight cut.

### The tile stage

`gdal raster tile` runs on the VRT of styled blocks with these settings:

- the XYZ convention, `WebMercatorQuad`, and 256-pixel tiles;
- zooms 0 to `max_zoom`;
- `--resampling average` and `--overview-resampling average`;
- `-j` set to the worker count;
- **no** `--skip-blank`, so transparent tiles are written too;
- WebP at `webp_quality` (painted variant), or PNG (value variant).

**Recovery:** when the tiler succeeds, the stage writes `tiles.done`. On start, a tile folder without `tiles.done` is deleted and tiled again. If step 1 shows that GDAL writes each tile atomically, the folder is resumed with `--resume` instead.

**Completeness check:** every relief tile the data extent should have, at every zoom, exists and decodes; otherwise the build fails and lists the tiles.

**Per-zoom mode** (item 5, small windows only): the block stage and tile stage run once per zoom, on that zoom's grid, into one tile folder.

### Pack

The pack step:

1. writes `metadata.json` (the format, zooms, bounds, `trial`, the variant and mode, the encoding, the fingerprint, both commits and the image digest) into the tile folder;
2. runs Protomaps' `disk_to_pmtiles` with `PYTHONHASHSEED=0`, writing `relief.pmtiles` into the output folder;
3. runs `pmtiles verify`, and compares `pmtiles show`'s bounds and zooms with the profile;
4. copies `metadata.json` beside the output.

### The base layer

The base layer is built from the mask blocks, then the work area is removed:

- **Land** for each zoom band (0–2, 3–5, 6 to `max_zoom`): the mask is resampled to the band's resolution, polygonised inside the data extent only, and cut on the block grid. Each band is tiled with tippecanoe into its own file, using the spike's flags (`--no-tile-size-limit --no-feature-limit --no-tiny-polygon-reduction`).
- **Coast:** `gdal_contour` at 128 on the same mask, inside the data extent only. The coast is drawn as lines, not from polygon outlines, so the edge of the data never shows as coast.
- **Beyond the data extent** (ADR 0009 decisions 3 and 6, amended by the owner, 2026-11-14): Natural Earth land and coastlines, clipped to outside the data extent shrunk by two pixels, so the two sources overlap slightly instead of leaving a gap. The scales are:
  - 1:110m at zooms 0–2;
  - 1:50m at 3–5;
  - 1:10m from 6 up to the highest build zoom at which the view can pass the data extent.
- **Joined:** all of these go into `base.pmtiles` with `tile-join`.
- **A missing vector tile** shows the sea background, which is correct, so G3 covers relief only.

**How far the view reaches past the camera bounds:** half the MatePad's width, about 550 dp at 22° N.

| MapLibre zoom | Reach |
| --- | --- |
| 6 | about 620 km |
| 7 | about 310 km |
| 8 | about 155 km |
| 9 | about 80 km |

With the 1° margin (about 100 km), the view passes the data extent up to about MapLibre zoom 8 (build zoom 9). Natural Earth goes up to build zoom 9; step 1 recomputes this from the measured screen widths.

**Coverage checks** (ADR 0009 decision 7, amended):

- the data extent contains the camera bounds plus `margin_deg`;
- `base.pmtiles` covers the camera bounds plus half the widest test screen at every MapLibre zoom from the minimum zoom up.

Both are unit tests of the profile's geometry.

### The run report

`report.json`, written beside the outputs, records:

- **Provenance:** both commits, the image digest and the fingerprint.
- **Time:** the wall time of each stage.
- **Memory:**
  - `memory.peak`, labelled "includes page cache";
  - the `oom_kill` count from `memory.events`;
  - the largest worker's resident memory (`getrusage(RUSAGE_CHILDREN)`).
- **Disk:** the work area's size after tiling, its largest point.
- **Tiles:** expected, written, and stored after deduplication.
- **Size:** bytes per zoom, inside and outside `peninsula_outline`.
- **Edits:** applied and skipped, by ID.
- **Workers:** their number and the cache settings.

### Valleys

The valley probe is two documented commands run in the image under the memory cap, not a tool command:

1. warp GLO-90 (`copernicus-dem-90m`, unedited) over the data extent to 90 m and to 180 m;
2. run WhiteboxTools' depression breaching and D8 flow accumulation on each.

"Fits" means the container was not killed for memory. The result goes in `docs/map-trial/step-1.md`. Edits do not change memory use, so the unedited data is enough to answer the question. The valley layer itself is specified only if the probe fits.

### The app

**`:core:map`** (new library module, stays in v1) is the only module that touches MapLibre (ADR 0003). Its public interface takes plain data:

- **`MapFiles(relief: File, base: File)`**.
- **`MapStyleSpec(relief: ReliefSource, reliefFade: ZoomRange, colours: MapColours)`**:
  - `ReliefSource` is `Painted(maxZoom)` or `Value(maxZoom, ramp: List<RampStep>)`;
  - `ZoomRange(start, end)` is in MapLibre zooms;
  - `MapColours(sea, land, coast, ink)` holds ARGB integers;
  - `RampStep(value: Double, colour: Int)`.
- **`CameraLimits(west, south, east, north, minZoom, maxZoom)`**.
- **`MapCamera`**, with `suspend fun animateTo(lat, lon, zoom, durationMs)`.
- **`MapEvent`**: `FullyRendered`, and `FrameRendered(atMillis: Long)`, where `atMillis` is `SystemClock.elapsedRealtime()` when the frame finished.
- **`SeerahMap(files, style, limits, modifier, onCamera: (MapCamera) -> Unit, onEvent: (MapEvent) -> Unit)`**: hosts MapLibre's map view, builds the style, applies `setLatLngBoundsForCameraTarget` and the zoom limits, hands out its `MapCamera` once the map is ready, and reports events.

The style is built by `styleJson(files, spec)`:

- **Sources:** `pmtiles://file://` URLs; the relief source is declared with `tileSize` 256 and the spec's `maxzoom`.
- **Layers:** a sea background; `land` fill and `coast` line; and the relief as either:
  - a `raster` layer, with opacity interpolated over `reliefFade`; or
  - a `color-relief` layer on a `raster-dem` source (`encoding: terrarium`), with the ramp.
- **Option (c)'s ramp:** thresholds at stored values 42.5, 127.5 and 212.5, giving ink alpha 0, 0.2, 0.4 and 0.6 between them. It is written with `interpolate linear` and paired stops one stored value apart, so each threshold is a hard edge: 42 → 0, 43 → 0.2, 127 → 0.2, 128 → 0.4, 212 → 0.4, 213 → 0.6. Stored values are whole numbers, so no pixel's stored value falls inside a pair. Values below the first stop or above the last take its colour.
  - **Why not `step`** (owner, 2026-11-17): MapLibre Native resolves only `interpolate` stops exactly and samples any other ramp 37 m apart over −500 to 9000 m (see Facts), which would lose the 0–255 values. The value encoding (0–255) is kept.
- **Option (c)'s fade:** the `color-relief` layer's opacity is interpolated over `reliefFade`, like the `raster` layer's.
- **Numbers:** every number in the style is a named constant or comes from the spec. The colours come from the spike's style values until `:core:designsystem` exists.

**The `benchmark` build type** (in `:app`):

- **Settings:** `initWith(release)`, R8 on, not debuggable, signed with the debug key, application ID suffix `.benchmark`, with `matchingFallbacks` set to `release` for library modules.
- **Dependencies:** only this build type depends on `:core:map` (`benchmarkImplementation`). `release` gains no new dependency.
- **Trial code:** everything lives in `android/app/src/benchmark/`, and is removed after the trial.

The trial code:

- **Files on the device:** the owner runs `adb push <folder> /sdcard/Android/data/app.seerah.benchmark/files/trial/` once per variant (owner, 2026-11-14). The app lists the folders under its own external files folder and reads each `metadata.json` and `camera-path.json` with `org.json`. If `adb push` fails on a device in step 1, an in-app folder import is added as a fallback.
- **Trial screen:** `TrialActivity` has its own launcher entry, "Map trial". It opens the view named in its launch intent, or the last one used, and offers the views of the imported variants:
  - zoom 12: `Painted(12)`, fade at MapLibre zooms 11.5–13.5, the spike's 12.5–14.5 converted to MapLibre zooms;
  - zoom 11: `Painted(11)`, same fade;
  - option (a): `Painted(12)`, fade at 13.5–15.5, a trial variable;
  - option (c): `Value(12, ramp)`, fade at 13.5–15.5, the same trial variable as option (a) (owner, 2026-11-17);
  - per-zoom: `Painted(12)` on the per-zoom variant.
- **Measurement overlay:**
  - **Frame rate**, following `Stats.kt`: each frame's gap is kept if it is 1–250 ms. Over the last 30 kept gaps, the rate is 1000 × 30 ÷ their sum, published at most once a second. Lowest = the minimum published rate.
  - **Memory:** `Debug.getPss()`.
  - **Open time:** `SystemClock.elapsedRealtime() − Process.getStartElapsedRealtime()` at the first fully rendered map.
  - **Shown:** the device, the view, the variant's eight-character fingerprint and the camera path's runs.
  - **Cost:** the overlay updates only when a value is published, never per frame, so it does not lower the rate it measures.
- **Camera path:** a list of stops (latitude, longitude, MapLibre zoom 4 to 14, duration) and the camera limits, read from `camera-path.json`, which `seerah-content/map/trial/` provides and the owner copies into each variant folder, so the edit locations stay private.
  - **Runs:** three, with each run's typical and lowest rates shown, plus the median of the typical rates and the minimum of the lowest rates.
  - **Export:** the results can be copied as text.

`docs/spike/measurements.md` line 7 describes an older frame-rate method. It is marked out of date in this change, pointing to `Stats.kt`.

### The synthetic example (`map-data/example/`, public, CI)

The example contains:

- a profile, edits and lock for a window of about 0.25° × 0.25° at a neutral place;
- `block_zoom: 11` (256-pixel blocks, so the window has several);
- one present cell and one neighbour marked `absent: true`;
- a window edge crossing land;
- one small `raise` edit crossing a block edge, `review: approved`.

CI caches the one cell and the Natural Earth files.

The tests build the example:

- **Twice from scratch**, comparing the PMTiles bytes.
- **Once killed during the block stage and once during the tile stage**, then resumed, comparing the decoded tiles with a clean build.
- **Once with `block_zoom` set so one block covers the window**, comparing the decoded zoom-12 tiles with the many-block build. This catches seams from the warp, the mask and the edits.
- **Coast check:** one assertion that no coast vertex lies on the window edge.

### Order of work

As decided (owner, 2026-11-14). Each step ends with something the owner can check:

1. **Foundations.**
   - Build: the image and launcher; `fetch` and `build` for the three windows; `:core:map`; the `benchmark` build type; the example in CI.
   - Checks: the step 1 checks below, and the valley probe.
   - Owner: approves the test edits.
2. **Small windows on the three devices.** The owner judges items 2, 3, 5, 8 and 11 on the windows, which fixes the style settings and the encoding.
3. **About 10% of the data extent** (a contiguous band of blocks across Najd and the Hijaz). Its time and disk are measured and extrapolated to the full extent. The trial stops if the extrapolation exceeds 12 hours or 120 GB.
4. **The full data extent, once:** items 1, 6, 8, 9, 10 and 11.
5. **Valleys:** item 7, only if the probe fits; otherwise it is deferred.

### Step 1 checks

| # | Check | Fallback | Recorded in |
| --- | --- | --- | --- |
| 1 | `adb push` into the benchmark app's external files folder works on all three devices | add the in-app folder import | `step-1.md` |
| 2 | MapLibre honours a raster source's `maxzoom` on `pmtiles://` | cut a zoom-11 copy with `pmtiles extract --maxzoom=11` | `step-1.md` |
| 3 | `color-relief` with option (c)'s paired `interpolate` stops draws sharp band edges on the device (the ramp reading is confirmed in MapLibre's source, see Facts) | report to the owner before step 2 | `step-1.md` |
| 4 | `addOnDidFinishRenderingFrameListener` reports every frame MapLibre draws | none needed: it is the spike's method, so results stay comparable | `step-1.md` |
| 5 | GDAL writes each tile atomically | keep "delete and retile" (already the default) | `step-1.md` |
| 6 | Average resampling of the painted RGBA keeps the ink colour under semi-transparent pixels | write the ink in every pixel (already done), and check one decoded edge tile | `step-1.md` |
| 7 | Water Body Mask class values on a sample cell (expected: 0 no water, 1 ocean, 2 lake, 3 river) | adjust the mask rule | `step-1.md` |
| 8 | The filesystem behind Docker's named volumes handles about 100,000 small files | move `seerah-map-work` to another disk | `step-1.md` |
| 9 | The GHCR package is public and free to pull | use the owner's personal access token for pulls | `step-1.md` |
| 10 | Licensee accepts MapLibre Native and its transitive dependencies | add a reviewed exception to `DependencyRules.kt` with the owner's approval | `step-1.md` |
| 11 | The three devices' screen widths in dp | recompute Natural Earth's upper zoom | `step-1.md` |
| 12 | The per-worker memory allowance for 2,048-pixel blocks | smaller `block_zoom` | `step-1.md` |

### Known risks and accepted costs

- **A relief file over 2 GiB**, GitHub's release-file limit (ADR 0009 decision 8). Checked by item 1's pass rule; zoom 11 is the fallback.
- **M2's temporary tile folder**, about 100,000 files, and a tile stage that restarts after an interruption if GDAL does not write tiles atomically. Checked by step 1 check 5 and step 3's time.
- **Edits narrower than about 500 m mostly vanish**, because edits come before the smoothing (owner, 2026-11-14). Checked by the test edits in step 2. If real reconstructions need narrower shapes, that decision is revisited after the trial.
- **The blur's ground spread varies by about 13%** between 12° N and 32° N, because blurs are in pixels while the slope is corrected for latitude. Checked by the owner in step 4.
- **Trust-on-first-use hashes are lost** if the `seerah-map-cache` volume is pruned and the bucket's files change. Accepted, because the trial is one-off and the committed lock detects any change.
- **One commit per tuning try,** because decision 8 refuses uncommitted changes. Accepted for traceability.
- **The `benchmark` build type may overlap with the Baseline Profile plugin's own build types** once `:benchmarks` exists. ADR 0004's note says `:benchmarks` targets this build type or replaces it.

## Alternatives Considered

Each alternative was weighed in the concepts brief ("brief") or while drafting this specification ("drafting"). The decisions are the owner's (the author), taken on 2026-11-14.

### Viewing the trial

#### A1. Reuse the spike app (build 11) as the viewer (brief)

- **Pros:** fastest; already measured on the three devices.
- **Cons:** builds on spike code, which AGENTS.md makes reference-only; its speed numbers would be the spike's, not v1's.
- **Reason for rejection:** Rejected by author: the trial must measure v1's map code.

#### A2. A separate throwaway viewer app (brief)

- **Pros:** quick to write; fully separate from v1.
- **Cons:** discarded afterwards, so nothing it proves carries into v1.
- **Reason for rejection:** Rejected by author: the first piece of `:core:map` stays as v1 code, so the trial's work is kept.

#### A3. Import trial files into the release APK, or a dated exception to ADR 0007 (brief)

- **Pros:** no new build type; release is what ADR 0002 measures.
- **Cons:** a file import in `release` is a way to load unsigned files, which ADR 0007 decision 10 forbids; an exception weakens a security rule and can ship by mistake.
- **Reason for rejection:** Rejected by author after review: `release` keeps no unsigned path; the `benchmark` build type gets the narrow note instead.

#### A4. A separate trial app module, `:app-maptrial` (brief)

- **Pros:** complete isolation from the app.
- **Cons:** changes ADR 0003's module layout (needs an ADR) and duplicates the app's setup.
- **Reason for rejection:** Rejected by author: the `benchmark` build type isolates the trial in one source set without either cost.

### Building the map

#### A5. Tile-by-tile rendering written straight into PMTiles (brief)

- **Pros:** one render path; no temporary tiles.
- **Cons:** each 256-pixel tile reads a border of about 26–35 pixels, 45–60% extra pixels; about 73,000 separate GDAL reads; Protomaps' Python writer only produces a file at the end, so there is no real resume.
- **Reason for rejection:** Rejected by author after review: it fails the resume and efficiency criteria.

#### A6. M1: block rendering with our own scheduler and SQLite staging (brief)

- **Pros:** one resume mechanism; no temporary tile folder; averaging on values before encoding; option (b) would use the same path. Recommended by five of seven reviewers.
- **Cons:** we would write and maintain tile slicing, averaging down and parallel tiling, which GDAL's tiler already does.
- **Reason for rejection:** Rejected by author: M2 hands tiling, averaging down and parallel work to GDAL. Accepted cost: a temporary tile folder and a tile stage that may restart (see Known risks).

#### A7. M3: one big raster (the spike's method, rio-pmtiles, or `gdal_translate` to MBTiles) (brief)

- **Pros:** the least new code.
- **Cons:** the styled data extent is about 23 GB per raster and about 40 GB of temporary files, beyond the owner's free memory; rio-pmtiles documents itself for sources of about 1 GB; the MBTiles path drops empty tiles, resamples lossy WebP twice and has no parallel work.
- **Reason for rejection:** Rejected by author: it cannot run on the owner's machine and breaks decision 5.

#### A8. M4: a bigger cloud machine (brief)

- **Pros:** the simplest code.
- **Cons:** costs money, against the project's rule to stay on free hosting and tools until the first public release; ADR 0009 decision 8 has real builds run on the owner's machine anyway.
- **Reason for rejection:** Rejected by author: it costs money and does not remove the need to build on the owner's machine.

#### A9. Style C entirely in GDAL (`gdaldem hillshade` and a stepped `color-relief`) (brief)

- **Pros:** no custom style code.
- **Cons:** changes the approved look (a different gradient method, no band blur); needs whole-extent intermediate files that cannot resume; edits stay custom anyway; harder to test than one pure function.
- **Reason for rejection:** Rejected by author after review: the look is the owner's approved design, and the style function is the right home for the project's own logic.

#### A10. Macrobenchmark's frame metrics instead of the overlay (drafting)

- **Pros:** standard AndroidX tooling.
- **Cons:** it measures the app's UI thread, not MapLibre's render thread, and its numbers would not compare with the spike's `Stats.kt`.
- **Reason for rejection:** Rejected by author after review: the trial needs readings comparable with build 11.

### Terrain and look

#### A11. Edits after the smoothing, or with their own smaller smoothing (brief)

- **Pros:** edits narrower than about 500 m stay visible at close zooms.
- **Cons:** an edit drawn too sharp looks stamped onto softer surroundings; a second smoothing strength adds a soft ring at its edge and widens the block border.
- **Reason for rejection:** Rejected by author: one rendering path, and edits blend into their surroundings. Revisited after the trial if real reconstructions need narrower shapes.

#### A12. Keep the spike's uncorrected shading (brief)

- **Pros:** keeps build 11's look exactly, with no calibration.
- **Cons:** slopes look about 2% flatter at 12° N and 15% flatter at 32° N, so the north looks flatter than it is.
- **Reason for rejection:** Rejected by author: the same slope should look the same everywhere; the exaggeration is recalibrated so the Hijaz still matches build 11.

#### A13. Relief only inside a peninsula outline, or fading out beyond it (brief)

- **Pros:** a smaller measured size.
- **Cons:** a hard outline shows as a seam across Jordan and Iraq; a fade is one more design element to judge.
- **Reason for rejection:** Rejected by author for the trial: relief covers all land in the data extent, and the run report shows what the outside land costs.

### Scope and settings

#### A14. A wider trial box, to latitude 34° N (brief)

- **Pros:** covers Bosra, southern Syria and lower Iraq for later phases.
- **Cons:** about 10–15% more area and size.
- **Reason for rejection:** Rejected by author: the peninsula box is the trial's working value; the final bounds stay an open decision.

#### A15. Trial settings in the public repository (brief)

- **Pros:** nothing in them is secret; one checkout builds the trial.
- **Cons:** a thrown-away configuration in the public repository, and the edit locations made public.
- **Reason for rejection:** Rejected by author: all map configuration stays in `seerah-content`.

#### A16. A higher minimum zoom, or sea colour, beyond the data extent (drafting)

- **Pros:** one coast source; no join where the data's edge crosses a coast.
- **Cons:** a higher minimum zoom removes the whole-peninsula view on a phone; sea colour makes land end in straight lines at the data's edge.
- **Reason for rejection:** Rejected by author: the map must look continuous; Natural Earth fills the view beyond the data, with ADR 0009 decisions 3, 6 and 7 amended in this change.

#### A17. A wider data margin so Natural Earth appears only at the widest zooms (drafting)

- **Pros:** keeps ADR 0009's original "widest zooms only" wording.
- **Cons:** about 300 more cells to download and build, and a larger measured size.
- **Reason for rejection:** Rejected by author: amending the ADR costs less than building land the app barely shows.

#### A18. Estimating the size from samples instead of a full build (brief)

- **Pros:** hours faster.
- **Cons:** items 1 and 6 require a measured full build; the spike's 8-square-degree sample is a weak basis for extrapolation, and ADR 0009 notes that the sand seas and Yemen's mountains may cost more than it.
- **Reason for rejection:** Rejected by author after review.

#### A19. Separate full builds for zoom 11, zoom 12 and option (a) (brief)

- **Pros:** each variant built exactly as it would ship.
- **Cons:** hours of duplicate building and more files on three devices.
- **Reason for rejection:** Rejected by author after review: one painted build to zoom 12 serves all three views.

---

## Non-Functional Requirements

- **Security:**
  - **App:** the trial adds no network code to the app. Trial files load only in the `benchmark` build type, never in `release` (ADR 0007 decision 10, with its dated note).
  - **Downloads:** every file `fetch` downloads is pinned in a committed lock and verified by SHA-256. The one unpinned read is the valley probe's GLO-90, which produces nothing that ships.
  - **Container:** it sees only `seerah-content/map/`, read-only, and has no network except during `fetch`.
- **Performance:** measured, not assumed, against item 6's limits and item 11's targets.
- **Rollout and removal:** nothing reaches users. After the owner signs off the trial, one change removes:
  - `android/app/src/benchmark/kotlin/app/seerah/trial/` and its manifest entry;
  - `seerah-content/map/trial/`;
  - the losing encoder (painted or value);
  - per-zoom mode;
  - the unused `ReliefSource` branch;
  - `FrameRendered`, unless v1 uses it.

  `:core:map`, the build tool and the `benchmark` build type stay.

## Additional Resources

- **Decisions:** ADRs [0002](../adr/0002-proposed-stack.md), [0003](../adr/0003-v1-architecture-and-modules.md), [0004](../adr/0004-build-and-quality-tooling.md), [0007](../adr/0007-content-formats-compatibility-and-signing.md) and [0009](../adr/0009-map-data-pipeline.md).
- **This work:** the [concepts brief](concepts_brief.md), and the [EGM review](egm-review-map-trial/consolidated_review_report.md) with its [resolution](egm-review-map-trial/goldfish_review_report.md).
- **The spike:**
  - [findings](../spike/findings.md) and [measurements](../spike/measurements.md);
  - `tools/spike/relief_style.py`, the look's formulas;
  - `tools/spike/build_base.sh`, the tippecanoe settings and the Natural Earth layout;
  - `android/spike/spike/src/main/kotlin/app/seerah/spike/Stats.kt`, the measures.
- **External references, for the facts this plan rests on:**
  - the `copernicus-dem-30m` bucket's listing and `tileList.txt` (read 2026-11-14);
  - GDAL's [`gdal raster tile`](https://gdal.org/programs/gdal_raster_tile.html) and [PMTiles driver](https://gdal.org/en/stable/drivers/vector/pmtiles.html);
  - Protomaps' [PMTiles repository](https://github.com/protomaps/PMTiles), `python/pmtiles/pmtiles/convert.py` and `writer.py` (read 2026-11-14);
  - [rio-pmtiles](https://pypi.org/project/rio-pmtiles/);
  - MapLibre Native's [Android changelog](https://github.com/maplibre/maplibre-native/blob/main/platform/android/CHANGELOG.md) (13.0.0: color-relief);
  - MapLibre Native's [`render_color_relief_layer.cpp`](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/renderer/layers/render_color_relief_layer.cpp) and [`color_relief.fragment.glsl`](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/shaders/color_relief.fragment.glsl) at `android-v13.7.1` (how the ramp is read);
  - the GHCR tag list for `osgeo/gdal` (read 2026-11-14).

## Future Improvements

- **Option (b)'s detail file**, if items 2 and 3 fail, in its own specification.
- **The real map package:** `map.json`, the manifest and signing (ADR 0007), `tile_schema` 1, and publishing (ADR 0008).
- **The reconstruction edit format**, decided when that work starts; `trial-0` is not it.
- **A shared strict YAML loader** in `tools/`, once ADR 0007's content build tool needs one too.

---

## Detailed Implementation Plan

Host paths are relative to the public repository root unless they start with `seerah-content/`; container paths start with `/src`, `/data`, `/cache`, `/work` or `/out`. Python follows the repository's ruff settings (Python 3.11). Kotlin follows ADR 0004 (spotless, ktlint, the Compose rules). No code is copied from the spike.

### Phases

Each phase ends with the code building and its tests passing in CI.

1. **Tool core and example** (files 1–18): the image, the launcher, `fetch`, the block stage, the tile stage and pack, the run report, the example, and the `map` workflow.
2. **The map on the device** (files 19–31): the library convention plugins, `:core:map`, the `benchmark` build type, the trial code, and the Android workflow changes.
3. **Base layer and documents** (files 32–44): land, coast and Natural Earth; the ADR notes; and the documents.

### Phase 1: tool core and example

#### 1. `tools/map/Dockerfile` (Create)

- **Scope:** the toolchain image.
- **Changes:**
  - Stage `tippecanoe`: build tippecanoe from a pinned commit on the same Ubuntu base.
  - Final stage: `FROM ghcr.io/osgeo/gdal:ubuntu-small-3.13.3@sha256:<digest>`.
  - Copy the tippecanoe binaries.
  - Download go-pmtiles and WhiteboxTools for pinned versions and check each SHA-256.
  - `pip install --require-hashes --break-system-packages -r requirements.txt`.
  - No tool code and no `ENTRYPOINT`; a comment gives each pin's source.

#### 2. `tools/map/requirements.in`, `tools/map/requirements.txt` (Create)

- **Scope:** Python dependencies of the image.
- **Changes:**
  - **Direct dependencies:** `pmtiles`, `scipy`, `morecantile`, `ruamel.yaml`, `jsonschema`, `pytest`. numpy comes with GDAL's bindings in the image; step 1 confirms its version.
  - **`requirements.txt`:** generated with `pip-compile --generate-hashes`.

#### 3. `tools/map/pyproject.toml` (Create)

- **Scope:** pytest configuration and package metadata for `seerah_map` (`src/` layout).
- **Changes:** the test paths; a `slow` marker for tests that need GDAL and the example's downloads; and `pythonpath = ["src"]`, so `pytest tools/map` finds the package without installing it.

#### 4. `tools/map/run.py` (Create)

- **Scope:** the host launcher (standard library only).
- **Changes:**
  - **Usage:** `run.py [--private PATH] [--out PATH] [--memory-mib N] [--per-worker-mib N] [--workers N] -- <command> [args]`.
  - **Behaviour:** as described under "The image and the launcher".
  - **Defaults:** `--private` is `../seerah-content`, `--out` is `map-data/dist`, and `--memory-mib` is 8192.
  - **Errors:** a missing Docker, a dirty checkout, a missing image or a missing output folder each give a message naming the next step.

#### 5. `tools/map/src/seerah_map/cli.py` (Create)

- **Scope:** the container entry point (`python -m seerah_map`).
- **Changes:**
  - **Subcommands:** `fetch`, `build` and `clean`, as in the Commands table.
  - **Provenance:** read from the environment that `run.py` sets.
  - **Exit codes:** 0 for success, 1 when a check fails, 2 for a usage or configuration error.
  - **Errors:** each names the file, the expected value, the value found, and the next step.

#### 6. `tools/map/src/seerah_map/config.py` and `tools/map/src/seerah_map/strict_yaml.py` (Create)

- **Scope:** loading and validation.
- **Changes:**
  - **`strict_yaml.load(path)`:** ruamel.yaml with the YAML 1.2 core schema, duplicate keys rejected, and timestamps left as strings (ADR 0007's rule).
  - **`load_profile`, `load_edits` and `load_lock`:** validate with JSON Schema and return frozen dataclasses with units in the field names (`margin_deg`, `feather_m`).

#### 7. `tools/map/src/seerah_map/schema/profile.schema.json`, `edits.schema.json` (Create)

- **Scope:** JSON Schemas (draft 2020-12).
- **Changes:**
  - **`profile`:** the fields listed under Inputs, with `purpose` the constant `trial`.
  - **`edits`:**
    - `schema` is the constant `trial-0`, and `purpose` the constant `trial`;
    - each edit has `id`, `geometry`, `operation`, `amount_m`, `height_m`, `width_m`, `feather_m`, `review` and optional `sources`;
    - `review` and `sources` are copied from `content/schema/content.schema.json`'s `$defs`, with a comment naming the source, since a cross-file reference would not resolve offline.

#### 8. `tools/map/src/seerah_map/grid.py` (Create)

- **Scope:** pure geometry.
- **Changes:**
  - the zoom-12 global grid;
  - `blocks(extent, block_zoom)`;
  - `border_px(settings, edits, latitude)`;
  - `row_latitudes(window)`;
  - `expected_tiles(extent, max_zoom)`;
  - `view_reach_km(zoom, half_width_dp, latitude)`;
  - `natural_earth_max_zoom(margin_deg, half_width_dp, latitude)`.

  Tile maths uses morecantile.

#### 9. `tools/map/src/seerah_map/fetch.py` (Create)

- **Scope:** the `fetch` command, as in the Commands table.
- **Changes:** standard library `urllib` only, one file at a time.

#### 10. `tools/map/src/seerah_map/workarea.py` (Create)

- **Scope:** the fingerprint, work areas and atomic writes.
- **Changes:**
  - `fingerprint(...)` over the inputs listed under Inputs;
  - `work_area(root, fingerprint)`;
  - `atomic_write(path)`, which writes to `<name>.tmp` and renames;
  - `list_areas(root)` with their sizes;
  - `remove(area)`.

#### 11. `tools/map/src/seerah_map/style.py`, `encode.py`, `mask.py`, `edits.py` (Create)

- **Scope:** pure functions, as described under "The block stage".
- **Changes:**
  - `style.darkness(elevation, land, row_lat_deg, pixel_m, settings, banded)` and `style.edge_fade(...)`;
  - `encode.painted(...)` and `encode.value(...)`;
  - `mask.land_mask(wbm, sigma_px)`;
  - `edits.apply(elevation, edits, window)`.

  Each has a docstring stating its inputs, outputs and units. The spike's settings are named constants, with a comment citing `relief_style.py`.

#### 12. `tools/map/src/seerah_map/blockstage.py` (Create)

- **Scope:** the block stage.
- **Changes:**
  - builds the VRT of locked cells, with absent cells filled as ocean and 0 m, and nodata outside the data extent;
  - runs blocks in a `ProcessPoolExecutor` sized as described;
  - skips finished blocks;
  - writes `style/<block>.tif` and `mask/<block>.tif` through `atomic_write`, plus `style.vrt` and `mask.vrt`;
  - logs one line per block with its ID.

#### 13. `tools/map/src/seerah_map/tilestage.py` (Create)

- **Scope:** the tile stage.
- **Changes:** runs `gdal raster tile` with the settings above, handles `tiles.done`, and runs the completeness check.

#### 14. `tools/map/src/seerah_map/pack.py` (Create)

- **Scope:** writes `metadata.json`, runs `disk_to_pmtiles` in a subprocess with `PYTHONHASHSEED=0`, runs `pmtiles verify` and `pmtiles show`, and copies `metadata.json` beside the output.

#### 15. `tools/map/src/seerah_map/report.py` (Create)

- **Scope:** writes `report.json` with the fields listed under "The run report".

#### 16. `tools/map/tests/` (Create)

- **Scope:** pytest. Unit tests use synthetic arrays only; integration tests are marked `slow`.
- **Changes:**
  - `test_grid.py`: whole-pixel blocks; border growth; expected tiles; the reach table; the coverage checks.
  - `test_mask.py`: ocean is sea; lakes and rivers stay land; the byte mask.
  - `test_edits.py`: each operation inside, at the outline and past the feather.
  - `test_style.py`:
    - flat ground gives 0;
    - light direction;
    - sea is 0;
    - the same ground slope gives the same darkness at 12° N and 32° N;
    - `banded` gives four levels.
  - `test_style_regression.py`: `darkness(banded=True)` at latitude 0 with exaggeration 8 matches `fixtures/spike_relief.npz` within 1/255. The fixture is `relief_style.py` run once on a small synthetic elevation, under 1 MB, with the script's commit recorded.
  - `test_encode.py`: the ink is in every pixel; truncated alpha; Terrarium values decode within 1/255; a half-transparent 2×2 average keeps the ink.
  - `test_config.py`: duplicate keys are refused; `yes`, `no` and dates stay strings; schema errors are actionable.
  - `test_workarea.py`: a different fingerprint gets a new area; `atomic_write` leaves no partial file.
  - `test_build_example.py` (`slow`), as described under "The synthetic example": killing the build means killing the process after N progress lines.

#### 17. `map-data/example/` (Create)

- **Scope:** the public CI example (Apache-2.0 under REUSE.toml's default; synthetic, not content).
- **Changes:** `example.yaml`, `edits.yaml`, `example.lock`, `peninsula.geojson` and `camera-path.json`, as described above.

#### 18. `.github/workflows/map.yml` (Create)

- **Scope:** CI for the tool, triggered by changes under `tools/map/`, `map-data/example/` and the workflow itself.
- **Changes:**
  - **Job `image`:** computes the tag; if `ghcr.io/<owner>/seerah-map:<tag>` is missing, builds it and, on `main`, pushes it (`packages: write` for this job only).
  - **Job `test`:**
    - runs in that image with the checkout mounted;
    - runs `pytest tools/map`, including the `slow` tests;
    - caches the example's downloads with `actions/cache`, keyed on `example.lock`.
  - **Conventions:** actions pinned by commit, `permissions: {}` at the top, and `persist-credentials: false`, as in `android.yml`.

### Phase 2: the map on the device

#### 19. `android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/AndroidCommon.kt` (Modify)

- **Scope:** shared build logic.
- **Changes:** type the shared functions on `CommonExtension` instead of the application extension, so the application and library plugins both use them unchanged.

#### 20. `android/build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt`, `AndroidLibraryComposeConventionPlugin.kt` (Create)

- **Scope:** convention plugins.
- **Changes:**
  - **Library plugin:** `com.android.library`, `configureAndroid`, `configureKotlin`, `enforceMapLibreBoundary` and the licence check.
  - **Compose plugin:** adds the Compose compiler and BOM, as the application Compose plugin does.

#### 21. `android/build-logic/convention/build.gradle.kts` (Modify)

- **Changes:** register `seerah.android.library` and `seerah.android.library.compose`.

#### 22. `android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt` (Modify)

- **Changes:** add the `benchmark` build type as described, with a KDoc line citing ADR 0004's note.

#### 23. `android/gradle/libs.versions.toml` (Modify)

- **Changes:** add the MapLibre version (13.7.1, or the newest 13.x at least two weeks old) and the library `maplibre-android-opengl`; add the plugin aliases for the two library plugins.

#### 24. `android/settings.gradle.kts` (Modify)

- **Changes:** `include(":core:map")`.

#### 25. `android/core/map/build.gradle.kts` (Create)

- **Scope:** module build file.
- **Changes:**
  - **Plugins and namespace:** the two library plugins; namespace `app.seerah.map`.
  - **Dependencies:**
    - `implementation`: MapLibre (OpenGL), Compose `runtime`, `ui` and `foundation`;
    - `api`: only what the public signatures expose (Compose `runtime` for `@Composable` and `ui` for `Modifier`).

    `buildHealth` decides the final split.
  - **Tests:** test dependencies as in `:app`.

#### 26. `android/core/map/src/main/kotlin/app/seerah/map/` (Create)

- **Scope:** the public types and functions under "The app".
- **Files:**
  - `MapFiles.kt`, `MapStyleSpec.kt` (with `ReliefSource`, `ZoomRange`, `MapColours` and `RampStep`), `CameraLimits.kt`, `MapCamera.kt` and `MapEvent.kt`;
  - `StyleJson.kt` (internal);
  - `SeerahMap.kt`.

  Each public type has KDoc.

#### 27. `android/core/map/src/test/kotlin/app/seerah/map/StyleJsonTest.kt` (Create)

- **Scope:** unit tests of the generated style.
- **Changes:** for each relief source, the style:
  - parses as JSON;
  - has the right layer type;
  - has the fade stops;
  - uses `terrarium` encoding for the value source;
  - uses `pmtiles://file://` URLs;
  - has `tileSize` 256 and the spec's `maxzoom`;
  - has the ramp as `interpolate linear` with the paired stops.

#### 28. `android/core/map/README.md` (Create)

- **Changes:** the module's job, its public types, how the style is built, and that it holds no trial code.

#### 29. `android/app/build.gradle.kts` (Modify)

- **Changes:** `"benchmarkImplementation"(project(":core:map"))`.

#### 30. `android/app/src/benchmark/` (Create)

- **Scope:** trial code, removed after the trial.
- **Files:**
  - `AndroidManifest.xml`: declares `app.seerah.trial.TrialActivity` with the launcher label "Map trial".
  - `kotlin/app/seerah/trial/TrialActivity.kt`: lists the variant folders, offers the views, hosts `SeerahMap`, and opens the view from the launch intent or the last one used.
  - `kotlin/app/seerah/trial/TrialFiles.kt`: reads `metadata.json` and `camera-path.json` with `org.json`.
  - `kotlin/app/seerah/trial/Views.kt`: the five views and their `MapStyleSpec`s.
  - `kotlin/app/seerah/trial/FrameStats.kt`: the frame-rate rule, in pure Kotlin.
  - `kotlin/app/seerah/trial/Overlay.kt`: the overlay, updated only on publish.
  - `kotlin/app/seerah/trial/CameraPath.kt`: runs the stops three times, and builds the results text.

  English text: it is a developer tool for the owner, never shipped.

#### 31. `android/app/src/testBenchmark/kotlin/app/seerah/trial/FrameStatsTest.kt` (Create), and `.github/workflows/android.yml` and `tools/android/check_no_trial_code.py` (Modify / Create)

- **Tests:** `FrameStatsTest` covers a steady 60 fps, a 300 ms pause that is ignored, and a slow stretch that lowers the lowest rate.
- **Workflow:** add `assembleBenchmark`; run `check_no_trial_code.py` on the release APK; upload the benchmark APK as artifact `seerah-app-benchmark`. No path-filter change is needed.
- **The check:**
  - finds `aapt2` under `$ANDROID_HOME/build-tools/` (newest version);
  - dumps the release APK's manifest;
  - fails if it found no manifest, or if any component name starts with `app.seerah.trial`.

  Its docstring cites ADR 0007 decision 10.

### Phase 3: base layer and documents

#### 32. `tools/map/src/seerah_map/base.py` (Create)

- **Scope:** the base layer, as described under "The base layer", using tippecanoe, `tile-join`, `gdal raster polygonize` and `gdal_contour` as subprocesses.

#### 33. `tools/map/tests/test_build_example.py` (Modify)

- **Changes:** add the coast assertion: no coast vertex lies on the window edge.

#### 34. `seerah-content/map/trial/` (Create, private repository)

- **Files:**
  - `full.yaml`, `window-spike.yaml`, `window-najd.yaml` and `window-yemen.yaml`;
  - `edits.yaml`: three banks about 300 m, 600 m and 1.2 km wide and 10–30 m high; a carved bed about 600 m wide and 5–10 m deep; a flattened block about 1.5 km across over low hills. All are on neutral Najd ground, `review: draft`, with a note that they are synthetic;
  - `camera-path.json`;
  - `peninsula.geojson`;
  - a `README.md` saying these are trial values, refused by packaging and deleted after the trial.

  The locks are added as `fetch` writes them.

#### 35. `docs/adr/0007-content-formats-compatibility-and-signing.md` (Modify)

- **Changes:** a dated note under decision 10 (owner, 2026-11-14): the `benchmark` build type may load unsigned trial map files; it is never published, and `release` keeps no unsigned path.

#### 36. `docs/adr/0004-build-and-quality-tooling.md` (Modify)

- **Changes:** a dated note under its build types (owner, 2026-11-14): the `benchmark` build type is brought forward for the map trial; it is release-like, debug-signed, has its own application ID, and is never published; `:benchmarks` targets it or replaces it with the Baseline Profile plugin's types.

#### 37. `docs/adr/0009-map-data-pipeline.md` (Modify)

- **Changes:** the amendment to decisions 3, 6 and 7, already applied in this change, with its status line linking this specification.

#### 38. `docs/open-decisions.md` (Modify)

- **Changes:**
  - **Reconstruction row:** note that `trial-0` is not the reconstruction edit format.
  - **Bounds and zoom row:** note the trial's working values (the box, the 1° margin, minimum zoom 4) and that they are not decisions.

#### 39. `docs/roadmap.md` (Modify)

- **Changes:** add the map trial as the next step after ADR 0009, linking this specification and `docs/map-trial/`; correct the line saying ADRs 0003 and 0004 are Proposed.

#### 40. `docs/spike/measurements.md` (Modify)

- **Changes:** mark line 7's frame-rate description as out of date, pointing to `Stats.kt`.

#### 41. `docs/map-trial/README.md` and `docs/map-trial/step-<n>.md` (Create)

- **Changes:**
  - **README:** the eleven items with their status, and links to each step's report.
  - **Step reports:** each records the run report's figures, the step 1 checks (step 1 only), device screenshots with the overlay, and the owner's judgement per item.

#### 42. `tools/README.md`, `map-data/README.md` (Modify)

- **Changes:**
  - **`tools/README.md`:** add rows for `map/` (run with `map/run.py`; CI `map` workflow) and `android/check_no_trial_code.py`.
  - **`map-data/README.md`:** describe `example/` and the trial output folder layout, stating it is not `tile_schema` 1; update the line saying ADR 0009 is proposed.

#### 43. `THIRD_PARTY_NOTICES.md` (Modify)

- **Changes:** add the map build tools and data to the existing table, noting that none ships in an app build yet:
  - **Tools:** GDAL, tippecanoe, go-pmtiles, Protomaps' `pmtiles`, scipy, morecantile, ruamel.yaml and WhiteboxTools.
  - **Data:** Copernicus GLO-30 and its Water Body Mask, and Natural Earth.

  MapLibre Native is added when `release` first depends on `:core:map`.

#### 44. `.github/dependabot.yml`, `docs/design/map-trial-build.md`, `docs/design/concepts_brief.md`, `docs/design/egm-review-map-trial/` (Modify / Create)

- **Changes:**
  - **Dependabot:** add the `docker` and `pip` ecosystems for `/tools/map`.
  - **Committed with them:** this specification, its brief and the review reports.

### Verification Plan

- **Build:** the `map` workflow's image builds; `cd android && ./gradlew spotlessCheck check buildHealth assembleRelease assembleBenchmark`.
- **Tests:** `pytest tools/map` inside the image (unit and `slow`); `./gradlew check` runs the `:core:map` and `testBenchmark` tests.
- **Lint:** `pre-commit run --all-files`; `python3 tools/validate_content.py`.
- **Release guard:** `check_no_trial_code.py` on the release APK, failing if it checked nothing.
- **Pure modules:** each has tests of its documented behaviour (`grid`, `mask`, `edits`, `style`, `encode`, `workarea`, `strict_yaml`); the stages are covered by the example's integration tests.
- **On devices** (phase 2 onwards): the benchmark APK installs beside the normal app on the three devices; `adb push`, the views, the overlay and the camera path work; the step 1 checks are recorded in `docs/map-trial/step-1.md`.
- **Trial items:** judged by the pass rules in the items table, recorded in `docs/map-trial/`.

---

<!-- Provenance: Indicates initial structural drafting via EGM. -->

Drafted with EGM.
