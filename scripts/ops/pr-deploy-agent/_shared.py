#!/usr/bin/env python3
"""Shared helpers for pr-deploy-agent.

Provides a single _gh() subprocess helper and a _parse_claude_json() parser
used by both deploy_gate.py and pr_duty.py.
"""
from __future__ import annotations

import json
import subprocess


def _gh(*args: str, timeout: int = 30, stdin_data: str | None = None) -> str:
    """Run `gh <args>` and return stdout; raise RuntimeError on failure."""
    result = subprocess.run(
        ["gh"] + list(args),
        input=stdin_data,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"gh {args[0] if args else '?'} failed: {result.stderr.strip()[:200]}"
        )
    return result.stdout


def _parse_claude_json(stdout: str, *, key: str) -> dict | None:
    """Parse Claude's JSON output in any of its three wrapper shapes.

    Returns the inner dict (containing `key`) if successful, None on parse failure.

    Shapes handled:
      1. Direct ``{"key": ...}``                    — fake claude in tests
      2. ``{"structured_output": {"key": ...}}``    — real claude -p with --json-schema
      3. ``{"result": '{"key": ...}'}``             — real claude -p, result is JSON string
    """
    try:
        data = json.loads(stdout)
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(data, dict):
        return None
    # Shape 1
    if key in data:
        return data
    # Shape 2
    if "structured_output" in data and isinstance(data.get("structured_output"), dict):
        so = data["structured_output"]
        if key in so:
            return so
    # Shape 3
    if "result" in data:
        raw = data["result"]
        try:
            inner = json.loads(raw) if isinstance(raw, str) else raw
            if isinstance(inner, dict) and key in inner:
                return inner
        except (json.JSONDecodeError, TypeError):
            pass
    return None
