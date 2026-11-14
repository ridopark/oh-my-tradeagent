"""OAuth store: Protocol + InMemoryStore.

`OAuthStore` is the interface that both `InMemoryStore` (tests / single-process)
and `PostgresStore` (Step 3c) implement.

For Step 3a this file ships the Protocol and the in-memory implementation.
The Postgres implementation arrives in Step 3c.

Token storage: tokens are stored by their raw string (the opaque bearer).  The
`AccessTokenRecord` carries all fields needed to build an `AccessToken` for the
SDK.  Hashing is done at the store boundary: `InMemoryStore` stores raw strings
(for simplicity in tests), while `PostgresStore` will store SHA-256 hex hashes,
matching V17.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Protocol, runtime_checkable


@dataclass
class AccessTokenRecord:
    """One row of mcp_oauth_access_token JOIN mcp_oauth_grant."""

    token: str
    grant_id: str
    client_id: str
    subject: str
    tenants: list[str]
    expires_at: int  # epoch seconds
    resource: str | None = None
    revoked_at: float | None = None  # grant revoked_at


@runtime_checkable
class OAuthStore(Protocol):
    """Minimal store interface for Step 3a."""

    async def get_access_token(self, token: str) -> AccessTokenRecord | None:
        """Return the token record if found, or None."""
        ...

    async def revoke_grant(self, grant_id: str, *, now: float | None = None) -> None:
        """Mark all tokens for grant_id as revoked."""
        ...

    async def bump_last_used(self, grant_id: str, *, interval_s: int = 60) -> None:
        """Update last_used_at on the grant, at most once per interval_s."""
        ...

    def get_membership_sync(self, subject: str) -> list[str]:
        """Return current tenant list for the given Google subject (synchronous, for re-checks)."""
        ...


class InMemoryStore:
    """In-process store for tests (also the single-process reference implementation).

    Not thread-safe (single-process, asyncio-safe enough for tests).
    """

    def __init__(self, membership: dict[str, list[str]] | None = None) -> None:
        # {token: AccessTokenRecord}
        self._tokens: dict[str, AccessTokenRecord] = {}
        # {grant_id: float | None} — revoked_at timestamps
        self._revoked: dict[str, float] = {}
        # {grant_id: float} — last_used_at
        self._last_used: dict[str, float] = {}
        # {subject: [tenant_id, ...]} — fake membership for tests
        self._membership: dict[str, list[str]] = dict(membership or {})

    # ------------------------------------------------------------------
    # OAuthStore protocol
    # ------------------------------------------------------------------

    async def get_access_token(self, token: str) -> AccessTokenRecord | None:
        rec = self._tokens.get(token)
        if rec is None:
            return None
        # Attach current grant revoked_at
        rec = AccessTokenRecord(
            token=rec.token,
            grant_id=rec.grant_id,
            client_id=rec.client_id,
            subject=rec.subject,
            tenants=list(rec.tenants),
            expires_at=rec.expires_at,
            resource=rec.resource,
            revoked_at=self._revoked.get(rec.grant_id),
        )
        return rec

    async def revoke_grant(self, grant_id: str, *, now: float | None = None) -> None:
        self._revoked[grant_id] = now if now is not None else time.time()

    async def bump_last_used(self, grant_id: str, *, interval_s: int = 60) -> None:
        last = self._last_used.get(grant_id, 0.0)
        now = time.time()
        if now - last >= interval_s:
            self._last_used[grant_id] = now

    def get_membership_sync(self, subject: str) -> list[str]:
        return list(self._membership.get(subject, []))

    # ------------------------------------------------------------------
    # Test helpers (not part of the Protocol)
    # ------------------------------------------------------------------

    def put_access_token(
        self,
        *,
        token: str,
        sub: str,
        tenants: list[str],
        client_id: str,
        expires_at: int,
        resource: str | None,
        revoked: bool = False,
    ) -> None:
        """Seed an access token for tests."""
        grant_id = f"grant-{token}"
        rec = AccessTokenRecord(
            token=token,
            grant_id=grant_id,
            client_id=client_id,
            subject=sub,
            tenants=list(tenants),
            expires_at=expires_at,
            resource=resource,
        )
        self._tokens[token] = rec
        if revoked:
            self._revoked[grant_id] = time.time() - 1

    def remove_membership(self, subject: str, tenant: str) -> None:
        """Remove one tenant from a subject's membership (simulate membership loss)."""
        tenants = self._membership.get(subject, [])
        self._membership[subject] = [t for t in tenants if t != tenant]

    def set_membership(self, subject: str, tenants: list[str]) -> None:
        """Set the full tenant list for a subject."""
        self._membership[subject] = list(tenants)
