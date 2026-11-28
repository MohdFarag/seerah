"""Read one YAML 1.2 source document as JSON-compatible data."""

import math
from pathlib import Path

from ruamel.yaml import YAML
from ruamel.yaml.error import YAMLError
from ruamel.yaml.events import AliasEvent, DocumentStartEvent

_JSON_TAGS = {
    f"tag:yaml.org,2002:{name}" for name in ("map", "seq", "str", "null", "bool", "int", "float")
}


class ContentSourceError(ValueError):
    """A content source file could not be decoded."""

    def __init__(self, path: Path, message: str):
        self.path = path
        super().__init__(f"{path}: {message}")


def _validate(value: object, path: Path) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ContentSourceError(path, "mapping keys must be strings")
            _validate(item, path)
    elif isinstance(value, list):
        for item in value:
            _validate(item, path)
    elif isinstance(value, float) and not math.isfinite(value):
        raise ContentSourceError(path, "numbers must be finite")


def load(path: Path) -> object:
    """Decode a UTF-8 source file, raising ContentSourceError with its path on failure."""
    yaml = YAML(typ="safe", pure=True)
    yaml.version = (1, 2)
    yaml.allow_duplicate_keys = False
    resolvers = yaml.resolver.versioned_resolver
    for char, entries in resolvers.items():
        resolvers[char] = [entry for entry in entries if entry[0] != "tag:yaml.org,2002:timestamp"]
    try:
        source = path.read_text(encoding="utf-8")
        documents = 0
        for event in yaml.parse(source):
            if isinstance(event, DocumentStartEvent):
                documents += 1
                if event.version not in (None, (1, 2)):
                    raise ContentSourceError(path, "only YAML 1.2 is supported")
            if isinstance(event, AliasEvent) or getattr(event, "anchor", None) is not None:
                raise ContentSourceError(path, "anchors and aliases are forbidden")
            tag = getattr(event, "tag", None)
            if tag is not None and tag not in _JSON_TAGS:
                raise ContentSourceError(path, f"unsupported tag {tag}")
        if documents != 1:
            raise ContentSourceError(path, "expected exactly one YAML document")
        value = yaml.load(source)
        if value is None:
            raise ContentSourceError(path, "expected content in the document")
        _validate(value, path)
        return value
    except ContentSourceError:
        raise
    except (
        OSError,
        YAMLError,
        ValueError,
        RecursionError,
        OverflowError,
        LookupError,
        TypeError,
    ) as error:
        raise ContentSourceError(path, str(error)) from error
