# tools

Scripts that check and build the repository's data. Run them from the repository root.

| Script | What it does | Run by |
| --- | --- | --- |
| [validate_content.py](validate_content.py) | Checks content against the JSON Schema and cross-references (IDs, phases, approval rules): the synthetic example by default, or a `seerah-content` checkout given as an argument | pre-commit and CI here; CI in `seerah-content` |
| [android/check_no_trial_code.py](android/check_no_trial_code.py) | Verifies with `aapt2` that a release APK's manifest declares no `app.seerah.trial` trial component, failing if unreadable ([ADR 0007](../docs/adr/0007-content-formats-compatibility-and-signing.md) decision 10) | the `Android` workflow, after the release build (with unit tests in `android/tests/`) |
| [android/check_page_alignment.py](android/check_page_alignment.py) | Checks that an APK's 64-bit native libraries support 16 KB memory pages | the `Android` workflow, after the `release` and `benchmark` builds |
| [map/](map/) | The map trial and production v1 map build tool: fetches pinned Copernicus elevation, Water Body Mask and Natural Earth data, applies coastal cleanup edits, and builds the relief (`relief.pmtiles`) and base (`base.pmtiles`) PMTiles archives and `data/map.json` (`tile_schema: 1`) in a toolchain image ([ADR 0009](../docs/adr/0009-map-data-pipeline.md)) | the owner, through `map/run.py`; the `map` workflow for its tests |
| [content/](content/README.md) | Reads v1 YAML authoring files with strict YAML 1.2 rules | CI and local content tool tests |
| [packages/](packages/) | Shared host-side packaging and offline Ed25519 manifest signing (`keygen`, `pack-map`, `verify`) for `content` and `map` packages ([ADR 0007](../docs/adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../docs/adr/0008-update-hosting-and-index.md), [ADR 0009](../docs/adr/0009-map-data-pipeline.md)) | the owner on the host; the `CI` workflow (`packages` job) for its tests |
| [spike/](spike/) | Base map, glyph and content-update builders for the finished technical spike (reference only) | the `Spike APK` workflow, on demand |

New tools for v1 (for example the content build and validator from [ADR 0005](../docs/adr/0005-content-model-exploration-and-story.md)) get their own folder here, with a line in this table.
