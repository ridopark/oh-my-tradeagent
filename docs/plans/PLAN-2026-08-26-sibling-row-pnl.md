# #832 — per-row P&L for sibling positions sharing an OCC (dashboard/BFF)

## Context

Live repro (prod_real SMCI, mark 2.33): the manual 5-lot @2.78 and the healed 21-lot @2.805 BOTH
display P&L(total) −$1,194 / P&L(today) −$1,274 — exactly the broker's COMBINED 26-lot figures.
`PortfolioService.positionItem` attaches `marksByOcc` (account-level broker position marks,
keyed by OCC) verbatim to every workflow row matching the symbol. Correct per-row: −$225 and
−$997.50 (at each row's DISPLAYED basis — note these sum to −$1,222.50, NOT the broker's −$1,194:
the healed row displays input.entryPremium 2.805, the #820 accepted residual, while its true
booked basis is 2.7914 and the broker blends 2.7891. Own-basis row totals reconcile to the broker
figure only when the displayed bases reconcile — an invariant this plan does not establish). Cost/Value columns are
already per-row correct. Display-only: no risk gate reads these fields (PortfolioService's own
#728 note).

## Design (BFF-only — no schema, exec, orchestrator, or dashboard-component changes)

`BrokerPositionsClient.PositionMarks` is built from contract `BrokerPosition`s, which already
carry the broker-position `qty`. Extend the BFF-internal record with `brokerQty`, then compute
per-row in `PortfolioService.positionItem`:

- `unrealized_pl` (total) = `(current_price − entry_premium) × remaining_qty × 100` — the row's
  OWN basis. NEVER prorate the broker total: sibling bases differ (2.78 vs 2.7914), proration
  mis-states both rows.
- `unrealized_intraday_pl` (today) = `unrealizedIntradayPl × remaining_qty / brokerQty` —
  proration by qty is EXACT for the intraday figure: `(current − lastday)` is identical per
  contract regardless of entry basis.
- Null-safety: entry_premium or current_price missing → omit total (row renders, cell dashes —
  today's degrade convention); brokerQty null/zero or intraday missing → omit today. A degraded
  marks map keeps today's behavior (both omitted).
- `current_price` stays as-is (per-unit, correctly shared across siblings).

The dashboard columns (`dashboard/app/live/page.tsx` keys `unrealized_pl` /
`unrealized_intraday_pl`) render whatever the BFF sends — untouched.

## Success criteria

- BFF test: TWO sibling rows (5 @ 2.78, 21 @ 2.805) against ONE broker mark (qty 26, current
  2.33, intraday −1274, total −1194) → rows carry **−225.00** and **−997.50** for total, and
  −245.00 / −1029.00 for today (26-lot −1274 prorated 5/26 and 21/26). Encoded on the live
  incident numbers. (Amended mid-run: the original "totals sum to the broker figure" clause was
  arithmetically unenforceable at production's displayed bases — see Context.)
- Single-owner regression: one row owning the whole broker qty reproduces today's values exactly
  (total from own basis == broker total when bases match; today == full broker intraday).
- Null-safety cases per above. Each new assertion sabotage-verified.
- Full tenant-dashboard-bff suite green; `dashboard` typecheck green; no other module touched.

## Halt conditions

- Any change required outside services/tenant-dashboard-bff (contract, exec, orchestrator,
  dashboard components).
- The snapshot pipeline turns out not to carry broker qty (would force a contract change — halt
  and re-plan).

## Deploy

tenant-dashboard-bff auto-deploys on merge. Field check: the prod_real SMCI sibling rows show
DISTINCT, non-duplicated figures (−$225-ish and −$997-ish at live marks) instead of one combined
figure on both. Do NOT expect their sum to equal the broker account line — the displayed healed
basis (2.805) is the #820 residual vs the broker blend (2.7891); the ~$28 structural gap is
expected and documented.
