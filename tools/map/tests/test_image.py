"""Checks that the toolchain image holds what the map build tool needs."""

import json
import os
import pathlib
import shutil
import subprocess

import pytest


def _srs3857():
    """Return an EPSG:3857 spatial reference."""
    from osgeo import osr

    osr.UseExceptions()
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(3857)
    return srs


def test_toolchain_present():
    import importlib

    import numpy
    from osgeo import gdal, gdal_array, osr  # noqa: F401

    for name in ("scipy", "pmtiles", "morecantile", "ruamel.yaml", "jsonschema"):
        importlib.import_module(name)

    assert gdal.__version__.startswith("3.13")
    assert numpy.__file__.startswith("/usr/lib/python3/dist-packages")
    assert shutil.which("pmtiles") is not None


def test_morecantile_and_osr_in_one_process():
    import morecantile
    from osgeo import osr

    osr.UseExceptions()
    tile = morecantile.tms.get("WebMercatorQuad").tile(39.6, 24.5, 12)
    t = osr.CoordinateTransformation(
        osr.SpatialReference(wkt=osr.SRS_WKT_WGS84_LAT_LONG), _srs3857()
    )
    assert tile.z == 12 and t is not None


def test_single_thread_environment():
    for name in (
        "GDAL_NUM_THREADS",
        "OMP_NUM_THREADS",
        "OPENBLAS_NUM_THREADS",
        "TIPPECANOE_MAX_THREADS",
        "RAYON_NUM_THREADS",
    ):
        assert os.environ[name] == "1"
    assert os.environ["GDAL_CACHEMAX"] == "256"


def test_writable_mount_points():
    """Check the image's own mount points are world-writable with the sticky bit.

    A path with a host folder or volume mounted over it shows the mount's
    mode, not the image's, so it is skipped (CI mounts its cache on /cache).
    """
    unmounted = [p for p in ("/cache", "/work", "/out") if not os.path.ismount(p)]
    for p in unmounted:
        assert oct(pathlib.Path(p).stat().st_mode)[-4:] == "1777"


def test_in_memory_vector_driver():
    from osgeo import ogr

    assert ogr.GetDriverByName("MEM") or ogr.GetDriverByName("Memory")


def _run(*args: str | pathlib.Path) -> subprocess.CompletedProcess[str]:
    """Run a command, capturing stdout and stderr for failure diagnostics."""
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True, check=False)
    assert result.returncode == 0, (
        f"{' '.join(map(str, args))} failed ({result.returncode}):\n"
        f"stdout: {result.stdout}\nstderr: {result.stderr}"
    )
    return result


@pytest.mark.parametrize(
    ("name", "license_path"),
    [
        ("tippecanoe", "tippecanoe/LICENSE.md"),
        ("gdal_contour", None),
        ("whitebox_tools", "whitebox-tools/LICENSE.txt"),
    ],
)
def test_base_and_valley_tools_present(name: str, license_path: str | None):
    executable = shutil.which(name)
    assert executable is not None
    _run(executable, "--version")
    if license_path is not None:
        assert (pathlib.Path("/usr/share/licenses") / license_path).is_file()


def test_tile_join_writes_an_archive(tmp_path: pathlib.Path):
    from osgeo import gdal

    assert shutil.which("tile-join") is not None
    slices = {
        "land": {
            "type": "Polygon",
            "coordinates": [[[39.0, 21.0], [40.0, 21.0], [40.0, 22.0], [39.0, 22.0], [39.0, 21.0]]],
        },
        "coast": {
            "type": "LineString",
            "coordinates": [[39.0, 21.0], [40.0, 22.0]],
        },
    }
    parts = []
    for layer, geometry in slices.items():
        geojson = tmp_path / f"{layer}.geojson"
        geojson.write_text(json.dumps({"type": "Feature", "properties": {}, "geometry": geometry}))
        part = tmp_path / f"{layer}.pmtiles"
        _run(
            "tippecanoe",
            "-q",
            "--no-progress-indicator",
            "-Z0",
            "-z0",
            "-l",
            layer,
            "-o",
            part,
            geojson,
        )
        parts.append(part)

    joined = tmp_path / "joined.pmtiles"
    _run("tile-join", "--no-tile-size-limit", "-o", joined, *parts)
    _run("pmtiles", "verify", joined)

    metadata = json.loads(_run("pmtiles", "show", joined, "--metadata").stdout)
    assert {layer["id"] for layer in metadata["vector_layers"]} == {"land", "coast"}

    with gdal.OpenEx(str(joined), gdal.OF_VECTOR, open_options=["ZOOM_LEVEL=0"]) as ds:
        for layer_name in ("land", "coast"):
            layer = ds.GetLayerByName(layer_name)
            assert layer is not None
            assert layer.GetFeatureCount() == 1
