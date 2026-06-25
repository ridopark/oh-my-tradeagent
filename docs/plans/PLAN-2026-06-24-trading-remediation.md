# PLAN — 2026-06-24 Trading-day remediation

Remediates four distinct failures observed on the 2026-06-24 trading day (forensics from
`order_intent_journal` on `exec_alpaca_paper`/`exec_alpaca_live` and `orchestrator.audit_log`).
Each phase is **independent** and ships as its **own PR** (single-concern, matching repo norm).
All trading-critical paths gate on homelab QA + operator merge approval.

## Incident summary (what went wrong)

1. **Orphaned, un-flattenable position (root cause).** `staging_paper` / `watchlist-trigger-v1`
   opened 5× `QQQ 6/26 723C` @ 2.45 at **15:33 ET — 3 min AFTER its own `force_close_eod_et: "15:30"`**.
   Stop-loss tripped at 16:00:03 ET (the bell); both the stop_loss and a follow-on time_stop
   force-flatten attempts (`EodForceFlattenRequested` → `EodForceFlattenFailed`) submitted MARKET
   sells at/after the 16:00 close → 0 fill, canceled. Workflow stays alive holding 5 contracts
   overnight, with **no alert**.
2. **Live account silently burning retries.** `prod_real` (live) orders sit in `RECORDED` with
   `last_error: 403 ... {"code":40310000,"message":"new orders are rejected by user request"}`.
   The block is **intentional** (operator halt at Alpaca). But the code does not classify
   40310000, so Temporal retries it 6× per order, the intent never reaches a terminal state, and
   the alerter fires once per attempt.
3. **Recon false-orphans.** From 15:35–19:55 ET the reconciler repeatedly paged
   `PositionOrphan`/`PositionOrphanOngoing` for the live-owned `QQQ260626C00723000` under BOTH
   `watchlist-trigger-v1` and `copytrade-v1` recon schedules (`journal_status:"missing"`,
   `expected_workflow_id:null`).
4. **(P0 ops)** The orphaned paper position is still open; the two live `RECORDED` orders will sit.

> Correction from investigation: the recon issue is **not** a padded-vs-compact OCC mismatch
> (that path is correct and tested — `JooqOrderIntentJournal.findLatestFilledByOcc:147-157`,
> `OccSymbol.compact/padded`). The real surface is the reconciler's `missing` branch.

---

## P0 — Immediate operational (no code; operator)

- **Orphaned paper position:** `staging_paper` `QQQ 6/26 723C` ×5 is open on the Alpaca **paper**
  account. Low stakes (paper, expires 6/26). Recommend manually closing at next RTH open to clear
  the orphan and silence recon, OR let it expire. If terminating workflows, use the
  terminate-PWs → trigger-recon-schedule recipe (see `project_recon_double_adoption_fix` memory).
- **prod_real (intentional halt):** the two `RECORDED` orders (`QQQ 6/29 730C`, `NVDA 7/6 190P`)
  will remain until Phase 2 ships, after which 40310000 rejects become terminal. No action needed
  beyond awareness; the Alpaca block stays as-is.

---

## Phase 1 — EOD entry cutoff for watchlist-trigger (root cause)

**Goal:** never open a watchlist position within N minutes of the strategy's close/flatten time.

**Changes**
- `contract/schemas/strategy-config.json`: add optional integer `no_entry_within_close_minutes`
  (`"type":"integer","minimum":0`, NOT in `required` → generated getter returns `null` = disabled).
  Mirror the existing `no_progress_time_stop_secs` (schema L366) / `trail_disarm_minutes_before_close`
  (L181) declaration pattern. Rebuild regenerates `StrategyConfig` POJO
  (`contract/java/target/.../StrategyConfig.java`).
- `services/orchestrator/.../workflows/WatchlistTriggerWorkflowImpl.java`: in `fire(...)` (starts
  L472), insert a new fail-closed guard at the **top (after L476, before account/quote/risk
  activities)**, gated behind a new `Workflow.getVersion("watchlist-eod-entry-guard-v1", ...)`
  change id (mirror `VERSION_WATCHLIST_EXIT`). Logic:
  - resolve cutoff time = `force_close_eod_et` if set else `MARKET_CLOSE_TIME` (16:00);
  - `Duration toClose = calendar.durationUntilEodCloseEt(cutoff)` (stub already wired, used at
    L371; impl `MarketCalendarActivitiesImpl.durationUntilEodCloseEt:33`);
  - if `toClose.isZero()/isNegative()` OR `toClose.toMinutes() < no_entry_within_close_minutes` →
    `logAudit(KIND_TRIGGER_FIRE_REJECTED, reason="too_close_to_eod")` and
    `return outcome(payload, "eod_skip")` (mirror the `capital_unavailable`/`sizing_skip` branches).
  - `null` config → guard disabled (behaviour unchanged).
- Tenant YAMLs: set `no_entry_within_close_minutes: 30` in
  `tenants/dev/strategies/watchlist-trigger-v1.yaml` and the live tenant equivalents
  (`staging_paper`, `prod_real`). NOTE: tenant ConfigMap is **not** applied by a deploy — needs a
  manual `kubectl apply` of `40-tenants-config.yaml` (see `reference_deploy_yml_apply_scope` memory).

**Verify / success criteria**
- New `WatchlistTriggerWorkflowImplTest` case: with `force_close_eod_et=15:30` (or threshold 30) and
  simulated "now" inside the cutoff, `fire()` returns `eod_skip`, emits `TriggerFireRejected
  (reason=too_close_to_eod)`, and **`exec.placeOrder` is never called**.
- Null config → existing tests still pass (guard inert).
- Reproduces the incident: a 15:33 ET fire with `force_close_eod_et=15:30` is skipped.
- `mvn -pl services/orchestrator,contract spotless:apply` then module CI green.

---

## Phase 2 — Broker account-block detection (exec; prod_real intentional halt)

**Goal:** a 403 `40310000` ("new orders are rejected by user request") fails fast as a terminal,
non-retryable, single-alert event instead of burning 6 retries and parking in `RECORDED`.

**Changes**
- `services/exec/.../broker/alpaca/AlpacaPaperBroker.java` `mapError(...)` (L803-846): add a branch
  BEFORE the fall-through `return e` (L845): if status `403` AND body contains `40310000` (or
  "new orders are rejected by user request") → `throw ApplicationFailure.newNonRetryableFailure(...)`
  with a new type e.g. `AccountOrdersBlockedError`. Mirror the existing non-retryable
  `InsufficientFundsError`/`AuthError` classification (L813/L817).
- `services/exec/.../activities/ExecActivitiesImpl.java` `placeOrder` catch (L96-127): when the
  cause is the account-block type, transition the intent to a **terminal** state
  (`OrderState.ERRORED`) via a new `journal.markErrored(intentKey, reason)` instead of leaving it
  `RECORDED` (current `markPlaceFailed:215-225` deliberately preserves `RECORDED` for retry — keep
  that for all other errors; only the terminal account-block class is marked ERRORED). Still rethrow
  so Temporal records the terminal failure (non-retryable → 1 attempt).
- Alerting: `BrokerRejectionAlerter.onBrokerRejection` (exec, L83-122) already fires; with
  non-retryable it now fires **once** per order (no spam). Optional follow-up: per-tenant/day dedupe.
- **Do NOT** auto-trip the kill switch — the block is an intentional broker-side halt.

**Verify / success criteria**
- `AlpacaPaperBrokerTest`: a 403 body with `40310000` → non-retryable `AccountOrdersBlockedError`
  (assert non-retryable); a generic 5xx still maps retryable.
- `ExecActivitiesImpl` test: account-block error marks intent `ERRORED` (not `RECORDED`); generic
  failure still leaves `RECORDED`.
- Temporal attempt count for an account-blocked submit = 1 (non-retryable).
- `mvn -pl services/exec spotless:apply` then exec module CI green.

---

## Phase 3 — Recon false-orphan suppression (missing branch)

**Goal:** stop paging `PositionOrphan` for a position that is actually owned by a running
PositionWorkflow when the Redis position-cache misses or lags.

**Diagnosis to confirm first** (the executing agent must verify before coding)
- Entry-race: the 15:35:00 page fired ~24s BEFORE `EntryFilled` (15:35:24) and the #475 cache seed
  in `startPositionWorkflow` — a transient.
- Cross-strategy: 16:10+ pages came from the `copytrade-v1` recon schedule for a
  `watchlist-trigger-v1`-owned OCC; #477's cross-strategy Redis SCAN
  (`sumRunningOwnerRemainingQtyForOcc`, `PositionLookupActivitiesImpl:140-188`) returns 0 on a cache
  miss (no Visibility fallback) → false page.

**Changes** (`services/orchestrator/.../workflows/ReconciliationWorkflowImpl.java`, `missing`
branch L287-315; `PositionLookupActivities`)
- Before paging `PositionOrphan` in the `missing` branch, add a **Temporal Visibility fallback**:
  when the Redis cross-strategy coverage SCAN returns < broker qty, query running PositionWorkflows
  by `ContractSymbol` (padded OCC) across the tenant's sibling strategies; suppress + emit
  `PositionOrphanSuppressedSiblingOwner` if a running owner covers the qty. (Reuse / extend the
  `findPositionWorkflowId` Visibility query already used in the `filled` branch at L328-333.)
- Add a **debounce on the FIRST page** (require ≥2 consecutive sweeps observing `missing` with no
  owner) so a single cache-cold sweep / entry-race does not page — extend the existing
  `emitPositionOrphanWithDebounce` (L406-470) to the initial `PositionOrphan`, not just `Ongoing`.

**Verify / success criteria**
- New `ReconciliationWorkflowImplTest` case: broker position owned by a running workflow of a
  DIFFERENT strategy with a COLD Redis cache → Visibility fallback finds the owner → **zero
  `PositionOrphan`**. Existing tests (`:360-389` compact-vs-padded) still green.
- A single transient missing observation does not page (debounce).
- `mvn -pl services/orchestrator spotless:apply` then module CI green.

---

## Phase 4 — Flatten-fail escalation: alert + retry next session (version-gated)

**Goal:** when a force-flatten fails to fill, page loudly AND re-attempt at the next session open
instead of silently holding the position indefinitely.

**Changes**
- **Alert (config only):** add `EodForceFlattenFailed` to `OrderFailureAlerter` `DEFAULT_FAILURE_KINDS`
  (`services/orchestrator/.../alert/OrderFailureAlerter.java:99-100`) and the `application.yml`
  `alert.discord.failure-kinds` default. The audit is already emitted
  (`PositionWorkflowImpl.java:2284` / `:2201`); `buildEmbed` renders generic failures; no workflow
  change needed for the alert itself.
- **Retry next session (workflow; version-gated `flatten-retry-next-session-v1`):** in
  `PositionWorkflowImpl`, at the give-up site (`flattenRemaining` v>=1 branch L2274-2297, sets
  `flattenAwaitingLateFill=true`, returns false) and the alive-block (L1020-1027, currently only
  `Workflow.await(() -> lastFillEvent != null)`):
  - arm `Workflow.newTimer(calendar.durationUntilRthOpenEt())` (next session open) → callback sets a
    new `retryFlattenArmed` latch (mirror the one-shot timer pattern at L744-786);
  - `Workflow.await(() -> lastFillEvent != null || retryFlattenArmed)`; on the timer wake, re-call
    `flattenRemaining(reason)`;
  - bound to a max number of sessions (e.g. 3) to avoid an unbounded loop; emit a distinct audit
    (`FlattenRetryScheduled`) per re-arm.
  - All new commands strictly behind the version marker so existing histories replay byte-identically.

**Verify / success criteria**
- `OrderFailureAlerter` test asserts `EodForceFlattenFailed` is in the failure-kind allowlist and
  builds an embed.
- `PositionWorkflowImplTest`: flatten fails → assert alert kind emitted; advance time to next RTH
  open → assert `flattenRemaining` re-attempted (`FlattenRetryScheduled` emitted). Preserve the
  existing `note=bounded_flatten_unfilled_workflow_stays_alive` assertion (`:611-613`) for the
  pre-version path.
- `mvn -pl services/orchestrator spotless:apply` then module CI green; replay test on a captured
  history passes.

---

## Ship order & gating
1. Phase 1 (root cause, orchestrator+contract) →
2. Phase 2 (exec, isolated) →
3. Phase 3 (recon, orchestrator) →
4. Phase 4 (PositionWorkflow, version-gated — riskiest, ship last).

Each: TDD-first, `spotless:apply` on **every** module touched (impl env skips it), module CI green,
own PR with a `Closes #<issue>` line. Trading-critical deploy + inter-phase QA target homelab
(`ssh ridopark@192.168.10.123`). Re-apply `40-tenants-config.yaml` manually after Phase 1.
