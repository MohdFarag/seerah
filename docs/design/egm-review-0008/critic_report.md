# Stage 7: Critic review, ADR 0008

**Target:** `docs/adr/0008-update-hosting-and-index.md`, FeatureRange lines 1 to 125, commit `229ba17`.
**Reviewers:** design (standard: `feature-spec-standard.md`), structural (same standard), style (persona rules; house style from `engineering.md`). All three completed. One round, no fixes applied: the owner asked that the ADR not be edited from the report.
**Context given:** ADR 0007, PRD v0.2, `open-decisions.md`, `engineering.md`. The design reviewer did not have ADRs 0003 and 0006 and took the ADR's claims about them at face value.

| Reviewer | 🔴 | 🟡 | 🟢 |
| --- | --- | --- | --- |
| design | 0 | 7 | 9 |
| structural | 0 | 2 | 9 |
| style | 0 | 7 | 17 |

## Design reviewer

Verdict: changes requested. Structure meets the standard; the gaps are operational failure modes and format choices that are costly to reverse but not weighed.

### 🟡 DE-1. A leaked key can poison the serial floor beyond key rotation

L53, L81, L85, L109. The app stores the highest accepted serial and refuses anything lower. A leaked key can sign an index with a huge serial (e.g. `2^63-1`); every install that sees it stores that floor, and rotating the key by app update (ADR 0007 §9) does not clear it, so honest indexes are refused from then on. L109's "until an app update rotates it out" is wrong for the index. **Recommendation:** tie the floor to `key_id` (a new key starts a new sequence), or reset or cap the floor in the build that drops a key, or bound the serial (e.g. `created` in seconds, rejecting serials implausibly far ahead). Update L109.

### 🟡 DE-2. The one address an app cannot change has no fallback

L18, L66, L71, L82, L106. `updateIndexUrl` is a single URL; a lapsed domain, a suspended Cloudflare account or a renamed project stops updates for every install. The `urls` list already applies the right pattern to packages, and the signature does not depend on the host. A multi-address option is not among the alternatives. **Recommendation:** an ordered list of index addresses (project domain, then `pages.dev`, then a raw file in the packages repository), keeping `pages.dev` alive permanently as a secondary; or record why one address was chosen.

### 🟡 DE-3. A whole-site Pages deploy can delete or roll back the other channel's index; the next serial has no source

L26, L68, L90, L97. `wrangler pages deploy` uploads a full snapshot that replaces the site, and both channels share one project. Publishing `test` from a folder with a stale or missing `stable/index.json` rolls `stable` back (lower serial: a silent freeze for every app) or deletes it (404: "could not check"). L97 does not say where the last serial comes from. **Recommendation:** the publish script fetches and verifies every channel's live index, takes the next serial from the live value, rebuilds the whole site tree from live values plus the change, and refuses to deploy if any serial would drop or any file disappear. Alternatives: one Pages project per channel; the published tree under version control.

### 🟡 DE-4. A broken stable index fails silently for every user

L87, L90, L97. Packages are tried on `test` first, but the `stable` index is a separately written and signed file no device sees before it goes live. A wrong `key_id`, a signature over different bytes, a schema mistake or a wrong `channel` turns into "could not check now" everywhere, with no telemetry. **Recommendation:** before deploy, the script verifies the exact `stable` bytes with the app's rules (schema, signature against the keys in the app, channel, serial); after deploy, it fetches the public address, verifies it, and checks every listed URL answers with the expected size; ideally the owner's device checks `stable` after each promotion. Add the failure mode to Consequences.

### 🟡 DE-5. Does the closed beta count as a "public build"?

L71, L90, L105; PRD §9 (release starts with a small closed group); `open-decisions.md`. If the beta goes out through a Play testing track before the domain is decided, those installs keep `pages.dev`. **Recommendation:** either the domain and R2 decision comes before any build reaches the beta, or commit to keeping `pages.dev` serving indefinitely (which also follows from DE-2).

### 🟡 DE-6. Closed-licence content in a public GitHub repository may grant GitHub users rights the licence does not

L15, L27, L69. GitHub's Terms of Service (section D.5, "License Grant to Other Users") say public repository content grants each GitHub user a licence to view it and copy it within GitHub (e.g. by forking). A plain file host carries no such grant. Not checked against ADR 0006 by the reviewer. **Recommendation:** owner checks against ADR 0006 and GitHub's current terms before acceptance and records the result next to the hosting choice; a real conflict argues for moving packages to R2 sooner.

### 🟡 DE-7. "One entry per kind" leaves too-old apps without compatible corrections, fixed into the v1 format

L68, L80, L86. Content v12 needs app 30; a user on app 25 with content v10 cannot see v11, only "needs a newer app". Worst when a future app drops older Android versions: users stuck on the last supported build lose every correction. Changing later needs `v2`. No alternative (e.g. newest package per `min_app_version` level) is weighed. **Recommendation:** decide explicitly now and record the trade-off.

### 🟢 Design nits

- **DE-8.** A fresh install (or cleared data) has no serial floor and accepts any old signed index; with first-launch download (still open) it could get old approved content. Cheap fix: a minimum serial built in at build time.
- **DE-9.** The once-a-day rule relies on the device clock, which L51 calls unreliable: if "now" is before the last check or more than a day after it, the check is due. Also say whether a failed check uses up the day.
- **DE-10.** The signed `size` only limits wasted data if the download stops once it exceeds it, or a too-large `Content-Length` is refused. Also cap the bytes read for `index.json` and `index.sig`, which are fetched before verification.
- **DE-11.** "No identifier" depends on headers: fix the `User-Agent` to a constant (Android's default includes device model and build); the ETag is echoed back and is safe only while the host is trusted.
- **DE-12.** Separate `index.json` and `index.sig` requests can mismatch (`304` for one, `200` for the other during a deploy): cache them as a unit, or weigh a single signed envelope.
- **DE-13.** Say whether apps ignore unknown fields within `format: 1`.
- **DE-14.** Give the Pages per-file size limit (25 MiB as far as the reviewer knows; to be checked) as the reason packages are not on Pages.
- **DE-15.** Link the record of the owner's 2026-11-13 decisions.
- **DE-16.** `engineering.md` L70 ("Signing keys … are injected in CI") contradicts offline signing in ADRs 0007 and 0008; fix in the same change.

Standard compliance: problem, goals and non-goals, architecture met (no data-flow diagram); alternatives missing for the number of index addresses, the entry model and the two-file layout; risks partly met (publishing failures and serial poisoning not covered).

## Structural reviewer

Layout matches the house ADR layout (0006, 0007); no skipped headings; fences closed; tables consistent; all four relative links resolve; every area the standard requires is present.

### 🟡 ST-1. "How the app learns about a new version" has no verdict

L35–41. The other Options sections end with **Choice:**; this one stops after the table, and its "Used by / Fit" columns put the rejection reasons for push messages and the host's API in a "Fit" cell. **Fix:** a one-line **Choice:** naming the index file and why the other two lose.

### 🟡 ST-2. Owner attributions have no linked record

L3, L17, L33, L62. The standard says a rejection must not rest on a maintainer's word without a linked issue, discussion or PR; ADR 0007's status links its review record. **Fix:** link where the owner answered (the commit or its PR).

### 🟢 Structural nits

- **ST-3.** Kiwix is cited (L39) but not in References; Mozilla Remote Settings is in References (L123) but never in the body (natural place: the `urls` size and SHA-256 check, L82).
- **ST-4.** ADR 0006 (L15) and ADR 0003 (L88) are not linked; "the spike" (L13) is not linked; the signing runbook (L97) does not exist yet and should read as planned.
- **ST-5.** "Cloudflare may ask for a payment method (reported by third parties…)" (L28) has no source.
- **ST-6.** F-Droid maintainers' note (L53) should be linked inline.
- **ST-7.** Risks are spread over five Consequences bullets, only one labelled "Accepted risks"; L107's mitigation reads as conditional on R2, while the real mitigation is that the index can point anywhere (L33, L82).
- **ST-8.** The domain and R2 deferral appears four times (L17, L33, L71, L105–106); keep Decision 3, reduce L33 to a reference.
- **ST-9.** `format: 1` in the format block mixes a value with field names.
- **ST-10.** "engineering.md now allows the automatic check" (L103) is written as in force while the ADR is Proposed, and `engineering.md` was already edited.
- **ST-11.** Optional: a Mermaid sequence diagram for publish, check and download.

## Style reviewer

Mostly dry, direct and sourced. The Majors are absolute claims, claims stated as already true, and phrases too vague to implement or test.

### 🟡 Style majors

- **SY-1.** L18 "the one thing an installed app cannot change by itself" is false as written: the public keys also change only with an app update (ADR 0007 §9; L109). Calibrate: "Among the hosting choices, only the index address is fixed in the app."
- **SY-2.** L53 "Freezing only delays a correction" contradicts L51 ("indefinitely"). Calibrate: "A freeze can withhold a correction for as long as it lasts; it cannot install anything…"
- **SY-3.** L71 implies no public build will carry `pages.dev`, while the domain is still open and L106 ("if it is a domain") allows otherwise; L105 assumes a domain. Make it conditional.
- **SY-4.** "Moving to R2" (L33, L71, L107) doesn't say what moves; `open-decisions.md` says "packages and the update index", and moving the index needs an app build. L107 should say "moving the packages to R2 needs only a new index".
- **SY-5.** L93 "is confirmed on the three test devices" reads as done; make it a requirement ("must be confirmed").
- **SY-6.** L87 "does not retry in a loop" is too vague to test: state the rule (no retry until next foreground, or counts as the day's check).
- **SY-7.** L26 "meant for websites, not large downloads": give the per-file limit (25 MiB, to be checked) as the reason packages go to GitHub.

### 🟢 Style nits

1. L3: date twice; "this version records those answers" narrates revision history; "EGM" undefined.
2. L13: "the spike" undefined.
3. L15: "by nature" is filler.
4. L17: write "hosting must cost nothing until…" (a requirement, not a provider fact).
5. L26: "20,000 files" does not bind a design with about four files per channel; drop or say so.
6. L26–27: "belongs to" → "is tied to".
7. L28: unsourced payment-method claim.
8. L30: "Simple" is a judgement; "a handful" → about 10 map downloads a day.
9. L39–41: "Many apps", "Small tools" against named projects; Kiwix unreferenced; Mozilla never in the text.
10. L40 vs L59: "few-kilobyte" vs "few hundred bytes", "now and then" vs "once a day", "the request" (one) vs two files.
11. L51: "often wrong on cheap phones" unsupported and informal; "which the app cannot trust".
12. L53: "cost nothing extra" contradicts L50's cost; "that need no online key".
13. L60: "Most users would never learn" is unmeasured; "Users who never open the update screen…".
14. L59 vs L92: "when the app opens" vs "comes to the foreground"; one term.
15. L91–95: "automatic check, never automatic download" said three times; keep the heading and PRD reference.
16. L85 and L96: the `304` fact stated twice; "an equal serial is fine" → "is accepted".
17. Smaller points: L33/L71 near-duplicate; L71 "keep … keeps"; L89 pinning overstated (a CA or backup pin survives renewal), and "same request" holds only apart from `If-None-Match`; L93 "simply" filler, and metered is not only mobile data; L94 "where content updates live" vague; L96 says "never change" twice; L105 "a one-line change" conflicts with one value per build type.

## Outside the target, raised by several reviewers

- `engineering.md` L70: "Signing keys stay outside git and are injected in CI" conflicts with ADR 0007 decision 9 (raised by zoom-out, design, structural, style).
- `engineering.md` L3 still calls ADRs 0003 and 0004 "Proposed"; both are Accepted (structural).
- `open-decisions.md` lists ADR 0008 under "Decided" while calling it Proposed (structural).
