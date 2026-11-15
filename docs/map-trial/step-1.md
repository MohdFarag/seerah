# Map trial: step 1 checks

Results of the specification's step-1 checks ([docs/design/map-trial-build.md](../design/map-trial-build.md), "Step 1 checks").

## Check 1: `adb push` into the benchmark app's external files folder

Not yet run.

## Check 2: MapLibre honours a raster source's `maxzoom` on `pmtiles://`

Not yet run.

## Check 3: `color-relief-color` accepts `step`

Not yet run.

## Check 4: `addOnDidFinishRenderingFrameListener` reports every frame

Not yet run.

## Check 5: GDAL writes each tile atomically

Not yet run.

## Check 6: Average resampling keeps the ink colour under semi-transparent pixels

Not yet run.

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

Not yet run.

## Check 11: The three devices' screen widths in dp

Not yet run.

## Check 12: The per-worker memory allowance for 2,048-pixel blocks

Not yet run.
