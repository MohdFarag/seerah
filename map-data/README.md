# map-data

Map data, kept separate from the app code. Historical map data (`historical/`, `updates/`) is content: all rights reserved under the [content licence](../LICENSES/LicenseRef-Seerah-Content.txt) ([ADR 0006](../docs/adr/0006-open-source-code-and-closed-content.md)). Today everything here belongs to the finished technical spike; the v1 map data pipeline for the Arabian Peninsula is proposed in [ADR 0009](../docs/adr/0009-map-data-pipeline.md) and gets its own folders.

```text
map-data/
  historical/spike/  Hijra route, waypoints, time states and region labels used by the spike (GeoJSON)
  style/fonts/       Amiri and IBM Plex Sans Arabic, with their OFL licences
  style/glyphs/      pre-built glyph files for the spike's rejected glyph mode, kept for comparison
  updates/           the spike's test content update (manifest and zip); the installed spike APKs fetch it from this path
  dist/              generated base map files (not committed)
```

Rules for all map data (from the PRD):

- Borders cover **states, kingdoms and major ruled regions** only. No tribal borders in v1. Historical areas, if shown, are a soft tint, never a crisp line (ADR 0005).
- Borders change by swapping in the next reviewed shape. Never animate or interpolate between shapes.
- Detail exists only inside the reviewed coverage. Panning somewhere else never creates historical detail.
- Every place and route carries its source and its accuracy; nothing is placed from a guess.

## Spike base map

`tools/spike/build_base.sh` builds two files into `map-data/dist/` (not committed):

| File | Content | Source |
| --- | --- | --- |
| `land.pmtiles` | World land outline, zoom 0–9 | Natural Earth, three scales, cut into 10-degree cells |
| `relief.pmtiles` | Generalized, painted relief for lat 21–25, lon 38–40, zoom 6–12, about 21 MB. The app fades it out from zoom 12.5 | Copernicus DEM GLO-30, smoothed |

It needs curl, GDAL, tippecanoe, and Python with Pillow, numpy and `pmtiles`. Copernicus DEM requires attribution; the wording must be added to the app before any release.
