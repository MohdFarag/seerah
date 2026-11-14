# 0009. Map data pipeline for v1

**Status:** Proposed on 2026-11-13. Revised after two EGM design reviews ([first](../design/egm-review-0009/intermediate_zoom_out_report.md), [second](../design/egm-review-0009-r2/consolidated_review_report.md)) and the owner's decisions of 2026-11-13 and 2026-11-14 (marked "owner" below, and recorded in the pull request that proposes this ADR). Awaiting the owner's acceptance.

## Context

v1's map covers the whole Arabian Peninsula. Panning and zooming stay inside it, and there is no world view. The PRD asks for the most detail where the events happen: Makkah, Taif, Madinah, Quba, Badr and the routes between them (PRD v0.2 §2, the map row, and §5; [requirements](../requirements.md)). v1's events run from the journey to Taif, about the tenth year of prophethood, to Badr in Ramadan 2 AH (After the Hijra). This ADR decides how the **base map** is built: the terrain under the historical layers, shipped as the **map package** of [ADR 0007](0007-content-formats-compatibility-and-signing.md).

Already decided:

- The base map is local PMTiles read by MapLibre Native ([ADR 0002](0002-proposed-stack.md)). The map style, icons and fonts ship inside the app (ADR 0007 decision 1). Historical geometry (places, routes, areas) travels in the content package.
- Content is all rights reserved and authored in a private repository, `seerah-content`; the public repository holds the code, schemas and tools ([ADR 0006](0006-open-source-code-and-closed-content.md)). The public `map-data/` folder holds the spike's map files today.
- The content model lists terrain as land, sea, relief, valleys and lava fields, built by this pipeline and not written per year ([specification](../design/exploration-and-story.md)). The PRD's technical study suggests the same kind of base: land, seas and natural features, with no names, borders or modern roads, and relief as 2D shading ("تركيب الخريطة المقترح" in the [PRD snapshot](../prd/PRD-v0.2.ar.md)).
- The zoom limit, the exact bounds and the detail levels are decided during implementation, without inventing historical landmarks or routes (PRD §5 and §13; [open decisions](../open-decisions.md)). This ADR defines how they are recorded and built; it does not set them.
- The spike settled the look: painted, generalised relief (style C, shading in three soft bands), faded out between zoom 12.5 and 14.5 so close views are plain paper ([spike summary](../spike/summary.md)). Style C is a semi-transparent overlay: one ink colour, with the shading in its alpha, at most 0.6 ([`relief_style.py`](../../tools/spike/relief_style.py)), drawn over the land layer. The spike rendered it at zoom 12 and averaged the wider zooms.

The owner's decisions for this ADR:

- **Offline first (2026-11-14).** v1 downloads the map; it does not stream tiles. Streaming may be added later as an option. Whether the map package ships inside the APK or is downloaded once during setup stays [open](../open-decisions.md).
- **One map package (2026-11-14),** not optional resolution tiers.
- **A historical atlas, not a topographic one (2026-11-14).** Where the sources describe a scene's terrain, as at Badr, the terrain is reconstructed approximately from historical sources, Seerah atlases and modern geography. It is part of the base map and drawn in the same style as the measured terrain, so the two cannot be told apart. Events are explained through the map itself, so the spike's plan for separate place diagrams (Makkah sites, Badr, Uhud) is dropped.

What the spike found ([findings](../spike/findings.md)):

- Relief drives the size. Style C from 30 m data (Copernicus GLO-30) for lat 21–25, lon 38–40 (about 91,000 km²) at zooms 6–12 measured about 21 MB. Scaled to the peninsula (about 3.2 million km², 35 times that area), zoom 12 everywhere would be about 700 MB, and each zoom level fewer cuts it to about a quarter. This is an estimate, not a bound: flat ground and sea are transparent and stored once, but the sand seas' dunes and the mountains of Yemen may cost more than the sample, and the extent reaches beyond the peninsula.
- 30 m data has no real detail past about zoom 13. Free elevation data for the region is no finer: ALOS AW3D30 and NASADEM are also 30 m; FABDEM, a 30 m bare-earth model, has non-commercial share-alike terms that would extend to the map package; finer data is commercial.
- The spike expected Natural Earth's 1:10m coast to look angular when zoomed in past the relief's detail; this was not checked on a device.
- tippecanoe dropped large polygons when the three Natural Earth scales shared one input; one tileset per scale, joined afterwards, fixed it.

**Modern terrain is not 7th-century terrain.** Copernicus DEM is a *surface* model: it includes buildings, so the towers around the Haram in Makkah and central Madinah are relief in the data. It also contains things that did not exist then, such as Harrat Rahat's lava flow of 654 AH (1256 CE) south-east of Madinah, artificial islands and reclaimed land, and cut and levelled mountains in Makkah.

An open MapLibre issue reports that MapLibre Android draws nothing for a tile missing from a PMTiles file ([maplibre-native#4767](https://github.com/maplibre/maplibre-native/issues/4767)); this is untested on our devices, and decision 5 removes the dependency on it.

## Options compared

### How historical terrain is shown

| Option | For | Against |
| --- | --- | --- |
| **Approved terrain edits applied to the elevation before rendering, so measured and reconstructed terrain come out of the same style C pass** | One renderer, one look, no seam; nothing extra in the app; the edits shape the result without needing a physically accurate historical elevation model | Reconstructed terrain cannot be told apart by eye; the map build reads private edits |
| A separate reconstruction layer above the base map (vector shapes or painted images) | Interpretation stays visually distinct | A different look, and not seamless (owner) |
| Separate place diagrams for close views (the spike's plan) | Free of the 30 m limit | The events are to be explained on the map itself (owner) |
| Measured terrain only | Simplest | Leaves out the scenes where terrain explains the event, such as Badr ([8:42](https://quran.com/8:42)) |

**Choice (owner, 2026-11-14):** terrain edits baked into the relief. Relief-scale terrain does not change within the Seerah's span, so one historical relief serves every date the app shows; every modern feature listed above comes after it. What changes with time inside the period is far smaller than the relief can show: wells blocked, a basin built, the Trench, buildings. Those stay content features with time states, drawn in the map's own style.

### Detail where the events happen

| Option | For | Against |
| --- | --- | --- |
| a. One uniform relief, enlarged past its top zoom, with the fade moved later | No extra relief data; may be enough to about zoom 13–14 | The bands soften as they are enlarged |
| b. Higher-zoom relief where approved reconstructions are, each spot drawn by one file only (decision 4) | Reconstructed sites stay sharp to zoom 15–16; elsewhere close views fade to paper as approved | Two relief sources |
| c. Bands drawn by the app from a small value raster (`color-relief`) | Band edges stay crisp when enlarged, because colour is applied after enlarging; possibly much smaller than painted tiles | Unverified on MapLibre Native Android; performance on the Oppo A55 unknown; crisp edges are a different look from style C's soft bands |
| d. Higher-zoom relief everywhere | Detail everywhere | About 2.8 GB at zoom 13 and 11 GB at zoom 14 (same estimate), with no real detail beyond 30 m outside reconstructed sites |
| e. Another style (contour polygons, standard hillshade) | Crisp at any zoom | Breaks the approved look |
| f. Finer elevation data | Real detail | Paid, or a licence that conflicts with the content licence |
| g. Optional resolution tiers, or streaming | Smaller first download | Breaks offline first and one map package (owner) |

**Choice (owner, 2026-11-14):** the trial build tries (a) and (c) first, because they need no extra relief data, and judges them on a test reconstruction (decision 13). If neither gives clear terrain at site scale, the map uses (b). Either way it stays one package, one style and one renderer. At reconstructed sites the edits supply detail the 30 m data lacks.

### Relief rendering

| Option | For | Against |
| --- | --- | --- |
| **Pre-rendered painted raster** (style C) | The approved look; computed offline; drawing it costs the device no more than any image tile | Size grows four times per zoom level; the look is baked into the map package |
| Relief drawn by MapLibre from elevation tiles (`hillshade`, added with `color-relief` in MapLibre Native 13.0.0) | The look would live in the app's style | Its hillshade methods do not produce style C's bands; `color-relief` might (option c) |
| Vector elevation bands | Small; sharp at every zoom | A stepped contour-map look |

**Choice:** the pre-rendered painted raster, unless the trial shows option (c) gives an acceptable look.

### Coastline and land

| Option | For | Against |
| --- | --- | --- |
| **Copernicus Water Body Mask** (30 m, shipped with the DEM tiles), ocean class only | The coast matches the relief closely; same licence as the DEM | Pixel edges need smoothing; shows modern reclamation and artificial islands |
| Natural Earth (public domain) | Hand-generalised; good at the widest zooms | Expected to look angular when zoomed in past the relief's detail (not checked on a device) |
| OpenStreetMap coastline | Detailed | ODbL: a PMTiles file we distribute is likely a database under the OSMF guidelines, which would require releasing that layer under the ODbL; shows every modern port |
| GSHHG | A global coastline | Coarser than the mask; LGPL, written for software rather than data |

**Choice:** the Water Body Mask's ocean class, generalised for each zoom; Natural Earth at the widest zooms only if the trial shows it looks better there. The mask's lake and river classes are not drawn; inland water appears only where content adds it with a source.

### Valleys (wadis)

| Option | For | Against |
| --- | --- | --- |
| **Derived from the elevation** (flow accumulation, then stream extraction) | Lines sit in the valleys the relief shows; stream order sets the zoom at which a valley appears | Heavy over the peninsula; noise in flat sand seas and in cities |
| HydroRIVERS (15 arc-seconds) | Ready-made | About 500 m cells, so lines drift off the valley floors |
| OpenStreetMap waterways | Detailed, sometimes named | ODbL; modern channels; uneven coverage |
| No valleys | Nothing to build | Leaves out a terrain feature the content model lists |

**Choice (owner, 2026-11-13):** derived from the edited elevation, unnamed, if the trial gives clean lines; otherwise the first package ships without valleys. How the flow is routed (grid size, tool, the rule at the extent's edge, masking the sand seas) is settled in the trial and the implementation specification.

### Lava fields (harrat)

**Choice (owner, 2026-11-13):** relief only, with names and approximate areas as content. No open dataset found covers the peninsula's lava fields as outlines, and classifying them from imagery has no published outline to check against.

### Where the map's private data lives

| Option | For | Against |
| --- | --- | --- |
| **One private repository with `map/` and `content/` sections** | Reconstruction edits cite the same sources and places as content, so one commit can change both and one build reads both; one approval flow; can be split later (`git filter-repo`) | Same access for both |
| Two private repositories (`seerah-map`, `seerah-content`) | Separate permissions and histories | Links across repositories can fall out of step; two checkouts to keep in step |
| The public `map-data/` | Open | Publishes the reconstructions and the map configuration |

**Choice (owner, 2026-11-14):** one private repository, `seerah-content`, holding `map/` and `content/`. It keeps its name: ADR 0006 already counts historical map data as content. The public repository keeps what ADR 0006 keeps for content: the build tools, the schemas, and in `map-data/` the documented tile schema and a small synthetic example.

### Tools

| Question | Choice | Alternatives | Why |
| --- | --- | --- | --- |
| Vector tiles | **tippecanoe** (felt, BSD-2) | Planetiler (built around OpenStreetMap); GDAL's MVT driver (less control over simplification) | Used in the spike; a few small layers |
| Raster relief | **GDAL** and our own Python for style C | rio-tiler | GDAL is already required; the spike's code is a reference to rewrite, not copy |
| Valleys | WhiteboxTools (MIT) as the first candidate | GRASS (GPL); pysheds | Command-line, permissive licence; whether it handles the peninsula's size is checked in the trial |
| Orchestration | **One Python tool in `tools/map/`**, with tests | The spike's Bash script; Make | Python is the project's tool language |
| Environment | **A container image pinned by digest** | System packages | The owner's machine and CI build with the same tool versions |

## Decision

1. **The base map is terrain:** land, sea, coastline, relief and, if the trial passes, valleys. It is measured terrain with approved terrain edits applied, all rendered the same way. No modern features are drawn as their own layer (roads, settlements, inland water). Modern terrain shape remains wherever no edit applies, and the credits say the terrain comes from modern elevation data. Everything historical that is not terrain shape, including the names of seas, regions, mountains, wadis and lava fields, is content ([ADR 0005](0005-content-model-exploration-and-story.md)).
2. **Terrain edits, in two classes (owner, 2026-11-14).** An edit is a shape with an operation on the elevation, feathered into its surroundings. Examples: raise or lower an area, flatten or smooth it, carve a channel, remove land.

   | Class | What it does | Evidence | When |
   | --- | --- | --- | --- |
   | **Cleanup** | Smooths away or removes what is known to be modern: city towers, artificial islands, reclaimed land. Never adds shape | Modern imagery or dated data | Format decided with this pipeline; may ship in the first package |
   | **Reconstruction** | Adds or restores historical shape: a bank, a dune, a wadi bed | Historical sources, Seerah atlases, modern geography | Method, sources and format decided when that work starts ([open decisions](../open-decisions.md)) |

   - Edits live in `map/` in the private repository and reuse the content schema's source and review types.
   - The build applies only `approved` edits and lists the others in its log (ADR 0007 decision 4).
   - Edits describe the terrain of the Seerah period, the same for every date the app shows.
   - The 1256 lava flow is left unedited unless the trial shows it visible in style C; if it is, it is cleanup.
3. **Sources**, each pinned in `map/sources.lock` (address, size, SHA-256). The build refuses a downloaded file that does not match its entry.

   | Layer | Source | Licence |
   | --- | --- | --- |
   | Relief and valleys | Copernicus GLO-30, with approved edits | Copernicus DEM licence |
   | Land and coastline | Copernicus GLO-30 Water Body Mask (ocean class), smoothed, with "remove land" edits | Copernicus DEM licence |
   | Land and coastline, widest zooms, only if the trial picks it | Natural Earth | Public domain |

4. **Relief.**
   - **The regional relief** (`relief`) covers the extent in style C up to one regional maximum zoom (11 or 12, chosen after the trial). It is rendered at that zoom and averaged down for the wider zooms, as the spike did; rendering each zoom separately is a trial comparison.
   - **Whatever the trial picks, the relief is one raster source, `relief`, in `tile_schema` 1.** Its encoding (painted, or one continuous darkness value coloured by the app under option c) is fixed before the first package.
   - **If option (b) is needed,** a second file, `relief-detail`, holds relief from the regional maximum zoom up to one shared detail maximum zoom (15 or 16, chosen after the trial), under four rules:
     1. Each spot at each zoom is shaded by exactly one relief file.
     2. At the zoom where the two files meet, both come from the same rendering.
     3. Detail relief is never sharper than the regional maximum zoom's smoothing, except where edits add shape. This keeps modern buildings and 30 m noise out of close views.
     4. The detail tiles are exactly the tiles touched by approved reconstruction edits, computed by the build (owner, 2026-11-14).

     The tile split, edge softening and fade schedule go in the implementation specification.
5. **No missing tiles.** Every file covers its whole extent at each of its zooms; empty areas are written as explicit transparent tiles, which PMTiles stores once. No design depends on how the engine treats a missing tile.
6. **One terrain dataset feeds every layer:** the relief, the valleys and the coast use the same land mask (the Water Body Mask's ocean class after "remove land" edits) and the same edited elevation. Natural Earth, if picked, is the one exception, at the widest zooms only.

   | Source | Step | Output |
   | --- | --- | --- |
   | GLO-30 elevation | approved edits; land mask; style C | `relief.pmtiles` and, under option (b), `relief-detail.pmtiles` |
   | GLO-30 Water Body Mask | "remove land" edits; smooth; turn into polygons; generalise for each zoom | `base.pmtiles`: land, coast |
   | Natural Earth (if picked) | one tileset per scale, joined | `base.pmtiles`, widest zooms |
   | Edited elevation | flow routing; stream order | `base.pmtiles`: valley (optional) |
   | `map/coverage.yaml`; source citations from `content/`; approved reconstruction edits | validate; generate the list of reconstructed areas | `map.json` |

7. **Map configuration** is `map/coverage.yaml` in the private repository. It holds:
   - the camera bounds;
   - the minimum and maximum zoom;
   - the extent the tiles cover;
   - the regional maximum zoom;
   - under option (b), the detail maximum zoom.

   The values are the owner's open decisions; this file records them once decided. The build fails when the extent does not contain the camera bounds with the margin the specification defines for each zoom.
8. **One build tool** in `tools/map/` (public, Apache-2.0). It reads the private repository (`map/` and `content/`) at one commit, as the content validator reads `content/`.
   - **Where it runs:** in the pinned container, on the owner's machine for real packages. In CI it runs on the synthetic example in `map-data/example/`, which includes a test edit at a neutral location and is never shipped.
   - **Checkouts:** it refuses uncommitted changes in either checkout: the public code or the private data.
   - **Manifest:** it records `source_commit` (the private repository, as ADR 0007 defines it), the public tool commit and the container digest. It is written by the same manifest code as the content package, a shared module in `tools/`, and signed by the signing tool from ADR 0007. Both are new work.
   - **Licence and size:** the map package contains approved reconstructions, so it is distributed under the content licence, with the third-party notices. The build fails if any file is 2 GiB or larger, GitHub's limit for a release file (ADR 0008).
9. **Map package contents** (inside the layout of ADR 0007):

   ```text
   data/base.pmtiles           vector: land, coast, and the optional valley layer (with a stream-order rank)
   data/relief.pmtiles         raster relief (the source `relief`)
   data/relief-detail.pmtiles  only under option (b)
   data/map.json               tile_schema, camera bounds, zoom limits, credits, reconstructed areas
   ```

   The layer names, attributes and zoom ranges form the **tile schema**, documented in `map-data/README.md` and numbered by `tile_schema`. The extent and zoom range are also written into each PMTiles header from `coverage.yaml`.
10. **Compatibility.** `tile_schema` changes only add. The app tolerates layers or files that are absent, and reads every `tile_schema` ever listed in `stable`, so a background app update never leaves an offline user without a map. A change that needs a second way of reading needs its own ADR. The other direction, a new map on an old app, is covered by `min_app_version` (ADR 0007) and ADR 0008 decision 10.
11. **Credits and disclosure.**
    - `map.json` lists each source's required notice, and the app's notices screen shows the map's credits from it. For Copernicus that is the notice for modified data ("produced using Copernicus WorldDEM-30 © DLR e.V. …") and the licence's liability sentence, checked against the licence files in the tiles' `INFO/` folders before the first release.
    - `map.json` also lists the areas with reconstructed terrain: their footprints and full citation text, generated by the build from the approved reconstruction edits, never written by hand.
    - No label appears on the map. How the app explains reconstruction is decided later.
    - **Gate (owner, 2026-11-14):** a map package with reconstruction edits is listed in `stable` only once an app with that explanation is in `stable`.
12. **Publishing.**
    - A new map package goes to the `test` channel first ([ADR 0008](0008-update-hosting-and-index.md)) and is checked on the three devices (Oppo A55, Galaxy A54, MatePad 11.5) before it is listed in `stable`.
    - Approved edits are batched into planned map versions.
    - Content that places features on a reconstruction is listed in `stable` no earlier than the map that contains it; the app offers both as one update (ADR 0008).
13. **The trial build is the gate before the first map package.** The owner judges every visual check on the three devices, side by side with spike build 11. It covers:
    1. relief at zooms 11 and 12 with the measured size of the full extent, margin included, for the owner to choose the regional maximum zoom;
    2. option (a): the regional relief enlarged to zooms 13–15 at the synthetic test edit, with the fade moved later;
    3. option (c): `color-relief` bands at the test edit, their size, and their cost on the Oppo A55;
    4. if option (b) is needed: the boundary where the two files meet, and the detail maximum zoom and its size;
    5. rendering each zoom separately against rendering at the top zoom and averaging down;
    6. a full build completes on the owner's machine, with its time and peak memory recorded;
    7. the valley lines are clean;
    8. the lava fields and the main valleys read in style C;
    9. which modern features (towers, the 1256 flow, islands) show near events;
    10. the coast from the Water Body Mask against Natural Earth at the widest zoom and, if available, an earlier water mask;
    11. performance on the three devices against the ADR 0002 targets.
14. **The spike's scripts.** `tools/spike/build_base.sh` and `relief_style.py` stay a reference only, and are deleted with the spike.

**Amends:**

- [ADR 0003](0003-v1-architecture-and-modules.md): the map bounds and zoom levels come from the map package (`map.json`), not the content package. Both packages are read at run time, whether bundled or downloaded, so nothing is hard-coded.
- [ADR 0004](0004-build-and-quality-tooling.md): the app's notices screen takes the map's credits from `map.json` instead of a fixed list.
- [ADR 0006](0006-open-source-code-and-closed-content.md): the private repository `seerah-content` also holds the map's private data, in `map/` beside `content/`.
- [ADR 0007](0007-content-formats-compatibility-and-signing.md):
  - a map package's `data/` holds `map.json` besides the PMTiles files;
  - the manifest also records the public tool commit and the container digest;
  - the map has its own build tool, `tools/map/`, sharing the manifest code (decision 3 said one tool);
  - keeping the verified package (decision 7) applies to content packages only;
  - the map package may be a few hundred MB, not "tens of megabytes".
- [ADR 0008](0008-update-hosting-and-index.md): the map package may be a few hundred MB; the publishing rules in decisions 11 and 12 above are added.
- `AGENTS.md`:
  - "approximation is labelled" covers reconstructed terrain disclosed outside the map, as decision 11 sets out;
  - `map-data/` is described as the public tile schema and example.

**Not in scope:**

- the reconstruction method, sources and format (decision 2);
- the disclosure design;
- sea depth;
- delta updates;
- streaming tiles;
- whether the map package ships inside the APK;
- the bounds and zoom values themselves (decision 7);
- the style itself (colours, fades, the zoom ladder), which is app work in `core:map`, the app module that draws the map.

## Consequences

- New work, each with tests:
  - the build tool;
  - the shared manifest module;
  - the schemas for `coverage.yaml` and for terrain edits;
  - the synthetic example;
  - the container image;
  - the app's reading of `map.json`.

  [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) gains the Water Body Mask and the build tools when the build lands.
- An implementation specification in `docs/design/` covers the mechanics:
  - smoothing and generalising the water mask;
  - flow routing for valleys (grid size, tool, edge rule, sand seas);
  - the style C recipe;
  - raster encoding;
  - windowed processing;
  - applying and feathering edits;
  - the camera margin for each zoom;
  - streaming extraction of the map package on the device;
  - under option (b), the tile split, edge softening and fade schedule.
- **Size and storage.**
  - **Package size:** the map package may be a few hundred MB.
  - **During an update:** the device holds the old map, the downloaded package and the new map together, so it needs free space of about three times the package. Streaming extraction can lower this.
  - **Bundling:** a bundled map would need install-time Play Asset Delivery, and would be stored twice, because MapLibre cannot read PMTiles from app assets. This feeds the open first-launch decision.
- **Risks, and what settles them:**
  - **Disclosure.** Reconstructed terrain looks the same as measured terrain; disclosure rests on the list in `map.json` and the explanation decided later. The gate in decision 11 settles it.
  - **Modern terrain.** It shows wherever no edit applies. Trial item 9 shows which features matter, and cleanup edits handle them.
  - **Processing scale.** Processing GLO-30 for the whole extent needs windowed processing: about 3.5 to 4 billion cells, tens of GB if held in memory. Trial item 6 settles this.
  - **Sizes.** They are estimates until trial items 1 and 4.

## References

- PRD v0.2 §2, §5, §13 and the technical study ([snapshot](../prd/PRD-v0.2.ar.md)); [spike findings](../spike/findings.md) and [summary](../spike/summary.md); EGM reviews: [first](../design/egm-review-0009/), [second](../design/egm-review-0009-r2/consolidated_review_report.md).
- MapLibre: [style specification, sources](https://maplibre.org/maplibre-style-spec/sources/); [maplibre-native#4767](https://github.com/maplibre/maplibre-native/issues/4767); [MapLibre Native Android changelog](https://github.com/maplibre/maplibre-native/blob/main/platform/android/CHANGELOG.md) (13.0.0: hillshade methods and the color-relief layer).
- Copernicus DEM: [on AWS](https://registry.opendata.aws/copernicus-dem/) (tiles with `AUXFILES/…_WBM.tif`), the [GLO-30 licence](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf), and the [collection description](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).
- [FABDEM](https://data.bris.ac.uk/data/dataset/25wfy0f9ukoge2gs7a5mqpq2j7) (non-commercial licence; check the current terms on the dataset page).
- [Natural Earth terms of use](https://www.naturalearthdata.com/about/terms-of-use/).
- OpenStreetMap Foundation: [Produced Work guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Produced_Work_-_Guideline) and [Collective Database guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Collective_Database_Guideline_Guideline).
- [HydroRIVERS](https://www.hydrosheds.org/products/hydrorivers), [GSHHG](https://www.soest.hawaii.edu/pwessel/gshhg/).
- [tippecanoe](https://github.com/felt/tippecanoe), [WhiteboxTools](https://github.com/jblindsay/whitebox-tools), [GDAL](https://gdal.org/), [PMTiles specification](https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
- U.S. Geological Survey: [geologic map of northern Harrat Rahat](https://pubs.usgs.gov/publication/sim3428) (the 1256 CE flow).
