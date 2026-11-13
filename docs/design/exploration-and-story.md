# Exploration and story: the content model

**Status:** Draft for discussion with the owner (2026-11-13). Once agreed, it becomes ADR 0005 (content authoring and package format). Nothing here is a fact about the Seerah: names in examples are placeholders, and every real fact still needs a source.

## The owner's idea

Content is written in a form aimed at the app, in two layers:

- **Exploration data:** what is on the map and in time, available at any moment. It covers the terrain (the shape of the map), places (the Kaaba, the Prophet's ﷺ house, Quba…), events (Hijra stations, Badr, the Aqabah pledges…), each with a time and a place, and the texts and information attached to places and events.
- **Story data:** what links exploration data into a narrative. It is written like television direction with a limited set of tools: the moves the app can make (zoom to an area, move from one year to another, show a text). First we list those moves, then we use them to tell the story as well as possible.

The order of work: build the whole app foundation (map, timeline…), then add the data: the world first, then year by year (new places and new events, some nested, some story-only, with categories), then the texts, then the story script that points at what already exists.

This document agrees with that model and works it out from three angles: the information model, the director's toolkit, and how the app runs it.

## Two layers, one rule

| | Exploration layer | Story layer |
| --- | --- | --- |
| Holds | Facts: what existed or happened, where, when, how certain, and from which source | Direction: which facts to show, in which order, and how the map moves between them |
| Available | Always, in free exploration | In story mode, as a sequence the user steps through |
| Written | Like an atlas and a chronicle | Like a screenplay |

**The rule: the story never adds a fact.** Every place, date, coordinate, route and text a story step shows must already exist in the exploration layer, with its source. The story layer only references them and adds direction, plus short linking sentences that are reviewed and always marked as editorial. This keeps all facts checkable in one place, and the build can verify it automatically.

## The exploration layer

### Entity types

The owner's list, with two additions (routes and areas):

| Type | What it is | Has a place | Has a time | Examples (placeholders) |
| --- | --- | --- | --- | --- |
| **Terrain** | The base map: land, sea, relief, valleys, lava fields. Built by the map pipeline (ADR 0007), not written per year | yes | no | — |
| **Place** | Something with a location that the story refers to: a city, a mountain, a well, a house, a mosque, a cave | point or small area, with accuracy | when it exists; names and states that change over time | Makkah, Mount Thawr, Quba |
| **Event** | Something that happened. Can contain smaller events and situations | one or more places, an area or a route | date with precision | a pledge, a journey, a battle |
| **Route** *(added)* | A documented path: a journey's stations in order, or a march | the stations (places) and the line between them, if documented | the event it belongs to | the Hijra route |
| **Area** *(added)* | A region with soft edges: for regional names and, later, a soft tint | approximate outline or a label point | when the name applies | the Hijaz, Tihamah |
| **Text** | An original text (Quran, hadith, athar, Seerah narration) or a reviewed editorial sentence | through what it is attached to | — | a narration attached to a situation |
| **Source** | A book and edition a text or fact comes from | — | — | a hadith collection, a Seerah book |

Why routes are separate: a journey's line is a claim of its own. It may be documented while one station's location is only general, and the same route can be drawn step by step in the story. Why areas: regions like the Hijaz carry names on the map and context in the story, but they are not points.

### Places change over time

A place has one identity and a history. Yathrib and al-Madinah are one place with two names, not two places. So a place has:

- a stable ID and a location with its accuracy (`exact`, `general`, `unknown`) and source;
- **time-scoped states**: its name, whether it exists yet (a mosque appears when it was built), and how it is drawn;
- a **kind** (settlement, mountain, valley, well, house, mosque, cave…), which decides its icon and at which zoom it appears.

### Events

| Field | Meaning |
| --- | --- |
| `id`, `title` | Stable ID; Arabic title |
| `parent` | The larger event it belongs to, if any. Nesting has no fixed depth |
| `kind` | A primary category (see below), for icons and filters |
| `date` | The single display date with its precision (year, month or day) and an approximation flag; a dispute note if there is one |
| `order` | Position among events with the same date, because many events share a year |
| `where` | One place, several places (for example the positions of two sides), an area, or a route |
| `visibility` | `map` (a marker in free exploration), `parent` (shown only inside its parent event, as the requirements ask for situations), or `story` (only in story mode) |
| `prominence` | 1–3: how early its marker appears when zooming in, like city and street names on Google Maps |
| `situations` | The situations inside the event, each with its texts |
| `related` | Links to other events, typed: `cause-of`, `result-of`, `continues` |
| `sources` | Per claim: one source for the date, another for the place, if they differ |

**Event kinds (draft, needs the owner's study).** Start with one primary kind per event, chosen because it changes what the user sees (an icon, a filter), plus free tags:

| Kind | Examples of the kind |
| --- | --- |
| Call to Islam (دعوة) | presenting the message to tribes |
| Pledge or covenant (بيعة وعهد) | the Aqabah pledges, a written covenant |
| Journey or migration (رحلة وهجرة) | the Hijra, the Companions' migration |
| Founding or building (تأسيس وبناء) | building a mosque |
| Legislation (تشريع) | the call to prayer, fasting, the change of qiblah |
| Expedition (غزوة وسرية) | an expedition the Prophet ﷺ led, or one he sent |
| Battle (معركة) | if kept apart from expeditions |

Revelation of a verse is better treated as a **link** (an event has verses attached) than as a kind, because verses come with many kinds of events. This is a recommendation for the owner to decide.

### Time

- **Years are counted from the Hijra:** 1 AH onward, and before it 1 BH, 2 BH… with no year zero. Display can also give the year of prophethood when the source dates it that way.
- **Precision is part of the date:** year, month or day. An unknown part is never filled in.
- **The v1 period is short:** from the journey to Taif to Badr is about five Hijri years. Most v1 events fall in two or three of them (1 AH alone holds the Hijra, Quba, the mosque and the brotherhood). A time bar that moves only by year will feel coarse here. Options: keep the year as the unit and order events inside it (`order`); add months where they are sourced; or move through the five phases. This is the owner's call (see the questions below).

### How the exploration layer is written

The owner's order works well, with one adjustment to how it is stored:

1. **World setup:** the terrain (map pipeline), the areas, and the places that exist for the whole period (the Kaaba, the mountains, the main towns).
2. **Year by year:** for each year, the places that appear or change (a new name, a new building) and the events of that year, nested where needed, with visibility and kind.
3. **Texts:** for each event and situation, its texts and sources.

Writing year by year matches how the Seerah is read and reviewed. But a place lives across many years, and its facts (location, sources) should be written once. So: **write in year files, and let the build tool turn them into one history per place and event.** Each entity is defined once, in the year it first appears; later years only record changes ("from this year, Yathrib is named al-Madinah"). The build checks that every reference resolves and every change applies to something that exists.

## The story layer

### The director's toolkit

A closed list of moves, each built once in the app and tested. A story is written only with these moves. The list below is the proposal for v1; the owner can add, remove or rename.

| Group | Move | What the user sees | Main settings |
| --- | --- | --- | --- |
| Camera | **Frame** | The map shows a place, an event or several things together | what to frame; the shot size: `wide` (the region), `medium` (a town and its surroundings), `close` (a site) |
| | **Follow** | The camera travels along a route while it is drawn | route; from which station to which |
| | Transition | How the camera gets there: `cut` (instant), `fly` (out, across, in), `pan` (a short slide) | chosen automatically by distance unless set |
| Time | **Set time** | The map changes to another year: names, buildings and routes fade to their state then | year (and month, if sourced) |
| | **Time-lapse** | The years pass one by one and the map changes with them | from year, to year |
| Emphasis | **Highlight** | Chosen places or events stand out; their names show even when zoomed out | what to highlight |
| | **Focus** | Everything else is dimmed | on or off |
| | **Reveal** | A story-only event or place appears | what to reveal |
| Drawing | **Draw route** | A documented route is drawn, step by step, with station numbers | route; station range |
| | **Show movement** | An arrow shows a group moving between places (for example a march) | from, to, route if documented |
| | **Show area** | A soft tint over an area, never a crisp border | area |
| Panel | **Title card** | Chapter or scene title, with the date and place lines | title; date and place come from the facts |
| | **Text** | An original text with its source; an excerpt opens the full text | text IDs |
| | **Linking sentence** | A short reviewed editorial sentence, visibly marked as editorial | the sentence |
| | Certainty note | "The exact spot is unknown", "approximate date": added **automatically** from the facts, never written by hand | — |
| Flow | **Step** | The user taps next or back; nothing moves on its own | — |
| | **Explore here** | An invitation to explore freely at this point; the story waits | — |

Not in v1: narration audio, illustrations and place diagrams (planned to be designed in Penpot first), timed auto-play, 3D tilt.

### Structure of a story

```text
Story
└── Chapter      usually one of the five phases
    └── Scene    usually one event; opening the event from the index can start here
        └── Step one shot: what the map shows and what the panel says
```

Situations stay in the exploration layer (they are part of an event and readable any time). A step can present a situation.

### Each step is a complete shot

A step states the whole picture: what is framed, which year, what is highlighted, what the panel shows. It does not say "and now also do X". The app works out the transition from the previous step.

Why: going back, resuming after a restart, and starting a scene from the index all need to rebuild a step without replaying the ones before it. This is how map storytelling tools work (each chapter of the Mapbox storytelling template declares its full camera and layer state).

### Directions are in words, not numbers

A step says "close shot on Quba", not "zoom 14.2 at these coordinates". The app turns the shot size into a zoom that fits the screen (phone or tablet, portrait or landscape) and the current map. If the map data changes, the story still works. Coordinates live only in the exploration layer.

### Directing guidelines

Rules of thumb for writing scenes, from film grammar and map storytelling practice:

1. **Open wide:** start a chapter with a wide shot of the region and its year, then go closer.
2. **Change one thing at a time:** move in time or in place, not both in the same step, so the viewer keeps their bearings.
3. **Place before text:** show where something happened before showing what was said there.
4. **Motion for journeys:** a journey is drawn, not jumped across.
5. **Short panels:** one idea per step; an excerpt with a link to the full text rather than a long block.
6. **Never hide uncertainty:** certainty notes come from the facts and are always shown.

### Example (structure only)

The IDs, years and text are placeholders, not facts.

```yaml
scene: leaving-the-cave            # anchored to an event in the exploration layer
steps:
  - frame: { what: [place/cave-of-thawr], shot: close }
    time: { year: 1 }
    panel:
      title: true                  # title, date and place lines come from the event
  - follow: { route: route/hijra, from: station/1, to: station/3 }
    highlight: [place/cave-of-thawr]
    panel:
      text: [text/example-narration-excerpt]
  - frame: { what: [place/quba], shot: medium }
    focus: true
    panel:
      linking: "…a reviewed editorial sentence…"
```

## How the app runs it

- **One map picture for both modes.** The map draws a *map scene*: the year, what is framed, what is highlighted or dimmed, and which routes, movements and areas are drawn. Free exploration builds that scene from what the user touches; story mode builds it from the current step. The map has one engine, and a story is exploration with direction applied.
- **The time rules live in one place** (`core:domain`): given the facts and a year, which places exist, with which names, and which events show. Exploration and story both use them.
- **The build checks the story against the facts.** Every reference must exist; nothing may appear before it exists (a mosque before it was built); a route can be drawn only if it is documented; a story-only event can only appear through a story step; every text has a source.
- **Preview while writing.** A debug build of the app can load content from the writer's computer and reload it, so a scene can be watched while it is written. Directing without seeing the result is guesswork.

## Comparable approaches

- **Google Earth tours (KML):** a tour is a list of primitives (fly to, wait, update the map, show a balloon). The closest model to the toolkit above.
- **Mapbox storytelling template:** chapters, each declaring a camera position and which layers are visible; the map flies between them as the reader scrolls.
- **StoryMapJS and ArcGIS StoryMaps:** slides tied to map locations; used by newsrooms and museums.
- **Game cutscenes:** level data (the world) kept apart from scripts (what the camera and characters do), written with a limited set of commands.

## Questions for the owner

1. **Time in v1:** the period covers about five years. Keep moving by year (with events ordered inside), add months where sourced, or move through phases?
2. **"The world map":** the 11 November decision limits v1 to the Hijaz, with wider bounds inside Arabia where needed and no world view. Is that what "رسم الخريطة بتاعت العالم" means here?
3. **Visibility:** are three levels enough (on the map; only inside the parent event; story only)?
4. **Event kinds:** start from the draft list above? And treat revelation of verses as a link rather than a kind?
5. **One change per step:** keep it as a firm rule, or as a guideline the story may break?
6. **Where to write:** text files in the repository (versioned, reviewable, checked by the build), or Notion databases that a script exports? Recommended: files in the repository, with the preview build for watching results.
