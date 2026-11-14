# Stage 5: Initial zoom out, map trial specification

**Target:** `docs/design/map-trial-build.md` (lines 1–736; FeatureRange 1–355, DetailedRange 356–736)

**Standard:** `zoom-out-standard.md` (EGM v0.1.1)

**Reviewers:**

| Reviewer | Status | Blocker | Major | Nit |
| --- | --- | --- | --- | --- |
| `egm-zoom-out` | completed | 0 | 9 | 8 |
| `egm-inventive-catalyst` | completed | 0 | 8 | 6 |

Both reviewers read the full target and every GoldfishContext file. They treated the owner's decisions dated 2026-11-14 as given and reported only the risks those decisions create. Line numbers refer to the target file.

**Overlaps between reviewers.** Five issues were found by both reviewers. They are kept in each reviewer's section and cross-referenced, so each section reads on its own:

| Issue | Zoom-out | Inventive catalyst |
| --- | --- | --- |
| Step 1's data scope and the valley probe's input | ZO-1, ZO-2 | IC-7 |
| How the image is published and pinned (`image.lock` pull request) | ZO-3 | IC-1 |
| Fingerprint and output paths disagree between the two plans | ZO-13 | IC-5 |
| Camera path positions have no source | ZO-14 | IC-10 |
| SELinux `:Z` relabelling and environment checks dropped from the brief | ZO-17 | IC-14 |

---

## Zoom-out reviewer (`egm-zoom-out`)

**Verdict:** Needs revision. There are no Blockers. Nine Major findings: two data flows that cannot run as written, a CI loop that will stall, an unclear build extent, and custom code that goes beyond the spec's own G5.

### ZO-1. The valley probe cannot run as specified. 🟡 Major

- **Lines:** 169, 171, 206, 210, 507–510.
- **Problem:** `probe-valleys` resamples the edited elevation (`terrain.vrt`) over the whole extent. That file exists only inside a build's work area, and line 169 removes the work area when a build succeeds. Step 1 fetches only the small windows, yet line 206 has the probe run alongside step 1. The edited elevation for the whole extent exists only during step 4, and only until that build finishes.
- **Fix:** Decouple the probe from `build`. A memory and time probe needs neither the edits nor the 30 m data. Give `probe-valleys` its own input: GLO-90 from the `copernicus-dem-90m` bucket (already used by the spike, `tools/spike/build_base.sh` lines 57–60), warped by GDAL straight to 90 m and 180 m over the full box. Add those cells to the lock and say so in "Order of work". See also IC-7.

### ZO-2. `lock` forces a full-extent download in step 1. 🟡 Major

- **Lines:** 118–119, 206, 431, 433–436.
- **Problem:** `lock` hashes every cell in the extent plus a one-cell ring by downloading each present file once. That is about 1,058 files before the first small window can be built, which contradicts "`lock` and `fetch` for the small windows" in step 1. `lock` and `fetch` also overlap: both download, cache and hash.
- **Fix:** Merge them into one `fetch --profile X`. It downloads only the cells profile X needs, adds trust-on-first-use hashes for new cells to `sources.lock`, verifies cells already listed, and records absent cells from `tileList.txt`. The lock then grows from the small windows to the full box. See also IC-7.

### ZO-3. The `image.lock` bot pull request will stall. 🟡 Major

- **Lines:** 110, 399–402, 657.
- **Problem:** A pull request opened with `GITHUB_TOKEN` does not start `pull_request` workflows. `android` is a required check on `main` (`.github/workflows/android.yml` lines 7–9), so every digest-bump pull request waits indefinitely. It also needs the repository setting that lets Actions create pull requests, which is off by default. Each Dockerfile change costs two pull requests, and between them `run.py` and CI use a stale image.
- **Fix:** Drop the bot pull request and the committed digest. Tag the image with a hash of its inputs (Dockerfile, `requirements.txt`, the hashes of the pinned binaries). CI pushes the tag if it is missing. `run.py` computes the same tag, pulls it, and records the resolved digest in the fingerprint and the run report. See also IC-1.

### ZO-4. Trial-only code stays in the permanent tool and in `:core:map`, with no removal plan. 🟡 Major

- **Lines:** 15, 51, 165, 175–182, 338, 485, 490, 602, 641.
- **Problem:** The removal plan (line 338) deletes only the app's trial source set and the private settings. These trial-shaped parts stay: `--mode per-zoom` (the item 5 comparison), whichever encoder loses (`painted` or `value`), and `ReliefSource.Painted`/`Value` with the tunable `reliefFade` in `MapStyleSpec`. Once the owner decides items 3 and 5, the losing branches are dead code, which AGENTS.md forbids. G7's claim that `:core:map` has no trial types holds only by name, because its public interface is the trial's variant grid.
- **Fix:** Add a post-trial cleanup step to "Order of work" and "Rollout and removal": delete the losing mode and encoder and reduce `MapStyleSpec` to the chosen relief kind. Until then, mark the variant settings with a Kotlin opt-in annotation (for example `@TrialMapStyle`) so v1 callers cannot depend on them. Reword G7's check to cover this.

### ZO-5. A custom binary PMTiles reader in Kotlin, used only to name variants. 🟡 Major

- **Lines:** 188, 639–641, 650.
- **Problem:** `PmtilesMetadata.kt` parses the PMTiles v3 header and gzip metadata. Its test fixture is a PMTiles file built by the Python tool and committed to the Android tests, so a change on the Python side breaks the Android tests. The importer already keys variants by folder name (line 639), and line 641 builds variants from folders, which contradicts line 188.
- **Fix:** Have `pack` write a small `variant.json` beside each output (variant, mode, encoding, fingerprint, `trial`, camera stops). The trial screen reads it with the JSON parser it already needs. Delete `PmtilesMetadata.kt`, its test and the committed fixture.

### ZO-6. The file list breaks G5, which names that file list as its check. 🟡 Major

- **Lines:** 49, 433–436, 446, 503–505.
- **Problem:** G5 limits custom code to the style function, the encoders, edits, configuration and lock validation, and thin orchestration. The plan also adds a downloader with HTTP Range resume and parallel downloads (`fetch.py`, `urllib`), a block scheduler, a completeness checker and a per-block disk sampler. Code quality is one of the owner's failure criteria.
- **Fix:** Cut `fetch.py` to a sequential loop that downloads, hashes and renames, resuming per file only; a failed file restarts from zero (one cell is tens of MB). Drop Range resume and parallel downloads. Reword G5 to name everything that remains (scheduler, completeness check, base layer) so the goal can be checked against the file list.

### ZO-7. It is unclear whether the trial box is the camera bounds or the data extent; this changes the work by about 25%. 🟡 Major

- **Lines:** 35, 75, 133, 154, 162, 200, 515, 561.
- **Problem:** The schema and lines 200 and 561 treat 12–32° N, 34–60° E as `camera_bounds`, with a 1° data margin outside it. The stated figures only add up for the box alone: about 1,150 zoom-9 blocks (the reviewer estimates about 1,150 for the box and about 1,430 with the margin), 4.8 billion pixels at zoom 12, and 616 cells with the ring (22 × 28, which leaves no margin). With a 1° margin plus the ring, the lock is about 720 cells. Memory, disk, time and the measured size for item 1 ("margin included") all scale with this.
- **Fix:** State once which box is the camera bounds and which is the data extent. Recompute the cell, block and pixel counts from that, and use the same wording at lines 75, 133, 200 and 561.

### ZO-8. The launcher is "standard library only" but has to read a YAML profile. 🟡 Major

- **Lines:** 389, 394.
- **Problem:** `run.py` reads the memory budget from `coverage.yaml` on the host before starting the container. Python's standard library has no YAML parser, and `ruamel.yaml` exists only inside the image.
- **Fix:** Pass the memory cap as a launcher flag (`--memory-mib`). Inside the container, the tool reads cgroup `memory.max` and refuses to start if it differs from the profile's `memory_budget_mib`. The host stays standard-library only and no setting is duplicated silently.

### ZO-9. The separate terrain stage stores the whole extent as float32 and adds a second resume layer. 🟡 Major

- **Lines:** 135–141, 477–485, 500.
- **Problem:** Stage 1 writes about 4.8 billion float32 elevation pixels (about 19 GB uncompressed) only for stage 2 to read them back with a border. Line 138 already says edits stay within their footprint plus feather, so they can be applied on the bordered window directly. The other users of the stored terrain are the land mask (byte, small) and the valley probe, which ZO-1 removes. Disk is one of the owner's failure criteria, and the spec gives no temporary-disk estimate or free-space check before a build.
- **Fix:** Fuse stages 1 and 2 per block: read the DEM and Water Body Mask over the block plus the style and mask borders (warp error threshold 0), apply the edits, compute the mask and the style, then write the styled block and the byte mask block. Keep a stored edited-elevation raster only if the valley layer is specified later. Add a free-disk check before `build`, using step 3's measured ratio. See IC-4 for the border this needs.

### ZO-10. `tools/common/` is a second package for one function. 🟢 Nit

- **Lines:** 363, 537–542, 672, 682.
- **Problem:** One strict YAML loader brings its own `pyproject`, README, tests, a Dependabot entry, a Dockerfile install step and a CI path trigger. Its second user, ADR 0007's content build tool, does not exist yet.
- **Fix:** Put the loader in `seerah_map` for now and move it to a shared package when the content build tool lands.

### ZO-11. The `report` subcommand and per-block disk sampling are unnecessary. 🟢 Nit

- **Lines:** 121, 167, 505.
- **Problem:** `report` only prints `report.json`. Sampling the work area's size after each of about 1,150 blocks walks a growing directory and never sees GDAL's tile stage, where disk use peaks. Nothing is deleted before success, so the peak is the work area's size just before removal.
- **Fix:** Drop `report`; the JSON is readable as it is. Measure the work area once, after the tile stage and before it is removed.

### ZO-12. Some schema values and operations exist only for the future. 🟢 Nit

- **Lines:** 127, 421, 456, 515, 529.
- **Problem:** `purpose` allows `release` only so that `config.py` can refuse it. The `lower` operation is `raise` with a negative amount, and no test edit uses it.
- **Fix:** Make `purpose` a constant `trial` in the schema, so the schema does the refusing, and drop `lower`. Add both back when packaging and real edits arrive.

### ZO-13. The Technical Plan and the Detailed Plan already disagree in several places. 🟢 Nit

- **Lines:** each pair gives the Technical Plan line first.
  - Launcher name: `tools/map/run` (112) against `run.py` (365, 387).
  - Fingerprint: leaves out `mode` (131) against includes it (441).
  - Output: `relief.pmtiles` / `relief-value.pmtiles` (150) against `<variant>-<mode>/relief.pmtiles` (495).
  - Variant naming: from file metadata (188) against from folder names (641).
  - `MapCamera` (607) is missing from the interface list (175–180).
  - Line 523 cites "the table in this specification", but no table exists (line 154 is prose).
- **Problem:** Two descriptions of the same mechanics have already drifted.
- **Fix:** Keep the mechanics in the Detailed Plan only, reduce the Technical Plan to components, data flow and rules, and reconcile the items above. See also IC-5.

### ZO-14. The camera path's positions have no source. 🟢 Nit

- **Lines:** 644 against 495.
- **Problem:** `CameraPath.kt` takes its stops "from the trial profile's metadata", but the metadata fields at line 495 include no camera stops. The test edits' locations are private (owner's decision), so hard-coding them in the public benchmark source set would expose them.
- **Fix:** Carry the camera stops in the sidecar from ZO-5, or in the PMTiles metadata. See also IC-10.

### ZO-15. The first APK with MapLibre native libraries skips the 16 KB alignment check. 🟢 Nit

- **Lines:** 662, 727.
- **Problem:** `release` gains no dependency on `:core:map`, so only the benchmark APK contains MapLibre's `.so` files. `tools/android/check_page_alignment.py` runs on the release APK only.
- **Fix:** Run the existing script on the benchmark APK too. No new code is needed.

### ZO-16. The `benchmark` build type that stays may overlap with the standard benchmark tooling. 🟢 Nit

- **Lines:** 15, 184, 583, 694.
- **Problem:** ADR 0004 plans Baseline Profiles and Macrobenchmark. The AndroidX Baseline Profile Gradle plugin generates its own `benchmarkRelease` and `nonMinifiedRelease` build types, so the kept `benchmark` type could become an unused second benchmark type. The trial's frame counter also duplicates part of what Macrobenchmark measures.
- **Fix:** Without reopening the owner's decision, have the ADR 0004 note (item 52) say either that `:benchmarks` must target this type, or that the type is removed when `:benchmarks` adopts the plugin's types. Record under Alternatives why Macrobenchmark's frame metrics were not used (MapLibre's render thread, line 68).

### ZO-17. The SELinux relabelling has risks, and a check from the brief was dropped. 🟢 Nit

- **Lines:** 69, 112, 393.
- **Problem:** `:Z` relabels the owner's checkouts in place with a private label, which can block other tools from reading them, and it fails on filesystems that cannot store labels. The concepts brief listed "the filesystem of `/mnt/WorkDrive`, SELinux labels on bind mounts" as something to verify (brief line 181), but Open Questions no longer includes it.
- **Fix:** Add that check to "To verify in step 1". Prefer `:z`, or `--security-opt label=disable` for this one container, over `:Z` on the read-only checkouts. See also IC-14.

---

## Inventive catalyst reviewer (`egm-inventive-catalyst`)

**Verdict:** Revise before implementation. There are no Blockers. Eight Major findings show the plan accepting a trade-off or failure mode that a known pattern removes. All findings stay inside the owner's decisions (M2, the `benchmark` build type, Natural Earth beyond the extent, edits before smoothing).

### IC-1. The image contains the tool code, so image and code are coupled. 🟡 Major

- **Lines:** 110, 131, 375, 395, 401–402, 421, 515, 657.
- **Problem:**
  - The Dockerfile installs `tools/map` and `tools/common` into the image (line 375), and `run.py` runs whatever image `image.lock` names (line 395). The fingerprint and report record `git rev-parse HEAD` of the mounted checkout (lines 131, 421). After the owner commits a change, the old code inside the image runs while the new commit is recorded. That breaks the provenance G4 relies on.
  - Every code change needs an image rebuild, a publish and an `image.lock` pull request before the owner can run it, which slows step 2's tuning loop.
  - `image.lock` sits under `tools/map/`, which is the `map` workflow's trigger path (line 657). Docker builds are not byte-reproducible by default, so merging the lock pull request rebuilds the image, produces a new digest and opens another pull request. This can loop.
  - The edits schema `$ref`s `content/schema/content.schema.json` (line 515), which is not inside any package installed in the image.
- **Fix (Extraction):** The image carries only the toolchain (GDAL, tippecanoe, go-pmtiles, WhiteboxTools, the Python dependencies). The code runs from the read-only `/src` mount that already exists, as `python -m seerah_map` with `PYTHONPATH` set to the two `src` folders. Tag the image by a hash of its real inputs (Dockerfile and `requirements.txt`), build and push only when that tag is missing, and keep `image.lock` out of the image's inputs and the trigger paths.
- **Secondary liabilities:** A run is then defined by the pair (commit, digest), both already recorded. A Python dependency change still needs an image rebuild. The image's Python version must match the code's (3.11 syntax). See also ZO-3.

### IC-2. The completeness check repairs a broken tile but not the tiles built from it. 🟡 Major

- **Lines:** 48, 69, 148, 490, 535.
- **Problem:** `gdal raster tile` builds each lower zoom from the tiles of the zoom above. A half-written zoom-12 tile from an interrupted run is used to build its zoom-11 to zoom-0 ancestors before the decode check runs. The check deletes only tiles that fail to decode, and `--resume` regenerates only those, so ancestors that decode but are wrong remain. The result is a silent defect that breaks G3 and G4 ("resumed equals clean"). The resume test (line 535) interrupts only the block stage ("after N blocks"), so this path is never tested.
- **Fix (Blessing in Disguise):** Treat each broken tile as a signal to invalidate its whole ancestor chain: delete the tile and its up to 12 parents (morecantile `parent`), then run `--resume` again. Alternatively, tile in two passes: zoom 12 only, then check and repair, then zooms 0–11 with `--resume`, provided step 1 confirms overviews are read from tiles on disk. Add a test that kills the `gdal raster tile` subprocess partway, resumes, and compares bytes with a clean build.
- **Secondary liabilities:** One small custom function; up to 12 extra tiles regenerated per broken tile; a two-pass run starts GDAL twice.

### IC-3. The coast line is derived from polygon outlines, which will also draw the extent's edge. 🟡 Major

- **Lines:** 137, 152, 158, 163, 500.
- **Problem:** The spec turns the mask into polygons and tiles a `coast` line layer, but does not say how coast lines are kept apart from the outer frame of the data extent. If coast lines come from the land polygons' outlines, the clipped edge across Jordan, Iraq and the Gulf of Aden will be drawn as a straight coast. The spike already shipped this artefact with its 10° cells (findings, builds 21 to 22). Polygonising a whole-extent mask of about 4.8 billion pixels in one pass is also an unmeasured memory risk under a budget of about 2 GB.
- **Fix (Preliminary Action):** Generate the coast from the smoothed mask field before thresholding, using `gdal_contour` at 0.5. In line mode, contour lines stop at the raster's edge and do not trace it; polygon mode (`-p`) at the same level gives the land fill. Both come from one field, so relief, land and coast cannot disagree, and sub-pixel interpolation removes the 30 m stair-steps without a separate simplify step. Add an automated check that no `coast` vertex lies on the extent's frame.
- **Secondary liabilities:** The smoothed float mask must be kept per zoom band (more temporary disk) or recomputed. Contour output needs the same simplification tolerance per band.

### IC-4. Edits are not local to a block, and the terrain border is never defined. 🟡 Major

- **Lines:** 135, 138, 144, 456, 480, 528.
- **Problem:** Line 138 claims that feathering by distance "never reaches beyond the block border". `edits.py` rasterises the edit and computes distances on the block's window (line 456). For a pixel outside the polygon, the nearest outline point can lie in the neighbouring block, so a distance computed only inside the window is too large and leaves a seam in the elevation. The style border is computed from the settings (line 144); "the terrain border" (line 135) never is.
- **Fix (Segmentation with a computed overlap):** Compute the terrain border the same way as the style border: the mask smoothing radius plus the largest `feather_m` (plus half of `width_m` for `carve`), converted to pixels at the block's equatorward edge. Apply edits over the block plus that border, then crop. State in `test_seams.py` that the test edit's feather zone crosses a block edge.
- **Secondary liabilities:** Slightly larger reads for every block, or per-block borders computed from the edits that touch each block (more logic). Wide feathers in future real edits enlarge the border for every block.

### IC-5. One fingerprint and one work area at a time block resume and repeat stage 1 for every variant. 🟡 Major

- **Lines:** 120, 131, 150, 165, 169, 441, 495, 500, 602, 639.
- **Problem:**
  - Any work area with a different fingerprint stops every other build until `clean` removes it (lines 131, 441). A failed full-box build kept for resume therefore blocks every small-window build unless the owner discards its finished blocks. This directly hits the owner's "cannot resume" failure criterion.
  - The fingerprint includes the variant and mode, so the painted, value and per-zoom builds each redo stage 1 (warping 529 cells) and the base layer, though their terrain is identical.
  - Line 131 omits `mode`, which line 441 includes.
  - Output paths disagree between line 150 and line 495. `base.pmtiles` goes to `<out>/` (line 500), outside the per-variant folder that the importer copies as one `MapFiles` (lines 602, 639), and each build overwrites it.
- **Fix (Segmentation):** Allow several work areas side by side; they are already named by fingerprint, so a mismatched resume cannot happen. Replace exclusivity with a free-space check on the work volume. Let one `build` run several variants (`--variant painted,value`) in one work area so stage 1 and the base layer run once. Write `base.pmtiles` into each `<variant>-<mode>/` folder.
- **Secondary liabilities:** More disk held while several areas coexist; `clean` must list them with sizes. A multi-variant build runs longer before it can succeed. See also ZO-13.

### IC-6. Blur settings in pixels make the zoom-11 choice not reproducible. 🟡 Major

- **Lines:** 25, 59, 127, 143, 165, 470, 485.
- **Problem:** Blur and band blur are profile settings in pixels at the build's maximum zoom (lines 127, 470). The trial's zoom-11 variant is the zoom-12 render averaged down, so its ground blur equals 7 px at zoom 12 (about 250 m). If the owner picks zoom 11, a real build with `max_zoom: 11` and the same 7 px blurs twice as far on the ground (about 500 m), erases edits narrower than about 1 km, and does not look like what the owner judged. This coupling between the open zoom decision and the style's units is not stated. Per-zoom mode already scales the blur to keep the ground distance (line 485); the regional build does not.
- **Fix (Inversion of the parameter's reference):** Define the style's blurs in pixels at zoom 12 (the zoom of the build 11 calibration) or in metres, and have `grid.py` derive the pixel σ for the build's maximum zoom, as per-zoom mode already does.
- **Secondary liabilities:** One more conversion, and a test that the zoom-12 result is unchanged. Below about 1 px the Gaussian does almost nothing; document that.

### IC-7. The data scope of step 1 contradicts itself. 🟡 Major

- **Lines:** 118, 131, 171, 200, 206, 431, 436, 510, 561.
- **Problem:** Step 1 says "`lock` and `fetch` for the small windows" (line 206), but `lock` downloads every present cell of the profile's extent to hash it (line 431), and no window locks exist (lines 200 and 561 list only the trial lock). So step 1 downloads the whole box, an estimated 13 GB or more. `lock` refuses to add cells without `--replace` (line 431), and the fingerprint hashes the whole lock (line 131), so any lock change invalidates every work area. The valley probe runs alongside step 1 (line 206) but needs the edited terrain of the whole extent (lines 171, 510), which exists only after stage 1 of step 4.
- **Fix (Preliminary Action):** State that step 1 locks and fetches the whole box once, which the probe needs anyway. Run the probe on the unedited DEM, warped to 90 m and 180 m directly from the cached cells; edits do not change its memory use. Fingerprint only the lock entries the profile's extent uses. Before running the probe, compute its expected memory: at 90 m, about 750 million cells at several bytes each, which is predictable against about 2 GB.
- **Secondary liabilities:** A large first download before any visible result. A lock-subset fingerprint needs a stable way to select entries. See also ZO-1 and ZO-2, which propose the alternative of growing the lock per profile and probing GLO-90; the author should pick one.

### IC-8. The open-time measure assumes a map that opens by itself. 🟡 Major

- **Lines:** 189, 637–638.
- **Problem:** Open time runs from process start to the first fully rendered map (line 189), but the trial screen opens on a variant list and an import button (line 638). A cold open therefore includes the owner's tap, and one of ADR 0002's four measures becomes meaningless.
- **Fix (Preliminary Action):** Persist the last variant and open it automatically on a cold start. Measure only that path, and show "cold" in the overlay only when no interaction came before the render.
- **Secondary liabilities:** A small piece of persisted state in the trial code.

### IC-9. The claim that edges never darken has no automated check. 🟢 Nit

- **Lines:** 69, 146, 148, 490, 527.
- **Problem:** Line 146 states the painted encoder makes averaging "never darken edges". Lower zooms are averaged from decoded lossy WebP, and line 69 leaves open whether WebP keeps the ink under transparent pixels. Dark coast edges at zooms 5–8 are easy to miss by eye.
- **Fix (Blessing in Disguise):** The decode check already reads every tile; make it also assert that RGB equals the ink (within a tolerance) wherever alpha is above 0, at every zoom, and fail the build otherwise.
- **Secondary liabilities:** More decode time per tile and a tolerance to choose.

### IC-10. The camera path's positions have no source. 🟢 Nit

- **Lines:** 99, 190, 495, 515, 644.
- **Problem:** `CameraPath.kt` takes "positions from the trial profile's metadata", but neither `metadata.json` (line 495) nor the coverage schema (line 515) holds them. Writing the private test-edit locations into public app code would also undo A14, and a fixed path cannot fit three different windows.
- **Fix (Intermediary, consistent with line 99):** Add `camera_path` stops to the profile schema and to `metadata.json`, so each file carries its own path.
- **Secondary liabilities:** A schema field that exists only for the trial. See also ZO-14.

### IC-11. A memory overrun ends the build instead of adapting. 🟢 Nit

- **Lines:** 46, 133, 394, 446.
- **Problem:** The worker allowance is measured for stage 2 only, but `-j` in stage 3, polygonize and pack have other memory profiles. An OOM kill inside the container stops a multi-hour run that the owner must restart by hand.
- **Fix (Dynamics):** Measure an allowance per stage. When the cgroup's `memory.events` `oom_kill` counter rises, resume automatically with one worker fewer and log it in the report. Block outputs are deterministic, so the bytes do not change.
- **Secondary liabilities:** Less predictable run time; a retry limit is needed.

### IC-12. The edits schema does not match the content schema. 🟢 Nit

- **Lines:** 128, 456, 515, 549, 561.
- **Problem:** `$defs/review` is a string enum, but `edits.py` tests `review.status`. `$defs/source` requires `work` and `locator`, and its `kind` enum has no synthetic value, so a synthetic edit's source would have to be invented, which goes against "a model's guess is never a source" (AGENTS.md).
- **Fix:** Test `review == "approved"`. In `trial-0`, replace `sources` with `synthetic: true`, or make `sources` optional when `purpose: trial`.

### IC-13. The value variant's size may be overstated by the choice of format. 🟢 Nit

- **Lines:** 147–148, 490.
- **Problem:** Option (c) is tiled as PNG, and item 3 judges its size. Lossless WebP is usually smaller, and MapLibre reads it for `raster-dem`.
- **Fix:** Tile the value variant with `-f WEBP` and `LOSSLESS=YES` (GDAL creation options), or measure both.
- **Secondary liabilities:** One more step-1 check on the Oppo A55.

### IC-14. Some environment checks were dropped from Open Questions. 🟢 Nit

- **Lines:** 69, 112, 192, 393.
- **Problem:** The brief's checks on the filesystem of `/mnt/WorkDrive`, SELinux labels, and Licensee's handling of MapLibre's licence metadata are not carried into Open Questions. `:Z` relabels the whole checkout on every run and fails on filesystems without xattrs. New named volumes are owned by root and cannot be written with `--user`.
- **Fix:** List these checks under step 1. Use `--security-opt label=disable` or `:z` for the read-only mounts. Create `/cache` and `/work` in the image with permissions the host UID can write to. See also ZO-17.

---

## Notes for the author

- **Conflicting fixes.** ZO-1/ZO-2 and IC-7 agree on the problem but propose different fixes: grow the lock per profile and probe on GLO-90 (zoom-out), or lock the whole box once in step 1 and probe on the unedited 30 m DEM (inventive catalyst). ZO-9 (fuse stages 1 and 2) and IC-5 (share stage 1 across variants) also interact. Choose one direction for each pair before revising.
- **Owner's failure criteria touched:** cannot resume (IC-2, IC-5, IC-11), disk use (ZO-9, IC-5), code quality (ZO-4, ZO-6).
