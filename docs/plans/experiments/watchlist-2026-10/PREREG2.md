# Pre-registration round 2 (2026-10-22, before outcomes)
Trial budget: <= 120 variants total across all hypotheses; ledger in ledger.csv. Inference: day-clustered
bootstrap + Hansen-style max-stat (reality check) over ALL ledger variants within the TEST set; report deflated p.
MDE on 2026 data alone ~25-40 bps underlying (80% power) -> 2026-only results can only be "consistent with".
Step 1: level reconstruction. Candidates: PDH, PDL, PDC, PMH, PML (04:00-09:29), prior-week H/L, round numbers
(step 1/2.5/5/10 by price), today's open is NOT available premarket. Match tolerance 0.15%.
If a rule reproduces >=60% of levels, generate synthetic levels 2016-2025 on SPY/QQQ + top 10 single names
and test hypotheses there (FIT=2016-2021, TEST=2022-2025, 2026 real-levels = 3rd check).
Hypotheses (sign predicted):
 H-a break&continue (+) w/ vol filter: opening-30m range / 20d avg of same, top tercile only.
 H-b touch&reject (fade, +): high/low tags within 0.05% of level but close stays on near side; enter fade at touch.
 H-c retest (+): close beyond level, later 1m low (ABOVE) returns within 0.1% of level without closing back through
     by >0.2%, enter on next close moving away. Earliest 10:30.
 H-d anticipation (+): price within 0.5% short of level after 10:30, enter in level direction.
 H-e gap-through (+): open beyond level; enter at 09:35 close.
Exits: triple barrier grid stop {0.5,1.0}% x target {0.5,1.0,2.0}% x time {60m, close} = 12 per hypothesis.

## Amendment 1 (before any overnight outcome seen): author holds 13/16 overnight.
Add 2 exits per hypothesis: hold to NEXT session open, hold to NEXT session close (no barriers).
+10 variants per dataset -> budget 140. Recorded in same ledger.

## Bug fix 1 (found 2026-10-22 after decade results): H-a look-ahead.
volok uses the 09:30-10:00 range but entries could fire before 10:00. Fixed: H-a entry no earlier than the
close of the 09:59 bar (enter then if still beyond, else at the first later close beyond). Variant count unchanged.
