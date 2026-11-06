package org.seerah.spike.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Map-label variants for the font experiment. Only label layout/paint changes between them;
 * data, time filters and the base map are identical.
 *
 * - glyph PBF variants: pre-rendered SDF glyphs; the engine shapes Arabic to presentation forms
 *   (ICU) and places every glyph by its advance — marks get no GPOS anchoring.
 * - [PRESHAPED]: each word was shaped with HarfBuzz at build time and is one glyph (Private Use
 *   Area), so marks are placed by the font's own anchors. Field `name_ps`.
 * - [FONT_FACES] / [FONT_FACES_REVERSED]: style `font-faces` with a local TTF (FreeType + HarfBuzz
 *   inside MapLibre Native). The reversed variant feeds the text backwards, only to diagnose
 *   whether the engine reorders the text before HarfBuzz.
 */
enum class LabelVariant(
    val title: String,
    val fontstack: String,
    val field: String = "name",
    val fontFaces: Boolean = false,
    val tuned: Boolean = true,
) {
    NASKH_OLD("Naskh (السابق)", "Noto Naskh Arabic Regular", tuned = false),
    NASKH("Noto Naskh", "Noto Naskh Arabic Regular"),
    NOTO_SANS("Noto Sans Arabic", "Noto Sans Arabic Medium"),
    NOTO_KUFI("Noto Kufi Arabic", "Noto Kufi Arabic Medium"),
    AMIRI("Amiri", "Amiri Bold"),
    PRESHAPED("مُشكَّل مسبقًا (HarfBuzz)", "Preshaped Noto Sans Arabic Medium", field = "name_ps"),
    FONT_FACES("font-faces", "Noto Sans Arabic Medium", fontFaces = true),
    FONT_FACES_REVERSED("font-faces معكوس (تشخيص)", "Noto Sans Arabic Medium", field = "name_rev", fontFaces = true),
}

object StyleBuilder {
    val LABEL_LAYERS = setOf("place-label", "region-label", "sample-label")
    private val ARABIC_RANGES = listOf("U+0600-06FF", "U+0750-077F", "U+08A0-08FF", "U+FB50-FDFF", "U+FE70-FEFF")

    /** Resolve the package's style to local files only, with time keys and the chosen label variant. */
    fun build(packDir: File, variant: LabelVariant): String {
        val template = Json.parseToJsonElement(
            File(packDir, "style.template.json").readText().replace("{PACK}", packDir.absolutePath),
        ).jsonObject
        val historical = TimeStates.annotate(Json.parseToJsonElement(File(packDir, "historical.geojson").readText()).jsonObject)
        val sources = template["sources"]!!.jsonObject.toMutableMap()
        sources["historical"] = buildJsonObject { put("type", "geojson"); put("data", historical) }
        val fontstack = fontstack(packDir, variant)
        val layers = buildJsonArray {
            template["layers"]!!.jsonArray.forEach { l ->
                val lo = l.jsonObject
                add(if (lo["id"]!!.jsonPrimitive.content in LABEL_LAYERS) labelLayer(lo, variant, fontstack) else lo)
            }
        }
        val style = buildJsonObject {
            template.forEach { (k, v) -> if (k != "sources" && k != "layers") put(k, v) }
            put("sources", JsonObject(sources))
            put("layers", layers)
            if (variant.fontFaces) {
                put("font-faces", buildJsonObject {
                    put(variant.fontstack, buildJsonArray {
                        add(buildJsonObject {
                            put("url", "file://${packDir.absolutePath}/fonts/NotoSansArabic-Medium.ttf")
                            put("unicode-range", JsonArray(ARABIC_RANGES.map(::JsonPrimitive)))
                        })
                    })
                })
            }
        }.toString()
        require(!Regex("\"https?://").containsMatchIn(style)) { "style must not reference the network" }
        return style
    }

    /** Pre-shaped glyphs live in a per-content stack (see pack/labels.py); others use the variant's name. */
    fun fontstack(packDir: File, v: LabelVariant): String {
        if (v != LabelVariant.PRESHAPED) return v.fontstack
        val meta = File(packDir, "labels.json")
        require(meta.isFile) { "package has no labels.json for pre-shaped labels" }
        return Json.parseToJsonElement(meta.readText()).jsonObject["preshaped_stack"]!!.jsonPrimitive.content
    }

    private fun labelLayer(layer: JsonObject, v: LabelVariant, fontstack: String): JsonObject {
        val id = layer["id"]!!.jsonPrimitive.content
        val layout = layer["layout"]!!.jsonObject.toMutableMap()
        val paint = layer["paint"]!!.jsonObject.toMutableMap()
        layout["text-font"] = JsonArray(listOf(JsonPrimitive(fontstack)))
        layout["text-field"] = JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(v.field)))
        if (v.field == "name_ps") layout["text-max-width"] = JsonPrimitive(100) // lines are pre-broken
        if (v.tuned) {
            // Larger text as you zoom in (marks are ~1/5 of the letter height), a thin soft halo
            // so small marks are not swallowed, and a little extra line height for stacked marks.
            val (z4, z8) = if (id == "region-label") 15 to 21 else 15 to 22
            layout["text-size"] = interpolate(4 to z4, 8 to z8)
            layout["text-line-height"] = JsonPrimitive(1.35)
            paint["text-color"] = JsonPrimitive("#1f160d")
            paint["text-halo-color"] = JsonPrimitive("rgba(252,248,238,0.9)")
            paint["text-halo-width"] = JsonPrimitive(1.1)
            paint["text-halo-blur"] = JsonPrimitive(0.4)
        }
        return JsonObject(layer + mapOf("layout" to JsonObject(layout), "paint" to JsonObject(paint)))
    }

    private fun interpolate(vararg stops: Pair<Int, Int>): JsonElement = buildJsonArray {
        add(JsonPrimitive("interpolate")); add(buildJsonArray { add(JsonPrimitive("linear")) })
        add(buildJsonArray { add(JsonPrimitive("zoom")) })
        stops.forEach { (z, s) -> add(JsonPrimitive(z)); add(JsonPrimitive(s)) }
    }
}
