package app.seerah.content

/** Result returned by package verification, inspection, storage, and installation operations. */
sealed interface PackageResult<out T> {
    /** Operation succeeded with [value]. */
    data class Success<out T>(val value: T) : PackageResult<T>

    /** Operation failed cleanly with typed [reason]. */
    data class Failure(val reason: PackageFailure) : PackageResult<Nothing>
}

/** Specific verification or format defect inside [PackageFailure.InvalidPackage]. */
enum class InvalidPackageCause {
    /** Embedded or supplied trusted keyring JSON or Tink key material is invalid. */
    INVALID_KEY_RING,

    /** ZIP container headers, offsets, flags, paths, or bounds violate the streaming format. */
    INVALID_CONTAINER,

    /** Ed25519 signature (`manifest.sig`) failed verification across all trusted keys. */
    INVALID_SIGNATURE,

    /** Signature verified under one trusted key, but `manifest.key_id` named a different key. */
    UNTRUSTED_KEY_ID,

    /** `manifest.json` failed JSON decoding, schema rules, provenance rules, or file list checks. */
    INVALID_MANIFEST,

    /** Valid manifest had a different [PackageKind] than requested by the caller. */
    UNEXPECTED_KIND,

    /** Extracted entry size, CRC-32, DEFLATE stream integrity, or SHA-256 digest mismatched. */
    PAYLOAD_INTEGRITY,

    /** `data/map.json` failed schema, coordinate, source, or credit checks. */
    INVALID_MAP_METADATA,

    /** A PMTiles map archive under `data/` failed header, binary directory, metadata, or Goal 9 checks. */
    INVALID_PMTILES,
}

/** UI-actionable failure states surfaced by `:core:content` without throwing expected exceptions. */
sealed interface PackageFailure {
    /** Trusted keyring contains zero keys (`{"keys": []}`); installation is blocked fail-closed. */
    data object KeyRingNotConfigured : PackageFailure

    /** Package requires at least [minAppVersion], newer than the installed [currentAppVersion]. */
    data class AppUpdateRequired(val minAppVersion: Int, val currentAppVersion: Int) : PackageFailure

    /** Candidate [candidateVersion] is not newer than [installedVersion] under an active trusted key. */
    data class DowngradeOrSameVersion(val installedVersion: Int, val candidateVersion: Int) : PackageFailure

    /** Map declares [count] `reconstructed_areas`, refused until disclosure UI ships. */
    data class UnsupportedReconstructedAreas(val count: Int) : PackageFailure

    /** Device storage lacks [requiredBytes] needed for safe staging/install ([availableBytes] free). */
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : PackageFailure

    /** Package archive or keyring failed integrity, cryptographic, or schema validation. */
    data class InvalidPackage(val cause: InvalidPackageCause, val detail: String) : PackageFailure

    /** Local filesystem I/O error unrelated to package integrity or out-of-space conditions. */
    data class IoFailure(val detail: String) : PackageFailure
}
