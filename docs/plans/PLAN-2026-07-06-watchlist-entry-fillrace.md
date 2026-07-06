# PLAN — 2026-07-06 watchlist-trigger entry fill-race (orphan-until-recon)

On 2026-07-06 the watchlist-trigger strategy on `staging_paper` orphaned every entry it
filled (QQQ 260710C00725000 and SPY 260710C00752000, 5 contracts each): the broker held
the lot but no `PositionWorkflow` managed it until the 5-minute recon sweep auto-adopted
it. For SPY the journal was marked `FILLED` at `14:39:55.992` yet the workflow logged
`TriggerEntryUnfilled` `~213ms later` at `14:39:56.205`, then recon raised `PositionOrphan`
at `14:40:01` and adopted it at `14:40:02`. The lot was **unmanaged** (no stop / target /
exit handling) for the gap between fill and adoption — a few seconds when the fill lands
just before a recon tick, up to ~5 minutes (the recon interval) otherwise. Benign on paper
(recon self-heals a real broker lot), but a real-money exposure gap once the live watchlist
notional cap is loosened.

**This is a partial-fix recurrence.** PR #472 (`6f349f1`) already ported copytrade's
cancel-on-filled adoption to the watchlist entry path. This plan closes the two residual
defects that #472 left, and brings the entry path to the same broker-authoritative standard
the exit/flatten paths reached in #503 (`2ec09e8`) / #509.

Source investigation: three read-only agent sweeps (entry fill-wait map, prior-fix history,
exec order/cancel/fill API), 2026-07-06. Anchors below were re-verified by direct read.

## Root cause (two coupled defects)

**Defect A — entry `onFill` is misrouted, so the happy-path await never wakes in prod.**
`FillDispatcherImpl.resolveWorkflowId` (`services/exec/.../fill/FillDispatcherImpl.java:165-174`)
routes an entry fill (no `:exit:` marker) to `WorkflowIds.copytradeSignal(tenant, strategy,
signalId)` → id shape `t-{tenant}/s-{strategy}/sig/{signalId}`. The watchlist workflow's id
is `t-{tenant}/s-{strategy}/wl/{et_date}/{ticker}/{C|P}`. They never match → the `onFill`
signal hits a non-existent `/sig/...` workflow → `WorkflowNotFoundException` → benign log
(`FillDispatcherImpl.java:151-158`); the journal is still terminalized `FILLED`
(`:114-124`). So `Workflow.await(ttl, () -> fillEvent != null)`
(`WatchlistTriggerWorkflowImpl.java:622`) **never wakes on a real broker fill** for the
watchlist path — it always runs the full TTL then depends on the timeout branch or recon.
Copytrade is unaffected: its workflow id *equals* `copytradeSignal(...)`, so its happy path
routes correctly.

**Defect B — the TTL-expiry branch has no `getOrderStatus` re-check.** At timeout the
watchlist path cancels and inspects the cancel result
(`WatchlistTriggerWorkflowImpl.java:630-647`), adopting inline only when
`cancelResult.getState() == FILLED` in that single call. It lacks the defense-in-depth
`exec.getOrderStatus(intentKey)` fallback the exit paths got in #503/#509
(`PositionWorkflowImpl.java:1410-1420`). When `cancelOrder` returns a non-`FILLED` state
(order was resting at cancel time and filled a beat later, or the WS listener terminalized
the journal `FILLED` around the same instant — exactly SPY today) or throws, the branch
falls through to `TriggerEntryUnfilled` and defers to recon. Copytrade's entry path
(`CopytradeSignalWorkflowImpl.java:1014-1074`) has the identical gap.

Confirmed reusable primitive (no exec contract change needed): `exec.cancelOrder(intentKey)`
already does cancel-and-confirm and returns broker-truth `FILLED` fill detail on
`ALREADY_FILLED` (`ExecActivitiesImpl.java:178-218,259-272`); `exec.getOrderStatus(intentKey)`
re-reads the reconciled journal row (`:220-230`). Both are already Activity-exposed.

## P0 — Immediate operational (no code; operator)
- **Today's SPY 260710C00752000 lot:** leave it — recon adopted it (`covered_qty 5 ==
  broker_qty 5`), it is managed and will EOD-force-flatten (`eod_force_flatten=true`). No action.
- **Sequencing gate (risk):** do **NOT** loosen the `prod_real` watchlist-trigger
  `notional_cap_pct_of_capital_base` (the earlier "why didn't live buy QQQ/AVGO" lever)
  until **Phase 1** is deployed and verified. Loosening it first would let real-money entry
  lots sit unmanaged for up to the TTL/recon window on every fill that races the timeout.
- **TTL headroom (config lever, optional now):** paper fills are landing at ~90s, colliding
  with `pending_ttl_paper_secs` default `90` (`WatchlistTriggerWorkflowImpl.java:996-999`).
  Raising it (e.g. `150`) on the live tenants gives the (Phase-3-fixed) happy path room and
  reduces timeout-branch churn. Live-tenant edit for `staging_paper` / `prod_real`
  (out-of-band, not in repo — see constraint 4); repo `tenants/dev/strategies/*.yaml` +
  `infra/k8s/40-tenants-config.yaml` for dev (constraint 3). Mitigation only, not the fix.

## Phase 1 — Broker-authoritative `getOrderStatus` re-check on the watchlist TTL branch (orchestrator)
**Goal:** the watchlist entry path must never abandon a fill that is already observable in the
journal — adopt it inline instead of leaning on the 5-minute recon sweep. Directly reproduces
and closes today's SPY incident.

**Changes** (anchors):
- `services/orchestrator/.../workflows/WatchlistTriggerWorkflowImpl.java:637-648` — after the
  existing `cancelResult == FILLED` adoption check, and before the `KIND_ENTRY_UNFILLED` audit
  at `:649`, add a defense-in-depth reconcile: when adoption did not fire, call
  `exec.getOrderStatus(intentKey)`; if it reports terminal `FILLED` with `filledQty > 0`, route
  through the existing `handleTtlFilledAdoption(...)` (`:695-767`, already returns `null` on
  decline). Mirror the exit-path shape at `PositionWorkflowImpl.java:1410-1420` (extract/reuse a
  `terminalFillFrom`-style helper rather than duplicating). Only on a non-FILLED `getOrderStatus`
  do we fall through to `TriggerEntryUnfilled`.
  **Version gate:** new `Workflow.getVersion("watchlist-entry-getorderstatus-reconcile-v1",
  DEFAULT_VERSION, 1)` read once at stable scope in the timeout branch — the new
  `getOrderStatus` activity call (and any resulting adoption commands) is a command-shape change;
  existing in-flight histories (`DEFAULT_VERSION`) must replay byte-identically via the current
  path. Reuse of `handleTtlFilledAdoption` keeps its own `watchlist-ttl-filled-adoption-v1` and
  `watchlist-position-cache-v1` markers intact.
- Adoption evidence: reuse `EntryFilled` with a distinct `recovery` label (e.g.
  `getorderstatus_reconcile`) in the subject — **no new audit kind** (no
  `AuditEventKinds.ALL_KINDS` / `KindRegistryGuardTest` change; constraint 5 not triggered).

**Tests (TDD):**
- `WatchlistTriggerWorkflowImplTest` — **incident reproduction:** `cancelOrder` returns
  `CANCELLED` (non-FILLED) but `getOrderStatus(intentKey)` returns `FILLED` qty=5 → assert
  `handleTtlFilledAdoption` fires, child `PositionWorkflow` started, `EntryFilled(recovery=…)`
  emitted, and **no** `TriggerEntryUnfilled` / no orphan. (Reproduces SPY 14:39:55.992-vs-56.205.)
- `cancelOrder` throws (`cancelResult == null`) + `getOrderStatus` `FILLED` → adopt inline.
- Both `cancelOrder` and `getOrderStatus` non-FILLED → `TriggerEntryUnfilled` unchanged
  (legacy behavior preserved).
- Partial fill at cancel time (`getOrderStatus` reports partial) → asserted behavior
  (adopt the filled qty vs. defer) — decide and pin the assertion; today's exit-path precedent
  is delta-only booking.
- Replay guard: a fixture history recorded pre-marker replays clean at v≥1 (no non-determinism).

**Verify / success criteria:**
- `mvn -pl services/orchestrator -am spotless:apply` then `mvn -pl services/orchestrator -am test`
  (constraint 2; the impl env skips spotless so CI fails on it otherwise).
- Behavioral assertion: a watchlist entry whose journal is `FILLED` at TTL expiry → adopted
  inline in the timeout branch, `PositionOrphan` never raised for it.
- Flaky `KillSwitchWorkflowImplTest` → re-run, do not fix (constraint 8).

## Phase 2 — Copytrade entry parity (orchestrator)
**Goal:** apply the same `getOrderStatus` defense-in-depth to the copytrade entry timeout branch,
which the history sweep confirmed has the identical residual gap. Keeps the two entry paths in
lockstep so the next fill-race audit doesn't find one path patched and the other not.

**Changes** (anchors):
- `services/orchestrator/.../workflows/CopytradeSignalWorkflowImpl.java:1029-1049`
  (`handleTtlExpired`) — after the `cancelResult == FILLED` check, add the same
  `exec.getOrderStatus(intentKey)` fallback → `handleCancelOnFilled(...)` before the
  `EntryExpired`/orphan audit. Also evaluate the pre-fill risk-breach race at `:565-607`
  (`breach-filled-adoption-v1`) for the same fallback.
  **Version gate:** new `Workflow.getVersion("copytrade-entry-getorderstatus-reconcile-v1",
  DEFAULT_VERSION, 1)`; existing `ttl-filled-adoption-v1` / `breach-filled-adoption-v1` markers
  unchanged.

**Tests (TDD):** mirror Phase 1's cases in `CopytradeSignalWorkflowImplTest` (cancel non-FILLED +
getOrderStatus FILLED → `handleCancelOnFilled`; cancel throws + FILLED → adopt; both non-FILLED →
`EntryExpired` unchanged).

**Verify / success criteria:** `mvn -pl services/orchestrator -am spotless:apply` + module test;
behavioral assertion: copytrade entry `FILLED`-at-timeout → `handleCancelOnFilled`, no `EntryExpired`.

## Phase 3 — Fix entry-fill routing so the happy path wakes on real fills (exec) — FORK, needs design pass
**Goal:** make the watchlist entry `onFill` actually reach the `/wl/...` workflow so a fill within
the TTL is managed immediately (closes the *early-fill* unmonitored window that Phases 1–2 leave —
a fill at 30s stays unmanaged until the 90s TTL because the await can't wake). This is the deepest
root-cause fix but the highest blast radius; it is intentionally a separate, later PR.

**Changes** (anchors):
- `services/exec/.../fill/FillDispatcherImpl.java:165-174` — generalize `resolveWorkflowId` to
  derive the owning workflow id from the intent-key prefix for entries too: every entry intent-key
  is `Workflow.getInfo().getWorkflowId() + ":entry"` (copytrade `CopytradeSignalWorkflowImpl.java:544`,
  watchlist `WatchlistTriggerWorkflowImpl.java:606`), so stripping the `:entry` suffix yields the
  correct id for BOTH paths — the same prefix-extraction already used for `:exit:` at `:167-171`.
  This is exec-service (dispatcher) code, **not** Temporal workflow history → **no replay gate**.

**Risks / required verification before this ships (the reason it is a fork, not folded in):**
1. **Copytrade non-regression:** prove `WorkflowIds.copytradeSignal(t,s,signalId)` is byte-identical
   to the copytrade entry intent-key prefix in all cases (signalId sanitization/truncation). If they
   can diverge, prefix-strip must be proven equal or copytrade stays on the reconstruct path via an
   explicit branch. Add a dispatch-routing unit test covering both a copytrade and a watchlist entry
   intent-key.
2. **Session-workflow signal semantics:** the `/wl/...` workflow is a long-lived session that fires
   many entries and continue-as-news; `onFill` sets a shared `this.fillEvent`
   (`WatchlistTriggerWorkflowImpl.java:206-209`) that a *specific* `fire()` await reads. Confirm a
   routed fill lands while that `fire()` is still awaiting, is correlated to the right order
   (broker_order_id / signalId) and cannot be cross-attributed to a different ticker's `fire()`.
   This likely requires the `fire()`-level await/predicate to match the fill to its own
   `broker_order_id` rather than a bare non-null flag — scope that in the design pass.

**Decision fork (surface to operator):** Phase 1 alone bounds the unmanaged window to the TTL
(~90s max), removes the recon dependency, and eliminates phantom risk — sufficient to unblock the
live-cap change. Phase 3 additionally makes management immediate-on-fill (window → ~0) but touches
shared fill infra and session-signal state. Recommend shipping Phases 1–2 now and treating Phase 3
as a follow-up after its design pass; do not block the incident fix on it.

## Ship order & gating
1. **Phase 1** (isolated, closes the incident + today's real-money sequencing risk) — own PR,
   replay-gated, operator merge gate (trading-critical).
2. **Phase 2** (copytrade parity) — own PR, replay-gated.
3. **Phase 3** (routing, deeper) — own PR, **after** the design pass on the two risks above; not a
   blocker for lifting the live watchlist notional cap.
- Config TTL lever (P0) may land anytime, independently.
- Each phase: TDD-first with the incident reproduction, `spotless:apply` on every touched module
  (`services/orchestrator` for 1–2, `services/exec` for 3), own PR. `gh pr edit --body` is broken
  here → set the body at create time or `gh api -X PATCH` (constraint 9). Never touch
  `.github/workflows/*.yml`.
