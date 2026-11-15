"""Shared pytest fixtures."""

import functools
import threading
from collections.abc import Iterator
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

FETCH_FIXTURES = Path(__file__).parent / "fixtures" / "fetch"


class ServerUrl(str):
    """The base URL of the test server, with the paths it was asked for in ``requests``."""

    requests: list[str]


class _RecordingHandler(SimpleHTTPRequestHandler):
    """Serves files and records each requested path."""

    def do_GET(self) -> None:  # noqa: N802 (name fixed by http.server)
        self.server.requests.append(self.path)  # type: ignore[attr-defined]
        super().do_GET()

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002
        """Keep test output quiet."""


@pytest.fixture
def http_server() -> Iterator[ServerUrl]:
    """Serve ``tests/fixtures/fetch/`` on 127.0.0.1 and yield its base URL.

    The yielded string has a ``requests`` list of every path requested.
    """
    handler = functools.partial(_RecordingHandler, directory=str(FETCH_FIXTURES))
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    server.requests = []  # type: ignore[attr-defined]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = ServerUrl(f"http://127.0.0.1:{server.server_address[1]}")
    url.requests = server.requests  # type: ignore[attr-defined]
    try:
        yield url
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
