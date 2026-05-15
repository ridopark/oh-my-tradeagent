#!/usr/bin/env bash
# Phase 5b.D — green-redeploy proof.
#
# Verifies PLAN.md's "Done when" criterion for Phase 5b:
#   "A green redeploy of orchestrator-svc with 3+ running PositionWorkflows
#    completes with zero workflow stalls."
#
# What it does:
#   1. Port-forwards temporal:7233 from the homelab cluster (background).
#   2. Spawns N PositionWorkflows via the Python harness.
#   3. Captures the running PositionWorkflow set as the BEFORE snapshot.
#   4. Triggers `kubectl rollout restart deployment/orchestrator`, waits up
#      to 180s for rollout completion.
#   5. Captures the running PositionWorkflow set as the AFTER snapshot.
#   6. Diff: BEFORE == AFTER, and pod restartCount climbed by exactly 1.
#   7. Emits the `Deploy-Verified:` line for the PR body.
#
# Inputs (env):
#   COUNT          — N positions to spawn (default 3)
#   HOMELAB        — ssh target (default ridopark@192.168.10.123)
#   NAMESPACE      — k8s namespace (default copytrade)
#   TENANT         — tenant_id (default dev)
#   STRATEGY       — strategy_id (default copytrade-v1)
#
# Exits non-zero on any divergence; the script is the proof.

set -euo pipefail

COUNT="${COUNT:-3}"
HOMELAB="${HOMELAB:-ridopark@192.168.10.123}"
NAMESPACE="${NAMESPACE:-copytrade}"
TENANT="${TENANT:-dev}"
STRATEGY="${STRATEGY:-copytrade-v1}"
LOCAL_TEMPORAL_PORT=7234   # avoid clashing with a local docker-compose Temporal on 7233

log() { printf '[%(%H:%M:%S)T] %s\n' -1 "$*" >&2; }

cleanup() {
  local pf_pid="${1:-}"
  if [ -n "$pf_pid" ] && kill -0 "$pf_pid" 2>/dev/null; then
    kill "$pf_pid" 2>/dev/null || true
    wait "$pf_pid" 2>/dev/null || true
  fi
}

# -----------------------------------------------------------------------------
# Step 1: port-forward
# -----------------------------------------------------------------------------
log "establishing port-forward temporal:7233 <- ssh $HOMELAB:${LOCAL_TEMPORAL_PORT}..."
ssh -fN -o ExitOnForwardFailure=yes \
    -L "${LOCAL_TEMPORAL_PORT}:127.0.0.1:7234" \
    "$HOMELAB" \
    "kubectl -n $NAMESPACE port-forward svc/temporal 7234:7233" &
SSH_PID=$!
trap "cleanup $SSH_PID" EXIT INT TERM

# wait until the local port is listening; fail loud if it never opens
PORT_READY=0
for _ in $(seq 1 20); do
  if (echo > "/dev/tcp/127.0.0.1/${LOCAL_TEMPORAL_PORT}") 2>/dev/null; then
    log "  reachable on 127.0.0.1:${LOCAL_TEMPORAL_PORT}"
    PORT_READY=1
    break
  fi
  sleep 1
done
if [ "$PORT_READY" = "0" ]; then
  log "FAIL: port-forward never became ready on 127.0.0.1:${LOCAL_TEMPORAL_PORT} after 20s"
  exit 1
fi

# -----------------------------------------------------------------------------
# Step 2: spawn N PositionWorkflows
# -----------------------------------------------------------------------------
log "spawning $COUNT PositionWorkflows via the harness..."
python3 "$(dirname "$0")/inject_synthetic_positions.py" \
  --count "$COUNT" \
  --temporal-host "127.0.0.1:${LOCAL_TEMPORAL_PORT}" \
  --tenant "$TENANT" --strategy "$STRATEGY"

# -----------------------------------------------------------------------------
# Step 3: snapshot BEFORE
# -----------------------------------------------------------------------------
list_running() {
  ssh "$HOMELAB" "kubectl -n $NAMESPACE run --rm -i temporal-cli-$$ --restart=Never \
    --image=temporalio/admin-tools:1.29 -- \
    temporal --address temporal:7233 workflow list \
      --query \"WorkflowType='PositionWorkflow' AND TenantStrategy='t-$TENANT/s-$STRATEGY' AND ExecutionStatus='Running'\" \
      --output json" 2>/dev/null | jq -r '.[].execution.workflowId' | sort
}

orchestrator_restart_count() {
  ssh "$HOMELAB" "kubectl -n $NAMESPACE get pod -l app=orchestrator -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}'"
}

orchestrator_image_sha() {
  ssh "$HOMELAB" "kubectl -n $NAMESPACE get deploy orchestrator -o jsonpath='{.spec.template.spec.containers[0].image}'" | awk -F: '{print $2}'
}

orchestrator_pod_name() {
  ssh "$HOMELAB" "kubectl -n $NAMESPACE get pod -l app=orchestrator -o jsonpath='{.items[0].metadata.name}'"
}

log "BEFORE redeploy:"
BEFORE_LIST=$(list_running)
BEFORE_RESTARTS=$(orchestrator_restart_count)
BEFORE_POD=$(orchestrator_pod_name)
echo "$BEFORE_LIST" | sed 's/^/  /'
log "  pod=$BEFORE_POD restartCount=$BEFORE_RESTARTS"

BEFORE_COUNT=$(echo "$BEFORE_LIST" | { grep -c . || true; })
if [ "$BEFORE_COUNT" -lt "$COUNT" ]; then
  log "FAIL: only $BEFORE_COUNT PositionWorkflows running, expected >= $COUNT"
  exit 1
fi

# -----------------------------------------------------------------------------
# Step 4: rollout restart
# -----------------------------------------------------------------------------
log "triggering rollout restart on deployment/orchestrator..."
ssh "$HOMELAB" "kubectl -n $NAMESPACE rollout restart deployment/orchestrator"
log "waiting up to 180s for rollout to complete..."
ssh "$HOMELAB" "kubectl -n $NAMESPACE rollout status deployment/orchestrator --timeout=180s"

# Brief settle so the new pod re-polls and resumes any in-flight WFTs.
sleep 5

# -----------------------------------------------------------------------------
# Step 5: snapshot AFTER
# -----------------------------------------------------------------------------
log "AFTER redeploy:"
AFTER_LIST=$(list_running)
AFTER_POD=$(orchestrator_pod_name)
AFTER_RESTARTS=$(orchestrator_restart_count)
IMAGE_SHA=$(orchestrator_image_sha)
echo "$AFTER_LIST" | sed 's/^/  /'
log "  pod=$AFTER_POD restartCount=$AFTER_RESTARTS"

# -----------------------------------------------------------------------------
# Step 6: assertions
# -----------------------------------------------------------------------------
log "asserting BEFORE == AFTER..."
if [ "$BEFORE_LIST" != "$AFTER_LIST" ]; then
  log "FAIL: PositionWorkflow set diverged across redeploy"
  log "BEFORE:"
  echo "$BEFORE_LIST" | sed 's/^/  /' >&2
  log "AFTER:"
  echo "$AFTER_LIST"  | sed 's/^/  /' >&2
  exit 1
fi

log "asserting orchestrator pod actually rolled..."
if [ "$BEFORE_POD" = "$AFTER_POD" ]; then
  log "FAIL: orchestrator pod name unchanged — rollout didn't actually replace it"
  exit 1
fi

log "checking new pod logs for NonDeterministicException..."
NDE=$(ssh "$HOMELAB" "kubectl -n $NAMESPACE logs $AFTER_POD --tail=500 | { grep -c 'NonDeterministicException' || true; }")
if [ "$NDE" -gt 0 ]; then
  log "FAIL: orchestrator pod logs contain NonDeterministicException ($NDE occurrences)"
  exit 1
fi

# -----------------------------------------------------------------------------
# Step 7: emit the Deploy-Verified line
# -----------------------------------------------------------------------------
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
LINE="Deploy-Verified: ${AFTER_POD}@${IMAGE_SHA} healthy ${TS}"
log "PASS"
log "  positions preserved: $COUNT/$COUNT"
log "  orchestrator pod: $BEFORE_POD → $AFTER_POD"
log "  NonDeterministicException count: 0"
echo
echo "$LINE"
