# 0004. Build and quality tooling

**Status:** Proposed (2026-11-13). Needs the owner's approval before the app is scaffolded.

## Context

The owner's bar is quality first: clean code, a sound structure, real tests and documentation, and checks that catch problems before they reach a device. With about 18 modules ([ADR 0003](0003-v1-architecture-and-modules.md)), the build setup itself must not be copy-pasted between modules, and every rule must be enforced by a tool, not by memory.

## Options compared

| Need | Choice | Compared with | Why |
| --- | --- | --- | --- |
| Shared build setup | **Convention plugins** in an included `build-logic` build, versions in a Gradle **version catalog** | `buildSrc`; repeated build files | The pattern Now in Android uses; `buildSrc` invalidates the whole build on any change |
| Formatting and style | **Spotless** running **ktlint** with the **Compose Rules** rule set | kotlinter; IDE formatting only | One command formats Kotlin and Gradle files; Compose Rules catch Compose mistakes (state hoisting, modifier order, naming). Now in Android uses Spotless + ktlint |
| Static analysis | **Android Lint** with warnings as errors | detekt | Lint knows Android and Compose. detekt 2.0 is still in alpha and 1.x lags behind Kotlin 2.4; add it when 2.0 is stable |
| Unit tests | **JUnit 4**, `kotlin.test` assertions, kotlinx-coroutines-test, **Turbine** for flows, **Robolectric** where Android classes are needed | JUnit 5 through a third-party plugin | JUnit 4 is what the Android toolchain, Robolectric and Compose tests support out of the box |
| Screenshot tests | **Roborazzi** (on Robolectric) | Compose Preview Screenshot Testing (Google, still alpha); Paparazzi (Cash App) | Mature, works with Hilt and Robolectric, used by Now in Android. Revisit Google's tool when it is stable |
| Device tests | AndroidX Test and Compose UI tests; a **Gradle Managed Device** in CI | Firebase Test Lab | Free on GitHub's Linux runners; the three owner devices stay the final check |
| Coverage | **Kover** reports in CI | JaCoCo | Kotlin-aware; thresholds set per module once the first features land |
| Performance | **Baseline Profiles** and **Macrobenchmark** (`:benchmarks`), R8 in release | Manual timing only | The official way to cut startup and jank; measures against the ADR 0002 targets |
| Dependency licences | **Licensee** (allowed SPDX list checked at build time) and an in-app open-source notices screen | Manual review | A dependency with a disallowed licence fails the build; fonts (OFL) and MapLibre (BSD) need attribution |
| Dependency updates | **Dependabot** for the version catalog and GitHub Actions (already set up) | Renovate | Built into GitHub, enough for one repository |

## Decision

### Build

- Gradle with Kotlin DSL; one version catalog (`android/gradle/libs.versions.toml`); convention plugins for application, library, Compose, feature, Hilt, test and Roborazzi setup. A module's build file names its plugins and its dependencies, nothing else.
- Latest stable Kotlin, Android Gradle Plugin and AndroidX versions at scaffolding time; no alpha or beta libraries without an ADR note.
- JDK 21 toolchain; Gradle configuration cache and build cache on.
- Build types: `debug`, `release` (R8, shrunk) and the benchmark variant. No product flavors (OpenGL only, per ADR 0002).
- Release signing key in GitHub secrets, never in git. Until the beta, release builds for testing are signed with the debug key, as in the spike.

### Checks

| Check | Local | CI (every push and pull request) |
| --- | --- | --- |
| Repo-wide hooks (Markdown, JSON, YAML, Python, secrets, commit messages) | pre-commit | yes |
| Kotlin and Gradle formatting | `./gradlew spotlessApply` | `spotlessCheck` |
| Android Lint | `./gradlew lint` | yes, warnings fail the build |
| Unit and screenshot tests | `./gradlew test`, `verifyRoborazzi` | yes |
| Content validation | `python3 tools/validate_content.py` | yes |
| Release build | `./gradlew assembleRelease` | yes, APK attached as an artifact |
| Device tests (Managed Device) | on demand | on pull requests that touch the app |
| Licences | part of the build | yes |

A pull request merges only when every CI check is green.

### Tests by layer

- `core:model`, `core:domain`: plain JVM unit tests, the bulk of the logic (time resolution, date precision).
- `core:content`, `core:update`, `core:userdata`: unit tests with fakes and temporary files; device tests for install, interruption and offline start.
- ViewModels: unit tests with fake repositories and Turbine.
- `core:designsystem`, `core:ui`, feature screens: Roborazzi screenshots for phone and tablet, RTL, large font scale.
- `core:map`: unit tests for the style and layer logic; MapLibre rendering is checked on devices.

## Consequences

- Formatting and Compose mistakes are fixed by a command, not discussed in review.
- Screenshot baselines live in git (small PNGs); a UI change shows up as an image diff in the pull request.
- CI time grows with the module count; the Gradle build cache and caching in GitHub Actions keep it reasonable.
- detekt and Google's screenshot tool are tracked to adopt once stable.

## References

- [Now in Android build-logic](https://github.com/android/nowinandroid/blob/main/build-logic/README.md).
- [Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing) and its [release notes](https://developer.android.com/studio/preview/compose-screenshot-testing-release-notes) (still alpha).
- [detekt 2.0 changelog](https://detekt.dev/changelog-2.0.0/) (still alpha).
- [Screenshot testing compared: Paparazzi, Roborazzi and Compose Preview](https://academy.droidcon.com/course/master-screenshot-testing-on-android-comparing-paparazzi-roborazzi-and-compose-preview-tools), droidcon.
