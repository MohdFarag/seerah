# Stage 7: Critic review, map trial specification

**Target:** `docs/design/map-trial-build.md`, FeatureRange lines 1–355 (Overview through Future Improvements). Reviewers could read the DetailedRange (356–736) for reference, but no finding below is about it alone; stage 9 reviews it.

**Standards:** `egm-design` and `egm-structural` applied `feature-spec-standard.md` (EGM v0.1.1). `egm-style` applied its persona's own rules.

**Reviewers:**

| Reviewer | Status | Blocker | Major | Nit |
| --- | --- | --- | --- | --- |
| `egm-design` | completed | 0 | 10 | 4 |
| `egm-structural` | completed | 1 | 5 | 4 |
| `egm-style` | completed | 0 | 6 | 10 |
| **Total** | | **1** | **21** | **18** |

All three reviewers read the target, every GoldfishContext file they needed, and the stage 5 and stage 6 reports. They left out issues those reports already raised (ZO-n, IC-n, CO-n) unless they had something to add, and they cite those IDs where they build on them. The owner's decisions dated 2026-11-14 are treated as final: findings report the risks those decisions create and do not reopen them. Line numbers refer to the target file.

The synthesizer checked two claims against the files. The target has no occurrence of "risk", "mitigat" or "2 GiB" (ST-1, DE-1). ADR 0009 line 25 calls the size extrapolation an estimate, not a bound, and gives no measurement (SY-6).

## Overlaps between reviewers

Each overlapping finding stays in its reviewer's section so that each section reads on its own. The table links them.

| Issue | Design | Structural | Style | Earlier |
| --- | --- | --- | --- | --- |
| No Risks and Mitigations section; the 2 GiB limit and the costs M2 accepted are not recorded | DE-1 | ST-1 (Blocker) | | ZO-9, IC-2 |
| No limits on build cost; step 3's "stop if the numbers are wrong" cannot be checked | DE-9 | ST-2 | SY-4 | ZO-9, IC-5 |
| Trial items have no pass rules; G9's check is deferred and so cannot be run in this work | | ST-2 | SY-5 | CO-1, CO-6 |
| The failure criteria at line 39 lose parts of the owner's wording | DE-9 (memory, disk, time) | ST-2 | SY-3 | |
| Rejection reasons give only "Rejected by author"; A8 gives no reason | | ST-3 | SY-9 | |
| "Facts this plan rests on" give no sources; "87 are all sea" is not proven | | ST-6 | SY-7 | |
| The security requirement overstates what the build protects | DE-10 | ST-1 (security points) | SY-2 | |
| "The coast… cannot disagree" with the relief; the base layer has no check | DE-5 | | SY-1 | IC-3, CO-3 |
| Open Questions: checks without IDs, and checks from the brief that were dropped (GHCR public image) | DE-14 | ST-5 | SY-14 | ZO-17, IC-14 |
| Additional Resources and Future Improvements are H3s under Non-Functional Requirements | | ST-7 | SY-14 | |

**Severity of the combined Risks issue.** ST-1 rates the missing section a Blocker, because the standard requires it. DE-1 rates it Major and lists its content. This report keeps ST-1 as the Blocker. One revision fixes both: add the section, structured as ST-1 says, and seed it with DE-1's (a)–(c) and the risks in DE-2 to DE-10.

---

## Design reviewer (`egm-design`)

**Verdict:** Needs revision before implementation. There are no Blockers. Ten Major findings. Three of them, DE-2, DE-4 and DE-5, are correctness gaps that neither the CI example nor the planned tests can catch: the example uses one present cell and tests relief only, and the spec states no zoom convention. If these are not fixed before the step 1 checkpoint, they will first show in the step 4 full-box build.

### DE-1. No Risks and Limits section, so several risks that the owner's decisions create go unrecorded. 🟡 Major

- **Lines:** 212–338, especially 252–254 and 334–338.
- **Problem:** The standard requires the spec to name its "failure modes, security implications, scalability limits, and how they will be mitigated" (feature-spec-standard.md lines 23–27, 38–39). Non-Functional Requirements (334–338) covers only security, measured performance and removal. Lines 1–355 do not mention three risks:
  - **(a) The 2 GiB limit.** ADR 0009 decision 8 fails a build if any file is 2 GiB or larger (ADR 0009 line 172). The brief warns that the zoom-12 file could come close to that limit (concepts_brief.md line 111). The spec does not say whether a trial build warns at that size, or how item 1 is decided if the zoom-12 file is over the limit. It also does not cover copying files of several GB onto three phones.
  - **(b) The costs M2 accepts.** The brief's M1/M2 table (brief lines 53–62) lists them: two resume layers, a `--resume` that only checks that a file exists, about 100,000 temporary files, and a separate path for option (b). A6 (252–254) lists only M1's cons. IC-2 shows that one of these costs is a real defect.
  - **(c) Loss of the verified downloads.** The lock trusts the first download's hashes (118). The only verified copy of the sources is the `seerah-map-cache` named volume (112), and `docker system prune --volumes` deletes it. If Copernicus then republishes a cell, the trial's inputs cannot be rebuilt, and `lock --replace` changes every fingerprint.
- **Fix:** Add a "Risks and limits" section after Alternatives. For each risk, give its trigger, the owner's failure criterion it touches, the mitigation or an explicit acceptance, and the step that checks it. Start with (a)–(c) and the risks in DE-2 to DE-10. See ST-1 for the structure.

### DE-2. Cells missing from the bucket become land, and their no-data value can be blurred into the coast. 🟡 Major

- **Lines:** 69, 75, 135–139, 196.
- **Problem:**
  - Stage 1 reads "a VRT of the cached cells" and treats "everything else" except ocean class 1 as land (137). The VRT returns its fill value, 0 unless no-data is set, wherever a cell does not exist. Class 0 means "no water" (69). Each of the 87 absent cells (75) therefore becomes a 1°×1° square of land in the Red Sea, the Gulf or the Arabian Sea, with relief and coast drawn around it.
  - If a no-data sentinel reaches the Gaussian blur (σ 7 px, a reach of about 21 px), it is pulled into coastal pixels of the neighbouring present cells. That leaves dark streaks along cell edges, which falls under the owner's "visible seams" criterion.
  - Nothing tests this case. The spike's area had no absent cells, and the CI example sits inside one present cell (196).
- **Fix:**
  - In stage 1, state that pixels with no source cell are sea in the mask, with the elevation that present cells carry over the ocean. Check that value on a sample cell along with the class check at line 69.
  - Set no-data and fill values explicitly in the VRT and the warp.
  - Add an `absent: true` neighbour to the example lock, so the seam and mask tests cross an absent-cell edge.
  - Add a check that no land pixel lies inside an absent cell.

### DE-3. Peak memory read from the control group will report the cap, not what the build uses. 🟡 Major

- **Lines:** 35, 46, 133, 167. Related: IC-11.
- **Problem:**
  - In cgroup v2, `memory.peak` includes page cache charged to the group. A build that reads about 1,000 source files and writes tens of GB will fill its cache up to the `--memory` cap, so the reported peak will be about the cap on every run.
  - Item 6's peak memory and the per-worker allowance "measured in step 1" (133) would then both be wrong. An allowance that is too high means fewer workers and a longer build.
  - G2's check, "never triggers the system's out-of-memory killer outside the container" (46), is not guaranteed by a cap. A cap is a ceiling, not a reservation. With about 2 GB free while the desktop runs (35), the host can still kill a desktop process. The spec does not say how the budget relates to free memory, or how G2 is observed.
- **Fix:**
  - Sample `memory.stat` (`anon`, `file_dirty`, `file_writeback`) about once a second, report the peak `anon`, and record `memory.events` (`high`, `max`, `oom_kill`).
  - Measure the per-worker allowance as one worker's peak RSS or PSS on a known block.
  - Derive the budget from measured free memory minus a margin.
  - Check G2 by scanning the kernel log for out-of-memory lines during the run, and record the result in `report.json`.

### DE-4. The spec never states its zoom convention or the relief source's `tileSize`, and line 154's figures do not match MapLibre. 🟡 Major

- **Lines:** 52, 67, 154, 163, 165, 178, 190.
- **Problem:**
  - **Two zoom scales.** Build zooms use 256-pixel XYZ tiles (148). MapLibre's camera zoom uses a world 512 dp wide at zoom 0.
  - **`tileSize` decides which camera zoom shows zoom-12 tiles.** A raster source with `tileSize` 256 shows zoom-12 tiles at full resolution at camera zoom 11. With the default of 512, it shows them at camera zoom 12.
  - **Nothing pins either down.** The spec never gives the relief source's `tileSize`. It never says whether these values are camera zooms or tile zooms: "11 or 12", the fade zooms (the spike's 12.5–14.5 is a style zoom, docs/spike/findings.md line 144), the camera path's "zoom 5 to 15" (190), and Q1's minimum zoom (67).
  - **Line 154's figures.** They only work out as 256-pixel tile zooms on a screen about 2,800 physical pixels wide. In MapLibre's convention the span is about 3 to 4 times smaller on these devices, so a 1° margin is left behind only up to about camera zoom 9, not zoom 10.
  - **What depends on this:** G8 (match build 11), the placement of the fades and of option (a)'s later fade, and the formula behind coverage check 2 (163).
- **Fix:**
  - Add a short "Zoom conventions" paragraph. Build zooms are 256-pixel tile zooms. Style, fade, camera-limit and camera-path zooms are MapLibre camera zooms. The relief source's `tileSize` is build 11's value, read from the spike style; state the resulting offset.
  - Recompute line 154 from each device's width in dp, and use that width in the coverage calculation.
  - Add a check of `tileSize` and the look to step 1's device checks.

### DE-5. The base layer has no automated check and repeats two of the spike's failures. 🟡 Major

- **Lines:** 47, 133, 148, 152, 156–163. Related: IC-3, CO-3, SY-1.
- **Problem:**
  - **(1) Dropped polygons.** In the spike, tippecanoe dropped the largest polygon when it was tiled whole, and whole rectangles of land showed as sea (findings line 90). The fix had two parts: cut the finer scales into 10° cells, and build one tileset per scale (build_base.sh lines 19–20, 36–37; findings line 93). The spec carries over only the second part, and only for Natural Earth (156). Polygonising the Water Body Mask over the trial box gives one very large land polygon, which is the case that failed in the spike.
  - **(2) No automated check.** G3's completeness check covers relief tiles only (148).
  - **(3) Where mask land meets Natural Earth land.** Across Jordan, Iraq, Iran and Ethiopia, the relief's edge follows zoom-9 block boundaries (133), while Natural Earth is clipped to "outside the tiled extent", which is never defined as a geometry. Any mismatch leaves a gap in sea colour or a faint seam. Item 10 checks only coasts that cross the edge (158).
  - **(4) Empty vector tiles.** tippecanoe writes no tile where there are no features. G3 still promises "every tile… at every zoom" (47) without limiting the promise to relief (ADR 0009 decision 5).
- **Fix:**
  - Polygonise per block on the existing block grid, with a small overlap. This also bounds IC-3's memory concern.
  - Clip Natural Earth so that it overlaps the extent by a few pixels.
  - Add an automated base check, either the spike's comparison of each parent tile against its children (findings line 93) or a per-tile comparison of land fraction against the mask.
  - Add land-to-land joins to item 10.
  - Limit G3 to relief and record why missing sea-only vector tiles are safe, or write empty tiles.

### DE-6. The spec does not say which darkness option (c) stores, and judges (c) only at the test edit. 🟡 Major

- **Lines:** 26, 143, 146, 165, 207. Context: ADR 0009 lines 53, 141.
- **Problem:**
  - Both encoders take the style function's output after banding and band blur (143, 146). ADR 0009 decision 4 describes (c) as "one continuous darkness value coloured by the app". Banded values have mostly four levels and compress far better than a continuous field. Item 3's size verdict therefore depends on a choice the spec never makes, and with banded values the app could not change the number of bands without a rebuild.
  - Under decision 4, the chosen encoding is the `relief` source at every zoom. At wide zooms the app's stepped ramp re-thresholds the averaged-down values, which gives hard, aliased band edges where style C's edges are soft. Step 2 (207) judges (c) only at the test edit.
- **Fix:**
  - State which darkness the value encoder stores. ADR 0009 says continuous, before banding; if banded values are chosen, record why.
  - Make banding a separate pure step that only the painted path uses.
  - Add to step 2 a side-by-side comparison of (c) with build 11 at regional zooms 5–11.

### DE-7. Option (b) (item 4) has no plan, and line 350's claim conflicts with the fixed zoom-12 grid. 🟡 Major

- **Lines:** 45, 59, 133, 204–210, 350. Context: ADR 0009 line 197; brief line 60.
- **Problem:**
  - G1 promises all eleven items, and item 4 must be judged if (a) and (c) both fail. The order of work has no step for it.
  - Line 350 says (b) "reuses stages 1–4". Stages 1–2, however, are fixed to the zoom-12 block grid (133), while (b) needs zooms 13–16. GDAL's tiler tiles the whole extent, not only the tiles that approved reconstruction edits touch (ADR 0009 rule 4).
  - If step 2 rejects both (a) and (c), the trial stops with no plan.
- **Fix:**
  - Add a conditional step after step 2: "If (a) and (c) both fail, stop and write an addendum that specifies (b) before item 4 is judged."
  - Mark item 4 as conditional in G1.
  - Correct line 350: the zoom of the block grid becomes a parameter, and tiling is limited to windows around each edit, for example with `--min-zoom 13`.

### DE-8. The median across runs can pass a device that misses ADR 0002's worst-case target. 🟡 Major

- **Lines:** 50, 189–190. Context: ADR 0002 lines 21–27; docs/spike/measurements.md line 8. Related: CO-6, IC-8.
- **Problem:**
  - ADR 0002's frame targets are worst cases: no 30-frame stretch below 20 fps on the low-end phone, and a lowest rate of at least 45 fps on the others. The overlay shows "each run and the median" (190), so a median of three "lowest" readings can pass a device whose worst run falls below 20 fps.
  - Run 1 loads tiles cold, and the median discards it, though a cold first pan is what users see. Repeating many runs on the Oppo A55 also builds up thermal throttling.
- **Fix:**
  - For each device and variant, report "lowest" as the minimum over all runs, and report the first, cold run separately. Use the median only for typical frame rate and open time.
  - Fix the run protocol: state whether each run is cold or warm, allow a fixed cool-down between runs, and show the device temperature on the overlay.

### DE-9. No limits are set for build cost, so G2 and step 3's stop rule cannot fail. 🟡 Major

- **Lines:** 39, 46, 112, 169, 208. Related: ZO-9, IC-5, SY-4, ST-2.
- **Problem:**
  - The owner's criteria fail the trial on "too much memory or disk, unreasonable build time" (brief line 22). G2 only requires these to be measured (46), and step 3 stops "if the numbers are wrong" (208).
  - The cache and work area sit in Docker named volumes on Docker's root filesystem (112, 169), which is usually the system disk. The spec gives no free-space figure.
  - ZO-9 and IC-5 ask for a free-space check. The limit that check, and step 3's extrapolation, should compare against is still missing.
- **Fix:**
  - Before step 3, the owner records these limits in the spec or the trial profile:
    - a memory budget;
    - a temporary-disk limit: free space on the volume minus a margin, checked before the build;
    - a wall-time limit for one full build;
    - every output file under 2 GiB.
  - Step 3 extrapolates each figure, using the Yemen window as the worst case, and stops if any projection is over its limit.
  - The run report records the volume's filesystem and its free space.

### DE-10. The build container can read the whole private repository and has network access, and tippecanoe is pinned by a tag that can move. 🟡 Major

- **Lines:** 103–112, 336. Related: SY-2.
- **Problem:**
  - The container runs third-party code (tippecanoe, go-pmtiles, WhiteboxTools and the pip packages) with the whole private checkout mounted. That checkout holds all-rights-reserved content and its git history. A read-only mount (336) stops writes, not reading and sending.
  - Only `lock` and `fetch` need the network.
  - tippecanoe is pinned by release tag (106). Tags can be moved, unlike the commit pins in ADR 0004 line 58 and the SHA-256 checks at line 107.
- **Fix:**
  - Run every command except `lock` and `fetch` with `--network none`. This also proves the build makes no hidden downloads, for example through GDAL's `/vsicurl/` or PROJ's network grids.
  - Mount only `map/trial/` (or `map/`) of the private checkout.
  - Run the clean-checkout and commit checks in the host launcher, and pass the commits into the container as arguments.
  - Pin tippecanoe by commit.
  - State all of this in the security requirement.

### DE-11. Importing doubles the storage each variant takes on the device, and the spec considers no alternative. 🟢 Nit

- **Lines:** 61, 187.
- **Problem:** The owner copies files to shared storage, and the app then copies them into `noBackupFilesDir`, so every variant takes twice its size on each phone. An alternative is not considered: the app-specific external folder (`getExternalFilesDir`). It gives a real file path, `adb push` can write to it, and it needs no picker, copy or free-space code.
- **Fix:** In step 1, check whether `adb push` to `Android/data/app.seerah.benchmark/files/` works on all three devices. If it does, drop the importer. If not, record why under "Viewing the trial", and tell the owner to delete the shared copy after import.

### DE-12. The spec does not say who marks the test edits approved. 🟢 Nit

- **Lines:** 200. Context: AGENTS.md ("Only the owner marks content `approved`"). Related: IC-12, CO-16.
- **Problem:** The spec has the test edits committed with `review: approved`. As written, whoever writes the file during implementation sets that value.
- **Fix:** The implementer commits the edits as `draft`, and the owner sets `approved` at the step 1 checkpoint. Alternatively, accept edits without approval under `purpose: trial` (IC-12's option).

### DE-13. `:core:map`, which stays in v1, has a per-frame callback in its public interface. 🟢 Nit

- **Lines:** 14, 180. Adds to ZO-4.
- **Problem:** `SeerahMap`'s `onEvent` reports "each rendered frame". That is measurement plumbing in a module that stays in v1.
- **Fix:** Expose frame timing through a separate, opt-in diagnostics listener that the trial passes in, under ZO-4's opt-in annotation. Keep `onEvent` for the map events v1 needs.

### DE-14. Nothing checks that the published image is public. 🟢 Nit

- **Lines:** 110, 112. Adds to ZO-3 and IC-14; see also ST-5.
- **Problem:** The GitHub Container Registry can create a newly pushed package as private, in which case every pull needs a login. The brief listed this check (brief line 181), but Open Questions does not include it.
- **Fix:** Add the check to "To verify in step 1". Set the image's source label in the publish job so the package is linked to the repository, and confirm the package is public, or document the login the launcher needs.

---

## Structural reviewer (`egm-structural`)

**Verdict:** Needs revision. One Blocker: the section the standard requires for risks and mitigations is missing. The Major findings are traceability gaps: the trial items have no pass rules, several rejection reasons only name the author, the ADR amendments are hidden inside the build tool section, the verification items have no IDs or exits, and the stated facts have no sources.

Heading map of lines 1–355: Overview (9), The Problem (21), Goals (41), Non-Goals (55), Open Questions (65), Technical Plan (Architecture) (71, with 7 H3s), Alternatives Considered (212, with 4 H3 groups and 18 H4 entries), Non-Functional Requirements (334), then H3 Additional Resources (342) and H3 Future Improvements (348). Measured against the standard, Problem, Goals and Non-Goals, Architecture and Alternatives are present. Risks and Mitigations is missing.

### ST-1. There is no Risks and Mitigations section. 🔴 Blocker

- **Lines:** 21–39, 50, 65–69, 276–280, 334–338.
- **Problem:** The standard requires a "Risks & Limits" treatment: failure modes, security implications, scalability limits and their mitigations. No heading in the spec covers this, and the words "risk" and "mitigat" do not occur in the file. The risks it does mention are scattered: the failure criteria (39), the no-labels caveat in G6 (50), unverified tool behaviour (68–69), and the edit-width threshold, which appears only as a "Pro" of the rejected A10 (278). The spec leaves out risks that the brief and the ADRs raise:
  - the 2 GiB file limit (ADR 0009 line 172; brief line 111), which directly constrains the choice between zoom 11 and 12;
  - about 100,000 temporary tile files plus the styled blocks (brief lines 57, 76; see ZO-9);
  - M2's two resume layers and half-written tiles (brief line 58; see IC-2);
  - shapes narrower than about 500 m vanish because edits come before smoothing, which can make option (a) fail by construction (brief lines 94, 162);
  - blur kept in pixels varies by about 13% in ground spread between 12° N and 32° N (brief line 165; see IC-6).
- **Fix:** Add `## Risks and Mitigations` between Alternatives Considered and Non-Functional Requirements, as a table with these columns: Risk, Failure mode or trigger, How it is detected, Mitigation or accepted cost, and Step. Seed it with the items above, M2's accepted costs relative to M1 (from the brief's table), and the security points: the read-only private mount (see DE-10), the bot pull request's permissions (ZO-3) and SELinux relabelling (ZO-17). This records the risks the owner's decisions create without reopening those decisions. See DE-1 for more content.

### ST-2. The trial items have no pass rules, and the goals' checks do not cover them. 🟡 Major

- **Lines:** 39, 41–53, 202–210. Extends CO-1 and CO-6. See DE-9 and SY-4, SY-5.
- **Problem:**
  - The brief commits the spec to "every parameter and a pass rule for each trial item" (brief line 126). Instead, the goals check the tool (G2–G5, G7, G9) or leave the item to the owner's sign-off (G1, G8).
  - No item has a threshold. Item 1 has no size limit, not even 2 GiB. Item 6 has no bound on time or memory. Item 11 cites ADR 0002 without its numbers.
  - G9's check depends on a packaging step that is out of scope.
  - The failure criteria (39) are not mapped to any goal.
- **Fix:**
  - Add an H2, "Trial items and pass rules", with a table. Columns: item number, decision 13's wording, step, build or variant, evidence, pass rule (numeric where possible, such as ADR 0002's values per device class and the 2 GiB limit), and what happens on failure.
  - In the same section, list the failure criteria as general fail rules, and move "Order of work" there.
  - Restate G9's check as something verifiable now: the marker is present in the output metadata and in the schema.

### ST-3. Several rejection reasons only name the author, and two decisions have no record outside this spec. 🟡 Major

- **Lines:** 214, 224–228, 250–254, 262–266, 282–286, 302–318. See SY-9.
- **Problem:** The standard requires rejection reasons that state concrete trade-offs, and does not allow a rejection to be attributed to a maintainer without a link to an issue, discussion or pull request.
  - A8's reason is only "Rejected by author."
  - A2, A6, A11, A14, A15 and A16 say only "in favour of X", which leaves the deciding trade-off unstated. A11 never says why the correction beats the approved uncorrected look.
  - Line 214 says each alternative was weighed "in the concepts brief or while drafting", but not which.
  - A15 and A16 amend the accepted ADR 0009, yet they are not among the brief's Alternatives Weighed (brief lines 134–151). Their only record is this spec, and ADR 0009's status line links back to it, so the reference is circular.
- **Fix:** This does not reopen any decision.
  - Rewrite each reason as one clause that states the deciding trade-off. Example for A6: "M2 hands tiling, averaging down, parallel work and resume to GDAL; accepted cost: a second resume layer and a large temporary tile folder (see Risks)."
  - Mark each entry "brief" or "drafting".
  - Link the record of each decision, and give A15 and A16 a pull request or issue link, as ADR 0009 does with pull request 14.

### ST-4. The changes this spec makes to accepted ADRs are hidden in the build tool section. 🟡 Major

- **Lines:** 11–19, 154–163, 184. Complements ZO-16.
- **Problem:**
  - The spec amends ADR 0009 decisions 3, 6 and 7 (Natural Earth beyond the extent, a split coverage check). It also brings forward ADR 0004's benchmark build type, which ADR 0004 line 36 ties to `:benchmarks`.
  - The ADR 0009 amendments appear only as bold run-in paragraphs inside "The build tool" (154, 160). The ADR 0004 change is not stated anywhere in lines 1–355.
  - The Overview mentions none of these changes, though AGENTS.md ranks accepted ADRs above other documents.
- **Fix:** Add a short "Decisions and ADR changes recorded here" subsection under Overview. List each change with its date, the owner marking and links: ADR 0009 decisions 3, 6 and 7, and the ADR 0004 build types. Point lines 154 and 160 to it.

### ST-5. The verification items in Open Questions have no IDs or exits, and they use terms before they are introduced. 🟡 Major

- **Lines:** 65–69. Extends ZO-17 and IC-14. See DE-14 and SY-14.
- **Problem:**
  - Only Q1 has an ID. Lines 68 and 69 each bundle three or four checks into one bullet.
  - Only the `maxzoom` check states a fallback, and no check says where its result is recorded, though the Verification Plan (729) depends on these results being "checked and recorded".
  - The section comes before the architecture and uses "step 1", `color-relief`, `--resume` and the Water Body Mask classes before they are introduced.
  - Two checks from the brief were dropped (brief lines 180–181), in addition to those ZO-17 and IC-14 found: the GitHub Container Registry terms for public images, and whether GDAL's average resampling weights pixels by transparency.
- **Fix:**
  - Split the bullets into Q2, Q3 and so on. Give each one the question, the step that answers it, the fallback if it fails, and where the result is recorded (`docs/map-trial/step-1.md`).
  - Move the section after Technical Plan or after Alternatives.
  - Add the two dropped checks.

### ST-6. "Facts this plan rests on" gives no sources. 🟡 Major

- **Lines:** 73–78, 342–346. See SY-7.
- **Problem:** Design choices rest on four facts "checked 2026-11-14" that cite nothing: the count of 26,450 cells, GDAL 3.13.3's flags, `disk_to_pmtiles` sorting by tile ID and matching tiles by `hash()` (the basis of G4's byte-identical claim), and `color-relief` being available since MapLibre 13.0.0. Additional Resources lists internal documents only. ADR 0009's References already link most of these sources.
- **Fix:** Add a source to each fact: a URL, a release tag, or the source file and commit inspected. Add an "External references" bullet to Additional Resources.

### ST-7. Two closing sections sit under the wrong parent, and section separators are used inconsistently. 🟢 Nit

- **Lines:** 7, 332, 340, 342, 348, 354. See SY-14.
- **Problem:** "Additional Resources" and "Future Improvements" are H3s after the H2 "Non-Functional Requirements", so an outline shows them as subsections of it. `---` rules appear only around the last sections.
- **Fix:** Make both H2s. Use `---` before every H2, or drop the rules at 332, 340 and 354.

### ST-8. The main mechanisms have bold run-in labels instead of headings. 🟢 Nit

- **Lines:** 103–171, 175–192. Supports ZO-13.
- **Problem:** About 17 mechanisms sit under one H3 as bold labels: where it runs, commands, inputs, the fingerprint, blocks, stages 1–4, the base layer, land beyond the extent, coverage checks, variants, the run report, temporary files and valleys. Each of the 18 alternatives, by contrast, has its own H4. The mechanisms cannot be linked to or shown in an outline.
- **Fix:** Make each mechanism an H4.

### ST-9. The Components diagram does not match the text. 🟢 Nit

- **Lines:** 82–97.
- **Problem:** The diagram leaves out `probe-valleys`, `relief-value.pmtiles` (150) and the Natural Earth input (156). It lists commands (`lock`, `fetch`, `report`, `clean`) as siblings of stages, and it shows `sources.lock` as read-only, though `lock` writes it (118).
- **Fix:** Separate commands from stages, add the missing input, output and command, and show `lock` writing `sources.lock`.

### ST-10. Some decision references name no ADR, and some documents are not linked at first mention. 🟢 Nit

- **Lines:** 31, 36–37, 47, 120, 137, 221. Related to CO-18.
- **Problem:** "decision 5" (47), "decision 8" (120), and "decision 1" and "decision 6" (137) name no ADR. ADR 0002 (31), ADR 0007 (37), AGENTS.md (36, 221) and "spike build 11" (31) are not linked where they first appear.
- **Fix:** Write "ADR 0009 decision N" every time, and link each document at its first mention.

---

## Style reviewer (`egm-style`)

**Verdict:** Needs revision. There are no Blockers. The six Major findings are sentences that claim more than the spec can support, a paraphrase of the owner's failure criteria that changes their meaning, a stop rule that cannot be checked, and goal checks that do not match their own introduction.

### SY-1. Several sentences state certainties that the spec contradicts elsewhere. 🟡 Major

- **Lines:** 69, 99, 146, 152, 189.
- **Problem and fix:**
  - **Line 146.** The spec says "so averaging can never darken edges", but line 69 still lists as unverified whether GDAL's WebP driver keeps the ink colour under transparent pixels. Rewrite as: "Because every pixel carries the ink colour, averaging changes only alpha, provided the WebP encoder keeps the colour under transparent pixels (checked in step 1)." IC-9 asks for a test; this finding is about the wording.
  - **Line 152.** The spec says "so they cannot disagree", but the same line generalises and polygonises the coast, and it meets Natural Earth at the extent's edge (158; see DE-5 and IC-3). Rewrite as: "Coast and relief start from the same mask. Generalising per band can move the coast slightly; step 4 compares them on the devices."
  - **Line 99.** The spec says "No app code knows how the files were made…", but the app reads the encoding and the fingerprint (188), and the value encoder exists only for MapLibre's `color-relief` (146). Rewrite as: "The app depends only on the PMTiles format and the metadata fields listed in stage 4. The build depends only on the tile encodings MapLibre reads."
  - **Line 69.** The spec says "(the build's decode check handles it either way)", which IC-2 disproves for ancestor tiles. Rewrite as: "…how `gdal raster tile --resume` treats a half-written tile, which decides whether the completeness check must also rebuild that tile's lower-zoom ancestors."
  - **Line 189.** The spec says "so a screenshot identifies everything". Rewrite as: "so each screenshot shows the readings, the variant and its build."

### SY-2. The security requirement overstates what the downloads verify. 🟡 Major

- **Lines:** 336, against 118. See DE-10, CO-3.
- **Problem:** "The build tool downloads only addresses pinned in the lock and verifies every file's SHA-256." In fact, `lock` downloads cells that are not yet pinned and records their first hashes as trusted. Natural Earth's download is covered by neither command.
- **Fix:** "`fetch` downloads only addresses listed in `sources.lock` and checks each file's size and SHA-256 against it. `lock` downloads each present cell once to record its first hash (trust on first use; Copernicus publishes no checksums)." Then say how Natural Earth is pinned.

### SY-3. The restated failure criteria change the owner's wording. 🟡 Major

- **Lines:** 39, against concepts_brief.md lines 18–24.
- **Problem:** Line 39 drops "rendering artefacts" and turns "noticeably bad terrain" into "bad terrain". It merges "too much memory or disk, unreasonable build time, or a process that cannot be repeated reliably" into "unmeasured or impractical build cost". The code-quality criterion loses "(major priority)", "duplicating what mature tools do", "poor separation of concerns" and "heavy maintenance". These criteria are the owner's final pass rules, and a paraphrase that omits parts of them changes what reviewers check.
- **Fix:** Copy the brief's five criteria word for word, as a bulleted list marked "(owner, 2026-11-14; concepts brief)".

### SY-4. Step 3's stop condition cannot be checked. 🟡 Major

- **Lines:** 208. See DE-9.
- **Problem:** "stop if the numbers are wrong" names no measure and no limit, yet it is the only gate before the full build.
- **Fix:** "Stop and report to the owner if the extrapolated full build exceeds the profile's memory budget or the free space on the `seerah-map-work` volume, or if its time exceeds [the owner's limit]. The owner decides from the step 3 report whether step 4 runs." If the owner has set no time limit, write "the owner judges the time from the report".

### SY-5. The goal checks do not match the sentence that introduces them, and G9 claims a result this work does not deliver. 🟡 Major

- **Lines:** 43, 49, 51, 53. Adds to ZO-6. See ST-2.
- **Problem:**
  - Line 43 says every goal is checked by "a test, a measurement in the run report, or the owner's judgement on a device". G5 and G7 are checked by code review, and G9's check is deferred.
  - G9, "Mark everything trial so tools refuse it", is not grammatical, and its own check admits that nothing refuses trial files in this work.
  - "Little custom code" (G5) cannot be measured.
- **Fix:**
  - Line 43: "Each goal names its check: an automated test, a run-report measurement, the owner's judgement on a device, or a code review."
  - G9: "**Mark all trial settings and output as trial.** *Check:* a test that a `purpose: trial` profile produces files whose metadata has `trial: true`." Move "the packaging step refuses both" to Future Improvements.
  - G5's heading: "Custom code limited to the parts named here."

### SY-6. A rejection reason misstates the evidence. 🟡 Major

- **Lines:** 323 (A17).
- **Problem:** "the spike's sample already proved a poor guide for mountains and sand seas." ADR 0009 (line 25) says only that the dunes and Yemen's mountains may cost more than the sample, and that the extrapolation is an estimate, not a bound. No spike measurement proves this.
- **Fix:** "the spike's 8-square-degree sample is a weak basis for extrapolation; ADR 0009 notes that the sand seas and Yemen's mountains may cost more than it."

### SY-7. A stated fact goes beyond its evidence. 🟢 Nit

- **Lines:** 73, 75. See ST-6.
- **Problem:** "87 are all sea, absent upstream." `tileList.txt` proves that these cells are absent, not that they are sea; the brief listed this as unverified. The heading "(checked 2026-11-14)" also does not say how each fact was checked.
- **Fix:** "87 are absent from `tileList.txt` (expected to be all sea)." Add a source in brackets after each fact. See DE-2 for what absent cells do to the build.

### SY-8. "Real" is used as an intensifier. 🟢 Nit

- **Lines:** 31, 247.
- **Problem and fix:**
  - Line 31: "real builds of the real extent, viewed in the real app". Rewrite as: "These answers need full builds of the trial extent, viewed in the v1 app on the three test devices, …"
  - Line 247: "so there is no real resume". Rewrite as: "so an interrupted build cannot resume."
  - Keep "real" where it means the production package (23, 57–58, 280).

### SY-9. The Alternatives section repeats itself and changes voice. 🟢 Nit

- **Lines:** 214–330. See ST-3.
- **Problem:**
  - Line 214 already says the decisions are the owner's, yet "Rejected by author" repeats in 18 reason lines (222, 228, 234, 240, 248, 254, 260, 266, 272, 280, 286, 292, 300, 306, 312, 318, 324, 330). "After review" appears on some of these lines and not others, with no explanation.
  - A8 (266) gives no reason.
  - "Our own" (250) and "we write and maintain" (253) break the third person used everywhere else.
- **Fix:**
  - Drop the prefix and keep only the reason. If "after review" matters, define it once at line 214.
  - A8's reason: "Costs money, and ADR 0009 decision 8 requires real builds on the owner's machine."
  - Write "the project's own scheduler" and "the project writes and maintains".

### SY-10. Pros and reasons use superlatives and subjective judgements. 🟢 Nit

- **Lines:** 220, 226, 238, 258, 264, 272, 322.
- **Fix:** Replace each judgement with a concrete fact:
  - "fastest": "no new viewer code";
  - "quick to write": "no shared module setup";
  - "complete isolation": "no trial code in `:app`";
  - "the least new code": "no block code";
  - "the simplest code": "the spike's whole-raster method runs unchanged";
  - "hours faster": "no full build needed";
  - "the right home for the project's own logic": "keeping the project's logic in one pure, testable function".

### SY-11. Jargon is not defined at first use. 🟢 Nit

- **Lines:** 13, 118, 133, 135, 143, 189, 196, 200. None of these terms is in CO-20.
- **Fix:** Add a short definition at first use:
  - style C: "(the spike's painted relief in three soft bands, ADR 0009)";
  - trust on first use: "(the first download's hash is recorded as trusted)";
  - EPSG:3857: write "Web Mercator (EPSG:3857)";
  - VRT: "(GDAL virtual raster: one file that joins many)";
  - σ: "(the blur's standard deviation, in pixels)";
  - PSS: "(proportional set size)";
  - "neutral place" and "neutral Najd ground": "away from any site or event the app shows".

### SY-12. "Spec" is ambiguous. 🟢 Nit

- **Lines:** 180, 182.
- **Problem:** The document calls itself a specification, so "builds the style from the spec" can be read as "from this document".
- **Fix:** "from the `MapStyleSpec`".

### SY-13. Some sentences are too long to scan. 🟢 Nit

- **Lines:** 68, 69, 112 (about 95 words), 143 (about 120 words), 187.
- **Fix:** Split each into a nested list, with one item per mount, step or check.

### SY-14. Heading levels and numbering are inconsistent. 🟢 Nit

- **Lines:** 65–69, 342, 348. Same as ST-7 for the headings; see ST-5 for the numbering.
- **Fix:** Make Additional Resources and Future Improvements H2s. Number the two "To verify" bullets Q2 and Q3, or move them under their own heading, "To verify in step 1".

### SY-15. Some wording is unclear. 🟢 Nit

- **Lines:** 25, 37, 154, 285.
- **Fix:**
  - Line 25: "going from 11 to 12 roughly quadruples the relief's size."
  - Line 37: "No safe way exists" becomes "No permitted way exists (ADR 0007 decision 10)."
  - Line 154: "the view leaves the data up to about zoom 10" becomes "at zoom 10 and below, the view can extend past the data." DE-4 questions the number itself.
  - Line 285: "slopes look about 2% flatter" becomes "slopes are shaded about 2% flatter at 12° N and 15% flatter at 32° N (a factor of cos latitude)."

### SY-16. The performance requirement is a slogan. 🟢 Nit

- **Lines:** 337.
- **Problem:** "Performance: measured, not assumed" repeats line 63 and G2 without adding anything.
- **Fix:** "Performance: build time, peak memory and temporary disk are recorded in the run report (G2), and ADR 0002's four measures are taken on the three devices (G6)." Once DE-9's limits exist, add them here.

---

## Notes for the author

- **Order of revision.** First, add the Risks and Mitigations section (ST-1, DE-1) and the "Trial items and pass rules" section (ST-2, together with CO-1). Many other findings then have a home: DE-9 and SY-4 (limits and the step 3 rule), SY-3 (the failure criteria word for word), SY-5 (G9's check), DE-7 (item 4 as a conditional step) and DE-8 (the per-device frame rule).
- **Correctness gaps the tests cannot catch:** DE-2 (absent cells), DE-4 (zoom convention and `tileSize`) and DE-5 (base layer). Each needs a step 1 check or an example change, not only new wording.
- **Owner input needed.** DE-9 needs the owner to set limits for build time and disk. DE-12 needs the owner to say who sets `approved` on the test edits. Neither reopens a decision; both fill a gap.
- **Owner's failure criteria touched:** visible seams or bad terrain (DE-2, DE-4, DE-5, DE-6), missed performance targets (DE-8), unmeasured or impractical build cost (DE-3, DE-9), unnecessary custom code (DE-11, DE-13), and repeatable builds (DE-1 (c)).
