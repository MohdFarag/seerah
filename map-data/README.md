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
| `relief.pmtiles` | Shaded relief for lat 21–25, lon 38–40: zoom 6–12 everywhere, 13 within 40 km of the route, 14 within 12 km; about 540 MB | Copernicus DEM GLO-30, upsampled to the zoom-14 pixel size |

It needs curl, GDAL, tippecanoe, and Python with Pillow, numpy and `pmtiles`. Copernicus DEM requires attribution; the wording must be added to the app before any release.
