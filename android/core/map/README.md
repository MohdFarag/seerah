# :core:map

The map module. It is the only module that may touch MapLibre ([ADR 0003](../../../docs/adr/0003-v1-architecture-and-modules.md)); the rest of the app sees plain data and the interfaces below.

## Public types (package `app.seerah.map`)

- `MapFiles`: the map files on the device.
- `MapStyleSpec`, `ReliefSource`, `ZoomRange`, `MapColours`, `RampStep`: how the style is built.
- `CameraLimits`: where the camera may go.
- `MapCamera`: moves the camera.
- `MapEvent`: rendering events (`FullyRendered`, `FrameRendered`).

The map screen and the style builder arrive in the next steps of the map trial ([plan](../../../docs/design/map-trial-phase-2-plan.md)). Until then the module holds only these types.

## Test

```sh
cd android && ./gradlew :core:map:check
```
