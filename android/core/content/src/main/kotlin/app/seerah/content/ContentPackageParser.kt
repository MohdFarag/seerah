package app.seerah.content

import app.seerah.content.db.AreaStateDto
import app.seerah.content.db.ContentDbJsonCodec
import app.seerah.content.db.EventRelationDto
import app.seerah.content.db.EventWhereDto
import app.seerah.content.db.GeoPointDto
import app.seerah.content.db.PlaceStateDto
import app.seerah.content.db.SituationDto
import app.seerah.content.db.StoryChapterDto
import app.seerah.model.Area
import app.seerah.model.CalendarAxis
import app.seerah.model.ContentPackageSummary
import app.seerah.model.ContentText
import app.seerah.model.DistanceBasis
import app.seerah.model.EventDepth
import app.seerah.model.EventKind
import app.seerah.model.EventLocation
import app.seerah.model.ExpeditionRole
import app.seerah.model.GeoPoint
import app.seerah.model.HistoricalDate
import app.seerah.model.KnownAccuracy
import app.seerah.model.MapTargetRef
import app.seerah.model.Phase
import app.seerah.model.Place
import app.seerah.model.PlaceLocation
import app.seerah.model.PlaceReference
import app.seerah.model.Route
import app.seerah.model.RouteGeometry
import app.seerah.model.SeerahEvent
import app.seerah.model.SourceCitation
import app.seerah.model.SourceKind
import app.seerah.model.SourceWork
import app.seerah.model.StepCamera
import app.seerah.model.Story
import app.seerah.model.TextKind
import app.seerah.model.TimelinePoint
import app.seerah.model.Visibility
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

internal const val SUPPORTED_CONTENT_SCHEMA = 1

internal val REQUIRED_CONTENT_DATA_PATHS: Set<String> = setOf(
    "data/areas.json",
    "data/events.json",
    "data/meta.json",
    "data/places.json",
    "data/routes.json",
    "data/sources.json",
    "data/stories.json",
    "data/texts.json",
)

private val SLUG_ID_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
private val HTTPS_URL_REGEX = Regex("^https://[^ /]+")

internal data class ParsedContentPackage(
    val summary: ContentPackageSummary,
    val phases: List<Phase>,
    val sources: List<SourceWork>,
    val places: List<Place>,
    val areas: List<Area>,
    val orderedEvents: List<SeerahEvent>,
    val routes: List<Route>,
    val orderedTexts: List<ContentText>,
    val stories: List<Story>,
)

/**
 * Strictly decodes and validates all eight compiled JSON payload entries from a signed `content`
 * package before constructing `:core:model` domain types or opening SQLite.
 */
internal object ContentPackageParser {
    private val wireJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

    @Serializable
    private data class TimelineBoundDto(val calendar: String, val year: Int)

    @Serializable
    private data class PhaseDto(val id: String, val order: Int, val title: String, val skippable: Boolean = false)

    @Serializable
    private data class MetaFileDto(
        @SerialName("content_schema")
        val contentSchema: JsonPrimitive,
        @SerialName("content_version")
        val contentVersion: String,
        val phases: List<PhaseDto>,
        @SerialName("min_timeline_point")
        val minTimelinePoint: TimelineBoundDto? = null,
        @SerialName("max_timeline_point")
        val maxTimelinePoint: TimelineBoundDto? = null,
    )

    @Serializable
    private data class SourceDto(
        val id: String,
        val kind: String,
        val work: String,
        val edition: String? = null,
        val url: String? = null,
    )

    @Serializable
    private data class SourcesFileDto(val sources: List<SourceDto>)

    @Serializable
    private data class PlaceDto(
        val id: String,
        val kind: String,
        val accuracy: String,
        val coordinates: GeoPointDto? = null,
        @SerialName("off_map")
        val offMap: Boolean = false,
        val visibility: String,
        val prominence: Int,
        val states: List<PlaceStateDto>,
    )

    @Serializable
    private data class PlacesFileDto(val places: List<PlaceDto>)

    @Serializable
    private data class AreaDto(
        val id: String,
        @SerialName("label_point")
        val labelPoint: GeoPointDto? = null,
        @SerialName("polygon_ring")
        val polygonRing: List<GeoPointDto>? = null,
        @SerialName("off_map")
        val offMap: Boolean = false,
        val states: List<AreaStateDto>,
    )

    @Serializable
    private data class AreasFileDto(val areas: List<AreaDto>)

    @Serializable
    private data class RouteDto(
        val id: String,
        @SerialName("event_id")
        val eventId: String,
        val stations: List<String>,
        val documented: Boolean,
        val coordinates: List<GeoPointDto>? = null,
        @SerialName("station_waypoint_indices")
        val stationWaypointIndices: List<Int>? = null,
        @SerialName("accuracy_note")
        val accuracyNote: String? = null,
        @SerialName("distance_km")
        val distanceKm: Double? = null,
        @SerialName("distance_basis")
        val distanceBasis: String? = null,
        val days: Int? = null,
    )

    @Serializable
    private data class RoutesFileDto(val routes: List<RouteDto>)

    @Serializable
    private data class EventDateDto(
        val precision: String,
        val calendar: String? = null,
        val year: Int? = null,
        val month: Int? = null,
        val day: Int? = null,
        @SerialName("prophethood_year")
        val prophethoodYear: Int? = null,
        val approximate: Boolean = false,
        val note: String? = null,
    )

    @Serializable
    private data class EventDto(
        val id: String,
        @SerialName("phase_id")
        val phaseId: String,
        @SerialName("parent_id")
        val parentId: String? = null,
        val order: Int,
        val kind: String,
        val depth: String,
        @SerialName("expedition_role")
        val expeditionRole: String? = null,
        val title: String,
        val date: EventDateDto,
        val where: EventWhereDto,
        val visibility: String,
        val prominence: Int,
        @SerialName("lead_in")
        val leadIn: String? = null,
        val outcome: String? = null,
        val situations: List<SituationDto>? = null,
        @SerialName("event_text_ids")
        val eventTextIds: List<String>? = null,
        val related: List<EventRelationDto>? = null,
        val tags: List<String>? = null,
    )

    @Serializable
    private data class EventsFileDto(val events: List<EventDto>)

    @Serializable
    private data class CitationDto(
        @SerialName("source_work_id")
        val sourceWorkId: String,
        val locator: String,
        val url: String? = null,
    )

    @Serializable
    private data class TextDto(
        val id: String,
        @SerialName("event_id")
        val eventId: String,
        val kind: String,
        val body: String,
        val source: CitationDto? = null,
        @SerialName("excerpt_of")
        val excerptOf: String? = null,
        val explanation: String? = null,
        @SerialName("explanation_source")
        val explanationSource: CitationDto? = null,
    )

    @Serializable
    private data class TextsFileDto(val texts: List<TextDto>)

    @Serializable
    private data class StoryDto(val id: String, val title: String, val chapters: List<StoryChapterDto>)

    @Serializable
    private data class StoriesFileDto(val stories: List<StoryDto>)

    fun parseAndValidate(
        entriesByPath: Map<String, ByteArray>,
        packageVersion: Int,
        generation: Long,
    ): PackageResult<ParsedContentPackage> {
        val missing = REQUIRED_CONTENT_DATA_PATHS.filter { it !in entriesByPath }
        if (missing.isNotEmpty()) {
            return invalidPayload("Content package missing required payload file(s): ${missing.sorted()}.")
        }

        return try {
            val metaRoot = decodeRootObject(entriesByPath.getValue("data/meta.json"), "data/meta.json")
            require("min_timeline_point" in metaRoot && "max_timeline_point" in metaRoot) {
                "data/meta.json must include min_timeline_point and max_timeline_point."
            }
            rejectForbiddenNulls("data/meta.json", metaRoot) { path ->
                path.size == 1 && (path[0] == "min_timeline_point" || path[0] == "max_timeline_point")
            }
            val metaDto = wireJson.decodeFromJsonElement<MetaFileDto>(metaRoot)
            require(MapMetadataParser.isExactJsonInteger(metaDto.contentSchema, SUPPORTED_CONTENT_SCHEMA)) {
                "Unsupported content_schema '${metaDto.contentSchema}'; expected integer $SUPPORTED_CONTENT_SCHEMA."
            }

            val sourcesRoot = decodeRootObject(entriesByPath.getValue("data/sources.json"), "data/sources.json")
            rejectForbiddenNulls("data/sources.json", sourcesRoot) { false }
            val sourcesDto = wireJson.decodeFromJsonElement<SourcesFileDto>(sourcesRoot)

            val placesRoot = decodeRootObject(entriesByPath.getValue("data/places.json"), "data/places.json")
            rejectForbiddenNulls("data/places.json", placesRoot) { false }
            val placesDto = wireJson.decodeFromJsonElement<PlacesFileDto>(placesRoot)

            val areasRoot = decodeRootObject(entriesByPath.getValue("data/areas.json"), "data/areas.json")
            rejectForbiddenNulls("data/areas.json", areasRoot) { false }
            val areasDto = wireJson.decodeFromJsonElement<AreasFileDto>(areasRoot)

            val routesRoot = decodeRootObject(entriesByPath.getValue("data/routes.json"), "data/routes.json")
            rejectForbiddenNulls("data/routes.json", routesRoot) { false }
            val routesDto = wireJson.decodeFromJsonElement<RoutesFileDto>(routesRoot)

            val eventsRoot = decodeRootObject(entriesByPath.getValue("data/events.json"), "data/events.json")
            rejectForbiddenNulls("data/events.json", eventsRoot) { false }
            val eventsDto = wireJson.decodeFromJsonElement<EventsFileDto>(eventsRoot)

            val textsRoot = decodeRootObject(entriesByPath.getValue("data/texts.json"), "data/texts.json")
            rejectForbiddenNulls("data/texts.json", textsRoot) { path ->
                path.size == 3 && path[0] == "texts" && path[2] == "source"
            }
            val textsDto = wireJson.decodeFromJsonElement<TextsFileDto>(textsRoot)

            val storiesRoot = decodeRootObject(entriesByPath.getValue("data/stories.json"), "data/stories.json")
            rejectForbiddenNulls("data/stories.json", storiesRoot) { path ->
                path.size == 7 &&
                    path[0] == "stories" &&
                    path[2] == "chapters" &&
                    path[4] == "scenes" &&
                    path[6] == "event_id"
            }
            val storiesDto = wireJson.decodeFromJsonElement<StoriesFileDto>(storiesRoot)

            val summary = ContentPackageSummary(
                contentSchema = SUPPORTED_CONTENT_SCHEMA,
                contentVersion = metaDto.contentVersion,
                packageVersion = packageVersion,
                generation = generation,
                minTimelinePoint = metaDto.minTimelinePoint?.let {
                    TimelinePoint(axis = ContentDbJsonCodec.parseCalendarAxis(it.calendar), year = it.year)
                },
                maxTimelinePoint = metaDto.maxTimelinePoint?.let {
                    TimelinePoint(axis = ContentDbJsonCodec.parseCalendarAxis(it.calendar), year = it.year)
                },
            )

            val phases = parsePhases(metaDto.phases)
            val sources = parseSources(sourcesDto.sources)
            val places = parsePlaces(placesDto.places)
            val areas = parseAreas(areasDto.areas)
            val routes = parseRoutes(routesDto.routes)
            val events = parseEvents(eventsDto.events)
            val texts = parseTexts(textsDto.texts)
            val stories = parseStories(storiesDto.stories)

            PackageResult.Success(
                crossValidateAndSort(
                    summary = summary,
                    phases = phases,
                    sources = sources,
                    places = places,
                    areas = areas,
                    routes = routes,
                    events = events,
                    texts = texts,
                    stories = stories,
                ),
            )
        } catch (exc: SerializationException) {
            invalidPayload("Invalid content JSON schema: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            invalidPayload("Invalid content payload invariant: ${exc.message}")
        }
    }

    private fun decodeRootObject(rawBytes: ByteArray, entryPath: String): JsonObject {
        val text = decodeStrictUtf8(rawBytes)
            ?: throw IllegalArgumentException("$entryPath is not valid UTF-8.")
        val element = wireJson.parseToJsonElement(text)
        return element as? JsonObject
            ?: throw IllegalArgumentException("$entryPath root must be a JSON object.")
    }

    private fun rejectForbiddenNulls(
        entryPath: String,
        element: JsonElement,
        isAllowedNull: (List<String>) -> Boolean,
    ) {
        fun walk(node: JsonElement, path: MutableList<String>) {
            when (node) {
                is JsonNull -> {
                    if (!isAllowedNull(path)) {
                        throw IllegalArgumentException(
                            "$entryPath contains forbidden null at '${path.joinToString(".")}'.",
                        )
                    }
                }

                is JsonObject -> {
                    for ((key, value) in node) {
                        path.add(key)
                        walk(value, path)
                        path.removeAt(path.lastIndex)
                    }
                }

                is JsonArray -> {
                    for (idx in node.indices) {
                        path.add("[$idx]")
                        walk(node[idx], path)
                        path.removeAt(path.lastIndex)
                    }
                }

                is JsonPrimitive -> Unit
            }
        }
        walk(element, ArrayList())
    }

    private fun parsePhases(dtos: List<PhaseDto>): List<Phase> {
        require(dtos.isNotEmpty()) { "meta.json phases must not be empty." }
        val seenIds = HashSet<String>(dtos.size)
        val seenOrders = HashSet<Int>(dtos.size)
        return dtos.map { dto ->
            requireSlugId(dto.id, "phase")
            require(seenIds.add(dto.id)) { "Duplicate phase id '${dto.id}'." }
            require(seenOrders.add(dto.order)) { "Duplicate phase order ${dto.order}." }
            Phase(id = dto.id, order = dto.order, title = dto.title, skippable = dto.skippable)
        }
    }

    private fun parseSources(dtos: List<SourceDto>): List<SourceWork> {
        val seenIds = HashSet<String>(dtos.size)
        return dtos.map { dto ->
            requireSlugId(dto.id, "source")
            require(seenIds.add(dto.id)) { "Duplicate source id '${dto.id}'." }
            dto.url?.let { requireHttpsUrl(it, "source '${dto.id}' url") }
            SourceWork(
                id = dto.id,
                kind = SourceKind.entries.firstOrNull { it.wireName == dto.kind }
                    ?: throw IllegalArgumentException("Unknown source kind '${dto.kind}'."),
                work = dto.work,
                edition = dto.edition,
                url = dto.url,
            )
        }
    }

    private fun parsePlaces(dtos: List<PlaceDto>): List<Place> {
        val seenIds = HashSet<String>(dtos.size)
        return dtos.map { dto ->
            requireSlugId(dto.id, "place")
            require(seenIds.add(dto.id)) { "Duplicate place id '${dto.id}'." }
            val coords = dto.coordinates?.let { GeoPoint(it.lat, it.lon) }
            val location = when (dto.accuracy) {
                "unknown" -> {
                    require(coords == null) { "Place '${dto.id}' with accuracy 'unknown' must omit coordinates." }
                    PlaceLocation.Unknown
                }

                else -> {
                    val knownAcc = ContentDbJsonCodec.parseKnownAccuracy(dto.accuracy)
                    if (dto.offMap) {
                        PlaceLocation.OffMap(accuracy = knownAcc, point = coords)
                    } else {
                        PlaceLocation.OnMap(
                            accuracy = knownAcc,
                            point = requireNotNull(coords) {
                                "On-map place '${dto.id}' with accuracy '${dto.accuracy}' requires coordinates."
                            },
                        )
                    }
                }
            }
            Place(
                id = dto.id,
                kind = ContentDbJsonCodec.parsePlaceKind(dto.kind),
                location = location,
                visibility = parseVisibility(dto.visibility),
                prominence = dto.prominence,
                states = dto.states.map { with(ContentDbJsonCodec) { it.toModel() } },
            )
        }
    }

    private fun parseAreas(dtos: List<AreaDto>): List<Area> {
        val seenIds = HashSet<String>(dtos.size)
        return dtos.map { dto ->
            requireSlugId(dto.id, "area")
            require(seenIds.add(dto.id)) { "Duplicate area id '${dto.id}'." }
            Area(
                id = dto.id,
                labelPoint = dto.labelPoint?.let { GeoPoint(it.lat, it.lon) },
                polygonRing = dto.polygonRing.orEmpty().map { GeoPoint(it.lat, it.lon) },
                offMap = dto.offMap,
                states = dto.states.map { with(ContentDbJsonCodec) { it.toModel() } },
            )
        }
    }

    private fun parseRoutes(dtos: List<RouteDto>): List<Route> {
        val seenIds = HashSet<String>(dtos.size)
        val seenEvents = HashSet<String>(dtos.size)
        return dtos.map { dto ->
            requireSlugId(dto.id, "route")
            requireSlugId(dto.eventId, "route.event_id")
            require(seenIds.add(dto.id)) { "Duplicate route id '${dto.id}'." }
            require(seenEvents.add(dto.eventId)) { "Multiple routes claim event '${dto.eventId}'." }
            dto.stations.forEach { requireSlugId(it, "route.station") }
            val geometry = if (dto.documented) {
                val rawCoords = requireNotNull(dto.coordinates) {
                    "Documented route '${dto.id}' requires coordinates."
                }
                val rawWaypoints = requireNotNull(dto.stationWaypointIndices) {
                    "Documented route '${dto.id}' requires station_waypoint_indices."
                }
                val note = requireNotNull(dto.accuracyNote) {
                    "Documented route '${dto.id}' requires accuracy_note."
                }
                RouteGeometry.Documented(
                    coordinates = rawCoords.map { GeoPoint(it.lat, it.lon) },
                    stationWaypointIndices = rawWaypoints,
                    accuracyNote = note,
                )
            } else {
                require(dto.coordinates == null && dto.stationWaypointIndices == null && dto.accuracyNote == null) {
                    "Undocumented route '${dto.id}' must omit coordinates, station_waypoint_indices, and accuracy_note."
                }
                RouteGeometry.Undocumented
            }
            val basis = dto.distanceBasis?.let { wire ->
                DistanceBasis.entries.firstOrNull { it.wireName == wire }
                    ?: throw IllegalArgumentException("Unknown distance_basis '$wire' on route '${dto.id}'.")
            }
            Route(
                id = dto.id,
                eventId = dto.eventId,
                stationPlaceIds = dto.stations,
                geometry = geometry,
                distanceKm = dto.distanceKm,
                distanceBasis = basis,
                days = dto.days,
            )
        }
    }

    private fun parseEvents(dtos: List<EventDto>): List<SeerahEvent> {
        val seenIds = HashSet<String>(dtos.size)
        return dtos.map { dto ->
            requireSlugId(dto.id, "event")
            require(seenIds.add(dto.id)) { "Duplicate event id '${dto.id}'." }
            requireSlugId(dto.phaseId, "event.phase_id")
            dto.parentId?.let { requireSlugId(it, "event.parent_id") }

            val situations = dto.situations.orEmpty().map { with(ContentDbJsonCodec) { it.toModel() } }
            val sitIds = HashSet<String>(situations.size)
            val sitOrders = HashSet<Int>(situations.size)
            for (sit in situations) {
                requireSlugId(sit.id, "situation")
                require(sitIds.add(sit.id)) { "Duplicate situation id '${sit.id}' inside event '${dto.id}'." }
                require(sitOrders.add(sit.order)) { "Duplicate situation order ${sit.order} inside event '${dto.id}'." }
                val sitPlace = sit.place
                if (sitPlace is PlaceReference.Known) {
                    requireSlugId(sitPlace.placeId, "situation.place.place_id")
                }
                sit.textIds.forEach { requireSlugId(it, "situation.text_ids") }
            }

            val eventTextIds = dto.eventTextIds.orEmpty().also { ids ->
                ids.forEach { requireSlugId(it, "event_text_ids") }
            }
            val related = dto.related.orEmpty().map { with(ContentDbJsonCodec) { it.toModel() } }.also { rels ->
                rels.forEach { requireSlugId(it.targetEventId, "related.target_event_id") }
            }

            val where = with(ContentDbJsonCodec) { dto.where.toModel() }
            when (where) {
                is EventLocation.SinglePlace -> requireSlugId(where.placeId, "where.place_id")
                is EventLocation.MultiPlace -> where.placeIds.forEach { requireSlugId(it, "where.place_ids") }
                is EventLocation.AreaTarget -> requireSlugId(where.areaId, "where.area_id")
                is EventLocation.RouteTarget, EventLocation.Unknown -> Unit
            }

            SeerahEvent(
                id = dto.id,
                phaseId = dto.phaseId,
                parentId = dto.parentId,
                order = dto.order,
                kind = EventKind.entries.firstOrNull { it.wireName == dto.kind }
                    ?: throw IllegalArgumentException("Unknown event kind '${dto.kind}'."),
                depth = EventDepth.entries.firstOrNull { it.wireName == dto.depth }
                    ?: throw IllegalArgumentException("Unknown event depth '${dto.depth}'."),
                expeditionRole = dto.expeditionRole?.let { role ->
                    ExpeditionRole.entries.firstOrNull { it.wireName == role }
                        ?: throw IllegalArgumentException("Unknown expedition_role '$role'.")
                },
                title = dto.title,
                date = parseEventDate(dto.id, dto.date),
                where = where,
                visibility = parseVisibility(dto.visibility),
                prominence = dto.prominence,
                leadIn = dto.leadIn,
                outcome = dto.outcome,
                situations = situations,
                eventTextIds = eventTextIds,
                related = related,
                tags = dto.tags.orEmpty(),
            )
        }
    }

    private fun parseEventDate(eventId: String, dto: EventDateDto): HistoricalDate = when (dto.precision) {
        "unknown" -> {
            require(dto.year == null && dto.month == null && dto.day == null) {
                "Unknown date on event '$eventId' must omit year, month, and day."
            }
            if (dto.prophethoodYear != null) {
                require(dto.calendar == CalendarAxis.PRE_HIJRA.wireName) {
                    "Event '$eventId' prophethood_year requires calendar 'pre-hijra'."
                }
            } else {
                require(dto.calendar == null) {
                    "Event '$eventId' unknown date without prophethood_year must omit calendar."
                }
            }
            HistoricalDate.Unknown(
                prophethoodYear = dto.prophethoodYear,
                approximate = dto.approximate,
                note = dto.note,
            )
        }

        "year" -> {
            require(dto.month == null && dto.day == null) {
                "Year-precision date on event '$eventId' must omit month and day."
            }
            HistoricalDate.Year(
                point = TimelinePoint(
                    axis = ContentDbJsonCodec.parseCalendarAxis(
                        requireNotNull(dto.calendar) { "Event '$eventId' date missing calendar." },
                    ),
                    year = requireNotNull(dto.year) { "Event '$eventId' date missing year." },
                ),
                prophethoodYear = dto.prophethoodYear,
                approximate = dto.approximate,
                note = dto.note,
            )
        }

        "month" -> {
            require(dto.day == null) { "Month-precision date on event '$eventId' must omit day." }
            HistoricalDate.Month(
                point = TimelinePoint(
                    axis = ContentDbJsonCodec.parseCalendarAxis(
                        requireNotNull(dto.calendar) { "Event '$eventId' date missing calendar." },
                    ),
                    year = requireNotNull(dto.year) { "Event '$eventId' date missing year." },
                    month = requireNotNull(dto.month) { "Event '$eventId' month date missing month." },
                ),
                prophethoodYear = dto.prophethoodYear,
                approximate = dto.approximate,
                note = dto.note,
            )
        }

        "day" -> HistoricalDate.Day(
            point = TimelinePoint(
                axis = ContentDbJsonCodec.parseCalendarAxis(
                    requireNotNull(dto.calendar) { "Event '$eventId' date missing calendar." },
                ),
                year = requireNotNull(dto.year) { "Event '$eventId' date missing year." },
                month = requireNotNull(dto.month) { "Event '$eventId' day date missing month." },
                day = requireNotNull(dto.day) { "Event '$eventId' day date missing day." },
            ),
            prophethoodYear = dto.prophethoodYear,
            approximate = dto.approximate,
            note = dto.note,
        )

        else -> throw IllegalArgumentException("Unknown date precision '${dto.precision}' on event '$eventId'.")
    }

    private fun parseTexts(dtos: List<TextDto>): List<ContentText> {
        val seenIds = HashSet<String>(dtos.size)
        return dtos.map { dto ->
            requireSlugId(dto.id, "text")
            requireSlugId(dto.eventId, "text.event_id")
            require(seenIds.add(dto.id)) { "Duplicate text id '${dto.id}'." }
            dto.excerptOf?.let { requireSlugId(it, "text.excerpt_of") }
            val sourceCitation = dto.source?.let { src ->
                requireSlugId(src.sourceWorkId, "text.source.source_work_id")
                src.url?.let { requireHttpsUrl(it, "text '${dto.id}' source url") }
                SourceCitation(sourceWorkId = src.sourceWorkId, locator = src.locator, url = src.url)
            }
            val expCitation = dto.explanationSource?.let { src ->
                requireSlugId(src.sourceWorkId, "text.explanation_source.source_work_id")
                src.url?.let { requireHttpsUrl(it, "text '${dto.id}' explanation_source url") }
                SourceCitation(sourceWorkId = src.sourceWorkId, locator = src.locator, url = src.url)
            }
            ContentText(
                id = dto.id,
                eventId = dto.eventId,
                kind = TextKind.entries.firstOrNull { it.wireName == dto.kind }
                    ?: throw IllegalArgumentException("Unknown text kind '${dto.kind}'."),
                body = dto.body,
                source = sourceCitation,
                excerptOfTextId = dto.excerptOf,
                explanation = dto.explanation,
                explanationSource = expCitation,
            )
        }
    }

    private fun parseStories(dtos: List<StoryDto>): List<Story> {
        val seenIds = HashSet<String>(dtos.size)
        return dtos.map { dto ->
            requireSlugId(dto.id, "story")
            require(seenIds.add(dto.id)) { "Duplicate story id '${dto.id}'." }
            val chapters = dto.chapters.map { with(ContentDbJsonCodec) { it.toModel() } }
            val chapterIds = HashSet<String>(chapters.size)
            val chapterOrders = HashSet<Int>(chapters.size)
            for (chapter in chapters) {
                requireSlugId(chapter.id, "chapter")
                chapter.phaseId?.let { requireSlugId(it, "chapter.phase_id") }
                require(chapterIds.add(chapter.id)) { "Duplicate chapter id '${chapter.id}' in '${dto.id}'." }
                require(chapterOrders.add(chapter.order)) { "Duplicate chapter order ${chapter.order} in '${dto.id}'." }
                val sceneIds = HashSet<String>(chapter.scenes.size)
                val sceneOrders = HashSet<Int>(chapter.scenes.size)
                for (scene in chapter.scenes) {
                    requireSlugId(scene.id, "scene")
                    scene.eventId?.let { requireSlugId(it, "scene.event_id") }
                    require(sceneIds.add(scene.id)) { "Duplicate scene id '${scene.id}' in '${chapter.id}'." }
                    require(sceneOrders.add(scene.order)) { "Duplicate scene order ${scene.order} in '${chapter.id}'." }
                    val stepIds = HashSet<String>(scene.steps.size)
                    val stepOrders = HashSet<Int>(scene.steps.size)
                    for (step in scene.steps) {
                        requireSlugId(step.id, "step")
                        require(stepIds.add(step.id)) { "Duplicate step id '${step.id}' in '${scene.id}'." }
                        require(stepOrders.add(step.order)) { "Duplicate step order ${step.order} in '${scene.id}'." }
                    }
                }
            }
            Story(id = dto.id, title = dto.title, chapters = chapters)
        }
    }

    private fun crossValidateAndSort(
        summary: ContentPackageSummary,
        phases: List<Phase>,
        sources: List<SourceWork>,
        places: List<Place>,
        areas: List<Area>,
        routes: List<Route>,
        events: List<SeerahEvent>,
        texts: List<ContentText>,
        stories: List<Story>,
    ): ParsedContentPackage {
        val phasesById = phases.associateBy { it.id }
        val sourcesById = sources.associateBy { it.id }
        val placesById = places.associateBy { it.id }
        val areasById = areas.associateBy { it.id }
        val routesById = routes.associateBy { it.id }
        val eventsById = events.associateBy { it.id }
        val routesByEvent = routes.associateBy { it.eventId }
        val textsById = texts.associateBy { it.id }

        for (route in routes) {
            require(route.eventId in eventsById) {
                "Route '${route.id}' references missing event '${route.eventId}'."
            }
            for (stationId in route.stationPlaceIds) {
                require(stationId in placesById) {
                    "Route '${route.id}' references missing station place '$stationId'."
                }
            }
        }

        for (event in events) {
            require(event.phaseId in phasesById) {
                "Event '${event.id}' references missing phase '${event.phaseId}'."
            }
            event.parentId?.let { parentId ->
                require(parentId in eventsById) {
                    "Event '${event.id}' references missing parent event '$parentId'."
                }
            }
            validateEventLocationRefs(event, placesById, areasById, routesByEvent)
            for (tid in event.eventTextIds) {
                require(tid in textsById) { "Event '${event.id}' references missing text '$tid'." }
            }
            for (sit in event.situations) {
                val sitPlace = sit.place
                if (sitPlace is PlaceReference.Known) {
                    val refPlace = placesById[sitPlace.placeId]
                        ?: throw IllegalArgumentException(
                            "Situation '${sit.id}' references missing place '${sitPlace.placeId}'.",
                        )
                    if (sitPlace.accuracy == KnownAccuracy.EXACT) {
                        require(refPlace.location !is PlaceLocation.Unknown) {
                            "Situation '${sit.id}' claims EXACT place '${refPlace.id}' whose location is Unknown."
                        }
                    }
                }
                for (tid in sit.textIds) {
                    require(tid in textsById) { "Situation '${sit.id}' references missing text '$tid'." }
                }
            }
            for (rel in event.related) {
                require(rel.targetEventId in eventsById) {
                    "Event '${event.id}' relation references missing event '${rel.targetEventId}'."
                }
                require(rel.targetEventId != event.id) {
                    "Event '${event.id}' contains a self-referencing relation."
                }
            }
        }

        val orderedEvents = topologicalSortEventsByParent(events)
        assertNoRelatedCycles(events, eventsById)

        for (text in texts) {
            text.eventId?.let { eid ->
                require(eid in eventsById) { "Text '${text.id}' references missing event '$eid'." }
            }
            text.source?.let { cit ->
                require(cit.sourceWorkId in sourcesById) {
                    "Text '${text.id}' references missing source work '${cit.sourceWorkId}'."
                }
            }
            text.explanationSource?.let { cit ->
                require(cit.sourceWorkId in sourcesById) {
                    "Text '${text.id}' explanation references missing source work '${cit.sourceWorkId}'."
                }
            }
            text.excerptOfTextId?.let { fullId ->
                val target = textsById[fullId]
                    ?: throw IllegalArgumentException("Text '${text.id}' excerpt_of missing text '$fullId'.")
                require(target.kind != TextKind.EDITORIAL) {
                    "Text '${text.id}' cannot be an excerpt of editorial text '$fullId'."
                }
                require(target.excerptOfTextId == null) {
                    "Text '${text.id}' cannot be an excerpt of another excerpt '$fullId'."
                }
            }
        }
        val orderedTexts = texts.sortedWith(
            compareBy<ContentText>({ it.excerptOfTextId != null }, { it.id }),
        )

        for (story in stories) {
            validateStoryRefs(story, phasesById, eventsById, placesById, areasById, routesById, textsById)
        }

        return ParsedContentPackage(
            summary = summary,
            phases = phases,
            sources = sources,
            places = places,
            areas = areas,
            orderedEvents = orderedEvents,
            routes = routes,
            orderedTexts = orderedTexts,
            stories = stories,
        )
    }

    private fun validateEventLocationRefs(
        event: SeerahEvent,
        placesById: Map<String, Place>,
        areasById: Map<String, Area>,
        routesByEvent: Map<String, Route>,
    ) {
        fun requirePlace(pid: String, accuracy: KnownAccuracy) {
            val place = placesById[pid]
                ?: throw IllegalArgumentException("Event '${event.id}' references missing place '$pid'.")
            if (accuracy == KnownAccuracy.EXACT) {
                require(place.location !is PlaceLocation.Unknown) {
                    "Event '${event.id}' claims EXACT accuracy on place '$pid' whose location is Unknown."
                }
            }
        }
        when (val where = event.where) {
            is EventLocation.SinglePlace -> requirePlace(where.placeId, where.accuracy)

            is EventLocation.MultiPlace -> where.placeIds.forEach { requirePlace(it, where.accuracy) }

            is EventLocation.AreaTarget -> {
                require(where.areaId in areasById) {
                    "Event '${event.id}' references missing area '${where.areaId}'."
                }
            }

            is EventLocation.RouteTarget -> {
                require(event.id in routesByEvent) {
                    "Event '${event.id}' specifies 'route' location without an owning route."
                }
            }

            EventLocation.Unknown -> Unit
        }
    }

    private fun topologicalSortEventsByParent(events: List<SeerahEvent>): List<SeerahEvent> {
        val orderedInput = events.sortedWith(compareBy({ it.phaseId }, { it.order }, { it.id }))
        val byId = orderedInput.associateBy { it.id }
        val inDegree = HashMap<String, Int>(orderedInput.size)
        val children = HashMap<String, MutableList<SeerahEvent>>()

        for (event in orderedInput) {
            val pid = event.parentId
            if (pid != null) {
                inDegree[event.id] = 1
                children.getOrPut(pid) { ArrayList() }.add(event)
            } else {
                inDegree[event.id] = 0
            }
        }

        val queue = ArrayDeque<SeerahEvent>()
        for (event in orderedInput) {
            if (inDegree[event.id] == 0) queue.addLast(event)
        }

        val result = ArrayList<SeerahEvent>(orderedInput.size)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)
            for (child in children[current.id].orEmpty()) {
                val remaining = inDegree.getValue(child.id) - 1
                inDegree[child.id] = remaining
                if (remaining == 0) queue.addLast(child)
            }
        }
        require(result.size == byId.size) { "Cycle detected in events.parent_id hierarchy." }
        return result
    }

    private fun assertNoRelatedCycles(events: List<SeerahEvent>, eventsById: Map<String, SeerahEvent>) {
        // 0 = unvisited, 1 = visiting, 2 = visited
        val state = HashMap<String, Int>(events.size)

        fun dfs(id: String) {
            state[id] = 1
            val event = eventsById.getValue(id)
            for (rel in event.related) {
                when (state[rel.targetEventId] ?: 0) {
                    1 -> throw IllegalArgumentException(
                        "Cycle detected in events.related involving '$id' -> '${rel.targetEventId}'.",
                    )

                    0 -> dfs(rel.targetEventId)
                }
            }
            state[id] = 2
        }

        for (event in events) {
            if ((state[event.id] ?: 0) == 0) dfs(event.id)
        }
    }

    private fun validateStoryRefs(
        story: Story,
        phasesById: Map<String, Phase>,
        eventsById: Map<String, SeerahEvent>,
        placesById: Map<String, Place>,
        areasById: Map<String, Area>,
        routesById: Map<String, Route>,
        textsById: Map<String, ContentText>,
    ) {
        fun requireTarget(ref: MapTargetRef, context: String) {
            when (ref) {
                is MapTargetRef.PlaceRef -> require(ref.id in placesById) { "$context missing place '${ref.id}'." }
                is MapTargetRef.AreaRef -> require(ref.id in areasById) { "$context missing area '${ref.id}'." }
                is MapTargetRef.EventRef -> require(ref.id in eventsById) { "$context missing event '${ref.id}'." }
                is MapTargetRef.RouteRef -> require(ref.id in routesById) { "$context missing route '${ref.id}'." }
            }
        }

        fun requireDocumentedRouteRange(routeId: String, fromIdx: Int, toIdx: Int, context: String) {
            val route = routesById[routeId]
                ?: throw IllegalArgumentException("$context references missing route '$routeId'.")
            require(route.geometry is RouteGeometry.Documented) {
                "$context references undocumented route '$routeId'."
            }
            require(toIdx < route.stationPlaceIds.size) {
                "$context station index $toIdx out of bounds for route '$routeId' (${route.stationPlaceIds.size} stations)."
            }
            require(fromIdx in 0..toIdx) {
                "$context invalid station index range $fromIdx..$toIdx."
            }
        }

        for (chapter in story.chapters) {
            chapter.phaseId?.let { pid ->
                require(pid in phasesById) { "Story '${story.id}' chapter '${chapter.id}' missing phase '$pid'." }
            }
            for (scene in chapter.scenes) {
                scene.eventId?.let { eid ->
                    require(eid in eventsById) { "Story '${story.id}' scene '${scene.id}' missing event '$eid'." }
                }
                for (step in scene.steps) {
                    val stepCtx = "Story '${story.id}' step '${step.id}'"
                    when (val cam = step.camera) {
                        is StepCamera.Frame -> cam.targets.forEach { requireTarget(it, stepCtx) }

                        is StepCamera.Follow -> requireDocumentedRouteRange(
                            cam.routeId,
                            cam.fromStationIndex,
                            cam.toStationIndex,
                            stepCtx,
                        )
                    }
                    step.highlight.forEach { requireTarget(it, stepCtx) }
                    step.reveal.forEach { requireTarget(it, stepCtx) }
                    step.drawRoute?.let { dr ->
                        requireDocumentedRouteRange(dr.routeId, dr.fromStationIndex, dr.toStationIndex, stepCtx)
                    }
                    step.showMovement?.let { mv ->
                        require(mv.fromPlaceId in placesById) { "$stepCtx missing movement from '${mv.fromPlaceId}'." }
                        require(mv.toPlaceId in placesById) { "$stepCtx missing movement to '${mv.toPlaceId}'." }
                        mv.routeId?.let { rid ->
                            val r = routesById[rid]
                                ?: throw IllegalArgumentException("$stepCtx missing movement route '$rid'.")
                            require(r.geometry is RouteGeometry.Documented) {
                                "$stepCtx show_movement references undocumented route '$rid'."
                            }
                        }
                    }
                    step.showAreaId?.let { aid ->
                        require(aid in areasById) { "$stepCtx missing show_area '$aid'." }
                    }
                    step.panel.textIds.forEach { tid ->
                        require(tid in textsById) { "$stepCtx missing panel text '$tid'." }
                    }
                }
            }
        }
    }

    private fun parseVisibility(wireName: String): Visibility =
        Visibility.entries.firstOrNull { it.wireName == wireName }
            ?: throw IllegalArgumentException("Unknown visibility '$wireName'.")

    private fun requireSlugId(value: String, label: String) {
        require(SLUG_ID_REGEX.matches(value)) { "Invalid slug $label '$value'." }
    }

    private fun requireHttpsUrl(value: String, label: String) {
        require(HTTPS_URL_REGEX.containsMatchIn(value)) { "Invalid HTTPS URL for $label: '$value'." }
    }

    private fun invalidPayload(detail: String): PackageResult.Failure =
        PackageResult.Failure(PackageFailure.InvalidPackage(InvalidPackageCause.INVALID_CONTENT_PAYLOAD, detail))
}
