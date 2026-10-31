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

Usage (CLI — called by launch.sh for launcher-level alerts):
    python3 notify.py --once KEY --title T --body B [--color C] [--state-dir DIR]
    Exits 0; prints "already notified" to stdout if the key was already recorded.

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


# ---------------------------------------------------------------------------
# CLI entry point (used by launch.sh for launcher-level alerts)
# ---------------------------------------------------------------------------

def _cli_main() -> None:
    """Handle ``python3 notify.py --once KEY --title T --body B ...``."""
    import argparse
    import sys

    parser = argparse.ArgumentParser(
        description="Post a once-only Discord alert and record it in state.json.",
    )
    parser.add_argument("--once", metavar="KEY", required=True,
                        help="Dedupe key; if already in state.json, skip the post.")
    parser.add_argument("--title", required=True)
    parser.add_argument("--body", required=True)
    parser.add_argument("--color", default="yellow")
    parser.add_argument(
        "--state-dir",
        required=True,
        help="State directory containing state.json (always passed by launch.sh).",
    )
    args = parser.parse_args()

    # repo_root: three levels up from this script's location (i.e. the clone root)
    repo_root = Path(__file__).resolve().parent.parent.parent
    state_path = Path(args.state_dir) / "state.json"

    state = statefile.load(state_path)
    if not notify_once(state, args.once, repo_root, args.title, args.body, args.color):
        print("already notified", flush=True)
        sys.exit(0)
    statefile.save(state_path, state)


if __name__ == "__main__":
    _cli_main()
