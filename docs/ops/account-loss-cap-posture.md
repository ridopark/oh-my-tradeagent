# Account daily-loss cap — loss posture (halt + alert, operator flattens)

Operator-facing record of the accepted risk posture after the "no auto-flatten" change
(PR #590, plan `docs/plans/PLAN-2026-07-15-single-account-loss-rule.md`). Satisfies risk-manager
condition **C5**.

## What the cap does now

The account daily-loss cap is `account_daily_loss_pct` (prod_real = **0.10** = 10% of start-of-day
equity), evaluated by `AccountKillSwitchWorkflowImpl` every ~60s on **realized + open (MTM)** P&L
across the whole tenant.

On an **automatic** breach (`auto:account_daily_loss`, or the fail-closed
`auto:account_mtm_unavailable`):
- **New entries are halted** (the entry gate consults the account kill switch — Phase 1).
- **A loud red Discord page fires** ("positions were NOT auto-flattened — close them manually in
  Alpaca, or trip the kill switch to flatten"), with open-position count + MTM.
- **A re-page repeats ~every 15 min** while the cap stays tripped, market is open, and positions are
  still open (`AccountKillSwitchStillHolding`). It stops on reset, market-close, or holding → 0.
- **Open positions are NOT auto-flattened.**

On a **manual** operator trip (via the kill-switch control): the book **is** flattened — this is the
deliberate one-click flatten path.

## The accepted max-loss posture

**The cap is NOT a hard flatten-stop. It bounds NEW entries, not existing drawdown.** Once tripped,
containment of the already-open book relies on:
1. Each position's own exits — targets, time-stops, chandelier, expiry/EOD backstops.
2. The operator responding to the page — either closing positions in Alpaca, or tripping the kill
   switch (which flattens).

Consequences to accept explicitly:
- **Drawdown can exceed −10%.** Between the trip and the operator acting, MTM can keep falling; the
  10% figure is the *alert* threshold, not a liquidation floor.
- **Per-instrument tail:** long single-leg options (the current copytrade book) are bounded at the
  premium paid (worst case −100% of that position). **If credit spreads / short legs are ever added,
  revisit this doc — their tail is materially larger and the "bounded at premium" statement no longer
  holds.**
- **Overnight exposure:** `eod_force_flatten=false`, so a book tripped late in the session can hold
  overnight with only expiry/EOD backstops. To bound this, set `eod_force_flatten=true`.

## Why this posture (operator decision, 2026-07-15)

Deliberate manual flatten control was preferred over an automated market-order dump of the whole book
— auto-flatten on fast moves / illiquid 0DTE risks slippage and the documented flatten fill-race
failure mode. The tradeoff is that loss containment now depends on the operator seeing and acting on
the page.

## Monitoring this posture depends on (do not let these rot)

- The trip page + the ~15-min re-page must **reach the operator** — the prod_real live Discord channel
  with **mobile push enabled** (risk condition **C2**; verify with a test trip after deploy).
- The `AccountKillSwitchCapInactive` pager (cap silently failing to arm) must be alerting — with the
  per-strategy breaker gone (Phase 3), a silently-inactive account cap = no loss auto-trip at all.

## How to change the posture back

- **Re-enable auto-flatten:** revert the `account-trip-no-auto-flatten-v1` gate (code change) — new
  trips would flatten again; in-flight replay is unaffected.
- **Bound overnight risk:** set `eod_force_flatten=true` on the strategy so a tripped book is flattened
  at EOD regardless.
