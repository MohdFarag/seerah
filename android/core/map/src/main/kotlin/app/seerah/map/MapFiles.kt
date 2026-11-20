package app.seerah.map

import java.io.File

/**
 * The map files on the device that the map reads.
 *
 * @property relief the relief tile archive (PMTiles).
 * @property base the vector base tile archive (PMTiles) holding land polygons and coastlines.
 */
data class MapFiles(val relief: File, val base: File)
