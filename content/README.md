# content

The **format** of the Seerah content: the JSON Schema the app and the content build rely on, and a synthetic example. Open source with the code.

The **content itself** (the v1 index of 26 draft events, event files, and everything the content model adds) is all rights reserved and lives in the private repository `seerah-content` ([ADR 0006](../docs/adr/0006-open-source-code-and-closed-content.md)). It was moved there on 2026-11-13.

```text
content/
  schema/content.schema.json  entity definitions (phase, event, situation, text, source, place, display date)
  example/                    a synthetic example in the same layout as seerah-content (not Seerah content); tests the validator
```

`python3 tools/validate_content.py` checks the example; `python3 tools/validate_content.py ../seerah-content` checks the real content. A change to the format is made here first, then applied in `seerah-content`.

## Rules the schema enforces

- A date carries its **precision** (`year`, `month`, `day`). An unknown part is never filled in.
- A place carries its **accuracy** (`exact`, `general`, `unknown`). With `general`, the event links to the city or region, so the app must say the exact spot is unknown. `unknown` means no marker at all.
- A route line exists only when `route.documented` is true. Distances say whether they come from a `source` or are an `estimate`.
- Each text records its source. Excerpts are marked and point to the full narration.
- Editorial (linking or summary) text is a separate kind and is never mixed with narration wording.
