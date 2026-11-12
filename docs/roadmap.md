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

Scope set by the owner (2026-11-11): one continuous period, from the journey to Taif (with a short lead-in on the Year of Sorrow) to Badr and its outcome. 26 draft events in 5 phases ([index](../content/index/phases.json)). The map covers the Hijaz only, with no world view.

The app and its content are built together; there is no separate content-sample stage (owner, 2026-11-13). Each event's situations, places, narrations and sources are worked out when that event is prepared, and the owner reviews each event before it ships.

App: map and time, index, event and situation reader, free exploration and follow-along, offline package and updates, feedback, analytics.

## 3. Closed beta → public release
