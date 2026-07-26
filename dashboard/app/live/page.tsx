import { auth } from "@/auth";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { DataTable, type Column } from "@/components/DataTable";
import { LiveAccount } from "@/components/LiveAccount";
import { AccountGuardBanner } from "@/components/AccountGuardBanner";
import { contractCell } from "@/components/ContractLink";
import { pnlCell, priceCell, fmtCurrency } from "@/components/Pnl";
import {
  ForceExitButton,
  type ForceExitActionResult,
} from "@/components/ForceExitButton";
import Link from "next/link";
import {
  getOrders,
  getPortfolio,
  getTrades,
  getTenantConfig,
  getAccountKillSwitch,
  forcePositionExit,
  NotAuthenticatedError,
  type Order,
  type Portfolio,
  type Trade,
  type TenantConfig,
  type AccountKillSwitch,
} from "@/lib/bff";

export const dynamic = "force-dynamic";

const ACTIVITY_LIMIT = 5;

// Dark-by-default: the per-position Force-exit button only renders when this flag is explicitly
// "true". Unset/anything-else => /live is byte-identical to today (no actions column). Paired with
// the BFF's own `positions.force-close.write-enabled` server flag (which 404s the route when off),
// so BOTH must be enabled for a force-exit to actually reach Temporal.
const FORCE_EXIT_WRITE_ENABLED =
  process.env.FORCE_EXIT_WRITE_ENABLED === "true";

// Inline server action: re-verifies the session, threads the verified operator email into the BFF
// force-close call (X-Operator-Id → audit attribution), and revalidates /live so a placed/cleared
// position drops out of the holdings table. Returns a typed result to the client island so the row
// can show a terminal outcome inline; only revalidates when the position should now be gone (an
// error leaves the row so the failure note persists). Co-located with the page so it captures nothing
// but the request-scoped session.
async function forceExitAction(
  workflowId: string,
): Promise<ForceExitActionResult> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return { ok: false, kind: "error" };
  }
  // Fall back to name when the session carries no email (dev / AUTH_DEV_TENANT path) so the BFF
  // records something as the actor rather than an empty "tenant:<t>:".
  const operator = s.user?.email ?? s.user?.name ?? undefined;
  const r = await forcePositionExit(
    workflowId,
    "operator force-exit via /live",
    operator,
  );
  if (r.ok) {
    revalidatePath("/live");
    return { ok: true };
  }
  if (r.alreadyClosed) {
    revalidatePath("/live");
    return { ok: false, kind: "already-closed" };
  }
  if (r.disabled) {
    return { ok: false, kind: "disabled" };
  }
  return { ok: false, kind: "error" };
}

// Robinhood-style account view: account-total header + range-aware +$X (Y%) and the equity chart
// (both client-side, sharing one history fetch via LiveAccount), then the open holdings and a recent
// activity strip. The chart's history is a READ-ONLY account-level (shared) proxy — no money path.
export default async function LivePage() {
  const session = await auth();

  // Holdings + activity come from the existing server-only BFF reads. A non-auth failure means a data
  // outage (orchestrator restarting) — render an "unavailable" panel at HTTP 200 with the Nav intact
  // so the kill switch stays reachable, exactly like /status (#428).
  let portfolio: Portfolio;
  let trades: Trade[];
  let orders: Order[];
  try {
    const [p, t, o] = await Promise.all([
      getPortfolio(),
      getTrades(ACTIVITY_LIMIT),
      getOrders(ACTIVITY_LIMIT),
    ]);
    portfolio = p;
    trades = t.items;
    orders = o.items;
  } catch (err) {
    if (err instanceof NotAuthenticatedError) {
      throw err; // not a data outage — let the auth flow handle it.
    }
    return <LiveUnavailable tenantId={session?.tenantId} />;
  }

  // Daily-loss protection card — per-strategy limits + the account-wide cap. Fetched together
  // (independent reads); each degrades to null on failure so the card stays neutral rather than
  // blanking the page.
  // The tenant's account-wide daily-loss cap is the single loss rule (the per-strategy
  // daily_loss_threshold was retired by the single-account-loss-rule epic). Degrade to null on
  // failure so the card stays neutral rather than blanking the page.
  const tenantConfig: TenantConfig | null = await getTenantConfig().catch(() => null);

  // Account kill-switch state — read INDEPENDENTLY (its own degrade) so a kill-switch read failure
  // logs and renders no banner rather than blanking /live (mirrors /status). The tripped state is
  // already wired end-to-end; this is frontend reuse, zero backend.
  const killSwitch: AccountKillSwitch | null = await getAccountKillSwitch().catch(
    (err) => {
      console.error(
        "getAccountKillSwitch failed; rendering /live without the guard banner",
        err,
      );
      return null;
    },
  );
  const guardState: "tripped" | "healthy" = killSwitch?.tripped
    ? "tripped"
    : "healthy";

  const count = portfolio.open_positions_count;

  // Total account value = live net-liquidation equity (GET /v2/account), summed across the tenant's
  // broker_targets — the SAME real-time source /status uses. The chart below draws Alpaca's
  // portfolio-history series, which does NOT fold a cash deposit into equity in real time (it catches
  // up next trading day). Sourcing the headline from the live snapshot (not the chart's last point)
  // makes the total reflect deposits immediately. Seed null (not 0) so "all unavailable" renders "—".
  const accountValue = portfolio.account_equity.reduce<number | null>((sum, a) => {
    const n = a.equity == null ? NaN : Number(a.equity);
    return Number.isNaN(n) ? sum : (sum ?? 0) + n;
  }, null);

  // Live intraday "today" P&L = equity - last_equity (BFF-computed per broker_target). Fold both the
  // numerator (sum today_pl) and its pct denominator (sum last_equity) in ONE null-aware pass so the
  // header shows the GENUINE today figure, not Alpaca portfolio-history's last completed daily bar.
  // last_equity is only added when its today_pl is a real number, so the pct denominator matches the
  // numerator exactly. Null pl (→ LiveAccount falls back to the daily bar) when NO broker_target
  // carries a today_pl; null pct when the denominator isn't strictly positive.
  const today = portfolio.account_equity.reduce<{
    pl: number | null;
    base: number | null;
  }>(
    (acc, a) => {
      const pl = a.today_pl == null ? NaN : Number(a.today_pl);
      if (Number.isNaN(pl)) return acc;
      const base = a.last_equity == null ? NaN : Number(a.last_equity);
      return {
        pl: (acc.pl ?? 0) + pl,
        base: Number.isNaN(base) ? acc.base : (acc.base ?? 0) + base,
      };
    },
    { pl: null, base: null },
  );
  const todayPl = today.pl;
  const todayPlPct =
    todayPl != null && today.base != null && today.base > 0
      ? todayPl / today.base
      : null;

  // Holdings columns. The trailing per-row "Force exit" action column is appended ONLY when the dark
  // flag is on — with it off the array is identical to the pre-existing six columns, so /live renders
  // byte-for-byte as before. current_price is the broker mark; null ⇒ likely phantom (the button
  // surfaces a "clears the tracking" hint).
  const holdingsColumns: Column[] = [
    { key: "contract_symbol", label: "Contract", render: contractCell },
    { key: "remaining_qty", label: "Qty" },
    { key: "entry_premium", label: "Entry premium" },
    { key: "current_price", label: "Current mark", render: priceCell },
    { key: "unrealized_intraday_pl", label: "P&L (today)", render: pnlCell },
    { key: "unrealized_pl", label: "P&L (total)", render: pnlCell },
  ];
  if (FORCE_EXIT_WRITE_ENABLED) {
    holdingsColumns.push({
      key: "actions",
      label: "",
      render: (_v, row) => (
        <ForceExitButton
          workflowId={String(row.workflow_id)}
          symbol={String(row.contract_symbol)}
          qty={Number(row.remaining_qty)}
          hasBrokerMark={row.current_price != null}
          action={forceExitAction}
        />
      ),
    });
  }

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      {/* Full-bleed: mounted OUTSIDE <main> so the tripped bar spans the viewport edge-to-edge
          (inside main's centered max-w-6xl it would be inset and capped — not the prominent bar). */}
      <AccountGuardBanner
        state={guardState}
        reason={killSwitch?.reason}
        trippedAt={killSwitch?.trippedAt}
        resetEligibleAt={killSwitch?.resettableAt}
        openPositions={killSwitch?.openPositions ?? null}
        openMtm={killSwitch?.openMtm ?? null}
        capText={accountCapText(tenantConfig)}
      />
      <main className="mx-auto flex max-w-6xl flex-col gap-8 px-4 py-6">
        <div>
          <h1 className="mb-1 text-xl font-semibold text-slate-100">Live</h1>
          <p className="text-sm text-slate-400">
            Account equity over time, your open holdings, and recent activity. The account total is an
            account-level (shared) value, not your tenant&apos;s slice.
          </p>
        </div>

        <LiveAccount
          accountValue={accountValue}
          accountScope={portfolio.account_equity_scope}
          todayPl={todayPl}
          todayPlPct={todayPlPct}
        />

        <section>
          <h2 className="mb-2 text-sm font-semibold text-slate-200">
            Holdings ({count})
          </h2>
          <DataTable
            empty="No open positions."
            columns={holdingsColumns}
            rows={portfolio.open_positions}
            // Key rows by the stable workflow_id: the Holdings cells hold the stateful
            // ForceExitButton island, so an index key would bleed a closed row's terminal state
            // onto the position that shifts into its index after a revalidate. See DataTable.rowKey.
            rowKey={(row, i) => (row.workflow_id ? String(row.workflow_id) : i)}
          />
        </section>

        <section className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <ActivityStrip
            title="Recent trades"
            href="/trades"
            empty="No fills yet."
            rows={trades.map((t) => ({
              primary: t.kind,
              secondary: t.strategy_id,
              when: t.occurred_at,
            }))}
          />
          <ActivityStrip
            title="Recent orders"
            href="/orders"
            empty="No orders yet."
            rows={orders.map((o) => ({
              primary: `${o.side} ${o.option_symbol}`,
              secondary: o.state,
              when: o.recorded_at,
            }))}
          />
        </section>
      </main>
    </>
  );
}

// A strategy's per-day realized-loss limit (`daily_loss_threshold`, absolute USD) read from its
// strategy config. When a strategy's realized losses for the day reach it, that strategy's kill
// switch trips (flatten that strategy's positions + halt its entries). Read at the call site with a
// guard; `null` there = the config read failed.
// Human-readable account-wide cap, or null when it's unset / the read degraded. `account_daily_loss_pct`
// is a FRACTION (0.40 → "40%") of start-of-day equity; `account_daily_loss_threshold` is absolute USD
// on realized + open P&L. Both are independent knobs — show whichever is set (both, joined with "or").
function accountCapText(cfg: TenantConfig | null): string | null {
  if (cfg === null) return null;
  const parts: string[] = [];
  const pct = cfg.account_daily_loss_pct;
  if (pct != null && pct > 0) {
    parts.push(`${+(pct * 100).toFixed(2)}% of start-of-day equity`);
  }
  const usd = cfg.account_daily_loss_threshold;
  if (usd != null && usd > 0) {
    parts.push(`${fmtCurrency(usd)} (realized + open P&L)`);
  }
  return parts.length > 0 ? parts.join(" or ") : null;
}

function ActivityStrip({
  title,
  href,
  empty,
  rows,
}: {
  title: string;
  href: string;
  empty: string;
  rows: { primary: string; secondary: string; when: string }[];
}) {
  return (
    <section>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-200">{title}</h2>
        <Link href={href} className="text-xs text-slate-400 hover:text-white">
          View all →
        </Link>
      </div>
      {rows.length === 0 ? (
        <p className="text-sm text-slate-500">{empty}</p>
      ) : (
        <ul className="divide-y divide-slate-800 rounded border border-slate-800 bg-slate-900">
          {rows.map((r, i) => (
            <li
              key={i}
              className="flex items-center justify-between px-3 py-2 text-sm"
            >
              <div className="min-w-0">
                <div className="truncate text-slate-200">{r.primary}</div>
                <div className="truncate text-xs text-slate-500">{r.secondary}</div>
              </div>
              <div className="shrink-0 pl-3 text-xs text-slate-500">{r.when}</div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// Degraded render when the BFF reads fail outright (unreachable / timed out). Keeps the page at HTTP
// 200 with the Nav intact so the operator can still reach the kill switch, rather than a hard 500.
function LiveUnavailable({ tenantId }: { tenantId?: string }) {
  return (
    <>
      <Nav tenantId={tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Live</h1>
        <p className="mb-4 text-sm text-slate-400">Tenant {tenantId}</p>
        <div className="rounded border border-amber-600/60 bg-amber-950/40 px-4 py-3">
          <div className="text-sm font-semibold text-amber-300">
            Live account view temporarily unavailable
          </div>
          <div className="mt-1 text-xs text-amber-200/80">
            The data service didn&apos;t respond in time (the orchestrator may be restarting). Refresh
            in a moment. This does not affect trading or the kill switch.
          </div>
        </div>
      </main>
    </>
  );
}
