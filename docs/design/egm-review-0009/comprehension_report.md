# Stage 6: Comprehension test, ADR 0009

**Target:** `docs/adr/0009-map-data-pipeline.md`, lines 1 to 185. FeatureRange: the whole file. DetailedRange: none.
**Reviewer:** comprehension, completed. It read the document alone, with no standard and no other context.

**Verdict:** no Blockers. Every section could be paraphrased. There are 3 Majors:

- "regional zoom" has two meanings;
- it is unclear which repository holds which file;
- the seam mechanism cannot be followed to the end.

The other 9 findings are Nits.

## What this proposes

The ADR settles how v1 builds the **base map**, the terrain-only layer under the historical content. It covers land, sea, coastline, painted relief and unnamed valleys. There are no names, borders, roads or other modern features.

- **Relief** is pre-rendered offline in the spike's "style C" and split into two PMTiles files that do not overlap:
  - a regional file covering the whole extent up to a regional maximum zoom, with the detail areas cut out;
  - a detail file holding only the detail areas, at higher zooms.

  Where the two meet, the build tool computes a blend so the shading looks continuous.
- **Coastline and land** come from Natural Earth at the widest zooms and from the Copernicus water mask (ocean class only) closer in. **Valleys** are derived from the Copernicus elevation data with WhiteboxTools, but only if a trial build gives clean lines.
- **Configuration files.** Camera bounds, extent, zoom limits and detail areas live in `map/coverage.yaml` in the content repository. The owner reviews each fix for modern features (artificial islands, city towers, a 1256 CE lava flow) in `map/terrain-corrections.yaml`. A fix may only remove land, hide relief or smooth relief, never add terrain.
- **Build and release.** One pinned, containerised Python tool in `tools/map/` downloads the hash-pinned sources, applies the approved corrections and writes the map package. The package contains three PMTiles files, `coverage.geojson`, and `map.json`, which carries the tile-schema version, camera limits and attributions. Each package is checked on devices in the `test` channel before it goes to `stable`.

## How the existing system works, per this document

- **App and packages.** The app renders local PMTiles with MapLibre Native. The style, icons and fonts are built into the app. Historical places, routes and areas come in a separately versioned, signed content package. The base map comes in a map package (ADR 0007), delivered through update channels (ADR 0008).
- **The spike.** A prototype fixed the look as style C, a semi-transparent relief with three soft bands that fades out between zoom 12.5 and 14.5. It also measured relief size (about 21 MB for a 91,000 km² test area at zooms 6–12) and found two tippecanoe and Natural Earth pitfalls. It was built with a Bash script that this ADR retires to reference only.
- **Current gaps.** ADR 0003 currently says map bounds come from the content package. ADR 0004 currently keeps the map attributions as a fixed list in the app. MapLibre Android leaves a tile blank when it is missing from a PMTiles file; the experimental `sparse` flag that would fix this is not supported on Android yet.

## Findings

### 🟡 CO-1. "Regional zoom" is never defined, and "regional" means two things

Lines 20, 53, 59, 60, 104, 105; compare lines 47, 114 and 122.

- **Line 20** says Natural Earth "looks angular at regional zoom".
- **Lines 104–105**, the source table, split the land and coastline sources between "widest zooms" and "regional zoom and closer".
- **Line 53**, "zoomed in past the regional zoom", seems to mean the *regional maximum zoom* from `coverage.yaml` (line 114).
- **Elsewhere**, "regional" names the regional *relief file*.

A reader cannot tell whether "regional zoom" is a band of zooms, the Natural Earth to water-mask switch zoom (deferred at line 158), or the regional maximum zoom.

**Fix:** give it a number or a range, or say it is set in the implementation specification. Use a different word for the file.

### 🟡 CO-2. Unclear which repository or folder holds which file

Lines 100, 111, 123, 137, 148, 157.

The document uses several places without saying where they are:

- `seerah-content`, which is read "as a checkout";
- `content/examples/` and `content/schema/`;
- `map-data/sources.lock` and `map-data/README.md`;
- `tools/map/`, described as "public", which implies something else is not;
- "the validator" and "the content build", both undefined.

So the reader cannot answer:

- where `sources.lock` lives and who edits it;
- whether the schemas sit beside the YAML files they validate;
- why CI uses a synthetic example instead of `seerah-content`.

**Fix:** a sentence or a small table mapping each path to its repository.

### 🟡 CO-3. The seam mechanism cannot be followed to the end

Lines 47, 51, 122.

The reason a plain crossfade leaves a lighter ring is explained well. Two things are not:

- **Where the fade band sits.** One file has the detail areas cut out and the other covers only the detail areas, yet both "fade over the same band". One file must therefore reach past the cut line into the band. Which file, and on which side of the boundary is the band?
- **What happens above the regional maximum zoom.** There, MapLibre stretches the regional file while the detail file has native tiles, so the build tool has no matching pixels to compute the blend from. Is the seam accepted as imperfect there, or hidden by the "faded to plain paper" look (line 53)?

"The two never shade the same place twice" (line 47) also seems to conflict with a shared fade band.

### 🟢 Nits

- **CO-4.** The rejection of "a complete regional file, with a detail file drawn on top" (line 46) depends on an unstated assumption: that the detail file starts only above the regional maximum zoom. The real reason for rejecting it is that it forces the still-open "faded to plain paper" look; the row should say so.
- **CO-5.** "Size is not a constraint" (line 38) has no number behind it. A sample figure would ground it, for example "regional to zoom N is about X MB, plus Y MB per detail area of size Z".
- **CO-6.** "The three devices" (line 150) are not named or linked.
- **CO-7.** "Free exploration", the value `always` and "a documented route" (line 119) are content-model terms used without a definition or a link to ADR 0005.
- **CO-8.** The app's "no more detail here" behaviour (line 144) is not described, and `core:map` (line 153) is not introduced as an app module.
- **CO-9.** "The content rules" (lines 82–83) are not linked.
- **CO-10.** Line 60 points to "corrections, below" for reclamation as well as artificial islands, but line 130 keeps reclaimed coast as the data shows it. The pointer should say only islands are corrected.
- **CO-11.** Three small unclear references:
  - "WorldDEM-30" and "WorldDEM-90" (line 149) are not tied to "GLO-30" and "GLO-90";
  - "the manifest" (line 137) does not say which manifest;
  - "from the lowest zoom" (line 122) does not say the lowest zoom of what.
- **CO-12.** It is unclear whether `coverage.yaml` needs `review: approved`, as the corrections do (lines 111–117, 130).
