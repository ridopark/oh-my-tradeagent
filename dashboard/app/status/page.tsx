import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import {
  getPortfolio,
  getAccountKillSwitch,
  resetAccountKillSwitch,
  NotAuthenticatedError,
  type Portfolio,
  type AccountKillSwitch,
} from "@/lib/bff";
import { brokerMode, brokerProvider } from "@/lib/mode";
import { Pnl, fmtCurrency } from "@/components/Pnl";
import { AccountKillSwitchReset } from "@/components/AccountKillSwitchReset";

export const dynamic = "force-dynamic";

// Dark-by-default: the tenant-self-service reset button only fires when this flag is explicitly
// "true". Unset/anything-else => the banner + countdown still render (so the tenant sees WHY trading
// is halted and when reset unlocks) but the button is inert. The BFF reset route is itself gated, so
// this is the UI half of the same dark launch.
const RESET_WRITE_ENABLED =
  process.env.ACCOUNT_KILLSWITCH_RESET_WRITE_ENABLED === "true";

// UI-P1: tenant operational status at a glance. Built ENTIRELY from the existing /api/portfolio
// read — no new backend, no money-path surface — so it surfaces the one fact no page shows today:
// is this tenant trading real money (LIVE) or simulated (PAPER)?
// Inline server action: re-verifies the session, forwards the reset to the BFF, and either refreshes
// the page (on success / no-op) or returns the circuit-breaker result to the client so its countdown
// can resync. Co-located with the page so it captures nothing but the request-scoped session.
async function resetKillSwitchAction(formData: FormData) {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    redirect("/signin");
  }
  const note = String(formData.get("note") ?? "").trim();
  const result = await resetAccountKillSwitch(note || undefined);

  if (result.ok) {
    revalidatePath("/status");
    redirect("/status?killswitch=reset");
  }
  if (result.error === "circuit_breaker_active") {
    // Race: the 15-min wait wasn't actually elapsed server-side. Hand the authoritative resettableAt
    // back to the client island so it re-locks + resyncs its countdown (no redirect).
    return { circuitBreakerActive: true, resettableAt: result.resettableAt };
  }
  if (result.error === "not_tripped") {
    // Already reset (or never tripped) — just refresh to the healthy state.
    revalidatePath("/status");
    redirect("/status");
  }
  if (result.error === "unauthorized") {
    redirect("/signin");
  }
  // Unknown/transport failure — surface a coarse error marker.
  redirect("/status?killswitch=error");
}

export default async function StatusPage({
  searchParams,
}: {
  searchParams: { killswitch?: string };
}) {
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

  // Account daily-loss kill switch. Read separately with its own guard so a kill-switch read failure
  // degrades to "no banner" rather than blanking the whole status page (the portfolio tiles matter
  // more than this optional widget).
  let killSwitch: AccountKillSwitch | null = null;
  try {
    killSwitch = await getAccountKillSwitch();
  } catch {
    killSwitch = null;
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
      equity: fmtCurrency(a.equity),
      status: a.equity === null || a.equity === undefined ? "Unavailable" : "Connected",
    };
  });

  // Total account value = net-liquidation equity summed across the tenant's broker_targets. Stays
  // null until a real number arrives (seed null, not 0) so "all unavailable" degrades to "—", not $0.
  const totalAccountValue = accounts.reduce<number | null>((sum, a) => {
    const n = a.equity == null ? NaN : Number(a.equity);
    return Number.isNaN(n) ? sum : (sum ?? 0) + n;
  }, null);

  // Unrealized P&L summed across open positions' live broker marks (today / total). Seed null so a
  // tenant whose positions carry NO marks degrades to "—" rather than a misleading $0; a row without
  // the field simply doesn't contribute.
  const sumMark = (field: "unrealized_intraday_pl" | "unrealized_pl"): number | null =>
    p.open_positions.reduce<number | null>((sum, pos) => {
      const raw = pos[field];
      const n = raw == null ? NaN : Number(raw);
      return Number.isNaN(n) ? sum : (sum ?? 0) + n;
    }, null);
  const unrealizedToday = sumMark("unrealized_intraday_pl");
  const unrealizedTotal = sumMark("unrealized_pl");

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Status</h1>
        <p className="mb-4 text-sm text-slate-400">
          Tenant {session?.tenantId} · trading day {p.trading_day} (America/New_York).
        </p>

        <ModeBanner anyLive={anyLive} hasAccounts={accounts.length > 0} />

        {searchParams.killswitch === "reset" && (
          <div className="mb-6 rounded border border-emerald-500/40 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-300">
            Account kill switch reset — trading can resume.
          </div>
        )}
        {searchParams.killswitch === "error" && (
          <div className="mb-6 rounded border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
            Could not reset the kill switch. Try again in a moment.
          </div>
        )}

        <KillSwitchPanel state={killSwitch} />

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

        <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="Total account value"
            value={fmtCurrency(totalAccountValue)}
            note="Net-liq equity (account-level, shared)."
          />
          <Stat label="Open positions" value={String(p.open_positions_count)} />
          <Stat
            label="Sum open notional"
            value={fmtCurrency(p.sum_open_notional)}
            note="Cost basis at entry — not live mark."
          />
          <Stat label="Realized P&L today" value={fmtCurrency(p.realized_pnl_today)} />
          <PnlStat
            label="Realized P&L (all-time)"
            value={p.realized_pnl_all_time}
            note="Since inception · FIFO cost basis."
          />
          <PnlStat
            label="Unrealized P&L (today)"
            value={unrealizedToday}
            note="Live broker marks summed across open positions."
          />
          <PnlStat
            label="Unrealized P&L (total)"
            value={unrealizedTotal}
            note="Live broker marks summed across open positions."
          />
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

// Account daily-loss kill switch surface. When tripped: a prominent RED banner + the client reset
// island (live 15-min circuit-breaker countdown → enabled reset button). When not tripped: a minimal
// "guard active" line. Renders nothing if the read degraded (state === null).
function KillSwitchPanel({ state }: { state: AccountKillSwitch | null }) {
  if (!state) return null;

  if (!state.tripped) {
    return (
      <div className="mb-6 text-xs text-slate-500">
        Daily-loss guard: <span className="text-emerald-400">active</span>.
      </div>
    );
  }

  return (
    <div className="mb-6 rounded border border-red-600/60 bg-red-950/40 px-4 py-3">
      <div className="text-sm font-semibold text-red-300">
        ● Account daily-loss limit hit — trading halted
      </div>
      <div className="mt-1 text-xs text-red-200/80">
        {state.reason ||
          "The account's daily loss cap was crossed. Trading is halted until you reset the kill switch."}
      </div>
      <div className="mt-3">
        <AccountKillSwitchReset
          trippedAt={state.trippedAt}
          resettableAt={state.resettableAt}
          resettable={state.resettable}
          action={resetKillSwitchAction}
          writeEnabled={RESET_WRITE_ENABLED}
        />
      </div>
    </div>
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

// Like Stat, but the value is a signed P&L rendered with the shared color-coded Pnl component.
function PnlStat({
  label,
  value,
  note,
}: {
  label: string;
  value: string | number | null | undefined;
  note?: string;
}) {
  return (
    <div className="rounded border border-slate-800 bg-slate-900 px-4 py-3">
      <div className="text-xs uppercase tracking-wide text-slate-500">{label}</div>
      <div className="mt-1 text-lg font-semibold">
        <Pnl value={value} />
      </div>
      {note && <div className="mt-1 text-xs text-slate-500">{note}</div>}
    </div>
  );
}
