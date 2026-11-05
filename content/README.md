# content

The Seerah content, kept separate from the app so it can ship as an independent, versioned package.

```text
content/
  schema/content.schema.json   entity definitions (phase, event, situation, text, source, place, display date)
  index/phases.json            the 4 approved phases and the approved event titles
  events/<event-id>.json       full event content (added during content preparation)
```

## Status of the index

Event titles in `index/phases.json` are **approved for inclusion only**. The owner hasn't reviewed their narrations, dates or locations yet. Every event starts as `review: "title-approved"`.

The Phase 3 dates the owner supplied are kept in `preliminary_date_note`, exactly as given. They are a **preliminary compilation, not production data**, and must not be used as display dates until reviewed.

## Rules the schema enforces

- A date carries its **precision** (`year`, `month`, `day`). An unknown part is never filled in.
- A place carries its **accuracy** (`exact`, `general`, `unknown`). With `general`, the event links to the city or region, so the app must say the exact spot is unknown. `unknown` means no marker at all.
- A route line exists only when `route.documented` is true. Distances say whether they come from a `source` or are an `estimate`.
- Each text records its source. Excerpts are marked and point to the full narration.
- Editorial (linking or summary) text is a separate kind and is never mixed with narration wording.
