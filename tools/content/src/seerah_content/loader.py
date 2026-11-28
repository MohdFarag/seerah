"""Discover authoring files and validate each against its source schema."""

import json
from functools import cache
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

from .strict_yaml import ContentSourceError, load

SCHEMAS = Path(__file__).resolve().parents[4] / "content/schema"


@cache
def validator(format_name: str, entity: str) -> Draft202012Validator:
    """Create an entity-specific validator with local schema references."""
    schema = json.loads((SCHEMAS / f"content-{format_name}.schema.json").read_text())
    return Draft202012Validator(
        {"$defs": schema["$defs"], "$ref": f"#/$defs/{entity}"}, format_checker=FormatChecker()
    )


def read_file(path: Path, entity: str) -> dict:
    """Read a source document and report the failing field alongside its filename."""
    data = load(path)
    error = next(validator("source", entity).iter_errors(data), None)
    if error:
        raise ContentSourceError(path, f"{error.json_path}: {error.message}")
    return data


def documents(root: Path):
    """Yield source documents with their relative roles and filesystem paths."""
    if (root / "index/phases.json").exists():
        raise ContentSourceError(root, "both YAML and legacy JSON markers present")
    paths = [("content", root / "content.yaml")]
    for name in ("sources", "areas", "places"):
        paths.append((name, root / "world" / f"{name}.yaml"))
    for folder, role in (("years", "year"), ("texts", "texts"), ("stories", "story")):
        for path in sorted((root / folder).glob("*.yaml")):
            paths.append(("undated" if role == "year" and path.stem == "undated" else role, path))
    expected = {path for _, path in paths}
    for folder in ("world", "years", "texts", "stories"):
        for path in (root / folder).rglob("*"):
            if path.suffix.lower() in (".yaml", ".yml") and path not in expected:
                raise ContentSourceError(path, "unexpected authoring file")
    for role, path in paths:
        if not path.resolve().is_relative_to(root.resolve()):
            raise ContentSourceError(path, "file resolves outside content root")
        data = read_file(path, role)
        if (
            role in ("story", "texts")
            and data["id" if role == "story" else "event_id"] != path.stem
        ):
            raise ContentSourceError(path, "filename does not match the document ID")
        yield role, path, data
