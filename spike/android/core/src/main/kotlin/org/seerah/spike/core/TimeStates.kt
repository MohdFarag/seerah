package org.seerah.spike.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Time-dependent map state. Each historical feature is one *state* of a stable entity,
 * valid for test-years `from <= y < to` (`to` absent = still valid).
 *
 * The year view shows the state at the END of the selected year (PRD v0.2 §5), so a change
 * that happens during year Y is already visible when Y is selected: `from <= Y`.
 * The app selects states; the map engine only draws them. Nothing is interpolated.
 */
object TimeStates {
    private const val OPEN_END = 1_000_000_000

    fun isActive(from: Int, to: Int?, year: Int): Boolean = from <= year && year < (to ?: OPEN_END)

    /** MapLibre expression: [baseFilter] AND the feature is active at [year]. */
    fun filter(baseFilter: JsonElement?, year: Int): JsonArray = buildJsonArray {
        add(JsonPrimitive("all"))
        if (baseFilter != null) add(baseFilter)
        add(expr("<=", get("from"), JsonPrimitive(year)))
        add(expr(">", buildJsonArray { add(JsonPrimitive("coalesce")); add(get("to")); add(JsonPrimitive(OPEN_END)) }, JsonPrimitive(year)))
    }

    /** Filters of the template's layers that read the time-dependent source. */
    fun timeLayers(style: JsonObject, source: String = "historical"): Map<String, JsonElement?> =
        style["layers"]!!.jsonArray.map { it.jsonObject }
            .filter { it["source"]?.jsonPrimitive?.content == source }
            .associate { it["id"]!!.jsonPrimitive.content to it["filter"] }

    /** States of the same entity must not overlap in time; every state needs `from`. */
    fun validateHistorical(geojson: JsonObject): List<String> = buildList {
        val features = geojson["features"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject }
        features.groupBy { it["entity"]?.jsonPrimitive?.content }.forEach { (entity, states) ->
            if (entity == null) { add("feature without entity"); return@forEach }
            val spans = states.map {
                val from = it["from"]?.jsonPrimitive?.intOrNull
                if (from == null) add("$entity: state without from")
                (from ?: 0) to it["to"]?.jsonPrimitive?.int
            }.sortedBy { it.first }
            spans.zipWithNext().forEach { (a, b) ->
                if ((a.second ?: OPEN_END) > b.first) add("$entity: states overlap (${a.first}..${a.second} and ${b.first}..)")
            }
            states.forEach { if (it["test"]?.jsonPrimitive?.content != "true") add("$entity: not marked test") }
        }
    }

    /** Marker data for events with an exactly known place. General/unknown places get none. */
    fun eventMarkers(pack: ContentPack): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", buildJsonArray {
            pack.events.filter { it.place.showsMarker && it.date.year != null }.forEach { e ->
                add(buildJsonObject {
                    put("type", "Feature")
                    put("geometry", buildJsonObject {
                        put("type", "Point")
                        put("coordinates", buildJsonArray { add(JsonPrimitive(e.place.lon!!)); add(JsonPrimitive(e.place.lat!!)) })
                    })
                    put("properties", buildJsonObject { put("id", e.id); put("year", e.date.year!!) })
                })
            }
        })
    }

    /** Filter for the event marker layer: markers of the selected year only (no accumulation). */
    fun markerFilter(year: Int): JsonArray = expr("==", get("year"), JsonPrimitive(year))

    private fun get(name: String) = buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive(name)) }
    private fun expr(op: String, a: JsonElement, b: JsonElement) = buildJsonArray { add(JsonPrimitive(op)); add(a); add(b) }
}
