# Stage 8: Intermediate zoom out, map trial specification

**Target:** `docs/design/map-trial-build.md` (lines 1–736; FeatureRange 1–355, DetailedRange 356–736)

**Standard:** `zoom-out-standard.md` (EGM v0.1.1)

**Reviewer:**

| Reviewer | Status | Blocker | Major | Nit |
| --- | --- | --- | --- | --- |
| `egm-zoom-out` | completed | 1 | 11 | 1 |

The reviewer read the full target, the three earlier reports (stages 5, 6 and 7: 91 raw findings) and the GoldfishContext files it needed. It treated the owner's decisions dated 2026-11-14 as final and reports only the risks they create. Line numbers refer to the target file.

The synthesizer checked these claims against the files:

- `android` is a required check on `main` (`.github/workflows/android.yml` lines 7–8, in the workflow's own comment), and page alignment is checked on the release APK only (line 41).
- The spike's relief source uses `tileSize: 256` (`android/spike/spike/src/main/kotlin/app/seerah/spike/MapStyle.kt` line 86).
- ADR 0009 decision 8 (lines 168–172) contains both rules that CO-18 says are mixed up. Decision 13 (lines 193–204) lists the eleven items.
- Target line 75: the 616 cells are the box plus one cell all round (22 × 28). The "one-cell ring" there is the 1° margin.
- The brief's order of work says "downloads for the small windows only" (brief line 167). The owner's failure criteria are at brief lines 18–24.
- ADR 0009's References (lines 268–274) do not cite `tileList.txt` or Protomaps' `disk_to_pmtiles`. ST-6 is therefore kept, not cut as the reviewer proposed.

## Verdict

**The spec needs revision before implementation.** There is one Blocker: as written, step 1 cannot be run, because the commands and their inputs do not connect. The other twelve items merge the 91 raw findings into their root causes. Most fixes delete code or text: an image without the tool code and without `image.lock`, one block stage instead of two, no PMTiles reader in Kotlin, no `tools/common`, no `report` command, and no Range-resume downloader. The few additions are one sentence, one flag or one table each.

Nothing here reopens M2, the `benchmark` build type, Natural Earth beyond the extent, edits before smoothing, or the private trial settings.

Only one decision needs the owner: limits for build time and disk (ZI-5). The owner should also be told about one change: ZI-2 can narrow how much resume GDAL's tiler provides.

What the spec most needs before implementation, in order:

1. Connect the inputs to the commands (ZI-1).
2. Fix the correctness gaps that the planned tests would not catch: absent cells (ZI-9), the zoom convention (ZI-7), coast lines and the base layer (ZI-10), and what option (c) stores (ZI-8).
3. Add the decision 13 item table with pass rules, and the owner's limits (ZI-5).
4. Make the net deletions (ZI-2, ZI-3, ZI-4, ZI-11, ZI-12).

The revision should remove at least as much text as it adds. Two plans of 736 lines have already drifted apart.

---

## Items

### ZI-1. The inputs do not connect to the commands. 🔴 Blocker

- **Resolves:** ZO-1, ZO-2, CO-3, CO-4, CO-5, SY-2 (IC-7 cut).
- **Problem:**
  - `lock` downloads and hashes every cell of the extent (118, 431). Step 1, by the owner's order, downloads only the small windows (206; brief line 167).
  - There is a single `sources.lock` (200, 561), and it refuses changes without `--replace` (431).
  - `build` takes only `--profile` (416) and never says how it finds the edits and the lock.
  - `probe-valleys` reads `terrain.vrt` over the whole extent (171, 510). That file does not exist in step 1, and the work area is deleted when a build succeeds (169).
  - `base.py` also reads `terrain.vrt` (500). Natural Earth is "pinned in the lock" (500, 515), but no command fetches or hashes it.
  - Line 336 says every download is pinned, which `lock` itself does not meet.
- **Fix:**
  - Merge `lock` into `fetch --profile P`. On its first run, `fetch` writes P's own lock, with trust-on-first-use hashes and Natural Earth included. On later runs it verifies against that lock.
  - Use a folder convention: `<profile>.lock` and `edits.yaml` sit beside the profile. The full-box lock stays the owner's "trial `sources.lock`". The windows get their own small locks, which share the same download cache.
  - Build `base.pmtiles` inside `build`, before the work area is removed.
  - Drop `probe-valleys` and `valleys.py`. Instead, document the probe as two commands run in the image under the memory cap: warp GLO-90 (`copernicus-dem-90m`, as `tools/spike/build_base.sh` already does) to 90 m and 180 m, then run WhiteboxTools. "Fits" means the container was not OOM-killed. The result goes into `step-1.md`. Line 336 names this as the one unpinned read, which is acceptable because the probe produces nothing that ships.
  - Reword line 336 as SY-2 proposes.
- **Owner decision:** no. The probe's input changes from edited 30 m terrain to unedited GLO-90. Edits do not change the probe's memory use, and the owner's order did not specify the input.

### ZI-2. The build stages: one block stage, one computed border, a defined tile-stage recovery, and no exclusive work area. 🟡 Major

- **Resolves:** ZO-9, IC-2, IC-4, IC-5, CO-8.
- **Problem:**
  - Stage 1 stores about 19 GB of float32 elevation (4.8 billion px × 4 bytes) only for stage 2 to read it back (135–141). This adds a third resume layer and is the largest use of temporary disk.
  - The terrain border is never defined (135). Line 138's claim that an edit "never reaches beyond the block border" does not hold when distances are computed on the block window.
  - A half-written zoom-12 tile can feed its lower-zoom ancestors before the decode check runs. "Delete, then `--resume` once more" (148, 490) repairs only the tile itself, and line 69's "handles it either way" is unproven.
  - The exclusive work-area rule (120, 131, 441) lets one failed full-box build block every small-window build. That conflicts with the owner's reliability criterion.
- **Fix:**
  - Fuse stages 1 and 2 into one block stage. It reads the DEM and the Water Body Mask over the block plus one border, applies the edits, computes the mask and the style, and writes the styled block plus a byte mask block.
  - Compute the border from the settings: the style border, plus the mask smoothing radius, plus the largest `feather_m` (plus half of `width_m` for `carve`). Per-zoom mode then warps straight to each zoom's grid.
  - Tile stage: in step 1, check whether `gdal raster tile` writes each tile atomically (temporary name, then rename). If it does, keep `--resume`. If it does not, an interrupted tile stage empties the tile folder and reruns. The completeness check then becomes "every expected tile exists and decodes, otherwise fail", with no repair loop.
  - If step 3 shows a long tile stage, tile in two passes (zoom 12, then zooms 0–11 with `--resume`).
  - Drop the ancestor-chain invalidation.
  - Delete the exclusivity rule. Work areas are already named by fingerprint, so several can coexist. `clean` lists them with their sizes.
  - Cut multi-variant builds: step 2 fixes the encoding, so only one variant is ever built at full size (207).
- **Owner decision:** not now. Tell the owner that if GDAL does not write tiles atomically, the tile stage restarts instead of resuming. That narrows A6's reason ("leaves … resume to GDAL", 254), though M2 is unchanged. The owner decides only if step 3 shows that restart is too slow and the two-pass fallback is also unsuitable.

### ZI-3. The image and the code are coupled, the publish loop stalls, and the container has more access than it needs. 🟡 Major

- **Resolves:** ZO-3, ZO-17, IC-1, DE-10.
- **Problem:**
  - The image installs `tools/map` (375), while the fingerprint and report record the mounted checkout's commit (131, 421), so the commit recorded is not necessarily the code that ran.
  - `image.lock` is updated by a bot pull request (402, 657). A pull request opened with `GITHUB_TOKEN` does not start the required `android` check, so it waits forever. `image.lock` also sits inside the workflow's trigger path, so merging it can trigger another rebuild.
  - The git checks run inside the GDAL image (421), which may not include git (new).
  - `:Z` relabels the owner's checkouts (112, 393).
  - Every command has network access and can read the whole private checkout. tippecanoe is pinned by a tag, which can move.
- **Fix:**
  - The image holds only the toolchain. Tag it with a hash of the Dockerfile and `requirements.txt`; CI pushes the tag if it is missing.
  - `run.py` computes the same tag, pulls it, and passes the resolved digest in. Code runs from the read-only `/src` mount with `PYTHONPATH` set.
  - Delete `image.lock`, the bot pull request and `pull-requests: write`.
  - `run.py` runs the clean-checkout checks on the host and passes both commits in.
  - Use `--security-opt label=disable` instead of `:Z`, and `--network none` for every command except `fetch`. Mount only `seerah-content/map/`, and pin tippecanoe by commit.
- **Owner decision:** no.

### ZI-4. Machine settings are inside the fingerprint, and the memory figures will mislead. 🟡 Major

- **Resolves:** ZO-8, ZO-11, DE-3 (IC-11 cut).
- **Problem:**
  - `run.py` is "standard library only" but has to read the YAML profile for the memory budget (390, 394).
  - `memory_budget_mib` and `per_worker_mib` are profile fields (446, 515), so they enter the fingerprint (131). After an OOM kill, lowering them throws away every finished block (new). This hits the owner's reliability criterion more directly than IC-11 does.
  - Peak memory read from the control group (167, 505) includes page cache, so it will read close to the cap on every run.
  - Sampling disk use after each block (505) misses the tile stage, where disk use peaks.
- **Fix:**
  - Make the memory cap a launcher flag (`--memory-mib`). The tool derives the worker count from cgroup `memory.max` and a per-worker allowance passed as a flag. Neither value enters the fingerprint; the block size stays in it. After an OOM kill, the owner reruns with a lower cap or fewer workers, and the build resumes.
  - The report records:
    - `memory.peak`, labelled "includes page cache";
    - the `oom_kill` count from `memory.events`;
    - the largest worker's maximum RSS (`resource.getrusage(RUSAGE_CHILDREN)`);
    - the work area's size, measured once after tiling.
  - Drop the `report` subcommand.
  - G2's host check becomes an owner step: read `journalctl -k` after the full build.
  - Cut automatic OOM retry and per-second `memory.stat` sampling.
- **Owner decision:** no.

### ZI-5. The trial items, their pass rules and the build limits are missing. 🟡 Major

- **Resolves:** CO-1, CO-6, DE-7, DE-8, ST-2, SY-3, SY-5, DE-9, SY-4.
- **Problem:**
  - G1 and the order of work cite decision 13's items by number (45, 207–210) but never list them.
  - Item 4 has no step, and line 350's claim that option (b) "reuses stages 1–4" conflicts with the fixed zoom-12 block grid.
  - Step 3's "stop if the numbers are wrong" (208) cannot be checked.
  - A median of "lowest" frame-rate readings (190) can pass a device that misses ADR 0002's worst-case floor.
  - Line 39 paraphrases the owner's criteria and drops parts of them (brief lines 20–24).
  - G9's check is deferred to packaging (53).
- **Fix:**
  - Add one table copied from ADR 0009 decision 13 (lines 194–204), with columns item, step, build or variant, and pass rule:
    - item 11 quotes ADR 0002's numbers;
    - item 1 flags the 2 GiB limit;
    - the other items say "owner judges".
  - Item 4 reads: "if (a) and (c) both fail, stop; (b) gets its own specification." Delete line 350's claim.
  - Item 11's lowest rate is the minimum over all runs, with the cold first run reported separately. The typical rate is the median of the one-second readings. Cut the cool-down protocol and the temperature display.
  - Copy the owner's five criteria word for word at line 39.
  - G9's check becomes a test that a trial profile produces `trial: true` in the output metadata.
  - Keep "Order of work" where it is; do not add a new H2 around it.
- **Owner decision: yes.** The owner sets a wall-time limit and a temporary-disk limit for one full build, or states that both are "judged from the step 3 report". Step 3's stop rule then cites them. Free-disk pre-checks are cut: atomic block writes plus resume already recover from a full disk, and step 3's extrapolation is the gate.

### ZI-6. The risks that the owner's decisions accept are not recorded. 🟡 Major

- **Resolves:** ST-1, DE-1, ZO-16.
- **Problem:** Lines 334–338 cover only security, performance and removal. The accepted risks appear only in the brief (lines 58, 111, 162, 165) or as a "Pro" of the rejected A10 (278). The earlier Blocker rating (ST-1) is overstated: a missing heading does not stop implementation, and most of these risks are already accepted in the brief.
- **Fix:** Add a short "Known risks and accepted costs" list of six to eight bullets, not a five-column table. Each bullet names its trigger and the step that checks it:
  - the 2 GiB file limit (ADR 0009 line 172);
  - M2's temporary tile folder of about 100,000 files, and the tile-stage recovery from ZI-2;
  - edits narrower than about 500 m vanish (brief line 162);
  - the blur's ground spread varies by about 13% with latitude (brief line 165);
  - trust-on-first-use hashes are lost if the cache volume is pruned, accepted because the trial is one-off;
  - one commit per tuning try, because of ADR 0009 decision 8;
  - the Baseline Profile plugin brings its own `benchmarkRelease` type (ZO-16).
- **Owner decision:** no. This records the owner's decisions; it does not reopen them.

### ZI-7. The extent terms, the counts and the zoom convention are undefined. 🟡 Major

- **Resolves:** ZO-7, CO-2, CO-15, DE-4.
- **Problem:**
  - "Box", "extent", "margin" and "ring" are never defined against each other (75, 118, 162, 200). Line 75's ring is the 1° margin, but line 118 adds a ring to "the extent", which reads as a second ring.
  - The counts of about 1,150 blocks and 4.8 billion pixels (35, 133) cover the box only. With the margin they are about 1,360–1,430 blocks. ZO-7's "about 720 cells" assumed that second ring.
  - The relief source's `tileSize` is never stated, and neither is which zoom scale each number uses (154, 178, 190, 605). Line 154's spans hold only for 256-pixel tile zooms on about 2,800 physical pixels. In MapLibre camera zooms, the view passes the data only up to about zoom 8–9.
  - New: in the trial, the two coverage checks (160–163, `check_coverage` at 426) hold by construction.
- **Fix:**
  - Define once: camera bounds = the box; data extent = the box plus a 1° margin = the 616 locked cells. Drop the separate ring.
  - Recompute the block and pixel counts.
  - Add a "Zoom conventions" paragraph: build zooms are 256-pixel tile zooms; style, fade, camera and path zooms are MapLibre zooms; the relief source uses `tileSize` 256, as in the spike.
  - List the three devices (ADR 0009 line 190) with their widths in dp. Recompute line 154 and Natural Earth's highest zoom from those widths.
  - Make the coverage formula a unit test rather than a runtime check.
- **Owner decision:** no. The extent values remain the owner's working values.

### ZI-8. The style's units and what option (c) stores. 🟡 Major

- **Resolves:** IC-6, DE-6, CO-7, CO-12.
- **Problem:**
  - Blurs are in pixels at the build's maximum zoom (127, 470). If the owner picks zoom 11, the real build blurs twice as far on the ground as the zoom-11 variant the owner judged.
  - The value encoder stores darkness after banding and band blur (143, 146). ADR 0009 decision 4 describes option (c) as "one continuous darkness value", and item 3's size verdict depends on which is stored.
  - The calibration rule (52, 143, 470) is not stated, and `contrast` (466) is missing from the settings list (127).
- **Fix:**
  - Define blurs in pixels at zoom 12 and convert them with the rule per-zoom mode already uses (485).
  - The style function returns darkness before banding. Banding and band blur move into the painted encoder's path.
  - State the calibration rule: exaggeration = 8 × cos 23° ≈ 7.36, confirmed by comparing pixels with build 11.
  - Add `contrast` to the settings list.
  - The comparison of (c) at zooms 5–11 belongs in step 2 (implementation).
- **Owner decision:** no. This follows ADR 0009 decision 4 as written.

### ZI-9. Absent cells become land. 🟡 Major

- **Resolves:** DE-2, SY-7.
- **Problem:** The mask treats everything except class 1 as land (137, 451). A VRT returns its fill value, 0 ("no water"), where a cell is absent. Each of the 87 absent sea cells (75) therefore becomes a 1° square of land, and a no-data sentinel can be blurred into neighbouring coasts. The CI example sits inside one present cell (196, 549), so no test sees this.
- **Fix:**
  - Set the fill values explicitly: an absent mask reads as ocean, and absent elevation reads as 0.
  - Give the example lock one `absent: true` neighbour, so the seam and mask tests cross it.
  - Line 75 says "87 are absent from `tileList.txt` (expected to be all sea)".
  - Add no extra build-time check.
- **Owner decision:** no.

### ZI-10. The base layer repeats two of the spike's failures. 🟡 Major

- **Resolves:** IC-3, DE-5, SY-1 (line 152), CO-3 (zoom bands).
- **Problem:**
  - Coast lines taken from polygon outlines will also draw the clipped extent edge as coast, the spike's build 21–22 artefact (spike findings line 271).
  - One very large land polygon is the case tippecanoe dropped in the spike (findings line 93; `build_base.sh` lines 19–20).
  - G3's "every tile at every zoom" (47) cannot hold for vector tiles.
  - Line 152's "cannot disagree" overstates.
  - The zoom bands are never listed (152, 500).
- **Fix:** Reuse the spike's fixes and add no new checks.
  - Keep the smoothed mask as a byte from 0 to 255. Draw the coast as `gdal_contour` lines at the midpoint, which stop at the raster's edge instead of tracing it.
  - Cut the land polygons on the existing block grid before tippecanoe.
  - Clip Natural Earth so that it overlaps the extent by a few pixels.
  - List the zoom bands, reusing Natural Earth's 0–2, 3–5 and 6–max.
  - Limit G3 to relief, and record that a missing vector tile shows the sea background, which is correct.
  - Cut the automated parent-against-children check and the check for coast vertices on the frame.
- **Owner decision:** no.

### ZI-11. The trial app carries more custom code than it needs, with no removal step. 🟡 Major

- **Resolves:** ZO-4, ZO-5, ZO-14, CO-9, CO-10, DE-11, IC-8 (IC-10 and DE-13 cut).
- **Problem:**
  - A binary PMTiles reader in Kotlin, with a fixture built by the Python tool, exists only to name variants (188, 640, 650).
  - A folder-picker importer doubles the storage each variant uses on each phone (187, 639).
  - The camera stops come from metadata that does not hold them (644 against 495), and putting the private edit locations in public code would expose them.
  - Open time includes the owner's tap (189, 638).
  - Output and import folders disagree (150, 187, 495, 639).
  - `MapCamera` (607) is missing from the `SeerahMap` signature (180, 606).
  - The trial-shaped branches in `:core:map` and the tool have no removal step (338).
- **Fix:**
  - `pack` copies the `metadata.json` it already writes beside each output, and the app reads it with `org.json`. Delete `PmtilesMetadata.kt`, its test and the fixture.
  - Use one output layout: `<profile>-<variant>-<fp8>/` holding the relief file, `base.pmtiles` and `metadata.json`.
  - The owner `adb push`es the folders into the benchmark app's external files folder. Check this on the three devices in step 1, and keep `TrialImporter` only as the fallback.
  - Put the camera stops in a private `camera-path.json` that travels with the folder.
  - Open the variant named in the launch intent, or the last one used.
  - Add `MapCamera` to the interface list and the signature.
  - Add a post-trial deletion list to "Rollout and removal": the losing encoder, per-zoom mode, the unused `ReliefSource` branch, and `FrameRendered` unless v1 needs it.
  - Cut the opt-in annotation and the separate diagnostics listener: `release` does not depend on `:core:map` (626), and there is no v1 caller yet.
- **Owner decision:** no. The step 1 device check decides whether the importer stays.

### ZI-12. The tool has custom code and packages beyond what one publisher needs. 🟡 Major

- **Resolves:** ZO-6, ZO-10, ZO-12, IC-12, CO-16, DE-12, SY-5 (G5), plus two new points.
- **Problem:**
  - `fetch` has HTTP Range resume and a parallel download pool (436).
  - `tools/common` is a second package for one function (363, 539), with its own pyproject, README, tests, Dependabot entry and CI path. Its second user, the content build tool, does not exist yet.
  - `purpose: release` exists only so that code can refuse it (421), and `lower` duplicates `raise` (456).
  - `review.status` (456) does not match the content schema's string enum (`content.schema.json` lines 7–9). `$defs/source` cannot describe a synthetic edit.
  - Line 200 has the implementer commit `review: approved`, against AGENTS.md ("Only the owner marks content `approved`").
  - New: a JSON Schema for the machine-written lock (515).
  - New: a test hook that raises inside the production block runner (535).
- **Fix:**
  - `fetch` downloads, hashes and renames one file at a time; a failed file restarts from zero.
  - Put the strict YAML loader in `seerah_map`.
  - Make `purpose` the constant `trial` in the schema, and drop `lower`.
  - Test `review == "approved"`. In `trial-0`, `sources` is optional and is checked against `$defs/source` when present.
  - Commit the test edits as `draft`; the owner sets `approved` at the step 1 checkpoint.
  - Drop `lock.schema.json`.
  - The resume test kills the build process after N progress lines.
  - G5 names everything that remains: the block stage, the completeness check and the base-layer glue.
- **Owner decision:** no. AGENTS.md already settles who approves.

### ZI-13. Consistency, accuracy and structure edits. 🟢 Nit

- **Resolves:** ZO-13, CO-11, CO-13, CO-14, CO-19, ST-3, ST-4, ST-5, ST-6, ST-7, ST-9, SY-1, SY-6.
- **Fix:**
  - Use one launcher name, `run.py` (112 against 365, 387).
  - Make the two fingerprint lists match (131 against 441). After ZI-4 they hold the profile, edits, lock, tool commit, image digest, variant and mode.
  - Use one CI job name, and drop the path-filter sentence (402, 657, 662).
  - Write "file 10" or "see `lock.py`" at 515.
  - Give A8 a reason (266). Correct A17's evidence (323) as SY-6 proposes.
  - Rewrite the claims at lines 69, 99, 146, 152 and 189 as SY-1 proposes.
  - Add a three-line list of ADR changes under the Overview: ADR 0009 decisions 3, 6 and 7, and the ADR 0004 build-type note.
  - Number the step-1 checks, each with a fallback and the place its result is recorded. Add the checks that were dropped or are new:
    - the filesystem behind Docker's volumes;
    - whether the GHCR package is public;
    - whether average resampling weights pixels by alpha;
    - Licensee against MapLibre;
    - `adb push`;
    - `tileSize`;
    - whether GDAL writes tiles atomically (ZI-2).
  - Add one "External references" line to Additional Resources that cites the source of each "fact this plan rests on" (73–78). The `disk_to_pmtiles` hash claim behind G4 especially needs one, because ADR 0009 does not cite it.
  - Make Additional Resources and Future Improvements H2s.
  - Redraw the components diagram after ZI-1 and ZI-2.
- **Owner decision:** no.

---

## Earlier findings that are wrong or overstated

- **CO-1 (rated Blocker) is Major.** ADR 0009 decision 13 lists the items (lines 193–204), and the target links ADR 0009 at line 11. The comprehension reviewer read only the target. The table is still worth adding (ZI-5).
- **ST-1 (rated Blocker) is Major.** Most of the risks it lists are already accepted in the brief. A short list is enough (ZI-6).
- **ZO-7 is partly wrong.** The 616 cells are the box plus a 1° margin all round, so the "about 720 cells" figure assumes a second ring that line 75 does not propose. Its point that the block and pixel counts cover the box only is correct.
- **CO-18 is wrong.** ADR 0009 decision 8 states both rules: builds run on the owner's machine, and uncommitted changes are refused.
- **CO-17 is wrong.** Line 13 describes M2 in the sentence that names it.
- **IC-7's fix contradicts the owner's order** to download only the small windows in step 1 (brief line 167).
- **IC-5's multi-variant build brings little.** Step 2 fixes the encoding, so only one variant is built at full size.
- **IC-2's mechanism is unverified.** Whether GDAL builds lower zooms from tiles on disk, and how it handles a corrupt one, is not known. ZI-2 makes that a step 1 check with a fallback instead.

---

## Triage of all raw findings

K = change the spec now; S = settle in implementation or the trial; O = owner decision; C = cut.

| ID | Triage | Item | Reason |
| --- | --- | --- | --- |
| ZO-1 | K | ZI-1 | The probe's input does not exist in step 1. |
| ZO-2 | K | ZI-1 | `lock` downloads the full box; merge it into `fetch` with per-profile locks. |
| ZO-3 | K | ZI-3 | The bot pull request never starts the required check. |
| ZO-4 | K | ZI-11 | Keep the deletion list; cut the opt-in annotation. |
| ZO-5 | K | ZI-11 | Delete the Kotlin PMTiles reader and the cross-language fixture. |
| ZO-6 | K | ZI-12 | Sequential downloads; G5 names what remains. |
| ZO-7 | K | ZI-7 | Counts cover the box only (its cell figure is wrong). |
| ZO-8 | K | ZI-4 | The memory cap belongs to the launcher, not the profile. |
| ZO-9 | K | ZI-2 | Fuse the terrain and style stages. |
| ZO-10 | K | ZI-12 | Put the loader in `seerah_map` until a second user exists. |
| ZO-11 | K | ZI-4 | Drop `report`; measure disk once after tiling. |
| ZO-12 | K | ZI-12 | `purpose` constant `trial`; drop `lower`. |
| ZO-13 | K | ZI-13 | Reconcile the listed mismatches only. |
| ZO-14 | K | ZI-11 | Camera stops go in a private file with the variant. |
| ZO-15 | S | – | One CI line runs the alignment script on the benchmark APK. |
| ZO-16 | K | ZI-6 | One risk line; the build type stays. |
| ZO-17 | K | ZI-3 | `label=disable` instead of `:Z`; check the filesystem in step 1. |
| IC-1 | K | ZI-3 | Toolchain-only image; code runs from `/src`. |
| IC-2 | K | ZI-2 | A step-1 atomic-write check with a restart fallback; cut ancestor invalidation. |
| IC-3 | K | ZI-10 | Coast as contour lines, which do not trace the edge. |
| IC-4 | K | ZI-2 | One border computed from the settings. |
| IC-5 | K | ZI-2 | Keep side-by-side work areas; cut multi-variant builds. |
| IC-6 | K | ZI-8 | Blurs in pixels at zoom 12. |
| IC-7 | C | – | Its fix overrides the owner's step-1 download order; ZO-1 and ZO-2 are chosen. |
| IC-8 | K | ZI-11 | A cold open must not include a tap. |
| IC-9 | S | – | A step-1 check plus one assertion in the example test; no per-tile build check. |
| IC-10 | C | – | Duplicate of ZO-14; a trial-only schema field is heavier. |
| IC-11 | C | – | Automatic OOM retry is not needed once machine settings leave the fingerprint (ZI-4). |
| IC-12 | K | ZI-12 | Correct; match the content schema. |
| IC-13 | S | – | Pick lossless WebP or PNG for the value tiles in step 1. |
| IC-14 | S | – | The Dockerfile creates writable `/cache` and `/work`; Licensee check in step 1. |
| CO-1 | K | ZI-5 | Add the item table (Major, not Blocker). |
| CO-2 | K | ZI-7 | Define the extent terms once. |
| CO-3 | K | ZI-1, ZI-10 | Base built inside `build`; Natural Earth fetched; zoom bands listed. |
| CO-4 | K | ZI-1 | The probe's input is stated. |
| CO-5 | K | ZI-1 | A folder convention for the edits and lock. |
| CO-6 | K | ZI-5 | Quote ADR 0002's targets; define the statistics. |
| CO-7 | K | ZI-8 | State the calibration rule. |
| CO-8 | K | ZI-2 | Define the border; reword line 138. |
| CO-9 | K | ZI-11 | One folder layout from build to device. |
| CO-10 | K | ZI-11 | Source of the stops; `MapCamera` in the signature. |
| CO-11 | K | ZI-13 | Fingerprint lists match. |
| CO-12 | K | ZI-8 | Add `contrast` to the settings. |
| CO-13 | K | ZI-13 | "file 10", not "item 10". |
| CO-14 | K | ZI-13 | One launcher name. |
| CO-15 | K | ZI-7 | Device widths are needed for the zoom calculation. |
| CO-16 | K | ZI-12 | One shape for `review`. |
| CO-17 | C | – | Wrong: line 13 describes M2. |
| CO-18 | C | – | Wrong: ADR 0009 decision 8 holds both rules. |
| CO-19 | K | ZI-13 | One job name; drop the path-filter sentence. |
| CO-20 | C | – | The brief is linked at line 19; the other term appears once, in a rejected alternative. |
| DE-1 | K | ZI-6 | A short list, not a section of tables. |
| DE-2 | K | ZI-9 | Explicit fill values and one absent cell in the example. |
| DE-3 | K | ZI-4 | Honest labels and `getrusage`; cut per-second sampling. |
| DE-4 | K | ZI-7 | Zoom conventions and `tileSize` (verified 256). |
| DE-5 | K | ZI-10 | Reuse the spike's fixes; cut the automated base check. |
| DE-6 | K | ZI-8 | Option (c) stores darkness before banding. |
| DE-7 | K | ZI-5 | Item 4 is a conditional stop; delete line 350's claim. |
| DE-8 | K | ZI-5 | Minimum over all runs, cold run separate; cut cool-down and temperature. |
| DE-9 | O | ZI-5 | The owner sets or waives the time and disk limits; cut the free-disk pre-checks. |
| DE-10 | K | ZI-3 | Cheap flags: no network, a narrower mount, tippecanoe by commit. |
| DE-11 | K | ZI-11 | `adb push` if the step-1 check passes; importer as fallback. |
| DE-12 | K | ZI-12 | AGENTS.md settles it: the owner approves. |
| DE-13 | C | – | Covered by ZI-11's deletion list; a listener adds API. |
| DE-14 | S | – | Step-1 check that the GHCR package is public. |
| ST-1 | K | ZI-6 | Major, not Blocker; a bullet list. |
| ST-2 | K | ZI-5 | A table only; "Order of work" stays where it is. |
| ST-3 | K | ZI-13 | Only A8's missing reason; cut the links and the origin marks. |
| ST-4 | K | ZI-13 | A three-line list of ADR changes. |
| ST-5 | K | ZI-13 | Number the step-1 checks; add the dropped ones. |
| ST-6 | K | ZI-13 | One external-references line; ADR 0009 does not cite `disk_to_pmtiles` or `tileList.txt`. |
| ST-7 | K | ZI-13 | Two headings become H2s. |
| ST-8 | C | – | 17 new H4s are churn. |
| ST-9 | K | ZI-13 | Redraw the diagram after ZI-1 and ZI-2. |
| ST-10 | C | – | The context makes "decision N" clear. |
| SY-1 | K | ZI-13 | Rewrite five overstated claims. |
| SY-2 | K | ZI-1 | Reword the download claim at line 336. |
| SY-3 | K | ZI-5 | Copy the owner's criteria word for word. |
| SY-4 | O | ZI-5 | Same decision as DE-9. |
| SY-5 | K | ZI-5, ZI-12 | G9's check now; G5 names what remains. |
| SY-6 | K | ZI-13 | Correct A17's evidence. |
| SY-7 | K | ZI-9 | "Absent", not "all sea". |
| SY-8 | C | – | Wording taste. |
| SY-9 | C | – | Wording taste; A8 is handled under ST-3. |
| SY-10 | C | – | Wording taste. |
| SY-11 | C | – | Wording taste. |
| SY-12 | C | – | Wording taste. |
| SY-13 | C | – | Wording taste. |
| SY-14 | C | – | Duplicate of ST-5 and ST-7. |
| SY-15 | C | – | Line 154 is handled by ZI-7; the rest is taste. |
| SY-16 | C | – | Replaced by ZI-5's pass rules. |

**Totals:** 91 raw findings: 66 K, 5 S, 2 O, 18 C.

**Conflicting fixes, resolved:**

- **ZO-1/ZO-2 against IC-7:** ZO-1 and ZO-2 are chosen, because IC-7 overrides the owner's step-1 order.
- **ZO-9 against IC-5:** ZO-9 is chosen. Only IC-5's side-by-side work areas are kept, because that is a deletion.
- **ZO-3 against IC-1:** these are one fix.
- **ST-1/DE-1 against a lighter treatment:** the lighter one is chosen, a bullet list.

**Fixes cut as heavier than needed:**

- automatic OOM retry;
- per-second `memory.stat` sampling, a kernel-log scanner and the device temperature display;
- ancestor-chain invalidation;
- the `camera_path` and `synthetic` schema fields;
- a lock-subset fingerprint;
- the opt-in annotation and the diagnostics listener;
- multi-variant builds;
- free-disk pre-checks;
- automated checks for land in absent cells, coast vertices on the frame, and base parent and child tiles;
- the five-column Risks table, a new pass-rules H2 that moves "Order of work", and H4s for every mechanism.
