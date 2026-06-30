#!/usr/bin/env bash
# One-command local dev stack for the OPERATOR ONBOARDING form (/admin/onboard). A thin wrapper over
# scripts/dev/config-edit-dev.sh: it brings up the same full chain (infra + orchestrator + BFF +
# api-gateway + exec + Next.js, passwordless Dev login) and additionally un-darks the operator
# onboarding routes so you can drive the form end-to-end.
#
#   Open http://localhost:3000 -> "Dev login (local only)" -> Admin -> "+ Onboard tenant".
#
# TIER 2 (default) — CREATE TENANT works fully end-to-end:
#   the form's Step 1 (create tenant) POSTs to the api-gateway -> StrategyConfigCreateWorkflow on
#   orchestrator-core -> the strategy_config row is inserted. Verify with:
#     docker compose -f infra/docker-compose.yml exec -T postgres \
#       psql -U temporal -d orchestrator -c \
#       "select tenant_id, strategy_id, version from strategy_config order by created_at desc limit 5;"
#   The dev-login identity (dev@localhost) is made an operator via OPERATOR_EMAILS below.
#   Step 2 (broker keys) is REACHABLE but will error at the exec hop unless you opt into Tier 3.
#
# TIER 3 (opt-in) — CREDENTIAL PASTE + ACCOUNT READ-BACK, run as:
#     ONBOARD_CREDS=db APCA_API_KEY_ID=<paper-key> APCA_API_SECRET_KEY=<paper-secret> \
#       ./scripts/dev/onboard-dev.sh        # (or: ONBOARD_CREDS=db make onboard-dev)
#   This points exec at DB-creds mode (broker.creds.source=db) so its credential-write endpoint
#   exists, matches the exec admin token across api-gateway<->exec, and uses your Alpaca PAPER keys
#   for the real /v2/account probe behind the read-back.
#   CAVEAT: DB-creds mode envelope-encrypts the pasted secret under a KEK. If your local exec has no
#   KEK configured it may refuse to boot or to write — check the exec log the parent script prints.
#   That KEK setup is environment-specific and intentionally NOT auto-provisioned here.
#
# Ctrl-C tears the stack down (infra is left up — see the parent script). Best-effort DX wrapper.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"

# Operator identity + the two onboarding route flags. OPERATOR_EMAILS must include the dev-login
# email (dev@localhost) so that identity is an operator and the /admin/* pages render. These drive
# BOTH the dashboard (Node reads these exact names) AND — via the parent script's run-args shim —
# the api-gateway dark flags.
export OPERATOR_EMAILS="${OPERATOR_EMAILS:-dev@localhost}"
export OPERATOR_TENANT_CREATE_ENABLED=true
export OPERATOR_CREDENTIAL_WRITE_ENABLED=true

if [ "${ONBOARD_CREDS:-}" = "db" ]; then
  # Tier 3: the credential-verify path against exec's DB-creds endpoint.
  export BROKER_CREDS_SOURCE=db
  export EXEC_ADMIN_SHARED_TOKEN="${EXEC_ADMIN_SHARED_TOKEN:-dev-admin-token}"
  export EXEC_BASE_URL="${EXEC_BASE_URL:-http://localhost:8080}"
  echo "==> onboard-dev: Tier 3 (ONBOARD_CREDS=db) — exec in DB-creds mode."
  if [ -z "${APCA_API_KEY_ID:-${ALPACA_API_KEY:-}}" ]; then
    echo "    WARNING: no Alpaca PAPER keys in env (APCA_API_KEY_ID/APCA_API_SECRET_KEY) — the"
    echo "             account read-back probe will fail. Export paper keys for a real read-back."
  fi
  echo "    NOTE: DB-creds mode needs a KEK; if exec won't boot/write, see the exec log it prints."
else
  echo "==> onboard-dev: Tier 2 — create-tenant works E2E; Step 2 (broker keys) needs ONBOARD_CREDS=db."
fi

exec "$ROOT/scripts/dev/config-edit-dev.sh"
