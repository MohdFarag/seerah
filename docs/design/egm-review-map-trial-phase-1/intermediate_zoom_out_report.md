# Stage 8: Intermediate zoom out, map trial phase 1 plan

**Target:** `docs/design/map-trial-phase-1-plan.md` (lines 1-725; FeatureRange 1-121, DetailedRange 122-725)
**Specification:** `docs/design/map-trial-build.md` (approved; its decisions are not reopened here)
**Standard:** EGM `zoom-out-standard.md` (v0.1.1)
**Earlier reports:** `zoom_out_report.md` (ZO-n, IC-n), `comprehension_report.md` (CO-n), `critic_report.md` (DE-n, ST-n, SY-n)

## Reviewer status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |

The reviewer read the full plan, all three earlier reports, the approved spec and the context files. It could not open URLs or run code. Claims about Docker, GDAL, pyproj, Protomaps `pmtiles`, the Copernicus bucket and U1's geography are reasoned, not tested, and are marked "(reasoned)".

Two facts that are not in any file shaped this review:

- On 2026-11-14 the owner asked for the plan to "keep the build pipeline portable between cloud and local environments".
- The owner also asked the plan to "identify any unresolved implementation decisions rather than silently making them".

The owner's top priority is no unnecessary custom code. Fixes are therefore sized for one publisher.

**ID count:** the earlier reports hold 131 raw IDs, not 151: ZO 29, IC 30, CO 21, DE 17, ST 13 and SY 21. All 131 are triaged below.

## Verdict

**Approve after one revision pass.** The plan keeps to the approved architecture: M2, a toolchain-only image, code mounted at `/src`, and custom code only where G5 allows it. No spec decision needs reopening. The reviewer found no existing code to reuse: `tools/validate_content.py` has no YAML loader, and the spec lists a shared strict YAML loader only as a future improvement.

- **Root items:** 18. Blocker 1, Major 14, Nit 3. ZI-17 is wholly new, and ZI-1 and ZI-3 have new parts.
- **Triage of the 131 raw IDs:** K 89, S 8, O 7, C 27.
- **The one Blocker (ZI-1):** when a provenance value is missing, the fingerprint ignores changes to the code and the toolchain. A resumed build then reuses stale blocks without any warning.
- **The Majors:** mostly cheap fixes that stop a task from failing as written. Each is one mount, one flag, one call or one sentence.
- **Cut:** most of the critic's operational hardening, as heavier than one publisher needs. That is DE-3 to DE-7, DE-10, DE-13, ST-3, ST-4 and ST-11.

## Root-cause items

### ZI-1 🔴 Blocker. A missing provenance value makes the fingerprint ignore code and toolchain changes

- **Resolves:** IC-3, CO-18, DE-1 (corrected below). Part is new.
- **Lines:** 13, 68, 258, 571-572 (spec 229, G4).
- **Problem:**
  - Portability is a real owner goal, so a plain `docker run` must work.
  - But a missing `SEERAH_TOOL_COMMIT` or `SEERAH_IMAGE_DIGEST` becomes the literal `"unknown"`, and that string goes into the fingerprint.
  - On any host with a persistent `/work`, changed code or a changed image then gets the same work area and silently resumes stale blocks. This breaks G4.
  - Neither the spec nor the plan says where the portability goal came from, so a fresh reader sees a conflict with A8 and with "run.py is the only way".
- **Fix (lightest):**
  - Keep `"unknown"` in the provenance fields of `metadata.json` and of the report.
  - In the fingerprint only, put a computed value in place of each missing one:
    - for the commit: the SHA-256 of the files under `/src/tools/map/src`, read in sorted path order;
    - for the digest: the GDAL version plus the sorted `name==version` list from `importlib.metadata`.
  - This is about 10 lines. It fills in missing values and is not a new fingerprint rule.
  - Add one test: with the variables unset, changing one source file changes the fingerprint.
  - At line 13 and in U5, cite the owner's requirement of 2026-11-14.
  - In the contents of `tools/map/README.md` (Task 16), document the plain `docker run` contract: the mounts, the three variables, the memory flags, and `--network none` for `build`.
- **Owner decision:** no.

### ZI-2 🟡 Major. The test container differs from the runtime contract, so the task tests cannot run

- **Resolves:** ZO-1, CO-1, IC-1, IC-5, ZO-2, ZO-5, IC-7.
- **Lines:** 26, 29, 52-56, 152-619, 627, 639-647, 681-682.
- **Problems:**
  - `-w /src` with paths like `tests/...` resolves to the wrong place.
  - No `/data` is mounted, so Task 14's `build --profile example/example` cannot find the profile.
  - There is no `--security-opt label=disable`. On an SELinux host the bind mount is denied (reasoned).
  - pytest's cache plugin writes into the read-only `/src`.
  - Docker creates new named volumes owned by root, so `run.py --user` cannot write to them (reasoned).
  - pytest collects the host-only launcher tests inside the image.
- **Fix:**
  - Define MAPTEST as follows. With no arguments it runs `testpaths`.

    ```sh
    docker run --rm --security-opt label=disable -v "$PWD:/src:ro" \
      -v "$PWD/map-data:/data:ro" -v seerah-map-test-cache:/cache -w /src/tools/map \
      -e PYTHONPATH=/src/tools/map/src seerah-map:dev python3 -m pytest -p no:cacheprovider <args>
    ```

  - Use the same flags in the CI test step.
  - Add `RUN mkdir -m 1777 /cache /work /out` to the Dockerfile, with one assertion in `test_image.py`.
  - Move the launcher tests to `tools/map/host_tests/`.
  - Do not add IC-1's `SEERAH_DATA`, `SEERAH_WORK` and `SEERAH_OUT` variables: one mount line does the same job without code. IC-1's worry about shared `/work` state does not apply, because each `--rm` container has its own `/work`.
- **Owner decision:** no.

### ZI-3 🟡 Major. Image identity in run.py and CI

- **Resolves:** ZO-3, IC-4, ZO-4, IC-2, CO-7, ZO-22, IC-16, CO-8, DE-11. Part is new.
- **Lines:** 65, 637, 651, 657, 670-692.
- **Problems:**
  - `run.py` always pulls, and `RepoDigests` is empty for `seerah-map:dev`, so the smoke test at line 657 fails.
  - On a pull request, the `test` job has no image.
  - The tag rule is written twice, and `--print-tag` is missing from Task 15.
  - The `mohdfarag` namespace is hard-coded where the spec says `<owner>`.
  - New: `map.yml` leaves out the `concurrency` and `timeout-minutes` settings that `android.yml` and `ci.yml` use.
- **Fix:**
  - When `--image` is given, `run.py` skips the pull. When an image has no repository digest, it records `local:<.Id>`. Test both cases.
  - Add `--print-tag` to Task 15's usage line, interface and tests. Both CI jobs take the tag only from `run.py --print-tag`.
  - In job `test`, run `docker pull "$IMG" || docker build -t "$IMG" tools/map`. IC-2's artifact-and-publish restructure is not needed.
  - Take the namespace from `github.repository_owner`, lowercased, and keep it in one constant in `run.py`.
  - Copy `concurrency` and `timeout-minutes` from the existing workflows.
- **Owner decision:** yes (U2: confirm the namespace).

### ZI-4 🟡 Major. Pip's numpy can shadow the numpy that GDAL was built against

- **Resolves:** ZO-6, IC-6.
- **Lines:** 140, 161-162.
- **Problem:** scipy pulls numpy into `requirements.txt`. If pip installs a numpy whose ABI differs from the base image's, it breaks `osgeo.gdal_array`, and the block stage depends on `ReadAsArray` (reasoned).
- **Fix:**
  - Add one line to `requirements.in`: `numpy==<the base image's version>`. This is lighter than a full `constraints.txt`.
  - Run pip-compile in a throwaway container made from the base image.
  - In `test_toolchain_present`, import `osgeo.gdal_array` and assert that numpy loads from the system location.
- **Owner decision:** no.

### ZI-5 🟡 Major. Absent cells and pixels outside the data extent feed undefined values into the blurs

- **Resolves:** ZO-9, ZO-10, IC-9, CO-14.
- **Lines:** 288, 298, 474-476 (spec 237-241).
- **Problems:**
  - The plan assigns each pixel to a cell after the warp. That is custom code, and it leaves holes next to absent cells.
  - Outside the data extent there is no stated fill, so the blurs create a false cliff and coast.
  - The `mask >= 128` step is not placed in Task 10.
- **Fix:**
  - **Absent cells:** build both VRTs over the data extent (`outputBounds`). Areas with no source then read as 0 m for elevation and 1 for the Water Body Mask (`VRTNodata`, `hideNodata=True`) (reasoned). The per-pixel cell lookup goes.
  - **Outside the extent:** fill invalid pixels from the nearest valid pixel with one `distance_transform_edt(..., return_indices=True)` call, then re-apply `valid`.
  - State "land = mask >= 128" in Task 10's steps.
  - Add one test: a hill cut by the extent edge leaves no dark rim.
- **Owner decision:** no.

### ZI-6 🟡 Major. The completeness check takes its expected tiles from its own output

- **Resolves:** ZO-8, IC-10.
- **Lines:** 463, 507 (spec 285, G3).
- **Problem:** `expected_tiles` comes from the extent of `style.vrt`. A missing inner block tiles as transparent files, and the check passes.
- **Fix:**
  - Before writing the VRT, assert that `style/<id>.tif` exists for every block in `grid.blocks(data_extent)`.
  - Compute `expected_tiles` from the profile's data extent.
  - Add a test with one block file deleted.
- **Owner decision:** no.

### ZI-7 🟡 Major. Per-zoom mode is in phase 1's scope but cannot be built from the plan

- **Resolves:** ZO-7, IC-11, CO-5.
- **Lines:** 222-231, 494, 500, 566, 580.
- **Problem:**
  - Only the blur scaling is tested.
  - The grid is fixed at zoom 12, `tile_args` fixes `--min-zoom 0`, and each tile folder has one `tiles.done` marker.
  - The plan settles this silently by half-implementing it, which goes against the owner's rule to name unresolved decisions.
- **Fix:** add decision U7 with two options.
  - **(a) Recommended:** defer per-zoom mode to a short follow-up before trial step 2, the first step that uses it. Drop `--mode` and `test_per_zoom_mode_scales_blur` from phase 1, and list the deferral as a deviation.
  - **(b) Build it now:** add a zoom parameter to the grid functions, `tile_args(min_zoom, max_zoom)`, one `tiles.done.<z>` marker per zoom, and a slow test on the example.
- **Owner decision:** yes (U7).

### ZI-8 🟡 Major. U1's example window is unverified, needed earlier than stated, and has no zoom for the one-block test

- **Resolves:** ZO-15, ZO-21, IC-12, IC-13, SY-13, ST-6.
- **Lines:** 64, 469, 480, 602-617.
- **Problems:**
  - The window may be all sea (reasoned, not tested).
  - "Inside one present cell" is wrong, because the window crosses 57° E. It spans a present cell and its absent neighbour, which is the intent.
  - Task 10's slow test needs the real cell before U1 is due.
  - A zoom-9 tile boundary cuts the window.
- **Fix:**
  - Before asking the owner, run two checks:
    - the window's land fraction, with `gdalinfo -hist` on the Water Body Mask through `/vsicurl/`;
    - that the neighbour cell is absent from `tileList.txt`.
  - Run the single-block comparison at `block_zoom: 8` (see Corrections), and assert that it yields exactly one block.
  - Move `test_block_split_matches_single_block` from Task 10 to Task 14. Do not build a synthetic two-cell generator or add a `network` marker.
  - Write "Owner to confirm".
  - Give `camera-path.json` the spec's Q1 minimum zoom of 4.
- **Owner decision:** yes (U1).

### ZI-9 🟡 Major. The spike regression and style tests can fail for the wrong reason

- **Resolves:** ZO-12, IC-15, ZO-27, IC-29, CO-3, DE-14.
- **Lines:** 40-46, 362-371.
- **Problems:**
  - The spike pads both blurs with edge values (`relief_style.py` line 41), but the plan's band blur uses scipy's default `reflect`. The 4-pixel rim is about 7.8% of the fixture, far above the 0.1% the test allows. A failure would be a false G8 stop.
  - The banding test checks a value from before the band blur, which `darkness` never returns.
  - 7.36 and the mask settings are presented as spike values, with no source.
- **Fix:**
  - Use `gaussian_filter(sigma=1.5, radius=4, mode="nearest")`.
  - Run the banding test with `band_blur_px=0`.
  - Cite spec 259 for 7.36 (8 × cos 23°). This also explains why the regression test uses 8 at latitude 0.
  - Say that mask σ 4 and the 128 threshold come from spec 241.
  - Keep `test_average_keeps_ink`, because the spec lists it (spec 784).
- **Owner decision:** no.

### ZI-10 🟡 Major. Edit correctness

- **Resolves:** ZO-11, IC-19, IC-20, CO-15.
- **Lines:** 227, 236, 324, 329-332, 349.
- **Problems:**
  - The seam test compares windows without borders, so it cannot pass.
  - A carve buffered in Mercator metres comes out 8-15% too narrow.
  - An empty `inside` mask leaves `distance_transform_edt` undefined.
  - Which latitude `border_px` uses, and which edge `edge_fade` measures to, are not stated.
- **Fix:**
  - Run the seam test on bordered windows and compare the cropped cores.
  - Buffer each carve by `width_m / 2 / cos(lat)`.
  - Skip an edit whose `inside` mask is empty.
  - `border_px` uses the block's poleward edge latitude.
  - `edge_fade` measures to the edge of the data extent.
- **Owner decision:** no.

### ZI-11 🟡 Major. Gaps in the pack step and the run report

- **Resolves:** ZO-17, IC-24, CO-9, DE-8, ZO-13, IC-27.
- **Lines:** 528-533, 545-547 (spec 293-296, 331-343).
- **Problems:**
  - Two packing commands are offered.
  - `metadata.json` is written to `out_dir`, but `disk_to_pmtiles` reads it from the tile folder.
  - The header bounds are unverified.
  - The report's size split, tile count and per-stage wall time have no function and no test.
  - `RUSAGE_CHILDREN` is mislabelled.
- **Fix:**
  - Follow the spec:
    1. write `metadata.json` into the tile folder;
    2. run `disk_to_pmtiles` in a subprocess with `PYTHONHASHSEED=0`;
    3. copy `metadata.json` beside the output.

    This embeds `trial: true` in the archive at no extra cost (DE-8). Assert it with `pmtiles show`.
  - Make the first slow test check the header bounds, and name the fallback if they are wrong.
  - Add `size_by_zoom(tiles_dir, outline)`, which classifies each tile by its centre point, with a test.
  - Take the stored-tile count from the PMTiles header.
  - Read `getrusage` right after the block pool closes, and label it "largest child process".
- **Owner decision:** no.

### ZI-12 🟡 Major. The kill-and-resume tests are flaky, and their trigger line is never logged

- **Resolves:** ZO-14, IC-8, CO-10.
- **Lines:** 584, 595, 614-615.
- **Problem:**
  - Killing the CLI leaves the pool workers and the tiler running.
  - The `tile stage started` line that the test waits for is never specified.
- **Fix:**
  - Start the CLI with `start_new_session=True` and kill it with `os.killpg`.
  - In Task 13, list every progress line with its exact text.
- **Owner decision:** no.

### ZI-13 🟡 Major. Decisions made silently, and deviations not flagged

- **Resolves:** ZO-16, IC-23, ST-8, SY-15, ZO-24, SY-2, SY-16, CO-21, ZO-20, IC-14, DE-9, DE-15, ZO-25, IC-17, ST-10.
- **Lines:** 27, 60-74, 82, 566-570, 637, 650, 700-725.
- **Problem:** the owner asked for unresolved decisions to be named. None of these are:
  - the `--per-worker-mib` default, and whether `GDAL_CACHEMAX` counts against it;
  - what happens when there is no `memory.max` (cgroup v1);
  - REL profile paths;
  - `run.py`'s `--data`, `--image` and `--print-tag`;
  - the dropped `natural_earth_max_zoom` and coverage-check tests;
  - files 41 and 44, pulled forward.

  The decision text also has errors:
  - U3's reason is wrong.
  - "The one deviation" is false.
  - Line 27's "every dependency is pinned" contradicts U4.
  - U4's "reproducible" contradicts the sentence before it.
  - U2 does not cross-reference check 9 and its token fallback.
- **Fix:**
  - **Add U6.** `--per-worker-mib` defaults to 2048 until check 12 measures it, and `GDAL_CACHEMAX` counts inside that allowance. CI's 6 GB cap then gives 3 workers. A missing `memory.max` is treated as `max`, with a test.
  - **U4.** Add the alternative: run a hash-pinned pip wheel directly. If U4 is kept as it is, add the pinning exception to line 27 and fix the "reproducible" wording.
  - **U2.** Add check 9 and its token fallback.
  - **U3.** Correct the reason.
  - **Self-review.** Add one "Deviations" list. It holds U3, U5, REL, the `run.py` flags, files 41, 42 and 44, the `GDAL_CACHEMAX` value, U7, and `natural_earth_max_zoom` with the coverage tests (deferred to phase 3).
- **Owner decision:** yes (U6, U4, and approving the deviations list).

### ZI-14 🟡 Major. Paths and the file tree

- **Resolves:** SY-1, ST-9, ST-7, DE-16, SY-21.
- **Lines:** 29, 86-118, 173, 216, 594.
- **Problem:** line 29 says paths are relative to the repository root, but from Task 2 on the Files lists are relative to `tools/map/`. A fresh subagent that sees one task can create `src/` at the repository root.
- **Fix:**
  - Write every Files path in full, or state the exception in line 29.
  - Correct the task tags: `__init__.py` belongs to Task 1, and `conftest.py` needs a task that creates it.
  - Add `docs/map-trial/step-1.md`, `tests/fixtures/` and `host_tests/` to the tree.
  - Skip the `[C]`/`[M]` legend and the `.dockerignore`.
- **Owner decision:** no.

### ZI-15 🟡 Major. Steps that need things before they exist

- **Resolves:** CO-6, IC-26, IC-22, CO-12.
- **Lines:** 208, 426-430, 577, 607.
- **Problems:**
  - Task 14 must run `fetch` before `run.py` exists, and under MAPTEST the new lock in `/out` is lost.
  - Nothing says how a new lock gets into the profile's folder.
  - Check 7 needs a Water Body Mask "fetched by hand", with no address given. It also gates Task 5, not Task 2.
- **Fix:**
  - `fetch` prints the spec's copy instruction (spec 195), and Task 9 tests that message.
  - Task 14 gives the exact `docker run` command, mounting `map-data` at `/data` and `map-data/dist` at `/out`, followed by the copy step.
  - Move check 7 to the start of Task 5 and run it through `/vsicurl/`. The folder pattern is from spec 117; the exact file name is reasoned.
  - Add the class legend from spec 447: 0 no water, 1 ocean, 2 lake, 3 river.
- **Owner decision:** no.

### ZI-16 🟢 Nit. Context and wording for a fresh reader

- **Resolves:** CO-2, CO-4, CO-11, CO-13, CO-17, CO-19, ST-5, DE-17, DE-2, SY-4, ST-1, ST-2, ST-12, SY-6, ST-13, SY-7, IC-18.
- **Lines:** 1-20.
- **Fix:**
  - Add a four-line "Where this fits" paragraph. It links the spec's Terms, Goals, Components, Step 1 checks and phases.
  - Add one clause each for: what the variants are for, the private repository, the neutrality rule, `camera-path.json`, the Natural Earth files, the mask blocks and `view_reach_km`.
  - Relabel the unit groups: edits use GDAL and grid uses morecantile, so neither is pure numpy. Do not refactor (see the DE-2 correction).
  - Add an Author, Status and Date block.
  - Add a `## Tasks` heading before Task 1.
  - Fix the title.
  - Make line 3 tool-neutral.
  - Say "linux/amd64 hosts" at line 13.
- **Owner decision:** no.

### ZI-17 🟢 Nit (new). Toolchain details to confirm in Task 1's image test

- **Resolves:** DE-12, ZO-28 (both S). The rest is new.
- **Lines:** 17, 140-150, 157, 329, 478.
- **Problems:**
  - morecantile's pyproj bundles its own PROJ, which may clash with GDAL's `proj.db` in the same process (reasoned).
  - The OGR in-memory driver may be named `MEM` rather than `Memory` in GDAL 3.11 and later (reasoned).
  - Installing go-pmtiles as a bare binary drops its BSD-3 licence file.
  - Forking after GDAL is loaded is a known hazard.
- **Fix:** settle these during implementation.
  - In `test_toolchain_present`, run one morecantile call and one OSR transform in the same process.
  - Use whichever in-memory driver name GDAL 3.13 accepts.
  - Copy go-pmtiles' LICENSE into the image. The notices file stays in phase 3.
  - Use `mp_context=forkserver`.
- **Owner decision:** no.

### ZI-18 🟢 Nit. Small test and schema details

- **Resolves:** ZO-19, SY-3, ZO-18, IC-21, ZO-26, IC-25, CO-16.
- **Lines:** 137-150, 194, 202, 429, 569-572, 616.
- **Fix:**
  - The content schema's `$defs` has `source` (singular) and `review`. Copy both, and define `sources` as an array of `source`.
  - Pin jsonschema to 4.26.0, as pre-commit does.
  - Add a `west: -1` case to the N/E test.
  - Assert that the lock is unchanged after a partial download.
  - Add the missing `os` and `shutil` imports.
  - Force the work-area failure after the block stage.
  - `<name>` is the profile's file stem, and `fp8` is the first 8 hexadecimal characters of the fingerprint.
- **Owner decision:** no.

## Corrections to earlier findings

- **DE-1: wrong premise.**
  - The owner did set a portability goal on 2026-11-14.
  - A8 rejects a bigger cloud machine as the way to build, on cost. It does not forbid a portable pipeline.
  - Keep line 13 and U5, and cite where the goal came from.
  - IC-3's G4 concern stands. The fix is ZI-1, filling in the missing values, not removing portability.
  - U5 also meets the spec's intent (spec 186) with less code.
- **IC-1: overstated.** It is Major, not Blocker.
  - It fails loudly on Task 14's first run, and one mount line fixes it.
  - Its first proposed fix, the environment variables, adds code that is not needed.
  - The shared-`/work` worry does not apply, because each `--rm` container has its own `/work`.
- **IC-2: overstated.** It is Major, not Blocker. One pull-or-build line is enough. On `main`, the test job pulls the image that was just pushed.
- **ZO-21 against IC-12: ZO-21 is right.** The window is 56.80-57.05° E and 17.50-17.75° N.
  - **At zoom 8,** both edges fall in tile x 168, which spans 56.25-57.656° E, and tile y is 115 at both latitudes. One tile holds the whole window.
  - **At zoom 9,** the tile boundary at 56.953° E cuts the window, and zoom 10 is finer still.
  - So use zoom 8, not IC-12's "zoom 9 or 10".
- **DE-2: overstated.** It is a Nit, not Major. The spec requires only `style.py` to be pure (spec 246), and it has edits use `gdal.Rasterize` (spec 242). Relabel the groups; do not refactor.
- **ZO-9 against IC-9: they fix different halves.**
  - The VRT background is the lighter fix for absent cells.
  - The nearest-value fill is the lighter fix for the edge of the extent: one call covers both the mask and the elevation, so ZO-10's normalised blur is not needed.
  - Use both (ZI-5).
- **CO-12: its assumption is correct.** Spec 447 gives the classes as 0 no water, 1 ocean, 2 lake and 3 river.
- **IC-24: partly wrong.**
  - The spike ran `pmtiles-convert` on an MBTiles file (`build_base.sh` line 98), not on a tile folder. The spec names `disk_to_pmtiles`, so follow the spec.
  - Its point about where `metadata.json` goes is right.
- **DE-8: overstated.** Following spec 293 embeds the trial marker for free. One assertion is enough.
- **DE-10: overstated.** Spec 119 expects the 87 absent cells to be all sea, and the owner judges the coast at step 4. A Natural Earth overlap check adds code for a near-zero risk.
- **ZO-27, second half: wrong.** The spec requires `test_average_keeps_ink` (spec 784), so keep it.
- **ZO-29 and IC-28: not a contradiction.** The two-week rule picks the first pin, and the spec uses the same rule (spec 829). Dependabot's 7-day cooldown governs the updates it proposes later. AGENTS.md's "assigned branch" settles the branch question.
- **ZO-15 and IC-12, "all sea": not tested.** The reasoning points that way, but nobody has checked the data. ZI-8 adds the check.
- **IC-30: no change to the plan.** The slip is in the spec (line 410), and the plan does not repeat it.

## Triage table (all 131 raw IDs)

K = change the plan now. S = settle during implementation. O = owner decision. C = cut (heavier than needed, wrong, or not worth it).

| ID | Code | ZI | Reason |
| --- | --- | --- | --- |
| ZO-1 | K | ZI-2 | MAPTEST paths, SELinux label and cache |
| ZO-2 | K | ZI-2 | volumes owned by root |
| ZO-3 | K | ZI-3 | a local image has no digest |
| ZO-4 | K | ZI-3 | no image for the PR test job |
| ZO-5 | K | ZI-2 | host tests collected in the image |
| ZO-6 | K | ZI-4 | numpy ABI |
| ZO-7 | O | ZI-7 | per-zoom: defer or build |
| ZO-8 | K | ZI-6 | completeness uses its own output |
| ZO-9 | K | ZI-5 | VRT background for absent cells |
| ZO-10 | K | ZI-5 | real problem; fixed by the nearest fill instead |
| ZO-11 | K | ZI-10 | seam test, carve width |
| ZO-12 | K | ZI-9 | blur edge mode |
| ZO-13 | K | ZI-11 | report size split |
| ZO-14 | K | ZI-12 | killpg and log line |
| ZO-15 | K | ZI-8 | U1 check and timing |
| ZO-16 | K | ZI-13 | unflagged deviations |
| ZO-17 | K | ZI-11 | one pack route; header-bounds test |
| ZO-18 | S | ZI-18 | trivial imports |
| ZO-19 | K | ZI-18 | `source`, not `sources`; jsonschema pin |
| ZO-20 | O | ZI-13 | per-worker default (U6) |
| ZO-21 | K | ZI-8 | zoom 8 is correct |
| ZO-22 | K | ZI-3 | one tag rule |
| ZO-23 | C | - | multi-arch pins not needed; the wording is fixed via IC-18 |
| ZO-24 | K | ZI-13 | U3's reason is wrong |
| ZO-25 | O | ZI-13 | U4: pinned pip wheel option |
| ZO-26 | S | ZI-18 | pick a later failure |
| ZO-27 | K | ZI-9 | banding test; keep the averaging test |
| ZO-28 | S | ZI-17 | forkserver |
| ZO-29 | C | - | not a contradiction; AGENTS.md governs the branch |
| IC-1 | K | ZI-2 | Major; mount `/data`, no new variables |
| IC-2 | K | ZI-3 | Major; pull-or-build |
| IC-3 | K | ZI-1 | the fingerprint fills in missing values |
| IC-4 | K | ZI-3 | skip the pull; local digest |
| IC-5 | K | ZI-2 | 1777 directories, label flag |
| IC-6 | K | ZI-4 | lighter: one numpy pin |
| IC-7 | K | ZI-2 | host_tests folder |
| IC-8 | K | ZI-12 | orphan processes |
| IC-9 | K | ZI-5 | nearest fill |
| IC-10 | K | ZI-6 | expected set from the grid |
| IC-11 | O | ZI-7 | per-zoom |
| IC-12 | K | ZI-8 | zoom 9 or 10 corrected to 8 |
| IC-13 | K | ZI-8 | lighter: move the test to Task 14 |
| IC-14 | O | ZI-13 | U6 |
| IC-15 | K | ZI-9 | `mode="nearest"` |
| IC-16 | K | ZI-3 | `--print-tag` |
| IC-17 | O | ZI-13 | U4 option |
| IC-18 | K | ZI-16 | say "linux/amd64" only |
| IC-19 | K | ZI-10 | edit details |
| IC-20 | K | ZI-10 | poleward latitude |
| IC-21 | S | ZI-18 | imports |
| IC-22 | K | ZI-15 | check 7 moves to Task 5, via `/vsicurl/` |
| IC-23 | K | ZI-13 | deviations list |
| IC-24 | K | ZI-11 | metadata location; follow the spec's `disk_to_pmtiles` |
| IC-25 | S | ZI-18 | fail after the block stage |
| IC-26 | K | ZI-15 | Task 14 needs an explicit fetch command |
| IC-27 | K | ZI-11 | `ru_maxrss` label |
| IC-28 | C | - | not a contradiction |
| IC-29 | K | ZI-9 | `band_blur_px=0` |
| IC-30 | C | - | spec slip; the plan is unaffected |
| CO-1 | K | ZI-2 | MAPTEST meaning |
| CO-2 | K | ZI-16 | link the spec's terms |
| CO-3 | K | ZI-9 | cite spec 259 |
| CO-4 | K | ZI-16 | what the variants are for |
| CO-5 | O | ZI-7 | build modes |
| CO-6 | K | ZI-15 | lock copy step |
| CO-7 | K | ZI-3 | PR image mechanism |
| CO-8 | K | ZI-3 | `--print-tag` |
| CO-9 | K | ZI-11 | one pack command |
| CO-10 | K | ZI-12 | log lines |
| CO-11 | K | ZI-16 | "Where this fits" |
| CO-12 | K | ZI-15 | class table correct per spec 447; give the URL |
| CO-13 | K | ZI-16 | one clause each |
| CO-14 | K | ZI-5 | where `mask >= 128` applies |
| CO-15 | K | ZI-10 | latitude and edge |
| CO-16 | S | ZI-18 | name and fp8 |
| CO-17 | K | ZI-16 | one clause |
| CO-18 | K | ZI-1 | same as IC-3 |
| CO-19 | K | ZI-16 | one clause each |
| CO-20 | C | - | a link to the spec is enough |
| CO-21 | K | ZI-13 | U4 wording |
| DE-1 | C | ZI-1 | wrong premise; keep portability and cite the owner |
| DE-2 | K | ZI-16 | relabel only, no refactor |
| DE-3 | C | - | stop points live in their tasks; U2's fallback via ZI-13 |
| DE-4 | C | - | one publisher; no locking |
| DE-5 | C | - | builds read local VRTs; the argument test is enough |
| DE-6 | C | - | trial steps start after phase 3; the owner merges by hand |
| DE-7 | C | - | the lock already detects changes; no ETag, no schedule |
| DE-8 | K | ZI-11 | free via the spec's metadata location |
| DE-9 | K | ZI-13 | cache counted inside the allowance |
| DE-10 | C | - | the spec accepts absent cells as sea |
| DE-11 | K | ZI-3 | derive the namespace; check 9 |
| DE-12 | S | ZI-17 | keep the go-pmtiles licence; notices in phase 3 |
| DE-13 | C | - | no real builds before phase 3 |
| DE-14 | K | ZI-9 | cite spec and spike sources |
| DE-15 | K | ZI-13 | missing `memory.max` = `max` |
| DE-16 | K | ZI-14 | tree fixes; no `.dockerignore` |
| DE-17 | K | ZI-16 | link the spec's Components |
| ST-1 | K | ZI-16 | `## Tasks` heading |
| ST-2 | K | ZI-16 | status block only |
| ST-3 | C | - | Task 16 has the exit check; an out-of-scope list exists |
| ST-4 | C | - | heavy restructuring |
| ST-5 | K | ZI-16 | link the spec's flow |
| ST-6 | S | ZI-8 | Q1 zoom 4 in the camera path; columns cut |
| ST-7 | K | ZI-14 | tree |
| ST-8 | K | ZI-13 | flag file 44 |
| ST-9 | K | ZI-14 | path rule |
| ST-10 | K | ZI-13 | only cross-reference U2 and check 9 |
| ST-11 | C | - | renumbering is not worth it |
| ST-12 | K | ZI-16 | title only |
| ST-13 | K | ZI-16 | tool-neutral line 3 |
| SY-1 | K | ZI-14 | path rule |
| SY-2 | K | ZI-13 | pinning exception |
| SY-3 | K | ZI-18 | tests match their claims |
| SY-4 | K | ZI-16 | unit groups |
| SY-5 | C | - | the spec's own name |
| SY-6 | K | ZI-16 | title |
| SY-7 | K | ZI-16 | line 3 |
| SY-8 | C | - | ruff ignores a `pyproject.toml` with no `[tool.ruff]` (reasoned) |
| SY-9 | C | - | wording from spec G3 |
| SY-10 | C | - | style only |
| SY-11 | C | - | style only |
| SY-12 | C | - | style only |
| SY-13 | K | ZI-8 | "Owner to confirm" |
| SY-14 | C | - | style only |
| SY-15 | K | ZI-13 | "one deviation" is false |
| SY-16 | K | ZI-13 | "reproducible" wording |
| SY-17 | C | - | style only |
| SY-18 | C | - | style only |
| SY-19 | C | - | style only |
| SY-20 | C | - | DE-15 covers the substance |
| SY-21 | K | ZI-14 | tree |

**Totals:** K 89, S 8, O 7, C 27 (131).

## Owner decisions needed

1. **U1 (ZI-8):** confirm the example window after the land-fraction and `tileList.txt` checks.
2. **U2 (ZI-3):** confirm the GHCR namespace, which CI derives from `github.repository_owner`, and set the package public. The fallback is a personal token (check 9).
3. **U4 (ZI-13):** accept pip from apt, or run a hash-pinned pip wheel directly (recommended).
4. **U6, new (ZI-13):** `--per-worker-mib` defaults to 2048 until check 12 measures it, with `GDAL_CACHEMAX` counted inside that allowance.
5. **U7, new (ZI-7):** per-zoom mode. Defer it to before trial step 2 (recommended), or build it in phase 1.
6. **The deviations list (ZI-13):** approve it together with the plan. It holds U3, U5, REL paths, the `run.py` flags, files 41, 42 and 44 pulled forward, and `natural_earth_max_zoom` with the coverage tests deferred to phase 3.
