"""Tests for strict YAML loading and the profile, edits and lock loaders."""

import copy
from pathlib import Path

import pytest
from seerah_map import config, strict_yaml
from seerah_map.config import Bounds, Cell, ConfigError, FileRef, Lock

FIXTURES = Path(__file__).parent / "fixtures"


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
    with pytest.raises(ConfigError, match="duplicate key"):
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
    bounds = {"west": 34, "south": 12, "east": 60, "north": 32, key: -1}
    with pytest.raises(ConfigError, match="N/E"):
        config.load_profile(_profile_with(tmp_path, camera_bounds=bounds))


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
