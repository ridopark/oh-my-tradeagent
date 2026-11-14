"""Session-scoped Postgres setup for gateway contract tests.

Creates a fresh named database, applies V1–V18 Flyway migrations with
the same test placeholder values as McpOauthMigrationIT, seeds
dashboard_user rows as superuser, then enables mcp_gateway LOGIN so
PostgresStore tests run as the real gateway role (never as superuser).

The ``pg_dsns`` fixture yields ``(superuser_dsn_for_new_db, mcp_gw_dsn)``
or ``(None, None)`` when OMT_TEST_DATABASE_URL is not set.

Per-test cleanup is the caller's responsibility (DELETE, not TRUNCATE,
since TRUNCATE is not in the mcp_gateway grant).
"""
from __future__ import annotations

import asyncio
import os
import uuid
from pathlib import Path
from urllib.parse import urlparse

import pytest

# ------------------------------------------------------------------
# Paths and constants
# ------------------------------------------------------------------

_REPO_ROOT = Path(__file__).resolve().parents[4]  # …/oh-my-tradeagent
_MIGRATIONS_DIR = (
    _REPO_ROOT / "services/tenant-dashboard-bff/src/main/resources/db/dashboard"
)

# Same test passwords as McpOauthMigrationIT (Java IT reference)
_PLACEHOLDERS = {
    "dashboard_readonly_password": "readonly-test-pw",
    "dashboard_writer_password": "writer-test-pw",
    "trade_context_writer_password": "tc-writer-test-pw",
}

# Password granted to mcp_gateway for the test session
MCP_GW_TEST_PW = "mcp-gw-test-pw"


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------


def _migration_key(path: Path) -> int:
    """Sort key: 'V17__mcp_oauth.sql' → 17."""
    stem = path.stem  # e.g. "V17__mcp_oauth"
    return int(stem.split("__")[0][1:])


def _apply_placeholders(sql: str) -> str:
    for key, value in _PLACEHOLDERS.items():
        sql = sql.replace("${" + key + "}", value)
    return sql


def _make_dsn(base: str, *, user: str, password: str | None, dbname: str) -> str:
    """Rebuild a postgres DSN with a different user/password/dbname."""
    p = urlparse(base)
    host = p.hostname or "127.0.0.1"
    port = p.port or 5432
    netloc = f"{user}:{password}@{host}:{port}" if password else f"{user}@{host}:{port}"
    return f"{p.scheme}://{netloc}/{dbname}"


# ------------------------------------------------------------------
# Async helpers (run via asyncio.run from the synchronous fixture)
# ------------------------------------------------------------------


async def _create_db_and_migrate(superuser_dsn: str, db_name: str) -> None:
    """Create *db_name*, apply V1–V18 migrations, enable mcp_gateway."""
    import asyncpg

    # Create database
    conn = await asyncpg.connect(superuser_dsn)
    try:
        await conn.execute(f'CREATE DATABASE "{db_name}"')
    finally:
        await conn.close()

    # Connect to new DB as superuser and migrate
    new_super_dsn = _make_dsn(superuser_dsn, user="postgres", password=None, dbname=db_name)
    conn = await asyncpg.connect(new_super_dsn)
    try:
        for f in sorted(_MIGRATIONS_DIR.glob("V*.sql"), key=_migration_key):
            sql = _apply_placeholders(f.read_text())
            await conn.execute(sql)

        # Enable mcp_gateway with a known test password (mirrors operator enablement step)
        await conn.execute(f"ALTER ROLE mcp_gateway LOGIN PASSWORD '{MCP_GW_TEST_PW}'")

        # Seed membership rows that the contract tests depend on (as superuser: mcp_gateway
        # has only SELECT on dashboard_user, never INSERT)
        await conn.execute(
            "INSERT INTO dashboard_user (provider, subject, email, tenant_id) VALUES "
            "('google', 'sub-1', 'sub1@test.com', 'acme'), "
            "('google', 'sub-2', 'sub2a@test.com', 'acme'), "
            "('google', 'sub-2', 'sub2b@test.com', 'beta') "
            "ON CONFLICT DO NOTHING"
        )
    finally:
        await conn.close()


async def _drop_db(superuser_dsn: str, db_name: str) -> None:
    """Terminate all connections to *db_name* then drop it."""
    import asyncpg

    conn = await asyncpg.connect(superuser_dsn)
    try:
        await conn.execute(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
            " WHERE datname = $1 AND pid <> pg_backend_pid()",
            db_name,
        )
        await conn.execute(f'DROP DATABASE IF EXISTS "{db_name}"')
    finally:
        await conn.close()


# ------------------------------------------------------------------
# Session-scoped fixture
# ------------------------------------------------------------------


@pytest.fixture(scope="session")
def pg_dsns():
    """Session-scoped fixture: provision a fresh DB, yield DSN pair, teardown.

    Yields:
        ``(super_dsn, gw_dsn)`` where *super_dsn* connects as postgres to the
        test DB (for seeding as superuser) and *gw_dsn* connects as mcp_gateway
        (for PostgresStore, exercising the column-level grants).

        When ``OMT_TEST_DATABASE_URL`` is not set, yields ``(None, None)``
        (callers should skip).
    """
    base_dsn = os.environ.get("OMT_TEST_DATABASE_URL")
    if not base_dsn:
        yield None, None
        return

    db_name = f"mcp_test_{uuid.uuid4().hex[:12]}"
    asyncio.run(_create_db_and_migrate(base_dsn, db_name))

    super_dsn = _make_dsn(base_dsn, user="postgres", password=None, dbname=db_name)
    gw_dsn = _make_dsn(base_dsn, user="mcp_gateway", password=MCP_GW_TEST_PW, dbname=db_name)

    yield super_dsn, gw_dsn

    asyncio.run(_drop_db(base_dsn, db_name))
