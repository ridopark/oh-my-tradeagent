# PLAN — 2026-07-25 STC close-intent classifier (full-close recall gap)

**Incident.** On 2026-07-24 a real-money STC — `STC META 7/27 590p @ 2.37 bears can't finish taking the W. Don't want to see it go red again` — closed only 30% of the position (prod_real 9→24 left, prod-kipark 11→21 left). Cause: the close-fraction is resolved by keyword-matching the free-text tail (`CopytradeSignalWorkflowImpl.handleStc` → `KeywordPartialMatcher`), the tail matched **no** keyword, so it fell through to `default_stc_fraction = 0.3`. The author's clear "get out" intent was invisible to the matcher.

**Scope of the gap (measured, not guessed).** The full `ExitRequested` audit history (118 events, 61 worded tails, 2026-05-27→07-24, ~26 unique author-tails) shows the deterministic matcher catches only **~50–67% of full-close intents** (`recall[full]`) while nailing 100% of partials — a structural asymmetry, and the under-close direction is the money-losing one. Two more real mislabels beyond the incident: `"closing to keep my winrate…"` under-closed (keyword is `close`, not a substring of `closing`), and `"partial. Taking profit as it comes"` **over-closed** to 1.0. Bench harness + corpus: `scratchpad/stc_bench.py` (delivered).

**Approach.** Add a small **close-intent classifier** (encoder or small LLM — selected by benchmark, swappable behind one HTTP contract) that runs **in the Discord sidecar, entirely outside Temporal**, and enriches the signal with `close_intent ∈ {full, partial}`. The orchestrator consumes it **behind a per-tenant `stc_intent_enforce` flag** with the keyword matcher as the permanent fallback. **Shadow-mode first**: enrich + audit the classifier's verdict without acting on it, for weeks, then flip enforce per live tenant. The classifier only arbitrates **full vs partial**; partial *sizing* still comes from keywords/default (which already scores 100% recall on partials — don't disturb it).

**Replay boundary (the key concern) — resolved.** The fraction feeds `PartialExitRequest.setFraction(...)` dispatched via a single `stub.signal("partialExit", req)` command. There is **no separate full-close command path** — 0.3 vs 1.0 is the same command with a different payload value, and signal/activity input payloads are **not** replay-checked on Temporal 1.27. The file already documents this exact precedent (`CopytradeSignalWorkflowImpl.java:1017,1029`, PLAN-2026-07-01 / -07-20 audit enrichments: *"no new command, no version gate"*). Therefore **no `Workflow.getVersion` gate is required** for Phase 3, provided the classifier is *additive* and the keyword path is preserved byte-for-byte when `close_intent` is absent (old histories) or enforce is off.

Source findings: this session's forensics + `scratchpad/stc_bench.py`. Related: `project_stc_fraction_keyword_collision`, `project_benign_stc_no_position`, `reference_temporal_replay_activity_input`.

---

## P0 — Immediate operational (no code; operator)

- **Benchmark on the homelab node** (`stc_bench.py --backend encoder|ollama --iters 400`) to pick the backend by **p99 latency** and **`recall[full]`**. Decision rule: choose the option that pushes `recall[full]`→~100% (must get the incident tail, `"closing…"`, and the empty/bare-STC tail right) **without** dropping `recall[partial]` below 100%, within the latency budget. Encoder (~10ms CPU, deterministic) is the front-runner; a warm 3B LLM is the fallback if the encoder's accuracy is short.
- **Stand up the model service (dark), memory-limited, in ns `copytrade`**, exposing the Phase-2 HTTP `/classify` contract at a stable cluster URL. Nothing calls it yet.
  - LLM backend → Ollama image + pulled model (`llama3.2:3b`/`qwen2.5:3b`), `resources.limits.memory` set so it cannot starve exec/orchestrator.
  - Encoder backend → a ~40-line FastAPI/onnxruntime wrapper serving the classifier. (Alternative considered: run the encoder in-process in the sidecar — rejected as default because it drags `torch` into the already memory-pressured Chromium sidecar, 2Gi, which OOMs easily; keep the model in its own pod. Revisit only if you want to avoid a pod and accept the image bloat.)
- **Model-service + sidecar-env manifest is operator-applied** (`deploy.yml` only applies per-service manifests; a new shared manifest needs a manual `kubectl apply`). Codify it durably in a manifest PR (mirrors the #630 fan-out bundle pattern) — see "Operator follow-ups".

**Fork to confirm with user before P0 completes:** encoder vs LLM backend. Doesn't change any app phase (both sit behind `/classify`), only the P0 provisioning. Default recommendation: encoder.

---

## Phase 1 — Contract: optional `close_intent` on the signal + `stc_intent_enforce` on strategy config (contract)

**Goal:** add the two optional fields the later phases read/write; behavior-neutral (nothing consumes them yet). Lowest blast radius → ships first.

**Changes** (anchors):
- `contract/schemas/copytrade-signal-payload.json` — add optional `close_intent` (`enum: ["full","partial"]`, nullable) and `close_confidence` (`number`, nullable). **Both OUT of `required`** (absent/null = "classifier said nothing"). This one schema regenerates BOTH the Java `CopytradeSignalPayload` POJO (`payload.getCloseIntent()`) and the Python pydantic `CopytradeSignalPayload` the sidecar builds at `watcher.py:209`.
- `contract/schemas/strategy-config.json` — add optional `stc_intent_enforce` (`boolean`, nullable, OUT of `required`; null/absent = disabled). Regenerates `StrategyConfig.getStcIntentEnforce()` + pydantic. Consumed near `CopytradeSignalWorkflowImpl.java:991`.
- No version gate (contract-only). No new audit kind. No `partial-exit-request.json` change (fraction field already exists).

**Tests (TDD):** contract round-trip drift check (the existing Java↔pydantic generator + Python round-trip test) must stay green with the new optional fields; a POJO/pydantic build proving `close_intent`/`stc_intent_enforce` are nullable and default to absent.

**Verify / success criteria:** `mvn -pl contract -am spotless:apply && mvn -pl contract -am verify` + the Python schema round-trip drift check pass. Deserializing a signal payload **without** the new fields yields `close_intent == null` (old producers unaffected). Constraint touched: **checklist #6 (contract regen)**. No ConfigMap drift (no `tenants/dev/*.yaml` edited — the field is optional/absent for all tenants).

---

## Phase 2 — Sidecar: intent-classifier client + enrich STC signals, dark + fail-safe (signal-source-discord)

**Goal:** when enabled, classify the STC tail out-of-band and attach `close_intent`/`close_confidence` to the outbound signal. Off by default; any failure = field absent = today's behavior.

**Changes** (anchors):
- New `services/signal-source-discord/ohmytradeagent_sidecar/stc_intent.py` — HTTP client to the P0 `/classify` service. Constrained response (`{"intent":"full|partial","confidence":0..1}`). **Hard timeout** (`STC_INTENT_TIMEOUT_MS`, default ~300ms). Returns `None` on timeout / transport error / malformed body / `confidence < STC_INTENT_MIN_CONFIDENCE`. Never raises into the caller.
- `services/signal-source-discord/ohmytradeagent_sidecar/watcher.py:209` (`_to_payload`) — when `STC_INTENT_ENRICH_ENABLED` **and** `sig.action == "STC"`, call `stc_intent.classify(sig.tail)`; set `close_intent`/`close_confidence` on the `CopytradeSignalPayload`; else leave both `None`. Non-STC actions never call the classifier.
- `services/signal-source-discord/ohmytradeagent_sidecar/main.py` — read env: `STC_INTENT_ENRICH_ENABLED` (default `false`), `STC_INTENT_URL`, `STC_INTENT_TIMEOUT_MS`, `STC_INTENT_MIN_CONFIDENCE`.
- No Temporal interaction. No contract change (uses Phase-1 fields).

**Tests (TDD, pytest):**
- `enrich_disabled_leaves_intent_none` — flag off → classifier not called, field `None`.
- `enrich_stc_sets_intent` — flag on, STC tail, service returns `full` → payload `close_intent == "full"`.
- `enrich_incident_tail` — the META tail → `close_intent == "full"` (reproduces the fix at the source).
- `enrich_non_stc_skips` — BTO/AVG → classifier not called.
- `enrich_timeout_falls_back_none` and `enrich_bad_body_none` — slow/garbage service → `None`, no exception.
- `enrich_low_confidence_none` — confidence below floor → `None`.

**Verify / success criteria:** `pytest services/signal-source-discord` green. With `STC_INTENT_ENRICH_ENABLED=false` the emitted payload is byte-identical to today (field absent). With it on and the service down, still identical (fail-safe). Ship 2nd (dark, no consumer yet).

---

## Phase 3 — Orchestrator: consume `close_intent` behind `stc_intent_enforce`, shadow-audit always (orchestrator)

**Goal:** the workflow records the classifier's verdict on **every** STC and, only when the tenant's `stc_intent_enforce` is on, lets it override the full-vs-partial decision. Keyword matcher stays the fallback and the partial-sizer.

**Changes** (anchors):
- `CopytradeSignalWorkflowImpl.java:991-996` — keep `matchResult` / keyword `fraction` exactly as-is (the fallback). Add `effectiveFraction`:
  - `boolean enforce = Boolean.TRUE.equals(config.getStcIntentEnforce());`
  - `String intent = payload.getCloseIntent();` (may be null)
  - **PROMOTE-ONLY** mapping (quant + risk review 2026-07-25 — revised from an earlier demote-capable draft):
    - `enforce && intent == FULL` → `1.0` (promote a keyword-missed full exit; fixes the under-close incident). `intent_source="classifier"`.
    - `enforce && intent == PARTIAL` → **defer to keyword**: `effectiveFraction = keywordFraction` (NO demotion). `intent_source="keyword"`.
    - else (enforce off / intent absent) → `keywordFraction`. `intent_source="keyword"`.
  - **Why promote-only:** a `keywordFraction == 1.0` ALWAYS means an *explicit* full-close keyword matched (the default is never 1.0), so demoting it on a PARTIAL verdict would silently override an author's explicit "out"/"all out" on a possibly-wrong classifier call — reintroducing the money-losing under-close. #600's smallest-fraction-wins already resolves the "partial. taking profit" collision at the keyword layer, so no demotion is needed. **Net property: `effectiveFraction >= keywordFraction` always — the classifier can only move an exit toward full, never size it smaller than today.** The PARTIAL verdict is still recorded in the audit (`close_intent`) for shadow review.
  - `req.setFraction(BigDecimal.valueOf(effectiveFraction));` (line 1002 uses `fraction` today → use `effectiveFraction`).
- `CopytradeSignalWorkflowImpl.java:1013-1034` (the `KIND_EXIT_REQUESTED` audit subject) — **extend the existing subject map** (same single `logAudit` call — no new command) with: `close_intent`, `close_confidence`, `keyword_fraction` (the matcher's value), `effective_fraction`, `intent_source` (`"classifier"` when the override applied, else `"keyword"`), `intent_enforced` (bool). This gives the shadow comparison for free on every STC.
- **Replay safety: NO version gate.** Rationale documented inline citing the existing precedent (`:1017,:1029`): `fraction` is a signal-payload value on the unchanged single `partialExit` command; old histories carry no `close_intent` → `intent == null` → `effectiveFraction == keywordFraction` → byte-identical replay; audit change is subject-only (activity input). Add a comment block mirroring the two existing PLAN-dated notes.
- **No new audit kind** (subject enrichment of `ExitRequested`) → no `AuditEventKinds.ALL_KINDS` change, no `KindRegistryGuardTest` impact.

**Tests (TDD, java-architect — `CopytradeSignalWorkflowImplTest`):**
- `stc_enforceOff_usesKeywordFraction_unchanged` — incident tail, `close_intent="full"`, `stc_intent_enforce` absent → `fraction == 0.3` (today's behavior) **and** audit subject carries `close_intent="full"`, `intent_source="keyword"` (shadow proof).
- `stc_enforceOn_fullIntent_promotesToFullClose` — **incident reproduction**: incident tail, `close_intent="full"`, enforce on → `PartialExitRequest.fraction == 1.0`.
- `stc_intentAbsent_replaySafe_keywordPath` — no `close_intent` (old-history shape), enforce on → `fraction` equals the pure-keyword value; asserts the `partialExit` command stream is unchanged.
- `stc_enforceOn_partialIntent_explicitFullKeyword_defersToKeyword` — a tail whose only match is an unambiguous full-close keyword (`"out"` → keyword 1.0), `close_intent="partial"`, enforce on → `fraction == 1.0` (keyword wins; the PARTIAL verdict does NOT demote an explicit full-close), audit `intent_source="keyword"`. (Verifies the promote-only property; replaces an earlier vacuous demote test that #600 made unreachable.)
- `stc_enforceOn_partialIntent_keepsKeywordSizing` — `"partial. Half out"`, `close_intent="partial"`, keyword 0.5 → `fraction == 0.5` (partial sizing untouched).

**Verify / success criteria:** `mvn -pl services/orchestrator -am spotless:apply && mvn -pl services/orchestrator -am test -Dtest=CopytradeSignalWorkflowImplTest` green. Behavioral assertion: **with enforce off, every existing `CopytradeSignalWorkflowImplTest` case is unchanged and the new shadow fields appear in the audit; with enforce on + `close_intent="full"`, the incident tail sizes to 1.0.** Constraints touched: **checklist #1 (replay — asserted no-gate + no-command-change), #2 (spotless: orchestrator + contract)**. Ships **last** (trading workflow). Operator merge gate.

---

## Operator follow-ups (no code)

- **Durable manifest PR** (mirrors #630): model-service Deployment/Service + the sidecar `STC_INTENT_*` env + `stc-intent` target URL, in `infra/k8s/`. `application.yml`/defaults stay dark; homelab opts in. Operator-applied (`kubectl apply`), since `deploy.yml` won't pick up a new shared manifest.
- **Rollout gate (shadow → enforce):**
  1. Deploy P1→P2→P3 images + model service. `kubectl set env` sidecar `STC_INTENT_ENRICH_ENABLED=true` (enrich on). **Leave `stc_intent_enforce` OFF everywhere** → pure shadow.
  2. Observe `ExitRequested` audits for N weeks: compare `close_intent` vs `keyword_fraction`; compute forward `recall[full]` and any over-close/false-full events. The `UnrecognizedStcTailAlerter` deltas are the watch signal.
  3. **Before enforcing on any real-money tenant (risk-manager conditions 2026-07-25):** add an alert/metric on the one direction the promote-only design can make *worse* — a classifier-forced full-close, i.e. `intent_source="classifier" && effective_fraction==1.0 && keyword_fraction<1.0`. Every such event on real money should be eyeballed early. (The audit already carries all fields; the alert is a downstream consumer — a natural Phase-3 follow-up, not a blocker for the workflow PR.)
  4. **GO/NO-GO:** only if forward `recall[full]` rises, no partial-recall regression, **and a low observed false-full rate from hand-labeled shadow data** (audit divergence alone shows disagreement, not correctness) → flip `stc_intent_enforce=true` **per live tenant via DB CAS** (live-tenant config is an operator edit, NOT a repo YAML — `staging_paper`/`prod_real`/`prod-kipark` strategy YAMLs are cluster-only). Order: **staging_paper first**, bake, then prod_real, then prod-kipark. Rollback = flip the flag off (per-tenant, next-STC, no redeploy — a clean kill switch).
- **Not in scope (user decision, 2026-07-24):** flipping `default_stc_fraction` 0.3→1.0. The classifier addresses the recall gap instead; the default stays 0.3.
- **Corpus strategy (user decision, 2026-07-25):** the audit retains STC tails only on `ExitRequested` (61 rows → 26 unique of 360 STC signals seen); the rest live only in Discord. Historical backfill via the sidecar's live Discord **user token** (REST `/channels/{id}/messages`) is **REJECTED** — it's a self-bot pattern on the account that *is* the production real-money feed, and the account is irreplaceable (ban = permanent loss of source). **Primary corpus = shadow mode** (Phase 2 enrich-on/enforce-off logs every live tail next to the keyword verdict). Historical top-ups only via **offline export from a different account** (e.g. DiscordChatExporter), never the prod session.

---

## Ship order & gating

1. **Phase 1** (contract, behavior-neutral) — own PR.
2. **Phase 2** (sidecar, dark behind `STC_INTENT_ENRICH_ENABLED=false`, fail-safe) — own PR.
3. **Phase 3** (orchestrator consume, dark behind `stc_intent_enforce` absent; replay-safe, no version gate) — own PR, **operator merge gate (trading-critical)**.
4. **Operator**: manifest PR + shadow rollout + per-tenant enforce flip (staging_paper → prod_real → prod-kipark).

Each phase: TDD-first (incl. the incident-reproduction test in Phases 2 & 3), `spotless:apply` on every touched Java module, single-concern PR. The trading path only changes behavior at step 4's per-tenant enforce flip — every code phase before it is dark.
