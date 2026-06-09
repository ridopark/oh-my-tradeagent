# Plan-2A — Stop silent loss, bound scheduled exits, auto-adopt orphans

**Split from Plan-2 after ultra-review (needs_revision).** 2A delivers the two hardest *safety*
wins — never silently complete a live lot, and never leave an orphan unmanaged — plus bounded
(non-market) scheduled exits anchored on a real bid. Fill-rate/repricing + the multi-day flatten
timer move to **Plan-2B**. **Prerequisite: Plan-1 (#361) merged** (R-AA-3 reuses its
`PartialExitPlaceFailed` + filled-orphan loud-path allowlist).

**Resolved decisions (from the review):**
- The willing-to-pay **cap is a BUY-side control**. Sells are bounded only on *normal* days and
  **MUST clear before expiry** — on the expiry session the floor collapses to a marketable/near-zero
  level so a decaying long option is sold for *something*, never ridden to $0.
- **risk_breach and force_close keep exit-NOW immediacy** (market, or an explicit emergency
  cross-the-bid band) — they are NOT folded into the rest-to-floor discipline.
- A live-bid **quote-snapshot activity** is added here (cheap wrapper over existing
  `snapshotQuote`) so bounded sells have a real anchor — without it "bounded limit" is hollow.
- `exit_floor` is **fail-SAFE**: null/absent/unresolvable → marketable fallback, never "no sell".

## Verified current-state (review-confirmed)
- `PositionWorkflowImpl.flattenIntent` (~1330) sets `limitPrice=null` → MARKET, for ALL FIVE
  `flattenRemaining` callers: eod/expiry (~563), risk_breach (~775), force_close (~781),
  chandelier (~881).
- `flattenRemaining` catch (~1235-1243) emits `EodForceFlattenFailed` **without** zeroing
  `remainingQty`; control falls through to the unconditional `KIND_POSITION_CLOSED` (~575-580) and
  `run()` returns (~582) → **silently completes with a live unmanaged lot** (the residual QQQ-725
  loss mode).
- `ReconciliationWorkflowImpl` is detect-only; it already fetches broker open orders (~143, today
  used only for JournalOrphan/BrokerOrphan by client_order_id). Recon executions are short-lived
  (`{{.ScheduledRunID}}` workflow id) → version gates there are vacuous (convention-only).
- `AdoptionWorkflowImpl` exists, idempotent (phantom guard #239, `ALREADY_OWNED` via
  `isPositionWorkflowRunning`, canonical `WorkflowIds.adoption`), starts a PositionWorkflow as a
  child with `ParentClosePolicy.ABANDON` (~152-161); `buildInput` (~229-261) sets
  eod_force_flatten/TTLs/broker_target/min_partial_qty_behavior but **NOT** `force_close_0dte_et`
  (pre-existing omission).
- `ExternalWorkflowStub` has **no `start()`** (verified via javap on temporal-sdk-1.27.0) — only
  signal/cancel. `WorkflowExecutionAlreadyStarted` is a client-side exception, never raised to
  workflow code.
- Market-data `snapshotQuote`/`Quote(bid,mid,ask)` exists but is **not** wired as an
  `@ActivityInterface` to the workflow; `PremiumTick` is mid-only and only set when
  `trailingArmed` (`processTick` early-returns at ~786), so `lastTickPremium`/`peakPremium` are
  null for any no-trail lot.

## R-AA-1 — No silent-complete: zero `remainingQty` from the FILL, not from placement (FIRST)
**The single most serious defect the re-review caught.** Today `flattenRemaining` sets
`remainingQty=0` at ~1293 on `placeOrder` *success* (no fill-await) and `run()` then emits
`KIND_POSITION_CLOSED` unconditionally (~609). This is benign ONLY because `flattenIntent` is
MARKET — but R-AA-3 makes it a bounded LIMIT that can rest **unfilled**, re-opening the QQQ-725
silent-loss class via the **TRY** branch (an accepted-but-unfilled limit), which a catch-only guard
never covers.
Fix (version-gated, long-lived workflow):
- **Remove the wholesale `remainingQty=0` at ~1293.** After `exec.placeOrder` on the bounded paths,
  `Workflow.await` on `lastFillEvent` up to a TTL (mirror `processOne` ~1133-1162) and decrement
  `remainingQty` **only from the actual fill**. On TTL timeout: reprice marketable/at-bid (R-AA-3
  expiry collapse) or keep the workflow **alive and re-armed** — never zero on mere placement.
- **Restructure the run()-tail / EOD-expiry epilogue (~595-613) into a guarded loop**, not
  straight-line flatten-once → unconditional `KIND_POSITION_CLOSED`: gate POSITION_CLOSED on
  `remainingQty==0`; on a bounded-flatten TTL timeout **re-arm/re-place** rather than `return`;
  the ONLY terminal conditions that let `run()` return are broker-confirmed zero OR a *visible*
  non-retryable `ApplicationFailure`. **All five flatten callers** (the three in-loop `continue`
  paths + the two post-loop eod/expiry) share the "zeroed only on fill" contract, so the in-loop
  paths still reach a **conditional** POSITION_CLOSED.
- **Redefined invariant:** `KIND_POSITION_CLOSED ⟹ broker-confirmed remaining == 0`.
- **Tests (incl. the ~595 epilogue path specifically, not just processOne):** placed-but-unfilled
  bounded limit → workflow stays alive, no POSITION_CLOSED; partial-fill-then-rest; the
  broker-confirmed-zero invariant.

## R-AA-2 — Quote-snapshot activity (anchor for bounded sells)
The market-data `Quote(bid,mid,ask)` record lives OUTSIDE the contract module and is not a shareable
activity type across the polyglot boundary — so follow the `SubscribePremiumActivity` precedent:
- Define `GetOptionQuoteActivity` as a **contract-module `@ActivityInterface`** + a JSON-schema
  result DTO (`contract/schemas/option-quote-result.json` → generated DTO with
  `bid`/`mid`/`ask`/`retrieved_at`).
- The market-data worker implements it, mapping its internal `Quote` record into the contract DTO;
  register on the **market-data task queue** (same one `SubscribePremiumActivity` uses) with a
  short start-to-close.
- Pure read; version-gate its first call site (a command in the long-lived workflow).

## R-AA-3 — Bounded, reason-scoped flatten (stop market dumps on scheduled paths)
`flattenIntent(intentKey, reason)` is **already** parameterized by reason (~1387); branch on the
existing `reason` arg — **do not** blanket-convert all five callers. Route by **classification**,
not an enumerated list, so 2B's `expiry_lead` reason is handled without editing 2A-owned switch
code: **any reason ∉ {`risk_breach`, `force_close`} is bounded**; those two are immediacy.
- **bounded (eod / expiry / chandelier / future `expiry_lead`)** → a **bounded marketable LIMIT**,
  placed then **fill-awaited per R-AA-1** (remainingQty zeroed only on the broker fill): anchor on
  the live
  bid from R-AA-2 (anchor chain: **live bid → mid → chandelier `lastTickPremium`/`peakPremium` →
  ref → expiry-session marketable**), placed marketable (at/through the bid) but bounded by
  `exit_floor_abs`/`exit_floor_pct`.
  - `exit_floor` **fail-safe**: null/absent/unresolvable, or a floor above the live bid → fall back
    to a marketable exit (never "no sell"); loud config-error audit.
  - **Quote-activity FAILURE (not just null)** on a scheduled/expiry path → fall back to
    **marketable**, NOT to a stale ref-premium limit; emit the loud availability audit. Give
    `GetOptionQuoteActivity` an explicit start-to-close + bounded retry so it can't wedge the
    flatten.
  - **Expiry session** → `expiry_day_floor` is strictly a price FLOOR applied **only when a live
    bid exists**; when `bid <= 0` go **fully marketable**. A contract with **no live bid expires
    worthless regardless** — that is out of scope of the sell guarantee (do NOT rest a $0.01 limit
    that never fills). Reword: "a bounded marketable sell is *placed at/through the live bid* before
    expiry."
- **risk_breach / force_close (immediacy)** → keep an **exit-NOW** semantic (MARKET, or an explicit
  emergency cross-the-bid band) — unchanged certainty for the kill-switch / operator path. (Code
  literals: `eod`/`expiry`/`chandelier_trail`/`risk_breach`/`force_close` — note `chandelier_trail`,
  not `chandelier`; route by the negative set `∉ {risk_breach, force_close}`.)
- Version-gated; **per-reason `limitPrice` assertions** in tests (success criterion does NOT compel
  `limitPrice != null` on the immediacy paths).

## R-AA-4 — Recon auto-adopts orphaned filled positions (mechanism fixed)
In `ReconciliationWorkflowImpl`, when a `PositionOrphan(journal_status='filled')` is detected, start
`AdoptionWorkflow` as an **ABANDON child** (`Workflow.newChildWorkflowStub` +
`ChildWorkflowOptions.setWorkflowId(WorkflowIds.adoption(...))` +
`ParentClosePolicy.ABANDON`, mirror AdoptionWorkflowImpl ~152-161; launch via `Async.function` so
recon doesn't block). **Do NOT** use `ExternalWorkflowStub.start()` and **do NOT** catch
`WorkflowExecutionAlreadyStarted`.
- **Idempotency (no nonexistent API):** `ChildWorkflowOptions` in temporal-sdk-1.27.0 has
  `setWorkflowIdReusePolicy` + `setParentClosePolicy` but **no conflict-policy setter** (that's
  client-side `WorkflowOptions` only), and no reuse policy makes a start against a *currently-RUNNING*
  id a silent no-op — it throws. So:
  - **Use `WorkflowIdReusePolicy = ALLOW_DUPLICATE`** and rely SOLELY on the two-window precheck
    below. (NOT `REJECT_DUPLICATE`: `WorkflowIds.adoption` keys on `(tenant,strategy,occ)` ONLY
    [WorkflowIds.java:46-48], so REJECT_DUPLICATE would *permanently* block re-adopting an OCC that
    is adopted → managed → later re-orphaned with the lot still held. ALLOW_DUPLICATE matches the
    code's own `ALREADY_OWNED` intent and the precheck already covers churn.)
  - **In-flight + post-complete no-op = the recon-side PRECHECK before `Async.function` start:**
    skip if `isPositionWorkflowRunning(posWfId)` (Recon ~279 / AdoptionWorkflowImpl:131) OR the
    adoption id keyed on `WorkflowIds.adoption(...)` is already running.
  - The residual sub-second TOCTOU child-already-started is a **benign no-op**; **swallow the failed
    child-start Promise** (do NOT propagate it to recon `run()`) — distinct from the forbidden
    client-side `WorkflowExecutionAlreadyStarted` catch. Test that the swallow holds.
  - Test both windows (in-flight AND post-complete) in a `TestWorkflowEnvironment`; confirm the
    ABANDON-child PositionWorkflow survives recon-cycle completion.
- **Over-sell gate — `R-AA-1` is the real #357 defense; gate (b) is a backstop:** auto-adopt only
  when (a) a FILLED journal anchor exists, AND (b) **no open/pending SELL order** at the broker for
  that OCC (recon's broker-open-orders ~143, matched on `side` + OCC **normalized via
  `OccSymbol.compact()` on BOTH sides** — padded-vs-compact mismatch would defeat the gate, cf. the
  %20-padding bug in 16e4c6e). **Caveat:** `AlpacaPaperBroker` does NOT override `listOpenOrders()`
  today (only the `List.of()` default at OptionsBroker.java:62), so gate (b) is **currently inert on
  Alpaca**. The real settling-close protection is **R-AA-1**: the workflow now stays *running* until
  a broker-confirmed fill, so `isPositionWorkflowRunning` stays true across the place→settle window
  and no filled-orphan is emitted to adopt against. Track implementing
  `AlpacaPaperBroker.listOpenOrders()` (GET `/v2/orders?status=open` → `BrokerOpenOrder` incl.
  `side`) as a named **fast-follow** so gate (b) becomes a real broker-independent backstop. Manual
  force-close stays authoritative.
- recon-side version gating is **convention-only** (short-lived scheduled executions).
- `journal_status='missing'` (no anchor) is **not** auto-adopted → stays a loud page (Plan-1 B3).

## R-AA-5 — Config plumbing at BOTH sites + deploy-safety
New fields: `exit_floor_abs`, `exit_floor_pct`, `expiry_day_floor`, plus the immediacy
`emergency_cross_band` (if used). Plumb yaml → `contract/schemas/strategy-config.json` →
regenerated `StrategyConfig` → **both** `CopytradeSignalWorkflowImpl` (~450-480) **and**
`AdoptionWorkflowImpl.buildInput` (~229-261) → `position-workflow-input.json` → PositionWorkflow.
**While in buildInput, fix the pre-existing `force_close_0dte_et` omission.** Ship safe in-code
defaults (a stale `40-tenants-config` ConfigMap — which `deploy.yml` does NOT apply,
[[reference_deploy_yml_apply_scope]] — must not break the guarantee). Add a pod effective-config
verification step (mirror Plan-1 B3). Integration test: an **auto-adopted** position arms the
flatten machinery with non-null config.

## R-AA-6 — Realized-P&L + hardening
- **Flatten fill MUST enter realized P&L (kill-switch correctness).** `DailyPnlActivitiesImpl`
  reads exits ONLY from `PartialExitFilled` (`qty_filled` + `avg_fill_price`, ~99-100); today
  `flattenRemaining` emits `qty_flattened` with no fill price → **force-flatten exits contribute
  ZERO realized P&L**, so the daily-loss kill-switch silently **under-counts** losses on exactly the
  eod/expiry/chandelier paths that close losing lots. Decision (single P&L source): once the flatten
  awaits a real fill (R-AA-1), **route that fill through `PartialExitFilled`**; keep
  `EodForceFlattened`/`ExpiryForceFlattened` as P&L-neutral **lifecycle markers**.
  - Seam: `applyExitFill` reads `req.getSignalId()` from a `PartialExitRequest` the flatten path
    lacks → **extract a shared fill-applier `(qty_filled, avg_fill_price, option_symbol, signal_id)`**
    used by both partial-exit and flatten (or synthesize a `flatten-<reason>` signal_id). The emitted
    `PartialExitFilled` MUST carry `avg_fill_price` + `qty_filled` + `option_symbol` (under the
    existing option-symbol version gate) so DailyPnl FIFO grouping (DailyPnlActivitiesImpl ~171-179)
    matches the entry basis.
  - The audit-completeness verifier must **tolerate a flatten-origin `PartialExitFilled` with no
    preceding `PartialExitRequested`** (no MissingTerminalClose / double-terminal regression).
  - Any `applyExitFill` signature change is replayed code → **version-gated**. Test: a flatten exit
    produces realized P&L **exactly once**.
- **Audit kinds:** every new kind in `AuditEventKinds.ALL_KINDS` (KindRegistryGuardTest) + correct
  lifecycle subgroup; the flatten fill rides `PARTIAL_EXIT_FILL_KINDS` (one source, no double-count).
- **ExecActivitiesFactory** `forTarget`: **extend the existing `RetryOptions`** (already
  `maxAttempts=5` at ~71; add `initialInterval`/`backoffCoefficient`/`maximumInterval`). This stub is
  **shared by all three exec activities** (placeOrder + cancelOrder + reads) — do NOT blanket-raise
  start-to-close to 30s (it would slow the cancel/read paths the over-sell gate relies on near
  expiry): either keep cancel/reads snappy with modest backoff only, or split the stub so only
  placeOrder gets the longer timeout. No version gate (plain options). *Reduces* (not removes) the
  dup-422 trigger; complements Plan-1 B1.
- **Determinism — marker placement:** new `Workflow.getVersion` markers append at the END of any
  pre-existing recorded command sequence for a decision point, never interleaved. Extend
  `PositionWorkflowImplLegacyReplayTest` (mirror the `VERSION_EXIT_RETRY_LATE_FILL_RECONCILE`
  constant-name guard ~106-119) with a v=0 byte-identical replay assertion per new gate. (Temporal
  1.27 replay ignores activity-input payload divergence — the guard is specifically about marker
  placement/ordering.)
- **Metrics:** `recon.auto_adopt.{initiated,refused_not_held,already_owned}`,
  `position.flatten.{limit_filled,unfilled_at_floor_marketable_fallback}` — a benign
  `REFUSED_NOT_HELD` (just-closed lot racing adopt) is NOT an alert.

## Constraints / invariants
- **No unbounded market order on the SCHEDULED paths** (eod/expiry/chandelier); risk_breach &
  force_close intentionally retain exit-NOW. Every scheduled sell clears before expiry (expiry-day
  floor collapse) — the guarantee is "sells", not "pages".
- **`POSITION_CLOSED ⟹ broker-confirmed remaining == 0`** — `remainingQty` is zeroed only by an
  actual fill, never by placement (R-AA-1).
- **Determinism:** version-gate the long-lived PositionWorkflow changes (R-AA-1, R-AA-3, R-AA-2's
  call site); recon changes are convention-only. No `Instant.now`/`UUID`/`Math.random`.
- **No over-sell:** primarily **R-AA-1** (workflow stays running until broker-confirmed fill →
  `isPositionWorkflowRunning` true across place→settle, no filled-orphan to adopt against); gate (b)
  is a backstop, currently inert on Alpaca until `listOpenOrders()` lands; adoption's existing guards.

## Tests (TDD)
- R-AA-1: `POSITION_CLOSED` invariant test (flatten throws → workflow stays alive/visibly fails,
  never emits POSITION_CLOSED with remaining>0).
- R-AA-3: per-reason `flattenIntent` — eod/expiry/chandelier emit a bounded limit (assert
  `limitPrice != null`, ≥ floor, ≤ live bid path); risk_breach/force_close keep exit-NOW; expiry-day
  collapses to marketable; null/unresolvable floor → marketable fallback + config-error audit.
- R-AA-4: `PositionOrphan(filled)` → ABANDON child adoption started once; re-issue every cycle
  (in-flight AND post-complete) is a no-op; open-SELL/quiet-period gate blocks the settling-close
  race; `missing` orphan → page only. v=0 recon replay unaffected.
- R-AA-5: auto-adopted position arms flatten with non-null config; `force_close_0dte_et` now set in
  buildInput.
- R-AA-2: quote activity returns bid/mid/ask; flatten anchors on it.

## Success criteria
1. `mvn -pl services/orchestrator,services/exec,services/audit -am test spotless:check` → SUCCESS.
2. No code path emits `POSITION_CLOSED` unless **broker-confirmed `remaining == 0`** (test:
   placed-but-unfilled bounded limit does NOT close).
3. Scheduled flatten (eod/expiry/chandelier) places a **bounded limit** and zeroes `remainingQty`
   only on the fill; risk_breach/force_close keep exit-NOW; a **same-day / expiry-session** lot
   sells before/at expiry (expiry-day collapse) — no ride-to-$0. (Multi-day lots that have no
   trigger today are covered by **2B's** flatten timer; 2A does not claim them.)
4. An orphaned **filled** position is **re-attached to a managing workflow** within one recon cycle
   via an ABANDON child (and, if expiry-day, sold via the bounded flatten), idempotent across cycles,
   never spawned against a settling close. (Multi-day adopted lots get their sell *deadline* only
   once 2B's flatten timer ships — see the rollout note.)
5. Long-lived PositionWorkflow changes version-gated (v=0 byte-identical); recon convention-only.
6. New config carried by in-code defaults; pod effective-config verified.

## Rollout / residual risk
Until **2B** ships, a **multi-day** lot whose STC never fills relies on operator force-close / the
Plan-1 B3 `PositionOrphan(filled)` page as the only sell backstop (2A bounds/guarantees the
same-day & expiry-session paths and re-attaches orphans, but the multi-day *timer* is 2B). Treat 2B
as the **immediate follow-on, not optional**. Named fast-follow inside/after 2A:
`AlpacaPaperBroker.listOpenOrders()` so over-sell gate (b) becomes a real broker-independent backstop.

## Spotless / CI
`mvn -pl services/orchestrator,services/exec spotless:apply` pre-commit; new `KIND_*` in ALL_KINDS.
