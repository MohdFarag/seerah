<div align="center">

# سيرة رسول الله ﷺ

**Explore the Prophet's ﷺ biography through place, time and the original texts.**

[![CI](https://github.com/MohdFarag/seerah/actions/workflows/ci.yml/badge.svg)](https://github.com/MohdFarag/seerah/actions/workflows/ci.yml)
![Status](https://img.shields.io/badge/status-pre--implementation-orange)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Language](https://img.shields.io/badge/language-العربية-blue)
![License](https://img.shields.io/badge/license-proprietary-lightgrey)

</div>

---

## Overview

**سيرة رسول الله ﷺ** is a free Arabic Android app (phones and tablets) that turns the Seerah into a journey across a 2D historical map. Users either **follow the Seerah** step by step or **explore freely**. As time moves, the map changes with it: place names, borders of kingdoms and landmarks such as the Prophet's Mosque all appear in their own time.

Every event shows its **original texts first** (Quran, hadith, athar, and narrations as carried in Seerah books), each with its source and a simple explanation on request.

| | |
| --- | --- |
| 🗺️ **Map first** | A historical map of the Arabian Peninsula, most detailed where the events happen: Makkah, Taif, Madinah, Quba, Badr and the routes between |
| ⏳ **Time-synced** | Browse by year; names, borders and landmarks match the selected time |
| 📜 **Texts first** | Original wording shown directly, sourced, with a simple explanation on request |
| 🧭 **Follow or explore** | Manual step-by-step follow-along, with free map movement at every step |
| 📴 **Offline** | Texts, map and sequence work offline after download |
| 🔒 **No accounts** | Progress stays on the device |

**Integrity rules:** no invented dates, coordinates or routes. Uncertainty is stated, never hidden. No depiction of the Prophet ﷺ or the Companions.

## Screenshots

> The app hasn't been built yet. Screenshots and a demo will go here once the first build exists.

## Status

The first release covers one continuous period, **from the journey to Taif to the Battle of Badr**. The technical spike is done and the stack is accepted ([ADR 0002](docs/adr/0002-proposed-stack.md)); next is building v1, app and content together ([roadmap](docs/roadmap.md)). Requirements: [PRD v0.2](docs/prd/PRD-v0.2.ar.md).

## Getting started

**Requirements:** Git, Python 3.11+. For the app: JDK 21 and the Android SDK with platform 37 (Android Studio includes both); see [android/README.md](android/README.md).

```sh
git clone https://github.com/MohdFarag/seerah.git
cd seerah

python3 -m venv .venv
source .venv/bin/activate           # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt

pre-commit install --install-hooks  # lint, format and commit-message checks
cp .env.example .env                # local config (never commit .env)
```

Run the checks:

```sh
python3 tools/validate_content.py   # validate content against the schema
pre-commit run --all-files          # all linters and formatters
(cd android && ./gradlew spotlessCheck check buildHealth)   # app: format, lint, tests, build rules
```

## Repository layout

```text
.
├── android/             Android project: the v1 app (:app, build-logic/) and spike/ (finished technical spike)
├── content/
│   ├── schema/          JSON Schema for phases, events, situations, texts, sources
│   ├── index/           v1 phases and events (draft)
│   └── events/          full event content
├── map-data/            base map, historical layers, map style (large binaries not in git)
├── docs/                see docs/README.md for the full index
│   ├── prd/             PRD snapshot (Notion is the source of truth)
│   ├── adr/             Architecture Decision Records
│   ├── requirements.md  English requirements digest
│   ├── engineering.md   engineering standards
│   ├── open-decisions.md
│   ├── roadmap.md
│   └── maintaining.md   branch protection, releases, repo settings
├── tools/               validation and packaging scripts
├── AGENTS.md            instructions for contributors and AI agents
└── .github/             CI, issue and PR templates
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [AGENTS.md](AGENTS.md) (also the instructions for AI agents) and the [engineering standards](docs/engineering.md). Commits follow [Conventional Commits](https://www.conventionalcommits.org/), and releases follow [Semantic Versioning](https://semver.org/) (see the [CHANGELOG](CHANGELOG.md)). Found a mistake in the content? Open a **Content correction** issue.

## License

Proprietary, all rights reserved. See [LICENSE](LICENSE).
