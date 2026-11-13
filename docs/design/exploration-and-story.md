# Exploration and story: the content model

**Status:** Accepted by the owner on 2026-11-13 as [ADR 0005](../adr/0005-content-model-exploration-and-story.md). This document is the detailed specification behind it. Nothing here is a fact about the Seerah: names in examples are placeholders or come from the PRD's source list, and every real fact still needs a source.

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
| **Terrain** | The base map: land, sea, relief, valleys, lava fields. Built by the map pipeline (ADR 0008), not written per year | yes | no | — |
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
| `shown` | `always` or `in-context`; see *Visibility* below |
| `prominence` | 1–3: how early its marker appears when zooming in, like city and street names on Google Maps |
| `situations` | The situations inside the event, each with its texts |
| `related` | Links to other events, typed: `cause-of`, `result-of`, `continues` |
| `sources` | Per claim: one source for the date, another for the place, if they differ |

### Visibility

The first draft had three levels: on the map, only inside the parent event, and story only. On review, "story only" conflicts with the PRD: introductions and related situations must stay available in free exploration, not only in story mode (PRD §6). It would also mean a sourced fact that free exploration can never reach. So the levels are revised, and the same two apply to events and places:

| Level | In free exploration | In story mode | Examples of the kind |
| --- | --- | --- | --- |
| `always` | On the map whenever its time applies, from the zoom its `prominence` allows | As the story directs | a major event; a city; a mountain on the route |
| `in-context` | On the map only while its parent event (or the event it belongs to) is open; always listed and readable inside it | Shown whenever a step points to it | the stations of a journey; the positions of two sides in a battle; a well that matters to one event |

Two things are **not** levels, because the facts decide them:

- **No marker:** an event or place whose location is unknown never gets a marker. It stays in the index and inside its parent (PRD rule on unknown places).
- **Off the map:** an event outside the map's area gets its own presentation, as the PRD says for al-Isra, without widening the map.

What the owner wanted from "story only" (keeping small details off the map in free exploration, and bringing them in during the story) is what `in-context` does. What remains story-only is direction itself: camera moves, title cards and reviewed linking sentences, which are not facts.

**Event kinds (owner, 2026-11-13).** One primary kind per event, chosen by **what the event was about**, not by how it is drawn: a journey made to call people to Islam is a call, and its route shows the movement. The kind sets the icon and the index filter; free tags can add more.

| Kind | Covers |
| --- | --- |
| Call to Islam (دعوة) | presenting the message, inviting tribes, sending a teacher |
| Opposition (أذى ومواجهة) | pressure and plots against the Muslims |
| Pledge and covenant (بيعة وعهد) | pledges, written covenants between groups |
| Migration and journey (هجرة ورحلة) | migrating, and the journeys of the Hijra |
| Founding (تأسيس) | building a mosque, founding the community (the brotherhood) |
| Legislation (تشريع) | a ruling or practice that began: prayer call, fasting, qiblah, permission to fight |
| Expeditions (غزوات وسرايا) | expeditions led by the Prophet ﷺ or sent by him, including battles; an attribute says which |

Tested against the 26 draft v1 events: every one fits a kind.

| Kind | Draft v1 events (by number in the index) |
| --- | --- |
| Call to Islam | 1 Taif, 2 the tribes at the seasons, 3 the Khazraj at Hajj, 5 Mus'ab in Yathrib |
| Opposition | 8 the Quraysh plot and the permission to migrate |
| Pledge and covenant | 4 first Aqabah, 6 second Aqabah, 17 the Madinah charter |
| Migration and journey | 7 the Companions migrate, 9 to 14 the Hijra journey |
| Founding | 15 the mosque, 16 the brotherhood |
| Legislation | 18 the call to prayer, 19 permission to fight, 21 the qiblah, 22 fasting |
| Expeditions | 20 early expeditions, 23 to 26 Badr |

**Verses are links, not a kind.** Revelation comes with events of every kind, so making it a kind would force a false choice (is Badr a battle or a revelation?). Instead an event or situation links to its verses, and the reader can list "verses revealed about this event". The PRD's own source list already pairs events with verses this way: the cave with 9:40, the qiblah with 2:144, Badr with 8:42 and 3:123.

### Time

- **Years are counted from the Hijra:** 1 AH onward, and before it 1 BH, 2 BH… with no year zero. Display can also give the year of prophethood when the source dates it that way.
- **Precision is part of the date:** year, month or day. An unknown part is never filled in.
- **Months where sourced (owner, 2026-11-13):** the v1 period is only about five Hijri years, and 1 AH alone holds the Hijra, Quba, the mosque and the brotherhood, so years alone are too coarse. Time moves by year, and by month where a source gives the month. An event dated only to its year stays at year level; it is never placed in a guessed month. Events with the same date keep their `order`.

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
2. **Change one thing at a time:** move in time or in place, not both in the same step, so the viewer keeps their bearings. It is a guideline, not a hard rule (see below).
3. **Place before text:** show where something happened before showing what was said there.
4. **Motion for journeys:** a journey is drawn, not jumped across.
5. **Short panels:** one idea per step; an excerpt with a link to the full text rather than a long block.
6. **Never hide uncertainty:** certainty notes come from the facts and are always shown.

### When a step may change more than one thing

The build **warns** (it does not fail) when a step changes both time and place. Three cases justify it:

1. **An opening through a title card.** A chapter or scene opens on a card that names the place and the date, then reveals the map: like a film caption, the card itself re-orients the viewer. Example of the kind: opening the Madinah chapter after the Makkah one.
2. **Things happening at the same time.** Several movements shown together because their being simultaneous is the point. Example of the kind: the days before Badr, when different groups were on the move toward the same place.
3. **Following a route.** The place changes continuously by design; that is one move, not several.

Anything else that changes time and place together is split into two steps, or the warning is explicitly accepted in review.

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
- **The build checks the story against the facts.** Every reference must exist; nothing may appear before it exists (a mosque before it was built); a route can be drawn only if it is documented; an `in-context` item appears on the map only when its parent is open or a step points to it; a step that changes both time and place without a title card gets a warning; every text has a source.
- **Preview while writing.** A debug build of the app can load content from the writer's computer and reload it, so a scene can be watched while it is written. Directing without seeing the result is guesswork.

## Comparable approaches

- **Google Earth tours (KML):** a tour is a list of primitives (fly to, wait, update the map, show a balloon). The closest model to the toolkit above.
- **Mapbox storytelling template:** chapters, each declaring a camera position and which layers are visible; the map flies between them as the reader scrolls.
- **StoryMapJS and ArcGIS StoryMaps:** slides tied to map locations; used by newsrooms and museums.
- **Game cutscenes:** level data (the world) kept apart from scripts (what the camera and characters do), written with a limited set of commands.

## Decisions

| Point | Status |
| --- | --- |
| Time | Decided: by year, and by month where sourced |
| Map extent | Decided: the whole Arabian Peninsula, most detailed where the events happen; no world view |
| Where content is written | Decided: files in the repository, checked by the build, with a preview build |
| Visibility | Decided: `always` and `in-context`; no fact is story-only |
| Event kinds | Decided: seven kinds, tested against the 26 events; verses as links |
| One change per step | Decided: a guideline the build warns about, with three accepted cases |
