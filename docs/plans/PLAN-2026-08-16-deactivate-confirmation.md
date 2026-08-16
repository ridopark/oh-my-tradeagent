# PLAN — 2026-08-16 Deactivate confirmation

On 2026-08-16 at 11:40 UTC, three Deactivate clicks on the live tenants attempted to market-sell
**12 real-money contracts** — prod_real 3, prod-kipark 7, prod-jinchul 2 of `DRAM 270319C00100000`.
Nothing sold, for exactly one reason: Alpaca rejected the orders with *"options market orders are
only allowed during market hours."* It was a Sunday. On a weekday all three positions would be gone.

The operator's intent was to close a position on a **paper** tenant and delete it. The delete route
would have refused those live tenants at its first gate anyway (`LIVE_BROKER_TARGET`), so the clicks
bought nothing and risked everything.

**This is not a bug.** `LiveActivationWorkflowImpl:205` documents the behaviour deliberately:

> *"The kill-switch trip below is the real stop (it also halts in-flight / open positions, which the
> void row — which only brakes new entries — does not)."*

The defect is that **nothing tells the operator that, and nothing asks.** Deactivate is one click,
with no request body, no dialog, and no statement of consequence.

## Current behaviour (verified anchors)

| anchor | what it does |
|---|---|
| `dashboard/components/ActivateButton.tsx:36` | `onClick` fires immediately — no dialog, no confirm, no consequence shown |
| `ActivationController.java:109` | `POST /{tenant}/strategies/{strategy}/deactivate-live` — **no `@RequestBody` at all**, only path vars |
| `LiveActivationWorkflowImpl.java:219` | `gate.tripKillSwitch(..., "live_deactivation:one_click")` |
| `KillSwitchCascadeActivitiesImpl.java:37` | cascade → every RUNNING PositionWorkflow for that `(tenant, strategy)` → `riskBreach` → `flatten-risk_breach` |

Contrast the repo's own treatment of a destructive operator action:

| anchor | what it does |
|---|---|
| `TenantDeleteRequestBody.java:10` | requires `confirm_tenant_id` |
| `TenantDeleteController.java:188-191` | 400 `CONFIRM_MISMATCH` unless it string-equals the path, **checked before any read** |

Deleting a *dark, never-traded* tenant demands a typed confirmation. Liquidating live positions
demands nothing. That asymmetry is the whole finding.

## P0 — Immediate operational (no code; operator)

- **Until this ships: do not use Deactivate on a live tenant with open positions during market
  hours.** There is no undo — the flatten is a market order.
- The three live tenants were re-Activated within ~3s and positions are intact (verified: prod_real
  3 @ 3.28, prod-kipark 7 @ 3.399, prod-jinchul 2 @ 3.40).

---

## Phase 1 — show what would be liquidated (api-gateway, read-only)

**Goal:** make the consequence visible before anyone changes the write path. No behaviour change.

**Changes** (anchors):
- New `GET /{tenant}/strategies/{strategy}/deactivate-preview` on
  `ActivationController.java` (alongside `:109`), returning
  `{open_position_count, positions: [{contract_symbol, remaining_qty}]}`.
- Reuse `OpenPositionWorkflowChecker` (`:31 hasOpen`) — it already runs the
  `TenantStrategy` Search-Attribute query the delete route's P3 uses. It returns a **boolean**, so
  extend it with a `listOpen(...)` that returns the executions and queries each `positionState()`
  for `contractSymbol` + `remainingQty`. Keep `hasOpen` intact — the delete route depends on it.

**Version gate:** none — api-gateway, not a workflow.

**Tests (TDD):**
- `deactivatePreview_openPositions_listsContractAndQty`
- `deactivatePreview_noOpenPositions_returnsEmptyAndZero`
- `deactivatePreview_requiresAllowlistedOperator` — 400 absent / 403 non-allowlisted, matching `:109`
- `deactivatePreview_hasNoSideEffects` — no kill-switch trip, no cascade

**Verify:** `mvn -pl services/api-gateway -am spotless:apply` + module tests. Behavioural assertion:
calling preview against a tenant with an open position returns it, and the kill switch is untouched.

---

## Phase 2 — UI states the consequence and asks (dashboard)

**Goal:** the operator sees what will be sold, and confirms deliberately.

**Changes** (anchors):
- `dashboard/components/ActivateButton.tsx:36` — for `intent="deactivate"` only, replace the
  immediate `onClick` with the inline arm/confirm island already used by `TrimButton`,
  `ForceExitButton` and `StopLossButton`. On arm: call the Phase 1 preview and render
  **"Deactivating sells N contracts at market: DRAM 270319C00100000 ×3"**, plus Confirm / Cancel.
  Activate keeps its current one-click behaviour — it is not destructive.
- Send `{"confirm_tenant_id": "<tenant>"}` in the POST body **now**, before Phase 3 enforces it. The
  endpoint has no `@RequestBody` today, so an unknown body is ignored — this is deliberately
  ordered so the button is never broken by Phase 3.

**Tests:** component tests — arm shows the preview; Cancel fires nothing; Confirm posts once with
the body; a zero-position tenant still confirms (wording differs, no false "sells 0 contracts"
alarm).

**Verify:** dashboard test run. Behavioural assertion: a single click never reaches the API.

---

## Phase 3 — enforce the confirmation server-side (api-gateway)

**Goal:** make the guarantee structural. A UI-only confirm is bypassable by a direct API call or a
stale tab — and a stale tab is a plausible cause of the original incident.

**Changes** (anchors):
- New `LiveDeactivationRequestBody` record mirroring `TenantDeleteRequestBody.java:10` — a plain
  api-gateway record, **not** a contract schema, so no POJO/pydantic regeneration.
- `ActivationController.java:109` — accept `@RequestBody(required = false)`; 400 `CONFIRM_MISMATCH`
  unless `confirm_tenant_id` string-equals the path tenant, **checked before the workflow starts**,
  exactly as `TenantDeleteController.java:188-191` does.

**Ship order is load-bearing:** Phase 3 must merge **after** Phase 2 is deployed, or every Deactivate
400s.

**Tests (TDD):**
- `deactivate_missingConfirm_400_andNeverTrips` — the incident, inverted
- `deactivate_confirmMismatch_400_andNeverTrips` — the wrong-tenant case that actually happened
- `deactivate_confirmMatch_proceeds`
- Assert **zero side effects** on refusal: no `KillSwitchTripped`, no cascade

**Verify:** module tests + `spotless:apply`. Behavioural assertion: a bodyless POST to
`deactivate-live` returns 400 and `audit_log` gains no `KillSwitchTripped` row.

---

## Forks — decide before Phase 3, do not bury

**A. Echo the position count as well as the tenant id?**
Requiring `confirm_open_positions: N` turns the confirmation into an optimistic-concurrency check: if
a position opened or closed between preview and confirm, the request fails and the operator re-reads.
Stricter and it closes the stale-tab hole properly. Cost: a new failure mode during fast markets, and
a second thing to keep in sync. *Recommendation: yes for live tenants, since that is where the
irreversible cost is.*

**B. Should Deactivate liquidate at all?**
The deeper question this incident raises. There is no way today to say *"stop new entries, keep what
I hold"* — `promotion.deactivate` brakes new entries, and the kill-switch trip is what flattens.
Splitting them would give an operator a genuine pause. That is a **behavioural change to a live
safety path** and needs its own plan; naming it here so the confirmation is not mistaken for having
solved it.

**C. Same treatment for the account-level kill switch?**
`AccountKillSwitchCascadeActivities` cascades tenant-wide. Out of scope, same class of exposure.

## Ship order & gating

1. **Phase 1** (read-only) → 2. **Phase 2** (UI, sends confirm) → 3. **Phase 3** (server enforces).

Each: TDD, `spotless:apply` on every touched module, own PR, operator merge gate. Phase 3 strictly
after Phase 2 is **deployed**, not merely merged.

## Notes

- No Temporal version gate anywhere — all changes are api-gateway/dashboard, not workflow bodies.
- No contract schema change → no pydantic regeneration, no drift job.
- If a `LiveDeactivationRefused` audit kind is added, register it in
  `services/audit/.../AuditEventKinds.ALL_KINDS` or the pre-push `KindRegistryGuardTest` blocks.
- `KillSwitchWorkflowImplTest` is a known flake — re-run, do not "fix".
