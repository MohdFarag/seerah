# :core:domain

Pure-JVM time resolution, certainty disclosure, follow-along playback, and map snapshot engine ([ADR 0003](../../../docs/adr/0003-v1-architecture-and-modules.md), [ADR 0005](../../../docs/adr/0005-content-model-exploration-and-story.md)). Uses `seerah.jvm.library` and depends strictly on `:core:model` (forbidden from depending on `:core:content`, `:core:userdata`, or Android APIs).

## Public API (`app.seerah.domain`)

- **`TimelineAxis` & `YearBucket`:** Canonical Hijri / pre-Hijra year buckets without year zero, sourced month discovery, empty-year prev/next navigation, and precision-preserving event target lookup.
- **`TimeStateResolver` & `TimelineQuery`:** Baseline and dated entity state resolution (year-end vs. sourced-month upper bounds), `always` vs. `in-context` visibility across nested child events, non-piling year markers suppressing `Unknown` and off-map items, and documented route slicing.
- **`CertaintyNoteResolver` & `CertaintyNote`:** Automatic typed disclosures for year-only dates, approximate/unknown/disputed dates, general/unknown/off-map places, undocumented routes, and estimated distances.
- **`FollowAlongEngine` and `FollowAlongState`** use the shared `FollowAlongBookmark` from `:core:model` for manual step navigation and suspended resume after relaunch. Exploration can suspend follow-along without moving the bookmark. Moving it to another event requires an explicit action.
- **`MapSnapshotBuilder`, `ResolvedMapSnapshot`, `SnapshotTime`:** Unifies exploration queries and directed story steps into one shared visibility snapshot.

## Test

```sh
cd android && ./gradlew :core:domain:check
```
