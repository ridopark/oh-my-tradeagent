#!/usr/bin/env python3
"""Discord notification helper for pr-deploy-agent.

Posts embed messages through the DISCORD_WEBHOOK_URL webhook.
Every post is idempotent via a dedupe key stored in state.json.

Usage (module):
    from notify import post, notify_once

    # Always post (no dedupe):
    post(repo_root, "Title", "Body text", color="green")

    # Post only if key has not been posted yet (returns True if posted):
    posted = notify_once(state, key, repo_root, "Title", "Body text", color="yellow")

Colors: "red" | "yellow" | "green"
Env:    DISCORD_WEBHOOK_URL  — read from env first, then from {repo_root}/.env
        DISCORD_DRY_RUN=1   — skip the actual HTTP call (for tests that can't use fake webhook)
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from pathlib import Path

import statefile

_COLORS = {"red": 16711680, "green": 65280, "yellow": 16776960}


def _load_webhook_url(repo_root: Path) -> str:
    """Read DISCORD_WEBHOOK_URL from env or from {repo_root}/.env."""
    url = os.environ.get("DISCORD_WEBHOOK_URL", "")
    if url:
        return url
    env_file = repo_root / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            line = line.strip()
            if line.startswith("DISCORD_WEBHOOK_URL="):
                return line.split("=", 1)[1].strip()
    return ""


def post(repo_root: Path, title: str, body: str, color: str = "yellow") -> None:
    """Post one Discord embed.  No-ops silently when URL is unset."""
    if os.environ.get("DISCORD_DRY_RUN") == "1":
        return

    url = _load_webhook_url(repo_root)
    if not url:
        return

    payload = json.dumps(
        {
            "embeds": [
                {
                    "title": title,
                    "description": body,
                    "color": _COLORS.get(color, _COLORS["yellow"]),
                }
            ]
        }
    ).encode()

    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        urllib.request.urlopen(req, timeout=15)
    except OSError:  # covers URLError, TimeoutError, and any network error
        pass  # best-effort; a Discord outage should not block the tick


def notify_once(
    state: dict,
    key: str,
    repo_root: Path,
    title: str,
    body: str,
    color: str = "yellow",
) -> bool:
    """Post if key not already in state; return True if the post was made."""
    if statefile.is_notified(state, key):
        return False
    post(repo_root, title, body, color)
    statefile.mark_notified(state, key)
    return True
