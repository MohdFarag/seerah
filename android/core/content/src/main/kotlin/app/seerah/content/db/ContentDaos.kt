package app.seerah.content.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

// DAOs strictly use @Insert (never @Upsert, RETURNING, STRICT, or json_extract) for SQLite 3.18.0 (minSdk 26).

@Dao
internal interface MetaDao {
    @Insert
    suspend fun insert(meta: MetaEntity)

    @Query("SELECT * FROM meta WHERE id = 1 LIMIT 1")
    suspend fun getMeta(): MetaEntity?
}

@Dao
internal interface PhaseDao {
    @Insert
    suspend fun insertAll(phases: List<PhaseEntity>)

    @Query("SELECT * FROM phases ORDER BY sort_order ASC, id ASC")
    suspend fun getAll(): List<PhaseEntity>
}

@Dao
internal interface SourceDao {
    @Insert
    suspend fun insertAll(sources: List<SourceEntity>)

    @Query("SELECT * FROM sources ORDER BY id ASC")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SourceEntity>
}

@Dao
internal interface PlaceDao {
    @Insert
    suspend fun insertAll(places: List<PlaceEntity>)

    @Query("SELECT * FROM places ORDER BY id ASC")
    suspend fun getAll(): List<PlaceEntity>
}

@Dao
internal interface AreaDao {
    @Insert
    suspend fun insertAll(areas: List<AreaEntity>)

    @Query("SELECT * FROM areas ORDER BY id ASC")
    suspend fun getAll(): List<AreaEntity>
}

@Dao
internal interface RouteDao {
    @Insert
    suspend fun insertAll(routes: List<RouteEntity>)

    @Query("SELECT * FROM routes ORDER BY id ASC")
    suspend fun getAll(): List<RouteEntity>

    @Query("SELECT * FROM routes WHERE event_id = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: String): RouteEntity?
}

@Dao
internal interface EventDao {
    @Insert
    suspend fun insertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY phase_id ASC, sort_order ASC, id ASC")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE parent_id = :parentId ORDER BY sort_order ASC, id ASC")
    suspend fun getChildren(parentId: String): List<EventEntity>

    @Query("SELECT * FROM events WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<EventEntity>
}

@Dao
internal interface TextDao {
    @Insert
    suspend fun insertAll(texts: List<TextEntity>)

    @Query("SELECT * FROM texts WHERE event_id = :eventId")
    suspend fun getByEventId(eventId: String): List<TextEntity>

    @Query("SELECT * FROM texts WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TextEntity>
}

@Dao
internal interface StoryDao {
    @Insert
    suspend fun insertAll(stories: List<StoryEntity>)

    @Query("SELECT id, sort_order, title, chapter_count FROM stories ORDER BY sort_order ASC, id ASC")
    suspend fun getSummaries(): List<StorySummaryRow>

    @Query("SELECT * FROM stories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StoryEntity?
}
