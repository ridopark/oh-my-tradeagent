import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { contractCell } from "@/components/ContractLink";
import { pnlCell, priceCell, fmtCurrency } from "@/components/Pnl";
import { getPortfolio } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function PortfolioPage() {
  const session = await auth();
  const p = await getPortfolio();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Portfolio</h1>
        <p className="mb-4 text-sm text-slate-400">Trading day {p.trading_day} (America/New_York).</p>

        <section className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Stat
            label="Open positions"
            value={String(p.open_positions_count)}
          />
          <Stat
            label="Sum open notional"
            value={fmtCurrency(p.sum_open_notional)}
            note="Cost basis at entry — not live mark."
          />
          <Stat label="Realized P&L today" value={fmtCurrency(p.realized_pnl_today)} />
        </section>

        <section className="mb-6">
          <h2 className="mb-2 text-sm font-semibold text-slate-200">Account equity</h2>
          <DataTable
            empty="No broker accounts."
            columns={[
              { key: "broker_target", label: "Broker" },
              // The BFF only emits account_number under its dev flag (never in prod), so show the
              // column only when present — confirms which brokerage account a broker_target maps to.
              ...(p.account_equity.some((a) => a.account_number)
                ? [{ key: "account_number", label: "Account" }]
                : []),
              { key: "equity", label: "Equity" },
            ]}
            rows={p.account_equity}
          />
          <p className="mt-2 text-xs text-slate-500">{p.account_equity_scope}</p>
        </section>

        <section className="mb-6">
          <h2 className="mb-2 text-sm font-semibold text-slate-200">Open positions</h2>
          <DataTable
            empty="No open positions."
            columns={[
              { key: "strategy_id", label: "Strategy" },
              { key: "contract_symbol", label: "Contract", render: contractCell },
              { key: "remaining_qty", label: "Qty" },
              { key: "entry_premium", label: "Entry premium" },
              { key: "open_notional", label: "Open notional" },
              { key: "current_price", label: "Current price", render: priceCell },
              { key: "unrealized_intraday_pl", label: "P&L (today)", render: pnlCell },
              { key: "unrealized_pl", label: "P&L (total)", render: pnlCell },
            ]}
            rows={p.open_positions}
          />
        </section>

        <p className="text-xs text-slate-500">{p.unrealized_pnl_note}</p>
      </main>
    </>
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

