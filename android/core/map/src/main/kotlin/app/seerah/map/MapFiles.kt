package app.seerah.map

import java.io.File

/**
 * The map files on the device that the map reads.
 *
 * @property relief the relief tile archive (PMTiles). The base map file is added with the base
 * map in phase 3.
 */
data class MapFiles(val relief: File)
