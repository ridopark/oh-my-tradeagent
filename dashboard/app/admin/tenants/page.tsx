import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { ActivateButton } from "@/components/ActivateButton";
import {
  getAdminTenants,
  AdminReadDisabledError,
  type AdminTenantItem,
} from "@/lib/adminBff";
import { postActivation } from "@/lib/adminActivation";

export const dynamic = "force-dynamic";

// Dark-by-default: the activate/deactivate buttons are only live when this flag is explicitly
// "true". Unset/anything-else => buttons rendered disabled (read-only list). The api-gateway
// activation route is itself dark (404s) until operator.activation.enabled is on, so even with this
// true the action degrades gracefully. Activation arms REAL money — keep it off until Phase E.
const ACTIVATION_ENABLED = process.env.OPERATOR_ACTIVATION_ENABLED === "true";

// Format an ISO timestamp as a UTC date for the "valid until" display. Server-rendered with an
// explicit UTC zone so it doesn't drift by render host. Fail-safe: a null/blank/unparseable value
// renders "unknown" rather than throwing a RangeError that would 500 the whole admin page (the BFF
// guarantees expires_at on VALID rows, so this only guards against a future contract drift).
function fmtDate(iso: string | null): string {
  if (!iso) {
    return "unknown";
  }
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "unknown" : d.toISOString().slice(0, 10);
}

// Per-state presentation for a LIVE row's activation badge. The state strings come straight from the
// gate's classification (LivePromotionStateReader) so the dashboard shows the SAME disposition the
// live-promotion gate computes before each real BTO.
function activationBadge(item: AdminTenantItem): {
  label: string;
  className: string;
} {
  const slate = "border-slate-600 bg-slate-800 text-slate-300";
  const amber = "border-amber-500/40 bg-amber-500/10 text-amber-300";
  const red = "border-red-500/40 bg-red-500/10 text-red-300";
  const emerald = "border-emerald-500/40 bg-emerald-500/10 text-emerald-300";
  switch (item.activation_state) {
    case "VALID":
      return item.at_risk
        ? {
            label: `valid until ${fmtDate(item.expires_at)} · expiring soon`,
            className: amber,
          }
        : {
            label: `valid until ${fmtDate(item.expires_at)}`,
            className: emerald,
          };
    case "STALE":
      return { label: "stale — re-approve", className: red };
    case "DEACTIVATED":
      return { label: "deactivated", className: slate };
    case "CONFIG_CHANGED":
      return { label: "config changed — re-approve", className: red };
    case "ABSENT":
      return { label: "not activated", className: slate };
    default:
      return { label: item.activation_state, className: slate };
  }
}

export default async function AdminTenantsPage({
  searchParams,
}: {
  searchParams: { done?: string; error?: string };
}) {
  // Independent reads — overlap the page's auth() with the BFF fetch (the config page's pattern).
  // The BFF fetch degrades to null when the admin-read route is dark (AdminReadDisabledError);
  // anything else propagates.
  const [session, adminRes] = await Promise.all([
    auth(),
    getAdminTenants().catch((e) => {
      if (e instanceof AdminReadDisabledError) {
        return null;
      }
      throw e;
    }),
  ]);

  // Coarse result banner from the activate/deactivate redirect.
  let banner: { tone: "ok" | "err"; msg: string } | null = null;
  if (searchParams.done) {
    banner = { tone: "ok", msg: `Live ${searchParams.done}.` };
  } else if (searchParams.error) {
    const e = searchParams.error;
    if (e === "422") {
      banner = { tone: "err", msg: "Rejected — see the row's state." };
    } else if (e === "404") {
      banner = { tone: "err", msg: "Activation not enabled." };
    } else {
      banner = { tone: "err", msg: "Could not complete. Try again." };
    }
  }

  // Server action: re-verify operator, forward to the api-gateway, redirect with a coarse result.
  async function activationAction(formData: FormData) {
    "use server";
    const s = await auth();
    if (!s?.isOperator) {
      redirect("/admin/tenants?error=403");
    }
    const tenantId = String(formData.get("tenant_id") ?? "");
    const strategyId = String(formData.get("strategy_id") ?? "");
    const intent = String(formData.get("intent") ?? "");
    if (intent !== "activate" && intent !== "deactivate") {
      redirect("/admin/tenants?error=400");
    }
    const result = await postActivation(
      intent as "activate" | "deactivate",
      tenantId,
      strategyId,
    );
    revalidatePath("/admin/tenants");
    redirect(
      result.ok
        ? `/admin/tenants?done=${intent === "activate" ? "activated" : "deactivated"}`
        : `/admin/tenants?error=${result.status}`,
    );
  }

  const readDisabled = adminRes === null;
  const items: AdminTenantItem[] = adminRes?.items ?? [];

  const columns = [
    { key: "tenant_id", label: "Tenant" },
    { key: "strategy_id", label: "Strategy" },
    { key: "broker_target", label: "Broker target" },
    {
      key: "account_masked",
      label: "Account",
      render: (v: unknown) => (
        <span className="font-mono text-slate-300">{String(v)}</span>
      ),
    },
    {
      key: "mode",
      label: "Mode",
      render: (v: unknown) =>
        v === "live" ? (
          <span className="rounded border border-amber-600/60 bg-amber-950/40 px-1.5 py-0.5 text-xs text-amber-300">
            ● live
          </span>
        ) : (
          <span className="rounded border border-slate-600 bg-slate-800 px-1.5 py-0.5 text-xs text-slate-300">
            paper
          </span>
        ),
    },
    {
      key: "activation_state",
      label: "Activation",
      render: (_v: unknown, row: Record<string, unknown>) => {
        const item = row as unknown as AdminTenantItem;
        if (item.mode !== "live") {
          return <span className="text-slate-500">—</span>;
        }
        const b = activationBadge(item);
        return (
          <span
            className={`rounded border px-1.5 py-0.5 text-xs ${b.className}`}
          >
            {b.label}
          </span>
        );
      },
    },
    {
      key: "_actions",
      label: "",
      render: (_v: unknown, row: Record<string, unknown>) => {
        const item = row as unknown as AdminTenantItem;
        if (item.mode !== "live") {
          return null;
        }
        const intent =
          item.activation_state === "VALID" ? "deactivate" : "activate";
        return (
          <ActivateButton
            tenantId={item.tenant_id}
            strategyId={item.strategy_id}
            intent={intent}
            action={activationAction}
            writeEnabled={ACTIVATION_ENABLED}
          />
        );
      },
    },
  ];

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">
          Operator · Tenants
        </h1>
        <p className="mb-4 text-sm text-slate-400">
          Every (tenant, strategy) with its broker account and, for live
          targets, the live-promotion activation state. Live activations carry a
          30-day TTL — a stale or config-changed promotion blocks real orders
          until re-approved.
        </p>

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

        {!ACTIVATION_ENABLED && (
          <p className="mb-4 text-sm text-slate-500">
            Activation controls not enabled (read-only).
          </p>
        )}

        {readDisabled ? (
          <p className="text-sm text-slate-400">
            Operator admin read not enabled.
          </p>
        ) : (
          <DataTable
            empty="No tenants."
            columns={columns}
            rows={items}
          />
        )}
      </main>
    </>
  );
}
