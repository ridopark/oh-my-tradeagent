import { auth } from "@/auth";
import Link from "next/link";
import { Nav } from "@/components/Nav";
import { OnboardForm, type OnboardActionResult } from "@/components/OnboardForm";
import {
  createTenant,
  enableStrategy,
  postOperatorBrokerCredential,
} from "@/lib/adminOnboarding";
import { createTenantInvite } from "@/lib/adminBff";
import { postActivation } from "@/lib/adminActivation";
import { EMAIL_RE, ID_RE } from "@/lib/validation";

export const dynamic = "force-dynamic";

// Dark-by-default UI gates, mirroring the api-gateway route flags. Unset/anything-else => that step
// renders read-only. Even with these "true" each api-gateway route is itself dark (404s) until its
// own flag is on, so the action degrades gracefully. Paper-only this phase (exec refuses -live).
const CREATE_ENABLED = process.env.OPERATOR_TENANT_CREATE_ENABLED === "true";
const CREDENTIAL_ENABLED = process.env.OPERATOR_CREDENTIAL_WRITE_ENABLED === "true";
// Mirrors the A1 backend `operator.strategy-enable.enabled` flag; the enable route itself 404s until
// its own flag is on, so step 3 degrades gracefully even if this is set ahead of the backend.
const ENABLE_ENABLED = process.env.OPERATOR_STRATEGY_ENABLE_ENABLED === "true";
// Mirrors the BFF `operator.tenant-invite.enabled` flag; the create-invite BFF route itself 404s until
// its own flag (plus dashboard.writer.enabled) is on, so step 4 degrades gracefully even if this is set
// ahead of the backend.
const INVITE_ENABLED = process.env.OPERATOR_TENANT_INVITE_ENABLED === "true";
// Dark-by-default UI gate for the LIVE onboarding path (broker_target=alpaca-live + activate-live
// step). Unset/anything-else => the form is paper-only, byte-for-byte the prior flow. Even with this
// "true" the underlying gateway routes are themselves dark: enable needs operator.strategy-enable.enabled
// and the Phase-1 per-target arm-guard; activate-live needs operator.activation.enabled — each 404s
// until on, so the live steps degrade gracefully. This arms REAL money — keep it off until cutover.
const LIVE_ONBOARD_ENABLED = process.env.OPERATOR_LIVE_ONBOARD_ENABLED === "true";

// Paper broker endpoints — the default targets. Live arming rides the separate LIVE mode below.
const DEFAULT_BASE_URL = "https://paper-api.alpaca.markets";
const DEFAULT_WS_URL = "wss://paper-api.alpaca.markets/stream";
// Live (real-money) broker endpoints — used only when the operator picks LIVE mode (dark-gated).
const LIVE_BASE_URL = "https://api.alpaca.markets";
const LIVE_WS_URL = "wss://api.alpaca.markets/stream";

// Minimal paper StrategyConfig template. tenant_id/strategy_id are injected server-side from the
// form (so they always match the create path); enabled:false creates the tenant dormant.
const DEFAULT_CONFIG = JSON.stringify(
  {
    schema_version: 1,
    broker_target: "alpaca-paper",
    author_whitelist: [],
    max_signal_age_bto_secs: 300,
    max_signal_age_stc_secs: 300,
    max_positions: 5,
    capital_weight: 1.0,
    min_contracts: 1,
    max_contracts: 10,
    enabled: false,
  },
  null,
  2,
);

// LIVE StrategyConfig template. Carries the three live-required invariants the activate-live gate
// (LiveActivationWorkflow / StrategyConfigInvariants.validateLiveRequiredGates) enforces, so the
// later activation passes: daily_loss_threshold>0, notional_cap_pct_of_capital_base set, and
// capital_source=account_cash (sizes a small real account from its own cash, never the static $100k
// global). Defaults are conservative and operator-editable before create; enabled:false stays dormant.
const LIVE_CONFIG = JSON.stringify(
  {
    schema_version: 1,
    broker_target: "alpaca-live",
    author_whitelist: [],
    max_signal_age_bto_secs: 300,
    max_signal_age_stc_secs: 300,
    max_positions: 5,
    capital_weight: 1.0,
    min_contracts: 1,
    max_contracts: 10,
    capital_source: "account_cash",
    daily_loss_threshold: 250,
    notional_cap_pct_of_capital_base: 0.8,
    enabled: false,
  },
  null,
  2,
);

export default async function OnboardPage() {
  const session = await auth();

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
          operator-scoped and dark-gated.
          {LIVE_ONBOARD_ENABLED
            ? " Pick paper or live below — the live path additionally activates real-money trading (a deliberate, gated step)."
            : " This phase targets paper accounts only — arming real money is a separate, gated step."}
        </p>

        <OnboardForm
          createEnabled={CREATE_ENABLED}
          credentialEnabled={CREDENTIAL_ENABLED}
          enableEnabled={ENABLE_ENABLED}
          inviteEnabled={INVITE_ENABLED}
          liveOnboardEnabled={LIVE_ONBOARD_ENABLED}
          defaultConfig={DEFAULT_CONFIG}
          defaultBaseUrl={DEFAULT_BASE_URL}
          defaultWsUrl={DEFAULT_WS_URL}
          liveConfig={LIVE_CONFIG}
          liveBaseUrl={LIVE_BASE_URL}
          liveWsUrl={LIVE_WS_URL}
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
