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

Plus two ceilings polling can't raise:

- **REST budget.** 2s/contract = 30 req/min/contract against a ~200 req/min data-REST budget shared
  with everything else. That is a hard ceiling around **~6 concurrently-trailed contracts**, and it
  is the same budget `GetOptionQuoteActivity` and the kill-switch MTM read draw from. One WS
  connection carries hundreds of contracts.
- **Trail tightness.** A trailing stop can be no tighter than its sampling interval.

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

## The commercial question that decides this

`application.yml:23` defaults `data-ws-url` to **`wss://stream.data.alpaca.markets/v1beta1/indicative`**.

- `indicative` — free, an *indicative* NBBO, not the real one.
- `opra` — the real OPRA feed, paid entitlement.

Per `reference_premium_tick_mid_vs_bid`, the exit path **trades on the bid**. Arming a real-money
trailing stop off an indicative bid is not obviously sound, and an indicative feed may also be the
reason the 2026-06 attempt was never pushed harder. **Resolve the entitlement before writing code** —
if the answer is `indicative`-only, the latency win is real but the price quality may be worse than
the REST snapshot we have, and the whole exercise is moot.

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
- **Backpressure** was free with a poll. It isn't with a socket.

## Suggested shape, risk-ordered

1. **Probe first, no product code.** Re-run `scripts/alpaca-ws-conn-check.py` on the data key and
   confirm (a) auth still succeeds on `/v1beta1`, (b) which feeds the key is entitled to. Then extend
   it to actually `subscribe` one live OCC and print quotes — the June attempt never got a frame, so
   *seeing one decoded quote* is the entire gate. **⚠ Do not run during RTH:** steps B and C
   deliberately open competing connections and can kick the live stocks WS that watchlist triggers
   depend on.
2. **Decode-only, dark.** Add msgpack + `dispatchOptionWsMessage` + tests against captured frames.
   No subscriber wiring, no behaviour.
3. **Shadow.** Run the WS alongside the poll, fan out nothing, log divergence (rate, latency,
   bid agreement). This is what tells you whether `indicative` is good enough, and it produces the
   data to re-tune the 35% band.
4. **Cut over per-tenant**, poll retained as fallback.

Steps 1–3 are reversible and touch no live exit path. Step 4 is trading-critical.

## Open questions for the operator

- **Which options feed is the data key entitled to — `indicative` or `opra`?** This gates everything.
- Is 2s latency actually costing money today, or is this a correctness/complexity win only? A shadow
  run (step 3) answers it; nothing here should be shipped on the assumption alone.
- Is the ~6-contract REST ceiling a live constraint yet, or still theoretical at current position counts?
