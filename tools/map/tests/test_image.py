"""Checks that the toolchain image holds what the map build tool needs."""

import os
import pathlib
import shutil


def _srs3857():
    """Return an EPSG:3857 spatial reference."""
    from osgeo import osr

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

    tile = morecantile.tms.get("WebMercatorQuad").tile(39.6, 24.5, 12)
    t = osr.CoordinateTransformation(
        osr.SpatialReference(wkt=osr.SRS_WKT_WGS84_LAT_LONG), _srs3857()
    )
    assert tile.z == 12 and t is not None


def test_single_thread_environment():
    for name in ("GDAL_NUM_THREADS", "OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS"):
        assert os.environ[name] == "1"
    assert os.environ["GDAL_CACHEMAX"] == "256"


def test_writable_mount_points():
    for p in ("/cache", "/work", "/out"):
        assert oct(pathlib.Path(p).stat().st_mode)[-4:] == "1777"


def test_in_memory_vector_driver():
    from osgeo import ogr

    assert ogr.GetDriverByName("MEM") or ogr.GetDriverByName("Memory")
