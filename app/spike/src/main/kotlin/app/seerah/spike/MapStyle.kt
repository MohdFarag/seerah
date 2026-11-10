package app.seerah.spike

/** How map labels get their glyphs. This is the main thing the spike compares. */
enum class TextPath(val label: String) {
    /** Classic path: pre-built glyph PBFs (signed distance fields). */
    GlyphPbf("حروف PBF"),

    /** New path: the engine shapes text from the TTF itself (`font-faces`). */
    FontFaces("font-faces"),
}

enum class MapFont(val label: String, val stack: String, val ttf: String) {
    Plex("Plex", "PlexArabic-Regular", "IBMPlexSansArabic-Regular.ttf"),
    Amiri("Amiri", "Amiri-Regular", "Amiri-Regular.ttf"),
}

object MapStyle {
    // Colours follow the Penpot tokens (athar/day). Hard-coded here for the spike only.
    private const val LAND = "#F1E6C8"
    private const val ROUTE = "#AF5A36"
    private const val LABEL = "#203D32"
    private const val HALO = "#FAF7F0"
    private const val MARK = "#B7963A"

    fun json(textPath: TextPath, font: MapFont, showLabelTest: Boolean): String {
        val fontFaces = if (textPath == TextPath.FontFaces) {
            """"font-faces": { "${font.stack}": "asset://fonts/${font.ttf}" },"""
        } else {
            ""
        }
        val testVisibility = if (showLabelTest) "visible" else "none"
        return """
        {
          "version": 8,
          "name": "seerah-spike",
          $fontFaces
          "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
          "sources": {
            "waypoints": { "type": "geojson", "data": "asset://hijra-waypoints.geojson" },
            "route": { "type": "geojson", "data": "asset://hijra-route.geojson" },
            "label-test": { "type": "geojson", "data": "asset://label-test.geojson" }
          },
          "layers": [
            { "id": "land", "type": "background", "paint": { "background-color": "$LAND" } },
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
              "layout": {
                "text-field": ["get", "name"],
                "text-font": ["${font.stack}"],
                "text-size": 15,
                "text-anchor": "top",
                "text-offset": [0, 0.6],
                "text-max-width": 9
              },
              "paint": { "text-color": "$LABEL", "text-halo-color": "$HALO", "text-halo-width": 1.5 }
            },
            {
              "id": "label-test", "type": "symbol", "source": "label-test",
              "layout": {
                "visibility": "$testVisibility",
                "text-field": ["get", "name"],
                "text-font": ["${font.stack}"],
                "text-size": 20,
                "text-allow-overlap": true,
                "text-ignore-placement": true
              },
              "paint": { "text-color": "$LABEL", "text-halo-color": "$HALO", "text-halo-width": 1.5 }
            }
          ]
        }
        """.trimIndent()
    }
}
