# :core:map

The map module. It is the only module that may touch MapLibre ([ADR 0003](../../../docs/adr/0003-v1-architecture-and-modules.md)); the rest of the app sees plain data and the interfaces below.

## Public types (package `app.seerah.map`)

- `MapFiles`: the map files on the device.
- `MapStyleSpec`, `ReliefSource`, `ZoomRange`, `MapColours`, `RampStep`: how the style is built.
- `CameraLimits`: where the camera may go.
- `MapCamera`: moves the camera.
- `MapEvent`: rendering events (`FullyRendered`, `FrameRendered`).

## Style builder

`styleJson(files, spec)` (internal) turns `MapFiles` and a `MapStyleSpec` into the MapLibre style document: a sea-coloured background, the `base` vector source's `land` fill and `coast` line layers, and one `relief` layer on top that fades out over `reliefFade`. A `Painted` source is a raster layer; a `Value` source is a Terrarium raster-dem coloured by an elevation ramp, which must be in strictly ascending order (the builder fails fast otherwise). Its tests run under Robolectric because `org.json` is a stub on the plain JVM.

## The map: `SeerahMap`

`SeerahMap(files, style, limits, modifier, onCamera, onEvent)` is the module's one composable. It hosts a MapLibre Native `MapView` (OpenGL build, version pinned in the catalog) in an `AndroidView`:

- The view is created once; `MapLibre.getInstance` is called before it. The host's lifecycle (`LocalLifecycleOwner`) is forwarded to it; if the lifecycle owner changes, the view is stepped down and follows the new one. Low-memory warnings are forwarded too. It is destroyed only when it leaves the composition, and no callback reaches the caller after that.
- The style comes from the style builder above; a change of `files` or `style` reloads it. `limits` bound the camera target and the zoom.
- `onCamera` receives a `MapCamera` after every style load, the same instance each time. Its `animateTo` runs on the main thread whatever the caller's dispatcher, suspends until MapLibre's animation ends, and ends with `CancellationException` (not an error) if it is cancelled: by the calling coroutine (which also stops the animation) or by MapLibre, for example when a gesture interrupts a camera path.
- `onEvent` receives `FullyRendered` when a render finishes fully and `FrameRendered` after every frame.

`SeerahMap` has no unit test: MapLibre's native view does not run under Robolectric. It is checked on devices in the map trial.

## What is not here

- The module holds no trial code: the trial screen, its files and its measurements live in the app's `benchmark` build type.

## Test

```sh
cd android && ./gradlew :core:map:check
```
