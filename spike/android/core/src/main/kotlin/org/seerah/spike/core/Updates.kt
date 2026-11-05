package org.seerah.spike.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI

/** What the update feed offers. The app shows version and size; the user starts the download. */
@Serializable
data class UpdateOffer(
    @SerialName("content_version") val contentVersion: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

sealed interface DownloadResult {
    data class Done(val file: File) : DownloadResult
    /** Connection lost; the partial file is kept so the next attempt resumes. */
    data class Interrupted(val bytes: Long, val reason: String) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

class Updater(private val workDir: File, private val timeoutMs: Int = 15_000) {
    init { workDir.mkdirs() }

    fun fetchOffer(feedUrl: String): UpdateOffer {
        val c = open(feedUrl)
        try {
            if (c.responseCode != 200) throw IOException("feed HTTP ${c.responseCode}")
            return ContentPack.json.decodeFromString(UpdateOffer.serializer(), c.inputStream.reader().readText())
        } finally { c.disconnect() }
    }

    fun isAvailable(offer: UpdateOffer, activeVersion: String?): Boolean =
        activeVersion == null || Versions.compare(offer.contentVersion, activeVersion) > 0

    /** Download [offer] into workDir, resuming a previous partial download via HTTP Range. */
    fun download(feedUrl: String, offer: UpdateOffer, onProgress: (Long, Long) -> Unit = { _, _ -> }): DownloadResult {
        val url = URI(feedUrl).resolve(offer.url).toString()
        val part = File(workDir, "${offer.contentVersion}.zip.part")
        val done = File(workDir, "${offer.contentVersion}.zip")
        if (done.isFile && done.length() == offer.size) return DownloadResult.Done(done)
        if (part.length() > offer.size) part.delete()
        val start = part.length()
        val c = open(url).apply { if (start > 0) setRequestProperty("Range", "bytes=$start-") }
        try {
            val code = c.responseCode
            val append = when {
                code == 206 && start > 0 -> true
                code == 200 -> false // server ignored Range: restart from zero
                else -> return DownloadResult.Failed("HTTP $code")
            }
            var written = if (append) start else 0L
            RandomAccessFile(part, "rw").use { raf ->
                if (!append) raf.setLength(0)
                raf.seek(written)
                val buf = ByteArray(1 shl 16)
                c.inputStream.use { input ->
                    while (true) {
                        val n = try { input.read(buf) } catch (e: IOException) {
                            return DownloadResult.Interrupted(written, e.message ?: "read failed")
                        }
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        written += n
                        onProgress(written, offer.size)
                    }
                }
            }
            if (written < offer.size) return DownloadResult.Interrupted(written, "connection closed early")
            if (written > offer.size) { part.delete(); return DownloadResult.Failed("larger than announced") }
            if (!PackStore.sha256(part).equals(offer.sha256, ignoreCase = true)) {
                part.delete()
                return DownloadResult.Failed("checksum mismatch")
            }
            part.renameTo(done)
            return DownloadResult.Done(done)
        } catch (e: IOException) {
            return DownloadResult.Interrupted(part.length(), e.message ?: "io error")
        } finally { c.disconnect() }
    }

    private fun open(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
        }
}
