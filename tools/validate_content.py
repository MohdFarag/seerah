#!/usr/bin/env python3
"""Validate Seerah content.

Usage: validate_content.py [CONTENT_DIR]

CONTENT_DIR holds `index/phases.json` and `events/`. The content itself lives in the
private `seerah-content` repository (ADR 0006); without an argument the synthetic
example in `content/example/` is checked, so the validator is tested in this repository.

Uses the JSON Schema (`content/schema/`) if the `jsonschema` package is installed, and
always runs the cross-reference and integrity checks the schema can't express.
Exit code 1 on any error.
"""

import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = REPO / "content" / "schema" / "content.schema.json"
DEFAULT_CONTENT = REPO / "content" / "example"
errors: list[str] = []


def load(p: pathlib.Path):
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        errors.append(f"{p}: invalid JSON: {e}")
        return None


def schema_check(doc, path):
    try:
        import jsonschema
    except ImportError:
        return False
    schema = load(SCHEMA)
    v = jsonschema.Draft202012Validator(schema)
    for err in v.iter_errors(doc):
        loc = "/".join(map(str, err.absolute_path))
        errors.append(f"{path}: {loc}: {err.message}")
    return True


def main(root: pathlib.Path) -> int:
    index_path = root / "index" / "phases.json"
    index = load(index_path)
    if index is None:
        return report(False)
    used_schema = schema_check(index, index_path)

    ids: dict[str, str] = {}
    events = {}
    for ph in index.get("phases", []):
        for key in [ph["id"]]:
            if key in ids:
                errors.append(f"duplicate id: {key}")
            ids[key] = "phase"
        for ev in ph.get("events", []):
            if ev["id"] in ids:
                errors.append(f"duplicate id: {ev['id']}")
            ids[ev["id"]] = "event"
            events[ev["id"]] = ev
            if ev.get("phase_id") != ph["id"]:
                errors.append(f"{ev['id']}: phase_id {ev.get('phase_id')!r} != {ph['id']!r}")

    for eid, ev in events.items():
        for r in ev.get("related_event_ids", []):
            if r not in events:
                errors.append(f"{eid}: related_event_ids -> unknown event {r!r}")

    # Full event files live in <content>/events/<event-id>.json
    for p in sorted((root / "events").glob("*.json")):
        ev = load(p)
        if ev is None:
            continue
        if p.stem not in events:
            errors.append(f"{p.name}: no event with id {p.stem!r} in index")
        text_ids = {t["id"] for t in ev.get("texts", [])}
        for t in ev.get("texts", []):
            if t.get("excerpt_of") and t["excerpt_of"] not in text_ids:
                errors.append(f"{p.name}: text {t['id']} excerpt_of unknown {t['excerpt_of']!r}")
        for s in ev.get("situations", []):
            for tid in s.get("text_ids", []):
                if tid not in text_ids:
                    errors.append(f"{p.name}: situation {s['id']} -> unknown text {tid!r}")
        for tid in ev.get("event_text_ids", []):
            if tid not in text_ids:
                errors.append(f"{p.name}: event_text_ids -> unknown text {tid!r}")

    return report(used_schema, len(events))


def report(used_schema: bool, n: int = 0) -> int:
    if not used_schema:
        print("note: `pip install jsonschema` to also run full schema validation")
    if errors:
        print("\n".join(f"error: {e}" for e in errors))
        return 1
    print(f"ok: {n} events")
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(pathlib.Path(sys.argv[1]) if len(sys.argv) == 2 else DEFAULT_CONTENT))
