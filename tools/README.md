# tools

Scripts that check and build the repository's data. Run them from the repository root.

| Script | What it does | Run by |
| --- | --- | --- |
| [validate_content.py](validate_content.py) | Checks content against the JSON Schema and cross-references (IDs, phases, approval rules): the synthetic example by default, or a `seerah-content` checkout given as an argument | pre-commit and CI here; CI in `seerah-content` |
| [android/check_no_trial_code.py](android/check_no_trial_code.py) | Verifies with `aapt2` that a release APK's manifest declares no `app.seerah.trial` trial component, failing if unreadable ([ADR 0007](../docs/adr/0007-content-formats-compatibility-and-signing.md) decision 10) | the `Android` workflow, after the release build (with unit tests in `android/tests/`) |
| [android/check_page_alignment.py](android/check_page_alignment.py) | Checks that an APK's 64-bit native libraries support 16 KB memory pages | the `Android` workflow, after the `release` and `benchmark` builds |
| [map/](map/) | The map trial's build tool: fetches pinned Copernicus elevation, Water Body Mask and Natural Earth data and builds the relief (`relief.pmtiles`) and base (`base.pmtiles`) PMTiles archives in a toolchain image ([ADR 0009](../docs/adr/0009-map-data-pipeline.md) decision 13) | the owner, through `map/run.py`; the `map` workflow for its tests |
| [spike/](spike/) | Base map, glyph and content-update builders for the finished technical spike (reference only) | the `Spike APK` workflow, on demand |

New tools for v1 (for example the content build and validator from [ADR 0005](../docs/adr/0005-content-model-exploration-and-story.md)) get their own folder here, with a line in this table.
