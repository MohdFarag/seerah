package app.seerah.content

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.seerah.model.ReliefEncoding
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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
    fun realAndroidFilesystem_fsyncDirAndR8VerificationAndAtomicSwitchSucceed() = runTest {
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

        // 3. Install Python-signed fixtures via real Tink NO_PREFIX + kotlinx.serialization + StatFs + ATOMIC_MOVE.
        val keysBytes = copyFixture("trusted-keys.json").readBytes()
        val ring = TrustedKeyRingLoader.loadFromBytes(keysBytes)
        assertIs<KeyRingState.Configured>(ring)

        val contentRoot = File(testRootDir, "content")
        val logger = AndroidPackageStorageLogger()
        val realVersionProvider = AndroidAppVersionCodeProvider(context, Dispatchers.IO)
        assertTrue(realVersionProvider.currentVersionCode() >= 0L)
        val storage = DefaultPackageStorage(contentRoot, Dispatchers.IO, logger)
        val installer = DefaultPackageInstaller(
            contentRootDir = contentRoot,
            storage = storage,
            keyRingSource = TrustedKeyRingSource { ring },
            verifier = IsolatedTinkEd25519Verifier { ring },
            availableSpaceProvider = StatFsAvailableSpaceProvider(),
            // Library androidTest APKs default to versionCode 0; clamp to 1 after calling the real provider.
            appVersionCodeProvider = object : AppVersionCodeProvider {
                override suspend fun currentVersionCode(): Long = maxOf(1L, realVersionProvider.currentVersionCode())
            },
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
        val contentInstall = installer.install(contentZip, PackageKind.CONTENT)
        assertIs<PackageResult.Success<InstalledPackageInfo>>(
            contentInstall,
            "CONTENT install failed: $contentInstall",
        )
        assertTrue(File(assertNotNull(contentInstall.value.retainedArchivePath)).isFile)

        // 4. Trial map fixture is rejected by Goal 9 guard in a clean root even under R8 obfuscation.
        val trialRoot = File(testRootDir, "trial-content")
        val trialStorage = DefaultPackageStorage(trialRoot, Dispatchers.IO, logger)
        val trialInstaller = DefaultPackageInstaller(
            contentRootDir = trialRoot,
            storage = trialStorage,
            keyRingSource = TrustedKeyRingSource { ring },
            verifier = IsolatedTinkEd25519Verifier { ring },
            availableSpaceProvider = StatFsAvailableSpaceProvider(),
            appVersionCodeProvider = object : AppVersionCodeProvider {
                override suspend fun currentVersionCode(): Long = maxOf(1L, realVersionProvider.currentVersionCode())
            },
            logger = logger,
            ioDispatcher = Dispatchers.IO,
        )
        val trialZip = copyFixture("trial-rejected-map-v1.zip")
        val rejected = assertIs<PackageResult.Failure>(trialInstaller.install(trialZip, PackageKind.MAP))
        val invalid = assertIs<PackageFailure.InvalidPackage>(rejected.reason)
        assertEquals(InvalidPackageCause.INVALID_PMTILES, invalid.cause)

        // 5. Cold reload preserves installed state offline + cleanup leaves active folders intact.
        val coldStorage = DefaultPackageStorage(contentRoot, Dispatchers.IO, logger)
        assertEquals(1, assertNotNull(coldStorage.currentInstalled(PackageKind.MAP)).version)
        assertEquals(1, assertNotNull(coldStorage.currentInstalled(PackageKind.CONTENT)).version)
        assertIs<PackageResult.Success<Unit>>(coldStorage.cleanupOrphanedFiles())
    }
}
