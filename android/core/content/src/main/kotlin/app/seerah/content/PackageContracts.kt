package app.seerah.content

import app.seerah.model.MapMetadata
import java.io.File
import kotlinx.coroutines.flow.StateFlow

private const val ED25519_PUBLIC_KEY_BYTES = 32

/**
 * Validated Ed25519 `NO_PREFIX` public key entry from the embedded trusted keyring.
 *
 * @property keyId Canonical lowercase key identifier (`^[a-z0-9][a-z0-9-]{0,63}$`).
 * @property publicKeySha256 Lowercase 64-hex SHA-256 digest of the raw 32-byte key.
 */
class TrustedPublicKey(val keyId: String, rawPublicKeyBytes: ByteArray, val publicKeySha256: String) {
    private val keyBytes: ByteArray = rawPublicKeyBytes.copyOf()

    init {
        require(keyId.isNotBlank()) { "TrustedPublicKey keyId must not be blank." }
        require(rawPublicKeyBytes.size == ED25519_PUBLIC_KEY_BYTES) {
            "TrustedPublicKey requires $ED25519_PUBLIC_KEY_BYTES raw bytes, got ${rawPublicKeyBytes.size}."
        }
        require(isLowerHex(publicKeySha256, SHA256_HEX_LENGTH)) {
            "TrustedPublicKey publicKeySha256 must be 64 lowercase hex characters."
        }
    }

    /** Returns a defensive copy of the 32-byte raw Ed25519 public key. */
    fun copyBytes(): ByteArray = keyBytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedPublicKey) return false
        return keyId == other.keyId &&
            publicKeySha256 == other.publicKeySha256 &&
            keyBytes.contentEquals(other.keyBytes)
    }

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + publicKeySha256.hashCode()
        result = 31 * result + keyBytes.contentHashCode()
        return result
    }

    override fun toString(): String = "TrustedPublicKey(keyId='$keyId', publicKeySha256='$publicKeySha256')"
}

/** Loaded state of the embedded `trusted_package_keys.json` keyring. */
sealed interface KeyRingState {
    /** Valid pre-key-ceremony placeholder `{"keys": []}` that fails closed on verification. */
    data object Unconfigured : KeyRingState

    /** Valid keyring holding `1..2` trusted Ed25519 public keys. */
    data class Configured(val keys: List<TrustedPublicKey>) : KeyRingState

    /** Keyring JSON, encoding, key count, or Tink key construction failed validation. */
    data class Invalid(val detail: String) : KeyRingState
}

/** Result of isolated single-key Ed25519 verification over raw `manifest.json` bytes. */
sealed interface VerifyOutcome {
    /** Signature verified against the single isolated key [verifiedKeyId] ([verifiedPublicKeySha256]). */
    data class Verified(val verifiedKeyId: String, val verifiedPublicKeySha256: String) : VerifyOutcome

    /** Signature verification did not succeed. */
    data class Failed(val failure: PackageFailure) : VerifyOutcome
}

/** Verifies raw 64-byte `manifest.sig` bytes against raw `manifest.json` bytes before parsing JSON. */
interface Ed25519Verifier {
    /** Verifies [rawSignature] over [rawManifestBytes] across isolated trusted key slots. */
    fun verify(rawSignature: ByteArray, rawManifestBytes: ByteArray): VerifyOutcome
}

/** Cold-start-safe state of an installed package slot (`content` or `map`). */
sealed interface InstalledPackageState {
    /** Initial state before disk hydration completes off the main thread. */
    data object Loading : InstalledPackageState

    /** No valid package is currently installed for this kind. */
    data object NotInstalled : InstalledPackageState

    /** A valid installed package [info] is ready for offline use. */
    data class Installed(val info: InstalledPackageInfo) : InstalledPackageState
}

/** Exposes active installed packages and reads validated active map metadata off the main thread. */
interface PackageStorage {
    /** Observes the live [InstalledPackageState] for [kind]. */
    fun observeInstalled(kind: PackageKind): StateFlow<InstalledPackageState>

    /** Returns the active [InstalledPackageInfo] for [kind] after disk hydration, or null. */
    suspend fun currentInstalled(kind: PackageKind): InstalledPackageInfo?

    /** Reads and validates `data/map.json` and referenced map files for active [info]. */
    suspend fun readActiveMapMetadata(info: InstalledPackageInfo): PackageResult<MapMetadata>
}

/** Performs locked maintenance sweeps over orphaned staging or old version directories. */
interface PackageStorageMaintenance {
    /** Deletes staging leftovers and inactive version folders safely under the storage lock. */
    suspend fun cleanupOrphanedFiles(): PackageResult<Unit>
}

/** Verifies, unpacks, and atomically activates local signed `.zip` packages. */
interface PackageInstaller {
    /**
     * Installs a signed package from local readable [archiveFile], optionally enforcing [expectedKind].
     */
    suspend fun install(archiveFile: File, expectedKind: PackageKind? = null): PackageResult<InstalledPackageInfo>
}

/** Abstracts filesystem free-space lookup so tests run without Android `StatFs`. */
interface AvailableSpaceProvider {
    /** Returns available bytes usable by the app inside [directory]. */
    fun availableBytes(directory: File): Long
}

/** Abstracts app `versionCode` lookup off the main thread without direct `BuildConfig` coupling. */
interface AppVersionCodeProvider {
    /** Returns the installed Android app long version code. */
    suspend fun currentVersionCode(): Long
}

/** Abstracts warning logs so plain-JVM tests avoid Android `android.util.Log` stub crashes. */
interface PackageStorageLogger {
    /** Logs a non-fatal recovery or cleanup warning. */
    fun warn(tag: String, message: String, throwable: Throwable? = null)
}
