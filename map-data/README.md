# map-data

Map data, kept separate from the app code. Historical map data (`historical/`, `updates/`) is content: all rights reserved under the [content licence](../LICENSES/LicenseRef-Seerah-Content.txt) ([ADR 0006](../docs/adr/0006-open-source-code-and-closed-content.md)). The v1 map data pipeline for the Arabian Peninsula is decided in [ADR 0009](../docs/adr/0009-map-data-pipeline.md). The real map configuration and the terrain edits are private (the private repository's `map/` section). This folder holds the spike's map files, the synthetic example the map trial's tool is tested on (`example/`), and, later, the documented tile schema.

```text
map-data/
  historical/spike/  Hijra route, waypoints, time states and region labels used by the spike (GeoJSON)
  style/fonts/       Amiri and IBM Plex Sans Arabic, with their OFL licences
  style/glyphs/      pre-built glyph files for the spike's rejected glyph mode, kept for comparison
  updates/           the spike's test content update (manifest and zip); the installed spike APKs fetch it from this path
  example/           synthetic map trial example for CI (public)
  dist/              generated base map files and trial output (not committed)
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

`tools/map/run.py` writes trial builds to `dist/` by default, one folder per profile, variant and fingerprint:

```text
dist/<name>-<variant>-<fp8>/
  relief.pmtiles  the relief archive (carries "trial": true)
  base.pmtiles    the vector base archive: land and coast layers (carries "trial": true)
  metadata.json   says "trial": true
  report.json
```

This is trial output, never packaged or published. It is **not** `tile_schema` 1, the map package format, which is not defined yet.

## Spike base map

`tools/spike/build_base.sh` builds two files into `map-data/dist/` (not committed):

| File | Content | Source |
| --- | --- | --- |
| `land.pmtiles` | World land outline, zoom 0–9 | Natural Earth, three scales, cut into 10-degree cells |
| `relief.pmtiles` | Generalized, painted relief for lat 21–25, lon 38–40, zoom 6–12, about 21 MB. The app fades it out from zoom 12.5 | Copernicus DEM GLO-30, smoothed |

It needs curl, GDAL, tippecanoe, and Python with Pillow, numpy and `pmtiles`. Copernicus DEM requires attribution; the wording must be added to the app before any release.
