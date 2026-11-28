package app.seerah.content

import java.io.File
import java.nio.channels.FileChannel

/**
 * Streams a verified `content` archive directly into a disposable Room 3 `content.db` without
 * unpacking permanent payload JSON copies on disk.
 */
internal interface ContentDatabaseImporter {
    suspend fun importFromZip(
        channel: FileChannel,
        container: StrictZipReader.ZipContainer,
        manifest: PackageManifest,
        packageVersion: Int,
        generation: Long,
        targetDbFile: File,
    ): PackageResult<Unit>
}
