# 0009. Map data pipeline for v1

**Status:** Proposed on 2026-11-13, then revised after an [EGM design review](../design/egm-review-0009/intermediate_zoom_out_report.md) and the owner's decisions of 2026-11-13 and 2026-11-14 (marked "owner" below). Awaiting the owner's acceptance.

## Context

v1's map covers the whole Arabian Peninsula. Panning and zooming stay inside it, and there is no world view. The PRD asks for the most detail where the events happen: Makkah, Taif, Madinah, Quba, Badr and the routes between them (PRD v0.2 §2, the map row, and §5; [requirements](../requirements.md)). This ADR decides how the **base map** is built: the terrain under the historical layers, shipped as the **map package** of [ADR 0007](0007-content-formats-compatibility-and-signing.md).

Already decided:

- The base map is local PMTiles read by MapLibre Native ([ADR 0002](0002-proposed-stack.md)). The map style, icons and fonts ship inside the app. Historical geometry (places, routes, areas) travels in the content package (ADR 0007).
- The content model lists terrain as land, sea, relief, valleys and lava fields, built by this pipeline and not written per year ([specification](../design/exploration-and-story.md)). The PRD's technical study suggests the same kind of base: land, seas and natural features, with no names, borders or modern roads, and relief as 2D shading ("تركيب الخريطة المقترح" in the [PRD snapshot](../prd/PRD-v0.2.ar.md)).
- The zoom limit, the exact bounds and the detail levels are decided during implementation, without inventing historical landmarks or routes (PRD §5 and §13; [open decisions](../open-decisions.md)). This ADR defines how they are recorded and built; it does not set them.
- The spike settled the look: painted, generalised relief (style C, shading in three soft bands), faded out between zoom 12.5 and 14.5 so close views are plain paper ([spike summary](../spike/summary.md)). Style C is a semi-transparent overlay: one ink colour, with the shading in its alpha (at most 0.6), drawn over the land layer.
- **Offline first (owner, 2026-11-14).** v1 downloads the map; it does not stream tiles. Streaming may be added later as an option. Whether the map package ships inside the APK or is downloaded once during setup stays [open](../open-decisions.md).
- **One map package (owner, 2026-11-14).** The whole map is one download, not optional resolution tiers.

What the spike found ([findings](../spike/findings.md)):

- Relief drives the size. Style C from 30 m data (Copernicus GLO-30) for lat 21–25, lon 38–40 (about 91,000 km²) at zooms 6–12 measured about 21 MB. The peninsula is about 3.2 million km², 35 times that area, so zoom 12 everywhere would be at most about 700 MB, and each zoom level fewer cuts it to about a quarter. This is an upper estimate from a rugged sample: style C is fully transparent over sea and flat ground, and identical tiles are stored once in PMTiles.
- 30 m data has no real detail past about zoom 13.
- The spike expected Natural Earth's 1:10m coast to look angular when zoomed in; this was not checked on a device.
- tippecanoe dropped large polygons when the three Natural Earth scales shared one input; one tileset per scale, joined afterwards, fixed it.

**Modern terrain is not 7th-century terrain.** Copernicus DEM is a *surface* model: it includes buildings, so the towers around the Haram in Makkah and central Madinah are relief in the data. It also contains things that did not exist then, such as Harrat Rahat's lava flow of 654 AH (1256 CE) south-east of Madinah, artificial islands and reclaimed land, and cut and levelled mountains in Makkah. The same holds for any modern source.

## Options compared

### What the base map is for

| Option | For | Against |
| --- | --- | --- |
| **Measured terrain as context; historical reconstruction as content above it** | Measured relief shows mountains, passes, valleys and lava fields, which explain movement at regional scale; reconstructions of scenes (a battlefield's banks, dunes and wells) are sourced, reviewed and labelled like any other historical claim; the two stay distinguishable | Two kinds of terrain on screen, which the design must make clear |
| Edit the measured terrain into a historical one | One layer | Interpretation becomes indistinguishable from measurement; a 30 m elevation model cannot show what matters at a site (sand against firm ground, a dune of tens of metres, wells); needs a terrain-editing pipeline |
| Measured terrain only | Simplest | Leaves out the scenes where terrain explains the event, such as Badr ([8:42](https://quran.com/8:42)) |

**Choice (owner, 2026-11-14):** the base map is measured terrain, used as context. Historical terrain reconstructions are content: they are drawn above the base map and never edit it (decision 2).

### Relief

| Option | For | Against |
| --- | --- | --- |
| **Pre-rendered painted raster** (style C) | The approved look; computed offline; drawing it costs the device no more than any image tile | Size grows four times per zoom level; the look is baked into the map package |
| Relief drawn by MapLibre from elevation tiles (`raster-dem` with the `hillshade` and `color-relief` layers added in MapLibre Native 13.0.0) | The look would live in the app's style | Per the style specification, none of its shading methods produces style C's bands; computed on the device, which needs measuring on the Oppo A55; not tried in the spike |
| Vector elevation bands | Small; sharp at every zoom | A stepped contour-map look, not painted |

**Choice:** the pre-rendered painted raster. Engine relief is the alternative if the package grows too large or the look must change with the app.

### Detail where the events happen

| Option | For | Against |
| --- | --- | --- |
| **One relief file, the same detail everywhere**, at the zoom the trial build picks | One source; no seams, holes or blending; the map package does not change as later releases cover more of the Seerah | Rugged areas far from events get the same detail as event areas |
| Detail areas at higher zoom (a regional file with holes plus a detail file) | Detail only where needed | Shown by the review to leave areas blank (one detail file cannot hold several maximum zooms), to need a seam blend that breaks during the style's fade, and to make the map package change with every content release |
| Optional resolution tiers (a base download plus an optional detail download) | Smaller first download | Rejected by the owner: the map is one package |
| Stream detail online | No large download | Rejected for v1 (offline first); possible later |

**Choice (owner, 2026-11-14):** one relief file, uniform, at zoom 11 or 12, chosen after the trial build shows both with their measured sizes. The relief has no real detail past about zoom 13 and fades out from 12.5, so higher zooms would add little. The PRD's "most detail where the events happen" is met by the historical layers, which exist only there, and by the reconstructions.

### Coastline and land

| Option | For | Against |
| --- | --- | --- |
| **Copernicus Water Body Mask** (30 m, shipped with the DEM tiles), ocean class only | The coast matches the relief closely, because both come from the same data; same licence as the DEM | Pixel edges need smoothing; shows modern reclamation and artificial islands |
| Natural Earth (public domain) | Hand-generalised; good at the widest zooms | Expected to look angular from regional zoom on (not checked on a device) |
| OpenStreetMap coastline | Detailed | ODbL: a PMTiles file we distribute is likely a database under the OSMF guidelines, which would require releasing that layer under the ODbL; shows every modern port |
| GSHHG | A global coastline | Coarser than the mask; LGPL, written for software rather than data |

**Choice:** the Water Body Mask's ocean class, generalised for each zoom. Natural Earth is used at the widest zooms only if the trial shows it looks better there. The mask's lake and river classes are not drawn; inland water appears only where content adds it with a source.

### Valleys (wadis)

| Option | For | Against |
| --- | --- | --- |
| **Derived from Copernicus DEM** (flow accumulation, then stream extraction with WhiteboxTools, MIT) | Lines sit in the valleys the relief shows; same licence; stream order sets the zoom at which a valley appears | Tuning; noise in flat sand seas and in cities |
| HydroRIVERS (15 arc-seconds) | Ready-made | About 500 m cells, so lines drift off the valley floors |
| OpenStreetMap waterways | Detailed, sometimes named | ODbL; modern channels; uneven coverage |
| No valleys | Nothing to build | Leaves out a terrain feature the content model lists |

**Choice (owner, 2026-11-13):** derived from the DEM, unnamed, if the trial gives clean lines.

### Lava fields (harrat)

| Option | For | Against |
| --- | --- | --- |
| **Relief only, names from content** | No new source; a named harrah is a content area with its source | No outline to tint |
| A lava-field layer from geological maps | Clean outlines | No open dataset found that covers the peninsula |
| Classified from imagery or roughness | Possible with open data | No published outline to check it against |

**Choice (owner, 2026-11-13):** relief only, with names and approximate areas as content. The trial checks that the lava fields read in style C.

### Modern features in the measured terrain

| Option | For | Against |
| --- | --- | --- |
| **No corrections in v1 unless the trial shows a visibly misleading modern feature near an event** | Nothing built before it is needed; style C's smoothing and the fade may already hide towers | A modern feature may show until it is handled |
| A corrections register with several treatments now | Ready for any case | Built before any case is shown; "smooth relief" would invent heights |

**Choice:** no corrections register in v1. If the trial shows a modern feature that misleads near an event, the owner decides it case by case. The base map never adds or restores terrain, and anything historical belongs to the reconstructions.

### Where the map configuration lives

| Option | For | Against |
| --- | --- | --- |
| **The public repository, in `map-data/`** | Bounds and zoom numbers are not Seerah content; the map tool reads one repository; `AGENTS.md` already puts base-map pipeline inputs there | Visible to everyone |
| The private `seerah-content` repository | Beside the content | The map tool would depend on the content repository and the content build |

**Choice (owner, 2026-11-13):** `map-data/`.

### Tools

| Question | Choice | Alternatives | Why |
| --- | --- | --- | --- |
| Vector tiles | **tippecanoe** (felt, BSD-2) | Planetiler (built around OpenStreetMap); GDAL's MVT driver (less control over simplification) | Used in the spike; a few small layers |
| Raster relief | **GDAL** and our own Python for style C | rio-tiler | GDAL is already required; the spike's code is a reference to rewrite, not copy |
| Valleys | **WhiteboxTools** (MIT) | GRASS (GPL); pysheds (in memory, small areas) | Command-line, permissive licence |
| Orchestration | **One Python tool in `tools/map/`**, with tests | The spike's Bash script; Make | Python is the project's tool language |
| Environment | **A container image pinned by digest** | System packages | The owner's machine and CI build with the same tool versions |

## Decision

1. **The base map is measured terrain, used as context:** land, sea, coastline, relief and, if the trial passes, valleys. It has no names, no borders, and no modern features such as roads, settlements or inland water. Everything historical, including the names of seas, regions, mountains, wadis and lava fields, is content ([ADR 0005](0005-content-model-exploration-and-story.md)). The map credits say the terrain comes from modern elevation data.
2. **Historical terrain reconstructions are content, not part of the base map.** Where sources describe a scene's terrain, such as the banks, dune and wells at Badr, it is reconstructed as content in the content package. Like all content, it is reviewed, ships only when `approved`, and is labelled as an approximate interpretation. It is drawn above the base map, which it never edits. Its sources, method and behaviour are decided when that work starts ([open decisions](../open-decisions.md)).
3. **Sources**, each pinned in `map-data/sources.lock` (address, size, SHA-256). The build refuses a downloaded file that does not match its entry. The pinned inputs are also archived privately, in case an upstream copy changes.

   | Layer | Source | Licence |
   | --- | --- | --- |
   | Relief and valleys | Copernicus GLO-30, resampled for each zoom | Copernicus DEM licence |
   | Land and coastline | Copernicus GLO-30 Water Body Mask (ocean class), smoothed | Copernicus DEM licence |
   | Land and coastline, widest zooms, only if the trial picks it | Natural Earth | Public domain |

4. **One relief file**, `relief.pmtiles`, in style C. It is complete over the whole extent up to one maximum zoom (11 or 12, chosen after the trial; owner, 2026-11-14). The smoothing is defined in metres, not pixels, so every zoom shows the same style. MapLibre enlarges the top zoom as the user zooms in further, and the app's style fades the relief out.
5. **One terrain dataset feeds every layer.** The relief, the valleys and the coast use the same land mask and the same elevation. Any correction the owner approves is applied to that dataset first.

   | Source | Step | Output |
   | --- | --- | --- |
   | GLO-30 elevation | corrections, if any; land mask; style C for each zoom | `relief.pmtiles` |
   | GLO-30 Water Body Mask | smooth; turn into polygons; generalise for each zoom | `base.pmtiles`: land, coast |
   | Natural Earth (if picked) | one tileset per scale, joined | `base.pmtiles`, widest zooms |
   | GLO-30 elevation | flow accumulation over complete catchments; stream order | `base.pmtiles`: valley (optional) |
   | `map-data/coverage.yaml`, `sources.lock`, credits | validate | `map.json` |

6. **Map configuration** is `map-data/coverage.yaml`. It holds the camera bounds, the minimum and maximum zoom, the extent the tiles cover, and the relief's maximum zoom. The values are the owner's open decisions; this file records them once decided. Changes are reviewed in a pull request like any change to the repository. The build fails when the extent does not contain the camera bounds with a margin, calculated from the minimum zoom and the largest supported screen.
7. **One build tool** in `tools/map/` (public, Apache-2.0) reads only this repository: `map-data/` and the pinned sources. It runs in the pinned container: on the owner's machine for real packages, and in CI on a small example configuration in `map-data/example/`, with inputs cached by their hashes ([ADR 0004](0004-build-and-quality-tooling.md)). It refuses a checkout with uncommitted changes. The map package's manifest records the repository commit and the container digest. The manifest is written by the same code as the content package's (ADR 0007 decision 3), and the existing signing tool signs it.
8. **Map package contents** (inside the layout of ADR 0007):

   ```text
   data/base.pmtiles    vector: land, coast, and the optional valley layer (with a stream-order rank)
   data/relief.pmtiles  raster relief, style C
   data/map.json        tile_schema, camera bounds, zoom limits, credits, the hash of sources.lock
   ```

   The layer names, attributes and zoom ranges form the **tile schema**, documented in `map-data/README.md` and numbered by `tile_schema`. The app's style is written against it.
   - Changes are additive where possible.
   - A change an older app would misread raises `tile_schema` and the package's `min_app_version` (ADR 0007).
   - An app that meets a map older than it can read asks the user to update the map.
9. **Credits travel with the data.** `map.json` lists each source's required notice, and the app's notices screen shows the map's credits from it. For Copernicus that is the notice for modified data ("produced using Copernicus WorldDEM-30 © DLR e.V. …") and the licence's liability sentence, checked against the licence files in the tiles' `INFO/` folders before the first release.
10. **The trial build is the gate before the first map package.** It checks:
    1. relief at zooms 11 and 12 side by side, in the Hijaz, with the measured size for the whole peninsula, for the owner to choose;
    2. a full build completes on the owner's machine, with its time and memory recorded;
    3. the valley lines are clean;
    4. the lava fields and the main valleys read in style C;
    5. whether city towers or other modern features show near events;
    6. Natural Earth against the mask-only coast at the widest zoom;
    7. performance on the Oppo A55, Galaxy A54 and MatePad 11.5 against the ADR 0002 targets.
11. **Review on devices.** A new map package goes to the `test` channel first ([ADR 0008](0008-update-hosting-and-index.md)) and is checked on the three devices before it is listed in `stable`.
12. The spike's `tools/spike/build_base.sh` and `relief_style.py` stay a reference only, and are deleted with the spike.

**Amends:**

- [ADR 0003](0003-v1-architecture-and-modules.md): the map bounds and zoom levels come from the map package (`map.json`), not the content package. Both are downloaded, so nothing is hard-coded either way.
- [ADR 0004](0004-build-and-quality-tooling.md): the app's notices screen takes the map's credits from `map.json` instead of a fixed list.
- ADR 0007: a map package's `data/` holds `map.json` besides the PMTiles files.

**Not in scope:**

- how historical reconstructions are made and shown (decision 2);
- the place diagrams;
- sea depth;
- delta updates;
- streaming tiles;
- whether the map package ships inside the APK;
- the style itself (colours, fades, the zoom ladder), which is app work in `core:map`, the app module that draws the map.

## Consequences

- New work, each with tests:
  - the build tool;
  - the schema for `coverage.yaml`;
  - the example configuration;
  - the container image;
  - the app's reading of `map.json`.
- An implementation specification in `docs/design/` covers the mechanics:
  - smoothing the water mask and generalising it;
  - the valley thresholds and the filter for flat areas;
  - the style C recipe in metres;
  - raster encoding (WebP quality, alpha precision, and whether the alpha uses its full range with the darkness set by the app's style);
  - windowed processing;
  - the camera-margin calculation;
  - what the app shows when a map is too old.
- New tools: WhiteboxTools and a container runtime on the owner's machine; the container image in CI.
- The map package may be a few hundred MB rather than the "tens of MB" ADRs 0007 and 0008 assumed. It still changes rarely: a uniform relief does not change as content grows, so users download it again only when the map itself improves.
- **Risks:**
  - Modern terrain shows wherever no correction applies; the app says the terrain comes from modern data and makes no claim that it is historical.
  - Processing GLO-30 for the whole peninsula needs windowed processing: about 3.5 to 4 billion cells, tens of GB if held in memory. Valleys draining across the extent's edge need their whole catchment. The trial confirms a full build completes.
  - Sizes are estimates until the trial build.
  - The relief has no real detail past about zoom 13.

## References

- PRD v0.2 §2, §5, §13 and the technical study ([snapshot](../prd/PRD-v0.2.ar.md)); [spike findings](../spike/findings.md) and [summary](../spike/summary.md); the [EGM review](../design/egm-review-0009/).
- MapLibre: [style specification, sources](https://maplibre.org/maplibre-style-spec/sources/); [MapLibre Native Android changelog](https://github.com/maplibre/maplibre-native/blob/main/platform/android/CHANGELOG.md) (13.0.0: hillshade methods and the color-relief layer).
- [Copernicus DEM on AWS](https://registry.opendata.aws/copernicus-dem/) (tiles with `AUXFILES/…_WBM.tif`), the licences in each tile's `INFO/` folder, and the [Copernicus DEM collection description](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).
- [Natural Earth terms of use](https://www.naturalearthdata.com/about/terms-of-use/).
- OpenStreetMap Foundation: [Produced Work guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Produced_Work_-_Guideline) and [Collective Database guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Collective_Database_Guideline_Guideline).
- [HydroRIVERS](https://www.hydrosheds.org/products/hydrorivers), [GSHHG](https://www.soest.hawaii.edu/pwessel/gshhg/).
- [tippecanoe](https://github.com/felt/tippecanoe), [WhiteboxTools](https://github.com/jblindsay/whitebox-tools), [GDAL](https://gdal.org/), [PMTiles specification](https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
- U.S. Geological Survey: [geologic map of northern Harrat Rahat](https://pubs.usgs.gov/publication/sim3428) (the 1256 CE flow).
