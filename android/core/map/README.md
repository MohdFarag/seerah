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

The map screen arrives in the next steps of the map trial ([plan](../../../docs/design/map-trial-phase-2-plan.md)).

## Test

```sh
cd android && ./gradlew :core:map:check
```
