# PLAN — 2026-08-04 copytrade de-risk-on-follow-up-cue

**Incident source:** forensic investigation of Fri 2026-07-31 live-tenant BTO losses (incident-forensics, this session).

On 2026-07-31 the copytrade source TradingTheTrend posted a lottery BTO —
`BTO INTC 8/03 95c @ 1.29 risky` (12:46 CT / 13:46 ET) — then **10 minutes later, in a
separate message with no BTO grammar**, posted the risk escalation:
`I'm cool with going 0 or hero on these. Feel free to use your own stop` (12:56 CT / 13:56 ET).
By the time that escalation landed we were already filled at **50 contracts @ 1.34** on each live
tenant. The INTC 95c expired worthless (bid $0.01 at expiry) → **−$6,700/tenant, −$13,400 combined,
~88.6% of the entire day's real-money loss.** The sidecar **drops** the escalation message entirely
today (the parser only recognizes BTO/STC/AVG grammar), so nothing acted on it.

**Goal of this plan:** when a signal author follows a BTO with a separate "0-or-hero / use-your-own-stop"
message, automatically **de-risk the already-open position** by (1) trimming it to a small keep-fraction
and (2) arming the existing chandelier trailing stop on the remainder. Had this shipped, Friday's INTC
would have been trimmed to ~12 contracts + trailed, cutting the loss from −$13,400 to **~−$960 combined
in the decay case** while preserving the "hero" upside on the retained lot.

This is NOT an entry-sizing change — the word "risky" is too common (most BTOs carry it) and is explicitly
NOT a trigger. The trigger is the separate escalation message, attributed to that author's preceding BTO.

---

## Design summary (agreed with operator)

- **Trigger cue** — a message with **no BTO/STC/AVG grammar** whose normalized text contains a
  "0-or-hero" family phrase (`0 or hero`, `zero or hero`, `0-or-hero`, `go 0 or hero`), or
  `use your own stop` / `your own stop`. Explicitly **not** `risky`.
- **Attribution** — the **same-author** preceding BTO (skip other authors' interleaved messages),
  within a bounded look-back window (~current session / 60 min):
  - Cue names a ticker that matches one of that author's recent open BTOs → target **that ticker's**
    preceding BTO.
  - Generic ("these", no ticker) → the author's **single most-recent** preceding BTO.
  - No attributable open position (unfilled/failed BTO, or a named ticker with no preceding BTO from
    that author) → **benign no-op + info audit**. Never fall back to a different lot.
- **Action** on the attributed open copytrade position, per subscribing tenant:
  1. **Trim** — reduce-only partial exit that keeps `derisk_keep_fraction` (default 0.25) of the
     current remaining quantity (reuses the existing STC partial-exit pipeline).
  2. **Arm trail** — send the existing `armChandelier` signal (giveback = `trail_giveback_pct`) so the
     retained lot trails its premium high-water and exits on a giveback retrace.
- **Rollout** — auto-live (attribution is deterministic; arming a trail is non-destructive and the trim
  reuses the audited partial pipeline). Behind a per-tenant config gate + a sidecar env flag; **canary
  on `staging_paper` before `prod_real` / `prod-kipark`.**

**Why a trailing stop and not just a trim:** "0 or hero" means the author wants the moonshot — a trim
alone throws away the upside on the sold lot; the trail floors the downside on the *retained* lot while
letting it run. The chandelier trail already ships for copytrade (armed today only on first partial); we
add a new arm trigger, not new trail machinery.

---

## Replay-safety strategy (read first — shapes the phase boundaries)

The de-risk cue is delivered as a **brand-new workflow type** (`CopytradeDeriskWorkflow`), started by the
sidecar exactly as `CopytradeSignalWorkflow` is started per signal. Consequences:

- The new workflow has **no prior histories** → its command flow needs **no `getVersion` gate**.
- It reaches the target position via the **existing, already-safe** signal handlers on
  `PositionWorkflowImpl`: `partialExit` (pre-existing, `PositionWorkflowImpl.java:1609`) and
  `armChandelier` (already gated behind `VERSION_CHANDELIER`, `PositionWorkflowImpl.java:1671`). We add
  **no new signal handler and no new command shape** to any running workflow, so no new replay gate is
  required on `PositionWorkflowImpl` or `CopytradeSignalWorkflowImpl`.
- We deliberately do **not** deliver the cue as a new signal into the existing `CopytradeSignalWorkflow`
  (that would perturb a running workflow's command stream and force a gate). A separate workflow keeps
  the hot path byte-identical.

This is the single most important design choice: it turns a scary "touch the live workflow" change into
"add an isolated new workflow that reuses two already-shipped signals."

---

## P0 — Immediate operational (no code; operator)

- **prod-kipark INTC 8/03 195c intent still `RECORDED`** (the mis-parsed nonexistent-strike order from
  Friday 13:36 ET). prod_real's twin was already terminalized to `ERRORED` on 08-04; prod-kipark's was
  not, and it emits a recurring `JournalOrphan` alert. Terminalize it the same way. *This is a pre-existing
  cleanup surfaced by the forensics, not part of this feature — listed so it isn't lost.*
- No open real-money exposure from Friday remains (INTC/SPY closed; QQQ/INTC-195c never filled).

---

## Phase 1 — Contract: `CopytradeDeriskPayload` (module: `contract`)

**Goal:** add the new event DTO carrying the resolved de-risk target, generated for Java + Python.

**Changes** (anchors):
- `contract/schemas/copytrade-signal-payload.json` — add a **new sibling schema file**
  `contract/schemas/copytrade-derisk-payload.json` (do NOT overload the `action` enum of
  `CopytradeSignalPayload`, which is `["BTO","STC","AVG"]` at line 61-65). New schema fields, mirroring
  the identity/scope fields already on `copytrade-signal-payload.json:26-110`:
  - `schema_version`, `tenant_id`, `strategy_id`, `signal_id` (`<cue_message_id>:derisk`), `message_id`,
    `author` (cue message author), `posted_at`.
  - Target BTO tuple (resolved by the sidecar): `ticker`, `expiry`, `strike`, `right` — same shapes as
    `copytrade-signal-payload.json:66-84`. OCC resolution stays downstream via `ContractActivities.resolve`,
    identical to the BTO path.
  - `target_bto_signal_id` (string) — the preceding BTO's `signal_id`, for audit correlation.
  - `target_entry_premium` (number) — the preceding BTO's stated price, used only as the chandelier
    peak **seed** (ratchets up on live ticks regardless). Optional (out of `required`).
  - `matched_cue` (string) — the normalized cue phrase that fired, for audit.
  - `raw_line` (string) — the cue message text, for audit.
- Regenerate DTOs (constraint #6): the build regenerates the Java POJO
  (`com.ohmytradeagent.contract.CopytradeDeriskPayload`) and the Python pydantic model
  (`contract/python/ohmytradeagent_contract/models/copytrade_derisk_payload.py`). Optional fields go OUT
  of `required`.

**Tests (TDD):**
- Contract round-trip / schema-drift check (the existing generator + Python round-trip drift job) passes
  with the new schema — a Java↔Python serialize/deserialize of a fully-populated and a minimal
  (required-only) `CopytradeDeriskPayload`.

**Verify / success criteria:**
- `mvn -pl contract -am spotless:apply && mvn -pl contract -am verify` green; the Python model-drift check
  passes. No runtime behavior yet (pure DTO). No audit kinds, no ConfigMap, no workflow changes in this
  phase.

---

## Phase 2 — Orchestrator: `CopytradeDeriskWorkflow` + config + audit kinds (module: `orchestrator`, `audit`)

**Goal:** a new isolated workflow that resolves the target position and issues trim + arm-trail, gated by
per-tenant config, fully dark until enabled.

**Changes** (anchors):
- **New config fields** in `contract/schemas/strategy-config.json` (regenerate DTOs; both optional,
  null/absent = feature OFF, byte-identical no-op — mirror the `entry_scale_in_fraction` opt-in note at
  `strategy-config.json:122-127`):
  - `derisk_on_followup_cue` (boolean) — per-tenant enable. Unset/false → the workflow no-ops with an audit.
  - `derisk_keep_fraction` (number, `exclusiveMinimum:0`, `maximum:1`, default 0.25 when the feature is
    enabled but the field is null) — fraction of the current position to KEEP.
  - Reuse the existing `trail_giveback_pct` (`strategy-config.json:178-182`) for the arm. Enablement
    (Phase 4) must set it — an unset giveback makes `armChandelier` reject with `invalid_giveback`
    (documented at line 182), so the trim would still apply but the trail would not arm.
- **New workflow** `services/orchestrator/.../workflows/CopytradeDeriskWorkflow[Impl].java`, modeled on
  `CopytradeSignalWorkflowImpl.handleStc` (the exact trim+arm pattern lives at
  `CopytradeSignalWorkflowImpl.java:1131-1168`):
  1. Load `StrategyConfig`; if `!derisk_on_followup_cue` → audit `KIND_DERISK_SKIPPED_DISABLED`, return.
  2. Resolve OCC from the payload tuple (`ContractActivities.resolve`, same as the BTO/STC path) →
     compute `positionWorkflowId` with the existing derivation used for STC routing.
  3. Get the position stub; **trim** via `stub.signal("partialExit", req)` where
     `req.fraction = 1 − keepFraction` (reuse `PartialExitRequest`; the existing handler at
     `PositionWorkflowImpl.java:1609-1659` applies it to live `remainingQty`, inherits dup-suppression,
     `min_partial_qty_behavior`, and exit repricing; fraction < 1 keeps it reduce-only, never a full close).
     Use the **cue's** `signal_id` so it never collides with an STC's dedup key (`processedSignalIds`,
     `PositionWorkflowImpl.java:1634`).
  4. **Arm trail** via `stub.signal("armChandelier", arm)` with `arm.peakPremium = target_entry_premium`
     (seed) and `arm.givebackPct = trail_giveback_pct` — byte-for-byte the arm block at
     `CopytradeSignalWorkflowImpl.java:1150-1168`.
  5. Wrap both `stub.signal(...)` calls in the **same** `catch (SignalExternalWorkflowException |
     ApplicationFailure e)` guard used at `CopytradeSignalWorkflowImpl.java:1133-1146`, so a
     closed/absent target position (Friday's QQQ / INTC-195c case) → audit
     `KIND_DERISK_NO_OPEN_POSITION` (benign, YELLOW, non-paging) and return — never a hard failure.
- **New audit kinds** (constraint #5) registered in `services/audit/.../AuditEventKinds.ALL_KINDS`
  (`AuditEventKinds.java:142`): `DeriskTrimRequested`, `DeriskArmRequested` (or reuse the existing
  `ChandelierArmRequested`, `CopytradeSignalWorkflowImpl.java:86`), `DeriskNoOpenPosition`,
  `DeriskSkippedDisabled`. The benign no-op kinds go in `ALL_KINDS` only (observability, like
  `StcNoOpenPosition` at `AuditEventKinds.java:163-165`), NOT in any lifecycle/paging group. Register the
  worker + workflow type so the new workflow is deployable but receives no traffic until Phase 3.

**Replay:** new workflow → no prior histories → **no `getVersion` gate needed**. No change to the command
shape of `CopytradeSignalWorkflowImpl` or `PositionWorkflowImpl`.

**Tests (TDD):**
- `CopytradeDeriskWorkflowImplTest`:
  - **Incident reproduction:** enabled config (`derisk_on_followup_cue=true`, `derisk_keep_fraction=0.25`,
    `trail_giveback_pct=0.30`), a de-risk payload targeting an open 50-contract INTC 95c position →
    asserts a `partialExit` with `fraction = 0.75` AND an `armChandelier(giveback=0.30)` are signaled to
    the correct `positionWorkflowId`, and `DeriskTrimRequested` + arm audits are emitted.
  - Feature disabled (`derisk_on_followup_cue` null/false) → no `partialExit`, no `armChandelier`, one
    `DeriskSkippedDisabled` audit (proves the dark-ship no-op).
  - Target position closed/absent → catch path → `DeriskNoOpenPosition`, no unhandled failure.
  - `keep_fraction` null while enabled → defaults to 0.25 (fraction 0.75 closed).
- `KindRegistryGuardTest` passes (new kinds present in `ALL_KINDS`).

**Verify / success criteria:**
- `mvn -pl services/orchestrator,services/audit,contract -am spotless:apply` then
  `mvn -pl services/orchestrator,services/audit -am test` green.
- Behavioral assertion tied to the incident: *"enabled config + open 50-lot INTC target →
  `partialExit(fraction=0.75)` + `armChandelier(giveback=0.30)` signaled; disabled config → neither
  signaled."*
- `KillSwitchWorkflowImplTest` is a known flake — re-run, do not fix (constraint #8).

---

## Phase 3 — Sidecar: cue detection + per-author BTO tracking + emit (module: `signal-source-discord`)

**Goal:** recognize the escalation message, attribute it to the author's preceding BTO, and start the new
workflow per subscribing tenant — behind an env flag, default off.

**Changes** (anchors):
- `services/signal-source-discord/ohmytradeagent_sidecar/parser.py` — add a pure
  `classify_derisk(text) -> DeriskCue | None` (mirrors `ScaleInMatcher` style, but in Python and only for
  messages that produced **no** `ParsedSignal`). Normalize (lowercase, collapse whitespace, map the word
  `zero`→`0`, strip hyphens) then substring-match the "0 or hero" family + `use your own stop` /
  `your own stop`. Extract candidate uppercase ticker tokens for the ticker-aware path. Keep it a pure
  function alongside `parse_message` (`parser.py:64`); do NOT change `_LINE_RE` (`parser.py:20-34`).
- `services/signal-source-discord/ohmytradeagent_sidecar/watcher.py` — in the per-message handler around
  the `parse_message(m.content)` call (`watcher.py:168`):
  - When a message yields BTOs, record them into a **per-author bounded ring**:
    `author -> deque[(ticker, expiry, strike, right, price, signal_id, posted_at)]`, pruned to the
    look-back window (~60 min / session). `author` is already on every message and on
    `CopytradeSignalPayload` (`copytrade-signal-payload.json:51-54`).
  - When a message yields no BTOs, call `classify_derisk`. On a hit, resolve the target from the **same
    author's** ring (ticker-aware → else most-recent). On no attributable BTO → structured log/metric +
    (optional) alert, emit nothing.
  - On a resolved target, build a `CopytradeDeriskPayload` per subscribing tenant via the existing
    fan-out (`fanout_registry.py`) and emit through a new emitter path.
  - **Gate the whole de-risk branch on an env flag `DERISK_CUE_ENABLED`** (default off — mirror the
    `STC_INTENT_ENRICH_ENABLED` pattern, `main.py:157-173`). Off → behavior byte-identical to today.
- `services/signal-source-discord/ohmytradeagent_sidecar/emitter.py` — add `workflow_id_for_derisk` +
  `emit_derisk` that `start_workflow` the new `CopytradeDeriskWorkflow` (mirror `emitter.py:45-101`,
  reuse `_start_workflow_deduped` keyed on the cue `signal_id` for replica-dedup).

**Tests (TDD):**
- `test_parser.py` / `test_derisk.py`:
  - **Incident reproduction:** the exact Friday text `"I'm cool with going 0 or hero on these. Feel free
    to use your own stop"` → `classify_derisk` returns a cue with no explicit ticker.
  - Variants: `"0 or hero"`, `"zero or hero"`, `"0-or-hero"`, `"go 0 or hero"`, `"use your own stop"` all
    match; `"BTO INTC 8/03 95c @ 1.29 risky"` and a bare `"risky"` do **not** (regression guard: risky is
    not a cue).
  - Ticker-aware: `"0 or hero on INTC"` resolves to a recorded INTC BTO; `"0 or hero on TSLA"` with no
    TSLA BTO from that author → no target.
- `test_watcher.py`:
  - Attribution skips an interleaved OTHER-author message and picks the correct author's preceding BTO.
  - Cue with no preceding same-author BTO → no emit.
  - `DERISK_CUE_ENABLED` off → no emit even on a clear cue (dark-ship guard).
  - A resolved cue → one `emit_derisk` per fan-out tenant, payload carries the resolved tuple +
    `target_bto_signal_id` + `target_entry_premium`.

**Verify / success criteria:**
- `pytest` for the sidecar + `ruff` clean (CI runs both; matches the stc-intent-service CI pattern).
- Behavioral assertion: *"Friday's escalation text with a preceding same-author INTC BTO in the window and
  `DERISK_CUE_ENABLED=true` emits one `CopytradeDeriskPayload` per subscribed tenant targeting INTC 95c;
  the same text with `risky`-only or no preceding BTO emits nothing."*

---

## Phase 4 — Enablement / canary (operator; no code)

**Goal:** turn the feature on safely, paper first.

Order (each step verified before the next):
1. Deploy Phases 1-3 images. The new `CopytradeDeriskWorkflow` worker is live but receives no traffic
   (`DERISK_CUE_ENABLED` off, per-tenant `derisk_on_followup_cue` unset).
2. **`staging_paper` canary** (DB CAS on `tenant_config`, mirroring the scale-in / stc-intent rollouts):
   set `derisk_on_followup_cue=true`, `derisk_keep_fraction=0.25`, and ensure `trail_giveback_pct=0.30`.
   Flip the sidecar `DERISK_CUE_ENABLED=true` env (env is NOT applied by deploy — manual
   `kubectl set env` / manifest apply; constraint #7). Watch one real escalation-message day: confirm
   `DeriskTrimRequested` + `ChandelierArmRequested` audits and an actual trim + trailed exit on the paper
   book; confirm no false trims on ordinary chatter.
3. **Promote to live** — after a clean paper window, DB CAS the same three config values on `prod_real`
   then `prod-kipark`. `staging_paper` / `prod_real` strategy YAMLs are live-cluster-only (constraint #4),
   so these are out-of-band operator edits, not repo phases.

**Rollback:** DB CAS `derisk_on_followup_cue=false` (per tenant) or flip `DERISK_CUE_ENABLED=false`
(sidecar-wide). Both are instant and byte-identical to today's behavior.

---

## Ship order & gating

1. **Phase 1** (contract DTO — pure, no runtime) →
2. **Phase 2** (orchestrator new workflow + config + audit kinds — dark, no traffic; no replay gate needed
   since it's a new workflow reusing already-safe signals) →
3. **Phase 3** (sidecar detection + emit — behind `DERISK_CUE_ENABLED`, default off) →
4. **Phase 4** (operator enablement, `staging_paper` canary → live).

Each code phase: TDD-first incl. the Friday reproduction, `spotless:apply` on every touched module, its
own single-concern PR, operator merge gate (trading-critical). Do not touch `.github/workflows/*.yml`
(constraint #9). `gh pr edit --body` is broken here — set the PR body at create time or via
`gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`.

## Open items / assumptions flagged

- **Trim math:** we express the target as a KEEP fraction (0.25) and pass `1 − keep` to the existing
  fraction-based `partialExit`, so the PositionWorkflow floors the CLOSED qty against live `remainingQty`
  (50 → close 37, keep 13 — vs the illustrative "keep 12"; functionally equivalent, and it inherits all
  existing partial-exit safety). If exact keep-qty parity is required, a target-qty exit variant is a
  small follow-up — flagged, not built.
- **Burst BTOs:** if several BTOs from the same author immediately precede the cue, we anchor to the
  single most-recent (batch-of-several de-risk is a later extension).
- **Look-back window** default ~60 min / session — tune during the paper canary.
- **`use your own stop` as a cue:** included as a secondary trigger. If it proves noisy on the paper
  canary, drop it and keep the "0 or hero" family only (one-line vocab change in `classify_derisk`).
