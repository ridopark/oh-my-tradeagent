# Step 1: afternoon 0DTE premium sale (2026-10-04)

The rules are pre-registered in the `sell_afternoon.py` docstring.

## Instruments
- **SPX:** in-sample, because the 14:00 entry was chosen on SPX.
- **SPY and QQQ:** out-of-sample.
- **IWM:** exploratory only.

## Pre-registered PASS
Iron fly or condor at 14:00, under stress cost, closed at 15:55 on SPY and QQQ: **FAIL for both**.
- Iron fly: −22..−26% of risk per day.
- Iron condor: −3..−4%.
- The exit spread on 4 legs plus the last minutes' moves eat the premium.

## Reported variant: S2 iron condor held to settlement
Shorts ±0.15%, wings ±0.60%. Results at stress cost:

| Entry | SPY | QQQ | SPX (in-sample) | IWM |
|---|---|---|---|---|
| 13:00 | +4.3% t2.4 | +6.5% t3.0 | +2.6% t1.4 | −2.0% |
| 14:00 | +5.5% t3.6 | +7.5% t3.8 | +2.9% t1.7 (H2 ≈ 0) | −0.3% |
| 15:00 | +2.0% t1.8 | +3.3% t2.3 | −0.1% | −0.5% |

Win rate is 67-77%. SPY and QQQ are positive in both halves.

## Why it isn't tradeable as tested
- **SPY/QQQ settle physically.** At 15:30 ET on expiration day, Alpaca stops opening orders on expiring contracts and evaluates the positions. If an ITM long lacks buying power to exercise, Alpaca liquidates it. Short ITM legs are assigned after the close.
- **So the "settle" P&L isn't achievable on SPY/QQQ as tested.**
- **The cash-settled vehicles are SPX and XSP.** XSP has $0 exchange fee under 10 contracts. On those the effect is weaker and not significant (t 1.4-1.7).

## Next valid test
A paper fill-cost test of the S2 condor on XSP/SPX, entered around 13:00-14:00 and held to cash settlement. Log the fill vs mid on every leg.

## Round 5 (2026-10-05): five additions, pre-registered
| Test | Verdict |
|---|---|
| **T4 IV-richness gate** on the 14:00 S2 condor (trade only when ATM straddle ≥ fair value from morning realized vol; trailing-60d median, one gate, no grid) | **PASS — first pass of the program.** Gated stress: SPY +10.6%/day of risk (t 3.8), QQQ +15.5% (t 4.2), both halves +, OOS; SPX (in-sample) +9.2% t 3.1 now both halves +. Anti-gate ≈ 0/negative — clean monotone mechanism. Caveats: trade-print entry fills; SPY/QQQ physical settlement still blocks direct trading (vehicle = XSP/SPX); structure chosen after step-1 results, so forward confirmation still required. |
| T2 1DTE overnight short (4 structures × morn/settle × 2 fills, 645 overnights) | **FAIL all 16 cells** (best t 0.46; H1 with crash days negative). The peer-reviewed overnight seller edge does not survive costs on 1DTE SPXW 2024-26. |
| T5 FOMC 13:00 long straddle (exploratory, n=21 + 1-week-before controls) | **Dead.** −8/−10% at the 14:35 exit, win 14%, no better than control Wednesdays — the event is priced. |
| T1 quote collector | `scripts/data/option_quote_collector.py` — forward NBBO for SPY/QQQ/XSP 0DTE chains (quotes can't be backfilled). Smoke-tested 2026-10-05 pre-market: 303/321/549 rows/cycle. **Operator: deploy on homelab (crontab @reboot / k8s), NOT a session cron.** |
| T3 mid-walk executor | Added as Phase 6 (6a telemetry, 6b ladder) to `PLAN-2026-10-04-orb30-paper-sniper.md`. |

**Review note (2026-10-05):** `t2_overnight.py`'s morning-buyback cells model the long-wing exit
cost roughly (a no-op line and a flat charge when the wing has no morning print). Materiality nil —
the settle cells, computed independently, fail on their own (best t 0.46) — but don't reuse the
morn-cell cost code without cleaning it. Also: the committed `fomc-dates.txt` initially ended at
2026-10-02 (backtest artifact); future statement days through 2027 were appended at review so the
skip-FOMC gate can actually fire.
