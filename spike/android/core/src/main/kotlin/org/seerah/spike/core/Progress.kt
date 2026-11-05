package org.seerah.spike.core

/**
 * User state kept on the device, separate from content. Content updates never overwrite it.
 * IDs are stable across content versions, so progress survives updates.
 */
data class UserState(
    val year: Int,
    val lon: Double,
    val lat: Double,
    val zoom: Double,
    /** Follow-along point. Exploring other events does not move it. */
    val followEventId: String? = null,
    val openEventId: String? = null,
)

data class Reconciled(val state: UserState, val notice: String?)

object ProgressRules {
    /** Fit saved state to a (possibly updated) package without silently losing the user's place. */
    fun reconcile(saved: UserState?, pack: ContentPack): Reconciled {
        if (saved == null) {
            val i = pack.initial
            return Reconciled(UserState(i.year, i.lon, i.lat, i.zoom), null)
        }
        var s = saved.copy(year = saved.year.coerceIn(pack.years.min, pack.years.max))
        var notice: String? = null
        if (s.openEventId != null && pack.event(s.openEventId!!) == null) s = s.copy(openEventId = null)
        val follow = s.followEventId
        if (follow != null && pack.event(follow) == null) {
            // Followed event removed by an update: keep nothing invented, clear and tell the user.
            s = s.copy(followEventId = null)
            notice = "الحدث الذي كنت تتابعه لم يعد في المحتوى بعد التحديث"
        }
        return Reconciled(s, notice)
    }
}
