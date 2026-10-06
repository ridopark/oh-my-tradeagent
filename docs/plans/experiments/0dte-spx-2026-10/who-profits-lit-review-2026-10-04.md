# Who makes money on 0DTE, and what is different about them (2026-10-04)

Evidence tags: **[PR]** peer-reviewed · **[WP]** working paper · **[EX]** exchange/market-maker publication (has a conflict of interest) · **[VEN]** vendor/broker · **[SELF]** self-reported by the trader, not audited.

## TL;DR
The money in 0DTE goes mostly to **liquidity providers through the bid-ask spread**. A thin, unstable slice goes to **sellers of risk premium who keep their costs tiny and survive the tails**. Most retail losses are not bad direction calls; they are **transaction costs**. The famous profitable retail sellers keep only about **5–75% of the premium they sell**, and the low end of that range is about **0.3% of risk per trade**. That margin is about one tick of slippage, so it is consistent with our finding that roughly one tick per leg erases MEIC/METF. The one recurring edge that does not depend on execution is **holding short premium overnight (1DTE)**, not intraday. The intraday option risk premium is about zero or even favors buyers.

---

## 1. Who captures 0DTE profits

**Market makers and wholesalers: the spread.**
- Beckmeyer–Branger–Gayda **[WP]**: 75%+ of retail S&P 500 option trades are 0DTE. Retail lost **$241k/day** over Feb 2021–Sep 2023, and **$350k/day** after daily expiries began in May 2022. **About 60% of the loss is transaction costs** (>$90M of the $125M total), even after Cboe price improvement. Single-leg, debit, high-IV trades drive the losses. Multi-leg trades and volatility-premium-harvesting trades did better, and the median put/call spread earned +3.0%/+3.3% margin-adjusted. They also find market makers are **net short 0DTE** because they absorb retail buying. https://wp.lancs.ac.uk/fofi2024/files/2024/04/FoFI-2024-146-Leander-Gayda.pdf · https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4404704
- Bryzgalova–Pavlova–Sikorskaya, *J. Finance* 2023 **[PR]**: retail lost **$2.1B** (Nov 2019–Jun 2021, 10-day horizon). Aggregate distance-to-mid cost was **$6.4B**, and indirect costs (spread) far exceeded the ~$0.9B of direct commissions. Retail prefers cheap weeklies with **12.6% average spreads**, and losses concentrate in short-dated contracts. Options PFOF was ~$2.4B vs $1.3B for stocks in 2021 (per BPS, as cited in the secondary source below). https://onlinelibrary.wiley.com/doi/full/10.1111/jofi.13285 · https://www.jbs.cam.ac.uk/wp-content/uploads/2022/06/2022-ccaf-conference-version-paper-bryzgalova-pavlova-sikorskaya-final.pdf
- Rebuttal, Amaya–Garcia-Ares–Pearson–Vasquez 2025 **[WP, Cboe-funded data grant]**: with true trade direction and expiration values, Cboe customer SLIM trades net **+$0.34M/day (t=0.29, not significant)**. The **intraday horizon is still a significant loss (~$1M/day)**. They argue Beckmeyer's auction proxy covers only 4–6% of SPX volume. Read it as "retail is roughly break-even net, and intraday round-trips lose." https://cdn.cboe.com/resources/education/research_publications/Retail_Profitability.pdf
- Bogousslavsky–Muravyev **[WP]**: trader-level data from a trading-journal provider covering $15B of trades and about 3,000 traders. Overall losses are small, but **0DTE trades lose about 4.6% per trade while other option trades earn about 0**. 93% of 0DTE trades are purchases, and **naked option selling shows "large gains"**. They speculate that the use of limit orders explains why losses are smaller than the spread. https://www.lsu.edu/business/files/event-files/2025-finance-mardi-gras/retail_option_trading_v2.pdf
- Market-maker scale **[EX]**: Citadel Securities says it handles ~35% of US-listed retail volume, that ~half of its retail options volume is now 0DTE, and a record ~$6.8B/day of retail premium in Jun 2026. I read this through search snippets because the page returned 403 to my fetch. https://www.citadelsecurities.com/news-and-insights/global-market-intelligence/1h-2026-market-structure-flows/ . A secondary analysis puts Robinhood options revenue at $0.44/contract and argues "in the index, the money is in the spread and the hedge mostly nets" **[VEN/blog]**: https://www.navnoorbawaresearch.com/p/how-market-makers-like-citadel-securities
- Cboe **[EX]**: SPX 0DTE customer flow is "extremely balanced" (14.95% of volume is customers buying, 14.06% customers selling). Market-maker net gamma hedging is ≤0.2% of SPX liquidity. Over 95% of 0DTE trades are defined-risk. Retail is 50–60% of SPX 0DTE volume (54% in May 2025). https://www.cboe.com/insights/posts/0-dt-es-decoded-positioning-trends-and-market-impact · https://www.cboe.com/insights/posts/spx-0-dte-options-jump-to-61-share-on-retail-resurgence/ . Dim–Eraker–Vilkov **[WP]** find market-maker 0DTE gamma inventory is on average *positive*, which conflicts with Beckmeyer's net-short finding. https://dx.doi.org/10.2139/ssrn.4692190
- **Conclusion:** market makers mostly net their book and earn the spread and rebates. They do not need a directional or VRP view.

**Option sellers: the risk premium is real but small intraday and shrinking.**
- Almeida–Freire–Hizmeri **[WP, May 2025]**: the 0DTE VRP is high but **driven by upside (call) risk**. Mispricing was "highly profitable before 2022, but dissipates after the daily availability of 0DTEs." https://www.fma.org/assets/docs/Derivatives2025/Almeida.pdf
- Vilkov 2026 **[WP]**: after the Aug-2026 cost-unit correction, **no strategy or basket keeps a positive net Sharpe**. https://github.com/vilkovgr/0dte-strategies · https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4641356
- Bandi–Fusari–Renò **[WP]** estimate 0DTE risk premia with an Edgeworth-expansion pricer. I did not verify a cost-adjusted magnitude. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4503344
- Muravyev–Ni, *JFE* 2020 **[PR]**: SPX option returns are −0.7%/day overall, made up of **−1% overnight and +0.3% intraday**. Options are *overpriced overnight* and **underpriced intraday**, because prices do not reflect that intraday volatility is ~2.5× overnight volatility. A purely intraday 0DTE seller is on the wrong side of this anomaly. https://www.researchgate.net/publication/335771661_Why_do_option_returns_change_sign_from_day_to_night

---

## 2. The most verifiable profitable individual 0DTE traders
None of these records is audited. All are **[SELF]**.

| Trader | What they do | Record | Tail behaviour | Verifiability |
|---|---|---|---|---|
| **John Einar Sandvand** (Theta Profits) | SPX "Breakeven IC": 10–15Δ shorts, wings ~30 pts, $1–2 credit per side, a new IC about every hour. Stop on each side = total credit, **stop on the short leg only**, using a stop-limit plus a backup stop-market (OCO). Daily risk 1–2% of the account. | 9,100 trades, Apr 2021–Feb 2026. **40% win rate, PCR 5.65%, +0.28% of risk per trade** net of fees. 49/57 months positive. 2024: PCR 3.53%, +0.18%/trade. 2025 total account +39% (BEIC >50% of the profit). | Double-stop days rose to 8.6% and are the main loss driver. He reports an account drawdown of more than 50% in 2020, before 0DTE. | Blog statistics and slides only; he publishes no broker statements. https://www.thetaprofits.com/my-most-profitable-options-trading-strategy-0dte-breakeven-iron-condor/ · https://www.thetaprofits.com/my-options-trading-results-for-2025-what-actually-worked/ |
| **Tammy Chambless** (MEIC) | Six ICs a day, 30–60 min apart. Per-side stop = full IC credit. MEIC+ variant. Automated. | **CAGR 20.7%, max DD 4.31%** since Jan 2023 (her figures, as reported by Sandvand). | n/a | Video and Discord posts only; no audit. https://www.thetaprofits.com/tammy-chambless-explains-her-meic-strategy-for-trading-0dte-options/ |
| **BigERN** (Early Retirement Now) | Mostly **1DTE overnight SPX puts** (7.2% OTM, ~$0.17), plus small 0DTE puts (3.9% OTM) and calls (1.9% OTM) at ~$0.10. Stop-limits set at 10–15× premium. **10+ years.** | 2024: $95.9k net, **PCR 77.3%**. 2025: ~$90k net on $121k sold (~74%), 10k+ contracts, no losing calendar month since 2022. | 2024-08-05: "wiped out about four to five months of income" intraday, held through and kept the full premium. April 2025: "silly losses", but no losing month. | Detailed charts and logs, no broker statements. https://earlyretirementnow.com/2025/01/14/options-trading-series-part-13-year-2024-review/ · https://earlyretirementnow.com/2026/01/30/options-trading-series-part-14-year-2025-review/ |
| **Options Cafe** author (counterexample) | ATM SPX iron fly, every trade published | 2024 +$5.1k (77% win rate). **2025 −$5.5k (78% win rate)**, so roughly zero over two years. A single trade lost about $5k when he **skipped his stop** in January 2025. | Gave back all 2024 gains by month 3. | Public trade list. https://options.cafe/blog/zero-dte-spx-iron-butterfly-strategy/ |
| **"Captain Condor" / David Chau** (blow-up) | SPX iron condors with **martingale sizing** | About $50M lost in Dec 2025 on about $13M of premium; subscribers were wiped out. | n/a | Press coverage. https://en.wikipedia.org/wiki/David_Chau |

I found no Collective2 or audited SPX 0DTE record, and nothing on "Ron Bertino." Option Alpha publishes no audited live-bot P&L.

**Note on BigERN:** most of his P&L is overnight 1DTE put risk. That is the Muravyev–Ni overnight-premium side, not intraday 0DTE. His 0DTE legs sell calls, which is the side Almeida et al. find carries the VRP.

---

## 3. Operational differences practitioners cite, and the evidence for each

**(a) Selling vs buying.** This is the strongest-supported factor.
- Beckmeyer: debit and single-leg trades lose, while credit and multi-leg trades do better **[WP]**.
- Bogousslavsky–Muravyev: naked selling gains, purchases lose **[WP]**.
- Option Alpha data: 78% of positions among its profitable 0DTE users were neutral premium-selling trades (IC/IF win rates ~67–70%) **[VEN]** https://optionalpha.com/blog/0dte-options-strategy-performance
- Caveat: the intraday premium is small (Muravyev–Ni, Vilkov). Selling only works if costs are near zero.

**(b) Execution.** This is the margin that decides everything.
- **Size of the margin:** Sandvand keeps 5.65% of the premium he sells. On a ~$3 IC that is about **$0.17 per IC**, or about **$0.04 per leg** across 4 legs. That is **less than one SPX tick** ($0.05 below $3; https://www.marketdata.app/education/options/option-tick-sizes/). Giving up one tick on every leg turns his record to zero or negative. This matches our own backtest finding.
- **Spread and fill evidence:** about 60% of retail 0DTE losses are spread **[WP]**. Bogousslavsky–Muravyev attribute smaller losses to limit-order use **[WP]**.
- **Stops:**
  - Sandvand: stop on the short leg only, never on the spread. Spread stops "tend to have higher slippages," which is a qualitative claim; **he publishes no slippage numbers**. https://www.thetaprofits.com/stop-loss-on-credit-spreads-in-0dte-options-trading/
  - BigERN: stop-*limit* only, to avoid flash-crash fills.
- **Repricing:** Option Alpha "SmartPricing" starts at mid and walks toward the natural price in steps (3–5 tries, 5–20 s each). This is the industry pattern; there is no published fill-rate study **[VEN]** https://docs.optionalpha.com/tools/bots/smartpricing
- **SPX vs SPY:** SPX is cash-settled, European-style, and taxed 60/40 under §1256 **[VEN]** https://optionalpha.com/learn/spx-vs-spy-how-to-trade-the-s-p-500 . SPX has a $0.05/$0.10 tick and an exchange fee of **$0.57–0.66/contract** (https://files.alpaca.markets/disclosures/library/BrokFeeSched.pdf), against SPY's penny tick. The trade-off: SPX has fewer contracts per dollar but coarser ticks; SPY is cheaper per fill but has assignment and pin risk.
- **Fill quality:** I found no tastytrade or Option Alpha study that quantifies mid-fill rates.

**(c) Sizing and survival.**
- Sandvand risks 1–2% per day and rarely uses more than 50% of buying power.
- The Options Cafe author recommends ≤10% risk per trade, and he lost by breaking his stop rule.
- BigERN accepts "an occasional loss that wipes out 2–3 days of income" over a catastrophic one.
- On 2024-08-05 he survived because his position was small and far OTM (7–10%).
- Blow-ups come from leverage and martingale sizing: Captain Condor, and anecdotal accounts of $500k–$1M naked accounts wiped out in April 2025 (https://earlyretirementnow.com/2026/01/30/options-trading-series-part-14-year-2025-review/ comments).
- Evidence quality: anecdotal but consistent across sources.

**(d) Multiple entries.**
- MEIC and BEIC both spread entries across 6 or more times of day. The claimed benefit is that per-entry stop outcomes decorrelate, which lowers drawdown (Chambless claims a 4.3% max DD).
- **Evidence: [SELF] only.** Our own backtests found a gross edge but no net edge, so time-diversification reduces variance, not cost.

**(e) Avoiding FOMC/CPI days.**
- This rests on practitioner consensus ("Nick" avoids pre-FOMC; https://www.thetaprofits.com/0dte-iron-condor-success-how-nick-doubled-his-trading-account/) and blog claims that SPX moves ~2% on FOMC days vs 1.25% otherwise **[VEN]**.
- Option Alpha found Mondays best and Thursdays worst in its user data **[VEN]**.
- I found no out-of-sample test. **Weak evidence.**

**(f) Discipline and automation.**
- Every winner automates or pre-commits stops: Chambless, Sandvand, and Option Alpha bots.
- The only public-log loser we found broke his stop.
- This is consistent with our tail-control memory that the edge is the exits. Evidence is anecdotal but uniform.

---

## 4. What the losers do
- **Buy single-leg, high-IV contracts, especially puts.** 60% of Beckmeyer's losses come from 0DTE puts **[WP]**. 93% of 0DTE retail trades are purchases **[WP]**.
- **Pay the spread.** Retail weekly spreads average 12.6%, and the spread accounts for about 60% of losses **[PR/WP]**.
- **Trade intraday round-trips.** Even the Cboe-funded rebuttal finds a significant loss at the intraday horizon **[WP]**.
- **On the selling side,** losers skip stops, use martingale or oversized sizing, or sell naked with leverage (Options Cafe, Captain Condor).
- Reddit itself was not indexed in my searches. Beckmeyer et al. explicitly frame the 0DTE retail flow as WallStreetBets-driven, and the patterns above come from their data and the press.

---

## 5. Survivorship
- **Every name in §2 is self-selected and self-reported.** Theta Profits publishes success profiles ("Nick doubled his account"), so the denominator is invisible.
- The best population-level evidence is **Option Alpha's live-account data [VEN]**: **49.64% of its 0DTE users are profitable**, about a coin flip, with mostly SPY (81%). Only the ~20% with 100+ trades are net profitable, which is itself a survivorship filter.
- Bogousslavsky–Muravyev's naked-seller gains come from a sophisticated, journal-keeping subsample **[WP]**.
- Nothing I found gives a credible P&L distribution across many 0DTE *sellers*. Also, **Option Alpha's 49.64% figure would be the same under zero edge**, so it is not evidence of an edge.

---

## 6. Bottom line: ranked factors, evidence strength, and what Alpaca can replicate

| # | Factor | Evidence | Replicable on Alpaca? |
|---|---|---|---|
| 1 | **Don't pay the spread.** Limit orders at or near mid, patient repricing. | Strong (2 PR/WP papers say costs ≈ 60% of losses) | **Partially.** mleg orders are **limit or market only**, with TIF **day or gtc only** (no IOC/FOK), so a mid-walk has to be built from cancel/replace. Fill quality on Alpaca's routing is unmeasured. https://docs.alpaca.markets/us/reference/postorder |
| 2 | **Be the seller, defined-risk.** | Strong for sign; weak for net magnitude intraday | **Yes.** mleg supports 2–4 legs, all legs must be covered, and the limit is the net credit. https://docs.alpaca.markets/us/docs/options-level-3-trading |
| 3 | **Survive the tail:** small risk per day (1–2%), no martingale, far-OTM wings. | Moderate (consistent anecdote plus blow-up cases) | **Yes.** Pure sizing logic; we already have the floor and account cap. |
| 4 | **Hold premium overnight (1DTE) rather than purely intraday.** | Strong ([PR] Muravyev–Ni), plus BigERN's 10-year record | **Yes** structurally. It changes the risk to gap risk, which needs explicit sizing (memory: overnight gaps measured). |
| 5 | **Mechanical stops on the short leg** (stop-limit with a backup). | Weak to moderate ([SELF], no numbers) | **Partially.** `stop`/`stop_limit` exist **only for single-leg options**, not for mleg, and there is **no trailing stop** for options. A single-leg BTC stop on the short leg is possible. Whether OCO (stop-limit plus stop-market) is supported for options is **unverified**. https://docs.alpaca.markets/us/docs/options-trading |
| 6 | **Multiple entries through the day.** | Weak ([SELF]) | Yes (scheduling). It reduces variance, not cost. |
| 7 | **Use SPX** (60/40 tax, cash settlement). | Tax and settlement facts are solid. The P&L effect is fee-dependent. | **Live since 2026-09-02** (SPX, SPXW, XSP, VIX). **But Alpaca charges $0.50/contract plus SPX exchange fees of $0.57–0.66**, so ≈**$1.1–1.2 per leg**, or about $4.5–5 to open a 4-leg IC. Against a ~$3 IC ($300), that is ~1.5% of credit to open and up to ~3% if the shorts are bought back. Sandvand nets 5.65%, so **fees alone eat a third to half of the best published edge.** XSP has **$0 exchange fee below 10 contracts** but wider relative spreads. **Alpaca provides no index market data yet**, so SPX/SPXW quotes for mid-pricing need a third-party feed. https://alpaca.markets/blog/alpaca-launches-index-options-via-trading-api/ · https://alpaca.markets/blog/alpaca-introduces-index-options-paper-trading/ · https://files.alpaca.markets/disclosures/library/BrokFeeSched.pdf |
| 8 | **Skip FOMC/CPI days.** | Weak (no OOS test) | Yes, trivially. |

**Answer to the operator.** People who profit from 0DTE are either:
- **market makers** collecting the spread, or
- **careful sellers** who (i) post limits near mid, (ii) size for a −4-to-5-month-income day, (iii) never skip a stop, and, in BigERN's case, (iv) earn most of the premium **overnight**.

The published retail winners' net edge is roughly one SPX tick per leg, so whether you make money comes down to execution, fees, and staying alive in the tails. Strategy choice matters much less. On Alpaca, factors 2, 3, 6 and 8 are fully replicable. Factors 1 and 5 are only partly replicable: no stop on mleg orders, no IOC, and fill quality is unmeasured. SPX trading (factor 7) is now possible but carries about $1.1–1.2/leg in fees and needs an external quote feed. **The cheapest honest next test is:**
- Paper-trade an SPX/XSP IC with a mid-walk and record **realized fill vs mid per leg**.
- If the measured cost exceeds ~$0.04/leg on top of fees, no published 0DTE intraday seller's edge survives on this broker.
- An overnight 1DTE put-sale test (BigERN style) is the more promising research direction, given the [PR] day/night asymmetry.

## Sources
- Beckmeyer, Branger & Gayda [WP]: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4404704 · https://wp.lancs.ac.uk/fofi2024/files/2024/04/FoFI-2024-146-Leander-Gayda.pdf
- Bryzgalova, Pavlova & Sikorskaya, *J. Finance* [PR]: https://onlinelibrary.wiley.com/doi/full/10.1111/jofi.13285
- Amaya, Garcia-Ares, Pearson & Vasquez [WP, Cboe-funded]: https://cdn.cboe.com/resources/education/research_publications/Retail_Profitability.pdf
- Han [EX, Cboe]: https://cdn.cboe.com/resources/government_relations/Understanding-Retail-Investors-Dynamic-Trading-Behavior-in-the-US-Options-Market_2024.pdf
- Bogousslavsky & Muravyev [WP]: https://www.lsu.edu/business/files/event-files/2025-finance-mardi-gras/retail_option_trading_v2.pdf
- Muravyev & Ni, *JFE* [PR]: https://www.researchgate.net/publication/335771661_Why_do_option_returns_change_sign_from_day_to_night
- Almeida, Freire & Hizmeri [WP]: https://www.fma.org/assets/docs/Derivatives2025/Almeida.pdf
- Vilkov [WP]: https://github.com/vilkovgr/0dte-strategies
- Bandi, Fusari & Renò [WP]: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4503344
- Dim, Eraker & Vilkov [WP]: https://dx.doi.org/10.2139/ssrn.4692190
- Cboe [EX]: https://www.cboe.com/insights/posts/0-dt-es-decoded-positioning-trends-and-market-impact · https://www.cboe.com/insights/posts/spx-0-dte-options-jump-to-61-share-on-retail-resurgence/
- Citadel Securities [EX]: https://www.citadelsecurities.com/news-and-insights/global-market-intelligence/1h-2026-market-structure-flows/
- Option Alpha [VEN]: https://optionalpha.com/blog/0dte-options-strategy-performance · https://docs.optionalpha.com/tools/bots/smartpricing
- Sandvand [SELF]: https://www.thetaprofits.com/my-most-profitable-options-trading-strategy-0dte-breakeven-iron-condor/ · https://www.thetaprofits.com/stop-loss-on-credit-spreads-in-0dte-options-trading/ · https://www.thetaprofits.com/my-options-trading-results-for-2025-what-actually-worked/
- Chambless [SELF]: https://www.thetaprofits.com/tammy-chambless-explains-her-meic-strategy-for-trading-0dte-options/
- Early Retirement Now [SELF]: https://earlyretirementnow.com/2025/01/14/options-trading-series-part-13-year-2024-review/ · https://earlyretirementnow.com/2026/01/30/options-trading-series-part-14-year-2025-review/
- Options Cafe [SELF]: https://options.cafe/blog/zero-dte-spx-iron-butterfly-strategy/
- David Chau: https://en.wikipedia.org/wiki/David_Chau
- Alpaca: https://docs.alpaca.markets/us/reference/postorder · https://docs.alpaca.markets/us/docs/options-trading · https://docs.alpaca.markets/us/docs/options-level-3-trading · https://alpaca.markets/blog/alpaca-launches-index-options-via-trading-api/ · https://files.alpaca.markets/disclosures/library/BrokFeeSched.pdf
- Tick sizes [VEN]: https://www.marketdata.app/education/options/option-tick-sizes/
