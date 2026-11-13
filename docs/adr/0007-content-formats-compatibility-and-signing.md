# 0007. Content formats, compatibility and signing

**Status:** Proposed (2026-11-13). Needs the owner's approval.

## Context

[ADR 0005](0005-content-model-exploration-and-story.md) fixed the content model (an exploration layer and a story layer, written year by year as files) and [ADR 0006](0006-open-source-code-and-closed-content.md) put the content in the private `seerah-content` repository. This ADR decides how that content is **written**, how it is **packaged** for the app, how the app knows a package **fits** it, and how the app knows a package is **genuine**.

Constraints:

- The app works offline; content arrives as downloaded packages, separate from app releases (ADR 0002, ADR 0003).
- Content is small now (26 events) and grows to the whole Seerah; the base map is large and changes rarely.
- Nothing unreviewed or tampered with may reach users; a broken download must never replace working content.
- The owner writes and reviews the content; files must be pleasant to write and review in Arabic.

## Options compared

### Source format (what is written in `seerah-content`)

| Option | For | Against |
| --- | --- | --- |
| **YAML**, checked against the JSON Schema | Comments; long Arabic texts as block scalars without escaping; readable diffs; the same JSON Schema validates it | Implicit typing pitfalls, avoided by loading strictly (below) |
| JSON (as today) | Strict, no surprises | No comments; long Arabic texts become one escaped line; tiring to write and review |
| Markdown with front matter | Natural for long texts | Weak for structured data (places, dates, story steps) |

**Choice:** YAML, loaded strictly: YAML 1.2 core schema only, duplicate keys rejected, every value's type checked by the JSON Schema (so a date, a number or a boolean is never guessed).

### Package format (what the app downloads)

| Option | For | Against |
| --- | --- | --- |
| **Signed zip of normalised JSON**, imported into the app's database on install | The package does not depend on the app's database schema; easy to inspect and test; small for our size | An import step on the device |
| Prebuilt SQLite opened with Room `createFromFile` | No import step | Room checks an identity hash of the app's compiled schema, so a database built by a Python tool must reproduce Room's internal table, and every app schema change breaks older packages |
| Protobuf or FlatBuffers | Fastest to read | Extra tooling and a binary format the owner cannot inspect; not needed at this size |

**Choice:** a zip of JSON files, imported into Room on the device. This keeps Room for content, as ADR 0002 decided, without tying packages to one app build.

### Authenticity

| Option | Protects against | Cost |
| --- | --- | --- |
| SHA-256 over HTTPS only (the spike) | Corrupt downloads | Nothing against a compromised host or a swapped file |
| **Ed25519 signature over a manifest of file hashes**, public keys in the app | Corruption, a compromised host, a swapped or modified file, downgrade to an older package | One signing key to keep safe |
| The Update Framework (TUF) | All of the above plus role separation and freshness | Several roles and metadata files; heavy for one publisher |

**Choice:** a signed manifest, taking from TUF the ideas that matter for one publisher: signed metadata, file hashes, no downgrade, and a planned key rotation. Expo's update signing and Google Play's own app signing follow the same pattern of a publisher key checked on the device.

### Verifying on Android (minimum API 26)

| Option | Note |
| --- | --- |
| **Google Tink** (`tink-android`, Apache-2.0) | Maintained by Google, Ed25519 through `PublicKeyVerify`, supported from API 24 |
| Java's built-in Ed25519 | Only from Android 13 (API 33) |
| Bouncy Castle | Works everywhere, but a large general-purpose library |

**Choice:** Tink.

## Decision

1. **Two kinds of package, versioned separately.** A **content package** (facts, story, texts; small, changes often) and a **map package** (base map PMTiles: land, relief; large, changes rarely). Historical geometry (places, routes, areas) travels in the content package; fonts and the map style ship inside the app.
2. **Source:** YAML files in `seerah-content`, laid out as ADR 0005 describes (world setup, year files, texts, story), validated with the JSON Schema from this repository.
3. **Build tool** (`tools/content/` in this repository, Python): reads the sources, turns year files into one history per entity, runs every check from ADR 0005 (references, time validity, documented routes, sources, step warnings), writes normalised JSON, the manifest and the package, then signs it.
4. **Package layout:**

   ```text
   seerah-content-<version>.zip
     manifest.json   format_version, kind (content | map), content_version, min_app_version,
                     created, files: [{ path, size, sha256 }]
     manifest.sig    Ed25519 signature over the exact bytes of manifest.json
     data/…          the JSON files (or PMTiles for a map package)
   ```

5. **On the device** (in `core:content` and `core:update`): download (ADR 0003), verify the signature with the bundled public keys, check every file's size and hash, unpack into a new version folder, import into a new content database, then switch with one pointer change. Any failure discards the new version and keeps the current one. The previous version stays until the next successful start, so a bad package can be rolled back.
6. **Compatibility:**
   - `format_version`: an integer the app supports in a range; a package with a newer format is refused with "update the app", and the current content stays.
   - `min_app_version`: the lowest app version code that may install the package.
   - `content_version`: `YYYY.MM.DD.N`, always increasing; the app refuses a package older than the one installed (no downgrade).
7. **Keys:** an Ed25519 key pair for content signing. The app carries two public keys, the current one and the next, so the key can be rotated without breaking installed apps. The private key is held by the owner, off-line, with a backup; packages are signed on the owner's machine with one command (`tools/content/sign.py`). Moving signing into CI waits until the repository can protect a signing environment with a required approver (a paid plan for private repositories).
8. **First launch:** whether an initial content package ships inside the APK or is downloaded on first launch stays an open decision. The format serves both: a bundled package is verified exactly like a downloaded one.

## Consequences

- The content build tool, the signing tool and the import in `core:content` are new work, each with tests: a tampered file, a wrong key, an older version, a newer format, an interrupted install.
- New dependencies: a strict YAML loader and `cryptography` in the Python tools; Tink in the app (checked by Licensee).
- The owner keeps a signing key and its backup; losing it means rotating to the next key in an app update.
- Hosting (where packages and the update index live, and how the app learns a new version exists) is ADR 0008; the update index will carry an expiry so an attacker cannot freeze an app on old content.
- `content/schema/` gains definitions for the manifest and the new entities as the build tool is written.

## References

- [Prepopulate your Room database](https://developer.android.com/training/data-storage/room/prepopulate), Android Developers.
- [Tink for Java and Android](https://developers.google.com/tink/setup/java) and [Tink releases](https://github.com/tink-crypto/tink-java/releases).
- [The Update Framework specification](https://theupdateframework.io/specification).
- [Expo: code signing for updates](https://docs.expo.dev/eas-update/code-signing.md).
