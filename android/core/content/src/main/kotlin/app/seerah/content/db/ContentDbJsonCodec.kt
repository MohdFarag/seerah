package app.seerah.content.db

import app.seerah.model.AreaState
import app.seerah.model.CalendarAxis
import app.seerah.model.CameraTransition
import app.seerah.model.DatePrecision
import app.seerah.model.EventLocation
import app.seerah.model.EventRelation
import app.seerah.model.GeoPoint
import app.seerah.model.KnownAccuracy
import app.seerah.model.MapTargetRef
import app.seerah.model.PlaceKind
import app.seerah.model.PlaceReference
import app.seerah.model.PlaceState
import app.seerah.model.RelationType
import app.seerah.model.ShotSize
import app.seerah.model.Situation
import app.seerah.model.StateStart
import app.seerah.model.StepCamera
import app.seerah.model.StepMovement
import app.seerah.model.StepPanel
import app.seerah.model.StepRouteDrawing
import app.seerah.model.StepTime
import app.seerah.model.StoryChapter
import app.seerah.model.StoryScene
import app.seerah.model.StoryStep
import app.seerah.model.TimelinePoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
internal data class GeoPointDto(val lat: Double, val lon: Double)

@Serializable
internal data class TimelineDto(
    val calendar: String,
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
    val precision: String? = null,
)

@Serializable
internal data class PlaceStateDto(
    val start: JsonElement,
    val name: String,
    val exists: Boolean,
    @SerialName("kind_override")
    val kindOverride: String? = null,
)

@Serializable
internal data class AreaStateDto(val start: JsonElement, val name: String, val exists: Boolean)

@Serializable
internal data class EventWhereDto(
    val kind: String,
    @SerialName("place_id")
    val placeId: String? = null,
    @SerialName("place_ids")
    val placeIds: List<String>? = null,
    @SerialName("area_id")
    val areaId: String? = null,
    val accuracy: String? = null,
)

@Serializable
internal data class SituationPlaceDto(val accuracy: String, @SerialName("place_id") val placeId: String? = null)

@Serializable
internal data class SituationDto(
    val id: String,
    val order: Int,
    val title: String,
    val place: SituationPlaceDto? = null,
    @SerialName("text_ids")
    val textIds: List<String>,
)

@Serializable
internal data class EventRelationDto(@SerialName("target_event_id") val targetEventId: String, val type: String)

@Serializable
internal data class StepCameraDto(
    val mode: String,
    val targets: List<String>? = null,
    @SerialName("route_id")
    val routeId: String? = null,
    @SerialName("from_station_index")
    val fromStationIndex: Int? = null,
    @SerialName("to_station_index")
    val toStationIndex: Int? = null,
    val shot: String? = null,
    val transition: String? = null,
)

@Serializable
internal data class StepTimeDto(
    val mode: String,
    val calendar: String? = null,
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val precision: String? = null,
    val from: TimelineDto? = null,
    val to: TimelineDto? = null,
)

@Serializable
internal data class StepRouteDrawingDto(
    @SerialName("route_id")
    val routeId: String,
    @SerialName("from_station_index")
    val fromStationIndex: Int,
    @SerialName("to_station_index")
    val toStationIndex: Int,
)

@Serializable
internal data class StepMovementDto(
    @SerialName("from_place_id")
    val fromPlaceId: String,
    @SerialName("to_place_id")
    val toPlaceId: String,
    @SerialName("route_id")
    val routeId: String? = null,
)

@Serializable
internal data class StepPanelDto(
    @SerialName("title_card")
    val titleCard: Boolean,
    @SerialName("text_ids")
    val textIds: List<String>? = null,
    @SerialName("linking_sentence")
    val linkingSentence: String? = null,
    @SerialName("explore_here")
    val exploreHere: Boolean? = null,
)

@Serializable
internal data class StoryStepDto(
    val id: String,
    val order: Int,
    val camera: StepCameraDto,
    val time: StepTimeDto,
    val highlight: List<String>? = null,
    @SerialName("focus_mode")
    val focusMode: Boolean? = null,
    val reveal: List<String>? = null,
    @SerialName("draw_route")
    val drawRoute: StepRouteDrawingDto? = null,
    @SerialName("show_movement")
    val showMovement: StepMovementDto? = null,
    @SerialName("show_area")
    val showArea: String? = null,
    val panel: StepPanelDto,
)

@Serializable
internal data class StorySceneDto(
    val id: String,
    @SerialName("event_id")
    val eventId: String? = null,
    val order: Int,
    val title: String,
    val steps: List<StoryStepDto>,
)

@Serializable
internal data class StoryChapterDto(
    val id: String,
    @SerialName("phase_id")
    val phaseId: String? = null,
    val order: Int,
    val title: String,
    val scenes: List<StorySceneDto>,
)

/** Encodes and decodes normalized nested structures between `:core:model` types and Room TEXT columns. */
internal object ContentDbJsonCodec {
    private val dbJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

    fun encodePlaceStates(states: List<PlaceState>): String = dbJson.encodeToString(states.map { it.toDto() })

    fun decodePlaceStates(raw: String): List<PlaceState> =
        dbJson.decodeFromString<List<PlaceStateDto>>(raw).map { it.toModel() }

    fun encodeAreaStates(states: List<AreaState>): String = dbJson.encodeToString(states.map { it.toDto() })

    fun decodeAreaStates(raw: String): List<AreaState> =
        dbJson.decodeFromString<List<AreaStateDto>>(raw).map { it.toModel() }

    fun encodePoints(points: List<GeoPoint>): String = dbJson.encodeToString(points.map { GeoPointDto(it.lat, it.lon) })

    fun decodePoints(raw: String): List<GeoPoint> =
        dbJson.decodeFromString<List<GeoPointDto>>(raw).map { GeoPoint(it.lat, it.lon) }

    fun encodeStrings(values: List<String>): String = dbJson.encodeToString(values)

    fun decodeStrings(raw: String): List<String> = dbJson.decodeFromString(raw)

    fun encodeInts(values: List<Int>): String = dbJson.encodeToString(values)

    fun decodeInts(raw: String): List<Int> = dbJson.decodeFromString(raw)

    fun encodeWhere(where: EventLocation): String = dbJson.encodeToString(where.toDto())

    fun decodeWhere(raw: String): EventLocation = dbJson.decodeFromString<EventWhereDto>(raw).toModel()

    fun encodeSituations(situations: List<Situation>): String = dbJson.encodeToString(situations.map { it.toDto() })

    fun decodeSituations(raw: String): List<Situation> =
        dbJson.decodeFromString<List<SituationDto>>(raw).map { it.toModel() }

    fun encodeRelations(relations: List<EventRelation>): String = dbJson.encodeToString(relations.map { it.toDto() })

    fun decodeRelations(raw: String): List<EventRelation> =
        dbJson.decodeFromString<List<EventRelationDto>>(raw).map { it.toModel() }

    fun encodeChapters(chapters: List<StoryChapter>): String = dbJson.encodeToString(chapters.map { it.toDto() })

    fun decodeChapters(raw: String): List<StoryChapter> =
        dbJson.decodeFromString<List<StoryChapterDto>>(raw).map { it.toModel() }

    fun parseCalendarAxis(wireName: String): CalendarAxis = when (wireName) {
        CalendarAxis.PRE_HIJRA.wireName -> CalendarAxis.PRE_HIJRA
        CalendarAxis.HIJRI.wireName -> CalendarAxis.HIJRI
        else -> throw IllegalArgumentException("Unknown calendar axis: '$wireName'")
    }

    fun parseDatePrecision(wireName: String): DatePrecision = when (wireName) {
        DatePrecision.YEAR.wireName -> DatePrecision.YEAR
        DatePrecision.MONTH.wireName -> DatePrecision.MONTH
        DatePrecision.DAY.wireName -> DatePrecision.DAY
        DatePrecision.UNKNOWN.wireName -> DatePrecision.UNKNOWN
        else -> throw IllegalArgumentException("Unknown date precision: '$wireName'")
    }

    fun parseKnownAccuracy(wireName: String): KnownAccuracy = when (wireName) {
        KnownAccuracy.EXACT.wireName -> KnownAccuracy.EXACT
        KnownAccuracy.GENERAL.wireName -> KnownAccuracy.GENERAL
        else -> throw IllegalArgumentException("Unknown known accuracy: '$wireName'")
    }

    fun parsePlaceKind(wireName: String): PlaceKind = PlaceKind.entries.firstOrNull { it.wireName == wireName }
        ?: throw IllegalArgumentException("Unknown place kind: '$wireName'")

    fun timelineToDto(point: TimelinePoint): TimelineDto = TimelineDto(
        calendar = point.axis.wireName,
        year = point.year,
        month = point.month,
        day = point.day,
        precision = point.precision.wireName,
    )

    fun timelineToModel(dto: TimelineDto): TimelinePoint {
        val axis = parseCalendarAxis(dto.calendar)
        val point = TimelinePoint(axis = axis, year = dto.year, month = dto.month, day = dto.day)
        dto.precision?.let { prec ->
            require(prec == point.precision.wireName) {
                "Timeline precision '$prec' disagrees with populated fields in $dto."
            }
        }
        return point
    }

    fun stateStartToModel(element: JsonElement): StateStart =
        if (element is JsonPrimitive && element.isString && element.content == "baseline") {
            StateStart.Baseline
        } else {
            val dto = dbJson.decodeFromJsonElement<TimelineDto>(element)
            require(dto.precision != null) { "Dated state start requires explicit precision." }
            StateStart.Dated(timelineToModel(dto))
        }

    fun PlaceStateDto.toModel(): PlaceState = PlaceState(
        start = stateStartToModel(start),
        name = name,
        exists = exists,
        kindOverride = kindOverride?.let(::parsePlaceKind),
    )

    fun AreaStateDto.toModel(): AreaState = AreaState(
        start = stateStartToModel(start),
        name = name,
        exists = exists,
    )

    fun EventWhereDto.toModel(): EventLocation = when (kind) {
        "single_place" -> {
            require(placeIds == null && areaId == null) { "single_place forbids place_ids and area_id." }
            EventLocation.SinglePlace(
                placeId = requireNotNull(placeId) { "single_place requires place_id." },
                accuracy = parseKnownAccuracy(requireNotNull(accuracy) { "single_place requires accuracy." }),
            )
        }

        "multi_place" -> {
            require(placeId == null && areaId == null) { "multi_place forbids place_id and area_id." }
            EventLocation.MultiPlace(
                placeIds = requireNotNull(placeIds) { "multi_place requires place_ids." },
                accuracy = parseKnownAccuracy(requireNotNull(accuracy) { "multi_place requires accuracy." }),
            )
        }

        "area" -> {
            require(placeId == null && placeIds == null) { "area forbids place_id and place_ids." }
            val acc = requireNotNull(accuracy) { "area requires accuracy." }
            require(acc == KnownAccuracy.GENERAL.wireName) { "area where must use accuracy 'general', got '$acc'." }
            EventLocation.AreaTarget(
                areaId = requireNotNull(areaId) { "area requires area_id." },
            )
        }

        "route" -> {
            require(placeId == null && placeIds == null && areaId == null) {
                "route forbids place_id, place_ids, and area_id."
            }
            EventLocation.RouteTarget(
                accuracy = parseKnownAccuracy(requireNotNull(accuracy) { "route requires accuracy." }),
            )
        }

        "unknown" -> {
            require(placeId == null && placeIds == null && areaId == null && accuracy == null) {
                "unknown where must not declare location fields."
            }
            EventLocation.Unknown
        }

        else -> throw IllegalArgumentException("Unknown event where kind: '$kind'")
    }

    fun SituationDto.toModel(): Situation {
        val ref = place?.let { p ->
            when (p.accuracy) {
                "unknown" -> {
                    require(p.placeId == null) { "Unknown situation place must omit place_id." }
                    PlaceReference.Unknown
                }

                else -> PlaceReference.Known(
                    accuracy = parseKnownAccuracy(p.accuracy),
                    placeId = requireNotNull(p.placeId) { "Known situation place requires place_id." },
                )
            }
        }
        return Situation(id = id, order = order, title = title, place = ref, textIds = textIds)
    }

    fun EventRelationDto.toModel(): EventRelation = EventRelation(
        targetEventId = targetEventId,
        type = RelationType.entries.firstOrNull { it.wireName == type }
            ?: throw IllegalArgumentException("Unknown relation type: '$type'"),
    )

    fun StoryChapterDto.toModel(): StoryChapter = StoryChapter(
        id = id,
        phaseId = phaseId,
        order = order,
        title = title,
        scenes = scenes.map { it.toModel() },
    )

    private fun StorySceneDto.toModel(): StoryScene = StoryScene(
        id = id,
        eventId = eventId,
        order = order,
        title = title,
        steps = steps.map { it.toModel() },
    )

    private fun StoryStepDto.toModel(): StoryStep = StoryStep(
        id = id,
        order = order,
        camera = camera.toModel(),
        time = time.toModel(),
        highlight = highlight.orEmpty().map(::parseTargetRef),
        focusMode = focusMode ?: false,
        reveal = reveal.orEmpty().map(::parseTargetRef),
        drawRoute = drawRoute?.let {
            StepRouteDrawing(
                routeId = it.routeId,
                fromStationIndex = it.fromStationIndex,
                toStationIndex = it.toStationIndex,
            )
        },
        showMovement = showMovement?.let {
            StepMovement(
                fromPlaceId = it.fromPlaceId,
                toPlaceId = it.toPlaceId,
                routeId = it.routeId,
            )
        },
        showAreaId = showArea,
        panel = StepPanel(
            titleCard = panel.titleCard,
            textIds = panel.textIds.orEmpty(),
            linkingSentence = panel.linkingSentence,
            exploreHere = panel.exploreHere ?: false,
        ),
    )

    private fun StepCameraDto.toModel(): StepCamera {
        val parsedTransition = transition?.let(::parseTransition) ?: CameraTransition.AUTO
        return when (mode) {
            "frame" -> {
                require(routeId == null && fromStationIndex == null && toStationIndex == null) {
                    "frame camera forbids follow fields."
                }
                StepCamera.Frame(
                    targets = requireNotNull(targets) { "frame camera requires targets." }.map(::parseTargetRef),
                    shot = parseShotSize(requireNotNull(shot) { "frame camera requires shot." }),
                    transition = parsedTransition,
                )
            }

            "follow" -> {
                require(targets == null) { "follow camera forbids targets." }
                StepCamera.Follow(
                    routeId = requireNotNull(routeId) { "follow camera requires route_id." },
                    fromStationIndex = requireNotNull(fromStationIndex) {
                        "follow camera requires from_station_index."
                    },
                    toStationIndex = requireNotNull(toStationIndex) { "follow camera requires to_station_index." },
                    shot = shot?.let(::parseShotSize),
                    transition = parsedTransition,
                )
            }

            else -> throw IllegalArgumentException("Unknown camera mode: '$mode'")
        }
    }

    private fun StepTimeDto.toModel(): StepTime = when (mode) {
        "at" -> {
            require(from == null && to == null) { "at time forbids time_lapse from/to fields." }
            val pt = timelineToModel(
                TimelineDto(
                    calendar = requireNotNull(calendar) { "at time requires calendar." },
                    year = requireNotNull(year) { "at time requires year." },
                    month = month,
                    day = day,
                    precision = requireNotNull(precision) { "at time requires precision." },
                ),
            )
            StepTime.At(pt)
        }

        "time_lapse" -> {
            require(calendar == null && year == null && month == null && day == null && precision == null) {
                "time_lapse forbids at-mode date fields."
            }
            StepTime.TimeLapse(
                from = timelineToModel(requireNotNull(from) { "time_lapse requires from." }),
                to = timelineToModel(requireNotNull(to) { "time_lapse requires to." }),
            )
        }

        else -> throw IllegalArgumentException("Unknown step time mode: '$mode'")
    }

    private fun parseTargetRef(raw: String): MapTargetRef {
        val colonIdx = raw.indexOf(':')
        require(colonIdx > 0 && colonIdx < raw.lastIndex) { "Malformed MapTargetRef '$raw'." }
        val prefix = raw.substring(0, colonIdx)
        val id = raw.substring(colonIdx + 1)
        return when (prefix) {
            "place" -> MapTargetRef.PlaceRef(id)
            "area" -> MapTargetRef.AreaRef(id)
            "event" -> MapTargetRef.EventRef(id)
            "route" -> MapTargetRef.RouteRef(id)
            else -> throw IllegalArgumentException("Unknown MapTargetRef prefix '$prefix' in '$raw'.")
        }
    }

    private fun parseShotSize(wireName: String): ShotSize = ShotSize.entries.firstOrNull { it.wireName == wireName }
        ?: throw IllegalArgumentException("Unknown shot size: '$wireName'")

    private fun parseTransition(wireName: String): CameraTransition =
        CameraTransition.entries.firstOrNull { it.wireName == wireName }
            ?: throw IllegalArgumentException("Unknown camera transition: '$wireName'")

    private fun PlaceState.toDto(): PlaceStateDto = PlaceStateDto(
        start = start.toJsonElement(),
        name = name,
        exists = exists,
        kindOverride = kindOverride?.wireName,
    )

    private fun AreaState.toDto(): AreaStateDto = AreaStateDto(
        start = start.toJsonElement(),
        name = name,
        exists = exists,
    )

    private fun StateStart.toJsonElement(): JsonElement = when (this) {
        StateStart.Baseline -> JsonPrimitive("baseline")
        is StateStart.Dated -> dbJson.encodeToJsonElement(timelineToDto(point))
    }

    private fun EventLocation.toDto(): EventWhereDto = when (this) {
        is EventLocation.SinglePlace -> EventWhereDto(
            kind = "single_place",
            placeId = placeId,
            accuracy = accuracy.wireName,
        )

        is EventLocation.MultiPlace -> EventWhereDto(
            kind = "multi_place",
            placeIds = placeIds,
            accuracy = accuracy.wireName,
        )

        is EventLocation.AreaTarget -> EventWhereDto(
            kind = "area",
            areaId = areaId,
            accuracy = accuracy.wireName,
        )

        is EventLocation.RouteTarget -> EventWhereDto(
            kind = "route",
            accuracy = accuracy.wireName,
        )

        EventLocation.Unknown -> EventWhereDto(kind = "unknown")
    }

    private fun Situation.toDto(): SituationDto = SituationDto(
        id = id,
        order = order,
        title = title,
        place = place?.let { ref ->
            when (ref) {
                is PlaceReference.Known -> SituationPlaceDto(accuracy = ref.accuracy.wireName, placeId = ref.placeId)
                PlaceReference.Unknown -> SituationPlaceDto(accuracy = "unknown")
            }
        },
        textIds = textIds,
    )

    private fun EventRelation.toDto(): EventRelationDto = EventRelationDto(
        targetEventId = targetEventId,
        type = type.wireName,
    )

    private fun StoryChapter.toDto(): StoryChapterDto = StoryChapterDto(
        id = id,
        phaseId = phaseId,
        order = order,
        title = title,
        scenes = scenes.map { it.toDto() },
    )

    private fun StoryScene.toDto(): StorySceneDto = StorySceneDto(
        id = id,
        eventId = eventId,
        order = order,
        title = title,
        steps = steps.map { it.toDto() },
    )

    private fun StoryStep.toDto(): StoryStepDto = StoryStepDto(
        id = id,
        order = order,
        camera = camera.toDto(),
        time = time.toDto(),
        highlight = highlight.takeIf { it.isNotEmpty() }?.map(::formatTargetRef),
        focusMode = focusMode.takeIf { it },
        reveal = reveal.takeIf { it.isNotEmpty() }?.map(::formatTargetRef),
        drawRoute = drawRoute?.let {
            StepRouteDrawingDto(it.routeId, it.fromStationIndex, it.toStationIndex)
        },
        showMovement = showMovement?.let {
            StepMovementDto(it.fromPlaceId, it.toPlaceId, it.routeId)
        },
        showArea = showAreaId,
        panel = StepPanelDto(
            titleCard = panel.titleCard,
            textIds = panel.textIds.takeIf { it.isNotEmpty() },
            linkingSentence = panel.linkingSentence,
            exploreHere = panel.exploreHere.takeIf { it },
        ),
    )

    private fun StepCamera.toDto(): StepCameraDto = when (this) {
        is StepCamera.Frame -> StepCameraDto(
            mode = "frame",
            targets = targets.map(::formatTargetRef),
            shot = shot.wireName,
            transition = transition.wireName,
        )

        is StepCamera.Follow -> StepCameraDto(
            mode = "follow",
            routeId = routeId,
            fromStationIndex = fromStationIndex,
            toStationIndex = toStationIndex,
            shot = shot?.wireName,
            transition = transition.wireName,
        )
    }

    private fun StepTime.toDto(): StepTimeDto = when (this) {
        is StepTime.At -> StepTimeDto(
            mode = "at",
            calendar = point.axis.wireName,
            year = point.year,
            month = point.month,
            day = point.day,
            precision = point.precision.wireName,
        )

        is StepTime.TimeLapse -> StepTimeDto(
            mode = "time_lapse",
            from = timelineToDto(from),
            to = timelineToDto(to),
        )
    }

    private fun formatTargetRef(ref: MapTargetRef): String = when (ref) {
        is MapTargetRef.PlaceRef -> "place:${ref.id}"
        is MapTargetRef.AreaRef -> "area:${ref.id}"
        is MapTargetRef.EventRef -> "event:${ref.id}"
        is MapTargetRef.RouteRef -> "route:${ref.id}"
    }
}
