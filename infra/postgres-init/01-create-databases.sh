#!/usr/bin/env bash
# Postgres init script (runs on first cluster boot, before Temporal auto-setup).
#
# Creates the per-broker-env databases that the plan calls for (line 95:
# "Postgres OrderIntentJournal per broker env"). Each exec-svc-* service points
# its DataSource at its own database; orchestrator-svc continues to share the
# 'temporal' database with the Temporal cluster.
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

# Phase 2c.2: alpaca-paper is the v0 default. Legacy tradier-paper DB retained so existing
# volumes mounted from prior phases don't lose state on re-init (idempotent skip).
create_db_if_missing exec_alpaca_paper
create_db_if_missing exec_tradier_paper
