import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { getPortfolio, NotAuthenticatedError, type Portfolio } from "@/lib/bff";
import { brokerMode, brokerProvider } from "@/lib/mode";

export const dynamic = "force-dynamic";

// UI-P1: tenant operational status at a glance. Built ENTIRELY from the existing /api/portfolio
// read — no new backend, no money-path surface — so it surfaces the one fact no page shows today:
// is this tenant trading real money (LIVE) or simulated (PAPER)?
export default async function StatusPage() {
  const session = await auth();

  // The portfolio read fans out to Temporal-backed sub-reads on the orchestrator worker. When that
  // worker is unavailable (a deploy rollout, a crash) the BFF degrades each section but can still be
  // slow enough to hit the request timeout — render a clear "unavailable" state at HTTP 200 instead
  // of throwing into a 500. (#428)
  let p: Portfolio;
  try {
    p = await getPortfolio();
  } catch (err) {
    if (err instanceof NotAuthenticatedError) {
      throw err; // not a data outage — let the auth flow handle it.
    }
    return <StatusUnavailable tenantId={session?.tenantId} />;
  }

  const accounts = p.account_equity;
  const anyLive = accounts.some((a) => brokerMode(a.broker_target) === "live");
  const showAccount = accounts.some((a) => a.account_number);

  const rows = accounts.map((a) => {
    const mode = brokerMode(a.broker_target);
    return {
      broker: brokerProvider(a.broker_target),
      mode: mode === "unknown" ? "—" : mode.toUpperCase(),
      account: a.account_number ?? "—",
      equity: fmt(a.equity),
      status: a.equity === null || a.equity === undefined ? "Unavailable" : "Connected",
    };
  });

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Status</h1>
        <p className="mb-4 text-sm text-slate-400">
          Tenant {session?.tenantId} · trading day {p.trading_day} (America/New_York).
        </p>

        <ModeBanner anyLive={anyLive} hasAccounts={accounts.length > 0} />

        <section className="mb-6">
          <h2 className="mb-2 text-sm font-semibold text-slate-200">Brokers &amp; accounts</h2>
          <DataTable
            empty="No broker accounts configured."
            columns={[
              { key: "broker", label: "Broker" },
              { key: "mode", label: "Mode" },
              ...(showAccount ? [{ key: "account", label: "Account" }] : []),
              { key: "equity", label: "Equity" },
              { key: "status", label: "Status" },
            ]}
            rows={rows}
          />
          <p className="mt-2 text-xs text-slate-500">{p.account_equity_scope}</p>
        </section>

        <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Stat label="Open positions" value={String(p.open_positions_count)} />
          <Stat
            label="Sum open notional"
            value={fmt(p.sum_open_notional)}
            note="Cost basis at entry — not live mark."
          />
          <Stat label="Realized P&L today" value={fmt(p.realized_pnl_today)} />
        </section>
      </main>
    </>
  );
}

// Degraded render when the portfolio read fails outright (BFF unreachable / timed out). Keeps the
// page at HTTP 200 with the nav intact so the operator can still reach the kill switch and other
// pages, rather than a hard 500. (#428)
function StatusUnavailable({ tenantId }: { tenantId?: string }) {
  return (
    <>
      <Nav tenantId={tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Status</h1>
        <p className="mb-4 text-sm text-slate-400">Tenant {tenantId}</p>
        <div className="rounded border border-amber-600/60 bg-amber-950/40 px-4 py-3">
          <div className="text-sm font-semibold text-amber-300">
            Live status temporarily unavailable
          </div>
          <div className="mt-1 text-xs text-amber-200/80">
            The data service didn&apos;t respond in time (the orchestrator may be restarting).
            Refresh in a moment. This does not affect trading or the kill switch.
          </div>
        </div>
      </main>
    </>
  );
}

function ModeBanner({
  anyLive,
  hasAccounts,
}: {
  anyLive: boolean;
  hasAccounts: boolean;
}) {
  if (!hasAccounts) {
    return (
      <div className="mb-6 rounded border border-slate-800 bg-slate-900 px-4 py-3 text-sm text-slate-400">
        No broker target is active for this tenant yet.
      </div>
    );
  }
  if (anyLive) {
    return (
      <div className="mb-6 rounded border border-amber-600/60 bg-amber-950/40 px-4 py-3">
        <div className="text-sm font-semibold text-amber-300">● LIVE TRADING</div>
        <div className="mt-1 text-xs text-amber-200/80">
          Real-money orders are active on at least one broker target. Verify the account below.
        </div>
      </div>
    );
  }
  return (
    <div className="mb-6 rounded border border-slate-700 bg-slate-900 px-4 py-3">
      <div className="text-sm font-semibold text-slate-200">Paper trading</div>
      <div className="mt-1 text-xs text-slate-400">
        All active broker targets are simulated — no real-money orders.
      </div>
    </div>
  );
}

function Stat({
  label,
  value,
  note,
}: {
  label: string;
  value: string;
  note?: string;
}) {
  return (
    <div className="rounded border border-slate-800 bg-slate-900 px-4 py-3">
      <div className="text-xs uppercase tracking-wide text-slate-500">{label}</div>
      <div className="mt-1 text-lg font-semibold text-slate-100">{value}</div>
      {note && <div className="mt-1 text-xs text-slate-500">{note}</div>}
    </div>
  );
}

function fmt(v: string | number | null | undefined): string {
  if (v === null || v === undefined) {
    return "—";
  }
  const n = typeof v === "string" ? Number(v) : v;
  if (Number.isNaN(n)) {
    return String(v);
  }
  return n.toLocaleString(undefined, {
    style: "currency",
    currency: "USD",
  });
}
