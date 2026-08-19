# PLAN-2026-08-19 — Partial-fill cumulative-vs-delta ledger (#735)

**Status:** proposed
**Issue:** #735
**Blocks:** enabling `EXEC_FILL_LISTENER_RECYCLE_AFTER_MS` on real money (#715)
**Service:** `services/orchestrator` (`PositionWorkflowImpl`) — Temporal workflow code, replay-critical

---

## The defect in one line

Alpaca reports `filled_qty` as **cumulative-so-far**; `emitExitFill` subtracts it as if it were an
**increment**, and `PositionWorkflowImpl` has exactly one site (`bookFlattenDelta`) that does this
correctly.

```java
private void emitExitFill(String signalId, FillSignalPayload fillEvent) {
    long filled = fillEvent.getFilledQty();
    remainingQty -= filled;          // <-- cumulative subtracted as a delta
```

## Why it has never fired, and why that is about to change

No partial fill has ever reached a workflow on live, for two independent reasons:

1. The WebSocket has delivered nothing for ~11 weeks (#715).
2. `FillPoller` **structurally cannot** dispatch a partial — `AlpacaPaperBroker.mapStatus` maps
   `partially_filled → OPEN` and `FillPoller.checkRow` returns early on `OPEN`.

So every quantity the exit path sees today is a terminal, complete, broker-confirmed value. Today's
uniform 30s slowness is not merely slower — it is **shape-preserving**.

A freshly-established socket demonstrably delivers (#715, 2026-08-19), and **every `exec` roll
creates one**. This is therefore live exposure on any exec deployment, not a future risk contingent
on the #715 fix. The recycle makes delivery *reliable*, converting intermittent exposure into
continuous exposure.

## Blast radius, both directions

| Direction | Mechanism | Consequence |
|---|---|---|
| `remainingQty` **overstated** | `processOne` books the first partial and `return`s — the exit is treated as complete | oversized flatten against a position that no longer exists — a fresh #716 (the 2026-08-17 AMD near-miss shape) |
| `remainingQty` **understated / negative** | `:3111` never nulls `lastFillEvent`, so a stale cumulative is drained again later (16 booked against an 11-lot sale) | `PositionClosed` with remaining < 0, breaking R-AA-1; realized P&L double-counts the same fill |
| position **understated at entry** | `:1183` latches `remainingQty = firstFilledQty` — the FIRST partial's cumulative | a 50-lot entry filling 10 then 40 leaves the workflow believing it holds **10**; the other 40 are unmanaged, unflattened, and invisible to the account cap |

The entry-side row is a **distinct defect with a worse direction** (silent unmanaged real-money
exposure) and is tracked separately — see Phase 3.

---

## Phase 1 — per-`intentKey` exit delta ledger

**Goal:** every exit booking goes through one clamped delta ledger. No control-flow change.

Generalize `flattenBookedKey`/`flattenBookedQty` (today a single active-key pair, flatten-only) into
a per-`intentKey` ledger and route **all eight** booking sites through one `bookExitDelta(...)`:

| # | Site | Today |
|---|---|---|
| 1 | `processOne` main fill path `:3111` `applyExitFill` | raw cumulative, `lastFillEvent` NOT nulled |
| 2 | cancel-terminal reconcile `:3183` `applyExitFill` | raw cumulative |
| 3 | late-fill reconcile `:3201` `applyExitFill` | raw cumulative |
| 4 | `flattenAwaitingLateFill` drain `:1287` | raw cumulative |
| 5 | #481 retry loop `:1570` | inline ad-hoc ledger (`:1565-1569`) — duplicates the logic |
| 6 | `flattenRemaining` TTL branch `:3506` | raw cumulative |
| 7 | `forceCloseZero` drain `:4111` | raw cumulative |
| 8 | `bookFlattenDelta` `:3948` | **correct** — becomes a thin caller of the shared helper |

Semantics (lifted verbatim from `bookFlattenDelta`, which already earns its keep):

- ledger resets to 0 when the key is first seen (fresh `:retry-N` placement is a distinct key)
- `delta = cumulative − alreadyBookedForThisKey`; only a **positive** delta is bookable
- `bookable = Math.min(delta, remainingQty)` — a stale or duplicate broker report can never drive
  `remainingQty` negative
- advance the ledger by the booked qty and clear `lastFillEvent` (existing guardrail #3)

**Version gate:** `VERSION_EXIT_CUMULATIVE_LEDGER = "exit-cumulative-ledger-v1"`.

Required even though Temporal 1.27 replay ignores activity *input* payloads: booking a delta of 0
where v=0 booked a full cumulative emits **zero** `auditLog` commands where the old history has one.
That is a command-count divergence, not a payload difference — replay-fatal without the gate.

**Ledger bound.** ~~Keyed by `intentKey`~~ **CORRECTED during implementation: keyed by
`broker_order_id`**, which is the unit Alpaca actually cumulates over (an `intentKey` is a proxy
that a `:retry-N` placement breaks). Capped at 64 with oldest-first eviction.

~~an unbounded map in workflow state is a history bloat vector~~ — **this claim was wrong.** Workflow
*fields* are never serialized into Temporal history; history holds commands and events and state is
reconstructed by replay. An unbounded ledger is a worker-heap concern only. Still bounded, but for
the right reason.

**Tests (red first):**
- 11-lot exit reporting cumulative `5` then `11` books `5` then `6`; `remainingQty == 0`, exactly two
  `PartialExitFilled`, total booked `11`
- the same cumulative delivered twice books once
- a broker report exceeding the lot clamps and never drives `remainingQty` negative
- a fresh `:retry-N` key restarts its own count and does not inherit the prior key's ledger
- **replay:** a v=0 history recorded before this change replays byte-identical (`WorkflowReplayer`
  against a captured live history — see "Replay fixtures" below)

**Done when:** all eight sites call the shared helper; no `remainingQty -=` remains outside it;
orchestrator suite green; replay test green.

---

## Phase 2 — `processOne` stops treating the first partial as a completed exit

**Goal:** a partial fill no longer releases the exit latch and returns.

Today `:3109-3116` books the fill, releases the latch, and `return`s the moment `lastFillEvent != null`
— correct when every fill is terminal, wrong the instant partials arrive. Change to: consume the
fill, null `lastFillEvent`, and **re-await** until the exit reaches its target qty or the TTL
expires; only then release the latch and return.

**Version gate:** `VERSION_EXIT_PARTIAL_AWAIT_LOOP = "exit-partial-await-loop-v1"`.

Independently gated and independently shippable — Phase 1 alone is a strict improvement (it stops
the double-count) and is safe to ship without this. Phase 2 without Phase 1 is **not** safe, so the
merge order is fixed.

**Tests:** a two-partial exit completes in one `processOne` cycle with one latch release; a partial
followed by TTL expiry takes the existing timeout/retry path with `remainingQty` reflecting only
what actually filled; EOD/expiry/risk-breach/force-close pre-emption mid-partial still wins the await.

---

## Phase 3 — entry-side partial fill (separate issue, filed from this plan)

`:1183` `this.remainingQty = firstFilledQty` latches the first partial and clears `lastFillEvent`,
so the balance of a partially-filled entry is never booked. Direction is the dangerous one: the
workflow **understates** a live real-money position, so exits and flattens are sized to a fraction of
what is actually held and the account cap under-counts exposure.

Deliberately **not** folded into Phases 1-2: it is the entry path, needs its own version gate and its
own await-to-completion decision (how long to wait for the balance before confirming), and bundling
it would make the replay risk of Phase 1 harder to reason about.

**Deliverable of this phase:** #738 — filed, with the worked example and the `getOrderStatus`
reconciliation option written up. Implementation is scoped separately.

---

## Phase 4 — price blending for delta bookings

`avg_fill_price` on each `PartialExitFilled` feeds `DailyPnlActivitiesImpl` realized P&L. When a
delta is carved out of a cumulative fill, the cumulative average is the wrong price for that delta:

```
deltaNotional = cumQty × cumAvg − bookedQty × bookedAvg
deltaPrice    = deltaNotional / deltaQty
```

`bookFlattenDelta` already passes the cumulative avg for the delta — a pre-existing inaccuracy that
only bites when one key books twice at different prices, which is exactly what partials introduce.
Requires tracking booked **notional** per key alongside booked qty.

**No version gate expected:** this changes only the audit payload (activity input), which Temporal
1.27 replay ignores. To be **verified, not assumed** — if the replay fixture disagrees, it gets a gate.

BigDecimal division needs an explicit scale + `RoundingMode`; guard `deltaQty == 0` and a negative
notional (possible from a broker correction) by falling back to the cumulative avg rather than
emitting a nonsense price.

---

## Phase 5 — enablement (the reason this plan exists)

Only after Phases 1-2 are on `main` **and rolled**:

1. `EXEC_FILL_LISTENER_RECYCLE_AFTER_MS` on `exec-alpaca-paper`, soak one session
2. manual `kubectl rollout restart deploy/exec-alpaca-live` (excluded from CI deploy)
3. arm on live, watch `ws_callbacks` / `recycles` / `PartialExitFilled` counts

---

## Replay fixtures — CONFIRMED TOOTHLESS for Phase 1

**Measured, not predicted.** The existing `position-pre-276-legacy-history.json` does NOT
discriminate this change:

- with `VERSION_EXIT_CUMULATIVE_LEDGER` **fully defeated** (ledger forced on unconditionally), all
  14 replay tests still pass — and so does the **entire 1,346-test suite**
- the reason is structural: that history books exactly ONCE, and the ledger only diverges on a
  SECOND booking against the same broker order

An attempt to record a discriminating fixture (a 4-lot drained by booking one cumulative twice, the
stale-`lastFillEvent` defect) reproduced the scenario but would not replay against the real impl —
it diverges at the cycle-2 `PlaceOrder` on `activityId`, i.e. the emulator does not mirror the real
`PositionWorkflowImpl`'s internal `Workflow.randomUUID()` consumption. Rather than commit a fixture
that does not replay, it was removed.

**Consequence, stated plainly: the version gate is NOT verified by any test.** What IS verified is
that the v=0 branch is reachable and byte-identical for the single-fill path (the pre-#276 fixture
exercises it), and that the changeId literal is pinned against a silent rename. The replay-safety
argument otherwise rests on Temporal's documented contract — an unknown changeId on a history with
no marker returns `DEFAULT_VERSION` — which is how all 27 existing gates in this file already work.

Building a discriminating fixture is required follow-up.

## Replay fixtures — the original guidance

This repo has been burned **twice** by replay fixtures that passed for the wrong reason (a recorded
config value independently disabled the branch under test). Before trusting any replay test here:

1. capture a **real** live/paper history that exercised an exit fill
2. **defeat the gate** — force the new-version branch and confirm the fixture goes RED
3. only then restore and call it green

A replay fixture nobody has watched fail is decoration.

## Risks

- **`PositionWorkflowImpl` has no continue-as-new.** History limits are 10,240 (warn) / 51,200
  (hard fail). Phase 2 makes a multi-partial exit emit more events per exit. Bound the added events
  and note the worst case; do not let the ledger map grow unbounded.
- **Twelve running `PositionWorkflow`s across three real-money tenants** as of this writing. Any
  ungated command-sequence change breaks their replay on the next orchestrator roll. Every phase
  above is gated for that reason.
- **`spotless:apply` on `services/orchestrator` before every commit** — the impl environment skips it
  and CI fails on it.
- **The orchestrator CI leg flakes ~50% and a red leg silently cancels the deploy (#723).** Expect
  to re-run; do not read a red orchestrator leg as a real failure without checking the failure text.

## Success criteria

1. No `remainingQty -=` outside the shared ledger helper.
2. All eight sites route through it.
3. An 11-lot exit reporting cumulative `5` then `11` books exactly `11` across exactly two
   `PartialExitFilled` audits and lands `remainingQty == 0`.
4. `remainingQty` cannot go negative under any duplicate/stale/oversized broker report.
5. A captured v=0 history replays byte-identical, **and** the fixture has been watched to fail with
   the gate defeated.
6. Full `services/orchestrator` suite green; `spotless:check` clean.
7. Phase 3's issue (#738) exists and is linked from #735.

## Halt conditions

- A replay fixture cannot be made to fail with its gate defeated → stop; the test proves nothing.
- Any phase requires touching `positionState` query shape → stop (#728: widening it can HALT and
  FLATTEN a live account; display-only state belongs on `trailingState`).
- Command-sequence divergence appears in a phase claimed to be payload-only → stop and add a gate.
