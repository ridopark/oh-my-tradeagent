# Plan-2 — Automatic recovery + bounded fill/sell guarantee — SUPERSEDED

This plan's first ultra-review returned **needs_revision** (a hard R1 compile blocker —
`ExternalWorkflowStub` has no `start()` — plus 5 load-bearing safety defects). It has been
**split and rewritten** into two shippable plans:

- **[PLAN-2A-stop-silent-loss-and-auto-adopt.md](PLAN-2A-stop-silent-loss-and-auto-adopt.md)** —
  the two hardest safety wins, ships first: no silent-complete of a live lot, bounded
  reason-scoped (non-market) scheduled exits anchored on a live bid, and recon auto-adopt of
  orphaned filled positions via an ABANDON child (with an over-sell gate). Requires Plan-1 (#361)
  merged.
- **[PLAN-2B-fill-rate-and-multiday-sell.md](PLAN-2B-fill-rate-and-multiday-sell.md)** — completes
  the multi-day sell guarantee (a flatten timer for lots that have none today) and bounded stepped
  repricing for fill rate. Ships after 2A.

## Resolved decisions (were open in v1)
- The willing-to-pay **cap is a BUY-side control**; sells are bounded only on normal days and
  **must clear before expiry** (expiry-day floor collapses to marketable/~$0.01 — sell for
  something, never ride to $0).
- **risk_breach / force_close keep exit-NOW immediacy** (not folded into rest-to-floor).
- Add a **quote-snapshot activity** (live bid/mid) so bounded sells have a real anchor (2A).
- **R2 (auto-re-drive a dropped exit) is DROPPED** — the journal cannot reconstruct a
  `PartialExitRequest`. 2A auto-adopt + 2B flatten timer are the guarantee instead.

See 2A/2B for the full design, the 8 MUST fixes applied, file:line anchors, tests, and criteria.
