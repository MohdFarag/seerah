# Consolidated EGM review: map trial specification

**Target:** `docs/design/map-trial-build.md` (736 lines; FeatureRange 1–355, DetailedRange 356–736), uncommitted, as written on 2026-11-14. The specification has not been edited; the owner decides each change.
**Mode:** consolidated report, all review rubrics.
**Date:** 2026-11-14.
**Reading list given to reviewers (except stage 6):** the eight files the specification links, plus AGENTS.md, the spike's `relief_style.py` and `build_base.sh`, the Android convention plugins and `DependencyRules.kt`, `android/app/build.gradle.kts`, the version catalogue, the `android` workflow, `dependabot.yml`, the content schema, `validate_content.py` and `pyproject.toml` (owner, 2026-11-14).

## Stages run

| Stage | Reviewers | Report | Result |
| --- | --- | --- | --- |
| 5. Initial zoom out | zoom-out, inventive-catalyst | [zoom_out_report.md](zoom_out_report.md) | 0 🔴, 17 🟡, 14 🟢 |
| 6. Comprehension test | comprehension | [comprehension_report.md](comprehension_report.md) | 1 🔴, 9 🟡, 10 🟢 |
| 7. Critic review | design, structural, style | [critic_report.md](critic_report.md) | 1 🔴, 21 🟡, 18 🟢 |
| 8. Intermediate zoom out | zoom-out | [intermediate_zoom_out_report.md](intermediate_zoom_out_report.md) | 91 findings merged into 13 items (1 🔴, 11 🟡, 1 🟢); triage below |
| 9. Implementation readiness | implementation-readiness, codebase-details, style | [implementation_readiness_report.md](implementation_readiness_report.md) | about 2 🔴, 30 🟡, 20 🟢 after merging |

**Notes:**

- Every reviewer completed. Stage 9 stopped once at the usage limit and was resumed from where it stopped.
- Stage 8 rated two earlier Blockers as Major: CO-1 (decision 13's items are listed in ADR 0009, which the comprehension reviewer did not read) and ST-1 (most risks are already accepted in the brief). It also found CO-17, CO-18 and part of ZO-7 wrong.
- Stage 9 challenged three stage 8 fixes that would cause defects if applied literally (IR-3, IR-10, IR-12) and one that leaves a gap (IR-14). The action items below use the corrected versions.

## Overall verdict

**Needs revision before implementation.** The design holds: no reviewer reopens M2, the `benchmark` build type, Natural Earth beyond the extent, edits before smoothing, or the private trial settings. But an engineer could not start yet:

- **Step 1 cannot run as written:** the commands and their inputs do not connect.
- **Two items contradict the spike's code** they claim to follow, the style steps and the device measures, so the trial would not match build 11.
- **Several correctness gaps are invisible to the planned tests:** sea cells becoming land, a false coast at the data's edge, and seams from the encoder and the warp.
- **The file-by-file plan predates the review:** about 25 of its 57 items need rewriting.

Most fixes **remove** code or text. The image loses the tool code and `image.lock`. The two block stages become one. The Kotlin PMTiles reader goes, and so do `tools/common`, the `report` command and the resumable downloader. The revision should come out shorter than the current 736 lines.

## Owner decisions needed

| # | Question | Recommendation |
| --- | --- | --- |
| D1 | **Build limits.** What wall-time limit and temporary-disk limit should one full build of the trial box meet, so step 3's "stop if the numbers are wrong" can be checked? The other choice is "judged from the step 3 report" (ZI-5). | Set two numbers now, for example 12 hours and 120 GB of temporary disk. Step 3 extrapolates from 10% of the box and stops if either would be exceeded. |
| D2 | **ADR 0007 note.** ADR 0007 decision 10 allows unsigned loading only in *debug* builds. The `benchmark` build type is not a debug build, so may ADR 0007 get a dated note allowing it to load unsigned trial map files (CD-7)? | Yes. It writes down the `benchmark` decision you already took (A3 stays rejected), and AGENTS.md requires the higher-ranked ADR to be fixed in the same change. |
| D3 | **Getting files onto devices.** Should the trial files reach the phones by `adb push` into the benchmark app's own files folder, with the in-app folder import kept only as a fallback if `adb push` fails on a device (ZI-11)? | Yes. It removes the importer and halves storage on each device, but you would run one `adb` command per device. If you'd rather not use `adb`, keep the import as the main path. |
| D4 | **Approving the test edits.** AGENTS.md says only you mark anything `approved`. Should the test edits be committed as `draft`, with you setting `approved` at the step 1 checkpoint (ZI-12)? | Yes. AGENTS.md already settles this; the specification only has to follow it. |

**To note, no decision needed:**

- **The valley probe changes input.** It runs on Copernicus GLO-90 (unedited, 90 m) inside the image, instead of the edited 30 m terrain, which does not exist in step 1 (ZI-1). Edits do not change the probe's memory use.
- **The tile stage may restart instead of resuming.** If step 1 shows that GDAL's tiler does not write each tile atomically, an interrupted tile stage restarts from the beginning (ZI-2, IR-14). M2 is unchanged. It becomes your decision only if step 3 shows that restarting is too slow.
- **Two of my calls are reversed.** `tools/common` is dropped: the strict YAML loader lives in `seerah_map` until a second user exists. `run.py` becomes the place that runs the git checks and resolves the image (ZI-3, ZI-12).

## Action items

| ID | Item | Raw findings |
| --- | --- | --- |
| A1 | **Connect inputs to commands (Blocker).** `fetch --profile P` downloads only P's cells and Natural Earth. On its first run it writes P's lock to `/out/<profile>.lock`, never into the read-only private mount, and prints "copy to `seerah-content/map/trial/` and commit". Later runs verify against the lock. `edits.yaml` and `<profile>.lock` sit beside each profile; the windows get their own small locks that share the download cache. `build` builds `base.pmtiles` before the work area is removed. The valley probe is two documented commands on GLO-90 under the memory cap, with no `probe-valleys` command. | ZI-1, IR-3, ZO-1, ZO-2, CO-3, CO-4, CO-5, SY-2, IC-7 (cut) |
| A2 | **One block stage and a tile stage that recovers.** Stages 1 and 2 merge into one block stage: read with a border, apply edits, mask, style, write the styled block and a mask block. The border is computed from the style border, the mask smoothing and the largest feather (plus half a carve's width). The tiler writes `tiles.done` when it finishes; a tile folder without it is deleted, or resumed if step 1 shows GDAL writes tiles atomically. Work areas coexist by fingerprint (G4 and `test_workarea` reworded), and the resume test also kills the tiler. A second double build compares one block against many blocks on decoded tiles, which catches warp, mask and edit seams. | ZI-2, IR-13, IR-14, ZO-9, IC-2, IC-4, IC-5, CO-8 |
| A3 | **Toolchain-only image.** The image holds GDAL, tippecanoe (pinned by commit), go-pmtiles, WhiteboxTools and the Python packages, and is tagged by a defined hash of the Dockerfile and `requirements.txt`; CI pushes the tag if it is missing. Code runs from the read-only `/src` mount through `PYTHONPATH`. `image.lock` and the bot pull request go. `run.py` runs the git checks on the host, uses `--security-opt label=disable` instead of `:Z`, and `--network none` except for `fetch`. State how Python packages are installed in the GDAL image, and that tests run inside the image. | ZI-3, IR-4, IR-5, IR-6, CD-10, CD-11, SY9-5, ZO-3, ZO-17, IC-1, DE-10 |
| A4 | **Machine settings out of the fingerprint.** The memory cap is a launcher flag, and the worker count comes from cgroup `memory.max` and a per-worker flag, so lowering them after an out-of-memory kill keeps finished blocks. Thread variables are set before numpy and GDAL load in each worker. The run report records `memory.peak` (labelled "includes page cache"), the `oom_kill` count, the largest worker's RSS, and the work area's size after tiling. The `report` command goes. | ZI-4, IR-7, CD-14, ZO-8, ZO-11, DE-3 |
| A5 | **Decision 13's items with pass rules.** Add a table copied from ADR 0009 decision 13 with columns item, step, build or variant, and pass rule: item 11 quotes ADR 0002's numbers; item 1 flags 2 GiB; the rest say "owner judges". Item 4 reads "if (a) and (c) both fail, stop; (b) gets its own specification." Lowest frame rate is the minimum over all runs, and the first cold run is reported separately. The owner's five failure criteria are copied word for word. G9's check is a test that trial profiles produce `trial: true`. The D1 limits go into step 3's stop rule. | ZI-5, CO-1, CO-6, DE-7, DE-8, DE-9, ST-2, SY-3, SY-4, SY-5 |
| A6 | **Known risks and accepted costs**, as six to eight bullets, each with its trigger and the step that checks it: 2 GiB; M2's temporary tile folder and tile restart; edits narrower than about 500 m vanish; the blur's ground spread varies about 13% by latitude; trust-on-first-use hashes are lost if the cache volume is pruned; one commit per tuning try (decision 8); the Baseline Profile plugin's own build type. | ZI-6, ST-1, DE-1, ZO-16 |
| A7 | **Terms, counts and zoom convention.** Define once: camera bounds = the box; data extent = the box plus a 1° margin = the 616 locked cells. Recompute blocks and pixels with the margin. A "Zoom conventions" paragraph: build zooms are 256-pixel tile zooms; style, fade, camera and path zooms are MapLibre zooms; the relief source uses `tileSize` 256. List the three devices with widths in dp, and recompute the Natural Earth zoom table. One name for the block setting. The coverage formula becomes a unit test. | ZI-7, SY9-2, ZO-7, CO-2, CO-15, DE-4 |
| A8 | **Style C exactly as build 11.** Write the steps from `relief_style.py`: `round(d × bands) / bands` (four levels for three bands); shade clipped to [0, 1]; aspect = atan2(−gx, gy) with rows north to south; band-blur radius 4 (or state the difference); alpha truncated. One function `darkness(…, banded)`: the painted path bands, while option (c)'s value path stores continuous darkness (ADR 0009 decision 4). Sea is set to 0 last, then crop, then per-pixel encoding, so the band blur runs inside the border and cannot seam. Blurs are in pixels at zoom 12, converted for other zooms. Calibration: exaggeration = 8 × cos 23° ≈ 7.36, confirmed by comparing pixels. Add `contrast`. A regression fixture: `relief_style.py` run once on a small synthetic DEM, committed under 1 MB, with `darkness(banded=True)` at latitude 0 and exaggeration 8 matching it within 1/255. Option (c)'s ramp: thresholds 42.5, 127.5 and 212.5, ink alpha 0, 0.2, 0.4 and 0.6, `step` or else `interpolate linear`, recorded in step 1. | CD-1, IR-10, IR-11, SY9-4, ZI-8, IC-6, DE-6, CO-7, CO-12 |
| A9 | **Sea cells and the data's edge.** Only cells marked `absent: true` in the lock read as ocean (mask) and 0 m (elevation). Pixels outside the data extent are nodata, and contouring and polygonising run on the data-extent window only. Natural Earth is clipped to the data extent shrunk by a few pixels. The example gets an absent neighbour and a window edge, and one assertion that no coast vertex lies on the window edge. | IR-12, ZI-9, DE-2, SY-7 |
| A10 | **Base layer from the spike's fixes.** Mask smoothing defined (radius in pixels at zoom 12; the output is a byte 0–255). Coast drawn as `gdal_contour` lines at the midpoint, not from polygon outlines. Land polygons cut on the block grid before tippecanoe, with the spike's proven tippecanoe settings. Zoom bands 0–2, 3–5 and 6–max. Natural Earth pinned to a fixed release, not a moving branch. The trial marker goes in each output folder's `metadata.json`. G3 limited to relief; a missing vector tile shows the sea background, which is correct. | ZI-10, IR-8, IR-17, IR-22, CD-12, IC-3, DE-5, SY-1, CO-3 |
| A11 | **A smaller trial app, measured like the spike.** Each output folder `<profile>-<variant>-<fp8>/` holds the relief, `base.pmtiles`, `metadata.json` and a private `camera-path.json`, read with `org.json`. The Kotlin PMTiles reader, its test and its fixture go. `adb push` is the main transfer (D3). Measures follow `Stats.kt`, rewritten, not copied: a 30-gap window of 1–250 ms gaps, published once a second, lowest = the minimum published value. Memory uses `Debug.getPss()` and open time `Process.getStartElapsedRealtime()`. Overlay updates don't recompose on every frame. `measurements.md` line 7 is marked out of date. `MapCamera` is added to the signature. Camera limits come from `camera-path.json`. One word each for "variant" and "view". The release check finds `aapt2` through `ANDROID_HOME` and fails if it checked nothing. A post-trial deletion list is added. | ZI-11, CD-2, IR-15, IR-16, IR-19, CD-9, CD-19, SY9-1, ZO-4, ZO-5, ZO-14, CO-9, CO-10, DE-11, IC-8 |
| A12 | **Less tool code.** `fetch` downloads, hashes and renames one file at a time. The strict YAML loader moves into `seerah_map`. `purpose` is the constant `trial`, and `lower` goes. Edits are rasterised with GDAL (`gdal.Rasterize`) and distances computed in pixels converted to metres per row. The schema checks `review == "approved"`, with `sources` optional in `trial-0`. The cross-file `$ref` resolves through a bundled copy of the content schema's `$defs`. One schema path. `lock.schema.json` goes, and the resume test kills the process, with no test hook in production code. | ZI-12, IR-9, IR-18, CD-6, SY9-3, ZO-6, ZO-10, ZO-12, IC-12, CO-16, DE-12 |
| A13 | **Android setup that passes `check buildHealth`.** Generalise `AndroidCommon.kt` to the common Android extension so the library plugins reuse it. Give `:core:map` the dependency list `buildHealth` accepts. Define the types item 38 uses. Remove the reference to a non-existent ADR 0004 comment. Add the ADR 0007 note (D2) and the ADR 0004 note to the file list, with the other documents that must change. Drop the completed `.gitignore` item. Merge the notices into the existing table, and give MapLibre the right row. | CD-3, CD-4, CD-5, CD-7, CD-8, CD-15, CD-16, CD-17, IR-21, SY9-11 |
| A14 | **Rewrite the implementation plan, then the editorial pass.** Rebuild items 1–57 from the revised design with an exact file list and phases that each leave the code building and tested. Then: one launcher name; matching fingerprint lists; one CI job name; reasons for A8 and A17; calibrated claims; a three-line list of ADR changes under the Overview; numbered step-1 checks with fallbacks; external references for each "fact this plan rests on"; Additional Resources and Future Improvements as H2s; and a redrawn components diagram. Also fix the hedges, the settings with no value, and the paths not marked host or container. | IR-1, IR-2, IR-20, IR-23, IR-24, CD-13, CD-18, ZI-13, SY9-6 to SY9-10, SY9-12 to SY9-18, ZO-13, CO-11, CO-13, CO-14, CO-19, ST-3 to ST-7, ST-9, SY-6 |

**Cut, as heavier than one publisher needs (stage 8, accepted here):**

- ancestor-chain invalidation of tiles (IC-2's fix);
- multi-variant full builds (IC-5);
- automatic retry after an out-of-memory kill (IC-11);
- per-second memory sampling;
- free-disk pre-checks;
- the opt-in annotation and a separate diagnostics listener in `:core:map` (IC-10, DE-13);
- cool-down protocols and temperature display;
- automated checks of parent against child tiles and of coast vertices on the frame, except A9's single assertion.

**To the implementation or the trial (step 1 checks):**

- the filesystem behind Docker's volumes;
- whether the GHCR package is public;
- whether average resampling weights pixels by alpha;
- Licensee against MapLibre;
- `adb push` on each device;
- whether a relief source's `maxzoom` is honoured on `pmtiles://`;
- whether `color-relief` accepts `step`;
- whether GDAL writes tiles atomically.

## Traceability

Every raw finding maps to one of A1–A14, D1–D4, "cut" or "step 1 check".

- **Stages 5 to 7 (91 findings):** mapped through stage 8's triage table ([intermediate_zoom_out_report.md](intermediate_zoom_out_report.md), "Triage of all raw findings"), where each ID is K, S, O or C under one of ZI-1 to ZI-13. Each ZI item maps to an action item as follows:
  - ZI-1 → A1;
  - ZI-2 → A2;
  - ZI-3 → A3;
  - ZI-4 → A4;
  - ZI-5 → A5 and D1;
  - ZI-6 → A6;
  - ZI-7 → A7;
  - ZI-8 → A8;
  - ZI-9 → A9;
  - ZI-10 → A10;
  - ZI-11 → A11 and D3;
  - ZI-12 → A12 and D4;
  - ZI-13 → A14.

  Findings stage 8 triaged C are in "Cut"; S findings are in the step 1 checks.
- **Stage 9, implementation readiness:**
  - IR-1, IR-2 → A14;
  - IR-3 → A1;
  - IR-4, IR-5, IR-6 → A3;
  - IR-7 → A4;
  - IR-8 → A10;
  - IR-9 → A12;
  - IR-10, IR-11 → A8;
  - IR-12 → A9;
  - IR-13, IR-14 → A2;
  - IR-15, IR-16 → A11;
  - IR-17 → A10;
  - IR-18 → A12;
  - IR-19 → A11;
  - IR-20 → A14;
  - IR-21 → A13;
  - IR-22 → A10;
  - IR-23, IR-24 → A14.
- **Stage 9, codebase details:**
  - CD-1 → A8;
  - CD-2 → A11;
  - CD-3, CD-4, CD-5 → A13;
  - CD-6 → A12;
  - CD-7 → A13 and D2;
  - CD-8 → A13;
  - CD-9 → A11;
  - CD-10, CD-11 → A3;
  - CD-12 → A10;
  - CD-13 → A14;
  - CD-14 → A4;
  - CD-15, CD-16, CD-17 → A13;
  - CD-18 → A14;
  - CD-19 → A11.
- **Stage 9, style:**
  - SY9-1 → A11;
  - SY9-2 → A7;
  - SY9-3 → A12;
  - SY9-4 → A8;
  - SY9-5 → A3;
  - SY9-6 to SY9-10 → A14;
  - SY9-11 → A13;
  - SY9-12 to SY9-18 → A14.
