# 0002. App stack and map engine

**Status:** Proposed. This is the assistant's recommendation in PRD v0.2 and has **not** been approved or tested on a device.

## Context

v1 is Android only (phone and tablet), Arabic RTL and 2D. It needs a historical map and content that work offline from a package we distribute ourselves, content updates separate from app releases, and names, borders and landmarks that change over time. The app is free.

## Proposal

- **UI:** Kotlin + Jetpack Compose (RTL and adaptive layouts). Alternative: Flutter + maplibre_gl, which becomes stronger if iOS becomes a near-term goal.
- **Map:** MapLibre Native Android (BSD-2-Clause). Base map from a local **PMTiles** file. Historical layers as GeoJSON in the sample. Style, glyphs (fonts) and sprites (icons) all ship inside the package.
- **Storage:** SQLite/Room for content. User progress and settings are stored **separately** from the replaceable content files.
- **Time logic lives in the app:** each entity has a stable ID and time states. The app picks the reviewed state for the selected time (end of year in year view). No borders are interpolated between states.
- **Updates:** a versioned package with an integrity and compatibility check, activated in one step only when complete. The working version is kept if the download or install fails. Content updates never reset user progress.

## Alternatives considered

- **Mapbox:** works technically, but its documented offline Tile Packs only support tilesets hosted on Mapbox, and pricing is per monthly active user.
- **Google Maps:** storage restrictions; not suited to a historical package we own.

## Consequences

The spike (see [roadmap](../roadmap.md)) must prove Arabic label rendering in MapLibre **Android** specifically. RTL results in Compose don't carry over to the map engine, and GL JS documentation doesn't apply to Native.
