# Map trial build (ADR 0009 decision 13)

- **Author:** MohdFaragDev
- **Date:** 2026-11-14

## Overview

[ADR 0009](../adr/0009-map-data-pipeline.md) decision 13 makes a trial build the gate before the first map package. The trial answers what the ADR left to measurement: the regional maximum zoom (11 or 12), which close-up option ships (a: enlarged relief, c: colour-relief, b: a second detail file only if both fail), the real map size, whether valleys ship, and which modern features need cleanup edits. The owner judges every visual check on the three test devices (Oppo A55, Galaxy A54, Huawei MatePad 11.5) side by side with spike build 11.

The trial needs three things that do not exist yet:

1. **A map build tool** in `tools/map/` (Python, public, Apache-2.0), running in a pinned container on the owner's machine. It renders style C relief from Copernicus GLO-30 elevation and its Water Body Mask (both downloaded without an account from the AWS bucket `copernicus-dem-30m`, which holds `DEM.tif` and `AUXFILES/…_WBM.tif` for every 1°×1° cell; checked on 2026-11-14).
2. **A first piece of the real v1 map** in `:core:map` (MapLibre Native, OpenGL, PMTiles read from files on the device), opened from a temporary trial screen that switches variants and shows ADR 0002's measures.
3. **A synthetic test edit** (a raised bank, a carved bed, a flattened block) at a neutral place, standing in for a reconstruction that does not exist yet. It never ships.

The trial extent is latitude 12–32° N, longitude 34–60° E, chosen by the owner as a working value (owner, 2026-11-14). The final bounds stay an open decision.

**Owner's failure criteria** (owner, 2026-11-14). The trial fails if any of these holds:

- **Visual quality:** visible seams, missing tiles, rendering artefacts, or noticeably bad terrain.
- **Performance:** ADR 0002's targets missed on any of the three devices, especially the Oppo A55.
- **Build practicality:** too much memory or disk, unreasonable build time, or a process that cannot be repeated reliably on the owner's machine. Build cost is measured, never assumed.
- **Code quality (major priority):** unnecessary custom code, duplicating what mature tools do, poor separation of concerns, fragile architecture, or heavy maintenance.
- **Reliability:** a build that cannot recover from an interruption, gives different output for the same input, or leaves temporary files behind.

## Analysis

Seven fresh-context reviewers (design, codebase details, zoom-out, comprehension, implementation readiness, structure, inventive catalyst) stress-tested the concept. After removing duplicates they raised 3 blockers, 25 major findings and 12 nits. The main points follow.

### Conflicts with accepted decisions

- **Viewing the trial in the release APK breaks ADR 0007 decision 10** (all seven reviewers). ADR 0002 measures performance in release builds only, and debug builds are about twice as slow (the spike measured 7.2 s against 3.5 s to open). But ADR 0007 decision 10 requires a build check proving release builds have no way to load unsigned files, and a file-picker import is exactly that. The way out six reviewers agree on: a release-like build type (for example `trial` or `benchmark`: `initWith(release)`, shrunk with R8, not debuggable, signed with the debug key, with its own application ID suffix). The trial screen, the import and the measurement overlay live only in that build type's source set. ADR 0004 already plans a benchmark build type; bringing it forward needs a short note in ADR 0004 and the owner's agreement.
- **The test edit touches an open decision.** A bank, a carved bed and a flattened block add shape, so they are reconstruction edits, whose format is still open. Whatever file format the trial uses would become that format by default. The edits also need `approved` to be applied (decision 2). The trial format must therefore be explicitly provisional (for example `schema: trial-0`), reuse the content schema's source and review types, and be read only through an explicit trial input that the build log records.
- **Trial values must be refused by tools, not just labelled.** A trial bounding box in the real `map/coverage.yaml` would fill an open decision with a guess. Trial settings go in a separate trial profile, trial output carries a `trial: true` marker in its PMTiles metadata, and the later package-and-sign step refuses it, the way unapproved content is refused; the test of that refusal comes with the packaging step, which is outside the trial.

### The build method

The earlier labels (a) and (c) clashed with ADR 0009's detail options (a) and (c), so the build methods are renamed here:

| | Method | What it is |
| --- | --- | --- |
| **M1** | Block rendering, our own scheduler | The extent is split into work units aligned to the tile grid (one tile at zoom 8–10, chosen by measured memory). Each unit reads its elevation once with a border, applies edits and style C, cuts its zoom-12 tiles by slicing the array, averages down inside the unit, and writes the tiles to an MBTiles-shaped SQLite file, one transaction per unit with a "units done" table. A short final pass builds the lowest zooms. Protomaps' converter turns the file into PMTiles. |
| **M2** | Block styling, then GDAL's tiler | Our code styles the extent in blocks with borders into compressed image files joined by a VRT. `gdal raster tile` (GDAL 3.11) cuts every tile, transparent ones included, into a folder. Protomaps' Python `disk_to_pmtiles` turns the folder into PMTiles. |
| M3 | One big raster | The spike's method or rio-pmtiles, scaled up. Rejected (see Alternatives). |
| M4 | A bigger cloud machine | Rejected (see Alternatives). |

Neither of the first versions survived review unchanged. Tile-by-tile rendering (the first idea) read 45–60% extra pixels for borders, made about 73,000 separate GDAL reads, and had no real way to resume: Protomaps' Python writer only produces a file at the end. M2 as first described still needed the custom block styling, a list of every tile to put back what `--skip-blank` drops, and a folder converter, plus about 100,000 temporary files. Five reviewers recommended M1; the structure reviewer preferred M2's stage boundaries. **The owner chose M2 (2026-11-14).**

**Checked after the review (Protomaps' source code, 2026-11-14):** the Python writer removes duplicate tiles as it writes them, and the package has `disk_to_pmtiles` (folder to PMTiles) and `mbtiles_to_pmtiles`. The Go tool `go-pmtiles convert` takes MBTiles only and removes duplicates by default (FNV-128a hashes). So both M1 and M2 can hand PMTiles writing and deduplication to a mature tool, and decision 5's explicit transparent tiles cost nothing extra in either.

What still differs between M1 and M2, on the same terms:

| | M1 | M2 |
| --- | --- | --- |
| Custom concepts | border reads, slicing into tiles, averaging down, resume by unit | border reads, block files and VRT |
| Mature tools doing the rest | GDAL (reading), SQLite (staging), go-pmtiles (convert, verify) | GDAL (reading, tiling, averaging down, resume), Protomaps Python (convert) |
| Temporary disk | one SQLite file, roughly the size of the output | styled block files plus about 100,000 tile files |
| Resume | per unit, inside one transaction | twice: blocks (ours) and tiles (`--resume`, which only checks that a file exists; a half-written tile may count as done, unverified) |
| Averaging down | on pixel values before encoding, so lossy WebP is encoded once per tile | GDAL averages from the styled raster; fine for painted relief, but option (c)'s encoded values must be lossless and averaged as values |
| Option (b) detail tiles later | same code path, more zooms on fewer units | a second styled raster for the detail areas |

The strongest argument for M2 is that GDAL's tiler, parallelism and resume are mature. The strongest argument for M1 is that what it writes itself is ordinary glue (an array slice, a reshape-and-mean, one transaction per unit), while M2 adds a second resume mechanism, a large temporary folder, and a separate path for option (c) and option (b).

Every reviewer agrees on one boundary whichever method wins: **style C is one pure function** (an elevation array in; an alpha or darkness array out), with no GDAL or PMTiles inside, so the tiler can be swapped without touching it.

### What M2 must handle

Choosing M2 keeps GDAL's tiler, parallelism and resume, and leaves these to the specification, each with a test:

- **Every tile written.** `gdal raster tile` runs without `--skip-blank`, so transparent tiles exist on disk and decision 5 needs no extra step. Protomaps' writer stores identical tiles once.
- **Resume that cannot keep a broken tile.** `--resume` only checks that a file exists. After tiling, the build checks that every expected tile exists and decodes, deletes any that do not, and runs `--resume` again before converting.
- **Styled blocks on the global grid.** Our code writes the styled extent as compressed, tiled GeoTIFF blocks, each placed at whole-pixel offsets on the zoom-12 grid with a computed border, joined by a VRT. Painted blocks carry the constant ink colour in every pixel, so GDAL's averaging never darkens edges (as the spike did).
- **Option (c) stays on the same path.** The darkness value is written into one channel of a fixed fake-elevation encoding (for Terrarium: red 128, green the value, blue 0), so averaging changes only that channel and the values stay true. Tiles are written lossless (PNG or lossless WebP).
- **One resampling.** `--resampling average` and `--overview-resampling average`, from the styled raster, so lossy WebP is encoded once per tile.
- **The same bytes for the same input.** The folder-to-PMTiles step writes tiles in tile-ID order and runs with a fixed `PYTHONHASHSEED`, since Protomaps' Python writer finds duplicates with Python's `hash()` (verify that the converter's order is stable).
- **Temporary files in one place.** Styled blocks and the tile folder (about 100,000 files) sit in one work area on a native Linux filesystem (a Docker volume, not `/mnt/WorkDrive`), kept on failure for resume and removed on success.
- **Resume twice.** Styled blocks resume by block (a block is written to a temporary name and renamed when complete); tiles resume through `--resume`. Both are keyed by the input fingerprint.
- **GDAL version.** `--parallel-method` needs GDAL 3.12; the image pins a GDAL release that has it.

### Correctness rules any method must keep

- **No seams.** Style C uses only local operations, so blocks with a large enough border give exactly the whole-raster result. Four ways this quietly breaks, each guarded by a test that renders a multi-block area block by block and as one array and compares them:
  1. the border is smaller than the blur and gradient need (about 26–35 px); it must be computed from the style settings, never hard-coded;
  2. GDAL's approximate warping samples a border pixel differently from the block that owns it (use an error threshold of zero, or warp the extent once to a tiled file);
  3. an edit's feathering or "flatten to the local mean" reaches further than the border;
  4. a block not placed at whole-pixel offsets on the global zoom-12 grid.
- **No missing tiles.** A 1°×1° cell missing from the bucket (all-sea cells appear to be absent, unverified) must not look the same as a failed download. The source lock lists every cell in the extent and margin as either "file and SHA-256" or "absent upstream", and the build fails on any cell it cannot account for. One extra ring of cells is downloaded so borders at the edge read real data.
- **The same output for the same input.** Tiles are written in tile-ID order; the staging area records a fingerprint of every input (lock, coverage, edits, tool commit, image digest, style settings) and refuses to resume under a different one. Reproducible means identical bytes on the same machine and image; CI builds the example twice and compares.
- **Memory under a set limit.** The owner's machine has 13 GB with about 2 GB free while the desktop runs. GDAL's per-process cache and numpy's thread pools would overrun it at 12 workers. Worker count comes from a memory budget, `GDAL_CACHEMAX`, `OMP_NUM_THREADS=1` and `GDAL_NUM_THREADS=1` are set, and the container runs with a memory cap so an overrun stops the build cleanly; peak memory is read from the container's control group for item 6.
- **Clean coasts.** Transparent pixels must not carry black colour, or wider zooms get dark coast edges. Carry alpha only and add the constant ink colour when encoding.

### Terrain questions the trial must settle first

- **Style C's smoothing may erase the test edit** (blocker for design). The blur radius at zoom 12 is about 230–270 m on the ground, and edits are applied before rendering. A bank or carved bed narrower than about 500 m would mostly disappear before shading, so option (a) would fail by construction and wrongly push the project to option (b). Decision 4 rule 3 says edits may be sharper than the smoothing, but not how. The order (edits after smoothing, or edits with their own smaller smoothing) must be decided and written down, and the test edit sized in ground metres: one feature narrower than the blur, one about equal, one wider. Unedited measured terrain at Badr at zooms 13–15 is a second, honest comparison. *Decided: edits before the smoothing, sized around the threshold (see Key Decisions).*
- **Shading changes with latitude.** The spike uses the projected pixel size, which understates slopes by about 2% at 12° N and 15% at 32° N (about 3% across the spike's area). Keep it as the spike did, or correct it per pixel row and calibrate to match build 11 at 23° N. A correction changes the approved look, so it needs the owner's sign-off. *Decided: corrected per row (see Key Decisions).*
- **Option (c) needs its own lossless encoding.** MapLibre's colour-relief reads a `raster-dem` source, so the darkness value is stored as fake elevation (Terrarium or Mapbox RGB), losslessly. That weakens the size advantage ADR 0009 expected. Sea is stored as darkness 0, not a special value. The style function feeds two encoders: painted WebP, or DEM-encoded lossless tiles. A short probe on the Oppo A55 with a small hand-made `raster-dem` file comes before the tiler is fixed.

### Order of work

- **The land mask belongs in the first step.** Decision 6 says relief, valleys and coast share one land mask from the Water Body Mask. Building relief first with the spike's "elevation above 0.5 m" rule would render the Dead Sea and the Danakil depression as holes and force a rebuild of every relief tile later. Download the mask in step 1; only item 10's comparison with Natural Earth waits.
- **Small windows first, the full extent once.** Option (c)'s encoding, item 5 (rendering each zoom or averaging down) and the style's pixel settings each change how the full build renders. Settle them on small windows: the spike's area (lat 21–25, lon 38–40) for the side-by-side with build 11, the test edit, and one Yemen mountain block for the worst-case tile size. Then run about 10% of the extent to measure time, memory and disk, and only then the full extent once for items 1, 6 and 11.
- **One painted build covers zoom 11, zoom 12 and option (a).** The zoom-11 size is the sum of zooms 0–11 in the zoom-12 build; option (a) is a style change. Only option (c) and item 5 need their own builds. Caveat: zoom 11 averaged from zoom 12 is not quite "rendered at the regional maximum zoom", which is what item 5 compares.
- **Valleys come off the critical path.** Flow routing works on the whole area at once and cannot be split into windows; even at 90 m it is about 750 million cells. A short early probe at 90 m and 180 m tells whether it fits. If it does not, the owner confirms that decision 13 is met with item 7 deferred, as ADR 0009 already allows the first package to ship without valleys.

### Measuring performance

A frame-rate counter alone cannot judge item 11. The overlay shows all four of ADR 0002's measures with the spike's definitions (open time, typical frame rate, lowest frame rate over 30 frames ignoring pauses over 250 ms, and memory), plus the variant name and a short file hash, since the owner reports by screenshot. A scripted camera path at zooms 5–15 over mountains, sand sea and the test edit runs three or more times per device and variant, and the median is recorded. The trial map has no labels yet, so a pass is a lower bound, not proof against ADR 0002; the Oppo A55's lowest rate in the spike was 23 fps against a floor of 20, so the result is recorded as a relief budget with its headroom. Where the frame timing comes from (MapLibre's frame callback, as in the spike, or AndroidX JankStats, which may miss MapLibre's own render thread) is settled by a short check on one device.

### The trial extent is not the peninsula

Latitude 12–32° N, longitude 34–60° E also covers the Ethiopian and Eritrean highlands, the Zagros and Makran, Sinai, Jordan and southern Iraq: some of the most expensive tiles to store. It is about 65 times the spike's sample area, so the measured size will be inflated by land the app may never show, and the zoom-12 file could approach the 2 GiB limit (extrapolation). The build report gives size per zoom and per region (inside and outside a peninsula outline), and the owner decides how land outside the peninsula is shown and how the extent's edge fades. *Decided: relief on all land in the box, fading out in the outer margin (see Key Decisions).*

### Ideal final result

One command, in one published image pulled by digest on the owner's machine and in CI, reads a trial profile, approved or trial-marked edits and a lock that accounts for every cell. It writes a seam-free, gap-free `relief.pmtiles` and a run report with measured time, peak memory, disk use and size per zoom and region. It stays inside a memory budget, resumes by work unit under an input fingerprint, gives identical bytes on the same machine, and removes its temporary work area on success (under M2: terrain blocks, style blocks and the tile folder). Custom code is limited to the style function with its two encoders, applying edits once in a shared edited-terrain stage, validating configuration, and a thin scheduler; GDAL, scipy, a tile-math library, SQLite and Protomaps' tools do the rest. Seams and completeness are checked by tests; the owner judges only the look and the speed. On the device, the trial lives in a release-like build type, variants are style data, and removing the trial means deleting one source set while `:core:map` keeps no trial code.

## Goals vs. Non-Goals

- **In-Scope Goals:**
  - The map build tool for relief and the land mask, then the coast, the option (c) encoding and the valley probe, in a pinned published container.
  - A source lock that accounts for every cell, a download command, and verification against it.
  - A provisional trial profile and trial edit format, marked so later tools refuse them.
  - The first piece of `:core:map` (reading map files, style C, fades, camera limits) and the conventions it needs (a library convention plugin, MapLibre in the version catalogue, licence checks).
  - A release-like trial build type with the trial screen, the import, the measurement overlay and a scripted camera path.
  - A run report for each build, automated seam and completeness tests, and a CI job building the synthetic example.
  - A short implementation and trial specification in `docs/design/` (ADR 0009 already expects one) with every parameter and a pass rule for each trial item.
- **Out-of-Scope Non-Goals:**
  - The real map package: manifest, signing, `tile_schema` 1 and publishing to a channel.
  - Reconstruction or cleanup edits for real places.
  - Labels, markers and the rest of the explore screen (`:feature:explore`).
  - Deciding the final bounds, zoom values or reconstruction method; the trial informs those decisions and the owner takes them.
  - Streaming tiles or any online map.

## Alternatives Weighed

- **Rendering in the spike app (reuse spike build 11 as the viewer):** rejected by the owner (2026-11-14) in favour of a first piece of v1 `:core:map`, because AGENTS.md makes the spike reference-only and its speed numbers would not be v1's.
- **A separate throwaway viewer app:** rejected by the owner (2026-11-14) for the same choice; it would be discarded after the trial.
- **The wider extent to latitude 34° N (Bosra, southern Syria, lower Iraq):** rejected by the owner for the trial (2026-11-14); the peninsula box is the working value.
- **Importing trial files in the release APK, or a dated exception to ADR 0007:** rejected because it breaks or weakens ADR 0007 decision 10 (review; owner, 2026-11-14).
- **A separate trial app module (`:app-maptrial`):** rejected by the owner (2026-11-14): it changes ADR 0003's module layout and duplicates the app's setup; the `benchmark` build type isolates the trial without either.
- **Tile-by-tile rendering with Protomaps' Python writer (first idea for the build):** rejected by the review: 45–60% extra reads, about 73,000 separate GDAL reads, and no real resume, because the writer only produces a file at the end. Replaced by block styling with GDAL's tiler (M2).
- **Block rendering with our own scheduler (M1):** recommended by five reviewers (one resume mechanism, no temporary tile folder, the same path for options b and c); rejected by the owner in favour of M2 (2026-11-14).
- **One big raster (M3: the spike's method, rio-pmtiles, or `gdal_translate` to MBTiles):** rejected: the styled peninsula is about 20 GB per raster and about 40 GB of temporary files, beyond the owner's free memory; rio-pmtiles documents itself for sources of about 1 GB; the MBTiles path drops empty tiles, resamples lossy WebP twice and has no parallelism.
- **A bigger cloud machine (M4):** rejected: it costs money, against free-until-release, and ADR 0009 decision 8 has real builds run on the owner's machine.
- **Style C entirely in GDAL (`gdaldem hillshade` and `color-relief` with a stepped table):** rejected: it changes the approved look (a different gradient method, no band blur), needs whole-extent intermediate files that cannot resume, leaves edits as custom code anyway, and is harder to test than one pure function.
- **Edits after the smoothing, or with their own smaller smoothing:** recommended by the review so narrow edits survive; rejected by the owner in favour of edits before the smoothing (2026-11-14).
- **Trial settings in the public repository (`map-data/trial/`):** nothing in them is private; rejected by the owner in favour of the private trial profile (2026-11-14).
- **Relief only inside a peninsula outline, or fading out beyond it:** rejected by the owner for the trial (2026-11-14); a hard outline would show as a seam across Jordan and Iraq.
- **Keeping the spike's uncorrected shading:** rejected by the owner (2026-11-14) in favour of the per-row correction.
- **Estimating the size from samples instead of a full build:** rejected: items 1 and 6 require a measured full build.
- **Building z11, z12 and option (a) as separate full builds:** rejected: one painted build covers all three.

## Key Decisions & Staging for Next Steps

**Settled:**

- The trial is viewed in a first piece of v1 `:core:map` (owner, 2026-11-14).
- The trial extent is latitude 12–32° N, longitude 34–60° E, a working value (owner, 2026-11-14).
- Elevation and the Water Body Mask come from the AWS bucket without an account (checked 2026-11-14).
- Style C stays one pure function; PMTiles writing and deduplication are left to Protomaps' tools (checked 2026-11-14).
- **The trial runs in a `benchmark` build type** (owner, 2026-11-14): `initWith(release)`, shrunk with R8, not debuggable, debug-signed, application ID `app.seerah.benchmark`. The trial screen, import and measurement overlay live only in its source set, so `release` keeps ADR 0007 decision 10. ADR 0004 gets a short note bringing the planned benchmark build type forward; CI uploads its APK as a second artifact. Removing the trial deletes that source set; the build type stays for `:benchmarks`.
- **Edits are applied before style C's smoothing** (owner, 2026-11-14), as ADR 0009 decision 2 implies: one rendering path, and edits blend into their surroundings. Consequence: shapes narrower than about 500 m (about twice the blur's spread of 230–270 m at zoom 12) mostly disappear at every zoom, option (b)'s detail file included, because rule 3 keeps detail relief no sharper than the regional smoothing; rule 3's exception for edits goes unused. The test edits are sized to show that threshold: banks about 300 m, 600 m and 1.2 km wide and 10–30 m high, a carved bed about 600 m wide and 5–10 m deep, and a flattened block about 1.5 km across over low hills, all on neutral Najd ground, never shipped. If the trial shows real reconstructions need narrower shapes, this decision is revisited.
- **Trial settings and the test edits are private** (owner, 2026-11-14), in `seerah-content/map/trial/`: the trial `coverage.yaml`, the test edits and the trial `sources.lock`. The trial build reads the private checkout at one commit, as decision 8 describes for real builds. Every trial file says `purpose: trial`, the output carries `trial: true` in its PMTiles metadata, and the later packaging step refuses both, with a test. The public `map-data/example/` keeps its own small synthetic edit for CI tests only.
- **Relief covers all land in the trial box** (owner, 2026-11-14), including land beyond the peninsula, so no outline cuts across land. The run report gives size per zoom inside and outside a peninsula outline, so the cost of the outside land is known before the real bounds are decided. Tiles cover a margin beyond the camera bounds (about half the largest screen at each zoom, as the specification defines), and in the outer part of that margin the relief fades to transparent instead of ending in a straight cut.
- **Shading is corrected for latitude, per row of pixels** (owner, 2026-11-14): slopes are computed with each row's true ground pixel size (the Mercator pixel size times the cosine of that row's latitude), taken from the row's absolute position on the global grid, never per block, so block edges cannot show steps. The exaggeration is calibrated so the result at about 23° N matches build 11 (about 8 × cos 23° ≈ 7.4, to be confirmed by comparing pixels), and the owner re-approves the look against build 11. Calibration call (author's, open to correction): the blur stays in pixels, so its ground spread varies about 13% between 12° N and 32° N; only the slope is corrected.
- **The order of work** (owner, 2026-11-14), each step ending with something the owner can check:
  1. Foundations: the pinned image and the source lock; downloads for the small windows only; one land mask from the Water Body Mask; style C with the decisions above; the `benchmark` build type and the first piece of `:core:map`.
  2. Small windows on the three devices: the spike's area (side by side with build 11), the test edits in Najd, and one Yemen mountain block for the worst-case tile size. The owner judges zoom 11 or 12, option (a), and option (c) after a colour-relief probe on the Oppo A55. This fixes the style and the encoding before anything large is built.
  3. About 10% of the trial box, to measure time, memory and disk, with a stop if the numbers are wrong.
  4. The full box once: items 1, 6, 11, 9 and 10.
  5. Valleys: a probe at 90 m and 180 m runs alongside step 1; if it does not fit in memory, item 7 is deferred, as ADR 0009 allows.
- **The build method is M2:** block styling in our code, `gdal raster tile` for tiling, Protomaps' converter for PMTiles (owner, 2026-11-14).

**For the owner:** all decisions raised by the review were answered on 2026-11-14 (above).

**To verify before the specification is final:**

- MapLibre Native Android: `color-relief` with `raster-dem` (encodings, stepped ramps, cost on the Oppo A55); whether a source's maximum zoom is honoured on `pmtiles://`; whether camera bounds limit the centre or the whole view; which frame source sees MapLibre's render thread; whether its dependencies pass the licence check.
- Data: whether all-sea cells are absent from the bucket and whether a tile list exists; the Water Body Mask's class values.
- Tools: `gdal raster tile --resume` on half-written files; whether GDAL's average resampling weights by transparency; WhiteboxTools' memory use.
- Environment: the filesystem of `/mnt/WorkDrive`, SELinux labels on bind mounts, the space in the repository path; GitHub Container Registry terms for public images.
- All sizes, times and line counts above are estimates until measured.

**Next:** once the owner answers the decisions above, `/egm:egm-draft` turns this brief into the implementation and trial specification in `docs/design/`, which is then reviewed before any code.
