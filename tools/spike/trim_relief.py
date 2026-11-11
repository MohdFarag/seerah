#!/usr/bin/env python3
"""Keep the finest relief zoom only near the route, to bound the file size.

    python3 tools/spike/trim_relief.py RELIEF.mbtiles ROUTE.geojson ZOOM KM

Deletes tiles at ZOOM whose centre is farther than KM kilometres from every
line in ROUTE.geojson. Coarser zooms are kept everywhere.
"""

import json
import math
import sqlite3
import sys

R = 6378137.0


def merc(lon: float, lat: float) -> tuple[float, float]:
    return math.radians(lon) * R, math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)) * R


def seg_dist(p, a, b) -> float:
    (px, py), (ax, ay), (bx, by) = p, a, b
    dx, dy = bx - ax, by - ay
    t = (
        0.0
        if dx == dy == 0
        else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    )
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def main() -> int:
    db, route_path, zoom, km = sys.argv[1], sys.argv[2], int(sys.argv[3]), float(sys.argv[4])
    lines = []
    for f in json.load(open(route_path, encoding="utf-8"))["features"]:
        pts = [merc(*c) for c in f["geometry"]["coordinates"]]
        lines += list(zip(pts, pts[1:]))
    # Mercator stretches distances by 1/cos(lat); at ~23° that is about 1.09.
    limit = km * 1000 * 1.09
    n = 2**zoom
    tile = 2 * math.pi * R / n
    con = sqlite3.connect(db)
    rows = con.execute(
        "select tile_column, tile_row from tiles where zoom_level = ?", (zoom,)
    ).fetchall()
    drop = []
    for x, y_tms in rows:
        cx = -math.pi * R + (x + 0.5) * tile
        cy = -math.pi * R + (y_tms + 0.5) * tile
        if min(seg_dist((cx, cy), a, b) for a, b in lines) > limit:
            drop.append((zoom, x, y_tms))
    con.executemany(
        "delete from tiles where zoom_level = ? and tile_column = ? and tile_row = ?", drop
    )
    con.commit()
    con.execute("vacuum")
    print(f"zoom {zoom}: kept {len(rows) - len(drop)} of {len(rows)} tiles")
    return 0


if __name__ == "__main__":
    sys.exit(main())
