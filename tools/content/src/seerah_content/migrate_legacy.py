"""Convert a legacy JSON corpus to a new, separately reviewed YAML tree."""

import argparse
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path
from tempfile import TemporaryDirectory

from jsonschema import Draft202012Validator
from ruamel.yaml import YAML

from .compiler import compile_source
from .loader import SCHEMAS
from .strict_yaml import load


def _read(path):
    def distinct(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"{path}: duplicate key {key}")
            result[key] = value
        return result

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=distinct)


def migrate(source: Path, destination: Path, mapping: dict):
    """Build and validate a separate output tree. Publish it only after every check passes."""
    source = source.resolve()
    destination = destination.resolve()
    if destination.exists() or destination.is_relative_to(source):
        raise ValueError("destination must be a new directory outside the source tree")
    index = _read(source / "index/phases.json")
    schema = json.loads((SCHEMAS / "content.schema.json").read_text())
    error = next(Draft202012Validator(schema).iter_errors(index), None)
    if error:
        raise ValueError(f"{error.json_path}: {error.message}")
    if not isinstance(mapping, dict) or not isinstance(mapping.get("relations", {}), dict):
        raise ValueError("migration mapping and relations must be mappings")
    if set(mapping) - {"relations", "places", "areas", "routes"}:
        raise ValueError("unknown migration mapping section")
    files = {}
    works = {}
    buckets = defaultdict(list)
    relations = mapping.get("relations", {})
    mapped = set()
    missing = []
    known = set()
    details = {p.stem: _read(p) for p in sorted((source / "events").glob("*.json"))}
    for phase in index["phases"]:
        for row in phase.pop("events"):
            eid = row["id"]
            if eid in known:
                raise ValueError(f"duplicate event ID: {eid}")
            known.add(eid)
            detail = details.pop(eid, {})
            for field, val in detail.items():
                if field in row and row[field] != val:
                    raise ValueError(f"{eid}: conflicting index and event values for {field}")
            row.update(detail)
            if row["id"] != eid:
                raise ValueError(f"{eid}: filename and ID differ")
            if row["phase_id"] != phase["id"]:
                raise ValueError(f"{eid}: conflicting phase ID")
            related = []
            for target in row.pop("related_event_ids", []):
                key = f"{eid}/{target}"
                mapped.add(key)
                if key not in relations:
                    missing.append(key)
                else:
                    related.append({"target_event_id": target, "type": relations[key]})
            if related:
                row["related"] = related
            if place := row.pop("place", None):
                row["where"] = (
                    {"kind": "unknown"}
                    if place["accuracy"] == "unknown"
                    else {"kind": "single_place", **place}
                )
            if route := row.get("route"):
                route.setdefault("id", eid)
                geometry = route.pop("geometry_ref", None)
                if geometry:
                    if geometry not in mapping.get("routes", {}):
                        raise ValueError(
                            f"{eid}: supply reviewed coordinates and station indices for {geometry}"
                        )
                    supplied = mapping["routes"][geometry]
                    if not isinstance(supplied, dict) or set(supplied) != {
                        "coordinates",
                        "station_waypoint_indices",
                    }:
                        raise ValueError(
                            f"{eid}: geometry needs coordinates and station_waypoint_indices"
                        )
                    route.update(supplied)
            for order, situation in enumerate(row.get("situations", []), 1):
                situation.setdefault("order", order)
            texts = row.pop("texts", [])
            for text in texts:
                text.setdefault("review", "draft")
                for field in ("source", "explanation_source"):
                    if citation := text.get(field):
                        work = {
                            k: v for k, v in citation.items() if k in ("kind", "work", "edition")
                        }
                        if set(citation) - set(work) - {"locator", "url"}:
                            raise ValueError(f"{eid}: unsupported citation fields")
                        slug = hashlib.sha256(json.dumps(work, sort_keys=True).encode()).hexdigest()
                        work_id = f"work-{slug}"
                        works[work_id] = {"id": work_id, **work, "review": "draft"}
                        text[field] = {
                            "source_work_id": work_id,
                            **{k: v for k, v in citation.items() if k not in work},
                        }
            if texts:
                files[f"texts/{eid}.yaml"] = {"event_id": eid, "texts": texts}
            date = row.setdefault("date", {"precision": "unknown"})
            bucket = (
                None if date["precision"] == "unknown" else (date.get("calendar"), date.get("year"))
            )
            if bucket and (
                bucket[0] not in ("pre-hijra", "hijri")
                or type(bucket[1]) is not int
                or bucket[1] < 1
            ):
                raise ValueError(f"{eid}: dated event needs a supported calendar and positive year")
            buckets[bucket].append(row)
    if details:
        raise ValueError(f"event files missing from index: {sorted(details)}")
    if missing:
        raise ValueError("supply explicit relation types for: " + ", ".join(sorted(missing)))
    if set(relations) - mapped:
        raise ValueError(f"unused relation mappings: {sorted(set(relations) - mapped)}")
    files["content.yaml"] = {"content_schema": 1, **index}
    for bucket, events in buckets.items():
        filename = "undated" if bucket is None else f"{bucket[0]}-{bucket[1]}"
        files[f"years/{filename}.yaml"] = {
            **({"calendar": bucket[0], "year": bucket[1]} if bucket else {}),
            "events": events,
        }
    for group in ("areas", "places"):
        files[f"world/{group}.yaml"] = {group: mapping.get(group, [])}
    files["world/sources.yaml"] = {"sources": list(works.values())}
    yaml = YAML(typ="safe", pure=True)
    yaml.default_flow_style = False
    yaml.allow_unicode = True
    with TemporaryDirectory(dir=destination.parent) as temp:
        tree = Path(temp) / "tree"
        for name, doc in files.items():
            path = tree / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("w", encoding="utf-8") as handle:
                yaml.dump(doc, handle)
        compile_source(tree)
        tree.rename(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--mapping", type=Path)
    args = parser.parse_args()
    try:
        migrate(args.source, args.destination, load(args.mapping) if args.mapping else {})
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
