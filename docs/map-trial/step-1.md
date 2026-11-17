# Map trial: step 1 checks

Results of the specification's step-1 checks ([docs/design/map-trial-build.md](../design/map-trial-build.md), "Step 1 checks").

## Check 1: `adb push` into the benchmark app's external files folder

Not yet run.

## Check 2: MapLibre honours a raster source's `maxzoom` on `pmtiles://`

Not yet run.

## Check 3: `color-relief` draws option (c)'s band edges sharp

Not yet run.

## Check 4: `addOnDidFinishRenderingFrameListener` reports every frame

Not yet run.

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

Not yet run.

## Check 10: Licensee accepts MapLibre Native and its dependencies

Run on 2026-11-17 with MapLibre Native Android 13.7.1 (`org.maplibre.gl:android-sdk-opengl`, the newest non-pre-release 13.x at least two weeks old), through `./gradlew check` on `:core:map`. Licensee passed with the existing allow list and no new exception. The MapLibre artifacts and their licences as Licensee read them:

- `org.maplibre.gl:android-sdk-opengl` 13.7.1 and `org.maplibre.gl:maplibre-android-gestures` 0.0.4: BSD-2-Clause.
- `org.maplibre.gl:android-sdk-geojson` 6.0.1, `org.maplibre.gl:android-sdk-turf` 6.0.1, `com.squareup.okhttp3:okhttp` 4.12.0 and `com.jakewharton.timber:timber` 5.0.1: Apache-2.0.

## Check 11: The three devices' screen widths in dp

Not yet run.

## Check 12: The per-worker memory allowance for 2,048-pixel blocks

Not yet run.
