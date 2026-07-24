# PLAN-2026-07-24 — Onboard UI: add a strategy to an existing tenant via dropdowns

Turn the operator Onboard page's two free-text identity fields (`tenant_id`, `strategy_id`) into a
tenant **combo-box** (pick an existing tenant OR type a new one) + a strategy **select** that offers
only the strategies the chosen tenant does NOT already have. Selecting a strategy loads the correct
per-strategy config template. Primary use case: add `watchlist-trigger-v1` to an existing live
tenant (e.g. `prod-kipark`) without hand-typing ids or hand-editing a copytrade template.

**Pure frontend (dashboard/Next.js).** No api-gateway / orchestrator / Temporal / DB-schema change:
the existing `POST /admin/tenants/{tenant}/strategies/{strategy}` already adds a strategy to an
existing tenant (`INSERT … ON CONFLICT (tenant_id, strategy_id) DO NOTHING`, forces `enabled=false`)
and the tenant/strategy list already ships via `getAdminTenants()`.

Source: investigation 2026-07-24 (onboard page + admin-tenants BFF + the add-strategy write path).

## 1. Current state (anchors verified)

- **Form identity fields** — `dashboard/components/OnboardForm.tsx:305-325`: two free-text `<input>`s
  bound to `tenant`/`strategy` React state (`useState`), placeholders `acme` / `copytrade-v1`. These
  feed all five server actions via `formData.set("tenant_id"/"strategy_id", …)` (`:252-276`).
- **Config template** — `dashboard/app/admin/onboard/page.tsx:50-115`: `prodConfig(brokerTarget)` is
  a **copytrade-only** blob (author_whitelist, skip_avg, partial_fractions, default_stc_fraction, …).
  `DEFAULT_CONFIG`/`LIVE_CONFIG` = paper/live variants, passed to `OnboardForm` and shown in the
  config textarea. There is **no watchlist template** and **no strategy catalog** anywhere in the repo.
- **Write path** — `page.tsx:122-161` `createTenantAction` → `createTenant(tenant, strategy, config)`
  (`lib/adminOnboarding.ts:32`) → `POST /admin/tenants/{tenant}/strategies/{strategy}`. Server-side
  it forces `config.tenant_id/strategy_id` to the path pair and `enabled:false`. Validates `ID_RE`.
- **Listing data** — `getAdminTenants()` (`lib/adminBff.ts:46`) → `AdminTenantsResponse.items:
  AdminTenantItem[]` where each item = `{tenant_id, strategy_id, broker_target, mode:"live"|"paper",
  activation_state, …}` (`:18-35`), sourced entirely from `strategy_config`. Throws
  `AdminReadDisabledError` on a 404 (BFF `operator.admin-read.enabled` off) — the caller must degrade.
- **The Onboard page is a server component** (`page.tsx:117` `async function OnboardPage`), so it can
  `await getAdminTenants()` and pass the derived data to `OnboardForm` as props — same pattern the
  `/admin/tenants` page already uses.

## 2. P0 / operator notes (no code)

- No dark-flag change needed. The dropdowns are additive; the create route stays gated by
  `OPERATOR_TENANT_CREATE_ENABLED` and degrades read-only exactly as today.
- **Watchlist caveat (drives Phase 2):** creating a `watchlist-trigger-v1` row via this UI is
  necessary but NOT sufficient to run watchlist — it also needs the sidecar
  `WATCHLIST_MIRROR_ADDITIONAL_TARGETS` mapping + a real-time stock feed, both out-of-band (see
  PLAN/investigation on adding watchlist to prod-kipark). The UI must SAY this, not imply completeness.

## 3. Phases

### Phase 1 — Tenant combo-box + unused-strategy select + template swap (dashboard)

**Goal:** replace the two free-text identity inputs with a pick-or-type tenant combo-box and a
strategy select scoped to the tenant's unused strategies; loading the right config template on select.

**Changes** (anchors):
- `dashboard/app/admin/onboard/page.tsx`:
  - Add a **strategy catalog** constant: `{ "copytrade-v1": copytradeConfig, "watchlist-trigger-v1":
    watchlistConfig }`, each a `(brokerTarget) => jsonString` template. `copytradeConfig` = the
    existing `prodConfig` (rename, behavior-identical). `watchlistConfig` = a new template derived
    from `tenants/dev/strategies/watchlist-trigger-v1.yaml` (tp_ratio, sl_pct, tp_partial_fraction,
    trail_giveback_pct, no_progress_time_stop_secs, watchlist_expiry_rule, gap_tolerance_pct,
    entry_mode, sl_pct, the exit floors, `capital_source:account_cash`, `enabled:false`), retargeted
    per `brokerTarget`. Omit the same three unsafe fields the copytrade template omits
    (broker_account_id, alert_webhook_url, tenant_id/strategy_id).
  - In `OnboardPage`, `await getAdminTenants()` inside a try/catch; on success build and pass
    `existingTenants: Array<{ tenantId, strategies: string[], mode: "live"|"paper" }>` (group items by
    tenant_id). On `AdminReadDisabledError`/any error pass `existingTenants: null` (degrade → the form
    falls back to today's free-text behavior). Pass the strategy catalog's config templates as props
    (a `{[strategyId]: {paper, live}}` map) so the client can swap the textarea without a round-trip.
- `dashboard/components/OnboardForm.tsx:305-325`:
  - **Tenant field** → an editable combo-box: an `<input list="ob-tenant-list">` + a `<datalist
    id="ob-tenant-list">` of `existingTenants.map(t => t.tenantId)`. Type-or-pick; a typed value not in
    the list is a NEW tenant (unchanged create-new flow). Keep the `ID_RE` hint text.
  - **Strategy field** → a `<select>` whose options are the *unused* strategies for the currently
    selected tenant: `catalogStrategyIds.filter(s => !usedForSelectedTenant.has(s))`. For a tenant not
    in `existingTenants` (a new tenant) offer the full catalog. When `existingTenants == null`
    (degraded), fall back to the current free-text `<input>` for BOTH fields so the page never regresses.
  - **On strategy change**, swap the config textarea to `templates[strategy][mode]` (mirrors how the
    existing paper/live Mode toggle already retargets the template — reuse that effect at
    `OnboardForm.tsx` around the mode state).
  - **On existing-tenant select**, default the Mode toggle to that tenant's `mode` (a live tenant →
    live template/endpoints) so the operator can't accidentally paper-target a live tenant's new strategy.

**Tests (dashboard has jest/RTL — mirror an existing component test):**
- `OnboardForm` renders the tenant datalist from `existingTenants` and the strategy select excludes
  already-used strategies for the selected tenant.
- Selecting `watchlist-trigger-v1` swaps the textarea to the watchlist template (assert a
  watchlist-only key like `tp_ratio` appears, a copytrade-only key like `author_whitelist` does not).
- Degraded mode (`existingTenants == null`) renders the two free-text inputs (no regression).
- Server action unchanged: still `ID_RE`-validates and forces ids onto the config (existing test stays green).

**Verify / success criteria:**
```
cd dashboard && npx tsc --noEmit && npm run build   # clean
```
Behavioral: on the live page, picking `prod-kipark` shows only `watchlist-trigger-v1` in the strategy
select (copytrade-v1 is hidden as already-used); selecting it loads the watchlist template with
`broker_target:alpaca-live` and `enabled:false`; submitting creates the disabled row (201/200).
Typing a brand-new tenant id still offers the full catalog and creates a new tenant (unchanged).

### Phase 2 — Watchlist "won't-arm" advisory (dashboard)

**Goal:** when `watchlist-trigger-v1` is selected, make clear the created row is dormant until the
out-of-band sidecar target + stock feed are wired — so the operator doesn't assume the UI finished the job.

**Changes** (anchors):
- `dashboard/components/OnboardForm.tsx` (near the create section, ~`:379`): when `strategy ===
  "watchlist-trigger-v1"`, render a yellow advisory note: "Creates a DISABLED row. Watchlist also
  needs the Discord sidecar `WATCHLIST_MIRROR_ADDITIONAL_TARGETS=<tenant>:watchlist-trigger-v1` (+
  sidecar restart) and a real-time stock feed before it can arm — these are out-of-band." Static copy,
  no logic. Keep it strategy-conditioned so copytrade onboarding is visually unchanged.

**Tests:** the advisory shows for watchlist-trigger and is absent for copytrade-v1.

**Verify:** `npx tsc --noEmit && npm run build` clean; visual check the note renders only for watchlist.

## 4. Forks

**Fork A — tenant control shape.** (a) editable datalist combo-box (pick-or-type, one field, keeps
new-tenant creation) — RECOMMENDED, matches the operator's combo-box ask and is native HTML; (b) a
"New tenant | Existing tenant" mode toggle swapping free-text vs a pure `<select>` (more explicit,
more UI). Recommend (a).

**Fork B — strategy catalog source.** (a) a small hardcoded catalog in `page.tsx` (KISS — there are
exactly two strategies and their templates already have to live in the page) — RECOMMENDED; (b) a new
BFF "strategy catalog" endpoint (over-engineered for two static strategies). Recommend (a); revisit if
a third strategy type ever lands.

## 5. Ship order & gating

1. **Phase 1** (dropdowns + template swap) — the feature; its own PR.
2. **Phase 2** (watchlist advisory) — small copy add; can fold into Phase 1's PR or ship after.

Frontend-only: no Temporal replay, no ConfigMap drift, no `kubectl apply`. Standard dashboard CI
(`tsc`, `next build`, jest). PR body at create time; commit trailer per CLAUDE.md.
