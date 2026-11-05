# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). App releases are tagged `vX.Y.Z`. Content packages are versioned separately (`content_version`).

## [Unreleased]

### Added

- Technical spike (`spike/`): offline test package builder (PMTiles base map, Arabic SDF glyphs, time-state GeoJSON, test content, manifest with checksums), local update server with Range and simulated cuts, Kotlin `core` module (time states, package install, update and rollback, resumable download, progress rules) with 21 tests, and an unbuilt Compose + MapLibre + Room spike app.
- CI job that builds the spike package and runs the core tests.

## [0.1.0] - 2026-11-05

### Added

- Repository structure, PRD v0.2 snapshot, requirements digest, open decisions, roadmap and ADRs.
- Content JSON Schema and the index of 4 phases and 41 approved event titles.
- Content validator, pre-commit hooks (Ruff, markdownlint, JSON/YAML checks, secret detection, Conventional Commits), and CI.
- Issue and PR templates, CODEOWNERS, Dependabot, LICENSE, CONTRIBUTING and SECURITY.

[Unreleased]: https://github.com/MohdFarag/seerah/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/MohdFarag/seerah/releases/tag/v0.1.0
