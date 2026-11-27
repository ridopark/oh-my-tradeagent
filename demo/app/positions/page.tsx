"use client";

import { PositionsView } from "@/components/views/PositionsView";
import { DemoShell, Loading } from "~/components/DemoShell";
import { usePortfolio } from "~/lib/feeds";

export default function PositionsPage() {
  const { p, stamps } = usePortfolio();
  return <DemoShell stamps={stamps}>{p ? <PositionsView p={p} /> : <Loading />}</DemoShell>;
}
