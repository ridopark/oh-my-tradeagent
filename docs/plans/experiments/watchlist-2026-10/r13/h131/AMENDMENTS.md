# H13.1 amendments / operational choices — logged 2026-10-23 BEFORE any outcome was computed

Hypothesis, metric, split, variants and pass rule are exactly as in PREREG13.md and are NOT changed here. The items
below pin down things the prereg left open or that the data forced, written down before the first P&L was looked at.

A1. Fetch tooling. One Alpaca option-bars request per session (D 15:00 ET .. D+1 09:50 ET, the 8 contracts of that
    night) via fetch_jobs.sh/fetch_remote_jobs.py (same creds path as wl/fetch.sh). A date-range fetch over all
    ~5,400 contracts would also return every pre-expiry listing day (tens of millions of bars). No analytic effect;
    the window also guarantees no bars from other dates can leak in (checked anyway in the code).
A2. Stock hedge cost: 1 bp of share notional on EACH leg (open at 15:55, unwind at 09:35) as instructed by the caller
    — 2 bp round trip, i.e. more conservative than the prereg's "1 bp round trip". Immaterial (hedge notional is a
    few $k vs ~$300-700 premium).
A3. Missing option bars (never filled with the entry price):
    - Entry leg: needs the D 15:55 bar (vw if present and >0, else c). If absent, use the LATEST bar in 15:50..15:55;
      if none, the night is dropped for that variant. Count reported.
    - Exit leg: needs the D+1 09:35 bar (vw else c). If absent, use the EARLIEST bar in 09:35..09:45; if none, the
      night is dropped for that variant (primary). Robustness row: value a missing exit leg at max(intrinsic at the
      09:35 spot, 0) plus the half-spread (a LOWER bound on the buy-back, i.e. optimistic for the short) to bound
      the drop-bias. Nights with no bars at all on D+1 (e.g. 2024-02-02 is entirely absent from Alpaca) are dropped
      and counted.
A4. Half-spread per leg: Roll estimate from that contract's own 1m closes in the fetched window (D 15:00-16:14 and
    D+1 09:30-09:49; lag-1 autocovariance of price changes within each date, never across the overnight gap):
    roll_half = sqrt(-cov) if cov < 0 else 0. Dollar half-spread applied = max(roll_half, 1% x leg price at that
    time, $0.005 = half a penny tick). Entry legs sold at price - hs, exit legs bought at price + hs. Sensitivity: 2x.
A5. IV / delta. Straddle: ONE IV solving BS_call + BS_put = straddle price at 15:55 (r = 4%, q = 0, T = calendar
    time from D 15:55 ET to expiry D+1 16:00 ET, in years/365). Strangle: the prereg's "15:55 IV" is ambiguous for
    two different strikes, so each leg's delta uses the IV backed out of that leg's own price (standard skew-aware
    delta). Hedge shares = (delta_call + delta_put) x 100 per spread, opened at the stock 15:55 close, unwound at
    the 09:35 close.
A6. Spot. "15:55 spot" = the stock 15:55 1m bar CLOSE (the 2026-06-15+ parquet has no vw, so c is used throughout
    for consistency). ATM strike = round(spot) ($1 grid); strangle strikes = round(spot x 1.005) call,
    round(spot x 0.995) put.
A7. Metric denominator: premium actually received = sum over legs of (price - hs) x 100.
A8. Event exclusion: drop a night if D+1 is in fomc-dates.txt or cpi_dates.txt (the prereg's "nights before
    FOMC/CPI release days"). CPI list: cpi_dates.txt, fixed from the BLS schedule as recalled (bls.gov blocks bots
    from this host; FRED/ALFRED unreachable) — flagged as unverified there.
A9. Pass rule operationalization. Nights are the clusters (no overlap, so cluster bootstrap = resampling nights).
    10,000 percentile-bootstrap resamples of the mean, seed 13. FIT: mean > 0 and 95% CI excludes 0. TEST: mean > 0,
    95% CI excludes 0, AND studentized max-|t| family-wise p < 0.05 across the 8 variants (joint resampling of
    TEST session dates so SPY/QQQ and all/ex-event variants of the same night move together). One-sided max-t
    p-values are reported alongside but the two-sided one is the gate (consistent with "CI excludes 0").
A10. Half-days (13:00 close) have no 15:55 bar and are skipped as entry days automatically; the following session
    is still a valid exit day for the previous night.
