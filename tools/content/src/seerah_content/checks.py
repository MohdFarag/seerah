"""Validate references and editorial rules across source documents."""

from graphlib import CycleError, TopologicalSorter

from .models import chronological


def _unique(items, label):
    seen = set()
    for item in items:
        if item in seen:
            raise ValueError(f"duplicate {label}: {item}")
        seen.add(item)
    return seen


def _sequence(items, label):
    _unique((row["order"] for row in items), f"{label} order")
    _unique((row["id"] for row in items), f"{label} ID")


def check_corpus(meta, rows, approved_events, text_files):
    """Check cross-file integrity and return review warnings in source order."""
    _sequence(meta["phases"], "phase")
    tables = {}
    for name, items in (rows | {"phases": meta["phases"]}).items():
        _unique((row["id"] for row in items), name)
        tables[name] = {row["id"]: row for row in items}

    def lookup(group, key):
        if key not in tables[group]:
            raise ValueError(f"unknown {group} ID: {key}")
        return tables[group][key]

    for row in rows["places"]:
        known = row["accuracy"] != "unknown"
        if ("coordinates" in row and not known) or (
            known and not row.get("off_map") and "coordinates" not in row
        ):
            raise ValueError(f"{row['id']}: coordinates disagree with accuracy")
    for row in rows["areas"]:
        ring = row.get("polygon_ring")
        if ring and ring[0] != ring[-1]:
            raise ValueError(f"{row['id']}: polygon ring must be closed")
    routes = {}
    for route in rows["routes"]:
        routes[route["event_id"]] = route
        for place in route["stations"]:
            lookup("places", place)
        for key in ("coordinates", "station_waypoint_indices", "accuracy_note"):
            if (key in route) != route["documented"]:
                raise ValueError(f"{route['id']}: invalid {key} for route documentation")
        if route["documented"]:
            indices = route["station_waypoint_indices"]
            if (
                len(indices) != len(route["stations"])
                or indices != sorted(indices)
                or max(indices) >= len(route["coordinates"])
            ):
                raise ValueError(f"{route['id']}: invalid station waypoint indices")
        if ("distance_km" in route) != ("distance_basis" in route):
            raise ValueError(f"{route['id']}: distance needs its basis")
    for key in text_files:
        lookup("events", key)
    for t in rows["texts"]:
        if bool(t.get("source")) != (t["kind"] != "editorial"):
            raise ValueError(f"{t['id']}: invalid source for text kind")
        for key in ("source", "explanation_source"):
            if citation := t.get(key):
                lookup("sources", citation["source_work_id"])
        if "explanation_source" in t and "explanation" not in t:
            raise ValueError(f"{t['id']}: missing explanation")
        if "excerpt_of" in t:
            origin = lookup("texts", t["excerpt_of"])
            if "excerpt_of" in origin or "editorial" in (t["kind"], origin["kind"]):
                raise ValueError(f"{t['id']}: invalid excerpt")
    graphs = [TopologicalSorter(), TopologicalSorter()]
    event_orders = []
    for e in rows["events"]:
        eid = e["id"]
        lookup("phases", e["phase_id"])
        date = e["date"]
        event_orders.append(
            (
                e["phase_id"],
                e.get("parent_id"),
                date.get("calendar"),
                date.get("year"),
                date.get("month"),
                date.get("day"),
                e["order"],
            )
        )
        if "expedition_role" in e and e.get("kind") != "expedition":
            raise ValueError(f"{eid}: invalid expedition_role")
        for graph, refs in zip(
            graphs,
            (
                [e["parent_id"]] if "parent_id" in e else [],
                [r["target_event_id"] for r in e.get("related", [])],
            ),
            strict=True,
        ):
            for key in refs:
                lookup("events", key)
            graph.add(eid, *refs)
        where = e.get("where", {})
        for key in where.get("place_ids", []) + (
            [where["place_id"]] if "place_id" in where else []
        ):
            if lookup("places", key)["accuracy"] == "unknown" and where["accuracy"] == "exact":
                raise ValueError(f"{eid}: cannot claim an exact place")
        if "area_id" in where:
            lookup("areas", where["area_id"])
        if where.get("kind") == "route" and eid not in routes:
            raise ValueError(f"{eid}: missing route")
        _sequence(e["situations"], eid)
        for t in e.get("event_text_ids", []):
            lookup("texts", t)
        for sit in e["situations"]:
            if p := sit.get("place", {}).get("place_id"):
                lookup("places", p)
            for t in sit["text_ids"]:
                lookup("texts", t)
        if eid in approved_events and (
            not e.get("lead_in")
            or not e.get("outcome")
            or not e["situations"]
            or not any(t["event_id"] == eid for t in rows["texts"])
        ):
            raise ValueError(f"{eid}: approved event needs lead_in, outcome, situations and texts")
    _unique(event_orders, "event order")
    _unique((s["id"] for e in rows["events"] for s in e["situations"]), "situation ID")
    chapters = [chapter for story in rows["stories"] for chapter in story["chapters"]]
    scenes = [scene for chapter in chapters for scene in chapter["scenes"]]
    _unique((chapter["id"] for chapter in chapters), "chapter ID")
    _unique((scene["id"] for scene in scenes), "scene ID")
    _unique((step["id"] for scene in scenes for step in scene["steps"]), "step ID")
    try:
        for graph in graphs:
            graph.prepare()
    except CycleError as exc:
        raise ValueError(f"event cycle: {exc.args[1]}") from exc
    warnings = []
    for story in rows["stories"]:
        _sequence(story["chapters"], story["id"])
        for chapter in story["chapters"]:
            if "phase_id" in chapter:
                lookup("phases", chapter["phase_id"])
            _sequence(chapter["scenes"], chapter["id"])
            for scene in chapter["scenes"]:
                if scene.get("event_id"):
                    lookup("events", scene["event_id"])
                _sequence(scene["steps"], scene["id"])
                previous = None
                for step in scene["steps"]:
                    _check_step(step, lookup, routes, rows)
                    camera = step["camera"]
                    note = step.pop("review_notes", {}).get("accept_multi_change")
                    if (
                        previous
                        and previous["time"] != step["time"]
                        and previous["camera"].get("targets") != camera.get("targets")
                        and camera["mode"] != "follow"
                        and "show_movement" not in step
                        and not note
                    ):
                        warnings.append(f"{step['id']}: time and camera targets change together")
                    previous = step
    return warnings


def _check_step(step, lookup, routes, rows):
    camera = step["camera"]
    time = step["time"]
    first = time if time["mode"] == "at" else time["from"]
    last = time if time["mode"] == "at" else time["to"]
    start = chronological(first, end_of_period=True)
    end = chronological(last, end_of_period=True)
    if start > end:
        raise ValueError(f"{step['id']}: reversed time lapse")
    for point in (first, last):
        if "month" in point and not any(
            all(e["date"].get(k) == point[k] for k in ("calendar", "year", "month"))
            for e in rows["events"]
        ):
            raise ValueError(f"{step['id']}: unsourced month")
    targets = [*camera.get("targets", []), *step.get("highlight", []), *step.get("reveal", [])]
    if "show_area" in step:
        targets.append("area:" + step["show_area"])
    if move := step.get("show_movement"):
        targets.extend("place:" + move[k] for k in ("from_place_id", "to_place_id"))
        if "route_id" in move and not lookup("routes", move["route_id"])["documented"]:
            raise ValueError("movement requires a documented route")
    for segment in (camera if camera["mode"] == "follow" else None, step.get("draw_route")):
        if segment:
            route = lookup("routes", segment["route_id"])
            a, b = segment["from_station_index"], segment["to_station_index"]
            if not route["documented"] or not 0 <= a <= b < len(route["stations"]):
                raise ValueError(f"{step['id']}: invalid route segment")
            targets.extend("place:" + p for p in route["stations"][a : b + 1])
    # Event and route targets also reference places or areas through their geometry.
    for ref in targets.copy():
        kind, key = ref.split(":", 1)
        if kind == "event":
            where = lookup("events", key).get("where", {})
            if "area_id" in where:
                targets.append("area:" + where["area_id"])
            for place in where.get("place_ids", []):
                targets.append("place:" + place)
            if "place_id" in where:
                targets.append("place:" + where["place_id"])
            if where.get("kind") == "route":
                targets.extend("place:" + p for p in routes[key]["stations"])
        elif kind == "route":
            targets.extend("place:" + p for p in lookup("routes", key)["stations"])
    for ref in targets:
        kind, key = ref.split(":", 1)
        item = lookup(kind + "s", key)
        if kind not in ("place", "area"):
            continue
        exists = False
        for state in item["states"]:
            date = state["start"]
            if date == "baseline" or chronological(date) <= start:
                exists = state["exists"]
            elif chronological(date) <= end and not state["exists"]:
                raise ValueError(f"{ref} disappears during time lapse")
        if not exists:
            raise ValueError(f"{ref} does not exist at the story time")
    for key in step["panel"].get("text_ids", []):
        lookup("texts", key)
