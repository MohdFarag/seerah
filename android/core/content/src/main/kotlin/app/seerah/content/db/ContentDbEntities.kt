package app.seerah.content.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.seerah.model.Area
import app.seerah.model.ContentPackageSummary
import app.seerah.model.ContentText
import app.seerah.model.DatePrecision
import app.seerah.model.DistanceBasis
import app.seerah.model.EventDepth
import app.seerah.model.EventKind
import app.seerah.model.ExpeditionRole
import app.seerah.model.GeoPoint
import app.seerah.model.HistoricalDate
import app.seerah.model.Phase
import app.seerah.model.Place
import app.seerah.model.PlaceLocation
import app.seerah.model.Route
import app.seerah.model.RouteGeometry
import app.seerah.model.SeerahEvent
import app.seerah.model.SourceCitation
import app.seerah.model.SourceKind
import app.seerah.model.SourceWork
import app.seerah.model.Story
import app.seerah.model.StorySummary
import app.seerah.model.TextKind
import app.seerah.model.TimelinePoint
import app.seerah.model.Visibility

@Entity(tableName = "meta")
internal data class MetaEntity(
    @PrimaryKey
    val id: Int = 1,
    @ColumnInfo(name = "content_schema")
    val contentSchema: Int,
    @ColumnInfo(name = "content_version")
    val contentVersion: String,
    @ColumnInfo(name = "package_version")
    val packageVersion: Int,
    val generation: Long,
    @ColumnInfo(name = "min_axis")
    val minAxis: String?,
    @ColumnInfo(name = "min_year")
    val minYear: Int?,
    @ColumnInfo(name = "max_axis")
    val maxAxis: String?,
    @ColumnInfo(name = "max_year")
    val maxYear: Int?,
)

@Entity(tableName = "phases")
internal data class PhaseEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    val title: String,
    val skippable: Boolean,
)

@Entity(tableName = "sources")
internal data class SourceEntity(
    @PrimaryKey
    val id: String,
    val kind: String,
    val work: String,
    val edition: String?,
    val url: String?,
)

@Entity(tableName = "places")
internal data class PlaceEntity(
    @PrimaryKey
    val id: String,
    val kind: String,
    val accuracy: String,
    val lat: Double?,
    val lon: Double?,
    @ColumnInfo(name = "off_map")
    val offMap: Boolean,
    val visibility: String,
    val prominence: Int,
    @ColumnInfo(name = "states_json")
    val statesJson: String,
)

@Entity(tableName = "areas")
internal data class AreaEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "label_lat")
    val labelLat: Double?,
    @ColumnInfo(name = "label_lon")
    val labelLon: Double?,
    @ColumnInfo(name = "polygon_json")
    val polygonJson: String?,
    @ColumnInfo(name = "off_map")
    val offMap: Boolean,
    @ColumnInfo(name = "states_json")
    val statesJson: String,
)

@Entity(
    tableName = "routes",
    indices = [Index(value = ["event_id"])],
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class RouteEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    val documented: Boolean,
    @ColumnInfo(name = "stations_json")
    val stationsJson: String,
    @ColumnInfo(name = "coordinates_json")
    val coordinatesJson: String?,
    @ColumnInfo(name = "station_waypoints_json")
    val stationWaypointsJson: String?,
    @ColumnInfo(name = "accuracy_note")
    val accuracyNote: String?,
    @ColumnInfo(name = "distance_km")
    val distanceKm: Double?,
    @ColumnInfo(name = "distance_basis")
    val distanceBasis: String?,
    val days: Int?,
)

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["phase_id"]),
        Index(value = ["axis", "continuous_year"]),
        Index(value = ["parent_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PhaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phase_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class EventEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "phase_id")
    val phaseId: String,
    @ColumnInfo(name = "parent_id")
    val parentId: String?,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    val kind: String,
    val depth: String,
    @ColumnInfo(name = "expedition_role")
    val expeditionRole: String?,
    val title: String,
    @ColumnInfo(name = "date_precision")
    val datePrecision: String,
    val axis: String?,
    @ColumnInfo(name = "continuous_year")
    val continuousYear: Int?,
    val year: Int?,
    val month: Int?,
    val day: Int?,
    @ColumnInfo(name = "prophethood_year")
    val prophethoodYear: Int?,
    val approximate: Boolean,
    @ColumnInfo(name = "date_note")
    val dateNote: String?,
    @ColumnInfo(name = "where_json")
    val whereJson: String,
    val visibility: String,
    val prominence: Int,
    @ColumnInfo(name = "lead_in")
    val leadIn: String?,
    val outcome: String?,
    @ColumnInfo(name = "situations_json")
    val situationsJson: String,
    @ColumnInfo(name = "event_text_ids_json")
    val eventTextIdsJson: String,
    @ColumnInfo(name = "related_json")
    val relatedJson: String,
    @ColumnInfo(name = "tags_json")
    val tagsJson: String,
)

@Entity(
    tableName = "texts",
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["source_work_id"]),
        Index(value = ["excerpt_of_text_id"]),
        Index(value = ["explanation_source_work_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_work_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TextEntity::class,
            parentColumns = ["id"],
            childColumns = ["excerpt_of_text_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["explanation_source_work_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class TextEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "event_id")
    val eventId: String?,
    val kind: String,
    val body: String,
    @ColumnInfo(name = "source_work_id")
    val sourceWorkId: String?,
    val locator: String?,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String?,
    @ColumnInfo(name = "excerpt_of_text_id")
    val excerptOfTextId: String?,
    val explanation: String?,
    @ColumnInfo(name = "explanation_source_work_id")
    val explanationSourceWorkId: String?,
    @ColumnInfo(name = "explanation_locator")
    val explanationLocator: String?,
    @ColumnInfo(name = "explanation_url")
    val explanationUrl: String?,
)

@Entity(tableName = "stories")
internal data class StoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    val title: String,
    @ColumnInfo(name = "chapter_count")
    val chapterCount: Int,
    @ColumnInfo(name = "chapters_json")
    val chaptersJson: String,
)

internal data class StorySummaryRow(
    val id: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    val title: String,
    @ColumnInfo(name = "chapter_count")
    val chapterCount: Int,
)

internal fun ContentPackageSummary.toEntity(): MetaEntity = MetaEntity(
    id = 1,
    contentSchema = contentSchema,
    contentVersion = contentVersion,
    packageVersion = packageVersion,
    generation = generation,
    minAxis = minTimelinePoint?.axis?.wireName,
    minYear = minTimelinePoint?.year,
    maxAxis = maxTimelinePoint?.axis?.wireName,
    maxYear = maxTimelinePoint?.year,
)

internal fun MetaEntity.toModel(): ContentPackageSummary = ContentPackageSummary(
    contentSchema = contentSchema,
    contentVersion = contentVersion,
    packageVersion = packageVersion,
    generation = generation,
    minTimelinePoint = if (minAxis != null && minYear != null) {
        TimelinePoint(axis = ContentDbJsonCodec.parseCalendarAxis(minAxis), year = minYear)
    } else {
        require(minAxis == null && minYear == null) { "Partial minTimelinePoint in meta table." }
        null
    },
    maxTimelinePoint = if (maxAxis != null && maxYear != null) {
        TimelinePoint(axis = ContentDbJsonCodec.parseCalendarAxis(maxAxis), year = maxYear)
    } else {
        require(maxAxis == null && maxYear == null) { "Partial maxTimelinePoint in meta table." }
        null
    },
)

internal fun Phase.toEntity(): PhaseEntity =
    PhaseEntity(id = id, sortOrder = order, title = title, skippable = skippable)

internal fun PhaseEntity.toModel(): Phase = Phase(id = id, order = sortOrder, title = title, skippable = skippable)

internal fun SourceWork.toEntity(): SourceEntity = SourceEntity(
    id = id,
    kind = kind.wireName,
    work = work,
    edition = edition,
    url = url,
)

internal fun SourceEntity.toModel(): SourceWork = SourceWork(
    id = id,
    kind = SourceKind.entries.firstOrNull { it.wireName == kind }
        ?: throw IllegalArgumentException("Unknown SourceKind '$kind' in database."),
    work = work,
    edition = edition,
    url = url,
)

internal fun Place.toEntity(): PlaceEntity {
    val accuracyWire: String
    val lat: Double?
    val lon: Double?
    val offMap: Boolean
    when (val loc = location) {
        is PlaceLocation.OnMap -> {
            accuracyWire = loc.accuracy.wireName
            lat = loc.point.lat
            lon = loc.point.lon
            offMap = false
        }

        is PlaceLocation.OffMap -> {
            accuracyWire = loc.accuracy.wireName
            lat = loc.point?.lat
            lon = loc.point?.lon
            offMap = true
        }

        PlaceLocation.Unknown -> {
            accuracyWire = "unknown"
            lat = null
            lon = null
            offMap = false
        }
    }
    return PlaceEntity(
        id = id,
        kind = kind.wireName,
        accuracy = accuracyWire,
        lat = lat,
        lon = lon,
        offMap = offMap,
        visibility = visibility.wireName,
        prominence = prominence,
        statesJson = ContentDbJsonCodec.encodePlaceStates(states),
    )
}

internal fun PlaceEntity.toModel(): Place {
    val placeLoc = when (accuracy) {
        "unknown" -> PlaceLocation.Unknown

        else -> {
            val knownAcc = ContentDbJsonCodec.parseKnownAccuracy(accuracy)
            if (offMap) {
                val point = if (lat != null && lon != null) GeoPoint(lat, lon) else null
                PlaceLocation.OffMap(knownAcc, point)
            } else {
                PlaceLocation.OnMap(
                    knownAcc,
                    GeoPoint(
                        lat = requireNotNull(lat) { "Place $id on-map missing lat." },
                        lon = requireNotNull(lon) { "Place $id on-map missing lon." },
                    ),
                )
            }
        }
    }
    return Place(
        id = id,
        kind = ContentDbJsonCodec.parsePlaceKind(kind),
        location = placeLoc,
        visibility = parseVisibility(visibility),
        prominence = prominence,
        states = ContentDbJsonCodec.decodePlaceStates(statesJson),
    )
}

internal fun Area.toEntity(): AreaEntity = AreaEntity(
    id = id,
    labelLat = labelPoint?.lat,
    labelLon = labelPoint?.lon,
    polygonJson = polygonRing.takeIf { it.isNotEmpty() }?.let(ContentDbJsonCodec::encodePoints),
    offMap = offMap,
    statesJson = ContentDbJsonCodec.encodeAreaStates(states),
)

internal fun AreaEntity.toModel(): Area = Area(
    id = id,
    labelPoint = if (labelLat != null && labelLon != null) GeoPoint(labelLat, labelLon) else null,
    polygonRing = polygonJson?.let(ContentDbJsonCodec::decodePoints).orEmpty(),
    offMap = offMap,
    states = ContentDbJsonCodec.decodeAreaStates(statesJson),
)

internal fun Route.toEntity(): RouteEntity {
    val documented = geometry as? RouteGeometry.Documented
    return RouteEntity(
        id = id,
        eventId = eventId,
        documented = documented != null,
        stationsJson = ContentDbJsonCodec.encodeStrings(stationPlaceIds),
        coordinatesJson = documented?.let { ContentDbJsonCodec.encodePoints(it.coordinates) },
        stationWaypointsJson = documented?.let { ContentDbJsonCodec.encodeInts(it.stationWaypointIndices) },
        accuracyNote = documented?.accuracyNote,
        distanceKm = distanceKm,
        distanceBasis = distanceBasis?.wireName,
        days = days,
    )
}

internal fun RouteEntity.toModel(): Route {
    val geom = if (documented) {
        RouteGeometry.Documented(
            coordinates = ContentDbJsonCodec.decodePoints(
                requireNotNull(coordinatesJson) { "Documented route $id missing coordinates." },
            ),
            stationWaypointIndices = ContentDbJsonCodec.decodeInts(
                requireNotNull(stationWaypointsJson) { "Documented route $id missing waypoints." },
            ),
            accuracyNote = requireNotNull(accuracyNote) { "Documented route $id missing accuracy_note." },
        )
    } else {
        RouteGeometry.Undocumented
    }
    val basis = distanceBasis?.let { wire ->
        DistanceBasis.entries.firstOrNull { it.wireName == wire }
            ?: throw IllegalArgumentException("Unknown DistanceBasis '$wire' in route $id.")
    }
    return Route(
        id = id,
        eventId = eventId,
        stationPlaceIds = ContentDbJsonCodec.decodeStrings(stationsJson),
        geometry = geom,
        distanceKm = distanceKm,
        distanceBasis = basis,
        days = days,
    )
}

internal fun SeerahEvent.toEntity(): EventEntity {
    val point = date.timelinePointOrNull
    val axisWire = point?.axis?.wireName ?: (date as? HistoricalDate.Unknown)?.calendar?.wireName
    return EventEntity(
        id = id,
        phaseId = phaseId,
        parentId = parentId,
        sortOrder = order,
        kind = kind.wireName,
        depth = depth.wireName,
        expeditionRole = expeditionRole?.wireName,
        title = title,
        datePrecision = date.precision.wireName,
        axis = axisWire,
        continuousYear = point?.continuousYear,
        year = point?.year,
        month = point?.month,
        day = point?.day,
        prophethoodYear = date.prophethoodYear,
        approximate = date.approximate,
        dateNote = date.note,
        whereJson = ContentDbJsonCodec.encodeWhere(where),
        visibility = visibility.wireName,
        prominence = prominence,
        leadIn = leadIn,
        outcome = outcome,
        situationsJson = ContentDbJsonCodec.encodeSituations(situations),
        eventTextIdsJson = ContentDbJsonCodec.encodeStrings(eventTextIds),
        relatedJson = ContentDbJsonCodec.encodeRelations(related),
        tagsJson = ContentDbJsonCodec.encodeStrings(tags),
    )
}

internal fun EventEntity.toModel(): SeerahEvent {
    val precision = ContentDbJsonCodec.parseDatePrecision(datePrecision)
    val parsedDate = when (precision) {
        DatePrecision.UNKNOWN -> HistoricalDate.Unknown(
            prophethoodYear = prophethoodYear,
            approximate = approximate,
            note = dateNote,
        )

        DatePrecision.YEAR -> HistoricalDate.Year(
            point = TimelinePoint(
                axis = ContentDbJsonCodec.parseCalendarAxis(requireNotNull(axis) { "Event $id missing axis." }),
                year = requireNotNull(year) { "Event $id missing year." },
            ),
            prophethoodYear = prophethoodYear,
            approximate = approximate,
            note = dateNote,
        )

        DatePrecision.MONTH -> HistoricalDate.Month(
            point = TimelinePoint(
                axis = ContentDbJsonCodec.parseCalendarAxis(requireNotNull(axis) { "Event $id missing axis." }),
                year = requireNotNull(year) { "Event $id missing year." },
                month = requireNotNull(month) { "Event $id missing month." },
            ),
            prophethoodYear = prophethoodYear,
            approximate = approximate,
            note = dateNote,
        )

        DatePrecision.DAY -> HistoricalDate.Day(
            point = TimelinePoint(
                axis = ContentDbJsonCodec.parseCalendarAxis(requireNotNull(axis) { "Event $id missing axis." }),
                year = requireNotNull(year) { "Event $id missing year." },
                month = requireNotNull(month) { "Event $id missing month." },
                day = requireNotNull(day) { "Event $id missing day." },
            ),
            prophethoodYear = prophethoodYear,
            approximate = approximate,
            note = dateNote,
        )
    }
    return SeerahEvent(
        id = id,
        phaseId = phaseId,
        parentId = parentId,
        order = sortOrder,
        kind = EventKind.entries.firstOrNull { it.wireName == kind }
            ?: throw IllegalArgumentException("Unknown EventKind '$kind' in event $id."),
        depth = EventDepth.entries.firstOrNull { it.wireName == depth }
            ?: throw IllegalArgumentException("Unknown EventDepth '$depth' in event $id."),
        expeditionRole = expeditionRole?.let { role ->
            ExpeditionRole.entries.firstOrNull { it.wireName == role }
                ?: throw IllegalArgumentException("Unknown ExpeditionRole '$role' in event $id.")
        },
        title = title,
        date = parsedDate,
        where = ContentDbJsonCodec.decodeWhere(whereJson),
        visibility = parseVisibility(visibility),
        prominence = prominence,
        leadIn = leadIn,
        outcome = outcome,
        situations = ContentDbJsonCodec.decodeSituations(situationsJson),
        eventTextIds = ContentDbJsonCodec.decodeStrings(eventTextIdsJson),
        related = ContentDbJsonCodec.decodeRelations(relatedJson),
        tags = ContentDbJsonCodec.decodeStrings(tagsJson),
    )
}

internal fun ContentText.toEntity(): TextEntity = TextEntity(
    id = id,
    eventId = eventId,
    kind = kind.wireName,
    body = body,
    sourceWorkId = source?.sourceWorkId,
    locator = source?.locator,
    sourceUrl = source?.url,
    excerptOfTextId = excerptOfTextId,
    explanation = explanation,
    explanationSourceWorkId = explanationSource?.sourceWorkId,
    explanationLocator = explanationSource?.locator,
    explanationUrl = explanationSource?.url,
)

internal fun TextEntity.toModel(): ContentText = ContentText(
    id = id,
    eventId = eventId,
    kind = TextKind.entries.firstOrNull { it.wireName == kind }
        ?: throw IllegalArgumentException("Unknown TextKind '$kind' in text $id."),
    body = body,
    source = if (sourceWorkId != null && locator != null) {
        SourceCitation(sourceWorkId = sourceWorkId, locator = locator, url = sourceUrl)
    } else {
        null
    },
    excerptOfTextId = excerptOfTextId,
    explanation = explanation,
    explanationSource = if (explanationSourceWorkId != null && explanationLocator != null) {
        SourceCitation(
            sourceWorkId = explanationSourceWorkId,
            locator = explanationLocator,
            url = explanationUrl,
        )
    } else {
        null
    },
)

internal fun Story.toEntity(sortOrder: Int): StoryEntity = StoryEntity(
    id = id,
    sortOrder = sortOrder,
    title = title,
    chapterCount = chapters.size,
    chaptersJson = ContentDbJsonCodec.encodeChapters(chapters),
)

internal fun StoryEntity.toModel(): Story = Story(
    id = id,
    title = title,
    chapters = ContentDbJsonCodec.decodeChapters(chaptersJson),
)

internal fun StorySummaryRow.toModel(): StorySummary = StorySummary(
    id = id,
    sortOrder = sortOrder,
    title = title,
    chapterCount = chapterCount,
)

private fun parseVisibility(wireName: String): Visibility = Visibility.entries.firstOrNull { it.wireName == wireName }
    ?: throw IllegalArgumentException("Unknown Visibility '$wireName' in database.")
