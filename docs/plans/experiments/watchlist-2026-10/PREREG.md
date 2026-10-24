# Pre-registration (written 2026-10-22 before any outcome computed)
Universe: 647 unique BREAKOUT legs (TriggerArmed, staging_paper+prod_real deduped), 2026-06-22..2026-10-22.
Split: FIT = 2026-06-22..2026-08-31, TEST = 2026-09-01..2026-10-22. TEST read once per rule.
Trigger: first RTH 1-min bar whose CLOSE is beyond level in stated direction. Entry = that close.
  gap legs (first RTH bar OPEN already beyond) reported separately.
Outcome: signed underlying return at +5,+15,+30,+60 min and 15:55 close; MFE/MAE to 15:55.
Primary null: placebo legs = same ticker, same direction, same % offset from prior RTH close,
  applied to 20 random OTHER sessions of that ticker in the same split. Edge = real - placebo.
Inference: day-clustered bootstrap (2000), report n, mean, 95% CI for both halves.
Exit grid (phase 3, underlying-path): time stops {15,30,60,close} x stop {none,0.25%,0.5%,1%} x target {none,0.5%,1%,2%} = 64 variants. Count reported.
Cost: underlying 1bp/side for stock; option layer separately with spread model.
