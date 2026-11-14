"""Gateway ASGI app builder.

`build_gateway_app(settings, store, *, bff_transport=None)` returns a Starlette
ASGI app that exposes the MCP server over Streamable HTTP with per-request
OAuth token verification.

The app structure (outside-in):
  1. KillSwitchMiddleware — 503 for all paths except /healthz when disabled
  2. /healthz           — always 200 {"status":"ok"}
  3. /readyz            — 200 {"status":"ok"} when enabled; 503 when disabled
  4. /.well-known/oauth-protected-resource      — RFC 9728 protected-resource doc
  5. /.well-known/oauth-protected-resource/mcp  — same (SDK-generated)
  6. /mcp               — MCP Streamable HTTP endpoint, auth-gated by bearer
                          token from InMemoryStore / PostgresStore
"""
from __future__ import annotations

import asyncio

from mcp.server.auth.settings import AuthSettings
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import AnyHttpUrl
from starlette.applications import Starlette
from starlette.datastructures import Headers as _Headers
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route
from starlette.types import ASGIApp, Receive, Scope, Send

from ohmytradeagent_mcp.gateway.assertion import Signer
from ohmytradeagent_mcp.gateway.resolver import OAuthIdentityResolver
from ohmytradeagent_mcp.gateway.settings import GatewaySettings
from ohmytradeagent_mcp.gateway.store import InMemoryStore
from ohmytradeagent_mcp.gateway.verifier import StoreTokenVerifier
from ohmytradeagent_mcp.tools import build_server


class _LazyLifespanApp:
    """Trigger ASGI lifespan on the first HTTP request.

    ``httpx.ASGITransport`` does not send ASGI lifespan events, so MCPServer's
    ``StreamableHTTPSessionManager`` task group is never initialised in tests.
    This wrapper starts the lifespan lazily on the first HTTP request and keeps
    the lifespan task alive for the ASGI app's lifetime.

    When running under uvicorn the real lifespan event is forwarded unchanged,
    so this wrapper is a no-op in production.
    """

    __slots__ = ("_app", "_lifespan_task", "_lock", "_started")

    def __init__(self, app: ASGIApp) -> None:
        self._app = app
        self._started = False
        self._lock: asyncio.Lock | None = None
        self._lifespan_task: asyncio.Task | None = None  # type: ignore[type-arg]

    def _get_lock(self) -> asyncio.Lock:
        # Create the lock lazily so it is bound to the running event loop.
        if self._lock is None:
            self._lock = asyncio.Lock()
        return self._lock

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "lifespan":
            # Pass real lifespan events through (e.g. from uvicorn) and mark as
            # started when startup.complete is sent, so the lazy path is skipped.
            async def _intercept_send(msg: dict) -> None:
                if msg.get("type") == "lifespan.startup.complete":
                    self._started = True
                await send(msg)

            await self._app(scope, receive, _intercept_send)
            return

        if scope["type"] == "http" and not self._started:
            lock = self._get_lock()
            async with lock:
                if not self._started:
                    await self._start_lifespan()

        await self._app(scope, receive, send)

    async def _start_lifespan(self) -> None:
        q: asyncio.Queue[dict] = asyncio.Queue()
        ready: asyncio.Event = asyncio.Event()

        async def _receive() -> dict:
            return await q.get()

        async def _send_msg(msg: dict) -> None:
            if msg.get("type") == "lifespan.startup.complete":
                ready.set()

        loop = asyncio.get_running_loop()
        self._lifespan_task = loop.create_task(
            self._app({"type": "lifespan", "asgi": {"version": "3.0"}}, _receive, _send_msg)
        )
        await q.put({"type": "lifespan.startup"})
        await asyncio.wait_for(ready.wait(), timeout=10.0)
        self._started = True


def _get_host_from_scope(scope: Scope) -> str | None:
    """Return the ``Host`` header value from an ASGI HTTP scope, or ``None``."""
    return _Headers(scope=scope).get("host")


def _host_matches_allowed(host: str, allowed_hosts: list[str]) -> bool:
    """Return True if *host* matches any entry in *allowed_hosts*.

    Supports trailing ``:*`` patterns for wildcard-port matching, e.g.
    ``"127.0.0.1:*"`` matches ``"127.0.0.1:12345"``.
    """
    if host in allowed_hosts:
        return True
    for pattern in allowed_hosts:
        if pattern.endswith(":*"):
            base = pattern[:-2]
            if host.startswith(base + ":"):
                return True
    return False


class _KillSwitchMiddleware:
    """Return 503 for every path except /healthz when gateway is disabled."""

    def __init__(self, app: ASGIApp, *, disabled: bool) -> None:
        self._app = app
        self._disabled = disabled

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        path: str = scope.get("path", "")
        if self._disabled and path != "/healthz":
            resp = JSONResponse({"error": "disabled"}, status_code=503)
            await resp(scope, receive, send)
            return

        await self._app(scope, receive, send)


def build_gateway_app(
    settings: GatewaySettings,
    store: InMemoryStore,
    *,
    bff_transport=None,
) -> Starlette:
    """Build and return the gateway Starlette ASGI app.

    Args:
        settings: Parsed gateway settings.
        store: OAuth token store (InMemoryStore for tests; PostgresStore in prod).
        bff_transport: Optional httpx transport override for BFF calls (testing).

    Returns:
        A Starlette app ready to serve.  Wrap it in uvicorn for production.
    """
    signer = Signer(settings.signing_key_pem)

    # Build MCP server with auth (resource-server only; issuer_url = gateway itself
    # because Step 3b will add the AS here; for 3a it's a placeholder that makes
    # the SDK generate valid protected-resource metadata).
    resource_url = AnyHttpUrl(settings.mcp_resource_url)
    issuer_url = AnyHttpUrl(settings.public_url)

    auth_settings = AuthSettings(
        issuer_url=issuer_url,
        resource_server_url=resource_url,
        required_scopes=["read"],
        validate_token_resource=False,  # StoreTokenVerifier checks resource itself
    )
    verifier = StoreTokenVerifier(store, resource=settings.mcp_resource_url)

    mcp_server = MCPServer(
        name="omt-mcp",
        description=(
            "Read-only operator view of one oh-my-tradeagent tenant's positions, "
            "risk state and market context.  Cannot trade."
        ),
        auth=auth_settings,
        token_verifier=verifier,
    )

    # Scope callable: OAuthIdentityResolver reads the auth contextvar per tool call
    resolver = OAuthIdentityResolver(
        store=store,
        signer=signer,
        bff_url=settings.bff_url,
        bff_transport=bff_transport,
    )

    # Register tools + prompt on the pre-built MCPServer
    build_server(resolver, mcp=mcp_server)

    # Allowed hosts: configured hosts + always allow localhost for local/e2e testing
    allowed_hosts = list(settings.allowed_hosts)
    for _local in ("127.0.0.1:*", "localhost:*", "[::1]:*"):
        if _local not in allowed_hosts:
            allowed_hosts.append(_local)

    transport_security: TransportSecuritySettings | None
    if allowed_hosts:
        transport_security = TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=allowed_hosts,
        )
    else:
        transport_security = None

    # Build the SDK Starlette app (includes /mcp, auth middleware, and
    # /.well-known/oauth-protected-resource/mcp route)
    sdk_app = mcp_server.streamable_http_app(
        streamable_http_path="/mcp",
        json_response=True,
        stateless_http=True,
        transport_security=transport_security,
    )

    # Wrap the SDK app to lazily start its ASGI lifespan on the first HTTP
    # request.  httpx.ASGITransport (used in tests) skips lifespan events, which
    # would leave StreamableHTTPSessionManager._task_group as None.  Under
    # uvicorn the real lifespan is forwarded unchanged.
    lazy_sdk_app = _LazyLifespanApp(sdk_app)

    # Build a bare protected-resource metadata doc for the extra route
    _resource_metadata = {
        "resource": settings.mcp_resource_url,
        "authorization_servers": [settings.public_url],
        "scopes_supported": ["read"],
        "bearer_methods_supported": ["header"],
    }

    async def _protected_resource_meta(request: Request) -> Response:
        return JSONResponse(_resource_metadata)

    async def _healthz(request: Request) -> Response:
        return JSONResponse({"status": "ok"})

    async def _readyz(request: Request) -> Response:
        if settings.disabled:
            return JSONResponse({"error": "disabled"}, status_code=503)
        return JSONResponse({"status": "ok"})

    # Mount health routes + extra metadata route on a wrapper Starlette app
    # so they don't go through the SDK's transport security / auth middleware.
    # The kill-switch middleware wraps everything.
    extra_routes = [
        Route("/healthz", endpoint=_healthz, methods=["GET"]),
        Route("/readyz", endpoint=_readyz, methods=["GET"]),
        # RFC 9728 §3.1: canonical path WITHOUT the resource suffix
        Route(
            "/.well-known/oauth-protected-resource",
            endpoint=_protected_resource_meta,
            methods=["GET"],
        ),
    ]

    # We need a composite app:
    # - /healthz, /readyz, /.well-known/oauth-protected-resource handled by our routes
    # - everything else goes to the lazy SDK app (with pre-auth host guard)
    extra_starlette = Starlette(routes=extra_routes)

    # Capture the allowed_hosts list in the closure so _CompositeApp can check it.
    _allowed_hosts_snapshot = list(allowed_hosts)

    class _CompositeApp:
        _HANDLED = frozenset([
            "/healthz",
            "/readyz",
            "/.well-known/oauth-protected-resource",
        ])

        async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
            if scope["type"] == "http":
                path = scope.get("path", "")
                if path in self._HANDLED:
                    await extra_starlette(scope, receive, send)
                    return
                # Apply DNS-rebinding host guard BEFORE the SDK's auth middleware
                # so a disallowed host gets 421 even when no bearer is present.
                if _allowed_hosts_snapshot:
                    host = _get_host_from_scope(scope)
                    if not host or not _host_matches_allowed(host, _allowed_hosts_snapshot):
                        resp = Response("Invalid Host header", status_code=421)
                        await resp(scope, receive, send)
                        return
                await lazy_sdk_app(scope, receive, send)
            else:
                await lazy_sdk_app(scope, receive, send)

    composite = _CompositeApp()
    wrapped = _KillSwitchMiddleware(composite, disabled=settings.disabled)

    # Wrap in Starlette for type compatibility (some test helpers expect Starlette)
    # but we need the actual ASGI app to be the wrapped one.
    # Return a lightweight Starlette that delegates to the wrapped composite.
    class _GatewayApp:
        """Thin ASGI wrapper that is callable like Starlette."""

        async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
            await wrapped(scope, receive, send)

    return _GatewayApp()  # type: ignore[return-value]
