# Stage 7: Critic review, map trial phase 1 plan

**Target:** `docs/design/map-trial-phase-1-plan.md`, lines 1-121 (the header, up to Task 1). The approved spec is `docs/design/map-trial-build.md`.

**Mode:** REVIEW. egm-design and egm-structural used the feature spec standard, adapted to the plan's header sections. egm-style used its persona's rules.

**Earlier reports:** `zoom_out_report.md` (ZO-n, IC-n) and `comprehension_report.md` (CO-n). Every reviewer read both. Findings that only repeat them have been left out, and findings that build on them say so.

**Ground rule:** the spec and the owner's decisions are final. These findings are about risks in the plan, not requests to reopen the spec.

## Reviewer status

| Reviewer | Status | Verdict | Blocker | Major | Nit |
| --- | --- | --- | --- | --- | --- |
| `egm-design` | completed | Revise before execution | 0 | 11 | 6 |
| `egm-structural` | completed | Revise (one pass) | 0 | 9 | 4 |
| `egm-style` | completed | Approve with changes | 0 | 4 | 17 |
| **Total** | | | **0** | **24** | **27** |

These are raw counts. The overlaps listed below bring the number of distinct issues down to about 41.

## Overlaps between reviewers

| Issue | Findings | Note |
| --- | --- | --- |
| Line 29's path rule is broken by the Files lists in Tasks 2 to 14 | SY-1, ST-9 | Both are Major. Separate from ZO-1 and CO-1, which covered only MAPTEST. |
| No Risks or stop-gates section in the header | ST-4, DE-3 | Both are Major. DE-3 adds a format: trigger, who decides, tasks blocked, fallback. |
| The Architecture section gives no data flow | ST-5, DE-17 | Major (ST) and Nit (DE). |
| The decisions table gives no alternatives and no status | ST-6, DE-13 | DE-13 adds U3's fingerprint reset. |
| The file tree is incomplete, and its task tags are wrong or missing | ST-7, SY-21, DE-16 | DE-16 adds `.dockerignore` and the files the tag covers. |
| The unit groups in lines 7-11 are wrong | SY-4, DE-2 | SY-4 covers units left out of every group. DE-2 covers edits.py, which uses GDAL but is listed as pure numpy. |
| Deviations from the spec are scattered, and "the one deviation" is not true | ST-8, SY-15 | ST-8 adds an unflagged item: file 44 (dependabot) pulled forward. |
| Step 1 checks: where they belong, what they mean, and check 9 | ST-10, SY-18, DE-11 | DE-11 adds that U2 is check 9 and has no fallback. |
| The title is malformed | ST-12, SY-6 | |
| Line 3's skill instruction | ST-13, SY-7 | ST-13: it depends on one tool. SY-7: it says both "required" and "recommended". |
| The decisions heading | ST-6, SY-12 | |
| cgroup and `memory.max` edge cases | DE-15, SY-20 | Different points that touch the same Review Focus item. |

## egm-design

**Verdict:** revise before execution, with no Blockers. The header has the right shape, but it adds a portability goal the spec does not have and calls a module that uses GDAL "pure numpy". It also leaves six operational risks unhandled.

Claims about how GDAL, GHCR and the Copernicus bucket behave are the reviewer's reasoning. The reviewer did not test them.

### DE-1. Major. Lines 13 and 68 (and line 572)

- **Problem:** "the pipeline runs unchanged on any Docker host, local or cloud" is a goal the approved spec does not set. The spec says run.py is the only way the owner runs the tool, and A8 rejected cloud machines. This goal then justifies U5 and the "unknown" provenance fallback in Task 13. IC-3 showed that this fallback breaks G4.
- **Fix:** reword line 13 to "the same image and entry point run through run.py on the owner's machine and in CI." Justify U5 by CI alone. If cloud runs are wanted, make them a new owner decision (U6).

### DE-2. Major. Lines 9-11, compared with Task 6 (lines 329-332)

- **Problem:** "edits" is listed among the pure numpy functions, but Task 6 rasterises with OGR and `gdal.Rasterize`. That breaks the stated line between pure functions and stages, and edits.py cannot be unit-tested without GDAL.
- **Fix:** move the GDAL rasterising into a stage helper that produces boolean `inside` masks. `edits.apply(...)` then stays pure numpy and scipy. If edits.py stays as it is, move it out of the "pure" group and say why.

### DE-3. Major. Lines 60-84

- **Problem:** the header has no section for risks or stop gates. The stop points are scattered through the tasks: check 7 (line 208), the G8 regression stop (line 371), U1 blocking Task 14 (line 602) and check 5 (line 512). If U2 is declined, the fallback (pull with a token) appears nowhere.
- **Fix:** add a "Stop gates and risks" table with these columns: trigger, who decides, tasks blocked, fallback. Include the external bucket, GHCR visibility, CI time and image size, and the "Expected: FAIL" steps that depend on Task 1's image. (Same issue as ST-4.)

### DE-4. Major. Lines 29 and 76-84

- **Problem:** `/cache` and `/work` are shared volumes, and nothing stops two runs from using them at once. Temporary names are fixed (`<file>.part`, `<path>.tmp`), so two writers truncate each other's files. A clean or a finishing build can also delete a work area that another build is still writing.
- **Fix:** add a Global Constraint and a Review Focus item. Take an exclusive `fcntl.flock` on the work area for build and clean, and a cache lock for fetch. Fail fast with a clear message, and make temporary names unique per process. Add a test in which a second build on the same fingerprint exits 1 and leaves the area untouched.

### DE-5. Major. Lines 29 and 52-56

- **Problem:** builds run with `--network none` only through run.py. In MAPTEST and CI, fetch and build share one container with network access, and `test_network_none_except_fetch` checks only the argument list. A build that reaches the network by mistake would pass CI. One example is a VRT that reads through `/vsicurl/`.
- **Fix:** in map.yml, run fetch in one `docker run`, then run the slow build tests in a second one with `--network none`. Give the tests that need the network their own marker (IC-13's `network` marker). Add a constraint that build and clean never open a socket.

### DE-6. Major. Lines 27 and 116

- **Problem:** the fingerprint includes the image digest, so a Dependabot bump merged during the trial changes the image. A long build can then no longer resume, and the builds compared under G1 and G8 would use different toolchains.
- **Fix:** add a constraint that toolchain updates under tools/map merge only between trial steps, with the owner's approval, and that each step report records its image digest. Leave the tools/map entries out of dependabot.yml until sign-off, or keep them with a comment that points to this rule.

### DE-7. Major. Lines 5 and 114-115

- **Problem:** the end-to-end test in CI depends on an external bucket that can change, checked against hashes recorded on first download. The actions cache drops entries after about 7 days without use, so the cell is downloaded again regularly. If the upstream file changes, main fails with no code change, and the only error is a generic hash mismatch.
- **Fix:** record each file's ETag or Last-Modified in the lock, so the error can say whether upstream changed or the cache is corrupted. Add a weekly scheduled run of the workflow, and document how to regenerate `example.lock`.

### DE-8. Major. Line 33 (G9)

- **Problem:** `"trial": true` is required only in `metadata.json`. `relief.pmtiles` is the file that travels to devices. As planned it may carry no trial marker, so a later packaging step that reads only the .pmtiles file could not refuse it (IC-24 noted the location).
- **Fix:** require the marker in the PMTiles file's embedded metadata as well as in the sidecar file. Assert it with `pmtiles show` in `test_example_builds_complete`.

### DE-9. Major. Lines 68 and 82

- **Problem:** `GDAL_CACHEMAX=256` applies to each process, so N workers can use N x 256 MB on top of their own arrays. The plan never says whether `--per-worker-mib` includes the cache. It is also unverified whether the tiler's `-j` starts processes or threads. Either way, this setting is part of the memory budget, not a portability detail.
- **Fix:** state in U5 that `--per-worker-mib` includes the cache. Find out how `-j` works and record it. Measure in check 12 with the cache value fixed, and note that the value is deliberately left out of the fingerprint.

### DE-10. Major. Lines 76-84

- **Problem:** absent cells are read as ocean at 0 m with no check. If any of the 87 absent cells holds land, the output has a sea hole and no error is raised. That is a G3 failure the completeness check cannot see.
- **Fix:** during fetch, test each absent cell against the Natural Earth land file, using a small inward buffer. Exit 1 and list any absent cell that overlaps land. Add a fixture test, and record the full profile's result in the step 1 report.

### DE-11. Major. Line 65 (U2), with lines 70-74

- **Problem:** the plan hard-codes `ghcr.io/mohdfarag/seerah-map`, where the spec says `<owner>`. With GITHUB_TOKEN, a workflow can push only to the lowercased namespace of the repository owner, which the reviewer could not confirm. A wrong name fails the first push, and it puts an account name in code. U2 is also spec check 9, but line 70 claims only checks 5-7.
- **Fix:** derive the namespace in map.yml from `github.repository_owner`, lowercased. Keep it in one constant in run.py. Confirm the owner in U2. Add check 9 to lines 70-74, with its token fallback.

### DE-12. Nit. Lines 17 and 65

- **Problem:** a public GHCR image redistributes GDAL, go-pmtiles, scipy and the other tools from phase 1 on. The third-party notices for these tools are planned only for phase 3.
- **Fix:** add OCI source and licence labels to the Dockerfile. Either bring the notices forward to phase 1, or note in U2 that they follow in phase 3 and get the owner's agreement.

### DE-13. Nit. Lines 62-68

- **Problem:** each decision has one proposal and no rejected alternative. Alternatives exist in the spec: a private package pulled with a token for U2, and the spec's own run.py and CI environment setup for U5. U3 also leaves out a cost: adding tippecanoe and WhiteboxTools in phase 3 changes every fingerprint. (Overlaps with ST-6.)
- **Fix:** add a column for the rejected alternative and the reason. Note U3's fingerprint reset.

### DE-14. Nit. Lines 40-46

- **Problem:** "the spike's values, verbatim" is not true for all of them. Mask sigma and land at 128 come from the spec, and the spike uses `dem > 0.5`. The exaggeration is derived (CO-3 covers only that part).
- **Fix:** split the list into the spike's values and the spec's values, and cite the source of each.

### DE-15. Nit. Line 82 compared with line 84

- **Problem:** under cgroup v1, `memory.max` does not exist, and the plan does not say what `worker_count` does then.
- **Fix:** treat a missing or unreadable `memory.max` the same as `max` (use 1 worker or `--workers`, and log the reason). Add a test.

### DE-16. Nit. Lines 86-118

- **Problem:** the file tree has these mismatches with the tasks:
  - `__init__.py` is tagged Task 13, but Task 1 creates it.
  - `tests/conftest.py` is modified in Task 14, but no task creates it.
  - `step-1.md` and `tests/fixtures/README.md` are missing.
  - There is no `.dockerignore`.
  - The image tag hashes only two files.
- **Fix:** correct the task numbers and add the missing files. Add a `.dockerignore`. State that the tag covers every file the Dockerfile copies. (Overlaps with ST-7 and SY-21.)

### DE-17. Nit. Lines 7-13

- **Problem:** the header never names the files the stages pass to each other or the markers that let a stage resume. Examples are style/ and mask/ blocks, style.vrt, the tile folder with tiles.done, relief.pmtiles, metadata.json and report.json.
- **Fix:** add a three-line data-flow sketch. (Overlaps with ST-5.)

## egm-structural

**Verdict:** revise. There are no Blockers, but seven structural gaps need one revision pass. The plan is missing several sections, its heading levels break at the Task boundary, and its file index is incomplete.

### ST-1. Major. Lines 120-122 (affects everything up to line 698)

- **Problem:** `### Task 1` follows `## File structure` directly, so in any outline all 16 tasks appear as children of "File structure". `## Self-review` at line 698 sits beside the tasks instead of above them.
- **Fix:** insert `## Tasks` before Task 1, and keep the tasks at H3.

### ST-2. Major. Lines 5-20

- **Problem:** Goal, Architecture, Tech Stack and Spec are bold labels, not headings. The plan also has no Author, Status or Date block, while the spec has one. A reader cannot tell whether the plan is a draft or approved, and AGENTS.md reserves approval for the owner.
- **Fix:** add a metadata list (Author, Status, Date, Spec) under the H1. Promote Goal, Architecture and Tech stack to H2s.

### ST-3. Major. Line 5

- **Problem:** the Goal has no exit check, and there is no Non-goals section. The only place out-of-scope work is listed is the self-review (line 724).
- **Fix:** add "*Check:* the `map` workflow is green on the pull request, including `slow` tests, and `pre-commit run --all-files` passes." Add `## Non-goals` listing the base layer, the valley probe, the Natural Earth coverage check, the trial profiles in seerah-content and all device work.

### ST-4. Major. Lines 22-121

- **Problem:** there is no Risks section. The plan's own risks have no home, for example the unverified header bounds, the unverified base image contents, U1 gating the tests, and the 2-week pin rule against Dependabot. The spec's Known risks that apply to phase 1 are not linked. (Same issue as DE-3.)
- **Fix:** add `## Risks`, with one row per risk and the task or check that handles it. Link the spec's Known risks section.

### ST-5. Major. Lines 7-13

- **Problem:** the Architecture section never gives the order of the stages, or which container path each stage reads and writes. (Overlaps with DE-17.)
- **Fix:** add a flow line, for example: `fetch: network to /cache, lock to /out; build: /data + /cache to /work/<fp>/ (blocks, then tiles) to /out/<name>-<variant>-<fp8>/`. Link the spec's Components section.

### ST-6. Major. Lines 60-68

- **Problem:**
  - The decisions table has no options column and no status column.
  - U2 is an owner action, not a decision.
  - The spec's Q1 (the trial camera's minimum zoom) is not carried over, although Task 14 writes `camera-path.json`, which records it.
- **Fix:**
  - Add the columns "Options considered (rejected because)" and "Status".
  - Move U2 to an "Owner actions" list.
  - Note Q1's working value (zoom 4).
  - Shorten the heading.

### ST-7. Major. Lines 86-118

- **Problem:** the tree leaves out files the tasks create: `step-1.md`, `spike_relief.npz`, `tests/fixtures/README.md`, `tests/fixtures/fetch/` and `tests/test_run.py`. Lines 91, 92, 99 and 113 have no task number. Files that are modified look the same as files that are created. (Overlaps with SY-21 and DE-16.)
- **Fix:** list every file the tasks touch, tag each line with its task number and `[C]` (create) or `[M]` (modify), and add a legend.

### ST-8. Major. Lines 20 and 116

- **Problem:** line 20 says the plan covers files 1-18, but line 116 brings in `.github/dependabot.yml`, which is spec file 44 (phase 3). Nothing flags this. The deviations are spread across U3, U5, line 66 and the self-review. (Overlaps with SY-15.)
- **Fix:** add `## Deviations from the spec`. List U3, U5, files 41, 42 and 44 pulled forward, and the items from ZO-16. Point line 20 to it.

### ST-9. Major. Line 29, compared with lines 173, 216, 253, 283 and 594

- **Problem:** the header says host paths are relative to the repository root, but from Task 2 on the Files lists are relative to `tools/map/`, and Task 14 uses bare file names. An agent that follows line 29 creates files in the wrong place. (Same issue as SY-1.)
- **Fix:** either write every Files path from the repository root, or state the exception in line 29.

### ST-10. Nit. Lines 70-74

- **Problem:** the step 1 checks sit under "Unresolved decisions", but they are facts to measure, not decisions. U2 is the same as check 9, and neither mentions the other. The plan does not say which step settles checks 8, 9 and 12.
- **Fix:** move the checks to their own section, cross-reference U2 with check 9, and name the step that settles the rest. (Overlaps with SY-18 and DE-11.)

### ST-11. Nit. Lines 76-84

- **Problem:** the Review Focus items are cited elsewhere by their position ("Review Focus 2"), so reordering the list would silently break those references. The order follows neither the tasks nor severity.
- **Fix:** give the items IDs (R1-R5), sort them by task, and update the references.

### ST-12. Nit. Lines 1 and 22-86

- **Problem:** the title is broken by a full stop, and the headings mix Title Case with sentence case. The groups inside Global Constraints are bold labels instead of headings. (Overlaps with SY-6.)
- **Fix:** retitle to "Map trial, phase 1 implementation plan: tool core and example". Use sentence case for all headings, and make the constraint groups H3s.

### ST-13. Nit. Lines 3, 20, 47 and 72-74

- **Problem:** line 3 requires `superpowers:` skills, which only Claude Code provides, while AGENTS.md is written for every agent, including Codex. AGENTS.md is mentioned without a link. "Technical Plan" and "check 5/6/7" do not link to the spec. (Overlaps with SY-7.)
- **Fix:** make line 3 tool-neutral, and add links to AGENTS.md and to the spec's sections.

## egm-style

**Verdict:** approve with changes. There are no Blockers. Four Major findings are claims that go beyond their own tests or contradict later tasks, so an implementer could act on them. The rest are wording Nits.

### SY-1. Major. Line 29 (also lines 126, 173 and 216)

- **Problem:** "Everything else is a host path relative to the repository root." The Files lists from Task 2 on break this rule. (Same issue as ST-9.)
- **Fix:** "Every other path is a host path relative to the repository root, except in a task's Files and Interfaces lists, where `src/` and `tests/` are relative to `tools/map/`." Writing every path in full also works.

### SY-2. Major. Line 27 compared with line 67

- **Problem:** the claim that every dependency is pinned is absolute, but U4 accepts a pip installed with apt that "is not pinnable".
- **Fix:** add "The one exception is pip from Ubuntu's archive, if the base image lacks it (U4)."

### SY-3. Major. Lines 80 and 83

- **Problem:** two Review Focus items promise more than their named tests check:
  - Line 80 says a partial download is "never written to the lock". `test_fetch_discards_partial` (line 429) does not check this, and "never trusts it" has no testable meaning.
  - Line 83 covers both southern and western extents, but the test at line 194 sets only `south: -1`. "Clear message" is subjective.
- **Fix:**
  - Line 80: describe only what the test checks, or add an assertion about the lock.
  - Line 83: add a `west: -1` case, and say the message must name the N/E limit.

### SY-4. Major. Lines 7-11

- **Problem:** the three unit groups leave out fetch, config and strict_yaml, workarea, and report. Fetch is the first unit the Goal names. Grid uses morecantile, so it is not pure numpy. (Overlaps with DE-2.)
- **Fix:** group the units as Configuration, Pure functions (numpy, scipy, morecantile), I/O modules (fetch, workarea, block stage, tile stage, pack), Report, and the CLI.

### SY-5. Nit. Line 5

- **Problem:** "proven end to end on a synthetic example" is not accurate. One window shows that the pipeline runs end to end; it does not prove the tool. The elevation data in the example is real Copernicus data.
- **Fix:** "tested end to end in CI on a small public example (one real Copernicus cell with a synthetic edit)."

### SY-6. Nit. Line 1

- **Problem:** the full stop before "Implementation Plan" breaks the title. (Overlaps with ST-12.)
- **Fix:** "Map trial phase 1 implementation plan: tool core and example".

### SY-7. Nit. Line 3

- **Problem:** the line says "REQUIRED SUB-SKILL" and then "(recommended)", and it offers two options.
- **Fix:** "use one of the sub-skills ... (preferred) or ...". (Overlaps with ST-13.)

### SY-8. Nit. Line 28

- **Problem:** "to the repository's ruff settings" is ungrammatical. It is also ambiguous, because Task 1 creates a second `pyproject.toml`.
- **Fix:** "Code targets Python 3.11 and passes ruff with the settings in the repository root's `pyproject.toml`."

### SY-9. Nit. Line 36

- **Problem:** "every relief tile exists at every zoom" is circular, because it does not say which tiles count.
- **Fix:** "every tile that covers the data extent exists at zooms 0 to `max_zoom`, fully transparent tiles included."

### SY-10. Nit. Line 46

- **Problem:** "All pixel values are at zoom 12" reads as if it were about raster sample values.
- **Fix:** "All lengths in pixels (px) are zoom-12 pixels."

### SY-11. Nit. Line 48

- **Problem:** the Docstrings line has no verb, and it reads as if modules must state their inputs and units.
- **Fix:** "Every module and every public function has a docstring. A function's docstring states its inputs, outputs and units."

### SY-12. Nit. Line 60

- **Problem:** the parenthetical in the heading is hard to parse, and the repository's term is "open decisions". (Overlaps with ST-6.)
- **Fix:** use "## Open owner decisions", followed by one lead sentence.

### SY-13. Nit. Line 64

- **Problem:** "The owner confirms ..." reads as if it has already happened.
- **Fix:** "Owner to confirm ..."

### SY-14. Nit. Line 65

- **Problem:** the Decision cell states the answer, and the Proposal cell repeats it.
- **Fix:** put the question in the Decision cell and the answer in the Proposal cell.

### SY-15. Nit. Line 66

- **Problem:** "the one deviation from file 1" is false, because U4 and U5 also change the image. (Overlaps with ST-8.)
- **Fix:** "This removes two tools from file 1. U4 and U5 also change the Dockerfile."

### SY-16. Nit. Line 67

- **Problem:** "reproducible" contradicts the sentence before it, which says the image cannot be rebuilt to the same bytes.
- **Fix:** "Rebuilding the image may give different bytes, but a run on the same image digest gives the same output."

### SY-17. Nit. Line 68

- **Problem:** "small deviation ... made for portability" is subjective and repeats the sentence before it.
- **Fix:** "This deviates from the spec."

### SY-18. Nit. Line 70

- **Problem:** the checks are called "facts", but they are questions to measure. "Settles" and "is recorded by" are two verbs for the same action. (Overlaps with ST-10.)
- **Fix:** "Phase 1 answers three of them and records the answers in `docs/map-trial/step-1.md`."

### SY-19. Nit. Line 78

- **Problem:** the items are called "inputs", but most are environment states. "Pinned" already means dependency pinning elsewhere in the plan.
- **Fix:** "Five edge cases the spec implies but does not name. Each one has a test in the task named."

### SY-20. Nit. Line 82

- **Problem:** "never divides by an unbounded number" describes the risk wrongly: the risk is dividing an unlimited memory value, not dividing by it. "And says so" is vague.
- **Fix:** "It never computes a worker count from an unlimited memory value, and it logs the reason."

### SY-21. Nit. Lines 88-118

- **Problem:** `docs/map-trial/step-1.md` is missing from the tree, and several entries have no task tag. (Overlaps with ST-7 and DE-16.)
- **Fix:** add the missing line and tag every entry.

## Most important findings

1. **The path rule in line 29 contradicts the tasks (SY-1, ST-9).** An agent that follows the header would create `src/` and `tests/` at the repository root. Two reviewers found this independently.
2. **The header has no Risks or stop-gates section (ST-4, DE-3).** The stop points and their fallbacks are scattered through the tasks, and the token fallback for U2 appears nowhere. Two reviewers found this independently.
3. **Nothing guards the shared volumes against concurrent runs (DE-4).** Fixed temporary names, and a clean or finishing build deleting a live work area, can silently corrupt the cache or a build. This is the reliability failure the owner ranks highest.

Next in weight: DE-1 (a cloud-portability goal the owner never set is driving two deviations), DE-5 (network isolation for builds is not exercised in CI) and DE-10 (absent cells are assumed to be sea without a check).
