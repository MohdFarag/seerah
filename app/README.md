# app

The Android project (Gradle). It holds one module today:

| Module | Status | Purpose |
| --- | --- | --- |
| `:spike` | Finished, reference only | The technical spike that proved the stack in [ADR 0002](../docs/adr/0002-proposed-stack.md). Results: [summary](../docs/spike/summary.md), [findings](../docs/spike/findings.md). |

The v1 app is written **from scratch** in new modules, following [docs/engineering.md](../docs/engineering.md). The spike shows what works and what failed; its code is not copied into v1. It is removed once v1 covers what it shows.

## The spike

What it proved: Arabic map labels drawn by the system text engine, a PMTiles base map with painted relief, time states with cross-fades, opening an event, offline cold start, an interruptible content update, and performance on three devices.

### Build

Needs JDK 21 and the Android SDK (platform 36). The base map must be built first with `tools/spike/build_base.sh` (GDAL, tippecanoe, Python with numpy and `pmtiles`).

```sh
cd app
./gradlew :spike:assembleDebug :spike:assembleRelease
```

One APK per renderer and build type under `spike/build/outputs/apk/`:

| Flavor | Renderer | App id (debug / release) |
| --- | --- | --- |
| `vulkan` | Vulkan | `app.seerah.spike.vulkan` / `….release` |
| `opengl` | OpenGL ES (the v1 choice) | `app.seerah.spike.opengl` / `….release` |

All four install side by side. The release build is minified with R8 and signed with the debug key; use it for measurements.

**From GitHub:** every push to `main` or a `claude/**` branch that touches the app, map data or spike tools runs the `Spike APK` workflow. It builds the base map (cached between runs), then the APKs, and attaches them to the run as `spike-vulkan` and `spike-opengl`. Download them from the run's **Artifacts** on the **Actions** tab. A run without the cache takes about 20 minutes.

### Regenerating glyphs

The spike's glyph mode (rejected for Arabic, kept for comparison) uses pre-built glyph files:

```sh
F=map-data/style/fonts
python3 tools/spike/make_glyphs.py $F/IBMPlexSansArabic-Regular.ttf "PlexArabic-Regular" map-data/style/glyphs
python3 tools/spike/make_glyphs.py $F/Amiri-Regular.ttf "Amiri-Regular" map-data/style/glyphs $F/IBMPlexSansArabic-Regular.ttf
```
