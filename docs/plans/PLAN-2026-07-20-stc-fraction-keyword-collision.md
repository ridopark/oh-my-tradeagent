# PLAN — 2026-07-20 STC fraction keyword collision (partial-intent liquidated to full close)

On 2026-07-20 a live prod_real STC `STC NVDA 7/27 200p @ 2.44 partial. Taking profit as it comes`
**liquidated the entire 50-lot position** instead of scaling out. The tail contains two configured
keywords mapping to different fractions — `partial → 0.3` and `taking profit → 1.0`.
`KeywordPartialMatcher` resolves by **longest-key-wins**, so `"taking profit"` (13 chars) beat
`"partial"` (7 chars) → `fraction = 1.0` → full close. Had `"partial"` won, it would have sold
`ceil(50 × 0.3) = 15` and left a 35-lot runner, matching the trader's "as it comes" intent.

Financial outcome was benign (sold 50 @ avg 2.37 vs 2.23 entry, ≈ +$700 gross, position flat, no
exposure). The defect is **semantic**: an explicitly-partial signal was silently full-closed. This
is a direct sibling of PLAN-2026-07-01-unrecognized-stc-tail-alert (same matcher, same alerter) and
the prior `"cutting" → half` vocabulary work.

**Source:** live forensic trace (this session) — `order_intent_journal` (exec_alpaca_live) +
`audit_log` (orchestrator), acct 847309116. Ground-truth events: `ExitRequested
{fraction:1.0, matched_keyword:"taking profit"}`, `PartialExitFilled {qty_filled:50,
remaining_qty_after:0}`, `PositionClosed`.

## Design decision (surfaced fork — not silently picked)

When a tail matches **multiple keys mapping to different fractions**, "longest key wins" is the
wrong policy. Two candidate policies:

- **(A) Conservative — smallest fraction wins on collision. [RECOMMENDED]** On a multi-fraction
  match, pick the smallest fraction. Never over-liquidates; the failure mode becomes "left a runner
  we could have closed" (recoverable by a follow-on STC) instead of "liquidated a position we meant
  to scale" (irrecoverable). Config-agnostic, no keyword classification needed. For this incident:
  `min(0.3, 1.0) = 0.3` → sells 15, keeps 35. Correct.
- **(B) Explicit-partial priority.** Classify keywords as partial-type vs full-close-type and prefer
  a partial-type keyword when present. More expressive but needs a new config dimension (which keys
  are "partial") and more surface area. Rejected as over-engineered for a single-operator vocabulary
  (KISS) — but noted in case the operator wants full-close words to win when unambiguous.

This plan implements **(A)**. If the operator prefers (B), Phase 1's policy swaps but the phase
shape is unchanged.

**Follow-up (NOT in this PR) — risk-off collision carve-out.** `quant-analyst` review (2026-07-20)
endorsed (A) as the right default for *profit-taking* collisions, with one caveat: smallest-fraction
is wrong when the full-close keyword expresses an urgent **risk-off / "cut it now"** intent on a
*losing* position — there, 0.3 leaves 70% of the exposure on the book when the operator wanted flat,
and the alert only helps if they react inside the gamma/gap window. A minimal, KISS follow-up would
carve out a tiny set of urgent flatten tokens ("stop", "emergency", "out now") that force full close
even in a collision. **Deliberately deferred** because it (a) is a new policy/config dimension beyond
this fix's scope and (b) collides with this operator's existing vocabulary where `"cutting" → half`
(a *partial* word, not a flatten word) — so which tokens count as urgent-risk-off is a real decision,
not a mechanical add. The Phase-2 collision page is the interim mitigation. Revisit if a risk-off STC
is ever under-sold in practice.

## P0 — Immediate operational (no code; operator)

- **None.** Position is flat, no orphan, no blocked order, no overnight exposure. No cleanup needed.
- Awareness only: until Phase 1 ships, any live tail combining a `<1.0` "partial/trim/half" word
  with a `1.0` "taking profit / out / close / all" word will **full-close**. If such a signal
  arrives before ship, expect full liquidation and re-buy manually if a runner was intended.

## Phase 1 — Conservative fraction selection on keyword collision (orchestrator, pure domain)

**Goal:** When multiple keywords match with differing fractions, resolve to the **smallest**
(most-conservative) fraction instead of the longest key, and report that a collision occurred.

**Changes** (anchors — verified by reading):
- `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/domain/KeywordPartialMatcher.java:59`
  — replace the longest-key tie-break (`lowerKey.length() > bestKey.length()`) with **smallest-fraction-wins**:
  track the minimum matched fraction; on ties in fraction, keep the longest key for a stable
  `matchedKey`. Iterate all matches (do not early-exit).
- `KeywordPartialMatcher.java:27` (`MatchResult`) — add a `boolean fractionCollision` field, set true
  when ≥2 matched keys map to **different** fraction values. Keep `match()` (line 33) byte-identical
  (delegates to `matchReporting().fraction()`), and keep the existing 2-arg call sites compiling.
- `KeywordPartialMatcher.java:7-14` — update the class Javadoc: the "longest matching key wins /
  ties impossible" contract is now "smallest fraction wins on multi-fraction match; collision
  flagged."

**Replay safety:** none required. `KeywordPartialMatcher` is a pure function; the resolved fraction
feeds a **signal payload** (`PartialExitRequest.setFraction`), not a Temporal command shape. Temporal
1.27 replay checks command type/ordering, not signal/activity **input values**
(`reference_temporal_replay_activity_input`), so changing the computed fraction on replay of an
in-flight history cannot wedge it. No `Workflow.getVersion` gate. State this explicitly in the PR.

**Tests (TDD)** — `services/orchestrator/src/test/java/com/ohmytradeagent/orchestrator/domain/KeywordPartialMatcherTest.java`:
- **Incident reproduction:** `matchReporting("partial. Taking profit as it comes", {"partial":0.3,"taking profit":1.0}, 0.3)`
  → `fraction == 0.3`, `matchedKey == "partial"`, `fractionCollision == true`. (Pre-fix this returns 1.0.)
- Single match unchanged: `{"out":1.0}` on `"all out"` → 1.0, no collision.
- Same-fraction multi-match is **not** a collision: `{"partial":0.5,"trim":0.5}` on `"partial trim"`
  → 0.5, `fractionCollision == false`.
- No match → default, no collision (existing behavior preserved).
- `match()` delegation still returns the fraction only and equals `matchReporting().fraction()`.

**Verify / success criteria:**
`mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator -am test -Dtest=KeywordPartialMatcherTest`.
Behavioral assertion: the incident tail resolves to **0.3** (sell `ceil(remaining×0.3)`, keep a
runner), and `fractionCollision` is true. Spotless on `services/orchestrator`.

## Phase 2 — Page the operator on a fraction collision (orchestrator alerter, out-of-workflow)

**Goal:** When an STC tail collides on fractions, page so the operator can confirm the
conservative auto-pick was right (or manually finish the close). Defense-in-depth on top of Phase 1.

**Changes** (anchors):
- `services/orchestrator/.../workflows/CopytradeSignalWorkflowImpl.java:1005-1012` — extend the
  existing **subject-only** `ExitRequested` enrichment (already replay-safe per the in-code comment;
  activity-input payloads are ignored on replay) with `"fraction_collision", matchResult.fractionCollision()`
  and the full matched-keyword set (e.g. `"matched_keywords", <comma-joined>`). **No new command, no
  version gate** — mirrors the PLAN-2026-07-01 enrichment two lines up.
- `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/alert/UnrecognizedStcTailAlerter.java`
  — extend the existing out-of-workflow alerter that reads `ExitRequested` subjects: in addition to
  its current "non-empty tail matched nothing" page, also page when `fraction_collision == true`,
  with a distinct message ("STC tail matched multiple fractions; auto-resolved conservatively to
  X — verify"). Reuse its existing Discord routing/dedup; do **not** add a new audit KIND (avoids
  `KindRegistryGuardTest` surface — the signal rides the existing `ExitRequested` subject).

**Replay safety:** none (subject enrichment only; alerter is out-of-workflow reading persisted audit
rows). Confirm no new `logAudit` KIND is introduced.

**Tests (TDD):**
- `UnrecognizedStcTailAlerterTest` — new case: an `ExitRequested` row with `fraction_collision=true`
  triggers exactly one collision page with the resolved fraction in the body; a row with
  `fraction_collision=false` and a matched keyword triggers none; the existing "matched nothing"
  case still pages (no regression).
- Optionally assert the enriched subject shape in a `CopytradeSignalWorkflowImpl` test if one already
  exercises `ExitRequested` subjects; otherwise the alerter test covers the contract.

**Verify / success criteria:**
`mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator -am test -Dtest=UnrecognizedStcTailAlerterTest`.
Behavioral assertion: the incident tail produces one collision page naming fraction 0.3; no new
audit KIND registered (no `KindRegistryGuardTest` change). Spotless on `services/orchestrator`.

## Ship order & gating

1. **Phase 1** (pure domain, zero replay/config/audit surface — lowest blast radius) → own PR.
2. **Phase 2** (subject enrichment + out-of-workflow alerter) → own PR, merges after Phase 1 so the
   page reflects the corrected fraction.

Both phases: TDD-first with the incident reproduction, `spotless:apply` on `services/orchestrator`
before commit, one PR each, operator merge gate (trading-critical path). Neither phase needs a
Temporal version gate, a ConfigMap-drift re-sync (no `tenants/*.yaml` edit — the fix is in code,
config-agnostic), or a new audit KIND. No `deploy.yml`/shared-manifest apply required.

Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
`gh pr edit --body` is broken here — set the PR body at create time.
