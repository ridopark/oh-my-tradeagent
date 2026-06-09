# Plan — Stop a duplicate-client_order_id 422 from crashing PositionWorkflow and orphaning a live position

## Context / incident (verified end-to-end)

Homelab copytrade, `QQQ 260608C00725000`. The position is **5 long, filled and managed
correctly** — the entry was NOT the problem:

- `2026-06-05 17:01:33 EntryFilled {filled_qty:5, avg:2.0, broker_order_id:465034cf}`
- `17:01:34 PositionEntered {qty:5}` (PositionWorkflow running), journal `BUY 5 @ 2.0 FILLED`.

Then a partial exit was requested and the position was silently orphaned:

- `19:58:06 PartialExitRequested {fraction:0.5}` → SELL 3 @ 0.52 placed, `broker_order_id
  cb7ad040`, journal `SUBMITTED`.
- The place activity was **retried** (at-least-once Activity semantics: the first POST's result
  was not durably recorded — 15s start-to-close timeout / worker churn — so Temporal re-ran it).
  The retry re-POSTed the **same `client_order_id`**; Alpaca returned **422
  `"client_order_id must be unique"` with NO `existing_order_id` field**.
- Our adapter did not recognize this duplicate shape and mapped it to a **non-retryable
  `InvalidRequestError`** (journal `last_error` is the verbatim output of that code path).
- That non-retryable failure propagated out of `PositionWorkflowImpl.processOne` (the exit
  `placeOrder` call is **not** wrapped) → **the PositionWorkflow FAILED**. Audit proves it:
  after `PartialExitRequested` there is **no** `PartialExitFillTimeout`, retry, fill, or close —
  just `PositionOrphan {qty:5, journal_status:"filled"}` every cycle from 20:00 onward.
- The abandoned SELL order expired at the broker (20:00) → journal stuck `SUBMITTED` →
  `JournalOrphan`. The position (5 long) rode unmanaged to ~total loss; `PositionOrphan` /
  `JournalOrphan` fired every cycle for **3 days with no Discord page**. Both were healed
  manually on 2026-06-08.

The earlier "exit order expired → journal stuck" framing was the **debris**; the disease is the
duplicate-422 misclassification that crashed the workflow.

*(One caveat: Temporal's 72h retention already purged this workflow's history, so "the workflow
FAILED" is inferred from the audit gap + the verified non-retryable mapping, not read off its
terminal state. The code-level misclassification and the absent try/catch are both verified.)*

## Root cause (verified in code)

1. **B1 — `placeOrder` only handles ONE duplicate-422 shape.**
   `AlpacaPaperBroker.placeOrder` (lines 126-147) catches `HttpStatusCodeException` and calls
   `duplicateExistingOrderId(e)` (525-535), which returns a value **only if the 422 body carries
   `existing_order_id`**. Alpaca's `"client_order_id must be unique"` 422 has no such field →
   returns null → `mapError(e)` (542-585) hits the catch-all at line 580 →
   `ApplicationFailure.newNonRetryableFailure("Alpaca rejected order (422, non-duplicate): …",
   "InvalidRequestError")`. A duplicate POST is a **normal** consequence of Activity retry, so
   this is a latent crash on the happy path, not an exotic edge.
2. **B2 — the exit `placeOrder` crash is uncaught.**
   `PositionWorkflowImpl.processOne` calls `exec.placeOrder(intent)` at **line 1058 with no
   try/catch**; a non-retryable `ApplicationFailure` fails the whole PositionWorkflow with **no
   audit**. (Contrast `flattenRemaining` lines 1220-1244, which wraps it and emits
   `EodForceFlattenFailed`.) Activity opts (`ExecActivitiesFactory` 65-72): startToClose 15s,
   maxAttempts 5, no `doNotRetry` — a non-retryable failure short-circuits straight to the
   workflow.
3. **B3 — orphaned positions never page.**
   `ReconciliationWorkflowImpl.emitPositionOrphanWithDebounce` (339-418) emits `PositionOrphan`
   on first sighting (1h debounce) and `PositionOrphanOngoing` after 30m. Neither is in the
   `OrderFailureAlerter` allowlist (default `OrphanSTC,EntryExpired`, line 92), so a live
   orphaned position is invisible on Discord — exactly what hid this for 3 days.
4. **A — broker terminal non-fills are not ingested (hygiene).**
   `FillPoller.checkRow` (line 114) drops every non-`FILLED` status; `AlpacaTradeUpdatesStream`
   (246) handles only `fill`/`partial_fill`; the journal has no `markExpired` writer. So an
   exit order that expires/cancels unfilled at the broker leaves its row stuck `SUBMITTED`
   forever → permanent `JournalOrphan`. Secondary to B1-B3 but real.

## The fix

### B1 — `placeOrder` idempotency for the `client_order_id must be unique` 422 (exec; PRIMARY)

In `AlpacaPaperBroker.placeOrder`'s catch (line 141-147): when `duplicateExistingOrderId(e)` is
null **and** the 422 indicates a `client_order_id` uniqueness conflict (body contains
`client_order_id` + `unique`, case-insensitive — keep matching the raw body like `mapError` does),
**resolve the existing order instead of crashing**:
- Add `getOrderByClientOrderId(clientOrderId)` → `GET /v2/orders:by_client_order_id?client_order_id={cid}`
  (the `client_order_id` is in hand at line 124, `request.clientOrderId()`).
- On a hit **whose status is non-terminal** (`new`/`accepted`/`pending_new`/`partially_filled`/
  `filled`), return `new PlaceOrderResponse(existingId, /*alreadyExisted=*/true)` — identical to
  the `existing_order_id` path.
- **MUST handle the terminal-lookup case (review B1):** the by-cid GET can surface a *terminal*
  order (`canceled`/`expired`/`rejected`) — the original `existing_order_id` 422 implied a still
  *live* duplicate, but this fallback does not. If the looked-up order is terminal, do **NOT**
  return `alreadyExisted=true` (that would strand the workflow awaiting a fill that never comes);
  rethrow the original `HttpStatusCodeException` as **retryable** (decision #3: strict live-only).
  Because `client_order_id` is deterministic per intent_key, a fresh POST re-collides with the same
  terminal order, so the retries exhaust `maxAttempts(5)` and the activity fails — at which point
  **B2 catches it gracefully** (keeps the workflow alive, emits `PartialExitPlaceFailed`, pages).
  That B1-strict → B2-backstop chain is the intended terminal-order behavior; the alternative
  (return a dead order as `alreadyExisted=true`) is explicitly rejected because it strands the
  await on a fill that never arrives.
- If the lookup **transiently** fails or returns nothing (sub-second visibility window), rethrow
  the original `HttpStatusCodeException` (NOT a non-retryable) so Temporal retries — never convert
  a duplicate into a non-retryable crash.
- Keep the existing `existing_order_id` fast-path unchanged; this is a strict fallback.
- Post-condition (review CONSIDER): after `alreadyExisted=true`, `ExecActivitiesImpl.markSubmittedIfRecorded`
  must transition the row `RECORDED→SUBMITTED` with the resolved `broker_order_id` so the
  FillPoller/WS can later mark it `FILLED`. Add a counter `alpaca.placeorder.duplicate_cid_resolved`
  (and a sibling for the empty-lookup rethrow) mirroring `buyingPowerFallbackCounter`.

This is the change that keeps the workflow alive: an exit placement retry that races its own
prior success now returns `alreadyExisted=true` and the workflow proceeds to await the fill.

### B2 — PositionWorkflow must not silently die on an exit-placement failure (orchestrator; gated)

Behind ONE version gate `VERSION_EXIT_PLACE_FAILURE_GUARD`
(`Workflow.getVersion(VERSION_EXIT_PLACE_FAILURE_GUARD, DEFAULT_VERSION, 1)`; convention per
`VERSION_DEFER_POSITION_ENTERED`, lines 138/399-400), wrap the `exec.placeOrder(intent)` at line
1058 in try/catch (mirror `flattenRemaining`'s try/catch *structure*, not its fall-through):
- On failure the catch block MUST, in order: emit `KIND_PARTIAL_EXIT_PLACE_FAILED =
  "PartialExitPlaceFailed"` (carry `intent_key`, `option_symbol`, `qty`, `signal_id`, error
  message), release the `exitInFlight` latch / clear `currentInFlightIntentKey`, then **`return;`
  out of `processOne`** — do NOT fall through, break, or continue.
- **Why `return;` is load-bearing (review B2):** line 1059 immediately dereferences
  `placed.getBrokerOrderId()` and lines 1062-1084 then `Workflow.await` on a fill. A literal
  mirror of `flattenRemaining` (whose `placeOrder` is the method's *last* statement, so its catch
  harmlessly falls through) would here either NPE on the never-assigned `placed` or **wedge the
  await on a fill that never arrives** for a never-placed order — silently re-creating the exact
  orphan bug B2 exists to prevent.
- Keep the workflow alive with the position still managed (`remainingQty` unchanged — nothing was
  sold) so a later STC / EOD flatten / re-drive can act.
- Register `PartialExitPlaceFailed` in `AuditEventKinds.ALL_KINDS` (KindRegistryGuardTest enforces
  this at build time) and add it to the B3 alert allowlist + `STC_KINDS` so it pages as an exit.
- v=0 replays are byte-identical (new command only under the gate); no `Instant.now`/`UUID`.
  Note (review CONSIDER): this byte-identity is **by construction** — the existing legacy-replay
  fixture never reaches the exit `placeOrder`, so "legacy-replay stays green" is vacuous for this
  change; the guarantee rests on the gated command, not the test.

B2 is defense-in-depth: even if a *different* placement error appears, the position is never
silently abandoned again.

### B3 — page on PositionOrphan / placement failure (orchestrator; config + small render)

- **Ship the allowlist via the image, NOT `40-tenants-config` (review B3, MUST):** add
  `PositionOrphan`, `PositionOrphanOngoing`, `PartialExitPlaceFailed` to **`DEFAULT_FAILURE_KINDS`
  in `OrderFailureAlerter.java:92` AND the `application.yml:82` default.** `ALERT_DISCORD_FAILURE_KINDS`
  is unset on homelab (only a comment in `51-orchestrator.yaml`), and `40-tenants-config.yaml` is a
  file-content ConfigMap with no env-injection — and `deploy.yml`'s apply glob (`*-orchestrator.yaml`)
  never matches it (see [[reference_deploy_yml_apply_scope]]). Relying on config would silently
  reopen the 3-day blind spot. If an env override is ever wanted, put `ALERT_DISCORD_FAILURE_KINDS`
  in `51-orchestrator.yaml` only. Add a deploy-verification step: confirm the *running pod's*
  effective allowlist contains the orphan kinds. Both orphan kinds are already in `ALL_KINDS`; the
  funnel (`AuditActivitiesImpl.log` → `AuditEventCommitted` → `OrderFailureAlerter.onAuditEvent`,
  after-commit) already works.
- **Fix the render (review B3):** `OrderFailureAlerter.buildEmbed` assumes a BTO/STC *order* shape
  (`STC_KINDS={OrphanSTC}`, reads `option_symbol`/`signal_id`). The recon orphan subject is
  different — `qty`, compact `option_symbol`, `journal_status`, `expected_workflow_id`, and the
  identifier key is **`journal_entry_signal_id`** (fallback `signal_id`), NOT `signal_id`. Add an
  orphan branch rendering "⚠️ Orphaned position — broker holds {qty} {symbol}, no managing
  workflow", **null-safe on every key** (a render that throws is swallowed by the catch → the page
  is silently lost). Assert against a *real* recon orphan subject shape in `OrderFailureAlerterTest`,
  not a hand-built map.
- Debounce already prevents spam: `PositionOrphan` fires once per 1h window, `…Ongoing` once after
  30m — so the page is one alert + one escalation, not every cycle.
- **Paging loudness (decision #4: carve out a loud path for `filled` orphans):**
  `DiscordWebhookClient` hardcodes `allowed_mentions: parse:[]` (deliberate no-ping), so absent a
  change this is **one silent embed** behind two preventive controls — and for a same-week decaying
  long option a missed embed ≈ total loss. Route a `journal_status='filled'` PositionOrphan (a live
  un-exited lot — the exact incident class) to a louder/acknowledgeable path (a pinging channel
  and/or tighter escalation); keep the quiet single-channel default for benign `missing` orphans.
  The B3 success criterion should assert the `filled` page is loud/acknowledgeable, not merely that
  one embed posts.

### A — ingest broker terminal non-fills into the journal (exec; hygiene, lower priority)

Extend `FillPoller.checkRow` to stop dropping non-`FILLED` statuses. Add `EXPIRED` to
`BrokerOrderStatus` + split `mapStatus` (`expired→EXPIRED`, `canceled|replaced→CANCELLED`). Add
journal writers **modeled on `markFilled` (guarded, boolean-returning), NOT `markCancelled`**
(which is an unconditional void update):
- `markExpired(intentKey)` — `UPDATE … SET state='EXPIRED', last_error='broker terminal: EXPIRED',
  last_state_at=now, version=version+1 WHERE intent_key=? AND state='SUBMITTED'`; return updated==1.
- `markBrokerRejected(intentKey, reason)` — same shape, `state='ERRORED'` (first writer of
  `ERRORED`; do NOT reuse `markPlaceFailed`, which keeps state).
- `markCancelledIfSubmitted(intentKey)` (review SHOULD) — the existing `markCancelled` is an
  **unconditional void update**; routing the poller's CANCELLED case through it would re-create the
  #357 late-fill clobber (demote a `FILLED` row to `CANCELLED`). Add a guarded, boolean-returning
  `SUBMITTED`-only variant so all three terminal paths share the same race-safe contract.
- Poller routes: `FILLED`→existing fill path; `EXPIRED`→`markExpired`;
  `CANCELLED`→`markCancelledIfSubmitted`; `REJECTED`→`markBrokerRejected`. Guard on the boolean
  return so a row that lost the late-fill race to the WS path (already `FILLED`) is a silent no-op.

**Observability note (from the ultra-review):** exec has **no `audit_log` writer** and cannot
reach `OrderFailureAlerter`. So A does NOT emit an audit kind. Operator visibility for an
un-exited position rides on **B3** (recon `PositionOrphan` page), which is the correct owner.
A is purely journal hygiene (stops the permanent `JournalOrphan`); it is explicitly NOT the
safety control.

### Scope note — B1 covers BOTH call sites; B2 is exit-only by design (review SHOULD)
`placeOrder` is the **shared** broker method, so **B1 structurally fixes the duplicate-422 crash
for both the exit (`PositionWorkflowImpl:1058`) and the entry (`CopytradeSignalWorkflowImpl:312`)**
— the entry path's `exec.placeOrder` was equally uncaught pre-fix. B2's try/catch is deliberately
**exit-only**: the entry `placeOrder` runs *before* any `Workflow.await` and *before*
`startPositionWorkflow`, so a crash there spawns no PositionWorkflow and orphans **no live lot** —
unlike the exit crash, which abandons an already-filled position. This asymmetry is the reason B2
is not mirrored at line 312; stated here so the resilience invariant is explicit rather than an
apparent omission.

### Non-goals (separate work)
- **Automatic STC re-drive** of an un-exited position (re-place the exit). B2 keeps the position
  managed + alerts; whether to auto-re-drive vs operator-driven is a separate trading-behavior
  plan.
- **Recon auto-heal of stuck journal rows** (Option B). A handles new cases via the poller;
  back-healing historical rows is a one-off ops task (already done for the QQQ-725 row).
- **The 3 stuck `RECORDED` never-placed rows** (CRWV / TSLA×… / QQQ-742) — one-off cleanup.

## Constraints / invariants
- **Determinism:** only B2 touches replayed workflow code → it is the only piece needing a
  `Workflow.getVersion` gate. B1, B3 (config + alerter), and A are plain Spring/exec — no gate.
- **B1 must never turn a duplicate into a non-retryable failure** — the structural guarantee that
  prevents the crash. Transient lookup failure → rethrow original (Temporal retries).
- **A transitions are conditional on `state='SUBMITTED'` and idempotent** — never clobber a
  `FILLED` row that won the late-fill race.
- **B2 must not decrement `remainingQty`** on a placement failure (nothing sold) and must keep the
  workflow alive.

## Tests (TDD)
- **B1** `AlpacaPaperBrokerTest`: 422 body `{"message":"client_order_id must be unique"}` (no
  `existing_order_id`) → adapter does a by-client-order-id GET returning a **live** order →
  `PlaceOrderResponse(id, alreadyExisted=true)`; **fails before the fix** (currently throws
  non-retryable `InvalidRequestError`). Plus: (a) lookup-returns-nothing → rethrows **retryable**,
  not non-retryable; (b) **lookup returns a terminal order (canceled/expired)** → does NOT return
  alreadyExisted=true (rethrows retryable per the chosen B1 strategy); (c) existing
  `existing_order_id` path still returns alreadyExisted=true.
- **B2** `PositionWorkflowImplTest` (TestWorkflowEnvironment): mock `exec.placeOrder` on the exit
  to throw non-retryable `InvalidRequestError`. Assert the **signal-handler turn COMPLETES**
  (stronger than "not FAILED" — a naive not-FAILED assertion also passes the await-wedge bug),
  `exitInFlight==false`, `remainingQty` unchanged, and `PartialExitPlaceFailed` emitted. The v=0
  byte-identity is by-construction (see B2 note); the existing legacy-replay fixture never reaches
  the exit `placeOrder`, so optionally add a fixture that does.
- **B3** `OrderFailureAlerterTest`: a `PositionOrphan` AuditEvent (built from a **real
  ReconciliationWorkflowImpl orphan subject** — keys `qty`/`option_symbol`/`journal_status`/
  `expected_workflow_id`/`journal_entry_signal_id`, not a hand-rolled map) → webhook posts the
  orphan-shaped embed; assert it does NOT throw on a missing key (null-safe) and labels as exit,
  not "BTO failed".
- **A** `FillPollerTest`: `EXPIRED`→`markExpired` called once, no `getFillDetail`, idempotent
  no-op when row already `FILLED`; `CANCELLED`→`markCancelledIfSubmitted` (guarded),
  `REJECTED`→`markBrokerRejected`; `OPEN`/`UNKNOWN` no-op; `FILLED` still dispatches.
  `JooqOrderIntentJournalIT`: `markExpired`/`markBrokerRejected`/`markCancelledIfSubmitted` flip
  only from `SUBMITTED`, bump version, set last_state_at/last_error.
- **A (existing-test, review MUST):** update `AlpacaPaperBrokerTest.getOrderStatus_mapsAllAlpacaStatusStrings`
  (~line 367) — `expired` must now assert `BrokerOrderStatus.EXPIRED` while `canceled`/`replaced`
  still assert `CANCELLED` — and the stale Javadoc at `AlpacaOrderResponse.java:19`. Without this
  the `mapStatus` split breaks the exec build (success criterion #1). StubBroker is unaffected
  (does not call `mapStatus`).

## Success criteria (must all hold)
1. `mvn -B -ntp -pl services/exec -am test` and `-pl services/orchestrator -am test` → BUILD
   SUCCESS, 0 failures (KillSwitchWorkflowImplTest known-flaky: re-run once).
2. **B1 headline test passes and fails without the fix** — a duplicate-`client_order_id` 422
   resolves to `alreadyExisted=true`, never a non-retryable crash.
3. **B2:** an exit placement failure leaves the PositionWorkflow alive + managed and emits
   `PartialExitPlaceFailed` (verified by test); v=0 replay unchanged.
4. **B3:** a first-sighting `PositionOrphan` produces exactly one Discord page (debounce intact),
   rendered with the orphan shape; a `journal_status='filled'` orphan additionally routes to the
   loud/acknowledgeable path (decision #4).
5. **A:** a `SUBMITTED` row whose broker order is expired/canceled/rejected is terminalized within
   one poll cycle, idempotently; recon no longer returns it as `JournalOrphan`.
6. Only B2 carries a Temporal version gate; B1/B3/A carry none.

## Rollout / shippability
Independently shippable, in priority order: **B1** (the crash fix — ship first, smallest, highest
value) → **B3** (paging — closes the visibility gap) → **B2** (workflow resilience, version-gated)
→ **A** (journal hygiene). B1 alone would have prevented this incident.

## Spotless / CI
Run `mvn -pl services/exec -pl services/orchestrator spotless:apply` before committing (impl env
skips it; CI enforces). New audit kind `PartialExitPlaceFailed` must be in `AuditEventKinds.ALL_KINDS`
or `KindRegistryGuardTest` (the pre-push guard) fails.

## Resolved decisions
1. **Sequence, not bundle** — four separate PRs in rollout order **B1 → B3 → B2 → A**. A's journal
   hygiene stays out of the trading-critical B1 PR.
2. **Keep B2** (version-gated defense-in-depth) — a silently-dying workflow orphaning a live
   position is severe enough to warrant it, but it ships **after B1+B3 are proven in prod**.
3. **B1 terminal-order = strict live-only** — `alreadyExisted=true` only for a live lookup; a
   terminal lookup rethrows retryable and falls through to the B2 backstop (see B1 above).
4. **Loud path for `filled` orphans** — `journal_status='filled'` PositionOrphan routes to a
   pinging/acknowledgeable channel; benign `missing` orphans keep the quiet default (see B3 above).

## Failure-mode audit — what B1–B3–A do NOT cover

A 5-lens sweep of the full BTO/STC lifecycle (2026-06-08) against the user's bar — *"handled
automatically, nothing should go bad, all BTO and STC actually fill and sell"*. **Central finding:
B1–B3–A make failures NOT-happen (B1) and VISIBLE (B2/B3), but recovery is still MANUAL and orders
can still fail to fill/sell.** Three structural gaps remain. Config facts below are verified.

### Tier 1 — breaks the "automatic / all fill & sell" guarantee (new scope)
- **C1 — No automatic recovery; reconciliation is detect-only.** `ReconciliationWorkflowImpl`
  emits `PositionOrphan` / `JournalOrphan` / `BrokerOrphan` and **stops** (code comment: "Detect-only:
  v1 … auto-adoption is a follow-up"). The only recovery paths — `POST /positions/adopt`
  (AdoptionWorkflow) and `POST /positions/force-close` — are **manual operator API calls**. So even
  post-B1/B2/B3, an orphaned *filled* position is never sold without a human. → **Add recon
  auto-adoption of `journal_status='filled'` PositionOrphans + auto-re-drive of unfilled exits.**
  (This promotes Non-goal #1 to in-scope — the user's requirement demands it.)
- **C2 — No guaranteed sell deadline for multi-day positions.** Verified:
  `eod_force_flatten: false`, `force_close_0dte_et: "14:45"`. So **only 0DTE** positions have a
  hard auto-sell; a multi-day option whose STC never fills (the QQQ-725 case) has **no backstop**
  and rides to expiry = total loss. → **Add a guaranteed force-flatten backstop (market order)
  before expiry for every held lot, or enable `eod_force_flatten`.**
- **C3 — Structural non-fills on BOTH entry and exit.** Verified: copytrade-v1 has **no
  `max_slippage_*`** → BTO/STC limit = the **exact signal price** (mirror), and unfilled exits are
  dropped after a single retry (PositionWorkflowImpl ~1156-1160). If price moved a tick, the order
  never fills (the QQQ-735 entry miss; the 725 exit miss). → **Marketable / repricing-chase limits
  (or a fill-or-escalate-to-market policy) for entries and exits**, and set slippage config.

### Tier 2 — resilience hardening (broaden existing parts)
- **C4 — B2 is too narrow.** Other uncaught position-critical activity calls also crash/strand the
  workflow: `marketData.subscribePremium` (~844), no top-level error boundary on the main loop,
  and `flattenRemaining` (~1220) *catches but then silently completes with the position still open*
  (`note:"orphan_until_phase_5_reconcile"`). → Broaden B2: top-level error boundary + flatten
  failure retries as a **market** order / never silent-completes with `remainingQty>0`.
- **C5 — Fill detection is single-path if misconfigured.** `EXEC_FILL_LISTENER_POLL_ENABLED`
  defaults OFF; A depends on the poller. **Verified both ON on homelab** (good), but add a
  startup fail-fast if WS is on while poll is off.
- **C6 — The 15s activity timeout *causes* the dup-422.** `ExecActivitiesFactory` startToClose 15s +
  a slow Alpaca options POST → Temporal kills/retries → duplicate POST → the very 422 B1 handles.
  → Bump startToClose (~30s) + tune retry/backoff; reduces the trigger frequency, complementing B1.

### Tier 3 — broker error mapping + config (catalogued; mostly small)
- Broker `mapError` gaps: `429` rate-limit retries forever (no backoff); non-IBP `403`
  (options-not-approved / PDT / short-restricted) falls through to retry-forever; `5xx` has no
  exponential backoff (5 attempts ≈ 5-10s → a short outage drops the order).
- Entry **partial fill** (BTO 5, only 3 fill before TTL) → position opens at 3, remainder can strand.
- `min_partial_qty_behavior: skip` → a 1-contract runner can't be closed by fractional STCs (waits
  for C1/C2 backstop).
- Config hygiene: `max_slippage_*` absent (→ mirror, see C3); `40-tenants-config.yaml` not applied
  by `deploy.yml` (see B3 / [[reference_deploy_yml_apply_scope]]); 0DTE 14:45 flatten vs stale
  market data; symbol-halt handling.

### New open decisions (Tier 1 — your call)
5. **Auto-recovery (C1):** add recon auto-adoption + auto-re-drive of orphaned/unfilled exits
   (true "automatic"), or keep manual adopt/force-close + rely on the B3 page? The user's bar
   points to auto-recovery.
6. **Sell deadline (C2):** enable `eod_force_flatten` and/or a market-order force-flatten backstop
   for all lots before expiry — accepting that force-selling at market overrides the pure
   mirror-the-author design. Yes/no, and at what time.
3. **Fill pricing (C3):** move entries/exits to marketable/repricing limits (higher fill rate, worse
   prices, drifts from the author's posted price) vs keep mirror-price + rely on C1/C2 backstops to
   sweep the misses.

These three are a **second plan/phase** (call it Plan-2: automatic-recovery-and-fill-guarantee);
B1–B3–A stay the immediate crash/visibility fix.

## Review history
- v1 (journal-ingestion framing): ultra-review → **needs_revision** (D3 unimplementable from exec;
  terminalizing silences the only alert). Root cause re-investigated → it was the duplicate-422
  crash, not the exit-ingestion gap.
- v2 (this plan): ultra-review → **approve_with_changes**; 4 MUST + several SHOULD applied above
  (B2 explicit `return`, B3 image-default allowlist, A existing-test/Javadoc, B1 terminal-order
  edge, guarded `markCancelledIfSubmitted`, entry-side asymmetry written down, render key fix).
