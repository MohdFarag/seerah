package app.seerah.content

import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StrictZipReaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun ascii(text: String): ByteArray = text.toByteArray(StandardCharsets.US_ASCII)

    private fun baseEntries(): List<TestArchiveBuilders.RawZipSpecEntry> = listOf(
        TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.json"), ascii("""{"stub":1}""")),
        TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.sig"), ByteArray(64)),
        TestArchiveBuilders.RawZipSpecEntry(ascii("data/a.json"), ascii("""{"ok":true}""")),
    )

    private fun inspectBytes(
        bytes: ByteArray,
        requireAllStored: Boolean = false,
    ): PackageResult<StrictZipReader.ZipContainer> {
        val file = TestArchiveBuilders.writeTempBytes(tempFolder.newFolder(), "test.zip", bytes)
        return FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
            StrictZipReader.inspectContainer(ch, requireAllStored)
        }
    }

    private fun assertContainerFails(bytes: ByteArray, requireAllStored: Boolean = false) {
        val res = inspectBytes(bytes, requireAllStored)
        val failure = assertIs<PackageResult.Failure>(res)
        val err = assertIs<PackageFailure.InvalidPackage>(failure.reason)
        assertEquals(InvalidPackageCause.INVALID_CONTAINER, err.cause)
    }

    @Test
    fun rejectsHostileContainerLayoutsAndHeaders() {
        val clean = baseEntries()
        assertIs<PackageResult.Success<StrictZipReader.ZipContainer>>(
            inspectBytes(TestArchiveBuilders.buildRawZip(clean)),
        )

        // Comments, trailing bytes, prepended bytes, gaps
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean, archiveComment = ascii("c")))
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean, trailingBytes = ascii("junk")))
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean, prependedBytes = ascii("pad")))
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean, gapBetweenFirstAndSecond = 4))
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean, trailingCdBytes = 3))

        // ZIP64 sentinels
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean, eocdEntriesOverride = 0xFFFF))
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean, eocdCdSizeOverride = 0xFFFF_FFFFL))
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean, eocdCdOffsetOverride = 0xFFFF_FFFFL))

        // Entry count < 3 or swapped order
        assertContainerFails(TestArchiveBuilders.buildRawZip(clean.take(2)))
        assertContainerFails(TestArchiveBuilders.buildRawZip(listOf(clean[1], clean[0], clean[2])))

        // Encrypted, data-descriptor, unsupported flagBits, STORED w/ DEFLATE bits
        assertContainerFails(TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(cdFlags = 0x01))))
        assertContainerFails(TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(cdFlags = 0x08))))
        assertContainerFails(TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(cdFlags = 0x40))))
        assertContainerFails(TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(cdFlags = 0x02))))

        // CD diskNumberStart != 0, CD/LFH extra fields, LFH mismatches
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(cdDiskNumberStart = 1))),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(cdExtra = byteArrayOf(1)))),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(lfhExtra = byteArrayOf(1)))),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(
                listOf(clean[0], clean[1], clean[2].copy(lfhNameBytes = ascii("data/b.json"))),
            ),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(lfhCompSize = 999))),
        )

        // Non-ASCII raw bytes (invalid in CD), symlink, FIFO, DOS directory, path traversal, duplicate names
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(
                listOf(
                    clean[0],
                    clean[1],
                    clean[2].copy(nameBytes = "data/bad\u0080".toByteArray(StandardCharsets.ISO_8859_1)),
                ),
            ),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(
                listOf(
                    clean[0],
                    clean[1],
                    clean[2].copy(
                        externalAttr =
                            0xA1FFL shl 16,
                    ),
                ),
            ),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(
                listOf(
                    clean[0],
                    clean[1],
                    clean[2].copy(
                        externalAttr =
                            0x11A4L shl 16,
                    ),
                ),
            ),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(externalAttr = 0x10L))),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(
                listOf(clean[0], clean[1], clean[2].copy(nameBytes = ascii("data/../evil"))),
            ),
        )
        assertContainerFails(
            TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2].copy(nameBytes = ascii("data//evil")))),
        )
        assertContainerFails(TestArchiveBuilders.buildRawZip(listOf(clean[0], clean[1], clean[2], clean[2])))

        // Map package refuses DEFLATE
        val deflatedContent = listOf(clean[0], clean[1], clean[2].copy(method = 8))
        assertIs<PackageResult.Success<StrictZipReader.ZipContainer>>(
            inspectBytes(TestArchiveBuilders.buildRawZip(deflatedContent), requireAllStored = false),
        )
        assertContainerFails(TestArchiveBuilders.buildRawZip(deflatedContent), requireAllStored = true)
    }

    @Test
    fun readStoredEntryBytes_enforcesMandatoryCrc32OnManifestAndSignature() {
        val entries = listOf(
            TestArchiveBuilders.RawZipSpecEntry(
                ascii("manifest.json"),
                ascii("""{"a":1}"""),
                crc32Override = 0xDEADBEEFL,
            ),
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.sig"), ByteArray(64), crc32Override = 0x12345678L),
            TestArchiveBuilders.RawZipSpecEntry(ascii("data/x.json"), ascii("{}")),
        )
        val zipFile = TestArchiveBuilders.writeTempBytes(
            tempFolder.newFolder(),
            "bad-crc.zip",
            TestArchiveBuilders.buildRawZip(entries),
        )
        FileChannel.open(zipFile.toPath(), StandardOpenOption.READ).use { ch ->
            val container = (StrictZipReader.inspectContainer(ch, false) as PackageResult.Success).value
            val rManifest = StrictZipReader.readStoredEntryBytes(
                ch,
                container.entries[0],
                1,
                ONE_MIB_BYTES,
                "manifest.json",
            )
            val rSig = StrictZipReader.readStoredEntryBytes(ch, container.entries[1], 64, 64, "manifest.sig")
            assertEquals(
                InvalidPackageCause.PAYLOAD_INTEGRITY,
                assertIs<PackageFailure.InvalidPackage>((rManifest as PackageResult.Failure).reason).cause,
            )
            assertEquals(
                InvalidPackageCause.PAYLOAD_INTEGRITY,
                assertIs<PackageFailure.InvalidPackage>((rSig as PackageResult.Failure).reason).cause,
            )
        }
    }

    @Test
    fun extractDeflated_rejectsTrailingBytesZeroByteZipBombAndCorruptStreams() = runTest {
        val work = tempFolder.newFolder()
        val staging = File(work, "staging").apply { mkdirs() }

        // 1. Valid 0-byte DEFLATED entry passes
        val emptyDeflated = TestArchiveBuilders.deflateRaw(ByteArray(0))
        val validEmptyEntries = listOf(
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.json"), ascii("{}")),
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.sig"), ByteArray(64)),
            TestArchiveBuilders.RawZipSpecEntry(
                nameBytes = ascii("data/empty.txt"),
                payloadBytes = ByteArray(0),
                method = 8,
            ),
        )
        val validEmptyZip = TestArchiveBuilders.writeTempBytes(
            work,
            "empty-ok.zip",
            TestArchiveBuilders.buildRawZip(validEmptyEntries),
        )
        FileChannel.open(validEmptyZip.toPath(), StandardOpenOption.READ).use { ch ->
            val c = (StrictZipReader.inspectContainer(ch, false) as PackageResult.Success).value
            val entryMeta = PackageFileEntry("data/empty.txt", 0L, sha256Hex(ByteArray(0)))
            assertIs<PackageResult.Success<File>>(
                StrictZipReader.extractEntryToFile(ch, c.entries[2], entryMeta, staging),
            )
        }

        // 2. 0-byte DEFLATED entry with extra compressed bytes overflowing size (zip bomb) -> rejected
        val nonEmptyCompressed = TestArchiveBuilders.deflateRaw(ascii("boom"))
        val zeroBombEntries = listOf(
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.json"), ascii("{}")),
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.sig"), ByteArray(64)),
            TestArchiveBuilders.RawZipSpecEntry(
                nameBytes = ascii("data/bomb.txt"),
                payloadBytes = nonEmptyCompressed,
                method = 8,
                crc32Override = 0L,
                compressedSizeOverride = nonEmptyCompressed.size.toLong(),
                uncompressedSizeOverride = 0L,
            ),
        )
        val bombZip = TestArchiveBuilders.writeTempBytes(
            work,
            "zero-bomb.zip",
            TestArchiveBuilders.buildRawZip(zeroBombEntries),
        )
        FileChannel.open(bombZip.toPath(), StandardOpenOption.READ).use { ch ->
            val c = (StrictZipReader.inspectContainer(ch, false) as PackageResult.Success).value
            val entryMeta = PackageFileEntry("data/bomb.txt", 0L, sha256Hex(ByteArray(0)))
            val fail =
                assertIs<PackageResult.Failure>(
                    StrictZipReader.extractEntryToFile(ch, c.entries[2], entryMeta, staging),
                )
            assertEquals(
                InvalidPackageCause.PAYLOAD_INTEGRITY,
                assertIs<PackageFailure.InvalidPackage>(fail.reason).cause,
            )
        }

        // 3. DEFLATE stream with trailing unauthenticated bytes inside compressed slice -> rejected by bytesRead check
        val realPlain = ascii("hello")
        val realDeflated = TestArchiveBuilders.deflateRaw(realPlain)
        val paddedDeflated = realDeflated + ascii("smuggled")
        val crcReal = CRC32().apply { update(realPlain) }.value and 0xFFFF_FFFFL
        val smuggledEntries = listOf(
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.json"), ascii("{}")),
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.sig"), ByteArray(64)),
            TestArchiveBuilders.RawZipSpecEntry(
                nameBytes = ascii("data/smuggled.txt"),
                payloadBytes = paddedDeflated,
                method = 8,
                crc32Override = crcReal,
                compressedSizeOverride = paddedDeflated.size.toLong(),
                uncompressedSizeOverride = realPlain.size.toLong(),
            ),
        )
        val smuggledZip = TestArchiveBuilders.writeTempBytes(
            work,
            "smuggled.zip",
            TestArchiveBuilders.buildRawZip(smuggledEntries),
        )
        FileChannel.open(smuggledZip.toPath(), StandardOpenOption.READ).use { ch ->
            val c = (StrictZipReader.inspectContainer(ch, false) as PackageResult.Success).value
            val entryMeta = PackageFileEntry("data/smuggled.txt", realPlain.size.toLong(), sha256Hex(realPlain))
            val fail =
                assertIs<PackageResult.Failure>(
                    StrictZipReader.extractEntryToFile(ch, c.entries[2], entryMeta, staging),
                )
            assertEquals(
                InvalidPackageCause.PAYLOAD_INTEGRITY,
                assertIs<PackageFailure.InvalidPackage>(fail.reason).cause,
            )
        }

        // 4. Corrupt DEFLATE bytes -> ZipException/DataFormatException cleanly mapped to PAYLOAD_INTEGRITY
        val corruptBytes = byteArrayOf(0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte())
        val corruptEntries = listOf(
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.json"), ascii("{}")),
            TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.sig"), ByteArray(64)),
            TestArchiveBuilders.RawZipSpecEntry(
                nameBytes = ascii("data/corrupt.txt"),
                payloadBytes = corruptBytes,
                method = 8,
                crc32Override = 0L,
                compressedSizeOverride = corruptBytes.size.toLong(),
                uncompressedSizeOverride = 4L,
            ),
        )
        val corruptZip = TestArchiveBuilders.writeTempBytes(
            work,
            "corrupt-deflate.zip",
            TestArchiveBuilders.buildRawZip(corruptEntries),
        )
        FileChannel.open(corruptZip.toPath(), StandardOpenOption.READ).use { ch ->
            val c = (StrictZipReader.inspectContainer(ch, false) as PackageResult.Success).value
            val entryMeta = PackageFileEntry("data/corrupt.txt", 4L, sha256Hex(ascii("abcd")))
            val fail =
                assertIs<PackageResult.Failure>(
                    StrictZipReader.extractEntryToFile(ch, c.entries[2], entryMeta, staging),
                )
            assertEquals(
                InvalidPackageCause.PAYLOAD_INTEGRITY,
                assertIs<PackageFailure.InvalidPackage>(fail.reason).cause,
            )
        }
        assertEquals(2, emptyDeflated.size)
    }

    @Test
    fun extractEntryToFile_propagatesCoroutineCancellationWithoutLeakingFile() = runTest {
        val work = tempFolder.newFolder("cancel")
        val staging = File(work, "staging").apply { mkdirs() }
        val payload = ascii("cancel-test-payload")
        val zipFile = TestArchiveBuilders.writeTempBytes(
            work,
            "cancel.zip",
            TestArchiveBuilders.buildRawZip(
                listOf(
                    TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.json"), ascii("{}")),
                    TestArchiveBuilders.RawZipSpecEntry(ascii("manifest.sig"), ByteArray(64)),
                    TestArchiveBuilders.RawZipSpecEntry(ascii("data/payload.txt"), payload),
                ),
            ),
        )

        val cancelledJob = Job().apply { cancel() }
        var caughtCancellation = false
        FileChannel.open(zipFile.toPath(), StandardOpenOption.READ).use { ch ->
            val c = (StrictZipReader.inspectContainer(ch, false) as PackageResult.Success).value
            val entryMeta = PackageFileEntry("data/payload.txt", payload.size.toLong(), sha256Hex(payload))
            try {
                withContext(cancelledJob) {
                    StrictZipReader.extractEntryToFile(ch, c.entries[2], entryMeta, staging)
                }
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        assertEquals(true, caughtCancellation)
        assertFalse(File(staging, "data/payload.txt").exists())
    }
}
