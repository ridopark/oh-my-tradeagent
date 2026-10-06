# Literature review: "0DTE SPX sniping" (ORB+CVD, power hour, GEX, VVIX, tight stops)

Compiled 2026-10-04. Source types are tagged: **[PR]** peer-reviewed, **[WP]** working paper/SSRN, **[EX]** exchange research (Cboe has a commercial interest in 0DTE volume), **[V]** vendor marketing, **[B]** blog / retail / independent GitHub replication. Anything not from a source is labelled *reasoning*.

---

## 1. 0DTE option returns, costs, dealer positioning

- **Retail long 0DTE loses on average.** Beckmeyer, Branger & Gayda, "Retail Traders Love 0DTE Options... But Should They?" [WP]. Retail lost about $241k/day over Feb 2021–Sep 2023, and about $350k/day after daily expiries arrived (May 2022). The average retail **long** position lost **53%** (61% after May 2022). Short positions earned +48%/+56%. The losses come from single-leg, debit-paid trades in high-IV options, which is exactly the trade this strategy makes. https://wp.lancs.ac.uk/fofi2024/files/2024/04/FoFI-2024-146-Leander-Gayda.pdf · https://www.researchgate.net/publication/370012499
- **Vilkov, "0DTE Trading Rules: Tail Risk, Implementation, and Tactical Timing"** [WP] (SPXW 0DTE, 2016–Jan 2026). In **Aug 2026** the replication repo published a correction (found by gex.live). The half-spread had been charged at 1/100 of its true size: 0.022 bp instead of **2.2 bp of spot**, a mean dollar spread of about **$1.33**. After the fix, **no structure or basket keeps a positive net Sharpe**. Examples: put ratio +0.84 → −0.61; strangle −0.51 → −0.97; conditional top-3 basket +0.82 → −0.82. **Lesson: published 0DTE edges can live entirely inside the spread.** https://ssrn.com/abstract=4641356 · https://github.com/vilkovgr/0dte-strategies (60★; see KNOWN-ISSUES.md)
- **Spread levels** [B/V, Concretum]. Using 30-min Cboe snapshots from 2022–Aug 2026, the median ATM 0DTE spread is **0.20 pt ($20)** and OTM spreads are 0.05–0.10 pt. The median ATM premium falls from about **24 bp of strike at 10:00** to 18 bp (12:00), 13 bp (14:00) and 8 bp (15:30). *Reasoning:* at SPX ≈ 6,600, a 10:00 ATM option costs about $16, so a 0.20 spread is about 1.3% of premium round-trip at mid-to-touch. By 15:30 the premium is about $5, so the spread costs about 4%, and a 1-OTM option costs more as a percentage. Vilkov's $1.33 mean (all moneyness) is a more conservative bound. Power-hour long premium is far more spread-sensitive than morning premium. https://concretumgroup.substack.com/p/spx-0dte-options
- **0DTE pricing** [WP, JF forthcoming]. Bandi, Fusari & Renò, "0DTE Option Pricing", gives Edgeworth-type ultra-short-tenor pricing and finds 0DTE variance/return risk premia (i.e., long option buyers pay a premium) with "nearly instantaneous predictability". https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4503344
- **Dealer gamma from 0DTE is small and dampening on average.**
  - Dim, Eraker & Vilkov, "0DTEs: Trading, Gamma Risk and Volatility Propagation" [WP]. High 0DTE OI gamma does **not** propagate volatility, and volume shocks do not amplify returns. MM net gamma is on average **positive** and negatively related to future intraday vol. **Positive MM gamma strengthens intraday reversal; negative strengthens momentum.** https://dx.doi.org/10.2139/ssrn.4692190
  - Adams, Fontaine & Ornthanalai [WP], 2019–2023: 0DTE intermediation **lowers** index vol by 60–90 annualized bp. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4881008
  - Amaya, Garcia-Ares, Pearson & Vasquez [WP, hosted by Cboe]: average effect is dampening, with episodic time-varying risk. https://cdn.cboe.com/resources/education/research_publications/gammasqueezes.pdf
  - Cboe [EX]: about 2M 0DTE contracts/day, 50–60% retail, customer flow "remarkably balanced", net MM gamma hedging "at best 0.2% of SPX daily liquidity". https://www.cboe.com/insights/posts/0-dt-es-decoded-positioning-trends-and-market-impact
- **No public study found showing positive net expectancy for rule-based long ATM/1-OTM 0DTE directional buying.**

## 2. Intraday momentum and opening-range breakout

- **Gao, Han, Li & Zhou (2018), JFE 129(2)** [PR]. SPY 1993–2013: the first half-hour return predicts the **last** half-hour. R² is 1.6% in-sample and 1.4% out-of-sample. This is a power-hour signal, **not** a 9:45–10:30 breakout signal. https://www.sciencedirect.com/science/article/abs/pii/S0304405X18301351
- **Decay.**
  - FirmTape [B/V] tested 1,085 SPX sessions (Apr 2022–Aug 2026). The unconditional rest-of-day → last-30m slope is **+0.006 (t = 0.6)**, with mixed signs by year. **Conditional on short-gamma closes** (about 15% of days) the slope rises by +0.055 (t = 3.1). Single vendor, small subsample, unreviewed. https://dev.to/firmtape/intraday-momentum-is-dead-in-the-0dte-era-we-measured-it-on-1085-spx-sessions-43g0
  - A Swedish thesis reports that the pattern has weakened about 75% versus the original. https://www.diva-portal.org/smash/get/diva2:1878991/FULLTEXT01.pdf
- **Zarattini & Aziz, "Can Day Trading Really Be Profitable?"** [WP]. 5-min ORB on QQQ, 2016–2023: 24% hit rate, +0.13R/trade, commissions only (**no spread or slippage**), **no OOS period**. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4416622
  - Replication on 5 indices, 2015–Jun 2026 [B]: gross reproduces (SPX +0.119R), but **net SPX −0.081R**, and no market is distinguishable from zero. "The first 5-minute candle does know something" worth about 0.1R, which equals costs. https://www.mql5.com/en/blogs/post/776235
  - giovannibrusco replication [B]: gross $0.07/share, **break-even at about 2.2¢/share slippage**. An NQ confirmation filter has per-trade t = 2.05, but **76% of its PnL is 2022**. https://github.com/giovannibrusco/zarattini-2023-orb-qqq
- **Zarattini, Aziz & Barbon, "Beat the Market" (noise-area momentum on SPY)** [WP]. Sharpe 1.33 for 2007–2024, in-sample only. https://www.sfi.ch/en/publications/n-24-97-beat-the-market-an-effective-intraday-momentum-strategy-for-s-p500-etf-spy
  - PazSheimy OOS (IQFeed) [B]: in-sample replication Sharpe 1.34; **OOS May 2024–Mar 2026 Sharpe 0.39**, underperforms SPY, decline significant (p < 0.001). https://github.com/PazSheimy/spy-intraday-momentum-oos
  - giovannibrusco (SPY + ES, two feeds) [B]: Sharpe 1.11 for 2020–24, **≈0 since 2025** on both instruments. A 27-variant grid picks the paper's own config, and **walk-forward optimisation destroys value**. https://github.com/giovannibrusco/zarattini-2024-momentum-spy
  - Maróy (2025) [WP] claims Sharpe > 3 via optimisation; the disciplined replication above refutes it. https://papers.ssrn.com/sol3/Delivery.cfm/5095349.pdf?abstractid=5095349&mirid=1
- *Reasoning:* across sources the pattern matches the operator's own finding. Gross ORB/momentum edges exist in-sample, are of the same size as costs, are concentrated in 2020/2022 high-vol regimes, and are near zero after publication.

## 3. Gamma exposure (GEX)

- **Peer-reviewed support for the mechanism:**
  - Baltussen, Da, Lammers & Martens, JFE 142(1), 2021 [PR]: 60+ futures, 1974–2020. Rest-of-day return predicts the last 30 min, driven by short-gamma hedging (option MMs and leveraged ETFs). The effect is stronger when hedging demand is larger and **reverts over following days**. https://www.sciencedirect.com/science/article/abs/pii/S0304405X21001598 · https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3760365
  - Ni, Pearson, Poteshman & White, RFS 34(4), 2021 [PR]: stock volatility **decreases** in hedgers' net gamma. https://www.researchgate.net/publication/228626188
  - Dim/Eraker/Vilkov (above): positive gamma → reversal, negative → momentum.
- **Predicts volatility, not direction.** The GEX literature supports "low-vol / mean-revert regime vs high-vol / trend regime". None of it shows GEX predicts the **sign** of the next 15 minutes.
- **Vendor sources [V]:**
  - SqueezeMetrics white paper (2016/2017): realized vol rises sharply as GEX < 0. In-house, no OOS. https://squeezemetrics.com/monitor/download/pdf/white_paper.pdf
  - SpotGamma methodology (proprietary sign model). https://spotgamma.com/gamma-exposure-gex/
- **Critiques of OI-based GEX:**
  1. The dealer sign is assumed. A typical repo convention is "dealers long calls, short puts", e.g. Matteo-Ferrara/gex-tracker (218★). https://github.com/Matteo-Ferrara/gex-tracker
  2. OI is a prior-night count. **Same-day 0DTE positions have zero reported OI until after they expire**, and 0DTE was about 59% of SPX volume in 2025. https://www.gexmetrix.com/blog/real-time-gamma-exposure-intraday-options-oi-machine-learning [V] · https://tradeecho.com/edge/gex-vs-open-interest [V]
  3. Cboe's measured customer 0DTE flow is balanced, so the true net is small (above).
- **Computing GEX historically:**
  - *Naive GEX:* daily OI by strike plus a greeks/IV snapshot (ThetaData, ORATS, Databento statistics schema, Cboe EOD Summary). Cheap ($40–$200/mo).
  - *Academically credible signed GEX:* needs **Cboe Open-Close** (customer/MM, buy/sell, open/close). EOD from 2005, 10-min from 2011, **1-min from Oct 2019**. Price is quote-only; academic studies use it. https://datashop.cboe.com/cboe-options-open-close-volume-summary
  - *Alternative:* quote-match trade sides from OPRA trades + NBBO. emlama/gex-backtesting does this (12★, Polygon flat files 2024–Feb 2026, pre-registered tests with FDR correction; README reports no headline result). https://github.com/emlama/gex-backtesting

## 4. CVD / order flow

- **Signed order imbalance predicts very short-horizon returns.**
  - Chordia, Roll & Subrahmanyam [PR, JFE 2002]: imbalances predict very short-term returns, and the effect decays within minutes as "reaction time is finite, though brief". https://www.cis.upenn.edu/~mkearns/finread/Chordia_buy-sell_orders.pdf
  - For E-mini: OFI links tightly to price changes **within tens of seconds**, with impact varying across the day [WP]. https://arxiv.org/pdf/2508.06788
- **No peer-reviewed evidence found** that retail-style CVD (cumulative bar-level delta, divergence) confirms 15-min breakouts at a horizon of 10+ minutes. GitHub CVD repos are MT4/MT5/ATAS indicators with no backtests, e.g. https://github.com/EarnForex/Cumulative-Volume-Delta.
- **Computing without aggressor flags.** SPX the index has **no trades**, so use SPY (SIP trades + NBBO) or ES. ES aggressor side is in CME MBO/trades on Databento.
  - Lee-Ready (quote test, then tick test) is about 93% accurate on classifiable trades.
  - The tick rule alone is decent.
  - Bulk Volume Classification (Easley/López de Prado/O'Hara) is cheaper but noisier per trade.
  - Chakrabarty, Pascual & Shkilko, J. Fin. Markets 2015 [PR]. https://ideas.repec.org/a/eee/finmar/v25y2015icp52-79.html
  - *Reasoning:* SPY off-exchange/odd-lot prints and sub-ms quote timing degrade classification. Expect a noisy signal whose informational half-life (seconds to minutes) is shorter than the strategy's 10–15 min hold.

## 5. VVIX

- Huang, Schlag, Shaliastovich & Thimme, JFQA 54(6) 2019 [PR]: VVIX is a priced risk factor that **negatively predicts delta-hedged option payoffs**. *Reasoning:* buying options when VVIX is high means paying up. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3183610
- Park, "Volatility-of-Volatility and Tail Risk Hedging Returns" [PR, J. Fin. Markets 2015]: higher VVIX raises the price of tail hedges (SPX puts, VIX calls) and **lowers their subsequent 3–4-week returns**. https://www.sciencedirect.com/science/article/abs/pii/S1386418115000403 · https://www.federalreserve.gov/econres/feds/volatility-of-volatility-and-tail-risk-premiums.htm
- The decomposed VVIX has some forecasting content for downturns and uncertainty, at multi-day/monthly horizons [WP]. https://acfr.aut.ac.nz/__data/assets/pdf_file/0003/541902/ATR-Paper-Yahua-Roh-ATR-Xu-_-paper.pdf
- "VVIX spike = smart money hedging = drawdown coming" appears in vendor/blog content without OOS tests [V/B]. https://spotgamma.com/vvix-explained-what-the-volatility-index-tells-traders/
- **No intraday evidence found** for VVIX as a minutes-horizon tell.

## 6. GitHub landscape (checked via `gh search repos`, 2026-10-04)

| Repo | ★ | What | OOS? | Red flags |
|---|---|---|---|---|
| [vilkovgr/0dte-strategies](https://github.com/vilkovgr/0dte-strategies) | 60 | Academic SPXW 0DTE strategy replication | Has a conditional OOS protocol | **Cost unit bug flipped net results to negative (fixed Aug 2026)**; LFS data unavailable |
| [giovannibrusco/zarattini-2024-momentum-spy](https://github.com/giovannibrusco/zarattini-2024-momentum-spy) | 4 | SPY+ES noise-area momentum | Yes, frozen protocols, DSR | Best-practice; finds edge ≈0 since 2025 |
| [giovannibrusco/zarattini-2023-orb-qqq](https://github.com/giovannibrusco/zarattini-2023-orb-qqq) | 1 | QQQ 5-min ORB + cost stress | Regime split, placebo, bootstrap | Edge inside the spread |
| [PazSheimy/spy-intraday-momentum-oos](https://github.com/PazSheimy/spy-intraday-momentum-oos) | 2 | OOS test of "Beat the Market" | Yes (May 2024–Mar 2026) | OOS Sharpe 0.39 |
| [emlama/gex-backtesting](https://github.com/emlama/gex-backtesting) | 12 | SPX 0DTE trades with quote-matched side, GEX metrics | Pre-registered, FDR | No headline OOS result published |
| [Matteo-Ferrara/gex-tracker](https://github.com/Matteo-Ferrara/gex-tracker) | 218 | Scrapes Cboe delayed chain → OI-GEX | No (snapshot tool) | Fixed dealer-sign assumption; OI-based |
| [gammagrid/gammagrid](https://github.com/gammagrid/gammagrid), [EzOptions-Schwab](https://github.com/EazyDuz1t/EzOptions-Schwab), [zrack/gex-terminal](https://github.com/zrack/gex-terminal) | 78/40/21 | GEX dashboards | No | Visualization only |
| [aicheung/0dte-trader](https://github.com/aicheung/0dte-trader) | 95 | IBKR 0DTE execution bot | No backtest | Execution tool, not evidence |
| [iulianallroad-glitch/gamma](https://github.com/iulianallroad-glitch/gamma), [zhuanleee/ORB-Platform](https://github.com/zhuanleee/ORB-Platform), [1797346220/qqq-trading-system](https://github.com/1797346220/qqq-trading-system) | 16/1/27 | GEX scalper / SPX 0DTE ORB / QQQ 0DTE | None found | Retail systems, no published OOS |

**No public repo found** that backtests long SPXW 0DTE ORB entries on real option quotes with an out-of-sample split.

## 7. Historical SPXW intraday data (rough costs)

- **ThetaData**:
  - Value $40/mo: 1-min, 6 yr.
  - Standard $80/mo: tick NBBO and trades, history stated as 8–10 yr.
  - Pro $160/mo.
  - Index underlying (SPX) needs a **separate index subscription**.
  - https://www.thetadata.net/pricing · comparison: https://flashalpha.com/articles/options-data-pricing-comparison-flashalpha-thetadata-polygon-spotgamma-squeezemetrics [V]
- **Databento OPRA**:
  - Usage-based from $0.04/GB.
  - 12 months of cbbo-1m for both SPX chains is about **$423**; 13 years usage-based is about $6.1k.
  - Standard plan $199/mo.
  - History from 2013.
  - Validated against Cboe DataShop: quotes match 99.999%.
  - https://databento.com/blog/introducing-new-opra-pricing-plans · https://concretumgroup.substack.com/p/spx-options-database-databento-vs
- **Cboe DataShop**:
  - 1-min SPX option bars, no greeks. Reported at $24–$550/yr and **capping at about $2,200 for 4+ years** (per Concretum).
  - Open-Close (signed) is quote-only.
  - https://datashop.cboe.com/option-eod-summary
- **ORATS**: 1-min chains with greeks/smoothed IV since Aug 2020 (option-level OPRA since Jan 2022), plus an intraday backtester; pricing on request/tiered. https://orats.com/intraday-data-api · https://orats.com/intraday-backtester
- **Massive (ex-Polygon)**: options plans from $29/mo; quotes from 2022, trades from 2016; flat files on higher tiers; SPX index data needs a paid indices tier. https://massive.com/knowledge-base/article/does-massive-support-options-data-for-index-contracts · https://polygon.io/options

## 8. Alpaca index options (live 2026-09-02)

- **Trading:**
  - SPX, SPXW, VIX, VIXW, DJX, XSP are tradable in paper (Jul 2026) and live (Sep 2 2026).
  - Cash-settled, European.
  - **$0.50/contract** plus exchange/regulatory pass-throughs. Index exchange fees differ from equity options and are not stated.
  - AM-settled contracts may have a morning cutoff on expiry day.
  - https://alpaca.markets/blog/alpaca-launches-index-options-via-trading-api/ · https://alpaca.markets/blog/alpaca-introduces-index-options-paper-trading/
- **Market data:** "Alpaca does not currently provide index data through its Market Data offering. Support … planned for a future release."
  - In practice that means **no SPX underlying, and no documented SPXW option bars, quotes, chain snapshots or greeks** via Alpaca's data API, so no history to backtest with.
  - The option-chain endpoint documentation is equity-option oriented: https://docs.alpaca.markets/us/reference/optionchain
  - Whether the OPRA feed tier covers SPXW quotes is **not documented**. Verify with a live API call before relying on it.
  - The forum thread confirms trading but has no data discussion: https://forum.alpaca.markets/t/spx-options-trading-available/19635
- *Reasoning:* the existing PremiumTick feed / exit path (which trades the bid) cannot be assumed to work for SPXW. A third-party real-time feed (ThetaData/Databento) is probably required for both backtest and live marks.

---

## Bottom line for the strategy's specific rules

| Rule | Evidence | Verdict |
|---|---|---|
| **15-min ORB 9:45–10:30 entry** | Gross ORB edge of about 0.1R reproduces but nets to about zero after costs on SPX and peers. Profits are concentrated in 2020/2022, and post-publication OOS Sharpe falls to 0–0.4. Matches the operator's own "momentum entries died OOS". | **Contradicted** (net), at best regime-dependent |
| **CVD confirmation** | Order-flow predictability is real but has a horizon of seconds to minutes. No evidence for bar-level CVD confirming 10–15 min holds. SPX has no trades, so a SPY/ES proxy is needed. | **Unsupported** |
| **Power hour 15:00–16:00 "gamma unwind"** | Gao et al. and Baltussen et al. are peer-reviewed for last-30-min momentum, but the unconditional effect appears to have decayed in the 0DTE era (one vendor study). It possibly survives only on short-gamma days (about 15%, small n). Long premium at 15:30 is about 8 bp of strike, so spreads are a large share of it. | **Mixed** (conditional only, cost-fragile) |
| **GEX regime / zero-gamma filter** | Peer-reviewed: dealer gamma predicts **volatility and reversal-vs-momentum**, not direction. OI-based GEX misses same-day 0DTE positions and assumes dealer sign. Cboe/academic work finds 0DTE net MM gamma small and on average positive. | **Mixed**: credible as a vol-regime gate only with signed (Open-Close) data; OI-GEX as a directional filter is **unsupported** |
| **VVIX spike as hedging tell** | Peer-reviewed: high VVIX means options are **expensive** and later option returns are lower. Drawdown prediction is multi-day at best. Nothing intraday. | **Unsupported** for timing; **contradicted** as a reason to *buy* options |
| **ATM / 1-OTM long calls/puts** | Retail long 0DTE averages −53% to −61%. 0DTE variance risk premium favours sellers. Vilkov's corrected study shows no structure with positive net Sharpe. | **Contradicted** as a default posture |
| **−30% premium hard stop** | No literature found. *Reasoning:* 0DTE premium noise at ATM gamma routinely exceeds 30% inside 15 min, and the operator's own data shows −30/−40% stops underperform a −50% floor. | **Contradicted** (internal data), unsupported externally |
| **10–15 min time stop** | No literature found. The only horizon evidence (order flow) is shorter; the momentum evidence (last-30-min) is longer. | **Unsupported** |
| **+50–75% partial, then breakeven trail** | No literature found. *Reasoning:* scaling out changes the distribution, not the expectation, unless the signal decays at that point. Breakeven trails on high-gamma premium get stopped by noise (same failure mode as tight stops). The operator's finding that "the edge is the author's exits" argues against substituting mechanical exits. | **Unsupported** |
| **1–2% equity risk per trade** | Sizing discipline is the one component consistent with the operator's own surviving result ("floor −50% + sizing survived"). | **Supported** (as risk control, not edge) |

**Overall:** no component has peer-reviewed or replicated evidence of a net-of-cost directional edge for *buying* SPXW 0DTE. The strongest literature (GEX/gamma, last-30-min momentum) is about volatility regime and late-day continuation, and it has decayed or become conditional since 2022. Alpaca currently gives no SPX/SPXW market data, so even a proper test needs ThetaData or Databento. The cheapest credible test is about $80–$200/mo of ThetaData/Databento cbbo-1m. Pre-register a single rule set, use a chronological hold-out, and charge the full spread at entry and exit.

## Source list (by type)

**Peer-reviewed:**
- Gao/Han/Li/Zhou JFE 2018: https://www.sciencedirect.com/science/article/abs/pii/S0304405X18301351
- Baltussen/Da/Lammers/Martens JFE 2021: https://www.sciencedirect.com/science/article/abs/pii/S0304405X21001598
- Ni/Pearson/Poteshman/White RFS 2021: https://www.researchgate.net/publication/228626188
- Huang et al. JFQA 2019: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3183610
- Park JFM 2015: https://www.sciencedirect.com/science/article/abs/pii/S1386418115000403
- Chordia/Roll/Subrahmanyam JFE 2002: https://www.cis.upenn.edu/~mkearns/finread/Chordia_buy-sell_orders.pdf
- Chakrabarty et al. JFM 2015: https://ideas.repec.org/a/eee/finmar/v25y2015icp52-79.html

**Working papers:**
- Beckmeyer/Branger/Gayda: https://www.researchgate.net/publication/370012499
- Bandi/Fusari/Renò (JF forthcoming): https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4503344
- Dim/Eraker/Vilkov: https://dx.doi.org/10.2139/ssrn.4692190
- Adams/Fontaine/Ornthanalai: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4881008
- Vilkov 0DTE rules: https://ssrn.com/abstract=4641356
- Zarattini/Aziz: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4416622
- Zarattini/Aziz/Barbon: https://www.sfi.ch/en/publications/n-24-97-beat-the-market-an-effective-intraday-momentum-strategy-for-s-p500-etf-spy
- Maróy: https://papers.ssrn.com/sol3/Delivery.cfm/5095349.pdf?abstractid=5095349&mirid=1
- E-mini OFI: https://arxiv.org/pdf/2508.06788

**Exchange:**
- Cboe 0DTEs Decoded: https://www.cboe.com/insights/posts/0-dt-es-decoded-positioning-trends-and-market-impact
- Amaya et al. via Cboe: https://cdn.cboe.com/resources/education/research_publications/gammasqueezes.pdf

**Vendor:**
- SqueezeMetrics: https://squeezemetrics.com/monitor/download/pdf/white_paper.pdf
- SpotGamma: https://spotgamma.com/gamma-exposure-gex/
- GEXmetrix: https://www.gexmetrix.com/blog/real-time-gamma-exposure-intraday-options-oi-machine-learning
- Concretum: https://concretumgroup.substack.com/p/spx-0dte-options
- FirmTape: https://dev.to/firmtape/intraday-momentum-is-dead-in-the-0dte-era-we-measured-it-on-1085-spx-sessions-43g0
- Data vendors: https://www.thetadata.net/pricing, https://databento.com/blog/introducing-new-opra-pricing-plans, https://orats.com/intraday-data-api, https://datashop.cboe.com/cboe-options-open-close-volume-summary
- Alpaca: https://alpaca.markets/blog/alpaca-launches-index-options-via-trading-api/

**Blog / GitHub replications:**
- ORB on five indices: https://www.mql5.com/en/blogs/post/776235
- GitHub repos listed in §6.
