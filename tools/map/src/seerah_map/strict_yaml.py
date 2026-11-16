"""Strict YAML reading and writing for the map tool's configuration files.

Duplicate keys are an error, and YAML 1.2 rules apply, so ``yes`` and ``no``
stay strings. Dates stay strings too: the timestamp resolver is removed.
"""

from pathlib import Path

from ruamel.yaml import YAML
from ruamel.yaml.error import YAMLError

from .errors import ConfigError

_TIMESTAMP_TAG = "tag:yaml.org,2002:timestamp"


def _make_yaml() -> YAML:
    """Return a safe YAML 1.2 parser that rejects duplicate keys and keeps dates as strings."""
    yaml = YAML(typ="safe", pure=True)
    yaml.version = (1, 2)
    yaml.allow_duplicate_keys = False
    yaml.default_flow_style = False
    # The versioned resolver is built lazily and per instance; filter it in place.
    resolvers = yaml.resolver.versioned_resolver
    for first_char, entries in resolvers.items():
        resolvers[first_char] = [e for e in entries if e[0] != _TIMESTAMP_TAG]
    return yaml


def load(path: Path) -> object:
    """Read a YAML file and return its content as plain Python objects.

    Args:
        path: The YAML file.

    Raises:
        ConfigError: The file cannot be read, is not valid YAML or repeats a key.
    """
    try:
        with open(path, encoding="utf-8") as handle:
            return _make_yaml().load(handle)
    except (OSError, YAMLError) as error:
        raise ConfigError(f"{path}: cannot load YAML: {error}") from error


def dump(obj: object, path: Path) -> None:
    """Write plain Python objects to a YAML file (block style, UTF-8).

    Args:
        obj: Dicts, lists, strings, numbers, booleans and None only.
        path: The file to write.
    """
    with open(path, "w", encoding="utf-8") as handle:
        _make_yaml().dump(obj, handle)
