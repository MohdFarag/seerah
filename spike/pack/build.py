#!/usr/bin/env python3
"""Build the spike's offline content packages (v1 and v2) from src/ and .cache/.

Output (dist/, not committed):
  dist/pack-<version>/            unpacked package
  dist/pack-<version>.zip         what the app downloads or bundles
  dist/assets/seed-pack.zip       v1, bundled into the APK as the first-run package
  dist/size-report.txt            size per component

Run ./prepare_sources.sh first. Requires: pip install -r requirements.txt
"""

from __future__ import annotations

import gzip
import hashlib
import json
import math
import shutil
import sys
import zipfile
from pathlib import Path

import freetype
import mapbox_vector_tile
import numpy as np
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import Writer
from scipy.ndimage import distance_transform_edt
from shapely import box, make_valid, transform
from shapely.affinity import translate as affine_translate
from shapely.geometry import Polygon
from shapely.ops import unary_union
from shapely.strtree import STRtree

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".cache"
DIST = HERE / "dist"

# Detail coverage for the spike: Arabia, the Levant, Egypt, Abyssinia, Iraq.
# The whole world is only at overview zooms (0-4); detail zooms (5-8) only here.
DETAIL_BBOX = (25.0, 5.0, 60.0, 40.0)
GLOBAL_MAXZOOM = 4
DETAIL_MAXZOOM = 8
EXTENT = 4096
R = 6378137.0

PACKS = [
    {"version": "1.0.0-test", "content": "content.v1.json", "historical": "historical.v1.geojson"},
    {"version": "1.1.0-test", "content": "content.v2.json", "historical": "historical.v2.geojson"},
]
FONTSTACK = "Noto Naskh Arabic Regular"
# Unicode ranges (256 each) needed for Arabic map labels after shaping, plus Latin/punct.
GLYPH_RANGES = [0, 256, 1536, 1792, 8192, 64256, 64512, 64768, 65024]


# --------------------------------------------------------------------- base map tiles


def merc(xy: np.ndarray) -> np.ndarray:
    lon, lat = xy[:, 0], np.clip(xy[:, 1], -85.0511, 85.0511)
    x = R * np.radians(lon)
    y = R * np.log(np.tan(np.pi / 4 + np.radians(lat) / 2))
    return np.column_stack([x, y])


def tile_bounds_merc(z: int, x: int, y: int) -> tuple[float, float, float, float]:
    size = 2 * math.pi * R / 2**z
    minx = -math.pi * R + x * size
    maxy = math.pi * R - y * size
    return minx, maxy - size, minx + size, maxy


def lonlat_to_tile(lon: float, lat: float, z: int) -> tuple[int, int]:
    n = 2**z
    x = int((lon + 180) / 360 * n)
    lat_r = math.radians(lat)
    y = int((1 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def _unwrap(ring):
    """Make longitudes continuous across the antimeridian (no +180 -> -180 jumps)."""
    out, shift, prev = [], 0.0, None
    for lon, lat in ring:
        if prev is not None and abs(lon + shift - prev) > 180:
            shift += -360.0 if lon + shift > prev else 360.0
        prev = lon + shift
        out.append((prev, lat))
    return out


def load_land(name: str):
    gj = json.loads((CACHE / name).read_text())
    world = box(-180, -85.0511, 180, 85.0511)
    parts = []
    for f in gj["features"]:
        g = f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        for rings in polys:
            p = make_valid(Polygon(_unwrap(rings[0]), [_unwrap(r) for r in rings[1:]]))
            for dx in (-360.0, 0.0, 360.0):  # fold pieces that spilled past +-180 back in
                q = affine_translate(p, xoff=dx).intersection(world)
                if not q.is_empty:
                    parts.append(q)
    return transform(unary_union(parts), merc)


def build_basemap(out: Path) -> dict:
    land50 = load_land("land-50m.geojson")
    land10 = load_land("land-10m.geojson")
    parts10 = list(getattr(land10, "geoms", [land10]))
    tree10 = STRtree(parts10)
    parts50 = list(getattr(land50, "geoms", [land50]))
    tree50 = STRtree(parts50)

    tiles: dict[int, bytes] = {}
    counts = {}
    for z in range(0, DETAIL_MAXZOOM + 1):
        if z <= GLOBAL_MAXZOOM:
            xs = ys = range(2**z)
            coords = [(x, y) for x in xs for y in ys]
        else:
            x0, y0 = lonlat_to_tile(DETAIL_BBOX[0], DETAIL_BBOX[3], z)
            x1, y1 = lonlat_to_tile(DETAIL_BBOX[2], DETAIL_BBOX[1], z)
            coords = [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]
        parts, tree = (parts50, tree50) if z <= 5 else (parts10, tree10)
        n = 0
        for x, y in coords:
            b = tile_bounds_merc(z, x, y)
            pad = (b[2] - b[0]) * 8 / 256  # 8px buffer
            clip = box(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
            hits = [parts[i] for i in tree.query(clip)]
            if not hits:
                continue
            tol = (b[2] - b[0]) / EXTENT * 1.5
            geom = unary_union([h.intersection(clip) for h in hits]).simplify(tol)
            if geom.geom_type == "GeometryCollection":
                polys = [g for g in geom.geoms if g.geom_type in ("Polygon", "MultiPolygon")]
                geom = unary_union(polys) if polys else geom
            if geom.is_empty or geom.geom_type not in ("Polygon", "MultiPolygon"):
                continue
            data = mapbox_vector_tile.encode(
                [{"name": "land", "features": [{"geometry": geom.wkt, "properties": {}}]}],
                default_options={"quantize_bounds": b, "extents": EXTENT, "y_coord_down": False},
            )
            tiles[zxy_to_tileid(z, x, y)] = gzip.compress(data, mtime=0)
            n += 1
        counts[z] = n

    with out.open("wb") as f:
        w = Writer(f)
        for tid in sorted(tiles):
            w.write_tile(tid, tiles[tid])
        w.finalize(
            {
                "tile_type": TileType.MVT,
                "tile_compression": Compression.GZIP,
                "min_zoom": 0,
                "max_zoom": DETAIL_MAXZOOM,
                "min_lon_e7": -1800000000,
                "min_lat_e7": -850000000,
                "max_lon_e7": 1800000000,
                "max_lat_e7": 850000000,
                "center_zoom": 4,
                "center_lon_e7": 400000000,
                "center_lat_e7": 245000000,
            },
            {
                "name": "seerah-spike-base",
                "attribution": "Natural Earth (public domain) via world-atlas",
                "vector_layers": [
                    {"id": "land", "fields": {}, "minzoom": 0, "maxzoom": DETAIL_MAXZOOM}
                ],
            },
        )
    return counts


# --------------------------------------------------------------------- SDF glyphs

SCALE = 4  # supersampling factor
SIZE = 24  # MapLibre glyph size
BUFFER = 3
RADIUS = 8
CUTOFF = 0.25


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _field(num: int, wt: int, payload: bytes | int) -> bytes:
    key = _varint((num << 3) | wt)
    if wt == 0:
        return key + _varint(payload)  # type: ignore[arg-type]
    return key + _varint(len(payload)) + payload  # type: ignore[arg-type]


def _zigzag(n: int) -> int:
    return (n << 1) ^ (n >> 31)


def render_glyph(face: freetype.Face, cp: int, ascender: float) -> bytes | None:
    idx = face.get_char_index(cp)
    if idx == 0:
        return None
    face.load_glyph(idx, freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING)
    g = face.glyph
    bmp = g.bitmap
    advance = round(g.advance.x / 64 / SCALE)
    w = math.ceil(bmp.width / SCALE)
    h = math.ceil(bmp.rows / SCALE)
    left = math.floor(g.bitmap_left / SCALE)
    top = math.ceil(g.bitmap_top / SCALE)
    msg = _field(1, 0, cp)
    if w == 0 or h == 0:
        msg += _field(3, 0, 0) + _field(4, 0, 0)  # no bitmap field for empty glyphs
    else:
        pad = BUFFER * SCALE + SCALE
        hi = np.zeros((h * SCALE + 2 * pad, w * SCALE + 2 * pad), dtype=bool)
        arr = np.array(bmp.buffer, dtype=np.uint8).reshape(bmp.rows, bmp.pitch)[:, : bmp.width]
        hi[pad : pad + bmp.rows, pad : pad + bmp.width] = arr >= 128
        d_out = distance_transform_edt(~hi)
        d_in = distance_transform_edt(hi)
        dist = (d_out - d_in) / SCALE  # in 24px units, positive outside
        gw, gh = w + 2 * BUFFER, h + 2 * BUFFER
        off = pad - BUFFER * SCALE
        ys = (np.arange(gh) * SCALE + SCALE // 2 + off).clip(0, dist.shape[0] - 1)
        xs = (np.arange(gw) * SCALE + SCALE // 2 + off).clip(0, dist.shape[1] - 1)
        d = dist[np.ix_(ys, xs)]
        val = np.clip(255 - 255 * (d / RADIUS + CUTOFF), 0, 255).astype(np.uint8)
        msg += _field(2, 2, val.tobytes()) + _field(3, 0, w) + _field(4, 0, h)
    msg += _field(5, 0, _zigzag(left)) + _field(6, 0, _zigzag(top - round(ascender)))
    msg += _field(7, 0, advance)
    return msg


def build_glyphs(out_dir: Path) -> int:
    faces = []
    for name in ("NotoNaskhArabic_400Regular.ttf", "NotoSans_400Regular.ttf"):
        face = freetype.Face(str(CACHE / name))
        face.set_pixel_sizes(0, SIZE * SCALE)
        faces.append(face)
    asc = faces[0].size.ascender / 64 / SCALE
    stack_dir = out_dir / FONTSTACK
    stack_dir.mkdir(parents=True, exist_ok=True)
    total = 0
    for start in GLYPH_RANGES:
        end = start + 255
        glyphs = b""
        for cp in range(start, end + 1):
            for face in faces:  # Naskh first, then Noto Sans fallback
                m = render_glyph(face, cp, asc)
                if m is not None:
                    glyphs += _field(3, 2, m)
                    total += 1
                    break
        stack = _field(1, 2, FONTSTACK.encode()) + _field(2, 2, f"{start}-{end}".encode()) + glyphs
        (stack_dir / f"{start}-{end}.pbf").write_bytes(_field(1, 2, stack))
    return total


# --------------------------------------------------------------------- packaging


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def dir_size(p: Path) -> int:
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())


def main() -> int:
    if not (CACHE / "land-10m.geojson").exists():
        print("missing sources: run ./prepare_sources.sh first", file=sys.stderr)
        return 1
    shutil.rmtree(DIST, ignore_errors=True)
    (DIST / "assets").mkdir(parents=True)

    shared = DIST / "_shared"
    shared.mkdir()
    print("building base map tiles…")
    counts = build_basemap(shared / "basemap.pmtiles")
    print("  tiles per zoom:", counts)
    print("building glyphs…")
    n_glyphs = build_glyphs(shared / "glyphs")
    print(f"  {n_glyphs} glyphs")

    report = []
    for spec in PACKS:
        ver = spec["version"]
        d = DIST / f"pack-{ver}"
        shutil.copytree(shared, d)
        shutil.copy(HERE / "src" / spec["historical"], d / "historical.geojson")
        # Real font file for MapLibre Native `font-faces` (FreeType + HarfBuzz path).
        (d / "fonts").mkdir()
        shutil.copy(
            CACHE / "NotoNaskhArabic_400Regular.ttf", d / "fonts" / "NotoNaskhArabic-Regular.ttf"
        )
        shutil.copy(CACHE / "OFL.txt", d / "fonts" / "OFL.txt")  # SIL OFL 1.1 requires the license
        content = json.loads((HERE / "src" / spec["content"]).read_text())
        content["content_version"] = ver
        (d / "content.json").write_text(json.dumps(content, ensure_ascii=False, indent=1))
        shutil.copy(HERE / "style.template.json", d / "style.template.json")
        files = sorted(p for p in d.rglob("*") if p.is_file())
        manifest = {
            "format": 1,
            "content_version": ver,
            "test": True,
            "files": [
                {"path": str(p.relative_to(d)), "size": p.stat().st_size, "sha256": sha256(p)}
                for p in files
            ],
        }
        (d / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1))
        z = DIST / f"pack-{ver}.zip"
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as zf:
            for p in sorted(d.rglob("*")):
                if p.is_file():
                    zf.write(p, p.relative_to(d))
        (DIST / f"pack-{ver}.zip.sha256").write_text(sha256(z) + "\n")
        report.append((ver, d, z))

    shutil.copy(report[0][2], DIST / "assets" / "seed-pack.zip")
    # A deliberately corrupted v2 to test that a bad package is rejected and v1 kept.
    bad = DIST / "pack-1.1.0-test-corrupt.zip"
    shutil.copy(report[1][2], bad)
    with zipfile.ZipFile(bad, "a") as zf:
        zf.writestr("content.json", "{ corrupted }")  # duplicate entry wins on extract

    lines = ["component                      bytes"]
    d0 = report[0][1]
    for name in ("basemap.pmtiles", "glyphs", "fonts", "historical.geojson", "content.json"):
        p = d0 / name
        lines.append(f"{name:<28} {dir_size(p) if p.is_dir() else p.stat().st_size:>10,}")
    for ver, d, z in report:
        lines.append(f"pack-{ver} unpacked     {dir_size(d):>10,}")
        lines.append(f"pack-{ver}.zip          {z.stat().st_size:>10,}")
    (DIST / "size-report.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    shutil.rmtree(shared)
    return 0


if __name__ == "__main__":
    sys.exit(main())
