# H9.2 amendments (logged 2026-10-22 BEFORE any flow tag was joined to outcomes)
A1 Contract universe: instead of guessing OCC strike grids, the listed contracts come from Alpaca's
   /v2/options/contracts (status active+inactive). Expiries = the 2 nearest LISTED expirations >= the session date
   (includes same-day 0DTE and Mon/Wed weeklies where listed); strikes = all listed strikes with |K/spot-1| <= 5%.
   This is the prereg definition implemented exactly, not a restriction.
A2 Spot for the +-5% band = the session's official open (Alpaca SIP 1Day bar open, raw) for every session incl. the
   pre-06-15 baseline days not in rth_1m.parquet. Same for event and baseline days.
A3 Flow window: 1m option bars with start minute index m in [0, min(fire_min, 59)] (fire_min = start index of the
   trigger bar; entry = its close, so this is all volume printed before the entry, capped at 10:30).
   Baseline sessions use the same minute cap. Bars filtered to the session DATE and 09:30<=t<10:30 ET.
A4 Baseline = the ticker's previous 20 sessions (calendar of that ticker's 1Day bars, independent of watchlist
   listing). z requires >= 15 of the 20 baseline sessions with non-zero option volume and sd>0; else tag = NA
   (reported as a coverage gap, excluded from tag means).
A5 Placebo comparison (secondary): restricted to placebo legs (kind=placebo, fired=1, gap=0, fire_min>=15) whose ticker
   is one of the 32 tickers in the real base sample (1455 of 1639 placebo legs); the other 25 placebo tickers are
   not fetched.
A6 Flow units = contracts (raw volume, not premium- or delta-weighted), per prereg "call minus put option volume".
