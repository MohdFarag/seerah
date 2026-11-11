package app.seerah.spike

import android.content.Context
import java.io.File

/**
 * The base map ships inside the APK for the spike, but MapLibre can only read
 * PMTiles from device storage, so the files are copied out once.
 */
object BaseMap {
    private val files = listOf("land.pmtiles", "relief.pmtiles")

    /** Returns the folder holding the base map, or null if it was not bundled. */
    fun prepare(context: Context): String? {
        val bundled = context.assets.list("")?.toSet().orEmpty()
        if (!bundled.containsAll(files)) return null
        val dir = File(context.filesDir, "base").apply { mkdirs() }
        files.forEach { name ->
            val target = File(dir, name)
            val size = context.assets.openFd(name).use { it.length }
            if (!target.exists() || target.length() != size) {
                // Written under a temporary name so an interrupted copy is never used.
                val partial = File(dir, "$name.part")
                context.assets.open(name).use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                partial.renameTo(target)
            }
        }
        return dir.absolutePath
    }
}
