import type { ReactNode } from "react";
import { DataTable } from "@/components/DataTable";
import type { AccountKillSwitch, Portfolio } from "@/lib/bff";
import { brokerMode, brokerProvider } from "@/lib/mode";
import { Pnl, fmtCurrency } from "@/components/Pnl";
import { PositionsUnavailable } from "@/components/PositionsUnavailable";
import { OptionsLevelNotice, fundingClauses } from "@/components/FundingBanners";
import type { levelShortfalls } from "@/lib/funding";

// The Status page body, rendered from reads passed as props. The page keeps the fetches, the
// kill-switch reset action and the session; `resetControl` is the reset button it builds (null for
// a read-only view).
export function StatusView({
  tenantId,
  p,
  killSwitch,
  shortfalls,
  notice,
  resetControl,
}: {
  tenantId?: string;
  p: Portfolio;
  killSwitch: AccountKillSwitch | null;
  shortfalls: ReturnType<typeof levelShortfalls>;
  notice?: string;
  resetControl: ReactNode;
}) {
  const accounts = p.account_equity;
  const anyLive = accounts.some((a) => brokerMode(a.broker_target) === "live");
  const showAccount = accounts.some((a) => a.account_number);
  // #942 funding detail, one line per account under the table (a column would push Status off a
  // phone screen). Accounts with no funding fields yet (exec not rolled) get no line at all.
  const fundingLines = accounts
    .map((a) => ({
      target: a.broker_target,
      label: `${brokerProvider(a.broker_target)} ${brokerMode(a.broker_target).toUpperCase()}`,
      clauses: fundingClauses(a),
    }))
    .filter((l) => l.clauses.length > 0);

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
  const positionsDegraded = p.open_positions_degraded === true;
  const unrealizedToday = sumMark("unrealized_intraday_pl");
  const unrealizedTotal = sumMark("unrealized_pl");

  return (
    <main className="mx-auto max-w-6xl px-4 py-6">
      <h1 className="mb-1 text-xl font-semibold text-slate-100">Status</h1>
      <p className="mb-4 text-sm text-slate-400">
        Tenant {tenantId} · trading day {p.trading_day} (America/New_York).
      </p>

      <ModeBanner anyLive={anyLive} hasAccounts={accounts.length > 0} />

      {notice === "reset" && (
        <div className="mb-6 rounded border border-emerald-500/40 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-300">
          Account kill switch reset — trading can resume.
        </div>
      )}
      {notice === "error" && (
        <div className="mb-6 rounded border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
          Could not reset the kill switch. Try again in a moment.
        </div>
      )}

      <KillSwitchPanel state={killSwitch} resetControl={resetControl} />

      {shortfalls.length > 0 && (
        <div className="mb-6">
          <OptionsLevelNotice shortfalls={shortfalls} />
        </div>
      )}

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
        {fundingLines.map((l) => (
          <p key={l.target} className="mt-2 text-xs text-slate-400">
            <span className="font-medium text-slate-300">{l.label}:</span>{" "}
            {l.clauses.join(" · ")}
          </p>
        ))}
        <p className="mt-2 text-xs text-slate-500">{p.account_equity_scope}</p>
      </section>

      {positionsDegraded && (
        <div className="mb-4">
          <PositionsUnavailable />
        </div>
      )}

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="Total account value"
          value={fmtCurrency(totalAccountValue)}
          note="Net-liq equity (account-level, shared)."
        />
        <Stat
          label="Open positions"
          value={positionsDegraded ? "Unknown" : String(p.open_positions_count)}
          note={positionsDegraded ? "Positions read failed — not an empty book." : undefined}
        />
        <Stat
          label="Sum open notional"
          value={positionsDegraded ? "—" : fmtCurrency(p.sum_open_notional)}
          note="Cost basis at entry — not live mark."
        />
        <Stat
          label="Realized P&L today"
          value={fmtCurrency(p.realized_pnl_today)}
          note="Booked P&L on trades closed today, measured vs. your entry cost (FIFO) — not the day's change in account value (see Live)."
        />
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
  );
}

// Degraded render when the portfolio read fails outright (BFF unreachable / timed out). Keeps the
// page at HTTP 200 with the nav intact so the operator can still reach the kill switch and other
// pages, rather than a hard 500. (#428)
export function StatusUnavailableView({ tenantId }: { tenantId?: string }) {
  return (
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
  );
}

// Account daily-loss kill switch surface. When tripped: a prominent RED banner + the reset control
// the page supplies (it owns the server action; a view with no control passes null). When not tripped: a minimal
// "guard active" line. Renders nothing if the read degraded (state === null).
function KillSwitchPanel({
  state,
  resetControl,
}: {
  state: AccountKillSwitch | null;
  resetControl: ReactNode;
}) {
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
      <div className="mt-3">{resetControl}</div>
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
