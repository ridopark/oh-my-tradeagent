"""Optional ``GET /healthz`` endpoint exposing the signal watcher's heartbeat age.

Read-only observability for the BFF's live connection indicator. It only stats the heartbeat file
the watch loop already touches each tick; it never touches the loop itself. Runs on a daemon
thread (stdlib ``ThreadingHTTPServer``), is enabled only when ``HEALTHZ_PORT`` is set, and every
failure (bad port, bind error, handler error) is logged — never raised into the caller.

``heartbeat_age_s`` means "watch loop alive", not "Discord readable".
"""

from __future__ import annotations

import json
import logging
import os
import pathlib
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def heartbeat_age_s(path: pathlib.Path) -> float | None:
    """Seconds since the heartbeat file's mtime, or None when it does not exist (yet)."""
    try:
        return max(0.0, time.time() - path.stat().st_mtime)
    except FileNotFoundError:
        return None


def _make_handler(heartbeat_path: pathlib.Path, log: logging.Logger):
    class _Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 (stdlib name)
            if self.path != "/healthz":
                self._send(404, {"error": "not found"})
                return
            try:
                body = {"heartbeat_age_s": heartbeat_age_s(heartbeat_path)}
            except Exception as e:  # noqa: BLE001
                log.warning("healthz: heartbeat read failed: %r", e)
                self._send(500, {"error": "heartbeat read failed"})
                return
            self._send(200, body)

        def _send(self, status: int, body: dict) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, format: str, *args) -> None:  # noqa: A002 - quiet access log
            pass

    return _Handler


def start_healthz_server(
    port: int, heartbeat_path: pathlib.Path, log: logging.Logger
) -> ThreadingHTTPServer | None:
    """Bind ``0.0.0.0:port`` and serve on a daemon thread. Returns None (after a warning) on any
    failure; never raises."""
    try:
        server = ThreadingHTTPServer(("0.0.0.0", port), _make_handler(heartbeat_path, log))
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, name="healthz", daemon=True).start()
    except Exception as e:  # noqa: BLE001
        log.warning("healthz server not started on port %s: %r", port, e)
        return None
    log.info("healthz server listening on port %s", server.server_address[1])
    return server


def start_from_env(heartbeat_path: pathlib.Path, log: logging.Logger) -> ThreadingHTTPServer | None:
    """Start the server iff ``HEALTHZ_PORT`` is set. Unset → None; invalid → warning, None."""
    raw = os.getenv("HEALTHZ_PORT")
    if raw is None or not raw.strip():
        return None
    try:
        port = int(raw)
    except ValueError:
        log.warning("HEALTHZ_PORT=%r is not an integer — healthz server disabled", raw)
        return None
    return start_healthz_server(port, heartbeat_path, log)
