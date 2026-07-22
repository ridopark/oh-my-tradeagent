# PLAN — 2026-07-22 account-cap deferred-fail-close Discord page

Follow-up to PR #606 (account-cap quote-debounce). #606 made a small-book quote blip DEFER the
fail-close instead of tripping (in-tick re-fetch + N=2 consecutive-tick debounce). But the deferred
tick is currently only a `Workflow.getLogger().warn(...)` — invisible to the operator. This is the
same *silent* footgun that ran through the whole 2026-07-21 incident (root cause B was a SILENT
SOD-equity guard with no WARN, and "an alert ≠ protection"). Make the deferred fail-close
operator-visible in Discord: a YELLOW "cap deferred a fail-close on a quote blip — watching" page,
so a chronic every-other-tick quote degradation surfaces instead of hiding until an eventual trip.

**Source:** #606 deferred deviation (b) — "loud-defer is a WARN log not a Discord page (needs a
registered audit kind in services/audit)". Single concern, single PR.

## P0 — operator: none (code change; no live mutation).

## Phase — page the deferred mtm-unavailable fail-close (orchestrator workflow + alerter + audit registry; VERSION-GATED)

**Goal:** on the FIRST deferred tick of a blip episode, emit an audit event that pages Discord YELLOW;
transient single blip → exactly one page, chronic flapping → one page per miss-episode. No change to
the trip path, the loss path, or the CapInactive path. Fail-safe framing (the cap is working — it
caught a blip), never RED.

**Anchors (verified by reading main @ 945e295):**

1. `services/audit/.../AuditEventKinds.java:~327` — add `"AccountKillSwitchMtmDeferred"` to the
   account-cap block of `ALL_KINDS` (observability-only kind, like `AccountKillSwitchStillHolding`).
   Required or `KindRegistryGuardTest` blocks the push.

2. `services/orchestrator/.../workflows/AccountKillSwitchWorkflowImpl.java`
   - `:82-85` — add `private static final String KIND_ACCOUNT_MTM_DEFERRED =
     "AccountKillSwitchMtmDeferred";`
   - `:631-632` — **widen the existing gate** `Workflow.getVersion(VERSION_ACCOUNT_MTM_DEBOUNCE,
     DEFAULT_VERSION, 1)` → `..., 2)`. Do NOT add a second marker name — widening the maxSupported of
     the existing change-id is the correct pattern; in-flight histories that recorded value 1 replay
     as 1 (no emit), new executions get 2.
   - `:726-738` — the defer branch (`if (failsClosed(...) && ++consecutiveMtmUnavailableTicks <
     MTM_UNAVAILABLE_TRIP_TICKS) { WARN; return false; }`). KEEP the WARN. ADD, gated on
     `mtmDebounceVersion >= 2` AND `consecutiveMtmUnavailableTicks == 1` (first defer of the
     episode only — the counter reset-on-clean-tick makes "== 1" the start of each new episode),
     a `auditLog(KIND_ACCOUNT_MTM_DEFERRED, subject(...))` BEFORE `return false`. Subject:
     `trading_day`, `listed` (=book.listed()), `failures` (=combinedFailures),
     `consecutive_ticks` (=consecutiveMtmUnavailableTicks), `trip_ticks` (=MTM_UNAVAILABLE_TRIP_TICKS),
     `scope`="account". Emitting `audit.log(...)` is an ACTIVITY command → command-shape change → the
     `>= 2` gate is mandatory (that is the whole reason for the widen).

3. `services/orchestrator/.../alert/AccountKillSwitchCapAlerter.java`
   - add `static final String KIND_MTM_DEFERRED = "AccountKillSwitchMtmDeferred";`
   - add it to the early-return kind filter (`:78-81`) and a `buildMtmDeferredEmbed(...)` branch in
     `buildEmbed` (`:90-97`). YELLOW (`AlertColors.YELLOW`). Title:
     `:hourglass_flowing_sand: Account cap deferred a fail-close — quote blip on <tenant>`.
     Description: names it as fail-safe ("book momentarily unpriceable; cap did NOT trip — watching,
     will fail-close if it stays unpriceable for <trip_ticks> consecutive ticks"). Fields: tenant_id,
     trading_day, listed, failures, `<consecutive_ticks>/<trip_ticks>`. Non-blocking/never-throws
     contract unchanged.

**Why gated on `consecutive_ticks == 1`:** with `MTM_UNAVAILABLE_TRIP_TICKS = 2` there is only ever
one defer tick before recovery or trip, so "== 1" = once per episode today; if N is raised later it
stays one-page-per-episode instead of per-tick spam. A chronic miss/clean/miss/clean flap resets the
counter to 0 on each clean tick and returns to 1 on the next miss → one page per miss-episode, which
correctly surfaces the degradation. `INACTIVE_ALERT_TICKS = 3` so a 1–2 tick blip never also fires
the RED CapInactive page — no double-paging.

**Tests (TDD):**
- 1 unpriceable tick (blip) → exactly ONE `AccountKillSwitchMtmDeferred` audit emitted, and NO
  `auto:account_mtm_unavailable` trip (reuse the #606 blip scenario, assert the new emit).
- 2 consecutive unpriceable ticks → the deferred page fires ONCE (on tick 1), then the trip fires on
  tick 2 — assert not a second deferred emit on tick 2.
- clean book → no deferred emit.
- genuine `auto:account_daily_loss` → no deferred emit (unchanged path).
- `AccountKillSwitchCapAlerter`: a `AccountKillSwitchMtmDeferred` event → YELLOW embed with the
  tenant + `n/N` field; every other kind still ignored; a null/garbage subject never throws.
- **Legacy replay (REQUIRED):** extend `AccountKillSwitchWorkflowImplLegacyReplayTest` — a v1 history
  (recorded before this change) that took a defer path replays BYTE-IDENTICALLY under the widened
  `..., 2)` gate (no new emit); version-constant name `account-mtm-debounce-v1` unchanged.

**Verify / success criteria:**
- `mvn -pl services/orchestrator -pl services/audit -am spotless:apply` then `spotless:check`.
- `mvn -pl services/orchestrator -am test -Dtest=AccountKillSwitchWorkflowImplTest,AccountKillSwitchWorkflowImplLegacyReplayTest,AccountKillSwitchCapAlerterTest`
  + `mvn -pl services/audit -am test -Dtest=KindRegistryGuardTest` all green.
- Behavioral assertion: the 2026-07-21 single-blip scenario now emits exactly one
  `AccountKillSwitchMtmDeferred` (YELLOW page), still NO trip; 2 consecutive blips → one deferred
  page then a trip. `KillSwitchWorkflowImplTest` is a known flake (re-run, don't fix).

## Ship order & gating
Single PR, VERSION-GATED (widen existing `account-mtm-debounce-v1` to v2). TDD, spotless on
`services/orchestrator` AND `services/audit`, operator merge gate (real-money kill-switch path). New
audit kind registered in `AuditEventKinds.ALL_KINDS`. No `tenants/*.yaml` / ConfigMap / contract-schema
change. Commit trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
