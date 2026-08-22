# Plan — Position context correlator: chat + underlying + news vs what we hold

## The question

For every open position, answer continuously: **what is the price action, and why?** —
joining the underlying's tape, the option's own greeks, the news wire, and what the
Discord channels are saying.

## Feasibility: all four sources exist and are entitled (verified 2026-08-21)

| Source | State | Evidence |
|---|---|---|
| Discord **discussion** chat | LIVE, persisted | `dashboard.options_chat_message`, ch `786109983065505792`, 1801 msgs, **79 authors** |
| Discord **signal** chat | LIVE, persisted | ch `769797179992571914`, 50 msgs, 4 authors — the id embedded in every `entry_signal_id` |
| Underlying 1-min bars | Available, RICH | `/v2/stocks/{sym}/bars?timeframe=1Min` — TSLA 13:50Z bar had `n=4036` trades, `v=257210` |
| Option bars | Available but **SPARSE** | `/v1beta1/options/bars` — DRAM returned **2 bars for a whole session**, `n=1`, `v=1` |
| Option snapshot + greeks | Available | `/v1beta1/options/snapshots` — delta/gamma/theta/vega/IV + NBBO |
| News | Available | `/v1beta1/news` — Benzinga; today's TSLA recall headline returned |
| **Premium history** | **NOT STORED ANYWHERE** | no tick/quote/bar table in `dashboard`, `orchestrator`, or `exec_alpaca_*` |
| **Chat → ticker linkage** | **DOES NOT EXIST** | `options_chat_message` holds raw `content` only |

## Two findings that determine the design

### 1. For OUR positions, the option's own tape is useless — the underlying's is not

DRAM `270319C00100000` produced **two 5-min bars in an entire session**, one trade each.
An illiquid LEAP simply does not print. Meanwhile TSLA's underlying printed ~4,000 trades
*per minute*.

So the correlator must be **underlying-driven**, translating underlying moves into premium
impact via **delta** (DRAM delta ≈ 0.229; the TSLA 300P was ≈ −0.092), rather than trying to
read price action off a contract that barely trades. Reading the option tape directly would
show "no movement" on a day the underlying moved 3%.

### 2. There is no premium history, and none is being collected for unarmed positions

Premium ticks exist only as Temporal signals into a PositionWorkflow and are never persisted.
Worse, a subscription only exists while a trail is **armed** — so today, with DRAM unarmed,
**zero premium data is being collected for the only live position we hold.**

`trailingState` retains just `peakPremium` / `lastTickPremium`. Everything else is gone.
Any historical "what was the price action" question is unanswerable today and will stay
unanswerable for every past day. Collection has to start before analysis is possible.

## Hard constraints

- **Strictly read-only with respect to trading.** No workflow signals, no orders, no writes to
  `order_intent_journal` or `audit_log`. This is observability; it must not become a control path.
- **Must not starve the premium poll.** `pollOnce` already runs 2/sec/contract and already
  observes 429s from Alpaca. Any new polling shares that budget and must be additive-small
  and backoff-aware.
- **Must not live in `orchestrator`, `exec-*`, or the market-data poll loop.** A separate
  read-only job/service, so a bug here cannot wedge a trading path.
- **Chat is third-party content.** Authors are real people (79 in option-chat). Store and
  display it; do not republish it outward. Access is behind the operator allowlist (see below),
  which is fail-closed on an empty list.

## Cadence reality check

Peak chat volume today was **48 messages/hour** (≈0.8/min); most hours were single digits.
A 1-minute *poll* is cheap and fine, but a 1-minute *analysis* would be a no-op the large
majority of ticks. Design for a 1-min collector that short-circuits when nothing changed, and
trigger synthesis on a real event: a new message, a material underlying move, or a news item.

---

## Phase 1 — On-demand position context (no new infra, no storage)

A read-only tool that, for each open PositionWorkflow, assembles:
- position identity + entry premium + qty (from `positionState` / `order_intent_journal`)
- option snapshot: NBBO, IV, delta/gamma/theta/vega
- underlying 1-min bars for the session, plus % move and where the position's contract sits
  relative to strike (moneyness, DTE)
- **delta-implied premium impact**: `Δunderlying × delta × 100 × qty` vs actual premium change —
  the gap is what IV/theta did
- news for the underlying since entry
- chat messages mentioning the ticker or contract since entry, from BOTH channels

**Why first:** it proves the joins and shows which of these signals is actually informative,
before any storage schema is committed. Costs 3 REST calls per position.

**Done when:** running it against today's DRAM position produces the underlying move, the
delta-implied vs actual premium gap, the TSLA recall headline for a TSLA position, and any
chat lines naming the ticker — with zero writes to any trading table.

## Phase 2 — 1-min collector + storage (the part that unblocks history)

> **Sharpened by E7/E8.** E7 showed bars, option bars and news ALL backfill (2016 / Feb-2024 /
> 2024), so those experiments were never history-limited — they were method-limited, and 2.68M
> backfilled bars killed four strategies in ten minutes. **The genuine case for forward collection
> is now narrower and stronger: IV and greeks CANNOT be backfilled** (E8b — greeks exist only on
> live-contract snapshots). Every day without collection is a day of IV history permanently lost.
> Prioritise `mkt_option_snapshot` (IV + greeks, 1-min, per held contract) OVER
> `mkt_underlying_bar`, which can be fetched from Alpaca at any time.

New tables (own schema, `dashboard` DB — NOT an exec/orchestrator DB):
- `mkt_underlying_bar (ticker, ts, o,h,l,c,v,vw,n)` — 1-min, only for tickers we hold
- `mkt_option_snapshot (occ, ts, bid, ask, mid, iv, delta, gamma, theta, vega)` — 1-min
- `news_item (id, ticker, created_at, headline, source, url)` — dedup on Alpaca's id
- `chat_ticker_link (message_id, ticker, confidence, matched_via)` — the missing join

**Ticker extraction is the real work here.** Reuse the copytrade parser's symbol recognition
rather than writing a second regex — a divergent extractor would quietly disagree with the
signal path about what a message refers to.

Collector polls only tickers with an open position (today: 1). Backs off on 429 and never
retries into the premium poll's budget.

**Done when:** a full session of 1-min bars + snapshots exists for every held underlying, and
`chat_ticker_link` resolves a known signal message to its ticker.

## Phase 3 — An EXIT-side gauge (reframed 2026-08-21 after E3-E5)

**This phase originally proposed a narrative panel describing price action. E3-E5 redirect it:
build an EXIT gauge, not an entry one.** Entry-side momentum was measured and LOSES (E5); exit
timing carries the whole result across four independent tests.

Surface, per open position, in real time:

1. **Time-in-trade against the 107-minute median** — E3's sharpest cut is 4.5% loss-rate under 2h
   vs 50% at 2-8h. A position crossing ~2h without working is the single strongest distress signal
   in the data, and it needs no new data source: entry time is already in `positionState`.
2. **Adverse move since entry** (E4b) — underlying moved >= 0.5% against the option's direction.
   Needs only the underlying's live price and the entry stamp.
3. **Option drawdown from entry premium** — the untested variant of E4b that is likely BETTER than
   the underlying proxy, and is now cheap: option 1-min bars proved fetchable for these contracts.
4. **DTE bucket** (E3): 0-9 DTE returned +10-18% with <10% loss-rate; 45+ DTE returned -30% with a
   50% loss-rate. The live DRAM position sits at **211 DTE**.

**Display only. It must NOT place or modify an order.** These are hypotheses at n=43 with an
in-sample-selected threshold (E4b). The correct sequence is: surface it -> watch it against live
trades for a quarter -> only then discuss automation, through the human lane that P2 of
`/issues-drain` reserves for trading-critical paths.

**What it explicitly does NOT do:** drive entries. E5 tested that directly and it loses at every
threshold.

## BASELINE EXPERIMENT — run 2026-08-21. Re-run this verbatim against real history.

Two experiments on the data available today. **Both are negative or inconclusive, and both fail
for the SAME reason: not enough history.** Recorded here so they can be re-run identically in
three months and the results compared, rather than re-derived from scratch with a different
method (the #241/#738 failure mode: analysis re-done badly because nobody read the prior write-up).

Inputs: 1,804 option-chat messages (2026-08-11 to 08-21) and 115,084 1-min SIP bars across 15
symbols (MU SPY AAPL QQQ TSLA AMD INTC NVDA AMZN AVGO GOOGL ORCL META HOOD NBIS).

### E1 — Does chat sentiment predict the underlying? NO SIGNAL, UNDERPOWERED

320 mentions of tracked symbols; only **66 directionally classifiable** by keyword.

| bucket | n | 5m | 15m | 60m |
|---|---|---|---|---|
| bullish | 27 | -0.026% | -0.112% | -0.017% |
| bearish | 32 | +0.097% | +0.077% | +0.174% |

Every `|t| < 1.6`. The direction is INVERTED (bullish talk precedes weakness), which hints at a
fade/contrarian pattern — but at n=27 that is indistinguishable from noise.

**The finding is the power, not the result.** 10 days yields ~66 directional mentions, i.e.
~2,400/year. Detecting a small edge needs hundreds-to-thousands. This is an argument for starting
collection NOW and re-testing in three months. It is NOT evidence that chat is useless.

### E2 — Do classic TA signals predict? THREE CLEAR |t|>=2, ALL TOO SMALL TO TRADE

Baseline: n=102,441, mean 5m `-0.0005%`, mean 30m `-0.0027%`.

| signal | n | 5m mean / t | 15m mean / t | 30m mean / t |
|---|---|---|---|---|
| **RSI < 25** | 6,191 | -0.0049 / -1.46 | **-0.0217 / -3.65** | -0.0181 / -2.37 |
| **z < -2 (stretched down)** | 9,046 | **+0.0079 / 2.80** | -0.0028 / -0.58 | **-0.0188 / -2.82** |
| vol spike >3x & z < -1 | 1,688 | -0.0084 / -0.93 | -0.0260 / -2.01 | -0.0409 / -2.15 |
| z > 2 (stretched up) | 8,867 | -0.0073 / -2.09 | -0.0068 / -1.28 | -0.0063 / -0.90 |
| RSI > 75 | 6,308 | -0.0057 / -1.47 | -0.0109 / -1.99 | -0.0146 / -1.99 |
| sma20>sma60 cross-up | 8,753 | +0.0069 / 2.69 | +0.0074 / 1.56 | +0.0060 / 0.89 |
| vol spike >3x | 4,772 | +0.0001 / 0.02 | -0.0061 / -0.75 | -0.0028 / -0.25 |

Two readings worth carrying forward:

1. **Oversold keeps falling.** RSI<25 and volume-spike-down both predict CONTINUED weakness at
   15-30m. Momentum, not mean reversion — dip-buying these names at these horizons is the wrong
   side.
2. **`z < -2` flips sign with horizon**: +2.80 at 5m, **-2.82 at 30m**. A short-lived bounce that
   reverses. If real, the dip-buy window is ~5 minutes and becomes a losing trade held 30.

### E1b — SAME corpus, LLM classifier instead of regex. THE METHOD WAS THE BOTTLENECK.

Re-ran E1 with an LLM classifying per-ticker direction instead of keyword matching.
**Same 1,804 messages, same 10 days, same bars — only the extraction changed.**

| | regex | LLM |
|---|---|---|
| labelled ticker-mentions | 66 | **222** (3.4x) |
| direction of effect | **INVERTED** (bullish -> negative) | **correct** (bullish -> positive) |
| best `t` | 1.3 | **2.51** |

The regex was not merely sparse, it was **wrong-signed**. It missed `"MU to rocket"`, `"inv h&s out
of the wedge"`, `"AAPL sstrong"` (a typo defeats keyword matching), and — decisively — could not
split `"GOOGL sucked but I made it all back with SPY"` into GOOGL-bear + SPY-bull. Mixed-direction
messages were dropped or mislabelled, and those are common.

**Raw result** (market-adjusted vs SPY, overlapping windows):

| hor | bull n | mean% | t | bear n | mean% | t | spread |
|---|---|---|---|---|---|---|---|
| 5m | 112 | +0.034 | 0.93 | 59 | -0.011 | -0.22 | 0.045 |
| 15m | 110 | +0.064 | 1.11 | 59 | -0.165 | -1.96 | 0.229 |
| 30m | 109 | +0.140 | 1.78 | 59 | **-0.241** | **-2.30** | 0.381 |
| 60m | 106 | **+0.225** | **2.51** | 58 | -0.134 | -0.98 | 0.359 |

### E1c — ADVERSARIAL CHECK on E1b. Half the result was clustering.

Mentions cluster in time: ten people discussing AAPL within an hour are NOT ten independent
observations. Collapsing to one observation per `(symbol, hour, direction)` at the 30m horizon:

| | bull n | mean% | t | bear n | mean% | t |
|---|---|---|---|---|---|---|
| raw (overlapping) | 109 | +0.140 | 1.78 | 59 | -0.241 | -2.30 |
| **clustered** | 78 | +0.021 | **0.23** | 47 | **-0.228** | **-2.01** |

**The bullish signal evaporates** (t 1.78 -> 0.23) — it was clustering, not signal. The bearish
side survives. Permutation test on the clustered data, 20,000 label shuffles:
**observed bull-bear spread +0.249%, p = 0.0455.**

**What is claimable:** a **bearish**-only effect at ~30m, worth roughly **-0.23% market-adjusted**.
Bullish chatter predicts nothing once clustering is accounted for. The asymmetry is plausible
rather than convenient — bearish commentary here is reactive to something concrete
(`"AVGO got crushed"`, `"TSLA dumping hard"`) while bullish talk is often aspirational
(`"come on MU do it!!"`, `"pump it MU"`).

**What is NOT claimable, and must be stated whenever this is cited:**

- **The labels were produced by an LLM that knew it was hunting for signal.** Labelling used text
  only, with no sight of returns — but unconscious bias cannot be excluded. **A held-out set
  labelled BEFORE any returns are computed is required before this is believed.**
- **p = 0.0455 is one coin-flip from nothing**, across 4 horizons x 2 directions. Borderline, not
  established.
- **-0.23% must still beat the spread.** ~10x larger than E2's TA signals, which is real progress,
  but DRAM's quoted spread was 9.4% of mid. Unproven on the contracts actually traded.

### The transferable lesson

**The extraction method was worth more than any infrastructure change.** Same corpus, same ten
days: swapping regex for an LLM turned an inverted null into a marginally significant bearish
signal. This was tested directly against the alternative proposal (a graph database) and the
graph DB would have changed nothing — the limit was never the data model, it was that 256 of 320
mentions were being discarded or mislabelled.

Rank future effort accordingly: **extraction quality > sample size > storage engine.**

### E3 — What strategy do the option-alerts authors actually run? (43 round trips, ~3 months)

Source: `order_intent_journal` BTO->STC pairs, `copytrade-v1`, paper+live deduped by `signal_id`.
53 unique entry signals across 19 underlyings (38 calls / 15 puts); 43 matched round trips.
Underlying moves are SIGNED toward the option's direction (call: +up, put: +down).

**Profile:**

| | value |
|---|---|
| pre-entry 30m underlying move | **+0.19% mean / +0.25% median** -> they buy MOMENTUM, not dips |
| entry->exit underlying move | +0.41% median favourable |
| post-exit 60m underlying move | **+0.09%** -> they leave almost nothing on the table |
| hold time | **median 107 min**, mean 1,053 (a few multi-day holds skew it) |
| option round-trip P&L | **mean +9.16%, median +16.12%** |
| win rate | **83.7%** |
| entry hour (UTC) | flat 13:00-19:00, no time-of-day edge visible |

They enter on continuation, hold under two hours, and exit into strength. **Mean far below median
= a fat left tail.**

**Tail concentration — 7 trades erase half the book:**

| | n | mean | total |
|---|---|---|---|
| losers | 7 (16%) | **-53.5%** | **-374 pts** |
| winners | 36 (84%) | +21.3% | +768 pts |

**By hold time — the sharpest cut in the data:**

| hold | n | mean | loss-rate |
|---|---|---|---|
| **<2h** | 22 | **+13.4%** | **4.5%** |
| 2-8h | 6 | **-7.6%** | **50.0%** |
| 8-48h | 13 | +15.8% | 15.4% |
| >48h | 2 | **-30.5%** | 50.0% |

**By DTE — longer-dated is strictly worse:**

| DTE | n | mean | loss-rate |
|---|---|---|---|
| 0-2 | 11 | **+18.0%** | 9.1% |
| 3-9 | 15 | +10.1% | 6.7% |
| 10-44 | 15 | +7.0% | 26.7% |
| **45+** | 2 | **-30.3%** | **50.0%** |

Worst trades were all held long and/or long-dated: `SPCX 260821C` **-72.2%** held **384h** on a
-22.4% underlying move; `GOOGL 260821C` -75.0% held 28h; `SPY 260825P` -56.1% held 25h. The one
fast loser was a **0DTE** (`TSLA 260618P`, -69.6% in 18 minutes on a -0.68% adverse move) — gamma
and theta, not direction.

### CORRECTION to the entry-only reading

Measuring only the underlying's forward move from entry suggested **put signals are systematically
wrong**: 120m signed move -0.400%, `t=-2.21`, win 33.3%, versus calls +0.314%.

**Round-trip P&L contradicts that: puts +6.8% mean, calls +10.1% — both positive.**

Both are true, and the reconciliation is the finding: **puts DO drift adversely on a 120-minute
horizon, but the authors exit before it hurts.** Their exit discipline rescues the put trades. So
the edge lives in EXIT TIMING, not entry selection — which is exactly the opposite of where a
copytrade system naturally puts its effort.

Do NOT cite the entry-only put number as evidence the signals are bad. It measures a horizon the
authors never hold to.

### What E3 implies (hypotheses, not conclusions — n=43)

1. **Hold-time is the dominant risk factor.** <2h loses 4.5% of the time; 2-8h loses 50%. If that
   survives more data, a time-stop near the 107-minute median hold is the single highest-value
   control — and it is testable against existing history at zero cost.
2. **DTE >= 45 is a distinct, worse regime** (n=2, so weak) — but it matches the live book: the
   DRAM 270319C position is **211 DTE**, sitting in the bucket with a 50% loss rate.
3. The 16%/84% split mirrors the exit-policy P&L review (`operator force_close +$57.7k carries the
   book; stop_loss 0% win`). Same shape from an independent dataset.

**Limits:** n=43 pairs, n=2 in two buckets. Many cells tested. Paper and live pooled. Option P&L is
fill-to-fill so it DOES include real spread cost — unlike the underlying-move figures elsewhere in
this document.

### E4 — Entry timing and early-exit rules, tested against the same 43 round trips

Two questions, opposite answers. Both used the authors' ACTUAL exit as the endpoint, so only the
entry (or an early cut) varies.

#### E4a — "buy the dip within the momentum" — WORSE, in every configuration

Wait up to W minutes for the underlying to retrace D% against the option's direction, enter there,
skip the trade if the dip never comes.

| dip | wait | taken | skipped | mean taken | TOTAL | **mean of SKIPPED** |
|---|---|---|---|---|---|---|
| *baseline (enter at signal)* | - | 43 | 0 | -0.107% | **-4.61** | - |
| 0.10% | 60m | 28 | 15 | -0.150% | -4.21 | **+0.264%** |
| 0.25% | 30m | 16 | 27 | -1.094% | -17.51 | **+0.660%** |
| 0.50% | 30m | 9 | 34 | -2.498% | **-22.48** | **+0.677%** |

**Not one of nine configurations beat entering at the signal, and it degrades monotonically with
dip depth.** The last column is why: trades that NEVER dipped returned +0.26% to +0.68%; trades
that did dip returned -0.15% to -3.70%.

**The dip is not a discount — it is the trade failing.** Waiting for a pullback filters you INTO
the losers and OUT of the winners. Pure adverse selection, and it follows directly from E3: the
edge is momentum continuation, so an entry that immediately retraces is failed momentum.

#### E4b — the inverse: cut early when the entry goes against you — BETTER

Same 43 trades. Flag a trade if the underlying moves adversely by >= threshold within a window.

Every one of nine configurations separated winners from losers:

| window | thresh | flagged | flagged mean P&L | unflagged mean P&L |
|---|---|---|---|---|
| 10m | 0.30% | 8 | -12.83% | +14.19% |
| 20m | 0.50% | 7 | -19.12% | +14.66% |
| **30m** | **0.50%** | **9** | **-21.83%** | **+17.37%** |

**Option prices at the cut moment were MEASURED, not assumed** (`/v1beta1/options/bars`, 1-min,
at the flag minute) — an earlier draft of this analysis guessed a flat -10% and that guess was
load-bearing, so it was replaced with real fills:

| contract | entry | at cut | exit | cut P&L | actual | saved |
|---|---|---|---|---|---|---|
| SPCX 260821C | 3.42 | 3.40 | 0.95 | **-0.58%** | **-72.2%** | +71.6 |
| GOOGL 260821C | 3.24 | 2.70 | 0.81 | -16.64% | -75.0% | +58.4 |
| TSLA 260618P | 1.12 | 0.91 | 0.34 | -18.75% | -69.6% | +50.8 |
| DRAM 260717P | 2.35 | 2.31 | 1.84 | -1.84% | -21.8% | +20.0 |
| NVDA 260727C | 3.10 | 2.79 | 2.20 | -10.00% | -29.0% | +19.0 |
| NVDA 260720C | 2.20 | 1.98 | 2.65 | -10.00% | **+20.5%** | **-30.5** |
| NVDA 260706P | 1.95 | 1.79 | 2.33 | -8.21% | **+19.5%** | **-27.7** |
| TSLA 260717C | 1.46 | 1.24 | 1.64 | -15.07% | **+12.3%** | **-27.4** |
| MSFT 260717C | 3.70 | 3.65 | 4.40 | -1.35% | **+18.9%** | **-20.3** |

**Book: +393.9 -> +507.9 pts (+29%).** It cuts **4 winners (-105.8)** to catch **5 losers
(+219.8)**. It only works because the losers are far larger — the same fat-left-tail asymmetry as
E3 and the exit-policy P&L review.

### THE CAVEAT THAT MATTERS MOST ON E4b

**The 0.5%/30m threshold was selected as the best of nine tested on this same 43 trades.** That is
in-sample optimisation and it is the single most likely thing here to evaporate out of sample.
**Treat +29% as an upper bound, not an expectation.**

What IS robust is the direction, not the number: **all nine configurations showed flagged worse
than unflagged**, which is far harder to produce by chance than one lucky cell. And it converges
with E3's independent hold-time cut (4.5% loss-rate under 2h vs 50% at 2-8h). Two different
measurements, one conclusion: **trades that do not work quickly do not work at all.**

### Cross-check before any of this is implemented

- Re-select the threshold on one period and test it on a held-out period. If the best threshold
  moves a lot, the rule is curve-fit.
- n=9 flagged. Four of them being winners means the rule is crude; a version using the OPTION's own
  drawdown rather than the underlying's may separate better and is now cheap to test, since option
  1-min bars proved fetchable for these contracts.
- This interacts with the existing chandelier trail and the #747 gap-protection issue. It is an
  ENTRY-side stop, not a replacement for either.

### E5 — Would a real-time MOMENTUM GAUGE driving entries have worked? NO. It loses.

The premise behind a live momentum gauge is that E3 showed the authors entering on continuation
(pre-entry +0.19%/+0.25% signed). If that is the edge, a mechanical detector should reproduce it.

**Mechanical gauge** on the same 15 names, 10 continuous days, 96k bars: trigger when the
underlying has moved >= X% over 30m, enter signed toward the move, hold 107m (the authors' median).
Clustered by (symbol, hour) so overlapping windows do not inflate `t`.

| trigger | clustered mean | t | win% |
|---|---|---|---|
| 0.20% | -0.030% | -1.76 | 46.6 |
| **0.30%** | **-0.049%** | **-2.20** | 46.5 |
| 0.50% | -0.058% | -1.85 | 47.5 |
| 0.75% | -0.085% | -1.76 | 47.9 |
| 1.00% | -0.110% | -1.64 | 49.8 |

Unconditional baseline: -0.0029%.

**Negative at EVERY threshold, win rate BELOW 50% at every threshold, and worse as the momentum
requirement strengthens.** On these names at this horizon momentum mildly MEAN-REVERTS —
consistent with E2 (oversold kept falling; stretched-up faded).

#### The apples-to-apples comparison — this is the finding

Identical treatment: signed underlying move, fixed 107-minute hold.

| | mean | t | win% |
|---|---|---|---|
| mechanical momentum gauge | -0.030% | -1.76 | 46.6% |
| **author entries, fixed 107m hold** | **+0.111%** | **+0.74** | **45.3%** |
| **author entries, THEIR actual exit** | **-0.107%** | -0.19 | **83.7%** |
| *median* (their exit) | **+0.407%** | | |
| **author OPTION round-trip** | **+9.16%** | | **83.7%** (median +16.12%) |

Author entries beat mechanical (+0.111% vs -0.030%) but **`t=0.74` — not significant** — and the
win rate is effectively identical (45.3% vs 46.6%).

Then the third row: **same entries, same trades**, exiting when THEY exit moves the win rate
**45.3% -> 83.7%**, on a mean underlying move that is still NEGATIVE.

**The entry is worth little. The exit is worth almost everything.**

The mechanism is the mean/median split: underlying move to their exit is **-0.107% mean but
+0.407% median**. They convert a slightly-negative-mean process into an 83.7% win rate by exiting
into the favourable part of the path, and option leverage turns a +0.41% median underlying move
into a **+16.12% median** on the contract.

**Caution:** n=53 entries; the author-vs-mechanical gap is NOT significant. "Their entries add
nothing" is not established — only that evidence for entry skill is weak while evidence for exit
skill is strong and consistent across four independent tests (E3, E4a, E4b, E5).

### Where all six experiments converge

| experiment | finding |
|---|---|
| E3 | loss rate 4.5% under 2h vs **50%** at 2-8h |
| E4a | dip-entry loses in 9/9 configs — the retrace IS the failure signal |
| E4b | adverse-move cut turns -196 pts into -82 pts on the flagged set |
| E5 | entry selection ~= mechanical; **exit timing carries the entire result** |
| E6 | their exit beats 5 of 6 mechanical rules; only an early-adverse cut wins, and only by truncating the tail |
| E7 | **out-of-sample: 4 of 5 mechanical strategies died** (`t=20.46 -> 0.66`); multi-timeframe made overfitting easier, not filtering better |
| E8 | real fills cost only 0.84% mean — the cut rule survives at +77.8; **IV/skew is the one family that cannot be backfilled** |
| E9 | out-of-sample: the cut rule keeps ~20% of its in-sample gain (+9.8 pts); threshold unstable; NOT a live control at n=22 |
| E10 | **entry filters all fail out of sample** — the tail is not predictable at entry, only boundable after it |

**Trades that do not work quickly do not work at all — and the exit, not the entry, is where the
money is.** Five independent measurements, one conclusion.

**But E6 sharpens it:** the authors' exit is already good and beats 5 of 6 mechanical replacements.
The opportunity is **bounding the ~16% of trades that become -53% losers**, not out-trading a
107-minute exit that works. Every remaining idea in this document should be judged against that
one target.

**The cheapest actionable item in the whole document:** the existing chandelier trail runs a 45%
giveback; E6 measured **30%** as marginally better on this sample. That is a config change to
code that already ships, not a new system.

### E6 — Keep their entries, replace the exit with a mechanical rule? MOSTLY WORSE.

The natural synthesis after E5: we copy their entries anyway, so swap in a mechanical exit.
Tested against **real option prices** — 16,017 option 1-min bars covering 41/43 contracts
(median 363 bars each) — not a delta proxy. Same entries, only the exit varies.

| exit rule | total | mean | median | win% | med hold |
|---|---|---|---|---|---|
| **AUTHORS' ACTUAL EXIT** | **+393.9** | +9.16% | **+16.12%** | **83.7%** | 107m |
| adverse cut <=-0.5% within 30m | **+451.3** | +10.50% | +12.82% | 74.4% | **23m** |
| OPTION trail -30% off peak | +403.4 | +9.38% | +13.48% | 81.4% | 93m |
| OPTION trail -20% off peak | +370.3 | +8.61% | +12.82% | 74.4% | 93m |
| underlying trail -0.4% off peak | +357.9 | +8.32% | +12.50% | 72.1% | 66m |
| time stop 107m | +237.2 | +5.52% | +12.39% | 65.1% | 107m |
| **momentum flip: adverse<0 after 20m** | **+131.1** | +3.05% | +3.90% | **51.2%** | 20m |

#### The momentum-flip exit is the WORST rule tested

+131 vs +394, win rate **83.7% -> 51.2%**, median hold collapses to 20m. Exiting when momentum
turns negative fires almost immediately and removes you from trades that were merely breathing.

This is E4a's lesson from the other side: **a short-term retrace is noise, not information** —
unless it is large AND early, which is precisely what the adverse-cut rule isolates. So a
"momentum gauge" is wrong for entries (E5) AND wrong for exits (E6). The only momentum-shaped
thing that works is the narrow early-adverse filter.

#### Only one rule beat the baseline, and it is tail insurance — not edge

**Adverse cut: +451 vs +394 (+15%).** But look at HOW it wins: mean UP, **median DOWN**
(12.82% vs 16.12%), **win rate DOWN** (74.4% vs 83.7%). It does not make trades better. It
truncates the left tail. That is real value — E3/E4b showed 7 losers erase half the book — but it
is insurance, not alpha, and it is the rule whose threshold was **selected in-sample from nine
candidates** (see E4b caveat).

**The OPTION trail at -30% off peak (+403) is within noise of the baseline — and it is essentially
the chandelier trail that already exists**, just tuned. Live trails currently run a **45%**
giveback; **30%** would have been marginally better on this sample. That is a cheap, testable,
already-implemented knob, unlike everything else in this document.

#### What E6 settles

The authors' exit is genuinely good and **hard to beat mechanically** — 5 of 6 rules lost to it.
So E5's framing needs refining:

> **Their exits ARE the edge. The opportunity is not replacing them; it is bounding the losers
> they do not cut.**

Which is exactly what Phase 3's exit gauge is scoped to surface, and why display-only is the right
call: the gauge's job is to flag the tail, not to second-guess a 107-minute exit that works.

#### The cost this simulation does NOT charge

The adverse cut fires at a **median 23 minutes vs the authors' 107** — it exits ~4x sooner, so it
pays round-trip friction ~4x more often. Against measured live conditions — fill detection 30-90s,
and a spread of **9.4% of mid** on a held contract — that friction is NOT small relative to a
+15% total improvement. **A version of this that charges spread per exit could plausibly erase the
entire advantage.** Model it before believing the +15%.

### E7 — OUT-OF-SAMPLE test on 2.68M bars. FOUR STRATEGIES DIED. Read this before trusting E2.

Alpaca backfills 1-min stock bars to **2016**, option bars to **Feb 2024**, news to **2024** — so
the market-data experiments were never actually history-limited. Only the Discord corpus is
(mirror starts 2026-08-11, and backfilling it means self-botting the source account, which risks
**permanent** source loss — do NOT).

Backfilled **6 liquid names x 2 years = 2,679,053 bars** (172 MB, ~10 minutes). Fit
**2024-08 -> 2025-12**, test **2026-01 -> 2026-08**. Forward return at 107m (the authors' median
hold), signed toward the signal.

The authors' own language motivated the multi-timeframe variants — `"MU 5 min candle over 935"`,
`"QQQ weekly breakout"`, `"SPY 4h"`, `"MU hourly looking a little bear flaggy"`,
`"wanna see that 8ema retest"`, `"MU just got my daily bull cross"`.

| strategy | FIT n | FIT mean | FIT t | TEST n | TEST mean | **TEST t** |
|---|---|---|---|---|---|---|
| trend only (1h vs 4h EMA) | 1,011,437 | +0.0201 | **+20.46** | 493,527 | +0.0008 | **+0.66** |
| 2TF: mom30 + 1h/4h trend | 211,294 | +0.0262 | **+9.12** | 93,115 | +0.0005 | **+0.12** |
| 3TF: mom + trend + 15EMA | 178,569 | +0.0206 | +6.67 | 79,310 | -0.0057 | **-1.38** |
| **pullback to 15EMA in trend** | 174,232 | +0.0238 | +8.81 | 81,092 | **-0.0115** | **-3.28** |
| 1TF momentum 30m>0.3% (E5) | 327,924 | +0.0039 | +1.70 | 143,605 | +0.0028 | +0.93 |

#### Three conclusions, all load-bearing

**1. `t=20.46 -> 0.66` was BETA, not alpha.** 2024-08 to 2025-12 was a strong uptrend, so "be long
when trend is up" printed. The 2026 regime changed and it vanished entirely. An in-sample t-stat of
20 on a million observations proved *nothing about markets* — only about that period.

**2. Multi-timeframe confirmation did NOT filter noise — it made OVERFITTING easier.** 2TF/3TF
looked far better than 1TF in fit (9.12, 6.67 vs 1.70) and were no better or WORSE in test. More
conditions = more fitting, not more filtering. The only honest performer was plain single-timeframe
momentum: weak in both periods (1.70 -> 0.93). **Consistently weak is more trustworthy than
strong-then-gone.**

**3. Pullback-in-trend went SIGNIFICANTLY NEGATIVE out of sample (`t=-3.28`).** This is the closest
mechanical analogue to what the authors describe, and it is independent confirmation of E4a (dip
entry lost 9/9). Buying pullbacks on these names loses, and a trend filter does not rescue it.

#### What E7 does to the rest of this document

**E2's TA results are now SUSPECT.** RSI<25 at `t=-3.65` and `z<-2` at `t=-2.82` were measured on
**10 days with no out-of-sample check** — exactly the shape that just collapsed here. The stated
caution ("roughly one |t|>2 expected by chance") was UNDERSTATED. **Do not cite E2 as evidence of
anything until it is re-run with a fit/test split.**

E5 and E6 are unaffected in direction: a mechanical momentum system fails in every form tested
(single-TF, multi-TF, pullback), which strengthens rather than weakens E6's finding that the
authors' **exit** is the edge.

### MANDATORY from 2026-08-21: no signal is recorded here without an out-of-sample split

Any future experiment claiming a tradeable signal MUST report:

1. **A fit period and a disjoint test period**, with both t-stats shown side by side. An in-sample
   number alone is not a result — E7 produced `t=20.46` from pure regime exposure.
2. **Clustering treatment** (E1c: half a signal was overlapping windows).
3. **Transaction costs** — spread and fill latency. E2's effects were ~0.02% against a 9.4%-of-mid
   spread; E6's winning rule fires 4x more often than the baseline and was never charged for it.
4. **How many variants were tried.** E4b's threshold was best-of-nine; E7's strategies were 5-of-5
   reported, which is why its negative result is trustworthy.

The cheapest lesson in this document: **6 symbols and 2.68M bars cost ~10 minutes and 172 MB, and
killed four strategies that looked compelling on 10 days.** Run the split FIRST.

### E8 — Real transaction costs (measured), and what the options API actually exposes

#### E8a — the transaction-cost objection FAILS. My caveat was wrong.

E4b/E6 simulated exits at option **bar closes**, and every prior section warned that charging real
spread "could plausibly erase the entire advantage". **Measured against the option TRADE TAPE**
(actual executions at each cut minute, 25th-percentile print as a conservative seller's fill):

| | TOTAL pts | vs baseline |
|---|---|---|
| authors' actual exit | 393.9 | - |
| adverse-cut @ bar close | 479.0 | +85.1 |
| **adverse-cut @ REAL traded price** | **471.8** | **+77.8** |

**Median haircut 0.00%, mean 0.84%.** Real fills cost **7.3 of 85 points** — the advantage survives
at 91% of its simulated value. In 4 of 8 flagged trades the 25th-pct print EQUALLED the bar close,
and TSLA's traded ABOVE it (-3.85%).

**Why the earlier caveat was wrong:** it generalised from DRAM's **9.4%-of-mid** spread — an
illiquid 211-DTE LEAP — to contracts that are mostly liquid near-dated names with a tight tape.
Spread cost is contract-specific, and assuming the worst case across the book was not conservative,
it was simply inaccurate.

**Still NOT charged:** fill latency (30-90s historically; now measured at 319ms), and the fact that
the cut fires at a median 23m vs the authors' 107m, so it takes ~4x more round trips over time.

**A bug worth recording.** A first pass reported a 70% haircut on GOOGL and would have "confirmed"
the caveat. Cause: that contract was traded TWICE, and trade prints were keyed by contract alone,
so the second trade's window (prints ~0.81) merged into the first (true price 2.70). **Key by
(contract, minute).** The wrong version agreed with my prior, which is exactly when to check hardest.

#### E8b — options API surface, and the one thing that cannot be backfilled

| endpoint | status |
|---|---|
| option bars 1-min, back to **Feb 2024** | works |
| option **trades** (historical tick) | works — used for E8a |
| option snapshots by explicit symbols, **live** expiry | **IV + full greeks + NBBO** |
| chain by underlying (`/snapshots/{sym}`) | 250 strikes, quotes/bars only — **NO greeks** |
| snapshots for **EXPIRED** contracts | no greeks |
| **historical** option quotes (NBBO time series) | **404 — does not exist** |

Building an IV surface = chain call to enumerate strikes -> symbols call for greeks. Two cheap hops.
Skew is immediately visible (TSLA 2026-09-18, spot ~347):

```
puts    300 -> IV 0.460    320 -> 0.431    340 -> 0.418
calls   350 -> 0.390       360 -> 0.392    380 -> 0.397
```

Downside puts carry **~7 vol points** over ATM calls — textbook equity skew, live and measurable.

**THE STRUCTURAL POINT: historical IV is NOT retrievable.** Greeks exist only on current snapshots
of live contracts. Unlike bars (2016), option bars (2024) and news (2024) — all of which E7 proved
are backfillable — **IV can only be ACCUMULATED GOING FORWARD.**

This inverts the argument for Phase 2. Every other experiment in this document failed for lack of
history **that already exists and could simply be fetched**. IV is the ONE signal family where
collection is the only path — and it is also the one family E7 has not falsified, because it was
never tested. (A historical IV series can be reconstructed by inverting Black-Scholes from option
bars + underlying bars, both of which backfill, but that carries model risk and is real work.)

### E9 — Out-of-sample verdict on the session's OWN best result: survives, shrunken, unstable

E7's discipline applied to E4b/E6/E8a — the adverse-cut rule, the only surviving positive claim.
Chronological split: FIT 2026-06-01 -> 07-10 (n=21), TEST 07-10 -> 08-18 (n=22). Real option
prices at the cut, authors' exit otherwise.

| config | FIT vs base | TEST vs base |
|---|---|---|
| **0.50% / 15m** | **+47.6** | **+16.1** |
| **0.50% / 30m** (the E4b pick) | **+47.6** | **+9.8** |
| 0.75% / 30m | -9.4 | +60.3 |
| 0.25% / 30m | -75.8 | -50.3 |
| 0.50% / 60m | -82.3 | -65.2 |

- **The fitted rule generalises at ~20% of its in-sample size**: +47.6 fit -> **+9.8 test**
  (+9% on the test baseline of +108.5, vs the +15%/+85pt headline before splitting).
- **Threshold stability is poor.** The best rule ON TEST (0.75/30, +60.3) was NEGATIVE on fit.
  Fitting on the second half would have picked a different threshold and told a different story.
  3 of 15 configs are positive in both halves; the 60m window is negative everywhere.
- **One genuine positive:** the survivors are ADJACENT (0.50/15, 0.50/30), not scattered. Real
  effects occupy contiguous parameter regions; noise wins are isolated. Weak evidence that the
  short-window / moderate-threshold corner is real.

**Verdict: direction credible, magnitude unknown, threshold untrustworthy. NOT a live control at
n=22.** The consistent story across E3/E4a/E4b/E6/E9 remains: damage announces itself early —
but pricing that insight needs more trades, which only come from trading.

### E10 — Can ENTRY selection make the copy consistent? NO — every filter fails out of sample.

The consistency problem is precisely 7 trades (16%) at -53.5% mean. E10 asked: is anything
observable AT ENTRY (equity- or option-side) that separates them?

**Single features: none separate.** Median overlap between disasters and the rest is 33-47% on
every feature tested (DTE, entry premium, premium-as-%-of-spot, moneyness, pre-entry aligned
momentum, entry hour). Notably, **strong aligned momentum at entry did NOT protect**: three of the
seven disasters entered with +0.69% to +0.95% pre-entry momentum — among the strongest in the book.

**Composite filters looked good pooled, then failed the chronological split:**

| filter | ALL 43 | FIT half | TEST half |
|---|---|---|---|
| skip deep OTM (mono < -10%) | **+65.1** | +76.8 | **-11.7** |
| skip DTE >= 45 | +60.5 | +72.2 | **-11.7** |
| skip 0DTE | -23.7 | -2.8 | -20.8 |

Every disaster that made "skip deep OTM / long DTE" look good (SPCX -72%, DRAM -22%) is in the
FIRST half; the test half's one deep-OTM trade was a WINNER. Skipping it would have COST money.
Same lesson as E7 and E9, third time: pooled n=43 supports any story; the split supports none.

#### The relationship, stated honestly

- **Entry side (equity or option): no exploitable relationship found.** Not momentum (E5, E7),
  not dips (E4a), not moneyness/DTE/premium (E10). The authors' entries work as a package with
  their exits; filtering their entries only removed winners out of sample.
- **Exit side: the only relationship that survived any split** — damage announces itself early
  (E9: +9.8 pts out of sample, weak and threshold-unstable, but directionally consistent across
  E3/E4a/E4b/E6/E9).
- **The tail is not predictable at entry on this sample. It is only boundable after entry.**

#### What "profit consistently on their BTO/STC" therefore means, on present evidence

1. **Copy entries UNFILTERED.** Every filter tested destroys value out of sample.
2. **Keep their exits.** They beat 5 of 6 mechanical replacements (E6).
3. The only credible improvement is a **wide, exit-side tail-bound** (early-adverse cut in the
   0.5%/15-30m corner) — expected value ~+9 pts per ~110-pt half (E9), too weak and unstable to
   automate at n=22. Surface it in the Phase 3 gauge; let more trades accumulate before wiring it
   to anything.
4. **Sizing, not selection, is the untested lever.** With disasters unpredictable at entry but
   boundable after it, per-trade size discipline (and the account cap already live) does more for
   consistency than any signal in this document. Sizing experiments need more trades to avoid the
   same in-sample trap.

### Why NONE of this is tradeable yet — the constraint that dominates everything

**The effects are ~0.02%. Options round-trip cost is 1-2 ORDERS OF MAGNITUDE larger.** The DRAM
contract held on 2026-08-21 quoted `3.05 x 3.35` — a **$0.30 spread on a $3.20 mid, 9.4%**. A
0.02% underlying edge is invisible beneath the spread.

Statistically detectable != tradeable. To matter, an edge must survive (a) delta leverage, and
(b) the cost of crossing the spread. **Neither was tested.** Any future work claiming a tradeable
signal must model both or it is not a result.

### Honest limits of E1/E2

- **10 days, one market regime**, 15 heavily-correlated tech names.
- **8 signals tested** -> roughly one `|t|>2` expected by chance alone. Nothing is out-of-sample.
- Bars are 1-min closes; no spread, no fill modelling, no slippage.
- Chat sentiment used crude keyword matching, not the copytrade parser.
- Forward returns skip windows with >45min bar gaps, so overnight moves are excluded — which is
  precisely where the 2026-08-19 SPY gap-through loss occurred.

**Re-run condition:** repeat both experiments verbatim once >=3 months of collected history
exists, then compare. Same code, same horizons, same t-stat bar.

## Explicitly out of scope

- **Any trading decision.** This never sizes, enters, exits, or arms anything.
- Sentiment scoring of chat authors as a signal. Phase 1 will show whether mentions even
  correlate; inventing a score first is the wrong order.
- Backfilling premium history before today — it does not exist and cannot be reconstructed.
  Underlying bars and news CAN be backfilled from Alpaca; option NBBO cannot.

## Operator decisions (answered 2026-08-21)

### Channels: **option-alerts AND option-chat** — both, and both are already captured

These map exactly onto the two channels already flowing into `options_chat_message`:

| Config name | Channel | ID | Authors | Messages |
|---|---|---|---|---|
| `Signals` | **option-alerts** | `769797179992571914` | 4 | 50 |
| `Discussion` | **option-chat** | `786109983065505792` | 79 | 1801 |

Default from `tenant-dashboard-bff/application.yml:102`; no k8s override, so both are live now.
**No new ingestion work is needed** — the correlator reads what is already there. option-alerts
is what we trade off; option-chat is the 79-author conversation and is where the "why" lives.

### Scope: **ALL TENANTS**, not tenant-scoped

Positions from every tenant are considered together, and the display is cross-tenant.

This changes the auth surface, and there is already a precedent for it: `requireAllowlistedOperator`
(`TenantContext:109`), gated on `operator.allowlist` and **fail-closed — an empty allowlist denies
everyone**. It is already used by `AdminTenantsController`, `TenantInvitesController`, and
`TenantDashboardRowsController`.

So the correlator's read endpoints go under an operator-allowlisted route (the `/api/admin/*`
family), NOT the per-tenant `X-Tenant-Id` path. Consequence to keep in view: a cross-tenant view
puts prod_real, prod-jinchul, prod-kipark and the paper tenants on one screen — that is the point,
but it means the display must label tenant on every row so a real-money position is never mistaken
for a paper one.

Position enumeration becomes namespace-wide (`WorkflowType='PositionWorkflow' AND
ExecutionStatus='Running'` with no `TenantStrategy` filter), which is the same query shape the
#776 recovery used.

### Retention: **indefinite** — measured, and space is not the constraint

**Symbol universe is small and known: 40 distinct underlyings ever traded.**

```
AAPL AMD AMZN ARM AVGO CRWD CRWV DELL DRAM GOOGL HOOD INTC IREN LRCX META MSFT MU NBIS
NET NFLX NOW NVDA ON ORCL PANW PLTR PLUG QCOM QQQ RIOT RKLB SMCI SNOW SPCX SPY TGT TSLA
TSM UNH WDC
```

**Measured quote rates (2026-08-21, live API):**

| Sample | quotes/10s | per min |
|---|---|---|
| TSLA 13:50Z (post-open) | 486 | 2,916 |
| TSLA 17:00Z (midday) | 330 | 1,980 |
| TSLA 19:30Z (pre-close) | 339 | 2,034 |
| **DRAM 17:00Z (illiquid)** | **564** | **3,384** |

Two counter-intuitive results worth keeping: the rate is **flat across the session**, and the
ILLIQUID name quotes MORE than TSLA — wide-spread market makers flicker constantly. Do not
assume small names are cheap to record. Working figure: **~2,500 quotes/min/symbol ≈ 1M/day**.

**Cost per approach** (~120 bytes/row, heap + index):

| Approach | per symbol/yr | all 40/yr |
|---|---|---|
| **1-min bars** | 13 MB | **535 MB** |
| **1-sec sampled NBBO** | 707 MB | 28 GB |
| Full tick/quote | 30 GB | **1.2 TB** |

Full tick is **~2,300x** the cost of 1-min bars and is never viable for the full universe.

**Disk after the 2026-08-21 image prune:**

| | before | after |
|---|---|---|
| Root fs used | 168 G (82%) | **50 G (25%)** |
| Free | 39 G | **158 G** |
| containerd | 140 G | ~20 G |
| Images | 958 | **42** (37 in use) |

**118 GB reclaimed.** The 10 Gi PVC is NOT enforced (`local-path` is hostPath-backed; `df`
inside postgres reports the full 218 G volume), so the real budget is node disk.

**Decision — collect two tiers, keep both forever:**

1. **1-min bars for all 40 symbols** — 535 MB/yr. At 158 G free that is measured in centuries.
   Backfillable from Alpaca, so history starts before the collector does.
2. **1-sec sampled NBBO for symbols currently held** — ~1.4 GB/yr at ~2 concurrent positions.
   60x the resolution of 1-min bars exactly where it matters.

Combined **under 2 GB/year**. Retention is set by usefulness, not space: **keep indefinitely, no
prune job.** (This removes the retention-enforcement item that a 1-year policy would have needed.)

**Full tick is deliberately NOT collected.** For the question this plan answers — what price
action is my position seeing — the delta-implied vs actual premium gap is a minutes-scale
phenomenon. Sub-second NBBO adds nothing 1-second sampling misses, at 40x the cost.

### Operational dependencies this creates

- **Image pruning must become routine.** containerd refilled to 140 G because `:latest` + a new
  digest per build accumulates ~920 dead images. Without a recurring prune the disk returns to
  82% regardless of what this plan stores. That is the actual space risk, not market data.
- **There is no database backup.** The only cronjobs are `stc-intent-alert` and
  `stc-intent-digest`; the sole backup artifact on the node is a KEK file from June. Accumulating
  years of market data on one un-replicated `local-path` volume raises the cost of losing it.
  **Fix backup before turning on multi-year collection**, or accept that the history is
  best-effort.

## Still open

- Whether option-chat mentions correlate with anything. Phase 1 answers it; Phase 3 is gated on it.
