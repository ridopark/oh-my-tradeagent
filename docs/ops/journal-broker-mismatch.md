# Runbook: journal vs broker mismatch (reconciliation surfaces discrepancy)

## When to use

`ReconciliationWorkflow` (runs every 5 minutes plus on orchestrator startup) compares the
local `OrderIntentJournal` to the broker's open-orders list and finds a discrepancy. The
plan documents three discrepancy classes:

1. **journal-no-broker** — journal has an entry the broker doesn't recognize. Either we
   never submitted, or the broker rejected without ack reaching us.
2. **broker-no-journal** — broker has an open order we have no journal record for.
   Usually means an out-of-band order was placed (someone hand-entered) — the bot does
   not auto-cancel.
3. **filled-but-not-acked** — broker reports a fill we never processed; recon signals
   `reconcile_orphan` to the matching `PositionWorkflow`.

## Symptoms

- Audit log shows `JournalOrphan`, `BrokerOrphan`, or `ReconciliationCompleted` events
  with `discrepancies > 0`.
- `api-gateway` operator notification (when wired — Phase 7).
- Position count visible in the api-gateway doesn't match positions in Tradier (or
  whichever live broker once promoted).

## Detection

```sh
ssh ridopark@192.168.10.123

# Latest reconciliation event:
curl -s 'http://copytrade.homelab.local/audit?tenant=dev&strategy=copytrade-v1&kind=ReconciliationCompleted&limit=5' | jq

# Orphan events:
curl -s 'http://copytrade.homelab.local/audit?tenant=dev&strategy=copytrade-v1&kind=JournalOrphan&limit=10' | jq
curl -s 'http://copytrade.homelab.local/audit?tenant=dev&strategy=copytrade-v1&kind=BrokerOrphan&limit=10' | jq

# Inspect the journal directly (exec-tradier-paper DB):
kubectl -n copytrade exec statefulset/postgres -- \
  psql -U "$(kubectl -n copytrade get secret postgres-credentials -o jsonpath='{.data.POSTGRES_USER}' | base64 -d)" \
       -d exec_tradier_paper \
       -c "SELECT intent_key, state, submitted_at, broker_order_id FROM order_intent_journal WHERE state IN ('Submitted','Pending') ORDER BY submitted_at DESC LIMIT 20;"
```

## Path 1: journal-no-broker

A journal entry is `Submitted` but the broker doesn't see it. Likely causes:

- Broker API call failed after `record_intent` wrote to the journal but before the
  broker accepted the order (network hiccup, broker 5xx, timeout).
- Broker rejected silently (rare; usually returns a typed error).

```sh
# Find the affected intent_key (from the JournalOrphan audit event subject):
curl -s 'http://copytrade.homelab.local/audit?kind=JournalOrphan&limit=1' | jq '.events[0].subject'

# If the entry is old (> 5 min) and the broker still doesn't recognize it, mark it
# expired so reconciliation stops re-firing the orphan event. This is currently a
# DB-direct fix (no api-gateway endpoint for journal mutation in v0):
kubectl -n copytrade exec statefulset/postgres -- \
  psql -U <user> -d exec_tradier_paper \
       -c "UPDATE order_intent_journal SET state='Expired' WHERE intent_key='<intent-key>' AND state IN ('Submitted','Pending');"
```

Verify the next reconciliation cycle has zero orphans for that intent_key.

## Path 2: broker-no-journal

The broker has an order with a `client_order_id` we never journaled. The bot does **not**
auto-cancel — it's likely an operator action via the broker's UI.

1. Confirm the order is intentional (check with whoever has broker access).
2. If intentional: do nothing. The orphan event will keep firing every 5 minutes until
   the order fills or you cancel it manually via the broker UI. Audit retains the trail.
3. If unintentional: cancel via the broker UI (NOT via the api-gateway, which would
   require a journal entry to bind the cancel to).

## Path 3: filled-but-not-acked

Broker reports a fill we never processed. Reconciliation signals `reconcile_orphan` to
the `PositionWorkflow` matching the OCC. If the workflow is still Running, it absorbs
the fill into its state. If not (workflow already completed because it didn't see this
fill happen):

```sh
# Find the affected position by OCC:
temporal workflow list --query "WorkflowType='PositionWorkflow' AND ContractSymbol='<OCC>'"

# If the position is closed but the broker shows shares outstanding:
# 1. Verify the fill on the broker side.
# 2. Open a new PositionWorkflow manually (rare; expected to happen <1/year in v0):
temporal workflow start \
  --workflow-id "t-dev/s-copytrade-v1/pos/<OCC>/manual-<ts>" \
  --workflow-type PositionWorkflow \
  --task-queue orchestrator-core \
  --search-attribute 'TenantStrategy="t-dev/s-copytrade-v1"' \
  --search-attribute 'ContractSymbol="<OCC>"' \
  --input '<PositionWorkflowInput JSON matching the broker-reported state>'
```

## Rollback

Reconciliation is read-side; there's no transactional rollback. The DB-direct fixes
above are best-effort. If a fix goes wrong:

```sh
# Restore the journal entry:
kubectl -n copytrade exec statefulset/postgres -- \
  psql -U <user> -d exec_tradier_paper \
       -c "UPDATE order_intent_journal SET state='<prior-state>' WHERE intent_key='<intent-key>';"
```

## Post-incident verification

- Audit log shows `ReconciliationCompleted` with `discrepancies: 0` for two consecutive
  cycles (10 minutes).
- `/positions` count matches the broker's open-positions list for the tenant.
- No new `JournalOrphan` / `BrokerOrphan` events for the resolved key.

## Prevention

- The 3-layer idempotency (Temporal workflow_id REJECT_DUPLICATE → `intent_key` →
  broker `client_order_id`) prevents double-fills but cannot prevent
  network-loss-after-broker-accept. The reconciliation cadence (5 min default) bounds
  the discovery time.
- Phase 6 plan calls out tightening the cadence to 60s for live broker targets.

## Why this exists

The journal-broker boundary is the highest-stakes consistency surface in the system
(real options money, leveraged). The reconciliation flow is designed to *surface* rather
than auto-resolve, because the wrong auto-resolve can flip a position from the author's
intent to the inverse. This runbook is the operator's manual decision tree.
