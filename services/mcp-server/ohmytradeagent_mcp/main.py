"""MCP server entry point.

`run(env, transport=None, _on_ready=None) → int` is the testable core:
- reads config from *env*
- calls the BFF to verify the token is bound to the configured tenant
- on any failure: prints one line to stderr, returns a non-zero exit code
- on success: builds the server and either calls `_on_ready(binding, server)`
  (for in-process tests that must not block on stdio) or runs the server.

`main()` is the console-script entry point; it calls `sys.exit(run(os.environ))`.

Nothing is ever written to stdout except by the MCP transport itself.
"""
from __future__ import annotations

import asyncio
import logging
import os
import sys
from collections.abc import Callable
from typing import Any

import httpx

from ohmytradeagent_mcp.bff import BffClient
from ohmytradeagent_mcp.config import Config, ConfigError
from ohmytradeagent_mcp.resolver import (
    BffUnreachable,
    StaticTokenResolver,
    TenantBinding,
    TenantMismatch,
    Unauthorized,
)
from ohmytradeagent_mcp.tools import build_server

# Redirect all logging to stderr so stdout stays clean for the MCP transport.
logging.basicConfig(stream=sys.stderr, level=logging.WARNING)


def run(
    env: dict[str, str],
    transport: httpx.AsyncBaseTransport | httpx.MockTransport | None = None,
    _on_ready: Callable[[TenantBinding, Any], None] | None = None,
) -> int:
    """Start the MCP server.

    Returns 0 on success (or when *_on_ready* returns), 1 on startup errors,
    2 on configuration errors.
    """
    # --- config ---
    try:
        cfg = Config(env)
    except ConfigError as exc:
        print(f"error: missing required environment variable {exc}", file=sys.stderr)
        return 2

    # --- BFF client and tenant resolver ---
    bff = BffClient(cfg.bff_url, cfg.token, cfg.tenant, transport=transport)
    resolver = StaticTokenResolver(bff, cfg.tenant)

    try:
        binding = asyncio.run(resolver.resolve())
    except TenantMismatch as exc:
        print(
            f"error: tenant mismatch — OMT_TENANT={exc.expected!r} but token is "
            f"bound to {exc.bound!r}",
            file=sys.stderr,
        )
        return 1
    except Unauthorized as exc:
        print(
            f"error: BFF rejected the token with {exc.status} — check OMT_MCP_TOKEN",
            file=sys.stderr,
        )
        return 1
    except BffUnreachable as exc:
        print(
            f"error: BFF unreachable at {exc.url} — check OMT_BFF_URL and connectivity",
            file=sys.stderr,
        )
        return 1

    # --- build server ---
    server = build_server(binding, bff)

    # _on_ready hook: used by in-process tests that must not block on stdio.
    if _on_ready is not None:
        _on_ready(binding, server)
        return 0

    server.run()
    return 0


def main() -> None:
    sys.exit(run(dict(os.environ)))
