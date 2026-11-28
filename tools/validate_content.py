#!/usr/bin/env python3
"""Validate legacy JSON or v1 YAML content and optionally compile a YAML corpus."""

import argparse
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


def validate_legacy(root: pathlib.Path) -> int:
    """Check the legacy index without loading YAML dependencies."""
    errors.clear()
    index_path = root / "index" / "phases.json"
    index = load(index_path)
    if index is None:
        return report(False)
    used_schema = schema_check(index, index_path)

    if errors:
        return report(used_schema)

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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=pathlib.Path, default=DEFAULT_CONTENT)
    parser.add_argument("--compile", type=pathlib.Path)
    parser.add_argument("--require-approved", action="store_true")
    parser.add_argument("--strict-warnings", action="store_true")
    parser.add_argument("--allow-dirty", action="store_true")
    parser.add_argument("--source-commit")
    args = parser.parse_args()
    legacy = (args.root / "index/phases.json").is_file()
    yaml = (args.root / "content.yaml").is_file()
    if legacy == yaml:
        print("error: expected exactly one of content.yaml and index/phases.json", file=sys.stderr)
        return 1
    if legacy:
        print(
            "warning: legacy phases.json format is deprecated; migrate to YAML 1.2", file=sys.stderr
        )
        if (
            args.compile
            or args.require_approved
            or args.strict_warnings
            or args.allow_dirty
            or args.source_commit
        ):
            parser.error("compilation and release options require a YAML source")
        return validate_legacy(args.root)
    sys.path.insert(0, str(REPO / "tools/content/src"))
    from seerah_content.compiler import compile_source, write_payload
    from seerah_content.provenance import source_revision

    try:
        if args.require_approved or args.source_commit:
            source_revision(args.root, args.allow_dirty, args.source_commit)
        docs, warnings = compile_source(
            args.root,
            require_approved=args.require_approved,
            validate_package=bool(args.compile),
            strict_warnings=args.strict_warnings,
        )
        for warning in warnings:
            print(f"warning: {warning}", file=sys.stderr)
        if args.compile:
            write_payload(docs, args.compile / "data")
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"ok: {len(docs['events']['events'])} events")
    return 0


if __name__ == "__main__":
    sys.exit(main())
