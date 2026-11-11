# map-data

Map sources, kept separate from the app code.

```text
map-data/
  base/        simplified world base map (land, sea, natural features; no modern names, borders or roads) → PMTiles
  historical/  places, borders, landmarks with time states (GeoJSON), each with source and accuracy
  style/       map style, glyphs (fonts), sprites (icons), all bundled for offline use
```

Rules:

- Borders cover **states, kingdoms and major ruled regions** only. No tribal borders in v1.
- Borders change by swapping in the next reviewed shape. Never animate or interpolate between shapes.
- Detail exists only inside the reviewed Seerah coverage. Panning somewhere else never creates historical detail.

## Spike base map

`tools/spike/build_base.sh` builds two files into `map-data/dist/` (not committed):

| File | Content | Source |
| --- | --- | --- |
| `land.pmtiles` | World land outline, zoom 0–9 | Natural Earth, three scales, cut into 10-degree cells |
| `relief.pmtiles` | Generalized, painted relief for lat 21–25, lon 38–40, zoom 6–12, about 21 MB. The app fades it out from zoom 12.5 | Copernicus DEM GLO-30, smoothed |

It needs curl, GDAL, tippecanoe, and Python with Pillow, numpy and `pmtiles`. Copernicus DEM requires attribution; the wording must be added to the app before any release.
