package org.seerah.spike.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeStatesTest {
    private val hist = Json.parseToJsonElement(File(Fixtures.src, "historical.v1.geojson").readText()).jsonObject
    private val pack = Fixtures.content("content.v1.json")

    private fun visible(view: ViewTime): Map<String, String?> =
        TimeStates.states(hist).filter { TimeStates.isVisible(it.from, it.to, view) }.associate { it.entity to it.name }

    private fun year(y: Int) = ViewTime.YearEnd(y)
    private fun eventView(id: String) = ViewTime.forEvent(pack.event(id)!!.date)!!

    @Test fun `historical test data chains states and is marked test`() {
        assertEquals(emptyList(), TimeStates.validateHistorical(hist))
    }

    @Test fun `year view is the end of the year`() {
        assertEquals("قَرْيَةُ الاخْتِبَارِ", visible(year(2))["test-town"])
        assertEquals("مَدِينَةُ الاخْتِبَارِ", visible(year(3))["test-town"], "a change during year 3 shows in the year-3 view")
        assertTrue("test-landmark" in visible(year(3)), "landmark built in month 9 of year 3 is there at year end")
        assertTrue("test-landmark" !in visible(year(2)))
    }

    @Test fun `opening an event uses the event's own time, not the end of its year`() {
        val e2 = eventView("e2") // month 7 of test year 3
        assertEquals(ViewTime.EventSpan(TimePoint(3, 7)), e2)
        assertTrue("test-landmark" !in visible(e2), "landmark (month 9) must not appear for an event in month 7")
        assertTrue("test-landmark" in visible(year(3)))
    }

    @Test fun `same-year change with unknown order shows the earlier state and is reported`() {
        val e2 = eventView("e2")
        // test-town renamed in "year 3" (no month): order vs. month 7 is unknown
        assertEquals("قَرْيَةُ الاخْتِبَارِ", visible(e2)["test-town"])
        val undetermined = TimeStates.undetermined(hist, e2)
        assertTrue("مَدِينَةُ الاخْتِبَارِ" in undetermined, "$undetermined")
        assertFalse("مَعْلَمٌ تَجْرِيبِيٌّ" in undetermined, "month 9 vs month 7 is a known order")
        assertEquals(emptyList(), TimeStates.undetermined(hist, year(3)), "end of year: every change of that year is known to be done")
    }

    @Test fun `exactly one state per entity at every view`() {
        val views = (1..6).map(::year) + listOf(eventView("e1"), eventView("e2"), ViewTime.EventSpan(TimePoint(3, 9, 15)))
        for (v in views) {
            val counts = TimeStates.states(hist).filter { TimeStates.isVisible(it.from, it.to, v) }.groupingBy { it.entity }.eachCount()
            assertTrue(counts.values.all { it == 1 }, "$v: $counts")
        }
    }

    @Test fun `event with no usable date does not move the map's time`() {
        assertEquals(null, ViewTime.forEvent(EventDate(precision = EventDate.Precision.UNKNOWN)))
    }

    @Test fun `engine filter matches the Kotlin rule on annotated data`() {
        val annotated = TimeStates.annotate(hist)
        val style = Json.parseToJsonElement(File(Fixtures.dist, "pack-1.0.0-test/style.template.json").readText()) as JsonObject
        val layers = TimeStates.timeLayers(style)
        assertEquals(setOf("region-fill", "region-line", "region-label", "landmark-dot", "place-label", "sample-label"), layers.keys)
        val f = TimeStates.filter(layers["region-fill"], eventView("e2")).toString()
        assertEquals("""["all",["==",["get","kind"],"region"],["<=",["get","from_e"],30701],[">",["coalesce",["get","to_e"],2147483647],30701]]""", f)
        // Evaluate the same comparison on the annotated keys for every view.
        val views = (1..6).map(::year) + listOf(eventView("e1"), eventView("e2"))
        for (v in views) {
            val byKeys = annotated["features"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject }
                .filter { p ->
                    p["from_e"]!!.jsonPrimitive.content.toInt() <= v.startKey &&
                        (p["to_e"]?.jsonPrimitive?.content?.toInt() ?: Int.MAX_VALUE) > v.startKey
                }.map { it["entity"]!!.jsonPrimitive.content to it["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(visible(v).map { it.key to it.value }.toSet(), byKeys, "$v")
        }
    }

    @Test fun `markers only for exact places of the selected year`() {
        val ids = TimeStates.eventMarkers(pack)["features"]!!.jsonArray.map {
            it.jsonObject["properties"]!!.jsonObject["id"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("e1"), ids, "e2 is general, e3 unknown: no point markers")
        assertEquals("""["==",["get","year"],2]""", TimeStates.markerFilter(2).toString())
    }

    @Test fun `style is local only and switches label font path`() {
        val dir = File(Fixtures.dist, "pack-1.0.0-test")
        val pbf = StyleBuilder.build(dir, LabelVariant.NASKH)
        val ff = StyleBuilder.build(dir, LabelVariant.FONT_FACES)
        assertFalse("font-faces" in pbf)
        assertTrue(""""font-faces":{"Noto Sans Arabic Medium":[{"url":"file://${dir.absolutePath}/fonts/NotoSansArabic-Medium.ttf"""" in ff)
        assertTrue(File(dir, "fonts/NotoSansArabic-Medium.ttf").isFile)
        assertTrue("\"from_e\"" in ff, "historical data is inlined with time keys")
    }
}
