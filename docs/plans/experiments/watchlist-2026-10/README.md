# Watchlist edge research, 2026-10-22 → 10-24 (archive)

Scripts, pre-registrations, results and independent reviews from fourteen rounds testing whether the
TradingTheTrend daily watchlist (and adjacent ideas) carries a tradeable edge. **Verdict: no edge survives
costs and out-of-sample testing in any of ~500 variants; the only family that ever passed in this repo is the
IV-richness-gated afternoon condor (`0dte-spx-2026-10/`).** The living report with all tables is the Claude doc
"Watchlist Edge Research"; the durable notes are in the operator's memory file
`reference_watchlist_levels_no_edge_2026_10`.

**Do not re-run these on the same 90 sessions.** The forward test (`PREREG-2026-10-23-watchlist-forward-v1.md`,
`scripts/research/watchlist_forward.py`, tag `watchlist-forward-v1`) is where new evidence accumulates; its single
look is 2027-04-23.

## Ledger

| Round | Question | Files | Verdict |
|---|---|---|---|
| 1 | First 1-min close through a level: continuation vs placebo; 64 exit rules | `PREREG.md`, `event_study.py`, `analyze.py`, `grid.py` | no edge |
| 2 | Break+vol filter, fade, retest, anticipation, gap-through; 10-year generic levels | `PREREG2.md`, `hyp.py`, `run2026*.py`, `run_decade.py`, `evalh.py`, `ledger_*.csv` | no edge; vol filter was look-ahead |
| 3 | The author's own written rules, 6 variants | `author_rules.py` | net negative |
| 4 | 9 support/resistance definitions; trailing stops on real option bids | `PREREG4.md`, `sr_react.py`, `sr_eval.py`, `trail_bid.py` | no rejection effect; trails lose |
| 5 | Is 0DTE convexity cheap; allocation across real strategies | `PREREG5.md`, `convex_ev.py`, `alloc/` | buyer −40¢/$; allocation not identifiable |
| 6 | 5-min pivot continuation as a stock strategy | `PREREG6.md`, `pivot_strat.py`, `pivot_eval.py` | the motivating effect was a dedupe look-ahead; net negative |
| 7 | Crowd footprint in option trades at the listed strikes | `PREREG7.md`, `crowd_eval.py` | no footprint; selling to the crowd loses |
| 8 | What the ticker choice reveals: vol selection, attention drift, put/call tilt | `PREREG8.md`, `r8/` | all fail |
| 9 | Watchlist + independent information (author consensus, option flow, earnings) | `PREREG9.md`, `r9/` incl. `r9/review/` (from-scratch rebuild) | fail / untestable |
| 10–11 | (forked session) LLM judge for entries/exits; exiting on author silence | see the Claude doc | no edge |
| 12 | Scalping: tick entry, targets 10–200 bps, fixed and trailing stops, re-entries; tick replay on 12.2M trades, 46 symbols | `scalp_1m`→`scalp_grid.py`, `tick_remote*.py`, `tick_replay.py`, `tick_all_summary.py` | two artifacts caught; no edge |
| 13 | Published effects: overnight option premium, market intraday momentum, SPX buy-side flow | `PREREG13.md`, `r13/` | effects absent post-2016/2024; flow data unobtainable |
| 14 | Machine learning: GBM, TabPFN, Kronos, with shuffled-label nulls and 10-year replication | `ml_check.py`, `ml_robust.py`, `ml_decade.py`, `ml_tabpfn*.py`, `ml_kronos.py` | all ≈ 0.50 on direction once replicated |

Independent reviews: `review/REVIEW.md` (rounds 1–3), `review2/REVIEW.md` (rounds 4–7), `r9/review/REVIEW.md` (round 9).

## Artifacts caught before they were acted on
Volatility filter using post-entry data; unstudentized reality check; option bars not filtered to the trade date
(OCC 0DTE symbols trade on earlier days); pivot dedupe against future pivots; stops booked at the stop price on bar
data; tick windows anchored on a bar-close trigger; an ML classifier that passed a shuffled-label null but
reversed sign on 10 years of other data. Each is described in the Claude doc's "Artifacts caught" table.

## Data (not committed; re-fetchable)
- Watchlist legs: `audit_log` kind `TriggerArmed` on the homelab (`scripts/data/homelab_psql.sh orchestrator`).
- 1-min SIP stock bars, option bars, option trades: `fetch.sh` / `fetch_remote.py` here (credentials stay on the
  homelab market-data pod, as in `scripts/data/equity_bars.sh`). `tick_remote2.py` is the retrying trades fetcher.
- Option NBBO: the homelab collector (`~/data/option-quotes*`), from 2026-10-06 (0DTE) and 2026-10-23 (watchlist).
- DoltHub `post-no-preference/options` for EOD IV (no open interest; no QQQ/TSM/NBIS).

Python deps: pandas, numpy, pyarrow; scikit-learn for round 14; `cvxpy` for `alloc/`; Kronos needs its repo
(MIT, `shiyu-coder/Kronos`) and a CUDA torch.

## Addendum: nautilus_trader spike (2026-10-25)
`nautilus/nautilus_fill.py` loads the collector's 1-min NBBO as `QuoteTick` data on `OptionContract` instruments and
runs a limit-buy-at-mid through nautilus 1.231's simulated exchange (one engine per contract-day and entry; agrees with
an independent pandas replay on 106/107 cases). On the author's 18 SPY/QQQ 0DTE trades a mid limit fills 67% within 15
minutes and saves 1.2% of premium, but the six trades it MISSES averaged +109% (−10% excluding the +703% day) while the
twelve it FILLS averaged −30%: resting at mid is adversely selected. At a trigger, pay the ask or don't trade.
