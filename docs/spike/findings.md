# Spike findings

A running log of what the spike has shown on real devices. Each entry says what was tested and what was not.

## 2026-11-11: Arabic map labels, first device run

**Build:** `:spike` 0.1, MapLibre Native Android 13.7.1, **OpenGL** flavor. One phone, reported by the owner with screenshots. The Vulkan flavor and the other two devices have not been reported yet.

| Path | Result |
| --- | --- |
| Glyph PBFs, no diacritics | Works. Letters join, order is right, ﷺ and Arabic-Indic digits render, mixed Arabic and Latin digits keep their order. |
| Glyph PBFs, with diacritics | Fails. Marks sit beside the letters instead of on them, and they break the joining between letters. |
| `font-faces` (TTF shaped by the engine) | Fails in the test-label layer. Glyph order is reversed and joining forms are wrong, in both fonts. This matches the RTL bug reported upstream for iOS, now seen on Android 13.7.1. The waypoint-label layer looked unchanged in this mode, which is not explained yet. |

Other observations:

- Glyph edges were jagged. That came from this repo's glyph generator, not the engine. The generator now seeds the distance field from anti-aliased coverage; not re-tested on a device yet.
- Amiri has no arrow glyph, so "يثرب ← المدينة" lost its arrow. The Amiri stack now takes missing code points from Plex; not re-tested on a device yet.
- The style used for `font-faces` follows the assistant's reading of the property. A wrong configuration is still possible, so this result is not a final verdict on that path.

**Working conclusion** (to confirm on the other devices): draw map place names **without diacritics** through glyph PBFs. Keep vocalised text in the Compose UI, where the system text engine handles it. The few decorative names can be pre-rendered images.
