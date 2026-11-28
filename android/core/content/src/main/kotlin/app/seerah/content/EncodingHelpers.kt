package app.seerah.content

import android.system.ErrnoException
import android.system.OsConstants
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val ONE_MIB_BYTES: Int = 1_048_576
internal const val FOUR_MIB_BYTES: Int = 4_194_304
internal const val MAX_RELEASE_FILE_BYTES: Long = 2_147_483_647L
internal const val SHA256_HEX_LENGTH: Int = 64
internal const val RAW_ED25519_SIGNATURE_BYTES: Int = 64

private const val NIBBLE_MASK = 0x0F
private const val HIGH_NIBBLE_SHIFT = 4
private const val BYTE_MASK = 0xFF
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

internal fun decodeStrictUtf8(bytes: ByteArray): String? {
    val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

internal fun ByteArray.toLowerHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and BYTE_MASK
        out[i * 2] = HEX_DIGITS[v ushr HIGH_NIBBLE_SHIFT]
        out[i * 2 + 1] = HEX_DIGITS[v and NIBBLE_MASK]
    }
    return String(out)
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

internal fun isLowerHex(value: String, expectedLength: Int): Boolean =
    value.length == expectedLength && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun readExactAt(channel: FileChannel, offset: Long, length: Int): ByteArray {
    require(offset >= 0L && length >= 0) { "Negative offset or length: ($offset, $length)." }
    val out = ByteArray(length)
    val buffer = ByteBuffer.wrap(out)
    var position = offset
    while (buffer.hasRemaining()) {
        val read = channel.read(buffer, position)
        if (read <= 0) {
            throw EOFException("Unexpected end of file at offset $position (wanted $length bytes).")
        }
        position += read
    }
    return out
}

internal fun isOutOfSpaceError(error: Throwable): Boolean {
    var cursor: Throwable? = error
    while (cursor != null) {
        if (cursor is ErrnoException && cursor.errno == OsConstants.ENOSPC) {
            return true
        }
        val msg = cursor.message
        if (msg != null && msg.contains("No space left on device", ignoreCase = true)) {
            return true
        }
        cursor = cursor.cause
    }
    return false
}

/**
 * Non-closing positional slice [InputStream] over a single pinned [FileChannel].
 *
 * Closing this stream does NOT close [channel], preserving the channel handle across
 * multiple entry reads and verification steps without reopening by path.
 */
internal class ChannelSliceInputStream(private val channel: FileChannel, startOffset: Long, length: Long) :
    InputStream() {
    private var position: Long = startOffset
    private var remaining: Long = length
    private val singleByteBuffer = ByteBuffer.allocate(1)

    override fun read(): Int {
        if (remaining <= 0L) return -1
        singleByteBuffer.clear()
        val read = channel.read(singleByteBuffer, position)
        if (read < 0) return -1
        if (read == 0) throw IOException("Zero-byte read from FileChannel slice at $position.")
        position += 1L
        remaining -= 1L
        return singleByteBuffer.get(0).toInt() and BYTE_MASK
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        if (remaining <= 0L) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val buf = ByteBuffer.wrap(b, off, toRead)
        val read = channel.read(buf, position)
        if (read < 0) return -1
        position += read.toLong()
        remaining -= read.toLong()
        return read
    }
}
