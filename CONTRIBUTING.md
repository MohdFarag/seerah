# Contributing

Thanks for helping. This is a single-owner project: Mohamed Farag owns the product, the content and the reviews. Please open an issue before starting any significant work.

## Setup

See [README → Getting started](README.md#getting-started). Pre-commit hooks must be installed (`pre-commit install --install-hooks`).

## Branches

- `main` is protected and always releasable. Nobody pushes to it directly.
- Work on short-lived branches named `<type>/<short-description>`, e.g. `feat/time-slider`, `content/badr`, `fix/rtl-labels`.
- Open a PR into `main`, get CI green, then **squash-merge**. The PR title becomes the commit message.

## Commit messages: Conventional Commits

```text
<type>(<optional scope>): <summary in imperative mood>
```

| Type | Use for |
| --- | --- |
| `feat` | a new user-facing capability |
| `fix` | a bug fix |
| `content` | adding or correcting Seerah content (texts, dates, places) |
| `docs` | documentation only |
| `refactor` | code change with no behaviour change |
| `perf` | performance |
| `test` | tests |
| `build` / `ci` | build system or CI |
| `chore` | maintenance |

Breaking changes: add `!` after the type (`feat!: …`) or a `BREAKING CHANGE:` footer. Example: `content(badr): add the Prophet's ﷺ supplication situation`.

A commit-msg hook checks this format locally, and CI checks PR titles.

## Content rules (non-negotiable)

1. **Never invent facts.** No date, month, day, coordinate, route or distance without a source. Unknown stays unknown, and approximation is flagged.
2. **The original text comes first**, with its source. Editorial linking text uses `kind: "editorial"` and never mixes with narration wording.
3. **One chosen narration** per situation in v1.
4. **No depiction** of the Prophet ﷺ or the Companions (male or female).
5. Only the owner can mark content `approved`.
6. `python3 tools/validate_content.py` must pass.

## Standards and definition of done

Read [AGENTS.md](AGENTS.md) (quality bar, definition of done, documentation review) and [docs/engineering.md](docs/engineering.md) (architecture, code, tests, docs) before your first change. They apply equally to people and AI agents.

## Pull requests

- Keep PRs small and focused, and fill in the template.
- Update `CHANGELOG.md` under **Unreleased** for user-visible changes.
- Record architecture decisions as ADRs in `docs/adr/`.

## Secrets

Never commit secrets, keystores or `.env`. Use `.env.example` to document new variables. The pre-commit hook blocks private keys.
