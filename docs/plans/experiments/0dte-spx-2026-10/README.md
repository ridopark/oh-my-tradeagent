# 0DTE SPX "sniping" guide test (2026-10-04)

Tests a retail "0DTE index options sniping" guide against 10 years of SPY data and 2.5 years of
real SPXW 0DTE option bars. The guide's rules:
- 15-minute opening-range breakout (ORB), entered 09:45–10:30 ET, confirmed by cumulative volume delta (CVD).
- "Power hour" trades from 15:00 to 16:00.
- ATM or 1-strike-OTM SPXW options.
- Exits: −30% stop; 10–15 minute time stop; sell half at +50%, then move the stop to breakeven.

Rules were **pre-registered from the guide, not fitted**. About 150 variants were run in total, and every one is reported.

## Verdict
**Nothing survived.**
- ORB and CVD show no edge on SPY over 2016–2026.
- On SPXW options, the guide's exits come to about 0 after realistic costs.
- Power hour is **reliably negative**: t from −2 to −14 in both halves of the sample, under every exit rule.

The only thread worth more data is ORB beating the opposite-direction trade on the same days:
- The gap is +11–15% of premium, with the same sign in both halves.
- But t is only 1.6–1.9 against about 150 variants tried, so it is not a result.

Summary and data facts: auto-memory note `reference_0dte_spx_sniping_research_2026_10`.

## Key numbers
| Test | Data | Result |
|---|---|---|
| ORB, SPY underlying | 2,536 trades 2016–2026 | 15/30m ≈ 0 bp; to 14:45 +1.3 bp vs +1.4 bp unconditional long drift; FIT16-20 negative, TEST21-26 positive (bull drift) |
| CVD confirmation (tick-rule, SIP trades) | all 2,703 days | agree vs disagree indistinguishable |
| Bar-level CVD as proxy | 30 sampled days | **unusable** — median per-minute corr 0.17 vs tick CVD |
| ORB on SPXW ATM 0DTE | 614 trades 2024-03..2026-10 | hold to 14:45 +7.0% (t=1.3, median −47%); guide exits +1.9% at $0.10 half-spread, −0.3% at $0.25 |
| ORB minus opposite direction (paired) | 614 days | +11..15% of premium, t 1.6–1.9, same sign both halves |
| Blind ATM call / put at 10:00 | 642 days | ≈ same as ORB (+2% / +0.2% hold) |
| Power hour (day's direction, 15:01→15:50) | 619 trades | −6% to −29% per trade, t −2 to −14, both halves |
| −30% vs −50% stop | — | indistinguishable here |

GEX and VVIX were **not tested**: Alpaca has no index data or historical greeks. A test needs paid data
(ThetaData or Databento, about $80–200/month).

## Data facts
- Alpaca serves **SPXW 1-min option bars** from about 2024-03; they are dense near the money.
- Those bars are built from **trade prints only**. Alpaca has **no** historical option quotes, no SPX index level, and no VIX/VVIX.
- The SPX level is derived from 0DTE put-call parity (`spx_level.py`):
  - SPX/SPY is about 10.03.
  - The ratio's intraday spread is about 4 SPX points (IQR 0.0005).
- **Cost model:** a half-spread per side ($0.10 base, $0.25 stress) plus $0.011 per side for fees.
  - Fees are $1.10 per contract: Alpaca's $0.50 plus an estimated $0.60 of Cboe and regulatory fees.
  - Stops fill at the stop price or the bar open, whichever is lower. This is optimistic, so it flatters the −30% rules.

## Re-run
Credentials come from the repo-root `.env` (`APCA_API_KEY_ID_DATA` / `APCA_API_SECRET_KEY_DATA`). All calls are read-only REST.
Pass a cache directory as the only argument. The cache needs about 1 GB and is not committed.

```bash
C=/path/to/cache; mkdir -p $C/cvd $C/spxw
python fetch_spy.py $C      # SPY 1-min RTH bars 2016..now        (~30s)
python cvd_validate.py $C   # bar-CVD vs tick-CVD on 30 days      (~1m)
python cvd_all.py $C        # tick-rule CVD 09:30-10:30, all days (~25m, resumable)
python underlying.py $C     # ORB / CVD / power hour on SPY  -> results-underlying-*.txt
python fetch_spxw.py $C     # SPXW 0DTE bars, ATM ±100pt        (~3m, resumable)
python spx_level.py $C      # SPX/SPY ratio from parity
python replay.py $C         # option replay grid
python baseline.py $C       # ANTI-ORB, CALL@10, PUT@10 controls
python report.py $C         # -> results-replay-*.txt
```
Requires pandas, numpy, scipy, pyarrow.

**Re-run rule:** use these scripts verbatim on longer history. Do not re-derive the test with a different method.
A result counts only if it is positive with |t| ≥ 2 in both chronological halves after the $0.25 stress cost,
and the total number of variants tried is reported alongside it.
