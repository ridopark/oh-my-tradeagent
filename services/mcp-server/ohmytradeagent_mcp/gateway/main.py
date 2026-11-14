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
        2 on configuration errors (missing required env vars or invalid key).
    """
    from ohmytradeagent_mcp.gateway.settings import ConfigError, GatewaySettings

    try:
        settings = GatewaySettings(env)
    except ConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    # Validate signing key early — bad PEM should fail before starting the server
    try:
        from ohmytradeagent_mcp.gateway.assertion import Signer
        Signer(settings.signing_key_pem)
    except Exception as exc:  # noqa: BLE001
        print(f"error: invalid signing key: {exc}", file=sys.stderr)
        return 2

    if _test_mode:
        return 0

    # Production-mode validation: when the gateway is active (not disabled),
    # DB URL and Google credentials are required — no InMemoryStore fallback.
    if not settings.disabled:
        prod_missing = []
        for var in ("OMT_GATEWAY_DB_URL", "OMT_GOOGLE_CLIENT_ID", "OMT_GOOGLE_CLIENT_SECRET"):
            if not env.get(var, "").strip():
                prod_missing.append(var)
        if prod_missing:
            print(
                f"error: production mode requires: {', '.join(prod_missing)}",
                file=sys.stderr,
            )
            return 2

    # Build the OAuth store
    if settings.disabled:
        # Disabled mode: use InMemoryStore (no connections needed; all /mcp → 503)
        from ohmytradeagent_mcp.gateway.store import InMemoryStore
        store = InMemoryStore()
    else:
        # Production: PostgresStore; inject MCP_GATEWAY_DB_PASSWORD if present
        from ohmytradeagent_mcp.gateway.store_postgres import PostgresStore
        db_url = settings.db_url  # validated above: not None
        db_password = env.get("MCP_GATEWAY_DB_PASSWORD", "").strip()
        if db_password and db_url:
            from urllib.parse import urlparse, urlunparse
            parsed = urlparse(db_url)
            # Rebuild netloc with password injected
            user = parsed.username or "mcp_gateway"
            host = parsed.hostname or "localhost"
            netloc = f"{user}:{db_password}@{host}"
            if parsed.port:
                netloc += f":{parsed.port}"
            db_url = urlunparse(parsed._replace(netloc=netloc))
        store = PostgresStore(db_url)
        # connect() is called by build_gateway_app via the ASGI lifespan event

    # Build rate limiters from settings
    from ohmytradeagent_mcp.gateway.ratelimit import AuthRateLimiter, ToolRateLimiter
    tool_rate_limiter = ToolRateLimiter(
        per_subject_rpm=settings.rate_tools_per_min_subject,
        per_client_rpm=settings.rate_tools_per_min_client,
    )
    auth_rate_limiter = AuthRateLimiter(per_ip_rpm=settings.rate_auth_per_min_ip)

    # Build the app and start uvicorn
    from ohmytradeagent_mcp.gateway.app import build_gateway_app

    app = build_gateway_app(
        settings,
        store,
        auth_rate_limiter=auth_rate_limiter,
        tool_rate_limiter=tool_rate_limiter,
    )

    import uvicorn

    # Parse listen address
    listen = settings.listen
    if ":" in listen:
        host, port_str = listen.rsplit(":", 1)
        port = int(port_str)
    else:
        host = listen
        port = 8000

    uvicorn.run(app, host=host, port=port)
    return 0


def main() -> None:
    sys.exit(run_gateway(dict(os.environ)))
