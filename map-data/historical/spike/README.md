# Spike sample data: the Hijra route

Sample data for the technical spike only. **Not reviewed and not shippable.**

- **Source:** the owner's Notion log "محطات الهجرة النبوية (استخراج من الصور)", last read on 2026-11-11. Coordinates are copied from that log as given. Its open review questions are set aside for the spike by the owner's decision.
- **Coverage:** 3 pre-departure places and route waypoints 1–35 (stages 1–6, up to Wadi Rim). The log says 6 waypoints remain in stages 7 and 8, ending at Quba; they are not in it yet.

## Files

| File | Content |
| --- | --- |
| `hijra-waypoints.geojson` | One point per place. `position` is `source` or `pending`. |
| `hijra-route.geojson` | Two lines: home → Thawr cave, and the route so far. Both are marked `approximate`. |

## Rules applied

- Waypoints 11 (Suraqa, Wadi Kulayya) and 13 (al-Kharrar) have **two coordinate pairs** in the source with no stated role. The owner asked for one to be picked for the spike. The pair that lies along the route was used (the second pair for 11, the first for 13). The other pair is kept in `other_source_pair`. This is not a reviewed choice.
- Ten waypoints from 18 onward have two coordinate pairs that join up with their neighbours (one waypoint's second pair is the next one's first). They are read as the start and end of a stretch: the marker sits at the start, the end is kept in `stretch_end`, and the route line passes through both.
- Waypoints 31 (al-Arj) and 32 (al-Jadawat) have the same coordinates in the source, so their markers coincide.
- The route line joins consecutive points with straight segments. It is not the path walked.
- Dates are kept as the source words them in `when_source`. They are not display dates.
