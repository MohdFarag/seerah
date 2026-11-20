# Map trial

Trackers and step reports for the map trial required by [ADR 0009](../adr/0009-map-data-pipeline.md) decision 13 before building the first v1 map package.

- **Specification:** [docs/design/map-trial-build.md](../design/map-trial-build.md) (Approved 2026-11-14; option (c) ramp and fade amended 2026-11-17)
- **Implementation plans:**
  - [Phase 1: tool core and example](../design/map-trial-phase-1-plan.md) — merged 2026-11-16 (`tools/map/`, `map-data/example/`, `map` workflow and public toolchain image)
  - [Phase 2: the map on the device](../design/map-trial-phase-2-plan.md) — merged 2026-11-17 (`:core:map`, `benchmark` build type, `app.seerah.trial`, release guard)
  - [Phase 3: base layer, trial inputs and documents](../design/map-trial-phase-3-plan.md) — adds `base.pmtiles` (`land` and `coast`), `tippecanoe` and `WhiteboxTools` in the toolchain image, `:core:map` base-layer wiring, documentation/notices, and private trial inputs plus the valley probe behind owner gates

> [!NOTE]
> **Temporary device deferral (as of 2026-11-20):** As recorded in [step-1.md](step-1.md), the Oppo A55 and Huawei MatePad 11.5 are temporarily unavailable for testing. Step 1 checks 1, 4 and 11 on both devices, as well as the Oppo A55 and MatePad measurements required for trial items 3 and 11, are deferred until those devices are available. Galaxy A54 step 1 mechanics numbers (run against phase 2 `relief.pmtiles`, before `base.pmtiles` landed) are recorded in [step-1.md](step-1.md).

## Decision 13 items

| # | Item (ADR 0009 decision 13) | Planned step | Status (2026-11-20) | Evidence and notes |
| --- | --- | --- | --- | --- |
| 1 | Relief at zooms 11 and 12, with measured size of the full data extent, margin included | Step 4 (`step-4.md`) | Open | `tools/map/` supports zooms 11/12 and inside/outside outline byte counts in `report.json`; full-extent run awaits steps 2–4 |
| 2 | Option (a): regional relief enlarged to zooms 13–15 at the synthetic test edits, fade moved later | Step 2 (`step-2.md`) | Open | `option-a` view (fade 13.5–15.5) implemented in `benchmark`; A54 example mechanics run in [step-1.md](step-1.md); awaits step 2 test-edit judgement on all three devices |
| 3 | Option (c): `color-relief` bands at the test edits, their size and cost on the Oppo A55 | Step 2 (`step-2.md`) | Open (Oppo A55 deferred) | Sharp edges with paired `interpolate` stops verified on Galaxy A54 ([step-1.md](step-1.md), check 3 and A54 run); awaits step 2 test-edit builds and Oppo A55 measurements |
| 4 | Option (b), if needed: boundary where the two relief files meet, detail maximum zoom and size | None unless items 2 and 3 fail | Conditional (not triggered) | Deferred unless options (a) and (c) both fail in step 2; would receive its own specification |
| 5 | Rendering each zoom separately (`--mode per-zoom`) against rendering at the top zoom and averaging down | Step 2 (`step-2.md`) | Open (`--mode per-zoom` deferred) | `--mode per-zoom` and the `per-zoom` app view are deferred to their own follow-up before step 2 (phase 1 D5, phase 2 D2, phase 3 D5) |
| 6 | Full build on the owner's machine, with wall time and peak memory recorded | Step 4 (`step-4.md`) | Open | Per-worker memory allowance check on two 2,048 × 2,048 px blocks passed at 1,024 MiB ([step-1.md](step-1.md), check 12); full build awaits steps 2–4 |
| 7 | Clean valley lines (`WhiteboxTools` breaching and D8 flow accumulation) | Step 1 probe and Step 5 (`step-5.md`) | Open | `whitebox_tools` (`v2.4.0`) added to the toolchain image in phase 3; step 1 GLO-90 probe under 8,192 MiB awaits owner run (to be recorded in [step-1.md](step-1.md); step 5 runs only if probe fits) |
| 8 | Lava fields and main valleys reading in style C | Steps 2 and 4 (`step-2.md`, `step-4.md`) | Open | Awaits step 2 window and step 4 full-extent builds on devices |
| 9 | Which modern features (towers, 1256 lava flow, artificial islands) show near events | Step 4 (`step-4.md`) | Open | Awaits step 4 full-extent build and owner cleanup list |
| 10 | Water Body Mask coast against Natural Earth at the widest zoom (and an earlier water mask if available), including extent joins | Step 4 (`step-4.md`) | Open | WBM legend verified ([step-1.md](step-1.md), check 7); `base.pmtiles` generation and `:core:map` sea/land/coast rendering built in phase 3; full-extent judgement awaits step 4 |
| 11 | Performance on the three devices against [ADR 0002](../adr/0002-proposed-stack.md) targets | Steps 1, 2 and 4 ([step-1.md](step-1.md), `step-2.md`, `step-4.md`) | Open (Oppo A55 and MatePad 11.5 deferred) | Galaxy A54 phase-2 mechanics numbers recorded in [step-1.md](step-1.md) (check 4 and the A54 run); Oppo A55 and Huawei MatePad 11.5 temporarily unavailable |

## Step reports

- **[step-1.md](step-1.md) (Step 1 — Foundations):** records checks 1–12 and the 2026-11-18 Galaxy A54 phase-2 run. Remaining in step 1: creating and owner-approving the private `seerah-content/map/trial/` profiles, synthetic test edits and locks; Step 1 `fetch` and `build` runs on the three windows; the valley probe (after the updated phase 3 toolchain image is published); optional phase 3 A54 base-layer smoke check; and deferred checks 1, 4 and 11 on the Oppo A55 and Huawei MatePad 11.5 once available.
- `step-2.md` (Step 2 — Small windows, not started): the `--mode per-zoom` follow-up, option (a) / option (c) / per-zoom builds, device screenshots and owner judgement of `window-spike`, `window-najd` and `window-yemen` (items 2, 3, 5, 8, 11).
- `step-3.md` (Step 3 — 10% band extrapolation, not started): contiguous band across Najd and the Hijaz to check time and temporary disk against the 12-hour and 120 GB limits.
- `step-4.md` (Step 4 — Full data extent, not started): full-extent build and review on all three devices (items 1, 6, 8, 9, 10, 11).
- `step-5.md` (Step 5 — Valleys, conditional / not started): valley layer trial (item 7), run only if the step 1 valley probe fits within the memory cap.
