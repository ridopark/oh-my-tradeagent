"use client";

import { TradesView } from "@/components/views/TradesView";
import { DemoShell, Loading } from "~/components/DemoShell";
import { useFills } from "~/lib/feeds";

export default function FillsPage() {
  const { items, stamps } = useFills();
  return <DemoShell stamps={stamps}>{items ? <TradesView items={items} title="Fills" showDetail={false} /> : <Loading />}</DemoShell>;
}
