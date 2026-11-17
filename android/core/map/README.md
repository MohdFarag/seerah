# :core:map

The map module. It is the only module that may touch MapLibre ([ADR 0003](../../../docs/adr/0003-v1-architecture-and-modules.md)); the rest of the app sees plain data and the interfaces below.

## Public types (package `app.seerah.map`)

- `MapFiles`: the map files on the device.
- `MapStyleSpec`, `ReliefSource`, `ZoomRange`, `MapColours`, `RampStep`: how the style is built.
- `CameraLimits`: where the camera may go.
- `MapCamera`: moves the camera.
- `MapEvent`: rendering events (`FullyRendered`, `FrameRendered`).

## Style builder

`styleJson(files, spec)` (internal) turns `MapFiles` and a `MapStyleSpec` into the MapLibre style document: a land-coloured background and one relief layer that fades out over `reliefFade`. A `Painted` source is a raster layer; a `Value` source is a Terrarium raster-dem coloured by an elevation ramp, which must be in strictly ascending order (the builder fails fast otherwise). Its tests run under Robolectric because `org.json` is a stub on the plain JVM.

## The map: `SeerahMap`

`SeerahMap(files, style, limits, modifier, onCamera, onEvent)` is the module's one composable. It hosts a MapLibre Native `MapView` (OpenGL build, version pinned in the catalog) in an `AndroidView`:

- The view is created once; `MapLibre.getInstance` is called before it. The host's lifecycle (`LocalLifecycleOwner`) is forwarded to it, and it is destroyed when it leaves the composition.
- The style comes from the style builder above; a change of `files` or `style` reloads it. `limits` bound the camera target and the zoom.
- `onCamera` receives a `MapCamera` after every style load, the same instance each time. Its `animateTo` suspends until MapLibre's animation ends and throws `CancellationException` if it is cancelled, either by MapLibre or by the calling coroutine (which also stops the animation).
- `onEvent` receives `FullyRendered` when a render finishes fully and `FrameRendered` after every frame.

`SeerahMap` has no unit test: MapLibre's native view does not run under Robolectric. It is checked on devices in the map trial.

## What is not here yet

- The base map (`MapFiles.base`, the sea, land and coast layers) arrives in phase 3 of the map trial (D1 in the [plan](../../../docs/design/map-trial-phase-2-plan.md)).
- The module holds no trial code: the trial screen, its files and its measurements live in the app's `benchmark` build type.

## Test

```sh
cd android && ./gradlew :core:map:check
```
