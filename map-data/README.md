# map-data

Map sources, kept separate from the app code.

```
map-data/
  base/        simplified world base map (land, sea, natural features; no modern names, borders or roads) → PMTiles
  historical/  places, borders, landmarks with time states (GeoJSON), each with source and accuracy
  style/       map style, glyphs (fonts), sprites (icons), all bundled for offline use
```

Rules:
- Borders cover **states, kingdoms and major ruled regions** only. No tribal borders in v1.
- Borders change by swapping in the next reviewed shape. Never animate or interpolate between shapes.
- Detail exists only inside the reviewed Seerah coverage. Panning somewhere else never creates historical detail.
