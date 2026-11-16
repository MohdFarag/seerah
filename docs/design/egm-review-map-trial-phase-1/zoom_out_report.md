# Stage 5: Initial zoom out, map trial phase 1 plan

**Target:** `docs/design/map-trial-phase-1-plan.md` (lines 1-725; FeatureRange 1-121, DetailedRange 122-725)
**Specification:** `docs/design/map-trial-build.md` (approved; its decisions are not reopened here)
**Standard:** EGM `zoom-out-standard.md` (v0.1.1)

## Reviewers

| Reviewer | Status | 🔴 | 🟡 | 🟢 |
| --- | --- | --- | --- | --- |
| `egm-zoom-out` | completed | 0 | 17 | 12 |
| `egm-inventive-catalyst` | completed | 2 | 14 | 14 |

Both reviewers read the full target, the full specification and the context files. The severities below are the ones each reviewer gave. Where both reviewers found the same problem, the finding is listed under each and cross-referenced. The overlap table at the end lists these pairs and any severity differences. Neither reviewer could open URLs or run code. Claims about third-party behaviour (Docker volume permissions, `disk_to_pmtiles` header bounds, the contents of the GDAL base image) and about geography (U1's window) are reasoned, not tested. The reviewers say so where it applies.

## Zoom-out reviewer (`egm-zoom-out`)

**Verdict:** The plan follows the approved architecture: M2, a toolchain-only image, code mounted from `/src`, and custom code only where G5 allows it. The deviations it does flag are stated honestly. It cannot be run as written. The test command, the CI test job, the launcher's first run and several tests would fail. Some approved behaviour (per-zoom mode, the report's size split) has no interface or test. One revision pass is needed. No decision has to be reopened.

### ZO-1 🟡 The test command `MAPTEST` does not work

- **Lines:** 52-56. It is used at 152, 165, 198, 241 and in every later task.
- **Problem:**
  - It runs `pytest tools/map <args>` with `-w /src`, so per-task paths such as `tests/test_image.py` resolve to `/src/tests/...`, which does not exist.
  - It leaves out `--security-opt label=disable` (spec 184), so on an SELinux-enforcing host the read-only bind mount is denied.
  - `/src` is read-only, so pytest's cache plugin warns on every run.
- **Fix:**
  - Use `-w /src/tools/map`, or write every per-task path in full (`tools/map/tests/...`).
  - Add `--security-opt label=disable` and `-p no:cacheprovider`.
- **Overlaps:** IC-1, IC-5.

### ZO-2 🟡 The named volumes are not writable under `--user`

- **Lines:** 159, 641, 657 (spec 182-184).
- **Problem:** Docker creates `seerah-map-cache` and `seerah-map-work` owned by root. A container run as the host user cannot write to them, so the smoke test (657) fails on its first download.
- **Fix:**
  - Add `RUN mkdir -p /cache /work /out && chmod 1777 /cache /work` to the Dockerfile.
  - Add a test in `test_image.py` that these directories exist and everyone can write to them.
- **Overlaps:** IC-5.

### ZO-3 🟡 `run.py` cannot resolve a digest for a locally built image

- **Lines:** 651, 657.
- **Problem:** `{{index .RepoDigests 0}}` fails for `seerah-map:dev`, so Task 15 Step 5's smoke test cannot pass.
- **Fix:** When `RepoDigests` is empty, fall back to `local:<.Id>`. Test both cases.
- **Overlaps:** IC-4.

### ZO-4 🟡 On a pull request, the CI `test` job has no image

- **Lines:** 675-681.
- **Problem:** Job `image` builds without pushing, and job `test` runs on a different runner where "the freshly built local image" does not exist.
- **Fix:** Use either of these:
  - In job `image`, `docker save` the image and upload it as an artifact; job `test` downloads it and runs `docker load`.
  - Rebuild in job `test` with the buildx `type=gha` cache.
  - In both cases, keep `packages: write` on the push step only (spec 798).
- **Overlaps:** IC-2 (🔴 there).

### ZO-5 🟡 The in-image pytest run collects the host-only launcher tests

- **Lines:** 627, 639, 681-682.
- **Problem:**
  - `tests/test_run.py` sits under `testpaths`.
  - `test_dirty_checkout_refused` needs `git`, which the `ubuntu-small` base probably lacks.
  - `import run` also fails from `tests/`.
- **Fix:** Move the launcher tests to `tools/map/host_tests/`, or ignore them in the pytest configuration, and run them only on the host.
- **Overlaps:** IC-7.

### ZO-6 🟡 numpy in the image can break GDAL's numpy bindings

- **Lines:** 140, 161-162 (spec 664).
- **Problem:**
  - `pip-compile` pins numpy anyway, because scipy depends on it. Installing it with `--break-system-packages` shadows the apt numpy that `osgeo.gdal_array` was built against.
  - `pip-compile` itself is not in the base image.
- **Fix:**
  - Constrain numpy to the base image's version and keep it out of `requirements.txt`.
  - Run pip-tools in a throwaway container.
  - In `test_toolchain_present`, assert that `from osgeo import gdal_array` imports and that numpy is the system copy.
- **Overlaps:** IC-6.

### ZO-7 🟡 Per-zoom mode cannot be built from the interfaces given

- **Lines:** 222-231, 368, 494, 500, 566, 580 (spec 263, 287).
- **Problem:** `BASE_ZOOM` is fixed, `pixel_m()` takes no zoom, and `tile_args` fixes `--min-zoom 0`. Only the blur scaling is tested.
- **Fix:**
  - Add a zoom parameter to `pixel_m`, `blocks`, `row_latitudes` and `border_px`.
  - Give `tile_args` a minimum and a maximum zoom.
  - Specify the per-zoom loop in `cli.py`.
  - Add a slow test that builds the example in per-zoom mode.
  - Derive the band-blur radius as `int(3 × band_blur_px)`.
  - Or list per-zoom mode as a deviation for the owner.
- **Overlaps:** IC-11.

### ZO-8 🟡 A missing block passes the completeness check without notice

- **Lines:** 463, 507 (spec 285, G3).
- **Problem:** `expected_tiles` is taken from the extent of `style.vrt`. A missing inner block tiles as transparent files, so the check passes.
- **Fix:**
  - Before writing the VRT, assert that every block from `grid.blocks(extent)` exists.
  - Compute `expected_tiles` from the profile's data extent.
  - Add a test that deletes one block.
- **Overlaps:** IC-10.

### ZO-9 🟡 Absent cells are handled with more custom code than the spec asks for, and it can leave holes

- **Lines:** 474-475 (spec 751).
- **Problem:** The plan assigns each pixel to a cell after the warp. That is custom code, and it leaves bilinear nodata next to absent cells.
- **Fix:**
  - Build both VRTs with `outputBounds` set to the data extent, `hideNodata=True`, and `VRTNodata` set to 0 for elevation and 1 for the Water Body Mask.
  - Derive `valid` from each pixel centre's position against the data extent.
- **Overlaps:** IC-9.

### ZO-10 🟡 The land mask's blur at the data edge is undefined

- **Lines:** 298 (spec 238-241).
- **Problem:** `land = (wbm != 1)` counts nodata outside the extent as land, and the blur spreads it about 12 pixels inward. The relief's fade hides this, but phase 3's coast contour would not.
- **Fix:**
  - Blur only the valid data: divide the blur of `land × valid` by the blur of `valid`, or fill from the nearest valid value first.
  - Add `test_edge_does_not_bleed`.
- **Overlaps:** IC-9.

### ZO-11 🟡 The edit seam test cannot pass as written, and carve widths come out wrong

- **Lines:** 324, 329-332.
- **Problem:**
  - Each plain window computes its own feather distances, so results near the seam differ from a single window.
  - A carve buffer of `width_m / 2` in EPSG:3857 is in Mercator metres, which makes carves about 9% too narrow at 23° N and about 15% too narrow at 32° N.
- **Fix:**
  - Run the test on bordered windows and compare the cropped cores.
  - Buffer by `width_m / 2 / cos(lat)`.
- **Overlaps:** IC-19.

### ZO-12 🟡 The spike regression test can fail for the wrong reason

- **Lines:** 368, 371.
- **Problem:** The band blur uses scipy's default `mode="reflect"`, but the spike pads by repeating the edge value. The 4-pixel rim is about 8% of a 200×200 fixture, far above the 0.1% the test allows. A failure would be a false G8 stop.
- **Fix:** Use `mode="nearest"` on both blurs.
- **Overlaps:** IC-15.

### ZO-13 🟡 Parts of the run report have no interface or test

- **Lines:** 530-533, 545-547 (spec 335-343).
- **Problem:**
  - Bytes per zoom inside and outside `peninsula_outline`, the size after deduplication, and per-stage wall time have no function and no test. Decision 13 and A13 depend on the size split.
  - `RUSAGE_CHILDREN.ru_maxrss` also counts the tiler and the packer.
- **Fix:**
  - Add `size_by_zoom(tiles_dir, outline)`, classifying tiles by their centre point, with a test.
  - Read `getrusage` right after the block pool shuts down.
- **Overlaps:** IC-27 (the `ru_maxrss` part only).

### ZO-14 🟡 The kill-and-resume tests will be flaky, and their trigger line is never logged

- **Lines:** 584, 595, 614-615.
- **Problem:**
  - Killing the CLI leaves the pool workers and `gdal raster tile` running.
  - The `tile stage started` line the test waits for is not in Task 13's interface.
- **Fix:**
  - Start the CLI with `start_new_session=True` and kill it with `os.killpg`.
  - Add the log line to Task 13.
- **Overlaps:** IC-8.

### ZO-15 🟡 U1 is needed earlier than stated, and the proposed window may hold no land

- **Lines:** 64, 208, 469, 480, 602.
- **Problem:**
  - Task 10 and Task 2 Step 5 need the real cell, but U1 says it is needed by Task 14.
  - "Inside one present cell" is wrong wording, because the window crosses 57° E.
  - The reviewer has moderate confidence that the coast at 56.8-57° E lies near 18.2-18.4° N. If so, the 17.50-17.75° N window may be all sea.
- **Fix:**
  - Settle U1 before Task 10, or use a synthetic GeoTIFF in Task 10.
  - Before asking the owner, check the window's land fraction and confirm in `tileList.txt` that the neighbour cell is absent.
- **Overlaps:** IC-12, IC-13.

### ZO-16 🟡 Several changes to the spec are not flagged

- **Lines:** 208, 569, 637, 650, 670, 708, 720-723.
- **Problem:** None of these are surfaced:
  - Profiles are named by a path under `/data` (`REL`), against spec 199.
  - `run.py` gains `--data`, `--image` and `--print-tag`, against spec 676. `--print-tag` is also missing from Task 15's own interface and tests.
  - `natural_earth_max_zoom` and the coverage-check tests are dropped (spec 717, 774, 322-327), yet the self-review says spec file 8 is covered (708).
  - `docs/map-trial/step-1.md` is created in phase 1, although spec file 41 belongs to phase 3.
- **Fix:** Add each one to the decisions table or the deviations list, or move it to phase 3.
- **Overlaps:** IC-23.

### ZO-17 🟡 (unverified risk) The pack step's header bounds and command are not settled

- **Lines:** 545-546.
- **Problem:**
  - The reviewer could not confirm that `disk_to_pmtiles` writes `bounds` from `metadata.json` into the PMTiles header. If it writes world bounds, `verify` fails on every build.
  - "`python3 -m pmtiles.convert`, or a small inline call" leaves the choice open.
- **Fix:**
  - Choose one command.
  - Make Task 12's first test check the header bounds, and name the fallback if they are wrong.
- **Overlaps:** IC-24.

### ZO-18 🟢 The Task 1 test snippet is missing imports

- **Lines:** 137-150.
- **Problem:** `os` and `shutil` are not imported.
- **Fix:** Add the imports.
- **Overlaps:** IC-21.

### ZO-19 🟢 The schema reference is wrong, and jsonschema is not pinned

- **Lines:** 202 (spec 705).
- **Problem:** The content schema's `$defs` has `source` (singular) and `review`, not `sources`.
- **Fix:**
  - Define `sources` as an array of a copied `$defs/source`.
  - Pin jsonschema to 4.26.0, as pre-commit does.

### ZO-20 🟢 `--per-worker-mib` has no default when a memory cap exists

- **Lines:** 566, 570.
- **Fix:** Make the flag required in that case, or give a documented provisional default until step 1 check 12 measures it.
- **Overlaps:** IC-14 (🟡 there).

### ZO-21 🟢 The single-block comparison needs a stated zoom

- **Lines:** 469.
- **Problem:** A zoom-9 tile boundary at 56.953° E cuts the window.
- **Fix:**
  - State the `block_zoom` used (zoom 8 covers the window) and assert that only one block results.
  - Note that spec 410's "256-pixel blocks" should read 512 pixels.
- **Overlaps:** IC-12, IC-30.

### ZO-22 🟢 The image tag rule is written twice

- **Lines:** 670, 676.
- **Fix:**
  - Use only `python3 tools/map/run.py --print-tag` on the runner.
  - Pass values to `run:` steps through `env:`.
- **Overlaps:** IC-16 (🟡 there).

### ZO-23 🟢 go-pmtiles is pinned for x86_64 only

- **Lines:** 157.
- **Fix:** Pin a SHA-256 per architecture and pick it with `TARGETARCH`, or state that the image is amd64 only.
- **Overlaps:** IC-18.

### ZO-24 🟢 U3's reason is inaccurate

- **Lines:** 66, 724 (spec 430).
- **Problem:** "Avoids two unpinned builds" is wrong, because tippecanoe is pinned by commit.
- **Fix:** Correct the reason, and say that U3 moves the valley probe out of step 1.

### ZO-25 🟢 U4 has a pinnable option

- **Lines:** 67, 156.
- **Fix:** Download a hash-checked pip wheel and use it instead of apt's python3-pip.
- **Overlaps:** IC-17.

### ZO-26 🟢 The work-area failure test uses the wrong failure

- **Lines:** 616.
- **Problem:** A corrupted lock entry changes the fingerprint, and it may fail before any work area exists.
- **Fix:** Force a failure after the work area is created.
- **Overlaps:** IC-25.

### ZO-27 🟢 Two tests are weak

- **Lines:** 362, 390.
- **Problem:**
  - `test_banded_has_four_levels` checks a value from before the band blur, which the function never returns.
  - `test_average_keeps_ink` re-implements GDAL's averaging, so it tests nothing in GDAL.
- **Fix:**
  - Run the banding test with `band_blur_px=0`.
  - Say that step 1 check 6 is the real check for averaging.
- **Overlaps:** IC-29 (the first test).

### ZO-28 🟢 `ProcessPoolExecutor` forks after GDAL is loaded

- **Lines:** 478.
- **Fix:** Pass `mp_context=multiprocessing.get_context("forkserver")`.

### ZO-29 🟢 The branch and the update cooldown need attention

- **Lines:** 27, 58, 686.
- **Problem:**
  - Line 58 commits code to the docs branch.
  - The two-week pin rule disagrees with Dependabot's 7-day cooldown.
- **Fix:**
  - Use a feature branch for the code.
  - Set `cooldown: 14` on the new `/tools/map` entries, or state which rule wins.
- **Overlaps:** IC-28.

## Inventive catalyst reviewer (`egm-inventive-catalyst`)

**Verdict:** The plan follows the approved architecture closely: M2, the toolchain-only image, the fingerprinted work areas and the style steps. It cannot be run as written:

- The end-to-end tests cannot reach `/data`.
- On a pull request, the CI test job cannot get the image that was just built.
- Several portability shortcuts weaken the fingerprint, so a resumed build can reuse stale blocks.

### IC-1 🔴 The end-to-end tests have no `/data`, `/work` or `/out`

- **Lines:** 26, 29, 53-56, 569, 599, 610-619, 681.
- **Problem:** The CLI only takes paths relative to `/data`. `MAPTEST` and the CI test job mount only `/src` and `/cache`, so `build --profile example/example` cannot find the profile.
- **Fix:** Use either of these:
  - Read the container roots from optional variables (`SEERAH_DATA`, `SEERAH_WORK`, `SEERAH_OUT`), defaulting to `/data`, `/work` and `/out`. `conftest.py` points data at `/src/map-data` and the other two at `tmp_path`. Document the variables as test and cloud only.
  - Add the mounts to `MAPTEST` and CI. That needs no code, but the tests then share `/work` state.
- **Overlaps:** ZO-1 (same command, a different defect).

### IC-2 🔴 On a pull request, the `test` job has no image

- **Lines:** 675-683, 692.
- **Problem:** The first phase 1 pull request always changes the Dockerfile, so Task 16 Step 4 ("green on that pull request") cannot pass.
- **Fix:**
  - Use one job, `image-and-test` (`contents: read`), that pulls or builds the image and runs both test suites. On `main` it saves the image as an artifact.
  - Add a `publish` job (`packages: write`, `needs: image-and-test`, `if: main`) that pushes that same artifact.
  - The cost is an artifact of a few hundred MB per `main` push. In return, an untested image is never pushed, and the pushed image is the one that was tested, not a rebuild (this matters under U4).
- **Overlaps:** ZO-4 (🟡 there).

### IC-3 🟡 "unknown" provenance makes the fingerprint ignore code changes

- **Lines:** 13, 258, 571-572 (spec 229).
- **Problem:** With a plain `docker run` (as in `MAPTEST`, CI or on a cloud host), `SEERAH_TOOL_COMMIT` and `SEERAH_IMAGE_DIGEST` are `"unknown"`. A developer who edits `style.py` gets the same fingerprint and silently resumes stale blocks. This breaks G4.
- **Fix:**
  - The tool always puts its own identity into the fingerprint:
    - the code identity: SHA-256 of `/src/tools/map/src`, read in sorted order;
    - the toolchain identity: GDAL, pmtiles and the installed package versions.
  - The commit and the image digest stay as provenance when `run.py` supplies them.
  - The cost is milliseconds. The toolchain hash misses system libraries such as libwebp, but the image digest covers them when supplied.

### IC-4 🟡 `run.py` cannot use a local image

- **Lines:** 637, 651, 657.
- **Problem:**
  - Step 3 always runs `docker pull`.
  - A local image has no `RepoDigests`, so the digest template fails.
- **Fix:**
  - Skip the pull when `--image` is given.
  - Record `local:<.Id>` when there is no repository digest.
  - Add a test for this.
- **Overlaps:** ZO-3.

### IC-5 🟡 `MAPTEST` differs from the container contract `run.py` uses, and new volumes are owned by root

- **Lines:** 29, 53-56, 642 (spec 183-184).
- **Problem:**
  - `run.py` runs with `--user`, but the volumes Docker creates are owned by root.
  - `MAPTEST` runs as root without `--security-opt label=disable`.
  - Neither difference is exercised before Task 15.
- **Fix:**
  - Add `RUN mkdir -m 1777 /cache /work /out` to the Dockerfile.
  - Add `--user "$(id -u):$(id -g)" --security-opt label=disable` to `MAPTEST` and the CI run, so every task runs under the production contract.
- **Overlaps:** ZO-1, ZO-2.

### IC-6 🟡 numpy ABI drift between GDAL's bindings and the pip packages

- **Lines:** 161-162 (spec 664).
- **Problem:** `pip-compile` pulls in numpy through scipy, and later Dependabot bumps can do the same.
- **Fix:**
  - Generate `constraints.txt` from the base image's own `pip list --format=freeze` and run `pip-compile -c constraints.txt`.
  - Add `from osgeo import gdal_array` to `test_toolchain_present`.
  - Note in the Dockerfile that `constraints.txt` must be regenerated when the base image's digest changes.
- **Overlaps:** ZO-6.

### IC-7 🟡 pytest in the image collects the host-only launcher tests

- **Lines:** 163, 627, 647, 681-682.
- **Fix:** Move the launcher tests to `tools/map/host_tests/test_run.py` and update lines 627, 647 and 682.
- **Overlaps:** ZO-5.

### IC-8 🟡 The kill-and-resume tests leave child processes running

- **Lines:** 584, 595, 614-615.
- **Fix:**
  - Use `start_new_session=True` and `os.killpg(pgid, SIGKILL)`.
  - Assert that `tiles.done` is absent when the kill lands, because the tile stage may finish first on a 0.25° window.
  - Add the `tile stage started` log line to Task 13.
- **Overlaps:** ZO-14.

### IC-9 🟡 Undefined values feed both blurs at the edge of the data extent

- **Lines:** 288, 298, 474-475 (spec 239).
- **Problem:**
  - NaN spreads 21 pixels inward.
  - A 0 m fill makes a false cliff, and the fade leaves about a third of its shading 21 pixels in.
  - A sea fill creates the false coast that A9 warns about.
- **Fix:**
  - Fill invalid pixels with the nearest valid value (`distance_transform_edt(..., return_indices=True)`) before mask and style, then re-apply `valid`.
  - Test that a hill cut by the extent edge leaves no dark ridge along the edge.
- **Overlaps:** ZO-9, ZO-10.

### IC-10 🟡 The completeness check takes its expected set from its own output

- **Lines:** 463, 507.
- **Fix:**
  - Assert that `style/<id>.tif` exists for every ID in `grid.blocks(...)` before writing `style.vrt`.
  - Compute `expected_tiles` from the same block list.
  - Add a test with one block file deleted.
- **Overlaps:** ZO-8.

### IC-11 🟡 Per-zoom mode is in phase 1 but cannot be implemented from the plan

- **Lines:** 222-231, 494, 566, 580 (spec 263, 287).
- **Problem:** There is also one `tiles.done` marker per folder, but per-zoom mode writes several zooms into one folder.
- **Fix:**
  - Add a zoom parameter throughout, `tile_args(min_zoom, max_zoom)` and a `tiles.done.<z>` marker per zoom, plus a slow test on the example.
  - Or record per-zoom mode as an explicit deferral to phase 2 in the Unresolved table.
- **Overlaps:** ZO-7.

### IC-12 🟡 U1's proposed window is probably all sea, and it is missing one constraint

- **Lines:** 64, 469, 602, 617 (spec 409-421).
- **Problem:**
  - The window may be open sea. The reviewer reasoned this from geography, not data, and asks for a check.
  - The one-block comparison needs the window to fit inside one tile at a coarser `block_zoom`.
- **Fix:**
  - Add a short procedure to U1: run `gdalinfo -hist` on the cell's Water Body Mask through `/vsicurl/`, check that the neighbour is absent from `tileList.txt`, and check that the window fits inside one zoom-9 or zoom-10 tile.
  - Move U1's "Needed by" to Task 10.
- **Overlaps:** ZO-15, ZO-21. ZO-21 says a zoom-9 boundary cuts the window and suggests zoom 8, so the zoom to use needs checking.

### IC-13 🟡 Tasks cannot be tested on their own because of the real example cell

- **Lines:** 163, 469, 480.
- **Problem:** Task 10's slow test needs data that only exists after Task 14.
- **Fix:**
  - Have `conftest.py` generate a synthetic two-cell GeoTIFF DEM and Water Body Mask (a hill, a coastline step, an absent neighbour). Use it in Task 10, and repeat the test on the real cell in Task 14.
  - Split the test markers into `slow` and `network`.
- **Overlaps:** ZO-15.

### IC-14 🟡 The default for `--per-worker-mib` is an unresolved decision the plan never names

- **Lines:** 566, 570, 637 (spec 233, 452 check 12).
- **Fix:**
  - Add a U6 row: "default 2048 until step 1 check 12 measures it", or make the flag required.
  - State the value CI uses.
- **Overlaps:** ZO-20 (🟢 there).

### IC-15 🟡 The band blur's edge mode will likely fail the spike regression test

- **Lines:** 363, 368, 370 (spec 253).
- **Fix:**
  - Use `gaussian_filter(sigma=1.5, radius=4, mode="nearest")`.
  - State the tolerances in one unit: "within 1 alpha level for 99.9% of pixels, within 2 for all".
- **Overlaps:** ZO-12.

### IC-16 🟡 The tag rule is implemented twice, and the cross-check calls a flag that does not exist

- **Lines:** 633, 637, 670, 676.
- **Fix:**
  - Add `--print-tag` to Task 15, with a test.
  - Have CI use only `run.py --print-tag`, and remove the shell copy of the rule.
- **Overlaps:** ZO-16, ZO-22.

### IC-17 🟢 U4 can be avoided instead of accepted

- **Lines:** 67, 156.
- **Fix:** Use a versioned pip zipapp or a static `uv` binary, checked against a pinned SHA-256 `ARG`.
- **Overlaps:** ZO-25.

### IC-18 🟢 Portability is claimed more widely than it holds

- **Lines:** 13, 157.
- **Fix:** Say "linux/amd64 hosts", or pin a SHA-256 per architecture and pick it with `TARGETARCH`.
- **Overlaps:** ZO-23.

### IC-19 🟢 Edit details

- **Lines:** 324, 330-332.
- **Fix:**
  - Widen each window by `border_px` in the seam test and compare the cropped cores.
  - Guard `distance_transform_edt` for a window that only the feather reaches.
  - Divide the carve buffer by `cos(lat)`.
- **Overlaps:** ZO-11 (🟡 there).

### IC-20 🟢 `border_px` should use the block's poleward edge latitude

- **Lines:** 236 (spec 240).
- **Fix:** Use the poleward edge latitude, not the block's centre.

### IC-21 🟢 The test snippet is missing its imports

- **Lines:** 138-150.
- **Fix:** Import `os` and `shutil`.
- **Overlaps:** ZO-18.

### IC-22 🟢 Check 7 is in the wrong task

- **Lines:** 208.
- **Fix:** Move it to the start of Task 5, which it gates, and run it through `/vsicurl/`. No manual download is needed before `fetch` exists.

### IC-23 🟢 Deviations from the spec that the self-review does not flag

- **Lines:** 68, 70, 208, 650, 720-723.
- **Problem:** The self-review does not mention these:
  - `step-1.md` is created in phase 1;
  - the coverage-check test and `natural_earth_max_zoom` are missing;
  - `run.py` has `--data` and `--image`, and `--data map-data` contradicts spec 182 and 597;
  - `GDAL_CACHEMAX=256` is a new value.
- **Fix:** List each one.
- **Overlaps:** ZO-16 (🟡 there).

### IC-24 🟢 Two parts of the pack step are undecided

- **Lines:** 529, 545 (spec 293).
- **Fix:**
  - Pick the packaged `pmtiles-convert` script, which the spike already uses.
  - Put `metadata.json` in the tile folder, where `disk_to_pmtiles` reads it, not in `out_dir`.
- **Overlaps:** ZO-17.

### IC-25 🟢 The failure test needs a different failure

- **Lines:** 616.
- **Fix:** Fail after the block stage, for example by pointing the output root at a file.
- **Overlaps:** ZO-26.

### IC-26 🟢 Task 14 needs a command that does not exist yet

- **Lines:** 607.
- **Problem:** Task 14 runs `fetch` through `run.py`, which arrives in Task 15.
- **Fix:** Give the exact `docker run` command, or swap Tasks 14 and 15.

### IC-27 🟢 The worker memory figure is mislabelled

- **Lines:** 547.
- **Fix:** Label `RUSAGE_CHILDREN.ru_maxrss` as "largest child process".
- **Overlaps:** ZO-13.

### IC-28 🟢 The pinning rule contradicts Dependabot

- **Lines:** 27.
- **Fix:** Align the two-week rule with Dependabot's 7-day cooldown, or explain why they differ.
- **Overlaps:** ZO-29.

### IC-29 🟢 The banding test needs an internal step

- **Lines:** 362.
- **Fix:** Run it with `band_blur_px=0`.
- **Overlaps:** ZO-27.

### IC-30 🟢 Spec text slip

- **Lines:** spec 410.
- **Problem:** Zoom-11 blocks are 512 pixels at zoom 12, not 256. The plan does not repeat the error.
- **Fix:** Keep the error out of U1 and the test comments.
- **Overlaps:** ZO-21.

### The contradictions behind these findings (inventive catalyst)

| Contradiction | Findings | Resolution |
| --- | --- | --- |
| Portability against exact verification | IC-1, IC-3, IC-5 | The tool identifies itself from its own code and packages; `MAPTEST`, CI and `run.py` share one container contract; container roots are passed in, not hard-coded. |
| Minimal custom code against verification | IC-2, IC-16 | One tag rule; one job builds and tests the image before it is pushed. |
| Small independent tasks against tests on real data | IC-13 | The tests generate synthetic GDAL fixtures. |

## Overlaps

| Problem | Zoom-out | Inventive catalyst | Severity differs |
| --- | --- | --- | --- |
| `MAPTEST` and the container contract | ZO-1 | IC-1, IC-5 | IC-1 is 🔴 (a different defect: no `/data`) |
| Named volumes owned by root | ZO-2 | IC-5 | no |
| No digest for a local image | ZO-3 | IC-4 | no |
| No image for the CI test job on a pull request | ZO-4 🟡 | IC-2 🔴 | yes |
| Launcher tests collected in the image | ZO-5 | IC-7 | no |
| numpy ABI | ZO-6 | IC-6 | no |
| Per-zoom mode | ZO-7 | IC-11 | no |
| Missing block passes the completeness check | ZO-8 | IC-10 | no |
| Edge and absent-cell values in the blurs | ZO-9, ZO-10 | IC-9 | no (the fixes differ: a VRT fill against a nearest-value fill) |
| Edit seam test and carve width | ZO-11 🟡 | IC-19 🟢 | yes |
| Blur edge mode in the regression test | ZO-12 | IC-15 | no |
| `ru_maxrss` label | ZO-13 (part) | IC-27 | no |
| Kill-and-resume orphan processes | ZO-14 | IC-8 | no |
| U1 window, timing and zoom | ZO-15, ZO-21 | IC-12, IC-13, IC-30 | no |
| Changes to the spec not flagged | ZO-16 🟡 | IC-23 🟢 | yes |
| Pack command and header bounds | ZO-17 🟡 | IC-24 🟢 | yes |
| Missing imports | ZO-18 | IC-21 | no |
| `--per-worker-mib` default | ZO-20 🟢 | IC-14 🟡 | yes |
| Tag rule written twice | ZO-22 🟢 | IC-16 🟡 | yes |
| go-pmtiles for x86_64 only | ZO-23 | IC-18 | no |
| U4 pip option | ZO-25 | IC-17 | no |
| Failure test uses the wrong failure | ZO-26 | IC-25 | no |
| Banding test | ZO-27 | IC-29 | no |
| Cooldown against the pin rule | ZO-29 | IC-28 | no |

Unique to the zoom-out reviewer: ZO-13 (size split), ZO-19, ZO-24, ZO-28, ZO-29 (branch part).
Unique to the inventive catalyst: IC-1, IC-3, IC-20, IC-22, IC-26.
