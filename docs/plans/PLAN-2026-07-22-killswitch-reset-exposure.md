# PLAN — 2026-07-22 show open exposure/MTM on account kill-switch reset (issue #591, risk C6)

Fast-follow to #590 (account-cap no-auto-flatten). After an alert-only account-cap trip, an operator
can reset the kill switch to resume entries while still holding an underwater book — the post-reset
cooldown suppresses a re-trip within the window, so a reset can silently mask a still-underwater
position ("reset to trade again" done blind). Surface the current open exposure (position count +
unrealized MTM) on the reset path so the operator sees what they still hold BEFORE resuming.

**Source:** GitHub issue #591 (risk-manager condition C6). Reset-flow map: 2026-07-22 investigation.

**Key constraint (why we cache):** a Temporal `@QueryMethod` cannot dispatch activities, so the
`account_killswitch_state` query (which the banner reads BEFORE the reset click) can only surface
exposure if the workflow CACHES the last-heartbeat book into queryable instance state. The heartbeat
already values the book (`valueOpenBook`) every tick, and `maybeRepageWhileHolding` re-values it every
tick while tripped+holding (the reset scenario) — so caching those results is free and stays fresh up
to the click. This is the ACCOUNT-CAP-accurate figure (the same `(liveBid−entry)×qty×100` the cap
trips on), not a possibly-divergent per-tenant portfolio number.

## P0 — operator: none (code + contract + UI; no live mutation).

## Phase 1 — cache + expose open exposure on the account kill-switch (contract + orchestrator)
**Goal:** the workflow caches last-heartbeat `{open_positions, open_mtm}` and returns them from the
state query + enriches the reset audit. Isolated backend; ships + deploys first.

**Anchors (verified by reading main @ 6729685):**
1. `contract/schemas/killswitch-state.json` — add two OPTIONAL (out of `required`) properties:
   - `open_positions`: `{"type":"integer","minimum":0,"description":"Account-cap open-position count
     (listed) from the last heartbeat's open-book read. Null for the per-strategy kill switch (only
     the AccountKillSwitchWorkflow populates it). Advisory/observability only."}`
   - `open_mtm`: `{"type":"number","description":"Account-cap unrealized open P&L
     ((liveBid−entry)×qty×100) from the last heartbeat, when the book was fully priceable; null when
     unpriceable or per-strategy. SIGNED (a gain is positive) — NOT a loss amount and NOT position
     value."}`
   - schema_version keeps `minimum:1`, NO maximum, NO bump — additive optional fields are backward-
     compatible for the fail-closed `check_entry` consumer that reads only `tripped`.
   - Regen: Java POJO at build; run `contract/python/regen.sh` and COMMIT the regenerated
     `killswitch_state.py` (CI pydantic-drift job).
2. `services/orchestrator/.../workflows/AccountKillSwitchWorkflowImpl.java`
   - add instance fields `private Integer lastOpenPositions;` `private BigDecimal lastOpenMtm;`
     (null until first valued heartbeat).
   - after `valued = valueOpenBook(book)` in `heartbeat()` (~`:736`/just before the failsClosed
     block) — cache: `this.lastOpenPositions = book.listed();` and, ONLY when priceable
     (`valued.quoteFailures() == 0`), `this.lastOpenMtm = valued.openMtm();` else leave/null the MTM
     (honest: don't show a partial MTM as the total). A book that can't be read at all (throw) leaves
     the last-known values untouched.
   - in `maybeRepageWhileHolding()` (~`:900-934`, where it values the book while tripped+holding)
     cache the same two fields — keeps the display fresh right up to the reset click.
   - `killswitchState()` (`:1256-1266`) — `s.setOpenPositions(lastOpenPositions);`
     `s.setOpenMtm(lastOpenMtm);` (nullable pass-through).
   - `reset(...)` audit subject (`:1239-1252`) — add `open_positions`/`open_mtm` from the cached
     fields when non-null (mirrors the `doTrip`/still-holding subjects). Payload only.
   - **NO `getVersion` marker:** caching is a pure state write from an already-dispatched activity
     result; the query is not replayed; the audit subject is activity-input payload. Zero command-
     shape change. A legacy-replay assertion still confirms byte-identical replay.

**Tests (TDD):**
- heartbeat values a priceable 2-position book → `killswitchState()` returns `open_positions=2`,
  `open_mtm=<signed sum>`.
- unpriceable book (quoteFailures>0) → `open_positions` set, `open_mtm` null (no partial MTM shown).
- `reset()` audit subject carries `open_positions`/`open_mtm` when cached; omits when null.
- per-strategy `KillSwitchState` path (or a fresh account workflow pre-first-heartbeat) → both null.
- `AccountKillSwitchWorkflowImplLegacyReplayTest`: an existing history replays byte-identically (the
  new cache writes add no commands).
- pydantic round-trip: regenerated `killswitch_state.py`, no drift.

**Verify:** `contract/python/regen.sh` committed; `mvn -pl contract -pl services/orchestrator -am
spotless:apply` + `spotless:check`; `mvn -pl services/orchestrator -am test
-Dtest=AccountKillSwitchWorkflowImplTest,AccountKillSwitchWorkflowImplLegacyReplayTest`.
`KillSwitchWorkflowImplTest` is a known flake (re-run).

## Phase 2 — surface exposure in the reset UI (tenant-dashboard-bff + dashboard)
**Goal:** the operator sees "still holding N positions · unrealized P&L ±$X" at the reset control,
BEFORE clicking, on both `/live` and `/status`. Ships after Phase 1 is deployed (reads the new field).

**Anchors (verified via reset-flow map — implementer re-reads before editing):**
1. `services/tenant-dashboard-bff/.../web/AccountKillSwitchController.java` (`GET`, ~`:90-94`) — map
   `open_positions`/`open_mtm` from the `account_killswitch_state` query result into the GET body.
2. `dashboard/lib/bff.ts` — add `openPositions?: number` / `openMtm?: number` to the
   `AccountKillSwitch` interface (~`:93-98`) and populate in `getAccountKillSwitch()` (~`:99`).
3. `dashboard/components/AccountGuardBanner.tsx` (~`:96-101`, `:121-126`) — thread the two values into
   `<AccountKillSwitchReset>`.
4. `dashboard/components/AccountKillSwitchReset.tsx` (~`:72-119`) — render an exposure summary line
   next to the reset button: "You are still holding **N** position(s) · unrealized P&L **±$X**" when
   `openPositions != null`. Sign the MTM (green +, red −) and label it "unrealized P&L" — NEVER an
   unsigned/`value` label (a gain must not read as underwater; mirror the still-holding embed's
   `signedUnrealizedPnl`). When `openMtm` is null show "unrealized P&L: unavailable (book
   unpriceable)". When `openPositions` is 0/null, show nothing (flat book — no blind-reset risk).
5. `dashboard/app/status/page.tsx` — mirror the same surface (the /status reset is a parallel copy).

**Tests:** dashboard component/render test — reset control shows the signed exposure line when
`openPositions>0`, hides it when flat, and renders "unavailable" when `openMtm` null. (Match the
dashboard's existing test style; if none exists for these components, add a minimal render assertion.)

**Verify:** dashboard build/lint + component test green; manual: a tripped tenant holding a book shows
the exposure line above the reset button on `/live` and `/status`; a flat tripped tenant shows none.

## Ship order & gating
1. **Phase 1** (isolated backend, contract bump; no command-shape change, no getVersion) → merge +
   deploy. 2. **Phase 2** (dashboard, reads the new field) → merge. Each: TDD, spotless on every
   touched Java module, own PR, operator merge gate (real-money kill-switch path). No `tenants/*.yaml`
   / ConfigMap change. Commit trailer:
   `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
