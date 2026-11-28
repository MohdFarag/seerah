package app.seerah.content

import androidx.sqlite.SQLiteException
import app.seerah.content.db.toModel
import app.seerah.content.di.ContentScope
import app.seerah.content.di.IoDispatcher
import app.seerah.model.ContentText
import app.seerah.model.EventDetail
import app.seerah.model.SeerahEvent
import app.seerah.model.Story
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val SQLITE_IN_CHUNK_SIZE = 900

/**
 * Default [ContentRepository] backed by [ContentDatabaseManager] and lazy SQLite Room 3 queries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultContentRepository @Inject constructor(
    private val manager: ContentDatabaseManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ContentScope private val contentScope: CoroutineScope,
) : ContentRepository {
    private val contentStateFlow: StateFlow<ContentState> = manager.activeState
        .map { state ->
            when (state) {
                ActiveDbState.Loading -> ContentState.Loading
                ActiveDbState.NotInstalled -> ContentState.NotInstalled
                is ActiveDbState.Ready -> ContentState.Ready(state.corpus)
                is ActiveDbState.Error -> ContentState.Error(state.failure)
            }
        }
        .stateIn(
            scope = contentScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0L),
            initialValue = ContentState.Loading,
        )

    override fun observeContentState(): StateFlow<ContentState> = contentStateFlow

    override fun observeEventDetail(eventId: String): Flow<ContentLookupResult<EventDetail>> =
        manager.activeState.flatMapLatest { state ->
            flow {
                when (state) {
                    ActiveDbState.Loading -> emit(ContentLookupResult.Loading)

                    ActiveDbState.NotInstalled -> emit(ContentLookupResult.NotFound)

                    is ActiveDbState.Error -> emit(ContentLookupResult.Error(state.failure))

                    is ActiveDbState.Ready -> {
                        emit(ContentLookupResult.Loading)
                        val result = queryWithRepairRetry(state.handle) { db ->
                            loadEventDetail(db, eventId)
                        }
                        if (result != null) {
                            emit(result)
                        } else {
                            // Handle retired or triggered disposable rebuild; stay Loading until next activeState.
                            emit(ContentLookupResult.Loading)
                        }
                    }
                }
            }
        }

    override fun observeStory(storyId: String): Flow<ContentLookupResult<Story>> =
        manager.activeState.flatMapLatest { state ->
            flow {
                when (state) {
                    ActiveDbState.Loading -> emit(ContentLookupResult.Loading)

                    ActiveDbState.NotInstalled -> emit(ContentLookupResult.NotFound)

                    is ActiveDbState.Error -> emit(ContentLookupResult.Error(state.failure))

                    is ActiveDbState.Ready -> {
                        emit(ContentLookupResult.Loading)
                        val result = queryWithRepairRetry(state.handle) { db ->
                            val row = db.storyDao().getById(storyId)
                            if (row == null) {
                                ContentLookupResult.NotFound
                            } else {
                                ContentLookupResult.Found(row.toModel())
                            }
                        }
                        if (result != null) {
                            emit(result)
                        } else {
                            emit(ContentLookupResult.Loading)
                        }
                    }
                }
            }
        }

    private suspend fun <T> queryWithRepairRetry(
        handle: ActiveDbHandle,
        query: suspend (app.seerah.content.db.SeerahContentDatabase) -> ContentLookupResult<T>,
    ): ContentLookupResult<T>? = withContext(ioDispatcher) {
        try {
            handle.withLease { db -> query(db) }
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: SQLiteException) {
            if (isSqliteFullError(exc)) {
                ContentLookupResult.Error(PackageFailure.InsufficientStorage(0L, 0L))
            } else {
                manager.requestRepair(handle)
                null
            }
        } catch (_: IllegalStateException) {
            manager.requestRepair(handle)
            null
        } catch (exc: IllegalArgumentException) {
            manager.requestRepair(handle)
            null
        }
    }

    private suspend fun loadEventDetail(
        db: app.seerah.content.db.SeerahContentDatabase,
        eventId: String,
    ): ContentLookupResult<EventDetail> {
        val eventRow = db.eventDao().getById(eventId) ?: return ContentLookupResult.NotFound
        val event = eventRow.toModel()

        val parentEvent = event.parentId?.let { pid -> db.eventDao().getById(pid)?.toModel() }
        val childEvents = db.eventDao().getChildren(event.id).map { it.toModel() }
        val route = db.routeDao().getByEventId(event.id)?.toModel()

        val attachedTexts = db.textDao().getByEventId(event.id).map { it.toModel() }
        val textsById = LinkedHashMap<String, ContentText>(attachedTexts.size * 2)
        for (t in attachedTexts) {
            textsById[t.id] = t
        }

        val referencedTextIds = (event.eventTextIds + event.situations.flatMap { it.textIds })
            .filter { it !in textsById }
            .distinct()
        if (referencedTextIds.isNotEmpty()) {
            for (chunk in referencedTextIds.chunked(SQLITE_IN_CHUNK_SIZE)) {
                for (row in db.textDao().getByIds(chunk)) {
                    val model = row.toModel()
                    textsById[model.id] = model
                }
            }
        }

        val excerptParentIds = textsById.values
            .mapNotNull { it.excerptOfTextId }
            .filter { it !in textsById }
            .distinct()
        if (excerptParentIds.isNotEmpty()) {
            for (chunk in excerptParentIds.chunked(SQLITE_IN_CHUNK_SIZE)) {
                for (row in db.textDao().getByIds(chunk)) {
                    val model = row.toModel()
                    textsById[model.id] = model
                }
            }
        }

        val relatedTargetIds = event.related.map { it.targetEventId }.distinct()
        val relatedTargetsById = HashMap<String, SeerahEvent>(relatedTargetIds.size)
        if (relatedTargetIds.isNotEmpty()) {
            for (chunk in relatedTargetIds.chunked(SQLITE_IN_CHUNK_SIZE)) {
                for (row in db.eventDao().getByIds(chunk)) {
                    val model = row.toModel()
                    relatedTargetsById[model.id] = model
                }
            }
        }
        val relatedEvents = event.related.mapNotNull { rel ->
            relatedTargetsById[rel.targetEventId]?.let { target -> rel to target }
        }

        val resolvedTexts = textsById.values.toList()
        val sourceWorkIds = buildList {
            for (text in resolvedTexts) {
                text.source?.sourceWorkId?.let(::add)
                text.explanationSource?.sourceWorkId?.let(::add)
            }
        }.distinct()
        val sources = LinkedHashMap<String, app.seerah.model.SourceWork>(sourceWorkIds.size)
        if (sourceWorkIds.isNotEmpty()) {
            for (chunk in sourceWorkIds.chunked(SQLITE_IN_CHUNK_SIZE)) {
                for (row in db.sourceDao().getByIds(chunk)) {
                    val model = row.toModel()
                    sources[model.id] = model
                }
            }
        }

        return ContentLookupResult.Found(
            EventDetail(
                event = event,
                parentEvent = parentEvent,
                childEvents = childEvents,
                route = route,
                texts = resolvedTexts,
                relatedEvents = relatedEvents,
                sources = sources,
            ),
        )
    }
}
