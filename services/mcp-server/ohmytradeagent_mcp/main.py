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

from ohmytradeagent_mcp.bff import BffClient, BffError
from ohmytradeagent_mcp.config import Config, ConfigError
from ohmytradeagent_mcp.resolver import (
    BffConfigError,
    BffUnreachable,
    StaticTokenResolver,
    TenantBinding,
    TenantMismatch,
    Unauthorized,
)
from ohmytradeagent_mcp.tools import build_server

# Redirect all logging to stderr so stdout stays clean for the MCP transport.
# This call must precede any MCPServer instantiation; MCPServer.__init__ calls
# configure_logging() which is a no-op when the root logger already has handlers.
logging.basicConfig(stream=sys.stderr, level=logging.WARNING)
log = logging.getLogger(__name__)


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
        print(f"error: {exc}", file=sys.stderr)
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
    except BffConfigError as exc:
        print(
            f"error: invalid OMT_BFF_URL={exc.url!r} — check that it starts with http:// or https://",
            file=sys.stderr,
        )
        return 2
    except BffError as exc:
        print(
            f"error: BFF returned {exc.status} from {cfg.bff_url}/api/mcp/whoami — check the BFF",
            file=sys.stderr,
        )
        return 1
    except httpx.HTTPError as exc:
        print(
            f"error: HTTP error calling {cfg.bff_url}: {exc}",
            file=sys.stderr,
        )
        return 1

    # --- build server ---
    server = build_server(binding, bff)
    log.warning("omt-mcp: serving tenant=%r", binding.tenant_id)

    # _on_ready hook: used by in-process tests that must not block on stdio.
    if _on_ready is not None:
        _on_ready(binding, server)
        return 0

    server.run()
    return 0


def main() -> None:
    sys.exit(run(dict(os.environ)))
