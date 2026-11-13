# Open decisions

From PRD v0.2 §13 and the open items elsewhere in it. **Don't hard-code an answer to any of these.** Keep each one configurable or leave it out until it is decided.

| Topic | Decided when |
| --- | --- |
| Map zoom limit; story-mode details; how the map looks outside a detail area when zoomed past the regional zoom ([ADR 0009](adr/0009-map-data-pipeline.md)) | During implementation (owner's call) |
| First launch: an initial content package inside the APK, or downloaded on first launch (full download before use vs start while downloading; size itself is not a constraint) | During the v1 build ([ADR 0007](adr/0007-content-formats-compatibility-and-signing.md)). A first-launch download is user-started, so the Data Saver rule for the automatic check ([ADR 0008](adr/0008-update-hosting-and-index.md)) must not block it |
| Exact map bounds within the Arabian Peninsula, detail levels and zoom levels (recorded in `map/coverage.yaml`, [ADR 0009](adr/0009-map-data-pipeline.md)); list of landmark and border changes | During content and map preparation |
| Final v1 event list and depth; whether and how al-Isra is included after its dating is checked | As each phase's content is prepared |
| Date documentation; pre-Hijra reference; Gregorian conversion | Content review with the owner |
| Long-running events, approximate or unknown dates, disputed locations | When those cases come up in content |
| The actual display-date values (the one-date rule is decided) | Content review |
| Choice of narrations, verses, explanations, editions; asset rights inventory | Content preparation |
| Accessibility details, failure states | Implementation spec |
| Final application ID (permanent after the first Play Store upload; `app.seerah` for development) | Before the first upload |
| Buying the project domain; whether the packages move to Cloudflare R2; whether public builds carry one index address or an ordered list (e.g. the domain, then `seerah-updates.pages.dev` as a fallback) | Before the first public release ([ADR 0008](adr/0008-update-hosting-and-index.md)) |
| Analytics tool and data; feedback fields and how feedback is delivered | Before any data collection is turned on |
| Closed-beta size, distribution, length; success criteria for going public | Before the beta is evaluated |
| Event categories | Not decided |
| How daily life, legislation and Shama'il topics are split into situations | Not decided |
| Backup or restore after reinstall | Not decided |
| How to depict anonymous soldiers (none may be assumed) | Not decided |
| English name, subtitle, logo; name availability check | Not decided |
| Pre-birth intro: reference date and detailed stations | Later release (outside v1) |

## Decided

- Update hosting and the update index: [ADR 0008](adr/0008-update-hosting-and-index.md), accepted 2026-11-13. Free hosting until the first public release (index at `seerah-updates.pages.dev`, packages on GitHub Releases), and an automatic check that never downloads.
- Content formats, compatibility and package signing: [ADR 0007](adr/0007-content-formats-compatibility-and-signing.md), accepted 2026-11-13.
- App framework, map engine, update mechanism and performance targets: [ADR 0002](adr/0002-proposed-stack.md), accepted 2026-11-13. Test devices: Oppo A55, Galaxy A54, Huawei MatePad 11.5 ([roadmap](roadmap.md)). Minimum Android version: API 26 (owner, 2026-11-13).
