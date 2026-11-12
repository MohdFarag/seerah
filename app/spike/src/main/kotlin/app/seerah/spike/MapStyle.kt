package app.seerah.spike

/** How map labels get their glyphs. This is the main thing the spike compares. */
enum class TextPath(val label: String) {
    /** Classic path: pre-built glyph PBFs (signed distance fields). */
    GlyphPbf("حروف PBF"),

    /** New path: the engine shapes text from the TTF itself (`font-faces`). */
    FontFaces("font-faces"),

    /** Labels drawn by Android's own text engine into bitmaps, placed by the map as icons. */
    SystemImages("نص النظام"),
}

enum class MapFont(val label: String, val stack: String, val ttf: String) {
    Plex("Plex", "PlexArabic-Regular", "IBMPlexSansArabic-Regular.ttf"),
    Amiri("Amiri", "Amiri-Regular", "Amiri-Regular.ttf"),
}

/**
 * Zoom ladder: each step in shows the next level of detail.
 *
 *  - cities (مكة، المدينة): zoom 0 to [CITY_MAX]; past that the city is the whole screen
 *  - key Hijra stations (tier 1): from 6.5
 *  - other stations (tier 2): from 8.5
 *  - landmarks inside a city, and the houses in Makkah (tier 3): from 11
 *
 * A marker and its name are one symbol, so they always appear together.
 */
object MapStyle {
    private const val LAND = "#F1E6C8"
    private const val SEA = "#809B94"
    const val ROUTE = "#AF5A36"
    const val LABEL = "#203D32"
    const val HALO = "#FAF7F0"

    private const val CITY_MAX = 11.5
    private val tierZoom = mapOf(1 to 6.5, 2 to 8.5, 3 to 11.0)

    fun json(textPath: TextPath, font: MapFont, showLabelTest: Boolean, baseDir: String?, eras: List<EraFeature>): String {
        val system = textPath == TextPath.SystemImages
        val fontFaces = if (textPath == TextPath.FontFaces) {
            """"font-faces": { "${font.stack}": "asset://fonts/${font.ttf}" },"""
        } else {
            ""
        }

        /** A marker with its name beside it; [prefix] picks the image set in system-text mode. */
        fun marker(prefix: String, dot: String, sizeSp: Float = LabelImages.TEXT_SP): String = if (system) {
            """
                "icon-image": ["concat", "$prefix", ["get", "name"]],
                "icon-anchor": "left", "icon-offset": [-${LabelImages.dotRadiusDp(prefix)}, 0],
                "icon-padding": 1
            """
        } else {
            """
                "icon-image": "$dot",
                "text-field": ["get", "name"],
                "text-font": ["${font.stack}"],
                "text-size": $sizeSp,
                "text-variable-anchor": ["left", "right", "top", "bottom"],
                "text-radial-offset": 0.5,
                "text-justify": "auto", "text-max-width": 9, "text-padding": 1
            """
        }

        fun text(sizeSp: Float): String = if (system) {
            """"icon-image": ["concat", "${LabelImages.LABEL}", ["get", "name"]]"""
        } else {
            """"text-field": ["get", "name"], "text-font": ["${font.stack}"], "text-size": $sizeSp"""
        }

        val textPaint = """"paint": { "text-color": "$LABEL", "text-halo-color": "$HALO", "text-halo-width": 1.5 }"""

        val baseSources = baseDir?.let {
            """
            "land": { "type": "vector", "url": "pmtiles://file://$it/land.pmtiles" },
            "relief": { "type": "raster", "url": "pmtiles://file://$it/relief.pmtiles", "tileSize": 256 },
            """
        }.orEmpty()
        val baseLayers = if (baseDir != null) {
            """
            { "id": "sea", "type": "background", "paint": { "background-color": "$SEA" } },
            { "id": "land", "type": "fill", "source": "land", "source-layer": "land",
              "paint": { "fill-color": "$LAND", "fill-antialias": false } },
            { "id": "relief", "type": "raster", "source": "relief",
              "paint": { "raster-fade-duration": 0,
                "raster-opacity": ["interpolate", ["linear"], ["zoom"], 12.5, 1, 14.5, 0] } },
            """
        } else {
            """{ "id": "land", "type": "background", "paint": { "background-color": "$LAND" } },"""
        }

        fun byId(id: String) = """["==", ["get", "id"], "$id"]"""

        // Layers later in the list are placed first and win label collisions,
        // so the order below runs from least to most important.
        val layers = mutableListOf<String>()
        eras.filter { it.kind == "region" }.forEach {
            layers += """{ "id": "era-${it.id}-fill", "type": "fill", "source": "eras", "filter": ${byId(it.id)},
                "paint": { "fill-color": "$ROUTE", "fill-opacity": 0 } }"""
            layers += """{ "id": "era-${it.id}-line", "type": "line", "source": "eras", "filter": ${byId(it.id)},
                "paint": { "line-color": "$ROUTE", "line-width": 1.5, "line-dasharray": [4, 3], "line-opacity": 0 } }"""
        }
        eras.filter { it.kind == "route" }.forEach {
            layers += """{ "id": "era-${it.id}", "type": "line", "source": "route", "filter": ${byId(it.id)},
                "layout": { "line-cap": "round", "line-join": "round" },
                "paint": { "line-color": "$ROUTE", "line-opacity": 0, "line-dasharray": [2, 2],
                  "line-width": ["interpolate", ["linear"], ["zoom"], 5, 1, 10, 2.5] } }"""
        }
        listOf(3, 2, 1).forEach { tier ->
            layers += """{ "id": "station-$tier", "type": "symbol", "source": "waypoints", "minzoom": ${tierZoom.getValue(tier)},
                "filter": ["==", ["get", "tier"], $tier],
                "layout": { ${marker(LabelImages.STATION, LabelImages.DOT_STATION)} }, $textPaint }"""
        }
        eras.filter { it.kind == "landmark" }.forEach {
            layers += """{ "id": "era-${it.id}", "type": "symbol", "source": "eras", "minzoom": ${tierZoom.getValue(3)},
                "filter": ${byId(it.id)},
                "layout": { ${marker(LabelImages.LANDMARK, LabelImages.DOT_LANDMARK)} },
                "paint": { "text-color": "$LABEL", "text-halo-color": "$HALO", "text-halo-width": 1.5, "text-opacity": 0, "icon-opacity": 0 } }"""
        }
        eras.filter { it.kind == "region-label" }.forEach {
            layers += """{ "id": "era-${it.id}", "type": "symbol", "source": "eras", "minzoom": 5, "maxzoom": 10,
                "filter": ${byId(it.id)}, "layout": { ${text(LabelImages.TEXT_SP)} },
                "paint": { "text-color": "$ROUTE", "text-halo-color": "$HALO", "text-halo-width": 1.5, "text-opacity": 0, "icon-opacity": 0 } }"""
        }
        eras.filter { it.kind == "place" }.forEach {
            layers += """{ "id": "era-${it.id}", "type": "symbol", "source": "eras", "maxzoom": $CITY_MAX,
                "filter": ${byId(it.id)}, "layout": { ${marker(LabelImages.CITY, LabelImages.DOT_CITY, LabelImages.CITY_SP)} },
                "paint": { "text-color": "$LABEL", "text-halo-color": "$HALO", "text-halo-width": 2, "text-opacity": 0, "icon-opacity": 0 } }"""
        }
        layers += """{ "id": "label-test", "type": "symbol", "source": "label-test",
            "layout": { "visibility": "${if (showLabelTest) "visible" else "none"}", ${text(LabelImages.TEST_SP)},
              "text-allow-overlap": true, "icon-allow-overlap": true }, $textPaint }"""

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
            "label-test": { "type": "geojson", "data": "asset://label-test.geojson" },
            "eras": { "type": "geojson", "data": "asset://time-states.geojson" }
          },
          "layers": [
            $baseLayers
            ${layers.joinToString(",\n")}
          ]
        }
        """.trimIndent()
    }
}
