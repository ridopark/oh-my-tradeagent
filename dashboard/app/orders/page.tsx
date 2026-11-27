import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { OrdersView } from "@/components/views/OrdersView";
import { getOrders } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function OrdersPage() {
  const session = await auth();
  const data = await getOrders();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <OrdersView items={data.items} />
    </>
  );
}
