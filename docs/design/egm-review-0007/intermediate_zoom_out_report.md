# Stage 8: Intermediate Zoom Out: ADR 0007

Target: `docs/adr/0007-content-formats-compatibility-and-signing.md` at `origin/claude/relaxed-knuth-gi0alj` (commit `a0f9c64`), unedited since Stage 5.

Reviewer: `egm-zoom-out` (completed). It read the reports from stages 5–7 and the full Goldfish context.

## Verdict

Approve with changes, but **don't apply the earlier stages wholesale**. Stages 5–7 make about 60 requests. Applied in full, they would make the ADR about three times longer and turn it into an implementation spec, a key-handling runbook and half of ADR 0008.

About ten changes are essential, and most of them fit in one sentence or one manifest field. Several of the best fixes remove mechanism rather than add it:

- drop the local rollback;
- drop the expiry promise;
- keep one compatibility gate instead of two;
- make the version an integer.

Don't split the ADR. The format, compatibility and signing decisions all meet in one place, the manifest.

## Findings

| Sev | Lines | Finding | Recommendation |
| --- | --- | --- | --- |
| 🟡 | whole | Applying the feature-spec standard to an ADR. None of ADRs 0002–0006 has a Risks or Goals section, and most sub-decisions are already justified on lines 12 and 78 or by the existing Python tooling. (D-M9, S-M1, S-M2, S-M3) | Don't add new sections. At most, add one Consequences bullet on accepted risks and one non-goals line. Downgrade these findings to Nit. |
| 🟡 | 73, 77 | Fix the rollback conflict by **removing the rollback**. A stored high-water mark, a trigger definition and a kept previous version all exist only to support a rollback that nothing needs. An atomic switch already gives crash safety, and a bad signed package is fixed by publishing N+1. Removing the rollback also removes most of the 3× peak storage. (D-M2, IC-3, CP-5, ST-N6, ZO-N2, part of D-M7) | Change line 73 to: "The previous version is deleted once the switch is committed. A faulty release is fixed by publishing a newer version." |
| 🟡 | 13, 62, 78 | The approval gate is essential, but it should stay small. (D-B1, CP-1, CP-2) | Keep: the build accepts only approved content; it runs from a clean commit; the manifest records that commit; one ordered sentence describes the flow. Cut: the tagged checkout and the build-tool version. Whether `title-approved` can ship belongs in the schema and `content/README.md`. |
| 🟡 | 86 | The freshness and expiry promise is in the wrong place. A second "freshness key" is YAGNI, and signing the index belongs in ADR 0008. A freeze attack is a small threat for a free app where the user starts every update. (D-M3, IC-1, ST-N7) | Change line 86 to: "ADR 0008 decides whether the index is signed and expires. An index problem never blocks installed content." |
| 🟡 | 74–77 | Collapse the compatibility gates rather than explaining both. `YYYY.MM.DD.N` needs comparison rules and repeats `created`. (ZO-4, IC-N3, CP-15, D-N5) | Keep only `min_app_version` (or only `format_version`). Use a single increasing integer version per kind. Older-app rule: the app keeps its current content and says "update the app". Leave older compatible packages to ADR 0008. |
| 🟡 | 60, 62, 73, 79 | The ADR contradicts existing documents, and AGENTS.md requires fixing these. (ZO-1, ZO-2, ZO-3, D-M1, D-M10, S-M4) | Map style: follow ADR 0002, or add one amendment line; **the owner decides**. Validator: grow `tools/validate_content.py` rather than writing a parallel tool, and note the one-time move of the JSON example to YAML. Preview: a debug-only folder loader, with a build check that keeps it out of release builds (simpler than a development key). |
| 🟡 | 36, 73 | A Room schema change needs one sentence in the ADR. (IC-4, D-M6) | ADR: the content database is a disposable copy, rebuilt from the kept package when the app's schema changes. Implementation spec: the atomic pointer, reopening Room and the UI's Flows, process death, and the Oppo A55 timing. |
| 🟢 | 78, 85 | Key handling: one field and one sentence. Expo uses a single key, so two key slots are already more than that precedent. (ZO-N1, IC-2, CP-4, D-M4) | Keep: `key_id`; the next key stored separately; either key accepted; the leak window recorded as an accepted risk. Cut: signed revocation. Runbook: the leaked-key procedure, passphrase or token storage, and backups. |
| 🟢 | 63–73 | Zip hardening and storage belong in the implementation spec. (D-M5, D-M7, IC-5, ZO-N4) | ADR: verify the raw bytes, then parse; the manifest is the complete file list; map files are stored uncompressed. Spec: traversal, symlinks, zip-bomb limits, free-space checks, disk-full errors. **Decline IC-5**: two transport shapes are not worth it. |
| 🟢 | 60, 66, 74–77 | The map–content link can wait. (ZO-5, D-M8, IC-N2, CP-3, S-N3) | Essential: `seerah-<kind>-<version>.zip`, and each kind has its own version and no-downgrade rule. Defer the `requires` field: v1 has one map, and `min_app_version` lets a later format add it safely. Downgrade D-M8 and ZO-5 to Nit. |
| 🟢 | n/a | Some earlier findings are rated too high or are out of scope. | ST-M1 and ST-M3 → Nit. Cut CP-10, CP-12, CP-13, CP-16 (linking the ADRs is enough) and D-N3 (speculative alternatives). D-N1: write "raw Ed25519" on line 69 and add a test that signs with Python and verifies with Kotlin; the rest belongs to implementation. D-N2: implementation. Agreed: ZO-N5, CP-6, ST-M2/ZO-N3. |

## Where each change belongs

| Destination | Contents |
| --- | --- |
| ADR 0007 (about one line each) | approval gate and flow; ADR 0002 amendment or fix; reuse of the validator; debug folder loader; rollback removed; one compatibility gate and the older-app rule; integer version per kind; kind in the file name; `key_id` and separate storage for the next key; verify-then-parse, the manifest as the complete file list, map stored uncompressed; database rebuilt on a schema change; line 86 reworded; links to ADRs 0002 and 0003; Play analogy dropped; "new entities" wording fixed |
| Implementation spec (`docs/design/`) | zip hardening, atomic pointer, Room and Flow reopen, process death, free-space checks and errors, import timing, Tink RAW keys, choice of YAML loader |
| Signing runbook | key generation, storage and backup, rotation, procedure for a leaked key |
| ADR 0008 | index signing and expiry, freeze attacks, which package an older app is offered |
| Deferred | map-requirement field, signed revocation, freshness key, CI signing |
| Cut | Risks table, Goals section, extra options tables, glossary items, tagged builds and build-tool version, D-N3 alternatives |

Expected growth: about 15–20 lines, not 150.
