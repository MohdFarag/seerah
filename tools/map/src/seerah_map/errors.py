"""The map tool's configuration error."""


class ConfigError(Exception):
    """A configuration file is invalid.

    The message names the file, the key, what was expected and what was found.
    """
