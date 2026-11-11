#!/usr/bin/env bash
# Build the spike base map: simplified world land + shaded relief for the sample area.
#
# Needs: curl, GDAL, tippecanoe, and Python with Pillow, numpy and `pmtiles`.
# Output: map-data/dist/land.pmtiles and map-data/dist/relief.pmtiles (not committed).
# RELIEF_ZOOM (default 14) sets the finest relief zoom, kept within
# RELIEF_CORRIDOR_KM (default 12) of the route. DEM_RES=90 uses coarser source data.
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
# DEM_RES=30 (default) uses GLO-30, DEM_RES=90 uses GLO-90.
DEM_RES="${DEM_RES:-30}"
if [ "$DEM_RES" = 30 ]; then ARC=10; else ARC=30; fi
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
# The DEM is upsampled smoothly to the pixel size of RELIEF_ZOOM, so close-up
# shading is smooth instead of blocky. It adds no detail beyond the source data.
RELIEF_ZOOM="${RELIEF_ZOOM:-14}"
PIXEL=$(python3 -c "print(40075016.68557849 / 256 / 2 ** $RELIEF_ZOOM)")
rm -f "$SRC"/dem3857.tif "$SRC"/hillshade.* "$SRC"/landmask.* "$SRC"/relief.raw "$SRC"/relief.vrt
gdalwarp -q -overwrite -t_srs EPSG:3857 -r cubicspline -tr "$PIXEL" "$PIXEL" -multi -wo NUM_THREADS=ALL_CPUS \
  -co COMPRESS=DEFLATE -co TILED=YES -co BIGTIFF=YES "$SRC/dem.vrt" "$SRC/dem3857.tif"
gdaldem hillshade -q -multidirectional -z 4 -of ENVI "$SRC/dem3857.tif" "$SRC/hillshade.envi"
gdal_translate -q -of ENVI -ot Byte -scale 0 1 0 255 "$SRC/dem3857.tif" "$SRC/landmask.envi"
python3 "$ROOT/tools/spike/shade_to_rgba.py" "$SRC/hillshade.envi" "$SRC/landmask.envi" "$SRC/relief.raw"
python3 - "$SRC" <<'PY'
import re, sys
src = sys.argv[1]
info = open(f"{src}/hillshade.hdr").read()
w = int(re.search(r"samples\s*=\s*(\d+)", info).group(1))
h = int(re.search(r"lines\s*=\s*(\d+)", info).group(1))
m = re.search(r"map info\s*=\s*\{[^,]*,[^,]*,[^,]*,\s*([-\d.e]+),\s*([-\d.e]+),\s*([-\d.e]+),\s*([-\d.e]+)", info)
x0, y0, px, py = (float(v) for v in m.groups())
bands = "".join(
    f'<VRTRasterBand dataType="Byte" band="{i + 1}" subClass="VRTRawRasterBand">'
    f'<ColorInterp>{c}</ColorInterp><SourceFilename relativeToVRT="1">relief.raw</SourceFilename>'
    f"<ImageOffset>{i}</ImageOffset><PixelOffset>4</PixelOffset><LineOffset>{w * 4}</LineOffset></VRTRasterBand>"
    for i, c in enumerate(["Red", "Green", "Blue", "Alpha"])
)
open(f"{src}/relief.vrt", "w").write(
    f'<VRTDataset rasterXSize="{w}" rasterYSize="{h}"><SRS>EPSG:3857</SRS>'
    f"<GeoTransform>{x0}, {px}, 0, {y0}, 0, {-py}</GeoTransform>{bands}</VRTDataset>"
)
PY
rm -f "$SRC/relief.mbtiles" "$OUT/relief.pmtiles"
gdal_translate -q -of MBTILES -co TILE_FORMAT=WEBP -co QUALITY=75 "$SRC/relief.vrt" "$SRC/relief.mbtiles"
gdaladdo -q -r average "$SRC/relief.mbtiles" 2 4 8 16 32 64 128 256
# The finest zoom is kept only within a corridor around the route and its places.
python3 "$ROOT/tools/spike/trim_relief.py" "$SRC/relief.mbtiles" \
  "$ROOT/map-data/historical/spike/hijra-route.geojson" "$RELIEF_ZOOM" "${RELIEF_CORRIDOR_KM:-12}"
python3 "$ROOT/tools/spike/trim_relief.py" "$SRC/relief.mbtiles" \
  "$ROOT/map-data/historical/spike/hijra-route.geojson" "$((RELIEF_ZOOM - 1))" "${RELIEF_WIDE_KM:-40}"
pmtiles-convert "$SRC/relief.mbtiles" "$OUT/relief.pmtiles"

ls -la "$OUT"
