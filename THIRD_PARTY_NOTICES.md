# Third-party notices

The project's code and documentation are Apache-2.0 ([LICENSE](LICENSE)) and its content is all rights reserved ([content licence](LICENSES/LicenseRef-Seerah-Content.txt)); [REUSE.toml](REUSE.toml) says which applies to each file. The third-party material below keeps its own licence. This list covers what is **in the repository**, **downloaded by its build scripts**, or part of the **map build toolchain** (`tools/map/`); the app's library dependencies are checked at build time by Licensee (allowed: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause; see [ADR 0004](docs/adr/0004-build-and-quality-tooling.md)) and will be listed in the app's open-source notices screen. None of the map build tools or MapLibre Native ships in the `release` app build yet.

## In the repository

| Material | Where | Licence | Copyright |
| --- | --- | --- | --- |
| Amiri font | `map-data/style/fonts/Amiri-*.ttf`, glyphs in `map-data/style/glyphs/Amiri-Regular/` | SIL Open Font License 1.1 ([text](map-data/style/fonts/OFL-amiri.txt)) | The Amiri Project Authors |
| IBM Plex Sans Arabic font | `map-data/style/fonts/IBMPlexSansArabic-*.ttf`, glyphs in `map-data/style/glyphs/PlexArabic-Regular/` | SIL Open Font License 1.1 ([text](map-data/style/fonts/OFL-ibmplexsansarabic.txt)), Reserved Font Name "Plex" | IBM Corp. |
| Gradle wrapper | `android/gradle/wrapper/`, `android/spike/gradle/wrapper/`, `gradlew*` | Apache-2.0 | Gradle Inc. |

## Downloaded by build scripts (not committed)

| Material | Used by | Licence and attribution |
| --- | --- | --- |
| Natural Earth land and coastlines | `tools/spike/build_base.sh`, `tools/map/` | Public domain |
| Copernicus DEM GLO-30 and Water Body Mask | `tools/spike/build_base.sh` (DEM), `tools/map/` (DEM and Water Body Mask; the valley probe also reads unpinned Copernicus DEM GLO-90 without shipping output) | Free licence with attribution ([licence](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf), verified on 2026-11-23 against `INFO/eula_F.pdf` in cell `Copernicus_DSM_COG_10_N20_00_E058_00_DEM`, SHA-256 `32049914c37f14e7d53b48d13d74a49e77c030236e2acc7a0df426f9344feba2`). Modified/adapted data notice (Article 6(b), embedded in `data/map.json` `credits` and PMTiles `attribution`): `"produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved."` Liability notice (Article 6(c), embedded in `data/map.json` `credits`): `"The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30."` |

## Map build tools (`tools/map/`)

Used in the containerised map build toolchain (`tools/map/`); none of these tools ships in any app build. Exact pinned Python package versions and transitive dependencies are recorded in [tools/map/requirements.txt](tools/map/requirements.txt).

| Tool | Licence |
| --- | --- |
| GDAL | MIT |
| `tippecanoe` (including `tile-join`) | BSD-2-Clause |
| `go-pmtiles` | BSD-3-Clause |
| Protomaps `pmtiles` | BSD-3-Clause |
| `numpy` | BSD-3-Clause |
| `scipy` | BSD-3-Clause |
| `morecantile` | MIT |
| `ruamel.yaml` | MIT |
| `jsonschema` | MIT |
| `pytest` (container test suite) | MIT |
| `WhiteboxTools` (`whitebox_tools`) | MIT |

## Map rendering (spike, `:core:map` and `benchmark`)

| Material | Used by | Licence |
| --- | --- | --- |
| MapLibre Native Android | Technical spike (`android/spike/`), `:core:map` (`android/core/map/`) and `benchmark` build type | BSD-2-Clause |

Neither the map build tools nor MapLibre Native ships in the `release` app build yet (`release` does not depend on `:core:map`). MapLibre Native moves to the shipped notices when `release` depends on `:core:map`.

The Quran, hadith and narrations from classical works are not claimed as the project's own; see the [content licence](LICENSES/LicenseRef-Seerah-Content.txt).
