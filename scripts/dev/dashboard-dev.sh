#!/usr/bin/env bash
# One-command local tenant-dashboard stack, run from source for fast iteration:
#   docker-compose infra (postgres + temporal) + the BFF (mvn spring-boot:run) + the Next.js
#   dashboard (npm run dev), wired together with the passwordless "Dev login" — so NO Google/Facebook
#   setup is needed. Open http://localhost:3000 and click "Dev login (local only)".
#
# Ctrl-C tears everything down (the BFF + web; the compose infra is left up — stop it with
# `docker compose -f infra/docker-compose.yml down`). Best-effort DX wrapper, not a tested product.
#
# Data note: trades/orders/positions render EMPTY locally (no trading system populating audit_log /
# order_intent_journal, no PositionWorkflows). The portfolio page waits ~8s for the account-snapshot
# workflow to time out (no orchestrator worker) before rendering. The point is a working full stack.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

# Single source of the dev credentials (must agree between the BFF and the web app).
export DASHBOARD_READONLY_PASSWORD="${DASHBOARD_READONLY_PASSWORD:-dashboard_readonly_dev}"
export BFF_SHARED_TOKEN="${BFF_SHARED_TOKEN:-dev-shared-token}"
PG_USER="${POSTGRES_USER:-temporal}"

echo "==> infra: postgres + temporal (docker compose)"
docker compose -f infra/docker-compose.yml up -d postgres temporal

echo "==> waiting for postgres"
for _ in $(seq 1 60); do
  docker compose -f infra/docker-compose.yml exec -T postgres pg_isready -U "$PG_USER" >/dev/null 2>&1 && break
  sleep 1
done

echo "==> registering Temporal search attributes (TenantStrategy/ContractSymbol — Positions/Portfolio need them)"
docker compose -f infra/docker-compose.yml up temporal-bootstrap >/dev/null 2>&1 \
  || echo "    (search-attribute bootstrap failed — Positions/Portfolio may 500)"

echo "==> seeding sample data (audit_log + order_intent_journal)"
scripts/dev/dashboard-seed.sh || echo "    (seed failed — continuing; Trades/Orders may be empty)"

bff_pid="" ; web_pid=""
cleanup() {
  echo; echo "==> stopping dashboard + BFF (infra left running)"
  [ -n "$web_pid" ] && kill "$web_pid" 2>/dev/null || true
  [ -n "$bff_pid" ] && kill "$bff_pid" 2>/dev/null || true
  pkill -f 'spring-boot:run' 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> BFF tenant-dashboard-bff :8083 (Flyway creates dashboard_user + dashboard_readonly)"
# BFF_TENANTS_DIR is absolute: the BFF runs with cwd=the module dir, so the relative default
# "tenants" would resolve to services/tenant-dashboard-bff/tenants (which doesn't exist).
( cd services/tenant-dashboard-bff && BFF_TENANTS_DIR="$ROOT/tenants" mvn -q spring-boot:run ) &
bff_pid=$!

echo "==> waiting for BFF health"
for _ in $(seq 1 90); do
  curl -sf http://localhost:8083/actuator/health >/dev/null 2>&1 && { echo "    BFF up"; break; }
  sleep 2
done

cd dashboard
# Check the `next` binary, not just the dir: an empty/partial node_modules passes `[ -d ]` but has
# no `next` → `next: not found`.
[ -x node_modules/.bin/next ] || { echo "==> npm install (dashboard deps)"; npm install; }

echo "==> dashboard (Next.js) :3000 — open http://localhost:3000 and click 'Dev login (local only)'"
# Pin the dev-server port: `next dev` honors an inherited $PORT, so a stray `export PORT=...` in the
# operator's shell would otherwise bind a non-:3000 port (breaks the Google OAuth redirect URI, which
# is registered at :3000). Set it explicitly so the script's :3000 promise always holds.
PORT=3000 \
AUTH_DEV_LOGIN=true \
AUTH_DEV_TENANT="${AUTH_DEV_TENANT:-dev}" \
AUTH_SECRET="${AUTH_SECRET:-dev-secret-not-for-prod}" \
AUTH_URL="${AUTH_URL:-http://localhost:3000}" \
BFF_INTERNAL_URL="${BFF_INTERNAL_URL:-http://localhost:8083}" \
BFF_SHARED_TOKEN="$BFF_SHARED_TOKEN" \
DASHBOARD_DB_HOST="${DASHBOARD_DB_HOST:-localhost}" \
DASHBOARD_DB_USER="${DASHBOARD_DB_USER:-dashboard_readonly}" \
DASHBOARD_READONLY_PASSWORD="$DASHBOARD_READONLY_PASSWORD" \
  npm run dev &
web_pid=$!

wait
