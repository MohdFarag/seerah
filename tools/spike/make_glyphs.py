#!/usr/bin/env python3
"""Generate MapLibre glyph PBFs (signed distance fields) from a TTF.

Spike tool. Needs Pillow (with FreeType) and numpy.

    python3 tools/spike/make_glyphs.py FONT.ttf "Font Stack Name" OUT_DIR

Writes OUT_DIR/<Font Stack Name>/<start>-<end>.pbf for all 256 ranges of the
Basic Multilingual Plane. Ranges the font does not cover get an empty stack so
the map engine never waits on a missing file.
"""

import pathlib
import sys

import numpy as np
from PIL import ImageFont

SIZE = 24  # px, the size the glyph spec expects
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


def sdf(alpha: np.ndarray) -> bytes:
    """Distance field of a coverage bitmap, padded by BUFFER on each side."""
    h, w = alpha.shape
    grid = np.zeros((h + 2 * BUFFER, w + 2 * BUFFER), dtype=bool)
    grid[BUFFER : BUFFER + h, BUFFER : BUFFER + w] = alpha > 127
    ys, xs = np.indices(grid.shape)
    pts = np.stack([ys.ravel(), xs.ravel()], axis=1).astype(np.float32)
    inside = grid.ravel()

    def nearest(targets: np.ndarray) -> np.ndarray:
        if not len(targets):
            return np.full(len(pts), RADIUS * 4, dtype=np.float32)
        d = pts[:, None, :] - targets[None, :, :]
        return np.sqrt((d * d).sum(axis=2)).min(axis=1)

    to_in = nearest(pts[inside])
    to_out = nearest(pts[~inside])
    signed = np.where(inside, -(to_out - 0.5), to_in - 0.5)
    val = 255.0 - 255.0 * (signed / RADIUS + CUTOFF)
    return np.clip(val, 0, 255).astype(np.uint8).tobytes()


def main() -> int:
    font_path, stack, out_dir = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
    font = ImageFont.truetype(font_path, SIZE, layout_engine=ImageFont.Layout.BASIC)
    notdef = font.getmask2("￿", mode="L")[0]
    notdef_bytes = (notdef.size, bytes(notdef))
    target = out_dir / stack
    target.mkdir(parents=True, exist_ok=True)
    covered = 0
    for start in range(0, 65536, 256):
        glyphs = b""
        for cp in range(start, start + 256):
            if cp < 32 or 0xD800 <= cp <= 0xDFFF:
                continue
            ch = chr(cp)
            try:
                mask, (left, top) = font.getmask2(ch, mode="L")
            except OSError:
                continue  # glyph too large to rasterise (e.g. U+FDFD)
            w, h = mask.size
            if w > 200 or h > 200:
                continue
            if cp != 0xFFFF and ((w, h), bytes(mask)) == notdef_bytes:
                continue
            advance = round(font.getlength(ch))
            g = field(1, 0, varint(cp))
            if w and h:
                alpha = np.frombuffer(bytes(mask), dtype=np.uint8).reshape(h, w)
                g += bytes_field(2, sdf(alpha))
            g += field(3, 0, varint(w)) + field(4, 0, varint(h))
            g += field(5, 0, varint(zigzag(left))) + field(6, 0, varint(zigzag(-top)))
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
