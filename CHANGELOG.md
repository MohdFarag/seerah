# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). App releases are tagged `vX.Y.Z`. Content packages are versioned separately (`content_version`).

## [Unreleased]

### Added

- Android Phase 2 verification core (`:core:content`) and Python-to-Kotlin golden fixtures: isolated single-key Ed25519 `NO_PREFIX` verification (`tink-android`), hardened `FileChannel` ZIP reader, `manifest.json` / `data/map.json` / PMTiles v3 binary directory & metadata validation, and deterministic fixture drift tests in `tools/packages/`.
- Android Phase 1 foundation for v1 packages (`seerah.jvm.library`, `:core:model`, `:core:map` `MapPackageBinding`): pure-JVM `tile_schema: 1` `MapMetadata` models and verified metadata-to-style helpers (`toCameraLimits`, `resolveMapFiles`, `styleCValueRamp`, `toStyleSpec`).
- Production map pipeline (`tools/map`): `coverage.schema.json`, `cleanup.schema.json`, `map-data/schema/map.schema.json`, coastal cleanup edits (`remove-land` and `fill-water`), `fetch --coverage`, `validate --coverage`, `build --coverage` (`tile_schema: 1` atomic staging), and the synthetic `map-data/example-production/` fixture.
- Shared host-side packaging and offline Ed25519 manifest signing (`tools/packages`, `content/schema/manifest.schema.json`, `docs/signing-runbook.md`): `keygen`, `pack-map`, and `verify` with isolated single-key verify-before-parse verification and deterministic streaming ZIP packaging.

- Repository structure, PRD v0.2 snapshot, requirements digest, open decisions, roadmap and ADRs.
- Content JSON Schema and the original index of 4 phases and 41 approved event titles (kept as `full-seerah-titles.json`).
- Content validator, pre-commit hooks (Ruff, markdownlint, JSON/YAML checks, secret detection, Conventional Commits), and CI.
- Issue and PR templates, CODEOWNERS, Dependabot, LICENSE, CONTRIBUTING and SECURITY.
- Technical spike app proving the stack; ADR 0002 accepted with performance targets.
- v1 scope (Taif to Badr) and its index of 26 draft events in 5 phases.
- `AGENTS.md` (instructions for contributors and AI agents), engineering standards and a documentation index.
- ADR 0003 (v1 architecture and modules) and ADR 0004 (build and quality tooling), accepted.
- The v1 Android project in `android/`: convention plugins, version catalog, a single-activity app that is always right to left, and CI that checks formatting, lint, tests, module rules, licences, dependency health and 16 KB page alignment. The spike moved to `android/spike/`.
- The content model for exploration and story mode (ADR 0005); the map now covers the whole Arabian Peninsula, and time moves by month where a source gives it.
- Licensing (ADR 0006): code and documentation under Apache-2.0, content all rights reserved, the name and logo not licensed, and every file's licence stated in `REUSE.toml`.
- The content moved to the private `seerah-content` repository; this repository keeps the schema, a synthetic example and the validator, which now takes the content folder as an argument.
- A code of conduct (Contributor Covenant 2.1).
- A `prd-sync` skill that re-snapshots the PRD from Notion the same way every time.
- The EGM design-review plugin enabled for Claude Code sessions on this project, pinned to v0.1.1.
- Repository hardening: every GitHub Action pinned to a commit SHA, per-job permissions and timeouts, workflow linting and security audit (actionlint, zizmor), a 7-day Dependabot cooldown, line-ending rules, a third-party notices file, and READMEs for `tools/` and `map-data/` that match their contents.
- The map trial's build tool (`tools/map`): a pinned toolchain image, a host launcher, `fetch`, `build` and `clean` commands that render the relief into a PMTiles archive with a run report, a synthetic example in `map-data/example/`, a `map` CI workflow, and Dependabot updates for its image and Python packages.
