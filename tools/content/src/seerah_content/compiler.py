"""Normalize authoring documents into deterministic v1 payloads."""

import json
from collections import defaultdict
from pathlib import Path

from .checks import check_corpus
from .loader import documents, validator
from .models import chronological

AUTHOR_FIELDS = {"review", "note", "preliminary_date_note", "review_notes"}


def _public(row):
    return {key: value for key, value in row.items() if key not in AUTHOR_FIELDS}


def _ordered(rows):
    return sorted(rows, key=lambda row: (row["order"], row["id"]))


def compile_source(
    root: Path, *, require_approved=False, strict_warnings=False, validate_package=False
):
    """Validate a complete corpus and return its eight payload documents and warnings."""

    rows = {
        key: [] for key in ("places", "areas", "sources", "events", "routes", "texts", "stories")
    }
    changes = defaultdict(list)
    years = set()
    points = []
    approved_events = set()
    text_files = set()

    def checked(row):
        if require_approved and row["review"] != "approved":
            raise ValueError(f"{row.get('id', row)}: release requires owner approval")
        return _public(row)

    def place_or_area(role, row, start):
        item = checked(row)
        item.pop("from", None)
        item["states"] = [{"start": start, "exists": True, "name": item.pop("name")}]
        rows[role].append(item)

    for role, path, doc in documents(root):
        if role == "content":
            meta = doc.copy()
            meta["phases"] = [_public(p) for p in _ordered(doc["phases"])]
        elif role in ("places", "areas"):
            for row in doc[role]:
                place_or_area(role, row, "baseline")
        elif role == "sources":
            rows[role].extend(checked(row) for row in doc[role])
        elif role in ("year", "undated"):
            bucket = {k: doc[k] for k in ("calendar", "year") if k in doc}
            if bucket:
                key = tuple(bucket.values())
                if key in years:
                    raise ValueError(f"{path}: duplicate year")
                years.add(key)
            for row in doc.get("new_places", []):
                start = bucket | row["from"]
                points.append(start)
                place_or_area("places", row, start)
            for group, name in (("places", "place"), ("areas", "area")):
                for row in doc.get(f"{name}_changes", []):
                    checked(row)
                    start = bucket | row["from"]
                    changes[group, row[f"{name}_id"]].append((start, row))
                    points.append(start)
            for row in doc.get("events", []):
                event = checked(row)
                if row["review"] == "approved":
                    approved_events.add(row["id"])
                date = event["date"]
                if (date["precision"] == "unknown") != (role == "undated"):
                    raise ValueError(f"{path}: date precision does not match year bucket")
                if any(k in date and date[k] != v for k, v in bucket.items()):
                    raise ValueError(f"{path}: date conflicts with year bucket")
                date = event["date"] = bucket | date
                if date.get("prophethood_year") and date.get("calendar") != "pre-hijra":
                    raise ValueError(f"{path}: prophethood_year needs pre-hijra")
                if not bucket and "calendar" in date and "prophethood_year" not in date:
                    raise ValueError(
                        f"{path}: unknown date has a calendar without prophethood_year"
                    )
                if bucket:
                    points.append(date)
                if route := event.pop("route", None):
                    rows["routes"].append(route | {"event_id": event["id"]})
                event["situations"] = _ordered(event.get("situations", []))
                rows["events"].append(event)
        elif role == "texts":
            text_files.add(doc["event_id"])
            rows["texts"].extend(checked(t) | {"event_id": doc["event_id"]} for t in doc["texts"])
        elif role == "story":
            story = checked(doc)
            story["chapters"] = _ordered(story["chapters"])
            for chapter in story["chapters"]:
                chapter["scenes"] = _ordered(chapter["scenes"])
                for scene in chapter["scenes"]:
                    scene["steps"] = _ordered(scene["steps"])
            rows["stories"].append(story)
    for group in ("places", "areas"):
        for row in rows[group]:
            states = row["states"]
            for start, change in sorted(
                changes.pop((group, row["id"]), []), key=lambda pair: chronological(pair[0])
            ):
                if states[-1]["start"] != "baseline" and chronological(start) < chronological(
                    states[-1]["start"]
                ):
                    raise ValueError(f"{row['id']}: change precedes first state")
                states.append(
                    states[-1]
                    | {"start": start}
                    | {k: change[k] for k in ("name", "exists", "kind_override") if k in change}
                )
    if changes:
        raise ValueError(f"changes reference missing entities: {list(changes)}")
    for key, fn in (("min", min), ("max", max)):
        bound = fn(points, key=chronological) if points else {}
        meta[f"{key}_timeline_point"] = (
            {k: bound[k] for k in ("calendar", "year")} if bound else None
        )
    warnings = check_corpus(meta, rows, approved_events, text_files)
    if warnings and strict_warnings:
        raise ValueError("\n".join(warnings))
    for group, items in rows.items():
        items.sort(key=lambda r: r["id"])
        if group == "texts":
            items.sort(key=lambda r: "excerpt_of" in r)
        if group == "events":
            items.sort(key=lambda r: (r["phase_id"], r["order"]))
    # Resolve cumulative values chronologically first. The wire order groups precision
    # within each year, with stable source order for changes at the same point.
    for entity in rows["areas"] + rows["places"]:
        entity["states"].sort(key=_wire_state_order)
    payload = {"meta": meta, **{group: {group: items} for group, items in rows.items()}}
    if require_approved or validate_package:
        for name, doc in payload.items():
            error = next(validator("package", name).iter_errors(doc), None)
            if error:
                raise ValueError(f"{name}{error.json_path}: {error.message}")
    return payload, warnings


def _wire_state_order(state):
    point = state["start"]
    if point == "baseline":
        return (0, 0, 0, 0, 0)
    year, month, day = chronological(point)
    return (1, year, ("year", "month", "day").index(point["precision"]), month, day)


def write_payload(payload: dict, directory: Path):
    """Write UTF-8 package entries beneath a staging directory."""
    directory.mkdir(parents=True, exist_ok=True)
    for name, doc in payload.items():
        (directory / f"{name}.json").write_text(
            json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
