import { DataTable } from "@/components/DataTable";
import { contractCell } from "@/components/ContractLink";
import type { Order } from "@/lib/bff";

// The Order History page body. Data arrives as props so the same markup renders wherever the rows
// come from; the page itself only fetches.
export function OrdersView({ items }: { items: Order[] }) {
  return (
    <main className="mx-auto max-w-6xl px-4 py-6">
      <h1 className="mb-1 text-xl font-semibold text-slate-100">Order History</h1>
      <p className="mb-4 text-sm text-slate-400">
        All order-journal states, newest first.
      </p>
      <DataTable
        empty="No orders yet."
        columns={[
          { key: "recorded_at", label: "Recorded" },
          { key: "option_symbol", label: "Contract", render: contractCell },
          { key: "side", label: "Side" },
          { key: "qty", label: "Qty" },
          { key: "state", label: "State" },
          { key: "limit_price", label: "Limit" },
          { key: "avg_fill_price", label: "Avg fill" },
          { key: "last_error", label: "Error" },
        ]}
        rows={items}
      />
    </main>
  );
}
