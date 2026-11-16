"""Seerah map build tool: turns open elevation data into the trial map's tiles."""

from osgeo import gdal, ogr, osr

__version__ = "0.1.0"

# Make GDAL raise Python exceptions instead of returning error codes.
gdal.UseExceptions()
ogr.UseExceptions()
osr.UseExceptions()
