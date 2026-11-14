"""OAuthIdentityResolver — the per-request scope callable for HTTP mode.

Reads the authenticated `AccessToken` from the MCP auth contextvar, applies
tenant-argument semantics, and returns `(TenantBinding, BffClient)` ready for
tool use.

Tenant-argument semantics:
- One tenant in grant: tenant arg is optional; if supplied and matches → OK;
  if supplied and mismatches → ToolError (reveals only the requested name).
- Two or more tenants in grant: tenant arg is required; if absent → ToolError
  (message says "tenant required", never lists the allowed names); if present
  but not in the grant → ToolError (names only the requested value).
"""
from __future__ import annotations

import hashlib

from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.mcpserver.exceptions import ToolError

from ohmytradeagent_mcp.bff import BffClient
from ohmytradeagent_mcp.gateway.assertion import Signer
from ohmytradeagent_mcp.resolver import TenantBinding


class OAuthIdentityResolver:
    """Callable scope for HTTP mode: (tenant: str | None) → (TenantBinding, BffClient)."""

    def __init__(
        self,
        store,
        signer: Signer,
        bff_url: str,
        bff_transport=None,
        tool_rate_limiter=None,
    ) -> None:
        self._store = store
        self._signer = signer
        self._bff_url = bff_url
        self._bff_transport = bff_transport
        self._tool_rate_limiter = tool_rate_limiter

    async def __call__(
        self, tenant: str | None
    ) -> tuple[TenantBinding, BffClient]:
        access_token = get_access_token()
        if access_token is None:
            raise ToolError("not authenticated: no bearer token")

        sub = access_token.subject or ""
        client_id = access_token.client_id

        # Apply tool rate limit before any other work
        if self._tool_rate_limiter is not None:
            self._tool_rate_limiter.check(sub, client_id)
        claims = access_token.claims or {}
        grant_tenants: list[str] = claims.get("tenants", [])

        if not grant_tenants:
            raise ToolError("not authorised: token has no tenant access")

        if len(grant_tenants) == 1:
            resolved = grant_tenants[0]
            if tenant is not None and tenant != resolved:
                raise ToolError(f"not authorised for tenant {tenant!r}")
        else:
            # 2+ tenants: tenant arg required; must not reveal the grant's tenant list
            if tenant is None:
                raise ToolError(
                    "tenant required: this token covers multiple tenants — "
                    "pass tenant= to identify which one"
                )
            if tenant not in grant_tenants:
                raise ToolError(f"not authorised for tenant {tenant!r}")
            resolved = tenant

        # Fresh assertion per BFF call: capture sub/resolved/client_id in closure
        _sub = sub
        _resolved = resolved
        _client_id = client_id

        def _make_assertion() -> str:
            return self._signer.assertion(_sub, _resolved, _client_id)

        token_id = "dlg-" + hashlib.sha256(f"google:{sub}".encode()).hexdigest()[:12]

        binding = TenantBinding(
            tenant_id=resolved,
            token_id=token_id,
            client_id=client_id,
        )
        bff = BffClient(
            base_url=self._bff_url,
            token=_make_assertion,
            tenant=resolved,
            transport=self._bff_transport,
        )
        return binding, bff
