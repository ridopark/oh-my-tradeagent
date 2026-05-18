# RTO / RPO targets per component (v0)

## Why this exists

Issue #24 (risk-manager review): the copy-trade stack runs single-node on the homelab k3s
cluster at `ssh ridopark@192.168.10.123`, with single Temporal cluster, single Postgres,
single Discord sidecar session, and one broker connection per `(tenant, broker_env)`. None
of those SPOFs had documented Recovery Time Objective (RTO) or Recovery Point Objective
(RPO) targets, so on-call had no quantified guidance for "is this recovery on track?" vs
"escalate / cut to safe-mode now". This doc publishes per-component targets and the v0 gap
+ Phase 6 remediation behind each one.

**Definitions:**

- **RTO** — Recovery Time Objective. How long after an outage starts before the
  component is back to serving its production workload. Counted from incident
  declaration to verified recovery (post-incident verification in the per-component
  runbook).
- **RPO** — Recovery Point Objective. The maximum tolerable amount of data loss
  measured in time. `RPO = 0` means no data loss is tolerable; the recovery must
  preserve every state transition through the outage.

**Operating envelope:** v0 deploys to one homelab node. All targets assume a
single-node restart-class incident (pod / container / process crash, image bump,
PVC remount). Node-loss class incidents fall back to manual rebuild — the v0 plan
explicitly defers HA to Phase 6 (see PLAN.md Phase 6 "single-node k3s is the v0
homelab target by design; revisit at Phase 6" callout in
`docs/ops/temporal-cluster-down.md` §Prevention).

## Per-component targets

| Component | RTO | RPO | What backs recovery | v0 gap | Phase 6 remediation |
|---|---|---|---|---|---|
| **Temporal shared cluster** (`temporal/temporal-frontend`) | 5 min | 0 | Workflow history journal in Postgres survives frontend pod restart; Temporal SDK clients retry transparently. Workers resume from last WFT after frontend recovers. | Shared-cluster Postgres on a single PVC; no automated snapshot. Restart of `temporal-frontend` affects every tenant of the shared cluster, not just copy-trade. | Multi-node k3s + automated Postgres PVC snapshots (per `temporal-cluster-down.md` §Prevention). |
| **Copy-trade Postgres** (`copytrade/postgres`) | 5 min | 0 (intra-day); 24h (node-loss) | Pod restart re-mounts the PVC; journal + audit + `option_symbol_cache` survive. | No automated PVC snapshot; PVC-loss requires manual restore from the last operator backup (cadence not formalised). The "24h node-loss RPO" assumes the operator runs `kubectl exec ... pg_dumpall` at least daily — there is no scheduled job for this in v0. | Scheduled `pg_dumpall` CronJob with off-node retention + tested restore drill. |
| **Redis** (`copytrade/redis`) | 60s | 0 for correctness; lossy for cost counters | Counters are short-TTL (`QuotaTracker`); position correctness is journaled to Postgres, not Redis. Redis pod restart loses the in-flight quota window — accepted because quota is cost-only, not safety. | None for safety. For cost-attribution accuracy across a restart, quota counters reset to zero — operators read `audit` for ground truth. | Optionally migrate quota counters to a Postgres-backed implementation if attribution accuracy across restarts becomes a Phase 6 requirement. |
| **orchestrator-svc** | 60s | 0 | All workflow state is in Temporal; the pod is stateless. New pod re-registers workers and resumes polling. `docs/ops/orchestrator-redeploy.md` documents the drain-vs-pin redeploy paths. | A workflow-shape change without `Workflow.getVersion` wrapping requires the **drain** path (could wait hours, or until 15:45 ET EOD timer). | Library of `Workflow.getVersion` checkpoints in every long-lived workflow so the **pin** path is always available. |
| **exec-svc** (per `<provider>-<env>`) | 60s | 0 | `OrderIntentJournal` in Postgres is the durable record of every intent; broker reconciliation (`ReconciliationWorkflow`, scheduled every 5 min) catches any intent that didn't materialise as an order. | Recon lag p99 target is < 60s (PLAN.md Phase 7 promotion-gate criterion d); v0 is sometimes slower under load. | Tune recon interval + p99 telemetry; required to graduate to live (Phase 7). |
| **market-data-svc** | 60s | not applicable (in-memory cache, no durable state) | Caffeine cache rebuilds from broker quote stream on restart. No journal. | Cache cold-start adds 1-5s of activity latency on the first quote per active contract after restart. Accepted for v0 (cold-start happens at most once per restart). | n/a — design is correct for v0 throughput. |
| **api-gateway** | 60s | 0 (no state; routes to Temporal + audit) | Pod is stateless; restart re-establishes Temporal client. | None. | n/a. |
| **signal-source-discord** | **60s** (issue #24 target) | not applicable (in-memory dedupe LRU; correctness via Temporal `workflow_id`) | New pod re-loads `storage_state.json` from PVC, resumes Playwright DOM polling. Heartbeat file refreshes within 30s of pod start (livenessProbe contract in `infra/k8s/55-signal-source-discord.yaml`). | **The session cookie itself can expire mid-day** with no automatic recovery — this is the SPOF the issue calls out. Re-bootstrap is a manual operator action (`docs/ops/discord-session-expired.md`) needing X-server access. The 60s RTO assumes a pre-staged secondary `storage_state.json` is available for hot swap (`docs/ops/sidecar-session-cutover.md`); without one, RTO is "operator time to complete 2FA in a visible Chromium window" — minutes to hours. | Proactive `storage_state.json` refresh cadence + alerting when the session is about to invalidate (PLAN.md Phase 6 callout; PRD.md "Discord auth: storage_state.json refresh cadence; alerting when session invalidates"). Until then, the alert spec in `docs/ops/sidecar-pagerduty-alert.md` + cutover runbook is the manual stopgap. |
| **audit-svc** | 60s | 0 | Audit events are append-only in Postgres; pod is stateless. | None. | n/a. |
| **Broker connection** (one per `(tenant, broker_env)`) | 60s | 0 | Single TCP/HTTP connection to broker per `exec-svc` pod; re-established on pod restart. Outstanding orders persist at the broker; recon reconciles. | Broker rate limits can drop the connection under load; v0 has no circuit breaker. | Per-tenant exec worker pools (PLAN.md Phase 6, Issue #20) bound the blast radius of any single broker connection drop to a single tenant. |

## Reading this table

- **RTO 60s** means: from the moment the on-call operator declares the incident
  and applies the documented restart procedure, the component should be back to
  serving the production workload within 60 seconds. This budget does **not**
  include detection time — detection-to-declaration is a separate SLO bounded by
  the staleness alerts (sidecar heartbeat, recon lag, etc.).
- **RPO 0** means: no state transition should be lost across the recovery. For
  Temporal-backed components this is guaranteed by the workflow journal; for
  Postgres-backed components it depends on the PVC surviving the restart (i.e.
  pod-class incident, not PVC-loss class).
- **RPO 24h** on Postgres node-loss is an honest acknowledgement that v0 has no
  scheduled PVC snapshot. Operators should treat node-loss as a **declare
  incident, scale to zero, restore from operator backup, re-bootstrap kill
  switch** sequence — not a routine recovery.

## What's NOT covered by these targets

- **Discord upstream outage.** If Discord itself is down, the sidecar can't poll
  and the cutover runbook doesn't help. Discord's SLA is not under our control;
  we wait it out.
- **Broker upstream outage.** If Alpaca paper (or Tradier sandbox, or live
  broker post-Phase 7) is down, `exec-svc` activities fail with `BROKER_DOWN`
  and Temporal retries per its retry policy. RTO is bounded by the broker's own
  recovery, not ours.
- **Kill-switch posture.** Whenever a recovery is in progress and the operator
  is uncertain whether Temporal is consistent, **trip the kill switch first**
  (per `docs/ops/temporal-cluster-down.md` §Immediate action). The kill switch
  fails closed by design — that's the safety floor under these RTO/RPO numbers.

## Verifying these numbers

These are **targets**, not measurements. The Phase 5b runbook drill schedule
(PLAN.md Phase 5b "runbook drills pass for at least 3 of the 5 documented
incidents") is the mechanism by which we'll confirm the 60s / 5min RTOs are
actually achievable on the v0 homelab topology. Drill results that come in
worse than these targets are a Phase 6 prioritisation signal, not a v0 ship
blocker — but they MUST be logged in the runbook's post-incident-verification
section so we don't lie to ourselves about recovery posture.

## See also

- `docs/ops/discord-session-expired.md` — sidecar re-bootstrap (no secondary available)
- `docs/ops/sidecar-session-cutover.md` — sidecar swap to pre-staged secondary (60s RTO path)
- `docs/ops/sidecar-pagerduty-alert.md` — heartbeat staleness alert spec
- `docs/ops/temporal-cluster-down.md` — shared Temporal cluster recovery
- `docs/ops/orchestrator-redeploy.md` — drain vs pin redeploy paths
- `docs/ops/kill-switch-stuck.md` — kill-switch recovery
- `docs/ops/journal-broker-mismatch.md` — reconciliation discrepancy recovery
- `docs/ops/live-promotion-rollback.md` — Phase 7 live → paper rollback
