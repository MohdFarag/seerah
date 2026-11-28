# Consolidated review of v1 user data

**Target** — [v1 user data and explore session](../v1-userdata-spec.md).

The review used fresh-context readers for all five stages. The comprehension reviewer read only the draft and the comprehension instructions. The other reviewers checked the draft against [ADR 0003](../../adr/0003-v1-architecture-and-modules.md), the [requirements](../../requirements.md), the [open decisions](../../open-decisions.md), and the [engineering standards](../../engineering.md).

## Comprehension

### What this proposes

Add a local Android session store that saves the selected timeline point at its original precision, the focused place or event, and a separate follow-along bookmark. Future app features will resolve saved references against the active content package after reopening offline and offer to resume a bookmark when the engine can resolve it. Playback begins only when the user accepts the offer. The store will use a versioned JSON record outside the content directory, support atomic updates to related fields, and report typed failures while preserving unreadable files and files from newer versions. This milestone builds and tests the store, including persistence across recreation, with the map, index, reader, and offline relaunch UI acceptance to follow in feature integration.

### How the existing system works, per this document

The app keeps its content in a Room database that a package update can replace. The shared model already represents timeline points and calendar axes, and the Android build already uses Kotlin serialization. The follow-along engine already interprets a bookmark containing story, chapter, scene, and step IDs plus a fallback index. It looks for the exact step, can fall back within the same story, and returns a suspended session when it finds a step to resume.

The two paragraphs above paraphrase the draft as the reviewer received it. The revised text describes follow-along as manual movement through steps.

## Findings and resolution

| Priority | Review stage | Finding | Change in the revised draft |
| --- | --- | --- | --- |
| Blocker | Implementation readiness | The initial plan left the file boundary and app wiring unclear. | Added exact paths and buildable checkpoints. Hilt module generation and a device storage test are in scope. Connecting the production app is in the shell milestone. |
| Blocker | Codebase details | AndroidX `FileStorage` can wrap a generic `IOException`, which would hide the typed exception for a newer format. | Use a separate runtime marker for unsupported versions. Require a real `FileStorage` read and update test that checks the result, unchanged bytes, and no transform invocation. |
| Major | Initial zoom out, catalyst, comprehension, design critic, intermediate zoom out | Time and focus could each appear valid even when an event moved to a different year. Empty years and loss of date precision also needed a rule. | Restore a supported time using existing timeline rules, falling back to a selectable year when precision is lost. Check focus at that time from the same content snapshot. Keep saved values on disk. |
| Major | Feature style, comprehension | Resume sounded optional and the terminology suggested automated playback. | Say that the app offers resume for a resolved bookmark and that users advance each step themselves. |
| Major | Implementation readiness, codebase details, implementation style | Exploration and story streams can change independently during content activation. | Require the later shell to restore from one installed generation and discard a stale lookup. Query only the saved story and distinguish loading, failure, and absence. |
| Major | Implementation readiness, codebase details, implementation style | Serializer decisions were implicit for version tokens, optional fields, and invalid bytes. | Define a positive integer header, version-first decoding, UTF-8, null handling, and required fields. Preserve malformed and newer data. |
| Major | Comprehension, implementation readiness, codebase details, implementation style | Retry, failed writes, cancellation, and both bookmark fallback paths needed explicit checks. | Require read failure followed by successful recollection, prior commit survival after a failed write, exception propagation, and exact-step, scene, story, and missing-story restore tests. |
| Nit | Zoom out, structure, style | The draft needed a clearer problem statement and a precise explanation of current backup eligibility. | State the interrupted exploration problem, link the current rules, and retain the open backup decision. |

The AndroidX wrapping behavior was checked against the [1.3 release history](https://developer.android.com/jetpack/androidx/releases/datastore) and its linked [FileStorage source](https://android.googlesource.com/platform/frameworks/support.git/+/1059f509df13f43981792e3de70830b7012236ff/datastore/datastore-core/src/jvmAndAndroidMain/kotlin/androidx/datastore/core/FileStorage.kt#225). Runtime behavior remains part of the proposed tests.

## Readiness recheck

A fresh reviewer confirmed the revised file boundary, the unsupported-version error path, and the real-file assertions. The recheck identified two remaining details. The storage build and its wire contract now form a single buildable phase with defined test conventions. The interruption test now stops the store before commit and reopens the prior session. It allows for a write that reaches disk before the caller receives confirmation. The revised plan also distinguishes malformed UTF-8 from a disk read failure.
