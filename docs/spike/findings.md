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

Owner check on build 11 (Galaxy A54): painted relief, fade on zoom-in and scale bar all accepted.

## 2026-11-12: time states and year bar (build 12)

Built off-device; **not yet run on a device**.

- A year bar from 13 ق.هـ to 11 هـ (no year 0). The map shows the state at the end of the chosen year.
- Three tests from `time-states.geojson`: يثرب → المدينة at 1 هـ (name change), المسجد النبوي appears at 1 هـ (landmark), and a **sample** authority area around Madinah that grows to include Makkah at 8 هـ (border change, a placeholder shape).
- Changing the year only swaps layer filters; the style is not reloaded, so the map should not flicker.

To check on a device: that each change happens exactly at its year, that dragging the bar stays smooth, and that labels in each mode still render correctly.

## 2026-11-12: build 12 feedback, detail by zoom (build 13)

Owner feedback on build 12 (OpenGL, Galaxy A54): the year changes worked, but the map showed everything at every zoom. Route dots stayed large when zoomed out, and the sample area's name repeated many times.

Build 13 (not yet run on a device):

- **Detail by zoom.** City names (مكة، المدينة) show at every zoom. Key stations (Thawr, Umm Ma'bad, Suraqa, al-Juhfa, al-Arj, Quba) appear from zoom 6.5, other stations from 9, the Makkah houses from 12.5; each label appears about one zoom after its dot. The Prophet's Mosque appears from zoom 11.
- Dots and the route line scale with zoom instead of keeping a fixed size.
- **Repeated area name:** the engine labels a polygon once per tile, so the name was drawn in every tile. It now comes from one point per state, shown between zoom 5 and 10.
- The test labels start hidden.

The tiers are set in the data (`tier` on each waypoint) so content review can move a place between them.

## 2026-11-12: build 13 feedback, zoom ladder and fades (build 14)

Owner feedback on build 13 (Vulkan, Galaxy A54): the Makkah houses needed too much zoom; dots appeared without their names; year changes snapped instead of moving smoothly; the Madinah name disappeared and reappeared while zooming and covered the Prophet's Mosque up close, while the mosque appeared and then vanished; and the Hijra route should belong to its year, not show in every year.

Build 14 (not yet run on a device):

- **Marker and name are one symbol.** In glyph modes the dot is the symbol's icon; in system-text mode the dot is drawn into the label image. A marker never shows without its name.
- **Zoom ladder:** cities 0–11.5, key stations from 6.5, other stations from 8.5, landmarks and the Makkah houses from 11.
- **Collision priority:** layers are ordered from least to most important, cities last, so a city name wins over stations and no longer flickers. City names stop at zoom 11.5, so up close they no longer cover the landmarks inside them.
- **Year changes cross-fade** over 450 ms: each time-dependent feature has its own layer whose opacity is animated, instead of a filter that switches instantly.
- **The route belongs to 1 هـ**: it shows only at that year. Waypoints show in every year.

## 2026-11-12: build 14 feedback, Google Maps-style labels (build 15)

Owner feedback on build 14 (Vulkan, Galaxy A54), compared side by side with Google Earth: al-Usba appeared, vanished and reappeared while zooming in; dots were still too big; city markers missing; names sat far below their dots, sometimes nearer another dot. The owner wants zoom behaviour like Google Maps.

How Google Maps does it, and what build 15 copies (not yet run on a device):

- **Small dots, name right beside the dot.** Dots are now 3.5 dp (cities 4.5 dp, dark). In system-text mode the dot and name are one image with the name just to the right of the dot; in glyph modes the engine tries right, left, above, then below.
- **Every city has a dot**, with a larger name.
- **A label hides only when it collides, and the more important one wins.** Al-Usba disappeared because the large "يثرب" label, placed above its point with wide padding, overlapped it at that zoom; with tight labels the collision zone is much smaller. The engine still hides a lower-priority label when two genuinely overlap, as Google does.
- Label images no longer carry font padding, which made each label's collision box taller than the text.

Owner screenshots of build 15 (Vulkan, Galaxy A54, system text, Amiri), no written comment: small dots with names beside them read cleanly from world view down to the Madinah mosque; key stations show at regional zoom and minor ones only closer in; al-Usba and Quba sit side by side without hiding each other. At world zoom only "المدينة" shows, because it collides with "مكة" and wins.

Owner decisions for the next build (2026-11-12), not yet implemented:

- Drop the "نطاق سلطة المدينة" sample and its name. Historical areas of influence must not look like modern borders: no crisp line. Show them as a soft, blurred tint (or another imprecise treatment) instead.
- Hide all city names when zoomed far out (below about zoom 4).

## 2026-11-12: opening an event (build 16)

Built off-device; **not yet run on a device**.

- Three event chips above the year bar: الهجرة، بناء المسجد النبوي، غزوة بدر. Tapping one moves the year bar to the event's year and flies the camera to its place over 1.8 s (the whole route for the Hijra, the mosque up close, Badr at zoom 11.5).
- Badr appears from 2 هـ. Its point is the modern town from Wikipedia (23.78, 38.7906), marked for the spike only, not a reviewed battlefield point.
- The two decisions above are applied: the sample area of influence and its name are gone, and city names hide below zoom 4.

## 2026-11-12: build 16 feedback (build 17)

Owner feedback on build 16 (OpenGL, Galaxy A54): opening an event works and the camera move is smooth, but a little fast; "يثرب" never showed before the Hijra; the Hijra line stayed visible when zoomed far out; and "الهجرة" did not frame the whole route, part of it sat under the panels.

Build 17 (not yet run on a device):

- **يثرب before the Hijra.** Cause: a symbol layer faded to zero opacity still takes part in label collisions, so the invisible "المدينة" at the same point always won. Faded-out layers are now switched off at the end of the fade and switched on before a fade-in.
- The Hijra line now starts at zoom 5, like the stations.
- Camera moves take 3 s (was 1.8 s); year cross-fades take 0.7 s (was 0.45 s).
- "الهجرة" frames the route inside the area left clear by the top panel and the bottom bar.

Testing note: this container has no hardware virtualisation (no /dev/kvm), so an Android emulator cannot run here; device checks stay with the owner.

## 2026-11-12: content update with interruption (build 18)

Built off-device; **not yet run on a device**.

- A chip "تحديث المحتوى · ن١" downloads a content update from the repository (`map-data/updates/manifest.json` and `content-2.zip`, built by `tools/spike/make_update.py`). The update adds "مسجد قباء" from 1 هـ, so its arrival is visible on the map.
- The download is deliberately slowed (about 1.3 KB/s, spike only) so it can be interrupted: tap the chip again to stop. The partial file stays; the next tap resumes with an HTTP Range request from where it stopped.
- After the download, the SHA-256 is checked against the manifest, the zip is unpacked into a new folder, and one small pointer file is replaced to switch to it. Until that step the app keeps using the current content. The map reloads with the new content without restarting the app.
- The app now has the INTERNET permission, used only for this update. The map and content still work with no network.

To check on a device: interrupt and resume (including by force-closing the app or turning on airplane mode mid-download), then restart the app in airplane mode and confirm مسجد قباء is still there.

Owner (2026-11-12): the Hijra route framing issue is fixed in build 17; no further change needed.
