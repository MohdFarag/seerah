package app.seerah.content

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentAndroidBindingsTest {
    private val rfc8032PublicKeysHex = setOf(
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
    )

    private val forbiddenTestKeyIdPrefix = Regex("^(?:test|rfc8032|dev|debug)(?:$|[-_])", RegexOption.IGNORE_CASE)

    @Test
    fun mainTrustedPackageKeysResource_containsZeroTestOrRfc8032Keys() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = RawResourceKeyRingProvider(context).currentKeyRing()

        // Pre-ceremony placeholder is Unconfigured (`{"keys": []}`); post-ceremony is Configured.
        assertFalse(state is KeyRingState.Invalid, "res/raw/trusted_package_keys.json must never be Invalid.")
        if (state is KeyRingState.Configured) {
            for (key in state.keys) {
                assertFalse(
                    forbiddenTestKeyIdPrefix.containsMatchIn(key.keyId),
                    "Forbidden test/dev/debug key_id '${key.keyId}' in main res/raw.",
                )
                assertFalse(
                    key.copyBytes().toLowerHex() in rfc8032PublicKeysHex,
                    "Forbidden RFC 8032 test vector public key in main res/raw.",
                )
            }
        }
    }

    @Test
    fun statFsVersionProviderAndLogger_workUnderRobolectric() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDir = File(context.filesDir, "content").apply { mkdirs() }

        val spaceProvider = StatFsAvailableSpaceProvider()
        assertTrue(spaceProvider.availableBytes(targetDir) >= 0L)

        val versionProvider = AndroidAppVersionCodeProvider(context, Dispatchers.IO)
        assertTrue(versionProvider.currentVersionCode() >= 0L)

        val logger = AndroidPackageStorageLogger()
        logger.warn("ContentTest", "Smoke log check", null)

        val keyRing = RawResourceKeyRingProvider(context).currentKeyRing()
        assertIs<KeyRingState.Unconfigured>(keyRing)
        assertEquals(KeyRingState.Unconfigured, keyRing)
    }
}
