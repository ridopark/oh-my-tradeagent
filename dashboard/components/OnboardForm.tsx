"use client";

import { useEffect, useState, useTransition } from "react";
import { ConfigFieldReference } from "@/components/ConfigFieldReference";
import { CloudflareGateNote } from "@/components/CloudflareGateNote";

// Coarse result both onboarding server actions return. No secret, no detail — just whether the call
// succeeded, the raw HTTP status, and (for the credential save) the NON-secret authenticated account
// number read-back so the operator can confirm the keys landed on the intended Alpaca account.
export interface OnboardActionResult {
  ok: boolean;
  status: number;
  createdVersion?: number;
  brokerAccountId?: string;
  newVersion?: number;
  // Set only on a successful invite (step 4) — the invite's expiry, shown to the operator.
  expiresAt?: string;
}

// An existing tenant for the identity dropdowns: the strategies it already has (subtracted from the
// catalog to offer only UNUSED ones) + its live/paper mode (to default the Mode toggle).
export interface ExistingTenant {
  tenantId: string;
  strategies: string[];
  mode: "live" | "paper";
}

type Action = (formData: FormData) => Promise<OnboardActionResult>;

const inputCls =
  "w-full rounded border border-slate-700 bg-slate-900 px-2 py-1 text-sm text-slate-100 placeholder:text-slate-600 focus:border-slate-500 focus:outline-none disabled:opacity-50";
const labelCls = "mb-1 block text-xs font-medium text-slate-400";

// Map a coarse {ok,status} to an operator-facing banner. Distinct per step where the status carries
// meaning (409 already-exists on create; 422 credential-rejected on the keys probe).
function createMsg(r: OnboardActionResult): { tone: "ok" | "err"; msg: string } {
  if (r.ok) {
    return {
      tone: "ok",
      msg: `Tenant created (version ${r.createdVersion ?? 1}). Now add its broker keys below.`,
    };
  }
  switch (r.status) {
    case 409:
      return { tone: "err", msg: "Already exists — this tenant/strategy is already created." };
    case 400:
      return { tone: "err", msg: "Rejected — config is invalid or its ids don't match." };
    case 404:
      return { tone: "err", msg: "Tenant-create not enabled." };
    default:
      return { tone: "err", msg: "Could not create. Try again." };
  }
}

function credentialMsg(r: OnboardActionResult): { tone: "ok" | "err"; msg: string } {
  if (r.ok) {
    return r.brokerAccountId
      ? { tone: "ok", msg: `Keys verified — authenticated account ${r.brokerAccountId}.` }
      : {
          tone: "ok",
          msg: "Keys saved. (Provide the expected account number to verify the authenticated account.)",
        };
  }
  switch (r.status) {
    case 422:
      return { tone: "err", msg: "Rejected — the broker did not accept these keys." };
    case 409:
      return { tone: "err", msg: "This tenant already has broker keys saved." };
    case 403:
      return { tone: "err", msg: "Tenant mismatch — keys must be for the tenant above." };
    case 429:
      return { tone: "err", msg: "Too many attempts — wait and retry." };
    case 404:
      return { tone: "err", msg: "Credential write not enabled." };
    default:
      return { tone: "err", msg: "Could not save keys. Try again." };
  }
}

function enableMsg(r: OnboardActionResult): { tone: "ok" | "err"; msg: string } {
  if (r.ok) {
    return {
      tone: "ok",
      msg: `Strategy armed (version ${r.newVersion ?? "updated"}). The tenant is now enabled.`,
    };
  }
  switch (r.status) {
    case 422:
      return {
        tone: "err",
        msg: "Rejected — no verified broker account (or an unsupported live target). Verify the keys above first.",
      };
    case 409:
      return { tone: "err", msg: "Version conflict — reload and retry." };
    case 404:
      return { tone: "err", msg: "Strategy enable not enabled, or no such tenant/strategy." };
    case 403:
      return { tone: "err", msg: "Not allowed — operator is not allowlisted for this action." };
    default:
      return { tone: "err", msg: "Could not arm the strategy. Try again." };
  }
}

function activateMsg(r: OnboardActionResult): { tone: "ok" | "err"; msg: string } {
  if (r.ok) {
    return {
      tone: "ok",
      msg: "Activated — the live strategy is armed for real trading (a 30-day live promotion was issued).",
    };
  }
  switch (r.status) {
    case 422:
      return {
        tone: "err",
        msg: "Rejected — a live gate failed (needs broker_target=alpaca-live, daily_loss_threshold>0 + notional cap, capital_source=account_cash, an armable kill switch, and a positive-cash account).",
      };
    case 404:
      return { tone: "err", msg: "Live activation not enabled, or no such tenant/strategy." };
    case 403:
      return { tone: "err", msg: "Not allowed — operator is not allowlisted for this action." };
    case 503:
      return { tone: "err", msg: "Backend fault — activation could not complete. Try again." };
    default:
      return { tone: "err", msg: "Could not activate. Try again." };
  }
}

function inviteMsg(r: OnboardActionResult): { tone: "ok" | "err"; msg: string } {
  if (r.ok) {
    const expiry = r.expiresAt
      ? `the invite expires ${r.expiresAt}`
      : "the invite is time-boxed";
    return {
      tone: "ok",
      msg: `Invited — they can sign in with this email (${expiry}).`,
    };
  }
  switch (r.status) {
    case 422:
      return { tone: "err", msg: "Unknown tenant — create the tenant (step 1) first." };
    case 400:
      return { tone: "err", msg: "Rejected — that doesn't look like a valid email." };
    case 403:
      return { tone: "err", msg: "Not allowed — operator is not allowlisted for this action." };
    case 404:
      return { tone: "err", msg: "User invites not enabled." };
    default:
      return { tone: "err", msg: "Could not create the invite. Try again." };
  }
}

function Banner({ r }: { r: { tone: "ok" | "err"; msg: string } }) {
  return (
    <div
      className={
        r.tone === "ok"
          ? "mt-3 rounded border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-300"
          : "mt-3 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300"
      }
    >
      {r.msg}
    </div>
  );
}

// Operator onboarding form. Steps share one tenant/strategy pair at the top:
//   1) Create tenant (INSERT first strategy_config row)  → createAction
//   2) Add broker keys (paste + verify, account read-back) → addCredentialAction
//   3) Enable strategy (arm the tenant)                   → enableAction
//   3b) Activate live (LIVE mode only, real money)        → activateAction
// Each step is independently dark-gated; a disabled step renders read-only with an explanatory note.
// Step 3 additionally stays inert until step 2 returns a verified brokerAccountId — arming a tenant
// with no verified account would be rejected 422 by the A1 route, so the UI gates on it up-front.
//
// The paper/live selector is always shown, so any operator can pick paper vs live. In LIVE mode the
// config/base/ws templates switch to the alpaca-live variants, the account number is REQUIRED (it
// becomes the pinned expected_account_id), and step 3b (activate-live) appears. Real money is still
// gated at the backend and behind step 3b's activateEnabled flag — the toggle only picks the target.
export function OnboardForm({
  createEnabled,
  credentialEnabled,
  enableEnabled,
  inviteEnabled,
  activateEnabled,
  defaultConfig,
  defaultBaseUrl,
  defaultWsUrl,
  liveConfig,
  liveBaseUrl,
  liveWsUrl,
  existingTenants,
  strategyTemplates,
  catalogStrategyIds,
  createAction,
  addCredentialAction,
  enableAction,
  activateAction,
  inviteAction,
}: {
  createEnabled: boolean;
  credentialEnabled: boolean;
  enableEnabled: boolean;
  inviteEnabled: boolean;
  activateEnabled: boolean;
  defaultConfig: string;
  defaultBaseUrl: string;
  defaultWsUrl: string;
  liveConfig: string;
  liveBaseUrl: string;
  liveWsUrl: string;
  // Existing (tenant → its strategies + mode), for the identity dropdowns. null ⇒ the admin read is
  // dark/failed ⇒ the form degrades to the prior free-text identity inputs.
  existingTenants: ExistingTenant[] | null;
  // Per-strategy config templates keyed by strategy id, then paper/live. Drives the textarea swap.
  strategyTemplates: Record<string, { paper: string; live: string }>;
  // The assignable strategy catalog (ids). "Unused for tenant" = this minus the tenant's strategies.
  catalogStrategyIds: string[];
  createAction: Action;
  addCredentialAction: Action;
  enableAction: Action;
  activateAction: Action;
  inviteAction: Action;
}) {
  // Shared identity for all steps. Every step uses the SAME tenant/strategy the create step used.
  const [tenant, setTenant] = useState("");
  const [strategy, setStrategy] = useState("copytrade-v1");

  // Onboarding mode. "paper" (default) is the prior flow; "live" retargets the alpaca-live templates
  // and reveals the activate step. The selector is always shown; `live` drives the config/base/ws
  // templates and reveals the activate step.
  const [mode, setMode] = useState<"paper" | "live">("paper");
  const live = mode === "live";
  // Non-secret account number (the pinned expected_account_id). Tracked in state ONLY to gate the
  // save button in live mode where it is required — it is NOT key material (MF-7 is about the secret).
  const [declaredAccount, setDeclaredAccount] = useState("");
  // Per-tenant Discord alert webhook (optional). Starts EMPTY and is never pre-filled — a blank value
  // means the tenant falls back to the global/default alert channel. Injected into the config on create.
  const [alertWebhook, setAlertWebhook] = useState("");

  // Step 4 (invite) is independent — it has its own email input, not the shared tenant/strategy pair.
  const [inviteEmail, setInviteEmail] = useState("");

  const [createResult, setCreateResult] = useState<OnboardActionResult | null>(null);
  const [credResult, setCredResult] = useState<OnboardActionResult | null>(null);
  const [enableResult, setEnableResult] = useState<OnboardActionResult | null>(null);
  const [activateResult, setActivateResult] = useState<OnboardActionResult | null>(null);
  const [inviteResult, setInviteResult] = useState<OnboardActionResult | null>(null);
  const [creating, startCreate] = useTransition();
  const [saving, startSave] = useTransition();
  const [enabling, startEnable] = useTransition();
  const [activating, startActivate] = useTransition();
  const [inviting, startInvite] = useTransition();

  // Any change of target identity or mode invalidates the in-session step pre-checks — never let a
  // prior tenant/strategy/mode's success gate a real-money action (Activate live) for a different
  // target. Resetting the step results re-locks the derived accountVerified/strategyArmed gates until
  // the NEW identity actually completes create → creds → enable in-session. declaredAccount and
  // alertWebhook are tenant-specific, so they clear too. (Invite step 4 is independent and keeps its
  // own email state.)
  useEffect(() => {
    setCreateResult(null);
    setCredResult(null);
    setEnableResult(null);
    setActivateResult(null);
    setDeclaredAccount("");
    setAlertWebhook("");
  }, [tenant, strategy, mode]);

  // Identity dropdowns (Phase 1). When the admin listing is available, the tenant is a pick-or-type
  // combo-box and the strategy is a <select> of the tenant's UNUSED catalog strategies. When null
  // (admin read dark / errored) both degrade to the prior free-text inputs.
  const useDropdowns = existingTenants !== null;
  const selectedTenant = existingTenants?.find((t) => t.tenantId === tenant) ?? null;
  const usedStrategies = selectedTenant?.strategies ?? [];
  const availableStrategies = catalogStrategyIds.filter((s) => !usedStrategies.includes(s));

  // When the tenant changes to a KNOWN existing tenant, default the Mode toggle to its mode (so a new
  // strategy on a live tenant is not accidentally paper-targeted) and, if the current strategy is one
  // that tenant already has, switch to the first still-available one. Keyed on `tenant` only so it
  // never fights the strategy <select>; inert for a new/typed tenant.
  useEffect(() => {
    if (!useDropdowns) return;
    if (selectedTenant) setMode(selectedTenant.mode);
    // Keep the strategy state in sync with what the <select> can actually offer for this tenant —
    // covers both switching to a tenant that already has the current strategy AND leaving a
    // fully-used tenant (strategy left "") for a new one (reset to the first catalog entry).
    if (!availableStrategies.includes(strategy)) {
      setStrategy(availableStrategies[0] ?? "");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  function submitCreate(formData: FormData) {
    formData.set("tenant_id", tenant);
    formData.set("strategy_id", strategy);
    formData.set("alert_webhook_url", alertWebhook);
    startCreate(async () => setCreateResult(await createAction(formData)));
  }

  function submitCredential(formData: FormData) {
    formData.set("tenant_id", tenant);
    startSave(async () => setCredResult(await addCredentialAction(formData)));
  }

  function submitEnable(formData: FormData) {
    formData.set("tenant_id", tenant);
    formData.set("strategy_id", strategy);
    startEnable(async () => setEnableResult(await enableAction(formData)));
  }

  function submitActivate(formData: FormData) {
    formData.set("tenant_id", tenant);
    formData.set("strategy_id", strategy);
    startActivate(async () => setActivateResult(await activateAction(formData)));
  }

  function submitInvite(formData: FormData) {
    formData.set("tenant_id", tenant);
    formData.set("email", inviteEmail);
    startInvite(async () => setInviteResult(await inviteAction(formData)));
  }

  // Step 1 (create) needs both ids; step 2 (keys) binds only the tenant.
  const idsMissing = !tenant.trim() || !strategy.trim();
  const tenantMissing = !tenant.trim();
  // Step 2 (keys) in LIVE mode additionally REQUIRES the account number (it becomes the pinned
  // expected_account_id and drives the read-back check). Paper mode leaves it optional as before.
  const credMissing = tenantMissing || (live && !declaredAccount.trim());
  // Step 3 (enable) unlocks ONLY once step 2's in-session result carries a non-blank verified
  // account. This mirrors the A1 backend guard (no verified account → 422) as a pre-check.
  const accountVerified = Boolean(credResult?.ok && credResult.brokerAccountId?.trim());
  // Step 3b (activate-live, LIVE mode only) unlocks once step 3 (enable) has succeeded in-session.
  const strategyArmed = Boolean(enableResult?.ok);
  // Step 4 (invite) needs the tenant id above + a non-blank email.
  const inviteMissing = tenantMissing || !inviteEmail.trim();

  return (
    <div className="space-y-6">
      {/* Shared identity */}
      <section className="rounded-lg border border-slate-800 bg-slate-900/40 p-4">
        <h2 className="mb-3 text-sm font-semibold text-slate-200">Tenant</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <label className={labelCls} htmlFor="ob-tenant">
              Tenant id
            </label>
            {/* Combo-box: pick an existing tenant OR type a new one (a typed id not in the list is a
                new tenant — the prior create-new flow). Free-text when the admin list is unavailable. */}
            <input
              id="ob-tenant"
              list={useDropdowns ? "ob-tenant-list" : undefined}
              className={inputCls}
              value={tenant}
              onChange={(e) => setTenant(e.target.value)}
              placeholder="acme"
              autoComplete="off"
            />
            {useDropdowns && (
              <datalist id="ob-tenant-list">
                {existingTenants!.map((t) => (
                  <option key={t.tenantId} value={t.tenantId} />
                ))}
              </datalist>
            )}
          </div>
          <div>
            <label className={labelCls} htmlFor="ob-strategy">
              Strategy id
            </label>
            {/* Select of the strategies this tenant does NOT already have (a new tenant → whole
                catalog). Free-text when the admin list is unavailable. */}
            {useDropdowns ? (
              <select
                id="ob-strategy"
                className={inputCls}
                value={strategy}
                onChange={(e) => setStrategy(e.target.value)}
              >
                {availableStrategies.length === 0 && <option value="">— none available —</option>}
                {availableStrategies.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            ) : (
              <input
                id="ob-strategy"
                className={inputCls}
                value={strategy}
                onChange={(e) => setStrategy(e.target.value)}
                placeholder="copytrade-v1"
                autoComplete="off"
              />
            )}
          </div>
        </div>
        <p className="mt-2 text-xs text-slate-500">
          {useDropdowns
            ? "Pick an existing tenant or type a new id; the strategy list shows only the ones the tenant doesn't already have."
            : "Allowed characters: letters, digits, _ and -."}{" "}
          These are injected into the config and bind the keys below — both steps use this pair.
        </p>
        {selectedTenant && availableStrategies.length === 0 && (
          <p className="mt-1 text-xs text-amber-400">
            {tenant} already has every catalog strategy — nothing to add.
          </p>
        )}

        {/* Paper/live selector — always shown. Switching to live retargets the templates below and
            reveals the activate-live step (real money). Real money stays gated at the backend and
            behind step 3b's activate flag; this toggle only picks the target. */}
        <div className="mt-4">
          <label className={labelCls}>Mode</label>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setMode("paper")}
              className={`rounded border px-3 py-1.5 text-sm font-medium transition-colors ${
                mode === "paper"
                  ? "border-slate-400 bg-slate-700 text-slate-100"
                  : "border-slate-700 bg-slate-900 text-slate-400 hover:bg-slate-800"
              }`}
            >
              Paper
            </button>
            <button
              type="button"
              onClick={() => setMode("live")}
              className={`rounded border px-3 py-1.5 text-sm font-medium transition-colors ${
                mode === "live"
                  ? "border-amber-500/60 bg-amber-600/20 text-amber-300"
                  : "border-slate-700 bg-slate-900 text-slate-400 hover:bg-slate-800"
              }`}
            >
              ● Live (real money)
            </button>
          </div>
          {live && (
            <p className="mt-2 text-xs text-amber-300/80">
              Live mode: the config, base and WebSocket URLs below target the real Alpaca account, the
              account number is required, and a final activate-live step arms real trading.
            </p>
          )}
          <p className="mt-1 text-xs text-slate-500">
            Switching mode resets the config template, base/WebSocket URLs and account number to that
            mode&apos;s defaults — any edits below are discarded.
          </p>
        </div>
      </section>

      {/* Step 1 — Create tenant */}
      <section className="rounded-lg border border-slate-800 bg-slate-900/40 p-4">
        <h2 className="mb-1 text-sm font-semibold text-slate-200">1 · Create tenant</h2>
        <p className="mb-3 text-xs text-slate-500">
          Inserts the first strategy_config row at version 1. <code>tenant_id</code> and{" "}
          <code>strategy_id</code> are set automatically from above. Edit the rest for your strategy.
          {live
            ? " Live template pre-fills the required loss gates (daily_loss_threshold, notional cap) and capital_source=account_cash so activation passes."
            : " Paper target — live arming is a separate mode."}
        </p>
        <form
          action={submitCreate}
          onSubmit={() => setCreateResult(null)}
        >
          <label className={labelCls} htmlFor="ob-config">
            Strategy config (JSON)
          </label>
          {/* Remount (via key) whenever strategy or mode changes so defaultValue re-applies the right
              template. Falls back to the copytrade paper/live config for a strategy without a template
              (e.g. a free-typed id in degraded mode). */}
          <textarea
            key={`${strategy}:${mode}`}
            id="ob-config"
            name="config"
            className={`${inputCls} h-56 font-mono`}
            defaultValue={strategyTemplates[strategy]?.[mode] ?? (live ? liveConfig : defaultConfig)}
            disabled={!createEnabled}
            spellCheck={false}
          />
          {strategy === "watchlist-trigger-v1" && (
            <p className="mt-2 rounded border border-amber-700/60 bg-amber-950/40 px-3 py-2 text-xs text-amber-300">
              Creates a <strong>disabled</strong> watchlist row. Two things outside this form gate it
              arming: (1) <strong>route the watchlist to this tenant</strong> — add{" "}
              <code>{tenant || "<tenant>"}:watchlist-trigger-v1</code> to the sidecar&apos;s{" "}
              <code>WATCHLIST_MIRROR_ADDITIONAL_TARGETS</code> + restart it (the only per-tenant step —
              a candidate to make DB-driven); (2) the <strong>real-time stock feed</strong> must be on
              — a one-time cluster toggle on market-data, not per-tenant. Then enable the strategy.
            </p>
          )}
          <div className="mt-3">
            <label className={labelCls} htmlFor="ob-alert-webhook">
              Alert webhook URL (Discord)
            </label>
            <input
              id="ob-alert-webhook"
              className={inputCls}
              type="url"
              value={alertWebhook}
              onChange={(e) => setAlertWebhook(e.target.value)}
              placeholder="https://discord.com/api/webhooks/… (optional)"
              disabled={!createEnabled}
              autoComplete="off"
            />
            <p className="mt-1 text-xs text-slate-500">
              Where THIS tenant&apos;s trade/order-execution alerts, broker rejections, and the daily
              digest post. Leave blank to fall back to the global/default alert channel. Optional.
            </p>
          </div>
          <button
            type="submit"
            disabled={!createEnabled || creating || idsMissing}
            className="mt-3 rounded border border-emerald-500/60 bg-emerald-600/20 px-3 py-1.5 text-sm font-medium text-emerald-300 transition-colors hover:bg-emerald-600/30 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {creating ? "Creating…" : "Create tenant"}
          </button>
          {!createEnabled && (
            <p className="mt-2 text-xs text-slate-500">Tenant creation not enabled (read-only).</p>
          )}
        </form>
        {createResult && <Banner r={createMsg(createResult)} />}
        <ConfigFieldReference />
      </section>

      {/* Step 2 — Broker credentials */}
      <section className="rounded-lg border border-slate-800 bg-slate-900/40 p-4">
        <h2 className="mb-1 text-sm font-semibold text-slate-200">2 · Broker keys</h2>
        <p className="mb-3 text-xs text-slate-500">
          Pasted keys go straight to the broker probe — they are never stored in the browser, logged,
          or echoed back. Only the verified account number is returned.
          {live
            ? " Live mode: the account number is required — it pins the expected account the keys must authenticate as."
            : ""}
        </p>
        <form action={submitCredential} onSubmit={() => setCredResult(null)}>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label className={labelCls} htmlFor="ob-provider">
                Provider
              </label>
              <input
                id="ob-provider"
                name="provider"
                className={inputCls}
                defaultValue="alpaca"
                disabled={!credentialEnabled}
                autoComplete="off"
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="ob-declared">
                Expected account number{live ? " (required)" : ""}
              </label>
              <input
                id="ob-declared"
                name="declared_account_id"
                className={inputCls}
                placeholder={
                  live
                    ? "e.g. 847309116 (required — verified on save)"
                    : "e.g. PA3FKGPFYPLH (verified on save)"
                }
                value={declaredAccount}
                onChange={(e) => setDeclaredAccount(e.target.value)}
                disabled={!credentialEnabled}
                autoComplete="off"
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="ob-key">
                API key id
              </label>
              <input
                id="ob-key"
                name="api_key_id"
                className={inputCls}
                disabled={!credentialEnabled}
                autoComplete="off"
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="ob-secret">
                API secret
              </label>
              <input
                id="ob-secret"
                name="api_secret_key"
                type="password"
                className={inputCls}
                disabled={!credentialEnabled}
                autoComplete="off"
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="ob-base">
                Base URL
              </label>
              <input
                key={live ? "base-live" : "base-paper"}
                id="ob-base"
                name="base_url"
                className={inputCls}
                defaultValue={live ? liveBaseUrl : defaultBaseUrl}
                disabled={!credentialEnabled}
                autoComplete="off"
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="ob-ws">
                WebSocket URL
              </label>
              <input
                key={live ? "ws-live" : "ws-paper"}
                id="ob-ws"
                name="ws_url"
                className={inputCls}
                defaultValue={live ? liveWsUrl : defaultWsUrl}
                disabled={!credentialEnabled}
                autoComplete="off"
              />
            </div>
          </div>
          <button
            type="submit"
            disabled={!credentialEnabled || saving || credMissing}
            className="mt-3 rounded border border-emerald-500/60 bg-emerald-600/20 px-3 py-1.5 text-sm font-medium text-emerald-300 transition-colors hover:bg-emerald-600/30 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? "Saving…" : "Save & verify keys"}
          </button>
          {!credentialEnabled && (
            <p className="mt-2 text-xs text-slate-500">
              Credential write not enabled (read-only).
            </p>
          )}
          {credentialEnabled && live && !declaredAccount.trim() && (
            <p className="mt-2 text-xs text-slate-500">
              Enter the expected account number (required for live).
            </p>
          )}
        </form>
        {credResult && <Banner r={credentialMsg(credResult)} />}
      </section>

      {/* Step 3 — Enable strategy */}
      <section className="rounded-lg border border-slate-800 bg-slate-900/40 p-4">
        <h2 className="mb-1 text-sm font-semibold text-slate-200">3 · Enable strategy</h2>
        <p className="mb-3 text-xs text-slate-500">
          Arms the tenant (<code>enabled=true</code>) via the operator enable route, which itself
          re-checks that a verified broker account exists. Only unlocks once the keys above verify.
        </p>
        <form action={submitEnable} onSubmit={() => setEnableResult(null)}>
          <button
            type="submit"
            disabled={!enableEnabled || enabling || idsMissing || !accountVerified}
            className="rounded border border-emerald-500/60 bg-emerald-600/20 px-3 py-1.5 text-sm font-medium text-emerald-300 transition-colors hover:bg-emerald-600/30 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {enabling ? "Enabling…" : "Enable strategy"}
          </button>
          {!enableEnabled && (
            <p className="mt-2 text-xs text-slate-500">Strategy enable not enabled (read-only).</p>
          )}
          {enableEnabled && !accountVerified && (
            <p className="mt-2 text-xs text-slate-500">Verify broker keys first (step 2).</p>
          )}
        </form>
        {enableResult && <Banner r={enableMsg(enableResult)} />}
      </section>

      {/* Step 3b — Activate live (LIVE mode only, real money) */}
      {live && (
        <section className="rounded-lg border border-amber-600/40 bg-amber-950/20 p-4">
          <h2 className="mb-1 text-sm font-semibold text-amber-200">
            3b · Activate live <span className="text-amber-400">(real money)</span>
          </h2>
          <p className="mb-3 text-xs text-amber-300/70">
            Promotes the armed strategy to real trading via the live-activation route, which re-runs
            every live gate server-side (live target, loss gates, capital_source=account_cash, an
            armable kill switch, a fresh positive-cash account probe). Only unlocks once the strategy
            is enabled above. The broker-403 canary lift at Alpaca stays a separate, manual step.
          </p>
          <form action={submitActivate} onSubmit={() => setActivateResult(null)}>
            <button
              type="submit"
              disabled={!activateEnabled || activating || idsMissing || !strategyArmed}
              className="rounded border border-amber-500/60 bg-amber-600/20 px-3 py-1.5 text-sm font-medium text-amber-300 transition-colors hover:bg-amber-600/30 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {activating ? "Activating…" : "Activate live"}
            </button>
            {!activateEnabled && (
              <p className="mt-2 text-xs text-slate-500">Live activation not enabled (read-only).</p>
            )}
            {activateEnabled && !strategyArmed && (
              <p className="mt-2 text-xs text-amber-300/60">Enable the strategy first (step 3).</p>
            )}
          </form>
          {activateResult && <Banner r={activateMsg(activateResult)} />}
        </section>
      )}

      {/* Step 4 — Invite user (optional, independent) */}
      <section className="rounded-lg border border-slate-800 bg-slate-900/40 p-4">
        <h2 className="mb-1 text-sm font-semibold text-slate-200">4 · Invite user (email)</h2>
        <p className="mb-3 text-xs text-slate-500">
          Optional. Grants a person login access to the tenant above by email. They sign in with
          Google/Facebook using this email and are bound to the tenant on first sign-in (member only,
          never operator). No email is sent — tell them to sign in. Independent of steps 1-3.
        </p>
        <form action={submitInvite} onSubmit={() => setInviteResult(null)}>
          <label className={labelCls} htmlFor="ob-invite-email">
            User email
          </label>
          <input
            id="ob-invite-email"
            className={inputCls}
            type="email"
            value={inviteEmail}
            onChange={(e) => setInviteEmail(e.target.value)}
            placeholder="person@example.com"
            disabled={!inviteEnabled}
            autoComplete="off"
          />
          <button
            type="submit"
            disabled={!inviteEnabled || inviting || inviteMissing}
            className="mt-3 rounded border border-emerald-500/60 bg-emerald-600/20 px-3 py-1.5 text-sm font-medium text-emerald-300 transition-colors hover:bg-emerald-600/30 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {inviting ? "Inviting…" : "Create invite"}
          </button>
          {!inviteEnabled && (
            <p className="mt-2 text-xs text-slate-500">User invites not enabled (read-only).</p>
          )}
          <CloudflareGateNote />
        </form>
        {inviteResult && <Banner r={inviteMsg(inviteResult)} />}
      </section>
    </div>
  );
}
