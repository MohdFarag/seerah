package org.seerah.spike.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackStoreTest {
    private fun tmp(): File = Files.createTempDirectory("packstore").toFile()

    @Test fun `install v1 then update to v2`() {
        val root = tmp()
        val store = PackStore(root)
        assertIs<InstallResult.Installed>(store.install(Fixtures.v1))
        assertEquals("1.0.0-test", store.activeVersion())
        val r = store.install(Fixtures.v2, PackStore.sha256(Fixtures.v2))
        assertEquals(InstallResult.Installed("1.1.0-test", "1.0.0-test"), r)
        val content = ContentPack.parse(File(store.activeDir(), "content.json").readText())
        assertNotNull(content.event("e4"), "update added an event")
        assertTrue(StyleBuilder.build(store.activeDir()!!).contains("file://${store.activeDir()!!.absolutePath}/basemap.pmtiles"))
    }

    @Test fun `corrupted package is rejected and the working one stays active`() {
        val store = PackStore(tmp())
        store.install(Fixtures.v1)
        val r = store.install(Fixtures.corrupt)
        assertIs<InstallResult.Rejected>(r)
        assertEquals("1.0.0-test", store.activeVersion())
    }

    @Test fun `wrong announced checksum is rejected`() {
        val store = PackStore(tmp())
        assertIs<InstallResult.Rejected>(store.install(Fixtures.v1, "00".repeat(32)))
    }

    @Test fun `older or same version is not installed over newer`() {
        val store = PackStore(tmp())
        store.install(Fixtures.v2)
        assertIs<InstallResult.Rejected>(store.install(Fixtures.v1))
        assertEquals("1.1.0-test", store.activeVersion())
    }

    @Test fun `damaged active package falls back to previous`() {
        val root = tmp()
        val store = PackStore(root)
        store.install(Fixtures.v1)
        store.install(Fixtures.v2)
        File(root, "packs/1.1.0-test/basemap.pmtiles").writeText("truncated")
        assertEquals("1.0.0-test", PackStore(root).activeVersion())
    }

    @Test fun `leftover staging from a crash is cleaned and old package still active`() {
        val root = tmp()
        PackStore(root).install(Fixtures.v1)
        File(root, "staging-123").mkdirs()
        File(root, "staging-123/half.bin").writeText("x")
        val store = PackStore(root)
        assertTrue(!File(root, "staging-123").exists())
        assertEquals("1.0.0-test", store.activeVersion())
    }

    @Test fun `versions order`() {
        assertTrue(Versions.compare("1.10.0", "1.9.9") > 0)
        assertTrue(Versions.compare("1.1.0-test", "1.0.0-test") > 0)
        assertEquals(0, Versions.compare("1.0.0-test", "1.0.0-test"))
    }
}
