# Stage 8: Intermediate zoom out, ADR 0008

**Target:** `docs/adr/0008-update-hosting-and-index.md` (125 lines, unchanged since commit `229ba17`).
**Reviewer:** zoom-out (standard: `zoom-out-standard.md`), completed. It read the Stage 5, 6 and 7 reports, ADRs 0006 and 0007, PRD v0.2 §7 and §9, `open-decisions.md` and `engineering.md`, and checked ADR 0003 and the `tools/` and `content/schema/` trees.

**Verdict:** no blockers. The core design is minimal for one publisher with offline keys: one small signed index at one build-set address, packages that move without an app update, no expiry, no pinning, no push, no server. The earlier stages produced about 70 findings; taking half of them as written would double the ADR and fill it with mechanics. The triage keeps about ten sentences of net change, sends mechanics to an implementation specification, and cuts proposals that add hosts, file layouts or stored state. Reuse check: nothing to flag (index writing goes into the planned signing tool; the index schema sits beside `content/schema/content.schema.json`; `tools/spike/make_update.py` is retired).

## Findings

### 🟡 ZI-1. Name an implementation specification, so mechanics leave the ADR

Consequences L104. ADR 0007 names a `docs/design/` implementation specification; ADR 0008 does not, so every operational point is pushed into the body. One Consequences bullet naming a specification for the `core:update` checker and the publish script takes ZO-6, ZO-8 (mechanics), CO-5, CO-7, CO-8, DE-9, DE-10, DE-11, DE-3 and DE-4 (mechanics), SY-5 and IC-4's timestamps, and shrinks decisions 7 and 9.

### 🟡 ZI-2. Fix the serial floor with one rule; the same flaw is in ADR 0007

L50, L53, L81, L85, L109. DE-1 is right and L109 is false for the index. The serial buys little (ZO-8): package versions already only increase, so a replay can only hold back an update, a partial freeze L53 already accepts; yet the serial is the root of DE-1, DE-8, ZO-8's silent "could not check" and half of DE-3. Options for the owner:

- **(A)** keep the serial and add "an app build that drops a key clears the stored serial floor"; narrow L50 to "refuses an index older than one this device already accepted"; correct L109.
- **(B)** drop serial enforcement; the freeze is accepted anyway.

Do not adopt DE-8 (a build-time minimum serial couples app builds to publishing) or DE-1's clock bound (brings back the clock trust L51 rejects). Optional: the serial is the publication time in UTC seconds, replacing `created`, which answers where the next serial comes from. **Outside the target:** a leaked key can likewise sign a content package with version `2^31-1`, and ADR 0007's no-downgrade rule then refuses every honest package even after rotation; the runbook's leak steps must cover both floors with one rule.

### 🟡 ZI-3. Define "public build" in one sentence, without pulling the domain decision forward

L71, L90, L105 (ZO-2, DE-5, CO-1, SY-3, SY-4). The only irreversible point, raised by four reviewers. Cheapest: "Play beta builds may carry the `pages.dev` address; it keeps serving until no beta build that reads it remains installed." Fix L105 to cover beta testers. Apply SY-4: R2 is about packages; moving the index needs an app build.

### 🟡 ZI-4. One sentence on how the format may evolve

L68, L72–83 (ZO-3, DE-13). "Apps ignore unknown fields and unknown kinds; only a change old apps would misread bumps the signed `format` and the path." It removes the "fixed into v1" argument behind DE-7 and covers future expiry or delta fields.

### 🟡 ZI-5. Remove conditional requests (accept ZO-1)

L26, L85, L89, L96 (index half). A `304` on a once-a-day fetch of a few hundred bytes gains nothing, adds stored ETag state, creates DE-12's mismatch and DE-11's tracking channel, contradicting L89. With it gone, IC-3's envelope solves nothing. Keep "package files are never replaced".

### 🟡 ZI-6. One publish invariant; mechanics to the specification

Decision 11, L97 (DE-3, DE-4, ZO-8). "Every deploy contains every channel's current index; the publish script refuses a deploy that would lower any serial or drop any file; it verifies the exact bytes with the app's rules before deploying, and fetches and verifies the live index afterwards." Cut DE-3's alternatives (a project per channel, the tree under version control).

### 🟡 ZI-7. Say how per-kind states are presented

L86, L95 (ZO-4). "The app computes states per kind and, following PRD §7, presents one update with the total size and one tap." Details to the UI specification.

### 🟢 ZI-8. Several index addresses (IC-1, DE-2) do not belong in this ADR

L66, L106. A list is a client-only change that can be made in any build before the first public release, and it commits one person to keeping several hosts alive. Add "one address or an ordered list (e.g. keeping `pages.dev` as a fallback)" to the domain entry in `open-decisions.md`. Drop `next_index_urls` (a leaked key could redirect apps permanently).

### 🟢 ZI-9. Accept that too-old apps get no corrections; no tiers

L80, L86 (ZO-5, DE-7, IC-5). Keep ZO-5 as an accepted-risk line; put IC-5 in the runbook (don't list in `stable` until its `min_app_version` is fully rolled out); cut DE-7's tiers.

### 🟢 ZI-10. Give the real reason packages are not on Pages

L26–33 (DE-14, ZO-7, SY-7, IC-2). The per-file size limit, once checked. Optional GitHub Pages row: same provider as the packages, so a GitHub suspension would also take the index needed to move them. Cut IC-2.

### 🟢 ZI-11. The licence question needs one sentence from the owner, not a hosting change

L15, L27, L69 (DE-6). ADR 0006 already keeps content under the content licence inside a public GitHub repository (spike data, git history). The owner reads GitHub's terms D.5 and records acceptance next to the hosting choice.

### 🟢 ZI-12. Replace "does not retry in a loop" with a policy line

L87, L92 (SY-6, IC-4, CO-7, DE-9). "A check counts only when it completes; after a failure the next foreground tries again." Clock handling to the specification; cut IC-4's minimum gap.

### 🟢 ZI-13. A first-launch download must not be blocked by decision 9 (new)

L93, L99. Under Data Saver, the skip rule would leave a fresh install with no content; that fetch is user-started. Record in the first-launch entry of `open-decisions.md`.

### 🟢 ZI-14. Cheap document fixes before acceptance

ZO-9 (name the ADR 0003 amendment), ST-1, ST-2/DE-15, SY-1, SY-2, links (CO-2/ST-4, CO-3 via ADR 0003, CO-4 to `docs/spike/summary.md`, CO-15/ST-3), ST-8, ST-5, CO-10 ("packages are never deleted"), ST-10. Outside the target, same change: DE-16 (`engineering.md` L70), `engineering.md` L3 statuses, `open-decisions.md` listing a Proposed ADR under "Decided".

## Triage

- **Keep (change the ADR before acceptance):** ZO-1, ZO-2, ZO-3, ZO-4, ZO-5, ZO-8 (L50 claim), ZO-9; DE-1 (A or B), DE-3 and DE-4 (invariant only), DE-5, DE-6 (one sentence), DE-13, DE-14, DE-15, DE-16; ST-1, ST-2, ST-3, ST-4, ST-5, ST-8, ST-10; SY-1, SY-2, SY-3, SY-4, SY-6 (as ZI-12), SY-7; CO-1, CO-2, CO-3, CO-4, CO-10, CO-15; the three fixes outside the target.
- **Move to the implementation specification or the runbook:** ZO-6, ZO-8 (mechanics), IC-4 (timestamps), IC-5 (runbook); DE-3 and DE-4 (mechanics), DE-9, DE-10, DE-11; CO-5, CO-6 (keep "respects the user's data preference"), CO-7, CO-8; SY-5.
- **Move to `open-decisions.md`:** IC-1 and DE-2; ZI-13.
- **Cut:** ZO-7 (at most one row), IC-2, IC-3; DE-7 (tiers), DE-8, DE-12; ST-9, ST-11; CO-9, CO-14. Owner's discretion: CO-11, CO-12, CO-13, ST-6, ST-7 and the 17 style nits.
