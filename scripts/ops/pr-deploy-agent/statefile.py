#!/usr/bin/env python3
"""Atomic state-file helper for pr-deploy-agent.

Stores per-run / per-PR dedupe keys and round counts so ticks are idempotent.
Writes are atomic (write temp + os.replace) so a crash between steps never corrupts state.

Structure of state.json:
{
  "notified": {
    "<key>": "<iso8601-utc-timestamp>"
  }
}

Keys use namespacing conventions:
  run:<run_id>:first-deferral
  run:<run_id>:waited-2h
  run:<run_id>:human-override
  pr:<pr_number>:<head_sha>:not-reviewed
  ...
"""
from __future__ import annotations

import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

_UTC = timezone.utc


def _now_iso() -> str:
    return datetime.now(tz=_UTC).isoformat()


def load(path: Path | str) -> dict:
    """Load state JSON; return {} if the file does not exist or is corrupt."""
    p = Path(path)
    try:
        return json.loads(p.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save(path: Path | str, data: dict) -> None:
    """Atomically write data to path as JSON."""
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp_fd, tmp_path = tempfile.mkstemp(dir=p.parent, suffix=".tmp")
    try:
        with os.fdopen(tmp_fd, "w") as f:
            json.dump(data, f, indent=2)
        os.replace(tmp_path, p)
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def is_notified(state: dict, key: str) -> bool:
    """Return True if this key has already been posted."""
    return key in state.get("notified", {})


def mark_notified(state: dict, key: str) -> None:
    """Record that key was posted (mutates state in-place)."""
    state.setdefault("notified", {})[key] = _now_iso()


def prune(state: dict, days: int = 14) -> None:
    """Remove notification keys older than `days` days (mutates in-place)."""
    cutoff = datetime.now(tz=_UTC) - timedelta(days=days)
    notified = state.get("notified", {})
    to_delete = []
    for key, ts in notified.items():
        try:
            if datetime.fromisoformat(ts) < cutoff:
                to_delete.append(key)
        except (ValueError, TypeError):
            to_delete.append(key)
    for key in to_delete:
        del notified[key]
