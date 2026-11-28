package app.seerah.userdata

import androidx.datastore.core.CorruptionException
import app.seerah.model.CalendarAxis
import app.seerah.model.FollowAlongBookmark
import app.seerah.model.TimelinePoint
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExploreSessionSerializerTest {
    @Test
    fun emptySession_encodesOnlyTheVersion() = runTest {
        assertEquals("""{"version":1}""", encode(ExploreSession()))
        assertEquals(ExploreSession(), decode("""{"version":1}"""))
        assertEquals(ExploreSession(), decode("""{"version":1,"time":null,"focus":null,"bookmark":null}"""))
    }

    @Test
    fun yearOnlySession_omitsNullableDatePartsAndAcceptsExplicitNulls() = runTest {
        val session = ExploreSession(time = TimelinePoint(CalendarAxis.PRE_HIJRA, 2))
        assertEquals("""{"version":1,"time":{"axis":"pre-hijra","year":2}}""", encode(session))
        assertEquals(
            session,
            decode("""{"version":1,"time":{"axis":"pre-hijra","year":2,"month":null,"day":null}}"""),
        )
    }

    @Test
    fun sessions_roundTripAllTimelinePrecisionsAndBothFocusTypes() = runTest {
        val bookmark = FollowAlongBookmark("story", "chapter", "scene", "step", 2)
        for (axis in CalendarAxis.entries) {
            val times = listOf(TimelinePoint(axis, 2), TimelinePoint(axis, 2, 9), TimelinePoint(axis, 2, 9, 4))
            val focuses = listOf(ExploreFocus.Place("مكة"), ExploreFocus.Event("event"))

            for (time in times) {
                for (focus in focuses) {
                    val session = ExploreSession(time, focus, bookmark)
                    assertEquals(session, decode(encode(session)))
                }
            }
        }
    }

    @Test
    fun invalidVersionTokens_areUnreadable() = runTest {
        assertFailsWith<CorruptionException> { decode("{}") }
        for (token in listOf("null", "\"1\"", "1.0", "1e0", "0", "-1", "2147483648", "true", "[]")) {
            assertFailsWith<CorruptionException>(token) { decode("""{"version":$token}""") }
        }
    }

    @Test
    fun newerVersion_isDetectedBeforeV1BodyValidation() = runTest {
        val exception = assertFailsWith<UnsupportedSessionVersion> {
            decode("""{"version":2147483647,"time":[],"newField":true}""")
        }
        assertEquals(Int.MAX_VALUE, exception.version)
    }

    @Test
    fun malformedOrInvalidSession_isUnreadable() = runTest {
        val examples = listOf(
            "",
            "{",
            "[]",
            """{"version":1,"extra":true}""",
            """{"version":1,"time":[]}""",
            """{"version":1,"time":{"axis":"hijri"}}""",
            """{"version":1,"time":{"axis":"other","year":1}}""",
            """{"version":1,"time":{"axis":"hijri","year":"1"}}""",
            """{"version":1,"time":{"axis":"hijri","year":0}}""",
            """{"version":1,"time":{"axis":"hijri","year":1,"month":13}}""",
            """{"version":1,"time":{"axis":"hijri","year":1,"day":1}}""",
            """{"version":1,"focus":{"kind":"event","id":2}}""",
            """{"version":1,"focus":{"kind":"event","id":" "}}""",
            """{"version":1,"focus":{"kind":"area","id":"area"}}""",
            """{"version":1,"focus":{"kind":"place","id":"place","extra":2}}""",
            """{"version":1,"bookmark":{"storyId":"s","chapterId":"c","sceneId":"a","stepId":"x"}}""",
            """{"version":1,"bookmark":{"storyId":"s","chapterId":"c","sceneId":"a","stepId":"x","fallbackStepIndex":-1}}""",
        )
        for (example in examples) {
            assertFailsWith<CorruptionException>(example) { decode(example) }
        }
    }

    @Test
    fun malformedUtf8_isRejectedEvenWhenItOccursInsideAJsonString() = runTest {
        val start = "{\"version\":1,\"focus\":{\"kind\":\"place\",\"id\":\"".encodeToByteArray()
        val bytes = start + byteArrayOf(0xC3.toByte(), 0x28) + "\"}}".encodeToByteArray()
        assertFailsWith<CorruptionException> { ExploreSessionSerializer.readFrom(bytes.inputStream()) }
    }

    private suspend fun encode(session: ExploreSession): String = ByteArrayOutputStream().use {
        ExploreSessionSerializer.writeTo(session, it)
        it.toByteArray().decodeToString()
    }

    private suspend fun decode(json: String): ExploreSession = ExploreSessionSerializer.readFrom(json.byteInputStream())
}
