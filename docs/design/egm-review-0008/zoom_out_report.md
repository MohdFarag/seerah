# Stage 5: Initial zoom out, ADR 0008

**Target:** `docs/adr/0008-update-hosting-and-index.md` (full file, 125 lines), commit `229ba17`.
**Reviewers:** zoom-out (standard: `zoom-out-standard.md`), inventive-catalyst (same standard). Both completed.
**Context given:** ADR 0007, PRD v0.2 (§7), `open-decisions.md`, `engineering.md`. The URLs in the ADR's references were not given: reviewers cannot open URLs, and the owner asked for an uninterrupted run.

## Zoom-out reviewer

**Verdict:** no blockers; 4 Major, 5 Nit. The context is clear and the core design is minimal and argued: one small index at a fixed address set by one build setting, signed with the existing keys and tool, no expiry with the reason given, no certificate pinning, the index deployed last, push messages, host-API polling and a VPS all rejected. No production code exists that the ADR fails to reuse; the spike's downloader and `make_update.py` are throwaway and the ADR retires them.

### 🟡 ZO-1. Conditional requests are a premature optimisation and break the ADR's own privacy rule

Lines 26, 85, 89, 96. The index is a few hundred bytes fetched at most once a day, so a `304` saves almost nothing, while the app must store the last ETag with the last accepted `index.json` and `index.sig` bytes, per file, with tests. Line 45 assumes a hostile host, which can give each client its own ETag (a known tracking technique); the client echoes it back, so "every install sends the same request" (line 89) stops being true. **Suggestion:** plain GET for both files; drop the conditional-request clause, the index caching paragraph, and "ETag handled by default" as a reason for Pages. If conditional requests stay, weaken line 89.

### 🟡 ZO-2. "Public build" is not defined, and the central safeguard depends on it

Lines 18, 71, 90, 105; `open-decisions.md` (application ID before the first upload; closed beta). Decision 3 says only test builds carry the `pages.dev` address until the first public release, but decision 8 says release builds for Google Play read `stable`, and a closed beta means Play builds for people other than the owner before going public. If beta builds carry `pages.dev`, line 105's plan (keep the old address serving until the owner's devices have moved) leaves beta testers out. If they must carry the final address, the domain and R2 decision is due before the first Play upload, not the public release. **Suggestion:** define the boundary in one sentence (either "any build installed outside the owner's devices ships the permanent address", or "beta builds carry `pages.dev`, which keeps serving until the beta ends") and align `open-decisions.md`.

### 🟡 ZO-3. No rule for how the index format may change

Lines 53, 68, 72–83, 85, 99. The ADR says a breaking change gets a new path but not what is *not* breaking, nor what an app does with unknown fields or an unknown `kind`. Additions are already expected (expiry later, delta updates later, maybe a third kind); without a rule the safe reading forces `v2` and an app update for each. There are also two version markers (the `v1` path and the signed `format: 1`) without saying which is authoritative. **Suggestion:** "apps ignore unknown fields and unknown kinds; only a change old apps would misread bumps `format` and the path"; the signed `format` is the authority and the path follows it.

### 🟡 ZO-4. Per-kind update states may go against the PRD's single-download preference

Lines 86, 88, 95; PRD §7 (one package for the whole Seerah preferred) and the PRD's tech study (internal file splitting must not force several downloads on the user). The ADR describes states and sizes per kind but does not say whether the user sees one "update available (total size)" with one tap, or two updates with two taps. This is product-facing and needs the owner. **Suggestion:** state that the user sees one combined update (total size, one tap, both kinds downloaded and activated), or state per-kind presentation as a deliberate departure with the owner's sign-off.

### 🟢 ZO-5. Say that older apps get no further content until they update the app

Lines 80, 86. Once the newest package of a kind raises `min_app_version`, older apps cannot get an earlier compatible package with corrections. The simplification is right; list it under accepted consequences next to the freeze risk.

### 🟢 ZO-6. Several implementation details belong in the implementation specification

Lines 85, 88, 89, 93, 96: `getRestrictBackgroundStatus()`, "confirmed on the three test devices", the manufacturer data switch, the API 26–27 cleartext configuration, Cloudflare Pages' default headers and `304`, GitHub's expiring redirect. Keep the policies (respect the user's data restriction, HTTPS only, resume from the index address) and move mechanics to the specification ADR 0007 already sets up in `docs/design/`. Keep the redirect point as a one-line rule.

### 🟢 ZO-7. The hosting comparison misses GitHub Pages and the real reason Pages cannot hold the packages

Lines 24–31, 33. GitHub Pages (free, static, HTTPS) could serve the index from the same public packages repository as the Releases: one provider, one account, one set of credentials. The Cloudflare Pages row also omits the per-file size limit (the reviewer believes 25 MiB; to be checked), which, not the 20,000-file cap, is what rules out the map package and justifies two providers.

### 🟢 ZO-8. Where the next serial comes from; the serial's protection is overstated

Lines 50, 81, 97. If a publish goes out with a lower serial, every install quietly reports "could not check" and nothing tells the owner; the publish script should read the live index's serial and refuse anything not higher. Since ADR 0007 already makes versions only increase, a replayed index can only offer an older version (refused) or one the device never saw superseded; the serial's extra protection is narrow (a device that saw a newer index but has not installed from it). Keep it, but don't present it at line 50 as broadly stopping replay.

### 🟢 ZO-9. ADR 0003 is amended but not named

Line 103. ADR 0003 says "the network only for a content update the user starts"; decision 9 changes that. Add "amends ADR 0003 (network use)", as ADR 0007 does for ADR 0002. `engineering.md` already states the new rule while this ADR is Proposed; revert it if the ADR is not accepted.

**Out of scope, noticed:** `engineering.md` line 70 says "Signing keys stay outside git and are injected in CI", which conflicts with ADR 0007 decision 9 (offline keys, signing on the owner's machine).

## Inventive-catalyst reviewer

**Verdict:** no blocker (its REVIEW mode gives advisory findings only); 1 Major, 4 Nit. The signed index already decouples the app from package hosts, and the expiry-versus-freeze trade is argued on its merits.

### 🟡 IC-1. The index address is treated as a single point of failure, though the design can remove it

Lines 18, 66, 71, 105–106. Hosts are already untrusted (line 82), and the index is signed with keys in the app, so the same reasoning applies to the index; yet packages get a `urls` list and the index one address. **Proposed fix:** make `updateIndexUrl` an ordered list (e.g. project domain, then `pages.dev`, then a third static host); the app tries each until one returns a validly signed index with a serial at least the last accepted. A public build could then ship with `pages.dev` (no renewal risk) and a domain, and a domain lapse loses one mirror, not all updates. Optional: a signed `next_index_urls` the app saves. **Costs:** the publish script deploys to N hosts that can drift (the serial makes a stale one harmless, but the client must try the next instead of "could not check"); more requests when the first host is down. `next_index_urls` adds stored state and a real risk: a leaked key could sign a permanent redirect that survives the key rotation; the reviewer recommends the static list alone.

### 🟢 IC-2. GitHub's acceptable-use risk is handled reactively

Lines 33, 69, 82, 107. Content packages (a few hundred KB) could also be listed on Cloudflare Pages, already deployed in the same run, as a second `urls` entry at no cost. Map packages cannot, because of the Pages per-file limit (25 MiB, to be confirmed), which should be in the "Against" column. **Costs:** two uploads per content release; the never-delete rule applies to two hosts.

### 🟢 IC-3. The detached `index.json` and `index.sig` pair is not atomic over HTTP

Lines 75–77, 85. Inside a zip the manifest and its signature are atomic; as two HTTP files, a check that straddles a deploy can get new JSON with an old signature (harmless: "could not check", then retry). **Fix:** one file (the 64-byte signature followed by the JSON bytes, or a JSON envelope). **Costs:** the index is no longer plain readable JSON; a second layout for the signing tool and the verifier.

### 🟢 IC-4. Does a failed check use up the day's check?

Lines 87, 92. If failures count, a user who usually opens the app offline first may rarely be checked, defeating PRD §7. **Fix:** count only a completed check (`200` or `304`); after a failure, try again at the next foreground with a short minimum gap. **Costs:** two timestamps, a few more tests.

### 🟢 IC-5. "One entry per kind" gives older apps nothing during a staged app rollout

Lines 80, 86. If the newest package raises `min_app_version` while the matching app is still in a staged Play rollout, older installs are asked to update an app Play does not yet offer them. **Simplest fix:** a publishing rule: don't list a package in `stable` until its `min_app_version` is fully rolled out. Structural option (deferred): one entry per kind per `min_app_version` tier; the `v1` path allows adding it later.

**Examined and not false compromises:** expiry versus offline keys; the index as the layer between app and package hosts; no certificate pinning; deploying the index last.
