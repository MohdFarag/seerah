# Engineering standards

How the app, the tools and the content pipeline are built. These standards apply to every change, by people and by AI agents. The stack is fixed in [ADR 0002](adr/0002-proposed-stack.md); the architecture and module layout in [ADR 0003](adr/0003-v1-architecture-and-modules.md) and the build and quality tooling in [ADR 0004](adr/0004-build-and-quality-tooling.md) (both Proposed until the owner approves).

## Principles

1. **Quality over speed.** Do it properly once. A shortcut needs a written reason and an issue to remove it.
2. **Follow the official guidance first.** Android's [Guide to app architecture](https://developer.android.com/topic/architecture), [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html), [Compose API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md) and [Material 3](https://m3.material.io/). Google's [Now in Android](https://github.com/android/nowinandroid) app is our reference for how these fit together.
3. **Compare before choosing.** For any significant choice, see *Choosing between options* below.
4. **Simple beats clever.** The smallest design that meets the requirement and leaves room for the next one.
5. **Everything the owner sees is checked.** On a device, in RTL, on a phone and a tablet.

## Architecture (app)

Baseline, refined by the v1 architecture ADR:

- **Layers:** UI (Compose screens + ViewModels) → domain (use cases, only where logic is shared or complex) → data (repositories over Room, files and the network). Dependencies point inward only.
- **Unidirectional data flow:** ViewModels expose immutable UI state as `StateFlow`; the UI sends events up. No business logic in composables.
- **Modules by feature,** plus shared `core` modules (design system, data, model, map, testing). Feature modules never depend on each other.
- **Dependency injection** with one framework across the app.
- **Single source of truth per data type.** Content (replaceable, versioned) and user data (progress, settings) live apart, so a content update never touches progress.
- **Offline is the normal case.** The app never needs the network except to download a content update the user asked for.
- **Map code stays behind an interface** in a map module, so screens don't talk to MapLibre directly. The map is created once and stays on screen; other screens are panels over it.

## Code

- **Kotlin:** official conventions, enforced by a formatter and static analysis in pre-commit and CI. Warnings are errors in CI.
- **Naming:** say what it is or does. No abbreviations except well-known ones (`id`, `url`). Types are nouns, functions are verbs, booleans read as questions (`isVisible`).
- **Size:** a function does one thing; a file holds one main type. If it needs a comment to explain *what* it does, split or rename it. Comments explain *why*.
- **No magic values:** numbers, colours, durations and zoom levels are named constants or design tokens.
- **No hard-coded user text.** All strings live in resources (Arabic). Code never builds sentences by concatenation.
- **Errors:** model expected failures as results (`sealed` types); exceptions are for bugs. Never swallow an error silently; never show a raw error to the user.
- **Immutability by default:** `val`, read-only collections, data classes for state.
- **Coroutines:** structured concurrency only (`viewModelScope`, injected dispatchers). No `GlobalScope`.
- **Dependencies:** declared once in the Gradle version catalog, pinned, updated by Dependabot. Before adding one: is it maintained, licensed compatibly, and worth its size? Record notable ones in an ADR.
- **No dead code,** no commented-out code, no `TODO` without an issue link.

## Compose and UI

- Screens are stateless composables fed by state; a thin stateful wrapper connects them to the ViewModel.
- Every reusable component lives in the design system module, built from the Penpot tokens (colour, type, spacing, radius). No one-off colours or sizes in screens.
- Every screen and component has `@Preview`s for phone and tablet, light and dark if supported, RTL, and large font scale.
- Layouts adapt with window size classes; tablets get a real tablet layout, not a stretched phone one.

## Accessibility and Arabic

- Touch targets at least 48 dp; text contrast at least WCAG AA; works at 200% font scale.
- Every interactive element has a TalkBack label in Arabic.
- RTL is the only direction: use `start`/`end`, never `left`/`right`, and test mirrored icons.
- Arabic text: real diacritics, Arabic-Indic digits where the design uses them, ﷺ rendered correctly. Map labels use the system-drawn path from ADR 0002.

## Testing

- **Unit tests** for all logic: time states, date precision, content parsing, update and integrity checks, ViewModels.
- **Screenshot tests** for design-system components and key screens (phone and tablet, RTL).
- **Instrumented tests** for storage, the update flow and offline start.
- **Content validation** (`tools/validate_content.py`) runs in pre-commit and CI.
- Use fakes, not mocking libraries: tests check behaviour, not which calls were made.
- A bug fix comes with a test that fails without it.
- Tests are named for behaviour: `yearBeforeHijra_showsYathrib`.

## Performance

Targets are in [ADR 0002](adr/0002-proposed-stack.md#performance-targets); measure in **release** builds on the Oppo A55 (low-end), Galaxy A54 and MatePad 11.5. A change that may affect startup, map smoothness or memory is measured before and after. Use Baseline Profiles and R8 in release.

## Security and privacy

- No accounts, no personal data. Analytics only within the scope the PRD approves.
- Network only over HTTPS, only for content updates; every package is verified (hash, version compatibility) before use.
- No secrets in the repository. Signing keys stay outside git and are injected in CI.

## Documentation

- **Where things go:** see [docs/README.md](README.md). Each module has a short `README.md`: purpose, public entry points, how to test it.
- **KDoc** on every public type and on functions whose behaviour isn't obvious from their name.
- **ADRs** for decisions that are costly to reverse: context, options compared (with how others do it), decision, consequences. Status *Proposed* until the owner approves.
- **Style:** plain English, short sentences, present tense, no filler. Diagrams in Mermaid so they stay editable.
- **Docs move with code:** a change that makes a doc wrong fixes that doc in the same commit. After every task, run the documentation review in [AGENTS.md](../AGENTS.md#definition-of-done).

## Choosing between options

For any significant choice (a library, a pattern, a data format, a UX convention, a process):

1. State the need and the constraints (offline, Arabic RTL, low-end Android, content integrity).
2. List at least two real options.
3. Check what the official guidance recommends, what leading apps do (for example Google Maps for map interaction, Quran.com and comparable Islamic apps for reading sourced texts), and what mature open-source Android apps do.
4. Compare on fit, quality, maintenance, size and cost to reverse.
5. Recommend one, with the reason, and record it in an ADR or the change's notes. Owner approval for anything product-facing.

## Git and reviews

- Conventional Commits; small, focused commits; one concern per pull request.
- CI must be green: lint, format, static analysis, unit tests, content validation and a release build.
- A pull request describes what and why, how it was checked, and includes phone and tablet screenshots for UI.
