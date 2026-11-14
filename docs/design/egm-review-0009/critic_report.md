# Stage 7: Critic review, ADR 0009

**Target:** `docs/adr/0009-map-data-pipeline.md`. FeatureRange: lines 1 to 185 (the whole file).
**Version:** branch `docs/adr-0009-map-data-pipeline`, commit `d64350e`, plus the owner's answers to questions 1 and 2.
**Reviewers:**

- design, against `feature-spec-standard.md`;
- structural, against `feature-spec-standard.md`;
- style, against its persona rules and the house style in `engineering.md`.

All three completed. One round was run and no fixes were applied, because the review is interactive and the owner decides each change.

| Reviewer | 🔴 | 🟡 | 🟢 |
| --- | --- | --- | --- |
| design | 1 | 9 | 9 |
| structural | 0 | 7 | 7 |
| style | 0 | 6 | 14 |

**Checked by the orchestrator against the sources:**

- **DE-2 / ST-6 / SY-1 are right.** The spike's 21 MB style C relief was built from 30 m data (GLO-30): `docs/spike/findings.md` says "four styles were rendered … from the same 30 m data", after build 6 switched to GLO-30. The "from GLO-90" in line 18 was introduced when the orchestrator revised the draft.
- **ST-1 is right.** PRD §3 is "تنظيم المحتوى ومراحل السيرة" (content organisation and Seerah phases). The map scope is in §2 (the الخريطة row) and §5; zoom and bounds are also deferred in §13.

## Design reviewer

The section structure meets the standard. The gaps are in how sound the reasoning is, and in risks the document misses. The reviewer wrote its severities as text; they are mapped to emoji here.

### 🔴 DE-1. A maximum zoom per detail area, in one detail file, brings back the blank-tile failure

Lines 25, 45, 115, 122.

`relief-detail.pmtiles` is one source with one maximum zoom, the highest of all areas. Take area A with maximum 13 and area B with maximum 14. At zoom 14, A has no tiles in a source whose maximum is 14. MapLibre will not stretch A's zoom 13 tiles, and the regional file has a hole there. So area A goes blank exactly where detail was promised. This is the defect line 45 uses to reject the one-file option.

**Fix**, written into decision 4 and the coverage schema, one of:

- (a) all detail areas share one maximum zoom;
- (b) the build fills each area up to the file's maximum zoom by stretching its own top level;
- (c) one detail source per distinct maximum zoom.

### 🟡 DE-2. The approved look and the size baseline are credited to the wrong source

Lines 14, 18, 106.

The 21 MB style C build was rendered from GLO-30, not GLO-90 (confirmed above). Decision 2 picks GLO-90 for the regional relief because it is smaller to download, and checks it only at the seam. Nobody has tested whether GLO-90 reproduces the approved look across the whole regional map.

**Fix:** correct line 18. Then either use GLO-30 resampled by default, or make "GLO-90 matches the approved style C at regional zooms" an acceptance check in the trial build.

### 🟡 DE-3. The seam blend has no defined target, and the two files will not look the same at shared zooms

Line 51, line 122, and the spike's `relief_style.py`.

Style C's smoothing is set in pixels (a 7-pixel blur), its bands are drawn at full resolution, and lower zooms are averaged down. So a detail file rendered at zoom 13–14 from GLO-30 and averaged down has a different texture from a regional file rendered at the regional zoom from GLO-90. Matching transparency pixel by pixel cannot hide a change of texture or band pattern, so every detail area shows as a patch.

**Fix:**

- define the smoothing in metres, not pixels;
- render every zoom of both files with the same recipe for each zoom;
- state the blend target, for example "regional outside, detail inside, linear in the band".

### 🟡 DE-4. The offline blend breaks under the app's fade and when tiles are stretched

Lines 14, 51, 53, 153.

The same failure as Stage 5's IC-1:

- **The fade.** During the 12.5–14.5 fade, and during MapLibre's own tile fade-in, the result differs from the intended shade by o(1−o)·a_d·a_r, which draws a darker ring.
- **Stretched tiles.** Above the regional maximum zoom, the stretched regional pixels no longer line up with the native detail pixels.

**Fix:** a "single writer" band. The detail file carries the fully blended edge pixels and the regional file is cut hard at the detail file's footprint, so no pixel is ever drawn from two semi-transparent layers.

### 🟡 DE-5. The simpler one-file layout is rejected on a premise the ADR leaves open

Lines 25, 45–47, 53; `open-decisions.md`.

A missing tile looks the same as a transparent tile, which is the "faded to plain paper" look. One file fails only if the stretched regional look is chosen, and that look is deferred. One option is missing: a single file with high zooms only in the detail areas, with the band blended inside that file. Its filler tiles can be transparent and are stored once (PMTiles v3 deduplicates them). Also, the blank-tile behaviour (line 25) has not been reproduced on a device.

**Fix:** settle the look outside detail areas first; it is a cheap test with the spike app. Or add the one-file option and state what keeping both looks open costs. Reproduce line 25 on a device.

### 🟡 DE-6. Package churn and size contradict ADRs 0007 and 0008

Lines 18, 38, 117–122, 160.

ADRs 0007 and 0008 assume the map package is "tens of megabytes and changes rarely". Here the detail areas grow with each content release, and the regional file, the largest one, changes with every edit to a detail area, because the areas are cut out of it. So line 160 is wrong: per-area files would not let users download only what changed. Line 38 says size is not a constraint, but the regional maximum zoom alone moves the package between about 44 MB and 175 MB.

**Fix:**

- state a size budget, or a target range for the regional maximum zoom, and how often map updates are expected;
- note that the "tens of MB, rarely" assumption changes;
- correct line 160, or record the trade-off.

### 🟡 DE-7. Build scale and memory are not addressed

Lines 92–93, 108, 137, 158.

- **Memory.** At 30 m, the peninsula is about 3.5 to 4 billion cells, about 14–16 GB per raster as 32-bit floats. Valley extraction needs several such rasters, and WhiteboxTools works in memory: tens of GB on the owner's machine. Style C over the whole extent hits the same limit; the spike's code loads everything into memory.
- **Valleys at the edge.** Valleys draining across the extent edge (from Yemen, Jordan or Iraq) get cut short.
- **CI.** The synthetic CI example cannot expose any of this.

**Fix:**

- add the risk, with mitigations: windowed processing, GLO-90 for valleys at the regional scale, and a memory and time estimate;
- process complete catchments for valleys;
- make "a full build completes on the owner's machine" a trial criterion.

### 🟡 DE-8. Coverage can ship geometry from unapproved content

Lines 111–119, 130, 144.

A buffer around a draft route would ship in the public map package and reveal the route's course. That breaks "nothing unreviewed reaches users" (ADR 0007).

**Fix:** the map build refuses unapproved coverage entries and unapproved referenced items, and `coverage.yaml` carries its own review status.

### 🟡 DE-9. Compatibility only works one way

Lines 119, 121, 148.

Raising `tile_schema` with `min_app_version` stops old apps from misreading new maps. The reverse is not covered: a new app whose style expects a newer schema, running an old map package the user never updated, or one bundled in the APK. Separately, the content warning checks coverage at the repository's latest commit, not the map package published to `stable`.

**Fix:**

- state the app's rule: keep a style for each schema it may meet, or show "map update required" and say what it shows meanwhile;
- check content against the published coverage.

### 🟡 DE-10. The example rule for correction outlines would remove natural land

Lines 124, 128.

The difference between the water mask and Natural Earth gives slivers along the whole coast, plus natural islets and reefs that Natural Earth leaves out. Natural Earth also carries no dates, so the rule cannot show "built after 2000". Removing that land would delete real 7th-century terrain.

**Fix:**

- drop the example;
- require each removal to name the specific feature, with a dated source showing it is artificial;
- a rule may only propose outlines for one-by-one review, never a bulk mask.

### 🟢 Design nits

- **DE-11.** Camera bounds in MapLibre limit the centre of the view, not its edges. At minimum zoom on a landscape tablet the view reaches half a screen past them, so the margin must be calculated from the minimum zoom and the largest supported screen.
- **DE-12.** Relief, valleys and land must use one land mask. The spike treated elevation above 0.5 m as land.
- **DE-13.** Apply corrections to the elevation data before both relief and valleys; towers and dams distort water flow.
- **DE-14.** "Smooth relief" over the 1256 lava flow amounts to an estimated pre-eruption surface. Either allow estimated surfaces explicitly, labelled, or limit the flow to "hide relief".
- **DE-15.** Rejecting a lava-field layer as "a model's output" is inconsistent with accepting valley extraction, which is the same kind of calculation. The real reason is that no published outline exists to check it against.
- **DE-16.** Record the public repository's commit and the container digest, and refuse a public checkout with uncommitted changes. Keep a private archive of the pinned inputs, since upstream copies can change.
- **DE-17.** Warn also for items a story step frames with a medium or close shot, not only `always` items and routes.
- **DE-18.** List the performance risk of a second raster source on the Oppo A55 (200 MB memory budget).
- **DE-19.** Say "amends ADR 0004" for the credits. `open-decisions.md` already cites ADR 0009 while it is Proposed; say it is reverted if the ADR is not accepted.

## Structural reviewer

Headings, tables, the code fence and relative links all pass. The house sections map onto the standard.

### 🟡 Structural majors

- **ST-1.** "PRD v0.2 §3" (lines 7, 13, 177) is the wrong section: §3 is content organisation. The map scope is in §2 (line 31), zoom in §5 (line 244), and deferral in §13 (line 360). Confirmed above.
- **ST-2.** The owner's choices (lines 75, 85, 171–172) are not linked to a record. ADR 0008 links its pull request.
- **ST-3.** The Decision section is not self-contained. These choices exist only under "Options compared":
  - the raster relief type, with engine relief as the fallback;
  - the valley condition ("if the trial gives clean lines"), "unnamed", and "dry valleys";
  - vector layers at one level of detail everywhere;
  - merging the two files once `sparse` works.

  An implementer reading only the decisions would build valleys unconditionally.
- **ST-4.** Decision 3 changes an accepted ADR (bounds move to the map package) with no alternatives compared, for example keeping bounds in content, keeping coverage public, or deriving bounds from the tiles. Decisions 5 and 8 have the same gap, less seriously.
- **ST-5.** Amendments are not declared in the house form ("**Amends** [ADR …]"):
  - ADR 0003 (bounds), buried in a paragraph;
  - ADR 0007 (`data/` now holds more than PMTiles files), not stated;
  - ADR 0004 (credits list), not stated.
- **ST-6.** The 21 MB figure is credited to GLO-90; it was GLO-30. Same as DE-2.
- **ST-7.** No overview shows which source goes through which step into which output file, or whether corrections are applied before or after resampling. A short table or diagram would close this.

### 🟢 Structural nits

- **ST-8.** The status line still asks for answers to all questions, though 1 and 2 are answered, and does not link the review.
- **ST-9.** `content/examples/` should be `content/example/`.
- **ST-10.** A spike prediction is presented as a measurement (line 20), and "regional zoom" has two meanings.
- **ST-11.** ESA WorldCover, GSHHG, Planetiler, GDAL, GRASS and pysheds are not linked.
- **ST-12.** Labels are inconsistent: "**The seam.**", the unlabelled deferral at line 53, and decision 10 without a lead phrase. The seam design is also repeated.
- **ST-13.** Answered questions repeat the choices. The deferred "outside a detail area" look is not listed under the questions or "Not in scope".
- **ST-14.** Decision 3 says coverage is in one file, but decision 7 splits it between `coverage.geojson` and `map.json`. Where the per-area maximum zooms go is not stated.

## Style reviewer

The prose is mostly plain, in the present tense, and free of marketing words.

### 🟡 Style majors

- **SY-1.** Line 18's GLO-90 is wrong; it was GLO-30. Same as DE-2.
- **SY-2.** "What the spike measured" includes things that were not measured. "Natural Earth 1:10m looks angular" (lines 20, 59) was a prediction (findings line 78), never checked on a device.
- **SY-3.** Line 25 (blank tiles) is stated as fact on the strength of an upstream issue, not tested on a device. The whole two-file decision rests on it.
- **SY-4.** "Size is not a constraint once detail is limited" (line 38) presents an unmeasured result as settled. The real basis is the owner's statement. "Too large" has no threshold.
- **SY-5.** "The coast matches the relief exactly" (line 60) stops being true once the mask is smoothed.
- **SY-6.** "Regional zoom" has three meanings: the regional maximum zoom, the coastline switch zoom, and a loose viewing scale. Define one term for each.

### 🟢 Style nits

- **SY-7.** The status line (see ST-8).
- **SY-8.** "Not an owner decision on its own" is vague; "this ADR builds the mechanism" should be "defines".
- **SY-9.** Long sentences at lines 14, 51, 53, 61, 117, 137 and 149, including the buried ADR 0003 amendment.
- **SY-10.** "Each zoom level less divides that by about four" reads awkwardly.
- **SY-11.** "Cells" in line 21 are never introduced.
- **SY-12.** "Improved in MapLibre Native 13.0" is vague, and "cannot make style C's bands" needs a qualifier.
- **SY-13.** The tables use unquantified words: "small", "cheap", "the most precise", "awkward", "normally", "nobody needs".
- **SY-14.** "The best free elevation data" should be "the finest".
- **SY-15.** "Mostly modern reservoirs" (line 64) has no source.
- **SY-16.** The lava fields were shown by a realistic relief preview, not style C. "The painted relief still shows the main valleys" is unchecked.
- **SY-17.** "Proven in the spike" overstates (tippecanoe needed a workaround); "rio-tiler and similar" and "heavy to install" are vague.
- **SY-18.** Line 99 calls names and borders "modern features"; `always` is not explained.
- **SY-19.** "Band" and "feather width" name one thing; "go with the spike" is unclear.
- **SY-20.** "The app makes no claim that the relief is historical" is not a decision anywhere, and line 166 is listed as a risk but is neutral.
