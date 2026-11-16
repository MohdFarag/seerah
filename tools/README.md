# tools

Scripts that check and build the repository's data. Run them from the repository root.

| Script | What it does | Run by |
| --- | --- | --- |
| [validate_content.py](validate_content.py) | Checks content against the JSON Schema and cross-references (IDs, phases, approval rules): the synthetic example by default, or a `seerah-content` checkout given as an argument | pre-commit and CI here; CI in `seerah-content` |
| [android/check_page_alignment.py](android/check_page_alignment.py) | Checks that an APK's 64-bit native libraries support 16 KB memory pages | the `Android` workflow, after the release build |
| [map/](map/) | The map trial's build tool: fetches pinned elevation data and builds the relief PMTiles archive in a toolchain image ([ADR 0009](../docs/adr/0009-map-data-pipeline.md) decision 13) | the owner, through `map/run.py`; the `map` workflow for its tests |
| [spike/](spike/) | Base map, glyph and content-update builders for the finished technical spike (reference only) | the `Spike APK` workflow, on demand |

New tools for v1 (for example the content build and validator from [ADR 0005](../docs/adr/0005-content-model-exploration-and-story.md)) get their own folder here, with a line in this table.
