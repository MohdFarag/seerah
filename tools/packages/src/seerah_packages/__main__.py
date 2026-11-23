"""CLI entrypoint for ``python3 -m seerah_packages``."""

import sys

from .cli import main

if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
