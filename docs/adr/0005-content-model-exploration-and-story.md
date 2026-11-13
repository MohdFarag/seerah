# 0005. Content model: exploration and story layers

**Status:** Accepted by the owner on 2026-11-13. The detailed specification (entity fields, the director's toolkit, directing guidelines, examples) is [design/exploration-and-story.md](../design/exploration-and-story.md).

## Context

The owner wants content written in a form aimed at the app: facts that live on the map and in time, and a story that is directed like television with the limited set of moves the app can make. The model must keep every fact sourced and checkable, let free exploration and story mode share one map, and stay easy to write and review for 26 events now and the whole Seerah later.

## Decision

1. **Two layers.** The *exploration layer* holds facts: places, events, routes, areas, texts and sources, each with its time, place and accuracy. The *story layer* holds direction: chapters, scenes and steps that reference exploration items.
2. **The story never adds a fact.** Places, dates, coordinates, routes and texts shown in a story must exist in the exploration layer with their sources. The story adds only direction and short reviewed linking sentences marked as editorial. The build checks this.
3. **Places have one identity and a history** (names, existence, appearance change over time). Yathrib and al-Madinah are one place.
4. **Time:** Hijri years counted from the Hijra, no year zero; months where a source gives them; precision is part of every date; events with the same date keep an explicit order.
5. **Visibility in free exploration:** `always` (on the map in its time, from the zoom its prominence allows) or `in-context` (on the map only while its parent event is open, always readable inside it). No fact is story-only. Unknown locations get no marker; events outside the map get their own presentation.
6. **Event kinds** by what the event was about: call to Islam, opposition, pledge and covenant, migration and journey, founding, legislation, expeditions. Verses are links to events, not a kind.
7. **A closed director's toolkit:** camera (frame wide, medium or close; follow a route; transitions), time (set year or sourced month; time-lapse), emphasis (highlight, focus, reveal in context), drawing (route step by step, movement arrows, soft area tint), panel (title card, original text, linking sentence, automatic certainty notes), flow (next and back, explore here).
8. **Each step is a complete shot**, described in words ("close shot on Quba"), not zoom numbers or coordinates. The app computes transitions and fits shots to the screen.
9. **One change per step is a guideline:** the build warns when a step changes both time and place, accepted for title-card openings, simultaneous events and route following.
10. **Authoring:** files in the repository, written year by year (world setup, then each year's new places, events and changes, then texts, then the story), compiled by a build tool into one history per entity, and checked by the build. A preview build shows a scene while it is written.

## Options compared

| Question | Chosen | Alternatives | Why |
| --- | --- | --- | --- |
| Where facts and direction live | Two layers | Story fields on each event | Facts stay in one checkable place; a fact can appear in several stories without being copied |
| How a story is expressed | A closed toolkit of moves | Free scripting; timed animation | Each move is built and tested once; content cannot ask for what the app cannot do; stories stay reviewable. The model of Google Earth tours (KML) and the Mapbox storytelling template |
| Step semantics | Full snapshot per step | Changes relative to the previous step | Back, resume and starting from the index work without replaying earlier steps |
| Camera directions | Shot sizes in words | Zoom levels and coordinates | Works on every screen size and survives map data changes |
| Story-only facts | None (`in-context` instead) | A "story only" visibility level | The PRD keeps introductions and related situations available in free exploration (§6) |
| Authoring order and storage | Year files compiled per entity | Hand-written per-entity files; Notion databases | Year by year matches how the Seerah is read and reviewed; git gives history, review and checks |

## Consequences

- The app has one map engine: story mode is exploration with direction applied.
- The time rules and the follow-along rules live in `core:domain` and serve both modes.
- A content build tool and validator are needed before content is written: references, time validity, documented routes, sources, and the step warnings.
- Adding a move to the toolkit is an app change with its own tests, never a content change.
- Follow-up decisions: ADR 0006 (content package format, compatibility between app and content versions, and package signing), ADR 0007 (where content updates are hosted), ADR 0008 (the v1 map data pipeline for the Arabian Peninsula).

## References

- [Google Earth touring (KML)](https://developers.google.com/kml/documentation/touring).
- [Mapbox storytelling template](https://github.com/mapbox/storytelling).
- PRD v0.2 §2, §2b, §5 and §6 ([snapshot](../prd/PRD-v0.2.ar.md)).
