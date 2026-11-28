package app.seerah.content

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.seerah.content.db.AndroidContentDatabaseOpener
import app.seerah.content.db.ContentDatabaseOpener
import app.seerah.model.EventDetail
import app.seerah.model.Story
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentRoomDatabaseTest {
    private lateinit var context: Context
    private lateinit var workDir: File
    private val scopesToCancel = ArrayList<CoroutineScope>()

    private class RecordingLogger : PackageStorageLogger {
        val warnings = ArrayList<String>()
        override fun warn(tag: String, message: String, throwable: Throwable?) {
            warnings.add("$tag: $message")
        }
    }

    private data class StackHarness(
        val contentRoot: File,
        val storage: DefaultPackageStorage,
        val installer: DefaultPackageInstaller,
        val manager: ContentDatabaseManager,
        val repository: DefaultContentRepository,
        val dbOpener: ContentDatabaseOpener,
        val scope: CoroutineScope,
        val logger: RecordingLogger,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workDir = File(context.filesDir, "room-db-test-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            for (scope in scopesToCancel) {
                scope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
        scopesToCancel.clear()
        workDir.deleteRecursively()
    }

    private fun createHarness(
        contentRoot: File = File(workDir, "content"),
        ringProvider: () -> KeyRingState,
        availableBytesProvider: () -> Long = { 2L * 1024L * 1024L * 1024L },
        appVersion: Long = 10L,
        stopTimeoutMillis: Long = 0L,
    ): StackHarness {
        val logger = RecordingLogger()
        val io = Dispatchers.IO
        val scope = CoroutineScope(SupervisorJob() + io).also { scopesToCancel.add(it) }
        val storage = DefaultPackageStorage(contentRoot, io, logger)
        val dbOpener = AndroidContentDatabaseOpener(context)
        val importer = RoomContentDatabaseImporter(dbOpener)
        val verifier = IsolatedTinkEd25519Verifier(ringProvider)
        val keyRingSource = TrustedKeyRingSource { ringProvider() }
        val spaceProvider = object : AvailableSpaceProvider {
            override fun availableBytes(directory: File): Long = availableBytesProvider()
        }
        val appVersionProvider = object : AppVersionCodeProvider {
            override suspend fun currentVersionCode(): Long = appVersion
        }
        val installer = DefaultPackageInstaller(
            contentRootDir = contentRoot,
            storage = storage,
            keyRingSource = keyRingSource,
            verifier = verifier,
            availableSpaceProvider = spaceProvider,
            appVersionCodeProvider = appVersionProvider,
            contentDatabaseImporter = importer,
            logger = logger,
            ioDispatcher = io,
        )
        val manager = ContentDatabaseManager(
            storage = storage,
            keyRingSource = keyRingSource,
            verifier = verifier,
            availableSpaceProvider = spaceProvider,
            appVersionCodeProvider = appVersionProvider,
            dbOpener = dbOpener,
            importer = importer,
            logger = logger,
            ioDispatcher = io,
            contentScope = scope,
            stopTimeoutMillis = stopTimeoutMillis,
        )
        val repository = DefaultContentRepository(manager, io, scope)
        return StackHarness(
            contentRoot = contentRoot,
            storage = storage,
            installer = installer,
            manager = manager,
            repository = repository,
            dbOpener = dbOpener,
            scope = scope,
            logger = logger,
        )
    }

    private fun loadFixture(name: String): File {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing fixture /fixtures/$name"
        }.use { it.readBytes() }
        return File(workDir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun coldStartHydrationAndGoldenContentFixture_installAndQueryCleanly() = runBlocking<Unit> {
        val keysRing = TrustedKeyRingLoader.loadFromBytes(loadFixture("trusted-keys.json").readBytes())
        assertIs<KeyRingState.Configured>(keysRing)
        val harness = createHarness(ringProvider = { keysRing })

        assertEquals(ContentState.Loading, harness.repository.observeContentState().value)
        val notInstalled = withTimeout(10_000L) {
            harness.repository.observeContentState().first { it !is ContentState.Loading }
        }
        assertEquals(ContentState.NotInstalled, notInstalled)

        val zipFile = loadFixture("valid-content-v1.zip")
        val installed = assertIs<PackageResult.Success<InstalledPackageInfo>>(
            harness.installer.install(zipFile, PackageKind.CONTENT),
        ).value
        assertTrue(File(installed.rootDirPath, CONTENT_DB_FILE_NAME).isFile)
        assertTrue(File(assertNotNull(installed.retainedArchivePath)).isFile)
        assertFalse(File(installed.dataDirPath).exists(), "No permanent data/ folder may exist for CONTENT.")

        val ready = withTimeout(10_000L) {
            harness.repository.observeContentState().first { it is ContentState.Ready }
        } as ContentState.Ready
        assertEquals("synthetic-1", ready.corpus.summary.contentVersion)
        assertEquals(listOf("fixture-event"), ready.corpus.events.map { it.id })

        val detail = withTimeout(10_000L) {
            harness.repository.observeEventDetail("fixture-event")
                .first { it !is ContentLookupResult.Loading }
        }
        val found = assertIs<ContentLookupResult.Found<EventDetail>>(detail).value
        assertEquals("fixture-event", found.event.id)
        assertEquals(listOf("fixture-text"), found.texts.map { it.id })

        val missingEvent = withTimeout(10_000L) {
            harness.repository.observeEventDetail("no-such-event")
                .first { it !is ContentLookupResult.Loading }
        }
        assertEquals(ContentLookupResult.NotFound, missingEvent)

        val missingStory = withTimeout(10_000L) {
            harness.repository.observeStory("no-such-story")
                .first { it !is ContentLookupResult.Loading }
        }
        assertEquals(ContentLookupResult.NotFound, missingStory)
    }

    @Test
    fun richMasirahCorpus_resolvesCorpusLazyEventDetailExcerptFullTextAndStory() = runBlocking<Unit> {
        val (pub, priv) = SyntheticContentFixtures.generateEd25519Keypair("k1")
        val ring = KeyRingState.Configured(listOf(pub))
        val harness = createHarness(ringProvider = { ring })

        val zipFile = SyntheticContentFixtures.buildSignedContentPackage(workDir, "masirah-v1.zip", 1, 1, "k1", priv)
        assertIs<PackageResult.Success<InstalledPackageInfo>>(
            harness.installer.install(zipFile, PackageKind.CONTENT),
        )

        val ready = withTimeout(10_000L) {
            harness.repository.observeContentState().first { it is ContentState.Ready }
        } as ContentState.Ready
        val corpus = ready.corpus
        assertEquals(2, corpus.phases.size)
        assertEquals(2, corpus.sources.size)
        assertEquals(4, corpus.places.size)
        assertEquals(1, corpus.areas.size)
        assertEquals(1, corpus.routes.size)
        assertEquals(2, corpus.events.size)
        assertEquals(1, corpus.storySummaries.size)
        assertEquals(1, corpus.storySummaries.single().chapterCount)

        val childDetailRes = withTimeout(10_000L) {
            harness.repository.observeEventDetail("event-child")
                .first { it !is ContentLookupResult.Loading }
        }
        val childDetail = assertIs<ContentLookupResult.Found<EventDetail>>(childDetailRes).value
        assertEquals("event-root", childDetail.parentEvent?.id)
        assertEquals("route-east", childDetail.route?.id)
        // Verifies both direct excerpt text AND its full target text resolved via excerpt_of!
        assertEquals(setOf("text-excerpt", "text-full"), childDetail.texts.map { it.id }.toSet())
        assertEquals(listOf("event-root"), childDetail.relatedEvents.map { it.second.id })
        assertEquals(setOf("source-a", "source-b"), childDetail.sources.keys)

        val rootDetailRes = withTimeout(10_000L) {
            harness.repository.observeEventDetail("event-root")
                .first { it !is ContentLookupResult.Loading }
        }
        val rootDetail = assertIs<ContentLookupResult.Found<EventDetail>>(rootDetailRes).value
        assertEquals(listOf("event-child"), rootDetail.childEvents.map { it.id })

        val storyRes = withTimeout(10_000L) {
            harness.repository.observeStory("story-main")
                .first { it !is ContentLookupResult.Loading }
        }
        val story = assertIs<ContentLookupResult.Found<Story>>(storyRes).value
        assertEquals("Synthetic Story", story.title)
        assertEquals(2, story.chapters.single().scenes.single().steps.size)
    }

    @Test
    fun hotSwapWhileActiveLeaseHeld_preservesOldDirUntilLeaseRelease() = runBlocking<Unit> {
        val (pub, priv) = SyntheticContentFixtures.generateEd25519Keypair("k1")
        val ring = KeyRingState.Configured(listOf(pub))
        val harness = createHarness(ringProvider = { ring }, stopTimeoutMillis = 5_000L)

        val collectorJob = launch { harness.repository.observeContentState().collect {} }
        try {
            val v1Zip = SyntheticContentFixtures.buildSignedContentPackage(workDir, "v1.zip", 1, 1, "k1", priv)
            val v1Info = (harness.installer.install(v1Zip, PackageKind.CONTENT) as PackageResult.Success).value
            val ready1 = withTimeout(10_000L) {
                harness.manager.activeState.first { it is ActiveDbState.Ready }
            } as ActiveDbState.Ready
            assertEquals(1, ready1.corpus.summary.packageVersion)

            // Hold an explicit lease on v1 handle across the upgrade to v2!
            assertTrue(ready1.handle.tryAcquireLease())

            val v2Zip = SyntheticContentFixtures.buildSignedContentPackage(workDir, "v2.zip", 2, 1, "k1", priv)
            val v2Info = (harness.installer.install(v2Zip, PackageKind.CONTENT) as PackageResult.Success).value
            assertEquals(2, v2Info.version)

            val ready2 = withTimeout(10_000L) {
                harness.repository.observeContentState().first {
                    it is ContentState.Ready && it.corpus.summary.packageVersion == 2
                }
            } as ContentState.Ready
            assertEquals("1.0.2-synthetic", ready2.corpus.summary.contentVersion)

            // Give upstream coroutine time to reach retireAndAwaitZeroLeases; v1 directory must still stay on disk!
            delay(150L)
            assertTrue(
                File(v1Info.rootDirPath).isDirectory,
                "Old generation folder must remain pinned while lease is active",
            )

            ready1.handle.releaseLease()
            withTimeout(10_000L) {
                while (File(v1Info.rootDirPath).exists()) {
                    delay(20L)
                }
            }
            assertTrue(File(v2Info.rootDirPath).isDirectory)
        } finally {
            collectorJob.cancelAndJoin()
        }
    }

    @Test
    fun disposableRebuild_recoversMissingCorruptMetaMismatchedAndRuntimeBrokenDb() = runBlocking<Unit> {
        val (pub, priv) = SyntheticContentFixtures.generateEd25519Keypair("k1")
        val ring = KeyRingState.Configured(listOf(pub))
        val root = File(workDir, "rebuild-content")

        val h1 = createHarness(contentRoot = root, ringProvider = { ring })
        val v1Zip = SyntheticContentFixtures.buildSignedContentPackage(workDir, "r1.zip", 1, 1, "k1", priv)
        val info = (h1.installer.install(v1Zip, PackageKind.CONTENT) as PackageResult.Success).value
        h1.scope.coroutineContext[Job]?.cancelAndJoin()

        val dbFile = File(info.rootDirPath, CONTENT_DB_FILE_NAME)

        // Case A: missing content.db
        assertTrue(dbFile.delete())
        val h2 = createHarness(contentRoot = root, ringProvider = { ring })
        val readyA = withTimeout(10_000L) {
            h2.repository.observeContentState().first { it is ContentState.Ready }
        } as ContentState.Ready
        assertEquals(1, readyA.corpus.summary.packageVersion)
        assertTrue(dbFile.isFile)
        h2.scope.coroutineContext[Job]?.cancelAndJoin()

        // Case B: corrupted content.db bytes
        dbFile.writeBytes("not-a-sqlite-file-at-all".toByteArray())
        val h3 = createHarness(contentRoot = root, ringProvider = { ring })
        val readyB = withTimeout(10_000L) {
            h3.repository.observeContentState().first { it is ContentState.Ready }
        } as ContentState.Ready
        assertEquals(1, readyB.corpus.summary.packageVersion)
        h3.scope.coroutineContext[Job]?.cancelAndJoin()

        // Case C: meta table version mismatch
        AndroidSQLiteDriver().open(dbFile.absolutePath).use { conn ->
            conn.prepare("UPDATE meta SET package_version = 999 WHERE id = 1").use { it.step() }
        }
        val h4 = createHarness(contentRoot = root, ringProvider = { ring })
        val readyC = withTimeout(10_000L) {
            h4.repository.observeContentState().first { it is ContentState.Ready }
        } as ContentState.Ready
        assertEquals(1, readyC.corpus.summary.packageVersion)

        // Case D: runtime read failure after Ready triggers automatic repair!
        AndroidSQLiteDriver().open(dbFile.absolutePath).use { conn ->
            conn.prepare("DROP TABLE routes").use { it.step() }
            conn.prepare("DROP TABLE texts").use { it.step() }
        }
        val repairedDetail = withTimeout(10_000L) {
            h4.repository.observeEventDetail("event-child")
                .first { it is ContentLookupResult.Found }
        }
        assertIs<ContentLookupResult.Found<EventDetail>>(repairedDetail)
    }

    @Test
    fun retiredKeyOfflineRebuild_succeedsWithoutOldKeyInKeyring() = runBlocking<Unit> {
        val (pub1, priv1) = SyntheticContentFixtures.generateEd25519Keypair("k1")
        val (pub2, _) = SyntheticContentFixtures.generateEd25519Keypair("k2")
        var ring: KeyRingState = KeyRingState.Configured(listOf(pub1))
        val root = File(workDir, "retired-key-content")

        val h1 = createHarness(contentRoot = root, ringProvider = { ring })
        val v1Zip = SyntheticContentFixtures.buildSignedContentPackage(workDir, "k1-v1.zip", 1, 1, "k1", priv1)
        val info = (h1.installer.install(v1Zip, PackageKind.CONTENT) as PackageResult.Success).value
        h1.scope.coroutineContext[Job]?.cancelAndJoin()

        // Retire k1 from keyring (Rule A) AND wipe disposable content.db.
        ring = KeyRingState.Configured(listOf(pub2))
        assertTrue(File(info.rootDirPath, CONTENT_DB_FILE_NAME).delete())

        val h2 = createHarness(contentRoot = root, ringProvider = { ring })
        val ready = withTimeout(10_000L) {
            h2.repository.observeContentState().first { it is ContentState.Ready }
        } as ContentState.Ready
        assertEquals("k1", info.keyId)
        assertEquals(1, ready.corpus.summary.packageVersion)
        val detail = withTimeout(10_000L) {
            h2.repository.observeEventDetail("event-root").first { it is ContentLookupResult.Found }
        }
        assertIs<ContentLookupResult.Found<EventDetail>>(detail)
    }

    @Test
    fun corruptPackageZipOnRebuild_quarantinesActivePointerAndAllowsCleanReinstall() = runBlocking<Unit> {
        val (pub, priv) = SyntheticContentFixtures.generateEd25519Keypair("k1")
        val ring = KeyRingState.Configured(listOf(pub))
        val root = File(workDir, "quarantine-content")

        val h1 = createHarness(contentRoot = root, ringProvider = { ring })
        val v1Zip = SyntheticContentFixtures.buildSignedContentPackage(workDir, "clean-v1.zip", 1, 1, "k1", priv)
        val info = (h1.installer.install(v1Zip, PackageKind.CONTENT) as PackageResult.Success).value
        h1.scope.coroutineContext[Job]?.cancelAndJoin()

        // Wipe disposable content.db and corrupt retained package.zip.
        assertTrue(File(info.rootDirPath, CONTENT_DB_FILE_NAME).delete())
        File(assertNotNull(info.retainedArchivePath)).writeBytes("corrupted-zip".toByteArray())

        val h2 = createHarness(contentRoot = root, ringProvider = { ring })
        val errState = withTimeout(10_000L) {
            h2.repository.observeContentState().first { it is ContentState.Error }
        } as ContentState.Error
        val invalid = assertIs<PackageFailure.InvalidPackage>(errState.failure)
        assertEquals(InvalidPackageCause.INVALID_CONTAINER, invalid.cause)
        assertFalse(File(root, "content/${ActivePointerStore.ACTIVE_FILE_NAME}").exists())

        // Re-downloading and installing version 1 cleanly MUST NOT hit DowngradeOrSameVersion!
        val reinstalled = assertIs<PackageResult.Success<InstalledPackageInfo>>(
            h2.installer.install(v1Zip, PackageKind.CONTENT),
        ).value
        assertEquals(1, reinstalled.version)
        val recovered = withTimeout(10_000L) {
            h2.repository.observeContentState().first { it is ContentState.Ready }
        } as ContentState.Ready
        assertEquals(1, recovered.corpus.summary.packageVersion)
    }

    @Test
    fun insufficientStorageAndSqliteFull_mapToFailureWithoutQuarantiningActiveJson() {
        runBlocking {
            val (pub, priv) = SyntheticContentFixtures.generateEd25519Keypair("k1")
            val ring = KeyRingState.Configured(listOf(pub))
            val root = File(workDir, "low-space-content")

            var available = 2L * 1024L * 1024L * 1024L
            val h1 = createHarness(
                contentRoot = root,
                ringProvider = { ring },
                availableBytesProvider = { available },
            )
            val v1Zip = SyntheticContentFixtures.buildSignedContentPackage(workDir, "space-v1.zip", 1, 1, "k1", priv)
            val info = (h1.installer.install(v1Zip, PackageKind.CONTENT) as PackageResult.Success).value
            h1.scope.coroutineContext[Job]?.cancelAndJoin()

            assertTrue(File(info.rootDirPath, CONTENT_DB_FILE_NAME).delete())
            available = 4_096L

            val h2 = createHarness(
                contentRoot = root,
                ringProvider = { ring },
                availableBytesProvider = { available },
            )
            val errState = withTimeout(10_000L) {
                h2.repository.observeContentState().first { it is ContentState.Error }
            } as ContentState.Error
            assertIs<PackageFailure.InsufficientStorage>(errState.failure)
            assertTrue(
                File(root, "content/${ActivePointerStore.ACTIVE_FILE_NAME}").isFile,
                "Transient InsufficientStorage must leave active.json intact",
            )
        }

        assertTrue(isSqliteFullError(SQLiteFullException("disk full")))
        assertTrue(isSqliteFullError(SQLiteException("Error code: 13 database or disk is full")))
    }
}
