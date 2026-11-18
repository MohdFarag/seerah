# Map trial: step 1 checks

Results of the specification's step-1 checks ([docs/design/map-trial-build.md](../design/map-trial-build.md), "Step 1 checks").

## Check 1: `adb push` into the benchmark app's external files folder

Run on 2026-11-18 on the Galaxy A54 only (SM-A546E, Android 16). The Oppo A55 and the MatePad 11.5 are not yet run, so the check is not complete.

Result on the A54: it works. The synthetic example's painted variant was pushed to `/sdcard/Android/data/app.seerah.benchmark/files/trial/example-painted-d891878a/` (`relief.pmtiles`, `metadata.json`, `report.json`, `camera-path.json`), the app listed it under "Views", and all three painted views opened from it. `relief.pmtiles` is 32,928 bytes, so this says nothing about the 2 GiB limit.

## Check 2: MapLibre honours a raster source's `maxzoom` on `pmtiles://`

Not yet run. The A54 run used the example's camera path, which has one stop at MapLibre zoom 10, below the variant's `maxzoom` of 12 (shown from MapLibre zoom 11), so it does not reach the case. It needs a path or a manual zoom past MapLibre zoom 11 on a variant with `maxzoom` 12.

## Check 3: `color-relief` draws option (c)'s band edges sharp

Not yet run. The A54 run covered the three painted views (zoom 12, zoom 11, option (a)). No value variant was built or installed, so option (c) was not shown.

## Check 4: `addOnDidFinishRenderingFrameListener` reports every frame

Run on 2026-11-18 on the Galaxy A54 only. Indirect result: consistent, not proven. With the panel at its 120 Hz mode (`renderFrameRate` 120.00001, read with `adb shell dumpsys display`), the overlay's typical rate was 119.8 to 120.7 fps in all nine runs and the lowest published rate was 118.6 fps. A listener that dropped frames would read below the refresh rate while the map moved. Counting the frames against an independent source, such as `dumpsys gfxinfo` over the same path, was not done. Not yet run on the other two devices.

## Check 5: GDAL writes each tile atomically

Run on 2026-11-16, with GDAL 3.13.3 from `seerah-map:dev`, on a synthetic 512 × 512 RGBA VRT (two by two zoom-12 tiles). `strace` was installed only in a throwaway container, never in the image:

```sh
docker run --rm --cap-add SYS_PTRACE -v "$PWD:/src:ro" seerah-map:dev bash -c \
  "apt-get update && apt-get install -y strace && \
   strace -f -e trace=rename,renameat,renameat2,openat -o /tmp/trace.txt \
   gdal raster tile --webviewer none -f PNG -j 2 <vrt> /tmp/t"
```

Result: no. Each tile is opened for writing under its final name (`openat("/tmp/t/12/2048/1536.png", O_WRONLY|O_CREAT|O_TRUNC)`), so an interrupted run can leave a half-written tile. With PNG the only renames are nine zoom-12 calls whose source and target are the same path (`rename("…/1536.png", "…/1536.png")`), so there is no temporary name. With `-f WEBP --co QUALITY=80` there are no renames at all. The tile stage therefore deletes a `tiles/` folder that has no `tiles.done` and tiles from scratch, and it does not use `--resume`.

Also seen: when the data's edge sits exactly on a tile boundary, `gdal raster tile` writes one extra, fully transparent tile past the east and south edges at zoom 12 (here `12/2050/*` and `12/*/1538`). They are harmless and are not in `grid.expected_tiles`, so the completeness check ignores them.

## Check 6: Average resampling keeps the ink colour under semi-transparent pixels

Run on 2026-11-16, with GDAL 3.13.3, on a synthetic 512 × 512 RGBA VRT: constant ink RGB 150, 118, 72 and alpha rising from 0 to 153 across the width. Tiled with `--resampling average --overview-resampling average` at zooms 0 to 12.

- **PNG** (`-f PNG`), zoom-11 edge tile `11/1024/768`: 65,024 pixels have alpha above 0 (alpha range 0 to 153). Largest RGB difference from the ink there: **0**. Averaging keeps the ink exactly.
- **WebP** (`-f WEBP --co QUALITY=80`), same tile, for information: largest RGB difference **18**. This is WebP's lossy compression, not the resampling.

## Check 7: Water Body Mask class values

Run on 2026-11-16, on cell N20 E058 (`Copernicus_DSM_COG_10_N20_00_E058_00_WBM.tif`):

```sh
docker run --rm --security-opt label=disable seerah-map:dev gdalinfo -hist \
  /vsicurl/https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N20_00_E058_00_DEM/AUXFILES/Copernicus_DSM_COG_10_N20_00_E058_00_WBM.tif
```

Result: 3600 × 3600 pixels, Byte. Classes found and their pixel counts:

| Class | Meaning | Pixels |
| --- | --- | --- |
| 0 | no water | 5,542,515 |
| 1 | ocean | 7,416,129 |
| 2 | lake | 1,356 |

No other value appears (class 3, river, is absent from this cell). The expected legend holds, so the mask rule stands: land is every class except 1.

## Check 8: Docker named volumes handle about 100,000 small files

Not yet run.

## Check 9: The GHCR package is public and free to pull

Run on 2026-11-16, after the owner made `ghcr.io/mohdfarag/seerah-map` public: with no `docker login`, `docker manifest inspect ghcr.io/mohdfarag/seerah-map:8cd01906e238e68b` succeeded, and the package page shows "Public". A full anonymous `docker pull` was not run, and GHCR's pricing was not checked.

## Check 10: Licensee accepts MapLibre Native and its dependencies

Run on 2026-11-17 with MapLibre Native Android 13.7.1 (`org.maplibre.gl:android-sdk-opengl`, the newest non-pre-release 13.x at least two weeks old), through `./gradlew check` on `:core:map`. Licensee passed with the existing allow list and no new exception. The MapLibre artifacts and their licences as Licensee read them:

- `org.maplibre.gl:android-sdk-opengl` 13.7.1 and `org.maplibre.gl:maplibre-android-gestures` 0.0.4: BSD-2-Clause.
- `org.maplibre.gl:android-sdk-geojson` 6.0.1, `org.maplibre.gl:android-sdk-turf` 6.0.1, `com.squareup.okhttp3:okhttp` 4.12.0 and `com.jakewharton.timber:timber` 5.0.1: Apache-2.0.

## Check 11: The three devices' screen widths in dp

Run on 2026-11-18 on the Galaxy A54 only, with `adb shell wm size` and `adb shell wm density`: 1080 × 2340 px at density 450 (no override), so the width in portrait is 1080 ÷ (450 ÷ 160) = **384 dp** (the specification's estimate was about 385 dp), and 832 dp in landscape. The Oppo A55 and the MatePad 11.5 are not yet run.

## Check 12: The per-worker memory allowance for 2,048-pixel blocks

Not yet run.

## Galaxy A54 run, 2026-11-18

Device: Samsung SM-A546E, Android 16 (API 36), 120 Hz panel. App: `app.seerah.benchmark` 0.1.0, installed from the CI artifact `seerah-app-benchmark` beside the normal app. Data: the synthetic example's painted variant `example-painted-d891878a` (Masirah window, `maxzoom` 12). This is a mechanics check of phase 2: there is no base layer yet, so the screenshots show relief on a plain land colour, and nothing here judges the look or answers trial item 11 for the Oppo A55 (the device that decides it).

The camera path (one stop at MapLibre zoom 10, 4 s) ran three times per view. No reading carries the warm-process marker, so each open time comes from a fresh process:

| View | Open time | Memory (PSS) | Run 1 (cold), typical / lowest | Run 2 | Run 3 | All runs, typical / lowest |
| --- | --- | --- | --- | --- | --- | --- |
| zoom 12 | 369 ms | 117 MB | 120.2 / 118.6 fps | 120.0 / 120.0 | 120.0 / 119.5 | 120.0 / 118.6 fps |
| zoom 11 | 326 ms | 116 MB | 120.2 / 120.0 fps | 120.5 / 119.5 | 120.7 / 120.0 | 120.5 / 119.5 fps |
| option (a) | 311 ms | 117 MB | 120.0 / 119.0 fps | 120.5 / 120.0 | 119.8 / 119.5 | 120.0 / 119.0 fps |

Observations:

- **Against trial item 11's rules for the devices other than the Oppo A55** (open time at most 1.5 s, typical rate equal to the screen's rate, lowest at least 45 fps, memory at most 200 MB): the three painted views meet all four on the A54 (open time 311 to 369 ms, typical 119.8 to 120.7 fps on a 120 Hz panel, lowest 118.6 fps or higher, memory 116 to 139 MB). Option (c) and the per-zoom view were not run, and the A54 is not the device that decides the trial.
- **Cancelling a path** ([screenshot](step-1-results/a54/a54-cancel-path.png)): a path was cancelled during a run on the zoom 11 view and the overlay showed "Path stopped", with "Copy results" disabled and no error. How it was cancelled is not recorded. This is the cancellation case that `SeerahMap` has no automated test for.
- **Warm-process marker:** in the same screenshot the open time reads "311 ms (from warm process)", the value from the option (a) launch carried into a view opened in the same process, as designed.
- **Memory:** 116 to 117 MB after a path; 139 MB in the cancelled-path screenshot.

Evidence (screenshots before and after each path, and the copied results text): [step-1-results/a54/](step-1-results/a54/).
