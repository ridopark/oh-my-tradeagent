# PLAN — 2026-07-22 DB-backed account-cap enumeration + loud-RED "cap NOT protecting" alert

Phases 3b + 3 of PLAN-2026-07-21-account-cap-failclose-and-silent-inactive, scoped as one PR. The
account daily-loss cap resolves a tenant's broker_target (for the SOD-equity read) and its loss basis
(realized PnL + open book) by **scanning the ConfigMap tenants tree** via `ScannerTenantStrategies`.
A DB-onboarded tenant that isn't in that tree resolves to an empty strategy set → cap **never arms**
(silently, on real money — this is exactly what happened to prod-kipark on 2026-07-21). Every live
tenant (prod_real, staging_paper, prod-kipark) currently depends on a **live-only** tree entry that a
full `kubectl apply` of `51-orchestrator.yaml` would revert, silently switching the real-money cap
OFF. Fix: resolve strategies from the **DB** — the same source order-routing, recon, and the
kill-switch bootstrap already use — so any DB-onboarded tenant's cap works automatically, and make a
cap that still can't arm page **loud RED** instead of a low-key inactive note.

**Source:** 2026-07-21 SOD-equity forensics + operator direction (2026-07-22). All live tenant tree
entries are out-of-band; the repo manifests contain only `dev` (confirmed `40-tenants-config.yaml`,
`51-orchestrator.yaml:170-175`).

## P0 — operator: none (code change; no live mutation).

## Phase A — DB-backed strategy enumeration for the account cap (orchestrator)
**Goal:** the account cap's broker_target + strategy enumeration comes from the DB, not the ConfigMap
tree, so any DB-onboarded live tenant (prod_real, staging_paper, prod-kipark, and every future one)
arms without a per-tenant tree patch.
**Changes** (anchors — verify by reading):
- Introduce a **DB-backed `TenantStrategies`** implementation that enumerates `(tenant_id,
  strategy_id)` from the DB, modeled on / delegating to `DbStrategyRegistry`
  (`services/orchestrator/.../platform/DbStrategyRegistry.java` — already `SELECT DISTINCT` in
  db-mode and reads `strategy_config` fresh). It must expose the same `strategyIdsForTenant(tenantId)`
  contract `ScannerTenantStrategies` (`.../activities/ScannerTenantStrategies.java:27-32`) provides.
- Rewire `AccountKillSwitchConfig` (`.../config/AccountKillSwitchConfig.java:33-35`, currently
  `new ScannerTenantStrategies(Path.of(tenantsDir))`) to the DB-backed impl **when
  `strategy.config.source=db`** (the live mode); keep the scanner as the fallback for yaml-mode so
  dev/tests are unchanged. Do the same for any other consumer resolving via the tree:
  `TenantConfigActivitiesImpl.tenantBrokerTarget` and `AccountPnlActivitiesImpl` (`:65,78,114`) must
  use the same resolver instance.
- **Fail-loud at the silent guard:** `AccountKillSwitchWorkflowImpl.captureSodEquity` (`:864-868`)
  returns null with NO log when broker_target is null — add a WARN and a **typed defer reason**
  (`broker_target_unresolved` | `snapshot_failed` | `equity_nonpositive`) carried on the
  `AccountKillSwitchCapInactive` audit subject (heartbeat inactivity bookkeeping, `:363-408`).
**Replay safety:** the resolution swap is INSIDE activities (`captureSodEquity`, realized/open-book
reads) — activity internals are not replay-checked (only command type/ordering), so no `getVersion`
gate for the source swap. If the cap now ARMS where it previously deferred, that changes future
heartbeat outcomes but does not diverge the replay of recorded history (activity results aren't
replay-verified). Confirm no NEW command is introduced unconditionally; if the defer-reason path adds
an audit command on a tick that previously emitted none, version-gate that emit.
**Config-source note:** live homelab runs `strategy.config.source=db` (values from DB). Verify the
DB-backed resolver is only active in db-mode; yaml-mode (dev/tests) keeps the scanner.
**Tests (TDD):** a DB-onboarded tenant with NO ConfigMap tree entry → `strategyIdsForTenant` returns
its strategy from the DB, `tenantBrokerTarget` resolves `alpaca-live`, and the heartbeat ARMS (SOD
equity read dispatched) — the prod-kipark scenario, now green. Empty DB result for a cap-configured
tenant → fail-loud typed reason, not silent defer. yaml-mode unchanged (scanner still used).
**Verify:** `mvn -pl services/orchestrator -am spotless:apply` + the new/affected tests; behavioral
assertion: a tenant present in DB but absent from the tenants tree resolves broker_target + arms.

## Phase B — loud-RED "account cap NOT protecting <tenant>" escalation (orchestrator alerter)
**Goal:** if a configured cap still can't arm for ≥K ticks while the tenant holds open positions,
page a **RED** "Account cap NOT protecting <tenant> — <reason>" alert, distinct from the low-key
`AccountKillSwitchCapInactive` note. (The existing `AccountKillSwitchCapAlerter.java:74-97` already
fires a generic inactive page; enrich it with the typed reason from Phase A + escalate severity —
do NOT invent a parallel alerter.)
**Changes:** thread the Phase-A typed defer reason into the `AccountKillSwitchCapInactive` subject;
in `AccountKillSwitchCapAlerter.buildEmbed` render RED (`AlertColors.RED`) with the reason + a faster
first-page threshold for a real-money tenant. If a distinct audit kind is cleaner
(`AccountKillSwitchCapUnprotected`), register it in `AuditEventKinds.ALL_KINDS` (KindRegistryGuard);
prefer enriching the existing kind if it avoids a new registration.
**Replay safety:** alerter is out-of-workflow; subject enrichment is activity input (replay-safe).
**Tests (TDD):** a configured pct cap + unresolvable broker_target + open positions past K ticks →
one RED "cap NOT protecting" page naming the reason; a normally-armed cap never fires it.
**Verify:** module build + the alerter test; behavioral assertion: prod-kipark's all-day not-armed
scenario (pre-Phase-A) would have paged RED with `broker_target_unresolved`.

## Ship order & gating
Single PR (both phases — the alert consumes Phase A's typed reason). TDD, `spotless:apply` on touched
modules, operator merge gate (real-money kill-switch path). No `tenants/*.yaml` / ConfigMap change.
No new Temporal version gate expected (activity-internal + subject-only); add one only if a new
command is introduced on a previously-silent tick. `KindRegistryGuardTest` if a new audit kind is
added. This makes the ConfigMap tenants-tree dependency vestigial for the cap — a durable follow-up
could drop the tree mount entirely once nothing else enumerates from it.
Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
