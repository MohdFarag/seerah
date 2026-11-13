# content

The Seerah content, kept separate from the app so it can ship as an independent, versioned package.

**Licensing ([ADR 0006](../docs/adr/0006-open-source-code-and-closed-content.md)):** the schema (`schema/`) is open source with the code. The content data (`index/`, `events/`) is **all rights reserved** under the [content licence](../LICENSES/LicenseRef-Seerah-Content.txt), and moves to the private repository `seerah-content` once it exists; the schema and validator stay here.

```text
content/
  schema/content.schema.json   entity definitions (phase, event, situation, text, source, place, display date)
  index/phases.json            v1: 5 phases, 26 draft events, Taif to Badr (owner, 2026-11-11)
  index/full-seerah-titles.json  the earlier whole-Seerah title list, kept for later releases (not validated, not shipped)
  events/<event-id>.json       full event content (added during content preparation)
```

## Status of the index

The v1 index follows the owner's decision of 2026-11-11: the story opens with the journey to Taif, after a short lead-in on the state of the call after the Year of Sorrow, and ends with Badr and its outcome. The 26 events and their depth are a **working draft**: every event is `review: "draft"`. Narrations, exact dates and coordinates are not approved. Detail per event (situations, places, narrations, sources) is worked out when that event's content is prepared, not before.

The whole-Seerah title list from PRD v0.2 is kept in `full-seerah-titles.json` for later releases. Its Phase 3 dates are a preliminary compilation, not production data.

## Rules the schema enforces

- A date carries its **precision** (`year`, `month`, `day`). An unknown part is never filled in.
- A place carries its **accuracy** (`exact`, `general`, `unknown`). With `general`, the event links to the city or region, so the app must say the exact spot is unknown. `unknown` means no marker at all.
- A route line exists only when `route.documented` is true. Distances say whether they come from a `source` or are an `estimate`.
- Each text records its source. Excerpts are marked and point to the full narration.
- Editorial (linking or summary) text is a separate kind and is never mixed with narration wording.
