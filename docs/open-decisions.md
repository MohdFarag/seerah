# Open decisions

From PRD v0.2 §13 and the open items elsewhere in it. **Don't hard-code an answer to any of these.** Keep each one configurable or leave it out until it is decided.

| Topic | Decided when |
| --- | --- |
| Map zoom limit; story-mode details | During implementation (owner's call) |
| Full download before use vs start while downloading (size itself is not a constraint) | During the v1 build |
| Exact map bounds within the Arabian Peninsula, detail levels and zoom levels; list of landmark and border changes | During content and map preparation |
| Final v1 event list and depth; whether and how al-Isra is included after its dating is checked | As each phase's content is prepared |
| Date documentation; pre-Hijra reference; Gregorian conversion | Content review with the owner |
| Long-running events, approximate or unknown dates, disputed locations | When those cases come up in content |
| The actual display-date values (the one-date rule is decided) | Content review |
| Choice of narrations, verses, explanations, editions; asset rights inventory | Content preparation |
| Accessibility details, failure states | Implementation spec |
| Final application ID (permanent after the first Play Store upload; `app.seerah` for development) | Before the first upload |
| Content package format, app/content compatibility, package signing | ADR 0007 |
| Where content updates are hosted | ADR 0008 |
| Analytics tool and data; feedback fields and how feedback is delivered | Before any data collection is turned on |
| Closed-beta size, distribution, length; success criteria for going public | Before the beta is evaluated |
| Event categories | Not decided |
| How daily life, legislation and Shama'il topics are split into situations | Not decided |
| Backup or restore after reinstall | Not decided |
| How to depict anonymous soldiers (none may be assumed) | Not decided |
| English name, subtitle, logo; name availability check | Not decided |
| Pre-birth intro: reference date and detailed stations | Later release (outside v1) |

## Decided

- App framework, map engine, update mechanism and performance targets: [ADR 0002](adr/0002-proposed-stack.md), accepted 2026-11-13. Test devices: Oppo A55, Galaxy A54, Huawei MatePad 11.5 ([roadmap](roadmap.md)). Minimum Android version: API 26 (owner, 2026-11-13).
