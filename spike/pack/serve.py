#!/usr/bin/env python3
"""Local package server for the spike's update test.

Serves dist/ with HTTP Range support (needed to resume interrupted downloads)
and an update feed at /feed.json.

  python3 serve.py                         # offer v2 (1.1.0-test)
  python3 serve.py --offer corrupt         # offer the corrupted v2 (must be rejected)
  python3 serve.py --cut-after 300000      # drop the connection once after N bytes

On a device: adb reverse tcp:8765 tcp:8765, then use http://127.0.0.1:8765/feed.json
On the emulator: http://10.0.2.2:8765/feed.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DIST = Path(__file__).resolve().parent / "dist"
STATE = {"root": DIST, "cut_after": 0, "cut_done": False, "offer": "pack-1.1.0-test.zip"}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(STATE["root"]), **kw)

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def do_GET(self):  # noqa: N802
        if self.path.split("?")[0] == "/feed.json":
            return self._feed()
        path = Path(self.translate_path(self.path))
        rng = self.headers.get("Range")
        if not path.is_file() or not rng:
            return self._send_file(path, 0, None)
        m = re.match(r"bytes=(\d+)-(\d*)", rng)
        if not m:
            return self.send_error(416)
        start = int(m.group(1))
        end = int(m.group(2)) if m.group(2) else None
        return self._send_file(path, start, end)

    def _feed(self):
        z = STATE["root"] / STATE["offer"]
        sha = hashlib.sha256(z.read_bytes()).hexdigest()
        version = "1.1.0-test"
        body = json.dumps(
            {
                "content_version": version,
                "url": f"/{z.name}",
                "size": z.stat().st_size,
                "sha256": sha,
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, path: Path, start: int, end: int | None):
        if not path.is_file():
            return self.send_error(404)
        size = path.stat().st_size
        end = size - 1 if end is None or end >= size else end
        if start >= size:
            return self.send_error(416)
        length = end - start + 1
        partial = start > 0 or end < size - 1 or self.headers.get("Range")
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", self.guess_type(str(path)))
        self.send_header("Content-Length", str(length))
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with path.open("rb") as f:
            f.seek(start)
            sent = 0
            while sent < length:
                chunk = f.read(min(65536, length - sent))
                if not chunk:
                    break
                limit = STATE["cut_after"]
                if (
                    limit
                    and not STATE["cut_done"]
                    and path.suffix == ".zip"
                    and sent + len(chunk) > limit
                ):
                    self.wfile.write(chunk[: max(0, limit - sent)])
                    STATE["cut_done"] = True
                    self.log_message("simulated network cut after %d bytes", limit)
                    self.close_connection = True
                    return
                self.wfile.write(chunk)
                sent += len(chunk)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--offer", choices=["good", "corrupt"], default="good")
    ap.add_argument("--cut-after", type=int, default=0, help="drop connection once after N bytes")
    ap.add_argument("--root", type=Path, default=DIST, help="directory to serve")
    a = ap.parse_args()
    STATE["root"] = a.root
    STATE["cut_after"] = a.cut_after
    if a.offer == "corrupt":
        STATE["offer"] = "pack-1.1.0-test-corrupt.zip"
    print(f"serving {a.root} on :{a.port} (offer={STATE['offer']}, cut_after={a.cut_after})")
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
