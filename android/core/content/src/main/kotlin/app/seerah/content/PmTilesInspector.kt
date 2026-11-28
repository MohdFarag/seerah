package app.seerah.content

import app.seerah.model.GeoBounds
import app.seerah.model.MapMetadata
import app.seerah.model.ReliefEncoding
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.math.abs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

private const val PMTILES_HEADER_SIZE = 127
private const val PMTILES_MAGIC = "PMTiles"
private const val PMTILES_VERSION = 3

private const val COMPRESSION_NONE = 1
private const val COMPRESSION_GZIP = 2
private const val TILE_TYPE_MVT = 1
private const val TILE_TYPE_PNG = 2
private const val TILE_TYPE_WEBP = 4

private const val MAX_PMTILES_DIR_BYTES = 1_048_576
private const val MAX_PMTILES_TOTAL_LEAF_BUDGET = 16L * ONE_MIB_BYTES
private const val MAX_LEB128_BYTES = 10
private const val MIN_VARINTS_PER_DIR_ENTRY = 4
private const val BYTE_MASK = 0xFF
private const val E7_SCALE = 1e7
private const val BOUNDS_MATCH_TOLERANCE_DEG = 1e-4
private const val GZIP_COPY_BUFFER_BYTES = 16_384

private const val OFFSET_ROOT_OFFSET = 8
private const val OFFSET_ROOT_LENGTH = 16
private const val OFFSET_METADATA_OFFSET = 24
private const val OFFSET_METADATA_LENGTH = 32
private const val OFFSET_LEAF_OFFSET = 40
private const val OFFSET_LEAF_LENGTH = 48
private const val OFFSET_TILE_DATA_OFFSET = 56
private const val OFFSET_TILE_DATA_LENGTH = 64
private const val OFFSET_ADDRESSED_TILES = 72
private const val OFFSET_TILE_ENTRIES = 80
private const val OFFSET_TILE_CONTENTS = 88
private const val OFFSET_INTERNAL_COMPRESSION = 97
private const val OFFSET_TILE_COMPRESSION = 98
private const val OFFSET_TILE_TYPE = 99
private const val OFFSET_MIN_ZOOM = 100
private const val OFFSET_MAX_ZOOM = 101
private const val OFFSET_MIN_LON_E7 = 102
private const val OFFSET_MIN_LAT_E7 = 106
private const val OFFSET_MAX_LON_E7 = 110
private const val OFFSET_MAX_LAT_E7 = 114

/**
 * Pure-Kotlin PMTiles v3 binary/directory/metadata validator.
 *
 * Hardens against malformed headers, overlapping sections, LEB128 varint bombs, and metadata
 * gzip bombs before MapLibre C++ mmaps extracted archives, while avoiding host build-provenance
 * coupling (ADR 0009 decision 10).
 */
internal object PmTilesInspector {
    private val wireJson = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    internal data class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafDirectoryOffset: Long,
        val leafDirectoryLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val addressedTilesCount: Long,
        val tileEntriesCount: Long,
        val tileContentsCount: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val bounds: GeoBounds?,
        val minLonE7: Int,
        val minLatE7: Int,
        val maxLonE7: Int,
        val maxLatE7: Int,
    )

    internal data class InspectionResult(val header: Header, val metadata: JsonObject)

    fun validateMapDirectory(
        dataDir: File,
        metadata: MapMetadata,
        additivePmTilesPaths: List<String> = emptyList(),
    ): PackageResult<Unit> {
        val baseFile = File(dataDir, metadata.sources.base.file)
        val reliefFile = File(dataDir, metadata.sources.relief.file)

        val baseResult = inspectFile(baseFile, "data/${metadata.sources.base.file}")
        val baseInspected = when (baseResult) {
            is PackageResult.Success -> baseResult.value
            is PackageResult.Failure -> return baseResult
        }

        val reliefResult = inspectFile(reliefFile, "data/${metadata.sources.relief.file}")
        val reliefInspected = when (reliefResult) {
            is PackageResult.Success -> reliefResult.value
            is PackageResult.Failure -> return reliefResult
        }

        validateBaseConsistency(metadata, baseInspected)?.let { return it }
        validateReliefConsistency(metadata, reliefInspected)?.let { return it }

        for (relPath in additivePmTilesPaths) {
            val extraFile = File(dataDir.parentFile ?: dataDir, relPath)
            val extraResult = inspectFile(extraFile, relPath)
            if (extraResult is PackageResult.Failure) return extraResult
        }
        return PackageResult.Success(Unit)
    }

    fun inspectFile(file: File, label: String): PackageResult<InspectionResult> {
        if (!file.isFile) {
            return invalidPmTiles("$label: PMTiles file does not exist.")
        }
        return try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                inspectChannel(channel, label)
            }
        } catch (exc: IOException) {
            PackageResult.Failure(PackageFailure.IoFailure("Cannot open PMTiles '$label': ${exc.message}"))
        }
    }

    fun inspectChannel(channel: FileChannel, label: String): PackageResult<InspectionResult> {
        val fileSize = try {
            channel.size()
        } catch (exc: IOException) {
            return PackageResult.Failure(PackageFailure.IoFailure("Cannot read size of '$label': ${exc.message}"))
        }
        val headerResult = readAndValidateHeader(channel, fileSize, label)
        val header = when (headerResult) {
            is PackageResult.Success -> headerResult.value
            is PackageResult.Failure -> return headerResult
        }

        val dirResult = validateDirectories(channel, header, label)
        if (dirResult is PackageResult.Failure) return dirResult

        val metaResult = readAndValidateMetadata(channel, header, label)
        val metaObj = when (metaResult) {
            is PackageResult.Success -> metaResult.value
            is PackageResult.Failure -> return metaResult
        }
        return PackageResult.Success(InspectionResult(header, metaObj))
    }

    private fun readAndValidateHeader(channel: FileChannel, fileSize: Long, label: String): PackageResult<Header> {
        if (fileSize < PMTILES_HEADER_SIZE) {
            return invalidPmTiles("$label: size $fileSize smaller than $PMTILES_HEADER_SIZE-byte header.")
        }
        val rawHeader = try {
            readExactAt(channel, 0L, PMTILES_HEADER_SIZE)
        } catch (exc: IOException) {
            return invalidPmTiles("$label: cannot read header: ${exc.message}")
        }

        val magic = String(rawHeader, 0, PMTILES_MAGIC.length, StandardCharsets.US_ASCII)
        if (magic != PMTILES_MAGIC) {
            return invalidPmTiles("$label: invalid PMTiles magic header.")
        }
        val version = rawHeader[PMTILES_MAGIC.length].toInt() and BYTE_MASK
        if (version != PMTILES_VERSION) {
            return invalidPmTiles("$label: unsupported PMTiles version $version, expected $PMTILES_VERSION.")
        }

        val buf = ByteBuffer.wrap(rawHeader).order(ByteOrder.LITTLE_ENDIAN)
        val rootOffset = buf.getLong(OFFSET_ROOT_OFFSET)
        val rootLength = buf.getLong(OFFSET_ROOT_LENGTH)
        val metadataOffset = buf.getLong(OFFSET_METADATA_OFFSET)
        val metadataLength = buf.getLong(OFFSET_METADATA_LENGTH)
        val leafOffset = buf.getLong(OFFSET_LEAF_OFFSET)
        val leafLength = buf.getLong(OFFSET_LEAF_LENGTH)
        val tileDataOffset = buf.getLong(OFFSET_TILE_DATA_OFFSET)
        val tileDataLength = buf.getLong(OFFSET_TILE_DATA_LENGTH)
        val addressedTiles = buf.getLong(OFFSET_ADDRESSED_TILES)
        val tileEntries = buf.getLong(OFFSET_TILE_ENTRIES)
        val tileContents = buf.getLong(OFFSET_TILE_CONTENTS)

        val internalCompression = rawHeader[OFFSET_INTERNAL_COMPRESSION].toInt() and BYTE_MASK
        val tileCompression = rawHeader[OFFSET_TILE_COMPRESSION].toInt() and BYTE_MASK
        val tileType = rawHeader[OFFSET_TILE_TYPE].toInt() and BYTE_MASK
        val minZoom = rawHeader[OFFSET_MIN_ZOOM].toInt() and BYTE_MASK
        val maxZoom = rawHeader[OFFSET_MAX_ZOOM].toInt() and BYTE_MASK
        val minLonE7 = buf.getInt(OFFSET_MIN_LON_E7)
        val minLatE7 = buf.getInt(OFFSET_MIN_LAT_E7)
        val maxLonE7 = buf.getInt(OFFSET_MAX_LON_E7)
        val maxLatE7 = buf.getInt(OFFSET_MAX_LAT_E7)

        if (rootOffset < PMTILES_HEADER_SIZE || rootLength !in 1L..MAX_PMTILES_DIR_BYTES) {
            return invalidPmTiles("$label: invalid root directory ($rootOffset, $rootLength).")
        }
        if (metadataOffset < PMTILES_HEADER_SIZE || metadataLength !in 1L..ONE_MIB_BYTES) {
            return invalidPmTiles("$label: invalid metadata slice ($metadataOffset, $metadataLength).")
        }
        val sections = listOf(
            "root_directory" to (rootOffset to rootLength),
            "metadata" to (metadataOffset to metadataLength),
            "leaf_directory" to (leafOffset to leafLength),
            "tile_data" to (tileDataOffset to tileDataLength),
        )
        val nonEmptyIntervals = ArrayList<Pair<Long, Long>>(sections.size)
        for ((name, span) in sections) {
            val (off, len) = span
            if (off < 0L || len < 0L || off > fileSize || len > fileSize - off) {
                return invalidPmTiles("$label: $name ($off + $len) exceeds archive size $fileSize.")
            }
            if (len > 0L) {
                if (off < PMTILES_HEADER_SIZE) {
                    return invalidPmTiles("$label: non-empty section $name overlaps 127-byte header.")
                }
                nonEmptyIntervals.add(off to (off + len))
            }
        }
        nonEmptyIntervals.sortBy { it.first }
        for (i in 1 until nonEmptyIntervals.size) {
            if (nonEmptyIntervals[i].first < nonEmptyIntervals[i - 1].second) {
                return invalidPmTiles("$label: header sections overlap on disk.")
            }
        }

        if (addressedTiles <= 0L || tileEntries <= 0L || tileContents <= 0L) {
            return invalidPmTiles("$label: PMTiles archive reports non-positive tile counts.")
        }
        if (internalCompression != COMPRESSION_NONE && internalCompression != COMPRESSION_GZIP) {
            return invalidPmTiles("$label: unsupported internal compression $internalCompression.")
        }
        if (tileCompression != COMPRESSION_NONE && tileCompression != COMPRESSION_GZIP) {
            return invalidPmTiles("$label: unsupported tile compression $tileCompression.")
        }
        if (tileType != TILE_TYPE_MVT && tileType != TILE_TYPE_PNG && tileType != TILE_TYPE_WEBP) {
            return invalidPmTiles("$label: unsupported tile type $tileType.")
        }
        if (minZoom != 0 || maxZoom !in 0..22) {
            return invalidPmTiles("$label: invalid header zoom range ($minZoom..$maxZoom).")
        }

        val bounds = if (minLonE7 < maxLonE7 && minLatE7 < maxLatE7) {
            val west = minLonE7 / E7_SCALE
            val south = minLatE7 / E7_SCALE
            val east = maxLonE7 / E7_SCALE
            val north = maxLatE7 / E7_SCALE
            try {
                GeoBounds(west, south, east, north)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }

        return PackageResult.Success(
            Header(
                rootOffset = rootOffset,
                rootLength = rootLength,
                metadataOffset = metadataOffset,
                metadataLength = metadataLength,
                leafDirectoryOffset = leafOffset,
                leafDirectoryLength = leafLength,
                tileDataOffset = tileDataOffset,
                tileDataLength = tileDataLength,
                addressedTilesCount = addressedTiles,
                tileEntriesCount = tileEntries,
                tileContentsCount = tileContents,
                internalCompression = internalCompression,
                tileCompression = tileCompression,
                tileType = tileType,
                minZoom = minZoom,
                maxZoom = maxZoom,
                bounds = bounds,
                minLonE7 = minLonE7,
                minLatE7 = minLatE7,
                maxLonE7 = maxLonE7,
                maxLatE7 = maxLatE7,
            ),
        )
    }

    private data class DirectoryEntry(val tileId: Long, val runLength: Long, val offset: Long, val length: Long)

    private fun validateDirectories(channel: FileChannel, header: Header, label: String): PackageResult<Unit> {
        val rootBytes = readAndDecompressSlice(
            channel = channel,
            offset = header.rootOffset,
            length = header.rootLength.toInt(),
            compression = header.internalCompression,
            maxUncompressedBytes = MAX_PMTILES_DIR_BYTES,
            label = "$label root directory",
        )
        val rawRoot = when (rootBytes) {
            is PackageResult.Success -> rootBytes.value
            is PackageResult.Failure -> return rootBytes
        }
        val rootEntriesResult = parseDirectoryBytes(rawRoot, "$label root directory")
        val rootEntries = when (rootEntriesResult) {
            is PackageResult.Success -> rootEntriesResult.value
            is PackageResult.Failure -> return rootEntriesResult
        }

        var leafBudgetRemaining = MAX_PMTILES_TOTAL_LEAF_BUDGET
        for (entry in rootEntries) {
            if (entry.runLength == 0L) {
                if (header.leafDirectoryLength == 0L || entry.length !in 1L..MAX_PMTILES_DIR_BYTES) {
                    return invalidPmTiles("$label: invalid leaf directory pointer (${entry.offset}, ${entry.length}).")
                }
                if (entry.offset < 0L || entry.offset > header.leafDirectoryLength - entry.length) {
                    return invalidPmTiles("$label: leaf pointer exceeds leaf_directory bounds.")
                }
                val leafSlice = readAndDecompressSlice(
                    channel = channel,
                    offset = header.leafDirectoryOffset + entry.offset,
                    length = entry.length.toInt(),
                    compression = header.internalCompression,
                    maxUncompressedBytes = MAX_PMTILES_DIR_BYTES,
                    label = "$label leaf directory",
                )
                val rawLeaf = when (leafSlice) {
                    is PackageResult.Success -> leafSlice.value
                    is PackageResult.Failure -> return leafSlice
                }
                leafBudgetRemaining -= rawLeaf.size.toLong()
                if (leafBudgetRemaining < 0L) {
                    return invalidPmTiles(
                        "$label: cumulative leaf directory size exceeds $MAX_PMTILES_TOTAL_LEAF_BUDGET bytes.",
                    )
                }
                val leafEntriesResult = parseDirectoryBytes(rawLeaf, "$label leaf directory")
                val leafEntries = when (leafEntriesResult) {
                    is PackageResult.Success -> leafEntriesResult.value
                    is PackageResult.Failure -> return leafEntriesResult
                }
                for (leafEntry in leafEntries) {
                    if (leafEntry.runLength == 0L) {
                        return invalidPmTiles("$label: nested leaf pointer depth > 1 refused.")
                    }
                    validateTileSliceBounds(header, leafEntry, label)?.let { return it }
                }
            } else {
                validateTileSliceBounds(header, entry, label)?.let { return it }
            }
        }
        return PackageResult.Success(Unit)
    }

    private fun validateTileSliceBounds(header: Header, entry: DirectoryEntry, label: String): PackageResult.Failure? {
        if (
            entry.length <= 0L ||
            entry.offset < 0L ||
            entry.length > header.tileDataLength ||
            entry.offset > header.tileDataLength - entry.length
        ) {
            return invalidPmTiles("$label: tile slice (${entry.offset}, ${entry.length}) out of tile_data bounds.")
        }
        return null
    }

    private fun parseDirectoryBytes(raw: ByteArray, label: String): PackageResult<List<DirectoryEntry>> {
        if (raw.isEmpty()) return invalidPmTiles("$label: empty binary directory.")
        var cursor = 0

        fun readVarint(): Long? {
            var value = 0L
            var shift = 0
            for (count in 0 until MAX_LEB128_BYTES) {
                if (cursor >= raw.size) return null
                val b = raw[cursor++].toInt() and BYTE_MASK
                if (count == MAX_LEB128_BYTES - 1 && (b and 0x7F) > 1) return null
                value = value or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) return value
                shift += 7
            }
            return null
        }

        val numEntriesLong = readVarint() ?: return invalidPmTiles("$label: invalid entry count varint.")
        val maxPossibleEntries = (raw.size - cursor) / MIN_VARINTS_PER_DIR_ENTRY
        if (numEntriesLong <= 0L || numEntriesLong > maxPossibleEntries.toLong()) {
            return invalidPmTiles("$label: invalid or oversized entry count $numEntriesLong.")
        }
        val n = numEntriesLong.toInt()

        val tileIds = LongArray(n)
        var lastId = 0L
        for (i in 0 until n) {
            val delta = readVarint() ?: return invalidPmTiles("$label: invalid tile_id varint at $i.")
            if (i > 0 && delta <= 0L) return invalidPmTiles("$label: non-increasing tile_id delta at $i.")
            if (delta > Long.MAX_VALUE - lastId) return invalidPmTiles("$label: tile_id overflow at $i.")
            lastId += delta
            tileIds[i] = lastId
        }

        val runLengths = LongArray(n)
        for (i in 0 until n) {
            runLengths[i] = readVarint() ?: return invalidPmTiles("$label: invalid run_length varint at $i.")
        }

        val lengths = LongArray(n)
        for (i in 0 until n) {
            lengths[i] = readVarint() ?: return invalidPmTiles("$label: invalid length varint at $i.")
        }

        val offsets = LongArray(n)
        for (i in 0 until n) {
            val tmp = readVarint() ?: return invalidPmTiles("$label: invalid offset varint at $i.")
            if (i > 0 && tmp == 0L) {
                if (offsets[i - 1] > Long.MAX_VALUE - lengths[i - 1]) {
                    return invalidPmTiles("$label: contiguous tile offset overflow at $i.")
                }
                offsets[i] = offsets[i - 1] + lengths[i - 1]
            } else {
                if (tmp == 0L) return invalidPmTiles("$label: zero offset varint on first entry.")
                offsets[i] = tmp - 1L
            }
        }

        if (cursor != raw.size) {
            return invalidPmTiles("$label: ${raw.size - cursor} trailing bytes after directory entries.")
        }

        return PackageResult.Success(
            List(n) { i ->
                DirectoryEntry(
                    tileId = tileIds[i],
                    runLength = runLengths[i],
                    offset = offsets[i],
                    length = lengths[i],
                )
            },
        )
    }

    private fun readAndValidateMetadata(
        channel: FileChannel,
        header: Header,
        label: String,
    ): PackageResult<JsonObject> {
        val rawResult = readAndDecompressSlice(
            channel = channel,
            offset = header.metadataOffset,
            length = header.metadataLength.toInt(),
            compression = header.internalCompression,
            maxUncompressedBytes = ONE_MIB_BYTES,
            label = "$label metadata",
        )
        val rawBytes = when (rawResult) {
            is PackageResult.Success -> rawResult.value
            is PackageResult.Failure -> return rawResult
        }
        val text = decodeStrictUtf8(rawBytes)
            ?: return invalidPmTiles("$label: PMTiles metadata is not valid UTF-8.")
        val elem = try {
            wireJson.parseToJsonElement(text)
        } catch (exc: SerializationException) {
            return invalidPmTiles("$label: invalid PMTiles metadata JSON: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            return invalidPmTiles("$label: invalid PMTiles metadata JSON: ${exc.message}")
        }
        if (elem !is JsonObject) {
            return invalidPmTiles("$label: PMTiles metadata must be a JSON object.")
        }
        if ("trial" in elem || "variant" in elem || "mode" in elem) {
            return invalidPmTiles("$label: trial metadata markers refused (Goal 9).")
        }
        val tileSchema = elem["tile_schema"] as? JsonPrimitive
        if (tileSchema == null || !MapMetadataParser.isExactJsonInteger(tileSchema, 1)) {
            return invalidPmTiles("$label: PMTiles metadata requires integer tile_schema == 1.")
        }
        return PackageResult.Success(elem)
    }

    private fun readAndDecompressSlice(
        channel: FileChannel,
        offset: Long,
        length: Int,
        compression: Int,
        maxUncompressedBytes: Int,
        label: String,
    ): PackageResult<ByteArray> = when (compression) {
        COMPRESSION_NONE -> {
            if (length > maxUncompressedBytes) {
                invalidPmTiles("$label: raw size $length exceeds $maxUncompressedBytes.")
            } else {
                try {
                    PackageResult.Success(readExactAt(channel, offset, length))
                } catch (exc: IOException) {
                    invalidPmTiles("$label: cannot read uncompressed slice: ${exc.message}")
                }
            }
        }

        COMPRESSION_GZIP -> {
            val slice = ChannelSliceInputStream(channel, offset, length.toLong())
            try {
                GZIPInputStream(slice).use { gz ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(GZIP_COPY_BUFFER_BYTES)
                    var total = 0
                    while (true) {
                        val read = gz.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > maxUncompressedBytes) {
                            return invalidPmTiles("$label: decompressed size exceeds $maxUncompressedBytes bytes.")
                        }
                        out.write(buf, 0, read)
                    }
                    PackageResult.Success(out.toByteArray())
                }
            } catch (exc: ZipException) {
                invalidPmTiles("$label: malformed GZIP stream: ${exc.message}")
            } catch (exc: EOFException) {
                invalidPmTiles("$label: truncated GZIP stream: ${exc.message}")
            } catch (exc: IOException) {
                invalidPmTiles("$label: GZIP read failure: ${exc.message}")
            }
        }

        else -> invalidPmTiles("$label: unsupported internal compression $compression.")
    }

    private fun validateBaseConsistency(map: MapMetadata, base: InspectionResult): PackageResult.Failure? {
        if (base.header.tileType != TILE_TYPE_MVT) {
            return invalidPmTiles("data/base.pmtiles tile_type must be MVT (1), got ${base.header.tileType}.")
        }
        if (base.header.tileCompression != COMPRESSION_GZIP) {
            return invalidPmTiles(
                "data/base.pmtiles tile_compression must be GZIP (2), got ${base.header.tileCompression}.",
            )
        }
        if (base.header.maxZoom != map.sources.base.maxZoom) {
            return invalidPmTiles(
                "data/base.pmtiles max_zoom ${base.header.maxZoom} disagrees with map.json ${map.sources.base.maxZoom}.",
            )
        }
        val vectorLayers = base.metadata["vector_layers"] as? JsonArray
            ?: return invalidPmTiles("data/base.pmtiles missing vector_layers array in metadata.")
        val ids = HashSet<String>(vectorLayers.size)
        for (layerElem in vectorLayers) {
            val obj = layerElem as? JsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            ids.add(id)
        }
        if (!ids.containsAll(map.sources.base.layers)) {
            return invalidPmTiles(
                "data/base.pmtiles missing layers declared in map.json: ${map.sources.base.layers - ids}.",
            )
        }
        return null
    }

    private fun validateReliefConsistency(map: MapMetadata, relief: InspectionResult): PackageResult.Failure? {
        if (relief.header.tileCompression != COMPRESSION_NONE) {
            return invalidPmTiles(
                "data/relief.pmtiles tile_compression must be NONE (1), got ${relief.header.tileCompression}.",
            )
        }
        if (relief.header.maxZoom != map.sources.relief.maxZoom) {
            return invalidPmTiles(
                "data/relief.pmtiles max_zoom ${relief.header.maxZoom} disagrees with map.json ${map.sources.relief.maxZoom}.",
            )
        }
        val expectedTileType = when (map.sources.relief.encoding) {
            ReliefEncoding.TERRARIUM_VALUE -> TILE_TYPE_PNG
            ReliefEncoding.PAINTED_RGBA -> TILE_TYPE_WEBP
        }
        if (relief.header.tileType != expectedTileType) {
            return invalidPmTiles(
                "data/relief.pmtiles tile_type ${relief.header.tileType} disagrees with encoding ${map.sources.relief.encoding.wireName}.",
            )
        }
        val actualEncoding = (relief.metadata["encoding"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (actualEncoding != map.sources.relief.encoding.wireName) {
            return invalidPmTiles(
                "data/relief.pmtiles encoding '$actualEncoding' disagrees with '${map.sources.relief.encoding.wireName}'.",
            )
        }
        val headerBounds = relief.header.bounds
            ?: return invalidPmTiles("data/relief.pmtiles header bounds are invalid or degenerate.")
        val extent = map.dataExtent
        if (
            abs(headerBounds.west - extent.west) > BOUNDS_MATCH_TOLERANCE_DEG ||
            abs(headerBounds.south - extent.south) > BOUNDS_MATCH_TOLERANCE_DEG ||
            abs(headerBounds.east - extent.east) > BOUNDS_MATCH_TOLERANCE_DEG ||
            abs(headerBounds.north - extent.north) > BOUNDS_MATCH_TOLERANCE_DEG
        ) {
            return invalidPmTiles("data/relief.pmtiles header bounds disagree with map.json data_extent.")
        }
        return null
    }

    private fun invalidPmTiles(detail: String): PackageResult.Failure =
        PackageResult.Failure(PackageFailure.InvalidPackage(InvalidPackageCause.INVALID_PMTILES, detail))
}
