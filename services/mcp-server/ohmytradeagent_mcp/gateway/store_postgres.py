"""PostgresStore — asyncpg-backed OAuthStore for the MCP gateway (Step 3c).

Implements the same OAuthStore interface as InMemoryStore, using the V17/V18
schema (mcp_oauth_* tables in the dashboard database).  All token-like values
are SHA-256 hex-hashed before storage, matching the V17 column spec.

The mcp_gateway role (V18) is granted ONLY:
  SELECT (provider, subject, tenant_id) ON dashboard_user
so get_membership() selects exactly those three columns and no others.

Atomic refresh-token rotation uses a single
  UPDATE … WHERE token_hash = $1 AND rotated_at IS NULL
and checks the returned rowcount to detect concurrent double-refresh.

Schema notes (V17):
  mcp_oauth_access_token  — no resource column; resource=None always.
  mcp_oauth_refresh_token — no client_id/subject/scopes; JOINs with grant.
  mcp_oauth_code          — no subject column; JOINs with grant.
  mcp_oauth_grant         — has id (UUID), client_id, subject, tenants, revoked_at.
"""
from __future__ import annotations

import hashlib
import json
import time
import uuid
from datetime import UTC, datetime, timedelta

try:
    import asyncpg
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "asyncpg is required for PostgresStore; install with: uv add asyncpg>=0.29"
    ) from exc

from ohmytradeagent_mcp.gateway.store import (
    AccessTokenRecord,
    CodeRecord,
    GrantRecord,
    PendingAuth,
    RefreshTokenRecord,
)


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def _from_ts(dt: datetime | None) -> float | None:
    return dt.timestamp() if dt is not None else None


def _to_ts(epoch: float) -> datetime:
    return datetime.fromtimestamp(float(epoch), tz=UTC)


class PostgresStore:
    """asyncpg-backed store for the MCP gateway.

    Lifecycle::

        store = PostgresStore(dsn)
        await store.connect()      # create pool
        # ... use store ...
        await store.close()        # drain pool

    In production the ASGI lifespan event in ``build_gateway_app`` drives
    connect/close.  In tests the fixture calls them directly.

    ``connect()`` and ``close()`` are idempotent: calling them twice is safe.
    """

    def __init__(self, dsn: str) -> None:
        self._dsn = dsn
        self._pool: asyncpg.Pool | None = None

    async def connect(self) -> None:
        """Create the connection pool with JSON/JSONB codecs.  No-op if already connected.

        asyncpg 0.32 returns JSONB columns as raw strings; the codecs below
        decode them to Python dicts/lists so store methods can call .get() on the
        result without an extra json.loads() call.
        """
        if self._pool is None:
            async def _init_conn(conn: asyncpg.Connection) -> None:
                await conn.set_type_codec(
                    "jsonb",
                    encoder=json.dumps,
                    decoder=json.loads,
                    schema="pg_catalog",
                    format="text",
                )
                await conn.set_type_codec(
                    "json",
                    encoder=json.dumps,
                    decoder=json.loads,
                    schema="pg_catalog",
                    format="text",
                )

            self._pool = await asyncpg.create_pool(self._dsn, init=_init_conn)

    async def close(self) -> None:
        """Drain and close the connection pool.  No-op if already closed."""
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    # ------------------------------------------------------------------
    # Protocol: get_access_token
    # ------------------------------------------------------------------

    async def get_access_token(self, token: str) -> AccessTokenRecord | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT t.grant_id, t.expires_at,
                       g.client_id, g.subject, g.tenants, g.revoked_at
                FROM   mcp_oauth_access_token t
                JOIN   mcp_oauth_grant g ON g.id = t.grant_id
                WHERE  t.token_hash = $1
                """,
                _sha256(token),
            )
        if row is None:
            return None
        return AccessTokenRecord(
            token=token,
            grant_id=str(row["grant_id"]),
            client_id=row["client_id"],
            subject=row["subject"],
            tenants=list(row["tenants"]),
            expires_at=int(row["expires_at"].timestamp()),
            resource=None,  # V17 mcp_oauth_access_token has no resource column
            revoked_at=_from_ts(row["revoked_at"]),
        )

    # ------------------------------------------------------------------
    # Protocol: revoke_grant / bump_last_used
    # ------------------------------------------------------------------

    async def revoke_grant(self, grant_id: str, *, now: float | None = None) -> None:
        ts = _to_ts(now if now is not None else time.time())
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE mcp_oauth_grant"
                " SET revoked_at = $1"
                " WHERE id = $2 AND revoked_at IS NULL",
                ts,
                uuid.UUID(grant_id),
            )

    async def bump_last_used(self, grant_id: str, *, interval_s: int = 60) -> None:
        cutoff = datetime.now(tz=UTC) - timedelta(seconds=interval_s)
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                UPDATE mcp_oauth_grant
                SET    last_used_at = now()
                WHERE  id = $1
                  AND  (last_used_at IS NULL OR last_used_at < $2)
                """,
                uuid.UUID(grant_id),
                cutoff,
            )

    # ------------------------------------------------------------------
    # Protocol: get_membership
    # ------------------------------------------------------------------

    async def get_membership(self, subject: str) -> list[str]:
        """Return tenant ids for *subject* from dashboard_user.

        Only the three columns the mcp_gateway role is granted are accessed:
        ``provider``, ``subject``, ``tenant_id``.  Never ``SELECT *`` or ``email``.
        """
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT tenant_id"
                " FROM dashboard_user"
                " WHERE provider = $1 AND subject = $2",
                "google",
                subject,
            )
        return [row["tenant_id"] for row in rows]

    # ------------------------------------------------------------------
    # Protocol: store_access_token
    # ------------------------------------------------------------------

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
        if grant_id is None:
            raise ValueError("PostgresStore.store_access_token requires grant_id")
        ts = _to_ts(expires_at)
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO mcp_oauth_access_token (token_hash, grant_id, expires_at)
                VALUES ($1, $2, $3)
                ON CONFLICT (token_hash) DO NOTHING
                """,
                _sha256(token),
                uuid.UUID(grant_id),
                ts,
            )

    # ------------------------------------------------------------------
    # Protocol: rotate_refresh_token / revoke_grant_by_id / sweep / healthy
    # ------------------------------------------------------------------

    async def rotate_refresh_token(self, old_token: str, new_token: str) -> bool:
        """Atomically mark *old_token* as rotated, recording the new token's hash.

        Returns ``True`` when this call performed the rotation (UPDATE rowcount 1).
        Returns ``False`` when already rotated — concurrent double-refresh detected.
        The caller must revoke the grant when this returns ``False``.

        ``new_token`` must be the raw value of the replacement token so that
        ``replaced_by_hash`` stores the hash of the ACTUAL successor, enabling
        audit correlation.  Pass a non-empty string; an empty string stores NULL.
        """
        async with self._pool.acquire() as conn:
            status = await conn.execute(
                """
                UPDATE mcp_oauth_refresh_token
                SET    rotated_at = now(),
                       replaced_by_hash = $2
                WHERE  token_hash = $1 AND rotated_at IS NULL
                """,
                _sha256(old_token),
                _sha256(new_token) if new_token else None,
            )
        # asyncpg returns "UPDATE N" as a string
        return status == "UPDATE 1"

    async def revoke_grant_by_id(self, grant_id: str) -> None:
        """Revoke a grant (also revokes all its tokens via revoke_grant)."""
        await self.revoke_grant(grant_id)

    async def sweep_expired_clients(self) -> None:
        """Delete DCR clients whose expires_at has passed and that were never used."""
        async with self._pool.acquire() as conn:
            await conn.execute(
                "DELETE FROM mcp_oauth_client"
                " WHERE expires_at < now() AND last_used_at IS NULL"
            )

    async def is_healthy(self) -> bool:
        """Return True when the pool is up and the DB responds."""
        try:
            async with self._pool.acquire() as conn:
                await conn.execute("SELECT 1")
            return True
        except Exception:  # noqa: BLE001
            return False

    # ------------------------------------------------------------------
    # Step 3b: Pending auth
    # ------------------------------------------------------------------

    async def put_pending_auth(self, pending: PendingAuth) -> None:
        state_hash = _sha256(pending.state)
        # Pass dict directly — the pool's init codec encodes it as JSONB.
        # Never json.dumps() here: that would cause double-encoding (str→str).
        params = {
            "client_state": pending.client_state,
            "redirect_uri": pending.redirect_uri,
            "redirect_uri_provided_explicitly": pending.redirect_uri_provided_explicitly,
            "code_challenge": pending.code_challenge,
            "scopes": pending.scopes,
            "resource": pending.resource,
            "nonce": pending.nonce,
            "client_name": pending.client_name,
        }
        expires_at = _to_ts(pending.expires_at)
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO mcp_oauth_pending_auth
                  (state_hash, client_id, params, expires_at)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT (state_hash) DO NOTHING
                """,
                state_hash,
                pending.client_id,
                params,
                expires_at,
            )

    async def get_pending_auth(self, state: str) -> PendingAuth | None:
        state_hash = _sha256(state)
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM mcp_oauth_pending_auth WHERE state_hash = $1",
                state_hash,
            )
        if row is None:
            return None
        params: dict = row["params"]  # decoded by the pool's init codec
        return PendingAuth(
            state=state,
            client_id=row["client_id"],
            client_state=params.get("client_state"),
            redirect_uri=params.get("redirect_uri", ""),
            redirect_uri_provided_explicitly=params.get("redirect_uri_provided_explicitly", False),
            code_challenge=params.get("code_challenge", ""),
            scopes=params.get("scopes", []),
            resource=params.get("resource"),
            nonce=params.get("nonce", ""),
            created_at=row["created_at"].timestamp(),
            expires_at=row["expires_at"].timestamp(),
            client_name=params.get("client_name"),
            csrf_hash=row["csrf_hash"],
            provider=row["provider"],
            subject=row["subject"],
            tenants=list(row["tenants"]) if row["tenants"] is not None else [],
        )

    async def update_pending_after_callback(
        self,
        state: str,
        *,
        csrf_hash: str,
        provider: str,
        subject: str,
        tenants: list[str],
    ) -> bool:
        state_hash = _sha256(state)
        async with self._pool.acquire() as conn:
            status = await conn.execute(
                """
                UPDATE mcp_oauth_pending_auth
                SET    csrf_hash = $2,
                       provider  = $3,
                       subject   = $4,
                       tenants   = $5
                WHERE  state_hash = $1
                """,
                state_hash,
                csrf_hash,
                provider,
                subject,
                tenants,
            )
        return status == "UPDATE 1"

    async def consume_pending_auth(self, state: str) -> PendingAuth | None:
        pending = await self.get_pending_auth(state)
        if pending is None:
            return None
        state_hash = _sha256(state)
        async with self._pool.acquire() as conn:
            await conn.execute(
                "DELETE FROM mcp_oauth_pending_auth WHERE state_hash = $1",
                state_hash,
            )
        return pending

    # ------------------------------------------------------------------
    # Step 3b: Grants
    # ------------------------------------------------------------------

    async def create_grant(
        self,
        *,
        client_id: str,
        provider: str,
        subject: str,
        tenants: list[str],
        client_name: str | None,
    ) -> str:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO mcp_oauth_grant
                  (client_id, client_name, provider, subject, tenants)
                VALUES ($1, $2, $3, $4, $5)
                RETURNING id
                """,
                client_id,
                client_name,
                provider,
                subject,
                tenants,
            )
        return str(row["id"])

    async def get_grant(self, grant_id: str) -> GrantRecord | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM mcp_oauth_grant WHERE id = $1",
                uuid.UUID(grant_id),
            )
        if row is None:
            return None
        return GrantRecord(
            grant_id=str(row["id"]),
            client_id=row["client_id"],
            provider=row["provider"],
            subject=row["subject"],
            tenants=list(row["tenants"]),
            created_at=row["created_at"].timestamp(),
            last_used_at=(
                row["last_used_at"].timestamp()
                if row["last_used_at"] is not None
                else row["created_at"].timestamp()
            ),
            revoked_at=_from_ts(row["revoked_at"]),
            client_name=row["client_name"],
        )

    # ------------------------------------------------------------------
    # Step 3b: Authorization codes
    # ------------------------------------------------------------------

    async def put_code(self, code: CodeRecord) -> None:
        code_hash = _sha256(code.code)
        expires_at = _to_ts(code.expires_at)
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO mcp_oauth_code
                  (code_hash, grant_id, client_id, code_challenge, redirect_uri,
                   redirect_uri_provided_explicitly, resource, scopes, expires_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                """,
                code_hash,
                uuid.UUID(code.grant_id),
                code.client_id,
                code.code_challenge,
                code.redirect_uri,
                code.redirect_uri_provided_explicitly,
                code.resource,
                code.scopes,
                expires_at,
            )

    async def get_code(self, code: str) -> CodeRecord | None:
        """Return CodeRecord; JOINs with grant for subject (not in V17 code table)."""
        code_hash = _sha256(code)
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT c.*, g.subject
                FROM   mcp_oauth_code c
                JOIN   mcp_oauth_grant g ON g.id = c.grant_id
                WHERE  c.code_hash = $1
                """,
                code_hash,
            )
        if row is None:
            return None
        return CodeRecord(
            code=code,
            grant_id=str(row["grant_id"]),
            client_id=row["client_id"],
            code_challenge=row["code_challenge"],
            redirect_uri=row["redirect_uri"],
            redirect_uri_provided_explicitly=row["redirect_uri_provided_explicitly"],
            resource=row["resource"],
            scopes=list(row["scopes"]) if row["scopes"] is not None else [],
            subject=row["subject"],
            expires_at=row["expires_at"].timestamp(),
            consumed_at=_from_ts(row["consumed_at"]),
        )

    async def consume_code(self, code: str) -> bool:
        """Atomically mark *code* as consumed.

        Returns True when this call performed the marking (rowcount 1).
        Returns False when already consumed — concurrent double-use detected.
        The caller in provider.py revokes the grant when this returns False.
        """
        code_hash = _sha256(code)
        async with self._pool.acquire() as conn:
            status = await conn.execute(
                "UPDATE mcp_oauth_code SET consumed_at = now()"
                " WHERE code_hash = $1 AND consumed_at IS NULL",
                code_hash,
            )
        return status == "UPDATE 1"

    # ------------------------------------------------------------------
    # Step 3b: Refresh tokens
    # ------------------------------------------------------------------

    async def put_refresh_token(self, rt: RefreshTokenRecord) -> None:
        token_hash = _sha256(rt.token)
        expires_at = _to_ts(rt.expires_at)
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO mcp_oauth_refresh_token (token_hash, grant_id, expires_at)
                VALUES ($1, $2, $3)
                """,
                token_hash,
                uuid.UUID(rt.grant_id),
                expires_at,
            )

    async def get_refresh_token(self, token: str) -> RefreshTokenRecord | None:
        """Return RefreshTokenRecord; JOINs with grant for client_id/subject (not in V17)."""
        token_hash = _sha256(token)
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT t.grant_id, t.expires_at, t.rotated_at,
                       g.client_id, g.subject
                FROM   mcp_oauth_refresh_token t
                JOIN   mcp_oauth_grant g ON g.id = t.grant_id
                WHERE  t.token_hash = $1
                """,
                token_hash,
            )
        if row is None:
            return None
        return RefreshTokenRecord(
            token=token,
            grant_id=str(row["grant_id"]),
            client_id=row["client_id"],
            scopes=["read"],  # V17 mcp_oauth_refresh_token has no scopes column
            subject=row["subject"],
            resource=None,  # V17 mcp_oauth_refresh_token has no resource column
            expires_at=int(row["expires_at"].timestamp()),
            rotated_at=_from_ts(row["rotated_at"]),
        )

    # ------------------------------------------------------------------
    # Step 3b: DCR clients
    # ------------------------------------------------------------------

    async def put_client(self, client) -> None:  # client: OAuthClientInformationFull
        """Upsert a DCR client.  On conflict (same client_id), update metadata."""
        # Pass dict directly — the pool's init codec encodes it as JSONB.
        metadata = {
            "client_id": client.client_id,
            "client_name": client.client_name,
            "redirect_uris": [str(u) for u in client.redirect_uris],
            "grant_types": list(client.grant_types or []),
            "response_types": list(client.response_types or []),
            "scope": client.scope or "read",
            "token_endpoint_auth_method": client.token_endpoint_auth_method or "none",
        }
        expires_at = datetime.now(tz=UTC) + timedelta(days=30)
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO mcp_oauth_client (client_id, metadata, expires_at)
                VALUES ($1, $2, $3)
                ON CONFLICT (client_id) DO UPDATE
                  SET metadata     = EXCLUDED.metadata,
                      expires_at   = now() + interval '30 days',
                      last_used_at = now()
                """,
                client.client_id,
                metadata,
                expires_at,
            )

    async def get_client_record(self, client_id: str):  # -> OAuthClientInformationFull | None
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT client_id, metadata"
                " FROM mcp_oauth_client"
                " WHERE client_id = $1",
                client_id,
            )
        if row is None:
            return None
        from mcp.shared.auth import OAuthClientInformationFull
        from pydantic import AnyUrl

        meta: dict = row["metadata"]  # decoded by the pool's init codec
        return OAuthClientInformationFull(
            client_id=row["client_id"],
            redirect_uris=[AnyUrl(u) for u in meta.get("redirect_uris", [])],
            client_name=meta.get("client_name"),
            grant_types=meta.get("grant_types", ["authorization_code"]),
            response_types=meta.get("response_types", ["code"]),
            scope=meta.get("scope", "read"),
            token_endpoint_auth_method=meta.get("token_endpoint_auth_method", "none"),
            client_secret=None,  # never store/return secrets
        )
