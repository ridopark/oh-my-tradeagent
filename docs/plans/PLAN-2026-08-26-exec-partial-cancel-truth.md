# #819 — exec partial-cancel truth + entry-fill straggler reroute (P3 of the under-booking plan)

## Context

Final phase of docs/plans/PLAN-2026-08-26-partial-fill-underbooking.md. The orchestrator side
(P1/P2/P4) is merged, deployed, and field-verified; the heal is done. Two exec-side gaps remain,
both confirmed by the #818 consults:

1. **A cancel destroys the filled portion.** `ExecActivitiesImpl.cancelOrder`: on outcome
   `CANCELLED`, `journal.markCancelled` records no fill detail — a partially-filled order's real
   contracts vanish from the journal row, the row leaves `FillPoller.findSubmittedOlderThan`
   scope, and recon's `maybeAutoAdopt` (FILLED-rows-only) can never adopt. The #817 page is the
   only surfacing left. The orchestrator's #818 fall-through books its LOCAL WS slices, so this
   gap now only bites when the WS missed slices entirely — exactly the redundancy P3 exists for.
2. **Entry-fill stragglers die on WorkflowNotFound.** `FillDispatcherImpl` routes entry fills to
   the parent signal workflow id (`<intent_key minus :entry>`); once the parent completes, later
   slices log "workflow already completed" and are dropped. #801's growth path in
   PositionWorkflow never receives them.

## Phase A — cancelOrder carries the filled portion (exec)

On `CANCELLED`, fetch `broker.getFillDetail(brokerOrderId)` (the ALREADY_FILLED branch's proven
helper; a broker-truth read that works post-cancel). If `filledQty > 0`, persist it WITH the
CANCELLED transition (one journal update — the risk consult requires the fill written before or
atomically with CANCELLED, never after) and return it on the `OrderIntentResult`. A fill-detail
fetch failure degrades to today's plain markCancelled (best-effort: cancel must never fail
because a fill-read did). Zero-fill cancels are byte-identical to today.

- Journal: add the atomic variant (`markCancelledWithFill` or equivalent single UPDATE). Inspect
  the real column set first; if the row lacks fill columns for non-FILLED states, reuse the
  existing filled-qty/avg-price columns (markFilled already writes them) under state=CANCELLED.
- Consumers: `OrderIntentResult.filledQty` on a CANCELLED result is additive — the orchestrator's
  deployed #818 code reads `state == FILLED` for adoption and its LOCAL fillEvent for partials;
  nothing breaks. (A follow-up may teach handleTtlExpired to prefer the broker figure; NOT this
  plan — the deployed orchestrator must not be assumed.)

## Phase B — dispatcher straggler reroute (exec)

In `FillDispatcherImpl.dispatch`, on `WorkflowNotFoundException` for an ENTRY intent (intent_key
ends `:entry` — exits already route by prefix and are excluded): derive the owning
PositionWorkflow id via `com.ohmytradeagent.contract.identity.WorkflowIds.position(row.tenantId,
row.strategyId, row.optionSymbol, row.signalId)` (the journal stores the padded OCC; the id
format lives in contract/identity so exec and orchestrator cannot drift) and signal `onFill`
there once. A second WorkflowNotFound keeps today's benign log. PositionWorkflowImpl's #801
`bookEntryGrowth` books the cumulative delta capped at expectedQty (fed the ORDERED qty since
#818) — a stale/duplicate report books 0.

- Metric: count reroutes (`recordDispatched` sibling) so the first live straggler is observable.

## Success criteria

- Phase A test: cancel of a partially-filled order (broker CANCELLED, fill detail qty=2) →
  journal row CANCELLED carrying filledQty=2/avgFillPrice, result carries both; fill-detail
  throw → plain CANCELLED (degrade pinned); zero-fill → byte-identical to today (no detail
  fields). Each sabotage-verified.
- Phase B test: entry-intent fill hits WorkflowNotFound → exactly one reroute signal to the
  contract-derived position workflow id (argument-captured, exact id asserted); exit-intent
  fills NEVER reroute; double-NotFound stays benign. Sabotage-verified.
- Full exec suite green; orchestrator suite untouched-green (contract identity unchanged).
- The #818 partial-at-TTL orchestrator test remains green (no orchestrator edits at all).

## Halt conditions

- Any edit required in services/orchestrator (this plan is exec-only; orchestrator is deployed).
- The journal schema cannot express fill-on-CANCELLED without a migration that alters existing
  columns (additive migration is acceptable; destructive is a halt).

## Deploy

exec is EXCLUDED from CI deploy. This ships in the next operator-scheduled closed-market
`kubectl rollout restart` of exec-alpaca-live/-paper — the same roll that picks up #772's
already-merged leniency. Until then the code is merged-but-dark on the live pod.
