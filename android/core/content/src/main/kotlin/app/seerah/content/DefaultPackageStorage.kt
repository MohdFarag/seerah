package app.seerah.content

import app.seerah.content.di.IoDispatcher
import app.seerah.model.MapMetadata
import java.io.File
import java.io.IOException
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val STORAGE_LOG_TAG = "PackageStorage"
private const val CONTENT_ARCHIVE_NAME = "package.zip"
private const val DATA_DIR_NAME = "data"
private const val MAP_JSON_NAME = "map.json"
private const val STAGING_DIR_NAME = "staging"

/**
 * Thread-safe, coroutine-locked storage manager for verified `content` and `map` packages under
 * `<filesDir>/content/`.
 *
 * All cold-start reads, cleanup sweeps, and active pointer switches share one [Mutex] so cleanup
 * can never remove staging or newly moved version folders concurrently with installation.
 * Construction performs zero disk I/O to preserve `StrictMode` compatibility.
 */
@Singleton
internal class DefaultPackageStorage @Inject constructor(
    private val contentRootDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: PackageStorageLogger,
) : PackageStorage,
    PackageStorageMaintenance {
    private val storageMutex = Mutex()
    private var hydrated = false

    private val activePointers = EnumMap<PackageKind, ActivePointer>(PackageKind::class.java)
    private val pinnedDirCounts = EnumMap<PackageKind, MutableMap<String, Int>>(PackageKind::class.java).apply {
        for (kind in PackageKind.entries) {
            put(kind, HashMap())
        }
    }
    private val stateFlows = EnumMap<PackageKind, MutableStateFlow<InstalledPackageState>>(
        PackageKind::class.java,
    ).apply {
        for (kind in PackageKind.entries) {
            put(kind, MutableStateFlow(InstalledPackageState.Loading))
        }
    }

    override fun observeInstalled(kind: PackageKind): StateFlow<InstalledPackageState> =
        stateFlows.getValue(kind).asStateFlow()

    override suspend fun currentInstalled(kind: PackageKind): InstalledPackageInfo? = withContext(ioDispatcher) {
        storageMutex.withLock {
            ensureHydratedLocked()
            (stateFlows.getValue(kind).value as? InstalledPackageState.Installed)?.info
        }
    }

    override suspend fun readActiveMapMetadata(info: InstalledPackageInfo): PackageResult<MapMetadata> {
        if (info.kind != PackageKind.MAP) {
            return PackageResult.Failure(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.UNEXPECTED_KIND,
                    "readActiveMapMetadata requires a MAP package, got ${info.kind.wireName}.",
                ),
            )
        }
        return withContext(ioDispatcher) {
            val dataDir = File(info.dataDirPath)
            val mapFile = File(dataDir, MAP_JSON_NAME)
            val rawBytes = try {
                if (!mapFile.isFile) {
                    return@withContext PackageResult.Failure(
                        PackageFailure.IoFailure("Active map.json missing at ${mapFile.path}"),
                    )
                }
                mapFile.readBytes()
            } catch (exc: IOException) {
                return@withContext PackageResult.Failure(
                    PackageFailure.IoFailure("Cannot read active map.json: ${exc.message}"),
                )
            }
            val parsed = MapMetadataParser.parseAndValidate(rawBytes)
            val meta = when (parsed) {
                is PackageResult.Success -> parsed.value
                is PackageResult.Failure -> return@withContext parsed
            }
            val baseFile = resolveContainedFile(dataDir, meta.sources.base.file)
            val reliefFile = resolveContainedFile(dataDir, meta.sources.relief.file)
            if (baseFile == null || !baseFile.isFile || reliefFile == null || !reliefFile.isFile) {
                return@withContext PackageResult.Failure(
                    PackageFailure.IoFailure("Active map archive files missing inside ${dataDir.path}"),
                )
            }
            PackageResult.Success(meta)
        }
    }

    override suspend fun cleanupOrphanedFiles(): PackageResult<Unit> = withContext(ioDispatcher) {
        storageMutex.withLock {
            ensureHydratedLocked()
            try {
                deleteDirContents(stagingRootDir())
                for (kind in PackageKind.entries) {
                    val kindDir = kindDir(kind)
                    if (!kindDir.isDirectory) continue
                    val tmpPointer = File(kindDir, ActivePointerStore.ACTIVE_TEMP_FILE_NAME)
                    if (tmpPointer.exists() && !tmpPointer.delete()) {
                        logger.warn(STORAGE_LOG_TAG, "Failed deleting stale ${tmpPointer.path}")
                    }
                    val keepDirName = activePointers[kind]?.activeDir
                    for (child in kindDir.listFiles().orEmpty()) {
                        if (child.isDirectory && child.name != keepDirName && !isPinnedLocked(kind, child.name)) {
                            deleteRecursivelyBestEffort(child)
                        }
                    }
                }
                PackageResult.Success(Unit)
            } catch (exc: IOException) {
                PackageResult.Failure(PackageFailure.IoFailure("Orphan cleanup failed: ${exc.message}"))
            } catch (exc: SecurityException) {
                PackageResult.Failure(PackageFailure.IoFailure("Orphan cleanup blocked: ${exc.message}"))
            }
        }
    }

    internal suspend fun <T> withStorageLock(block: suspend () -> T): T = storageMutex.withLock {
        ensureHydratedLocked()
        block()
    }

    internal fun activePointerLocked(kind: PackageKind): ActivePointer? = activePointers[kind]

    internal fun kindDir(kind: PackageKind): File = File(contentRootDir, kind.wireName)

    internal fun stagingRootDir(): File = File(contentRootDir, STAGING_DIR_NAME)

    internal fun pinActiveDirLocked(kind: PackageKind, expectedVersion: Int, expectedGeneration: Long): String? {
        val active = activePointers[kind] ?: return null
        if (active.version != expectedVersion || active.generation != expectedGeneration) return null
        pinVersionDirLocked(kind, active.activeDir)
        return active.activeDir
    }

    internal fun pinVersionDirLocked(kind: PackageKind, dirName: String) {
        val counts = pinnedDirCounts.getValue(kind)
        counts[dirName] = (counts[dirName] ?: 0) + 1
    }

    internal fun unpinVersionDirLocked(kind: PackageKind, dirName: String) {
        val counts = pinnedDirCounts.getValue(kind)
        val remaining = (counts[dirName] ?: 0) - 1
        if (remaining > 0) {
            counts[dirName] = remaining
        } else {
            counts.remove(dirName)
        }
    }

    internal fun isPinnedLocked(kind: PackageKind, dirName: String): Boolean =
        (pinnedDirCounts[kind]?.get(dirName) ?: 0) > 0

    internal fun quarantineActiveLocked(kind: PackageKind, message: String) {
        quarantineCorruptPointer(kind, File(kindDir(kind), ActivePointerStore.ACTIVE_FILE_NAME), message)
    }

    internal fun publishActiveLocked(kind: PackageKind, pointer: ActivePointer) {
        val versionDir = File(kindDir(kind), pointer.activeDir)
        activePointers[kind] = pointer
        stateFlows.getValue(kind).value = InstalledPackageState.Installed(toInstalledInfo(kind, pointer, versionDir))
    }

    internal fun deleteRecursivelyBestEffort(target: File) {
        if (!target.exists()) return
        try {
            if (!target.deleteRecursively()) {
                logger.warn(STORAGE_LOG_TAG, "Could not completely delete ${target.path}; will retry on cleanup.")
            }
        } catch (exc: SecurityException) {
            logger.warn(STORAGE_LOG_TAG, "Delete blocked for ${target.path}", exc)
        }
    }

    private fun ensureHydratedLocked() {
        if (hydrated) return
        for (kind in PackageKind.entries) {
            loadKindFromDiskLocked(kind)
        }
        hydrated = true
    }

    private fun loadKindFromDiskLocked(kind: PackageKind) {
        val kindDir = kindDir(kind)
        val activeFile = File(kindDir, ActivePointerStore.ACTIVE_FILE_NAME)
        if (!activeFile.exists()) {
            activePointers.remove(kind)
            stateFlows.getValue(kind).value = InstalledPackageState.NotInstalled
            return
        }

        // Cold start trusts active.json & required files on disk without re-checking keyring or PMTiles hashes
        // so users retain offline continuity after key retirement or revocation (Rule A).
        val pointer = ActivePointerStore.readValidated(kindDir)
        if (pointer == null) {
            quarantineCorruptPointer(kind, activeFile, "Unparsable or invalid active.json at ${activeFile.path}")
            return
        }

        val versionDir = File(kindDir, pointer.activeDir)
        if (!hasRequiredActiveFiles(kind, versionDir)) {
            quarantineCorruptPointer(
                kind,
                activeFile,
                "Dangling active pointer '${pointer.activeDir}' in ${kindDir.path}",
            )
            return
        }

        activePointers[kind] = pointer
        stateFlows.getValue(kind).value = InstalledPackageState.Installed(toInstalledInfo(kind, pointer, versionDir))
    }

    private fun quarantineCorruptPointer(kind: PackageKind, activeFile: File, message: String) {
        logger.warn(STORAGE_LOG_TAG, message)
        if (activeFile.exists() && !activeFile.delete()) {
            logger.warn(STORAGE_LOG_TAG, "Failed deleting corrupt ${activeFile.path}")
        }
        activePointers.remove(kind)
        stateFlows.getValue(kind).value = InstalledPackageState.NotInstalled
    }

    private fun hasRequiredActiveFiles(kind: PackageKind, versionDir: File): Boolean {
        val manifestFile = File(versionDir, StrictZipReader.MANIFEST_ENTRY_NAME)
        val sigFile = File(versionDir, StrictZipReader.SIGNATURE_ENTRY_NAME)
        if (!versionDir.isDirectory || !manifestFile.isFile || !sigFile.isFile) {
            return false
        }
        return when (kind) {
            // content.db is disposable and automatically rebuilt from package.zip if missing or corrupt.
            PackageKind.CONTENT -> File(versionDir, CONTENT_ARCHIVE_NAME).isFile

            PackageKind.MAP -> {
                val dataDir = File(versionDir, DATA_DIR_NAME)
                dataDir.isDirectory &&
                    File(dataDir, MAP_JSON_NAME).isFile &&
                    File(dataDir, "base.pmtiles").isFile &&
                    File(dataDir, "relief.pmtiles").isFile
            }
        }
    }

    private fun toInstalledInfo(kind: PackageKind, pointer: ActivePointer, versionDir: File): InstalledPackageInfo =
        InstalledPackageInfo(
            kind = kind,
            version = pointer.version,
            generation = pointer.generation,
            keyId = pointer.keyId,
            publicKeySha256 = pointer.publicKeySha256,
            rootDirPath = versionDir.path,
            dataDirPath = File(versionDir, DATA_DIR_NAME).path,
            retainedArchivePath = if (kind == PackageKind.CONTENT) {
                File(versionDir, CONTENT_ARCHIVE_NAME).path
            } else {
                null
            },
        )

    private fun deleteDirContents(dir: File) {
        if (!dir.isDirectory) return
        for (child in dir.listFiles().orEmpty()) {
            deleteRecursivelyBestEffort(child)
        }
    }

    private fun resolveContainedFile(dataDir: File, relativeName: String): File? {
        if (relativeName.isBlank() || File(relativeName).isAbsolute) return null
        return try {
            val canonicalRoot = dataDir.canonicalFile.toPath()
            val target = File(dataDir, relativeName).canonicalFile.toPath()
            if (target.startsWith(canonicalRoot) && target != canonicalRoot) target.toFile() else null
        } catch (_: IOException) {
            null
        }
    }
}
