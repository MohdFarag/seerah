package app.seerah.spike

import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Opening an event restores its time and place: the year bar moves to the
 * event's year and the camera flies to its place.
 */
enum class SpikeEvent(val title: String, val year: Int) {
    Hijra("الهجرة", 1),
    Mosque("بناء المسجد النبوي", 1),
    Badr("غزوة بدر", 2),
    ;

    /** [top] and [bottom] are the pixels covered by the panels above and below the map. */
    fun camera(top: Int, bottom: Int, side: Int): CameraUpdate = when (this) {
        Hijra -> CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.Builder().include(LatLng(21.33, 39.1)).include(LatLng(24.47, 39.9)).build(),
            side, top, side, bottom,
        )
        Mosque -> CameraUpdateFactory.newLatLngZoom(LatLng(24.4686, 39.6111), 14.5)
        // Spike only: the modern town of Badr, not a reviewed battlefield point.
        Badr -> CameraUpdateFactory.newLatLngZoom(LatLng(23.78, 38.7906), 11.5)
    }
}
