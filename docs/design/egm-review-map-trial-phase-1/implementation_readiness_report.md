# Stage 9: Implementation readiness, map trial phase 1 plan

**Target:** `docs/design/map-trial-phase-1-plan.md` (DetailedRange lines 122-725; lines 1-121 read for reference)
**Standard:** `resources/document_standards/implementation-readiness-standard.md` (EGM 0.1.1) for the implementation-readiness and codebase-details lenses; the style persona's rules for the style lens.
**Earlier reports read:** `zoom_out_report.md`, `comprehension_report.md`, `critic_report.md`, `intermediate_zoom_out_report.md` (stage 8).

## Reviewer status

| Reviewer | Status |
| --- | --- |
| `egm-implementation-readiness` | completed |
| `egm-codebase-details` | completed |
| `egm-style` | completed |

Each reviewer ran once. None of them opened URLs or ran code. Wherever a finding depends on how GDAL, WebP, Protomaps, GitHub or Dependabot behave, it is marked "(reasoned)": the reviewer worked it out but did not test it.

## Verdict

**Could an agent execute each task without impactful decisions? No, not yet.**

1. **None of stage 8's fixes are in the text yet.** Stage 8 marked a set of fixes "change the plan now" (K), but the plan has not changed since stage 8: line numbers in the earlier reports still match the text. MAPTEST is unchanged, and U6, U7, `--print-tag`, the host-tests folder, the numpy pin and the provenance fallback are all still missing. So every stage 8 finding stands, including the **ZI-1 Blocker**.
2. **The plan leaves data contracts to the implementer.** Several of these become formats that later phases depend on:
   - the profile and edits schemas;
   - the run report's fields;
   - the `camera-path.json` shape, which phase 2 parses;
   - the Copernicus and Natural Earth URL templates;
   - what a bare `clean` does.
3. **Some acceptance checks would fail for the wrong reason.** Check 6 would fail under lossy WebP, the stage 8 expected-tile set disagrees with what the tiler writes, and the Task 7 regression fixture cannot be built as described.
4. **Owner gates are missing.**
   - U2 to U5 (and U6 and U7, once they are added) have no "stop until the owner confirms" step.
   - Task 16's exit gate needs a pull request. AGENTS.md says an agent opens one only when the owner asks, and the plan lists no owner action for it.

**Task readiness (implementation-readiness lens):**

- **Ready once their stage 8 fixes land:** Task 3 (Grid), Task 4 (Work areas), Task 5 (Land mask), Task 8 (Encoders).
- **Ready after ZI-10 and the Nits below:** Task 6 (Edits).
- **Not ready:** Tasks 1, 2, 7, 9, 10, 11, 12, 13, 14, 15 and 16.

The plan's claims about existing repository files are almost all accurate (see *Claims verified*). The gaps are details that an existing file would settle but the plan leaves out, plus a few interfaces the plan quietly changes from the approved spec. No spike code or structure is copied into v1. Building the regression fixture by running `relief_style.py` counts as learning from the spike, which AGENTS.md allows.

**Severity breakdown (new findings at this stage):**

| Lens | Blocker | Major | Nit | Total |
| --- | --- | --- | --- | --- |
| Implementation readiness (IR) | 0 | 17 | 9 | 26 |
| Codebase details (CD) | 0 | 4 | 14 | 18 |
| Style (SY9) | 0 | 13 | 3 | 16 |
| **Total** | **0** | **34** | **26** | **60** |

The style reviewer's summary line said "12 Majors and 4 Nits". Its numbered list contains 13 Majors (SY9-1 to SY9-13) and 3 Nits (SY9-14 to SY9-16). The table counts the list. Stage 8's ZI-1 Blocker is not counted here, because it is not a new finding, but it is still open.

**Overlaps between lenses.** Several issues were found by more than one reviewer. Each finding is kept under its own lens, and the related IDs are cross-referenced:

| Issue | Findings |
| --- | --- |
| Fetch URL templates and `ne_base = ...` | IR-5, CD-7, SY9-11 |
| Task 7 regression fixture and `pixel_m` | IR-4, CD-6, SY9-13 |
| Task 11 `resume` hedge | IR-9, SY9-2 |
| Task 16 pull request and owner gate | IR-16, CD-14, SY9-5 |
| Task 15 clean-checkout scope | IR-15, CD-13, SY9-8 |
| `worker_count` tuple against bare ints | IR-22, CD-10, SY9-4 |
| Example edit marked `review: approved` | IR-24, CD-11 |
| Dependabot entries | IR-25, CD-16 |
| Earth circumference written twice | CD-4, SY9-9 |
| Task 14 "FAIL until wired" | IR-14, SY9-6 |
| Missing entries in Files lists | IR-17, CD-9 |

## Implementation readiness (`egm-implementation-readiness`)

### IR-1. Major. Tasks 1, 13 and 16 (lines 154-159, 672-692; decisions table lines 65-68)

- **Problem:** only Task 14 has a "stop until the owner confirms" step (line 602).
  - U3, U4 and U5 are "Needed by Task 1", and U2 is needed by Task 16, but neither task says whether to wait for the owner or go ahead on the proposal.
  - The same gap applies to stage 8's U7, which decides whether Task 13 builds `--mode`, and U6, which sets Task 13's default.
  - An agent will either stall or quietly adopt the proposals. That breaks the owner's 2026-11-14 rule that unresolved decisions are named, not silently made.
- **Fix:** add the stop steps:
  - Task 1: "Step 0: the owner confirms U3, U4 and U5. Do not continue until they are confirmed."
  - Task 13: the same step for U6 and U7.
  - Task 16: the same step for U2.

  Alternatively, state in the decisions table that approving the plan approves every proposal, and mark each row "Approved with the plan".

### IR-2. Major. Task 1 (line 27, lines 161-162)

- **Problem:** the rule "a new pin is the newest release at least two weeks old" has no method for Python. pip-compile resolves every transitive package to its newest version. Following the rule therefore means choosing between hand-pinning, a resolver with a date cut-off, or applying the rule to direct dependencies only, and that is a supply-chain policy decision. pip-tools is also not in the base image (ZO-6).
- **Fix:** state the rule for Python:
  - Each direct dependency in `requirements.in` is pinned with `==` to its newest release that is at least 14 days old on the implementation date.
  - Transitive pins are whatever pip-compile resolves, and the plan records this as the exception.
  - pip-tools is installed unpinned, only in the throwaway container that runs pip-compile.

### IR-3. Major. Task 2 (lines 180-186, 200-202; fixtures at line 173)

- **Problem:** "fields as in the spec's Inputs" does not define the profile and edits schemas. These files become the format of the private trial profiles in phase 3. As written, the implementer would have to decide:
  - the YAML key names;
  - whether `style` is optional and has defaults;
  - where `Profile.name` comes from;
  - what `peninsula_outline` is relative to;
  - which key in `edits.yaml` holds the list of edits;
  - the required fields for each operation, and the sign convention for `carve`;
  - the value ranges (`block_zoom <= max_zoom`, WebP quality 0-100);
  - whether `additionalProperties` is allowed;
  - whether `feather_m: 0` is allowed. If it is, the weight `1 - d / feather_m` at line 331 divides by zero.
- **Fix:** add a field table for each schema (key, type, required, range, default), or give the full contents of `profile_ok.yaml` and `edits_ok.yaml`. Settle `feather_m` either with `exclusiveMinimum: 0` or with "0 means a hard edge". Allow `band_blur_px: 0`, which stage 8's ZI-9 banding test needs.

### IR-4. Major. Task 7 (lines 342, 351-355, 363). See also CD-6 and SY9-13

- **Problem:** the regression fixture for stop gate G8 cannot be made without design choices.
  - The generator script has no entry in the Files list.
  - The hill and ridge parameters are not given.
  - The pixel size in the ENVI file is not given, and the `.npz` does not store it, but the test reads "pixel_m from the fixture's grid".
  - `/src` is read-only, so `relief_style.py` has nowhere to write its output.
  - The fixture must contain sea and steep slopes, or the test passes trivially.
- **Fix:**
  - Add `tools/map/tests/fixtures/make_spike_relief.py` to the Files list. It is committed and run once.
  - Fix the fixture's parameters: grid size, pixel size `grid.pixel_m()`, hill amplitude and σ, a ridge, and a sea corner below 0.5 m.
  - Store `pixel_m` in the `.npz`.
  - Give the exact `docker run` command, which writes to `/out` or a temporary folder.

### IR-5. Major. Task 9 (lines 416-420, 424-438). See also CD-7

- **Problem:** the download addresses are partly undefined.
  - `ne_base: str = ...` is literally elided.
  - Neither the DEM path nor the WBM (Water Body Mask) path is given.
  - Neither the location of `tileList.txt` nor the format of its lines is given.
  - The fake fixtures will copy whatever format the agent guesses, so the tests can pass while real downloads fail.
- **Fix:**
  - Write out the templates: `{DEM_BASE}/{cell}/{cell}.tif`, `{DEM_BASE}/{cell}/AUXFILES/{cell minus _DEM}_WBM.tif`, `{DEM_BASE}/tileList.txt`, and `NE_BASE = https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{NATURAL_EARTH_COMMIT}/geojson`.
  - Add a step before the tests that reads the real `tileList.txt` and one cell folder, then copies that exact layout into the fixture.

### IR-6. Major. Tasks 9 and 13 (lines 426-430, 577)

- **Problem:** three lock cases are not handled.
  - If the profile's camera bounds or margin are edited, the lock's cells no longer match `cells_for(profile.data_extent)`. `build` would then read the wrong cells.
  - The plan does not say whether later runs download `tileList.txt` again or trust the lock.
  - `urllib` has no timeout, so a stalled download hangs CI.
- **Fix:**
  - `fetch` and `build` both check that the lock's cells equal `cells_for(extent)`. If they do not, exit 1 with "the lock does not match the profile; move the lock aside and run fetch". Add a test for this case.
  - `tileList.txt` is read only when no lock exists.
  - Call `urlopen(..., timeout=60)`.

### IR-7. Major. Tasks 3 and 11 (lines 230, 507). This challenges stage 8's ZI-6

- **Problem:** ZI-6 computes `expected_tiles` from the data extent. But the tiler covers the union of the style blocks. Blocks are whole zoom-9 tiles, so above `block_zoom` they cover more than the data extent.
  - The folder will hold more tiles than expected. The completeness check only looks for missing tiles, so it still passes.
  - The report's "expected" and "written" counts will disagree, and an agent may "fix" this by clipping the VRT. That would resample the data (reasoned).
- **Fix:** go back to IC-10's form. `expected_tiles` is the set of tiles at zooms 0 to `max_zoom` covered by the windows of `grid.blocks(data_extent, block_zoom)`, and it is computed from the grid. Keep ZI-6's check that every block file exists before the VRT is written. State that tiles outside the data extent are transparent and expected.

### IR-8. Major. Tasks 11 and 12 (lines 494-507, 529, 545)

- **Problem:** the plan never fixes what the tile folder contains, yet `disk_to_pmtiles` walks that folder.
  - It does not say where `tiles.done` lives.
  - `gdal raster tile` may write web-viewer files by default (reasoned).
  - Stray files in the folder may break the pack (reasoned).
- **Fix:**
  - Put the marker at `<area>/tiles.done`, beside `<area>/tiles/`.
  - Pass the tiler's option that turns off web viewers, if GDAL 3.13 has one.
  - Make `test_tile_small_vrt` assert that the folder holds only `z/x/y.<ext>` files, plus `metadata.json` after packing.

### IR-9. Major. Task 11, Step 5 (line 512, check 5). See also SY9-2

- **Problem:** "run_tile_stage *may* pass resume=True" leaves the behaviour to the agent. Spec line 283 says the stage *is* resumed if tiles are written atomically. The code change would come after the task's tests have passed, so nothing would test it.
- **Fix:** write check 5 as a branch with both outcomes:
  - If check 5 shows that tiles are written to a temporary name and renamed, change `run_tile_stage` to resume a folder without `tiles.done`, and replace `test_folder_without_done_is_retiled` with `test_folder_without_done_is_resumed`.
  - Otherwise, change nothing.

  Record the result either way, and give the exact scratch command (for example, `strace` inside the image).

### IR-10. Major. Task 11, Step 5 (line 513, check 6)

- **Problem:** "RGB equals the ink wherever alpha is above 0" would fail on a WebP tile for a reason unrelated to averaging. Lossy WebP at quality 80 changes RGB by a few levels, especially at alpha edges (reasoned). An agent would then either stop or quietly loosen the check.
- **Fix:** run check 6 on a PNG tiling of the same small VRT (`-f PNG`), where RGB must equal the ink exactly. Record the largest RGB deviation in the WebP tile for information only.

### IR-11. Major. Task 12 (lines 531, 539-540)

- **Problem:** "Report, a dataclass with the fields in the spec's run report" names categories, not fields. The key names, nesting, types and units of `report.json` would be the implementer's design. Phase 3's step reports read this file, and the tests already assume `peak_bytes` and `oom_kills`.
- **Fix:** list every field with its type and unit:
  - `provenance{tool_commit, data_commit, image, fingerprint}`;
  - `stage_seconds{block, tile, pack}`;
  - `memory{peak_bytes, peak_reason, oom_kills, largest_child_rss_kib}`;
  - `disk{work_area_bytes_after_tiling}`;
  - `tiles{expected, written, stored}`;
  - `size_by_zoom[{zoom, inside_bytes, outside_bytes}]`, from stage 8's ZI-11;
  - `edits{applied[], skipped[]}`;
  - `workers{count, reason, gdal_cachemax_mib}`.

  Note that `ru_maxrss` is in KiB on Linux.

### IR-12. Major. Tasks 13 and 14 (line 567 against lines 613 and 616)

- **Problem:** the plan never says what a bare `clean` does, and it contradicts itself. Line 613 uses `clean` to remove areas, but line 616 uses it to list them. Choosing a destructive default is a decision the agent should not make.
- **Fix:**
  - A bare `clean` lists the areas and their sizes and removes nothing.
  - `clean --fingerprint F` removes area F.
  - `clean --all` removes every area.

  Add a test for each, and change line 613 to `clean --all`.

### IR-13. Major. Task 14 (line 608)

- **Problem:** Task 14 creates `camera-path.json`, but no part of the plan defines its JSON shape. Phase 2's `TrialFiles.kt` will parse this file, so whatever key names the agent chooses become a contract between phases.
- **Fix:** write out the exact JSON. For example: `{"stops":[{"lat","lon","zoom","duration_ms"}], "limits":{"west","south","east","north","min_zoom":4,"max_zoom"}}`, with zoom in MapLibre units. Alternatively, move the file to phase 2 and list that as a deviation.

### IR-14. Major. Task 14, Step 4 (line 619). See also SY9-6

- **Problem:** "Expected: FAIL until all the earlier tasks are wired" means that failures found with real data will be fixed in source modules that are not in Task 14's Files list. The plan gives no rule for those changes.
- **Fix:**
  - A failure found here is fixed in the module that owns it, in its own `fix(map): ...` commit, with a regression test in that module's test file.
  - List those module paths in Task 14's Files list as "Modify only to fix a failure these tests find".

### IR-15. Major. Task 15, Step 3 (line 650). See also CD-13 and SY9-8

- **Problem:**
  - "The clean-checkout check covers the repository that holds --data" narrows spec line 179, which says both checkouts are checked. With `--private`, a dirty public checkout would go undetected.
  - The plan does not say what the relative defaults (`../seerah-content`, `map-data/dist`) are resolved against.
- **Fix:**
  - `check_clean` runs on the public repository root and on `git -C <data> rev-parse --show-toplevel`. These give `SEERAH_TOOL_COMMIT` and `SEERAH_DATA_COMMIT`.
  - Relative defaults resolve against the public repository root.
  - Add a test where only the public checkout is dirty.

### IR-16. Major. Task 16, Step 4 (line 692; trigger at line 673). See also CD-14 and SY9-5

- **Problem:** the task's exit gate is "green on that pull request", which needs a pull request. AGENTS.md says an agent opens one only when the owner asks. `map.yml` runs only on pushes to `main` and on pull requests, so pushing the branch alone runs nothing.
- **Fix:**
  - Add `workflow_dispatch:` to the triggers, as `android.yml` has.
  - Make the agent's own gate local: MAPTEST, the host tests and pre-commit all pass.
  - List the owner's actions: approve the push, then open the pull request or dispatch the workflow, which must come back green.
  - State that the agent stops and reports at that point.

### IR-17. Major. Files lists (lines 126, 173, 208, 342, 406, 511, 594-595). See also CD-9

- **Problem:** the standard asks for an exact list of files per task, one that can serve as a write allowlist. Stage 8's ZI-14 fixed only the file tree. These gaps remain:
  - `docs/map-trial/step-1.md` is created in Task 2 (or Task 5, after ZI-15) and changed in Task 11, but neither task lists it.
  - The Task 7 fixture generator is not listed.
  - The files in `tests/fixtures/fetch/` are not named.
  - `tests/conftest.py` is modified in Task 14 but never created.
  - Task 15 never adds `--print-tag` to `run.py`.
- **Fix:** add each path, marked Create or Modify, to the task that touches it. Create `conftest.py` in Task 9, the first task that needs a shared fixture (its local HTTP server).

### IR-18. Nit. Task 3 (lines 227, 236)

- **Problem:** `border_px` leaves out "and half the largest width_m" from spec line 240.
- **Fix:** include `max(width_m / 2)` over the carve edits in the border, and add a carve case to the test.

### IR-19. Nit. Tasks 7 and 10 (lines 349, 364, 476)

- **Problem:** the plan does not state the distance measure for `edge_fade`, or the darkness value at invalid pixels.
- **Fix:**
  - Distance to the edge is `min(lon - west, east - lon, lat - south, north - lat)` at each pixel centre, in plain degrees.
  - Darkness is 0 wherever `valid` is false.

### IR-20. Nit. Task 10 (line 477)

- **Problem:** the tiler must recognise band 4 as alpha. Otherwise it adds its own alpha band and the averaging changes (reasoned).
- **Fix:** write painted blocks with `PHOTOMETRIC=RGB` and `ALPHA=UNASSOCIATED`, and assert band 4's colour interpretation in `test_render_block_writes_atomically`.

### IR-21. Nit. Task 6 (lines 329-332)

- **Problem:** two behaviours are open:
  - the order in which overlapping edits apply;
  - the axis order when GeoJSON is reprojected with OSR in GDAL 3, which can swap longitude and latitude.
- **Fix:** state that edits apply in file order. Set `OAMS_TRADITIONAL_GIS_ORDER` on the EPSG:4326 spatial reference. State that `carve` subtracts amount × weight.

### IR-22. Nit. Task 13 (lines 570, 572, 575-578). See also CD-10 and SY9-4

- **Problem:** five behaviours are undefined, and the tests do not match the return type.
  - A memory cap below `per_worker_mib` gives 0 workers, and `ProcessPoolExecutor(0)` raises an error.
  - The plan does not say what happens when the output folder already exists.
  - `PackError` and unexpected exceptions have no stated exit code.
  - A missing `edits.yaml` has no stated handling.
  - With ZI-7 option (a), `mode` has no stated value in the fingerprint.
  - The tests compare a function that returns a tuple with a bare `3`.
- **Fix:**
  - If the count is below 1, exit 2 with a message that names both values.
  - Replace an existing output folder through `atomic_write`.
  - `PackError` exits 1. Any other exception propagates (exit 1) and keeps the work area.
  - Assert on `worker_count(...)[0]`.
  - A missing `edits.yaml` is a `ConfigError` (exit 2).
  - With ZI-7 option (a), keep `mode` in the fingerprint as the constant `average-down`.

### IR-23. Nit. Task 15 (lines 637, 652)

- **Problem:** the plan does not say how `run.py --workers` and `--per-worker-mib` reach the container. `fetch` would reject them.
- **Fix:** add them to the container command only for `build`, and test this.

### IR-24. Nit. Task 14 (lines 606, 617). See also CD-11

- **Problem:** two smaller gaps.
  - The agent is told to commit the example edit as `review: approved`, but AGENTS.md reserves `approved` for the owner. The spec allows it (spec 413, 791), but the plan does not say so, so a careful agent will stop.
  - `test_absent_neighbour_is_sea` can fail near 57° E, because the mask blur spreads across the cell edge.
- **Fix:** cite spec lines 413 and 791 beside the edit. Check alpha 0 only on absent-cell pixels more than `border_px` from 57° E. Define `peninsula.geojson` as a synthetic rectangle.

### IR-25. Nit. Task 16 (lines 683, 686). See also CD-16

- **Problem:** three gaps.
  - "The existing schedule and grouping style" is ambiguous, because the existing Dependabot entries differ.
  - Dependabot will bump the numpy pin from ZI-4 and break the match with the base image.
  - CI never sets `SEERAH_IMAGE_DIGEST`.
- **Fix:**
  - Spell out the new entries: monthly, 7-day cooldown, prefix `build`, no groups.
  - Add `ignore: numpy` to the pip entry, with a comment that numpy follows the base image.
  - Set `SEERAH_IMAGE_DIGEST` from `docker image inspect`.

### IR-26. Nit. Task 1 (lines 155-157, 163)

- **Problem:**
  - The plan drops spec file 1's rule that a comment gives each pin's source.
  - It does not say where the go-pmtiles SHA-256 comes from.
  - The `slow` marker's description does not match how it is used.
- **Fix:**
  - Take the SHA-256 from the release's published checksums file, and say so in a comment.
  - Redefine `slow` as "needs the network, or real GDAL output that takes more than a few seconds".
  - State that slow tests are never skipped.

### On stage 8's triage (implementation-readiness lens)

- **K items:** this lens agrees with all of them. None is in the current text yet, and all must land before anyone executes the plan.
- **ZI-6:** this lens disagrees with the choice of expected tile set (IR-7).
- **ZI-7:** if option (a) is chosen, keep `mode` as a constant rather than removing it (IR-22).
- **ZI-9:** the schema must accept `band_blur_px: 0` (IR-3).
- **DE-5:** cutting the network-isolation requirement for CI builds is acceptable.
- **The approved spec:** no decision in it needs reopening.

## Codebase details (`egm-codebase-details`)

### CD-1. Nit. Task 1 (lines 137-143)

- **Problem:** the test snippet breaks the repository's ruff settings.
  - It has several imports on one line (E401) and an unsorted import block (I001). `pyproject.toml:6` selects both rules.
  - The pre-commit hook `ruff-check --fix` (`.pre-commit-config.yaml:23-24`) would rewrite the file and stop the commit.
  - The `os` and `shutil` imports are also missing (already raised as ZI-18).
- **Fix:** put the imports at the top of the module, one per line and sorted. Add `# noqa: F401` only to the imports that are probed but not used.

### CD-2. Nit. Task 1 (line 163)

- **Problem:** spec file 3 (`map-trial-build.md:669`) says `tools/map/pyproject.toml` holds the pytest configuration *and* the package metadata. The plan gives it only `[tool.pytest.ini_options]` and puts the version in `__init__.py`. This deviation is not flagged.
- **Fix:** either add a minimal `[project]` table (name, version, `requires-python = ">=3.11"`), or list the omission under the plan's deviations.

### CD-3. Nit. Task 2 (line 204)

- **Problem:** "The lock has no schema" agrees with the approved review (A12, `consolidated_review_report.md:65`). But spec line 695 still says `load_lock` validates against a JSON Schema, so an agent reading the spec could add a third schema.
- **Fix:** cite A12 at line 204, and note that spec line 695's mention of `load_lock` is a leftover.

### CD-4. Nit. Task 3 (lines 229-231, 236, 243). See also SY9-9

- **Problem:**
  - The Earth's circumference is written as 40075016.68557849 in one place and 40075016.69 in another, and 512 appears without a name. AGENTS.md forbids magic numbers and copy-paste.
  - The border of 38 uses the band blur's actual radius of 4 (spec 253). Spec 240's rule (3σ, rounded up) gives 39. The plan's choice is defensible, but it is not flagged.
- **Fix:** define `EARTH_CIRCUMFERENCE_M` and `MAPLIBRE_TILE_PX` once in `grid.py`. State the radius choice and add it to the deviations list.

### CD-5. Major. Tasks 5 and 3 (lines 287-288, 226)

- **Problem:** the plan's `land_mask(wbm, valid)` fixes σ through `grid.MASK_SIGMA_PX`. The spec lists `mask.land_mask(wbm, sigma_px)` (`map-trial-build.md:742`), so this is an interface change the plan does not flag.
  - It removes the parameter that per-zoom mode needs: spec 263 scales the blurs by 2^(zoom − 12).
  - It also puts the mask σ in `grid.py`, which is an odd home for it.
- **Fix:** keep `land_mask(wbm, valid, sigma_px)`, define `MASK_SIGMA_PX` in `mask.py`, and have `border_px` use the σ that is actually applied. If the plan keeps its own signature instead, list it as a deviation tied to ZI-7 / U7.

### CD-6. Major. Task 7 (lines 351-355, 363). See also IR-4 and SY9-13

- **Problem:** the plan says only that the fixture is "written as ENVI", and that is not enough to drive the spike script.
  - `relief_style.py` reads the pixel size from the `map info` line of `<stem>.hdr`, using a regex (`relief_style.py:27-33`). An ENVI file without a geotransform has no `map info`, so the script crashes.
  - The script writes raw, pixel-interleaved RGBA, not a GDAL band (`relief_style.py:61-64`).
  - `pixel_m` is not stored anywhere. A wrong value would trigger a false G8 stop.
- **Fix:**
  - Write the elevation with GDAL's ENVI driver in EPSG:3857, with pixel size `grid.pixel_m()`, as the spike's input was made (`build_base.sh:74`). Name the files `dem.envi` and `dem.hdr`.
  - Read the alpha with `np.fromfile(out, np.uint8).reshape(h, w, 4)[..., 3]`.
  - Store `pixel_m` in the `.npz`, and record it in the fixtures README.

### CD-7. Major. Task 9 (lines 416-420, 435-437). See also IR-5

- **Problem:** only `DEM_BASE` and `cell_name` are given.
  - The DEM path can be worked out from the spike (`build_base.sh:60-65`).
  - The WBM file name is only `AUXFILES/…_WBM.tif` (spec 117).
  - The location and line format of `tileList.txt` are missing.
  - The `ne_base` default is left out. The spike's layout is `.../natural-earth-vector/<ref>/geojson/<file>` (`build_base.sh:17`).
- **Fix:** add `dem_url(cell)`, `wbm_url(cell)`, `TILE_LIST_URL` and `NE_BASE` with the exact path templates. Mark the WBM name and the `tileList.txt` format "to confirm against the bucket at implementation". Make the fixture tree copy these layouts.

### CD-8. Nit. Task 9 (lines 419, 425)

- **Problem:** "every 1° cell that intersects the extent" is ambiguous. If touching edges count, `Bounds(33, 11, 61, 33)` gives 23 × 29 = 667 cells, not the spec's 616 (22 × 28).
- **Fix:** define the cells as half-open ranges: latitudes in `range(floor(south), ceil(north))` and longitudes in `range(floor(west), ceil(east))`. For U1's window this gives E056 and E057, as U1 intends.

### CD-9. Nit. Tasks 10-12 (Files lists at lines 446-449, 486-487, 519-521). See also IR-17

- **Problem:** the Files lists leave out test inputs that the tests name:
  - the tiny synthetic GeoTIFF (line 466);
  - the 512×512 RGBA VRT (line 503);
  - the fake cgroup directory (lines 539-540);
  - Task 12's tile folder.
- **Fix:** for each one, say that the test generates it in `tmp_path`, or add the committed path to the task's Files list.

### CD-10. Nit. Task 13 (lines 563, 575-579). See also IR-22 and SY9-4

- **Problem:**
  - `test_build_pipeline_order` monkeypatches the stages and asserts the order of calls. `docs/engineering.md:58` says to use fakes, not mocking libraries, and to test behaviour, not calls.
  - `worker_count` returns a tuple, but the tests expect bare ints.
- **Fix:** use fake stages that write marker files, then assert the outputs: `relief.pmtiles` and `report.json` exist, and the work area is removed on success and kept on failure. Write the expected values as `(4, <reason>)` and `(3, <reason>)`.

### CD-11. Nit. Task 14 (line 606). See also IR-24

- **Problem:** `review: approved` conflicts with `AGENTS.md:94` unless the plan cites spec 413 and 791.
- **Fix:** cite spec 413 at line 606, and state that the example is synthetic CI data, not content.

### CD-12. Nit. Tasks 14 and 9 (line 607). This adds to ZI-15

- **Problem:** ZI-15's copy message names `seerah-content/map/trial/`, but the example's lock belongs in `map-data/example/`.
- **Fix:** build the message from the profile's folder, and test it for both the example profile and a trial profile.

### CD-13. Nit. Task 15 (line 650). See also IR-15

- **Problem:** it is unclear whether the public checkout is still checked. Spec 179 says both checkouts are.
- **Fix:** `check_clean` runs on both checkouts, or once if they are the same repository. Add a test where only the public checkout is dirty.

### CD-14. Major. Task 16 (lines 673, 692). See also IR-16

- **Problem:** `map.yml` triggers only on a push to `main` or on a pull request.
  - Step 4 needs a green run on a pull request, but `AGENTS.md:111` forbids opening one unless the owner asks.
  - `android.yml:3-5` handles the same problem with `claude/**` pushes and `workflow_dispatch`.
  - `workflow_dispatch` may not work before `map.yml` is on the default branch (reasoned).
- **Fix:** make "the owner opens the pull request" an explicit owner action in Step 4 and in the decisions table. Alternatively, add the working-branch pattern to `push.branches`, and push images only from `main`.

### CD-15. Nit. Task 16 (lines 675-684)

- **Problem:** existing files already settle several `map.yml` details, but the plan leaves them open.
- **Fix:**
  - Reuse `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1` (`android.yml:24`, `ci.yml:17`) and `actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0` (`ci.yml:26`).
  - Give job `image` both `contents: read` and `packages: write`.
  - Log in with a shell `docker login ghcr.io --password-stdin` and `GITHUB_TOKEN` from `env:`, which adds no new action dependency.
  - Use `${{ runner.temp }}` in the cache path (reasoned).
  - State that `map` is not a required check (`android.yml:7-8` warns that a path-filtered required check blocks pull requests).

### CD-16. Nit. Tasks 16 and 1 (lines 686, 157). See also IR-25

- **Problem:** the existing `dependabot.yml` entries are not uniform:
  - two are monthly (lines 7 and 14) and one is weekly (line 20);
  - only actions and gradle have groups;
  - every entry has a 7-day cooldown;
  - the pip entry uses the commit prefix `build`.

  The go-pmtiles `ARG` will not be tracked by Dependabot, and the plan does not say so.
- **Fix:** give the exact `docker` and `pip` entries for `/tools/map`: monthly, `cooldown: { default-days: 7 }`, prefix `build`, no group. Add a Dockerfile comment saying that go-pmtiles is updated by hand.

### CD-17. Nit. Task 16 (line 692)

- **Problem:** the list of pre-commit hooks in Step 4 is incomplete.
  - It leaves out `actionlint` (`.pre-commit-config.yaml:37-40`), which checks `map.yml` and its shell steps.
  - It leaves out `check-yaml` and `check-json` (lines 9-10).
  - The plan never runs `python3 tools/validate_content.py`, which `AGENTS.md:41` requires.
- **Fix:** say "all hooks in `.pre-commit-config.yaml`", and add `python3 tools/validate_content.py`.

### CD-18. Nit. Tasks 2, 14 and 16 (documentation duties under `AGENTS.md:43-49` and `docs/engineering.md:74, 78`)

- **Problem:**
  - The new `docs/map-trial/` folder gets no entry in `docs/README.md`.
  - `map-data/README.md:3-12` will be out of date once Task 14 adds `example/`.
  - `tools/README.md:11` already requires a table row, so Task 16's row is required by an existing file, not only pulled forward from the spec.
  - The module README contents list leaves out how to run the tests locally, which `engineering.md:74` requires.
- **Fix:**
  - Add the `docs/README.md` row in Task 2.
  - Update `map-data/README.md` in Task 14.
  - Cite `tools/README.md:11` at line 690.
  - Add "how to run the tests locally (MAPTEST and host unittest)" to the README contents.

### On earlier reports (codebase-details lens)

- **ZI-3:** the hard-coded `ghcr.io/mohdfarag/seerah-map` matches the remote (`.git/config:7`) and `content.schema.json:3`. Deriving it from `github.repository_owner` is still better, but the hard-coded name is not wrong.
- **ZI-13:** the `tools/README.md` row is required by `tools/README.md:11`, so it is not purely a pull-forward (CD-18).
- **ZI-15:** the copy message must depend on the profile (CD-12).
- **Reuse:** stage 8 said "no existing code to reuse", which is right for code. The existing pins and conventions in `ci.yml`, `android.yml` and `dependabot.yml` should still be reused explicitly (CD-15, CD-16).
- **SY-8:** stage 8 cut SY-8 on reasoning about how ruff finds its configuration, not on a test. If CD-2's `[project]` table is added, ruff may infer the target version from it (reasoned). That is harmless at Python 3.11.
- **Earlier checks confirmed against the files:**
  - ZI-9: the band-blur rim is 3,136 of 40,000 pixels (7.84%), from `relief_style.py:41`.
  - ZI-18: `$defs` has `source` and `review` but no `sources`.
  - IC-24: the spike used `pmtiles-convert` on an MBTiles file (`build_base.sh:98`).
  - ZI-8: the zoom-9 boundary at 56.953° E cuts the window.
  - ZI-3: the `concurrency` and `timeout-minutes` conventions exist in both workflows.

## Style (`egm-style`)

The plan has not changed since the earlier reviews, so every earlier style finding in this range (for example CO-1, CO-9, CO-16, IC-15, ST-9 and SY-1) is still present in the text. They are not repeated here.

### SY9-1. Major. All tasks (lines 300, 334, 370, 398, 440, 480, 509, 549, 586, 655; also 152 and 198)

- **Problem:** most run steps say only "Run." Tasks 7 and 12 each have two test files, and Task 15 runs on the host with `unittest`, not with MAPTEST. The expected-failure wording is inconsistent, and only line 198 names the expected error.
- **Fix:** give the full command and the expected count in every step, for example "Run `MAPTEST tests/test_style.py tests/test_style_regression.py`. Expected: 7 passed." Write failing runs as "Expected: FAIL (`ModuleNotFoundError: seerah_map.<module>`)".

### SY9-2. Major. Task 11 (lines 494, 512, against 501). See also IR-9

- **Problem:** "`run_tile_stage` may pass `resume=True`" is a hedge where a rule is needed. It contradicts `test_folder_without_done_is_retiled`, and the `resume` parameter of `tile_args` is never defined.
- **Fix:** replace the hedge with a definite instruction: either IR-9's two-outcome branch, or "Record the result only; do not change `run_tile_stage` in phase 1; tell the owner". Define the `resume` parameter, or remove it.

### SY9-3. Major. Task 11 (line 495)

- **Problem:** the plan does not say when `tiles.done` is written. If it is written before the completeness check, a resumed build could skip an incomplete tile folder.
- **Fix:** "It writes `tiles.done` only after `check_completeness` returns an empty list. Otherwise it raises `CompletenessError` and writes no marker." Add this case to `test_completeness_reports_missing_and_broken`.

### SY9-4. Major. Task 13 (line 570 against lines 575-576). See also IR-22 and CD-10

- **Problem:** `worker_count` is declared to return `tuple[int, str]`, but the tests say it "gives 4" and "gives 3". An agent cannot tell whether to assert on an int or on a tuple.
- **Fix:** write each expected value as a full tuple, with the reason string spelled out.

### SY9-5. Major. Owner steps (lines 602, 657, 692). See also IR-1 and IR-16

- **Problem:** owner steps do not say who acts.
  - Line 602, "Confirm the window", reads as addressed to the agent.
  - Line 657, "Smoke test on the owner's machine", does not say who runs it, and its command leaves out the `--image seerah-map:dev` flag that the next sentence requires.
  - Line 692 assumes a pull request that no step opens.
- **Fix:**
  - Line 602: "Ask the owner to confirm U1's window or give another one. Stop until the owner answers."
  - Line 657: "The owner runs, on their machine: `python3 tools/map/run.py --image seerah-map:dev ...`", followed by the expected output.
  - Line 692: "Ask the owner for approval to push and open a pull request. After the owner opens it, check that the `map` workflow passes on it."

### SY9-6. Major. Task 14 (line 619). See also IR-14

- **Problem:** "FAIL until all the earlier tasks are wired" has no meaning here. Tasks 1-13 are already done, and Step 2 writes the example files before the tests run. The agent cannot tell an expected failure from a defect.
- **Fix:** "Expected: 7 passed. A failure is a defect in the module the test exercises. Fix it in this task, with a test in that module's test file, then run this step again."

### SY9-7. Major. Task 14 (lines 605-606)

- **Problem:**
  - "one `raise` of 20 m and 600 m wide" mixes up the edit fields. `width_m` belongs to `carve`, and `raise` applies to a polygon.
  - "the default style" does not say whether to leave `style` out or write the defaults in.
- **Fix:** "One edit: `operation: raise`, `amount_m: 20`, `feather_m: 300`, `review: approved`, with a polygon 600 m across that crosses a zoom-11 block edge. Leave out `style`, so the defaults apply."

### SY9-8. Major. Task 15 (lines 632-633, 637, 650). See also IR-15

- **Problem:**
  - `docker_args` has `public` and `private` parameters but no `data` parameter, although the `--data` flag exists. `public` is never defined.
  - "the two files' bytes concatenated" does not give the order, and CI must reproduce the result byte for byte.
  - It is unclear whether the clean-checkout check covers the tool checkout.
- **Fix:** define `public` as the tool checkout (mounted at `/src`), and add a `data: Path` parameter (mounted at `/data`). State the hash input as "the bytes of `Dockerfile` followed by the bytes of `requirements.txt`". State that `check_clean` runs on both checkouts.

### SY9-9. Major. Task 3 (lines 230, 243). See also CD-4

- **Problem:** the two formulas use two values for the same constant and mix notations. `cos(lat)` takes radians but is given degrees, the formula says `zoom` where the parameter is `maplibre_zoom`, and it writes `2^zoom` where line 230 writes `2**12`.
- **Fix:** define one constant and write both formulas as code:

  ```python
  EARTH_CIRCUMFERENCE_M = 40075016.68557849
  pixel_m = EARTH_CIRCUMFERENCE_M / (TILE_PX * 2**BASE_ZOOM)
  view_reach_km = (
      half_width_dp
      * EARTH_CIRCUMFERENCE_M
      * math.cos(math.radians(lat_deg))
      / (512 * 2**maplibre_zoom)
      / 1000
  )
  ```

### SY9-10. Major. Several terms carry more than one meaning

- **Problem:** five words each name several different things.
  - "window" means both the `Window` dataclass and the example's area (lines 223, 469, 605, 608, 617).
  - "d" means both a distance (lines 330-331) and darkness (lines 349, 383-391).
  - "edge" means a block edge, an extent edge, and "edge tile" (line 513), which matters most because check 6 depends on it.
  - "extent" is used for several different parameters (lines 225, 230, 419, 461, 538).
  - "block" and "step" are used for several things.
- **Fix:**
  - Call the area "the example area".
  - Rename the distance to `dist_m`.
  - Line 513: "decode one zoom-11 tile that contains alpha values strictly between 0 and 153".
  - Name the extent parameters `data_extent`.
  - Write "inside the `with` statement", "a 2×2 pixel group", and "**Order:**" at line 476.

### SY9-11. Major. Interface signatures (lines 179, 182, 186, 259-262, 332, 420, 453, 461, 463, 528-533, 571-572, 635)

- **Problem:** the signatures are inconsistent and incomplete.
  - `Bounds(west, south, east, north: float)` reads in Python as if only `north` were typed.
  - `ne_base: str = ...` has no default value.
  - Many parameters have no types.
  - Names drift: `WorkArea(root, fingerprint)` against `open_area(work_root, fp)`, and `amount` against `amount_m`.
  - Some names are module-qualified and others are not.
  - Task 13 lists inputs under "Produces".
- **Fix:** type every parameter, and use one module-qualified name per item. Name the Natural Earth constant. Keep the `_m` and `_deg` suffixes in prose. Move Task 13's environment inputs and output folder to "Consumes".

### SY9-12. Major. Test oracles and preconditions (lines 426, 427, 500, 503, 641, 643, 644)

- **Problem:** several expected results are vague or circular.
  - "as many transparent tiles as expected" (line 503) defines the result by itself.
  - "`verified` equals the file count" (line 427) does not say which files.
  - "known bytes give a known tag" (line 644) gives no values.
  - Line 643 has no input or verb.
  - Lines 500 and 641 do not say whether the flags are separate list elements or substrings.
  - "no committed lock" (line 426) is vague.
- **Fix:**
  - Count the fully transparent tiles from `expected_tiles`.
  - Line 427: `verified == 2 × present cells + len(NE_FILES)`.
  - Line 644: write out two fixed byte strings and their tags.
  - Line 643: "with `memory_mib=8192`, the list contains `--memory`, `8192m`, `--memory-swap`, `8192m`".
  - Lines 500 and 641: list each element separately, for example `"-f", "WEBP"`.
  - Line 426: `lock_path=None`.

### SY9-13. Major. Task 7 (lines 352-354, 363). See also IR-4 and CD-6

- **Problem:** line 363 is a single sentence of about 60 words with six conditions, and it is the G8 stop gate. It relies on `pixel_m`, which Step 1 never provides.
- **Fix:** have Step 1 store `pixel_m` in `spike_relief.npz`. Split line 363 into sub-bullets: the inputs, the conversion (`trunc(d * 0.6 * 255)`), and the pass rule.

### SY9-14. Nit. Vague verbs and unnamed outputs (lines 156, 417, 525, 577, 584, 634, 653, 657)

- **Problem:** several steps name an output without giving it.
  - "each print the next step" gives no messages.
  - "with the command to commit or stash" gives no text.
  - The plan does not say which stream progress lines go to, although Task 14's conftest reads them.
  - "reports the lock as verified" gives no line.
  - `apt-get install ...` has no verb.
  - "resolved at implementation" does not say how.
- **Fix:**
  - Write out each message.
  - "Progress lines go to stderr, one per line, flushed."
  - "Look up the commit of tag v5.1.2 with `git ls-remote` and write it as a literal."

### SY9-15. Nit. Abbreviations (lines 184, 208, 288, 460-461, 466, 474-475)

- **Problem:** "WBM" is never tied to "Water Body Mask" (line 208), and DEM, VRT, OGR, ENVI and `NE_` are never expanded.
- **Fix:** expand each abbreviation the first time it is used. Rename `NE_FILES` to `NATURAL_EARTH_FILES`, to match `NATURAL_EARTH_COMMIT`.

### SY9-16. Nit. Notation (lines 70, 208, 426, 507, 511, 512, 584, 614, 637, 673, 681-684)

- **Problem:** notation varies for the same thing.
  - The progress-line pattern differs between line 584 and line 614.
  - The usage line has `[--memory-mib 8192]` where the rest of the plan uses `N`.
  - Check names appear as both "step-1 check" and "Step 1 checks".
  - The plan says both "the tiler" and `gdal raster tile`.
  - Paths are written as both `out/` and `/out`, and as both `android.yml` and the full path.
  - Task 16 sets `SEERAH_TOOL_COMMIT` after the steps that need it.
  - It is unclear whether the path filter applies to both triggers.
- **Fix:** pick one form for each and use it everywhere. Set `SEERAH_TOOL_COMMIT=${{ github.sha }}` in the job's `env:`. State that both triggers filter on the paths.

## Claims verified

The codebase-details reviewer checked these against repository files. The implementation-readiness reviewer separately confirmed the `.gitignore` and `REUSE.toml` claims.

1. **Ruff settings.** Python 3.11, line length 100, rules E, F, W, I, UP, B and SIM: `pyproject.toml:2-3, 6`.
2. **Pre-commit hooks.** ruff (`.pre-commit-config.yaml:20-25`), markdownlint (26-30), REUSE (32-35) and zizmor `--offline` (41-45). Also present but not named by the plan: actionlint (37-40), check-yaml and check-json (9-10).
3. **Commit types.** `build`, `feat`, `test` and `ci` are allowed: `.pre-commit-config.yaml:51`, `AGENTS.md:110`.
4. **Fixture size.** A fixture under 1 MB passes `check-added-large-files --maxkb=1024`: `.pre-commit-config.yaml:12-13`.
5. **Workflow conventions.** Actions are pinned by commit, `persist-credentials: false` is set, `permissions: {}` is set at the top level, and `contents: read` is set per job: `android.yml:10, 18-19, 24-43` and `ci.yml:6, 14-19`.
6. **Dependabot.** `dependabot.yml` has no docker entry and no `/tools/map` entry, so "Modify" is correct. Every entry uses a 7-day cooldown: `dependabot.yml:1-25`.
7. **Content schema.** `content.schema.json` is draft 2020-12 (line 2), with `$defs.review` (7-10) and `$defs.source` (42-52).
8. **Schema validator.** `Draft202012Validator` is the repository's existing validator (`tools/validate_content.py:39`). jsonschema is pinned at 4.26.0 (`requirements-dev.txt:2`, `.pre-commit-config.yaml:58`).
9. **Tool folders.** Tools run from the repository root (`tools/README.md:3`), and a new tool gets its own folder and a table row (`tools/README.md:11`).
10. **Spike style values in `relief_style.py`.**
    - Blur σ 7 (line 18), exaggeration 8 (19), azimuth 315 and altitude 45 (20).
    - 3 bands (21), ink 150, 118, 72 (22), maximum alpha 0.6 (23).
    - Contrast 1.6 (58), band blur 1.5 with radius 4 (37, 59).
    - Land where elevation > 0.5 (49), and truncated alpha (63).
    - The value 7.36 and the mask σ of 4 with threshold 128 come from the spec (259, 241).
11. **Pixel size.** `pixel_m = 40075016.68557849 / 256 / 2**12`: `build_base.sh:72`.
12. **Natural Earth files.** The six files are at `build_base.sh:17-18`. Pinning them to a fixed ref follows A10 (`consolidated_review_report.md:63`).
13. **Copernicus names.** The `Copernicus_DSM_COG_10_N..._00_E..._00_DEM` cell name and the DEM URL pattern: `build_base.sh:59-65`.
14. **WebP quality.** `webp_quality: 80` matches the spike: `build_base.sh:96`.
15. **Ignored outputs.** `.gitignore` ignores `map-data/dist` (line 26), `*.pmtiles` (24), `__pycache__` (21) and `.pytest_cache` (37).
16. **Licences.** New files under `tools/map` and `map-data/example` fall under the Apache-2.0 default in `REUSE.toml:6-10`. This matches spec 791.
17. **GHCR namespace.** `mohdfarag` matches the remote: `.git/config:7`.
18. **New paths.** `tools/map/`, `docs/map-trial/` and `.dockerignore` do not exist yet, so "Create" is correct.
19. **Numbers checked against the spec.**
    - Data extent `Bounds(33, 11, 61, 33)` and 616 cells (spec 119, 133-134).
    - 2,048-pixel blocks and about 1,400 blocks (computed: 41 × 35 = 1,435).
    - View reach (computed: 623.7 km at zoom 6 and 78.0 km at zoom 9, both within 5% of spec 313-318).
    - `worker_count`: 8 GiB at 2,048 MiB per worker gives 4.
    - Painted alpha: trunc(0.6 × 255) = 153.
    - Terrarium offset: 32768.
20. **Interfaces checked against the spec.**
    - Exit codes (spec 687), output folder (155, 196) and `run.py` defaults (678).
    - Mounts, label, network and memory flags (182-185), and the tag rule (173).
    - Fingerprint inputs (229), metadata fields (293), report categories (331-343) and tiler settings (276-281).
    - `tiles.done` (283), edit operations (222) and `trial-0` (218, 703).
    - `requirements.in` contents (664), base image tag (120-121), and `map.yml` paths and jobs (796-803).
21. **Choices from the approved review.**
    - `lower` removed, no test hook for killing processes, no lock schema: A12, `consolidated_review_report.md:65`.
    - No resumable downloader: `consolidated_review_report.md:33`, spec 195.
    - Thread variables set before numpy and GDAL load (`consolidated_review_report.md:57`); U5's Dockerfile `ENV` satisfies this.
    - Setting `GDAL_CACHEMAX` at all: `concepts_brief.md:89`. The value 256 is new.
22. **Self-review mapping.** The plan's mapping of spec files to tasks matches spec 649-803.
23. **Fetch method.** Fetch downloads one file at a time with standard-library `urllib`: spec 724.
24. **YAML rules.** YAML 1.2 core schema with duplicate keys rejected: ADR 0007:26, spec 694.
25. **Spike code.** None is copied into v1. Running `relief_style.py` to build a fixture is the learning from the spike that `AGENTS.md:98` allows.

## Claims not verifiable

| # | Claim | Why it could not be checked |
| --- | --- | --- |
| 1 | The digest of `ghcr.io/osgeo/gdal:ubuntu-small-3.13.3`, and what the image contains (pip, numpy version and origin, Python version, git absent) | Needs a registry lookup or `docker run` |
| 2 | go-pmtiles v1.31.2: the asset name, the SHA-256, the `pmtiles show --header-json` flag and its field names | Needs URLs |
| 3 | The `gdal raster tile` flags (`--convention`, `--tiling-scheme`, `--min-zoom`, `--max-zoom`, `-f`, `--co QUALITY`), whether `-j` uses processes, and whether a web-viewer option exists | Needs GDAL's documentation or a run |
| 4 | `python3 -m pmtiles.convert` as a working entry point, and the signature `disk_to_pmtiles(dir, out, "auto")` | Needs the package source |
| 5 | ruamel.yaml: `version=(1, 2)`, `allow_duplicate_keys`, and how to remove the timestamp resolver | Needs the library |
| 6 | `morecantile.tms.get("WebMercatorQuad")`, `gdal.__version__`, `gdal.Warp`'s `errorThreshold`, `GMF_PER_DATASET`, the OGR `Memory` driver name in 3.13, and scipy's `radius` argument | Needs the installed versions |
| 7 | The Natural Earth tag v5.1.2 and the contents of its `geojson/` folder | Needs GitHub |
| 8 | The exact WBM file name; the location and line format of `tileList.txt`; whether cell N17 E056 exists and E057 is absent; whether U1's window holds land | Needs the bucket |
| 9 | Anonymous pulls from a public GHCR package, `GITHUB_TOKEN` push rights to `ghcr.io/mohdfarag`, and the memory available to a hosted runner for `--memory 6g` | Needs GitHub |
| 10 | Whether Dependabot regenerates a hash-locked pip-compile file correctly for an image-specific numpy, and whether it updates digest-pinned `FROM` lines | Needs Dependabot's documentation |
| 11 | Whether `actions/cache` expands environment variables in `path`, and whether `workflow_dispatch` needs the workflow on the default branch | Needs GitHub's documentation |
| 12 | When `memory.peak` is missing (cgroup v1, kernels older than 5.19, the root cgroup), and so whether "cgroup v1 or no cap" is the right reason text | Needs a kernel check |
| 13 | Whether ruff ignores a nested `pyproject.toml` that has no `[tool.ruff]` table (the basis for stage 8 cutting SY-8) | Needs ruff's documentation or a run |
| 14 | How lossy WebP alters RGB at alpha edges (IR-10), whether the tiler writes viewer files (IR-8), and how it treats an unflagged alpha band (IR-20) | Reasoned, not tested |
| 15 | The owner's portability and "name unresolved decisions" request of 2026-11-14 | Supplied by the caller; it is in no repository file |
