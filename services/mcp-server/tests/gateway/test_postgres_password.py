"""PostgresStore — password special-character test (Item 3, fix round).

Verifies that PostgresStore passes the password to asyncpg as a keyword
argument rather than splicing it into the DSN string.  Special characters
(@, /, :, %) in the password would corrupt a DSN splice but are harmless
when passed as `password=` to asyncpg.create_pool.

Skipped when OMT_TEST_DATABASE_URL is not set.

RED: PostgresStore splices the password into the DSN → special chars break it.
GREEN: PostgresStore accepts a `password=` kwarg and passes it to asyncpg.
"""
from __future__ import annotations

import pytest

# Password containing every URL-special character the plan requires.
_SPECIAL_PW = "pa@ss/wo:rd%42"

# Original test password used by conftest.py's pg_dsns fixture
_ORIGINAL_PW = "mcp-gw-test-pw"


@pytest.fixture
async def special_pw_store(pg_dsns):
    """Set the mcp_gateway role password to _SPECIAL_PW, yield a connected
    PostgresStore, then restore the original password.

    Skips the test when OMT_TEST_DATABASE_URL is not set.
    """
    super_dsn, gw_dsn = pg_dsns
    if gw_dsn is None:
        pytest.skip("OMT_TEST_DATABASE_URL not set; PostgresStore password tests skipped")

    from urllib.parse import urlparse

    import asyncpg

    # Alter password to the special value (as superuser)
    conn = await asyncpg.connect(super_dsn)
    try:
        await conn.execute(f"ALTER ROLE mcp_gateway PASSWORD '{_SPECIAL_PW}'")
    finally:
        await conn.close()

    # Build a DSN WITHOUT the password embedded (bare form: user@host/db)
    parsed = urlparse(gw_dsn)
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or 5432
    dbname = parsed.path.lstrip("/")
    bare_dsn = f"postgresql://mcp_gateway@{host}:{port}/{dbname}"

    from ohmytradeagent_mcp.gateway.store_postgres import PostgresStore
    store = PostgresStore(bare_dsn, password=_SPECIAL_PW)
    await store.connect()

    try:
        yield store
    finally:
        await store.close()

        # Restore original password
        conn = await asyncpg.connect(super_dsn)
        try:
            await conn.execute(f"ALTER ROLE mcp_gateway PASSWORD '{_ORIGINAL_PW}'")
        finally:
            await conn.close()


@pytest.mark.asyncio
async def test_postgresStore_passwordWithSpecialChars_connects(special_pw_store):
    """PostgresStore connects successfully when password contains @, /, :, %.

    RED: password is spliced into the DSN; special chars corrupt the URL
         and asyncpg raises an authentication/parse error.
    GREEN: password= kwarg is used; asyncpg accepts the raw password string.
    """
    # is_healthy() runs SELECT 1 — if the password was wrong this would fail
    healthy = await special_pw_store.is_healthy()
    assert healthy, (
        "PostgresStore.is_healthy() returned False after connecting with "
        f"special-char password {_SPECIAL_PW!r}. "
        "Ensure PostgresStore passes `password=` to asyncpg.create_pool "
        "rather than splicing it into the DSN string."
    )


@pytest.mark.asyncio
async def test_postgresStore_passwordWithSpecialChars_canReadMembership(special_pw_store):
    """get_membership() works after connecting with special-char password."""
    # sub-1 and sub-2 are seeded in conftest.py's _create_db_and_migrate
    tenants = await special_pw_store.get_membership("sub-1")
    assert "acme" in tenants, (
        f"Expected 'acme' in membership for sub-1; got {tenants!r}. "
        "Connection with special-char password may not have succeeded."
    )
