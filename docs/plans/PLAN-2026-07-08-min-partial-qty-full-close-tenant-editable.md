# PLAN 2026-07-08 — min_partial_qty_behavior = full_close (real-money) + tenant-editable /config control

**Author:** remediation-architect · **Team:** trading-remediation
**Motivating case:** prod_real `copytrade-v1` last-lot partial STC is SKIPPED, so the final contract never exits on a "sell half" signal.
**Status:** PLAN ONLY — not implemented.

---

## Incident summary

prod_real `copytrade-v1` runs `min_partial_qty_behavior = "skip"`. When a position is down to its
last lot (`remainingQty <= 1`) and an incoming partial STC's fraction floors to 0 contracts
(`floor(remainingQty * fraction) == 0`), the runner-quantum gate emits `PartialExitSkippedMinQty`
and places **no order** — the last lot never exits on partial signals. It only exits on a full-close
keyword, stop/target, expiry, or trail. Today two open runners (NVDA, SPCX) are sitting at 1 lot
because of this.

**Operator intent:** flip the behavior to `full_close` so a partial STC on the last lot CLOSES it,
and expose `min_partial_qty_behavior` (and, generally, every enum/typed field) as an **intuitive
control** in the tenant-facing `/config` UI instead of a typo-prone free-text box.

**Key fact — this is NOT new logic.** `full_close` already exists as a schema enum value and is
already wired end-to-end and version-gated (deployed). This plan is a **config-VALUE flip** plus a
**UI control** change. No workflow logic, no new getVersion, no contract schema change.

### Verified anchors (read 2026-07-08)

| Fact | Anchor |
|---|---|
| Enum `["skip","full_close"]` in contract | `contract/schemas/strategy-config.json:280-283` |
| Runner-quantum gate (SKIP vs FULL_CLOSE) | `services/orchestrator/.../workflows/PositionWorkflowImpl.java:2162-2199` |
| Version gate (already deployed) | `PositionWorkflowImpl.java:2170-2171` `Workflow.getVersion(VERSION_MIN_PARTIAL_QTY_SKIP, DEFAULT, 1)` |
| SKIP branch emits `PARTIAL_EXIT_SKIPPED_MIN_QTY` | `PositionWorkflowImpl.java:2179-2194` |
| FULL_CLOSE branch flushes last lot (`qtyToClose = remainingQty`) | `PositionWorkflowImpl.java:2195-2196` |
| Behavior baked into PositionWorkflowInput at START (copytrade) | `CopytradeSignalWorkflowImpl.java:861-867` |
| …baked at START (adoption) | `AdoptionWorkflowImpl.java:282-285` |
| dev yaml value (repo) | `tenants/dev/strategies/copytrade-v1.yaml:85` `min_partial_qty_behavior: skip` |
| Generated DTO enum + `fromValue` (throws on unknown) | `contract/java/target/generated-sources/jsonschema2pojo/.../StrategyConfig.java:2514-2544` |
| Field-class map exposed to UI (min_partial NOT listed → SAFE) | `services/tenant-dashboard-bff/.../platform/StrategyConfigReader.java:35-51` |
| Writer field-class governance (SAFE = freely writable) | `services/orchestrator/.../platform/StrategyConfigWriter.java:37-45` |
| `/config` render loop + field metadata lookup | `dashboard/app/config/page.tsx:525-534` |
| `FieldValue` control selection (bool→select, num/string→input) | `dashboard/app/config/page.tsx:94-135` |
| `saveConfig` posts the raw string value | `dashboard/app/config/page.tsx:367-380` |
| Field metadata source (single source of truth) | `dashboard/components/ConfigFieldReference.tsx:221-227` (entry), `:280-282` (`CONFIG_FIELD_INFO`) |

---

## P0 — operator follow-ups (real money, no code)

### P0.1 — Flip prod_real `copytrade-v1` `min_partial_qty_behavior` `skip → full_close`

- Strategy config is DB-backed on prod_real (`STRATEGY_CONFIG_SOURCE=db`). This is a **`strategy_config`
  DB update**, NOT a repo edit. prod_real's config is live-cluster-only (not in the repo), so there
  is **no ConfigMap drift and no `tenants/dev/*` edit** for this flip.
- Two ways to make the change: (a) direct `strategy_config` row update on the orchestrator DB now,
  or (b) via the `/config` write path **once Phase 1 ships and `STRATEGY_CONFIG_WRITE_ENABLED` is on**.
  For the immediate fix, use (a); (b) becomes the durable tenant-self-service path.

- **BAKED-AT-START (state to operator):** the behavior is copied into `PositionWorkflowInput` when a
  PositionWorkflow STARTS (`CopytradeSignalWorkflowImpl.java:861-867`, `AdoptionWorkflowImpl.java:282-285`).
  Therefore the flip applies to positions **entered AFTER** the change. The currently-open NVDA and
  SPCX 1-lot runners keep their baked `skip` value — to exit those two, an **explicit full-close
  keyword STC is still required** (the flip will not retro-close them).

### P0.2 — Risk sign-off judgment

**My judgment: risk-manager sign-off is NOT strictly required, but notify.** This toggle makes exits
**more conservative**, not more aggressive: `full_close` banks the last runner on the author's trim
signal instead of holding a naked 1-lot to trail/EOD/expiry. It reduces overnight/expiry exposure and
places no larger order than the position already holds (`qtyToClose = remainingQty`, capped at 1 lot).
There is no sizing, entry, or cap change. Recommend a one-line heads-up to the risk owner for the
audit trail, but this does not warrant a blocking dual-control review. **Flag for the lead to confirm.**

---

## Backend writability — VERIFIED, no backend phase needed

`min_partial_qty_behavior` is **absent** from the IDENTITY / DANGEROUS / EXPOSURE lists
(`StrategyConfigReader.java:37-51`), so it falls into the **SAFE** (freely writable) class. The
existing write path already accepts it:

`StrategyConfigWriter` SAFE write → api-gateway `StrategyConfigController` → BFF `/config` `saveConfig`
→ `postStrategyConfig`, gated by the existing dark flag `STRATEGY_CONFIG_WRITE_ENABLED`.

Server-side enum validation is already enforced: the request JSON deserializes into the generated
`StrategyConfig` DTO, whose `MinPartialQtyBehavior.fromValue(...)` (`StrategyConfig.java:2542-2544`)
throws on any value outside `[skip, full_close]` — so an out-of-enum value is rejected at the DTO
boundary (HTTP 4xx) regardless of the UI. The same holds for the other generated enums (`capital_source`,
`entry_mode`, `watchlist_expiry_rule`).

**Conclusion: no backend change. Reuse the existing write path.** The UI phase is purely a UX/typo-
prevention layer on top of the already-authoritative server validation. **Do not drop or duplicate
server-side validation.**

---

## Phase 1 (only code phase) — /config intuitive controls: generic enum dropdown (+ time input)

**One concern, one PR.** Make the `/config` editable-field renderer choose its control from field
metadata instead of always rendering strings as free text. The **core deliverable** is the generic
enum→`<select>` mechanism (covers `min_partial_qty_behavior` and every current/future enum field);
a same-mechanism **time-of-day HH:MM control** for the two `_et` fields is included below and can be
split to a P1b follow-up if the lead prefers a smaller PR (see Forks).

### Current state (verified)

`FieldValue` (`page.tsx:94-135`) renders editable scalars as: boolean → `<select>` true/false;
number → number input; **string → free-text `<input type="text">`**. So `min_partial_qty_behavior`,
`capital_source`, `entry_mode`, `watchlist_expiry_rule`, and the `_et` time fields ALL render as raw
text boxes today — only server-rejected on a typo. The render loop already computes
`const info = CONFIG_FIELD_INFO[field]` at `page.tsx:534` but does not yet use it to pick a control.
`saveConfig` (`page.tsx:367-380`) posts the raw string value — a `<select>`/time input is a drop-in
for the string branch, so **`saveConfig` needs no change**.

### Field-control audit (verified against `contract/schemas/strategy-config.json` + `StrategyConfigReader.java:35-51`)

Only **editable** fields (SAFE or EXPOSURE; IDENTITY/DANGEROUS render read-only and are unaffected):

| Field | Type | Class | Chosen control | Allowed values / source |
|---|---|---|---|---|
| `min_partial_qty_behavior` | string enum | SAFE | **`<select>`** | `[skip, full_close]` (schema `:282`) |
| `capital_source` | string enum | SAFE | **`<select>`** | `[static, account_cash]` |
| `entry_mode` | string enum | SAFE | **`<select>`** | `[BREAKOUT, RETEST]` |
| `watchlist_expiry_rule` | string enum | SAFE | **`<select>`** (single option) | `[NEAREST_WEEKLY]` |
| `broker_target` | string enum | **DANGEROUS** | read-only span (no change) | n/a — not editable in UI |
| `force_close_0dte_et` | string HH:MM | SAFE | **time / HH:MM input** | pattern `^([01][0-9]|2[0-3]):[0-5][0-9]$` |
| `force_close_eod_et` | string HH:MM | SAFE | **time / HH:MM input** | same pattern |
| `alert_webhook_url` | string (free) | SAFE | text input (keep) | genuinely free text |
| all boolean fields (`skip_avg`, `trail_on_partial`, `pre_trade_check_enabled`, `halt_check_enabled`, `eod_force_flatten`, `enabled`) | boolean | SAFE | `<select>` true/false (already done — confirm) | n/a |
| all number fields | number | SAFE/EXPOSURE | number input (already done) | optional: add `min/max/step` for 0–1 pct fields (deferred, see Forks) |
| `author_whitelist`, `partial_fractions`, `sector_overrides` | array/object | SAFE | read-only JSON (keep) | editing arrays/objects is out of scope |

### Change — generic, metadata-driven (KISS, one mechanism)

1. Extend the `ConfigField` metadata interface in `dashboard/components/ConfigFieldReference.tsx`
   (interface at `:10-15`) with an optional control hint, e.g.:
   - `options?: { value: string; label: string }[]` — presence ⇒ render `<select>`.
   - `control?: "time"` — render an HH:MM time input.
   Populate these on the relevant entries in `CONFIG_FIELDS` (the `min_partial_qty_behavior` entry is
   `ConfigFieldReference.tsx:221-227`; add `capital_source`, `entry_mode`, `watchlist_expiry_rule`,
   `force_close_0dte_et`, `force_close_eod_et`). `CONFIG_FIELD_INFO` (`:280-282`) already derives from
   `CONFIG_FIELDS`, so this is the existing single source of truth — no new lookup table.
   Human labels e.g. `skip → "Skip (leave last lot)"`, `full_close → "Full close (exit last lot on any trim)"`.

2. In `FieldValue` (`page.tsx:94-135`), before the existing `kind === "number" || kind === "string"`
   branch, add: **if the field's metadata has `options`, render a `<select>` over those options**
   (defaulting to the current `value`); **if `control === "time"`, render `<input type="time">`**.
   Fall through to the existing text/number input otherwise. Pass the field's `info`/metadata into
   `FieldValue` (the render loop already has `info` at `page.tsx:534`; thread it through the
   `FieldValue({...})` props at the call site ~`page.tsx:560+`).

3. `saveConfig` unchanged — a `<select>`/time input posts a plain string via the existing
   `nextConfig[field] = String(raw)` path (`page.tsx:380`).

**Why generic, not a per-field switch:** one metadata-driven rule (`options ⇒ select`) covers all four
current enums and every future enum automatically — no special-casing `min_partial_qty_behavior`. This
is the minimal generalization CLAUDE.md §2 favors, not a form rewrite.

### Constraints folded into Phase 1

- **Replay:** none — dashboard-only. No workflow code, no command shape, no getVersion.
- **No contract schema change:** the enums already exist in `strategy-config.json`; no Python regen,
  no `contract/java` rebuild driven by this PR.
- **Dark flag unchanged:** editing stays gated by the existing `STRATEGY_CONFIG_WRITE_ENABLED`
  (`page.tsx` `WRITE_ENABLED`, `:532`). No flag flip in this PR.
- **No new audit kind:** reuse the existing StrategyConfig write/audit path (SAFE write).
- **Server stays authoritative:** the dropdown is UX only; do NOT remove the DTO `fromValue` enum
  validation. Its rejection of bad values is the real gate.
- **No ConfigMap drift, no `tenants/dev/*` edit:** Phase 1 touches only `dashboard/`. (If a future
  choice edits `tenants/dev/strategies/copytrade-v1.yaml:85`, re-sync
  `infra/k8s/40-tenants-config.yaml` via `scripts/check-tenants-configmap-drift.py` — NOT needed here.)
- **Spotless:** N/A (no Java). Run the dashboard lint/format the repo uses (`npm run lint`).

### TDD success criteria — Phase 1

Dashboard tests (extend the existing `/config` test suite):
1. A field with `options` metadata (`min_partial_qty_behavior`) renders a `<select>` whose options are
   **exactly** `[skip, full_close]` (with the human labels), defaulted to the current value — and NOT
   a free-text input.
2. `capital_source`, `entry_mode`, `watchlist_expiry_rule` each render a `<select>` over their exact
   schema enums; `force_close_0dte_et`/`_et` render a time/HH:MM control; `alert_webhook_url` stays a
   text input; array/object fields stay read-only JSON.
3. Submitting the form posts the selected string value through the unchanged `saveConfig`/
   `postStrategyConfig` path (assert the outgoing `nextConfig[field]` equals the chosen option).
4. An out-of-enum value is not selectable from the dropdown (structural — options are the only choices).

**Verify command:** `cd dashboard && npm run lint && npm test` (config page suite green).

### Behavioral repro (incident-equivalent) — belongs to the orchestrator, assert in PositionWorkflowImplTest

Extend the existing Issue #205 cases (`PositionWorkflowImplTest`, ~`:2316`):
- `PartialExitRequest(fraction=0.3)` on `remainingQty=1` with `min_partial_qty_behavior=full_close`
  ⇒ `qtyToClose == 1` (last lot CLOSED; a `PARTIAL_EXIT_REQUESTED` exit is placed).
- Same request with `min_partial_qty_behavior=skip` ⇒ `PartialExitSkippedMinQty` emitted, no order.

This is the incident assertion (proves the flip fixes the reported behavior). It exercises code that is
**already deployed**, so it is a regression guard, not a gate on Phase 1's UI PR.
**Verify command:** `./gradlew :services:orchestrator:test --tests '*PositionWorkflowImplTest*'`
(`KillSwitchWorkflowImplTest` is flaky here — re-run, don't fix, if it trips in the same module run).

---

## Ship order

1. **P0.1 (operator DB flip)** — immediate, independent of code. Fixes new positions on prod_real now.
   Optionally hold P0.1 to route through the UI after Phase 1 if the operator prefers a UI-audited change.
2. **Phase 1 (dashboard PR)** — the tenant-editable intuitive control. Independent, UI-only, dark-gated.

No inter-phase dependency: the backend already accepts the value, so the UI and the DB flip are
orthogonal. Risk order is trivially satisfied (no backend/workflow change ships at all).

---

## Replay-safe / no-schema-change / no-ConfigMap-drift note

- **Replay-safe:** the only workflow-adjacent element (`VERSION_MIN_PARTIAL_QTY_SKIP`) is ALREADY
  deployed; this plan adds NO getVersion and NO command-shape change. The config value is baked into
  `PositionWorkflowInput` at workflow START, so in-flight runners are untouched and there is no replay
  divergence for open workflows.
- **No contract schema change:** `[skip, full_close]` (and the other enums) already exist in
  `strategy-config.json`; no schema edit, no jsonschema2pojo/Python regen.
- **No ConfigMap drift for prod_real:** prod_real config is DB/live-only (not in the repo). The P0
  flip is an operator DB action. Phase 1 touches only `dashboard/`. No `tenants/dev/*` file changes,
  so `infra/k8s/40-tenants-config.yaml` needs no re-sync.

---

## Forks for the lead / user to resolve

1. **Scope of Phase 1 (coordinator-relayed broadening).** The original user ask was the enum-dropdown
   generalization (motivated by `min_partial_qty_behavior`). A coordinator relay (no user authority)
   added "intuitive control for EVERY field type," including time inputs for `_et` fields and a full
   audit. I have folded the verified audit + time control into Phase 1 because it rides the SAME generic
   metadata mechanism and is low-cost. **Confirm with the user** whether Phase 1 should ship (a) enum
   dropdowns only, or (b) enum dropdowns + `_et` time inputs together. Default if unanswered: (b) — the
   incremental cost is one metadata flag and the drift risk of guessing wrong is low.
2. **DRY vs schema (enum values hardcoded in the dashboard).** Option (a) hardcodes the enum options in
   `CONFIG_FIELDS` metadata (repo-consistent — the dashboard already hardcodes per-field help; server
   stays authoritative, so a drift only yields a stale option that the server still rejects). Option (b)
   surfaces the schema `enum` to the UI via BFF field metadata (no duplication, but new plumbing).
   **Recommended: (a)** for KISS; flag (b) as a future hardening if enum churn becomes a maintenance cost.
3. **Number-field polish (deferred).** Adding `min/max/step` to the 0–1 pct number inputs is a nice-to-
   have not required by the finding; recommend deferring out of this PR to keep it single-concern.
4. **P0 risk sign-off** (see P0.2) — confirm the "notify, not block" judgment.
