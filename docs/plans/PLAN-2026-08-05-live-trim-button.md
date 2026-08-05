# PLAN — 2026-08-05 /live per-position "Trim" (partial close) button

Give the operator a one-click, audited way to **shave a position down** from the dashboard /live
holdings table — sell a fraction of the remaining contracts at market and leave the rest running
with every existing exit intact (STC, chandelier trail, EOD/expiry timers). The reduce-only sibling
of the shipped "Force exit" button ([[PLAN-2026-07-25-live-force-exit-button]]), which can only
flatten the WHOLE lot.

**Operator interaction** (three steps, no modal — the repo has no modal component):

```
NVDA 260821C00225000   22  …   [ Trim ]  [ Force exit ]
  click Trim  →   Trim  [25% · 6] [50% · 11] [75% · 17]  [Cancel]
  click 50%   →   [Confirm — sells 11 of 22 NVDA 260821C00225000 at market]  [Cancel]
```

**Core design decision — reuse, don't rebuild.** The trim does NOT add exit machinery. The Update
handler synthesizes a `PartialExitRequest{market:true}` onto the SAME `pendingExits` deque the STC
path feeds, so it inherits the qty math, min-partial-qty behavior, dedupe, realized-P&L fill
booking, and place-failure / late-fill recovery that `processOne` already carries. `CopytradeDerisk
WorkflowImpl` (#656) is the precedent: it drives the identical `partialExit` path programmatically.
The only genuinely new workflow code is the Update handler plus a one-line `market → limitPrice=null`
branch.

**Operator decisions locked before implementation** (asked, not assumed):
1. **Size** = preset fractions 25/50/75%, each labelled with the contracts it actually sells.
2. **Pricing** = MARKET now (exit-NOW, like force_close) — not the bounded-limit reprice ladder.
3. **Remainder** = untouched (no auto-arm of the chandelier trail).

## Implementation status (read this first)

**The code for all three phases is ALREADY IMPLEMENTED and green on branch
`feat/live-trim-button`** (branched off `main` @ `386a765`). This plan documents the shipped design,
its replay-safety argument, and the operator runbook; it is NOT a request to re-derive the
implementation. What remains is: validate against the success criteria below, then open the PR(s).

Verified at authoring time: orchestrator 173 tests green (incl. `PositionWorkflowImplLegacyReplayTest`),
audit + tenant-dashboard-bff 203 green, contract round-trip 35 green, dashboard `tsc --noEmit` +
`next build` clean, and every UI state driven in a real browser against a throwaway harness.

## P0 — Operator follow-ups (no code)

- After the phases ship + deploy: flip BOTH dark flags — the BFF
  `positions.partial-close.write-enabled` (env `POSITIONS_PARTIAL_CLOSE_WRITE_ENABLED`) and the
  dashboard `TRIM_WRITE_ENABLED` — via `kubectl set env` (live-only, survives deploys), exactly like
  the force-exit pair. Deliberately SEPARATE flags from force-exit: trimming (reduce-only) and
  flattening are independent capabilities, so either can be armed without the other.
- Real-money gate: first live use should be an intentional operator trim on a small, known,
  multi-contract position, watched end-to-end (`OperatorTrimRequested` → `PartialExitRequested` →
  `PartialExitFilled` → the /live Qty cell drops).

## Phase 1 — Contract DTOs (contract) — non-trading-critical

**Goal:** the operator-facing Update payload/result, plus the `market` placement flag on the
existing partial-exit signal.

**Changes** (anchors):
- `contract/schemas/partial-close-request.json` (NEW) — `PartialCloseRequest{schema_version,
  operator_id, reason, fraction}`. `fraction` is `exclusiveMinimum: 0` **and `exclusiveMaximum: 1`**:
  a trim is reduce-only BY CONSTRUCTION, so a full close can only be a `force_close`.
- `contract/schemas/partial-close-result.json` (NEW) — `PartialCloseResult{schema_version, status,
  exit_signal_id}`, `status ∈ {ACCEPTED, NOOP_ALREADY_CLOSED}`. Mirrors `ForceCloseResult`.
- `contract/schemas/partial-exit-request.json` — add optional `market` boolean (MARKET placement vs
  the `ref_premium`-seeded bounded limit + reprice ladder). ALSO drop `ref_premium` from `required`:
  it is the limit SEED, and the de-risk-cue dispatcher already passes a null
  `target_entry_premium` (`CopytradeDeriskWorkflowImpl.setRefPremium`) — the schema was lying about
  existing behavior. Loosening `required` cannot break deserialization of recorded payloads.
- `contract/fixtures/partial-exit-request.json` — add `"market": false` (the round-trip test asserts
  serialized == fixture with no `exclude_none`, so optional fields are populated by convention).
- Regenerate the Python models: `cd contract/python && ./regen.sh` (CI fails the
  `Python (pydantic round-trip + regen drift)` job on drift).

**Tests:** `contract/python/tests/test_round_trip.py::test_partial_exit_request_round_trips` asserts
`market is False` (an STC partial rests a bounded limit; market is the opt-in operator placement).

**Verify:** `cd contract/python && python -m pytest tests/test_round_trip.py -q` (35 passed);
`mvn -q -pl contract/java -am compile`. **Constraints:** regen drift job; no replay concern (additive
optional field). Ships FIRST (the other phases import these DTOs).

## Phase 2 — PositionWorkflow.partial_close Update (orchestrator) — TRADING-CRITICAL

**Goal:** an operator-initiated, audited, reduce-only MARKET partial close that reuses the existing
partial-exit pipeline end to end.

**Changes** (anchors):
- `services/orchestrator/.../workflows/PositionWorkflow.java` — add
  `@UpdateValidatorMethod(updateName="partial_close") partialCloseValidator(PartialCloseRequest)` +
  `@UpdateMethod(name="partial_close") PartialCloseResult partialClose(PartialCloseRequest)`.
- `services/orchestrator/.../workflows/PositionWorkflowImpl.java`:
  - `partialCloseValidator` — reject blank `operator_id` / `reason`, and `fraction` outside `(0,1)`
    EXCLUSIVE, so the operator gets a synchronous rejection instead of a silent
    `ExitDuplicateSuppressed(bad_fraction)` audit.
  - `partialClose` handler — mirrors `forceClose`: buffer a `PartialCloseDirective` into
    `pendingPartialCloses`; `NOOP_ALREADY_CLOSED` when `positionConfirmed && remainingQty <= 0`;
    `ACCEPTED` otherwise with `exit_signal_id = "trim:<operator_id>:<workflowMillis>"`. Buffers
    (rather than synthesizing in-handler) because the Update can land before `run()` assigns `input`.
  - `operatorTrimRequest(...)` — converts the directive into the synthetic `PartialExitRequest`
    (`market=true`, `ref_premium` null, `author=<operator>`, `raw_line=<reason>`,
    `reason=operator_trim`).
  - main loop — drain `pendingPartialCloses` into `pendingExits` immediately BEFORE the
    `pendingExits` drain (no `continue`: a trim is a normal FIFO partial, unlike
    force_close/risk_breach which pre-empt because they flatten the whole lot); add
    `|| !pendingPartialCloses.isEmpty()` to the `Workflow.await` predicate.
  - `processOne` — `boolean marketNow = Boolean.TRUE.equals(req.getMarket())` gates `limitPrice=null`
    at all THREE placement sites: the first placement, the stepped-reprice retry (also skipping its
    quote Activity), and the legacy `freshLimit` chain (reachable for a multi-day lot whose
    stepped-reprice gate is at DEFAULT_VERSION).
  - New audits `OperatorTrimRequested` / `OperatorTrimNoop` (WHO trimmed and WHY — the synthetic
    request cannot carry that); qty/placement/fill still ride the existing `PartialExit*` events.
- `services/audit/.../AuditEventKinds.java` — register both kinds in `ALL_KINDS`.

**Replay-safety (no `Workflow.getVersion` gate — deliberate).** Two independent arguments:
1. `partial_close` is a NEW Update: no recorded history contains an invocation, so replay never
   reaches the handler. A gate would be an inert marker (same reasoning as
   `CopytradeDeriskWorkflowImpl`'s no-gate note).
2. The `marketNow` branch is DATA-driven off a field no recorded `PartialExitRequest` carries (STC
   and de-risk-cue dispatchers both omit `market`), so replaying any existing history takes the
   identical branch and records the identical commands. Precedent: the Issue #15
   `force_close_0dte_et` no-gate reasoning.
The `pendingPartialCloses` await term is predicate-only (not a recorded command) and constant-false
on every legacy history.

**Tests (TDD, `PositionWorkflowImplTest`):**
- `partialClose_healthyPosition_sellsFractionAtMarketAndKeepsRunner` — 8 contracts, fraction 0.25 →
  `ACCEPTED`, `exit_signal_id` starts `trim:ops-1:`, ONE SELL of qty 2 with `limitPrice == null`
  (MARKET), `PartialExitRequested.qty_to_close == 2`, `OperatorTrimRequested` carries operator+reason,
  and `positionState().remainingQty() == 6` — the position is STILL OPEN (reduce-only).
- `partialCloseValidator_fullFraction_rejects` — fraction 1.0 → `WorkflowUpdateException`; the
  subsequent full drain still sells all 4, proving nothing was enqueued.
- `partialCloseValidator_blankOperatorId_rejects`.
- The six `PositionWorkflow` test doubles (`Recording…`/`LegacyPositionEmulator…` in
  AdoptionWorkflowIT/ImplTest/ImplLegacyReplayTest, CopytradeSignalWorkflowImplTest,
  WatchlistTriggerWorkflowImplTest, PositionWorkflowImplLegacyReplayTest) must implement the two new
  interface methods — adding methods to `@WorkflowInterface` breaks every implementor
  ([[feedback_cross_module_exec_ctor_and_spotless]]).

**Verify:** `mvn -q -pl services/orchestrator -am spotless:apply` then
`mvn -pl services/orchestrator -am test`. **`PositionWorkflowImplLegacyReplayTest` MUST stay green —
it is the replay gate.** `armChandelier_secondArm_isNoOp` and
`liveBtoWithStalePromotion_refusesOrder` are known timing flakes (re-run before believing them,
[[reference_ci_flakes_kubeconform_and_orchestrator]]). **Constraints:** spotless (orchestrator +
audit); **operator merge gate (real-money sell capability).**

## Phase 3 — BFF endpoint + /live Trim button (tenant-dashboard-bff + dashboard) — dark

**Goal:** a tenant-scoped, dark-gated `POST /api/positions/partial-close` and the button that drives
it.

**Changes** (anchors):
- `services/tenant-dashboard-bff/.../web/PositionsController.java` — `@PostMapping("/partial-close")`
  taking `{workflow_id, reason, fraction}`; own flag `positions.partial-close.write-enabled`
  (404 `{"error":"partial_close_disabled"}` while off); reject `fraction` outside `(0,1)` with a 400
  BEFORE any Temporal call; `update("partial_close", PartialCloseResult.class, …)`; ACCEPTED → 202
  else 200. **Extract** the tenant-prefix + `/pos/` guards and the operator-attribution into shared
  `guardWorkflowId` / `operatorId` / `requireWorkflowIdAndReason` helpers used by BOTH writes (DRY —
  the guards are security-critical and must not drift between the two endpoints).
- `services/tenant-dashboard-bff/src/main/resources/application.yml` — `positions.partial-close.
  write-enabled: ${POSITIONS_PARTIAL_CLOSE_WRITE_ENABLED:false}`.
- `dashboard/lib/bff.ts` — `trimPosition(workflowId, fraction, reason, operatorId)` → typed
  `{ok, disabled?, alreadyClosed?}`, never throwing on the expected non-2xx (the button must SHOW
  the outcome).
- `dashboard/components/TrimButton.tsx` (NEW) — client island modelled on `ForceExitButton`:
  explicit `submitting` lock (NOT `useTransition` — React 18.3.1 closes the transition scope at the
  first `await`, which would re-expose a clickable button mid-flight and allow a SECOND real-money
  sell), picker → confirm → terminal states, 5s auto-disarm + `onBlur`.
  `usablePresets(remainingQty)` mirrors `processOne`'s `min(remaining, ceil(remaining × f))`
  EXACTLY, then drops presets that resolve to 0, that resolve to the whole lot (that is a full
  close), or that duplicate another preset's qty — so a 2-lot offers only `25% · 1` and a 1-lot
  renders NO Trim button at all.
- `dashboard/app/live/page.tsx` — `TRIM_WRITE_ENABLED` flag, a `"use server"` `trimAction`
  (re-verify session → `trimPosition` → `revalidatePath("/live")`), and the actions column rendering
  Trim LEFT of Force exit, each gated by its OWN flag. Column present iff either flag is on ⇒ with
  both off /live is byte-identical to today.

**Tests:**
- `PositionsPartialCloseControllerWebMvcTest` (flag ON): 202 + `operator_id="tenant:acme"`,
  `NOOP_ALREADY_CLOSED` → 200, `WorkflowNotFoundException` → 409 `position_already_closed`, missing
  tenant → 401 fail-closed, cross-tenant → 403, non-position id → 403, fraction 1.0 / 0 / missing →
  400, blank reason → 400, `X-Operator-Id` threaded into the audit subject.
- `PositionsPartialCloseDarkLaunchTest` (flag OFF, **force-close ON**) → 404 +
  `verify(client, never()).newUntypedWorkflowStub(...)`, proving the two flags are INDEPENDENT.
- Dashboard has no test framework: `npx tsc --noEmit && npm run build`, plus a throwaway
  `app/obcheck/page.tsx` harness to eyeball idle / picker / confirm / in-flight / terminal states
  and the 1-lot and 2-lot edge rows (delete the harness before commit).

**Verify:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply test`; dashboard typecheck +
build. **Dark by default** ⇒ unreachable until the operator flips both flags.

## Ship order & gating

1. **Phase 1** (contract DTOs) — own PR, no runtime behavior change.
2. **Phase 2** (orchestrator Update) — own PR, **operator merge gate (real-money)**; replay gate is
   `PositionWorkflowImplLegacyReplayTest`.
3. **Phase 3** (BFF + dashboard, both dark) — own PR.
4. **Operator:** deploy → flip `positions.partial-close.write-enabled` + `TRIM_WRITE_ENABLED` →
   supervised first trim on a small multi-contract position.

Each phase: spotless (Java) / tsc+build (dashboard), single-concern PR, dark-launch flag so nothing
is reachable until the operator flips it.

## Known edge (accepted, not a blocker)

If a position drains to a single contract between the /live render and the click, the pre-existing
runner-quantum rule in `processOne` applies: `floor(1 × fraction) == 0` ⇒ default `SKIP` (nothing
sells, `PartialExitSkippedMinQty` audited) or a full close where a tenant has set
`min_partial_qty_behavior=full_close` ([[project_min_partial_qty_full_close]]). That is existing
configured behavior on ONE contract, not new risk. The UI already hides Trim for a rendered 1-lot.
