package app.seerah.spike

/** How map labels get their glyphs. This is the main thing the spike compares. */
enum class TextPath(val label: String) {
    /** Classic path: pre-built glyph PBFs (signed distance fields). */
    GlyphPbf("حروف PBF"),

    /** New path: the engine shapes text from the TTF itself (`font-faces`). */
    FontFaces("font-faces"),

    /**
     * Labels drawn by Android's own text engine into bitmaps, then placed by the
     * map as icons. Shaping, diacritics and direction come from the system.
     */
    SystemImages("نص النظام"),
}

enum class MapFont(val label: String, val stack: String, val ttf: String) {
    Plex("Plex", "PlexArabic-Regular", "IBMPlexSansArabic-Regular.ttf"),
    Amiri("Amiri", "Amiri-Regular", "Amiri-Regular.ttf"),
}

object MapStyle {
    // Colours follow the Penpot tokens (athar/day). Hard-coded here for the spike only.
    private const val LAND = "#F1E6C8"
    private const val SEA = "#809B94"
    private const val ROUTE = "#AF5A36"
    const val LABEL = "#203D32"
    const val HALO = "#FAF7F0"
    private const val MARK = "#B7963A"

    /**
     * @param baseDir folder on device storage holding land.pmtiles and relief.pmtiles,
     *   or null when the base map was not bundled.
     */
    fun json(textPath: TextPath, font: MapFont, showLabelTest: Boolean, baseDir: String?): String {
        val baseSources = if (baseDir != null) {
            """
            "land": { "type": "vector", "url": "pmtiles://file://$baseDir/land.pmtiles" },
            "relief": { "type": "raster", "url": "pmtiles://file://$baseDir/relief.pmtiles", "tileSize": 256 },
            """
        } else {
            ""
        }
        val baseLayers = if (baseDir != null) {
            """
            { "id": "sea", "type": "background", "paint": { "background-color": "$SEA" } },
            { "id": "land", "type": "fill", "source": "land", "source-layer": "land",
              "paint": { "fill-color": "$LAND", "fill-antialias": false } },
            { "id": "relief", "type": "raster", "source": "relief",
              "paint": { "raster-fade-duration": 0 } },
            """
        } else {
            """{ "id": "land", "type": "background", "paint": { "background-color": "$LAND" } },"""
        }
        val fontFaces = if (textPath == TextPath.FontFaces) {
            """"font-faces": { "${font.stack}": "asset://fonts/${font.ttf}" },"""
        } else {
            ""
        }
        val testVisibility = if (showLabelTest) "visible" else "none"
        val systemText = textPath == TextPath.SystemImages
        val waypointLabel = if (systemText) {
            """
                "icon-image": ["concat", "${LabelImages.PREFIX}", ["get", "name"]],
                "icon-anchor": "top",
                "icon-offset": [0, 6]
            """
        } else {
            """
                "text-field": ["get", "name"],
                "text-font": ["${font.stack}"],
                "text-size": ${LabelImages.WAYPOINT_SP},
                "text-anchor": "top",
                "text-offset": [0, 0.6],
                "text-max-width": 9
            """
        }
        val testLabel = if (systemText) {
            """
                "icon-image": ["concat", "${LabelImages.PREFIX}", ["get", "name"]],
                "icon-size": ${LabelImages.TEST_SP / LabelImages.WAYPOINT_SP},
                "icon-allow-overlap": true,
                "icon-ignore-placement": true
            """
        } else {
            """
                "text-field": ["get", "name"],
                "text-font": ["${font.stack}"],
                "text-size": ${LabelImages.TEST_SP},
                "text-allow-overlap": true,
                "text-ignore-placement": true
            """
        }
        return """
        {
          "version": 8,
          "name": "seerah-spike",
          $fontFaces
          "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
          "sources": {
            $baseSources
            "waypoints": { "type": "geojson", "data": "asset://hijra-waypoints.geojson" },
            "route": { "type": "geojson", "data": "asset://hijra-route.geojson" },
            "label-test": { "type": "geojson", "data": "asset://label-test.geojson" }
          },
          "layers": [
            $baseLayers
            {
              "id": "route", "type": "line", "source": "route",
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": { "line-color": "$ROUTE", "line-width": 2.5, "line-dasharray": [2, 2] }
            },
            {
              "id": "waypoint-candidate", "type": "circle", "source": "waypoints",
              "filter": ["==", ["get", "position"], "candidate"],
              "paint": {
                "circle-radius": 5, "circle-color": "$HALO",
                "circle-stroke-color": "$ROUTE", "circle-stroke-width": 1.5
              }
            },
            {
              "id": "waypoint", "type": "circle", "source": "waypoints",
              "filter": ["==", ["get", "position"], "source"],
              "paint": {
                "circle-radius": 5, "circle-color": "$MARK",
                "circle-stroke-color": "$HALO", "circle-stroke-width": 2
              }
            },
            {
              "id": "waypoint-label", "type": "symbol", "source": "waypoints",
              "layout": { $waypointLabel },
              "paint": { "text-color": "$LABEL", "text-halo-color": "$HALO", "text-halo-width": 1.5 }
            },
            {
              "id": "label-test", "type": "symbol", "source": "label-test",
              "layout": { "visibility": "$testVisibility", $testLabel },
              "paint": { "text-color": "$LABEL", "text-halo-color": "$HALO", "text-halo-width": 1.5 }
            }
          ]
        }
        """.trimIndent()
    }
}
