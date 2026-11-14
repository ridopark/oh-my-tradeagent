"""Gateway entry point.

`run_gateway(env, *, _test_mode=False) → int` is the testable core:
- Reads config from *env* dict
- Exits 2 on ConfigError
- In _test_mode=True returns 0 without starting uvicorn

`main()` is the console-script entry point for `omt-mcp-gateway`.
"""
from __future__ import annotations

import os
import sys


def run_gateway(
    env: dict[str, str],
    *,
    _test_mode: bool = False,
) -> int:
    """Start the MCP gateway server.

    Returns:
        0 on success (or when _test_mode is True and config is valid).
        2 on configuration errors (missing required env vars).
    """
    from ohmytradeagent_mcp.gateway.settings import ConfigError, GatewaySettings

    try:
        settings = GatewaySettings(env)
    except ConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    if _test_mode:
        return 0

    # Build the app and start uvicorn
    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.store import InMemoryStore

    # In production, PostgresStore replaces InMemoryStore (Step 3c).
    store = InMemoryStore()
    app = build_gateway_app(settings, store)

    import uvicorn

    # Parse listen address
    listen = settings.listen
    if ":" in listen:
        host, port_str = listen.rsplit(":", 1)
        port = int(port_str)
    else:
        host = listen
        port = 8080

    uvicorn.run(app, host=host, port=port)
    return 0


def main() -> None:
    sys.exit(run_gateway(dict(os.environ)))
