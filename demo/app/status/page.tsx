"use client";

import { StatusView } from "@/components/views/StatusView";
import { DemoShell, Loading } from "~/components/DemoShell";
import { useKillSwitch, usePortfolio } from "~/lib/feeds";

export default function StatusPage() {
  const { p, stamps } = usePortfolio();
  const { killSwitch, stamp } = useKillSwitch();
  return (
    <DemoShell stamps={[...stamps, stamp]}>
      {p ? (
        <StatusView
          tenantId="paper demo"
          p={p}
          killSwitch={killSwitch}
          shortfalls={[]}
          resetControl={null}
        />
      ) : (
        <Loading />
      )}
    </DemoShell>
  );
}
