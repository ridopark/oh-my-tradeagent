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
        for var in (
            "OMT_GATEWAY_DB_URL",
            "OMT_GOOGLE_CLIENT_ID",
            "OMT_GOOGLE_CLIENT_SECRET",
            "MCP_GATEWAY_DB_PASSWORD",
        ):
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
        # Production: PostgresStore; password passed as kwarg, never spliced into DSN
        from ohmytradeagent_mcp.gateway.store_postgres import PostgresStore
        db_url = settings.db_url  # validated above: not None
        db_password = env.get("MCP_GATEWAY_DB_PASSWORD", "").strip() or None
        store = PostgresStore(db_url, password=db_password)
        # connect() is called by build_gateway_app via the ASGI lifespan event

    # Probe the DB before starting uvicorn so a bad URL/password exits 2 with one line instead
    # of failing inside the ASGI lifespan. The probe uses its OWN one-shot connection: the
    # store's pool must be created later, on uvicorn's event loop (a pool created under
    # asyncio.run here would be bound to a loop that is closed before the first request).
    if not settings.disabled:
        import asyncio

        import asyncpg

        async def _probe_db() -> None:
            conn = await asyncpg.connect(db_url, password=db_password, timeout=4.0)
            try:
                await conn.execute("SELECT 1")
            finally:
                await conn.close()

        try:
            asyncio.run(asyncio.wait_for(_probe_db(), timeout=5.0))
        except Exception:  # noqa: BLE001 — any failure is the same operator action
            print(
                "error: DB connection failed (check OMT_GATEWAY_DB_URL and MCP_GATEWAY_DB_PASSWORD)",
                file=sys.stderr,
            )
            return 2

    app = _build_app_from_settings(settings, store)

    import uvicorn

    # Parse listen address
    listen = settings.listen
    if ":" in listen:
        host, port_str = listen.rsplit(":", 1)
        port = int(port_str)
    else:
        host = listen
        port = 8000

    uvicorn.run(app, host=host, port=port, access_log=False)
    return 0


def _build_app_from_settings(settings, store):
    """Build the gateway ASGI app from settings and store, wiring rate limiters.

    Extracted from run_gateway so tests can build a fully-wired app without
    starting uvicorn.
    """
    from ohmytradeagent_mcp.gateway.app import build_gateway_app
    from ohmytradeagent_mcp.gateway.ratelimit import AuthRateLimiter, ToolRateLimiter

    tool_rate_limiter = ToolRateLimiter(
        per_subject_rpm=settings.rate_tools_per_min_subject,
        per_client_rpm=settings.rate_tools_per_min_client,
    )
    auth_rate_limiter = AuthRateLimiter(per_ip_rpm=settings.rate_auth_per_min_ip)

    return build_gateway_app(
        settings,
        store,
        auth_rate_limiter=auth_rate_limiter,
        tool_rate_limiter=tool_rate_limiter,
    )


def main() -> None:
    sys.exit(run_gateway(dict(os.environ)))
