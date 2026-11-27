# :core:model

Pure-JVM domain and metadata models shared across modules ([ADR 0003](../../../docs/adr/0003-v1-architecture-and-modules.md)). It depends on no other project module and no Android or serialization framework.

## Public types (`app.seerah.model`)

- `GeoBounds`: WGS84 latitude/longitude bounding box within WebMercator bounds.
- `ReliefEncoding`: `TERRARIUM_VALUE` (`terrarium-value`, `png`) and `PAINTED_RGBA` (`painted-rgba`, `webp`).
- `BaseSourceMetadata`, `ReliefSourceMetadata`, `MapSourcesMetadata`: validated `data/map.json` archive descriptors.
- `MapCredit`: attribution and optional liability statement for a map data source.
- `MapMetadata`: validated `tile_schema: 1` map package metadata passed from `:core:content` into `:core:map`.

## Test

```sh
cd android && ./gradlew :core:model:check
```
