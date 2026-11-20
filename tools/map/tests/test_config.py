"""Tests for strict YAML loading and the profile, edits and lock loaders."""

import copy
import math
import re
from pathlib import Path

import morecantile
import pytest
from seerah_map import config, strict_yaml
from seerah_map.config import Bounds, Cell, ConfigError, FileRef, Lock

FIXTURES = Path(__file__).parent / "fixtures"
GRID_TOP = morecantile.tms.get("WebMercatorQuad").bbox.top
CAMERA = {"west": 34, "south": 12, "east": 60, "north": 32}


def _write(tmp_path, text, name="f.yaml"):
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


def _profile_with(tmp_path, **changes):
    data = strict_yaml.load(FIXTURES / "profile_ok.yaml")
    data.update(changes)
    path = tmp_path / "p.yaml"
    strict_yaml.dump(data, path)
    return path


def _profile_with_scalar(tmp_path, key, scalar):
    """Copy the fixture profile with ``key``'s value replaced by the raw YAML ``scalar``."""
    text = (FIXTURES / "profile_ok.yaml").read_text(encoding="utf-8")
    text, count = re.subn(rf"^(\s*{key}:).*$", rf"\g<1> {scalar}", text, flags=re.MULTILINE)
    assert count == 1
    return _write(tmp_path, text, "p.yaml")


def _assert_rejected(path, key, *fragments):
    """Assert loading ``path`` raises ConfigError naming the file, ``key`` and every fragment."""
    with pytest.raises(ConfigError) as caught:
        config.load_profile(path)
    message = str(caught.value)
    for expected in (str(path), f"key {key}:", *fragments):
        assert expected in message


def _edits_with(tmp_path, schema="trial-0", **edit_changes):
    data = strict_yaml.load(FIXTURES / "edits_ok.yaml")
    data["schema"] = schema
    edit = copy.deepcopy(data["edits"][0])
    edit.update(edit_changes)
    data["edits"] = [{k: v for k, v in edit.items() if v is not None}]
    path = tmp_path / "e.yaml"
    strict_yaml.dump(data, path)
    return path


def test_duplicate_key_rejected(tmp_path):
    with pytest.raises(ConfigError, match=r'duplicate key "a"'):
        strict_yaml.load(_write(tmp_path, "a: 1\na: 2\n"))


def test_yes_no_and_dates_stay_strings(tmp_path):
    data = strict_yaml.load(_write(tmp_path, "x: yes\ny: no\nd: 2024-01-01\n"))
    assert data == {"x": "yes", "y": "no", "d": "2024-01-01"}


def test_profile_loads():
    profile = config.load_profile(FIXTURES / "profile_ok.yaml")
    assert profile.style.exaggeration == 7.36
    assert profile.data_extent == Bounds(33, 11, 61, 33)
    assert profile.name == "profile_ok"
    assert profile.peninsula_outline == FIXTURES / "outline.geojson"
    assert profile.webp_quality == 80


def test_profile_rejects_release_purpose(tmp_path):
    with pytest.raises(ConfigError, match="purpose"):
        config.load_profile(_profile_with(tmp_path, purpose="release"))


@pytest.mark.parametrize("key", ["south", "west"])
def test_profile_rejects_southern_or_western_extent(tmp_path, key):
    path = _profile_with(tmp_path, camera_bounds={**CAMERA, key: -1})
    _assert_rejected(path, f"camera_bounds.{key}", "N/E")


def test_profile_accepts_the_grid_edges(tmp_path):
    bounds = {"west": 0, "south": 0, "east": 180, "north": GRID_TOP}
    path = _profile_with(tmp_path, camera_bounds=bounds, margin_deg=0, fade_deg=0)
    assert config.load_profile(path).data_extent == Bounds(0, 0, 180, GRID_TOP)


def test_profile_accepts_a_margin_reaching_the_edges(tmp_path):
    bounds = {"west": 1, "south": 1, "east": 179, "north": GRID_TOP - 1}
    path = _profile_with(tmp_path, camera_bounds=bounds, margin_deg=1)
    assert config.load_profile(path).data_extent == Bounds(0, 0, 180, GRID_TOP)


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("west", math.nextafter(0, -math.inf)),
        ("south", math.nextafter(0, -math.inf)),
        ("east", math.nextafter(180, math.inf)),
        ("north", math.nextafter(GRID_TOP, math.inf)),
        ("east", 180.01),
        ("north", 86),
    ],
)
def test_profile_rejects_camera_beyond_the_grid(tmp_path, key, value):
    path = _profile_with(tmp_path, camera_bounds={**CAMERA, key: value}, margin_deg=0, fade_deg=0)
    _assert_rejected(path, f"camera_bounds.{key}")


@pytest.mark.parametrize(
    ("key", "value"),
    [("west", 0.5), ("south", 0.5), ("east", 179.5), ("north", GRID_TOP - 0.5)],
)
def test_profile_rejects_a_margin_beyond_the_grid(tmp_path, key, value):
    path = _profile_with(tmp_path, camera_bounds={**CAMERA, key: value}, margin_deg=1)
    _assert_rejected(path, "margin_deg")


@pytest.mark.parametrize(
    ("changes", "rule"),
    [
        ({"west": 40, "east": 40}, "west < east"),
        ({"west": 50, "east": 40}, "west < east"),
        ({"south": 20, "north": 20}, "south < north"),
        ({"south": 25, "north": 20}, "south < north"),
    ],
)
def test_profile_rejects_equal_or_reversed_endpoints(tmp_path, changes, rule):
    path = _profile_with(tmp_path, camera_bounds={**CAMERA, **changes})
    _assert_rejected(path, "camera_bounds", rule)


@pytest.mark.parametrize("scalar", [".inf", "-.inf", ".nan"])
@pytest.mark.parametrize(
    ("key", "field"),
    [
        ("west", "camera_bounds.west"),
        ("south", "camera_bounds.south"),
        ("east", "camera_bounds.east"),
        ("north", "camera_bounds.north"),
        ("margin_deg", "margin_deg"),
        ("fade_deg", "fade_deg"),
    ],
)
def test_profile_rejects_non_finite_yaml_numbers(tmp_path, key, field, scalar):
    _assert_rejected(_profile_with_scalar(tmp_path, key, scalar), field)


@pytest.mark.parametrize(
    ("setting", "scalar"),
    [
        ("blur_px", ".nan"),
        ("blur_px", ".inf"),
        ("azimuth_deg", ".nan"),
        ("azimuth_deg", ".inf"),
        ("azimuth_deg", "-.inf"),
    ],
)
def test_profile_rejects_non_finite_style_numbers(tmp_path, setting, scalar):
    base = (FIXTURES / "profile_ok.yaml").read_text(encoding="utf-8")
    path = _write(tmp_path, f"{base.rstrip()}\nstyle:\n  {setting}: {scalar}\n", "p.yaml")
    _assert_rejected(path, f"style.{setting}", "finite")


def test_edits_rules(tmp_path):
    loaded = config.load_edits(_edits_with(tmp_path, review="draft"))
    assert loaded[0].review == "draft"
    assert loaded[0].feather_m == 10
    with pytest.raises(ConfigError):
        config.load_edits(_edits_with(tmp_path, operation="lower"))
    with pytest.raises(ConfigError):
        config.load_edits(_edits_with(tmp_path, schema="trial-1"))
    with pytest.raises(ConfigError):
        config.load_edits(_edits_with(tmp_path, feather_m=0))
    with pytest.raises(ConfigError):
        config.load_edits(_edits_with(tmp_path, width_m=None))
    with pytest.raises(ConfigError):
        config.load_edits(_edits_with(tmp_path, amount_m=-3))


def test_lock_round_trip(tmp_path):
    ref = FileRef("https://example.org/a.tif", 10, "ab" * 32, "dem/a.tif")
    lock = Lock(
        created="2026-11-15",
        tile_list_sha256="cd" * 32,
        cells=(Cell(24, 39, False, ref, ref), Cell(25, 39, True, None, None)),
        natural_earth=(ref,),
    )
    path = tmp_path / "lock.yaml"
    config.dump_lock(lock, path)
    assert config.load_lock(path) == lock


@pytest.mark.parametrize(
    ("cells", "key"),
    [
        ("[{lat: 24, lon: 39, absent: 'false'}]", "cells[0].absent"),
        ("[{lat: 24.7, lon: 39, absent: false}]", "cells[0].lat"),
        ("null", "cells"),
    ],
)
def test_lock_rejects_bad_types(tmp_path, cells, key):
    path = _write(
        tmp_path,
        f"created: x\ntile_list_sha256: y\ncells: {cells}\nnatural_earth: []\n",
    )
    with pytest.raises(ConfigError, match=key.replace("[", r"\[").replace("]", r"\]")):
        config.load_lock(path)
