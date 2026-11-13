# 0009. Map data pipeline for v1

**Status:** Proposed on 2026-11-13, then revised after an [EGM design review](../design/egm-review-0009/intermediate_zoom_out_report.md) and the owner's decisions of 2026-11-13 and 2026-11-14 (marked "owner" below). Awaiting the owner's acceptance.

## Context

v1's map covers the whole Arabian Peninsula. Panning and zooming stay inside it, and there is no world view. The PRD asks for the most detail where the events happen: Makkah, Taif, Madinah, Quba, Badr and the routes between them (PRD v0.2 §2, the map row, and §5; [requirements](../requirements.md)). This ADR decides how the **base map** is built: the terrain under the historical layers, shipped as the **map package** of [ADR 0007](0007-content-formats-compatibility-and-signing.md).

Already decided:

- The base map is local PMTiles read by MapLibre Native ([ADR 0002](0002-proposed-stack.md)). The map style, icons and fonts ship inside the app. Historical geometry (places, routes, areas) travels in the content package (ADR 0007).
- The content model lists terrain as land, sea, relief, valleys and lava fields, built by this pipeline and not written per year ([specification](../design/exploration-and-story.md)). The PRD's technical study suggests the same kind of base: land, seas and natural features, with no names, borders or modern roads, and relief as 2D shading ("تركيب الخريطة المقترح" in the [PRD snapshot](../prd/PRD-v0.2.ar.md)).
- The zoom limit, the exact bounds and the detail levels are decided during implementation, without inventing historical landmarks or routes (PRD §5 and §13; [open decisions](../open-decisions.md)). This ADR defines how they are recorded and built; it does not set them.
- The spike settled the look: painted, generalised relief (style C, shading in three soft bands), faded out between zoom 12.5 and 14.5 so close views are plain paper ([spike summary](../spike/summary.md)). Style C is a semi-transparent overlay: one ink colour, with the shading in its alpha (at most 0.6), drawn over the land layer.

The owner's decisions for this ADR:

- **Offline first (2026-11-14).** v1 downloads the map; it does not stream tiles. Streaming may be added later as an option. Whether the map package ships inside the APK or is downloaded once during setup stays [open](../open-decisions.md).
- **One map package (2026-11-14),** not optional resolution tiers.
- **A historical atlas, not a topographic one (2026-11-14).** Where the sources describe a scene's terrain, as at Badr, the terrain is reconstructed approximately from historical sources, Seerah atlases and modern geography. It is rendered in the same style as the measured terrain, so the two look identical, inside the base map itself.

What the spike found ([findings](../spike/findings.md)):

- Relief drives the size. Style C from 30 m data (Copernicus GLO-30) for lat 21–25, lon 38–40 (about 91,000 km²) at zooms 6–12 measured about 21 MB. The peninsula is about 3.2 million km², 35 times that area, so zoom 12 everywhere would be at most about 700 MB, and each zoom level fewer cuts it to about a quarter. This is an upper estimate from a rugged sample: style C is fully transparent over sea and flat ground, and identical tiles are stored once in PMTiles.
- 30 m data has no real detail past about zoom 13. Free elevation data for the region is no finer: ALOS AW3D30 and NASADEM are also 30 m; FABDEM, a 30 m bare-earth model, is CC BY-NC-SA, whose share-alike terms would extend to the map package; finer data is commercial.
- The spike expected Natural Earth's 1:10m coast to look angular when zoomed in; this was not checked on a device.
- tippecanoe dropped large polygons when the three Natural Earth scales shared one input; one tileset per scale, joined afterwards, fixed it.

**Modern terrain is not 7th-century terrain.** Copernicus DEM is a *surface* model: it includes buildings, so the towers around the Haram in Makkah and central Madinah are relief in the data. It also contains things that did not exist then, such as Harrat Rahat's lava flow of 654 AH (1256 CE) south-east of Madinah, artificial islands and reclaimed land, and cut and levelled mountains in Makkah.

**MapLibre Android draws nothing for a tile missing from a PMTiles file**, per the open issue on `sparse` sources ([maplibre-native#4767](https://github.com/maplibre/maplibre-native/issues/4767)). This has not been tested on our devices.

## Options compared

### How historical terrain is shown

| Option | For | Against |
| --- | --- | --- |
| **Approved terrain edits applied to the elevation before rendering, so measured and reconstructed terrain come out of the same style C pass** | One renderer, one look, no seam; nothing extra in the app; the edits shape the result without needing a physically accurate historical elevation model | Reconstructed terrain cannot be told apart by eye; the map build reads private edits |
| A separate reconstruction layer above the base map (vector shapes or painted images) | Interpretation stays visually distinct | Rejected by the owner: a different look, and not seamless |
| Measured terrain only | Simplest | Leaves out the scenes where terrain explains the event, such as Badr ([8:42](https://quran.com/8:42)) |

**Choice (owner, 2026-11-14):** terrain edits baked into the relief. Relief-scale terrain does not change within the Seerah's span, so one historical relief serves every date the app shows. The modern features above all come after it. What changes with time inside the period is far smaller than the relief can show: wells blocked, a basin built, the Trench, buildings. Those stay content features with time states, drawn in the map's own style.

### Detail where the events happen

| Option | For | Against |
| --- | --- | --- |
| a. One uniform relief, enlarged past its top zoom, with the fade moved later | No extra data | The bands soften as they are enlarged; may be enough to about zoom 13–14 |
| b. **Higher-zoom relief at important places**, each spot drawn by one file only (decision 4) | Reconstructed sites stay sharp to zoom 15–16; elsewhere close views fade to paper as approved | Two relief sources; a list of important places to maintain; untested missing-tile behaviour |
| c. Bands drawn by the app from a small elevation-like file (`color-relief`) | Band edges stay crisp when enlarged, because colour is applied after enlarging | Unverified on MapLibre Native Android; performance on the Oppo A55 unknown |
| d. Higher-zoom relief everywhere | Detail everywhere | Gigabytes (zoom 13 is about 2.8 GB, zoom 14 about 11 GB), with no real detail beyond 30 m outside reconstructed sites |
| e. Another style (contour polygons, standard hillshade) | Crisp at any zoom | Breaks the approved look |
| f. Finer elevation data | Real detail | Paid, or a licence that conflicts with ours |
| Optional resolution tiers, or streaming | Smaller first download | Rejected by the owner for v1 |

**Choice (owner, 2026-11-14):** the trial build tries (a) and (c) first, because they cost little. If neither gives clear terrain at the scale of a site like Badr, the map uses (b). Either way it stays one package, one style and one renderer. The detail at important places comes from the reconstruction edits, which is real content at those zooms, where the 30 m data alone has none.

### Relief rendering

| Option | For | Against |
| --- | --- | --- |
| **Pre-rendered painted raster** (style C) | The approved look; computed offline; drawing it costs the device no more than any image tile | Size grows four times per zoom level; the look is baked into the map package |
| Relief drawn by MapLibre from elevation tiles (`hillshade`, `color-relief`, added in MapLibre Native 13.0.0) | The look would live in the app's style | Per the style specification, its shading methods do not produce style C's bands (option c above is the exception being tried) |
| Vector elevation bands | Small; sharp at every zoom | A stepped contour-map look |

**Choice:** the pre-rendered painted raster, unless the trial shows option (c) gives the same look.

### Coastline and land

| Option | For | Against |
| --- | --- | --- |
| **Copernicus Water Body Mask** (30 m, shipped with the DEM tiles), ocean class only | The coast matches the relief closely; same licence as the DEM | Pixel edges need smoothing; shows modern reclamation and artificial islands |
| Natural Earth (public domain) | Hand-generalised; good at the widest zooms | Expected to look angular from regional zoom on (not checked on a device) |
| OpenStreetMap coastline | Detailed | ODbL: a PMTiles file we distribute is likely a database under the OSMF guidelines, which would require releasing that layer under the ODbL; shows every modern port |
| GSHHG | A global coastline | Coarser than the mask; LGPL, written for software rather than data |

**Choice:** the Water Body Mask's ocean class, generalised for each zoom; Natural Earth at the widest zooms only if the trial shows it looks better there. The mask's lake and river classes are not drawn; inland water appears only where content adds it with a source.

### Valleys (wadis)

| Option | For | Against |
| --- | --- | --- |
| **Derived from the elevation** (flow accumulation, then stream extraction with WhiteboxTools, MIT) | Lines sit in the valleys the relief shows; stream order sets the zoom at which a valley appears | Tuning; noise in flat sand seas and in cities |
| HydroRIVERS (15 arc-seconds) | Ready-made | About 500 m cells, so lines drift off the valley floors |
| OpenStreetMap waterways | Detailed, sometimes named | ODbL; modern channels; uneven coverage |
| No valleys | Nothing to build | Leaves out a terrain feature the content model lists |

**Choice (owner, 2026-11-13):** derived from the edited elevation, unnamed, if the trial gives clean lines.

### Lava fields (harrat)

**Choice (owner, 2026-11-13):** relief only, with names and approximate areas as content. No open dataset found covers the peninsula's lava fields as outlines, and classifying them from imagery has no published outline to check against. The trial checks that they read in style C.

### Where the map's private data lives

| Option | For | Against |
| --- | --- | --- |
| **One private repository with `map/` and `content/` sections** | Reconstruction edits cite the same sources and places as content, so one commit can change both and one validator checks the links; one approval flow; builds read one checkout | Same access for both; can be split later (`git filter-repo`) if a map-only collaborator joins |
| Two private repositories (`seerah-map`, `seerah-content`) | Separate permissions and histories | Links across repositories can fall out of step; two checkouts to keep in step |
| The public `map-data/` | Open | Publishes the reconstructions and the map configuration |

**Choice (owner, 2026-11-14):** one private repository. `seerah-content` is renamed (for example to `seerah-data`) and holds `map/` and `content/`. This replaces the earlier choice of the public `map-data/` for the map configuration. The public repository keeps what ADR 0006 keeps for content: the build tools, the schemas and a small synthetic example. Where the repositories live is independent of whether the map is bundled with the app or downloaded.

### Tools

| Question | Choice | Alternatives | Why |
| --- | --- | --- | --- |
| Vector tiles | **tippecanoe** (felt, BSD-2) | Planetiler (built around OpenStreetMap); GDAL's MVT driver (less control over simplification) | Used in the spike; a few small layers |
| Raster relief | **GDAL** and our own Python for style C | rio-tiler | GDAL is already required; the spike's code is a reference to rewrite, not copy |
| Valleys | **WhiteboxTools** (MIT) | GRASS (GPL); pysheds (in memory, small areas) | Command-line, permissive licence |
| Orchestration | **One Python tool in `tools/map/`**, with tests | The spike's Bash script; Make | Python is the project's tool language |
| Environment | **A container image pinned by digest** | System packages | The owner's machine and CI build with the same tool versions |

## Decision

1. **The base map is terrain:** land, sea, coastline, relief and, if the trial passes, valleys. It is measured terrain with approved historical terrain edits applied, all rendered the same way. It has no names, no borders, and no modern features such as roads, settlements or inland water. Everything historical that is not terrain shape, including the names of seas, regions, mountains, wadis and lava fields, is content ([ADR 0005](0005-content-model-exploration-and-story.md)).
2. **Terrain edits.**
   - **What an edit is:** a shape with an operation on the elevation, feathered into its surroundings:
     - raise or lower an area to a height (a bank, a dune);
     - flatten or smooth an area (city towers, the 1256 lava flow);
     - carve a channel along a line (a wadi bed);
     - remove land (an artificial island).
   - **Approval:** each edit records its sources and a review state, and only `approved` edits are applied (ADR 0007 decision 4).
   - **Where it lives:** in `map/` in the private repository.
   - **Time:** edits describe the terrain of the Seerah period, the same for every date the app shows. Smaller things that change with time stay content features with time states.
   - **Still open:** which sources and atlases to use, the method, and the file format are decided when the reconstruction work starts ([open decisions](../open-decisions.md)).
3. **Sources**, each pinned in `map/sources.lock` (address, size, SHA-256). The build refuses a downloaded file that does not match its entry. The pinned inputs are also archived privately, in case an upstream copy changes.

   | Layer | Source | Licence |
   | --- | --- | --- |
   | Relief and valleys | Copernicus GLO-30, with approved terrain edits | Copernicus DEM licence |
   | Land and coastline | Copernicus GLO-30 Water Body Mask (ocean class), smoothed, with "remove land" edits | Copernicus DEM licence |
   | Land and coastline, widest zooms, only if the trial picks it | Natural Earth | Public domain |

4. **Relief files.**
   - **The regional relief** (`relief.pmtiles`) covers the whole extent in style C up to one regional maximum zoom (11 or 12, chosen after the trial).
   - **If the trial chooses option (b),** higher-zoom relief is added at important places:
     - **Important places:** a list of whole tiles at the regional maximum zoom.
     - **No double shading:** the regional file leaves exactly those tiles out at its maximum zoom and keeps them at wider zooms. `relief-detail.pmtiles` holds those tiles from that zoom up to one shared detail maximum (15 or 16, chosen after the trial). Each spot is therefore drawn by one file only, and the app's fade stays correct.
     - **Seamless boundary:** both files are rendered from the same edited elevation, with the smoothing defined in metres for each zoom. At the zoom where they meet, both sides come from the same rendering.
     - **Zooming in:** outside the important places the regional relief fades to paper. Inside them the relief continues, and the detail file softens its own outer edge.
   - **If option (a) or (c) is enough,** there is one relief file and no list of important places.
5. **One terrain dataset feeds every layer.** The relief, the valleys and the coast use the same land mask and the same edited elevation.

   | Source | Step | Output |
   | --- | --- | --- |
   | GLO-30 elevation | approved terrain edits; land mask; style C for each zoom | `relief.pmtiles` and, under option (b), `relief-detail.pmtiles` |
   | GLO-30 Water Body Mask | "remove land" edits; smooth; turn into polygons; generalise for each zoom | `base.pmtiles`: land, coast |
   | Natural Earth (if picked) | one tileset per scale, joined | `base.pmtiles`, widest zooms |
   | Edited elevation | flow accumulation over complete catchments; stream order | `base.pmtiles`: valley (optional) |
   | `map/coverage.yaml`, `sources.lock`, credits, the list of reconstructed areas | validate | `map.json` |

6. **Map configuration** is `map/coverage.yaml` in the private repository. It holds:
   - the camera bounds;
   - the minimum and maximum zoom;
   - the extent the tiles cover;
   - the regional maximum zoom;
   - under option (b), the important places and the detail maximum zoom.

   The values are the owner's open decisions; this file records them once decided. The build fails when the extent does not contain the camera bounds with a margin, calculated from the minimum zoom and the largest supported screen.
7. **One build tool** in `tools/map/` (public, Apache-2.0). It reads a checkout of the private repository's `map/` section, as the content validator reads `content/`.
   - **Where it runs:** in the pinned container, on the owner's machine for real packages, and in CI on a small synthetic example in `map-data/example/`, with inputs cached by their hashes ([ADR 0004](0004-build-and-quality-tooling.md)).
   - **Checkouts:** it refuses a checkout of either repository with uncommitted changes.
   - **Manifest:** the map package's manifest records both repository commits and the container digest. It is written by the same code as the content package's (ADR 0007 decision 3), and the existing signing tool signs it.
   - **Licence:** the map package contains the approved reconstructions, so it is distributed under the content licence, alongside the third-party notices.
8. **Map package contents** (inside the layout of ADR 0007):

   ```text
   data/base.pmtiles           vector: land, coast, and the optional valley layer (with a stream-order rank)
   data/relief.pmtiles         raster relief, style C
   data/relief-detail.pmtiles  only under option (b): relief at important places
   data/map.json               tile_schema, camera bounds, zoom limits, credits, reconstructed areas, the hash of sources.lock
   ```

   The layer names, attributes and zoom ranges form the **tile schema**, documented in `map-data/README.md` and numbered by `tile_schema`. The app's style is written against it.
   - Changes are additive where possible.
   - A change an older app would misread raises `tile_schema` and the package's `min_app_version` (ADR 0007).
   - An app that meets a map older than it can read asks the user to update the map.
9. **Credits and disclosure.** `map.json` lists each source's required notice, and the app's notices screen shows the map's credits from it. For Copernicus that is the notice for modified data ("produced using Copernicus WorldDEM-30 © DLR e.V. …") and the licence's liability sentence, checked against the licence files in the tiles' `INFO/` folders before the first release. `map.json` also lists the areas that contain reconstructed terrain, with their sources. No label appears on the map itself. How the app explains reconstruction (settings, map information, credits) is decided later.
10. **The trial build is the gate before the first map package.** It checks:
    1. relief at zooms 11 and 12, with the measured size for the whole peninsula, for the owner to choose the regional maximum zoom;
    2. option (a): the regional relief enlarged to zooms 13–15 at a site, with the fade moved later;
    3. option (c): `color-relief` bands on the three devices, and its cost on the Oppo A55;
    4. if option (b) is needed: that a missing tile shows nothing on Android; the tile boundary at an important place; the detail maximum zoom and its size;
    5. a full build completes on the owner's machine, with its time and memory recorded;
    6. the valley lines are clean;
    7. the lava fields and the main valleys read in style C;
    8. which modern features show near events;
    9. Natural Earth against the mask-only coast at the widest zoom;
    10. performance on the Oppo A55, Galaxy A54 and MatePad 11.5 against the ADR 0002 targets.
11. **Review on devices.** A new map package goes to the `test` channel first ([ADR 0008](0008-update-hosting-and-index.md)) and is checked on the three devices before it is listed in `stable`.
12. The spike's `tools/spike/build_base.sh` and `relief_style.py` stay a reference only, and are deleted with the spike.

**Amends:**

- [ADR 0003](0003-v1-architecture-and-modules.md): the map bounds and zoom levels come from the map package (`map.json`), not the content package. Both are downloaded, so nothing is hard-coded either way.
- [ADR 0004](0004-build-and-quality-tooling.md): the app's notices screen takes the map's credits from `map.json` instead of a fixed list.
- [ADR 0006](0006-open-source-code-and-closed-content.md): the private repository holds the map's private data in `map/` beside `content/`, and is renamed to fit.
- ADR 0007: a map package's `data/` holds `map.json` besides the PMTiles files.

**Not in scope:**

- the reconstruction method, sources and edit format (decision 2);
- the disclosure wording;
- sea depth;
- delta updates;
- streaming tiles;
- whether the map package ships inside the APK;
- the style itself (colours, fades, the zoom ladder), which is app work in `core:map`, the app module that draws the map.

## Consequences

- **Renaming the private repository** is the owner's action on GitHub. The same change updates every reference to `seerah-content`: AGENTS.md, ADR 0006's text, the READMEs, `REUSE.toml` and the validator's help text.
- New work, each with tests:
  - the build tool;
  - the schemas for `coverage.yaml` and for terrain edits;
  - the synthetic example;
  - the container image;
  - the app's reading of `map.json`.
- An implementation specification in `docs/design/` covers the mechanics:
  - smoothing the water mask and generalising it;
  - the valley thresholds and the filter for flat areas;
  - the style C recipe in metres;
  - raster encoding (WebP quality, alpha precision);
  - windowed processing;
  - applying edits and feathering them;
  - the camera-margin calculation;
  - under option (b), the tile split;
  - what the app shows when a map is too old.
- New tools: WhiteboxTools and a container runtime on the owner's machine; the container image in CI.
- The map package may be a few hundred MB rather than the "tens of MB" ADRs 0007 and 0008 assumed. It changes when the map improves, when a new reconstruction is approved, or when the list of important places grows; each time, users download it again.
- **Risks:**
  - Reconstructed terrain looks the same as measured terrain. The app's honesty about it rests on the disclosure decided later and on the list in `map.json`.
  - Modern terrain shows wherever no edit applies; the credits say the terrain comes from modern data.
  - Processing GLO-30 for the whole peninsula needs windowed processing: about 3.5 to 4 billion cells, tens of GB if held in memory. Valleys draining across the extent's edge need their whole catchment. The trial confirms a full build completes.
  - Option (b) relies on Android drawing nothing for a missing tile, which is not yet tested.
  - Sizes are estimates until the trial build.

## References

- PRD v0.2 §2, §5, §13 and the technical study ([snapshot](../prd/PRD-v0.2.ar.md)); [spike findings](../spike/findings.md) and [summary](../spike/summary.md); the [EGM review](../design/egm-review-0009/).
- MapLibre: [style specification, sources](https://maplibre.org/maplibre-style-spec/sources/); [maplibre-native#4767](https://github.com/maplibre/maplibre-native/issues/4767); [MapLibre Native Android changelog](https://github.com/maplibre/maplibre-native/blob/main/platform/android/CHANGELOG.md) (13.0.0: hillshade methods and the color-relief layer).
- [Copernicus DEM on AWS](https://registry.opendata.aws/copernicus-dem/) (tiles with `AUXFILES/…_WBM.tif`), the licences in each tile's `INFO/` folder, and the [Copernicus DEM collection description](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).
- [Natural Earth terms of use](https://www.naturalearthdata.com/about/terms-of-use/).
- OpenStreetMap Foundation: [Produced Work guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Produced_Work_-_Guideline) and [Collective Database guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Collective_Database_Guideline_Guideline).
- [HydroRIVERS](https://www.hydrosheds.org/products/hydrorivers), [GSHHG](https://www.soest.hawaii.edu/pwessel/gshhg/).
- [tippecanoe](https://github.com/felt/tippecanoe), [WhiteboxTools](https://github.com/jblindsay/whitebox-tools), [GDAL](https://gdal.org/), [PMTiles specification](https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
- U.S. Geological Survey: [geologic map of northern Harrat Rahat](https://pubs.usgs.gov/publication/sim3428) (the 1256 CE flow).
