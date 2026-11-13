# Consolidated EGM Review: ADR 0008

**Target:** `docs/adr/0008-update-hosting-and-index.md` on branch `docs/adr-0008-update-hosting`, commit `229ba17`. The ADR has not been edited: the owner decides every change.
**Mode:** consolidated report (uninterrupted), with all review rubrics.
**Date:** 2026-11-13.

## Stages run

| Stage | Reviewers | Report | Result |
| --- | --- | --- | --- |
| 5. Initial zoom out | zoom-out, inventive-catalyst | [zoom_out_report.md](zoom_out_report.md) | 0 🔴, 5 🟡, 9 🟢 |
| 6. Comprehension test | comprehension | [comprehension_report.md](comprehension_report.md) | 0 🔴, 1 🟡, 14 🟢 |
| 7. Critic review | design, structural, style | [critic_report.md](critic_report.md) | 0 🔴, 16 🟡, 35 🟢 |
| 8. Intermediate zoom out | zoom-out | [intermediate_zoom_out_report.md](intermediate_zoom_out_report.md) | Triage: about ten sentences of change; mechanics to a specification; several proposals cut |
| 9. Implementation readiness | none | none | **Not run.** The ADR has no implementation-plan section. |

Every requested reviewer completed. Reviewers were given ADR 0007, the PRD snapshot, `open-decisions.md` and `engineering.md` (Stage 8 also ADR 0006); the ADR's reference URLs were not given, since reviewers cannot open URLs. The orchestrator checked two reviewer claims against the sources:

- Cloudflare Pages: "The maximum file size for a single Cloudflare Pages site asset is 25 MiB" (Pages limits page).
- GitHub Terms of Service D.5: making a repository public allows others "to view and 'fork' your repositories" within GitHub.

## Overall verdict

**Needs revision before the owner accepts it; no blockers.** The core held up in every stage: one small signed index at one build-set address, packages that move without an app update, no expiry, no pinning, no push messages, no server, the index deployed last. The changes below are mostly about one flaw in the serial rule, one undefined boundary (which builds may carry `pages.dev`), and statements that are vaguer or stronger than the design.

## Action items

Each item lists the raw findings it merges and the orchestrator's recommendation for the owner.

### 🟡 Major

| ID | Item | From | Recommendation |
| --- | --- | --- | --- |
| A1 | A leaked key can sign an index with a huge serial; installs keep that floor after the key is rotated, so every honest index is refused. L109 is wrong for the index. The same flaw exists for package versions in ADR 0007. | DE-1, DE-8, ZO-8, ZI-2 | Keep the serial; add "an app build that drops a key clears the stored serial floor"; narrow L50's claim; correct L109. Add the same rule for package versions to the signing runbook's leak steps (not an ADR 0008 change). Don't add a built-in minimum serial or a clock bound. |
| A2 | Which builds may carry the `pages.dev` address is undefined: a closed beta through Google Play is neither clearly "test" nor "public". "Moving to R2" doesn't say what moves. | ZO-2, DE-5, CO-1, SY-3, SY-4, ZI-3 | One sentence: beta builds may carry `pages.dev`, which keeps serving until no build that reads it remains installed; public builds carry the address the owner fixes before the first public release. Say R2 concerns packages; moving the index needs an app build. |
| A3 | The index has one address and no fallback, unlike packages. | IC-1, DE-2, ZI-8 | Not in this ADR: add "one address or an ordered list (e.g. keeping `pages.dev` as a fallback)" to the domain entry in `open-decisions.md`. Drop the `next_index_urls` idea. |
| A4 | No rule for how the index format may change. | ZO-3, DE-13, ZI-4 | Add: apps ignore unknown fields and unknown kinds; only a change old apps would misread bumps the signed `format` and the path. |
| A5 | Conditional requests (ETag) save nothing on a few hundred bytes a day, add stored state, can pair a stale index with a fresh signature, and let a hostile host tag each install. | ZO-1, DE-11, DE-12, IC-3, ZI-5 | Remove them: plain GET for both files, a constant `User-Agent`. Keep "package files are never replaced". |
| A6 | A Cloudflare Pages deploy replaces the whole site, so publishing one channel can roll back or delete the other's index; a broken `stable` index fails silently for everyone. | DE-3, DE-4, ZO-8, ZI-6 | One invariant in decision 11: every deploy contains every channel's current index; the script refuses a deploy that lowers a serial or drops a file, verifies the exact bytes with the app's rules before deploying, and fetches and verifies the live index after. Mechanics to the specification. |
| A7 | The ADR shows update states per kind; the PRD prefers one package and says internal splitting must not force several downloads. | ZO-4, ZI-7 | Product question for the owner. Recommended: one update shown with the total size and one tap; states are still computed per kind. |
| A8 | Content under "all rights reserved" in a public GitHub repository: GitHub's terms let users view and fork public repositories. | DE-6, ZI-11 | Owner reads D.5 and records acceptance next to the hosting choice. The packages repository holds the zips as release files, not in git; as far as the orchestrator knows, a fork copies the git repository, not release files. Not a reason to move to R2 earlier. |
| A9 | Apps too old for the newest package get no corrections, including during a staged Play rollout. | ZO-5, DE-7, IC-5, ZI-9 | Accept and say so under accepted risks; runbook rule: don't list a package in `stable` until its `min_app_version` is fully rolled out. No per-version tiers. |
| A10 | "At most once a day" and "does not retry in a loop" are undefined: does a failed check use up the day? | SY-6, IC-4, CO-7, DE-9, ZI-12 | One policy line: a check counts only when it completes; after a failure, the next foreground tries again. Clock handling to the specification. |
| A11 | "How the app learns" has no **Choice:** line. | ST-1 | Add it, with why push messages and a host API lose. |
| A12 | The owner's decisions are not linked to a record. | ST-2, DE-15 | Link the pull request once it is open. |
| A13 | Overstated or premature claims: "the one thing an installed app cannot change" (the public keys can't either); "freezing only delays" (it can last indefinitely); "is confirmed on the three test devices" (not done yet). | SY-1, SY-2, SY-5 | Correct the wording. |
| A14 | The reason packages are not on Pages is vague. | DE-14, ZO-7, SY-7, IC-2, ZI-10 | State the 25 MiB per-file limit (checked). Add one GitHub Pages row: same provider as the packages, so one suspension would take both. Don't also list content packages on Pages. |
| A15 | No implementation specification is named, so mechanics sit in the ADR. | ZI-1 | Add a Consequences bullet naming a specification for the checker and the publish script; move the API names, header details, device checks and timestamps there. |

### 🟢 Nit

| ID | Item | From | Recommendation |
| --- | --- | --- | --- |
| A16 | The ADR amends ADR 0003 ("the network only for a content update the user starts") without saying so. | ZO-9 | Name the amendment, as ADR 0007 does for ADR 0002. |
| A17 | Missing links and references: ADR 0003, ADR 0006, `core:update`, the spike, the PRD at first mention, Kiwix; Mozilla Remote Settings referenced but unused; an unsourced payment-method claim; F-Droid claim not linked inline; the runbook doesn't exist yet. | CO-2, CO-3, CO-4, CO-15, ST-3, ST-4, ST-5, ST-6 | Fix all; delete the payment-method claim; cite Mozilla at the `urls` check or drop it. |
| A18 | Repetition and wording: the domain deferral four times; "now allows" while Proposed; "never deleted while any index may list it"; size and frequency figures that don't match; "opens" vs "foreground"; the 17 style nits. | ST-7, ST-8, ST-10, CO-10, CO-11, CO-13, style nits | Take the free fixes: state the deferral once, "packages are never deleted", one trigger term, matching figures. |
| A19 | With a first-launch download, the Data Saver skip rule could leave a fresh install empty; that fetch is user-started. | ZI-13 | Note it in the first-launch entry of `open-decisions.md`. |
| A20 | Outside the ADR: `engineering.md` L70 says signing keys are injected in CI (contradicts ADR 0007's offline signing); `engineering.md` L3 calls ADRs 0003 and 0004 Proposed; `open-decisions.md` lists ADR 0008 under "Decided" while Proposed. | DE-16, ZO (out of scope), ST (out of scope), SY (out of scope) | Fix all three in the same change. |

### Cut (recommended not to adopt)

IC-2 (content packages also on Pages), IC-3 (single envelope file, unneeded without ETags), DE-7 (per-version tiers), DE-8 (built-in minimum serial), DE-12 (solved by A5), DE-3's alternatives (a Pages project per channel, the tree under version control), `next_index_urls`, ST-9, ST-11 (diagram), CO-9, CO-12, CO-14.

## Traceability matrix

| Raw finding | Action item |
| --- | --- |
| ZO-1 | A5 |
| ZO-2 | A2 |
| ZO-3 | A4 |
| ZO-4 | A7 |
| ZO-5 | A9 |
| ZO-6 | A15 |
| ZO-7 | A14 |
| ZO-8 | A1, A6 |
| ZO-9 | A16 |
| IC-1 | A3 |
| IC-2 | A14 (cut) |
| IC-3 | A5 (cut) |
| IC-4 | A10 |
| IC-5 | A9 |
| CO-1 | A2 |
| CO-2, CO-3, CO-4, CO-15 | A17 |
| CO-5, CO-6, CO-8 | A15 |
| CO-7 | A10 |
| CO-9, CO-12, CO-14 | cut |
| CO-10, CO-11, CO-13 | A18 |
| DE-1, DE-8 | A1 |
| DE-2 | A3 |
| DE-3, DE-4 | A6 |
| DE-5 | A2 |
| DE-6 | A8 |
| DE-7 | A9 |
| DE-9 | A10 |
| DE-10 | A15 |
| DE-11, DE-12 | A5 |
| DE-13 | A4 |
| DE-14 | A14 |
| DE-15 | A12 |
| DE-16 | A20 |
| ST-1 | A11 |
| ST-2 | A12 |
| ST-3, ST-4, ST-5, ST-6 | A17 |
| ST-7, ST-8, ST-10 | A18 |
| ST-9, ST-11 | cut |
| SY-1, SY-2, SY-5 | A13 |
| SY-3, SY-4 | A2 |
| SY-6 | A10 |
| SY-7 | A14 |
| Style nits 1–17 | A18 |
| ZI-1 | A15 |
| ZI-2 | A1 |
| ZI-3 | A2 |
| ZI-4 | A4 |
| ZI-5 | A5 |
| ZI-6 | A6 |
| ZI-7 | A7 |
| ZI-8 | A3 |
| ZI-9 | A9 |
| ZI-10 | A14 |
| ZI-11 | A8 |
| ZI-12 | A10 |
| ZI-13 | A19 |
| ZI-14 | A11, A12, A13, A16, A17, A18, A20 |
