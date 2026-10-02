"""Tiny /healthz endpoint exposing the signal watcher's heartbeat age.

The endpoint is observability only: it must never raise into its caller, and a bind failure must
leave startup (and therefore the watch loop) untouched.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import time
import urllib.error
import urllib.request

import pytest

from ohmytradeagent_sidecar import healthz

LOG = logging.getLogger("test-healthz")


def _get(server, path="/healthz"):
    port = server.server_address[1]
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=2) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, None


@pytest.fixture
def heartbeat(tmp_path):
    return tmp_path / "heartbeat"


@pytest.fixture
def server(heartbeat):
    srv = healthz.start_healthz_server(0, heartbeat, LOG)
    assert srv is not None
    yield srv
    srv.shutdown()
    srv.server_close()


def test_fresh_heartbeat_reports_small_age(server, heartbeat):
    heartbeat.touch()
    status, body = _get(server)
    assert status == 200
    assert 0 <= body["heartbeat_age_s"] < 5


def test_stale_heartbeat_reports_its_age(server, heartbeat):
    heartbeat.touch()
    old = time.time() - 300
    os.utime(heartbeat, (old, old))
    status, body = _get(server)
    assert status == 200
    assert 295 <= body["heartbeat_age_s"] < 310


def test_missing_heartbeat_reports_null(server):
    status, body = _get(server)
    assert status == 200
    assert body == {"heartbeat_age_s": None}


def test_other_paths_are_404(server):
    status, _ = _get(server, "/")
    assert status == 404
    status, _ = _get(server, "/healthz/x")
    assert status == 404


def test_port_in_use_logs_warning_and_returns_none(heartbeat, caplog):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as blocker:
        blocker.bind(("0.0.0.0", 0))
        blocker.listen()
        port = blocker.getsockname()[1]
        with caplog.at_level(logging.WARNING):
            assert healthz.start_healthz_server(port, heartbeat, LOG) is None
    assert "healthz" in caplog.text


def test_env_unset_starts_no_server(heartbeat, monkeypatch):
    monkeypatch.delenv("HEALTHZ_PORT", raising=False)
    started = []
    monkeypatch.setattr(healthz, "start_healthz_server", lambda *a: started.append(a))
    assert healthz.start_from_env(heartbeat, LOG) is None
    assert started == []


def test_env_set_starts_server(heartbeat, monkeypatch):
    monkeypatch.setenv("HEALTHZ_PORT", "0")
    srv = healthz.start_from_env(heartbeat, LOG)
    try:
        assert srv is not None
        heartbeat.touch()
        assert _get(srv)[0] == 200
    finally:
        srv.shutdown()
        srv.server_close()


def test_bad_env_value_logs_warning_and_continues(heartbeat, monkeypatch, caplog):
    monkeypatch.setenv("HEALTHZ_PORT", "not-a-port")
    with caplog.at_level(logging.WARNING):
        assert healthz.start_from_env(heartbeat, LOG) is None
    assert "HEALTHZ_PORT" in caplog.text


def test_server_construction_failure_never_raises(heartbeat, monkeypatch):
    def boom(*_a, **_k):
        raise RuntimeError("anything at all")

    monkeypatch.setattr(healthz, "ThreadingHTTPServer", boom)
    monkeypatch.setenv("HEALTHZ_PORT", "0")
    assert healthz.start_from_env(heartbeat, LOG) is None


async def test_failing_server_does_not_affect_the_watcher_task(heartbeat, monkeypatch):
    # Mirrors main's ordering: healthz start (failing) then the watcher task runs untouched.
    def boom(*_a, **_k):
        raise OSError("address in use")

    monkeypatch.setattr(healthz, "ThreadingHTTPServer", boom)
    monkeypatch.setenv("HEALTHZ_PORT", "8090")
    ticks = []

    async def fake_watch_loop():
        for i in range(3):
            heartbeat.touch()
            ticks.append(i)

    assert healthz.start_from_env(heartbeat, LOG) is None
    task = asyncio.create_task(fake_watch_loop())
    await task
    assert ticks == [0, 1, 2]
    assert task.exception() is None


def test_handler_error_returns_500_without_killing_server(server, heartbeat, monkeypatch):
    def boom(_path):
        raise RuntimeError("stat exploded")

    monkeypatch.setattr(healthz, "heartbeat_age_s", boom)
    status, _ = _get(server)
    assert status == 500
    monkeypatch.undo()
    heartbeat.touch()
    assert _get(server)[0] == 200
