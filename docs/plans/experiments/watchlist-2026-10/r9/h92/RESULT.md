# H9.2 EARLY OPTION FLOW — RESULT: FAIL

Pre-registered in wl/PREREG9.md. The hypothesis, metric, split and pass rule were not changed. Amendments are in
r9/h92/AMENDMENTS.md and were logged before any tag was joined to outcomes. A1-A6 summary:
- A1: the contract universe comes from Alpaca's listed contracts (2 nearest listed expiries >= day, all strikes within +-5%).
- A2: spot = the official open.
- A3: flow uses option bars with minute index 0..min(fire_min,59), i.e. all volume printed before the entry close.
- A4: the baseline needs 20 prior sessions, >=15 of them non-zero.
- A5: the placebo comparison only uses legs on the 32 real-sample tickers.
- A6: flow is raw contract volume.
No restriction was forced by data volume. Every in-scope contract was fetched, about 3.5k ticker-sessions from 2026-05-15 to 2026-10-22.

Base: kind=real, fired=1, gap=0, fire_min>=15. n=170 (FIT 92 / TEST 78) across 32 tickers. Coverage: every real leg was tagged (0 NA).
8 legs have NaN r60 because they fired too late. Placebo: 1452 of 1455 were tagged (3 lacked a baseline).

## Tag counts (real)
| half | AGREE | DISAGREE | SILENT |
|---|---|---|---|
| FIT | 24 | 4 | 64 |
| TEST | 16 | 3 | 59 |

## Mean outcome per tag (real, bps)
| half | tag | n | r60 | rclose |
|---|---|---|---|---|
| FIT | AGREE | 24 | -1.3 | -2.5 |
| FIT | DISAGREE | 4 | +29.6 | +37.9 |
| FIT | SILENT | 64 | -13.7 | -17.6 |
| TEST | AGREE | 16 | +7.7 | +31.4 |
| TEST | DISAGREE | 3 | -8.7 | -24.5 |
| TEST | SILENT | 59 | +2.5 | +1.8 |

Placebo (A5 scope), r60 AGREE/DISAGREE/SILENT: FIT +6.6/-13.4/-3.9 (n 191/61/552), TEST +7.7/-7.0/+3.4 (n 141/18/489).

## Contrasts (day-clustered bootstrap, B=10000, 95% CI, bps)
| half | outcome | contrast | nA | nB | est | 95% CI |
|---|---|---|---|---|---|---|
| FIT | r60 | AGREE-SILENT | 23 | 60 | +12.4 | [-41.9, +50.4] |
| FIT | r60 | AGREE-DISAGREE | 23 | 4 | -31.0 | [-88.3, +20.1] |
| FIT | r60 | AGREE-placeboAGREE | 23 | 181 | -7.9 | [-71.2, +33.4] |
| FIT | rclose | AGREE-SILENT | 24 | 64 | +15.1 | [-58.7, +75.8] |
| FIT | rclose | AGREE-DISAGREE | 24 | 4 | -40.3 | [-119.5, +34.1] |
| TEST | r60 | AGREE-SILENT | 16 | 56 | +5.1 | [-21.5, +31.5] |
| TEST | r60 | AGREE-DISAGREE | 16 | 3 | +16.4 | [-7.8, +37.2] |
| TEST | r60 | AGREE-placeboAGREE | 16 | 135 | -0.1 | [-23.1, +21.1] |
| TEST | rclose | AGREE-SILENT | 16 | 59 | +29.6 | [-10.0, +72.0] |
| TEST | rclose | AGREE-DISAGREE | 16 | 3 | +55.9 | [+12.0, +103.6] (n=3 DISAGREE; secondary outcome; not part of pass rule) |

## Pass rule
- AGREE-SILENT r60 > 0 with CI excluding 0 in both FIT and TEST: **NO**. The point estimates are positive (+12.4 / +5.1) but both CIs straddle 0 widely.
- AGREE-DISAGREE r60 point estimate > 0 in both halves: **NO**. FIT is -31.0.
- **H9.2 FAILS.** Input for the max-t combiner: TEST AGREE-SILENT r60 t ~= 0.38 (est / bootstrap-CI-implied SE).
- Placebo check: real AGREE legs do no better than placebo AGREE legs (-7.9 / -0.1 bps r60). So any AGREE lift is not specific to the watchlist level.

## Caveats / coverage
- The DISAGREE cell is tiny (7 legs total, all SPY/QQQ). No single-name leg was ever tagged DISAGREE. The AGREE-DISAGREE contrast is noise-dominated in both halves.
- AGREE (40) far outnumbers DISAGREE (7). Flow up to the trigger is contemporaneous with the price move that carried price to the level, so the tag is partly mechanical (momentum-driven option buying) and not independent "informed" demand.
- Descriptive only, not a test: Spearman(z, r60) is +0.04 in FIT and +0.16 in TEST (n=92/78). The direction is weakly consistent but nowhere near the MDE (~35-50 bps for a 15-30% subset).
- Power: AGREE is about 23% of legs. The MDE for that subset is about 40 bps, and the observed effects are 5-12 bps. The test was underpowered for effects of the plausible size, and the observed effects are small.
- Window 09:30-10:30 only. Volume is not premium- or delta-weighted (A6). Opening-auction or complex-order prints are included as Alpaca reports them.

## Files (wl/r9/h92/)
AMENDMENTS.md, flow_remote.py + run_remote.sh (remote fetch: contract listing + per-session call/put minute aggregation),
flow_raw.jsonl (per ticker-session minute call/put volume), fetch.log, h92.py (tagging + bootstrap), legs_tagged.parquet,
contrasts.csv, h92_out.txt (raw output).
