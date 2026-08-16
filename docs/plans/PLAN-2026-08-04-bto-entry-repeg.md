# PLAN — Copytrade BTO entry bounded single re-peg

**Authored** 2026-08-04. **REVISED 2026-08-15** — Fork C reopened and re-resolved; all `file:line`
anchors re-read against `main` at revision time (the 2026-08-04 anchors were stale: the manual-BTO
work grew `CopytradeSignalWorkflowImpl` from ~1500 to 2100 lines).

**Goal.** Stop abandoning copytrade BUY entries on fast-moving options: let the BTO entry order
perform **one bounded re-peg toward the live ask** before it expires — spending a wider slippage
budget only when the cheap peg has already failed, and only as much as the market actually demands.

Source of finding: live diagnosis on `prod_real` (real money) 2026-08-04.

---

## Incident summary (confirmed from the audit trail)

The BTO limit is `BtoPricing.computeBtoLimit`
(`services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/domain/BtoPricing.java:68`):
`min(price + max_slippage_abs, price × (1 + max_slippage_pct))`, rounded once to a penny tick via
`OptionTick.round` (`:98`). It is **anchored to the signal's already-stale `payload.getPrice()`**
and has **no ask term** — it never reaches toward the live market.

The order is submitted **once** at that limit (`exec.placeOrder`,
`CopytradeSignalWorkflowImpl.java:824`; `OrderSubmitted` audit `:826`), then a single
`Workflow.await(Duration.ofSeconds(ttlSecs), …)` (`:843-846`; `ttlSecs` from `pendingTtlSecs`,
`:1876` → `DEFAULT_PENDING_TTL_PAPER_SECS = 90L` at `:313`). On timeout `handleTtlExpired` (`:919` →
`:1547`) cancels and emits `EntryExpired`. **No re-peg, no chase, no retry.**

Real-money misses (figures below CORRECTED 2026-08-15 from the audit trail — see the forensic note):
- NVDA 8/10 212.5C, 10:25 ET — signal price **2.90**, limit 2.95 (`slip_min`, the `abs=0.05`
  branch), option traded 2.98–3.20 inside the TTL → expired unfilled.
- AAPL 8/14 315C, 12:24 ET — signal price **2.46**, limit 2.51, first print inside the TTL 2.55 →
  never marketable → expired.

> **The original plan back-solved these prices as `limit / 1.05` (2.81 and 2.39), assuming the
> percentage branch.** Both orders were actually `slip_min` on `max_slippage_abs = 0.05`, so the
> real prices are `limit − 0.05`. Every ceiling figure derived from the old numbers was wrong: the
> true ceilings at the 10% default are **3.19** (not 3.09) and **2.71** (not 2.63).

**The `abs = 0` mitigation POSTDATES both incidents.** `strategy_config` was updated at
**13:02 ET on 2026-08-04** — after NVDA (10:25) and after AAPL (12:24). Confirmed independently:
orders submitted that morning carry `limit_price_strategy=slip_min`, while a 14:31 order the same
day carries `slip_pct`. Under the config now in force the limits would have been 3.05 and 2.58,
both above the first print within seconds of submit — **so both cited incidents would fill today at
t≈0 and never reach a re-peg.** They motivate the work but do NOT justify it; see the measured
business case below.

**Target field is present but inert.** `contract/schemas/strategy-config.json:179` defines
`repeg_after_ms` (*"Spec-only: no orchestrator/exec code consumes this field"*). Confirmed: the only
references are the generated Python model, the generated dashboard field manifest, and a
**test-only** fixture (`contract/fixtures/strategy-config-copytrade-v1.json:18` = `5000`). **No
tenant YAML, ConfigMap, or DB row sets it** — so under this plan every tenant runs on the code
defaults from day one (see Activation).

---

## Why the original Fork C was withdrawn (read before re-litigating)

The 2026-08-04 resolution was: keep the ceiling = today's `computeBtoLimit` unchanged, and submit
initially at a **tighter** peg (the bare mirror price), re-pegging up to that ceiling.

**That cannot add a single fill.** With the shipped mitigation the single order already goes out
*at* the ceiling, so the highest limit ever reached is `price × 1.05` before and after — the change
only arrives there 30s later:

| | max limit ever reached | reached at |
|---|---|---|
| today | `price × 1.05` | t=0 |
| original Fork C | `price × 1.05` | t=`repeg_after_ms` |

It is therefore **strictly fill-negative**: every entry that fills today between the mirror and the
ceiling inside the first window now sits unfilled for that window instead. And both cited incidents
expired **at** that ceiling — re-pegging AAPL to an unmarketable 2.51 against a 2.55 ask still
expires. The original plan's headline test (`repeg_fills_at_ceiling`, "models NVDA/AAPL") asserts a
fill at a limit that by its own incident data did not fill.

**The arithmetic is inescapable: an order capped at 2.51 cannot fill against a 2.55 ask.** Fixing
missed fills *requires* being willing to pay more than today's cap in some cases. Lowering the floor
without raising the roof only subtracts.

**What the re-peg is actually for, then:** it is the mechanism that makes a wider cap *safe*. Simply
raising `max_slippage_pct` 0.05 → 0.12 would make **every** entry immediately willing to pay 12%.
The ladder spends that wider budget only after the cheap peg has demonstrably failed, and then only
up to the **live ask**, never blindly to the cap.

---

## Fork C — RE-RESOLVED 2026-08-15

**Initial peg = today's `computeBtoLimit`, UNCHANGED.** No first-window regression: the opening
order is byte-identical to today's.

**Re-peg target = `round(min(ask + repeg_tick, repegCeiling))`**, anchored on a **fresh
`GetOptionQuoteActivity` snapshot**, where `repegCeiling = round(price × (1 + repeg_ceiling_pct))`
from a **new** strategy-config field.

**Ships ACTIVE, not dark (operator decision, 2026-08-15).** Both fields carry hardcoded defaults, so
the re-peg is live for every copytrade tenant the moment the orchestrator rolls — no config flip.
See "Activation" below for the defaults, the off-switch, and what this costs.

This mirrors the **already-proven exit-side ladder** in `PositionWorkflowImpl`
(`VERSION_EXIT_STEPPED_REPRICE`, `:2537-2600`): fresh quote anchor per step, distinct `:reprice-N`
intent keys, bounded by a configured floor, explicit fail-safe when the quote is unavailable. The
entry path should not invent a second shape.

**Fail-safe direction is INVERTED from the exit side, and this is load-bearing.** On exit, a missing
quote degrades to a *marketable* order — you must get out. On entry, a missing quote (`status =
UNAVAILABLE | FAILED`, or `ask` absent/non-positive) must degrade to **no re-peg at all**: leave the
initial order standing and let it expire as today. Never buy at a cap you could not price against a
live market.

**Schema cost, accepted.** `repeg_ceiling_pct` is a new field: `jsonschema2pojo` + pydantic regen,
plus `scripts/gen-config-field-manifest.py` → `dashboard/lib/strategyConfigFields.generated.ts` and
its CI drift job. Note that **StrategyConfig fields are not removable** (removal wedges in-flight
workflows on replay — see `LegacyReplayTest`), so this addition is permanent; deprecate-in-place if
it is ever abandoned. Repurposing the existing `max_slippage_abs`/`_pct` pair to carry two bounds
was considered and **rejected**: those are live real-money fields on every tenant, and `abs = 0`
currently means *unset* (`isSet` tests `signum() != 0`), so the semantics would silently change
underneath the shipped mitigation.

**Forks A and B stand as resolved.** Fork A: `repeg_after_ms = 30000` (30s of the 90s TTL). Because
the initial peg now equals today's limit, the wait is very nearly free — but **not literally free**,
as the original revision claimed. Measured over 120 days of live BUY fills (n=47): p50 **0.09s**,
p90 12.3s, and **2 of 47 (4.3%) filled after 30s** — both exactly AT the limit (NVDA 7/16 at 45.1s,
NVDA 7/20 at 33.9s). Those are orders a 30s re-peg cancels and re-places higher; they would very
likely still fill, but at a worse price having surrendered queue position. Accepted: the regression
is ~4% of entries, price-worse rather than fill-worse. Do NOT recalibrate this on `staging_paper`
(12% fill past 30s) — that is Alpaca's paper fill simulator, not live microstructure. Fork B:
BTO-only for v1; symmetric STC re-peg deferred to Phase 4.

---

## Measured business case (forensics, 2026-08-15) — read before judging the value

Both cited incidents are already fixed by the `abs = 0` mitigation, so the real question is what
this buys on top of TODAY's config. Measured across the live tenants:

- **Headline expiry rate ~27%** (`prod_real` 6/22 over 30 days; 12/45 over 120 days) — but that does
  NOT decompose into 27% of addressable loss.
- Of `prod_real`'s **12 expiries in 120 days**: 3 had **zero trade prints** in the entire 90s window
  (no market to re-peg into, one submitted 00:13 ET with the market shut), 1 was a **stale signal
  price** at +93% (correctly refused, see the ceiling rationale), 1 was a **benign artifact** (a
  wrong-expiry edited signal whose corrected sibling filled 14s later — zero economic loss). That
  leaves **8 genuine "market moved slightly away" misses**, i.e. roughly **2/month** across live
  tenants, not 27% of entries.
- **Today's config already catches 6 of those 8** against the full 90s window. **The re-peg adds
  exactly 2** over 120 days, and only one of them (MU 8/13 260817C1050, needing +8.26%) postdates
  the mitigation.

**So the honest value is ~1–2 additional filled entries per quarter, not a fix for a 27% failure
rate.** That is still worth shipping — the mechanism is bounded, fail-safe and cheap — but it does
not justify loosening anything further, and it should not be sold internally as an outage fix.

**Caveat on all of the above:** historical options **NBBO quotes** are not on the current data plan
(`/v1beta1/options/quotes` → `Not Found`); trades and bars are. Every ask-side figure is therefore
inferred from **trade prints, which sit at or below the ask**, making the required-ceiling numbers
lower bounds. This does not change the ranking of 10% over 12/15%.

---

## Activation — ships ACTIVE (supersedes the original dark-ship gating)

**Defaults, as code constants beside `DEFAULT_PENDING_TTL_PAPER_SECS`
(`CopytradeSignalWorkflowImpl.java:313`):**

| field | default | rationale |
|---|---|---|
| `repeg_after_ms` | `30000` | 30s at today's limit, then ~60s of the 90s TTL at the re-peg |
| `repeg_ceiling_pct` | `0.10` | smallest ceiling capturing **all 8** real historical misses |

**Calibrated, not picked.** Replaying every genuine live miss over 120 days against its own trade
tape gives the ceiling each needed: +1.95%, +2.11%, +2.11%, +2.86%, +3.66%, +5.17%, +6.85%, +8.26%.
So **+5% captures 5/8, +8% captures 7/8, +10% captures 8/8**, and 12% or 15% capture nothing more
while raising the worst price payable. Hence 0.10.

Both 2026-08-04 incidents are **captured**, not bounded out: NVDA needed +5.17% against its real
2.90 signal price (ceiling 3.19) and AAPL +3.66% (ceiling 2.71). The earlier claim that NVDA
"needed +16% and is deliberately not chased" came from the back-solved price and is **false**. The
genuine bounded-out case is the **stale-signal-price** class — MSFT 7/07 posted at 3.65 while
trading ~7.05 (+93%) — which the ceiling correctly refuses.

**Off-switch without a redeploy:** `repeg_after_ms = 0` disables the re-peg for that tenant,
restoring today's exact one-shot behavior. Schema `minimum` relaxes from `1` to `0` and `0` is
documented as the disable sentinel. This is the emergency lever in place of the canary.

**What shipping active costs — accept knowingly:**
- **No `staging_paper` canary.** `prod_real` and `prod-kipark` (real money) change entry behavior at
  the moment the orchestrator pod rolls, with no observed paper trades first.
- **Every copytrade tenant's max entry cost rises 5% → 10%** on entries that need the re-peg. Entries
  that fill in the first 30s are unaffected and still pay today's price or better.
- **Position sizes shrink slightly**, because the risk gates and `Sizing.computeEntry` now budget
  against the 10% ceiling rather than the 5% limit (see Phase 3, item 1).
- **The version gate is still REQUIRED and is unrelated to darkness.** It exists so workflows
  in-flight when the pod rolls replay their recorded command stream; removing it wedges live
  entries mid-signal. Do not conflate "ships active" with "needs no marker."
- **Post-deploy watch:** grep the audit trail for `reason=repeg` on the first session and confirm the
  re-pegged fills land at sane premiums before walking away.

---

## P0 / operator follow-ups (NOT code phases)

**No config flip is required to activate** — the defaults do that (see Activation). The operator
actions are now post-deploy verification and the override path.

1. **Watch the first live session after the Phase 3 deploy.** Filter the audit trail for
   `reason=repeg` and confirm: re-pegs fire only after ~30s, fills land at or below the ceiling, and
   no workflow places two entry orders.
2. **Overrides are optional**, per tenant: `repeg_after_ms = 0` to disable, or a tighter
   `repeg_ceiling_pct` than the 0.10 default. **Config sources are the usual 4 spots** (dev yaml +
   `40-tenants-config.yaml` ConfigMap + onboard template + per-live-tenant DB row). Editing any
   `tenants/dev/*` file trips the **ConfigMap drift guard** — regen `40-tenants-config.yaml` in the
   same PR. Live-tenant YAMLs are **live-cluster-only**; a naive `kubectl apply` DROPS live blocks —
   apply the merged ConfigMap.
3. **Deploy gate + homelab verify** after each code phase (deploy targets the k3s homelab). Since
   Phase 3 is behavior-live on arrival, prefer rolling it outside RTH.

   **ROLL THE ORCHESTRATOR FIRST — this one is ordered, not a preference.** There is no custom
   Temporal `DataConverter`, so the SDK default applies with `FAIL_ON_UNKNOWN_PROPERTIES` on, and
   the generated `StrategyConfig` carries no `@JsonIgnoreProperties(ignoreUnknown = true)` (the
   schema is `additionalProperties: false`). `StrategyConfig` crosses the Temporal wire inside
   `StrategyConfigUpdateRequest`, so if the dashboard/api-gateway rolls first and an operator sets
   `repeg_ceiling_pct` in that window, the write fails with `DataConverterException`. The READ path
   is safe either way — `DbStrategyRegistry` uses the Spring-injected `ObjectMapper`, which Boot
   configures unknown-property-tolerant.

   Note also that `.github/workflows/deploy.yml` lists `market-data` as RESTART_ONLY, so CI does a
   rollout restart and skips `kubectl apply` for it. Nothing here needs a market-data manifest
   change, but its task queue is now on the entry path, so its health matters more than it did.
4. Leave `max_slippage_abs = 0` / `max_slippage_pct = 0.05` as-is. The wider budget lives entirely in
   `repeg_ceiling_pct`, so the *initial* peg stays exactly as tight as it is today.

---

## Phase 1 (contract) — add `repeg_ceiling_pct`

**Concern:** schema + regen only. No runtime consumer, no Temporal surface.

**Changes:**
- `contract/schemas/strategy-config.json` — add `repeg_ceiling_pct` (`number`, `exclusiveMinimum: 0`)
  beside `repeg_after_ms:179`. Description must state: fraction above the signal price that the BTO
  re-peg may reach; **unset ⇒ the 0.10 default applies**; the re-peg targets `min(ask + tick, this)`,
  never this blindly.
- `repeg_after_ms` — relax `minimum` from `1` to `0` and document `0` as the **disable sentinel**
  (restores today's one-shot). Correct the now-false description ("Spec-only … no runtime effect"),
  state that unset ⇒ the 30000 default applies, and drop the STC clause (deferred to Phase 4).
- Regen: `jsonschema2pojo` (Java), pydantic model, and
  `scripts/gen-config-field-manifest.py` → `dashboard/lib/strategyConfigFields.generated.ts`.

**Success criteria:** contract round-trip tests green in both languages
(`contract/java/.../RoundTripTest.java`, `contract/python/tests/test_round_trip.py`); the
config-field drift job green; the field appears in `/config`; **no** tenant config sets either field.

---

## Phase 2 (code, lowest blast radius) — pure re-peg math in `BtoPricing`

**Concern:** pure math only. No Temporal surface, no replay gate, behavior-neutral for all callers.

**Replay-gate decision:** NONE — `BtoPricing` is a pure, determinism-safe function.

**Changes:**
- `BtoPricing.java:68` — leave `computeBtoLimit` **exactly as today** (it is now the *initial* peg;
  every existing caller keeps it).
- Add two pure siblings:
  - `computeRepegCeiling(payload, config)` → `round(price × (1 + repeg_ceiling_pct))`, applying the
    **`DEFAULT_REPEG_CEILING_PCT = 0.10`** constant when the field is unset. Never null — the
    disable path is `repeg_after_ms = 0`, resolved in the workflow (Phase 3), not here.
  - `computeRepegLimit(ask, ceiling, initialPeg)` → `round(min(ask + REPEG_TICK, ceiling))`, or
    **null** when `ask` is null/non-positive or when the result is `<= initialPeg` (nothing to gain —
    degrade to one-shot).
- `REPEG_TICK` is a single penny constant. Keep both helpers single-use and free of new
  records/abstractions (KISS) until a second caller exists.

**Success criteria (TDD, `BtoPricingTest.java`):**
- `computeBtoLimit` output **byte-identical** to today across every branch (MIRROR / SLIP_ABS /
  SLIP_PCT / SLIP_MIN) — explicit no-regression assertions.
- `computeRepegCeiling` applies the 0.10 default when the field is unset, the configured value when
  set; penny-rounded in both cases.
- `computeRepegLimit` returns `ask + tick` when that sits below the ceiling; returns the ceiling when
  the ask is above it (**the bound holds — no chase past the ceiling**).
- `computeRepegLimit` null for: null/zero/negative ask, and result `<= initialPeg`.
- Every non-null output is ≤ 2 dp (penny tick) so none can trip Alpaca's non-retryable HTTP 422.

**Verify:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator test -Dtest=BtoPricingTest
```

---

## Phase 3 (code, version-gated workflow) — wire the bounded single re-peg into `handleBto`

**Concern:** the behavior change, and the only replay surface. Ships **ACTIVE** on the defaults for
every copytrade tenant (see Activation). Depends on Phases 1–2.

**Replay-gate decision: REQUIRED — and note this is orthogonal to shipping active.** The marker
protects workflows *already in flight* when the pod rolls; it is not a feature flag. A signal that
started before the deploy must replay its recorded command stream (no timer, no quote, no
cancel/place) or it wedges mid-entry. New commands in `CopytradeSignalWorkflowImpl` — a re-peg-delay
timer, a `GetOptionQuoteActivity` dispatch, a `cancelOrder` + `placeOrder` pair, and re-peg audits.
Add `VERSION_BTO_ENTRY_REPEG = "bto-entry-repeg-v1"`, read **once, unconditionally**, at a stable
scope near the top of `handleBto`, mirroring the read-once discipline of `VERSION_NOTIONAL_CAP_CLAMP`
(`:689`) and `VERSION_LIVE_PROMOTION_GATE`.
> The limit **value** is an activity input and is not replay-checked (Temporal 1.27 compares command
> type/ordering only). It is the new timer + quote + cancel + place + audit **commands** that need
> the gate.

**Changes (anchors re-read 2026-08-15):**

1. **Gates and sizing must run against the ceiling, not the initial peg.** This corrects the
   original plan's "no re-check needed" claim, which no longer holds once the re-peg can exceed
   `priced.limit()`. Feed `repegCeiling ?: priced.limit()` — the true max cost — to
   `checkEntryWithLimit` (`:567`, `:573`), `notionalCapHeadroomContracts` (`:692`), and
   `Sizing.computeEntry` (`:627`). Then the re-peg limit is **already pre-gated** and needs no
   re-check at re-peg time. When the re-peg is disabled (`repeg_after_ms = 0`) or the marker resolves
   to `DEFAULT_VERSION`, the value passed is `priced.limit()`, so that path stays byte-identical.
   These are activity inputs, so they add no command and need no marker of their own.
   **Consequence to expect:** on the default path the gates now see the 10% ceiling, so sizing is
   modestly smaller than today for every copytrade entry.

2. `:822-824` — the initial `OrderIntent` stays at `priced.limit()` on the `:entry` key,
   **unchanged on every path**.

3. `:843-846` — replace the single `Workflow.await(ttl, …)` with a bounded two-window await, **only**
   when active — `v >= 1` AND `repegAfterMs > 0` AND `repegAfterMs < ttlSecs * 1000`, where
   `repegAfterMs` resolves to the 30000 default when the field is unset and `0` is the operator
   disable sentinel:
   - Await `Duration.ofMillis(repegAfterMs)` on `fillEvent != null || riskBreachReceived`.
   - If unfilled and unbreached: dispatch `GetOptionQuoteActivity.getOptionQuote` for the resolved
     OCC symbol. **If `status != OK`, or `ask` is absent, or `computeRepegLimit` returns null → do
     NOT re-peg**: fall through to the remaining window on the original order (entry fail-safe =
     don't buy). Emit an audit recording the skip and its reason.
   - Otherwise re-peg **once**: `OrderCancelRequested` (`reason=repeg`) → `exec.cancelOrder(intentKey)`
     → `OrderCancelled` (`reason=repeg`) → `exec.placeOrder` a new intent at the re-peg limit on a
     **distinct key** `…:entry:repeg-1` (mirrors the exit ladder's `:reprice-N`; the `:entry` key was
     cancelled and exec is idempotent by intent key) → `OrderSubmitted` carrying `peg=repeg`,
     `ask`, and `source_premium=live_quote` (mirroring `PARTIAL_EXIT_RETRY_REQUESTED`'s subject).
   - **Cancel-on-filled race:** when `exec.cancelOrder` returns `state = FILLED`, adopt via the
     existing `handleCancelOnFilled(… RECOVERY_CANCEL_ON_FILLED)` (`:884`, `:1648`) and place **no**
     re-peg order.
   - Await the remaining window (`ttl − repegAfterMs`) on the same predicate, then fall into the
     existing terminal paths with the re-peg order's `placed`/`intentKey`.
   - **Bounded: at most ONE re-peg. Never past the ceiling.**

4. **`onFill` must reject fills for a superseded order.** `onFill` currently accepts any fill
   unconditionally — `public void onFill(FillSignalPayload event) { this.fillEvent = event; }`
   (`:413-415`), with no intent-key or broker-order-id match. That is safe today only because a
   workflow has exactly one entry order; the re-peg makes it two. A late or duplicate fill signal
   for the **cancelled** first order would otherwise be adopted as the re-peg fill, threading the
   wrong `broker_order_id` / `avg_fill_price` into `EntryFilled` and `startPositionWorkflow`. The
   synchronous cancel-on-filled path in (3) does not cover this — that is the *asynchronous* signal
   arriving after `cancelOrder` already returned non-FILLED. Guard `onFill` to accept only a fill
   matching the currently-live intent key, dropping non-matching fills with an audit. Signal
   handlers must not call `getVersion`, so the guard must be a plain fail-safe field compare against
   a field the main path sets; when the re-peg never runs, the live key is `:entry` and behavior is
   unchanged.

5. `:919` / `:1547` — `handleTtlExpired` and the FILLED branch stay unchanged; they now act on the
   re-pegged order's `intentKey` when a re-peg happened.

6. When not active (`v == DEFAULT_VERSION`, `repeg_after_ms = 0`, or `repegAfterMs >= ttl`), the path
   is the existing single `await(ttl)` + `handleTtlExpired` — **byte-identical to today**. Note this
   is the *replay* and *opt-out* path only; the default path for new signals is active.

**Audit — no new kind (KindRegistryGuard NOT triggered).** Reuses `OrderCancelRequested` /
`OrderCancelled` / `OrderSubmitted` (already in `AuditEventKinds.ALL_KINDS`) with a new **reason**
string `"repeg"`; reason values are not registered, so
`services/audit/src/test/java/com/ohmytradeagent/audit/lint/KindRegistryGuardTest.java` needs no
change. A dedicated `BtoRepegged` kind was considered and rejected for blast radius. The quote-skip
audit in (3) and the dropped-fill audit in (4) must likewise reuse registered kinds.

**Repo gates folded in:**
- `spotless:apply` on `services/orchestrator` before commit (impl env skips it → CI fails).
- `KillSwitchWorkflowImplTest` and `PositionWorkflowImplTest` are known-flaky → re-run; not caused by
  this change.
- `gh pr edit --body` is broken here — set the body at `gh pr create` time or via
  `gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`, and verify it persisted.
- Do **not** touch `.github/workflows/*` (it disables the PR's own Claude review).

**Success criteria (TDD, `CopytradeSignalWorkflowImplTest.java`):**
1. **`repeg_fills_at_live_ask` (AAPL repro):** initial limit 2.51, live ask 2.55, ceiling 2.75 →
   re-pegs to 2.56 and fills. Assert a second `OrderSubmitted` at 2.56 then `EntryFilled`. *This is
   the test the original plan could not have passed.*
2. **`repeg_bounded_at_ceiling` (stale-signal-price repro):** ask far above the ceiling → re-pegs
   to exactly the ceiling, does **not** chase further, `EntryExpired` at TTL. Assert exactly **one**
   re-peg `OrderSubmitted`. Anchored on MSFT 7/07 (posted 3.65, trading ~7.05) — NOT on NVDA
   8/04, which the audit trail shows the 10% ceiling would have CAPTURED.
3. **`repeg_skipped_when_quote_unavailable`:** quote returns `UNAVAILABLE`/`FAILED` → **no** cancel,
   **no** second order; the original order rides to `EntryExpired`. (Entry fail-safe.)
4. **`normal_fill_no_repeg`:** fills at the initial peg before `repeg_after_ms` → no second
   `OrderSubmitted`, no cancel, no quote dispatch, single `EntryFilled`.
5. **`repeg_defaults_apply_when_unset`:** both fields null at `v >= 1` → the re-peg **still fires**,
   at 30s, against a `price × 1.10` ceiling. This is the ships-active guarantee, and it is the
   inverse of what the pre-revision plan asserted.
5b. **`repeg_after_ms_zero_disables`:** `repeg_after_ms = 0` at `v >= 1` → byte-identical single
   `await(ttl)`, one order, one `handleTtlExpired`, **no quote dispatch**. This is the operator
   off-switch; it must be exercised, not assumed.
6. **`repeg_cancel_on_filled_adopts`:** `cancelOrder` returns `FILLED` at the boundary → adopt via
   `handleCancelOnFilled`, no re-peg order, no orphan.
7. **`late_fill_for_cancelled_order_is_dropped`:** after a re-peg, an `onFill` carrying the **first**
   order's broker id is ignored — `EntryFilled` and `startPositionWorkflow` carry the re-peg order's
   ids. (Covers finding 4.)
8. **`gates_run_against_ceiling`:** `checkEntryWithLimit` / `notionalCapHeadroomContracts` /
   `Sizing.computeEntry` receive the ceiling, not `priced.limit()`, on the default path — and
   receive `priced.limit()` when `repeg_after_ms = 0`.
9. **Replay:** a pre-fix history (single order, single 90s TTL) replays green on `DEFAULT_VERSION`
   via the existing histories-replay harness. Per the replay-fixture caveat, **confirm the new
   fixture actually fails with the version gate removed** — a fixture that stays green either way is
   toothless.

**Verify:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator test -Dtest=CopytradeSignalWorkflowImplTest,BtoPricingTest
# existing replay-history harness must stay green
```

---

## Phase 4 (DEFERRED fast-follow, NOT this ship) — STC symmetric re-peg toward bid

The exit path **already has** a bounded stepped reprice ladder
(`PositionWorkflowImpl:2537-2600`, `VERSION_EXIT_STEPPED_REPRICE`, `exit_reprice_steps`,
`exit_floor`). Phase 4 is therefore mostly a question of whether `handleStc`'s *initial* exit peg
should also re-anchor — a separate command-shape change with its own replay gate and its own
forensics. Not specced here; track as a fast-follow.

---

## Ship order

1. **Phase 1** (contract) — schema + regen; no runtime effect.
2. **Phase 2** (`BtoPricing`) — behavior-neutral pure math; safe to merge/deploy anytime.
3. **Phase 3** (workflow wiring, `bto-entry-repeg-v1`) — **this is the live-fire step.** Behavior
   changes for every copytrade tenant, real money included, the moment the orchestrator rolls.
   Existing in-flight histories replay on `DEFAULT_VERSION`. Prefer rolling outside RTH.
4. **Watch the first session** (`reason=repeg` in the audit trail); use `repeg_after_ms = 0` per
   tenant if anything looks wrong — no redeploy needed.
5. **Phase 4 (STC)** — deferred, after the BTO path is proven live.

**Gating note:** Phases 1–2 carry no runtime effect and can merge freely. Phase 3 is **not** gated
behind a config flip — that was the pre-revision design. The only pre-deploy safety is the test
suite and the replay harness, so treat Phase 3's review bar as correspondingly higher.

---

## Revision log

- **2026-08-04** — original. Forks A/B/C resolved by the lead.
- **2026-08-15** — Fork C **withdrawn and re-resolved**: lowering the initial peg while holding the
  ceiling fixed is strictly fill-negative and fixes neither cited incident. Re-peg now anchors on the
  live ask under a new `repeg_ceiling_pct` bound (new Phase 1), mirroring the proven exit-side
  ladder. Added: gates/sizing must run against the ceiling (the original "no re-check needed"
  rationale no longer held); the `onFill` superseded-order guard; the quote-unavailable entry
  fail-safe. All `file:line` anchors re-read against `main`.
- **2026-08-15 (b)** — operator decision: **ship active, not dark.** Both fields gain code defaults
  (`repeg_after_ms = 30000`, `repeg_ceiling_pct = 0.10`), so Phase 3 changes real-money behavior on
  deploy with no `staging_paper` canary. Added `repeg_after_ms = 0` as the no-redeploy off-switch
  (schema `minimum` 1 → 0). The version marker is retained — it guards in-flight replay, not
  darkness. Tests inverted accordingly: the unset case now asserts the re-peg *fires*.
- **2026-08-15 (c)** — forensic validation against production. Corrected the incident prices
  (`slip_min`/`abs=0.05`, not the back-solved `limit/1.05`), established that the `abs = 0`
  mitigation POSTDATES and independently resolves both cited incidents, replaced the false
  "NVDA needed +16%" rationale with a calibration over 8 real misses (which **confirms** 0.10),
  added the measured business case (~2 extra fills per 120 days, not a 27% failure rate), and
  corrected "the wait costs nothing" to the measured 4.3% price-worse regression.
