package app.seerah.content

/**
 * Signed package category distributed for offline v1 installation ([ADR 0007](../../../docs/adr/0007-content-formats-compatibility-and-signing.md)).
 *
 * @property wireName Canonical string stored inside `manifest.json` `kind`.
 */
enum class PackageKind(val wireName: String) {
    /** Historical text and timeline content package (`package.zip` retained on disk). */
    CONTENT("content"),

    /** Base vector and relief map package (`tile_schema: 1`, unpacked directly into `data/`). */
    MAP("map"),
}

/**
 * One payload file listed in a verified `manifest.json` `files` array.
 *
 * @property path Relative archive path under `data/` using safe printable ASCII segments.
 * @property sizeBytes Exact uncompressed byte size (`0..2_147_483_647`).
 * @property sha256Hex Lowercase 64-character hexadecimal SHA-256 digest.
 */
data class PackageFileEntry(val path: String, val sizeBytes: Long, val sha256Hex: String) {
    init {
        require(path.isNotBlank()) { "Package file entry path must not be blank." }
        require(sizeBytes in 0L..MAX_RELEASE_FILE_BYTES) {
            "Package file entry size $sizeBytes outside 0..$MAX_RELEASE_FILE_BYTES."
        }
        require(isLowerHex(sha256Hex, SHA256_HEX_LENGTH)) {
            "Package file entry sha256Hex must be 64 lowercase hex characters."
        }
    }
}

/**
 * Verified metadata parsed from `manifest.json` after signature verification succeeds.
 *
 * @property kind Package type (`content` or `map`).
 * @property version Monotonic package version (`1..1_000_000`).
 * @property minAppVersion Minimum Android app `versionCode` required to read this package.
 * @property keyId Identifier of the trusted Ed25519 key that signed this manifest.
 * @property sourceCommit Git commit SHA of the source content or map inputs.
 * @property createdUtc Creation timestamp in strict `YYYY-MM-DDTHH:MM:SSZ` format.
 * @property toolCommit Map build toolchain commit SHA (present for `map` packages only).
 * @property image Container image `sha256:...` digest (present for `map` packages only).
 * @property files Lexicographically ordered payload entries inside `data/`.
 */
data class PackageManifest(
    val kind: PackageKind,
    val version: Int,
    val minAppVersion: Int,
    val keyId: String,
    val sourceCommit: String,
    val createdUtc: String,
    val toolCommit: String? = null,
    val image: String? = null,
    val files: List<PackageFileEntry>,
)

/**
 * Active installed package pointer resolved from disk without eagerly re-parsing `manifest.json`.
 *
 * @property kind Package category (`content` or `map`).
 * @property version Installed monotonic package version.
 * @property generation Strictly increasing install generation directory suffix (`v<version>-g<gen>`).
 * @property keyId Signing key identifier recorded at installation time.
 * @property publicKeySha256 Lowercase 64-hex SHA-256 digest of the raw 32-byte signing key.
 * @property rootDirPath Absolute path to the active `v<version>-g<gen>/` version directory.
 * @property dataDirPath Absolute path to the `data/` directory inside [rootDirPath] (populated for `map`
 * packages; `content` packages stream directly from [retainedArchivePath] into disposable `content.db`).
 * @property retainedArchivePath Absolute path to retained `package.zip` for `content` packages, or null for `map`.
 */
data class InstalledPackageInfo(
    val kind: PackageKind,
    val version: Int,
    val generation: Long,
    val keyId: String,
    val publicKeySha256: String,
    val rootDirPath: String,
    val dataDirPath: String,
    val retainedArchivePath: String?,
)
