# Plan-2 — Automatic recovery + bounded fill/sell guarantee

**Goal (user requirement):** every BTO/STC is handled **automatically**; nothing silently goes
bad; every filled position is eventually **sold** — using **bounded repricing LIMITS, never an
unbounded market order** (the existing willing-to-pay cap is a hard risk control).

**Relationship to Plan-1** (`PLAN-exit-place-duplicate-422-crash.md`): Plan-1 (B1–B3–A) *prevents*
the crash and makes failures *visible*. Plan-2 makes recovery *automatic* and guarantees fills/
sells. Ship Plan-1 first; Plan-2 builds on it. Decisions #1/#2/#3 from the Plan-1 audit are
resolved: (1) **auto** recovery, (2) **yes** sell-by-expiry backstop, (3) **no market orders** —
bound everything by the willing-to-pay/accept cap.

## Verified current-state facts (the gaps)
- **Flatten is a MARKET order today.** `PositionWorkflowImpl.flattenIntent` (~1317-1333) sets
  `limitPrice = null` (line ~1330) → `flattenRemaining` (~1169-1245) places MKT on EOD / expiry /
  risk-breach / force-close / chandelier. Violates "no market orders".
- **Multi-day positions have NO force-close before expiry.** `eod_force_flatten: false` (verified)
  + `VERSION_EOD_FLATTEN_OPT_IN` (fail-closed) ⇒ the EOD timer is not armed; only the 0DTE
  expiry-close timer (`force_close_0dte_et: "14:45"`) ever fires. A multi-day option whose STC
  never fills rides to expiry (the QQQ-725 loss).
- **Reconciliation is detect-only.** `ReconciliationWorkflowImpl` emits `PositionOrphan` /
  `JournalOrphan` and stops; recovery (`AdoptionWorkflow` via `POST /positions/adopt`, force-close)
  is manual. Recon runs every **5 min** (`ReconciliationScheduleBootstrapper.INTERVAL`).
- **AdoptionWorkflow already exists and is idempotent** (`AdoptionWorkflowImpl`): phantom guard
  (`brokerGetPositionByOcc`, #239), `ALREADY_OWNED` via `isPositionWorkflowRunning`, canonical
  `WorkflowIds.adoption(tenant,strategy,occ)`, reconstructs `PositionWorkflowInput` from broker
  truth + journal anchor, forwards `onFill`, emits `PositionAdopted`.
- **Exit pricing has no bid/ask.** `exitIntent` (~1293-1315) uses `req.getRefPremium()` (author
  price) → `lastTickPremium` → `peakPremium`; `PremiumTick` carries **mid only**. `Quote` (bid/mid/
  ask) exists in market-data-svc but there is **no on-demand snapshot activity** exposed to the
  workflow. Exit retry today is **one** attempt (`VERSION_EXIT_RETRY_ON_TIMEOUT`).
- **Entry cap exists** = the willing-to-pay control: `BtoPricing.computeBtoLimit` (~68-100),
  `max_slippage_abs`/`max_slippage_pct` (SLIP_MIN = tighter of the two), MIRROR when both null.
  copytrade-v1 currently sets **neither** ⇒ entry limit = exact signal price.

## The design (5 parts)

### R1 — Recon auto-adopts orphaned filled positions (C1a)
In `ReconciliationWorkflowImpl`, behind `VERSION_RECON_AUTO_ADOPT`, when a
`PositionOrphan(journal_status='filled')` is detected (the `recentFilled != null` branch ~280),
auto-start the existing `AdoptionWorkflow` via
`Workflow.newUntypedExternalWorkflowStub(WorkflowIds.adoption(...)).start(...)` (fire-and-forget;
catch `ApplicationFailure`/`WorkflowExecutionAlreadyStarted` as benign). Idempotency is already
guaranteed by adoption's canonical id + phantom guard + `ALREADY_OWNED` — safe to re-attempt every
5-min cycle. Emit `ReconAutoAdoptionInitiated` audit. The existing `PositionOrphan` audit stays
(observability). Re-establishes management within one recon cycle, no human.
- `journal_status='missing'` (no FILLED anchor) cannot be auto-adopted (no qty/signal anchor) →
  leave as a loud page (Plan-1 B3) for operator. Document this boundary.

### R2 — Auto-re-drive a dropped/unfilled exit (C1b, best-effort)
When recon finds a stale exit `JournalOrphan` (intent_key contains `:exit`) whose PositionWorkflow
is running, re-send the `partialExit` signal (reconstructed `PartialExitRequest` from the journal
row) via external stub, behind `VERSION_RECON_AUTO_EXIT_REDRIVE`. **This is best-effort speed, NOT
the guarantee** — R3 is the hard guarantee, so R2 may be deferred if reconstruction proves fiddly.
Emit `ReconAutoExitRedriven`.

### R3 — Guaranteed bounded force-flatten before expiry, for ALL lots (C2) — **the hard guarantee**
1. **Arm a flatten timer for every position, not just 0DTE.** Add `flatten_lead_minutes` (e.g. 30)
   config; arm a timer at `expiry_close - lead` for multi-day too (independent of
   `eod_force_flatten`, which stays the operator's separate EOD-vs-expiry choice). Gate
   `VERSION_GUARANTEED_FLATTEN`.
2. **Change `flattenIntent` from MARKET to a BOUNDED repricing LIMIT.** No more `limitPrice=null`.
   Source the limit from `lastTickPremium` (mid) when available, else `peakPremium`, else
   `refPremium`; place at/just-through that price, then **reprice down in bounded steps** toward a
   configured **floor** (`exit_floor_abs`/`exit_floor_pct` — the sell-side analogue of the entry
   willing-to-pay cap). Reuse the `#216` retry/reprice loop machinery + `:retry`/`:reprice-N`
   intent-key suffix + `OptionTick.round` (2dp).
3. **Never market-dump.** If the lot is **unfilled at the floor** by the deadline, do **NOT** send
   a market order — emit a **loud `filled`-orphan page** (Plan-1 B3 loud path) and hold for the
   operator. (See open decision O1: expiry-day may warrant a deeper/zero floor so a decaying option
   doesn't expire worthless — configurable.)

### R4 — Bounded repricing exits on the normal path (C3)
Replace the single exit retry with a stepped reprice (down toward the market, bounded by the
`exit_floor_*` cap), behind `VERSION_EXIT_REPRICING`. Add `exit_reprice_steps` /
`exit_reprice_tick` config. Emit `PartialExitRepriced` per step. For **entries**, set
`max_slippage_abs`/`max_slippage_pct` in copytrade-v1 (currently absent → exact-mirror non-fills)
and optionally reprice up to the cap, then accept the miss (the author's price cap is the bound —
no chase beyond it). Net: higher fill rate on both sides, strictly within the willing-to-pay/accept
caps.

### R5 — Resilience hardening (C4 + C6, folded from the Plan-1 audit)
- **Top-level error boundary** in `PositionWorkflowImpl` main loop (~515-560): catch, emit
  `KIND_POSITION_LOOP_ERROR`, and fail *visibly* (or recover) rather than dying silently; wrap the
  other uncaught activity calls (`marketData.subscribePremium` ~844). Gate it.
- **Flatten failure must never silent-complete with `remainingQty>0`** — the current `catch` emits
  `EodForceFlattenFailed` then completes; instead keep the lot managed + page (ties to R3/B3).
- **Bump `ExecActivitiesFactory` `startToCloseTimeout` 15s → ~30s + add backoff** — the 15s cap is
  a root *trigger* of the dup-422 (slow Alpaca POST → kill → retry → duplicate). Cheap, complements
  Plan-1 B1.

## New config (strategy-config.json → StrategyConfig DTO → PositionWorkflowInput)
`flatten_lead_minutes`, `exit_floor_abs`, `exit_floor_pct`, `exit_reprice_steps`,
`exit_reprice_tick`; plus set `max_slippage_abs`/`max_slippage_pct` for entries. Plumb yaml →
`contract/schemas/strategy-config.json` → regenerated `StrategyConfig` → mapped in
`CopytradeSignalWorkflowImpl` (~450-480) → `position-workflow-input.json` → `PositionWorkflowImpl`.
**Remember:** `40-tenants-config.yaml` is NOT applied by `deploy.yml`
([[reference_deploy_yml_apply_scope]]) — ship defaults in code + apply the ConfigMap manually.

## Constraints / invariants
- **No unbounded market orders anywhere** (R3 #3). Every order is a limit bounded by a configured
  cap/floor; the residual (unfilled at floor) is a loud page, not a dump.
- **Determinism:** every workflow change (R1, R2, R3, R4, R5) is replayed code → each behind its
  own `Workflow.getVersion` gate (convention per `VERSION_DEFER_POSITION_ENTERED`). Recon gates are
  new (it has none today). No `Instant.now`/`UUID`/`Math.random`.
- **Idempotency:** auto-adopt relies on adoption's existing guards; auto-re-drive relies on
  `partialExit` signal dedup (`processedSignalIds`) + the exit intent-key.
- **R1+R3 together are the guarantee:** R1 ensures nothing stays unmanaged; R3 ensures every
  managed lot is sold (within the floor) before expiry. R2/R4 improve speed/fill-rate but are not
  load-bearing for the guarantee.

## Tests (TDD)
- **R1** `ReconciliationWorkflowImplTest`: a `PositionOrphan(filled)` → external AdoptionWorkflow
  start invoked once; repeated cycles are idempotent (no duplicate); `missing` orphan → no
  auto-adopt (page only). v=0 replay unchanged.
- **R3** `PositionWorkflowImplTest`: (a) multi-day lot now arms a flatten timer and sells via a
  **limit** (assert `limitPrice != null`); (b) reprice walks down to the floor; (c) unfilled at
  floor → loud page, **no market order placed**; (d) `flattenIntent` never emits `limitPrice=null`
  under v≥1. Legacy v=0 replay = MARKET (unchanged).
- **R4** `PositionWorkflowImplTest`: exit repricing steps within the floor; `BtoPricingTest` for the
  entry cap with slippage set.
- **R5** main-loop error boundary test (inject a throwing activity → workflow fails visibly / emits
  `KIND_POSITION_LOOP_ERROR`, not silent); flatten-failure-keeps-managed test.
- New audit kinds (`ReconAutoAdoptionInitiated`, `ReconAutoExitRedriven`, `PartialExitRepriced`,
  `KIND_POSITION_LOOP_ERROR`, any flatten-bounded kind) registered in `AuditEventKinds.ALL_KINDS`
  (else `KindRegistryGuardTest` fails the pre-push).

## Success criteria
1. `mvn -pl services/orchestrator -am test` and `-pl services/exec -am test` → BUILD SUCCESS.
2. An orphaned **filled** position is auto-adopted within one recon cycle (test) — no operator.
3. Every managed lot has a flatten timer and sells via a **bounded limit** before expiry; **no code
   path emits a market order** (assert `limitPrice != null` on all flatten/exit intents under v≥1).
4. Unfilled-at-floor → loud page, position held (no market dump) — verified.
5. Entries fill at higher rate within the `max_slippage` cap; exits reprice within the `exit_floor`
   cap.
6. Every workflow change behind its own version gate; v=0 replays byte-identical.

## Open decisions (need a human call)
- **O1 — expiry-day floor.** A strict floor can leave a decaying option to expire worthless
  (better than a market dump? maybe not, for a near-zero option). Allow an **aggressive
  expiry-day floor** (e.g. floor→$0.01 on the actual expiry session) so it sells for *something*,
  while keeping a tighter floor on normal days? Recommended: yes, a separate `expiry_day_floor`.
- **O2 — bid for repricing.** Reprice off `lastTickPremium` (mid, already in-workflow, requires the
  chandelier subscription to be armed) vs add a new **on-demand quote-snapshot activity**
  (`Quote.bid`) for a true bid-chase. Recommended: start with mid-stepping (no new activity), add
  the snapshot activity only if fill rates are insufficient.
- **O3 — R2 in scope now or later?** R3 is the hard guarantee, so R2 (fast exact-STC re-drive) can
  be a fast-follow. Recommended: ship R1+R3+R4+R5 first; R2 next.
- **O4 — sequence.** Recommended PR order: R5(hardening) + the flatten-limit half of R3 first
  (stops market dumps immediately), then R1(auto-adopt), then R4(repricing), then R3 timer-arm for
  multi-day, then R2.

## Spotless / CI
`mvn -pl services/orchestrator -pl services/exec spotless:apply` pre-commit. New `KIND_*` constants
must be in `AuditEventKinds.ALL_KINDS` (KindRegistryGuardTest / pre-push guard).
