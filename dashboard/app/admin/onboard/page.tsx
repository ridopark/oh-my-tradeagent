import { auth } from "@/auth";
import Link from "next/link";
import { Nav } from "@/components/Nav";
import { OnboardForm, type OnboardActionResult } from "@/components/OnboardForm";
import {
  createTenant,
  enableStrategy,
  postOperatorBrokerCredential,
} from "@/lib/adminOnboarding";
import { createTenantInvite, getAdminTenants } from "@/lib/adminBff";
import type { ExistingTenant } from "@/components/OnboardForm";
import { postActivation } from "@/lib/adminActivation";
import { EMAIL_RE, ID_RE } from "@/lib/validation";

export const dynamic = "force-dynamic";

// Dark-by-default UI gates, mirroring the api-gateway route flags. Unset/anything-else => that step
// renders read-only. Even with these "true" each api-gateway route is itself dark (404s) until its
// own flag is on, so the action degrades gracefully. The LIVE path has its own gates (see below).
const CREATE_ENABLED = process.env.OPERATOR_TENANT_CREATE_ENABLED === "true";
const CREDENTIAL_ENABLED = process.env.OPERATOR_CREDENTIAL_WRITE_ENABLED === "true";
// Mirrors the A1 backend `operator.strategy-enable.enabled` flag; the enable route itself 404s until
// its own flag is on, so step 3 degrades gracefully even if this is set ahead of the backend.
const ENABLE_ENABLED = process.env.OPERATOR_STRATEGY_ENABLE_ENABLED === "true";
// Mirrors the BFF `operator.tenant-invite.enabled` flag; the create-invite BFF route itself 404s until
// its own flag (plus dashboard.writer.enabled) is on, so step 4 degrades gracefully even if this is set
// ahead of the backend.
const INVITE_ENABLED = process.env.OPERATOR_TENANT_INVITE_ENABLED === "true";
// Independent UI kill-switch for step 3b (activate-live) — the highest-stakes, real-money action.
// Same flag the tenants-page ActivateButton reads; the activate-live route is itself dark
// (operator.activation.enabled → 404) until on, so step 3b degrades gracefully. Keep off until cutover.
const ACTIVATION_ENABLED = process.env.OPERATOR_ACTIVATION_ENABLED === "true";

// Paper broker endpoints — the default targets. Live arming rides the separate LIVE mode below.
const DEFAULT_BASE_URL = "https://paper-api.alpaca.markets";
const DEFAULT_WS_URL = "wss://paper-api.alpaca.markets/stream";
// Live (real-money) broker endpoints — used only when the operator picks LIVE mode (dark-gated).
const LIVE_BASE_URL = "https://api.alpaca.markets";
const LIVE_WS_URL = "wss://api.alpaca.markets/stream";

// StrategyConfig template. Mirrors prod_real's production strategy config so a new tenant starts from
// the same battle-tested defaults, differing ONLY in broker_target (alpaca-paper vs alpaca-live).
// tenant_id/strategy_id are injected server-side from the form (so they always match the create path);
// enabled:false creates the tenant dormant. Three prod_real fields are DELIBERATELY OMITTED so this is
// a safe template: broker_account_id (prod_real's real account 847309116 — would pin every new tenant
// to it and fail the R-6.5 account-uniqueness check; the operator supplies the account per-tenant via
// the keys step), alert_webhook_url (prod_real's private Discord webhook — a live secret that must
// never live in source), and tenant_id/strategy_id (the form injects these). The live variant already
// carries the activate-live gate's required invariants (daily_loss_threshold>0,
// notional_cap_pct_of_capital_base set, capital_source=account_cash) so later activation passes.
const copytradeConfig = (brokerTarget: string) =>
  JSON.stringify(
    {
      schema_version: 1,
      broker_target: brokerTarget,
      author_whitelist: ["TradingTheTrend", "Edtrader", "TB22", "beendoubleyou", "ridopark"],
      skip_avg: true,
      max_positions: 5,
      min_contracts: 1,
      max_contracts: 50,
      capital_source: "account_cash",
      capital_weight: 0.2,
      exit_floor_abs: 0.05,
      exit_floor_pct: 0.5,
      expiry_day_floor: 0.01,
      max_slippage_abs: 0.05,
      max_slippage_pct: 0.05,
      trail_on_partial: false,
      eod_force_flatten: false,
      exit_reprice_tick: 0.05,
      exit_reprice_steps: 3,
      force_close_0dte_et: "14:45",
      reset_cooldown_secs: 60,
      daily_loss_threshold: 2500.0,
      default_stc_fraction: 0.3,
      flatten_lead_minutes: 30,
      pending_ttl_live_secs: 30,
      pending_ttl_paper_secs: 90,
      max_signal_age_bto_secs: 30,
      max_signal_age_stc_secs: 60,
      min_partial_qty_behavior: "skip",
      bto_price_move_reject_pct: 0.1,
      notional_cap_pct_of_capital_base: 0.8,
      // Arm the pre-trade affordability gate for new tenants (deliberately ON, unlike prod_real's
      // current config which leaves it off). The gate verifies the account can afford the entry
      // against AVAILABLE CASH (not margin buying power) before submitting. Opt-in: null/false disables.
      pre_trade_check_enabled: true,
      partial_fractions: {
        out: 1.0,
        half: 0.5,
        trim: 0.25,
        close: 1.0,
        third: 0.33,
        "sl hit": 1.0,
        "all out": 1.0,
        cutting: 1.0,
        dumping: 1.0,
        partial: 0.3,
        "half out": 0.5,
        "stop hit": 1.0,
        "two thirds": 0.67,
        "stopped out": 1.0,
        "holding most": 0.2,
        "keeping half": 0.5,
        "taking the l": 1.0,
        "swinging most": 0.25,
        "taking profit": 1.0,
        "keeping stop tight": 0.9,
      },
      enabled: false,
    },
    null,
    2,
  );

// Watchlist-trigger StrategyConfig template. Mirrors tenants/dev/strategies/watchlist-trigger-v1.yaml
// (the shipped-disabled reference) — the 2:1 breakout bracket with the chandelier trail. Same safe
// omissions as copytradeConfig (broker_account_id / alert_webhook_url / tenant_id / strategy_id are
// injected or supplied per-tenant), enabled:false so the row is created dormant. NOTE: a watchlist
// row alone does NOT arm the strategy — it also needs the Discord sidecar WATCHLIST_MIRROR_ADDITIONAL_
// TARGETS mapping + a real-time stock feed, both out-of-band. The form surfaces that advisory.
const watchlistConfig = (brokerTarget: string) =>
  JSON.stringify(
    {
      schema_version: 1,
      broker_target: brokerTarget,
      // Schema-required (strategy-config.json `required` lists author_whitelist), but UNUSED by the
      // watchlist-trigger path — it gates the Discord-author copytrade flow. A non-author placeholder
      // satisfies the shared schema without whitelisting anyone, matching
      // tenants/dev/strategies/watchlist-trigger-v1.yaml. Without it the create 400s on validation.
      author_whitelist: ["watchlist-trigger-unused"],
      capital_source: "account_cash",
      capital_weight: 0.05,
      min_contracts: 1,
      max_contracts: 5,
      notional_cap_pct_of_capital_base: 0.1,
      max_positions: 3,
      entry_mode: "BREAKOUT",
      watchlist_expiry_rule: "NEAREST_WEEKLY",
      gap_tolerance_pct: 0.005,
      equity_emit_delta_pct: 0.0005,
      sl_pct: 0.3,
      tp_ratio: 2.0,
      tp_partial_fraction: 0.5,
      trail_giveback_pct: 0.3,
      no_progress_time_stop_secs: 1500,
      force_close_eod_et: "15:30",
      no_entry_within_close_minutes: 30,
      eod_force_flatten: true,
      exit_floor_abs: 0.05,
      exit_floor_pct: 0.5,
      expiry_day_floor: 0.01,
      max_signal_age_bto_secs: 30,
      max_signal_age_stc_secs: 60,
      enabled: false,
    },
    null,
    2,
  );

// The strategy catalog: the assignable strategy ids + their per-mode config templates. The onboard
// form offers, per tenant, the catalog strategies the tenant does NOT already have, and loads the
// matching template on selection. Two static strategies today (Fork B of the plan — a hardcoded
// catalog beats a BFF endpoint for a closed set); add a row here when a third strategy type lands.
const STRATEGY_TEMPLATES: Record<string, { paper: string; live: string }> = {
  "copytrade-v1": { paper: copytradeConfig("alpaca-paper"), live: copytradeConfig("alpaca-live") },
  "watchlist-trigger-v1": {
    paper: watchlistConfig("alpaca-paper"),
    live: watchlistConfig("alpaca-live"),
  },
};
const CATALOG_STRATEGY_IDS = Object.keys(STRATEGY_TEMPLATES);

const DEFAULT_CONFIG = STRATEGY_TEMPLATES["copytrade-v1"].paper;
const LIVE_CONFIG = STRATEGY_TEMPLATES["copytrade-v1"].live;

// Group the flat (tenant, strategy) admin listing into per-tenant entries: the strategies the tenant
// already has (to subtract from the catalog) + its live/paper mode (to default the Mode toggle for a
// live tenant). Returns null when the admin read is dark/errors so the form degrades to free-text.
async function loadExistingTenants(): Promise<ExistingTenant[] | null> {
  try {
    const { items } = await getAdminTenants();
    const byTenant = new Map<string, ExistingTenant>();
    for (const it of items) {
      const g: ExistingTenant = byTenant.get(it.tenant_id) ?? {
        tenantId: it.tenant_id,
        strategies: [],
        mode: it.mode,
        hasBrokerAccount: false,
      };
      if (!g.strategies.includes(it.strategy_id)) {
        g.strategies.push(it.strategy_id);
      }
      // A tenant is "live" if ANY of its strategies is live (so a new strategy defaults to live too).
      if (it.mode === "live") {
        g.mode = "live";
      }
      // Has a verified broker account when account_masked carries a real last-4 suffix. The BFF masks
      // a MISSING credential to bare bullets ("••••") and a present one to "••••XXXX", so any
      // non-bullet char means a broker_credentials row exists for the tenant.
      if (/[^•]/.test(it.account_masked ?? "")) {
        g.hasBrokerAccount = true;
      }
      byTenant.set(it.tenant_id, g);
    }
    return [...byTenant.values()];
  } catch {
    // AdminReadDisabledError (BFF dark) or any transient failure → degrade to free-text identity.
    return null;
  }
}

export default async function OnboardPage() {
  const session = await auth();
  const existingTenants = await loadExistingTenants();

  // Server action: create the tenant. Re-verifies operator, parses the config, force-binds the
  // tenant/strategy ids onto it (so the writer's identity check passes), forwards to the api-gateway.
  async function createTenantAction(formData: FormData): Promise<OnboardActionResult> {
    "use server";
    const s = await auth();
    if (!s?.isOperator) {
      return { ok: false, status: 0 };
    }
    const tenant = String(formData.get("tenant_id") ?? "").trim();
    const strategy = String(formData.get("strategy_id") ?? "").trim();
    if (!ID_RE.test(tenant) || !ID_RE.test(strategy)) {
      return { ok: false, status: 400 };
    }
    let config: Record<string, unknown>;
    try {
      const parsed = JSON.parse(String(formData.get("config") ?? "")) as unknown;
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        return { ok: false, status: 400 };
      }
      config = parsed as Record<string, unknown>;
    } catch {
      // Malformed JSON is a client error — surface 400 without forwarding.
      return { ok: false, status: 400 };
    }
    // Force identity to match the path; default schema_version if the operator dropped it.
    config.tenant_id = tenant;
    config.strategy_id = strategy;
    config.schema_version ??= 1;
    // Per-tenant Discord alert webhook — set from the dedicated form field (overrides anything in the
    // textarea, same discipline as the injected ids). Blank => leave the key absent so the backend
    // falls back to the global/default alert channel. Light guard: a non-blank value must be https://
    // (mirrors the ID_RE/EMAIL_RE 400s above); anything else is a client error.
    const alertWebhookUrl = String(formData.get("alert_webhook_url") ?? "").trim();
    if (alertWebhookUrl) {
      if (!alertWebhookUrl.startsWith("https://")) {
        return { ok: false, status: 400 };
      }
      config.alert_webhook_url = alertWebhookUrl;
    }
    const r = await createTenant(tenant, strategy, config);
    return { ok: r.ok, status: r.status, createdVersion: r.createdVersion };
  }

  // Server action: paste + verify broker keys for the tenant. The secret rides FormData into the
  // server action and onward to the api-gateway; it is never returned or logged.
  async function addCredentialAction(formData: FormData): Promise<OnboardActionResult> {
    "use server";
    const s = await auth();
    if (!s?.isOperator) {
      return { ok: false, status: 0 };
    }
    const tenant = String(formData.get("tenant_id") ?? "").trim();
    if (!ID_RE.test(tenant)) {
      return { ok: false, status: 400 };
    }
    const r = await postOperatorBrokerCredential(tenant, {
      provider: String(formData.get("provider") ?? "alpaca"),
      api_key_id: String(formData.get("api_key_id") ?? ""),
      api_secret_key: String(formData.get("api_secret_key") ?? ""),
      base_url: String(formData.get("base_url") ?? ""),
      ws_url: String(formData.get("ws_url") ?? ""),
      declared_account_id: String(formData.get("declared_account_id") ?? ""),
      expected_version: 0, // onboarding = first write
    });
    return { ok: r.ok, status: r.status, brokerAccountId: r.brokerAccountId };
  }

  // Server action: arm the tenant's strategy (enabled=true) via the A1 operator enable route. No
  // secret and no config — only the (tenant, strategy) ids; the route re-checks the verified account.
  async function enableStrategyAction(formData: FormData): Promise<OnboardActionResult> {
    "use server";
    const s = await auth();
    if (!s?.isOperator) {
      return { ok: false, status: 0 };
    }
    const tenant = String(formData.get("tenant_id") ?? "").trim();
    const strategy = String(formData.get("strategy_id") ?? "").trim();
    if (!ID_RE.test(tenant) || !ID_RE.test(strategy)) {
      return { ok: false, status: 400 };
    }
    const r = await enableStrategy(tenant, strategy);
    return { ok: r.ok, status: r.status, newVersion: r.newVersion };
  }

  // Server action: promote a just-armed LIVE tenant to real trading via the Phase F activation route
  // (POST /admin/tenants/{tenant}/strategies/{strategy}/activate-live). No secret and no config — only
  // the (tenant, strategy) ids; the route re-runs the live gates (broker_target=-live, loss gates,
  // capital_source=account_cash, kill switch armable, fresh account probe) server-side. Only surfaced
  // for the LIVE onboarding path and behind the dark gates.
  async function activateLiveAction(formData: FormData): Promise<OnboardActionResult> {
    "use server";
    const s = await auth();
    if (!s?.isOperator) {
      return { ok: false, status: 0 };
    }
    const tenant = String(formData.get("tenant_id") ?? "").trim();
    const strategy = String(formData.get("strategy_id") ?? "").trim();
    if (!ID_RE.test(tenant) || !ID_RE.test(strategy)) {
      return { ok: false, status: 400 };
    }
    const r = await postActivation("activate", tenant, strategy);
    return { ok: r.ok, status: r.status };
  }

  // Server action: create a login invite for the tenant by email (optional, independent of steps
  // 1-3). Re-verifies operator, validates the tenant id + email, delegates to the BFF-routed
  // createTenantInvite. No secret — just an email + the tenant id + the verified operator id.
  async function inviteUserAction(formData: FormData): Promise<OnboardActionResult> {
    "use server";
    const s = await auth();
    if (!s?.isOperator) {
      return { ok: false, status: 0 };
    }
    const tenant = String(formData.get("tenant_id") ?? "").trim();
    const email = String(formData.get("email") ?? "").trim();
    if (!ID_RE.test(tenant) || !EMAIL_RE.test(email)) {
      return { ok: false, status: 400 };
    }
    const r = await createTenantInvite(tenant, email);
    return { ok: r.ok, status: r.status, expiresAt: r.expiresAt };
  }

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-3xl px-4 py-6">
        <div className="mb-4 flex items-center justify-between">
          <h1 className="text-xl font-semibold text-slate-100">Operator · Onboard tenant</h1>
          <Link href="/admin/tenants" className="text-sm text-slate-400 hover:text-white">
            ← Tenants
          </Link>
        </div>
        <p className="mb-6 text-sm text-slate-400">
          Data-only onboarding: create a tenant&apos;s first strategy config, paste and verify its
          broker keys, then enable the strategy (unlocks only after the keys verify). All steps are
          operator-scoped and dark-gated. Pick paper or live below — the live path additionally
          activates real-money trading (a deliberate, separately gated step).
        </p>

        <OnboardForm
          createEnabled={CREATE_ENABLED}
          credentialEnabled={CREDENTIAL_ENABLED}
          enableEnabled={ENABLE_ENABLED}
          inviteEnabled={INVITE_ENABLED}
          activateEnabled={ACTIVATION_ENABLED}
          defaultConfig={DEFAULT_CONFIG}
          defaultBaseUrl={DEFAULT_BASE_URL}
          defaultWsUrl={DEFAULT_WS_URL}
          liveConfig={LIVE_CONFIG}
          liveBaseUrl={LIVE_BASE_URL}
          liveWsUrl={LIVE_WS_URL}
          existingTenants={existingTenants}
          strategyTemplates={STRATEGY_TEMPLATES}
          catalogStrategyIds={CATALOG_STRATEGY_IDS}
          createAction={createTenantAction}
          addCredentialAction={addCredentialAction}
          enableAction={enableStrategyAction}
          activateAction={activateLiveAction}
          inviteAction={inviteUserAction}
        />
      </main>
    </>
  );
}
