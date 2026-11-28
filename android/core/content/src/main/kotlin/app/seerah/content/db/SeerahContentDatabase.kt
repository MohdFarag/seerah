package app.seerah.content.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Disposable Room 3 SQLite database backing one verified content package generation. */
@Database(
    entities = [
        MetaEntity::class,
        PhaseEntity::class,
        SourceEntity::class,
        PlaceEntity::class,
        AreaEntity::class,
        RouteEntity::class,
        EventEntity::class,
        TextEntity::class,
        StoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class SeerahContentDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao
    abstract fun phaseDao(): PhaseDao
    abstract fun sourceDao(): SourceDao
    abstract fun placeDao(): PlaceDao
    abstract fun areaDao(): AreaDao
    abstract fun routeDao(): RouteDao
    abstract fun eventDao(): EventDao
    abstract fun textDao(): TextDao
    abstract fun storyDao(): StoryDao
}

/** Abstracts Room 3 opening so Robolectric/JVM tests can plug in alternative SQLite drivers if needed. */
internal fun interface ContentDatabaseOpener {
    fun open(dbFile: File): SeerahContentDatabase
}

/** Production [ContentDatabaseOpener] using system SQLite via [AndroidSQLiteDriver] and `TRUNCATE` journaling. */
internal class AndroidContentDatabaseOpener @Inject constructor(@ApplicationContext private val context: Context) :
    ContentDatabaseOpener {
    override fun open(dbFile: File): SeerahContentDatabase = Room.databaseBuilder<SeerahContentDatabase>(
        context = context,
        name = dbFile.absolutePath,
    )
        .setDriver(AndroidSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()
}
