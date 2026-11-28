<!--
SPDX-FileCopyrightText: 2026 Mohamed Farag
SPDX-License-Identifier: Apache-2.0
-->

# Consolidated EGM Review Report: v1 Content Pipeline, Room Database, and Domain Rules

**Target:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md)
**Verdict:** **Not Approved for Implementation Until Revised**
**Spec checked against:** [AGENTS.md](../../../AGENTS.md), [ADR 0002](../../adr/0002-proposed-stack.md), [ADR 0003](../../adr/0003-v1-architecture-and-modules.md), [ADR 0004](../../adr/0004-build-and-quality-tooling.md), [ADR 0005](../../adr/0005-content-model-exploration-and-story.md), [ADR 0006](../../adr/0006-open-source-code-and-closed-content.md), [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md), [ADR 0008](../../adr/0008-update-hosting-and-index.md), [ADR 0009](../../adr/0009-map-data-pipeline.md), [exploration-and-story.md](../exploration-and-story.md), [v1-android-package-and-map-spec.md](../v1-android-package-and-map-spec.md), [open-decisions.md](../../open-decisions.md), [engineering.md](../../engineering.md), [roadmap.md](../../roadmap.md), [content/README.md](../../../content/README.md), [content.schema.json](../../../content/schema/content.schema.json), [phases.json](../../../content/example/index/phases.json), [map-data/README.md](../../../map-data/README.md), [tools/validate_content.py](../../../tools/validate_content.py), [strict_yaml.py](../../../tools/map/src/seerah_map/strict_yaml.py), [run.py](../../../tools/map/run.py), [archive.py](../../../tools/packages/src/seerah_packages/archive.py), [generate_android_test_fixtures.py](../../../tools/packages/scripts/generate_android_test_fixtures.py), [test_archive_and_cli.py](../../../tools/packages/tests/test_archive_and_cli.py), [test_android_fixtures.py](../../../tools/packages/tests/test_android_fixtures.py), [requirements-dev.txt](../../../requirements-dev.txt), [.pre-commit-config.yaml](../../../.pre-commit-config.yaml), [.github/workflows/ci.yml](../../../.github/workflows/ci.yml), [.github/workflows/map.yml](../../../.github/workflows/map.yml), [.github/dependabot.yml](../../../.github/dependabot.yml), [android/build.gradle.kts](../../../android/build.gradle.kts), [android/settings.gradle.kts](../../../android/settings.gradle.kts), [libs.versions.toml](../../../android/gradle/libs.versions.toml), [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt), [HiltConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/HiltConventionPlugin.kt), [android/core/content/build.gradle.kts](../../../android/core/content/build.gradle.kts), [r8-smoke-keep.pro](../../../android/core/content/r8-smoke-keep.pro), [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt), [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt), [PackageContracts.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/PackageContracts.kt), [PackageManifestParser.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/PackageManifestParser.kt), [PackageResult.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/PackageResult.kt), [ContentModule.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/di/ContentModule.kt), [PackageStorageAndInstallerTest.kt](../../../android/core/content/src/test/kotlin/app/seerah/content/PackageStorageAndInstallerTest.kt), and [ContentDeviceSmokeTest.kt](../../../android/core/content/src/androidTest/kotlin/app/seerah/content/ContentDeviceSmokeTest.kt)

## 1. Header and Verdict

### Reviewer Status

| Lens | Reviewer | Status | Summary |
| --- | --- | --- | --- |
| Cold-read comprehension | `egm-comprehension` | completed | 6 Blockers (`C-B1..C-B6`), 5 Majors (`C-M7..C-M11`), 2 Nits (`C-N12..C-N13`) |
| Zoom-out architecture | `egm-zoom-out` | completed | 2 Blockers (`Z-B1..Z-B2`), 2 Majors (`Z-M3..Z-M4`), 1 Nit (`Z-N5`) |
| Implementation readiness | `egm-implementation-readiness` | completed | 5 Blockers (`R-B1..R-B5`), 3 Majors (`R-M6..R-M8`), 2 Nits (`R-N9..R-N10`) |
| Hostile design and security | `egm-design` | completed | 7 Blockers (`D-B1..D-B7`), 8 Majors (`D-M1..D-M8`), 5 Nits (`D-N1..D-N5`) |
| Codebase details | `egm-codebase-details` | completed | 3 Blockers (`CD-B1..CD-B3`), 6 Majors (`CD-M4..CD-M9`), 2 Nits (`CD-N10..CD-N11`) |

### Overall Verdict

**Not Approved for Implementation Until Revised.** All five reviewers agree on the overarching architectural backbone: YAML 1.2 authoring compiled deterministically into signed `content` packages, imported into a generation-scoped disposable Room 3 database (`androidx.room3` `3.0.3` with `AndroidSQLiteDriver`), and `:core:domain` remaining strictly pure JVM (`seerah.jvm.library`, `D3`) while `D1` preserves a temporary legacy JSON validator fallback for `seerah-content`.

However, implementation cannot start until the specification resolves **10 deduplicated Blockers (`B1–B10`)**, **11 Majors (`M1–M11`)**, **4 Nits (`N1–N4`)**, and the architectural scope triage items in Section 5:

1. **Critical `:core:content` concurrency, cold-start, and Room 3 traps (`B1`, `B2`):** `DefaultPackageInstaller` deletes `vOld-gOld/` under `storageMutex` before `ContentDatabaseManager` switches/closes `v1Db`; `observeInstalled()` never triggers cold-start hydration, creating an infinite `Loading` deadlock; `ContentDatabaseManager` omits `JournalMode.TRUNCATE` on open; `@Upsert` is incompatible with `minSdk = 26` (SQLite 3.18.0); and `AndroidSQLiteDriver` throws `androidx.sqlite.SQLiteException` rather than `android.database.sqlite.SQLiteFullException`.
2. **Broken schema, `:core:model`, `:core:domain`, and repository parity (`B3`, `B4`, `B5`):** Contradictory fields across YAML -> JSON -> `:core:model` -> Room (`schema` vs `content_schema`, `title` vs `name`, missing route YAML home, `expedition_role`, `related_event_ids` vs `related[]`, missing `StoryStep` moves), broken `PRE_HIJRA` / `UNKNOWN` / baseline timeline resolution, `IN_CONTEXT` event marker leaks, and undefined/tearing `ContentRepository` streams (`EventDetail`, `observeAllSources`, three state enum names).
3. **`D1` CI breakage and existing test regressions (`B6`):** `seerah-content` CI installs only `jsonschema==4.26.0`, and tightening `archive.verify_package` breaks existing stub `content` tests in `tools/packages` and `:core:content` unless explicitly scoped and migrated.
4. **Over-engineered disk footprint and `:core:domain` scope conflict (`B7`, `B8`):** Triple disk retention (`package.zip` + `data/*.json` + `content.db`) and 16 tables vs simpler storage, plus `egm-zoom-out`'s recommendation to defer `FollowAlongEngine`/`MapSceneBuilder` vs [ADR 0003](../../adr/0003-v1-architecture-and-modules.md) and [ADR 0005](../../adr/0005-content-model-exploration-and-story.md) placing follow-along rules in `:core:domain`.
5. **Content-integrity violation and inline scratchpad contradictions (`B9`, `B10`):** Spec line `L210` embeds real coordinates of Madinah (`{ lat: 24.467, lon: 39.611 }`, `kind: mosque`) against [AGENTS.md](../../../AGENTS.md) and Non-Goal #3, while unedited self-debates (`L438`, `L452–453`, `L514`) and `draft` vs `approved` fixture contradictions block unambiguous coding.

---

## 2. Top Blockers

### B1. Active-DB Close vs. Old-Generation Deletion Race, Cold-Start Deadlock, and Lockout

- **Raw findings:** `C-B6` (part), `Z-B2` (part), `R-B5` (part), `D-B1`, `D-B2`, `D-B3` (part), `CD-B1`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L444-L454), [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt#L160-L166), [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt#L39-L59)
- **Problem:**
  1. *Active-DB delete race:* [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt#L160-L166) publishes the new pointer inside `storageMutex` and synchronously calls `storage.deleteRecursivelyBestEffort(oldActiveDir)` before `ContentDatabaseManager` asynchronously reacts on its collector to switch off and close `v1Db`. Likewise, [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt#L113-L118) (`cleanupOrphanedFiles()`) sweeps every directory `!= keepDirName`. Closing `v1Db` while `flatMapLatest` child DAO queries are running causes connection-pool-closed crashes, whereas awaiting DB drain inside `storageMutex` risks `Mutex` deadlock.
  2. *Cold-start infinite `Loading` deadlock and `CoroutineScope` leak:* [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt#L46-L52) initializes `stateFlows[CONTENT]` to `InstalledPackageState.Loading` (`hydrated = false`), and `observeInstalled()` never calls `ensureHydratedLocked()`. If a screen only collects `ContentRepository` flows without anyone calling `currentInstalled(PackageKind.CONTENT)`, `ContentDatabaseManager` remains stuck on `Loading` forever. Additionally, `ContentDatabaseManager`'s coroutine scope ownership is unspecified ([engineering.md](../../engineering.md#L34) forbids `GlobalScope`, and uncancelled singleton scopes hang `runTest`).
  3. *Corrupt-package permanent lockout:* [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt#L360-L364) (`checkVersionFloor`) rejects same-version reinstalls (`DowngradeOrSameVersion` for `5 <= 5`). If a package's retained `package.zip` or `content.db` becomes corrupt on disk while `active.json` stays valid, the user can never re-download and repair version `5` unless corrupt `active.json` is quarantined.
- **Required fix:**
  - Add generation directory pinning and a lease/ref-counted `ActiveDbHandle` in `DefaultPackageStorage` / `ContentDatabaseManager`: track active readers, emit `v2Db`, let `v1` queries finish off-`storageMutex`, close `v1Db`, then unpin and delete `vOld-gOld/`.
  - Trigger cold-start hydration automatically when `ContentDatabaseManager` activates (via an injected lifecycle-bounded or closable `CoroutineScope` compatible with `runTest`) or trigger `ensureHydratedLocked()` cleanly on observation startup.
  - Quarantine corrupt `active.json` (and transition state cleanly) when `package.zip` fails integrity verification during disposable rebuild so re-downloading the same version succeeds.

### B2. Disposable Room 3 Rebuild, `JournalMode.TRUNCATE`, SQLite 3.18 (`minSdk = 26`), and Build Traps

- **Raw findings:** `C-B6` (part), `Z-B2` (part), `R-B4`, `R-B5` (part), `D-B3`, `D-B4` (part), `D-B7` (part), `CD-7` (part), `CD-8` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L380-L454), [libs.versions.toml](../../../android/gradle/libs.versions.toml), [HiltConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/HiltConventionPlugin.kt#L11), [PackageResult.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/PackageResult.kt#L13-L40), [PackageStorageAndInstallerTest.kt](../../../android/core/content/src/test/kotlin/app/seerah/content/PackageStorageAndInstallerTest.kt)
- **Problem:**
  1. *Omitted `JournalMode.TRUNCATE` on cold-start open:* Spec `L439` sets `.setJournalMode(RoomDatabase.JournalMode.TRUNCATE)` during staging import, but `L449` omits it when opening `content.db` in `ContentDatabaseManager`. Room's default `AUTOMATIC` resolves to `WAL` on Android, creating `-wal`/`-shm` sidecars in `v<ver>-g<gen>/`. Also `dbFile.path` (`L439`) should use `absolutePath` / canonical containment.
  2. *Non-atomic 2-file rebuild & ambiguous validation:* Replacing `content.db` and `db_stamp.json` separately (`L451`) is not atomic across process death, `content_schema` bump behavior is unspecified, pre-open `db_stamp.json` checks vs Room open exceptions are ambiguous, and `metaDao.getMeta()` alone misses corruption outside `meta` compared to `PRAGMA quick_check`.
  3. *Rebuild trust gap & `Context` coupling:* Skipping `manifest.sig` during disposable rebuild (`L453`) leaves local tampering of `manifest.json` + `package.zip` undetected even when the signing key is still trusted in `TrustedKeyRingSource` (signature check should run when the key is still in the active ring, falling back to manifest-digest integrity only when `key_id` was retired per Rule A). Passing Android `Context` into `DefaultPackageInstaller` breaks pure-JVM [PackageStorageAndInstallerTest.kt](../../../android/core/content/src/test/kotlin/app/seerah/content/PackageStorageAndInstallerTest.kt) unless Room import/open is abstracted behind a pure interface (`ContentDatabaseImporter` / `ContentDatabaseOpener`).
  4. *`minSdk = 26` SQLite 3.18.0 & exception mapping:* Android API 26 ships SQLite 3.18.0 (`ON CONFLICT DO UPDATE` / Room `@Upsert` requires SQLite 3.24+); `@Upsert` **must be forbidden** (`@Insert`-only). `AndroidSQLiteDriver` throws `androidx.sqlite.SQLiteException` (`SQLITE_FULL` = `13`), not `android.database.sqlite.SQLiteFullException`, and Room/driver opens can throw `IllegalStateException`. `InvalidPackageCause` (`L438`) also leaves `INVALID_CONTENT_PAYLOAD` undecided.
  5. *Gradle / KSP / DAGP Room 3 wiring:* [HiltConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/HiltConventionPlugin.kt#L11) already applies KSP (applying `ksp` again fails/warns); Room 3 `@ConstructedBy` `expect object` syntax must compile cleanly in non-KMP Android under `allWarningsAsErrors = true`; DAGP (`onAny { severity("fail") }`) requires direct declaration (or a `room3` bundle) of `androidx.room3:room3-common` and `androidx.sqlite:sqlite:2.7.1`; and `r8-smoke-keep.pro` needs updating if `ContentDeviceSmokeTest` calls repository/DB APIs.
- **Required fix:** Always pass `.setJournalMode(RoomDatabase.JournalMode.TRUNCATE)` on both import and open; replace `db_stamp.json` or make DB+stamp validation/swap atomic via `PRAGMA user_version` + `meta` (pinned in `Triage`); verify `manifest.sig` during rebuild whenever `key_id` remains trusted; abstract `ContentDatabaseImporter` so `DefaultPackageInstaller` stays `Context`-free; forbid `@Upsert`; catch `androidx.sqlite.SQLiteException` (`SQLITE_FULL` = 13) + `SQLiteFullException` + `IllegalStateException`; decide `INVALID_CONTENT_PAYLOAD` explicitly; and pin exact Room 3 Gradle/KSP/DAGP declarations.

### B3. Contradictory YAML, Compiled JSON, `:core:model`, and Room 3 Schemas

- **Raw findings:** `C-B2`, `C-M7`, `C-M10` (part), `R-B1`, `R-B4` (part), `D-B5` (part), `D-B6` (part), `CD-B3` (part), `Z-N5` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L161-L429), [content.schema.json](../../../content/schema/content.schema.json#L70-L127)
- **Problem:** Every layer between YAML authoring and SQLite schema has mismatched or missing definitions:
  - *Root/naming mismatches:* `schema: 1` (`L163`) vs `content_schema: 1` (`L191`); `title` (`L208`, `L218`) vs `name` in `data/places.json` (`L319`), `data/areas.json` (`L320`), `place_states`, and `area_states`; file-level `calendar` + `year` (`L203–204`) vs per-event `date` (`L228`); and `ContentDatabase` (`L34`) vs `SeerahContentDatabase` (`L140`, `L410`).
  - *Missing YAML authoring schemas/examples:* `world/sources.yaml`, `world/areas.yaml`, `world/places.yaml`, `area_changes`, and **routes** have no YAML directory or example (`L336` still mentions legacy `geometry_ref`/`coordinates`, and station-to-coordinate mapping for `Route` is unspecified).
  - *Event & situation mismatches:* `related_event_ids` (`L242`) vs typed `related[] { target_event_id, type }` (`L322`, `L334`, `L372`); `expedition_role` appears in `data/events.json` (`L322`) but is absent from YAML (`L221–244`), `EventModels.kt` (`L372`), and SQL `events` (`L422`); `situations` omits `order` in YAML (`L237–240`) and [content.schema.json](../../../content/schema/content.schema.json#L70-L76) while requiring `sort_order` in SQL (`L424`), and omits `place` from YAML and `EventModels.kt` despite [content.schema.json](../../../content/schema/content.schema.json#L74) and SQL (`L424`); `phase.note` and `date.note` conflict with `L322` ("strip working author-only `note`") vs `events.date_note` (`L422`).
  - *Story & toolkit mismatches:* YAML example (`L282–296`) and `StoryModels.kt` (`L373`) omit `route:*` targets, `draw_route`, `follow`, `show_movement`, `reveal`, `show_area`, `explore_here`, `TimeLapse`, `panel.linking` (`StepPanel.linkingSentence`), `RouteRef`, and `review_notes.accept_multi_change` (`L338`); `story_scenes.event_id` nullability is unspecified (`L428`); `StepMovement` omits `route.documented` checks; `excerpt_of` cross-event validation (`L334`) differs from legacy per-event lookup; `Route.stations.size >= 2` (`L370`) is missing; and unique `order` scope (`L335`) is unpinned.
  - *Room FK & DAO gaps:* `@Dao` signatures and nullable columns are omitted; `events.parent_id` self-FK (`L422`) requires topological parent-before-child insertion order; `texts.explanation_source_work_id` (`L425`) lacks a foreign key to `sources`; and `where` union (`SinglePlace`, `MultiPlace`, `AreaTarget`, `RouteTarget`, `Unknown`) wire encodings are unpinned.
- **Required fix:** Define exact normative field-by-field mappings and schemas for all YAML files (`content.yaml`, `world/*.yaml`, `years/*.yaml`, `texts/*.yaml`, `stories/*.yaml` including routes), all 8 `data/*.json` outputs, all `:core:model` types, and all `@Entity`/`@Dao` Room definitions before coding.

### B4. Broken Pre-Hijra `continuousYear`, `UNKNOWN` Precision, Baseline States, and Domain Resolution

- **Raw findings:** `C-B3`, `C-M9` (part), `Z-M3` (part), `R-B3`, `D-B5` (part), `D-B6` (part), `D-M3`, `D-M8` (part), `CD-9` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L169-L170), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L319-L370), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L487-L506)
- **Problem:**
  1. *No year zero & `UNKNOWN` date authoring:* Spec `L363` defines `TimelinePoint(axis, year, month?, day?)` with `year >= 1` (`L369`), which cannot represent `DatePrecision.UNKNOWN` (`year == null`) or non-null SQL `continuous_year` in `events` (`L422`). Furthermore, `years/<slug>.yaml` mandates `calendar + year` (`L170`), leaving nowhere to author `precision: unknown` events (`D1`/authoring gap).
  2. *Baseline states, `precision_rank`, and state-vs-query comparison:* `world/places.yaml` and `world/areas.yaml` define baseline initial states existing at period start (`StateStart.Baseline`), yet `place_states` / `area_states` require `(axis, continuous_year, precision, year, ...)`. `precision_rank` (`L319`) is never defined, same-year `YEAR`-precision state transitions fail strict chronology, and `TimeStateResolver` compares `endOfYear()` / `day ?: 31` queries against state `from` points whose implicit start-of-period semantics (`YEAR` -> month 1 day 1, `MONTH` -> day 1) are unstated.
  3. *Visibility & filter leaks in `TimeStateResolver`:* `L496` filters events only by `selectedPoint` and `where != Unknown`, forgetting `SeerahEvent.visibility == IN_CONTEXT` — leaking `in-context` events onto the overview map against [ADR 0005](../../adr/0005-content-model-exploration-and-story.md#L15). Area resolution (`L492–495`) never filters `exists == true` or non-empty shapes; `RouteTarget` marker rules, `YEAR` events in month view ([exploration-and-story.md](../exploration-and-story.md#L114)), `UNKNOWN` `eventOpenTarget` (`L490`), `StepTime` month validation, and tie-breaking are unpinned.
  4. *`FollowAlongEngine` & `CertaintyNoteResolver` gaps:* `FollowAlongEngine` (`L499–505`) omits `resume()`, omits `chapterId` in `FollowAlongBookmark` (`L500` vs `L503`), risks a self-suspension loop if step `StepTime` feeds back into `onExploreTimeChanged()`, and uses fragile `stepIndex` rather than `stepId + fallbackStepIndex` across content updates ([ADR 0003](../../adr/0003-v1-architecture-and-modules.md#L183)). `CertaintyNoteResolver` (`L498`) omits a note for `DatePrecision.UNKNOWN`.
- **Required fix:** Separate `StateStart` (`Baseline` vs `At(point)`), separate `TimelinePoint` (`year >= 1`) from nullable `HistoricalDate` / `Unknown`, provide an explicit YAML authoring home for undated events, define `precision_rank` and start-vs-end comparison explicitly, filter `Visibility.ALWAYS` (and active-parent `IN_CONTEXT`) on map event markers, fix `FollowAlongEngine` `resume()` / `stepId` bookmarking / loop guard, and add `UnknownDate` certainty notes.

### B5. Broken `ContentRepository` and `:core:domain` Contract and Stream Tearing

- **Raw findings:** `C-B4`, `R-B3` (part), `D-B6` (part), `D-M1`, `D-M8` (part), `CD-B3` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L143), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L447-L479)
- **Problem:**
  1. Three incompatible state types exist for the same lifecycle: `ContentLoadState` (`L143`), `ContentDbHandle` (`L447`), and `ContentState` (`L458`).
  2. `EventDetail` (`L470`) is never defined, `observeAllSources()` / `SourceWork` queries are missing (even though texts only hold `source_work_id`), `observeEventsInYear` takes `TimelinePoint` whose `month`/`day` semantics are unclear, and `observeStories()` eagerly loads full `Story` step trees instead of `StorySummary`.
  3. Exposing 9 independent `Flow<List<...>>` methods each backed by `flatMapLatest` over `activeHandle` causes **cross-stream tearing** when `v1-g1 -> v2-g2` activates mid-`combine()` (e.g., `v2` events paired with `v1` places/texts), and leaves emission behavior undefined during `Loading`, `NotInstalled`, and `Error` (empty lists blur loading vs missing content and risk `combine()` deadlocks).
- **Required fix:** Standardize on one state type (`ContentState`), define `EventDetail` and `SourceWork` / `StorySummary` queries (or snapshot bundle), and prevent cross-stream tearing by keying queries or publishing a consistent versioned snapshot / combined corpus handle per generation.

### B6. `D1` `seerah-content` CI Trap and Breaking Existing `tools/packages` / Android Stub Tests

- **Raw findings:** `C-B5`, `Z-M3` (part), `R-M6`, `R-M7` (part), `D-B7` (part), `D-M7` (part), `CD-B2`, `CD-M4`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L31), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L298-L302), [tools/validate_content.py](../../../tools/validate_content.py), [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L555), [test_archive_and_cli.py](../../../tools/packages/tests/test_archive_and_cli.py#L63-L79), [PackageStorageAndInstallerTest.kt](../../../android/core/content/src/test/kotlin/app/seerah/content/PackageStorageAndInstallerTest.kt#L62), [ContentDeviceSmokeTest.kt](../../../android/core/content/src/androidTest/kotlin/app/seerah/content/ContentDeviceSmokeTest.kt#L80-L112)
- **Problem:**
  1. `seerah-content/.github/workflows/validate.yml` checks out `seerah` at `main` and installs **only** `jsonschema==4.26.0`. Top-level `import ruamel.yaml` or `import seerah_content` inside [tools/validate_content.py](../../../tools/validate_content.py) will immediately crash `seerah-content` CI with `ModuleNotFoundError`. The spec also omits concrete error rules (both `content.yaml` and `index/phases.json` present, neither present, corrupt YAML) and migration mechanics for the `26` existing files.
  2. Tightening [archive.py](../../../tools/packages/src/seerah_packages/archive.py#L880-L1066) (`verify_package(..., expected_kind="content")`) to require all 8 `content_schema: 1` `data/*.json` files breaks existing stub `content` tests (`data/ok.json: b"{}"`, `data/item.json`, `data/content.db`) in [test_archive_and_cli.py](../../../tools/packages/tests/test_archive_and_cli.py#L63-L79), [test_archive_and_cli.py](../../../tools/packages/tests/test_archive_and_cli.py#L550-L671), [PackageStorageAndInstallerTest.kt](../../../android/core/content/src/test/kotlin/app/seerah/content/PackageStorageAndInstallerTest.kt#L62), and [ContentDeviceSmokeTest.kt](../../../android/core/content/src/androidTest/kotlin/app/seerah/content/ContentDeviceSmokeTest.kt#L80-L112) unless migrated together.
  3. [.pre-commit-config.yaml](../../../.pre-commit-config.yaml#L54-L60) misses `tools/content/` in `files:` and misses `ruamel.yaml==0.19.1`; [.github/workflows/ci.yml](../../../.github/workflows/ci.yml#L24-L51) misses `tools/content/tests` and `PYTHONPATH`.
- **Required fix:** Lazy-import `seerah_content` / `ruamel.yaml` inside `tools/validate_content.py` only when `content.yaml` is detected (refusing dual-format roots cleanly), specify the test-stub migration across `tools/packages` and `:core:content`, and update `.pre-commit-config.yaml` and `.github/workflows/ci.yml` explicitly in Phase 1.

### B7. Triple Disk Footprint, Full-AST Memory Parse, `db_stamp.json` Redundancy, and 16-Table Over-Normalization

- **Raw findings:** `Z-B2`, `C-M10` (part), `D-B4` (part), `D-M1` (part), `CD-N11`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L398-L454), [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt#L205-L214)
- **Problem:** Keeping `package.zip` + extracted `data/*.json` + `content.db` on disk triples storage (note [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt#L208) currently requires `dataDir.isDirectory`), and parsing all 8 JSON files into `:core:model` lists before DB insertion (`L438`) contradicts the bounded-RAM goal on the Oppo A55 (`4 GB` RAM). Additionally, `db_stamp.json` duplicates SQLite `PRAGMA user_version` + `room_master_table` + `meta` + `active.json`, and 16 hybrid Room tables + JSON columns add heavy boilerplate for a corpus of ~26–200 events.
- **Required fix:** Stream one collection at a time directly from `package.zip` into Room (sharing one ZIP -> Room pipeline for both initial install and disposable rebuild without permanently keeping unpacked `data/*.json` on disk, updating `DefaultPackageStorage.hasRequiredActiveFiles`), replace `db_stamp.json` with `PRAGMA user_version` + `meta`, and simplify nested non-indexed tables where appropriate (see Section 5 Conflict Triage).

### B8. Premature `:core:domain` Story-Mode Engines vs. ADR 0003 Scope (Reviewer Conflict)

- **Raw findings:** `Z-B1` (vs. [ADR 0003](../../adr/0003-v1-architecture-and-modules.md#L27) & [ADR 0005](../../adr/0005-content-model-exploration-and-story.md#L37))
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L458-L508)
- **Problem:** `egm-zoom-out` flags `FollowAlongEngine`, `MapSceneBuilder`, and the full 10-method `ContentRepository` as premature speculation before `:core:userdata` (`ExploreSession`), `:core:map` historical overlay rendering contracts, and `:feature:*` ViewModels exist — recommending trimming Phase 2 `:core:domain` to `TimelineAxis`, `TimeStateResolver`, and `CertaintyNoteResolver`. Conversely, [ADR 0003](../../adr/0003-v1-architecture-and-modules.md#L27) (`L27`, `L142`, `L160`) and [ADR 0005](../../adr/0005-content-model-exploration-and-story.md#L37) explicitly assign follow-along rules to pure-JVM `:core:domain`.
- **Required fix (owner/author triage needed):** See Section 5 Conflict 1 for the recommended middle ground so the owner/author can weigh keeping `FollowAlongEngine` now while keeping `MapSceneBuilder` / `ContentRepository` tightly scoped to verified consumers.

### B9. Inline Scratchpad Monologues, `draft` vs. `approved` Fixture Contradictions, and Filename/Commit Mismatches

- **Raw findings:** `C-B1`, `R-B2`, `D-B4` (part), `CD-6` (part), `Z-N5` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L118), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L214-L270), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L438), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L452-L453), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L514), [phases.json](../../../content/example/index/phases.json#L15-L23), [generate_android_test_fixtures.py](../../../tools/packages/scripts/generate_android_test_fixtures.py#L29)
- **Problem:**
  1. Unedited self-debates remain inline at `L438`, `L452–453`, and `L514`, giving conflicting instructions.
  2. `L118` compiles `valid-content-v1.zip` from `content/example/`, contradicting `L514`'s in-script corpus and `L214/220/244/250/270`'s `review: approved` snippets (while existing [phases.json](../../../content/example/index/phases.json#L15-L23) is `review: draft`).
  3. `L514` cites `<40 zeros>` for `source_commit`, conflicting with `FIXTURE_SOURCE_COMMIT = "a" * 40` in [generate_android_test_fixtures.py](../../../tools/packages/scripts/generate_android_test_fixtures.py#L29).
  4. `stories/example-story.yaml` (`L267`) declares `id: example-main-story` (`L268`), violating `<story-id>.yaml` (`L174`); rules for duplicate/eventless `years/` files and standalone `always` places/sources under `--require-approved` are missing.
- **Required fix:** Remove all inline self-debates, keep `content/example/` cleanly `review: draft` (tested without `--require-approved`) while building `valid-content-v1.zip` from an explicit in-script synthetic `approved` corpus using `FIXTURE_SOURCE_COMMIT = "a" * 40`, and enforce `<story-id>.yaml` filename-to-ID parity plus `years/` and standalone-entity rules.

### B10. Content-Integrity Policy Violation: Real Madinah Coordinates in Example Snippet

- **Raw findings:** `CD-6` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L206-L214), [AGENTS.md](../../../AGENTS.md), [ADR 0006](../../adr/0006-open-source-code-and-closed-content.md), [map-data/README.md](../../../map-data/README.md#L26)
- **Problem:** Spec `L210` puts `{ lat: 24.467, lon: 39.611 }` (the real coordinates of al-Masjid an-Nabawi in Madinah) alongside `kind: mosque`, `year: 1`, `month: 3`. That directly violates `AGENTS.md` ("Never copy or reference real Seerah titles, dates, coordinates, routes, or narrations from `../seerah-content/`"), [ADR 0006](../../adr/0006-open-source-code-and-closed-content.md), and Spec Non-Goal #3, whereas [map-data/README.md](../../../map-data/README.md#L26) uses neutral Masirah Island coordinates (`20.50–20.75° N, 58.80–59.05° E`) away from any Seerah site.
- **Required fix:** Immediately replace `{ lat: 24.467, lon: 39.611 }` (and the `kind: mosque` + `year: 1, month: 3` pairing) in the spec and all `content/example/` or test fixtures with neutral coordinates inside `20.50–20.75° N, 58.80–59.05° E` and clearly fictional attributes.

---

## 3. Major Findings

### Storage and Database Lifecycle

#### M1. Permanent `data/*.json` Retention, `DefaultPackageStorage` Coupling, and Streaming RAM Budget

- **Raw findings:** `Z-B2` (part), `C-M10` (part), `D-B4` (part), `D-M1` (part), `CD-N11`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L312), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L405), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L438), [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt#L205-L214)
- **Problem:** Even though Table §2 justifies 8 files to bound peak RAM on Oppo A55 (`4 GB`), `L438` parses and validates the entire corpus into memory before Room insertion, and `L405` permanently retains unpacked `data/*.json` alongside `package.zip` and `content.db`. [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt#L208) (`hasRequiredActiveFiles`) also assumes `dataDir.isDirectory` for `CONTENT`.
- **Required fix:** Stream JSON collections sequentially (one file at a time inside one SQLite transaction), and update `DefaultPackageStorage` so `CONTENT` stops retaining redundant `data/*.json` once `content.db` commits (relying on `package.zip` + `content.db`).

#### M2. `StatFs` Space Formula Errors, `copiedContentZipEarly` Double-Counting, and Rebuild Space Gate

- **Raw findings:** `C-M10` (part), `R-B5` (part), `D-M2`, `CD-7` (part), `Z-N5` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L435-L437), [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt#L21), [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt#L206-L214), [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt#L288-L292), [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt#L370-L394)
- **Problem:**
  1. Spec `L436` contains invalid Kotlin (`2L * sumOf { ... }` with no receiver) and leaves only `1x` for `content.db` + SQLite indexes (when `data/*.json` is also present, `2L *` under-budgets; use `3L *` if `data/*.json` remains, or explicit DB multiplier restricted to `PackageKind.CONTENT`).
  2. In [DefaultPackageInstaller.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageInstaller.kt#L206-L214), `expectedKind == PackageKind.CONTENT` copies `package.zip` into `stagingDir` **before** `checkAvailableSpace()` runs at `L288`, so passing `retainedArchiveBytes = archiveBytes` double-counts the retained ZIP's disk footprint (`L377`).
  3. On `ENOSPC` during Room import (`L437`, `DefaultPackageInstaller.kt:174`), passing `archiveBytes` rather than the computed `requiredBytes` misreports space needed; `content.db` block alignment (`BLOCK_SIZE_BYTES`) is omitted; and disposable rebuild (`L451`) never calls `StatFs` before writing `content.db.tmp`.
- **Required fix:** Fix the `copiedContentZipEarly` double-count, apply block-aligned DB headroom strictly to `PackageKind.CONTENT`, pass accurate `requiredBytes` to `PackageFailure.InsufficientStorage`, and run an explicit `StatFs` check before disposable DB rebuild.

#### M3. Atomic Disposable Rebuild Staging, `db_stamp.json` Simplification, and Shared Import Pipeline

- **Raw findings:** `Z-B2` (part), `C-B6` (part), `D-B3` (part), `CD-7` (part), `CD-8` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L407), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L439-L454)
- **Problem:** Initial install (`L439`) and disposable rebuild (`L451`) implement separate JSON extraction/import paths and rely on two-file `content.db` + `db_stamp.json` synchronization (`ATOMIC_MOVE` on two files is not atomic across power loss). Updating `DefaultPackageInstaller`'s constructor also affects call sites in [PackageStorageAndInstallerTest.kt](../../../android/core/content/src/test/kotlin/app/seerah/content/PackageStorageAndInstallerTest.kt) and [ContentDeviceSmokeTest.kt](../../../android/core/content/src/androidTest/kotlin/app/seerah/content/ContentDeviceSmokeTest.kt#L80-L128).
- **Required fix:** Share one `ContentDatabaseImporter` (ZIP -> Room) between staging install and disposable rebuild, drop `db_stamp.json` in favor of SQLite `PRAGMA user_version` + `meta` row validation + atomic single-file `content.db.tmp -> content.db` replacement, and list all updated test call sites explicitly.

#### M4. Room 3 Toolchain, `sqlite-bundled`, DAGP, KSP, R8, and Pre-Commit Schema Hooks

- **Raw findings:** `R-B4` (part), `R-M8` (part), `D-B7` (part), `CD-7` (part), `CD-8` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L123-L147), [.pre-commit-config.yaml](../../../.pre-commit-config.yaml#L6-L10), [HiltConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/HiltConventionPlugin.kt#L11), [android/build.gradle.kts](../../../android/build.gradle.kts#L33-L54), [r8-smoke-keep.pro](../../../android/core/content/r8-smoke-keep.pro)
- **Problem:**
  1. `androidx.sqlite:sqlite-bundled:2.7.1` bundles native `.so` libraries and must be pinned strictly in `testImplementation` (never `implementation`/`api`) so release APKs keep zero `.so` / 16 KB page overhead.
  2. Applying `ksp` in `:core:content/build.gradle.kts` duplicates [HiltConventionPlugin.kt](../../../android/build-logic/convention/src/main/kotlin/HiltConventionPlugin.kt#L11).
  3. DAGP `buildHealth` (`severity("fail")`) requires direct declaration (or a `bundle("room3")` in [android/build.gradle.kts](../../../android/build.gradle.kts)) for `androidx.room3:room3-common` and `androidx.sqlite:sqlite:2.7.1`; non-KMP `@ConstructedBy` `expect object` compilation under `allWarningsAsErrors = true` must be verified cleanly.
  4. Room exported schemas (`android/core/content/schemas/*.json`) are checked by [.pre-commit-config.yaml](../../../.pre-commit-config.yaml#L6-L10) (`check-json`, `trailing-whitespace`, `end-of-file-fixer`), and [r8-smoke-keep.pro](../../../android/core/content/r8-smoke-keep.pro) must be updated if `ContentDeviceSmokeTest` calls repository/Room classes.
- **Required fix:** Guard `sqlite-bundled` as `testImplementation`-only, omit duplicate `ksp` plugin application, declare exact DAGP dependencies/bundles, verify exported Room JSON formatting against pre-commit hooks, and update `r8-smoke-keep.pro`.

### YAML, Package, and CI Tooling

#### M5. 5-Way Validation Duplication, `strict_yaml` Hardening, and `PYTHONPATH` Coupling

- **Raw findings:** `Z-M4` (part), `D-M5`, `CD-7` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L179-L185), [strict_yaml.py](../../../tools/map/src/seerah_map/strict_yaml.py), [run.py](../../../tools/map/run.py#L31), [.github/workflows/map.yml](../../../.github/workflows/map.yml#L118)
- **Problem:**
  1. Validating identical rules across two JSON schemas, `tools/content/checks.py`, `tools/packages/archive.py`, `ContentPackageParser`, and `:core:model` `init` creates 5-way drift risk. Also, [run.py](../../../tools/map/run.py#L31) and [.github/workflows/map.yml](../../../.github/workflows/map.yml#L118) mount `/src/tools/map/src` via `PYTHONPATH`, breaking naive cross-imports from `tools/content` unless packaged cleanly.
  2. `strict_yaml.py` in `tools/content/` needs explicit hardening beyond [tools/map/src/seerah_map/strict_yaml.py](../../../tools/map/src/seerah_map/strict_yaml.py): `SafeComposer` rejecting `*alias`/`&anchor` and custom `!tags`, `load_all` rejecting multi-document YAML streams, rejecting non-`str` mapping keys, rejecting `NaN`/`Inf` floats, and rejecting empty (`None`) documents.
- **Required fix:** Keep domain cross-checks out of `tools/packages/archive.py` (which should check only `content-package.schema.json` + `content_schema == 1`), extract/share the strict YAML logic cleanly without breaking `tools/map`'s Docker `PYTHONPATH`, and add the missing `SafeComposer`/`load_all`/non-`str`-key/float guards.

#### M6. Editorial `source` Contradiction, Draft Nullability, and Missing Validator Checks

- **Raw findings:** `C-M7` (part), `C-M8` (part), `R-M7` (part), `D-B6` (part), `D-M8` (part), `CD-9` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L317-L339), [content.schema.json](../../../content/schema/content.schema.json#L57), [content.schema.json](../../../content/schema/content.schema.json#L114-L127)
- **Problem:**
  1. Spec `L335` and [content.schema.json](../../../content/schema/content.schema.json#L57) require every `text` — including `kind: editorial` — to have a `source`, yet `SourceKind` (`L371`) has no editorial/internal kind and editorial linking text is written by the author/owner rather than cited from a book.
  2. Spec `L339` mandates `review == "approved"` on every phase/event, whereas [content.schema.json](../../../content/schema/content.schema.json#L119) has no `review` field on `phase`, and [content.schema.json](../../../content/schema/content.schema.json#L114-L115) makes `date`/`lead_in`/`outcome`/`situations`/`texts` required **only** when `review == "approved"` — leaving draft compilation (`--compile` without `--require-approved`) unspecified for nullability.
  3. Missing checks in `tools/content/checks.py`: `explanation_source != null` requiring non-blank `explanation`; story `TimeLapse` checking resolved `exists == true` across the full `[from..to]` span; `Area` `polygon_ring` validation (`>= 4` vertices, `first == last`, finite bounds); `min_timeline_point` / `max_timeline_point` derivation rules; and off-extent coordinates handling (e.g., `al-Isra` outside `dataExtent`).
- **Required fix:** Make `source` optional (or define explicit editorial attribution) when `kind == "editorial"`, align `phase` `review` requirements with `content.schema.json`, define draft-vs-approved compilation nullability (or forbid `--compile` on unapproved items unless testing), and add all missing `checks.py` validations.

#### M7. Enum Wire Formats, JSON Determinism, and `ContentPackageParser` Codec Hardening

- **Raw findings:** `C-M7` (part), `R-M7` (part), `D-M4`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L315-L375), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L438)
- **Problem:** Wire formats for every enum (`seerah-narration` vs `SEERAH_NARRATION`, `single_place` vs `SinglePlace`, `cause-of` vs `CAUSE_OF`, etc.) and deterministic JSON emission parameters (`ensure_ascii`, `separators` vs indent, key sorting, array ordering across all 8 files) are unpinned, risking byte-drift and parser failures. `ContentPackageParser` also omits strict `Json` settings, unknown-enum refusal (`InvalidPackage` instead of `SerializationException`), and pre-constructor/pre-SQLite DAG cycle checks on `parent_id` / `related`.
- **Required fix:** Specify exact lowercase/kebab/snake wire values and canonical JSON formatting in both `compiler.py` and `ContentPackageParser`, and pre-validate DAG acyclicity and enum values before constructing `:core:model` types or inserting into Room.

#### M8. Incomplete CLI Specs, Pre-Commit Triggers, CI Jobs, and Dependabot Coverage

- **Raw findings:** `C-M8` (part), `R-M6` (part), `D-B7` (part), `D-M7` (part), `CD-4`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L341-L347), [requirements-dev.txt](../../../requirements-dev.txt), [.pre-commit-config.yaml](../../../.pre-commit-config.yaml#L54-L60), [.github/workflows/ci.yml](../../../.github/workflows/ci.yml#L24-L51), [.github/dependabot.yml](../../../.github/dependabot.yml#L12-L39)
- **Problem:** CLI flags and error behaviors for `validate_content.py` (`--allow-dirty`, `--source-commit`, missing root config, dual YAML+JSON conflict) and `pack-content` (`--source`, `--out`, `--version`, `--min-app-version`, `--key`, etc.) are incomplete; [.pre-commit-config.yaml](../../../.pre-commit-config.yaml#L54-L60) lacks `ruamel.yaml==0.19.1` and fails to trigger on `tools/content/` changes; [.github/workflows/ci.yml](../../../.github/workflows/ci.yml#L24-L51) lacks `tools/content/tests` + `PYTHONPATH` and `requirements-dev.txt` alignment; and [.github/dependabot.yml](../../../.github/dependabot.yml) does not monitor `/tools/content`.
- **Required fix:** Document full CLI signatures/exit paths and include `.pre-commit-config.yaml`, `requirements-dev.txt`, `.github/workflows/ci.yml`, and `.github/dependabot.yml` updates in Phase 1.

### `:core:model` and `:core:domain` Contracts

#### M9. Sealed Domain Modeling, `prophethood_year`, `CertaintyNote`, and `MapScene` Unification

- **Raw findings:** `Z-M4` (part), `C-M9` (part), `R-B3` (part), `D-B5` (part), `CD-B3` (part), `CD-9` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L322), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L367-L374), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L487-L508)
- **Problem:**
  1. Using flat nullable fields in `HistoricalDate`, `Place`, and `Route` forces runtime `require(...)` checks instead of Kotlin `sealed interface` variants (`HistoricalDate` by precision, `PlaceLocation` `Known` vs `Unknown`, `RouteGeometry` `Documented` vs `Undocumented`) that eliminate invalid states at compile time.
  2. `prophethood_year` is present in SQL (`L422`) and `TimelineModels.kt` (`L369`) but omitted from `data/events.json` (`L322`).
  3. `"PRE_HIJRA descending"` (`L488`) confuses BH numeral descent (`3 BH -> 2 BH -> 1 BH`) with `continuousYear` ascent (`-2 -> -1 -> 0`).
  4. `CertaintyNote`'s module placement (`:core:model` vs `:core:domain`) is unclear; `YEAR`-only events in month view ([exploration-and-story.md](../exploration-and-story.md#L114)) need explicit behavior; and `MapScene` (`L507`) omits `TimeLapse`, `reveal`, `show_area`, `explore_here`, and `panel.linking`.
- **Required fix:** Model date/location/route invariants with sealed types, carry `prophethood_year` in `data/events.json`, clarify chronological `TimelineAxis` wording, specify `YEAR`-in-month behavior, place `CertaintyNote` cleanly, and complete `MapScene` move support (subject to Section 5 Conflict 1 triage).

#### M10. `DependencyRules.kt` Enforcement Gap and Incomplete `D3` ADR 0003 Amendment

- **Raw findings:** `C-M11` (part), `R-M8` (part), `D-M7` (part), `CD-5`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L33), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L59), [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt#L32-L37), [ADR 0003](../../adr/0003-v1-architecture-and-modules.md#L172-L182)
- **Problem:** Spec `L59` suggests [DependencyRules.kt](../../../android/build-logic/convention/src/main/kotlin/app/seerah/buildlogic/DependencyRules.kt#L36) already isolates `:core:domain` from `:core:content`, whereas line 36 (`:core:(model|domain) -X> :core:(map|designsystem|ui|navigation|update)`) does **not** restrict `:core:content` or `:core:userdata`. Moreover, `D3` (`L33`) cites only [ADR 0003](../../adr/0003-v1-architecture-and-modules.md) lines `172` and `182`, missing **line `178`** (the 2026-11-28 amendment stating *"keeping :core:domain (which depends on :core:content) isolated from network libraries"*).
- **Required fix:** Add `:core:domain -X> :core:(content|userdata)` to `DependencyRules.kt` in Phase 2, and update `D3` to amend [ADR 0003](../../adr/0003-v1-architecture-and-modules.md#L172-L182) lines `172`, `178`, and `182`.

### Governance, Scope, and Decisions

#### M11. Misscoped Owner Decisions (`D1–D4`) and `open-decisions.md` / ADR Alignment

- **Raw findings:** `Z-M3` (part), `CD-9` (part), `D-M8` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L25-L48), [open-decisions.md](../../open-decisions.md), [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md#L78-L80), [content.schema.json](../../../content/schema/content.schema.json#L16)
- **Problem:**
  1. Only `D1` (`seerah-content` CI migration cutover) and `D3` (amending accepted [ADR 0003](../../adr/0003-v1-architecture-and-modules.md)) require owner approval. `D2` (Room `3.0.3` vs `2.8.5` internal library choice) and `D4` (deferring the debug preview loader until `:feature:explore`/`:app` exist) are engineering/milestone choices governed by [AGENTS.md](../../../AGENTS.md) and [engineering.md](../../engineering.md). Note also that deferring `D4` requires honoring [ADR 0007](../../adr/0007-content-formats-compatibility-and-signing.md#L78-L80) decision 10's release-build guard when preview later lands.
  2. [content.schema.json](../../../content/schema/content.schema.json#L16) allows `"gregorian"` while [open-decisions.md](../../open-decisions.md#L14) leaves Gregorian conversion open — new YAML schemas (`content-source.schema.json` / `content-package.schema.json`) must explicitly forbid `"gregorian"`. Off-map events (`al-Isra` outside `dataExtent` per [open-decisions.md](../../open-decisions.md#L13) and [exploration-and-story.md](../exploration-and-story.md#L80)) also need an explicit placeholder/no-coordinate representation rather than failing extent bounds.
- **Required fix:** Keep `D1` and `D3` in the Owner Decisions table, move `D2` and `D4` into internal technical/scope sections (preserving ID traceability), forbid `"gregorian"` in `content-source.schema.json` and `content-package.schema.json`, and specify how off-extent places (`al-Isra`) behave without hard-coding undecided bounds.

---

## 4. Minor Findings and Nits

### N1. Remove Scratchpad Prose and Fix Terminology / Diagram Mismatches

- **Raw findings:** `C-B1` (prose part), `C-M8` (typo), `C-M11` (diagram), `C-N13`, `Z-N5` (part), `R-N9`, `D-B4` (part), `CD-6` (part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L83-L85), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L337-L339), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L438), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L452-L453), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L514)
- **Issues:** Replace conversational inline notes (`L438`, `L452–453`, `L514`) with crisp normative specifications; fix `"phase event"` -> `"phase, event"` (`L339`); remove the redundant `"visibility: in-context or always"` clause (`L337`, since those are the only two `Visibility` values); align Mermaid edge labels (`implementation/api` at `L85` vs `api` at `L131`) and clarify `packer -.-> content` (`L83`) as offline `.zip` artifact flow rather than a Gradle dependency.

### N2. Standardize Neutral Placeholder Coordinates Across Examples

- **Raw findings:** `CD-6` (placeholder coordinates part)
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L210), [map-data/README.md](../../../map-data/README.md#L26)
- **Issues:** Alongside removing the real Madinah coordinates (`B10`), standardize all synthetic place/area/route coordinates in `content/example/` and Android/Python test suites on the neutral Masirah Island window (`20.50–20.75° N, 58.80–59.05° E`) used by `map-data/example/`.

### N3. Define Acronyms, Path Conventions, and Machine-Local Tooling Paths

- **Raw findings:** `C-N12`, `C-N13` (`SqliteBundled`), `CD-N10`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L19-L20), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L56), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L522)
- **Issues:** Briefly spell out/explain `seerah.jvm.library`, `BH`/`AH` (`ق.هـ`/`هـ`), and `<filesDir>/content/content/` (`content` root + `PackageKind.CONTENT.wireName`); normalize `SqliteBundled` (`L522`) to `sqlite-bundled`; and replace the machine-local path `/opt/android-studio/jbr` (`L56`) with generic `JDK 21`.

### N4. Trim Over-Prescriptive Micro-Formulas and Complete Exit-Gate Documentation List

- **Raw findings:** `Z-N5` (micro-formulas), `R-N10`
- **Scope:** [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L436), [v1-content-pipeline-and-domain-spec.md](../v1-content-pipeline-and-domain-spec.md#L531-L533)
- **Issues:** Keep spec text focused on architectural invariants rather than fragile micro-formulas/file-count prescriptions where implementation owns details, and add [docs/README.md](../../README.md), [content/README.md](../../../content/README.md), and [tools/README.md](../../../tools/README.md) explicitly to the Phase 1/3 exit-gate documentation checklist per [AGENTS.md](../../../AGENTS.md) Definition of Done #4.

---

## 5. Triage Summary and Reviewer Consensus

### Reviewer Consensus

All five reviewers independently converged on the following non-negotiable conclusions:

1. **Core technology choices hold:** Strict YAML 1.2 authoring, deterministic `content_schema: 1` normalized JSON inside `ZIP_DEFLATED` signed packages, disposable Room 3 (`3.0.3` + `AndroidSQLiteDriver` + `JournalMode.TRUNCATE`) in generation-scoped folders, pure-JVM `:core:domain -> :core:model` (`D3`), deferred debug preview (`D4`), and lazy-imported legacy JSON fallback (`D1`) are all sound once defects are fixed.
2. **Immediate safety/correctness fixes required before coding:** Resolve the `DefaultPackageInstaller` delete-before-close race, cold-start hydration deadlock, `JournalMode.TRUNCATE` on open, `minSdk = 26` `@Upsert` prohibition, `androidx.sqlite.SQLiteException` (`SQLITE_FULL`) mapping, `seerah-content` CI lazy YAML import, test-stub migration, `IN_CONTEXT` / `PRE_HIJRA` / `UNKNOWN` domain bugs, and the real Madinah coordinates leak (`L210`).

### Conflict Triage and Recommendations

Where reviewers diverged (`egm-zoom-out` vs. detail/readiness/codebase reviewers and existing ADRs), the table below states the competing positions and recommended resolution for the owner and author to weigh:

| Conflict Topic | Competing Positions | Codebase / ADR Cross-Check & Triage Recommendation |
| --- | --- | --- |
| **1. `:core:domain` Scope (`FollowAlongEngine`, `MapSceneBuilder`, 10-method `ContentRepository`)** | **`egm-zoom-out` (`Z-B1`):** Defer `FollowAlongEngine`, `MapSceneBuilder`, and unused `ContentRepository` queries until `:core:userdata` (`ExploreSession`), `:core:map` overlays, and `:feature:*` exist; scope Phase 2 strictly to `TimelineAxis`, `TimeStateResolver`, and `CertaintyNoteResolver`. **Detail reviewers (`C`, `R`, `D`, `CD`):** Keep and fix `FollowAlongEngine`, `MapSceneBuilder`, and `ContentRepository` in place. | **Author/Owner Decision Needed (Recommended Compromise):** [ADR 0003](../../adr/0003-v1-architecture-and-modules.md#L27) (`L27`, `L142`, `L160`) and [ADR 0005](../../adr/0005-content-model-exploration-and-story.md#L37) explicitly assign **both time resolution and follow-along rules** to pure-JVM `:core:domain`, so omitting `FollowAlongEngine` entirely conflicts with the accepted architecture track. **Recommended resolution:** Keep `TimelineAxis`, `TimeStateResolver`, `CertaintyNoteResolver`, and the core `FollowAlongEngine` state machine (`stepId + fallbackStepIndex` bookmarks, `resume()`, loop-safe `onExploreTimeChanged`) in Phase 2 to prove ADR 0003/0005 rules on JVM; **keep `MapSceneBuilder` minimal (or defer map-rendering directives like `dimBackground` / `CameraDirective`)** and trim `ContentRepository` to the concrete methods needed by `:core:domain` until `:core:userdata` and `:core:map` overlay specs land. |
| **2. Room 3 Table Count (16 vs. 8–9), `db_stamp.json`, and Permanent `data/*.json`** | **`egm-zoom-out` (`Z-B2`):** Collapse 16 tables into ~8–9 flat indexed tables, drop `db_stamp.json` (use `PRAGMA user_version` + `meta`), and stream directly from `package.zip` into Room without retaining `data/*.json` on disk. **Detail reviewers (`C`, `R`, `D`, `CD`):** Harden the 16-table schema, fix `StatFs` for `package.zip` + `data/*.json` + `content.db`, and make `content.db` + `db_stamp.json` rebuild atomic. | **Adopt `egm-zoom-out`'s storage and stamp simplifications (`B7`, `M1`, `M3`):** Dropping permanent unpacked `data/*.json` eliminates 3x disk retention and unifies initial install + disposable rebuild into one streaming `package.zip -> Room` importer (updating [DefaultPackageStorage.kt](../../../android/core/content/src/main/kotlin/app/seerah/content/DefaultPackageStorage.kt#L208)). Replacing `db_stamp.json` with SQLite `PRAGMA user_version` + `meta` eliminates 2-file atomic swap races. For table count, keep indexed relational tables only for queried collections (`events`, `places`, `areas`, `routes`, `texts`, `phases`, `sources`) and collapse deeply nested read-whole structures (`stories` / `chapters` / `scenes` / `steps`, or `place_states` / `area_states` if always loaded with their parent) unless SQL-level filtering is needed. |
| **3. Scope of Owner Decisions (`D1–D4`)** | **`egm-zoom-out` (`Z-M3`):** Only `D1` (`seerah-content` CI coordination) and `D3` (amending ADR 0003) are owner decisions; `D2` (Room 3 vs 2.8) and `D4` (deferring debug preview) are internal technical/milestone choices. **Detail reviewers:** Amend `D1`/`D3` in place and note `D4` vs ADR 0007 decision 10. | **Adopt `egm-zoom-out` (`M11`):** Matches the precedent established in [egm-review-v1-android-package-and-map/consolidated_review_report.md](../egm-review-v1-android-package-and-map/consolidated_review_report.md#L61). Keep `D1` and `D3` (amending ADR 0003 lines `172`, `178`, `182`) in the Owner Decisions gate; move `D2` and `D4` into internal technical/non-goal subsections while preserving ID traceability. |
| **4. 5-Way Validation Duplication vs. Defense-in-Depth** | **`egm-zoom-out` (`Z-M4`):** Eliminate duplicate checks across JSON schemas, `checks.py`, `archive.py`, `ContentPackageParser`, and `:core:model` `init`; use sealed Kotlin interfaces. **Detail reviewers (`R`, `D`, `CD`):** Validate `content_schema: 1` inside `verify_package`, harden `ContentPackageParser`, and enforce `:core:model` invariants. | **Adopt clean layer ownership (`M5`, `M7`, `M9`):** (1) `content-source.schema.json` + `tools/content/checks.py` own editorial, story, and cross-reference authoring rules; (2) `tools/packages/archive.py` checks **only** `content-package.schema.json` + `content_schema == 1` (no duplicated business cross-checks); (3) `:core:model` replaces nullable permutations with sealed interfaces (`HistoricalDate`, `PlaceLocation`, `RouteGeometry`); and (4) `ContentPackageParser` performs strict JSON decoding + DAG checks once prior to Room insertion. |

### Complete Traceability Matrix

| Reviewer | Raw Finding ID | Consolidated Destination |
| --- | --- | --- |
| `egm-comprehension` | `C-B1` (scratchpad prose, `L118` vs `L514` vs `L214..270`) | `B9`, `N1` |
| `egm-comprehension` | `C-B2` (YAML -> JSON -> Model -> Room structural gaps) | `B3` |
| `egm-comprehension` | `C-B3` (pre-Hijra `continuousYear`, `UNKNOWN`, `precision_rank`, `endOfYear`) | `B4` |
| `egm-comprehension` | `C-B4` (`EventDetail`, `observeAllSources`, tearing, 3 state names) | `B5` |
| `egm-comprehension` | `C-B5` (`D1` `seerah-content` CI `jsonschema`-only trap, 26-file migration) | `B6` |
| `egm-comprehension` | `C-B6` (disposable rebuild lifecycle, `quick_check`, switch race, `content_schema` bump) | `B1`, `B2` |
| `egm-comprehension` | `C-M7` (`world/`/`area_changes`, `schema` vs `content_schema`, `title` vs `name`, enums, `date_note`, `min/max`) | `B3`, `M6`, `M7` |
| `egm-comprehension` | `C-M8` (editorial `source`, `"phase event"`, draft nullability, CLI flags, `explanationSource`) | `M6`, `M8`, `N1` |
| `egm-comprehension` | `C-M9` (`prophethood_year`, `"PRE_HIJRA descending"`, `Area` filter, `CertaintyNote`, `FollowAlongBookmark`, `MapScene`) | `B4`, `M9` |
| `egm-comprehension` | `C-M10` (3x disk, `2L * sumOf`, `parent_id` topological sort, `explanation_source_work_id` FK, `ContentDatabase`) | `B3`, `M1`, `M2` |
| `egm-comprehension` | `C-M11` (Mermaid labels, `packer -.-> content`, `D3` ADR 0003 lines) | `M10`, `N1` |
| `egm-comprehension` | `C-N12`, `C-N13` (acronyms, `<filesDir>/content/content/`, `SqliteBundled`, `Visibility` wording) | `N1`, `N3` |
| `egm-zoom-out` | `Z-B1` (premature `FollowAlongEngine`/`MapSceneBuilder`/`ContentRepository` vs ADR 0003) | `B8`, Conflict 1 |
| `egm-zoom-out` | `Z-B2` (16 -> 8-9 tables, 3x disk/`data/*.json`, `db_stamp.json`, delete race) | `B1`, `B2`, `B7`, `M1`, `M3`, Conflict 2 |
| `egm-zoom-out` | `Z-M3` (`D1–D4` scoping, lazy `ruamel.yaml`, `precision: unknown` location) | `B4`, `B6`, `M11`, Conflict 3 |
| `egm-zoom-out` | `Z-M4` (5-way validation duplication, sealed Kotlin types, `archive.py`, `strict_yaml.py`) | `M5`, `M9`, Conflict 4 |
| `egm-zoom-out` | `Z-N5` (draft scratchpad, `expedition_role`, micro-formulas) | `B3`, `B9`, `N1`, `N4` |
| `egm-implementation-readiness` | `R-B1` (YAML & `data/*.json` contradictions across entities and story moves) | `B3` |
| `egm-implementation-readiness` | `R-B2` (filename-to-ID checks, `years/`, standalone `always`, `draft`/`approved` & `FIXTURE_SOURCE_COMMIT`) | `B9` |
| `egm-implementation-readiness` | `R-B3` (nullability, `EventDetail`, `precision_rank`, `IN_CONTEXT` markers, `YEAR` in month, `FollowAlongEngine`, `UNKNOWN`) | `B4`, `B5`, `M9` |
| `egm-implementation-readiness` | `R-B4` (nullable/FK SQL columns, `@Dao`, `@ConstructedBy`, DAGP `room3-common`/`sqlite`, `SQLiteException` 13) | `B2`, `B3`, `M4` |
| `egm-implementation-readiness` | `R-B5` (`DefaultPackageInstaller` delete race, `Context` coupling, rotation rebuild, `INVALID_CONTENT_PAYLOAD`, `StatFs`) | `B1`, `B2`, `M2` |
| `egm-implementation-readiness` | `R-M6` (`pack-content` flags, `D1` CLI errors, `.pre-commit-config.yaml`, `ci.yml` `tools/content/tests`) | `B6`, `M8` |
| `egm-implementation-readiness` | `R-M7` (breaking `test_archive_and_cli.py` stubs, JSON determinism, `TimeLapse` `exists == true`) | `B6`, `M6`, `M7` |
| `egm-implementation-readiness` | `R-M8` (`sqlite-bundled` `testImplementation`, `r8-smoke-keep.pro`, `DependencyRules.kt`) | `M4`, `M10` |
| `egm-implementation-readiness` | `R-N9`, `R-N10` (scratchpad prose, exit-gate doc links `docs/README.md`, `content/README.md`, `tools/README.md`) | `N1`, `N4` |
| `egm-design` | `D-B1` (`DefaultPackageInstaller` / `cleanupOrphanedFiles` vs open `v1Db`, pin set + lease-counted handle) | `B1` |
| `egm-design` | `D-B2` (cold-start infinite `Loading` deadlock, `CoroutineScope` ownership vs `engineering.md`) | `B1` |
| `egm-design` | `D-B3` (corrupt lockout, 2-file `content.db`+`db_stamp.json`, missing `JournalMode.TRUNCATE` on open, `SQLiteException`, `absolutePath`) | `B1`, `B2` |
| `egm-design` | `D-B4` (rebuild `manifest.sig` check, full-AST memory load on Oppo A55, inline scratchpad notes) | `B2`, `B9`, `M1` |
| `egm-design` | `D-B5` (`in-context` marker leak, `expedition_role`, `UNKNOWN`/`Baseline`, same-year `YEAR` `place_states`) | `B3`, `B4` |
| `egm-design` | `D-B6` (`related`, `StepPanel.linkingSentence`, `story_scenes.event_id`, `StepMovement`/`StepTime`, `Route.stations`, `EventDetail`, `excerpt_of`) | `B3`, `B4`, `B5`, `M6` |
| `egm-design` | `D-B7` (CI/pre-commit gaps, `@ConstructedBy`, **`@Upsert` banned on `minSdk = 26`**, DAGP `room3-common`/`sqlite`, `r8-smoke-keep.pro`) | `B2`, `B6`, `M4`, `M8` |
| `egm-design` | `D-M1` (`ContentRepository` `Loading` vs `NotInstalled` / `combine()` deadlock, `StorySummary`, streaming JSON) | `B5`, `M1` |
| `egm-design` | `D-M2` (`StatFs` `3L *`, `copiedContentZipEarly` double-count, `ENOSPC` `requiredBytes`, rebuild `StatFs`) | `M2` |
| `egm-design` | `D-M3` (`FollowAlongEngine` `resume()`, `StepTime` self-suspension loop, `stepId + fallbackStepIndex`) | `B4` |
| `egm-design` | `D-M4` (`ContentPackageParser` strict codec, unknown enums, DAG cycle pre-checks) | `M7` |
| `egm-design` | `D-M5` (`StrictYaml` `SafeComposer` aliases, `load_all`, non-`str` keys, NaN/Inf) | `M5` |
| `egm-design` | `D-M6` (extract `ContentDatabaseImporter` / `ContentDatabaseOpener` for JVM tests) | `B2` |
| `egm-design` | `D-M7` (`D1` lazy import + dual-format refusal, `D3` ADR 0003 `L178`) | `B6`, `M8`, `M10` |
| `egm-design` | `D-M8` (`RouteTarget` markers, `polygon_ring`, off-extent `al-Isra`, `observeEventsInYear`) | `B4`, `B5`, `M6`, `M11` |
| `egm-design` | `D-N1..D-N5` (5 minor/nit items listed in `egm-design` summary without itemized transcript text) | `N1–N4` (unitemized in transcript) |
| `egm-codebase-details` | `CD-B1` (`DefaultPackageInstaller.kt:160-166` deletes `v1-g1/` under `storageMutex` before `v1Db` closes) | `B1` |
| `egm-codebase-details` | `CD-B2` (`archive.verify_package` breaks `test_archive_and_cli.py`, `PackageStorageAndInstallerTest`, `ContentDeviceSmokeTest`) | `B6` |
| `egm-codebase-details` | `CD-B3` (Sections 1–5 contradictions, `Situation.place`, `StoryStep` moves, `MapScene` `TimeLapse`, route station mapping) | `B3`, `B5`, `M9` |
| `egm-codebase-details` | `CD-M4` (`D1` lazy import `validate.yml:30-31`, `.pre-commit-config.yaml:54-60`, `ci.yml`, `dependabot.yml`) | `B6`, `M8` |
| `egm-codebase-details` | `CD-M5` (`D3` missing ADR 0003 `L178`, `DependencyRules.kt:32-37` allows `:core:domain -> :core:(content\|userdata)`) | `M10` |
| `egm-codebase-details` | `CD-M6` (real Madinah coords `L210` violating `AGENTS.md` / Non-Goal #3, `review: approved` vs `draft`, scratchpad prose) | `B9`, `B10`, `N1`, `N2` |
| `egm-codebase-details` | `CD-M7` (`tools/map/run.py:31` `PYTHONPATH`, rebuild DB/key reuse, `copiedContentZipEarly` `StatFs`, test call sites) | `B2`, `M2`, `M3`, `M4`, `M5` |
| `egm-codebase-details` | `CD-M8` (`HiltConventionPlugin.kt:11` duplicate KSP, DAGP, `@ConstructedBy`, `sqlite-bundled`, `SQLiteException`, JSON schema hooks) | `B2`, `M3`, `M4` |
| `egm-codebase-details` | `CD-M9` (`"gregorian"`, `YEAR` in month, `UNKNOWN` sort, `al-Isra`, `editorial` `source`, `D4` CI guard) | `B4`, `M6`, `M9`, `M11` |
| `egm-codebase-details` | `CD-N10` (machine-local `/opt/android-studio/jbr` at `L56`) | `N3` |
| `egm-codebase-details` | `CD-N11` (3x disk footprint keeping `data/*.json` per `DefaultPackageStorage.kt:208`) | `B7`, `M1` |
