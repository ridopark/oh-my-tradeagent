import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { LiveAccount } from "@/components/LiveAccount";
import { contractCell } from "@/components/ContractLink";
import { pnlCell, priceCell, fmtCurrency } from "@/components/Pnl";
import Link from "next/link";
import {
  getOrders,
  getPortfolio,
  getTrades,
  getStrategyConfig,
  NotAuthenticatedError,
  type Order,
  type Portfolio,
  type Trade,
  type StrategyConfigItem,
} from "@/lib/bff";

export const dynamic = "force-dynamic";

const ACTIVITY_LIMIT = 5;

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

  // Daily-loss protection card — optional context; degrade to a neutral state (never blanks the
  // page) if the config read fails. Live tripped state + the reset itself live on /status.
  let dailyLossLimit: AccountLossLimit | null = null;
  try {
    dailyLossLimit = accountLossLimit((await getStrategyConfig()).items);
  } catch {
    dailyLossLimit = null;
  }

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

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto flex max-w-6xl flex-col gap-8 px-4 py-6">
        <div>
          <h1 className="mb-1 text-xl font-semibold text-slate-100">Live</h1>
          <p className="text-sm text-slate-400">
            Account equity over time, your open holdings, and recent activity. The account total is an
            account-level (shared) value, not your tenant&apos;s slice.
          </p>
        </div>

        <LiveAccount accountValue={accountValue} accountScope={portfolio.account_equity_scope} />

        <DailyLossProtection limit={dailyLossLimit} />

        <section>
          <h2 className="mb-2 text-sm font-semibold text-slate-200">
            Holdings ({count})
          </h2>
          <DataTable
            empty="No open positions."
            columns={[
              { key: "contract_symbol", label: "Contract", render: contractCell },
              { key: "remaining_qty", label: "Qty" },
              { key: "entry_premium", label: "Entry premium" },
              { key: "current_price", label: "Current mark", render: priceCell },
              { key: "unrealized_intraday_pl", label: "P&L (today)", render: pnlCell },
              { key: "unrealized_pl", label: "P&L (total)", render: pnlCell },
            ]}
            rows={portfolio.open_positions}
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

// The tenant's account-level daily-loss limit, read from strategy config. Precedence matches the
// AccountKillSwitch workflow: a percent-of-start-of-day-equity cap wins when set, else the absolute
// dollar cap; `none` when neither is configured (the account guard is inert). `null` = read failed.
type AccountLossLimit =
  | { kind: "abs"; usd: number }
  | { kind: "pct"; pct: number }
  | { kind: "none" };

function toNumber(v: unknown): number | null {
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function accountLossLimit(items: StrategyConfigItem[]): AccountLossLimit {
  // Precedence mirrors the AccountKillSwitch workflow: a percent-of-SOD-equity cap wins over the
  // absolute dollar cap. Scan ALL strategies for a pct first, then for an absolute, so item order
  // never lets an earlier strategy's absolute cap shadow a later one's pct.
  for (const it of items) {
    const pct = toNumber(it.config["account_daily_loss_pct"]);
    if (pct != null && pct > 0) return { kind: "pct", pct };
  }
  for (const it of items) {
    const abs = toNumber(it.config["account_daily_loss_threshold"]);
    if (abs != null && abs > 0) return { kind: "abs", usd: abs };
  }
  return { kind: "none" };
}

// Informational: explains what the account daily-loss circuit breaker does (flatten + halt) and how
// resetting works. Purely read-only — the reset itself lives on /status. `limit === null` means the
// config read degraded; render a neutral "unavailable" line rather than implying no protection.
function DailyLossProtection({ limit }: { limit: AccountLossLimit | null }) {
  return (
    <section className="rounded border border-slate-800 bg-slate-900 px-4 py-3">
      <h2 className="text-sm font-semibold text-slate-200">
        🛡️ Daily-loss protection
      </h2>
      <p className="mt-1 text-sm text-slate-400">
        Your account has a daily loss limit that acts as a circuit breaker. If your losses reach it
        during a trading day, the system automatically:
      </p>
      <ul className="mt-2 space-y-1 text-sm text-slate-300">
        <li>
          <span className="font-medium text-slate-200">
            Closes all open positions at market
          </span>{" "}
          (flattens them) — immediately.
        </li>
        <li>
          <span className="font-medium text-slate-200">Stops opening new positions</span> for the
          rest of the day.
        </li>
      </ul>
      <p className="mt-2 text-sm text-slate-400">
        Trading stays halted until the switch is reset. You can reset it yourself from the{" "}
        <Link href="/status" className="text-sky-400 hover:text-white">
          Status
        </Link>{" "}
        page once a <span className="font-medium text-slate-200">15-minute cool-off</span> has
        passed — but a reset only sticks if your account has recovered above the limit; if
        you&apos;re still in breach it re-halts within about a minute.
      </p>

      <div className="mt-3 border-t border-slate-800 pt-3 text-sm">
        <DailyLossLimitLine limit={limit} />
      </div>

      <p className="mt-2 text-xs text-slate-500">
        Outside a daily-loss trip, open positions are normally held overnight (unless your strategy
        has end-of-day flatten enabled).
      </p>
    </section>
  );
}

function DailyLossLimitLine({ limit }: { limit: AccountLossLimit | null }) {
  if (limit === null) {
    return (
      <span className="text-slate-500">Daily-loss limit unavailable right now.</span>
    );
  }
  if (limit.kind === "abs") {
    return (
      <span className="text-slate-300">
        Your account daily-loss limit:{" "}
        <span className="font-semibold text-slate-100">{fmtCurrency(limit.usd)}</span> (realized +
        open P&amp;L).
      </span>
    );
  }
  if (limit.kind === "pct") {
    return (
      <span className="text-slate-300">
        Your account daily-loss limit:{" "}
        <span className="font-semibold text-slate-100">{limit.pct}%</span> of start-of-day equity.
      </span>
    );
  }
  return (
    <span className="text-slate-500">
      No account-wide daily-loss limit is currently set.
    </span>
  );
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
