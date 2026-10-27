# Pre-registration round 7 (2026-10-22, before outcomes): the CROWD following the watchlist
Ledger before: ~266 variants + 9 S/R defs. Budget: 5 hypotheses, listed here; nothing added after outcomes.
Stop: one hypothesis passes historical test AND >=20-session frozen forward paper test, or all 5 fail.
Unit of analysis: a listed contract (ticker, right, strike from the author's watchlist image / watchlist_daily),
 its expiries E in {nearest listed expiry >= day (0DTE for SPY/QQQ), next weekly}, vs CONTROL contracts = adjacent
 strikes (one further OTM, one nearer ATM) same right/expiry. Events: POST (exact post time from the Discord
 snowflake) and TRIGGER (first 1m bar trading through the level, as in round 3 'break').
Sample: author's watchlists 2026-09-22..10-22 with exact strikes. FIT = 09-22..10-07, TEST = 10-08..10-22.
H1 footprint (volume): listed/control volume ratio in [0,+10m] after TRIGGER minus same ratio in [-30,-5m]. PASS:
  log-ratio change > 0, day-clustered CI excl. 0 in FIT and TEST. (Also reported for POST; descriptive.)
H2 IV pop: listed minus control IV (Black-Scholes from trade prices, r=4%, q=0) change from [-10,0] to [0,+5] min after
  TRIGGER; decay to +30m. PASS: >0 with CI excl 0 in FIT and TEST.
H3 sell-to-crowd: at TRIGGER+1m sell the listed contract at the BID (collector) or trade-price*(1-hs) where no quotes,
  delta-hedge with shares at 1 bp, exit at +10/+30m at ASK (2 variants). PASS: net mean >0, CI excl 0 both halves.
H4 stock overshoot fade: at the break, fade in shares from TRIGGER+5m to +30m, all 647 legs (FIT Jun-Aug, TEST Sep-Oct)
  + exits at +15/+30 (2 variants), cost 1 bp ETF / 3 bp single. PASS as H3.
H5 dose-response: H1/H2 effect larger on legs with more follower entry messages in chat ([-5,+15m] of trigger);
  Spearman >0, CI excl 0 pooled. Validation only.
Studentized max-t across H3+H4 variants (4) on TEST. Independent rebuild of the key number by a reviewer from raw data.
