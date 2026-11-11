#!/usr/bin/env bash
# Build the spike base map: simplified world land + shaded relief for the sample area.
#
# Needs: curl, GDAL, tippecanoe, and Python with Pillow, numpy and `pmtiles`.
# Output: map-data/dist/land.pmtiles and map-data/dist/relief.pmtiles (not committed).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${SEERAH_SRC_DIR:-$ROOT/map-data/.cache}"
OUT="$ROOT/map-data/dist"
mkdir -p "$SRC" "$OUT"

fetch() { [ -s "$2" ] || curl -fsSL --retry 3 -o "$2" "$1"; }

# 1. Land: Natural Earth (public domain), three scales merged into one layer.
NE=https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson
for f in ne_110m_land ne_50m_land ne_10m_land; do fetch "$NE/$f.geojson" "$SRC/$f.geojson"; done
python3 "$ROOT/tools/spike/merge_land.py" "$SRC" "$SRC/land.geojson"
rm -f "$OUT/land.pmtiles"
tippecanoe -q -o "$OUT/land.pmtiles" -l land -Z0 -z9 \
  --detect-shared-borders --no-tile-size-limit "$SRC/land.geojson"

# 2. Relief: Copernicus DEM GLO-90 for the sample area (lat 21-25, lon 38-40).
DEM=https://copernicus-dem-90m.s3.amazonaws.com
tiles=()
for lat in 21 22 23 24; do
  for lon in 038 039; do
    n="Copernicus_DSM_COG_30_N${lat}_00_E${lon}_00_DEM"
    fetch "$DEM/$n/$n.tif" "$SRC/$n.tif"
    tiles+=("$SRC/$n.tif")
  done
done
gdalbuildvrt -q -overwrite "$SRC/dem.vrt" "${tiles[@]}"
gdalwarp -q -overwrite -t_srs EPSG:3857 -r bilinear -tr 76.437 76.437 "$SRC/dem.vrt" "$SRC/dem3857.tif"
gdaldem hillshade -q -multidirectional -z 4 "$SRC/dem3857.tif" "$SRC/hillshade.tif"
# GDAL's Python bindings are not assumed, so the colouring step goes through PNG.
gdal_translate -q -of PNG "$SRC/hillshade.tif" "$SRC/hillshade.png"
gdal_translate -q -of PNG -ot Byte -scale 0 1 0 255 "$SRC/dem3857.tif" "$SRC/landmask.png"
python3 "$ROOT/tools/spike/shade_to_rgba.py" "$SRC/hillshade.png" "$SRC/landmask.png" "$SRC/relief.png"
cp "$SRC/hillshade.png.aux.xml" "$SRC/relief.png.aux.xml"
rm -f "$SRC/relief.mbtiles" "$OUT/relief.pmtiles"
gdal_translate -q -of MBTILES -co TILE_FORMAT=WEBP -co QUALITY=80 "$SRC/relief.png" "$SRC/relief.mbtiles"
gdaladdo -q -r average "$SRC/relief.mbtiles" 2 4 8 16 32
pmtiles-convert "$SRC/relief.mbtiles" "$OUT/relief.pmtiles"

ls -la "$OUT"
