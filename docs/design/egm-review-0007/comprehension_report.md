# Stage 6: Comprehension Test: ADR 0007

Target: `docs/adr/0007-content-formats-compatibility-and-signing.md` at `origin/claude/relaxed-knuth-gi0alj` (commit `a0f9c64`). FeatureRange: lines 1–95. DetailedRange: N/A. The reviewer (`egm-comprehension`) read the document alone, with no other files.

## What this proposes

The owner writes content as YAML files in the private `seerah-content` repository. The YAML is loaded strictly (YAML 1.2 core schema, duplicate keys rejected) and checked against the JSON Schema kept in the code repository. A Python build tool in `tools/content/` validates the sources, turns them into normalised JSON and packs them into a zip. The zip holds a `manifest.json` (format version, kind, content version, minimum app version, and the size and SHA-256 of each file) and an Ed25519 signature over that manifest.

There are two kinds of package, versioned separately: a content package and a large map package (PMTiles).

On the device, the app:

1. checks the signature with Google Tink against two bundled public keys (current and next);
2. checks each file's hash;
3. refuses packages that need a newer format or app, or that are older than what is installed;
4. imports the content into a new Room database;
5. switches to it with one pointer change, keeping the previous version so it can roll back.

The private key stays offline with the owner. Signing moves to CI only once a protected environment is affordable.

## How the existing system works, per this document

ADR 0005 already split content into an exploration layer and a story layer, written as year-by-year files. ADR 0006 put that content in a private repository, separate from the open-source code. Today content is written as JSON. An earlier "spike" checked downloads only with SHA-256 over HTTPS. The app works offline, gets content as downloads separate from app releases, and stores content in Room (ADR 0002, ADR 0003). There are 26 events so far. Where packages are hosted and how the app learns about updates is left to a future ADR 0008.

## Findings

The reviewer could paraphrase every section, so there are no Blockers. The gaps are concentrated in the Decision section.

| ID | Sev | Lines | Finding |
| --- | --- | --- | --- |
| CP-1 | 🟡 | 62, 78 | **Who signs, and where.** Line 62 says the build tool "writes … the package, then signs it". Line 78 makes signing a separate command, run on the owner's machine. The full flow is never written out in order: write YAML, build, review, sign, upload. |
| CP-2 | 🟡 | 13, 78 | **What enforces "nothing unreviewed".** Line 13 says nothing unreviewed may reach users, but no step is named as the one that enforces it. Signing by the owner appears to be the review gate, but the document doesn't say so. It also doesn't say what the owner reviews: the YAML diff or the built JSON. |
| CP-3 | 🟡 | 60, 67–77, 73 | **How the map package works.** The layout and rules describe only content packages: `seerah-content-<version>.zip` and `content_version`. The reader can't tell which name, version field and downgrade rule apply to a map package, whether content can require a minimum map version, or how PMTiles are "imported". |
| CP-4 | 🟡 | 78, 85 | **How key rotation works.** The app carries two public keys, but the document mentions only one private key. It doesn't say whether the next private key exists, whether the app accepts a package signed with either key, or what fills the "next" slot after a rotation. |
| CP-5 | 🟡 | 73 | **The switch and rollback are vague.** The document doesn't define what the pointer is, what counts as a "successful start", or who decides a package is bad and how. A package that starts fine but has bad content falls outside this mechanism, and the document doesn't acknowledge that. |
| CP-6 | 🟡 | 87 | **"The new entities" refers to nothing.** No entity other than the manifest is introduced anywhere in the document. |
| CP-7 | 🟡 | 11, 36, 73 | **ADR 0002 and ADR 0003 are cited without links or a summary.** "Download (ADR 0003)" tells the reader nothing about what that ADR covers, such as resumable downloads or where the file is staged. |
| CP-8 | 🟢 | 42 | "The spike" is never introduced. |
| CP-9 | 🟢 | 22, 23, 61 | It is unclear which JSON Schema is meant, and "this repository" is never named. |
| CP-10 | 🟢 | 62 | ADR 0005 terms are used without explanation: "one history per entity", "time validity", "documented routes", "step warnings". |
| CP-11 | 🟢 | 73, 83 | The roles of the `core:content` and `core:update` modules aren't stated, and neither is which one verifies packages. |
| CP-12 | 🟢 | 12, 60 | "Seerah", "events" and "PMTiles" are not explained. |
| CP-13 | 🟢 | 84 | "Licensee" is not explained or linked. |
| CP-14 | 🟢 | 78 | The platform and feature behind "a paid plan for private repositories" are not named (environments with required reviewers). |
| CP-15 | 🟢 | 75–76 | The document doesn't say why both `format_version` and `min_app_version` exist, or when each applies. |
| CP-16 | 🟢 | 86 | "Update index" is not defined. |
