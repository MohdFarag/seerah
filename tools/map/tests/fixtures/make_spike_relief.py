"""Make ``spike_relief.npz``, the regression fixture for the style function.

    python3 make_spike_relief.py OUT_DIR

Builds a synthetic 256x256 float32 elevation (metres), writes it as an ENVI
raster, runs the spike's ``tools/spike/relief_style.py`` on it once, and saves
``elevation``, ``alpha`` (band 4 of the spike's RGBA, uint8) and ``pixel_m``
(metres) to ``OUT_DIR/spike_relief.npz``. Run it inside the ``seerah-map``
image with the repository mounted at ``/src``; the result is committed.
"""

import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from seerah_map.grid import pixel_m

_SIZE = 256
_HILL_M = 600.0
_HILL_SIGMA_PX = 30.0
_RIDGE_M = 200.0
_RIDGE_SIGMA_PX = 10.0
_SEA_CORNER_PX = 40
_SEA_M = 0.0
_RGBA = 4
_ALPHA_BAND = 3
_SPIKE_SCRIPT = Path(__file__).resolve().parents[4] / "tools/spike/relief_style.py"


def make_elevation() -> np.ndarray:
    """The synthetic elevation: a central hill, a diagonal ridge and a sea corner (metres)."""
    rows, cols = np.mgrid[0:_SIZE, 0:_SIZE].astype(np.float64)
    centre = (_SIZE - 1) / 2
    hill = _HILL_M * np.exp(
        -((rows - centre) ** 2 + (cols - centre) ** 2) / (2 * _HILL_SIGMA_PX**2)
    )
    across_diagonal = (rows - cols) / np.sqrt(2)
    ridge = _RIDGE_M * np.exp(-(across_diagonal**2) / (2 * _RIDGE_SIGMA_PX**2))
    z = (hill + ridge).astype(np.float32)
    z[-_SEA_CORNER_PX:, -_SEA_CORNER_PX:] = _SEA_M
    return z


def write_envi(z: np.ndarray, path: Path, px: float) -> None:
    """Write ``z`` as a raw float32 ENVI file with its ``.hdr``, pixel size ``px`` metres."""
    z.astype("<f4").tofile(path)
    path.with_suffix(".hdr").write_text(
        "ENVI\n"
        f"samples = {z.shape[1]}\n"
        f"lines = {z.shape[0]}\n"
        "bands = 1\n"
        "header offset = 0\n"
        "file type = ENVI Standard\n"
        "data type = 4\n"
        "interleave = bsq\n"
        "byte order = 0\n"
        f"map info = {{Pseudo Mercator, 1, 1, 0, 0, {px!r}, {px!r}}}\n"
    )


def main() -> int:
    """Generate the fixture in the folder given as the first argument."""
    out_dir = Path(sys.argv[1])
    z = make_elevation()
    px = pixel_m()
    with tempfile.TemporaryDirectory() as tmp:
        dem = Path(tmp) / "dem.envi"
        raw = Path(tmp) / "relief.raw"
        write_envi(z, dem, px)
        subprocess.run([sys.executable, str(_SPIKE_SCRIPT), str(dem), str(raw)], check=True)
        rgba = np.fromfile(raw, dtype=np.uint8).reshape(_SIZE, _SIZE, _RGBA)
    out = out_dir / "spike_relief.npz"
    np.savez_compressed(out, elevation=z, alpha=rgba[..., _ALPHA_BAND], pixel_m=np.float64(px))
    print(f"wrote {out}: pixel_m={px}, alpha max={rgba[..., _ALPHA_BAND].max()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
