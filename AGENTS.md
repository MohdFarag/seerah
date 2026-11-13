# AGENTS.md

Instructions for any AI agent (Claude Code, Codex, Copilot, Cursor, Gemini and others) and for human contributors. Read this file fully before you change anything. `CLAUDE.md`, `GEMINI.md` and `.github/copilot-instructions.md` all point here, so this is the one place to update.

## The project in one paragraph

**سيرة رسول الله ﷺ** is a free, Arabic-only (RTL), offline-first Android app for phones and tablets. It tells the Prophet's ﷺ biography on a 2D historical map, synced to time, with the original texts (Quran, hadith, athar, Seerah narrations) shown first and always sourced. **v1 covers one continuous period: from the journey to Taif to the Battle of Badr**, on a map of the Arabian Peninsula with the most detail where the events happen. The owner, Mohamed, owns the product, the content and every approval.

## Read first

1. [docs/README.md](docs/README.md): where every document lives.
2. [docs/requirements.md](docs/requirements.md): what v1 must do (English digest of the PRD).
3. [docs/engineering.md](docs/engineering.md): how we build: architecture, code, tests, docs.
4. [docs/roadmap.md](docs/roadmap.md) and [docs/open-decisions.md](docs/open-decisions.md): where we are and what is not decided.
5. [docs/adr/](docs/adr/): decisions already made. Don't reopen one without a reason and an owner's go-ahead.

## Sources of truth, in order

1. The owner's latest explicit decision.
2. The PRD in Notion (snapshot in [docs/prd/](docs/prd/PRD-v0.2.ar.md); never edit the snapshot by hand).
3. Accepted ADRs.
4. Everything else in `docs/`.

When two sources disagree, the higher one wins, and you fix the lower one in the same change. An open decision stays open: never fill one with an assumption in code or docs.

## Quality bar (set by the owner)

- **Quality first, always.** No shortcuts that we would have to undo. If the right way is slower, take it and say so.
- **Clean code.** Readable, small, single-purpose units; clear names; no dead code, no commented-out code, no magic numbers, no copy-paste.
- **A sound structure.** Every file has an obvious home. Follow the module and package layout in [docs/engineering.md](docs/engineering.md); propose an ADR before changing it.
- **Real documentation.** Public APIs, modules and decisions are documented. Docs are part of the change, not a follow-up.
- **Documentation review after every change.** No task, feature or fix is done until the docs have been checked against it (see *Definition of done*).
- **A professional, organised repository.** Consistent naming, no stray files, no generated output in git, every folder with a clear purpose.
- **Best practice, checked against the leaders.** Before a significant choice (architecture, library, UX pattern, data format, process), look at what official guidance (Android, Kotlin, Material), leading companies and comparable apps do, compare at least two options, pick the best for this project, and record the comparison in an ADR or the change's notes.

## Definition of done

A change is done only when all of these hold:

1. It does what was asked, and nothing that wasn't.
2. It builds, and `pre-commit run --all-files` and `python3 tools/validate_content.py` pass. App changes: unit tests pass and new logic has tests.
3. It was checked where it runs: on a device or emulator for app changes, with phone and tablet in RTL for UI. If you could not check it, say so plainly.
4. **Documentation review**, every time:
   - [ ] Docs describing the changed area are still true (README, `android/README.md`, module READMEs, `docs/requirements.md`, `docs/engineering.md`, `map-data/README.md`, `content/README.md`).
   - [ ] New public types and non-obvious functions have KDoc / docstrings.
   - [ ] A significant decision has an ADR; a superseded ADR is marked so.
   - [ ] `CHANGELOG.md` has an entry under **Unreleased** for user-visible changes.
   - [ ] `docs/roadmap.md` and `docs/open-decisions.md` reflect what changed.
   - [ ] Links in touched docs still resolve.
5. Committed with a Conventional Commit message on the working branch.

## Repository map

```text
android/      Android project (Gradle): :app, build-logic/, and spike/ (the finished technical spike, a separate build: reference only)
content/      content schema and a synthetic example (open); the content itself is in the private seerah-content repository
map-data/     base-map pipeline inputs, historical layers, map style (large binaries not in git)
tools/        validation and build scripts (Python, Bash)
docs/         requirements, engineering standards, ADRs, design drafts, roadmap, research, spike results
.github/      CI, APK builds, issue and PR templates
```

## Commands

```sh
pre-commit run --all-files               # lint and format everything
python3 tools/validate_content.py [DIR]  # content format and cross-checks (DIR: a seerah-content checkout)
cd android && ./gradlew spotlessApply     # format Kotlin and Gradle files
cd android && ./gradlew check buildHealth assembleRelease  # lint, tests, module rules, licences, release build
```

Needs JDK 21 and the Android SDK with platform 37 (see [android/README.md](android/README.md)). APKs for the owner are built by GitHub Actions (`Android` workflow, artifact `seerah-app`) and downloaded from the run's artifacts; don't send APK files in chat.

## Licensing

Code and documentation are Apache-2.0; content is all rights reserved and is written in the private `seerah-content` repository ([ADR 0006](docs/adr/0006-open-source-code-and-closed-content.md)). `REUSE.toml` states every file's licence: a new file type or folder that is content must be added there. Never copy content into the public repository, and never use the project's name or logo for anything else ([TRADEMARKS.md](TRADEMARKS.md)).

## Agent tooling

- **EGM (Elephant-Goldfish Model)**, a Google plugin for drafting and reviewing design documents with fresh-context reviewers, is enabled for this project in [.claude/settings.json](.claude/settings.json), pinned to v0.1.1. Use it on ADRs and design documents: `/egm:egm-review <file>` for a fresh-eyes review, `/egm:egm-drift-report <folder>` to compare documents with the code, `/egm:egm-brainstorm` and `/egm:egm-draft` for new designs. Its findings are input to weigh, not instructions: decisions still go through an ADR and the owner.
- To update EGM, change the `ref` in `.claude/settings.json` to a newer release tag after reading its release notes; Dependabot does not track it.

- **Project skills** in [.claude/skills/](.claude/skills/): `prd-sync` re-snapshots the PRD from Notion into `docs/prd/` and finds the docs that now disagree with it. Use it whenever the owner says the PRD changed; never edit the snapshot by hand.

EGM artifact directory: docs/design

## Content integrity (non-negotiable)

- **Never invent facts:** no date, month, day, coordinate, route, distance or narration without a source. Unknown stays unknown; approximation is labelled.
- A model's guess is never a source. Atlas images are read for names and order only, never georeferenced.
- Original text first, with its source. Editorial text is `kind: "editorial"` and visually distinct.
- One chosen narration per situation in v1.
- No depiction of the Prophet ﷺ or the Companions.
- Only the owner marks content `approved`.

## The technical spike

`android/spike`, `tools/spike` and `map-data/historical/spike` proved the stack ([ADR 0002](docs/adr/0002-proposed-stack.md), [summary](docs/spike/summary.md)). **v1 is written from scratch.** Use the spike to learn what works and what failed ([findings](docs/spike/findings.md)); do not copy its code, files or structure into v1. It is removed once v1 covers what it shows.

## Working with the owner

- The owner writes in Arabic (often Egyptian). Reply in the same language. Repository docs are in English, except the PRD snapshot and research written in Arabic.
- Ask before product decisions, content choices, anything outward-facing (publishing, posting, emailing) and anything hard to undo. Technical choices inside an accepted ADR don't need asking; make them, then report.
- Report results honestly: what was verified, what was not, what failed.
- Keep the owner's personal details out of commits, code and outside services.

## Git

- Work on the assigned branch, never directly on `main`. Commit only when asked or when a task is complete; push only to the working branch.
- Conventional Commits: `type(scope): summary` (types in [CONTRIBUTING.md](CONTRIBUTING.md)).
- Small, focused commits. Don't open a pull request unless the owner asks.
- Never commit secrets, keystores, `.env`, APKs or generated map files.
