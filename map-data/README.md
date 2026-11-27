# map-data

Map data, kept separate from the app code. Historical map data (`historical/`, `updates/`) is content: all rights reserved under the [content licence](../LICENSES/LicenseRef-Seerah-Content.txt) ([ADR 0006](../docs/adr/0006-open-source-code-and-closed-content.md)). The v1 map data pipeline for the Arabian Peninsula is decided in [ADR 0009](../docs/adr/0009-map-data-pipeline.md). The real map configuration and the terrain edits are private (the private repository's `map/` section). This folder holds the spike's map files, the synthetic examples the map tool is tested on (`example/` and `example-production/`), and the public `tile_schema: 1` producer schema (`schema/map.schema.json`).

```text
map-data/
  historical/spike/    Hijra route, waypoints, time states and region labels used by the spike (GeoJSON)
  style/fonts/         Amiri and IBM Plex Sans Arabic, with their OFL licences
  style/glyphs/        pre-built glyph files for the spike's rejected glyph mode, kept for comparison
  updates/             the spike's test content update (manifest and zip); the installed spike APKs fetch it from this path
  schema/              public JSON Schema for data/map.json (tile_schema: 1)
  example/             synthetic map trial example for CI (public)
  example-production/  synthetic production map example for CI (public)
  dist/                generated base map files, trial output and production staging output (not committed)
```

Rules for all map data (from the PRD):

- Borders cover **states, kingdoms and major ruled regions** only. No tribal borders in v1. Historical areas, if shown, are a soft tint, never a crisp line (ADR 0005).
- Borders change by swapping in the next reviewed shape. Never animate or interpolate between shapes.
- Detail exists only inside the reviewed coverage. Panning somewhere else never creates historical detail.
- Every place and route carries its source and its accuracy; nothing is placed from a guess.

## The map trial's example (`example/`)

A synthetic profile for [tools/map](../tools/map/README.md), used by its tests and the `map` workflow: a window of 20.50–20.75° N, 58.80–59.05° E off Masirah Island, Oman, away from any Seerah event or named historical place (decision D1 of the [phase 1 plan](../docs/design/map-trial-phase-1-plan.md)). It spans cell N20 E058 and its absent all-sea neighbour N20 E059.

| File | Content |
| --- | --- |
| `example.yaml` | the profile (`purpose: trial`) |
| `edits.yaml` | one synthetic `raise` edit crossing a block edge, approved by the owner for the example |
| `example.lock` | the pinned cells and Natural Earth files, with their SHA-256 |
| `peninsula.geojson` | the outline for the run report's inside/outside size split |
| `camera-path.json` | the camera path the trial app reads |

## Map trial output

`tools/map/run.py` writes trial builds to `dist/` by default, one folder per profile, variant, mode (`--mode per-zoom` adds `-per-zoom` after the variant) and fingerprint:

```text
dist/<name>-<variant>-<fp8>/           # or dist/<name>-<variant>-per-zoom-<fp8>/ for --mode per-zoom
  relief.pmtiles  the relief archive (carries "trial": true)
  base.pmtiles    the vector base archive: land and coast layers (carries "trial": true)
  metadata.json   says "trial": true
  report.json
```

This is trial output, never packaged or published. It is **not** `tile_schema` 1.

## The production map example (`example-production/`) and `tile_schema: 1` staging

`example-production/` is the synthetic `purpose: production` fixture for CI, covering the same Masirah Island window (`min_data_margin_deg: 0`, tested with `--allow-zero-margin`):

| File | Content |
| --- | --- |
| `coverage.yaml` | production coverage configuration (`purpose: production`, `relief_encoding: painted-rgba`) |
| `cleanup.yaml` | one approved `remove-land` polygon crossing a block edge and one `draft` `fill-water` polygon (skipped by builds) |
| `sources.lock` | pinned Copernicus DEM and Natural Earth files with SHA-256 checksums |

`python3 tools/map/run.py -- build --coverage <rel>` stages a `tile_schema: 1` production map directory into `dist/map-staging-<fp8>/`:

```text
dist/map-staging-<fp8>/
  data/
    base.pmtiles    vector base archive ("tile_schema": 1, no "trial" flag)
    relief.pmtiles  relief archive ("tile_schema": 1, no "trial" flag)
    map.json        validated against map-data/schema/map.schema.json
  report.json       build provenance, timings, tile counts and applied/skipped cleanup IDs
```

### `tile_schema` 1

- **`data/base.pmtiles`:** PMTiles v3 archive of gzip-compressed MVT vector tiles across build zooms `0..regional_max_zoom`, containing `land` (`MultiPolygon`) and `coast` (`MultiLineString`) with empty feature properties (`fields: {}`), plus reserved optional layer `valley` (absent in v1; `tile_schema: 1` readers accept `{"land", "coast"} <= layer_ids`).
- **`data/relief.pmtiles`:** PMTiles v3 archive of uncompressed `256×256` raster tiles across build zooms `0..regional_max_zoom` (every tile covering `data_extent` is present with zero skipped blank tiles), encoded as `terrarium-value` (`png`) or `painted-rgba` (`webp`). Reserved optional archive `data/relief-detail.pmtiles` is absent in v1.
- **PMTiles v3 binary header and JSON metadata:** the binary header stores `min_zoom = 0`, `max_zoom = regional_max_zoom`, tile type and compression, and bounds (`relief.pmtiles` bounds match `data_extent`). JSON metadata carries `"name": "seerah-map"`, `"tile_schema": 1`, `"attribution"`, `"fingerprint"`, `"tool_commit"`, `"data_commit"`, and `"image"`, plus `"encoding"` on `relief.pmtiles` or `"vector_layers"` on `base.pmtiles`, with no `"trial"`, `"variant"`, or `"mode"` keys.

## Spike base map

`tools/spike/build_base.sh` builds two files into `map-data/dist/` (not committed):

| File | Content | Source |
| --- | --- | --- |
| `land.pmtiles` | World land outline, zoom 0–9 | Natural Earth, three scales, cut into 10-degree cells |
| `relief.pmtiles` | Generalized, painted relief for lat 21–25, lon 38–40, zoom 6–12, about 21 MB. The app fades it out from zoom 12.5 | Copernicus DEM GLO-30, smoothed |

It needs curl, GDAL, tippecanoe, and Python with Pillow, numpy and `pmtiles`. Copernicus DEM requires attribution; the wording must be added to the app before any release.
