package app.seerah.content

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.seerah.content.db.AndroidContentDatabaseOpener
import app.seerah.model.EventDetail
import app.seerah.model.ReliefEncoding
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke suite exercising real Android filesystems (`fsyncDir`, `ATOMIC_MOVE`),
 * `StatFs`, `PackageInfoCompat`, and R8-shrunk Tink + `kotlinx.serialization` execution on device.
 */
@RunWith(AndroidJUnit4::class)
class ContentDeviceSmokeTest {
    private lateinit var context: Context
    private lateinit var testRootDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testRootDir = File(context.filesDir, "device-smoke-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        testRootDir.deleteRecursively()
    }

    private fun copyFixture(name: String): File {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing instrumentation fixture /fixtures/$name"
        }.use { it.readBytes() }
        return File(testRootDir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun realAndroidFilesystem_fsyncDirAndR8VerificationAndAtomicSwitchSucceed() = runBlocking<Unit> {
        // 1. Verify raw resource keyring placeholder compiles cleanly even under R8 shrinkResources.
        assertIs<KeyRingState.Unconfigured>(RawResourceKeyRingProvider(context).currentKeyRing())

        // 2. Verify real Android F2FS/FUSE directory fsync + atomic pointer write.
        val fsyncProbeDir = File(testRootDir, "fsync-probe").apply { mkdirs() }
        ActivePointerStore.writeAtomic(
            fsyncProbeDir,
            ActivePointer(
                schema = 1,
                version = 1,
                generation = 1L,
                keyId = "probe-key",
                publicKeySha256 = "0".repeat(64),
                activeDir = "v1-g1",
            ),
        )
        assertNotNull(ActivePointerStore.readValidated(fsyncProbeDir))

        // 3. Install Python-signed fixtures via real Tink NO_PREFIX + kotlinx.serialization + StatFs + ATOMIC_MOVE + Room 3.
        val keysBytes = copyFixture("trusted-keys.json").readBytes()
        val ring = TrustedKeyRingLoader.loadFromBytes(keysBytes)
        assertIs<KeyRingState.Configured>(ring)

        val contentRoot = File(testRootDir, "content")
        val logger = AndroidPackageStorageLogger()
        val realVersionProvider = AndroidAppVersionCodeProvider(context, Dispatchers.IO)
        assertTrue(realVersionProvider.currentVersionCode() >= 0L)
        val clampedVersionProvider = object : AppVersionCodeProvider {
            override suspend fun currentVersionCode(): Long = maxOf(1L, realVersionProvider.currentVersionCode())
        }
        val keyRingSource = TrustedKeyRingSource { ring }
        val verifier = IsolatedTinkEd25519Verifier { ring }
        val spaceProvider = StatFsAvailableSpaceProvider()
        val dbOpener = AndroidContentDatabaseOpener(context)
        val importer = RoomContentDatabaseImporter(dbOpener)
        val storage = DefaultPackageStorage(contentRoot, Dispatchers.IO, logger)
        val installer = DefaultPackageInstaller(
            contentRootDir = contentRoot,
            storage = storage,
            keyRingSource = keyRingSource,
            verifier = verifier,
            availableSpaceProvider = spaceProvider,
            // Library androidTest APKs default to versionCode 0; clamp to 1 after calling the real provider.
            appVersionCodeProvider = clampedVersionProvider,
            contentDatabaseImporter = importer,
            logger = logger,
            ioDispatcher = Dispatchers.IO,
        )

        val mapZip = copyFixture("valid-map-v1.zip")
        val mapInstall = installer.install(mapZip, PackageKind.MAP)
        assertIs<PackageResult.Success<InstalledPackageInfo>>(mapInstall, "MAP install failed: $mapInstall")
        val mapInfo = mapInstall.value
        assertNull(mapInfo.retainedArchivePath)
        val mapMetaRes = storage.readActiveMapMetadata(mapInfo)
        assertIs<PackageResult.Success<app.seerah.model.MapMetadata>>(
            mapMetaRes,
            "readActiveMapMetadata failed: $mapMetaRes",
        )
        assertEquals(ReliefEncoding.TERRARIUM_VALUE, mapMetaRes.value.sources.relief.encoding)

        val contentZip = copyFixture("valid-content-v1.zip")
        val importStartMs = System.currentTimeMillis()
        val contentInstall = installer.install(contentZip, PackageKind.CONTENT)
        val elapsedMs = System.currentTimeMillis() - importStartMs
        Log.i("ContentDeviceSmokeTest", "Room 3 content install + import took ${elapsedMs}ms")
        val contentInfo = assertIs<PackageResult.Success<InstalledPackageInfo>>(
            contentInstall,
            "CONTENT install failed: $contentInstall",
        ).value
        assertTrue(File(assertNotNull(contentInfo.retainedArchivePath)).isFile)
        val dbFile = File(contentInfo.rootDirPath, CONTENT_DB_FILE_NAME)
        assertTrue(dbFile.isFile, "Expected Room 3 content.db at ${dbFile.path}")
        assertFalse(File(contentInfo.dataDirPath).exists(), "CONTENT must not unpack data/")

        // 4. Query ContentRepository under R8 Full Mode, then verify automatic disposable rebuild.
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager1 = ContentDatabaseManager(
                storage = storage,
                keyRingSource = keyRingSource,
                verifier = verifier,
                availableSpaceProvider = spaceProvider,
                appVersionCodeProvider = clampedVersionProvider,
                dbOpener = dbOpener,
                importer = importer,
                logger = logger,
                ioDispatcher = Dispatchers.IO,
                contentScope = scope1,
                stopTimeoutMillis = 0L,
            )
            val repo1 = DefaultContentRepository(manager1, Dispatchers.IO, scope1)
            val ready = withTimeout(15_000L) {
                repo1.observeContentState().first { it is ContentState.Ready }
            } as ContentState.Ready
            assertEquals("synthetic-1", ready.corpus.summary.contentVersion)
            val detail = withTimeout(15_000L) {
                repo1.observeEventDetail("fixture-event").first { it !is ContentLookupResult.Loading }
            }
            assertIs<ContentLookupResult.Found<EventDetail>>(detail)
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        // 5. Delete disposable content.db and verify cold restart rebuilds from package.zip under R8.
        assertTrue(dbFile.delete())
        val coldStorage = DefaultPackageStorage(contentRoot, Dispatchers.IO, logger)
        assertEquals(1, assertNotNull(coldStorage.currentInstalled(PackageKind.MAP)).version)
        assertEquals(1, assertNotNull(coldStorage.currentInstalled(PackageKind.CONTENT)).version)
        assertIs<PackageResult.Success<Unit>>(coldStorage.cleanupOrphanedFiles())

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager2 = ContentDatabaseManager(
                storage = coldStorage,
                keyRingSource = keyRingSource,
                verifier = verifier,
                availableSpaceProvider = spaceProvider,
                appVersionCodeProvider = clampedVersionProvider,
                dbOpener = dbOpener,
                importer = importer,
                logger = logger,
                ioDispatcher = Dispatchers.IO,
                contentScope = scope2,
                stopTimeoutMillis = 0L,
            )
            val repo2 = DefaultContentRepository(manager2, Dispatchers.IO, scope2)
            val rebuiltReady = withTimeout(15_000L) {
                repo2.observeContentState().first { it is ContentState.Ready }
            } as ContentState.Ready
            assertEquals(1, rebuiltReady.corpus.summary.packageVersion)
            assertTrue(dbFile.isFile, "Expected rebuilt content.db after cold start")
            val rebuiltDetail = withTimeout(15_000L) {
                repo2.observeEventDetail("fixture-event").first { it !is ContentLookupResult.Loading }
            }
            assertIs<ContentLookupResult.Found<EventDetail>>(rebuiltDetail)
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }

        // 6. Trial map fixture is rejected by Goal 9 guard in a clean root even under R8 obfuscation.
        val trialRoot = File(testRootDir, "trial-content")
        val trialStorage = DefaultPackageStorage(trialRoot, Dispatchers.IO, logger)
        val trialInstaller = DefaultPackageInstaller(
            contentRootDir = trialRoot,
            storage = trialStorage,
            keyRingSource = keyRingSource,
            verifier = verifier,
            availableSpaceProvider = spaceProvider,
            appVersionCodeProvider = clampedVersionProvider,
            contentDatabaseImporter = importer,
            logger = logger,
            ioDispatcher = Dispatchers.IO,
        )
        val trialZip = copyFixture("trial-rejected-map-v1.zip")
        val rejected = assertIs<PackageResult.Failure>(trialInstaller.install(trialZip, PackageKind.MAP))
        val invalid = assertIs<PackageFailure.InvalidPackage>(rejected.reason)
        assertEquals(InvalidPackageCause.INVALID_PMTILES, invalid.cause)
    }
}
