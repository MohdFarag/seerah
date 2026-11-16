# Stage 6: Comprehension test, map trial phase 1 plan

**Target:** `docs/design/map-trial-phase-1-plan.md`, lines 1–725 (FeatureRange 1–121, DetailedRange 122–725).

**Reviewer:** egm-comprehension. It read only the plan, and no document standard applied. Status: completed.

**Result:** 0 🔴, 12 🟡, 9 🟢. Every section could be paraphrased. CO-5 (per-zoom mode) comes closest to a blocker, and CO-1 affects every task's run step.

## What this proposes

The plan is to build a Python tool, `seerah_map`, that runs inside a Docker image containing only the toolchain (GDAL, go-pmtiles, numpy and similar). The tool's code is mounted read-only from the checkout. The tool has three commands:

- **fetch** downloads Copernicus 30 m elevation cells, the matching Water Body Mask cells and Natural Earth files. It records their sizes and SHA-256 hashes in a lock file.
- **build** splits the area into fixed blocks on a zoom-12 Web Mercator grid. For each block it makes a soft land mask, applies approved terrain edits, computes a shaded-relief "darkness", fades the edges, and encodes the result as either a "painted" RGBA image or a "value" image. It then tiles the blocks with `gdal raster tile`, packs the tiles into a deterministic PMTiles file with `metadata.json`, and writes a run report with memory figures.
- **clean** removes work areas.

A host script, `run.py`, starts the container with the right mounts, memory limits and network rules. A small public example on the Oman coast proves the whole pipeline end to end in GitHub Actions, including determinism and resuming after a kill. There are 16 test-first tasks, each with one commit, and five owner decisions are still open (U1–U5).

## How the existing system works, per this document

An approved specification (`map-trial-build.md`) defines the trial, its numbered "files", its phases and its "step 1 checks". An earlier spike (`tools/spike/relief_style.py`, "build 11") set the visual style ("style C") and its constants. AGENTS.md forbids copying spike code, so the spike is used only to produce a regression fixture.

The repository already has:

- ruff settings in `pyproject.toml`;
- pre-commit with ruff, markdownlint, zizmor and REUSE;
- an `android.yml` workflow whose conventions are copied;
- Dependabot;
- `tools/README.md`;
- a content schema whose `review` and `sources` definitions are reused.

Private map data lives in a sibling repository, `../seerah-content`. A later phase 2 consumes this phase's example output, and phase 3 adds a base layer, tippecanoe and WhiteboxTools.

The reviewer was not confident this second paragraph is accurate, because most of it comes from names the plan mentions without explaining.

## Findings

### 🟡 Major

**CO-1. The shared test command reads two ways and fails as written.**

- **Lines:** 50–56, and every task's "Run" step.
- **Problem:** "MAPTEST" ends in `python3 -m pytest tools/map <args>`, and the tasks pass paths like `tests/test_image.py`. Taken literally, that runs `pytest tools/map tests/test_image.py` from `/src`:
  - `/src/tests/test_image.py` does not exist;
  - `tools/map` would also run the whole suite.

  A reader cannot tell whether `<args>` replaces `tools/map` or is added after it, or whether the paths are relative to `tools/map`. Each "Expected: FAIL" step would then fail for the wrong reason.
- **Fix:** define MAPTEST as `... python3 -m pytest <args>` with `-w /src/tools/map`, so that `tests/...` paths resolve. Alternatively, write every path as `tools/map/tests/...`. Say which rule applies.

**CO-2. Spike vocabulary is never explained.**

- **Lines:** 5, 40, 47, 370, 372, 537.
- **Problem:** "Style C", "the spike", "build 11", "G8" and "G9" are never defined. A reader cannot tell:
  - what style C looks like, or what other styles exist;
  - that G8 and G9 are probably goal IDs in the specification;
  - what "the look differs from build 11 (G8)" asks them to do, beyond stopping.
- **Fix:**
  - Add a one-line glossary entry per term, for example: "Style C: the banded oblique-light relief chosen in spike build 11."
  - Expand G8 and G9 inline, for example: "(spec goal G8: the trial must look like build 11)".

**CO-3. The exaggeration value seems to contradict itself.**

- **Lines:** 45 against 363.
- **Problem:** Global Constraints says the spike's values are used "verbatim", with exaggeration 7.36. The regression test against the spike uses `exaggeration=8` at latitude 0. A reader will think one of them is a typo. 7.36 is probably 8 × cos 23°, a latitude-corrected form of the spike's 8, but the plan never says so.
- **Fix:** state where 7.36 comes from, and why the regression test uses 8 at latitude 0.

**CO-4. The two variants are never explained.**

- **Lines:** 383–392, 466, 536, 566.
- **Problem:**
  - The plan never says what "painted" and "value" are for.
  - "Value" stores darkness, not elevation, in a Terrarium-style encoding, but the reader is not told who consumes it, or why it is not banded (`banded=(variant == "painted")`).
  - The plan never says whether one build makes both variants or each needs its own build.
- **Fix:** add two sentences to the Architecture section. Say what each variant is for, which consumer reads it, and why only the painted variant is banded.

**CO-5. The two build modes are never explained.**

- **Lines:** 566, 580, and Tasks 10–11.
- **Problem:** the modes `average-down` and `per-zoom` are never defined, and `--mode` has no stated default. Tasks 10 and 11 render only at zoom 12 and then average down, so a reader cannot work out how a per-zoom build makes zoom 10 with a 1.75 px blur. Does it run the block stage once per zoom, and how does the tile stage combine the results? The only clue is one CLI test, so the reviewer could not paraphrase how per-zoom builds work.
- **Fix:** add a short description of both modes and the default to Task 13 or Global Constraints. Say which task implements per-zoom rendering, or state that phase 1 only threads the scaled settings through and full per-zoom output comes later.

**CO-6. The steps for getting a new lock into place are missing.**

- **Lines:** 426, 430, 577, 607.
- **Problem:** `fetch` writes a new lock only under `/out` (which `run.py` maps to `map-data/dist`) and never into the read-only `/data`. `build` then needs the lock beside the profile ("run fetch first, then commit the lock"), and the plan never says how the lock gets from `out/<name>.lock` to the profile's folder. Task 14 says only "generate example.lock with fetch and commit it".
- **Fix:** add the manual step explicitly, for example "copy `map-data/dist/<name>.lock` to `<data>/<folder>/<name>.lock` and commit it in the data repository". Put it in Task 9's behaviour and in Task 16's README contents.

**CO-7. How the test job gets a pull-request image.**

- **Line:** 681.
- **Problem:** on pull requests, the `test` job uses "the freshly built local image". But `test` is a separate job, and the image is built in the `image` job, possibly on another runner, and not pushed on pull requests. A reader cannot tell how the image reaches `test`: built again in `test`, passed as an artifact, or with both jobs merged.
- **Fix:** state the mechanism. For example: "on pull requests the test job rebuilds the image from the same Dockerfile", or "`image` saves it with `docker save` as an artifact".

**CO-8. Task 16 relies on an option Task 15 never builds.**

- **Lines:** 637, 670, 676.
- **Problem:** Task 16 checks the CI tag against `run.py --print-tag`, but Task 15's usage line, interface and tests never include `--print-tag`.
- **Fix:** add `--print-tag` to Task 15's usage line, and add a test for it.

**CO-9. Two different packing commands are offered.**

- **Lines:** 545, 35.
- **Problem:** the text says "`pack` runs `python3 -m pmtiles.convert`, *or* a small inline call of `disk_to_pmtiles(...)`". That reads as two different options, and the determinism rule (`PYTHONHASHSEED=0`) is phrased for `disk_to_pmtiles` only.
- **Fix:** pick one and name it. If the choice depends on something, say what.

**CO-10. A log line the test needs is never specified.**

- **Lines:** 615 against 584 and 507.
- **Problem:** the tile-stage resume test kills the build after a "`tile stage started`" log line, but no task says that line is emitted. Only the `block <id> done (<n>/<total>)` line is specified.
- **Fix:** in Task 11 or Task 13, list every progress line with its exact text.

**CO-11. The larger trial is not explained.**

- **Lines:** 70–74, 600, 724.
- **Problem:** the plan relies on a structure it does not explain:
  - "the trial's step 1 on devices";
  - "Step 1 checks", numbered 5–7, with 1–4 never explained;
  - phases 2 and 3;
  - "the valley probe".

  A reader cannot tell what "devices" are (Android phones?), what step 1 tests, or what phase 2 needs from Task 14's output.
- **Fix:** add a 3–4 line "Where this fits" paragraph: what the trial is, its steps, what each phase delivers, and what phase 2 takes from this phase.

**CO-12. The Water Body Mask classes and the fetch-by-hand step are unexplained.**

- **Lines:** 208, 291–292, 298.
- **Problem:**
  - Task 2 asks for a Water Body Mask to be "fetched by hand inside the image" before `fetch` exists in Task 9. No URL or file name pattern is given; only the DEM's base URL appears.
  - The meaning of classes 0–3 is never stated. Task 5 treats 1 as ocean and 2 and 3 as lakes and rivers; class 0 is never described.
- **Fix:**
  - Give the Water Body Mask file's URL pattern, or a one-line `curl` command.
  - Add a one-line table: 0 = no water, 1 = ocean, 2 = lake, 3 = river, assuming that is the intent.

### 🟢 Nit

**CO-13. Several items have no stated purpose.**

- **Lines:** 114, 181, 417, 462–463, 608.
- **Problem:** these are created in phase 1, but no task says what they are for or uses them, so a reader may think a step is missing:
  - `peninsula_outline` and `peninsula.geojson`: which peninsula, and what uses it?
  - `camera-path.json` and its "stops";
  - the Natural Earth files;
  - `mask/<id>.tif` and `mask.vrt`, which the tile stage never reads.
- **Fix:** add one clause per item, for example "used by phase 3's base layer" or "read by the app in phase 2, not by this tool".

**CO-14. How the soft mask becomes `land` is not stated.**

- **Lines:** 44, 348, 476.
- **Problem:** `land_mask` returns 0–255, but `darkness` treats `land` as true or false. The threshold "land at 128 and above" appears only among the constants, and Task 10's steps do not say where it is applied.
- **Fix:** in Task 10's steps, write "land = mask >= 128", and say whether `darkness` receives the boolean or the 0–255 value.

**CO-15. Two parameters are ambiguous.**

- **Lines:** 227, 349.
- **Problem:**
  - `border_px(..., lat_deg)`: which latitude a block uses (its centre, or the edge nearer the pole).
  - `edge_fade(distance_to_edge_deg)`: which edge is meant (the data extent, or the camera bounds).
- **Fix:** name the latitude and the edge.

**CO-16. `<name>` can mean two things.**

- **Lines:** 569, 572.
- **Problem:** `<name>.lock` and `/out/<name>-...` could use the profile's file name or the `Profile.name` field.
- **Fix:** say which one, and define `fp8` as the first 8 hexadecimal characters of the fingerprint.

**CO-17. `view_reach_km` is unexplained.**

- **Lines:** 231, 243.
- **Problem:** it uses "dp" and a tile size of 512, while `TILE_PX = 256`, and phase 1 never calls it.
- **Fix:** add one sentence. Say that dp means Android density-independent pixels, that 512 is MapLibre's zoom convention, and that the function exists to check the specification's view-reach table.

**CO-18. A missing provenance value has an unexplained effect.**

- **Lines:** 571, 258.
- **Problem:** a missing `SEERAH_TOOL_COMMIT` or `SEERAH_IMAGE_DIGEST` becomes `"unknown"`, and both values feed the fingerprint. A reader cannot tell whether two different code versions, both "unknown", would share a work area and resume each other's blocks.
- **Fix:** state what happens in that case, or say that the fingerprint uses some other value when the input is unknown.

**CO-19. Two background terms are unexplained.**

- **Lines:** 64, 650.
- **Problem:**
  - U1's "neutral (far from any event or named place)" assumes the reader knows the project's sensitivity about historical places.
  - `--private` and `../seerah-content` assume the reader knows there is a separate private data repository.
- **Fix:** add one clause to each.

**CO-20. Specification file numbers mean nothing without the specification open.**

- **Lines:** 20, 700–723.
- **Problem:** the coverage table and line 20 refer to "files 1–18" and "file 42" by number only.
- **Fix:** add each file's path next to its number in the self-review table.

**CO-21. "Runs use the image by digest" reads two ways.**

- **Line:** 67.
- **Problem:** `run.py` pulls by tag and reads the digest only for provenance, and CI uses the tag.
- **Fix:** say whether a run pins the digest it executes or only records it.
