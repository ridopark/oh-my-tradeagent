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

Step 3b additions: `PendingAuth`, `GrantRecord`, `CodeRecord`,
`RefreshTokenRecord` dataclasses; extended `InMemoryStore` with methods for
the full OAuth authorization-code flow.
"""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

# ------------------------------------------------------------------
# Step 3b data classes
# ------------------------------------------------------------------


@dataclass
class PendingAuth:
    """One in-flight authorization request (10-minute lifetime)."""

    state: str                          # raw 256-bit state; keyed by this
    client_id: str
    client_state: str | None            # the client's original state param
    redirect_uri: str
    redirect_uri_provided_explicitly: bool
    code_challenge: str
    scopes: list[str]
    resource: str | None
    nonce: str                          # our nonce sent to the IdP
    created_at: float
    expires_at: float
    client_name: str | None = None
    # Set after IdP callback, consumed by /consent POST:
    csrf_hash: str | None = None
    provider: str | None = None
    subject: str | None = None
    tenants: list[str] = field(default_factory=list)


@dataclass
class GrantRecord:
    """One mcp_oauth_grant row."""

    grant_id: str
    client_id: str
    provider: str
    subject: str
    tenants: list[str]
    created_at: float
    last_used_at: float
    revoked_at: float | None = None
    client_name: str | None = None


@dataclass
class CodeRecord:
    """One mcp_oauth_code row."""

    code: str                           # raw; stored as hash in Postgres
    grant_id: str
    client_id: str
    code_challenge: str
    redirect_uri: str
    redirect_uri_provided_explicitly: bool
    resource: str | None
    scopes: list[str]
    subject: str
    expires_at: float
    consumed_at: float | None = None


@dataclass
class RefreshTokenRecord:
    """One mcp_oauth_refresh_token row."""

    token: str                          # raw; stored as hash in Postgres
    grant_id: str
    client_id: str
    scopes: list[str]
    subject: str
    resource: str | None
    expires_at: int                     # epoch seconds
    rotated_at: float | None = None     # None = still valid


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
    """Store interface for the MCP gateway (Steps 3a + 3b + 3c)."""

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

    async def get_membership(self, subject: str) -> list[str]:
        """Return current tenant list for subject (async; use this in app + provider)."""
        ...

    async def store_access_token(
        self,
        *,
        token: str,
        sub: str,
        tenants: list[str],
        client_id: str,
        expires_at: int,
        resource: str | None,
        grant_id: str | None = None,
    ) -> None:
        """Persist an access token (async protocol-level write)."""
        ...

    async def rotate_refresh_token(self, old_token: str, new_token: str) -> bool:
        """Mark old_token rotated; return True on success, False if already rotated."""
        ...

    async def revoke_grant_by_id(self, grant_id: str) -> None:
        """Revoke a grant by its id."""
        ...

    async def sweep_expired_clients(self) -> None:
        """Remove expired-and-never-used DCR clients."""
        ...

    async def is_healthy(self) -> bool:
        """Return True when the store can serve requests (e.g. DB reachable)."""
        ...


class InMemoryStore:
    """In-process store for tests (also the single-process reference implementation).

    Not thread-safe (single-process, asyncio-safe enough for tests).
    """

    def __init__(self, membership: dict[str, list[str]] | None = None) -> None:
        # -- access tokens (Step 3a + 3b) --
        # {token: AccessTokenRecord}
        self._tokens: dict[str, AccessTokenRecord] = {}
        # {grant_id: float} — revoked_at timestamps
        self._revoked: dict[str, float] = {}
        # {grant_id: float} — last_used_at
        self._last_used: dict[str, float] = {}
        # {subject: [tenant_id, ...]} — fake membership for tests
        self._membership: dict[str, list[str]] = dict(membership or {})

        # -- Step 3b additions --
        # {state: PendingAuth}
        self._pending_auths: dict[str, PendingAuth] = {}
        # {grant_id: GrantRecord}
        self._grants: dict[str, GrantRecord] = {}
        # {code: CodeRecord}
        self._codes: dict[str, CodeRecord] = {}
        # {token: RefreshTokenRecord}
        self._refresh_tokens: dict[str, RefreshTokenRecord] = {}
        # {client_id: OAuthClientInformationFull}
        self._clients: dict = {}

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

    async def get_membership(self, subject: str) -> list[str]:
        """Async membership lookup (wraps get_membership_sync)."""
        return self.get_membership_sync(subject)

    async def store_access_token(
        self,
        *,
        token: str,
        sub: str,
        tenants: list[str],
        client_id: str,
        expires_at: int,
        resource: str | None,
        grant_id: str | None = None,
    ) -> None:
        """Async protocol method; delegates to put_access_token."""
        self.put_access_token(
            token=token,
            sub=sub,
            tenants=tenants,
            client_id=client_id,
            expires_at=expires_at,
            resource=resource,
            grant_id=grant_id,
        )

    async def is_healthy(self) -> bool:
        """In-memory store is always healthy."""
        return True

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
        grant_id: str | None = None,
    ) -> None:
        """Seed or store an access token.

        ``grant_id`` is optional; when absent a synthetic id is generated for
        backward-compat with 3a tests that seed tokens directly.
        """
        if grant_id is None:
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

    # ------------------------------------------------------------------
    # Step 3b helpers
    # ------------------------------------------------------------------

    async def put_pending_auth(self, pending: PendingAuth) -> None:
        """Store a pending authorization request."""
        self._pending_auths[pending.state] = pending

    async def get_pending_auth(self, state: str) -> PendingAuth | None:
        """Return the pending auth for *state*, or None."""
        return self._pending_auths.get(state)

    async def update_pending_after_callback(
        self,
        state: str,
        *,
        csrf_hash: str,
        provider: str,
        subject: str,
        tenants: list[str],
    ) -> bool:
        """Fill in the IdP-side fields after the callback; return True if found."""
        pending = self._pending_auths.get(state)
        if pending is None:
            return False
        pending.csrf_hash = csrf_hash
        pending.provider = provider
        pending.subject = subject
        pending.tenants = list(tenants)
        return True

    async def consume_pending_auth(self, state: str) -> PendingAuth | None:
        """Remove and return the pending auth for *state* (single-use)."""
        return self._pending_auths.pop(state, None)

    async def create_grant(
        self,
        *,
        client_id: str,
        provider: str,
        subject: str,
        tenants: list[str],
        client_name: str | None,
    ) -> str:
        """Create a new grant and return its id."""
        grant_id = str(uuid.uuid4())
        now = time.time()
        self._grants[grant_id] = GrantRecord(
            grant_id=grant_id,
            client_id=client_id,
            provider=provider,
            subject=subject,
            tenants=list(tenants),
            created_at=now,
            last_used_at=now,
            client_name=client_name,
        )
        return grant_id

    async def get_grant(self, grant_id: str) -> GrantRecord | None:
        """Return the grant record, or None."""
        return self._grants.get(grant_id)

    async def revoke_grant_by_id(self, grant_id: str) -> None:
        """Revoke a grant (also revokes all its tokens via revoke_grant)."""
        await self.revoke_grant(grant_id)
        # Also stamp the GrantRecord itself so load_refresh_token can detect it
        gr = self._grants.get(grant_id)
        if gr is not None and gr.revoked_at is None:
            gr.revoked_at = time.time()

    async def put_code(self, code: CodeRecord) -> None:
        """Store an authorization code."""
        self._codes[code.code] = code

    async def get_code(self, code: str) -> CodeRecord | None:
        """Return the code record, or None."""
        return self._codes.get(code)

    async def consume_code(self, code: str) -> None:
        """Mark a code as consumed (single-use enforcement)."""
        rec = self._codes.get(code)
        if rec is not None:
            rec.consumed_at = time.time()

    async def put_refresh_token(self, rt: RefreshTokenRecord) -> None:
        """Store a refresh token."""
        self._refresh_tokens[rt.token] = rt

    async def get_refresh_token(self, token: str) -> RefreshTokenRecord | None:
        """Return the refresh token record, or None."""
        return self._refresh_tokens.get(token)

    async def rotate_refresh_token(self, old_token: str, new_token: str) -> bool:
        """Mark *old_token* as rotated; return True on success, False if already rotated."""
        rec = self._refresh_tokens.get(old_token)
        if rec is None:
            return False
        if rec.rotated_at is not None:
            # Already rotated — concurrent double-refresh attempt
            return False
        rec.rotated_at = time.time()
        return True

    async def put_client(self, client) -> None:  # client: OAuthClientInformationFull
        """Store a registered DCR client."""
        self._clients[client.client_id] = client

    async def get_client_record(self, client_id: str):  # -> OAuthClientInformationFull | None
        """Return a registered client, or None."""
        return self._clients.get(client_id)

    async def sweep_expired_clients(self) -> None:
        """Remove clients whose ``expires_at`` has passed and have never been used."""
        now = time.time()
        to_delete = [
            cid
            for cid, c in self._clients.items()
            if hasattr(c, "_expires_at")
            and c._expires_at is not None
            and c._expires_at < now
            and not hasattr(c, "_last_used_at")
        ]
        for cid in to_delete:
            del self._clients[cid]
