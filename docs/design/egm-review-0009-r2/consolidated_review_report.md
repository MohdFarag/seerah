# Consolidated EGM Review: ADR 0009 (second review)

**Target:** `docs/adr/0009-map-data-pipeline.md` on branch `docs/adr-0009-map-data-pipeline`, commit `a280cb8`. The ADR has not been edited; the owner decides each change.
**Mode:** consolidated report, all review rubrics.
**Date:** 2026-11-14.
**First review:** [egm-review-0009](../egm-review-0009/intermediate_zoom_out_report.md), on the version before the owner's decisions of 2026-11-14.

## Stages run

| Stage | Reviewers | Report | Result |
| --- | --- | --- | --- |
| 5. Initial zoom out | zoom-out, inventive-catalyst | [zoom_out_report.md](zoom_out_report.md) | 0 🔴, 13 🟡, 9 🟢 |
| 6. Comprehension test | comprehension | [comprehension_report.md](comprehension_report.md) | 0 🔴, 5 🟡, 9 🟢 |
| 7. Critic review | design, structural, style | [critic_report.md](critic_report.md) | 0 🔴, 15 🟡, 29 🟢 |
| 8. Intermediate zoom out | zoom-out | [intermediate_zoom_out_report.md](intermediate_zoom_out_report.md) | 15 items; triage below |
| 9. Implementation readiness | none | none | Not run: the ADR has no implementation-plan section |

**Notes:**

- Every requested reviewer completed.
- Stage 6 ran alongside stage 5. It reads only the ADR, and no stage changes the ADR, so the order does not affect it.
- **Checked by the orchestrator:**
  - No signing tool exists yet (ZO-8, SY-2), so "the existing signing tool" is wrong.
  - ADR 0007 line 12 also assumes "tens of MB", so SY-16 is partly wrong.
  - ZI-12's recommendation to keep the spike's place diagrams is reversed below, because the owner rejected separate illustrations on 2026-11-14.

## Overall verdict

**Needs revision before acceptance; no blockers.** The architecture fits every owner decision. The 80 findings come mostly from a few missing rules:

- two kinds of terrain edit;
- tiles that are never missing;
- option (b) stated as invariants;
- one compatibility rule;
- one build, with a publishing order;
- the size consequences carried into the earlier ADRs.

## Owner decisions needed

| # | Question | Recommendation |
| --- | --- | --- |
| D1 | Split terrain edits into **cleanup** (removing known-modern things such as towers, artificial islands and reclaimed land; never adds shape; decided with the pipeline; can ship in the first package) and **reconstruction** (adds or restores historical shape; method deferred)? | Yes. Leave the 1256 lava flow alone unless the trial shows it is visible. |
| D2 | Under option (b), are the higher-zoom places exactly the tiles touched by approved reconstruction edits, derived by the build, rather than a hand-kept list of "important places"? | Yes. You still control the set by approving each edit. A smoothing floor keeps modern noise out elsewhere. |
| D3 | Can a map package with reconstructions be listed in `stable` (the channel Play builds read, closed beta included) only once an app with the disclosure is also in `stable`? | Yes |
| D4 | Do the spike's place diagrams (Makkah sites, Badr, Uhud) still stand? | **No**, superseded by your decision that events are explained through the map itself. The ADR says so in one line. (The reviewer's recommendation was the reverse; it didn't know your later decision.) |
| D5 | Can the map build tool be written before the private repository is renamed? It reads a checkout by path. | Yes |

## Action items

| ID | Item | Raw findings |
| --- | --- | --- |
| A1 | **Two edit classes** (D1). Decision 1 becomes "no modern features drawn as their own layer; modern terrain shape remains wherever no edit applies". The operations in decision 2 become examples. Unapproved edits are listed in the build log. | ZO-4, DE-5, SY-1, DE-12, DE-16, IC-5 (part) |
| A2 | **No missing tiles:** every file covers its extent at each zoom, with explicit transparent tiles, which PMTiles stores once. The ADR then doesn't depend on how the engine treats missing tiles under any option. | DE-4, IC-2, SY-10, the line 237 risk |
| A3 | **Option (b) as four invariants** (D2): one file per spot; the same rendering where the files meet; a smoothing floor; places derived from approved reconstruction edits. The mechanics go to the specification. | ZO-2, ZO-3, CO-3, CO-14, DE-4, DE-5, IC-7, SY-4, SY-15 |
| A4 | **The trial judges (a) and (c) on a synthetic test edit** at a neutral place that is never shipped. For every visual criterion, the owner judges on the three devices, side by side with build 11. | ZO-1, IC-1, SY-5 |
| A5 | **Disclosure:** `map.json`'s reconstructed areas are generated from the edits. AGENTS.md is amended: "labelled, or, for reconstructed terrain, disclosed outside the map". The publishing gate is D3. | IC-6, DE-6, ST-9, CO-13 |
| A6 | **One compatibility rule:** `tile_schema` changes only add; the app reads every schema ever listed in `stable`; line 179 is deleted. | ZO-5, DE-8 |
| A7 | **One build:** it reads the private checkout at one commit, `map/` and `content/`, to write citations. The manifest records the private commit, the public tool commit and the container digest; ADR 0007's manifest and decision 3 are amended. The `sources.lock` hash leaves `map.json`. "The signing tool from ADR 0007 (new work)." Publishing rule: content that relies on a reconstruction goes to `stable` no earlier than its map. | DE-7, ZO-8, ZO-9, ZO-13, CO-5, DE-10, SY-2, ST-8 |
| A8 | **Size consequences:** the estimate is not an upper bound; the package must stay under 2 GiB; storage peaks during an update; keeping the verified package applies to content only; bundling would need Play Asset Delivery (noted in the open decisions); approvals are batched into map versions; ADRs 0007 and 0008 are amended. A margin per zoom and a measurement of the full extent go to the specification and trial. | DE-1, DE-2, ZO-6, IC-4, SY-9, SY-16, DE-9, CO-10 |
| A9 | **Valleys:** "complete catchments" becomes "an edge rule in the specification". The tool, a 90 m routing grid and masking the sand seas go to the trial, with peak memory recorded. | ZO-7, IC-3, DE-3 |
| A10 | **Approved look kept:** "rendered at the file's top zoom and averaged down, as approved". Rendering each zoom separately becomes a trial comparison. | ZO-12 |
| A11 | **Option (c) defined:** one raster source, `relief`, whose encoding (painted, or a darkness value coloured by the app) is fixed before the first package. Its size is measured in trial item 3. | CO-1, DE-11, IC-8 |
| A12 | **Place diagrams superseded** (D4), in one line. | ST-12 |
| A13 | **Amends and facts:** "read at run time, whether bundled or downloaded"; name the content licence; introduce `map-data/`; add AGENTS.md (the approximation wording and its `map-data/` description) and ADRs 0007 and 0008 to Amends; amend ADR 0006 rather than editing its text. | CO-2, CO-4, CO-6, SY-3, ST-8 |
| A14 | **Editorial pass:** see the list below. | see list |

**A14, the editorial pass:**

- the wording nits: CO-7 to CO-9, CO-11, CO-12, ST-3 to ST-5, ST-10, SY-8, SY-11 to SY-15;
- ST-1 and DE-14: tie the tiers row to offline first;
- each risk points to its trial item;
- SY-6 and SY-7: cite the right sources;
- the FABDEM licence link.

**Cut, as heavier than one publisher needs:**

- the private archive of inputs (ZO-10, DE-13);
- tile caching groundwork (IC-9);
- skipping downloads by hash, and rebuilding only tiles near an edit (DE-2);
- `requires_map` (DE-7);
- hand edits for every Gulf reclamation (IC-5), which trial item 9 can compare instead;
- the crossfade (IC-2), kept only as a trial fallback;
- tables for lava fields and the tools (ST-6, ST-7);
- links for every alternative (ST-11);
- a separate security entry (ST-2).

**To the implementation specification or trial:** CO-10, CO-14, DE-3, DE-9, DE-15, IC-3, IC-4 (streaming extraction), IC-8, ZO-7.

## Traceability

Every raw finding maps to one or more of A1–A14, D1–D5, or "cut" or "spec/trial", as follows:

- **Stage 5, zoom-out:**
  - ZO-1 → A4;
  - ZO-2, ZO-3 → A3, D2;
  - ZO-4 → A1, D1;
  - ZO-5 → A6;
  - ZO-6 → A8;
  - ZO-7 → A9;
  - ZO-8, ZO-9, ZO-13 → A7;
  - ZO-10 → cut;
  - ZO-11 → D5;
  - ZO-12 → A10.
- **Stage 5, inventive-catalyst:**
  - IC-1 → A4;
  - IC-2 → A2, with the crossfade cut;
  - IC-3 → A9;
  - IC-4 → A8;
  - IC-5 → A1 and cut;
  - IC-6 → A5;
  - IC-7 → A3;
  - IC-8 → A11;
  - IC-9 → A8 and cut.
- **Stage 6:**
  - CO-1 → A11;
  - CO-2, CO-4, CO-6 → A13;
  - CO-3 → A3, A5;
  - CO-5 → A7;
  - CO-7 to CO-9, CO-11, CO-12 → A14;
  - CO-10, CO-14 → spec;
  - CO-13 → A5 and cut.
- **Stage 7, design:**
  - DE-1, DE-2 → A8;
  - DE-3 → A9;
  - DE-4 → A2, A3;
  - DE-5 → A1, A3;
  - DE-6 → A5, D3;
  - DE-7 → A7;
  - DE-8 → A6;
  - DE-9 → A8, spec;
  - DE-10 → A7;
  - DE-11 → A11;
  - DE-12, DE-16 → A1;
  - DE-13 → cut;
  - DE-14 → A14;
  - DE-15 → spec.
- **Stage 7, structural:**
  - ST-1, ST-3 to ST-5, ST-10 → A14;
  - ST-2 → A14 and cut;
  - ST-6, ST-7, ST-11 → cut;
  - ST-8 → A7, A13;
  - ST-9 → A5;
  - ST-12 → A12, D4.
- **Stage 7, style:**
  - SY-1 → A1;
  - SY-2 → A7;
  - SY-3 → A13;
  - SY-4 → A3;
  - SY-5 → A4;
  - SY-6 to SY-8, SY-11 to SY-15 → A14;
  - SY-9, SY-16 → A8;
  - SY-10 → A2.
- **Stage 8:** ZI-1 to ZI-15 → A1 to A14 and D1 to D5.
