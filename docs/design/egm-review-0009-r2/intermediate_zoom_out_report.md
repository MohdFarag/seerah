# Stage 8: Intermediate zoom out, ADR 0009 (second review)

**Target:** `docs/adr/0009-map-data-pipeline.md` at commit `a280cb8` (lines 1–250).

**Reviewer:** zoom-out, using `zoom-out-standard.md`; completed. It read the three stage reports, `AGENTS.md`, the requirements, the open decisions, ADRs 0002, 0003 and 0006–0008, the content-model specification, and the spike's summary, findings and relief scripts. It treated the owner's decisions as final.

**Verdict:** context sufficient; no Blockers. The stage reports hold 80 findings, and most come from a few missing rules, which the 15 items below cover.

**Checked by the reviewer:**

- No signing tool exists.
- The owner has said size is not a constraint.
- ADR 0007 line 12 also assumed "tens of MB", so SY-16 is partly wrong.
- GitHub Releases caps each file at 2 GiB, the only hard size limit in the system.
- The spike rendered style C at zoom 12 and averaged the wider zooms.
- The owner accepted place diagrams as later design work in the spike (findings line 144).

**Orchestrator note on ZI-12:** the owner later rejected separate illustrations. On 2026-11-14 they asked that important events be explained through the map itself. The spike's diagrams are therefore superseded, and ZI-12's recommendation is reversed in the consolidated report.

## Findings

### 🟡 ZI-1. Two classes of terrain edit: cleanup and reconstruction (owner decision)

Resolves ZO-4, DE-5 (first half), SY-1, DE-12 and DE-16; part of IC-5.

- **Cleanup edits** only smooth away or remove things known to be modern, with modern imagery or dated data as evidence. They never add shape. Their format is decided with the pipeline, and they may ship in the first package.
- **Reconstruction edits** add or restore historical shape, and stay deferred.
- **Both classes** need `review: approved`, and unapproved edits are listed in the build log.

Also fix decision 1: "no modern features drawn as their own layer; modern terrain shape remains wherever no edit applies". The operations in decision 2 become examples.

**Recommendation:** accept. Leave the 1256 lava flow unedited unless trial item 8 shows it can be seen in style C.

### 🟡 ZI-2. No file has missing tiles inside its extent

Resolves the risk part of DE-4, the premise of IC-2, the risk at line 237 and SY-10.

Every file covers its whole extent at each of its zooms. Empty areas are written as explicit transparent tiles, which PMTiles stores once. No design then depends on how the engine treats a missing tile, under any option.

**Cut:** IC-2's crossfade. Keep it only as a fallback for the trial.

### 🟡 ZI-3. Option (b) as four invariants, with its places derived from approved reconstruction edits (owner decision)

Resolves ZO-2, ZO-3, CO-3, CO-14, the contradiction in DE-4, the second half of DE-5, IC-7, SY-4 and part of SY-15.

1. Each spot at each zoom is shaded by exactly one relief file.
2. At the zoom where the files meet, both come from the same rendering.
3. **Smoothing floor:** detail relief is never sharper than the regional maximum zoom's smoothing, except where edits add shape. This keeps Badr's modern town and the 30 m noise out of zoom 15–16, the "soft blobs" the owner rejected in build 9.
4. The detail tiles are the tiles touched by approved reconstruction edits, computed by the build. The hand-kept list of important places goes.

**Move to the specification:** the tile split, edge softening and the fade schedule.

**Recommendation:** accept. The owner still controls the set, through approving each edit.

### 🟡 ZI-4. The trial judges (a) and (c) on edited terrain, and says who judges

Resolves ZO-1, IC-1 and SY-5.

- **A synthetic test edit** in `map-data/example/`: a bank of tens of metres, a carved bed and a flattened block. It sits at a neutral location, never at the real Badr, and never ships.
- **Every visual criterion:** "the owner judges on the three devices, side by side with build 11".

### 🟡 ZI-5. Generate the disclosure record from the edits, and amend AGENTS.md (the gate is an owner decision)

Resolves IC-6, DE-6, ST-9, part of CO-13 and the rest of CO-3.

- **Generated, not hand-written:** `map.json`'s reconstructed areas are built from the approved edits: footprints, source references and class.
- **AGENTS.md in the same change:** "…labelled, or, for reconstructed terrain, disclosed outside the map (ADR 0009)".
- **Gate (owner decision):** a map package with reconstruction edits is not listed in `stable` until an app with the disclosure is in `stable`. Play builds, closed beta included, read `stable`. **Recommendation:** accept.

The way the disclosure is shown (certainty notes, matching the view against the reconstructed areas) is passed to the later disclosure design.

### 🟡 ZI-6. One compatibility rule: an app reads every `tile_schema` ever listed in `stable`

Resolves ZO-5 and DE-8.

- **Additive only:** schema changes only add, and the app tolerates layers or files that are absent. A change that needs a second reading path needs its own ADR.
- **Delete:** line 179 ("asks the user to update the map") and the matching specification item.
- **The other direction:** a new map with an old app is covered by `min_app_version` (ADR 0007) and ADR 0008 decision 10.

### 🟡 ZI-7. One private commit, one build, and an explicit publishing order

Resolves DE-7, ZO-9, CO-5, DE-10, ZO-13, ZO-8, SY-2 and part of ST-8.

1. **One commit:** the map build reads the private checkout at one commit, because it needs `content/` to write citations. Edits reuse the content schema's source and review types.
2. **Manifest:** it records `source_commit` (the private repository), the public tool commit and the container digest. Amend ADR 0007's manifest fields and its decision 3 ("one tool").
3. **No lock hash:** drop the `sources.lock` hash from `map.json`; the private commit already pins it.
4. **Map and content out of step:** an accepted risk, with one publishing rule. A content package that places features on a reconstruction is listed in `stable` no earlier than the map that contains it.
5. **Wording:** "the signing tool from ADR 0007 (new work)", not "the existing signing tool".

**Cut:** a `requires_map` field.

### 🟡 ZI-8. Size, storage and hosting: one paragraph of consequences, no new mechanism

Resolves DE-1, DE-2, ZO-6, IC-4, SY-9, SY-16, DE-9 and CO-10.

**Keep:**

- The estimate is not an upper bound: sand seas and Yemen cost more, and the extent reaches beyond the peninsula.
- A build check that the package is under 2 GiB.
- During an update, storage of about the old map plus the zip plus the new map. ADR 0007 decision 7 (keep the verified package) applies to content only.
- Bundling would need install-time Play Asset Delivery and store the map twice; note this in the open decisions.
- Batch approvals into planned map versions.
- Amend ADRs 0007 and 0008 for the size.

**Move to the specification or trial:**

- streaming extraction;
- a margin per zoom (about half the largest screen at each zoom, so it shrinks as the zoom grows);
- trial item 1 measuring the full extent.

**Cut:** skipping downloads by hash, rebuilding only tiles near an edit, and work to reduce size.

### 🟡 ZI-9. Valleys stay optional; their mechanics go to the trial

Resolves ZO-7, IC-3 and DE-3.

**ADR change:** replace "over complete catchments" with "with an edge rule in the specification".

**To the trial or specification:**

- the choice of tool, with trial 5 recording peak memory;
- routing at about 90 m with local snapping;
- masking the sand seas.

If the lines aren't clean, the first package ships without valleys, as the owner already decided.

### 🟡 ZI-10. Keep the approved wide-zoom look

Resolves ZO-12.

"Rendered at the file's top zoom and averaged down, as approved." Rendering each zoom separately becomes a trial comparison.

### 🟡 ZI-11. Say what option (c) ships

Resolves CO-1 and DE-11.

"Whatever the trial picks, the relief is one raster source, `relief`, in `tile_schema` 1. Its encoding (painted RGBA, or one continuous darkness value coloured by the app) is fixed before the first package."

**Move to trial item 3:** measuring (c)'s size advantage (IC-8).

### 🟡 ZI-12. Place diagrams (owner decision)

Resolves ST-12.

The reviewer recommends keeping the spike's diagrams as separate later work. See the orchestrator note above: the owner has since rejected separate illustrations, so this recommendation is reversed.

### 🟢 ZI-13. Amends and factual fixes, in one pass

**Must fix:**

- "Both are downloaded": say "read at run time from the map package, whether bundled or downloaded".
- The wording about the signing tool.
- "Upper estimate".

**Amends gains:**

- AGENTS.md: the approximation wording, and its description of `map-data/`;
- ADRs 0007 and 0008: the size, and ADR 0007's manifest fields and decision 3;
- ADR 0006: amend it or edit its text, not both.

**One sentence each:** name the content licence (all rights reserved), and introduce `map-data/`.

### 🟢 ZI-14. Editorial nits worth one pass

**Keep:**

- the wording nits: CO-7, CO-8, CO-9, CO-11, CO-12, ST-3, ST-4, ST-5, ST-10, SY-8, SY-11 to SY-15;
- ST-1 and DE-14: tie the tiers row to offline first;
- the first half of ST-2: each risk points to its trial item;
- SY-6 and SY-7: cite the right sources.

**Cut:**

- ST-6 and ST-7;
- ST-11, except the FABDEM licence link;
- ST-2's security entry, because ADR 0007 covers signing.

### 🟢 ZI-15. Cut as heavier than one publisher needs

- **The private archive of inputs:** the SHA-256 pins already detect upstream changes.
- **IC-9's caching groundwork:** keep only the batching rule.
- **Hand edits for every Gulf reclamation:** modern coast far from the events is an accepted risk, and trial item 9 can compare an earlier water mask.
- **The repository rename blocking the build tool:** owner decision; recommend unblocking, since the tool reads a checkout by path.

## Triage

| Code | Meaning |
| --- | --- |
| K | Change the ADR before acceptance. |
| S | Implementation specification or trial. |
| O | Owner decision. |
| C | Cut. |

| Stage | K | S | O | C |
| --- | --- | --- | --- | --- |
| 5, zoom-out | ZO-1, ZO-3 (reduced), ZO-5, ZO-6, ZO-8, ZO-9, ZO-12, ZO-13 | ZO-7 | ZO-2, ZO-4, ZO-11 | ZO-10 |
| 5, inventive | IC-1, IC-4 (consequence), IC-6 | IC-3, IC-4 (streaming), IC-5, IC-8 | | IC-2 (crossfade), IC-7, IC-9 (caching) |
| 6 | CO-1 to CO-9, CO-11, CO-12 | CO-10, CO-13 (CI inputs), CO-14 | | CO-13 (archive) |
| 7, design | DE-1, DE-2, DE-4 (explicit tiles), DE-5 (smoothing floor), DE-6 (AGENTS.md), DE-7, DE-8, DE-10, DE-11, DE-14, DE-16 | DE-3, DE-9, DE-15 | DE-5 (classes), DE-6 (gate) | DE-2 (skip by hash, rebuild near edits), DE-7 (`requires_map`), DE-12, DE-13 |
| 7, structural | ST-1, ST-2 (pointers), ST-3, ST-4, ST-5, ST-8, ST-9, ST-10 | | ST-12 | ST-2 (security), ST-6, ST-7, ST-11 (except FABDEM) |
| 7, style | SY-1 to SY-9, SY-11 to SY-16 | | | SY-10 (resolved) |
