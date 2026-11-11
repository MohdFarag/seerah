# app

The Android project. For now it holds only the **technical spike** (`:spike`), a throwaway app that tests the stack proposed in [ADR 0002](../docs/adr/0002-proposed-stack.md). The real app is scaffolded after the spike.

## What the spike shows today

- A MapLibre Native map with no network permission at all. Everything is bundled.
- The Hijra sample data from [`map-data/historical/spike`](../map-data/historical/spike): waypoints, candidate points and the approximate route line.
- Arabic labels drawn three ways, switchable on screen: pre-built glyph PBFs, the engine's `font-faces` path, and labels drawn by Android's text engine and placed as images. Two fonts: IBM Plex Sans Arabic and Amiri.
- A column of test labels (diacritics, ﷺ, digits, mixed direction) in the sea area.

- A base map from local PMTiles: world land outline and shaded relief for the sample area, when `tools/spike/build_base.sh` has been run before the build.

Not there yet: time states, opening an event, content updates.

## Build

Needs JDK 17+ and the Android SDK (platform 36).

```sh
cd app
./gradlew :spike:assembleDebug
```

This produces one APK per map renderer under `spike/build/outputs/apk/`:

| Flavor | Renderer | App id |
| --- | --- | --- |
| `vulkan` | Vulkan (MapLibre default since 13.0) | `app.seerah.spike.vulkan` |
| `opengl` | OpenGL ES | `app.seerah.spike.opengl` |

Both install side by side.

**From GitHub:** every push to `main` or a `claude/**` branch that touches the app, map data or spike tools runs the `Spike APK` workflow. It builds the base map (cached between runs), then the two APKs, and attaches them to the run as `spike-vulkan` and `spike-opengl`. Open the run under the repository's **Actions** tab and download them from **Artifacts**. A first run without the cache takes about 20 minutes.

## Regenerating glyphs

```sh
F=map-data/style/fonts
python3 tools/spike/make_glyphs.py $F/IBMPlexSansArabic-Regular.ttf "PlexArabic-Regular" map-data/style/glyphs
python3 tools/spike/make_glyphs.py $F/Amiri-Regular.ttf "Amiri-Regular" map-data/style/glyphs $F/IBMPlexSansArabic-Regular.ttf
```
