# Spike sample data: the Hijra route

Sample data for the technical spike only. **Not reviewed and not shippable.**

- **Source:** the owner's Notion log "محطات الهجرة النبوية (استخراج من الصور)", read on 2026-11-10. Coordinates are copied from that log as given. Its open review questions are set aside for the spike by the owner's decision.
- **Coverage:** 3 pre-departure places and route waypoints 1–15 (stages 1–3). Waypoints 16–17 have names only and no geometry. Waypoints 18–41 are not in the log yet.

## Files

| File | Content |
| --- | --- |
| `hijra-waypoints.geojson` | One point per place. `position` is `source` or `pending`. |
| `hijra-route.geojson` | Two lines: home → Thawr cave, and the route so far. Both are marked `approximate`. |

## Rules applied

- Waypoints 11 (Suraqa, Wadi Kulayya) and 13 (al-Kharrar) have **two coordinate pairs** in the source with no stated role. The owner asked for one to be picked for the spike. The pair that lies along the route was used (the second pair for 11, the first for 13). The other pair is kept in `other_source_pair`. This is not a reviewed choice.
- The route line joins consecutive waypoints with straight segments. It is not the path walked.
- Dates are kept as the source words them in `when_source`. They are not display dates.
