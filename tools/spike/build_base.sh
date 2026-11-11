#!/usr/bin/env bash
# Build the spike base map: simplified world land + shaded relief for the sample area.
#
# Needs: curl, GDAL, tippecanoe, and Python with Pillow, numpy and `pmtiles`.
# Output: map-data/dist/land.pmtiles and map-data/dist/relief.pmtiles (not committed).
# Set DEM_RES=90 for a smaller, coarser relief.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${SEERAH_SRC_DIR:-$ROOT/map-data/.cache}"
OUT="$ROOT/map-data/dist"
mkdir -p "$SRC" "$OUT"

fetch() { [ -s "$2" ] || curl -fsSL --retry 3 -o "$2" "$1"; }

# 1. Land: Natural Earth (public domain), three scales.
NE=https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson
for f in ne_110m_land ne_50m_land ne_10m_land; do fetch "$NE/$f.geojson" "$SRC/$f.geojson"; done
# The largest land polygon (Africa, Europe and Asia in one piece) is dropped from
# some tiles when tiled whole, so the two finer scales are cut into 10-degree cells.
split() {
  local name="$1" gpkg="$SRC/$1.gpkg" cells="$SRC/$1_cells.gpkg"
  [ -s "$SRC/$name.cells.geojson" ] && return
  rm -f "$gpkg" "$cells"
  ogr2ogr -q -f GPKG -makevalid -nln land "$gpkg" "$SRC/$name.geojson"
  for lon in $(seq -180 10 170); do
    for lat in $(seq -90 10 80); do
      ogr2ogr -q -f GPKG -append -nln land -nlt MULTIPOLYGON -makevalid \
        -clipsrc "$lon" "$lat" "$((lon + 10))" "$((lat + 10))" "$cells" "$gpkg" land 2>/dev/null || true
    done
  done
  ogr2ogr -q -f GeoJSON "$SRC/$name.cells.geojson" "$cells" land
}
split ne_50m_land
split ne_10m_land
# One tileset per scale, joined afterwards. Putting the three scales in one file
# with per-feature zoom ranges made tippecanoe drop polygons from some tiles.
T="--no-tile-size-limit --no-feature-limit --no-tiny-polygon-reduction"
rm -f "$SRC"/land_*.pmtiles "$OUT/land.pmtiles"
# shellcheck disable=SC2086
tippecanoe -q -o "$SRC/land_a.pmtiles" -l land -Z0 -z2 $T "$SRC/ne_110m_land.geojson"
# shellcheck disable=SC2086
tippecanoe -q -o "$SRC/land_b.pmtiles" -l land -Z3 -z5 $T "$SRC/ne_50m_land.cells.geojson"
# shellcheck disable=SC2086
tippecanoe -q -o "$SRC/land_c.pmtiles" -l land -Z6 -z9 $T "$SRC/ne_10m_land.cells.geojson"
tile-join -q --no-tile-size-limit -o "$OUT/land.pmtiles" "$SRC/land_a.pmtiles" "$SRC/land_b.pmtiles" "$SRC/land_c.pmtiles"

# 2. Relief: Copernicus DEM for the sample area (lat 21-25, lon 38-40).
# DEM_RES=30 (default) uses GLO-30 and tiles to zoom 12; DEM_RES=90 uses GLO-90 and zoom 11.
DEM_RES="${DEM_RES:-30}"
if [ "$DEM_RES" = 30 ]; then ARC=10; PIXEL=38.2185; else ARC=30; PIXEL=76.437; fi
DEM="https://copernicus-dem-${DEM_RES}m.s3.amazonaws.com"
tiles=()
for lat in 21 22 23 24; do
  for lon in 038 039; do
    n="Copernicus_DSM_COG_${ARC}_N${lat}_00_E${lon}_00_DEM"
    fetch "$DEM/$n/$n.tif" "$SRC/$n.tif"
    tiles+=("$SRC/$n.tif")
  done
done
gdalbuildvrt -q -overwrite "$SRC/dem.vrt" "${tiles[@]}"
gdalwarp -q -overwrite -t_srs EPSG:3857 -r bilinear -tr "$PIXEL" "$PIXEL" "$SRC/dem.vrt" "$SRC/dem3857.tif"
gdaldem hillshade -q -multidirectional -z 4 "$SRC/dem3857.tif" "$SRC/hillshade.tif"
# GDAL's Python bindings are not assumed, so the colouring step goes through PNG.
gdal_translate -q -of PNG "$SRC/hillshade.tif" "$SRC/hillshade.png"
gdal_translate -q -of PNG -ot Byte -scale 0 1 0 255 "$SRC/dem3857.tif" "$SRC/landmask.png"
python3 "$ROOT/tools/spike/shade_to_rgba.py" "$SRC/hillshade.png" "$SRC/landmask.png" "$SRC/relief.png"
cp "$SRC/hillshade.png.aux.xml" "$SRC/relief.png.aux.xml"
rm -f "$SRC/relief.mbtiles" "$OUT/relief.pmtiles"
gdal_translate -q -of MBTILES -co TILE_FORMAT=WEBP -co QUALITY=80 "$SRC/relief.png" "$SRC/relief.mbtiles"
gdaladdo -q -r average "$SRC/relief.mbtiles" 2 4 8 16 32 64
pmtiles-convert "$SRC/relief.mbtiles" "$OUT/relief.pmtiles"

ls -la "$OUT"
