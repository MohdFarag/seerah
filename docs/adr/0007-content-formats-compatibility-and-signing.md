# 0007. Content formats, compatibility and signing

**Status:** Proposed (2026-11-13), revised the same day after an EGM review ([report](../design/egm-review-0007/consolidated_review_report.md)). Needs the owner's approval. The owner has decided the three questions the review raised: the map style ships inside the app, only `approved` items ever ship, and the review's triage stands.

## Context

[ADR 0005](0005-content-model-exploration-and-story.md) fixed the content model (an exploration layer and a story layer, written year by year as files) and [ADR 0006](0006-open-source-code-and-closed-content.md) put the content in the private `seerah-content` repository. This ADR decides how that content is **written**, how it is **packaged** for the app, how the app knows a package **fits** it, and how the app knows a package is **genuine and approved**.

Constraints:

- The app works offline; content arrives as downloaded packages, separate from app releases ([ADR 0002](0002-proposed-stack.md), [ADR 0003](0003-v1-architecture-and-modules.md)). The [spike](../spike/summary.md) proved an interruptible download with a hash check and a one-step switch.
- Content is a few hundred kilobytes now (26 draft events) and grows to the whole Seerah; the base map is tens of megabytes and changes rarely.
- Nothing unreviewed or tampered with may reach users; a broken download must never replace working content.
- The owner writes and reviews the content in Arabic, so the files must keep long Arabic texts readable in a diff.

## Options compared

### Source format (what is written in `seerah-content`)

| Option | For | Against |
| --- | --- | --- |
| **YAML**, checked against the JSON Schema | Comments; long Arabic texts as block scalars, without escaping; line-by-line diffs; the same JSON Schema validates it | Implicit typing pitfalls, avoided by loading strictly (below) |
| JSON (as today) | Strict | No comments; a long text is one line full of escapes, so a one-word change shows as a whole-line diff |
| Markdown with front matter | Natural for long texts | No structure for places, dates and story steps beyond the front matter |

**Choice:** YAML, loaded strictly: YAML 1.2 core schema only, duplicate keys rejected, every value's type checked by the JSON Schema, so a date, a number or a boolean is never inferred.

### Package format (what the app downloads)

| Option | For | Against |
| --- | --- | --- |
| **Signed zip of normalised JSON**, imported into a Room database on the device | The package does not depend on the app's database schema; readable when inspected; tested without a device | An import step on the device |
| Prebuilt SQLite opened with Room `createFromFile` | No import step | Room checks a prepackaged database against the identity hash of the app's compiled schema, stored in its `room_master_table`. A database built by a Python tool must reproduce that table, and any app schema change makes every older package unusable |
| Protobuf or FlatBuffers | No JSON parsing on the device | Binary; generated code on both sides; not justified while parsing is not a measured bottleneck |

**Choice:** a zip of JSON files, imported into Room on the device. This keeps Room for content, as ADR 0002 decided, without tying packages to one app build.

### Authenticity

| Option | Protects against | Cost |
| --- | --- | --- |
| SHA-256 over HTTPS only (the spike) | Corrupt downloads | Nothing against a compromised host or a swapped file |
| **Ed25519 signature over a manifest of file hashes**, public keys in the app | Corruption, a compromised host, a swapped or modified file, a downgrade to an older package | One signing key to keep safe |
| The Update Framework (TUF) | All of the above plus role separation and freshness | Several roles, keys and metadata files to run for a single publisher |

**Choice:** a signed manifest, taking from TUF the ideas that matter for one publisher: signed metadata, file hashes, no downgrade, and a planned key rotation. Expo's update code signing follows the same pattern of a publisher key checked on the device.

### Verifying on Android (minimum API 26)

| Option | Note |
| --- | --- |
| **Google Tink** (`tink-android`, Apache-2.0) | Maintained by Google; Ed25519 through `PublicKeyVerify`; supported from API 24 |
| Java's built-in Ed25519 | Only from Android 13 (API 33) |
| Bouncy Castle | Works on every version, but a general-purpose library far larger than one signature check needs |

**Choice:** Tink.

## Decision

1. **Two kinds of package, each versioned on its own.** A **content package** (facts, story, texts) and a **map package** (base map PMTiles). Historical geometry (places, routes, areas) travels in the content package. The **map style, icons and fonts ship inside the app**, because the code that draws the map (time states, the director's toolkit, system-drawn labels) lives in the app and changes with it; this amends ADR 0002, which had them ship with the content.
2. **Source:** YAML files in `seerah-content`, laid out as ADR 0005 describes (world setup, year files, texts, story). The synthetic example in this repository moves from JSON to YAML once, with the build tool.
3. **One tool.** The build tool grows out of `tools/validate_content.py` (the validator the Definition of Done already requires); it is not a second tool. It validates the YAML sources against the content schema, turns year files into one history per entity, runs every check from ADR 0005 (references, time validity, documented routes, sources, step warnings), writes normalised JSON, and writes the manifest, which a manifest schema checks.
4. **Only approved content ships.** The order is fixed: write, the owner approves (`review: approved`), commit, build, sign, upload. The build refuses any shipped item whose `review` is anything but `approved` (`draft`, `in-review` and `title-approved` never ship), refuses a checkout with uncommitted changes, and records the source commit in the manifest. A signature therefore covers content the owner approved at a known commit.
5. **Package layout:**

   ```text
   seerah-<kind>-<version>.zip          kind: content | map; version: increasing integer per kind
     manifest.json   kind, version, min_app_version, key_id, source_commit, created,
                     files: [{ path, size, sha256 }]
     manifest.sig    raw Ed25519 signature over the exact bytes of manifest.json
     data/…          the JSON files (content) or the PMTiles files, stored uncompressed (map)
   ```

6. **On the device** (in `core:content` and `core:update`): download (ADR 0003); verify the signature over the raw manifest bytes **before** parsing them; treat the manifest as the complete file list and extract nothing else; check every file's size and hash; unpack into a new version folder; import into a new content database; switch with one pointer change; then delete the previous version. Any failure discards the new version and keeps the current one. A bad release is fixed by publishing a newer version, never by going back.
7. **The content database is disposable.** The verified package of the installed version is kept, and the database is rebuilt from it when an app update changes the Room schema.
8. **Compatibility: one gate per package.** `min_app_version` is the lowest app version code that may install it. An older app refuses the package, keeps its current content and asks the user to update the app. Each kind's `version` only increases, and the app refuses a package that is not newer than the installed one of the same kind.
9. **Keys:** Ed25519. The manifest names its `key_id`; the app carries two public keys, the current one and the next, and accepts either, so the key can be rotated without breaking installed apps. The private keys are kept offline by the owner, the next one stored apart from the current one; packages are signed on the owner's machine. Signing in CI waits until the private repository can protect a signing environment with a required reviewer, which needs a paid GitHub plan. Generation, storage, backup, rotation and the steps after a leak go in a signing runbook.
10. **Preview while writing:** debug builds can load an unpacked content folder from the writer's computer without a signature, for the preview ADR 0005 describes. A build check proves that release builds contain no unsigned loading path.
11. **First launch:** whether an initial content package ships inside the APK or is downloaded on first launch stays [an open decision](../open-decisions.md). The format serves both: a bundled package is verified exactly like a downloaded one.

**Not in scope:** delta updates, more than one publisher, signed revocation, hosting and the update index ([ADR 0008](../roadmap.md)), the map pipeline (ADR 0009).

## Consequences

- New work, each with tests: the build tool (from the validator), the signing tool, and the verify-and-import path in `core:content`. One test signs a manifest with the Python tool and verifies it with the Kotlin code, so both sides agree on the bytes.
- New dependencies: a strict YAML loader and `cryptography` in the Python tools; Tink in the app (checked by Licensee).
- An implementation specification in `docs/design/` covers what this ADR leaves out: safe extraction (paths, links, size limits), free-space checks and the disk-full message, the atomic pointer, reopening Room and its flows after a switch, process death during import, and import timing on the Oppo A55.
- ADR 0008 decides whether the update index is signed and expires; a problem with the index never blocks content already installed.
- Accepted risks: a leaked key can sign packages until an app update rotates it out; the app has no freshness guarantee until ADR 0008 adds one.
- `content/schema/` gains the manifest schema and the new entities from ADR 0005 as the build tool is written.

## References

- [Prepopulate your Room database](https://developer.android.com/training/data-storage/room/prepopulate), Android Developers.
- [Tink for Java and Android](https://developers.google.com/tink/setup/java) and [Tink releases](https://github.com/tink-crypto/tink-java/releases).
- [The Update Framework specification](https://theupdateframework.io/specification).
- [Expo: code signing for updates](https://docs.expo.dev/eas-update/code-signing/).
