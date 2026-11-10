# Roadmap

No dates, budget or estimates have been approved. This is only the order of work.

## 0. Repo ready ✅

Structure, PRD snapshot, requirements digest, content schema, approved event index.

## 1. Technical spike (proves the stack before it's adopted)

From the PRD section "ما يلزم إثباته قبل الاعتماد" (what must be proven before adoption):

- A simplified world base map plus one detailed local area.
- Arabic place names: joined letters, diacritics, mixed with digits, and how labels collide, all **offline**.
- Three time states that show a **name change**, a **border change** and a **landmark appearing**.
- Opening an event restores its time and place context.
- Reading, map and progress after a cold restart in **airplane mode** (no warm cache).
- A content update without a new app version, including an **interrupted download**.
- Measure package size, map smoothness and memory on a target Android phone **and** tablet. Only then set numeric targets.

Added by the owner (2026-11-10), detailed in the [stack study](research/android-stack-study.ar.md) §9–10:

- Map data sourcing trial: georeference one atlas page, compare an AI-drafted route with a hand-traced one, build a base from open elevation data, and try one battle as both a map layer and a standalone illustration. Atlas pages are a reference only; no coordinates are taken from a model's guess.

Exit: the owner approves the stack (update ADR 0002 to Accepted).

## 2. Content sample

Fill one complete event in `content/` (e.g. the Hijra journey or Badr) to the per-event minimum. The owner reviews it.

## 3. v1 build

Map and time, index, event and situation reader, follow-along, offline package and updates, feedback, analytics.

## 4. Closed beta → public release
