"""The tile stage: cut the styled mosaic into a Web Mercator XYZ tile pyramid.

``gdal raster tile`` reads ``style.vrt`` and writes ``<area>/tiles/<z>/<x>/<y>.<ext>``
for zooms 0 to the profile's maximum. Every tile in the data extent is written,
transparent ones included, so the tiler never gets ``--skip-blank``. A finished
tiling is marked by ``<area>/tiles.done``; a tiles folder without it is deleted
and tiled again.
"""

import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path

from osgeo import gdal

from .blockstage import PAINTED
from .config import Profile
from .grid import blocks, expected_tiles
from .workarea import WorkArea

_WEBP_EXT = "webp"
_PNG_EXT = "png"
_AVERAGE = "average"


class CompletenessError(Exception):
    """The tiling is missing tiles or holds tiles that do not decode."""


def tile_args(
    style_vrt: Path,
    tiles_dir: Path,
    max_zoom: int,
    variant: str,
    webp_quality: int,
    workers: int,
    resume: bool,
) -> list[str]:
    """Build the ``gdal raster tile`` command line.

    Args:
        style_vrt: the styled mosaic to tile.
        tiles_dir: the output directory.
        max_zoom: the highest zoom level written (XYZ zoom).
        variant: ``painted`` (WebP tiles) or ``value`` (PNG tiles).
        webp_quality: WebP quality, 1 to 100; used by ``painted`` only.
        workers: the number of tiling threads.
        resume: whether to write only the tiles that are missing.

    Returns:
        The argument list. Tiles use XYZ numbering on WebMercatorQuad,
        average resampling at every zoom and no web-viewer files.
    """
    args = [
        "gdal",
        "raster",
        "tile",
        "--convention",
        "xyz",
        "--tiling-scheme",
        "WebMercatorQuad",
        "--min-zoom",
        "0",
        "--max-zoom",
        str(max_zoom),
        "--resampling",
        _AVERAGE,
        "--overview-resampling",
        _AVERAGE,
        "--webviewer",
        "none",
        "-j",
        str(workers),
    ]
    if variant == PAINTED:
        args += ["-f", "WEBP", "--co", f"QUALITY={webp_quality}"]
    else:
        args += ["-f", "PNG"]
    if resume:
        args.append("--resume")
    return [*args, str(style_vrt), str(tiles_dir)]


def check_completeness(
    tiles_dir: Path, expected: set[tuple[int, int, int]], ext: str
) -> list[tuple[int, int, int]]:
    """List the tiles that are missing or cannot be decoded.

    Args:
        tiles_dir: the tile pyramid's root.
        expected: the ``(z, x, y)`` tiles that must exist (XYZ).
        ext: the tile file extension, without the dot.

    Returns:
        The missing or broken tiles, sorted. A tile is broken when GDAL cannot
        open it or read one band.
    """
    bad = []
    for z, x, y in sorted(expected):
        path = tiles_dir / str(z) / str(x) / f"{y}.{ext}"
        if not _decodes(path):
            bad.append((z, x, y))
    return bad


def _decodes(path: Path) -> bool:
    if not path.is_file():
        return False
    try:
        with gdal.quiet_errors():
            ds = gdal.Open(str(path))
            return ds is not None and ds.GetRasterBand(1).ReadRaster() is not None
    except RuntimeError:
        return False


def run_tile_stage(
    work: WorkArea,
    profile: Profile,
    variant: str,
    workers: int,
    log: Callable[[str], None],
) -> int:
    """Tile the styled mosaic unless a finished tiling is already there.

    Args:
        work: the work area; ``style.vrt`` must be in it.
        profile: the build profile.
        variant: ``painted`` or ``value``.
        workers: the number of tiling threads.
        log: receives ``tile stage started`` and ``tile stage done (<n> tiles)``.

    Returns:
        The number of tiles the profile's blocks cover at zooms 0 to
        ``max_zoom``.

    Raises:
        CompletenessError: if a tile is missing or broken after tiling;
            ``tiles.done`` is then not written.
    """
    expected = expected_tiles(blocks(profile.data_extent, profile.block_zoom), profile.max_zoom)
    ext = _WEBP_EXT if variant == PAINTED else _PNG_EXT
    log("tile stage started")
    if not work.tiles_done.exists():
        shutil.rmtree(work.tiles_dir, ignore_errors=True)
        work.tiles_dir.mkdir(parents=True)
        subprocess.run(
            tile_args(
                work.path / "style.vrt",
                work.tiles_dir,
                profile.max_zoom,
                variant,
                profile.webp_quality,
                workers,
                resume=False,
            ),
            check=True,
        )
        bad = check_completeness(work.tiles_dir, expected, ext)
        if bad:
            raise CompletenessError(
                f"{len(bad)} of {len(expected)} tiles are missing or broken, first: {bad[:5]}"
            )
        work.tiles_done.write_text("")
    log(f"tile stage done ({len(expected)} tiles)")
    return len(expected)
