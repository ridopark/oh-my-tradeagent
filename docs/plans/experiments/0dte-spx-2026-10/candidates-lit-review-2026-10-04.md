# 0DTE SPX/SPXW candidates for backtesting: evidence review (2026-10-04)

## Bottom line

- **No public source shows a 0DTE short-premium edge that is both large and survives costs in a peer-reviewed or audited setting.** The best academic test (Vilkov 2026) finds the 0DTE variance risk premium is "small and difficult to monetize after realistic frictions." Unconditional net Sharpe is roughly zero to negative for iron condors and iron butterflies. Only *conditional* 10:00 ET rules, tested strictly out-of-sample, survive costs, and those use structures other than the popular IC/IBF.
- **The longest real-money records** come from practitioners running short premium with **mechanical per-side stops**: Sandvand (9,100 trades), Chambless (MEIC) and BigERN. All are self-reported, all net of commissions, and all show the edge thinning in 2024.
- **Muravyev & Ni:** option buyers *gain* intraday and sellers earn their premium overnight. That is a structural headwind for any same-day short-vol strategy. The edge has to come from stops and timing, not from raw carry.
- **Stop discipline drives the sign of the result.** Every credible source says the stop rule matters more than strike choice. FlashAlpha (SPY, non-0DTE): +5,439% with a 100%-of-credit stop vs −100% without, and a 200% stop was worse than none. So a backtest on trade-print bars must model stop fills conservatively, or it will overstate results.

## Testability notes (apply to all candidates)

- **SPX spot:** SPXW settles on the SPX 16:00 close. Derive spot each minute from put-call parity on the nearest-ATM call/put bars, or use SPY×ratio recalibrated daily. Both are approximate.
- **Tick size:** SPXW ticks are $0.05 below $3.00. A "10-cent" far-OTM short loses half its premium to one tick of slippage.
- **Fills from trade prints:** prints are biased toward the side that crossed.
  - Entry: credit = sum of leg *last prints* minus about 1 tick per leg.
  - Stops: trigger on the first print at or above the stop, then fill at that bar's **high** plus 1 tick.
  - Far-OTM strikes print sparsely. A missing bar means "no fill possible", not "price unchanged".
- **Delta selection:** back out ATM IV from the ATM straddle (≈ 0.8·σ·S·√τ). Then pick strikes by Black-Scholes delta with a skew haircut: puts are richer, so a "10-delta put" sits further OTM than flat-vol Black-Scholes implies. Simpler: select by **premium target** (what practitioners actually use) or by **% OTM**, which needs no greeks.
- **VIX proxy:** ATM straddle price / spot at 10:00, annualized. This is effectively VIX1D, which is the better 0DTE regime variable anyway.
- **Data span:** SPXW bars cover Mar 2024–Oct 2026 (~640 sessions). That window includes 2024-08-05, 2025-04-03/04/07/09 and the 2026 regime that Concretum calls challenging for intraday trend. SPY 1-min bars (2016–2026) can test **underlying-only signal components**, and option P&L via Black-Scholes with a straddle-implied vol, but only as a rough check.

---

## Ranked candidates

### 1. MEIC / Breakeven Iron Condor (multi-entry IC, per-side stop = total credit). **Evidence 3/5**

**Rules** (Chambless MEIC; Sandvand BEIC is equivalent):

- **Instrument:** SPXW 0DTE.
- **Entries:** 6 per day at fixed times, e.g. 12:00, 12:30, 13:00, 13:30, 14:00, 14:30 ET. Sandvand's variant: about hourly from 09:45, at least 30 min apart.
- **Structure:** each entry is an iron condor.
  - Shorts are chosen as the strikes whose spread credit ≈ $1.00–1.75 per side. That is roughly a 10–15 delta short.
  - Long wings 30–50 points beyond each short (Sandvand 30, Chambless 50–60).
  - Put credit ≈ call credit.
- **Stop:** separate per side. Close that side's short when its spread mark reaches the **total IC credit** (e.g. $1.50 + $1.50 → stop each side at $3.00). Sandvand's version is "stop = total credit".
- **MEIC+ variant:** stop = total credit − $0.10.
- **Long leg:** after a short is stopped, sell its long if it has any bid. Otherwise let it expire.
- **No profit target.** Hold to expiry.
- **Daily risk cap:** 1–2% of account.

**Logic:** one side stopped plus the other side expiring is about breakeven. A loss needs both sides stopped (8.6% of trades in Sandvand's data, 15–17% in Talon's) or gap-through slippage.

**Evidence:**

| Source | Type | Results | Notes |
|---|---|---|---|
| Sandvand | Live, self-reported | 9,100 trades, Apr 2021–Feb 2026; 40% win rate; avg +0.28% of account per trade; 49 of 57 months profitable | Net of commissions. 2024 degraded: premium capture 3.53% vs 5.65% average, more double stops. |
| Chambless (MEIC) | Live, self-reported | 20.7% CAGR since Jan 2023, max drawdown 4.31% | Costs not stated. |
| Talon (MEIC variant) | Backtest on 4 years of SPX 0DTE tick data | Max drawdown ~$3k vs ~$9k for MEIC; ~49% profitable days | Live tracking only recent. |
| Kam (SSRN 2026) meta-study | 3,909 Option Alpha backtests | 99.9% profitable; median profit factor 1.98 | Author concludes the top results "materially overstate" forward expectations. |

**Red flags:**
- All self-reported and survivorship-prone: these are the traders still teaching.
- The edge per trade is tiny, a few % of premium. Stop-slippage modelling decides the sign.
- Market stops on gap days (2024-08-05 open, 2025-04-09 +10% open-to-high) are where the tail lives.

**Testable with 1-min trade bars:** yes.
- Premium-targeted strikes avoid delta entirely.
- Per-side stops on the spread mark need both legs printing in the same minute. Fall back to the short-leg print plus the last long print.
- Run both entry clocks (morning-hourly vs 12:00–14:30) as two pre-registered variants, not a grid.

### 2. BigERN far-OTM 0DTE put and call writing at the open with stop-limits. **Evidence 3/5**

**Rules:**
- In the first 15–30 min, sell SPX 0DTE puts about 3.9% OTM and calls about 1.9% OTM.
  - 2025 average |delta| ≈ 0.004 (puts) and 0.008 (calls).
  - Premium about $0.10.
  - Use only "prominent" strikes (multiples of 25).
  - Naked, no wings. Collateral is held in bond/futures margin.
- **Stop:** stop-limit at a multiple of the premium; ERN does not publish a fixed multiple. Test 3× and 5×. Limit 2–3 ticks above the stop.
- Otherwise expire. Size the calls about 3× the put count.

**Evidence** (live, self-reported, annual reviews):
- 2024: 0DTE calls +$21.5k, premium capture 61.5%; 10 loss days each for puts and calls.
- 2025: put premium capture 62.2%, call 53.1%; 8 put-loss days and 6 call-loss days. April was the main damage.
- Options-writing record since 2011; no losing calendar month since 2022.
- Net of commissions.

**Red flags:**
- Embedded leverage (premiums are tiny, so sizing is large).
- Naked shorts.
- Exact stop multiples undisclosed.
- His total includes 1DTE and longer trades, so the 0DTE share is only partly isolated.

**Testable:** partly.
- % OTM selection needs no greeks.
- **But** $0.05–0.15 SPXW strikes print sparsely, and one tick is 33–50% of the premium. Results will hinge on the fill assumption.
- Test on SPXW only. SPY far-OTM 0DTE is penny-ticked but a much smaller notional.
- Expect the result to be: thin positive edge before slippage, near zero after.

### 3. Vilkov conditional 10:00 ET rules (put ratio spread / strangle / top-3 basket). **Evidence 3.5/5 on method, 2.5 on implementability**

**Rules:**
- At 10:00 ET, near-ATM structures with |moneyness − 1| ≤ 1%:
  - put ratio spread (1×2);
  - strangle;
  - iron condor;
  - and others.
- Trade the day's strategy only if a logistic classifier predicts P(net P&L > 0) > 0.5. Use a hard 0/1 mapping, not probability weighting.
- **Classifier:** 252-day rolling window. Features:
  - IV, skew and slope at 10:00;
  - lagged realized SPX variance, skewness and return;
  - lagged strategy P&L (1-day, 5-day mean and 5-day std).
- Hold to the close.
- **Costs:** half the bid-ask spread per leg plus 0.5 bp.

**Evidence:** working paper with MIT replication code; OOS April 2019–January 2026.

| Strategy | Gross Sharpe | Net Sharpe |
|---|---|---|
| Put ratio spread | 1.18 | 0.93 |
| Strangles | 0.56 | 0.39 |
| Top-3 basket | 1.12 | 0.82 |
| Iron condor | 0.77 | −0.20 |

- Daily ES₁% is 0.6–1.6% of spot; max drawdowns 10–25%.
- The paper footnotes a *corrected* short-side cost formula ("sign × r_gross − c"). **You noted the repo turned negative after the spread-cost fix. Check which results that applied to.** The current annotated paper still reports positive *conditional* net OOS Sharpe for the ratio spread, strangle and basket. If your "negative" finding was the unconditional or IC result, it agrees with the paper. If it was the conditional ratio spread or strangle, the paper's current text is wrong and should be flagged to the author.

**Red flags:**
- Many strategy×feature combinations, so selection of the top-3 is itself a researcher degree of freedom.
- The 1×2 put ratio is net short a put, so the tail lives there (2025-04).

**Testable:** moderately.
- Features need IV and skew: compute from bars via Black-Scholes on 10:00 prints.
- The half-spread cost cannot be observed from trades. Use a tick schedule: about $0.05 per leg below $3 and about $0.10–0.20 for ATM.
- 640 SPXW days leaves only ~390 OOS days after a 252-day burn-in. That is low power, but it is a genuine replication.

### 4. Short-premium 60-min opening-range breakout credit spread. **Evidence 2/5**

**Rules** (Option Alpha / Quantish):
- Opening range = 09:30–10:30 high and low. Require range ≥ 0.2% of the open.
- First breakout only, between 10:30 and 12:00. Skip FOMC days.
- **Break above the high:** sell a $15-wide SPXW put spread with the short at or just below the range low.
- **Break below the low:** sell a $15-wide call spread with the short at or just above the range high.
- Hold to expiry. No stop in the published version; test a 100%-of-credit stop as a second pre-registered variant.

**Evidence:**

| Source | Type | Results | Notes |
|---|---|---|---|
| Option Alpha 60-min | Vendor backtest | Win rate 89.4%; profit factor 1.44; avg +$39/trade; max drawdown −$3,453 | Period and costs not disclosed. |
| Quantish (QuantConnect) | Backtest | May 2022–Aug 2025; 661 trades; win rate 87%; profit factor 1.3 | Costs not stated. |
| Quantish follow-up | Backtest | Sharpe 2.26 after dropping Wed and Fri (363 trades) | The day exclusion was chosen in-sample. |

**Red flags:**
- No costs. Profit factor 1.3 at a ~$39 average leaves little room for 4 legs of slippage.
- The day-of-week filter is data mining.
- No stop means 2024-08-05-style reversals go to max loss ($1,500 minus credit).

**Why it is still worth a run:** it is the *short-premium* counterpart of your null long-ORB test. It does not need direction to be right, only that the opposite range boundary holds. It is cheap to implement: SPX spot plus two strikes.

**Testable:** yes; no greeks needed.

### 5. Afternoon-only credit spreads with trend side (METF). **Evidence 2/5**

**Rules:**
- At 12:30, 13:00, 13:30, 14:00, 14:30 and 14:45 ET, check the 1-min SPX EMA(20) vs EMA(40).
  - EMA20 > EMA40: sell a put spread.
  - Otherwise: sell a call spread.
- Width 25–35 points; target credit $1.25–2.50.
- Stop at 2× the credit received (a spread-mark stop).
- Hold to expiry.

**Evidence:**
- Live, self-reported (Theta Profits interview): June 2023–June 2024, 50% CAR, max drawdown 8.5%. One year only.
- Supported indirectly by Sandvand's finding that entries from 13:00 on were his most profitable hours and 12:00–13:00 the only losing hour.

**Red flags:**
- A single year, and it was a low-vol year.
- Costs not stated.
- Note your power-hour *long-option* test was strongly negative. This is the *short* side of a similar time window, which is consistent with that result (late-day longs overpay). Don't treat it as contradicting it.

**Testable:** yes. EMA on SPX spot derived from SPY or parity; premium-targeted strikes.

### 6. Intraday "noise-area" momentum with a short ATM 0DTE option overlay (Concretum / J.P. Morgan). **Evidence: base 3.5/5, option overlay 1.5/5**

**Base rules** (Zarattini, Aziz & Barbon, SSRN 4824172; fully specified in the paper):
- Noise band = today's open × (1 ± avg|move from open to time t| over the last 14 days), adjusted for the overnight gap.
- Check at :00 and :30. Go long above the upper band and short below the lower band.
- Trailing exit at the band or VWAP. Flat at the close.
- Size by volatility.

**Overlay:** on a long signal, sell an ATM 0DTE put; on a short signal, sell an ATM 0DTE call. Hold until the signal exit or the close.

**Evidence:**
- Base strategy on SPY, 2007–early 2024: 19.6% annualized, Sharpe 1.33, net of costs; widely replicated.
- The option overlay's results and exact rules are paywalled. The teaser claims a smoother equity curve and better P&L.
- Concretum notes 2026 has been "challenging" for these models on the S&P.

**Red flags:** overlay unverifiable; recent decay.

**Testable:** the base is fully testable on SPY 1-min 2016–2026 with no options at all. That makes it a **good first, cheap test**, distinct from your 15-min long-ORB test. The overlay is testable on SPXW 2024–2026 using ATM prints, which are the densest.

### 7. Baseline / control: unconditional 10:00 ATM iron fly or IC with a 25–30% target. **Evidence 1.5/5 (expected ≈0 or negative)**

**Rules:**
- At 10:00, sell an ATM straddle with wings at ±(straddle price rounded to 5 points).
- Profit target 25–30% of the credit. Stop when the mark reaches 1.5× the credit. Time exit 15:45.
- Variants: Jim Olson's fly sold at the open with a $1.50 target, out by 11:00 (no track record). Option Alpha's 0-1DTE iron butterfly study, 09:45 → 15:45.

**Evidence:**

| Source | Results |
|---|---|
| Option Alpha | 25k trades; iron butterfly 72% win rate, +4.6% avg on premium; no costs. |
| Option Alpha single IBF backtest | 498 trades; profit factor 1.09; max drawdown −$17.9k vs +$20k total P&L. |
| Vilkov | Unconditional IBF/IC net Sharpe ≈ 0 to −0.96, depending on version. |

**Use this as the null comparator** for candidates 1–5. If MEIC cannot beat this baseline on the same fill model, the stop structure is not adding anything.

### Not recommended (evidence says the edge died)

- **Almeida–Freire–Hizmeri SSD-violation short-straddle strategy.** Sharpe 0.2–0.3 after costs, but it "dissipates after the daily availability of 0DTEs" (after May 2022). That is entirely before your SPXW window.
- **Long-gamma or GEX/vanna-driven 0DTE strategies.** Public repos (e.g. YichengYang-Ethan/0dte-strategy) document their own falsifications. Their GEX-skew signal for variance compression was significant but, in the author's words, "too small to survive short-straddle wrapper costs."

---

## Reddit / retail claims that look too good

- **OptionsTradingIQ / Option Omega: 09:35 14-delta IC, 30% target, out by 10:59.**
  - Reported 5,497% CAGR on 133 trades (Jan–Aug 2022) with **100% of the account per trade**.
  - Max drawdown 27.8%; average loser 2× average winner; largest loss −$11k.
  - The CAGR is a compounding artifact. The author's own Aug–Oct 2022 update shows it deteriorating.
- **Option Alpha "25k trades".**
  - "IC opened outside the first two hours averaged 37% return; 94% held to expiry were full winners."
  - No costs. Survivorship within bots (users abandon losers). The "94% held to expiry" figure is conditioned on not being stopped.
- **Kam (2026) meta-study: 99.9% of 3,909 user backtests profitable.** That is a pure publication and selection effect, and profit factors run up to 138 on small samples.
- **Quantish ORB Sharpe 2.26.** Achieved only after dropping Wednesdays and Fridays, chosen with the same data. Without the filter: Sharpe 1.25 and max drawdown 46%, with no costs stated.
- **options.cafe 5-min ORB, ATM SPY 0DTE longs, Mon/Wed/Fri only.**
  - +59% with max drawdown 7.6%, Feb 2024–Mar 2026.
  - Mid fills, no spread modelled, a day filter, still forward-testing.
  - It is the strategy your 15-min ORB test already found null.
- **options.cafe 2024 iron fly "+84.65%".** One lot, $6k account, 168 trades. Discretionary exits, with no stop or target disclosed.
- **"Tachyon" 0DTE IC signals, 80.5% win rate over 4 years net of commissions.** A signal vendor: no rules, no drawdown, and a high win rate says nothing about expectancy.
- **FlashAlpha "+5,439% with stop vs −100% without".** Real lesson (stops matter), but it is not 0DTE (7–60 DTE SPY), the best cell is undisclosed, and the "t > 8 one-line macro flag" is undisclosed.
- **Verdad "Sharpe 0.85–1.4" for short 0DTE ATM straddles.** Not a backtest. It is a Black-Scholes calculation that *assumes* 0.75–1.0 vol points of overpricing.
- **Self-reported live records (Chambless 4.3% max drawdown, METF 50% CAR).** Unaudited, short windows, from educators who sell courses or automation tools. Treat them as hypotheses, not evidence.

## Suggested test order

1. **#6 base on SPY 2016–2026.** Underlying only, fast, and it tells you whether intraday trend still has edge in 2024–2026.
2. **#7 baseline and #1 MEIC on SPXW**, using the conservative stop-fill model above. Pre-register both entry clocks.
3. **#4 ORB credit spread and #5 METF.** These share the spot and EMA plumbing with #6 and the spread plumbing with #1.
4. **#3 Vilkov:** replicate the conditional put-ratio and strangle results with their code against your bars.
5. **#2 BigERN** last. It is the most fill-model-sensitive.

For every candidate:
- Report results with and without 2024-08-05 and 2025-04-03 to 04-09.
- Use a fit/test split: fit on Mar 2024–Jun 2025, test on Jul 2025–Oct 2026.

## Sources

- Vilkov, *0DTE Trading Rules* (SSRN 4641356): https://papers.ssrn.com/sol3/Delivery.cfm/SSRN_ID4694974_code417015.pdf?abstractid=4641356 ; annotated paper and code: https://github.com/vilkovgr/0dte-strategies/blob/main/docs/paper/paper-annotated.md
- FirmTape literature digest: https://firmtape.com/research/what-the-0dte-literature-claims
- Almeida, Freire & Hizmeri, *0DTE Asset Pricing*: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4701401 ; https://www.fma.org/assets/docs/Derivatives2025/Almeida.pdf
- Bandi, Fusari & Renò, *0DTE Option Pricing*: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4503344
- Dim, Eraker & Vilkov, *0DTEs: Trading, Gamma Risk and Volatility Propagation*: https://dx.doi.org/10.2139/ssrn.4692190
- Beckmeyer et al., *Retail Traders Love 0DTE Options*: https://wp.lancs.ac.uk/fofi2024/files/2024/04/FoFI-2024-146-Leander-Gayda.pdf
- Kam, *Efficacy of 0DTE Strategies: 3,909 Option Alpha Backtests*: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=7055179
- Zarattini, Aziz & Barbon, *Beat the Market* (SSRN 4824172): https://www.researchgate.net/publication/380582442
- Concretum, *Intraday Trend Following With 0DTE Options*: https://concretumgroup.substack.com/p/intraday-trend-following-with-0dte
- Sandvand, Breakeven IC after 9,000 trades: https://www.thetaprofits.com/my-most-profitable-options-trading-strategy-0dte-breakeven-iron-condor/
- Chambless MEIC: https://www.thetaprofits.com/tammy-chambless-explains-her-meic-strategy-for-trading-0dte-options/
- Talon IC: https://www.thetaprofits.com/inside-a-0dte-iron-condor-strategy-built-for-smaller-drawdowns/
- METF: https://www.thetaprofits.com/how-to-trade-the-metf-0dte-options-strategy/
- Early Retirement Now 2024 review: https://earlyretirementnow.com/2025/01/14/options-trading-series-part-13-year-2024-review/ ; 2025 review: https://earlyretirementnow.com/2026/01/30/options-trading-series-part-14-year-2025-review/
- Option Alpha 0DTE 25k-trade study: https://optionalpha.com/blog/0dte
- Option Alpha ORB strategy: https://optionalpha.com/blog/opening-range-breakout-0dte-options-trading-strategy-explained
- Option Alpha SPX iron butterfly backtest: https://app.optionalpha.com/zdte/backtester/test/ZT217398679126096701009
- Option Alpha community, IC with iron-fly hedging: https://app.optionalpha.com/community/posts/0dte-iron-condor-w-iron-fly-hedging
- Quantish ORB: https://blog.quantish.io/2025/09/09/0dte-spx-opening-range-breakouts/ ; with exclusions: https://blog.quantish.io/2025/09/24/refining-the-0dte-spx-breakout-strategy/
- OptionsTradingIQ / Option Omega 09:35 IC: https://optionstradingiq.com/option-omega/
- options.cafe ORB: https://options.cafe/blog/0dte-opening-range-breakout-strategy-spy-backtested-results/ ; 2024 iron fly results: https://options.cafe/results/2024/zero-dte-spx-iron-butterflies/
- Jim Olson iron fly thread: https://forums.aeromir.com/threads/jim-olson-0dte-iron-butterfly.2576/
- FlashAlpha 96 put-spread backtest: https://flashalpha.com/articles/spy-put-credit-spread-active-backtest-mm-fills-vrp-signal-drawdown-breaker
- Verdad, *Zero-Day Options*: https://verdadcap.com/archive/zero-day-options
- Tachyon: https://futures.aeromir.com/tachyon
- GitHub falsification log: https://github.com/YichengYang-Ethan/0dte-strategy
- SPXW 0DTE trades dataset with bid/ask side: https://github.com/emlama/gex-backtesting

**Note on Reddit:** `site:reddit.com` searches returned no indexable threads. Retail claims above come from the blogs and forums those threads usually link to.
