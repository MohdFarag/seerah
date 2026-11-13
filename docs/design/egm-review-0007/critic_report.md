# Stage 7: Critic Review: ADR 0007

Target: `docs/adr/0007-content-formats-compatibility-and-signing.md` at `origin/claude/relaxed-knuth-gi0alj` (commit `a0f9c64`). FeatureRange: lines 1–95.

| Reviewer | Standard | Status | Findings |
| --- | --- | --- | --- |
| `egm-design` | feature-spec-standard | completed | 1 🔴, 10 🟡, 6 🟢 |
| `egm-structural` | feature-spec-standard | completed | 0 🔴, 4 🟡, 6 🟢 |
| `egm-style` | none | completed | 0 🔴, 3 🟡, 13 🟢 |

The orchestrator checked these claims against the files:

- **B1 confirmed.** `content.schema.json` line 8 reads "Only 'approved' may ship". ADR 0007 never mentions `approved`.
- **The ADR 0002 contradiction confirmed.** ADR 0002 line 13 reads "Style and icons ship with the content". ADR 0007 line 60 says the map style ships inside the app.

## 🔴 Blocker

**D-B1: Nothing enforces that only approved content is published.** (design; lines 13, 62, 78)

- Constraint 13 says nothing unreviewed may reach users, and the schema says only `approved` may ship.
- Yet the build checks (line 62) never look at `review`, and signing (line 78) signs whatever is in the working tree.
- A signature proves who signed a package, not that its content was reviewed.

Fix:

1. The build refuses any entity it would ship that is not `approved`. Also decide whether `title-approved` can ship.
2. Packages are built only from a clean, committed (preferably tagged) checkout of `seerah-content`.
3. The manifest records the content commit and the build tool version.

## 🟡 Major

| ID | Reviewer | Lines | Finding | Fix |
| --- | --- | --- | --- | --- |
| D-M1 / S-M4 | design, structural | 60 | The map style ships in the app, contradicting accepted ADR 0002. The ADR doesn't mark this as an amendment. Icons are not mentioned. Lines 33 and 36 also sharpen ADR 0002's "SHA-256 and compatibility check". | State the amendment and the reason, update ADR 0002 in the same change, and say where icons ship. |
| D-M2 | design | 43, 73, 77 | "No downgrade" is measured against the installed version, so a local rollback lowers the bar and opens a replay window. The ADR doesn't say where the highest accepted version is stored. The rollback trigger is undefined. A bad but signed package has no recovery path. | Keep a stored "highest accepted version" (content and map) apart from the content files. Define the trigger. State that faults are fixed only by publishing a newer version. |
| D-M3 | design | 43, 46, 86 | Freshness protection is deferred to ADR 0008, but only `manifest.json` is signed. An expiry on an unsigned index protects nothing. | Decide here that the index is signed with the same keys and carries an expiry. An expired index must never block installed content. |
| D-M4 | design | 78, 85 | Rotation covers losing a key, not a key being leaked or stolen. There is no `key_id`. The next private key's storage is not stated. Apps that never update are not covered. "Offline" doesn't fit a key decrypted on a daily-use laptop. | Add `key_id`. Store the two private keys apart, protected by a passphrase or hardware token. Write a short runbook for a leaked key. Optionally support a signed revocation. |
| D-M5 | design | 63–73 | Unpacking is not hardened. Nothing rejects files not in the manifest, duplicate names, `..` or absolute paths, symbolic links, or zip bombs. Nothing requires checking the signature over the raw bytes before parsing. | Verify, then parse. Treat the manifest as the complete file list. Keep paths inside the version folder. Enforce declared sizes while extracting. |
| D-M6 | design | 32, 36, 73 | A package is free of the app's Room schema, but the local database is not. The ADR has no plan for a re-import when the schema changes. The pointer swap is not shown to be atomic. Closing and reopening Room and the UI's Flows is not covered, and neither is process death during import or switch. | Keep the verified JSON. Re-import when the schema version changes. Name the atomic pointer mechanism. Add process-death tests. |
| D-M7 | design | 60, 73 | At peak, the map package can take about three times its size on the Oppo A55: the zip, the new version and the previous version. | Check free space before download. Delete the zip after verification. Store PMTiles uncompressed in the zip. Reconsider keeping the previous map version. Define the user-facing error when the disk is full. |
| D-M8 | design | 60, 74–77 | No rule links content packages to map packages. The map package has no version field of its own, and both kinds share the file name `seerah-content-<version>.zip`. | Add `requires: { map: ">= N" }`. Give maps their own version fields. Define what the app does when they don't match. |
| D-M9 / S-M1 | design, structural | whole | There is no Risks and Mitigations section, which the standard requires. Risks are scattered across lines 13, 73 and 83–86. | Add a risk table between Decision and Consequences. |
| S-M2 | structural | 7, 79, 86 | There is no Goals and Non-goals section, so the reader has to piece the scope together. | Add a short list. Non-goals: hosting (ADR 0008), the map pipeline (ADR 0009), first-launch bundling, delta updates, more than one publisher. |
| S-M3 | structural | 60, 62, 74–78 | Several decisions have no compared alternatives: the package split, the Python build tool, the compatibility and versioning scheme, and who holds the key. | Add an H3 with a table and a **Choice:** line for each, as in ADRs 0003 and 0006. |
| D-M10 | design | 73, 79 | The preview build that loads content from the writer's computer (exploration-and-story line 222) conflicts with "every package is verified". | Debug builds only: unsigned loading, or a separate development key. A build check proves neither reaches release builds. |
| ST-M1 | style | 46 | The Play App Signing analogy is wrong: Google re-signs the APK at install, which is not a publisher key checking content. | Keep only the Expo precedent. |
| ST-M2 | style | 79 | The ADR says first launch "stays an open decision", but `open-decisions.md` has no entry for it. | Add the entry and link it. |
| ST-M3 | style | 14 | "Pleasant to write and review in Arabic" can't be tested, yet it drives the choice of YAML. | State the properties: multi-line Arabic without escaping, comments, line-level diffs. |

## 🟢 Nits

| ID | Reviewer | Lines | Finding |
| --- | --- | --- | --- |
| D-N1 | design | 52, 84 | By default, Tink signatures carry a 5-byte TINK prefix, while Python `cryptography` produces raw 64-byte Ed25519 signatures. State the key encoding and the RAW variant, and add a test that checks Python-signed packages with the Kotlin verifier. |
| D-N2 | design | 26, 84 | Name the YAML 1.2 loader. PyYAML implements YAML 1.1; ruamel.yaml supports 1.2. Say how duplicate keys are rejected. |
| D-N3 | design | 32–34, 54 | Unconsidered options: building the database with Room on the JVM, and holding the JSON in memory. "Large" Bouncy Castle has no size given. "Small for our size" has no estimate for the whole Seerah or the import time on the Oppo A55. |
| D-N4 | design | 79 | Same as ST-M2. |
| D-N5 | design | 76–77 | `YYYY.MM.DD.N` must be compared component by component as numbers. State how the manifest's `content_version` relates to the schema's `content_version`, and what the user sees on each refusal. |
| D-N6 | design | 79 | The APK signature already covers a bundled package. Say the extra check is there to keep one code path, and that a bundled package never downgrades downloaded content. |
| S-N1 | structural | 50–54 | The "Verifying on Android" table uses one "Note" column. Use For and Against, like the other tables. |
| S-N2 | structural | 11, 36, 73 | ADRs 0002 and 0003 are not linked; ADRs 0005 and 0006 are. |
| S-N3 / ST-N | structural, style | 66 | The file name doesn't fit both kinds of package. Use `seerah-<kind>-<version>.zip`. |
| S-N4 | structural | 33, 46, 53, 26, 84 | Some claims have no reference: the "identity hash" (the cited Room page doesn't mention it), Ed25519 in Java only from API 33, Play signing, YAML 1.2, Licensee. |
| S-N5 | structural | 94 | The Expo link points to the `.md` copy meant for agents. Use `https://docs.expo.dev/eas-update/code-signing/`. |
| S-N6 | structural | 3 | Optional: shorten the status line, or add an "Amends ADR 0002" note to it. |
| ST-N1 | style | 23, 32, 34, 44, 46, 54 | Replace subjective words with concrete properties: "tiring", "easy", "small for our size", "fastest", "heavy", "that matter", "large". |
| ST-N2 | style | 33 | "Room's internal table": name the exported schema and the identity hash in `room_master_table`. |
| ST-N3 | style | 52 | "Maintained by Google" is not a reason in itself. Keep it as a plain fact, or say what it implies. |
| ST-N4 | style | 78 | "Off-line" is spelled "offline" everywhere else. "On an offline device" would be more precise. |
| ST-N5 | style | 42 | "The spike" needs a link to `docs/spike/summary.md`. |
| ST-N6 | style | 73 | Name the rollback trigger (overlaps D-M2). |
| ST-N7 | style | 86 | "Cannot freeze": state it as planned for ADR 0008, not as already guaranteed. |

The style reviewer found no hype words or buzzwords. Terminology is consistent with ADRs 0002–0006, and the British spelling "normalised" matches the rest of the repository.
