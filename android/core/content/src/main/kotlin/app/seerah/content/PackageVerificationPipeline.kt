package app.seerah.content

import app.seerah.model.MapMetadata
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Verified package output ready for atomic activation in Phase 3's storage layer. */
internal data class VerifiedPackageStaging(
    val manifest: PackageManifest,
    val rawManifestBytes: ByteArray,
    val rawSignatureBytes: ByteArray,
    val verifiedPublicKeySha256: String,
    val mapMetadata: MapMetadata?,
)

/**
 * Coordinates verify-before-parse ZIP inspection, streaming extraction into [stagingDir], and
 * `data/map.json` + PMTiles v3 validation without trusting unverified bytes.
 */
internal class PackageVerificationPipeline(
    private val verifier: Ed25519Verifier,
    private val appVersionCodeProvider: AppVersionCodeProvider,
    private val manifestParser: (
        rawManifestBytes: ByteArray,
        verifiedKeyId: String,
        expectedKind: PackageKind?,
    ) -> PackageResult<PackageManifest> = { bytes, keyId, expected ->
        PackageManifestParser.parseAndValidate(bytes, keyId, expected)
    },
) {
    suspend fun verifyAndExtract(
        archiveFile: File,
        stagingDir: File,
        expectedKind: PackageKind? = null,
    ): PackageResult<VerifiedPackageStaging> {
        if (!archiveFile.isFile) {
            return PackageResult.Failure(PackageFailure.IoFailure("Archive file does not exist: ${archiveFile.path}"))
        }
        return try {
            FileChannel.open(archiveFile.toPath(), StandardOpenOption.READ).use { channel ->
                verifyAndExtractChannel(channel, stagingDir, expectedKind)
            }
        } catch (exc: IOException) {
            PackageResult.Failure(PackageFailure.IoFailure("Cannot open archive file: ${exc.message}"))
        }
    }

    suspend fun verifyAndExtractChannel(
        channel: FileChannel,
        stagingDir: File,
        expectedKind: PackageKind? = null,
    ): PackageResult<VerifiedPackageStaging> {
        val containerResult = StrictZipReader.inspectContainer(
            channel = channel,
            requireAllStored = (expectedKind == PackageKind.MAP),
        )
        val container = when (containerResult) {
            is PackageResult.Success -> containerResult.value
            is PackageResult.Failure -> return containerResult
        }

        val manifestEntry = container.entries[0]
        val sigEntry = container.entries[1]
        val preParseLimit = if (expectedKind == PackageKind.MAP) ONE_MIB_BYTES else FOUR_MIB_BYTES

        val sigResult = StrictZipReader.readStoredEntryBytes(
            channel = channel,
            entry = sigEntry,
            minSize = RAW_ED25519_SIGNATURE_BYTES,
            maxSize = RAW_ED25519_SIGNATURE_BYTES,
            entryLabel = StrictZipReader.SIGNATURE_ENTRY_NAME,
        )
        val rawSig = when (sigResult) {
            is PackageResult.Success -> sigResult.value
            is PackageResult.Failure -> return sigResult
        }

        val manifestBytesResult = StrictZipReader.readStoredEntryBytes(
            channel = channel,
            entry = manifestEntry,
            minSize = 1,
            maxSize = preParseLimit,
            entryLabel = StrictZipReader.MANIFEST_ENTRY_NAME,
        )
        val rawManifestBytes = when (manifestBytesResult) {
            is PackageResult.Success -> manifestBytesResult.value
            is PackageResult.Failure -> return manifestBytesResult
        }

        // Critical invariant: verify Ed25519 signature over raw manifest.json BEFORE parsing JSON/UTF-8.
        val verifyOutcome = verifier.verify(rawSig, rawManifestBytes)
        val verified = when (verifyOutcome) {
            is VerifyOutcome.Verified -> verifyOutcome
            is VerifyOutcome.Failed -> return PackageResult.Failure(verifyOutcome.failure)
        }

        val parsedManifest = manifestParser(rawManifestBytes, verified.verifiedKeyId, expectedKind)
        val manifest = when (parsedManifest) {
            is PackageResult.Success -> parsedManifest.value
            is PackageResult.Failure -> return parsedManifest
        }

        val appVersionCode = try {
            appVersionCodeProvider.currentVersionCode()
        } catch (exc: IOException) {
            return PackageResult.Failure(
                PackageFailure.IoFailure("Cannot read installed app versionCode: ${exc.message}"),
            )
        }
        if (appVersionCode < manifest.minAppVersion.toLong()) {
            val clampedCurrent = appVersionCode.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            return PackageResult.Failure(
                PackageFailure.AppUpdateRequired(
                    minAppVersion = manifest.minAppVersion,
                    currentAppVersion = clampedCurrent,
                ),
            )
        }

        val expectedNames = listOf(StrictZipReader.MANIFEST_ENTRY_NAME, StrictZipReader.SIGNATURE_ENTRY_NAME) +
            manifest.files.map { it.path }
        val actualNames = container.entries.map { it.path }
        if (actualNames != expectedNames) {
            return PackageResult.Failure(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.INVALID_CONTAINER,
                    "ZIP entry list does not match verified manifest files.",
                ),
            )
        }

        if (manifest.kind == PackageKind.MAP && container.entries.any { it.method != StrictZipReader.METHOD_STORED }) {
            return PackageResult.Failure(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.INVALID_CONTAINER,
                    "Map package data/* entries must all use ZIP_STORED.",
                ),
            )
        }

        for (fileEntry in manifest.files) {
            val zipEntry = container.entriesByPath.getValue(fileEntry.path)
            val extractResult = StrictZipReader.extractEntryToFile(channel, zipEntry, fileEntry, stagingDir)
            if (extractResult is PackageResult.Failure) return extractResult
        }

        val mapMetadata = if (manifest.kind == PackageKind.MAP) {
            val mapResult = validateExtractedMap(stagingDir, manifest.files)
            when (mapResult) {
                is PackageResult.Success -> mapResult.value
                is PackageResult.Failure -> return mapResult
            }
        } else {
            null
        }

        return PackageResult.Success(
            VerifiedPackageStaging(
                manifest = manifest,
                rawManifestBytes = rawManifestBytes,
                rawSignatureBytes = rawSig,
                verifiedPublicKeySha256 = verified.verifiedPublicKeySha256,
                mapMetadata = mapMetadata,
            ),
        )
    }

    private fun validateExtractedMap(stagingDir: File, files: List<PackageFileEntry>): PackageResult<MapMetadata> {
        val dataDir = File(stagingDir, "data")
        val mapJsonFile = File(dataDir, "map.json")
        val rawMapBytes = try {
            mapJsonFile.readBytes()
        } catch (exc: IOException) {
            return PackageResult.Failure(
                PackageFailure.IoFailure("Cannot read extracted data/map.json: ${exc.message}"),
            )
        }
        val mapResult = MapMetadataParser.parseAndValidate(rawMapBytes)
        val metadata = when (mapResult) {
            is PackageResult.Success -> mapResult.value
            is PackageResult.Failure -> return mapResult
        }

        val corePaths = setOf("data/${metadata.sources.base.file}", "data/${metadata.sources.relief.file}")
        val additivePmTiles = files
            .map { it.path }
            .filter { it.endsWith(".pmtiles") && it !in corePaths }

        val pmTilesResult = PmTilesInspector.validateMapDirectory(dataDir, metadata, additivePmTiles)
        return when (pmTilesResult) {
            is PackageResult.Success -> PackageResult.Success(metadata)
            is PackageResult.Failure -> pmTilesResult
        }
    }
}
