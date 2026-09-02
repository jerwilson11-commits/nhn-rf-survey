#!/usr/bin/env python3
"""
NHN throughput endpoint.

A deliberately small download/upload responder for the RF survey app to measure against.

## Why this exists

Public speed-test endpoints refuse sustained automated use. On the 2026-09-02 walk, eight
consecutive download bursts against a public host returned HTTP 429 and every download in the
session was lost -- while the uploads, which happened to be spaced differently, succeeded. The
resulting dataset had eight upload-only rows and no explanation, which in a client report reads as
"not measured here" rather than "the endpoint refused us".

Rate limiting is also not a property of the network under test. A survey that reports it as one is
reporting a fault at the wrong address.

Owning the endpoint fixes all of that, and buys something else worth having: a report can state the
measurement path precisely -- "measured against NHN's endpoint at <host>, <region>" -- rather than
"a public speed test, when it allowed us".

## What it deliberately is not

Not a general-purpose file server, and not a benchmark of this machine. It streams pre-generated
random bytes from memory and discards uploads without touching disk, so the box is not the
bottleneck at the rates a handset can reach.

Random rather than zeroes, and it matters: a compressing middlebox between the handset and here
would squash a block of zeroes and report a throughput the link cannot actually deliver.

## Endpoints

    GET  /down?bytes=N   stream N random bytes (capped)
    POST /up             read and discard, reply with the byte count
    GET  /ping           tiny reply, for latency
    GET  /health         readiness

No authentication. The payload is random bytes in both directions and nothing here touches
persistent state, so the exposure is bandwidth. Keep MAX_BYTES sane, put it behind a reverse proxy
with TLS, and if the host is billed by transfer, watch it.
"""

from __future__ import annotations

import argparse
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# Streamed in blocks of this size. Large enough that per-write Python overhead is negligible next
# to the kernel copy, small enough to stay responsive to a client that disconnects mid-transfer.
BLOCK = 256 * 1024

# One block of random bytes, generated once at startup and reused. Regenerating per request would
# make this a benchmark of the server's entropy source rather than of the network.
_BLOCK_DATA = os.urandom(BLOCK)

# Ceiling on a single download. A survey burst asks for a few tens of megabytes; anything far
# larger is a mistake or an abuse, and an unbounded endpoint on a metered VPS is a billing
# incident waiting to happen.
MAX_BYTES = 200 * 1024 * 1024
DEFAULT_BYTES = 25 * 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.1 so connections can be reused across a burst's repeated requests.
    protocol_version = "HTTP/1.1"
    server_version = "nhn-speedtest/1.0"

    # ---- logging ---------------------------------------------------------

    def log_message(self, fmt: str, *args) -> None:
        if self.server.verbose:  # type: ignore[attr-defined]
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # ---- helpers ---------------------------------------------------------

    def _cors(self) -> None:
        # The live view page is served from the phone and may fetch from here.
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _no_store(self) -> None:
        # A cached response would measure the cache, not the link. This is the whole point.
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.send_header("Pragma", "no-cache")

    def _text(self, code: int, body: str) -> None:
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self._no_store()
        self._cors()
        self.end_headers()
        self.wfile.write(data)

    # ---- routes ----------------------------------------------------------

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        route = urlparse(self.path)
        if route.path == "/health":
            return self._text(200, "ok")
        if route.path == "/ping":
            return self._text(200, "p")
        if route.path == "/down":
            return self._download(route.query)
        self._text(404, "not found")

    def do_POST(self) -> None:  # noqa: N802
        if urlparse(self.path).path != "/up":
            return self._text(404, "not found")
        self._upload()

    # ---- download --------------------------------------------------------

    def _download(self, query: str) -> None:
        try:
            want = int(parse_qs(query).get("bytes", [DEFAULT_BYTES])[0])
        except (TypeError, ValueError):
            return self._text(400, "bytes must be an integer")
        if want <= 0:
            return self._text(400, "bytes must be positive")
        want = min(want, MAX_BYTES)

        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(want))
        self._no_store()
        self._cors()
        self.end_headers()

        sent = 0
        try:
            while sent < want:
                chunk = min(BLOCK, want - sent)
                self.wfile.write(_BLOCK_DATA[:chunk])
                sent += chunk
        except (BrokenPipeError, ConnectionResetError):
            # Expected and not an error: the client stops reading when its burst window closes.
            # A survey burst is time-bounded, so most downloads end this way by design.
            pass

    # ---- upload ----------------------------------------------------------

    def _upload(self) -> None:
        received = 0
        try:
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                # The app streams chunked, because it does not know in advance how many bytes it
                # will manage inside its burst window.
                while True:
                    line = self.rfile.readline().strip()
                    if not line:
                        break
                    try:
                        size = int(line.split(b";")[0], 16)
                    except ValueError:
                        break
                    if size == 0:
                        self.rfile.readline()
                        break
                    remaining = size
                    while remaining:
                        block = self.rfile.read(min(BLOCK, remaining))
                        if not block:
                            break
                        remaining -= len(block)
                        received += len(block)
                    self.rfile.readline()
            else:
                remaining = int(self.headers.get("Content-Length", 0))
                while remaining > 0:
                    block = self.rfile.read(min(BLOCK, remaining))
                    if not block:
                        break
                    remaining -= len(block)
                    received += len(block)
        except (BrokenPipeError, ConnectionResetError):
            pass

        self._text(200, str(received))


def main() -> int:
    ap = argparse.ArgumentParser(description="NHN throughput endpoint")
    ap.add_argument("--host", default="127.0.0.1",
                    help="bind address (default loopback; put a TLS reverse proxy in front)")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--verbose", action="store_true", help="log every request")
    args = ap.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    server.verbose = args.verbose  # type: ignore[attr-defined]
    print(f"nhn speedtest endpoint on http://{args.host}:{args.port}", file=sys.stderr)
    print(f"  GET  /down?bytes=N   (max {MAX_BYTES // (1024 * 1024)} MB)", file=sys.stderr)
    print("  POST /up", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
