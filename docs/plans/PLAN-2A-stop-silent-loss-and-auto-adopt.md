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
- On the **bounded scheduled paths**, after `exec.placeOrder` do **not** zero `remainingQty`.
  `Workflow.await` on `lastFillEvent` up to a TTL (mirror `processOne` ~1133-1162) and decrement
  `remainingQty` **only from the actual fill**. On TTL timeout: reprice marketable/at-bid (within
  the expiry-day collapse, R-AA-3) or keep the workflow **alive and re-armed** — never zero and
  emit POSITION_CLOSED on mere placement.
- On a placement **failure** (placeOrder throws): keep the workflow alive/re-armed; reserve a
  *visible* `ApplicationFailure` only for a genuinely non-retryable invariant violation. Never a
  silent normal completion.
- **Redefined invariant:** `KIND_POSITION_CLOSED ⟹ broker-confirmed remaining == 0`.
- **Tests:** placed-but-unfilled bounded limit (workflow stays alive, no POSITION_CLOSED);
  partial-fill-then-rest; the broker-confirmed-zero invariant.

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
  bid from R-AA-2 (fallback mid → chandelier `lastTickPremium`/`peakPremium` → ref), placed
  marketable (at/through the bid) but bounded by `exit_floor_abs`/`exit_floor_pct`.
  - `exit_floor` **fail-safe**: null/absent/unresolvable, or a floor that sits above the live bid →
    fall back to a marketable exit (never "no sell"); emit a loud config-error audit.
  - **Expiry session** → collapse the floor to a marketable/near-zero level (`expiry_day_floor`,
    default ~$0.01 or a limit at the live bid) so the lot always sells.
- **risk_breach / force_close (immediacy)** → keep an **exit-NOW** semantic (MARKET, or an explicit
  emergency cross-the-bid band) — unchanged certainty for the kill-switch / operator path.
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
  - **Post-complete no-op:** set the child's `WorkflowIdReusePolicy = REJECT_DUPLICATE` (a completed
    adoption id is not re-run).
  - **In-flight no-op:** a recon-side **PRECHECK before `Async.function` start** — reuse
    `isPositionWorkflowRunning` (ReconciliationWorkflowImpl ~279 / AdoptionWorkflowImpl:131) PLUS an
    explicit "adoption already running" check keyed on `WorkflowIds.adoption(...)`. Skip the start if
    either is live.
  - The residual sub-second TOCTOU (child-already-started for the adoption id) is a **benign
    REFUSED/ALREADY_ADOPTING no-op** — distinct from, and NOT, the forbidden client-side
    `WorkflowExecutionAlreadyStarted` catch of the mechanism rule above.
  - Test both windows (in-flight AND post-complete collision) in a `TestWorkflowEnvironment`;
    confirm the ABANDON-child PositionWorkflow survives recon-cycle completion.
- **Over-sell gate (prevents the #357 settling-close race):** auto-adopt only when (a) a FILLED
  journal anchor exists, AND (b) **no open/pending SELL order** at the broker for that OCC
  (cross-check recon's already-fetched broker open orders ~143, matched on `option_symbol`+`side`).
  Gates (a)+(b) cover the place→settle window — a resting flatten SELL is visible as an open order,
  so a separate quiet-period sub-gate is **dropped** (`BrokerOpenOrder` carries no timestamp to
  implement it anyway; if ever needed, source it from recon audit history, not the broker order).
  Manual force-close stays authoritative.
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
  awaits a real fill (R-AA-1), **route that fill through `applyExitFill` → `PartialExitFilled`**
  (carrying `avg_fill_price`+`qty_filled`); keep `EodForceFlattened`/`ExpiryForceFlattened` as
  P&L-neutral **lifecycle markers**. Test: a flatten exit produces realized P&L **exactly once**.
- **Audit kinds:** every new kind in `AuditEventKinds.ALL_KINDS` (KindRegistryGuardTest) + correct
  lifecycle subgroup; the flatten fill rides `PARTIAL_EXIT_FILL_KINDS` (one source, no double-count).
- **ExecActivitiesFactory** `forTarget`: `startToCloseTimeout` 15s→30s and **extend the existing
  `RetryOptions`** (it already sets `maxAttempts=5` at ~71; add `initialInterval`/
  `backoffCoefficient`/`maximumInterval`). Note this stub is shared by all three exec activities, not
  just placeOrder. No version gate (plain options). *Reduces* (not removes) the dup-422 trigger;
  complements Plan-1 B1.
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
- **No over-sell:** R-AA-4's broker-open-SELL + quiet-period gate; adoption's existing guards.

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
4. An orphaned **filled** position is auto-adopted within one recon cycle via an ABANDON child,
   idempotent across cycles, and never spawned against an open SELL / settling close.
5. Long-lived PositionWorkflow changes version-gated (v=0 byte-identical); recon convention-only.
6. New config carried by in-code defaults; pod effective-config verified.

## Spotless / CI
`mvn -pl services/orchestrator,services/exec spotless:apply` pre-commit; new `KIND_*` in ALL_KINDS.
