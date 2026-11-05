package org.seerah.spike.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Local server with Range support that drops the connection once, mid-download. */
class UpdaterTest {
    private val bytes = Fixtures.v2.readBytes()
    private val cutOnce = AtomicBoolean(true)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/feed.json") { ex ->
            val body = """{"content_version":"1.1.0-test","url":"/pack.zip","size":${bytes.size},"sha256":"${PackStore.sha256(Fixtures.v2)}"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        createContext("/pack.zip") { ex ->
            val start = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val len = bytes.size - start
            if (start > 0) ex.responseHeaders.add("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
            ex.sendResponseHeaders(if (start > 0) 206 else 200, len.toLong())
            ex.responseBody.use { out ->
                if (cutOnce.getAndSet(false)) {
                    out.write(bytes, start, 300_000); out.flush()
                    ex.close() // simulated network loss
                    return@createContext
                }
                out.write(bytes, start, len)
            }
        }
        start()
    }
    private val feed = "http://127.0.0.1:${server.address.port}/feed.json"

    @AfterTest fun stop() = server.stop(0)

    @Test fun `interrupted download resumes and installs, keeping v1 until then`() {
        val root = Files.createTempDirectory("upd").toFile()
        val store = PackStore(File(root, "content")).apply { install(Fixtures.v1) }
        val updater = Updater(File(root, "downloads"), timeoutMs = 3_000)
        val offer = updater.fetchOffer(feed)
        assertTrue(updater.isAvailable(offer, store.activeVersion()))
        assertEquals(bytes.size.toLong(), offer.size)

        val first = updater.download(feed, offer)
        assertIs<DownloadResult.Interrupted>(first)
        assertEquals("1.0.0-test", store.activeVersion(), "nothing changes while the download is incomplete")

        val second = updater.download(feed, offer)
        assertIs<DownloadResult.Done>(second)
        assertIs<InstallResult.Installed>(store.install(second.file, offer.sha256))
        assertEquals("1.1.0-test", store.activeVersion())
        assertTrue(!updater.isAvailable(offer, store.activeVersion()))
    }
}
