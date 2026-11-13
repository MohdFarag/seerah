# Stage 6: Comprehension test, ADR 0008

**Target:** `docs/adr/0008-update-hosting-and-index.md`, lines 1 to 124 (FeatureRange: whole file; DetailedRange: none), commit `229ba17`.
**Reviewer:** comprehension, completed. It read the document alone, with no standard and no other context.

**Verdict:** self-contained enough to follow; every section could be paraphrased. No Blockers, 1 Major (how `stable`, "public builds" and the `pages.dev` address relate before the first public release), 14 Nits, mostly references to project things that are never defined or linked.

## What this proposes

The ADR decides two things that ADR 0007 left open: where the content and map packages are hosted, and how the app finds out that a newer one exists.

- **Hosting until the first public release:**
  - A small signed index file sits on Cloudflare Pages at a fixed `pages.dev` address, one per channel (`stable`, `test`).
  - The packages are GitHub Releases assets in a separate public repository.
  - Before the first public release the owner decides on buying a project domain and moving to Cloudflare R2.
- **The index:**
  - It lists the newest package of each kind with its size, SHA-256 and one or more URLs.
  - It is signed with the same offline Ed25519 keys as the packages and carries a serial that only increases. The app refuses a lower serial, which stops replays. The index never expires, so a host can still hide updates (a "freeze"); the ADR accepts that risk.
- **Checking and downloading:**
  - The app checks by itself when it comes to the foreground: at most once a day, only when online, and not when the user has restricted the app's data.
  - It never downloads by itself. The user sees the size and taps to download.
- **Publishing:** upload the packages first, then sign and deploy the index last.
- **Addresses:** only the index address (`updateIndexUrl`) is built into the app. Package locations can change just by publishing a new index.

## How the existing system works, per this document

- **Packages (ADR 0007):** a package is a zip whose manifest is signed with Ed25519. Each kind (`content`, `map`) has its own version line. A `min_app_version` field gates which app versions can use it, and downgrades are refused.
- **Signing:** one publisher, the owner, signs on their own machine with offline keys. There is no backend, admin panel or user accounts. A Python signing tool exists, the Kotlin code verifies its output, and a signing runbook exists.
- **Current update source:** a spike reads a manifest from a raw GitHub link on a working branch (`map-data/updates/`). The spike is unsigned and rate-limited.
- **Other pieces referenced:**
  - Downloads run through a download job (ADR 0003).
  - A `core:update` component holds the update logic.
  - Until now, `engineering.md` allowed only downloads the user starts.
  - The app works offline, and installed content must survive any failed check.

## Findings

### 🟡 CO-1. What is on `pages.dev` before the first public release is ambiguous

Lines 71, 90, 105. Line 71: until the first public release, "only test builds carry the `pages.dev` address". Line 90: Google Play release builds read `stable`, test builds read `test`, and each package goes to `test` first, then `stable`. A fresh reader cannot tell whether a `stable` index exists at `pages.dev` before the public release and who reads it; whether a Play build on an internal or closed test track is a "public build" or a "test build"; or whether the test-then-stable promotion starts only after the domain decision. A sentence linking build type, channel and address for each phase would close the gap.

### 🟢 Nits

- **CO-2.** ADR 0003 and ADR 0006 are cited but not linked (lines 15, 88), and not in References.
- **CO-3.** `core:update` is never introduced (lines 66, 84, 104).
- **CO-4.** "The spike" is never identified or linked, and when it is retired is not said (lines 13, 108).
- **CO-5.** The three test devices are not identified, and "is confirmed" reads as either done or still to do (line 93).
- **CO-6.** Why a foreground check follows background-data settings (Data Saver, `getRestrictBackgroundStatus()`) is not explained (line 93); a reader may think it is a mistake rather than respect for the user's data preference.
- **CO-7.** "At most once a day" is undefined: 24 hours since the last attempt, since the last success, or per calendar day; and whether a failed check uses up the day's check (lines 87, 92).
- **CO-8.** "Where content updates live" names no screen (line 94).
- **CO-9.** The package file name rule is not stated; line 69 defines only the release tag (line 96 against line 69).
- **CO-10.** When an old package may be deleted is not defined (line 97).
- **CO-11.** "A few-kilobyte file" (line 40) against "a few hundred bytes" (line 59): say which is the response and which the request.
- **CO-12.** The analytics decision is not linked, and the first-launch question is linked but not described (line 99).
- **CO-13.** A few terms lean on ADR 0007 without a gloss: "one version line per kind" (line 7), "the same tool" (line 50), "the current or the next public key" (line 83).
- **CO-14.** "EGM review" is unexplained in the status line (line 3).
- **CO-15.** The PRD is not linked where §7 is first cited (line 11); Kiwix (line 39) has no reference link.
