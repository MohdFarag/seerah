package org.seerah.spike.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Content of one package (content.json). The spike uses synthetic data only (`test = true`). */
@Serializable
data class ContentPack(
    val format: Int,
    @SerialName("content_version") val contentVersion: String,
    val title: String,
    val test: Boolean = false,
    val years: YearRange,
    val initial: CameraSpec,
    val events: List<Event>,
) {
    fun event(id: String): Event? = events.firstOrNull { it.id == id }

    /** Events whose display date falls in [year]. Undated events never match a year. */
    fun eventsInYear(year: Int): List<Event> = events.filter { it.date.year == year }

    /** Nearest dated event before/after [year]: offered when a year has no events. */
    fun previousEvent(year: Int): Event? =
        events.filter { it.date.year != null && it.date.year < year }.maxByOrNull { it.date.year!! }

    fun nextEvent(year: Int): Event? =
        events.filter { it.date.year != null && it.date.year > year }.minByOrNull { it.date.year!! }

    /** Integrity problems that must block a package from being activated. */
    fun validate(): List<String> = buildList {
        if (format != SUPPORTED_FORMAT) add("unsupported content format $format")
        if (years.min > years.max) add("years.min > years.max")
        val dup = events.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) add("duplicate event ids: $dup")
        events.forEach { e -> e.validate().forEach { add("${e.id}: $it") } }
    }

    companion object {
        const val SUPPORTED_FORMAT = 1
        val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ContentPack = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class YearRange(val min: Int, val max: Int, val label: String)

@Serializable
data class CameraSpec(val year: Int, val lon: Double, val lat: Double, val zoom: Double)

@Serializable
data class Event(
    val id: String,
    val title: String,
    val date: EventDate,
    val place: EventPlace,
    val texts: List<Text>,
    val explanation: String? = null,
    val test: Boolean = false,
) {
    fun validate(): List<String> = date.validate() + place.validate() +
        (if (texts.isEmpty()) listOf("no texts") else emptyList()) +
        texts.flatMap { it.validate() }
}

/**
 * The single display date chosen for an event (PRD v0.2 §5).
 * Only the documented parts are present; nothing is inferred.
 */
@Serializable
data class EventDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val precision: Precision,
    val approximate: Boolean = false,
) {
    @Serializable
    enum class Precision {
        @SerialName("year") YEAR,
        @SerialName("month") MONTH,
        @SerialName("day") DAY,
        @SerialName("unknown") UNKNOWN,
    }

    fun validate(): List<String> = buildList {
        when (precision) {
            Precision.YEAR -> if (year == null || month != null || day != null) add("precision=year needs year only")
            Precision.MONTH -> if (year == null || month == null || day != null) add("precision=month needs year+month only")
            Precision.DAY -> if (year == null || month == null || day == null) add("precision=day needs year+month+day")
            Precision.UNKNOWN -> if (year != null || month != null || day != null) add("precision=unknown must not carry parts")
        }
        if (month != null && month !in 1..12) add("month out of range")
        if (day != null && day !in 1..31) add("day out of range") // calendar reference is still an open decision
    }

    /** Human label that states precision and approximation. Never adds a missing part. */
    fun label(yearLabel: String): String {
        val y = year?.let { "$yearLabel ${it.toArabicDigits()}" }
        val text = when (precision) {
            Precision.UNKNOWN -> "التاريخ غير معروف"
            Precision.YEAR -> y!!
            Precision.MONTH -> "الشهر ${month!!.toArabicDigits()} من $y"
            Precision.DAY -> "اليوم ${day!!.toArabicDigits()} من الشهر ${month!!.toArabicDigits()} من $y"
        }
        return if (approximate) "$text (تقريبي)" else text
    }
}

/** Where an event happened, with honest accuracy (PRD v0.2 §5 "دقة المكان والمسار"). */
@Serializable
data class EventPlace(
    val accuracy: Accuracy,
    val lon: Double? = null,
    val lat: Double? = null,
    val zoom: Double? = null,
    /** For `general`: the historical entity (city or region) the event is tied to. */
    val entity: String? = null,
) {
    @Serializable
    enum class Accuracy {
        @SerialName("exact") EXACT,
        @SerialName("general") GENERAL,
        @SerialName("unknown") UNKNOWN,
    }

    /** Only an exactly known place gets a point marker. */
    val showsMarker: Boolean get() = accuracy == Accuracy.EXACT

    /** Camera target when the event is opened; null means "don't move the map". */
    fun cameraTarget(): CameraSpec? =
        if (accuracy == Accuracy.UNKNOWN || lon == null || lat == null) null
        else CameraSpec(year = 0, lon = lon, lat = lat, zoom = zoom ?: 6.0)

    fun note(): String? = when (accuracy) {
        Accuracy.EXACT -> null
        Accuracy.GENERAL -> "المنطقة معروفة، والموضع الدقيق غير معروف"
        Accuracy.UNKNOWN -> "المكان غير معروف"
    }

    fun validate(): List<String> = buildList {
        when (accuracy) {
            Accuracy.EXACT -> if (lon == null || lat == null) add("exact place needs lon/lat")
            Accuracy.GENERAL -> if (entity == null) add("general place needs an entity")
            Accuracy.UNKNOWN -> if (lon != null || lat != null) add("unknown place must not carry coordinates")
        }
    }
}

@Serializable
data class Text(val id: String, val kind: String, val body: String, val source: Source) {
    fun validate(): List<String> =
        if (source.work.isBlank() || source.locator.isBlank()) listOf("$id: text without a source") else emptyList()
}

@Serializable
data class Source(val kind: String, val work: String, val locator: String)

private val arabicDigits = "٠١٢٣٤٥٦٧٨٩"

fun Int.toArabicDigits(): String = toString().map { c -> if (c.isDigit()) arabicDigits[c - '0'] else c }.joinToString("")
