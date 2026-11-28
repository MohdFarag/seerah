"""End-to-end checks for authoring, normalization, and legacy conversion."""

import copy
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
from seerah_content.compiler import compile_source, write_payload
from seerah_content.loader import validator
from seerah_content.migrate_legacy import migrate
from seerah_content.strict_yaml import load

ROOT = Path(__file__).resolve().parents[3]
YEAR = "years/01-bh-02.yaml"
STORY = "stories/example-story.yaml"


@pytest.fixture
def corpus(tmp_path):
    return shutil.copytree(ROOT / "content/example", tmp_path / "source")


def change(root, name, edit):
    doc = load(root / name)
    edit(doc)
    (root / name).write_text(json.dumps(doc))


def test_example_compiles_deterministically(corpus, tmp_path):
    payload, warnings = compile_source(corpus)
    assert not warnings
    for name, doc in payload.items():
        validator("package", name).validate(doc)
    assert payload["meta"]["min_timeline_point"] == {"calendar": "pre-hijra", "year": 2}
    assert payload["places"]["places"][0]["states"][0]["start"] == "baseline"
    assert "review" not in str(payload)
    first, second = tmp_path / "first", tmp_path / "second"
    write_payload(payload, first)
    write_payload(compile_source(corpus)[0], second)
    assert len(list(first.iterdir())) == 8
    assert all(f.read_bytes() == (second / f.name).read_bytes() for f in first.iterdir())
    with pytest.raises(ValueError, match="approval"):
        compile_source(corpus, require_approved=True)


@pytest.mark.parametrize(
    ("file", "edit", "error"),
    [
        (YEAR, lambda d: d["events"][0].update(parent_id="example-first-event"), "cycle"),
        (
            YEAR,
            lambda d: d["events"][0].update(
                related=[{"target_event_id": "example-second-event", "type": "continues"}]
            ),
            "cycle",
        ),
        (YEAR, lambda d: d["events"][0]["date"].update(year=1), "bucket"),
        (YEAR, lambda d: d.update(calendar="gregorian"), "calendar"),
        (YEAR, lambda d: d["events"][0].update(review="approved"), "approved event"),
        (YEAR, lambda d: d["events"][0].update(event_text_ids=["absent"]), "unknown texts"),
        (
            YEAR,
            lambda d: d["events"][0].update(where={"kind": "route", "accuracy": "general"}),
            "missing route",
        ),
        ("world/places.yaml", lambda d: d["places"][0].pop("coordinates"), "coordinates"),
        (
            "world/areas.yaml",
            lambda d: d["areas"].append(
                {
                    "id": "area",
                    "name": "Synthetic",
                    "review": "draft",
                    "polygon_ring": [{"lat": 20.6, "lon": 58.91 + n * 0.01} for n in range(4)],
                }
            ),
            "closed",
        ),
        ("texts/example-first-event.yaml", lambda d: d.update(event_id="absent"), "filename"),
        (
            STORY,
            lambda d: d["chapters"][0]["scenes"][0]["steps"][0].update(highlight=["area:absent"]),
            "unknown areas",
        ),
    ],
)
def test_validation_errors(corpus, file, edit, error):
    change(corpus, file, edit)
    with pytest.raises(ValueError, match=error):
        compile_source(corpus)


def test_state_and_story_time_lapse(corpus):
    def lapse(doc):
        step = doc["chapters"][0]["scenes"][0]["steps"][0]
        point = {k: v for k, v in step["time"].items() if k != "mode"}
        step["time"] = {
            "mode": "time_lapse",
            "from": point,
            "to": point | {"calendar": "hijri", "year": 1},
        }

    change(corpus, STORY, lapse)
    (corpus / "years/next.yaml").write_text(
        json.dumps(
            {
                "calendar": "pre-hijra",
                "year": 1,
                "place_changes": [
                    {
                        "place_id": "example-port",
                        "from": {"precision": "year"},
                        "exists": False,
                        "review": "draft",
                    }
                ],
            }
        )
    )
    with pytest.raises(ValueError, match="disappears"):
        compile_source(corpus)


def test_story_warning_and_editorial_exception(corpus):
    def another_step(doc):
        steps = doc["chapters"][0]["scenes"][0]["steps"]
        step = copy.deepcopy(steps[0])
        step.update(id="next", order=2)
        step["time"]["year"] = 1
        step["camera"]["targets"] = ["event:example-first-event"]
        steps.append(step)

    change(corpus, STORY, another_step)
    assert len(compile_source(corpus)[1]) == 1
    with pytest.raises(ValueError, match="time and camera"):
        compile_source(corpus, strict_warnings=True)
    change(
        corpus,
        STORY,
        lambda d: d["chapters"][0]["scenes"][0]["steps"][1].update(
            review_notes={"accept_multi_change": "Reviewed transition"}
        ),
    )
    assert not compile_source(corpus, strict_warnings=True)[1]


def legacy(root):
    path = root / "index/phases.json"
    path.parent.mkdir(parents=True)
    events = [
        {
            "id": f"draft-{n}",
            "phase_id": "phase",
            "order": n,
            "title": "Synthetic",
            "review": "draft",
        }
        for n in (1, 2, 3)
    ]
    events[0]["related_event_ids"] = ["draft-2", "draft-3"]
    path.write_text(
        json.dumps(
            {
                "content_version": "sample",
                "phases": [
                    {"id": "phase", "title": "Synthetic phase", "order": 1, "events": events}
                ],
            }
        )
    )
    return path


def test_migration_requires_each_relation_and_preserves_original(tmp_path):
    source = tmp_path / "legacy"
    original = legacy(source)
    before = original.read_bytes()
    out = tmp_path / "new"
    with pytest.raises(ValueError, match="draft-1/draft-2, draft-1/draft-3"):
        migrate(source, out, {})
    assert not out.exists()
    migrate(
        source, out, {"relations": {"draft-1/draft-2": "cause-of", "draft-1/draft-3": "continues"}}
    )
    events = load(out / "years/undated.yaml")["events"]
    assert [r["type"] for r in events[0]["related"]] == ["cause-of", "continues"]
    assert all(e["review"] == "draft" and e["date"] == {"precision": "unknown"} for e in events)
    assert original.read_bytes() == before
    with pytest.raises(ValueError, match="new directory"):
        migrate(source, out, {})


def test_legacy_cli_does_not_import_yaml(tmp_path):
    source = tmp_path / "legacy"
    legacy(source)
    # Simulate the private CI environment: importing either YAML-only dependency must fail.
    for package in ("ruamel", "seerah_content"):
        (tmp_path / f"{package}.py").write_text("raise AssertionError('unexpected YAML import')")

    def run():
        return subprocess.run(
            [sys.executable, str(ROOT / "tools/validate_content.py"), str(source)],
            capture_output=True,
            text=True,
            env=os.environ | {"PYTHONPATH": str(tmp_path)},
        )

    assert run().returncode == 0
    assert "deprecated" in run().stderr
    (source / "content.yaml").touch()
    assert run().returncode == 1
    (source / "content.yaml").unlink()
    (source / "index/phases.json").unlink()
    assert run().returncode == 1


def test_provenance_rejects_ignored_sources(corpus):
    from seerah_content.provenance import source_revision

    def git(*args):
        return subprocess.run(
            ["git", "-C", str(corpus), *args], check=True, capture_output=True, text=True
        )

    git("init")
    git("add", ".")
    git(
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "commit",
        "-m",
        "Fixture",
    )
    assert source_revision(corpus, False, None) == git("rev-parse", "HEAD").stdout.strip()
    (corpus / ".git/info/exclude").write_text("years/ignored.yaml\n")
    (corpus / "years/ignored.yaml").write_text("year: 1\n")
    with pytest.raises(ValueError, match="tracked regular file"):
        source_revision(corpus, False, None)


def test_route_waypoint_alignment(corpus):
    route = {
        "id": "route-one",
        "documented": True,
        "stations": ["example-port", "example-port"],
        "coordinates": [{"lat": 20.62, "lon": 58.91}, {"lat": 20.63, "lon": 58.92}],
        "station_waypoint_indices": [0, 1],
        "accuracy_note": "Synthetic test line",
    }
    change(corpus, YEAR, lambda d: d["events"][0].update(route=route))
    payload, _ = compile_source(corpus, validate_package=True)
    assert payload["routes"]["routes"][0]["event_id"] == "example-first-event"
    route["station_waypoint_indices"] = [1, 0]
    change(corpus, YEAR, lambda d: d["events"][0].update(route=route))
    with pytest.raises(ValueError, match="waypoint"):
        compile_source(corpus)
