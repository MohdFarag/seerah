# Consolidated EGM Review: ADR 0007

**Target:** `docs/adr/0007-content-formats-compatibility-and-signing.md` on branch `claude/relaxed-knuth-gi0alj`, commit `a0f9c64`. The review used a read-only copy, and the ADR has not been edited.
**Mode:** consolidated report (uninterrupted), with all review rubrics.
**Date:** 2026-11-13.

## Stages run

| Stage | Reviewers | Report | Result |
| --- | --- | --- | --- |
| 5. Initial zoom out | zoom-out, inventive-catalyst | [zoom_out_report.md](zoom_out_report.md) | 0 🔴, 10 🟡, 8 🟢 |
| 6. Comprehension test | comprehension | [comprehension_report.md](comprehension_report.md) | 0 🔴, 7 🟡, 9 🟢 |
| 7. Critic review | design, structural, style | [critic_report.md](critic_report.md) | 1 🔴, 17 🟡, 25 🟢 |
| 8. Intermediate zoom out | zoom-out | [intermediate_zoom_out_report.md](intermediate_zoom_out_report.md) | Triage: about 10 changes are essential, many are cut or moved |
| 9. Implementation readiness | none | none | **Not run.** The ADR has no implementation-plan section, so there was nothing for this stage to check. |

Every reviewer that was asked to run completed. The inventive-catalyst reviewer did not open `exploration-and-story.md` or `content.schema.json`. The orchestrator checked two claims directly against the files and confirmed both:

- the schema rule "Only 'approved' may ship";
- the contradiction between ADR 0007 line 60 and ADR 0002 line 13.

## Overall verdict

**Needs revision before the owner approves it.** The core choices held up across every stage:

- YAML sources, loaded strictly;
- a zip of normalised JSON, imported into a fresh Room database;
- a manifest signed with Ed25519 and checked with Tink;
- no TUF, no protobuf, no signing in CI yet.

Two pages the reviewers saved confirmed key claims: Room validates prepackaged databases, and Tink supports API 24 and later. What's wrong is in the gaps:

- an approval rule that nothing enforces;
- a contradiction with an accepted ADR;
- two rules that conflict (rollback and no-downgrade);
- a promise the ADR can't keep (expiry).

The ADR should grow by about 15–20 lines. The Stage 8 triage is the right filter: most of the 60-odd findings belong in an implementation spec, a runbook or ADR 0008, or should be cut.

## Action items

### 🔴 Blocker

| # | Action | Where |
| --- | --- | --- |
| A1 | **Enforce approval in the build.** The build refuses any shipped entity that is not `approved`, and runs only from a clean, committed `seerah-content` checkout. The manifest records that commit. Add one sentence giving the order: write, owner approves, commit, build, sign, upload. | ADR lines 13, 62, 78 |

### 🟡 Major

| # | Action | Where |
| --- | --- | --- |
| A2 | **Resolve the ADR 0002 conflict on the map style.** Either ship the style with the content, as ADR 0002 says, or amend ADR 0002 in the same change and give the reason. Say where icons ship. **This is the owner's decision.** | ADR line 60; ADR 0002 line 13 |
| A3 | **Remove the local rollback.** Delete the previous version once the switch is committed, and fix bad releases by publishing a newer version. The installed version stays the bar for refusing downgrades. | ADR line 73 |
| A4 | **Use one compatibility gate.** Keep `min_app_version` and drop `format_version`. Make each package kind's version an increasing integer. State what happens to an older app: it keeps its content and asks for an app update. | ADR lines 74–77 |
| A5 | **Reword the expiry promise.** "ADR 0008 decides whether the index is signed and expires; an index problem never blocks installed content." | ADR line 86 |
| A6 | **Reuse the existing validator.** The build tool grows out of `tools/validate_content.py` instead of being a parallel tool. Say which schema checks which artefact, and note the one-time move of the JSON example to YAML. | ADR lines 61–62, 87 |
| A7 | **Add a preview path.** A debug-only loader for an unpacked folder, with a build check that keeps it out of release builds. | ADR lines 73, 79; `exploration-and-story.md` line 222 |
| A8 | **Treat the content database as disposable.** It is rebuilt from the kept, verified package when the app's Room schema changes. | ADR lines 36, 73 |

### 🟢 Nit (each a line or less)

| # | Action | Where |
| --- | --- | --- |
| A9 | Key handling: add `key_id` to the manifest; store the next private key apart from the current one; accept either key; record the leak window as an accepted risk. | lines 78, 85 |
| A10 | Name the file `seerah-<kind>-<version>.zip`. Each kind has its own version and its own no-downgrade rule. Map files are stored uncompressed in the zip. | lines 60, 66–70 |
| A11 | Verify the signature over the raw manifest bytes, then parse. The manifest is the complete file list, and nothing outside it is extracted. Signatures are raw Ed25519. Add a test that signs with Python and verifies with Kotlin. | lines 69, 73, 83 |
| A12 | Add the first-launch question to `open-decisions.md` (merge it with the "download before use" row) and link to it. | line 79 |
| A13 | Link ADRs 0002 and 0003, and the spike summary, at first mention. | lines 11, 36, 42, 73 |
| A14 | Drop the Play App Signing analogy and keep only Expo. Use the canonical Expo URL. | lines 46, 94 |
| A15 | Replace subjective words with properties: "pleasant", "tiring", "easy", "small for our size", "fastest", "heavy", "large". Name the Room identity hash, or match the wording of the cited page. | lines 14, 23, 32–34, 44, 46, 54 |
| A16 | Small wording fixes: "the new entities" (line 87), "off-line" (line 78), drop `sign.py` (line 78), name the platform feature behind CI signing (line 78). | various |
| A17 | Optional: one Consequences bullet listing accepted risks, and one non-goals line (no delta updates, one publisher). | Consequences |

### Not for this ADR

| Destination | Items |
| --- | --- |
| Implementation spec (`docs/design/`) | zip-slip, symlink and zip-bomb limits; free-space check and disk-full error; atomic pointer mechanism; reopening Room and Flows; process-death tests; import timing on the Oppo A55; Tink RAW keyset; YAML 1.2 loader (D-M5, D-M6 details, D-M7, D-N1, D-N2) |
| Signing runbook | key generation, storage, backup, rotation; procedure for a leaked key (D-M4 details, IC-2) |
| ADR 0008 | index signing and expiry, freeze attacks, which package an older app is offered (D-M3, IC-1, ZO-4 second half) |
| Schema and `content/README.md` | whether `title-approved` items can ship (part of D-B1); **owner decision** |
| Deferred | map-requirement field (D-M8, ZO-5, IC-N2), signed revocation, freshness key, CI signing |
| Cut | Risks and Goals sections, extra options tables (D-M9, S-M1, S-M2, S-M3); glossary items (CP-10, CP-12, CP-13, CP-16); speculative alternatives (D-N3); manifest-only transport (IC-5); tagged builds and build-tool version |

## Decisions for the owner

1. **Map style (A2):** does it ship with the content (ADR 0002 as accepted) or inside the app (ADR 0007 as proposed)?
2. **`title-approved`:** can an item that has only its title approved ever ship, for example as a placeholder?
3. **Stage 8 triage:** accept the cuts, deferrals and moves listed above, or keep some of the cut items (for example the Risks section) in the ADR.

## Traceability matrix

| Action | Raw findings |
| --- | --- |
| A1 | D-B1, CP-1, CP-2 |
| A2 | ZO-2, IC-N1, D-M1, S-M4 |
| A3 | D-M2, IC-3, CP-5, ZO-N2, ST-N6, D-M7 (part), D-N6 (part) |
| A4 | ZO-4, IC-N3, CP-15, D-N5 |
| A5 | D-M3, IC-1, ST-N7 |
| A6 | ZO-1, ZO-N5 (migration), CP-6, CP-9 |
| A7 | ZO-3, D-M10 |
| A8 | IC-4, D-M6 (ADR part) |
| A9 | ZO-N1, IC-2, CP-4, D-M4 (ADR part) |
| A10 | ZO-5, ZO-N4, IC-5, IC-N2, CP-3, D-M7 (part), D-M8, S-N3 |
| A11 | D-M5 (principle), D-N1 |
| A12 | ZO-N3, ST-M2, D-N4 |
| A13 | CP-7, CP-8, S-N2, ST-N5 |
| A14 | ST-M1, S-N5 |
| A15 | ST-M3, ST-N1, ST-N2, ST-N3, S-N4, S-N1 |
| A16 | CP-6, ST-N4, ZO-N5, CP-14 |
| A17 | D-M9, S-M1, S-M2 (reduced) |
| Not for this ADR | D-M5, D-M6, D-M7, D-N1, D-N2, D-N3, D-M3, IC-1, IC-5, CP-10, CP-11, CP-12, CP-13, CP-16, S-M3, S-N6 |
