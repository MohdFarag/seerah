# 0006. Open-source code, closed content

**Status:** Accepted by the owner on 2026-11-13.

## Context

The repository is public and was entirely proprietary. The owner wants the **code** to be open source and the **content** not to be. The content (the selection and editing of texts, explanations, event, place and route data, story scripts, historical map data) is the project's real value and carries a review duty: an unreviewed narration must never look published.

## Options compared

### Code licence

| Option | Effect | Used by |
| --- | --- | --- |
| **Apache-2.0** | Anyone may reuse the code, also in closed apps, with attribution; includes a patent grant; contributions come in under the same licence (section 5) | Android, Jetpack, Now in Android |
| GPL-3.0 | Anyone who builds on the code must open their code too | Quran for Android |
| MIT | Like Apache-2.0 but shorter, with no patent grant | many small libraries |

**Choice:** Apache-2.0. The value that needs protecting is the content, which has its own licence; the code is most useful to others when it is easy to reuse; Apache-2.0 is the Android ecosystem's standard and compatible with every dependency in use.

### Where content lives

| Option | Effect |
| --- | --- |
| The public repository, under a closed licence | Simplest, but drafts (narrations not yet reviewed) are readable by anyone |
| **A private content repository** | Content is written and reviewed in private; it reaches people only inside signed content packages, after the owner approves it |

**Choice:** a private repository, `seerah-content`. The app does not need content to build (it downloads content packages), so the split is clean: the public repository keeps the app, the content schema, the validator and build tools, and the documentation.

### Content licence

| Option | Effect |
| --- | --- |
| **All rights reserved** | No reuse without written permission |
| CC BY-NC-ND 4.0 | Sharing allowed with credit, without changes or commercial use; still not open |

**Choice:** all rights reserved (`LicenseRef-Seerah-Content`).

## Decision

1. **Code and documentation: Apache-2.0.** `LICENSE` holds the Apache text so GitHub detects it; `NOTICE` names the project and points to the other licences. Contributions are accepted under Apache-2.0 section 5; no separate contributor agreement for now.
2. **Content: all rights reserved,** under `LICENSES/LicenseRef-Seerah-Content.txt`, wherever it is stored or distributed, including content packages.
3. **Content is authored in a private repository, `seerah-content`.** Until it exists, the content still in this repository (`content/index/`, `content/events/`, `map-data/historical/`, `map-data/updates/`) is under the content licence.
4. **Every file's licence is stated** in `REUSE.toml` following the [REUSE specification](https://reuse.software); `reuse lint` runs in pre-commit and CI.
5. **The name and logo are not licensed** ([TRADEMARKS.md](../../TRADEMARKS.md)): a fork must use its own name and icon. Apache-2.0 grants no trademark rights (section 6).
6. Third-party material keeps its own licence ([THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)).

## Consequences

- Anyone may read, build and reuse the app's code; nobody may reuse the content, name or logo.
- Content already committed to this public repository stays in its git history: the draft event titles (also in the PRD) and the spike's Hijra data. It remains under the content licence. Rewriting the public history is not planned; it would break every clone for little gain, since that material was already public.
- The content build, validator and preview build must work with content from the private repository; the schema and tools stay public.
- A code of conduct (Contributor Covenant 2.1) is in place, with the contact the owner chose.
- AI agents working on content need access to the private repository.

## References

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0), sections 5 (contributions) and 6 (trademarks).
- [REUSE specification 3.3](https://reuse.software/spec-3.3/).
