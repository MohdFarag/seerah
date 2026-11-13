# Third-party notices

The project's code and documentation are Apache-2.0 ([LICENSE](LICENSE)) and its content is all rights reserved ([content licence](LICENSES/LicenseRef-Seerah-Content.txt)); [REUSE.toml](REUSE.toml) says which applies to each file. The third-party material below keeps its own licence. This list covers what is **in the repository** or **downloaded by its build scripts**; the app's library dependencies are checked at build time by Licensee (allowed: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause; see [ADR 0004](docs/adr/0004-build-and-quality-tooling.md)) and will be listed in the app's open-source notices screen.

## In the repository

| Material | Where | Licence | Copyright |
| --- | --- | --- | --- |
| Amiri font | `map-data/style/fonts/Amiri-*.ttf`, glyphs in `map-data/style/glyphs/Amiri-Regular/` | SIL Open Font License 1.1 ([text](map-data/style/fonts/OFL-amiri.txt)) | The Amiri Project Authors |
| IBM Plex Sans Arabic font | `map-data/style/fonts/IBMPlexSansArabic-*.ttf`, glyphs in `map-data/style/glyphs/PlexArabic-Regular/` | SIL Open Font License 1.1 ([text](map-data/style/fonts/OFL-ibmplexsansarabic.txt)), Reserved Font Name "Plex" | IBM Corp. |
| Gradle wrapper | `android/gradle/wrapper/`, `android/spike/gradle/wrapper/`, `gradlew*` | Apache-2.0 | Gradle Inc. |

## Downloaded by build scripts (not committed)

| Material | Used by | Licence and attribution |
| --- | --- | --- |
| Natural Earth land and coastlines | `tools/spike/build_base.sh` | Public domain |
| Copernicus DEM GLO-30 | `tools/spike/build_base.sh` | Free licence with attribution. For derived data such as relief: "Produced using Copernicus WorldDEM-30 © DLR e.V." Confirm the wording against the current [licence](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf) before any release |

## Used by the technical spike only

| Material | Licence |
| --- | --- |
| MapLibre Native Android | BSD-2-Clause |

The Quran, hadith and narrations from classical works are not claimed as the project's own; see the [content licence](LICENSES/LicenseRef-Seerah-Content.txt).
