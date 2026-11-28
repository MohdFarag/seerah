package app.seerah.content

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Ed25519VerifierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // RFC 8032 Section 7.1 TEST 1 (public vector + empty-message signature)
    private val rfcPub1Hex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private val rfcSig1Hex = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
        "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

    // RFC 8032 Section 7.1 TEST 2 (public vector + 1-byte message 0x72 signature)
    private val rfcPub2Hex = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
    private val rfcSig2Hex = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
        "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    private fun base64OfHex(hex: String): String = Base64.getEncoder().encodeToString(hexToBytes(hex))

    private fun keyJson(vararg entries: Pair<String, String>): String {
        val rendered = entries.joinToString(",") { (id, hex) ->
            """{"key_id":"$id","algorithm":"Ed25519","tink_variant":"NO_PREFIX","public_key_base64":"${base64OfHex(
                hex,
            )}"}"""
        }
        return """{"keys":[$rendered]}"""
    }

    @Test
    fun loadFromJson_handlesUnconfiguredValidAndAllInvalidShapes() {
        assertIs<KeyRingState.Unconfigured>(TrustedKeyRingLoader.loadFromJson("""{"keys":[]}"""))

        val valid = TrustedKeyRingLoader.loadFromJson(keyJson("rfc-1" to rfcPub1Hex, "rfc-2" to rfcPub2Hex))
        assertIs<KeyRingState.Configured>(valid)
        assertEquals(listOf("rfc-1", "rfc-2"), valid.keys.map { it.keyId })

        val hexMatched = """
            {"keys":[{"key_id":"k1","algorithm":"Ed25519","tink_variant":"NO_PREFIX",
            "public_key_base64":"${base64OfHex(rfcPub1Hex)}","public_key_hex":"$rfcPub1Hex"}]}
        """.trimIndent()
        assertIs<KeyRingState.Configured>(TrustedKeyRingLoader.loadFromJson(hexMatched))

        val invalidCases = listOf(
            "[]",
            """{"key_id":"k1"}""",
            """{"keys":[],"extra":1}""",
            keyJson("k1" to rfcPub1Hex, "k2" to rfcPub2Hex, "k3" to "00".repeat(32)),
            keyJson("same" to rfcPub1Hex, "same" to rfcPub2Hex),
            keyJson("k1" to rfcPub1Hex, "k2" to rfcPub1Hex),
            keyJson("BAD_ID" to rfcPub1Hex),
            """{"keys":[{"key_id":"k1","algorithm":"RSA","tink_variant":"NO_PREFIX","public_key_base64":"${base64OfHex(
                rfcPub1Hex,
            )}"}]}""",
            """{"keys":[{"key_id":"k1","algorithm":"Ed25519","tink_variant":"TINK","public_key_base64":"${base64OfHex(
                rfcPub1Hex,
            )}"}]}""",
            """{"keys":[{"key_id":"k1","algorithm":"Ed25519","tink_variant":"NO_PREFIX","public_key_base64":"${base64OfHex(
                rfcPub1Hex,
            ).trimEnd('=')}"}]}""",
            """{"keys":[{"key_id":"k1","algorithm":"Ed25519","tink_variant":"NO_PREFIX","public_key_base64":"${Base64.getEncoder().encodeToString(
                ByteArray(31),
            )}"}]}""",
            """{"keys":[{"key_id":"k1","algorithm":"Ed25519","tink_variant":"NO_PREFIX","public_key_base64":"${base64OfHex(
                rfcPub1Hex,
            )}","public_key_hex":null}]}""",
            """{"keys":[{"key_id":"k1","algorithm":"Ed25519","tink_variant":"NO_PREFIX","public_key_base64":"${base64OfHex(
                rfcPub1Hex,
            )}","public_key_hex":"${"00".repeat(32)}"}]}""",
            """{"keys":[{"key_id":"k1","algorithm":"Ed25519","tink_variant":"NO_PREFIX","public_key_base64":"${base64OfHex(
                rfcPub1Hex,
            )}","unknown":true}]}""",
        )
        for (bad in invalidCases) {
            assertIs<KeyRingState.Invalid>(TrustedKeyRingLoader.loadFromJson(bad), "Expected Invalid for: $bad")
        }
    }

    @Test
    fun verifier_matchesRfc8032VectorsAcrossDualKeySlots() {
        val ring = TrustedKeyRingLoader.loadFromJson(keyJson("rfc-2" to rfcPub2Hex, "rfc-1" to rfcPub1Hex))
        val verifier = IsolatedTinkEd25519Verifier { ring }

        val out1 = verifier.verify(hexToBytes(rfcSig1Hex), ByteArray(0))
        assertIs<VerifyOutcome.Verified>(out1)
        assertEquals("rfc-1", out1.verifiedKeyId)

        val out2 = verifier.verify(hexToBytes(rfcSig2Hex), byteArrayOf(0x72))
        assertIs<VerifyOutcome.Verified>(out2)
        assertEquals("rfc-2", out2.verifiedKeyId)

        val wrongMsg = verifier.verify(hexToBytes(rfcSig1Hex), byteArrayOf(0x01))
        val failed = assertIs<VerifyOutcome.Failed>(wrongMsg)
        val pkgErr = assertIs<PackageFailure.InvalidPackage>(failed.failure)
        assertEquals(InvalidPackageCause.INVALID_SIGNATURE, pkgErr.cause)

        val shortSig = verifier.verify(ByteArray(63), ByteArray(0))
        assertIs<VerifyOutcome.Failed>(shortSig)
    }

    @Test
    fun verifier_failsClosedWhenUnconfiguredOrInvalid() {
        val unconfigured = IsolatedTinkEd25519Verifier { KeyRingState.Unconfigured }
        val r1 = assertIs<VerifyOutcome.Failed>(unconfigured.verify(ByteArray(64), ByteArray(0)))
        assertIs<PackageFailure.KeyRingNotConfigured>(r1.failure)

        val invalid = IsolatedTinkEd25519Verifier { KeyRingState.Invalid("broken json") }
        val r2 = assertIs<VerifyOutcome.Failed>(invalid.verify(ByteArray(64), ByteArray(0)))
        val pkgErr = assertIs<PackageFailure.InvalidPackage>(r2.failure)
        assertEquals(InvalidPackageCause.INVALID_KEY_RING, pkgErr.cause)
    }

    @Test
    fun pipeline_neverInvokesManifestParserWhenSignatureFails() = runTest {
        val fixtureDir = tempFolder.newFolder("spy")
        val corruptUtf8Json = byteArrayOf(0xFF.toByte(), '{'.code.toByte(), 'b'.code.toByte())
        val zipBytes = TestArchiveBuilders.buildRawZip(
            listOf(
                TestArchiveBuilders.RawZipSpecEntry(
                    nameBytes = "manifest.json".toByteArray(StandardCharsets.US_ASCII),
                    payloadBytes = corruptUtf8Json,
                ),
                TestArchiveBuilders.RawZipSpecEntry(
                    nameBytes = "manifest.sig".toByteArray(StandardCharsets.US_ASCII),
                    payloadBytes = ByteArray(64) { 0x42 },
                ),
                TestArchiveBuilders.RawZipSpecEntry(
                    nameBytes = "data/index.json".toByteArray(StandardCharsets.US_ASCII),
                    payloadBytes = "{}".toByteArray(),
                ),
            ),
        )
        val zipFile = TestArchiveBuilders.writeTempBytes(fixtureDir, "bad-sig.zip", zipBytes)
        val ring = TrustedKeyRingLoader.loadFromJson(keyJson("rfc-1" to rfcPub1Hex))

        var parseInvocations = 0
        val pipeline = PackageVerificationPipeline(
            verifier = IsolatedTinkEd25519Verifier { ring },
            appVersionCodeProvider = object : AppVersionCodeProvider {
                override suspend fun currentVersionCode(): Long = 10L
            },
            manifestParser = { bytes, keyId, expected ->
                parseInvocations++
                PackageManifestParser.parseAndValidate(bytes, keyId, expected)
            },
        )

        val result = pipeline.verifyAndExtract(zipFile, File(fixtureDir, "staging"))
        val failure = assertIs<PackageResult.Failure>(result)
        val invalid = assertIs<PackageFailure.InvalidPackage>(failure.reason)
        assertEquals(InvalidPackageCause.INVALID_SIGNATURE, invalid.cause)
        assertEquals(0, parseInvocations, "Manifest parser must NEVER run before Ed25519 verification.")
    }

    @Test
    fun packageManifestParser_rejectsKeyIdMismatchBetweenVerifierAndManifest() {
        val raw = """
            {
              "kind": "content",
              "version": 1,
              "min_app_version": 1,
              "key_id": "key-two",
              "source_commit": "${"a".repeat(40)}",
              "created": "2026-01-01T00:00:00Z",
              "files": [{"path": "data/a.json", "size": 2, "sha256": "${sha256Hex("{}".toByteArray())}"}]
            }
        """.trimIndent().toByteArray()

        val result = PackageManifestParser.parseAndValidate(raw, verifiedKeyId = "key-one")
        val failure = assertIs<PackageResult.Failure>(result)
        val invalid = assertIs<PackageFailure.InvalidPackage>(failure.reason)
        assertEquals(InvalidPackageCause.UNTRUSTED_KEY_ID, invalid.cause)
        assertTrue("key-one" in invalid.detail && "key-two" in invalid.detail)
    }
}
