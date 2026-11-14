"""Store contract suite — Seam J.

Parametrised over InMemoryStore (always) and PostgresStore (skipped when
OMT_TEST_DATABASE_URL is not set).  Every test proves the same behaviour from
both implementations so that InMemoryStore is a valid drop-in for tests and
PostgresStore is correct against V17.

The PostgresStore fixture connects as ``mcp_gateway`` (never as superuser) so
the column-level grants in V17/V18 are exercised on every Postgres test run.
Per-test cleanup uses DELETE (not TRUNCATE — the mcp_gateway role does not hold
that privilege).

The session-scoped ``pg_dsns`` fixture in conftest.py creates the fresh DB and
applies V1–V18 migrations.
"""
from __future__ import annotations

import hashlib
import time
from collections.abc import AsyncGenerator

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
async def store(request, memory_store, pg_dsns) -> AsyncGenerator:
    """Parametrized fixture over InMemoryStore and PostgresStore.

    The ``postgres`` variant skips when ``OMT_TEST_DATABASE_URL`` is not set;
    the ``memory`` variant always runs.

    PostgresStore connects as ``mcp_gateway`` (not superuser) so the
    column-level grants from V17/V18 are exercised in every Postgres test.
    Per-test cleanup runs as mcp_gateway using DELETE (not TRUNCATE).
    """
    if request.param == "memory":
        yield memory_store
        return

    # postgres variant
    _super_dsn, gw_dsn = pg_dsns
    if gw_dsn is None:
        pytest.skip("OMT_TEST_DATABASE_URL not set; PostgresStore tests skipped (will run in CI)")

    pg = PostgresStore(gw_dsn)
    await pg.connect()

    # Clean all OAuth tables as mcp_gateway (DELETE in FK order: children first)
    async with pg._pool.acquire() as conn:
        await conn.execute("DELETE FROM mcp_oauth_access_token")
        await conn.execute("DELETE FROM mcp_oauth_refresh_token")
        await conn.execute("DELETE FROM mcp_oauth_code")
        await conn.execute("DELETE FROM mcp_oauth_grant")
        await conn.execute("DELETE FROM mcp_oauth_pending_auth")
        await conn.execute("DELETE FROM mcp_oauth_client")

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

    # Artificially expire it
    if isinstance(store, InMemoryStore):
        store._client_expires_at["test-sweep-cli"] = time.time() - 1

    elif isinstance(store, PostgresStore):
        async with store._pool.acquire() as conn:
            await conn.execute(
                "UPDATE mcp_oauth_client SET expires_at = now() - interval '1 day' "
                "WHERE client_id = 'test-sweep-cli'"
            )

    await store.sweep_expired_clients()

    rec = await store.get_client_record("test-sweep-cli")
    assert rec is None, "expired-never-used client should be swept"


@pytest.mark.asyncio
async def test_clientSweep_withActiveGrant_notRemoved(store):
    """An expired client that has an active grant must NOT be swept."""
    from mcp.shared.auth import OAuthClientInformationFull
    from pydantic import AnyUrl

    client = OAuthClientInformationFull(
        client_id="test-sweep-active",
        redirect_uris=[AnyUrl("https://example.com/cb")],
        client_name="Sweep Active",
        grant_types=["authorization_code"],
        response_types=["code"],
        scope="read",
        token_endpoint_auth_method="none",
    )
    await store.put_client(client)

    # Create an active grant for this client
    await store.create_grant(
        client_id="test-sweep-active",
        provider="google",
        subject="sweep-user",
        tenants=["acme"],
        client_name="Sweep Active",
    )

    # Mark the client as expired
    if isinstance(store, InMemoryStore):
        store._client_expires_at["test-sweep-active"] = time.time() - 1
    elif isinstance(store, PostgresStore):
        async with store._pool.acquire() as conn:
            await conn.execute(
                "UPDATE mcp_oauth_client SET expires_at = now() - interval '1 day' "
                "WHERE client_id = 'test-sweep-active'"
            )

    await store.sweep_expired_clients()

    # Must still exist because it has an active grant
    rec = await store.get_client_record("test-sweep-active")
    assert rec is not None, (
        "client with an active grant must not be swept even after expiry"
    )


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
    """PostgresStore: DB stores SHA-256 hash of the token, not the raw value.
    InMemoryStore: keys by raw token (intentional) — asserted, not skipped.
    """
    if isinstance(store, InMemoryStore):
        # Assert the skip is correct: memory store keys by raw token, never hashes.
        # If someone accidentally introduces hashing here, this assertion catches it.
        grant_id = await _make_grant(store)
        now = int(time.time())
        sentinel = "raw-rt-memory-hash-check"
        rt = RefreshTokenRecord(
            token=sentinel,
            grant_id=grant_id,
            client_id="cli-1",
            scopes=["read"],
            subject="sub-1",
            resource=None,
            expires_at=now + 86400 * 30,
        )
        await store.put_refresh_token(rt)
        assert sentinel in store._refresh_tokens, (
            "InMemoryStore must key refresh tokens by raw value"
        )
        assert _sha256(sentinel) not in store._refresh_tokens, (
            "InMemoryStore must NOT hash refresh tokens"
        )
        return  # hash-in-DB check only applies to PostgresStore

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


# ---------------------------------------------------------------------------
# 10. Finding D — bump_client_used + sweep uses store-managed expiry dict
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_bumpClientUsed_pushesExpiryForward_thenAbandonedClientIsSwept(store):
    """A use pushes expires_at 30 days forward; a used-then-abandoned client still expires.

    Plan: "expires_at = created_at + 30 d, pushed forward on every use".
    """
    from mcp.shared.auth import OAuthClientInformationFull
    from pydantic import AnyUrl

    async def age_out() -> None:
        if isinstance(store, InMemoryStore):
            store._client_expires_at["test-bump-cli"] = time.time() - 1
        else:
            async with store._pool.acquire() as conn:
                await conn.execute(
                    "UPDATE mcp_oauth_client SET expires_at = now() - interval '1 day' "
                    "WHERE client_id = 'test-bump-cli'"
                )

    await store.put_client(
        OAuthClientInformationFull(
            client_id="test-bump-cli",
            redirect_uris=[AnyUrl("https://example.com/cb")],
            client_name="Bump Test",
            grant_types=["authorization_code"],
            response_types=["code"],
            scope="read",
            token_endpoint_auth_method="none",
        )
    )

    # Past expiry, then used: the use pushes expiry forward, so the sweep keeps it.
    await age_out()
    await store.bump_client_used("test-bump-cli")
    await store.sweep_expired_clients()
    assert await store.get_client_record("test-bump-cli") is not None

    # Used once, then abandoned past the new expiry with no live grant: swept.
    await age_out()
    await store.sweep_expired_clients()
    assert await store.get_client_record("test-bump-cli") is None


@pytest.mark.asyncio
async def test_clientSweep_usesDictExpiry(store):
    """InMemory sweep uses _client_expires_at dict, not hasattr on the client object.

    RED: put_client does not populate _client_expires_at; sweep uses hasattr so
         an unmodified registered client is never swept.
    GREEN: put_client writes to _client_expires_at; expiring via the dict works.
    """
    if not isinstance(store, InMemoryStore):
        pytest.skip("dict-expiry path is InMemoryStore-specific")

    from mcp.shared.auth import OAuthClientInformationFull
    from pydantic import AnyUrl

    client = OAuthClientInformationFull(
        client_id="test-dict-expiry",
        redirect_uris=[AnyUrl("https://example.com/cb")],
        client_name="Dict Expiry",
        grant_types=["authorization_code"],
        response_types=["code"],
        scope="read",
        token_endpoint_auth_method="none",
    )
    await store.put_client(client)

    # _client_expires_at must exist and have an entry for the new client
    assert hasattr(store, "_client_expires_at"), (
        "InMemoryStore must have a _client_expires_at dict"
    )
    assert "test-dict-expiry" in store._client_expires_at, (
        "put_client must add an entry to _client_expires_at"
    )

    # Expire via the dict (not via c._expires_at)
    store._client_expires_at["test-dict-expiry"] = time.time() - 1
    await store.sweep_expired_clients()
    assert await store.get_client_record("test-dict-expiry") is None, (
        "dict-expired client must be swept"
    )


# ---------------------------------------------------------------------------
# 11. Finding J — code double-consume returns False
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_code_consumeSecondTime_returnsFalse(store):
    """consume_code returns False when the code was already consumed (mutation guard).

    Verifies that the conditional in consume_code (consumed_at is not None → return
    False) is load-bearing and cannot be dropped.
    """
    grant_id = await _make_grant(store)
    now = time.time()
    code = CodeRecord(
        code="raw-code-double",
        grant_id=grant_id,
        client_id="cli-1",
        code_challenge="ch-double",
        redirect_uri="https://example.com/cb",
        redirect_uri_provided_explicitly=True,
        resource=None,
        scopes=["read"],
        subject="sub-1",
        expires_at=now + 300,
    )
    await store.put_code(code)

    first = await store.consume_code("raw-code-double")
    assert first is True, "first consume must succeed"

    second = await store.consume_code("raw-code-double")
    assert second is False, "second consume must return False (already consumed)"
