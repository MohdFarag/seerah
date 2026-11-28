# v1 user data and explore session

**Status** — Approved by the owner on 2026-11-28.

## Goal

An offline restart can interrupt exploration, and a package update can replace the current content database. Save the explore session separately so the user can return to the last explored time and place. If the saved bookmark resolves, the app offers to resume the follow-along. It resumes only when the user chooses Resume, and each step remains manual.

This milestone builds the local session store and tests its contract. The map, index, and reader will connect to it when those features are built. The v1 requirements call for settings to be stored locally. Add each setting when its behavior is defined by the corresponding feature. This milestone has no setting to write. See [ADR 0003](../adr/0003-v1-architecture-and-modules.md), the [requirements](../requirements.md), and [open decisions](../open-decisions.md).

## What the app remembers

| Value | What it represents |
| --- | --- |
| Selected time | The canonical timeline point, including its original year, month, or day precision. A new session starts without a saved time. |
| Focus | A stable place ID or event ID. A new session starts without a saved focus. |
| Follow-along bookmark | The existing story, chapter, scene, and step IDs, plus the engine's fallback step index. |

The recorded time and focus describe exploration. They can change independently of the bookmark. The app can save a time and a focus together when opening an event. Moving the bookmark follows the existing follow-along rules. A bookmark on disk carries no playback status. Camera gestures, animation state, and navigation history belong to the UI.

## Restoring a session

The store reads and writes user data without opening the content database. At integration, one app-shell restore path waits for a ready exploration snapshot. It uses the timeline's selectable years, including years without events. It restores month or day precision only if that point still has the same documented precision in the active content. If that precision is gone but the year is selectable, it restores the year alone.

Use the same exploration snapshot to resolve focus by ID and check that it can be shown at the restored time. A recorded focus action includes its selected time. If an event moved to another year or its focus is otherwise unavailable, restore the supported saved time on its own. If the saved year is unavailable or no time was saved, the app shell supplies its startup view without the saved focus. The startup view's actual time and place remain the shell's responsibility. A restore never rewrites the saved values.

The restore path loads only the saved bookmark's story. The [follow-along engine](../../android/core/domain/src/main/kotlin/app/seerah/domain/FollowAlongEngine.kt) resolves the bookmark by step ID. If that step disappeared, the engine clamps the saved index to the surviving scene, or to the story when the scene disappeared. A missing story gives no resume offer. A resolved bookmark returns a suspended session independently of the restored exploration position. The store changes the saved values when a caller records a new action.

## Storage choice

| Option | Fit |
| --- | --- |
| Typed DataStore with JSON | Keeps the session together and supports atomic updates. Uses the existing Kotlin serialization toolchain. This follows ADR 0003. |
| Proto DataStore | Gives the store a separate schema and generated types. Google's Now in Android uses this for its preferences. This project would add a Protobuf toolchain for one small local record. |
| Separate Room database | Useful for large sets of records, queries, and partial row updates. This session is one bounded record and has no queries. |

Android's DataStore guidance supports typed JSON, an observable data stream, and atomic updates. The file is independent of the Room content database. The current [backup rules](../../android/app/src/main/res/xml/backup_rules.xml) and [data transfer rules](../../android/app/src/main/res/xml/data_extraction_rules.xml) exclude the content directory. The proposed session file is eligible for Android cloud backup and device transfer when the device permits it. The product decision on [backup and restore after reinstall](../open-decisions.md) remains open.

## Failure cases

| Case | Required behavior |
| --- | --- |
| Fresh install with no file | Return an empty saved session. A caller can tell this apart from a failed read. |
| Concurrent writes | Apply each change to the most recent saved session. One action can write several related fields in one transaction. |
| File read or write fails | Report a typed storage failure. A failed read emits the failure once and ends that collection. The caller can retain its last known session and retry by collecting again. |
| File is malformed or a saved value is invalid | Report unreadable data and preserve the file. Recovery UI is a later feature decision. |
| File uses a newer version | Report the unsupported version and preserve the file so a compatible build can still read it. |
| The app closes while a write is in flight | Restore the last complete session committed to disk. A write may reach disk before the caller receives confirmation. |

## Detailed Implementation Plan

### 1. Share the bookmark type

Move the immutable `FollowAlongBookmark` value into `:core:model`. Update the engine and test imports. Preserve construction checks and existing engine behavior. Run `./gradlew :core:model:test :core:domain:test` from `android/`.

### 2. Build and verify the storage module

Register the Android library with the existing Android library and Hilt conventions. Use Kotlin serialization's JSON tree API to check token types before constructing model instances. Pin `androidx.datastore:datastore-core:1.3.0` in the version catalog. The production module exposes `:core:model` and coroutines core as API dependencies for the public data and Flow types. The domain dependency is test-only, for checking bookmark restoration with the real engine. Use the project's cataloged JUnit 4 and coroutines test dependencies for host tests, and the AndroidX test dependencies and `androidx.test.runner.AndroidJUnitRunner` for the device test. Follow the [content module's test setup](../../android/core/content/build.gradle.kts) and [scoped dispatcher conventions](../../android/core/content/src/main/kotlin/app/seerah/content/di/ContentModule.kt) with userdata's own qualifier and no content dependency.

The store factory accepts a `File` and `CoroutineContext` so tests can own its lifetime. Use `DataStore.Builder` with `FileStorage` and the library's default single-process coordinator and rethrowing corruption handler. Bind one lazy singleton via Hilt's `SingletonComponent`. The provider uses an injected I/O dispatcher backed by `Dispatchers.IO` and an app-lifetime `SupervisorJob`. The file is `filesDir/datastore/explore-session.json`. Production app wiring belongs to the later shell milestone. Here the library build processes its Hilt binding and the device smoke test exercises its store factory using an isolated file.

The session and its typed focus belong to `:core:userdata`. Reuse `TimelinePoint` and `CalendarAxis.wireName`. Time, focus, and bookmark are independently nullable. Focus is sealed with a place or event variant containing a nonblank stable ID. The shell can map those variants to the model's existing `MapTargetRef.PlaceRef` and `MapTargetRef.EventRef` when forming a timeline query.

#### Reads, writes, and the wire format

The internal serializer reads and writes one UTF-8 JSON object. Reject malformed UTF-8 and other JSON root types. Inspect the `version` header before decoding the body. The version must be an unquoted positive JSON integer that fits a Kotlin `Int`. Missing, null, quoted, fractional, nonpositive, or out-of-range versions are unreadable. An unknown positive integer version returns `UnsupportedFormat(version)` even when the body uses unknown fields. The v1 writer always writes `version: 1`.

| Version 1 field | Required fields when present | Optional fields |
| --- | --- | --- |
| `time` | `axis`, `year` | `month`, `day` |
| `focus` | `kind` (`place` or `event`), `id` | None |
| `bookmark` | `storyId`, `chapterId`, `sceneId`, `stepId`, `fallbackStepIndex` | None |

The writer omits null `time`, `focus`, `bookmark`, `month`, and `day` fields. The reader accepts their absence or explicit null. It rejects unknown v1 fields, missing required fields, and invalid values. Reuse the model constructors for validation. A missing file starts with an empty session. An existing empty or malformed file is unreadable.

Expose `UserDataRepository.observeSession(): Flow<UserDataResult>` and `suspend fun updateSession(transform: (ExploreSession) -> ExploreSession): UserDataResult`. Call the pure, non-suspending transform inside `updateData` against the current immutable session. Use `Success(session)` for a read or committed write, `IoFailure` for failing disk operations, `UnreadableData` for invalid stored data, and `UnsupportedFormat(version)` for unsupported versions.

Read input bytes outside the decode error handler so disk read failures remain I/O failures. Inside the decoder, turn malformed UTF-8, JSON decoding, and model validation failures into `CorruptionException`. Report an unsupported version using an internal runtime exception that carries the version. This preserves its type through `FileStorage`, which can wrap other I/O exceptions in a new `IOException`. Convert these two recognized cases before general I/O failure. Let unexpected errors escape. Preserve a caller transform's `IOException` by wrapping it in a private runtime exception inside the callback and unwrapping it before storage error handling. Let coroutine cancellation propagate. After a typed read failure, the flow emits that failure once and ends. A new collection retries from the same store.

#### Verification

- Test the canonical empty wire record, optional null and absent fields, both axes and all three time precisions, both focus variants, and all bookmark fields. Test version and shape failures separately. Through the real `FileStorage`, exercise files containing malformed UTF-8, malformed JSON, and an unsupported version. For reads and updates, assert the typed result, that the transform is never called, and that the original bytes remain unchanged.
- Test a successful write followed by closing the first DataStore job and opening a new store over the same file. Test that concurrent transforms retain independent updates. Use a storage fake for deterministic read and write I/O failures. A read failure emits once and terminates. Removing the failure lets a fresh collector on the same store read the saved session. A failed disk write and a caller's `IOException` preserve the previous commit after reopening.
- Test interruption before commit by pausing a write in a test serializer or storage gate after seeding a valid file. Cancel and join the store job before releasing the gate or reopening the file. Assert cancellation propagates and the old commit survives. Caller cancellation after the write reaches disk may still leave the new session committed.
- Run persisted bookmarks through the existing engine with a step reordered, a removed step in a surviving scene, a removed scene in a surviving story, and a removed story. Assert the same step ID wins over the old index, both clamp paths return a suspended bookmark, and a missing story returns inactive. Check the original bytes are unchanged.
- Run `./gradlew :core:userdata:testDebugUnitTest :core:userdata:connectedDebugAndroidTest`. On a device or emulator, write and recreate the store from disk. Cancel and join the old job before reopening its file. Hilt injection from the production app and offline restart of the full app belong to the shell integration.
- Run `./gradlew check buildHealth assembleRelease` from `android/` and `pre-commit run --all-files` and `python3 tools/validate_content.py` from the repository root. Update the module and Android READMEs and roadmap. Check the open decisions and changelog against the actual change.

### Files in scope

| Action | Paths |
| --- | --- |
| Register the module | `android/settings.gradle.kts`, `android/gradle/libs.versions.toml`, `android/core/userdata/build.gradle.kts` |
| Share the bookmark | `android/core/model/src/main/kotlin/app/seerah/model/FollowAlongBookmark.kt`, `android/core/domain/src/main/kotlin/app/seerah/domain/FollowAlongEngine.kt`, `android/core/domain/src/test/kotlin/app/seerah/domain/FollowAlongEngineTest.kt` |
| Add the storage API and implementation | `android/core/userdata/src/main/kotlin/app/seerah/userdata/ExploreSession.kt`, `android/core/userdata/src/main/kotlin/app/seerah/userdata/UserDataRepository.kt`, `android/core/userdata/src/main/kotlin/app/seerah/userdata/DataStoreUserDataRepository.kt`, `android/core/userdata/src/main/kotlin/app/seerah/userdata/ExploreSessionSerializer.kt`, `android/core/userdata/src/main/kotlin/app/seerah/userdata/ExploreSessionStore.kt`, `android/core/userdata/src/main/kotlin/app/seerah/userdata/di/UserDataModule.kt` |
| Add tests | `android/core/userdata/src/test/kotlin/app/seerah/userdata/ExploreSessionSerializerTest.kt`, `android/core/userdata/src/test/kotlin/app/seerah/userdata/DataStoreUserDataRepositoryTest.kt`, `android/core/userdata/src/androidTest/kotlin/app/seerah/userdata/ExploreSessionStoreTest.kt` |
| Update documentation when the module lands | `android/README.md`, `android/core/model/README.md`, `android/core/domain/README.md`, `android/core/userdata/README.md`, `docs/README.md`, `docs/roadmap.md`, `docs/design/v1-userdata-spec.md` |

### Later shell acceptance

The shell uses `TimelineAxis.buildBuckets` with corpus events and the summary's timeline bounds. A year is eligible when in those buckets. A month is eligible when the bucket records that sourced month, including months sourced to the day. A day is eligible when an event has that exact documented date. A story-only time stays with the story bookmark. When date precision is lost, use the supported year. Look up focus through the exploration map resolver at that time. A saved event may supply its own open-event context, subject to the same time and map placement checks as any other event.

The shell reads the bookmark's story only when a bookmark exists. Use the same installed generation for exploration and story restoration. If the generation changes or the lookup cannot establish a match, discard that in-flight restore and resolve against a consistent snapshot. A missing story gives no resume offer. Loading and I/O failure remain separate from a confirmed missing story.

| Scenario | Expected result |
| --- | --- |
| Cold restart in airplane mode with unchanged content | Restore the saved supported time and valid focus. Offer resume if the bookmark resolves. |
| Empty year still in the timeline | Restore the empty year. |
| Saved day or month loses its supporting date within a selectable year | Restore the year and recheck the focus at that view. |
| Event moves to another year and its ID survives | Restore the supported saved time without that event focus. |
| Event's date changes within the same year | Recheck focus at the resulting time and keep it only if it is visible there. |
| Saved year is outside the active timeline | Use the shell's startup view. An independently valid bookmark may still offer resume. |

## References

References checked on 2026-11-28.

- [Android DataStore guide](https://developer.android.com/topic/libraries/architecture/datastore)
- [AndroidX DataStore release notes](https://developer.android.com/jetpack/androidx/releases/datastore)
- [AndroidX DataStore builder API](https://developer.android.com/reference/kotlin/androidx/datastore/core/DataStore.Builder)
- [AndroidX FileStorage implementation](https://android.googlesource.com/platform/frameworks/support.git/+/1059f509df13f43981792e3de70830b7012236ff/datastore/datastore-core/src/jvmAndAndroidMain/kotlin/androidx/datastore/core/FileStorage.kt#225)
- [Android Auto Backup file rules](https://developer.android.com/identity/data/autobackup#files-that-are-backed-up)
- [Now in Android architecture guide](https://github.com/android/nowinandroid/blob/main/docs/ArchitectureLearningJourney.md)
