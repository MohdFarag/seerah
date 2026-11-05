# v1 requirements digest

English summary of [PRD v0.2](prd/PRD-v0.2.ar.md). If they differ, the PRD wins.

## Product

- **What:** an Arabic app for exploring the Seerah through place, time and the original texts. The map is the main way to browse.
- **Goals:** see where places are, understand why events happened and how they connect, and start learning the Seerah, with enjoyment rather than boredom. Visuals never cut text at the cost of understanding.
- **Primary user:** a young Muslim using the app alone, who knows the Seerah in general but has no clear picture of the geography. Children are not direct users.
- **Language:** Arabic only (RTL).
- **Scope of content:** the Seerah only. No commitment to wider Islamic history.

## v1 scope

| Area | Requirement |
| --- | --- |
| Platform | Android phones **and** tablets |
| Distribution | Free. A small closed beta first, then public |
| Time span | From an agreed point before the birth until the Prophet's ﷺ death, with selected key events |
| Map | Fully **2D**, historical style. The whole world at overview zoom, with detail only inside the reviewed Seerah coverage |
| Changes over time | Place names, borders of states, kingdoms and major ruled regions, and key landmarks (e.g. the Prophet's Mosque appears when it was built) |
| Access | Map, phase and event index, guided follow-along ("story mode"). **No search** |
| Content | Quran, hadith, athar, and narrations as carried in Seerah books, each with its source and a simple explanation |
| Narrations | **One** chosen narration per situation. No alternatives and no comparison |
| Offline | Texts, map and situation sequence work offline inside the downloaded coverage |
| Storage | Progress and settings stay on the device. No accounts and no sync |
| Updates | Content updates ship separately from app releases. The app shows availability and size, and the user starts the download |
| Feedback | Free-text feedback or problem report from inside the app, not linked to specific content |
| Analytics | Sessions and active time; free exploration vs story mode; events and situations opened; progress and drop-off points; explanation or full narration opened. No screen recording, no free-text content, no automatic crash logging, no account |

## Content model

**Phase → Event → Situations (only when needed).** A simple event can be a single situation, and there is no fixed count. Linked but separate events stay separate stations (e.g. al-Ahzab, then Banu Qurayza).

**Minimum content per event:**

1. A short, sourced lead-in linking it to what came before.
2. Place and time as far as they are known, with approximation or disagreement stated.
3. Key situations with their original texts.
4. A short outcome linking it to what follows.

**Text rules:**

- The original text comes first. The explanation shows on request.
- A narration from a Seerah book is shown as that book gives it, with the source cited. The book carries the report; it does not create the event.
- An excerpt from a long narration must keep its full meaning. Label it as an excerpt and give access to the full narration and its source.
- Explanation is simple (word meanings or a hadith commentary). No mandatory sections, e.g. no "lessons" section.
- Event-level texts stay available at event level. One text can link to several situations.
- Editorial linking or summary sentences are allowed only when reviewed, and must be visually distinct from narration wording.

**Sources:** the Quran, hadith collections, early Seerah books (e.g. Ibn Hisham), Seerah atlases and Shama'il books.

## Map and time

- **Time unit:** the year, plus a direct jump to a date. Choosing months directly comes later.
- **Markers:** only the selected year's events show; markers don't pile up. Older events stay reachable from the index, and opening one moves the map to its time.
- **Year view:** shows the state at **end of year**, and says so. All of that year's event markers stay visible.
- **Opening an event:** sets map time to the event's documented precision. Never invent a month or day.
- **Disputed dates:** each event gets **one** display date reviewed by the owner, with approximation flagged. Choosing it does not settle the scholarly dispute.
- **Years with no content:** still selectable, with "no events added for this year yet" and prev/next event navigation.
- **Uncertain place:** if the place is known only generally (city or region), link to that general place and say the exact spot is unknown. If the place is fully unknown, the event stays in the index and sequence with **no** guessed marker.
- **Journeys:** show documented stations, and a route line only if the route itself is documented, with its accuracy stated. Days and distances only when well founded, and an estimate is labeled as an estimate. A step number is not a day of the journey.
- **Situations of a large event:** shown inside the event, not on the overview map, in v1.
- **Not in v1:** tribal borders.

## Follow-along behaviour (kept general on purpose)

- Next and back are manual. The map can be panned and zoomed freely during any step.
- Changing the time **suspends** the follow-along and keeps its place, with a way back.
- Opening another event is exploration and keeps the follow-along point. Moving that point is an explicit user choice.
- On relaunch, restore the last place and time, and offer to resume. Never auto-start.
- Events before the birth can be skipped, starting from the birth.
- Other story-mode details will be decided during implementation.

## Out of v1

Audio and recitation · search · accounts and sync · biographies of people · alternative narrations · Google Maps links or comparison with today's map · 3D · iOS and other languages · choosing months directly · situations on the overview map · religious and social context in the intro · Islamic history beyond the Seerah · quizzes, AI features for users, user-generated content, payments.

## Acceptance checks (derived from the PRD)

- [ ] Opening an event from the map or the index sets its time and place to documented precision and shows the text, source and explanation. No guessed position.
- [ ] Large events show their situations inside the event, with an excerpt and access to the full narration.
- [ ] No alternative narrations, audio, search or accounts.
- [ ] Changing the year changes the markers and the names, borders and landmarks for that time.
- [ ] No exact coordinate for an unknown location, and no invented route between known stations.
- [ ] Reading, map and follow-along work offline after download, including after a cold restart in airplane mode.
- [ ] Place, time and progress are restored. Visiting another event doesn't move the follow-along point unless the user chooses.
- [ ] A content update arrives by user choice, without a new app release.
- [ ] Free-text feedback is available, and analytics record the approved scope.
