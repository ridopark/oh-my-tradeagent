"use client";

import { DemoShell, Loading } from "~/components/DemoShell";
import { LiveView } from "~/components/LiveView";
import { useFills, useLiveMarksState, useOrders, usePortfolio } from "~/lib/feeds";

export default function LivePage() {
  const { p, stamps } = usePortfolio();
  const marks = useLiveMarksState();
  const fills = useFills();
  const orders = useOrders();
  return (
    <DemoShell stamps={[...stamps, ...fills.stamps, ...orders.stamps]}>
      {p ? (
        <LiveView p={p} marks={marks.state} trades={fills.items} orders={orders.items} />
      ) : (
        <Loading />
      )}
    </DemoShell>
  );
}
