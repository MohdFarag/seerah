package org.seerah.spike.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeStatesTest {
    private val hist = Json.parseToJsonElement(File(Fixtures.src, "historical.v1.geojson").readText()).jsonObject

    /** Evaluate the states the same way the generated filter does. */
    private fun visible(year: Int): Map<String, String> =
        hist["features"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject }
            .filter { TimeStates.isActive(it["from"]!!.jsonPrimitive.int, it["to"]?.jsonPrimitive?.intOrNull, year) }
            .associate { it["entity"]!!.jsonPrimitive.content to it["name"]!!.jsonPrimitive.content }

    @Test fun `historical test data has no overlapping states`() {
        assertEquals(emptyList(), TimeStates.validateHistorical(hist))
    }

    @Test fun `place name changes with time`() {
        assertEquals("قَرْيَةُ الاخْتِبَارِ", visible(2)["test-town"])
        assertEquals("مَدِينَةُ الاخْتِبَارِ", visible(3)["test-town"], "end-of-year view: change during year 3 shows in 3")
    }

    @Test fun `region border swaps shape and landmark appears from its year`() {
        val states = hist["features"]!!.jsonArray.map { it.jsonObject }
        fun regionGeom(y: Int) = states.single {
            val p = it["properties"]!!.jsonObject
            p["entity"]!!.jsonPrimitive.content == "test-region" &&
                TimeStates.isActive(p["from"]!!.jsonPrimitive.int, p["to"]?.jsonPrimitive?.intOrNull, y)
        }["geometry"]
        assertTrue(regionGeom(2) != regionGeom(3))
        assertTrue("test-landmark" !in visible(2))
        assertTrue("test-landmark" in visible(3))
    }

    @Test fun `exactly one state per entity at any year`() {
        for (y in 1..6) {
            val counts = hist["features"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject }
                .filter { TimeStates.isActive(it["from"]!!.jsonPrimitive.int, it["to"]?.jsonPrimitive?.intOrNull, y) }
                .groupingBy { it["entity"]!!.jsonPrimitive.content }.eachCount()
            assertTrue(counts.values.all { it == 1 }, "year $y: $counts")
        }
    }

    @Test fun `generated filter keeps the layer filter and adds the time window`() {
        val style = Json.parseToJsonElement(File(Fixtures.dist, "pack-1.0.0-test/style.template.json").readText()) as JsonObject
        val layers = TimeStates.timeLayers(style)
        assertEquals(setOf("region-fill", "region-line", "region-label", "landmark-dot", "place-label"), layers.keys)
        val f = TimeStates.filter(layers["region-fill"], 3).toString()
        assertEquals("""["all",["==",["get","kind"],"region"],["<=",["get","from"],3],[">",["coalesce",["get","to"],1000000000],3]]""", f)
    }

    @Test fun `markers only for exact places of the selected year`() {
        val pack = Fixtures.content("content.v1.json")
        val ids = TimeStates.eventMarkers(pack)["features"]!!.jsonArray.map {
            it.jsonObject["properties"]!!.jsonObject["id"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("e1"), ids, "e2 is general, e3 unknown: no point markers")
        assertEquals("""["==",["get","year"],2]""", TimeStates.markerFilter(2).toString())
    }
}
