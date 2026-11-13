# 0009. Map data pipeline for v1

**Status:** Proposed on 2026-11-13, and revised the same day after a first review. Needs the owner's approval, and the owner's answers to the questions at the end.

## Context

v1's map covers the whole Arabian Peninsula, with the most detail where the events happen: Makkah, Taif, Madinah, Quba, Badr and the routes between them. Panning and zooming stay inside the peninsula, and there is no world view ([requirements](../requirements.md), PRD v0.2 §3). This ADR decides how the **base map** is built: the terrain under the historical layers, shipped as the **map package** of [ADR 0007](0007-content-formats-compatibility-and-signing.md).

Already decided:

- The base map is local PMTiles read by MapLibre Native ([ADR 0002](0002-proposed-stack.md)). The map style, icons and fonts ship inside the app. Historical geometry (places, routes, areas) travels in the content package ([ADR 0007](0007-content-formats-compatibility-and-signing.md)).
- The content model lists terrain as land, sea, relief, valleys and lava fields, built by this pipeline and not written per year ([specification](../design/exploration-and-story.md)). The PRD's technical study suggests the same kind of base: "land, seas and the natural features needed, with no names, borders or modern roads in the background images", relief as 2D shading ("تركيب الخريطة المقترح" in the [PRD snapshot](../prd/PRD-v0.2.ar.md)). That study was a recommendation for discussion, later tried in the spike, not an owner decision on its own.
- Exact bounds, detail levels and zoom levels are decided during implementation, without inventing historical landmarks or routes (PRD §3; [open decisions](../open-decisions.md)). **This ADR builds the mechanism; it does not set those values.**
- The spike settled the look: painted, generalised relief (style C, shading in three soft bands), fading out between zoom 12.5 and 14.5 so close views are plain paper, and diagrams for the few places that need close detail (Makkah sites, Badr, Uhud) as later design work ([spike summary](../spike/summary.md)). Style C is a **semi-transparent overlay**: shading with an alpha of at most 0.6, drawn over the land layer.

What the spike measured ([findings](../spike/findings.md)):

- Relief drives the size. Style C from GLO-90 for lat 21–25, lon 38–40 (about 91,000 km²) at zooms 6–12 is about 21 MB. The peninsula (about 3.2 million km²) is about 35 times that area, so zoom 12 everywhere would be **roughly 700 MB**, and each zoom level less divides that by about four. This is an estimate from one area, not a measurement.
- Copernicus GLO-30, the best free elevation data for the region, has no real detail past about zoom 13.
- Natural Earth's finest scale (1:10m) looks angular at regional zoom near the coast.
- tippecanoe dropped large polygons when the three Natural Earth scales shared one input; one tileset per scale, joined afterwards, fixed it. The coastline must come from coastline lines, not from the outline of land cut into cells.

Two facts found for this ADR:

- **MapLibre Android shows nothing for a tile missing from a PMTiles file.** It does not fall back to a lower zoom of the same source. The style specification has an experimental `sparse` flag for exactly this; Android support is still only an open issue ([maplibre-native#4767](https://github.com/maplibre/maplibre-native/issues/4767)). A single file with high zooms only in some areas would therefore leave the rest of the map blank when zoomed in.
- **Modern terrain is not 7th-century terrain.** Copernicus DEM is a *surface* model: it includes buildings, so the towers around the Haram in Makkah and central Madinah are relief in the data. It also contains things that did not exist then: Harrat Rahat's lava flow of 654 AH (1256 CE), which stopped a few kilometres south-east of Madinah; artificial islands and reclaimed land on the Gulf and Red Sea coasts; dam lakes; cut and levelled mountains in Makkah. The same holds for any modern source.

## Options compared

### Relief

| Option | For | Against |
| --- | --- | --- |
| **Pre-rendered painted raster** (the spike's style C) | The look the owner approved; any style can be computed offline; drawing it costs the device no more than any image tile | Size grows four times per zoom level; the look is baked into the map package, so changing it means a new map package |
| Relief drawn by MapLibre from elevation tiles (`raster-dem` with the `hillshade` and `color-relief` layers, improved in MapLibre Native 13.0) | The look lives in the app's style, where ADR 0007 put everything that draws the map; one elevation file serves any style | The engine's shading methods (standard, basic, combined, igor, multidirectional) cannot make style C's three soft bands; shading is computed on the device, which needs measuring on the Oppo A55; not tried in the spike |
| Vector elevation bands (generalised contours as polygons) | Small; sharp at every zoom | A stepped contour-map look, not painted; making bands look natural is its own project |

**Choice:** the pre-rendered painted raster. It is the approved look, and size is not a constraint once detail is limited to where events happen (below). Engine relief is the alternative to try if the map package grows too large or the look must change with the app.

### Detail where the events happen

| Option | For | Against |
| --- | --- | --- |
| The same maximum zoom everywhere | Simplest | Roughly 700 MB at zoom 12, almost all of it detail nobody needs |
| One relief file with high zooms only in some areas | One file | Areas without high-zoom tiles go blank when zoomed in (fact above) |
| A complete regional file, with a detail file drawn on top | No holes to cut | Style C is semi-transparent, so inside each detail area the two layers shade the same place twice, unless the regional layer fades out above its maximum zoom everywhere |
| **Two complementary relief files:** a *regional* file for the whole map with the detail areas cut out, and a *detail* file with only the detail areas, at every zoom | Detail only where it is needed; each file is complete at its own zooms, so MapLibre over-zooms it normally; the two never shade the same place twice; works with either look outside detail areas (below) | Two sources in the style; a seam at each area's edge, which needs a computed blend (below) |

**Choice:** two complementary files. Vector layers (land, coast, valleys) are cheap, so they keep one level of detail across the whole map. When `sparse` works on Android, the two relief files can merge into one.

**The seam.** Both files fade over the same band at each area's edge, but a plain crossfade of two semi-transparent layers leaves a lighter ring: MapLibre draws one over the other, so two layers at half their alpha cover less than one layer at full alpha. The build tool knows both files' pixels, so in the band it computes the regional file's alpha from the detail file's, so that the two together give the shade a single layer would. The trial build checks the seam on the devices.

How the map looks *outside* a detail area when zoomed in past the regional zoom (the regional relief over-zoomed and soft, or faded to plain paper as close views already are) is set by the style in the app. The two-file layout supports both, so it is tried on the devices and decided then.

### Where the coastline and land come from

| Option | For | Against |
| --- | --- | --- |
| Natural Earth only (public domain) | Already used; no attribution needed | 1:10m is angular from regional zoom on |
| **Copernicus Water Body Mask** (30 m, shipped with the DEM tiles, including on the AWS mirror) for regional zoom and closer; Natural Earth for the widest views | The coast matches the relief exactly, because both come from the same data; same licence as the DEM, already credited | Pixel edges need smoothing before tiling; it shows modern reclamation and artificial islands (corrections, below) |
| OpenStreetMap coastline | The most precise | ODbL: a PMTiles file we distribute is likely a database, not a "produced work", under the OSMF guideline, which would require releasing that layer under the ODbL inside a closed map package; shows every modern port and island |
| GSHHG | Long-standing global coastline | Coarser than the mask; LGPL licence, awkward for data |

**Choice:** the Copernicus Water Body Mask, with Natural Earth for the widest zooms. Only its *ocean* class becomes sea. Its lake and river classes are not drawn: in the peninsula they are mostly modern reservoirs. Inland water appears only where content adds it with a source.

### Valleys (wadis)

| Option | For | Against |
| --- | --- | --- |
| **Derived from Copernicus DEM** (flow accumulation, then stream extraction with WhiteboxTools, MIT) | Lines sit in the valleys the relief shows; same licence as the DEM; the threshold controls density, and stream order controls the zoom at which a valley appears | Processing to tune and test: noise in flat sand seas and in cities |
| HydroRIVERS (HydroSHEDS, 15 arc-seconds) | Ready-made, with stream order | About 500 m cells, so lines drift off the relief's valley floors at close zoom; its licence sits in the technical documentation and would need reading in full |
| OpenStreetMap waterways | Precise, sometimes named | ODbL as above; modern channels and dams; uneven coverage |
| No valleys in v1 | Nothing to build | Leaves out a terrain feature the content model lists; the painted relief still shows the main valleys |

**Choice (owner, 2026-11-13):** derived from Copernicus DEM, unnamed, **if the trial build gives clean lines**; otherwise v1 ships without the valley layer and the relief carries the valleys. A named wadi that matters to a story (e.g. one a route follows) is a content item with its source; the base map's lines carry no names or historical claims. The style draws them as dry valleys, not as rivers.

### Lava fields (harrat)

| Option | For | Against |
| --- | --- | --- |
| **Relief only, names from content** | The relief already shows the lava fields clearly (spike); no new source; a named harrah is a content *area* with its source | No outline to tint or highlight |
| A lava-field layer from geological maps | Clean outlines | No open dataset found that covers the peninsula; tracing a published map is georeferencing an image, which the content rules exclude |
| Classified from imagery or roughness | Possible with open data | A model's output, which the content rules do not accept as a source |

**Choice (owner, 2026-11-13):** relief only in v1, with names and approximate areas as content.

### Tools

| Question | Choice | Alternatives | Why |
| --- | --- | --- | --- |
| Vector tiles | **tippecanoe** (felt, BSD-2) | Planetiler (Java, built around OpenStreetMap); GDAL's MVT driver (less control over simplification) | Proven in the spike; the inputs are a few small layers, not OpenStreetMap |
| Raster relief | **GDAL** and our own Python for style C | rio-tiler and similar | GDAL is already required; the spike's style code is the reference to rewrite (not copy) |
| Valleys | **WhiteboxTools** (MIT) | GRASS (GPL, heavy to install); pysheds (in-memory, small areas) | Command-line, permissive licence, handles large rasters |
| Orchestration | **One Python tool in `tools/map/`**, with tests | The spike's Bash script; Make | Python is the project's tool language (Ruff, tests); one entry point |
| Environment | **A container image pinned by digest** | System packages | The owner's machine and CI build with the same GDAL, tippecanoe and WhiteboxTools versions |

## Decision

1. **The base map is terrain only:** land, sea, coastline, relief and valleys. No names, borders, roads, settlements, inland water or other modern features. Everything historical, including the names of seas, regions, mountains, wadis and lava fields, is content ([ADR 0005](0005-content-model-exploration-and-story.md)).
2. **Sources**, each pinned in `map-data/sources.lock` (address, size, SHA-256). The build refuses a downloaded file that does not match its entry.

   | Layer | Source | Licence |
   | --- | --- | --- |
   | Land and coastline, widest zooms | Natural Earth 1:110m, 1:50m, 1:10m | Public domain |
   | Land and coastline, regional zoom and closer | Copernicus DEM Water Body Mask (ocean class), smoothed | Copernicus DEM licence |
   | Regional relief | Copernicus GLO-90 (smaller to download); GLO-30 resampled instead if the trial shows a visible difference at the seam | Copernicus DEM licence |
   | Detail relief | Copernicus GLO-30 | Copernicus DEM licence |
   | Valleys | Derived from Copernicus DEM; which resolution is used where is settled in the implementation specification after a trial | Copernicus DEM licence |

   A source needed by an approved terrain correction (decision 5) is added to this table and to `sources.lock` when the correction is approved.
3. **Coverage is content, in one file, and ships in the map package.** `map/coverage.yaml` in `seerah-content` holds:
   - the **camera bounds** and the minimum and maximum zoom the app allows;
   - the **extent** the tiles cover, which must contain the camera bounds with a margin;
   - the regional maximum zoom;
   - the **detail areas**: each a polygon, or a buffer around a content place or route, with its own maximum zoom.

   The values are the owner's open decisions; this file is where they are recorded once decided. Because the bounds and the tiles travel in the same package, they cannot disagree on a device. This amends [ADR 0003](0003-v1-architecture-and-modules.md), which has the map bounds and zoom levels come from "the content package": they come from the map package, which is downloaded and versioned like content, so nothing is hard-coded either way.

   Checks: the build fails when the camera bounds are not inside the extent. The content build warns when an item shown on the map in free exploration (`always`) or a documented route lies outside every detail area.

   Content and map packages are versioned apart (ADR 0007). Content newer than the installed map still works: items in an area the installed map does not detail show over the regional relief. A map package that adds coverage is published with, or before, the content that needs it, and the app offers them as one update ([ADR 0008](0008-update-hosting-and-index.md)).
4. **Relief in two complementary files**, as compared above: `relief.pmtiles` covers the extent up to the regional maximum zoom with the detail areas cut out; `relief-detail.pmtiles` covers only the detail areas, from the lowest zoom to each area's maximum. Both fade over the same band at each area's edge, with the regional alpha in the band computed from the detail alpha so the two give one layer's shade.
5. **Terrain corrections, reviewed one by one:** `map/terrain-corrections.yaml` in `seerah-content` lists the modern features that would otherwise show. Each entry gives what it is, why it is modern (with a source), its outline and where the outline comes from, and one treatment:
   - *remove land*: an artificial island becomes sea;
   - *hide relief* inside the outline: plain paper;
   - *smooth relief* inside the outline: heights there are replaced by values interpolated from the outline's edges (e.g. over city towers). These are estimates, not data, and the entry says so.

   **Outlines** come from a published vector dataset with a licence that allows this use, or are derived from the pinned sources by a rule written in the entry (e.g. the difference between the water mask and Natural Earth for an island built after 2000). They are never traced from a map or an image, and never produced by a model.

   No treatment adds terrain: there is no restored coastline or mountain. Reclaimed coast therefore stays as the data shows it, since removing it would mean drawing the old coastline. Like content, only entries marked `review: approved` are applied ([ADR 0007](0007-content-formats-compatibility-and-signing.md) decision 4).

   The first candidates, each for the owner to decide:
   - city towers in the surface model (Makkah, Madinah, Taif), decided after the trial build shows whether they are visible at all; if smoothed, their outlines come from ESA WorldCover's built-up class (CC BY 4.0);
   - the 654 AH lava flow, whose outline could come from the U.S. Geological Survey's [database for the geologic map of northern Harrat Rahat](https://www.usgs.gov/data/database-geologic-map-northern-harrat-rahat-volcanic-field-kingdom-saudi-arabia) (marked CC0), to be checked before use;
   - Gulf artificial islands;
   - cut and levelled mountains in Makkah, if a published outline exists.
6. **One build tool** in `tools/map/` (public, Apache-2.0) reads a `seerah-content` checkout, as the validator does. It downloads and checks the pinned inputs, applies the approved corrections, and writes the map package's files. It runs in the pinned container, on the owner's machine for real packages (which are then signed there, ADR 0007) and in CI on a small synthetic coverage example kept in `content/examples/`, with inputs cached by their hashes ([ADR 0004](0004-build-and-quality-tooling.md)). Like the content build, it refuses a `seerah-content` checkout with uncommitted changes, and the manifest records its commit.
7. **Map package contents** (inside the layout of ADR 0007):

   ```text
   data/base.pmtiles           vector: land, coast, valley (with a stream-order rank)
   data/relief.pmtiles         raster, regional, detail areas cut out
   data/relief-detail.pmtiles  raster, detail areas only
   data/coverage.geojson       the extent and the detail areas, for the app's "no more detail here"
   data/map.json               tile_schema, camera bounds, zoom limits, attributions, the hash of sources.lock
   ```

   The layer names, attributes and zoom ranges form the **tile schema**, documented in `map-data/README.md` and numbered by `tile_schema`. The app's style is written against it. A change that an older style would misread raises `tile_schema` and the package's `min_app_version` (ADR 0007).
8. **Attribution travels with the data.** `map.json` lists each source's required notice, and the app's notices screen shows the map's credits from it, instead of from a list fixed in the app (ADR 0004 lists them today). For Copernicus, modified data (the relief and everything derived) need the notice for each product used: "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved", and the same for WorldDEM-90, plus the licence's liability sentence. The exact wording is checked against the licence files shipped in the tiles' `INFO/` folders before the first release. Natural Earth is credited though it does not require it.
9. **Review on devices.** A new map package goes to the `test` channel first ([ADR 0008](0008-update-hosting-and-index.md)). The owner checks it on the three devices (detail areas, seams, coast, relief, valleys, every applied correction) and it is measured against the targets in ADR 0002 before it is listed in `stable`.
10. The spike's `tools/spike/build_base.sh` and `relief_style.py` stay a reference only and go with the spike.

**Not in scope:** the place diagrams (Makkah sites, Badr, Uhud), sea depth, delta updates, whether the first map package ships inside the APK ([open decisions](../open-decisions.md)), and the style itself (colours, fades, zoom ladder), which is app work in `core:map`.

## Consequences

- New work, each with tests: the build tool, the coverage and corrections schemas in `content/schema/`, the synthetic coverage example, the container image, the coverage checks in the content build, and the app's reading of `coverage.geojson` and `map.json` (camera bounds and zoom limits included).
- An implementation specification in `docs/design/` covers what this ADR leaves out: smoothing the water mask, the switch zoom between Natural Earth and the mask, the valley thresholds and the flat-area filter, the feather width and the seam blend, raster encoding (WebP quality, alpha precision), and a first trial build to measure real sizes, check the seam and the valley lines, and see whether city towers show.
- New tools: WhiteboxTools and a container runtime on the owner's machine; the container image in CI.
- **When the detail areas grow** (later releases cover the whole Seerah), a new map package is built and the whole package is downloaded again. Delta updates are out of scope (ADR 0007). Per-area detail files would allow downloading only what changed if they are added later.
- **Risks:**
  - Modern terrain shows wherever no correction applies, reclaimed coast and modern ports included. Corrections reduce this but never restore the past; the app makes no claim that the relief is historical.
  - Smoothed areas show estimated heights, not measured ones.
  - The 30 m data limits real detail to about zoom 13; close views rely on the planned diagrams.
  - Sizes above are estimates until the first build.
  - If `sparse` never reaches MapLibre Android, the two relief files stay.
- The in-app notices take the map's credits from `map.json`; [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) gains the GLO-90 notice and any correction source when it is adopted.

## Questions for the owner

1. **Valleys:** show unnamed valley lines derived from the elevation data in v1 if the trial gives clean lines, as proposed, or leave valleys out? **Answered (owner, 2026-11-13): show them.**
2. **Lava fields:** relief only, with named areas as content, as proposed? **Answered (owner, 2026-11-13): yes.**
3. **Modern terrain:** do you accept the corrections register (only removing islands, hiding or smoothing relief, never adding terrain; outlines only from licensed datasets or stated rules; each entry approved by you)? City towers are decided after the trial build.

## References

- PRD v0.2 §3 and the technical study's "تركيب الخريطة المقترح" ([snapshot](../prd/PRD-v0.2.ar.md)); [spike findings](../spike/findings.md) and [summary](../spike/summary.md).
- MapLibre: [style specification, sources](https://maplibre.org/maplibre-style-spec/sources/) (`sparse`, experimental); [maplibre-native#4767](https://github.com/maplibre/maplibre-native/issues/4767); [MapLibre Native Android changelog](https://github.com/maplibre/maplibre-native/blob/main/platform/android/CHANGELOG.md) (13.0.0: hillshade methods and the color-relief layer).
- [Copernicus DEM on AWS](https://registry.opendata.aws/copernicus-dem/) (tiles with `AUXFILES/…_WBM.tif`), the licences shipped in each tile's `INFO/` folder, and the [Copernicus DEM collection description](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM) (attribution for modified GLO-30 and GLO-90 data).
- [Natural Earth terms of use](https://www.naturalearthdata.com/about/terms-of-use/).
- OpenStreetMap Foundation: [Produced Work guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Produced_Work_-_Guideline) and [Collective Database guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Collective_Database_Guideline_Guideline).
- [HydroRIVERS](https://www.hydrosheds.org/products/hydrorivers).
- U.S. Geological Survey: [geologic map of the northern Harrat Rahat volcanic field](https://pubs.usgs.gov/publication/sim3428) (SIM 3428, 2019; the 1256 CE flow is its only confirmed historical eruption) and its [database](https://www.usgs.gov/data/database-geologic-map-northern-harrat-rahat-volcanic-field-kingdom-saudi-arabia).
- [tippecanoe](https://github.com/felt/tippecanoe), [WhiteboxTools](https://github.com/jblindsay/whitebox-tools), [PMTiles specification](https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
