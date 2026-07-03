"use client";

import { useState, useTransition } from "react";

// Coarse result both onboarding server actions return. No secret, no detail — just whether the call
// succeeded, the raw HTTP status, and (for the credential save) the NON-secret authenticated account
// number read-back so the operator can confirm the keys landed on the intended Alpaca account.
export interface OnboardActionResult {
  ok: boolean;
  status: number;
  createdVersion?: number;
  brokerAccountId?: string;
  newVersion?: number;
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

// Operator onboarding form. Three steps share one tenant/strategy pair at the top:
//   1) Create tenant (INSERT first strategy_config row)  → createAction
//   2) Add broker keys (paste + verify, account read-back) → addCredentialAction
//   3) Enable strategy (arm the tenant)                   → enableAction
// Each step is independently dark-gated; a disabled step renders read-only with an explanatory note.
// Step 3 additionally stays inert until step 2 returns a verified brokerAccountId — arming a tenant
// with no verified account would be rejected 422 by the A1 route, so the UI gates on it up-front.
export function OnboardForm({
  createEnabled,
  credentialEnabled,
  enableEnabled,
  defaultConfig,
  defaultBaseUrl,
  defaultWsUrl,
  createAction,
  addCredentialAction,
  enableAction,
}: {
  createEnabled: boolean;
  credentialEnabled: boolean;
  enableEnabled: boolean;
  defaultConfig: string;
  defaultBaseUrl: string;
  defaultWsUrl: string;
  createAction: Action;
  addCredentialAction: Action;
  enableAction: Action;
}) {
  // Shared identity for all steps. Every step uses the SAME tenant/strategy the create step used.
  const [tenant, setTenant] = useState("");
  const [strategy, setStrategy] = useState("copytrade-v1");

  const [createResult, setCreateResult] = useState<OnboardActionResult | null>(null);
  const [credResult, setCredResult] = useState<OnboardActionResult | null>(null);
  const [enableResult, setEnableResult] = useState<OnboardActionResult | null>(null);
  const [creating, startCreate] = useTransition();
  const [saving, startSave] = useTransition();
  const [enabling, startEnable] = useTransition();

  function submitCreate(formData: FormData) {
    formData.set("tenant_id", tenant);
    formData.set("strategy_id", strategy);
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

  // Step 1 (create) needs both ids; step 2 (keys) binds only the tenant.
  const idsMissing = !tenant.trim() || !strategy.trim();
  const tenantMissing = !tenant.trim();
  // Step 3 (enable) unlocks ONLY once step 2's in-session result carries a non-blank verified
  // account. This mirrors the A1 backend guard (no verified account → 422) as a pre-check.
  const accountVerified = Boolean(credResult?.ok && credResult.brokerAccountId?.trim());

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
            <input
              id="ob-tenant"
              className={inputCls}
              value={tenant}
              onChange={(e) => setTenant(e.target.value)}
              placeholder="acme"
              autoComplete="off"
            />
          </div>
          <div>
            <label className={labelCls} htmlFor="ob-strategy">
              Strategy id
            </label>
            <input
              id="ob-strategy"
              className={inputCls}
              value={strategy}
              onChange={(e) => setStrategy(e.target.value)}
              placeholder="copytrade-v1"
              autoComplete="off"
            />
          </div>
        </div>
        <p className="mt-2 text-xs text-slate-500">
          Allowed characters: letters, digits, <code>_</code> and <code>-</code>. These are injected
          into the config and bind the keys below — both steps use this pair.
        </p>
      </section>

      {/* Step 1 — Create tenant */}
      <section className="rounded-lg border border-slate-800 bg-slate-900/40 p-4">
        <h2 className="mb-1 text-sm font-semibold text-slate-200">1 · Create tenant</h2>
        <p className="mb-3 text-xs text-slate-500">
          Inserts the first strategy_config row at version 1. <code>tenant_id</code> and{" "}
          <code>strategy_id</code> are set automatically from above. Edit the rest for your strategy
          (paper target this phase — live arming is a separate step).
        </p>
        <form
          action={submitCreate}
          onSubmit={() => setCreateResult(null)}
        >
          <label className={labelCls} htmlFor="ob-config">
            Strategy config (JSON)
          </label>
          <textarea
            id="ob-config"
            name="config"
            className={`${inputCls} h-56 font-mono`}
            defaultValue={defaultConfig}
            disabled={!createEnabled}
            spellCheck={false}
          />
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
      </section>

      {/* Step 2 — Broker credentials */}
      <section className="rounded-lg border border-slate-800 bg-slate-900/40 p-4">
        <h2 className="mb-1 text-sm font-semibold text-slate-200">2 · Broker keys</h2>
        <p className="mb-3 text-xs text-slate-500">
          Pasted keys go straight to the broker probe — they are never stored in the browser, logged,
          or echoed back. Only the verified account number is returned. Paper target this phase.
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
                Expected account number
              </label>
              <input
                id="ob-declared"
                name="declared_account_id"
                className={inputCls}
                placeholder="e.g. PA3FKGPFYPLH (verified on save)"
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
                id="ob-base"
                name="base_url"
                className={inputCls}
                defaultValue={defaultBaseUrl}
                disabled={!credentialEnabled}
                autoComplete="off"
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="ob-ws">
                WebSocket URL
              </label>
              <input
                id="ob-ws"
                name="ws_url"
                className={inputCls}
                defaultValue={defaultWsUrl}
                disabled={!credentialEnabled}
                autoComplete="off"
              />
            </div>
          </div>
          <button
            type="submit"
            disabled={!credentialEnabled || saving || tenantMissing}
            className="mt-3 rounded border border-emerald-500/60 bg-emerald-600/20 px-3 py-1.5 text-sm font-medium text-emerald-300 transition-colors hover:bg-emerald-600/30 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? "Saving…" : "Save & verify keys"}
          </button>
          {!credentialEnabled && (
            <p className="mt-2 text-xs text-slate-500">
              Credential write not enabled (read-only).
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
    </div>
  );
}
