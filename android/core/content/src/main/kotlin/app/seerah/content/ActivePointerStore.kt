package app.seerah.content

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val ACTIVE_POINTER_SCHEMA = 1
private const val MAX_PACKAGE_VERSION = 1_000_000
private val ACTIVE_DIR_REGEX = Regex("^v[1-9][0-9]*-g[1-9][0-9]*$")
private val GENERATION_SCAN_REGEX = Regex("^v[1-9][0-9]*-g([1-9][0-9]*)$")
private val KEY_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

/** Atomic pointer record persisted in `<filesDir>/content/<kind>/active.json`. */
@Serializable
internal data class ActivePointer(
    val schema: Int,
    val version: Int,
    val generation: Long,
    @SerialName("key_id")
    val keyId: String,
    @SerialName("public_key_sha256")
    val publicKeySha256: String,
    @SerialName("active_dir")
    val activeDir: String,
)

internal object ActivePointerStore {
    internal const val ACTIVE_FILE_NAME = "active.json"
    internal const val ACTIVE_TEMP_FILE_NAME = "active.json.tmp"

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

    fun readValidated(kindDir: File): ActivePointer? {
        val activeFile = File(kindDir, ACTIVE_FILE_NAME)
        if (!activeFile.isFile) return null
        val bytes = try {
            activeFile.readBytes()
        } catch (_: IOException) {
            return null
        }
        val text = decodeStrictUtf8(bytes) ?: return null
        val pointer = try {
            strictJson.decodeFromString<ActivePointer>(text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (isValid(kindDir, pointer)) pointer else null
    }

    fun writeAtomic(kindDir: File, pointer: ActivePointer) {
        if (!kindDir.isDirectory && !kindDir.mkdirs()) {
            throw IOException("Cannot create package kind directory: ${kindDir.path}")
        }
        val tmpFile = File(kindDir, ACTIVE_TEMP_FILE_NAME)
        val activeFile = File(kindDir, ACTIVE_FILE_NAME)
        val encoded = strictJson.encodeToString(pointer).toByteArray(StandardCharsets.UTF_8)

        FileChannel.open(
            tmpFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buf = ByteBuffer.wrap(encoded)
            while (buf.hasRemaining()) {
                channel.write(buf)
            }
            channel.force(true)
        }

        Files.move(
            tmpFile.toPath(),
            activeFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        fsyncDir(kindDir)
    }

    fun fsyncDir(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    fun scanHighestGeneration(kindDir: File): Long {
        val entries = kindDir.listFiles() ?: return 0L
        var maxGen = 0L
        for (entry in entries) {
            if (!entry.isDirectory) continue
            val match = GENERATION_SCAN_REGEX.matchEntire(entry.name) ?: continue
            val gen = match.groupValues[1].toLongOrNull() ?: continue
            if (gen > maxGen) maxGen = gen
        }
        return maxGen
    }

    private fun isValid(kindDir: File, pointer: ActivePointer): Boolean {
        if (pointer.schema != ACTIVE_POINTER_SCHEMA) return false
        if (pointer.version !in 1..MAX_PACKAGE_VERSION) return false
        if (pointer.generation < 1L) return false
        if (!KEY_ID_REGEX.matches(pointer.keyId)) return false
        if (!isLowerHex(pointer.publicKeySha256, SHA256_HEX_LENGTH)) return false
        if (!ACTIVE_DIR_REGEX.matches(pointer.activeDir)) return false
        if (pointer.activeDir != "v${pointer.version}-g${pointer.generation}") return false

        val resolved = try {
            File(kindDir, pointer.activeDir).canonicalFile
        } catch (_: IOException) {
            return false
        }
        val canonicalKind = try {
            kindDir.canonicalFile
        } catch (_: IOException) {
            return false
        }
        return resolved.parentFile == canonicalKind
    }
}
