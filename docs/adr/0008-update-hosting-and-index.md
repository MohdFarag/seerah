# 0008. Update hosting and the update index

**Status:** Proposed on 2026-11-13. The owner answered the first proposal's questions on 2026-11-13 (hosting until the first public release, and an automatic check that never downloads); this version records those answers. Awaiting an EGM review and the owner's acceptance.

## Context

[ADR 0007](0007-content-formats-compatibility-and-signing.md) fixed what a package is: a zip with a manifest signed with Ed25519, one version line per kind (`content`, `map`), a `min_app_version` gate, and no downgrade. It left two things to this ADR: **where packages are hosted**, and **how the app learns that a new version exists**, including whether that information is signed and expires.

Constraints:

- The PRD asks that the app show that an update exists and how big it is, and that the user start the download; no automatic download, even on Wi-Fi (§7). It does not tie the decision to any provider, server or admin panel (§7), and it leaves the timing of the check open.
- The app works offline. A failed check must never affect installed content (ADR 0007).
- The spike read a manifest from a raw GitHub link on a working branch. That proved the mechanism but is not a delivery service: it is rate-limited, tied to a branch, and unsigned.
- One publisher (the owner), signing on their own machine with offline keys (ADR 0007). No backend to run, no admin panel, no user accounts.
- Packages are public by nature: every install downloads them. The content licence still applies to them (ADR 0006).
- Sizes: the content package is a few hundred kilobytes now; the map package is tens of megabytes and changes rarely. Users: unknown; the app is free.
- **Cost (owner, 2026-11-13):** hosting is free until the first public release. Paid hosting and a project domain are decided before that release.
- **The address of the index is the one thing an installed app cannot change by itself.** Packages can move whenever the index says so; the index can move only with an app update.

## Options compared

### Where the files are served from

| Option | For | Against |
| --- | --- | --- |
| **Cloudflare Pages** (a static site at `<project>.pages.dev`) | Free; requests for static files are free and unlimited on every plan; ETag and `If-None-Match` handled by default; deployed from the owner's machine with `wrangler pages deploy` (direct upload, no build) | Up to 20,000 files per site on the free plan; meant for websites, not large downloads; the `pages.dev` address belongs to the Cloudflare project name |
| **GitHub Releases** in a public repository | Free; no stated limit on total size or bandwidth (each file under 2 GiB); range requests work (checked on 2026-11-13: `206 Partial Content` after a redirect to a signed, time-limited address) | Bandwidth judged "relative to similar users" under GitHub's acceptable-use policy, with suspension or throttling possible; GitHub's 2025 limits on unauthenticated traffic do not say whether release downloads are covered; the address belongs to a GitHub account and repository name |
| Cloudflare R2 behind a project domain | Built to serve files to the public; no charge for egress; free tier of 10 GB stored and 10 million reads a month, then $0.015 per GB-month and $0.36 per million reads | Needs a domain whose DNS is in the same Cloudflare account (the `r2.dev` address is rate-limited and not for production); Cloudflare may ask for a payment method (reported by third parties, not stated in its docs) |
| Play Asset Delivery | Google's own channel for large assets | Asset packs are updated with app versions, not on their own, which the PRD rules out; Play-only, so the owner's CI test builds would not get them |
| Firebase Hosting | Simple static hosting | Free tier is 360 MB of transfer a day, a handful of map downloads; then pay-as-you-go |
| Our own server (VPS) | Full control | Patching, TLS, monitoring and uptime fall on one person; no gain over a static file host |

**Choice (owner, 2026-11-13):** until the first public release, the **index on Cloudflare Pages** at a `pages.dev` address and the **packages on GitHub Releases**, both free. Before the first public release the owner decides on buying a project domain and on moving to R2 ([open decisions](../open-decisions.md)). Because the index names the package addresses, packages can move from GitHub to R2 or anywhere else without an app update.

### How the app learns about a new version

| Option | Used by | Fit |
| --- | --- | --- |
| **A small index file at a fixed address, fetched by the app** | F-Droid (signed repository index), Kiwix (catalog), Organic Maps (a meta server lists download servers), Expo updates (a manifest checked at launch) | A static file; nothing to run; works with any host |
| Push messages (Firebase Cloud Messaging) | Many apps | Needs Google Play services, a device token and a server to send messages; far more than a few-kilobyte file read now and then |
| Query the host's API (e.g. GitHub's latest-release endpoint) | Small tools | Ties the app to one provider's API and its rate limits (60 unauthenticated requests per hour on GitHub's REST API) |

### Whether the index is signed and expires

Packages are already signed, so a tampered index cannot install anything. What an unsigned index still lets a hostile host or network do: show a false version or size, point to a file that wastes the user's data before its signature fails, serve an older index again (replay), or hide a new version (freeze). With the index and the packages on two different providers, the signature is also what ties them together.

| Option | Stops | Cost |
| --- | --- | --- |
| Unsigned index over HTTPS | Nothing beyond what TLS gives | None |
| **Signed index with an increasing serial, no expiry** | False versions and sizes, a swapped file address, replaying an older index (the app refuses a serial lower than the last one it accepted) | Signing one more small file on the owner's machine, with the same tool and keys, at the same moment as the packages |
| Signed index that expires (TUF's *timestamp* role) | All of the above, and a host that hides updates indefinitely | Someone must re-sign before every expiry, which in practice needs a signing key online (TUF gives this role its own online key). With offline keys, a missed re-signing makes every app report that it cannot check. It also trusts the device clock, which is often wrong on cheap phones |

**Choice:** signed, with an increasing serial, without expiry. This takes from TUF and F-Droid the protections that cost nothing extra for one publisher with offline keys. F-Droid's client makes the same trade: it refuses an index older than the one it has, and its maintainers note this does not stop a freeze. Freezing only delays a correction; it cannot install anything, and installed content keeps working. Revisit expiry if signing moves to a protected CI environment (ADR 0007), since that gives an online key.

### When the app checks

| Option | For | Against |
| --- | --- | --- |
| **(a) Automatically when the app opens, at most once a day, when online** | The app can *show* that an update exists, as the PRD asks; the request is a few hundred bytes with no identifier | A network request the user did not ask for |
| (b) Only when the user asks | No network use unless asked | Most users would never learn that a correction exists |

**Choice (owner, 2026-11-13):** (a), with the limits in decision 9.

## Decision

1. **The index address is one build setting** (`updateIndexUrl`), set per build type and read only by `core:update`. Nothing else in the app knows a host. Changing the index host means changing that setting in a new app build; changing where packages live means publishing a new index, with no app update.
2. **Hosting until the first public release:**
   - **Index:** a Cloudflare Pages project, at `https://<project>.pages.dev/v1/<channel>/index.json`, with its signature beside it as `index.sig`. `v1` is the index format's major version: a breaking change gets a new path, and older apps keep reading theirs.
   - **Packages:** GitHub Releases in a public repository used only for packages, one release per package (tag `content-<version>` or `map-<version>`), never in the code repository, whose releases are app versions.
   - The project name, the repository name and the accounts are created by the owner when the publish script is first used.
3. **Before the first public release**, the owner decides on a project domain and on moving to R2 ([open decisions](../open-decisions.md)). Public builds must ship with the index address they will keep, since every install keeps it for as long as it runs that build; until then, only test builds carry the `pages.dev` address.
4. **Index format** (checked by an index schema in `content/schema/`):

   ```text
   index.json   format: 1, channel, serial, created, key_id,
                packages: [{ kind, version, min_app_version, size, sha256, urls: [...] }]
   index.sig    raw Ed25519 signature over the exact bytes of index.json
   ```

   - At most one entry per kind: the newest published package of that kind.
   - `serial` increases with every published index of a channel. `channel` is inside the signed bytes, so a test index cannot pass as a stable one.
   - `urls` lists one or more addresses for the same file, tried in order. Every copy is checked against `size` and `sha256`, so a host or mirror can be added or changed without trusting it.
   - Same keys and `key_id` scheme as the package manifest (ADR 0007): the app accepts the current or the next public key.
5. **Checking** (`core:update`):
   - Fetch `index.json` and `index.sig` with a conditional request (`If-None-Match`; Cloudflare Pages answers `304 Not Modified` when nothing changed). Verify the signature over the raw bytes **before** parsing. Refuse a different `channel`, an unknown `format`, or a `serial` lower than the last accepted one (an equal serial is fine).
   - For each kind, compare with the installed version: *up to date*; *update available*, shown with its size; or *needs a newer app*, when `min_app_version` is above the app's version code, shown as a request to update the app (ADR 0007).
   - Any failure (no network, an HTTP error, a bad signature, an unreadable file) means "could not check now". It never touches installed content and does not retry in a loop.
6. **Downloading** happens only when the user taps to start it (ADR 0003 for the download job). The app checks the file's size and SHA-256 against the index before opening it, then verifies the package as ADR 0007 describes; the manifest's `kind` and `version` must match the index entry. GitHub redirects each download to a signed address that expires, so the app resumes an interrupted download from the address in the index, never from a stored redirect.
7. **Transport:** HTTPS only; cleartext is disabled in the network security configuration, which API 26 and 27 need explicitly. No certificate pinning: the signatures already protect the content, and a pin would break every installed app whenever a host changes its certificate. Requests carry no identifier, cookie or query string; every install sends the same request.
8. **Channels:** release builds for Google Play read `stable`; the owner's test builds read `test`. A package is published to `test` first, checked on the owner's devices, then the same file is listed in the `stable` index. The channel is fixed by `updateIndexUrl`, not a setting users can change.
9. **Automatic check, never automatic download.** The app checks for updates on its own; it never downloads one on its own. The two are separate steps, and only the first is automatic:
   - The check runs when the app comes to the foreground, at most once a day, and only when a network is available.
   - It is skipped on a metered network (mobile data) when the user has restricted the app's data: Data Saver is on and the app is not exempt, or the app's background data is turned off. `ConnectivityManager.getRestrictBackgroundStatus()` reports both; its behaviour is confirmed on the three test devices. A check blocked by a manufacturer's own per-app data switch simply fails as "could not check".
   - The result is shown quietly where content updates live; it never interrupts reading.
   - The download, its size shown first, starts only when the user taps. This is the PRD's rule (§7) and holds on every network, Wi-Fi included.
10. **Caching:** package files never change once uploaded (the version is in the name) and are never replaced. The index uses Cloudflare Pages' default headers (`max-age=0, must-revalidate` with an ETag), so every check revalidates and an unchanged index costs a `304`.
11. **Publishing, on the owner's machine,** in this order: build and sign the packages (ADR 0007), upload them as GitHub releases, write the index with the next serial, sign it, and deploy the index **last**, so an index never points to a missing file. A package is never deleted while any published index may still list it. A publish script in `tools/` does these steps with the owner's own GitHub and Cloudflare credentials, which stay on the owner's machine, never in git. The signing runbook (ADR 0007) gains the publishing steps.

**Not in scope:** delta updates, several publishers, push notifications, download statistics (they belong to the open analytics decision), and the first-launch question ([open decisions](../open-decisions.md)).

## Consequences

- [engineering.md](../engineering.md) now allows the automatic check alongside user-started downloads.
- New work, each with tests: the index schema; index writing and signing in the signing tool; the publish script; the checker in `core:update` (signature, serial, channel, compatibility states, the once-a-day and data-restriction rules, failure handling) with tests against a local HTTP server. One test signs an index with the Python tool and verifies it with the Kotlin code, as for the manifest.
- Moving the index to a project domain before the first public release is a one-line change of `updateIndexUrl`; test builds already installed keep reading the `pages.dev` address until they are updated, so the old address keeps serving until the owner's test devices have moved.
- Once a public build ships, its index address is permanent infrastructure: if it is a domain, it needs auto-renewal and a registrar lock, since a lapsed domain stops updates for every install (signatures still protect users from anything it serves).
- GitHub's acceptable-use policy and unauthenticated limits are a known risk while packages are there; the move to R2, if chosen, needs only a new index.
- The spike's raw GitHub link (`map-data/updates/`) is retired with the spike.
- Accepted risks: a host or network attacker can hide new versions (freeze) but cannot install anything; a leaked key can sign indexes as well as packages until an app update rotates it out (ADR 0007).
- The hosts see each device's IP address and the time of each check and download; the app's privacy policy must say so.

## References

- [ADR 0007](0007-content-formats-compatibility-and-signing.md) and PRD v0.2 §7 ([snapshot](../prd/PRD-v0.2.ar.md)).
- Cloudflare Pages: [limits](https://developers.cloudflare.com/pages/platform/limits/), [serving pages](https://developers.cloudflare.com/pages/configuration/serving-pages/) (default cache headers and ETags), [headers](https://developers.cloudflare.com/pages/configuration/headers/).
- Cloudflare R2: [pricing](https://developers.cloudflare.com/r2/pricing/), [public buckets and custom domains](https://developers.cloudflare.com/r2/buckets/public-buckets/), [limits](https://developers.cloudflare.com/r2/platform/limits/) (`r2.dev` rate limit).
- GitHub: [about releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [acceptable use policies](https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies), [updated rate limits for unauthenticated requests](https://github.blog/changelog/2025-05-08-updated-rate-limits-for-unauthenticated-requests/), [REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api).
- [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery), Android Developers; [Firebase pricing](https://firebase.google.com/pricing).
- [Data Saver](https://developer.android.com/develop/connectivity/network-ops/data-saver) (`getRestrictBackgroundStatus`) and [network security configuration](https://developer.android.com/privacy-and-security/security-config) (cleartext and pinning), Android Developers.
- [The Update Framework specification](https://theupdateframework.io/specification) (timestamp role, freeze and rollback attacks).
- F-Droid: [check repo index timestamps](https://gitlab.com/fdroid/fdroidclient/-/merge_requests/302) (rollback protection, and why it does not stop a freeze).
- Organic Maps: [download mirrors](https://cdn.organicmaps.app/) and its [meta server](https://github.com/organicmaps/meta).
- Mozilla: [Remote Settings](https://firefox-source-docs.mozilla.org/services/settings/) (a signed collection; attachments checked by size and SHA-256).
- Expo: [`checkAutomatically`](https://docs.expo.dev/versions/latest/sdk/updates/) (a check on every launch by default).
