package app.seerah.trial

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrialFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validMetadata = """{"trial":true,"variant":"painted","fingerprint":"0123456789abcdef","maxzoom":12}"""

    private val cameraPath =
        """
        {
          "stops": [{"lat": 20.62, "lon": 58.9, "zoom": 10, "duration_ms": 4000}],
          "limits": {"west": 58.8, "south": 20.5, "east": 59.05, "north": 20.75, "min_zoom": 4, "max_zoom": 14}
        }
        """.trimIndent()

    private fun variantFolder(
        name: String = "trial-painted-01234567",
        metadata: String? = validMetadata,
        camera: String? = cameraPath,
        relief: Boolean = true,
        base: Boolean = true,
    ): File = temporaryFolder.newFolder(name).also { folder ->
        metadata?.let { File(folder, "metadata.json").writeText(it) }
        camera?.let { File(folder, "camera-path.json").writeText(it) }
        if (relief) File(folder, "relief.pmtiles").writeText("tiles")
        if (base) File(folder, "base.pmtiles").writeText("tiles")
    }

    private fun loadOnlyEntry(): VariantLoad = loadVariants(temporaryFolder.root).single()

    private fun assertSkippedMentioning(word: String) {
        val skipped = assertIs<VariantLoad.Skipped>(loadOnlyEntry())
        assertTrue(skipped.reason.contains(word, ignoreCase = true), "reason was: ${skipped.reason}")
    }

    @Test
    fun validFolder_isLoaded() {
        val folder = variantFolder()

        val loaded = assertIs<VariantLoad.Loaded>(loadOnlyEntry()).variant

        assertEquals(folder, loaded.folder)
        assertEquals(Variant.PAINTED, loaded.variant)
        assertEquals("01234567", loaded.fingerprint8)
        assertEquals(File(folder, "relief.pmtiles"), loaded.relief)
        assertEquals(File(folder, "base.pmtiles"), loaded.base)
        assertEquals(
            CameraStop(lat = 20.62, lon = 58.9, zoom = 10.0, durationMs = 4000),
            loaded.cameraPath.stops.single(),
        )
        assertEquals(4.0, loaded.cameraPath.limits.minZoom)
        assertEquals(14.0, loaded.cameraPath.limits.maxZoom)
        assertEquals(58.8, loaded.cameraPath.limits.west)
    }

    @Test
    fun folderWithoutTrialTrue_isSkippedWithReason() {
        variantFolder(metadata = validMetadata.replace("\"trial\":true", "\"trial\":false"))

        assertSkippedMentioning("trial")
    }

    @Test
    fun folderWithoutCameraPath_isSkippedWithReason() {
        variantFolder(camera = null)

        assertSkippedMentioning("camera-path.json")
    }

    @Test
    fun folderWithUnknownVariant_isSkippedWithReason() {
        variantFolder(metadata = validMetadata.replace("painted", "per-zoom"))

        assertSkippedMentioning("variant")
    }

    @Test
    fun folderWithoutRelief_isSkippedWithReason() {
        variantFolder(relief = false)

        assertSkippedMentioning("relief.pmtiles")
    }

    @Test
    fun folderWithoutBaseArchive_isSkippedWithReason() {
        variantFolder(base = false)

        assertEquals("base.pmtiles is missing", assertIs<VariantLoad.Skipped>(loadOnlyEntry()).reason)
    }

    @Test
    fun folderWithMaxZoomNot12_isSkippedWithReason() {
        variantFolder(metadata = validMetadata.replace("\"maxzoom\":12", "\"maxzoom\":11"))

        assertSkippedMentioning("maxzoom")
    }

    @Test
    fun folderWithMalformedJson_isSkippedWithFileNameAndParserMessage() {
        variantFolder(metadata = "{not json")

        assertSkippedMentioning("metadata.json")
        assertSkippedMentioning("Expected")
    }

    @Test
    fun folderWithoutMetadata_isSkippedWithReason() {
        variantFolder(metadata = null)

        assertSkippedMentioning("metadata.json")
    }

    @Test
    fun folderWithShortFingerprint_isSkippedWithReason() {
        variantFolder(metadata = validMetadata.replace("0123456789abcdef", "0123"))

        assertSkippedMentioning("fingerprint")
    }

    @Test
    fun folderWithUnreadableFile_isSkippedNotThrown() {
        val folder = variantFolder()
        val metadata = File(folder, "metadata.json")
        metadata.setReadable(false)
        assumeFalse("running as a user that ignores file permissions", metadata.canRead())

        val skipped = assertIs<VariantLoad.Skipped>(loadOnlyEntry())

        assertTrue(skipped.reason.contains("cannot read", ignoreCase = true), "reason was: ${skipped.reason}")
    }

    @Test
    fun foldersAreListedSortedByName_andFilesAreIgnored() {
        variantFolder(name = "b-value-89abcdef", metadata = validMetadata.replace("painted", "value"))
        variantFolder(name = "a-painted-01234567")
        File(temporaryFolder.root, "notes.txt").writeText("not a variant")

        val names = loadVariants(temporaryFolder.root).map { (it as VariantLoad.Loaded).variant.folder.name }

        assertEquals(listOf("a-painted-01234567", "b-value-89abcdef"), names)
    }

    @Test
    fun missingRoot_givesNoVariants() {
        assertEquals(emptyList(), loadVariants(File(temporaryFolder.root, "absent")))
    }
}
