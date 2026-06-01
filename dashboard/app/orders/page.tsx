import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { getOrders } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function OrdersPage() {
  const session = await auth();
  const data = await getOrders();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-800">Order History</h1>
        <p className="mb-4 text-sm text-slate-500">
          All order-journal states, newest first.
        </p>
        <DataTable
          empty="No orders yet."
          columns={[
            { key: "recorded_at", label: "Recorded" },
            { key: "option_symbol", label: "Contract" },
            { key: "side", label: "Side" },
            { key: "qty", label: "Qty" },
            { key: "state", label: "State" },
            { key: "limit_price", label: "Limit" },
            { key: "avg_fill_price", label: "Avg fill" },
            { key: "last_error", label: "Error" },
          ]}
          rows={data.items}
        />
      </main>
    </>
  );
}
