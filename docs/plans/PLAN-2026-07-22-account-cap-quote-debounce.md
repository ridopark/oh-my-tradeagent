# PLAN — 2026-07-22 account-cap quote debounce (stop the over-sensitive small-book fail-close)

Phase 2 of PLAN-2026-07-21-account-cap-failclose-and-silent-inactive, split out as its own PR now
that Phases 1/3/3b shipped (#602/#604/#605). On 2026-07-21 the prod_real account cap tripped
`auto:account_mtm_unavailable` on a **profitable** day because a **single transient option-quote
miss on a 1-position book** fail-closed the cap: `failsClosed` trips the small-book floor when
`listed <= SMALL_BOOK_MAX_POSITIONS(2) && failures >= 1` — one miss is enough. The very next tick
priced the contract fine (a blip). This makes the real-money cap re-trip spuriously on any transient
market-data hiccup while holding 1–2 positions.

**Goal:** require the book to be unpriceable for **N consecutive heartbeats** before fail-closing on
a small book — a single/transient unpriceable tick must NOT trip. Still fail-closed after N ticks
(protection preserved), and a genuine computed loss still trips immediately.

**Source:** 2026-07-21 forensics (RS1) + operator direction (2026-07-22).

## P0 — operator: none (code change; no live mutation).

## Phase — debounce the mtm-unavailable fail-close (orchestrator workflow; VERSION-GATED)
**Anchors (post-#604 — verify by reading):**
- `services/orchestrator/.../workflows/AccountKillSwitchWorkflowImpl.java`
  - fail-close trip site: `:635-645` — `int combinedFailures = book.valueFailures() +
    valued.quoteFailures();` then `if (book.listed() > 0 && failsClosed(book.listed(),
    combinedFailures)) { doTrip("auto:account_mtm_unavailable", ...) }`.
  - `failsClosed(int listed, int valueFailures)` — the small-book floor (`listed <=
    SMALL_BOOK_MAX_POSITIONS && valueFailures >= 1`) + the relative >50% threshold.
  - `SMALL_BOOK_MAX_POSITIONS = 2` (`:229`).
  - the real-loss trip `doTrip("auto:account_daily_loss", ...)` (`:654-655`) — must stay immediate.
  - continue-as-new carry (the `sodEquity` carry sets `schemaVersion`; the new counter must be
    carried the same way so a same-day CAN doesn't reset the debounce).

**Change:**
- Add a workflow-state counter `consecutiveMtmUnavailableTicks`. On a tick where the fail-close
  condition (`book.listed() > 0 && failsClosed(...)`) holds, **increment** it; on any tick that
  prices the book cleanly (no fail-close condition), **reset it to 0**. Only call
  `doTrip("auto:account_mtm_unavailable", ...)` when `consecutiveMtmUnavailableTicks >=
  MTM_UNAVAILABLE_TRIP_TICKS` (default **2**, tunable constant). Below the threshold: defer this
  tick (do not trip), exactly like the not-armed defer — the cap simply doesn't evaluate a trip on a
  book it momentarily can't price.
- Carry `consecutiveMtmUnavailableTicks` across continue-as-new (bump the carry `schemaVersion` like
  `sodEquity`); reset it on a new trading day and on reset/untrip.
- **Scope strictly to the mtm-unavailable path.** The `auto:account_daily_loss` trip (a *computed*
  loss vs threshold) stays immediate — debounce applies ONLY to "can't price the book," never to a
  real loss.
- **Version gate:** `Workflow.getVersion("account-mtm-debounce-v1", DEFAULT_VERSION, 1)` read once at
  stable scope. At v=0, keep the exact trip-on-first-miss behavior (byte-identical command stream);
  the consecutive-tick counter + deferred trip only apply at v>=1. The trip now fires on a *later*
  tick (changed command ordering) so this MUST be gated — existing in-flight histories replay
  identically.

**Replay safety (REQUIRED):** this changes whether/when `doTrip` is emitted → command-shape change →
`getVersion` gate mandatory. Old histories replay on v0 (trip on first miss); debounce only for
executions at v>=1. A legacy-replay test must prove byte-identical replay + a version-constant
name-stability check (mirror `AccountKillSwitchWorkflowImplLegacyReplayTest`).

**Risk posture (confirm in review):** still fail-CLOSED — after N consecutive unpriceable ticks it
DOES trip. The debounce widens the unprotected window by (N-1) heartbeats on a genuine sustained
outage; with a ~per-minute cadence and N=2 that is ~1 extra minute — acceptable vs the current
spurious-trip-on-a-blip. Do NOT loosen the relative >50% large-book threshold — this change touches
ONLY the small-book single-miss floor.

**Tests (TDD):**
- 1 unpriceable tick then a priced tick → **no trip** (counter resets); assert no
  `auto:account_mtm_unavailable` `doTrip`.
- N consecutive unpriceable ticks → trip `auto:account_mtm_unavailable` on the Nth.
- a genuine `auto:account_daily_loss` breach → trips **immediately** (debounce does not apply).
- large-book >50% failure → still fail-closes per the relative threshold (unchanged).
- old-history replay (v0) byte-identical; version-constant name stable.
- counter carried across continue-as-new; reset on new trading day + on reset.

**Verify:** `mvn -pl services/orchestrator -am spotless:apply` + the affected workflow +
legacy-replay tests; behavioral assertion: the 2026-07-21 single-blip scenario produces **no** trip;
2 consecutive blips still trip. `KillSwitchWorkflowImplTest` is a known flake (re-run).

## Ship order & gating
Single PR, VERSION-GATED. TDD, spotless on `services/orchestrator`, operator merge gate (real-money
kill-switch path). No `tenants/*.yaml` / ConfigMap / contract change. Commit trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
