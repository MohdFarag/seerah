package app.seerah.content

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val EOCD_SIGNATURE = 0x06054b50
private const val CD_SIGNATURE = 0x02014b50
private const val LFH_SIGNATURE = 0x04034b50

private const val EOCD_SIZE = 22
private const val CD_FIXED_HEADER_SIZE = 46
private const val LFH_FIXED_HEADER_SIZE = 30

private const val MIN_ZIP_ENTRIES = 3
private const val MAX_ZIP_ENTRIES = 65_534
private const val ZIP64_UINT16_SENTINEL = 0xFFFF
private const val ZIP64_UINT32_SENTINEL = 0xFFFF_FFFFL
private const val MAX_CD_BYTES = 8_388_608L
private const val MAX_ENTRY_NAME_BYTES = 1024

private const val METHOD_DEFLATED = 8
private const val ALLOWED_FLAG_BITS_MASK = 0x0806
private const val DEFLATE_LEVEL_BITS_MASK = 0x0006
private const val DOS_DIRECTORY_BIT = 0x10L
private const val UNIX_MODE_SHIFT = 16
private const val UNIX_MODE_MASK = 0xFFFFL
private const val UNIX_S_IFMT_MASK = 0xF000L
private const val UNIX_S_IFREG = 0x8000L

private const val ASCII_MIN_PRINTABLE = 0x20
private const val ASCII_MAX_PRINTABLE = 0x7E
private const val UINT16_MASK = 0xFFFF
private const val UINT32_MASK = 0xFFFF_FFFFL

private const val EXTRACT_BUFFER_BYTES = 262_144
private const val INFLATER_BUFFER_BYTES = 65_536

private val DATA_PATH_REGEX = Regex("^data/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")

/**
 * Hardened positional ZIP container reader and streaming extractor mirroring `archive.py`.
 *
 * Inspects EOCD, Central Directory, Local File Headers, raw ASCII filenames, Unix/DOS modes,
 * strictly contiguous offset-0 layout, CRC-32 checksums, and bounded DEFLATE streams over a
 * single pinned [FileChannel].
 */
internal object StrictZipReader {
    internal const val MANIFEST_ENTRY_NAME = "manifest.json"
    internal const val SIGNATURE_ENTRY_NAME = "manifest.sig"
    internal const val METHOD_STORED = 0

    internal class ZipEntryMeta(
        val path: String,
        val flagBits: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val dataOffset: Long,
    )

    internal class ZipContainer(
        val totalSize: Long,
        val cdOffset: Long,
        val entries: List<ZipEntryMeta>,
        val entriesByPath: Map<String, ZipEntryMeta>,
    )

    fun inspectContainer(channel: FileChannel, requireAllStored: Boolean): PackageResult<ZipContainer> {
        val totalSize = try {
            channel.size()
        } catch (exc: IOException) {
            return PackageResult.Failure(PackageFailure.IoFailure("Cannot query archive size: ${exc.message}"))
        }
        if (totalSize < EOCD_SIZE || totalSize > MAX_RELEASE_FILE_BYTES) {
            return invalidContainer(
                "Archive size $totalSize outside allowed range $EOCD_SIZE..$MAX_RELEASE_FILE_BYTES.",
            )
        }

        val eocdResult = readAndCheckEocd(channel, totalSize)
        val eocd = when (eocdResult) {
            is PackageResult.Success -> eocdResult.value
            is PackageResult.Failure -> return eocdResult
        }

        val rawCd = try {
            readExactAt(channel, eocd.cdOffset, eocd.cdSize.toInt())
        } catch (exc: IOException) {
            return invalidContainer("Cannot read Central Directory: ${exc.message}")
        }

        val cdResult = parseAndValidateCentralDirectory(rawCd, eocd, requireAllStored)
        val parsedCd = when (cdResult) {
            is PackageResult.Success -> cdResult.value
            is PackageResult.Failure -> return cdResult
        }

        return verifyLocalFileHeaders(channel, totalSize, eocd.cdOffset, parsedCd)
    }

    fun readStoredEntryBytes(
        channel: FileChannel,
        entry: ZipEntryMeta,
        minSize: Int,
        maxSize: Int,
        entryLabel: String,
    ): PackageResult<ByteArray> {
        if (entry.method != METHOD_STORED || entry.compressedSize != entry.uncompressedSize) {
            return invalidContainer("$entryLabel must use ZIP_STORED with matching compressed/uncompressed sizes.")
        }
        if (entry.uncompressedSize < minSize || entry.uncompressedSize > maxSize) {
            return invalidContainer(
                "$entryLabel size ${entry.uncompressedSize} outside allowed range $minSize..$maxSize.",
            )
        }
        val bytes = try {
            readExactAt(channel, entry.dataOffset, entry.uncompressedSize.toInt())
        } catch (exc: IOException) {
            return PackageResult.Failure(
                PackageFailure.IoFailure("Failed reading $entryLabel from archive: ${exc.message}"),
            )
        }
        val crc = CRC32().apply { update(bytes) }.value and UINT32_MASK
        if (crc != entry.crc32) {
            return PackageResult.Failure(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.PAYLOAD_INTEGRITY,
                    "$entryLabel CRC-32 mismatch: expected ${entry.crc32}, got $crc.",
                ),
            )
        }
        return PackageResult.Success(bytes)
    }

    suspend fun extractEntryToFile(
        channel: FileChannel,
        entry: ZipEntryMeta,
        manifestEntry: PackageFileEntry,
        stagingRoot: File,
    ): PackageResult<File> {
        if (entry.uncompressedSize != manifestEntry.sizeBytes) {
            return payloadIntegrity(
                "${entry.path}: declared size ${manifestEntry.sizeBytes} disagrees with ZIP header ${entry.uncompressedSize}.",
            )
        }

        val canonicalTarget = resolveSafeOutputFile(stagingRoot, manifestEntry.path)
            ?: return invalidContainer("Unsafe payload destination path '${manifestEntry.path}'.")
        canonicalTarget.parentFile?.mkdirs()

        val sliceStream = ChannelSliceInputStream(channel, entry.dataOffset, entry.compressedSize)
        return try {
            streamVerifiedPayload(sliceStream, entry, manifestEntry, canonicalTarget)
        } catch (exc: CancellationException) {
            canonicalTarget.delete()
            throw exc
        } catch (exc: ZipException) {
            canonicalTarget.delete()
            payloadIntegrity("${entry.path}: malformed compressed stream: ${exc.message}")
        } catch (exc: DataFormatException) {
            canonicalTarget.delete()
            payloadIntegrity("${entry.path}: invalid DEFLATE data: ${exc.message}")
        } catch (exc: EOFException) {
            canonicalTarget.delete()
            payloadIntegrity("${entry.path}: truncated ZIP payload stream: ${exc.message}")
        } catch (exc: IOException) {
            canonicalTarget.delete()
            if (isOutOfSpaceError(exc)) {
                PackageResult.Failure(PackageFailure.InsufficientStorage(manifestEntry.sizeBytes, 0L))
            } else {
                PackageResult.Failure(PackageFailure.IoFailure("Failed extracting '${entry.path}': ${exc.message}"))
            }
        }
    }

    private suspend fun streamVerifiedPayload(
        sliceStream: InputStream,
        entry: ZipEntryMeta,
        manifestEntry: PackageFileEntry,
        outputFile: File,
    ): PackageResult<File> {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buffer = ByteArray(EXTRACT_BUFFER_BYTES)
        var bytesWritten = 0L

        val inflater = if (entry.method == METHOD_DEFLATED) Inflater(true) else null
        val input: InputStream = if (inflater != null) {
            InflaterInputStream(sliceStream, inflater, INFLATER_BUFFER_BYTES)
        } else {
            BufferedInputStream(sliceStream, INFLATER_BUFFER_BYTES)
        }

        try {
            FileChannel.open(
                outputFile.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { outChannel ->
                while (bytesWritten < manifestEntry.sizeBytes) {
                    currentCoroutineContext().ensureActive()
                    val want = minOf(buffer.size.toLong(), manifestEntry.sizeBytes - bytesWritten).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) {
                        return payloadIntegrity(
                            "${entry.path}: stream ended early at $bytesWritten of ${manifestEntry.sizeBytes} bytes.",
                        ).also { outputFile.delete() }
                    }
                    crc.update(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    val writeBuf = ByteBuffer.wrap(buffer, 0, read)
                    while (writeBuf.hasRemaining()) {
                        outChannel.write(writeBuf)
                    }
                    bytesWritten += read
                }

                // Attempt a 1-byte probe past sizeBytes (including 0-byte entries) to catch trailing data/zip bombs.
                val extraByte = input.read()
                if (extraByte != -1) {
                    outputFile.delete()
                    return payloadIntegrity(
                        "${entry.path}: decompressed stream exceeded declared size ${manifestEntry.sizeBytes}.",
                    )
                }
                if (inflater != null && (!inflater.finished() || inflater.bytesRead != entry.compressedSize)) {
                    outputFile.delete()
                    return payloadIntegrity(
                        "${entry.path}: trailing unauthenticated bytes in DEFLATE slice " +
                            "(finished=${inflater.finished()}, read=${inflater.bytesRead}/${entry.compressedSize}).",
                    )
                }
                outChannel.force(true)
            }
        } finally {
            inflater?.end()
        }

        val actualCrc = crc.value and UINT32_MASK
        if (actualCrc != entry.crc32) {
            outputFile.delete()
            return payloadIntegrity("${entry.path}: CRC-32 mismatch (expected ${entry.crc32}, got $actualCrc).")
        }
        val actualSha = digest.digest().toLowerHex()
        if (actualSha != manifestEntry.sha256Hex) {
            outputFile.delete()
            return payloadIntegrity(
                "${entry.path}: SHA-256 mismatch (expected ${manifestEntry.sha256Hex}, got $actualSha).",
            )
        }
        return PackageResult.Success(outputFile)
    }

    private data class EocdRecord(val totalEntries: Int, val cdSize: Long, val cdOffset: Long)

    private fun readAndCheckEocd(channel: FileChannel, totalSize: Long): PackageResult<EocdRecord> {
        val raw = try {
            readExactAt(channel, totalSize - EOCD_SIZE, EOCD_SIZE)
        } catch (exc: IOException) {
            return invalidContainer("Cannot read End of Central Directory: ${exc.message}")
        }
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        val disk = buf.short.toInt() and UINT16_MASK
        val cdDisk = buf.short.toInt() and UINT16_MASK
        val diskEntries = buf.short.toInt() and UINT16_MASK
        val totalEntries = buf.short.toInt() and UINT16_MASK
        val cdSize = buf.int.toLong() and UINT32_MASK
        val cdOffset = buf.int.toLong() and UINT32_MASK
        val commentLen = buf.short.toInt() and UINT16_MASK

        if (magic != EOCD_SIGNATURE || commentLen != 0) {
            return invalidContainer("ZIP must end with a comment-free EOCD record and no trailing bytes.")
        }
        if (disk != 0 || cdDisk != 0 || diskEntries != totalEntries) {
            return invalidContainer("Multi-disk ZIP archives are not supported.")
        }
        if (
            totalEntries == ZIP64_UINT16_SENTINEL ||
            cdSize == ZIP64_UINT32_SENTINEL ||
            cdOffset == ZIP64_UINT32_SENTINEL
        ) {
            return invalidContainer("ZIP64 sentinel fields are not supported.")
        }
        if (totalEntries !in MIN_ZIP_ENTRIES..MAX_ZIP_ENTRIES) {
            return invalidContainer("ZIP entry count $totalEntries outside $MIN_ZIP_ENTRIES..$MAX_ZIP_ENTRIES.")
        }
        if (cdSize <= 0L || cdSize > MAX_CD_BYTES) {
            return invalidContainer("ZIP Central Directory size $cdSize outside 1..$MAX_CD_BYTES.")
        }
        val expectedEocdOffset = totalSize - EOCD_SIZE
        if (cdOffset > expectedEocdOffset || cdOffset + cdSize != expectedEocdOffset) {
            return invalidContainer("ZIP Central Directory does not end immediately before EOCD.")
        }
        return PackageResult.Success(EocdRecord(totalEntries, cdSize, cdOffset))
    }

    private class CdEntry(
        val path: String,
        val rawNameBytes: ByteArray,
        val flagBits: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    private fun parseAndValidateCentralDirectory(
        rawCd: ByteArray,
        eocd: EocdRecord,
        requireAllStored: Boolean,
    ): PackageResult<List<CdEntry>> {
        val buf = ByteBuffer.wrap(rawCd).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ArrayList<CdEntry>(eocd.totalEntries)
        val seenPaths = HashSet<String>(eocd.totalEntries)

        repeat(eocd.totalEntries) { index ->
            if (buf.remaining() < CD_FIXED_HEADER_SIZE) {
                return invalidContainer("Truncated Central Directory entry at index $index.")
            }
            val sig = buf.int
            if (sig != CD_SIGNATURE) {
                return invalidContainer("Invalid Central Directory signature at index $index.")
            }
            buf.short // version made by
            buf.short // version needed
            val flagBits = buf.short.toInt() and UINT16_MASK
            val method = buf.short.toInt() and UINT16_MASK
            buf.short // mod time
            buf.short // mod date
            val crc32 = buf.int.toLong() and UINT32_MASK
            val compressedSize = buf.int.toLong() and UINT32_MASK
            val uncompressedSize = buf.int.toLong() and UINT32_MASK
            val nameLen = buf.short.toInt() and UINT16_MASK
            val extraLen = buf.short.toInt() and UINT16_MASK
            val commentLen = buf.short.toInt() and UINT16_MASK
            val diskNumberStart = buf.short.toInt() and UINT16_MASK
            buf.short // internal attributes
            val externalAttr = buf.int.toLong() and UINT32_MASK
            val localHeaderOffset = buf.int.toLong() and UINT32_MASK

            if (diskNumberStart != 0) {
                return invalidContainer("ZIP CD entry $index has non-zero diskNumberStart ($diskNumberStart).")
            }
            if (extraLen != 0 || commentLen != 0) {
                return invalidContainer("ZIP CD entry $index contains disallowed extra fields or comments.")
            }
            if (
                compressedSize == ZIP64_UINT32_SENTINEL ||
                uncompressedSize == ZIP64_UINT32_SENTINEL ||
                localHeaderOffset == ZIP64_UINT32_SENTINEL
            ) {
                return invalidContainer("ZIP CD entry $index uses unsupported ZIP64 sentinel sizes.")
            }
            if (compressedSize > MAX_RELEASE_FILE_BYTES || uncompressedSize > MAX_RELEASE_FILE_BYTES) {
                return invalidContainer("ZIP CD entry $index exceeds 2 GiB entry limit.")
            }
            if ((flagBits and ALLOWED_FLAG_BITS_MASK.inv()) != 0) {
                return invalidContainer(
                    "ZIP CD entry $index sets unsupported general purpose flags (0x${flagBits.toString(16)}).",
                )
            }
            if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                return invalidContainer("ZIP CD entry $index uses unsupported compression method $method.")
            }
            if (method == METHOD_STORED) {
                if ((flagBits and DEFLATE_LEVEL_BITS_MASK) != 0) {
                    return invalidContainer("ZIP_STORED entry $index sets DEFLATE compression-level flags.")
                }
                if (compressedSize != uncompressedSize) {
                    return invalidContainer("ZIP_STORED entry $index has mismatched compressed/uncompressed sizes.")
                }
            }
            if ((externalAttr and DOS_DIRECTORY_BIT) != 0L) {
                return invalidContainer("ZIP directory entry refused at index $index.")
            }
            val unixType = ((externalAttr ushr UNIX_MODE_SHIFT) and UNIX_MODE_MASK) and UNIX_S_IFMT_MASK
            if (unixType != 0L && unixType != UNIX_S_IFREG) {
                return invalidContainer("ZIP CD entry $index has forbidden non-regular Unix file mode.")
            }
            if (nameLen !in 1..MAX_ENTRY_NAME_BYTES || nameLen > buf.remaining()) {
                return invalidContainer("ZIP CD entry $index has invalid filename length $nameLen.")
            }

            val rawName = ByteArray(nameLen)
            buf.get(rawName)
            val entryPath = validateRawEntryName(rawName, allowRootManifest = true)
                ?: return invalidContainer("ZIP CD entry $index has unsafe or non-ASCII filename.")
            if (!seenPaths.add(entryPath)) {
                return invalidContainer("Duplicate ZIP entry path '$entryPath' in Central Directory.")
            }

            entries.add(
                CdEntry(
                    path = entryPath,
                    rawNameBytes = rawName,
                    flagBits = flagBits,
                    method = method,
                    crc32 = crc32,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                ),
            )
        }

        if (buf.hasRemaining()) {
            return invalidContainer("Central Directory has ${buf.remaining()} trailing bytes past entry list.")
        }
        if (entries[0].path != MANIFEST_ENTRY_NAME || entries[1].path != SIGNATURE_ENTRY_NAME) {
            return invalidContainer(
                "ZIP archive must place manifest.json first and manifest.sig second (found '${entries[0].path}', '${entries[1].path}').",
            )
        }
        if (entries[0].method != METHOD_STORED || entries[1].method != METHOD_STORED) {
            return invalidContainer("manifest.json and manifest.sig must use ZIP_STORED.")
        }
        if (requireAllStored && entries.any { it.method != METHOD_STORED }) {
            return invalidContainer("Map package entries must all use ZIP_STORED.")
        }
        return PackageResult.Success(entries)
    }

    private fun verifyLocalFileHeaders(
        channel: FileChannel,
        totalSize: Long,
        cdOffset: Long,
        cdEntries: List<CdEntry>,
    ): PackageResult<ZipContainer> {
        val resultEntries = ArrayList<ZipEntryMeta>(cdEntries.size)
        val byPath = LinkedHashMap<String, ZipEntryMeta>(cdEntries.size)
        var expectedNextOffset = 0L

        for (cd in cdEntries) {
            if (cd.localHeaderOffset != expectedNextOffset) {
                return invalidContainer(
                    "ZIP entry '${cd.path}' starts at offset ${cd.localHeaderOffset}, expected contiguous offset $expectedNextOffset.",
                )
            }
            val nameLen = cd.rawNameBytes.size
            if (cd.localHeaderOffset + LFH_FIXED_HEADER_SIZE + nameLen > cdOffset) {
                return invalidContainer("ZIP Local File Header for '${cd.path}' overlaps Central Directory.")
            }

            val lfhAndName = try {
                readExactAt(channel, cd.localHeaderOffset, LFH_FIXED_HEADER_SIZE + nameLen)
            } catch (exc: IOException) {
                return invalidContainer("Cannot read Local File Header for '${cd.path}': ${exc.message}")
            }
            val buf = ByteBuffer.wrap(lfhAndName).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != LFH_SIGNATURE) {
                return invalidContainer("Invalid Local File Header signature for '${cd.path}'.")
            }
            buf.short // version needed
            val lfhFlags = buf.short.toInt() and UINT16_MASK
            val lfhMethod = buf.short.toInt() and UINT16_MASK
            buf.short // time
            buf.short // date
            val lfhCrc32 = buf.int.toLong() and UINT32_MASK
            val lfhCompSize = buf.int.toLong() and UINT32_MASK
            val lfhUncompSize = buf.int.toLong() and UINT32_MASK
            val lfhNameLen = buf.short.toInt() and UINT16_MASK
            val lfhExtraLen = buf.short.toInt() and UINT16_MASK

            if (lfhExtraLen != 0) {
                return invalidContainer("ZIP Local File Header for '${cd.path}' contains disallowed extra fields.")
            }
            if (
                lfhFlags != cd.flagBits ||
                lfhMethod != cd.method ||
                lfhCrc32 != cd.crc32 ||
                lfhCompSize != cd.compressedSize ||
                lfhUncompSize != cd.uncompressedSize ||
                lfhNameLen != nameLen
            ) {
                return invalidContainer("Local File Header disagrees with Central Directory for '${cd.path}'.")
            }
            val lfhNameBytes = ByteArray(nameLen)
            buf.get(lfhNameBytes)
            if (!lfhNameBytes.contentEquals(cd.rawNameBytes)) {
                return invalidContainer(
                    "Local File Header filename bytes disagree with Central Directory for '${cd.path}'.",
                )
            }

            val dataOffset = cd.localHeaderOffset + LFH_FIXED_HEADER_SIZE + nameLen
            if (dataOffset + cd.compressedSize > cdOffset) {
                return invalidContainer("ZIP entry '${cd.path}' payload extends past Central Directory offset.")
            }
            expectedNextOffset = dataOffset + cd.compressedSize

            val meta = ZipEntryMeta(
                path = cd.path,
                flagBits = cd.flagBits,
                method = cd.method,
                crc32 = cd.crc32,
                compressedSize = cd.compressedSize,
                uncompressedSize = cd.uncompressedSize,
                dataOffset = dataOffset,
            )
            resultEntries.add(meta)
            byPath[meta.path] = meta
        }

        if (expectedNextOffset != cdOffset) {
            return invalidContainer(
                "ZIP entries leave gap before Central Directory ($expectedNextOffset vs $cdOffset).",
            )
        }
        return PackageResult.Success(ZipContainer(totalSize, cdOffset, resultEntries, byPath))
    }

    internal fun validateRawEntryName(rawBytes: ByteArray, allowRootManifest: Boolean): String? {
        if (rawBytes.isEmpty()) return null
        for (b in rawBytes) {
            val code = b.toInt() and 0xFF
            if (code !in ASCII_MIN_PRINTABLE..ASCII_MAX_PRINTABLE) return null
        }
        val path = String(rawBytes, StandardCharsets.US_ASCII)
        if ('\\' in path || path.startsWith('/') || path.endsWith('/') || "//" in path) return null
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (allowRootManifest && (path == MANIFEST_ENTRY_NAME || path == SIGNATURE_ENTRY_NAME)) {
            return path
        }
        return if (DATA_PATH_REGEX.matches(path)) path else null
    }

    internal fun validateDataPaths(paths: List<String>): String? {
        if (paths.isEmpty()) return "Manifest files list must not be empty."
        for (i in paths.indices) {
            val p = paths[i]
            val valid = validateRawEntryName(p.toByteArray(StandardCharsets.US_ASCII), allowRootManifest = false)
            if (valid != p) return "Invalid manifest file path '$p'."
            if (i > 0) {
                val prev = paths[i - 1]
                if (p == prev) return "Duplicate manifest file path '$p'."
                if (p < prev) return "Manifest files must be sorted lexicographically ('$prev' before '$p')."
                if (p.startsWith(
                        "$prev/",
                    )
                ) {
                    return "Manifest paths have file/directory prefix collision: '$prev' and '$p'."
                }
            }
        }
        return null
    }

    private fun resolveSafeOutputFile(rootDir: File, relativePath: String): File? {
        val canonicalRoot = rootDir.canonicalFile.toPath()
        val candidate = File(rootDir, relativePath).canonicalFile.toPath()
        return if (candidate.startsWith(canonicalRoot) && candidate != canonicalRoot) {
            candidate.toFile()
        } else {
            null
        }
    }

    private fun invalidContainer(detail: String): PackageResult.Failure =
        PackageResult.Failure(PackageFailure.InvalidPackage(InvalidPackageCause.INVALID_CONTAINER, detail))

    private fun payloadIntegrity(detail: String): PackageResult.Failure =
        PackageResult.Failure(PackageFailure.InvalidPackage(InvalidPackageCause.PAYLOAD_INTEGRITY, detail))
}
