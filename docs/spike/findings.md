# Spike findings

A running log of what the spike has shown on real devices. Each entry says what was tested and what was not.

## 2026-11-11: Arabic map labels, first device run

**Build:** `:spike` 0.1, MapLibre Native Android 13.7.1, **OpenGL** flavor. One phone, reported by the owner with screenshots. The Vulkan flavor and the other two devices have not been reported yet.

| Path | Result |
| --- | --- |
| Glyph PBFs, no diacritics | Works. Letters join, order is right, ﷺ and Arabic-Indic digits render, mixed Arabic and Latin digits keep their order. |
| Glyph PBFs, with diacritics | Fails. Marks sit beside the letters instead of on them, and they break the joining between letters. |
| `font-faces` (TTF shaped by the engine) | Fails in the test-label layer. Glyph order is reversed and joining forms are wrong, in both fonts. This matches the RTL bug reported upstream for iOS, now seen on Android 13.7.1. The waypoint-label layer looked unchanged in this mode, which is not explained yet. |

Other observations:

- Glyph edges were jagged. That came from this repo's glyph generator, not the engine. The generator now seeds the distance field from anti-aliased coverage; not re-tested on a device yet.
- Amiri has no arrow glyph, so "يثرب ← المدينة" lost its arrow. The Amiri stack now takes missing code points from Plex; not re-tested on a device yet.
- The style used for `font-faces` follows the assistant's reading of the property. A wrong configuration is still possible, so this result is not a final verdict on that path.

**Working conclusion** (to confirm on the other devices): draw map place names **without diacritics** through glyph PBFs. Keep vocalised text in the Compose UI, where the system text engine handles it. The few decorative names can be pre-rendered images.

## 2026-11-11: owner feedback on build 2

The owner reported, without device details: glyph quality is very poor, diacritics are still wrong, and `font-faces` still reverses words.

Changes made in response (built, **not yet run on a device**):

- **Glyph quality:** the generator now renders each glyph at four times the size, computes the distance field there and averages it down. The earlier field was computed from a 24 px bitmap, which loses thin strokes.
- **Third label path, "نص النظام":** each label is drawn by Android's own text engine into a bitmap and placed by the map as an icon. Shaping, diacritics and direction come from the system, and the map still handles collisions.

Known limits of the third path: a label cannot follow a curved line, each distinct label costs one bitmap in memory, and labels do not reflow when the user changes the system font size until the style is rebuilt.

Checked off-device after regenerating: a Plex glyph rendered back from its PBF is clean, but an Amiri glyph still shows a broken thin stroke. Amiri's hairlines are under one pixel at the 24 px size the glyph format uses, so the PBF path is a poor fit for Amiri regardless of the generator. Plex suits it.

## 2026-11-11: Vulkan run on Galaxy A54 (build 2)

Reported by the owner with screenshots. The screens show two label modes only, so this is build 2; build 3 with the system-drawn path has not been run yet. The owner also confirmed the first run was on the same Galaxy A54.

| Check | Result |
| --- | --- |
| Vulkan renderer on Galaxy A54 | Works. Map, route and markers draw correctly. |
| Glyph PBFs, no diacritics | Works in both fonts. Edges are now smooth, so the build 2 generator fix held on a device. The arrow in "يثرب ← المدينة" shows in Amiri. |
| Glyph PBFs, with diacritics | Still fails: marks are misplaced and break the joining. |
| `font-faces` | Fails in **both** layers on Vulkan: every label is reversed. This removes the earlier unexplained case where waypoint labels looked unaffected. |

Still open: build 3 on any device, and any run on the Oppo A55 or the Huawei tablet.

## 2026-11-11: build 3 on Galaxy A54 (OpenGL)

Reported by the owner with screenshots.

| Path | Result |
| --- | --- |
| System-drawn labels ("نص النظام"), Plex | Works. Diacritics sit on their letters, joining and direction are right, ﷺ, digits and the arrow render, and edges are sharp. |
| System-drawn labels, Amiri | Works. Amiri places its marks high, and the test lines were close enough that one line's marks touched the line above. That is test spacing, not a shaping fault; the lines are now further apart. |
| Glyph PBFs | Unchanged: good without diacritics, wrong with them. |
| `font-faces` | Unchanged: reversed. |

**Decision for the spike:** map labels are drawn by the system text engine and placed as images. Glyph PBFs stay as the fallback for unvocalised names that must follow a curved line. `font-faces` is dropped until the engine fixes right-to-left shaping.

Not yet run: build 3 on Vulkan, and anything on the Oppo A55 or the Huawei tablet.

## 2026-11-11: build 3 on Galaxy A54 (Vulkan)

Reported by the owner with screenshots. Same results as the OpenGL run: system-drawn labels are correct in Plex and Amiri, glyph PBFs are good only without diacritics, and `font-faces` reverses every label. The label decision above therefore holds on both renderers for this device.

Not yet run: anything on the Oppo A55 or the Huawei tablet.

## 2026-11-11: base map pipeline (build 4)

Built off-device; **not yet run on a device**.

- The owner approved installing the standard tools. GDAL 3.8.4, tippecanoe 2.49 and the Python `pmtiles` package build the base from Natural Earth and Copernicus DEM GLO-90.
- Sizes for the sample area: `land.pmtiles` 5.1 MB (whole world, zoom 0–9) and `relief.pmtiles` 26.2 MB (lat 21–25, lon 38–40, zoom 6–11, WebP). Relief is the size driver, as expected.
- A preview of the relief rendered off-device shows the Hijaz ranges and lava fields clearly.
- The APK bundles both files and copies them to app storage on first launch, because the engine cannot read PMTiles from assets.
- Natural Earth's finest scale is 1:10m, so the coast will look angular when zoomed in past the relief's detail. A finer coastline source is a later decision.

To check on a device: that both PMTiles sources load from storage, how the relief looks against the Penpot design, and panning smoothness on the Oppo A55.

## 2026-11-11: build 4 on Galaxy A54 (both renderers)

Reported by the owner with screenshots.

| Check | Result |
| --- | --- |
| PMTiles read from app storage | Works on Vulkan and OpenGL, for both the vector land and the raster relief. |
| Relief appearance | Reads well under the route and labels at regional zoom. |
| Land outline | **Bug:** at some zooms whole rectangles showed sea colour where there is land. |
| Zooming far in (Makkah streets) | Relief turns into blurred blobs. The 90 m data has no detail at that scale. |

Cause of the land bug, found off-device: tippecanoe dropped the largest polygons from some tiles when the three Natural Earth scales shared one input file with per-feature zoom ranges. The pipeline now cuts the finer scales into 10-degree cells, builds one tileset per scale and joins them. A check comparing every tile with its four children over lon 30–50, lat 14–32, zoom 2–8 (325 tiles) found no mismatches. **Not yet re-run on a device.**

Also changed: the map's maximum zoom is capped at 12.5 for now. Showing the Makkah sites, which are a few hundred metres apart, needs finer elevation data for the city areas or a different treatment at that scale. The zoom limit is the owner's open decision.

## 2026-11-11: build 5 on Galaxy A54, and finer relief (build 6)

Build 5, reported by the owner with screenshots (OpenGL): the land outline is now correct at every zoom shown, from the regional view down to the sample area. The owner asked for finer relief overall; at the zoom cap the 90 m relief was still blurred.

Build 6 switches the relief to Copernicus GLO-30. Built off-device; **not yet run on a device**.

| Relief for lat 21–25, lon 38–40 | Max tile zoom | Size |
| --- | --- | --- |
| GLO-90 | 11 | 26 MB |
| GLO-30, full transparency precision | 12 | 110 MB |
| GLO-30, transparency in 16 levels (used) | 12 | 71 MB |

- Lowering the WebP quality barely changes the size, because the transparency channel is stored losslessly and dominates it. Reducing transparency to 16 levels cuts about 40% with no visible difference in a side-by-side tile.
- Zoom 12 alone is about half the file. The zoom cap is now 13.5.
- The debug APK is about 143 MB. Roughly 80 MB of that is the base map; the rest is the unshrunk debug build.

This size is for a 2° × 4° area only. Covering all Seerah regions at this detail would be several hundred megabytes, so the real package needs one of: fine relief only along routes and around cities, the engine's own hillshading from compact elevation tiles, or a lower zoom cap outside key areas. Not decided.

## 2026-11-11: route data extended to waypoint 35 (build 7)

The owner's Notion log now holds route waypoints 1–35 (six of eight stages, up to Wadi Rim). The log itself states that the last 6 waypoints, in stages 7 and 8 and ending at Quba, are still missing. Build 7 carries the 35 waypoints; **not yet run on a device**. How the two-pair waypoints are read is recorded in `map-data/historical/spike/README.md`.

## 2026-11-11: full route, 41 waypoints (build 8)

The owner completed the Notion log. Build 8 carries all 41 route waypoints, ending at Quba; coordinates of waypoints 1–35 are unchanged from build 7. **Not yet run on a device.**

## 2026-11-11: build 8 on Galaxy A54, and zoom-14 relief (build 9)

Build 8 (Vulkan and OpenGL), reported by the owner: all 41 waypoints show, and the full route reads well at regional zoom. Close in, the 30 m relief at zoom 12 turned blocky. The owner asked for sharper relief and said package size is not a constraint, because the app will download content.

Build 9, built off-device and **not yet run on a device**:

- The DEM is upsampled smoothly (cubic spline) to the zoom-14 pixel size before shading, so close-up relief is smooth rather than blocky. This adds no detail beyond the 30 m source; there is no free finer elevation data for the region.
- Zoom 14 is kept within 12 km of the route, zoom 13 within 14 km, zoom 12 within 35 km, and zooms 6–11 everywhere in the area. The corridors were narrowed so the APK fits the 400 MB file-sharing limit.
- Transparency is back to full precision: at 16 levels it banded visibly at zoom 13 and above.
- `relief.pmtiles` is about 300 MB and the debug APK under 400 MB. The map zoom cap is 15.5.

## 2026-11-11: build 9 on Galaxy A54, and relief style

Build 9 (Vulkan), reported by the owner: relief near the route is smooth and readable at mid zoom, but at the closest zooms it turns into large soft blobs, and the owner finds the relief **too realistic** for the app's look. The owner asked for a scale bar and for examples of how historical map apps handle deep zoom.

- **Scale bar (build 10):** MapLibre Android has no built-in scale bar, so the spike draws one in Compose from the map projection, snapped to 1, 2 or 5 × 10ⁿ, with Arabic-Indic digits. Bottom-left, as in the Penpot design.
- **Relief style:** four styles were rendered off-device from the same 30 m data for the owner to choose: A (current, realistic), B (generalized, single light, elevation tint), C (generalized, shading in three soft bands, painted look), D (only the major ridges, very soft). Not decided.
- **Deep zoom:** the 30 m source cannot give real detail past about zoom 13. Options recorded for the owner: cap the zoom, fade the relief out at high zoom so the map turns to plain paper, use a generalized style that does not pretend to more detail, or switch to a plan or illustration at that scale, as the Penpot "No more detail at this zoom" screen already does.

## 2026-11-11: owner chose the painted relief (build 11)

The owner accepted the recommendation: style C (generalized terrain, shading in three soft bands), relief faded out between zoom 12.5 and 14.5 so close-up views are plain paper with names and route, and diagrams for the few places that need detail (Makkah sites, Badr, Uhud) as later design work.

Build 11: `relief.pmtiles` is now about 21 MB (zoom 6–12, no route corridor), down from about 312 MB. Map zoom cap is 16. **Not yet run on a device.**
