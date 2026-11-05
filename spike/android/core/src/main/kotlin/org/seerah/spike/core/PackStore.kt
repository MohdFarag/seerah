package org.seerah.spike.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@Serializable
data class Manifest(
    val format: Int,
    @SerialName("content_version") val contentVersion: String,
    val test: Boolean = false,
    val files: List<ManifestFile>,
)

@Serializable
data class ManifestFile(val path: String, val size: Long, val sha256: String)

sealed interface InstallResult {
    data class Installed(val version: String, val previous: String?) : InstallResult
    data class Rejected(val reason: String) : InstallResult
}

/**
 * Content packages on disk, independent of the app version and of user progress.
 *
 * ```
 * root/
 *   packs/<version>/   verified, extracted packages (immutable once activated)
 *   active             name of the active version (switched atomically)
 *   previous           last known-good version, used if the active one is unusable
 *   staging-*          in-progress extraction (deleted on failure or restart)
 * ```
 *
 * A package becomes active only after every file matches the manifest, the content parses
 * and validates, and its version is newer. The switch is a single atomic rename, so a crash
 * at any step leaves the old package active.
 */
class PackStore(private val root: File) {
    private val packs = File(root, "packs")
    private val activeFile = File(root, "active")
    private val previousFile = File(root, "previous")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        packs.mkdirs()
        root.listFiles { f -> f.name.startsWith("staging-") }?.forEach { it.deleteRecursively() }
    }

    /** Directory of the usable active package, falling back to the previous one if needed. */
    fun activeDir(): File? {
        for (version in listOfNotNull(read(activeFile), read(previousFile))) {
            val dir = File(packs, version)
            if (isIntact(dir)) {
                if (version != read(activeFile)) writeAtomic(activeFile, version) // self-heal
                return dir
            }
        }
        return null
    }

    fun activeVersion(): String? = activeDir()?.name

    fun install(zip: File, expectedSha256: String? = null): InstallResult {
        if (expectedSha256 != null) {
            val actual = sha256(zip)
            if (!actual.equals(expectedSha256, ignoreCase = true)) return InstallResult.Rejected("package checksum mismatch")
        }
        val staging = Files.createTempDirectory(root.toPath(), "staging-").toFile()
        try {
            unzipStrict(zip, staging)
            val manifest = runCatching {
                json.decodeFromString(Manifest.serializer(), File(staging, "manifest.json").readText())
            }.getOrElse { return InstallResult.Rejected("unreadable manifest: ${it.message}") }
            if (manifest.format != ContentPack.SUPPORTED_FORMAT) return InstallResult.Rejected("unsupported package format ${manifest.format}")
            verifyFiles(staging, manifest)?.let { return InstallResult.Rejected(it) }
            val content = runCatching { ContentPack.parse(File(staging, "content.json").readText()) }
                .getOrElse { return InstallResult.Rejected("unreadable content: ${it.message}") }
            if (content.contentVersion != manifest.contentVersion) return InstallResult.Rejected("manifest/content version mismatch")
            content.validate().takeIf { it.isNotEmpty() }?.let { return InstallResult.Rejected("invalid content: $it") }

            val current = activeVersion()
            if (current != null && Versions.compare(manifest.contentVersion, current) <= 0) {
                return InstallResult.Rejected("not newer than active $current")
            }
            val target = File(packs, manifest.contentVersion)
            target.deleteRecursively()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            current?.let { writeAtomic(previousFile, it) }
            writeAtomic(activeFile, manifest.contentVersion) // <- the commit point
            prune(keep = setOfNotNull(manifest.contentVersion, current))
            return InstallResult.Installed(manifest.contentVersion, current)
        } catch (e: IOException) {
            return InstallResult.Rejected("io error: ${e.message}")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun isIntact(dir: File): Boolean {
        val mf = File(dir, "manifest.json").takeIf { it.isFile } ?: return false
        val manifest = runCatching { json.decodeFromString(Manifest.serializer(), mf.readText()) }.getOrNull() ?: return false
        // Startup check is size-only (fast); full hashes are verified once, at install time.
        return manifest.files.all { File(dir, it.path).let { f -> f.isFile && f.length() == it.size } }
    }

    private fun verifyFiles(dir: File, manifest: Manifest): String? {
        val listed = manifest.files.map { it.path }.toSet()
        val present = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet() - "manifest.json"
        if (present != listed) return "files differ from manifest: extra=${present - listed} missing=${listed - present}"
        manifest.files.forEach { f ->
            val file = File(dir, f.path)
            if (file.length() != f.size) return "size mismatch: ${f.path}"
            if (!sha256(file).equals(f.sha256, ignoreCase = true)) return "checksum mismatch: ${f.path}"
        }
        return null
    }

    /** Extract rejecting path traversal and duplicate entries (a duplicate could shadow a verified file). */
    private fun unzipStrict(zip: File, into: File) {
        val base = into.canonicalFile
        val seen = HashSet<String>()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!seen.add(entry.name)) throw IOException("duplicate zip entry ${entry.name}")
                val out = File(base, entry.name).canonicalFile
                if (!out.path.startsWith(base.path + File.separator)) throw IOException("bad zip path ${entry.name}")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    private fun prune(keep: Set<String>) {
        packs.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    private fun read(f: File): String? = f.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    private fun writeAtomic(f: File, value: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(value)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** SemVer-ish ordering of `MAJOR.MINOR.PATCH[-tag]`; tags compare as strings. */
object Versions {
    fun compare(a: String, b: String): Int {
        val (an, at) = split(a)
        val (bn, bt) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = (an.getOrElse(i) { 0 }).compareTo(bn.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return at.compareTo(bt)
    }

    private fun split(v: String): Pair<List<Int>, String> {
        val core = v.substringBefore('-')
        return core.split('.').map { it.toIntOrNull() ?: 0 } to v.substringAfter('-', "")
    }
}
