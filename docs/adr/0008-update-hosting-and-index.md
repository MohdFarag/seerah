# 0008. Update hosting and the update index

**Status:** Proposed on 2026-11-13, then revised twice that day: with the owner's answers on hosting and the update check, and after an [EGM design review](../design/egm-review-0008/consolidated_review_report.md) whose recommendations the owner approved. Awaiting the owner's acceptance.

## Context

[ADR 0007](0007-content-formats-compatibility-and-signing.md) fixed what a package is: a zip with a manifest signed with Ed25519, a version per kind (`content`, `map`) that only increases, a `min_app_version` gate, and no downgrade. It left two things to this ADR: **where packages are hosted**, and **how the app learns that a new version exists**, including whether that information is signed and expires.

Constraints:

- The [PRD](../prd/PRD-v0.2.ar.md) asks that the app show that an update exists and how big it is, and that the user start the download; no automatic download, even on Wi-Fi (§7). It prefers one package for the whole Seerah and does not want internal file splitting to force several downloads. It ties the decision to no provider, server or admin panel, and leaves the timing of the check open (§7).
- The app works offline. A failed check must never affect installed content (ADR 0007).
- The [technical spike](../spike/summary.md) read a manifest from a raw GitHub link on a working branch. That proved the mechanism but is not a delivery service: it is rate-limited, tied to a branch, and unsigned.
- One publisher (the owner), signing on their own machine with offline keys (ADR 0007). No backend to run, no admin panel, no user accounts.
- Packages are public: every install downloads them. The content licence ([ADR 0006](0006-open-source-code-and-closed-content.md)) still applies to them.
- Sizes: the content package is a few hundred kilobytes now; the map package is tens of megabytes and changes rarely. Users: unknown; the app is free.
- **Cost (owner, 2026-11-13):** hosting must cost nothing until the first public release.
- **Among the hosting choices, only the index address is fixed in the app.** Packages can move whenever the index says so; the index can move only with a new app build.

## Options compared

### Where the files are served from

| Option | For | Against |
| --- | --- | --- |
| **Cloudflare Pages** (a static site at `<project>.pages.dev`) | Free; requests for static files are free and unlimited on every plan; deployed from the owner's machine with `wrangler pages deploy` (direct upload, no build) | At most 25 MiB per file, which rules out the map package; each deployment replaces the whole site; the address is tied to the Cloudflare project name |
| **GitHub Releases** in a public repository | Free; no stated limit on total size or bandwidth (each file under 2 GiB); range requests work (checked on 2026-11-13: `206 Partial Content` after a redirect to a signed, time-limited address) | Bandwidth judged "relative to similar users" under GitHub's acceptable-use policy, with suspension or throttling possible; GitHub's 2025 limits on unauthenticated traffic do not say whether release downloads are covered; the address is tied to a GitHub account and repository name |
| GitHub Pages for the index, beside GitHub Releases | One provider and one account for everything | A GitHub suspension would take down both the packages and the index needed to point apps elsewhere |
| Cloudflare R2 behind a project domain | Built to serve files to the public; no charge for egress; free tier of 10 GB stored and 10 million reads a month, then $0.015 per GB-month and $0.36 per million reads | Needs a domain whose DNS is in the same Cloudflare account (the `r2.dev` address is rate-limited and not for production) |
| Play Asset Delivery | Google's own channel for large assets | Asset packs are updated with app versions, not on their own, which the PRD rules out; Play-only, so the owner's CI test builds would not get them |
| Firebase Hosting | Static hosting | Free tier is 360 MB of transfer a day, about ten map downloads; then pay-as-you-go |
| Our own server (VPS) | Full control | Patching, TLS, monitoring and uptime fall on one person; no gain over a static file host |

**Choice (owner, 2026-11-13):** the index on Cloudflare Pages and the packages on GitHub Releases, both free, with the providers split so that losing one still leaves a way to point apps elsewhere. Decision 3 says what changes before the first public release.

### How the app learns about a new version

| Option | Used by | For | Against |
| --- | --- | --- | --- |
| **A small index file at a fixed address, fetched by the app** | F-Droid (signed repository index), [Kiwix](https://library.kiwix.org) (catalog), Organic Maps (a meta server lists download servers), Expo updates (a manifest checked at launch) | A static file; nothing to run; works with any host | One request a day per install |
| Push messages (Firebase Cloud Messaging) | Most apps with a backend | No polling | Needs Google Play services, a device token and a server to send messages |
| Query the host's API (e.g. GitHub's latest-release endpoint) | Command-line tools | Nothing to publish beyond the release | Ties the app to one provider's API and its limits (60 unauthenticated requests per hour on GitHub's REST API) |

**Choice:** the index file. Push messages need a server, a token and Google Play services for a file of a few hundred bytes; a host API ties every installed app to one provider.

### Whether the index is signed and expires

Packages are already signed, so a tampered index cannot install anything. What an unsigned index still lets a hostile host or network do: show a false version or size, point to a file that wastes the user's data before its signature fails, serve an index the device has already moved past, or hide a new version (freeze). With the index and the packages on two providers, the signature is also what ties them together.

| Option | Stops | Cost |
| --- | --- | --- |
| Unsigned index over HTTPS | Nothing beyond what TLS gives | None |
| **Signed index with an increasing serial, no expiry** | False versions and sizes, a swapped file address, an index older than one this device already accepted | Signing one more small file on the owner's machine, with the same tool and keys, at the same moment as the packages |
| Signed index that expires (TUF's *timestamp* role) | All of the above, and a host that hides updates indefinitely | Someone must re-sign before every expiry, which in practice needs a signing key online (TUF gives this role its own online key). With offline keys, a missed re-signing makes every app report that it cannot check. It also trusts the device clock, which the app cannot trust |

**Choice:** signed, with an increasing serial, without expiry: the protections that need no online key. F-Droid's client makes the same trade: it [refuses an index older than the one it has](https://gitlab.com/fdroid/fdroidclient/-/merge_requests/302), and its maintainers note this does not stop a freeze. A freeze can withhold a correction for as long as it lasts; it cannot install anything, and installed content keeps working. Revisit expiry if signing moves to a protected CI environment (ADR 0007), since that gives an online key.

### When the app checks

| Option | For | Against |
| --- | --- | --- |
| **(a) Automatically when the app comes to the foreground, at most once a day, when online** | The app can *show* that an update exists, as the PRD asks; two small requests with no identifier | Network requests the user did not ask for |
| (b) Only when the user asks | No network use unless asked | Users who never open the update screen would not learn that a correction exists |

**Choice (owner, 2026-11-13):** (a), with the limits in decision 9.

## Decision

1. **The index address is one build setting** (`updateIndexUrl`), set per build type and read only by `core:update`, the module that checks for, downloads and verifies updates ([ADR 0003](0003-v1-architecture-and-modules.md)). Nothing else in the app knows a host. Changing where packages live means publishing a new index; changing the index address means a new app build.
2. **Hosting:**
   - **Index:** the Cloudflare Pages project `seerah-updates`, at `https://seerah-updates.pages.dev/v1/<channel>/index.json`, with its signature beside it as `index.sig`.
   - **Packages:** GitHub Releases in a public repository used only for packages, one release per package (tag `content-<version>` or `map-<version>`, file named as in ADR 0007), never in the code repository, whose releases are app versions.
   - **Licence:** the owner accepts GitHub's terms (section D.5): anyone may view and fork a public repository within GitHub. The packages are release files, not part of the repository's git content, and stay under the content licence.
3. **Which builds carry which address.** Test builds and closed-beta builds carry the `pages.dev` address, which keeps serving for as long as any installed build reads it. Public builds carry the address the owner fixes before the first public release ([open decisions](../open-decisions.md)): that decision covers buying a project domain, moving the packages to R2, and whether `updateIndexUrl` becomes an ordered list (e.g. a domain first, `pages.dev` as a fallback).
4. **Index format** (checked by an index schema in `content/schema/`):

   ```text
   index.json   format, channel, serial, created, key_id,
                packages: [{ kind, version, min_app_version, size, sha256, urls: [...] }]
   index.sig    raw Ed25519 signature over the exact bytes of index.json
   ```

   - `format` is 1. It is the authority for the format's version; the `v1` in the path follows it.
   - **Evolution:** apps ignore fields and kinds they do not know. Only a change that older apps would misread raises `format` and gets a new path; older apps keep reading theirs.
   - At most one entry per kind: the newest published package of that kind.
   - `serial` increases with every published index of a channel. `channel` is inside the signed bytes, so a test index cannot pass as a stable one.
   - `urls` lists one or more addresses for the same file, tried in order. Every copy is checked against `size` and `sha256`, as Mozilla's [Remote Settings](https://firefox-source-docs.mozilla.org/services/settings/) checks its attachments, so a host or mirror can be added or changed without trusting it.
   - Same keys and `key_id` scheme as the package manifest (ADR 0007): the app accepts the current or the next public key.
5. **Checking** (`core:update`):
   - Fetch `index.json` and `index.sig` with plain requests; no cache validators, cookies or query strings, and a constant `User-Agent`, so every install sends the same requests.
   - Verify the signature over the raw bytes **before** parsing. Refuse a different `channel`, an unknown `format`, or a `serial` lower than the last one this device accepted; an equal serial is accepted.
   - An app build that drops a key also clears the stored serial floor, so an index signed with a leaked key cannot block honest indexes after the key is rotated.
   - For each kind, compare with the installed version: *up to date*, *update available*, or *needs a newer app* (when `min_app_version` is above the app's version code, ADR 0007).
   - A check counts only when it completes. After a failure (no network, an HTTP error, a bad signature, an unreadable file) the app shows "could not check now", touches nothing installed, and tries again the next time it comes to the foreground.
6. **What the user sees:** one update, with the total size of every kind that needs updating, started with one tap; behind it, the existing machinery downloads and activates each kind. If a kind needs a newer app, the user is asked to update the app (ADR 0007).
7. **Downloading** happens only when the user taps to start it (the download job in ADR 0003). The app checks the file's size and SHA-256 against the index, then verifies the package as ADR 0007 describes; the manifest's `kind` and `version` must match the index entry. An interrupted download resumes from the address in the index, never from a redirect it stored.
8. **Transport:** HTTPS only, with cleartext disabled. No certificate pinning: the signatures already protect the content, and a pin would break installed apps when a host changes the pinned key.
9. **Automatic check, never automatic download.**
   - The check runs when the app comes to the foreground, at most once a day, only when a network is available.
   - It respects the user's data preference: it is skipped on a metered network when the user has restricted the app's data (Data Saver on without an exemption for the app, or the app's background data turned off). A manufacturer's own per-app data switch makes the check fail as "could not check now".
   - The download, its size shown first, starts only when the user taps, on every network, Wi-Fi included (PRD §7).
10. **Channels:** release builds for Google Play read `stable`; the owner's test builds read `test`. A package is published to `test` first, checked on the owner's devices, then the same file is listed in the `stable` index. Do not list a package in `stable` until the app version its `min_app_version` needs has fully rolled out on Google Play.
11. **Publishing, on the owner's machine,** in this order: build and sign the packages (ADR 0007), upload them as GitHub releases, write and sign the indexes, and deploy the indexes **last**, so an index never points to a missing file. Package files are never replaced or deleted. The publish script in `tools/` keeps one invariant:
    - Every deploy contains every channel's current index, since a Cloudflare Pages deploy replaces the whole site.
    - The next serial comes from the live index. The script refuses a deploy that would lower any serial or drop any file.
    - Before deploying, it verifies the exact bytes with the app's rules (schema, signature with the keys in the app, channel, serial); after deploying, it fetches the live index and verifies it again.
    - The owner's GitHub and Cloudflare credentials stay on the owner's machine, never in git.

**Amends** [ADR 0003](0003-v1-architecture-and-modules.md), which allowed "the network only for a content update the user starts": the automatic check is now allowed too.

**Not in scope:** delta updates, several publishers, push notifications, download statistics (they belong to the open analytics decision), and the first-launch question ([open decisions](../open-decisions.md)).

## Consequences

- [engineering.md](../engineering.md) allows the automatic check beside user-started downloads; it was updated with this proposal and is reverted if the ADR is not accepted.
- An implementation specification in `docs/design/`, beside the one ADR 0007 calls for, covers the checker and the publish script: the daily rule and the device clock, the data-restriction APIs and their behaviour on the three test devices (Oppo A55, Galaxy A54, MatePad 11.5), byte limits on the index and on downloads, the exact headers, the cleartext configuration on API 26 and 27, GitHub's expiring redirects, and how the publish script rebuilds the site from the live indexes.
- New work, each with tests: the index schema; index writing and signing in the signing tool; the publish script; the checker in `core:update` (signature, serial, channel, the update states, the daily and data-restriction rules, failure handling) with tests against a local HTTP server. One test signs an index with the Python tool and verifies it with the Kotlin code, as for the manifest.
- The signing runbook (to be written, ADR 0007) gains the publishing steps and the staged-rollout rule. Its steps after a key leak must also cover package versions: a leaked key can sign a package with a huge version, which the no-downgrade rule of ADR 0007 would then protect.
- The spike's raw GitHub link (`map-data/updates/`) is retired with the spike.
- **Risks:**
  - A host or network attacker can hide new versions (freeze) but cannot install anything (accepted).
  - Apps too old for the newest package of a kind get no further content until they update the app (accepted: one entry per kind keeps the index simple).
  - GitHub may throttle or suspend release downloads; the index can then point the packages anywhere, R2 included, without an app update.
  - Once a public build ships, its index address is permanent. If it is a domain, it needs auto-renewal and a registrar lock: a lapsed domain stops updates for every install (signatures still protect users from anything it serves).
  - A leaked key can sign indexes as well as packages until an app update rotates it out; decision 5 keeps the index serial from outliving the rotation.
  - The hosts see each device's IP address and the time of each check and download; the app's privacy policy must say so.

## References

- [ADR 0007](0007-content-formats-compatibility-and-signing.md) and PRD v0.2 §7 ([snapshot](../prd/PRD-v0.2.ar.md)).
- Cloudflare Pages: [limits](https://developers.cloudflare.com/pages/platform/limits/) (25 MiB per file), [serving pages](https://developers.cloudflare.com/pages/configuration/serving-pages/).
- Cloudflare R2: [pricing](https://developers.cloudflare.com/r2/pricing/), [public buckets and custom domains](https://developers.cloudflare.com/r2/buckets/public-buckets/), [limits](https://developers.cloudflare.com/r2/platform/limits/) (`r2.dev` rate limit).
- GitHub: [about releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [acceptable use policies](https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies), [terms of service](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service) (D.5), [updated rate limits for unauthenticated requests](https://github.blog/changelog/2025-05-08-updated-rate-limits-for-unauthenticated-requests/), [REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api).
- [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery), Android Developers; [Firebase pricing](https://firebase.google.com/pricing).
- [Data Saver](https://developer.android.com/develop/connectivity/network-ops/data-saver) and [network security configuration](https://developer.android.com/privacy-and-security/security-config), Android Developers.
- [The Update Framework specification](https://theupdateframework.io/specification) (timestamp role, freeze and rollback attacks).
- F-Droid: [check repo index timestamps](https://gitlab.com/fdroid/fdroidclient/-/merge_requests/302).
- Organic Maps: [download mirrors](https://cdn.organicmaps.app/) and its [meta server](https://github.com/organicmaps/meta).
- [Kiwix library](https://library.kiwix.org).
- Mozilla: [Remote Settings](https://firefox-source-docs.mozilla.org/services/settings/).
- Expo: [`checkAutomatically`](https://docs.expo.dev/versions/latest/sdk/updates/) (a check on every launch by default).
