# Spike summary

**Status:** approved by the owner on 2026-11-13; [ADR 0002](../adr/0002-proposed-stack.md) is now Accepted.

The spike asked whether the stack in [ADR 0002](../adr/0002-proposed-stack.md) (Kotlin + Jetpack Compose, MapLibre Native Android, PMTiles base map, GeoJSON historical layers) can carry the app. The detailed log is in [findings.md](findings.md); the numbers are in [measurements.md](measurements.md).

## What was proven

| Roadmap item | Result |
| --- | --- |
| Simplified world base plus one detailed area | Works. World land from Natural Earth in three scales, painted relief for Makkah, Madinah and the Hijra route from Copernicus DEM, both as local PMTiles. |
| Arabic place names offline: joined letters, diacritics, digits, collisions | Works with **system-drawn labels**: names are drawn by Android's text engine and handed to the map as images. MapLibre's own glyph path drops diacritics, and its `font-faces` option reverses Arabic in 13.7.1; both rejected. Collisions are handled by layer order and zoom ranges. |
| Three time states: name change, border change, landmark appearing | Name change (يثرب → المدينة) and landmark (المسجد النبوي) work with cross-fades. The border example was dropped by the owner: historical borders will be a soft, blurred tint if shown at all, not a crisp line. |
| Opening an event restores time and place | Works (الهجرة، بناء المسجد النبوي، غزوة بدر). |
| Cold restart in airplane mode | Works. |
| Content update without a new app version, with interruption | Works: resume after stop, after closing the app and after airplane mode; SHA-256 check; one-step switch. |
| Size, smoothness and memory on a phone and a tablet | Measured on three devices, release builds. All smooth. |

## Measurements (release builds)

| Device | Renderer | Open | fps | Lowest | Memory |
| --- | --- | --- | --- | --- | --- |
| Oppo A55 | OpenGL | 3.5 s | 59 | 23 | 142 MB |
| Oppo A55 | Vulkan | 5.4 s | 60 | 39 | 205 MB |
| Galaxy A54 | OpenGL | 1.3 s | 60 | 56 | 128 MB |
| Galaxy A54 | Vulkan | 1.0 s | 60 | 45 | 241 MB |
| MatePad 11.5 | OpenGL | 0.8 s | 119 / 120 Hz | 89 | 88 MB |
| MatePad 11.5 | Vulkan | 1.0 s | 120 / 120 Hz | 63 | 181 MB |

One run per row, so treat single figures as indicative.

## Proposed performance targets for v1

Measured in a release build, with the same steps as [measurements.md](measurements.md), on the Oppo A55 as the low-end reference.

| Measure | Low-end (Oppo A55) | Mid-range and tablet |
| --- | --- | --- |
| Cold open to first full map | ≤ 4 s | ≤ 1.5 s |
| Frame rate while panning and zooming | ≥ 55 fps typical, no stretch of 30 frames below 20 fps | the screen's rate (60 or 120) typical, lowest ≥ 45 fps at 60 Hz |
| Memory (PSS) on the map screen | ≤ 200 MB | ≤ 200 MB |
| Base map and content download | no limit set; the owner has said size is not a concern because content is downloaded | |

These are set a little above what the spike reached, because the real app will carry more layers and content. If v1 misses one, the first suspects are the relief raster and the number of symbol layers.

## Renderer recommendation

**Ship OpenGL** (`android-sdk-opengl`) for v1 and keep a Vulkan build to re-measure once real content is in.

- OpenGL used 60–110 MB less memory on every device.
- It opened faster on the low-end phone (3.5 s vs 5.4 s), which is the device where open time matters most.
- Both reach the screen's full rate everywhere. Vulkan's only lead was the lowest fps on the Oppo (39 vs 23); on the A54 and the tablet OpenGL's lowest was better.

## Decisions made during the spike (to carry into v1)

- Labels: system-drawn, Amiri for place names, IBM Plex Sans Arabic for the interface.
- Zoom behaviour like Google Maps: cities first, smaller places as you zoom in, city names hidden when far out; relief fades out between zoom 12.5 and 14.5.
- Painted, generalised relief rather than realistic hillshade.
- No crisp historical borders.
- A route belongs to its year (the Hijra route shows in 1 هـ only); places stay.
- APKs are built by GitHub Actions.

## Still open (not blocking the stack decision)

- Place diagrams for Makkah sites, Badr and Uhud: design in Penpot first.
- The soft tint for areas of influence, when a reviewed example exists.
- The Badr point is spike-only (modern town position), not reviewed.
- Storage (SQLite/Room) and progress kept apart from content were not exercised by the spike; they are standard Android and carry little risk.

## Change to ADR 0002 (approved)

The status is now **Accepted**, recording:

- MapLibre Native Android 13.7.x with the **OpenGL** renderer; Vulkan kept as an alternative to re-measure.
- Arabic labels are drawn by the system text engine and passed to the map as images; MapLibre glyphs and `font-faces` are not used for Arabic.
- Base map as local PMTiles copied to device storage (MapLibre cannot read PMTiles from APK assets).
- The performance targets above.
