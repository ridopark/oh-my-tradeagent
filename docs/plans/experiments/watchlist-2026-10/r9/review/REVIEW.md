# Independent review: round 9 (H9.1 / H9.2 / H9.3)

**How I worked:** I rebuilt everything from raw data before opening any r9 RESULT, code or parquet file. The raw data was audit_log (SELECT only), Alpaca SIP stock bars, Alpaca option bars and the Nasdaq earnings calendar. My numbers were frozen in `review/MY_NUMBERS.md` first. My code is in `review/code/`. The temporary homelab helper `/tmp/rev9_rfetch.py` has been deleted, and the credentials never left the host.

**Bottom line:** every claimed number reproduces, and all three FAIL verdicts stand. Two things need correcting:
1. The 2026-07-01 legs (armed at 22:55 ET, after that session closed) are silently kept in the base. They supply the only FIT AGREE leg in H9.1.
2. The quoted MDEs (19/35/50 bps) are for a pooled mean. The pass rule tests a per-half difference, so true power is about 2-4x worse.

## 1. Base sample: HOLDS (with a 07-01 caveat)
- **Legs:** 719 TriggerArmed rows (staging_paper 647, prod_real 72). Deduping on (ET date, ticker, dir, trigger) leaves 647 legs over 81 dates. Of these, 309 fired, 16 were gaps and 322 never fired.
- **Match with events.parquet:** my legs match row for row. All 309 fired legs agree on fire bar, entry price and r60, to within 0.01 bps.
- **Gap legs:** their 12 `gap=1` legs plus 4 legs (`fired=0`, `gap=NaN`) equal my 16 gap legs. In those 4 the stock opened beyond the level and never closed beyond it. They are excluded from the base either way.
- **n:** 309 legs over 80 days (FIT 159/44, TEST 150/36). This matches the claim.
- **sd(r60):** 121.9 bps on 309 legs, where I truncate r60 at 16:00. On their 301 non-NaN legs (they set late fires to NaN) it is 123.3. This matches the claimed 123.
- **MDE 19/35/50:** reproduces as the iid figure 2.8·sd/√(n·share) = 19.4/35.4/50.1. The day-clustered version is 17.6/32.2/45.5, because intraday legs are slightly negatively correlated. So the claim holds either way.
- **MDE caveat (WEAKENED as a power statement):** those figures are for a pooled mean. The pass rule needs AGREE − SILENT to be significant within each half separately.
  - Per-half clustered SE: FIT 8.4, TEST 9.6, giving an all-legs MDE of 23 and 27.
  - For an AGREE-vs-SILENT contrast at H9.2's actual cell sizes, the realised CI half-widths imply an MDE80 of about 62 bps (FIT) and 38 bps (TEST). The analytic iid value is about 85-95.
  - The prereg line "MDE 19 bps" should not be read as the detectable effect for any of these hypotheses.
- **rclose is defined differently:** events.parquet's rclose uses the **15:55** bar close, not 16:00. 275 of 309 legs match the 15:55 price exactly; the rest are ties on flat prints. This is a legitimate exit convention but it is undocumented in PREREG9. My 15:59 close differs by up to 110 bps (TSLA 07-23).
- **2026-07-01 legs (unflagged leakage risk):** the 5 legs dated 07-01 were armed at 22:55:37 ET, after that session.
  - They are not a copy of the 07-02 digest. 07-02 has its own 09:22 digest with different levels (TSLA 438 vs 425).
  - Treating them as live on 07-01 assumes levels that could not have been acted on in real time. They may also have been set with knowledge of that day's tape.
  - 3 of them fired (TSLA, MSFT, AMZN) and they are needed to reach n=309/80 days. No r9 RESULT mentions them.
  - My primary treatment would exclude them: n=306, 79 days, sd 122.4, clustered MDE 17.8.

## 2. H9.1 author consensus: HOLDS (FAIL), with one correction
- **Independent BTOs: 25.** That is after excluding TradingTheTrend, `manual:*` ids, `tenant:*` authors and the hand-entered `ridopark` DRAM entry, then deduping on signal_id. The author split is TB22 7, Lars 5, beendoubleyou 5, sloth 5, Shakira T 2, Donald T 1, identical to the claim.
- **Tags:** FIT AGREE 1 / DISAGREE 0 / SILENT 158; TEST 1 / 0 / 149. This is identical to the claim, and it is the same whether I time signals by posted_at or by receipt time.
- **AGREE legs:**
  - MSFT 07-01 ABOVE: r60 −17.0, rclose (15:55) −54.0.
  - AMZN 09-21 ABOVE: r60 −12.2.
- **Correction:** the single FIT AGREE leg is a 07-01 after-session leg. If 07-01 is excluded, FIT has **0 AGREE**, so the FIT contrast is undefined rather than −10.8. The verdict does not change, because it fails either way. But the "1 per half" framing should say that the FIT observation is a leg that was not tradable in real time.
- No timezone or dedupe problems found. posted_at is UTC and is compared against a correctly converted trigger time.

## 3. H9.2 early option flow: HOLDS (FAIL)
- **My random sample:** n=40 from the 172 legs that fired at or after 09:45, using seed 20261023, drawn on leg keys only.
- **My method:**
  - Expiries were inferred from listed option bars. The paper-api contracts endpoint returns 401 for data keys. Their code uses `api.alpaca.markets/v2/options/contracts`, which works.
  - Strikes are on a 0.5 grid within ±5% of the official open, over the nearest 2 expiries on or after the session.
  - Bars were fetched only for 09:30-10:30 of the trade date and re-filtered to that ET date. **0 off-date bars.**
  - The baseline is the trailing 20 sessions over the same clock window.
- **Comparison with their tags:** 39 of my 40 legs are in their base. The 40th, GOOGL 10-12, fired on the 09:44 bar, so their `fire_min >= 15` rule drops it. That is a boundary interpretation, and they have 170 legs vs my 172.
  - **Tags agree 39/39** (5 AGREE, 5 DISAGREE, 29 SILENT).
  - |z| matches to 2 decimals on every leg. They store z already signed by leg direction.
  - Their remote code also filters bars to the trade date (`t.date() != d` → skip). No option-bar date mixing.
- **My sample's tag mix** is 12.5% AGREE, unlike their full sample's 23%. The tags are identical leg by leg, so this is sampling variation (about −1.8 sd hypergeometric), not a tagging difference.
- **Contrasts on my sample (r60, CR1 day-clustered):**
  - AGREE − SILENT: FIT +40.1 [−9.3, +89.6], TEST +27.6 [−5.1, +60.2].
  - AGREE − DISAGREE: FIT +19.8, TEST +29.0.
  - These rest on 2-3 AGREE legs per half, so they are not informative on their own.
- **Their full-sample contrasts recomputed** with my r60 and CR1 clustering:

| half | contrast | mine | 95% CI | claimed |
|---|---|---|---|---|
| FIT | AGREE − SILENT | +12.4 | [−31.6, +56.3] | +12.4 |
| TEST | AGREE − SILENT | +5.1 | [−21.4, +31.6] | +5.1 |
| FIT | AGREE − DISAGREE | −31.0 | | −31.0 |
| TEST | AGREE − DISAGREE | +16.4 | | +16.4 |

  The point estimates match exactly and the CIs are close to their bootstrap. Tag counts 24/4/64 and 16/3/59 match.
- **Sensitivities:**
  - 07-01 also feeds H9.2: TSLA and MSFT on 07-01 are both FIT AGREE. Excluding 07-01 gives FIT AGREE − SILENT = +10.0 [−38, +58].
  - Including the 8 late-fire legs with r60 truncated at 16:00 gives FIT +13.5, TEST +5.9.
  - The verdict is unchanged in every variant: CIs straddle 0 in both halves, and FIT AGREE − DISAGREE is negative.
- I agree with their own caveat that the tag is partly mechanical, since flow is contemporaneous with the move to the level.

## 4. H9.3 catalyst day: HOLDS (FAIL)
- **My method:** Nasdaq calendar from 06-10 to 10-30. Nasdaq gives `time-not-supplied` for every past report, so I inferred before-open vs after-close from the larger overnight gap. As a robustness check I also allowed both the report day and the next day as the reaction day.
- **Result:** of 647 legs, exactly 2 fall on a reaction day: **TSLA 2026-07-23 BELOW** and **MSFT 2026-07-30 ABOVE**. Both are FIT, and **0 are in TEST.**
  - My r60 for these legs is +382.8 and +155.6, against claimed +383 and +156.
- **Near misses, correctly excluded:** these are report days with an after-close release, not reaction days.
  - AMZN ×2 and AAPL (a gap leg) on 07-30.
  - TSLA 10-21.
- **Timing method:** their method (Benzinga headline timestamps) is better than my gap heuristic. My heuristic mislabels a few reports, e.g. TSM 07-16 and INTC 07-23, but none of those has a leg on either candidate day, so the count is unchanged.
- **SILENT exclusions:** their ±3-day EXCLUDED set is 19 fired legs vs my 14. That is because they also exclude around the Nasdaq report date, which is slightly stricter. This has no effect on the verdict.

## Bugs / leakage checklist
- **Dates and timezones:** OK. Trigger times are ET to UTC, and posted_at is UTC. All sessions are in EDT.
- **Dedupe:** OK for both legs and signals.
- **Gap rule:** OK. A first-bar open beyond the level is excluded. Those 4 never-fired legs carry gap=NaN, which is cosmetic.
- **Option-bar date mixing:** none found, in their code or in mine.
- **Outcome leakage into tags:** none. Flow windows end at the entry close, and the baseline uses prior sessions only.
- **Issue 1 (fix):** the 07-01 after-session legs are kept without disclosure. They affect H9.1's only FIT AGREE leg and 2 of the 24 FIT AGREE legs in H9.2.
- **Issue 2 (document):** rclose uses 15:55, not the close.
- **Issue 3 (correct the power claim):** the prereg's 19/35/50 bps MDE describes a pooled mean, not the per-half AGREE − SILENT contrast the pass rule uses. Realistic per-half MDE80 is about 40-90 bps, so "underpowered" is understated, not overstated.

## Does any conclusion change?
No. H9.1, H9.2 and H9.3 all FAIL as claimed, and nothing qualifies as a survivor to freeze forward. The corrections only strengthen the "uninformative / underpowered" reading:
- H9.1 has 0-1 usable AGREE legs in FIT.
- H9.3 has 0 AGREE legs in TEST.
- H9.2's small positive AGREE − SILENT estimates (+12 / +5 bps) are well below a realistic per-half MDE.
