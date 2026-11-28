package app.seerah.content

import app.seerah.model.CalendarAxis
import app.seerah.model.EventKind
import app.seerah.model.ExpeditionRole
import app.seerah.model.HistoricalDate
import app.seerah.model.PlaceLocation
import app.seerah.model.RouteGeometry
import app.seerah.model.StepCamera
import app.seerah.model.StepTime
import app.seerah.model.TextKind
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class ContentPackageParserTest {
    @Test
    fun parsesGoldenValidContentFixtureArchiveCleanly() {
        val entries = HashMap<String, ByteArray>()
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/valid-content-v1.zip"))
        ZipInputStream(stream).use { zip ->
            generateSequence { zip.nextEntry }.filter { it.name.startsWith("data/") }.forEach { entry ->
                entries[entry.name] = zip.readBytes()
            }
        }

        val parsed = assertIs<PackageResult.Success<ParsedContentPackage>>(
            ContentPackageParser.parseAndValidate(entries, packageVersion = 1, generation = 1L),
        ).value

        assertEquals(1, parsed.summary.contentSchema)
        assertEquals("synthetic-1", parsed.summary.contentVersion)
        assertEquals(listOf("fixture-event"), parsed.orderedEvents.map { it.id })
        assertEquals(listOf("fixture-text"), parsed.orderedTexts.map { it.id })
    }

    @Test
    fun parsesRichMasirahCorpusAndToleratesFutureAdditiveFields() {
        val entries = SyntheticContentFixtures.richMasirahEntries()
        // Add additive unknown key in meta.json for forward compatibility (ADR 0008).
        val metaJson = String(entries.getValue("data/meta.json"), StandardCharsets.UTF_8)
        entries["data/meta.json"] = metaJson.replace(
            """"content_schema": 1,""",
            """"content_schema": 1, "future_note": "forward-compat",""",
        ).toByteArray(StandardCharsets.UTF_8)

        val parsed = assertIs<PackageResult.Success<ParsedContentPackage>>(
            ContentPackageParser.parseAndValidate(entries, packageVersion = 4, generation = 2L),
        ).value

        assertEquals(4, parsed.summary.packageVersion)
        assertEquals(2L, parsed.summary.generation)
        assertEquals(CalendarAxis.PRE_HIJRA, parsed.summary.minTimelinePoint?.axis)
        assertEquals(2, parsed.summary.minTimelinePoint?.year)
        assertEquals(CalendarAxis.HIJRI, parsed.summary.maxTimelinePoint?.axis)
        assertEquals(2, parsed.summary.maxTimelinePoint?.year)

        // Parent ordered before child for FK insert safety.
        assertEquals(listOf("event-root", "event-child"), parsed.orderedEvents.map { it.id })
        val child = parsed.orderedEvents[1]
        assertEquals(EventKind.EXPEDITION, child.kind)
        assertEquals(ExpeditionRole.PROPHET_LED, child.expeditionRole)
        assertIs<HistoricalDate.Day>(child.date)

        // Non-excerpts ordered before excerpts for FK insert safety.
        assertEquals(listOf("text-editorial", "text-full", "text-excerpt"), parsed.orderedTexts.map { it.id })
        assertEquals(TextKind.EDITORIAL, parsed.orderedTexts[0].kind)

        assertIs<PlaceLocation.Unknown>(parsed.places.first { it.id == "island-unknown" }.location)
        assertIs<PlaceLocation.OffMap>(parsed.places.first { it.id == "cape-offmap" }.location)
        assertIs<RouteGeometry.Documented>(parsed.routes.single().geometry)

        val story = parsed.stories.single()
        val steps = story.chapters.single().scenes.single().steps
        assertIs<StepCamera.Frame>(steps[0].camera)
        assertIs<StepCamera.Follow>(steps[1].camera)
        assertIs<StepTime.TimeLapse>(steps[1].time)
    }

    @Test
    fun rejectsSchemaNullGregorianCycleRefAndInvariantViolations() {
        fun assertInvalid(mutator: (MutableMap<String, ByteArray>) -> Unit, caseLabel: String) {
            val entries = SyntheticContentFixtures.richMasirahEntries()
            mutator(entries)
            val result = ContentPackageParser.parseAndValidate(entries, packageVersion = 1, generation = 1L)
            val failure = assertIs<PackageResult.Failure>(result, "Expected failure for $caseLabel")
            val invalid = assertIs<PackageFailure.InvalidPackage>(failure.reason, "Wrong failure for $caseLabel")
            assertEquals(InvalidPackageCause.INVALID_CONTENT_PAYLOAD, invalid.cause, caseLabel)
        }

        // 1. Missing required JSON file
        assertInvalid({ it.remove("data/stories.json") }, "missing stories.json")

        // 2. Float content_schema (1.0) or unsupported version (2)
        assertInvalid({
            val s = String(it.getValue("data/meta.json"), StandardCharsets.UTF_8)
            it["data/meta.json"] = s.replace(""""content_schema": 1""", """"content_schema": 1.0""").toByteArray()
        }, "float content_schema")
        assertInvalid({
            val s = String(it.getValue("data/meta.json"), StandardCharsets.UTF_8)
            it["data/meta.json"] = s.replace(""""content_schema": 1""", """"content_schema": 2""").toByteArray()
        }, "unsupported content_schema")

        // 3. Disallowed explicit null field
        assertInvalid({
            val s = String(it.getValue("data/sources.json"), StandardCharsets.UTF_8)
            it["data/sources.json"] =
                s.replace(""""work": "Hadith Work"""", """"edition": null, "work": "Hadith Work"""").toByteArray()
        }, "disallowed null in source.edition")

        // 4. Gregorian axis rejected & year zero rejected
        assertInvalid({
            val s = String(it.getValue("data/events.json"), StandardCharsets.UTF_8)
            it["data/events.json"] = s.replace(""""calendar": "hijri"""", """"calendar": "gregorian"""").toByteArray()
        }, "gregorian calendar")
        assertInvalid({
            val s = String(it.getValue("data/events.json"), StandardCharsets.UTF_8)
            it["data/events.json"] = s.replace(""""year": 2""", """"year": 0""").toByteArray()
        }, "year zero")

        // 5. events.parent_id cycle
        assertInvalid({
            val s = String(it.getValue("data/events.json"), StandardCharsets.UTF_8)
            it["data/events.json"] = s.replace(
                """"id": "event-root",""",
                """"id": "event-root", "parent_id": "event-child",""",
            ).toByteArray()
        }, "parent_id cycle")

        // 6. events.related cycle (event-root -> event-child -> event-root)
        assertInvalid({
            val s = String(it.getValue("data/events.json"), StandardCharsets.UTF_8)
            it["data/events.json"] = s.replace(
                """"id": "event-root",""",
                """"id": "event-root", "related": [{"target_event_id": "event-child", "type": "cause-of"}],""",
            ).toByteArray()
        }, "related cycle")

        // 7. Editorial text with source citation & non-editorial text with null source
        assertInvalid({
            val s = String(it.getValue("data/texts.json"), StandardCharsets.UTF_8)
            it["data/texts.json"] = s.replace(
                """"kind": "editorial",
      "source": null""",
                """"kind": "editorial",
      "source": {"locator": "1", "source_work_id": "source-a"}""",
            ).toByteArray()
        }, "editorial with source")
        assertInvalid({
            val s = String(it.getValue("data/texts.json"), StandardCharsets.UTF_8)
            it["data/texts.json"] = s.replace(
                """"source": {"locator": "1/10", "source_work_id": "source-b"}""",
                """"source": null""",
            ).toByteArray()
        }, "non-editorial with null source")

        // 8. excerpt_of pointing to editorial text
        assertInvalid({
            val s = String(it.getValue("data/texts.json"), StandardCharsets.UTF_8)
            it["data/texts.json"] =
                s.replace(""""excerpt_of": "text-full",""", """"excerpt_of": "text-editorial",""").toByteArray()
        }, "excerpt_of editorial")

        // 9. Unknown place declaring coordinates
        assertInvalid({
            val s = String(it.getValue("data/places.json"), StandardCharsets.UTF_8)
            it["data/places.json"] = s.replace(
                """"id": "island-unknown",""",
                """"id": "island-unknown", "coordinates": {"lat": 20.6, "lon": 58.9},""",
            ).toByteArray()
        }, "unknown place with coordinates")

        // 10. Undocumented route declaring coordinates & story follow referencing undocumented route
        assertInvalid({
            val s = String(it.getValue("data/routes.json"), StandardCharsets.UTF_8)
            it["data/routes.json"] = s.replace(""""documented": true""", """"documented": false""").toByteArray()
        }, "undocumented route with coordinates")

        // 11. Mixed camera mode/time mode sibling fields
        assertInvalid({
            val s = String(it.getValue("data/stories.json"), StandardCharsets.UTF_8)
            it["data/stories.json"] = s.replace(
                """"mode": "frame",""",
                """"mode": "frame", "route_id": "route-east",""",
            ).toByteArray()
        }, "mixed frame+follow fields")

        // 12. Broken reference in story panel text_ids
        assertInvalid({
            val s = String(it.getValue("data/stories.json"), StandardCharsets.UTF_8)
            it["data/stories.json"] = s.replace(""""text-editorial"""", """"missing-text"""").toByteArray()
        }, "missing story text ref")

        assertTrue(true)
    }
}
