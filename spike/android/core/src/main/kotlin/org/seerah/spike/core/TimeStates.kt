package org.seerah.spike.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A documented point in time with its precision: only the known parts are present.
 * It stands for a span: `{year 3}` = all of year 3, `{year 3, month 9}` = all of month 9.
 */
@Serializable
data class TimePoint(val year: Int, val month: Int? = null, val day: Int? = null) {
    /** Sortable keys of the first and last day of the span (ordinal, calendar-agnostic). */
    val startKey: Int get() = key(year, month ?: 1, day ?: 1)
    val endKey: Int get() = key(year, month ?: 12, day ?: 31)

    companion object {
        fun key(y: Int, m: Int, d: Int) = y * 10_000 + m * 100 + d
    }
}

/** The moment the map represents. */
sealed interface ViewTime {
    val startKey: Int
    val endKey: Int

    /** Year view: the state at the END of the selected year (PRD v0.2 §5). */
    data class YearEnd(val year: Int) : ViewTime {
        override val startKey = TimePoint.key(year, 12, 31)
        override val endKey = startKey
    }

    /** Opened event: its own span, at the precision of its display date. Nothing finer is assumed. */
    data class EventSpan(val point: TimePoint) : ViewTime {
        override val startKey = point.startKey
        override val endKey = point.endKey
    }

    companion object {
        /** Null when the event has no usable date: the map keeps its current time. */
        fun forEvent(date: EventDate): ViewTime? = date.year?.let { EventSpan(TimePoint(it, date.month, date.day)) }
    }
}

/**
 * Time-dependent map state. Each historical feature is one *state* of a stable entity,
 * valid from the change `from` until the change `to` (absent = still valid).
 *
 * A change counts as having happened at the view time only if its whole span ended by the
 * start of the view: `change.endKey <= view.startKey`. When a change's span overlaps the
 * view (e.g. a change known only as "year 3" and an event in month 7 of year 3), the order
 * is unknown: the earlier state is shown and the overlap is reported, never guessed.
 * The app selects states; the map engine only draws them. Nothing is interpolated.
 */
object TimeStates {
    private const val OPEN_END = Int.MAX_VALUE

    fun happened(change: TimePoint, view: ViewTime): Boolean = change.endKey <= view.startKey

    fun isVisible(from: TimePoint, to: TimePoint?, view: ViewTime): Boolean =
        happened(from, view) && (to == null || !happened(to, view))

    fun overlaps(change: TimePoint, view: ViewTime): Boolean =
        change.startKey <= view.endKey && change.endKey > view.startKey

    /** MapLibre expression for [view]: [baseFilter] AND the state is visible. Uses keys from [annotate]. */
    fun filter(baseFilter: JsonElement?, view: ViewTime): JsonArray = buildJsonArray {
        add(JsonPrimitive("all"))
        if (baseFilter != null) add(baseFilter)
        add(expr("<=", get("from_e"), JsonPrimitive(view.startKey)))
        add(expr(">", buildJsonArray { add(JsonPrimitive("coalesce")); add(get("to_e")); add(JsonPrimitive(OPEN_END)) }, JsonPrimitive(view.startKey)))
    }

    /** Add numeric span keys (`from_s/from_e/to_s/to_e`) so the engine can filter without parsing dates. */
    fun annotate(geojson: JsonObject): JsonObject = buildJsonObject {
        geojson.forEach { (k, v) -> if (k != "features") put(k, v) }
        put("features", buildJsonArray {
            geojson["features"]!!.jsonArray.forEach { f ->
                val fo = f.jsonObject
                val p = fo["properties"]!!.jsonObject
                val from = point(p["from"])!!
                val to = point(p["to"])
                add(buildJsonObject {
                    fo.forEach { (k, v) -> if (k != "properties") put(k, v) }
                    put("properties", buildJsonObject {
                        p.forEach { (k, v) -> if (k != "from" && k != "to") put(k, v) }
                        put("from_s", from.startKey); put("from_e", from.endKey)
                        to?.let { put("to_s", it.startKey); put("to_e", it.endKey) }
                    })
                })
            }
        })
    }

    data class State(val entity: String, val name: String?, val from: TimePoint, val to: TimePoint?)

    fun states(geojson: JsonObject): List<State> = geojson["features"]!!.jsonArray.map {
        val p = it.jsonObject["properties"]!!.jsonObject
        State(p["entity"]!!.jsonPrimitive.content, p["name"]?.jsonPrimitive?.content, point(p["from"])!!, point(p["to"]))
    }

    /** Entities whose change around [view] has an unknown order relative to it (for an honest notice). */
    fun undetermined(geojson: JsonObject, view: ViewTime): List<String> =
        states(geojson).filter { s -> overlaps(s.from, view) || (s.to != null && overlaps(s.to, view)) }
            .mapNotNull { it.name }.distinct()

    /** States of an entity must chain without overlap; every feature is marked test. */
    fun validateHistorical(geojson: JsonObject): List<String> = buildList {
        val all = runCatching { states(geojson) }.getOrElse { return listOf("unreadable states: ${it.message}") }
        all.groupBy { it.entity }.forEach { (entity, ss) ->
            ss.sortedBy { it.from.startKey }.zipWithNext().forEach { (a, b) ->
                if (a.to != b.from) add("$entity: states must chain (one state's to == next state's from)")
            }
        }
        geojson["features"]!!.jsonArray.forEach {
            val p = it.jsonObject["properties"]!!.jsonObject
            if (p["test"]?.jsonPrimitive?.content != "true") add("${p["entity"]}: not marked test")
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

    /** Filters of the template's layers that read the time-dependent source. */
    fun timeLayers(style: JsonObject, source: String = "historical"): Map<String, JsonElement?> =
        style["layers"]!!.jsonArray.map { it.jsonObject }
            .filter { it["source"]?.jsonPrimitive?.content == source }
            .associate { it["id"]!!.jsonPrimitive.content to it["filter"] }

    private fun point(e: JsonElement?): TimePoint? =
        e?.takeIf { it is JsonObject }?.let { ContentPack.json.decodeFromJsonElement<TimePoint>(it) }

    private fun get(name: String) = buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive(name)) }
    private fun expr(op: String, a: JsonElement, b: JsonElement) = buildJsonArray { add(JsonPrimitive(op)); add(a); add(b) }
}
