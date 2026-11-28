package app.seerah.domain

import app.seerah.model.SeerahEvent
import app.seerah.model.TimelinePoint

/**
 * One year bucket on the timeline together with any sourced months attested by events in that year.
 *
 * @property yearPoint Canonical year point (`month = null`, `day = null`).
 * @property sourcedMonths Sorted unique months (`1..12`) that have sourced `Month` or `Day` events.
 * @property hasEvents True when at least one dated event belongs to this year.
 */
data class YearBucket(val yearPoint: TimelinePoint, val sourcedMonths: List<Int>, val hasEvents: Boolean)

/**
 * Resolves the canonical Hijri / pre-Hijra timeline axis (never inserting a year zero or inventing
 * undocumented months).
 */
object TimelineAxis {
    /**
     * Builds discrete chronological year buckets spanning [minYear]..[maxYear] (expanded to cover any
     * dated [events]), omitting year zero cleanly across `1 BH -> 1 AH`.
     */
    fun buildBuckets(
        events: List<SeerahEvent>,
        minYear: TimelinePoint? = null,
        maxYear: TimelinePoint? = null,
    ): List<YearBucket> {
        val datedEventsByYear = mutableMapOf<Int, MutableList<SeerahEvent>>()
        for (event in events) {
            val pt = event.date.timelinePointOrNull ?: continue
            datedEventsByYear.getOrPut(pt.continuousYear) { mutableListOf() }.add(event)
        }

        val candidatesMin = listOfNotNull(
            minYear?.continuousYear,
            datedEventsByYear.keys.minOrNull(),
        )
        val candidatesMax = listOfNotNull(
            maxYear?.continuousYear,
            datedEventsByYear.keys.maxOrNull(),
        )
        val startContinuous = candidatesMin.minOrNull() ?: return emptyList()
        val endContinuous = candidatesMax.maxOrNull() ?: return emptyList()
        if (startContinuous > endContinuous) return emptyList()

        return (startContinuous..endContinuous).map { continuous ->
            val yearEvents = datedEventsByYear[continuous].orEmpty()
            val sourcedMonths = yearEvents
                .mapNotNull { it.date.timelinePointOrNull?.month }
                .distinct()
                .sorted()
            YearBucket(
                yearPoint = TimelinePoint.fromContinuousYear(continuous),
                sourcedMonths = sourcedMonths,
                hasEvents = yearEvents.isNotEmpty(),
            )
        }
    }

    /**
     * Returns the nearest preceding year before [current] that contains at least one dated event,
     * supporting empty-year prev/next navigation in v1.
     */
    fun previousEventYear(current: TimelinePoint, events: List<SeerahEvent>): TimelinePoint? {
        val currentContinuous = current.continuousYear
        val targetContinuous = events
            .mapNotNull { it.date.timelinePointOrNull?.continuousYear }
            .filter { it < currentContinuous }
            .maxOrNull()
            ?: return null
        return TimelinePoint.fromContinuousYear(targetContinuous)
    }

    /**
     * Returns the nearest following year after [current] that contains at least one dated event.
     */
    fun nextEventYear(current: TimelinePoint, events: List<SeerahEvent>): TimelinePoint? {
        val currentContinuous = current.continuousYear
        val targetContinuous = events
            .mapNotNull { it.date.timelinePointOrNull?.continuousYear }
            .filter { it > currentContinuous }
            .minOrNull()
            ?: return null
        return TimelinePoint.fromContinuousYear(targetContinuous)
    }

    /**
     * Resolves the timeline position for opening [event] at its exact documented precision without
     * inventing a missing month or day; undated events keep [fallback].
     */
    fun eventOpenTarget(event: SeerahEvent, fallback: TimelinePoint): TimelinePoint =
        event.date.timelinePointOrNull ?: fallback
}
