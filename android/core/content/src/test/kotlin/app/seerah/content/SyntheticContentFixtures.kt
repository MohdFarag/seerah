package app.seerah.content

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature

internal object SyntheticContentFixtures {
    fun generateEd25519Keypair(keyId: String): Pair<TrustedPublicKey, PrivateKey> {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val x509 = kp.public.encoded
        val rawPub = x509.copyOfRange(x509.size - 32, x509.size)
        return TrustedPublicKey(keyId, rawPub, sha256Hex(rawPub)) to kp.private
    }

    fun signEd25519(privateKey: PrivateKey, rawManifest: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(privateKey)
        update(rawManifest)
        sign()
    }

    fun richMasirahEntries(contentVersion: String = "1.0.0-synthetic"): LinkedHashMap<String, ByteArray> {
        val files = linkedMapOf(
            "data/areas.json" to """
                {
                  "areas": [
                    {
                      "id": "region-east",
                      "label_point": {"lat": 20.63, "lon": 58.92},
                      "off_map": false,
                      "polygon_ring": [
                        {"lat": 20.55, "lon": 58.85},
                        {"lat": 20.55, "lon": 58.98},
                        {"lat": 20.72, "lon": 58.98},
                        {"lat": 20.72, "lon": 58.85},
                        {"lat": 20.55, "lon": 58.85}
                      ],
                      "states": [
                        {"exists": true, "name": "East Coast", "start": "baseline"},
                        {
                          "exists": true,
                          "name": "East Anchorage",
                          "start": {"calendar": "hijri", "year": 2, "precision": "year"}
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            "data/events.json" to """
                {
                  "events": [
                    {
                      "date": {
                        "approximate": true,
                        "calendar": "pre-hijra",
                        "note": "Synthetic dispute note",
                        "precision": "year",
                        "prophethood_year": 12,
                        "year": 2
                      },
                      "depth": "deep",
                      "event_text_ids": ["text-editorial"],
                      "id": "event-root",
                      "kind": "call",
                      "lead_in": "Synthetic root lead-in.",
                      "order": 1,
                      "outcome": "Synthetic root outcome.",
                      "phase_id": "phase-one",
                      "prominence": 1,
                      "situations": [
                        {
                          "id": "sit-root",
                          "order": 1,
                          "place": {"accuracy": "exact", "place_id": "port-east"},
                          "text_ids": ["text-full"],
                          "title": "Root situation"
                        }
                      ],
                      "tags": ["synthetic"],
                      "title": "Synthetic Root Event",
                      "visibility": "always",
                      "where": {"accuracy": "exact", "kind": "single_place", "place_id": "port-east"}
                    },
                    {
                      "date": {
                        "calendar": "hijri",
                        "day": 17,
                        "month": 9,
                        "precision": "day",
                        "year": 2
                      },
                      "depth": "medium",
                      "expedition_role": "prophet-led",
                      "id": "event-child",
                      "kind": "expedition",
                      "order": 1,
                      "parent_id": "event-root",
                      "phase_id": "phase-two",
                      "prominence": 2,
                      "related": [{"target_event_id": "event-root", "type": "continues"}],
                      "situations": [
                        {
                          "id": "sit-child",
                          "order": 1,
                          "text_ids": ["text-excerpt"],
                          "title": "Child situation"
                        }
                      ],
                      "title": "Synthetic Child Event",
                      "visibility": "in-context",
                      "where": {"accuracy": "general", "kind": "route"}
                    }
                  ]
                }
            """.trimIndent(),
            "data/meta.json" to """
                {
                  "content_schema": 1,
                  "content_version": "$contentVersion",
                  "max_timeline_point": {"calendar": "hijri", "year": 2},
                  "min_timeline_point": {"calendar": "pre-hijra", "year": 2},
                  "phases": [
                    {"id": "phase-one", "order": 1, "skippable": false, "title": "Phase One"},
                    {"id": "phase-two", "order": 2, "skippable": true, "title": "Phase Two"}
                  ]
                }
            """.trimIndent(),
            "data/places.json" to """
                {
                  "places": [
                    {
                      "accuracy": "exact",
                      "coordinates": {"lat": 20.62, "lon": 58.91},
                      "id": "port-east",
                      "kind": "port",
                      "off_map": false,
                      "prominence": 1,
                      "states": [
                        {"exists": true, "name": "Old East Port", "start": "baseline"},
                        {
                          "exists": true,
                          "kind_override": "settlement",
                          "name": "New East Harbor",
                          "start": {"calendar": "hijri", "year": 1, "month": 1, "precision": "month"}
                        }
                      ],
                      "visibility": "always"
                    },
                    {
                      "accuracy": "general",
                      "coordinates": {"lat": 20.70, "lon": 58.88},
                      "id": "bay-north",
                      "kind": "port",
                      "off_map": false,
                      "prominence": 2,
                      "states": [
                        {"exists": true, "name": "North Bay", "start": "baseline"}
                      ],
                      "visibility": "in-context"
                    },
                    {
                      "accuracy": "unknown",
                      "id": "island-unknown",
                      "kind": "other",
                      "off_map": false,
                      "prominence": 3,
                      "states": [
                        {"exists": true, "name": "Unknown Spot", "start": "baseline"}
                      ],
                      "visibility": "in-context"
                    },
                    {
                      "accuracy": "general",
                      "id": "cape-offmap",
                      "kind": "other",
                      "off_map": true,
                      "prominence": 3,
                      "states": [
                        {"exists": true, "name": "Offmap Cape", "start": "baseline"}
                      ],
                      "visibility": "always"
                    }
                  ]
                }
            """.trimIndent(),
            "data/routes.json" to """
                {
                  "routes": [
                    {
                      "accuracy_note": "Synthetic route accuracy",
                      "coordinates": [
                        {"lat": 20.62, "lon": 58.91},
                        {"lat": 20.66, "lon": 58.90},
                        {"lat": 20.70, "lon": 58.88}
                      ],
                      "days": 2,
                      "distance_basis": "estimate",
                      "distance_km": 12.5,
                      "documented": true,
                      "event_id": "event-child",
                      "id": "route-east",
                      "station_waypoint_indices": [0, 2],
                      "stations": ["port-east", "bay-north"]
                    }
                  ]
                }
            """.trimIndent(),
            "data/sources.json" to """
                {
                  "sources": [
                    {"id": "source-a", "kind": "hadith", "url": "https://example.invalid/source-a", "work": "Hadith Work"},
                    {"edition": "ed-1", "id": "source-b", "kind": "seerah-book", "work": "Seerah Work"}
                  ]
                }
            """.trimIndent(),
            "data/stories.json" to """
                {
                  "stories": [
                    {
                      "chapters": [
                        {
                          "id": "chap-1",
                          "order": 1,
                          "phase_id": "phase-one",
                          "scenes": [
                            {
                              "event_id": "event-root",
                              "id": "scene-1",
                              "order": 1,
                              "steps": [
                                {
                                  "camera": {
                                    "mode": "frame",
                                    "shot": "wide",
                                    "targets": ["place:port-east", "event:event-root"],
                                    "transition": "fly"
                                  },
                                  "focus_mode": true,
                                  "highlight": ["area:region-east"],
                                  "id": "step-1",
                                  "order": 1,
                                  "panel": {
                                    "explore_here": false,
                                    "linking_sentence": "Synthetic transition.",
                                    "text_ids": ["text-editorial"],
                                    "title_card": true
                                  },
                                  "time": {"calendar": "pre-hijra", "mode": "at", "precision": "year", "year": 2}
                                },
                                {
                                  "camera": {
                                    "from_station_index": 0,
                                    "mode": "follow",
                                    "route_id": "route-east",
                                    "shot": "medium",
                                    "to_station_index": 1,
                                    "transition": "pan"
                                  },
                                  "draw_route": {"from_station_index": 0, "route_id": "route-east", "to_station_index": 1},
                                  "id": "step-2",
                                  "order": 2,
                                  "panel": {"explore_here": true, "text_ids": ["text-excerpt"], "title_card": false},
                                  "reveal": ["place:bay-north", "event:event-child", "route:route-east"],
                                  "show_area": "region-east",
                                  "show_movement": {"from_place_id": "port-east", "route_id": "route-east", "to_place_id": "bay-north"},
                                  "time": {
                                    "from": {"calendar": "hijri", "precision": "year", "year": 1},
                                    "mode": "time_lapse",
                                    "to": {"calendar": "hijri", "month": 9, "precision": "month", "year": 2}
                                  }
                                }
                              ],
                              "title": "Scene One"
                            }
                          ],
                          "title": "Chapter One"
                        }
                      ],
                      "id": "story-main",
                      "title": "Synthetic Story"
                    }
                  ]
                }
            """.trimIndent(),
            "data/texts.json" to """
                {
                  "texts": [
                    {
                      "body": "Synthetic full narration.",
                      "event_id": "event-root",
                      "id": "text-full",
                      "kind": "seerah-narration",
                      "source": {"locator": "1/10", "source_work_id": "source-b"}
                    },
                    {
                      "body": "Synthetic editorial note.",
                      "event_id": "event-root",
                      "id": "text-editorial",
                      "kind": "editorial",
                      "source": null
                    },
                    {
                      "body": "Synthetic excerpt narration.",
                      "event_id": "event-child",
                      "excerpt_of": "text-full",
                      "explanation": "Synthetic explanation.",
                      "explanation_source": {"locator": "5", "source_work_id": "source-a"},
                      "id": "text-excerpt",
                      "kind": "seerah-narration",
                      "source": {"locator": "1/10", "source_work_id": "source-b", "url": "https://example.invalid/t"}
                    }
                  ]
                }
            """.trimIndent(),
        )
        return LinkedHashMap<String, ByteArray>().apply {
            for ((path, json) in files) {
                put(path, json.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    fun buildSignedContentPackage(
        outDir: File,
        fileName: String,
        version: Int,
        minAppVersion: Int,
        keyId: String,
        privateKey: PrivateKey,
        entriesMap: Map<String, ByteArray> = richMasirahEntries("1.0.$version-synthetic"),
    ): File {
        val sortedEntries = entriesMap.entries.sortedBy { it.key }
        val commit40 = "a".repeat(40)
        val filesJson = sortedEntries.joinToString(",") { (path, bytes) ->
            """{"path":"$path","size":${bytes.size},"sha256":"${sha256Hex(bytes)}"}"""
        }
        val rawManifest = """
            {
              "kind":"content",
              "version":$version,
              "min_app_version":$minAppVersion,
              "key_id":"$keyId",
              "source_commit":"$commit40",
              "created":"2026-11-28T00:00:00Z",
              "files":[$filesJson]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val rawSig = signEd25519(privateKey, rawManifest)

        val zipSpecs = listOf(
            TestArchiveBuilders.RawZipSpecEntry("manifest.json".toByteArray(), rawManifest),
            TestArchiveBuilders.RawZipSpecEntry("manifest.sig".toByteArray(), rawSig),
        ) + sortedEntries.map { (path, bytes) ->
            TestArchiveBuilders.RawZipSpecEntry(
                nameBytes = path.toByteArray(StandardCharsets.US_ASCII),
                payloadBytes = bytes,
                method = 8,
            )
        }
        return TestArchiveBuilders.writeTempBytes(outDir, fileName, TestArchiveBuilders.buildRawZip(zipSpecs))
    }
}
