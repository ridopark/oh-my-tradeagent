# Exploration — stream option premium over WebSocket instead of polling REST

2026-08-16. Worktree `options-ws-streaming`. **No code changed.** This is a feasibility read, not a plan.

## The question

Option premium is a **REST poll** today: one fixed-rate `snapshotQuote` task per open OCC, 2s default
(`AlpacaMarketData#pollOnce`, `startPremiumPoll`). Equities already stream over a WebSocket
(`ensureStockWs`). Should options move to the WS too?

## Why the recent plans point at streaming

Four of the last five premium-path commits are working around properties of *polling*, not of the market:

| what shipped | what it was actually fixing |
|---|---|
| `e7cc387` `lastQuoteStamp` resample-dedup | `pollOnce` emits on **every** poll with no dedup and takes its timestamp from the quote (`latestQuote.t`), so a stalled NBBO produces byte-identical ticks. Consecutive polls resampling *one* quote were being counted as *two* corroborating observations. **This is a polling artifact and only a polling artifact.** |
| `e7cc387` 35% deviation band | The band is wide *because the sampling interval is wide* — "option premium legitimately moves tens of percent between two 2s polls on a short-dated contract." Shrink the interval and the band can shrink with it. |
| corroboration hold | A genuine violent move is admitted "one poll (~2s) later rather than lost." On a 0DTE collapse the stop acts a full 2s late by construction. |
| `PLAN-…-premium-feed-silence-backstop` P3 | Its whole fork exists because **a poll cannot distinguish "feed dead" from "market quiet"** — a 429, a dead poll thread, a withdrawn bid and a dead feed all look identical: no tick. Its recommended option C literally spends an extra activity call probing REST to recover information the transport should have carried. |

Plus one ceiling polling can't raise:

- **Trail tightness.** A trailing stop can be no tighter than its sampling interval.

*(A second one — the REST budget — turns out **not** to be an argument for streaming. See
"Correction" below.)*

## Why it was removed last time — and why that reason is dead

`da492f3` (2026-06-23, #471) removed the options WS. Read the message carefully: **both causes were
client bugs, not service problems.**

1. Header auth instead of the in-band `{"action":"auth"}` message → the socket sat
   connected-but-unauthenticated and Alpaca honored no subscriptions.
2. The JDK `WebSocket.Listener` never overrode `onBinary`, so **every msgpack frame was silently
   dropped.**

Bug (1) is the *identical* bug `9ec7387` then fixed for the stocks WS — and `ensureStockWs` /
`StockWsListener` / `handleStockControlSuccess` are now a working, tested in-band-auth template
sitting in the same file. Bug (2) is a missing method plus a decoder.

So the honest summary is: **the options stream was never observed to be broken. Our client was, twice.**
That is a materially different starting position from June.

The class javadoc currently states the WS "never delivered ticks" as though it were a property of
Alpaca's feed. It should say we never successfully authenticated to it or decoded a frame from it.

## What a real attempt needs

1. **msgpack decode.** The v1beta1 options endpoint speaks **msgpack**; v2 stocks speaks JSON
   (`scripts/alpaca-ws-conn-check.py:22-25`, learned empirically). `org.msgpack:jackson-dataformat-msgpack`
   yields an `ObjectMapper` producing ordinary `JsonNode`, so `dispatchOptionWsMessage` can be a
   near-copy of `dispatchStockWsMessage`. New dependency; market-data has none today.
2. **`onBinary` with reassembly.** `WebSocket.Listener.onBinary` is fragment-oriented (`last` flag)
   and needs `request(1)` flow control — the existing `onText` handler is the shape to mirror.
3. **The padded-vs-compact OCC trap, again.** `snapshotQuote` already strips space-padding for the
   request *and* the response key because Alpaca rejects the canonical padded form. A WS repeats
   this on both legs: subscribe frames must carry compact, inbound `S` fields arrive compact, and
   `bySymbol` is keyed on the **padded** canonical symbol. Needs an explicit compact→padded map, or
   every inbound quote silently finds no listeners. This is the same class as `JooqOrderIntentJournal`
   / `16e4c6e` and it has bitten this repo twice.
4. **Single replica, now for two reasons.** Verified 2026-06-20: Alpaca's limit is one connection
   **per endpoint**, so stocks (`/v2`) and options (`/v1beta1`) coexist on one key, but a second
   market-data replica's duplicate connection is 406'd. Already true; a second socket doubles the
   surface.

## Alpaca's actual limits (researched 2026-08-16)

### Plans

| | Basic (free) | Algo Trader Plus ($99/mo) |
|---|---|---|
| options feed | `indicative` | **`opra`** |
| stocks feed | IEX | all US exchanges (SIP) |
| REST data | **200 req/min** | **10,000 req/min** |
| options WS symbols | 200 quotes | **1000 quotes** |
| equity WS symbols | 30 | unlimited |
| **connections per endpoint** | **1** | **1** |

The repo is on **Basic** today: `application.yml:23` defaults to `.../v1beta1/indicative`, and the
poll interval is explicitly "sized against Alpaca's data-REST limit (~200 req/min)".

### The connection limit — your memory is right, and it does not go away with money

> "The number of connections to a single endpoint from a user is limited based on the user's
> subscription, but in many subscriptions (or without one) this limit is **1**." — error **406
> connection limit exceeded**

**Algo Trader Plus does not raise it.** Paying for OPRA buys symbols and REST throughput, not sockets.

The saving grace is that the limit is **per endpoint**, which this repo already verified empirically
on 2026-06-20 (`alpaca-ws-conn-check.py`): stocks `/v2` and options `/v1beta1` coexist on one key, and
a second connection to the *same* endpoint is 406'd while the existing one survives. So:

- One options socket, serving **every tenant and every contract** — market-data is `replicas: 1`
  (`infra/k8s/53-market-data.yaml:26`) with a single **pod-global** data key. Architecturally this
  fits.
- exec's `AlpacaTradeUpdatesStream` is on `wss://api.alpaca.markets/stream` — the **trading** API, a
  different host. It does not contend.
- The real cost is that market-data is **pinned to one replica permanently**. No HA, and the ceiling
  is now structural rather than incidental.

### ⚠ The rollout hazard this exposes (already live, would double)

`infra/k8s/53-market-data.yaml` has **no `strategy:` block**, so it defaults to `RollingUpdate` with
`maxSurge` 25% → **1** at `replicas: 1`. The new pod starts **before** the old terminates, so every
deploy briefly runs two pods against the same data endpoint — a guaranteed 406 collision. This is
**already true for the stocks WS today**; adding an options socket makes it two feeds that flap on
every rollout. `strategy: {type: Recreate}` is the fix and it is worth doing **regardless of whether
this exploration proceeds**.

### 407 slow client — backpressure is documented, not theoretical

Alpaca's error table includes **407 slow client**: consumers that can't keep up are *disconnected*.
OPRA on an at-the-money 0DTE at the open is a different order of volume from indicative. The JDK
`WebSocket.Listener`'s `request(n)` flow control is what decides whether we take a 407. The poll had
backpressure for free; this is the axis where a naive port fails in production and not in tests.

Also relevant: **405 symbol limit exceeded** (1000 option quotes on Plus), and `*` wildcard subscribe
is **prohibited** for option quotes.

## ⟲ Correction to my own earlier argument

I previously listed the REST budget — ~6 concurrently-trailed contracts at 2s/contract against
200 req/min — as a ceiling only streaming could raise. **That was wrong.** Algo Trader Plus takes REST
from 200 → **10,000 req/min**, which at 30 req/min/contract supports **~330 concurrently-polled
contracts**. Buying OPRA raises the polling ceiling ~55× by itself.

This matters because it **separates two decisions that look like one**:

1. **Buy Algo Trader Plus.** Gets you real OPRA prices, kills the ~6-contract ceiling, and — since
   REST is no longer scarce — lets you simply *drop the poll interval*, say 2s → 500ms, for a 4×
   latency win. **Zero new code, zero new failure modes, reversible by a config value.**
2. **Then, separately, switch to the WS.** Buys sub-second granularity, removes the resample artifact,
   and makes silence-detection honest. Costs a msgpack dependency, a binary frame path, a
   compact/padded symbol map, a permanent single-replica pin, and a 407 backpressure risk.

Decision 1 delivers a large fraction of the value at a small fraction of the risk, and it is a
prerequisite for decision 2 anyway. **I'd buy the plan, re-tune the poll, measure, and only then
decide whether the socket is still worth it.**

## The price-quality question underneath all of it

Per `reference_premium_tick_mid_vs_bid`, the exit path **trades on the bid**. Today the trail is
armed off an **indicative** bid on the display path and a REST snapshot on the exit path. Moving to
OPRA improves that on both. This is a genuine correctness argument for the plan purchase that is
independent of transport.

### OPRA entitlement — one thing to confirm before paying

OPRA requires a signed Subscriber Agreement classifying each recipient as **professional or
non-professional**. Two facts about this deployment are worth putting in front of Alpaca first:

- market-data holds **one pod-global data key** and `replicas: 1`, so a single account's entitlement
  feeds every tenant.
- `/live` **displays** option premium to tenant users (`ccdd073`) — and `prod-kipark` /
  `prod-jinchul` are **other people's** accounts.

Today that displayed data is `indicative` (derived, free), so it's uninteresting. With real-time OPRA
flowing into other people's dashboards, whether that counts as redistribution or flips the
classification to professional is a question for Alpaca — not something to infer. Cheap to ask, and
expensive to get wrong after the fact.

## What streaming does NOT fix

- **Temporal history is already safe** — and only because `a7e8149`/`8cc1b7a` shipped. The 1% min-move
  throttle lives in `SubscribePremiumActivityImpl#shouldEmit`, **downstream** of the feed, so a 100×
  tick rate does not multiply history events on the un-continue-as-new'd `PositionWorkflow`. Had this
  exploration happened a week earlier, streaming would have been a non-starter on this axis alone.
- **`acceptPremiumQuote` still earns its keep** — the no-bid rejection especially. Only the
  `lastQuoteStamp` resample-dedup becomes vestigial.
- **`snapshotQuote` does not go away.** Kill-switch MTM, `GetOptionQuoteActivity`, and
  `resolveTrailAnchor` all use it. This *adds* a transport; it does not replace one. The
  unfiltered-snapshot vs filtered-tick asymmetry the trailing-stop plan flags stays exactly as it is.
- **Silence gets *worse* before it gets better.** A dead poll is loud (`optionPollFailures` →
  `markDisconnected`). A WS that stays open and stops delivering is silent — precisely the failure
  the premium-feed-silence plan is fighting. Streaming only wins here **if** ping/pong liveness is
  wired as part of it; done right it collapses that plan's P3 fork (socket healthy + no quotes =
  market quiet; socket dead = feed dead, no probe activity needed).
- **Backpressure** was free with a poll. It isn't with a socket — and Alpaca *disconnects* slow
  clients (407) rather than buffering for them.
- **The single-replica pin becomes permanent.** One connection per endpoint means market-data can
  never scale out while it owns a socket.

## Suggested shape, risk-ordered

**Step 0 — free, do it regardless.** Set `strategy: {type: Recreate}` on the market-data Deployment.
The default rolling update surges to 2 pods and 406-collides the *existing* stocks WS on every deploy.
This is a live bug, independent of everything below.

**Step 1 — buy Algo Trader Plus, change no code.** Confirm the entitlement question above with Alpaca
first. Then point `data-ws-url`/feeds at `opra`, and drop `premium-poll-interval-ms` from 2000 to
~500 now that REST is 10,000 req/min instead of 200. Measure what that alone does to trail behaviour.
Reversible by one config value.

**Step 2 — probe, no product code.** Re-run `scripts/alpaca-ws-conn-check.py` to confirm `/v1beta1`
auth and OPRA entitlement, then extend it to `subscribe` one live OCC and print decoded quotes. The
June attempt never got a single frame, so *seeing one decoded quote* is the entire gate.
**⚠ Do not run during RTH:** steps B and C deliberately open competing connections and will 406 or
kick the live stocks WS that watchlist triggers depend on.

**Step 3 — decode-only, dark.** msgpack dependency + `onBinary` + `dispatchOptionWsMessage` + tests
against captured frames. No subscriber wiring, no behaviour.

**Step 4 — shadow.** Run the WS alongside the poll, fan out nothing, log divergence: rate, latency,
bid agreement, and any 407. This produces the data to re-tune the 35% band and tells you whether the
socket beats a 500ms OPRA poll by enough to justify itself.

**Step 5 — cut over per-tenant**, poll retained as fallback.

Steps 0–4 are reversible and touch no live exit path. Step 5 is trading-critical.

## Open questions for the operator

- **Confirm with Alpaca:** does one pod-global OPRA key feeding premium into *other tenants'*
  dashboards count as redistribution, or flip the classification to professional? Ask before paying.
- **Is the socket still worth it after a 500ms OPRA poll?** Genuinely open. Step 4 answers it with
  data instead of assumption; the honest possibility is "no."
- Is 2s latency demonstrably costing money today, or is this a correctness/complexity win only?
