package app.seerah.userdata

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.seerah.model.CalendarAxis
import app.seerah.model.FollowAlongBookmark
import app.seerah.model.TimelinePoint
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val SESSION_VERSION = 1
private val JSON_INTEGER = Regex("-?(0|[1-9][0-9]*)")

internal class UnsupportedSessionVersion(val version: Int) : RuntimeException()

internal object ExploreSessionSerializer : Serializer<ExploreSession> {
    override val defaultValue = ExploreSession()

    override suspend fun readFrom(input: InputStream): ExploreSession {
        // Keep stream failures distinct from errors found in the saved bytes.
        val bytes = input.readBytes()
        try {
            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                ?: throw IllegalArgumentException("Expected a session object.")
            val version = root.requiredInt("version")
            require(version > 0) { "Session version must be positive." }
            if (version != SESSION_VERSION) throw UnsupportedSessionVersion(version)

            root.requireFields("version", "time", "focus", "bookmark")
            return ExploreSession(
                time = root.optionalObject("time")?.toTimelinePoint(),
                focus = root.optionalObject("focus")?.toFocus(),
                bookmark = root.optionalObject("bookmark")?.toBookmark(),
            )
        } catch (exception: CharacterCodingException) {
            throw CorruptionException("Session file contains malformed UTF-8.", exception)
        } catch (exception: IllegalArgumentException) {
            throw CorruptionException("Session file contains invalid data.", exception)
        }
    }

    override suspend fun writeTo(t: ExploreSession, output: OutputStream) {
        val root = buildJsonObject {
            put("version", SESSION_VERSION)
            t.time?.let { time ->
                put(
                    "time",
                    buildJsonObject {
                        put("axis", time.axis.wireName)
                        put("year", time.year)
                        time.month?.let { put("month", it) }
                        time.day?.let { put("day", it) }
                    },
                )
            }
            t.focus?.let { focus ->
                put(
                    "focus",
                    buildJsonObject {
                        put(
                            "kind",
                            when (focus) {
                                is ExploreFocus.Place -> "place"
                                is ExploreFocus.Event -> "event"
                            },
                        )
                        put("id", focus.id)
                    },
                )
            }
            t.bookmark?.let { bookmark ->
                put(
                    "bookmark",
                    buildJsonObject {
                        put("storyId", bookmark.storyId)
                        put("chapterId", bookmark.chapterId)
                        put("sceneId", bookmark.sceneId)
                        put("stepId", bookmark.stepId)
                        put("fallbackStepIndex", bookmark.fallbackStepIndex)
                    },
                )
            }
        }
        output.write(root.toString().encodeToByteArray())
    }

    private fun JsonObject.toTimelinePoint(): TimelinePoint {
        requireFields("axis", "year", "month", "day")
        val axisName = requiredString("axis")
        val axis = CalendarAxis.entries.firstOrNull { it.wireName == axisName }
            ?: throw IllegalArgumentException("Unknown calendar axis.")
        return TimelinePoint(
            axis = axis,
            year = requiredInt("year"),
            month = optionalValue("month")?.asInt("month"),
            day = optionalValue("day")?.asInt("day"),
        )
    }

    private fun JsonObject.toFocus(): ExploreFocus {
        requireFields("kind", "id")
        val id = requiredString("id")
        return when (requiredString("kind")) {
            "place" -> ExploreFocus.Place(id)
            "event" -> ExploreFocus.Event(id)
            else -> throw IllegalArgumentException("Unknown exploration focus kind.")
        }
    }

    private fun JsonObject.toBookmark(): FollowAlongBookmark {
        requireFields("storyId", "chapterId", "sceneId", "stepId", "fallbackStepIndex")
        return FollowAlongBookmark(
            storyId = requiredString("storyId"),
            chapterId = requiredString("chapterId"),
            sceneId = requiredString("sceneId"),
            stepId = requiredString("stepId"),
            fallbackStepIndex = requiredInt("fallbackStepIndex"),
        )
    }

    private fun JsonObject.requireFields(vararg allowed: String) {
        require(keys.all { it in allowed }) { "Unexpected session field." }
    }

    private fun JsonObject.optionalValue(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }

    private fun JsonObject.optionalObject(key: String): JsonObject? = optionalValue(key)?.let {
        it as? JsonObject ?: throw IllegalArgumentException("Expected an object in $key.")
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = this[key] as? JsonPrimitive
        require(value != null && value.isString) { "Expected a string in $key." }
        return value.content
    }

    private fun JsonObject.requiredInt(key: String): Int = this[key].asInt(key)

    private fun JsonElement?.asInt(key: String): Int {
        val value = this as? JsonPrimitive
        require(value != null && !value.isString && JSON_INTEGER.matches(value.content)) {
            "Expected an integer in $key."
        }
        return value.intOrNull ?: throw IllegalArgumentException("Integer in $key is out of range.")
    }
}
