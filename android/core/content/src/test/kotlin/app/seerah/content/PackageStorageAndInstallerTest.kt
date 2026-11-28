package app.seerah.content

import app.seerah.model.ReliefEncoding
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageStorageAndInstallerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class RecordingLogger : PackageStorageLogger {
        val warnings = ArrayList<String>()
        override fun warn(tag: String, message: String, throwable: Throwable?) {
            warnings.add("$tag: $message")
        }
    }

    private fun generateEd25519Keypair(keyId: String): Pair<TrustedPublicKey, PrivateKey> {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp: KeyPair = kpg.generateKeyPair()
        val x509 = kp.public.encoded
        // SubjectPublicKeyInfo for Ed25519 is fixed 12-byte ASN.1 header + 32-byte raw public key.
        val rawPub = x509.copyOfRange(x509.size - 32, x509.size)
        return TrustedPublicKey(keyId, rawPub, sha256Hex(rawPub)) to kp.private
    }

    private fun signEd25519(privateKey: PrivateKey, rawManifest: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(rawManifest)
            sign()
        }

    private fun buildSignedPackage(
        outDir: File,
        fileName: String,
        kind: PackageKind,
        version: Int,
        minAppVersion: Int,
        keyId: String,
        privateKey: PrivateKey,
    ): File {
        val commit40 = "a".repeat(40)
        val entries = ArrayList<Pair<String, ByteArray>>()
        if (kind == PackageKind.CONTENT) {
            val fixture = requireNotNull(javaClass.getResourceAsStream("/fixtures/valid-content-v1.zip"))
            ZipInputStream(fixture).use { zip ->
                generateSequence { zip.nextEntry }.filter { it.name.startsWith("data/") }.forEach { entry ->
                    entries.add(entry.name to zip.readBytes())
                }
            }
        } else {
            val mapJson = """
                {
                  "tile_schema": 1,
                  "camera_bounds": {"west": 34.0, "south": 12.0, "east": 60.0, "north": 32.0},
                  "data_extent": {"west": 33.0, "south": 11.0, "east": 61.0, "north": 33.0},
                  "min_zoom": 4.0,
                  "max_zoom": 14.0,
                  "sources": {
                    "base": {"file": "base.pmtiles", "layers": ["land", "coast"], "min_zoom": 0, "max_zoom": 10},
                    "relief": {
                      "file": "relief.pmtiles",
                      "encoding": "terrarium-value",
                      "tile_format": "png",
                      "tile_size": 256,
                      "min_zoom": 0,
                      "max_zoom": 12
                    }
                  },
                  "credits": [
                    {"id": "copernicus-worlddem-30", "source": "Copernicus", "attribution": "Attr", "liability": "Liab"},
                    {"id": "natural-earth", "source": "Natural Earth", "attribution": "Public domain."}
                  ],
                  "reconstructed_areas": []
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            val basePm = TestArchiveBuilders.buildMinimalPmTiles(
                tileCompression = 2,
                tileType = 1,
                maxZoom = 10,
                metadataBytes = TestArchiveBuilders.gzipBytes(
                    """{"tile_schema":1,"vector_layers":[{"id":"land"},{"id":"coast"}]}""".toByteArray(),
                ),
            )
            val reliefPm = TestArchiveBuilders.buildMinimalPmTiles(
                tileCompression = 1,
                tileType = 2,
                maxZoom = 12,
                metadataBytes = TestArchiveBuilders.gzipBytes(
                    """{"tile_schema":1,"encoding":"terrarium-value"}""".toByteArray(),
                ),
            )
            entries.add("data/base.pmtiles" to basePm)
            entries.add("data/map.json" to mapJson)
            entries.add("data/relief.pmtiles" to reliefPm)
        }

        val filesJson = entries.joinToString(",") { (path, bytes) ->
            """{"path":"$path","size":${bytes.size},"sha256":"${sha256Hex(bytes)}"}"""
        }
        val extraFields = if (kind == PackageKind.MAP) {
            """"tool_commit":"$commit40","image":"sha256:${"c".repeat(64)}","""
        } else {
            ""
        }
        val rawManifest = """
            {
              "kind":"${kind.wireName}",
              "version":$version,
              "min_app_version":$minAppVersion,
              "key_id":"$keyId",
              "source_commit":"$commit40",
              $extraFields
              "created":"2026-11-28T00:00:00Z",
              "files":[$filesJson]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val rawSig = signEd25519(privateKey, rawManifest)

        val zipSpecs = listOf(
            TestArchiveBuilders.RawZipSpecEntry("manifest.json".toByteArray(), rawManifest),
            TestArchiveBuilders.RawZipSpecEntry("manifest.sig".toByteArray(), rawSig),
        ) + entries.map { (path, bytes) ->
            TestArchiveBuilders.RawZipSpecEntry(
                nameBytes = path.toByteArray(StandardCharsets.US_ASCII),
                payloadBytes = bytes,
                method = if (kind == PackageKind.CONTENT) 8 else 0,
            )
        }
        return TestArchiveBuilders.writeTempBytes(outDir, fileName, TestArchiveBuilders.buildRawZip(zipSpecs))
    }

    private fun createStack(
        rootDir: File,
        ringProvider: () -> KeyRingState,
        availableBytesProvider: () -> Long = { 2L * 1024L * 1024L * 1024L },
        appVersion: Long = 10L,
        logger: RecordingLogger = RecordingLogger(),
    ): Triple<DefaultPackageStorage, DefaultPackageInstaller, RecordingLogger> {
        val storage = DefaultPackageStorage(rootDir, Dispatchers.IO, logger)
        val installer = DefaultPackageInstaller(
            contentRootDir = rootDir,
            storage = storage,
            keyRingSource = TrustedKeyRingSource { ringProvider() },
            verifier = IsolatedTinkEd25519Verifier(ringProvider),
            availableSpaceProvider = object : AvailableSpaceProvider {
                override fun availableBytes(directory: File): Long = availableBytesProvider()
            },
            appVersionCodeProvider = object : AppVersionCodeProvider {
                override suspend fun currentVersionCode(): Long = appVersion
            },
            logger = logger,
            ioDispatcher = Dispatchers.IO,
        )
        return Triple(storage, installer, logger)
    }

    @Test
    fun install_mapAndContent_enforcesMinAppVersionDowngradeAndSpaceGate() = runTest {
        val work = tempFolder.newFolder("basic")
        val contentRoot = File(work, "files/content")
        val (pub, priv) = generateEd25519Keypair("k1")
        var ring: KeyRingState = KeyRingState.Configured(listOf(pub))
        var avail = 2L * 1024L * 1024L * 1024L
        var appVer = 5L
        val (storage, installer, _) = createStack(contentRoot, { ring }, { avail }, appVer)

        assertEquals(InstalledPackageState.Loading, storage.observeInstalled(PackageKind.MAP).value)

        // Requires newer app version (min_app_version = 6 > 5)
        val tooNewZip = buildSignedPackage(work, "too-new.zip", PackageKind.MAP, 1, 6, "k1", priv)
        val updFail = assertIs<PackageResult.Failure>(installer.install(tooNewZip, PackageKind.MAP))
        assertEquals(PackageFailure.AppUpdateRequired(6, 5), updFail.reason)
        assertNull(storage.currentInstalled(PackageKind.MAP))

        // Insufficient space (includes 4 KiB block slack + 32 MiB margin)
        val mapV1Zip = buildSignedPackage(work, "map-v1.zip", PackageKind.MAP, 1, 1, "k1", priv)
        avail = 10_000L
        val spaceFail = assertIs<PackageResult.Failure>(installer.install(mapV1Zip, PackageKind.MAP))
        val insuf = assertIs<PackageFailure.InsufficientStorage>(spaceFail.reason)
        assertEquals(10_000L, insuf.availableBytes)
        assertTrue(insuf.requiredBytes > 32L * 1024L * 1024L)
        assertTrue(File(contentRoot, "staging").listFiles().orEmpty().isEmpty())

        // Install valid MAP v1 (must NOT retain package.zip)
        avail = 2L * 1024L * 1024L * 1024L
        val mapV1 = (installer.install(mapV1Zip) as PackageResult.Success).value
        assertEquals(1, mapV1.version)
        assertEquals(1L, mapV1.generation)
        assertNull(mapV1.retainedArchivePath)
        val meta = (storage.readActiveMapMetadata(mapV1) as PackageResult.Success).value
        assertEquals(ReliefEncoding.TERRARIUM_VALUE, meta.sources.relief.encoding)

        // Downgrade/same-version under same trusted key -> DowngradeOrSameVersion
        val downgrade = assertIs<PackageResult.Failure>(installer.install(mapV1Zip, PackageKind.MAP))
        assertEquals(PackageFailure.DowngradeOrSameVersion(1, 1), downgrade.reason)

        // Install CONTENT v1 (MUST retain package.zip)
        val contentV1Zip = buildSignedPackage(work, "content-v1.zip", PackageKind.CONTENT, 1, 1, "k1", priv)
        val contentV1 = (installer.install(contentV1Zip) as PackageResult.Success).value
        val retained = assertNotNull(contentV1.retainedArchivePath)
        assertTrue(File(retained).isFile)
        assertFalse(File(retained).readBytes().isEmpty())
    }

    @Test
    fun keyCompromiseFloorReset_allowsSameAndLowerVersionsAndBindsPublicKeySha() = runTest {
        val work = tempFolder.newFolder("compromise")
        val contentRoot = File(work, "files/content")
        val (pubComp, privComp) = generateEd25519Keypair("k1")
        val (pubCleanSameId, privCleanSameId) = generateEd25519Keypair("k1")
        val (pubCleanOther, privCleanOther) = generateEd25519Keypair("k2")

        var ring: KeyRingState = KeyRingState.Configured(listOf(pubComp))
        val (storage, installer, _) = createStack(contentRoot, { ring })

        // Compromised key installs version 999_999 -> v999999-g1
        val compZip = buildSignedPackage(work, "comp.zip", PackageKind.CONTENT, 999_999, 1, "k1", privComp)
        val vComp = (installer.install(compZip, PackageKind.CONTENT) as PackageResult.Success).value
        assertEquals(999_999, vComp.version)

        // Rotate keyring to retired/different key even reusing key_id = "k1": offline files STILL readable before swap!
        ring = KeyRingState.Configured(listOf(pubCleanSameId, pubCleanOther))
        val freshStorage = DefaultPackageStorage(contentRoot, Dispatchers.IO, RecordingLogger())
        val stillReadable = freshStorage.currentInstalled(PackageKind.CONTENT)
        assertNotNull(stillReadable)
        assertEquals(999_999, stillReadable.version)

        // Rule A resets floor because publicKeySha256 changed even though key_id stayed "k1"!
        val cleanSameVerZip = buildSignedPackage(work, "clean5.zip", PackageKind.CONTENT, 5, 1, "k1", privCleanSameId)
        val cleanRes = (installer.install(cleanSameVerZip, PackageKind.CONTENT) as PackageResult.Success).value
        assertEquals(5, cleanRes.version)
        assertEquals(2L, cleanRes.generation)
        assertFalse(File(vComp.rootDirPath).exists(), "Old compromised folder should be purged after switch")

        // Installing another clean v5 under "k2" while k1 IS still trusted must hit DowngradeOrSameVersion (Rule B)
        val cleanV5Other = buildSignedPackage(work, "clean5-k2.zip", PackageKind.CONTENT, 5, 1, "k2", privCleanOther)
        val blocked = assertIs<PackageResult.Failure>(installer.install(cleanV5Other, PackageKind.CONTENT))
        assertEquals(PackageFailure.DowngradeOrSameVersion(5, 5), blocked.reason)
    }

    @Test
    fun crashRecoveryAndConcurrentCleanupMutex_preserveActiveAndReclaimOrphans() = runTest {
        val work = tempFolder.newFolder("crash")
        val contentRoot = File(work, "files/content")
        val (pub, priv) = generateEd25519Keypair("k1")
        val ring = KeyRingState.Configured(listOf(pub))
        val (storage, installer, logger) = createStack(contentRoot, { ring })

        val zipV1 = buildSignedPackage(work, "c1.zip", PackageKind.CONTENT, 1, 1, "k1", priv)
        val installed = (installer.install(zipV1, PackageKind.CONTENT) as PackageResult.Success).value

        // Seed crash leftovers: staging leftover, post-Step-11 orphan v2-g5, active.json.tmp
        val staleStage = File(contentRoot, "staging/leftover").apply { mkdirs() }
        File(staleStage, "partial.bin").writeText("half")
        val kindDir = File(contentRoot, "content")
        val step11Orphan = File(kindDir, "v2-g5").apply { mkdirs() }
        File(step11Orphan, "manifest.json").writeText("{}")
        File(kindDir, ActivePointerStore.ACTIVE_TEMP_FILE_NAME).writeText("tmp")

        assertIs<PackageResult.Success<Unit>>(storage.cleanupOrphanedFiles())
        assertFalse(staleStage.exists())
        assertFalse(step11Orphan.exists())
        assertFalse(File(kindDir, ActivePointerStore.ACTIVE_TEMP_FILE_NAME).exists())
        assertTrue(File(installed.rootDirPath).isDirectory)

        // Seed higher generation orphan BEFORE next install -> nextGen must skip past g5 to g6!
        File(kindDir, "v9-g5").mkdirs()
        val zipV2 = buildSignedPackage(work, "c2.zip", PackageKind.CONTENT, 2, 1, "k1", priv)

        // Concurrent cleanup + install under shared Mutex must never corrupt or race
        val installJob = async { installer.install(zipV2, PackageKind.CONTENT) }
        val cleanupJob = async { storage.cleanupOrphanedFiles() }
        val installRes = installJob.await()
        val cleanupRes = cleanupJob.await()
        assertIs<PackageResult.Success<Unit>>(cleanupRes)
        val v2 = (installRes as PackageResult.Success).value
        assertEquals(6L, v2.generation)

        // Corrupt active.json on cold start -> deletes pointer, logs warning, returns NotInstalled
        File(kindDir, ActivePointerStore.ACTIVE_FILE_NAME).writeText("{corrupt")
        val restartLogger = RecordingLogger()
        val coldStorage = DefaultPackageStorage(contentRoot, Dispatchers.IO, restartLogger)
        assertNull(coldStorage.currentInstalled(PackageKind.CONTENT))
        assertEquals(InstalledPackageState.NotInstalled, coldStorage.observeInstalled(PackageKind.CONTENT).value)
        assertFalse(File(kindDir, ActivePointerStore.ACTIVE_FILE_NAME).exists())
        assertTrue(restartLogger.warnings.isNotEmpty())
        assertTrue(logger.warnings.isEmpty())
    }
}
