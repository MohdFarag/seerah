# Roadmap

No dates, budget or estimates have been approved. This is only the order of work.

## 0. Repo ready ✅

Structure, PRD snapshot, requirements digest, content schema, approved event index.

## 1. Technical spike (proves the stack before it's adopted) ✅

From the PRD section "ما يلزم إثباته قبل الاعتماد" (what must be proven before adoption):

- A simplified world base map plus one detailed local area.
- Arabic place names: joined letters, diacritics, mixed with digits, and how labels collide, all **offline**.
- Three time states that show a **name change**, a **border change** and a **landmark appearing**.
- Opening an event restores its time and place context.
- Reading, map and progress after a cold restart in **airplane mode** (no warm cache).
- A content update without a new app version, including an **interrupted download**.
- Measure package size, map smoothness and memory on a target Android phone **and** tablet. Only then set numeric targets.

Added by the owner (2026-11-10), detailed in the [stack study](research/android-stack-study.ar.md) §9–10:

- Map data sourcing trial: build the Hijra route from its station names and their sourced coordinates (atlas pages are read for names and order only, not georeferenced), compare an AI-drafted route with a hand-made one, build a base from open elevation data, and try one battle as both a map layer and a standalone illustration. Atlas pages are a reference only; no coordinates are taken from a model's guess.

Spike inputs set by the owner (2026-11-10):

- **Devices:** Huawei MatePad 11.5 (tablet), Galaxy A54 5G on Android 16 / One UI 8.5, Oppo A55 on Android 13 (the low-end target).
- **Sample area:** Makkah, Madinah and the Hijra route in detail, over a simplified world base.
- **Time states:** Yathrib → Madinah (name change) and the Prophet's Mosque (landmark appearing). The border change uses a placeholder shape marked as sample data, not a historical claim, until a reviewed example is chosen.
- **Arabic test labels:** taken from the map data and reference maps the owner supplies.
- **Atlas pages:** the owner supplies Hijra-route pages as a working reference for the data trial.

Exit: the owner approves the stack. Done 2026-11-13: [ADR 0002](adr/0002-proposed-stack.md) accepted; results in [spike/summary.md](spike/summary.md).

## 2. v1: from Taif to Badr

Scope set by the owner (2026-11-11): one continuous period, from the journey to Taif (with a short lead-in on the Year of Sorrow) to Badr and its outcome. 26 draft events in 5 phases ([index](../content/index/phases.json)). The map covers the whole Arabian Peninsula, most detailed where the events happen, with no world view (owner, 2026-11-13). Time moves by year, and by month where a source gives it.

The app and its content are built together; there is no separate content-sample stage (owner, 2026-11-13). Each event's situations, places, narrations and sources are worked out when that event is prepared, and the owner reviews each event before it ships.

App: map and time, index, event and situation reader, free exploration and follow-along, offline package and updates, feedback, analytics.

The app is written from scratch to the [engineering standards](engineering.md); the spike is a reference only. Before scaffolding, for the owner's approval: [ADR 0003](adr/0003-v1-architecture-and-modules.md) (architecture and modules) and [ADR 0004](adr/0004-build-and-quality-tooling.md) (build and quality tooling), both Proposed and revised after a second review.

Then, in order:

1. Scaffold `android/` with the build setup and checks from ADR 0004, and move the spike there. ✅ 2026-11-13 ([android/README.md](../android/README.md)).
2. ADR 0005: the content model (exploration and story layers; draft in [design/exploration-and-story.md](design/exploration-and-story.md)), authoring and package format, compatibility between app and content versions, and package signing.
3. ADR 0006: where content updates are hosted (the spike's raw GitHub link is not a delivery service).
4. ADR 0007: the v1 map data pipeline for the Arabian Peninsula, with more detail where events happen (Makkah, Taif, Madinah, Badr and the routes), since full detail everywhere would be very large.
5. Design: check the Penpot screens against the v1 scope (Taif to Badr, the Arabian Peninsula, panels over a map that stays on screen, months where sourced).

## 3. Closed beta → public release
