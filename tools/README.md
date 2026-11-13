# tools

Scripts that check and build the repository's data. Run them from the repository root.

| Script | What it does | Run by |
| --- | --- | --- |
| [validate_content.py](validate_content.py) | Checks `content/` against the JSON Schema and cross-references (IDs, phases, approval rules) | pre-commit and CI |
| [android/check_page_alignment.py](android/check_page_alignment.py) | Checks that an APK's 64-bit native libraries support 16 KB memory pages | the `Android` workflow, after the release build |
| [spike/](spike/) | Base map, glyph and content-update builders for the finished technical spike (reference only) | the `Spike APK` workflow, on demand |

New tools for v1 (the content build and validator from [ADR 0005](../docs/adr/0005-content-model-exploration-and-story.md), and the map pipeline) get their own folder here, with a line in this table.
