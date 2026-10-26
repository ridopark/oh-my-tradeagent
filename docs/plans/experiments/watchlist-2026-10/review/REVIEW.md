# Independent review: watchlist-level edge study

All numbers below come from my own code, kept in this folder: r1_legs, r2_c1c2, r3_checks, r4_c4, r5_c3, r6_c5_decade, r7_nvda_lookahead (all `.py`).
Units are bps of underlying unless stated. Bootstraps are day-clustered.

## Verdicts

| Claim | Verdict | One line |
|---|---|---|
| C1 | **HOLDS** | Reproduced exactly. Fixing the placebo leaves real legs at or below placebo. |
| C2 | **HOLDS** | 0/64 reproduced. Still 0/64 with optimistic same-bar ordering. |
| C3 | **HOLDS (weakly evidenced)** | 44% vs 31% reproduced, but the placebo and "best rule 33%" have no code. |
| C4 | **HOLDS for "no survivor". WEAKENED for "candidate".** | RC p=0.061 is an artifact of the unstudentized RC. Studentized p=0.59. |
| C5 | MDE **HOLDS** only for round-1. Cost **WRONG/UNSUPPORTED**. | No spread data exists. Trade-implied spread is about 4.5x larger. Theta is ignored. |
| C6 | **WEAKENED** | Reproducible, but exits are handled asymmetrically. The sign flips with a defensible alternative. |

### C1: HOLDS
- **Reproduced.** Intraday-cross r60 is −6.3 [−24,+10] in FIT (n=154, 44 days) and −4.6 [−23,+15] in TEST (n=147, 36 days). This matches events.parquet exactly. ABOVE and BELOW are each ≈ 0 in both halves.
- **Placebo flaw is real.** Among fired legs, the original placebo gaps 48–53% of the time vs 3–4% for real legs (event_study.py:41,47). The cause: offsets are anchored to the prior close, so they ignore the premarket move the author sees.
- **The flaw does not rescue the claim.** I built two alternative placebos:
  - **B:** same offset from *today's 9:30 open*, applied to the open of other days. Gap rate 4.6%, matching real legs.
  - **C:** same day, level shifted ±0.5/1/1.5%.
- Real minus placebo, r60:
  - vs A: −9 (FIT), −8 (TEST)
  - vs B: −17 [−37,+2] (FIT), −5 [−25,+16] (TEST)
  - vs C: +2 (FIT), −20 (TEST)
- r5 in TEST is −12 [−22,−2] absolute and −11 to −14 vs placebos A/B (CI excludes 0). FIT r5 is −3 (n.s.).
- **Net:** there is no continuation. If anything, real levels show a slight short-horizon fade. "Same as placebo" slightly flatters the real legs.

### C2: HOLDS
- Rebuilt with independent numpy code: 0/64 variants are positive in both halves. Best-on-FIT is (15m, stop 1%, target 1%): +3.3 FIT → −10.2 TEST, identical to grid.py.
- With target checked before stop on the same bar (optimistic bound): still 0/64. Max FIT +3.3, max TEST +6.5.
- Even before costs, the best min(FIT, TEST) is only +1.9.

### C3: HOLDS, weakly evidenced
- **Reproduced.** 43.7% of levels sit within 0.15% of a standard level. By candidate: PMH 16.5%, PDH 11.4%, others ≤ 9%.
- **The placebo has no code.** levels_re.py contains no placebo, and "best rule 33%" is not reproducible from any file.
- **My placebos** (shuffled offsets from PMC, optionally snapped to the real level's round step): 30.5% and 31.3%. That puts the excess at about +13pp.
- **Untested hypothesis: round numbers.** 94% of levels are integers or .5, which the original never tested as a rule. My best round-number rule ("next round step beyond PMC", step scaled by price) hits 24%. No rule comes near the 60% gate.
- **Mild look-ahead.** levels_re.py:5 uses PMH/PML up to 09:29, but posts land between 09:12 and 09:24 (median 09:21). This favours matching, so the "not reconstructable" verdict is conservative.
- **Caveat.** "Not reconstructable" ≠ "random". The +13pp excess shows partial anchoring to PMH/PDH.

### C4: no survivor HOLDS; "only candidate" is noise
- **Reproduced.** e_gap next_close: +95.3 FIT (n=9), +168.2 TEST (n=7). That is 16 legs on 16 distinct days.
- **Why p=0.061 is not meaningful:**
  - **Wrong test.** evalh.py:15 runs White's RC on raw means. The bootstrap max is driven by the highest-variance variants: e_gap next_close / next_open and a_break_vol next_close are the argmax in about 20% of draws each. A small-n, high-variance variant can then look "significant" against everything else.
  - **Studentized result.** A Hansen-style studentized max-stat (which PREREG2 called for) gives e_gap next_close **p=0.59**.
  - **FIT is weak.** Median −25, 4/9 positive, t=0.52. Leave-one-out FIT mean drops to −31.
  - **Pooled test.** n=16, one-sided exact sign-flip p=0.13.
  - **Power.** MDE for n=7 with sd 244–554 is about 260–520 bps, so +168 is well inside noise.
  - **Junk leg.** The FIT set includes a typo leg (SPY ABOVE 554 with SPY at 752).
  - **Baseline.** Same tickers, same sign, 9:35 → next close: −24 to −30 (sd ~350–400).
  - **Out of sample.** Generic premarket-high/low gap-through in the decade data is −47.5 for NVDA (n=32) and about 0 for QQQ. No support.
- **"Min RC p 0.58 intraday" is ambiguous.** I reproduce it only as White's RC on an intraday-only family (0.578). Over the full 70-variant ledger, the intraday minimum is 0.92. Studentized, the intraday minimum is 0.39.
- **The real best candidate.** Studentized, the best variant is d_anticip (stop 1%, target 0.5%, 60m): +1.2 FIT / +6.6 TEST, TEST CI [+1,+13]. It is positive in both halves, but its RC p is 0.39. It is still not a survivor, but it is a better "candidate" than gap-through.
- **Contamination.** The 2026 H-a result is contaminated by the vol-filter look-ahead (bug 1). It still fails, so the conclusion is unchanged.

### C5: MDE OK for round-1; cost claim unsupported and likely ~5–10x too low
- **MDE (my numbers).** r60: clustered SE 8.3/9.8 → MDE80 23/27. rclose: SE 12.9/14.0 → MDE 36/39. "25–40" is correct **only for the round-1 intraday-cross arm** (n≈150/half, 36–44 days).
- **The MDE does not carry over to round 2.** It is about 30–60 for a_break_vol (n≈40–47) and about 260+ for e_gap.
- **No spread data exists.** author/opt holds trade bars only (no NBBO), and no script computes the 84 bps.
- **Trade-implied spread is much larger:**
  - One tick on the median premium ($2.15) is already 47 bps.
  - A Roll estimator on option 1-minute closes gives a median **378 bps of premium** (IQR 188–802; 106 contract-days, 33 with undefined Roll).
- **Converting to underlying requires dividing by delta:** spread% × premium / (Δ·S). With 378 bps, $2.15 premium, Δ≈0.4 and S≈200, that is about 10 bps per round trip. Computed without Δ, it is understated by 2–3x.
- **Theta is missing.** On the 5–11 DTE contracts the author trades, held overnight, theta is roughly 5–10% of premium per day, or about 15–30 bps of underlying. That is the same order as the MDE and larger than any observed intraday mean.

### C6: WEAKENED
- **What reproduces.** +7.05% (n=10) is only reproducible as: closed alerts, excluding RT01 (a chat trade with an inferred contract). Adding RT11 and RT14 at −99.7/−99.8 gives −10.8% (n=12).
- **Asymmetric treatment.** RT08 (AMD 640c) has "exit never stated". The author claimed "2.50 to 8.00" at 09:35 on 10-02, and the contract **printed 7.50 at the open and 11.45 high on 10-02**, so the claim was achievable. build_roundtrips.py models it as expiry, −99.6%. It is excluded from C6, while the two never-closed losers are included.
- **Sensitivity of the mean:**
  - with RT08 at +200: **+5.5%**
  - with RT08 at +200 and RT15 at −99: **−2.0%**
  - all 11 closed trades incl. RT01: **+19.4%**
- **Further problems:**
  - Equal-weighted % ignores size.
  - n is 10–14 with sd above 60pp, so the CI spans roughly ±40pp.
  - Partial-exit fractions are the extractor's reading of "partial" (see the build_roundtrips docstring).
  - RT05 and RT07 are labelled "high" confidence but include modelled `bar` exit legs.
- Follower slippage (alert price vs next print) has a median of about +1%, with RT09 at +11%.

## Bugs / issues

| # | Location | Issue | Impact | Changes a conclusion? |
|---|---|---|---|---|
| 1 | hyp.py:15 + 57–62 | **Look-ahead.** The H-a vol filter uses the 09:30–10:00 range, but 77% of 2026 H-a entries (66/86) happen before 10:00. | Decade NVDA H-a st.5/tg1/ts60 is +17 in both halves (n=444/297), **entirely from look-ahead**. Early entries on high-vol days: +21/+23. Late entries on high-vol days: −0.6/−7.3. Early entries on normal days: −10/−10. QQQ shows the same pattern. | Not for 2026 (H-a fails anyway). **Yes for any decade H-a claim.** Fix before reporting decade results. |
| 2 | hyp.py:62 | Threshold of 1.2 selects the top 21%, not the top tercile (the 2/3 quantile is 1.05). | Prereg deviation. | No |
| 3 | evalh.py:15 | White's RC on raw means instead of the Hansen/studentized max-stat in PREREG2. | Gap-through p: 0.061 → 0.59. | Removes the "candidate" |
| 4 | event_study.py:15, run2026.py, levels_re.py | No level sanity filter. Four typos: SPY 549/554 (07-13, SPY≈752), DELL 540 (≈434), AMD 965 (≈461). | SPY 554 enters the e_gap FIT set. Placebo A offsets of −27% to +109% generate junk placebo legs. | No (small) |
| 5 | event_study.py:41,47 | Placebo is anchored to the prior close, giving a fired-gap rate of 50% vs 4%. | Placebo intraday-cross subset comes from different days than real legs. | No. Gap-matched placebo B gives the same answer. |
| 6 | levels_re.py | No placebo code. "33%" and "best rule 33%" cannot be reproduced. PM window ends 09:29 but posts are ~09:21. | Reporting gap; mild look-ahead favouring matches. | No |
| 7 | author/build_roundtrips.py (RT08) | Unstated exit modelled as expiry despite the claimed exit price trading. Excluded from C6, while the expired losers are included. | Sign of C6 is fragile. | Weakens C6 |
| 8 | legs_raw.psv | 5 legs dated 07-01 have ingest ts 22:55 ET, after that session. 3 of them fire. Separately, author/watchlist_daily.psv shows 10-15 posted at 09:58 while legs were ingested at 09:20. | Possible misdating or post-open levels. Workflow ts is ingest time, not post time. | No (n=5), but verify post times |
| 9 | run_decade.py | Decade arm ran although the reconstruction gate (≥60%) failed. Only 3 of the 9 downloaded symbols were processed; PREREG2 called for SPY/QQQ + 10 names. | Outside prereg, so it is exploratory. Partial. | n/a |
| 10 | hyp.py:96–113 | run_overnight lacks the `i>=len(c)-1` guard. "Next session" is the next day with data. | Checked: no skipped days among e_gap legs. | No |

**Checked and found OK:**
- BELOW sign handling in MFE/MAE (event_study.py:34–36) and in barrier() (hyp.py:38–39).
- Stop-before-target ordering is conservative, and reversing it changes nothing.
- UTC→NY conversion: the whole 2026 window is EDT, and m spans 0..389.
- Dedupe gives 647 legs (= prereg), with no duplicate (d, s, dir).
- Horizon indexing.
- The White RC is correctly demeaned (just not studentized).

## Prereg compliance
- **PREREG.md:** followed (universe, split, trigger, placebo as written, bootstrap, 64 grid, cost).
- **PREREG2.md deviations:**
  - White instead of Hansen RC (#3).
  - Fixed 1.2 instead of the top tercile (#2).
  - Ledger filename.
  - Decade run despite the failed gate (#9).
- **Trial budget:** 70 per dataset, within the 140 limit. OK.

## What I'd test next
1. **Fix bug 1** by computing the vol filter from the trailing 20 days only, or gating entries to ≥10:00. Then rerun H-a on 2026 and the decade data.
2. **Re-run the reality check studentized**, with the family pre-split into intraday vs overnight. Report stepwise (Romano-Wolf) p-values.
3. **Replace option cost assumptions with NBBO quotes** for the author's contracts. Model cost as spread/(Δ·S) plus theta per hold day, then re-express the MDE in option-P&L terms.
4. **Short-horizon fade.** r5/r15 reversal at author levels vs placebo B, pre-registered on new data (from 2026-10-23).
5. **d_anticip on fresh data:** stop 1% / target 0.5% / 60m (the best studentized variant), pre-registered on forward data only.
6. **C6 sensitivity table.** Report every never-closed alert under three marks (claimed, expiry, mark-to-market at the author's last mention) instead of one selective choice.
7. **Level sanity filter:** drop legs more than 10% from PMC, and use actual post time, not ingest time.
