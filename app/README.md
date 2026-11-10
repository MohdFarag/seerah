# app

The Android project. For now it holds only the **technical spike** (`:spike`), a throwaway app that tests the stack proposed in [ADR 0002](../docs/adr/0002-proposed-stack.md). The real app is scaffolded after the spike.

## What the spike shows today

- A MapLibre Native map with no network permission at all. Everything is bundled.
- The Hijra sample data from [`map-data/historical/spike`](../map-data/historical/spike): waypoints, candidate points and the approximate route line.
- Arabic labels drawn two ways, switchable on screen: pre-built glyph PBFs and the engine's `font-faces` path. Two fonts: IBM Plex Sans Arabic and Amiri.
- A column of test labels (diacritics, ﷺ, digits, mixed direction) in the sea area.

Not there yet: base map (coast, terrain), time states, opening an event, content updates.

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

Both install side by side. The `Spike APK` workflow also builds them and attaches them to the run as an artifact.

## Regenerating glyphs

```sh
F=map-data/style/fonts
python3 tools/spike/make_glyphs.py $F/IBMPlexSansArabic-Regular.ttf "PlexArabic-Regular" map-data/style/glyphs
python3 tools/spike/make_glyphs.py $F/Amiri-Regular.ttf "Amiri-Regular" map-data/style/glyphs $F/IBMPlexSansArabic-Regular.ttf
```
