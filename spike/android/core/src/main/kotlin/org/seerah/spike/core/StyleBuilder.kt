package org.seerah.spike.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * How map labels get their glyphs (both are MapLibre Native features):
 * - [PBF]: pre-rendered SDF glyph ranges (`glyphs` URL). Arabic is shaped to presentation
 *   forms; diacritics are not positioned with the font's GPOS data.
 * - [FONT_FACES]: the style's `font-faces` points at a TTF; Native rasterizes it with FreeType
 *   and shapes with HarfBuzz (Android since 11.13.0). Unmatched characters fall back to PBF.
 */
enum class LabelFonts { PBF, FONT_FACES }

object StyleBuilder {
    const val FONTSTACK = "Noto Naskh Arabic Regular"
    private val ARABIC_RANGES = listOf("U+0600-06FF", "U+0750-077F", "U+08A0-08FF", "U+FB50-FDFF", "U+FE70-FEFF")

    /** Resolve the package's style to local files only, with time keys and the chosen label path. */
    fun build(packDir: File, fonts: LabelFonts): String {
        val template = Json.parseToJsonElement(
            File(packDir, "style.template.json").readText().replace("{PACK}", packDir.absolutePath),
        ).jsonObject
        val historical = TimeStates.annotate(Json.parseToJsonElement(File(packDir, "historical.geojson").readText()).jsonObject)
        val sources = template["sources"]!!.jsonObject.toMutableMap()
        sources["historical"] = buildJsonObject { put("type", "geojson"); put("data", historical) }
        val style = buildJsonObject {
            template.forEach { (k, v) -> if (k != "sources") put(k, v) }
            put("sources", JsonObject(sources))
            if (fonts == LabelFonts.FONT_FACES) {
                put("font-faces", buildJsonObject {
                    put(FONTSTACK, buildJsonArray {
                        add(buildJsonObject {
                            put("url", "file://${packDir.absolutePath}/fonts/NotoNaskhArabic-Regular.ttf")
                            put("unicode-range", buildJsonArray { ARABIC_RANGES.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        })
                    })
                })
            }
        }.toString()
        require(!Regex("\"https?://").containsMatchIn(style)) { "style must not reference the network" }
        return style
    }
}
