# Plan-2B — Multi-day sell guarantee + bounded repricing fill-rate

**Split from Plan-2 after ultra-review. Ships AFTER Plan-2A** (reuses 2A's bounded reason-scoped
`flattenIntent`, the `GetOptionQuoteActivity` anchor, the `exit_floor` fail-safe, and the
no-silent-complete invariant). 2B completes the *multi-day* sell guarantee (lots that have no
flatten timer today) and raises fill rates on the normal entry/exit paths — all bounded limits,
never market.

**R2 (auto-re-drive a dropped exit) is DROPPED.** Reconstructing a `PartialExitRequest` from the
journal is contract-infeasible: it needs `fraction`/`ref_premium`/`author`/`raw_line`/`occurred_at`,
none of which exist on `JournalEntry` (only intent_key/signal_id/option_symbol/side/qty/state/
recorded_at), and `journal.qty` is an absolute resolved count, not the fraction the signal is keyed
on; re-sending `partialExit` also collides with `processedSignalIds` dedup. 2A's auto-adopt + the
multi-day flatten timer below are the guarantee; a dropped-STC fast re-drive can be revived later
ONLY as an absolute-qty exit variant (intent_key+qty) gated on a broker-truth "no open SELL for OCC"
precheck.

## Verified current-state (review-confirmed)
- `durationUntilExpiryCloseEt` returns **ZERO unless `expiry == today`** → multi-day lots have no
  force-close timer at all (only 0DTE arms today). A new calendar surface is required.
- The exit retry in `processOne` is hard-capped at **`maxRetries = 1`** (`VERSION_EXIT_RETRY_ON_TIMEOUT`);
  the late-fill reconcile lives at ~1130-1154; `flattenRemaining` and `processOne` do **not** share
  a reprice loop today.
- `BtoPricing.computeBtoLimit` (~68-100) applies `max_slippage_abs`/`max_slippage_pct` (SLIP_MIN),
  MIRROR when both null; copytrade-v1 currently sets **neither** → entry limit = exact signal price.

## R-AB-1 — Arm a guaranteed flatten timer for ALL lots (multi-day included)
- New calendar activity `durationUntilExpiryFlattenEt(expiry, lead, closeTime)` — **ET-aware /
  weekend-aware** (matching the existing calendar capability) — returning a **positive** Duration
  for any future expiry (not just today). (`durationUntilExpiryCloseEt` can't be reused; 0DTE-only.)
  **Holiday-awareness is a separate dependency** (no holiday calendar exists to reuse) — call it out
  with its own source + test, or descope it; a flatten timer that fires on a **closed market is a
  safe no-op** (the bounded limit simply doesn't fill until the next session), so weekend/ET-only is
  acceptable for v1.
- In `PositionWorkflowImpl`, arm a flatten timer at `expiry_close − flatten_lead_minutes` for every
  lot, independent of `eod_force_flatten`. On fire → 2A's bounded flatten with `reason=expiry_lead`,
  which 2A's **classification router** already treats as bounded (it's ∉ {`risk_breach`,
  `force_close`}) — no edit to 2A switch code. **Audit kind decision:** today `flattenRemaining`'s
  reason `else`-branch falls through to the **EOD** kinds, so register a **dedicated `expiry_lead`
  KIND pair** (PascalCase-alphanumeric per KindRegistryGuard, added to `ALL_KINDS`) and route it
  explicitly — do NOT silently reuse `Eod*`/`Expiry*` via fallthrough (that would mislabel the
  lifecycle event).
- **Version-gated** (genuinely long-lived multi-day workflow; in-flight executions replay across a
  redeploy). New config `flatten_lead_minutes` (default e.g. 30) — plumbed at BOTH config sites
  (CopytradeSignalWorkflowImpl + AdoptionWorkflowImpl.buildInput) with an in-code default.

## R-AB-2 — Bounded stepped repricing on the normal exit (loop redesign, not a tweak)
Redesign the `processOne` exit retry from a single attempt into a bounded stepped reprice:
- New config `exit_reprice_steps`, `exit_reprice_tick`; per-step intent keys `:reprice-N`.
- **Each step re-runs the existing late-fill reconcile (~1130-1154) BEFORE re-placing** so
  `remainingQty` is recomputed and the **naked-short guard holds across every step** (the #357
  class must not regress when there are now N re-places instead of 1).
- Each step's limit is anchored on a fresh `GetOptionQuoteActivity` bid/mid (2A) and **bounded by
  `exit_floor`** (fail-safe identical to 2A); walks toward the market in `exit_reprice_tick` steps
  up to `exit_reprice_steps`, then stops (the scheduled flatten timer from R-AB-1 is the backstop).
- **Factor the place→await-fill→decrement-from-fill helper in 2A R-AA-1 from the start**; 2B's
  reprice LOOP then *wraps* that helper rather than re-extracting 2A-owned code (clean split
  boundary). `targetRemaining` is captured **once** in the helper; per-step `client_order_id`s use
  the deterministic loop counter with a distinct `:reprice-N` prefix (separate from flatten keys) so
  no two steps reuse an id — preserving replay determinism and the #357 naked-short guard.
- **Pin the deadline:** the reprice deadline terminates **at or before** the R-AB-1 flatten-lead
  trigger, so the bounded flatten is unambiguously the **final owner** (no overlapping double-place).
- Version-gated (a second gate layered over 2A's single-shot await); **per-caller replay tests** +
  an assertion of **no double-place / no client_order_id reuse across the double gate**.

## R-AB-3 — Entry fill-rate (within the willing-to-pay cap)
- Set `max_slippage_abs`/`max_slippage_pct` in copytrade-v1 (currently absent → exact-mirror
  non-fills). Optionally reprice the entry up to the cap then accept the miss (the cap is the bound;
  no chase beyond it — buy-side cap is authoritative). No market orders.

## Constraints / invariants
- Inherits 2A's invariants (no unbounded market on scheduled paths; sells clear before expiry;
  `POSITION_CLOSED ⟹ remaining==0`; buy-side cap).
- **Naked-short guard across N reprice steps** (R-AB-2) — re-derive `remainingQty` each step.
- Version-gate all PositionWorkflow changes (timer-arm, reprice loop); recon untouched here.

## Tests (TDD)
- R-AB-1: a multi-day lot arms a flatten timer (`durationUntilExpiryFlattenEt` > 0) and sells via
  the bounded limit at `expiry_close − lead`; v=0 replay unaffected.
- R-AB-2: stepped reprice walks within the floor; late-fill reconcile runs per step (no over-sell
  across steps); deadline respected; per-caller replay tests stay green.
- R-AB-3: `BtoPricingTest` with slippage set (SLIP_MIN); MIRROR still valid when null.

## Success criteria
1. Builds green; spotless clean.
2. A multi-day position with no STC is **sold via a bounded limit before expiry** (test) — the
   QQQ-725 ride-to-expiry class is closed end-to-end (2A auto-adopt + 2B timer).
3. The exit reprices within the `exit_floor` over `exit_reprice_steps`, never over-selling across
   steps, never a market order.
4. Entry fill rate improves within `max_slippage`; all PositionWorkflow changes version-gated.

## Spotless / CI
`mvn -pl services/orchestrator -pl services/exec spotless:apply` pre-commit; new `KIND_*` in ALL_KINDS.
