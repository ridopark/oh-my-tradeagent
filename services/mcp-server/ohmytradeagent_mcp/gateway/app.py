"""Gateway ASGI app builder.

``build_gateway_app(settings, store, *, bff_transport, idp, fetch_cimd)`` returns
a Starlette ASGI app that exposes the MCP server over Streamable HTTP with a
full OAuth 2.1 AS (Step 3b) or bare token verification (backward-compatible with
Step 3a tests that seed tokens directly).

App structure (outside-in):
  1. KillSwitchMiddleware — 503 for all paths except /healthz when disabled
  2. /healthz           — always 200 {"status":"ok"}
  3. /readyz            — 200 when enabled; 503 when disabled
  4. /.well-known/oauth-protected-resource — RFC 9728 doc (root path)
  5. SDK Starlette app:
       /.well-known/oauth-authorization-server  (patched: adds CIMD flag)
       /authorize, /token, /register, /revoke   (SDK AS routes)
       /oauth/google/callback                   (custom: IdP callback)
       /oauth/consent                           (custom: consent POST)
       /mcp                                     (MCP endpoint, auth-gated)
       /.well-known/oauth-protected-resource/mcp (SDK-generated)
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import ipaddress
import logging
import secrets
import time

_LOG = logging.getLogger(__name__)

from mcp.server.auth.settings import (
    AuthSettings,
    ClientRegistrationOptions,
    RevocationOptions,
)
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import AnyHttpUrl
from starlette.applications import Starlette
from starlette.datastructures import Headers as _Headers
from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from starlette.routing import Route
from starlette.types import ASGIApp, Receive, Scope, Send

from ohmytradeagent_mcp.gateway.assertion import Signer
from ohmytradeagent_mcp.gateway.consent import render_consent
from ohmytradeagent_mcp.gateway.provider import OmtAuthorizationServerProvider
from ohmytradeagent_mcp.gateway.resolver import OAuthIdentityResolver
from ohmytradeagent_mcp.gateway.settings import GatewaySettings
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
        if self._lock is None:
            self._lock = asyncio.Lock()
        return self._lock

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "lifespan":
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
    return _Headers(scope=scope).get("host")


def _host_matches_allowed(host: str, allowed_hosts: list[str]) -> bool:
    if host in allowed_hosts:
        return True
    for pattern in allowed_hosts:
        if pattern.endswith(":*"):
            base = pattern[:-2]
            if host.startswith(base + ":"):
                return True
    return False


class _KillSwitchMiddleware:
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


_AUTH_RATE_PATHS = frozenset(["/authorize", "/token", "/register", "/revoke"])


def _is_auth_rate_path(path: str) -> bool:
    """Return True for endpoints that require per-IP rate limiting."""
    return path in _AUTH_RATE_PATHS or path.startswith("/oauth/")


def _is_peer_trusted(peer_ip: str, cidrs: list) -> bool:
    """Return True when *peer_ip* falls inside any of the given *cidrs*.

    Handles IPv4, IPv6, and IPv4-mapped IPv6 addresses (::ffff:a.b.c.d).
    """
    if not cidrs:
        return False
    try:
        addr: ipaddress.IPv4Address | ipaddress.IPv6Address = ipaddress.ip_address(peer_ip)
        # Unwrap IPv4-mapped IPv6 so "::ffff:10.0.0.1" matches "10.0.0.0/8"
        if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped is not None:
            addr = addr.ipv4_mapped
    except ValueError:
        return False
    for cidr in cidrs:
        try:
            if addr in cidr:
                return True
        except TypeError:
            # IPv4Address vs IPv6Network or vice versa — skip
            pass
    return False


class _AuthRateLimitMiddleware:
    """ASGI middleware that enforces per-IP rate limits on OAuth auth endpoints.

    ``CF-Connecting-IP`` is only trusted when the socket peer address falls
    inside one of the ``trusted_proxy_cidrs`` CIDRs (configured via
    ``OMT_TRUSTED_PROXY_CIDRS``).  When the CIDR list is empty (the default)
    the header is always ignored and the socket peer address is used directly.
    This prevents spoofed headers from untrusted callers.

    Returns HTTP 429 + ``Retry-After`` when the per-IP bucket is exhausted.
    """

    def __init__(self, app: ASGIApp, limiter, *, trusted_proxy_cidrs: list | None = None) -> None:
        self._app = app
        self._limiter = limiter
        self._trusted_proxy_cidrs: list = trusted_proxy_cidrs or []

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        path: str = scope.get("path", "")
        if not _is_auth_rate_path(path):
            await self._app(scope, receive, send)
            return

        # Extract client IP: socket peer first; trust CF-Connecting-IP only when
        # the peer is inside a configured trusted-proxy CIDR.
        client = scope.get("client")
        peer_ip = client[0] if client else "127.0.0.1"

        cf_ip = _Headers(scope=scope).get("cf-connecting-ip")
        if cf_ip and _is_peer_trusted(peer_ip, self._trusted_proxy_cidrs):
            ip = cf_ip.strip()
        else:
            ip = peer_ip

        allowed, retry_after = self._limiter.check(ip)
        if not allowed:
            resp = Response(
                content=b"",
                status_code=429,
                headers={"Retry-After": str(int(retry_after))},
            )
            await resp(scope, receive, send)
            return

        await self._app(scope, receive, send)


def _patch_as_metadata(sdk_app: Starlette, issuer_url, auth_settings) -> None:
    """Replace the SDK's AS metadata route to add ``client_id_metadata_document_supported``."""
    from mcp.server.auth.handlers.metadata import MetadataHandler
    from mcp.server.auth.routes import build_metadata, cors_middleware

    metadata = build_metadata(
        issuer_url,
        None,
        auth_settings.client_registration_options,
        auth_settings.revocation_options,
    )
    metadata.client_id_metadata_document_supported = True

    new_route = Route(
        "/.well-known/oauth-authorization-server",
        endpoint=cors_middleware(MetadataHandler(metadata).handle, ["GET", "OPTIONS"]),
        methods=["GET", "OPTIONS"],
    )

    routes = sdk_app.router.routes
    for i, route in enumerate(routes):
        if getattr(route, "path", None) == "/.well-known/oauth-authorization-server":
            routes[i] = new_route
            return
    # Not found (shouldn't happen when auth_server_provider is set), just append
    routes.append(new_route)


def _make_google_callback_handler(
    provider: OmtAuthorizationServerProvider,
    store,
    settings: GatewaySettings,
):
    """Return the ASGI handler for GET /oauth/google/callback."""

    async def google_callback(request: Request) -> Response:
        code = request.query_params.get("code", "")
        state = request.query_params.get("state", "")
        google_error = request.query_params.get("error", "")

        if not state:
            return JSONResponse({"error": "missing_state"}, status_code=400)

        pending = await store.get_pending_auth(state)
        if pending is None:
            return JSONResponse({"error": "unknown_state"}, status_code=400)

        # Google sent an error (e.g. user denied consent on the Google sign-in page)
        if google_error:
            from mcp.server.auth.provider import construct_redirect_uri
            await store.consume_pending_auth(state)
            redirect_url = construct_redirect_uri(
                pending.redirect_uri,
                error="access_denied",
                state=pending.client_state,
            )
            return RedirectResponse(redirect_url, status_code=302)

        if pending.expires_at < time.time():
            await store.consume_pending_auth(state)
            return JSONResponse({"error": "expired_pending"}, status_code=400)

        # Exchange code with IdP
        try:
            identity = await provider._idp.exchange(code, settings.google_callback_url)
        except Exception:  # noqa: BLE001
            return JSONResponse({"error": "idp_error"}, status_code=502)

        # Verify nonce
        if identity.nonce != pending.nonce:
            return JSONResponse({"error": "nonce_mismatch"}, status_code=400)

        # Verify email is verified
        if not identity.email_verified:
            from mcp.server.auth.provider import construct_redirect_uri
            redirect_url = construct_redirect_uri(
                pending.redirect_uri,
                error="access_denied",
                error_description="email_not_verified",
                state=pending.client_state,
            )
            return RedirectResponse(redirect_url, status_code=302)

        # Look up tenant membership
        tenants = await store.get_membership(identity.sub)
        if not tenants:
            from mcp.server.auth.provider import construct_redirect_uri
            await store.consume_pending_auth(state)
            redirect_url = construct_redirect_uri(
                pending.redirect_uri,
                error="access_denied",
                error_description="not_a_member",
                state=pending.client_state,
            )
            return RedirectResponse(redirect_url, status_code=302)

        # Generate CSRF token
        csrf_token = secrets.token_hex(32)
        csrf_hash = hashlib.sha256(csrf_token.encode()).hexdigest()

        # Update pending auth with identity and CSRF
        await store.update_pending_after_callback(
            state,
            csrf_hash=csrf_hash,
            provider="google",
            subject=identity.sub,
            tenants=tenants,
        )

        # Render consent page
        client_name = pending.client_name
        body, headers = render_consent(
            client_name=client_name,
            client_id=pending.client_id,
            tenants=tenants,
            state=state,
            csrf_token=csrf_token,
        )
        return HTMLResponse(content=body, status_code=200, headers=headers)

    return google_callback


def _make_consent_handler(
    provider: OmtAuthorizationServerProvider,
    store,
    settings: GatewaySettings,
):
    """Return the ASGI handler for POST /oauth/consent."""

    async def consent_post(request: Request) -> Response:
        # Same-origin check: Sec-Fetch-Site must be "same-origin" OR
        # Origin header must match our public URL
        sec_fetch_site = request.headers.get("sec-fetch-site", "")
        origin = request.headers.get("origin", "")
        public_origin = settings.public_url  # e.g. https://tradeagent-mcp.ridopark.com

        if sec_fetch_site:
            if sec_fetch_site != "same-origin":
                return JSONResponse(
                    {"error": "forbidden", "detail": "cross-site request"},
                    status_code=403,
                )
        elif origin and origin.rstrip("/") != public_origin.rstrip("/"):
            return JSONResponse(
                {"error": "forbidden", "detail": "origin mismatch"},
                status_code=403,
            )
        elif not origin:
            # Both Sec-Fetch-Site and Origin are absent — reject to prevent CSRF
            # from non-browser contexts that strip both headers.
            return JSONResponse(
                {"error": "forbidden", "detail": "missing origin"},
                status_code=403,
            )

        form = await request.form()
        state = form.get("state", "")
        csrf = form.get("csrf", "")
        action = form.get("action", "")
        tenant_values = form.getlist("tenants")

        if not state:
            return JSONResponse({"error": "missing_state"}, status_code=400)

        pending = await store.get_pending_auth(state)
        if pending is None:
            return JSONResponse({"error": "unknown_state"}, status_code=400)

        if pending.csrf_hash is None:
            return JSONResponse({"error": "csrf_not_set"}, status_code=400)

        # Verify CSRF (constant-time comparison prevents timing attacks)
        actual_hash = hashlib.sha256(csrf.encode()).hexdigest()
        if not hmac.compare_digest(actual_hash, pending.csrf_hash):
            return JSONResponse({"error": "invalid_csrf"}, status_code=400)

        # Consume pending auth (also invalidates the CSRF — single-use)
        await store.consume_pending_auth(state)

        from mcp.server.auth.provider import construct_redirect_uri

        if action == "deny":
            _LOG.info(
                "consent denied: outcome=denied client_id=%s subject=%s",
                pending.client_id,
                pending.subject or "<unknown>",
            )
            redirect_url = construct_redirect_uri(
                pending.redirect_uri,
                error="access_denied",
                state=pending.client_state,
            )
            return RedirectResponse(redirect_url, status_code=302)

        if action != "approve":
            return JSONResponse({"error": "invalid_action"}, status_code=400)

        # Validate tenants: must be non-empty and a subset of pending.tenants
        if not tenant_values:
            return JSONResponse({"error": "no_tenant_selected"}, status_code=400)

        tenant_set = set(pending.tenants)
        for t in tenant_values:
            if t not in tenant_set:
                return JSONResponse(
                    {"error": "tenant_not_in_set", "tenant": t},
                    status_code=400,
                )

        # Create grant
        grant_id = await store.create_grant(
            client_id=pending.client_id,
            provider=pending.provider or "google",
            subject=pending.subject or "",
            tenants=list(tenant_values),
            client_name=pending.client_name,
        )

        # Mint authorization code (5-min lifetime)
        code_value = secrets.token_hex(32)
        from ohmytradeagent_mcp.gateway.store import CodeRecord
        await store.put_code(
            CodeRecord(
                code=code_value,
                grant_id=grant_id,
                client_id=pending.client_id,
                code_challenge=pending.code_challenge,
                redirect_uri=pending.redirect_uri,
                redirect_uri_provided_explicitly=pending.redirect_uri_provided_explicitly,
                resource=pending.resource,
                scopes=pending.scopes,
                subject=pending.subject or "",
                expires_at=time.time() + 300,  # 5 minutes
            )
        )

        redirect_url = construct_redirect_uri(
            pending.redirect_uri,
            code=code_value,
            state=pending.client_state,
        )
        return RedirectResponse(redirect_url, status_code=302)

    return consent_post


def build_gateway_app(
    settings: GatewaySettings,
    store,
    *,
    bff_transport=None,
    idp=None,
    fetch_cimd=None,
    auth_rate_limiter=None,
    tool_rate_limiter=None,
):
    """Build and return the gateway ASGI app.

    Args:
        settings: Parsed gateway settings.
        store: OAuth store (InMemoryStore for tests; PostgresStore in prod).
        bff_transport: Optional httpx transport override for BFF calls (testing).
        idp: Identity provider (GoogleIdP or FakeIdP; None → build GoogleIdP).
        fetch_cimd: Optional injectable CIMD fetcher (None → real cached fetcher).
        auth_rate_limiter: Optional AuthRateLimiter for OAuth endpoints (None = no limit).
        tool_rate_limiter: Optional ToolRateLimiter for tool calls (None = no limit).

    Returns:
        A callable ASGI app ready to serve.
    """
    if idp is None:
        from ohmytradeagent_mcp.gateway.google import GoogleIdP
        idp = GoogleIdP(
            settings.google_client_id or "",
            settings.google_client_secret or "",
        )

    signer = Signer(settings.signing_key_pem)
    resource_url = AnyHttpUrl(settings.mcp_resource_url)
    issuer_url = AnyHttpUrl(settings.public_url)

    provider = OmtAuthorizationServerProvider(
        store=store,
        idp=idp,
        resource=settings.mcp_resource_url,
        google_callback_url=settings.google_callback_url,
        fetch_cimd_fn=fetch_cimd,
    )

    auth_settings = AuthSettings(
        issuer_url=issuer_url,
        resource_server_url=resource_url,
        required_scopes=["read"],
        validate_token_resource=False,
        client_registration_options=ClientRegistrationOptions(
            enabled=True,
            valid_scopes=["read"],
            default_scopes=["read"],
        ),
        revocation_options=RevocationOptions(enabled=True),
    )

    mcp_server = MCPServer(
        name="omt-mcp",
        description=(
            "Read-only operator view of one oh-my-tradeagent tenant's positions, "
            "risk state and market context.  Cannot trade."
        ),
        auth=auth_settings,
        auth_server_provider=provider,
    )

    resolver = OAuthIdentityResolver(
        store=store,
        signer=signer,
        bff_url=settings.bff_url,
        bff_transport=bff_transport,
        tool_rate_limiter=tool_rate_limiter,
    )
    build_server(resolver, mcp=mcp_server)

    # Register custom routes BEFORE building the Starlette app
    @mcp_server.custom_route("/oauth/google/callback", methods=["GET"])
    async def google_callback(request: Request) -> Response:
        return await _make_google_callback_handler(provider, store, settings)(request)

    @mcp_server.custom_route("/oauth/consent", methods=["POST"])
    async def consent_post(request: Request) -> Response:
        return await _make_consent_handler(provider, store, settings)(request)

    # Allowed hosts: use settings as-is (defaults to public URL host when absent).
    # No loopback auto-append — the caller must explicitly include 127.0.0.1:* in
    # OMT_ALLOWED_HOSTS when running tests that connect via loopback.
    # Host enforcement is done in _CompositeApp (covers ALL routes including custom
    # ones).  The SDK's TransportSecuritySettings is NOT used because it only covers
    # SDK-managed routes, not /healthz, /readyz, or /oauth/* custom routes.
    allowed_hosts = list(settings.allowed_hosts)

    # Disable the SDK's built-in host check: pass an explicit no-op
    # TransportSecuritySettings so the SDK doesn't auto-create localhost-only
    # protection.  Host checking is done in _CompositeApp for ALL routes.
    sdk_app = mcp_server.streamable_http_app(
        streamable_http_path="/mcp",
        json_response=True,
        stateless_http=True,
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
    )

    # Patch AS metadata to add client_id_metadata_document_supported: true
    _patch_as_metadata(sdk_app, issuer_url, auth_settings)

    lazy_sdk_app = _LazyLifespanApp(sdk_app)

    # Build extra routes (health, readyz, root protected-resource metadata)
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
        if not await store.is_healthy():
            return JSONResponse({"error": "store_unhealthy"}, status_code=503)
        return JSONResponse({"status": "ok"})

    extra_routes = [
        Route("/healthz", endpoint=_healthz, methods=["GET"]),
        Route("/readyz", endpoint=_readyz, methods=["GET"]),
        Route(
            "/.well-known/oauth-protected-resource",
            endpoint=_protected_resource_meta,
            methods=["GET"],
        ),
    ]
    extra_starlette = Starlette(routes=extra_routes)

    _allowed_hosts_snapshot = list(allowed_hosts)

    _HANDLED = frozenset([
        "/healthz",
        "/readyz",
        "/.well-known/oauth-protected-resource",
    ])

    class _CompositeApp:
        async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
            if scope["type"] == "http":
                path = scope.get("path", "")
                if path in _HANDLED:
                    await extra_starlette(scope, receive, send)
                    return
                if _allowed_hosts_snapshot:
                    host = _get_host_from_scope(scope)
                    if not host or not _host_matches_allowed(host, _allowed_hosts_snapshot):
                        resp = Response("Invalid Host header", status_code=421)
                        await resp(scope, receive, send)
                        return
                await lazy_sdk_app(scope, receive, send)
            else:
                await lazy_sdk_app(scope, receive, send)

    _inner = _CompositeApp()
    if auth_rate_limiter is not None:
        _inner = _AuthRateLimitMiddleware(  # type: ignore[assignment]
            _inner,
            auth_rate_limiter,
            trusted_proxy_cidrs=settings.trusted_proxy_cidrs,
        )
    composite = _inner
    wrapped = _KillSwitchMiddleware(composite, disabled=settings.disabled)

    class _GatewayApp:
        async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
            if scope["type"] == "lifespan":
                # Connect/close store around the ASGI lifespan event
                async def _store_aware_receive() -> dict:
                    msg = await receive()
                    if msg.get("type") == "lifespan.startup":
                        _connect = getattr(store, "connect", None)
                        if _connect is not None:
                            await _connect()
                    elif msg.get("type") == "lifespan.shutdown":
                        _close = getattr(store, "close", None)
                        if _close is not None:
                            await _close()
                    return msg

                await wrapped(scope, _store_aware_receive, send)
                return
            await wrapped(scope, receive, send)

    return _GatewayApp()  # type: ignore[return-value]
