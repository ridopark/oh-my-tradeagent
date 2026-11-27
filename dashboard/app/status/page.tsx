import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import {
  getPortfolio,
  getAccountKillSwitch,
  resetAccountKillSwitch,
  NotAuthenticatedError,
  type Portfolio,
  type AccountKillSwitch,
  getStrategyConfig,
} from "@/lib/bff";
import { AccountKillSwitchReset } from "@/components/AccountKillSwitchReset";
import { StatusUnavailableView, StatusView } from "@/components/views/StatusView";
import { levelShortfalls } from "@/lib/funding";

export const dynamic = "force-dynamic";

// Dark-by-default: the tenant-self-service reset button only fires when this flag is explicitly
// "true". Unset/anything-else => the banner + countdown still render (so the tenant sees WHY trading
// is halted and when reset unlocks) but the button is inert. The BFF reset route is independently
// flag-gated server-side (404s when its own flag is off), so this UI flag is only the visible half —
// both must be enabled to actually reset.
const RESET_WRITE_ENABLED =
  process.env.ACCOUNT_KILLSWITCH_RESET_WRITE_ENABLED === "true";

// UI-P1: tenant operational status at a glance. Built ENTIRELY from the existing /api/portfolio
// read — no new backend, no money-path surface — so it surfaces the one fact no page shows today:
// is this tenant trading real money (LIVE) or simulated (PAPER)?
// Inline server action: re-verifies the session, forwards the reset to the BFF, and either refreshes
// the page (on success / no-op) or returns the circuit-breaker result to the client so its countdown
// can resync. Co-located with the page so it captures nothing but the request-scoped session.
async function resetKillSwitchAction() {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    redirect("/signin");
  }
  const result = await resetAccountKillSwitch();

  if (result.ok) {
    revalidatePath("/status");
    redirect("/status?killswitch=reset");
  }
  if (result.error === "circuit_breaker_active") {
    // Race: the 15-min wait wasn't actually elapsed server-side. Hand the authoritative resettableAt
    // back to the client island so it re-locks + resyncs its countdown (no redirect).
    return { circuitBreakerActive: true, resettableAt: result.resettableAt };
  }
  if (result.error === "not_tripped") {
    // Already reset (or never tripped) — just refresh to the healthy state.
    revalidatePath("/status");
    redirect("/status");
  }
  if (result.error === "unauthorized") {
    redirect("/signin");
  }
  // Unknown/transport failure — surface a coarse error marker.
  redirect("/status?killswitch=error");
}

export default async function StatusPage({
  searchParams,
}: {
  searchParams: { killswitch?: string };
}) {
  const session = await auth();

  // The portfolio read fans out to Temporal-backed sub-reads on the orchestrator worker. When that
  // worker is unavailable (a deploy rollout, a crash) the BFF degrades each section but can still be
  // slow enough to hit the request timeout — render a clear "unavailable" state at HTTP 200 instead
  // of throwing into a 500. (#428)
  // Fetch both in parallel — the kill-switch query (a Temporal workflow query) shouldn't stack its
  // latency serially onto the portfolio read on every page load. Each is handled independently: a
  // portfolio outage degrades the whole page; a kill-switch read failure degrades only to "no banner"
  // (the portfolio tiles matter more than this optional widget).
  const [pRes, ksRes] = await Promise.allSettled([
    getPortfolio(),
    getAccountKillSwitch(),
  ]);

  if (pRes.status === "rejected") {
    if (pRes.reason instanceof NotAuthenticatedError) {
      throw pRes.reason; // not a data outage — let the auth flow handle it.
    }
    return (
      <>
        <Nav tenantId={session?.tenantId} />
        <StatusUnavailableView tenantId={session?.tenantId} />
      </>
    );
  }
  const p: Portfolio = pRes.value;
  const killSwitch: AccountKillSwitch | null =
    ksRes.status === "fulfilled" ? ksRes.value : null;


  // Fail-soft and outside the reads above: the readiness notice is advisory, so a strategy-config
  // outage drops the notice rather than the page.
  const shortfalls = await getStrategyConfig()
    .then((r) => levelShortfalls(p.account_equity, r.items))
    .catch(() => []);

  // Built here, not in the view: the reset control carries this page's server action.
  const resetControl = killSwitch?.tripped ? (
    <AccountKillSwitchReset
      trippedAt={killSwitch.trippedAt}
      resettableAt={killSwitch.resettableAt}
      action={resetKillSwitchAction}
      writeEnabled={RESET_WRITE_ENABLED}
      openPositions={killSwitch.openPositions ?? null}
      openMtm={killSwitch.openMtm ?? null}
    />
  ) : null;

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <StatusView
        tenantId={session?.tenantId}
        p={p}
        killSwitch={killSwitch}
        shortfalls={shortfalls}
        notice={searchParams.killswitch}
        resetControl={resetControl}
      />
    </>
  );
}
