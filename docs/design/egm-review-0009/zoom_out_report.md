# Stage 5: Initial zoom out, ADR 0009

**Target:** `docs/adr/0009-map-data-pipeline.md` (full file), branch `docs/adr-0009-map-data-pipeline`, commit `d64350e` plus the owner's answers to questions 1 and 2 (not yet committed).
**Mode:** interactive review.
**Reviewers:** zoom-out and inventive-catalyst, both using `zoom-out-standard.md`. Both completed.
**Context given:** the files the ADR links to inside the repository, the spike's relief scripts, and `AGENTS.md`. External URLs were not given, because reviewers cannot open them.
**Not yet in the ADR:** the outline rule proposed for question 3, under which hand-drawn outlines are allowed only for "hide relief".

## Zoom-out reviewer

**Verdict:** revise before acceptance. 0 🔴, 5 🟡, 4 🟢.

The context is good and the objective is clear: a terrain-only base map for the whole peninsula, at a size that can ship. The concern is that the most complex parts are chosen before anyone has checked whether simpler options would do:

- the two complementary relief files with a computed blend at the seam;
- coverage tied to content entities;
- a general terrain-corrections mechanism.

### 🟡 ZO-1. The simplest workable option is missing: one relief file at a lower uniform zoom

Lines 14, 18, 38, 44, 47; ADR 0002 line 29.

The only uniform option compared is zoom 12 everywhere, at about 700 MB. By the ADR's own rule (each zoom level less divides the size by four), zoom 11 would be about 175 MB and zoom 10 about 44 MB.

Style C is deliberately soft. The spike blurs the elevation data by about 270 m at zoom 12, and the approved look already stretches zoom 12 tiles up to where the relief fades out (zoom 12.5 to 14.5). A soft painted raster stretched one more zoom level may look the same as native zoom 12. If it does, one file covers the whole map, and these all go away:

- the second relief source;
- the hole-cutting and the seam blend;
- the per-area maximum zoom;
- the content build's "outside every detail area" warning;
- most of `coverage.geojson`.

The PRD's "most detail where the events happen" is largely met anyway by the historical layers, which exist only there, and by the vector layers.

**Recommendation:**

- Add "uniform zoom 10 or 11, stretched beyond it" as an option.
- Have the trial build compare zooms 10, 11 and 12 in the Hijaz before committing to two files.
- Keep the two-file design as the fallback if the trial shows a visible loss.

### 🟡 ZO-2. The two-file variant chosen is the most complex one, and its complexity serves a look that is still undecided

Lines 46–47, 51, 53, 106, 122.

- **What the complexity buys.** The holes at every zoom, a detail file starting at the lowest zoom, and the compensated blend all exist to keep one look open: the regional relief stretched past its maximum zoom outside the detail areas (line 53). The other look, plain paper outside the detail areas, follows the spike's approved principle that close views are paper. With that look, a complete regional file plus a detail file on top (line 46) needs no holes and no computed blend.
- **Holes are cut at more zooms than needed.** Even with the stretched look, holes are only needed in the regional file's top zoom, and the detail file only needs zooms from the regional maximum up. Holes at every zoom create a seam at wide zooms, where GLO-90 and GLO-30 cannot be told apart. That seam is why line 106 has its "GLO-30 instead" hedge.
- **The blend is only approximate where it matters.** Above the regional maximum, the regional file is stretched (resampled) while the detail file is native.
- **Line 51 is compositing maths**, which belongs in the implementation specification, not the ADR.

**Recommendation:** ask the owner now how the map looks outside the detail areas past the regional zoom, as question 4. If the answer is paper, use a complete regional file with the detail file on top. If it is the stretched look, limit the holes and the detail file to the regional maximum zoom and above.

### 🟡 ZO-3. "A buffer around a content place or route" ties the map tool to the content model

Line 115; also lines 119 and 137.

To compute a buffer, the map tool must load and resolve content entities. Under ADR 0005 those are compiled from year files into one history per entity. So the map tool either re-implements that compilation or depends on the content build, while the content build reads coverage for its warnings. That is a two-way dependency between two tools that ADR 0007 versions apart.

**Recommendation:** allow only polygons in `coverage.yaml`. A buffered route can be generated once and committed with a note on where it came from. The warning at line 119 still catches a polygon that has drifted away from the content.

### 🟡 ZO-4. The terrain-corrections register is built before there is evidence it is needed

Lines 110, 123–136, 157; question 3.

Decision 5 commits to a schema, three treatments, rules for outline sources, a review flow and per-correction pins. Yet every candidate is conditional:

- **Towers:** to be decided after the trial, and style C's blur and fade make it unlikely they show.
- **The 654 AH lava flow:** its outline is still "to be checked".
- **Makkah's cut mountains:** only "if a published outline exists".
- **Gulf artificial islands:** far from the v1 events, and mostly created by choosing the 30 m water mask for the coast.

**Recommendation:**

- Keep the principles in the ADR: never add terrain, outlines only from licensed data or stated rules, owner approval for each entry, and the risk statement.
- Defer the register and its treatments until the trial build shows a visible modern artefact, then build only the treatment that is needed.
- Add "smooth relief" last, because it is the most speculative.

### 🟡 ZO-5. The map tool's overlap with the content build is not stated, so the same code would be written twice

Lines 100, 137, 157; ADR 0007 decisions 3–5.

The map tool needs:

- strict YAML loading and schema validation;
- filtering to `review: approved`;
- refusing a checkout with uncommitted changes and recording its commit;
- writing the manifest and signing it.

ADR 0007 gives all of these to the content build. "Like the content build" (line 137) means copying its behaviour, not sharing it, and ADR 0009 never says who writes and signs the map package's manifest.

**Recommendation:** both tools share one Python package under `tools/` for these steps, and the existing signing tool signs map packages.

### 🟢 Zoom-out nits

- **ZO-6.** GLO-90 is a second elevation source used only to save download bytes. It adds a second notice, a seam risk and the hedge at line 106. Use GLO-30, resampled, everywhere.
- **ZO-7.** `tile_schema` looks like a second compatibility gate, but every breaking change already raises `min_app_version`. Either say it is for documentation only and the app never checks it, or drop it.
- **ZO-8.** Line 137 says `content/examples/`; the repository folder is `content/example/`.
- **ZO-9.** The valley layer is listed as fixed (decision 1, line 99; `base.pmtiles`, line 141) although it depends on the trial. Mark it optional, so that failing the trial is not a schema change.

**Does not need changing:**

- a terrain-only base map, with names as content;
- `sources.lock` pinned by hash;
- reusing `review: approved` and the `test`→`stable` channels;
- bounds and tiles in one package;
- engine-drawn relief kept as the fallback;
- tippecanoe and GDAL, with the spike code as reference only;
- deferring delta updates and per-area files;
- the water mask instead of Natural Earth, which the spike's angular-coast finding justifies.

## Inventive-catalyst reviewer

**Verdict:** the context is sufficient. 0 🔴 (its mode gives advisory findings only), 5 🟡, 3 🟢.

Two parts of the ADR already resolve their contradictions well: splitting relief into regional and detail files, and having the data carry its own attribution in `map.json`. Some limits are fixed constraints, not false compromises: no real detail past about zoom 13, the ODbL and LGPL licence terms, and no `sparse` support on Android. The reviewer did not open `THIRD_PARTY_NOTICES.md` and only searched ADRs 0002, 0004 and 0005.

### 🟡 IC-1. The computed seam blend only holds when nothing changes the layers' opacity, and the app's style does

Lines 14, 46–47, 51, 53, 122.

The blend fails in three ways:

- **The fade breaks it.** The style fades both layers out between zoom 12.5 and 14.5. With both at opacity `o`, the result is `o·a_d + o·a_r·(1 − o·a_d)`, not `o·S`. The error, `o·a_r·a_d·(1 − o)`, is never zero inside the fade. For example, with S = 0.5, a_d = 0.25 and o = 0.5, the result is about 0.27 against 0.25: a darker ring in exactly the fade zone.
- **The two files don't have the same pixels at each zoom.** The regional file is stretched above its maximum zoom, while the detail file has native pixels there, so one set of regional edge pixels cannot match several detail zooms. Averaged overviews below the regional maximum also break the formula, because the blend is not linear.
- **There are several maximum zooms.** The regional file has one; each detail area has its own.

**Resolution (segmentation):** make the two files a non-overlapping, tile-aligned partition.

- Each detail area is a set of whole tiles at the regional maximum zoom (Zr).
- The regional file is complete below Zr and leaves exactly those tiles out at Zr. MapLibre's "missing tile shows nothing" then becomes the hole-cutting mechanism.
- The detail file holds those tiles and their children, from Zr up.
- The change from the regional look to the detail look is baked offline into an edge ring inside the detail tiles.
- Because the layers never overlap, a single opacity scales every pixel the same way, and the fade stays correct.

**Costs:**

- Detail areas become blocky tile unions, about 9 km squares at zoom 12, and the "no more detail here" outline becomes square.
- Changing Zr re-partitions every area.
- The baked ring favours the stretched look outside detail areas, so that look must be chosen together with this design.
- `sparse` must stay off for the regional file, or it would fill the holes.
- Filtering at tile edges and lossy WebP make the edge match close, not exact.

### 🟡 IC-2. "The look is baked into the map package" assumes the shading must ship as finished colour pixels

Lines 11, 14, 34–35, 38.

The spike's style C writes one constant ink colour and keeps all the information in transparency, capped at 0.6 (`relief_style.py` lines 22–23, 60–63). So only the colour and the cap are really baked in; the band shapes are computed separately.

**Resolution (extraction):** keep the shape offline and move the appearance into the app's style.

- **Minimal step:** store the band intensity at the full 0–1 range and let the app set the maximum darkness with `raster-opacity`, which it already uses for the fade. Darkness becomes a style choice, and transparency gets about 1.7 times more levels, which matters because the spike saw banding when that precision was reduced.
- **Fuller step, to try:** ship the band intensity as a single-value `raster-dem` with `custom` encoding, drawn by the `color-relief` layer. The colour ramp then lives in the app's style.

**Costs:**

- The value tiles must be lossless, so their size needs measuring.
- `color-relief` on MapLibre Native Android 13 needs checking on the Oppo A55, and support for `custom` encoding on Native is unconfirmed.
- The style and the tile schema then share a value scale.

### 🟡 IC-3. Coverage is tied to live content geometry: the check cannot fail for buffer areas, and draft geometry could ship

Lines 111–121.

- **The check cannot fail.** The map build and the content build read the same checkout, so a buffer area always contains the item it surrounds. The mismatch that matters is between new content and the *published* map package, and "published with, or before" (line 121) is a process rule that nothing enforces.
- **Draft geometry could ship.** Nothing requires a buffered place or route to be `review: approved`, so a draft route's outline could ship in `coverage.geojson`, getting around ADR 0007 decision 4 ("only approved content ships").

**Resolution:**

- Buffers resolve only against approved items.
- The resolved coverage is committed with the map version, as a reviewable file.
- The content build checks against the published coverage and names the map version needed.

**Costs:**

- The content build depends on a published file that can go out of date.
- When content and map are released together, a "pending map version" exception is needed.

### 🟡 IC-4. `map.json` fixes the relief layout, so both planned layout changes become breaking changes

Lines 49, 138–148, 160, 166.

Merging the two files once `sparse` works, or splitting detail into one file per area, would each raise `tile_schema` and `min_app_version`. Under ADR 0008, apps too old for the newest package of a kind get no more updates of that kind.

**Resolution (inversion):** `map.json` lists the raster relief sources (path, role, zoom range, bounds), and `core:map` adds one source and layer for each entry. Merging or splitting files then changes only data.

**Costs:**

- The style is partly generated at runtime.
- It needs unit tests.
- Many sources cost memory on the Oppo A55.

### 🟡 IC-5. Removing a modern feature can leave a hole in its own shape

Lines 123–136, 163.

- **"Hide relief" draws the outline it is meant to remove.** Inside the 654 AH lava flow, it would draw a smooth, paper-coloured tongue in the exact 1256 outline, in the middle of a textured lava field. That is a visible modern shape.
- **"Smooth relief" invents heights** instead.

**Resolution:**

- Add a "generalise" treatment: increase style C's own blur inside a feathered mask. This uses the map's existing principle of not showing more detail than the source has.
- Add a device-review rule: an applied correction must not itself be visible as a shape.
- The trial may show that no correction is needed at all.

**Costs:**

- It is still a change to the data, so the entry must say so.
- Blur mixes in neighbouring heights, and a large feature can still leave a soft bump.
- Each entry needs its own tuning.

### 🟢 Inventive-catalyst nits

- **IC-6.** Two coastline sources switched by zoom add a seam and a parameter, and Natural Earth's 1:110m and 1:50m served the spike's world view, which v1 does not have. Use the smoothed water mask, generalised per zoom by tippecanoe, and keep Natural Earth only if the trial shows it looks better at the widest zoom. Costs: hand-generalised coasts may look better, the mask's large polygons may bring back the spike's dropped-polygon problem, and turning the mask into polygons is heavy.
- **IC-7.** Two elevation products add a second seam variable and a second notice to save a one-time download. Use GLO-30 everywhere; the cost is about 9 times more input data.
- **IC-8.** Apply the corrections once, to a single corrected terrain (elevation and land/sea mask), and feed every product from it. Otherwise relief, valleys and coast can disagree, for example valley lines on a removed island. Record its hash in `map.json`.
