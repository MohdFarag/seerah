# Stage 7: Critic review, ADR 0009 (second review)

**Target:** `docs/adr/0009-map-data-pipeline.md` at commit `a280cb8`. FeatureRange is the whole file (lines 1–250).

**Reviewers:**

- design (standard: `feature-spec-standard.md`);
- structural (same standard);
- style (its persona rules, plus the house style in `engineering.md`).

All three completed. One round; no fixes were applied, because the owner decides each change.

| Reviewer | 🔴 | 🟡 | 🟢 |
| --- | --- | --- | --- |
| design | 0 | 8 | 8 |
| structural | 0 | 2 | 10 |
| style | 0 | 5 | 11 |

## Design reviewer

Every section the standard asks for is present. The Majors fall into three groups: size and extent; the mechanics of valleys and of option (b); and integrity and compatibility.

### 🟡 DE-1. The size estimate and the extent

Lines 24, 52, 137, 154–161, 232, 236.

**The estimate is not an upper bound.** The sample is Hijaz escarpment. Other ground costs more:

- The sand seas (Rub' al Khali, Nafud, Dahna) have dunes 1–3 km apart and up to about 200 m high. Style C's roughly 270 m smoothing leaves them as dense banding, the most expensive input for WebP transparency.
- Yemen, Asir and the Hajar mountains are more rugged than the sample.

So 700 MB could be exceeded.

**The extent includes neighbouring lands.** The extent is the camera bounds plus a margin. Because the Red Sea is narrow, the margin takes in Sinai, Egypt, Sudan, Eritrea, Jordan, Iraq and the Zagros in Iran. Without relief there, those lands would draw as sea, and none of them is counted in the 3.2 million km².

**Fix:**

- Drop "upper", or measure a sand-sea tile and a Yemen tile.
- Measure the size over the full extent in trial item 1.
- Let `coverage.yaml` set a maximum zoom per zone, with the margin limited to the zooms where it can be seen.
- Recount the cells for the full extent.

### 🟡 DE-2. The size consequences are not carried into ADRs 0002, 0007 and 0008

Lines 18, 197–200, 232.

**Device storage:**

- During an update, the device holds about three times the package: the old map, the zip and the new map, which is about 2 GB at 700 MB.
- If ADR 0007 decision 7 (keep the verified package) applied to maps, the map would be stored twice permanently.

**A bundled map:**

- It likely exceeds Play's base download limit (200 MB, to be verified), so it would need install-time Play Asset Delivery.
- It would be stored twice on the device anyway: the asset copy and the extracted copy.

**Hosting:** ADR 0008 assumed "tens of MB, changes rarely". A full re-download for each approved reconstruction raises the risk of GitHub throttling.

**Fix:**

- Add ADRs 0007 and 0008 to Amends.
- State the free-space requirement.
- Batch approved edits into scheduled map versions.
- Optionally, skip downloading any file whose hash matches the installed one.
- Rebuild only the tiles near an edit.

### 🟡 DE-3. Valley extraction is not feasible as written

Lines 84, 111, 151, 236.

- **Memory.** To the reviewer's knowledge, WhiteboxTools also loads whole rasters into memory: about 30 GB per raster, with several held at once. So the rejection of pysheds ("in memory") applies to WhiteboxTools too.
- **"Complete catchments".** Taken literally, this pulls in Wadi Sirhan, which reaches into Jordan and Syria, and the Tigris–Euphrates basin.
- **Sand seas.** Filling depressions across them behaves badly.

**Fix:**

- Route flow on a coarser grid, or one drainage basin at a time.
- Replace "complete catchments" with an explicit edge rule.
- Mask out the sand seas.
- Record peak memory in the trial.

### 🟡 DE-4. Option (b)'s tile split and its dependency on missing tiles

Lines 31, 140–142, 185, 210, 237.

**A contradiction.** "Both sides come from the same rendering" conflicts with the detail file "softening its own outer edge":

- If the softening sits at the zoom where the files meet, it leaves a lighter rim.
- If it sits only at higher zooms, its schedule is fixed per whole zoom, while the app's fade is a continuous curve.

**An avoidable risk.** Write explicit transparent tiles instead of leaving tiles out. PMTiles stores identical tiles once, so this costs almost nothing, and the design no longer depends on how the engine treats missing tiles.

**Not only under (b).** As far as the reviewer knows, GDAL's tile writers skip fully transparent tiles, so missing tiles can appear under (a) and (c) as well. If the engine fell back to a parent tile, flat ground beside mountains would show blurred relief.

**Fix:**

- Fix where the edge softening is applied, and record the fade window as a tile-schema value.
- Prefer explicit transparent tiles.
- Test missing tiles under every option, including double shading while zooming across the meeting zoom.

### 🟡 DE-5. Cleaning modern features is treated as historical reconstruction

Lines 29, 119–127, 141, 188.

- **Makkah and Madinah.** Flattening the towers is an edit, so it waits for the reconstruction method. The first package therefore either waits for that unrelated decision, or ships the towers as relief at the two main event sites.
- **Badr under (b).** The modern town sits on or beside the battlefield. At zoom 15–16, smoothing defined in metres would show buildings and surface noise.

**Fix:**

- Two classes of edit: **removing modern artefacts** (evidence: modern imagery; allowed before the reconstruction method exists; listed separately in `map.json`) and **historical reconstruction**.
- A smoothing floor: inside important places, measured elevation is never sharper than at the regional maximum zoom, and only edits add finer shape.

### 🟡 DE-6. Disclosure has no gate, and AGENTS.md is not brought into line

Lines 20, 39, 117, 180, 234; `AGENTS.md`; `open-decisions.md`.

**AGENTS.md:**

- AGENTS.md's non-negotiable rule says "approximation is labelled".
- The owner's choice of no label on the map is the higher source and wins.
- But AGENTS.md requires the lower source to be fixed in the same change, and the ADR doesn't list it under Amends.

**No gate:** a closed-beta map package could carry reconstructions while the app has no disclosure yet.

**A simpler way to meet the owner's choice:** the content model's automatic certainty notes could appear in the panel whenever the view frames one of the reconstructed areas in `map.json`.

**Fix:**

- Amend AGENTS.md to cover reconstructed terrain disclosed outside the map.
- Add a rule: no map package with reconstructions goes beyond `test` until the app has the disclosure.

### 🟡 DE-7. The map and content packages can fall out of step

Lines 99, 152, 162, 165, 173, 180.

**No dependency between packages.** Reconstructions ship in the map package, but the places that sit on them, and their sources, ship in the content package. The two install independently, with no "content N needs map M". A user can see the Badr positions on a bank their installed map doesn't have.

**`map.json` cannot be built as described.** It is meant to carry sources, but the sources live in `content/`, and the map tool reads only `map/`.

**Fix:**

- Make `map.json` carry full citation text.
- Build from `map/` and `content/` at one commit, recorded once.
- Add a dependency field (for example `requires_map >= M` in the content manifest) checked by the app and by ADR 0008's publishing rule. Or state the mismatch as an accepted risk.

### 🟡 DE-8. A tile-schema change leaves an offline user without a map

Lines 176–179.

Google Play updates apps in the background. If an app version that drops the old schema reaches an offline user, they have no map. That breaks the requirement that the map works offline after download.

**Fix:**

- The app reads the current schema and the one before it.
- A matching map is listed in `stable` before any app build that needs it starts rolling out.

### 🟢 Design nits

- **DE-9.** "The largest supported screen" has no upper limit. Let the app derive its minimum zoom from the viewport instead, and check the margin against a stated reference screen.
- **DE-10.** The manifest gains two commits and a container digest, but ADR 0007's manifest has a single `source_commit`. Add this to Amends, and name the two repositories.
- **DE-11.** Option (c) ships a different kind of file that decision 8 never defines. Its crisp band edges are also a different look from style C's soft bands, so "the same look" needs a criterion.
- **DE-12.** There is "remove land" but no "add land" or "restore coast", and a "raise" edit over water has no effect. Add the operation or put it out of scope.
- **DE-13.** Archiving the inputs means tens of GB, with no stated location or cost; R2's free tier is 10 GB.
- **DE-14.** "Rejected by the owner" has no link, and line 55 states no trade-off.
- **DE-15.** THIRD_PARTY_NOTICES.md needs the Water Body Mask and the new tools.
- **DE-16.** "Only approved edits are applied" (unapproved ones skipped) differs from ADR 0007's "the build refuses" an unapproved item. `coverage.yaml` and the important places carry no review state.

## Structural reviewer

The ADR matches the house structure of ADRs 0007 and 0008. Headings, tables and the code fence all pass.

### 🟡 Structural majors

- **ST-1.** The "tiers or streaming" row is rejected only "by the owner for v1", with no technical reason. Tie it to offline first.
- **ST-2.** The risks have no mitigations and there is no security entry:
  - the missing-tile risk doesn't point to trial item 4;
  - the size risk doesn't point to trial item 1;
  - the size and re-download limit sits outside the Risks list;
  - the security measures (pinned sources, the pinned container, the signed manifest, and the licence for reconstructions published in a public package) are not gathered into a risk entry.

### 🟢 Structural nits

- **ST-3.** The status line doesn't link where the owner's decisions are recorded.
- **ST-4.** The review links are inconsistent (one report, then a folder), and the second review isn't referenced.
- **ST-5.** The detail table bolds (b), but the choice is to try (a) and (c) first. The last row has no label, and the labels are written three different ways. Row (a)'s "Against" holds a point in its favour.
- **ST-6.** Lava fields is the only Options subsection without a table.
- **ST-7.** Some tool alternatives (rio-tiler, the Bash script, Make, system packages) have no reason for rejection.
- **ST-8.** Amends misses three things:
  - ADRs 0007 and 0008 (the size assumption);
  - ADR 0007 decision 3 ("not a second tool");
  - AGENTS.md's description of `map-data/`.

  The ADR also both amends ADR 0006 and edits its text; pick one way.
- **ST-9.** The credits and the list of reconstructed areas have no defined home.
- **ST-10.** Decision 12 has no bold lead. "Not in scope" leaves out the bounds and zoom values.
- **ST-11.** The claims that decide rejections have no links: FABDEM's licence, ALOS, NASADEM, Planetiler, rio-tiler, GRASS, pysheds and `git filter-repo`. References also lacks the GLO-30 licence PDF that ADR 0004 links.
- **ST-12.** The spike's accepted "diagrams for the few places that need detail" is not mentioned, and the ADR departs from it without saying so.

## Style reviewer

Mostly plain, in the present tense, with no marketing words. The problems are claims that go further than the evidence, internal contradictions, and trial criteria that can't be measured.

### 🟡 Style majors

- **SY-1.** Decision 1's "no modern features" contradicts line 29, the risk at line 235 and trial item 8. Narrow it to "no modern features drawn as their own layer".
- **SY-2.** "The existing signing tool" is false; no such tool exists yet.
- **SY-3.** "Both are downloaded" (the amendment to ADR 0003) settles the open question of bundling the map with the app. Say "read at run time, whether bundled or downloaded".
- **SY-4.** Lines 50 and 57 assume every important place has reconstruction edits, which the ADR never requires. Elsewhere, enlarged 30 m data has no real detail. "Cost little" conflicts with the unknown performance of (c) on the Oppo A55. There is also a grammar slip ("edits, which is").
- **SY-5.** The trial criteria can't be measured: "clear terrain at the scale of a site like Badr", "clean lines" and "read in style C". Say what is measured, or who judges and on what device.

### 🟢 Style nits

- **SY-6.** The rule that the style ships in the app comes from ADR 0007, not ADR 0002.
- **SY-7.** The 0.6 alpha comes from `relief_style.py`, not from the spike summary.
- **SY-8.** "Identical" is absolute, and the clause hangs.
- **SY-9.** The size estimate is hedged twice in one place and not at all at line 52, and the sentence is long.
- **SY-10.** A reported bug is stated as fact.
- **SY-11.** "Above all" is ambiguous.
- **SY-12.** `color-relief` is called an "exception" to the hillshade methods, but it isn't one of them.
- **SY-13.** "Angular from regional zoom on" goes further than the spike found.
- **SY-14.** Points sit in the wrong column (lines 49 and 99).
- **SY-15.** "Seamless boundary" and "the fade stays correct" are untested claims; line 103 is filler; line 163 is a long list.
- **SY-16.** Only ADR 0008 assumed "tens of MB", not ADR 0007. "Honesty" is a loaded word.
