#!/usr/bin/env python3
"""Build a spike content update: a zip of the map content files plus a manifest.

    python3 tools/spike/make_update.py VERSION

Writes map-data/updates/content-VERSION.zip and map-data/updates/manifest.json.
The app downloads the manifest, then the zip (resuming if interrupted),
checks the SHA-256 and switches to the new files in one step.
"""

import hashlib
import json
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "map-data/historical/spike"
OUT = ROOT / "map-data/updates"
FILES = ["hijra-waypoints.geojson", "hijra-route.geojson", "time-states.geojson"]
BASE_URL = "https://raw.githubusercontent.com/MohdFarag/seerah/claude/relaxed-knuth-gi0alj/map-data/updates"

# What the spike update changes, so it is visible on the map after installing:
# Quba Mosque appears from 1 AH. Sample only, not reviewed content.
QUBA = {
    "type": "Feature",
    "id": "masjid-quba",
    "properties": {
        "id": "masjid-quba",
        "kind": "landmark",
        "name": "مسجد قباء",
        "from": 1,
        "to": 99,
        "review": "draft",
        "note": "Added by the spike content update to show that an update arrived.",
    },
    "geometry": {"type": "Point", "coordinates": [39.6173, 24.4393]},
}


def main() -> int:
    version = int(sys.argv[1])
    zip_path = OUT / f"content-{version}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for name in FILES:
            data = json.loads((SRC / name).read_text(encoding="utf-8"))
            if name == "time-states.geojson":
                data["features"].append(QUBA)
            z.writestr(name, json.dumps(data, ensure_ascii=False))
        z.writestr("version.txt", str(version))
    blob = zip_path.read_bytes()
    manifest = {
        "version": version,
        "url": f"{BASE_URL}/{zip_path.name}",
        "sha256": hashlib.sha256(blob).hexdigest(),
        "size": len(blob),
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(manifest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
