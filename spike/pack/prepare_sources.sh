#!/usr/bin/env bash
# Fetch third-party sources for the spike pack into .cache/ (not committed).
#   - world-atlas (Natural Earth land, public domain) -> GeoJSON
#   - Noto Naskh Arabic + Noto Sans TTF (SIL OFL 1.1)
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p .cache && cd .cache
[ -f package.json ] || echo '{"name":"spike-pack-sources","private":true}' > package.json
npm i --no-audit --no-fund world-atlas@2.0.2 topojson-client@3.1.0 \
  @expo-google-fonts/noto-naskh-arabic@0.4.5 @expo-google-fonts/noto-sans@0.4.2
for r in 50m 10m; do
  node -e "
    const t=require('./node_modules/world-atlas/land-$r.json');
    const {feature}=require('topojson-client');
    process.stdout.write(JSON.stringify(feature(t,t.objects.land)));
  " > land-$r.geojson
done
cp node_modules/@expo-google-fonts/noto-naskh-arabic/400Regular/NotoNaskhArabic_400Regular.ttf .
cp node_modules/@expo-google-fonts/noto-sans/400Regular/NotoSans_400Regular.ttf .
echo "sources ready in $(pwd)"
