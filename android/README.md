# android

The Android project for the v1 app, built to [ADR 0003](../docs/adr/0003-v1-architecture-and-modules.md) (architecture and modules) and [ADR 0004](../docs/adr/0004-build-and-quality-tooling.md) (build and quality tooling). Standards: [docs/engineering.md](../docs/engineering.md).

## Layout

```text
android/
  build-logic/   convention plugins: the shared Gradle setup every module applies
  gradle/        version catalog (libs.versions.toml) and the Gradle wrapper
  app/           :app, the application module
  core/map/      :core:map, the map module: the only one that touches MapLibre (see its README)
  spike/         the finished technical spike, a separate build (reference only)
```

Other modules from ADR 0003 (`core/*`, `feature/*`) are added as their first real code lands; until then they don't exist.

## Requirements

- JDK 21.
- Android SDK with platform 37.2 (Android 17). Point Gradle at it with `local.properties` (`sdk.dir=…`, not committed) or `ANDROID_HOME`.

## Commands

Run from `android/`:

```sh
./gradlew assembleDebug        # build a debug APK
./gradlew spotlessApply        # format Kotlin and Gradle files
./gradlew check                # lint (warnings fail), unit tests, module rules, licences
./gradlew buildHealth          # unused or wrongly declared dependencies
./gradlew assembleRelease      # R8-shrunk release APK (debug-signed until the beta)
python3 ../tools/android/check_page_alignment.py app/build/outputs/apk/release/app-release.apk
python3 ../tools/android/check_no_trial_code.py app/build/outputs/apk/release/app-release.apk
python3 -m unittest discover -s ../tools/android/tests   # the release guard's own tests
./gradlew assembleBenchmark    # the benchmark APK, for measuring on devices
```

## Build types

| Build type | Used for |
| --- | --- |
| `debug` | Development: LeakCanary, no shrinking. |
| `release` | The shipped app: R8-shrunk, debug-signed until the beta. Contains no map-trial code; `tools/android/check_no_trial_code.py` fails when its manifest names an `app.seerah.trial` component (ADR 0007, decision 10). |
| `benchmark` | Release-like build for measuring on devices (`:app:assembleBenchmark`, application ID `app.seerah.benchmark`). Never published. The only build type that may depend on `:core:map`; its unit tests run with `check` (`:app:testBenchmarkUnitTest`). |

CI (`.github/workflows/android.yml`) runs all of these on every push and pull request that touches `android/` (except the spike), runs the release guard and its tests, checks 16 KB page alignment on the release and benchmark APKs, and attaches the release APK to the run as `seerah-app` and the benchmark APK as `seerah-app-benchmark`.

## Running the map trial

The benchmark APK carries the map trial screen, launched from its own launcher icon "Map trial".

1. Download the artifact `seerah-app-benchmark` from the `Android` workflow run (or build it with `./gradlew assembleBenchmark`; the APK is in `app/build/outputs/apk/benchmark/`).
2. Install it. Its application ID is `app.seerah.benchmark`, so it installs beside the normal app without replacing it: `adb install app-benchmark.apk`.
3. Push each variant folder (a folder produced by the map pipeline) into the app's external files folder. Each one must land as `trial/<folder>/`, because the `<folder>/<view id>` selection value uses that folder name. Create the parent first, then push, for example:

   ```sh
   adb shell mkdir -p /sdcard/Android/data/app.seerah.benchmark/files/trial
   adb push zoom12 /sdcard/Android/data/app.seerah.benchmark/files/trial/zoom12
   ```

4. Open "Map trial" and pick the folder and view.

Two rules for the runs:

- **Runs that measure open time** (checklist item 11) must launch with the `app.seerah.trial.VIEW` intent extra set to `<folder>/<view id>` (for example `adb shell am start -S -W -n app.seerah.benchmark/app.seerah.trial.TrialActivity --es app.seerah.trial.VIEW <folder>/zoom12`), or with a valid last-used view already stored. Otherwise the view picker appears first and its waiting time is counted as open time. Force-stop the app before each open-time run (`-S` does this): open time is measured once per process, so a map opened in a process that was already running shows the earlier value or counts the time the process sat idle; the overlay marks such a reading "(from warm process)".
- **Do not rotate the device during a camera-path run.** Rotation recreates the screen and loses that run's results.

## Convention plugins

| Plugin | What it sets up |
| --- | --- |
| `seerah.android.application` | SDK levels (min 26, compile 37.2, target 37), Java 17 bytecode, lint with warnings as errors, Kotlin warnings as errors, R8 for release, the licence check, the module dependency rules, the MapLibre boundary, the `benchmark` build type and the rule that keeps `:core:map` out of other build types |
| `seerah.android.application.compose` | Compose compiler, Compose BOM and preview tooling |
| `seerah.android.library` | Like the application plugin for a library module: SDK levels, lint, Kotlin settings, the licence check and the MapLibre boundary (the module dependency rules run from `:app`) |
| `seerah.android.library.compose` | Compose compiler and Compose BOM for a library module, without preview tooling |
| `seerah.hilt` | Hilt with KSP |

Feature and JVM conventions are added with the first module that needs them.

## Rules the build enforces

- **Module graph** (`assertModuleGraph`, part of `check`): features never depend on features; `core:model` and `core:designsystem` depend on no module; `core:model` and `core:domain` never depend on UI or map modules.
- **MapLibre boundary:** only `:core:map` may declare a MapLibre dependency.
- **Map only in `benchmark`:** `:app` may depend on `:core:map` only through `benchmark`, `testBenchmark` and `androidTestBenchmark` configurations.
- **Licences** (Licensee): runtime dependencies must be Apache-2.0, MIT, BSD-2-Clause or BSD-3-Clause.
- **Dependency health** (`buildHealth`): unused or misdeclared dependencies fail. The convention plugins apply the Dependency Analysis plugin to every module; the root build only configures it (ADR 0004).
- **Formatting** (Spotless with ktlint and Compose Rules, configured in the root `.editorconfig`).

## The app today

A single activity showing the app name, laid out right to left whatever the device language. In place:

- Hilt, edge-to-edge, Arabic as the only locale (`res/resources.properties`, generated locale config).
- Backup rules that exclude the future content folder (`files/content/`); user-data backup awaits its open decision.
- Debug builds: StrictMode (logs main-thread disk and network use) and LeakCanary.
- A placeholder launcher icon until the logo is designed.

## Set up when first needed

Kept out until there is code for them to work on: Roborazzi screenshot tests (with the design system), Kover coverage (with the first logic module), the Gradle Managed Device for device tests, and `:benchmarks` with Baseline Profiles (with the first real screen).
