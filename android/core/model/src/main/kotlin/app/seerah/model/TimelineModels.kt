package app.seerah.model

private const val MIN_HIJRI_MONTH = 1
private const val MAX_HIJRI_MONTH = 12
private const val MIN_HIJRI_DAY = 1
private const val MAX_HIJRI_DAY = 31

/**
 * Canonical date axis used by v1 Seerah content ([ADR 0005](../../../docs/adr/0005-content-model-exploration-and-story.md)).
 *
 * Pre-Hijra years (`BH`) count backward cleanly without a year zero before `1 AH`.
 *
 * @property wireName Canonical serialized identifier in compiled content packages.
 */
enum class CalendarAxis(val wireName: String) {
    /** Years before the Hijra (`1 BH, 2 BH, ...`). */
    PRE_HIJRA("pre-hijra"),

    /** Years starting from the Hijra (`1 AH, 2 AH, ...`). */
    HIJRI("hijri"),
}

/**
 * Documented precision level of a historical date or state transition.
 *
 * @property wireName Canonical serialized string inside content payloads.
 * @property rank Deterministic ordering weight within the same calendar year (`YEAR` < `MONTH` < `DAY`).
 */
enum class DatePrecision(val wireName: String, val rank: Int) {
    YEAR("year", 0),
    MONTH("month", 1),
    DAY("day", 2),
    UNKNOWN("unknown", 3),
}

/**
 * Comparable continuous key mapping pre-Hijra and Hijri points onto one linear integer axis.
 *
 * `1 BH` maps to `continuousYear = 0`, `2 BH` to `-1`, and `1 AH` to `+1` (no year zero).
 *
 * @property continuousYear Continuous year coordinate with zero representing `1 BH`.
 * @property month Month coordinate (`0` for state-start year boundaries, `12` for year-end query bounds).
 * @property day Day coordinate (`0` for month/year boundaries, `31` for period-end query bounds).
 */
data class ChronologicalKey(val continuousYear: Int, val month: Int, val day: Int) : Comparable<ChronologicalKey> {
    override fun compareTo(other: ChronologicalKey): Int = compareValuesBy(
        this,
        other,
        ChronologicalKey::continuousYear,
        ChronologicalKey::month,
        ChronologicalKey::day,
    )
}

/**
 * Specific non-unknown point on the canonical pre-Hijra / Hijri timeline.
 *
 * @property axis Whether the point falls before or from the Hijra.
 * @property year Strictly positive year integer (`>= 1`; year zero does not exist).
 * @property month Sourced month (`1..12`), or null for year-only precision.
 * @property day Sourced day (`1..31`, requires non-null [month]), or null.
 */
data class TimelinePoint(val axis: CalendarAxis, val year: Int, val month: Int? = null, val day: Int? = null) :
    Comparable<TimelinePoint> {
    init {
        require(year >= 1) { "Timeline year must be >= 1 (no year zero), got $year." }
        require(month == null || month in MIN_HIJRI_MONTH..MAX_HIJRI_MONTH) {
            "Timeline month $month outside $MIN_HIJRI_MONTH..$MAX_HIJRI_MONTH."
        }
        require(day == null || day in MIN_HIJRI_DAY..MAX_HIJRI_DAY) {
            "Timeline day $day outside $MIN_HIJRI_DAY..$MAX_HIJRI_DAY."
        }
        require(day == null || month != null) { "Timeline day requires a month." }
    }

    /** Highest precision present in this timeline point. */
    val precision: DatePrecision
        get() = when {
            day != null -> DatePrecision.DAY
            month != null -> DatePrecision.MONTH
            else -> DatePrecision.YEAR
        }

    /** Linear year integer eliminating the seam/gap between `1 BH` (`0`) and `1 AH` (`1`). */
    val continuousYear: Int
        get() = when (axis) {
            CalendarAxis.PRE_HIJRA -> 1 - year
            CalendarAxis.HIJRI -> year
        }

    /** Returns the year bucket containing this point. */
    fun yearOnly(): TimelinePoint = TimelinePoint(axis = axis, year = year)

    /** Returns the immediately following year on the canonical axis, crossing `1 BH -> 1 AH` directly. */
    fun nextYear(): TimelinePoint = fromContinuousYear(continuousYear + 1)

    /** Returns the immediately preceding year on the canonical axis, crossing `1 AH -> 1 BH` directly. */
    fun previousYear(): TimelinePoint = fromContinuousYear(continuousYear - 1)

    /** Returns the end-of-year upper-bound point (`month = 12, day = 31`) in this point's year. */
    fun endOfYear(): TimelinePoint = TimelinePoint(
        axis = axis,
        year = year,
        month = MAX_HIJRI_MONTH,
        day = MAX_HIJRI_DAY,
    )

    /**
     * Start-of-period comparison key used by entity state changes (a year-level change applies from
     * month `0`, day `0` of its year).
     */
    fun asStateStartKey(): ChronologicalKey = ChronologicalKey(
        continuousYear = continuousYear,
        month = month ?: 0,
        day = day ?: 0,
    )

    /**
     * End-of-period upper bound used when resolving timeline queries (year views see end-of-year state;
     * month views see state through the end of that month).
     */
    fun asQueryUpperBoundKey(): ChronologicalKey = ChronologicalKey(
        continuousYear = continuousYear,
        month = month ?: MAX_HIJRI_MONTH,
        day = day ?: MAX_HIJRI_DAY,
    )

    override fun compareTo(other: TimelinePoint): Int = asStateStartKey().compareTo(other.asStateStartKey())

    companion object {
        /** Reconstructs a year-precision [TimelinePoint] from a [continuousYear] integer. */
        fun fromContinuousYear(continuousYear: Int): TimelinePoint = if (continuousYear <= 0) {
            TimelinePoint(axis = CalendarAxis.PRE_HIJRA, year = 1 - continuousYear)
        } else {
            TimelinePoint(axis = CalendarAxis.HIJRI, year = continuousYear)
        }
    }
}

/**
 * Documented historical date attached to an event. Invalid precision/field combinations are
 * unrepresentable across the four sealed variants.
 */
sealed interface HistoricalDate {
    /** Sourced precision level of this date. */
    val precision: DatePrecision

    /** Explicit owner-reviewed year of Prophethood when sourced for pre-Hijra / undated items. */
    val prophethoodYear: Int?

    /** Whether the date is marked approximate in historical sources. */
    val approximate: Boolean

    /** Optional scholarly dispute or precision note surfaced in UI/certainty checks. */
    val note: String?

    /** Non-null [TimelinePoint] for dated variants, or null when precision is [DatePrecision.UNKNOWN]. */
    val timelinePointOrNull: TimelinePoint?

    /** Dated to its calendar year only. */
    data class Year(
        val point: TimelinePoint,
        override val prophethoodYear: Int? = null,
        override val approximate: Boolean = false,
        override val note: String? = null,
    ) : HistoricalDate {
        init {
            require(point.precision == DatePrecision.YEAR) {
                "HistoricalDate.Year requires a year-only TimelinePoint, got $point."
            }
            validateProphethoodAndNote(point.axis, prophethoodYear, note)
        }

        override val precision: DatePrecision get() = DatePrecision.YEAR
        override val timelinePointOrNull: TimelinePoint get() = point
    }

    /** Dated to a sourced calendar year and month. */
    data class Month(
        val point: TimelinePoint,
        override val prophethoodYear: Int? = null,
        override val approximate: Boolean = false,
        override val note: String? = null,
    ) : HistoricalDate {
        init {
            require(point.precision == DatePrecision.MONTH) {
                "HistoricalDate.Month requires a month-precision TimelinePoint, got $point."
            }
            validateProphethoodAndNote(point.axis, prophethoodYear, note)
        }

        override val precision: DatePrecision get() = DatePrecision.MONTH
        override val timelinePointOrNull: TimelinePoint get() = point
    }

    /** Dated to a sourced calendar year, month, and day. */
    data class Day(
        val point: TimelinePoint,
        override val prophethoodYear: Int? = null,
        override val approximate: Boolean = false,
        override val note: String? = null,
    ) : HistoricalDate {
        init {
            require(point.precision == DatePrecision.DAY) {
                "HistoricalDate.Day requires a day-precision TimelinePoint, got $point."
            }
            validateProphethoodAndNote(point.axis, prophethoodYear, note)
        }

        override val precision: DatePrecision get() = DatePrecision.DAY
        override val timelinePointOrNull: TimelinePoint get() = point
    }

    /** Undated historical item whose year on the Hijri axis is not established. */
    data class Unknown(
        override val prophethoodYear: Int? = null,
        override val approximate: Boolean = false,
        override val note: String? = null,
    ) : HistoricalDate {
        init {
            require(prophethoodYear == null || prophethoodYear >= 1) {
                "prophethoodYear must be >= 1 when present, got $prophethoodYear."
            }
            require(note == null || note.isNotBlank()) { "HistoricalDate note must be null or non-blank." }
        }

        /** Pre-Hijra calendar when an explicit [prophethoodYear] is documented, else null. */
        val calendar: CalendarAxis?
            get() = if (prophethoodYear != null) CalendarAxis.PRE_HIJRA else null

        override val precision: DatePrecision get() = DatePrecision.UNKNOWN
        override val timelinePointOrNull: TimelinePoint? get() = null
    }
}

private fun validateProphethoodAndNote(axis: CalendarAxis, prophethoodYear: Int?, note: String?) {
    require(prophethoodYear == null || (axis == CalendarAxis.PRE_HIJRA && prophethoodYear >= 1)) {
        "prophethoodYear ($prophethoodYear) requires PRE_HIJRA axis and value >= 1."
    }
    require(note == null || note.isNotBlank()) { "HistoricalDate note must be null or non-blank." }
}
