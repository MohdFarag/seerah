package app.seerah.content

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

internal object TestArchiveBuilders {
    data class RawZipSpecEntry(
        val nameBytes: ByteArray,
        val payloadBytes: ByteArray,
        val method: Int = 0,
        val crc32Override: Long? = null,
        val compressedSizeOverride: Long? = null,
        val uncompressedSizeOverride: Long? = null,
        val cdFlags: Int = 0,
        val lfhFlags: Int? = null,
        val lfhMethod: Int? = null,
        val lfhCrc32: Long? = null,
        val lfhCompSize: Long? = null,
        val lfhUncompSize: Long? = null,
        val lfhNameBytes: ByteArray? = null,
        val cdExtra: ByteArray = ByteArray(0),
        val cdComment: ByteArray = ByteArray(0),
        val lfhExtra: ByteArray = ByteArray(0),
        val cdDiskNumberStart: Int = 0,
        val externalAttr: Long = 0x81A4L shl 16,
    )

    fun deflateRaw(plain: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(plain)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            if (count > 0) out.write(buf, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    fun buildRawZip(
        entries: List<RawZipSpecEntry>,
        prependedBytes: ByteArray = ByteArray(0),
        gapBetweenFirstAndSecond: Int = 0,
        archiveComment: ByteArray = ByteArray(0),
        trailingBytes: ByteArray = ByteArray(0),
        eocdEntriesOverride: Int? = null,
        eocdCdSizeOverride: Long? = null,
        eocdCdOffsetOverride: Long? = null,
        trailingCdBytes: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(prependedBytes)

        val recordedOffsets = LongArray(entries.size)
        val recordedCrcs = LongArray(entries.size)
        val recordedCompSizes = LongArray(entries.size)
        val recordedUncompSizes = LongArray(entries.size)

        for (i in entries.indices) {
            if (i == 1 && gapBetweenFirstAndSecond > 0) {
                out.write(ByteArray(gapBetweenFirstAndSecond))
            }
            val e = entries[i]
            recordedOffsets[i] = out.size().toLong()

            val storedOrCompressed = when (e.method) {
                8 -> if (e.payloadBytes.isNotEmpty() || e.compressedSizeOverride == null) {
                    deflateRaw(e.payloadBytes)
                } else {
                    e.payloadBytes
                }

                else -> e.payloadBytes
            }
            val actualPayload = if (e.compressedSizeOverride != null) e.payloadBytes else storedOrCompressed
            val defaultCrc = CRC32().apply { update(e.payloadBytes) }.value and 0xFFFF_FFFFL
            val crc = e.crc32Override ?: defaultCrc
            val compSize = e.compressedSizeOverride ?: actualPayload.size.toLong()
            val uncompSize = e.uncompressedSizeOverride ?: e.payloadBytes.size.toLong()

            recordedCrcs[i] = crc
            recordedCompSizes[i] = compSize
            recordedUncompSizes[i] = uncompSize

            val lfhName = e.lfhNameBytes ?: e.nameBytes
            val lfh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x04034b50)
                putShort(20)
                putShort((e.lfhFlags ?: e.cdFlags).toShort())
                putShort((e.lfhMethod ?: e.method).toShort())
                putShort(0)
                putShort(0x5821)
                putInt((e.lfhCrc32 ?: crc).toInt())
                putInt((e.lfhCompSize ?: compSize).toInt())
                putInt((e.lfhUncompSize ?: uncompSize).toInt())
                putShort(lfhName.size.toShort())
                putShort(e.lfhExtra.size.toShort())
            }.array()
            out.write(lfh)
            out.write(lfhName)
            out.write(e.lfhExtra)
            out.write(actualPayload)
        }

        val cdOffset = out.size().toLong()
        for (i in entries.indices) {
            val e = entries[i]
            val cd = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x02014b50)
                putShort((3 shl 8 or 20).toShort())
                putShort(20)
                putShort(e.cdFlags.toShort())
                putShort(e.method.toShort())
                putShort(0)
                putShort(0x5821)
                putInt(recordedCrcs[i].toInt())
                putInt(recordedCompSizes[i].toInt())
                putInt(recordedUncompSizes[i].toInt())
                putShort(e.nameBytes.size.toShort())
                putShort(e.cdExtra.size.toShort())
                putShort(e.cdComment.size.toShort())
                putShort(e.cdDiskNumberStart.toShort())
                putShort(0)
                putInt(e.externalAttr.toInt())
                putInt(recordedOffsets[i].toInt())
            }.array()
            out.write(cd)
            out.write(e.nameBytes)
            out.write(e.cdExtra)
            out.write(e.cdComment)
        }
        if (trailingCdBytes > 0) out.write(ByteArray(trailingCdBytes))
        val cdSize = out.size().toLong() - cdOffset

        val totalEntries = eocdEntriesOverride ?: entries.size
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x06054b50)
            putShort(0)
            putShort(0)
            putShort(totalEntries.toShort())
            putShort(totalEntries.toShort())
            putInt((eocdCdSizeOverride ?: cdSize).toInt())
            putInt((eocdCdOffsetOverride ?: cdOffset).toInt())
            putShort(archiveComment.size.toShort())
        }.array()
        out.write(eocd)
        out.write(archiveComment)
        out.write(trailingBytes)
        return out.toByteArray()
    }

    fun gzipBytes(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return out.toByteArray()
            }
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
    }

    fun validSingleTileDirectoryRaw(tileLength: Long = 10L): ByteArray = ByteArrayOutputStream().apply {
        write(encodeVarint(1))
        write(encodeVarint(0))
        write(encodeVarint(1))
        write(encodeVarint(tileLength))
        write(encodeVarint(1))
    }.toByteArray()

    fun buildMinimalPmTiles(
        rootDirBytes: ByteArray = gzipBytes(validSingleTileDirectoryRaw(10L)),
        metadataBytes: ByteArray = gzipBytes("""{"tile_schema":1,"encoding":"terrarium-value"}""".toByteArray()),
        leafDirBytes: ByteArray = ByteArray(0),
        tileDataBytes: ByteArray = ByteArray(10),
        internalCompression: Int = 2,
        tileCompression: Int = 1,
        tileType: Int = 2,
        minZoom: Int = 0,
        maxZoom: Int = 12,
        minLonE7: Int = (33.0 * 1e7).toInt(),
        minLatE7: Int = (11.0 * 1e7).toInt(),
        maxLonE7: Int = (61.0 * 1e7).toInt(),
        maxLatE7: Int = (33.0 * 1e7).toInt(),
        addressedTiles: Long = 1L,
        tileEntries: Long = 1L,
        tileContents: Long = 1L,
        rootOffsetOverride: Long? = null,
        rootLengthOverride: Long? = null,
        metadataOffsetOverride: Long? = null,
        metadataLengthOverride: Long? = null,
        leafOffsetOverride: Long? = null,
        leafLengthOverride: Long? = null,
        tileDataOffsetOverride: Long? = null,
        tileDataLengthOverride: Long? = null,
    ): ByteArray {
        val rootOff = 127L
        val metaOff = rootOff + rootDirBytes.size
        val leafOff = if (leafDirBytes.isNotEmpty()) metaOff + metadataBytes.size else 0L
        val tileOff = metaOff + metadataBytes.size + leafDirBytes.size

        val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PMTiles".toByteArray(StandardCharsets.US_ASCII))
            put(3.toByte())
            putLong(8, rootOffsetOverride ?: rootOff)
            putLong(16, rootLengthOverride ?: rootDirBytes.size.toLong())
            putLong(24, metadataOffsetOverride ?: metaOff)
            putLong(32, metadataLengthOverride ?: metadataBytes.size.toLong())
            putLong(40, leafOffsetOverride ?: leafOff)
            putLong(48, leafLengthOverride ?: leafDirBytes.size.toLong())
            putLong(56, tileDataOffsetOverride ?: tileOff)
            putLong(64, tileDataLengthOverride ?: tileDataBytes.size.toLong())
            putLong(72, addressedTiles)
            putLong(80, tileEntries)
            putLong(88, tileContents)
            put(96, 1.toByte())
            put(97, internalCompression.toByte())
            put(98, tileCompression.toByte())
            put(99, tileType.toByte())
            put(100, minZoom.toByte())
            put(101, maxZoom.toByte())
            putInt(102, minLonE7)
            putInt(106, minLatE7)
            putInt(110, maxLonE7)
            putInt(114, maxLatE7)
            put(118, 6.toByte())
            putInt(119, ((minLonE7 + maxLonE7) / 2))
            putInt(123, ((minLatE7 + maxLatE7) / 2))
        }.array()

        return ByteArrayOutputStream().apply {
            write(header)
            write(rootDirBytes)
            write(metadataBytes)
            write(leafDirBytes)
            write(tileDataBytes)
        }.toByteArray()
    }

    fun writeTempBytes(dir: File, name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }
}
