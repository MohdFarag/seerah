# 0008. Update hosting and the update index

**Status:** Proposed on 2026-11-13. Three questions for the owner are listed under [Questions for the owner](#questions-for-the-owner).

## Context

[ADR 0007](0007-content-formats-compatibility-and-signing.md) fixed what a package is: a zip with a manifest signed with Ed25519, one version line per kind (`content`, `map`), a `min_app_version` gate, and no downgrade. It left two things to this ADR: **where packages are hosted**, and **how the app learns that a new version exists**, including whether that information is signed and expires.

Constraints:

- The PRD asks that the app show that an update exists and how big it is, and that the user start the download; no automatic download, even on Wi-Fi (§7). It does not tie the decision to any provider, server or admin panel (§7), and it leaves the timing of the check open.
- The app works offline. A failed check must never affect installed content (ADR 0007).
- The spike read a manifest from a raw GitHub link on a working branch. That proved the mechanism but is not a delivery service: it is rate-limited, tied to a branch, and unsigned.
- One publisher (the owner), signing on their own machine with offline keys (ADR 0007). No backend to run, no admin panel, no user accounts.
- Packages are public by nature: every install downloads them. The content licence still applies to them (ADR 0006).
- Sizes: the content package is a few hundred kilobytes now; the map package is tens of megabytes and changes rarely. Users: unknown; the app is free.
- **The address of the index is the one thing every installed app keeps forever.** Packages can move as long as the index says where they are; the index itself cannot move without an app update.

## Options compared

### Where the files are served from

| Option | For | Against |
| --- | --- | --- |
| **Cloudflare R2 bucket behind a project domain** | Built to serve files to the public; no charge for egress; free tier of 10 GB stored and 10 million reads a month, then $0.015 per GB-month and $0.36 per million reads; Cloudflare's cache in front with per-file cache headers; plain HTTPS with range requests; index and packages in one place | A new account; the domain's DNS must be in the same Cloudflare account; Cloudflare may ask for a payment method before the first bucket (reported by third parties, not stated in its docs); the `r2.dev` address is rate-limited and not for production, so a custom domain is required |
| **GitHub Releases in a public `seerah-packages` repository, the index on GitHub Pages behind a project domain** | No new account; free; no stated limit on total size or bandwidth for releases (each file under 2 GiB); range requests work (checked on 2026-11-13: `206 Partial Content` after a redirect to a signed, time-limited address) | Bandwidth judged "relative to similar users" under GitHub's acceptable-use policy, with suspension or throttling possible; GitHub's 2025 limits on unauthenticated traffic do not say whether release downloads are covered; two services to publish to (a release, then a Pages commit); Pages sets its own cache headers |
| GitHub Releases alone, at `github.com/…/releases/latest/download/index.json` | Simplest; no domain | The permanent address belongs to a GitHub account and repository name; moving away later needs an app update and leaves older installs without updates |
| Play Asset Delivery | Google's own channel for large assets | Asset packs are updated with app versions, not on their own, which the PRD rules out; Play-only, so the owner's CI test builds would not get them |
| Firebase Hosting | Simple static hosting | Free tier is 360 MB of transfer a day, a handful of map downloads; then pay-as-you-go on the Blaze plan; a Google project for static files |
| Our own server (VPS) | Full control | Patching, TLS, monitoring and uptime fall on one person; no gain over a static file host |

**Recommendation:** Cloudflare R2 behind a project domain. It is made for this job, its limits and prices are documented, and index and packages live in one place. The GitHub option is a sound fallback if the owner prefers no new account. Either way the app knows only the project domain, so the host can be changed later without an app update.

### How the app learns about a new version

| Option | Used by | Fit |
| --- | --- | --- |
| **A small index file at a fixed address, fetched by the app** | F-Droid (signed repository index), Kiwix (catalog), Organic Maps (a meta server lists download servers), Expo updates (a manifest checked at launch) | A static file; nothing to run; works with any host |
| Push messages (Firebase Cloud Messaging) | Many apps | Needs Google Play services, a device token and a server to send messages; far more than a few-kilobyte file read now and then |
| Query the host's API (e.g. GitHub's latest-release endpoint) | Small tools | Ties the app to one provider's API and its rate limits (60 unauthenticated requests per hour on GitHub's REST API) |

### Whether the index is signed and expires

Packages are already signed, so a tampered index cannot install anything. What an unsigned index still lets a hostile host or network do: show a false version or size, point to a file that wastes the user's data before its signature fails, serve an older index again (replay), or hide a new version (freeze).

| Option | Stops | Cost |
| --- | --- | --- |
| Unsigned index over HTTPS | Nothing beyond what TLS gives | None |
| **Signed index with an increasing serial, no expiry** | False versions and sizes, a swapped file address, replaying an older index (the app refuses a serial lower than the last one it accepted) | Signing one more small file on the owner's machine, with the same tool and keys, at the same moment as the packages |
| Signed index that expires (TUF's *timestamp* role) | All of the above, and a host that hides updates indefinitely | Someone must re-sign before every expiry, which in practice needs a signing key online (TUF gives this role its own online key). With offline keys, a missed re-signing makes every app report that it cannot check. It also trusts the device clock, which is often wrong on cheap phones |

**Recommendation:** signed, with an increasing serial, without expiry. This takes from TUF and F-Droid the protections that cost nothing extra for one publisher with offline keys. F-Droid's client makes the same trade: it refuses an index older than the one it has, and its maintainers note this does not stop a freeze. Freezing only delays a correction; it cannot install anything, and installed content keeps working. Revisit expiry if signing moves to a protected CI environment (ADR 0007), since that gives an online key.

## Decision

1. **One fixed address per channel, on a domain the project owns:** `https://updates.<domain>/v1/<channel>/index.json`, with its signature beside it as `index.sig`. The domain is the owner's choice (question 2). `v1` is the index format's major version: a breaking change gets a new path, and older apps keep reading theirs.
2. **Host:** Cloudflare R2 behind that domain, if the owner agrees (question 1). Packages and indexes are plain files; nothing runs on the server.
3. **Layout on the host:**

   ```text
   v1/
     stable/index.json, stable/index.sig
     test/index.json,   test/index.sig
     packages/seerah-content-<version>.zip
     packages/seerah-map-<version>.zip
   ```

4. **Index format** (checked by an index schema in `content/schema/`):

   ```text
   index.json   format: 1, channel, serial, created, key_id,
                packages: [{ kind, version, min_app_version, size, sha256, urls: [...] }]
   index.sig    raw Ed25519 signature over the exact bytes of index.json
   ```

   - At most one entry per kind: the newest published package of that kind.
   - `serial` increases with every published index of a channel. `channel` is inside the signed bytes, so a test index cannot pass as a stable one.
   - `urls` lists one or more addresses for the same file, tried in order. Every copy is checked against `size` and `sha256`, so a mirror can be added later without trusting it.
   - Same keys and `key_id` scheme as the package manifest (ADR 0007): the app accepts the current or the next public key.
5. **In the app** (`core:update`):
   - Fetch `index.json` and `index.sig` with a conditional request (`If-None-Match`). Verify the signature over the raw bytes **before** parsing. Refuse a different `channel`, an unknown `format`, or a `serial` lower than the last accepted one (an equal serial is fine).
   - For each kind, compare with the installed version: *up to date*; *update available*, shown with its size, the download started by the user; or *needs a newer app*, when `min_app_version` is above the app's version code, shown as a request to update the app (ADR 0007).
   - On download, check the file's size and SHA-256 against the index before opening it, then verify the package as ADR 0007 describes. The manifest's `kind` and `version` must match the index entry.
   - Any failure (no network, an HTTP error, a bad signature, an unreadable file) means "could not check now". It never touches installed content and does not retry in a loop.
   - HTTPS only: cleartext is disabled in the network security configuration, which API 26 and 27 need explicitly. No certificate pinning: the signatures already protect the content, and a pin would break every installed app whenever the CDN changes its certificate. Requests carry no identifier, cookie or query string; every install sends the same request.
6. **Channels:** release builds for Google Play read `stable`. The owner's test builds read `test`. A package is published to `test` first, checked on the owner's devices, then the same file is listed in the `stable` index. The channel is a build-time value, not a setting users can change.
7. **Caching:** package files never change once uploaded (the version is in the name) and are served as immutable with a long cache life. Indexes are served with a cache life of a few minutes, so a publication reaches users quickly and a conditional request usually costs a few hundred bytes.
8. **Publishing, on the owner's machine,** in this order: build and sign the packages (ADR 0007), upload them, write the index with the next serial, sign it, and upload the index **last**, so an index never points to a missing file. Uploaded packages are never overwritten or deleted while any published index may still list them. A publish script in `tools/` does these steps; the upload key is scoped to the bucket and kept on the owner's machine, never in git. The signing runbook (ADR 0007) gains the publishing steps.
9. **When the app checks** is the owner's decision (question 3). The format above serves either answer.

**Not in scope:** delta updates, several publishers, push notifications, download statistics (they belong to the open analytics decision), and the first-launch question ([open decisions](../open-decisions.md)).

## Questions for the owner

1. **Host:** Cloudflare R2 behind a project domain (recommended), or GitHub Releases plus GitHub Pages behind a project domain (no new account)?
2. **Domain:** which domain serves `updates.<domain>`? It does not have to be the final English name (still an open decision); a neutral domain works. It must be renewed automatically for as long as any install exists.
3. **When to check:**
   - **(a) Automatically when the app opens, at most once a day, only when online** (recommended). The PRD asks the app to *show* that an update exists, which needs a check the user did not ask for. The request is a few hundred bytes with no identifier, and nothing is downloaded until the user taps.
   - (b) Only when the user opens the content-update screen or taps "check". No network use at all unless the user asks, but most users would never learn that a correction exists.

## Consequences

- The project domain becomes permanent infrastructure: a lapsed domain stops all updates for every install, and someone else could register it. Signatures still protect users from anything it serves, but auto-renewal and a registrar lock are required.
- New work, each with tests: the index schema; index writing and signing in the signing tool; the publish script; the checker in `core:update` (signature, serial, channel, compatibility states, failure handling) with tests against a local HTTP server. One test signs an index with the Python tool and verifies it with the Kotlin code, as for the manifest.
- If the owner chooses (a), [engineering.md](../engineering.md) changes in the same change that accepts this ADR: today it says the network is used only for a download the user asked for.
- The spike's raw GitHub link (`map-data/updates/`) is retired with the spike.
- Accepted risks: a host or network attacker can hide new versions (freeze) but cannot install anything; a leaked key can sign indexes as well as packages until an app update rotates it out (ADR 0007).
- The host sees each device's IP address and the time of each check, as any download does; the app's privacy policy must say so.

## References

- [ADR 0007](0007-content-formats-compatibility-and-signing.md) and PRD v0.2 §7 ([snapshot](../prd/PRD-v0.2.ar.md)).
- Cloudflare R2: [pricing](https://developers.cloudflare.com/r2/pricing/), [public buckets and custom domains](https://developers.cloudflare.com/r2/buckets/public-buckets/), [limits](https://developers.cloudflare.com/r2/platform/limits/) (`r2.dev` rate limit).
- GitHub: [about releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [acceptable use policies](https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies), [GitHub Pages limits](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits), [updated rate limits for unauthenticated requests](https://github.blog/changelog/2025-05-08-updated-rate-limits-for-unauthenticated-requests/), [REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api).
- [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery), Android Developers; [Firebase pricing](https://firebase.google.com/pricing).
- [The Update Framework specification](https://theupdateframework.io/specification) (timestamp role, freeze and rollback attacks).
- F-Droid: [check repo index timestamps](https://gitlab.com/fdroid/fdroidclient/-/merge_requests/302) (rollback protection, and why it does not stop a freeze).
- Organic Maps: [download mirrors](https://cdn.organicmaps.app/) and its [meta server](https://github.com/organicmaps/meta).
- Mozilla: [Remote Settings](https://firefox-source-docs.mozilla.org/services/settings/) (a signed collection; attachments checked by size and SHA-256).
- Expo: [`checkAutomatically`](https://docs.expo.dev/versions/latest/sdk/updates/) (a check on every launch by default).
- [Network security configuration](https://developer.android.com/privacy-and-security/security-config), Android Developers (cleartext and pinning).
