# Consolidated EGM review: map trial phase 1 plan

**Status:** resolved. The owner accepted D1–D7 as recommended on 2026-11-15, and A1–A16 were applied to the plan. D1 stays an owner gate: the Dhofar window measured 0.1% land and was replaced by a Masirah window measured at 27%.

**Target:** `docs/design/map-trial-phase-1-plan.md` (725 lines; FeatureRange 1–121, DetailedRange 122–725), at commit `65ec40f` on branch `docs/map-trial-phase-1-plan`. The plan has not been edited; the owner decides each change.

**Mode:** consolidated report, all review rubrics. **Dates:** 2026-11-14 and 2026-11-15.

**What the reviewers read:**

- **Linked from the plan:** the approved specification.
- **Added by the owner:**
  - `AGENTS.md`, the concepts brief, and the specification review's consolidated report;
  - the spike's `relief_style.py`, `build_base.sh` and `Stats.kt`;
  - `pyproject.toml`, `.pre-commit-config.yaml` and `dependabot.yml`;
  - the `android` and `ci` workflows;
  - the content schema and `tools/README.md`.
- **Stage 6:** the comprehension reviewer read the plan alone.

## Stages run

| Stage | Reviewers | Report | Result |
| --- | --- | --- | --- |
| 5. Initial zoom out | zoom-out, inventive-catalyst | [zoom_out_report.md](zoom_out_report.md) | 2 🔴, 31 🟡, 26 🟢 (before merging) |
| 6. Comprehension test | comprehension | [comprehension_report.md](comprehension_report.md) | 0 🔴, 12 🟡, 9 🟢 |
| 7. Critic review | design, structural, style | [critic_report.md](critic_report.md) | 0 🔴, 24 🟡, 27 🟢 |
| 8. Intermediate zoom out | zoom-out | [intermediate_zoom_out_report.md](intermediate_zoom_out_report.md) | 131 findings merged into 18 items (1 🔴, 14 🟡, 3 🟢); K 89, S 8, O 7, C 27 |
| 9. Implementation readiness | implementation-readiness, codebase-details, style | [implementation_readiness_report.md](implementation_readiness_report.md) | 0 🔴, 34 🟡, 26 🟢 (new) |

**Notes:**

- **Completion:** every reviewer completed. Stage 9 stopped once at the usage limit and was resumed.
- **A corrected premise:** stage 7's DE-1 assumed portability was not the owner's goal. The owner asked for it on 2026-11-14, so stage 8 corrected DE-1, and line 13 and U5 stay.
- **Two Blockers lowered:** stage 8 rated stage 5's IC-1 and IC-2 as Major, since one mount line and one pull-or-build line fix them.
- **One stage 8 fix challenged:** stage 9's IR-7 challenges ZI-6. Expected tiles must come from the block windows, not the data extent; A6 below uses IR-7's form.

## Overall verdict

**Approve after one revision pass.** The plan keeps to the approved architecture (M2, a toolchain-only image, code mounted at `/src`, custom code only where G5 allows) and reopens no specification decision. Its repository claims hold up: stage 9 verified 25 groups against file and line.

An agent could not yet carry out most tasks, for four reasons:

- **One Blocker (ZI-1):** a missing provenance value makes the fingerprint ignore code changes, so a resumed build reuses stale blocks.
- **Shared contracts left for the implementer to invent:**
  - the profile and edits schemas;
  - the `report.json` and `camera-path.json` shapes;
  - the download address templates;
  - what a bare `clean` does.
- **Missing owner gates:** the open decisions have no "stop until the owner confirms" steps, so an agent would stall or adopt the proposals silently.
- **Tests that would fail for the wrong reason:**
  - the test command (`MAPTEST`) as written;
  - the spike regression's blur edges;
  - the seam test without borders;
  - the WebP check on ink colour.

**Tasks ready once the fixes land:**

- Tasks 3, 4, 5 and 8: ready once the stage 8 fixes are in.
- Task 6: also needs ZI-10.
- Tasks 1, 2, 7 and 9–16: need the action items below.

## Owner decisions needed

| # | Question | Recommendation |
| --- | --- | --- |
| D1 (U1) | **Where the public CI example sits.** Is the Dhofar window (17.50–17.75° N, 56.80–57.05° E) neutral, and does it hold land? | Check its land fraction from the Water Body Mask and that cell E057 is absent from `tileList.txt`, then confirm. If it is all sea, pick a nearby window from the same check. |
| D2 (U2) | **The image name and its visibility.** The image name is derived from `github.repository_owner`, lowercased (`ghcr.io/mohdfarag/seerah-map`). The owner makes the package public after its first push; the fallback is a personal token. | Yes |
| D3 (U4) | **Where pip comes from.** Take pip from Ubuntu's archive, which cannot be pinned, or run a hash-pinned pip wheel directly. | The hash-pinned wheel, so every dependency stays pinned. |
| D4 (U6) | **The worker memory default.** `--per-worker-mib` defaults to 2048 until step-1 check 12 measures it, with `GDAL_CACHEMAX` counted inside that allowance. CI's 6 GB cap then gives 3 workers. | Yes |
| D5 (U7) | **Per-zoom mode.** Defer it to a short follow-up before trial step 2, the first step that uses it, or build it in phase 1. | Defer. Phase 1 drops `--mode`, and the plan lists the deferral as a deviation. |
| D6 | **The deviations list.** Approve, with the plan, its listed departures from the specification. | Yes |
| D7 | **What approving the plan approves.** Each decision is either approved with the plan, or the agent stops at a gate step until the owner confirms it (IR-1). | Approve D1–D6 with the plan. Keep one gate for D1, since it needs the land check first, and one at the end of Task 16, where the owner approves the push and opens the pull request or runs the workflow (IR-16; AGENTS.md: no pull request unless the owner asks). |

**The deviations list (D6):**

- tippecanoe and WhiteboxTools join the image in phase 3 (U3);
- the thread variables are set in the Dockerfile (U5);
- profile paths are relative to `/data`;
- `run.py` gains `--data`, `--image` and `--print-tag`;
- spec files 41, 42 and 44 are pulled forward;
- `natural_earth_max_zoom` and the coverage tests are deferred to phase 3;
- per-zoom mode is deferred (D5).

## Action items

| ID | Item | Raw findings |
| --- | --- | --- |
| A1 | **The fingerprint never ignores a code change (Blocker).** When a provenance value is missing, the fingerprint fills it in: the commit with the SHA-256 of `/src/tools/map/src` in sorted path order, the digest with the GDAL version plus the sorted package list. `"unknown"` stays only in `metadata.json` and the report. Add one test. Document the plain `docker run` contract in `tools/map/README.md`. | ZI-1, IC-3, CO-18, DE-1 |
| A2 | **A test container that matches the runtime.** MAPTEST becomes the stage 8 command: `--security-opt label=disable`, `map-data` mounted at `/data`, `-w /src/tools/map`, and `-p no:cacheprovider`. CI uses the same flags. The Dockerfile adds `RUN mkdir -m 1777 /cache /work /out`. The launcher's tests move to `tools/map/host_tests/`. | ZI-2, ZO-1, ZO-2, ZO-5, CO-1, IC-1, IC-5, IC-7 |
| A3 | **Image identity.** `--image` skips the pull, and an image with no repository digest records `local:<Id>`. `--print-tag` is added to Task 15, and both CI jobs take the tag from it. The `test` job runs `docker pull … \|\| docker build …`. `map.yml` gets `concurrency`, `timeout-minutes` and `workflow_dispatch`. The agent's own exit gate is local (MAPTEST, host tests and pre-commit), and the owner pushes and runs the workflow. | ZI-3, IR-16, CD-14, SY9-5, ZO-3, ZO-4, IC-2, IC-4, CO-7, CO-8, ZO-22, IC-16, DE-11 |
| A4 | **Toolchain safety.** Pin numpy in `requirements.in` to the base image's version, and import `osgeo.gdal_array` in the image test. Add one morecantile call and one OSR transform in the same process, use GDAL 3.13's name for the in-memory driver, copy go-pmtiles' licence file, and use `forkserver` for workers. State the pinning rule for Python: direct dependencies use `==` with the newest release at least 14 days old; transitive pins are whatever pip-compile resolves, recorded as the exception. | ZI-4, ZI-17, IR-2, ZO-6, IC-6, DE-12, ZO-28 |
| A5 | **Values at the data's edges.** Build both VRTs over the data extent, so absent areas read as 0 m and Water Body Mask 1. The per-pixel cell lookup goes. Invalid pixels are filled from the nearest valid pixel before blurring, then `valid` is applied again. Task 10 states "land = mask ≥ 128". Add a test that a hill cut by the extent edge leaves no dark rim. | ZI-5, ZO-9, ZO-10, IC-9, CO-14 |
| A6 | **Completeness from the grid.** Assert that every block's style file exists before the VRT is built. `expected_tiles` covers zooms 0 to `max_zoom` over the windows of `grid.blocks(data_extent, block_zoom)`, not the data extent. Add a test with one block file deleted. | ZI-6 corrected by IR-7, ZO-8, IC-10 |
| A7 | **Per-zoom mode** follows D5: removed from phase 1 if deferred. | ZI-7, ZO-7, IC-11, CO-5 |
| A8 | **The example window and the one-block test.** Run the land and `tileList.txt` checks before asking the owner (D1). The window spans a present cell and its absent neighbour. Run the single-block comparison at `block_zoom: 8` and assert one block, moving it to Task 14. `camera-path.json` uses minimum zoom 4. | ZI-8, ZO-15, ZO-21, IC-12, IC-13, SY-13, ST-6 |
| A9 | **A spike regression that can only fail for a real reason.** The band blur uses `mode="nearest"` and `radius=4`. The banding test uses `band_blur_px=0`. Cite spec line 259 for 7.36 and spec line 241 for the mask values. Add the committed generator `tests/fixtures/make_spike_relief.py`, with fixed parameters: grid size, pixel size `grid.pixel_m()`, hill, ridge, and a sea corner below 0.5 m. Store `pixel_m` in the `.npz`. Give the exact `docker run` command, writing to `/out`. | ZI-9, IR-4, CD-6, SY9-13, ZO-12, IC-15, ZO-27, IC-29, CO-3, DE-14 |
| A10 | **Edit correctness.** The seam test uses bordered windows and compares the cropped cores. A carve is buffered by `width_m / 2 / cos(lat)`. An empty footprint is skipped. `border_px` uses the block's poleward latitude, and `edge_fade` measures to the data extent. `feather_m` must be greater than 0. | ZI-10, IR-3 (part), ZO-11, IC-19, IC-20, CO-15 |
| A11 | **Pack and report.** Packing follows the spec's order: `metadata.json` into the tile folder, `disk_to_pmtiles` with `PYTHONHASHSEED=0`, then a copy beside the output. Assert `trial: true` with `pmtiles show`, and check the header bounds. Add `size_by_zoom`. Write out every `report.json` field with its type and unit (IR-11's list). `tiles.done` lives at `<area>/tiles.done`. The tile folder holds only `z/x/y` files, with any viewer output turned off. | ZI-11, IR-8, IR-11, ZO-13, ZO-17, IC-24, IC-27, CO-9, DE-8 |
| A12 | **Resume tests that do not flake.** The CLI starts with `start_new_session=True` and is killed with `os.killpg`. Task 13 lists every progress line with its exact text. Check 5 is written as a branch with both outcomes and the exact `strace` command. Check 6 runs on a PNG tiling, where RGB must equal the ink exactly. | ZI-12, IR-9, IR-10, SY9-2, ZO-14, IC-8, CO-10 |
| A13 | **Contracts written out**, each as a table or an exact example, because phases 2 and 3 read them: the profile and edits schemas (key, type, required, range, default, and the edits list key); `camera-path.json`'s JSON; the download templates for the DEM, the Water Body Mask, `tileList.txt` and Natural Earth (`ne_base` written out); the content schema's `$defs` copied as `source` and `review`, with `sources` an array; jsonschema pinned to 4.26.0. Also: a bare `clean` lists and removes nothing, while `--fingerprint F` and `--all` remove; `fetch` and `build` refuse a lock whose cells no longer match the profile; `tileList.txt` is read only when there is no lock; `urlopen` uses `timeout=60`. | IR-3, IR-5, IR-6, IR-12, IR-13, CD-7, ZI-18, ZO-19, SY-3 |
| A14 | **Owner gates and deviations made explicit.** Add the D1–D7 rows, the "approved with the plan" marking, a Step 0 gate where D7 keeps one, and the Deviations list. Fix U3's reason, line 27's pinning claim against U4, and U2's cross-reference to check 9. `check_clean` covers both checkouts, and relative defaults resolve against the public repository root. | ZI-13, IR-1, IR-15, CD-13, SY9-8, ZO-16, IC-23, ST-8, SY-15, ZO-24, SY-2, SY-16, CO-21, ZO-20, IC-14, DE-9, DE-15, ZO-25, IC-17, ST-10 |
| A15 | **Paths, files and order.** Write every Files path in full. Fix task ownership: `__init__.py` belongs to Task 1, and `conftest.py` is created in Task 9. List `docs/map-trial/step-1.md` and the fixture files. Move check 7 to Task 5, through `/vsicurl/`, with the class legend. `fetch` prints the copy instruction. Task 14 gives the exact `docker run` command and the copy step. Failures found in Task 14 are fixed in the owning module, in their own commit, with a regression test. | ZI-14, ZI-15, IR-14, IR-17, CD-9, CD-12, SY-1, ST-7, ST-9, DE-16, SY-21, CO-6, CO-12, IC-22, IC-26 |
| A16 | **Context and editorial pass.** A four-line "Where this fits" paragraph. One clause each for the variants, the private repository, the neutrality rule, `camera-path.json`, the Natural Earth files, the mask blocks and `view_reach_km`. Relabel the unit groups. Add an Author, Status and Date block and a `## Tasks` heading. One meaning per term. Exact signatures. Test oracles with their preconditions. Docs duties per AGENTS.md. | ZI-16, ZI-18 (rest), IR-18 to IR-26, CD-1 to CD-5, CD-8, CD-10, CD-11, CD-15 to CD-18, SY9-1, SY9-3, SY9-4, SY9-6, SY9-7, SY9-9 to SY9-12, SY9-14 to SY9-16, the CO and ST nits |

**Cut, as heavier than one publisher needs (stage 8, accepted here):**

- the critic's operational hardening: concurrent-run locks, extra risk tables and stop-gate frameworks (DE-3 to DE-7, DE-10, DE-13, ST-3, ST-4, ST-11);
- multi-architecture pins (ZO-23);
- IC-1's extra path variables;
- IC-2's artifact restructure;
- the ancestor-chain and two-cell generator ideas;
- style-only nits.

**Settled during implementation (step 1 checks and the image test):**

- check 5, whether GDAL writes tiles atomically;
- check 6, whether averaging keeps the ink;
- check 7, the Water Body Mask's class values;
- the base image's numpy version;
- the in-memory driver's name;
- the tiler's option for turning off viewer files.

## Traceability

Every raw finding maps to one of A1–A16, D1–D7, "cut" or "settled during implementation".

- **Stages 5 to 7 (131 findings):** mapped through stage 8's triage table ([intermediate_zoom_out_report.md](intermediate_zoom_out_report.md), "Triage table"). Each ZI item maps to an action item:
  - ZI-1 → A1;
  - ZI-2 → A2;
  - ZI-3 → A3 and D2;
  - ZI-4 → A4 and D3;
  - ZI-5 → A5;
  - ZI-6 → A6;
  - ZI-7 → A7 and D5;
  - ZI-8 → A8 and D1;
  - ZI-9 → A9;
  - ZI-10 → A10;
  - ZI-11 → A11;
  - ZI-12 → A12;
  - ZI-13 → A14, D4 and D6;
  - ZI-14 and ZI-15 → A15;
  - ZI-16 → A16;
  - ZI-17 → A4;
  - ZI-18 → A13 and A16.

  C findings are under "Cut"; S findings are under "Settled during implementation".
- **Stage 9, implementation readiness:**
  - IR-1 → A14 and D7;
  - IR-2 → A4;
  - IR-3 → A13 and A10;
  - IR-4 → A9;
  - IR-5, IR-6 → A13;
  - IR-7 → A6;
  - IR-8 → A11;
  - IR-9, IR-10 → A12;
  - IR-11 → A11;
  - IR-12, IR-13 → A13;
  - IR-14 → A15;
  - IR-15 → A14;
  - IR-16 → A3 and D7;
  - IR-17 → A15;
  - IR-18 to IR-26 → A16.
- **Stage 9, codebase details:**
  - CD-6 → A9;
  - CD-7 → A13;
  - CD-9, CD-12 → A15;
  - CD-13 → A14;
  - CD-14 → A3;
  - the other CD nits → A16.
- **Stage 9, style:**
  - SY9-2 → A12;
  - SY9-5 → A3;
  - SY9-8 → A14;
  - SY9-13 → A9;
  - the rest → A16.
