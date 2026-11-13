# android

The Android project for the v1 app, built to [ADR 0003](../docs/adr/0003-v1-architecture-and-modules.md) (architecture and modules) and [ADR 0004](../docs/adr/0004-build-and-quality-tooling.md) (build and quality tooling). Standards: [docs/engineering.md](../docs/engineering.md).

## Layout

```text
android/
  build-logic/   convention plugins: the shared Gradle setup every module applies
  gradle/        version catalog (libs.versions.toml) and the Gradle wrapper
  app/           :app, the application module
  spike/         the finished technical spike, a separate build (reference only)
```

Modules from ADR 0003 (`core/*`, `feature/*`) are added as their first real code lands; until then they don't exist.

## Requirements

- JDK 21.
- Android SDK with platform 37 (Android 17). Point Gradle at it with `local.properties` (`sdk.dir=…`, not committed) or `ANDROID_HOME`.

## Commands

Run from `android/`:

```sh
./gradlew assembleDebug        # build a debug APK
./gradlew spotlessApply        # format Kotlin and Gradle files
./gradlew check                # lint (warnings fail), unit tests, module rules, licences
./gradlew buildHealth          # unused or wrongly declared dependencies
./gradlew assembleRelease      # R8-shrunk release APK (debug-signed until the beta)
python3 ../tools/android/check_page_alignment.py app/build/outputs/apk/release/app-release.apk
```

CI (`.github/workflows/android.yml`) runs all of these on every push and pull request that touches `android/` (except the spike), and attaches the release APK to the run as `seerah-app`.

## Convention plugins

| Plugin | What it sets up |
| --- | --- |
| `seerah.android.application` | SDK levels (min 26, compile and target 37), Java 17 bytecode, lint with warnings as errors, Kotlin warnings as errors, R8 for release, the licence check, the module dependency rules and the MapLibre boundary |
| `seerah.android.application.compose` | Compose compiler, Compose BOM and preview tooling |
| `seerah.hilt` | Hilt with KSP |

Library, feature and JVM conventions are added with the first module that needs them.

## Rules the build enforces

- **Module graph** (`assertModuleGraph`, part of `check`): features never depend on features; `core:model` and `core:designsystem` depend on no module; `core:model` and `core:domain` never depend on UI or map modules.
- **MapLibre boundary:** only `:core:map` may declare a MapLibre dependency.
- **Licences** (Licensee): runtime dependencies must be Apache-2.0, MIT, BSD-2-Clause or BSD-3-Clause.
- **Dependency health** (`buildHealth`): unused or misdeclared dependencies fail.
- **Formatting** (Spotless with ktlint and Compose Rules, configured in the root `.editorconfig`).

## The app today

A single activity showing the app name, laid out right to left whatever the device language. In place:

- Hilt, edge-to-edge, Arabic as the only locale (`res/resources.properties`, generated locale config).
- Backup rules that exclude the future content folder (`files/content/`); user-data backup awaits its open decision.
- Debug builds: StrictMode (logs main-thread disk and network use) and LeakCanary.
- A placeholder launcher icon until the logo is designed.

## Set up when first needed

Kept out until there is code for them to work on: Roborazzi screenshot tests (with the design system), Kover coverage (with the first logic module), the Gradle Managed Device for device tests, and `:benchmarks` with Baseline Profiles (with the first real screen).
