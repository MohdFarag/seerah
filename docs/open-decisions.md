# Open decisions

From PRD v0.2 §13 and the open items elsewhere in it. **Don't hard-code an answer to any of these.** Keep each one configurable or leave it out until it is decided.

| Topic | Decided when |
| --- | --- |
| Map zoom limit; story-mode details | During implementation (owner's call) |
| First launch: the initial content package and the map package inside the APK, or downloaded once during setup (full download before use vs start while downloading; size itself is not a constraint) | During the v1 build ([ADR 0007](adr/0007-content-formats-compatibility-and-signing.md)). A first-launch download is user-started, so the Data Saver rule for the automatic check ([ADR 0008](adr/0008-update-hosting-and-index.md)) must not block it. The map package may be a few hundred MB: bundling it would need install-time Play Asset Delivery and would store it twice on the device ([ADR 0009](adr/0009-map-data-pipeline.md)) |
| Exact map bounds within the Arabian Peninsula and zoom levels: the regional relief zoom (11 or 12) and, if needed, the detail zoom at reconstructed sites (15 or 16), after the trial build; recorded in `map/coverage.yaml` in the private repository ([ADR 0009](adr/0009-map-data-pipeline.md)); list of landmark and border changes. Also the data margin (`min_data_margin_deg`) and the widest supported half-screen width (`widest_half_screen_dp`, after the MatePad 11.5 is measured), which have no defaults ([v1 map pipeline specification](design/v1-map-pipeline-spec.md) D6). The map trial's working values (12–32° N, 34–60° E, 1° margin, minimum MapLibre zoom 4) are working test values only, not decisions | During content and map preparation |
| Historical terrain reconstruction (for example Badr): which sources and atlases to rely on, the method, and the format of reconstruction edits (baked into the base relief; the cleanup of modern features is decided: `remove-land` and `fill-water` edits in `map/cleanup.yaml`, [v1 map pipeline specification](design/v1-map-pipeline-spec.md) D1, D2 and D5; the trial's provisional `schema: trial-0` edit format is not the reconstruction edit format) | When that work starts |
| How the app explains reconstructed terrain (settings, map information, credits); nothing is labelled on the map itself | Before a map package with reconstructions is listed in `stable` ([ADR 0009](adr/0009-map-data-pipeline.md) decision 11) |
| Streaming map tiles online, as an option beside the offline map | After v1 |
| Final v1 event list and depth; whether and how al-Isra is included after its dating is checked | As each phase's content is prepared |
| Date documentation; Gregorian conversion | Content review with the owner |
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

- v1 map pipeline: [specification](design/v1-map-pipeline-spec.md), owner decisions D1–D7 approved 2026-11-23. Coastal cleanup edits (`remove-land` and `fill-water`, their own schema, imagery or dated-data sources), the production `coverage.yaml` and `map.json` (`tile_schema: 1`) formats, and host-side package signing in `tools/packages/`. The values in `coverage.yaml` stay open (above).
- Pre-Hijra dating: canonical Hijri axis (`1 BH, 2 BH…` / `1 AH…`, no year zero) plus an explicit, optional, owner-reviewed `prophethood_year` (السنة من البعثة) shown alongside pre-Hijra dates when sourced, never computed by formula; a display-priority toggle (Hijri default) may be added in a future release (owner, 2026-11-22; [ADR 0005](adr/0005-content-model-exploration-and-story.md)).
- Map data pipeline: [ADR 0009](adr/0009-map-data-pipeline.md), accepted 2026-11-14. Terrain-only base map in style C from pinned Copernicus data, one map package, terrain edits baked into the relief and disclosed outside the map, built from the private `seerah-content` repository.
- Update hosting and the update index: [ADR 0008](adr/0008-update-hosting-and-index.md), accepted 2026-11-13. Free hosting until the first public release (index at `seerah-updates.pages.dev`, packages on GitHub Releases), and an automatic check that never downloads.
- Content formats, compatibility and package signing: [ADR 0007](adr/0007-content-formats-compatibility-and-signing.md), accepted 2026-11-13.
- App framework, map engine, update mechanism and performance targets: [ADR 0002](adr/0002-proposed-stack.md), accepted 2026-11-13. Test devices: Oppo A55, Galaxy A54, Huawei MatePad 11.5 ([roadmap](roadmap.md)). Minimum Android version: API 26 (owner, 2026-11-13).
