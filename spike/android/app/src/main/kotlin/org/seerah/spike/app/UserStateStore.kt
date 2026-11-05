package org.seerah.spike.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import org.seerah.spike.core.UserState

/** User progress and last view, in its own database, never touched by content updates. */
@Entity(tableName = "user_state")
data class UserStateRow(
    @PrimaryKey val id: Int = 0,
    val year: Int,
    val lon: Double,
    val lat: Double,
    val zoom: Double,
    val followEventId: String?,
    val openEventId: String?,
) {
    fun toCore() = UserState(year, lon, lat, zoom, followEventId, openEventId)

    companion object {
        fun of(s: UserState) = UserStateRow(0, s.year, s.lon, s.lat, s.zoom, s.followEventId, s.openEventId)
    }
}

@Dao
interface UserStateDao {
    @Query("SELECT * FROM user_state WHERE id = 0")
    suspend fun get(): UserStateRow?

    @Upsert
    suspend fun put(row: UserStateRow)
}

@Database(entities = [UserStateRow::class], version = 1, exportSchema = false)
abstract class UserDb : RoomDatabase() {
    abstract fun state(): UserStateDao

    companion object {
        fun open(context: Context): UserDb =
            Room.databaseBuilder(context, UserDb::class.java, "user.db").build()
    }
}
