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

Real-money misses:
- NVDA 8/10 212.5C — limit 2.95, option ran 2.95→3.25 within 2 min → expired unfilled.
- AAPL 8/14 315C — limit 2.51 while the option was already 2.55–2.61 **at submit** → never
  marketable → expired.

**Interim mitigation already shipped (config only):** `max_slippage_abs = 0` on all copytrade
tenants, so the effective limit is `price × (1 + max_slippage_pct)` = `price × 1.05`.

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

**Forks A and B stand as resolved.** Fork A: `repeg_after_ms = 30000` (30s of the 90s TTL) — and
under the revised Fork C this delay is **no longer a regression window**, because the initial peg now
equals today's limit, so the wait costs nothing relative to current behavior. Fork B: BTO-only for
v1; symmetric STC re-peg deferred to Phase 4.

---

## Activation — ships ACTIVE (supersedes the original dark-ship gating)

**Defaults, as code constants beside `DEFAULT_PENDING_TTL_PAPER_SECS`
(`CopytradeSignalWorkflowImpl.java:313`):**

| field | default | rationale |
|---|---|---|
| `repeg_after_ms` | `30000` | 30s at today's limit, then ~60s of the 90s TTL at the re-peg |
| `repeg_ceiling_pct` | `0.10` | doubles today's 5% cap; covers the AAPL miss (needed +7.1%) |

Against the two incidents: **AAPL is fixed** (ceiling $2.63 ≥ the $2.56 needed). **NVDA is bounded,
not chased** — its peak needed **+16%** over the signal price; the bot re-pegs to $3.09 and fills
only if the option trades back through it. Declining to chase a +16% runner is the ceiling working
as intended, not a gap.

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
2. **`repeg_bounded_at_ceiling` (NVDA repro):** ask runs to 3.25 with ceiling 3.10 → re-pegs to
   exactly 3.10, does **not** chase further, `EntryExpired` at TTL. Assert exactly **one** re-peg
   `OrderSubmitted`.
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
