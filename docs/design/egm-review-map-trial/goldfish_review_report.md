Status: resolved (all 14 fixes applied to the specification on 2026-11-14)

# Goldfish Annotated Review Report

**Target:** `docs/design/map-trial-build.md` (full file, lines 1–736)
**Findings:** the consolidated review's owner decisions D1–D4 and action items A1–A14 ([consolidated_review_report.md](consolidated_review_report.md)), each tracing to the raw findings in the five stage reports.

**Owner decisions** (owner, 2026-11-14, "your recommendations"):

- **D1:** one full build of the trial box must stay within 12 hours of wall time and 120 GB of temporary disk; step 3 stops if its extrapolation exceeds either.
- **D2:** ADR 0007 gets a dated note under decision 10 allowing the `benchmark` build type to load unsigned trial map files. It is never published.
- **D3:** trial files reach the devices by `adb push` into the benchmark app's external files folder. The in-app folder import is a fallback, built only if `adb push` fails on a device in step 1.
- **D4:** test edits are committed as `draft`; the owner sets `approved` at the step 1 checkpoint.

**Summary:** 14 PROPOSE FIX, 0 CONTEST, 0 ESCALATE, 0 DECLINE.

**What I checked:** stage 8 already contested the earlier findings that were wrong (CO-17, CO-18, part of ZO-7) and lowered two Blockers (CO-1, ST-1), and stage 9 corrected three of stage 8's fixes. I checked each remaining item against the specification and the repository files the reviewers read, and found none I can contest with evidence.

## Prioritized Findings & AI Suggestions

### [Issue 1] [`egm-zoom-out`, `egm-implementation-readiness`] - Severity: Blocker (A1)

- **Affected Scope:** [Lines 114–171](../map-trial-build.md#L114-L171) (Commands, Inputs, Valleys), plan items 7, 10, 11, 22, 24, 31.
- **Discrepancy Description:** step 1 cannot run. `lock` downloads the whole box, the edits and lock have no path from `build`, `probe-valleys` and `base.py` read a terrain file that does not exist yet, no command fetches Natural Earth, and the lock would be written into a read-only mount.
- **Rationale:** ZI-1, IR-3, ZO-1, ZO-2, CO-3, CO-4, CO-5, SY-2.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** Each gap is visible in the specification. The owner's order (brief line 167) limits step 1's downloads to the small windows.
- ***Proposed Edit:***
  - Location: the Commands table, Inputs, Valleys, the Order of work, and items 7, 10, 11, 22, 24 and 31.
  - Change:
    - Delete `lock`; `fetch --profile P` downloads P's cells and the pinned Natural Earth files.
    - On its first run, `fetch` writes `/out/<profile>.lock` and prints "copy to `seerah-content/map/trial/` and commit"; later runs verify against the lock.
    - `edits.yaml` and `<profile>.lock` sit beside each profile, and each window has its own lock, sharing the download cache.
    - `build` builds `base.pmtiles` before removing the work area.
    - Replace `probe-valleys` with two documented commands run in the image under the memory cap: warp GLO-90 to 90 m and 180 m, then run WhiteboxTools. "Fits" means no out-of-memory kill; the result goes in `step-1.md`.
    - Reword the security line to name this as the one unpinned read.

### [Issue 2] [`egm-codebase-details`] - Severity: Blocker (A8)

- **Affected Scope:** [Lines 143–146](../map-trial-build.md#L143-L146) and plan items 16, 17 and 19.
- **Discrepancy Description:** the style steps contradict `relief_style.py`:
  - three band levels instead of four;
  - shade clipping omitted;
  - no aspect formula;
  - the band-blur radius differs;
  - alpha is rounded instead of truncated.

  Option (c) stores banded darkness, though ADR 0009 decision 4 says continuous. ZI-8's move of banding into the encoder would add a seam. The calibration rule, `contrast` and option (c)'s ramp are not defined.
- **Rationale:** CD-1, IR-10, IR-11, SY9-4, ZI-8, IC-6, DE-6, CO-7, CO-12.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** `relief_style.py` lines 37, 51–59 and 63 show the spike's formulas, and G8 requires build 11's look.
- ***Proposed Edit:***
  - Location: Stage 2 and items 16, 17, 19 and 26.
  - Change:
    - **Steps from the spike:** write the steps from `relief_style.py`: `round(d × bands) / bands`; shade clipped to [0, 1]; aspect = atan2(−gx, gy) with rows running north to south; band-blur radius 4; alpha truncated.
    - **One function:** `darkness(…, banded)`. The painted path passes `banded=True`; the value path stores continuous darkness. Sea is set to 0 last, then the block is cropped and encoded per pixel.
    - **Units and calibration:** blurs are given in pixels at zoom 12 and converted for other zooms. Exaggeration = 8 × cos 23° ≈ 7.36, confirmed by comparing pixels with build 11. Add `contrast` to the settings.
    - **Regression fixture:** `relief_style.py` run once on a small synthetic DEM, committed under 1 MB; `darkness(banded=True)` at latitude 0 and exaggeration 8 must match it within 1/255.
    - **Option (c)'s ramp:** thresholds at 42.5, 127.5 and 212.5; ink alpha 0, 0.2, 0.4 and 0.6; written with `step`, or `interpolate linear` if `step` is rejected, recorded in step 1.

### [Issue 3] [`egm-codebase-details`] - Severity: Blocker (A11)

- **Affected Scope:** [Lines 184–191](../map-trial-build.md#L184-L191) and plan items 43 and 44.
- **Discrepancy Description:**
  - **Measures:** frame rate, memory and open time differ from the spike's `Stats.kt`, so G6's numbers would not compare with build 11's.
  - **Trial code:** the trial app carries a Kotlin PMTiles reader and fixture, an importer, folder layouts that disagree, camera stops from metadata that does not hold them, no `MapCamera` in the signature, and no removal list.
  - **Release check:** it depends on `aapt2` being on CI's PATH, and it can pass without checking anything.
- **Rationale:** CD-2, IR-15, IR-16, IR-19, CD-9, CD-19, SY9-1, ZI-11, ZO-4, ZO-5, ZO-14, CO-9, CO-10, DE-11, IC-8.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** `Stats.kt` lines 25–60 show the spike's method. `measurements.md` line 7 is out of date and the plan copied it. D3 settles the file transfer.
- ***Proposed Edit:***
  - Location: The app, items 38, 41–44 and 47, and Rollout and removal.
  - Change:
    - **One output layout:** `<profile>-<variant>-<fp8>/` holding the relief file, `base.pmtiles`, `metadata.json` and a private `camera-path.json`. `adb push` copies these folders (D3), the app reads the JSON with `org.json`, and `PmtilesMetadata.kt`, its test and its fixture are deleted.
    - **Measures, written anew from `Stats.kt`:** a 30-gap window of 1–250 ms gaps, published once a second; lowest is the minimum published value. Memory uses `Debug.getPss()` and open time `Process.getStartElapsedRealtime()`. The overlay does not recompose on every frame. Mark `measurements.md` line 7 as out of date.
    - **`:core:map` interface:** add `MapCamera` to `SeerahMap`'s signature; camera limits come from `camera-path.json`.
    - **Wording:** use "variant" for a build output and "view" for a style over it.
    - **Release check:** find `aapt2` through `ANDROID_HOME`, and fail if it found no manifest.
    - **Removal:** add a post-trial deletion list to Rollout and removal.

### [Issue 4] [`egm-zoom-out`, `egm-inventive-catalyst`, `egm-implementation-readiness`] - Severity: Major (A2)

- **Affected Scope:** [Lines 131–148](../map-trial-build.md#L131-L148) and plan items 12, 13, 18–20, 26 and 27.
- **Discrepancy Description:**
  - **Stages:** two block stages, the larger storing about 19 GB.
  - **Border:** the terrain border is undefined.
  - **Recovery:** a half-written tile can feed its ancestors, and the tile stage has no resume marker.
  - **Work areas:** the exclusive work area blocks other builds.
  - **Seam test:** it covers only synthetic arrays.
- **Rationale:** ZI-2, IR-13, IR-14, ZO-9, IC-2, IC-4, IC-5, CO-8.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** Each gap is visible in the specification. The fix removes a stage and an exclusivity rule.
- ***Proposed Edit:***
  - Location: Stages 1–3, Temporary files, G4, and items 12, 13, 18–20, 26 and 27.
  - Change:
    - **One block stage:** it reads with a border, applies edits, computes the mask and the style, and writes the styled block plus a mask block.
    - **Border:** the style border, plus the mask smoothing, plus the largest feather (and half a carve's width).
    - **Tile recovery:** `tiles.done` is written when tiling succeeds. A tile folder without it is deleted and retiled, or resumed if step 1 shows GDAL writes tiles atomically.
    - **Work areas:** they coexist by fingerprint, and `clean` lists them with their sizes; reword G4 and `test_workarea` to match.
    - **Tests:** add a kill point during the tiler, and a double build comparing one block against many blocks on decoded zoom-12 tiles.

### [Issue 5] [`egm-zoom-out`, `egm-inventive-catalyst`, `egm-implementation-readiness`] - Severity: Major (A3)

- **Affected Scope:** [Lines 101–112](../map-trial-build.md#L101-L112) and plan items 1–5 and 45.
- **Discrepancy Description:**
  - **Code in the image:** the image holds the tool code, but the run records the commit of the mounted checkout.
  - **Publish loop:** the `image.lock` bot pull request stalls, because a pull request opened with `GITHUB_TOKEN` does not start the required check, and it sits inside the workflow's trigger path.
  - **Open choices:** how Python packages are installed in the GDAL image, where tests run, and the image tag.
  - **Access:** the container has more than it needs.
- **Rationale:** ZI-3, IR-4, IR-5, IR-6, CD-10, CD-11, SY9-5, ZO-3, ZO-17, IC-1, DE-10.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** Each gap is visible in the specification. The fix deletes `image.lock` and the bot.
- ***Proposed Edit:***
  - Location: Where it runs, and items 1–5 and 45.
  - Change:
    - **Image contents:** the toolchain only, with tippecanoe pinned by commit and Python packages installed with `pip install --require-hashes --break-system-packages` into the image's system Python.
    - **Tag:** `sha256` of the Dockerfile and `requirements.txt` together, first 16 hexadecimal characters. CI builds and pushes it when the tag is missing.
    - **Code:** runs from the read-only `/src` mount, with `PYTHONPATH=/src/tools/map/src`. Tests run in the image.
    - **`run.py`:** computes the tag, pulls the image and passes the digest in; runs the git checks on the host; uses `--security-opt label=disable`; uses `--network none` except for `fetch`; mounts only `seerah-content/map/`.
    - **Removed:** `image.lock`, the bot pull request and `pull-requests: write`.

### [Issue 6] [`egm-zoom-out`, `egm-design`, `egm-implementation-readiness`] - Severity: Major (A4)

- **Affected Scope:** [Lines 131–133, 165–167](../map-trial-build.md#L131-L167) and plan items 4, 13 and 23.
- **Discrepancy Description:**
  - **Fingerprint:** the memory settings are in it, so lowering them after an out-of-memory kill discards every finished block.
  - **Thread variables:** set too late to take effect.
  - **Memory reading:** `memory.peak` includes page cache.
  - **Disk reading:** sampling after each block misses the tile stage, where disk use peaks.
- **Rationale:** ZI-4, IR-7, CD-14, ZO-8, ZO-11, DE-3.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** Each gap is visible in the specification, and the first one hits the owner's reliability criterion.
- ***Proposed Edit:***
  - Location: Blocks, the run report, and items 4, 13 and 23.
  - Change:
    - **Settings:** `--memory-mib` and `--per-worker-mib` become launcher flags, outside the fingerprint. Workers = `memory.max` divided by the per-worker value.
    - **Thread variables:** set in `run.py`'s environment, before numpy and GDAL load.
    - **The run report:** `memory.peak`, labelled "includes page cache"; the `oom_kill` count; the largest worker's RSS; and the work area's size after tiling.
    - **Removed:** the `report` command.
    - **G2's host check:** becomes an owner step: read `journalctl -k` after the full build.

### [Issue 7] [`egm-comprehension`, `egm-design`, `egm-structural`, `egm-style`] - Severity: Major (A5)

- **Affected Scope:** [Lines 39–53, 202–210](../map-trial-build.md#L39-L210) (Goals, Order of work).
- **Discrepancy Description:**
  - **Items:** decision 13's items are cited by number but never listed, and they have no pass rules.
  - **Limits:** build cost has none.
  - **Failure criteria:** they are paraphrased with parts missing.
  - **G9:** its check is deferred.
- **Rationale:** ZI-5, CO-1, CO-6, DE-7, DE-8, DE-9, ST-2, SY-3, SY-4, SY-5.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** Visible in the specification. D1 supplies the limits.
- ***Proposed Edit:***
  - Location: after Goals.
  - Change:
    - **A table** of ADR 0009 decision 13's eleven items, with columns item, step, build or variant, and pass rule:
      - item 1: size per zoom, every file under 2 GiB;
      - item 6: within 12 hours and 120 GB of temporary disk (D1);
      - item 11: ADR 0002's numbers quoted;
      - item 4: "if (a) and (c) both fail, stop; option (b) gets its own specification";
      - the others: owner judges.
    - **Frame rates:** lowest is the minimum over all runs, with the cold first run reported separately; typical is the median of the published readings.
    - **Failure criteria:** copy the owner's five word for word.
    - **G9:** a test that a trial profile produces `trial: true`.
    - **Step 3:** stops if its extrapolation exceeds D1's limits.

### [Issue 8] [`egm-structural`, `egm-design`, `egm-zoom-out`] - Severity: Major (A6)

- **Affected Scope:** [Lines 334–338](../map-trial-build.md#L334-L338) (Non-Functional Requirements).
- **Discrepancy Description:** the risks the owner's decisions accept are recorded only in the brief.
- **Rationale:** ZI-6, ST-1, DE-1, ZO-16.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** A human reader of the specification needs them; this records decisions without reopening them.
- ***Proposed Edit:***
  - Location: new section "Known risks and accepted costs", after Non-Functional Requirements.
  - Change: seven bullets, each with its trigger and the step that checks it:
    - the 2 GiB file limit;
    - M2's temporary tile folder and the tile restart;
    - edits narrower than about 500 m vanish;
    - the blur's ground spread varies about 13% by latitude;
    - trust-on-first-use hashes are lost if the cache volume is pruned;
    - one commit per tuning try (decision 8);
    - the Baseline Profile plugin's own build type.

### [Issue 9] [`egm-zoom-out`, `egm-comprehension`, `egm-design`, `egm-style`] - Severity: Major (A7)

- **Affected Scope:** [Lines 75, 133, 154–163](../map-trial-build.md#L75-L163).
- **Discrepancy Description:** box, extent, margin and ring are undefined against each other; the counts cover the box only; the zoom convention and `tileSize` are not stated; the devices are not listed; the block setting has two names.
- **Rationale:** ZI-7, SY9-2, ZO-7, CO-2, CO-15, DE-4.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** Visible in the specification.
- ***Proposed Edit:***
  - Location: the Facts list, Blocks, Land beyond the extent, and new "Terms" and "Zoom conventions" paragraphs.
  - Change:
    - **Terms:** camera bounds = the box (12–32° N, 34–60° E); data extent = the box plus a 1° margin = the 616 locked cells (11–33° N, 33–61° E). Drop the separate ring.
    - **Counts:** recompute the blocks and pixels for the data extent.
    - **Zoom conventions:** build zooms are 256-pixel tile zooms; style, fade, camera and path zooms are MapLibre zooms; the relief source uses `tileSize` 256.
    - **Devices:** list the three, with their screen widths in dp, and recompute the Natural Earth zoom table from them.
    - **Names:** one name for the block setting, `block_zoom`.
    - **Coverage formula:** a unit test.

### [Issue 10] [`egm-design`, `egm-implementation-readiness`] - Severity: Major (A9)

- **Affected Scope:** [Lines 137, 152–158](../map-trial-build.md#L137-L158) and plan items 14, 18, 22 and 29.
- **Discrepancy Description:**
  - **Absent cells:** they read as "no water", so 87 sea cells become land.
  - **The data's edge:** treating every missing pixel as ocean would draw a false coast across land where the data ends.
- **Rationale:** IR-12, ZI-9, DE-2, SY-7.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** The mask rule at line 137 and a VRT's fill value produce the first; IR-12 shows the second.
- ***Proposed Edit:***
  - Location: Stage 1 (now the block stage), the base layer, and items 14, 18, 22 and 29.
  - Change:
    - **Absent cells:** only cells marked `absent: true` read as ocean in the mask and 0 m in the elevation.
    - **The data's edge:** pixels outside the data extent are nodata, and contouring and polygonising run on the data-extent window only.
    - **Natural Earth:** clipped to the data extent shrunk by a few pixels.
    - **The example:** gets an absent neighbour and a window edge, plus one assertion that no coast vertex lies on the window edge.

### [Issue 11] [`egm-inventive-catalyst`, `egm-design`, `egm-codebase-details`] - Severity: Major (A10)

- **Affected Scope:** [Lines 152–158](../map-trial-build.md#L152-L158) and plan item 22.
- **Discrepancy Description:** the base layer repeats two of the spike's failures: coast lines taken from polygon outlines, and one very large polygon that tippecanoe drops. Mask smoothing, the zoom bands, Natural Earth's version and the trial marker are not defined.
- **Rationale:** ZI-10, IR-8, IR-17, IR-22, CD-12, IC-3, DE-5, SY-1, CO-3.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** The spike's findings (line 93, and line 271 on builds 21–22) and `build_base.sh` lines 19–49 record both failures and their fixes.
- ***Proposed Edit:***
  - Location: Base layer and item 22.
  - Change:
    - **Mask:** smoothing radius 4 px at zoom 12, written as a byte from 0 to 255.
    - **Coast:** `gdal_contour` at 128, not polygon outlines.
    - **Land:** cut on the block grid before tippecanoe, with the spike's flags (`--no-tile-size-limit --no-feature-limit --no-tiny-polygon-reduction`), one tileset per zoom band, joined.
    - **Zoom bands:** 0–2, 3–5 and 6 to the maximum.
    - **Natural Earth:** pinned to release tag v5.1.2 by commit (to be confirmed when locked).
    - **Trial marker:** in the folder's `metadata.json`.
    - **G3:** limited to relief.

### [Issue 12] [`egm-zoom-out`, `egm-design`, `egm-implementation-readiness`] - Severity: Major (A12)

- **Affected Scope:** plan items 8, 11, 15, 25, 27 and 28.
- **Discrepancy Description:** the tool carries more code than one publisher needs:
  - Range-resume downloads;
  - a second package for one function;
  - `purpose: release`;
  - `lower`;
  - a lock schema;
  - a test hook in production code.

  Also: edits have no rasterising library or distance unit, the `review` shape does not match the content schema, the cross-file `$ref` cannot resolve, and line 200 has the implementer approve edits.
- **Rationale:** ZI-12, IR-9, IR-18, CD-6, SY9-3, ZO-6, ZO-10, ZO-12, IC-12, CO-16, DE-12.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** The owner's top criterion is no unnecessary custom code. `content.schema.json` lines 7–9 show `review` as a string enum. AGENTS.md reserves `approved` for the owner (D4).
- ***Proposed Edit:***
  - Location: items 8, 11, 15, 25, 27 and 28, and the calls list.
  - Change:
    - **Downloads:** one file at a time; a failed download restarts.
    - **Packages:** the strict YAML loader goes in `seerah_map`, and `tools/common` is deleted.
    - **Schemas:** `purpose` is the constant `trial`; `lower` is dropped; `review == "approved"`; `sources` is optional in `trial-0`; the content schema's `$defs` are copied into `edits.schema.json` with a comment naming the source; one schema path; `lock.schema.json` is deleted.
    - **Edits:** rasterised with `gdal.Rasterize`, with distances computed from `scipy.ndimage.distance_transform_edt` in pixels times each row's ground size.
    - **Approval:** test edits are committed as `draft` (D4).
    - **Resume test:** kills the process; no test hook in production code.

### [Issue 13] [`egm-codebase-details`] - Severity: Major (A13)

- **Affected Scope:** plan items 33, 35, 37, 38, 49, 51, 52 and 53.
- **Discrepancy Description:**
  - `AndroidCommon.kt` is typed for the application module.
  - `:core:map`'s dependency list would fail `buildHealth`.
  - Item 38 uses types it never defines.
  - Item 35 cites an ADR 0004 comment that does not exist.
  - ADR 0007 needs a note (D2).
  - Some documents that must change are missing from the file list.
  - Item 49 is already done.
  - Item 51 would duplicate the notices table.
- **Rationale:** CD-3, CD-4, CD-5, CD-7, CD-8, CD-15, CD-16, CD-17, IR-21, SY9-11.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** These come from the repository files (`AndroidCommon.kt`, the application plugin, ADR 0007 line 78, `.gitignore`, `THIRD_PARTY_NOTICES.md`).
- ***Proposed Edit:***
  - Location: items 33, 35, 37, 38, 49 and 51–55, plus a new item for ADR 0007.
  - Change:
    - **`AndroidCommon.kt`:** generalise it to `CommonExtension`.
    - **`:core:map` dependencies:** state the `api` and `implementation` split that `buildHealth` accepts.
    - **Item 38:** define `MapColours` and `RampStep`.
    - **Item 35:** drop the non-existent comment.
    - **ADR 0007:** add an item for the dated note under decision 10 (D2).
    - **File list:** add the missing documents.
    - **`.gitignore`:** drop item 49.
    - **Notices:** add the rows to the existing notices table.

### [Issue 14] [all reviewers] - Severity: Major (A14)

- **Affected Scope:** [Detailed Implementation Plan](../map-trial-build.md#L356-L736) and editorial points across the file.
- **Discrepancy Description:** about 25 of the 57 plan items predate the review, and the plan has no phases. There are also editorial inconsistencies, hedges, settings with no value, and paths not marked as host or container paths.
- **Rationale:** IR-1, IR-2, IR-20, IR-23, IR-24, CD-13, CD-18, ZI-13, SY9-6 to SY9-10, SY9-12 to SY9-18, ZO-13, CO-11, CO-13, CO-14, CO-19, ST-3 to ST-7, ST-9, SY-6.
- ***AI Suggestion:*** PROPOSE FIX
- ***Justification:*** Visible in the specification. Without it, the plan and the revised design disagree.
- ***Proposed Edit:***
  - Location: the Detailed Implementation Plan, plus small edits elsewhere.
  - Change:
    - **The plan:** rebuild it from Issues 1–13, with an exact file list and three phases, each ending buildable and tested:
      - (1) tool core and the example in CI;
      - (2) `:core:map` and the `benchmark` trial;
      - (3) base layer and Natural Earth.
    - **Names and lists:** one launcher name, `run.py`; matching fingerprint lists; one CI job name.
    - **Reasons and claims:** give A8 a reason and correct A17's evidence; calibrate the claims at lines 69, 99, 146, 152 and 189.
    - **ADR changes:** a three-line list under the Overview (ADR 0009 decisions 3, 6 and 7; ADR 0004; ADR 0007).
    - **Step 1 checks:** numbered, each with a fallback and where its result is recorded.
    - **References and structure:** external references for each fact the plan rests on; Additional Resources and Future Improvements as H2s; a redrawn components diagram.
    - **Paths and settings:** paths marked as host or container; a value for every setting.
