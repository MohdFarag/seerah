package app.seerah.content.db

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import app.seerah.content.ParsedContentPackage

/** Populates all nine Room 3 tables inside one immediate write transaction (no `@Inject` KSP processing). */
internal suspend fun SeerahContentDatabase.writeParsedPackage(parsed: ParsedContentPackage) {
    useWriterConnection { transactor ->
        transactor.immediateTransaction {
            metaDao().insert(parsed.summary.toEntity())
            if (parsed.phases.isNotEmpty()) {
                phaseDao().insertAll(parsed.phases.map { it.toEntity() })
            }
            if (parsed.sources.isNotEmpty()) {
                sourceDao().insertAll(parsed.sources.map { it.toEntity() })
            }
            if (parsed.places.isNotEmpty()) {
                placeDao().insertAll(parsed.places.map { it.toEntity() })
            }
            if (parsed.areas.isNotEmpty()) {
                areaDao().insertAll(parsed.areas.map { it.toEntity() })
            }
            if (parsed.orderedEvents.isNotEmpty()) {
                eventDao().insertAll(parsed.orderedEvents.map { it.toEntity() })
            }
            if (parsed.routes.isNotEmpty()) {
                routeDao().insertAll(parsed.routes.map { it.toEntity() })
            }
            if (parsed.orderedTexts.isNotEmpty()) {
                textDao().insertAll(parsed.orderedTexts.map { it.toEntity() })
            }
            if (parsed.stories.isNotEmpty()) {
                storyDao().insertAll(parsed.stories.mapIndexed { idx, story -> story.toEntity(sortOrder = idx) })
            }
        }
    }
}
