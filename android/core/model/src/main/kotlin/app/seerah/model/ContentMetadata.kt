package app.seerah.model

private const val SUPPORTED_CONTENT_SCHEMA = 1

/**
 * Metadata summarizing the currently active imported `content` package.
 *
 * @property contentSchema Package content payload format version (always `1`).
 * @property contentVersion Human-readable semantic/version label authored in `content.yaml`.
 * @property packageVersion Strictly increasing installed signed package integer version (`>= 1`).
 * @property generation Installed storage generation directory counter (`>= 1`).
 * @property minTimelinePoint Earliest dated year in the corpus, or null when only undated events exist.
 * @property maxTimelinePoint Latest dated year in the corpus, or null when only undated events exist.
 */
data class ContentPackageSummary(
    val contentSchema: Int,
    val contentVersion: String,
    val packageVersion: Int,
    val generation: Long,
    val minTimelinePoint: TimelinePoint? = null,
    val maxTimelinePoint: TimelinePoint? = null,
) {
    init {
        require(contentSchema == SUPPORTED_CONTENT_SCHEMA) {
            "Unsupported contentSchema $contentSchema; expected $SUPPORTED_CONTENT_SCHEMA."
        }
        require(contentVersion.isNotBlank()) { "contentVersion must not be blank." }
        require(packageVersion >= 1 && generation >= 1L) {
            "packageVersion ($packageVersion) and generation ($generation) must be >= 1."
        }
        require((minTimelinePoint == null) == (maxTimelinePoint == null)) {
            "minTimelinePoint and maxTimelinePoint must either both be null or both present."
        }
        if (minTimelinePoint != null && maxTimelinePoint != null) {
            require(
                minTimelinePoint.precision == DatePrecision.YEAR &&
                    maxTimelinePoint.precision == DatePrecision.YEAR,
            ) {
                "Corpus bounds must have YEAR precision."
            }
            require(minTimelinePoint <= maxTimelinePoint) {
                "minTimelinePoint ($minTimelinePoint) cannot exceed maxTimelinePoint ($maxTimelinePoint)."
            }
        }
    }
}

/**
 * Atomic, generation-consistent snapshot of exploration entities and story summaries for one active
 * content package version, avoiding cross-stream tearing across updates.
 *
 * Heavy Arabic narration texts ([EventDetail.texts]) and nested story step scripts ([Story]) are
 * queried lazily by ID when needed.
 */
data class ExplorationCorpus(
    val summary: ContentPackageSummary,
    val phases: List<Phase>,
    val sources: Map<String, SourceWork>,
    val places: List<Place>,
    val areas: List<Area>,
    val routes: List<Route>,
    val events: List<SeerahEvent>,
    val storySummaries: List<StorySummary>,
)
