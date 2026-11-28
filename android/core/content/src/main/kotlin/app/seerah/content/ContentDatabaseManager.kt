package app.seerah.content

import androidx.sqlite.SQLiteException
import app.seerah.content.db.ContentDatabaseOpener
import app.seerah.content.db.SeerahContentDatabase
import app.seerah.content.db.toModel
import app.seerah.content.di.ContentScope
import app.seerah.content.di.IoDispatcher
import app.seerah.model.ExplorationCorpus
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val MANAGER_LOG_TAG = "ContentDbManager"
private const val REBUILD_BLOCK_SIZE = 4_096L
private const val REBUILD_BLOCK_MASK = 4_095L
private const val REBUILD_BLOCK_SHIFT = 12
private const val MIN_REBUILD_SPACE_BYTES = 65_536L
private const val REBUILD_DB_MULTIPLIER = 3L
private const val REBUILD_SAFETY_MARGIN_BYTES = 32L * 1_024L * 1_024L

internal class ActiveDbHandle(val info: InstalledPackageInfo, val dirName: String, val db: SeerahContentDatabase) {
    private val lock = Any()
    private var activeLeases = 0
    private var retired = false
    private val drainedDeferred = CompletableDeferred<Unit>()

    fun tryAcquireLease(): Boolean = synchronized(lock) {
        if (retired) return@synchronized false
        activeLeases += 1
        true
    }

    fun releaseLease() {
        val completeNow = synchronized(lock) {
            activeLeases -= 1
            retired && activeLeases == 0
        }
        if (completeNow) {
            drainedDeferred.complete(Unit)
        }
    }

    suspend inline fun <T> withLease(block: (SeerahContentDatabase) -> T): T? {
        if (!tryAcquireLease()) return null
        return try {
            block(db)
        } finally {
            releaseLease()
        }
    }

    suspend fun retireAndAwaitZeroLeases() {
        val alreadyDrained = synchronized(lock) {
            retired = true
            activeLeases == 0
        }
        if (alreadyDrained) {
            drainedDeferred.complete(Unit)
        }
        drainedDeferred.await()
    }
}

internal sealed interface ActiveDbState {
    data object Loading : ActiveDbState
    data object NotInstalled : ActiveDbState
    data class Ready(val handle: ActiveDbHandle, val corpus: ExplorationCorpus) : ActiveDbState
    data class Error(val failure: PackageFailure) : ActiveDbState
}

/**
 * Coordinates generation-pinned [SeerahContentDatabase] opening, lease-safe hot-swap closing, and
 * single-attempt disposable rebuilds from retained `package.zip` archives.
 */
@Singleton
internal class ContentDatabaseManager internal constructor(
    private val storage: DefaultPackageStorage,
    private val keyRingSource: TrustedKeyRingSource,
    private val verifier: Ed25519Verifier,
    private val availableSpaceProvider: AvailableSpaceProvider,
    private val appVersionCodeProvider: AppVersionCodeProvider,
    private val dbOpener: ContentDatabaseOpener,
    private val importer: ContentDatabaseImporter,
    private val logger: PackageStorageLogger,
    private val ioDispatcher: CoroutineDispatcher,
    private val contentScope: CoroutineScope,
    private val stopTimeoutMillis: Long,
) {
    @Inject
    constructor(
        storage: DefaultPackageStorage,
        keyRingSource: TrustedKeyRingSource,
        verifier: Ed25519Verifier,
        availableSpaceProvider: AvailableSpaceProvider,
        appVersionCodeProvider: AppVersionCodeProvider,
        dbOpener: ContentDatabaseOpener,
        importer: ContentDatabaseImporter,
        logger: PackageStorageLogger,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @ContentScope contentScope: CoroutineScope,
    ) : this(
        storage = storage,
        keyRingSource = keyRingSource,
        verifier = verifier,
        availableSpaceProvider = availableSpaceProvider,
        appVersionCodeProvider = appVersionCodeProvider,
        dbOpener = dbOpener,
        importer = importer,
        logger = logger,
        ioDispatcher = ioDispatcher,
        contentScope = contentScope,
        stopTimeoutMillis = STOP_TIMEOUT_MILLIS,
    )

    private sealed interface UpstreamSignal {
        data class StorageChanged(val state: InstalledPackageState) : UpstreamSignal
        data class RepairRequested(val info: InstalledPackageInfo) : UpstreamSignal
    }

    private sealed interface RebuildResult {
        data object Rebuilt : RebuildResult
        data object Superseded : RebuildResult
        data class Failed(val failure: PackageFailure, val quarantined: Boolean) : RebuildResult
    }

    private sealed interface OpenLoadResult {
        data class Loaded(val handle: ActiveDbHandle, val corpus: ExplorationCorpus) : OpenLoadResult
        data class CorruptOrMismatched(val detail: String, val error: Throwable? = null) : OpenLoadResult
        data class UnrecoverableFailure(val failure: PackageFailure) : OpenLoadResult
    }

    private val repairRequests = MutableSharedFlow<InstalledPackageInfo>(extraBufferCapacity = 1)

    val activeState: StateFlow<ActiveDbState> = buildUpstreamFlow()
        .stateIn(
            scope = contentScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = stopTimeoutMillis,
                replayExpirationMillis = 0L,
            ),
            initialValue = ActiveDbState.Loading,
        )

    fun requestRepair(failedHandle: ActiveDbHandle) {
        repairRequests.tryEmit(failedHandle.info)
    }

    private fun buildUpstreamFlow(): Flow<ActiveDbState> = flow {
        var currentHandle: ActiveDbHandle? = null
        var quarantinedError: PackageFailure? = null
        val rebuildAttemptedGens = HashSet<Pair<Int, Long>>()

        try {
            // Ensure cold-start hydration completes off the main thread upon subscription.
            storage.currentInstalled(PackageKind.CONTENT)

            val signals = merge(
                storage.observeInstalled(PackageKind.CONTENT).map { UpstreamSignal.StorageChanged(it) },
                repairRequests.map { UpstreamSignal.RepairRequested(it) },
            ).conflate()

            signals.collect { signal ->
                when (signal) {
                    is UpstreamSignal.StorageChanged -> {
                        when (val pkgState = signal.state) {
                            InstalledPackageState.Loading -> {
                                if (currentHandle == null && quarantinedError == null) {
                                    emit(ActiveDbState.Loading)
                                }
                            }

                            InstalledPackageState.NotInstalled -> {
                                val err = quarantinedError
                                if (err != null) {
                                    emit(ActiveDbState.Error(err))
                                } else {
                                    emit(ActiveDbState.NotInstalled)
                                }
                                retireCloseAndUnpin(currentHandle)?.let { currentHandle = null }
                            }

                            is InstalledPackageState.Installed -> {
                                val info = pkgState.info
                                val current = currentHandle
                                if (current != null &&
                                    current.info.version == info.version &&
                                    current.info.generation == info.generation
                                ) {
                                    return@collect
                                }
                                quarantinedError = null
                                val activation = activateGeneration(
                                    info = info,
                                    rebuildAttemptedGens = rebuildAttemptedGens,
                                )
                                when (activation) {
                                    is ActivationOutcome.Activated -> {
                                        val previous = currentHandle
                                        currentHandle = activation.handle
                                        emit(ActiveDbState.Ready(activation.handle, activation.corpus))
                                        retireCloseAndUnpin(previous)
                                    }

                                    ActivationOutcome.Superseded -> Unit

                                    is ActivationOutcome.Failed -> {
                                        if (activation.quarantined) quarantinedError = activation.failure
                                        val previous = currentHandle
                                        currentHandle = null
                                        emit(ActiveDbState.Error(activation.failure))
                                        retireCloseAndUnpin(previous)
                                    }
                                }
                            }
                        }
                    }

                    is UpstreamSignal.RepairRequested -> {
                        val active = currentHandle ?: return@collect
                        if (active.info.version != signal.info.version ||
                            active.info.generation != signal.info.generation
                        ) {
                            return@collect
                        }
                        emit(ActiveDbState.Loading)
                        retireCloseAndUnpin(active)
                        currentHandle = null

                        val activation = activateGeneration(
                            info = signal.info,
                            rebuildAttemptedGens = rebuildAttemptedGens,
                        )
                        when (activation) {
                            is ActivationOutcome.Activated -> {
                                currentHandle = activation.handle
                                emit(ActiveDbState.Ready(activation.handle, activation.corpus))
                            }

                            ActivationOutcome.Superseded -> Unit

                            is ActivationOutcome.Failed -> {
                                if (activation.quarantined) quarantinedError = activation.failure
                                emit(ActiveDbState.Error(activation.failure))
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                retireCloseAndUnpin(currentHandle)
            }
        }
    }

    private sealed interface ActivationOutcome {
        data class Activated(val handle: ActiveDbHandle, val corpus: ExplorationCorpus) : ActivationOutcome
        data object Superseded : ActivationOutcome
        data class Failed(val failure: PackageFailure, val quarantined: Boolean) : ActivationOutcome
    }

    private suspend fun activateGeneration(
        info: InstalledPackageInfo,
        rebuildAttemptedGens: MutableSet<Pair<Int, Long>>,
    ): ActivationOutcome = withContext(ioDispatcher) {
        val dirName = storage.withStorageLock {
            storage.pinActiveDirLocked(PackageKind.CONTENT, info.version, info.generation)
        } ?: return@withContext ActivationOutcome.Superseded

        var keepPinned = false
        try {
            val genKey = info.version to info.generation
            val initial = openAndLoadCorpus(info, dirName)
            val resolved = when (initial) {
                is OpenLoadResult.Loaded -> initial

                is OpenLoadResult.UnrecoverableFailure -> {
                    return@withContext ActivationOutcome.Failed(initial.failure, quarantined = false)
                }

                is OpenLoadResult.CorruptOrMismatched -> {
                    logger.warn(MANAGER_LOG_TAG, initial.detail, initial.error)
                    if (!rebuildAttemptedGens.add(genKey)) {
                        return@withContext ActivationOutcome.Failed(
                            PackageFailure.IoFailure("Content database repair limit exceeded for v${info.version}."),
                            quarantined = false,
                        )
                    }
                    when (val rebuildRes = rebuildContentDbOnce(info)) {
                        RebuildResult.Superseded -> return@withContext ActivationOutcome.Superseded

                        is RebuildResult.Failed -> {
                            return@withContext ActivationOutcome.Failed(
                                rebuildRes.failure,
                                quarantined = rebuildRes.quarantined,
                            )
                        }

                        RebuildResult.Rebuilt -> {
                            when (val reopened = openAndLoadCorpus(info, dirName)) {
                                is OpenLoadResult.Loaded -> reopened

                                is OpenLoadResult.UnrecoverableFailure -> {
                                    return@withContext ActivationOutcome.Failed(reopened.failure, quarantined = false)
                                }

                                is OpenLoadResult.CorruptOrMismatched -> {
                                    return@withContext ActivationOutcome.Failed(
                                        PackageFailure.IoFailure(reopened.detail),
                                        quarantined = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            keepPinned = true
            ActivationOutcome.Activated(resolved.handle, resolved.corpus)
        } finally {
            if (!keepPinned) {
                withContext(NonCancellable) {
                    storage.withStorageLock {
                        storage.unpinVersionDirLocked(PackageKind.CONTENT, dirName)
                    }
                }
            }
        }
    }

    private suspend fun retireCloseAndUnpin(handle: ActiveDbHandle?): Unit? {
        if (handle == null) return null
        handle.retireAndAwaitZeroLeases()
        runCatching { handle.db.close() }
        storage.withStorageLock {
            storage.unpinVersionDirLocked(PackageKind.CONTENT, handle.dirName)
        }
        storage.cleanupOrphanedFiles()
        return Unit
    }

    private suspend fun openAndLoadCorpus(info: InstalledPackageInfo, dirName: String): OpenLoadResult {
        val dbFile = File(info.rootDirPath, CONTENT_DB_FILE_NAME)
        if (!dbFile.isFile) {
            return OpenLoadResult.CorruptOrMismatched("Content DB missing at ${dbFile.path}; rebuilding.")
        }
        val db = try {
            dbOpener.open(dbFile)
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: SQLiteException) {
            if (isSqliteFullError(exc)) {
                return OpenLoadResult.UnrecoverableFailure(PackageFailure.InsufficientStorage(0L, 0L))
            }
            return OpenLoadResult.CorruptOrMismatched("Failed opening content DB: ${exc.message}", exc)
        } catch (exc: IllegalStateException) {
            return OpenLoadResult.CorruptOrMismatched("Content DB state error on open: ${exc.message}", exc)
        }

        return try {
            val meta = db.metaDao().getMeta()
            if (meta == null ||
                meta.contentSchema != SUPPORTED_CONTENT_SCHEMA ||
                meta.packageVersion != info.version ||
                meta.generation != info.generation
            ) {
                runCatching { db.close() }
                return OpenLoadResult.CorruptOrMismatched(
                    "Content DB meta mismatch (expected v${info.version}-g${info.generation}, got $meta).",
                )
            }
            val summary = meta.toModel()
            val phases = db.phaseDao().getAll().map { it.toModel() }
            val sources = db.sourceDao().getAll().map { it.toModel() }.associateBy { it.id }
            val places = db.placeDao().getAll().map { it.toModel() }
            val areas = db.areaDao().getAll().map { it.toModel() }
            val routes = db.routeDao().getAll().map { it.toModel() }
            val events = db.eventDao().getAll().map { it.toModel() }
            val storySummaries = db.storyDao().getSummaries().map { it.toModel() }
            val corpus = ExplorationCorpus(
                summary = summary,
                phases = phases,
                sources = sources,
                places = places,
                areas = areas,
                routes = routes,
                events = events,
                storySummaries = storySummaries,
            )
            OpenLoadResult.Loaded(ActiveDbHandle(info, dirName, db), corpus)
        } catch (exc: CancellationException) {
            runCatching { db.close() }
            throw exc
        } catch (exc: SQLiteException) {
            runCatching { db.close() }
            if (isSqliteFullError(exc)) {
                OpenLoadResult.UnrecoverableFailure(PackageFailure.InsufficientStorage(0L, 0L))
            } else {
                OpenLoadResult.CorruptOrMismatched("Content DB SQLite read error: ${exc.message}", exc)
            }
        } catch (exc: IllegalStateException) {
            runCatching { db.close() }
            OpenLoadResult.CorruptOrMismatched("Room schema/identity mismatch on read: ${exc.message}", exc)
        } catch (exc: IllegalArgumentException) {
            runCatching { db.close() }
            OpenLoadResult.CorruptOrMismatched("Content DB row decode error: ${exc.message}", exc)
        }
    }

    private suspend fun rebuildContentDbOnce(info: InstalledPackageInfo): RebuildResult = storage.withStorageLock {
        val active = storage.activePointerLocked(PackageKind.CONTENT)
        if (active == null || active.version != info.version || active.generation != info.generation) {
            return@withStorageLock RebuildResult.Superseded
        }

        val versionDir = File(info.rootDirPath)
        val zipFile = File(versionDir, "package.zip")
        val retainedManifest = File(versionDir, StrictZipReader.MANIFEST_ENTRY_NAME)
        val retainedSig = File(versionDir, StrictZipReader.SIGNATURE_ENTRY_NAME)
        if (!zipFile.isFile) {
            val failure = PackageFailure.InvalidPackage(
                InvalidPackageCause.INVALID_CONTAINER,
                "Retained package.zip missing at ${zipFile.path}.",
            )
            storage.quarantineActiveLocked(PackageKind.CONTENT, failure.detail)
            return@withStorageLock RebuildResult.Failed(failure, quarantined = true)
        }

        try {
            FileChannel.open(zipFile.toPath(), StandardOpenOption.READ).use { channel ->
                val containerRes = StrictZipReader.inspectContainer(channel, requireAllStored = false)
                val container = when (containerRes) {
                    is PackageResult.Success -> containerRes.value

                    is PackageResult.Failure -> {
                        return@withStorageLock quarantineIfInvalidPackage(containerRes.reason)
                    }
                }

                val sigRes = StrictZipReader.readStoredEntryBytes(
                    channel = channel,
                    entry = container.entries[1],
                    minSize = RAW_ED25519_SIGNATURE_BYTES,
                    maxSize = RAW_ED25519_SIGNATURE_BYTES,
                    entryLabel = StrictZipReader.SIGNATURE_ENTRY_NAME,
                )
                val rawSig = when (sigRes) {
                    is PackageResult.Success -> sigRes.value
                    is PackageResult.Failure -> return@withStorageLock quarantineIfInvalidPackage(sigRes.reason)
                }

                val manifestRes = StrictZipReader.readStoredEntryBytes(
                    channel = channel,
                    entry = container.entries[0],
                    minSize = 1,
                    maxSize = FOUR_MIB_BYTES,
                    entryLabel = StrictZipReader.MANIFEST_ENTRY_NAME,
                )
                val rawManifestBytes = when (manifestRes) {
                    is PackageResult.Success -> manifestRes.value

                    is PackageResult.Failure -> {
                        return@withStorageLock quarantineIfInvalidPackage(manifestRes.reason)
                    }
                }

                val verifyFail = verifyRetainedCredentialsLocked(
                    info = info,
                    rawManifestBytes = rawManifestBytes,
                    rawSig = rawSig,
                    retainedManifest = retainedManifest,
                    retainedSig = retainedSig,
                )
                if (verifyFail != null) {
                    return@withStorageLock quarantineIfInvalidPackage(verifyFail)
                }

                val manifestParsed = PackageManifestParser.parseAndValidate(
                    rawManifestBytes = rawManifestBytes,
                    verifiedKeyId = info.keyId,
                    expectedKind = PackageKind.CONTENT,
                )
                val manifest = when (manifestParsed) {
                    is PackageResult.Success -> manifestParsed.value

                    is PackageResult.Failure -> {
                        return@withStorageLock quarantineIfInvalidPackage(manifestParsed.reason)
                    }
                }
                if (manifest.version != info.version) {
                    return@withStorageLock quarantineIfInvalidPackage(
                        PackageFailure.InvalidPackage(
                            InvalidPackageCause.INVALID_MANIFEST,
                            "Retained manifest version ${manifest.version} disagrees with active v${info.version}.",
                        ),
                    )
                }

                val appVersion = appVersionCodeProvider.currentVersionCode()
                if (appVersion < manifest.minAppVersion.toLong()) {
                    return@withStorageLock RebuildResult.Failed(
                        PackageFailure.AppUpdateRequired(
                            minAppVersion = manifest.minAppVersion,
                            currentAppVersion = appVersion.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                        ),
                        quarantined = false,
                    )
                }

                val targetDbFile = File(versionDir, CONTENT_DB_FILE_NAME)
                val totalJsonBytes = manifest.files.sumOf { it.sizeBytes }
                val requiredBytes =
                    maxOf(MIN_REBUILD_SPACE_BYTES, alignToBlock(totalJsonBytes * REBUILD_DB_MULTIPLIER)) +
                        REBUILD_SAFETY_MARGIN_BYTES
                val spaceFailure = checkRebuildSpaceAndReclaim(versionDir, targetDbFile, requiredBytes)
                if (spaceFailure != null) {
                    return@withStorageLock RebuildResult.Failed(spaceFailure, quarantined = false)
                }

                val stagingRoot = storage.stagingRootDir()
                if (!stagingRoot.isDirectory && !stagingRoot.mkdirs()) {
                    return@withStorageLock RebuildResult.Failed(
                        PackageFailure.IoFailure("Cannot create rebuild staging directory: ${stagingRoot.path}"),
                        quarantined = false,
                    )
                }
                val rebuildDir = File(stagingRoot, "rebuild-${UUID.randomUUID()}")
                if (!rebuildDir.mkdirs()) {
                    return@withStorageLock RebuildResult.Failed(
                        PackageFailure.IoFailure("Cannot create rebuild folder: ${rebuildDir.path}"),
                        quarantined = false,
                    )
                }

                try {
                    val stagedDbFile = File(rebuildDir, CONTENT_DB_FILE_NAME)
                    val importRes = importer.importFromZip(
                        channel = channel,
                        container = container,
                        manifest = manifest,
                        packageVersion = info.version,
                        generation = info.generation,
                        targetDbFile = stagedDbFile,
                    )
                    if (importRes is PackageResult.Failure) {
                        return@withStorageLock quarantineIfInvalidPackage(importRes.reason)
                    }
                    deleteSqliteFiles(targetDbFile)
                    Files.move(
                        stagedDbFile.toPath(),
                        targetDbFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    ActivePointerStore.fsyncDir(versionDir)
                    RebuildResult.Rebuilt
                } finally {
                    withContext(NonCancellable) {
                        storage.deleteRecursivelyBestEffort(rebuildDir)
                    }
                }
            }
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: IOException) {
            val failure = if (isOutOfSpaceError(exc)) {
                PackageFailure.InsufficientStorage(zipFile.length(), 0L)
            } else {
                PackageFailure.IoFailure("Content rebuild failed: ${exc.message}")
            }
            RebuildResult.Failed(failure, quarantined = false)
        }
    }

    private fun verifyRetainedCredentialsLocked(
        info: InstalledPackageInfo,
        rawManifestBytes: ByteArray,
        rawSig: ByteArray,
        retainedManifest: File,
        retainedSig: File,
    ): PackageFailure? {
        val currentRing = keyRingSource.currentKeyRing()
        val matchingTrustedKey = (currentRing as? KeyRingState.Configured)?.keys?.any {
            it.keyId == info.keyId && it.publicKeySha256 == info.publicKeySha256
        } == true

        return if (matchingTrustedKey) {
            when (val outcome = verifier.verify(rawSig, rawManifestBytes)) {
                is VerifyOutcome.Failed -> outcome.failure

                is VerifyOutcome.Verified -> {
                    if (outcome.verifiedKeyId != info.keyId ||
                        outcome.verifiedPublicKeySha256 != info.publicKeySha256
                    ) {
                        PackageFailure.InvalidPackage(
                            InvalidPackageCause.UNTRUSTED_KEY_ID,
                            "Retained package signature key mismatch on rebuild.",
                        )
                    } else {
                        null
                    }
                }
            }
        } else {
            // Rule A offline continuity after key retirement: require exact byte match against retained manifest/sig.
            if (!retainedManifest.isFile || !retainedSig.isFile) {
                return PackageFailure.InvalidPackage(
                    InvalidPackageCause.INVALID_SIGNATURE,
                    "Retained manifest/signature missing for offline retired-key rebuild.",
                )
            }
            val expectedManifest = retainedManifest.readBytes()
            val expectedSig = retainedSig.readBytes()
            if (!rawManifestBytes.contentEquals(expectedManifest) || !rawSig.contentEquals(expectedSig)) {
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.INVALID_SIGNATURE,
                    "Retained package.zip metadata disagrees with retained manifest/signature.",
                )
            } else {
                null
            }
        }
    }

    private fun checkRebuildSpaceAndReclaim(
        versionDir: File,
        targetDbFile: File,
        requiredBytes: Long,
    ): PackageFailure? {
        var availableBytes = try {
            availableSpaceProvider.availableBytes(versionDir)
        } catch (exc: IllegalArgumentException) {
            return PackageFailure.IoFailure("StatFs failed on ${versionDir.path}: ${exc.message}")
        }
        if (availableBytes < requiredBytes) {
            // Broken content.db can be removed first to reclaim blocks before re-checking free space.
            deleteSqliteFiles(targetDbFile)
            availableBytes = try {
                availableSpaceProvider.availableBytes(versionDir)
            } catch (exc: IllegalArgumentException) {
                return PackageFailure.IoFailure("StatFs failed on ${versionDir.path}: ${exc.message}")
            }
        }
        return if (availableBytes < requiredBytes) {
            PackageFailure.InsufficientStorage(requiredBytes = requiredBytes, availableBytes = availableBytes)
        } else {
            null
        }
    }

    private fun quarantineIfInvalidPackage(failure: PackageFailure): RebuildResult {
        val shouldQuarantine = failure is PackageFailure.InvalidPackage
        if (shouldQuarantine) {
            storage.quarantineActiveLocked(PackageKind.CONTENT, failure.detail)
        }
        return RebuildResult.Failed(failure, quarantined = shouldQuarantine)
    }

    private fun alignToBlock(sizeBytes: Long): Long =
        ((sizeBytes + REBUILD_BLOCK_MASK) ushr REBUILD_BLOCK_SHIFT) shl REBUILD_BLOCK_SHIFT
}
