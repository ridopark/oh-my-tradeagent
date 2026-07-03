import { auth } from "@/auth";
import Link from "next/link";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { ActivateButton } from "@/components/ActivateButton";
import { DeleteTenantButton } from "@/components/DeleteTenantButton";
import {
  getAdminTenants,
  AdminReadDisabledError,
  type AdminTenantItem,
} from "@/lib/adminBff";
import { getTenantEmails, type TenantEmails } from "@/lib/db";
import { postActivation } from "@/lib/adminActivation";
import { postTenantDelete } from "@/lib/adminTenantDelete";

export const dynamic = "force-dynamic";

// Dark-by-default: the activate/deactivate buttons are only live when this flag is explicitly
// "true". Unset/anything-else => buttons rendered disabled (read-only list). The api-gateway
// activation route is itself dark (404s) until operator.activation.enabled is on, so even with this
// true the action degrades gracefully. Activation arms REAL money — keep it off until Phase E.
const ACTIVATION_ENABLED = process.env.OPERATOR_ACTIVATION_ENABLED === "true";

// Dark-by-default: the per-tenant "Delete" affordance is only rendered when this flag is explicitly
// "true" (and only for a NOT-live, all-dark tenant). Unset/anything-else => no delete affordance at
// all. The api-gateway delete route is itself dark (404s) and re-enforces the live/all-dark
// preconditions server-side (P0/P2), so this is the UI-side half of the same gate. Deleting a tenant
// is irreversible — keep it off until the operator cutover.
const TENANT_DELETE_ENABLED =
  process.env.OPERATOR_TENANT_DELETE_ENABLED === "true";

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
  searchParams: { done?: string; error?: string; blocked_by?: string };
}) {
  // Independent reads — overlap the page's auth() with the BFF fetch (the config page's pattern).
  // The BFF fetch degrades to null when the admin-read route is dark (AdminReadDisabledError);
  // anything else propagates.
  const [session, adminRes, emailsByTenant] = await Promise.all([
    auth(),
    getAdminTenants().catch((e) => {
      if (e instanceof AdminReadDisabledError) {
        return null;
      }
      throw e;
    }),
    // Member + pending-invite emails (dashboard DB, dashboard_readonly). Fail-safe: any error
    // (e.g. the V6 invite-grant not yet applied) degrades to no emails — never 500s the page. Log
    // it so a PERSISTENT failure (grant reverted, DB unreachable) is observable, not silently
    // indistinguishable from "no invites".
    getTenantEmails().catch((e) => {
      console.error("admin/tenants: getTenantEmails failed, rendering no emails", e);
      return new Map<string, TenantEmails>();
    }),
  ]);

  // Coarse result banner from the activate/deactivate/delete redirect.
  let banner: { tone: "ok" | "err"; msg: string } | null = null;
  if (searchParams.done === "deleted") {
    banner = { tone: "ok", msg: "Tenant deleted." };
  } else if (searchParams.done) {
    banner = { tone: "ok", msg: `Live ${searchParams.done}.` };
  } else if (searchParams.error) {
    const e = searchParams.error;
    if (e === "409") {
      // Delete blocked by a server-side precondition (live target / non-dark strategy). blocked_by
      // names the reason when the gateway supplied one.
      banner = {
        tone: "err",
        msg: searchParams.blocked_by
          ? `Blocked — ${searchParams.blocked_by}.`
          : "Blocked.",
      };
    } else if (e === "207") {
      // Partial teardown — some stores cleaned, one step failed. Safe to retry (idempotent).
      banner = { tone: "err", msg: "Partially deleted — retry." };
    } else if (e === "422") {
      banner = { tone: "err", msg: "Rejected — see the row's state." };
    } else if (e === "404") {
      // Activation not enabled. (A delete 404 = dark/flag off, but the delete button only renders
      // when the flag is on, so that path is unreachable from the UI.)
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

  // Server action: re-verify operator, enforce the type-to-confirm match, forward to the api-gateway
  // delete route, redirect with a coarse result. Belt-and-suspenders with the client modal and the
  // server-side P0/P2 preconditions — none of confirm/operator/live checks trust the browser alone.
  async function deleteTenantAction(formData: FormData) {
    "use server";
    const s = await auth();
    if (!s?.isOperator) {
      redirect("/admin/tenants?error=403");
    }
    const tenantId = String(formData.get("tenant_id") ?? "");
    const confirmTenantId = String(formData.get("confirm_tenant_id") ?? "");
    // Exact, case-sensitive match — reject a mismatch (or empty) before touching the gateway.
    if (!tenantId || confirmTenantId !== tenantId) {
      redirect("/admin/tenants?error=400");
    }
    const result = await postTenantDelete(tenantId, confirmTenantId);
    revalidatePath("/admin/tenants");
    if (result.ok) {
      redirect("/admin/tenants?done=deleted");
    }
    if (result.status === 409 && result.blockedBy) {
      redirect(
        `/admin/tenants?error=409&blocked_by=${encodeURIComponent(result.blockedBy)}`,
      );
    }
    redirect(`/admin/tenants?error=${result.status}`);
  }

  const readDisabled = adminRes === null;
  const items: AdminTenantItem[] = adminRes?.items ?? [];

  // The DataTable renders per-(tenant, strategy) rows, but delete is PER-TENANT. Pre-compute, across
  // all of a tenant's rows: (a) whether ANY row is live, (b) whether ANY strategy looks active
  // (activation_state VALID — belt-and-suspenders; only a live row can be VALID), and (c) the tenant's
  // FIRST strategy id (so the delete button renders once, on the first row). A tenant is "deletable"
  // from the UI iff it has NO live row AND nothing looks active — the authoritative all-dark check
  // lives server-side (P0/P2); this is only a UI guard so a live tenant shows NO delete affordance.
  const liveTenants = new Set<string>();
  const activeTenants = new Set<string>();
  const firstStrategyByTenant = new Map<string, string>();
  for (const it of items) {
    if (it.mode === "live") {
      liveTenants.add(it.tenant_id);
    }
    if (it.activation_state === "VALID") {
      activeTenants.add(it.tenant_id);
    }
    if (!firstStrategyByTenant.has(it.tenant_id)) {
      firstStrategyByTenant.set(it.tenant_id, it.strategy_id);
    }
  }
  const isDeletableTenant = (tenantId: string): boolean =>
    !liveTenants.has(tenantId) && !activeTenants.has(tenantId);

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
      key: "_members",
      label: "Members / invites",
      render: (_v: unknown, row: Record<string, unknown>) => {
        const e = emailsByTenant.get(String(row.tenant_id));
        if (!e || (e.members.length === 0 && e.pending.length === 0)) {
          return <span className="text-slate-500">—</span>;
        }
        return (
          <div className="flex flex-col gap-0.5 text-xs">
            {e.members.map((m) => (
              <span key={`m-${m}`} className="text-slate-300">
                {m}
              </span>
            ))}
            {e.pending.map((p) => (
              <span key={`p-${p}`} className="text-amber-300/80">
                {p} <span className="text-amber-500/70">(pending)</span>
              </span>
            ))}
          </div>
        );
      },
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
        if (item.mode === "live") {
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
        }
        // Paper row. The per-tenant delete affordance renders once (on the tenant's first row) and
        // only for a NOT-live, all-dark tenant when the dark flag is on. A live tenant never reaches
        // here (handled above) and so shows NO delete affordance at all.
        if (
          TENANT_DELETE_ENABLED &&
          isDeletableTenant(item.tenant_id) &&
          firstStrategyByTenant.get(item.tenant_id) === item.strategy_id
        ) {
          return (
            <DeleteTenantButton
              tenantId={item.tenant_id}
              action={deleteTenantAction}
            />
          );
        }
        return null;
      },
    },
  ];

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <div className="mb-1 flex items-center justify-between">
          <h1 className="text-xl font-semibold text-slate-100">
            Operator · Tenants
          </h1>
          <Link
            href="/admin/onboard"
            className="rounded border border-slate-700 px-2 py-1 text-sm text-slate-300 hover:bg-slate-800 hover:text-white"
          >
            + Onboard tenant
          </Link>
        </div>
        <div className="mb-4 space-y-1 text-sm text-slate-400">
          <p>
            Every (tenant, strategy) with its broker account, the people who can
            sign into the tenant (bound members + still-pending invites), and its
            trading mode.
          </p>
          <p>
            <span className="font-medium text-slate-300">Activation</span> is a{" "}
            <span className="font-medium text-amber-300">
              real-money safety gate for live targets only
            </span>
            : an <code className="text-slate-300">alpaca-live</code> account must
            hold a valid 30-day live-promotion before it can place a real order
            (a stale or config-changed promotion blocks orders until
            re-approved). Paper targets show{" "}
            <span className="text-slate-500">—</span> because activation does not
            apply to them — whether a paper strategy trades is governed by its{" "}
            <code className="text-slate-300">enabled</code> flag and signal
            subscription, not this column.
          </p>
        </div>

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
