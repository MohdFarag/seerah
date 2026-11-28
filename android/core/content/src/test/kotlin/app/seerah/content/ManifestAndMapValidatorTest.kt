package app.seerah.content

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManifestAndMapValidatorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val commit40 = "a".repeat(40)
    private val digest64 = "b".repeat(64)

    private fun validMapJson(
        tileSchemaJson: String = "1",
        maxZoomBase: Int = 10,
        maxZoomRelief: Int = 12,
        layersJson: String = """["land","coast"]""",
        reconstructedJson: String = "[]",
        extraSourcesJson: String = "",
        liabilityJson: String = """"No liability."""",
        extraCreditId: String = "natural-earth",
    ): ByteArray = """
        {
          "tile_schema": $tileSchemaJson,
          "camera_bounds": {"west": 34.0, "south": 12.0, "east": 60.0, "north": 32.0},
          "data_extent": {"west": 33.0, "south": 11.0, "east": 61.0, "north": 33.0},
          "min_zoom": 4.0,
          "max_zoom": 14.0,
          "sources": {
            "base": {"file": "base.pmtiles", "layers": $layersJson, "min_zoom": 0, "max_zoom": $maxZoomBase},
            "relief": {
              "file": "relief.pmtiles",
              "encoding": "terrarium-value",
              "tile_format": "png",
              "tile_size": 256,
              "min_zoom": 0,
              "max_zoom": $maxZoomRelief
            }$extraSourcesJson
          },
          "credits": [
            {"id": "copernicus-worlddem-30", "source": "Copernicus", "attribution": "Attr", "liability": $liabilityJson},
            {"id": "$extraCreditId", "source": "Natural Earth", "attribution": "Public domain."}
          ],
          "reconstructed_areas": $reconstructedJson,
          "future_optional_field": "ignored-forward-compat"
        }
    """.trimIndent().toByteArray()

    @Test
    fun manifestParser_enforcesNullBypassCreatedDatesProvenanceAndKind() {
        val baseManifest = """
            {
              "kind": "content",
              "version": 1,
              "min_app_version": 2,
              "key_id": "k1",
              "source_commit": "$commit40",
              "created": "2026-11-28T00:00:00Z",
              "files": [{"path": "data/a.json", "size": 2, "sha256": "$digest64"}],
              "future_field": 123
            }
        """.trimIndent()
        val ok = PackageManifestParser.parseAndValidate(baseManifest.toByteArray(), "k1")
        assertIs<PackageResult.Success<PackageManifest>>(ok)
        assertEquals(PackageKind.CONTENT, ok.value.kind)

        // Expected kind mismatch -> UNEXPECTED_KIND
        val wrongKind = PackageManifestParser.parseAndValidate(baseManifest.toByteArray(), "k1", PackageKind.MAP)
        assertEquals(
            InvalidPackageCause.UNEXPECTED_KIND,
            assertIs<PackageFailure.InvalidPackage>((wrongKind as PackageResult.Failure).reason).cause,
        )

        // CONTENT manifest declaring `"tool_commit": null` must NOT bypass schema checks
        val nullToolCommit = baseManifest.replace("\"files\"", "\"tool_commit\": null, \"files\"")
        assertIs<PackageResult.Failure>(PackageManifestParser.parseAndValidate(nullToolCommit.toByteArray(), "k1"))

        // Bad timestamps: fractional seconds and non-existent Feb 30
        for (badTs in listOf("2026-11-28T00:00:00.123Z", "2026-02-30T00:00:00Z", "20260-01-01T00:00:00Z")) {
            val bad = baseManifest.replace("2026-11-28T00:00:00Z", badTs)
            assertIs<PackageResult.Failure>(PackageManifestParser.parseAndValidate(bad.toByteArray(), "k1"), badTs)
        }

        // MAP manifest unconditionally refuses local:* image provenance (no allowLocalProvenance backdoor)
        val localImageMap = """
            {
              "kind": "map",
              "version": 1,
              "min_app_version": 1,
              "key_id": "k1",
              "source_commit": "$commit40",
              "tool_commit": "$commit40",
              "image": "local:dev",
              "created": "2026-11-28T00:00:00Z",
              "files": [
                {"path": "data/base.pmtiles", "size": 1, "sha256": "$digest64"},
                {"path": "data/map.json", "size": 1, "sha256": "$digest64"},
                {"path": "data/relief.pmtiles", "size": 1, "sha256": "$digest64"}
              ]
            }
        """.trimIndent()
        assertIs<PackageResult.Failure>(PackageManifestParser.parseAndValidate(localImageMap.toByteArray(), "k1"))
    }

    @Test
    fun mapMetadataParser_handlesReconstructedAreasSchemaParityAndAdditiveFields() {
        // Valid with additive valley layer + additive sources.relief-detail
        val validAdditive = validMapJson(
            layersJson = """["land","coast","valley"]""",
            extraSourcesJson = ""","relief-detail":{"file":"relief-detail.pmtiles"}""",
        )
        assertIs<PackageResult.Success<*>>(MapMetadataParser.parseAndValidate(validAdditive))

        // Non-empty reconstructed_areas with unknown object schema fails with UnsupportedReconstructedAreas(2)
        val reconstructed = validMapJson(reconstructedJson = """[{"future_shape":{"x":1}}, {}]""")
        val rFail = assertIs<PackageResult.Failure>(MapMetadataParser.parseAndValidate(reconstructed))
        val unsupported = assertIs<PackageFailure.UnsupportedReconstructedAreas>(rFail.reason)
        assertEquals(2, unsupported.count)

        // Duplicate layers, string tile_schema, out-of-range max_zoom, null liability, duplicate credit IDs
        val invalidCases = listOf(
            validMapJson(layersJson = """["land","coast","land"]"""),
            validMapJson(tileSchemaJson = """"1""""),
            validMapJson(maxZoomBase = 5),
            validMapJson(maxZoomRelief = 13),
            validMapJson(liabilityJson = "null"),
            validMapJson(extraCreditId = "copernicus-worlddem-30"),
        )
        for (bad in invalidCases) {
            val fail = assertIs<PackageResult.Failure>(MapMetadataParser.parseAndValidate(bad))
            assertEquals(
                InvalidPackageCause.INVALID_MAP_METADATA,
                assertIs<PackageFailure.InvalidPackage>(fail.reason).cause,
            )
        }
    }

    @Test
    fun pmTilesInspector_hardensBinaryDirectoryAndIgnoresHostProvenance() {
        val dir = tempFolder.newFolder("pmtiles")

        // Valid archive WITHOUT name/fingerprint/tool_commit/data_commit/image still passes (ADR 0009 decision 10)
        val minimalNoProv = TestArchiveBuilders.buildMinimalPmTiles(
            metadataBytes = TestArchiveBuilders.gzipBytes(
                """{"tile_schema":1,"encoding":"terrarium-value"}""".toByteArray(),
            ),
        )
        val okFile = TestArchiveBuilders.writeTempBytes(dir, "minimal.pmtiles", minimalNoProv)
        assertIs<PackageResult.Success<PmTilesInspector.InspectionResult>>(
            PmTilesInspector.inspectFile(okFile, "minimal"),
        )

        // High unsigned uint8 maxZoom (200 as Byte = -56) must NOT wrap or pass 0..22 check
        val signedByteMaxZoom = TestArchiveBuilders.buildMinimalPmTiles(maxZoom = 200)
        assertInvalidPmTiles(dir, "uint8.pmtiles", signedByteMaxZoom)

        // Header/section overlap (metadataOffset < 127)
        val overlap = TestArchiveBuilders.buildMinimalPmTiles(metadataOffsetOverride = 100L)
        assertInvalidPmTiles(dir, "overlap.pmtiles", overlap)

        // Degenerate relief bounds
        val badBounds = TestArchiveBuilders.buildMinimalPmTiles(minLonE7 = 100, maxLonE7 = 100)
        assertIs<PackageResult.Success<PmTilesInspector.InspectionResult>>(
            PmTilesInspector.inspectFile(
                TestArchiveBuilders.writeTempBytes(dir, "bounds.pmtiles", badBounds),
                "bounds",
            ),
        )
        val inspectResult = (
            PmTilesInspector.inspectFile(
                File(dir, "bounds.pmtiles"),
                "bounds",
            ) as PackageResult.Success
            ).value
        assertEquals(null, inspectResult.header.bounds)

        // Goal 9 "trial" key and string "tile_schema": "1" refused
        assertInvalidPmTiles(
            dir,
            "trial.pmtiles",
            TestArchiveBuilders.buildMinimalPmTiles(
                metadataBytes = TestArchiveBuilders.gzipBytes("""{"tile_schema":1,"trial":true}""".toByteArray()),
            ),
        )
        assertInvalidPmTiles(
            dir,
            "str-schema.pmtiles",
            TestArchiveBuilders.buildMinimalPmTiles(
                metadataBytes = TestArchiveBuilders.gzipBytes("""{"tile_schema":"1"}""".toByteArray()),
            ),
        )

        // Gzip bomb exceeding 1 MiB (1_048_577 bytes)
        val bombJson = ByteArrayOutputStream().apply {
            write("""{"tile_schema":1,"pad":""".toByteArray())
            write("\"".toByteArray())
            write(ByteArray(ONE_MIB_BYTES) { 'x'.code.toByte() })
            write("\"}".toByteArray())
        }.toByteArray()
        assertInvalidPmTiles(
            dir,
            "gzip-bomb.pmtiles",
            TestArchiveBuilders.buildMinimalPmTiles(metadataBytes = TestArchiveBuilders.gzipBytes(bombJson)),
        )

        // Overlong LEB128 varint (> 10 bytes with high continuation bits) in root directory
        val overlongVarint = ByteArray(12) { 0x81.toByte() }
        assertInvalidPmTiles(
            dir,
            "bad-varint.pmtiles",
            TestArchiveBuilders.buildMinimalPmTiles(rootDirBytes = TestArchiveBuilders.gzipBytes(overlongVarint)),
        )

        // Directory OOM numEntries claim inside valid small gzipped root directory
        val insaneEntries = TestArchiveBuilders.encodeVarint(999_999L) + TestArchiveBuilders.encodeVarint(0L)
        assertInvalidPmTiles(
            dir,
            "huge-entries.pmtiles",
            TestArchiveBuilders.buildMinimalPmTiles(rootDirBytes = TestArchiveBuilders.gzipBytes(insaneEntries)),
        )

        // Truncated GZIP stream cleanly maps EOFException/ZipException to INVALID_PMTILES
        assertInvalidPmTiles(
            dir,
            "corrupt-gz.pmtiles",
            TestArchiveBuilders.buildMinimalPmTiles(metadataBytes = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0x00)),
        )
    }

    @Test
    fun pmTilesInspector_validatesOneLevelLeafDirectoryAndRejectsDepthTwo() {
        val dir = tempFolder.newFolder("pmtiles-leaf")

        fun dirEntryBytes(tileId: Long, runLength: Long, length: Long, offsetPlusOne: Long): ByteArray =
            ByteArrayOutputStream().apply {
                write(TestArchiveBuilders.encodeVarint(1L))
                write(TestArchiveBuilders.encodeVarint(tileId))
                write(TestArchiveBuilders.encodeVarint(runLength))
                write(TestArchiveBuilders.encodeVarint(length))
                write(TestArchiveBuilders.encodeVarint(offsetPlusOne))
            }.toByteArray()

        // Valid 1-level leaf directory: root (runLength=0) -> leaf (runLength=1, tile offset=0, len=10)
        val validLeafGz = TestArchiveBuilders.gzipBytes(
            dirEntryBytes(tileId = 0L, runLength = 1L, length = 10L, offsetPlusOne = 1L),
        )
        val rootPointingToLeafGz = TestArchiveBuilders.gzipBytes(
            dirEntryBytes(tileId = 0L, runLength = 0L, length = validLeafGz.size.toLong(), offsetPlusOne = 1L),
        )
        val okLeafPmTiles = TestArchiveBuilders.buildMinimalPmTiles(
            rootDirBytes = rootPointingToLeafGz,
            leafDirBytes = validLeafGz,
        )
        val okFile = TestArchiveBuilders.writeTempBytes(dir, "leaf-ok.pmtiles", okLeafPmTiles)
        assertIs<PackageResult.Success<PmTilesInspector.InspectionResult>>(
            PmTilesInspector.inspectFile(okFile, "leaf-ok"),
        )

        // Depth-2 nested leaf pointer (runLength=0 inside leaf) must be rejected
        val depth2LeafGz = TestArchiveBuilders.gzipBytes(
            dirEntryBytes(tileId = 0L, runLength = 0L, length = 5L, offsetPlusOne = 1L),
        )
        val rootToDepth2Gz = TestArchiveBuilders.gzipBytes(
            dirEntryBytes(tileId = 0L, runLength = 0L, length = depth2LeafGz.size.toLong(), offsetPlusOne = 1L),
        )
        assertInvalidPmTiles(
            dir,
            "leaf-depth2.pmtiles",
            TestArchiveBuilders.buildMinimalPmTiles(rootDirBytes = rootToDepth2Gz, leafDirBytes = depth2LeafGz),
        )
    }

    private fun assertInvalidPmTiles(dir: File, name: String, bytes: ByteArray) {
        val file = TestArchiveBuilders.writeTempBytes(dir, name, bytes)
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
            val failure = assertIs<PackageResult.Failure>(PmTilesInspector.inspectChannel(ch, name))
            assertEquals(
                InvalidPackageCause.INVALID_PMTILES,
                assertIs<PackageFailure.InvalidPackage>(failure.reason).cause,
            )
        }
    }
}
