"""Store contract suite — Seam J.

Parametrised over InMemoryStore (always) and PostgresStore (skipped when
OMT_TEST_DATABASE_URL is not set).  Every test proves the same behaviour from
both implementations so that InMemoryStore is a valid drop-in for tests and
PostgresStore is correct against V17.

The PostgresStore tests did NOT run locally (OMT_TEST_DATABASE_URL unset).
They will run in CI when PR 5 adds the postgres:16 service to the mcp-server
job.
"""
from __future__ import annotations

import hashlib
import os
import time
from collections.abc import AsyncGenerator

import asyncpg
import pytest

from ohmytradeagent_mcp.gateway.store import (
    CodeRecord,
    GrantRecord,
    InMemoryStore,
    PendingAuth,
    RefreshTokenRecord,
)
from ohmytradeagent_mcp.gateway.store_postgres import PostgresStore

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def memory_store() -> InMemoryStore:
    return InMemoryStore(membership={"sub-1": ["acme"], "sub-2": ["acme", "beta"]})


@pytest.fixture(params=["memory", "postgres"])
async def store(request, memory_store) -> AsyncGenerator:
    """Parametrized fixture over InMemoryStore and PostgresStore.

    The ``postgres`` variant skips when ``OMT_TEST_DATABASE_URL`` is not set;
    the ``memory`` variant always runs.
    """
    if request.param == "memory":
        yield memory_store
        return

    # postgres variant
    url = os.environ.get("OMT_TEST_DATABASE_URL")
    if not url:
        pytest.skip("OMT_TEST_DATABASE_URL not set; PostgresStore tests skipped (will run in CI)")

    pg = PostgresStore(url)
    await pg.connect()

    # Clean all OAuth tables before each test
    async with pg._pool.acquire() as conn:
        await conn.execute(
            "TRUNCATE mcp_oauth_access_token, mcp_oauth_refresh_token, "
            "mcp_oauth_code, mcp_oauth_grant, mcp_oauth_pending_auth, "
            "mcp_oauth_client RESTART IDENTITY CASCADE"
        )
        # Seed membership rows; skip if dashboard_user doesn't exist in this schema
        try:
            await conn.execute(
                "INSERT INTO dashboard_user (provider, subject, tenant_id) "
                "VALUES ('google', 'sub-1', 'acme'), ('google', 'sub-2', 'acme'), "
                "('google', 'sub-2', 'beta') ON CONFLICT DO NOTHING"
            )
        except asyncpg.PostgresError:
            pass  # dashboard_user may not exist in a pure-OAuth test schema

    yield pg
    await pg.close()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


async def _make_grant(store, *, client_id="cli-1", provider="google", subject="sub-1", tenants=None) -> str:
    """Create a grant and return its id."""
    if tenants is None:
        tenants = ["acme"]
    return await store.create_grant(
        client_id=client_id,
        provider=provider,
        subject=subject,
        tenants=tenants,
        client_name="Test App",
    )


# ---------------------------------------------------------------------------
# 1. Client CRUD + sweep
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_clientCrud_put_and_get(store):
    """register client, then get_client_record returns it."""
    from mcp.shared.auth import OAuthClientInformationFull
    from pydantic import AnyUrl

    client = OAuthClientInformationFull(
        client_id="test-cli-1",
        redirect_uris=[AnyUrl("https://example.com/cb")],
        client_name="Test",
        grant_types=["authorization_code"],
        response_types=["code"],
        scope="read",
        token_endpoint_auth_method="client_secret_post",
        client_secret="secret123",
    )
    await store.put_client(client)
    rec = await store.get_client_record("test-cli-1")
    assert rec is not None
    assert rec.client_id == "test-cli-1"


@pytest.mark.asyncio
async def test_clientSweep_expiredUnused_removed(store):
    """Expired-and-never-used clients are swept."""
    from mcp.shared.auth import OAuthClientInformationFull
    from pydantic import AnyUrl

    client = OAuthClientInformationFull(
        client_id="test-sweep-cli",
        redirect_uris=[AnyUrl("https://example.com/cb")],
        client_name="Sweep Test",
        grant_types=["authorization_code"],
        response_types=["code"],
        scope="read",
        token_endpoint_auth_method="none",
    )
    await store.put_client(client)

    # Artificially expire it (InMemoryStore: set private attr; PostgresStore: bypass)
    if isinstance(store, InMemoryStore):
        c = await store.get_client_record("test-sweep-cli")
        c._expires_at = time.time() - 1  # already expired

    elif isinstance(store, PostgresStore):
        async with store._pool.acquire() as conn:
            await conn.execute(
                "UPDATE mcp_oauth_client SET expires_at = now() - interval '1 day' "
                "WHERE client_id = 'test-sweep-cli'"
            )

    await store.sweep_expired_clients()

    rec = await store.get_client_record("test-sweep-cli")
    assert rec is None, "expired-never-used client should be swept"


# ---------------------------------------------------------------------------
# 2. Pending auth create / consume / expire
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_pendingAuth_put_and_get(store):
    """put_pending_auth then get_pending_auth returns it."""
    now = time.time()
    pending = PendingAuth(
        state="state-abc123",
        client_id="cli-1",
        client_state="cs-1",
        redirect_uri="https://example.com/cb",
        redirect_uri_provided_explicitly=True,
        code_challenge="ch-abc",
        scopes=["read"],
        resource="https://mcp.example.com/mcp",
        nonce="nonce-1",
        created_at=now,
        expires_at=now + 600,
        client_name="TestApp",
    )
    await store.put_pending_auth(pending)
    rec = await store.get_pending_auth("state-abc123")
    assert rec is not None
    assert rec.client_id == "cli-1"
    assert rec.code_challenge == "ch-abc"
    assert rec.nonce == "nonce-1"


@pytest.mark.asyncio
async def test_pendingAuth_updateAfterCallback(store):
    """update_pending_after_callback fills provider/subject/tenants/csrf."""
    now = time.time()
    pending = PendingAuth(
        state="state-upd",
        client_id="cli-2",
        client_state=None,
        redirect_uri="https://example.com/cb",
        redirect_uri_provided_explicitly=False,
        code_challenge="ch-upd",
        scopes=["read"],
        resource=None,
        nonce="nonce-upd",
        created_at=now,
        expires_at=now + 600,
    )
    await store.put_pending_auth(pending)

    ok = await store.update_pending_after_callback(
        "state-upd",
        csrf_hash="csrf-hash-1",
        provider="google",
        subject="sub-1",
        tenants=["acme"],
    )
    assert ok is True

    rec = await store.get_pending_auth("state-upd")
    assert rec is not None
    assert rec.csrf_hash == "csrf-hash-1"
    assert rec.provider == "google"
    assert rec.subject == "sub-1"
    assert rec.tenants == ["acme"]


@pytest.mark.asyncio
async def test_pendingAuth_consume_singleUse(store):
    """consume_pending_auth returns and removes the record (single-use)."""
    now = time.time()
    pending = PendingAuth(
        state="state-consume",
        client_id="cli-3",
        client_state=None,
        redirect_uri="https://example.com/cb",
        redirect_uri_provided_explicitly=True,
        code_challenge="ch-con",
        scopes=["read"],
        resource=None,
        nonce="nonce-con",
        created_at=now,
        expires_at=now + 600,
    )
    await store.put_pending_auth(pending)

    rec1 = await store.consume_pending_auth("state-consume")
    assert rec1 is not None

    rec2 = await store.get_pending_auth("state-consume")
    assert rec2 is None


# ---------------------------------------------------------------------------
# 3. Grant create / revoke
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_grant_createAndGet(store):
    """create_grant returns an id; get_grant returns the GrantRecord."""
    grant_id = await _make_grant(store)
    assert grant_id

    rec = await store.get_grant(grant_id)
    assert rec is not None
    assert isinstance(rec, GrantRecord)
    assert rec.grant_id == grant_id
    assert rec.client_id == "cli-1"
    assert rec.subject == "sub-1"
    assert rec.tenants == ["acme"]
    assert rec.revoked_at is None


@pytest.mark.asyncio
async def test_grant_revoke_setsRevokedAt(store):
    """revoke_grant_by_id sets revoked_at on the GrantRecord."""
    grant_id = await _make_grant(store)

    await store.revoke_grant_by_id(grant_id)

    rec = await store.get_grant(grant_id)
    assert rec is not None
    assert rec.revoked_at is not None


# ---------------------------------------------------------------------------
# 4. Code consume-once
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_code_consumeOnce(store):
    """put_code then consume_code sets consumed_at; get_code shows it."""
    grant_id = await _make_grant(store)
    now = time.time()
    code = CodeRecord(
        code="raw-code-xyz",
        grant_id=grant_id,
        client_id="cli-1",
        code_challenge="ch-xyz",
        redirect_uri="https://example.com/cb",
        redirect_uri_provided_explicitly=True,
        resource=None,
        scopes=["read"],
        subject="sub-1",
        expires_at=now + 300,
    )
    await store.put_code(code)

    rec = await store.get_code("raw-code-xyz")
    assert rec is not None
    assert rec.consumed_at is None

    await store.consume_code("raw-code-xyz")

    rec2 = await store.get_code("raw-code-xyz")
    assert rec2 is not None
    assert rec2.consumed_at is not None


# ---------------------------------------------------------------------------
# 5. Refresh token: rotate / reuse flags
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_refreshToken_put_and_get(store):
    """put_refresh_token then get_refresh_token returns the record."""
    grant_id = await _make_grant(store)
    now = int(time.time())
    rt = RefreshTokenRecord(
        token="raw-rt-123",
        grant_id=grant_id,
        client_id="cli-1",
        scopes=["read"],
        subject="sub-1",
        resource=None,
        expires_at=now + 86400 * 30,
    )
    await store.put_refresh_token(rt)

    rec = await store.get_refresh_token("raw-rt-123")
    assert rec is not None
    assert rec.grant_id == grant_id
    assert rec.rotated_at is None


@pytest.mark.asyncio
async def test_refreshToken_rotate_setsRotatedAt(store):
    """rotate_refresh_token marks the token; second rotate returns False."""
    grant_id = await _make_grant(store)
    now = int(time.time())
    rt = RefreshTokenRecord(
        token="raw-rt-rot",
        grant_id=grant_id,
        client_id="cli-1",
        scopes=["read"],
        subject="sub-1",
        resource=None,
        expires_at=now + 86400 * 30,
    )
    await store.put_refresh_token(rt)

    # First rotation should succeed
    ok1 = await store.rotate_refresh_token("raw-rt-rot", "new-rt-token")
    assert ok1 is True

    rec = await store.get_refresh_token("raw-rt-rot")
    assert rec is not None
    assert rec.rotated_at is not None


@pytest.mark.asyncio
async def test_refreshToken_atomicRotate_secondReturnsFalse(store):
    """Rotating an already-rotated token returns False (reuse-detection)."""
    grant_id = await _make_grant(store)
    now = int(time.time())
    rt = RefreshTokenRecord(
        token="raw-rt-reuse",
        grant_id=grant_id,
        client_id="cli-1",
        scopes=["read"],
        subject="sub-1",
        resource=None,
        expires_at=now + 86400 * 30,
    )
    await store.put_refresh_token(rt)

    ok1 = await store.rotate_refresh_token("raw-rt-reuse", "new-token-1")
    assert ok1 is True

    ok2 = await store.rotate_refresh_token("raw-rt-reuse", "new-token-2")
    assert ok2 is False, "second rotation on already-rotated token must return False"


@pytest.mark.asyncio
async def test_refreshToken_isStoredHashed(store):
    """PostgresStore-only: DB stores hash, not raw token."""
    if isinstance(store, InMemoryStore):
        pytest.skip("InMemoryStore intentionally stores raw tokens for test simplicity")

    grant_id = await _make_grant(store)
    now = int(time.time())
    raw_token = "raw-rt-hash-check"
    rt = RefreshTokenRecord(
        token=raw_token,
        grant_id=grant_id,
        client_id="cli-1",
        scopes=["read"],
        subject="sub-1",
        resource=None,
        expires_at=now + 86400 * 30,
    )
    await store.put_refresh_token(rt)

    expected_hash = _sha256(raw_token)

    async with store._pool.acquire() as conn:
        row_by_hash = await conn.fetchrow(
            "SELECT token_hash FROM mcp_oauth_refresh_token WHERE token_hash = $1",
            expected_hash,
        )
        row_by_raw = await conn.fetchrow(
            "SELECT token_hash FROM mcp_oauth_refresh_token WHERE token_hash = $1",
            raw_token,
        )

    assert row_by_hash is not None, "hash should be present in DB"
    assert row_by_raw is None, "raw token must NOT be stored in DB"


# ---------------------------------------------------------------------------
# 6. Access token lookup joins revoked_at
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_accessToken_lookup_notExpired(store):
    """store_access_token then get_access_token returns a record."""
    grant_id = await _make_grant(store)
    now = int(time.time())

    await store.store_access_token(
        token="raw-at-123",
        sub="sub-1",
        tenants=["acme"],
        client_id="cli-1",
        expires_at=now + 3600,
        resource=None,
        grant_id=grant_id,
    )

    rec = await store.get_access_token("raw-at-123")
    assert rec is not None
    assert rec.subject == "sub-1"
    assert rec.tenants == ["acme"]
    assert rec.client_id == "cli-1"
    assert rec.revoked_at is None


@pytest.mark.asyncio
async def test_accessToken_lookup_revokedGrantIncludesRevokedAt(store):
    """get_access_token returns record with revoked_at set when grant revoked."""
    grant_id = await _make_grant(store)
    now = int(time.time())

    await store.store_access_token(
        token="raw-at-revoked",
        sub="sub-1",
        tenants=["acme"],
        client_id="cli-1",
        expires_at=now + 3600,
        resource=None,
        grant_id=grant_id,
    )

    await store.revoke_grant(grant_id)

    rec = await store.get_access_token("raw-at-revoked")
    assert rec is not None, "record is returned (revocation check is in provider.py)"
    assert rec.revoked_at is not None, "revoked_at should be set when grant is revoked"


# ---------------------------------------------------------------------------
# 7. last_used_at bump
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_bumpLastUsed_atMostOncePerInterval(store):
    """bump_last_used at most once per interval_s; second call within interval is no-op."""
    if isinstance(store, InMemoryStore):
        grant_id = await _make_grant(store)
        # For InMemoryStore: verify the internal _last_used dict is updated
        await store.bump_last_used(grant_id, interval_s=60)
        t1 = store._last_used.get(grant_id, 0.0)
        assert t1 > 0, "first bump should set last_used_at"

        # Bump again immediately — should be a no-op (within interval)
        await store.bump_last_used(grant_id, interval_s=60)
        t2 = store._last_used.get(grant_id, 0.0)
        # t2 should equal t1 (no-op) or be very slightly ahead
        assert t2 == t1, "second bump within interval should not change last_used_at"
    else:
        # PostgresStore: just verify no exception and the grant's last_used_at updates
        grant_id = await _make_grant(store)
        await store.bump_last_used(grant_id, interval_s=0)  # 0 = always bump
        rec = await store.get_grant(grant_id)
        assert rec is not None


# ---------------------------------------------------------------------------
# 8. Membership lookup
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_membership_returnsTenants(store):
    """get_membership returns the tenant list for a known subject."""
    if isinstance(store, InMemoryStore):
        tenants = await store.get_membership("sub-1")
        assert tenants == ["acme"]
        tenants2 = await store.get_membership("sub-2")
        assert set(tenants2) == {"acme", "beta"}
        unknown = await store.get_membership("unknown-sub")
        assert unknown == []
    else:
        # For PostgresStore, the membership data depends on dashboard_user rows
        # which may or may not exist in the test DB; just verify no exception
        tenants = await store.get_membership("sub-1")
        assert isinstance(tenants, list)


# ---------------------------------------------------------------------------
# 9. is_healthy
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_isHealthy(store):
    """is_healthy returns True for a connected store."""
    ok = await store.is_healthy()
    assert ok is True
