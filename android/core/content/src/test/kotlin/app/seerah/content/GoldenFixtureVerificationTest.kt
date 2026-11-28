package app.seerah.content

import app.seerah.model.ReliefEncoding
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoldenFixtureVerificationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun loadFixtureFile(name: String, outDir: File): File {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing test fixture resource: /fixtures/$name"
        }
        return File(outDir, name).apply {
            writeBytes(stream.use { it.readBytes() })
        }
    }

    private fun buildPipeline(appVersion: Long = 1L): PackageVerificationPipeline {
        val keysBytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/trusted-keys.json")) {
            "Missing test fixture /fixtures/trusted-keys.json"
        }.use { it.readBytes() }
        val keyRing = TrustedKeyRingLoader.loadFromBytes(keysBytes)
        assertIs<KeyRingState.Configured>(keyRing)

        return PackageVerificationPipeline(
            verifier = IsolatedTinkEd25519Verifier { keyRing },
            appVersionCodeProvider = object : AppVersionCodeProvider {
                override suspend fun currentVersionCode(): Long = appVersion
            },
        )
    }

    @Test
    fun pythonSignedValidMapFixture_verifiesAndParsesAllMetadataAndPmTiles() = runTest {
        val work = tempFolder.newFolder("valid-map")
        val zipFile = loadFixtureFile("valid-map-v1.zip", work)
        val staging = File(work, "staging").apply { mkdirs() }

        val result = buildPipeline().verifyAndExtract(zipFile, staging, expectedKind = PackageKind.MAP)
        val verified = assertIs<PackageResult.Success<VerifiedPackageStaging>>(result).value

        assertEquals(PackageKind.MAP, verified.manifest.kind)
        assertEquals(1, verified.manifest.version)
        assertEquals("rfc8032-test-1", verified.manifest.keyId)

        val mapMeta = assertNotNull(verified.mapMetadata)
        assertEquals(1, mapMeta.tileSchema)
        assertEquals(ReliefEncoding.TERRARIUM_VALUE, mapMeta.sources.relief.encoding)
        assertEquals(setOf("land", "coast"), mapMeta.sources.base.layers)
        assertTrue(File(staging, "data/base.pmtiles").isFile)
        assertTrue(File(staging, "data/map.json").isFile)
        assertTrue(File(staging, "data/relief.pmtiles").isFile)
    }

    @Test
    fun pythonSignedValidContentFixture_decompressesDeflatedPayload() = runTest {
        val work = tempFolder.newFolder("valid-content")
        val zipFile = loadFixtureFile("valid-content-v1.zip", work)
        val staging = File(work, "staging").apply { mkdirs() }

        val result = buildPipeline().verifyAndExtract(zipFile, staging, expectedKind = PackageKind.CONTENT)
        val verified = assertIs<PackageResult.Success<VerifiedPackageStaging>>(result).value

        assertEquals(PackageKind.CONTENT, verified.manifest.kind)
        assertNull(verified.mapMetadata)
        val extracted = File(staging, "data/index.json")
        assertTrue(extracted.isFile)
        assertTrue("taif" in extracted.readText())
    }

    @Test
    fun pythonSignedTrialMapFixture_isRejectedByGoal9GuardInKotlin() = runTest {
        val work = tempFolder.newFolder("trial-map")
        val zipFile = loadFixtureFile("trial-rejected-map-v1.zip", work)
        val staging = File(work, "staging").apply { mkdirs() }

        val result = buildPipeline().verifyAndExtract(zipFile, staging, expectedKind = PackageKind.MAP)
        val failure = assertIs<PackageResult.Failure>(result)
        val invalid = assertIs<PackageFailure.InvalidPackage>(failure.reason)
        assertEquals(InvalidPackageCause.INVALID_PMTILES, invalid.cause)
        assertTrue("Goal 9" in invalid.detail || "trial" in invalid.detail)
    }
}
