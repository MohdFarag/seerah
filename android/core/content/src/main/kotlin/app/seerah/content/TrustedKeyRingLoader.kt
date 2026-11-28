package app.seerah.content

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

private const val MAX_TRUSTED_KEYS = 2
private const val ED25519_PUBLIC_KEY_BYTES = 32
private const val EXPECTED_ALGORITHM = "Ed25519"
private const val EXPECTED_TINK_VARIANT = "NO_PREFIX"
private val KEY_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

/** Strictly parses `trusted_package_keys.json` (`{"keys": [...]}` only) into [KeyRingState]. */
internal object TrustedKeyRingLoader {
    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

    @Serializable
    private data class KeyRingRootDto(val keys: List<KeyEntryDto>)

    @Serializable
    private data class KeyEntryDto(
        @SerialName("key_id")
        val keyId: String,
        val algorithm: String,
        @SerialName("tink_variant")
        val tinkVariant: String,
        @SerialName("public_key_base64")
        val publicKeyBase64: String,
        @SerialName("public_key_hex")
        val publicKeyHex: JsonPrimitive? = null,
    )

    fun loadFromBytes(rawBytes: ByteArray): KeyRingState {
        val text = decodeStrictUtf8(rawBytes)
            ?: return KeyRingState.Invalid("Trusted keyring JSON is not valid UTF-8.")
        return loadFromJson(text)
    }

    fun loadFromJson(rawJson: String): KeyRingState {
        val rootElement = try {
            strictJson.parseToJsonElement(rawJson)
        } catch (exc: SerializationException) {
            return KeyRingState.Invalid("Invalid trusted keyring JSON: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            return KeyRingState.Invalid("Invalid trusted keyring JSON: ${exc.message}")
        }
        if (rootElement !is JsonObject) {
            return KeyRingState.Invalid("Trusted keyring root must be a JSON object {'keys': [...]}.")
        }

        val dto = try {
            strictJson.decodeFromJsonElement<KeyRingRootDto>(rootElement)
        } catch (exc: SerializationException) {
            return KeyRingState.Invalid("Trusted keyring schema mismatch: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            return KeyRingState.Invalid("Trusted keyring schema mismatch: ${exc.message}")
        }

        if (dto.keys.isEmpty()) return KeyRingState.Unconfigured
        if (dto.keys.size > MAX_TRUSTED_KEYS) {
            return KeyRingState.Invalid(
                "Trusted keyring supports at most $MAX_TRUSTED_KEYS keys, got ${dto.keys.size}.",
            )
        }

        val rawKeys = rootElement["keys"] as? JsonArray
            ?: return KeyRingState.Invalid("Trusted keyring 'keys' must be a JSON array.")
        return parseAndValidateKeys(rawKeys, dto.keys)
    }

    private fun parseAndValidateKeys(rawKeys: JsonArray, entries: List<KeyEntryDto>): KeyRingState {
        val seenIds = LinkedHashSet<String>(entries.size)
        val seenDigests = LinkedHashSet<String>(entries.size)
        val keys = ArrayList<TrustedPublicKey>(entries.size)

        for (i in entries.indices) {
            val rawObj = rawKeys[i] as? JsonObject
                ?: return KeyRingState.Invalid("Keyring entry $i must be a JSON object.")
            if ("public_key_hex" in rawObj && rawObj["public_key_hex"] is JsonNull) {
                return KeyRingState.Invalid("Optional public_key_hex must be a string when present.")
            }
            val entry = entries[i]
            val parsed = parseSingleEntry(entry)
                ?: return KeyRingState.Invalid(explainEntryError(entry))
            if (!seenIds.add(parsed.keyId)) {
                return KeyRingState.Invalid("Duplicate key_id '${parsed.keyId}' in trusted keyring.")
            }
            if (!seenDigests.add(parsed.publicKeySha256)) {
                return KeyRingState.Invalid("Duplicate public key material for key_id '${parsed.keyId}'.")
            }
            keys.add(parsed)
        }
        return KeyRingState.Configured(keys)
    }

    private fun parseSingleEntry(entry: KeyEntryDto): TrustedPublicKey? {
        if (!KEY_ID_REGEX.matches(entry.keyId)) return null
        if (entry.algorithm != EXPECTED_ALGORITHM) return null
        if (entry.tinkVariant != EXPECTED_TINK_VARIANT) return null

        val rawBytes = try {
            Base64.getDecoder().decode(entry.publicKeyBase64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (rawBytes.size != ED25519_PUBLIC_KEY_BYTES) return null
        val canonicalBase64 = Base64.getEncoder().encodeToString(rawBytes)
        if (canonicalBase64 != entry.publicKeyBase64) return null

        val hexPrimitive = entry.publicKeyHex
        if (hexPrimitive != null) {
            if (hexPrimitive is JsonNull || !hexPrimitive.isString) return null
            val hex = hexPrimitive.content
            if (!isLowerHex(hex, SHA256_HEX_LENGTH)) return null
            if (hex != rawBytes.toLowerHex()) return null
        }

        return TrustedPublicKey(
            keyId = entry.keyId,
            rawPublicKeyBytes = rawBytes,
            publicKeySha256 = sha256Hex(rawBytes),
        )
    }

    private fun explainEntryError(entry: KeyEntryDto): String = when {
        !KEY_ID_REGEX.matches(entry.keyId) -> "Invalid key_id '${entry.keyId}' in trusted keyring."

        entry.algorithm != EXPECTED_ALGORITHM -> "Unsupported algorithm '${entry.algorithm}' for '${entry.keyId}'."

        entry.tinkVariant != EXPECTED_TINK_VARIANT ->
            "Unsupported tink_variant '${entry.tinkVariant}' for '${entry.keyId}'."

        entry.publicKeyHex != null && (entry.publicKeyHex is JsonNull || !entry.publicKeyHex.isString) ->
            "Optional public_key_hex must be a string when present for '${entry.keyId}'."

        else -> "Invalid Ed25519 public key encoding or hex mismatch for '${entry.keyId}'."
    }
}
