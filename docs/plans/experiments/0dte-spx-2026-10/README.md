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

## Follow-up: macro events and dark-pool flow (H1/H2, same day)
These were pre-registered in the `h1h2.py` docstring before any result was seen.
- **Tests:** 3 hypotheses × 2 horizons (h30, to 14:45) = 6 tests.
- **Pass rule:**
  - pooled Welch t ≥ 2.64 (Bonferroni);
  - **and** the same sign in P1 2016–20, P2 2021–23 and P3 2024–26.

| Hypothesis | Result |
|---|---|
| H1 ORB on CPI / NFP / FOMC days vs other days (311 event days) | diff −0.8 bp (h30, t −0.46), +2.3 bp (h1445, t 0.55); sign flips across periods — **FAIL** |
| H2a off-exchange share (FINRA TRF, `D`) 09:30–09:44, top vs bottom tercile vs trailing 60d | +0.6 bp (t 0.47), −2.7 bp (t −0.83) — **FAIL** |
| H2b off-exchange tick-signed imbalance agrees with breakout | −0.6 bp (t −0.56), −0.6 bp (t −0.22) — **FAIL** |

- **Exploratory split** (CPI, NFP and FOMC separately): every |t| < 1.3, and signs flip between periods.
- **Off-exchange share** of SPY volume in the first 15 minutes:
  - median 21.6%;
  - rising from about 18% in 2016–21 to about 27% in 2023–26.
- **Calendar sources:**
  - CPI and NFP release dates come from ALFRED vintage dates (series CPIAUCNS and PAYEMS), including the delays from the 2025 shutdown.
  - FOMC statement days come from the federalreserve.gov calendars.
- Calendar files are in `calendar/`.
- To re-run, after `cvd_all.py`, run `offex_all.py $C` (~20 min) and then `h1h2.py $C`.

## Round 3: "find a 0DTE strategy that works" (short premium, same day)
- **Sources:** candidates came from a web, GitHub and Reddit search; see `candidates-lit-review-2026-10-04.md`.
- **Pre-registration:** written in the `strategies.py` docstring before any result.
- **Engine:** `engine.py`.
  - Multi-leg positions on SPXW trade-print bars.
  - Strikes chosen by delta or by target credit.
  - Positions held to the close cash-settle at intrinsic value.
- **Units:** P&L ÷ max risk, with each day as one observation.
- **Pass rule:**
  - base t ≥ 2.7;
  - both halves (H1 2024-03..2025-06, H2 2025-07..2026-10) positive;
  - stress mean > 0.

| Candidate | Base mean / t | Zero-spread (fees only) t | Verdict |
|---|---|---|---|
| C6 base: Zarattini noise-area momentum, SPY | in-sample-era Sharpe 0.77 (t 2.2) → **post-publication (2024-05+) Sharpe −0.58** | — | dead after publication |
| C6o short ATM SPXW on noise-area signal | +8.4% / 2.24 | 2.88 | FAIL; per trade t ≤ 1.15. Short puts carry it (bull market); the opposite-side placebo is only about 6 pts worse |
| C1A MEIC 12:00–14:30 (per-side stop = total credit) | −0.4% / **−4.3** | **+3.46** | FAIL: real gross edge, smaller than 1 tick/leg of spread |
| C1B MEIC hourly | −0.5% / −5.8 | +1.69 | FAIL |
| C5 METF afternoon trend credit spreads | −0.2% / −1.1 | **+3.09** | FAIL: same pattern as MEIC |
| C4A ORB-60 credit spread, hold | −0.6% / −0.5 (88% win) | 0.32 | FAIL; halves flip |
| C4B ORB-60 credit spread, 2× stop | −1.0% / −1.9 | 1.75 | FAIL |
| C7 control: 10:00 ATM iron fly | −22% / −7.6 | −4.0 | strongly negative (intraday short ATM vol loses, cf. Muravyev & Ni) |

**Takeaway:**
- MEIC and METF have a real *gross* edge, which is consistent with the practitioners' self-reports. Whether it is net-positive depends on execution: it needs fills at or near mid and stops on the short leg only.
- Trade-print bars cannot settle that question. Settling it needs either quote data (ThetaData or Databento) or a paper-trading forward test with real fills.
- Not tested:
  - BigERN far-OTM writing: 4% OTM is outside the cache, and its $0.10 premiums are untestable on prints.
  - Vilkov conditional classifier: needs his trained classifier.

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
