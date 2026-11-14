"""StoreTokenVerifier — resource-server token verifier backed by InMemoryStore / PostgresStore.

Per-request flow:
1. Look up token → `AccessTokenRecord`
2. Check expiry (epoch seconds)
3. Check revocation (grant revoked_at is not None)
4. Check resource indicator (if present, must match this gateway's /mcp URL)
5. Re-check membership (intersect grant's tenant list with current membership)
6. If intersection is empty → reject (membership was removed since the grant was issued)
7. Return `AccessToken` with `claims={"tenants": valid_tenants, "grant_id": rec.grant_id}`
"""
from __future__ import annotations

import time

from mcp.server.auth.provider import AccessToken

from ohmytradeagent_mcp.gateway.store import InMemoryStore


class StoreTokenVerifier:
    """TokenVerifier that delegates to an OAuthStore, checking expiry, revocation,
    resource indicator, and live tenant membership on every request."""

    def __init__(self, store: InMemoryStore, *, resource: str) -> None:
        """
        Args:
            store: The OAuth store to look tokens up in.
            resource: The expected OAuth resource indicator (e.g. https://…/mcp).
        """
        self._store = store
        self._resource = resource

    async def verify_token(self, token: str) -> AccessToken | None:
        """Verify *token* and return an `AccessToken` if valid, or None."""
        rec = await self._store.get_access_token(token)
        if rec is None:
            return None

        # Expiry check
        if rec.expires_at < int(time.time()):
            return None

        # Revocation check
        if rec.revoked_at is not None:
            return None

        # Resource indicator check: absent → accept; present and wrong → reject
        if rec.resource is not None and rec.resource != self._resource:
            return None

        # Membership re-check: intersect grant tenants with live membership
        current = set(self._store.get_membership_sync(rec.subject))
        valid_tenants = [t for t in rec.tenants if t in current]
        if not valid_tenants:
            return None

        return AccessToken(
            token=token,
            client_id=rec.client_id,
            scopes=["read"],
            expires_at=rec.expires_at,
            resource=rec.resource,
            subject=rec.subject,
            claims={"tenants": valid_tenants, "grant_id": rec.grant_id},
        )
