"""Map-label font experiment: glyph stacks, label samples and pre-shaped names.

Kept separate from build.py so the experiment stays contained.

Variants built here (all offline, all inside the package):
- One glyph-PBF stack per candidate font (Arabic font first, Noto Sans for Latin/punctuation).
- "Preshaped": each label word is shaped at build time with HarfBuzz (GSUB + GPOS, so marks are
  anchored by the font) and stored as one SDF glyph in the Private Use Area. The map engine then
  only places whole words; it no longer positions marks. The original `name` stays untouched.
- `name_rev`: the name with its characters reversed, only to diagnose the font-faces path.
"""

from __future__ import annotations

import json
from pathlib import Path

import freetype
import numpy as np
import uharfbuzz as hb

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".cache"
NPM = CACHE / "node_modules" / "@expo-google-fonts"

LATIN = CACHE / "NotoSans_400Regular.ttf"

# Fontstack name -> Arabic TTF. All SIL OFL 1.1 (license copied into the package).
STACKS: dict[str, Path] = {
    "Noto Naskh Arabic Regular": CACHE / "NotoNaskhArabic_400Regular.ttf",  # current baseline
    "Noto Sans Arabic Medium": NPM / "noto-sans-arabic/500Medium/NotoSansArabic_500Medium.ttf",
    "Noto Kufi Arabic Medium": NPM / "noto-kufi-arabic/500Medium/NotoKufiArabic_500Medium.ttf",
    "Amiri Bold": NPM / "amiri/700Bold/Amiri_700Bold.ttf",
}
LICENSES = {
    "Noto": NPM / "noto-sans-arabic/LICENSE_FONT",
    "Amiri": NPM / "amiri/LICENSE_FONT",
}
PRESHAPED_STACK = "Preshaped Noto Sans Arabic Medium"
PRESHAPED_FONT = STACKS["Noto Sans Arabic Medium"]
PUA_START = 0xE000
FONT_FACES_TTF = STACKS["Noto Sans Arabic Medium"]

SAMPLES_FILE = HERE / "src" / "label-samples.geojson"


def stack_faces(stack: str, px: int) -> list[freetype.Face]:
    faces = []
    for p in (STACKS[stack], LATIN):
        f = freetype.Face(str(p))
        f.set_pixel_sizes(0, px)
        faces.append(f)
    return faces


def merge_samples(historical: dict) -> dict:
    """Add the label sample grid (kind=sample, test=true) to a historical FeatureCollection."""
    samples = json.loads(SAMPLES_FILE.read_text())
    return {**historical, "features": historical["features"] + samples["features"]}


def _split_lines(name: str, max_chars: int = 16) -> list[str]:
    """Logical-order line breaking at a space near the middle, for long labels."""
    if len(name) <= max_chars or " " not in name:
        return [name]
    words = name.split(" ")
    best, best_d = 1, 1e9
    for i in range(1, len(words)):
        d = abs(len(" ".join(words[:i])) - len(name) / 2)
        if d < best_d:
            best, best_d = i, d
    return [" ".join(words[:best]), " ".join(words[best:])]


class Preshaper:
    """Assigns one PUA codepoint per unique word and remembers how to draw it."""

    def __init__(self) -> None:
        self.words: dict[str, int] = {}

    @property
    def stack(self) -> str:
        """Fontstack name unique to this word->codepoint table.

        MapLibre caches glyphs per fontstack name and range. Codepoints are reassigned when the
        content changes, so each table needs its own name or an update would draw stale words.
        """
        import hashlib

        table = "\n".join(f"{cp:x}={w}" for w, cp in sorted(self.words.items(), key=lambda x: x[1]))
        return f"{PRESHAPED_STACK} {hashlib.sha256(table.encode()).hexdigest()[:10]}"

    def label(self, name: str) -> str:
        # PUA characters are strong LTR for the bidi algorithm, so the engine will not reorder
        # them: give it the words in visual (right-to-left) order, line by line.
        lines = []
        for line in _split_lines(name):
            cps = []
            for w in line.split(" "):
                if w not in self.words:
                    self.words[w] = PUA_START + len(self.words)
                cps.append(chr(self.words[w]))
            lines.append(" ".join(reversed(cps)))
        return "\n".join(lines)


def annotate(historical: dict, pre: Preshaper) -> dict:
    out = []
    for f in historical["features"]:
        p = dict(f["properties"])
        if "name" in p:
            p["name_ps"] = pre.label(p["name"])
            p["name_rev"] = p["name"][::-1]
        out.append({**f, "properties": p})
    return {**historical, "features": out}


def shape_word_bitmap(word: str, px: int) -> tuple[np.ndarray, int, int, float]:
    """HarfBuzz-shape one word and rasterize it: (bitmap, left, top, advance) at `px` size."""
    blob = hb.Blob.from_file_path(str(PRESHAPED_FONT))
    face = hb.Face(blob)
    font = hb.Font(face)
    upem = face.upem
    font.scale = (upem, upem)
    buf = hb.Buffer()
    buf.add_str(word)
    buf.guess_segment_properties()
    # HarfBuzz does no bidi: a word of digits/punctuation only (e.g. "٣٤") must run left-to-right.
    if not any("\u0620" <= ch <= "\u064a" or "\u066e" <= ch <= "\u06d3" for ch in word):
        buf.direction = "ltr"
    hb.shape(font, buf, {})
    ft = freetype.Face(str(PRESHAPED_FONT))
    ft.set_pixel_sizes(0, px)
    s = px / upem
    pieces, pen = [], 0.0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions, strict=True):
        ft.load_glyph(info.codepoint, freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING)
        g = ft.glyph
        b = g.bitmap
        if b.rows and b.width:
            arr = np.array(b.buffer, dtype=np.uint8).reshape(b.rows, b.pitch)[:, : b.width]
            x = pen + pos.x_offset * s + g.bitmap_left
            y_top = pos.y_offset * s + g.bitmap_top  # distance above the baseline
            pieces.append((arr, round(x), round(y_top)))
        pen += pos.x_advance * s
    if not pieces:
        return np.zeros((0, 0), np.uint8), 0, 0, pen
    x0 = min(x for _, x, _ in pieces)
    x1 = max(x + a.shape[1] for a, x, _ in pieces)
    t0 = max(t for _, _, t in pieces)
    b0 = min(t - a.shape[0] for a, _, t in pieces)
    canvas = np.zeros((t0 - b0, x1 - x0), np.uint8)
    for a, x, t in pieces:
        r, c = t0 - t, x - x0
        canvas[r : r + a.shape[0], c : c + a.shape[1]] = np.maximum(
            canvas[r : r + a.shape[0], c : c + a.shape[1]], a
        )
    return canvas, x0, t0, pen
