package app.seerah.map

/** Moves the map's camera. Handed out by the map once it is ready. */
interface MapCamera {
    /**
     * Animates the camera to a place and returns when the animation ends or is cancelled.
     *
     * @param lat the latitude, in degrees.
     * @param lon the longitude, in degrees.
     * @param zoom the MapLibre zoom.
     * @param durationMs how long the animation takes, in milliseconds.
     */
    suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long)
}
