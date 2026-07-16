import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { SubmitButton } from "@/components/SubmitButton";
import { StrategySwitch } from "@/components/StrategySwitch";
import { getStrategyConfig, getTenantConfig } from "@/lib/bff";
import { postStrategyConfig, postTenantConfig } from "@/lib/apiGateway";
import type { StrategyConfigResponse, TenantConfig } from "@/lib/bff";
import { CONFIG_FIELD_INFO } from "@/components/ConfigFieldReference";
import { fmtCurrency } from "@/components/Pnl";

export const dynamic = "force-dynamic";

// Dark-by-default: the SAVE form (and the editable inputs) only appear when this env flag is
// explicitly "true". Unset/anything-else => read-only view. The api-gateway /strategy-config route
// is itself dark (404s) until its own flag is on, so even with this true the action degrades
// gracefully on 404.
const WRITE_ENABLED = process.env.STRATEGY_CONFIG_WRITE_ENABLED === "true";

// Independent dark flag for the account-cap tighten-only WRITE form (account-loss-cap-db Phase 3).
// Separate from STRATEGY_CONFIG_WRITE_ENABLED so the account-cap write handle flips on its own,
// AFTER risk-manager sign-off. Unset/anything-else => read-only cap view. The api-gateway
// /tenant-config route is itself dark (404s) until its own flag is on, so even with this true the
// action degrades gracefully on 404. Server is authoritative; the client tighten hint is UX-only.
const TENANT_WRITE_ENABLED = process.env.TENANT_CONFIG_WRITE_ENABLED === "true";

// The per-tenant strategy on/off field. It gets a dedicated Switch (rendered in the section header)
// instead of the generic boolean select, so it's excluded from the field list below. Treat a missing
// value as enabled (older configs predate the flag).
const ENABLED_FIELD = "enabled";

// Deprecated per single-account-loss-rule (2026-07-15): the account cap (account_daily_loss_pct) is
// the sole daily-loss breaker; the dead per-strategy field is hidden from /config (still nullable in
// the schema). It must be FILTERED OUT explicitly — classOf() defaults unlisted fields to SAFE,
// which would otherwise render it as an editable input.
const DEPRECATED_HIDDEN_FIELDS = new Set(["daily_loss_threshold"]);

function resolveEnabled(config: Record<string, unknown>): boolean {
  // Absent OR null (JSONB null / older configs that predate the flag) -> enabled, matching the
  // backend's "absent/null treated as true" semantics; only an explicit false renders off.
  const value = config[ENABLED_FIELD];
  return value === undefined || value === null ? true : Boolean(value);
}

type FieldClass = "IDENTITY" | "DANGEROUS" | "EXPOSURE" | "SAFE";

// A field's class drives whether it's read-only and how it's labeled. Anything not listed in the
// BFF's field_classes is SAFE.
function classOf(
  field: string,
  fieldClasses: StrategyConfigResponse["field_classes"],
): FieldClass {
  if (fieldClasses.IDENTITY.includes(field)) return "IDENTITY";
  if (fieldClasses.DANGEROUS.includes(field)) return "DANGEROUS";
  if (fieldClasses.EXPOSURE.includes(field)) return "EXPOSURE";
  return "SAFE";
}

// Scalars (string/number/boolean) are editable; arrays/objects/null are rendered as read-only
// pretty-printed JSON for now (edited via config files).
type ScalarKind = "string" | "number" | "boolean";
function scalarKind(value: unknown): ScalarKind | null {
  if (typeof value === "string") return "string";
  if (typeof value === "number") return "number";
  if (typeof value === "boolean") return "boolean";
  return null;
}

// Per-field form input name: "<strategy_id>::<field>". Keeps multiple strategies' edits distinct
// within one form submission while remaining a flat FormData key.
function inputName(strategyId: string, field: string): string {
  return `${strategyId}::${field}`;
}

// Single source of truth for "is this field writable from the UI" (ignoring the WRITE_ENABLED
// gate): a scalar that is neither IDENTITY nor DANGEROUS. Used by BOTH the render (to decide
// editable inputs) and the save action (to decide which fields to overlay) so the two never drift.
function isEditableField(klass: FieldClass, kind: ScalarKind | null): boolean {
  return kind !== null && klass !== "IDENTITY" && klass !== "DANGEROUS";
}

const CLASS_BADGE: Record<
  Exclude<FieldClass, "SAFE">,
  { label: string; className: string }
> = {
  IDENTITY: {
    label: "read-only",
    className: "border-slate-600 bg-slate-800 text-slate-300",
  },
  DANGEROUS: {
    label: "dual-control",
    className: "border-amber-500/40 bg-amber-500/10 text-amber-300",
  },
  EXPOSURE: {
    label: "tighten-only",
    className: "border-sky-500/40 bg-sky-500/10 text-sky-300",
  },
};

// Renders one field's value cell. Editable scalars become inputs (boolean → select, number →
// number input, string → text input); everything else is read-only (a scalar span, or pretty-
// printed JSON for arrays/objects/null).
function FieldValue({
  name,
  value,
  kind,
  editable,
  options,
  control,
}: {
  name: string;
  value: unknown;
  kind: ScalarKind | null;
  editable: boolean;
  // Optional control hints from the field's CONFIG_FIELD_INFO metadata (single source of truth):
  // `options` ⇒ enum <select>; `control === "time"` ⇒ HH:MM time input. Server validation is still
  // the authority — these only prevent typos on known-enum / time-of-day string fields.
  options?: { value: string; label: string }[];
  control?: "time";
}) {
  const inputClass =
    "w-full rounded border border-slate-700 bg-slate-950 px-2 py-1 text-sm text-slate-100";

  if (editable && kind === "boolean") {
    return (
      <select
        id={name}
        name={name}
        defaultValue={String(value as boolean)}
        className={inputClass}
      >
        <option value="true">true</option>
        <option value="false">false</option>
      </select>
    );
  }
  // Enum string → <select> over exactly the schema values (defaulted to the current stored value).
  if (editable && kind === "string" && options) {
    return (
      <select
        id={name}
        name={name}
        defaultValue={String(value)}
        className={inputClass}
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>
    );
  }
  // Time-of-day (HH:MM ET) string → native time input.
  if (editable && kind === "string" && control === "time") {
    return (
      <input
        id={name}
        name={name}
        type="time"
        defaultValue={String(value)}
        className={inputClass}
      />
    );
  }
  if (editable && (kind === "number" || kind === "string")) {
    return (
      <input
        id={name}
        name={name}
        type={kind === "number" ? "number" : "text"}
        step={kind === "number" ? "any" : undefined}
        defaultValue={String(value)}
        spellCheck={false}
        className={inputClass}
      />
    );
  }
  if (kind !== null) {
    // Read-only scalar (IDENTITY/DANGEROUS, or write disabled).
    return (
      <span className="block font-mono text-slate-300 sm:text-right">
        {String(value)}
      </span>
    );
  }
  // Complex value (array/object/null) — read-only pretty JSON.
  return (
    <div className="flex flex-col gap-0.5">
      <pre className="overflow-x-auto rounded bg-slate-950 px-2 py-1 text-xs text-slate-300">
        {JSON.stringify(value, null, 2)}
      </pre>
      <span className="text-xs text-slate-500">edit via config files for now</span>
    </div>
  );
}

// Account-level daily-loss cap (tenant-wide, realized + open P&L) — distinct from the per-strategy
// `daily_loss_threshold` rendered above. Read-only unless `writeEnabled` (account-loss-cap-db Phase
// 3), in which case a SET cap becomes a tighten-only number input (a null cap stays read-only — the
// server rejects ADDING a cap where none existed). Reuses the EXPOSURE "tighten-only" badge + the
// SubmitButton + the coarse banner. `cfg === null` = unset or the read degraded (no section).
// `account_daily_loss_pct` is a FRACTION (0.40 == "40%"); `account_daily_loss_threshold` is USD.
// The client tighten hint is UX-only — the server (TenantConfigWriter) is authoritative.
function AccountCapSection({
  cfg,
  writeEnabled,
  action,
}: {
  cfg: TenantConfig | null;
  writeEnabled: boolean;
  action: (formData: FormData) => void;
}) {
  if (cfg === null) return null;
  // Treat a non-positive value as unset ("not set"), agreeing with /live — a stored 0 must not
  // render as a configured "0% cap" (which would read as "halt at any loss") on a real-money page.
  const pct =
    typeof cfg.account_daily_loss_pct === "number" && cfg.account_daily_loss_pct > 0
      ? cfg.account_daily_loss_pct
      : null;
  const usd =
    typeof cfg.account_daily_loss_threshold === "number" &&
    cfg.account_daily_loss_threshold > 0
      ? cfg.account_daily_loss_threshold
      : null;
  // The "min 0.05" / "min $100" hints below are DISPLAY ONLY and mirror the authoritative policy
  // floors TenantConfigWriter.MIN_ACCOUNT_DAILY_LOSS_PCT / MIN_ACCOUNT_DAILY_LOSS_THRESHOLD_USD — keep
  // them in sync if those tune (the server enforces the real floor regardless of this hint).
  const rows: {
    field: string;
    label: string;
    display: string | null;
    // The raw value fed to the editable input (fraction for pct, USD for threshold).
    raw: number | null;
  }[] = [
    {
      field: "account_daily_loss_pct",
      label: "fraction of start-of-day equity (min 0.05)",
      display: pct === null ? null : `${+(pct * 100).toFixed(2)}%`,
      raw: pct,
    },
    {
      field: "account_daily_loss_threshold",
      label: "absolute USD (realized + open P&L, min $100)",
      display: usd === null ? null : fmtCurrency(usd),
      raw: usd,
    },
  ];
  const badge = CLASS_BADGE.EXPOSURE;
  const inputClass =
    "w-full rounded border border-slate-700 bg-slate-950 px-2 py-1 text-sm text-slate-100";

  // The cap is a single loss rule expressed as EITHER a fraction (pct) OR an absolute USD threshold.
  // Show only the form(s) actually set; if none is set, show a single "not set" line (the pct form)
  // rather than two confusing "not set" rows for a knob the tenant isn't using.
  const setRows = rows.filter((r) => r.raw !== null);
  const displayRows = setRows.length > 0 ? setRows : rows.slice(0, 1);

  const list = (
    <ul className="flex flex-col divide-y divide-slate-800 rounded border border-slate-800 bg-slate-900">
      {displayRows.map((r) => {
        // Editable only when write is enabled AND the cap is currently SET (a null cap can't be
        // added tenant-side — the server rejects it — so it stays read-only "not set").
        const editable = writeEnabled && r.raw !== null;
        return (
          <li
            key={r.field}
            className="flex flex-col gap-1 px-3 py-2 text-sm sm:flex-row sm:items-center sm:justify-between sm:gap-4"
          >
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-slate-200">{r.field}</span>
              <span
                className={`rounded border px-1.5 py-0.5 text-xs ${badge.className}`}
              >
                {badge.label}
              </span>
              <span className="text-xs text-slate-500">{r.label}</span>
              {editable && (
                <span className="text-xs text-slate-500">tighten only</span>
              )}
            </div>
            <div className="sm:w-1/2 sm:max-w-md">
              {editable ? (
                <input
                  id={r.field}
                  name={r.field}
                  type="number"
                  step="any"
                  defaultValue={String(r.raw)}
                  spellCheck={false}
                  className={inputClass}
                />
              ) : r.display === null ? (
                <span className="block text-slate-500 sm:text-right">not set</span>
              ) : (
                <FieldValue
                  name={r.field}
                  value={r.display}
                  kind="string"
                  editable={false}
                />
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );

  return (
    <section className="mt-8">
      <h2 className="mb-2 text-lg font-medium text-slate-100">
        Account daily-loss cap
      </h2>
      <p className="mb-2 text-sm text-slate-400">
        Tenant-wide cap across all your strategies (realized + open P&amp;L) — separate from the
        per-strategy limits above. You can only make it <strong>stricter</strong> (a lower cap =
        an earlier halt).
      </p>
      {writeEnabled ? (
        <form action={action} className="flex flex-col gap-2">
          {list}
          <div className="mt-1">
            <SubmitButton className="rounded border border-slate-700 bg-slate-900 px-4 py-2 text-sm font-medium text-slate-100 hover:bg-slate-800">
              Save cap
            </SubmitButton>
          </div>
        </form>
      ) : (
        list
      )}
    </section>
  );
}

export default async function ConfigPage({
  searchParams,
}: {
  searchParams: { saved?: string; error?: string };
}) {
  // Independent reads — run them together rather than serializing the BFF fetches. The tenant-config
  // read degrades to null (no account-cap section) rather than failing the whole page.
  const [session, cfg, tenantConfig] = await Promise.all([
    auth(),
    getStrategyConfig(),
    getTenantConfig().catch(() => null),
  ]);

  const saved = searchParams.saved === "1";
  const errorStatus = searchParams.error;

  // Coarse banner mapping — the api-gateway returns coarse statuses only, never config detail.
  let banner: { tone: "ok" | "err"; msg: string } | null = null;
  if (saved) {
    banner = { tone: "ok", msg: "Config saved." };
  } else if (errorStatus) {
    if (errorStatus === "409") {
      banner = { tone: "err", msg: "Version changed — reload and retry." };
    } else if (errorStatus === "403") {
      banner = {
        tone: "err",
        msg: "That change is not allowed (dangerous/tighten-only).",
      };
    } else if (errorStatus === "422") {
      banner = {
        tone: "err",
        msg: "That cap is below the allowed minimum.",
      };
    } else if (errorStatus === "400") {
      banner = { tone: "err", msg: "Invalid value." };
    } else if (errorStatus === "404") {
      banner = { tone: "err", msg: "Config editing not available." };
    } else {
      // 0 (transport error / timeout — write did not happen), 503, or anything else.
      banner = { tone: "err", msg: "Could not save. Try again." };
    }
  }

  // Inline server action: re-verifies the session, rebuilds the FULL config (stored config with
  // edited SCALAR + EDITABLE fields overlaid), recomputes expected_version from the current item,
  // forwards via the server-only client, then redirects with a COARSE result. NEVER puts config
  // values in the redirect.
  async function saveConfig(formData: FormData) {
    "use server";
    const s = await auth();
    if (!s?.tenantId) {
      redirect("/signin");
    }

    const strategyId = String(formData.get("strategy_id") ?? "");

    // Re-read the latest config inside the action: rebuild the full object from the stored config
    // (source of truth) and recompute expected_version for optimistic concurrency.
    const current = await getStrategyConfig();
    const item = current.items.find((i) => i.strategy_id === strategyId);
    if (!item) {
      redirect("/config?error=404");
    }
    // Optimistic-CAS guard: a missing/non-numeric version would send a bad expected_version to the
    // gateway (a silent mis-CAS). Fail safe instead. In practice the DB column is BIGINT DEFAULT 1,
    // so this only trips on an unseeded/degraded read.
    if (!Number.isFinite(item.version)) {
      redirect("/config?error=409");
    }

    // Start from the stored config; overlay only the editable scalar fields. IDENTITY/DANGEROUS are
    // never read from the form (no input rendered) so they pass through untouched. Complex values
    // (arrays/objects) also pass through untouched.
    const nextConfig: Record<string, unknown> = { ...item.config };
    for (const [field, value] of Object.entries(item.config)) {
      // Deprecated + hidden (no input rendered): never overlay from the form. The field is now SAFE
      // server-side, so without this a crafted POST could change it — pass through the stored value.
      if (DEPRECATED_HIDDEN_FIELDS.has(field)) continue;
      const klass = classOf(field, current.field_classes);
      const kind = scalarKind(value);
      // IDENTITY/DANGEROUS or complex values are never editable — pass through untouched.
      if (!isEditableField(klass, kind)) continue;
      const raw = formData.get(inputName(strategyId, field));
      if (raw === null) continue;
      if (kind === "number") {
        const n = Number(raw);
        // Reject a non-numeric edit before it reaches the gateway.
        if (!Number.isFinite(n)) {
          redirect("/config?error=400");
        }
        nextConfig[field] = n;
      } else if (kind === "boolean") {
        // Boolean rendered as a select with "true"/"false" values.
        nextConfig[field] = String(raw) === "true";
      } else {
        nextConfig[field] = String(raw);
      }
    }

    // The on/off Switch submits `enabled` on its own (the stored config may not yet contain it, so
    // the loop above can't pick it up). Overlay it explicitly when present in the form.
    const enabledRaw = formData.get(inputName(strategyId, ENABLED_FIELD));
    if (enabledRaw !== null) {
      nextConfig[ENABLED_FIELD] = String(enabledRaw) === "true";
    }

    const result = await postStrategyConfig({
      strategy_id: strategyId,
      config: nextConfig,
      expected_version: item.version,
      correlation_id: crypto.randomUUID(),
    });

    revalidatePath("/config");
    // NEVER put config values in the redirect — only a coarse saved/error marker.
    redirect(result.ok ? "/config?saved=1" : "/config?error=" + result.status);
  }

  // account-loss-cap-db (Phase 3) server action: re-verifies the session, RECOMPUTES
  // expected_version from a FRESH tenant-config read (optimistic CAS), overlays the edited cap
  // fields onto the fresh stored state (a read-only null cap passes through untouched), forwards via
  // the server-only client, then redirects with a COARSE result. NEVER puts cap values in the
  // redirect. The server enforces tighten-only + floor; this is UX only.
  async function saveAccountCap(formData: FormData) {
    "use server";
    const s = await auth();
    if (!s?.tenantId) {
      redirect("/signin");
    }

    const fresh = await getTenantConfig().catch(() => null);
    // Fail safe: a degraded read or a missing/non-numeric version would send a bad expected_version
    // (a silent mis-CAS). The DB column is BIGINT DEFAULT 1, so this only trips on a degraded read.
    if (!fresh || !Number.isFinite(fresh.version)) {
      redirect("/config?error=409");
    }

    // Full desired state = fresh stored values, with the edited (SET) fields overlaid. Normalize a
    // NON-POSITIVE stored cap to null (unset) to match AccountCapSection's display: a <=0 field renders
    // read-only "not set" and carries no input, so its baseline must be null here too. Otherwise a
    // stored 0 would be resubmitted as literal 0, which TenantConfigWriter.validateRange forbids —
    // rejecting the WHOLE request (400) and blocking an edit to the OTHER cap field. A null baseline
    // passes through as null (the server rejects adding a cap where none existed).
    let threshold =
      typeof fresh.account_daily_loss_threshold === "number" &&
      fresh.account_daily_loss_threshold > 0
        ? fresh.account_daily_loss_threshold
        : null;
    let pct =
      typeof fresh.account_daily_loss_pct === "number" && fresh.account_daily_loss_pct > 0
        ? fresh.account_daily_loss_pct
        : null;

    const rawThreshold = formData.get("account_daily_loss_threshold");
    if (rawThreshold !== null && String(rawThreshold).trim() !== "") {
      const n = Number(rawThreshold);
      if (!Number.isFinite(n)) {
        redirect("/config?error=400");
      }
      threshold = n;
    }
    const rawPct = formData.get("account_daily_loss_pct");
    if (rawPct !== null && String(rawPct).trim() !== "") {
      const n = Number(rawPct);
      if (!Number.isFinite(n)) {
        redirect("/config?error=400");
      }
      pct = n;
    }

    const result = await postTenantConfig({
      account_daily_loss_threshold: threshold,
      account_daily_loss_pct: pct,
      expected_version: fresh.version as number,
      correlation_id: crypto.randomUUID(),
    });

    revalidatePath("/config");
    redirect(result.ok ? "/config?saved=1" : "/config?error=" + result.status);
  }

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Config</h1>
        <p className="mb-4 text-sm text-slate-400">Strategy configuration.</p>

        {banner && (
          <div
            className={
              banner.tone === "ok"
                ? "mb-4 rounded border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-300"
                : "mb-4 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300"
            }
          >
            {banner.msg}
          </div>
        )}

        {!WRITE_ENABLED && (
          <p className="mb-4 text-sm text-slate-500">
            Config editing not enabled.
          </p>
        )}

        {/* Account-wide cap first — it governs every strategy below. */}
        <div className="mb-8">
          <AccountCapSection
            cfg={tenantConfig}
            writeEnabled={TENANT_WRITE_ENABLED}
            action={saveAccountCap}
          />
        </div>

        {cfg.items.length === 0 ? (
          <p className="text-sm text-slate-400">No strategy config available.</p>
        ) : (
          <div className="flex flex-col gap-8">
            {[...cfg.items]
              .sort((a, b) => a.strategy_id.localeCompare(b.strategy_id))
              .map((item) => {
              const fields = Object.entries(item.config)
                .filter(
                  ([field]) =>
                    field !== ENABLED_FIELD && !DEPRECATED_HIDDEN_FIELDS.has(field),
                )
                .sort(([a], [b]) => a.localeCompare(b));
              const enabled = resolveEnabled(item.config);
              return (
                // Collapsible so the page stays short — the summary (name + version + on/off) shows
                // when collapsed; expand to see/edit fields. Native <details>: no client JS needed.
                <details
                  key={item.strategy_id}
                  className="rounded border border-slate-800 bg-slate-900/30"
                >
                  <summary className="cursor-pointer px-3 py-2 text-lg font-medium text-slate-100 marker:text-slate-500 hover:bg-slate-800/40">
                    {item.strategy_id}{" "}
                    <span className="text-sm font-normal text-slate-400">
                      v{item.version}
                    </span>{" "}
                    <span
                      className={`text-xs ${enabled ? "text-emerald-400" : "text-slate-500"}`}
                    >
                      {enabled ? "● enabled" : "○ disabled"}
                    </span>
                  </summary>

                  <div className="px-3 pb-3 pt-1">
                    <div className="mb-3">
                      <StrategySwitch
                        strategyId={item.strategy_id}
                        enabled={enabled}
                        writeEnabled={WRITE_ENABLED}
                        enabledFieldName={inputName(item.strategy_id, ENABLED_FIELD)}
                        action={saveConfig}
                      />
                    </div>

                    <form action={saveConfig} className="flex flex-col gap-2">
                    <input
                      type="hidden"
                      name="strategy_id"
                      value={item.strategy_id}
                    />

                    <ul className="flex flex-col divide-y divide-slate-800 rounded border border-slate-800 bg-slate-900">
                      {fields.map(([field, value]) => {
                        const klass = classOf(field, cfg.field_classes);
                        const kind = scalarKind(value);
                        const badge =
                          klass === "SAFE" ? null : CLASS_BADGE[klass];
                        // Editable only when: write enabled AND the field is UI-writable.
                        const editable =
                          WRITE_ENABLED && isEditableField(klass, kind);
                        const name = inputName(item.strategy_id, field);
                        const info = CONFIG_FIELD_INFO[field];

                        return (
                          <li
                            key={field}
                            className="flex flex-col gap-2 px-3 py-2 text-sm"
                          >
                            <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
                              <div className="flex flex-wrap items-center gap-2">
                                <label
                                  htmlFor={editable ? name : undefined}
                                  className="font-mono text-slate-200"
                                >
                                  {field}
                                </label>
                                {badge && (
                                  <span
                                    className={`rounded border px-1.5 py-0.5 text-xs ${badge.className}`}
                                  >
                                    {badge.label}
                                  </span>
                                )}
                                {klass === "EXPOSURE" && editable && (
                                  <span className="text-xs text-slate-500">
                                    tighten only
                                  </span>
                                )}
                              </div>

                              <div className="sm:w-1/2 sm:max-w-md">
                                <FieldValue
                                  name={name}
                                  value={value}
                                  kind={kind}
                                  editable={editable}
                                  options={info?.options}
                                  control={info?.control}
                                />
                              </div>
                            </div>

                            {info && (
                              <dl className="space-y-0.5 text-xs text-slate-400">
                                <div>
                                  <span className="font-medium text-slate-300">
                                    What:{" "}
                                  </span>
                                  {info.what}
                                </div>
                                <div>
                                  <span className="font-medium text-slate-300">
                                    Effect:{" "}
                                  </span>
                                  {info.effect}
                                </div>
                                <div>
                                  <span className="font-medium text-slate-300">
                                    Example:{" "}
                                  </span>
                                  <span className="text-slate-500">
                                    {info.example}
                                  </span>
                                </div>
                              </dl>
                            )}
                          </li>
                        );
                      })}
                    </ul>

                    {WRITE_ENABLED && (
                      <div className="mt-1">
                        <SubmitButton className="rounded border border-slate-700 bg-slate-900 px-4 py-2 text-sm font-medium text-slate-100 hover:bg-slate-800">
                          Save config
                        </SubmitButton>
                      </div>
                    )}
                    </form>
                  </div>
                </details>
              );
            })}
          </div>
        )}
      </main>
    </>
  );
}
