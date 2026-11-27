"use client";

import { PortfolioView } from "@/components/views/PortfolioView";
import { DemoShell, Loading } from "~/components/DemoShell";
import { usePortfolio } from "~/lib/feeds";

export default function PortfolioPage() {
  const { p, stamps } = usePortfolio();
  return <DemoShell stamps={stamps}>{p ? <PortfolioView p={p} /> : <Loading />}</DemoShell>;
}
