import { DataTable } from "@/components/DataTable";
import { contractCell } from "@/components/ContractLink";
import { pnlCell, priceCell } from "@/components/Pnl";
import { PositionsUnavailable } from "@/components/PositionsUnavailable";
import type { Portfolio } from "@/lib/bff";

// The Open Positions page body, rendered from a portfolio read passed as a prop.
export function PositionsView({ p }: { p: Portfolio }) {
  const count = p.open_positions_count;
  const degraded = p.open_positions_degraded === true;
  return (
    <main className="mx-auto max-w-6xl px-4 py-6">
      <h1 className="mb-1 text-xl font-semibold text-slate-100">Open Positions</h1>
      <p className="mb-4 text-sm text-slate-400">
        {degraded ? "Unknown number of" : count} open position{count === 1 ? "" : "s"} across
        your strategies. Notional is cost basis at entry; price &amp; P&amp;L are live broker
        marks (shown when available).
      </p>
      {degraded ? (
        <PositionsUnavailable />
      ) : (
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
      )}
    </main>
  );
}
