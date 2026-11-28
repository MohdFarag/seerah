# Documentation

Where every document lives and what it is for. If you add a document, list it here.

## Product

| Document | Purpose |
| --- | --- |
| [prd/PRD-v0.2.ar.md](prd/PRD-v0.2.ar.md) | Snapshot of the PRD from Notion (Arabic). Notion is the source of truth; re-snapshot, never edit. |
| [requirements.md](requirements.md) | English digest of the v1 requirements, with acceptance checks. |
| [open-decisions.md](open-decisions.md) | What is not decided yet, and when it will be. Never hard-code an answer to these. |
| [roadmap.md](roadmap.md) | Order of work and where we are. |

## Engineering

| Document | Purpose |
| --- | --- |
| [../AGENTS.md](../AGENTS.md) | Rules for every contributor and AI agent: quality bar, definition of done, content integrity. |
| [../.agents/skills/](../.agents/skills/) | Project skills for AI agents (`prd-sync`: re-snapshot the PRD from Notion), linked from `.claude/skills/` for Claude Code. |
| [engineering.md](engineering.md) | Architecture, code, UI, testing, performance and documentation standards. |
| [adr/](adr/) | Architecture Decision Records. |
| [signing-runbook.md](signing-runbook.md) | Offline Ed25519 key generation, storage, planned dual-key rotation and compromise recovery for `content` and `map` packages. |
| [maintaining.md](maintaining.md) | Repository settings (rulesets, Actions, security, labels) and releases. |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | Branches, commit messages, content rules, pull requests. |

## Design

| Document | Purpose |
| --- | --- |
| [design/](design/) | Design specifications and drafts, including EGM drafts and review reports (see [AGENTS.md](../AGENTS.md#agent-tooling)). |
| [design/exploration-and-story.md](design/exploration-and-story.md) | Specification of the content model accepted in ADR 0005: the exploration layer (facts on the map and in time) and the story layer (directed steps built from a fixed set of app moves). |
| [design/map-trial-build.md](design/map-trial-build.md) | Specification for the ADR 0009 decision 13 map trial build ([consolidated EGM review](design/egm-review-map-trial/consolidated_review_report.md)). |
| [design/map-trial-phase-1-plan.md](design/map-trial-phase-1-plan.md) | Phase 1 plan: map build tool (`tools/map/`), synthetic example and `map` CI workflow ([consolidated EGM review](design/egm-review-map-trial-phase-1/consolidated_review_report.md)). |
| [design/map-trial-phase-2-plan.md](design/map-trial-phase-2-plan.md) | Phase 2 plan: `:core:map`, `benchmark` build type and `app.seerah.trial` ([consolidated EGM review](design/egm-review-map-trial-phase-2/consolidated_review_report.md)). |
| [design/map-trial-phase-3-plan.md](design/map-trial-phase-3-plan.md) | Phase 3 plan: vector base layer (`base.pmtiles`), `tippecanoe` and `WhiteboxTools`, `:core:map` base-layer wiring, private trial inputs and documentation ([consolidated EGM review](design/egm-review-map-trial-phase-3/consolidated_review_report.md)). |
| [design/map-profile-validation-plan.md](design/map-profile-validation-plan.md) | Coordinate bounds, outline paths and strict YAML regression test plan ([consolidated EGM review](design/egm-review-map-profile-validation/consolidated_review_report.md)). |
| [design/v1-map-pipeline-spec.md](design/v1-map-pipeline-spec.md) | Production v1 map pipeline: coastal cleanup edits, `coverage.yaml`, `map.json` (`tile_schema: 1`) and shared host-side Ed25519 signing/packaging ([consolidated EGM review](design/egm-review-v1-map-pipeline/consolidated_review_report.md)). |
| [design/v1-android-package-and-map-spec.md](design/v1-android-package-and-map-spec.md) | Approved v1 Android package verification, storage lifecycle and `tile_schema: 1` map-loading specification ([consolidated EGM review](design/egm-review-v1-android-package-and-map/consolidated_review_report.md)). |
| [design/2026-11-22_DRIFT_REPORT.md](design/2026-11-22_DRIFT_REPORT.md) | Dated EGM drift report comparing `docs/` against the codebase at commit `cb58036` (historical snapshot). |

## Map trial

| Document | Purpose |
| --- | --- |
| [map-trial/README.md](map-trial/README.md) | Status tracker for ADR 0009 decision 13's eleven trial items, device deferrals and step reports. |
| [map-trial/step-1.md](map-trial/step-1.md) | Step 1 foundation check results (workstation checks, Galaxy A54 phase-2 run and GLO-90 valley probe; Oppo A55 and MatePad 11.5 pending). |
| [map-trial/step-2.md](map-trial/step-2.md) | Step 2 small-window builds (`window-najd`, `window-spike`, `window-yemen`), Galaxy A54 runs and owner decisions. |
| [map-trial/step-3.md](map-trial/step-3.md) | Step 3 10%-band builds (`band-10pct`) and full-extent time, disk and `2 GiB` size extrapolations. |
| [map-trial/step-4.md](map-trial/step-4.md) | Step 4 full-extent builds (`full`), Galaxy A54 runs, modern-feature audit and Step 4 decisions. |

## Research and spike

| Document | Purpose |
| --- | --- |
| [research/android-stack-study.ar.md](research/android-stack-study.ar.md) | Android stack study that led to ADR 0002 (Arabic). |
| [spike/summary.md](spike/summary.md) | What the technical spike proved, with targets and the renderer choice. |
| [spike/findings.md](spike/findings.md) | Build-by-build log of the spike: what worked, what failed and why. |
| [spike/measurements.md](spike/measurements.md) | Measurement method and results on the three test devices. |

## Elsewhere in the repository

| Document | Purpose |
| --- | --- |
| [../android/README.md](../android/README.md) | The Android project: layout, commands, convention plugins and the rules the build enforces. |
| [../android/spike/README.md](../android/spike/README.md) | The finished technical spike and how to build it. |
| [../content/README.md](../content/README.md) | Content layout, schema rules and review states. |
| [../map-data/README.md](../map-data/README.md) | Map data sources, licences and the base-map pipeline. |
| [../CHANGELOG.md](../CHANGELOG.md) | User-visible changes. |
| [../SECURITY.md](../SECURITY.md) | How to report a vulnerability privately. |
| [../CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) | Contributor Covenant 2.1, and how to report a problem. |
| [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | Third-party fonts, map build tools, libraries and data, with their licences and attribution. |
| [../REUSE.toml](../REUSE.toml), [../LICENSES/](../LICENSES/) | The licence of every file: Apache-2.0 for code and docs, the content licence, OFL for fonts. |
| [../TRADEMARKS.md](../TRADEMARKS.md) | The name and logo are not licensed; forks use their own. |
| [../tools/README.md](../tools/README.md) | The validation and build scripts. |
