#!/usr/bin/env bash
# Seed sample tenant-dashboard data into the local Postgres (the `make dashboard-dev` infra):
# creates the audit_log + order_intent_journal read tables (absent in the lightweight local stack
# because orchestrator-svc / exec-svc aren't running) and inserts sample fills/orders for tenant
# `dev`. Idempotent — safe to re-run. See scripts/dev/seed.sql.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
PG_USER="${POSTGRES_USER:-temporal}"

echo "==> seeding dashboard sample data (audit_log + order_intent_journal)"
docker compose -f infra/docker-compose.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d postgres < scripts/dev/seed.sql
echo "==> seeded (idempotent) — Trades / Orders / Portfolio realized-PnL will now show data"
