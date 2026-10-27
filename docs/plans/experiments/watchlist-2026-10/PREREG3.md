# Pre-registration round 3 (2026-10-22, before outcomes): the author's OWN written rules
Source: author's "HOW TO READ THE WATCHLIST" post (pasted by operator 2026-10-22).
Legs: 647 author legs (drop 4 typo legs >10% from premarket close). Split FIT<2026-09-01<=TEST.
Entry confirmation C in {break: first 1m bar whose high/low trades through level, fill AT level (optimistic);
  m5: first 5m bar close beyond level, fill at that close; m15: same with 15m}.
First-30m F in {allow, skip: ignore crosses before 10:00; first qualifying cross at/after 10:00}.
Stop: a CLOSE back through the trigger on the confirmation timeframe (1m for 'break'), exit at that close.
Target: next S/R beyond entry in trade direction = nearest of {PDH,PDL,PMH,PML,PWH,PWL, round numbers
  (step 1 if px<100, 5 if <500 or index ETF, 10 otherwise)} at least 0.15% beyond entry; exit AT target when touched.
Else exit 15:55 close. Same-bar stop/target: stop first (conservative).
Variants: 3 x 2 = 6. Placebo: reviewer's placebo B (same offset from today's OPEN applied to 20 random other
  sessions' open, same ticker/half) with identical rules. Report gross and net of 10 bps underlying round trip.
Inference: day-clustered bootstrap CI of (real - placebo); studentized max-t over the 6 variants on TEST.
