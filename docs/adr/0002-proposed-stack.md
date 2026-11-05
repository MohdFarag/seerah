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

## Spike results (2026-11-05)

See [`spike/README.md`](../../spike/README.md). Summary:

- **Verified without a device:** time-state logic, package verify/activate/rollback, resumable updates, progress kept across updates (21 tests), and package assets (PMTiles, glyphs, style) rendered in MapLibre GL JS. MapLibre Native 13.7.1 ships `PMTilesFileSource`, `file://`/`asset://` and ICU Arabic shaping.
- **Risk found:** Arabic diacritics on map labels are drawn without mark positioning (no HarfBuzz/GPOS). Letters join correctly. Event texts in Compose are unaffected.
- **Still unverified:** everything on a real Android device (rendering, airplane-mode restart, performance, memory). The cloud environment blocks `dl.google.com`, so no SDK or APK was available.
- **Adjustments:** content as JSON in the package for now (SQLite later), Room for user state only, GeoJSON for historical layers until they grow, and `maplibre/font-maker` for production glyphs.

Status stays **Proposed** until the device checklist in the spike README is run.
