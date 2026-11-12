package app.seerah.spike

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

/**
 * Content files (route, waypoints, time states) and their updates.
 *
 * The app starts with the bundled copy. An update is downloaded to a `.part`
 * file that survives interruption and resumes from where it stopped, checked
 * against the manifest's SHA-256, unpacked into a new folder, and only then
 * made current by rewriting one small pointer file. Until that last step the
 * working version is untouched.
 */
object Content {
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/MohdFarag/seerah/claude/relaxed-knuth-gi0alj/map-data/updates/manifest.json"

    /** Spike only: slows the download so it can be interrupted by hand. */
    private const val THROTTLE_BYTES = 256
    private const val THROTTLE_MS = 200L

    private fun root(c: Context) = File(c.filesDir, "content").apply { mkdirs() }
    private fun pointer(c: Context) = File(root(c), "current")

    /** The installed update's folder, or null when running on the bundled content. */
    fun activeDir(c: Context): File? =
        pointer(c).takeIf { it.exists() }?.readText()?.trim()?.let { File(root(c), it) }?.takeIf { it.isDirectory }

    fun version(c: Context): Int = activeDir(c)?.let { File(it, "version.txt").readText().trim().toInt() } ?: 1

    fun read(c: Context, name: String): String =
        activeDir(c)?.let { File(it, name) }?.takeIf { it.exists() }?.readText()
            ?: c.assets.open(name).bufferedReader().use { it.readText() }

    /** A URL MapLibre can load. */
    fun url(c: Context, name: String): String =
        activeDir(c)?.let { File(it, name) }?.takeIf { it.exists() }?.let { "file://${it.absolutePath}" } ?: "asset://$name"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Verifying : State
        data class Paused(val done: Long, val total: Long) : State
        data class UpToDate(val version: Int) : State
        data class Installed(val version: Int) : State
        data class Failed(val message: String) : State
    }

    /** A download interrupted earlier (even by closing the app), as a paused state, or null. */
    fun pending(c: Context): State.Paused? {
        val part = root(c).listFiles { f -> f.name.endsWith(".part") }?.firstOrNull() ?: return null
        val total = File(root(c), part.name.removeSuffix(".part") + ".size").takeIf { it.exists() }
            ?.readText()?.trim()?.toLongOrNull() ?: return null
        return State.Paused(part.length(), total)
    }

    suspend fun update(c: Context, report: (State) -> Unit) {
        report(State.Checking)
        val manifest = JSONObject(URL(MANIFEST_URL).readText())
        val version = manifest.getInt("version")
        if (version <= version(c)) return report(State.UpToDate(version(c)))
        val total = manifest.getLong("size")
        val part = File(root(c), "content-$version.part")
        File(root(c), "content-$version.size").writeText(total.toString())

        // Resume from what is already on disk.
        while (part.length() < total) {
            val conn = URL(manifest.getString("url")).openConnection() as HttpURLConnection
            if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            conn.connect()
            val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            RandomAccessFile(part, "rw").use { out ->
                if (!resumed) out.setLength(0)
                out.seek(out.length())
                conn.inputStream.use { input ->
                    val buf = ByteArray(THROTTLE_BYTES)
                    while (true) {
                        if (!coroutineContext.isActive) {
                            report(State.Paused(out.length(), total))
                            return
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        report(State.Downloading(out.length(), total))
                        delay(THROTTLE_MS)
                    }
                }
            }
        }

        report(State.Verifying)
        val sha = MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
        if (sha != manifest.getString("sha256")) {
            part.delete()
            return report(State.Failed("الملف تالف؛ سيُعاد تنزيله"))
        }
        val staging = File(root(c), "v$version.tmp").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(part.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                File(staging, File(e.name).name).outputStream().use { zip.copyTo(it) }
            }
        }
        val target = File(root(c), "v$version").apply { deleteRecursively() }
        staging.renameTo(target)
        // The single switching step: the pointer is replaced atomically.
        val tmp = File(root(c), "current.tmp").apply { writeText(target.name) }
        tmp.renameTo(pointer(c))
        part.delete()
        File(root(c), "content-$version.size").delete()
        report(State.Installed(version))
    }
}
