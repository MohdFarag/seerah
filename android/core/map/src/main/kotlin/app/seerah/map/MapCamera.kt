package app.seerah.map

/**
 * Moves the map's camera. Handed out by the map once it is ready.
 *
 * Threading: [animateTo] may be called from any coroutine; it switches to the main thread itself.
 */
interface MapCamera {
    /**
     * Animates the camera to a place and returns when the animation ends.
     *
     * If the animation is cancelled, by the calling coroutine or by the map itself (for example
     * when the user touches the map), the call ends with a `CancellationException`, not an error.
     *
     * @param lat the latitude, in degrees.
     * @param lon the longitude, in degrees.
     * @param zoom the MapLibre zoom.
     * @param durationMs how long the animation takes, in milliseconds, from 0 to `Int.MAX_VALUE`.
     * @throws IllegalArgumentException if [durationMs] is outside that range.
     */
    suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long)
}
