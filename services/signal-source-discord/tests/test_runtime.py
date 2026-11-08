"""Tests for runtime.attach_run_log (Step 1 — PLAN-2026-11-08-durable-collector-records).

All tests run in a fresh temporary directory; the handler is detached in teardown so
the root logger is clean between tests and the full suite is repeatable.
"""

from __future__ import annotations

import json
import logging

import pytest

from ohmytradeagent_sidecar.runtime import attach_run_log


# ---------------------------------------------------------------------------
# Fixture: detach any handler the test attaches so the root logger is clean.
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _detach_run_log_handlers():
    """Remove any RotatingFileHandler added during the test."""
    root = logging.getLogger()
    before = list(root.handlers)
    yield
    for h in list(root.handlers):
        if h not in before:
            h.close()
            root.removeHandler(h)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_basic_warning_line(tmp_path):
    """attach_run_log writes a valid JSON record for a WARNING log call."""
    handler = attach_run_log(tmp_path)
    assert handler is not None

    logging.getLogger("x").warning("hello %s", 1)

    lines = (tmp_path / "run.log").read_text().splitlines()
    assert len(lines) == 1
    rec = json.loads(lines[0])
    assert rec["level"] == "WARNING"
    assert rec["logger"] == "x"
    assert rec["msg"] == "hello 1"
    assert rec["ts"].endswith("Z")
    # No exc key when there is no exception
    assert "exc" not in rec


def test_exception_record_has_exc(tmp_path):
    """log.exception inside an except block writes an 'exc' field with Traceback."""
    attach_run_log(tmp_path)

    log = logging.getLogger("test.exc")
    try:
        raise ValueError("boom")
    except ValueError:
        log.exception("something failed")

    rec = json.loads((tmp_path / "run.log").read_text().splitlines()[-1])
    assert "exc" in rec
    assert "Traceback" in rec["exc"]
    assert "ValueError" in rec["exc"]


def test_rotation(tmp_path):
    """With max_bytes=300, backup_count=1 and 20 records, run.log and run.log.1 both exist."""
    attach_run_log(tmp_path, max_bytes=300, backup_count=1)

    log = logging.getLogger("test.rot")
    for i in range(20):
        log.warning("record number %d with some padding to push past 300 bytes quickly", i)

    assert (tmp_path / "run.log").exists()
    assert (tmp_path / "run.log.1").exists()
    total = (tmp_path / "run.log").stat().st_size + (tmp_path / "run.log.1").stat().st_size
    # Total must be under 2 × max_bytes + one record worth of overhead
    assert total < 2 * 300 + 400


def test_unwritable_dir_returns_none(tmp_path, caplog):
    """attach_run_log returns None and logs WARNING when the dir is unwritable."""
    # Make a regular file at the path where the dir should be, so mkdir/open fails
    bad_dir = tmp_path / "file_not_dir"
    bad_dir.write_text("not a dir")

    with caplog.at_level(logging.WARNING):
        result = attach_run_log(bad_dir)

    assert result is None
    assert any(str(bad_dir) in m for m in caplog.messages)


def test_none_dir_returns_none():
    """attach_run_log(None) returns None and does not attach a handler."""
    root = logging.getLogger()
    before_count = len(root.handlers)
    result = attach_run_log(None)
    assert result is None
    assert len(root.handlers) == before_count


def test_attaches_to_root_logger(tmp_path):
    """The handler is on the root logger so a websockets logger line lands in run.log."""
    attach_run_log(tmp_path)
    logging.getLogger("websockets.client").warning("ws warning")

    lines = (tmp_path / "run.log").read_text().splitlines()
    assert any("ws warning" in json.loads(line)["msg"] for line in lines)
