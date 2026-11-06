#!/usr/bin/env python3
"""Compose side-by-side sheets from FontComparisonTest screenshots.

usage: compose_fonts.py <screens-dir> <out-dir> [--log]
--log also prints small JPEG sheets as base64 between markers,
for reviewers without artifact access.
"""

import base64
import io
import sys
from pathlib import Path

from PIL import Image, ImageDraw

VARIANTS = [
    "NASKH_OLD",
    "NASKH",
    "NOTO_SANS",
    "NOTO_KUFI",
    "AMIRI",
    "PRESHAPED",
    "FONT_FACES",
    "FONT_FACES_REVERSED",
]
SCENES = ["closeup-top", "closeup-bottom", "close", "mid", "far"]


def sheet(src: Path, scene: str, width: int) -> Image.Image | None:
    tiles = []
    for v in VARIANTS:
        p = src / f"{v}-{scene}.png"
        if p.exists():
            im = Image.open(p).convert("RGB")
            im = im.resize((width, round(im.height * width / im.width)))
            tiles.append((v, im))
    if not tiles:
        return None
    th = max(im.height for _, im in tiles) + 28
    cols = 2
    rows = (len(tiles) + cols - 1) // cols
    out = Image.new("RGB", (cols * (width + 8), rows * th), "white")
    d = ImageDraw.Draw(out)
    for i, (v, im) in enumerate(tiles):
        x, y = (i % cols) * (width + 8), (i // cols) * th
        d.text((x + 4, y + 6), f"{v}  [{scene}]", fill="black")
        out.paste(im, (x, y + 24))
    return out


def main() -> None:
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)
    for sc in SCENES:
        full = sheet(src, sc, 720)
        if full is None:
            continue
        full.save(dst / f"sheet-{sc}.jpg", quality=90)
        if "--log" in sys.argv and sc in ("closeup-top", "closeup-bottom", "close", "mid"):
            small = sheet(src, sc, 440)
            buf = io.BytesIO()
            small.save(buf, "JPEG", quality=72)
            print(f"===BEGIN-SHEET {sc} ===")
            b = base64.b64encode(buf.getvalue()).decode()
            for i in range(0, len(b), 4000):
                print(b[i : i + 4000])
            print(f"===END-SHEET {sc} ===")


if __name__ == "__main__":
    main()
