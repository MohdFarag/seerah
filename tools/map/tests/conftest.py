"""Shared pytest fixtures."""

import functools
import os
import re
import signal
import subprocess
import sys
import threading
from collections.abc import Callable, Iterator
from dataclasses import dataclass
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


@dataclass(frozen=True)
class CliRun:
    """A finished (or killed) run of the command line.

    Attributes:
        returncode: the exit code; negative when the run was killed.
        output: everything the run printed, stdout and stderr merged.
        killed: whether the run was stopped at the requested progress line.
    """

    returncode: int
    output: str
    killed: bool


@dataclass(frozen=True)
class StopAt:
    """Kill the run once ``pattern`` has matched ``count`` output lines."""

    pattern: str
    count: int = 1


def _run_cli(
    args: list[str], stop_at: StopAt | None = None, env: dict[str, str] | None = None
) -> CliRun:
    """Run ``python3 -m seerah_map <args>`` in its own session.

    The process group is killed with SIGKILL once ``stop_at`` is reached, so the
    tool's worker processes die with it, as they would on a real interruption.
    """
    process = subprocess.Popen(
        [sys.executable, "-m", "seerah_map", *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
        env={**os.environ, **(env or {})},
    )
    assert process.stdout is not None
    lines = []
    matched = 0
    killed = False
    for line in process.stdout:
        lines.append(line)
        if stop_at is not None and re.search(stop_at.pattern, line):
            matched += 1
            if matched == stop_at.count:
                os.killpg(process.pid, signal.SIGKILL)
                killed = True
                break
    process.stdout.close()
    return CliRun(process.wait(), "".join(lines), killed)


@pytest.fixture
def run_cli() -> Callable[..., CliRun]:
    """Run the command line as a subprocess: ``run_cli(args, stop_at=None, env=None)``.

    ``stop_at`` (a ``StopAt``) kills the run's process group once the given
    progress line has been printed the given number of times.
    """
    return _run_cli
