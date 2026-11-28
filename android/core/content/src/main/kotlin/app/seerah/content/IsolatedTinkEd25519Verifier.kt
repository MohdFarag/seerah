package app.seerah.content

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig2026
import com.google.crypto.tink.util.Bytes
import java.security.GeneralSecurityException

/**
 * Isolated single-key Google Tink Ed25519 `NO_PREFIX` verifier backed by a lazy keyring provider.
 *
 * Each configured trusted key resides in its own single-key [KeysetHandle] so signature verification
 * uniquely identifies which trusted key verified `manifest.sig`, matching `tools/packages/` `signing.py`.
 */
internal class IsolatedTinkEd25519Verifier(private val keyRingProvider: () -> KeyRingState) : Ed25519Verifier {
    private data class VerifiedSlot(val keyId: String, val publicKeySha256: String, val verifier: PublicKeyVerify)

    private sealed interface PreparedState {
        data object Unconfigured : PreparedState
        data class Configured(val slots: List<VerifiedSlot>) : PreparedState
        data class Invalid(val detail: String) : PreparedState
    }

    private data class CachedPrepared(val sourceState: KeyRingState, val prepared: PreparedState)

    private val lock = Any()

    @Volatile
    private var cached: CachedPrepared? = null

    override fun verify(rawSignature: ByteArray, rawManifestBytes: ByteArray): VerifyOutcome {
        val prepared = ensurePrepared()
        return when (prepared) {
            PreparedState.Unconfigured -> VerifyOutcome.Failed(PackageFailure.KeyRingNotConfigured)

            is PreparedState.Invalid ->
                VerifyOutcome.Failed(
                    PackageFailure.InvalidPackage(InvalidPackageCause.INVALID_KEY_RING, prepared.detail),
                )

            is PreparedState.Configured -> verifyWithSlots(prepared.slots, rawSignature, rawManifestBytes)
        }
    }

    private fun ensurePrepared(): PreparedState {
        val currentRing = keyRingProvider()
        cached?.let { existing ->
            if (existing.sourceState == currentRing) return existing.prepared
        }
        return synchronized(lock) {
            val rechecked = cached
            if (rechecked != null && rechecked.sourceState == currentRing) {
                rechecked.prepared
            } else {
                val built = buildPrepared(currentRing)
                cached = CachedPrepared(currentRing, built)
                built
            }
        }
    }

    private fun buildPrepared(state: KeyRingState): PreparedState = when (state) {
        KeyRingState.Unconfigured -> PreparedState.Unconfigured

        is KeyRingState.Invalid -> PreparedState.Invalid(state.detail)

        is KeyRingState.Configured -> {
            if (state.keys.isEmpty()) {
                PreparedState.Unconfigured
            } else {
                buildTinkSlots(state.keys)
            }
        }
    }

    private fun buildTinkSlots(keys: List<TrustedPublicKey>): PreparedState = try {
        val config = SignatureConfig2026.get()
        val slots = keys.map { trustedKey ->
            val rawBytes = trustedKey.copyBytes()
            val ed25519Key = Ed25519PublicKey.create(
                Ed25519Parameters.Variant.NO_PREFIX,
                Bytes.copyFrom(rawBytes),
                null,
            )
            val handle = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(ed25519Key).withFixedId(1).makePrimary())
                .build()
            val primitive = handle.getPrimitive(config, PublicKeyVerify::class.java)
            VerifiedSlot(
                keyId = trustedKey.keyId,
                publicKeySha256 = trustedKey.publicKeySha256,
                verifier = primitive,
            )
        }
        PreparedState.Configured(slots)
    } catch (exc: GeneralSecurityException) {
        PreparedState.Invalid("Failed to initialize Tink Ed25519 public key: ${exc.message}")
    } catch (exc: IllegalArgumentException) {
        PreparedState.Invalid("Invalid Tink Ed25519 key material: ${exc.message}")
    }

    private fun verifyWithSlots(
        slots: List<VerifiedSlot>,
        rawSignature: ByteArray,
        rawManifestBytes: ByteArray,
    ): VerifyOutcome {
        if (rawSignature.size != RAW_ED25519_SIGNATURE_BYTES) {
            return VerifyOutcome.Failed(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.INVALID_SIGNATURE,
                    "manifest.sig must be $RAW_ED25519_SIGNATURE_BYTES bytes, got ${rawSignature.size}.",
                ),
            )
        }
        for (slot in slots) {
            try {
                slot.verifier.verify(rawSignature, rawManifestBytes)
                return VerifyOutcome.Verified(
                    verifiedKeyId = slot.keyId,
                    verifiedPublicKeySha256 = slot.publicKeySha256,
                )
            } catch (_: GeneralSecurityException) {
                // Mismatch on this isolated key slot; try the next trusted key if present.
            }
        }
        return VerifyOutcome.Failed(
            PackageFailure.InvalidPackage(
                InvalidPackageCause.INVALID_SIGNATURE,
                "manifest.sig failed Ed25519 verification against all trusted keys.",
            ),
        )
    }
}
