# 0002. App stack and map engine

**Status:** Accepted by the owner on 2026-11-13, after the technical spike. Results: [spike summary](../spike/summary.md).

## Context

v1 is Android only (phone and tablet), Arabic RTL and 2D. It needs a historical map and content that work offline from a package we distribute ourselves, content updates separate from app releases, and names, borders and landmarks that change over time. The app is free.

## Decision

- **UI:** Kotlin + Jetpack Compose (RTL and adaptive layouts).
- **Map:** MapLibre Native Android (BSD-2-Clause), 13.7.x, with the **OpenGL** renderer (`android-sdk-opengl`). A Vulkan build is kept to re-measure once real content is in.
- **Base map:** local **PMTiles** files, copied to device storage before use (MapLibre cannot read PMTiles from APK assets). Historical layers are GeoJSON. Style and icons ship with the content.
- **Arabic labels:** drawn by Android's text engine and passed to the map as images. MapLibre's glyph path (drops diacritics) and its `font-faces` option (reverses Arabic in 13.7.1) are not used for Arabic. Amiri for place names, IBM Plex Sans Arabic for the interface.
- **Storage:** SQLite/Room for content. User progress and settings are stored **separately** from the replaceable content files.
- **Time logic lives in the app:** each entity has a stable ID and time states. The app picks the reviewed state for the selected time (end of year in year view). No borders are interpolated between states; historical areas, if shown, are a soft tint, not a crisp line.
- **Updates:** a versioned package with a SHA-256 and compatibility check, resumable download, activated in one step only when complete. The working version is kept if the download or install fails. Content updates never reset user progress.

## Performance targets

Release build, steps in [measurements.md](../spike/measurements.md), Oppo A55 as the low-end reference.

| Measure | Low-end | Mid-range and tablet |
| --- | --- | --- |
| Cold open to first full map | ≤ 4 s | ≤ 1.5 s |
| Frame rate while panning and zooming | ≥ 55 fps typical, no 30-frame stretch below 20 fps | the screen's rate typical, lowest ≥ 45 fps at 60 Hz |
| Memory (PSS) on the map screen | ≤ 200 MB | ≤ 200 MB |

No package-size limit: content is downloaded.

## Alternatives considered

- **Flutter + maplibre_gl:** stronger only if iOS becomes a near-term goal.
- **Vulkan renderer:** same frame rate, but 60–110 MB more memory and a slower open on the low-end phone.
- **Mapbox:** its documented offline Tile Packs only support tilesets hosted on Mapbox, and pricing is per monthly active user.
- **Google Maps:** storage restrictions; not suited to a historical package we own.

## Consequences

- Every Arabic label is a bitmap: label images must be rebuilt when the font, size or content changes, and many labels cost memory. Watch this as content grows.
- Measure against the targets above in release builds only; debug builds are much slower.
- If a MapLibre release fixes Arabic shaping in `font-faces`, re-test it, since it would remove the label images.
