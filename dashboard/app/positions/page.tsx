import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { contractCell } from "@/components/ContractLink";
import { pnlCell, priceCell } from "@/components/Pnl";
import { getPortfolio } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function PositionsPage() {
  const session = await auth();
  // Sourced from /api/portfolio (not /api/positions) so each row carries its live broker marks
  // (current price + today's/total unrealized P&L), joined by OCC in the BFF.
  const p = await getPortfolio();
  const count = p.open_positions_count;
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Open Positions</h1>
        <p className="mb-4 text-sm text-slate-400">
          {count} open position{count === 1 ? "" : "s"} across your strategies. Notional is cost
          basis at entry; price &amp; P&amp;L are live broker marks (shown when available).
        </p>
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
      </main>
    </>
  );
}
