#!/usr/bin/env bash
# Postgres init script (runs on first cluster boot, before Temporal auto-setup).
#
# Creates the dedicated `orchestrator` (audit_log), `dashboard` (dashboard_user),
# and per-broker-env (order_intent_journal) databases. Each exec-svc-* service
# points its DataSource at its own database. Keep this in lockstep with the
# inlined copy in infra/k8s/10-postgres.yaml (the k8s ConfigMap) — both must
# create the same set of databases.
#
# Idempotent: skipped automatically by Postgres-image init mechanics on
# subsequent boots because /docker-entrypoint-initdb.d only fires for fresh
# data volumes.
set -euo pipefail

create_db_if_missing() {
  local db="$1"
  # Postgres-image entrypoint runs as the POSTGRES_USER (temporal here), which
  # has CREATEDB privilege.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -d postgres <<-EOSQL
    SELECT 'CREATE DATABASE $db OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\\gexec
EOSQL
  echo "  ensured database: $db"
}

# Issue #56: dedicated `orchestrator` DB for audit_log + option_symbol_cache
# (separate from Temporal's `temporal` DB, which Temporal auto-setup creates).
create_db_if_missing orchestrator

# Tenant dashboard identity binding (dashboard_user). Owned by tenant-dashboard-bff-svc's
# Flyway. Separate DB so the read-only BFF never holds DDL rights on the trading-state DBs.
create_db_if_missing dashboard

# Phase 2c.2 onward: one database per <provider>-<env> whitelisted in the
# strategy-config contract schema (contract/schemas/strategy-config.json
# broker_target enum), excluding the two legacy bare paper/live values (no
# worker polls those queues, so no DB is needed). Idempotent (NOT EXISTS
# guard) so re-running this script on an upgraded cluster is safe — though
# /docker-entrypoint-initdb.d only fires on a fresh data volume.
create_db_if_missing exec_alpaca_paper
create_db_if_missing exec_alpaca_live
create_db_if_missing exec_tradier_paper
create_db_if_missing exec_tradier_live
create_db_if_missing exec_ibkr_paper
create_db_if_missing exec_ibkr_live
create_db_if_missing exec_schwab_paper
create_db_if_missing exec_schwab_live
