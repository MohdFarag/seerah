package app.seerah.model

/**
 * Category of a classical source work cited by content items.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class SourceKind(val wireName: String) {
    QURAN("quran"),
    HADITH("hadith"),
    SEERAH_BOOK("seerah-book"),
    ATLAS("atlas"),
    SHAMAIL("shamail"),
    COMMENTARY("commentary"),
}

/**
 * Catalogue entry describing a cited source work in `data/sources.json`.
 *
 * @property id Stable identifier slug referenced by [SourceCitation.sourceWorkId].
 * @property kind Category of source work.
 * @property work Arabic title of the book or collection.
 * @property edition Optional edition details.
 * @property url Optional reference URL (`https://...`).
 */
data class SourceWork(
    val id: String,
    val kind: SourceKind,
    val work: String,
    val edition: String? = null,
    val url: String? = null,
) {
    init {
        require(id.isNotBlank() && work.isNotBlank()) { "SourceWork id and work must not be blank." }
        require(edition == null || edition.isNotBlank()) { "SourceWork edition must be null or non-blank." }
        require(url == null || url.isNotBlank()) { "SourceWork url must be null or non-blank." }
    }
}

/**
 * Specific locator reference pointing into a [SourceWork].
 *
 * @property sourceWorkId Identifier of the cited [SourceWork].
 * @property locator Volume/page, hadith number, or surah:ayah reference string.
 * @property url Optional deep-link URI.
 */
data class SourceCitation(val sourceWorkId: String, val locator: String, val url: String? = null) {
    init {
        require(sourceWorkId.isNotBlank() && locator.isNotBlank()) {
            "SourceCitation sourceWorkId and locator must not be blank."
        }
        require(url == null || url.isNotBlank()) { "SourceCitation url must be null or non-blank." }
    }
}

/**
 * Text origin classification enforcing visual distinction between primary narrations and editorial prose.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class TextKind(val wireName: String) {
    QURAN("quran"),
    HADITH("hadith"),
    ATHAR("athar"),
    SEERAH_NARRATION("seerah-narration"),
    EDITORIAL("editorial"),
}

/**
 * Original narration or reviewed editorial text attached to an event.
 *
 * Non-editorial texts always carry [source]; [TextKind.EDITORIAL] texts carry neither [source] nor
 * [excerptOfTextId].
 *
 * @property id Stable identifier slug.
 * @property eventId Owning [SeerahEvent.id], when attached to an event.
 * @property kind Narration or editorial category.
 * @property body Arabic text body shown to the reader.
 * @property source Citation to a [SourceWork] (`null` only for [TextKind.EDITORIAL]).
 * @property excerptOfTextId Identifier of the full text when this item is an excerpt.
 * @property explanation Optional simple word meanings or commentary shown on request.
 * @property explanationSource Optional citation supporting [explanation].
 */
data class ContentText(
    val id: String,
    val eventId: String? = null,
    val kind: TextKind,
    val body: String,
    val source: SourceCitation? = null,
    val excerptOfTextId: String? = null,
    val explanation: String? = null,
    val explanationSource: SourceCitation? = null,
) {
    init {
        require(id.isNotBlank() && body.isNotBlank()) { "ContentText id and body must not be blank." }
        require(eventId == null || eventId.isNotBlank()) { "ContentText eventId must be null or non-blank." }
        if (kind == TextKind.EDITORIAL) {
            require(source == null) { "Editorial text $id must not declare a source citation." }
            require(excerptOfTextId == null) { "Editorial text $id cannot be marked excerpt_of." }
        } else {
            require(source != null) { "Non-editorial text $id (${kind.wireName}) requires a source citation." }
        }
        require(excerptOfTextId == null || (excerptOfTextId.isNotBlank() && excerptOfTextId != id)) {
            "ContentText $id cannot declare self or blank excerptOfTextId."
        }
        require(explanation == null || explanation.isNotBlank()) {
            "ContentText $id explanation must be null or non-blank."
        }
        require(explanationSource == null || explanation != null) {
            "ContentText $id explanationSource requires a non-null explanation."
        }
    }
}
