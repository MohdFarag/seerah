package app.seerah.content

import app.seerah.content.di.IoDispatcher
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val BLOCK_SIZE_BYTES = 4_096L
private const val BLOCK_ALIGN_MASK = 4_095L
private const val BLOCK_SHIFT_BITS = 12
private const val MIN_CONTENT_DB_SPACE_BYTES = 65_536L
private const val CONTENT_DB_MULTIPLIER = 3L
private const val SAFETY_MARGIN_BYTES = 32L * 1_024L * 1_024L
private const val COPY_CHUNK_BYTES = 262_144
private const val CONTENT_ARCHIVE_NAME = "package.zip"
private const val INSTALLER_LOG_TAG = "PackageInstaller"

/**
 * Atomic, anti-TOCTOU package installer coordinating verification, block-aligned space gating,
 * key-compromise version-floor resets, and rollback-safe activation.
 */
@Singleton
internal class DefaultPackageInstaller @Inject constructor(
    private val contentRootDir: File,
    private val storage: DefaultPackageStorage,
    private val keyRingSource: TrustedKeyRingSource,
    private val verifier: Ed25519Verifier,
    private val availableSpaceProvider: AvailableSpaceProvider,
    private val appVersionCodeProvider: AppVersionCodeProvider,
    private val contentDatabaseImporter: ContentDatabaseImporter,
    private val logger: PackageStorageLogger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PackageInstaller {
    private val pipeline = PackageVerificationPipeline(verifier, appVersionCodeProvider)

    private data class StagedInstall(val staged: VerifiedPackageStaging, val generation: Long)

    private data class InstallLockOutcome(
        val result: PackageResult<InstalledPackageInfo>,
        val oldDirToDeleteOffLock: File? = null,
    )

    override suspend fun install(archiveFile: File, expectedKind: PackageKind?): PackageResult<InstalledPackageInfo> =
        withContext(ioDispatcher) {
            val outcome = storage.withStorageLock {
                installLocked(archiveFile, expectedKind)
            }
            outcome.oldDirToDeleteOffLock?.let { oldDir ->
                storage.deleteRecursivelyBestEffort(oldDir)
            }
            outcome.result
        }

    private suspend fun installLocked(archiveFile: File, expectedKind: PackageKind?): InstallLockOutcome {
        val currentRing = keyRingSource.currentKeyRing()
        val ringFailure = when (currentRing) {
            KeyRingState.Unconfigured -> PackageFailure.KeyRingNotConfigured

            is KeyRingState.Invalid ->
                PackageFailure.InvalidPackage(InvalidPackageCause.INVALID_KEY_RING, currentRing.detail)

            is KeyRingState.Configured -> {
                if (currentRing.keys.isEmpty()) PackageFailure.KeyRingNotConfigured else null
            }
        }
        if (ringFailure != null) return InstallLockOutcome(PackageResult.Failure(ringFailure))

        val trustedKeys = (currentRing as KeyRingState.Configured).keys
        if (!archiveFile.isFile) {
            return InstallLockOutcome(
                PackageResult.Failure(
                    PackageFailure.IoFailure("Package archive does not exist: ${archiveFile.path}"),
                ),
            )
        }
        val archiveBytes = archiveFile.length()
        if (archiveBytes > MAX_RELEASE_FILE_BYTES) {
            return InstallLockOutcome(
                PackageResult.Failure(
                    PackageFailure.InvalidPackage(
                        InvalidPackageCause.INVALID_CONTAINER,
                        "Archive size $archiveBytes exceeds maximum release file limit $MAX_RELEASE_FILE_BYTES.",
                    ),
                ),
            )
        }

        val stagingRoot = storage.stagingRootDir()
        if (!stagingRoot.isDirectory && !stagingRoot.mkdirs()) {
            return InstallLockOutcome(
                PackageResult.Failure(
                    PackageFailure.IoFailure("Cannot create staging directory: ${stagingRoot.path}"),
                ),
            )
        }
        val stageName = "${expectedKind?.wireName ?: "pkg"}-${UUID.randomUUID()}"
        val stagingDir = File(stagingRoot, stageName)
        if (!stagingDir.mkdirs()) {
            return InstallLockOutcome(
                PackageResult.Failure(PackageFailure.IoFailure("Cannot create staging folder: ${stagingDir.path}")),
            )
        }

        var movedFinalDir: File? = null
        var activeTmpFile: File? = null
        try {
            val verifiedResult = verifyAndExtractAntiToctou(
                archiveFile = archiveFile,
                archiveBytes = archiveBytes,
                stagingDir = stagingDir,
                expectedKind = expectedKind,
                trustedKeys = trustedKeys,
            )
            val (staged, nextGen) = when (verifiedResult) {
                is PackageResult.Success -> verifiedResult.value
                is PackageResult.Failure -> return InstallLockOutcome(verifiedResult)
            }

            // Persist verified manifest.json and manifest.sig at package root for audit/self-check.
            writeVerifiedRootFile(stagingDir, StrictZipReader.MANIFEST_ENTRY_NAME, staged.rawManifestBytes)?.let {
                return InstallLockOutcome(PackageResult.Failure(it))
            }
            writeVerifiedRootFile(stagingDir, StrictZipReader.SIGNATURE_ENTRY_NAME, staged.rawSignatureBytes)?.let {
                return InstallLockOutcome(PackageResult.Failure(it))
            }
            fsyncStagingTree(stagingDir)

            val kind = staged.manifest.kind
            val kindDir = storage.kindDir(kind)
            if (!kindDir.isDirectory && !kindDir.mkdirs()) {
                return InstallLockOutcome(
                    PackageResult.Failure(PackageFailure.IoFailure("Cannot create target kind dir: ${kindDir.path}")),
                )
            }

            val oldPointer = storage.activePointerLocked(kind)
            val activeDirName = "v${staged.manifest.version}-g$nextGen"
            val finalVersionDir = File(kindDir, activeDirName)
            val oldActiveDir = oldPointer?.let { File(kindDir, it.activeDir) }

            val newPointer = ActivePointer(
                schema = 1,
                version = staged.manifest.version,
                generation = nextGen,
                keyId = staged.manifest.keyId,
                publicKeySha256 = staged.verifiedPublicKeySha256,
                activeDir = activeDirName,
            )

            withContext(NonCancellable) {
                if (finalVersionDir.exists()) {
                    storage.deleteRecursivelyBestEffort(finalVersionDir)
                }
                Files.move(stagingDir.toPath(), finalVersionDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
                movedFinalDir = finalVersionDir
                ActivePointerStore.fsyncDir(kindDir)

                activeTmpFile = File(kindDir, ActivePointerStore.ACTIVE_TEMP_FILE_NAME)
                ActivePointerStore.writeAtomic(kindDir, newPointer)
                movedFinalDir = null
                activeTmpFile = null

                storage.publishActiveLocked(kind, newPointer)
            }

            val oldDirToDeleteOffLock = if (
                oldActiveDir != null &&
                oldActiveDir != finalVersionDir &&
                !storage.isPinnedLocked(kind, oldActiveDir.name)
            ) {
                oldActiveDir
            } else {
                null
            }

            val installedInfo = storage.observeInstalled(kind).value as InstalledPackageState.Installed
            return InstallLockOutcome(
                result = PackageResult.Success(installedInfo.info),
                oldDirToDeleteOffLock = oldDirToDeleteOffLock,
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: IOException) {
            return InstallLockOutcome(
                PackageResult.Failure(
                    mapIoOrNoSpace(exc, requiredBytes = archiveBytes, detail = "Install I/O error"),
                ),
            )
        } catch (exc: SecurityException) {
            return InstallLockOutcome(
                PackageResult.Failure(
                    PackageFailure.IoFailure("Install filesystem permission error: ${exc.message}"),
                ),
            )
        } finally {
            withContext(NonCancellable) {
                activeTmpFile?.let { tmp ->
                    if (tmp.exists() && !tmp.delete()) {
                        logger.warn(INSTALLER_LOG_TAG, "Failed cleaning temp pointer ${tmp.path}")
                    }
                }
                movedFinalDir?.let { moved ->
                    storage.deleteRecursivelyBestEffort(moved)
                }
                if (stagingDir.exists()) {
                    storage.deleteRecursivelyBestEffort(stagingDir)
                }
            }
        }
    }

    private suspend fun verifyAndExtractAntiToctou(
        archiveFile: File,
        archiveBytes: Long,
        stagingDir: File,
        expectedKind: PackageKind?,
        trustedKeys: List<TrustedPublicKey>,
    ): PackageResult<StagedInstall> {
        val channelToVerify: FileChannel
        val copiedContentZipEarly: Boolean
        if (expectedKind == PackageKind.CONTENT) {
            val retainedFile = File(stagingDir, CONTENT_ARCHIVE_NAME)
            FileChannel.open(archiveFile.toPath(), StandardOpenOption.READ).use { src ->
                copyChannelBounded(src, retainedFile, MAX_RELEASE_FILE_BYTES)?.let {
                    return PackageResult.Failure(it)
                }
            }
            channelToVerify = FileChannel.open(retainedFile.toPath(), StandardOpenOption.READ)
            copiedContentZipEarly = true
        } else {
            channelToVerify = FileChannel.open(archiveFile.toPath(), StandardOpenOption.READ)
            copiedContentZipEarly = false
        }

        return channelToVerify.use { pinnedChannel ->
            val containerRes = StrictZipReader.inspectContainer(
                channel = pinnedChannel,
                requireAllStored = (expectedKind == PackageKind.MAP),
            )
            val container = when (containerRes) {
                is PackageResult.Success -> containerRes.value
                is PackageResult.Failure -> return@use containerRes
            }

            val sigResult = StrictZipReader.readStoredEntryBytes(
                channel = pinnedChannel,
                entry = container.entries[1],
                minSize = RAW_ED25519_SIGNATURE_BYTES,
                maxSize = RAW_ED25519_SIGNATURE_BYTES,
                entryLabel = StrictZipReader.SIGNATURE_ENTRY_NAME,
            )
            val rawSig = when (sigResult) {
                is PackageResult.Success -> sigResult.value
                is PackageResult.Failure -> return@use sigResult
            }

            val preParseLimit = if (expectedKind == PackageKind.MAP) ONE_MIB_BYTES else FOUR_MIB_BYTES
            val manifestResult = StrictZipReader.readStoredEntryBytes(
                channel = pinnedChannel,
                entry = container.entries[0],
                minSize = 1,
                maxSize = preParseLimit,
                entryLabel = StrictZipReader.MANIFEST_ENTRY_NAME,
            )
            val rawManifestBytes = when (manifestResult) {
                is PackageResult.Success -> manifestResult.value
                is PackageResult.Failure -> return@use manifestResult
            }

            // Signature checked over raw manifest bytes BEFORE UTF-8 decoding or JSON parsing.
            val verifyOutcome = verifier.verify(rawSig, rawManifestBytes)
            val verified = when (verifyOutcome) {
                is VerifyOutcome.Verified -> verifyOutcome
                is VerifyOutcome.Failed -> return@use PackageResult.Failure(verifyOutcome.failure)
            }

            val manifestParsed = PackageManifestParser.parseAndValidate(
                rawManifestBytes = rawManifestBytes,
                verifiedKeyId = verified.verifiedKeyId,
                expectedKind = expectedKind,
            )
            val manifest = when (manifestParsed) {
                is PackageResult.Success -> manifestParsed.value
                is PackageResult.Failure -> return@use manifestParsed
            }

            val appVersion = try {
                appVersionCodeProvider.currentVersionCode()
            } catch (exc: IOException) {
                return@use PackageResult.Failure(PackageFailure.IoFailure("Cannot read app version: ${exc.message}"))
            }
            if (appVersion < manifest.minAppVersion.toLong()) {
                return@use PackageResult.Failure(
                    PackageFailure.AppUpdateRequired(
                        minAppVersion = manifest.minAppVersion,
                        currentAppVersion = appVersion.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    ),
                )
            }

            checkVersionFloor(manifest, trustedKeys)?.let { return@use PackageResult.Failure(it) }

            checkAvailableSpace(
                manifest = manifest,
                rawManifestSize = rawManifestBytes.size,
                copiedContentZipEarly = copiedContentZipEarly,
                archiveBytes = archiveBytes,
            )?.let { return@use PackageResult.Failure(it) }

            val kindDir = storage.kindDir(manifest.kind)
            val oldPointer = storage.activePointerLocked(manifest.kind)
            val nextGen = maxOf(
                oldPointer?.generation ?: 0L,
                ActivePointerStore.scanHighestGeneration(kindDir),
            ) + 1L

            if (manifest.kind == PackageKind.CONTENT) {
                val importRes = if (copiedContentZipEarly) {
                    contentDatabaseImporter.importFromZip(
                        channel = pinnedChannel,
                        container = container,
                        manifest = manifest,
                        packageVersion = manifest.version,
                        generation = nextGen,
                        targetDbFile = File(stagingDir, CONTENT_DB_FILE_NAME),
                    )
                } else {
                    val retainedFile = File(stagingDir, CONTENT_ARCHIVE_NAME)
                    copyChannelBounded(pinnedChannel, retainedFile, MAX_RELEASE_FILE_BYTES)?.let {
                        return@use PackageResult.Failure(it)
                    }
                    importPinnedContentCopy(
                        retainedFile = retainedFile,
                        expectedRawManifest = rawManifestBytes,
                        expectedRawSig = rawSig,
                        manifest = manifest,
                        generation = nextGen,
                        targetDbFile = File(stagingDir, CONTENT_DB_FILE_NAME),
                    )
                }
                return@use when (importRes) {
                    is PackageResult.Success -> PackageResult.Success(
                        StagedInstall(
                            staged = VerifiedPackageStaging(
                                manifest = manifest,
                                rawManifestBytes = rawManifestBytes,
                                rawSignatureBytes = rawSig,
                                verifiedPublicKeySha256 = verified.verifiedPublicKeySha256,
                                mapMetadata = null,
                            ),
                            generation = nextGen,
                        ),
                    )

                    is PackageResult.Failure -> importRes
                }
            }

            when (val extractRes = pipeline.verifyAndExtractChannel(pinnedChannel, stagingDir, expectedKind)) {
                is PackageResult.Success -> PackageResult.Success(StagedInstall(extractRes.value, nextGen))
                is PackageResult.Failure -> extractRes
            }
        }
    }

    private suspend fun importPinnedContentCopy(
        retainedFile: File,
        expectedRawManifest: ByteArray,
        expectedRawSig: ByteArray,
        manifest: PackageManifest,
        generation: Long,
        targetDbFile: File,
    ): PackageResult<Unit> {
        FileChannel.open(retainedFile.toPath(), StandardOpenOption.READ).use { copiedChannel ->
            val containerRes = StrictZipReader.inspectContainer(copiedChannel, requireAllStored = false)
            val container = when (containerRes) {
                is PackageResult.Success -> containerRes.value
                is PackageResult.Failure -> return containerRes
            }
            val copiedManifest = StrictZipReader.readStoredEntryBytes(
                copiedChannel,
                container.entries[0],
                expectedRawManifest.size,
                expectedRawManifest.size,
                StrictZipReader.MANIFEST_ENTRY_NAME,
            )
            val copiedSig = StrictZipReader.readStoredEntryBytes(
                copiedChannel,
                container.entries[1],
                expectedRawSig.size,
                expectedRawSig.size,
                StrictZipReader.SIGNATURE_ENTRY_NAME,
            )
            val manifestBytes = (copiedManifest as? PackageResult.Success)?.value
                ?: return copiedManifest as PackageResult.Failure
            val sigBytes = (copiedSig as? PackageResult.Success)?.value
                ?: return copiedSig as PackageResult.Failure
            if (!manifestBytes.contentEquals(expectedRawManifest) || !sigBytes.contentEquals(expectedRawSig)) {
                return PackageResult.Failure(
                    PackageFailure.InvalidPackage(
                        InvalidPackageCause.PAYLOAD_INTEGRITY,
                        "Archive contents changed while copying to staging.",
                    ),
                )
            }
            return contentDatabaseImporter.importFromZip(
                channel = copiedChannel,
                container = container,
                manifest = manifest,
                packageVersion = manifest.version,
                generation = generation,
                targetDbFile = targetDbFile,
            )
        }
    }

    private fun checkVersionFloor(manifest: PackageManifest, trustedKeys: List<TrustedPublicKey>): PackageFailure? {
        val installed = storage.activePointerLocked(manifest.kind) ?: return null
        val activeKeyStillTrusted = trustedKeys.any {
            it.keyId == installed.keyId && it.publicKeySha256 == installed.publicKeySha256
        }
        if (!activeKeyStillTrusted) {
            // Rule A: installed key retired or revoked -> reset version floor.
            return null
        }
        // Rule B: same active trusted key pair -> enforce strict forward-only monotonicity.
        return if (manifest.version <= installed.version) {
            PackageFailure.DowngradeOrSameVersion(
                installedVersion = installed.version,
                candidateVersion = manifest.version,
            )
        } else {
            null
        }
    }

    private fun checkAvailableSpace(
        manifest: PackageManifest,
        rawManifestSize: Int,
        copiedContentZipEarly: Boolean,
        archiveBytes: Long,
    ): PackageFailure? {
        val payloadAlignedBytes = when (manifest.kind) {
            PackageKind.CONTENT -> {
                val totalJsonBytes = manifest.files.sumOf { it.sizeBytes }
                maxOf(MIN_CONTENT_DB_SPACE_BYTES, alignToBlock(totalJsonBytes * CONTENT_DB_MULTIPLIER))
            }

            PackageKind.MAP -> manifest.files.sumOf { alignToBlock(it.sizeBytes) }
        }
        val manifestAlignedBytes = alignToBlock(rawManifestSize.toLong()) + BLOCK_SIZE_BYTES
        val retainedAligned = if (manifest.kind == PackageKind.CONTENT && !copiedContentZipEarly) {
            alignToBlock(archiveBytes)
        } else {
            0L
        }
        val requiredBytes = payloadAlignedBytes + manifestAlignedBytes + retainedAligned + SAFETY_MARGIN_BYTES

        val spaceTarget = ensureExistingSpaceDir()
            ?: return PackageFailure.IoFailure(
                "Cannot resolve existing directory for StatFs under ${contentRootDir.path}.",
            )
        val availableBytes = try {
            availableSpaceProvider.availableBytes(spaceTarget)
        } catch (exc: IllegalArgumentException) {
            return PackageFailure.IoFailure("StatFs failed on ${spaceTarget.path}: ${exc.message}")
        }
        return if (availableBytes < requiredBytes) {
            PackageFailure.InsufficientStorage(requiredBytes = requiredBytes, availableBytes = availableBytes)
        } else {
            null
        }
    }

    private fun ensureExistingSpaceDir(): File? {
        if (contentRootDir.isDirectory || contentRootDir.mkdirs()) return contentRootDir
        val parent = contentRootDir.parentFile
        return if (parent != null && parent.isDirectory) parent else null
    }

    private fun alignToBlock(sizeBytes: Long): Long =
        ((sizeBytes + BLOCK_ALIGN_MASK) ushr BLOCK_SHIFT_BITS) shl BLOCK_SHIFT_BITS

    private suspend fun copyChannelBounded(source: FileChannel, destination: File, maxBytes: Long): PackageFailure? {
        var total = 0L
        val buffer = ByteBuffer.allocate(COPY_CHUNK_BYTES)
        var readPos = 0L
        return try {
            FileChannel.open(
                destination.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { outChannel ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val read = source.read(buffer, readPos)
                    if (read <= 0) break
                    readPos += read.toLong()
                    total += read.toLong()
                    if (total > maxBytes) {
                        destination.delete()
                        return PackageFailure.InvalidPackage(
                            InvalidPackageCause.INVALID_CONTAINER,
                            "Archive exceeded max release file limit $maxBytes bytes while staging.",
                        )
                    }
                    buffer.flip()
                    while (buffer.hasRemaining()) {
                        outChannel.write(buffer)
                    }
                }
                outChannel.force(true)
            }
            null
        } catch (exc: CancellationException) {
            destination.delete()
            throw exc
        } catch (exc: IOException) {
            destination.delete()
            mapIoOrNoSpace(exc, requiredBytes = maxOf(total, source.size()), detail = "Failed staging package copy")
        }
    }

    private fun writeVerifiedRootFile(dir: File, name: String, bytes: ByteArray): PackageFailure? = try {
        val target = File(dir, name)
        FileChannel.open(
            target.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { out ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) {
                out.write(buf)
            }
            out.force(true)
        }
        null
    } catch (exc: IOException) {
        mapIoOrNoSpace(exc, requiredBytes = bytes.size.toLong(), detail = "Cannot write verified $name")
    }

    private fun fsyncStagingTree(stagingDir: File) {
        val dirs = ArrayList<File>()
        fun walk(dir: File) {
            for (child in dir.listFiles().orEmpty()) {
                if (child.isDirectory) {
                    walk(child)
                    dirs.add(child)
                }
            }
        }
        walk(stagingDir)
        dirs.add(stagingDir)
        for (d in dirs) {
            ActivePointerStore.fsyncDir(d)
        }
    }

    private fun mapIoOrNoSpace(exc: IOException, requiredBytes: Long, detail: String): PackageFailure =
        if (isOutOfSpaceError(exc)) {
            PackageFailure.InsufficientStorage(requiredBytes = requiredBytes, availableBytes = 0L)
        } else {
            PackageFailure.IoFailure("$detail: ${exc.message}")
        }
}
