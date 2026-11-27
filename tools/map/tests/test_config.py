"""Tests for strict YAML loading and the profile, edits and lock loaders."""

import ast
import copy
import datetime
import math
import re
from pathlib import Path

import morecantile
import pytest
from ruamel.yaml import YAML
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


def _assert_rejected(path, key, *fragments, data_root=None):
    """Assert loading ``path`` raises ConfigError naming the file, ``key`` and every fragment."""
    with pytest.raises(ConfigError) as caught:
        if data_root is None:
            config.load_profile(path)
        else:
            config.load_profile(path, data_root=data_root)
    message = str(caught.value)
    for expected in (str(path), f"key {key}:", *fragments):
        assert expected in message


def _outline_dirs(tmp_path):
    """Create a data root with ``trial`` and ``shared`` folders and sample outlines."""
    data = tmp_path / "data"
    trial = data / "trial"
    shared = data / "shared"
    trial.mkdir(parents=True)
    shared.mkdir(parents=True)
    (trial / "local.geojson").write_text("{}", encoding="utf-8")
    (shared / "outline.geojson").write_text("{}", encoding="utf-8")
    return data, trial, shared


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


def test_strict_yaml_timestamp_resolver_isolation(tmp_path):
    timestamp = "2026-11-15T12:34:56+02:00"
    date = "2026-11-15"
    path = _write(tmp_path, f"ts: {timestamp}\nd: {date}\nflag: true\n")

    for _ in range(2):
        strict = strict_yaml.load(path)
        assert isinstance(strict["ts"], str)
        assert strict["ts"] == timestamp
        assert isinstance(strict["d"], str)
        assert strict["d"] == date
        assert strict["flag"] is True

        default_yaml = YAML(typ="safe", pure=True)
        default_yaml.version = (1, 2)
        with open(path, encoding="utf-8") as handle:
            default = default_yaml.load(handle)
        assert isinstance(default["ts"], datetime.datetime)
        assert isinstance(default["d"], datetime.date)
        assert not isinstance(default["d"], datetime.datetime)
        assert default["flag"] is True


def test_profile_loads():
    profile = config.load_profile(FIXTURES / "profile_ok.yaml")
    assert profile.style.exaggeration == 7.36
    assert profile.data_extent == Bounds(33, 11, 61, 33)
    assert profile.name == "profile_ok"
    assert profile.peninsula_outline == (FIXTURES / "outline.geojson").resolve()
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


def test_profile_resolves_local_and_sibling_outlines(tmp_path):
    data, trial, shared = _outline_dirs(tmp_path)
    local = _profile_with(trial, peninsula_outline="local.geojson")
    assert config.load_profile(local).peninsula_outline == (trial / "local.geojson").resolve()
    assert (
        config.load_profile(local, data_root=data).peninsula_outline
        == (trial / "local.geojson").resolve()
    )
    sibling = _profile_with(trial, peninsula_outline="../shared/outline.geojson")
    assert (
        config.load_profile(sibling, data_root=data).peninsula_outline
        == (shared / "outline.geojson").resolve()
    )


def test_profile_sibling_outline_requires_broader_data_root(tmp_path):
    data, trial, shared = _outline_dirs(tmp_path)
    path = _profile_with(trial, peninsula_outline="../shared/outline.geojson")
    assert (
        config.load_profile(path, data_root=data).peninsula_outline
        == (shared / "outline.geojson").resolve()
    )
    _assert_rejected(path, "peninsula_outline")


def test_profile_accepts_uncreated_in_root_outline(tmp_path):
    data, trial, shared = _outline_dirs(tmp_path)
    local_missing = _profile_with(trial, peninsula_outline="future.geojson")
    assert (
        config.load_profile(local_missing).peninsula_outline == (trial / "future.geojson").resolve()
    )
    sibling_missing = _profile_with(trial, peninsula_outline="../shared/future.geojson")
    assert (
        config.load_profile(sibling_missing, data_root=data).peninsula_outline
        == (shared / "future.geojson").resolve()
    )


def test_profile_accepts_internal_symlink_outline(tmp_path):
    data, trial, shared = _outline_dirs(tmp_path)
    local_link = trial / "local-link.geojson"
    local_link.symlink_to(trial / "local.geojson")
    local_profile = _profile_with(trial, peninsula_outline="local-link.geojson")
    assert (
        config.load_profile(local_profile).peninsula_outline == (trial / "local.geojson").resolve()
    )

    shared_link = trial / "shared-link.geojson"
    shared_link.symlink_to("../shared/outline.geojson")
    shared_profile = _profile_with(trial, peninsula_outline="shared-link.geojson")
    assert (
        config.load_profile(shared_profile, data_root=data).peninsula_outline
        == (shared / "outline.geojson").resolve()
    )
    _assert_rejected(shared_profile, "peninsula_outline")

    dangling_link = trial / "dangling-internal.geojson"
    dangling_link.symlink_to("../shared/future.geojson")
    dangling_profile = _profile_with(trial, peninsula_outline="dangling-internal.geojson")
    assert (
        config.load_profile(dangling_profile, data_root=data).peninsula_outline
        == (shared / "future.geojson").resolve()
    )
    _assert_rejected(dangling_profile, "peninsula_outline")


def test_profile_rejects_absolute_outlines(tmp_path):
    data, trial, shared = _outline_dirs(tmp_path)
    outside = tmp_path / "outside.geojson"
    outside.write_text("{}", encoding="utf-8")
    for target in (trial / "local.geojson", shared / "outline.geojson", outside):
        path = _profile_with(trial, peninsula_outline=str(target.resolve()))
        _assert_rejected(path, "peninsula_outline", "relative")
        _assert_rejected(path, "peninsula_outline", "relative", data_root=data)


@pytest.mark.parametrize(
    "outline",
    [
        "../../outside.geojson",
        "../../data-other/outline.geojson",
    ],
)
def test_profile_rejects_escaping_outline_paths(tmp_path, outline):
    data, trial, _ = _outline_dirs(tmp_path)
    (tmp_path / "outside.geojson").write_text("{}", encoding="utf-8")
    prefix_sibling = tmp_path / "data-other"
    prefix_sibling.mkdir()
    (prefix_sibling / "outline.geojson").write_text("{}", encoding="utf-8")
    path = _profile_with(trial, peninsula_outline=outline)
    _assert_rejected(path, "peninsula_outline")
    _assert_rejected(path, "peninsula_outline", data_root=data)


def test_profile_rejects_outside_symlink_outline(tmp_path):
    data, trial, _ = _outline_dirs(tmp_path)
    outside = tmp_path / "outside.geojson"
    outside.write_text("{}", encoding="utf-8")
    link = trial / "escape.geojson"
    link.symlink_to(outside)
    path = _profile_with(trial, peninsula_outline="escape.geojson")
    _assert_rejected(path, "peninsula_outline")
    _assert_rejected(path, "peninsula_outline", data_root=data)

    dangling = trial / "dangling-escape.geojson"
    dangling.symlink_to(tmp_path / "missing-outside.geojson")
    dangling_path = _profile_with(trial, peninsula_outline="dangling-escape.geojson")
    _assert_rejected(dangling_path, "peninsula_outline")
    _assert_rejected(dangling_path, "peninsula_outline", data_root=data)


@pytest.mark.parametrize(
    "outline",
    [
        "loop-a.geojson",
        "missing/../loop-a.geojson",
        "loop-dir/child.geojson",
        "missing/../loop-dir/../local.geojson",
        "loop-missing",
        "loop-missing/../../outside.geojson",
    ],
)
def test_profile_rejects_symlink_loop_outline(tmp_path, outline):
    data, trial, _ = _outline_dirs(tmp_path)
    loop_a = trial / "loop-a.geojson"
    loop_b = trial / "loop-b.geojson"
    loop_a.symlink_to(loop_b)
    loop_b.symlink_to(loop_a)
    (trial / "loop-dir").symlink_to("loop-dir")
    (trial / "loop-missing").symlink_to("missing/../loop-missing")
    path = _profile_with(trial, peninsula_outline=outline)
    _assert_rejected(path, "peninsula_outline")
    _assert_rejected(path, "peninsula_outline", data_root=data)


@pytest.mark.parametrize(
    "outline", ["local.geojson/child.geojson", "local.geojson/../local.geojson"]
)
def test_profile_rejects_regular_file_traversal_outline(tmp_path, outline):
    data, trial, _ = _outline_dirs(tmp_path)
    path = _profile_with(trial, peninsula_outline=outline)
    _assert_rejected(path, "peninsula_outline")
    _assert_rejected(path, "peninsula_outline", data_root=data)


def test_profile_rejects_root_directory_outline(tmp_path):
    data, trial, _ = _outline_dirs(tmp_path)
    parent = _profile_with(trial, peninsula_outline="..")
    _assert_rejected(parent, "peninsula_outline", data_root=data)
    current = _profile_with(trial, peninsula_outline=".")
    _assert_rejected(current, "peninsula_outline")


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


def _cleanup_doc(**overrides):
    data = strict_yaml.load(FIXTURES / "cleanup_ok.yaml")
    data.update(overrides)
    return data


def _write_cleanup(tmp_path, data, name="cleanup.yaml"):
    path = tmp_path / name
    strict_yaml.dump(data, path)
    return path


def _cleanup_with_edit(tmp_path, index=0, **edit_changes):
    data = _cleanup_doc()
    edit = copy.deepcopy(data["edits"][index])
    for k, v in edit_changes.items():
        if v is None:
            edit.pop(k, None)
        else:
            edit[k] = v
    data["edits"] = [edit]
    return _write_cleanup(tmp_path, data)


def test_cleanup_loads_valid_fixture_and_empty_edits(tmp_path):
    cleanups = config.load_cleanup(FIXTURES / "cleanup_ok.yaml")
    assert [e.id for e in cleanups] == [
        "remove-synthetic-pier",
        "fill-synthetic-basin",
        "draft-synthetic-spit",
    ]
    assert cleanups[0].operation == "remove-land"
    assert cleanups[0].geometry["type"] == "Polygon"
    assert cleanups[0].sources[0].kind == "imagery"
    assert cleanups[1].operation == "fill-water"
    assert cleanups[1].geometry["type"] == "MultiPolygon"
    assert cleanups[1].sources[0].edition == "2020"
    assert cleanups[2].review == "draft"
    assert cleanups[2].sources == ()

    empty = _write_cleanup(tmp_path, {"schema": 1, "purpose": "production", "edits": []})
    assert config.load_cleanup(empty) == []


@pytest.mark.parametrize("review", ["draft", "in-review", "title-approved"])
@pytest.mark.parametrize("sources", [None, []])
def test_cleanup_allows_unapproved_without_sources(tmp_path, review, sources):
    path = _cleanup_with_edit(tmp_path, review=review, sources=sources)
    loaded = config.load_cleanup(path)
    assert loaded[0].review == review
    assert loaded[0].sources == ()


def test_cleanup_accepts_grid_edges(tmp_path):
    ring = [
        [-180, -GRID_TOP],
        [180, -GRID_TOP],
        [180, GRID_TOP],
        [-180, GRID_TOP],
        [-180, -GRID_TOP],
    ]
    path = _cleanup_with_edit(tmp_path, geometry={"type": "Polygon", "coordinates": [ring]})
    assert len(config.load_cleanup(path)) == 1


@pytest.mark.parametrize("schema", ["1", 2, True])
def test_cleanup_rejects_bad_schema(tmp_path, schema):
    path = _write_cleanup(tmp_path, _cleanup_doc(schema=schema))
    with pytest.raises(ConfigError):
        config.load_cleanup(path)


@pytest.mark.parametrize("purpose", ["trial", "staging"])
def test_cleanup_rejects_non_production_purpose(tmp_path, purpose):
    path = _write_cleanup(tmp_path, _cleanup_doc(purpose=purpose))
    with pytest.raises(ConfigError, match="purpose"):
        config.load_cleanup(path)


@pytest.mark.parametrize(
    "changes",
    [
        {"class": "reconstruction"},
        {"class": None},
        {"operation": "raise"},
        {"operation": "lower"},
        {"id": "Upper_Case"},
        {"id": "-leading"},
        {"id": "trailing-"},
        {"id": "double--dash"},
        {"id": ""},
        {"reason": "   "},
        {"reason": "\t\n"},
        {"feather_m": 10},
        {
            "geometry": {
                "type": "LineString",
                "coordinates": [[58.1, 20.1], [58.2, 20.2]],
            }
        },
        {
            "geometry": {
                "type": "Polygon",
                "coordinates": [[[58.1, 20.1], [58.2, 20.1], [58.1, 20.1]]],
            }
        },
        {
            "geometry": {
                "type": "Polygon",
                "coordinates": [
                    [[58.1, 20.1], [58.2, 20.1], [58.2, 20.2], [58.1, 20.2], [58.1, 20.19]]
                ],
            }
        },
        {
            "geometry": {
                "type": "MultiPolygon",
                "coordinates": [
                    [[[58.1, 20.1], [58.2, 20.1], [58.2, 20.2], [58.1, 20.2], [58.1, 20.1]]],
                    [[[58.3, 20.3], [58.4, 20.3], [58.4, 20.4], [58.3, 20.4], [58.3, 20.39]]],
                ],
            }
        },
    ],
)
def test_cleanup_rejects_invalid_edit_fields(tmp_path, changes):
    path = _cleanup_with_edit(tmp_path, **changes)
    with pytest.raises(ConfigError):
        config.load_cleanup(path)


def test_cleanup_rejects_duplicate_ids(tmp_path):
    data = _cleanup_doc()
    second = copy.deepcopy(data["edits"][0])
    data["edits"] = [data["edits"][0], second]
    with pytest.raises(ConfigError, match="edits.id"):
        config.load_cleanup(_write_cleanup(tmp_path, data))


@pytest.mark.parametrize(
    ("lon", "lat"),
    [
        (180.1, 20.0),
        (-180.1, 20.0),
        (58.0, math.nextafter(GRID_TOP, math.inf)),
        (58.0, math.nextafter(-GRID_TOP, -math.inf)),
        (58.0, -86.0),
    ],
)
def test_cleanup_rejects_out_of_range_coordinates(tmp_path, lon, lat):
    ring = [[58.1, 20.1], [lon, lat], [58.2, 20.2], [58.1, 20.2], [58.1, 20.1]]
    path = _cleanup_with_edit(tmp_path, geometry={"type": "Polygon", "coordinates": [ring]})
    with pytest.raises(ConfigError):
        config.load_cleanup(path)


@pytest.mark.parametrize("scalar", [".nan", ".inf", "-.inf"])
def test_cleanup_rejects_non_finite_coordinates(tmp_path, scalar):
    raw = (FIXTURES / "cleanup_ok.yaml").read_text(encoding="utf-8")
    bad = raw.replace("[58.2, 20.1]", f"[{scalar}, 20.1]", 1)
    assert bad != raw
    with pytest.raises(ConfigError) as caught:
        config.load_cleanup(_write(tmp_path, bad, "bad.yaml"))
    if scalar == ".nan":
        assert "finite" in str(caught.value)


@pytest.mark.parametrize(
    "sources",
    [
        None,
        [],
        [{"kind": "atlas", "work": "Atlas", "locator": "p. 1"}],
        [{"kind": "quran", "work": "Quran", "locator": "2:1"}],
        [{"kind": "imagery", "work": "  ", "locator": "scene-1"}],
        [{"kind": "imagery", "work": "Sat", "locator": "   "}],
        [{"kind": "imagery", "work": "Sat", "locator": "s1", "edition": "   "}],
        [{"kind": "imagery", "work": "Sat", "locator": "s1", "url": "ftp://example.invalid/x"}],
        [{"kind": "imagery", "work": "Sat", "locator": "s1", "url": "not a url"}],
        [{"kind": "imagery", "work": "Sat", "locator": "s1", "url": "https://bad url"}],
        [{"kind": "imagery", "work": "Sat", "locator": "s1", "extra": "bad"}],
    ],
)
def test_cleanup_rejects_invalid_approved_sources(tmp_path, sources):
    path = _cleanup_with_edit(tmp_path, review="approved", sources=sources)
    with pytest.raises(ConfigError):
        config.load_cleanup(path)


def test_config_module_does_not_import_osgeo():
    tree = ast.parse(Path(config.__file__).read_text(encoding="utf-8"))
    imported = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.extend(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.append(node.module)
    assert not any(name == "osgeo" or name.startswith("osgeo.") for name in imported)


def _valid_coverage_dict(**overrides):
    data = {
        "schema": 1,
        "purpose": "production",
        "camera_bounds": {"west": 58.85, "south": 20.55, "east": 59.00, "north": 20.70},
        "data_extent": {"west": 58.80, "south": 20.50, "east": 59.05, "north": 20.75},
        "camera_min_zoom": 4.0,
        "camera_max_zoom": 14.0,
        "regional_max_zoom": 12,
        "block_zoom": 11,
        "fade_deg": 0.02,
        "relief_encoding": "painted-rgba",
        "webp_quality": 80,
        "widest_half_screen_dp": 550.0,
        "min_data_margin_deg": 0.05,
    }
    data.update(overrides)
    return data


def _write_coverage(tmp_path: Path, data: dict, name: str = "coverage.yaml") -> Path:
    import json

    return _write(tmp_path, json.dumps(data, indent=2), name)


def test_load_coverage_valid_painted_and_terrarium(tmp_path: Path):
    painted_path = _write_coverage(tmp_path, _valid_coverage_dict(), "painted.yaml")
    painted = config.load_coverage(painted_path)
    assert painted.purpose == "production"
    assert painted.relief_encoding == "painted-rgba"
    assert painted.webp_quality == 80
    assert painted.variant == "painted"
    assert painted.peninsula_outline is None
    target = painted.as_build_target()
    assert target.camera_bounds == painted.camera_bounds
    assert target.data_extent == painted.data_extent
    assert target.max_zoom == 12
    assert target.block_zoom == 11
    assert target.fade_deg == pytest.approx(0.02)

    terrarium_data = _valid_coverage_dict(relief_encoding="terrarium-value")
    terrarium_data.pop("webp_quality")
    terrarium_path = _write_coverage(tmp_path, terrarium_data, "terrarium.yaml")
    terrarium = config.load_coverage(terrarium_path)
    assert terrarium.relief_encoding == "terrarium-value"
    assert terrarium.webp_quality == 80
    assert terrarium.variant == "value"


def test_load_coverage_example_production_fixture():
    repo_root = Path(__file__).resolve().parents[3]
    cov_path = repo_root / "map-data" / "example-production" / "coverage.yaml"
    with pytest.raises(ConfigError, match="min_data_margin_deg"):
        config.load_coverage(cov_path, data_root=repo_root / "map-data")
    cov = config.load_coverage(cov_path, data_root=repo_root / "map-data", allow_zero_margin=True)
    assert cov.purpose == "production"
    assert (
        cov.peninsula_outline
        == (repo_root / "map-data" / "example" / "peninsula.geojson").resolve()
    )
    cleanup = config.load_cleanup(repo_root / "map-data" / "example-production" / "cleanup.yaml")
    assert [e.id for e in cleanup] == ["remove-synthetic-pier", "draft-synthetic-basin"]


def test_load_coverage_webp_quality_rules(tmp_path: Path):
    missing_q = _valid_coverage_dict(relief_encoding="painted-rgba")
    missing_q.pop("webp_quality")
    with pytest.raises(ConfigError):
        config.load_coverage(_write_coverage(tmp_path, missing_q, "missing_q.yaml"))

    forbidden_q = _valid_coverage_dict(relief_encoding="terrarium-value", webp_quality=80)
    with pytest.raises(ConfigError):
        config.load_coverage(_write_coverage(tmp_path, forbidden_q, "forbidden_q.yaml"))


def test_load_coverage_margin_and_fade_rules(tmp_path: Path):
    zero_margin = _valid_coverage_dict(
        camera_bounds={"west": 58.80, "south": 20.50, "east": 59.05, "north": 20.75},
        data_extent={"west": 58.80, "south": 20.50, "east": 59.05, "north": 20.75},
        min_data_margin_deg=0.0,
        fade_deg=0.0,
    )
    path = _write_coverage(tmp_path, zero_margin, "zero.yaml")
    with pytest.raises(ConfigError, match="--allow-zero-margin"):
        config.load_coverage(path, allow_zero_margin=False)
    cov = config.load_coverage(path, allow_zero_margin=True)
    assert cov.min_data_margin_deg == 0.0

    insufficient_margin = _valid_coverage_dict(min_data_margin_deg=0.10)
    with pytest.raises(ConfigError, match="enclosing camera_bounds widened by min_data_margin_deg"):
        config.load_coverage(_write_coverage(tmp_path, insufficient_margin, "insuff.yaml"))

    asymmetric_zero_side = _valid_coverage_dict(
        camera_bounds={"west": 58.80, "south": 20.55, "east": 59.00, "north": 20.70},
        data_extent={"west": 58.80, "south": 20.50, "east": 59.05, "north": 20.75},
        min_data_margin_deg=0.0,
        fade_deg=0.01,
    )
    with pytest.raises(ConfigError, match="fade_deg"):
        config.load_coverage(
            _write_coverage(tmp_path, asymmetric_zero_side, "asym.yaml"),
            allow_zero_margin=True,
        )


@pytest.mark.parametrize(
    "overrides,match",
    [
        ({"camera_min_zoom": 14.0, "camera_max_zoom": 14.0}, "camera_min_zoom"),
        ({"camera_min_zoom": 12.2, "regional_max_zoom": 12}, "regional_max_zoom"),
        ({"regional_max_zoom": 10, "block_zoom": 11}, "block_zoom"),
    ],
)
def test_load_coverage_zoom_and_base_coverage_errors(tmp_path: Path, overrides: dict, match: str):
    data = _valid_coverage_dict(**overrides)
    with pytest.raises(ConfigError, match=match):
        config.load_coverage(_write_coverage(tmp_path, data))


def test_build_and_validate_map_json(tmp_path: Path):
    painted = config.load_coverage(_write_coverage(tmp_path, _valid_coverage_dict(), "p.yaml"))
    painted_json = config.build_map_json(painted)
    assert painted_json["tile_schema"] == 1
    assert painted_json["sources"]["base"] == {
        "file": "base.pmtiles",
        "layers": ["land", "coast"],
        "min_zoom": 0,
        "max_zoom": 12,
    }
    assert painted_json["sources"]["relief"] == {
        "file": "relief.pmtiles",
        "encoding": "painted-rgba",
        "tile_format": "webp",
        "tile_size": 256,
        "min_zoom": 0,
        "max_zoom": 12,
    }
    assert painted_json["reconstructed_areas"] == []
    credits_by_id = {c["id"]: c for c in painted_json["credits"]}
    assert credits_by_id["copernicus-worlddem-30"]["attribution"] == config.COPERNICUS_ATTRIBUTION
    assert credits_by_id["copernicus-worlddem-30"]["liability"] == config.COPERNICUS_LIABILITY
    assert credits_by_id["natural-earth"]["attribution"] == config.NATURAL_EARTH_ATTRIBUTION

    terrarium_data = _valid_coverage_dict(relief_encoding="terrarium-value")
    terrarium_data.pop("webp_quality")
    terrarium = config.load_coverage(_write_coverage(tmp_path, terrarium_data, "t.yaml"))
    terrarium_json = config.build_map_json(terrarium)
    assert terrarium_json["sources"]["relief"]["encoding"] == "terrarium-value"
    assert terrarium_json["sources"]["relief"]["tile_format"] == "png"


def test_validate_map_json_general_bounds_and_rejections(tmp_path: Path):
    import copy

    cov = config.load_coverage(_write_coverage(tmp_path, _valid_coverage_dict()))
    base_doc = config.build_map_json(cov)

    western_doc = copy.deepcopy(base_doc)
    western_doc["camera_bounds"] = {"west": -10.0, "south": -5.0, "east": 15.0, "north": 25.0}
    western_doc["data_extent"] = {"west": -12.0, "south": -6.0, "east": 16.0, "north": 26.0}
    config.validate_map_json(western_doc)

    bad_west_east = copy.deepcopy(base_doc)
    bad_west_east["camera_bounds"]["west"] = 60.0
    with pytest.raises(ConfigError, match="west < east"):
        config.validate_map_json(bad_west_east)

    bad_extent = copy.deepcopy(base_doc)
    bad_extent["data_extent"]["east"] = 58.90
    with pytest.raises(ConfigError, match="data_extent"):
        config.validate_map_json(bad_extent)

    bad_zoom = copy.deepcopy(base_doc)
    bad_zoom["min_zoom"] = 14.0
    bad_zoom["max_zoom"] = 14.0
    with pytest.raises(ConfigError, match="min_zoom"):
        config.validate_map_json(bad_zoom)

    bad_reconstructed = copy.deepcopy(base_doc)
    bad_reconstructed["reconstructed_areas"] = [{"id": "not-yet-specified"}]
    with pytest.raises(ConfigError):
        config.validate_map_json(bad_reconstructed)

    bad_format = copy.deepcopy(base_doc)
    bad_format["sources"]["relief"]["tile_format"] = "png"
    with pytest.raises(ConfigError):
        config.validate_map_json(bad_format)

    missing_credit = copy.deepcopy(base_doc)
    missing_credit["credits"] = [
        c for c in base_doc["credits"] if c["id"] != "copernicus-worlddem-30"
    ]
    with pytest.raises(ConfigError):
        config.validate_map_json(missing_credit)
