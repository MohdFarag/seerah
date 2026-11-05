# السيرة (Al-Seerah)

An Arabic Android app (phones and tablets) for exploring the Prophet's ﷺ biography through **place, time and the original texts**. The 2D historical map is the main way to browse: users follow the Seerah step by step or explore it freely.

> Status: **pre-implementation**. Requirements are a draft for review (PRD v0.2). No code yet.

## Where things are

| Path | What |
| --- | --- |
| [`docs/prd/`](docs/prd/) | PRD v0.2 snapshot (Arabic) and how to read it |
| [`docs/requirements.md`](docs/requirements.md) | English digest of v1 requirements, rules and out-of-scope items |
| [`docs/open-decisions.md`](docs/open-decisions.md) | Decisions still open and when each gets settled |
| [`docs/roadmap.md`](docs/roadmap.md) | Next steps, starting with the technical spike |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records |
| [`content/`](content/) | Content model (JSON Schema) and the approved phase and event index |
| [`map-data/`](map-data/) | Historical map layers and base map sources (empty) |
| [`app/`](app/) | Android app (to be scaffolded once the stack is approved) |
| [`tools/`](tools/) | Content validation and packaging scripts |

## Ground rules

- **The PRD in Notion is the source of truth.** The most recent explicit decision by the product owner wins; old proposals in the log are not requirements.
- **Never invent facts.** No made-up date, month, day, coordinate, route or distance. Unknown stays unknown, and approximations are labeled.
- **No depiction** of the Prophet ﷺ or the Companions (male or female).
- Content is prepared, reviewed and published by the product owner.

## Checks

```sh
python3 tools/validate_content.py
```
