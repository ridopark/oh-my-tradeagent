"use client";

import { OrdersView } from "@/components/views/OrdersView";
import { DemoShell, Loading } from "~/components/DemoShell";
import { useOrders } from "~/lib/feeds";

export default function OrdersPage() {
  const { items, stamps } = useOrders();
  return <DemoShell stamps={stamps}>{items ? <OrdersView items={items} /> : <Loading />}</DemoShell>;
}
