import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { SubmitButton } from "@/components/SubmitButton";
import { getStrategyConfig } from "@/lib/bff";
import { postStrategyConfig } from "@/lib/apiGateway";
import type { StrategyConfigResponse } from "@/lib/bff";

export const dynamic = "force-dynamic";

// Dark-by-default: the SAVE form (and the editable inputs) only appear when this env flag is
// explicitly "true". Unset/anything-else => read-only view. The api-gateway /strategy-config route
// is itself dark (404s) until its own flag is on, so even with this true the action degrades
// gracefully on 404.
const WRITE_ENABLED = process.env.STRATEGY_CONFIG_WRITE_ENABLED === "true";

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
}: {
  name: string;
  value: unknown;
  kind: ScalarKind | null;
  editable: boolean;
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

export default async function ConfigPage({
  searchParams,
}: {
  searchParams: { saved?: string; error?: string };
}) {
  // Independent reads — run them together rather than serializing the BFF fetch behind auth().
  const [session, cfg] = await Promise.all([auth(), getStrategyConfig()]);

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

    // Start from the stored config; overlay only the editable scalar fields. IDENTITY/DANGEROUS are
    // never read from the form (no input rendered) so they pass through untouched. Complex values
    // (arrays/objects) also pass through untouched.
    const nextConfig: Record<string, unknown> = { ...item.config };
    for (const [field, value] of Object.entries(item.config)) {
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

        {cfg.items.length === 0 ? (
          <p className="text-sm text-slate-400">No strategy config available.</p>
        ) : (
          <div className="flex flex-col gap-8">
            {[...cfg.items]
              .sort((a, b) => a.strategy_id.localeCompare(b.strategy_id))
              .map((item) => {
              const fields = Object.entries(item.config).sort(([a], [b]) =>
                a.localeCompare(b),
              );
              return (
                <section key={item.strategy_id}>
                  <h2 className="mb-2 flex items-center gap-2 text-lg font-medium text-slate-100">
                    {item.strategy_id}
                    <span className="text-sm font-normal text-slate-400">
                      version {item.version}
                    </span>
                  </h2>

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

                        return (
                          <li
                            key={field}
                            className="flex flex-col gap-1 px-3 py-2 text-sm sm:flex-row sm:items-center sm:justify-between sm:gap-4"
                          >
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
                              />
                            </div>
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
                </section>
              );
            })}
          </div>
        )}
      </main>
    </>
  );
}
