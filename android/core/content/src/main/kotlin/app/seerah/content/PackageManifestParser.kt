package app.seerah.content

import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private const val MAX_PACKAGE_VERSION = 1_000_000
private const val MIN_APP_VERSION = 1
private const val GIT_SHA1_HEX_LEN = 40
private const val GIT_SHA256_HEX_LEN = 64

private val KEY_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
private val TIMESTAMP_REGEX = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""")
private val STRICT_TIMESTAMP_FORMATTER = DateTimeFormatter
    .ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
    .withResolverStyle(ResolverStyle.STRICT)

private val REQUIRED_MAP_DATA_PATHS = setOf(
    "data/base.pmtiles",
    "data/map.json",
    "data/relief.pmtiles",
)

/** Strictly parses and validates verified `manifest.json` bytes against `manifest.schema.json`. */
internal object PackageManifestParser {
    private val wireJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

    @Serializable
    private data class FileEntryDto(val path: String, val size: Long, val sha256: String)

    @Serializable
    private data class ManifestDto(
        val kind: String,
        val version: Int,
        @SerialName("min_app_version")
        val minAppVersion: Int,
        @SerialName("key_id")
        val keyId: String,
        @SerialName("source_commit")
        val sourceCommit: String,
        val created: String,
        @SerialName("tool_commit")
        val toolCommit: String? = null,
        val image: String? = null,
        val files: List<FileEntryDto>,
    )

    fun parseAndValidate(
        rawManifestBytes: ByteArray,
        verifiedKeyId: String,
        expectedKind: PackageKind? = null,
    ): PackageResult<PackageManifest> {
        val text = decodeStrictUtf8(rawManifestBytes)
            ?: return invalidManifest("manifest.json is not valid UTF-8.")

        val rootElement = try {
            wireJson.parseToJsonElement(text)
        } catch (exc: SerializationException) {
            return invalidManifest("Invalid manifest.json JSON: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            return invalidManifest("Invalid manifest.json JSON: ${exc.message}")
        }
        if (rootElement !is JsonObject) {
            return invalidManifest("manifest.json root must be a JSON object.")
        }

        val dto = try {
            wireJson.decodeFromJsonElement<ManifestDto>(rootElement)
        } catch (exc: SerializationException) {
            return invalidManifest("manifest.json schema mismatch: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            return invalidManifest("manifest.json schema mismatch: ${exc.message}")
        }

        val kind = when (dto.kind) {
            PackageKind.CONTENT.wireName -> PackageKind.CONTENT
            PackageKind.MAP.wireName -> PackageKind.MAP
            else -> return invalidManifest("Unknown manifest kind '${dto.kind}'.")
        }
        if (expectedKind != null && kind != expectedKind) {
            return PackageResult.Failure(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.UNEXPECTED_KIND,
                    "Expected ${expectedKind.wireName} package, got ${kind.wireName}.",
                ),
            )
        }
        if (kind == PackageKind.MAP && rawManifestBytes.size > ONE_MIB_BYTES) {
            return invalidManifest("Map manifest.json exceeds 1 MiB (${rawManifestBytes.size} bytes).")
        }
        if (dto.version !in 1..MAX_PACKAGE_VERSION) {
            return invalidManifest("Manifest version ${dto.version} outside 1..$MAX_PACKAGE_VERSION.")
        }
        if (dto.minAppVersion < MIN_APP_VERSION) {
            return invalidManifest("Manifest min_app_version ${dto.minAppVersion} must be >= $MIN_APP_VERSION.")
        }
        if (!KEY_ID_REGEX.matches(dto.keyId)) {
            return invalidManifest("Manifest key_id '${dto.keyId}' has invalid syntax.")
        }
        if (dto.keyId != verifiedKeyId) {
            return PackageResult.Failure(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.UNTRUSTED_KEY_ID,
                    "Verified signature under '$verifiedKeyId', but manifest claims '${dto.keyId}'.",
                ),
            )
        }
        if (!isValidCommitSha(dto.sourceCommit)) {
            return invalidManifest("Invalid source_commit '${dto.sourceCommit}'.")
        }
        if (!isValidStrictUtcTimestamp(dto.created)) {
            return invalidManifest("Invalid created timestamp '${dto.created}'.")
        }

        validateProvenance(kind, rootElement, dto)?.let { return it }

        val pathError = StrictZipReader.validateDataPaths(dto.files.map { it.path })
        if (pathError != null) return invalidManifest(pathError)

        val entries = ArrayList<PackageFileEntry>(dto.files.size)
        for (entryDto in dto.files) {
            if (entryDto.size !in 0L..MAX_RELEASE_FILE_BYTES) {
                return invalidManifest("Invalid file size ${entryDto.size} for '${entryDto.path}'.")
            }
            if (!isLowerHex(entryDto.sha256, SHA256_HEX_LENGTH)) {
                return invalidManifest("Invalid SHA-256 digest for '${entryDto.path}'.")
            }
            entries.add(
                PackageFileEntry(
                    path = entryDto.path,
                    sizeBytes = entryDto.size,
                    sha256Hex = entryDto.sha256,
                ),
            )
        }

        if (kind == PackageKind.MAP) {
            val pathSet = entries.mapTo(HashSet(entries.size)) { it.path }
            if (!pathSet.containsAll(REQUIRED_MAP_DATA_PATHS)) {
                return invalidManifest("Map package missing required files: ${REQUIRED_MAP_DATA_PATHS - pathSet}.")
            }
        }

        return PackageResult.Success(
            PackageManifest(
                kind = kind,
                version = dto.version,
                minAppVersion = dto.minAppVersion,
                keyId = dto.keyId,
                sourceCommit = dto.sourceCommit,
                createdUtc = dto.created,
                toolCommit = dto.toolCommit,
                image = dto.image,
                files = entries,
            ),
        )
    }

    private fun validateProvenance(
        kind: PackageKind,
        rawObject: JsonObject,
        dto: ManifestDto,
    ): PackageResult.Failure? = when (kind) {
        PackageKind.CONTENT -> {
            // Inspect raw keys so explicit `"tool_commit": null` cannot bypass explicitNulls = false.
            if ("tool_commit" in rawObject || "image" in rawObject) {
                invalidManifest("Content manifests must not declare tool_commit or image.")
            } else {
                null
            }
        }

        PackageKind.MAP -> {
            val toolCommit = dto.toolCommit
            val image = dto.image
            if (toolCommit == null || !isValidCommitSha(toolCommit)) {
                invalidManifest("Map manifest requires 40/64-char hex tool_commit.")
            } else if (image == null || !image.startsWith("sha256:") ||
                !isLowerHex(image.removePrefix("sha256:"), SHA256_HEX_LENGTH)
            ) {
                invalidManifest("Map manifest image must be a sha256:<64-hex> digest.")
            } else {
                null
            }
        }
    }

    private fun isValidCommitSha(value: String): Boolean =
        isLowerHex(value, GIT_SHA1_HEX_LEN) || isLowerHex(value, GIT_SHA256_HEX_LEN)

    private fun isValidStrictUtcTimestamp(value: String): Boolean {
        if (!TIMESTAMP_REGEX.matches(value)) return false
        return try {
            STRICT_TIMESTAMP_FORMATTER.parse(value)
            true
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private fun invalidManifest(detail: String): PackageResult.Failure =
        PackageResult.Failure(PackageFailure.InvalidPackage(InvalidPackageCause.INVALID_MANIFEST, detail))
}
