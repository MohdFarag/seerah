# Stage 8: Intermediate zoom out, ADR 0009

**Target:** `docs/adr/0009-map-data-pipeline.md` (185 lines, unchanged since Stage 5).

**Reviewer:** zoom-out, using `zoom-out-standard.md`; completed. It read:

- the Stage 5, 6 and 7 reports;
- `AGENTS.md`, `open-decisions.md`, the spike summary and findings, and `relief_style.py`;
- parts of ADRs 0003, 0007 and 0008, PRD §2, §5 and the technical study, the requirements and the content-model specification.

**Verdict:** revise before acceptance. 1 🔴, 6 🟡, 3 🟢.

## Summary

Most of the 82 earlier findings share one root cause: the relief is split into a regional file and detail files. These findings all exist only because of that split:

- the Blocker DE-1;
- the seam findings (IC-1, DE-3, DE-4, CO-3, CO-4, ST-12, SY-19);
- the coupling between coverage and content (ZO-3, IC-3, DE-8, DE-17, CO-7, ST-14);
- IC-4, DE-6, SY-3, DE-5 and DE-18.

One owner decision removes all of that. What is left is a short list of real changes:

- factual fixes;
- a Decision section that stands alone;
- one terrain dataset feeding every layer;
- an answer to question 3.

Several proposed fixes would make the design heavier than one publisher needs, and are cut:

- IC-3 and IC-4;
- IC-1's tile-aligned partition;
- IC-2's `color-relief` step;
- IC-5's fourth treatment;
- DE-9's check against published coverage.

## Findings

### 🔴 ZI-1. Make the relief one uniform file, settled by one owner decision

Lines 14, 18, 38, 40–53, 106–107, 111–122, 141–144, 158, 160, 166; `open-decisions.md` line 7.

The relief layout as written has three problems:

- the confirmed blank-area defect (DE-1);
- a seam blend that three reviewers show breaks during the app's own fade (IC-1, DE-4, CO-3);
- the simplest option was never compared (ZO-1).

The evidence points to one uniform file:

- **The approved look is already uniform zoom 12.** Build 11 was relief at zooms 6–12 with no route corridor. Higher zooms add almost nothing: the relief fades out from zoom 12.5, and the data has no detail past about zoom 13.
- **The owner said size is not a constraint**, twice. Line 44 rejects uniform zoom 12 without asking.
- **The 700 MB estimate is probably high.** It comes from the most rugged sample, and style C writes full transparency over sea and flat ground. Many of the peninsula's tiles are therefore identical empty tiles, which PMTiles stores once.
- **Uniform relief removes churn.** The map package no longer changes as later releases cover more of the Seerah, which matches "changes rarely" in ADRs 0007 and 0008.

What goes away:

- the second relief source and the hole-cutting;
- the seam blend;
- per-area zooms and detail areas;
- `coverage.geojson`;
- the content-build coverage warning;
- the `sparse` note;
- the open decision on the look outside detail areas.

**Owner decision:** should the relief have the same detail everywhere, in one file, at the zoom the owner picks from the trial's side-by-side screenshots of zooms 10, 11 and 12 in the Hijaz, each with its measured size for the whole peninsula? Or must it be finer around the events?

**Recommendation:** the same detail everywhere.

**Fallback,** if finer relief around the events is required:

- one maximum zoom shared by all detail areas;
- detail areas stored as committed polygons;
- the detail layer only above the regional maximum zoom, and the regional layer stopping there (so outside the detail areas is paper);
- the edge fade baked into the detail tiles;
- no computed blend and no holes.

### 🟡 ZI-2. Map configuration belongs in the public `map-data/`, not in `seerah-content`

Lines 111–117, 123, 137, 157; `AGENTS.md`.

Without buffers or detail areas, coverage is just camera bounds and zoom numbers. The corrections register describes modern features, not Seerah content, and `AGENTS.md` already puts "base-map pipeline inputs" in `map-data/`.

Moving both files there:

- removes the map tool's need for a `seerah-content` checkout;
- removes the two-way dependency between the map tool and the content build;
- removes the need for the synthetic coverage example;
- mostly settles CO-2.

The bounds still reach the app through `map.json`. Because this changes what is public, it is an owner question. **Recommendation:** make both public.

### 🟡 ZI-3. Answer question 3 with principles only, and defer the mechanism

Lines 110, 123–136, 162–163, 173.

**Recommended answer:**

- **Accept the principles:** never add or restore terrain, no guessed past terrain, outlines only from licensed datasets, and owner approval for each entry.
- **Defer the register file and its treatments** until the trial shows a specific visible modern artefact.
- **Drop "smooth relief".** Interpolated heights are guessed terrain (DE-14).
- **Drop the example rule "water mask minus Natural Earth".** It would delete real islets (DE-10); this fix is needed whatever else is decided.

**On the orchestrator's earlier advice to the owner:** the reviewer agrees with "do not guess past terrain". It disagrees with allowing hand-drawn outlines for "hide relief": hiding draws its own outline as a paper-coloured shape (IC-5), so a hand-drawn outline would put a hand-made shape on the map. It is also not needed for the likely case, because city towers have a licensed outline in ESA WorldCover.

**Also:**

- add a device check: "no applied correction is visible as a shape";
- the cheapest honest mitigation is one line in the map credits saying the terrain comes from modern elevation data (SY-20), asked as part of question 3.

### 🟡 ZI-4. Fix the factual errors and unchecked premises before acceptance

- **Line 18:** GLO-30, not GLO-90.
- **Lines 7, 13, 177:** PRD §2, §5 and §13, not §3.
- **Line 38:** "size is not a constraint" is unmeasured; ZI-1 replaces it.
- **Line 160:** wrong; removed under ZI-1.
- **Line 128:** the example outline rule.
- **Lines 81 and 85:** "lava fields show clearly" came from the realistic preview, not style C. The owner's answer to question 2 rests on it, so the trial must check it, and question 2 is revisited if it fails.
- **Size assumption:** state that "tens of MB, changes rarely" in ADRs 0007 and 0008 depends on ZI-1.

### 🟡 ZI-5. The Decision section must stand alone, and amendments must be declared

Lines 38, 49, 75, 97–151, 117.

- **Move into the decisions** what now sits only under "Options compared":
  - raster relief, with engine relief as the fallback;
  - the valley condition, "unnamed" and "dry valleys";
  - the valley layer marked optional.
- **Declare** "**Amends** ADR 0003 (bounds), ADR 0004 (credits), ADR 0007 (`data/` holds more than PMTiles)".
- **One sentence** on why the bounds belong in the map package; no comparison tables needed.

### 🟡 ZI-6. One elevation source and one corrected terrain feed every layer

Lines 60, 102–108, 123–126, 149.

- **GLO-30 everywhere,** resampled for each zoom. This gives one notice, no hedge at line 106 and no doubt about GLO-90; the larger download happens once.
- **One land mask, and one corrected elevation if any correction is approved,** feed the relief, the valleys and the coast.
- **A five-row data-flow table** (source → step → output file).

### 🟡 ZI-7. The trial build is the real gate; list its checks once

Line 158. One checklist:

1. zooms 10, 11 and 12 side by side, with the measured size for the whole peninsula;
2. a full build completes on the owner's machine;
3. the valley lines are clean;
4. whether city towers are visible;
5. whether lava fields and valleys read in style C;
6. Natural Earth against a coast from the water mask only, at the widest zoom;
7. performance on the Oppo A55 against the ADR 0002 targets.

The rest of DE-7, DE-3, DE-11, DE-16 and IC-2 goes to the implementation specification.

### 🟢 ZI-8. Keep `tile_schema`, and state the reverse rule in one sentence

It lets a new app detect an old map package (DE-9). Add: schema changes are additive where possible, and the app tells the user when a map is older than it can read. Cut IC-4.

### 🟢 ZI-9. Reuse the content build's manifest code and the signing tool

One sentence. With ZI-2, the uncommitted-changes check applies to the public repository, and the build records the container digest (DE-16).

### 🟢 ZI-10. Small wording and path fixes

- `content/example/`, not `content/examples/`.
- Define or remove "regional zoom".
- Update the status line.
- The pointers and naming in CO-9, CO-10, CO-11, SY-5, SY-15 and SY-18.
- Name the three devices, and introduce `core:map`.

**Does not need changing:**

- a terrain-only base, with names as content;
- `sources.lock`;
- the `test`→`stable` review on devices;
- attribution in `map.json`;
- the tools behind the trial gate;
- one Python tool in a pinned container;
- the spike code as reference only;
- delta updates out of scope.

## Owner decisions, with recommended answers

1. **The same relief detail everywhere?** (ZI-1) **Yes,** at the zoom picked from the trial's screenshots and sizes.
2. **Question 3, modern terrain.** (ZI-3)
   - Accept the principles.
   - No estimated or restored terrain, so drop "smooth relief".
   - No hand-drawn outlines in v1.
   - Build the register only when the trial shows a specific artefact.
   - Add a credits line saying the terrain comes from modern data.
3. **Map configuration in the public `map-data/`?** (ZI-2) **Yes.**

## Triage

**Key:**

| Code | Meaning |
| --- | --- |
| K | Change the ADR before acceptance. |
| S | Implementation specification or trial build. |
| O | Owner decision. |
| C | Cut. "Moot" means it goes away under ZI-1. |

| Stage | K | S | O | C |
| --- | --- | --- | --- | --- |
| 5 | ZO-1, ZO-3, ZO-5 (one sentence), ZO-6, ZO-8, ZO-9, IC-5 (device rule), IC-7, IC-8 | IC-2 (minimal step), IC-6 | ZO-2, ZO-4 | ZO-7, IC-1, IC-2 (`color-relief` step), IC-3, IC-4 |
| 6 | CO-1, CO-2, CO-5, CO-6, CO-8 (`core:map`), CO-9, CO-10, CO-11, CO-12 | | | CO-3, CO-4, CO-7, CO-8 ("no more detail") |
| 7, design | DE-1, DE-2, DE-6, DE-7 (one risk line and a trial criterion), DE-8 (light form), DE-9 (one sentence), DE-10, DE-12, DE-13, DE-15, DE-19 | DE-3 (smoothing in metres, one recipe per zoom), DE-7 (rest), DE-9 (handling), DE-11, DE-16 | DE-5, DE-14 | DE-3 (seam target), DE-4, DE-17, DE-18 |
| 7, structural | ST-1, ST-2 (at acceptance), ST-3, ST-4 (one sentence), ST-5, ST-6, ST-7, ST-8, ST-9, ST-10, ST-13 | | | ST-11, ST-12, ST-14 |
| 7, style | SY-1, SY-2, SY-4, SY-5, SY-6, SY-7, SY-9 (line 117 only), SY-15, SY-18 | SY-16 | SY-20 | SY-3, SY-8, SY-10 to SY-14, SY-17, SY-19 |
