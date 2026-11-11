#!/usr/bin/env python3
"""Merge three Natural Earth land scales into one GeoJSON with per-feature zoom ranges.

    python3 tools/spike/merge_land.py SRC_DIR OUT.geojson

tippecanoe reads the `tippecanoe` member of each feature, so the coarse outline
serves the world view and the detailed one appears only when zoomed in.
"""

import json
import pathlib
import sys

SCALES = [("ne_110m_land", 0, 2), ("ne_50m_land", 3, 5), ("ne_10m_land", 6, 9)]


def main() -> int:
    src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    features = []
    for name, zmin, zmax in SCALES:
        data = json.loads((src / f"{name}.geojson").read_text(encoding="utf-8"))
        for f in data["features"]:
            features.append(
                {
                    "type": "Feature",
                    "properties": {},
                    "tippecanoe": {"minzoom": zmin, "maxzoom": zmax},
                    "geometry": f["geometry"],
                }
            )
    out.write_text(
        json.dumps({"type": "FeatureCollection", "features": features}), encoding="utf-8"
    )
    print(f"{len(features)} land features")
    return 0


if __name__ == "__main__":
    sys.exit(main())
