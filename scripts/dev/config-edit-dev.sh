#!/usr/bin/env bash
# One-command local "config editing" dev stack — the local equivalent of the homelab
# config-edit flow. Brings up the FULL chain needed to edit strategy config in the dashboard
# /config page and SAVE it locally with the passwordless "Dev login" (NO Google):
#
#   docker-compose infra (postgres + temporal + redis + temporal-bootstrap)
#     + orchestrator      (mvn spring-boot:run)  — headless Temporal worker (NO HTTP port):
#                                                  Flyway-creates + seeds `strategy_config`
#                                                  from the tenants/ tree AND hosts the
#                                                  StrategyConfigUpdateWorkflow worker.
#     + tenant-dashboard-bff :8083 (mvn)          — READS strategy_config (GET /api/strategy-config).
#     + api-gateway          :8082 (mvn)          — WRITE forward (POST /strategy-config →
#                                                  starts StrategyConfigUpdateWorkflow).
#     + exec                 (mvn spring-boot:run) — broker activity worker on the
#                                                  broker-alpaca-paper queue. Serves
#                                                  AccountSnapshotActivity so the Portfolio page's
#                                                  "Account equity" card resolves (without it the
#                                                  BFF's snapshot times out → equity shows "—").
#     + dashboard            :3000 (npm run dev)  — Next.js UI; /config page edits + saves.
#
# Open http://localhost:3000, click "Dev login (local only)", go to /config, edit + Save.
#
# Ctrl-C tears down the three JVMs (mvn spring-boot:run) + the Next.js dev server. The compose
# infra is LEFT UP — stop it with `docker compose -f infra/docker-compose.yml down`.
#
# This is a superset of scripts/dev/dashboard-dev.sh (the read-only dashboard). The extra moving
# parts vs that script: redis (the orchestrator requires it), the orchestrator worker, and the
# api-gateway write forward. The critical wiring is that the api-gateway and the orchestrator
# share TEMPORAL_NAMESPACE=default and the `orchestrator-core` task queue — else the
# POST /strategy-config workflow start has no live worker and times out → 503.
#
# Best-effort DX wrapper, not a tested product.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

# ---- shared dev credentials (must agree across the BFF, api-gateway, and the web app) --------
export DASHBOARD_READONLY_PASSWORD="${DASHBOARD_READONLY_PASSWORD:-dashboard_readonly_dev}"
export BFF_SHARED_TOKEN="${BFF_SHARED_TOKEN:-dev-shared-token}"
export API_GATEWAY_SHARED_TOKEN="${API_GATEWAY_SHARED_TOKEN:-dev-shared-token}"
PG_USER="${POSTGRES_USER:-temporal}"

COMPOSE="docker compose -f infra/docker-compose.yml"

echo "==> infra: postgres + temporal + redis (docker compose)"
$COMPOSE up -d postgres temporal redis

echo "==> waiting for postgres"
for _ in $(seq 1 60); do
  $COMPOSE exec -T postgres pg_isready -U "$PG_USER" >/dev/null 2>&1 && break
  sleep 1
done

echo "==> registering Temporal search attributes (temporal-bootstrap)"
$COMPOSE up temporal-bootstrap >/dev/null 2>&1 \
  || echo "    (search-attribute bootstrap failed — Positions/Portfolio may 500; config-edit is unaffected)"

orch_pid="" ; bff_pid="" ; gw_pid="" ; exec_pid="" ; web_pid=""
cleanup() {
  echo; echo "==> stopping dashboard + exec + api-gateway + BFF + orchestrator (infra left running)"
  [ -n "$web_pid" ]  && kill "$web_pid"  2>/dev/null || true
  [ -n "$exec_pid" ] && kill "$exec_pid" 2>/dev/null || true
  [ -n "$gw_pid" ]   && kill "$gw_pid"   2>/dev/null || true
  [ -n "$bff_pid" ]  && kill "$bff_pid"  2>/dev/null || true
  [ -n "$orch_pid" ] && kill "$orch_pid" 2>/dev/null || true
  # Fallbacks for the forked children the captured PIDs don't track: mvn spring-boot:run forks a
  # child JVM, and `npm run dev` forks `next-server` — killing the wrappers ($*_pid) above does not
  # reliably reap either. (dashboard-dev.sh has the spring-boot:run fallback for the same reason.)
  pkill -f 'spring-boot:run' 2>/dev/null || true
  pkill -f 'next-server' 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ---- orchestrator — Flyway-creates + seeds strategy_config, hosts the update worker ----------
# ORCHESTRATOR_TENANTS_DIR is ABSOLUTE: the orchestrator runs with cwd=its module dir, so the
# relative default "tenants" would resolve to services/orchestrator/tenants (which doesn't exist).
# DB user/Flyway user are both `temporal` locally (superuser) — the constrained-role split
# (orchestrator_runtime) is a prod concern; locally `temporal` keeps boot simple.
#
# NOTE: orchestrator-svc has NO web stack (it's a headless Temporal worker — see its pom.xml), so
# there is no /actuator/health HTTP endpoint to poll. Readiness == the Spring context started AND
# the Temporal pollers are up on the orchestrator-core queue. We tee its output to a log and wait
# on those markers.
ORCH_LOG="$(mktemp -t config-edit-orch.XXXXXX.log)"
echo "==> orchestrator (Flyway creates + seeds strategy_config; hosts StrategyConfigUpdateWorkflow worker; log: $ORCH_LOG)"
( cd services/orchestrator && \
  TEMPORAL_TARGET=localhost:7233 \
  TEMPORAL_NAMESPACE=default \
  ORCHESTRATOR_DB_URL=jdbc:postgresql://localhost:5432/orchestrator \
  ORCHESTRATOR_DB_USER=temporal \
  ORCHESTRATOR_DB_PASS=temporal \
  ORCHESTRATOR_FLYWAY_USER=temporal \
  ORCHESTRATOR_FLYWAY_PASS=temporal \
  REDIS_HOST=localhost \
  REDIS_PORT=6379 \
  ORCHESTRATOR_TENANTS_DIR="$ROOT/tenants" \
  mvn -q spring-boot:run ) > "$ORCH_LOG" 2>&1 &
orch_pid=$!

echo "==> waiting for orchestrator worker (boot is heavy — runs strict validators; no HTTP health)"
for _ in $(seq 1 180); do
  # Both the context-start AND the orchestrator-core Workflow poller must be up before the
  # api-gateway's POST /strategy-config can be served by a live worker.
  if grep -q "Started OrchestratorApplication" "$ORCH_LOG" 2>/dev/null \
     && grep -q 'Workflow Poller taskQueue="orchestrator-core"' "$ORCH_LOG" 2>/dev/null; then
    echo "    orchestrator worker up (orchestrator-core poller live on namespace=default)"; break
  fi
  if grep -qE "BUILD FAILURE|APPLICATION FAILED TO START" "$ORCH_LOG" 2>/dev/null; then
    echo "    orchestrator FAILED TO START — see $ORCH_LOG"; tail -30 "$ORCH_LOG"; exit 1
  fi
  sleep 2
done

# ---- BFF :8083 — reads strategy_config (GET /api/strategy-config) — same as dashboard-dev -----
echo "==> BFF tenant-dashboard-bff :8083 (reads strategy_config; orchestrator datasource → localhost:5432/orchestrator)"
( cd services/tenant-dashboard-bff && BFF_TENANTS_DIR="$ROOT/tenants" mvn -q spring-boot:run ) &
bff_pid=$!

echo "==> waiting for BFF health"
for _ in $(seq 1 90); do
  curl -sf http://localhost:8083/actuator/health >/dev/null 2>&1 && { echo "    BFF up"; break; }
  sleep 2
done

# ---- api-gateway :8082 — write forward: POST /strategy-config → StrategyConfigUpdateWorkflow ---
# CRITICAL: TEMPORAL_NAMESPACE=default and the orchestrator-core task queue MUST match the
# orchestrator above, else the workflow start has no live worker → 30s timeout → 503.
# STRATEGY_CONFIG_WRITE_ENABLED=true un-darks the StrategyConfigController (else the route 404s).
echo "==> api-gateway :8082 (write forward; STRATEGY_CONFIG_WRITE_ENABLED=true; shares ns=default + orchestrator-core)"
( cd services/api-gateway && \
  TEMPORAL_TARGET=localhost:7233 \
  TEMPORAL_NAMESPACE=default \
  API_GATEWAY_DB_URL=jdbc:postgresql://localhost:5432/orchestrator \
  API_GATEWAY_DB_USER=temporal \
  API_GATEWAY_DB_PASS=temporal \
  API_GATEWAY_SHARED_TOKEN="$API_GATEWAY_SHARED_TOKEN" \
  STRATEGY_CONFIG_WRITE_ENABLED=true \
  mvn -q spring-boot:run ) &
gw_pid=$!

echo "==> waiting for api-gateway health"
for _ in $(seq 1 120); do
  curl -sf http://localhost:8082/actuator/health >/dev/null 2>&1 && { echo "    api-gateway up"; break; }
  sleep 2
done

# ---- exec — broker activity worker on broker-alpaca-paper (serves AccountSnapshotActivity) -----
# The Portfolio page's "Account equity" card needs a worker on the broker-<target> queue: the BFF
# starts AccountSnapshotWorkflow (orchestrator-core) which dispatches AccountSnapshotActivity to
# broker-alpaca-paper. With no worker there the activity never starts → BFF times out at 8s →
# equity degrades to "—". exec also hosts the order-exec + reconciliation activities.
#
# Broker impl: alpaca-paper (REAL paper-account net-liq equity) when Alpaca paper creds are present
# in the environment, else stub (boots clean — broker.impl=alpaca-* fail-fasts on blank creds — and
# equity shows the sentinel $0.00 rather than a live figure). The exec env names are APCA_API_KEY_ID
# / APCA_API_SECRET_KEY; fall back to the ALPACA_API_KEY / ALPACA_SECRET_KEY names the orchestrator
# already uses locally so a single ~/.bashrc export drives both. EXEC_DB_URL points at the same
# exec_alpaca_paper journal the BFF reads positions from. No HTTP health endpoint is polled — like
# the orchestrator we tee to a log and wait on the broker-alpaca-paper poller marker.
EXEC_APCA_KEY="${APCA_API_KEY_ID:-${ALPACA_API_KEY:-}}"
EXEC_APCA_SECRET="${APCA_API_SECRET_KEY:-${ALPACA_SECRET_KEY:-}}"
if [ -n "$EXEC_APCA_KEY" ] && [ -n "$EXEC_APCA_SECRET" ]; then
  EXEC_BROKER_IMPL="alpaca-paper"
  echo "==> exec worker (broker-alpaca-paper; BROKER_IMPL=alpaca-paper — REAL paper-account equity)"
else
  EXEC_BROKER_IMPL="stub"
  echo "==> exec worker (broker-alpaca-paper; BROKER_IMPL=stub — no Alpaca creds in env, equity card shows \$0.00 sentinel)"
fi
EXEC_LOG="$(mktemp -t config-edit-exec.XXXXXX.log)"
echo "    (exec log: $EXEC_LOG)"
( cd services/exec && \
  TEMPORAL_TARGET=localhost:7233 \
  TEMPORAL_NAMESPACE=default \
  TEMPORAL_TASK_QUEUE=broker-alpaca-paper \
  EXEC_DB_URL=jdbc:postgresql://localhost:5432/exec_alpaca_paper \
  EXEC_DB_USER=temporal \
  EXEC_DB_PASS=temporal \
  BROKER_IMPL="$EXEC_BROKER_IMPL" \
  APCA_API_KEY_ID="$EXEC_APCA_KEY" \
  APCA_API_SECRET_KEY="$EXEC_APCA_SECRET" \
  APCA_API_BASE_URL="${APCA_API_BASE_URL:-https://paper-api.alpaca.markets}" \
  mvn -q spring-boot:run ) > "$EXEC_LOG" 2>&1 &
exec_pid=$!

echo "==> waiting for exec worker (broker-alpaca-paper poller)"
for _ in $(seq 1 180); do
  if grep -q 'Started ExecApplication' "$EXEC_LOG" 2>/dev/null \
     && grep -q 'Poller taskQueue="broker-alpaca-paper"' "$EXEC_LOG" 2>/dev/null; then
    echo "    exec worker up (broker-alpaca-paper poller live)"; break
  fi
  if grep -qE "BUILD FAILURE|APPLICATION FAILED TO START" "$EXEC_LOG" 2>/dev/null; then
    echo "    exec FAILED TO START — see $EXEC_LOG"; tail -30 "$EXEC_LOG"; exit 1
  fi
  sleep 2
done

# ---- dashboard :3000 — Next.js UI (dashboard-dev env + the write-path wiring) -----------------
cd dashboard
# Check the `next` binary, not just the dir: an empty/partial node_modules (e.g. a leftover dir
# from an interrupted install) passes `[ -d node_modules ]` but has no `next` → `next: not found`.
[ -x node_modules/.bin/next ] || { echo "==> npm ci (dashboard deps)"; npm ci; }

echo "==> dashboard (Next.js) :3000 — open http://localhost:3000, 'Dev login (local only)', then /config"
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
API_GATEWAY_BASE_URL="${API_GATEWAY_BASE_URL:-http://localhost:8082}" \
API_GATEWAY_SHARED_TOKEN="$API_GATEWAY_SHARED_TOKEN" \
STRATEGY_CONFIG_WRITE_ENABLED=true \
  npm run dev &
web_pid=$!

wait
