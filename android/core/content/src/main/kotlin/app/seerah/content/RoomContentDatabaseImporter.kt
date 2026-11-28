package app.seerah.content

import android.database.sqlite.SQLiteFullException
import androidx.sqlite.SQLiteException
import app.seerah.content.db.ContentDatabaseOpener
import app.seerah.content.db.SeerahContentDatabase
import app.seerah.content.db.writeParsedPackage
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

private const val MAX_CONTENT_JSON_BYTES = 32 * 1024 * 1024

/** Default Room 3 [ContentDatabaseImporter] writing all nine tables inside one immediate transaction. */
internal class RoomContentDatabaseImporter @Inject constructor(private val dbOpener: ContentDatabaseOpener) :
    ContentDatabaseImporter {
    override suspend fun importFromZip(
        channel: FileChannel,
        container: StrictZipReader.ZipContainer,
        manifest: PackageManifest,
        packageVersion: Int,
        generation: Long,
        targetDbFile: File,
    ): PackageResult<Unit> {
        if (manifest.kind != PackageKind.CONTENT) {
            return PackageResult.Failure(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.UNEXPECTED_KIND,
                    "Expected content manifest for Room import, got ${manifest.kind.wireName}.",
                ),
            )
        }
        val expectedNames = listOf(StrictZipReader.MANIFEST_ENTRY_NAME, StrictZipReader.SIGNATURE_ENTRY_NAME) +
            manifest.files.map { it.path }
        val actualNames = container.entries.map { it.path }
        if (actualNames != expectedNames) {
            return PackageResult.Failure(
                PackageFailure.InvalidPackage(
                    InvalidPackageCause.INVALID_CONTAINER,
                    "ZIP entry list does not match verified manifest files.",
                ),
            )
        }

        val entryBytes = LinkedHashMap<String, ByteArray>(REQUIRED_CONTENT_DATA_PATHS.size)
        for (fileEntry in manifest.files) {
            val zipEntry = container.entriesByPath.getValue(fileEntry.path)
            if (fileEntry.path in REQUIRED_CONTENT_DATA_PATHS) {
                val readRes = StrictZipReader.readVerifiedEntryBytes(
                    channel = channel,
                    entry = zipEntry,
                    manifestEntry = fileEntry,
                    maxBytes = MAX_CONTENT_JSON_BYTES,
                )
                when (readRes) {
                    is PackageResult.Success -> entryBytes[fileEntry.path] = readRes.value
                    is PackageResult.Failure -> return readRes
                }
            } else {
                val discardRes = StrictZipReader.verifyEntryBytesDiscarding(
                    channel = channel,
                    entry = zipEntry,
                    manifestEntry = fileEntry,
                )
                if (discardRes is PackageResult.Failure) return discardRes
            }
        }

        val parsed = when (val res = ContentPackageParser.parseAndValidate(entryBytes, packageVersion, generation)) {
            is PackageResult.Success -> res.value
            is PackageResult.Failure -> return res
        }

        val totalJsonBytes = manifest.files.sumOf { it.sizeBytes }
        val parentDir = targetDbFile.parentFile
        if (parentDir != null && !parentDir.isDirectory && !parentDir.mkdirs()) {
            return PackageResult.Failure(
                PackageFailure.IoFailure("Cannot create database parent directory: ${parentDir.path}"),
            )
        }
        deleteSqliteFiles(targetDbFile)

        var db: SeerahContentDatabase? = null
        return try {
            val openedDb = dbOpener.open(targetDbFile)
            db = openedDb
            openedDb.writeParsedPackage(parsed)
            openedDb.close()
            db = null
            fsyncFile(targetDbFile)
            PackageResult.Success(Unit)
        } catch (exc: CancellationException) {
            runCatching { db?.close() }
            deleteSqliteFiles(targetDbFile)
            throw exc
        } catch (exc: SQLiteFullException) {
            runCatching { db?.close() }
            deleteSqliteFiles(targetDbFile)
            PackageResult.Failure(PackageFailure.InsufficientStorage(totalJsonBytes * 3L, 0L))
        } catch (exc: SQLiteException) {
            runCatching { db?.close() }
            deleteSqliteFiles(targetDbFile)
            if (isSqliteFullError(exc)) {
                PackageResult.Failure(PackageFailure.InsufficientStorage(totalJsonBytes * 3L, 0L))
            } else {
                PackageResult.Failure(PackageFailure.IoFailure("SQLite import error: ${exc.message}"))
            }
        } catch (exc: IOException) {
            runCatching { db?.close() }
            deleteSqliteFiles(targetDbFile)
            if (isSqliteFullError(exc)) {
                PackageResult.Failure(PackageFailure.InsufficientStorage(totalJsonBytes * 3L, 0L))
            } else {
                PackageResult.Failure(PackageFailure.IoFailure("Database I/O error: ${exc.message}"))
            }
        }
    }

    private fun fsyncFile(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
            ch.force(true)
        }
    }
}
