# Stage 9: Implementation readiness, map trial specification

**Target:** `docs/design/map-trial-build.md`, DetailedRange lines 356–736 (plan items 1–57 and the Verification Plan). Reviewers could read the FeatureRange (1–355) for reference.

**Standard:** `implementation-readiness-standard.md` (EGM v0.1.1) for `egm-implementation-readiness` and `egm-codebase-details`; `egm-style` used its persona's own rules.

**Reviewers:**

| Reviewer | Status | Blocker | Major | Nit |
| --- | --- | --- | --- | --- |
| `egm-implementation-readiness` | completed (second attempt) | 0 | 17 | 7 |
| `egm-codebase-details` | completed (second attempt) | 2 | 9 | 8 |
| `egm-style` | completed (second attempt) | 0 | 9 | 9 |

All three hit a rate limit on the first attempt and finished on the retry. Each reviewer read the four earlier reports first. Findings that more than one reviewer raised are written out once, at the most complete entry. The other entries are kept as short cross-references so the numbering stays stable. The counts above are before this deduplication. After it, there are 2 Blockers, about 30 distinct Majors and about 20 Nits.

**Severity changes by the synthesizer:**

- CD-3 was rated 🔴 Blocker under the persona's "contradicts the code" rule. It is rated 🟢 Nit here: it cites a comment that does not exist, and a one-line edit fixes it.
- CD-1 and CD-2 stay 🔴 Blockers. Both claim to follow the spike but would give a different look (G8) and different measurements (G6).

**Claims checked again by the synthesizer:**

- `tools/spike/relief_style.py` line 59: `np.round(dark * BANDS) / BANDS`, with `BANDS = 3` (line 21). This gives four levels.
- Line 63 truncates alpha with `.astype(np.uint8)`.
- `android/spike/.../Stats.kt` lines 47–49 compute the rate as `1000 * n / sum(gaps)`, published once a second. Line 60 reads memory with `Debug.getPss()`.
- `AndroidApplicationConventionPlugin.kt` contains no "ADR" text.
- ADR 0007 decision 10 (line 78) allows unsigned loading in **debug** builds only.

## Verdict

**Could an engineer implement this without making impactful decisions? No, not yet.**

Many items are concrete: flags, signatures, data classes and paths. Four things stand in the way.

1. **The DetailedRange still describes the design before review (IR-1).** About 25 of the 57 items are changed or deleted by stage 8 fixes triaged K. An engineer would be working from two sources that disagree.
2. **Two items contradict the spike code they say they follow.**
   - The style steps would change the approved look (CD-1).
   - The frame-rate and memory measures differ from the ones that produced build 11's numbers (CD-2).
3. **Behaviour-changing choices are left open.** Examples:
   - where the lock is written (IR-3);
   - how Python is installed in the GDAL image and where tests run (IR-4, IR-5);
   - the image tag (IR-6);
   - the worker, thread and cache settings (IR-7);
   - the mask smoothing (IR-8);
   - how edits are rasterised, and in which distance unit (IR-9);
   - option (c)'s colour ramp (IR-11);
   - the Natural Earth source (IR-17).
4. **Three stage 8 fixes would cause defects if applied literally:**
   - ZI-8 adds a band-blur seam that the seam test cannot see (IR-10).
   - ZI-9 and ZI-10 together draw a false coast through land at the data extent's edge (IR-12).
   - ZI-1 and ZI-3 have the lock written into a read-only mount (IR-3).

Also:

- The Android setup would not pass `buildHealth` as written (CD-5).
- The library plugins cannot reuse `AndroidCommon.kt` unchanged (CD-4).
- ADR 0007, which ranks above this specification, needs a note for the `benchmark` build type (CD-7).

Most fixes are one sentence, one value or one named function. Several delete work:

- the `aapt2` lookup;
- the block logic in per-zoom mode;
- the `ActivityManager` memory call;
- the in-file marker for `base.pmtiles`;
- a duplicate notices heading.

**Recommended order of revision:**

1. Apply stage 8 and this report's challenges to it (IR-3, IR-10, IR-12, IR-14) in one pass.
2. Rewrite items 1–57 from the result, with an exact file list (IR-1) and phases (IR-2).
3. Fix CD-1 and CD-2 against the spike's code.

After that pass, the plan should be implementable without the engineer making impactful decisions.

---

## Implementation readiness reviewer (`egm-implementation-readiness`)

### IR-1. The plan items have not been updated for stage 8. 🟡 Major

- **Items / lines:** about 25 of items 1–57 (356–719).
- **Problem:** The stage 8 fixes triaged K delete, merge or reshape many items, but the DetailedRange still describes the earlier design:
  - ZI-1 deletes items 10 and 24 and changes 7, 22, 29 and 31.
  - ZI-2 merges items 18 and 19 and changes 12, 20 and 26.
  - ZI-3 deletes item 5 and changes 1, 4, 8 and 45.
  - ZI-4 changes items 4, 7, 13, 23 and 25.
  - ZI-8 changes items 16, 17 and 25.
  - ZI-10 changes items 14 and 22.
  - ZI-11 changes items 21, 38 and 41–44.
  - ZI-12 changes items 8, 11, 15, 25–28, 31, 48 and 50.

  Files are also missing from the list:
  - `seerah_map/__init__.py`;
  - a `__main__.py`, if ZI-3 runs `python -m seerah_map`;
  - `camera-path.json` (ZI-11);
  - the example's absent neighbour cell (ZI-9).
- **Fix:** Rewrite items 1–57 after the FeatureRange revision, not alongside it. Make the file list an exact write allowlist (standard, lines 29–33).

### IR-2. No phasing, so no checkpoint leaves the code buildable. 🟡 Major

- **Items / lines:** 1–57; Order of work at 202–210.
- **Problem:** The standard requires phases that can each be built and tested. The plan is a flat file list. Its dependencies are hidden:
  - item 46 needs item 47;
  - item 45's test job needs a published image;
  - item 33 must land before item 37.
- **Fix:** Add a table of five or six pull requests, each with its exit check:
  - A: image and unit tests (1–4, 9, 12–17, 25, 26, 45).
  - B: block stage, tile, pack and the example integration test (18–21, 27, 29).
  - C: base layer (22).
  - D: build logic and `:core:map` (32–40).
  - E: benchmark trial and release guard (35, 41–47).
  - F: docs (50–57).

### IR-3. The lock is written into a read-only mount. This challenges ZI-1 and ZI-3. 🟡 Major

- **Items / lines:** 4 (391), 10 (431), 31.
- **Problem:**
  - `run.py` mounts the private checkout read-only at `/data`. Yet `lock` (or `fetch` after ZI-1) writes `sources.lock` beside the profile in that checkout, so the first run fails.
  - Once the lock is written, it is an uncommitted change, so `build` refuses to run until the owner commits it. Nothing tells the owner this.
- **Fix:**
  - `fetch` writes a new lock to `/out/<profile>.lock`.
  - It prints the next step: "copy to `seerah-content/map/trial/` and commit".
  - Every mount of the private checkout stays read-only.

### IR-4. Installing Python packages in the GDAL image needs choices the plan does not make. 🟡 Major

- **Items / lines:** 1 (375), 2 (380).
- **Problem:**
  - Ubuntu's system Python is probably marked externally managed (PEP 668), so a plain `pip install` fails.
  - A virtual environment needs `--system-site-packages` to see GDAL's bindings (`osgeo`).
  - A pip-installed numpy can break `osgeo.gdal_array`, which was built against the system numpy.
  - `requirements.in` cannot say "numpy if the base lacks it". scipy pulls numpy in anyway.
  - `pip-compile --generate-hashes` run on the Fedora host may resolve for a different Python, so `--require-hashes` then fails inside the image.
- **Fix:**
  - Install into `/opt/venv`, created with `--system-site-packages`.
  - Keep numpy (and anything else GDAL links against) from the base image once step 1 confirms it is there.
  - Generate `requirements.txt` inside the base image and document the command in `tools/map/README.md`.
- **Merged:** CD-11, SY9-8.

### IR-5. Where and how the Python tests run is undefined. 🟡 Major

- **Items / lines:** 3 (385), 26, 28; Verification at 724.
- **Problem:**
  - Line 724 runs pytest "locally". The host has no GDAL, and `requirements-dev.txt` holds only jsonschema, pre-commit and ruff.
  - Pytest takes its settings from `tools/` and above, so it never reads `tools/map/pyproject.toml`. The `slow` marker and the `src/` path are not registered.
- **Fix:**
  - Run every Python test inside the image, through `run.py` or the `map` workflow.
  - Put `[tool.pytest.ini_options]` (markers, `pythonpath`, `--strict-markers`) in the root `pyproject.toml`.
  - Drop the pytest settings from item 3.
- **Merged:** CD-10.

### IR-6. ZI-3's tag-by-hash has no defined computation and no rule for a missing tag. 🟡 Major

- **Items / lines:** 1, 4, 45; ZI-3.
- **Problem:** ZI-3 leaves three things open:
  - how the hash is formed;
  - what `run.py` does when the tag is not yet in GHCR;
  - how a pull request that changes the Dockerfile gets an image for its test job.

  A local build gives a different digest, and so a different fingerprint.
- **Fix:**
  - One function in `run.py`: `sha256(Dockerfile + b"\0" + requirements.txt)[:16]`. CI calls `run.py --print-tag`, so there is one implementation.
  - `run.py` refuses when the tag is missing, unless `--build-local` is given. A local build is reported as `image: local` and is never used for step 3 or 4.
  - In CI, the test job builds a missing image in the same job and does not push it.
- **Related:** SY9-5.

### IR-7. The worker thread and memory settings would not take effect as written. 🟡 Major

- **Items / lines:** 13 (446), line 133; ZI-4.
- **Problem:**
  - `OMP_NUM_THREADS` and `OPENBLAS_NUM_THREADS` are read when numpy is imported. Setting them inside fork-started workers is too late.
  - `GDAL_CACHEMAX` has no value.
  - Under ZI-4, the worker count comes from cgroup `memory.max`. CI runs uncapped, so that file reads `max` and the division has no answer.
  - There is no starting per-worker allowance for the first step 1 runs.
  - Line 133 and item 13 list different variables.
- **Fix:**
  - `run.py` passes `-e OMP_NUM_THREADS=1 -e OPENBLAS_NUM_THREADS=1` (or the Dockerfile sets them with `ENV`).
  - The pool initialiser calls `gdal.SetCacheMax`, with a named constant of 256 MiB.
  - `--per-worker-mib` defaults to 1024.
  - When `memory.max` is `max`, the worker count is `--workers`, which defaults to 1.
  - Line 133 and item 13 use one list.
- **Merged:** CD-14 and part of SY9-7.

### IR-8. The land-mask smoothing is unspecified, and ZI-10 changes its output type. 🟡 Major

- **Items / lines:** 14 (451), 25 (515); line 137; ZI-2, ZI-10.
- **Problem:**
  - "Blurred with `smooth_px`" gives no kernel, no value and no place to set it. Line 137 calls it "a fixed radius".
  - The smoothing sets the coast's shape, which the owner judges.
  - ZI-10 needs a 0–255 byte mask, not `bool`.
  - ZI-2's border formula needs the radius.
- **Fix:**
  - `land_fraction(wbm, sigma_px) -> uint8`, using `gaussian_filter(truncate=3)` scaled to 0–255.
  - Land for the relief is `fraction >= 128`.
  - Add `style.mask_sigma_px` to the schema, with a starting value, marked as a trial value judged in step 2.
- **Merged:** part of SY9-7.

### IR-9. Rasterising the edits needs a library and a distance unit the plan does not name. 🟡 Major

- **Items / lines:** 15 (456), 2.
- **Problem:**
  - Turning GeoJSON longitude and latitude into a 3857 pixel mask needs a projection and a polygon fill. Neither shapely nor rasterio is a dependency.
  - `distance_m` could be Mercator metres or ground metres, about 10% apart at 25° N. The edit sizes at lines 200 and 561 are ground sizes.
  - The order of overlapping edits is undefined.
- **Fix:**
  - Rasterise with `gdal.Rasterize` into a MEM dataset, using `ALL_TOUCHED` for lines.
  - Compute distance with `scipy.ndimage.distance_transform_edt`, scaled to ground metres by `pixel_m × cos(row latitude)`.
  - Apply edits in file order.
  - Drop "pure" from the item's description and test it inside the image.

### IR-10. Banding is ambiguous, nothing compares the look with build 11, and ZI-8's fix creates a seam. This challenges ZI-8. 🟡 Major

- **Items / lines:** 16 (461–470), 17, 19 (485), 26 (`test_style`, `test_seams`, 526–528).
- **Problem:**
  - The band count is wrong (see CD-1).
  - No test compares the output with build 11's formula, so errors first show on a device.
  - **ZI-8's seam:** ZI-8 moves banding and band blur into the painted encoder. Item 19 encodes after cropping, so the 1.5 px band blur runs on cropped blocks and leaves a seam at every block edge.
  - `test_seams` compares the arrays before encoding (528), so it cannot catch that seam.
  - Sea-zeroing would no longer be the last step, so the band blur spreads darkness into the sea.
- **Fix:**
  - Keep one function, `darkness(…, banded: bool)`. The painted path passes `True` and the value path passes `False` (ZI-8's continuous value).
  - Set sea to 0 last, then crop, then encode. The encoders stay per-pixel.
  - Add a regression fixture: run `relief_style.py` once on a small synthetic elevation model and commit the output (under 1 MB). Assert that `darkness(banded=True)`, at latitude 0 with exaggeration 8, matches it within 1/255.

### IR-11. Option (c)'s colour ramp is unspecified. 🟡 Major

- **Items / lines:** 43 `Variants.kt` (641), 38 `StyleJson.kt` (605).
- **Problem:**
  - "The stepped ramp matching style C's bands and ink" gives no thresholds, no alpha values and no expression form.
  - Under ZI-8 the stored value is continuous, so the ramp alone decides where the bands fall. The item 3 comparison depends on it.
  - It is unverified whether `color-relief-color` accepts `step`.
- **Fix:**
  - Thresholds at the stored values 42.5, 127.5 and 212.5.
  - The ink colour with alpha 0, 0.2, 0.4 and 0.6.
  - If `step` is rejected, use `interpolate linear` with stops 0.5 apart.
  - Record in step 1 which form works.

### IR-12. ZI-9 and ZI-10 together draw a false coast at the data extent's edge. This challenges both. 🟡 Major

- **Items / lines:** 14, 18, 22, 29; extends DE-5.
- **Problem:**
  - Blocks are zoom-9 tiles, so they reach up to about 0.7° past the data extent. Those pixels have no locked cell.
  - Under ZI-9, every missing cell reads as ocean.
  - ZI-10's contour coast therefore draws a coast line across Jordan, Iraq, Iran and Ethiopia, and the overflow band becomes sea in the mask.
  - ZI-10's claim that contours "stop at the raster's edge" fails, because that edge lies inside the raster.
- **Fix:**
  - Only cells marked `absent: true` in the lock read as ocean.
  - Pixels outside the data extent are nodata, and contouring and polygonising run on the data-extent window only.
  - Clip Natural Earth to the data extent shrunk by a few pixels.
  - Give the example an absent cell and a window edge, and add one assertion in the integration test: no coast vertex lies on the window edge.

### IR-13. The seam tests cannot catch seams from warping, masking or edits. 🟡 Major

- **Items / lines:** 26 `test_seams` (528), 27.
- **Problem:** `test_seams` uses synthetic arrays only. Under ZI-2's fused stage, these all come from per-block GDAL reads that no test covers:
  - the warp border;
  - the mask smoothing;
  - the edit distances.

  G3 rests on those reads.
- **Fix:** In `test_build_example.py`, build the example twice: once at its small `block_zoom`, and once with a single block covering the window. Compare the decoded zoom-12 tiles, not the file bytes, since the fingerprints differ. This reuses the double build.

### IR-14. After ZI-2, the tile stage has no resume marker, and G4 and `test_workarea` contradict the fix. This challenges ZI-2. 🟡 Major

- **Items / lines:** 12, 20, 26 (`test_workarea`, 530), 27 (535); G4 (48).
- **Problem:**
  - ZI-2's fallback ("empty the tile folder and rerun") does not say how the build knows the tile stage was interrupted.
  - ZI-2 deletes the exclusive work area, yet `test_workarea` still asserts that a different fingerprint is refused, and G4 says the build "refuses to continue".
  - Item 27 kills the build only during the block stage.
- **Fix:**
  - Write `tiles.done` after the tiler succeeds. On start, if the tile folder exists without the marker, delete it, or resume it if step 1 shows GDAL writes tiles atomically.
  - Change the test to "a different fingerprint gets a new work area", and reword G4 to match.
  - Add a second kill point, during the tiler.

### IR-15. The device measurements differ from the spike's, and the 250 ms rule hides stalls during the camera path. 🟡 Major

- **Items / lines:** 43 (637–645), 44 (650); relates to DE-8 and ZI-5.
- **Problem:**
  - The definition differences are in CD-2.
  - Sending every `FrameRendered` into Compose state recomposes on each frame, which lowers the rate being measured on the Oppo A55.
  - The scripted path never pauses, so a gap over 250 ms during it is a real stall. The spike's rule drops it, and item 44's test ("one 300 ms pause ignored") locks that in. This risk comes from the owner's choice to keep the spike's definitions.
- **Fix:**
  - Aggregate outside Compose and publish once a second.
  - Add one overlay number: the longest frame gap in each path run. This does not change the owner's definitions.
  - Report memory as the maximum of the per-second samples taken during the path.

### IR-16. `check_no_trial_code.py` depends on a tool that is not on CI's PATH, and it can pass without checking anything. 🟡 Major

- **Items / lines:** 46 (662), 47 (667).
- **Problem:**
  - `aapt2` lives in `$ANDROID_HOME/build-tools/<version>/`, which is not on the runner's PATH. The plan does not say how to find it.
  - The step's command line is not given. The job runs from `working-directory: android` (`android.yml` lines 20–22).
  - A check that finds nothing passes the same way whether it works or is broken.
- **Fix:**
  - Read `AndroidManifest.xml` from the APK with `zipfile` and search for `app.seerah.trial` in UTF-16LE, as binary XML stores it. This uses only the standard library, like `check_page_alignment.py`.
  - Run it with `--expect-absent` on the release APK and `--expect-present` on the benchmark APK.
  - Separately, `check_page_alignment.py` already accepts several APKs (docstring line 10). Appending the benchmark APK path to `android.yml` line 41 settles ZO-15 with no new code.
- **Merged:** CD-9.

### IR-17. Natural Earth's source has no fixed version. 🟡 Major

- **Items / lines:** 22 (500), 25 (515), 29.
- **Problem:**
  - The spike downloads Natural Earth from the moving `master` branch (`build_base.sh` line 17). Trust-on-first-use hashes against a moving address fail on a later `fetch`.
  - The plan does not say whether the coast comes from land polygons or coastline lines. The spike used coastline lines (`build_base.sh` lines 46–47).
- **Fix:**
  - The lock records addresses pinned to a release tag or commit. Step 1 picks it.
  - Name the files: `ne_{110m,50m,10m}_{land,coastline}`.
- **Related:** CD-12.

### IR-18. The cross-file `$ref` cannot resolve. 🟢 Nit

- **Merged into CD-6.**

### IR-19. The camera limits have no source and no values. This fills a gap in ZI-11. 🟢 Nit

- **Items / lines:** 38 `CameraLimits` (603), 43 `Variants.kt` (641).
- **Problem:**
  - ZI-11 gives the camera stops a source but not the bounds or the minimum and maximum zoom.
  - Option (a)'s fade ends at 16.5, above the spike's cap of 16.
- **Fix:**
  - `pack` copies `camera_bounds` and `min_zoom` into `metadata.json`.
  - The maximum zoom is a named constant in `Variants.kt`, set to 17.

### IR-20. The look constants in `StyleJson.kt` have no stated source. 🟢 Nit

- **Items / lines:** 38 (605), 43.
- **Problem:**
  - Line 605 gives no values for the land, sea and coast colours, the coast-width ramp or `fill-antialias`.
  - G8 depends on these values. Line 358's "no code is copied from the spike" may keep the engineer from looking them up.
- **Fix:** State that the values come from build 11 (`android/spike/spike/src/main/kotlin/app/seerah/spike/MapStyle.kt` lines 31–32 and 86–98), copied as constants with a comment on their origin.

### IR-21. The build-logic helpers are typed for the application module only. 🟢 Nit

- **Merged into CD-4.**

### IR-22. `base.pmtiles` may not be able to carry the trial marker. 🟢 Nit

- **Items / lines:** 22 (500); G9.
- **Problem:** It is unverified whether tippecanoe or `tile-join` can write an arbitrary metadata key.
- **Fix:** Use the sidecar `metadata.json` (ZI-11) as the trial marker for every file in the output folder, and drop the in-file marker for `base.pmtiles`.

### IR-23. The launcher's path conventions and the container runtime are open. 🟢 Nit

- **Items / lines:** 4, 7.
- **Problem:** The path part is merged into SY9-6. Two runtime points remain:
  - Rootless Podman (possible on Fedora; unverified) needs `--userns=keep-id` instead of `--user`.
  - ZI-1's GLO-90 probe needs network access, but ZI-3 gives every command except `fetch` `--network none`.
- **Fix:**
  - Step 1 records the runtime, and `run.py` switches to `--userns=keep-id` under Podman.
  - The probe runs with network access, and the specification says so.

### IR-24. Small decisions still left to the engineer. 🟢 Nit

- **`edge_fade` (item 16):** the ramp runs inward from the data extent's edge over `fade_deg`. At corners it takes the smaller of the two ramps.
- **Per-zoom mode (items 19 and 20):**
  - The step 2 windows are small, so render each zoom's window as one array. This deletes the block logic for this mode.
  - Zoom range: `min_zoom` to 12.
- **`test_grid` (line 523):** it checks against "the table in this specification", which does not exist. Point it at ZI-7's recomputed values.
- **`StyleJsonTest` (item 39):** `org.json` is a stub in plain JVM tests. Use Robolectric, which is already in the catalogue.
- **Report (item 23):** a tile counts as inside the peninsula if its centre is inside the outline.
- **Probe (ZI-1):** name the exact WhiteboxTools tools and flags.

---

## Codebase details reviewer (`egm-codebase-details`)

### CD-1. The style steps contradict the spike code they claim to follow. 🔴 Blocker

- **Items / lines:** 16 (461–470), 17 (475), line 143.
- **Problem:**
  - **Bands.** Step 6, "quantise to `settings.bands` levels", reads as three levels. The spike computes `np.round(dark * BANDS) / BANDS` (`relief_style.py` line 59), which gives four levels: 0, ⅓, ⅔ and 1. An engineer who follows step 6 changes style C, which breaks G8.
  - **Shade clipping.** Step 4 omits that shade is clipped to [0, 1] (`relief_style.py` lines 55–57).
  - **Aspect.** No formula or sign is given. The spike uses `aspect = arctan2(-gx, gy)` with rows running north to south (lines 51–53).
  - **Band blur cutoff.** "Cut off at 3σ as the spike did" (143) holds for σ = 7, but not for the band blur. The spike's radius there is `int(1.5 × 3) = 4` (line 37), while `truncate=3.0` gives 5.
  - **Alpha.** The spike truncates alpha (line 63), and item 17 rounds it. Under ZI-8's pixel calibration against build 11, that shows as a systematic off-by-one.
- **Fix:**
  - Step 6: "round(d × bands) / bands (bands + 1 levels, as `relief_style.py` line 59)".
  - Step 4: "shade = clip(sin(alt)·cos(slope) + cos(alt)·sin(slope)·cos(az − aspect), 0, 1), with aspect = atan2(−gx, gy) and rows running north to south".
  - Band blur radius 4, or state the difference.
  - Truncate alpha, or state the difference.
  - These formulas go with banding wherever ZI-8 and IR-10 place it.
- **Merged:** SY9-4 and the banding part of IR-10.

### CD-2. The frame-rate, memory and open-time measures differ from the spike code that line 189 cites. 🔴 Blocker

- **Items / lines:** line 189; 43 (`FrameStats.kt`, `Memory.kt`, `TrialActivity.kt`; 637–645).
- **Problem:**
  - **Typical rate.** Item 43 says "frames in the last second". The spike's `Stats.kt` (lines 43–49) instead computes `1000·n / Σgaps` over the last 30 gaps of 250 ms or less, published at most once a second. "Lowest" is the minimum of those published values.
  - **Source of the error.** `docs/spike/measurements.md` line 7 still describes the older definition, so the plan copied the spike's document rather than its code.
  - **Memory.** `Memory.kt` uses `ActivityManager.getProcessMemoryInfo`. The spike used `Debug.getPss()` (`Stats.kt` line 60). Since Android 10, `getProcessMemoryInfo` is documented as rate-limited (external, not verified here).
  - **Open time.** "Records the process start time" leaves the clock open. The spike used `Process.getStartElapsedRealtime()` (`Stats.kt` line 28).
  - G6's numbers would therefore not compare with build 11's.
- **Fix:**
  - Quote `Stats.kt`'s algorithm in item 43, written anew, not copied: a 30-gap window, gaps of 1–250 ms, published once a second, and lowest = the minimum published value.
  - Use `Debug.getPss()` and `Process.getStartElapsedRealtime()`, so `TrialActivity` records nothing.
  - Mark `measurements.md` line 7 as out of date, or fix it.
- **Merged:** the definition part of IR-15. DE-8 and ZI-5 cover how runs are combined, not how one reading is defined.

### CD-3. Item 35 cites a comment that does not exist. 🟢 Nit (reviewer rated it Blocker)

- **Items / lines:** 35 (583).
- **Problem:** "ADR 0004's comment in the file points to the ADR note." `AndroidApplicationConventionPlugin.kt` contains no ADR reference. The only ADR 0004 signing comment is in `android/app/build.gradle.kts` line 28.
- **Fix:** "Add a comment on the `benchmark` block citing ADR 0004's dated note (item 52)." Also say that `initWith(release)` copies only the convention's release settings (plugin lines 27–31). The app module sets release signing itself (`app/build.gradle.kts` lines 26–31), so the explicit `signingConfig = debug` is required.
- **Merged:** SY9-11.

### CD-4. The library convention plugins cannot reuse `AndroidCommon.kt` as it is. 🟡 Major

- **Items / lines:** 33, 34 (570–578).
- **Problem:**
  - `configureAndroid` takes an `ApplicationExtension` (`AndroidCommon.kt` line 25). The application Compose plugin configures `ApplicationExtension` (line 14).
  - The licence check is `configureLicences` (`DependencyRules.kt` line 53), and it needs the Licensee plugin applied.
  - `enforceMapLibreBoundary` returns at once for `:core:map` (`DependencyRules.kt` line 39).
  - `configureModuleGraphRules` belongs to the application module only.
  - `AndroidCommon.kt` (Modify) is not in the file list.
- **Fix:**
  - Add `AndroidCommon.kt` (Modify): take AGP's common extension type (check its form in AGP 9.5.1).
  - Share the Compose setup between the two Compose plugins in one function.
  - In item 33, list the library plugin's exact calls: `com.android.library`, `app.cash.licensee`, `configureAndroid`, `configureKotlin`, `configureLicences`, `enforceMapLibreBoundary`.
  - Set `targetSdk` only in the application plugin.
- **Merged:** IR-21 and part of SY9-11.

### CD-5. `:core:map`'s dependencies would fail `buildHealth`. 🟡 Major

- **Items / lines:** 32 (568), 37 (595), 38, 39, 43.
- **Problem:** `android/build.gradle.kts` lines 29–35 fail the build on any dependency issue, and CI runs `buildHealth`. As written:
  - "As the application Compose plugin does" copies `ui-tooling-preview`, which `:core:map` does not use.
  - "Unit-test dependencies as in `:app`" copies `compose-ui-test-junit4` and `androidx-test-ext-junit`, which `StyleJsonTest` does not use.
  - Compose foundation is probably unused.
  - `Modifier` in the public signature makes `compose-ui` an `api` dependency, which the plan does not say.
  - These used libraries are missing from the catalogue:
    - `lifecycle-runtime-compose` (the catalogue has only `lifecycle-runtime`, line 42; the spike used the Compose version);
    - `kotlinx-coroutines-core`, for `suspend fun animateTo`;
    - probably `androidx.documentfile`, for the importer.
  - The `viewinterop` "if needed" is left open.
  - The JSON parser for `StyleJsonTest` is not named.
- **Fix:**
  - Give the exact declarations:
    - `api(compose-ui)`;
    - `implementation(maplibre-android-opengl, lifecycle-runtime-compose, kotlinx-coroutines-core[, compose-ui-viewinterop])`;
    - `testImplementation(junit4, kotlin-test, robolectric)`.
  - Leave preview tooling out of the library Compose plugin.
  - Add every new alias to item 32.
  - Settle `viewinterop` by checking the contents of BOM 2026.09.00 once.

### CD-6. The cross-file `$ref` to the content schema has no way to resolve. 🟡 Major

- **Items / lines:** 8 (421), 25 (512–515); line 127.
- **Problem:**
  - A relative `$ref` resolves against the referring schema's own location. Inside the installed package that location is `…/seerah_map/schema/content/schema/…`, which does not exist.
  - The content schema's `$id` is a GitHub URL (`content.schema.json` line 3).
  - `validate_content.py` line 39 builds its validator with no registry, so there is no pattern to reuse.
  - jsonschema does not fetch referenced files by itself (external).
  - `format: uri` in `$defs/source` is not enforced without a format checker.
  - The schema folder also has two paths: `tools/map/schema/` (127) and `tools/map/src/seerah_map/schema/` (512).
- **Fix:**
  - `config.py` loads `/src/content/schema/content.schema.json` into a `referencing.Registry` under its `$id`.
  - The edits schema refers to that absolute `$id`. This needs ZI-3's `/src` mount.
  - Use one schema folder path in both places.
- **Merged:** IR-18 and SY9-3.

### CD-7. ADR 0007 decision 10 allows unsigned loading in debug builds only, and the plan amends ADR 0004 but not ADR 0007. 🟡 Major

- **Items / lines:** 52, 53; G7 (51); line 37.
- **Problem:**
  - ADR 0007 line 78: "debug builds can load an unpacked content folder … without a signature."
  - The `benchmark` build type is not a debug build, yet it loads unsigned files.
  - AGENTS.md ranks accepted ADRs above this specification and says the lower source is fixed in the same change.
- **Fix:** Add `docs/adr/0007-content-formats-compatibility-and-signing.md` (Modify): a dated note under decision 10 that the `benchmark` build type may import unsigned trial map files (owner, 2026-11-14) and is never published. This does not reopen A3.

### CD-8. Documents that must change are not in the file list. 🟡 Major

- **Items / lines:** Target Files (368–719).
- **Problem:**
  - **`android/README.md`:**
    - line 45 says library conventions are "added with the first module that needs them";
    - the plugin table (39–43) needs the two library plugins;
    - the commands (27–32) and CI line (35) need `assembleBenchmark` and the second artifact;
    - line 66 should mention the build type.
  - **`docs/README.md`:** lines 30 and 37–39 list design specifications and the spike's documents one by one, so it needs entries for this specification and `docs/map-trial/`.
  - **`AGENTS.md`:** lines 69 and 72 (the commands and the `seerah-app` artifact).
  - **Review reports:** `docs/design/egm-review-map-trial/` is not in item 57, though ADR 0009 links its own review reports.
- **Fix:** Add these as Modify or Create entries.

### CD-9. `aapt2` is not on PATH, and the step's command line is not given. 🟡 Major

- **Merged into IR-16**, which also takes this finding's reuse of `check_page_alignment.py` for ZO-15.

### CD-10. The pytest command will not pick up the package settings, and the host has no test dependencies. 🟡 Major

- **Merged into IR-5.**

### CD-11. Installing Python packages in the GDAL image is unspecified. 🟡 Major

- **Merged into IR-4.**
- **Added:** installing `tools/map` "without dependencies" pulls an unpinned build backend, which undermines "same bytes" (line 110). Under ZI-3, code runs from `/src` with `PYTHONPATH` and nothing is installed, which removes the problem.

### CD-12. Item 22 drops the spike's proven tippecanoe settings. 🟡 Major

- **Items / lines:** 22 (500).
- **Problem:** The spike's fix for dropped polygons had three parts, and item 22 names none of them:
  - one tileset per scale;
  - the flags `--no-tile-size-limit --no-feature-limit --no-tiny-polygon-reduction` (`build_base.sh` line 38) and `tile-join --no-tile-size-limit` (line 54);
  - 1:50m and 1:10m land cut into 10° cells (lines 19–35).

  ZI-10 says "reuse the spike's fixes" without naming them.
- **Fix:**
  - List the flags and the 10° split in item 22.
  - Draw Natural Earth's coast from the coastline files (lines 46–53).
- **Related:** IR-17, which covers pinning.

### CD-13. The conventions for new workflows and Dependabot entries are not stated. 🟢 Nit

- **Items / lines:** 32 (568), 45, 48 (672).
- **Problem and fix:**
  - **Workflows.** Every workflow sets top-level `permissions: {}`, `persist-credentials: false` and `timeout-minutes` (`android.yml` lines 10, 17, 26). State these in item 45.
  - **Dependabot.** Every entry has `cooldown: { default-days: 7 }` and a `build`/`ci` commit prefix. Copy them in item 48.
  - **MapLibre version.** Item 32's "or the newest 13.x … at least two weeks old" conflicts with the 7-day cooldown and leaves a choice open. Pin 13.7.1, the spike's version (spike `build.gradle.kts` lines 7 and 86).
  - **Binary pins.** Dependabot cannot see the tippecanoe, go-pmtiles and WhiteboxTools pins. Document how to update them by hand in `tools/map/README.md`.
- **Merged:** the MapLibre and Dependabot parts of SY9-9.

### CD-14. The worker thread variables are set too late. 🟢 Nit

- **Merged into IR-7.**

### CD-15. Item 49 is mostly done already. 🟢 Nit

- **Items / lines:** 49 (677).
- **Problem:**
  - `.gitignore` already covers `dist/` (line 26) and `.pytest_cache/` (line 37).
  - Only `*.egg-info/` is missing, and it disappears under ZI-3.
  - `run.py` has no default `--out`, though item 49 assumes `map-data/dist/`.
- **Fix:** Keep only `*.egg-info/` (or drop the item under ZI-3), and give `--out` the default `map-data/dist/`.
- **Merged:** the `.gitignore` part of SY9-9.

### CD-16. Item 51 would duplicate the notices table and leave the MapLibre row wrong. 🟢 Nit

- **Items / lines:** 51 (687).
- **Problem:**
  - `THIRD_PARTY_NOTICES.md` lines 13–18 already list Natural Earth and Copernicus under "Downloaded by build scripts".
  - MapLibre sits under "Used by the technical spike only" (lines 20–24). That stops being true once the benchmark APK contains it.
  - "None is shipped in an app build yet" is untrue for MapLibre in the benchmark APK.
- **Fix:**
  - Add `tools/map/` to the existing rows, and add the tool rows to the same table.
  - Move the MapLibre row now.
- **Merged:** the first point of SY9-16.

### CD-17. Item 38 uses types it never defines. 🟢 Nit

- **Items / lines:** 38 (602).
- **Problem:** `MapColours` and `RampStep` are used but never defined.
- **Fix:**
  - `data class MapColours(val land: Int, val sea: Int, val coast: Int)`.
  - `data class RampStep(val value: Double, val colour: Int)`, with `value` stated in Terrarium metres, 0–255.

### CD-18. `webp_quality` has no default, and the converter choice is unchecked. 🟢 Nit

- **Items / lines:** 20, 21, 25.
- **Problem:**
  - Build 11 used WebP quality 80 (`build_base.sh` line 96), but the plan gives no default.
  - The spike called the `pmtiles` package's own command-line converter (line 98), not a custom wrapper. Whether that converter accepts a tile folder was not verified.
- **Fix:**
  - Default `webp_quality` to 80, citing the spike.
  - In step 1, check the package's command-line converter before writing a subprocess wrapper around `disk_to_pmtiles`.

### CD-19. The PMTiles test fixture breaks two repository rules. 🟢 Nit, supports ZI-11

- **Items / lines:** 44 (650).
- **Problem:**
  - AGENTS.md says never to commit generated map files.
  - `REUSE.toml` lines 6–10 would label the fixture Apache-2.0, although it is derived from Copernicus data.
- **Fix:** Delete the fixture, as ZI-11 already proposes.

---

## Style reviewer (`egm-style`)

### SY9-1. "Variant" means two things, so the overlay cannot tell three views apart. 🟡 Major

- **Items / lines:** 7 (416), 21 (495), 43 (641, 645); also 131, 165, 188, 189.
- **Problem:**
  - On the build side, `--variant {painted,value}` and the metadata field `variant` mean the encoding. That is a third name for it, beside `encoding` and `ReliefSource.Painted`/`Value`.
  - On the app side, "variants" are five views.
  - Zoom 12, zoom 11 and option (a) share one file, so the overlay shows the same name (`painted`) and the same fingerprint for all three. Line 189's promise that a screenshot "identifies everything" fails.
- **Fix:**
  - Rename the build flag to `--encoding` and drop the metadata `variant` field.
  - Give each app variant a fixed ID (`z12`, `z11`, `a`, `c`, `per-zoom`), and show that ID with the fingerprint.
  - Update lines 131, 441 and 495 to match.
- **Related:** ZO-13, CO-9, ZI-11.

### SY9-2. The block setting has two names, and "a small block zoom" means the opposite of what the example needs. 🟡 Major

- **Items / lines:** 9 (426), 25 (515), 29 (549); also 127, 133.
- **Problem:**
  - Lines 127 and 133 say "block size". The schema says `block_zoom`, where a higher value gives smaller blocks.
  - Item 29's "small block zoom so the example has several blocks" would put the 0.25° window into one or two blocks. A zoom-9 block is about 0.70° wide and a zoom-10 block about 0.35°.
  - The edit "crossing a block edge" may then have no edge to cross.
- **Fix:**
  - Item 29: "`block_zoom: 11` (blocks about 0.18° wide, so the window spans at least 2×2 blocks)".
  - Lines 127 and 133: "the block zoom (`block_zoom`, 9 for the trial)".

### SY9-3. The schema folder has two paths, and the `$ref` cannot resolve. 🟡 Major

- **Merged into CD-6.**

### SY9-4. Step 6's band count changes the approved look. 🟡 Major

- **Merged into CD-1.**

### SY9-5. The image build command contradicts the install step, and "the image's inputs" is defined two ways. 🟡 Major

- **Items / lines:** 1 (375), 45 (657); Verification (723); line 110.
- **Problem:**
  - `docker build tools/map` (723) cannot copy `tools/common`, which line 375 installs.
  - Line 110 rebuilds the image on any change to its inputs. Line 657 rebuilds only on a change to the Dockerfile, which misses `requirements.txt`.
- **Fix:**
  - Line 723: "`docker build -f tools/map/Dockerfile .` from the repository root". Under ZI-3's toolchain-only image (with ZI-12 removing `tools/common`), keep `docker build tools/map` and drop the install step instead.
  - Line 657: "changes `tools/map/Dockerfile` or `tools/map/requirements.txt`". ZI-3's tag hash needs the same rule, or CI tests a stale image.
- **Related:** IR-6.

### SY9-6. The launcher and the CLI name the same mounts differently, and paths are not marked as host or container paths. 🟡 Major

- **Items / lines:** 4 (390–395), 7 (416); also 169, 677.
- **Problem:**
  - The private checkout is `--private PATH` in `run.py` but `--data /data` in the CLI.
  - `--out` is a host path in one and a container path in the other.
  - `--profile` passes through `--` as a container path, yet `run.py` reads the profile on the host (394) with no rule for mapping one path to the other.
  - `--data /data` and the other flags read as required literal values, not defaults.
  - The output folder is "outside git" at line 169 but `map-data/dist/` at line 677.
- **Fix:**
  - Item 4: "`run.py --private HOST_PATH --out HOST_PATH` mounts them at `/data` and `/out`; every path after `--` is a container path."
  - Item 7: list each flag with its default.
  - Use one output default, `map-data/dist/` (ignored by `dist/`), at lines 169, 390 and 677.
  - `--private` is optional, for CI.
- **Merged:** the path part of IR-23.

### SY9-7. Several settings have no number or no home. 🟡 Major

- **Items / lines:** 13, 14, 16, 19, 25 (446, 451, 470, 485, 515).
- **Problem:**
  - The mask smoothing: see IR-8.
  - `GDAL_CACHEMAX`: see IR-7.
  - `webp_quality`: see CD-18.
  - Line 470's defaults leave out the ink colour (150, 118, 72 in the spike) and the maximum alpha (0.6).
  - Line 485 scales "the blur", but the style has two blurs.
- **Fix:**
  - Add `ink_rgb` and `max_alpha` to line 470.
  - Line 485: "`blur_px` and `band_blur_px` are both divided by 2^(12 − zoom)", or state that `band_blur_px` stays fixed.

### SY9-8. The numpy line is a hedge the engineer cannot act on. 🟡 Major

- **Merged into IR-4.**

### SY9-9. Hedges leave choices to the engineer. 🟡 Major

- **Items / lines:** 32, 41, 43, 48, 49; heading at 360 (lines 360, 568, 626, 639, 641, 672, 677).
- **Problem and fix** (the MapLibre and Dependabot points are in CD-13; `.gitignore` is in CD-15):
  - **626, "as needed":** "no trial-only test dependencies".
  - **639, "a margin":** give a number, for example "its size plus 100 MiB" (owner's value). This applies if the importer stays under ZI-11.
  - **641, "a trial variable":** "a constant in `Variants.kt` that step 2 may change".
  - **568, `viewinterop` "if needed":** see CD-5.
  - **360, "(open to correction)":** "Choices made while drafting; not owner decisions; the owner confirms or changes them at the step 1 checkpoint."

### SY9-10. The plan breaks its own rule on paths. 🟢 Nit

- **Items / lines:** 358; items 7–24, 26, 27 (532), 33 (570).
- **Problem:** Line 358 says paths are relative to the repository root, but items 7–24 give bare file names. `tests/test_build_example.py` could mean `tests/` at the root or `tools/map/tests/tests/`.
- **Fix:** Give the full path in every item heading, or amend line 358.

### SY9-11. Item 35 cites a comment that does not exist. 🟢 Nit

- **Merged into CD-3 and CD-4.**

### SY9-12. The CI cache covers "one cell", but the example lock also holds Natural Earth. 🟢 Nit

- **Items / lines:** 29 (549), 45 (657); line 196.
- **Problem:** As written, CI downloads Natural Earth on every run.
- **Fix:** Line 657: "the download cache for every file in the example lock, keyed on the lock's hash". Match line 196.

### SY9-13. `FrameRendered(val nanos: Long)` does not say whether it is a timestamp or a duration. 🟢 Nit

- **Items / lines:** 38 (604), 43 (642).
- **Fix:** Rename it `timestampNanos`, with KDoc: "`System.nanoTime()` when the frame listener fired".

### SY9-14. The number of camera-path runs is stated three ways. 🟢 Nit

- **Items / lines:** 644; also 50, 190.
- **Problem:** "Three times" (644), "at least three times" (50) and "three or more" (190).
- **Fix:** "Three runs, fixed in `CameraPath.kt`", and change lines 50 and 190 to match.

### SY9-15. The Verification Plan names the wrong list, and two of its claims cannot be checked. 🟢 Nit

- **Items / lines:** 728, 729.
- **Problem:**
  - Line 729 says "the facts listed under Open Questions", but the facts are in "Facts this plan rests on" (73). Open Questions holds checks still to run.
  - Line 729's "import, variant switching, the overlay and the camera path work" has nothing observable to check.
  - Line 728 calls `workarea` and `strict_yaml` pure, though both touch files, and leaves out `config`.
- **Fix:**
  - Line 729: name the numbered step 1 checks (ZI-13).
  - Line 729: give observable checks, for example "a folder of two variants imports; both appear in the list; the overlay shows the ID and fingerprint; the camera path finishes three runs".
  - Line 728: split the modules into pure modules and file I/O modules, and add `config`.

### SY9-16. Two claims are not calibrated, and status is written inconsistently. 🟢 Nit

- **Items / lines:** 31 (558), 53 (698–699), 57 (716).
- **Problem:** The first point (item 51) is in CD-16. The others:
  - The README says the values are "refused by packaging", but no packaging step exists yet.
  - Item 53 says "already applied" under Type / Scope.
  - Item 57 marks two files that already exist as Create.
- **Fix:**
  - "Marked `purpose: trial`, which the future packaging step must refuse."
  - State status in one form in Proposed Changes.
  - Replace "at its final path" with `docs/design/map-trial-build.md`.

### SY9-17. The Type / Scope fields are not parallel, and some requirements appear only there. 🟢 Nit

- **Items / lines:** 4, 5, 26, 44, 53.
- **Problem:** Some requirements appear only in Type / Scope, where a reader of Proposed Changes misses them:
  - "standard library only" (4);
  - "no downloads; each file under 1 MB" (26);
  - "run by `check`" (44).
- **Fix:** Make Type / Scope a noun phrase only, and move every requirement into Proposed Changes.

### SY9-18. Minor wording. 🟢 Nit

- **Line 397:** "an actionable message" should refer to item 7's definition of an error message.
- **Line 495:** `name` and `image` need stated values: "the profile file's stem" and "the full digest reference".

### Notes on the stage 8 triage (style)

- **SY-13 (cut):** the cut stands for 1–355. In the DetailedRange, items 25 and 45 pack several definitions into one paragraph, and SY9-3 and SY9-5 hid inside them. Split them when ZI-12 and ZI-3 rewrite those items.
- **ZI-1's `<profile>.lock`:** it adds a third name for the lock. Rename every `sources.lock` in one pass (lines 129, 200, 431, 546, 549, 558, 657).
- **ZI-7 moves screen widths to dp:** rename `largest_screen_px` and `screen_px` (426, 515) to `_dp`.

---

## Claims verified against the repository

Checked by `egm-codebase-details`. Lines marked * were also checked by the synthesizer.

| Claim | Plan line | Evidence | Result |
| --- | --- | --- | --- |
| Ruff settings, Python 3.11 syntax | 358 | `pyproject.toml` lines 2–6 | Correct. No `D` rules, so the docstring rule at line 411 is not enforced |
| Kotlin uses spotless, ktlint and the Compose rules | 358 | `android/build.gradle.kts` lines 13–26 | Correct |
| ADR 0007 requires YAML 1.2 core with duplicate keys rejected; library unchosen | 362 | ADR 0007 lines 26, 86 | Correct |
| `validate_content.py` has no YAML loader to reuse | 362, item 28 | `validate_content.py` lines 25–30 | Correct (JSON only) |
| `$defs/review` is a string enum; `$defs/source` requires `kind`, `work` and `locator`, with no synthetic kind | 456, 515 | `content.schema.json` lines 7–9, 42–52 | Confirms IC-12 and ZI-12 |
| `configureAndroid`, `configureKotlin` and `enforceMapLibreBoundary` exist | 573 | `AndroidCommon.kt` lines 25, 46; `DependencyRules.kt` line 38 | Names correct; `configureAndroid` is typed to the application (CD-4) |
| A licence check exists | 573 | `DependencyRules.kt` line 53 (`configureLicences`) | Exists, under another name |
| An ADR 0004 comment exists in the application plugin * | 583 | `AndroidApplicationConventionPlugin.kt` | **Incorrect** (CD-3) |
| Current build types are release only; debug signing is set in the app module | 583 | plugin lines 27–31; `app/build.gradle.kts` lines 26–31 | As described in CD-3 |
| Plugin registration and alias naming | 568, 578 | `convention/build.gradle.kts` lines 17–31; `libs.versions.toml` lines 72–74 | Consistent |
| `settings.gradle.kts` has only `:app` | 588 | lines 20–22 | Correct |
| `:app` is barred from MapLibre; `:app → :core:*` is allowed | 182 | `DependencyRules.kt` lines 10–11, 24, 38–50 | Correct |
| LeakCanary and StrictMode are debug-only | 184 | `app/build.gradle.kts` line 50; `SeerahApplication.kt` line 12 | Correct |
| MapLibre and `viewinterop` not in the catalogue; Compose BOM 2026.09.00 | 568 | `libs.versions.toml` | Correct |
| The spike uses MapLibre 13.7.1, `android-sdk-opengl`, with R8 | 78, 184, 568 | spike `build.gradle.kts` lines 7, 43–47, 86 | Correct |
| Spike relief `tileSize` 256; `pmtiles://file://` sources; fade 12.5–14.5 | 605, 614, 641 | `MapStyle.kt` lines 85–86, 98 | Correct |
| The spike uses the frame and fully-rendered listeners, and calls `MapLibre.getInstance` once | 68, 606 | `Stats.kt` lines 25–27; spike `MainActivity.kt` line 67 | Correct |
| Spike measurement definitions * | 189, 642 | `Stats.kt` lines 39–64; `measurements.md` lines 7–10 | **Incorrect** for the typical rate and memory (CD-2) |
| Style default values (blur 7, exaggeration 8, light 315°/45°, contrast 1.6, 3 bands, band blur 1.5, ink, maximum alpha 0.6) * | 146, 470 | `relief_style.py` lines 18–23, 58–63 | Values correct; quantisation, blur radius and alpha rounding described wrongly (CD-1) |
| `truncate=3` matches the spike's 3σ cutoff | 143 | `relief_style.py` line 37 | True for σ = 7, false for σ = 1.5 |
| Natural Earth at three scales, one tileset per scale, joined; tiles averaged down | 148, 156, 500 | `build_base.sh` lines 16–54, 97 | Correct; the flags, the 10° split and the pinning are omitted (CD-12, IR-17) |
| `android.yml` steps, path filter, release-only alignment check, actions pinned by commit | 657, 662 | `android.yml` lines 6, 24–48; `ci.yml` lines 17–26 | Correct; `android/core/**` is already covered |
| Dependabot has pip at `/` and no docker entry | 672 | `dependabot.yml` lines 12–16 | Correct |
| `.gitignore` covers `dist/` and `.pytest_cache/`, but not `*.egg-info` | 677 | `.gitignore` lines 26, 37 | Correct (CD-15) |
| `tools/README.md`, `THIRD_PARTY_NOTICES.md` and `REUSE.toml` exist as described | 548, 682, 687 | the files | Correct; notices duplication in CD-16 |
| `map-data/README.md` says ADR 0009 is proposed | 554 | line 3 | Correct (out of date, as item 30 says) |
| `open-decisions.md` has the bounds/zoom and reconstruction rows | 704 | lines 9–10 | Correct |
| `roadmap.md` says ADRs 0003 and 0004 are Proposed | 709 | line 43 | Correct (out of date) |
| Pre-commit runs ruff, markdownlint, REUSE and zizmor, with a 1 MB file limit | 521, 650, 726 | `.pre-commit-config.yaml` | Correct; actionlint also runs |
| ADR 0004 build types, and its plan for library convention plugins | 573, 694 | ADR 0004 lines 32, 36 | Correct |
| ADR 0003 module path `android/core/map` and package naming `app.seerah.<module>` | 592–597 | ADR 0003 lines 146, 194 | Correct |
| ADR 0007 decision 10 * | 37, 51 | ADR 0007 line 78 | Covers debug builds only (CD-7) |
| ADR 0009 amendments applied; decisions 8 and 13; edits reuse the content schema's types | 45, 120, 128, 699 | ADR 0009 lines 3, 127, 137–172, 193–204 | Correct |
| Terrarium R=128, G=d·255, B=0 encodes 0–255 m | 146, 475 | arithmetic | Correct |
| `adb push` path for ZI-11 | ZI-11 | `app/build.gradle.kts` line 12 plus the `.benchmark` suffix | Consistent |
| ZI-8's calibration 8 × cos 23° | ZI-8 | `relief_style.py` line 51; `build_base.sh` line 72 | Correct (the spike has no latitude factor) |

**Stage 8 against the repository:** `egm-codebase-details` found no stage 8 fix that conflicts with the repository's current contents. The repository also supports these points:

- ZI-12 (the loader lives in `seerah_map`): ADR 0007 line 62 says the content tool grows out of `validate_content.py`.
- ZI-3 (code runs from `/src`): every tool in `tools/` already runs in place.
- ZI-13's path-filter point and ZO-15.

`egm-implementation-readiness` found three stage 8 fixes that cause defects (IR-3, IR-10, IR-12).

## Claims not verifiable

| Claim | Plan line | Why |
| --- | --- | --- |
| ruamel.yaml's YAML 1.2 core mode leaves dates unresolved; PyYAML is YAML 1.1; strictyaml returns text | 362, 542 | External library behaviour |
| GDAL 3.13.3 image tag; `gdal raster tile` flags; `--resume` behaviour; WebP options; atomic tile writes | 76, 490 | External; no repository file uses them |
| `ubuntu-small` contents: Python version, pip, PEP 668, numpy, `python3-gdal` | 69, 375 | External image (IR-4) |
| `disk_to_pmtiles` sorting and `hash()` deduplication; `pmtiles verify` and `show --header-json`; whether the CLI converter accepts a folder | 77, 495 | External tools |
| Whether tippecanoe or `tile-join` can write arbitrary metadata keys | 500 | External (IR-22) |
| `color-relief` since MapLibre 13.0.0; whether its colour accepts `step`; `maxzoom` on a `pmtiles://` raster source | 68, 78, 641 | External (IR-11) |
| Licensee accepts MapLibre's declared licence | 192 | External POM; BSD-2-Clause is on the allowed list (`DependencyRules.kt` line 14) |
| BOM 2026.09.00 places `AndroidView` in `ui-viewinterop` | 568 | External |
| AGP 9 runs `testBenchmarkUnitTest` under `check`; DAGP and modules-graph-assert analyse `benchmarkImplementation` | 650, 724 | Needs a build |
| R8 keeps manifest activity names; where `aapt2` sits on runners | 667 | External (IR-16) |
| `getProcessMemoryInfo` is rate-limited | 643 | Android documentation (CD-2) |
| jsonschema does not fetch `$ref` files; pytest's root-directory selection | 515, 724 | External library behaviour (CD-6, IR-5) |
| zizmor's cache-poisoning audit on the `map` workflow | 657 | Depends on the actions chosen |
| Natural Earth release tag or commit | 500 | External (IR-17) |
| Docker or Podman on the owner's machine | 112, 393 | Host setup (IR-23) |
| Copernicus facts (26,450 cells; 616/529/87; Water Body Mask class values) | 69, 75 | External data |
| Contents of `seerah-content/map/trial/` | 200, 561 | Private repository not available |
