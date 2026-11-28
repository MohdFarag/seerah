# :core:model

Pure-JVM domain and metadata models shared across modules ([ADR 0003](../../../docs/adr/0003-v1-architecture-and-modules.md)). It depends on no other project module and no Android or serialization framework.

## Public types (`app.seerah.model`)

- **Map metadata (`MapMetadata.kt`):** `GeoBounds`, `ReliefEncoding`, `BaseSourceMetadata`, `ReliefSourceMetadata`, `MapSourcesMetadata`, `MapCredit`, `MapMetadata`.
- **Timeline & dates (`TimelineModels.kt`):** `CalendarAxis`, `DatePrecision`, `ChronologicalKey`, `TimelinePoint` (no year zero across `PRE_HIJRA` and `HIJRI`), sealed `HistoricalDate`.
- **Geography & states (`GeoModels.kt`):** `GeoPoint`, `PlaceKind`, `KnownAccuracy`, `Visibility`, `DistanceBasis`, sealed `PlaceLocation` (`OnMap`, `OffMap`, `Unknown`), sealed `StateStart` (`Baseline`, `Dated`), `PlaceState`, `Place`, `AreaState`, `Area`, sealed `RouteGeometry`, `Route`.
- **Sources & texts (`TextModels.kt`):** `SourceKind`, `SourceWork`, `SourceCitation`, `TextKind`, `ContentText`.
- **Events & details (`EventModels.kt`):** `Phase`, `EventKind`, `EventDepth`, `ExpeditionRole`, `RelationType`, `PlaceReference`, `EventLocation`, `Situation`, `EventRelation`, `SeerahEvent`, `EventDetail`.
- **Story & corpus summaries (`StoryModels.kt`, `ContentMetadata.kt`):** `ShotSize`, `CameraTransition`, `MapTargetRef`, `StepCamera`, `StepTime`, `StepRouteDrawing`, `StepMovement`, `StepPanel`, `StoryStep`, `StoryScene`, `StoryChapter`, `Story`, `StorySummary`, `ContentPackageSummary`, `ExplorationCorpus`.
- **`FollowAlongBookmark`** identifies a saved story step by stable IDs and carries a fallback index for content changes.

## Test

```sh
cd android && ./gradlew :core:model:check
```
