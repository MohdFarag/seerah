# Stage 6: Comprehension test, ADR 0009 (second review)

**Target:** `docs/adr/0009-map-data-pipeline.md` at commit `a280cb8`, lines 1 to 250. FeatureRange is the whole file; there is no DetailedRange.

**Reviewer:** comprehension. It completed, reading the document alone with no standard and no other context.

**Verdict:** no Blockers; every section could be paraphrased. There are 5 Majors and 9 Nits. The gaps are in two places:

- where one undecided option changes what ships;
- where an unstated premise (the content licence) carries the argument.

## What this proposes

The ADR decides how the v1 base map of the Arabian Peninsula is built. The base map is terrain only: land, sea, coast, relief and possibly wadis, with no names, borders or modern features. It is built offline from Copernicus GLO-30 elevation data and its Water Body Mask, then shipped as one downloadable, signed map package. The package holds:

- `base.pmtiles`: vector land, coast and valleys;
- `relief.pmtiles`: a pre-rendered "style C" painted relief raster;
- possibly `relief-detail.pmtiles`;
- `map.json`: camera bounds, zoom limits, credits and the list of reconstructed areas.

**Seerah-period terrain.** Owner-approved "terrain edits" are applied to the elevation before rendering. They raise, lower, flatten, carve or remove land. Reconstructed and measured terrain therefore look identical, and only the credits and `map.json` disclose which is which.

**The trial build** is the gate before the first package. It chooses the regional maximum zoom, and it decides how sharp detail at event sites is achieved:

- (a) enlarge the regional relief;
- (c) draw the bands in the app with `color-relief`;
- (b) if neither works, add a second, high-zoom relief file for listed tiles.

**Where things live.** The private map data (edits, `coverage.yaml`, `sources.lock`) goes in a `map/` folder of the existing private content repository, which is renamed. A Python build tool in the public `tools/map/` runs in a container pinned by digest.

## How the existing system works, per this document

**The app and packages:**

- The app is built on MapLibre Native, reading local PMTiles (ADR 0002), with the style, icons and fonts inside the app.
- Historical geometry travels in a separate signed content package. Both package kinds, their manifests, the signing tool, review states and `min_app_version` are defined in ADR 0007.
- Code is public and content is closed, in a private repository called `seerah-content` (ADR 0006). A content validator checks it.
- New packages go out on a `test` channel and are later listed in `stable` (ADR 0008).
- A content model (ADR 0005) lists terrain as built by the pipeline, not written per year.
- ADR 0003 currently takes the map bounds and zoom from the content package.

**The spike:**

- It settled the look: style C, three soft bands of one ink colour with alpha at most 0.6, faded out between zoom 12.5 and 14.5.
- It measured the sizes.
- It left a Bash script, `relief_style.py`, and a public `map-data/` directory.

**Devices and modules:** there are three test devices (Oppo A55, Galaxy A54, MatePad 11.5), and `core:map` is the app module that draws the map.

## Findings

### 🟡 CO-1. What the package contains if option (c) wins

Lines 51, 64, 67, 136–143, 148, 170–176.

Option (c) draws the bands in the app from "a small elevation-like file", which is never described: not its encoding, not its zoom range, and not whether it is `relief.pmtiles`. Decision 4 says that under (a) or (c) "there is one relief file". But decision 4's first bullet, the pipeline table ("style C for each zoom") and the package listing ("raster relief, style C") all describe a painted raster. Under (c), that file would hold encoded values instead.

The ADR should say:

- what file (c) ships;
- how it is built from the edited elevation;
- how `relief.pmtiles` and `tile_schema` differ under (c).

### 🟡 CO-2. "The content licence" is never named, yet the source rejections depend on it

Lines 25, 54, 75–76, 166.

Several rejections only make sense once you know the map package goes out under restrictive, non-share-alike terms:

- FABDEM's share-alike terms "would extend to the map package";
- option (f) has "a licence that conflicts with ours";
- ODbL and LGPL are rejected.

Line 166 then says the package goes out "under the content licence". A reader is never told that the content licence is "all rights reserved". One sentence in Context would fix this.

### 🟡 CO-3. "Important places", "reconstructed areas" and "where the events happen" overlap, and their relationship is never stated

Lines 7, 50, 57, 139, 152, 159, 173, 180, 232.

The terms appear in different places:

- line 7: detail "where the events happen";
- option (b): "important places", and "reconstructed sites stay sharp";
- line 57: the detail at important places "comes from the reconstruction edits";
- `coverage.yaml` holds "the important places";
- `map.json` holds "reconstructed areas".

A reader cannot tell whether important places are exactly the reconstructed areas, a subset of them, or an independent list. They also cannot tell whether approving a new reconstruction adds an important place.

### 🟡 CO-4. `map-data/` is never introduced, and "the earlier choice" points to history the reader lacks

Lines 101, 103, 163, 176.

- **"The earlier choice":** line 103 says the decision "replaces the earlier choice of the public `map-data/`". The reader can't tell whether that was an earlier draft, another ADR or the spike.
- **`map-data/` itself:** it is never introduced, yet it reappears as the home of `example/` and `README.md`.

**Fix:** one sentence saying what `map-data/` is, what it holds now, and what it keeps after this ADR.

### 🟡 CO-5. "Either repository" and "both repository commits" are ambiguous

Lines 164–165.

These come right after a comparison of one private repository against two (`seerah-map` and `seerah-content`), so "both" may be read as those two. The intended pair is presumably the public code repository and the private data repository. The ADR should say so, and say what the manifest records in CI, where only the public example is used.

### 🟢 Nits

- **CO-6.** "Both are downloaded" (the ADR 0003 amendment) contradicts the open decision on bundling the map package in the APK (lines 18, 209). Reword the premise; the conclusion still holds.
- **CO-7.** "The modern features above all come after it" (line 43) reads as "most of all". It should say "the modern features listed above".
- **CO-8.** "(decision 4)" in the option (b) row is a forward reference. Line 124's "ADR 0007 decision 4" is a different decision, so say "decision 4 below".
- **CO-9.** "The trial" is used from line 57 but only defined at line 181. Add a forward pointer at first use.
- **CO-10.** The devices are named late (line 191). "The largest supported screen" (line 161) is not identified.
- **CO-11.** Decision 5 says the coast uses "the same edited elevation", but it comes from the Water Body Mask, and possibly from Natural Earth. Say which mask is meant, and note that Natural Earth is an exception.
- **CO-12.** The Seerah's time span is never given (lines 43, 126), and "AH" is not explained.
- **CO-13.** Three locations or inputs are unclear:
  - where "credits" and "the list of reconstructed areas" come from (line 152);
  - where inputs are "archived privately" (line 128);
  - whether the CI example uses real GLO-30 inputs (line 163).
- **CO-14.** Under option (b), the fade relies on an unstated mechanism (lines 140, 142). The app fades the regional layer by zoom but not the detail layer, which bakes a feathered edge into its own tiles.
