# H8.3 MARKET BIAS: result. FAIL (none of the 4 metrics passes; nothing is close)

Pre-registration: wl/PREREG8.md (H8.3). Code: r8/h83/h83.py (seed 83, B=10,000 day-resampling bootstrap). Raw output: r8/h83/out.txt.
Run with `uv run --no-project --with pandas,pyarrow,scipy python -I h83.py <wl dir>`.

## Data
- legs_raw.psv has 719 rows. After deduping on (d,ticker,dir,level) there are 647 legs. The 72 dropped rows are prod_real copies of staging_paper legs. Deduping on (d,ticker,dir) gives the same 647, so no ticker ever had two levels on the same side.
- **81 days have single-name legs, not ~90**: FIT has 45 (06-22..08-31) and TEST has 36 (09-01..10-22). close->next close has 35 TEST days because 10-22 has no next close.
- The bias is lopsided. Its mean is -0.47 and its sd is 0.61. 78% of days lean toward calls, 4% are neutral and only 19% (15 days) lean toward puts. Median 5 single-name legs per day. Most of the information in the signal sits in roughly 15 put-leaning days.
- All legs were armed between 09:12 and 09:29 ET, before the open, with one exception: on 2026-07-01 the legs were armed at 22:55 ET, after the close (the renderer-crash day). Using that day's bias for its own open->close return would be lookahead, so there is a sensitivity run that drops it. The conclusion does not change.

## Interpretation choices (fixed in code before the first run, not changed after)
- "PASS as above" means the H8.2 rule applied to H8.3. For a given metric: Spearman rho has the same sign in FIT and TEST, the 95% bootstrap CI excludes 0 in both halves, the TEST studentized max-|t| over the 4 metrics gives p<0.05, and the sign test hit rate leans the same way as rho in both halves. A sign-test hit means sign(bias) == -sign(return), i.e. puts predict a down day. Days with bias==0 or return==0 are excluded from the sign test.
- The max-T test uses joint day resampling on TEST days where all 4 returns exist (35 days, 10-22 dropped). That is why the SPY_oc TEST rho shows 0.116 (n=36) in the per-half table and 0.167 (n=35) in the max-T table.
- No amendments to the hypothesis, metrics, split or pass rule.

## PRIMARY: bias from single names (non-SPY/QQQ)
| half | metric | n | Spearman rho [95% CI] | sign hits/n (rate) [CI] | sign p |
|---|---|---|---|---|---|
| FIT | SPY o->c | 45 | -0.007 [-0.28, 0.27] | 20/43 (0.47) [0.32, 0.61] | 0.76 |
| FIT | SPY c->nc | 45 | +0.061 [-0.23, 0.33] | 17/43 (0.40) [0.26, 0.54] | 0.22 |
| FIT | QQQ o->c | 45 | -0.024 [-0.31, 0.29] | 19/43 (0.44) [0.30, 0.60] | 0.54 |
| FIT | QQQ c->nc | 45 | +0.026 [-0.27, 0.31] | 19/43 (0.44) [0.30, 0.60] | 0.54 |
| TEST | SPY o->c | 36 | +0.116 [-0.24, 0.46] | 16/35 (0.46) [0.29, 0.63] | 0.74 |
| TEST | SPY c->nc | 35 | +0.150 [-0.20, 0.48] | 10/34 (0.29) [0.15, 0.45] | 0.024 |
| TEST | QQQ o->c | 36 | -0.037 [-0.38, 0.34] | 15/35 (0.43) [0.28, 0.60] | 0.50 |
| TEST | QQQ c->nc | 35 | +0.018 [-0.32, 0.36] | 18/34 (0.53) [0.36, 0.69] | 0.86 |

TEST studentized max-|t| over the 4 (crit95 = 2.38): t = 0.93 / 0.85 / 0.27 / 0.10, giving max-T adjusted p = 0.73 / 0.77 / 0.99 / 1.00.

Pass rule per metric: no metric has a CI that excludes 0 in either half, and no metric has max-T p<0.05. **H8.3 FAILS.**

Every correlation is within about ±0.15 of zero. The point estimates do not even agree on the hypothesised sign: the hypothesis predicts rho<0 (puts lead to down days), and 5 of the 8 estimates are positive.

The one nominal sign-test p<0.05 is TEST SPY c->nc (10/34, p=0.024). It does not count, for three reasons:
- It points the *contrarian* way: put-leaning days were followed by up closes.
- It is unadjusted, and it is one of 16 sign/Spearman tests.
- In FIT it is 17/43 (p=0.22) and the Spearman CI there includes 0.

Treat it as noise.

Sensitivity (2026-07-01 dropped): the FIT rhos move by at most 0.03, TEST is unchanged, and the result is still FAIL.

## Trade version (primary bias): short SPY at the open if bias>0, otherwise long; exit at the close; 1 bp cost
| half | days | short days | mean bp/day [95% CI] | hit | long-only SPY o->c, 1bp |
|---|---|---|---|---|---|
| FIT | 45 | 9 | -3.4 [-17.5, +10.8] | 0.47 | -5.0 |
| TEST | 36 | 6 | -4.3 [-19.3, +10.2] | 0.44 | -4.2 |
| ALL | 81 | 15 | -3.8 [-13.8, +6.3] | 0.46 | -4.7 |

The bias is negative on ~80% of days, so this trade is mostly long SPY. It tracks the long-only benchmark, and both are slightly negative intraday over this window. TEST adds nothing over long-only (-4.3 vs -4.2 bp).

## EXPLORATORY (not part of the test): bias from SPY/QQQ legs only
The author nearly always posts both a call and a put leg on SPY/QQQ, so this bias is **exactly 0 on 91% of days**. Only 7 of 78 days are non-zero, and only 1 of those is in TEST.

The TEST numbers look significant: SPY o->c rho 0.24, CI [0.19, 0.46], max-T p=0.02; QQQ o->c is similar. **This is an artifact.** With 33 tied values and one non-zero day, the rank correlation is the rank of a single day's return. The bootstrap draws that miss that day give rho=NaN, which is where the "All-NaN slice" warning in out.txt comes from, and those draws are silently dropped, so the CI is conditional and meaningless. The sign test has n=6 in FIT and n=1 in TEST. Its direction is also positive (contrarian), the same as the noise above. Conclusion: no usable signal, and the SPY/QQQ-leg bias is not a meaningful variable on this sample.

## Power: what ~80 days can detect
- Spearman, two-sided, alpha=0.05, 80% power: minimum detectable |rho| ≈ (1.96+0.84)/sqrt(n-1). That is **0.42 in FIT (n=45)** and **0.47 in TEST (n=36)**. With the max-T critical value of 2.38, TEST needs **|rho| ≈ 0.55**.
- Sign test on ~35 TEST days: 80% power needs a hit rate of about **0.74**.
- Rough calibration: an rho of 0.1 between a daily signal and next-day index returns would already be an exceptional edge. This design can only find very large, implausible effects. A FAIL here means "no large effect", not "no effect". The upper CI bounds (~0.3 FIT, ~0.35-0.48 TEST) are consistent with a modest effect of either sign.
- The effective sample is even smaller than 81, because the bias varies mainly through ~15 put-leaning days. With the FIT-TEST-forward arbiter, an rho of 0.2 would need roughly 200+ days per half to detect.

## Verdict
H8.3 FAIL on the pre-registered rule. No metric has a CI that excludes 0 in either half, the TEST max-T p-values are all ≥0.73, and the point estimates do not agree on the hypothesised sign. The directional trade is indistinguishable from long-only SPY. The forward period (10-23 onward) can still be logged, but given the power figures above, a forward pass would need an implausibly large effect.
