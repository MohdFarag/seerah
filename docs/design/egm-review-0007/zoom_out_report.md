# Stage 5: Initial Zoom Out: ADR 0007

Target: `docs/adr/0007-content-formats-compatibility-and-signing.md` at `origin/claude/relaxed-knuth-gi0alj` (commit `a0f9c64`), full file.
Reviewers: `egm-zoom-out` (completed), `egm-inventive-catalyst` (completed; it did not open `exploration-and-story.md` or `content.schema.json`).

## Verdicts

- **Zoom Out:** approve with changes. 0 Blockers, 5 Majors, 5 Nits. The base choices are lean for one publisher: a signed manifest instead of TUF, JSON imported into Room instead of a prebuilt database, and Tink. The Majors are gaps and conflicts with existing documents, not over-engineering.
- **Inventive Catalyst:** 0 Blockers, 5 Majors, 3 Nits. These are advisory, because this persona caps review findings at Major. The problems are in how the decisions combine, not in the base choices.
- Both reviewers checked two claims against the saved pages:
  - Room's `createFromFile` validates the file's schema against the app's schema.
  - Tink is fully supported from API 24, below the app's minimum of 26.

## Zoom Out findings

| ID | Sev | Lines | Finding | Suggested fix |
| --- | --- | --- | --- | --- |
| ZO-1 | 🟡 | 61, 62, 87 | The new build tool in `tools/content/` ignores the existing `tools/validate_content.py`, which the Definition of Done requires. Moving to YAML breaks that validator and the JSON example. The ADR doesn't say whether the JSON Schema validates the YAML sources or the compiled package. These are different shapes: the current schema describes a `phases.json` index, while ADR 0005 calls for year files. | Say the build tool grows out of the existing validator and update the command in AGENTS.md. Say which schema validates what. |
| ZO-2 | 🟡 | 60 | "Fonts and the map style ship inside the app" contradicts ADR 0002 line 13 ("Style and icons ship with the content") and ADR 0003 line 183. It also ties style changes to app releases. | Follow ADR 0002, or revise ADR 0002 explicitly in the same change. |
| ZO-3 | 🟡 | 73, 78, 83 | Every package must be signed and the only key is offline. Nothing covers the preview build that reloads content from the writer's computer (exploration-and-story line 222), CI tests or contributors who have no key. | Use a development key pair that only debug builds trust and release builds reject, checked by the build. Or use a loader for unpacked folders in debug builds only. |
| ZO-4 | 🟡 | 74–77, 86 | `format_version` and `min_app_version` are two gates for the same question. The ADR has no rule for an older app when the newest package needs a newer app: does it stay on its current content, or get the newest package it can install? That rule decides the shape of the index. | Keep one gate and state the rule for older apps. |
| ZO-5 | 🟡 | 60, 63–71 | Nothing links the content package to the map package. Content can widen the map bounds (ADR 0003) beyond what the installed map covers. Both kinds of package are named `seerah-content-<version>.zip`. | Add a dependency field or explain why one isn't needed. Give the map package its own file name. |
| ZO-N1 | 🟢 | 78, 85 | Rotating keys only works if the next private key is stored apart from the current one. A leaked key stays trusted until every app updates. | State where the next key is stored, and record the leak window as an accepted risk. |
| ZO-N2 | 🟢 | 73 vs 77 | "Rolled back" reads as if an older package could be reinstalled, but line 77 refuses older packages. | Clarify that a bad release is fixed by publishing a newer version. |
| ZO-N3 | 🟢 | 79 | The first-launch question is not in `open-decisions.md`, and it overlaps the existing entry on downloading before use. | Add it there or merge it with that entry. |
| ZO-N4 | 🟢 | 70, 73 | Zipping a PMTiles file and then unpacking it briefly needs twice its space on the low-end reference phone. | Store the map without compression, or keep it unzipped next to its signed manifest. |
| ZO-N5 | 🟢 | 78, 26 | Naming the script `sign.py` is below the level of detail an ADR needs. Moving to YAML needs a one-time migration of existing JSON content, which the ADR doesn't mention. | Drop the script name. Add the migration to Consequences. |

The reviewer found no YAGNI problems. Rejecting TUF, protobuf and FlatBuffers, keeping signing out of CI, and deferring expiry to ADR 0008 are all sound. Its one caution: an expired index must never block content that is already installed.

## Inventive Catalyst findings

| ID | Sev | Lines | Contradiction | Proposed resolution |
| --- | --- | --- | --- | --- |
| IC-1 | 🟡 | 78, 86 | The promised signed expiry needs frequent signing, but the only key is offline and used by hand. ADR 0008 inherits a conflict it cannot resolve without breaking one of these lines. | Add a low-power freshness key that only signs the index, or drop the promise to defend against freeze attacks. An expired index must block updates only, never installed content. |
| IC-2 | 🟡 | 78, 85 | The two-key scheme covers losing a key, not a key being leaked or stolen. A leaked key stays trusted on devices that lag behind on updates, and updates are started by the user. | Store the next key separately. Add `key_id` to the manifest. Write a playbook for a leaked key: accept the window, or support a revocation signed by the next key. |
| IC-3 | 🟡 | 73, 77 | Local rollback conflicts with "no downgrade". Nothing defines what triggers a rollback, or how the publisher recovers from a bad release. | Make rolling forward the only recovery: re-sign the good content as version N+1. Keep the highest version ever accepted separate from the active-version pointer. Name the rollback trigger. |
| IC-4 | 🟡 | 36, 73 | Packages are free of the app's Room schema, but the installed content database is not. The ADR doesn't say what happens when an app update changes that schema: migrations for data the app doesn't own, or a wipe and re-download, which breaks offline use. | Treat the database as disposable. Keep the verified package and re-import from it when the schema changes. Measure import time on the Oppo A55. |
| IC-5 | 🟡 | 60–71 | One zip container is used for both a small content package and a large map package. That doubles peak storage and rewrites the whole map on every change, yet the zip adds no trust: the manifest is what gets verified. | Make the signed manifest the package and list the files it covers. Write PMTiles straight to disk while hashing it, and keep download resume. |
| IC-N1 | 🟢 | 60 | The map style placement contradicts ADR 0002 and ADR 0003. | Same as ZO-2. |
| IC-N2 | 🟢 | 60, 74–77 | No package says which map version it requires. | Add an optional `requires` field. Activate the map first, then the content. |
| IC-N3 | 🟢 | 75–76 | `format_version` and `min_app_version` overlap. The ADR doesn't say when each is bumped or what the user sees when one fails. | One sentence per field, and the same "update the app" message for both. |

## Where the two reviewers agree

Both reviewers raised these independently:

- **Map style placement:** ZO-2, IC-N1.
- **Link between content and map packages:** ZO-5, IC-N2.
- **Overlapping compatibility gates:** ZO-4, IC-N3.
- **Rollback against no-downgrade:** ZO-N2, IC-3.
- **Next-key storage and the leak window:** ZO-N1, IC-2.
- **Doubled storage when unpacking the map:** ZO-N4, IC-5.
