# Pre-registration round 9 (2026-10-22, before outcomes): watchlist COMBINED with independent information
Ledger before: ~290 variants + 9 S/R defs + 5 crowd + 3 ticker-choice hypotheses (all failed). Condor out of scope.
Power (fired, non-gap legs, n=309 over 80 days, sd r60 = 123 bps): MDE80 all legs 19 bps; 30% subset 35; 15% subset 50.
Forward sessions to detect 20 bps: ~74 (all legs) / ~246 (30% subset).
Base sample: events.parquet kind=real, fired=1, gap=0 (first 1m close through the level; entry = that close).
Outcome (primary): signed underlying return at +60m (r60); secondary: rclose. Split FIT 06-22..08-31, TEST 09-01..10-22.
Each hypothesis splits legs into AGREE / DISAGREE / SILENT. Metric = mean r60 AGREE minus mean r60 SILENT (and minus
placebo legs with the same tag from events.parquet kind=placebo where the tag is computable). PASS: AGREE-SILENT > 0
with day-clustered 95% CI excl 0 in FIT and TEST, AND AGREE-DISAGREE > 0 (point estimate) in both halves.
H9.1 AUTHOR CONSENSUS: AGREE = another copytrade author (audit SignalReceived, action BTO, any tenant, deduped by
  signal_id) bought a same-ticker option in the leg's direction (C for ABOVE, P for BELOW) within [-24h, trigger time];
  DISAGREE = opposite right in that window; SILENT = none.
H9.2 EARLY OPTION FLOW: for the leg's ticker, call minus put option volume (all strikes within +-5% of spot, nearest
  2 expiries, Alpaca option bars) from 09:30 to min(trigger, 10:30), standardized by that ticker's own trailing
  20-session mean/sd of the same window. AGREE = z >= +1 in the leg direction; DISAGREE = z <= -1; SILENT otherwise.
  Triggers before 09:45 are excluded (no flow window).
H9.3 CATALYST DAY: AGREE = ticker has scheduled earnings that morning (before open) or the previous evening (after
  close); SILENT = no earnings within +-3 trading days; DISAGREE = n/a (report AGREE-SILENT only).
H9.4 is the DISAGREE side of H9.1/H9.2 (built into the pass rule above).
Studentized max-t over the 3 AGREE-SILENT metrics on TEST.
FORWARD: freeze survivors (or best-supported, labelled) into a daily shadow scorer from 2026-10-23.
