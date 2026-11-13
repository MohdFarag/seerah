<div align="center">

# سيرة رسول الله ﷺ

**Explore the Prophet's ﷺ biography through place, time and the original texts.**

[![CI](https://github.com/MohdFarag/seerah/actions/workflows/ci.yml/badge.svg)](https://github.com/MohdFarag/seerah/actions/workflows/ci.yml)
[![Android](https://github.com/MohdFarag/seerah/actions/workflows/android.yml/badge.svg)](https://github.com/MohdFarag/seerah/actions/workflows/android.yml)
![Status](https://img.shields.io/badge/status-in%20development-blue)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Language](https://img.shields.io/badge/language-العربية-blue)
![Code license](https://img.shields.io/badge/code-Apache--2.0-blue)
![Content](https://img.shields.io/badge/content-all%20rights%20reserved-lightgrey)

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

> The app's first screens are not built yet. Screenshots and a demo will go here when they are.

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
python3 tools/validate_content.py   # check the content format (add a path to check seerah-content)
pre-commit run --all-files          # all linters and formatters
(cd android && ./gradlew spotlessCheck check buildHealth)   # app: format, lint, tests, build rules
```

## Repository layout

```text
.
├── android/             Android project: the v1 app (:app, build-logic/) and spike/ (finished technical spike)
├── content/
│   ├── schema/          JSON Schema for phases, events, situations, texts, sources
│   └── example/         synthetic example of the format (the content itself is in the private seerah-content repository)
├── map-data/            base map, historical layers, map style (large binaries not in git)
├── docs/                see docs/README.md for the full index
│   ├── prd/             PRD snapshot (Notion is the source of truth)
│   ├── adr/             Architecture Decision Records
│   ├── requirements.md  English requirements digest
│   ├── engineering.md   engineering standards
│   ├── open-decisions.md
│   ├── roadmap.md
│   └── maintaining.md   branch protection, releases, repo settings
├── tools/               validation and build scripts (see tools/README.md)
├── AGENTS.md            instructions for contributors and AI agents
└── .github/             CI, issue and PR templates
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [AGENTS.md](AGENTS.md) (also the instructions for AI agents) and the [engineering standards](docs/engineering.md). Commits follow [Conventional Commits](https://www.conventionalcommits.org/), and releases follow [Semantic Versioning](https://semver.org/) (see the [CHANGELOG](CHANGELOG.md)). Found a mistake in the content? Open a **Content correction** issue.

## License

- **Code and documentation:** [Apache-2.0](LICENSE). Contributions are accepted under the same licence.
- **Content** (Seerah data, texts as selected and edited, explanations, story scripts, historical map data, designs): **all rights reserved**, under the [content licence](LICENSES/LicenseRef-Seerah-Content.txt). Being able to read it here grants no right to reuse it.
- **Name and logo:** not licensed for others' use; a fork needs its own name and icon ([TRADEMARKS.md](TRADEMARKS.md)).
- **Third-party** fonts and data keep their own licences ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).

[REUSE.toml](REUSE.toml) states the licence of every file; `reuse lint` checks it. See [ADR 0006](docs/adr/0006-open-source-code-and-closed-content.md).
