# Stage 5: Initial zoom out, ADR 0009 (second review)

**Target:** `docs/adr/0009-map-data-pipeline.md` at commit `a280cb8` (full file).

**Reviewers:** zoom-out and inventive-catalyst, both using `zoom-out-standard.md`. Both completed.

**Context given:** the files the ADR links to inside the repository, `AGENTS.md`, the spike's relief scripts and `map-data/README.md`. Reviewers were told to treat the owner's decisions as given and to report the risks those decisions create.

**Orchestrator check:** ZO-8 is right. No signing tool exists yet; ADR 0007 lists it as new work.

## Zoom-out reviewer

**Verdict:** context sufficient; no blockers; 7 🟡, 6 🟢. The architecture fits the owner's decisions. The main gap is the order of work: the terrain edits are needed to judge the trial and to clean the first package, but they are deferred.

### 🟡 ZO-1. The trial cannot test the condition the detail choice depends on

Lines 57, 127, 181–191.

Options (a) and (c) are kept only if they give clear terrain at site scale, and the detail there "comes from the reconstruction edits". But no edit exists at trial time. So the trial judges (a) and (c) on 30 m terrain that has no detail past zoom 13. It may pass an option that the first real reconstruction later forces to (b), which changes the package layout, `coverage.yaml` and possibly `tile_schema`.

**Fix:** a trial check with a throwaway test edit, or a rule that the choice is made again when the first reconstruction is approved.

### 🟡 ZO-2. Option (b) at sites without edits brings back a look the owner rejected

Lines 50, 57, 138–142, 159.

At important places with no edits, (b) carries 30 m relief to zoom 15–16. Spike build 9 did this, and the owner got "large soft blobs"; the fade to paper was the answer.

**Fix:** under (b), take the detail tiles automatically as the tiles the approved edits touch. That removes the hand-kept list and one open decision.

### 🟡 ZO-3. A three-way conditional runs through the whole ADR

Lines 136–143, 148, 159, 172, 185, 229, 237.

"Under option (b)" appears in seven places, and its mechanics are specified in detail, although (a) and (c) are tried first.

**Fix:** run trial checks 2 and 3 before acceptance and record one path. Or reduce (b) in the ADR to its invariant, "each spot is drawn by exactly one relief source", and leave the split to the specification.

### 🟡 ZO-4. Removing modern terrain is blocked behind the deferred reconstruction work

Lines 29, 119–127, 190, 235.

Flattening city towers and removing artificial islands need no historical sources; they are cleanup. Because they share the deferred edit format, the first package cannot remove the towers around the Haram. Style C may keep them as bumps at the most important site in v1, which contradicts decision 1 ("no modern features"). The 1256 lava flow is different: the ground beneath it is a reconstruction.

**Fix:** split edits into **cleanup** (decided now, with the pipeline) and **reconstruction** (deferred). Or require the edit format before the first map package.

### 🟡 ZO-5. A reverse `tile_schema` check conflicts with ADR 0007 and offline first

Lines 176–179, 230.

"An app that meets a map older than it can read asks the user to update the map." After an automatic app update, an offline user could be left without a usable map until a download of several hundred MB.

**Fix:** each app release keeps reading the `tile_schema` of the map in `stable`, and of the one before it (N and N−1). Breaking changes ship the map first, gated by `min_app_version`, then the app.

### 🟡 ZO-6. The size is not linked to the open bundling decision or to device storage

Lines 18, 24, 209, 232.

- **Play's size limit:** at a few hundred MB, a bundled map likely exceeds Google Play's limit on the base download (about 200 MB compressed; to be verified). That would need Play Asset Delivery.
- **Storage:** MapLibre cannot read PMTiles from assets (ADR 0002), so a bundled map is stored twice.
- **Updates:** every approved reconstruction means downloading the whole map again.

**Fix:** state these costs in the Consequences and in the open-decisions row.

### 🟡 ZO-7. Valleys at 30 m across the peninsula are the heaviest step, for an optional layer

Lines 84, 89, 151, 236.

Flow accumulation over 3.5–4 billion cells drives the memory risk, and it reruns for every approved edit. The lines show at zoom 12 or below, where 30 m adds nothing visible.

**Fix:** route streams on elevation resampled to about 90 m, which is about nine times fewer cells, using the 30 m edited elevation only where edits exist. Or limit valleys to the event region.

### 🟢 Zoom-out nits

- **ZO-8.** "The existing signing tool" doesn't exist yet. Also say where the shared manifest code lives.
- **ZO-9.** The one-repository choice claims "one validator checks the links", but the map tool reads only `map/`. Say which tool resolves the links from edits to sources. The edit schema should reuse the content schema's source and review types.
- **ZO-10.** The private archive of inputs has no location or cost, and little value: fixes go forward, and the SHA-256 pins already detect upstream changes. Drop it, or name where it is stored.
- **ZO-11.** The repository rename should not block the build tool: ADR 0006 already counts historical map data as content, and the tool reads a checkout by path.
- **ZO-12.** The spike rendered style C once at zoom 12 and averaged the wider zooms. If "style C for each zoom" means drawing the bands again at every zoom, the wide zooms will look different from what the owner approved. Either state it as a change and compare in the trial, or reword.
- **ZO-13.** The `sources.lock` hash belongs in the manifest, with the commits and the container digest. The extent and zoom range already sit in the PMTiles header; write them there from `coverage.yaml`. `map.json` keeps camera bounds, credits, reconstructed areas and `tile_schema`.

## Inventive-catalyst reviewer

**Verdict:** context sufficient; real false compromises were found; 6 🟡, 3 🟢, no blockers. Every owner decision was left in place.

### 🟡 IC-1. The trial cannot test what it decides

Same as ZO-1.

**Fix:** add a synthetic fixture to `map-data/example/` with a provisional edit format: a bank or dune of tens of metres, a carved wadi bed and a flattened block. Run trial items 2–4 on it. Place it at a neutral spot, never at the real Badr, so it cannot read as invented terrain. It never ships.

### 🟡 IC-2. Option (b)'s "no double shading" rule rests on untested missing-tile behaviour

Lines 31, 50, 140, 142, 185, 237.

If MapLibre fills a missing tile from its parent tile, the regional file's holes fail and the shading doubles.

**Resolution (inversion):** keep `relief.pmtiles` complete at every zoom. Let `relief-detail.pmtiles` exist only at important places, from the regional maximum zoom up. In the style, fade the regional relief out over 12.5–14.5 everywhere and fade the detail in over the same range.

Outside the important places, the detail source has nothing, so the approved fade to paper is unchanged. The regional file no longer depends on missing-tile behaviour, is the same under (a), (b) and (c), and doesn't change when the list of important places changes.

**Cost:** during the crossfade the shading is up to about 15% lighter at the darkest band, because two half-transparent layers combine to a − 0.25a². The owner must judge this in the trial.

### 🟡 IC-3. WhiteboxTools doesn't fit the windowed processing the ADR requires

Lines 84, 89, 111, 151, 236.

To the reviewer's knowledge (to be verified), WhiteboxTools loads whole rasters into memory, and flow accumulation is a whole-area operation in any case. Buildings and flat sand seas add noise.

**Resolution:** route flow on a grid of the edited elevation at about 90 m. Burn the "carve a channel" edits into that grid. Snap lines to the lowest 30 m cell locally.

**Cost:** lines may sit up to one coarse cell off the valley floor before snapping, and there are two stages to test.

### 🟡 IC-4. The size grew from tens to hundreds of MB without tracing the storage consequences

Lines 18, 167–174, 209, 232; ADR 0007 decisions 5–7; ADR 0002.

ADR 0007's on-device flow was sized for tens of MB: download, verify, unpack into a new folder, then delete the old one. At this size:

- **Updates:** about three times the map size in storage while an update runs (old map, zip and new map).
- **Keeping the verified package** (ADR 0007 decision 7) would store the map twice for good.
- **A bundled map** is stored twice and likely exceeds Play's base download limit (to be verified).

**Resolution:**

- For maps, verify and extract each file as it streams in, then delete the zip, which brings the peak to about twice the map size.
- Record that ADR 0007 decision 7 does not apply to maps.
- Feed the trial's measured size into the bundling question.

**Cost:** re-hashing hundreds of MB to re-verify; streaming extraction must survive the app being killed; free-space checks must assume twice the map size.

### 🟡 IC-5. Every modern reclamation becomes a hand-sourced edit

Lines 29, 73, 78, 123, 133, 190.

The Water Body Mask dates from about 2011–2015. It includes every artificial island and reclaimed coast on both coasts: Jeddah, Dammam and Khobar, Bahrain, Doha, Abu Dhabi, Dubai's Palm islands and Kuwait. Each would need its own edit.

**Resolution:** compare a mask from an earlier epoch in trial item 9, such as SRTM Water Body Data from February 2000 (about 30 m; believed public domain, to be verified). Decision 5's single land mask would then clip relief over later reclamation automatically.

**Cost:** that mask has its own artefacts, still includes earlier reclamation, and must stay the one mask for every layer.

### 🟡 IC-6. The disclosure list is a hand-kept second record that can drift from the edits

Lines 152, 173, 180, 234.

If an approved edit is missing from the list, it ships undisclosed, which conflicts with "approximation is labelled".

**Resolution:** derive the reconstructed areas from the approved edits at build time, as shapes with their sources. Ship those shapes in `map.json`. The app can then tell whether the current view contains reconstructed terrain by checking it against those shapes, with no label drawn on the map.

**Cost:** a slightly larger `map.json`, tests, and a little app logic.

### 🟢 Inventive-catalyst nits

- **IC-7.** Under (b), add a build check that every approved edit falls inside the important places, with a per-edit exemption. Don't fill the list automatically, because it is the owner's decision. (ZO-2 proposes deriving it instead.)
- **IC-8.** Option (c) is also the biggest lever on size: style C's information is all in its transparency, so a single value raster coloured by the app could be far smaller. Measure it in trial 3, and encode the continuous darkness value, not the band number.
- **IC-9.** Keep tile rendering a pure function of the elevation window, the edits that touch it, and the recipe, so tile caching can be added later. Batch approvals into planned map releases.
