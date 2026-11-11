#!/usr/bin/env python3
"""Generate MapLibre glyph PBFs (signed distance fields) from a TTF.

Spike tool. Needs Pillow (with FreeType) and numpy.

    python3 tools/spike/make_glyphs.py FONT.ttf "Font Stack Name" OUT_DIR [FALLBACK.ttf]

The engine cannot fall back between bundled stacks, so a code point the main
font lacks (an arrow, say) is taken from the fallback font when one is given.

Writes OUT_DIR/<Font Stack Name>/<start>-<end>.pbf for all 256 ranges of the
Basic Multilingual Plane. Ranges the font does not cover get an empty stack so
the map engine never waits on a missing file.
"""

import pathlib
import sys

import numpy as np
from PIL import ImageFont

SIZE = 24  # px, the size the glyph spec expects
SCALE = 4  # render larger, then average the field down
BUFFER = 3
RADIUS = 8.0
CUTOFF = 0.25


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def zigzag(n: int) -> int:
    return (n << 1) ^ (n >> 63)


def field(num: int, wire: int, payload: bytes) -> bytes:
    return varint((num << 3) | wire) + payload


def bytes_field(num: int, data: bytes) -> bytes:
    return field(num, 2, varint(len(data)) + data)


INF = 1e20


def _edt1d(f: np.ndarray) -> np.ndarray:
    """1D squared distance transform (Felzenszwalb and Huttenlocher)."""
    n = len(f)
    d = np.empty(n)
    v = [0] * n
    z = [0.0] * (n + 1)
    k = 0
    z[0], z[1] = -INF, INF
    for q in range(1, n):
        while True:
            p = v[k]
            s = ((f[q] + q * q) - (f[p] + p * p)) / (2 * q - 2 * p)
            if s > z[k]:
                break
            k -= 1
        k += 1
        v[k] = q
        z[k], z[k + 1] = s, INF
    k = 0
    for q in range(n):
        while z[k + 1] < q:
            k += 1
        p = v[k]
        d[q] = (q - p) ** 2 + f[p]
    return d


def _edt2d(grid: np.ndarray) -> np.ndarray:
    out = np.apply_along_axis(_edt1d, 0, grid)
    return np.apply_along_axis(_edt1d, 1, out)


def sdf(alpha: np.ndarray, pad_x: int, pad_y: int) -> tuple[bytes, int, int]:
    """Distance field of a glyph rendered SCALE times larger than SIZE.

    The field is computed at the large size and averaged down, which keeps
    thin strokes and sharp corners that a 24 px bitmap loses. Returns the
    field plus the glyph width and height at SIZE.
    """
    hh, hw = alpha.shape
    w = -(-(hw + pad_x) // SCALE)
    h = -(-(hh + pad_y) // SCALE)
    a = np.zeros(((h + 2 * BUFFER) * SCALE, (w + 2 * BUFFER) * SCALE))
    y0, x0 = BUFFER * SCALE + pad_y, BUFFER * SCALE + pad_x
    a[y0 : y0 + hh, x0 : x0 + hw] = alpha / 255.0
    outer = np.where(a == 1, 0.0, np.where(a == 0, INF, np.maximum(0, 0.5 - a) ** 2))
    inner = np.where(a == 1, INF, np.where(a == 0, 0.0, np.maximum(0, a - 0.5) ** 2))
    dist = (np.sqrt(_edt2d(outer)) - np.sqrt(_edt2d(inner))) / SCALE
    dist = dist.reshape(h + 2 * BUFFER, SCALE, w + 2 * BUFFER, SCALE).mean(axis=(1, 3))
    val = 255.0 - 255.0 * (dist / RADIUS + CUTOFF)
    return np.clip(np.round(val), 0, 255).astype(np.uint8).tobytes(), w, h


def main() -> int:
    font_path, stack, out_dir = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
    fonts = []
    for path in [font_path, *sys.argv[4:5]]:
        f = ImageFont.truetype(path, SIZE * SCALE, layout_engine=ImageFont.Layout.BASIC)
        nd = f.getmask2(chr(0xFFFF), mode="L")[0]
        fonts.append((f, (nd.size, bytes(nd))))
    target = out_dir / stack
    target.mkdir(parents=True, exist_ok=True)
    covered = 0
    for start in range(0, 65536, 256):
        glyphs = b""
        for cp in range(start, start + 256):
            if cp < 32 or 0xD800 <= cp <= 0xDFFF:
                continue
            ch = chr(cp)
            found = None
            for font, notdef_bytes in fonts:
                try:
                    mask, (left, top) = font.getmask2(ch, mode="L")
                except OSError:
                    continue  # glyph too large to rasterise (e.g. U+FDFD)
                hw, hh = mask.size
                if hw > 200 * SCALE or hh > 200 * SCALE or ((hw, hh), bytes(mask)) == notdef_bytes:
                    continue
                found = font
                break
            if found is None:
                continue
            advance = round(found.getlength(ch) / SCALE)
            left_lo, top_lo = left // SCALE, top // SCALE
            g = field(1, 0, varint(cp))
            w = h = 0
            if hw and hh:
                alpha = np.frombuffer(bytes(mask), dtype=np.uint8).reshape(hh, hw)
                data, w, h = sdf(alpha, left - left_lo * SCALE, top - top_lo * SCALE)
                g += bytes_field(2, data)
            g += field(3, 0, varint(w)) + field(4, 0, varint(h))
            g += field(5, 0, varint(zigzag(left_lo))) + field(6, 0, varint(zigzag(-top_lo)))
            g += field(7, 0, varint(advance))
            glyphs += bytes_field(3, g)
            covered += 1
        rng = f"{start}-{start + 255}"
        body = bytes_field(1, stack.encode()) + bytes_field(2, rng.encode()) + glyphs
        (target / f"{rng}.pbf").write_bytes(bytes_field(1, body))
    print(f"{stack}: {covered} glyphs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
